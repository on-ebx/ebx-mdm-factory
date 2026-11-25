package com.on.ebx.factory.ebx;

/**
 * JUnit-free: run the main, a failure throws. Same package as {@link WizardData} on purpose, to reach its
 * package-private {@link WizardData#strongerOrigin} directly - the exact fragment behind two live incidents of
 * the same defect class: 2026-09-24 (project Vague1, SFDC tables, {@code SOURCE}) and 2026-09-27 (project
 * DeducedTest3, {@code DEDUCED}). Both times a key-column origin left the table's aggregated {@code keyOrigin}
 * at {@code ""} instead of the real origin, because {@code originRank} did not know about that origin yet and a
 * tie at the default rank never overrides {@code pCurrent} (see WizardData's own Javadoc on
 * {@link WizardData#strongerOrigin}).
 */
public final class WizardDataTest
{
	public static void main(final String[] pArgs)
	{
		// the live bug: folding a single SOURCE-origin column onto the initial "" must yield "SOURCE", not "" -
		// this is exactly the aggregation LandingRun.runKeyStage's short-circuit ("SOURCE".equals(keyOrigin))
		// depends on to skip the sample-based study entirely for a source-declared key.
		WizardDataTest.check("a single SOURCE column: aggregated origin is SOURCE, not stuck at the empty default",
			"SOURCE".equals(WizardData.strongerOrigin("", "SOURCE")));

		// USER still wins over SOURCE - a human decision is never overridden by what the source declares
		WizardDataTest.check("USER wins over SOURCE", "USER".equals(WizardData.strongerOrigin("USER", "SOURCE")));
		WizardDataTest.check("USER wins over SOURCE, other fold order", "USER".equals(WizardData.strongerOrigin("SOURCE", "USER")));

		// SOURCE wins over LLM and RULE - a declared key is more authoritative than either pass
		WizardDataTest.check("SOURCE wins over LLM", "SOURCE".equals(WizardData.strongerOrigin("LLM", "SOURCE")));
		WizardDataTest.check("SOURCE wins over RULE", "SOURCE".equals(WizardData.strongerOrigin("RULE", "SOURCE")));
		WizardDataTest.check("SOURCE wins over RULE, other fold order", "SOURCE".equals(WizardData.strongerOrigin("SOURCE", "RULE")));

		// the pre-existing ranking (USER > LLM > RULE > empty) stays intact
		WizardDataTest.check("USER still wins over LLM", "USER".equals(WizardData.strongerOrigin("LLM", "USER")));
		WizardDataTest.check("LLM still wins over RULE", "LLM".equals(WizardData.strongerOrigin("RULE", "LLM")));
		WizardDataTest.check("RULE still wins over empty", "RULE".equals(WizardData.strongerOrigin("", "RULE")));

		// an unrecognised/blank candidate never regresses a stronger current value
		WizardDataTest.check("a blank candidate never overrides a real origin", "SOURCE".equals(WizardData.strongerOrigin("SOURCE", "")));
		WizardDataTest.check("a blank candidate never overrides a real origin (null)", "SOURCE".equals(WizardData.strongerOrigin("SOURCE", null)));

		// the live bug reported 2026-09-27 (project DeducedTest3): the exact same defect class as the
		// 2026-09-24 SOURCE incident above, reintroduced for DEDUCED - a single DEDUCED-origin column must fold
		// onto the initial "" as "DEDUCED", not stay stuck at the empty default (which KeyCertainty.alreadySettled
		// does not recognise, sending the table back through a full LLM re-study of a key a person already
		// confirmed via the deduced-scan board).
		WizardDataTest.check("a single DEDUCED column: aggregated origin is DEDUCED, not stuck at the empty default",
			"DEDUCED".equals(WizardData.strongerOrigin("", "DEDUCED")));

		// USER (an explicit, later override in the key picker) still wins over DEDUCED
		WizardDataTest.check("USER wins over DEDUCED", "USER".equals(WizardData.strongerOrigin("USER", "DEDUCED")));
		WizardDataTest.check("USER wins over DEDUCED, other fold order", "USER".equals(WizardData.strongerOrigin("DEDUCED", "USER")));

		// DEDUCED (a person's confirmation via board 2e) outranks SOURCE, LLM and RULE, same as USER does
		WizardDataTest.check("DEDUCED wins over SOURCE", "DEDUCED".equals(WizardData.strongerOrigin("SOURCE", "DEDUCED")));
		WizardDataTest.check("DEDUCED wins over LLM", "DEDUCED".equals(WizardData.strongerOrigin("LLM", "DEDUCED")));
		WizardDataTest.check("DEDUCED wins over RULE", "DEDUCED".equals(WizardData.strongerOrigin("RULE", "DEDUCED")));

		System.out.println("WizardDataTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
