package com.on.ebx.factory.landing;

/** JUnit-free: run the main, a failure throws. */
public final class KeyCertaintyTest
{
	public static void main(final String[] pArgs)
	{
		KeyCertaintyTest.check("SOURCE is always certain, sample unknown", KeyCertainty.certain("SOURCE", 0, null, null));
		KeyCertaintyTest.check("SOURCE is always certain, even if the sample disagrees", KeyCertainty.certain("SOURCE", 0, Boolean.FALSE, Boolean.FALSE));
		KeyCertaintyTest.check("USER is always certain, sample unknown", KeyCertainty.certain("USER", 0, null, null));
		KeyCertaintyTest.check("USER is always certain, even if the sample disagrees", KeyCertainty.certain("USER", 100, Boolean.FALSE, Boolean.FALSE));
		KeyCertaintyTest.check("DEDUCED is always certain, sample unknown", KeyCertainty.certain("DEDUCED", 0, null, null));
		KeyCertaintyTest.check("DEDUCED is always certain, even if the sample disagrees", KeyCertainty.certain("DEDUCED", 100, Boolean.FALSE, Boolean.FALSE));

		KeyCertaintyTest.check("RULE is certain when the sample is unique and never empty", KeyCertainty.certain("RULE", 70, Boolean.TRUE, Boolean.TRUE));
		KeyCertaintyTest.check("RULE is NOT certain when the sample says otherwise", !KeyCertainty.certain("RULE", 95, Boolean.FALSE, Boolean.TRUE));
		KeyCertaintyTest.check("RULE is NOT certain when the sample could not tell (null)", !KeyCertainty.certain("RULE", 95, null, Boolean.TRUE));

		// 2026-10-07 (the product owner): a language-model key is a suggestion a person confirms, never applied on its own
		KeyCertaintyTest.check("LLM is NOT certain even at confidence 90 with a unique sample AND unique at the source",
			!KeyCertainty.certain("LLM", 90, Boolean.TRUE, Boolean.TRUE));
		KeyCertaintyTest.check("LLM is NOT certain at confidence 100 either", !KeyCertainty.certain("LLM", 100, Boolean.TRUE, Boolean.TRUE));
		KeyCertaintyTest.check("RULE stays certain when unique in the sample AND at the source", KeyCertainty.certain("RULE", 0, Boolean.TRUE, Boolean.TRUE));
		KeyCertaintyTest.check("LLM under 90 confidence is NOT certain, even with a unique sample", !KeyCertainty.certain("LLM", 89, Boolean.TRUE, Boolean.TRUE));
		KeyCertaintyTest.check("LLM at high confidence but a non-unique sample is NOT certain", !KeyCertainty.certain("LLM", 100, Boolean.FALSE, Boolean.TRUE));
		KeyCertaintyTest.check("LLM with an unknown sample is NOT certain", !KeyCertainty.certain("LLM", 100, null, Boolean.TRUE));

		KeyCertaintyTest.check("an unrecognised origin is never certain", !KeyCertainty.certain("", 100, Boolean.TRUE, Boolean.TRUE));
		KeyCertaintyTest.check("a null origin is never certain, not an exception", !KeyCertainty.certain(null, 100, Boolean.TRUE, Boolean.TRUE));

		// volume-guards lot (2026-09-28, task item 4): a key unique in the sample but NOT verified unique
		// against the whole source (a real collision found, or the check could not even be run) is never
		// auto-applied - "not unique or not verifiable -> not certain -> the chooser is shown".
		KeyCertaintyTest.check("RULE, unique sample, but a collision found at the source: NOT certain",
			!KeyCertainty.certain("RULE", 70, Boolean.TRUE, Boolean.FALSE));
		KeyCertaintyTest.check("RULE, unique sample, but the source could not be checked (null): NOT certain - never verifiable, never certain",
			!KeyCertainty.certain("RULE", 70, Boolean.TRUE, null));
		KeyCertaintyTest.check("LLM, unique sample and confidence 100, but a collision found at the source: NOT certain",
			!KeyCertainty.certain("LLM", 100, Boolean.TRUE, Boolean.FALSE));
		KeyCertaintyTest.check("LLM, unique sample and confidence 100, but the source could not be checked (null): NOT certain",
			!KeyCertainty.certain("LLM", 100, Boolean.TRUE, null));
		// SOURCE/USER/DEDUCED are always certain regardless of pUniqueAtSource - the whole point of those
		// origins is that no sample/source check was ever needed to trust them in the first place.
		KeyCertaintyTest.check("SOURCE stays certain even when pUniqueAtSource is FALSE - it is never checked for these origins",
			KeyCertainty.certain("SOURCE", 0, Boolean.TRUE, Boolean.FALSE));
		KeyCertaintyTest.check("USER stays certain even when pUniqueAtSource is null", KeyCertainty.certain("USER", 0, Boolean.TRUE, null));
		KeyCertaintyTest.check("DEDUCED stays certain even when pUniqueAtSource is FALSE", KeyCertainty.certain("DEDUCED", 0, Boolean.TRUE, Boolean.FALSE));

		// fix lot (2026-09-26, defect 11): alreadySettled decides whether LandingRun's key-finding stage may
		// skip calling the model at all - USER/SOURCE were already skipped there.
		// fix lot (2026-09-27, round 5, task item 3): a deduced-scan-confirmed key now carries its own origin,
		// DEDUCED, instead of the old fragile LLM+confidence-exactly-100 stand-in signature - that signature is
		// DROPPED outright (never checked again, at any confidence), not kept as a second way to reach "settled".
		KeyCertaintyTest.check("USER origin is already settled", KeyCertainty.alreadySettled("USER", true, 0));
		KeyCertaintyTest.check("SOURCE origin is already settled", KeyCertainty.alreadySettled("SOURCE", true, 0));
		KeyCertaintyTest.check("DEDUCED origin is already settled, whatever its confidence", KeyCertainty.alreadySettled("DEDUCED", true, 0));
		KeyCertaintyTest.check("DEDUCED origin is already settled at confidence 100 too", KeyCertainty.alreadySettled("DEDUCED", true, 100));
		KeyCertaintyTest.check("the dropped signature: LLM at confidence 100 is NOT settled any more", !KeyCertainty.alreadySettled("LLM", true, 100));
		KeyCertaintyTest.check("a genuine LLM proposal below 100 is NOT settled - keep re-verifying it", !KeyCertainty.alreadySettled("LLM", true, 95));
		KeyCertaintyTest.check("a genuine LLM proposal at 90 (KeyCertainty's own certain threshold) is still NOT settled",
			!KeyCertainty.alreadySettled("LLM", true, 90));
		KeyCertaintyTest.check("RULE origin is not settled - it must still be checked", !KeyCertainty.alreadySettled("RULE", true, 100));
		KeyCertaintyTest.check("no key columns at all is never settled, whatever the origin", !KeyCertainty.alreadySettled("USER", false, 100));
		KeyCertaintyTest.check("no key columns at all is never settled, even DEDUCED", !KeyCertainty.alreadySettled("DEDUCED", false, 100));
		KeyCertaintyTest.check("a null origin is never settled, not an exception", !KeyCertainty.alreadySettled(null, true, 100));

		// key-confidence lot (2026-09-28, project DeducedTest3, live incident): the canonical confidence/
		// rationale a settled key must always read, so a stale value an earlier faulty pass left on the table
		// (LandingTable.keyConfidence reading 55/5 while sourceKeyOrigin already read DEDUCED) can be self-healed.
		KeyCertaintyTest.check("SOURCE settles at confidence 100", KeyCertainty.settledConfidence("SOURCE").confidence == 100);
		KeyCertaintyTest.check("SOURCE's rationale matches SourceKeyRule's own declared-key text",
			"Primary key declared at the source.".equals(KeyCertainty.settledConfidence("SOURCE").rationale));
		KeyCertaintyTest.check("USER settles at confidence 100", KeyCertainty.settledConfidence("USER").confidence == 100);
		KeyCertaintyTest.check("USER's rationale matches WizardData.setTableKey's own default text",
			"Key chosen by a person in the wizard.".equals(KeyCertainty.settledConfidence("USER").rationale));
		KeyCertaintyTest.check("DEDUCED settles at confidence 100", KeyCertainty.settledConfidence("DEDUCED").confidence == 100);
		KeyCertaintyTest.check("DEDUCED's rationale matches LandingPlanner's own deduced-confirmed text",
			"Key columns proposed by the language model during a deduced scan, confirmed by a person.".equals(KeyCertainty.settledConfidence("DEDUCED").rationale));
		KeyCertaintyTest.check("RULE has no canonical settled value - it is never settled in the first place", KeyCertainty.settledConfidence("RULE") == null);
		KeyCertaintyTest.check("LLM has no canonical settled value either", KeyCertainty.settledConfidence("LLM") == null);
		KeyCertaintyTest.check("an unrecognised origin has no canonical settled value", KeyCertainty.settledConfidence("") == null);
		KeyCertaintyTest.check("a null origin has no canonical settled value, not an exception", KeyCertainty.settledConfidence(null) == null);

		// key-confidence lot (2026-09-28): the exact decision ebx.LandingRun#runKeyStage makes in its
		// alreadySettled branch - the live incident itself, reproduced without EBX: a DEDUCED table whose
		// stored keyConfidence/keyRationale are the stale ones an earlier faulty run left (project
		// DeducedTest3's own 55/5, see docs/DEDUCED-SCAN.md section 14) must be corrected to the canonical value.
		KeyCertaintyTest.check("the live incident: a stale DEDUCED confidence of 55 is corrected to 100",
			KeyCertainty.staleSettledCorrection("DEDUCED", 55, "some stale rationale from an earlier study").confidence == 100);
		KeyCertaintyTest.check("the correction's rationale is the canonical DEDUCED sentence, not the stale one",
			"Key columns proposed by the language model during a deduced scan, confirmed by a person."
				.equals(KeyCertainty.staleSettledCorrection("DEDUCED", 55, "some stale rationale from an earlier study").rationale));
		KeyCertaintyTest.check("the live incident's second table: a stale DEDUCED confidence of 5 is corrected too",
			KeyCertainty.staleSettledCorrection("DEDUCED", 5, "").confidence == 100);
		KeyCertaintyTest.check("a DEDUCED table already at the canonical value needs no correction (no-op)",
			KeyCertainty.staleSettledCorrection("DEDUCED", 100, "Key columns proposed by the language model during a deduced scan, confirmed by a person.") == null);
		KeyCertaintyTest.check("a DEDUCED table at confidence 100 but with a DIFFERENT rationale is still corrected (rationale must match too)",
			KeyCertainty.staleSettledCorrection("DEDUCED", 100, "stale text") != null);
		KeyCertaintyTest.check("a stale SOURCE confidence is corrected to 100 with the declared-key sentence",
			KeyCertainty.staleSettledCorrection("SOURCE", 40, "leftover text").confidence == 100);
		KeyCertaintyTest.check("a stale USER confidence is corrected to 100 with the person-chose-it sentence",
			KeyCertainty.staleSettledCorrection("USER", 0, "") != null);
		KeyCertaintyTest.check("a RULE table is never corrected - it is not settled in the first place",
			KeyCertainty.staleSettledCorrection("RULE", 55, "anything") == null);
		KeyCertaintyTest.check("an LLM table is never corrected either", KeyCertainty.staleSettledCorrection("LLM", 55, "anything") == null);
		KeyCertaintyTest.check("a blank origin is never corrected", KeyCertainty.staleSettledCorrection("", 55, "anything") == null);
		KeyCertaintyTest.check("a null rationale on a stale DEDUCED table is treated as empty, not an exception",
			KeyCertainty.staleSettledCorrection("DEDUCED", 55, null) != null);

		System.out.println("KeyCertaintyTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
