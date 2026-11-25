package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import com.on.ebx.factory.staging.StagingModelDiff;

/**
 * Repairs a promoted master table that ALREADY EXISTS in the live model but is missing one of the two
 * per-table facets {@code ebx.MasterGenerator}'s regeneration path otherwise only ever applies to a
 * genuinely NEW table: the {@code id} field's {@code autoIncrementStart}/{@code autoIncrementStep}
 * ({@link MasterModel.Result#autoIncrementOps}) and the table's own {@code osd:table/defaultLabel}
 * ({@link MasterModel.Result#defaultLabelByTable}, applied through {@code execute_operation(set_default_label)}).
 * <p>
 * <b>The incident this closes</b> - `docs/MASTER-ZONE.md` &sect;10, MEASURED LIVE 2026-09-22 12:09-12:39: a
 * patch that failed partway through (op 14 of 28, the {@code defaultLabel}-as-{@code set_element_property}
 * bug, fixed since by {@code fix/master-default-label-op}) left an EARLIER table (Contact, ops 0-13) fully
 * patched and a LATER one (Company, ops 15-27, its own auto-increment facets included) with nothing applied
 * at all. The next {@code master/generate} then found the model already existed with BOTH tables present
 * (`get_data_model` doesn't distinguish "table created, ops applied" from "table created, ops never
 * reached") and took the REGENERATE path, whose own {@code existingNames.contains(entityName) -> continue}
 * gate skips Company because it is not a NEW table this run - so its missing auto-increment facet was never
 * revisited, and {@code master/load} then rejected all 196 rows with "primary key has unset element(s)".
 * That gate's question is "is this table new THIS RUN", never "does this table's own key facet actually
 * exist live" - this class asks the second question instead, independently of the first.
 * <p>
 * <b>Additive and idempotent either way</b>: re-applying {@code autoIncrementStart=1}/{@code
 * autoIncrementStep=1} to a table that already has them is the exact same {@code set_element_property} call
 * with the exact same value (this project's own facet value is a hardcoded constant - {@code
 * MasterModel.buildTable} never derives it from anything project-specific), and {@code set_default_label}
 * is {@code ADDITIVE_RETRYABLE} by its own read contract (see {@code ebx.MasterGenerator#applyDefaultLabels}'s
 * own Javadoc). So {@link #decide} only needs to avoid REDUNDANT calls for legibility, never to avoid an
 * unsafe one - see {@link StagingModelDiff#parseExisting}'s own Javadoc for why a wrong facet-key guess
 * fails safe (always repairs) rather than silently missing the incident again.
 * <p>
 * Pure: no EBX or MCP class in this file, Jackson only - unit-tested without an EBX runtime, the same
 * convention as {@link MasterModel}/{@link StagingModelDiff} themselves.
 */
public final class MasterFacetRepair
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MasterFacetRepair()
	{
	}

	public static final class Result
	{
		/** {@code set_element_property} ops for every table found missing its {@code id} field's auto-increment facet - append to the regeneration patch, in promoted-entity order. Never null, empty when nothing needs repair. */
		public final ArrayNode operations;
		/** {@code tablePath} -&gt; label template, for every table found missing (or blank) its own {@code defaultLabel} - the same shape {@code ebx.MasterGenerator#applyDefaultLabels} already consumes as its eligible-table filter. Never null. */
		public final Map<String, String> labelsToApply;
		/** One human-readable line per repair {@link #decide} found necessary - for the route's own "applied"/"notes" arrays. The caller still has to actually make the calls; this class only decides. Never null. */
		public final List<String> notes;

		Result(final ArrayNode pOperations, final Map<String, String> pLabelsToApply, final List<String> pNotes)
		{
			this.operations = pOperations;
			this.labelsToApply = pLabelsToApply;
			this.notes = pNotes;
		}

		/** True when nothing needs repairing - the caller must not let this short-circuit an UP_TO_DATE fast path the way an undeclared MAME add-on already must not (see {@code ebx.MasterGenerator.runRegeneration}'s own comment on that same hazard). */
		public boolean isEmpty()
		{
			return this.operations.size() == 0 && this.labelsToApply.isEmpty();
		}
	}

	/**
	 * @param pExistingTables the live model's tables, exactly as {@code ebx.MasterGenerator.runRegeneration}
	 *        already parsed them for its own {@link StagingModelDiff#diff} call - reused, not re-fetched
	 * @param pPlannedModel this run's {@code master.MasterModel.build} result - {@link MasterModel.Result#builtEntities}
	 *        is walked directly rather than the diff's own additive/nonAdditive lists, because a table needing
	 *        THIS repair is, by definition, neither: it already exists (so it's not in the diff's "new table"
	 *        additive changes) and its own facets are simply invisible to {@link StagingModelDiff}'s generic
	 *        per-field engine (which only ever compares {@code maxLength}/{@code mandatory} - see that class'
	 *        own Javadoc), so it never appears as a nonAdditive difference either
	 */
	public static Result decide(final List<StagingModelDiff.ExistingTable> pExistingTables, final MasterModel.Result pPlannedModel)
	{
		final Map<String, StagingModelDiff.ExistingTable> existingByName = new LinkedHashMap<String, StagingModelDiff.ExistingTable>();
		for (final StagingModelDiff.ExistingTable table : pExistingTables)
		{
			existingByName.put(table.name, table);
		}

		final ArrayNode operations = MasterFacetRepair.MAPPER.createArrayNode();
		final Map<String, String> labelsToApply = new LinkedHashMap<String, String>();
		final List<String> notes = new ArrayList<String>();

		for (final String entityName : pPlannedModel.builtEntities)
		{
			final StagingModelDiff.ExistingTable existing = existingByName.get(entityName);
			if (existing == null)
			{
				// not built at all yet on the live model - the ordinary "new table" path handles this, not a
				// repair (and MasterModel.Result#autoIncrementOps/#defaultLabelByTable would be re-derived
				// identically there anyway)
				continue;
			}
			final String tablePath = MasterModel.tablePath(entityName);

			final StagingModelDiff.ExistingField idField = existing.field("id");
			if (idField == null || idField.autoIncrementStart == null)
			{
				final ArrayNode autoIncrementOps = pPlannedModel.autoIncrementOps.get(entityName);
				if (autoIncrementOps != null)
				{
					operations.addAll(autoIncrementOps);
					notes.add(tablePath + ": id field has no autoIncrementStart/Step facet on the live model (an earlier patch likely never reached it) - repaired.");
				}
			}

			final String desiredLabel = pPlannedModel.defaultLabelByTable.get(tablePath);
			if (desiredLabel != null && existing.defaultLabel == null)
			{
				labelsToApply.put(tablePath, desiredLabel);
				notes.add(tablePath + ": defaultLabel missing on the live model - repaired to \"" + desiredLabel + "\".");
			}
		}
		return new Result(operations, labelsToApply, notes);
	}
}
