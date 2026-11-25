package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JUnit-free: run the main, a failure throws. Covers {@link MameCalls#decide} only - the pure part of {@link
 * MameCalls#call} (`docs/MATCHING.md` &sect;28) that decides, from a raw match-merge answer, whether to retry
 * once with a resolved {@code dataModel}, refuse outright, or use the answer as it came back. {@link
 * MameCalls#call} itself is never exercised here (it needs a real {@code McpClient}, whose {@code ToolResult}
 * cannot be constructed from a test - same discipline {@code MatchingPreviewPolicyTest}'s own class Javadoc
 * states, and the exact reason {@link MameCalls#decide} exists as a separate, {@code JsonNode}-only method).
 * <p>
 * The live {@code AMBIGUOUS_TABLE} payload fixtures here are the SAME ones {@code MameDataModelResolutionTest}
 * already pins - this class does not re-derive the candidate-extraction logic, only checks that {@link
 * MameCalls#decide} routes each of {@link MameDataModelResolution.Action}'s outcomes to the right {@link
 * MameCalls.Outcome}.
 */
public final class MameCallsTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MameCallsTest.decide_nullResponse_asIs();
		MameCallsTest.decide_genuineSuccess_asIs();
		MameCallsTest.decide_unrelatedSoftMiss_asIs_configNotFound();
		MameCallsTest.decide_unrelatedSoftMiss_asIs_locatorNotFound();
		MameCallsTest.decide_ambiguousResolvesToOneCandidate_retryWithFullUrn();
		MameCallsTest.decide_ambiguousNoMatchingCandidate_refuse();
		MameCallsTest.decide_ambiguousSeveralMatchingCandidates_refuse();
		System.out.println("MameCallsTest OK");
	}

	private static void decide_nullResponse_asIs() throws Exception
	{
		final MameCalls.Decision decision = MameCalls.decide(null, "Companies_Master");
		MameCallsTest.check("null response: AS_IS, never a guess", decision.outcome == MameCalls.Outcome.AS_IS);
	}

	private static void decide_genuineSuccess_asIs() throws Exception
	{
		final JsonNode answer = MameCallsTest.parse("{\"success\":true,\"data\":{\"table\":\"/root/Contact\"}}");
		final MameCalls.Decision decision = MameCalls.decide(answer, "Companies_Master");
		MameCallsTest.check("a real success is never touched", decision.outcome == MameCalls.Outcome.AS_IS);
	}

	private static void decide_unrelatedSoftMiss_asIs_configNotFound() throws Exception
	{
		final JsonNode answer = MameCallsTest.parse("{\"success\":false,\"code\":\"CONFIG_NOT_FOUND\"}");
		final MameCalls.Decision decision = MameCalls.decide(answer, "Companies_Master");
		MameCallsTest.check("CONFIG_NOT_FOUND is the caller's own business, never this class'", decision.outcome == MameCalls.Outcome.AS_IS);
	}

	/** `docs/MATCHING.md` &sect;25: a soft miss unrelated to ambiguity, left entirely to the caller. */
	private static void decide_unrelatedSoftMiss_asIs_locatorNotFound() throws Exception
	{
		final JsonNode answer = MameCallsTest.parse("{\"success\":false,\"code\":\"LOCATOR_NOT_FOUND\",\"message\":\"No merge policy found for locator: Contact__preview\"}");
		final MameCalls.Decision decision = MameCalls.decide(answer, "Companies_Master");
		MameCallsTest.check("LOCATOR_NOT_FOUND is not this class' business either", decision.outcome == MameCalls.Outcome.AS_IS);
	}

	/** The EXACT live payload, `docs/MATCHING.md` &sect;28/&sect;2.2.2 - the failure that made this class necessary. */
	private static void decide_ambiguousResolvesToOneCandidate_retryWithFullUrn() throws Exception
	{
		final JsonNode answer = MameCallsTest.parse("{\"code\":\"AMBIGUOUS_TABLE\","
			+ "\"message\":\"Table /root/Contact is declared by more than one data model: urn:ebx:module:EBX FastTrack - TIBCO EBX Presales environment:/WEB-INF/ebx/schemas/Companies.xsd, urn:ebx:publication:Companies_Master\","
			+ "\"nextStep\":\"Pass the optional 'dataModel' to disambiguate; candidates: urn:ebx:module:EBX FastTrack - TIBCO EBX Presales environment:/WEB-INF/ebx/schemas/Companies.xsd, urn:ebx:publication:Companies_Master\","
			+ "\"success\":false}");
		final MameCalls.Decision decision = MameCalls.decide(answer, "Companies_Master");
		MameCallsTest.check("resolves to a retry", decision.outcome == MameCalls.Outcome.RETRY);
		MameCallsTest.check("retries with the full publication URN, never the bare name", "urn:ebx:publication:Companies_Master".equals(decision.dataModel));
	}

	private static void decide_ambiguousNoMatchingCandidate_refuse() throws Exception
	{
		final JsonNode answer = MameCallsTest.parse(
			"{\"code\":\"AMBIGUOUS_TABLE\",\"message\":\"declared by more than one data model: urn:ebx:publication:SomeOtherProject_Master\",\"success\":false}");
		final MameCalls.Decision decision = MameCalls.decide(answer, "Companies_Master");
		MameCallsTest.check("no candidate names this project's own publication: refuse, never guess", decision.outcome == MameCalls.Outcome.REFUSE);
		MameCallsTest.check("carries a message", decision.message != null && !decision.message.isEmpty());
	}

	/** Two DIFFERENT candidate spans that both contain the publication name as a whole token - {@link MameDataModelResolution} itself refuses rather than pick one. */
	private static void decide_ambiguousSeveralMatchingCandidates_refuse() throws Exception
	{
		final JsonNode answer = MameCallsTest.parse("{\"code\":\"AMBIGUOUS_TABLE\","
			+ "\"message\":\"declared by more than one data model: urn:ebx:module:Foo:/WEB-INF/ebx/schemas/Companies_Master.xsd, urn:ebx:publication:Companies_Master\","
			+ "\"success\":false}");
		final MameCalls.Decision decision = MameCalls.decide(answer, "Companies_Master");
		MameCallsTest.check("two candidates both name Companies_Master as a whole token: refuse, never guess which one",
			decision.outcome == MameCalls.Outcome.REFUSE);
	}

	private static JsonNode parse(final String pJson) throws Exception
	{
		return MameCallsTest.MAPPER.readTree(pJson);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
