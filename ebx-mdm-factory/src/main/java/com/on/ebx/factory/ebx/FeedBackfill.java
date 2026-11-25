package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.staging.StagingRules;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Target-mapping lot 1 (docs/TARGET-MAPPING.md sec 6): {@code AttributeMapping.feed} is a new field - every
 * mapping row written before this lot has it empty. For each such row, finds (or creates, once per (entity,
 * landing table) pair) the root {@code TransferFeed} it implicitly always belonged to - exactly the grouping
 * {@code transfer.TransferPlan} itself falls back to for an unset feed (see its own Javadoc) - and sets
 * {@code feed} on the mapping. Same shape and same calling convention as {@link MemberEntitiesBackfill}:
 * a self-contained, idempotent, cheap pass (no MCP call, no language model) called from every entry point
 * that could otherwise see a stale/incomplete feed reference - {@code StagingSuggester.suggest} today (reports
 * a {@code feedsBackfilled} counter, alongside its own other catch-up counters).
 * <p>
 * <b>Idempotent by construction</b>: a mapping already carrying a {@code feed} is never touched (found and
 * skipped, per {@link #run}'s own read), and a second run therefore backfills nothing - it re-finds the SAME
 * (entity, landing table) feed for every mapping, never creates a duplicate, because the create-or-find
 * lookup is checked against BOTH the rows already on EBX before this run started AND every root feed this
 * SAME run already created (a project with several still-unbackfilled mappings on the same landing table
 * only ever creates ONE new {@code TransferFeed} row for them, not one per mapping).
 * <p>
 * <b>The backfilled feed's status</b> follows {@link StagingRules#technicalKeyBackfillStatus} - the exact
 * same "cascade to ACCEPTED/REJECTED when the entity already says so, else PROPOSED" rule the technical-key
 * backfill already uses, since a feed with no decision of its own recorded yet (a pre-lot-1 row) should read
 * exactly as if it had followed its entity from the start (docs/TARGET-MAPPING.md sec 4.1: "a PROPOSED feed
 * follows its entity's decision exactly like a mapping follows its attribute").
 */
public final class FeedBackfill
{
	private FeedBackfill()
	{
	}

	public static final class Result
	{
		/** How many {@code AttributeMapping} rows had {@code feed} written by this call. */
		public final int mappingsBackfilled;
		/** How many NEW {@code TransferFeed} rows this call created (0 when every mapping's feed already existed on EBX). */
		public final int feedsCreated;

		Result(final int pMappingsBackfilled, final int pFeedsCreated)
		{
			this.mappingsBackfilled = pMappingsBackfilled;
			this.feedsCreated = pFeedsCreated;
		}
	}

	/** One {@code AttributeMapping} row with no {@code feed} yet, resolved down to what {@link #run} needs to find-or-create its root feed. */
	private static final class PendingMapping
	{
		final String mappingId;
		final String entityId;
		final String entityStatus;
		final String landingTableId;

		PendingMapping(final String pMappingId, final String pEntityId, final String pEntityStatus, final String pLandingTableId)
		{
			this.mappingId = pMappingId;
			this.entityId = pEntityId;
			this.entityStatus = pEntityStatus;
			this.landingTableId = pLandingTableId;
		}
	}

	public static Result run(final Session pSession, final Adaptation pFactory, final String pProjectId) throws OperationException
	{
		// 1. every StagingEntity of the project, with its own current status
		final Map<String, String> entityStatusById = new HashMap<String, String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				entityStatusById.put(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.StagingEntity.STATUS));
			}
		}
		finally
		{
			entityRows.close();
		}
		if (entityStatusById.isEmpty())
		{
			return new Result(0, 0);
		}

		// 2. an existing root TransferFeed per (entity, landingTable) pair, so a mapping whose feed is already
		// backfilled (by an earlier run, or by StagingSuggester writing it directly for a brand-new entity)
		// never gets a second, duplicate feed
		final Map<String, String> existingFeedIdByKey = new HashMap<String, String>();
		for (final String entityId : entityStatusById.keySet())
		{
			final RequestResult feedRows = pFactory.getTable(FactoryPaths.TRANSFER_FEED).createRequestResult(
				"./entity=" + CatalogReader.quote(entityId) + " and osd:is-null(./parentFeed)");
			try
			{
				for (Adaptation row = feedRows.nextAdaptation(); row != null; row = feedRows.nextAdaptation())
				{
					final String landingTableId = row.getString(FactoryPaths.TransferFeed.LANDING_TABLE);
					if (landingTableId != null)
					{
						existingFeedIdByKey.put(entityId + "#" + landingTableId, row.getOccurrencePrimaryKey().format());
					}
				}
			}
			finally
			{
				feedRows.close();
			}
		}

		// 3. every AttributeMapping row of the project with no feed yet, resolved to its entity/landing table
		final List<PendingMapping> pending = new ArrayList<PendingMapping>();
		final Map<String, String> landingTableIdByColumnId = new HashMap<String, String>();
		for (final Map.Entry<String, String> entity : entityStatusById.entrySet())
		{
			final String entityId = entity.getKey();
			final RequestResult attributeRows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(entityId));
			try
			{
				for (Adaptation attributeRow = attributeRows.nextAdaptation(); attributeRow != null; attributeRow = attributeRows.nextAdaptation())
				{
					final String attributeId = attributeRow.getOccurrencePrimaryKey().format();
					final RequestResult mappingRows = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING)
						.createRequestResult("./attribute=" + CatalogReader.quote(attributeId));
					try
					{
						for (Adaptation mappingRow = mappingRows.nextAdaptation(); mappingRow != null; mappingRow = mappingRows.nextAdaptation())
						{
							if (mappingRow.getString(FactoryPaths.AttributeMapping.FEED) != null)
							{
								continue; // already has a feed - nothing to backfill
							}
							final String landingColumnId = mappingRow.getString(FactoryPaths.AttributeMapping.LANDING_COLUMN);
							final String landingTableId = FeedBackfill.landingTableIdOf(pFactory, landingColumnId, landingTableIdByColumnId);
							if (landingTableId == null)
							{
								continue; // a dangling landingColumn - nothing this pass can resolve
							}
							pending.add(new PendingMapping(mappingRow.getOccurrencePrimaryKey().format(), entityId, entity.getValue(), landingTableId));
						}
					}
					finally
					{
						mappingRows.close();
					}
				}
			}
			finally
			{
				attributeRows.close();
			}
		}

		if (pending.isEmpty())
		{
			return new Result(0, 0);
		}

		final AdaptationTable feedsTable = pFactory.getTable(FactoryPaths.TRANSFER_FEED);
		final AdaptationTable mappingsTable = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING);
		final int[] counts = new int[2]; // mappingsBackfilled, feedsCreated
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Map<String, String> feedIdByKey = new HashMap<String, String>(existingFeedIdByKey);
				for (final PendingMapping mapping : pending)
				{
					final String key = mapping.entityId + "#" + mapping.landingTableId;
					String feedId = feedIdByKey.get(key);
					if (feedId == null)
					{
						final ValueContextForUpdate feedValue = pContext.getContextForNewOccurrence(feedsTable);
						feedValue.setValue(mapping.entityId, FactoryPaths.TransferFeed.ENTITY);
						feedValue.setValue(mapping.landingTableId, FactoryPaths.TransferFeed.LANDING_TABLE);
						feedValue.setValue("RULE", FactoryPaths.TransferFeed.ORIGIN);
						feedValue.setValue(StagingRules.technicalKeyBackfillStatus(mapping.entityStatus), FactoryPaths.TransferFeed.STATUS);
						feedValue.setValue("Backfilled from mapping " + mapping.mappingId + " (target-mapping lot 1).", FactoryPaths.TransferFeed.RATIONALE);
						feedId = pContext.doCreateOccurrence(feedValue, feedsTable).getOccurrencePrimaryKey().format();
						feedIdByKey.put(key, feedId);
						counts[1]++;
					}
					final Adaptation mappingRow = mappingsTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(mapping.mappingId));
					if (mappingRow == null)
					{
						continue;
					}
					final ValueContextForUpdate mappingValue = pContext.getContext(mappingRow.getAdaptationName());
					mappingValue.setValue(feedId, FactoryPaths.AttributeMapping.FEED);
					pContext.doModifyContent(mappingRow, mappingValue);
					counts[0]++;
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.backfillFeed", "Backfilling AttributeMapping.feed failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		return new Result(counts[0], counts[1]);
	}

	private static String landingTableIdOf(final Adaptation pFactory, final String pLandingColumnId, final Map<String, String> pCache)
	{
		if (pLandingColumnId == null)
		{
			return null;
		}
		if (pCache.containsKey(pLandingColumnId))
		{
			return pCache.get(pLandingColumnId);
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.LANDING_COLUMN).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingColumnId));
		final String landingTableId = row == null ? null : row.getString(FactoryPaths.LandingColumn.LANDING_TABLE);
		pCache.put(pLandingColumnId, landingTableId);
		return landingTableId;
	}
}
