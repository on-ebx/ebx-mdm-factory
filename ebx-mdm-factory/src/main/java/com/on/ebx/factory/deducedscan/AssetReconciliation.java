package com.on.ebx.factory.deducedscan;

import java.util.List;
import java.util.TreeSet;

/**
 * Fix lot (2026-09-26, defect 9, project DeducedTest2): a second deduced scan of a connection already in the
 * catalog must not create a duplicate {@code Asset} for a table it (or an earlier deduced scan) already wrote
 * - same recipe (connector + operation + inputs + itemsPath, {@link ReadRecipe#equals}) means the same table,
 * matched on the recipe rather than on the proposed name (which is not stable across two model runs - see
 * DEDUCED-SCAN.md item 11 "table names came back inconsistent"). Pure decision only: {@code
 * ebx.DeducedScanCatalogWriter} does the actual find-or-update; this class only decides, given the columns
 * before and after, whether the previous decision (CONFIRMED/LEFT_OUT/TO_CONFIRM) survives the reconciliation
 * or must be reopened.
 * <ul>
 * <li>Columns unchanged (same set of names, order or case-sensitivity aside is not this class's business -
 * the caller passes whatever set it already compares elsewhere) - keep the existing state as-is: a person's
 * CONFIRMED/LEFT_OUT decision is never silently reopened just because the source was scanned again.</li>
 * <li>Columns changed (added, removed, or both) - the asset must be looked at again: back to {@code
 * TO_CONFIRM}, whatever it was before, with a reason a person can read.</li>
 * </ul>
 */
public final class AssetReconciliation
{
	private AssetReconciliation()
	{
	}

	/** Outcome of reconciling one existing {@code Asset} against a fresh sample of the SAME table (same recipe, or since audit D-07 the same key). Immutable. */
	public static final class Decision
	{
		public final boolean columnsChanged;
		/** The {@code scanState} to write - the asset's own previous state when unchanged, else {@code "TO_CONFIRM"}. */
		public final String scanState;
		/** Non-empty only when {@link #columnsChanged} - a short, person-readable reason to show on the reopened card. */
		public final String reason;

		Decision(final boolean pColumnsChanged, final String pScanState, final String pReason)
		{
			this.columnsChanged = pColumnsChanged;
			this.scanState = pScanState;
			this.reason = pReason == null ? "" : pReason;
		}
	}

	public static final String TO_CONFIRM = "TO_CONFIRM";

	/**
	 * @param pExistingState the asset's current {@code scanState} (whatever it is - TO_CONFIRM/CONFIRMED/LEFT_OUT)
	 * @param pExistingColumnNames every column the asset carried before this scan
	 * @param pNewColumnNames every column the fresh sample measured
	 */
	public static Decision reconcile(final String pExistingState, final List<String> pExistingColumnNames, final List<String> pNewColumnNames)
	{
		return AssetReconciliation.reconcile(pExistingState, pExistingColumnNames, pNewColumnNames, false);
	}

	/**
	 * Audit D-07: the asset key is {@code source:tableName}, so a deduced scan that names a table the catalog already has
	 * for this source lands on that row even when it was read another way (another operation, other inputs: another
	 * recipe) - same source + same table = same asset, reconciled rather than disambiguated. A changed recipe changes how
	 * the table is read, so it reopens the asset like changed columns do ({@code columnsChanged} true: the fresh columns and
	 * key proposal are taken), with its own reason; the caller writes the new recipe.
	 *
	 * @param pRecipeChanged true when the existing asset's {@code readRecipe} differs from the fresh one
	 */
	public static Decision reconcile(final String pExistingState, final List<String> pExistingColumnNames, final List<String> pNewColumnNames,
		final boolean pRecipeChanged)
	{
		if (pRecipeChanged)
		{
			final String state = pExistingState == null || pExistingState.trim().isEmpty() ? AssetReconciliation.TO_CONFIRM : pExistingState;
			return new Decision(true, AssetReconciliation.TO_CONFIRM,
				"Another read of the source named this same table (it was " + state + "): its columns and the way it is read were replaced by the latest one - confirm it again.");
		}
		final String existingState = pExistingState == null || pExistingState.trim().isEmpty() ? AssetReconciliation.TO_CONFIRM : pExistingState;
		final boolean changed = !new TreeSet<String>(pExistingColumnNames).equals(new TreeSet<String>(pNewColumnNames));
		if (!changed)
		{
			return new Decision(false, existingState, "");
		}
		return new Decision(true, AssetReconciliation.TO_CONFIRM,
			"The columns changed since this table was last scanned (it was " + existingState + "): confirm it again.");
	}
}
