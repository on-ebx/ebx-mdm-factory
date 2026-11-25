package com.on.ebx.factory.ebx;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LlmClient;
import com.orchestranetworks.service.LoggingCategory;

/**
 * Record / replay of the language model's answers (test-cycle lot F4, 2026-10-19): a store of request/answer pairs, one JSON file per request, keyed by a SHA-256 digest of
 * everything that determines the answer. It exists so that what is NOT the model (the engine, the matching, the master load, the distribution) can be tested again and again
 * without paying for the model's turns, which are two thirds of an end-to-end run.
 * <p>
 * Two JVM system properties, both read AT CALL TIME (so they can be set from a running instance's launcher or a test, never cached):
 * <ul>
 * <li>{@value #RECORD_DIR_PROPERTY}{@code =<dir>}: every answer obtained from the model is written to {@code <dir>/<digest>.json};</li>
 * <li>{@value #REPLAY_DIR_PROPERTY}{@code =<dir>}: a request whose digest is in {@code <dir>} is answered from the file, with no call to the model; any other request calls the
 * model and, when a record directory is set too, records the answer there;</li>
 * <li>{@value #RECORD_PROMPTS_PROPERTY}{@code =true}: the file also carries the full request ({@code request}: system prompt, messages, tools). Off by default - prompts may
 * hold sampled shapes of the customer's data. Without it the file holds only a {@code shape} (counts and tool names).</li>
 * </ul>
 * <p>
 * Audit F2-13 (2026-10-24): a file in the replay directory chooses what people accept, so neither directory is honoured on a property alone. {@value #STORE_ROOT_PROPERTY}{@code =<root>} names the
 * one allowed root; a record or replay directory counts only when it EXISTS, is writable, and its canonical path (symlinks resolved) is under that root's canonical path - otherwise it is
 * ignored with a WARN once per JVM, and the model is asked. Prompts are never recorded without {@value #RECORD_PROMPTS_PROPERTY}{@code =true} on top, and only to a valid record directory.
 * Files are created readable by their owner only, a file over {@value #MAX_FILE_BYTES} bytes is neither written nor replayed, and a record directory holds at most {@value #MAX_FILES} files.
 * <p>
 * A multi-turn loop is replayed turn by turn: every turn is its own request (the conversation so far included) and its own digest.
 * <p>
 * <b>The digest</b> covers, for a chat turn: {@code provider}, {@code model}, the call's parameters ({@code maxTokens}, {@code reasoningEffort}; there is no temperature in
 * {@link LlmClient}), the system prompt, the tools (name, description, schema) and the whole conversation (texts, tool calls with their arguments, tool results). Object keys are
 * sorted, so a tool's arguments digest the same whatever the order they were written in. For a one-question {@code llm.ask}: connection id, system, prompt, {@code maxTokens}
 * and {@code json}. A request that embeds anything run-specific (a project code with a time stamp in it) digests differently each run: replay a run against the SAME project.
 * <p>
 * File layout: {@code {"format":1,"digest","kind":"chat"|"ask","provider","model","recordedAt","answer":{...},"shape":{...},"request":{...}?}} where {@code answer} is
 * {@code {text,toolCalls[{id,name,arguments}],stopReason,inputTokens,outputTokens}} for a chat turn and {@code {text,model,provider,connector,inputTokens,outputTokens,truncated,
 * durationMs}} for an ask. Files are written to a temporary name and moved into place, so a reader never sees half a file. The module never prunes the folder.
 */
final class LlmRecordStore
{
	static final String RECORD_DIR_PROPERTY = "ebx.factory.llm.record.dir";
	static final String REPLAY_DIR_PROPERTY = "ebx.factory.llm.replay.dir";
	static final String RECORD_PROMPTS_PROPERTY = "ebx.factory.llm.record.prompts";
	static final String STORE_ROOT_PROPERTY = "ebx.factory.llm.storeRoot";
	static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
	static final int MAX_FILES = 5000;
	static final int FORMAT = 1;
	static final String KIND_CHAT = "chat";
	static final String KIND_ASK = "ask";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private LlmRecordStore()
	{
	}

