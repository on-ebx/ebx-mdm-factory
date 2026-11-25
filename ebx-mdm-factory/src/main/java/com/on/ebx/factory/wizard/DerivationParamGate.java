package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fix/derivation-apply-backfill (2026-10-17, lot G1, item 3): the pure decisions behind step 4's "a derivation waits for a parameter" block.
 * <p>
 * <b>The defect (live, TiersB2CRetestFinal).</b> The matrix accepted {@code mobileE164} ({@code toE164WithDefault}, parameter {@code defaultCountry} to be provided) without a
 * word; step 4 then said the staging was built and loaded, and the only place to enter the country was the "To apply" list further down the page, which the person found by
 * luck. A parameter that only a person can give is a step of the build's own flow, so it is asked where the build is asked: while any ACCEPTED derivation waits for one, the
 * primary block of step 4 (next to "Build and load" / "Continue to the master") shows its inputs, and no sentence of the block calls the staging done without saying so. The
 * build itself is never blocked: the attribute stays empty until the derivation is applied (the loader holds an unapplied derivation empty, lot M5).
 */
public final class DerivationParamGate
{
	private DerivationParamGate()
	{
	}

	/** The accepted derivations of the "To apply" list that still have a {@code toBeProvided} parameter, order kept; {@code null} reads as empty. */
	public static List<StillToDecide.CheckItem> waiting(final List<StillToDecide.CheckItem> pToApply)
	{
		final List<StillToDecide.CheckItem> out = new ArrayList<StillToDecide.CheckItem>();
		if (pToApply != null)
		{
			for (final StillToDecide.CheckItem item : pToApply)
			{
				if (!item.toBeProvidedRemaining().isEmpty())
				{
					out.add(item);
				}
			}
		}
		return out;
	}

	/** Every distinct parameter name still to be provided across {@code pWaiting}, in first-seen order. */
	public static List<String> parameterNames(final List<StillToDecide.CheckItem> pWaiting)
	{
		final Set<String> names = new LinkedHashSet<String>();
		if (pWaiting != null)
		{
			for (final StillToDecide.CheckItem item : pWaiting)
			{
				names.addAll(item.toBeProvidedRemaining());
			}
		}
		return new ArrayList<String>(names);
	}

	/** Whether the block of inputs is shown at the top of step 4: a build is not running, the proposal exists (not the first-run state) and a derivation waits. */
	public static boolean showTopBlock(final WizardRules.Step4Primary pPrimary, final int pWaiting)
	{
		if (pWaiting <= 0 || pPrimary == null)
		{
			return false;
		}
		return pPrimary == WizardRules.Step4Primary.REVIEW || pPrimary == WizardRules.Step4Primary.BUILD || pPrimary == WizardRules.Step4Primary.CONTINUE;
	}

	/**
	 * The bundle key of the sentence under the primary block of a loaded staging ({@code CONTINUE}), given the derivations still to apply and, among them, those that wait for
	 * a parameter. A waiting parameter wins over every other wording: the staging is never called done without it.
	 */
	public static String continueTextKey(final int pToApply, final int pWaiting)
	{
		if (pWaiting > 0)
		{
			return pWaiting == 1 ? "wizard.step4.primary.next.paramWaiting.one" : "wizard.step4.primary.next.paramWaiting.other";
		}
		return pToApply > 0 ? (pToApply == 1 ? "wizard.step4.primary.next.toApply.one" : "wizard.step4.primary.next.toApply.other") : "wizard.step4.primary.next.continue";
	}

	/** Whether the extra line "a derivation waits for a parameter" is added under the primary block of a state that does not word the staging as done (REVIEW, BUILD). */
	public static boolean addsWaitingLine(final WizardRules.Step4Primary pPrimary, final int pWaiting)
	{
		return pWaiting > 0 && (pPrimary == WizardRules.Step4Primary.REVIEW || pPrimary == WizardRules.Step4Primary.BUILD);
	}

	/** The parameters a still-to-decide derivation (not accepted yet) will need, as a list; empty when it needs none. Drives the "needs a parameter" note shown BEFORE accepting. */
	public static List<String> neededBeforeAccept(final StillToDecide.CheckItem pCheck)
	{
		return pCheck == null ? new ArrayList<String>() : pCheck.toBeProvidedRemaining();
	}
}
