package com.on.ebx.factory.ebx;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.on.ebx.agent.McpException;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.deducedscan.ColumnMeasurer;
import com.on.ebx.factory.deducedscan.ListOperationPass;
import com.on.ebx.factory.deducedscan.NameKeyPass;
import com.on.ebx.factory.deducedscan.OperationCatalog;
import com.on.ebx.factory.deducedscan.TableProposal;
import com.on.ebx.factory.landing.KeyLlmPass;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Deduced-scan lot (2026-09-24): runs {@link ListOperationPass}/{@link NameKeyPass} through {@link
 * AgentRuntime} - same "local tool group only, no MCP server given to the tool set" shape {@code
 * StagingLlmSuggester} already uses for {@code StagingLlmPass}. Two small, single-purpose passes rather than
 * one multi-table session (unlike {@code StagingLlmPass.Session}'s own accumulate-everything shape): "How to
 * list" runs ONCE per connection (it may record several operations), "Name and key" runs ONCE PER candidate
 * table (its own sample/measurements only - keeps each call small and its own failure isolated to one table,
 * task brief's per-stage progress reporting wants exactly that granularity).
 */
public final class DeducedScanLlmSuggester
{
	private static final String PHASE_LIST = "DEDUCED_SCAN_LIST";
	private static final String PHASE_NAME = "DEDUCED_SCAN_NAME";

	private DeducedScanLlmSuggester()
	{
	}

	/** Never throws for "the model found nothing" (an empty list is a valid, meaningful answer - contract: "no list operation -&gt; enrichment"); throws only when the run itself could not be executed (no LLM connector configured, budget exhausted with an error, etc). */
	public static List<OperationCatalog.ListChoice> chooseListOperations(final Session pSession, final Adaptation pFactory, final String pProjectId,
		final String pConnectorId, final List<OperationCatalog.OperationInfo> pReadOnlyOperations) throws OperationException
	{
		final ListOperationPass.Session llmSession = new ListOperationPass.Session(pReadOnlyOperations);
		final ToolSet tools;
		try
		{
			tools = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), Collections.singletonMap("factory", llmSession.toolGroup()));
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.codedWithCause("external.agentTools", "Could not build the agent's tool set: " + ex.getMessage(), ex);
		}
		AgentRuntime.run(pSession, pFactory, pProjectId, DeducedScanLlmSuggester.PHASE_LIST, "Choose the read-only operation(s) that list records on connection " + pConnectorId,
			ListOperationPass.systemPrompt(), ListOperationPass.userPrompt(pConnectorId, pReadOnlyOperations), tools);
		return llmSession.chosen();
	}

	/** Null when the model never made a valid {@code propose_table} call (budget exhausted before it did, or every attempt was refused) - the caller then reports the table as not named rather than writing a half-built {@code Asset}. */
	public static TableProposal proposeTable(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pOperationName,
		final List<ColumnMeasurer.Measured> pMeasured) throws OperationException
	{
		final NameKeyPass.Session llmSession = new NameKeyPass.Session(pMeasured);
		final ToolSet tools;
		try
		{
			tools = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), Collections.singletonMap("factory", llmSession.toolGroup()));
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.codedWithCause("external.agentTools", "Could not build the agent's tool set: " + ex.getMessage(), ex);
		}
		// the example of each column is a real source value: it follows the project's effective data-policy level (the
		// instance cap applied), and a sensitive column never gives one. A project that cannot be read fails closed.
		final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId));
		final LlmDataPolicy policy = LlmDataPolicy.of(pFactory);
		final KeyLlmPass.Sharing sharing = project == null ? KeyLlmPass.Sharing.NONE : LlmDataPolicy.sharingOf(policy.levelFor(project));
		AgentRuntime.run(pSession, pFactory, pProjectId, DeducedScanLlmSuggester.PHASE_NAME, "Name the table read by operation " + pOperationName + " and propose its key",
			NameKeyPass.systemPrompt(), NameKeyPass.userPrompt(pOperationName, pMeasured, sharing, DeducedScanLlmSuggester.withheldColumns(policy, pMeasured)), tools);
		return llmSession.proposal();
	}

	/**
	 * The measured columns whose example must never reach the model: a name matching the policy's sensitive list, tested on the
	 * whole flattened name and on its last segment ({@code properties.bic} is the column {@code bic}), array indexes ignored.
	 */
	static Set<String> withheldColumns(final LlmDataPolicy pPolicy, final List<ColumnMeasurer.Measured> pMeasured)
	{
		final Set<String> withheld = new HashSet<String>();
		for (final ColumnMeasurer.Measured column : pMeasured)
		{
			final String whole = column.name;
			final String plain = whole.replaceAll("\\[\\d*\\]", "");
			final String last = plain.substring(plain.lastIndexOf('.') + 1);
			if (pPolicy.isSensitive(whole) || pPolicy.isSensitive(plain) || pPolicy.isSensitive(last))
			{
				withheld.add(whole);
			}
		}
		return withheld;
	}
}
