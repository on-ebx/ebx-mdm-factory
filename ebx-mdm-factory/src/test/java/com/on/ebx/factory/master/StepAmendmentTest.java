package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free: run the main, a failure throws. Lot MP (2026-10-04): the pure half of amending an already ended step. */
public final class StepAmendmentTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		StepAmendmentTest.mergeKeepsTheExistingDetail();
		StepAmendmentTest.mergeReplacesAKeyAlreadyThere();
		StepAmendmentTest.mergeOnAnUnusableDetailKeepsTheAmendment();
		StepAmendmentTest.messageKeepsPrefixAndSuffixWhenTooLong();
		StepAmendmentTest.messageWithoutPrefixOrSuffix();
		System.out.println("StepAmendmentTest OK");
	}

	private static ObjectNode value() throws Exception
	{
		final ObjectNode node = StepAmendmentTest.MAPPER.createObjectNode();
		node.put("resume", "OK");
		return node;
	}

	private static void mergeKeepsTheExistingDetail() throws Exception
	{
		final JsonNode merged = StepAmendmentTest.MAPPER.readTree(StepAmendment.mergeDetail("{\"entities\":[{\"entity\":\"A\"}]}", "matchingPause", StepAmendmentTest.value()));
		StepAmendmentTest.check("entities kept", merged.path("entities").size() == 1);
		StepAmendmentTest.check("key added", "OK".equals(merged.path("matchingPause").path("resume").asText()));
	}

	private static void mergeReplacesAKeyAlreadyThere() throws Exception
	{
		final JsonNode merged = StepAmendmentTest.MAPPER.readTree(StepAmendment.mergeDetail("{\"matchingPause\":{\"resume\":\"?\"}}", "matchingPause", StepAmendmentTest.value()));
		StepAmendmentTest.check("replaced", "OK".equals(merged.path("matchingPause").path("resume").asText()));
	}

	private static void mergeOnAnUnusableDetailKeepsTheAmendment() throws Exception
	{
		for (final String bad : new String[] { null, "", "  ", "{\"entities\":[{\"entity\":\"A\"", "[1,2]" })
		{
			final JsonNode merged = StepAmendmentTest.MAPPER.readTree(StepAmendment.mergeDetail(bad, "matchingPause", StepAmendmentTest.value()));
			StepAmendmentTest.check("amendment kept for " + bad, "OK".equals(merged.path("matchingPause").path("resume").asText()));
		}
	}

	private static void messageKeepsPrefixAndSuffixWhenTooLong()
	{
		final StringBuilder existing = new StringBuilder();
		for (int i = 0; i < 300; i++)
		{
			existing.append("0123456789");
		}
		final String message = StepAmendment.composeMessage("WARNING: table X is still PAUSED.", existing.toString(), "Catch-up: 12 record(s).", 2000);
		StepAmendmentTest.check("within the bound: " + message.length(), message.length() <= 2000);
		StepAmendmentTest.check("prefix first", message.startsWith("WARNING: table X is still PAUSED. 0123"));
		StepAmendmentTest.check("suffix last", message.endsWith("... Catch-up: 12 record(s)."));
		StepAmendmentTest.check("short message untouched", "A B C".equals(StepAmendment.composeMessage("A", "B", "C", 100)));
	}

	private static void messageWithoutPrefixOrSuffix()
	{
		StepAmendmentTest.check("no prefix", "B C".equals(StepAmendment.composeMessage(null, "B", "C", 100)));
		StepAmendmentTest.check("no suffix", "A B".equals(StepAmendment.composeMessage("A", "B", "", 100)));
		StepAmendmentTest.check("nothing existing", "A C".equals(StepAmendment.composeMessage("A", null, "C", 100)));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
