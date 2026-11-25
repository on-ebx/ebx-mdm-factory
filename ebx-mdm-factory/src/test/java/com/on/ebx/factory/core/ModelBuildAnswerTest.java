package com.on.ebx.factory.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Answers are the shapes read live from {@code build_data_model(dry_run: true)}, 2026-09-30. */
public final class ModelBuildAnswerTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		ModelBuildAnswerTest.builtAndGreenIsOk();
		ModelBuildAnswerTest.builtButNotValidOrCompiledFailsAndNeedsCleanup();
		ModelBuildAnswerTest.opFailureDeletedByTheToolNeedsNoCleanup();
		ModelBuildAnswerTest.opFailureNotDeletedNeedsCleanup();
		ModelBuildAnswerTest.toolErrorNeedsCleanupCheck();
		ModelBuildAnswerTest.noJsonNeedsCleanupCheck();
		ModelBuildAnswerTest.mustDeleteOnlyWhenTheModelIsThere();
		ModelBuildAnswerTest.rehearsalIsJudgedByTheGatesAlone();
		ModelBuildAnswerTest.rehearsalSwitch();
		System.out.println("ModelBuildAnswerTest OK");
	}

	private static JsonNode json(final String pText) throws Exception
	{
		return ModelBuildAnswerTest.MAPPER.readTree(pText);
	}

	/** The live answer of a model that builds: warnings in compileFindings do not fail it. */
	private static void builtAndGreenIsOk() throws Exception
	{
		final ModelBuildAnswer answer = ModelBuildAnswer.read(false, ModelBuildAnswerTest.json(
			"{\"built\":true,\"compiled\":true,\"valid\":true,\"deletedOnFailure\":false,\"compileFindings\":[{\"severity\":\"warning\",\"message\":\"x\"}],\"validationFindings\":[]}"));
		ModelBuildAnswerTest.check("built + valid + compiled -> ok", answer.ok());
		ModelBuildAnswerTest.check("ok -> no cleanup", !answer.cleanupNeeded());
	}

	/** The case the rehearsal used to absorb: the model exists under the real name but fails the terminal gate. */
	private static void builtButNotValidOrCompiledFailsAndNeedsCleanup() throws Exception
	{
		final ModelBuildAnswer notCompiled = ModelBuildAnswer.read(false, ModelBuildAnswerTest.json("{\"built\":true,\"compiled\":false,\"valid\":true,\"deletedOnFailure\":false}"));
		ModelBuildAnswerTest.check("not compiled -> failed", !notCompiled.ok());
		ModelBuildAnswerTest.check("not compiled, not deleted by the tool -> cleanup needed", notCompiled.cleanupNeeded());
		final ModelBuildAnswer notValid = ModelBuildAnswer.read(false, ModelBuildAnswerTest.json("{\"built\":true,\"compiled\":true,\"valid\":false}"));
		ModelBuildAnswerTest.check("not valid -> failed", !notValid.ok());
		ModelBuildAnswerTest.check("no deletedOnFailure field reads as not deleted -> cleanup needed", notValid.cleanupNeeded());
	}

	/** Atomic build: an op failure is rolled back by the tool itself. */
	private static void opFailureDeletedByTheToolNeedsNoCleanup() throws Exception
	{
		final ModelBuildAnswer answer = ModelBuildAnswer.read(false, ModelBuildAnswerTest.json(
			"{\"built\":false,\"compiled\":false,\"valid\":false,\"deletedOnFailure\":true,\"validationFindings\":[{\"errorCode\":\"BUILD_OP_FAILED\",\"severity\":\"error\"}]}"));
		ModelBuildAnswerTest.check("op failure -> failed", !answer.ok());
		ModelBuildAnswerTest.check("op failure deleted by the tool -> no cleanup", !answer.cleanupNeeded());
		ModelBuildAnswerTest.check("... and never a delete, even if a lookup said the model exists", !answer.mustDelete(true));
	}

	/** The exact dry-run shape of an op failure, seen as a REAL build answer only if the tool did not delete: be safe. */
	private static void opFailureNotDeletedNeedsCleanup() throws Exception
	{
		final ModelBuildAnswer answer = ModelBuildAnswer.read(false,
			ModelBuildAnswerTest.json("{\"built\":false,\"compiled\":false,\"valid\":false,\"deletedOnFailure\":false}"));
		ModelBuildAnswerTest.check("op failure, not deleted -> cleanup needed", answer.cleanupNeeded());
	}

	private static void toolErrorNeedsCleanupCheck() throws Exception
	{
		final ModelBuildAnswer answer = ModelBuildAnswer.read(true, ModelBuildAnswerTest.json("{\"built\":true,\"compiled\":true,\"valid\":true}"));
		ModelBuildAnswerTest.check("a tool error is never ok, whatever the body says", !answer.ok());
		ModelBuildAnswerTest.check("tool error -> cleanup check needed", answer.cleanupNeeded());
		ModelBuildAnswerTest.check("tool error flag kept", answer.toolError());
	}

	private static void noJsonNeedsCleanupCheck()
	{
		final ModelBuildAnswer answer = ModelBuildAnswer.read(false, null);
		ModelBuildAnswerTest.check("no answer body -> not ok", !answer.ok());
		ModelBuildAnswerTest.check("no answer body -> cleanup check needed", answer.cleanupNeeded());
	}

	private static void mustDeleteOnlyWhenTheModelIsThere() throws Exception
	{
		final ModelBuildAnswer answer = ModelBuildAnswer.read(false, ModelBuildAnswerTest.json("{\"built\":true,\"compiled\":false,\"valid\":true}"));
		ModelBuildAnswerTest.check("cleanup needed and model found -> delete", answer.mustDelete(true));
		ModelBuildAnswerTest.check("cleanup needed but model not found -> nothing to delete", !answer.mustDelete(false));
		final ModelBuildAnswer ok = ModelBuildAnswer.read(false, ModelBuildAnswerTest.json("{\"built\":true,\"compiled\":true,\"valid\":true}"));
		ModelBuildAnswerTest.check("a good model is never deleted", !ok.mustDelete(true));
	}

	/** A dry run always answers built:false; the rehearsal is judged by valid + compiled only. */
	private static void rehearsalIsJudgedByTheGatesAlone() throws Exception
	{
		final ModelBuildAnswer dry = ModelBuildAnswer.read(false, ModelBuildAnswerTest.json("{\"built\":false,\"compiled\":true,\"valid\":true,\"deletedOnFailure\":false}"));
		ModelBuildAnswerTest.check("dry run answer: gates passed", dry.gatesPassed());
		final ModelBuildAnswer dryBad = ModelBuildAnswer.read(false, ModelBuildAnswerTest.json("{\"built\":false,\"compiled\":false,\"valid\":false}"));
		ModelBuildAnswerTest.check("dry run op failure: gates not passed", !dryBad.gatesPassed());
	}

	private static void rehearsalSwitch()
	{
		ModelBuildAnswerTest.check("off by default (property unset)", !ModelRehearsal.enabled(null));
		ModelBuildAnswerTest.check("empty is off", !ModelRehearsal.enabled(""));
		ModelBuildAnswerTest.check("false is off", !ModelRehearsal.enabled("false"));
		ModelBuildAnswerTest.check("1 is off (only the literal true)", !ModelRehearsal.enabled("1"));
		ModelBuildAnswerTest.check("true is on", ModelRehearsal.enabled("true"));
		ModelBuildAnswerTest.check("TRUE with spaces is on", ModelRehearsal.enabled(" TRUE "));
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pLabel);
		}
	}
}
