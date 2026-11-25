package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lot S10 (2026-10-18, staging proposal): the numeric FEEDBACK given to the staging language model after its proposal, and the open point the review shows, for a
 * landing table whose rows carry identity attributes (catalog identity tags, {@link IdentityFacts.Kind}) and that feeds no entity holding the identity attributes
 * of the other tables.
 * <p>
 * <b>Never a decision.</b> The Factory states what it measured - how many rows carry an identity, how many of its e-mails or phones no other source holds - and
 * the model answers with more {@code propose_feed} calls or with a note; whichever it chooses stands. The entity "that holds the attributes" is not named from a
 * word list: it is derived from the proposal itself, as the entity fed by the identity columns of OTHER tables ({@link #gaps}).
 */
public final class IdentityCoverage
{
	private IdentityCoverage()
	{
	}

	/** One feed of the proposal, reduced to what this check reads: which entity reads which columns of which landing table. */
	public static final class FeedRead
	{
		public final String entity;
		public final String landingTableKey;
		public final boolean root;
		/** Field names of the landing columns the feed reads; null when unknown (a feed of an earlier run whose mappings were not read): the feed then counts as reading everything. */
		public final Set<String> columns;

		public FeedRead(final String pEntity, final String pLandingTableKey, final boolean pRoot, final Set<String> pColumns)
		{
			this.entity = pEntity;
			this.landingTableKey = pLandingTableKey;
			this.root = pRoot;
			this.columns = pColumns;
		}
	}

	/** A landing table with identity columns that no entity holding the identity of the other tables reads. */
	public static final class Gap
	{
		public final String landingTableKey;
		public final String tableName;
		/** Rows carrying at least one identity value; -1 when the table could not be read (the row count, else, is {@link #rows}). */
		public final long identityRows;
		/** The table's rows; -1 when unknown. */
		public final long rows;
		/** The identity tags present on the table, once each, in column order, with how firm each is ({@code EMAIL (applied)}, {@code PHONE (suggested by the catalogue from its sampled values, pending review)}). */
		public final List<String> tags;
		/** How the identity of the table is known, once each: {@code APPLIED}, {@code SAMPLED}, {@code NAMES}, {@code MODEL}, {@code OTHER} ({@link StagingRules.TagSuggestionInfo#basis}), {@code SHAPES} (lot F2: measured from value shapes, no catalogue tag). */
		public final List<String> bases;
		/** Lot F2: the identity columns the catalogue gave no tag, with what was measured on their values: {@code EMAIL (e-mail-shaped in 98% of sampled values)}. Empty when none. */
		public final List<String> shapes;
		/** Landing field names of the identity columns. */
		public final List<String> columns;
		public final List<IdentityFacts.Kind> kinds;
		/** Kind -> distinct values seen in no other source (empty when not measured). */
		public final Map<IdentityFacts.Kind, Long> seenNowhereElse;
		/** True when a cross-table figure could not be computed because a table was read on a sample. */
		public final boolean crossSkipped;
		/** The entities fed by the identity columns of the other tables (root feeds first), the ones this table could feed. */
		public final List<String> entities;
		/** The source key of the table (field names), for "one row = one ...". */
		public final List<String> keyColumns;

		Gap(final String pLandingTableKey, final String pTableName, final long pIdentityRows, final long pRows, final List<String> pTags, final List<String> pBases, final List<String> pColumns,
			final List<IdentityFacts.Kind> pKinds, final Map<IdentityFacts.Kind, Long> pSeenNowhereElse, final boolean pCrossSkipped, final List<String> pEntities,
			final List<String> pKeyColumns, final List<String> pShapes)
		{
			this.shapes = pShapes;
			this.landingTableKey = pLandingTableKey;
			this.tableName = pTableName;
			this.identityRows = pIdentityRows;
			this.rows = pRows;
			this.tags = pTags;
			this.bases = pBases;
			this.columns = pColumns;
			this.kinds = pKinds;
			this.seenNowhereElse = pSeenNowhereElse;
			this.crossSkipped = pCrossSkipped;
			this.entities = pEntities;
			this.keyColumns = pKeyColumns;
		}

		/** Whether some identity column is known only from a suggestion of the catalogue, not (yet) an applied tag (a column known from its value shapes is another case: {@link #hasShapes}). */
		public boolean suggestedOnly()
		{
			for (final String basis : this.bases)
			{
				if (!"APPLIED".equals(basis) && !IdentityFacts.BASIS_SHAPES.equals(basis))
				{
					return true;
				}
			}
			return false;
		}

		/** Lot F2: whether some identity column of the table is known only from the shapes measured on its values (no catalogue tag, no suggestion). */
		public boolean hasShapes()
		{
			return !this.shapes.isEmpty();
		}

		/** The rows the sentence quotes: those carrying an identity value when measured, else the table's rows; -1 when neither is known. */
		public long carryingRows()
		{
			return this.identityRows >= 0L ? this.identityRows : this.rows;
		}

		/** {@code "46 e-mails, 12 phones"}: the figures of the values seen nowhere else, empty when none was measured. */
		public String seenNowhereElseText()
		{
			final List<String> parts = new ArrayList<String>();
			for (final IdentityFacts.Kind kind : this.kinds)
			{
				final Long count = this.seenNowhereElse.get(kind);
				if (count != null)
				{
					parts.add(count + " distinct " + (count.longValue() == 1L ? kind.singular() : kind.plural()));
				}
			}
			return String.join(", ", parts);
		}

		/**
		 * How the identity of the table is known, as the follow-up sentence says it: {@code tags EMAIL (applied), PHONE (applied)}; with columns the catalogue gave no tag (lot F2) {@code
		 * measured from value shapes, no catalogue tag: EMAIL (e-mail-shaped in 98% of sampled values)}, after the tags when there are some, separated by {@code ; }.
		 */
		public String basisText()
		{
			final List<String> parts = new ArrayList<String>();
			if (!this.tags.isEmpty())
			{
				parts.add("tags " + String.join(", ", this.tags));
			}
			if (!this.shapes.isEmpty())
			{
				parts.add("measured from value shapes, no catalogue tag: " + String.join(", ", this.shapes));
			}
			return String.join("; ", parts);
		}

		/** What "one row" is in this table, for the sentence that proposes a root feed: the key columns, else the table. */
		public String oneRowIs()
		{
			return this.keyColumns.isEmpty() ? "row of " + this.tableName : String.join(" + ", this.keyColumns);
		}

		/**
		 * The feedback sentence for the follow-up turn: {@code SHOP.TICKETS: 234 rows carry identity attributes (tags ...; columns ...) but feed no entity that holds
		 * them; among them 46 distinct e-mails, 12 distinct phones are seen in no other source - they will be absent from the master. Feed them (a root feed of Client on
		 * SHOP.TICKETS, one row = one NUM_TICKET) or say in a note why not.}
		 */
		public String followUpText()
		{
			final StringBuilder out = new StringBuilder();
			out.append(this.landingTableKey).append(": ");
			final long carrying = this.carryingRows();
			out.append(carrying >= 0L ? carrying + " row" + (carrying == 1L ? "" : "s") : "its rows").append(" carry identity attributes (").append(this.basisText())
				.append("; columns ").append(String.join(", ", this.columns)).append(") but feed no entity that holds them");
			final String only = this.seenNowhereElseText();
			if (!only.isEmpty())
			{
				out.append("; among them ").append(only).append(" are seen in no other source - they will be absent from the master");
			}
			else if (this.crossSkipped)
			{
				out.append(" (whether other sources hold the same values was not measured: a table is read on a sample)");
			}
			out.append(". Feed them (a root feed of ").append(String.join(" or ", this.entities)).append(" on ").append(this.landingTableKey).append(", one row = one ")
				.append(this.oneRowIs()).append(") or say in a note why not.");
			return out.toString();
		}
	}

	/**
	 * The landing tables whose identity columns feed no entity that holds the identity of the other tables.
	 * <p>
	 * For a table T with at least one identity column: the entities that read an identity column of some OTHER table (a feed of an earlier run, whose columns are
	 * unknown, reading such a table counts as reading them) are the holders; when there is no holder there is nothing to compare with and no gap; when a holder
	 * already has a feed on T, T is fed; otherwise T is a gap.
	 *
	 * @param pTables the landing tables by key, with their columns' tags (and, when measured, their identity figures)
	 * @param pReads every live feed of the proposal (this run's and the earlier runs' not rejected ones)
	 */
	public static List<Gap> gaps(final Map<String, StagingRules.LandingTableInfo> pTables, final List<FeedRead> pReads)
	{
		final Map<String, Set<String>> identityFieldsByTable = new LinkedHashMap<String, Set<String>>();
		for (final Map.Entry<String, StagingRules.LandingTableInfo> entry : pTables.entrySet())
		{
			final Set<String> fields = new LinkedHashSet<String>();
			for (final StagingRules.LandingColumnInfo column : entry.getValue().columns)
			{
				if (IdentityFacts.kindOf(column) != null)
				{
					fields.add(column.fieldName);
				}
			}
			if (!fields.isEmpty())
			{
				identityFieldsByTable.put(entry.getKey(), fields);
			}
		}
		final List<Gap> out = new ArrayList<Gap>();
		for (final Map.Entry<String, Set<String>> own : identityFieldsByTable.entrySet())
		{
			final String tableKey = own.getKey();
			final Set<String> holders = new LinkedHashSet<String>();
			final Set<String> rootHolders = new LinkedHashSet<String>();
			final Set<String> fedEntities = new LinkedHashSet<String>();
			for (final FeedRead read : pReads)
			{
				if (read.landingTableKey.equals(tableKey))
				{
					fedEntities.add(read.entity);
					continue;
				}
				final Set<String> identityFields = identityFieldsByTable.get(read.landingTableKey);
				if (identityFields == null || !IdentityCoverage.readsAny(read, identityFields))
				{
					continue;
				}
				holders.add(read.entity);
				if (read.root)
				{
					rootHolders.add(read.entity);
				}
			}
			if (holders.isEmpty())
			{
				continue;
			}
			boolean fed = false;
			for (final String holder : holders)
			{
				if (fedEntities.contains(holder))
				{
					fed = true;
					break;
				}
			}
			if (fed)
			{
				continue;
			}
			out.add(IdentityCoverage.gapOf(tableKey, pTables.get(tableKey), rootHolders.isEmpty() ? holders : rootHolders));
		}
		return out;
	}

	private static boolean readsAny(final FeedRead pRead, final Set<String> pFields)
	{
		if (pRead.columns == null)
		{
			return true;
		}
		for (final String field : pFields)
		{
			if (pRead.columns.contains(field))
			{
				return true;
			}
		}
		return false;
	}

	private static Gap gapOf(final String pTableKey, final StagingRules.LandingTableInfo pTable, final Set<String> pEntities)
	{
		final List<String> tags = new ArrayList<String>();
		final List<String> columns = new ArrayList<String>();
		final List<IdentityFacts.Kind> kinds = new ArrayList<IdentityFacts.Kind>();
		final List<String> keyColumns = new ArrayList<String>();
		final List<String> bases = new ArrayList<String>();
		final List<String> shapes = new ArrayList<String>();
		for (final StagingRules.LandingColumnInfo column : pTable.columns)
		{
			final IdentityFacts.Kind kind = IdentityFacts.kindOf(column);
			if (kind != null)
			{
				columns.add(column.fieldName);
				if (IdentityFacts.shapeOnly(column))
				{
					shapes.add(column.fieldName + " (" + IdentityFacts.shapeWords(column) + ")");
				}
				for (final String word : IdentityFacts.tagWords(column))
				{
					if (!tags.contains(word))
					{
						tags.add(word);
					}
				}
				final String basis = IdentityFacts.basisOf(column);
				if (basis != null && !bases.contains(basis))
				{
					bases.add(basis);
				}
				if (!kinds.contains(kind))
				{
					kinds.add(kind);
				}
			}
			if (column.sourceKey)
			{
				keyColumns.add(column.fieldName);
			}
		}
		final List<String> order = java.util.Arrays.asList("APPLIED", "SAMPLED", "NAMES", "MODEL", "OTHER", IdentityFacts.BASIS_SHAPES);
		java.util.Collections.sort(bases, new java.util.Comparator<String>()
		{
			@Override
			public int compare(final String pLeft, final String pRight)
			{
				return Integer.compare(order.indexOf(pLeft), order.indexOf(pRight));
			}
		});
		java.util.Collections.sort(kinds); // enum order: the same words in the same order whatever the column order
		final IdentityFacts.Table measured = pTable.identity;
		final Long rows = pTable.rows();
		return new Gap(pTableKey, pTable.name, measured == null ? -1L : measured.rowsWithIdentity, rows == null ? -1L : rows.longValue(), tags, bases, columns, kinds,
			measured == null ? new java.util.EnumMap<IdentityFacts.Kind, Long>(IdentityFacts.Kind.class) : measured.seenNowhereElse, measured != null && measured.crossSkipped,
			new ArrayList<String>(pEntities), keyColumns, shapes);
	}

	// ------------------------------------------------------------------------------------- lot F2: coverage of an entity

	/**
	 * Lot F2 (2026-10-19): how much of the landing a target entity the model proposed is fed from - a count, not a verdict. For an entity whose feeds read at least one identity
	 * column ({@link IdentityFacts#kindOf}: tagged by the catalogue or measured from the value shapes), the identity kinds those columns have are "its kind of columns"; the landing tables
	 * carrying a column of one of those kinds are the tables that could hold records of the entity, and the entity is fed by the ones it has a feed on.
	 * {@code Client is fed by 2 of the 3 landing tables that carry its kind of columns (e-mail, phone); it would receive 300 rows (WEB.ECOM 160, LOY.MEMBERS 140; SHOP.STORES 234 not fed).}
	 * The model may answer with more {@code propose_feed} calls or with a note, and either stands.
	 */
	public static final class EntityCoverage
	{
		public final String entity;
		/** The identity kinds the entity's feeds read, in {@link IdentityFacts.Kind} order. */
		public final List<IdentityFacts.Kind> kinds;
		/** Landing table keys carrying a column of one of {@link #kinds}, in landing order. */
		public final List<String> carrying;
		/** The ones of {@link #carrying} some feed of the entity reads (a feed of this run or a live one of an earlier run). */
		public final List<String> fed;
		/** Rows of each carrying table (the landing table's rows); a table whose rows are unknown has no entry. */
		public final Map<String, Long> rows;

		EntityCoverage(final String pEntity, final List<IdentityFacts.Kind> pKinds, final List<String> pCarrying, final List<String> pFed, final Map<String, Long> pRows)
		{
			this.entity = pEntity;
			this.kinds = pKinds;
			this.carrying = pCarrying;
			this.fed = pFed;
			this.rows = pRows;
		}

		/** The carrying tables the entity has no feed on, in landing order. */
		public List<String> notFed()
		{
			final List<String> out = new ArrayList<String>(this.carrying);
			out.removeAll(this.fed);
			return out;
		}

		/** True when some table carrying the entity's kind of columns feeds it nothing. */
		public boolean partial()
		{
			return this.fed.size() < this.carrying.size();
		}

		/** Rows of the fed tables whose rows are known. */
		public long rowsReceived()
		{
			long total = 0L;
			for (final String key : this.fed)
			{
				final Long rows = this.rows.get(key);
				total += rows == null ? 0L : rows.longValue();
			}
			return total;
		}

		/** True when the rows of some fed table are unknown, so {@link #rowsReceived} is a lower bound. */
		public boolean rowsReceivedIsLowerBound()
		{
			for (final String key : this.fed)
			{
				if (!this.rows.containsKey(key))
				{
					return true;
				}
			}
			return false;
		}

		/** {@code WEB.ECOM 160} / {@code WEB.ECOM (rows unknown)}: one table of the breakdown. */
		public String tableText(final String pKey)
		{
			final Long rows = this.rows.get(pKey);
			return rows == null ? pKey + " (rows unknown)" : pKey + " " + rows;
		}

		/** {@code WEB.ECOM 160, LOY.MEMBERS 140; SHOP.STORES 234 not fed}. */
		public String breakdownText()
		{
			final List<String> fedParts = new ArrayList<String>();
			for (final String key : this.fed)
			{
				fedParts.add(this.tableText(key));
			}
			final List<String> unfedParts = new ArrayList<String>();
			for (final String key : this.notFed())
			{
				unfedParts.add(this.tableText(key) + " not fed");
			}
			final String fedText = String.join(", ", fedParts);
			final String unfedText = String.join(", ", unfedParts);
			return fedText.isEmpty() ? unfedText : unfedText.isEmpty() ? fedText : fedText + "; " + unfedText;
		}

		/** {@code e-mail, phone, name}: the kinds of columns the entity's feeds read. */
		public String kindsText()
		{
			final List<String> words = new ArrayList<String>();
			for (final IdentityFacts.Kind kind : this.kinds)
			{
				words.add(kind.singular());
			}
			return String.join(", ", words);
		}

		/** The feedback sentence, for the follow-up turn. */
		public String followUpText()
		{
			final long received = this.rowsReceived();
			return this.entity + " is fed by " + this.fed.size() + " of the " + this.carrying.size() + " landing tables that carry its kind of columns (" + this.kindsText()
				+ "); it would receive " + (this.rowsReceivedIsLowerBound() ? "at least " : "") + received + " row" + (received == 1L ? "" : "s") + " (" + this.breakdownText() + ").";
		}
	}

	/**
	 * The coverage of every entity the proposal feeds, in the order the entities first appear in {@code pReads}. An entity none of whose feeds reads an identity column has no coverage
	 * (there is no kind of columns to compare), nor does one with fewer than two carrying tables (nothing to compare with). A feed whose read columns are unknown ({@link FeedRead#columns}
	 * null, a feed of an earlier run) counts as reading every column of its table.
	 */
	public static List<EntityCoverage> entityCoverage(final Map<String, StagingRules.LandingTableInfo> pTables, final List<FeedRead> pReads)
	{
		final Map<String, List<FeedRead>> byEntity = new LinkedHashMap<String, List<FeedRead>>();
		for (final FeedRead read : pReads)
		{
			List<FeedRead> list = byEntity.get(read.entity);
			if (list == null)
			{
				list = new ArrayList<FeedRead>();
				byEntity.put(read.entity, list);
			}
			list.add(read);
		}
		final List<EntityCoverage> out = new ArrayList<EntityCoverage>();
		for (final Map.Entry<String, List<FeedRead>> entry : byEntity.entrySet())
		{
			final Set<IdentityFacts.Kind> kindSet = new LinkedHashSet<IdentityFacts.Kind>();
			final Set<String> readTables = new LinkedHashSet<String>();
			for (final FeedRead read : entry.getValue())
			{
				final StagingRules.LandingTableInfo table = pTables.get(read.landingTableKey);
				if (table == null)
				{
					continue;
				}
				readTables.add(read.landingTableKey);
				for (final StagingRules.LandingColumnInfo column : table.columns)
				{
					final IdentityFacts.Kind kind = IdentityFacts.kindOf(column);
					if (kind != null && (read.columns == null || read.columns.contains(column.fieldName)))
					{
						kindSet.add(kind);
					}
				}
			}
			if (kindSet.isEmpty())
			{
				continue;
			}
			final List<IdentityFacts.Kind> kinds = new ArrayList<IdentityFacts.Kind>(kindSet);
			java.util.Collections.sort(kinds);
			final List<String> carrying = new ArrayList<String>();
			final List<String> fed = new ArrayList<String>();
			final Map<String, Long> rows = new LinkedHashMap<String, Long>();
			for (final Map.Entry<String, StagingRules.LandingTableInfo> table : pTables.entrySet())
			{
				boolean carries = false;
				for (final StagingRules.LandingColumnInfo column : table.getValue().columns)
				{
					carries |= kindSet.contains(IdentityFacts.kindOf(column));
				}
				if (!carries)
				{
					continue;
				}
				carrying.add(table.getKey());
				if (readTables.contains(table.getKey()))
				{
					fed.add(table.getKey());
				}
				final Long tableRows = table.getValue().rows();
				if (tableRows != null)
				{
					rows.put(table.getKey(), tableRows);
				}
			}
			if (carrying.size() >= 2)
			{
				out.add(new EntityCoverage(entry.getKey(), kinds, carrying, fed, rows));
			}
		}
		return out;
	}
}
