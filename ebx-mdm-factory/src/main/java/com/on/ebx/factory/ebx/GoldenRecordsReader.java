package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.master.MameMetadataContract;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.query.Query;
import com.orchestranetworks.query.QueryResult;
import com.orchestranetworks.query.Tuple;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.Session;

/**
 * Wizard step 7 ("Summary") lot, 2026-09-24: a small reader of this lot's own for the golden-record facts
 * {@code com.on.ebx.factory.ebx.ui.CockpitData} does not carry - fill rate per business attribute of each
 * matched master entity's golden records, and the golden/pending-suspect counts the journey diagram and the
 * Matching zone card need. Deliberately separate from whatever the parallel step 6 ("Matching results") lot
 * builds for its own screen (see the task brief: "the other agent may build a richer one, the coordinator
 * will merge them later") - this one reads only what step 7 needs, as cheaply as possible (one table scan
 * per promoted master entity, no MCP call).
 * <p>
 * Reads the business table directly ({@code CLAUDE.md} &sect;8, "A record's Match and Merge state ... is
 * readable from Java" / "The business table itself exposes {@code Linked_Field_State}/{@code GroupId}/
 * {@code GoldenId}/{@code AutoCreated}/{@code FunctionalId}/{@code HasSuspects} on every row"), never the
 * add-on's metadataset table directly - no dependency on the add-on's own schema beyond these six fields,
 * and no separate metadataset lookup. {@link #Result#matchingAvailable} is false (and every count falls back
 * to a plain, unfiltered read of the master table, {@link Result#masterFallback}) when a table does not
 * carry {@code Linked_Field_State} at all - the add-on was never configured for it, or the model was
 * generated before matching ran a first time. {@code Linked_Field_State} state key 4 (DELETED, an unmerged
 * auto-created golden's own physical row - CLAUDE.md &sect;8) is always excluded from every count; state 1
 * (GOLDEN) is what "golden record" means throughout.
 */
public final class GoldenRecordsReader
{
	private GoldenRecordsReader()
	{
	}

	private static final Path LINKED_FIELD_STATE = Path.parse("./Linked_Field_State");
	private static final int STATE_GOLDEN = MameMetadataContract.STATE_GOLDEN;
	private static final int STATE_DELETED = MameMetadataContract.STATE_DELETED;
	private static final int STATE_MERGED = MameMetadataContract.STATE_MERGED;
	/**
	 * Volume fix (2026-10-05, Vol1mF, 1.46 M master rows): above this many rows the SQL aggregate of {@link #queryEntityCounts} is not run -
	 * {@code Linked_Field_State} is not stored in the master table, the engine reads it through the add-on's metadataset ONE ROW AT A TIME
	 * (jstack: {@code LinkedFieldValueFunction.getValue} under {@code CteQueryResult}), so the wizard's summary took minutes and the page stayed
	 * blank until it ended. Above the threshold the figures come from {@code RecordMetadata}'s own row counts and the per-attribute fill is skipped.
	 */
	static final long LARGE_TABLE_ROWS = 50000L;

	/** One business attribute's fill rate, aggregated over every promoted master entity read - {@link #name} is prefixed with the entity name when more than one entity was read (see {@link #read}). */
	public static final class AttributeFill
	{
		public final String name;
		public final int filled;
		public final int total;
		/** UX lot U3 (2026-10-13): the master object read and the attribute's own name (without the object prefix of {@link #name}) - the summary groups the fill rates by object. */
		public final String entityName;
		public final String attribute;

		AttributeFill(final String pName, final int pFilled, final int pTotal, final String pEntityName, final String pAttribute)
		{
			this.name = pName;
			this.filled = pFilled;
			this.total = pTotal;
			this.entityName = pEntityName;
			this.attribute = pAttribute;
		}
	}

