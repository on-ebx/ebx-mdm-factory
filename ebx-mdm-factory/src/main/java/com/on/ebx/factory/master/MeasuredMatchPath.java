package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.factory.matching.KeyFacts;

/**
 * Lot M3 (2026-10-17, {@code docs/MATCHING.md} &sect;56): when may a node of a matching plan send a pair to {@code MATCH} although none of its fields is "a real
 * identifier" by the old per-field rule (near-unique among its filled values, never a normalised copy of a business field)?
 * <p>
 * The old rule asked every field of a MATCH-terminal node to be an identifier on its own. A key that two systems really share is by definition NOT near-unique
 * (the same e-mail appears in the web shop and in the loyalty file), and a matching key is a normalised copy of a business field - so no plan could ever confirm a pair,
 * and the live B2C scenario ended with 310 pairs for a steward to decide, 341 of them certain on the data.
 * <p>
 * The rule here is measured instead: an {@code ALL_FIELDS} node of two or more fields on the entity's own exact keys, whose {@code onTrue} is {@code MATCH}, is
 * justified when two of its fields {@code K} and {@code J} are independent keys (different bases) that the entity's {@link KeyFacts} mark SAFE: among the pairs of
 * records that carry the same {@code K}, at least 99% of those whose {@code J} can be compared carry the same {@code J}, none differ, over at least ten comparable
 * pairs - on the complete table, never a sample. The node then requires both: "same {@code K} AND same {@code J}", the independent agreement the facts show to hold
 * everywhere {@code K} is shared. Anything weaker keeps the old rule (and the model routes it to {@code SUSPECTED}). No field name is looked at.
 * <p>
 * <b>Lot M13 (2026-10-18, {@code docs/MATCHING.md} &sect;66): the rule is judged on the PATH, not on the last node.</b> Live run 542: {@code G1 ALL_FIELDS [mobileE164 exact] onTrue G2;
 * G2 ALL_FIELDS [emailKey exact] onTrue MATCH} requires BOTH keys of a SAFE pair to reach the MATCH, and the node-by-node rule refused it twice ("emailKey cannot terminate a
 * MATCH-terminal node on its own") although it is exactly the pair the refusal recommended. A MATCH is now justified by the conjunction of the exact conditions met along the
 * {@code onTrue} edges from the root ({@link #requiredNodes}, {@link #pathKeyNames}, {@link #pathJustification}); an ancestor left through {@code onFalse}, or one on which a
 * missing value would pass, requires nothing.
 */
final class MeasuredMatchPath
{
	private MeasuredMatchPath()
	{
	}

	// ------------------------------------------------------------------------------------------------------------------------------------- lot M13: the path

	/**
	 * Lot M13 (2026-10-18, {@code docs/MATCHING.md} &sect;66): the nodes whose conditions a pair must have met to reach {@code pNode}: every ancestor whose edge toward the node is
	 * {@code onTrue} (root first - an ancestor left through {@code onFalse} says nothing about the pair), then the node itself. An ancestor reached through a node that has several
	 * parents (a tree that was not cloned) ends the walk: only what holds on every route is returned.
	 */
	static List<DecisionTreePlan.Node> requiredNodes(final DecisionTreePlan.Plan pPlan, final DecisionTreePlan.Node pNode)
	{
		final List<DecisionTreePlan.Node> reversed = new ArrayList<DecisionTreePlan.Node>();
		reversed.add(pNode);
		final java.util.Set<String> seen = new java.util.HashSet<String>();
		DecisionTreePlan.Node current = pNode;
		while (current != null && current.id != null && seen.add(current.id))
		{
			DecisionTreePlan.Node parent = null;
			boolean viaTrue = false;
			int incoming = 0;
			for (final DecisionTreePlan.Node candidate : pPlan.nodes)
			{
				if (current.id.equals(candidate.onTrue))
				{
					incoming++;
					parent = candidate;
					viaTrue = true;
				}
				if (current.id.equals(candidate.onFalse))
				{
					incoming++;
					parent = candidate;
					viaTrue = false;
				}
			}
			if (incoming != 1 || parent == null)
			{
				break;
			}
			if (viaTrue)
			{
				reversed.add(parent);
			}
			current = parent;
		}
		java.util.Collections.reverse(reversed);
		return reversed;
	}

