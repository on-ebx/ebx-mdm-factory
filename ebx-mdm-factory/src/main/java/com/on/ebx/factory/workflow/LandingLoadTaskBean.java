package com.on.ebx.factory.workflow;

import com.on.ebx.factory.ebx.EngineRefusal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.ebx.FactoryInstaller;
import com.on.ebx.factory.ebx.LandingFlows;
import com.on.ebx.factory.ebx.LandingLoadPool;
import com.on.ebx.factory.landing.LandingParallelism;
import com.on.ebx.factory.ebx.McpServers;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.workflow.ScriptTaskBean;
import com.orchestranetworks.workflow.ScriptTaskBeanContext;

/**
 * A workflow "script task" bean that loads the landing zone of an MDM Factory project - the in-process
 * equivalent of {@code POST /ebx-mdm-factory/rest/v1/sources/{id}/landing/load}
 * ({@code FactoryResource#loadLanding}), extended to run every {@link com.on.ebx.factory.core.FactoryPaths
 * ProjectSource} of the project in one step when {@link #projectSource} is left blank. Same discipline as
 * {@link MatchingRunTaskBean} (see its own class Javadoc): no HTTP loopback, no permission check here - a
 * workflow step's effective caller identity is governed by EBX's own workflow permissions, not by a check
 * duplicated inside the bean.
 * <p>
 * <b>Preflight, same as the REST route.</b> {@code FactoryResource#loadLanding} runs {@link
 * McpServers#preflight} against {@link McpServers#CONNECTORS} before doing anything, so a disabled MCP
 * server or a revoked agent account is reported as a clean refusal instead of a half-finished load; this
 * bean reproduces that check before calling {@link LandingFlows#load} for any source.
 * <p>
 * <b>Every source, one aggregate result.</b> When {@link #projectSource} is blank, every {@code
 * ProjectSource} row of the project is loaded in turn ({@link LandingFlows#projectSourceIds}, the same
 * project/{@code ProjectSource} lookup {@code ebx.MatchingConfigurator#readTrustedSources} and {@code
 * ebx.StagingSuggester#suggest} already use, made public there for this bean instead of a fourth inline
 * copy). {@link #resultJson} carries one aggregate object ({@code project}, {@code sources[]} - one {@link
 * LandingFlows#load} result per source, {@code failed}, {@code status}); {@link #failed} carries the same
 * total as plain text, for a workflow condition node to compare against {@code "0"} without parsing JSON.
 * <p>
 * <b>The outputs are set before the step is failed.</b> A load that reports at least one failed table (any
 * source's own {@code failed} count &gt; 0, or a source's own {@code status} is {@code FAILED}) still writes
 * {@link #resultJson}/{@link #failed} - so the data context carries the real numbers even when this method
 * then throws - and only afterwards throws an {@link OperationException} naming the failing source(s), so
 * the workflow step itself lands {@code inError} rather than silently continuing to the staging load. An
 * {@link OperationException} {@link LandingFlows#load} itself throws for one source (no landing table has a
 * flow yet, the project source does not exist) is let through as-is and stops the whole step immediately,
 * same as every other loader bean in this package - it is not caught and turned into a per-source failure
 * entry.
 */
