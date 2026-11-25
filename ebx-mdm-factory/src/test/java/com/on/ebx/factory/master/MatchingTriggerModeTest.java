package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * JUnit-free: run the main, a failure throws (same convention as every other test in this codebase).
 * <p>
 * Lot MT (2026-10-04): {@link MatchingTriggerMode}, the pure mapping of the project's matching trigger to the add-on's execution modes
 * and the {@code set_execution_mode} operations still to write.
 */
public final class MatchingTriggerModeTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchingTriggerModeTest.mapping();
		MatchingTriggerModeTest.parseIsTolerantAndDefaultsToManual();
		MatchingTriggerModeTest.acceptedValues();
		MatchingTriggerModeTest.opsOnlyForWhatDiffers();
		MatchingTriggerModeTest.opShapeIsWhatTheAddonReads();
		System.out.println("MatchingTriggerModeTest OK");
	}

	private static void mapping()
	{
		MatchingTriggerModeTest.check("MANUAL writes 0 (the add-on's MANUAL)", MatchingTriggerMode.MANUAL.executionValue() == 0);
		MatchingTriggerModeTest.check("REALTIME writes 1 (AFTER_SUBMISSION)", MatchingTriggerMode.REALTIME.executionValue() == 1);
		MatchingTriggerModeTest.check("exactly two modes, no third", MatchingTriggerMode.values().length == 2);
	}

	private static void parseIsTolerantAndDefaultsToManual()
	{
		MatchingTriggerModeTest.check("null reads MANUAL", MatchingTriggerMode.parse(null) == MatchingTriggerMode.MANUAL);
		MatchingTriggerModeTest.check("empty reads MANUAL", MatchingTriggerMode.parse("") == MatchingTriggerMode.MANUAL);
		MatchingTriggerModeTest.check("blank reads MANUAL", MatchingTriggerMode.parse("   ") == MatchingTriggerMode.MANUAL);
		MatchingTriggerModeTest.check("unknown reads MANUAL (the safe side: nothing runs inline)", MatchingTriggerMode.parse("INLINE_MATCHING") == MatchingTriggerMode.MANUAL);
		MatchingTriggerModeTest.check("REALTIME", MatchingTriggerMode.parse("REALTIME") == MatchingTriggerMode.REALTIME);
		MatchingTriggerModeTest.check("case and spaces do not matter", MatchingTriggerMode.parse(" realtime ") == MatchingTriggerMode.REALTIME);
	}

	private static void acceptedValues()
	{
		MatchingTriggerModeTest.check("empty is accepted (saves as MANUAL)", MatchingTriggerMode.isAccepted("") && MatchingTriggerMode.isAccepted(null));
		MatchingTriggerModeTest.check("the two modes are accepted", MatchingTriggerMode.isAccepted("MANUAL") && MatchingTriggerMode.isAccepted("REALTIME"));
		MatchingTriggerModeTest.check("anything else is refused", !MatchingTriggerMode.isAccepted("BEFORE_CREATION") && !MatchingTriggerMode.isAccepted("1"));
	}

	private static void opsOnlyForWhatDiffers() throws Exception
	{
		final JsonNode afterSubmission = MatchingTriggerModeTest.MAPPER.readTree("{\"processCode\":\"Personne\",\"matchingOnCreation\":1,\"matchingOnUpdate\":1}");
		final JsonNode manual = MatchingTriggerModeTest.MAPPER.readTree("{\"processCode\":\"Personne\",\"matchingOnCreation\":0,\"matchingOnUpdate\":0}");
		final JsonNode mixed = MatchingTriggerModeTest.MAPPER.readTree("{\"processCode\":\"Personne\",\"matchingOnCreation\":0,\"matchingOnUpdate\":1}");
		final JsonNode absent = MatchingTriggerModeTest.MAPPER.readTree("{\"processCode\":\"Personne\"}");

		MatchingTriggerModeTest.check("MANUAL on a 1/1 policy: two ops", MatchingTriggerMode.MANUAL.patchOps("Personne", afterSubmission).size() == 2);
		MatchingTriggerModeTest.check("MANUAL on a 0/0 policy: nothing to write (idempotent)", MatchingTriggerMode.MANUAL.patchOps("Personne", manual).size() == 0);
		MatchingTriggerModeTest.check("REALTIME on a 1/1 policy: nothing to write", MatchingTriggerMode.REALTIME.patchOps("Personne", afterSubmission).size() == 0);
		MatchingTriggerModeTest.check("REALTIME on a 0/0 policy: two ops", MatchingTriggerMode.REALTIME.patchOps("Personne", manual).size() == 2);
		final ArrayNode mixedOps = MatchingTriggerMode.MANUAL.patchOps("Personne", mixed);
		MatchingTriggerModeTest.check("MANUAL on a 0/1 policy: only matchingOnUpdate is written",
			mixedOps.size() == 1 && "matchingOnUpdate".equals(mixedOps.get(0).path("target").path("paramName").asText()));
		MatchingTriggerModeTest.check("absent values read as different: two ops", MatchingTriggerMode.MANUAL.patchOps("Personne", absent).size() == 2);
		MatchingTriggerModeTest.check("a missing policy node reads as different: two ops", MatchingTriggerMode.MANUAL.patchOps("Personne", null).size() == 2);
		final JsonNode textual = MatchingTriggerModeTest.MAPPER.readTree("{\"matchingOnCreation\":\"0\",\"matchingOnUpdate\":\"0\"}");
		MatchingTriggerModeTest.check("a value read back as text \"0\" counts as 0", MatchingTriggerMode.MANUAL.patchOps("Personne", textual).size() == 0);
	}

	private static void opShapeIsWhatTheAddonReads() throws Exception
	{
		final JsonNode policy = MatchingTriggerModeTest.MAPPER.readTree("{\"matchingOnCreation\":1,\"matchingOnUpdate\":1}");
		final ArrayNode ops = MatchingTriggerMode.MANUAL.patchOps("Personne", policy);
		MatchingTriggerModeTest.check("op name", "set_execution_mode".equals(ops.get(0).path("op").asText()));
		MatchingTriggerModeTest.check("target.policyCode", "Personne".equals(ops.get(0).path("target").path("policyCode").asText()));
		MatchingTriggerModeTest.check("target.paramName: creation first, then update", "matchingOnCreation".equals(ops.get(0).path("target").path("paramName").asText())
			&& "matchingOnUpdate".equals(ops.get(1).path("target").path("paramName").asText()));
		MatchingTriggerModeTest.check("value is a JSON STRING (the add-on's PatchOp.value is a String)", ops.get(0).path("value").isTextual() && "0".equals(ops.get(0).path("value").asText()));
		final ArrayNode realtimeOps = MatchingTriggerMode.REALTIME.patchOps("Personne", MatchingTriggerModeTest.MAPPER.readTree("{\"matchingOnCreation\":0,\"matchingOnUpdate\":0}"));
		MatchingTriggerModeTest.check("REALTIME value is \"1\"", "1".equals(realtimeOps.get(0).path("value").asText()));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
