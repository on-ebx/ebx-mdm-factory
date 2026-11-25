package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.transfer.TransferPlan;

/**
 * The staging -&gt; master transfer plan of one project: which promoted entity to write first (parents
 * before children, so a foreign key can always be resolved against an already-transferred parent - the same
 * ordering concern {@code transfer.TransferPlan} already solved for landing -&gt; staging), and, per entity,
 * exactly which staging field feeds which master field. Reuses {@link TransferPlan#loadOrder} directly
 * rather than re-deriving a topological sort a second time (it is already public and general-purpose: field
 * names, not landing-specific concepts) - `docs/MASTER-ZONE.md`'s own recommendation ("the sibling of
 * TransferPlan.loadOrder/ForeignKeyPlan ... not to invent").
 * <p>
 * <b>Much simpler than {@code transfer.TransferPlan}</b>: a promoted entity has exactly ONE source, its own
 * staging table - never several feeds the way a canonical staging entity is fed by each of its members
 * (`docs/MASTER-ZONE.md` §2.3/§2.4: "rows of a canonical entity come from its own staging table, not from its
 * members'" - the members were already folded into the canonical entity's OWN staging rows, at the earlier
 * staging transfer). No transformation either: a staging value is already typed and clean, so
 * {@code ebx.MasterLoader} only ever copies it across, field by field - unlike
 * {@code transfer.Transformations}, this plan carries no transformation/argument at all.
 * <p>
 * <b>Foreign keys are already redirected</b> by {@link MasterModel}: {@link FieldPlan#targetEntityName} (when
 * {@link FieldPlan#isForeignKey}) always names a PROMOTED entity - the canonical one, when the original
 * {@code StagingAttribute.referenceEntity} pointed at a member. {@code ebx.MasterLoader} resolves the actual
 * master id at load time through {@code Xref}, keyed by (project, stagingTable = {@link FieldPlan#targetEntityName},
 * the CHILD's own staging field value unchanged) - see {@link MasterModel}'s own Javadoc for why that staging
 * key value, computed once by the earlier staging transfer, is reusable unmodified against the redirected
 * target's Xref rows.
 * <p>
 * <b>Self references and cycles among promoted entities are DETECTED, never silently ignored</b> - reusing
 * {@link TransferPlan.LoadOrder#deferred} (the exact same "self reference, or an edge inside an unresolved
 * cycle" classification {@code transfer.TransferPlan} already computes for landing -&gt; staging, not
 * re-derived here): a FK edge {@link TransferPlan#loadOrder} classifies as deferred marks its
 * {@link FieldPlan#deferred} true and adds a note naming it. {@code ebx.MasterLoader} must never attempt to
 * resolve a deferred field during the entity's own first pass (the target row - possibly the SAME entity's own
 * row - does not necessarily have a master key yet); a second pass, after every promoted entity has completed
 * its first pass, resolves it the same way an immediate reference is resolved, just later.
 * <p>
 * Pure: no EBX class anywhere in this file, unit-tested without a running instance, the same convention as
 * {@code transfer.TransferPlan} and {@link MasterModel} itself.
 */
public final class MasterTransferPlan
{
	private MasterTransferPlan()
	{
	}

	// -------------------------------------------------------------------------------------------------------- output

	/** One field of one promoted entity: what {@code ebx.MasterLoader} reads a staging value from, and what master field it writes. */
	public static final class FieldPlan
	{
		public final String masterField;
		/**
		 * When {@link #isForeignKey} is false: the exact staging FIELD name to read (identical to {@link
		 * StagingModel}'s own plain-attribute naming - never renamed). When {@link #isForeignKey} is true: the
		 * business ATTRIBUTE name, not a field name - {@code ebx.MasterLoader} resolves it to the actual staging
		 * {@code <attribute>Ref} FIELD name itself (which can differ from {@code attributeName + "Ref"} on a rare
		 * collision - the same reason {@code transfer.TransferPlan.MappingInfo} carries {@code foreignKeyField}
		 * as a value computed once by {@code StagingModel.build}, never re-derived). Kept as the attribute name
		 * here, not the field name, so this pure class never has to depend on {@code staging.StagingModel} to
		 * compute it.
		 */
		public final String stagingField;
		public final String dataType;
		public final boolean isForeignKey;
		/** Non-null only when {@link #isForeignKey}: the PROMOTED (already redirected) entity this field targets. */
		public final String targetEntityName;
		/** True when {@link #isForeignKey} AND this edge is a self reference or part of a cycle among promoted entities: never resolved in the entity's own first pass, only in {@code ebx.MasterLoader}'s second pass - see the class Javadoc. Always false when {@link #isForeignKey} is false. */
		public final boolean deferred;

