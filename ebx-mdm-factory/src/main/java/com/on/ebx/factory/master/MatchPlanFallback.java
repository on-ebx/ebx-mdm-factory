package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.matching.KeyFacts;

/**
 * Lot M9 (2026-10-18, {@code docs/MATCHING.md} &sect;63): the Factory's own matching plan for an entity the language model left WITHOUT an accepted plan although its
 * key facts were measured - built from the facts alone, by the structural rules the pass already enforces on every model proposal, never from a domain heuristic or
 * a column name.
 * <p>
 * Live run 497 (project {@code E2EMASTER20261018005904}): the person entity ended with no plan after five refused proposals (two identical {@code UNKNOWN_BRANCH_TARGET}
 * ended it), while the same model had written a valid 18-leaf plan for the same entity in the run before. The failure is variance of the model; without a plan on the
 * person entity nothing of the master phase can be measured. The fallback is the Factory's answer to that variance: a conservative plan a person reviews (confidence
 * {@link #CONFIDENCE}, status PROPOSED, origin RULE), replaced by the model's plan as soon as someone asks for another proposal.
 * <p>
 * The shape, from the facts of the entity ({@link KeyFacts}: the exact keys, their fill, their sharing, and which pairs of independent keys are SAFE):
 * <ol>
 * <li>root: an {@code ALL_FIELDS} node on the strongest SAFE pair of independent keys, exact at 100, nulls NOT_MATCH, {@code onTrue} MATCH - the one node the pass accepts
 * as a MATCH on a measured justification ({@link MeasuredMatchPath}). "Strongest": the most comparable pairs, the Factory's own order ({@link MeasuredMatchPath#safePairs}),
 * among the keys that may stand in a node at all - a coarse key (more than {@link #MAX_PAIRS_PER_FILLED_RECORD} pairs per filled record: the first name, 5,660 pairs for
 * 534 records) never does, so a pair made of a key and a coarse component of it (the name key with the first name: 694 of 694 agree) is not a candidate; ties go to the
 * pair whose weaker key is the more selective;</li>
 * <li>then one {@code ALL_FIELDS} node per {@linkplain #eligibleKeys near-unique exact key}, alone, exact at 100, nulls NOT_MATCH, {@code onTrue} SUSPECTED - the pairs that
 * share one strong key and not the other are the ones a steward should see (lot M3), and a key only one source system fills still reaches that system (lot M3's reach rule);</li>
 * <li>then, when the entity carries a derived child-keys attribute (lot M8a), one {@code ALL_FIELDS} node on it with {@code exactFullText} at the ceiling the pass computes
 * ({@link MatchPlanLlmPass#childKeysScoreCeiling}, at most {@link MatchPlanLlmPass#CHILD_KEYS_MAX_MINIMUM_SCORE}), {@code onTrue} SUSPECTED - lot M14: <b>never alone</b>: the node also
 * requires an exact key of the record itself ({@link #partnerKey}), because child evidence about the addresses says nothing about the record when the address is coarse;</li>
 * <li>{@code NOT_MATCHED} for everything else.</li>
 * </ol>
 * Every path to SUSPECTED or MATCH starts with an exact key of the record itself, so the candidate search is filtered (lot M8a). No {@code clusteringFields}: the policy
 * translator declares every field the tree compares anyway ({@code MatchingPolicyTranslator}, "the UNION of every field the decision tree compares and every clusteringFields
 * entry"), and the pre-clustering dominance of a standalone key is applied from the tree.
 * <p>
 * The arguments built here are those of a {@code propose_match_plan} call: the pass runs them through the SAME checks as a model proposal ({@code
 * MatchPlanLlmPass.Session#proposeFallbacks()}). When a check refuses them, the entity stays without a plan and the reason is recorded.
 */