	/** Ids of {@link #requiredNodes}, {@code G1 > G2}. */
	static String pathLabel(final List<DecisionTreePlan.Node> pRequired)
	{
		final StringBuilder out = new StringBuilder();
		for (final DecisionTreePlan.Node node : pRequired)
		{
			out.append(out.length() == 0 ? "" : " > ").append(node.id);
		}
		return out.toString();
	}

	/**
	 * The keys the exact conditions of {@code pRequired} bind together - the names the facts can speak about. An ancestor counts only when it is an {@code ALL_FIELDS} node and the field is
	 * a plain attribute (not read through a child, not a parent reference) compared with the exact algorithm, a full score and {@code NOT_MATCH} on both null handlings (a missing
	 * value would otherwise satisfy it: the pair would not need that key at all). The last node, the one whose {@code onTrue} is the MATCH leaf, counts as {@link #justification} always
	 * counted it: its fields were already required to be exact at 100.
	 */
	static List<String> pathKeyNames(final MatchPlanLlmPass.EntityInfo pEntity, final List<DecisionTreePlan.Node> pRequired)
	{
		final List<String> out = new ArrayList<String>();
		for (int i = 0; i < pRequired.size(); i++)
		{
			final DecisionTreePlan.Node node = pRequired.get(i);
			if (!DecisionTreePlan.FUNCTION_ALL_FIELDS.equals(node.function))
			{
				continue;
			}
			final boolean last = i == pRequired.size() - 1;
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				if (field.isRelation() || field.field == null)
				{
					continue;
				}
				MatchPlanLlmPass.AttributeInfo attribute = pEntity.attribute(field.field);
				if (attribute != null && MatchPlanLlmPass.AttributeInfo.ROLE_REFERENCE.equals(attribute.role))
				{
					continue;
				}
				if (!last && !MeasuredMatchPath.strictlyExact(field))
				{
					continue;
				}
				String name = field.field;
				if (attribute != null && attribute.matchKey != null && pEntity.attribute(attribute.matchKey) != null)
				{
					name = attribute.matchKey;
				}
				if (!out.contains(name))
				{
					out.add(name);
				}
			}
		}
		return out;
	}

	/** The exact algorithm (not {@code exactFullText}), a full score, and {@code NOT_MATCH} (absent = {@code NOT_MATCH}) on both null handlings. */
	private static boolean strictlyExact(final DecisionTreePlan.FieldRef pField)
	{
		final String algorithm = pField.algorithm == null ? "" : pField.algorithm.trim();
		final int at = algorithm.lastIndexOf('@');
		if (!"exact".equalsIgnoreCase(at >= 0 ? algorithm.substring(at + 1) : algorithm))
		{
			return false;
		}
		if (pField.minimumScore != null && pField.minimumScore.intValue() < 100)
		{
			return false;
		}
		return MeasuredMatchPath.isNotMatch(pField.bothNull) && MeasuredMatchPath.isNotMatch(pField.oneNull);
	}

	private static boolean isNotMatch(final String pHandling)
	{
		return pHandling == null || pHandling.trim().isEmpty() || pHandling.trim().toUpperCase(java.util.Locale.ROOT).contains("NOT_MATCH");
	}

	/**
	 * Lot M13: the SAFE agreement that justifies a {@code MATCH} reached through {@code onTrue} of {@code pNode} on the CONJUNCTION of the exact conditions met along the path, or null.
	 * Run 542 (2026-10-18): the model wrote {@code G1 ALL_FIELDS [mobileE164 exact] onTrue G2; G2 ALL_FIELDS [emailKey exact] onTrue MATCH} - the MATCH requires BOTH keys, exactly the
	 * SAFE pair the refusal recommended - and the node-by-node rule refused it twice ("emailKey cannot terminate a MATCH-terminal node on its own"). The node itself must be an
	 * {@code ALL_FIELDS} node with {@code MATCH} on its {@code onTrue} edge only; a SAFE pair of independent keys among {@link #pathKeyNames} justifies it, whichever node holds each key.
	 */
	static KeyFacts.Agreement pathJustification(final MatchPlanLlmPass.EntityInfo pEntity, final DecisionTreePlan.Plan pPlan, final DecisionTreePlan.Node pNode)
	{
		final KeyFacts facts = pEntity.keyFacts;
		if (facts == null || facts.sampled() || !DecisionTreePlan.FUNCTION_ALL_FIELDS.equals(pNode.function) || !DecisionTreePlan.LEAF_MATCH.equals(pNode.onTrue)
			|| DecisionTreePlan.LEAF_MATCH.equals(pNode.onFalse))
		{
			return null;
		}
		return MeasuredMatchPath.bestSafePair(facts, MeasuredMatchPath.pathKeyNames(pEntity, MeasuredMatchPath.requiredNodes(pPlan, pNode)));
	}

	private static KeyFacts.Agreement bestSafePair(final KeyFacts pFacts, final List<String> pNames)
	{
		KeyFacts.Agreement best = null;
		for (final String key : pNames)
		{
			for (final String other : pNames)
			{
				if (key.equals(other) || !pFacts.safePair(key, other))
				{
					continue;
				}
				final KeyFacts.Agreement agreement = pFacts.agreement(key, other);
				if (best == null || agreement.comparable() > best.comparable())
				{
					best = agreement;
				}
			}
		}
		return best;
	}

	/**
	 * Lot M13: what the path to a refused {@code MATCH} leaf requires, in a sentence the model can act on - the keys bound so far and that no pair of them is SAFE - so the refusal says
	 * what is missing on THIS path instead of repeating the node's own field.
	 */
	static String pathSentence(final MatchPlanLlmPass.EntityInfo pEntity, final DecisionTreePlan.Plan pPlan, final DecisionTreePlan.Node pNode)
	{
		final KeyFacts facts = pEntity.keyFacts;
		if (facts == null || facts.sampled())
		{
			return "";
		}
		final List<DecisionTreePlan.Node> required = MeasuredMatchPath.requiredNodes(pPlan, pNode);
		final List<String> names = MeasuredMatchPath.pathKeyNames(pEntity, required);
		return " The path to the MATCH of node '" + pNode.id + "' requires "
			+ (names.isEmpty() ? "no exact key" : String.join(" and ", names))
			+ "; keys count together when they sit on the SAME path - an ALL_FIELDS node on each (exact, minimumScore 100, NOT_MATCH on both null handlings) chained through onTrue, or both in one node - and "
			+ (names.size() < 2 ? "a pair needs two independent keys." : "the facts mark no pair of them SAFE.");
	}

	/**
	 * Lot M13: one sentence per {@code MATCH} path of the tree that the measured facts justify through keys held by SEVERAL nodes of the path (what the stored rationale says the data
	 * showed); the single-node case keeps {@link #notes}'s wording.
	 */
	static List<String> pathNotes(final MatchPlanLlmPass.EntityInfo pEntity, final DecisionTreePlan.Plan pPlan)
	{
		final List<String> out = new ArrayList<String>();
		for (final DecisionTreePlan.Node node : pPlan.nodes)
		{
			final KeyFacts.Agreement agreement = MeasuredMatchPath.pathJustification(pEntity, pPlan, node);
			if (agreement == null)
			{
				continue;
			}
			final List<DecisionTreePlan.Node> required = MeasuredMatchPath.requiredNodes(pPlan, node);
			final String names = required.size() > 1 ? agreement.key + " + " + agreement.other : String.join(" + ", MeasuredMatchPath.pathKeyNames(pEntity, required));
			out.add("MATCH path " + MeasuredMatchPath.pathLabel(required) + " (" + names + ") is justified by the measured data: " + agreement.pairs + " pair(s) of records share "
				+ agreement.key + ", " + agreement.agree + " of the " + agreement.comparable() + " that can be compared also share " + agreement.other + ", " + agreement.conflict + " differ");
		}
		return out;
	}

	/** Lot M13: true when {@code pFieldName} is one of the two keys of a pair the entity's complete facts mark SAFE - the only fields a path can justify for a MATCH without an identifier of their own. */
	static boolean belongsToASafePair(final MatchPlanLlmPass.EntityInfo pEntity, final String pFieldName)
	{
		for (final KeyFacts.Agreement agreement : MeasuredMatchPath.safePairs(pEntity))
		{
			if (agreement.key.equals(pFieldName) || agreement.other.equals(pFieldName))
			{
				return true;
			}
		}
		return false;
	}

	/** The pairs of independent keys the entity's facts mark SAFE, strongest first (most comparable pairs); empty when there are no complete facts or none is safe. */
	static List<KeyFacts.Agreement> safePairs(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		final List<KeyFacts.Agreement> safe = new ArrayList<KeyFacts.Agreement>();
		final KeyFacts facts = pEntity.keyFacts;
		if (facts == null || facts.sampled())
		{
			return safe;
		}
		for (final KeyFacts.Agreement agreement : facts.agreements())
		{
			if (agreement.safe())
			{
				safe.add(agreement);
			}
		}
		java.util.Collections.sort(safe, new java.util.Comparator<KeyFacts.Agreement>()
		{
			@Override
			public int compare(final KeyFacts.Agreement pA, final KeyFacts.Agreement pB)
			{
				return Long.compare(pB.comparable(), pA.comparable());
			}
		});
		return safe;
	}

	/**
	 * A sentence for a refusal of a MATCH on a single key: the pairs of independent keys the measured facts mark SAFE (up to four, strongest first), or an empty string
	 * when there are none (no facts, a sample, nothing safe) - so the refusal points at what would be accepted. Superseded in the refusals by {@link #wayOut}, which
	 * always names a way out; kept for callers that only want the list of pairs.
	 */
	static String hint(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		final List<KeyFacts.Agreement> safe = MeasuredMatchPath.safePairs(pEntity);
		if (safe.isEmpty())
		{
			return "";
		}
		return " The measured key facts mark these pairs of independent keys SAFE (an ALL_FIELDS node on BOTH keys, exact, minimumScore 100, may end in MATCH): "
			+ MeasuredMatchPath.pairList(safe) + ".";
	}

	private static String pairList(final List<KeyFacts.Agreement> pSafe)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pSafe.size() && i < 4; i++)
		{
			final KeyFacts.Agreement agreement = pSafe.get(i);
			out.append(i == 0 ? "" : "; ").append(agreement.key).append(" + ").append(agreement.other).append(" (").append(agreement.agree).append(" of ").append(agreement.comparable())
				.append(" comparable pairs agree)");
		}
		return out.toString();
	}

	/**
	 * Lot M5 (2026-10-18, {@code docs/MATCHING.md} &sect;60): what a refusal of a MATCH-terminal node says to do INSTEAD, computed from the facts - never a refusal that names no way out
	 * (live: the same "not near-unique" sentence four times in a row until the token budget ended the run, on an entity where no pair of keys could be SAFE).
	 * <ul>
	 * <li>the field belongs to a SAFE pair: "pair it with X" (the node M3 accepts), and the SUSPECTED path otherwise;</li>
	 * <li>SAFE pairs exist but not for this field: those pairs, and "end this path in SUSPECTED";</li>
	 * <li>no pair is SAFE (facts complete): "no SAFE pair is measured on this entity: end this path in SUSPECTED";</li>
	 * <li>no complete facts (none read, or a sample): the same way out, without claiming a measurement.</li>
	 * </ul>
	 *
	 * @param pFieldName the field the refusal is about, as the pass resolved it (after matching-key substitution); null for none
	 */
	static String wayOut(final MatchPlanLlmPass.EntityInfo pEntity, final String pFieldName)
	{
		final KeyFacts facts = pEntity.keyFacts;
		if (facts == null)
		{
			return " No key facts were measured on this entity, so no pair of keys can be accepted for a MATCH: end this path in SUSPECTED.";
		}
		if (facts.sampled())
		{
			return " The key facts of this entity were counted on a sample of the table only, so no pair of keys can be accepted for a MATCH: end this path in SUSPECTED.";
		}
		final List<KeyFacts.Agreement> safe = MeasuredMatchPath.safePairs(pEntity);
		if (safe.isEmpty())
		{
			return " No SAFE pair of independent keys is measured on this entity: end this path in SUSPECTED (a MATCH is possible only on a single field that is itself a near-unique identifier).";
		}
		final List<String> partners = new ArrayList<String>();
		final List<KeyFacts.Agreement> mine = new ArrayList<KeyFacts.Agreement>();
		for (final KeyFacts.Agreement agreement : safe)
		{
			final String partner = pFieldName == null ? null : pFieldName.equals(agreement.key) ? agreement.other : pFieldName.equals(agreement.other) ? agreement.key : null;
			if (partner != null)
			{
				mine.add(agreement);
				if (!partners.contains(partner))
				{
					partners.add(partner);
				}
			}
		}
		if (!mine.isEmpty())
		{
			return " Pair it with " + String.join(" or ", partners) + ". The measured key facts mark these pairs of independent keys SAFE (an ALL_FIELDS node on BOTH keys, exact, minimumScore 100, may end in MATCH): "
				+ MeasuredMatchPath.pairList(mine) + ". Otherwise end this path in SUSPECTED.";
		}
		return MeasuredMatchPath.hint(pEntity) + " '" + pFieldName + "' is not part of any of them: end this path in SUSPECTED, or build the MATCH path on one of those pairs.";
	}

	/**
	 * Lot M5: the line of the prompt, per entity, that says up front whether any path of this entity can end in MATCH - so the model does not try a MATCH that cannot be accepted.
	 * Counts only, never a column name beyond the keys' own attribute names already listed above it.
	 */
	static String verdictLine(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		final KeyFacts facts = pEntity.keyFacts;
		final String tail = " Any other MATCH is refused; a path that is not backed by one of these ends in SUSPECTED.\n";
		final String pairs = " Only an ALL_FIELDS node on both keys of one of those pairs may end in MATCH - both keys in that node, or one in a node above it, chained through onTrue "
			+ "(a MATCH is judged on every exact condition its path requires).";
		if (facts == null)
		{
			return "  MATCH paths on " + pEntity.name + ": no key facts were measured on it, so no pair of keys is marked SAFE. Only a single field that is itself a near-unique "
				+ "identifier (fill rate and distinct-among-filled ratio above the thresholds given in the rules) may end in MATCH." + tail;
		}
		if (facts.sampled())
		{
			return "  MATCH paths on " + pEntity.name + ": the key facts above were counted on a sample of the table only, so no pair of keys can be marked SAFE. Only a single "
				+ "field that is itself a near-unique identifier may end in MATCH." + tail;
		}
		final List<KeyFacts.Agreement> safe = MeasuredMatchPath.safePairs(pEntity);
		if (safe.isEmpty())
		{
			return "  MATCH paths on " + pEntity.name + ": NO pair of independent keys is marked SAFE on this entity. Do not build a MATCH path on keys that are not near-unique "
				+ "identifiers by themselves (a normalised copy of a field never is one): end every such path in SUSPECTED." + tail;
		}
		return "  MATCH paths on " + pEntity.name + ": " + safe.size() + " pair(s) of independent keys are marked SAFE (" + MeasuredMatchPath.pairList(safe)
			+ ")." + pairs + tail;
	}
}
