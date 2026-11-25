package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LocalTool;

/**
 * Lot G2 (2026-10-17): the deterministic guards on a script-constraint suggestion - a function that returns a plain value is no check, an attribute fed only by
 * constants holds a closed set of fixed texts, and a literal pattern that none of the measured values can satisfy would flag every record. The three live
 * cases (project TiersB2CRetestFinal, suggestions 227, 228, 233) are rebuilt with made-up values. JUnit-free like the rest of the module.
 */
public final class ConstraintSanityTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String REGEX_LIB = "library/Fasttrack/lib/validator/regex";
	private static final String NAMES_LIB = "library/Fasttrack/lib/names";

	public static void main(final String[] pArgs)
	{
		ConstraintSanityTest.returnTypeRule();
		ConstraintSanityTest.constantOfReadsTheLastConstantStepOnly();
		ConstraintSanityTest.passRateIsMeasuredOnTheValuesWeightedByRows();
		ConstraintSanityTest.aKeyFunctionIsNotAConstraint();
		ConstraintSanityTest.anAttributeFedOnlyByConstantsGetsNoCheck();
		ConstraintSanityTest.aPatternNoValueCanSatisfyIsRefused();
		ConstraintSanityTest.aPatternTheValuesSatisfyIsAccepted();
		ConstraintSanityTest.withoutEvidenceNothingIsRefusedOnTheData();
		ConstraintSanityTest.theSystemPromptTellsTheRules();
		ConstraintSanityTest.aLiteralMustFitTheParameterType();
		System.out.println("ConstraintSanityTest OK");
	}

	/** Staging integration (2026-10-17, live CapabilitySuggestion 265): one pattern string bound to matchesAny's list<string> parameter. */
	private static void aLiteralMustFitTheParameterType()
	{
		final String list = ConstraintSanity.literalTypeRefusal("matchesAny", "patterns", "list<string>", "[A-Z]{3}-[0-9]+");
		if (list == null || !list.contains("list<string>") || !list.contains("matches rather than matchesAny"))
		{
			throw new AssertionError("a list parameter refuses a literal: " + list);
		}
		if (ConstraintSanity.literalTypeRefusal("f", "n", "set<string>", "a") == null || ConstraintSanity.literalTypeRefusal("f", "n", "decimal", "abc") == null
			|| ConstraintSanity.literalTypeRefusal("f", "b", "boolean", "yes") == null)
		{
			throw new AssertionError("set, non-numeric decimal and non-boolean literals are refused");
		}
		if (ConstraintSanity.literalTypeRefusal("matches", "pattern", "string", "[A-Z]+") != null || ConstraintSanity.literalTypeRefusal("f", "n", "decimal", " 12.5 ") != null
			|| ConstraintSanity.literalTypeRefusal("f", "b", "boolean", "TRUE") != null || ConstraintSanity.literalTypeRefusal("f", "x", null, "anything") != null
			|| ConstraintSanity.literalTypeRefusal("f", "c", "string", "") != null)
		{
			throw new AssertionError("a string, a number, a boolean, an unknown type and a blank string literal are accepted");
		}
	}

	// ---------------------------------------------------------------------------------------------- fixtures

	private static CapabilityLlmPass.FunctionRef matches()
	{
		return new CapabilityLlmPass.FunctionRef(ConstraintSanityTest.REGEX_LIB, "Fasttrack.lib.validator.regex", "Regex", "Regex engine.", "matches",
			Arrays.asList(new CapabilityLlmPass.ParamRef("value", "string"), new CapabilityLlmPass.ParamRef("pattern", "string")), "boolean", "Full-string match.");
	}

	private static CapabilityLlmPass.FunctionRef sortedKeyOf()
	{
		return new CapabilityLlmPass.FunctionRef(ConstraintSanityTest.NAMES_LIB, "Fasttrack.lib.names", "Names", "Name keys.", "sortedKeyOf",
			Arrays.asList(new CapabilityLlmPass.ParamRef("firstName", "string"), new CapabilityLlmPass.ParamRef("lastName", "string")), "string", "Key of a name.");
	}

	private static CapabilityLlmPass.AttributeInfo attribute(final String pName, final ValueEvidence pEvidence)
	{
		return new CapabilityLlmPass.AttributeInfo(pName, "STRING", Integer.valueOf(50), null, null, null, null, null, null, null, false, pEvidence);
	}

	private static CapabilityLlmPass.Session session(final List<CapabilityLlmPass.AttributeInfo> pAttributes, final CapabilityLlmPass.FunctionRef... pFunctions)
	{
		final Map<String, List<CapabilityLlmPass.FunctionRef>> byLibrary = new LinkedHashMap<String, List<CapabilityLlmPass.FunctionRef>>();
		for (final CapabilityLlmPass.FunctionRef function : pFunctions)
		{
			List<CapabilityLlmPass.FunctionRef> list = byLibrary.get(function.libraryIdePath);
			if (list == null)
			{
				list = new ArrayList<CapabilityLlmPass.FunctionRef>();
				byLibrary.put(function.libraryIdePath, list);
			}
			list.add(function);
		}
		final CapabilityLlmPass.EntityInfo entity = new CapabilityLlmPass.EntityInfo("Address", "Address", false, pAttributes,
			Collections.<CapabilityLlmPass.ExistingSuggestion>emptyList());
		final CapabilityLlmPass.Session session = new CapabilityLlmPass.Session(entity, byLibrary, Collections.<CapabilityLlmPass.ConnectorSummary>emptyList(), null);
		for (final String library : byLibrary.keySet())
		{
			final ObjectNode args = ConstraintSanityTest.MAPPER.createObjectNode();
			args.put("library", library);
			session.getLibraryFunctions(args);
		}
		return session;
	}

	private static LocalTool.Result call(final CapabilityLlmPass.Session pSession, final String pAttribute, final String pLibrary, final String pFunction, final ObjectNode pBinding)
	{
		final ObjectNode args = ConstraintSanityTest.MAPPER.createObjectNode();
		if (pAttribute != null)
		{
			args.put("attribute", pAttribute);
		}
		args.put("zone", "STAGING");
		args.put("libraryIdePath", pLibrary);
		args.put("functionName", pFunction);
		args.set("binding", pBinding);
		args.put("severity", "WARN");
		args.put("message", "The value does not look right.");
		args.put("rationale", "because");
		args.put("confidence", 60);
		return pSession.suggestScriptConstraint(args);
	}

	private static ObjectNode regexBinding(final String pAttribute, final String pPattern)
	{
		final ObjectNode binding = ConstraintSanityTest.MAPPER.createObjectNode();
		binding.put("value", pAttribute);
		binding.putObject("pattern").put("literal", pPattern);
		return binding;
	}

	private static ValueEvidence values(final String... pValues)
	{
		final List<ValueEvidence.Weighted> out = new ArrayList<ValueEvidence.Weighted>();
		for (final String value : pValues)
		{
			out.add(new ValueEvidence.Weighted(value, 10L));
		}
		return ValueEvidence.sampled(out);
	}

	// ---------------------------------------------------------------------------------------------- tests

	private static void returnTypeRule()
	{
		ConstraintSanityTest.check("boolean is a verdict", ConstraintSanity.returnTypeRefusal("isValid", "boolean") == null);
		ConstraintSanityTest.check("json_object is a report (the real validate entry points)", ConstraintSanity.returnTypeRefusal("validate", "json_object") == null);
		ConstraintSanityTest.check("an unparsed return type is never refused", ConstraintSanity.returnTypeRefusal("f", null) == null && ConstraintSanity.returnTypeRefusal("f", " ") == null);
		for (final String type : new String[] { "string", "decimal", "date", "datetime", "list<string>" })
		{
			final String refusal = ConstraintSanity.returnTypeRefusal("f", type);
			ConstraintSanityTest.check(type + " is a computed value: " + refusal, refusal != null && refusal.contains("suggest_script_derivation") && refusal.contains("'" + type + "'"));
		}
	}

	private static void constantOfReadsTheLastConstantStepOnly()
	{
		ConstraintSanityTest.check("constant(HOME)", "HOME".equals(ValueEvidence.constantOf("constant(HOME)")));
		ConstraintSanityTest.check("a quoted text", "a b".equals(ValueEvidence.constantOf("constant('a b')")) && "x".equals(ValueEvidence.constantOf("constant(\"x\")")));
		ConstraintSanityTest.check("steps before it do not matter", "BUYER".equals(ValueEvidence.constantOf("trim|constant(BUYER)")));
		ConstraintSanityTest.check("a step after it: unknown output", ValueEvidence.constantOf("constant(HOME)|upper") == null);
		ConstraintSanityTest.check("a plain pipeline", ValueEvidence.constantOf("trim|upper") == null && ValueEvidence.constantOf("") == null && ValueEvidence.constantOf(null) == null);
	}

	private static void passRateIsMeasuredOnTheValuesWeightedByRows()
	{
		final List<ValueEvidence.Weighted> weighted = Arrays.asList(new ValueEvidence.Weighted("123456789", 30L), new ValueEvidence.Weighted("HOME", 70L),
			new ValueEvidence.Weighted("", 500L));
		final ValueEvidence evidence = ValueEvidence.sampled(weighted);
		ConstraintSanityTest.check("30 % of the non-empty rows (the empty ones never count)", Integer.valueOf(30).equals(evidence.passRatePercent("[0-9]{9}", false)));
		ConstraintSanityTest.check("a full match, not a find", Integer.valueOf(0).equals(evidence.passRatePercent("[0-9]{3}", false)));
		ConstraintSanityTest.check("ignore case", Integer.valueOf(70).equals(evidence.passRatePercent("home", true)));
		ConstraintSanityTest.check("an invalid pattern is never judged", evidence.passRatePercent("[", false) == null);
		ConstraintSanityTest.check("nothing to judge", ValueEvidence.sampled(null).passRatePercent("x", false) == null);
		ConstraintSanityTest.check("two judgeable values", evidence.judgeable() == 2);
	}

	/** Suggestion 233 of the live test: a name KEY positioned as a check. */
	private static void aKeyFunctionIsNotAConstraint()
	{
		final CapabilityLlmPass.Session session = ConstraintSanityTest.session(
			Arrays.asList(ConstraintSanityTest.attribute("buyerFirstName", null), ConstraintSanityTest.attribute("buyerLastName", null)), ConstraintSanityTest.sortedKeyOf());
		final ObjectNode binding = ConstraintSanityTest.MAPPER.createObjectNode();
		binding.put("firstName", "buyerFirstName");
		binding.put("lastName", "buyerLastName");
		final LocalTool.Result result = ConstraintSanityTest.call(session, "buyerFirstName", ConstraintSanityTest.NAMES_LIB, "sortedKeyOf", binding);
		ConstraintSanityTest.check("refused: " + result.text, result.isError && result.text.contains("returns 'string'") && result.text.contains("suggest_script_derivation"));
		ConstraintSanityTest.check("not recorded", session.proposedScriptConstraints().isEmpty());
	}

	/** Suggestion 228: a nine-digit pattern on an attribute whose only sources are constant(HOME) / constant(BUYER). */
	private static void anAttributeFedOnlyByConstantsGetsNoCheck()
	{
		final ValueEvidence constants = ValueEvidence.constants(Arrays.asList("HOME", "BUYER", "HOME"));
		ConstraintSanityTest.check("two distinct constants", constants.constantOnly && constants.values.size() == 2);
		final CapabilityLlmPass.Session session = ConstraintSanityTest.session(Arrays.asList(ConstraintSanityTest.attribute("addressType", constants)), ConstraintSanityTest.matches());
		final LocalTool.Result result = ConstraintSanityTest.call(session, "addressType", ConstraintSanityTest.REGEX_LIB, "matches", ConstraintSanityTest.regexBinding("addressType", "[0-9]{9}"));
		ConstraintSanityTest.check("refused: " + result.text, result.isError && result.text.contains("fed only by constants ('HOME', 'BUYER')"));
		ConstraintSanityTest.check("not recorded", session.proposedScriptConstraints().isEmpty());
		// the same without naming the attribute in 'attribute': the binding alone is enough to know what the check reads
		final LocalTool.Result entityLevel = ConstraintSanityTest.call(session, null, ConstraintSanityTest.REGEX_LIB, "matches", ConstraintSanityTest.regexBinding("addressType", "HOME|BUYER"));
		ConstraintSanityTest.check("refused through the binding too: " + entityLevel.text, entityLevel.isError && entityLevel.text.contains("fed only by constants"));
	}

	/** Suggestion 227: a timestamp pattern on a country - here measured on the values of a real country column, none of which a timestamp satisfies. */
	private static void aPatternNoValueCanSatisfyIsRefused()
	{
		final CapabilityLlmPass.Session session = ConstraintSanityTest.session(
			Arrays.asList(ConstraintSanityTest.attribute("country", ConstraintSanityTest.values("FR", "DE", "BE"))), ConstraintSanityTest.matches());
		final LocalTool.Result result = ConstraintSanityTest.call(session, "country", ConstraintSanityTest.REGEX_LIB, "matches",
			ConstraintSanityTest.regexBinding("country", "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"));
		ConstraintSanityTest.check("refused: " + result.text, result.isError && result.text.contains("matches none of the 3 distinct value(s)") && result.text.contains("0 %"));
		ConstraintSanityTest.check("not recorded", session.proposedScriptConstraints().isEmpty());
		ConstraintSanityTest.check("no value in the message to the model", !result.text.contains("'FR'") && !result.text.contains("BE"));
		ConstraintSanityTest.check("a whole-table read does not mention a sample", !result.text.contains("sample"));
		// staging polish (2026-10-17): the same refusal on values read on the first rows of a big table says so
		final List<ValueEvidence.Weighted> read = new ArrayList<ValueEvidence.Weighted>();
		read.add(new ValueEvidence.Weighted("FR", 10L));
		read.add(new ValueEvidence.Weighted("DE", 10L));
		final CapabilityLlmPass.Session sampled = ConstraintSanityTest.session(
			Arrays.asList(ConstraintSanityTest.attribute("country", ValueEvidence.sampled(read, 50000L))), ConstraintSanityTest.matches());
		final LocalTool.Result onSample = ConstraintSanityTest.call(sampled, "country", ConstraintSanityTest.REGEX_LIB, "matches",
			ConstraintSanityTest.regexBinding("country", "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"));
		ConstraintSanityTest.check("refused on a sample, and says where: " + onSample.text, onSample.isError && onSample.text.contains("(a sample of 50,000 rows; 0 % of its rows)"));
	}

	private static void aPatternTheValuesSatisfyIsAccepted()
	{
		final CapabilityLlmPass.Session session = ConstraintSanityTest.session(
			Arrays.asList(ConstraintSanityTest.attribute("country", ConstraintSanityTest.values("FR", "DE", "BE", "usa"))), ConstraintSanityTest.matches());
		final LocalTool.Result result = ConstraintSanityTest.call(session, "country", ConstraintSanityTest.REGEX_LIB, "matches", ConstraintSanityTest.regexBinding("country", "[A-Z]{2}"));
		ConstraintSanityTest.check("a pattern most values satisfy is recorded: " + result.text, !result.isError);
		ConstraintSanityTest.check("recorded", session.proposedScriptConstraints().size() == 1);
	}

	private static void withoutEvidenceNothingIsRefusedOnTheData()
	{
		final CapabilityLlmPass.Session session = ConstraintSanityTest.session(Arrays.asList(ConstraintSanityTest.attribute("code", null)), ConstraintSanityTest.matches());
		final LocalTool.Result result = ConstraintSanityTest.call(session, "code", ConstraintSanityTest.REGEX_LIB, "matches", ConstraintSanityTest.regexBinding("code", "[0-9]{9}"));
		ConstraintSanityTest.check("no values read: the guard claims nothing: " + result.text, !result.isError);
		// another function of the regex library, another library: never judged on a pattern
		final CapabilityLlmPass.Session other = ConstraintSanityTest.session(Arrays.asList(ConstraintSanityTest.attribute("code", ConstraintSanityTest.values("A"))),
			new CapabilityLlmPass.FunctionRef("library/Other/lib/check", "Other.lib.check", "Check", "x", "matches",
				Arrays.asList(new CapabilityLlmPass.ParamRef("value", "string"), new CapabilityLlmPass.ParamRef("pattern", "string")), "boolean", "x"));
		final LocalTool.Result unknown = ConstraintSanityTest.call(other, "code", "library/Other/lib/check", "matches", ConstraintSanityTest.regexBinding("code", "[0-9]{9}"));
		ConstraintSanityTest.check("a function that is not the regex library's is not evaluated: " + unknown.text, !unknown.isError);
	}

	private static void theSystemPromptTellsTheRules()
	{
		final String prompt = CapabilityLlmPass.systemPrompt();
		ConstraintSanityTest.check("verdict or report", prompt.contains("must give a VERDICT (a boolean) or a validation report (a json_object)"));
		ConstraintSanityTest.check("constants and patterns are measured", prompt.contains("only sources are fixed constants") && prompt.contains("literal pattern that none of the attribute's"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("ConstraintSanityTest: " + pWhat);
		}
	}
}
