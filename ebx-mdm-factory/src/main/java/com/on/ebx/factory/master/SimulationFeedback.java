package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.matching.CandidateFilterCheck;
import com.on.ebx.factory.matching.KeyFacts;

/**
 * Lot F1 (2026-10-19, {@code docs/MATCHING.md} &sect;70): the NUMERIC FEEDBACK a language model gets AFTER it proposed a matching plan - what the plan does to the pairs the real match engine
 * evaluated in a simulation of that very plan. Counts only, never a value; the model reads them and answers with a revised plan or keeps its plan with a rationale. <b>The Factory decides nothing
 * here</b>: no weight, no threshold, no leaf is set, suggested or ranked by this class - it states what the simulation measured and what that means for the pairs.
 * <p>
 * Pure (Jackson, {@link DecisionTreePlan}, {@link CandidateFilterCheck} and {@link KeyFacts} only): {@code ebx.MatchPlanSimulationRunner} hands in the pairs of a simulation as plain
 * {@link Pair}s (verdict, the two records' values of the plan's key fields, the add-on's visit order) and the stored plan ({@code requirements}); the text goes to the model's prompt (English), to the
 * step detail ({@link Result#toJson}) and, as one line, to the plan's rationale (the bundle keys {@code matchfb.rationale.*}).
 * <p>
 * <b>What is measured, per group of pairs</b> ({@link #ORIGIN_ENGINE}: the candidates the add-on evaluated for the pivots the runner drew - the sample the figures are about;
 * {@link #ORIGIN_PROBE}: the pairs the runner chose because they share a key across source systems, counted apart because they are selected, not drawn):
 * <ul>
 * <li>how many pairs the plan routes to {@code MATCH} / {@code SUSPECTED} / {@code NOT_MATCHED};</li>
 * <li>the KEY SETS of the plan: the exact conditions a path of the tree passes ({@link CandidateFilterCheck}: {@code ALL_FIELDS} nodes on an exact key of the record itself), and the pairs of keys
 * the entity's key facts mark SAFE ({@link KeyFacts.Agreement#safe}). For each set, the pairs that agree on EVERY field of it (both records fill it, identical value) and where the plan sends them;</li>
 * <li>how many {@code SUSPECTED} pairs agree on such a set (pairs a stricter leaf would merge) and how many {@code NOT_MATCHED} pairs do (possible misses);</li>
 * <li>per leaf of the tree, the pairs it decided - from the add-on's own visit order of each pair ({@code enginePath}), for the pairs whose path was recorded.</li>
 * </ul>
 * A set containing a multi-token key (compared with {@code exactFullText}) is not counted: two records "agree" on it by a score, not by an identical value.
 */