	// ------------------------------------------------------------------------------------------------ settings (read at call time)

	/** The directory answers are recorded to; null when {@value #RECORD_DIR_PROPERTY} is unset, or is not an existing writable directory under {@value #STORE_ROOT_PROPERTY}. */
	static File recordDir()
	{
		return LlmRecordStore.dirOf(LlmRecordStore.RECORD_DIR_PROPERTY);
	}

	/** The directory answers are replayed from; null when {@value #REPLAY_DIR_PROPERTY} is unset, or is not an existing writable directory under {@value #STORE_ROOT_PROPERTY}. */
	static File replayDir()
	{
		return LlmRecordStore.dirOf(LlmRecordStore.REPLAY_DIR_PROPERTY);
	}

	static boolean recordPrompts()
	{
		return "true".equalsIgnoreCase(String.valueOf(System.getProperty(LlmRecordStore.RECORD_PROMPTS_PROPERTY, "false")).trim());
	}

	private static final java.util.Set<String> WARNED = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

	private static File dirOf(final String pProperty)
	{
		final String value = System.getProperty(pProperty);
		if (value == null || value.trim().isEmpty())
		{
			return null;
		}
		final String problem = LlmRecordStore.dirProblem(value.trim(), System.getProperty(LlmRecordStore.STORE_ROOT_PROPERTY));
		if (problem != null)
		{
			if (LlmRecordStore.WARNED.add(pProperty + "|" + value + "|" + problem))
			{
				LlmRecordStore.warn("-D" + pProperty + " is ignored: " + problem);
			}
			return null;
		}
		return new File(value);
	}

	/** Why a directory may not be used as a store, or null when it may: it must exist, be writable, and be under the canonical allowed root. Pure of any property. */
	static String dirProblem(final String pDir, final String pRoot)
	{
		if (pRoot == null || pRoot.trim().isEmpty())
		{
			return "-D" + LlmRecordStore.STORE_ROOT_PROPERTY + " is not set: name the one existing directory the record and replay directories must be under";
		}
		try
		{
			final java.nio.file.Path root = java.nio.file.Paths.get(pRoot.trim()).toRealPath();
			if (!Files.isDirectory(root))
			{
				return "-D" + LlmRecordStore.STORE_ROOT_PROPERTY + " is not an existing directory";
			}
			final java.nio.file.Path dir = java.nio.file.Paths.get(pDir.trim());
			if (!Files.isDirectory(dir))
			{
				return "the directory " + pDir + " does not exist: create it first (it is no longer created for you)";
			}
			final java.nio.file.Path real = dir.toRealPath();
			if (!real.startsWith(root))
			{
				return "the directory " + pDir + " is not under -D" + LlmRecordStore.STORE_ROOT_PROPERTY + " (" + root + ")";
			}
			if (!Files.isWritable(real))
			{
				return "the directory " + pDir + " is not writable by the server";
			}
			return null;
		}
		catch (final IOException | RuntimeException ex)
		{
			return "the directory " + pDir + " cannot be resolved (" + ex + ")";
		}
	}

	/** The {@code llm} block of {@code /status}: what record and replay are set to right now (paths, not content). */
	static ObjectNode statusJson()
	{
		final ObjectNode out = LlmRecordStore.MAPPER.createObjectNode();
		final File replay = LlmRecordStore.replayDir();
		final File record = LlmRecordStore.recordDir();
		final String root = System.getProperty(LlmRecordStore.STORE_ROOT_PROPERTY);
		out.put("storeRootSet", root != null && !root.trim().isEmpty());
		final ObjectNode replayNode = out.putObject("replay");
		replayNode.put("enabled", replay != null);
		if (replay != null)
		{
			replayNode.put("dir", replay.getPath());
			replayNode.put("files", LlmRecordStore.countFiles(replay));
		}
		final ObjectNode recordNode = out.putObject("record");
		recordNode.put("enabled", record != null);
		if (record != null)
		{
			recordNode.put("dir", record.getPath());
			recordNode.put("prompts", LlmRecordStore.recordPrompts());
			recordNode.put("files", LlmRecordStore.countFiles(record));
		}
		return out;
	}

