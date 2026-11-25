package com.on.ebx.factory.ebx;

import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.factory.rejects.RejectCauses;
import com.on.ebx.factory.rejects.RejectSql;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.query.Query;
import com.orchestranetworks.query.QueryResult;
import com.orchestranetworks.query.Tuple;
import com.orchestranetworks.service.Session;

/**
 * Staging load and rejects lot (2026-10-17), item 3: why the rows held back in STAGING were set aside, per entity and for the whole project - ONE aggregate SQL query on
 * {@code TransferReject} ({@link RejectSql#causes}), run as the caller (EBX's own permissions apply), never a read of every reject. This is what the wizard's step 4 uses to word
 * "N rows set aside" truthfully instead of blaming a configured rule: {@code RejectCauseReader.staging(...).project().sentence(locale)}.
 * <p>
 * The population is the one the load grids count as "held back": unresolved STAGING rejects, acknowledged ones included. A failed read answers an empty result (the screen then
 * says nothing about the cause rather than a wrong thing), never an exception.
 */
public final class RejectCauseReader
{
	private RejectCauseReader()
	{
	}

	/** The causes of a project's held-back staging rows: per entity name and altogether. */
	public static final class Causes
	{
		private final Map<String, RejectCauses> byEntity = new LinkedHashMap<String, RejectCauses>();
		private final RejectCauses project = new RejectCauses();

		/** Every entity's causes added together. */
		public RejectCauses project()
		{
			return this.project;
		}

		/** The causes of one entity (an empty {@link RejectCauses} when it has none). */
		public RejectCauses entity(final String pEntityName)
		{
			final RejectCauses causes = this.byEntity.get(pEntityName);
			return causes == null ? new RejectCauses() : causes;
		}

		public Map<String, RejectCauses> byEntity()
		{
			return this.byEntity;
		}

		void add(final String pEntityName, final String pCategory, final long pCount)
		{
			RejectCauses causes = this.byEntity.get(pEntityName);
			if (causes == null)
			{
				causes = new RejectCauses();
				this.byEntity.put(pEntityName, causes);
			}
			causes.add(pCategory, pCount);
			this.project.add(pCategory, pCount);
		}
	}

	/** The unresolved STAGING rejects of {@code pProjectCode} by entity and cause. */
	public static Causes staging(final Session pSession, final Adaptation pFactory, final String pProjectCode)
	{
		return RejectCauseReader.read(pSession, pFactory, pProjectCode, "STAGING");
	}

	/**
	 * EBX refusal classes lot H4 (2026-10-17): the unresolved MASTER rejects of {@code pProjectCode} by entity and cause - the same query, the same categories and the same
	 * sentence as {@link #staging}, so the wizard's master step and the Cockpit word the rows held back from master the way step 4 words the ones held back from staging.
	 */
	public static Causes master(final Session pSession, final Adaptation pFactory, final String pProjectCode)
	{
		return RejectCauseReader.read(pSession, pFactory, pProjectCode, "MASTER");
	}

	private static Causes read(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pStage)
	{
		final Causes out = new Causes();
		try
		{
			final Map<String, String> names = RejectReview.entityNamesOf(pFactory, pProjectCode);
			final Query<Tuple> query = pFactory.createQuery(RejectSql.causes(pProjectCode, pStage));
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				for (final Tuple tuple : result)
				{
					final Object entityId = tuple.get("ENT");
					final Object count = tuple.get("N");
					if (entityId == null || !(count instanceof Number))
					{
						continue;
					}
					final String id = String.valueOf(entityId);
					out.add(names.containsKey(id) ? names.get(id) : id, String.valueOf(tuple.get("CAT")), ((Number) count).longValue());
				}
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex) // includes QueryPermissionException: a screen that cannot read the causes says nothing about them
		{
			return new Causes();
		}
		return out;
	}
}
