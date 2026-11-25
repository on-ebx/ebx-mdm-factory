package com.on.ebx.factory.wizard;

import java.util.List;

/**
 * UX lot C4 (2026-10-14): when the tables of an application read from its data are ready to be listed (planned as landing tables). Seen live on
 * project UxCrmSf3: DemoCRM's 2 tables had been CONFIRMED in an earlier project, the deduced scan ended "2 table(s) up to date, decisions kept." and
 * nothing planned them - the plan only ran when a person decided the LAST table, never when every table was already decided - so the card asked for
 * "Find its tables", which ran the same scan again, three times. One rule now answers "every table is decided and there is at least one" for both
 * places that need it: the deduced-scan job (plans at once, whether the person stays on the page or not) and the application card (offers "List its
 * tables" instead of another scan). Pure, tested in {@code DeducedPlanRuleTest}.
 */
public final class DeducedPlanRule
{
	/** The catalog's states of a table found from the data ({@code Asset.scanState}). */
	public static final String CONFIRMED = "CONFIRMED";
	public static final String LEFT_OUT = "LEFT_OUT";
	public static final String TO_CONFIRM = "TO_CONFIRM";

	private DeducedPlanRule()
	{
	}

	/** Every table found from the data is decided (confirmed or left out), none waits for a person, and there is at least one. */
	public static boolean allDecided(final int pConfirmed, final int pLeftOut, final int pToConfirm)
	{
		return pToConfirm == 0 && pConfirmed + pLeftOut > 0;
	}

	/**
	 * The same rule read from the objects the catalog holds for an application with no planned table
	 * ({@link WizardSnapshot.SourceInfo#unplannedAssets}): only the tables found from the data carry a state; an object of an official scan never
	 * counts here. The tables to list must carry a scanned column (a decided table with none cannot be planned).
	 */
	public static boolean listable(final List<UnplannedAsset> pAssets)
	{
		int confirmed = 0;
		int leftOut = 0;
		int toConfirm = 0;
		boolean withColumns = false;
		for (final UnplannedAsset asset : pAssets)
		{
			if (DeducedPlanRule.CONFIRMED.equals(asset.scanState))
			{
				confirmed++;
				withColumns |= asset.hasColumns;
			}
			else if (DeducedPlanRule.LEFT_OUT.equals(asset.scanState))
			{
				leftOut++;
				withColumns |= asset.hasColumns;
			}
			else if (DeducedPlanRule.TO_CONFIRM.equals(asset.scanState))
			{
				toConfirm++;
			}
		}
		return withColumns && DeducedPlanRule.allDecided(confirmed, leftOut, toConfirm);
	}

	/** The end message of a deduced-scan job that planned {@code pTables} table(s) at once. */
	public static String readyMessage(final int pTables)
	{
		return pTables + (pTables == 1 ? " table" : " tables") + " found, ready to choose.";
	}
}
