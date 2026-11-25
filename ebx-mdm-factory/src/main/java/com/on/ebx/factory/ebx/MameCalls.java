package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.master.MameAnswer;
import com.on.ebx.factory.master.MameDataModelResolution;
import com.orchestranetworks.service.OperationException;

/**
 * The single choke point every match-merge tool call for a project's master table now goes through
 * (`docs/MATCHING.md` &sect;28) - two layers, both needed, neither alone would have been enough:
 * <ol>
 * <li><b>Always pass {@code dataModel}</b> - {@link #call} sets it, unconditionally, to {@link
 * MameDataModelResolution#publicationUrn(String)} before every call, replacing the older "omit by default,
 * resolve {@code dataModel} once from the first {@code get_matching_config} read, thread the (possibly
 * {@code null}) result through every following call of the pass" discipline {@code
 * ebx.MatchingConfigurator}/{@code ebx.MatchingPreviewPolicy}/{@code ebx.MatchingActivationGuard}/{@code
 * ebx.ProjectDeleter}/{@code ebx.MatchPlanLlmSuggester} each grew independently between 2026-09-21 and
 * 2026-09-23 (&sect;2.2/&sect;2.2.1/&sect;2.2.2/&sect;2.2.3/&sect;13.8/&sect;25/&sect;27).</li>
 * <li><b>Retry once on a genuine {@code AMBIGUOUS_TABLE}</b> - the safety net for the rare case where the
 * guessed publication URN still comes back ambiguous (most likely: the wrong project/table pairing, or a
 * table this factory does not actually own). {@link #decide} is the pure part (never touches {@code
 * McpClient}), delegating the actual candidate parsing to {@link MameDataModelResolution#resolve}, itself
 * unchanged - never duplicated here.</li>
 * </ol>
 * <p>
 * <b>The bug that made layer 1 necessary, measured live 2026-09-23 14:20 (`docs/MATCHING.md` &sect;28,
 * Proposal review, Contact, plan PROPOSED, table {@code /root/Contact}).</b> A "Simulate" click failed:
 * {@code apply_matching_config} answered {@code AMBIGUOUS_TABLE} (both candidates: the FastTrack module's own
 * {@code Companies.xsd} and {@code urn:ebx:publication:Companies_Master}) even though the SAME table's own
 * {@code get_matching_config}, called WITHOUT {@code dataModel} right before it in the exact same request,
 * answered {@code success:true} with the table's real configuration. Only ONE of the two candidate data
 * models actually carries a configuration for this table by this point in the session - the add-on stops
 * treating a READ as ambiguous once it can serve an answer from the one that has data, but a WRITE is not
 * disambiguated by that at all. So "resolve {@code dataModel} once from a read, reuse the (here: {@code null})
 * result for every later write" - the discipline every call site above independently implemented - is unsound
 * in general, not merely under-tested: it depends on the FIRST call in a pass always being a read, and on that
 * read's own ambiguity outcome staying representative of every later call, neither of which held here.
 * Separately, {@code get_matching_activation_status(table="/root/Company", dataModel=
 * "urn:ebx:publication:Companies_Master")} - {@code Company}, NOT an ambiguous table at all - answered its
 * ordinary {@code ENABLED}: passing the real publication URN is always accepted, whether the table is
 * ambiguous or not. Together, always sending it costs nothing on a non-ambiguous table and fixes the ambiguous
 * one.
 * <p>
 * <b>{@code McpClient.ToolResult}'s constructor is package-private in {@code com.on.ebx.agent}</b> (a different
 * package, a different jar - the `on-ebx-agent.jar` this module compiles against, never checked into this
 * repository) - this codebase's established rule (see {@code ebx.MatchingPreviewPolicyTest}'s own class
 * Javadoc) is to never construct a real one in a test. {@link #decide} is therefore the seam this class was
 * split around: it takes only the raw {@link JsonNode} answer, never a {@code ToolResult}, so {@code
 * MameCallsTest} can drive it directly against hand-built fixtures - only {@link #call} itself (the transport
 * part) is untested, like every other real {@code McpClient.callTool} caller in this package.
 */
final class MameCalls
{
	private MameCalls()
	{
	}

	/** What {@link #decide} concluded from one match-merge answer. */
	enum Outcome
	{
		/** Not an {@code AMBIGUOUS_TABLE} answer (a genuine success, or any other soft miss) - use it as it came back, no retry. */
		AS_IS,
		/** A genuine {@code AMBIGUOUS_TABLE} with exactly one candidate naming the caller's own publication - retry once with {@link Decision#dataModel}. */
		RETRY,
		/** {@code AMBIGUOUS_TABLE} with zero or several matching candidates (or an unparseable answer) - {@link MameDataModelResolution} itself refuses to guess. */
		REFUSE
	}

