package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.wizard.EngineMessageCodec;
import com.orchestranetworks.service.OperationException;

/**
 * Lot M6 (2026-10-18, docs/MATCHING.md &sect;61): the {@code validate_matching_config} call of a configure, with the fallback measured
 * live on E2EMASTER20261018000216 / E2EMASTER20261017235630 (6.3.0-rc3 + Match and Merge RC3). On the main table of a declared business
 * object the tool is called with {@code businessObject} (it validates every configured member); the add-on's own validator then dies
 * in its 7th iterator ({@code BusinessObjectValidationIterator} &rarr; {@code buildResult} looks the business-object record up as a
 * {@code MatchingTableConfig} and finds none) as soon as the report has one item, and the MCP client receives a JSON-RPC ERROR -
 * {@code tools/call: Tool invocation failed: Cannot invoke "...MatchingTableConfig.getDataModel()" because "matchingTableRecord" is
 * null} - while the configuration itself is fine (the same call with {@code table} answers {@code valid:true}, the object reads
 * ENABLED with its metadatasets). A product bug of the add-on's tool, {@code
 * product-bugs/2026-10-18-mame-validate-business-object-npe.md}.
 * <p>
 * What this class does: when the business-object validation ends in a THROWN error (a {@link McpException}, or a tool-level {@code
 * isError} answer - a transport/tool failure, never a soft miss {@code success:false}), it validates the main table alone ({@code
 * table} + the data model, no {@code businessObject}), records {@code validateBusinessObject = {arguments, thrown, fallback: "table"}}
 * in the step detail and hands back the table answer, which the caller checks exactly as before (soft miss, {@code valid:false}: the
 * same refusals as today). If the fallback itself throws, the exception propagates: today's refusal. The caller adds the human note
 * ({@link #note}, bundle key {@link #NOTE_KEY}, en/fr, stored with its code marker like the RunTexts sentences) to the step and to the
 * configure job, so the person sees why the object was not validated as a whole.
 * <p>
 * Pure apart from the {@link Port}: the tests drive it with a hand-written port (same discipline as {@code
 * MatchingActivationGuard.StatusPort} - never a real {@code McpClient}, whose {@code ToolResult} cannot be built outside its package).
 */
