package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JUnit-free (same convention as every other test in this package): run the main, a failure throws. Covers
 * {@link DefaultMatchingPolicyGate#decide} against the three call sites its own class Javadoc documents
 * ({@code ebx.MatchingPreviewPolicy#ensureFresh} with no real code, {@code ebx.MatchingConfigurator
 * #configureOne} with a real code, {@code ebx.MatchingPreviewPolicy#remove} either way) plus {@link
 * DefaultMatchingPolicyGate#currentDefault}'s own {@code data}/root read (same shape {@code MameAnswerTest}
 * already covers for other fields).
 */
public final class DefaultMatchingPolicyGateTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		DefaultMatchingPolicyGateTest.ensureFresh_defaultIsThePreview_clears();
		DefaultMatchingPolicyGateTest.ensureFresh_defaultIsBlank_clears();
		DefaultMatchingPolicyGateTest.ensureFresh_defaultIsSomethingElse_none();
		DefaultMatchingPolicyGateTest.configureOne_defaultIsPreview_setsReal();
		DefaultMatchingPolicyGateTest.configureOne_defaultBlank_setsReal();
		DefaultMatchingPolicyGateTest.configureOne_defaultAlreadyReal_none();
		DefaultMatchingPolicyGateTest.configureOne_defaultNamesAStranger_none();
		DefaultMatchingPolicyGateTest.remove_defaultIsPreviewBeingRemoved_noRealLeft_clears();
		DefaultMatchingPolicyGateTest.remove_defaultIsPreviewBeingRemoved_realLeft_setsReal();
		DefaultMatchingPolicyGateTest.remove_defaultIsNotThePreview_none();
		DefaultMatchingPolicyGateTest.currentDefault_readsFromData_withRootFallback();
		DefaultMatchingPolicyGateTest.currentDefault_absent_null();
		System.out.println("DefaultMatchingPolicyGateTest OK");
	}

	// ------------------------------------------------------------------------------------- ensureFresh (no real code)

	/** Live measurement, 2026-09-23 13:30 (`docs/MATCHING.md` &sect;26): the FIRST policy ever generated on a table becomes its default - here, the just-generated preview. */
	private static void ensureFresh_defaultIsThePreview_clears() throws Exception
	{
		DefaultMatchingPolicyGateTest.check("default == previewCode, no real code yet: CLEAR",
			DefaultMatchingPolicyGate.decide("Contact__preview", "Contact__preview", null) == DefaultMatchingPolicyGate.Action.CLEAR);
	}

	private static void ensureFresh_defaultIsBlank_clears() throws Exception
	{
		DefaultMatchingPolicyGateTest.check("blank default, no real code: CLEAR (idempotent - clearing an already-blank default)",
			DefaultMatchingPolicyGate.decide("", "Contact__preview", null) == DefaultMatchingPolicyGate.Action.CLEAR);
		DefaultMatchingPolicyGateTest.check("null default, no real code: CLEAR", DefaultMatchingPolicyGate.decide(null, "Contact__preview", null) == DefaultMatchingPolicyGate.Action.CLEAR);
	}

	private static void ensureFresh_defaultIsSomethingElse_none() throws Exception
	{
		DefaultMatchingPolicyGateTest.check("default names something this fix has no evidence about: NONE, never guessed",
			DefaultMatchingPolicyGate.decide("SomeOtherPolicy", "Contact__preview", null) == DefaultMatchingPolicyGate.Action.NONE);
	}

	// -------------------------------------------------------------------------------- configureOne (a real code known)

	private static void configureOne_defaultIsPreview_setsReal() throws Exception
	{
		DefaultMatchingPolicyGateTest.check("default == previewCode, real code known: SET_REAL",
			DefaultMatchingPolicyGate.decide("Contact__preview", "Contact__preview", "Contact") == DefaultMatchingPolicyGate.Action.SET_REAL);
	}

	private static void configureOne_defaultBlank_setsReal() throws Exception
	{
		DefaultMatchingPolicyGateTest.check("blank default, real code known: SET_REAL",
			DefaultMatchingPolicyGate.decide("", "Contact__preview", "Contact") == DefaultMatchingPolicyGate.Action.SET_REAL);
	}

	private static void configureOne_defaultAlreadyReal_none() throws Exception
	{
		DefaultMatchingPolicyGateTest.check("default already names the real code: NONE, nothing to change",
			DefaultMatchingPolicyGate.decide("Contact", "Contact__preview", "Contact") == DefaultMatchingPolicyGate.Action.NONE);
	}

	private static void configureOne_defaultNamesAStranger_none() throws Exception
	{
		DefaultMatchingPolicyGateTest.check("default names neither blank, the preview, nor the real code: NONE - this fix never touches an unrelated value",
			DefaultMatchingPolicyGate.decide("SomeOtherEntity", "Contact__preview", "Contact") == DefaultMatchingPolicyGate.Action.NONE);
	}

	// ------------------------------------------------------------------------------------------------------- remove

	private static void remove_defaultIsPreviewBeingRemoved_noRealLeft_clears() throws Exception
	{
		DefaultMatchingPolicyGateTest.check("removing the preview that is the default, no real policy left on the table: CLEAR",
			DefaultMatchingPolicyGate.decide("Contact__preview", "Contact__preview", null) == DefaultMatchingPolicyGate.Action.CLEAR);
	}

	private static void remove_defaultIsPreviewBeingRemoved_realLeft_setsReal() throws Exception
	{
		DefaultMatchingPolicyGateTest.check("removing the preview that is the default, a real merge policy remains (firstNonPreviewCode): SET_REAL to it",
			DefaultMatchingPolicyGate.decide("Contact__preview", "Contact__preview", "Contact") == DefaultMatchingPolicyGate.Action.SET_REAL);
	}

	private static void remove_defaultIsNotThePreview_none() throws Exception
	{
		DefaultMatchingPolicyGateTest.check("the default already names the real policy, not the preview being removed: NONE, leave it alone",
			DefaultMatchingPolicyGate.decide("Contact", "Contact__preview", "Contact") == DefaultMatchingPolicyGate.Action.NONE);
	}

	// -------------------------------------------------------------------------------------------------- currentDefault

	private static void currentDefault_readsFromData_withRootFallback() throws Exception
	{
		final JsonNode nested = DefaultMatchingPolicyGateTest.parse("{\"success\":true,\"data\":{\"tableSettings\":{\"defaultMatchingPolicy\":\"Contact__preview\"}}}");
		DefaultMatchingPolicyGateTest.check("reads data.tableSettings.defaultMatchingPolicy",
			"Contact__preview".equals(DefaultMatchingPolicyGate.currentDefault(nested)));

		final JsonNode rootOnly = DefaultMatchingPolicyGateTest.parse("{\"success\":true,\"tableSettings\":{\"defaultMatchingPolicy\":\"Contact\"}}");
		DefaultMatchingPolicyGateTest.check("falls back to the root tableSettings when data does not carry it",
			"Contact".equals(DefaultMatchingPolicyGate.currentDefault(rootOnly)));
	}

	private static void currentDefault_absent_null() throws Exception
	{
		DefaultMatchingPolicyGateTest.check("null json: null, never throws", DefaultMatchingPolicyGate.currentDefault(null) == null);
		final JsonNode noTableSettings = DefaultMatchingPolicyGateTest.parse("{\"success\":true,\"data\":{}}");
		DefaultMatchingPolicyGateTest.check("no tableSettings at all: null", DefaultMatchingPolicyGate.currentDefault(noTableSettings) == null);
	}

	private static JsonNode parse(final String pJson) throws Exception
	{
		return DefaultMatchingPolicyGateTest.MAPPER.readTree(pJson);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
