package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.List;

/**
 * Landing-walkthrough lot (2026-10-16, docs/mockups/landing-walkthrough/): the pure rules behind step 3 shown as a walkthrough, one application at a time and the
 * launch at the end - which page is current, what each table's key is worth, when "Validate" and the launch button are open and why not. No EBX class: tested in
 * {@code LandingWalkTest}. The page itself is {@link LandingWalkPage}.
 */
public final class LandingWalk
{
	/** The page id of the launch page (the others are {@code ProjectSource} ids). */
	public static final String LAUNCH = "LAUNCH";
	/** A key proposed by the language model under this confidence is a proposal, not an applied key ({@link com.on.ebx.factory.landing.KeyCertainty#LLM_CONFIDENCE_MIN}). */
	public static final int LLM_CONFIDENCE_MIN = com.on.ebx.factory.landing.KeyCertainty.LLM_CONFIDENCE_MIN;

	private LandingWalk()
	{
	}

	public enum KeyKind
	{
		/** The source declares the key. */
		DECLARED,
		/** A person chose (or confirmed) it. */
		CHOSEN,
		/** Applied by the naming rule or the language model, checked at the load. */
		DEDUCED,
		/** No key (a proposal below the certainty bar is not a key). */
		NONE
	}

	/** What the key column of a table shows. */
	public static final class KeyState
	{
		public final KeyKind kind;
		/** The key columns, comma-joined (empty for {@link KeyKind#NONE}). */
		public final String columns;
		/** Confidence of a {@link KeyKind#DEDUCED} key, 0-100. */
		public final int confidence;
		/** For {@link KeyKind#NONE}: the columns of a proposal that was not applied, comma-joined; empty when there is none. */
		public final String proposal;
		/** For {@link KeyKind#NONE}: the confidence of that proposal (0 when unknown). */
		public final int proposalConfidence;

		KeyState(final KeyKind pKind, final String pColumns, final int pConfidence, final String pProposal, final int pProposalConfidence)
		{
			this.kind = pKind;
			this.columns = pColumns;
			this.confidence = pConfidence;
			this.proposal = pProposal;
			this.proposalConfidence = pProposalConfidence;
		}
	}

	/** The key of one table, as the walkthrough shows it. */
	public static KeyState keyState(final WizardSnapshot.TableInfo pTable)
	{
		final String columns = pTable.keyColumns == null ? "" : pTable.keyColumns;
		final String origin = pTable.keyOrigin == null ? "" : pTable.keyOrigin;
		if (!columns.isEmpty())
		{
			if ("SOURCE".equals(origin))
			{
				return new KeyState(KeyKind.DECLARED, columns, 100, "", 0);
			}
			if ("USER".equals(origin) || "DEDUCED".equals(origin))
			{
				return new KeyState(KeyKind.CHOSEN, columns, 100, "", 0);
			}
			if ("LLM".equals(origin) && pTable.keyConfidence < LandingWalk.LLM_CONFIDENCE_MIN)
			{
				// the language model's proposal under the certainty bar: kept on the table, not applied - the person decides
				return new KeyState(KeyKind.NONE, "", 0, columns, pTable.keyConfidence);
			}
			return new KeyState(KeyKind.DEDUCED, columns, pTable.keyConfidence, "", 0);
		}
		if (!pTable.declaredKeyColumns.isEmpty())
		{
			return new KeyState(KeyKind.DECLARED, LandingWalk.join(pTable.declaredKeyColumns), 100, "", 0);
		}
		return new KeyState(KeyKind.NONE, "", 0, "", pTable.pendingKeyConfidence);
	}

	/** Whether the table's rows are known to be none: the walkthrough folds such a table behind "See the N empty tables". */
	public static boolean isEmpty(final WizardSnapshot.TableInfo pTable)
	{
		return pTable.sourceRowCountKnown && pTable.sourceRowCount == 0L && pTable.lastLoadRows == 0L;
	}

