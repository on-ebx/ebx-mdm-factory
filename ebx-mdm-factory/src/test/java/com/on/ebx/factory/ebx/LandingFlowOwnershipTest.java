package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.landing.LandingFlow;
import com.orchestranetworks.service.OperationException;

/**
 * JUnit-free: run the main, a failure throws. Fix of 2026-10-03 (Vol100k / Vol100kM, two projects on one source system shared one
 * Connectors mapping): {@link LandingFlows#ownedMappingId} follows the id stored on a landing table only when that mapping really writes
 * into the table, {@link LandingFlows#upsertFlow} updates it by id (renaming it to the project-specific label) and deletes the old one
 * if the label found another, and {@link LandingFlowOwnership#read} tells a gone mapping from an unreadable one. A scripted
 * {@link McpClient.Transport} plays ebx-connectors and records every tool call; no real client, session or EBX instance.
 */
public final class LandingFlowOwnershipTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** One scripted tool answer: {@code structured} JSON, or an error text. */
	private static final class Answer
	{
		final String structured;
		final boolean error;

		Answer(final String pStructured, final boolean pError)
		{
			this.structured = pStructured;
			this.error = pError;
		}
	}

	/** Plays the Connectors MCP server: answers {@code tools/call} from a script keyed by tool name, records name + arguments. */
	private static final class FakeConnectors implements McpClient.Transport
	{
		final Map<String, Answer> script = new LinkedHashMap<String, Answer>();
		final List<String> calls = new ArrayList<String>();
		final List<JsonNode> arguments = new ArrayList<JsonNode>();

		@Override
		public String exchange(final String pRequest) throws McpException
		{
			try
			{
				final JsonNode message = LandingFlowOwnershipTest.MAPPER.readTree(pRequest);
				final String method = message.path("method").asText();
				final int id = message.path("id").asInt();
				if (method.startsWith("notifications/"))
				{
					return "";
				}
				if ("initialize".equals(method))
				{
					return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"serverInfo\":{\"name\":\"ebx-connectors\"}}}";
				}
				final String tool = message.path("params").path("name").asText();
				this.calls.add(tool);
				this.arguments.add(message.path("params").path("arguments"));
				final Answer answer = this.script.get(tool);
				if (answer == null)
				{
					throw new McpException("no scripted answer for " + tool, null);
				}
				final ObjectNode result = LandingFlowOwnershipTest.MAPPER.createObjectNode();
				result.putArray("content").addObject().put("type", "text").put("text", answer.structured);
				result.set("structuredContent", LandingFlowOwnershipTest.MAPPER.readTree(answer.structured.startsWith("{") ? answer.structured : "{\"error\":\"" + answer.structured + "\"}"));
				result.put("isError", answer.error);
				return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
			}
			catch (final java.io.IOException ex)
			{
				throw new McpException(ex.getMessage(), ex);
			}
		}

		@Override
		public String describe()
		{
			return "fake";
		}

		List<JsonNode> argumentsOf(final String pTool)
		{
			final List<JsonNode> out = new ArrayList<JsonNode>();
			for (int i = 0; i < this.calls.size(); i++)
			{
				if (this.calls.get(i).equals(pTool))
				{
					out.add(this.arguments.get(i));
				}
			}
			return out;
		}
	}

	public static void main(final String[] pArgs) throws Exception
	{
		LandingFlowOwnershipTest.ownMappingIsFollowed();
		LandingFlowOwnershipTest.otherProjectsMappingIsNotTouched();
		LandingFlowOwnershipTest.goneMappingIsReplacedByANewOne();
		LandingFlowOwnershipTest.unreadableMappingRefusesTheGeneration();
		LandingFlowOwnershipTest.neverGeneratedTableAsksNothing();
		LandingFlowOwnershipTest.upsertGoesByIdAndRenames();
		LandingFlowOwnershipTest.labelFindingAnotherMappingRetiresTheOldOne();
		LandingFlowOwnershipTest.secondProjectGetsItsOwnMappingAndFirstOneKeepsItsOwn();
		System.out.println("LandingFlowOwnershipTest OK");
	}

	private static String mapping(final String pId, final String pDataspace)
	{
		return "{\"id\":\"" + pId + "\",\"label\":\"x\",\"dataspace\":\"" + pDataspace + "\",\"dataset\":\"VOL100KCRM\",\"table\":\"/root/CONTACT\"}";
	}

	private static LandingFlows.FlowTable flowTable(final String pInbound)
	{
		return new LandingFlows.FlowTable("87", "CONTACT", "PUBLIC.CONTACT", new ArrayList<LandingFlow.Column>(), null, "", "", pInbound);
	}

	private static List<LandingFlow.Column> columns()
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("CONTACT_ID", "CONTACT_ID", true));
		columns.add(new LandingFlow.Column("NAME", "NAME", false));
		return columns;
	}

	private static LandingFlow.Table flowFor(final String pProject, final String pDataspace, final String pOwnedId)
	{
		return new LandingFlow.Table("Vol100kCRM", "h2", "VOL100KCRM", "PUBLIC.CONTACT", pDataspace, "VOL100KCRM", "CONTACT", LandingFlowOwnershipTest.columns())
			.forProject(pProject, pOwnedId);
	}

	private static void ownMappingIsFollowed() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors();
		fake.script.put("connectors_mapping_get", new Answer(LandingFlowOwnershipTest.mapping("346", "Vol100k_Landing"), false));
		final ObjectNode detail = MAPPER.createObjectNode();
		final String owned = LandingFlows.ownedMappingId(new McpClient(fake), LandingFlowOwnershipTest.flowTable("346"), "Vol100k_Landing", "VOL100KCRM", detail);
		check("a mapping writing into this very table is the table's own", "346".equals(owned));
		check("it was looked up by id", "346".equals(fake.argumentsOf("connectors_mapping_get").get(0).path("mapping").asText()));
		check("nothing to report", !detail.has("previousMapping"));
	}

	/** The live defect: project 2's table points at mapping 346, which writes into project 1's landing dataspace. */
	private static void otherProjectsMappingIsNotTouched() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors();
		fake.script.put("connectors_mapping_get", new Answer(LandingFlowOwnershipTest.mapping("346", "Vol100k_Landing"), false));
		final ObjectNode detail = MAPPER.createObjectNode();
		final String owned = LandingFlows.ownedMappingId(new McpClient(fake), LandingFlowOwnershipTest.flowTable("346"), "Vol100kM_Landing", "VOL100KCRM", detail);
		check("a mapping of another project's landing dataspace is not followed", owned == null);
		check("the table's detail says why: " + detail.path("previousMapping").asText(), detail.path("previousMapping").asText().contains("Vol100k_Landing")
			&& detail.path("previousMapping").asText().contains("another project"));
		check("a mapping on the right dataspace but another dataset is not followed either",
			LandingFlows.ownedMappingId(new McpClient(fake), LandingFlowOwnershipTest.flowTable("346"), "Vol100k_Landing", "OTHER_DATASET", MAPPER.createObjectNode()) == null);
		check("nor another table",
			LandingFlows.ownedMappingId(new McpClient(fake), new LandingFlows.FlowTable("88", "ACCOUNT", "PUBLIC.ACCOUNT", new ArrayList<LandingFlow.Column>(), null, "", "", "346"),
				"Vol100k_Landing", "VOL100KCRM", MAPPER.createObjectNode()) == null);
	}

	private static void goneMappingIsReplacedByANewOne() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors();
		fake.script.put("connectors_mapping_get", new Answer("No mapping '346'", true));
		final ObjectNode detail = MAPPER.createObjectNode();
		check("a gone mapping gives no id to update", LandingFlows.ownedMappingId(new McpClient(fake), LandingFlowOwnershipTest.flowTable("346"), "Vol100k_Landing", "VOL100KCRM", detail) == null);
		check("and is said", detail.path("previousMapping").asText().contains("no longer exists"));
	}

	private static void unreadableMappingRefusesTheGeneration() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors();
		fake.script.put("connectors_mapping_get", new Answer("Access denied to TOOL 'connectors_mapping_get'", true));
		try
		{
			LandingFlows.ownedMappingId(new McpClient(fake), LandingFlowOwnershipTest.flowTable("346"), "Vol100k_Landing", "VOL100KCRM", MAPPER.createObjectNode());
			check("an unreadable mapping must not be taken for the table's own", false);
		}
		catch (final OperationException ex)
		{
			check("the refusal names the mapping: " + ex.getMessage(), ex.getMessage().contains("346"));
		}
		final LandingFlowOwnership.Mapping gone;
		fake.script.put("connectors_mapping_get", new Answer("{\"id\":\"1\"}", false));
		try
		{
			LandingFlowOwnership.read(new McpClient(fake), "1");
			check("an answer without a dataspace is not a valid proof", false);
		}
		catch (final OperationException ex)
		{
			check("refused with the answer", ex.getMessage().contains("dataspace"));
		}
	}

	private static void neverGeneratedTableAsksNothing() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors();
		check("a table with no stored mapping needs no id", LandingFlows.ownedMappingId(new McpClient(fake), LandingFlowOwnershipTest.flowTable(""), "D", "S", MAPPER.createObjectNode()) == null);
		check("and calls nothing", fake.calls.isEmpty());
	}

	private static void upsertGoesByIdAndRenames() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors();
		fake.script.put("connectors_mapping_upsert", new Answer("{\"mapping\":\"346\",\"created\":false}", false));
		final ObjectNode detail = MAPPER.createObjectNode();
		final String id = LandingFlows.upsertFlow(new McpClient(fake), LandingFlowOwnershipTest.flowFor("Vol100k", "Vol100k_Landing", "346"), detail);
		check("the mapping is the same one, updated in place", "346".equals(id));
		final List<JsonNode> upserts = fake.argumentsOf("connectors_mapping_upsert");
		check("a dry run and a real call", upserts.size() == 2 && upserts.get(0).path("dryRun").asBoolean(false) && !upserts.get(1).path("dryRun").asBoolean(true));
		for (final JsonNode call : upserts)
		{
			final JsonNode mapping = call.path("mapping");
			check("each carries the id and updateById", "346".equals(mapping.path("id").asText()) && mapping.path("updateById").asBoolean(false));
			check("and the project-specific label", "Landing: Vol100k VOL100KCRM CONTACT".equals(mapping.path("label").asText()));
		}
		check("nothing was deleted", fake.argumentsOf("connectors_mapping_delete").isEmpty() && !detail.has("supersededMapping"));
	}

	/** Defensive: the label already named ANOTHER mapping of the same table - two active landing mappings must not be left behind. */
	private static void labelFindingAnotherMappingRetiresTheOldOne() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors();
		fake.script.put("connectors_mapping_upsert", new Answer("{\"mapping\":\"400\",\"created\":false}", false));
		fake.script.put("connectors_mapping_delete", new Answer("{\"deleted\":true}", false));
		final ObjectNode detail = MAPPER.createObjectNode();
		final String id = LandingFlows.upsertFlow(new McpClient(fake), LandingFlowOwnershipTest.flowFor("Vol100k", "Vol100k_Landing", "346"), detail);
		check("the table now points at the mapping the label found", "400".equals(id));
		check("the old own mapping 346 was deleted", fake.argumentsOf("connectors_mapping_delete").size() == 1
			&& "346".equals(fake.argumentsOf("connectors_mapping_delete").get(0).path("mapping").asText()));
		check("and the detail says so: " + detail.path("supersededMapping").asText(), detail.path("supersededMapping").asText().contains("346")
			&& detail.path("supersededMapping").asText().contains("400"));
		fake.script.put("connectors_mapping_delete", new Answer("boom", true));
		final ObjectNode failed = MAPPER.createObjectNode();
		check("a failed deletion does not fail the generation", "400".equals(LandingFlows.upsertFlow(new McpClient(fake),
			LandingFlowOwnershipTest.flowFor("Vol100k", "Vol100k_Landing", "346"), failed)) && failed.path("supersededMapping").asText().contains("by hand"));
	}

	/**
	 * The scenario of the defect, end to end at the Connectors boundary: project 1 owns 346; project 2's table points at 346 too, which is
	 * not its own, so its upsert carries NO id (a new mapping, found/created by its own label), and 346 is never sent anywhere.
	 */
	private static void secondProjectGetsItsOwnMappingAndFirstOneKeepsItsOwn() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors();
		fake.script.put("connectors_mapping_get", new Answer(LandingFlowOwnershipTest.mapping("346", "Vol100k_Landing"), false));
		fake.script.put("connectors_mapping_upsert", new Answer("{\"mapping\":\"500\",\"created\":true}", false));
		final McpClient client = new McpClient(fake);
		final ObjectNode detail = MAPPER.createObjectNode();
		final String owned = LandingFlows.ownedMappingId(client, LandingFlowOwnershipTest.flowTable("346"), "Vol100kM_Landing", "VOL100KCRM", detail);
		final String id = LandingFlows.upsertFlow(client, LandingFlowOwnershipTest.flowFor("Vol100kM", "Vol100kM_Landing", owned), detail);
		check("the second project got a mapping of its own", "500".equals(id));
		for (final JsonNode call : fake.argumentsOf("connectors_mapping_upsert"))
		{
			final JsonNode mapping = call.path("mapping");
			check("without any id: the first project's mapping 346 is never addressed", !mapping.has("id") && !mapping.has("updateById"));
			check("labelled with its own project", "Landing: Vol100kM VOL100KCRM CONTACT".equals(mapping.path("label").asText())
				&& "Vol100kM_Landing".equals(mapping.path("dataspace").asText()));
		}
		check("and 346 was not deleted either", fake.argumentsOf("connectors_mapping_delete").isEmpty());
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