	/** Pure result of {@link #decide} - see {@link Outcome} for what each member means. */
	static final class Decision
	{
		final Outcome outcome;
		/** Non-null only for {@link Outcome#RETRY}. */
		final String dataModel;
		/** Non-null only for {@link Outcome#REFUSE}. */
		final String message;

		private Decision(final Outcome pOutcome, final String pDataModel, final String pMessage)
		{
			this.outcome = pOutcome;
			this.dataModel = pDataModel;
			this.message = pMessage;
		}

		static Decision asIs()
		{
			return new Decision(Outcome.AS_IS, null, null);
		}

		static Decision retry(final String pDataModel)
		{
			return new Decision(Outcome.RETRY, pDataModel, null);
		}

		static Decision refuse(final String pMessage)
		{
			return new Decision(Outcome.REFUSE, null, pMessage);
		}
	}

	/**
	 * Pure: {@code pResponse} is the raw {@code ToolResult.json} of a call that ALREADY carried {@code
	 * dataModel = MameDataModelResolution.publicationUrn(pPublicationName)} (layer 1) - this only ever reacts
	 * to a genuine {@code AMBIGUOUS_TABLE} soft miss (layer 2's safety net); every other answer (a real
	 * success, {@code CONFIG_NOT_FOUND}, {@code TABLE_NOT_FOUND}, {@code LOCATOR_NOT_FOUND}, {@code
	 * INVALID_PARAMS}, a transport failure represented as {@code null}...) is {@link Outcome#AS_IS} - the
	 * caller's own existing handling of that code is untouched by this class.
	 */
	static Decision decide(final JsonNode pResponse, final String pPublicationName)
	{
		if (pResponse == null || !"AMBIGUOUS_TABLE".equals(MameAnswer.code(pResponse)))
		{
			return Decision.asIs();
		}
		final MameDataModelResolution.Decision resolution = MameDataModelResolution.resolve(pResponse, pPublicationName);
		if (resolution.action == MameDataModelResolution.Action.USE_DATA_MODEL)
		{
			return Decision.retry(resolution.dataModel);
		}
		return Decision.refuse(resolution.message == null ? "AMBIGUOUS_TABLE with no resolvable candidate" : resolution.message);
	}

	/**
	 * Calls {@code pTool} on the match-merge server, {@code pArgs} carrying {@code table} (or {@code
	 * businessObject}) already - {@code dataModel} is set on {@code pArgs} UNCONDITIONALLY before the call
	 * (layer 1) whenever {@code pPublicationName} is non-null, overwriting whatever the caller may already
	 * have put there (every call site of this project passes the SAME project model name throughout one pass,
	 * so there is never a legitimate reason for a call-specific override). A genuine {@code AMBIGUOUS_TABLE}
	 * answer is retried once with {@link MameDataModelResolution}'s own resolved candidate (layer 2); anything
	 * else (a real success, an unrelated soft miss, a transport error) is returned to the caller exactly as it
	 * came back - this method never interprets a tool's own business codes ({@code CONFIG_NOT_FOUND}, {@code
	 * LOCATOR_NOT_FOUND}...), only the transport-and-ambiguity envelope around them.
	 * <p>
	 * Pass {@code pPublicationName == null} only for a call that genuinely has no owning project (none, in
	 * this codebase, as of this fix) - {@code dataModel} is then left untouched and no retry is ever attempted,
	 * the exact pre-fix behavior for such a call.
	 *
	 * @throws OperationException the retried call still cannot be told apart (zero or several matching
	 *             candidates) - named after {@code pTool} and {@code pArgs}' own {@code table}, carrying
	 *             {@link MameDataModelResolution}'s own refusal message verbatim.
	 */
	static McpClient.ToolResult call(final McpClient pClient, final String pTool, final ObjectNode pArgs, final String pPublicationName)
		throws McpException, OperationException
	{
		if (pPublicationName != null)
		{
			pArgs.put("dataModel", MameDataModelResolution.publicationUrn(pPublicationName));
		}
		final McpClient.ToolResult result = pClient.callTool(pTool, pArgs);
		if (pPublicationName == null)
		{
			return result;
		}
		final Decision decision = MameCalls.decide(result.json, pPublicationName);
		switch (decision.outcome)
		{
			case RETRY:
				pArgs.put("dataModel", decision.dataModel);
				return pClient.callTool(pTool, pArgs);
			case REFUSE:
				throw EngineRefusal.coded("external.mameModelOwner", pTool + " could not tell which data model owns " + pArgs.path("table").asText(pArgs.path("businessObject").asText("?")) + ": " + decision.message, pTool);
			default:
				return result;
		}
	}
}
