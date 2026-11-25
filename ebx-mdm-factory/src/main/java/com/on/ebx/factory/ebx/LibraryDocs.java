package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.factory.capability.FunctionDoc;
import com.on.ebx.factory.capability.LibraryDocCache;
import com.on.ebx.factory.capability.ScriptInventory;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.Session;

/**
 * What a published library function says about itself, for the Mapping matrix's computed fields
 * (fix/matrix-computed-field, docs/MAPPING-MATRIX.md sec 14): the doc comment right above {@code export function
 * <name>(} in the library source, read through the SAME scripting MCP server and the SAME {@code get_script}
 * call ({@code scriptIdePath}, {@code includeSource}) the capability pass ({@code CapabilityLlmSuggester
 * #buildScriptInventory}) already uses to choose the function - nothing is stored, nothing is guessed.
 * <p>
 * The source is cached in memory ({@link LibraryDocCache}: per library, revalidated by {@code revisionId}
 * through a metadata-only call after {@link #FRESH_MILLIS}) so a render makes at most one call per library and
 * most renders none. The call runs with a short read timeout ({@link #READ_TIMEOUT_MS}): a screen must not hang
 * on a scripting server that is slow or disabled - the failure is shown as "could not be read" and retried later.
 */
public final class LibraryDocs
{
	/** A cached library is trusted this long without any call. */
	static final long FRESH_MILLIS = 60000L;
	/** A render never waits longer than this for the scripting server. */
	private static final int READ_TIMEOUT_MS = 10000;
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** What {@link #of} answers: the function's doc, or why there is none ({@code problem}, plain text, never empty when {@code doc} is null). */
	public static final class Result
	{
		/** Null when the library could not be read or the function carries no doc comment. */
		public final FunctionDoc doc;
		public final String problem;

		Result(final FunctionDoc pDoc, final String pProblem)
		{
			this.doc = pDoc;
			this.problem = pProblem == null ? "" : pProblem;
		}
	}

	private LibraryDocs()
	{
	}

	/** One cache for the JVM; the fetcher reads the session and the factory of the CALL (the cache itself keeps no session). */
	private static final ThreadLocal<Object[]> CALLER = new ThreadLocal<Object[]>();
	private static final LibraryDocCache CACHE = new LibraryDocCache(new LibraryDocCache.Fetcher()
	{
		@Override
		public LibraryDocCache.Fetched fetch(final String pLibraryIdePath, final boolean pIncludeSource) throws Exception
		{
			final Object[] caller = LibraryDocs.CALLER.get();
			final McpClient client = McpServers.client((Session) caller[0], (Adaptation) caller[1], McpServers.SCRIPTING, LibraryDocs.READ_TIMEOUT_MS);
			final ObjectNode args = LibraryDocs.MAPPER.createObjectNode();
			args.put("scriptIdePath", pLibraryIdePath);
			args.put("includeSource", pIncludeSource);
			final McpClient.ToolResult result = client.callTool("get_script", args);
			if (result.error || result.json == null)
			{
				throw new IllegalStateException("get_script(" + pLibraryIdePath + ") failed: " + result.text);
			}
			final String revisionId = result.json.path("data").path("revisionId").asText("");
			if (!pIncludeSource)
			{
				return new LibraryDocCache.Fetched(revisionId, null);
			}
			return new LibraryDocCache.Fetched(revisionId, ScriptInventory.parseScript(result.json).source);
		}
	}, LibraryDocs.FRESH_MILLIS);

	/**
	 * The doc comment of {@code pFunctionName} in the library {@code pLibraryIdePath} (e.g. {@code
	 * library/Fasttrack/lib/phone}). Never throws; a blank argument, an unreadable library or a function without a
	 * doc comment all come back as a null {@link Result#doc} with a {@link Result#problem} saying which.
	 */
	public static Result of(final Session pSession, final Adaptation pFactory, final String pLibraryIdePath, final String pFunctionName)
	{
		if (pLibraryIdePath == null || pLibraryIdePath.isEmpty() || pFunctionName == null || pFunctionName.isEmpty())
		{
			return new Result(null, "the suggestion names no library function");
		}
		final LibraryDocCache.Lookup lookup;
		LibraryDocs.CALLER.set(new Object[] { pSession, pFactory });
		try
		{
			lookup = LibraryDocs.CACHE.lookup(pLibraryIdePath, System.currentTimeMillis());
		}
		finally
		{
			LibraryDocs.CALLER.remove();
		}
		if (lookup.source == null)
		{
			return new Result(null, lookup.problem.isEmpty() ? "the library source could not be read" : lookup.problem);
		}
		final FunctionDoc doc = FunctionDoc.parse(lookup.source, pFunctionName);
		if (doc == null || !doc.hasAnyText())
		{
			return new Result(null, "the library has no doc comment above " + pFunctionName);
		}
		return new Result(doc, "");
	}
}
