package com.on.ebx.factory.ebx;

/**
 * JUnit-free: run the main, a failure throws. Lot M4 (2026-10-18, docs/MATCHING.md section 59, point C): why accepted STAGING-zone constraints 333 (minValue) and 351 (isDate) stayed ACCEPTED
 * with an empty applyMessage after the staging load of the automated test, and the two pure decisions that now say it. The load DID try them (the step message of PipelineStep 2024 and 2068
 * carries "Accepted script constraint 333 could not be applied by this load: cannot apply '333': parameter(s) [value] are still 'to be provided' in the binding"): the refusal came from
 * CapabilityApplier.prepare, before any call, and that one path never wrote the row; the notes were also forwarded to the job only after a derivation had been applied.
 */
public final class ConstraintAutoApplyTest
{
	private ConstraintAutoApplyTest()
	{
	}

	public static void main(final String[] pArgs)
	{
		// the live message ended with a full stop and the loader added another one ("before applying..")
		final String live = "cannot apply '333': parameter(s) [value] are still 'to be provided' in the binding - resolve them (reject and re-propose, or edit the row) before applying.";
		final String note = ConstraintAutoApply.failureNote("333", live);
		ConstraintAutoApplyTest.check("the note names the constraint, says it stays ACCEPTED and gives the reason", note.startsWith("Accepted script constraint 333 could not be applied by this load, it stays ACCEPTED: ")
			&& note.contains("parameter(s) [value] are still 'to be provided'"), note);
		ConstraintAutoApplyTest.check("no full stop of its own: the loader's sentence adds one (the live text read '..')", !note.endsWith("."), note);
		ConstraintAutoApplyTest.check("an empty reason is said", ConstraintAutoApply.failureNote("9", "  ").endsWith("no reason was given") && ConstraintAutoApply.failureNote("9", null).endsWith("no reason was given"));
		final StringBuilder longReason = new StringBuilder();
		for (int i = 0; i < 400; i++)
		{
			longReason.append('x');
		}
		ConstraintAutoApplyTest.check("a long reason is cut", ConstraintAutoApply.failureNote("9", longReason.toString()).length() < 450);

		ConstraintAutoApplyTest.check("an ACCEPTED constraint of the project gets its refusal written", CapabilityApplier.recordsPrepareFailure(true, "SCRIPT_CONSTRAINT", "ACCEPTED"));
		ConstraintAutoApplyTest.check("an APPLIED one too (an explicit re-apply): only applyMessage changes, never the status", CapabilityApplier.recordsPrepareFailure(true, "SCRIPT_CONSTRAINT", "APPLIED"));
		ConstraintAutoApplyTest.check("a row of another project is never touched", !CapabilityApplier.recordsPrepareFailure(false, "SCRIPT_CONSTRAINT", "ACCEPTED"));
		ConstraintAutoApplyTest.check("a derivation or an external service is never touched", !CapabilityApplier.recordsPrepareFailure(true, "SCRIPT_DERIVATION", "ACCEPTED")
			&& !CapabilityApplier.recordsPrepareFailure(true, "EXTERNAL_SERVICE", "ACCEPTED"));
		ConstraintAutoApplyTest.check("a PROPOSED, REJECTED or SUPERSEDED row is never touched", !CapabilityApplier.recordsPrepareFailure(true, "SCRIPT_CONSTRAINT", "PROPOSED")
			&& !CapabilityApplier.recordsPrepareFailure(true, "SCRIPT_CONSTRAINT", "REJECTED") && !CapabilityApplier.recordsPrepareFailure(true, "SCRIPT_CONSTRAINT", "SUPERSEDED"));
		System.out.println("ConstraintAutoApplyTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		ConstraintAutoApplyTest.check(pWhat, pCondition, "");
	}

	private static void check(final String pWhat, final boolean pCondition, final String pDetail)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat + (pDetail.isEmpty() ? "" : " | " + pDetail));
		}
	}
}