	/**
	 * The tables of an application that sit in the main list: the ticked ones first (rows descending, as step 2 lists them), then the not-kept ones (landing-walkthrough
	 * display fix, 2026-10-17): the schemas of the ticked tables first, tables with a key before tables with none, then by name. Unticked empty tables and unticked sample
	 * data sets are not here ({@link #emptyTables}, {@link #sampleTables}).
	 */
	public static List<WizardSnapshot.TableInfo> listed(final WizardSnapshot.SourceInfo pSource)
	{
		final List<WizardSnapshot.TableInfo> ticked = new ArrayList<WizardSnapshot.TableInfo>();
		final List<WizardSnapshot.TableInfo> notKept = new ArrayList<WizardSnapshot.TableInfo>();
		final java.util.Set<String> tickedSchemas = new java.util.HashSet<String>();
		for (final WizardSnapshot.TableInfo table : SourceTableList.order(pSource.tables))
		{
			if (table.selected)
			{
				ticked.add(table);
				tickedSchemas.add(LandingWalk.schemaOf(table));
			}
		}
		for (final WizardSnapshot.TableInfo table : pSource.tables)
		{
			if (!table.selected && !LandingWalk.isEmpty(table) && !LandingWalk.isSampleDataSet(table))
			{
				notKept.add(table);
			}
		}
		java.util.Collections.sort(notKept, new java.util.Comparator<WizardSnapshot.TableInfo>()
		{
			@Override
			public int compare(final WizardSnapshot.TableInfo pA, final WizardSnapshot.TableInfo pB)
			{
				final int schema = Integer.compare(tickedSchemas.contains(LandingWalk.schemaOf(pA)) ? 0 : 1, tickedSchemas.contains(LandingWalk.schemaOf(pB)) ? 0 : 1);
				if (schema != 0)
				{
					return schema;
				}
				final int key = Integer.compare(LandingWalk.keyState(pA).kind == KeyKind.NONE ? 1 : 0, LandingWalk.keyState(pB).kind == KeyKind.NONE ? 1 : 0);
				return key != 0 ? key : pA.tableName.compareToIgnoreCase(pB.tableName);
			}
		});
		return LandingWalk.concat(ticked, notKept);
	}

	/** The unticked, non-empty tables recognised as a sample data set: folded in their own group, last. */
	public static List<WizardSnapshot.TableInfo> sampleTables(final WizardSnapshot.SourceInfo pSource)
	{
		final List<WizardSnapshot.TableInfo> out = new ArrayList<WizardSnapshot.TableInfo>();
		for (final WizardSnapshot.TableInfo table : pSource.tables)
		{
			if (!table.selected && !LandingWalk.isEmpty(table) && LandingWalk.isSampleDataSet(table))
			{
				out.add(table);
			}
		}
		java.util.Collections.sort(out, new java.util.Comparator<WizardSnapshot.TableInfo>()
		{
			@Override
			public int compare(final WizardSnapshot.TableInfo pA, final WizardSnapshot.TableInfo pB)
			{
				return pA.tableName.compareToIgnoreCase(pB.tableName);
			}
		});
		return out;
	}

	/** The unticked empty tables of an application. */
	public static List<WizardSnapshot.TableInfo> emptyTables(final WizardSnapshot.SourceInfo pSource)
	{
		final List<WizardSnapshot.TableInfo> out = new ArrayList<WizardSnapshot.TableInfo>();
		for (final WizardSnapshot.TableInfo table : SourceTableList.order(pSource.tables))
		{
			if (!table.selected && LandingWalk.isEmpty(table))
			{
				out.add(table);
			}
		}
		return out;
	}

	/**
	 * The fact behind a table's "Why" cell, as a message key suffix ({@code wizard.walk.why.<suffix>}), or null when the data holds none. Only facts the data holds:
	 * the table is empty, it was not ticked at the Sources step, it is a sample data set. Never a business reason.
	 */
	public static String why(final WizardSnapshot.SourceInfo pSource, final WizardSnapshot.TableInfo pTable)
	{
		if (LandingWalk.isEmpty(pTable))
		{
			return "empty";
		}
		// landing test fixes (2026-10-17): the columns of one document holding a list (customers[0].x): the reader did not find the rows
		if (com.on.ebx.factory.landing.FlattenedDocument.looksFlattened(pTable.columnNames))
		{
			return "flattened";
		}
		if (!pTable.selected)
		{
			return "notTicked";
		}
		return "sample-file".equals(pSource.platform) ? "sample" : null;
	}

	public static int tickedTables(final WizardSnapshot.SourceInfo pSource)
	{
		return SourceTableList.ticked(pSource.tables);
	}

	/** Rows of the ticked tables that are known (a table whose count is unknown adds nothing; {@link #unknownRows} says how many). */
	public static long tickedRows(final WizardSnapshot.SourceInfo pSource)
	{
		long total = 0;
		for (final WizardSnapshot.TableInfo table : pSource.tables)
		{
			if (table.selected)
			{
				final TableRowCount rows = SourceTableList.rows(table);
				if (rows.known)
				{
					total += rows.value;
				}
			}
		}
		return total;
	}

