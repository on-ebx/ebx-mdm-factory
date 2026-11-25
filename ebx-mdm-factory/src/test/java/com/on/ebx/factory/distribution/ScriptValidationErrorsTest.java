package com.on.ebx.factory.distribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class ScriptValidationErrorsTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		ScriptValidationErrorsTest.nullJsonIsEmpty();
		ScriptValidationErrorsTest.noValidationErrorsIsEmpty();
		ScriptValidationErrorsTest.oneErrorWithLineAndColumn();
		ScriptValidationErrorsTest.errorWithNoLineIsMessageOnly();
		ScriptValidationErrorsTest.severalErrorsAreSemicolonJoined();
		System.out.println("ScriptValidationErrorsTest OK");
	}

	private static void nullJsonIsEmpty()
	{
		ScriptValidationErrorsTest.check("null json answers empty", "".equals(ScriptValidationErrors.format(null)));
	}

	private static void noValidationErrorsIsEmpty()
	{
		final ObjectNode json = ScriptValidationErrorsTest.MAPPER.createObjectNode();
		json.put("success", false);
		ScriptValidationErrorsTest.check("no data.validation.errors[] answers empty", "".equals(ScriptValidationErrors.format(json)));
	}

	private static void oneErrorWithLineAndColumn()
	{
		final ObjectNode json = ScriptValidationErrorsTest.errorsOf(ScriptValidationErrorsTest.error(3, 12, "Expression type is not 'DECIMAL'"));
		ScriptValidationErrorsTest.check("line:col: message", "3:12: Expression type is not 'DECIMAL'".equals(ScriptValidationErrors.format(json)));
	}

	private static void errorWithNoLineIsMessageOnly()
	{
		final ObjectNode error = ScriptValidationErrorsTest.MAPPER.createObjectNode();
		error.put("message", "Cannot resolve symbol");
		final ObjectNode json = ScriptValidationErrorsTest.errorsOf(error);
		ScriptValidationErrorsTest.check("no startLine: message alone, no leading 'null:'", "Cannot resolve symbol".equals(ScriptValidationErrors.format(json)));
	}

	private static void severalErrorsAreSemicolonJoined()
	{
		final ObjectMapper mapper = ScriptValidationErrorsTest.MAPPER;
		final ObjectNode json = mapper.createObjectNode();
		final ArrayNode errors = json.putObject("data").putObject("validation").putArray("errors");
		errors.add(ScriptValidationErrorsTest.error(1, 1, "first problem"));
		errors.add(ScriptValidationErrorsTest.error(5, 3, "second problem"));
		ScriptValidationErrorsTest.check("semicolon-joined, in order",
			"1:1: first problem; 5:3: second problem".equals(ScriptValidationErrors.format(json)));
	}

	private static ObjectNode error(final int pLine, final int pColumn, final String pMessage)
	{
		final ObjectNode error = ScriptValidationErrorsTest.MAPPER.createObjectNode();
		error.put("startLine", pLine);
		error.put("startColumn", pColumn);
		error.put("message", pMessage);
		return error;
	}

	private static ObjectNode errorsOf(final ObjectNode pError)
	{
		final ObjectNode json = ScriptValidationErrorsTest.MAPPER.createObjectNode();
		json.putObject("data").putObject("validation").putArray("errors").add(pError);
		return json;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
