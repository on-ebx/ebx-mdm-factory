package com.on.ebx.factory.wizard;

/**
 * UX lot U2 (2026-10-13): one object the catalog holds for an application that has no planned table, and whether it carries a scanned
 * column - what the application card turns into "Account: no column scanned yet" (a plain data holder, no EBX type, like the rest of
 * {@link WizardSnapshot}). Filled by {@code ebx.WizardData#attachUnplannedAssets} for the wizard page only.
 */
public final class UnplannedAsset
{
	public final String name;
	public final boolean hasColumns;
	/**
	 * UX lot C4 (2026-10-14): {@code CONFIRMED} / {@code LEFT_OUT} / {@code TO_CONFIRM} for a table found from the data ({@code Asset.scanOrigin = DEDUCED};
	 * an empty state counts as confirmed, the catalog's own rule), {@code ""} for an object of an official scan.
	 */
	public final String scanState;

	public UnplannedAsset(final String pName, final boolean pHasColumns)
	{
		this(pName, pHasColumns, "");
	}

	public UnplannedAsset(final String pName, final boolean pHasColumns, final String pScanState)
	{
		this.name = pName == null ? "" : pName;
		this.hasColumns = pHasColumns;
		this.scanState = pScanState == null ? "" : pScanState;
	}
}