	/** How many ticked tables have no known row count. */
	public static int unknownRows(final WizardSnapshot.SourceInfo pSource)
	{
		int count = 0;
		for (final WizardSnapshot.TableInfo table : pSource.tables)
		{
			if (table.selected && !SourceTableList.rows(table).known)
			{
				count++;
			}
		}
		return count;
	}

	/** Ticked tables of the application whose key is deduced or missing: what makes the language-model choice relevant on its page. */
	public static int tablesWithUncertainKey(final WizardSnapshot.SourceInfo pSource)
	{
		int count = 0;
		for (final WizardSnapshot.TableInfo table : pSource.tables)
		{
			if (table.selected)
			{
				final KeyKind kind = LandingWalk.keyState(table).kind;
				if (kind == KeyKind.DEDUCED || kind == KeyKind.NONE)
				{
					count++;
				}
			}
		}
		return count;
	}

	/** Ticked tables of the application that have no key. */
	public static int tablesWithoutKey(final WizardSnapshot.SourceInfo pSource)
	{
		int count = 0;
		for (final WizardSnapshot.TableInfo table : pSource.tables)
		{
			if (table.selected && LandingWalk.keyState(table).kind == KeyKind.NONE)
			{
				count++;
			}
		}
		return count;
	}

	/** The message key saying why "Validate" is closed for the application, or null when it is open: an application with no ticked table cannot be validated. */
	public static String validateReasonKey(final WizardSnapshot.SourceInfo pSource)
	{
		return LandingWalk.tickedTables(pSource) == 0 ? "wizard.walk.validate.needTable" : null;
	}

