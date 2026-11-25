package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Proposes the relations between the source objects of ONE project source (one application): a column of
 * a table that looks like a foreign key to the single-column key of another table of the same source. Only
 * the tables of one call are ever compared to each other, so the caller must call {@link #propose} once per
 * project source, never across two applications of the same platform (ERP and CRM sharing one Snowflake
 * account, for instance) - a link between applications is a matching question, not a foreign key, and this
 * rule has no notion of "application" to enforce that on its own. Pure: no EBX class.
 * <p>
 * Trust order, highest first:
 * <ol>
 * <li>a column named exactly like the single-column key of another table (confidence 80);</li>
 * <li>a column whose name ends with that key name after a separator, in the same table (a self reference)
 * or another (confidence 65);</li>
 * <li>a column named {@code <TABLE>_ID} (or the singular of a plural table name) of another table whose key
 * is a bare {@code ID} (confidence 60).</li>
 * </ol>
 * Never proposed: the key column of a table pointing to itself; two candidates tied on the same child column
 * (the ambiguity is reported, nothing is proposed); a relation whose known data types are incompatible. A
 * column that names another table but that table has no single-column key to point to (composite or empty)
 * is reported in {@link Result#skipped} instead of silently dropped.
 * <p>
 * Name comparisons are word by word ({@link Words}: separators and camelCase humps split words, an
 * acronym run and a digit stay attached to their word), not on names flattened to bare letters: rule 2's
 * "ends with that key name after a separator" needs a real word boundary, not just a coincidental tail of
 * letters - flattened, {@code SUBACCOUNT_ID} would wrongly "end with" the key {@code ACCOUNT_ID}.
 */
public final class RelationRule
{
	private RelationRule()
	{
	}

	/** A column of a table, as known to the rule: its name and the catalog's normalised data type (null when unknown). */
	public static final class Column
	{
		public final String name;
		public final String dataType;

		public Column(final String pName, final String pDataType)
		{
			this.name = pName;
			this.dataType = pDataType;
		}
	}

	/** A table of one project source: its name (last part of the source object), its source key columns, and its columns. */
	public static final class Table
	{
		public final String name;
		/** In key order; empty when no key is known for this table. */
		public final List<String> keyColumns;
		public final List<Column> columns;

		public Table(final String pName, final List<String> pKeyColumns, final List<Column> pColumns)
		{
			this.name = pName;
			this.keyColumns = pKeyColumns;
			this.columns = pColumns;
		}
	}

	/** A proposed foreign key from {@code fromTable}'s columns to {@code toTable}'s columns. */
	public static final class Proposal
	{
		public final String fromTable;
		public final List<String> fromColumns;
		public final String toTable;
		public final List<String> toColumns;
		public final boolean selfReference;
		/** 0-100. */
		public final int confidence;
		public final String rationale;

		Proposal(final String pFromTable, final List<String> pFromColumns, final String pToTable, final List<String> pToColumns,
			final boolean pSelfReference, final int pConfidence, final String pRationale)
		{
			this.fromTable = pFromTable;
			this.fromColumns = pFromColumns;
			this.toTable = pToTable;
			this.toColumns = pToColumns;
			this.selfReference = pSelfReference;
			this.confidence = pConfidence;
			this.rationale = pRationale;
		}
	}

	/** A column the rule looked at but did not turn into a proposal, with why - for the plan's report, not for the user to guess at. */
	public static final class Skip
	{
		public final String table;
		public final String column;
		public final String reason;

		Skip(final String pTable, final String pColumn, final String pReason)
		{
			this.table = pTable;
			this.column = pColumn;
			this.reason = pReason;
		}
	}

	public static final class Result
	{
		public final List<Proposal> proposals;
		public final List<Skip> skipped;

		Result(final List<Proposal> pProposals, final List<Skip> pSkipped)
		{
			this.proposals = pProposals;
			this.skipped = pSkipped;
		}
	}

	public static Result propose(final List<Table> pTables)
	{
		final List<Proposal> proposals = new ArrayList<Proposal>();
		final List<Skip> skipped = new ArrayList<Skip>();
		for (final Table table : pTables)
		{
			for (final Column column : table.columns)
			{
				RelationRule.proposeColumn(pTables, table, column, proposals, skipped);
			}
		}
		return new Result(proposals, skipped);
	}

	private static void proposeColumn(final List<Table> pTables, final Table pTable, final Column pColumn, final List<Proposal> pProposals,
		final List<Skip> pSkipped)
	{
		// fix (2026-09-26, live bug: two tables both keyed on a column literally named "id" proposed a relation
		// to EACH OTHER, confidence 80 both ways - a cycle, and neither direction real). A measured, not a name,
		// fact: pColumn is this table's OWN entire single-column key - already established, independently of what
		// it happens to be called, by whatever proposed THIS table's key (a declared source key, or the naming
		// rule's own profile-backed match). A column that already identifies every row of its OWN table is not,
		// at the same time, a plausible copy of another table's identically-named identifier: excluding it here
		// is what stops the coincidence rather than trying to arbitrate a direction between two equally-named,
		// equally-"real" identifiers after the fact. (A rare legitimate shared-primary-key/inheritance pattern,
		// where a table's own key IS also meant as a foreign key, is not proposed by this rule any more either -
		// accepted trade-off, a person can still declare that relation by hand; see RelationRuleTest.)
		final boolean ownEntireKey = pTable.keyColumns.size() == 1 && pTable.keyColumns.get(0).equals(pColumn.name);
		final List<Candidate> candidates = new ArrayList<Candidate>();
		for (final Table target : pTables)
		{
			final boolean self = target == pTable;
			final int keySize = target.keyColumns.size();
			if (keySize == 1)
			{
				final String keyColumn = target.keyColumns.get(0);
				final boolean bareId = RelationRule.isBareId(keyColumn);
				if (!self && !ownEntireKey && RelationRule.sameKey(pColumn.name, keyColumn))
				{
					candidates.add(new Candidate(target, keyColumn, 80, false,
						"Column " + pColumn.name + " of " + pTable.name + " is named exactly like the key of " + target.name + " (" + keyColumn + ")."));
				}
				// a bare "ID" is a trivial one-word suffix of almost every identifier-shaped column name
				// (SUPPLIER_ID, CONTACT_ID, ...): matching it here would make rule 2 fire on nearly everything.
				// A bare-ID key is only ever matched by rule 3 below, which also requires the column to name
				// the table itself.
				else if (!bareId && RelationRule.endsWithKey(pColumn.name, keyColumn))
				{
					candidates.add(new Candidate(target, keyColumn, 65, self, "Column " + pColumn.name + " of " + pTable.name
						+ " ends with the key of " + (self ? "its own table" : target.name) + " (" + keyColumn + ")."));
				}
			}
			if (!self && RelationRule.namedLikeTableId(pColumn.name, target.name))
			{
				if (keySize == 1 && RelationRule.isBareId(target.keyColumns.get(0)))
				{
					candidates.add(new Candidate(target, target.keyColumns.get(0), 60, false,
						"Column " + pColumn.name + " of " + pTable.name + " is named like the identifier of " + target.name + ", whose key is a bare ID."));
				}
				else if (keySize != 1)
				{
					pSkipped.add(new Skip(pTable.name, pColumn.name, "Looks like a reference to " + target.name + " (named like its identifier), but "
						+ target.name + " has " + (keySize == 0 ? "no known key" : "a composite key") + ": no foreign key can be proposed to it."));
				}
			}
		}

		final List<Candidate> compatible = new ArrayList<Candidate>();
		for (final Candidate candidate : candidates)
		{
			if (RelationRule.typesCompatible(pColumn.dataType, RelationRule.dataTypeOf(candidate.table, candidate.keyColumn)))
			{
				compatible.add(candidate);
			}
		}
		if (compatible.isEmpty())
		{
			return;
		}

		int best = 0;
		for (final Candidate candidate : compatible)
		{
			if (candidate.confidence > best)
			{
				best = candidate.confidence;
			}
		}
		final List<Candidate> top = new ArrayList<Candidate>();
		for (final Candidate candidate : compatible)
		{
			if (candidate.confidence == best)
			{
				top.add(candidate);
			}
		}
		if (top.size() > 1)
		{
			final StringBuilder targets = new StringBuilder();
			for (final Candidate candidate : top)
			{
				if (targets.length() > 0)
				{
					targets.append(", ");
				}
				targets.append(candidate.table.name);
			}
			pSkipped.add(new Skip(pTable.name, pColumn.name,
				"Ambiguous: equally matches " + targets + " at confidence " + best + "; keeping none, pick the right one by hand."));
			return;
		}
		final Candidate winner = top.get(0);
		pProposals.add(new Proposal(pTable.name, Collections.singletonList(pColumn.name), winner.table.name, Collections.singletonList(winner.keyColumn),
			winner.selfReference, winner.confidence, winner.rationale));
	}

	/** One candidate target for a column, before the tie-break. */
	private static final class Candidate
	{
		final Table table;
		final String keyColumn;
		final int confidence;
		final boolean selfReference;
		final String rationale;

		Candidate(final Table pTable, final String pKeyColumn, final int pConfidence, final boolean pSelfReference, final String pRationale)
		{
			this.table = pTable;
			this.keyColumn = pKeyColumn;
			this.confidence = pConfidence;
			this.selfReference = pSelfReference;
			this.rationale = pRationale;
		}
	}

	// --------------------------------------------------------------------------------------------------- name matching
	//
	// Every comparison here is word by word ({@link Words}), never on the flattened letters of a name: a
	// flattened "ends with" is not the same test as "ends with that key name after a separator" - it also
	// accepts a coincidental run of letters that crosses a word boundary (SUBACCOUNT_ID flattens to
	// "subaccountid", which genuinely ends with "accountid", the flattened form of ACCOUNT_ID, even though
	// SUBACCOUNT_ID does not end with the word ACCOUNT_ID after any separator).

	private static boolean sameKey(final String pColumn, final String pKeyColumn)
	{
		return Words.of(pColumn).equals(Words.of(pKeyColumn));
	}

	/** The key's words are a PROPER suffix of the column's words: at least one more word in front, so an exact match (rule 1) is never also a rule-2 match. */
	private static boolean endsWithKey(final String pColumn, final String pKeyColumn)
	{
		final List<String> column = Words.of(pColumn);
		final List<String> key = Words.of(pKeyColumn);
		if (key.isEmpty() || column.size() <= key.size())
		{
			return false;
		}
		return column.subList(column.size() - key.size(), column.size()).equals(key);
	}

	/** Same shape as {@link SourceKeyRule}'s own rank-0 test: {@code <TABLE>_ID} or the singular of a plural table name - but word by word. */
	private static boolean namedLikeTableId(final String pColumn, final String pTableName)
	{
		final List<String> column = Words.of(pColumn);
		final List<String> table = Words.of(pTableName);
		if (table.isEmpty() || column.isEmpty() || !"id".equals(column.get(column.size() - 1)))
		{
			return false;
		}
		final List<String> body = column.subList(0, column.size() - 1);
		if (body.equals(table))
		{
			return true;
		}
		final String lastTableWord = table.get(table.size() - 1);
		if (lastTableWord.length() <= 1 || !lastTableWord.endsWith("s"))
		{
			return false;
		}
		final List<String> singular = new ArrayList<String>(table.subList(0, table.size() - 1));
		singular.add(lastTableWord.substring(0, lastTableWord.length() - 1));
		return body.equals(singular);
	}

	/** Whether a single-column key is a bare {@code ID}: one word, exactly "id" - checked word by word, like everything else here. */
	private static boolean isBareId(final String pKeyColumn)
	{
		final List<String> words = Words.of(pKeyColumn);
		return words.size() == 1 && "id".equals(words.get(0));
	}

	private static String letters(final String pName)
	{
		return pName == null ? "" : pName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	// --------------------------------------------------------------------------------------------------- data types

	private static final Set<String> NUMERIC = new HashSet<String>(Arrays.asList("INTEGER", "DECIMAL"));
	private static final Set<String> TEMPORAL = new HashSet<String>(Arrays.asList("DATE", "TIMESTAMP"));

	/** Unknown on either side (never profiled, or a column the caller could not find) is always compatible: only a known clash blocks a proposal. */
	private static boolean typesCompatible(final String pFrom, final String pTo)
	{
		if (pFrom == null || pTo == null)
		{
			return true;
		}
		final String from = pFrom.trim().toUpperCase(Locale.ROOT);
		final String to = pTo.trim().toUpperCase(Locale.ROOT);
		if (from.isEmpty() || to.isEmpty() || from.equals(to))
		{
			return true;
		}
		if (RelationRule.NUMERIC.contains(from) && RelationRule.NUMERIC.contains(to))
		{
			return true;
		}
		return RelationRule.TEMPORAL.contains(from) && RelationRule.TEMPORAL.contains(to);
	}

	private static String dataTypeOf(final Table pTable, final String pColumnName)
	{
		for (final Column column : pTable.columns)
		{
			if (RelationRule.letters(column.name).equals(RelationRule.letters(pColumnName)))
			{
				return column.dataType;
			}
		}
		return null;
	}

	// --------------------------------------------------------------------------------------------------- load order

	/** One relation to order by, stripped down to what the topological sort needs. */
	public static final class Edge
	{
		public final String fromTable;
		public final String toTable;
		public final boolean selfReference;

		public Edge(final String pFromTable, final String pToTable, final boolean pSelfReference)
		{
			this.fromTable = pFromTable;
			this.toTable = pToTable;
			this.selfReference = pSelfReference;
		}
	}

	public static final class LoadOrder
	{
		/** Parents-first order of the tables that could be placed; a table blocked by a cycle is not in here. */
		public final List<String> order;
		/** Empty when every table could be ordered; otherwise every table {@link #order} could not place because it (directly or through another
		 * blocked table) depends on a cycle. */
		public final List<String> cycle;

		LoadOrder(final List<String> pOrder, final List<String> pCycle)
		{
			this.order = pOrder;
			this.cycle = pCycle;
		}
	}

	/**
	 * Parents before children, from {@code pEdges} (child -&gt; parent; self references are ignored, as a table always exists before its own children
	 * regardless of a self relation). A cycle stops the sort rather than looping forever: whatever was already placed is returned in {@link
	 * LoadOrder#order}, and every table still blocked - the cycle itself, and anything that in turn depends on it - is returned in {@link
	 * LoadOrder#cycle}. Deterministic: ties are broken by the order of {@code pTableNames}.
	 */
	public static LoadOrder loadOrder(final List<String> pTableNames, final List<Edge> pEdges)
	{
		final Map<String, Set<String>> dependsOn = new LinkedHashMap<String, Set<String>>();
		for (final String name : pTableNames)
		{
			dependsOn.put(name, new LinkedHashSet<String>());
		}
		for (final Edge edge : pEdges)
		{
			if (edge.selfReference || edge.fromTable.equals(edge.toTable))
			{
				continue;
			}
			final Set<String> parents = dependsOn.get(edge.fromTable);
			if (parents != null && dependsOn.containsKey(edge.toTable))
			{
				parents.add(edge.toTable);
			}
		}

		final List<String> order = new ArrayList<String>();
		final Set<String> emitted = new HashSet<String>();
		final Set<String> remaining = new LinkedHashSet<String>(pTableNames);
		boolean progress = true;
		while (!remaining.isEmpty() && progress)
		{
			progress = false;
			for (final Iterator<String> it = remaining.iterator(); it.hasNext();)
			{
				final String name = it.next();
				if (emitted.containsAll(dependsOn.get(name)))
				{
					order.add(name);
					emitted.add(name);
					it.remove();
					progress = true;
				}
			}
		}
		return new LoadOrder(order, new ArrayList<String>(remaining));
	}
}
