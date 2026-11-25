package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.matching.KeyFacts;

/**
 * Lot M13 (2026-10-18, {@code docs/MATCHING.md} &sect;66): what the model is told about an entity BEFORE its first proposal, built from the entity's own measured facts.
 * <p>
 * Live run 542 (project {@code E2EMASTER20261018073824}, entity {@code Client}): six {@code propose_match_plan} calls, each refused on a different rule - child evidence required, the
 * child-keys threshold above 50, a field entirely empty for one source system, a path whose candidate search was not filtered, and the MATCH-terminal rule twice. The model fixed
 * every refusal faithfully; each rule was learned one turn at a time, from a refusal, although every fact it rests on was already measured and in the prompt.
 * <p>
 * Two blocks, both pure (no EBX class) and both numbers-from-the-facts only:
 * <ul>
 * <li>{@link #entityBlock}: a checklist - the exact keys of the record usable to open a path (with the share of each source system that fills them), the fields a source
 * system never fills, the child-keys attribute with its threshold ceiling, the rule that every path opens with an exact key of the record itself and puts similarity and
 * related-record nodes after it, the child evidence the plan is expected to use. A line is written only when the entity's facts back it.</li>
 * <li>{@link #startingPlanBlock}: the Factory's own plan for the entity ({@link MatchPlanFallback}, built from the same facts) as a starting point that passes every check;
 * the model keeps its structure and improves it, or says why it prefers another shape ("rules first, the model refines"). The pass accepts it as it is if the model
 * submits it unchanged ({@code MatchPlanLlmPass.Session#prepareStartingPlans}).</li>
 * </ul>
 */
final class MatchPlanChecklist
{
	private MatchPlanChecklist()
	{
	}

	/** Records of a source system that fill a key, as "ECOM 160 of 160 (100%)". */
	static String fillText(final KeyFacts.KeyStat pStat, final Map<String, Integer> pRecordsBySystem)
	{
		final StringBuilder out = new StringBuilder();
		for (final Map.Entry<String, Integer> system : pRecordsBySystem.entrySet())
		{
			final Integer filled = pStat.filledBySystem.get(system.getKey());
			final int value = filled == null ? 0 : filled.intValue();
			out.append(out.length() == 0 ? "" : ", ").append(system.getKey()).append(' ').append(value).append(" of ").append(system.getValue());
			if (system.getValue().intValue() > 0)
			{
				out.append(" (").append(Math.round(100d * value / system.getValue().intValue())).append("%)");
			}
		}
		return out.toString();
	}

	/** The source systems (with at least one record) in which the key is not filled at all, as "STORES 0 of 234"; empty when every system fills it somewhere. */
	static List<String> emptySystems(final KeyFacts.KeyStat pStat, final Map<String, Integer> pRecordsBySystem)
	{
		final List<String> out = new ArrayList<String>();
		for (final Map.Entry<String, Integer> system : pRecordsBySystem.entrySet())
		{
			final Integer filled = pStat.filledBySystem.get(system.getKey());
			if (system.getValue().intValue() > 0 && (filled == null || filled.intValue() == 0))
			{
				out.add(system.getKey() + " 0 of " + system.getValue());
			}
		}
		return out;
	}

	/**
	 * The checklist of one entity, English, every line backed by a measure of the entity; empty when nothing is measured and the entity has neither child keys nor child
	 * evidence (the prompt then keeps exactly what it said before the lot).
	 */
	static String entityBlock(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		final StringBuilder lines = new StringBuilder();
		final KeyFacts facts = pEntity.keyFacts;
		final boolean measured = facts != null && !facts.sampled();
		// lot M17 (2026-10-18, docs/MATCHING.md sec 69): how many source systems feed the entity and, for one source, whether two of its rows share an exact key
		final SingleSourceFacts sources = SingleSourceFacts.of(pEntity);
		if (sources != null)
		{
			lines.append(sources.checklistLine(pEntity));
		}
		if (measured)
		{
			MatchPlanChecklist.openingKeys(pEntity, facts, lines);
			MatchPlanChecklist.emptyFields(pEntity, facts, lines);
		}
		MatchPlanChecklist.childKeys(pEntity, lines);
		MatchPlanChecklist.childEvidence(pEntity, lines);
		if (lines.length() == 0)
		{
			return "";
		}
		return "  Checklist for the plan of " + pEntity.name + " (built from the measured facts of this entity; the pass checks a proposal against ALL of it and tells every violation at once, so "
			+ "satisfy every line before your first proposal):\n" + lines;
	}