	public static final class Result
	{
		/** True as soon as ONE promoted entity's table carries {@code Linked_Field_State} - the journey diagram/Matching card fall back to "not available" only when this stays false for every entity. */
		public boolean matchingAvailable;
		/** Sum of GOLDEN (state 1) rows across every entity read; 0 when {@link #matchingAvailable} is false. */
		public long goldenCount;
		/**
		 * The number of PENDING SUSPECT PAIRS, summed over the entities read: the rows of each metadataset's {@code Suspected}
		 * table (wave 2, 2026-09-30). It used to be the GOLDEN rows flagged {@code HasSuspects} - a record count, up to twice the
		 * pairs (a pair is flagged on both of its records) - shown to the person as "N pairs to decide" (Vol100k: 46 663 shown,
		 * 33 374 real, for {@code Personne}).
		 */
		public long pendingSuspects;
		/** Wave 2: the goldens still flagged {@code HasSuspects}, summed over the entities read - reported as records, never as pairs. */
		public long goldensWithSuspects;
		/** True when NO entity read carried {@code Linked_Field_State}: every count above falls back to a plain, unfiltered read of the master table(s) instead of GOLDEN rows only. */
		public boolean masterFallback;
		/** True when at least one entity was too large for the per-attribute fill aggregate (see {@link GoldenRecordsReader#LARGE_TABLE_ROWS}): {@link #attributes} then lacks that entity. */
		public boolean fillRatesSkipped;
		public final List<AttributeFill> attributes = new ArrayList<AttributeFill>();
		/** Every promoted entity's own total row count (every state but DELETED - a plain "how many master records" figure, not filtered to GOLDEN) - the Master zone card/journey need this, GOLDEN-only would under-report a project with unmerged duplicates still awaiting a decision. */
		public final Map<String, Long> rowsByEntity = new LinkedHashMap<String, Long>();
		/**
		 * Step 7 screen counts (2026-10-18): the GOLDEN rows and the MERGED rows of each promoted entity whose table carries {@code Linked_Field_State}. An entity without the state
		 * field has no entry (no Match and Merge state to count: its rows are the figure), so a caller never reads a 0 for "not known". {@link #goldenCount} is the sum of the first
		 * map over ALL entities - a figure for the whole master, which the screens quote per main object instead.
		 */
		public final Map<String, Long> goldenByEntity = new LinkedHashMap<String, Long>();
		public final Map<String, Long> mergedByEntity = new LinkedHashMap<String, Long>();
	}

	/** One promoted master entity to read: its table path and the business (non-technical) attribute names to measure fill rate on. */
	public static final class EntitySpec
	{
		public final String entityName;
		public final String tablePath;
		public final List<String> businessAttributeNames;

		public EntitySpec(final String pEntityName, final String pTablePath, final List<String> pBusinessAttributeNames)
		{
			this.entityName = pEntityName;
			this.tablePath = pTablePath;
			this.businessAttributeNames = pBusinessAttributeNames;
		}
	}

	/** @deprecated lot volume-4 (2026-09-28): kept only so an out-of-tree caller does not break at compile time - every in-tree caller now passes a {@link Session} (see {@link #read(Session, Repository, String, String, List)}), needed to run the SQL aggregate this class now uses instead of a full table scan. */
	@Deprecated
	public static Result read(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName, final List<EntitySpec> pEntities)
	{
		return GoldenRecordsReader.read(null, pRepository, pMasterDataspaceKey, pMasterDatasetName, pEntities);
	}

