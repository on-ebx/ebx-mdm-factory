package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lot M3 (2026-10-17, {@code docs/MATCHING.md} &sect;56): the two findings a plan card shows about the exact keys of a matching plan, computed from the stored plan
 * ({@code requirements.decisionTree} + {@code requirements.keyFacts}) alone - pure (Jackson and {@link KeyFacts} only), evaluated when the card is drawn, never
 * stored, so a plan proposed before this lot (no {@code keyFacts}) simply has none. Nothing here rewrites a plan: a finding only says what the measured data allows.
 * <ol>
 * <li>{@link #KIND_SAFE_AUTOMATIC_PATH}: a path of the tree that ends in a data steward's review ({@code SUSPECTED}) and whose exact key {@code K} is, on the data,
 * safe (see {@link KeyFacts.Agreement#safe()}) with an independent key {@code J}: among the pairs of records sharing {@code K}, at least 99% of those that can be
 * compared also share {@code J} and none differ. Said only when the plan has no {@code MATCH} leaf at all. The finding names the pairs and tells whether {@code J}
 * is already required on the path or would have to be added.</li>
 * <li>{@link #KIND_UNREACHABLE_SOURCE}: the candidate filter of the plan (the exact keys the add-on searches candidates with,
 * {@link CandidateFilterCheck}) leaves a source system mostly unreachable: at least {@link KeyFacts#UNREACHABLE_SHARE} of its records fill none of those keys, so
 * they can never be proposed as a duplicate of anything. It names the keys that source does fill.</li>
 * </ol>
 */
public final class KeyFindings
{
	public static final String KIND_SAFE_AUTOMATIC_PATH = "SAFE_AUTOMATIC_PATH";
	public static final String KIND_UNREACHABLE_SOURCE = "UNREACHABLE_SOURCE";

	private KeyFindings()
	{
	}

	/** One finding: structured (the screens word it in the user's language), with an English sentence for logs and the stored rationale. */
	public static final class Finding
	{
		public final String kind;
		/** SAFE_AUTOMATIC_PATH: the label of the path ({@code N1}, {@code N2 > N3}). */
		public final String path;
		/** SAFE_AUTOMATIC_PATH: the key of the path the agreement is about. UNREACHABLE_SOURCE: null. */
		public final String key;
		/** SAFE_AUTOMATIC_PATH: the independent key that agrees. */
		public final String partner;
		/** SAFE_AUTOMATIC_PATH: true when {@link #partner} is already required by the path, false when it would have to be added. */
		public final boolean partnerInPath;
		/** SAFE_AUTOMATIC_PATH: pairs sharing {@link #key}; the number of those also sharing {@link #partner} is {@link #agree}. UNREACHABLE_SOURCE: records of the system. */
		public final long pairs;
		public final long agree;
		public final long conflicts;
		/** UNREACHABLE_SOURCE: the source system, and how many of its records fill none of the filter keys. */
		public final String system;
		public final long unreached;
		/** UNREACHABLE_SOURCE: the filter keys; {@link #alternatives}: keys the system fills well that the filter does not use, best first, with their fill share (0-100). */
		public final List<String> filterKeys;
		public final List<String> alternatives;
		public final List<Integer> alternativeShares;

		Finding(final String pKind, final String pPath, final String pKey, final String pPartner, final boolean pPartnerInPath, final long pPairs, final long pAgree,
			final long pConflicts, final String pSystem, final long pUnreached, final List<String> pFilterKeys, final List<String> pAlternatives,
			final List<Integer> pAlternativeShares)
		{
			this.kind = pKind;
			this.path = pPath;
			this.key = pKey;
			this.partner = pPartner;
			this.partnerInPath = pPartnerInPath;
			this.pairs = pPairs;
			this.agree = pAgree;
			this.conflicts = pConflicts;
			this.system = pSystem;
			this.unreached = pUnreached;
			this.filterKeys = pFilterKeys;
			this.alternatives = pAlternatives;
			this.alternativeShares = pAlternativeShares;
		}

		/** English, one or two sentences, no trailing blank. */
		public String text()
		{
			if (KIND_SAFE_AUTOMATIC_PATH.equals(this.kind))
			{
				return "Path " + this.path + " is safe to merge automatically: " + this.pairs + " pair(s) of records share " + this.key + ", " + this.agree + " of the "
					+ (this.agree + this.conflicts) + " that can be compared also share " + this.partner + ", " + this.conflicts + " differ."
					+ (this.partnerInPath ? "" : " It ends in a review today: require " + this.key + " and " + this.partner + " together to make it a confirmed match.");
			}
			final StringBuilder out = new StringBuilder("Source " + this.system + ": " + this.unreached + " of " + this.pairs + " record(s) fill none of the candidate-filter keys ("
				+ String.join(", ", this.filterKeys) + "), so they can never be proposed as duplicates.");
			if (!this.alternatives.isEmpty())
			{
				out.append(" It fills ");
				for (int i = 0; i < this.alternatives.size(); i++)
				{
					out.append(i == 0 ? "" : ", ").append(this.alternatives.get(i)).append(" (").append(this.alternativeShares.get(i)).append("%)");
				}
				out.append('.');
			}
			return out.toString();
		}
	}

	/** Every finding of the stored plan, safe-path findings first. Empty when the plan has no tree or no {@code keyFacts}. */
	public static List<Finding> check(final JsonNode pRequirements)
	{
		final List<Finding> out = new ArrayList<Finding>();
		if (pRequirements == null || pRequirements.isMissingNode())
		{
			return out;
		}
		final KeyFacts facts = KeyFacts.fromJson(pRequirements.path("keyFacts"));
		if (facts == null)
		{
			return out;
		}
		final CandidateFilterCheck.Result filter = CandidateFilterCheck.check(pRequirements);
		out.addAll(KeyFindings.safeAutomaticPaths(filter, facts));
		if (filter.filtered())
		{
			out.addAll(KeyFindings.unreachableSources(facts, filter.keyFields()));
		}
		return out;
	}

	/** True when a path of the tree ends in {@code MATCH}. */
	static boolean hasMatchLeaf(final CandidateFilterCheck.Result pFilter)
	{
		for (final CandidateFilterCheck.PathResult path : pFilter.paths)
		{
			if ("MATCH".equals(path.outcome))
			{
				return true;
			}
		}
		return false;
	}

	/** The {@link #KIND_SAFE_AUTOMATIC_PATH} findings: none when the plan already has a {@code MATCH} leaf, or the facts are only a sample. */
	public static List<Finding> safeAutomaticPaths(final CandidateFilterCheck.Result pFilter, final KeyFacts pFacts)
	{
		final List<Finding> out = new ArrayList<Finding>();
		if (pFacts.sampled() || KeyFindings.hasMatchLeaf(pFilter))
		{
			return out;
		}
		final Set<String> reported = new LinkedHashSet<String>();
		for (final CandidateFilterCheck.PathResult path : pFilter.paths)
		{
			if (!"SUSPECTED".equals(path.outcome) || !path.hasKey() || !reported.add(path.label()))
			{
				continue;
			}
			Finding best = null;
			for (final String key : path.keyFields)
			{
				if (pFacts.key(key) == null)
				{
					continue;
				}
				for (final KeyFacts.Agreement agreement : pFacts.agreements())
				{
					if (!agreement.key.equals(key) || !pFacts.safePair(key, agreement.other))
					{
						continue;
					}
					final boolean inPath = path.keyFields.contains(agreement.other);
					final Finding candidate = new Finding(KIND_SAFE_AUTOMATIC_PATH, path.label(), key, agreement.other, inPath, agreement.pairs, agreement.agree, agreement.conflict,
						null, 0, Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.<Integer>emptyList());
					// a partner already required by the path says more than one to add; then the larger evidence
					if (best == null || (candidate.partnerInPath && !best.partnerInPath) || (candidate.partnerInPath == best.partnerInPath && candidate.pairs > best.pairs))
					{
						best = candidate;
					}
				}
			}
			if (best != null)
			{
				out.add(best);
			}
		}
		return out;
	}

	/**
	 * The {@link #KIND_UNREACHABLE_SOURCE} findings for a candidate filter made of {@code pFilterKeys}: one per source system with at least
	 * {@link KeyFacts#MIN_SYSTEM_RECORDS} records of which {@link KeyFacts#UNREACHABLE_SHARE} or more fill none of them. Empty when a filter key is not one of the
	 * measured keys (nothing can then be said about it).
	 */
	public static List<Finding> unreachableSources(final KeyFacts pFacts, final Collection<String> pFilterKeys)
	{
		final List<Finding> out = new ArrayList<Finding>();
		if (pFacts.sampled() || pFilterKeys == null || pFilterKeys.isEmpty())
		{
			return out;
		}
		for (final String key : pFilterKeys)
		{
			if (pFacts.key(key) == null)
			{
				return out;
			}
		}
		final Map<String, long[]> reach = pFacts.recordsReachedBy(pFilterKeys);
		for (final Map.Entry<String, long[]> entry : reach.entrySet())
		{
			final long records = entry.getValue()[0];
			final long unreached = records - entry.getValue()[1];
			if (records < KeyFacts.MIN_SYSTEM_RECORDS || unreached == 0 || (double) unreached / (double) records < KeyFacts.UNREACHABLE_SHARE)
			{
				continue;
			}
			final List<String[]> alternatives = new ArrayList<String[]>();
			for (final KeyFacts.KeyStat stat : pFacts.keys())
			{
				if (pFilterKeys.contains(stat.name))
				{
					continue;
				}
				final Integer filled = stat.filledBySystem.get(entry.getKey());
				final int share = filled == null ? 0 : (int) Math.round(100d * filled.intValue() / records);
				if (share >= 50)
				{
					alternatives.add(new String[] { stat.name, String.valueOf(share) });
				}
			}
			Collections.sort(alternatives, new Comparator<String[]>()
			{
				@Override
				public int compare(final String[] pA, final String[] pB)
				{
					return Integer.compare(Integer.parseInt(pB[1]), Integer.parseInt(pA[1]));
				}
			});
			final List<String> names = new ArrayList<String>();
			final List<Integer> shares = new ArrayList<Integer>();
			for (final String[] alternative : alternatives)
			{
				if (names.size() >= 3)
				{
					break;
				}
				names.add(alternative[0]);
				shares.add(Integer.valueOf(alternative[1]));
			}
			out.add(new Finding(KIND_UNREACHABLE_SOURCE, null, null, null, false, records, 0, 0, entry.getKey(), unreached, new ArrayList<String>(pFilterKeys), names, shares));
		}
		return out;
	}

	/** {@code 44%}: the share of {@code pPart} in {@code pWhole}, rounded, for the screens. */
	public static String percent(final long pPart, final long pWhole)
	{
		return pWhole <= 0 ? "0%" : String.format(Locale.ROOT, "%d%%", Long.valueOf(Math.round(100d * pPart / pWhole)));
	}
}
