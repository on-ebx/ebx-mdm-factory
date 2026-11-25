package com.on.ebx.factory.ebx;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LlmChat;
import com.on.ebx.agent.LlmClient;
import com.on.ebx.agent.LlmException;

/**
 * JUnit-free: run the main, a failure throws. Test-cycle lot F4 (2026-10-19): the record / replay store of the language model's answers ({@link LlmRecordStore},
 * {@link LlmReplayChat}) against a fake client - miss, record, hit, turn-by-turn replay of a multi-turn loop, the digest's inputs, the files, the properties read at call time.
 */
public final class LlmRecordStoreTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Answers a scripted turn per call and counts the calls. */
	private static final class FakeClient implements LlmChat
	{
		int calls;
		final List<LlmClient.Turn> script = new ArrayList<LlmClient.Turn>();

		@Override
		public LlmClient.Turn send(final String pSystem, final List<LlmClient.ToolSpec> pTools, final List<LlmClient.Message> pMessages) throws LlmException
		{
			if (this.calls >= this.script.size())
			{
				throw new LlmException(500, "the script is exhausted");
			}
			return this.script.get(this.calls++);
		}
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final File root = Files.createTempDirectory("llmrec").toFile();
		try
		{
			System.setProperty(LlmRecordStore.STORE_ROOT_PROPERTY, root.getPath());
			LlmRecordStoreTest.passThroughWithoutProperties();
			LlmRecordStoreTest.missRecordsThenHitReplaysWithoutCallingTheModel(root);
			LlmRecordStoreTest.aMultiTurnLoopReplaysTurnByTurn(root);
			LlmRecordStoreTest.theDigestCoversWhatDeterminesTheAnswer();
			LlmRecordStoreTest.theFileLayoutAndThePromptsOption(root);
			LlmRecordStoreTest.replayMissCallsTheModelAndRecordsToTheRecordDir(root);
			LlmRecordStoreTest.anUnusableFileIsAMissAndPropertiesAreReadAtCallTime(root);
			LlmRecordStoreTest.askIsRecordedAndReplayed(root);
			LlmRecordStoreTest.statusJsonReportsTheSettings(root);
			LlmRecordStoreTest.aDirectoryOutsideTheStoreRootIsRefused(root);
			LlmRecordStoreTest.filesAreOwnerOnlyAndBounded(root);
		}
		finally
		{
			System.clearProperty(LlmRecordStore.STORE_ROOT_PROPERTY);
			LlmRecordStoreTest.clear();
			LlmRecordStoreTest.delete(root);
		}
		System.out.println("LlmRecordStoreTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static void clear()
	{
		System.clearProperty(LlmRecordStore.RECORD_DIR_PROPERTY);
		System.clearProperty(LlmRecordStore.REPLAY_DIR_PROPERTY);
		System.clearProperty(LlmRecordStore.RECORD_PROMPTS_PROPERTY);
	}

	private static void delete(final File pFile)
	{
		final File[] children = pFile.listFiles();
		if (children != null)
		{
			for (final File child : children)
			{
				LlmRecordStoreTest.delete(child);
			}
		}
		pFile.delete();
	}

	/** A directory under the root, created: since F2-13 the module never creates a record or replay directory itself. */
	private static File fresh(final File pRoot, final String pName)
	{
		final File dir = new File(pRoot, pName);
		dir.mkdirs();
		return dir;
	}

	/** F2-13: replay and record need the store root AND an existing directory under its canonical path; prompts are never recorded without their own opt-in. */
	private static void aDirectoryOutsideTheStoreRootIsRefused(final File pRoot) throws Exception
	{
		LlmRecordStoreTest.clear();
		final File ok = LlmRecordStoreTest.fresh(pRoot, "valid");
		ok.mkdirs();
		final File outside = Files.createTempDirectory("llmrec-outside").toFile();
		try
		{
			System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, ok.getPath());
			LlmRecordStoreTest.check(LlmRecordStore.replayDir() != null, "a directory under the root is accepted");
			System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, outside.getPath());
			LlmRecordStoreTest.check(LlmRecordStore.replayDir() == null, "a directory outside the root is refused");
			System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, new File(pRoot, "absent").getPath());
			LlmRecordStoreTest.check(LlmRecordStore.replayDir() == null, "a directory that does not exist is refused (never created)");
			LlmRecordStoreTest.check(!new File(pRoot, "absent").exists(), "and it is not created");
			final File link = new File(pRoot, "link");
			Files.createSymbolicLink(link.toPath(), outside.toPath());
			System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, link.getPath());
			LlmRecordStoreTest.check(LlmRecordStore.replayDir() == null, "a symlink out of the root is refused");
			System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, pRoot.getPath() + "/valid/../../" + outside.getName());
			LlmRecordStoreTest.check(LlmRecordStore.replayDir() == null, "a .. path out of the root is refused");
			System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, ok.getPath());
			System.clearProperty(LlmRecordStore.STORE_ROOT_PROPERTY);
			LlmRecordStoreTest.check(LlmRecordStore.replayDir() == null && LlmRecordStore.recordDir() == null, "without the store root nothing is replayed");
			final FakeClient client = new FakeClient();
			client.script.add(LlmRecordStoreTest.text("answered by the model"));
			final LlmReplayChat chat = LlmRecordStoreTest.chat(client, "m1");
			chat.send("sys", LlmRecordStoreTest.NO_TOOLS, LlmRecordStoreTest.user("q"));
			LlmRecordStoreTest.check(client.calls == 1 && chat.replayedTurns() == 0, "so the model is asked");
			System.setProperty(LlmRecordStore.STORE_ROOT_PROPERTY, pRoot.getPath());
			System.setProperty(LlmRecordStore.RECORD_PROMPTS_PROPERTY, "true");
			LlmRecordStoreTest.check(LlmRecordStore.recordDir() == null, "the prompts option alone records nothing");
		}
		finally
		{
			LlmRecordStoreTest.clear();
			LlmRecordStoreTest.delete(outside);
		}
	}

	private static void filesAreOwnerOnlyAndBounded(final File pRoot) throws Exception
	{
		LlmRecordStoreTest.clear();
		final File dir = LlmRecordStoreTest.fresh(pRoot, "perms");
		dir.mkdirs();
		final com.fasterxml.jackson.databind.node.ObjectNode request = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("k", "v");
		final com.fasterxml.jackson.databind.node.ObjectNode answer = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("text", "t");
		final String name = LlmRecordStore.write(dir, "abc123", LlmRecordStore.KIND_ASK, "p", "m", request, answer);
		LlmRecordStoreTest.check(name != null, "a valid directory records");
		LlmRecordStoreTest.check("rw-------".equals(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(new File(dir, name).toPath()))),
			"the file is readable by its owner only");
		final com.fasterxml.jackson.databind.node.ObjectNode big = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
			.put("text", new String(new char[LlmRecordStore.MAX_FILE_BYTES + 10]).replace('\0', 'x'));
		LlmRecordStoreTest.check(LlmRecordStore.write(dir, "big", LlmRecordStore.KIND_ASK, "p", "m", request, big) == null && !new File(dir, "big.json").exists(),
			"an answer over the size limit is not recorded");
	}

	private static LlmClient.Turn text(final String pText)
	{
		return new LlmClient.Turn(pText, new ArrayList<LlmClient.ToolCall>(), LlmClient.StopReason.END, 11, 7);
	}

	private static LlmClient.Turn toolTurn(final String pId, final String pJson) throws Exception
	{
		return new LlmClient.Turn("calling", Collections.singletonList(new LlmClient.ToolCall(pId, "propose_entity", LlmRecordStoreTest.MAPPER.readTree(pJson))),
			LlmClient.StopReason.TOOL_USE, 120, 40);
	}

	private static LlmReplayChat chat(final FakeClient pClient, final String pModel)
	{
		final Map<String, Object> params = new LinkedHashMap<String, Object>();
		params.put("maxTokens", Integer.valueOf(4096));
		return new LlmReplayChat(pClient, "openai", pModel, params);
	}

	private static final List<LlmClient.ToolSpec> NO_TOOLS = new ArrayList<LlmClient.ToolSpec>();

	private static List<LlmClient.Message> user(final String pText)
	{
		return new ArrayList<LlmClient.Message>(Collections.singletonList(LlmClient.Message.user(pText)));
	}

	private static String[] jsonFiles(final File pDir)
	{
		final String[] names = pDir.list();
		if (names == null)
		{
			return new String[0];
		}
		final List<String> out = new ArrayList<String>();
		for (final String name : names)
		{
			if (name.endsWith(".json"))
			{
				out.add(name);
			}
		}
		Collections.sort(out);
		return out.toArray(new String[0]);
	}

	private static void passThroughWithoutProperties() throws Exception
	{
		LlmRecordStoreTest.clear();
		final FakeClient client = new FakeClient();
		client.script.add(LlmRecordStoreTest.text("a"));
		client.script.add(LlmRecordStoreTest.text("b"));
		final LlmReplayChat chat = LlmRecordStoreTest.chat(client, "m1");
		LlmRecordStoreTest.check("a".equals(chat.send("sys", LlmRecordStoreTest.NO_TOOLS, LlmRecordStoreTest.user("q")).text), "first answer");
		LlmRecordStoreTest.check("b".equals(chat.send("sys", LlmRecordStoreTest.NO_TOOLS, LlmRecordStoreTest.user("q")).text), "the same question asked again goes to the model again");
		LlmRecordStoreTest.check(client.calls == 2 && chat.replayedTurns() == 0 && chat.recordedTurns() == 0 && chat.summary() == null && chat.takeReplayMarker() == null,
			"with no property set nothing is replayed, recorded or reported");
	}

	private static void missRecordsThenHitReplaysWithoutCallingTheModel(final File pRoot) throws Exception
	{
		final File dir = LlmRecordStoreTest.fresh(pRoot, "store1/nested");
		LlmRecordStoreTest.clear();
		System.setProperty(LlmRecordStore.RECORD_DIR_PROPERTY, dir.getPath());
		final FakeClient recorder = new FakeClient();
		recorder.script.add(LlmRecordStoreTest.toolTurn("c1", "{\"b\":1,\"a\":{\"y\":2,\"x\":[1,2]}}"));
		final LlmReplayChat first = LlmRecordStoreTest.chat(recorder, "m1");
		final LlmClient.Turn answered = first.send("sys", LlmRecordStoreTest.NO_TOOLS, LlmRecordStoreTest.user("q"));
		LlmRecordStoreTest.check(recorder.calls == 1 && first.recordedTurns() == 1 && first.replayedTurns() == 0, "a miss calls the model once and records");
		LlmRecordStoreTest.check(dir.isDirectory() && LlmRecordStoreTest.jsonFiles(dir).length == 1, "the record directory holds one file");
		LlmRecordStoreTest.check(first.takeReplayMarker() == null, "a turn the model answered carries no replay marker");

		System.clearProperty(LlmRecordStore.RECORD_DIR_PROPERTY);
		System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, dir.getPath());
		final FakeClient silent = new FakeClient(); // a call would throw: the script is empty
		final LlmReplayChat second = LlmRecordStoreTest.chat(silent, "m1");
		final LlmClient.Turn replayed = second.send("sys", LlmRecordStoreTest.NO_TOOLS, LlmRecordStoreTest.user("q"));
		LlmRecordStoreTest.check(silent.calls == 0 && second.replayedTurns() == 1, "a hit never calls the model");
		LlmRecordStoreTest.check(replayed.text.equals(answered.text) && replayed.stopReason == answered.stopReason && replayed.inputTokens == 120 && replayed.outputTokens == 40,
			"the replayed turn is the recorded one (text, stop reason, tokens)");
		LlmRecordStoreTest.check(replayed.toolCalls.size() == 1 && "c1".equals(replayed.toolCalls.get(0).id) && "propose_entity".equals(replayed.toolCalls.get(0).name)
			&& replayed.toolCalls.get(0).arguments.equals(answered.toolCalls.get(0).arguments), "the tool call comes back with id, name and arguments");
		final String marker = second.takeReplayMarker();
		LlmRecordStoreTest.check(marker != null && marker.equals(LlmRecordStoreTest.jsonFiles(dir)[0]), "the replayed turn is marked with its file name: " + marker);
		LlmRecordStoreTest.check(second.takeReplayMarker() == null, "the marker is handed out once");
		LlmRecordStoreTest.check(second.summary() != null && second.summary().contains("replay: 1 of 1"), "the run summary says how many turns were replayed: " + second.summary());
	}

	private static void aMultiTurnLoopReplaysTurnByTurn(final File pRoot) throws Exception
	{
		final File dir = LlmRecordStoreTest.fresh(pRoot, "store2");
		LlmRecordStoreTest.clear();
		System.setProperty(LlmRecordStore.RECORD_DIR_PROPERTY, dir.getPath());
		System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, dir.getPath());
		final LlmClient.Turn turn1 = LlmRecordStoreTest.toolTurn("c1", "{\"entity\":\"Person\"}");
		final LlmClient.Turn turn2 = LlmRecordStoreTest.toolTurn("c2", "{\"entity\":\"Address\"}");
		final LlmClient.Turn turn3 = LlmRecordStoreTest.text("done");

		// run 1: three turns to the model, the conversation grows with each tool result
		final FakeClient model = new FakeClient();
		model.script.addAll(Arrays.asList(turn1, turn2, turn3));
		final LlmReplayChat run1 = LlmRecordStoreTest.chat(model, "m1");
		LlmRecordStoreTest.loop(run1);
		LlmRecordStoreTest.check(model.calls == 3 && run1.recordedTurns() == 3 && LlmRecordStoreTest.jsonFiles(dir).length == 3, "three turns, three files (one per request)");

		// run 2: the same loop, the model must not be called at all, and the answers come in the same order
		final FakeClient never = new FakeClient();
		final LlmReplayChat run2 = LlmRecordStoreTest.chat(never, "m1");
		final List<String> texts = LlmRecordStoreTest.loop(run2);
		LlmRecordStoreTest.check(never.calls == 0 && run2.replayedTurns() == 3 && run2.recordedTurns() == 0, "the whole loop is replayed");
		LlmRecordStoreTest.check(Arrays.asList("calling", "calling", "done").equals(texts), "turn by turn: " + texts);
		LlmRecordStoreTest.check(run2.summary().contains("replay: 3 of 3"), run2.summary());

		// run 3: the loop is different from turn 2 on (the tool answered differently): turn 1 is replayed, the rest goes to the model
		final FakeClient partial = new FakeClient();
		partial.script.addAll(Arrays.asList(LlmRecordStoreTest.text("new turn 2"), LlmRecordStoreTest.text("new turn 3")));
		final LlmReplayChat run3 = LlmRecordStoreTest.chat(partial, "m1");
		final List<LlmClient.Message> conversation = LlmRecordStoreTest.user("the task");
		final LlmClient.Turn t1 = run3.send("sys", LlmRecordStoreTest.NO_TOOLS, conversation);
		LlmRecordStoreTest.check(run3.replayedTurns() == 1 && "c1".equals(t1.toolCalls.get(0).id), "turn 1 is replayed");
		conversation.add(LlmClient.Message.assistant(t1.text, t1.toolCalls));
		conversation.add(LlmClient.Message.toolResults(Collections.singletonList(new LlmClient.ToolResult("c1", "a DIFFERENT tool answer", false))));
		final LlmClient.Turn t2 = run3.send("sys", LlmRecordStoreTest.NO_TOOLS, conversation);
		LlmRecordStoreTest.check("new turn 2".equals(t2.text) && partial.calls == 1 && run3.replayedTurns() == 1, "a changed conversation is a miss: the model is asked");
		LlmRecordStoreTest.check(LlmRecordStoreTest.jsonFiles(dir).length == 4, "and the new answer is recorded (record dir set)");
	}

	/** The scripted three-turn loop of the tests above; returns the texts of the three answers. */
	private static List<String> loop(final LlmReplayChat pChat) throws Exception
	{
		final List<String> texts = new ArrayList<String>();
		final List<LlmClient.Message> conversation = LlmRecordStoreTest.user("the task");
		for (int turn = 1; turn <= 3; turn++)
		{
			final LlmClient.Turn answer = pChat.send("sys", LlmRecordStoreTest.NO_TOOLS, conversation);
			texts.add(answer.text);
			if (answer.toolCalls.isEmpty())
			{
				break;
			}
			conversation.add(LlmClient.Message.assistant(answer.text, answer.toolCalls));
			conversation.add(LlmClient.Message.toolResults(Collections.singletonList(new LlmClient.ToolResult(answer.toolCalls.get(0).id, "recorded " + turn, false))));
		}
		return texts;
	}

	private static void theDigestCoversWhatDeterminesTheAnswer() throws Exception
	{
		final List<LlmClient.ToolSpec> tools = Collections.singletonList(new LlmClient.ToolSpec("t", "does t", LlmRecordStoreTest.MAPPER.readTree("{\"type\":\"object\"}")));
		final Map<String, Object> params = new LinkedHashMap<String, Object>();
		params.put("maxTokens", Integer.valueOf(4096));
		final String base = LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", params, "sys", tools, LlmRecordStoreTest.user("q")));
		LlmRecordStoreTest.check(base.matches("[0-9a-f]{64}"), "SHA-256 hex: " + base);
		LlmRecordStoreTest.check(base.equals(LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", params, "sys", tools, LlmRecordStoreTest.user("q")))), "stable");
		final Map<String, Object> other = new LinkedHashMap<String, Object>();
		other.put("maxTokens", Integer.valueOf(8192));
		final Map<String, Object> reasoning = new LinkedHashMap<String, Object>(params);
		reasoning.put("reasoningEffort", "high");
		final String[] different = {
			LlmRecordStore.digest(LlmRecordStore.chatRequest("anthropic", "m1", params, "sys", tools, LlmRecordStoreTest.user("q"))),
			LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m2", params, "sys", tools, LlmRecordStoreTest.user("q"))),
			LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", other, "sys", tools, LlmRecordStoreTest.user("q"))),
			LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", reasoning, "sys", tools, LlmRecordStoreTest.user("q"))),
			LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", params, "sys2", tools, LlmRecordStoreTest.user("q"))),
			LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", params, "sys", LlmRecordStoreTest.NO_TOOLS, LlmRecordStoreTest.user("q"))),
			LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", params, "sys", Collections.singletonList(new LlmClient.ToolSpec("t", "does t, differently",
				LlmRecordStoreTest.MAPPER.readTree("{\"type\":\"object\"}"))), LlmRecordStoreTest.user("q"))),
			LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", params, "sys", tools, LlmRecordStoreTest.user("q2"))) };
		for (int i = 0; i < different.length; i++)
		{
			LlmRecordStoreTest.check(!base.equals(different[i]), "input #" + i + " (provider, model, maxTokens, reasoningEffort, system, tools name/description, message) is part of the digest");
		}
		// an object's keys in another order digest the same; a different tool-call argument does not
		final List<LlmClient.Message> a = LlmRecordStoreTest.user("q");
		a.add(LlmClient.Message.assistant("x", Collections.singletonList(new LlmClient.ToolCall("c", "t", LlmRecordStoreTest.MAPPER.readTree("{\"a\":1,\"b\":{\"c\":2,\"d\":3}}")))));
		final List<LlmClient.Message> b = LlmRecordStoreTest.user("q");
		b.add(LlmClient.Message.assistant("x", Collections.singletonList(new LlmClient.ToolCall("c", "t", LlmRecordStoreTest.MAPPER.readTree("{\"b\":{\"d\":3,\"c\":2},\"a\":1}")))));
		final List<LlmClient.Message> c = LlmRecordStoreTest.user("q");
		c.add(LlmClient.Message.assistant("x", Collections.singletonList(new LlmClient.ToolCall("c", "t", LlmRecordStoreTest.MAPPER.readTree("{\"a\":1,\"b\":{\"c\":2,\"d\":4}}")))));
		LlmRecordStoreTest.check(LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", params, "sys", tools, a))
			.equals(LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", params, "sys", tools, b))), "key order inside tool-call arguments does not change the digest");
		LlmRecordStoreTest.check(!LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", params, "sys", tools, a))
			.equals(LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", params, "sys", tools, c))), "a different argument value does");
		// a tool result's text and error flag are part of the request
		final List<LlmClient.Message> r1 = LlmRecordStoreTest.user("q");
		r1.add(LlmClient.Message.toolResults(Collections.singletonList(new LlmClient.ToolResult("c", "ok", false))));
		final List<LlmClient.Message> r2 = LlmRecordStoreTest.user("q");
		r2.add(LlmClient.Message.toolResults(Collections.singletonList(new LlmClient.ToolResult("c", "ok", true))));
		LlmRecordStoreTest.check(!LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", params, "sys", tools, r1))
			.equals(LlmRecordStore.digest(LlmRecordStore.chatRequest("openai", "m1", params, "sys", tools, r2))), "the tool result's error flag is part of the digest");
	}

	private static void theFileLayoutAndThePromptsOption(final File pRoot) throws Exception
	{
		final File without = LlmRecordStoreTest.fresh(pRoot, "layout-plain");
		final File with = LlmRecordStoreTest.fresh(pRoot, "layout-prompts");
		LlmRecordStoreTest.clear();
		System.setProperty(LlmRecordStore.RECORD_DIR_PROPERTY, without.getPath());
		final FakeClient client = new FakeClient();
		client.script.add(LlmRecordStoreTest.text("secret-free answer"));
		client.script.add(LlmRecordStoreTest.text("secret-free answer"));
		final List<LlmClient.ToolSpec> tools = Collections.singletonList(new LlmClient.ToolSpec("sample_tool", "d", LlmRecordStoreTest.MAPPER.readTree("{}")));
		LlmRecordStoreTest.chat(client, "m1").send("SYSTEM-PROMPT-TEXT", tools, LlmRecordStoreTest.user("SAMPLED-CUSTOMER-VALUE"));
		final String name = LlmRecordStoreTest.jsonFiles(without)[0];
		final String raw = new String(Files.readAllBytes(new File(without, name).toPath()), "UTF-8");
		final JsonNode file = LlmRecordStoreTest.MAPPER.readTree(raw);
		LlmRecordStoreTest.check(name.equals(file.path("digest").asText() + ".json") && file.path("digest").asText().length() == 64, "file name = <digest>.json");
		LlmRecordStoreTest.check(file.path("format").asInt() == 1 && "chat".equals(file.path("kind").asText()) && "openai".equals(file.path("provider").asText())
			&& "m1".equals(file.path("model").asText()), "format, kind, provider, model");
		LlmRecordStoreTest.check(file.path("recordedAt").asText().matches("\\d{4}-\\d\\d-\\d\\dT.*Z"), "recordedAt is an ISO instant: " + file.path("recordedAt").asText());
		LlmRecordStoreTest.check("secret-free answer".equals(file.path("answer").path("text").asText()) && file.path("answer").path("inputTokens").asInt() == 11
			&& "END".equals(file.path("answer").path("stopReason").asText()), "the raw answer");
		LlmRecordStoreTest.check(!file.has("request") && !raw.contains("SAMPLED-CUSTOMER-VALUE") && !raw.contains("SYSTEM-PROMPT-TEXT"), "the prompt text is NOT written by default");
		LlmRecordStoreTest.check(file.path("shape").path("messages").asInt() == 1 && "sample_tool".equals(file.path("shape").path("tools").path(0).asText()), "only a shape is written");
		LlmRecordStoreTest.check(LlmRecordStoreTest.jsonFiles(without).length == 1 && !hasTemp(without), "no temporary file is left behind");

		System.setProperty(LlmRecordStore.RECORD_DIR_PROPERTY, with.getPath());
		System.setProperty(LlmRecordStore.RECORD_PROMPTS_PROPERTY, "true");
		LlmRecordStoreTest.chat(client, "m1").send("SYSTEM-PROMPT-TEXT", tools, LlmRecordStoreTest.user("SAMPLED-CUSTOMER-VALUE"));
		final JsonNode withPrompts = LlmRecordStoreTest.MAPPER.readTree(new File(with, name));
		LlmRecordStoreTest.check("SYSTEM-PROMPT-TEXT".equals(withPrompts.path("request").path("system").asText())
			&& "SAMPLED-CUSTOMER-VALUE".equals(withPrompts.path("request").path("messages").path(0).path("text").asText()), "record.prompts=true adds the request");
	}

	private static boolean hasTemp(final File pDir)
	{
		for (final String name : pDir.list())
		{
			if (name.endsWith(".tmp"))
			{
				return true;
			}
		}
		return false;
	}

	private static void replayMissCallsTheModelAndRecordsToTheRecordDir(final File pRoot) throws Exception
	{
		final File replayDir = LlmRecordStoreTest.fresh(pRoot, "replay-only");
		final File recordDir = LlmRecordStoreTest.fresh(pRoot, "record-other");
		replayDir.mkdirs();
		LlmRecordStoreTest.clear();
		System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, replayDir.getPath());
		final FakeClient model = new FakeClient();
		model.script.add(LlmRecordStoreTest.text("one"));
		model.script.add(LlmRecordStoreTest.text("two"));
		final LlmReplayChat chat = LlmRecordStoreTest.chat(model, "m1");
		LlmRecordStoreTest.check("one".equals(chat.send("s", LlmRecordStoreTest.NO_TOOLS, LlmRecordStoreTest.user("q")).text) && model.calls == 1, "replay dir without the answer: the model is asked");
		LlmRecordStoreTest.check(LlmRecordStoreTest.jsonFiles(replayDir).length == 0 && chat.recordedTurns() == 0, "and nothing is recorded without a record dir");
		System.setProperty(LlmRecordStore.RECORD_DIR_PROPERTY, recordDir.getPath());
		chat.send("s", LlmRecordStoreTest.NO_TOOLS, LlmRecordStoreTest.user("q"));
		LlmRecordStoreTest.check(LlmRecordStoreTest.jsonFiles(recordDir).length == 1 && LlmRecordStoreTest.jsonFiles(replayDir).length == 0 && chat.recordedTurns() == 1,
			"with a record dir the miss is written there (and only there)");
		// a different model name is a miss against the file recorded for m1
		System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, recordDir.getPath());
		final FakeClient other = new FakeClient();
		other.script.add(LlmRecordStoreTest.text("other model answer"));
		LlmRecordStoreTest.check("other model answer".equals(LlmRecordStoreTest.chat(other, "m2").send("s", LlmRecordStoreTest.NO_TOOLS, LlmRecordStoreTest.user("q")).text), "another model never gets m1's answer");
	}

	private static void anUnusableFileIsAMissAndPropertiesAreReadAtCallTime(final File pRoot) throws Exception
	{
		final File dir = LlmRecordStoreTest.fresh(pRoot, "corrupt");
		LlmRecordStoreTest.clear();
		System.setProperty(LlmRecordStore.RECORD_DIR_PROPERTY, dir.getPath());
		final FakeClient client = new FakeClient();
		client.script.add(LlmRecordStoreTest.text("recorded"));
		client.script.add(LlmRecordStoreTest.text("second call"));
		client.script.add(LlmRecordStoreTest.text("third call"));
		final LlmReplayChat chat = LlmRecordStoreTest.chat(client, "m1");
		chat.send("s", LlmRecordStoreTest.NO_TOOLS, LlmRecordStoreTest.user("q"));
		final File file = new File(dir, LlmRecordStoreTest.jsonFiles(dir)[0]);
		Files.write(file.toPath(), "{ not json".getBytes("UTF-8"));
		System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, dir.getPath());
		LlmRecordStoreTest.check("second call".equals(chat.send("s", LlmRecordStoreTest.NO_TOOLS, LlmRecordStoreTest.user("q")).text), "a corrupt file is a miss, the model is asked");
		LlmRecordStoreTest.check(chat.replayedTurns() == 0 && LlmRecordStoreTest.MAPPER.readTree(file).path("answer").path("text").asText().equals("second call"), "and the file is rewritten by the record dir");
		// the very same chat object, properties cleared between two calls: read at call time, so nothing is replayed nor recorded now
		LlmRecordStoreTest.clear();
		LlmRecordStoreTest.check("third call".equals(chat.send("s", LlmRecordStoreTest.NO_TOOLS, LlmRecordStoreTest.user("q")).text) && chat.replayedTurns() == 0, "properties are read at each call");
		System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, dir.getPath());
		LlmRecordStoreTest.check("second call".equals(chat.send("s", LlmRecordStoreTest.NO_TOOLS, LlmRecordStoreTest.user("q")).text) && chat.replayedTurns() == 1, "and switched on again");
	}

	private static void askIsRecordedAndReplayed(final File pRoot) throws Exception
	{
		final File dir = LlmRecordStoreTest.fresh(pRoot, "ask");
		LlmRecordStoreTest.clear();
		final int[] calls = { 0 };
		final LlmRecordStore.AskCall call = new LlmRecordStore.AskCall()
		{
			@Override
			public LlmContract.Answer call()
			{
				calls[0]++;
				return new LlmContract.Answer("{\"packs\":[\"P1\"]}", "gpt-x", "openai", "OpenAI", 50, 9, false, 1234L);
			}
		};
		LlmRecordStoreTest.check(LlmRecordStore.ask("OpenAI", "sys", "prompt", 1000, true, call).replayedFrom == null && calls[0] == 1, "no property: straight to the model");
		System.setProperty(LlmRecordStore.RECORD_DIR_PROPERTY, dir.getPath());
		System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, dir.getPath());
		final LlmRecordStore.AskResult miss = LlmRecordStore.ask("OpenAI", "sys", "prompt", 1000, true, call);
		LlmRecordStoreTest.check(miss.replayedFrom == null && calls[0] == 2 && LlmRecordStoreTest.jsonFiles(dir).length == 1, "miss: the model answers and the pair is recorded");
		final LlmRecordStore.AskResult hit = LlmRecordStore.ask("OpenAI", "sys", "prompt", 1000, true, call);
		LlmRecordStoreTest.check(calls[0] == 2 && hit.replayedFrom != null && hit.replayedFrom.equals(LlmRecordStoreTest.jsonFiles(dir)[0]), "hit: no call, the file is named");
		LlmRecordStoreTest.check("{\"packs\":[\"P1\"]}".equals(hit.answer.text) && "gpt-x".equals(hit.answer.model) && "openai".equals(hit.answer.provider) && hit.answer.inputTokens == 50
			&& hit.answer.outputTokens == 9 && !hit.answer.truncated && hit.answer.durationMs == 1234L, "the whole Answer comes back");
		LlmRecordStore.ask("OpenAI", "sys", "prompt", 1000, false, call);
		LlmRecordStore.ask("OpenAI", "sys", "prompt2", 1000, true, call);
		LlmRecordStore.ask("OpenAI", "sys", "prompt", 2000, true, call);
		LlmRecordStore.ask("Other", "sys", "prompt", 1000, true, call);
		LlmRecordStoreTest.check(calls[0] == 6, "json flag, prompt, maxTokens and connection are part of the digest (4 more calls)");
		// a chat file is never taken for an ask (kind is checked)
		final ObjectMapperHolder holder = new ObjectMapperHolder();
		LlmRecordStoreTest.check(holder.kindMismatchIsAMiss(dir), "a file of the other kind is a miss");
	}

	private static final class ObjectMapperHolder
	{
		boolean kindMismatchIsAMiss(final File pDir)
		{
			final String digest = LlmRecordStore.digest(LlmRecordStore.askRequest("OpenAI", "sys", "prompt", 1000, true));
			return LlmRecordStore.lookup(pDir, digest, LlmRecordStore.KIND_CHAT) == null && LlmRecordStore.lookup(pDir, digest, LlmRecordStore.KIND_ASK) != null;
		}
	}

	private static void statusJsonReportsTheSettings(final File pRoot) throws Exception
	{
		LlmRecordStoreTest.clear();
		JsonNode status = LlmRecordStore.statusJson();
		LlmRecordStoreTest.check(!status.path("replay").path("enabled").asBoolean(true) && !status.path("record").path("enabled").asBoolean(true), "off by default");
		final File dir = LlmRecordStoreTest.fresh(pRoot, "ask");
		System.setProperty(LlmRecordStore.REPLAY_DIR_PROPERTY, dir.getPath());
		System.setProperty(LlmRecordStore.RECORD_DIR_PROPERTY, dir.getPath());
		System.setProperty(LlmRecordStore.RECORD_PROMPTS_PROPERTY, "true");
		status = LlmRecordStore.statusJson();
		LlmRecordStoreTest.check(status.path("replay").path("enabled").asBoolean() && dir.getPath().equals(status.path("replay").path("dir").asText()) && status.path("replay").path("files").asInt() >= 1
			&& status.path("record").path("enabled").asBoolean() && status.path("record").path("prompts").asBoolean(), "on: dirs, file counts, prompts flag: " + status);
	}
}
