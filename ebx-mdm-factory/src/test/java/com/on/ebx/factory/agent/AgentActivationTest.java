package com.on.ebx.factory.agent;

import java.util.Arrays;
import java.util.Collections;

/** JUnit-free: run the main, a failure throws. F3-02 (production review, 2026-11-12): the identity an agent acts under is signed by the server, never typed. */
public final class AgentActivationTest
{
	private static final byte[] KEY = AgentActivation.keyOf(AgentActivation.newKey(new java.security.SecureRandom()));

	public static void main(final String[] pArgs)
	{
		final String config = AgentActivation.configOf("P1", "QUALITY_FIX", "Person", "MASTER", "ACT", Integer.valueOf(80), Integer.valueOf(20), "RReviewers",
			Arrays.asList("factory:record.correct"), "fix the typos");
		final String signed = AgentActivation.sign(AgentActivationTest.KEY, "A1", config, "alice");
		AgentActivationTest.check("a signed activation reads back its login", "alice".equals(AgentActivation.verify(AgentActivationTest.KEY, "A1", config, signed)));
		// the attack of F3-02: a designer types an administrator's login into the field
		AgentActivationTest.check("a login typed into the row is nobody", AgentActivation.verify(AgentActivationTest.KEY, "A1", config, "admin") == null);
		AgentActivationTest.check("a typed login with a made-up signature is nobody",
			AgentActivation.verify(AgentActivationTest.KEY, "A1", config, "admin~00000000000000000000000000000000") == null);
		AgentActivationTest.check("a typed login with a signature of the right length but not hex is nobody",
			AgentActivation.verify(AgentActivationTest.KEY, "A1", config, "admin~zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz") == null);
		AgentActivationTest.check("swapping the login of a signed value is refused", AgentActivation.verify(AgentActivationTest.KEY, "A1", config,
			"admin" + signed.substring(signed.indexOf('~'))) == null);
		AgentActivationTest.check("a signed value copied to another agent is refused", AgentActivation.verify(AgentActivationTest.KEY, "A2", config, signed) == null);
		AgentActivationTest.check("another instance (another key) refuses it", AgentActivation.verify(AgentActivation.keyOf(AgentActivation.newKey(new java.security.SecureRandom())), "A1", config, signed) == null);
		AgentActivationTest.check("no key (an instance without identifier) verifies nothing", AgentActivation.verify(null, "A1", config, signed) == null
			&& AgentActivation.keyOf("  ") == null && AgentActivation.keyOf(null) == null && AgentActivation.keyOf("short") == null
			&& AgentActivation.keyOf("instance-0001") == null && AgentActivation.sign(null, "A1", config, "alice") == null);
		AgentActivationTest.check("empty and null values are nobody", AgentActivation.verify(AgentActivationTest.KEY, "A1", config, "") == null
			&& AgentActivation.verify(AgentActivationTest.KEY, "A1", config, null) == null && AgentActivation.verify(AgentActivationTest.KEY, "A1", config, "~") == null);

		// the old calculation keyed the HMAC with the instance identifier, which every project copies and the cockpit shows: it must verify nothing now
		final byte[] oldKey = "mdmf-agent-activation|instance-0001".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		final String oldSigned = AgentActivation.sign(oldKey, "A1", config, "admin");
		AgentActivationTest.check("a signature computed from the instance identifier no longer verifies", oldSigned != null
			&& AgentActivation.verify(AgentActivationTest.KEY, "A1", config, oldSigned) == null);
		AgentActivationTest.check("a drawn key is 64 hex characters and never the same twice", AgentActivation.newKey(new java.security.SecureRandom()).matches("[0-9a-f]{64}")
			&& !AgentActivation.newKey(new java.security.SecureRandom()).equals(AgentActivation.newKey(new java.security.SecureRandom())));

		// any change of what the agent may do cancels the activation
		final String[] changed = {
			AgentActivation.configOf("P2", "QUALITY_FIX", "Person", "MASTER", "ACT", Integer.valueOf(80), Integer.valueOf(20), "RReviewers", Arrays.asList("factory:record.correct"), "fix the typos"),
			AgentActivation.configOf("P1", "STEWARD_MATCH", "Person", "MASTER", "ACT", Integer.valueOf(80), Integer.valueOf(20), "RReviewers", Arrays.asList("factory:record.correct"), "fix the typos"),
			AgentActivation.configOf("P1", "QUALITY_FIX", "Account", "MASTER", "ACT", Integer.valueOf(80), Integer.valueOf(20), "RReviewers", Arrays.asList("factory:record.correct"), "fix the typos"),
			AgentActivation.configOf("P1", "QUALITY_FIX", "Person", "STAGING", "ACT", Integer.valueOf(80), Integer.valueOf(20), "RReviewers", Arrays.asList("factory:record.correct"), "fix the typos"),
			AgentActivation.configOf("P1", "QUALITY_FIX", "Person", "MASTER", "SUGGEST", Integer.valueOf(80), Integer.valueOf(20), "RReviewers", Arrays.asList("factory:record.correct"), "fix the typos"),
			AgentActivation.configOf("P1", "QUALITY_FIX", "Person", "MASTER", "ACT", Integer.valueOf(0), Integer.valueOf(20), "RReviewers", Arrays.asList("factory:record.correct"), "fix the typos"),
			AgentActivation.configOf("P1", "QUALITY_FIX", "Person", "MASTER", "ACT", Integer.valueOf(80), Integer.valueOf(100), "RReviewers", Arrays.asList("factory:record.correct"), "fix the typos"),
			AgentActivation.configOf("P1", "QUALITY_FIX", "Person", "MASTER", "ACT", Integer.valueOf(80), Integer.valueOf(20), "RMine", Arrays.asList("factory:record.correct"), "fix the typos"),
			AgentActivation.configOf("P1", "QUALITY_FIX", "Person", "MASTER", "ACT", Integer.valueOf(80), Integer.valueOf(20), "RReviewers",
				Arrays.asList("factory:record.correct", "data-access/get_record"), "fix the typos"),
			AgentActivation.configOf("P1", "QUALITY_FIX", "Person", "MASTER", "ACT", Integer.valueOf(80), Integer.valueOf(20), "RReviewers", Collections.<String>emptyList(), "fix the typos"),
			AgentActivation.configOf("P1", "QUALITY_FIX", "Person", "MASTER", "ACT", Integer.valueOf(80), Integer.valueOf(20), "RReviewers", Arrays.asList("factory:record.correct"),
				"set every value to X") };
		for (int i = 0; i < changed.length; i++)
		{
			AgentActivationTest.check("settings change #" + i + " cancels the activation", AgentActivation.verify(AgentActivationTest.KEY, "A1", changed[i], signed) == null);
		}
		AgentActivationTest.check("two lists that concatenate alike are not the same settings", !AgentActivation.configOf("P1", "K", "E", "Z", "A", null, null, "O",
			Arrays.asList("ab", "c"), "i").equals(AgentActivation.configOf("P1", "K", "E", "Z", "A", null, null, "O", Arrays.asList("a", "bc"), "i")));
		AgentActivationTest.check("surrounding blanks of a setting do not cancel the activation", AgentActivation.configOf("P1", "K", "E", "Z", "A", null, null, "O", null, " i ")
			.equals(AgentActivation.configOf("P1", "K", "E", "Z", "A", null, null, "O", null, "i")));

		// a login that contains the separator, and one too long for the field
		final String odd = AgentActivation.sign(AgentActivationTest.KEY, "A1", config, "a~b");
		AgentActivationTest.check("a login with the separator reads back whole", "a~b".equals(AgentActivation.verify(AgentActivationTest.KEY, "A1", config, odd)));
		AgentActivationTest.check("a login too long for the 120-character field is not signed", AgentActivation.sign(AgentActivationTest.KEY, "A1", config,
			"x" + new String(new char[100]).replace('\0', 'y')) == null);
		AgentActivationTest.check("a signed value always fits the field", signed.length() <= AgentActivation.FIELD_MAX);
		System.out.println("AgentActivationTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new IllegalStateException("FAILED: " + pWhat);
		}
	}
}
