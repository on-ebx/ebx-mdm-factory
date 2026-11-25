package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JUnit-free, like the sibling classes' own tests: run the main, a failure throws. Fixtures reproduce the
 * exact captured answer that exposed the 2026-09-22 validate-response-shape bug (see {@link MameAnswer}'s
 * own Javadoc) plus the {@code activate_matching} shape the fix generalizes from.
 */
public final class MameAnswerTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MameAnswerTest.capturedValidateAnswer_validReadFromData();
		MameAnswerTest.rootLevelValid_stillReadsAsValid_backwardCompatibility();
		MameAnswerTest.validateSoftMiss_successFalse_reportedAsSuchNotAsInvalid();
		MameAnswerTest.activateAnswer_stateReadFromData();
		MameAnswerTest.codeAndSuccess_nullJson_defaultsApply();
		System.out.println("MameAnswerTest OK");
	}

	/**
	 * The EXACT answer measured live, 2026-09-22 08:58 (`docs/MATCHING.md`, coordinator run of {@code
	 * Companies}): {@code {"data":{"messages":[],"table":"/root/Company","valid":true},"success":true}} -
	 * the bug this class fixes read {@code valid} at the root ({@code MissingNode} -&gt; false) instead of
	 * under {@code data}.
	 */
	private static void capturedValidateAnswer_validReadFromData() throws Exception
	{
		final JsonNode answer = MameAnswerTest.parse("{\"success\":true,\"data\":{\"messages\":[],\"table\":\"/root/Company\",\"valid\":true}}");
		MameAnswerTest.check("success true at the root", MameAnswer.success(answer, false));
		MameAnswerTest.check("valid true, read from data.valid", MameAnswer.field(answer, "valid").asBoolean(false));
		MameAnswerTest.check("root carries no valid key at all - proves the read really comes from data, not a coincidence", answer.path("valid").isMissingNode());
	}

	/**
	 * Backward compatibility: an answer that (unlike the captured shape above) carries {@code valid} at the
	 * ROOT, with no {@code data} wrapper at all, must still read correctly - {@link MameAnswer#field} falls
	 * back to the root when {@code data} does not carry the field.
	 */
	private static void rootLevelValid_stillReadsAsValid_backwardCompatibility() throws Exception
	{
		final JsonNode answer = MameAnswerTest.parse("{\"success\":true,\"valid\":true}");
		MameAnswerTest.check("valid true, read from the root fallback (no data.valid present)", MameAnswer.field(answer, "valid").asBoolean(false));
	}

	/**
	 * A soft miss on {@code validate_matching_config} (success:false, e.g. a stale {@code CONFIG_NOT_FOUND})
	 * must be told apart from "the policy is invalid" - {@link MameAnswer#success} reports it as false, never
	 * folded into a {@code valid=false} read (the exact accident {@code ebx.MatchingConfigurator} fixed).
	 */
	private static void validateSoftMiss_successFalse_reportedAsSuchNotAsInvalid() throws Exception
	{
		final JsonNode answer = MameAnswerTest.parse("{\"success\":false,\"code\":\"CONFIG_NOT_FOUND\",\"message\":\"no such configuration\"}");
		MameAnswerTest.check("success is false", !MameAnswer.success(answer, false));
		MameAnswerTest.check("code surfaces the real soft-miss code", "CONFIG_NOT_FOUND".equals(MameAnswer.code(answer)));
	}

	/** {@code activate_matching}'s own shape ({@code ebx.MatchingConfigurator}'s Javadoc quotes it as already correct before this fix): {@code state} under {@code data}. */
	private static void activateAnswer_stateReadFromData() throws Exception
	{
		final JsonNode answer = MameAnswerTest.parse("{\"success\":true,\"data\":{\"state\":\"ENABLED\"}}");
		MameAnswerTest.check("state ENABLED, read from data.state", "ENABLED".equals(MameAnswer.field(answer, "state").asText(null)));
	}

	private static void codeAndSuccess_nullJson_defaultsApply()
	{
		MameAnswerTest.check("success(null, true) is true", MameAnswer.success(null, true));
		MameAnswerTest.check("success(null, false) is false", !MameAnswer.success(null, false));
		MameAnswerTest.check("code(null) is null", MameAnswer.code(null) == null);
		MameAnswerTest.check("field(null, x) is a MissingNode, not null, not a crash", MameAnswer.field(null, "x").isMissingNode());
	}

	private static JsonNode parse(final String pJson) throws Exception
	{
		return MameAnswerTest.MAPPER.readTree(pJson);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
