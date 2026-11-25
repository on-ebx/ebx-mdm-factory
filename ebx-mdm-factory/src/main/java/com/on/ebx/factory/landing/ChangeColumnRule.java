package com.on.ebx.factory.landing;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Volume wave 2, lot V2-E (2026-10-03, docs/VOLUME.md "Wave 2 - landing watermark"): which source column of a
 * landing table says that a ROW changed, so that a reload can read only the rows changed since the last
 * successful load (the Connectors feed watermark, ebx-connectors docs/REFERENCE.md 2.14.7).
 * <p>
 * Deliberately a deterministic rule over what the catalog already knows about each column - its name, its
 * normalised type and, when profiled, its null rate - and never a guess from data and never a new language-
 * model call. When nothing qualifies the answer is {@code null} and the table stays a FULL reload, which is
 * always correct, only slower. Pure: no EBX class.
 * <p>
 * <b>Why the lists are short.</b> A wrong watermark does not fail, it silently misses rows. So a column
 * qualifies only if its name is one of the well-known "last changed" markers AND it is of the matching type,
 * and a few tempting candidates are left out on purpose: a creation date (rows updated later are never seen
 * again), an auto-increment {@code ID} (growing for inserts, blind to updates), a bare {@code VERSION} or
 * {@code REVISION} (usually a per-row optimistic-lock counter: row A at 5 and row B at 2 is not "B is older"),
 * and a bare {@code SEQ} (often a line number inside a document). A column that the catalog PROFILED as holding
 * NULLs is refused too: {@code >=} never returns a NULL, so such a row would never be read after the first load.
 * A person can still pick any column in the wizard.
 */
public final class ChangeColumnRule
{
	public static final String TIMESTAMP = "TIMESTAMP";
	public static final String MONOTONIC_ID = "MONOTONIC_ID";
	public static final String ORDERED_STRING = "ORDERED_STRING";

	/**
	 * Date/timestamp markers, upper case with every non-alphanumeric character removed (see {@link #normalise}),
	 * best first: when two columns qualify the one whose name comes first here wins, then the first in table order.
	 */
	static final List<String> TIMESTAMP_NAMES = Collections.unmodifiableList(Arrays.asList(
		"LASTMODIFIED", "LASTMODIFIEDDATE", "LASTMODIFIEDDATETIME", "LASTMODIFIEDAT", "LASTMODIFIEDON", "LASTMODIFIEDTIME", "LASTMODIFIEDTS", "SYSTEMMODSTAMP",
		"MODIFIED", "MODIFIEDDATE", "MODIFIEDAT", "MODIFIEDON", "MODIFIEDTIME", "MODIFICATIONDATE", "MODIFICATIONTIME", "DATEMODIFICATION", "DATEMODIFIED", "DATEMODIF",
		"UPDATEDAT", "UPDATEDON", "UPDATED", "UPDATEDATE", "UPDATETIME", "UPDATEDTIME", "UPDATEDDATE", "UPDATEDTS", "LASTUPDATED", "LASTUPDATEDAT", "LASTUPDATEDON",
		"LASTUPDATE", "LASTUPDATEDATE", "LASTUPDATETIME", "DATEUPDATE", "DATEUPDATED",
		"CHANGEDON", "CHANGEDAT", "CHANGEDATE", "CHANGETIME", "CHANGEDTIME", "LASTCHANGED", "LASTCHANGEDATE", "LASTCHANGEDAT", "LASTCHANGEDON", "LASTCHANGE", "AEDAT",
		"DTMAJ", "DATEMAJ", "DTMODIF", "DTMODIFICATION", "DTUPDATE", "DTUPDATED", "ETLUPDATED", "ETLUPDATEDAT"));

	/** Number markers that only ever grow source-wide (a SQL Server {@code rowversion}, a change sequence, a log sequence number), same normalisation and order rule. */
	static final List<String> NUMBER_NAMES = Collections.unmodifiableList(Arrays.asList(
		"ROWVERSION", "CHANGESEQ", "CHANGESEQUENCE", "CHANGESEQNO", "CHANGENUMBER", "CHANGEID", "CHANGEVERSION", "UPDATESEQ", "MODSEQ", "LSN", "SCN", "ORASCN"));