		FieldPlan(final String pMasterField, final String pStagingField, final String pDataType, final boolean pIsForeignKey, final String pTargetEntityName,
			final boolean pDeferred)
		{
			this.masterField = pMasterField;
			this.stagingField = pStagingField;
			this.dataType = pDataType;
			this.isForeignKey = pIsForeignKey;
			this.targetEntityName = pTargetEntityName;
			this.deferred = pDeferred;
		}
	}

	/** One promoted entity of the plan, in load order (parents before children). */
	public static final class Entity
	{
		public final String name;
		public final List<FieldPlan> fields;

		Entity(final String pName, final List<FieldPlan> pFields)
		{
			this.name = pName;
			this.fields = pFields;
		}
	}

	public static final class Result
	{
		/** Every promoted entity, parents before children. */
		public final List<Entity> loadOrder;
		/** Entities the topological sort could not place on its own (a real multi-entity cycle) - still present in {@link #loadOrder}, appended at the end, same convention as {@code transfer.TransferPlan.Result#cycleEntities}. */
		public final List<String> cycleEntities;
		public final List<String> notes;

		Result(final List<Entity> pLoadOrder, final List<String> pCycleEntities, final List<String> pNotes)
		{
			this.loadOrder = pLoadOrder;
			this.cycleEntities = pCycleEntities;
			this.notes = pNotes;
		}
	}

	// --------------------------------------------------------------------------------------------------------- plan

	public static Result plan(final MasterModel.Result pMasterModel)
	{
		final Map<String, List<FieldPlan>> fieldsByEntity = new LinkedHashMap<String, List<FieldPlan>>();
		for (final String entityName : pMasterModel.builtEntities)
		{
			fieldsByEntity.put(entityName, new ArrayList<FieldPlan>());
		}

		// the load order (and which edges it could not place strictly parent-first) is computed BEFORE building
		// any FieldPlan, so a FK field's own deferred flag can be set from it directly - see the class Javadoc
		final List<TransferPlan.Edge> edges = new ArrayList<TransferPlan.Edge>();
		final List<String> notes = new ArrayList<String>(pMasterModel.notes);
		for (final MasterModel.ForeignKeyInfo fk : pMasterModel.foreignKeys)
		{
			if (!fieldsByEntity.containsKey(fk.targetEntityName))
			{
				notes.add(fk.entityName + "." + fk.attributeName + ": target entity " + fk.targetEntityName
					+ " has no field mapping in this transfer plan - the reference will be left unresolved.");
				continue;
			}
			edges.add(new TransferPlan.Edge(fk.entityName, fk.targetEntityName));
		}
		final TransferPlan.LoadOrder order = TransferPlan.loadOrder(new ArrayList<String>(fieldsByEntity.keySet()), edges);

		for (final MasterModel.FieldInfo field : pMasterModel.fields)
		{
			final List<FieldPlan> fields = fieldsByEntity.get(field.entityName);
			if (fields == null) // defensive: a field of an entity that somehow did not make it into builtEntities
			{
				continue;
			}
			fields.add(new FieldPlan(field.masterField, field.stagingField, field.dataType, false, null, false));
		}
		for (final MasterModel.ForeignKeyInfo fk : pMasterModel.foreignKeys)
		{
			final List<FieldPlan> fields = fieldsByEntity.get(fk.entityName);
			if (fields == null)
			{
				continue;
			}
			final boolean deferred = order.deferred.contains(fk.entityName + "->" + fk.targetEntityName);
			if (deferred)
			{
				notes.add(fk.entityName + "." + fk.fieldName + ": "
					+ (fk.entityName.equals(fk.targetEntityName) ? "a self reference" : "part of a cycle among promoted entities")
					+ " - resolved in a second pass, once every promoted entity has a master key.");
			}
			fields.add(new FieldPlan(fk.fieldName, fk.attributeName, "STRING", true, fk.targetEntityName, deferred));
		}

		final List<Entity> entities = new ArrayList<Entity>();
		for (final String entityName : order.order)
		{
			final List<FieldPlan> fields = fieldsByEntity.get(entityName);
			if (fields == null) // an entity only ever seen as a FK target, never itself promoted - should not happen (redirection only ever targets a promoted entity), defensive
			{
				continue;
			}
			entities.add(new Entity(entityName, fields));
		}
		return new Result(entities, order.cycleEntities, notes);
	}
}
