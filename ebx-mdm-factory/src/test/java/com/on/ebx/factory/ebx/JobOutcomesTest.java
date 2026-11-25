package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.landing.KeyPrimeRunner;

/** JUnit-free: run the main, a failure throws. F2-06 / F2-15: a PARTIAL staging result and key-priming failures are job outcomes, not green jobs. */
public final class JobOutcomesTest
{
	public static void main(final String[] pArgs)
	{
		final ObjectNode done = new ObjectMapper().createObjectNode();
		done.put("status", "DONE");
		done.put("read", 10);
		check("a DONE load is not partial", JobOutcomes.stagingOutcome(done, "x") == null);

		final ObjectNode partial = new ObjectMapper().createObjectNode();
		partial.put("status", "PARTIAL");
		partial.put("read", 10);
		partial.put("referencesNotWritten", 3);
		partial.put("landingStatusNotWritten", 0);
		partial.put("endMessage", "[STAGING_END_REFERENCES_NOT_WRITTEN] 3 deferred reference(s) could not be written");
		final FactoryJobQueue.PartialOutcome outcome = JobOutcomes.stagingOutcome(partial, "Staging build finished.");
		check("a PARTIAL load is a partial job", outcome != null);
		check("the coded message is carried", outcome.getMessage().contains("STAGING_END_REFERENCES_NOT_WRITTEN") && outcome.getMessage().contains("finished"));
		check("what failed is in the detail", outcome.detail.contains("references"));

		final KeyPrimeRunner.Outcome clean = new KeyPrimeRunner.Outcome();
		clean.studied = 4;
		check("no failed key: no partial", JobOutcomes.keyPrimeOutcome(clean, "Planned.") == null && JobOutcomes.keyPrimeOutcome(null, "x") == null);
		final KeyPrimeRunner.Outcome failed = new KeyPrimeRunner.Outcome();
		failed.studied = 4;
		failed.failed = 2;
		final FactoryJobQueue.PartialOutcome keys = JobOutcomes.keyPrimeOutcome(failed, "Planned 4 tables.");
		check("failed > 0 is PARTIAL", keys != null && keys.getMessage().contains(JobOutcomes.CODE_KEY_PRIME) && keys.getMessage().contains("2 table key(s)") && keys.getMessage().startsWith("Planned"));
		System.out.println("JobOutcomesTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
