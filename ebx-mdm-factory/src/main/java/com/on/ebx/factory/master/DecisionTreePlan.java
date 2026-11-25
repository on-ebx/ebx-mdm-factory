package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The matching plan's decision tree, represented EXACTLY the way the {@code match-merge} add-on represents
 * its own {@code decisionTree}: a BINARY tree of unbounded depth, every node comparing a set of fields with
 * one function and branching {@code onTrue}/{@code onFalse} to either another node or a terminal leaf
 * ({@code MATCH}/{@code SUSPECTED}/{@code NOT_MATCHED}) - never the linear cascade-of-tiers shape this
 * codebase used until 2026-09-22 (a cascade is simply the special case where every {@code onTrue} is already
 * a leaf).
 * <p>
 * <b>2026-09-22, structural rework (the product owner, "C'est parfait" on `docs/mockups/review-match-plan-binary-tree.html`,
 * read FIRST - it fixes this class' shape, the flat node list an LLM answers, and the validation rules).</b>
 * {@code matching_config_schema} (measured 2026-09-22, quoted in full in the mockup and in `CLAUDE.md` &sect;8's
 * own "the add-on's tree shape" note) is the source of truth this class mirrors: a nested {@code decisionTree}
 * node is {@code {name, function, params[{name,value}], fields[{field,algorithm,params[],
 * bothNullValueHandling,oneNullValueHandling}], trueNode, falseNode}}; a leaf is
 * {@code {name:"MATCHED"|"SUSPECTED"|"NOT_MATCHED", function:null, fields:[], params:[]}} - the LIVE
 * {@code Companies} configuration instead names its leaves {@code "Match"}/{@code "Suspect"}/{@code "Not Match"}
 * (display strings, not the schema's own enum tokens - the same discrepancy {@link MatchingPolicyTranslator}'s
 * own Javadoc already flags), so {@link #fromNested} reads a leaf's outcome TOLERANTLY (by substring, case
 * insensitive), never by exact match against one fixed vocabulary.
 * <p>
 * This class keeps a SEPARATE, flatter representation for {@code MatchPlan.requirements.decisionTree} (the
 * shape an LLM answers, and the shape this codebase stores) - a {@link Plan} is {@code {root, nodes[]}}, every
 * {@link Node} carrying its own {@code id} and naming its {@code onTrue}/{@code onFalse} target by id OR by one
 * of the three leaf tokens directly (no nesting, so a re-proposal only ever rewrites the flat list, and a tool
 * schema never has to describe recursive JSON to a language model). {@link #toNested} builds the add-on's own
 * nested shape from a {@link Plan} (recursively, {@code trueNode}/{@code falseNode}); {@link #fromNested} reads
 * ANY add-on tree back into a {@link Plan}, assigning ids {@code N1..Nn} in pre-order - never losing a node,
 * even one this class does not fully understand (an unrecognised {@code function} string becomes a
 * {@link #FUNCTION_PREDICATE} passthrough node, flagged in {@link FromNestedResult#warnings}, never dropped).
 * <p>
 * <b>Backward compatibility with the pre-2026-09-22 cascade shape</b> ({@code requirements.tiers}, still held by
 * every {@code MatchPlan} row written before this lot): {@link #fromTiers} converts a cascade into the
 * equivalent flat {@link Plan} (each tier becomes one {@link #FUNCTION_ALL_FIELDS}/{@link #FUNCTION_AVERAGE}
 * node, {@code onTrue} = the tier's own outcome leaf, {@code onFalse} = the next tier or {@link #LEAF_NOT_MATCHED}
 * for the last one) - {@link #readFrom} applies it automatically whenever a requirements document carries
 * {@code tiers} but no {@code decisionTree}, so no previously-stored plan needs re-entering. {@code toTiersView}/
 * {@code compatibilityTiersView} - the mirror this class carried during lot 2's transition period, rendering a
 * plain-cascade-shaped tree back to the old {@code tiers} array for the three review screens that had not yet
 * been rewritten for a general tree - were REMOVED once that lot landed (binary-decision-tree-screen lot,
 * 2026-09-22: {@code review.MatchPlanTreeDiagram}/{@code MatchPlanView}/{@code MatchSimulationBlock} and {@code
 * ebx.MatchPlanSimulationRunner}/{@code RecordLineageReader} all walk a real tree now, see `docs/MATCHING.md`
 * &sect;16 lot 2 - grep found nothing left depending on either method).
 * <p>
 * Pure: no EBX class anywhere in this file, Jackson only - unit-tested without a running instance, the same
 * convention every other class in this package follows.
 */
public final class DecisionTreePlan
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	// ------------------------------------------------------------------------------------------------- vocabulary

	/** {@code matching_config_schema}'s {@code "All fields match"} - every field must reach its own {@code minimumScore} (default 100). */
	public static final String FUNCTION_ALL_FIELDS = "ALL_FIELDS";
	/** {@code "At least N fields match"} - {@link Node#minimumMatches} of the node's fields must each reach their own {@code minimumScore}. */
	public static final String FUNCTION_AT_LEAST_N = "AT_LEAST_N";
	/** {@code "Average"} - a weighted average of field scores must reach {@link Node#minimumScore}. */
	public static final String FUNCTION_AVERAGE = "AVERAGE";
	/** {@code "Predicate"} - every field carries its own {@link FieldRef#predicate}; read back by {@link #fromNested}, never proposed by the language-model pass (`docs/MATCHING.md`). */
	public static final String FUNCTION_PREDICATE = "PREDICATE";
	private static final Set<String> FUNCTIONS = DecisionTreePlan.setOf(DecisionTreePlan.FUNCTION_ALL_FIELDS, DecisionTreePlan.FUNCTION_AT_LEAST_N,
		DecisionTreePlan.FUNCTION_AVERAGE, DecisionTreePlan.FUNCTION_PREDICATE);

	/** A confirmed duplicate - the decision tree stops here, no further comparison. */
	public static final String LEAF_MATCH = "MATCH";
	/** Routed to a data steward's review queue. */
	public static final String LEAF_SUSPECTED = "SUSPECTED";
	/** The candidate pair is not considered a duplicate. */
	public static final String LEAF_NOT_MATCHED = "NOT_MATCHED";
	private static final Set<String> LEAVES = DecisionTreePlan.setOf(DecisionTreePlan.LEAF_MATCH, DecisionTreePlan.LEAF_SUSPECTED,
		DecisionTreePlan.LEAF_NOT_MATCHED);

	/** {@code matching_config_schema}'s own nested-shape function labels - see class Javadoc. */
	static final String NESTED_FUNCTION_ALL_FIELDS = "All fields match";
	static final String NESTED_FUNCTION_AT_LEAST_N = "At least N fields match";
	static final String NESTED_FUNCTION_AVERAGE = "Average";
	static final String NESTED_FUNCTION_PREDICATE = "Predicate";
	/** The nested shape's own documented leaf tokens (`matching_config_schema`) - never what the LIVE Companies configuration actually stores (display strings instead, see class Javadoc) - {@link #fromNested} never relies on these being exact, only {@link #toNested} ever WRITES them. */
	private static final String NESTED_LEAF_MATCH = "MATCHED";
	private static final String NESTED_LEAF_SUSPECTED = "SUSPECTED";
	private static final String NESTED_LEAF_NOT_MATCHED = "NOT_MATCHED";

	/** Caps (documented, INFERRED for this lot - re-tune if a real project ever needs more): a decision tree beyond this size stops being reviewable by a person on one screen. */
	public static final int MAX_NODES = 64;
	/** Maximum root-to-leaf depth (number of internal nodes on the longest path) - same reasoning as {@link #MAX_NODES}. */
	public static final int MAX_DEPTH = 32;

	private DecisionTreePlan()
	{
	}

	private static Set<String> setOf(final String... pValues)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final String value : pValues)
		{
			out.add(value);
		}
		return out;
	}

	public static boolean isLeaf(final String pTarget)
	{
		return pTarget != null && DecisionTreePlan.LEAVES.contains(pTarget);
	}

	// ------------------------------------------------------------------------------------------------------ model

	/** One field compared by a {@link Node} - see class Javadoc for which properties matter to which {@link Node#function}. */
	public static final class FieldRef
	{
		public String field;
		public String algorithm;
		/** {@link #FUNCTION_AVERAGE} only - defaults to 1. */
		public Integer weight;
		/** {@link #FUNCTION_ALL_FIELDS}/{@link #FUNCTION_AT_LEAST_N} only - defaults to 100. */
		public Integer minimumScore;
		/** {@link #FUNCTION_PREDICATE} only - required there. */
		public String predicate;
		public String bothNull;
		public String oneNull;
		/**
		 * Relation-fields lot (2026-09-24/26, `docs/MATCHING.md` dated entry - lot R2): non-null names the CHILD
		 * entity this field is actually read through (e.g. {@code "Adresse"} on a {@code Personne} plan comparing
		 * {@code cityKey}) - a match-and-merge "Use relationships = By foreign key" field, never a plain attribute of
		 * the entity the plan is FOR. When non-null, {@link #field} names the CHILD's own attribute (e.g. {@code
		 * "cityKey"}), not a field of the parent. This class stays product-neutral about relation fields exactly as it
		 * already is about everything else: it only carries the concept and round-trips it. Native since add-on
		 * 6.3.0.5-RC3 (`docs/MATCHING.md` sect;33): {@code master.MatchingPolicyTranslator} rewrites {@link #field} to
		 * the absolute business-object path {@code root/<ChildTable>/<childField>} just before {@link #toNested}, which then also writes
		 * {@code businessObjectField} on the tree field.
		 */
		public String via;
		/**
		 * Declared data type of {@link #field} (e.g. {@code "STRING"}, the same vocabulary {@code
		 * MasterModel.AttributeInfo#dataType}/{@code StagingModel.AttributeInfo#dataType} use) - optional, carried
		 * for the callers that validate a relation field against the child's attribute; harmless metadata for a plain field.
		 */
		public String dataType;
		/**
		 * Lot R14 (2026-09-28, docs/MATCHING.md dated entry): explicit, model/caller-given marker - true only for
		 * the single field of a genuine "parent confirmation node" ({@code MatchPlanLlmPass.Session
		 * #checkParentConfirmationNodeShape}'s own contract: a REFERENCE-role foreign key, alone in its own
		 * {@link #FUNCTION_ALL_FIELDS} node, deliberately {@code DO_NOTHING}/{@code DO_NOTHING} - a missing or
		 * different parent must never disqualify a pair the entity's OWN identity fields already matched). Never
		 * inferred from shape here (same "explicit beats inferred" reasoning as {@link Node#contradiction}'s own
		 * Javadoc: DecisionTreePlan carries no attribute-role vocabulary at all, and a plain single-field node with
		 * {@code DO_NOTHING} null handling is exactly the shape of the ordinary bug this lot fixes - CLAUDE.md
		 * &sect;15's own "single-field ALL_FIELDS null exemption too broad" entry). Used only by {@link
		 * #isIdentityCountingNode} to keep exempting a genuine parent-confirmation field from {@code
		 * IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH} while an ordinary single-field identity node (no role
		 * information at all, e.g. a bare {@code phoneE164}) is no longer exempted merely for having one field.
		 * Defaults {@code false}: every field built before this lot, and any field this class' own caller never
		 * marks, is unaffected.
		 */
		public boolean referenceRole;
		/** Any other named param this field carried in a nested document read by {@link #fromNested} (e.g. an algorithm-specific {@code jaroWinklerThreshold}) - preserved for round-trip fidelity, never interpreted by this class. Never null; may be empty. */
		public final List<String[]> extraParams = new ArrayList<String[]>();

		public FieldRef()
		{
		}

		public FieldRef(final String pField, final String pAlgorithm)
		{
			this.field = pField;
			this.algorithm = pAlgorithm;
		}

		/** True when this field is read through a child entity ({@link #via} non-blank) rather than being a plain attribute of the entity the plan is for. */
		public boolean isRelation()
		{
			return this.via != null && !this.via.trim().isEmpty();
		}
	}

	/** One node of the flat plan - see class Javadoc. {@link #onTrue}/{@link #onFalse} each name either another node's {@link #id} or one of {@link #LEAF_MATCH}/{@link #LEAF_SUSPECTED}/{@link #LEAF_NOT_MATCHED}. */
	public static final class Node
	{
		public String id;
		public String function;
		/** Optional human-readable label; when absent, {@link #toNested} generates one from {@link #function}/{@link #fields}, exactly mirroring the label the pre-2026-09-22 translator computed on the fly for a cascade tier. */
		public String label;
		/** {@link #FUNCTION_AVERAGE} only - required there (1-100). */
		public Integer minimumScore;
		/** {@link #FUNCTION_AT_LEAST_N} only - required there (1..fields.size()). */
		public Integer minimumMatches;
		public final List<FieldRef> fields = new ArrayList<FieldRef>();
		public String onTrue;
		public String onFalse;
		public String rationale;
		/**
		 * Lot R13 (2026-09-28, docs/MATCHING.md dated entry): an explicit, model-given marker - never inferred from
		 * the node's shape alone. A "contradiction" node compares an identifier-like field genuinely filled on both
		 * sides and, when it DISAGREES, rejects the pair outright ({@code onFalse} the plain {@link #LEAF_NOT_MATCHED}
		 * leaf) before a weaker, similarity-based verdict downstream ({@code onTrue}, continuing to another node)
		 * gets the chance to confirm it anyway. Explicit rather than shape-inferred because the exact same shape
		 * (exact algorithm, onFalse NOT_MATCHED, onTrue continuing) is ALSO the ordinary, far more common "identity
		 * gate above a further check" cascade node (checked live: PersVol7's own child-entity plans use it
		 * routinely, with no contradiction semantics at all) - only the MODEL knows which meaning it intends for a
		 * given node, so it must say so. Defaults {@code false}: every plan built before this lot, and every node a
		 * model never marks, is entirely unaffected by the {@code CONTRADICTION_NODE_*} rules in {@link #validate}.
		 */
		public boolean contradiction;

		public Node()
		{
		}

		public Node(final String pId, final String pFunction)
		{
			this.id = pId;
			this.function = pFunction;
		}
	}

	/** {@code {root, nodes[]}} - see class Javadoc. */
	public static final class Plan
	{
		public String root;
		public final List<Node> nodes = new ArrayList<Node>();

		public Node byId(final String pId)
		{
			for (final Node node : this.nodes)
			{
				if (node.id != null && node.id.equals(pId))
				{
					return node;
				}
			}
			return null;
		}
	}

	// -------------------------------------------------------------------------------------------------- read/write

	/** {@code {root, nodes[]}} JSON exactly as {@link Plan}'s own fields name them - the shape stored at {@code MatchPlan.requirements.decisionTree}. Field object keys: {@code field, algorithm, weight, minimumScore, predicate, bothNull, oneNull} (per `docs/MATCHING.md`, deliberately SHORTER than the nested add-on shape's {@code bothNullValueHandling}/{@code oneNullValueHandling} - {@link #toNested}/{@link #fromNested} do that renaming at the add-on boundary, never here). */
	public static ObjectNode toJson(final Plan pPlan)
	{
		final ObjectNode out = DecisionTreePlan.MAPPER.createObjectNode();
		if (pPlan.root != null)
		{
			out.put("root", pPlan.root);
		}
		final ArrayNode nodes = out.putArray("nodes");
		for (final Node node : pPlan.nodes)
		{
			nodes.add(DecisionTreePlan.nodeToJson(node));
		}
		return out;
	}

	private static ObjectNode nodeToJson(final Node pNode)
	{
		final ObjectNode out = DecisionTreePlan.MAPPER.createObjectNode();
		out.put("id", pNode.id);
		out.put("function", pNode.function);
		if (pNode.label != null)
		{
			out.put("label", pNode.label);
		}
		if (pNode.minimumScore != null)
		{
			out.put("minimumScore", pNode.minimumScore.intValue());
		}
		if (pNode.minimumMatches != null)
		{
			out.put("minimumMatches", pNode.minimumMatches.intValue());
		}
		if (pNode.contradiction)
		{
			out.put("contradiction", true);
		}
		final ArrayNode fields = out.putArray("fields");
		for (final FieldRef field : pNode.fields)
		{
			fields.add(DecisionTreePlan.fieldToJson(field));
		}
		out.put("onTrue", pNode.onTrue);
		out.put("onFalse", pNode.onFalse);
		if (pNode.rationale != null)
		{
			out.put("rationale", pNode.rationale);
		}
		return out;
	}

	private static ObjectNode fieldToJson(final FieldRef pField)
	{
		final ObjectNode out = DecisionTreePlan.MAPPER.createObjectNode();
		out.put("field", pField.field);
		out.put("algorithm", pField.algorithm);
		if (pField.weight != null)
		{
			out.put("weight", pField.weight.intValue());
		}
		if (pField.minimumScore != null)
		{
			out.put("minimumScore", pField.minimumScore.intValue());
		}
		if (pField.predicate != null)
		{
			out.put("predicate", pField.predicate);
		}
		if (pField.bothNull != null)
		{
			out.put("bothNull", pField.bothNull);
		}
		if (pField.oneNull != null)
		{
			out.put("oneNull", pField.oneNull);
		}
		if (pField.via != null)
		{
			out.put("via", pField.via);
		}
		if (pField.dataType != null)
		{
			out.put("dataType", pField.dataType);
		}
		if (pField.referenceRole)
		{
			out.put("referenceRole", true);
		}
		if (!pField.extraParams.isEmpty())
		{
			final ArrayNode params = out.putArray("params");
			for (final String[] entry : pField.extraParams)
			{
				final ObjectNode param = params.addObject();
				param.put("name", entry[0]);
				param.put("value", entry[1]);
			}
		}
		return out;
	}

	/** Reads back exactly what {@link #toJson} writes - never throws: a malformed document reads as an empty plan (no root, no nodes), which {@link #validate} then reports as {@code NO_ROOT}/{@code NO_NODES}. */
	public static Plan fromJson(final JsonNode pJson)
	{
		final Plan plan = new Plan();
		if (pJson == null || pJson.isMissingNode())
		{
			return plan;
		}
		plan.root = pJson.hasNonNull("root") ? pJson.path("root").asText() : null;
		for (final JsonNode nodeJson : pJson.path("nodes"))
		{
			plan.nodes.add(DecisionTreePlan.nodeFromJson(nodeJson));
		}
		return plan;
	}

	private static Node nodeFromJson(final JsonNode pJson)
	{
		final Node node = new Node();
		node.id = pJson.path("id").asText(null);
		node.function = pJson.path("function").asText(null);
		node.label = pJson.hasNonNull("label") ? pJson.path("label").asText() : null;
		node.minimumScore = pJson.hasNonNull("minimumScore") ? Integer.valueOf(pJson.path("minimumScore").asInt()) : null;
		node.minimumMatches = pJson.hasNonNull("minimumMatches") ? Integer.valueOf(pJson.path("minimumMatches").asInt()) : null;
		node.contradiction = pJson.path("contradiction").asBoolean(false);
		for (final JsonNode fieldJson : pJson.path("fields"))
		{
			node.fields.add(DecisionTreePlan.fieldFromJson(fieldJson));
		}
		node.onTrue = pJson.path("onTrue").asText(null);
		node.onFalse = pJson.path("onFalse").asText(null);
		node.rationale = pJson.hasNonNull("rationale") ? pJson.path("rationale").asText() : null;
		return node;
	}

	private static FieldRef fieldFromJson(final JsonNode pJson)
	{
		final FieldRef field = new FieldRef();
		field.field = pJson.path("field").asText(null);
		field.algorithm = pJson.path("algorithm").asText(null);
		field.weight = pJson.hasNonNull("weight") ? Integer.valueOf(pJson.path("weight").asInt()) : null;
		field.minimumScore = pJson.hasNonNull("minimumScore") ? Integer.valueOf(pJson.path("minimumScore").asInt()) : null;
		field.predicate = pJson.hasNonNull("predicate") ? pJson.path("predicate").asText() : null;
		// lot R7 fix, coordinator review of 913775c7 (2026-09-27): read the short key FIRST (this class' own
		// round-trip convention, class Javadoc), falling back to the long add-on-shape key ONLY when the short one
		// is absent - a plan stored BEFORE the R7 fix (MatchPlanLlmPass used to write the long keys here by mistake)
		// still carries "bothNullValueHandling"/"oneNullValueHandling" and must keep translating to its own real
		// DO_NOTHING/etc value, not silently fall through to the NOT_MATCH default forever until someone re-proposes
		// it. Writing (fieldToJson, above) is UNCHANGED - only ever the short key, going forward.
		field.bothNull = pJson.hasNonNull("bothNull") ? pJson.path("bothNull").asText()
			: pJson.hasNonNull("bothNullValueHandling") ? pJson.path("bothNullValueHandling").asText() : null;
		field.oneNull = pJson.hasNonNull("oneNull") ? pJson.path("oneNull").asText()
			: pJson.hasNonNull("oneNullValueHandling") ? pJson.path("oneNullValueHandling").asText() : null;
		field.via = pJson.hasNonNull("via") ? pJson.path("via").asText() : null;
		field.dataType = pJson.hasNonNull("dataType") ? pJson.path("dataType").asText() : null;
		field.referenceRole = pJson.path("referenceRole").asBoolean(false);
		for (final JsonNode param : pJson.path("params"))
		{
			field.extraParams.add(new String[] { param.path("name").asText(""), param.path("value").asText("") });
		}
		return field;
	}

	/**
	 * Reads {@code pRequirements.decisionTree} when present (the current, flat shape); otherwise falls back to
	 * {@link #fromTiers} on {@code pRequirements.tiers} (every plan stored before 2026-09-22) - so no stored plan
	 * needs re-entering. Neither present: an empty {@link Plan} ({@link #validate} then reports it as invalid).
	 */
	public static Plan readFrom(final JsonNode pRequirements)
	{
		if (pRequirements == null || pRequirements.isMissingNode())
		{
			return new Plan();
		}
		if (pRequirements.has("decisionTree"))
		{
			return DecisionTreePlan.fromJson(pRequirements.path("decisionTree"));
		}
		if (pRequirements.has("tiers"))
		{
			return DecisionTreePlan.fromTiers(pRequirements.path("tiers"));
		}
		return new Plan();
	}

	// -------------------------------------------------------------------------------------------- tiers (legacy)

	/** See class Javadoc "Backward compatibility". {@code pTiers} may be any {@link JsonNode} that iterates tier objects (a missing/non-array node yields an empty {@link Plan}, root null). */
	public static Plan fromTiers(final JsonNode pTiers)
	{
		final Plan plan = new Plan();
		int index = 0;
		final List<JsonNode> tiers = new ArrayList<JsonNode>();
		for (final JsonNode tier : pTiers)
		{
			tiers.add(tier);
		}
		for (final JsonNode tier : tiers)
		{
			index++;
			final String id = "T" + index;
			final boolean average = "AVERAGE".equals(tier.path("type").asText());
			final Node node = new Node(id, average ? DecisionTreePlan.FUNCTION_AVERAGE : DecisionTreePlan.FUNCTION_ALL_FIELDS);
			if (average)
			{
				node.minimumScore = Integer.valueOf(tier.path("minimumScore").asInt(90));
			}
			for (final JsonNode fieldJson : tier.path("fields"))
			{
				final FieldRef field = new FieldRef(fieldJson.path("field").asText(null), fieldJson.path("algorithm").asText(null));
				if (average)
				{
					field.weight = Integer.valueOf(Math.max(1, fieldJson.path("weight").asInt(1)));
				}
				else
				{
					field.minimumScore = Integer.valueOf(fieldJson.path("minimumScore").asInt(100));
				}
				field.bothNull = fieldJson.hasNonNull("bothNullValueHandling") ? fieldJson.path("bothNullValueHandling").asText() : null;
				field.oneNull = fieldJson.hasNonNull("oneNullValueHandling") ? fieldJson.path("oneNullValueHandling").asText() : null;
				node.fields.add(field);
			}
			node.onTrue = tier.path("outcome").asText(DecisionTreePlan.LEAF_SUSPECTED);
			node.onFalse = index < tiers.size() ? "T" + (index + 1) : DecisionTreePlan.LEAF_NOT_MATCHED;
			plan.nodes.add(node);
		}
		plan.root = plan.nodes.isEmpty() ? null : "T1";
		return plan;
	}

	// ------------------------------------------------------------------------------------------------ validation

	/** Named reasons, never an exception - see {@link #validate}. */
	public static final class ValidationResult
	{
		public final boolean ok;
		public final List<String> reasons;

		private ValidationResult(final boolean pOk, final List<String> pReasons)
		{
			this.ok = pOk;
			this.reasons = pReasons;
		}
	}

	public static ValidationResult validate(final Plan pPlan)
	{
		return DecisionTreePlan.validate(pPlan, null, null);
	}

	/**
	 * Relation-fields lot (2026-09-26, `docs/MATCHING.md` dated entry): what {@link #validate(Plan, List, List,
	 * RelationCatalog, String)} needs to know about an entity's CHILD entities to accept a {@link FieldRef#via}
	 * reference - built by the caller (ultimately {@code ebx.MatchPlanLlmSuggester}, from {@code
	 * MasterModel.Result#resolveReference}'s own reverse lookup: which promoted entities hold a foreign key
	 * TARGETING the entity this plan is for) - never derived here, this class knows nothing about EBX or the
	 * master model.
	 */
	public static final class RelationCatalog
	{
		private final Map<String, Set<String>> acceptedAttributesByChild;

		/** @param pAcceptedAttributesByChild child entity name -&gt; the set of that child's accepted, non-technical, non-REFERENCE attribute names a relation field may name. Never null values; an unknown child is simply absent from the map. */
		public RelationCatalog(final Map<String, Set<String>> pAcceptedAttributesByChild)
		{
			this.acceptedAttributesByChild = pAcceptedAttributesByChild == null ? new LinkedHashMap<String, Set<String>>()
				: new LinkedHashMap<String, Set<String>>(pAcceptedAttributesByChild);
		}

		public boolean isKnownChild(final String pChildEntity)
		{
			return pChildEntity != null && this.acceptedAttributesByChild.containsKey(pChildEntity);
		}

		public boolean isAcceptedAttribute(final String pChildEntity, final String pAttribute)
		{
			final Set<String> attributes = this.acceptedAttributesByChild.get(pChildEntity);
			return attributes != null && pAttribute != null && attributes.contains(pAttribute);
		}
	}

	/**
	 * Pure structural validation - never throws, always returns every reason it found (never stops at the
	 * first). See class Javadoc for the shape this checks against `docs/mockups/review-match-plan-binary-tree.html`'s
	 * own rules: exactly one root, every node reachable from it, no cycle, EVERY NODE HAS EXACTLY ONE PARENT
	 * (2026-09-22, coordinator follow-up below - {@code NODE_SHARED}), every {@code onTrue}/{@code onFalse}
	 * resolves (to a leaf or a real node id), {@link #FUNCTION_AVERAGE} needs {@link Node#minimumScore},
	 * {@link #FUNCTION_AT_LEAST_N} needs {@link Node#minimumMatches} &le; its own field count, a
	 * {@link #FUNCTION_PREDICATE} field needs {@link FieldRef#predicate}, and (only when the corresponding list is
	 * non-null - see {@code MatchPlanLlmPass.Session}'s own use of the algorithm list) the function/algorithm
	 * vocabulary. {@code SAME_OUTCOME_BOTH_BRANCHES} (PersVol5, 2026-09-26 live failure): a node whose {@code
	 * onTrue} and {@code onFalse} name the exact same target (leaf or node id) decides nothing - match-merge
	 * itself refuses this at generate/apply time with an uninformative message, so it is caught here first, at
	 * propose/validate time, for every plan (not only a relation-field one). {@code RELATION_NODE_OUTCOMES_MUST_DIFFER}
	 * (lot R9, 2026-09-27 live failure, PersVol7 run 169): a node comparing at least one relation ({@link
	 * FieldRef#via}) field whose {@code onTrue} and {@code onFalse} both name one of {@link #LEAF_MATCH}/
	 * {@link #LEAF_SUSPECTED} (the same one, or not) decides nothing once auto-merge downgrades {@code MATCH} to
	 * {@code SUSPECTED} everywhere - the child evidence would silently never reach the add-on (see {@code
	 * MatchingPolicyTranslator#collapseSameOutcomeNodes}). Caps: {@link #MAX_NODES} nodes, {@link #MAX_DEPTH} deep.
	 * <p>
	 * <b>2026-09-22, coordinator follow-up (same day as this whole lot) - a plan is a TREE, not a DAG.</b> The
	 * mockup (`docs/mockups/review-match-plan-binary-tree.html`) was first misread as allowing one node to be
	 * reached from two different parents (N5 from both N3's false branch and N4's true branch) - re-reading the
	 * SVG paths shows it is in fact a strict tree (N3's false branch is its own {@code NOT_MATCHED} leaf; only
	 * N4's true branch reaches N5). The coordinator settled the design question this raised either way: a node
	 * has EXACTLY ONE PARENT, exactly like the add-on's own nested {@code trueNode}/{@code falseNode} shape
	 * (which has no way to reference a node twice at all - two uses would have to be two independent copies).
	 * {@code NODE_SHARED} enforces it: a node id named by more than one {@code onTrue}/{@code onFalse} anywhere
	 * in the plan is refused, naming every referencing branch (e.g. {@code "NODE_SHARED: 'N5' is referenced by
	 * N3.onFalse and N4.onTrue..."}). This guarantees {@link #toNested} never has to "unroll" a shared subtree
	 * into two copies - see that method's own Javadoc.
	 *
	 * @param pAllowedFunctions null means "every one of the four functions above is allowed" (the pure model's
	 * own default - {@code MatchPlanRules}/a direct unit test never restrict this); a non-null list restricts to
	 * exactly those (e.g. {@code MatchPlanLlmPass.Session} never allows {@link #FUNCTION_PREDICATE} from the
	 * model - see that class' own tool schema).
	 * @param pAllowedAlgorithms null means "no algorithm restriction" (this class knows nothing about the add-on's
	 * own algorithm vocabulary - that list lives in {@code MatchPlanProfileReader}/{@code
	 * MatchPlanLlmPass.AllowedVocabulary} and is passed in by whichever caller has it).
	 */
	public static ValidationResult validate(final Plan pPlan, final List<String> pAllowedFunctions, final List<String> pAllowedAlgorithms)
	{
		final List<String> reasons = new ArrayList<String>();
		if (pPlan == null)
		{
			reasons.add("NO_ROOT: plan is null.");
			return new ValidationResult(false, reasons);
		}
		if (pPlan.root == null || pPlan.root.trim().isEmpty())
		{
			reasons.add("NO_ROOT: 'root' is missing or blank.");
		}
		if (pPlan.nodes.isEmpty())
		{
			reasons.add("NO_NODES: the plan has no nodes.");
			return new ValidationResult(false, reasons);
		}
		if (pPlan.nodes.size() > DecisionTreePlan.MAX_NODES)
		{
			reasons.add("TOO_MANY_NODES: " + pPlan.nodes.size() + " nodes, the cap is " + DecisionTreePlan.MAX_NODES + ".");
		}

		final Set<String> ids = new LinkedHashSet<String>();
		final Set<String> duplicateIds = new LinkedHashSet<String>();
		for (final Node node : pPlan.nodes)
		{
			final String id = node.id == null ? "" : node.id.trim();
			if (id.isEmpty())
			{
				reasons.add("NODE_MISSING_ID: a node has no id.");
				continue;
			}
			if (DecisionTreePlan.LEAVES.contains(id))
			{
				reasons.add("NODE_ID_RESERVED: node id '" + id + "' collides with a leaf outcome name.");
			}
			if (!ids.add(id))
			{
				duplicateIds.add(id);
			}
		}
		for (final String duplicate : duplicateIds)
		{
			reasons.add("DUPLICATE_NODE_ID: '" + duplicate + "' is used by more than one node.");
		}

		if (pPlan.root != null && !pPlan.root.trim().isEmpty() && !ids.contains(pPlan.root))
		{
			reasons.add("ROOT_NOT_FOUND: root '" + pPlan.root + "' is not the id of any node.");
		}

		final Set<String> allowedFunctions = pAllowedFunctions == null ? DecisionTreePlan.FUNCTIONS : new LinkedHashSet<String>(pAllowedFunctions);
		for (final Node node : pPlan.nodes)
		{
			DecisionTreePlan.validateNode(node, ids, allowedFunctions, pAllowedAlgorithms, reasons);
		}

		// NODE_SHARED (2026-09-22, coordinator follow-up - see this method's own Javadoc): a node id may be named
		// by at most one onTrue/onFalse anywhere in the plan - one parent, like the add-on's own nested shape.
		// Only a target that IS a known node id is counted (a leaf is meant to be referenced by many nodes; an
		// unknown target is already reported by UNKNOWN_BRANCH_TARGET above, never double-reported here).
		final Map<String, List<String>> referencesByTarget = new LinkedHashMap<String, List<String>>();
		for (final Node node : pPlan.nodes)
		{
			final String id = node.id == null ? "(no id)" : node.id;
			DecisionTreePlan.recordReference(referencesByTarget, ids, id, "onTrue", node.onTrue);
			DecisionTreePlan.recordReference(referencesByTarget, ids, id, "onFalse", node.onFalse);
		}
		for (final Map.Entry<String, List<String>> entry : referencesByTarget.entrySet())
		{
			if (entry.getValue().size() > 1)
			{
				reasons.add("NODE_SHARED: '" + entry.getKey() + "' is referenced by " + DecisionTreePlan.join(entry.getValue())
					+ " - each node must have exactly one parent, like the add-on's own nested tree.");
			}
		}

		if (pPlan.root != null && ids.contains(pPlan.root) && duplicateIds.isEmpty())
		{
			DecisionTreePlan.validateGraphShape(pPlan, reasons);
		}

		return new ValidationResult(reasons.isEmpty(), reasons);
	}

	/**
	 * Relation-fields lot (2026-09-26): everything {@link #validate(Plan, List, List)} already checks, PLUS the
	 * three structural guards a relation field ({@link FieldRef#via} non-blank) needs, in the style of {@code
	 * MatchPlanLlmPass.Session#checkParentConfirmationNodeShape} - domain-neutral, never a check on a column name:
	 * <ul>
	 * <li>{@code RELATION_FIELD_UNKNOWN_CHILD}/{@code RELATION_FIELD_UNKNOWN_ATTRIBUTE}: {@link FieldRef#via} must
	 * name a real child of the entity this plan is for, and {@link FieldRef#field} one of that child's accepted
	 * attributes - per {@code pRelationCatalog} (null means "no relation field is legal at all": every {@code via}
	 * is then reported as unknown);</li>
	 * <li>{@code RELATION_FIELD_ALONE_AT_ROOT}: the {@link Plan#root} node must compare at least one NON-relation
	 * field - pre-clustering search always runs on the entity's OWN attributes (CLAUDE.md &sect;8), so a root that
	 * compares only child evidence has nothing of its own to search candidates on;</li>
	 * <li>{@code RELATION_FIELD_IS_CLUSTERING_FIELD}: {@code pClusteringFieldNames} (the plan's own
	 * {@code clusteringFields}, bare names) must never name a relation field's {@link FieldRef#field} - a
	 * relationship field cannot be the add-on's pre-clustering key (CLAUDE.md &sect;8: a decision-tree relationship
	 * field is compared by best-pair over the child rows, not the parent's own {@code searchStrategy}).</li>
	 * </ul>
	 *
	 * @param pRelationCatalog may be null (no relation field is ever legal for this plan; every {@code via} found is refused).
	 * @param pClusteringFieldNames the plan's own {@code clusteringFields} (bare field names) - may be null (no clustering-field guard is run).
	 */
	public static ValidationResult validate(final Plan pPlan, final List<String> pAllowedFunctions, final List<String> pAllowedAlgorithms,
		final RelationCatalog pRelationCatalog, final List<String> pClusteringFieldNames)
	{
		final ValidationResult base = DecisionTreePlan.validate(pPlan, pAllowedFunctions, pAllowedAlgorithms);
		final List<String> reasons = new ArrayList<String>(base.reasons);
		if (pPlan != null)
		{
			for (final Node node : pPlan.nodes)
			{
				boolean hasNonRelationField = false;
				for (final FieldRef field : node.fields)
				{
					if (!field.isRelation())
					{
						hasNonRelationField = true;
						continue;
					}
					if (pRelationCatalog == null || !pRelationCatalog.isKnownChild(field.via))
					{
						reasons.add("RELATION_FIELD_UNKNOWN_CHILD: node '" + node.id + "' field '" + field.field + "' is read via '" + field.via
							+ "', which is not a known child entity of this plan's own entity.");
					}
					else if (!pRelationCatalog.isAcceptedAttribute(field.via, field.field))
					{
						reasons.add("RELATION_FIELD_UNKNOWN_ATTRIBUTE: node '" + node.id + "' field '" + field.field + "' is not an accepted attribute of child '"
							+ field.via + "'.");
					}
					if (pClusteringFieldNames != null && pClusteringFieldNames.contains(field.field))
					{
						reasons.add("RELATION_FIELD_IS_CLUSTERING_FIELD: '" + field.field + "' (via '" + field.via
							+ "') cannot be a pre-clustering/clustering field - a relationship field is compared by best-pair over the child rows, "
							+ "never used as the parent's own search key.");
					}
				}
				if (pPlan.root != null && pPlan.root.equals(node.id) && !node.fields.isEmpty() && !hasNonRelationField)
				{
					reasons.add("RELATION_FIELD_ALONE_AT_ROOT: the root node '" + node.id
						+ "' compares only relation (child) field(s) - pre-clustering search needs at least one of the entity's own fields there too.");
				}
			}
		}
		return new ValidationResult(reasons.isEmpty(), reasons);
	}

	private static void recordReference(final Map<String, List<String>> pReferencesByTarget, final Set<String> pIds, final String pSourceNodeId,
		final String pBranchName, final String pTarget)
	{
		if (pTarget == null || !pIds.contains(pTarget))
		{
			return;
		}
		List<String> references = pReferencesByTarget.get(pTarget);
		if (references == null)
		{
			references = new ArrayList<String>();
			pReferencesByTarget.put(pTarget, references);
		}
		references.add(pSourceNodeId + "." + pBranchName);
	}

	private static String join(final List<String> pValues)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pValues.size(); i++)
		{
			if (i > 0)
			{
				out.append(" and ");
			}
			out.append(pValues.get(i));
		}
		return out.toString();
	}

	private static void validateNode(final Node pNode, final Set<String> pIds, final Set<String> pAllowedFunctions, final List<String> pAllowedAlgorithms,
		final List<String> pReasons)
	{
		final String id = pNode.id == null ? "(no id)" : pNode.id;
		if (pNode.function == null || !pAllowedFunctions.contains(pNode.function))
		{
			pReasons.add("UNKNOWN_FUNCTION: node '" + id + "' has function '" + pNode.function + "', not one of " + pAllowedFunctions + ".");
		}
		if (pNode.fields.isEmpty())
		{
			pReasons.add("NODE_HAS_NO_FIELDS: node '" + id + "' compares no field.");
		}
		if (DecisionTreePlan.FUNCTION_AVERAGE.equals(pNode.function) && (pNode.minimumScore == null || pNode.minimumScore.intValue() < 1 || pNode.minimumScore.intValue() > 100))
		{
			pReasons.add("AVERAGE_MISSING_MINIMUM_SCORE: node '" + id + "' is an AVERAGE node without a minimumScore in 1..100.");
		}
		if (DecisionTreePlan.FUNCTION_AT_LEAST_N.equals(pNode.function))
		{
			final int fieldCount = pNode.fields.size();
			if (pNode.minimumMatches == null || pNode.minimumMatches.intValue() < 1 || pNode.minimumMatches.intValue() > fieldCount)
			{
				pReasons.add("AT_LEAST_N_INVALID_MINIMUM_MATCHES: node '" + id + "' needs minimumMatches between 1 and its field count (" + fieldCount + ").");
			}
		}
		if (DecisionTreePlan.FUNCTION_PREDICATE.equals(pNode.function))
		{
			for (final FieldRef field : pNode.fields)
			{
				if (field.predicate == null || field.predicate.trim().isEmpty())
				{
					pReasons.add("PREDICATE_FIELD_MISSING_PREDICATE: node '" + id + "' field '" + field.field + "' has no predicate.");
				}
			}
		}
		if (pAllowedAlgorithms != null)
		{
			for (final FieldRef field : pNode.fields)
			{
				if (field.algorithm == null || !pAllowedAlgorithms.contains(field.algorithm))
				{
					pReasons.add("DISALLOWED_ALGORITHM: node '" + id + "' field '" + field.field + "' uses algorithm '" + field.algorithm + "'.");
				}
			}
		}
		DecisionTreePlan.validateBranch(id, "onTrue", pNode.onTrue, pIds, pReasons);
		DecisionTreePlan.validateBranch(id, "onFalse", pNode.onFalse, pIds, pReasons);
		// SAME_OUTCOME_BOTH_BRANCHES (PersVol5, 2026-09-26 live failure): a node whose onTrue and onFalse are the
		// exact same target (a leaf OR another node id) decides nothing - the comparison it makes never changes
		// where the pair ends up - and match-merge itself refuses it at generate/apply time with an uninformative
		// message ("The outputs from the Same <node> data comparison node cannot connect to the same type of
		// relationship status"). Caught here instead, at propose/validate time, so the caller (the LLM pass, or a
		// stored plan re-validated before configure) gets a clear reason naming the node and the shared target,
		// rather than a generic MCP refusal surfacing only once someone tries to push the policy live. Applies to
		// EVERY plan (not only a relation-field one) - this is a plain structural defect of the tree itself.
		if (pNode.onTrue != null && !pNode.onTrue.trim().isEmpty() && pNode.onTrue.equals(pNode.onFalse))
		{
			pReasons.add("SAME_OUTCOME_BOTH_BRANCHES: node '" + id + "' has onTrue and onFalse both '" + pNode.onTrue
				+ "' - the comparison decides nothing, since either result leads to the same outcome. Route onFalse to a different outcome "
				+ "(or another node), or remove this node from the tree.");
		}
		// RELATION_NODE_OUTCOMES_MUST_DIFFER (lot R9, 2026-09-27, docs/MATCHING.md sect;40, live PersVol7 run 169):
		// a node carrying at least one relation (child) field must actually DISCRIMINATE on it. Auto-merge is a
		// product policy this plan cannot see (DecisionTreePlan stays neutral about it - same reasoning as
		// MatchingPolicyTranslator#forceMatchedLeavesToSuspected's own Javadoc) and is OFF by default (a human
		// toggle) - with it off, MATCH is downgraded to SUSPECTED everywhere, so a node whose onTrue and onFalse
		// BOTH already name one of {MATCH, SUSPECTED} (whether the SAME one or not - SAME_OUTCOME_BOTH_BRANCHES
		// above only catches the identical-string case) collapses to a single outcome once translated:
		// MatchingPolicyTranslator#collapseSameOutcomeNodes then silently drops the node, and the child evidence it
		// was built to compare never reaches the add-on at all - though the plan itself reads as if it "used" the
		// relation field. Caught here, at the plan level, before any collapse gets the chance: route the other
		// branch to NOT_MATCHED, or to another node (a coarser child-evidence level, or an independent identifier
		// of the entity's own), never to the other of MATCH/SUSPECTED. This is a plain structural defect of the
		// node itself (like SAME_OUTCOME_BOTH_BRANCHES above), so it runs in validateNode - every validate overload
		// gets it for free, both at propose time (MatchPlanLlmPass.Session#proposeMatchPlan's own relation-aware
		// validate call) and before any MCP write (MatchingPolicyTranslator#buildMatchingPolicyFromPlan's own
		// plain DecisionTreePlan.validate(plan) call already gates every configure step on it - CLAUDE.md sect;13's
		// "no new call site needed" precedent, same as SAME_OUTCOME_BOTH_BRANCHES itself).
		boolean hasRelationField = false;
		for (final FieldRef field : pNode.fields)
		{
			if (field.isRelation())
			{
				hasRelationField = true;
				break;
			}
		}
		if (hasRelationField && DecisionTreePlan.isAmbiguousOutcomeLeaf(pNode.onTrue) && DecisionTreePlan.isAmbiguousOutcomeLeaf(pNode.onFalse))
		{
			pReasons.add("RELATION_NODE_OUTCOMES_MUST_DIFFER: node '" + id + "' compares relation (child) evidence and routes onTrue ('" + pNode.onTrue
				+ "') and onFalse ('" + pNode.onFalse + "') to outcomes that collapse to the same result once auto-merge is off (MATCH and SUSPECTED are "
				+ "then equivalent) - the child evidence would never actually discriminate anything. Route the other branch to NOT_MATCHED, or to another "
				+ "node comparing a coarser child-evidence level or an independent identifier, never to MATCH or SUSPECTED.");
		}
		// IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH (lot R13, 2026-09-28, docs/MATCHING.md dated entry, live
		// PersVol7 measurement 2026-09-27 23:51; narrowed by lot R14, 2026-09-28, CLAUDE.md &sect;15 "single-field
		// ALL_FIELDS null exemption too broad", live project ret): a node that COUNTS agreeing fields - AT_LEAST_N
		// always, a MULTI-field ALL_FIELDS used the same way, or (since R14) a SINGLE-field ALL_FIELDS node UNLESS
		// its one field is a relation ("via") field or is explicitly marked {@link FieldRef#referenceRole} (a
		// genuine parent-confirmation node, per checkParentConfirmationNodeShape's own contract - both already
		// define their own null handling deliberately; see #isIdentityCountingNode) - and whose onTrue leads
		// straight to a terminal outcome (MATCH or SUSPECTED, DecisionTreePlan.isAmbiguousOutcomeLeaf reused, same
		// predicate RELATION_NODE_OUTCOMES_MUST_DIFFER above already uses) must never let a MISSING key on one side
		// count as an AGREEING key. Measured live: the add-on's own "At least N fields match"/"All fields match"
		// functions count a field whose oneNullValueHandling is DO_NOTHING as agreeing, not abstaining, when the
		// other side's value is simply absent. On a real volume test this produced 302 of 309 false ERP-ERP pairs
		// from a multi-field counting node (one equal key + one null key counted as "2 of 3 fields match"); a
		// SEPARATE live project (ret) then showed the identical failure on a single-field node - "same first name +
		// vaguely similar last name" pairs sent to SUSPECTED whenever an ordinary identifier (e.g. phoneE164, no
		// role at all) was simply missing on one side, because R13's own exemption treated EVERY single-field
		// ALL_FIELDS node as a parent-confirmation/relation node, never checking whether it actually was one. A
		// relation ("via") field is exempt - it keeps its own, deliberate DO_NOTHING contract (docs/MATCHING.md
		// &sect;33/&sect;34: missing CHILD evidence withholds, never disqualifies AND never counts as agreement, by
		// construction of the relation-evidence guards themselves, not this rule); a field marked {@code
		// referenceRole} is exempt the same way, for the parent-confirmation contract. {@code null} (never
		// explicitly set) is left alone here - {@link #nestedField}'s own default is already the safe {@code
		// NOT_MATCH}; only an EXPLICIT value other than {@code NOT_MATCH} is refused, so this never fires on a plan
		// that simply never mentioned the property.
		if (DecisionTreePlan.isIdentityCountingNode(pNode) && DecisionTreePlan.isAmbiguousOutcomeLeaf(pNode.onTrue) && !pNode.contradiction)
		{
			for (final FieldRef field : pNode.fields)
			{
				if (field.isRelation())
				{
					continue;
				}
				final String oneNull = field.oneNull;
				if (oneNull != null && !"NOT_MATCH".equals(oneNull))
				{
					pReasons.add("IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH: node '" + id + "' field '" + field.field + "' counts agreeing fields (" + pNode.function
						+ ") and routes onTrue straight to '" + pNode.onTrue + "', but its own oneNullValueHandling is '" + oneNull + "' - the add-on's own counting "
						+ "function treats a DO_NOTHING null field as an AGREEING field, not an abstention, so one filled key plus one merely-missing key on the "
						+ "other record would count exactly like two genuinely equal keys (a real, measured failure: 302 of 309 false pairs in one volume test came "
						+ "from exactly this shape). Set oneNullValueHandling to NOT_MATCH for '" + field.field + "', or omit it (NOT_MATCH is the safe default).");
				}
			}
		}
		// CONTRADICTION_NODE_WRONG_SHAPE / CONTRADICTION_NODE_NULL_HANDLING_MUST_BE_DO_NOTHING (lot R13, 2026-09-28,
		// docs/MATCHING.md dated entry): the mirror image of the guard above, gated on {@link Node#contradiction} -
		// an EXPLICIT, model-given marker, never inferred from the node's shape alone. The exact shape a genuine
		// contradiction node needs (onFalse the plain NOT_MATCHED leaf, onTrue CONTINUING to another node, never a
		// leaf) is ALSO the shape of the ordinary, far more common "identity gate above a further check" cascade
		// node (measured live: PersVol7's own child-entity plans route an exact-algorithm identity field this exact
		// way with no contradiction semantics at all) - inferring "contradiction" from shape alone would wrongly
		// refuse every such ordinary gate node unless it too carried DO_NOTHING, which is not what it needs. Only a
		// node the model itself marks {@code contradiction: true} is ever checked by this rule - see {@link
		// Node#contradiction}'s own Javadoc for why explicit beats inferred here.
		if (pNode.contradiction)
		{
			final boolean rightShape = pNode.onFalse != null && DecisionTreePlan.LEAF_NOT_MATCHED.equals(pNode.onFalse) && pNode.onTrue != null
				&& !pNode.onTrue.trim().isEmpty() && !DecisionTreePlan.isLeaf(pNode.onTrue);
			if (!rightShape)
			{
				pReasons.add("CONTRADICTION_NODE_WRONG_SHAPE: node '" + id + "' is marked contradiction:true, but a contradiction node must route onFalse to the "
					+ "plain NOT_MATCHED leaf (a disagreement disqualifies outright) and onTrue to ANOTHER node, never a leaf directly (agreeing on this field alone "
					+ "never confirms anything by itself, it only fails to contradict) - this node has onTrue '" + pNode.onTrue + "', onFalse '" + pNode.onFalse
					+ "'. Fix the shape, or unmark this node if it is not really a contradiction guard.");
			}
			else
			{
				// A MISSING value on either side must never itself trigger the rejection - it withholds, it does not
				// disqualify (the same principle the parent-confirmation node and the relation-evidence guards
				// already apply). So every non-relation field of a genuine contradiction node must have BOTH
				// bothNullValueHandling and oneNullValueHandling resolve to DO_NOTHING; {@link #nestedField}'s own
				// default for an omitted value is NOT_MATCH (the OPPOSITE of what a contradiction node needs), so -
				// unlike the counting rule above - an omitted value is NOT safe here and is refused exactly like an
				// explicit wrong one. A relation ("via") field is exempt - its own DO_NOTHING contract is already
				// established elsewhere and never re-litigated by this generic rule.
				for (final FieldRef field : pNode.fields)
				{
					if (field.isRelation())
					{
						continue;
					}
					final String effectiveBothNull = field.bothNull == null ? "NOT_MATCH" : field.bothNull;
					final String effectiveOneNull = field.oneNull == null ? "NOT_MATCH" : field.oneNull;
					if (!"DO_NOTHING".equals(effectiveBothNull) || !"DO_NOTHING".equals(effectiveOneNull))
					{
						pReasons.add("CONTRADICTION_NODE_NULL_HANDLING_MUST_BE_DO_NOTHING: node '" + id + "' field '" + field.field + "' is marked contradiction:true "
							+ "(onFalse NOT_MATCHED, onTrue continuing to another node) - meant to reject a pair outright when this field is filled and DIFFERENT on "
							+ "both sides. A MISSING value on either side must never itself disqualify a pair: set BOTH bothNullValueHandling and oneNullValueHandling "
							+ "to DO_NOTHING for '" + field.field + "' explicitly - omitting them defaults to NOT_MATCH, exactly the disqualifying behaviour this node "
							+ "must avoid for a merely-missing value.");
					}
				}
			}
		}
	}

	/**
	 * True when {@code pNode} COUNTS a set of fields agreeing with each other rather than requiring every field to
	 * reach its own threshold independently in the ordinary sense - {@code AT_LEAST_N} always, a MULTI-field
	 * {@code ALL_FIELDS} always, and (lot R14, 2026-09-28 - see {@link #validateNode}'s own dated comment for the
	 * live bug this narrows) a SINGLE-field {@code ALL_FIELDS} node UNLESS its one field is a relation ({@code via})
	 * field or is explicitly marked {@link FieldRef#referenceRole} - a genuine parent-confirmation node, per
	 * {@code MatchPlanLlmPass.Session#checkParentConfirmationNodeShape}'s own contract, or a relation-match node,
	 * per {@code checkRelationMatchNodeShape}'s, both of which already define their own {@code DO_NOTHING} null
	 * handling deliberately and must keep it. An ORDINARY single-field identity node (no relation, no
	 * {@code referenceRole} marker - e.g. a bare {@code phoneE164}) is no longer exempted merely for having one
	 * field: R13's own blanket "a single-field ALL_FIELDS node is never counting anything" assumption was too
	 * broad (CLAUDE.md &sect;15's dated entry). Used only by the {@code IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH}
	 * rule above.
	 */
	private static boolean isIdentityCountingNode(final Node pNode)
	{
		if (DecisionTreePlan.FUNCTION_AT_LEAST_N.equals(pNode.function))
		{
			return true;
		}
		if (!DecisionTreePlan.FUNCTION_ALL_FIELDS.equals(pNode.function))
		{
			return false;
		}
		if (pNode.fields.size() != 1)
		{
			// zero fields: NODE_HAS_NO_FIELDS is already reported elsewhere, nothing to "count" here; more than
			// one field: always a counting node.
			return pNode.fields.size() > 1;
		}
		final FieldRef onlyField = pNode.fields.get(0);
		return !onlyField.isRelation() && !onlyField.referenceRole;
	}

	/**
	 * True when {@code pTarget} is a leaf that {@link MatchingPolicyTranslator#forceMatchedLeavesToSuspected} can
	 * make indistinguishable from another such leaf - see {@link #validateNode}'s own {@code
	 * RELATION_NODE_OUTCOMES_MUST_DIFFER} rule. Package-visible (not {@code private}) so {@code
	 * MatchPlanLlmPass#argumentsUseAnyRelationField} can reuse the exact same predicate against the RAW,
	 * not-yet-validated {@code propose_match_plan} arguments - a relation field sitting alone in a node whose
	 * onTrue/onFalse both satisfy this must not be counted as "genuinely used" by the relation-evidence-required
	 * challenge, since {@link #validateNode} is about to refuse that very shape anyway (lot R9, 2026-09-27).
	 */
	static boolean isAmbiguousOutcomeLeaf(final String pTarget)
	{
		return DecisionTreePlan.LEAF_MATCH.equals(pTarget) || DecisionTreePlan.LEAF_SUSPECTED.equals(pTarget);
	}

	/**
	 * Lot R16 (2026-09-28, `docs/MATCHING.md` &sect;46): {@code true} for the suffix EBX's own exact matching
	 * algorithms always carry - {@code ebx-addon-mame@exact} (the plain single-field exact algorithm this
	 * codebase's own {@code MatchPlanRules}/{@code MatchPlanLlmPass} always propose) and {@code exactFullText}
	 * (a Lucene-query exact algorithm, {@code review.MatchSimulationBlock}'s own {@code fullTextFieldNames}) -
	 * same case-insensitive, last-{@code @}-segment convention {@code MatchSimulationBlock#isExactAlgorithm}
	 * already uses for the first of the two, extended here to also recognise the full-text form.
	 */
	static boolean isExactAlgorithm(final String pAlgorithm)
	{
		final String trimmed = pAlgorithm == null ? "" : pAlgorithm.trim();
		final int at = trimmed.lastIndexOf('@');
		final String suffix = (at >= 0 ? trimmed.substring(at + 1) : trimmed).toLowerCase(Locale.ROOT);
		return "exact".equals(suffix) || "exactfulltext".equals(suffix);
	}

	/**
	 * Lot R16 (2026-09-28, `docs/MATCHING.md` &sect;46, live PersVol7 measurement): the bare field name(s) of
	 * every "standalone shared identifier" node found in {@code pPlan}'s LEADING chain from the root - a
	 * PURELY STRUCTURAL notion, read off the tree's own shape, never a column name or business-word guess (this
	 * class stays domain-neutral, same discipline as every other rule here). Feeds {@code
	 * MatchingPolicyTranslator}'s own {@code PRECLUSTERING_DOMINANCE} guard: the add-on's pre-clustering
	 * (candidate search) is an APPROXIMATE, WEIGHTED search over every {@code matchingPolicy.fields} entry - a
	 * field this method returns must be given a pre-clustering weight that on its own dominates every other
	 * field's weight, or an identical value on that field alone is not enough to even make two records
	 * candidates for comparison when their OTHER fields (name, address, ...) happen to be malformed, split,
	 * inverted, or simply unfilled in one particular source.
	 * <p>
	 * A node qualifies when ALL of the following hold: it is the {@link Plan#root}, OR it is reached from the
	 * root EXCLUSIVELY through the {@code onFalse} branch of every earlier node in the chain, each of which
	 * itself already qualifies (so a node past an {@code onTrue} branch, or past the first non-qualifying node
	 * in the chain - in particular any {@link #FUNCTION_AVERAGE}/similarity node - is never even visited, let
	 * alone counted); its {@link Node#function} is {@link #FUNCTION_ALL_FIELDS} with EXACTLY ONE field; that
	 * field is neither a relation field ({@link FieldRef#isRelation}) nor marked {@link
	 * FieldRef#referenceRole} (a parent-confirmation node is a different, unrelated concept - see that field's
	 * own Javadoc, and {@code MatchPlanLlmPass}'s "PARENT CONFIRMATION NODE" prompt section); that field's
	 * {@link FieldRef#algorithm} is {@link #isExactAlgorithm exact}; its {@link FieldRef#minimumScore} is
	 * {@code null} or {@code 100} (the add-on's own documented default); and the node's {@code onTrue} is a
	 * terminal outcome ({@link #isAmbiguousOutcomeLeaf} - {@link #LEAF_MATCH} or {@link #LEAF_SUSPECTED}) <b>or, for the ROOT node only
	 * (lot MT, 2026-10-04), is another node from which at least one path reaches such an outcome</b> ({@link #leadsToPositiveOutcome}):
	 * an exact key whose true side only leads to NOT_MATCHED decides nothing positive and gets no dominance. A later node of the chain whose
	 * true side continues to another node is still not an identifier (the measured E3 tree: {@code phoneE164} then a names confirmation).
	 * <p>
	 * Returns bare (slash-less) field names, in root-to-leaf order, one per qualifying node (a plan may stack
	 * more than one standalone-identifier node before its first similarity node - each is returned, and {@code
	 * MatchingPolicyTranslator}'s own guard makes each dominate the sum of every NON-identifier field, never
	 * requiring the identifiers to also dominate each other). Empty for a null/rootless plan, a plan whose root
	 * node does not itself qualify, or an unresolvable root id - this method never throws and never reports a
	 * validation reason, it is read-only structural inspection, run only after {@link #validate} has already
	 * accepted the plan (a cycle would otherwise be possible; guarded defensively here anyway via {@code
	 * visited}, exactly like {@link #walk}'s own guard).
	 */
	public static List<String> standaloneIdentifierFieldNames(final Plan pPlan)
	{
		final List<String> out = new ArrayList<String>();
		if (pPlan == null || pPlan.root == null || pPlan.root.trim().isEmpty())
		{
			return out;
		}
		String currentId = pPlan.root;
		final Set<String> visited = new LinkedHashSet<String>();
		while (currentId != null && !DecisionTreePlan.isLeaf(currentId) && visited.add(currentId))
		{
			final Node node = pPlan.byId(currentId);
			// lot MT (2026-10-04): the ROOT's onTrue may also be another node that can reach a positive outcome (Vol1mF: root emailKey, onTrue = a
			// confirmation node); a LATER chain node still needs a terminal onTrue (E3's phone -> names confirmation keeps its weight, measured)
			final boolean trueSideCounts = node != null && (DecisionTreePlan.isAmbiguousOutcomeLeaf(node.onTrue)
				|| (currentId.equals(pPlan.root) && DecisionTreePlan.leadsToPositiveOutcome(pPlan, node.onTrue)));
			if (node == null || !DecisionTreePlan.isStandaloneIdentifierNodeShape(node) || !trueSideCounts)
			{
				break;
			}
			out.add(node.fields.get(0).field);
			currentId = node.onFalse;
		}
		return out;
	}

	/** See {@link #standaloneIdentifierFieldNames}'s own Javadoc for the exact shape this checks. */
	private static boolean isStandaloneIdentifierNodeShape(final Node pNode)
	{
		if (!DecisionTreePlan.FUNCTION_ALL_FIELDS.equals(pNode.function) || pNode.fields.size() != 1)
		{
			return false;
		}
		final FieldRef field = pNode.fields.get(0);
		if (field.isRelation() || field.referenceRole)
		{
			return false;
		}
		if (!DecisionTreePlan.isExactAlgorithm(field.algorithm))
		{
			return false;
		}
		if (field.minimumScore != null && field.minimumScore.intValue() != 100)
		{
			return false;
		}
		// the node's onTrue is checked by the caller (standaloneIdentifierFieldNames): a terminal outcome, or - for the root only - a node that reaches one
		return true;
	}

	/**
	 * Lot MT (2026-10-04, Vol1mF: {@code emailKey} got weight 1 instead of 13 because its node's {@code onTrue} was ANOTHER node, not directly
	 * SUSPECTED): {@code true} when {@code pTargetId} is a terminal outcome {@link #isAmbiguousOutcomeLeaf MATCHED/SUSPECTED}, or a node from
	 * which at least ONE path (through either branch, to any depth) reaches one - i.e. an identical value on the identifier can lead to a pair
	 * being kept, so the candidate search must find the pair on that value alone. A target that can only ever reach {@code NOT_MATCHED} (or
	 * does not resolve) is {@code false}: such an identifier decides nothing positive, and gets no dominance. Cycle-safe ({@code visited}); the
	 * plan is already validated acyclic when this runs, the guard is defensive.
	 */
	static boolean leadsToPositiveOutcome(final Plan pPlan, final String pTargetId)
	{
		return DecisionTreePlan.leadsToPositiveOutcome(pPlan, pTargetId, new LinkedHashSet<String>());
	}

	private static boolean leadsToPositiveOutcome(final Plan pPlan, final String pTargetId, final Set<String> pVisited)
	{
		if (pTargetId == null || pTargetId.trim().isEmpty())
		{
			return false;
		}
		if (DecisionTreePlan.isAmbiguousOutcomeLeaf(pTargetId))
		{
			return true;
		}
		if (DecisionTreePlan.isLeaf(pTargetId) || !pVisited.add(pTargetId))
		{
			return false;
		}
		final Node node = pPlan.byId(pTargetId);
		if (node == null)
		{
			return false;
		}
		return DecisionTreePlan.leadsToPositiveOutcome(pPlan, node.onTrue, pVisited) || DecisionTreePlan.leadsToPositiveOutcome(pPlan, node.onFalse, pVisited);
	}

	private static void validateBranch(final String pNodeId, final String pBranchName, final String pTarget, final Set<String> pIds, final List<String> pReasons)
	{
		if (pTarget == null || pTarget.trim().isEmpty())
		{
			pReasons.add("MISSING_BRANCH: node '" + pNodeId + "' has no " + pBranchName + ".");
			return;
		}
		if (!DecisionTreePlan.isLeaf(pTarget) && !pIds.contains(pTarget))
		{
			// lot M9 (2026-10-18, docs/MATCHING.md sec 63, live run 497): the model wrote a 12-node tree whose N5.onFalse named N7, a node it never defined, twice in a row -
			// the refusal named neither the ids the tree DOES define nor the two ways out, and the breaker ended the entity on the second identical text.
			final List<String> defined = new ArrayList<String>(pIds);
			final String leaves = "onTrue".equals(pBranchName) ? DecisionTreePlan.LEAF_MATCH + ", " + DecisionTreePlan.LEAF_SUSPECTED + " or " + DecisionTreePlan.LEAF_NOT_MATCHED
				: DecisionTreePlan.LEAF_SUSPECTED + " or " + DecisionTreePlan.LEAF_NOT_MATCHED;
			pReasons.add("UNKNOWN_BRANCH_TARGET: node '" + pNodeId + "'." + pBranchName + " = '" + pTarget + "' is neither a leaf nor a known node id (the tree defines "
				+ (defined.isEmpty() ? "no node id" : DecisionTreePlan.join(defined)) + "; the leaves are " + DecisionTreePlan.LEAF_MATCH + ", " + DecisionTreePlan.LEAF_SUSPECTED + ", "
				+ DecisionTreePlan.LEAF_NOT_MATCHED + "). Two fixes: define node '" + pTarget + "' in nodes[], or point " + pNodeId + "." + pBranchName + " to a leaf (" + leaves + ").");
		}
	}

	/** Reachability from {@link Plan#root} and cycle detection - only called once every node id is known distinct and the root itself resolves (see caller). */
	private static void validateGraphShape(final Plan pPlan, final List<String> pReasons)
	{
		final Set<String> visited = new LinkedHashSet<String>();
		final Set<String> inStack = new LinkedHashSet<String>();
		final boolean[] cycle = new boolean[] { false };
		final int[] maxDepth = new int[] { 0 };
		DecisionTreePlan.walk(pPlan, pPlan.root, visited, inStack, 0, maxDepth, cycle);
		if (cycle[0])
		{
			pReasons.add("CYCLE_DETECTED: the tree loops back on itself.");
		}
		else if (maxDepth[0] > DecisionTreePlan.MAX_DEPTH)
		{
			pReasons.add("TOO_DEEP: depth " + maxDepth[0] + " exceeds the cap of " + DecisionTreePlan.MAX_DEPTH + ".");
		}
		for (final Node node : pPlan.nodes)
		{
			if (node.id != null && !visited.contains(node.id))
			{
				pReasons.add("UNREACHABLE_NODE: '" + node.id + "' is never reached from the root.");
			}
		}
	}

	private static void walk(final Plan pPlan, final String pCurrent, final Set<String> pVisited, final Set<String> pInStack, final int pDepth,
		final int[] pMaxDepth, final boolean[] pCycle)
	{
		if (pCycle[0] || DecisionTreePlan.isLeaf(pCurrent) || pCurrent == null)
		{
			return;
		}
		if (pInStack.contains(pCurrent))
		{
			pCycle[0] = true;
			return;
		}
		final Node node = pPlan.byId(pCurrent);
		if (node == null)
		{
			return;
		}
		pVisited.add(pCurrent);
		pInStack.add(pCurrent);
		pMaxDepth[0] = Math.max(pMaxDepth[0], pDepth + 1);
		DecisionTreePlan.walk(pPlan, node.onTrue, pVisited, pInStack, pDepth + 1, pMaxDepth, pCycle);
		DecisionTreePlan.walk(pPlan, node.onFalse, pVisited, pInStack, pDepth + 1, pMaxDepth, pCycle);
		pInStack.remove(pCurrent);
	}

	// --------------------------------------------------------------------------------------------------- nested

	/**
	 * The add-on's own nested {@code decisionTree} shape - see class Javadoc. Leaves are always written with the
	 * schema's documented tokens ({@code MATCHED}/{@code SUSPECTED}/{@code NOT_MATCHED}); the caller ({@code
	 * MatchingPolicyTranslator}) is the one place that forces a {@code MATCHED} leaf back down to {@code
	 * SUSPECTED} when the plan's {@code autoMerge} is false - a policy decision, deliberately kept out of this
	 * product-neutral class.
	 * <p>
	 * <b>2026-09-22, coordinator follow-up: this method never has to "unroll" a shared subtree.</b> An earlier
	 * revision of this class allowed a flat model where one node was reached from two different parents (a DAG,
	 * not a tree) - since the add-on's own nested shape has no way to reference a node twice, {@code
	 * toNestedTarget} would recurse into that node's subtree a second time and build an entirely separate copy
	 * of it ("unrolling"). {@link #validate}'s new {@code NODE_SHARED} rule (a node has exactly one parent, like
	 * the add-on's own nested shape) now makes that structurally impossible for any plan this codebase actually
	 * accepts: {@code MatchPlanLlmPass.Session} validates before ever storing a plan, and {@code
	 * MatchingPolicyTranslator} validates again immediately before calling this method (belt and braces - see
	 * that class' own self-checks). The recursion-guard below is kept only as a DEAD-CODE defensive backstop for
	 * a plan built by hand and never validated (a bug elsewhere, not a case this codebase's own callers can
	 * reach) - it still terminates rather than looping forever on a cycle, at the cost of a wrong {@code
	 * NOT_MATCHED} stub, never by silently unrolling a legitimate second copy.
	 */
	public static ObjectNode toNested(final Plan pPlan)
	{
		return DecisionTreePlan.toNestedTarget(pPlan, pPlan.root, new LinkedHashSet<String>());
	}

	/** {@code pAncestors} is a recursion-stack (the path from the root to here), not a "visited" set - see {@link #toNested}'s own Javadoc for why a validated plan never actually triggers the dead-code fallback below. */
	private static ObjectNode toNestedTarget(final Plan pPlan, final String pTarget, final Set<String> pAncestors)
	{
		if (DecisionTreePlan.isLeaf(pTarget))
		{
			return DecisionTreePlan.nestedLeaf(pTarget);
		}
		final Node node = pPlan.byId(pTarget);
		if (node == null || !pAncestors.add(pTarget))
		{
			// dead code for any plan that passed validate() (see this method's own Javadoc) - a defensive-only
			// fallback for an unknown id or a cycle, rendered as NOT_MATCHED rather than a NullPointerException or
			// an infinite loop, so a caller that skips validate() (its own mistake) still gets a well-formed, if
			// wrong, document back.
			return DecisionTreePlan.nestedLeaf(DecisionTreePlan.LEAF_NOT_MATCHED);
		}
		final ObjectNode out = DecisionTreePlan.MAPPER.createObjectNode();
		out.put("name", DecisionTreePlan.labelFor(node));
		out.put("function", DecisionTreePlan.nestedFunctionName(node.function));
		final ArrayNode nodeParams = out.putArray("params");
		if (DecisionTreePlan.FUNCTION_AVERAGE.equals(node.function))
		{
			DecisionTreePlan.addParam(nodeParams, "minimumScore", String.valueOf(node.minimumScore == null ? 90 : node.minimumScore.intValue()));
		}
		else if (DecisionTreePlan.FUNCTION_AT_LEAST_N.equals(node.function))
		{
			DecisionTreePlan.addParam(nodeParams, "minimumNumberOfMatches", String.valueOf(node.minimumMatches == null ? 1 : node.minimumMatches.intValue()));
		}
		final ArrayNode fields = out.putArray("fields");
		for (final FieldRef field : node.fields)
		{
			fields.add(DecisionTreePlan.nestedField(node.function, field));
		}
		out.set("trueNode", DecisionTreePlan.toNestedTarget(pPlan, node.onTrue, pAncestors));
		out.set("falseNode", DecisionTreePlan.toNestedTarget(pPlan, node.onFalse, pAncestors));
		pAncestors.remove(pTarget);
		return out;
	}

	private static ObjectNode nestedLeaf(final String pLeaf)
	{
		final ObjectNode leaf = DecisionTreePlan.MAPPER.createObjectNode();
		if (DecisionTreePlan.LEAF_MATCH.equals(pLeaf))
		{
			leaf.put("name", DecisionTreePlan.NESTED_LEAF_MATCH);
		}
		else if (DecisionTreePlan.LEAF_SUSPECTED.equals(pLeaf))
		{
			leaf.put("name", DecisionTreePlan.NESTED_LEAF_SUSPECTED);
		}
		else
		{
			leaf.put("name", DecisionTreePlan.NESTED_LEAF_NOT_MATCHED);
		}
		leaf.putNull("function");
		return leaf;
	}

	private static String nestedFunctionName(final String pFunction)
	{
		if (DecisionTreePlan.FUNCTION_AVERAGE.equals(pFunction))
		{
			return DecisionTreePlan.NESTED_FUNCTION_AVERAGE;
		}
		if (DecisionTreePlan.FUNCTION_AT_LEAST_N.equals(pFunction))
		{
			return DecisionTreePlan.NESTED_FUNCTION_AT_LEAST_N;
		}
		if (DecisionTreePlan.FUNCTION_PREDICATE.equals(pFunction))
		{
			return DecisionTreePlan.NESTED_FUNCTION_PREDICATE;
		}
		return DecisionTreePlan.NESTED_FUNCTION_ALL_FIELDS;
	}

	/**
	 * The node label as it will be written to the add-on ({@code decisionTree...name}): {@link Node#label} when
	 * set, else the same auto-generated label {@link #toNestedTarget} always used to compute inline. Exposed
	 * (2026-09-26, relation-fields lot) so {@code master.MatchingPolicyTranslator} can freeze a label BEFORE it
	 * rewrites a relation field's name (the auto label is built from the CURRENT field names), without duplicating this class' own label logic.
	 */
	public static String labelFor(final Node pNode)
	{
		return pNode.label != null && !pNode.label.trim().isEmpty() ? pNode.label : DecisionTreePlan.autoLabel(pNode);
	}

	/**
	 * Freezes {@link Node#label} for every node of {@code pPlan} (computing {@link #labelFor} from each node's
	 * CURRENT fields when the label is still blank) and disambiguates any COLLISION between two DIFFERENT
	 * nodes' labels - lot R8 (2026-09-27, live PersVol7, PipelineStep 587, `docs/MATCHING.md` dated entry):
	 * {@code generate_matching_policy} refused a tree with two nodes carrying the identical add-on-stored
	 * {@code name} ("Duplicate decision node name in the decision tree: Similar firstName and lastName and
	 * email and p...") - {@link #autoLabel} names a node purely from its {@code function}/{@code fields}, with
	 * no regard for a SIBLING node comparing the exact same fields (e.g. two {@code AVERAGE} nodes on the same
	 * four fields at different thresholds, or two relation nodes on the same child fields - both legitimate,
	 * distinct decisions), and an explicit LLM-given {@link Node#label} can collide the very same way.
	 * <p>
	 * <b>Idempotent</b> - safe to call more than once on the same {@link Plan}: a {@link Node#label} already set
	 * (frozen by an earlier call, unique or not) is used AS IS as this call's own candidate, never recomputed
	 * from {@link #autoLabel} again, so a label this method already disambiguated is never re-suffixed.
	 * {@code master.MatchingPolicyTranslator} calls this exactly where every node's name must already be its
	 * FINAL, add-on-stored string: once, up front, before it rewrites the relation fields to their business-object path
	 * (so the node name does not change with the rewrite), and again in the shared {@code buildMatchingPolicyFromPlan} body
	 * (a no-op there for a plan already frozen).
	 * <p>
	 * Disambiguation is deterministic: nodes are visited in {@link Plan#nodes}' own list order (an LLM's or
	 * {@link #fromNested}'s own pre-order assignment - never re-derived from the tree shape here), and every
	 * node whose computed/explicit label repeats one already seen earlier in that order has its OWN id appended,
	 * {@code " (<id>)"} (e.g. {@code "Similar firstName and lastName (N4)"}) - {@link Node#id} is unique by
	 * construction ({@link #validate}'s own {@code DUPLICATE_ID} rule), so the suffixed result can never itself
	 * collide with another node's. A label that does not repeat is left exactly as computed/given, so a plan
	 * with no collision at all renders byte-for-byte as it did before this fix.
	 */
	public static void freezeAndDisambiguateLabels(final Plan pPlan)
	{
		final Map<String, Integer> seen = new LinkedHashMap<String, Integer>();
		for (final Node node : pPlan.nodes)
		{
			final String candidate = node.label != null && !node.label.trim().isEmpty() ? node.label : DecisionTreePlan.autoLabel(node);
			final Integer priorCount = seen.get(candidate);
			if (priorCount == null)
			{
				seen.put(candidate, Integer.valueOf(1));
				node.label = candidate;
			}
			else
			{
				seen.put(candidate, Integer.valueOf(priorCount.intValue() + 1));
				node.label = candidate + " (" + node.id + ")";
			}
		}
	}

	private static String autoLabel(final Node pNode)
	{
		final StringBuilder label = new StringBuilder();
		if (DecisionTreePlan.FUNCTION_AVERAGE.equals(pNode.function))
		{
			label.append("Similar ");
		}
		else if (DecisionTreePlan.FUNCTION_AT_LEAST_N.equals(pNode.function))
		{
			label.append("At least ").append(pNode.minimumMatches == null ? 1 : pNode.minimumMatches.intValue()).append(" of ");
		}
		else if (DecisionTreePlan.FUNCTION_PREDICATE.equals(pNode.function))
		{
			label.append("Predicate on ");
		}
		else
		{
			label.append("Same ");
		}
		boolean first = true;
		for (final FieldRef field : pNode.fields)
		{
			if (!first)
			{
				label.append(" and ");
			}
			if (field.isRelation())
			{
				label.append(field.via).append('.').append(field.field);
			}
			else
			{
				label.append(field.field);
			}
			first = false;
		}
		return label.toString();
	}

	private static ObjectNode nestedField(final String pFunction, final FieldRef pField)
	{
		final ObjectNode out = DecisionTreePlan.MAPPER.createObjectNode();
		final String name = pField.field == null ? "" : pField.field.trim();
		final String fieldPath = name.startsWith("/") ? name : "/" + name;
		out.put("field", fieldPath);
		if (pField.isRelation())
		{
			// native relation field (add-on 6.3.0.5-RC3, `docs/MATCHING.md` sect;33): the tree field is the absolute path of the member
			// table's field; businessObjectField repeats it (the validator reads `field`, the server derives the relationship from the path)
			out.put("businessObjectField", fieldPath);
		}
		out.put("algorithm", pField.algorithm);
		out.put("bothNullValueHandling", pField.bothNull == null ? "NOT_MATCH" : pField.bothNull);
		out.put("oneNullValueHandling", pField.oneNull == null ? "NOT_MATCH" : pField.oneNull);
		final ArrayNode params = out.putArray("params");
		if (DecisionTreePlan.FUNCTION_AVERAGE.equals(pFunction))
		{
			DecisionTreePlan.addParam(params, "weight", String.valueOf(pField.weight == null ? 1 : pField.weight.intValue()));
		}
		else if (DecisionTreePlan.FUNCTION_PREDICATE.equals(pFunction))
		{
			DecisionTreePlan.addParam(params, "predicate", pField.predicate == null ? "" : pField.predicate);
		}
		else
		{
			DecisionTreePlan.addParam(params, "minimumScore", String.valueOf(pField.minimumScore == null ? 100 : pField.minimumScore.intValue()));
		}
		for (final String[] extra : pField.extraParams)
		{
			DecisionTreePlan.addParam(params, extra[0], extra[1]);
		}
		return out;
	}

	private static void addParam(final ArrayNode pParams, final String pName, final String pValue)
	{
		for (final JsonNode existing : pParams)
		{
			if (pName.equals(existing.path("name").asText(null)))
			{
				return;
			}
		}
		final ObjectNode param = pParams.addObject();
		param.put("name", pName);
		param.put("value", pValue);
	}

	/** {@link #fromNested}'s own result - a {@link Plan}, plus every non-fatal oddity found along the way (an unrecognised leaf name, an unrecognised function) - see class Javadoc "never losing a node". Never null, never throws. */
	public static final class FromNestedResult
	{
		public final Plan plan;
		public final List<String> warnings;

		private FromNestedResult(final Plan pPlan, final List<String> pWarnings)
		{
			this.plan = pPlan;
			this.warnings = pWarnings;
		}
	}

	/** Reads ANY add-on {@code decisionTree} document back into a {@link Plan}, ids {@code N1..Nn} assigned in pre-order (root first) - see class Javadoc. */
	public static FromNestedResult fromNested(final JsonNode pNested)
	{
		final Plan plan = new Plan();
		final List<String> warnings = new ArrayList<String>();
		if (pNested == null || pNested.isMissingNode())
		{
			return new FromNestedResult(plan, warnings);
		}
		final int[] counter = new int[] { 0 };
		final String target = DecisionTreePlan.convertNested(pNested, plan, counter, warnings);
		plan.root = DecisionTreePlan.isLeaf(target) ? null : target;
		return new FromNestedResult(plan, warnings);
	}

	/** @return either a leaf token or the id assigned to the node just added to {@code pOutPlan.nodes}. */
	private static String convertNested(final JsonNode pNested, final Plan pOutPlan, final int[] pCounter, final List<String> pWarnings)
	{
		if (pNested == null || pNested.isMissingNode() || !pNested.hasNonNull("function"))
		{
			return DecisionTreePlan.leafFromNestedName(pNested == null ? null : pNested.path("name").asText(null), pWarnings);
		}
		final String id = "N" + (++pCounter[0]);
		final Node node = new Node(id, null);
		node.label = pNested.hasNonNull("name") ? pNested.path("name").asText() : null;
		pOutPlan.nodes.add(node);

		final String rawFunction = pNested.path("function").asText("");
		if (DecisionTreePlan.NESTED_FUNCTION_ALL_FIELDS.equals(rawFunction))
		{
			node.function = DecisionTreePlan.FUNCTION_ALL_FIELDS;
		}
		else if (DecisionTreePlan.NESTED_FUNCTION_AT_LEAST_N.equals(rawFunction))
		{
			node.function = DecisionTreePlan.FUNCTION_AT_LEAST_N;
		}
		else if (DecisionTreePlan.NESTED_FUNCTION_AVERAGE.equals(rawFunction))
		{
			node.function = DecisionTreePlan.FUNCTION_AVERAGE;
		}
		else if (DecisionTreePlan.NESTED_FUNCTION_PREDICATE.equals(rawFunction))
		{
			node.function = DecisionTreePlan.FUNCTION_PREDICATE;
		}
		else
		{
			node.function = DecisionTreePlan.FUNCTION_PREDICATE;
			pWarnings.add("Unknown function '" + rawFunction + "' on node '" + id + "': kept as a PREDICATE passthrough so nothing is lost.");
		}
		for (final JsonNode param : pNested.path("params"))
		{
			final String name = param.path("name").asText("");
			final String value = param.path("value").asText("");
			if ("minimumScore".equals(name))
			{
				node.minimumScore = DecisionTreePlan.parseIntOrNull(value);
			}
			else if ("minimumNumberOfMatches".equals(name))
			{
				node.minimumMatches = DecisionTreePlan.parseIntOrNull(value);
			}
		}
		for (final JsonNode fieldJson : pNested.path("fields"))
		{
			node.fields.add(DecisionTreePlan.fieldFromNested(fieldJson));
		}
		node.onTrue = DecisionTreePlan.convertNested(pNested.path("trueNode"), pOutPlan, pCounter, pWarnings);
		node.onFalse = DecisionTreePlan.convertNested(pNested.path("falseNode"), pOutPlan, pCounter, pWarnings);
		return id;
	}

	private static Integer parseIntOrNull(final String pValue)
	{
		try
		{
			return Integer.valueOf(Integer.parseInt(pValue.trim()));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** {@link #relationFromNestedField}'s own result - see that method's Javadoc. Pure value object. */
	public static final class RelationRef
	{
		public final String childEntity;
		public final String childTablePath;
		public final String foreignKeyPath;
		public final String field;

		RelationRef(final String pChildEntity, final String pChildTablePath, final String pForeignKeyPath, final String pField)
		{
			this.childEntity = pChildEntity;
			this.childTablePath = pChildTablePath;
			this.foreignKeyPath = pForeignKeyPath;
			this.field = pField;
		}
	}

	/**
	 * Add-on build 6.3.0.5-RC3, measured live 2026-10-12 on the scratch model W13BO (`docs/MATCHING.md` sect;33): a relationship
	 * field is stored with the ABSOLUTE path of the member table's field - a decision-tree field reads back
	 * {@code field = /root/Adresse/city}, a matching-policy field {@code field = null, businessObjectField = /root/Adresse/city,
	 * useRelationships = BY_FOREIGN_KEY, location.associationTable = /root/Adresse/personneRef}. A field of the entity's OWN
	 * table is a one-segment path ({@code /city}, or a group path that never starts with {@code /root/}), so
	 * {@code /root/<Table>/<field>} recognises a relation field on {@code businessObjectField} first, then {@code field}.
	 * Returns null for a plain field. {@link RelationRef#foreignKeyPath} is not part of what the add-on echoes: always null.
	 */
	public static RelationRef relationFromNestedField(final JsonNode pFieldJson)
	{
		if (pFieldJson == null || pFieldJson.isMissingNode())
		{
			return null;
		}
		String path = pFieldJson.path("businessObjectField").asText(null);
		if (path == null || path.trim().isEmpty())
		{
			path = pFieldJson.path("field").asText(null);
		}
		if (path == null)
		{
			return null;
		}
		final String rootPrefix = "/root/";
		if (!path.startsWith(rootPrefix))
		{
			return null;
		}
		final String rest = path.substring(rootPrefix.length());
		final int slash = rest.indexOf('/');
		if (slash <= 0 || slash == rest.length() - 1)
		{
			return null;
		}
		final String childEntity = rest.substring(0, slash);
		return new RelationRef(childEntity, rootPrefix + childEntity, null, rest.substring(slash + 1));
	}

	private static FieldRef fieldFromNested(final JsonNode pFieldJson)
	{
		final FieldRef field = new FieldRef();
		final String rawField = pFieldJson.path("field").asText(null);
		field.field = rawField != null && rawField.startsWith("/") ? rawField.substring(1) : rawField;
		field.algorithm = pFieldJson.path("algorithm").asText(null);
		final RelationRef relation = DecisionTreePlan.relationFromNestedField(pFieldJson);
		if (relation != null)
		{
			field.via = relation.childEntity;
			field.field = relation.field;
		}
		field.bothNull = pFieldJson.hasNonNull("bothNullValueHandling") ? pFieldJson.path("bothNullValueHandling").asText() : null;
		field.oneNull = pFieldJson.hasNonNull("oneNullValueHandling") ? pFieldJson.path("oneNullValueHandling").asText() : null;
		for (final JsonNode param : pFieldJson.path("params"))
		{
			final String name = param.path("name").asText("");
			final String value = param.path("value").asText("");
			if ("weight".equals(name))
			{
				field.weight = DecisionTreePlan.parseIntOrNull(value);
			}
			else if ("minimumScore".equals(name))
			{
				field.minimumScore = DecisionTreePlan.parseIntOrNull(value);
			}
			else if ("predicate".equals(name))
			{
				field.predicate = value;
			}
			else
			{
				field.extraParams.add(new String[] { name, value });
			}
		}
		return field;
	}

	/** Tolerant leaf-name mapping - see class Javadoc: the LIVE {@code Companies} configuration names its leaves {@code "Match"}/{@code "Suspect"}/{@code "Not Match"}, not the schema's own documented tokens. Checked in this order so {@code "Not Match"} (containing neither "SUSPECT" first) is not mistaken for a match. */
	private static String leafFromNestedName(final String pName, final List<String> pWarnings)
	{
		final String upper = (pName == null ? "" : pName).toUpperCase(Locale.ROOT);
		if (upper.contains("SUSPECT"))
		{
			return DecisionTreePlan.LEAF_SUSPECTED;
		}
		if (upper.contains("NOT"))
		{
			return DecisionTreePlan.LEAF_NOT_MATCHED;
		}
		if (upper.contains("MATCH"))
		{
			return DecisionTreePlan.LEAF_MATCH;
		}
		pWarnings.add("Unrecognized leaf name '" + pName + "': treated as NOT_MATCHED.");
		return DecisionTreePlan.LEAF_NOT_MATCHED;
	}

	// --------------------------------------------------------------------------------------------- comparison

	/**
	 * Structural equivalence used by {@code MatchPlanLlmPass.sameStructure} - ids and node ORDER both ignored (two
	 * plans that reach the same leaves through the same functions/fields/thresholds on the same branches are the
	 * same plan, whatever their nodes happen to be called or listed in) by comparing the RECURSIVE {@link
	 * #toNested} rendering (which has no ids at all) rather than the flat lists directly. A node reachable from
	 * more than one place, or an invalid plan, still compares safely (never throws) - {@link #toNested} itself
	 * never throws.
	 */
	public static boolean sameStructure(final Plan pOldPlan, final Plan pNewPlan)
	{
		return DecisionTreePlan.toNested(pOldPlan).equals(DecisionTreePlan.toNested(pNewPlan));
	}

	static ObjectMapper mapper()
	{
		return DecisionTreePlan.MAPPER;
	}
}