	/**
	 * Lot volume-4 (2026-09-28, task brief item 7): {@code pSession} is new - needed to run the SQL {@code
	 * COUNT}/{@code CASE WHEN} aggregate {@link #queryEntityCounts} now uses in place of the old per-record
	 * {@code RequestResult} scan (one query per entity instead of one row read per master record - a 5-million-
	 * row master table used to mean 5 million {@code Adaptation} reads just to compute a handful of fill rates).
	 * Both in-tree callers ({@code ebx.DataProductPublication}, {@code ebx.ui.ProjectWizardUserService}) already
	 * have a {@code Session} in scope at their own call site - neither is on this lot's restricted-file list.
	 */
	public static Result read(final Session pSession, final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final List<EntitySpec> pEntities)
	{
		final Result out = new Result();
		if (pRepository == null || pMasterDataspaceKey == null || pMasterDataspaceKey.isEmpty() || pMasterDatasetName == null || pMasterDatasetName.isEmpty() || pEntities.isEmpty())
		{
			return out;
		}
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(pMasterDataspaceKey));
		final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pMasterDatasetName));
		if (dataset == null)
		{
			return out;
		}
		boolean anyStateField = false;
		for (final EntitySpec entity : pEntities)
		{
			final AdaptationTable table = dataset.getTable(Path.parse(entity.tablePath));
			if (table == null)
			{
				continue;
			}
			if (GoldenRecordsReader.readEntity(pSession, pRepository, pMasterDataspaceKey, pMasterDatasetName, dataset, table, entity, out, pEntities.size() > 1))
			{
				anyStateField = true;
				GoldenRecordsReader.addPendingFigures(pRepository, pMasterDataspaceKey, pMasterDatasetName, entity.tablePath, out);
			}
		}
		out.matchingAvailable = anyStateField;
		out.masterFallback = !anyStateField;
		return out;
	}

	/** Returns true when this entity's table carries {@code Linked_Field_State} (the caller ORs this across every entity into {@link Result#matchingAvailable}). */
	private static boolean readEntity(final Session pSession, final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final Adaptation pDataset, final AdaptationTable pTable, final EntitySpec pEntity, final Result pOut, final boolean pPrefixLabel)
	{
		final boolean hasStateField = pTable.getTableOccurrenceRootNode().getNode(GoldenRecordsReader.LINKED_FIELD_STATE) != null;
		if (hasStateField)
		{
			final long[] large = GoldenRecordsReader.largeTableCounts(pRepository, pMasterDataspaceKey, pMasterDatasetName, pTable, pEntity.tablePath);
			if (large != null)
			{
				pOut.goldenCount += large[1];
				pOut.rowsByEntity.put(pEntity.entityName, Long.valueOf(large[0]));
				pOut.goldenByEntity.put(pEntity.entityName, Long.valueOf(large[1]));
				pOut.mergedByEntity.put(pEntity.entityName, Long.valueOf(large[2]));
				pOut.fillRatesSkipped = true;
				return true;
			}
		}
		final EntityCounts counts = pSession == null ? null
			: GoldenRecordsReader.queryEntityCounts(pSession, pDataset, pTable, pEntity, hasStateField);
		if (counts == null)
		{
			// no Session (the deprecated overload), or the SQL query itself failed - degrade to the original
			// full scan rather than silently reporting every count as zero for a table that does exist.
			return GoldenRecordsReader.scanEntity(pTable, pEntity, pOut, pPrefixLabel);
		}
		pOut.goldenCount += counts.golden;
		pOut.rowsByEntity.put(pEntity.entityName, Long.valueOf(counts.total));
		if (hasStateField)
		{
			pOut.goldenByEntity.put(pEntity.entityName, Long.valueOf(counts.golden));
			pOut.mergedByEntity.put(pEntity.entityName, Long.valueOf(counts.merged));
		}
		for (int i = 0; i < pEntity.businessAttributeNames.size(); i++)
		{
			final String name = pEntity.businessAttributeNames.get(i);
			final String label = pPrefixLabel ? pEntity.entityName + " - " + name : name;
			pOut.attributes.add(new AttributeFill(label, counts.filled[i], (int) counts.golden, pEntity.entityName, name));
		}
		return hasStateField;
	}

	/**
	 * {@code [total without DELETED, GOLDEN, MERGED]} of a table above {@link #LARGE_TABLE_ROWS}, read as sizes of the add-on's own {@code RecordMetadata}
	 * (state 1 = golden, 4 = deleted; no join, no per-row association read) - or {@code null} when the table is small or the metadataset is
	 * unavailable, in which case the caller keeps the SQL aggregate.
	 */
	private static long[] largeTableCounts(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName, final AdaptationTable pTable,
		final String pTablePath)
	{
		try
		{
			final long size = GoldenRecordsReader.requestSize(pTable, null);
			if (size <= GoldenRecordsReader.LARGE_TABLE_ROWS)
			{
				return null;
			}
			final AdaptationTable metadata = MatchingResultsBuilder.metadatasetTable(pRepository, pMasterDataspaceKey, pMasterDatasetName, pTablePath, "RecordMetadata");
			if (metadata == null)
			{
				return null;
			}
			final long golden = GoldenRecordsReader.requestSize(metadata, "./state=" + GoldenRecordsReader.STATE_GOLDEN);
			final long deleted = GoldenRecordsReader.requestSize(metadata, "./state=" + GoldenRecordsReader.STATE_DELETED);
			final long merged = GoldenRecordsReader.requestSize(metadata, "./state=" + GoldenRecordsReader.STATE_MERGED);
			return new long[] { Math.max(0L, size - deleted), golden, merged };
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static long requestSize(final AdaptationTable pTable, final String pPredicate)
	{
		final RequestResult rows = UserView.select(pTable, pPredicate);
		try
		{
			return rows.getSize();
		}
		finally
		{
			rows.close();
		}
	}

	/** One entity's SQL-aggregate answer - {@link #queryEntityCounts}'s own return shape. */
	private static final class EntityCounts
	{
		final long total;
		final long golden;
		final long merged;
		final int[] filled;

		EntityCounts(final long pTotal, final long pGolden, final long pMerged, final int[] pFilled)
		{
			this.total = pTotal;
			this.golden = pGolden;
			this.merged = pMerged;
			this.filled = pFilled;
		}
	}

	/**
	 * ONE {@code SELECT COUNT(*), COUNT(CASE WHEN ... THEN 1 END), ...} round trip per entity (task brief item 7)
	 * - the direct SQL replacement for {@link #scanEntity}'s per-record loop. {@code COUNT(field)} (not {@code
	 * COUNT(CASE WHEN ... THEN 1 END)}) is used for a business attribute's own fill count: it ignores {@code
	 * NULL} by definition, type-agnostic for any column type, and EBX itself stores a blank string as {@code
	 * NULL} (CLAUDE.md sec 9, "EBX stores an empty string as null") - so it answers the exact same "has a real
	 * value" question {@link #scanEntity}'s own {@code value != null && !"".equals(trim)} check did, without
	 * needing a type-specific {@code TRIM} that could fail on a non-string column. Every business attribute name
	 * is checked against the table's own schema first (never referenced in SQL if absent - a field the caller
	 * listed that this particular table does not carry reads as 0 filled, matching {@link #scanEntity}'s own
	 * behavior) and re-checked with {@link CrossSystemMeasures#isQuotable} before being concatenated (defence in
	 * depth, same discipline as every other SQL builder in this module). Returns {@code null} (never partial
	 * counts) on any failure - the caller then falls back to {@link #scanEntity}.
	 */
	private static EntityCounts queryEntityCounts(final Session pSession, final Adaptation pDataset, final AdaptationTable pTable, final EntitySpec pEntity,
		final boolean pHasStateField)
	{
		if (!CrossSystemMeasures.isQuotable(pEntity.entityName))
		{
			return null;
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntity.entityName);
		final boolean[] fieldPresent = new boolean[pEntity.businessAttributeNames.size()];
		final StringBuilder select = new StringBuilder("SELECT COUNT(*) AS TOTAL");
		if (pHasStateField)
		{
			select.append(", COUNT(CASE WHEN c.\"Linked_Field_State\" = ").append(GoldenRecordsReader.STATE_GOLDEN).append(" THEN 1 END) AS GOLDEN");
			select.append(", COUNT(CASE WHEN c.\"Linked_Field_State\" = ").append(GoldenRecordsReader.STATE_MERGED).append(" THEN 1 END) AS MERGED");
		}
		for (int i = 0; i < pEntity.businessAttributeNames.size(); i++)
		{
			final String name = pEntity.businessAttributeNames.get(i);
			fieldPresent[i] = CrossSystemMeasures.isQuotable(name) && pTable.getTableOccurrenceRootNode().getNode(Path.parse("./" + name)) != null;
			if (!fieldPresent[i])
			{
				continue;
			}
			final String field = CrossSystemMeasures.quoteIdentifier(name);
			if (pHasStateField)
			{
				select.append(", COUNT(CASE WHEN c.\"Linked_Field_State\" = ").append(GoldenRecordsReader.STATE_GOLDEN).append(" THEN c.").append(field)
					.append(" END) AS F").append(i);
			}
			else
			{
				select.append(", COUNT(c.").append(field).append(") AS F").append(i);
			}
		}
		// UX lot U3 (2026-10-13): a row with no metadata (never reached by a matching run: Linked_Field_State is NULL) is a real master row - "<> 4" alone is
		// unknown for NULL and dropped it, so the summary counted only the records a run had touched (30 of 194 on UxCrmSf). The full-scan fallback
		// (scanEntity) and the large-table path (rows minus DELETED) already counted it.
		final String sql = select + " FROM " + table + (pHasStateField ? " c WHERE c.\"Linked_Field_State\" IS NULL OR c.\"Linked_Field_State\" <> " + GoldenRecordsReader.STATE_DELETED : " c");
		try
		{
			final Query<Tuple> query = pDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				if (!it.hasNext())
				{
					return new EntityCounts(0, 0, 0, new int[pEntity.businessAttributeNames.size()]);
				}
				final Tuple tuple = it.next();
				final long total = GoldenRecordsReader.asLong(tuple.get("TOTAL"));
				final long golden = pHasStateField ? GoldenRecordsReader.asLong(tuple.get("GOLDEN")) : total;
				final long merged = pHasStateField ? GoldenRecordsReader.asLong(tuple.get("MERGED")) : 0L;
				final int[] filled = new int[pEntity.businessAttributeNames.size()];
				for (int i = 0; i < filled.length; i++)
				{
					filled[i] = fieldPresent[i] ? (int) GoldenRecordsReader.asLong(tuple.get("F" + i)) : 0;
				}
				return new EntityCounts(total, golden, merged, filled);
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
	 * Wave 2 (2026-09-30): the pending PAIRS ({@code Suspected} rows) and the goldens still flagged {@code hasSuspects}
	 * ({@code RecordMetadata}) of one master table, each one {@code RequestResult.getSize()} on the entity's own metadataset - no
	 * session, no join with the master table (the former {@code COUNT(CASE WHEN ... HasSuspects ...)} column of the big aggregate
	 * read the flag through the Linked_Field association, once per master row). Zero when the metadataset or a table is missing.
	 */
	private static void addPendingFigures(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName, final String pTablePath,
		final Result pOut)
	{
		pOut.pendingSuspects += GoldenRecordsReader.tableSize(pRepository, pMasterDataspaceKey, pMasterDatasetName, pTablePath, "Suspected", null);
		pOut.goldensWithSuspects += GoldenRecordsReader.tableSize(pRepository, pMasterDataspaceKey, pMasterDatasetName, pTablePath, "RecordMetadata",
			"./state=1 and ./hasSuspects=true");
	}

	private static long tableSize(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName, final String pTablePath, final String pTableName,
		final String pPredicate)
	{
		final AdaptationTable table = MatchingResultsBuilder.metadatasetTable(pRepository, pMasterDataspaceKey, pMasterDatasetName, pTablePath, pTableName);
		if (table == null)
		{
			return 0L;
		}
		try
		{
			final RequestResult rows = UserView.select(table, pPredicate);
			try
			{
				return rows.getSize();
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return 0L;
		}
	}

	private static long asLong(final Object pValue)
	{
		return pValue instanceof Number ? ((Number) pValue).longValue() : 0L;
	}

	/** The pre-lot, per-record fallback - kept for when {@link #queryEntityCounts} cannot run at all (no {@link Session}, or the SQL engine refused the statement). Unchanged from before lot volume-4 except for its name. */
	private static boolean scanEntity(final AdaptationTable pTable, final EntitySpec pEntity, final Result pOut, final boolean pPrefixLabel)
	{
		final Map<String, int[]> counts = new LinkedHashMap<String, int[]>(); // name -> [filled, total]
		for (final String name : pEntity.businessAttributeNames)
		{
			counts.put(name, new int[2]);
		}
		boolean hasStateField = false;
		boolean first = true;
		long rowCount = 0;
		long goldenRows = 0;
		long mergedRows = 0;
		final RequestResult rows = UserView.select(pTable, null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (first)
				{
					hasStateField = row.getSchemaNode().getNode(GoldenRecordsReader.LINKED_FIELD_STATE) != null;
					first = false;
				}
				final Integer state = hasStateField ? GoldenRecordsReader.intOrNull(UserView.get(row, GoldenRecordsReader.LINKED_FIELD_STATE)) : null;
				final boolean deleted = state != null && state.intValue() == GoldenRecordsReader.STATE_DELETED;
				if (deleted)
				{
					continue; // never count a DELETED row - CLAUDE.md ss8, the physical row of an unmerged auto-created golden
				}
				rowCount++;
				if (state != null && state.intValue() == GoldenRecordsReader.STATE_MERGED)
				{
					mergedRows++;
				}
				final boolean golden = !hasStateField || (state != null && state.intValue() == GoldenRecordsReader.STATE_GOLDEN);
				if (!golden)
				{
					continue; // a real row (counted in rowCount above), but not a GOLDEN one - excluded from goldenCount/completeness
				}
				pOut.goldenCount++;
				goldenRows++;
				for (final String name : pEntity.businessAttributeNames)
				{
					final int[] c = counts.get(name);
					c[1]++;
					final Path path = Path.parse("./" + name);
					if (row.getSchemaNode().getNode(path) == null)
					{
						continue;
					}
					final Object value = UserView.get(row, path);
					if (value != null && !"".equals(String.valueOf(value).trim()))
					{
						c[0]++;
					}
				}
			}
		}
		finally
		{
			rows.close();
		}
		pOut.rowsByEntity.put(pEntity.entityName, Long.valueOf(rowCount));
		if (hasStateField)
		{
			pOut.goldenByEntity.put(pEntity.entityName, Long.valueOf(goldenRows));
			pOut.mergedByEntity.put(pEntity.entityName, Long.valueOf(mergedRows));
		}
		for (final Map.Entry<String, int[]> entry : counts.entrySet())
		{
			final String label = pPrefixLabel ? pEntity.entityName + " - " + entry.getKey() : entry.getKey();
			pOut.attributes.add(new AttributeFill(label, entry.getValue()[0], entry.getValue()[1], pEntity.entityName, entry.getKey()));
		}
		return hasStateField;
	}

	private static Integer intOrNull(final Object pValue)
	{
		if (pValue instanceof Number)
		{
			return Integer.valueOf(((Number) pValue).intValue());
		}
		return null;
	}
}