	private static int countFiles(final File pDir)
	{
		final String[] names = pDir.list();
		int count = 0;
		if (names != null)
		{
			for (final String name : names)
			{
				if (name.endsWith(".json"))
				{
					count++;
				}
			}
		}
		return count;
	}

	// ------------------------------------------------------------------------------------------------ the request and its digest (pure)

	/** The request of one chat turn, as the digest sees it. {@code pParams} holds the call's other settings (maxTokens, reasoningEffort...). */
	static ObjectNode chatRequest(final String pProvider, final String pModel, final Map<String, Object> pParams, final String pSystem,
		final List<LlmClient.ToolSpec> pTools, final List<LlmClient.Message> pMessages)
	{
		final ObjectNode request = LlmRecordStore.MAPPER.createObjectNode();
		request.put("kind", LlmRecordStore.KIND_CHAT);
		request.put("provider", pProvider == null ? "" : pProvider);
		request.put("model", pModel == null ? "" : pModel);
		final ObjectNode params = request.putObject("params");
		if (pParams != null)
		{
			for (final Map.Entry<String, Object> entry : new TreeMap<String, Object>(pParams).entrySet())
			{
				params.set(entry.getKey(), LlmRecordStore.MAPPER.valueToTree(entry.getValue()));
			}
		}
		request.put("system", pSystem == null ? "" : pSystem);
		final ArrayNode tools = request.putArray("tools");
		if (pTools != null)
		{
			for (final LlmClient.ToolSpec tool : pTools)
			{
				final ObjectNode node = tools.addObject();
				node.put("name", tool.name);
				node.put("description", tool.description == null ? "" : tool.description);
				node.set("inputSchema", tool.inputSchema == null ? LlmRecordStore.MAPPER.nullNode() : tool.inputSchema);
			}
		}
		final ArrayNode messages = request.putArray("messages");
		if (pMessages != null)
		{
			for (final LlmClient.Message message : pMessages)
			{
				final ObjectNode node = messages.addObject();
				node.put("role", message.role());
				node.put("text", message.getText() == null ? "" : message.getText());
				final ArrayNode calls = node.putArray("toolCalls");
				if (message.getToolCalls() != null)
				{
					for (final LlmClient.ToolCall call : message.getToolCalls())
					{
						calls.add(LlmRecordStore.callJson(call));
					}
				}
				final ArrayNode results = node.putArray("results");
				if (message.getResults() != null)
				{
					for (final LlmClient.ToolResult result : message.getResults())
					{
						final ObjectNode resultNode = results.addObject();
						resultNode.put("toolCallId", result.toolCallId == null ? "" : result.toolCallId);
						resultNode.put("text", result.text == null ? "" : result.text);
						resultNode.put("isError", result.isError);
					}
				}
			}
		}
		return request;
	}

	/** The request of one {@code llm.ask}, as the digest sees it. The model is not known before the answer: the connection id stands for it. */
	static ObjectNode askRequest(final String pConnector, final String pSystem, final String pPrompt, final int pMaxTokens, final boolean pJson)
	{
		final ObjectNode request = LlmRecordStore.MAPPER.createObjectNode();
		request.put("kind", LlmRecordStore.KIND_ASK);
		request.put("connector", pConnector == null ? "" : pConnector);
		request.put("system", pSystem == null ? "" : pSystem);
		request.put("prompt", pPrompt == null ? "" : pPrompt);
		request.put("maxTokens", pMaxTokens);
		request.put("json", pJson);
		return request;
	}