	private static final Set<String> TEMPORAL_TYPES = new HashSet<String>(Arrays.asList("TIMESTAMP", "DATE"));
	private static final Set<String> NUMERIC_TYPES = new HashSet<String>(Arrays.asList("INTEGER", "DECIMAL"));

	private ChangeColumnRule()
	{
	}

	/** One column of the table as the catalog knows it. */
	public static final class Candidate
	{
		public final String name;
		/** The catalog's normalised type ({@code TIMESTAMP}, {@code DATE}, {@code INTEGER}, {@code DECIMAL}, {@code STRING}...), as {@code LandingColumn.inferredType} holds it; case-insensitive. */
		public final String type;
		/** 0-1 share of NULL values when the catalog profiled the column, {@code null} when it never did. */
		public final BigDecimal nullRate;

		public Candidate(final String pName, final String pType, final BigDecimal pNullRate)
		{
			this.name = pName;
			this.type = pType;
			this.nullRate = pNullRate;
		}
	}

	/** The column picked and why ({@link #kind} is one of {@link #TIMESTAMP} / {@link #MONOTONIC_ID}). */
	public static final class Choice
	{
		public final String column;
		public final String kind;
		public final String reason;

		Choice(final String pColumn, final String pKind, final String pReason)
		{
			this.column = pColumn;
			this.kind = pKind;
			this.reason = pReason;
		}
	}

