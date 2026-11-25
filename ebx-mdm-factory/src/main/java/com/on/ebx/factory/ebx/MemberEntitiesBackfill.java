package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.staging.StagingLlmPass;
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
 * The one-time {@code StagingEntity.memberEntities} backfill (`docs/MASTER-ZONE.md`, incident of
 * 2026-09-21), extracted from {@code StagingLlmSuggester} into a SHARED, self-contained pass so it can no
 * longer depend on "someone happened to re-run the language-model suggestion after the field was added".
 * <p>
 * <b>The incident this exists to never repeat</b>: {@code StagingEntity} id 4, {@code Company} (origin
 * {@code LLM}, status {@code APPLIED}), had {@code memberEntities} empty on all 5 of its rows although its
 * {@code rationale} still read {@code "Unifies Supplier, Account: ..."} - the structured field was added
 * 2026-09-20, after {@code Company} was proposed, and the only code that ever backfilled it was {@code
 * StagingLlmSuggester.suggest} (the paid, admin-only, language-model call), which nobody had reason to run
 * again on an already-accepted project. {@code master.MasterModel} trusts {@code memberEntities} alone (by
 * design - text is not structure), read no fallback, and promoted {@code Supplier}/{@code Account} to master
 * right alongside {@code Company} - the real, live consequence this class' callers exist to prevent.
 * <p>
 * <b>Called from every entry point that reads {@code StagingEntity} for a decision that depends on
 * membership</b> - {@code StagingSuggester.suggest} (reports a {@code memberEntitiesBackfilled} counter,
 * alongside its own other catch-up counters), {@code StagingLlmSuggester.suggest} (still the ONLY place new
 * canonical entities are proposed, and still needs a resolved member-name list even for a row this pass
 * cannot backfill - see {@link #run}'s own Javadoc for exactly what it returns), {@code StagingGenerator.generate},
 * {@code MasterGenerator.generate}, {@code MasterLoader.load}. Idempotent and cheap (no MCP call, no language
 * model): safe to call on every one of those, every time.
 * <p>
 * <b>Safety kept exactly as it was</b>: {@link StagingLlmPass#parseMembers} recovers a member-name list from
 * an old-style rationale, but a structured value is only ever PERSISTED when every parsed name resolves to a
 * real {@code RULE}-origin {@code StagingEntity} of the same project - a partial or bogus parse (a hand-typed
 * canonical entity, or one naming something that does not exist) is never written, and is not this class'
 * problem to flag; see {@code master.MembershipConsistencyCheck} for what happens when a row THIS backfill
 * could not fix looks structurally like an unresolved canonical unification anyway.
 */
public final class MemberEntitiesBackfill
{
	private MemberEntitiesBackfill()
	{
	}

	public static final class Result
	{
		/** How many {@code StagingEntity} rows had {@code memberEntities} written by this call. */
		public final int backfilled;
		public final List<String> entityNames;

		Result(final int pBackfilled, final List<String> pEntityNames)
		{
			this.backfilled = pBackfilled;
			this.entityNames = pEntityNames;
		}
	}

	private static final class Candidate
	{
		final String entityId;
		final String entityName;
		final List<String> memberIds;

		Candidate(final String pEntityId, final String pEntityName, final List<String> pMemberIds)
		{
			this.entityId = pEntityId;
			this.entityName = pEntityName;
			this.memberIds = pMemberIds;
		}
	}

	/**
	 * @return every {@code StagingEntity} row this call actually wrote {@code memberEntities} onto (never
	 * null, possibly empty when nothing needed it - the common case once a project is stable).
	 */
	public static Result run(final Session pSession, final Adaptation pFactory, final String pProjectId) throws OperationException
	{
		final List<Adaptation> rows = new ArrayList<Adaptation>();
		final Map<String, String> nameById = new HashMap<String, String>();
		final Map<String, String> ruleIdByName = new HashMap<String, String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				rows.add(row);
				final String id = row.getOccurrencePrimaryKey().format();
				final String name = row.getString(FactoryPaths.StagingEntity.NAME);
				nameById.put(id, name);
				if ("RULE".equals(row.getString(FactoryPaths.StagingEntity.ORIGIN)))
				{
					ruleIdByName.put(name, id);
				}
			}
		}
		finally
		{
			entityRows.close();
		}

		final List<Candidate> candidates = new ArrayList<Candidate>();
		for (final Adaptation row : rows)
		{
			if (!"LLM".equals(row.getString(FactoryPaths.StagingEntity.ORIGIN)))
			{
				continue;
			}
			final List<?> rawMembers = row.getList(FactoryPaths.StagingEntity.MEMBER_ENTITIES);
			if (rawMembers != null && !rawMembers.isEmpty())
			{
				continue; // already structured - nothing to backfill
			}
			final List<String> parsedNames = StagingLlmPass.parseMembers(row.getString(FactoryPaths.StagingEntity.RATIONALE));
			if (parsedNames.isEmpty())
			{
				continue; // not this pass' shape (a hand-typed canonical entity, or one with no rationale) - left alone
			}
			final List<String> resolvedIds = new ArrayList<String>();
			boolean allResolved = true;
			for (final String name : parsedNames)
			{
				final String id = ruleIdByName.get(name);
				if (id == null)
				{
					allResolved = false;
					break;
				}
				resolvedIds.add(id);
			}
			if (allResolved)
			{
				candidates.add(new Candidate(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.StagingEntity.NAME), resolvedIds));
			}
		}

		if (candidates.isEmpty())
		{
			return new Result(0, Collections.<String>emptyList());
		}

		final AdaptationTable entitiesTable = pFactory.getTable(FactoryPaths.STAGING_ENTITY);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Candidate candidate : candidates)
				{
					final Adaptation row = entitiesTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(candidate.entityId));
					if (row == null)
					{
						continue;
					}
					// ONLY memberEntities is set here - status, rationale and everything else are untouched
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(new ArrayList<String>(candidate.memberIds), FactoryPaths.StagingEntity.MEMBER_ENTITIES);
					pContext.doModifyContent(row, value);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.backfillMembers", "Backfilling memberEntities failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}

		final List<String> names = new ArrayList<String>();
		for (final Candidate candidate : candidates)
		{
			names.add(candidate.entityName);
		}
		return new Result(candidates.size(), names);
	}
}
