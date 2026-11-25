package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.master.CrossSystemFieldGuard;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.QualityRuleCheck;
import com.on.ebx.factory.quality.ValidityAggregator;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.query.Query;
import com.orchestranetworks.query.QueryResult;
import com.orchestranetworks.query.Tuple;
import com.orchestranetworks.service.Session;

/**
 * The two live-SQL measurements {@link CrossSystemFieldGuard} needs, in ONE place - package-private,
 * package {@code com.on.ebx.factory.ebx}, so both {@code MatchPlanLlmSuggester} (the language-model route)
 * and {@code MatchPlanSuggester} (the deterministic route) call the SAME statements instead of each keeping
 * its own copy. Coordinator review, 2026-09-21 (`feature/match-plan-cross-system-guard`, second pass): the
 * first pass of this branch duplicated {@code crossSystemSharedCount} into {@code MatchPlanSuggester}
 * verbatim, on the (then-correct) belief that touching {@code MatchPlanLlmSuggester.java} at all was
 * off-limits because of a concurrent, unrelated change on that file - lifted the same day once that other
 * branch merged. With the concurrency constraint gone, the duplication itself is the worse choice: two
 * independent copies of a live SQL statement drift apart the first time either one is fixed. This class is
 * the one home for both; the duplicate that used to live in {@code MatchPlanSuggester.java} is deleted.
 * <p>
 * <b>{@link #sharedCount} is the ORIGINAL statement, unchanged, not a rewrite.</b> Its exact text (the
 * derived-table/{@code GROUP BY}/{@code HAVING COUNT(DISTINCT ...) > 1} shape, {@code IS NOT NULL}, every
 * identifier double-quoted) is the one the coordinator verified live against the real demo master table,
 * 2026-09-21 - see the method's own Javadoc for the exact statement and the live-verified numbers. Moving it
 * here changes nothing about what is sent to the database.
 * <p>
 * <b>{@link #perSystemFilledCounts} is the "one more measurement" the guard needs</b> (`PLAN.md`'s
 * 2026-09-21 "Rapprochement" entry): per source system, how many rows have one field filled at all - see
 * that method's own Javadoc for the exact statement.
 * <p>
 * <b>Identifier quoting</b> - both an entity name and a field name are concatenated into the generated SQL
 * text. Both go through {@link #isQuotable}, the SAME check {@code MatchPlanLlmSuggester}'s own original
 * method already used before this extraction: only {@code [A-Za-z_][A-Za-z0-9_]*} is accepted, so a name
 * carrying a double quote (or any other character that could otherwise close the quoting early and inject
 * arbitrary SQL) is REFUSED outright - the measurement degrades to "not measured" (null), never a broken or
 * unsafe statement. Every real entity/attribute name this factory itself ever generates already matches
 * this pattern; the check exists as the last line of defence for a name that reaches here some other way -
 * an entity/field name proposed upstream by a language model, for instance, is never assumed safe just
 * because an earlier layer looked at it. Proven by {@code CrossSystemMeasuresTest}.
 */
final class CrossSystemMeasures
{
	private CrossSystemMeasures()
	{
	}