public final class LandingLoadTaskBean extends ScriptTaskBean
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private String projectCode;
	private String projectSource;
	private String resultJson;
	private String failed;

	public String getProjectCode()
	{
		return this.projectCode;
	}

	public void setProjectCode(final String pProjectCode)
	{
		this.projectCode = pProjectCode;
	}

	/** Optional: a single {@code ProjectSource.id}. Blank/{@code null} loads every source of {@link #projectCode}. */
	public String getProjectSource()
	{
		return this.projectSource;
	}

	public void setProjectSource(final String pProjectSource)
	{
		this.projectSource = pProjectSource;
	}

	/** The aggregate result, as JSON text - see this class' own Javadoc for its shape. */
	public String getResultJson()
	{
		return this.resultJson;
	}

	public void setResultJson(final String pResultJson)
	{
		this.resultJson = pResultJson;
	}

	/** The aggregate {@code failed} count of {@link #resultJson}, as plain text (a workflow condition compares strings). */
	public String getFailed()
	{
		return this.failed;
	}

	public void setFailed(final String pFailed)
	{
		this.failed = pFailed;
	}

	/**
	 * Pure, unit-testable: the {@code failed} count of one {@link LandingFlows#load} result, or {@code 0}
	 * when the field is missing/not a number (defensive only - {@link LandingFlows#load} always sets it).
	 */
	static int failedCount(final JsonNode pSourceResult)
	{
		return pSourceResult == null ? 0 : pSourceResult.path("failed").asInt(0);
	}

	/**
	 * Pure, unit-testable: true when {@code pSourceResult} itself reports failure, either through its own
	 * {@code failed} count or its {@code status} field - mirrors {@link LandingFlows#load}'s own {@code
	 * failed == 0 ? "DONE" : "FAILED"} convention, checked both ways in case a future caller of this helper
	 * hands it a result whose two fields disagree (never the case for {@link LandingFlows#load} itself).
	 */
	static boolean sourceFailed(final JsonNode pSourceResult)
	{
		return LandingLoadTaskBean.failedCount(pSourceResult) > 0 || "FAILED".equals(pSourceResult == null ? null : pSourceResult.path("status").asText(""));
	}

	/**
	 * Lot L2 (2026-10-12): loads {@code pSourceIds}, one {@link LandingFlows#load} result per source, in the order given. With
	 * {@code mdm.factory.landing.parallelSources} at 1, or a single source, they load one after the other on this thread and an {@link OperationException}
	 * stops the step at once, as before. Above 1 they load on a {@link LandingLoadPool} of at most that many threads ({@code mdm-factory-landing-N}); a source
	 * whose load throws an {@link OperationException} keeps the not-yet-started ones from starting, the loads already running finish (nothing here can stop
	 * them), and the first such exception in the sources' order is thrown once they have all ended - the same exception the sequential loop would have thrown
	 * for that source, so the step still lands {@code inError} on the same cause.
	 */
	static List<ObjectNode> loadAll(final Session pSession, final Adaptation pDataset, final List<String> pSourceIds) throws OperationException
	{
		final List<ObjectNode> out = new ArrayList<ObjectNode>();
		final int bound = LandingParallelism.bound();
		if (!LandingParallelism.usesPool(pSourceIds.size(), bound))
		{
			for (final String sourceId : pSourceIds)
			{
				out.add(LandingFlows.load(pSession, pDataset, sourceId));
			}
			return out;
		}
		final AtomicBoolean aborted = new AtomicBoolean();
		final List<Future<ObjectNode>> futures = new ArrayList<Future<ObjectNode>>();
		final LandingLoadPool pool = LandingLoadPool.open(Math.min(bound, pSourceIds.size()));
		try
		{
			for (final String sourceId : pSourceIds)
			{
				futures.add(pool.submit(new Callable<ObjectNode>()
				{
					@Override
					public ObjectNode call() throws Exception
					{
						if (aborted.get())
						{
							return null;
						}
						try
						{
							return LandingFlows.load(pSession, pDataset, sourceId);
						}
						catch (final OperationException | RuntimeException ex)
						{
							aborted.set(true);
							throw ex;
						}
					}
				}));
			}
			OperationException firstRefusal = null;
			Throwable firstOther = null;
			for (final Future<ObjectNode> future : futures)
			{
				try
				{
					out.add(future.get());
				}
				catch (final InterruptedException ex)
				{
					aborted.set(true);
					Thread.currentThread().interrupt();
					throw EngineRefusal.coded("unexpected.landingLoadFailed", "LandingLoadTaskBean: the step was interrupted while loading", 0, pSourceIds);
				}
				catch (final ExecutionException ex)
				{
					out.add(null);
					final Throwable cause = ex.getCause();
					if (cause instanceof OperationException)
					{
						firstRefusal = firstRefusal == null ? (OperationException) cause : firstRefusal;
					}
					else
					{
						firstOther = firstOther == null ? cause : firstOther;
					}
				}
			}
			if (firstRefusal != null)
			{
				throw firstRefusal;
			}
			if (firstOther instanceof RuntimeException)
			{
				throw (RuntimeException) firstOther;
			}
			if (firstOther instanceof Error)
			{
				throw (Error) firstOther;
			}
			return out;
		}
		finally
		{
			pool.close();
		}
	}

	@Override
	public void executeScript(final ScriptTaskBeanContext pContext) throws OperationException
	{
		// pipeline-audit lot: the launcher must hold the pipeline role, and the steps are recorded as theirs
		PipelineLauncher.run(pContext, "LandingLoadTaskBean", com.on.ebx.factory.ebx.FactoryJobs.LANDING, this.projectCode, () -> {
			this.executeAuthorized(pContext);
			return null;
		});
	}

	private void executeAuthorized(final ScriptTaskBeanContext pContext) throws OperationException
	{
		if (this.projectCode == null || this.projectCode.trim().isEmpty())
		{
			throw EngineRefusal.coded("invalid.taskNoProject", "LandingLoadTaskBean: 'projectCode' is required", "LandingLoadTaskBean");
		}
		final Repository repository = pContext.getRepository();
		final Session session = pContext.getSession();
		FactoryInstaller.ensure(repository, session);
		final Adaptation dataset = FactoryInstaller.dataset(repository);
		if (dataset == null)
		{
			throw EngineRefusal.coded("missing.taskNoDataset", "LandingLoadTaskBean: the MDM Factory dataset is not installed", "LandingLoadTaskBean");
		}

		final ObjectNode preflight = McpServers.preflight(session, dataset, McpServers.CONNECTORS);
		if (!preflight.path("ready").asBoolean(false))
		{
			throw EngineRefusal.coded("external.mcpNotUsable", preflight.path("summary").asText("MCP server(s) not usable before the landing load ran."));
		}

		final List<String> sourceIds = this.projectSource == null || this.projectSource.trim().isEmpty()
			? LandingFlows.projectSourceIds(dataset, this.projectCode)
			: Collections.singletonList(this.projectSource.trim());
		if (sourceIds.isEmpty())
		{
			throw EngineRefusal.coded("missing.noSourceToLoad", "LandingLoadTaskBean: project " + this.projectCode + " has no project source to load", this.projectCode);
		}

		final ObjectNode out = LandingLoadTaskBean.MAPPER.createObjectNode();
		out.put("project", this.projectCode);
		final ArrayNode sources = out.putArray("sources");
		int totalFailed = 0;
		final List<String> failedSourceIds = new ArrayList<String>();
		final List<ObjectNode> results = LandingLoadTaskBean.loadAll(session, dataset, sourceIds);
		for (int i = 0; i < sourceIds.size(); i++)
		{
			final String sourceId = sourceIds.get(i);
			final ObjectNode sourceResult = results.get(i);
			sources.add(sourceResult);
			totalFailed += LandingLoadTaskBean.failedCount(sourceResult);
			if (LandingLoadTaskBean.sourceFailed(sourceResult))
			{
				failedSourceIds.add(sourceId);
			}
		}
		out.put("failed", totalFailed);
		out.put("status", totalFailed == 0 ? "DONE" : "FAILED");

		// outputs are set BEFORE a possible throw below, so the data context still carries the real numbers
		// when the step lands inError - see this class' own Javadoc
		this.resultJson = out.toString();
		this.failed = String.valueOf(totalFailed);

		if (!failedSourceIds.isEmpty())
		{
			throw EngineRefusal.coded("unexpected.landingLoadFailed", "LandingLoadTaskBean: " + totalFailed + " landing table(s) failed to load for project source(s) " + failedSourceIds, totalFailed, failedSourceIds);
		}
	}
}