final class MatchingValidation
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The bundle key of the note; {0} the business object (main table path), {1} the table validated alone, {2} the product-bug file. */
	static final String NOTE_KEY = "wizard.step6.configure.note.validateBusinessObjectFallback";

	/** The product-bug text handed to the editor (written by the product owner; named here so the note points at it). */
	static final String PRODUCT_BUG_FILE = "product-bugs/2026-10-18-mame-validate-business-object-npe.md";

	/** The English of {@link #NOTE_KEY}, once; {@code Messages_en} says the same ({@code MatchingValidationTest} pins it). */
	static final String NOTE_ENGLISH = "Match and Merge could not validate the business object {0}: its validation tool failed on an error of its own (product bug, see {2})."
		+ " The main table {1} was validated alone and the configuration went on.";

	/** The note is stored with its marker in the step detail; the text gives way to the marker past this length (same budget as a step message). */
	static final int NOTE_MAX = 2000;

	private MatchingValidation()
	{
	}

	/** What one {@code validate_matching_config} call answered - the three fields of {@code McpClient.ToolResult}, buildable in a test. */
	static final class Answer
	{
		final boolean error;
		final String text;
		final JsonNode json;

		Answer(final boolean pError, final String pText, final JsonNode pJson)
		{
			this.error = pError;
			this.text = pText;
			this.json = pJson;
		}

		static Answer of(final McpClient.ToolResult pResult)
		{
			return new Answer(pResult.error, pResult.text, pResult.json);
		}
	}

	/** The one call this class makes: {@code validate_matching_config} with the arguments given ({@code dataModel} is the port's business). */
	interface Port
	{
		Answer validate(ObjectNode pArgs) throws McpException, OperationException;
	}

	/** The answer the caller checks (soft miss, {@code valid}), and whether it came from the fallback. */
	static final class Outcome
	{
		/** The answer to check: the business object's when it answered, the main table's after a fallback. */
		final Answer answer;
		/** True when the business-object validation threw and the main table was validated alone. */
		final boolean fellBack;
		/** The thrown text (exception message, or the tool error text) when {@link #fellBack}; null otherwise. */
		final String thrown;

		Outcome(final Answer pAnswer, final boolean pFellBack, final String pThrown)
		{
			this.answer = pAnswer;
			this.fellBack = pFellBack;
			this.thrown = pThrown;
		}
	}

	/**
	 * Validates: with {@code businessObject} when {@code pBusinessObject} is not null (one call, no fallback when it answers - a soft miss or
	 * {@code valid:false} is an ANSWER, handed back as such), else with {@code table}. Writes {@code validate} (the call checked) and, after a
	 * fallback, {@code validateBusinessObject} into {@code pDetail}.
	 *
	 * @throws McpException the call to check threw (after a fallback: the table call) - today's refusal path, unchanged
	 */
	static Outcome validate(final Port pPort, final String pTablePath, final String pBusinessObject, final ObjectNode pDetail) throws McpException, OperationException
	{
		if (pBusinessObject == null)
		{
			final ObjectNode tableArgs = MatchingValidation.MAPPER.createObjectNode();
			tableArgs.put("table", pTablePath);
			final Answer tableAnswer = pPort.validate(tableArgs);
			pDetail.set("validate", MatchingValidation.toolDetail(tableArgs, tableAnswer));
			return new Outcome(tableAnswer, false, null);
		}
		final ObjectNode boArgs = MatchingValidation.MAPPER.createObjectNode();
		// validate_matching_config takes exactly one of table / businessObject: with a business object it validates every configured member
		boArgs.put("businessObject", pBusinessObject);
		String thrown = null;
		Answer boAnswer = null;
		try
		{
			boAnswer = pPort.validate(boArgs);
			if (boAnswer.error)
			{
				// a tool-level error (isError) is a failure of the tool, not an answer about the configuration: same fallback as a thrown call
				thrown = MatchingValidation.shortText(boAnswer.text);
			}
		}
		catch (final McpException ex)
		{
			thrown = ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
		}
		if (thrown == null)
		{
			pDetail.set("validate", MatchingValidation.toolDetail(boArgs, boAnswer));
			return new Outcome(boAnswer, false, null);
		}
		final ObjectNode fallback = pDetail.putObject("validateBusinessObject");
		fallback.set("arguments", boArgs);
		fallback.put("thrown", thrown);
		fallback.put("fallback", "table");
		final ObjectNode tableArgs = MatchingValidation.MAPPER.createObjectNode();
		tableArgs.put("table", pTablePath);
		// a throw here propagates: the fallback failed too, today's refusal stands
		final Answer tableAnswer = pPort.validate(tableArgs);
		pDetail.set("validate", MatchingValidation.toolDetail(tableArgs, tableAnswer));
		return new Outcome(tableAnswer, true, thrown);
	}

	/** The English note, as the step message and the configure job carry it (plain text, no marker). */
	static String noteEnglish(final String pBusinessObject, final String pTablePath)
	{
		return new java.text.MessageFormat(MatchingValidation.NOTE_ENGLISH, java.util.Locale.ENGLISH)
			.format(new Object[] { pBusinessObject, pTablePath, MatchingValidation.PRODUCT_BUG_FILE });
	}

	/** The note with its code marker: the English text plus {@code [mdmf:<key>|...]}, which the wizard renders in the person's language ({@code RunPanelText.display}). */
	static String note(final String pBusinessObject, final String pTablePath)
	{
		return EngineMessageCodec.encode(MatchingValidation.noteEnglish(pBusinessObject, pTablePath), MatchingValidation.NOTE_KEY,
			new Object[] { pBusinessObject, pTablePath, MatchingValidation.PRODUCT_BUG_FILE }, MatchingValidation.NOTE_MAX);
	}

	/** Same shape as {@code MatchingConfigurator.toolDetail}, for an {@link Answer}. */
	static ObjectNode toolDetail(final ObjectNode pArguments, final Answer pAnswer)
	{
		final ObjectNode node = MatchingValidation.MAPPER.createObjectNode();
		node.set("arguments", pArguments);
		node.put("error", pAnswer.error);
		if (pAnswer.json != null)
		{
			node.set("result", pAnswer.json);
		}
		else
		{
			node.put("resultText", pAnswer.text);
		}
		return node;
	}

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