public final class SimulationFeedback
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The pair comes from the add-on's candidate search for a drawn pivot. */
	public static final String ORIGIN_ENGINE = "ENGINE";
	/** The pair was chosen by the runner (it shares a key across systems): counted apart. */
	public static final String ORIGIN_PROBE = "PROBE";

	public static final String MATCH = "MATCH";
	public static final String SUSPECTED = "SUSPECTED";
	public static final String NOT_MATCHED = "NOT_MATCHED";

	/** At most this many key sets are told in the prompt (the ones with agreeing pairs, SAFE first). */
	static final int MAX_SETS_TOLD = 6;
	/** At most this many leaves are listed. */
	static final int MAX_LEAVES_TOLD = 16;

	private SimulationFeedback()
	{
	}

	// ---------------------------------------------------------------------------------------------------- gate

	/**
	 * The system property that bounds the master table size under which the proposal job runs the simulation and the feedback turn by itself; 0 or below turns the turn off (the Simulate button
	 * and "Propose another" stay). Unset: the bound of {@link ScanBudget} (the module's one bound on what it reads in one pass).
	 */
	public static final String MAX_ROWS_PROPERTY = "mdm.factory.matchFeedback.maxRows";

	/** The wall-clock bound of the simulations of one proposal job (all entities together): once spent, the remaining entities keep their plan and say so. */
	public static final long MAX_PHASE_MILLIS = 300000L;

	/** The bound in force: the property when it is a whole number, else {@code pScanBudget}. */
	public static long maxRows(final String pPropertyValue, final long pScanBudget)
	{
		if (pPropertyValue == null || pPropertyValue.trim().isEmpty())
		{
			return pScanBudget;
		}
		try
		{
			return Long.parseLong(pPropertyValue.trim());
		}
		catch (final NumberFormatException ex)
		{
			return pScanBudget;
		}
	}

	/**
	 * Null when the simulation of an entity may run inside the proposal job, otherwise why it does not (kept in the step detail; the Simulate button is then the way).
	 * The simulation itself is bounded whatever the table (20 pivots, 2,000 rows read per source system, 60 detail calls); the rows bound is for the two reads that are not (the grouping of
	 * the probes, the coverage read).
	 */
	public static String gateRefusal(final long pRows, final long pMaxRows, final long pElapsedMillis)
	{
		if (pMaxRows <= 0L)
		{
			return "disabled (" + MAX_ROWS_PROPERTY + "=" + pMaxRows + ")";
		}
		if (pRows > pMaxRows)
		{
			return pRows + " master rows exceed " + MAX_ROWS_PROPERTY + "=" + pMaxRows + ": use Simulate on the plan card, then Propose another";
		}
		if (pElapsedMillis > MAX_PHASE_MILLIS)
		{
			return "the time bound of " + MAX_PHASE_MILLIS / 1000L + " s for the simulations of one proposal was spent: use Simulate on the plan card";
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------------- input

	/** One pair judged by the engine. Values are only compared here, never written anywhere. */
	public static final class Pair
	{
		public final String origin;
		/** {@link #MATCH}, {@link #SUSPECTED}, {@link #NOT_MATCHED} or empty when the engine's verdict is not one of them. */
		public final String verdict;
		public final Map<String, String> pivotValues;
		public final Map<String, String> targetValues;
		/** The add-on's visit order ({@code DecisionNode} names); empty when it was not recorded for the pair. */
		public final List<String> enginePath;

		public Pair(final String pOrigin, final String pRawVerdict, final Map<String, String> pPivotValues, final Map<String, String> pTargetValues, final List<String> pEnginePath)
		{
			this.origin = ORIGIN_PROBE.equals(pOrigin) ? ORIGIN_PROBE : ORIGIN_ENGINE;
			this.verdict = SimulationFeedback.normalizedVerdict(pRawVerdict);
			this.pivotValues = pPivotValues == null ? Collections.<String, String>emptyMap() : pPivotValues;
			this.targetValues = pTargetValues == null ? Collections.<String, String>emptyMap() : pTargetValues;
			this.enginePath = pEnginePath == null ? Collections.<String>emptyList() : pEnginePath;
		}

		/** True when both records fill {@code pField} with the same value (trimmed, case-insensitive). */
		boolean agreesOn(final String pField)
		{
			final String a = this.pivotValues.get(pField);
			final String b = this.targetValues.get(pField);
			return a != null && b != null && !a.trim().isEmpty() && a.trim().equalsIgnoreCase(b.trim());
		}
	}

	/** {@code MATCHED}/{@code MATCH} -&gt; {@link #MATCH}, {@code SUSPECTED}/{@code SUSPECT} -&gt; {@link #SUSPECTED}, {@code NOT_MATCHED}/{@code NOT_MATCH} -&gt; {@link #NOT_MATCHED}, anything else empty. */
	public static String normalizedVerdict(final String pRaw)
	{
		final String upper = pRaw == null ? "" : pRaw.trim().toUpperCase(Locale.ROOT);
		if ("MATCH".equals(upper) || "MATCHED".equals(upper))
		{
			return MATCH;
		}
		if ("SUSPECT".equals(upper) || "SUSPECTED".equals(upper))
		{
			return SUSPECTED;
		}
		return "NOT_MATCH".equals(upper) || "NOT_MATCHED".equals(upper) ? NOT_MATCHED : "";
	}

	// ---------------------------------------------------------------------------------------------------- key sets

	/** A set of key fields the plan compares exactly, or that the key facts mark SAFE together. */
	public static final class KeySet
	{
		public final List<String> fields;
		/** The outcomes ({@code MATCH}/{@code SUSPECTED}) of the paths whose exact conditions this set is; empty for a set that is only SAFE. */
		public final Set<String> pathOutcomes = new LinkedHashSet<String>();
		/** The labels of those paths ({@code N2 > N3}). */
		public final Set<String> pathLabels = new LinkedHashSet<String>();
		/** The entity's key facts mark these keys SAFE together (a pair of two keys). */
		public boolean safe;
		/** False when a field of the set is compared by a score (multi-token key): agreement is not an identical value, the set is not counted. */
		public boolean countable = true;

		KeySet(final List<String> pFields)
		{
			this.fields = pFields;
		}

		public String fieldsText()
		{
			return String.join(" + ", this.fields);
		}

		/** English, no trailing period: where the set comes from. */
		public String why()
		{
			final List<String> parts = new ArrayList<String>();
			if (!this.pathOutcomes.isEmpty())
			{
				parts.add("an exact key set of " + (this.pathLabels.size() == 1 ? "the path " : "the paths ") + String.join(", ", this.pathLabels) + " of your tree, ending in "
					+ String.join(" / ", this.pathOutcomes));
			}
			if (this.safe)
			{
				parts.add("the entity's key facts mark these two keys SAFE: at least " + Math.round(KeyFacts.MIN_AGREEMENT * 100d) + "% of the comparable pairs sharing one also share the other, none differ");
			}
			return String.join("; ", parts);
		}
	}

	/**
	 * The key sets of a stored plan: one per distinct field set of {@link CandidateFilterCheck} (the exact conditions of its paths to MATCH/SUSPECTED), plus every pair of keys its
	 * {@code keyFacts} mark SAFE. Never null; empty for a plan without a tree or keys.
	 */
	public static List<KeySet> keySets(final JsonNode pRequirements)
	{
		final Map<String, KeySet> byKey = new LinkedHashMap<String, KeySet>();
		final Set<String> notComparable = SimulationFeedback.fullTextFields(pRequirements);
		final CandidateFilterCheck.Result filter = CandidateFilterCheck.check(pRequirements);
		for (final CandidateFilterCheck.PathResult path : filter.paths)
		{
			if (!path.hasKey())
			{
				continue;
			}
			final KeySet set = SimulationFeedback.setFor(byKey, path.keyFields);
			set.pathOutcomes.add(path.outcome);
			set.pathLabels.add(path.label());
		}
		final KeyFacts facts = pRequirements == null ? null : KeyFacts.fromJson(pRequirements.path("keyFacts"));
		if (facts != null)
		{
			// a pair of keys the facts mark SAFE counts as a key set only when the plan itself uses both keys (a node field or a clustering field)
			final Set<String> declared = SimulationFeedback.declaredFields(pRequirements);
			for (final KeyFacts.Agreement agreement : facts.agreements())
			{
				if (declared.contains(agreement.key) && declared.contains(agreement.other) && facts.safePair(agreement.key, agreement.other))
				{
					SimulationFeedback.setFor(byKey, java.util.Arrays.asList(agreement.key, agreement.other)).safe = true;
				}
			}
			for (final KeyFacts.KeyStat stat : facts.keys())
			{
				if (stat.multiToken)
				{
					notComparable.add(stat.name);
				}
			}
		}
		for (final KeySet set : byKey.values())
		{
			for (final String field : set.fields)
			{
				if (notComparable.contains(field))
				{
					set.countable = false;
				}
			}
		}
		return new ArrayList<KeySet>(byKey.values());
	}

	/** The fields of the record the plan names: those of its nodes (relation fields left out) and its clustering fields. */
	private static Set<String> declaredFields(final JsonNode pRequirements)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final DecisionTreePlan.Node node : DecisionTreePlan.readFrom(pRequirements).nodes)
		{
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				if (field.field != null && !field.isRelation())
				{
					out.add(field.field);
				}
			}
		}
		for (final JsonNode field : pRequirements.path("clusteringFields"))
		{
			if (field.isTextual())
			{
				out.add(field.asText());
			}
		}
		return out;
	}

	private static KeySet setFor(final Map<String, KeySet> pByKey, final List<String> pFields)
	{
		final String id = new TreeSet<String>(pFields).toString();
		KeySet set = pByKey.get(id);
		if (set == null)
		{
			set = new KeySet(new ArrayList<String>(new LinkedHashSet<String>(pFields)));
			pByKey.put(id, set);
		}
		return set;
	}

	/** The fields the plan compares ONLY by a score (a full-text algorithm anywhere and an exact one nowhere): two records agree on them by similarity, not by an identical value. */
	private static Set<String> fullTextFields(final JsonNode pRequirements)
	{
		final Set<String> fullText = new LinkedHashSet<String>();
		final Set<String> exact = new LinkedHashSet<String>();
		for (final DecisionTreePlan.Node node : DecisionTreePlan.readFrom(pRequirements).nodes)
		{
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				if (field.field == null || field.isRelation())
				{
					continue;
				}
				final String algorithm = field.algorithm == null ? "" : field.algorithm.trim().toLowerCase(Locale.ROOT);
				final String suffix = algorithm.substring(algorithm.lastIndexOf('@') + 1);
				if ("exact".equals(suffix))
				{
					exact.add(field.field);
				}
				else if (suffix.endsWith("fulltext"))
				{
					fullText.add(field.field);
				}
			}
		}
		fullText.removeAll(exact);
		return fullText;
	}

	// ---------------------------------------------------------------------------------------------------- output

	/** Counts of one group of pairs by verdict. */
	public static final class Verdicts
	{
		public int pairs;
		public int match;
		public int suspected;
		public int notMatched;
		public int other;

		void add(final String pVerdict)
		{
			this.pairs++;
			if (MATCH.equals(pVerdict))
			{
				this.match++;
			}
			else if (SUSPECTED.equals(pVerdict))
			{
				this.suspected++;
			}
			else if (NOT_MATCHED.equals(pVerdict))
			{
				this.notMatched++;
			}
			else
			{
				this.other++;
			}
		}

		ObjectNode toJson()
		{
			final ObjectNode out = MAPPER.createObjectNode();
			out.put("pairs", this.pairs);
			out.put("match", this.match);
			out.put("suspected", this.suspected);
			out.put("notMatched", this.notMatched);
			if (this.other > 0)
			{
				out.put("other", this.other);
			}
			return out;
		}
	}

	/** One key set with the pairs that agree on every field of it, by group and verdict. */
	public static final class SetCount
	{
		public final KeySet set;
		public final Verdicts engine = new Verdicts();
		public final Verdicts probe = new Verdicts();

		SetCount(final KeySet pSet)
		{
			this.set = pSet;
		}
	}

	/** One leaf of the tree ({@code node.onTrue/onFalse} that names an outcome) and the engine pairs whose recorded path ended there. */
	public static final class LeafCount
	{
		public final String node;
		/** {@code onTrue}, {@code onFalse}, or {@code either} when both branches of the node end in the same outcome (the pair's branch cannot be told from its verdict). */
		public final String branch;
		public final String outcome;
		public int pairs;

		LeafCount(final String pNode, final String pBranch, final String pOutcome)
		{
			this.node = pNode;
			this.branch = pBranch;
			this.outcome = pOutcome;
		}

		public String label()
		{
			return this.node + (this.branch.equals("either") ? " (either branch)" : " " + this.branch) + " -> " + this.outcome;
		}
	}

	/** The measurement. */
	public static final class Result
	{
		public final int pivots;
		public final Verdicts engine = new Verdicts();
		public final Verdicts probe = new Verdicts();
		/** Engine pairs whose visit order was recorded and resolved to a leaf. */
		public int engineWithLeaf;
		public final List<SetCount> sets = new ArrayList<SetCount>();
		public final List<LeafCount> leaves = new ArrayList<LeafCount>();
		/** Engine pairs that agree on every field of at least one counted key set, by verdict (each pair once). */
		public int suspectedOnKeys;
		public int notMatchedOnKeys;
		public int matchOnKeys;
		/** Sets left out because a field is compared by a score. */
		public final List<String> uncountedSets = new ArrayList<String>();

		Result(final int pPivots)
		{
			this.pivots = pPivots;
		}

		/** True when the simulation judged no pair: nothing to tell. */
		public boolean isEmpty()
		{
			return this.engine.pairs == 0 && this.probe.pairs == 0;
		}

		/**
		 * The set the one-line summary is about: among the sets with {@code SUSPECTED} engine pairs agreeing on every field, a SAFE pair of keys first (the key facts measured those records as
		 * the same ones), then the set with the most such pairs; null when no pair that waits for a steward agrees on a counted key set.
		 */
		public SetCount headlineSet()
		{
			SetCount best = null;
			for (final SetCount count : this.sets)
			{
				if (!count.set.countable || count.engine.suspected == 0)
				{
					continue;
				}
				if (best == null || count.set.safe && !best.set.safe || count.set.safe == best.set.safe && count.engine.suspected > best.engine.suspected)
				{
					best = count;
				}
			}
			return best;
		}

		/**
		 * The statements for the model's prompt (English, numbered by the caller): the sample, the verdicts, the leaves, each key set with its consequence, the possible misses, the probed pairs.
		 * Counts only.
		 */
		public List<String> facts()
		{
			final List<String> out = new ArrayList<String>();
			out.add("sample: " + this.pivots + " pivot(s) drawn at random across the source systems; the add-on evaluated " + this.engine.pairs + " distinct pair(s) for them"
				+ (this.probe.pairs > 0 ? " (and the Factory probed " + this.probe.pairs + " more, chosen because they share a key across source systems: counted apart, below)" : "")
				+ ". These figures are measured on that sample, not on the whole table.");
			if (this.engine.pairs > 0)
			{
				out.add("verdicts of the " + this.engine.pairs + " evaluated pair(s): " + this.engine.match + " go to MATCH (merged automatically once automatic merge is on for the plan), "
					+ this.engine.suspected + " to SUSPECTED (a data steward decides), " + this.engine.notMatched + " to NOT_MATCHED" + (this.engine.other > 0 ? ", " + this.engine.other + " without a verdict" : "") + ".");
				out.add(this.leavesText());
			}
			int told = 0;
			for (final SetCount count : this.sets)
			{
				if (!count.set.countable || count.engine.pairs == 0 || told >= MAX_SETS_TOLD)
				{
					continue;
				}
				told++;
				out.add(SimulationFeedback.setText(count));
			}
			if (this.engine.notMatched > 0 && this.notMatchedOnKeys > 0)
			{
				out.add("possible misses: " + this.notMatchedOnKeys + " of the " + this.engine.notMatched + " NOT_MATCHED pair(s) agree on every field of at least one key set above (" + this.notMatchedBySetText() + ").");
			}
			if (this.suspectedOnKeys > 0)
			{
				out.add(this.suspectedOnKeys + " of the " + this.engine.suspected + " SUSPECTED pair(s) agree on every field of at least one key set above: with this plan they wait for a steward.");
			}
			if (this.probe.pairs > 0)
			{
				out.add("probed pairs (chosen, not drawn): " + this.probe.pairs + " pair(s) sharing a key across source systems: " + this.probe.match + " go to MATCH, " + this.probe.suspected + " to SUSPECTED, "
					+ this.probe.notMatched + " to NOT_MATCHED" + this.probedSetsText() + ".");
			}
			if (!this.uncountedSets.isEmpty())
			{
				out.add("not counted: " + String.join("; ", this.uncountedSets) + " (compared by a score on a multi-token key: two records agree on it by similarity, not by an identical value).");
			}
			return out;
		}

		private String leavesText()
		{
			final StringBuilder out = new StringBuilder("leaf by leaf (the add-on's visit order was recorded for ").append(this.engineWithLeaf).append(" of the ").append(this.engine.pairs).append(" pair(s)): ");
			if (this.leaves.isEmpty())
			{
				return out.append("the tree could not be read.").toString();
			}
			int shown = 0;
			for (final LeafCount leaf : this.leaves)
			{
				if (shown >= MAX_LEAVES_TOLD)
				{
					out.append("; ...");
					break;
				}
				out.append(shown == 0 ? "" : "; ").append(leaf.label()).append(' ').append(leaf.pairs);
				shown++;
			}
			return out.append('.').toString();
		}

		private String notMatchedBySetText()
		{
			final List<String> parts = new ArrayList<String>();
			for (final SetCount count : this.sets)
			{
				if (count.set.countable && count.engine.notMatched > 0)
				{
					parts.add(count.set.fieldsText() + ": " + count.engine.notMatched);
				}
			}
			return String.join("; ", parts);
		}

		private String probedSetsText()
		{
			final List<String> parts = new ArrayList<String>();
			for (final SetCount count : this.sets)
			{
				if (count.set.countable && count.probe.pairs > 0)
				{
					parts.add(count.probe.pairs + " agree on " + count.set.fieldsText() + " (" + count.probe.suspected + " SUSPECTED, " + count.probe.notMatched + " NOT_MATCHED, " + count.probe.match + " MATCH)");
				}
			}
			return parts.isEmpty() ? "" : "; of them " + String.join("; ", parts);
		}

		/** One line for the kernel log: {@code pivots=20 pairs=87 match=12 suspected=61 notMatched=14 suspectedOnKeys=52 notMatchedOnKeys=3 keys=[emailKey: 40 suspected]}. */
		public String summaryLine()
		{
			final StringBuilder out = new StringBuilder("pivots=").append(this.pivots).append(" pairs=").append(this.engine.pairs).append(" match=").append(this.engine.match).append(" suspected=")
				.append(this.engine.suspected).append(" notMatched=").append(this.engine.notMatched).append(" suspectedOnKeys=").append(this.suspectedOnKeys).append(" notMatchedOnKeys=")
				.append(this.notMatchedOnKeys).append(" probed=").append(this.probe.pairs);
			final List<String> keys = new ArrayList<String>();
			for (final SetCount count : this.sets)
			{
				if (count.set.countable && count.engine.pairs > 0)
				{
					keys.add(count.set.fieldsText() + ": " + count.engine.pairs + " agree (" + count.engine.match + "/" + count.engine.suspected + "/" + count.engine.notMatched + ")");
				}
			}
			return out.append(" keys=[").append(String.join("; ", keys)).append(']').toString();
		}

		/** The figures as the stored JSON (the step detail): counts only. */
		public ObjectNode toJson()
		{
			final ObjectNode out = MAPPER.createObjectNode();
			out.put("pivots", this.pivots);
			out.set("engine", this.engine.toJson());
			out.set("probe", this.probe.toJson());
			out.put("engineWithLeaf", this.engineWithLeaf);
			out.put("suspectedOnKeys", this.suspectedOnKeys);
			out.put("notMatchedOnKeys", this.notMatchedOnKeys);
			out.put("matchOnKeys", this.matchOnKeys);
			final ArrayNode setArray = out.putArray("keySets");
			for (final SetCount count : this.sets)
			{
				final ObjectNode node = setArray.addObject();
				final ArrayNode fields = node.putArray("fields");
				for (final String field : count.set.fields)
				{
					fields.add(field);
				}
				node.put("safe", count.set.safe);
				node.put("countable", count.set.countable);
				final ArrayNode outcomes = node.putArray("pathOutcomes");
				for (final String outcome : count.set.pathOutcomes)
				{
					outcomes.add(outcome);
				}
				node.set("engine", count.engine.toJson());
				node.set("probe", count.probe.toJson());
			}
			final ArrayNode leafArray = out.putArray("leaves");
			for (final LeafCount leaf : this.leaves)
			{
				final ObjectNode node = leafArray.addObject();
				node.put("node", leaf.node);
				node.put("branch", leaf.branch);
				node.put("outcome", leaf.outcome);
				node.put("pairs", leaf.pairs);
			}
			return out;
		}
	}

	private static String setText(final SetCount pCount)
	{
		final Verdicts v = pCount.engine;
		final StringBuilder out = new StringBuilder("of the ").append(v.pairs).append(" pair(s) that agree on every field of ").append(pCount.set.fieldsText()).append(" (")
			.append(pCount.set.why()).append("): ").append(v.match).append(" go to MATCH, ").append(v.suspected).append(" to SUSPECTED, ").append(v.notMatched).append(" to NOT_MATCHED.");
		if (v.suspected > 0)
		{
			out.append(" With this plan, ").append(v.suspected).append(" of the ").append(v.pairs).append(" pair(s) the simulation judged identical on ").append(pCount.set.fieldsText())
				.append(" wait for a steward instead of merging.");
		}
		return out.toString();
	}

	// ---------------------------------------------------------------------------------------------------- measure

	/**
	 * @param pRequirements the stored plan ({@code MatchPlan.requirements}: tree, clustering, {@code keyFacts})
	 * @param pPairs the pairs of the simulation
	 * @param pPivots how many pivots the simulation drew
	 */
	public static Result measure(final JsonNode pRequirements, final List<Pair> pPairs, final int pPivots)
	{
		final Result result = new Result(pPivots);
		final List<KeySet> sets = SimulationFeedback.keySets(pRequirements);
		for (final KeySet set : sets)
		{
			final SetCount count = new SetCount(set);
			result.sets.add(count);
			if (!set.countable)
			{
				result.uncountedSets.add(set.fieldsText());
			}
		}
		final LeafIndex leafIndex = new LeafIndex(pRequirements);
		result.leaves.addAll(leafIndex.leaves);
		for (final Pair pair : pPairs)
		{
			final boolean engine = ORIGIN_ENGINE.equals(pair.origin);
			(engine ? result.engine : result.probe).add(pair.verdict);
			boolean onKeys = false;
			for (final SetCount count : result.sets)
			{
				if (!count.set.countable || !SimulationFeedback.agreesOnAll(pair, count.set))
				{
					continue;
				}
				(engine ? count.engine : count.probe).add(pair.verdict);
				onKeys = true;
			}
			if (engine)
			{
				if (onKeys)
				{
					if (SUSPECTED.equals(pair.verdict))
					{
						result.suspectedOnKeys++;
					}
					else if (NOT_MATCHED.equals(pair.verdict))
					{
						result.notMatchedOnKeys++;
					}
					else if (MATCH.equals(pair.verdict))
					{
						result.matchOnKeys++;
					}
				}
				if (leafIndex.count(pair))
				{
					result.engineWithLeaf++;
				}
			}
		}
		return result;
	}

	private static boolean agreesOnAll(final Pair pPair, final KeySet pSet)
	{
		for (final String field : pSet.fields)
		{
			if (!pPair.agreesOn(field))
			{
				return false;
			}
		}
		return true;
	}

	/** The leaves of the stored tree and the walk of an engine path through it. */
	private static final class LeafIndex
	{
		final DecisionTreePlan.Plan plan;
		final List<LeafCount> leaves = new ArrayList<LeafCount>();
		final Map<String, LeafCount> byKey = new LinkedHashMap<String, LeafCount>();

		LeafIndex(final JsonNode pRequirements)
		{
			this.plan = DecisionTreePlan.readFrom(pRequirements);
			DecisionTreePlan.freezeAndDisambiguateLabels(this.plan);
			for (final DecisionTreePlan.Node node : this.plan.nodes)
			{
				if (DecisionTreePlan.isLeaf(node.onTrue) && node.onTrue.equals(node.onFalse))
				{
					// both branches end in the same outcome: the branch a pair took cannot be told from its verdict, one entry for the node
					this.add(node.id, "either", node.onTrue);
					continue;
				}
				this.add(node.id, "onTrue", node.onTrue);
				this.add(node.id, "onFalse", node.onFalse);
			}
		}

		private void add(final String pNode, final String pBranch, final String pTarget)
		{
			if (DecisionTreePlan.isLeaf(pTarget))
			{
				final LeafCount leaf = new LeafCount(pNode, pBranch, pTarget);
				this.leaves.add(leaf);
				this.byKey.put(pNode + "|" + pBranch, leaf);
			}
		}

		/** Counts the pair under the leaf its recorded path ended at; false when the path is missing, does not resolve against the tree, or ends on a node that is not left through a leaf. */
		boolean count(final Pair pPair)
		{
			if (pPair.enginePath.isEmpty() || this.plan.root == null || pPair.verdict.isEmpty())
			{
				return false;
			}
			DecisionTreePlan.Node current = this.plan.byId(this.plan.root);
			if (current == null || !DecisionTreePlan.labelFor(current).equals(pPair.enginePath.get(0)))
			{
				return false;
			}
			for (int i = 1; i < pPair.enginePath.size(); i++)
			{
				final String wanted = pPair.enginePath.get(i);
				final DecisionTreePlan.Node onTrue = DecisionTreePlan.isLeaf(current.onTrue) ? null : this.plan.byId(current.onTrue);
				final DecisionTreePlan.Node onFalse = DecisionTreePlan.isLeaf(current.onFalse) ? null : this.plan.byId(current.onFalse);
				if (onTrue != null && DecisionTreePlan.labelFor(onTrue).equals(wanted))
				{
					current = onTrue;
				}
				else if (onFalse != null && DecisionTreePlan.labelFor(onFalse).equals(wanted))
				{
					current = onFalse;
				}
				else
				{
					return false;
				}
			}
			final boolean trueEnds = pPair.verdict.equals(current.onTrue);
			final boolean falseEnds = pPair.verdict.equals(current.onFalse);
			if (trueEnds && falseEnds)
			{
				final LeafCount either = this.byKey.get(current.id + "|either");
				if (either == null)
				{
					return false;
				}
				either.pairs++;
				return true;
			}
			if (!trueEnds && !falseEnds)
			{
				return false;
			}
			final LeafCount leaf = this.byKey.get(current.id + "|" + (trueEnds ? "onTrue" : "onFalse"));
			if (leaf == null)
			{
				return false;
			}
			leaf.pairs++;
			return true;
		}
	}
}
