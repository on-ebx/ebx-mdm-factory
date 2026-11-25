package com.on.ebx.factory.ebx;

import com.on.ebx.factory.wizard.EngineMessageCodec;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.on.ebx.factory.core.FactoryPaths;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Volume-operations lot 8a (2026-09-28, task 2): {@link ProjectDeleter#delete} as a WHOLE-PROJECT job - same
 * shape as {@link StepResetRun}, see that class' own Javadoc for the reasoning ({@link FactoryJobs#claimAll},
 * {@link ProjectDeleter#deleteHoldingProject} called directly rather than through {@link
 * FactoryJobQueue#exclusively}). {@link ProjectDeleter#plan} is untouched and stays synchronous.
 */
public final class ProjectDeleteRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "PROJECT_DELETE_RUN";

	private ProjectDeleteRun()
	{
	}

	/** Queues the deletion (whole project) and returns at once. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Set<String> pOptions, final String pConfirm)
		throws OperationException
	{
		final Adaptation project = ProjectDeleteRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = ProjectDeleteRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		final ArrayNode options = params.putArray("options");
		if (pOptions != null)
		{
			for (final String option : pOptions)
			{
				options.add(option);
			}
		}
		params.put("confirm", pConfirm == null ? "" : pConfirm);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), ProjectDeleteRun.STEP, params);
		final ObjectNode out = ProjectDeleteRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The job's work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final Set<String> options = new LinkedHashSet<String>();
			for (final JsonNode option : pParams.path("options"))
			{
				options.add(option.asText());
			}
			final String confirm = pParams.path("confirm").asText("");
			final ObjectNode result = ProjectDeleter.deleteHoldingProject(pJobSession, pFactory, projectCode, options, confirm.isEmpty() ? null : confirm);
			// volume-operations lot 8b (2026-09-28, task 3): this message is ALL a caller can read back once the
			// job ends - unlike StepResetRun, a successful delete removes the project's own PipelineStep rows
			// along with everything else (ProjectDeleter.deleteFactoryRows' own per-project table sweep), so
			// there is no durable, query-able-after-the-fact place left to keep the full generated[]/outcome
			// detail this method used to return synchronously (see ProjectDeleteRun's own class Javadoc). Kept
			// as a plain, human-readable sentence rather than embedded JSON, since it is also what the generic
			// jobs banner shows verbatim as a job's own "stepMessage".
			return "Project " + projectCode + " deleted (" + ProjectDeleteRun.totalRows(result.path("rowsDeleted")) + " row(s) across "
				+ result.path("rowsDeleted").size() + " table(s))." + ProjectDeleteRun.keptNote(result.path("generated")) + ProjectDeleteRun.matchingNote(result.path("generated"))
				+ ProjectDeleteRun.leftNote(result.path("matchingConfigRowsLeft"));
		}
	};

	/**
	 * Lot C (2026-10-17): the objects a ticked option left in place because something else still uses them ({@code KEPT_IN_USE}: a connection another project's
	 * application or a mapping still runs on, a catalogue source a data product uses) - the one outcome a person must hear about although nothing failed.
	 * {@code ""} when none; otherwise {@code " Kept: <kind> <name> (<why>); ..."}. Pure; the full per-item answer is the synchronous route's.
	 */
	static String keptNote(final JsonNode pGenerated)
	{
		final StringBuilder sb = new StringBuilder();
		if (pGenerated != null && pGenerated.isArray())
		{
			for (final JsonNode item : pGenerated)
			{
				if (GeneratedObjectDeleter.RESULT_KEPT_IN_USE.equals(item.path("outcome").asText("")))
				{
					sb.append(sb.length() == 0 ? " Kept: " : "; ").append(item.path("kind").asText("")).append(' ').append(item.path("name").asText(""));
					final String message = item.path("message").asText("");
					if (!message.isEmpty())
					{
						sb.append(" (").append(message).append(')');
					}
				}
			}
		}
		return sb.toString();
	}

	/**
	 * Lot M18 (second round, 2026-10-18): the outcome of EACH matching item, in the job's message - the 15:56 deletions ended DONE with the main table's configuration still in the
	 * add-on's dataset and nothing in the job said why (the per-item answer lives only in the synchronous route's JSON). {@code " Matching: /root/Adresse DELETED; /root/Personne
	 * KEPT_BUSINESS_OBJECT (<add-on answer, clipped>)"}; the add-on's answer is quoted for everything that is not a plain DELETED / SKIPPED_ABSENT / NOT_SELECTED. {@code ""} when the
	 * plan had no matching item. Pure.
	 */
	static String matchingNote(final JsonNode pGenerated)
	{
		final StringBuilder sb = new StringBuilder();
		if (pGenerated != null && pGenerated.isArray())
		{
			for (final JsonNode item : pGenerated)
			{
				final String kind = item.path("kind").asText("");
				if (!"MATCHING_CONFIG".equals(kind) && !"MATCHING_BUSINESS_OBJECT".equals(kind) && !"DISTRIBUTION_TRIGGERS".equals(kind))
				{
					continue;
				}
				final String outcome = item.path("outcome").asText("");
				sb.append(sb.length() == 0 ? " Matching: " : "; ").append("MATCHING_BUSINESS_OBJECT".equals(kind) ? "business object " : "DISTRIBUTION_TRIGGERS".equals(kind) ? "distribution triggers of " : "").append(item.path("name").asText("")).append(' ').append(outcome);
				if (!"DELETED".equals(outcome) && !"SKIPPED_ABSENT".equals(outcome) && !"NOT_SELECTED".equals(outcome))
				{
					final String why = item.path("answer").asText(item.path("message").asText(""));
					if (!why.isEmpty())
					{
						sb.append(" (").append(why.length() > 220 ? why.substring(0, 220) + "..." : why).append(')');
					}
				}
			}
		}
		return sb.toString();
	}

	/** Lot M18: {@code " Add-on dataset still holds N business-object and M table configuration row(s)."} when the read-back found rows; {@code ""} otherwise. Pure. */
	static String leftNote(final JsonNode pLeft)
	{
		if (pLeft == null || !pLeft.isObject())
		{
			return "";
		}
		final int businessObjects = pLeft.path("businessObjectConfigurations").asInt(0);
		final int tableConfigs = pLeft.path("tableConfigurations").asInt(0);
		return businessObjects + tableConfigs == 0 ? "" : " The add-on's dataset still holds " + businessObjects + " business-object and " + tableConfigs + " table configuration row(s) of the master publication.";
	}

	/** Pure, unit-testable: sums an object node's own numeric field values (a {@code rowsDeleted} shape, table name to count) - {@code 0} for null/non-numeric/empty. */
	static long totalRows(final JsonNode pRowsDeleted)
	{
		long total = 0L;
		if (pRowsDeleted != null && pRowsDeleted.isObject())
		{
			final java.util.Iterator<String> names = pRowsDeleted.fieldNames();
			while (names.hasNext())
			{
				total += pRowsDeleted.path(names.next()).asLong(0L);
			}
		}
		return total;
	}

	/**
	 * Volume-operations lot 8b (2026-09-28, task 3): current state of the job {@code pJobId} - looked up by its
	 * OWN primary key, independent of the project (unlike {@code StagingLoadRun#status}/{@code
	 * StepResetRun#status}, which key off the project). Deliberate: a SUCCESSFUL delete removes the {@code
	 * Project} row itself, so "is the project still there" cannot be the lookup key; the {@code FactoryJob} row
	 * is untouched by {@link ProjectDeleter#deleteFactoryRows} (it only ever iterates {@code MdmFactory}'s own
	 * per-project tables, never {@code FactoryJob}) and survives regardless of the outcome - the same primary-
	 * key lookup {@link FactoryJobQueue#cancel} already does.
	 */
	public static ObjectNode status(final Adaptation pFactory, final String pJobId)
	{
		final ObjectNode out = ProjectDeleteRun.MAPPER.createObjectNode();
		final Adaptation job = pFactory.getTable(FactoryPaths.FACTORY_JOB).lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(pJobId));
		if (job == null)
		{
			out.put("exists", false);
			return out;
		}
		final String status = job.getString(FactoryPaths.FactoryJob.STATUS);
		out.put("exists", true);
		out.put("status", status);
		out.put("running", FactoryJobQueue.QUEUED.equals(status) || FactoryJobQueue.RUNNING.equals(status));
		final String message = job.getString(FactoryPaths.FactoryJob.MESSAGE);
		out.put("message", message == null ? "" : EngineMessageCodec.decode(message).raw); // wave 10: never the code marker
		return out;
	}

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}
}