	/**
	 * Lot M17: the entities the facts mark as needing no plan (one source system, an exact key measured, none shared by two rows of that source), in the prompt's order, as
	 * {@code name -> "STORES, 48 rows"}.
	 */
	static Map<String, String> noPlanNeeded(final List<MatchPlanLlmPass.EntityInfo> pEntities)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (final MatchPlanLlmPass.EntityInfo entity : pEntities)
		{
			final SingleSourceFacts sources = SingleSourceFacts.of(entity);
			if (sources != null && sources.noPlanNeeded())
			{
				out.put(entity.name, sources.sourceText());
			}
		}
		return out;
	}

	/** Lot M17: the one line that closes the entity list, outside the fenced inventory; empty when no entity is marked. */
	static String noPlanLine(final Map<String, String> pNoPlan)
	{
		if (pNoPlan.isEmpty())
		{
			return "";
		}
		return "Entities the facts mark as needing no plan: " + String.join(", ", pNoPlan.keySet())
			+ " (one source, no shared key) - skip them with skip_entity, after the entities above.\n";
	}

	private static void openingKeys(final MatchPlanLlmPass.EntityInfo pEntity, final KeyFacts pFacts, final StringBuilder pOut)
	{
		// the keys a source system never fills are named in their own line (emptyFields): they do not open a path
		final List<MatchPlanFallback.Key> keys = new ArrayList<MatchPlanFallback.Key>();
		for (final MatchPlanFallback.Key key : MatchPlanFallback.eligibleKeys(pEntity, pFacts, false))
		{
			if (MatchPlanChecklist.emptySystems(key.stat, pFacts.recordsBySystem()).isEmpty())
			{
				keys.add(key);
			}
		}
		if (keys.isEmpty())
		{
			return;
		}
		pOut.append("    * Every path to SUSPECTED or MATCH opens with an exact key of the record itself: an ALL_FIELDS node on one key, exact algorithm, minimumScore 100, NOT_MATCH on "
			+ "both null handlings. AVERAGE nodes, similarity scores and nodes on related records come only AFTER such a node on the path - never first, never alone (a path that opens "
			+ "without one leaves the candidate search unfiltered, which the pass refuses). Keys you can open a path with, and how many records of each source system fill them: ");
		for (int i = 0; i < keys.size(); i++)
		{
			final MatchPlanFallback.Key key = keys.get(i);
			pOut.append(i == 0 ? "" : "; ").append(key.name).append(" (").append(MatchPlanChecklist.fillText(key.stat, pFacts.recordsBySystem())).append(')');
			if (pFacts.predictive(key.name))
			{
				pOut.append(" [identifies a record]");
			}
		}
		pOut.append(". A source system that fills none of the keys on a path can never take that path: give each system a path that opens with a key it fills.");
		if (!pFacts.predictiveKeys().isEmpty())
		{
			pOut.append(" Every path to SUSPECTED or MATCH must also require a condition that identifies a record: a key marked [identifies a record] (the pairs of records that share it "
				+ "also share an independent key 90% or more of the time) or a precise field; a key without the mark may open a path but never carries it alone.");
		}
		pOut.append('\n');
		pOut.append("    * Child evidence never carries a path alone (lot M14): a derived child-keys attribute or a field of a related record (a street, a full address) goes in an ALL_FIELDS node "
			+ "TOGETHER with one of these keys, or below a node on one of them (onTrue chain) - whatever its measured rate. A SUSPECTED or MATCH leaf is never the onFalse of an AVERAGE or fuzzy "
			+ "node unless one of these keys already holds on the path (onTrue of its ancestors): 'names not similar -> suspect' is refused, point that edge to NOT_MATCHED.\n");
	}

	private static void emptyFields(final MatchPlanLlmPass.EntityInfo pEntity, final KeyFacts pFacts, final StringBuilder pOut)
	{
		final Map<String, List<String>> empty = new LinkedHashMap<String, List<String>>();
		for (final KeyFacts.KeyStat stat : pFacts.keys())
		{
			final MatchPlanLlmPass.AttributeInfo keyAttribute = pEntity.attribute(stat.name);
			if (stat.multiToken || keyAttribute != null && keyAttribute.isChildKeys())
			{
				// derived child keys: the pass exempts them from the fill and cross-system guards (they are compared with exactFullText, their own node is what filters)
				continue;
			}
			final List<String> systems = MatchPlanChecklist.emptySystems(stat, pFacts.recordsBySystem());
			if (!systems.isEmpty() && systems.size() < pFacts.recordsBySystem().size())
			{
				empty.put(stat.name, systems);
			}
		}
		// attributes the facts do not carry as keys but whose per-system fill is measured (the profile): a field a system never fills is just as empty there
		for (final MatchPlanLlmPass.AttributeInfo attribute : pEntity.attributes)
		{
			if (empty.containsKey(attribute.name) || pFacts.key(attribute.name) != null || attribute.fillCountBySystem == null || pEntity.rowCountBySystem == null
				|| MatchPlanLlmPass.AttributeInfo.ROLE_SOURCE_KEY.equals(attribute.role) || MatchPlanLlmPass.AttributeInfo.ROLE_REFERENCE.equals(attribute.role) || attribute.isChildKeys())
			{
				continue;
			}
			final List<String> systems = new ArrayList<String>();
			for (final Map.Entry<String, Long> system : pEntity.rowCountBySystem.entrySet())
			{
				final Long filled = attribute.fillCountBySystem.get(system.getKey());
				if (system.getValue() != null && system.getValue().longValue() > 0 && (filled == null || filled.longValue() == 0L))
				{
					systems.add(system.getKey() + " 0 of " + system.getValue());
				}
			}
			if (!systems.isEmpty() && systems.size() < pEntity.rowCountBySystem.size())
			{
				empty.put(attribute.name, systems);
			}
		}
		if (empty.isEmpty())
		{
			return;
		}
		pOut.append("    * Fields that at least one source system never fills (measured): ");
		int index = 0;
		for (final Map.Entry<String, List<String>> field : empty.entrySet())
		{
			pOut.append(index++ == 0 ? "" : "; ").append(field.getKey()).append(" (empty in ").append(String.join(", ", field.getValue())).append(')');
		}
		pOut.append(". Never list such a field as a clustering field, and never make it the exact key that opens a path: the records of that system could never take the path. "
			+ "It may only refine a path that already opens with a key that system fills.\n");
	}

	private static void childKeys(final MatchPlanLlmPass.EntityInfo pEntity, final StringBuilder pOut)
	{
		for (final MatchPlanLlmPass.AttributeInfo attribute : pEntity.attributes)
		{
			if (!attribute.isChildKeys())
			{
				continue;
			}
			final Integer maxChildren = MatchPlanLlmPass.maxChildrenPerParent(pEntity, attribute);
			final int supported = MatchPlanLlmPass.childKeysScoreCeiling(maxChildren);
			final int ceiling = Math.min(supported, MatchPlanLlmPass.CHILD_KEYS_MAX_MINIMUM_SCORE);
			pOut.append("    * ").append(attribute.name).append(" holds the derived child keys of ").append(attribute.childKeysChild == null ? "its child records" : attribute.childKeysChild)
				.append(": compare it only with ").append(MatchPlanLlmPass.EXACT_FULL_TEXT_ALGORITHM).append(" in an ALL_FIELDS node, NOT_MATCH on both null handlings, minimumScore at most ")
				.append(ceiling);
			if (maxChildren != null && maxChildren.intValue() > 0)
			{
				pOut.append(" (measured: one record has ").append(maxChildren).append(" child record(s) at most, which supports ").append(supported).append("; the pass refuses anything above ")
					.append(MatchPlanLlmPass.CHILD_KEYS_MAX_MINIMUM_SCORE).append(" whatever the count)");
			}
			else
			{
				pOut.append(" (the pass refuses anything above ").append(MatchPlanLlmPass.CHILD_KEYS_MAX_MINIMUM_SCORE).append(')');
			}
			pOut.append(".\n");
		}
	}

	private static void childEvidence(final MatchPlanLlmPass.EntityInfo pEntity, final StringBuilder pOut)
	{
		if (pEntity.relationChildren.isEmpty())
		{
			return;
		}
		pOut.append("    * Child evidence (");
		for (int i = 0; i < pEntity.relationChildren.size(); i++)
		{
			final MatchPlanLlmPass.RelationChildInfo child = pEntity.relationChildren.get(i);
			pOut.append(i == 0 ? "" : "; ").append(child.childEntity).append(": ");
			for (int j = 0; j < child.attributes.size(); j++)
			{
				pOut.append(j == 0 ? "" : ", ").append(child.attributes.get(j).name);
			}
		}
		pOut.append(") is expected in the plan: at least one node comparing it, placed after an exact key of the record (see 'Relation evidence' below); a plan that uses none is "
			+ "challenged once.\n");
	}

	// ------------------------------------------------------------------------------------------------------------------------ the starting plan

	/**
	 * The Factory's starting plan of one entity as the prompt shows it: the nodes in words, then the {@code root} and {@code nodes} to resubmit, and what the model is asked to do
	 * with it. {@code pArguments} is the proposal {@link MatchPlanFallback#buildStartingPlan} built and the pass already judged by every check.
	 */
	static String startingPlanBlock(final String pEntityName, final ObjectNode pArguments, final String pSummary)
	{
		final StringBuilder out = new StringBuilder();
		out.append("  Starting plan for ").append(pEntityName).append(" (built by the Factory, by the rules of this pass, from the measured facts above - it passes every check of the pass for ")
			.append(pEntityName).append("; keep its structure, improve its leaves and thresholds, add what the facts justify (child evidence, weaker paths for the sources it does not reach), "
				+ "or explain in the rationale why you prefer another shape; resubmitted unchanged it is accepted as it is): ").append(pSummary).append(".\n");
		out.append("    Nodes:\n");
		for (final JsonNode node : pArguments.path("nodes"))
		{
			out.append("      ").append(node.path("id").asText()).append(' ').append(node.path("function").asText()).append(" [");
			boolean first = true;
			for (final JsonNode field : node.path("fields"))
			{
				out.append(first ? "" : ", ").append(field.path("field").asText()).append(' ').append(MatchPlanChecklist.shortAlgorithm(field.path("algorithm").asText()));
				if (field.has("minimumScore"))
				{
					out.append(' ').append(field.path("minimumScore").asInt());
				}
				first = false;
			}
			out.append("] true -> ").append(node.path("onTrue").asText()).append(", false -> ").append(node.path("onFalse").asText()).append('\n');
		}
		final ObjectNode tree = pArguments.objectNode();
		tree.put("root", pArguments.path("root").asText());
		tree.set("nodes", pArguments.path("nodes"));
		out.append("    To resubmit as it is: ").append(tree).append('\n');
		return out.toString();
	}

	private static String shortAlgorithm(final String pAlgorithm)
	{
		final int at = pAlgorithm.lastIndexOf('@');
		return at >= 0 ? pAlgorithm.substring(at + 1) : pAlgorithm;
	}
}
