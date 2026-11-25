package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Audit F-35 (wave 5): "Change the key..." ({@link LandingKeyChange#change}) as a job of {@link FactoryJobQueue}, resource {@link FactoryJobs#LANDING}
 * (the resource every landing load and generation of the project claims, so a key change never overlaps a load of the same table). The row deletion was
 * already batched (wave 2: {@code VolumeBatchOps.deleteAllInBatches}, 1000 rows per transaction); what remained was that the whole sweep ran inside the
 * HTTP request - on a landing table of millions of rows that is minutes with the page frozen, and a click elsewhere abandoned it half-way. Now the wizard
 * only validates ({@link LandingKeyChange#refusal}, shown at once) and queues; the jobs banner follows it. Stop is honoured between two batches
 * ({@code Handle#cancelled}, also the job's check-in for the watchdog): the new key stays saved, the table keeps its status, the same button finishes it.
 */
public final class LandingKeyChangeRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "LANDING_KEY_CHANGE_RUN";

	private LandingKeyChangeRun()
	{
	}

	/** Pure: the columns CSV a request carries, split the way {@code LandingKeyChangeRules} expects (blank entries dropped). */
	public static List<String> splitColumns(final String pColumnsCsv)
	{
		final List<String> columns = new ArrayList<String>();
		if (pColumnsCsv != null)
		{
			for (final String column : pColumnsCsv.split(","))
			{
				final String trimmed = column.trim();
				if (!trimmed.isEmpty())
				{
					columns.add(trimmed);
				}
			}
		}
		return columns;
	}

	/** Pure: whether the stored params of a queued job are for this landing table. */
	static boolean paramsNameTable(final String pParamsJson, final String pTableId)
	{
		if (pParamsJson == null || pTableId == null || pTableId.isEmpty())
		{
			return false;
		}
		try
		{
			return pTableId.equals(LandingKeyChangeRun.MAPPER.readTree(pParamsJson).path("tableId").asText(""));
		}
		catch (final java.io.IOException ex)
		{
			return false;
		}
	}

	/** True when a key change of this landing table is already QUEUED or RUNNING: a second click is refused with a sentence, not queued behind it. */
	public static boolean isActive(final Adaptation pFactory, final String pProjectId, final String pTableId)
	{
		for (final Adaptation job : FactoryJobQueue.active(pFactory, pProjectId))
		{
			if (LandingKeyChangeRun.STEP.equals(job.getString(FactoryPaths.FactoryJob.KIND))
				&& LandingKeyChangeRun.paramsNameTable(job.getString(FactoryPaths.FactoryJob.PARAMS), pTableId))
			{
				return true;
			}
		}
		return false;
	}

	/** Queues the key change (resource LANDING) and returns at once. The caller validated with {@link LandingKeyChange#refusal} first. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode, final String pTableId,
		final List<String> pColumns) throws OperationException
	{
		final ObjectNode params = LandingKeyChangeRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("tableId", pTableId);
		final StringBuilder csv = new StringBuilder();
		for (final String column : pColumns)
		{
			csv.append(csv.length() == 0 ? "" : ",").append(column);
		}
		params.put("columns", csv.toString());
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, pProjectId, LandingKeyChangeRun.STEP, params);
		final ObjectNode out = LandingKeyChangeRun.MAPPER.createObjectNode();
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
			final LandingKeyChange.Result result = LandingKeyChange.change(pJobSession, pFactory, pParams.path("projectCode").asText(""), pParams.path("tableId").asText(""),
				LandingKeyChangeRun.splitColumns(pParams.path("columns").asText("")), new java.util.function.BooleanSupplier()
				{
					@Override
					public boolean getAsBoolean()
					{
						return pHandle.cancelled();
					}
				});
			if (!result.ok)
			{
				throw new FactoryJobQueue.ExpectedFailure(result.message);
			}
			return "Key changed: " + result.rowsDeleted + " landing row(s) deleted, the table's flow regenerated. Load the landing again to refill it.";
		}
	};
}