	/** Ticked tables over the whole project. */
	public static int tickedTables(final WizardSnapshot pSnapshot)
	{
		int count = 0;
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			count += LandingWalk.tickedTables(source);
		}
		return count;
	}

	public static boolean allValidated(final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			if (!source.landingValidated)
			{
				return false;
			}
		}
		return !pSnapshot.sources.isEmpty();
	}

	/** The id of the first application not validated yet, or null when every one is. */
	public static String firstToValidate(final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			if (!source.landingValidated)
			{
				return source.id;
			}
		}
		return null;
	}

	/**
	 * Why the launch button is closed, as a message key ({@code wizard.walk.launch.*}), or null when it is open: every application must be validated and at least one
	 * table ticked overall.
	 */
	public static String launchReasonKey(final WizardSnapshot pSnapshot)
	{
		if (LandingWalk.firstToValidate(pSnapshot) != null)
		{
			return "wizard.walk.launch.needValidation";
		}
		if (LandingWalk.tickedTables(pSnapshot) == 0)
		{
			return "wizard.walk.launch.needTable";
		}
		return null;
	}

	/** The page to show: {@code pRequested} when it is the launch page or an application of the project, else the first application to validate, else the launch page. */
	public static String resolvePage(final WizardSnapshot pSnapshot, final String pRequested)
	{
		if (LandingWalk.LAUNCH.equals(pRequested))
		{
			return LandingWalk.LAUNCH;
		}
		if (pRequested != null && !pRequested.isEmpty())
		{
			for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
			{
				if (source.id.equals(pRequested))
				{
					return pRequested;
				}
			}
		}
		final String first = LandingWalk.firstToValidate(pSnapshot);
		return first == null ? LandingWalk.LAUNCH : first;
	}

	/** The page after (+1) or before (-1) {@code pPage} in the walk: an application id, {@link #LAUNCH} after the last, null before the first. */
	public static String neighbour(final WizardSnapshot pSnapshot, final String pPage, final int pDelta)
	{
		final int count = pSnapshot.sources.size();
		int index = LandingWalk.LAUNCH.equals(pPage) ? count : -1;
		for (int i = 0; i < count; i++)
		{
			if (pSnapshot.sources.get(i).id.equals(pPage))
			{
				index = i;
			}
		}
		final int target = index + pDelta;
		if (index < 0 || target < 0)
		{
			return null;
		}
		return target >= count ? LandingWalk.LAUNCH : pSnapshot.sources.get(target).id;
	}

	/** The application of the given id, or null. */
	public static WizardSnapshot.SourceInfo source(final WizardSnapshot pSnapshot, final String pId)
	{
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			if (source.id.equals(pId))
			{
				return source;
			}
		}
		return null;
	}

	/**
	 * The application's own name (landing-walkthrough display fix, 2026-10-17): the connection it reads ({@code ProjectSource.connector}, its label when the snapshot has one,
	 * else the connector id), the source-system code only when there is no connector, then the row id.
	 */
	public static String baseName(final WizardSnapshot.SourceInfo pSource)
	{
		if (!pSource.connectorLabel.isEmpty())
		{
			return pSource.connectorLabel;
		}
		if (!pSource.connector.isEmpty())
		{
			return pSource.connector;
		}
		return pSource.sourceSystem.isEmpty() ? pSource.id : pSource.sourceSystem;
	}

	/** The name an application goes by on the walkthrough: {@link #baseName}, with its code added in brackets when another application of the project has the same name (several schemas of one account). */
	public static String name(final WizardSnapshot pSnapshot, final WizardSnapshot.SourceInfo pSource)
	{
		final String base = LandingWalk.baseName(pSource);
		if (pSource.sourceSystem.isEmpty() || pSource.sourceSystem.equals(base))
		{
			return base;
		}
		for (final WizardSnapshot.SourceInfo other : pSnapshot.sources)
		{
			if (other != pSource && base.equals(LandingWalk.baseName(other)))
			{
				return base + " (" + pSource.sourceSystem + ")";
			}
		}
		return base;
	}

	/** The source-system code shown as small secondary text beside the title: empty when it adds nothing (it is the name already, or there is none). */
	public static String code(final WizardSnapshot.SourceInfo pSource)
	{
		return pSource.sourceSystem.equals(LandingWalk.baseName(pSource)) ? "" : pSource.sourceSystem;
	}

	/** The path segments before the table's own name in a {@code DB.SCHEMA.TABLE} string, upper-cased, quotes removed. */
	private static List<String> prefixSegments(final String pPath)
	{
		final List<String> out = new ArrayList<String>();
		if (pPath != null)
		{
			final String[] parts = pPath.replace("\"", "").replace("`", "").split("[.]");
			for (int i = 0; i < parts.length - 1; i++)
			{
				out.add(parts[i].trim().toUpperCase(java.util.Locale.ROOT));
			}
		}
		return out;
	}

	/** The schema of the table (the segment just before its name in the source object, else in the table name), upper-cased, or empty. */
	public static String schemaOf(final WizardSnapshot.TableInfo pTable)
	{
		List<String> segments = LandingWalk.prefixSegments(pTable.sourceObject);
		if (segments.isEmpty())
		{
			segments = LandingWalk.prefixSegments(pTable.tableName);
		}
		return segments.isEmpty() ? "" : segments.get(segments.size() - 1);
	}

	/**
	 * Landing-walkthrough display fix (2026-10-17): the table belongs to one of Snowflake's sample databases, recognised by schema name only: SNOWFLAKE_SAMPLE_DATA, TPCH_SF*,
	 * TPCDS_SF*. (The file sample of a {@code sample-file} connection is the separate "sample" reason of {@link #why}.)
	 */
	public static boolean isSampleDataSet(final WizardSnapshot.TableInfo pTable)
	{
		final List<String> segments = LandingWalk.prefixSegments(pTable.sourceObject);
		segments.addAll(LandingWalk.prefixSegments(pTable.tableName));
		for (final String segment : segments)
		{
			if (segment.equals("SNOWFLAKE_SAMPLE_DATA") || segment.startsWith("TPCH_SF") || segment.startsWith("TPCDS_SF"))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether step 3 shows the walkthrough: before any landing run exists for the project (nothing running, no progress grid, not all ticked tables loaded) or when
	 * the person asked to review the applications.
	 */
	public static boolean shown(final boolean pReview, final boolean pRunning, final boolean pRunDone, final boolean pHasProgress)
	{
		return pReview || !(pRunning || pRunDone || pHasProgress);
	}

	private static String join(final List<String> pValues)
	{
		final StringBuilder out = new StringBuilder();
		for (final String value : pValues)
		{
			if (out.length() > 0)
			{
				out.append(',');
			}
			out.append(value);
		}
		return out.toString();
	}

	private static <T> List<T> concat(final List<T> pFirst, final List<T> pSecond)
	{
		final List<T> out = new ArrayList<T>(pFirst);
		out.addAll(pSecond);
		return out;
	}
}
