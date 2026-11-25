package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.matching.CandidateFilterCheck;

/**
 * Lot M16 (2026-10-18, {@code docs/MATCHING.md} &sect;68): the arithmetic of the add-on's candidate search, as a MEASUREMENT the Factory hands to the language model - never a rule it applies to the
 * weights itself (the product owner's correction of the lot: the Factory gives the model the elements that let it find the configuration; the model sets the weights).
 * <p>
 * <b>The facts</b> (read by {@code javap} on {@code ebx-lib.jar}, {@code CnyTableMinScoreHelper}/{@code CnyMandatoryFieldSets}, and on the add-on's
 * {@code MatchingQueueProducer}, which calls {@code setThresholds(0.3f, 0.8f)}; measured on the 11th end-to-end run of the B2C master scenario, project
 * {@code E2EMASTER20261018104106}). For each pivot record the candidate search computes the pivot's own score {@code C} = the sum of the pre-clustering weights of the fields the pivot FILLS, and keeps
 * a candidate only when it scores at least {@code max(0.3 x C, the smallest set score)}. A pair that satisfies the key set S of a path scores
 * {@code sum(weight(f) x minScore(f)/100, f in S)}. The pivot that fills every declared field has {@code C = W}, the sum of ALL declared weights: such a pair is a candidate only when its set holds at
 * least 30 % of {@code W}.
 * <p>
 * Live: {@code emailKey} 4, {@code mobileE164} 1, {@code personNameKey} 1, {@code addressKeys} 1 (W = 7) - the dominance guard of lot R16 had raised the e-mail above the sum of the others: a pair
 * that shares the phone and the name key scored 2 of 7 (29 %) against an ECOM record that fills all four fields; 150 of 565 true pairs were never proposed, every one of them a pair of which one side
 * has no e-mail. The same data with every weight at 1 (W = 4, 50 %) found all 565.
 * <p>
 * <b>This class</b> (structural: no field name, no business word) computes, for every key set of the tree ({@link CandidateFilterCheck}), the share of {@code W} it holds, says which sets are under
 * the add-on's 30 % and what weights would lift them (the {@link #LADDER} of shares 40 %, 35 %, 31.5 %; raise by one unit the member that counts most of the most deficient set, at most
 * {@link #MAX_RAISES} raises; sets of MATCH paths first when not every set can be served). It never changes a weight: the model reads the numbers, and writes its own
 * {@code clusteringWeights}.
 */
