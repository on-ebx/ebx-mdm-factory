package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.staging.ChildKeysSweep;
import com.on.ebx.factory.staging.DerivationBackfill;
import com.on.ebx.factory.staging.DerivationStaleness;
import com.on.ebx.factory.staging.StagingModel;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The EBX-bound half of the backfill of a stored script derivation (lot T1, 2026-10-04; the why, the sentinel and the guard are in {@link
 * DerivationBackfill}): scans the staging table of the entity, and for each row that needs it writes the derived field so that the modify trigger
 * computes it. Runs at the end of a successful {@link DerivationApplier#apply} when the table already holds rows, and on demand ({@link #backfill},
 * {@code POST /projects/{code}/capabilities/{id}/backfill}).
 * <p>
 * Modelled on {@link ChildKeysPass}: ONE streaming read in table order, writes through the record the scan hands over (never a second lookup of the
 * key), batches of {@link ChildKeysPass#MIN_WRITE_BATCH} rows at least (or the factory's {@code transferBatchSize} when larger), every batch in its own
 * {@code Procedure} with an execution-information tag, the triggers left ACTIVE (never switched off: the whole point is that they run). Only the derived
 * field is written - {@code _sourceHash}, {@code _status} and {@code _loadId} stay as they are, so a row is not "changed" for the incremental
 * landing-to-staging transfer.
 * <p>
 * <b>Exclusion.</b> The pass claims the {@code STAGING} resource of the project ({@link FactoryJobs#claim}) for its duration, so it never overlaps a
 * staging load; when another run holds it the pass is SKIPPED with that message and the person runs it later. It is not a {@code FactoryJob} row of its
 * own: the job kinds are an XSD enumeration, and the pass is bounded to one table.
 * <p>
 * <b>Re-entry (fix/derivation-apply-backfill, 2026-10-17, lot G1).</b> A claim held by THE CALLING THREAD is not another run: the apply job ({@code
 * CAPABILITY_APPLY_STAGING}) and the staging load that auto-applies a derivation both hold the STAGING resource and run this pass on their own thread, and the pass used to
 * refuse itself ("Another run is using the staging of this project (CAPABILITY_APPLY_STAGING, started by admin...)", live on capability 226, 534 rows left empty). It now
 * re-enters such a claim, with the same rule as {@link EngineClaim#decide}, and the job's handle serves as its cancellation flag.
 * <p>
 * <b>Repair mode</b> ({@link #repairStale}, lot G1): the same writes restricted to the rows {@link DerivationStaleness} calls stale, run by a staging load for every applied derivation
 * so that a row skipped as "unchanged" is never left with a derived value that nothing will compute.
 * <p>
 * <b>Not measured live</b> (no instance in the lot): whether the {@code Adaptation} returned by {@code doModifyContent} already shows the value the
 * trigger wrote. If it did not, the guard would read the sentinel back although the triggers are live, and the pass would stop on its first row with
 * "trigger not live", nothing changed - the safe direction.
 */
public final class DerivationBackfillPass
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The resource kind shown in a busy message. */
	static final String KIND = "DERIVATION_BACKFILL";

	private DerivationBackfillPass()
	{
	}

	// ============================================================================================ entry points

	/**
	 * On demand: backfills the staging rows of an {@code APPLIED} {@code SCRIPT_DERIVATION}. Answers the pass' node ({@code status}: {@code DONE}, {@code
	 * EMPTY}, {@code SKIPPED}, {@code STOPPED}, {@code TRIGGER_NOT_LIVE}, {@code FAILED}, the counters, the duration); throws when the suggestion is not
	 * an applied derivation. Recorded as a {@code PipelineStep} of the {@code CAPABILITY_APPLY} kind (the step enumeration has no value of its own).
	 */
	public static ObjectNode backfill(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pSuggestionId) throws OperationException
	{
		final DerivationApplier.Prepared p = DerivationApplier.prepare(pFactory, pProjectCode, pSuggestionId);
		final Adaptation row = ProposalReviews.byId(pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION), pSuggestionId);
		if (row == null || !"APPLIED".equals(row.getString(FactoryPaths.CapabilitySuggestion.STATUS)))
		{
			throw EngineRefusal.coded("conflict.backfillNotApplied", "suggestion '" + pSuggestionId + "' is not applied: apply it first, the backfill recomputes rows through its triggers", pSuggestionId);
		}
		final String stepId = PipelineSteps.begin(pSession, pFactory, p.projectId, DerivationApplier.STEP_APPLY, pSuggestionId);
		final ObjectNode detail = DerivationBackfillPass.MAPPER.createObjectNode();
		final ObjectNode node = DerivationBackfillPass.claimAndRun(pSession, pFactory, p, pSuggestionId, null);
		detail.set("backfill", node);
		final String status = node.path("status").asText("?");
		final boolean ok = "DONE".equals(status) || "EMPTY".equals(status);
		PipelineSteps.end(pSession, pFactory, stepId, ok, "backfill of '" + p.newAttributeName + "' on " + p.entityName + ": " + status
			+ (node.has("summary") ? " - " + node.path("summary").asText() : node.has("reason") ? " - " + node.path("reason").asText() : ""), detail.toString());
		return node;
	}

	/** After an apply: the same pass, never throwing - a problem is a {@code FAILED} node, the apply stays successful (its triggers are live and recorded). */
	static ObjectNode runAfterApply(final Session pSession, final Adaptation pFactory, final DerivationApplier.Prepared pPrepared, final String pSuggestionId)
	{
		try
		{
			return DerivationBackfillPass.claimAndRun(pSession, pFactory, pPrepared, pSuggestionId, null);
		}
		catch (final RuntimeException ex)
		{
			final ObjectNode node = DerivationBackfillPass.MAPPER.createObjectNode();
			node.put("suggestion", pSuggestionId);
			node.put("status", "FAILED");
			node.put("reason", ErrorText.of(ex));
			return node;
		}
	}

	/**
	 * Lot G1 (2026-10-17, item 2): called by a staging load for an APPLIED derivation. Recomputes the rows {@link DerivationStaleness} finds stale - every row when nothing
	 * was written to the table since the apply, else the rows with an empty derived value and a filled input that were written before it. Answers the pass' node ({@code status}
	 * {@code CURRENT} when no row is stale, otherwise the backfill statuses, with {@code need}); never throws.
	 */
	static ObjectNode repairStale(final Session pSession, final Adaptation pFactory, final DerivationApplier.Prepared pPrepared, final String pSuggestionId, final Date pAppliedAt)
	{
		try
		{
			return DerivationBackfillPass.claimAndRun(pSession, pFactory, pPrepared, pSuggestionId, pAppliedAt);
		}
		catch (final RuntimeException ex)
		{
			final ObjectNode node = DerivationBackfillPass.MAPPER.createObjectNode();
			node.put("suggestion", pSuggestionId);
			node.put("status", "FAILED");
			node.put("reason", ErrorText.of(ex));
			return node;
		}
	}

	/**
	 * The sentence to keep on the applied row when the backfill did not complete as expected ({@code applyMessage}), or {@code null} when there is
	 * nothing to say (done without a limitation, or no row to backfill).
	 */
	static String noteFor(final ObjectNode pNode)
	{
		final String status = pNode.path("status").asText("");
		if ("DONE".equals(status))
		{
			final long limited = pNode.path("skippedNullNonString").asLong(0);
			return limited > 0 ? "Applied. " + limited + " existing row(s) with an empty value could not be recomputed by a write (the value is not text, or too short to take the "
				+ "marker): they are computed the next time their source changes." : null;
		}
		if ("EMPTY".equals(status))
		{
			return null;
		}
		return "Applied, but the rows already in the staging table were not recomputed (" + status.toLowerCase(Locale.ROOT) + ": " + pNode.path("reason").asText("") + "). Run POST /projects/{code}/capabilities/"
			+ pNode.path("suggestion").asText("{id}") + "/backfill.";
	}

	// ====================================================================================================== run

	/** Pure (lot G1): what the pass does about the STAGING claim {@code pHeld} (null when free) on thread {@code pCurrent} - re-enter the caller's own, claim a free one, refuse another run's. */
	static EngineClaim.Decision claimDecision(final FactoryJobs.Handle pHeld, final Thread pCurrent)
	{
		return EngineClaim.decide(pHeld, pCurrent);
	}

	private static ObjectNode claimAndRun(final Session pSession, final Adaptation pFactory, final DerivationApplier.Prepared pPrepared, final String pSuggestionId,
		final Date pRepairAppliedAt)
	{
		final ObjectNode node = DerivationBackfillPass.MAPPER.createObjectNode();
		node.put("suggestion", pSuggestionId);
		node.put("entity", pPrepared.entityName);
		node.put("attribute", pPrepared.newAttributeName);
		// lot G1: a claim of THIS thread (the apply job, the load that auto-applies) is re-entered, never refused
		final FactoryJobs.Handle held = FactoryJobs.live(pPrepared.projectId, FactoryJobs.STAGING);
		final EngineClaim.Decision decision = DerivationBackfillPass.claimDecision(held, Thread.currentThread());
		final FactoryJobs.Handle handle = decision == EngineClaim.Decision.REENTER ? held
			: decision == EngineClaim.Decision.ACQUIRE ? FactoryJobs.claim(pPrepared.projectId, FactoryJobs.STAGING, DerivationBackfillPass.KIND, Actor.current(pSession).userId) : null;
		final boolean ownClaim = decision == EngineClaim.Decision.ACQUIRE && handle != null;
		if (handle == null)
		{
			final FactoryJobs.Handle live = FactoryJobs.live(pPrepared.projectId, FactoryJobs.STAGING);
			node.put("status", "SKIPPED");
			node.put("reason", live == null ? "a run of this project has just started" : FactoryJobs.busyMessage(live));
			return node;
		}
		node.put("reentered", decision == EngineClaim.Decision.REENTER);
		try
		{
			DerivationBackfillPass.execute(pSession, pFactory, pPrepared, handle, node, pRepairAppliedAt);
		}
		catch (final DerivationBackfill.TriggerNotLive ex)
		{
			node.put("status", "TRIGGER_NOT_LIVE");
			node.put("reason", ex.getMessage());
		}
		catch (final Exception ex)
		{
			node.put("status", "FAILED");
			node.put("reason", DerivationBackfillPass.shorten(ex.getMessage(), 500) + " (rows already recomputed are kept; running the backfill again recomputes the rest)");
		}
		finally
		{
			if (ownClaim)
			{
				FactoryJobs.release(handle);
			}
		}
		return node;
	}

	private static void execute(final Session pSession, final Adaptation pFactory, final DerivationApplier.Prepared pPrepared, final FactoryJobs.Handle pHandle,
		final ObjectNode pNode, final Date pRepairAppliedAt) throws Exception
	{
		final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pPrepared.projectId));
		final String dataspaceKey = project == null ? null : project.getString(FactoryPaths.Project.STAGING_DATASPACE);
		final String datasetName = project == null ? null : project.getString(FactoryPaths.Project.STAGING_DATASET);
		if (dataspaceKey == null || dataspaceKey.trim().isEmpty() || datasetName == null || datasetName.trim().isEmpty())
		{
			pNode.put("status", "SKIPPED");
			pNode.put("reason", "the project has no staging zone yet");
			return;
		}
		final AdaptationHome home = pFactory.getHome().getRepository().lookupHome(HomeKey.forBranchName(dataspaceKey));
		final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(datasetName));
		if (dataset == null)
		{
			pNode.put("status", "SKIPPED");
			pNode.put("reason", "staging dataset " + dataspaceKey + "/" + datasetName + " not found");
			return;
		}
		final AdaptationTable table;
		try
		{
			table = dataset.getTable(Path.parse(StagingModel.tablePath(pPrepared.entityName)));
		}
		catch (final RuntimeException ex)
		{
			pNode.put("status", "SKIPPED");
			pNode.put("reason", "table " + StagingModel.tablePath(pPrepared.entityName) + " is not in the published staging model: regenerate the staging model");
			return;
		}
		final Path derivedPath = Path.parse("./" + pPrepared.newAttributeName);
		final List<Path> sourcePaths = new ArrayList<Path>();
		final List<String> missing = new ArrayList<String>();
		if (table.getTableOccurrenceRootNode().getNode(derivedPath) == null)
		{
			missing.add(pPrepared.newAttributeName);
		}
		for (final String source : pPrepared.boundAttributes)
		{
			final Path path = Path.parse("./" + source);
			sourcePaths.add(path);
			if (table.getTableOccurrenceRootNode().getNode(path) == null)
			{
				missing.add(source);
			}
		}
		if (!missing.isEmpty())
		{
			pNode.put("status", "SKIPPED");
			pNode.put("reason", "field(s) not in the published staging model yet: " + missing + " - regenerate the staging model");
			return;
		}
		final long rowCount = DerivationBackfillPass.size(table);
		pNode.put("rows", rowCount);
		if (rowCount == 0)
		{
			pNode.put("status", "EMPTY");
			pNode.put("reason", "the staging table holds no row yet: the triggers compute the value as rows are loaded");
			return;
		}

		// lot G1: repair mode - which rows are stale (DerivationStaleness), and the predicate that finds the candidates without reading the whole table
		DerivationStaleness.Need need = null;
		String candidatePredicate = null;
		if (pRepairAppliedAt != null)
		{
			need = DerivationStaleness.decide(rowCount, pRepairAppliedAt, table.getLastModificationDate(), false);
			pNode.put("need", need.name());
			if (need == DerivationStaleness.Need.NONE)
			{
				pNode.put("status", "CURRENT");
				pNode.put("reason", "no row to recompute");
				return;
			}
			if (need == DerivationStaleness.Need.EMPTY_DERIVED)
			{
				candidatePredicate = DerivationStaleness.emptyDerivedPredicate(pPrepared.newAttributeName, pPrepared.boundAttributes);
				if (candidatePredicate == null)
				{
					pNode.put("status", "SKIPPED");
					pNode.put("reason", "the empty values of " + pPrepared.newAttributeName + " cannot be searched: " + pPrepared.boundAttributes);
					return;
				}
			}
		}
		final DerivationStaleness.Need repairNeed = need;
		final String predicate = candidatePredicate;

		// a text field takes the marker unless it is shorter than the marker (a write over the length is refused by EBX)
		final boolean derivedIsText = "string".equals(pPrepared.derivedScriptType)
			&& (pPrepared.derivedMaxLength <= 0 || pPrepared.derivedMaxLength >= DerivationBackfill.SENTINEL.length());
		final int batchSize = Math.max(StagingLoader.batchSize(pFactory), ChildKeysPass.MIN_WRITE_BATCH);
		final String tag = "mdm-factory:derivation-backfill:" + pPrepared.entityName + "." + pPrepared.newAttributeName;

		final DerivationBackfill.Rows rows = new DerivationBackfill.Rows()
		{
			@Override
			public void scan(final DerivationBackfill.RowVisitor pVisitor) throws Exception
			{
				// ONE streaming read, in table order; the result is frozen when it is created, so the writes made from the visitor, each in its own Procedure,
				// neither disturb it nor are seen by it (ChildKeysPass uses the same shape)
				final RequestResult result = table.createRequestResult(predicate);
				try
				{
					for (Adaptation row = result.nextAdaptation(); row != null; row = result.nextAdaptation())
					{
						boolean anySource = false;
						for (final Path path : sourcePaths)
						{
							if (DerivationBackfillPass.filled(row.get(path)))
							{
								anySource = true;
								break;
							}
						}
						// lot G1: of the candidates (empty derived value, filled input), only a row written before the apply is stale - a row the library legitimately answers
						// nothing for was written by an earlier repair and is newer than the apply, so it is not rewritten at every load
						if (repairNeed == DerivationStaleness.Need.EMPTY_DERIVED && !DerivationStaleness.rowStale(anySource, DerivationBackfillPass.filled(row.get(derivedPath)),
							row.getTimeOfLastModification(), pRepairAppliedAt))
						{
							continue;
						}
						if (!pVisitor.row(row.getOccurrencePrimaryKey().format(), anySource, DerivationBackfillPass.filled(row.get(derivedPath)), row))
						{
							return;
						}
					}
				}
				finally
				{
					result.close();
				}
			}
		};
		final DerivationBackfill.Writer writer = new DerivationBackfill.Writer()
		{
			@Override
			public void writeFirst(final DerivationBackfill.Update pUpdate) throws Exception
			{
				final boolean[] notLive = new boolean[1];
				final ProcedureResult result = ProgrammaticService.createForSession(pSession, dataset.getHome()).execute(new Procedure()
				{
					@Override
					public void execute(final ProcedureContext pContext) throws Exception
					{
						pContext.setExecutionInformation(tag + ":first-row");
						final Adaptation row = (Adaptation) pUpdate.handle;
						final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
						value.setValue(pUpdate.value, derivedPath);
						final Adaptation after = pContext.doModifyContent(row, value);
						// the guard: the trigger ran inside doModifyContent, so the row read back must hold a computed value, never the marker
						if (derivedIsText && after != null && DerivationBackfill.SENTINEL.equals(after.get(derivedPath)))
						{
							notLive[0] = true;
							throw new IllegalStateException("the marker is still in " + pUpdate.key);
						}
					}
				});
				if (notLive[0])
				{
					throw new DerivationBackfill.TriggerNotLive("trigger not live on " + StagingModel.tablePath(pPrepared.entityName) + " (the value written to " + pUpdate.key
						+ " was not replaced by the trigger), nothing changed - publish the model again or check the triggers of the table");
				}
				if (result.hasFailed())
				{
					throw new IllegalStateException(DerivationBackfillPass.shorten(result.getExceptionFullMessage(Locale.ENGLISH), 500));
				}
			}

			@Override
			public void writeBatch(final List<DerivationBackfill.Update> pBatch) throws Exception
			{
				final ProcedureResult result = ProgrammaticService.createForSession(pSession, dataset.getHome()).execute(new Procedure()
				{
					@Override
					public void execute(final ProcedureContext pContext) throws Exception
					{
						pContext.setExecutionInformation(tag);
						// only doModifyContent on the records in hand: no read of the table, so the index is not refreshed between two rows of the batch
						for (final DerivationBackfill.Update update : pBatch)
						{
							final Adaptation row = (Adaptation) update.handle;
							final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
							value.setValue(update.value, derivedPath);
							pContext.doModifyContent(row, value);
						}
					}
				});
				if (result.hasFailed())
				{
					throw new IllegalStateException(DerivationBackfillPass.shorten(result.getExceptionFullMessage(Locale.ENGLISH), 500));
				}
			}
		};
		final ChildKeysSweep.Cancellation cancellation = new ChildKeysSweep.Cancellation()
		{
			@Override
			public boolean cancelled()
			{
				return pHandle != null && pHandle.cancelled();
			}
		};

		final DerivationBackfill.Result result = DerivationBackfill.run(rows, writer, derivedIsText, batchSize, cancellation);
		pNode.put("scanned", result.scanned);
		pNode.put("recomputed", result.recomputed);
		pNode.put("skipped", result.skipped);
		pNode.put("skippedNullNonString", result.skippedNullNonString);
		pNode.put("writeBatches", result.writeBatches);
		pNode.put("writeBatchSize", batchSize);
		pNode.put("durationMs", result.durationMs);
		pNode.put("markerUsed", derivedIsText);
		if (result.guardRow != null)
		{
			pNode.put("guardRow", result.guardRow);
		}
		pNode.put("summary", result.summary());
		pNode.put("status", result.cancelled ? "STOPPED" : "DONE");
		if (result.cancelled)
		{
			pNode.put("reason", "the pass was stopped; running the backfill again recomputes the rest");
		}
	}

	// ==================================================================================================== helpers

	/** A value counts as filled when it is not null and not an empty string (EBX stores an empty string as null; this is only defensive). */
	static boolean filled(final Object pValue)
	{
		return pValue != null && !(pValue instanceof String && ((String) pValue).isEmpty());
	}

	private static long size(final AdaptationTable pTable)
	{
		final RequestResult rows = pTable.createRequestResult(null);
		try
		{
			return rows.getSize();
		}
		finally
		{
			rows.close();
		}
	}

	private static String shorten(final String pText, final int pMax)
	{
		if (pText == null)
		{
			return "";
		}
		return pText.length() <= pMax ? pText : pText.substring(0, pMax - 3) + "...";
	}
}
