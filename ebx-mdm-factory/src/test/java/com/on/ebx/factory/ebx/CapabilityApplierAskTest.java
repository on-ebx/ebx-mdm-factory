package com.on.ebx.factory.ebx;

/**
 * JUnit-free: run the main, a failure throws. Wave 11 (audit F-26): the pure part of the one-question passes of {@link CapabilityApplier}
 * (constraint message, constraint explanation) that go through the Connectors contract instead of a one-tool agent run - reading the
 * one string field out of a model's JSON answer.
 */
public final class CapabilityApplierAskTest
{
	public static void main(final String[] pArgs)
	{
		check("a plain object", "hello".equals(CapabilityApplier.fieldOf("{\"message\":\"hello\"}", "message")));
		check("a code fence around it", "hello".equals(CapabilityApplier.fieldOf("```json\n{\"message\": \"hello\"}\n```", "message")));
		check("a sentence before and after it", "hello".equals(CapabilityApplier.fieldOf("Here you go: {\"message\":\"hello\"} Hope it helps.", "message")));
		check("another field is not it", CapabilityApplier.fieldOf("{\"text\":\"hello\"}", "message") == null);
		check("not a string is not it", CapabilityApplier.fieldOf("{\"message\":42}", "message") == null && CapabilityApplier.fieldOf("{\"message\":null}", "message") == null);
		check("no object at all", CapabilityApplier.fieldOf("just words", "message") == null && CapabilityApplier.fieldOf("", "message") == null && CapabilityApplier.fieldOf(null, "message") == null);
		check("a cut answer is not an object", CapabilityApplier.fieldOf("{\"message\":\"hel", "message") == null);
		check("an empty string is returned, the acceptor refuses it", "".equals(CapabilityApplier.fieldOf("{\"message\":\"\"}", "message")));
		System.out.println("CapabilityApplierAskTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