public final class CandidateScoreShare
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The add-on's low threshold: a candidate must score at least this share of the pivot's own score (read in {@code MatchingQueueProducer}: {@code setThresholds(0.3f, 0.8f)}). */
	public static final double ADDON_LOW_THRESHOLD = 0.3d;

	/** The shares tried, strongest first, as {numerator, denominator}: 40 %, 35 %, 31.5 % - each above {@link #ADDON_LOW_THRESHOLD} so a float rounding or an equality never decides. */
	static final int[][] LADDER = { { 2, 5 }, { 7, 20 }, { 63, 200 } };

	/** An attempt that needs more than this many one-unit raises is given up (the sets pull against each other, or the weights would be absurd). */
	static final int MAX_RAISES = 300;

	/** The note's {@code scope}: every set was satisfied. */
	public static final String SCOPE_ALL = "ALL_PATHS";
	/** The note's {@code scope}: only the sets of the paths that end in MATCH could be satisfied. */
	public static final String SCOPE_MATCH = "MATCH_PATHS";
	/** The note's {@code scope}: no set could be satisfied, the weights are the plan's own. */
	public static final String SCOPE_NONE = "NONE";

	private CandidateScoreShare()
	{
	}

	/** One key set of the tree: the fields the add-on would require together, how much of each field's weight it counts, and whether one of the paths behind it ends in MATCH. */
	public static final class KeySet
	{
		public final List<String> fields;
		/** field -&gt; 0..100: the comparator's minimumScore (100 for an exact key). */
		public final Map<String, Integer> percents;
		public final boolean match;
		/** {@code MATCH} when one path with this set ends in MATCH, else {@code SUSPECTED}. */
		public final String outcome;

		KeySet(final List<String> pFields, final Map<String, Integer> pPercents, final boolean pMatch)
		{
			this.fields = Collections.unmodifiableList(pFields);
			this.percents = Collections.unmodifiableMap(pPercents);
			this.match = pMatch;
			this.outcome = pMatch ? CandidateFilterCheck_LEAF_MATCH : "SUSPECTED";
		}
	}

	private static final String CandidateFilterCheck_LEAF_MATCH = "MATCH";

	/**
	 * The distinct key sets of a {@link CandidateFilterCheck.Result}, in path order, names passed through {@code pNormalize} (the translator's bare field names). A set that
	 * several paths share is one set, MATCH if any of those paths is. A set with no member in {@code pKnownFields} is dropped.
	 */
	public static List<KeySet> keySets(final CandidateFilterCheck.Result pResult, final java.util.function.Function<String, String> pNormalize, final Set<String> pKnownFields)
	{
		final Map<String, KeySet> bySignature = new LinkedHashMap<String, KeySet>();
		if (pResult == null)
		{
			return new ArrayList<KeySet>();
		}
		for (final CandidateFilterCheck.PathResult path : pResult.paths)
		{
			if (!path.hasKey())
			{
				continue;
			}
			final List<String> fields = new ArrayList<String>();
			final Map<String, Integer> percents = new LinkedHashMap<String, Integer>();
			boolean known = true;
			for (final String raw : path.keyFields)
			{
				final String name = pNormalize.apply(raw);
				if (!pKnownFields.contains(name))
				{
					known = false;
					break;
				}
				fields.add(name);
				final Integer percent = path.keyPercents.get(raw);
				percents.put(name, percent == null ? Integer.valueOf(100) : percent);
			}
			if (!known)
			{
				continue;
			}
			final String signature = new LinkedHashSet<String>(fields).toString() + percents;
			final KeySet existing = bySignature.get(signature);
			final boolean match = "MATCH".equals(path.outcome) || (existing != null && existing.match);
			bySignature.put(signature, new KeySet(fields, percents, match));
		}
		return new ArrayList<KeySet>(bySignature.values());
	}

	/** A set is reported as under the add-on's threshold below this share (a little above 30 % so a thin pass is not called a failure and a float never decides). */
	public static final double FAIL_BELOW = 0.31d;

	/** The result of {@link #analyze}: the shares as they stand, the weights that would lift the failing sets (if any), the note for the step detail. */
	public static final class Analysis
	{
		public final int totalWeight;
		/** The own-table weights analysed (a copy), field -&gt; weight. */
		public final Map<String, Integer> weights;
		/** One entry per key set, in the order given. */
		public final List<SetShare> sets;
		/** The sets under {@link #FAIL_BELOW} as the weights stand. */
		public final List<SetShare> failing;
		/** field -&gt; weight that would lift the failing sets (only fields whose weight differs from the current one); empty when nothing fails or nothing can be suggested. */
		public final Map<String, Integer> suggested;
		/** {@link #SCOPE_ALL}, {@link #SCOPE_MATCH} or {@link #SCOPE_NONE} (no suggestion could be found) - meaningful only when {@link #failing} is not empty. */
		public final String scope;
		/** The share the suggestion holds every set above, 0 when there is none. */
		public final double suggestedShare;

		Analysis(final int pTotalWeight, final Map<String, Integer> pWeights, final List<SetShare> pSets, final List<SetShare> pFailing, final Map<String, Integer> pSuggested, final String pScope, final double pSuggestedShare)
		{
			this.totalWeight = pTotalWeight;
			this.weights = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(pWeights));
			this.sets = Collections.unmodifiableList(pSets);
			this.failing = Collections.unmodifiableList(pFailing);
			this.suggested = Collections.unmodifiableMap(pSuggested);
			this.scope = pScope;
			this.suggestedShare = pSuggestedShare;
		}

		public boolean ok()
		{
			return this.failing.isEmpty();
		}

		/** The note recorded under {@code detail.candidateScoreShare}: the facts, never an action ({@code applied} is always false). */
		public ObjectNode toJson()
		{
			final ObjectNode note = MAPPER.createObjectNode();
			note.put("rule", "CANDIDATE_SCORE_SHARE");
			note.put("applied", false);
			note.put("addonLowThreshold", ADDON_LOW_THRESHOLD);
			note.put("totalWeight", this.totalWeight);
			note.put("ok", this.ok());
			final ArrayNode array = note.putArray("sets");
			for (final SetShare set : this.sets)
			{
				final ObjectNode entry = array.addObject();
				final ArrayNode fields = entry.putArray("fields");
				for (final String field : set.set.fields)
				{
					fields.add(field);
				}
				entry.put("outcome", set.set.outcome);
				entry.put("share", Math.round(set.share * 1000d) / 1000d);
				entry.put("passesAddon", set.share > ADDON_LOW_THRESHOLD);
			}
			if (!this.suggested.isEmpty())
			{
				final ObjectNode suggestion = note.putObject("suggestedClusteringWeights");
				for (final Map.Entry<String, Integer> entry : this.suggested.entrySet())
				{
					suggestion.put(entry.getKey(), entry.getValue().intValue());
				}
				note.put("suggestionScope", this.scope);
				note.put("suggestionShare", this.suggestedShare);
			}
			return note;
		}
	}

	/** A key set with the share of the total weight it holds. */
	public static final class SetShare
	{
		public final KeySet set;
		public final double share;

		SetShare(final KeySet pSet, final double pShare)
		{
			this.set = pSet;
			this.share = pShare;
		}

		public String fieldsText()
		{
			return String.join(" + ", this.set.fields);
		}

		public String percentText()
		{
			return String.format(java.util.Locale.ROOT, "%.1f %%", this.share * 100d);
		}
	}

	/**
	 * The shares of {@code pSets} under {@code pWeights} (own-table fields, each &gt;= 1) and {@code pFixedWeight} (the relation fields), and - when a set is under the add-on's threshold - the weights
	 * that would lift them. {@code pWeights} is NOT modified.
	 *
	 * @return {@code null} when there is no key set to serve
	 */
	public static Analysis analyze(final Map<String, Integer> pWeights, final int pFixedWeight, final List<KeySet> pSets)
	{
		if (pSets == null || pSets.isEmpty())
		{
			return null;
		}
		final List<SetShare> shares = new ArrayList<SetShare>();
		final List<SetShare> failing = new ArrayList<SetShare>();
		for (final KeySet set : pSets)
		{
			final SetShare share = new SetShare(set, CandidateScoreShare.share(pWeights, pFixedWeight, set));
			shares.add(share);
			if (share.share < FAIL_BELOW)
			{
				failing.add(share);
			}
		}
		final int total = CandidateScoreShare.total(pWeights, pFixedWeight);
		if (failing.isEmpty())
		{
			return new Analysis(total, pWeights, shares, failing, new LinkedHashMap<String, Integer>(), SCOPE_ALL, 0d);
		}
		final List<KeySet> matchSets = new ArrayList<KeySet>();
		for (final KeySet set : pSets)
		{
			if (set.match)
			{
				matchSets.add(set);
			}
		}
		final String[] scopes = matchSets.size() == pSets.size() || matchSets.isEmpty() ? new String[] { SCOPE_ALL } : new String[] { SCOPE_ALL, SCOPE_MATCH };
		for (final String scope : scopes)
		{
			final List<KeySet> wanted = SCOPE_ALL.equals(scope) ? pSets : matchSets;
			for (final int[] share : LADDER)
			{
				final Map<String, Integer> attempt = new LinkedHashMap<String, Integer>(pWeights);
				if (CandidateScoreShare.attempt(attempt, pFixedWeight, wanted, share[0], share[1]))
				{
					final Map<String, Integer> changed = new LinkedHashMap<String, Integer>();
					for (final Map.Entry<String, Integer> entry : attempt.entrySet())
					{
						if (entry.getValue().intValue() != pWeights.get(entry.getKey()).intValue())
						{
							changed.put(entry.getKey(), entry.getValue());
						}
					}
					if (changed.isEmpty())
					{
						// every failing set is one no weight can lift (its members all count under the share): nothing to suggest
						return new Analysis(total, pWeights, shares, failing, changed, SCOPE_NONE, 0d);
					}
					return new Analysis(total, pWeights, shares, failing, changed, scope, (double) share[0] / (double) share[1]);
				}
			}
		}
		return new Analysis(total, pWeights, shares, failing, new LinkedHashMap<String, Integer>(), SCOPE_NONE, 0d);
	}

	/** One attempt at a share: raises {@code pWeights} (a copy) and answers whether every reachable set of {@code pWanted} now holds {@code pNum/pDen} of the total. */
	private static boolean attempt(final Map<String, Integer> pWeights, final int pFixedWeight, final List<KeySet> pWanted, final int pNum, final int pDen)
	{
		final List<KeySet> reachable = new ArrayList<KeySet>();
		for (final KeySet set : pWanted)
		{
			int best = 0;
			for (final String field : set.fields)
			{
				best = Math.max(best, set.percents.get(field).intValue());
			}
			// a set whose members all count under the target share can never hold it, whatever their weight: nothing to serve
			if ((long) best * pDen > (long) pNum * 100L)
			{
				reachable.add(set);
			}
		}
		if (reachable.isEmpty())
		{
			return false;
		}
		for (int raises = 0; raises <= MAX_RAISES; raises++)
		{
			final int total = CandidateScoreShare.total(pWeights, pFixedWeight);
			KeySet worst = null;
			long worstDeficiency = 0L;
			for (final KeySet set : reachable)
			{
				final long deficiency = (long) pNum * 100L * total - CandidateScoreShare.contribution100(pWeights, set) * pDen;
				if (deficiency > worstDeficiency)
				{
					worstDeficiency = deficiency;
					worst = set;
				}
			}
			if (worst == null)
			{
				return true;
			}
			String pick = null;
			for (final String field : worst.fields)
			{
				if (pick == null || worst.percents.get(field).intValue() > worst.percents.get(pick).intValue()
					|| (worst.percents.get(field).intValue() == worst.percents.get(pick).intValue() && pWeights.get(field).intValue() < pWeights.get(pick).intValue()))
				{
					pick = field;
				}
			}
			pWeights.put(pick, Integer.valueOf(pWeights.get(pick).intValue() + 1));
		}
		return false;
	}

	private static int total(final Map<String, Integer> pWeights, final int pFixedWeight)
	{
		int total = pFixedWeight;
		for (final Integer weight : pWeights.values())
		{
			total += weight.intValue();
		}
		return total;
	}

	/** sum(weight x percent) over the set: the set score of a pair that satisfies it, times 100. */
	private static long contribution100(final Map<String, Integer> pWeights, final KeySet pSet)
	{
		long sum = 0L;
		for (final String field : pSet.fields)
		{
			sum += (long) pWeights.get(field).intValue() * pSet.percents.get(field).intValue();
		}
		return sum;
	}

	/** The share of the total weight a pair satisfying the set scores (0..1). */
	public static double share(final Map<String, Integer> pWeights, final int pFixedWeight, final KeySet pSet)
	{
		final int total = CandidateScoreShare.total(pWeights, pFixedWeight);
		return total == 0 ? 0d : (double) CandidateScoreShare.contribution100(pWeights, pSet) / (100d * total);
	}

	/**
	 * The {@code clusteringWeights} that hold every key set of a proposal's tree above the add-on's threshold: the arguments of a {@code propose_match_plan} call ({@code root}, {@code nodes},
	 * {@code clusteringFields}, optional {@code clusteringWeights}) as they stand, analysed through {@link MatchingPolicyTranslator#analyzeShares}. Empty when nothing fails or nothing can be suggested.
	 * Used only for the plan the Factory builds itself ({@link MatchPlanFallback}); a model's proposal is told the numbers instead.
	 */
	public static ObjectNode suggestFor(final JsonNode pArguments)
	{
		final ObjectNode out = MAPPER.createObjectNode();
		final ObjectNode requirements = MAPPER.createObjectNode();
		final ObjectNode tree = requirements.putObject("decisionTree");
		tree.put("root", pArguments.path("root").asText(""));
		tree.set("nodes", pArguments.path("nodes"));
		requirements.set("clusteringFields", pArguments.path("clusteringFields"));
		if (pArguments.has("clusteringWeights"))
		{
			requirements.set("clusteringWeights", pArguments.path("clusteringWeights"));
		}
		final Analysis analysis;
		try
		{
			analysis = MatchingPolicyTranslator.analyzeShares(requirements);
		}
		catch (final RuntimeException ex)
		{
			return out;
		}
		if (analysis == null || analysis.ok())
		{
			return out;
		}
		for (final Map.Entry<String, Integer> entry : analysis.suggested.entrySet())
		{
			out.put(entry.getKey(), entry.getValue().intValue());
		}
		return out;
	}
}
