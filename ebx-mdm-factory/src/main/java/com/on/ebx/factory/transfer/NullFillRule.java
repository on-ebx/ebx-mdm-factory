package com.on.ebx.factory.transfer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.quality.ChildKeysDerivation;
import com.on.ebx.factory.quality.ScriptDerivation;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.TechnicalKeyRule;

/**
 * The loader-defect fix (review-quality lot, task A, 2026-09-21): computes which staging fields a feed's row
 * must write an explicit {@code null} for, because THIS feed structurally does not supply them any more - not
 * because a specific row happened to have no value this time (the per-row computation in {@code
 * ebx.StagingLoader#processRow} already handles that case correctly, by producing a {@code null} xs-string
 * for a blank/absent landing value - target-mapping lot 1, {@code MappingEngine.compute}'s own {@code
 * Values.get}).
 * <p>
 * <b>The bug this fixes</b>: {@code StagingLoader#upsertOne} writes only the fields present in a row's
 * computed field map, on both create AND update. A create is unaffected (a new EBX occurrence already
 * defaults every field to {@code null}) but an UPDATE of an already-existing staging row left every field
 * this feed's mapping does not CURRENTLY produce entirely untouched - so a mapping a person REJECTS (after it
 * was once ACCEPTED and had already written a value on every row of that feed) leaves that stale value on the
 * row forever, silently, on every later load: nothing in the transfer engine ever compares what a field USED
 * to hold against what it holds now. Real case that surfaced this: {@code Account.ACCOUNT_TYPE ->
 * Company.legalForm} and {@code Account.SIREN -> Company.vatNo} were accepted, ran once, then rejected -
 * {@code Company.legalForm}/{@code vatNo} kept their old CRM-sourced values on every existing {@code Account}
 * row forever after, on every re-run of {@code staging/load}.
 * <p>
 * <b>The criterion is "not supplied by THIS row's own feed", never "not supplied by the canonical entity at
 * all"</b>: a canonical (language-model) entity can be fed by more than one member ({@code
 * transfer.TransferPlan}'s own Javadoc, "One entity, one or several landing feeds"), each producing its own
 * DISJOINT set of staging rows - one row per landing row, keyed by that row's own {@code sourceSystem}/{@code
 * sourceId}, which differs by member (a different source system entirely). A row written by feed A must never
 * be nulled for an attribute only feed B happens to supply - it was never feed A's to supply in the first
 * place, and nulling it there would be exactly as wrong as leaving a stale value stale. So {@link #defaultsFor}
 * is scoped to ONE feed's own coverage at a time, meant to be called ONCE per {@code TransferPlan.Feed} (the
 * answer never depends on a specific row's own values) and reused, unchanged, for every row that feed
 * contributes - "per row, not per entity" in the sense that every row from feed A gets feed A's own answer,
 * never the union across every feed of the entity.
 * <p>
 * <b>Key and technical fields are never in scope here, by construction, not by caller discipline alone</b>:
 * this method filters {@code pEntityAttributes} itself to accepted-like, non-{@code primaryKey} attributes -
 * the exact same partition {@code staging.StagingModel#partition} (private there) applies before building a
 * table field for an attribute. The entity's primary key ({@code sourceSystem}/{@code sourceId}) and the four
 * technical bookkeeping fields ({@code _loadId}/{@code _receivedAt}/{@code _status}/{@code _rejectReason}) are
 * written unconditionally by {@code StagingLoader#upsertOne} itself, outside this class, and can never appear
 * in this method's output.
 * <p>
 * <b>A foreign-key {@code &lt;attribute&gt;Ref} field is nulled exactly when its own plain attribute is</b>,
 * whether the reference is immediate or deferred: an attribute not fed by this feed can never resolve a
 * reference for it either. This matters specifically for a DEFERRED reference (a self reference, or an edge
 * inside an unresolved cycle): {@code StagingLoader#resolveDeferredReferences} (pass 2) reads the PLAIN
 * attribute value off the already-staged row to decide whether to resolve anything at all, and skips a row
 * whose plain value is {@code null} without ever touching its {@code Ref} field - so a {@code Ref} field left
 * un-nulled by THIS class would never be cleared by anything downstream either, deferred or not.
 * <p>
 * Pure: no EBX class, no I/O - a plain set-difference plus one nullable lookup per attribute, unit-tested
 * without a running instance.
 */
public final class NullFillRule
{
	private NullFillRule()
	{
	}

