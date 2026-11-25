package com.on.ebx.factory.wizard;

/**
 * Sources-landing live fix (2026-09-24, project A, source PERSONNES_SALESFORCE): the Review screen's
 * per-table line read "18 columns, 0 rows" for a table that had genuinely loaded 180 rows -
 * {@code LandingTable.sourceRowCount} (what the catalog profiled) is null for these sample-file sources
 * (the catalog rarely profiles them), and {@code WizardData.tableInfo} coerced that null to the primitive
 * {@code 0}, indistinguishable from a real zero. {@code LandingTable.lastLoadRows} (what the landing's own
 * last load actually moved, measured live: 180) was sitting right there unused. This is a pure decision -
 * no EBX access - so it lives here (wizard.*) rather than in ebx.WizardData: given what is known about one
 * table, pick the best row count to show, or say none is known at all (never render a misleading "0").
 */
public final class TableRowCount
{
	/** True when {@link #value} is a real, known count (the catalog's own profile, or - failing that - the landing's own last load). False: no count is known, render nothing (not "0"). */
	public final boolean known;
	public final long value;

	private TableRowCount(final boolean pKnown, final long pValue)
	{
		this.known = pKnown;
		this.value = pValue;
	}

	/**
	 * {@code pSourceRowCountKnown}/{@code pSourceRowCount}: the catalog's own profiled row count, when it
	 * actually profiled one (may legitimately be a real zero). Else {@code pLastLoadRows}: the landing's own
	 * last load, when it moved at least one row. Else unknown.
	 */
	public static TableRowCount of(final boolean pSourceRowCountKnown, final long pSourceRowCount, final long pLastLoadRows)
	{
		if (pSourceRowCountKnown)
		{
			return new TableRowCount(true, pSourceRowCount);
		}
		if (pLastLoadRows > 0)
		{
			return new TableRowCount(true, pLastLoadRows);
		}
		return new TableRowCount(false, 0L);
	}
}
