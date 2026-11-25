package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The staging data model of one project, as a pure function of its ACCEPTED (or already {@code APPLIED})
 * entities and attributes: a {@code PROPOSED} or {@code REJECTED} line is never generated (PLAN.md,
 * "Suggestion du staging" / the 2026-09-20 staging pass, "le modèle propose, l'humain accepte, le moteur
 * applique"). One table per entity, one field per accepted attribute, typed from {@code dataType}; the
 * table's primary key is the {@code primaryKey}-flagged attributes ({@code sourceSystem}, {@code sourceId},
 * in that fixed order - see {@code StagingAttribute.primaryKey}), followed by the technical bookkeeping
 * fields every staging table gets: {@code _loadId} (string), {@code _receivedAt} (dateTime), {@code _status}
 * (string), {@code _rejectReason} (text), {@code _sourceHash} (string - added 2026-09-23, the incremental-
 * transfer lot, {@code docs/INCREMENTAL-TRANSFER.md}: the landing row's own {@code _rowHash} the staging row
 * was last (re)built from, what {@code transfer.IncrementalTransfer#shouldWrite}/{@code ebx.StagingLoader}
 * compare against to skip an unchanged row's write under scope {@code new}).
 * <p>
 * {@link #build} gives both the {@code build_data_model} tree ({@link Result#tree}) and the ordered
 * {@code apply_data_model_patch} operations ({@link Result#operations}) for what the tree cannot carry -
 * {@code maxLength}, {@code mandatory} (together with {@code minOccurs=1}: {@code mandatory} alone only
 * writes {@code osd:checkNullInput}, the model would validate and then fail to compile) - measured live
 * against a real EBX instance, 2026-09-20 ({@code build_data_model(dry_run=true)} on a throwaway two-table
 * probe): a plain field's {@code mandatory}/{@code maxLength}/{@code minOccurs} in the tree's {@code
 * constraints} block come back {@code TREE_FACET_NOT_LOWERED} every time, exactly as the knowledge base
 * already documents for the landing model's own facets. A primary-key field needs no such op: listing it in
 * the table's {@code primaryKey} array is enough for {@code build_data_model} to set {@code minOccurs=1} on
 * it by itself (confirmed on the same probe, and independently on the deployed landing model's own
 * {@code _sourceId}) - so this class never puts a primary-key field in {@code constraints} either.
 * <p>
 * <b>Foreign keys, and the one place this class disagrees with its own brief:</b> an accepted attribute with
 * an accepted {@code referenceEntity} gets a generated field {@code <attribute>Ref} (string, optional -
 * {@code minOccurs=0}, since an EBX foreign key is BLOCKING on save and a parent not yet loaded, or a real
 * orphan, must never refuse the child row - PLAN.md, "Relations entre objets sources") holding the parent's
 * composite staging key in EBX's own {@code system|id} format, while the original attribute is kept as plain
 * data (untouched, still carrying the source's raw value). The brief assumed this FK would need a separate
 * {@code add_foreign_key} patch op after the build, the way the landing model's facets need {@code
 * apply_data_model_patch}; the SAME live probe that confirmed the facet behaviour above also built a field
 * with {@code isForeignKey: true} / {@code foreignKeyTargetPath} pointing at a table with a COMPOSITE primary
 * key, directly in the {@code build_data_model} tree - it compiled clean, {@code foreignKeyCount: 1}, no
 * {@code TREE_FACET_NOT_LOWERED} for it at all. So the FK is lowered straight from {@link Result#tree} here,
 * never as a patch operation: fewer round trips, and the atomic build either creates every table and every
 * FK together or none of them (an {@code add_foreign_key} op would only be needed for a FK into a table the
 * SAME atomic build did not also create, which never happens here - every entity's table is built in the
 * one {@link #build} call). A reference whose target entity was itself skipped (see below) is not built
 * either: the original attribute stays, plain, and the skip is reported in {@link Result#notes}.
 * <p>
 * <b>The technical key (a {@code primaryKey=true} attribute) has no decision of its own</b> - {@link
 * TechnicalKeyRule}, added after a coordinator review, 2026-09-20, of a live refusal on project {@code
 * Companies}: an entity's {@code sourceSystem}/{@code sourceId} attributes are included here purely
 * because their ENTITY is accepted-like, whatever their own stored status happens to be (a fresh proposal
 * still {@code PROPOSED}, or an older row a backfill has not reached yet - see {@code
 * StagingSuggester}/{@code ProposalReviews} for what keeps that status coherent going forward). An accepted
 * entity with no OTHER (non-key) accepted attribute is still skipped rather than turned into a key-only
 * table - reported in {@link Result#skippedEntities}, naming the entity and how many attributes are still
 * waiting for review; the rarer case of an accepted entity with no {@code primaryKey} attribute row AT ALL
 * (the staging suggestion never created one) is skipped too. Nothing destructive either way.
 * <p>
 * <b>A canonical (language-model) entity and one of its members can both be accepted at once</b> - this
 * class does NOT decide, silently, that the canonical entity makes the member's own table unnecessary (a
 * coordinator review, 2026-09-20, was explicit that this must not be an automatic decision): every
 * accepted entity is generated as its own table regardless of whether it is somebody else's {@code
 * EntityInfo#memberEntities}, and the overlap is only ever surfaced as a note in {@link Result#notes}
 * ({@code "<Canonical> and its member <Member> are both accepted: both tables are generated; decide which
 * one feeds the master."}) for a person to act on.
 * <p>
 * Pure: no EBX class anywhere in this file - Jackson only, the same as {@code LandingModel}'s own {@code
 * tree()} - so this is unit-tested without an EBX runtime.
 */
public final class StagingModel
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** One string field holding a composite parent key in EBX's own {@code system|id} format: generous on purpose, never profiled. */
	private static final int FOREIGN_KEY_FIELD_MAX_LENGTH = 500;

	private static final String LOAD_ID = "_loadId";
	private static final String RECEIVED_AT = "_receivedAt";
	private static final String STATUS = "_status";
	private static final String REJECT_REASON = "_rejectReason";
	/** 2026-09-23, incremental-transfer lot: the landing row's own {@code _rowHash} the staging row was last (re)built from - see the class Javadoc. */
	private static final String SOURCE_HASH = "_sourceHash";

	private StagingModel()
	{
	}

	// --------------------------------------------------------------------------------------------------------- input

	/** One ACCEPTED-or-not {@code StagingAttribute}: {@link #build} itself decides, from {@link #status}, whether it is generated at all. */
	public static final class AttributeInfo
	{
		public final String name;
		public final String label;
		/** {@code STRING}/{@code INTEGER}/{@code DECIMAL}/{@code BOOLEAN}/{@code DATE}/{@code TIMESTAMP}; blank or null reads as {@code STRING}. */
		public final String dataType;
		/** Only meaningful for {@code STRING}. */
		public final Integer maxLength;
		public final boolean mandatory;
		/** The entity's technical identity ({@code sourceSystem}/{@code sourceId}); see {@code StagingAttribute.primaryKey}'s Javadoc. */
		public final boolean primaryKey;
		/** Name of the entity this attribute is a foreign key to (an id is resolved to a name by the caller, same convention as {@code StagingRules}), or null. */
		public final String referenceEntity;
		/** {@code PROPOSED}/{@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}: only {@code ACCEPTED} and {@code APPLIED} are ever generated. */
		public final String status;
		/**
		 * {@code StagingAttribute.derivation} - null/blank for an ordinary (mapping-fed) attribute, {@code
		 * FOLD_KEY}/{@code VALID_VALUE} for one {@code staging.DerivedFieldRule} computes in Java at load time,
		 * or {@code quality.ScriptDerivation#DERIVATION_SCRIPT} (script-backed-derivation lot, 2026-09-23) for
		 * one EBX keeps up to date itself - a STORED field fed by two table triggers (lot T1, 2026-10-04: {@code beforeCreate}
		 * and {@code beforeModify}, declared by {@code ebx.DerivationApplier}) - never written by {@code
		 * ebx.StagingLoader} at all, see that class' {@code readDerivedFields}/{@code NullFillRule.defaultsFor}
		 * own exclusion of it. This field only decides ONE thing here (whether {@code NullFillRule} may force
		 * this attribute's field to {@code null} on a row this feed does not otherwise supply) - {@link #build}
		 * itself never reads it, a script-derivation field is built as a perfectly ordinary field the same way
		 * {@code FOLD_KEY}/{@code VALID_VALUE} already are (a later, separate step then declares the triggers on its table).
		 */
		public final String derivation;
		/**
		 * Lot M5 (2026-10-04): only meaningful when {@link #derivation} is {@code SCRIPT} - whether the pair of triggers that
		 * computes it is applied on the staging model ({@code staging.ScriptDerivationGate#isApplied}). Defaults to {@code true} in
		 * every constructor that does not say (what the callers before this lot assumed). When {@code false}, {@code
		 * transfer.NullFillRule} forces the field to an explicit {@code null}: an unapplied script derivation is held empty, never
		 * left with a value an older load copied into it.
		 */
		public final boolean scriptApplied;

		/** Convenience for an ordinary or Java-computed-derived attribute (every caller before this lot) - equivalent to passing a null {@link #derivation}. */
		public AttributeInfo(final String pName, final String pLabel, final String pDataType, final Integer pMaxLength, final boolean pMandatory,
			final boolean pPrimaryKey, final String pReferenceEntity, final String pStatus)
		{
			this(pName, pLabel, pDataType, pMaxLength, pMandatory, pPrimaryKey, pReferenceEntity, pStatus, null);
		}

		/** Full form - see {@link #derivation} (script-backed-derivation lot, 2026-09-23). */
		public AttributeInfo(final String pName, final String pLabel, final String pDataType, final Integer pMaxLength, final boolean pMandatory,
			final boolean pPrimaryKey, final String pReferenceEntity, final String pStatus, final String pDerivation)
		{
			this(pName, pLabel, pDataType, pMaxLength, pMandatory, pPrimaryKey, pReferenceEntity, pStatus, pDerivation, true);
		}

		/** Full form with the applied flag of a {@code SCRIPT} derivation - see {@link #scriptApplied} (lot M5, 2026-10-04). */
		public AttributeInfo(final String pName, final String pLabel, final String pDataType, final Integer pMaxLength, final boolean pMandatory,
			final boolean pPrimaryKey, final String pReferenceEntity, final String pStatus, final String pDerivation, final boolean pScriptApplied)
		{
			this.name = pName;
			this.label = pLabel;
			this.dataType = pDataType;
			this.maxLength = pMaxLength;
			this.mandatory = pMandatory;
			this.primaryKey = pPrimaryKey;
			this.referenceEntity = pReferenceEntity;
			this.status = pStatus;
			this.derivation = pDerivation;
			this.scriptApplied = pScriptApplied;
		}
	}

	/** One ACCEPTED-or-not {@code StagingEntity} and its attributes, exactly as read from EBX - status filtering happens inside {@link #build}. */
	public static final class EntityInfo
	{
		public final String name;
		public final String label;
		/** {@code PROPOSED}/{@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}: only {@code ACCEPTED} and {@code APPLIED} are ever generated. */
		public final String status;
		public final List<AttributeInfo> attributes;
		/** Names of the entities a canonical (LLM) entity unifies ({@code StagingEntity.memberEntities}, resolved to names by the caller); empty for an entity fed by one landing table. */
		public final List<String> memberEntities;

		public EntityInfo(final String pName, final String pLabel, final String pStatus, final List<AttributeInfo> pAttributes, final List<String> pMemberEntities)
		{
			this.name = pName;
			this.label = pLabel;
			this.status = pStatus;
			this.attributes = pAttributes == null ? new ArrayList<AttributeInfo>() : pAttributes;
			this.memberEntities = pMemberEntities == null ? new ArrayList<String>() : pMemberEntities;
		}
	}

	// -------------------------------------------------------------------------------------------------------- output

	/** One generated foreign-key field, for the caller's own bookkeeping (logging, {@code PipelineStep.detail}) - not needed to replay the build. */
	public static final class ForeignKeyInfo
	{
		public final String entityName;
		public final String attributeName;
		public final String fieldName;
		public final String targetEntityName;

		ForeignKeyInfo(final String pEntityName, final String pAttributeName, final String pFieldName, final String pTargetEntityName)
		{
			this.entityName = pEntityName;
			this.attributeName = pAttributeName;
			this.fieldName = pFieldName;
			this.targetEntityName = pTargetEntityName;
		}
	}

	public static final class Result
	{
		/** The {@code model_json} argument of {@code build_data_model}: every table this call actually builds, tables and fields alike. */
		public final ObjectNode tree;
		/** The {@code operations_json} argument of {@code apply_data_model_patch}, to run right after the build - possibly empty, never null. */
		public final ArrayNode operations;
		/** {@code "<EntityName>: <reason>"}: an accepted entity {@link #build} chose not to generate a table for. */
		public final List<String> skippedEntities;
		/** Every generated foreign-key field, table order then field order. */
		public final List<ForeignKeyInfo> foreignKeys;
		/** Anything else worth surfacing to the caller (a reference whose target entity was skipped: the plain attribute is kept, no FK field). */
		public final List<String> notes;
		/** Names of every entity a table was actually built for - what a caller (e.g. {@code StagingGenerator}) writes back {@code APPLIED} onto. */
		public final List<String> builtEntities;

		Result(final ObjectNode pTree, final ArrayNode pOperations, final List<String> pSkippedEntities, final List<ForeignKeyInfo> pForeignKeys,
			final List<String> pNotes, final List<String> pBuiltEntities)
		{
			this.tree = pTree;
			this.operations = pOperations;
			this.skippedEntities = pSkippedEntities;
			this.foreignKeys = pForeignKeys;
			this.notes = pNotes;
			this.builtEntities = pBuiltEntities;
		}
	}

	// --------------------------------------------------------------------------------------------------------- build

	public static Result build(final List<EntityInfo> pEntities)
	{
		final ObjectNode root = StagingModel.MAPPER.createObjectNode();
		final ArrayNode tables = root.putArray("tables");
		final ArrayNode operations = StagingModel.MAPPER.createArrayNode();
		final List<String> skipped = new ArrayList<String>();
		final List<String> notes = new ArrayList<String>();
		final List<ForeignKeyInfo> foreignKeys = new ArrayList<ForeignKeyInfo>();

		// pass 1: which entities are even candidates (accepted-like, with a business attribute and a primary key)
		final List<EntityInfo> candidates = new ArrayList<EntityInfo>();
		for (final EntityInfo entity : pEntities)
		{
			if (!TechnicalKeyRule.isAcceptedLike(entity.status))
			{
				continue;
			}
			final Partition partition = StagingModel.partition(entity);
			if (partition.keyAttributes.isEmpty())
			{
				skipped.add(entity.name + ": no primary key attribute (sourceSystem/sourceId) exists - the staging suggestion did not create one.");
				continue;
			}
			if (partition.businessAttributes.isEmpty())
			{
				skipped.add("Accept at least one attribute of " + entity.name + ": " + StagingModel.waitingBusinessAttributes(entity) + " attribute(s) waiting for review.");
				continue;
			}
			candidates.add(entity);
		}

		final Set<String> builtNames = new LinkedHashSet<String>();
		for (final EntityInfo entity : candidates)
		{
			builtNames.add(entity.name);
		}

		// this lot does NOT decide, silently, that an accepted canonical entity makes its accepted member entities'
		// own tables unnecessary: every accepted entity is generated as its own table regardless (the loop below is
		// unconditional, same as any other candidate) - only surfaced here as a note for a person to act on, per the
		// coordinator review of 2026-09-20 ("decide which one feeds the master")
		for (final EntityInfo entity : candidates)
		{
			for (final String member : entity.memberEntities)
			{
				if (builtNames.contains(member))
				{
					notes.add(entity.name + " and its member " + member + " are both accepted: both tables are generated; decide which one feeds the master.");
				}
			}
		}

		// pass 2: build each candidate's table and field-level ops, resolving a reference only against a name pass 1 actually kept
		for (final EntityInfo entity : candidates)
		{
			StagingModel.buildTable(entity, builtNames, tables, operations, foreignKeys, notes);
		}

		return new Result(root, operations, skipped, foreignKeys, notes, new ArrayList<String>(builtNames));
	}

	/** {@link #partition}'s result: an entity's technical key (unconditional once the entity itself is accepted-like) and its accepted business attributes. */
	private static final class Partition
	{
		final List<AttributeInfo> keyAttributes = new ArrayList<AttributeInfo>();
		final List<AttributeInfo> businessAttributes = new ArrayList<AttributeInfo>();
	}

	/**
	 * Splits an entity's attributes per {@link TechnicalKeyRule}: every {@code primaryKey=true} attribute
	 * goes into {@link Partition#keyAttributes} regardless of its own stored status (it has no decision of
	 * its own - only called for an entity already known accepted-like, by {@link #build}, which is what
	 * makes including every key attribute unconditionally correct here); a {@code primaryKey=false}
	 * attribute goes into {@link Partition#businessAttributes} only when its OWN status is accepted-like.
	 */
	private static Partition partition(final EntityInfo pEntity)
	{
		final Partition out = new Partition();
		for (final AttributeInfo attribute : pEntity.attributes)
		{
			if (attribute.primaryKey)
			{
				out.keyAttributes.add(attribute);
			}
			else if (TechnicalKeyRule.isAcceptedLike(attribute.status))
			{
				out.businessAttributes.add(attribute);
			}
		}
		return out;
	}

	/** How many non-key attributes of {@code pEntity} are still {@code PROPOSED} - what a "accept at least one attribute" skip message tells a person is waiting for them. */
	private static int waitingBusinessAttributes(final EntityInfo pEntity)
	{
		int waiting = 0;
		for (final AttributeInfo attribute : pEntity.attributes)
		{
			if (!attribute.primaryKey && "PROPOSED".equals(attribute.status))
			{
				waiting++;
			}
		}
		return waiting;
	}

	private static void buildTable(final EntityInfo pEntity, final Set<String> pBuiltNames, final ArrayNode pTables, final ArrayNode pOperations,
		final List<ForeignKeyInfo> pForeignKeys, final List<String> pNotes)
	{
		final String tablePath = StagingModel.tablePath(pEntity.name);
		final ObjectNode table = pTables.addObject();
		table.put("path", tablePath);
		table.put("label", pEntity.label == null || pEntity.label.trim().isEmpty() ? pEntity.name : pEntity.label);
		final ArrayNode primaryKey = table.putArray("primaryKey");
		final ArrayNode fields = table.putArray("fields");

		final Partition partition = StagingModel.partition(pEntity);
		final List<AttributeInfo> keyAttributes = partition.keyAttributes;
		final List<AttributeInfo> businessAttributes = partition.businessAttributes;
		StagingModel.sortPrimaryKeyFirst(keyAttributes);

		final Set<String> usedNames = new LinkedHashSet<String>();
		for (final AttributeInfo attribute : keyAttributes)
		{
			primaryKey.add(attribute.name);
			usedNames.add(attribute.name.toLowerCase(Locale.ROOT));
			StagingModel.field(fields, tablePath, attribute.name, StagingModel.ebxType(attribute.dataType), attribute.label);
			StagingModel.maxLengthOp(pOperations, tablePath, attribute.name, attribute);
			// no mandatory/minOccurs op here: listing the field in primaryKey already makes build_data_model set minOccurs=1 on it
		}
		for (final AttributeInfo attribute : businessAttributes)
		{
			usedNames.add(attribute.name.toLowerCase(Locale.ROOT));
		}
		for (final AttributeInfo attribute : businessAttributes)
		{
			StagingModel.field(fields, tablePath, attribute.name, StagingModel.ebxType(attribute.dataType), attribute.label);
			StagingModel.maxLengthOp(pOperations, tablePath, attribute.name, attribute);
			StagingModel.mandatoryOps(pOperations, tablePath, attribute.name, attribute);

			if (attribute.referenceEntity != null)
			{
				if (!pBuiltNames.contains(attribute.referenceEntity))
				{
					pNotes.add(pEntity.name + "." + attribute.name + ": referenced entity " + attribute.referenceEntity
						+ " was not generated (skipped): kept as plain data, no foreign key created.");
				}
				else
				{
					final String fieldName = StagingModel.foreignKeyFieldName(attribute.name, usedNames);
					final String targetTablePath = StagingModel.tablePath(attribute.referenceEntity);
					StagingModel.foreignKeyField(fields, tablePath, fieldName, attribute.label, targetTablePath);
					final ObjectNode maxLengthOp = StagingModel.setElementProperty(tablePath, fieldName, "maxLength", String.valueOf(StagingModel.FOREIGN_KEY_FIELD_MAX_LENGTH));
					pOperations.add(maxLengthOp);
					pForeignKeys.add(new ForeignKeyInfo(pEntity.name, attribute.name, fieldName, attribute.referenceEntity));
				}
			}
		}

		// technical bookkeeping fields: no accepted StagingAttribute behind these, every staging table gets them the same way
		StagingModel.field(fields, tablePath, StagingModel.LOAD_ID, "string", "Load");
		StagingModel.field(fields, tablePath, StagingModel.RECEIVED_AT, "dateTime", "Received");
		StagingModel.field(fields, tablePath, StagingModel.STATUS, "string", "Status");
		StagingModel.field(fields, tablePath, StagingModel.REJECT_REASON, "text", "Reject reason");
		// 2026-09-23, incremental-transfer lot: additive on a REGENERATE of an already-published table (StagingModelDiff
		// diffs it in as an ordinary new field, same as any other) - ebx.StagingLoader guards its own use of this field
		// with a live schema check (never assumes it exists) so a project not yet regenerated keeps working unchanged.
		StagingModel.field(fields, tablePath, StagingModel.SOURCE_HASH, "string", "Source hash");
	}

	/** {@code sourceSystem} before {@code sourceId}, per the fixed order the brief asks for; anything else (none expected today) falls back to name order. */
	private static void sortPrimaryKeyFirst(final List<AttributeInfo> pKeyAttributes)
	{
		final List<AttributeInfo> ordered = new ArrayList<AttributeInfo>();
		AttributeInfo sourceSystem = null;
		AttributeInfo sourceId = null;
		final List<AttributeInfo> others = new ArrayList<AttributeInfo>();
		for (final AttributeInfo attribute : pKeyAttributes)
		{
			if ("sourceSystem".equals(attribute.name))
			{
				sourceSystem = attribute;
			}
			else if ("sourceId".equals(attribute.name))
			{
				sourceId = attribute;
			}
			else
			{
				others.add(attribute);
			}
		}
		if (sourceSystem != null)
		{
			ordered.add(sourceSystem);
		}
		if (sourceId != null)
		{
			ordered.add(sourceId);
		}
		ordered.addAll(others);
		pKeyAttributes.clear();
		pKeyAttributes.addAll(ordered);
	}

	private static String ebxType(final String pDataType)
	{
		final String type = pDataType == null ? "" : pDataType.trim().toUpperCase(Locale.ROOT);
		if ("INTEGER".equals(type))
		{
			return "int";
		}
		if ("DECIMAL".equals(type))
		{
			return "decimal";
		}
		if ("BOOLEAN".equals(type))
		{
			return "boolean";
		}
		if ("DATE".equals(type))
		{
			return "date";
		}
		if ("TIMESTAMP".equals(type))
		{
			return "dateTime";
		}
		return "string"; // STRING, blank, or anything unrecognised
	}

	private static void field(final ArrayNode pFields, final String pTablePath, final String pName, final String pType, final String pLabel)
	{
		final ObjectNode field = pFields.addObject().putObject("field");
		field.put("name", pName);
		field.put("path", pTablePath + "/" + pName);
		field.put("type", pType);
		field.put("label", pLabel == null || pLabel.trim().isEmpty() ? pName : pLabel);
	}

	private static void foreignKeyField(final ArrayNode pFields, final String pTablePath, final String pName, final String pLabel, final String pTargetTablePath)
	{
		final ObjectNode field = pFields.addObject().putObject("field");
		field.put("name", pName);
		field.put("path", pTablePath + "/" + pName);
		field.put("type", "string");
		field.put("label", pLabel == null || pLabel.trim().isEmpty() ? pName : pLabel);
		field.put("isForeignKey", true);
		field.put("foreignKeyTargetPath", pTargetTablePath);
	}

	/** {@code set_element_property(maxLength)}, only for a STRING field that actually carries one. */
	private static void maxLengthOp(final ArrayNode pOperations, final String pTablePath, final String pFieldName, final AttributeInfo pAttribute)
	{
		if (!"STRING".equalsIgnoreCase(pAttribute.dataType == null ? "STRING" : pAttribute.dataType) || pAttribute.maxLength == null)
		{
			return;
		}
		pOperations.add(StagingModel.setElementProperty(pTablePath, pFieldName, "maxLength", String.valueOf(pAttribute.maxLength)));
	}

	/** {@code set_element_property(mandatory)} + {@code set_element_property(minOccurs=1)}, together, exactly as the tool's own answer asks for. */
	private static void mandatoryOps(final ArrayNode pOperations, final String pTablePath, final String pFieldName, final AttributeInfo pAttribute)
	{
		if (!pAttribute.mandatory)
		{
			return;
		}
		pOperations.add(StagingModel.setElementProperty(pTablePath, pFieldName, "mandatory", "true"));
		pOperations.add(StagingModel.setElementProperty(pTablePath, pFieldName, "minOccurs", "1"));
	}

	private static ObjectNode setElementProperty(final String pTablePath, final String pFieldName, final String pProperty, final String pValue)
	{
		final ObjectNode op = StagingModel.MAPPER.createObjectNode();
		op.put("op", "set_element_property");
		final ObjectNode arguments = op.putObject("arguments");
		arguments.put("element_path", pTablePath + "/" + pFieldName);
		arguments.put("property", pProperty);
		arguments.put("value", pValue);
		return op;
	}

	/** EBX compares sibling names as they are, a database does not: uniqueness is decided without the case (same convention as {@code LandingModel}). */
	private static String unique(final String pName, final Set<String> pTaken)
	{
		String name = pName;
		for (int i = 2; !pTaken.add(name.toLowerCase(Locale.ROOT)); i++)
		{
			name = pName + "_" + i;
		}
		return name;
	}

	/**
	 * The exact {@code <attribute>Ref} field name {@link #buildTable} generates for an attribute carrying a
	 * {@code referenceEntity} - extracted (target-mapping lot 2, docs/TARGET-MAPPING.md sec 4.2) so a caller
	 * that must PREDICT this name before the table is ever built (the language-model staging pass, which
	 * derives a child feed's {@code TransferFeed.parentLinkField} from the entity's own {@code
	 * parentLinkAttribute} while the entity is still {@code PROPOSED}) reuses the exact same collision-safe
	 * derivation instead of a second one that could silently drift out of step with what {@link #build}
	 * actually generates later. {@code pTaken} must hold the lower-cased names of every OTHER attribute of the
	 * same entity (technical key attributes included - see {@link #buildTable}'s own call, which seeds it the
	 * same way) so the collision check matches {@link #buildTable} exactly; it is mutated (the winning name is
	 * added to it), exactly like {@link #unique} itself, so a caller building several FK field names for the
	 * SAME entity in sequence must reuse one growing set, not a fresh one per call.
	 */
	public static String foreignKeyFieldName(final String pAttributeName, final Set<String> pTaken)
	{
		return StagingModel.unique(pAttributeName + "Ref", pTaken);
	}

	/** Where {@link #build} puts a table of the given entity name: {@code /root/<EntityName>}. What a foreign key's {@code foreignKeyTargetPath} also points at. */
	public static String tablePath(final String pEntityName)
	{
		return "/root/" + pEntityName;
	}
}