	/** Upper case, every character that is not a letter or a digit dropped: {@code last_modified}, {@code LastModified} and {@code _LAST-MODIFIED_} all read {@code LASTMODIFIED}. */
	static String normalise(final String pName)
	{
		final String name = pName == null ? "" : pName.toUpperCase(Locale.ROOT);
		final StringBuilder out = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++)
		{
			final char c = name.charAt(i);
			if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')
			{
				out.append(c);
			}
		}
		return out.toString();
	}

	/**
	 * The change column of a table, or {@code null} (reload in full). A date/timestamp column with a marker name
	 * beats a number column with a marker name; within one family the marker list's own order decides, then the
	 * table's column order. A column profiled with NULLs ({@code nullRate > 0}) never qualifies.
	 */
	public static Choice choose(final List<Candidate> pColumns)
	{
		if (pColumns == null)
		{
			return null;
		}
		final Choice timestamp = ChangeColumnRule.best(pColumns, ChangeColumnRule.TIMESTAMP_NAMES, ChangeColumnRule.TEMPORAL_TYPES, ChangeColumnRule.TIMESTAMP);
		if (timestamp != null)
		{
			return timestamp;
		}
		return ChangeColumnRule.best(pColumns, ChangeColumnRule.NUMBER_NAMES, ChangeColumnRule.NUMERIC_TYPES, ChangeColumnRule.MONOTONIC_ID);
	}

	private static Choice best(final List<Candidate> pColumns, final List<String> pNames, final Set<String> pTypes, final String pKind)
	{
		Choice best = null;
		int bestRank = Integer.MAX_VALUE;
		for (final Candidate column : pColumns)
		{
			if (column == null || column.name == null || column.type == null || !pTypes.contains(column.type.trim().toUpperCase(Locale.ROOT)))
			{
				continue;
			}
			if (column.nullRate != null && column.nullRate.signum() > 0)
			{
				continue;
			}
			final int rank = pNames.indexOf(ChangeColumnRule.normalise(column.name));
			if (rank >= 0 && rank < bestRank)
			{
				bestRank = rank;
				best = new Choice(column.name, pKind, "Column " + column.name + " is a " + column.type.trim().toLowerCase(Locale.ROOT) + " named like a change marker"
					+ (column.nullRate == null ? " (null rate not profiled)" : " and holds no NULL") + ".");
			}
		}
		return best;
	}

	/** Whether {@code pKind} is one of the three kinds a change column can have. */
	public static boolean isKind(final String pKind)
	{
		return ChangeColumnRule.TIMESTAMP.equals(pKind) || ChangeColumnRule.MONOTONIC_ID.equals(pKind) || ChangeColumnRule.ORDERED_STRING.equals(pKind);
	}

	/** The kind a column of this normalised type is read as when a person picks it without saying: dates compare as dates, numbers as numbers, anything else as plain text order. */
	public static String kindFor(final String pType)
	{
		final String type = pType == null ? "" : pType.trim().toUpperCase(Locale.ROOT);
		if (ChangeColumnRule.TEMPORAL_TYPES.contains(type))
		{
			return ChangeColumnRule.TIMESTAMP;
		}
		if (ChangeColumnRule.NUMERIC_TYPES.contains(type))
		{
			return ChangeColumnRule.MONOTONIC_ID;
		}
		return ChangeColumnRule.ORDERED_STRING;
	}

	/**
	 * Whether the next load of a table must IGNORE its stored checkpoint ({@code fullRefresh=true}) - the Connectors
	 * checkpoint is not tied to a column ("changing checkpointValuePath does not invalidate the stored value"), so the
	 * Factory has to notice by itself:
	 * <ul>
	 * <li>the change column is not the one the last successful load read through ({@code pLoadedColumn}: never loaded,
	 * or the person picked another column, or the table moved between full and incremental) - the stored value, if any,
	 * belongs to something else;</li>
	 * <li>the landing table is EMPTY ({@code pLandingRows == 0}) although a checkpoint may exist - a key change, a rebuilt
	 * dataset or a step reset emptied it, and an incremental read would put only the latest changes into it. {@code
	 * pLandingRows} is negative when it could not be counted: no decision from it.</li>
	 * </ul>
	 * A table with no change column is a FULL reload anyway: never a full refresh request.
	 */
	public static boolean fullRefreshNeeded(final String pChangeColumn, final String pLoadedColumn, final long pLandingRows)
	{
		final String column = pChangeColumn == null ? "" : pChangeColumn.trim();
		if (column.isEmpty())
		{
			return false;
		}
		final String loaded = pLoadedColumn == null ? "" : pLoadedColumn.trim();
		return !column.equals(loaded) || pLandingRows == 0L;
	}

	/**
	 * What to write in {@code LandingTable.changeColumnLoaded} once a load has ended: {@code null} leaves it as it was (the
	 * load failed, or ended in a state where the checkpoint was NOT reset to this column - {@link
	 * WatermarkOutcome#checkpointSettled}); otherwise the column the load read through, empty when the table is loaded in full.
	 */
	public static String columnToRecord(final String pChangeColumn, final boolean pLoadOk, final WatermarkOutcome pWatermark, final long pRowsRead)
	{
		if (!pLoadOk || pWatermark != null && !pWatermark.checkpointSettled(pRowsRead))
		{
			return null;
		}
		return pChangeColumn == null ? "" : pChangeColumn.trim();
	}

	/** {@code ok} with the column and kind to store (both empty when the person chose "none, reload in full"), or a {@link #message} saying what was wrong. */
	public static final class Validation
	{
		public final boolean ok;
		public final String message;
		public final String column;
		public final String kind;

		private Validation(final boolean pOk, final String pMessage, final String pColumn, final String pKind)
		{
			this.ok = pOk;
			this.message = pMessage;
			this.column = pColumn;
			this.kind = pKind;
		}
	}

	/**
	 * A person's choice in the wizard. A blank column is "none": the table is reloaded in full. Otherwise the
	 * column must be one of the table's own columns ({@code pColumnTypes} maps each source column to its
	 * normalised type; its key set is the table's column list) and the kind, when blank, is {@link #kindFor} its type.
	 */
	public static Validation validate(final Map<String, String> pColumnTypes, final String pColumn, final String pKind)
	{
		final String column = pColumn == null ? "" : pColumn.trim();
		if (column.isEmpty())
		{
			return new Validation(true, null, "", "");
		}
		final Map<String, String> types = pColumnTypes == null ? new LinkedHashMap<String, String>() : pColumnTypes;
		if (!types.containsKey(column))
		{
			return new Validation(false, "'" + column + "' is not a column of this table.", "", "");
		}
		final String kindText = pKind == null ? "" : pKind.trim();
		if (!kindText.isEmpty() && !ChangeColumnRule.isKind(kindText))
		{
			return new Validation(false, "'" + kindText + "' is not a kind of change column (TIMESTAMP, MONOTONIC_ID or ORDERED_STRING).", "", "");
		}
		return new Validation(true, null, column, kindText.isEmpty() ? ChangeColumnRule.kindFor(types.get(column)) : kindText);
	}
}