final class MatchPlanFallback
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The confidence of a fallback plan: a starting point to review, never a recommendation. */
	static final int CONFIDENCE = 40;
	/**
	 * A key whose records share, on average, more than this many pairs each groups records (a postal code, a first name) instead of identifying them: never a SUSPECTED node
	 * on its own. The name key of the B2C scenario (694 pairs for 534 filled records) passes; its first name (5,660 pairs) does not.
	 */
	static final int MAX_PAIRS_PER_FILLED_RECORD = KeyFacts.MAX_PAIRS_PER_FILLED_RECORD;
	/** How much of the last refusal the stored plan and the rationale quote. */
	static final int LAST_REFUSAL_IN_PLAN_MAX_LENGTH = 400;
	private static final String NOT_MATCH = "NOT_MATCH";

	private MatchPlanFallback()
	{
	}

	/** The result of {@link #build}: the arguments of the proposal, or the reason none could be built. */
	static final class Built
	{
		/** Null when no fallback can be built; {@link #reason} then says why. */
		final ObjectNode arguments;
		final String reason;
		/** One line naming the nodes, for the note of the run. */
		final String summary;

		private Built(final ObjectNode pArguments, final String pReason, final String pSummary)
		{
			this.arguments = pArguments;
			this.reason = pReason == null ? "" : pReason;
			this.summary = pSummary == null ? "" : pSummary;
		}

		static Built none(final String pReason)
		{
			return new Built(null, pReason, null);
		}
	}

	/**
	 * Lot M13: the same plan, offered to the model BEFORE its first proposal as a starting point ("rules first, the model refines"): built from the facts exactly as the fallback
	 * is, with a rationale that says what it is instead of quoting refused proposals that never happened.
	 */
	static Built buildStartingPlan(final MatchPlanLlmPass.EntityInfo pEntity, final MatchPlanLlmPass.AllowedVocabulary pVocabulary)
	{
		return MatchPlanFallback.build(pEntity, pVocabulary, -1, "");
	}

	/** An exact key of the facts that qualifies for a node of its own, with the measures the ranking uses. */
	static final class Key
	{
		final String name;
		/** Distinct values among the filled records (0..1): the pass's own identifier measure. */
		final double selectivity;
		final KeyFacts.KeyStat stat;

		Key(final KeyFacts.KeyStat pStat)
		{
			this.name = pStat.name;
			this.stat = pStat;
			final int filled = pStat.filledTotal();
			this.selectivity = filled <= 0 ? 0d : (double) pStat.distinct / (double) filled;
		}
	}

	/**
	 * The arguments of the fallback proposal for {@code pEntity}, or why none can be built.
	 *
	 * @param pAttempts how many proposals the model made for the entity (all refused)
	 * @param pLastRefusal the last refusal the model read, quoted in the rationale
	 */
	static Built build(final MatchPlanLlmPass.EntityInfo pEntity, final MatchPlanLlmPass.AllowedVocabulary pVocabulary, final int pAttempts, final String pLastRefusal)
	{
		if (pEntity == null)
		{
			return Built.none("no entity");
		}
		if (pEntity.childOfParent != null)
		{
			return Built.none("'" + pEntity.name + "' is a child entity of '" + pEntity.childOfParent + "': it follows its parent and has no matching plan of its own (lot M2).");
		}
		final KeyFacts facts = pEntity.keyFacts;
		if (facts == null)
		{
			return Built.none("no key facts were measured on '" + pEntity.name + "' - the fallback plan is built from them alone.");
		}
		if (facts.sampled())
		{
			return Built.none("the key facts of '" + pEntity.name + "' were counted on a sample of the table only: no pair of keys can be marked SAFE and the fallback plan needs the complete table.");
		}
		final MatchPlanLlmPass.AllowedVocabulary vocabulary = pVocabulary == null ? new MatchPlanLlmPass.AllowedVocabulary(null, null, null) : pVocabulary;
		if (!vocabulary.algorithms.contains(MatchPlanLlmPass.EXACT_ALGORITHM))
		{
			return Built.none("the allowed algorithms do not include " + MatchPlanLlmPass.EXACT_ALGORITHM + ", which every node of the fallback plan uses.");
		}
		if (!vocabulary.treeNullHandlings.contains(MatchPlanFallback.NOT_MATCH))
		{
			return Built.none("the decision-tree null handlings do not include " + MatchPlanFallback.NOT_MATCH + ", which every field of the fallback plan uses.");
		}

		// the chain of SUSPECTED nodes: keys the data shows to carry identity; the MATCH root: the strongest SAFE pair among the keys that may be compared exactly at all
		final List<Key> keys = MatchPlanFallback.eligibleKeys(pEntity, facts, true);
		final KeyFacts.Agreement rootPair = MatchPlanFallback.strongestSafePair(facts, MatchPlanFallback.eligibleKeys(pEntity, facts, false));
		final List<MatchPlanLlmPass.AttributeInfo> childKeys = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		final List<String> childKeysDropped = new ArrayList<String>();
		final List<String> childKeysNoPartner = new ArrayList<String>();
		// lot M14: child evidence never carries a path alone - it needs a key of the record itself beside it
		final Key partner = MatchPlanFallback.partnerKey(pEntity, facts, keys);
		for (final MatchPlanLlmPass.AttributeInfo attribute : MatchPlanFallback.childKeyAttributes(pEntity, vocabulary))
		{
			// lot M10a: the path rule (PathEvidenceRule) would refuse a node on a measured key that does not identify a record when other keys do
			if (partner == null)
			{
				childKeysNoPartner.add(attribute.name);
			}
			else if (facts.key(attribute.name) != null && !facts.predictiveKeys().isEmpty() && !facts.predictive(attribute.name))
			{
				childKeysDropped.add(attribute.name);
			}
			else
			{
				childKeys.add(attribute);
			}
		}
		if (rootPair == null && keys.isEmpty() && childKeys.isEmpty())
		{
			return Built.none("the measured key facts of '" + pEntity.name + "' offer no exact key of the record itself that identifies it (" + MatchPlanFallback.keyNames(facts)
				+ " were measured: none is both shared and selective enough for a node of its own), and the entity has no derived child keys"
				+ (childKeysDropped.isEmpty() ? "." : " that identify a record (" + String.join(", ", childKeysDropped) + " did not: the pairs that share it do not share an independent key often enough).")
				+ (childKeysNoPartner.isEmpty() ? "" : " Derived child keys (" + String.join(", ", childKeysNoPartner)
					+ ") never carry a path alone and the entity has no exact key of its own to stand beside them."));
		}

		final ArrayNode nodes = MatchPlanFallback.MAPPER.createArrayNode();
		final List<String> summary = new ArrayList<String>();
		final List<String> explained = new ArrayList<String>();
		int index = 0;
		// every node's onFalse is the next node; the last one falls to NOT_MATCHED - set once the list is complete
		if (rootPair != null)
		{
			index++;
			final ObjectNode node = MatchPlanFallback.node(nodes, "F" + index, DecisionTreePlan.FUNCTION_ALL_FIELDS, DecisionTreePlan.LEAF_MATCH);
			final ArrayNode fields = node.putArray("fields");
			MatchPlanFallback.exactField(fields, rootPair.key, MatchPlanLlmPass.EXACT_ALGORITHM, 100);
			MatchPlanFallback.exactField(fields, rootPair.other, MatchPlanLlmPass.EXACT_ALGORITHM, 100);
			summary.add("MATCH on " + rootPair.key + " + " + rootPair.other);
			explained.add("records that share both " + rootPair.key + " and " + rootPair.other + " are the same record (SAFE pair: " + rootPair.pairs + " pair(s) share " + rootPair.key
				+ ", " + rootPair.agree + " of the " + rootPair.comparable() + " that can be compared also share " + rootPair.other + ", " + rootPair.conflict + " differ) - MATCH");
		}
		final List<String> suspectedKeys = new ArrayList<String>();
		for (final Key key : keys)
		{
			index++;
			final ObjectNode node = MatchPlanFallback.node(nodes, "F" + index, DecisionTreePlan.FUNCTION_ALL_FIELDS, DecisionTreePlan.LEAF_SUSPECTED);
			MatchPlanFallback.exactField(node.putArray("fields"), key.name, MatchPlanLlmPass.EXACT_ALGORITHM, 100);
			suspectedKeys.add(key.name);
		}
		if (!suspectedKeys.isEmpty())
		{
			summary.add("SUSPECTED on " + String.join(", ", suspectedKeys) + " (each alone)");
			explained.add("records that share one exact key alone (" + String.join(", ", suspectedKeys) + (suspectedKeys.size() > 1 ? ", in that order" : "") + ") are shown to a steward - SUSPECTED");
		}
		for (final MatchPlanLlmPass.AttributeInfo attribute : childKeys)
		{
			index++;
			final int ceiling = Math.min(MatchPlanLlmPass.childKeysScoreCeiling(MatchPlanLlmPass.maxChildrenPerParent(pEntity, attribute)), MatchPlanLlmPass.CHILD_KEYS_MAX_MINIMUM_SCORE);
			final ObjectNode node = MatchPlanFallback.node(nodes, "F" + index, DecisionTreePlan.FUNCTION_ALL_FIELDS, DecisionTreePlan.LEAF_SUSPECTED);
			final ArrayNode fields = node.putArray("fields");
			MatchPlanFallback.exactField(fields, partner.name, MatchPlanLlmPass.EXACT_ALGORITHM, 100);
			MatchPlanFallback.exactField(fields, attribute.name, MatchPlanLlmPass.EXACT_FULL_TEXT_ALGORITHM, ceiling);
			summary.add("SUSPECTED on " + partner.name + " + shared child keys " + attribute.name + " (exactFullText " + ceiling + ")");
			explained.add("records that share " + partner.name + " AND a child record's key (" + attribute.name + (attribute.childKeysChild == null ? "" : ", the keys of " + attribute.childKeysChild)
				+ ", exactFullText at " + ceiling + ") are shown to a steward - SUSPECTED; child evidence never carries a path alone, it needs an exact key of the record itself beside it");
		}
		for (int i = 0; i < nodes.size(); i++)
		{
			((ObjectNode) nodes.get(i)).put("onFalse", i + 1 < nodes.size() ? nodes.get(i + 1).path("id").asText() : DecisionTreePlan.LEAF_NOT_MATCHED);
		}

		final ObjectNode arguments = MatchPlanFallback.MAPPER.createObjectNode();
		arguments.put("entity", pEntity.name);
		arguments.put("root", "F1");
		arguments.set("nodes", nodes);
		arguments.putArray("clusteringFields");
		// lot M16 (docs/MATCHING.md sec 68): the Factory's own plan is the one place the Factory writes weights - they are part of the plan it proposes (stored in requirements.clusteringWeights, shown on the
		// card, the model may replace them): the ones that hold every key set of the tree above the add-on's candidate threshold, when the default weights do not
		final ObjectNode suggestedWeights = CandidateScoreShare.suggestFor(arguments);
		if (suggestedWeights.size() > 0)
		{
			arguments.set("clusteringWeights", suggestedWeights);
		}
		arguments.put("confidence", MatchPlanFallback.CONFIDENCE);
		arguments.put("rationale", MatchPlanFallback.rationale(pEntity.name, pAttempts, pLastRefusal, explained));
		return new Built(arguments, null, String.join("; ", summary));
	}

	/**
	 * The exact keys of the facts that may stand alone in a SUSPECTED node, most selective first: an attribute of the entity that is not a reference, not a per-source record
	 * key, not a child-keys attribute (compared with {@code exactFullText}, never exact), not the source of another measured key, with no measured format conflict between
	 * source systems (the pass refuses an exact comparison on such a field), shared by at least one pair, at least two distinct values, at most {@link
	 * #MAX_PAIRS_PER_FILLED_RECORD} pairs per filled record - and, with {@code pRequireSafeAsKey} and when the facts mark any pair SAFE, a key whose sharing predicts another
	 * key (a key that is {@linkplain KeyFacts#predictive predictive}, lot M10a): the data then shows it carries identity, where a name key shared by namesakes does not. The MATCH root asks for the
	 * basic conditions only ({@code pRequireSafeAsKey} false): its weaker key is justified by the pair, not on its own (a name key: not SAFE as a key because of namesakes, SAFE
	 * as the partner of an e-mail or phone key).
	 */
	static List<Key> eligibleKeys(final MatchPlanLlmPass.EntityInfo pEntity, final KeyFacts pFacts, final boolean pRequireSafeAsKey)
	{
		final Set<String> basesOfOtherKeys = new LinkedHashSet<String>();
		for (final KeyFacts.KeyStat stat : pFacts.keys())
		{
			if (stat.base != null && !stat.base.equals(stat.name))
			{
				basesOfOtherKeys.add(stat.base);
			}
		}
		// lot M10a: "a key whose sharing predicts another key" is KeyFacts#predictive - the other key must itself be selective. The first definition (the key side of any SAFE
		// agreement) let the name key through on the facts of run 7: it agrees 694 of 694 with the FIRST NAME, a component of itself, while 19% of its pairs are namesakes.
		// the fallback keeps the strict reading (99%, no conflict) where the facts offer one: it is a conservative starting point, and every key it accepts passes PathEvidenceRule a fortiori;
		// where the facts offer only the looser one (90%), that one; where no key predicts anything, no restriction (the path rule does not apply either)
		boolean anySafely = false;
		for (final KeyFacts.KeyStat stat : pFacts.keys())
		{
			anySafely |= pFacts.safelyPredictive(stat.name);
		}
		final boolean anyPredictive = !pFacts.predictiveKeys().isEmpty();
		final List<Key> out = new ArrayList<Key>();
		for (final KeyFacts.KeyStat stat : pFacts.keys())
		{
			if (stat.multiToken || basesOfOtherKeys.contains(stat.name))
			{
				continue;
			}
			final MatchPlanLlmPass.AttributeInfo attribute = pEntity.attribute(stat.name);
			if (attribute == null || MatchPlanLlmPass.AttributeInfo.ROLE_REFERENCE.equals(attribute.role) || MatchPlanLlmPass.AttributeInfo.ROLE_SOURCE_KEY.equals(attribute.role)
				|| attribute.isChildKeys() || attribute.dominantShapeConflict() != null)
			{
				continue;
			}
			final int filled = stat.filledTotal();
			if (stat.pairsSharing <= 0 || stat.distinct < 2 || filled <= 0 || stat.pairsSharing > (long) MatchPlanFallback.MAX_PAIRS_PER_FILLED_RECORD * filled)
			{
				continue;
			}
			if (pRequireSafeAsKey && (anySafely ? !pFacts.safelyPredictive(stat.name) : anyPredictive && !pFacts.predictive(stat.name)))
			{
				continue;
			}
			out.add(new Key(stat));
		}
		Collections.sort(out, new Comparator<Key>()
		{
			@Override
			public int compare(final Key pA, final Key pB)
			{
				final int bySelectivity = Double.compare(pB.selectivity, pA.selectivity);
				return bySelectivity != 0 ? bySelectivity : pA.name.compareTo(pB.name);
			}
		});
		return out;
	}

	/**
	 * The SAFE pair of independent keys the MATCH root is built on, or null: both keys {@linkplain #eligibleKeys eligible}, ranked by comparable pairs (the Factory's own
	 * "strongest first"), then by the selectivity of the WEAKER key, then by name (a pair is counted once whatever its direction; the agreement kept is the one with the most
	 * comparable pairs).
	 */
	static KeyFacts.Agreement strongestSafePair(final KeyFacts pFacts, final List<Key> pKeys)
	{
		final Map<String, Key> byName = new LinkedHashMap<String, Key>();
		for (final Key key : pKeys)
		{
			byName.put(key.name, key);
		}
		final Map<String, KeyFacts.Agreement> byPair = new LinkedHashMap<String, KeyFacts.Agreement>();
		for (final KeyFacts.Agreement agreement : pFacts.agreements())
		{
			if (!agreement.safe() || !byName.containsKey(agreement.key) || !byName.containsKey(agreement.other))
			{
				continue;
			}
			final String pairKey = agreement.key.compareTo(agreement.other) < 0 ? agreement.key + "|" + agreement.other : agreement.other + "|" + agreement.key;
			final KeyFacts.Agreement kept = byPair.get(pairKey);
			if (kept == null || agreement.comparable() > kept.comparable())
			{
				byPair.put(pairKey, agreement);
			}
		}
		KeyFacts.Agreement best = null;
		double bestWeaker = -1d;
		for (final KeyFacts.Agreement agreement : byPair.values())
		{
			final double weaker = Math.min(byName.get(agreement.key).selectivity, byName.get(agreement.other).selectivity);
			if (best == null || agreement.comparable() > best.comparable() || agreement.comparable() == best.comparable()
				&& (weaker > bestWeaker || weaker == bestWeaker && (agreement.key + "|" + agreement.other).compareTo(best.key + "|" + best.other) < 0))
			{
				best = agreement;
				bestWeaker = weaker;
			}
		}
		return best;
	}

	/**
	 * Lot M14: the key of the record itself that stands beside a child-keys attribute in its node, since child evidence never carries a path alone. Among the keys that may stand in a node
	 * ({@link #eligibleKeys}, the basic conditions only), the ones that do NOT already stand alone as a SUSPECTED node ({@code pAlone}) come first - beside an e-mail key that already
	 * suspects on its own the child keys would add nothing - then the one that reaches the most source systems (filled in every one), the most records, the most selective, by name.
	 * Null when the entity has no such key.
	 */
	static Key partnerKey(final MatchPlanLlmPass.EntityInfo pEntity, final KeyFacts pFacts, final List<Key> pAlone)
	{
		final List<Key> all = MatchPlanFallback.eligibleKeys(pEntity, pFacts, false);
		final Set<String> aloneNames = new LinkedHashSet<String>();
		for (final Key key : pAlone)
		{
			aloneNames.add(key.name);
		}
		final List<Key> candidates = new ArrayList<Key>();
		for (final Key key : all)
		{
			if (!aloneNames.contains(key.name))
			{
				candidates.add(key);
			}
		}
		if (candidates.isEmpty())
		{
			candidates.addAll(all);
		}
		Key best = null;
		for (final Key key : candidates)
		{
			if (best == null || MatchPlanFallback.partnerOrder(pFacts, key, best) < 0)
			{
				best = key;
			}
		}
		return best;
	}

	private static int partnerOrder(final KeyFacts pFacts, final Key pA, final Key pB)
	{
		final int emptyA = MatchPlanChecklist.emptySystems(pA.stat, pFacts.recordsBySystem()).size();
		final int emptyB = MatchPlanChecklist.emptySystems(pB.stat, pFacts.recordsBySystem()).size();
		if (emptyA != emptyB)
		{
			return Integer.compare(emptyA, emptyB);
		}
		if (pA.stat.filledTotal() != pB.stat.filledTotal())
		{
			return Integer.compare(pB.stat.filledTotal(), pA.stat.filledTotal());
		}
		final int bySelectivity = Double.compare(pB.selectivity, pA.selectivity);
		return bySelectivity != 0 ? bySelectivity : pA.name.compareTo(pB.name);
	}

	/** The derived child-keys attributes of the entity (lot M8a), when the vocabulary offers {@code exactFullText}; empty otherwise. */
	static List<MatchPlanLlmPass.AttributeInfo> childKeyAttributes(final MatchPlanLlmPass.EntityInfo pEntity, final MatchPlanLlmPass.AllowedVocabulary pVocabulary)
	{
		final List<MatchPlanLlmPass.AttributeInfo> out = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		if (!pVocabulary.algorithms.contains(MatchPlanLlmPass.EXACT_FULL_TEXT_ALGORITHM))
		{
			return out;
		}
		for (final MatchPlanLlmPass.AttributeInfo attribute : pEntity.attributes)
		{
			if (attribute.isChildKeys())
			{
				out.add(attribute);
			}
		}
		return out;
	}

	private static ObjectNode node(final ArrayNode pNodes, final String pId, final String pFunction, final String pOnTrue)
	{
		final ObjectNode node = pNodes.addObject();
		node.put("id", pId);
		node.put("function", pFunction);
		node.put("onTrue", pOnTrue);
		return node;
	}

	private static void exactField(final ArrayNode pFields, final String pName, final String pAlgorithm, final int pMinimumScore)
	{
		final ObjectNode field = pFields.addObject();
		field.put("field", pName);
		field.put("algorithm", pAlgorithm);
		field.put("minimumScore", pMinimumScore);
		field.put("bothNullValueHandling", MatchPlanFallback.NOT_MATCH);
		field.put("oneNullValueHandling", MatchPlanFallback.NOT_MATCH);
	}

	/** The rationale stored with the plan: what it is, why it exists, how it was built, and that the model's plan replaces it on request. English, like every stored rationale. */
	static String rationale(final String pEntity, final int pAttempts, final String pLastRefusal, final List<String> pExplained)
	{
		final StringBuilder out = pAttempts < 0
			? new StringBuilder("Factory starting plan for '" + pEntity + "', built from the measured key facts alone, by the rules the Factory applies to every plan: ")
			: new StringBuilder("Factory fallback plan, not a proposal of the language model: the model made ").append(pAttempts).append(" proposal(s) for '")
				.append(pEntity).append("' and none was accepted (last refusal: \"").append(MatchPlanFallback.shorten(pLastRefusal, MatchPlanFallback.LAST_REFUSAL_IN_PLAN_MAX_LENGTH))
				.append("\"). Built from the measured key facts alone, by the rules the Factory applies to every plan: ");
		for (int i = 0; i < pExplained.size(); i++)
		{
			out.append(i == 0 ? "" : "; then ").append(pExplained.get(i));
		}
		out.append("; anything else is NOT_MATCHED. Every path starts with an exact key of the record, so the candidate search is filtered. Confidence ").append(MatchPlanFallback.CONFIDENCE)
			.append(pAttempts < 0 ? ": a starting point to refine, not a recommendation."
				: ": a starting point for a person to review, not a recommendation. Asking for another proposal replaces it with the model's plan.");
		return out.toString();
	}

	static String shorten(final String pText, final int pMax)
	{
		if (pText == null)
		{
			return "";
		}
		final String text = pText.trim();
		return text.length() <= pMax ? text : text.substring(0, Math.max(0, pMax - 3)) + "...";
	}

	private static String keyNames(final KeyFacts pFacts)
	{
		final List<String> names = pFacts.keyNames();
		return names.isEmpty() ? "no key" : String.join(", ", names);
	}
}