	/** SHA-256 (lower-case hex) of the canonical form of {@code pRequest}: object keys sorted, no white space. */
	static String digest(final JsonNode pRequest)
	{
		final StringBuilder canonical = new StringBuilder();
		LlmRecordStore.canonical(pRequest, canonical);
		try
		{
			final byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			final StringBuilder hex = new StringBuilder(hash.length * 2);
			for (final byte b : hash)
			{
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (final NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException("SHA-256 is guaranteed on every JVM", ex);
		}
	}

	private static void canonical(final JsonNode pNode, final StringBuilder pOut)
	{
		if (pNode == null || pNode.isNull() || pNode.isMissingNode())
		{
			pOut.append("null");
		}
		else if (pNode.isObject())
		{
			final TreeMap<String, JsonNode> sorted = new TreeMap<String, JsonNode>();
			for (final Iterator<Map.Entry<String, JsonNode>> it = pNode.fields(); it.hasNext();)
			{
				final Map.Entry<String, JsonNode> entry = it.next();
				sorted.put(entry.getKey(), entry.getValue());
			}
			pOut.append('{');
			boolean first = true;
			for (final Map.Entry<String, JsonNode> entry : sorted.entrySet())
			{
				if (!first)
				{
					pOut.append(',');
				}
				first = false;
				pOut.append(LlmRecordStore.MAPPER.valueToTree(entry.getKey()).toString()).append(':');
				LlmRecordStore.canonical(entry.getValue(), pOut);
			}
			pOut.append('}');
		}
		else if (pNode.isArray())
		{
			pOut.append('[');
			for (int i = 0; i < pNode.size(); i++)
			{
				if (i > 0)
				{
					pOut.append(',');
				}
				LlmRecordStore.canonical(pNode.get(i), pOut);
			}
			pOut.append(']');
		}
		else
		{
			pOut.append(pNode.toString());
		}
	}

	// ------------------------------------------------------------------------------------------------ the answer (pure)

	private static ObjectNode callJson(final LlmClient.ToolCall pCall)
	{
		final ObjectNode node = LlmRecordStore.MAPPER.createObjectNode();
		node.put("id", pCall.id == null ? "" : pCall.id);
		node.put("name", pCall.name == null ? "" : pCall.name);
		node.set("arguments", pCall.arguments == null ? LlmRecordStore.MAPPER.nullNode() : pCall.arguments);
		return node;
	}

	static ObjectNode turnJson(final LlmClient.Turn pTurn)
	{
		final ObjectNode node = LlmRecordStore.MAPPER.createObjectNode();
		node.put("text", pTurn.text == null ? "" : pTurn.text);
		final ArrayNode calls = node.putArray("toolCalls");
		if (pTurn.toolCalls != null)
		{
			for (final LlmClient.ToolCall call : pTurn.toolCalls)
			{
				calls.add(LlmRecordStore.callJson(call));
			}
		}
		node.put("stopReason", pTurn.stopReason == null ? LlmClient.StopReason.OTHER.name() : pTurn.stopReason.name());
		node.put("inputTokens", pTurn.inputTokens);
		node.put("outputTokens", pTurn.outputTokens);
		return node;
	}

	static LlmClient.Turn turnOf(final JsonNode pAnswer)
	{
		final List<LlmClient.ToolCall> calls = new ArrayList<LlmClient.ToolCall>();
		for (final JsonNode call : pAnswer.path("toolCalls"))
		{
			calls.add(new LlmClient.ToolCall(call.path("id").asText(""), call.path("name").asText(""), call.path("arguments")));
		}
		LlmClient.StopReason stop;
		try
		{
			stop = LlmClient.StopReason.valueOf(pAnswer.path("stopReason").asText("OTHER"));
		}
		catch (final IllegalArgumentException ex)
		{
			stop = LlmClient.StopReason.OTHER;
		}
		return new LlmClient.Turn(pAnswer.path("text").asText(""), calls, stop, pAnswer.path("inputTokens").asInt(0), pAnswer.path("outputTokens").asInt(0));
	}

	static ObjectNode answerJson(final LlmContract.Answer pAnswer)
	{
		final ObjectNode node = LlmRecordStore.MAPPER.createObjectNode();
		node.put("text", pAnswer.text);
		node.put("model", pAnswer.model);
		node.put("provider", pAnswer.provider);
		node.put("connector", pAnswer.connector);
		node.put("inputTokens", pAnswer.inputTokens);
		node.put("outputTokens", pAnswer.outputTokens);
		node.put("truncated", pAnswer.truncated);
		node.put("durationMs", pAnswer.durationMs);
		return node;
	}

	static LlmContract.Answer askAnswerOf(final JsonNode pAnswer)
	{
		return new LlmContract.Answer(pAnswer.path("text").asText(""), pAnswer.path("model").asText(""), pAnswer.path("provider").asText(""), pAnswer.path("connector").asText(""),
			pAnswer.path("inputTokens").asInt(0), pAnswer.path("outputTokens").asInt(0), pAnswer.path("truncated").asBoolean(false), pAnswer.path("durationMs").asLong(0L));
	}

	/** What a request looks like without its text: counts and tool names only - what a file holds when prompts are not recorded. */
	static ObjectNode shapeOf(final JsonNode pRequest)
	{
		final ObjectNode shape = LlmRecordStore.MAPPER.createObjectNode();
		shape.put("systemChars", pRequest.path("system").asText("").length());
		if (pRequest.has("prompt"))
		{
			shape.put("promptChars", pRequest.path("prompt").asText("").length());
		}
		if (pRequest.has("messages"))
		{
			shape.put("messages", pRequest.path("messages").size());
		}
		final ArrayNode tools = shape.putArray("tools");
		for (final JsonNode tool : pRequest.path("tools"))
		{
			tools.add(tool.path("name").asText(""));
		}
		return shape;
	}

	// ------------------------------------------------------------------------------------------------ the files

	/** A stored answer found by {@link #lookup}. */
	static final class Hit
	{
		/** The file name only (no directory), what the trace shows. */
		final String file;
		final JsonNode answer;
		final String provider;
		final String model;

		Hit(final String pFile, final JsonNode pAnswer, final String pProvider, final String pModel)
		{
			this.file = pFile;
			this.answer = pAnswer;
			this.provider = pProvider;
			this.model = pModel;
		}
	}

	static String fileName(final String pDigest)
	{
		return pDigest + ".json";
	}

	/** The stored answer of {@code pDigest} in {@code pDir}; null when there is none, or when the file is unreadable / of another format / for another kind (a miss, never an error). */
	static Hit lookup(final File pDir, final String pDigest, final String pKind)
	{
		final File file = new File(pDir, LlmRecordStore.fileName(pDigest));
		if (!file.isFile())
		{
			return null;
		}
		if (file.length() > LlmRecordStore.MAX_FILE_BYTES)
		{
			LlmRecordStore.warn("record " + file.getPath() + " is larger than " + LlmRecordStore.MAX_FILE_BYTES + " bytes: the model is asked instead");
			return null;
		}
		try
		{
			final JsonNode root = LlmRecordStore.MAPPER.readTree(file);
			if (root == null || root.path("format").asInt(0) != LlmRecordStore.FORMAT || !pDigest.equals(root.path("digest").asText("")) || !pKind.equals(root.path("kind").asText(""))
				|| !root.path("answer").isObject())
			{
				LlmRecordStore.warn("record " + file.getPath() + " is not a usable answer of this request (format, digest or kind): the model is asked instead");
				return null;
			}
			return new Hit(file.getName(), root.path("answer"), root.path("provider").asText(""), root.path("model").asText(""));
		}
		catch (final IOException | RuntimeException ex)
		{
			LlmRecordStore.warn("record " + file.getPath() + " cannot be read (" + ex + "): the model is asked instead");
			return null;
		}
	}

	/**
	 * Writes the pair to {@code <pDir>/<digest>.json} (temporary file, then moved). Never throws: a recording that fails is logged and the run goes on.
	 *
	 * @return the file name written, or null when it could not be
	 */
	static String write(final File pDir, final String pDigest, final String pKind, final String pProvider, final String pModel, final JsonNode pRequest, final JsonNode pAnswer)
	{
		try
		{
			final File target = new File(pDir, LlmRecordStore.fileName(pDigest));
			if (!target.exists() && LlmRecordStore.countFiles(pDir) >= LlmRecordStore.MAX_FILES)
			{
				LlmRecordStore.warn("the record directory " + pDir.getPath() + " already holds " + LlmRecordStore.MAX_FILES + " files: this answer is not recorded");
				return null;
			}
			final ObjectNode root = LlmRecordStore.MAPPER.createObjectNode();
			root.put("format", LlmRecordStore.FORMAT);
			root.put("digest", pDigest);
			root.put("kind", pKind);
			root.put("provider", pProvider == null ? "" : pProvider);
			root.put("model", pModel == null ? "" : pModel);
			root.put("recordedAt", Instant.now().toString());
			root.set("answer", pAnswer);
			root.set("shape", LlmRecordStore.shapeOf(pRequest));
			if (LlmRecordStore.recordPrompts())
			{
				root.set("request", pRequest);
			}
			final byte[] bytes = LlmRecordStore.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
			if (bytes.length > LlmRecordStore.MAX_FILE_BYTES)
			{
				LlmRecordStore.warn("this answer is " + bytes.length + " bytes, over the " + LlmRecordStore.MAX_FILE_BYTES + " limit: it is not recorded");
				return null;
			}
			final File temp = File.createTempFile("." + pDigest, ".tmp", pDir);
			try
			{
				Files.write(temp.toPath(), bytes);
				LlmRecordStore.ownerOnly(temp);
				Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
			finally
			{
				Files.deleteIfExists(temp.toPath());
			}
			return target.getName();
		}
		catch (final IOException | RuntimeException ex)
		{
			LlmRecordStore.warn("could not record the answer to " + pDir.getPath() + ": " + ex);
			return null;
		}
	}

	/** Readable and writable by the owner only (a no-op on a file system without POSIX permissions). */
	private static void ownerOnly(final File pFile) throws IOException
	{
		try
		{
			Files.setPosixFilePermissions(pFile.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
		}
		catch (final UnsupportedOperationException ex)
		{
			pFile.setReadable(false, false);
			pFile.setReadable(true, true);
			pFile.setWritable(false, false);
			pFile.setWritable(true, true);
		}
	}

	// ------------------------------------------------------------------------------------------------ one question (llm.ask)

	/** The call to the model that {@link #ask} makes on a miss. */
	interface AskCall
	{
		LlmContract.Answer call() throws ConnectorsContractClient.Refusal;
	}

	/** What {@link #ask} produced: the answer and, when it came from the store, the file it came from (null = the model answered). */
	static final class AskResult
	{
		final LlmContract.Answer answer;
		final String replayedFrom;

		AskResult(final LlmContract.Answer pAnswer, final String pReplayedFrom)
		{
			this.answer = pAnswer;
			this.replayedFrom = pReplayedFrom;
		}
	}

	/** One {@code llm.ask}: from the replay directory when it holds this request, else {@code pCall} (and recorded when a record directory is set). */
	static AskResult ask(final String pConnector, final String pSystem, final String pPrompt, final int pMaxTokens, final boolean pJson, final AskCall pCall)
		throws ConnectorsContractClient.Refusal
	{
		final File replay = LlmRecordStore.replayDir();
		final File record = LlmRecordStore.recordDir();
		if (replay == null && record == null)
		{
			return new AskResult(pCall.call(), null);
		}
		final ObjectNode request = LlmRecordStore.askRequest(pConnector, pSystem, pPrompt, pMaxTokens, pJson);
		final String digest = LlmRecordStore.digest(request);
		if (replay != null)
		{
			final Hit hit = LlmRecordStore.lookup(replay, digest, LlmRecordStore.KIND_ASK);
			if (hit != null)
			{
				return new AskResult(LlmRecordStore.askAnswerOf(hit.answer), hit.file);
			}
		}
		final LlmContract.Answer answer = pCall.call();
		if (record != null)
		{
			LlmRecordStore.write(record, digest, LlmRecordStore.KIND_ASK, answer.provider, answer.model, request, LlmRecordStore.answerJson(answer));
		}
		return new AskResult(answer, null);
	}

	private static void warn(final String pMessage)
	{
		try
		{
			LoggingCategory.getKernel().warn("[mdm-factory] LLM record/replay: " + pMessage);
		}
		catch (final Throwable ex)
		{
			System.err.println("[mdm-factory] LLM record/replay: " + pMessage);
		}
	}
}
