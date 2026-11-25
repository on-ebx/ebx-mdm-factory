package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.ModelBuildAnswer;
import com.on.ebx.factory.core.ModelRehearsal;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.onwbp.adaptation.Adaptation;

/**
 * The one {@code build_data_model} call of a generated model (landing, staging, master), shared by the three
 * generators (wave 2, lot V2-C, {@code docs/VOLUME.md}). On ON EBX 6.3.0-rc2 a DMA model was built one element per
 * transaction (~1 s per field); MEASURED AGAIN 2026-11-12 on 6.3.0-gp (F3-102): 109 operations (4 tables, 100 fields) in ONE transaction took 1.15 s, about 10 ms per
 * operation, the whole call 4 to 5 s (docs/VOLUME.md, "Model build time, re-measured"). The generators used to pay the rc2 cost twice: a {@code dry_run: true} rehearsal (a full
 * throwaway {@code ebxmcp_rehearsal_<ts>} model, validated, compiled, deleted), then the real build. The
 * rehearsal is off by default now ({@link ModelRehearsal}); the real build's own answer is the gate
 * ({@link ModelBuildAnswer}): a model that does not validate or compile fails the step with the same messages
 * and the same {@code PipelineStep} detail as before, and - the one thing the rehearsal gave for free - the
 * model it may have left behind is deleted again ({@link #cleanUp}).
 */
final class ModelBuilder
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ModelBuilder()
	{
	}

	/**
	 * Builds {@code pModelName} from {@code pTreeJson}; returns when the model exists, validates and compiles.
	 * Records {@code build} (and {@code dryRun} when the rehearsal is on, {@code buildCleanup} when a leftover
	 * had to be looked for) in {@code pDetail}.
	 *
	 * @param pKind {@code landing}, {@code staging} or {@code master}, for the messages
	 * @param pSubject what the message names as the owner of the model: the source system or the project code
	 * @throws OperationException the build failed; the message says whether the model was removed again
	 */
	static void build(final Session pSession, final Adaptation pFactory, final McpClient pClient, final String pKind, final String pSubject, final String pModelName,
		final String pTreeJson, final ObjectNode pDetail) throws McpException, OperationException
	{
		if (ModelRehearsal.enabled())
		{
			final ObjectNode dryArgs = ModelBuilder.MAPPER.createObjectNode();
			dryArgs.put("data_model_name", pModelName);
			dryArgs.put("model_json", pTreeJson);
			dryArgs.put("dry_run", true);
			final McpClient.ToolResult dry = pClient.callTool("build_data_model", dryArgs);
			pDetail.set("dryRun", ModelBuilder.toolDetail(dryArgs, dry));
			if (dry.error)
			{
				throw EngineRefusal.coded("external.buildModel", "build_data_model (dry run) refused the " + pKind + " model " + pModelName + ": " + ModelBuilder.shortText(dry.text), pKind, pModelName);
			}
			if (!ModelBuildAnswer.read(dry.error, dry.json).gatesPassed())
			{
				throw EngineRefusal.coded("invalid.builtModel", "The " + pKind + " model of " + pSubject + " does not compile or is not valid (build_data_model dry run): " + ModelBuilder.shortText(dry.text), pKind, pSubject);
			}
		}

		final ObjectNode buildArgs = ModelBuilder.MAPPER.createObjectNode();
		buildArgs.put("data_model_name", pModelName);
		buildArgs.put("model_json", pTreeJson);
		buildArgs.put("dry_run", false);
		final McpClient.ToolResult build = pClient.callTool("build_data_model", buildArgs);
		pDetail.set("build", ModelBuilder.toolDetail(buildArgs, build));
		final ModelBuildAnswer answer = ModelBuildAnswer.read(build.error, build.json);
		if (answer.ok())
		{
			return;
		}
		final String cleanup = ModelBuilder.cleanUp(pSession, pFactory, pClient, pModelName, answer, pDetail);
		if (build.error)
		{
			throw EngineRefusal.coded("external.buildModel", "build_data_model could not create the " + pKind + " model " + pModelName + ": " + ModelBuilder.shortText(build.text) + cleanup, pKind, pModelName);
		}
		throw EngineRefusal.coded("invalid.builtModel", "The " + pKind + " model of " + pSubject + " does not compile or is not valid (build_data_model): " + ModelBuilder.shortText(build.text) + cleanup, pKind, pSubject);
	}

	/**
	 * After a failed build: when the tool did not delete the model itself ({@link ModelBuildAnswer#cleanupNeeded}),
	 * look whether it exists under the built name ({@code get_data_model}) and delete it ({@link
	 * GeneratedObjectDeleter#deleteDataModel}) - the generators only build a name they found free just before, so
	 * a model found now is the failed build's own leftover. Never throws: the build's own failure is what the
	 * caller reports, this only appends what became of the model.
	 *
	 * @return a sentence to append to the failure message (leading space), empty when nothing was to be said
	 */
	private static String cleanUp(final Session pSession, final Adaptation pFactory, final McpClient pClient, final String pModelName, final ModelBuildAnswer pAnswer,
		final ObjectNode pDetail)
	{
		final ObjectNode cleanup = pDetail.putObject("buildCleanup");
		cleanup.put("deletedByTool", pAnswer.deletedOnFailure());
		if (!pAnswer.cleanupNeeded())
		{
			return " The partially built model was deleted by the tool (delete-on-fail).";
		}
		try
		{
			final ObjectNode getArgs = ModelBuilder.MAPPER.createObjectNode();
			getArgs.put("data_model_name", pModelName);
			final McpClient.ToolResult lookup = pClient.callTool("get_data_model", getArgs);
			final boolean found = lookup.json != null && lookup.json.path("found").asBoolean(false);
			cleanup.put("found", found);
			if (!pAnswer.mustDelete(found))
			{
				return "";
			}
			final GeneratedObjectDeleter.Outcome outcome = GeneratedObjectDeleter.deleteDataModel(pSession, pFactory, pModelName);
			cleanup.put("result", outcome.result);
			if (outcome.message != null)
			{
				cleanup.put("message", outcome.message);
			}
			return "DELETED".equals(outcome.result) || "SKIPPED_ABSENT".equals(outcome.result) ? " The model left behind was deleted again."
				: " The model " + pModelName + " was left behind and could not be deleted (" + ModelBuilder.shortText(outcome.message) + "): delete it before generating again.";
		}
		catch (final McpException ex)
		{
			cleanup.put("error", ex.getMessage());
			return " Whether a model was left behind could not be checked (" + ex.getMessage() + "): look for " + pModelName + " before generating again.";
		}
		catch (final OperationException ex)
		{
			cleanup.put("error", ex.getMessage());
			return " Whether a model was left behind could not be checked (" + ex.getMessage() + "): look for " + pModelName + " before generating again.";
		}
	}

	private static ObjectNode toolDetail(final ObjectNode pArguments, final McpClient.ToolResult pResult)
	{
		final ObjectNode node = ModelBuilder.MAPPER.createObjectNode();
		node.set("arguments", pArguments);
		node.put("error", pResult.error);
		if (pResult.json != null)
		{
			node.set("result", pResult.json);
		}
		else
		{
			node.put("resultText", pResult.text);
		}
		return node;
	}

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
