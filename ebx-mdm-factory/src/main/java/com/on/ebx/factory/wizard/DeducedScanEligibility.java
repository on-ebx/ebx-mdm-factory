package com.on.ebx.factory.wizard;

/**
 * Fix (2026-09-27, project A, wizard step 2): whether a deduced scan would even start on one application's
 * connection - the ONE rule {@code ebx.DeducedScanRun}'s LIST stage applies before anything else, shared here
 * so the Review card offers "Run a deduced scan" only where that stage would accept it. Seen live: the card of
 * {@code Personnes_SAP_Sample} (a sample-file connection, which HAS a {@code catalog} operation) offered the
 * button; the click wrote PipelineStep 528 SKIPPED "This connection has an official scan" and the card turned
 * every stage skipped - the old "any card with no table" heuristic (docs/DEDUCED-SCAN.md sec 9).
 *
 * <p>Only the first gate is decidable before the run: the second one (a listing operation exists) is the
 * language model's own choice inside the run, and stays there.</p>
 */
public enum DeducedScanEligibility
{
	/** Not computed: the application already has tables, or no connection was described for it. The Review card offers nothing. */
	NOT_CHECKED,
	/** {@code connectors_describe} answered with no {@code catalog} operation: the deduced scan may run. */
	OFFERED,
	/** The connection has a {@code catalog} operation: its tables come from the official scan and the landing plan. */
	OFFICIAL_SCAN,
	/** {@code connectors_describe} failed: the deduced scan would fail at once, so it is not offered either. */
	UNREACHABLE;

	/** The rule itself, from the two facts {@code ebx.DeducedScanOperations.Described} carries, in the order the run checks them. */
	public static DeducedScanEligibility of(final String pDescribeError, final boolean pHasCatalogOperation)
	{
		if (pDescribeError != null)
		{
			return UNREACHABLE;
		}
		return pHasCatalogOperation ? OFFICIAL_SCAN : OFFERED;
	}
}