	/**
	 * @param pEntityAttributes every {@code StagingAttribute} of the entity, whatever its own status or
	 *            {@code primaryKey} flag - this method does the same accepted-like/non-key filtering {@code
	 *            StagingModel.partition} does, so the caller does not have to pre-filter (and cannot get it
	 *            wrong the same way twice); {@code null} reads as empty
	 * @param pForeignKeyFieldByAttribute {@code attributeName -> <attribute>Ref field name}, this entity's
	 *            entries only ({@code StagingModel.ForeignKeyInfo}, filtered by the caller to one entity) - an
	 *            attribute absent from this map is not a foreign key; {@code null} reads as empty
	 * @param pFeedAttributeNames the stagingField names THIS feed's own {@code TransferPlan.FieldPlan} list
	 *            covers ({@code field.stagingField} for every field of {@code TransferPlan.Feed#fields}) -
	 *            whether or not a given ROW's own transform happens to produce a value for one of them;
	 *            {@code null} reads as "covers nothing"
	 * @return one {@code null} entry per attribute (and, where one exists, its {@code Ref} field) this feed
	 *         does not cover - meant to seed a row's field map BEFORE the feed's own per-field computation
	 *         overwrites whatever it does cover (a plain {@code Map.put} on the same key); empty (never null)
	 *         when the feed covers everything, or when the entity has no accepted-like business attribute at
	 *         all
	 */
	public static Map<String, Object> defaultsFor(final List<StagingModel.AttributeInfo> pEntityAttributes,
		final Map<String, String> pForeignKeyFieldByAttribute, final Set<String> pFeedAttributeNames)
	{
		final Map<String, Object> out = new LinkedHashMap<String, Object>();
		if (pEntityAttributes == null)
		{
			return out;
		}
		for (final StagingModel.AttributeInfo attribute : pEntityAttributes)
		{
			if (attribute.primaryKey || !TechnicalKeyRule.isAcceptedLike(attribute.status))
			{
				// key/technical fields have no decision of their own and are never touched here (StagingLoader
				// writes them unconditionally, outside this class); a not-yet-accepted attribute has no
				// generated field to clear in the first place - StagingModel never built one for it
				continue;
			}
			final boolean unappliedScript = ScriptDerivation.DERIVATION_SCRIPT.equals(attribute.derivation) && !attribute.scriptApplied;
			if (ScriptDerivation.DERIVATION_SCRIPT.equals(attribute.derivation) && !unappliedScript)
			{
				// script-backed-derivation lot, 2026-09-23, reworked by lot T1 (2026-10-04): this field is a stored value kept up
				// to date BY EBX (a beforeCreate and a beforeModify table trigger, ScriptDerivation's own Javadoc). A null seeded
				// here would be written into the row's NEW record, where the modify trigger would see the derived field differ
				// and recompute it anyway - but never writing it keeps the loader's write minimal and the value stable. No mapping
				// ever "covers" it either, so without this exclusion every feed would seed it null on every row, unconditionally.
				continue;
			}
			// lot M5 (2026-10-04): a script derivation whose function is NOT applied has no computing function, so its field is an ordinary
			// one - it is held EMPTY (an explicit null on every row this feed writes, clearing a value an older load copied into it),
			// never left to hold a stale copy of its source. It falls through to the null seeding below ("not supplied by this feed").
			if (ChildKeysDerivation.DERIVATION_CHILD_KEYS.equals(attribute.derivation))
			{
				// lot M3, 2026-10-03: a CHILD_KEYS attribute (the keys of the child records, on the parent) is written by the post-pass of
				// ebx.StagingLoader, never by a feed. A parent row REWRITTEN by its feed (changed landing row, scope all) must keep what it has -
				// seeding a null here would erase the keys, and nothing would put them back when no child changed in this run.
				continue;
			}
			if (pFeedAttributeNames != null && pFeedAttributeNames.contains(attribute.name))
			{
				// this feed supplies it: the row's own per-field computation already overwrites whatever this
				// seeds, so seeding a null here would only be dead weight, never wrong - but omitting it keeps
				// the output exactly "what must be forced null", nothing else
				continue;
			}
			out.put(attribute.name, null);
			final String foreignKeyField = pForeignKeyFieldByAttribute == null ? null : pForeignKeyFieldByAttribute.get(attribute.name);
			if (foreignKeyField != null)
			{
				out.put(foreignKeyField, null);
			}
		}
		return out;
	}
}
