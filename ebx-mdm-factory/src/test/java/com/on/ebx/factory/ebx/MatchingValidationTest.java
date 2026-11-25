package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.wizard.EngineMessageCodec;
import com.on.ebx.factory.wizard.RunPanelText;
import com.on.ebx.factory.wizard.WizardMessages;

/**
 * JUnit-free: run the main, a failure throws. Lot M6 (2026-10-18, docs/MATCHING.md &sect;61): the fallback of {@link MatchingValidation} when the add-on's
 * business-object validation throws - the exact live text of E2EMASTER20261018000216 - and the note it leaves. Driven by a hand-written {@link
 * MatchingValidation.Port} (the same "never construct a real McpClient in a test" discipline as {@code MatchingActivationGuardTest}).
 */
public final class MatchingValidationTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The live JSON-RPC error text of 2026-10-18 02:07 (kernel.log: MameConfigValidator.validateBusinessObject ... buildInvalidIssue). */
	private static final String LIVE_THROWN = "tools/call: Tool invocation failed: Cannot invoke \"com.orchestranetworks.addon.mame.model.config.MatchingTableConfig.getDataModel()\" because \"matchingTableRecord\" is null";

	private static final String BO = "/root/Client";

	public static void main(final String[] pArgs) throws Exception
	{
		MatchingValidationTest.businessObjectThrows_tableValid_fallsBackWithTheNote();
		MatchingValidationTest.businessObjectToolError_isAThrow_fallsBack();
		MatchingValidationTest.bothThrow_theTableCallsExceptionPropagates_refusalUnchanged();
		MatchingValidationTest.fallbackReportsErrors_handedBackForTodaysRefusal();
		MatchingValidationTest.businessObjectAnswers_noFallback();
		MatchingValidationTest.businessObjectSoftMiss_isAnAnswerNotAFallback();
		MatchingValidationTest.plainTable_oneCallNoFallback();
		MatchingValidationTest.noteEnglishIsTheBundlesEnglish_andRendersInFrench();
		MatchingValidationTest.jobMessageCarriesTheEnglishWithoutTheMarker();
		MatchingValidationTest.readerExtractsTheNotesOfAConfigureDetail();
		System.out.println("MatchingValidationTest OK");
	}

	/** A port scripted per argument shape: what to answer (or throw) for the business-object call and for the table call; records the calls in order. */
	private static final class ScriptedPort implements MatchingValidation.Port
	{
		final List<String> calls = new ArrayList<String>();
		final String boThrows;
		final MatchingValidation.Answer boAnswer;
		final String tableThrows;
		final MatchingValidation.Answer tableAnswer;

		ScriptedPort(final String pBoThrows, final MatchingValidation.Answer pBoAnswer, final String pTableThrows, final MatchingValidation.Answer pTableAnswer)
		{
			this.boThrows = pBoThrows;
			this.boAnswer = pBoAnswer;
			this.tableThrows = pTableThrows;
			this.tableAnswer = pTableAnswer;
		}

		@Override
		public MatchingValidation.Answer validate(final ObjectNode pArgs) throws McpException
		{
			if (pArgs.hasNonNull("businessObject"))
			{
				MatchingValidationTest.check("exactly one of table / businessObject", !pArgs.has("table"));
				this.calls.add("businessObject=" + pArgs.path("businessObject").asText());
				if (this.boThrows != null)
				{
					throw new McpException(-32603, this.boThrows);
				}
				return this.boAnswer;
			}
			this.calls.add("table=" + pArgs.path("table").asText());
			if (this.tableThrows != null)
			{
				throw new McpException(-32603, this.tableThrows);
			}
			return this.tableAnswer;
		}
	}

	private static MatchingValidation.Answer answer(final String pJson) throws Exception
	{
		final JsonNode json = MatchingValidationTest.MAPPER.readTree(pJson);
		return new MatchingValidation.Answer(false, pJson, json);
	}

	/** The live table answer of 2026-10-18 02:07: valid, two warnings. */
	private static MatchingValidation.Answer tableValidWithWarnings() throws Exception
	{
		return MatchingValidationTest.answer("{\"success\":true,\"data\":{\"table\":\"/root/Client\",\"valid\":true,\"messages\":["
			+ "{\"severity\":\"WARNING\",\"code\":\"W1\",\"message\":\"first warning\"},{\"severity\":\"WARNING\",\"code\":\"W2\",\"message\":\"second warning\"}]}}");
	}

	private static void businessObjectThrows_tableValid_fallsBackWithTheNote() throws Exception
	{
		final ScriptedPort port = new ScriptedPort(MatchingValidationTest.LIVE_THROWN, null, null, MatchingValidationTest.tableValidWithWarnings());
		final ObjectNode detail = MatchingValidationTest.MAPPER.createObjectNode();
		final MatchingValidation.Outcome outcome = MatchingValidation.validate(port, MatchingValidationTest.BO, MatchingValidationTest.BO, detail);
		MatchingValidationTest.check("two calls, the object first, then the table alone", port.calls.size() == 2 && port.calls.get(0).equals("businessObject=/root/Client")
			&& port.calls.get(1).equals("table=/root/Client"));
		MatchingValidationTest.check("fell back", outcome.fellBack);
		MatchingValidationTest.check("the thrown text is kept whole", MatchingValidationTest.LIVE_THROWN.equals(outcome.thrown));
		MatchingValidationTest.check("the answer to check is the table's, valid", !outcome.answer.error && outcome.answer.json.path("data").path("valid").asBoolean(false));
		final JsonNode fallback = detail.path("validateBusinessObject");
		MatchingValidationTest.check("detail.validateBusinessObject.thrown", MatchingValidationTest.LIVE_THROWN.equals(fallback.path("thrown").asText()));
		MatchingValidationTest.check("detail.validateBusinessObject.fallback = table", "table".equals(fallback.path("fallback").asText()));
		MatchingValidationTest.check("detail.validateBusinessObject.arguments names the object", "/root/Client".equals(fallback.path("arguments").path("businessObject").asText()));
		MatchingValidationTest.check("detail.validate is the table call", "/root/Client".equals(detail.path("validate").path("arguments").path("table").asText())
			&& !detail.path("validate").path("arguments").has("businessObject") && !detail.path("validate").path("error").asBoolean(true));
		// the note
		final String english = MatchingValidation.noteEnglish(MatchingValidationTest.BO, MatchingValidationTest.BO);
		MatchingValidationTest.check("the English note names the object, the table and the product-bug file", english.contains("business object /root/Client")
			&& english.contains("main table /root/Client was validated alone") && english.contains(MatchingValidation.PRODUCT_BUG_FILE) && english.indexOf("[mdmf:") < 0);
		final String coded = MatchingValidation.note(MatchingValidationTest.BO, MatchingValidationTest.BO);
		final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(coded);
		MatchingValidationTest.check("the coded note carries the bundle key and its three arguments", MatchingValidation.NOTE_KEY.equals(decoded.key) && decoded.args.length == 3
			&& "/root/Client".equals(decoded.args[0]) && "/root/Client".equals(decoded.args[1]) && MatchingValidation.PRODUCT_BUG_FILE.equals(decoded.args[2]));
		MatchingValidationTest.check("the coded note's text is the English note", english.equals(decoded.raw));
	}

	private static void businessObjectToolError_isAThrow_fallsBack() throws Exception
	{
		final MatchingValidation.Answer toolError = new MatchingValidation.Answer(true, "Tool invocation failed: matchingTableRecord is null", null);
		final ScriptedPort port = new ScriptedPort(null, toolError, null, MatchingValidationTest.tableValidWithWarnings());
		final ObjectNode detail = MatchingValidationTest.MAPPER.createObjectNode();
		final MatchingValidation.Outcome outcome = MatchingValidation.validate(port, MatchingValidationTest.BO, MatchingValidationTest.BO, detail);
		MatchingValidationTest.check("a tool-level error (isError) is a failure of the tool: same fallback", outcome.fellBack && port.calls.size() == 2);
		MatchingValidationTest.check("the tool error text is the thrown text", "Tool invocation failed: matchingTableRecord is null".equals(detail.path("validateBusinessObject").path("thrown").asText()));
		MatchingValidationTest.check("the table answer is checked", !outcome.answer.error);
	}

	private static void bothThrow_theTableCallsExceptionPropagates_refusalUnchanged() throws Exception
	{
		final ScriptedPort port = new ScriptedPort(MatchingValidationTest.LIVE_THROWN, null, "tools/call: the table call failed too", null);
		final ObjectNode detail = MatchingValidationTest.MAPPER.createObjectNode();
		try
		{
			MatchingValidation.validate(port, MatchingValidationTest.BO, MatchingValidationTest.BO, detail);
			MatchingValidationTest.check("both throw: the exception must propagate (today's refusal)", false);
		}
		catch (final McpException ex)
		{
			MatchingValidationTest.check("the exception that propagates is the table call's", "tools/call: the table call failed too".equals(ex.getMessage()));
		}
		MatchingValidationTest.check("both calls were made", port.calls.size() == 2);
		MatchingValidationTest.check("the first failure is still recorded", MatchingValidationTest.LIVE_THROWN.equals(detail.path("validateBusinessObject").path("thrown").asText()));
		MatchingValidationTest.check("no validate entry: nothing answered", !detail.has("validate"));
	}

	private static void fallbackReportsErrors_handedBackForTodaysRefusal() throws Exception
	{
		final MatchingValidation.Answer invalid = MatchingValidationTest.answer("{\"success\":true,\"data\":{\"table\":\"/root/Client\",\"valid\":false,\"messages\":[{\"severity\":\"ERROR\",\"code\":\"E1\",\"message\":\"bad\"}]}}");
		final ScriptedPort port = new ScriptedPort(MatchingValidationTest.LIVE_THROWN, null, null, invalid);
		final ObjectNode detail = MatchingValidationTest.MAPPER.createObjectNode();
		final MatchingValidation.Outcome outcome = MatchingValidation.validate(port, MatchingValidationTest.BO, MatchingValidationTest.BO, detail);
		MatchingValidationTest.check("fell back", outcome.fellBack);
		MatchingValidationTest.check("the table's valid:false is handed back as it is - the caller's 'reported errors' refusal is unchanged",
			!outcome.answer.json.path("data").path("valid").asBoolean(true));
		MatchingValidationTest.check("describeValidationMessages reads it", MatchingConfigurator.describeValidationMessages(outcome.answer.json).contains("E1"));
	}

	private static void businessObjectAnswers_noFallback() throws Exception
	{
		final MatchingValidation.Answer boValid = MatchingValidationTest.answer("{\"success\":true,\"data\":{\"businessObject\":\"/root/Client\",\"valid\":true,\"messages\":[]}}");
		final ScriptedPort port = new ScriptedPort(null, boValid, "must not be called", null);
		final ObjectNode detail = MatchingValidationTest.MAPPER.createObjectNode();
		final MatchingValidation.Outcome outcome = MatchingValidation.validate(port, MatchingValidationTest.BO, MatchingValidationTest.BO, detail);
		MatchingValidationTest.check("one call, the object", port.calls.size() == 1 && port.calls.get(0).startsWith("businessObject="));
		MatchingValidationTest.check("no fallback", !outcome.fellBack && outcome.thrown == null);
		MatchingValidationTest.check("detail.validate is the object call", "/root/Client".equals(detail.path("validate").path("arguments").path("businessObject").asText()));
		MatchingValidationTest.check("no validateBusinessObject entry", !detail.has("validateBusinessObject"));
	}

	private static void businessObjectSoftMiss_isAnAnswerNotAFallback() throws Exception
	{
		final MatchingValidation.Answer softMiss = MatchingValidationTest.answer("{\"success\":false,\"code\":\"CONFIG_NOT_FOUND\",\"message\":\"no configuration\"}");
		final ScriptedPort port = new ScriptedPort(null, softMiss, "must not be called", null);
		final ObjectNode detail = MatchingValidationTest.MAPPER.createObjectNode();
		final MatchingValidation.Outcome outcome = MatchingValidation.validate(port, MatchingValidationTest.BO, MatchingValidationTest.BO, detail);
		MatchingValidationTest.check("a soft miss is an answer: no fallback, one call", !outcome.fellBack && port.calls.size() == 1);
		MatchingValidationTest.check("the soft miss is handed back for the caller's own refusal", "CONFIG_NOT_FOUND".equals(outcome.answer.json.path("code").asText()));
	}

	private static void plainTable_oneCallNoFallback() throws Exception
	{
		final ScriptedPort port = new ScriptedPort("must not be called", null, null, MatchingValidationTest.tableValidWithWarnings());
		final ObjectNode detail = MatchingValidationTest.MAPPER.createObjectNode();
		final MatchingValidation.Outcome outcome = MatchingValidation.validate(port, "/root/Personne", null, detail);
		MatchingValidationTest.check("a plain table: one table call", port.calls.size() == 1 && port.calls.get(0).equals("table=/root/Personne"));
		MatchingValidationTest.check("no fallback", !outcome.fellBack);
		MatchingValidationTest.check("detail.validate is the table call", "/root/Personne".equals(detail.path("validate").path("arguments").path("table").asText()));
	}

	private static void noteEnglishIsTheBundlesEnglish_andRendersInFrench()
	{
		final String english = MatchingValidation.noteEnglish(MatchingValidationTest.BO, "/root/Client");
		final String bundle = WizardMessages.get(Locale.ENGLISH, MatchingValidation.NOTE_KEY, MatchingValidationTest.BO, "/root/Client", MatchingValidation.PRODUCT_BUG_FILE);
		MatchingValidationTest.check("Messages_en says what the class says (the English lives once)", english.equals(bundle));
		final String coded = MatchingValidation.note(MatchingValidationTest.BO, "/root/Client");
		MatchingValidationTest.check("displayed in English: the note itself", english.equals(RunPanelText.display(coded, Locale.ENGLISH)));
		final String french = RunPanelText.display(coded, Locale.FRENCH);
		MatchingValidationTest.check("displayed in French: the bundle's sentence with the three arguments", french.contains("objet métier /root/Client")
			&& french.contains("table principale /root/Client") && french.contains(MatchingValidation.PRODUCT_BUG_FILE) && french.indexOf("{0}") < 0 && french.indexOf("[mdmf:") < 0);
	}

	private static void jobMessageCarriesTheEnglishWithoutTheMarker()
	{
		final java.util.List<String> none = java.util.Collections.<String>emptyList();
		final String base = MatchingConfigureRun.message(false, null, 1, 0, 0, 0);
		final String coded = MatchingValidation.note(MatchingValidationTest.BO, MatchingValidationTest.BO);
		final String message = MatchingConfigureRun.withNotes(base, none, none, none, none, java.util.Collections.singletonList(coded));
		MatchingValidationTest.check("the job message starts with the counts and carries the English note", message.startsWith(base)
			&& message.contains(" NOTE: " + MatchingValidation.noteEnglish(MatchingValidationTest.BO, MatchingValidationTest.BO)));
		MatchingValidationTest.check("no marker in the job message (it is several sentences, never one coded sentence)", message.indexOf("[mdmf:") < 0);
		MatchingValidationTest.check("the configure is a success: 1 configured, 0 refused", message.startsWith("1 entity configured, 0 refused, 0 not accepted."));
	}

	private static void readerExtractsTheNotesOfAConfigureDetail() throws Exception
	{
		final String coded = MatchingValidation.note(MatchingValidationTest.BO, MatchingValidationTest.BO);
		final ObjectNode result = MatchingValidationTest.MAPPER.createObjectNode();
		result.put("project", "P");
		result.putArray("configured").add("Client");
		result.putArray("validationNotes").add(coded);
		final List<String> notes = MatchingActivityReader.validationNotes(result.toString());
		MatchingValidationTest.check("the reader hands the coded note to the panel", notes.size() == 1 && coded.equals(notes.get(0)));
		MatchingValidationTest.check("no detail, garbage, no array: no note", MatchingActivityReader.validationNotes(null).isEmpty() && MatchingActivityReader.validationNotes("{not json").isEmpty()
			&& MatchingActivityReader.validationNotes("{\"project\":\"P\"}").isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
