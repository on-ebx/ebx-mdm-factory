package com.on.ebx.factory.master;

import java.util.Arrays;

/** JUnit-free: run the main, a failure throws. {@link PlanFingerprint} (lot M-INC, 2026-10-12). */
public final class PlanFingerprintTest
{
	public static void main(final String[] pArgs)
	{
		final String a = PlanFingerprint.of(Arrays.asList("masterPlan/v1", "Personne", "name<-name", "writable", "name"));
		check("64 lower-case hex characters", a.length() == 64 && a.matches("[0-9a-f]+"));
		check("deterministic", a.equals(PlanFingerprint.of(Arrays.asList("masterPlan/v1", "Personne", "name<-name", "writable", "name"))));
		check("one part more changes it", !a.equals(PlanFingerprint.of(Arrays.asList("masterPlan/v1", "Personne", "name<-name", "writable", "name", "phone"))));
		check("one part changed changes it", !a.equals(PlanFingerprint.of(Arrays.asList("masterPlan/v1", "Personne", "name<-nom", "writable", "name"))));
		check("order matters", !PlanFingerprint.of(Arrays.asList("a", "b")).equals(PlanFingerprint.of(Arrays.asList("b", "a"))));
		check("the cut between parts matters (length-prefixed)", !PlanFingerprint.of(Arrays.asList("ab", "c")).equals(PlanFingerprint.of(Arrays.asList("a", "bc"))));
		check("a separator character inside a part cannot fake a cut", !PlanFingerprint.of(Arrays.asList("a;b")).equals(PlanFingerprint.of(Arrays.asList("a", "b"))));
		check("null counts as empty", PlanFingerprint.of(Arrays.asList((String) null, "x")).equals(PlanFingerprint.of(Arrays.asList("", "x"))));
		check("no part still hashes", PlanFingerprint.of(Arrays.<String>asList()).length() == 64);
		System.out.println("PlanFingerprintTest OK");
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
