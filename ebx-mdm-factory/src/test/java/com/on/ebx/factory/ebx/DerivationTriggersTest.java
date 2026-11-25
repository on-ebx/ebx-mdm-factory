package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.orchestranetworks.service.OperationException;

/**
 * JUnit-free: run the main, a failure throws. Lot T1 (2026-10-04): {@link DerivationTriggers} against a scripted {@link McpClient.Transport} that
 * plays the {@code data-model} and {@code scripting} servers and records every tool call with its arguments - what is declared, authored, verified
 * and removed, in which order, and that a trigger is only ever removed BY NAME (the whole trigger list of a table is never wiped).
 */
public final class DerivationTriggersTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Plays a server: one scripted JSON answer per tool name (or an error), every call recorded as {@code tool <arguments>}. */
	private static final class Fake implements McpClient.Transport
	{
		final Map<String, String> answers = new LinkedHashMap<String, String>();
		final List<String> errors = new ArrayList<String>();
		final List<String> calls = new ArrayList<String>();
		final List<JsonNode> arguments = new ArrayList<JsonNode>();

		@Override
		public String exchange(final String pRequest) throws McpException
		{
			try
			{
				final JsonNode message = DerivationTriggersTest.MAPPER.readTree(pRequest);
				final String method = message.path("method").asText();
				final int id = message.path("id").asInt();
				if (method.startsWith("notifications/"))
				{
					return "";
				}
				if ("initialize".equals(method))
				{
					return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"serverInfo\":{\"name\":\"fake\"}}}";
				}
				final String tool = message.path("params").path("name").asText();
				this.calls.add(tool);
				this.arguments.add(message.path("params").path("arguments"));
				String structured = this.answers.get(tool);
				final boolean error = this.errors.contains(tool) || structured == null;
				if (structured == null)
				{
					structured = "{\"success\":false,\"error\":\"not scripted\"}";
				}
				final ObjectNode result = DerivationTriggersTest.MAPPER.createObjectNode();
				result.putArray("content").addObject().put("type", "text").put("text", structured);
				result.set("structuredContent", DerivationTriggersTest.MAPPER.readTree(structured));
				result.put("isError", error);
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
	}

	private static JsonNode json(final String pText) throws Exception
	{
		return DerivationTriggersTest.MAPPER.readTree(pText);
	}

	public static void main(final String[] pArgs) throws Exception
	{
		DerivationTriggersTest.declareSendsTheMeasuredArguments();
		DerivationTriggersTest.aRefusedDeclarationIsAnError();
		DerivationTriggersTest.authorReadsTheRevisionThenUpdatesAndChecksValidation();
		DerivationTriggersTest.aBodyThatDoesNotValidateIsAnError();
		DerivationTriggersTest.stateOfReadsTheTriggerListDefensively();
		DerivationTriggersTest.verifyFailsOnAMissingOrUnauthoredTriggerOnly();
		DerivationTriggersTest.unsetNamesTheItemAndNeverWipesTheList();
		DerivationTriggersTest.functionDeclarationsAreRecognised();
		DerivationTriggersTest.aTriggerIsLookedUpOnItsOwnTable();
		DerivationTriggersTest.thePreviousSourceIsReadAndPutBackWithoutThrowing();
		System.out.println("DerivationTriggersTest OK");
	}

	private static void declareSendsTheMeasuredArguments() throws Exception
	{
		final Fake fake = new Fake();
		fake.answers.put("execute_operation", "{\"success\":true,\"replaced\":false,\"publishSafe\":true}");
		final ObjectNode detail = DerivationTriggersTest.MAPPER.createObjectNode();
		DerivationTriggers.declare(new McpClient(fake), "Vol100k_Staging", "/root/Personne", "DerivePhoneE164OnCreate", DerivationTriggers.TYPE_CREATE, detail, "declareCreate");
		final JsonNode arguments = fake.arguments.get(0);
		DerivationTriggersTest.check("model", "Vol100k_Staging".equals(arguments.path("data_model_name").asText()));
		DerivationTriggersTest.check("operation", "set_script_trigger".equals(arguments.path("operation").asText()));
		final JsonNode operation = DerivationTriggersTest.json(arguments.path("arguments_json").asText());
		DerivationTriggersTest.check("element_path is the TABLE", "/root/Personne".equals(operation.path("element_path").asText()));
		DerivationTriggersTest.check("name", "DerivePhoneE164OnCreate".equals(operation.path("name").asText()));
		DerivationTriggersTest.check("type is case-sensitive beforeCreate", "beforeCreate".equals(operation.path("type").asText()));
		DerivationTriggersTest.check("scope table", "table".equals(operation.path("scope").asText()));
		DerivationTriggersTest.check("recorded in the detail", detail.has("declareCreate"));
		DerivationTriggersTest.check("modify type", "beforeModify".equals(DerivationTriggers.TYPE_MODIFY));
	}

	private static void aRefusedDeclarationIsAnError() throws Exception
	{
		final Fake fake = new Fake();
		fake.answers.put("execute_operation", "{\"success\":false,\"error\":\"Invalid type\"}");
		try
		{
			DerivationTriggers.declare(new McpClient(fake), "M", "/root/T", "X", "BeforeCreate", DerivationTriggersTest.MAPPER.createObjectNode(), "d");
			throw new AssertionError("a refusal went through");
		}
		catch (final OperationException expected)
		{
			DerivationTriggersTest.check("names the trigger: " + expected.getMessage(), expected.getMessage().contains("'X'"));
		}
	}

	private static void authorReadsTheRevisionThenUpdatesAndChecksValidation() throws Exception
	{
		final Fake fake = new Fake();
		fake.answers.put("get_script", "{\"success\":true,\"data\":{\"scriptTypeId\":\"triggerBeforeCreate\",\"revisionId\":\"r7\"}}");
		fake.answers.put("update_script", "{\"success\":true,\"data\":{\"validation\":{\"isSuccess\":true}}}");
		final String idePath = "data-model/M/root/T/@trigger/X";
		DerivationTriggers.author(new McpClient(fake), idePath, "SOURCE", DerivationTriggersTest.MAPPER.createObjectNode(), "authorCreate");
		DerivationTriggersTest.check("order: " + fake.calls, fake.calls.equals(Arrays.asList("get_script", "update_script")));
		final JsonNode update = fake.arguments.get(1);
		DerivationTriggersTest.check("path, source, revision, confirm",
			idePath.equals(update.path("scriptIdePath").asText()) && "SOURCE".equals(update.path("source").asText()) && "r7".equals(update.path("revisionId").asText())
				&& update.path("confirmReplaceMetadata").asBoolean(false));
	}

	private static void aBodyThatDoesNotValidateIsAnError() throws Exception
	{
		final Fake fake = new Fake();
		fake.answers.put("get_script", "{\"success\":true,\"data\":{\"revisionId\":\"r1\"}}");
		fake.answers.put("update_script", "{\"success\":false,\"data\":{\"validation\":{\"isSuccess\":false}}}");
		try
		{
			DerivationTriggers.author(new McpClient(fake), "p", "S", DerivationTriggersTest.MAPPER.createObjectNode(), "k");
			throw new AssertionError("an invalid body went through");
		}
		catch (final OperationException expected)
		{
			DerivationTriggersTest.check("says it did not validate: " + expected.getMessage(), expected.getMessage().contains("did not validate"));
		}
		final Fake noRevision = new Fake();
		noRevision.answers.put("get_script", "{\"success\":false}");
		try
		{
			DerivationTriggers.author(new McpClient(noRevision), "p", "S", DerivationTriggersTest.MAPPER.createObjectNode(), "k");
			throw new AssertionError("a missing revision went through");
		}
		catch (final OperationException expected)
		{
			DerivationTriggersTest.check("says it has no revision: " + expected.getMessage(), expected.getMessage().contains("revisionId"));
		}
		DerivationTriggersTest.check("update_script is not even called without a revision: " + noRevision.calls, noRevision.calls.size() == 1);
	}

	private static void stateOfReadsTheTriggerListDefensively() throws Exception
	{
		final JsonNode measured = DerivationTriggersTest.json("{\"tableTriggers\":[{\"table\":\"/root/Personne\",\"name\":\"DerivePhoneE164OnCreate\",\"type\":\"beforeCreate\",\"authored\":true},"
			+ "{\"table\":\"/root/Personne\",\"name\":\"DerivePhoneE164OnModify\",\"type\":\"beforeModify\",\"authored\":false}]}");
		DerivationTriggersTest.check("authored", DerivationTriggers.stateOf(measured, "DerivePhoneE164OnCreate") == DerivationTriggers.State.AUTHORED);
		DerivationTriggersTest.check("not authored", DerivationTriggers.stateOf(measured, "DerivePhoneE164OnModify") == DerivationTriggers.State.NOT_AUTHORED);
		DerivationTriggersTest.check("missing", DerivationTriggers.stateOf(measured, "Other") == DerivationTriggers.State.MISSING);
		final JsonNode nested = DerivationTriggersTest.json("{\"success\":true,\"data\":{\"tableTriggers\":[{\"trigger\":{\"name\":\"X\"}}]}}");
		DerivationTriggersTest.check("found under data, name anywhere in the entry, no flag is unknown", DerivationTriggers.stateOf(nested, "X") == DerivationTriggers.State.UNKNOWN);
		DerivationTriggersTest.check("empty list", DerivationTriggers.stateOf(DerivationTriggersTest.json("{\"tableTriggers\":[]}"), "X") == DerivationTriggers.State.MISSING);
		DerivationTriggersTest.check("no json", DerivationTriggers.stateOf(null, "X") == DerivationTriggers.State.MISSING);
		DerivationTriggersTest.check("a data array", DerivationTriggers.stateOf(DerivationTriggersTest.json("{\"data\":[{\"name\":\"X\",\"authored\":true}]}"), "X") == DerivationTriggers.State.AUTHORED);
	}

	private static void verifyFailsOnAMissingOrUnauthoredTriggerOnly() throws Exception
	{
		final List<String> names = Arrays.asList("A", "B");
		final Fake good = new Fake();
		good.answers.put("execute_query", "{\"tableTriggers\":[{\"name\":\"A\",\"authored\":true},{\"name\":\"B\"}]}");
		DerivationTriggers.verify(new McpClient(good), "M", names, DerivationTriggersTest.MAPPER.createObjectNode());
		DerivationTriggersTest.check("query asked", "list_script_table_triggers".equals(good.arguments.get(0).path("operation").asText()));

		final Fake missing = new Fake();
		missing.answers.put("execute_query", "{\"tableTriggers\":[{\"name\":\"A\",\"authored\":true}]}");
		try
		{
			DerivationTriggers.verify(new McpClient(missing), "M", names, DerivationTriggersTest.MAPPER.createObjectNode());
			throw new AssertionError("a missing trigger went through");
		}
		catch (final OperationException expected)
		{
			DerivationTriggersTest.check("names it: " + expected.getMessage(), expected.getMessage().contains("'B'") && expected.getMessage().contains("not reported"));
		}
		final Fake unauthored = new Fake();
		unauthored.answers.put("execute_query", "{\"tableTriggers\":[{\"name\":\"A\",\"authored\":true},{\"name\":\"B\",\"authored\":false}]}");
		try
		{
			DerivationTriggers.verify(new McpClient(unauthored), "M", names, DerivationTriggersTest.MAPPER.createObjectNode());
			throw new AssertionError("an unauthored trigger went through");
		}
		catch (final OperationException expected)
		{
			DerivationTriggersTest.check("says authored:false: " + expected.getMessage(), expected.getMessage().contains("authored:false"));
		}
	}

	private static void unsetNamesTheItemAndNeverWipesTheList() throws Exception
	{
		final Fake fake = new Fake();
		fake.answers.put("unset_element_property", "{\"success\":true}");
		final ObjectNode detail = DerivationTriggersTest.MAPPER.createObjectNode();
		DerivationTriggers.unset(new McpClient(fake), "Vol100k_Staging", "/root/Personne", "DerivePhoneE164OnModify", detail, "remove_X");
		final JsonNode arguments = fake.arguments.get(0);
		DerivationTriggersTest.check("table path, property and item", "/root/Personne".equals(arguments.path("element_path").asText())
			&& "triggers".equals(arguments.path("property").asText()) && "DerivePhoneE164OnModify".equals(arguments.path("item").asText()));
		final Fake refused = new Fake();
		refused.errors.add("unset_element_property");
		refused.answers.put("unset_element_property", "{\"success\":false,\"error\":\"no such trigger\"}");
		final McpClient.ToolResult raw = DerivationTriggers.unsetRaw(new McpClient(refused), "M", "/root/T", "X", DerivationTriggersTest.MAPPER.createObjectNode(), "k");
		DerivationTriggersTest.check("the raw form does not throw", raw.error);
		try
		{
			DerivationTriggers.unset(new McpClient(refused), "M", "/root/T", "X", DerivationTriggersTest.MAPPER.createObjectNode(), "k");
			throw new AssertionError("a refused removal went through");
		}
		catch (final OperationException expected)
		{
			DerivationTriggersTest.check("names the trigger: " + expected.getMessage(), expected.getMessage().contains("'X'"));
		}
		DerivationTriggersTest.check("the two names of an attribute", DerivationTriggers.namesOf("phoneE164").equals(Arrays.asList("DerivePhoneE164OnCreate", "DerivePhoneE164OnModify")));
	}

	private static void functionDeclarationsAreRecognised() throws Exception
	{
		final JsonNode declared = DerivationTriggersTest.json("{\"data\":{\"functions\":[{\"fieldPath\":\"/root/Personne/phoneE164\",\"authored\":false}]}}");
		DerivationTriggersTest.check("declared, authored or not", DerivationTriggers.isFunctionDeclared(declared, "/root/Personne/phoneE164"));
		DerivationTriggersTest.check("another field", !DerivationTriggers.isFunctionDeclared(declared, "/root/Personne/other"));
		DerivationTriggersTest.check("a data array with elementPath",
			DerivationTriggers.isFunctionDeclared(DerivationTriggersTest.json("{\"data\":[{\"elementPath\":\"/root/T/f\"}]}"), "/root/T/f"));
		DerivationTriggersTest.check("nothing declared", !DerivationTriggers.isFunctionDeclared(DerivationTriggersTest.json("{\"data\":{\"functions\":[]}}"), "/root/T/f"));
		DerivationTriggersTest.check("no json", !DerivationTriggers.isFunctionDeclared(null, "/root/T/f"));
	}

	private static void aTriggerIsLookedUpOnItsOwnTable() throws Exception
	{
		// the master model holds every promoted entity: two tables can derive an attribute of the same name, hence two triggers of the same name
		final JsonNode answer = DerivationTriggersTest.json("{\"tableTriggers\":[{\"authored\":true,\"declared\":true,\"name\":\"DeriveKeyOnCreate\",\"tablePath\":\"/root/A\",\"type\":\"beforeCreate\"},"
			+ "{\"authored\":false,\"declared\":true,\"name\":\"DeriveKeyOnCreate\",\"tablePath\":\"/root/B\",\"type\":\"beforeCreate\"}]}");
		DerivationTriggersTest.check("table A", DerivationTriggers.stateOf(answer, "DeriveKeyOnCreate", "/root/A") == DerivationTriggers.State.AUTHORED);
		DerivationTriggersTest.check("table B", DerivationTriggers.stateOf(answer, "DeriveKeyOnCreate", "/root/B") == DerivationTriggers.State.NOT_AUTHORED);
		DerivationTriggersTest.check("table C has none", DerivationTriggers.stateOf(answer, "DeriveKeyOnCreate", "/root/C") == DerivationTriggers.State.MISSING);
		DerivationTriggersTest.check("no table asked: the first one, as before", DerivationTriggers.stateOf(answer, "DeriveKeyOnCreate") == DerivationTriggers.State.AUTHORED);
		DerivationTriggersTest.check("an entry that names no table is accepted", DerivationTriggers.stateOf(DerivationTriggersTest.json("{\"data\":[{\"name\":\"X\",\"authored\":true}]}"), "X", "/root/A")
			== DerivationTriggers.State.AUTHORED);
		final Fake fake = new Fake();
		fake.answers.put("execute_query", answer.toString());
		DerivationTriggers.verify(new McpClient(fake), "M_Master", "/root/A", Arrays.asList("DeriveKeyOnCreate"), DerivationTriggersTest.MAPPER.createObjectNode(), "masterVerify");
		final Fake other = new Fake();
		other.answers.put("execute_query", answer.toString());
		final ObjectNode detail = DerivationTriggersTest.MAPPER.createObjectNode();
		try
		{
			DerivationTriggers.verify(new McpClient(other), "M_Master", "/root/C", Arrays.asList("DeriveKeyOnCreate"), detail, "masterVerify");
			throw new AssertionError("a trigger of another table went through");
		}
		catch (final OperationException expected)
		{
			DerivationTriggersTest.check("names the trigger and the detail key: " + expected.getMessage(), expected.getMessage().contains("'DeriveKeyOnCreate'") && expected.getMessage().contains("masterVerify"));
		}
		DerivationTriggersTest.check("the answer is recorded under the key", detail.has("masterVerify") && !detail.has("verify"));
	}

	private static void thePreviousSourceIsReadAndPutBackWithoutThrowing() throws Exception
	{
		final Fake fake = new Fake();
		fake.answers.put("get_script", "{\"success\":true,\"data\":{\"source\":\"export procedure onBeforeCreate()\\nbegin\\nend\\n\",\"revisionId\":\"r3\"}}");
		final ObjectNode detail = DerivationTriggersTest.MAPPER.createObjectNode();
		final String source = DerivationTriggers.readSource(new McpClient(fake), "data-model/M/root/T/@trigger/X", detail, "previousCreate");
		DerivationTriggersTest.check("the source", "export procedure onBeforeCreate()\nbegin\nend\n".equals(source));
		DerivationTriggersTest.check("asked with the source included", fake.arguments.get(0).path("includeSource").asBoolean(false) && detail.has("previousCreate"));
		final Fake noSource = new Fake();
		noSource.answers.put("get_script", "{\"success\":true,\"data\":{\"revisionId\":\"r3\"}}");
		DerivationTriggersTest.check("no source reads as null", DerivationTriggers.readSource(new McpClient(noSource), "p", DerivationTriggersTest.MAPPER.createObjectNode(), "k") == null);
		final Fake refused = new Fake();
		refused.errors.add("get_script");
		refused.answers.put("get_script", "{\"success\":false}");
		DerivationTriggersTest.check("a refusal reads as null", DerivationTriggers.readSource(new McpClient(refused), "p", DerivationTriggersTest.MAPPER.createObjectNode(), "k") == null);

		final Fake restore = new Fake();
		restore.answers.put("get_script", "{\"success\":true,\"data\":{\"revisionId\":\"r9\"}}");
		restore.answers.put("update_script", "{\"success\":true,\"data\":{\"validation\":{\"isSuccess\":true}}}");
		DerivationTriggersTest.check("restore authors the previous body", DerivationTriggers.restore(new McpClient(restore), "p", "OLD", DerivationTriggersTest.MAPPER.createObjectNode(), "rollbackRestore")
			&& "OLD".equals(restore.arguments.get(1).path("source").asText()));
		final Fake failing = new Fake();
		failing.answers.put("get_script", "{\"success\":true,\"data\":{\"revisionId\":\"r9\"}}");
		failing.answers.put("update_script", "{\"success\":false,\"data\":{\"validation\":{\"isSuccess\":false}}}");
		final ObjectNode failingDetail = DerivationTriggersTest.MAPPER.createObjectNode();
		DerivationTriggersTest.check("a failed restore answers false, never throws", !DerivationTriggers.restore(new McpClient(failing), "p", "OLD", failingDetail, "rollbackRestore")
			&& failingDetail.has("rollbackRestoreError"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
