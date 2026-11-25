package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * The {@code workflow-modeling} MCP calls of the domain generation (lot C1-D): {@code workflow_model_import} {modelName, modelMap} then
 * {@code workflow_publication_publish} {modelName, publicationName, publicationMode}. The publication carries the model's name. A republication
 * over an existing name uses {@code UPDATE} (the previous record stays as a disabled one, as the tool says), chosen from the caller's knowledge
 * (a DomainObject row of a first run) and, when a first publication is refused, from whether the publication list names the publication - never
 * from the wording of an error. Optional server: {@link #importAndPublish} answers {@code skipped} with the reason when it is not reachable.
 * <p>
 * Not run live in this lot (no EBX call allowed): the tool arguments are those of the tool schemas read from the server's declarations.
 */
final class WorkflowModelOps
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** What happened. */
	static final class Outcome
	{
		final boolean skipped;
		final String message;
		final String publicationName;
		final boolean updated;

		private Outcome(final boolean pSkipped, final String pMessage, final String pPublicationName, final boolean pUpdated)
		{
			this.skipped = pSkipped;
			this.message = pMessage;
			this.publicationName = pPublicationName;
			this.updated = pUpdated;
		}

		static Outcome skipped(final String pMessage)
		{
			return new Outcome(true, pMessage, null, false);
		}

		static Outcome published(final String pName, final boolean pUpdated)
		{
			return new Outcome(false, "Workflow model " + pName + " imported and " + (pUpdated ? "republished (UPDATE)" : "published") + ".", pName, pUpdated);
		}
	}

	private WorkflowModelOps()
	{
	}

	/** True when the answer is an error or a soft miss ({@code success:false}, {@code valid:false}). */
	static boolean failed(final McpClient.ToolResult pResult)
	{
		if (pResult.error)
		{
			return true;
		}
		final JsonNode json = pResult.json;
		return json != null && (json.path("success").asText("true").equals("false") || json.path("valid").asText("true").equals("false")
			|| json.path("found").asText("true").equals("false")
			|| json.path("refused").asBoolean(false));
	}

	static Outcome importAndPublish(final Session pSession, final Adaptation pFactory, final ObjectNode pModelJson, final String pModelName, final boolean pRepublish)
		throws OperationException
	{
		final ObjectNode preflight = McpServers.preflight(pSession, pFactory, McpServers.WORKFLOW_MODELING);
		if (!preflight.path("ready").asBoolean(false))
		{
			return Outcome.skipped("The workflow-modeling server is not reachable (" + preflight.path("summary").asText("no detail") + "): the workflow was not generated.");
		}
		try
		{
			final McpClient client = McpServers.client(pSession, pFactory, McpServers.WORKFLOW_MODELING);
			final ObjectNode importArgs = WorkflowModelOps.MAPPER.createObjectNode();
			importArgs.put("modelName", pModelName);
			importArgs.set("modelMap", pModelJson);
			final McpClient.ToolResult imported = client.callTool("workflow_model_import", importArgs);
			if (WorkflowModelOps.failed(imported))
			{
				throw EngineRefusal.coded("external.workflowImport", "workflow_model_import refused " + pModelName + ": " + WorkflowModelOps.shortText(imported.text), pModelName);
			}
			boolean update = pRepublish;
			McpClient.ToolResult published = WorkflowModelOps.publish(client, pModelName, update);
			if (WorkflowModelOps.failed(published) && !update && WorkflowModelOps.publicationExists(client, pModelName))
			{
				update = true;
				published = WorkflowModelOps.publish(client, pModelName, true);
			}
			if (WorkflowModelOps.failed(published))
			{
				throw EngineRefusal.coded("external.workflowPublish", "workflow_publication_publish refused " + pModelName + ": " + WorkflowModelOps.shortText(published.text), pModelName);
			}
			return Outcome.published(pModelName, update);
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.codedWithCause("external.mcpWorkflow", "MCP call failed while generating the workflow " + pModelName + ": " + ex.getMessage(), ex, pModelName);
		}
	}

	private static McpClient.ToolResult publish(final McpClient pClient, final String pModelName, final boolean pUpdate) throws McpException
	{
		final ObjectNode args = WorkflowModelOps.MAPPER.createObjectNode();
		args.put("modelName", pModelName);
		args.put("publicationName", pModelName);
		args.put("publicationMode", pUpdate ? "UPDATE" : "NEW");
		return pClient.callTool("workflow_publication_publish", args);
	}

	/** Whether the publication list names the publication. Any failure of the listing reads as "not there". */
	static boolean publicationExists(final McpClient pClient, final String pName)
	{
		try
		{
			final McpClient.ToolResult list = pClient.callTool("workflow_publication_list", WorkflowModelOps.MAPPER.createObjectNode());
			return !list.error && list.text != null && list.text.contains("\"" + pName + "\"");
		}
		catch (final McpException ex)
		{
			return false;
		}
	}

	/** Whether the model list names the model. */
	static boolean modelExists(final McpClient pClient, final String pName)
	{
		try
		{
			final McpClient.ToolResult list = pClient.callTool("workflow_model_list", WorkflowModelOps.MAPPER.createObjectNode());
			return !list.error && list.text != null && list.text.contains("\"" + pName + "\"");
		}
		catch (final McpException ex)
		{
			return false;
		}
	}

	static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