	/**
	 * How many DISTINCT non-null values of {@code pFieldName} are shared by at least two different {@code
	 * sourceSystem}s on the master table (Java query API - {@code Adaptation.createQuery(String)}/{@code
	 * Query<Tuple>}/{@code QueryResult<Tuple>}, the same shape {@code ebx.RelationChecker#runCount} already
	 * established for this codebase's other live-SQL measurement).
	 * <p>
	 * <b>A {@code GROUP BY ... HAVING} derived table, NOT a self-join</b> - coordinator review, 2026-09-21,
	 * blocking change before merge, `docs/MATCHING.md` &sect;4.2.2: a self-join
	 * ({@code FROM T a, T b WHERE a.f = b.f AND a.sourceSystem <> b.sourceSystem}) is on the order of
	 * rows²/distinct-value-count - harmless on a 107-row demo table, catastrophic on this project's real
	 * tables (hundreds of millions of rows, `CLAUDE.md`: "volume is never marginal in this project"). The
	 * statement below is linear in row count instead:
	 * <pre>{@code
	 * SELECT COUNT(*) FROM (
	 *   SELECT a."<field>" AS v FROM "<Entity>" a WHERE a."<field>" IS NOT NULL
	 *   GROUP BY a."<field>" HAVING COUNT(DISTINCT a."sourceSystem") > 1
	 * ) t
	 * }</pre>
	 * <b>Verified live by the coordinator, 2026-09-21, against the demo master table</b>: this exact
	 * statement shape (derived table in {@code FROM}, {@code GROUP BY} + {@code HAVING COUNT(DISTINCT ...) >
	 * 1}, {@code IS NOT NULL}, every identifier double-quoted), run as {@code SELECT COUNT(*) FROM (SELECT
	 * c."name" AS v FROM "Company" c WHERE c."name" IS NOT NULL GROUP BY c."name" HAVING COUNT(DISTINCT
	 * c."sourceSystem") > 1) t}, is accepted by the EBX SQL engine and answered {@code 4} on {@code
	 * Company.name} (the coordinator's own self-join form was ALSO accepted on the same table and answered 0
	 * for both {@code vatNo} and {@code supplierId} - the self-join was never rejected outright, only
	 * unacceptably expensive at real volume). On ANY failure (a statement the engine refuses, a transport
	 * error, anything) this still returns null ("not measured") rather than throwing - {@link
	 * CrossSystemFieldGuard} never refuses on a null count, only records "not measured".
	 * <p>
	 * Should ONLY ever be called lazily, for a field a caller has already decided it needs to know about
	 * (never for every candidate attribute up front) - see {@code MatchPlanLlmPass.CrossSystemUsageLookup}'s
	 * own Javadoc for why that matters at this project's real table sizes.
	 */
	static Integer sharedCount(final Session pSession, final Adaptation pMasterDataset, final String pEntityName, final String pFieldName)
	{
		if (!CrossSystemMeasures.isQuotable(pEntityName) || !CrossSystemMeasures.isQuotable(pFieldName))
		{
			return null;
		}
		// MasterModel.tablePath is always "/root/<entityName>" (that class' own Javadoc) - the table name IS the entity name, no parsing needed
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String field = CrossSystemMeasures.quoteIdentifier(pFieldName);
		final String sourceSystem = CrossSystemMeasures.quoteIdentifier("sourceSystem");
		final String sql = "SELECT COUNT(*) AS SHARED FROM (SELECT a." + field + " AS v FROM " + table + " a WHERE a." + field + " IS NOT NULL GROUP BY a." + field
			+ " HAVING COUNT(DISTINCT a." + sourceSystem + ") > 1) t";
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				if (!it.hasNext())
				{
					return null;
				}
				final Object value = it.next().get("SHARED");
				return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
			}
			finally
			{
				result.close();
			}
		}
		// deliberately broad, unlike ebx.RelationChecker's own two named query exceptions: this exact statement has
		// not been run live BY THIS CODEBASE (only the coordinator's own hand-run probe, quoted above) - "not
		// measured" is the documented fallback for ANY failure here, never a route failure, so nothing narrower
		// than RuntimeException is safe to rely on yet
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * The WITHIN-system mirror of {@link #sharedCount} (2026-09-22, "child entities matched too" lot,
	 * `docs/MATCHING.md`): how many DISTINCT non-null values of {@code pFieldName} are repeated by two or more
	 * records of the SAME source system, on the master table - the structural evidence a single-source-system
	 * entity's own matching plan needs, since {@link #sharedCount}/{@link #perSystemFilledCounts} are both
	 * meaningless questions once only one source system is present (CLAUDE.md &sect;8: single-source matching is
	 * not a lesser case, it needs its own evidence, not a bare "does not apply").
	 * <p>
	 * Same derived-table/{@code GROUP BY}/{@code HAVING COUNT(*) > 1} shape as {@link #sharedCount}, grouped by
	 * BOTH the field and {@code sourceSystem} instead of the field alone - this is the shape that correctly
	 * captures "the same value repeated within one system" (as opposed to grouping by the field alone and
	 * comparing distinct {@code sourceSystem} values, which is what {@link #sharedCount} does for the CROSS-system
	 * question):
	 * <pre>{@code
	 * SELECT COUNT(*) FROM (
	 *   SELECT a."<field>" AS v, a."sourceSystem" AS s FROM "<Entity>" a WHERE a."<field>" IS NOT NULL
	 *   GROUP BY a."<field>", a."sourceSystem" HAVING COUNT(*) > 1
	 * ) t
	 * }</pre>
	 * <b>Never run live this session</b> (no EBX/live MCP access) - built by direct analogy to {@link #sharedCount}'s
	 * own statement, which WAS verified live by the coordinator, 2026-09-21 (that method's own Javadoc); this
	 * method changes only the {@code GROUP BY}/{@code HAVING} clause shape, not the surrounding statement
	 * structure, identifier quoting, session wiring, or failure handling - all identical to {@link #sharedCount}.
	 * Returns null ("not measured") on ANY failure, exactly like {@link #sharedCount}/{@link #perSystemFilledCounts} -
	 * {@link CrossSystemFieldGuard} never refuses on a null count here either, only records "not measured".
	 * <p>
	 * Should ONLY ever be called lazily, for a field a caller has already determined is on a single-source-system
	 * table (never for every candidate attribute up front, and never when the table genuinely has two or more
	 * source systems, where {@link #sharedCount} is the relevant question instead) - see {@code
	 * MatchPlanLlmPass.WithinSystemDuplicateLookup}'s own Javadoc.
	 */
	static Integer withinSystemDuplicateCount(final Session pSession, final Adaptation pMasterDataset, final String pEntityName, final String pFieldName)
	{
		if (!CrossSystemMeasures.isQuotable(pEntityName) || !CrossSystemMeasures.isQuotable(pFieldName))
		{
			return null;
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String field = CrossSystemMeasures.quoteIdentifier(pFieldName);
		final String sourceSystem = CrossSystemMeasures.quoteIdentifier("sourceSystem");
		final String sql = "SELECT COUNT(*) AS DUPES FROM (SELECT a." + field + " AS v, a." + sourceSystem + " AS s FROM " + table + " a WHERE a." + field
			+ " IS NOT NULL GROUP BY a." + field + ", a." + sourceSystem + " HAVING COUNT(*) > 1) t";
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				if (!it.hasNext())
				{
					return null;
				}
				final Object value = it.next().get("DUPES");
				return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
			}
			finally
			{
				result.close();
			}
		}
		// deliberately broad, same reasoning as sharedCount's own catch: this exact statement has never been run
		// live BY THIS CODEBASE this session - "not measured" is the documented fallback for ANY failure here
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * <b>Coordinator fix, 2026-09-22 18:20 (measured live on {@code Companies_Master/Master/"Company"}) -
	 * this is a RENAME of a method that measured the wrong thing under the name {@code divergingAfterFoldCount}.
	 * </b> Grouping by an ATTRIBUTE's OWN folded key (e.g. {@code cityKey}) and counting DISTINCT RAW values
	 * (e.g. {@code city}) inside each group answers 32 (of 33) on the real data - but that is COSMETIC
	 * variation (the same folded identity, e.g. {@code "Paris"} vs {@code "PARIS"}), not a real disagreement:
	 * folding two spellings to the same key and then complaining they spell differently is circular. This
	 * method keeps that ORIGINAL statement (still a legitimate, useful number - "how much of the raw text
	 * would collapse to nothing once cleaning normalises case/accents/spacing") under an HONEST name; {@link
	 * #divergingGroupsCount} is the real "does the value genuinely differ" question, grouped by the entity's
	 * own IDENTITY key instead.
	 * <pre>{@code
	 * SELECT COUNT(*) FROM (
	 *   SELECT a."<foldedField>" AS k FROM "<Entity>" a WHERE a."<foldedField>" IS NOT NULL
	 *   GROUP BY a."<foldedField>" HAVING COUNT(DISTINCT a."sourceSystem") > 1 AND COUNT(DISTINCT a."<rawField>") > 1
	 * ) t
	 * }</pre>
	 * The denominator (how many {@code pFoldedFieldName} groups are shared across systems at all) is {@link
	 * #sharedCount} called with the folded field name as its own {@code pFieldName} - reused, never duplicated.
	 * Never run live this session under ITS OWN name (the coordinator ran the SQL shape directly, not through
	 * this Java method) - {@link #cosmeticVariantGroupsSql} is unit-tested for its exact text in {@code
	 * CrossSystemMeasuresTest}. Returns null ("not measured") on any failure, same discipline as every other
	 * method here.
	 */
	static Integer cosmeticVariantGroupsCount(final Session pSession, final Adaptation pMasterDataset, final String pEntityName, final String pFoldedFieldName,
		final String pRawFieldName)
	{
		if (!CrossSystemMeasures.isQuotable(pEntityName) || !CrossSystemMeasures.isQuotable(pFoldedFieldName) || !CrossSystemMeasures.isQuotable(pRawFieldName))
		{
			return null;
		}
		final String sql = CrossSystemMeasures.cosmeticVariantGroupsSql(pEntityName, pFoldedFieldName, pRawFieldName);
		return CrossSystemMeasures.runCountQuery(pSession, pMasterDataset, sql, "COSMETIC");
	}

	/** The exact SQL {@link #cosmeticVariantGroupsCount} sends - split out purely so {@code CrossSystemMeasuresTest} can assert its text without an {@code Adaptation}/{@code Session}. Callers must have already checked {@link #isQuotable} on every argument - this method does not check again. */
	static String cosmeticVariantGroupsSql(final String pEntityName, final String pFoldedFieldName, final String pRawFieldName)
	{
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String foldedField = CrossSystemMeasures.quoteIdentifier(pFoldedFieldName);
		final String rawField = CrossSystemMeasures.quoteIdentifier(pRawFieldName);
		final String sourceSystem = CrossSystemMeasures.quoteIdentifier("sourceSystem");
		return "SELECT COUNT(*) AS COSMETIC FROM (SELECT a." + foldedField + " AS k FROM " + table + " a WHERE a." + foldedField + " IS NOT NULL GROUP BY a."
			+ foldedField + " HAVING COUNT(DISTINCT a." + sourceSystem + ") > 1 AND COUNT(DISTINCT a." + rawField + ") > 1) t";
	}

	/**
	 * <b>The REAL "does this field genuinely disagree" question (coordinator fix, 2026-09-22 18:20)</b>: among
	 * the groups of {@code pEntityIdentityKeyField} - the field the ENTITY ITSELF uses to recognise "this is
	 * the same real-world thing" across systems (its own MATCHING key, e.g. {@code nameKey} for {@code
	 * Company} - see {@code ebx.MatchPlanLlmSuggester#entityIdentityKeyField} for how it is chosen, never the
	 * attribute's own key) - SHARED by two or more different {@code sourceSystem}s, how many ALSO carry more
	 * than one DISTINCT value of {@code pAttributeFoldedField} (this attribute's own FOLDED form, e.g. {@code
	 * cityKey} for {@code city} - the raw form would just re-count the cosmetic question above). Measured live
	 * on {@code Companies_Master/Master/"Company"}, {@code city}, grouped by {@code nameKey}: <b>0 of 33</b>
	 * shared identity groups disagree - every ERP/CRM pair that is confidently the same company (same folded
	 * name) also agrees on the folded city; the 32/33 the cosmetic statement reports is entirely spelling.
	 * <p>
	 * <b>Coordinator fix, 2026-09-22 21:36 (agent run 29): {@code pEntityIdentityKeyField} must be the entity's
	 * MATCHING key, never a blocking/clustering field.</b> Passing {@code Company}'s {@code clusteringFields[0]}
	 * ({@code country}) instead of {@code nameKey} - the bug in {@code entityIdentityKeyField} the 18:20 fix
	 * shipped with, fixed the same day it was found - grouped every company by country and answered a
	 * fabricated "many groups disagree on name" (of course - a country holds many different companies) and a
	 * fabricated "zero groups disagree on country" (a field never disagrees with its own grouping key). The
	 * live 0-of-33 number above was only ever correct because {@code nameKey} happened to also be {@code
	 * clusteringFields[0]} on that particular plan - this method itself is unchanged (it groups by whatever key
	 * it is given), the fix is entirely in what the caller passes.
	 * <pre>{@code
	 * SELECT COUNT(*) FROM (
	 *   SELECT a."<identityKey>" AS k FROM "<Entity>" a WHERE a."<identityKey>" IS NOT NULL
	 *   GROUP BY a."<identityKey>" HAVING COUNT(DISTINCT a."sourceSystem") > 1 AND COUNT(DISTINCT a."<attributeFoldedField>") > 1
	 * ) t
	 * }</pre>
	 * Never called at all when the entity has no identity key yet (see the caller's own Javadoc - "and say
	 * so": a note is added to the prompt instead of a fabricated/degenerate number). Returns null ("not
	 * measured") on any failure, same discipline as every other method here.
	 */
	static Integer divergingGroupsCount(final Session pSession, final Adaptation pMasterDataset, final String pEntityName, final String pEntityIdentityKeyField,
		final String pAttributeFoldedField)
	{
		if (!CrossSystemMeasures.isQuotable(pEntityName) || !CrossSystemMeasures.isQuotable(pEntityIdentityKeyField)
			|| !CrossSystemMeasures.isQuotable(pAttributeFoldedField))
		{
			return null;
		}
		final String sql = CrossSystemMeasures.divergingGroupsSql(pEntityName, pEntityIdentityKeyField, pAttributeFoldedField);
		return CrossSystemMeasures.runCountQuery(pSession, pMasterDataset, sql, "DIVERGING");
	}

	/** The exact SQL {@link #divergingGroupsCount} sends - split out purely so {@code CrossSystemMeasuresTest} can assert its text without an {@code Adaptation}/{@code Session}. Callers must have already checked {@link #isQuotable} on every argument - this method does not check again. */
	static String divergingGroupsSql(final String pEntityName, final String pEntityIdentityKeyField, final String pAttributeFoldedField)
	{
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String identityKey = CrossSystemMeasures.quoteIdentifier(pEntityIdentityKeyField);
		final String attributeFoldedField = CrossSystemMeasures.quoteIdentifier(pAttributeFoldedField);
		final String sourceSystem = CrossSystemMeasures.quoteIdentifier("sourceSystem");
		return "SELECT COUNT(*) AS DIVERGING FROM (SELECT a." + identityKey + " AS k FROM " + table + " a WHERE a." + identityKey + " IS NOT NULL GROUP BY a."
			+ identityKey + " HAVING COUNT(DISTINCT a." + sourceSystem + ") > 1 AND COUNT(DISTINCT a." + attributeFoldedField + ") > 1) t";
	}

	/** Shared execution shape every {@code COUNT(*) AS <alias>} single-row query in this class uses - {@link #sharedCount}/{@link #withinSystemDuplicateCount} keep their own inline copies (pre-existing, unchanged by this fix) since only the two methods above needed their SQL text split out for a unit test; a full de-duplication of all four is left for whoever next touches one of the older ones. */
	private static Integer runCountQuery(final Session pSession, final Adaptation pMasterDataset, final String pSql, final String pAlias)
	{
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(pSql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				if (!it.hasNext())
				{
					return null;
				}
				final Object value = it.next().get(pAlias);
				return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
			}
			finally
			{
				result.close();
			}
		}
		// deliberately broad, same reasoning as sharedCount's own catch: never anything narrower than
		// RuntimeException is safe to rely on yet for a statement not exercised live by this codebase
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * The "one more measurement" `PLAN.md`'s 2026-09-21 "Rapprochement" entry asks for: how many rows of EACH
	 * source system PRESENT in the table have {@code pFieldName} filled - {@code SELECT "sourceSystem",
	 * COUNT("field") ... GROUP BY "sourceSystem"}, this exact shape run live by the coordinator against the
	 * real demo master table, 2026-09-21. A {@code GROUP BY sourceSystem} with no {@code WHERE} returns one
	 * row per source system holding at least one row in the table at all, whether or not the field itself is
	 * filled there - so the returned map's key set doubles as "which source systems are present", and a
	 * present system's {@code COUNT(field)} of 0 means the field is entirely empty for it (see {@link
	 * CrossSystemFieldGuard}'s own Javadoc). Returns null ("not measured") on any failure - same discipline
	 * as {@link #sharedCount}.
	 */
	static Map<String, Long> perSystemFilledCounts(final Session pSession, final Adaptation pMasterDataset, final String pEntityName, final String pFieldName)
	{
		if (!CrossSystemMeasures.isQuotable(pEntityName) || !CrossSystemMeasures.isQuotable(pFieldName))
		{
			return null;
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String field = CrossSystemMeasures.quoteIdentifier(pFieldName);
		final String sourceSystem = CrossSystemMeasures.quoteIdentifier("sourceSystem");
		final String sql = "SELECT a." + sourceSystem + " AS SYS, COUNT(a." + field + ") AS FILLED FROM " + table + " a GROUP BY a." + sourceSystem;
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final Map<String, Long> out = new LinkedHashMap<String, Long>();
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					final Tuple tuple = it.next();
					final Object system = tuple.get("SYS");
					final Object filled = tuple.get("FILLED");
					if (system != null && filled instanceof Number)
					{
						out.put(String.valueOf(system), Long.valueOf(((Number) filled).longValue()));
					}
				}
				return out;
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * How many rows of EACH source system PRESENT in the master table exist at all - {@code SELECT
	 * "sourceSystem", COUNT(*) ... GROUP BY "sourceSystem"}, the plain row-count sibling of {@link
	 * #perSystemFilledCounts} (same statement shape, minus the per-field {@code WHERE}/{@code COUNT(field)}).
	 * Added for the review-match-plan-simulation lot (2026-09-22, {@code ebx.MatchPlanSimulationRunner}'s own
	 * per-system random pivot draw - "10 per source system") - the key set doubles as "which systems exist on
	 * this table", the same convention {@link #perSystemFilledCounts}'s own Javadoc already documents. Never
	 * run live this session (no EBX access) - built by direct analogy to {@link #perSystemFilledCounts}, which
	 * WAS. Returns null ("not measured") on any failure, same discipline as every other method here.
	 */
	static Map<String, Long> rowCountsBySystem(final Session pSession, final Adaptation pMasterDataset, final String pEntityName)
	{
		if (!CrossSystemMeasures.isQuotable(pEntityName))
		{
			return null;
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String sourceSystem = CrossSystemMeasures.quoteIdentifier("sourceSystem");
		final String sql = "SELECT a." + sourceSystem + " AS SYS, COUNT(*) AS N FROM " + table + " a GROUP BY a." + sourceSystem;
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final Map<String, Long> out = new LinkedHashMap<String, Long>();
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					final Tuple tuple = it.next();
					final Object system = tuple.get("SYS");
					final Object count = tuple.get("N");
					if (system != null && count instanceof Number)
					{
						out.put(String.valueOf(system), Long.valueOf(((Number) count).longValue()));
					}
				}
				return out;
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** Audit F-33: the bound on what the master table's modification date does not cover. */
	static final long ROW_COUNTS_MAX_AGE_MS = 5L * 60L * 1000L;
	private static final ReadCache<Map<String, Long>> ROW_COUNTS = new ReadCache<Map<String, Long>>(128, CrossSystemMeasures.ROW_COUNTS_MAX_AGE_MS);

	/** Pure: the cache key of {@link #rowCountsBySystemCached} - where the table is, who asked (the query runs as the session), and the table's modification token. */
	static String rowCountsKey(final String pDataspace, final String pDataset, final String pEntityName, final String pUser, final String pTableToken)
	{
		return pDataspace + "\u0001" + pDataset + "\u0001" + pEntityName + "\u0001" + pUser + "\u0001" + pTableToken;
	}

	/**
	 * Audit F-33 (2026-10-06): {@link #rowCountsBySystem} for the wizard's step 6 plan cards, which redrew a whole-table {@code GROUP BY} per
	 * master entity on EVERY render of every step. The counts only move when the master table does, so they are kept under that table's last
	 * modification date ({@link StagingLoadGridReader#modificationToken}: any record created, updated or deleted - a load, a merge, a decision
	 * moves it) and a time limit; a null answer ("not measured") is never kept. The other callers (the LLM survivorship prompt, the simulation's
	 * pivot draw) keep the live read: they run once per action, on the numbers of that instant.
	 */
	static Map<String, Long> rowCountsBySystemCached(final Session pSession, final Adaptation pMasterDataset, final String pEntityName)
	{
		String key = null;
		try
		{
			final String token = StagingLoadGridReader
				.modificationToken(pMasterDataset.getTable(com.orchestranetworks.schema.Path.parse(com.on.ebx.factory.master.MasterModel.tablePath(pEntityName))));
			if (token != null && !"-".equals(token))
			{
				key = CrossSystemMeasures.rowCountsKey(pMasterDataset.getHome().getKey().format(), pMasterDataset.getAdaptationName().getStringName(), pEntityName,
					pSession == null ? "-" : String.valueOf(pSession.getUserReference()), token);
			}
		}
		catch (final RuntimeException ex)
		{
			key = null; // the table cannot be resolved: measure live, as before
		}
		if (key != null)
		{
			final Map<String, Long> cached = CrossSystemMeasures.ROW_COUNTS.get(key, System.currentTimeMillis());
			if (cached != null)
			{
				return cached;
			}
		}
		final Map<String, Long> fresh = CrossSystemMeasures.rowCountsBySystem(pSession, pMasterDataset, pEntityName);
		if (key != null && fresh != null)
		{
			CrossSystemMeasures.ROW_COUNTS.put(key, fresh, System.currentTimeMillis());
		}
		return fresh;
	}

	/**
	 * Lot 1 (2026-09-23, quality-informed survivorship, `docs/MATCHING.md` &sect;23): the cap on how many
	 * DISTINCT (source system, value) pairs {@link #perSystemValidityCounts} will ever read off the master
	 * table before giving up and reporting "not measured" (returning {@code null}) rather than scoring a
	 * partial read - the same "never divide a partial count" discipline {@code MatchPlanLlmPass}'s own {@code
	 * fillRateReliable()}/{@code EXACT} provenance convention applies one layer up (`profile_table_for_matching`
	 * statistics), reproduced here for a measure that has no such provenance tag of its own (every method in
	 * this class already returns {@code null} on ANY failure - see the class Javadoc - so "the cap was
	 * exceeded" is simply one more reason to return {@code null}, not a new signal shape). INFERRED ("~5000" -
	 * task brief), not read from a product document; re-tune if a real project's own per-attribute distinct
	 * count makes it too low.
	 */
	static final int MAX_VALIDITY_DISTINCT_VALUES = 5000;

	/**
	 * Lot 1 (2026-09-23, quality-informed survivorship): per source system present on the entity's master
	 * table, how many non-null values of {@code pFieldName} were CHECKED against {@code pRules} (every one of
	 * the attribute's own accepted VALIDITY rules - {@code REFERENCE}/{@code CHECK_DIGIT}/{@code PATTERN},
	 * {@code ebx.StagingLoader#readQualityRules}'s own filter) and how many PASSED every one of them -
	 * {@code ebx.MatchPlanLlmSuggester} feeds this straight into the survivorship prompt (a per-system "valid
	 * x / checked y" line, next to the pre-existing per-system fill line) so the language model can prefer
	 * {@code most trusted source} for a field one source demonstrably fills with cleaner data, never merely a
	 * fuller one.
	 * <p>
	 * <b>SQL shape</b> - a single {@code GROUP BY sourceSystem, field} (never one query per distinct value, and
	 * never a self-join - the same linear-in-row-count discipline {@link #sharedCount}'s own Javadoc already
	 * establishes for this class):
	 * <pre>{@code
	 * SELECT a."sourceSystem" AS SYS, a."<field>" AS V, COUNT(*) AS N
	 *   FROM "<Entity>" a WHERE a."<field>" IS NOT NULL GROUP BY a."sourceSystem", a."<field>"
	 * }</pre>
	 * Each returned row is one DISTINCT (system, value) pair with its own row count - fed straight to {@code
	 * quality.ValidityAggregator#aggregate} (the SAME pure scoring method {@code quality.ValidValueDerivation}
	 * uses on a sampled landing-data equivalent of this shape, see that class' own Javadoc for why one shared
	 * method exists), which runs {@link QualityRuleCheck#passes} on each distinct value ONCE and weighs the
	 * verdict by how many rows carry it - never once per row.
	 * <p>
	 * <b>Capped, not exhaustive</b> - more than {@link #MAX_VALIDITY_DISTINCT_VALUES} distinct (system, value)
	 * pairs and this returns {@code null} ("not reliable") rather than scoring a partial read the caller could
	 * mistake for the whole picture - the same "never divide a partial count" rule this class already applies
	 * everywhere else, just expressed as one more null-returning failure mode instead of a new provenance tag.
	 * Never run live this session (no EBX/live MCP access) - built by direct analogy to {@link
	 * #perSystemFilledCounts}'s own statement (verified live by the coordinator, 2026-09-21), changing only the
	 * {@code GROUP BY}/{@code SELECT} shape and adding the in-Java scoring pass. Returns {@code null} on ANY
	 * failure, same discipline as every other method here - including {@code pRules} empty/null (nothing to
	 * score) and either identifier failing {@link #isQuotable}.
	 */
	static Map<String, ValidityAggregator.SystemValidity> perSystemValidityCounts(final Session pSession, final Adaptation pMasterDataset,
		final String pEntityName, final String pFieldName, final List<QualityRuleCheck.Rule> pRules, final CleaningSteps.Lookups pLookups)
	{
		if (pRules == null || pRules.isEmpty())
		{
			return null;
		}
		final List<ValidityAggregator.DistinctValue> rows = CrossSystemMeasures.perSystemDistinctValues(pSession, pMasterDataset, pEntityName, pFieldName);
		return rows == null ? null : ValidityAggregator.aggregate(rows, pRules, pLookups);
	}

	/**
	 * The raw (source system, distinct value, occurrence count) rows BOTH {@link #perSystemValidityCounts} and
	 * {@link #perSystemValueShapes} are built from - factored out (lot volume-4, 2026-09-28, task brief item 6)
	 * so a caller that needs BOTH derivations for the same field (a STRING attribute that also carries validity
	 * rules - {@code ebx.MatchPlanLlmSuggester}'s own survivorship loop, the one caller of either method) can run
	 * the identical {@code GROUP BY sourceSystem, field} statement ONCE and feed it to both {@link
	 * ValidityAggregator#aggregate} and {@link #shapesFromDistinctValues}, instead of the same live SQL query
	 * being sent twice per attribute - up to 30 attributes, doubling the SQL round trips for every one of them
	 * that qualified for both. Same cap/failure discipline as every other method in this class: {@code null}
	 * ("not measured"/"not reliable") on an unquotable identifier, ANY query failure, or more than {@link
	 * #MAX_VALIDITY_DISTINCT_VALUES} distinct (system, value) pairs - never a partial read a caller could mistake
	 * for the whole picture.
	 */
	static List<ValidityAggregator.DistinctValue> perSystemDistinctValues(final Session pSession, final Adaptation pMasterDataset, final String pEntityName,
		final String pFieldName)
	{
		if (!CrossSystemMeasures.isQuotable(pEntityName) || !CrossSystemMeasures.isQuotable(pFieldName))
		{
			return null;
		}
		final String sql = CrossSystemMeasures.perSystemValidityCountsSql(pEntityName, pFieldName);
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final List<ValidityAggregator.DistinctValue> rows = new ArrayList<ValidityAggregator.DistinctValue>();
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					if (rows.size() >= CrossSystemMeasures.MAX_VALIDITY_DISTINCT_VALUES)
					{
						return null; // cap exceeded: "not reliable" rather than a partial score - see this method's own Javadoc
					}
					final Tuple tuple = it.next();
					final Object system = tuple.get("SYS");
					final Object value = tuple.get("V");
					final Object count = tuple.get("N");
					if (system != null && value != null && count instanceof Number)
					{
						rows.add(new ValidityAggregator.DistinctValue(String.valueOf(system), String.valueOf(value), ((Number) count).longValue()));
					}
				}
				return rows;
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** The exact SQL {@link #perSystemValidityCounts} sends - split out purely so {@code CrossSystemMeasuresTest} can assert its text without an {@code Adaptation}/{@code Session}, the same discipline {@link #cosmeticVariantGroupsSql}/{@link #divergingGroupsSql} already established. Callers must have already checked {@link #isQuotable} on both arguments - this method does not check again. */
	static String perSystemValidityCountsSql(final String pEntityName, final String pFieldName)
	{
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String field = CrossSystemMeasures.quoteIdentifier(pFieldName);
		final String sourceSystem = CrossSystemMeasures.quoteIdentifier("sourceSystem");
		return "SELECT a." + sourceSystem + " AS SYS, a." + field + " AS V, COUNT(*) AS N FROM " + table + " a WHERE a." + field + " IS NOT NULL GROUP BY a."
			+ sourceSystem + ", a." + field;
	}

	/**
	 * 2026-09-25, "matching-plan-child-and-identity" lot ({@code docs/MATCHING.md} &sect;14, PersVol3 volume
	 * test): per source system present on the entity's master table, the {@link #SHAPES_SHOWN} most frequent
	 * value SHAPES of {@code pFieldName} ({@code quality.Shapes#of} - the SAME masking {@code
	 * ebx.StagingLlmSuggester#fillShapes}/{@code #topShapes} already applies on landing data, reused here rather
	 * than reinvented), each with its share of that system's own non-null values - e.g. {@code {"ERP":
	 * ["9(8) (100%)"], "CRM": ["+99(11) (72%)", "99(10) (28%)"]}}. Lets {@code MatchPlanLlmPass}'s prompt show, and
	 * its {@code Session.AttributeInfo#dominantShapeConflict} guard catch, a text field written in genuinely
	 * different formats per source (a phone {@code +33612345678}/{@code 0033612345678}/{@code 0612345678}) -
	 * exactly the case measured live on PersVol3 that an EXACT comparison can never bridge.
	 * <p>
	 * <b>SQL shape</b> - the SAME single {@code GROUP BY sourceSystem, field} statement {@link
	 * #perSystemValidityCountsSql} already sends (reused verbatim, not duplicated: only what the rows are used
	 * for differs - shape aggregation here, quality-rule scoring there), capped the same way ({@link
	 * #MAX_VALIDITY_DISTINCT_VALUES} distinct (system, value) pairs; past it this returns {@code null} rather
	 * than a partial read the caller could mistake for the whole picture). Never run live this session (no
	 * EBX/live MCP access) - built by direct analogy to {@link #perSystemValidityCounts}'s own live-verified
	 * statement shape, changing only what happens to each row once read. Returns {@code null} on ANY failure,
	 * same discipline as every other method in this class.
	 */
	static Map<String, List<String>> perSystemValueShapes(final Session pSession, final Adaptation pMasterDataset, final String pEntityName,
		final String pFieldName)
	{
		final List<ValidityAggregator.DistinctValue> rows = CrossSystemMeasures.perSystemDistinctValues(pSession, pMasterDataset, pEntityName, pFieldName);
		return rows == null ? null : CrossSystemMeasures.shapesFromDistinctValues(rows);
	}

	/**
	 * Pure shape-aggregation half of {@link #perSystemValueShapes} (lot volume-4, 2026-09-28), taking the SAME
	 * {@link ValidityAggregator.DistinctValue} rows {@link #perSystemValidityCounts} can be fed too - so a caller
	 * needing BOTH derivations for one field ({@code ebx.MatchPlanLlmSuggester}) fetches the rows ONCE (via
	 * {@link #perSystemDistinctValues}) and calls this method directly, skipping a second identical SQL query.
	 */
	static Map<String, List<String>> shapesFromDistinctValues(final List<ValidityAggregator.DistinctValue> pRows)
	{
		final Map<String, Map<String, Long>> countBySystemAndShape = new LinkedHashMap<String, Map<String, Long>>();
		if (pRows != null)
		{
			for (final ValidityAggregator.DistinctValue row : pRows)
			{
				if (row.sourceSystem == null || row.value == null)
				{
					continue;
				}
				final String shape = com.on.ebx.factory.quality.Shapes.of(row.value);
				Map<String, Long> byShape = countBySystemAndShape.get(row.sourceSystem);
				if (byShape == null)
				{
					byShape = new LinkedHashMap<String, Long>();
					countBySystemAndShape.put(row.sourceSystem, byShape);
				}
				final Long prior = byShape.get(shape);
				byShape.put(shape, Long.valueOf((prior == null ? 0L : prior.longValue()) + row.count));
			}
		}
		return CrossSystemMeasures.topShapesBySystem(countBySystemAndShape);
	}

	/** The {@link #SHAPES_SHOWN} most frequent shapes of each system in {@code pCountBySystemAndShape}, formatted {@code "<shape> (<share>%)"}, most frequent first - the exact rendering {@code ebx.StagingLlmSuggester#topShapes} already established for the staging pass, reproduced here (package-private, no shared dependency between the two packages) rather than reused across a package boundary that does not otherwise exist. */
	static Map<String, List<String>> topShapesBySystem(final Map<String, Map<String, Long>> pCountBySystemAndShape)
	{
		final Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
		for (final Map.Entry<String, Map<String, Long>> systemEntry : pCountBySystemAndShape.entrySet())
		{
			final List<Map.Entry<String, Long>> sorted = new ArrayList<Map.Entry<String, Long>>(systemEntry.getValue().entrySet());
			Collections.sort(sorted, new java.util.Comparator<Map.Entry<String, Long>>()
			{
				@Override
				public int compare(final Map.Entry<String, Long> pLeft, final Map.Entry<String, Long> pRight)
				{
					return pRight.getValue().compareTo(pLeft.getValue());
				}
			});
			long total = 0;
			for (final Map.Entry<String, Long> entry : sorted)
			{
				total += entry.getValue().longValue();
			}
			final List<String> shapes = new ArrayList<String>();
			for (final Map.Entry<String, Long> entry : sorted)
			{
				if (shapes.size() >= CrossSystemMeasures.SHAPES_SHOWN)
				{
					break;
				}
				shapes.add(entry.getKey() + " (" + Math.round(100.0 * entry.getValue().longValue() / Math.max(1L, total)) + "%)");
			}
			out.put(systemEntry.getKey(), shapes);
		}
		return out;
	}

	private static final int SHAPES_SHOWN = 3;

	/**
	 * A conservative identifier/field-name shape check before either is ever concatenated into SQL text -
	 * never anything but letters/digits/underscore, matching every real entity/attribute name this factory
	 * itself ever generates. Refuses (rather than escapes) a name carrying a double quote, or any other
	 * character that could otherwise close the quoting early and inject arbitrary SQL - the ONE check both
	 * {@link #sharedCount} and {@link #perSystemFilledCounts} share, so an unsafe name degrades to "not
	 * measured" identically in both, never a broken or unsafely-built statement.
	 */
	static boolean isQuotable(final String pIdentifier)
	{
		return pIdentifier != null && pIdentifier.matches("[A-Za-z_][A-Za-z0-9_]*");
	}

	static String quoteIdentifier(final String pIdentifier)
	{
		return "\"" + pIdentifier.replace("\"", "\"\"") + "\""; // a quote inside a name is doubled (security review, 2026-09-24)
	}
}
