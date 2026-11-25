package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Structural comparison between a LIVE {@code get_matching_config} answer's own matching-policy/merge-policy
 * entries and {@code MatchingPolicyTranslator}'s freshly-built translation of the CURRENT accepted {@code
 * MatchPlan} - the missing half of `docs/MATCHING.md` &sect;19 "the gap": {@code ebx.MatchingConfigurator}
 * used to RESUME (validate + activate only) the moment a real, owned configuration was found, never checking
 * whether the accepted plan had actually changed since the config was last written. This class answers
 * exactly that question, pure Jackson, no EBX class, unit-tested without a running instance - same convention
 * as every other class in this package.
 * <p>
 * <b>What is compared, and why - the task brief's own scope, deliberately narrower than "every field
 * `apply_matching_config` could touch":</b>
 * <ul>
 * <li>the decision tree ({@code matchingPolicies[].decisionTree}) - the nested shape {@code
 * master.DecisionTreePlan#toNested} builds, compared STRUCTURALLY by {@link #sameDecisionTree} (walking both
 * sides in parallel, NOT a plain normalize-then-deep-equals - see that method's own Javadoc "read-back gaps",
 * 2026-09-23): an internal decision node's own display {@code name} (a cosmetic label - {@code
 * DecisionTreePlan.Node#label}/{@code #autoLabel}, never read by the matching engine) is ignored; a LEAF's
 * {@code name} (the actual outcome) is compared through {@link #canonicalLeafOutcome} - tolerant of BOTH the
 * documented enum tokens ({@code MATCHED}/{@code SUSPECTED}/{@code NOT_MATCHED}) this class emits and the
 * add-on's own live DISPLAY strings ({@code "Match"}/{@code "Suspect"}/{@code "Not Match"}) a real read-back
 * actually carries; a node's own {@code params[]} (thresholds) and per-field {@code params[]} are compared
 * NUMERICALLY where possible ({@link #sameParams}, since the add-on may echo a value as a JSON number where
 * this class always emits a string); a node's own {@code fields[]} are compared as a SET keyed by {@code
 * field} path ({@link #sameTreeFieldSet}, order may differ from ours), each field's null-handling defaulted
 * to {@code NOT_MATCH} when absent (the tree's own default, distinct from the candidate gate's {@code
 * DO_NOTHING} below). A {@code null}-valued key and an ABSENT key are treated as equivalent throughout.</li>
 * <li>the candidate-gate fields ({@code matchingPolicies[].fields[]}) - compared as a SET keyed by {@code
 * field} path (order is never significant here, unlike the decision tree itself), each entry canonicalised to
 * {@code {weight, searchStrategy, bothNullValueHandling, oneNullValueHandling}} with
 * {@code MatchingPolicyTranslator.buildMatchingPolicy}'s own defaults ({@code DO_NOTHING} for null-handling)
 * backfilled for a key either side omits.</li>
 * <li>{@code mergePolicyCode} - the matching policy's own link to its merge policy (set by {@code
 * patch_matching_config}'s {@code set_merge_policy_link} op on the fresh path, never present in {@code
 * MatchingPolicyTranslator.buildMatchingPolicy}'s own output - the caller supplies the DESIRED code
 * separately, by convention always the entity name itself: {@code ebx.MatchingConfigurator} never links a
 * matching policy to any other merge policy).</li>
 * <li>the merge policy's {@code recordSelectionFunctionChain}/{@code defaultFieldMergeFunctionChain} (order-
 * sensitive: a chain's own fallback semantics assign PRIORITY to position) and {@code survivors[]} (order-
 * INsensitive across fields - a stored answer may list them in a different order than {@code
 * MatchingPolicyTranslator.buildMergePolicy} builds them in - but order-sensitive WITHIN one field's own
 * {@code mergeFunctionChain}), both through {@link #sameChain} - each entry canonicalised by {@link
 * #normalizeChainEntry} so an explicit {@code constantValue: null}/{@code dateFieldPath: null} (a real,
 * measured read-back shape) and the add-on's own {@code dateFieldPath: "System date"} default on a date-aware
 * entry this class emits with none (measured live, 2026-09-23, coordinator - independently confirmed the same
 * day by {@code master.MergeConfigPatchPlanner}'s own {@code normalizedDateFieldPath}) never read as a
 * difference.</li>
 * <li><b>{@code relatedTables} alignment (lot 2, cascade, 2026-09-23, `docs/MATCHING.md` &sect;12.4/&sect;22)</b> - NOW
 * compared, narrowly: the merge policy's own {@code relatedTables[]} entries are matched against {@code
 * RelatedTableAlignment.align}'s own desired output (built from the SAME stored list plus the caller-supplied
 * {@code pChildTablePaths}) by {@code path}, and only the matched pair's {@code alignForeignKeys} value is
 * compared. An entry present on one side only (typically {@code generate_merge_policy}'s own inverse-FK
 * auto-additions the caller's {@code pChildTablePaths} does not yet know about, or simply never matched) is
 * SKIPPED, never counted as a difference - this class never proposes adding or removing a {@code
 * relatedTables[]} entry, only realigning one already present on both sides (see {@code RelatedTableAlignment}'s
 * own Javadoc: the add-on, not this generator, owns list membership).</li>
 * </ul>
 * Deliberately NOT compared (the task brief's own scope, `docs/MATCHING.md` &sect;19): {@code active}, {@code
 * matchingOnCreation}/{@code matchingOnUpdate}, {@code executePurge}, {@code showMatchType}, {@code
 * matchAgainstMergedRecords}, any {@code workflowOn*} key, the merge policy's {@code mode}/{@code
 * useForManualMerge}/{@code applyPermission}, and the whole {@code trustedSources}/{@code tableSettings}
 * surface - these are either operational/admin settings a data steward may have tuned live (never something a
 * plan translation should silently clobber) or the exclusive concern of the survivorship route ({@code
 * set_trusted_sources}/{@code MergeConfigPatchPlanner}). {@code relatedTables} moved OUT of this list in lot 2
 * (above) - only its per-entry {@code alignForeignKeys} value is compared, never the rest of the merge policy's
 * own admin surface.
 * <p>
 * <b>2026-09-23, live round-trip gaps closed (coordinator, PipelineStep 128).</b> The FIRST live re-{@code
 * configure} of an entity whose stored policy was BYTE-FOR-BYTE what this project's own translator had
 * written (a genuine {@code Diff.same()} case) instead answered {@code "differs: decisionTree, ..."} - not
 * because the plan had changed, but because {@link #sameDecisionTree}'s predecessor (a plain {@link
 * #normalize}-then-deep-equals) could not tell a real difference from an add-on read-back artifact: leaf
 * outcomes echoed as display strings, a {@code dateFieldPath: "System date"} default stamped onto an entry
 * that never set one, param values echoed as JSON numbers, and node-level {@code fields[]} in a different
 * order. {@link #sameDecisionTree}/{@link #sameChain} (both new this lot) tolerate exactly these, and ONLY
 * these - see each method's own Javadoc for the precise rule and {@code MatchingPolicyComparatorTest}'s own
 * live-pair fixture (`docs/MATCHING.md` &sect;19.11) for the regression test built from the exact stored shape
 * this gap was found against. {@code sameDecisionTree}/{@code sameChain} also only ever READ the specific keys
 * they document (never a blind object-equality over every key present) - an echoed key neither method reads
 * at all is silently ignored, never a source of a spurious difference, unlike the old {@code normalize}-based
 * approach.
 * <p>
 * <b>Still best-effort, like every other "shape read from a live answer" class in this module.</b> The
 * candidate-gate field comparison ({@link #sameCandidateFields}) and the {@code relatedTables} alignment
 * ({@link #sameRelatedTables}) were NOT exercised by the PipelineStep 128 live round-trip above (that entity's
 * fields/relatedTables genuinely matched already) - a future read-back gap in either is plausible and would
 * show the same symptom (a spurious {@code differs}) until found and fixed the same way. Whoever finds one
 * should re-check a genuinely unmodified round trip (a plan re-{@code configure}d with no changes) reports
 * {@link Diff#same()} before trusting a REWRITE decision on a live table.
 * <p>
 * <b>2026-09-23 13:30, a THIRD read-back gap (`docs/MATCHING.md` &sect;26, PipelineStep 159, Contact "Simulate"
 * pass).</b> {@link #sameParams} used to require the STORED and DESIRED sides of a node's/field's own {@code
 * params[]} to carry the exact same set of names - a jarowinkler field's {@code get_matching_config} read-back
 * carries an extra {@code jaroWinklerThreshold: 0.7} entry this class never sends (an algorithm default the
 * add-on fills in on its own), which made every jarowinkler field compare as changed forever, regardless of
 * whether the plan itself had actually changed. {@link #sameParams} is now asymmetric: a STORED-only param is
 * an add-on default and ignored, a DESIRED-only param (or a shared one with a different value) is still a real
 * difference - see that method's own Javadoc.
 */
public final class MatchingPolicyComparator
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MatchingPolicyComparator()
	{
	}

	/** {@link #compare}'s own result - which of the six compared parts (if any) actually differ. */
	public static final class Diff
	{
		public final boolean decisionTreeChanged;
		public final boolean candidateFieldsChanged;
		public final boolean mergePolicyCodeChanged;
		public final boolean mergeChainsChanged;
		public final boolean survivorsChanged;
		/** Lot 2, cascade (2026-09-23) - see class Javadoc "relatedTables alignment". */
		public final boolean relatedTablesChanged;

		Diff(final boolean pDecisionTreeChanged, final boolean pCandidateFieldsChanged, final boolean pMergePolicyCodeChanged, final boolean pMergeChainsChanged,
			final boolean pSurvivorsChanged, final boolean pRelatedTablesChanged)
		{
			this.decisionTreeChanged = pDecisionTreeChanged;
			this.candidateFieldsChanged = pCandidateFieldsChanged;
			this.mergePolicyCodeChanged = pMergePolicyCodeChanged;
			this.mergeChainsChanged = pMergeChainsChanged;
			this.survivorsChanged = pSurvivorsChanged;
			this.relatedTablesChanged = pRelatedTablesChanged;
		}

		/** {@code true} when NONE of the six compared parts differ - {@code ebx.MatchingConfigurator}'s own signal to resume (validate/activate only) rather than rewrite. */
		public boolean same()
		{
			return !this.decisionTreeChanged && !this.candidateFieldsChanged && !this.mergePolicyCodeChanged && !this.mergeChainsChanged && !this.survivorsChanged
				&& !this.relatedTablesChanged;
		}

		/** Human-readable summary for a {@code PipelineStep}'s own recorded detail - {@code ebx.MatchingConfigurator} never surfaces the raw booleans directly. */
		public String summary()
		{
			if (this.same())
			{
				return "same - stored policy already matches the accepted plan";
			}
			final List<String> parts = new ArrayList<String>();
			if (this.decisionTreeChanged)
			{
				parts.add("decisionTree");
			}
			if (this.candidateFieldsChanged)
			{
				parts.add("fields (candidate gate)");
			}
			if (this.mergePolicyCodeChanged)
			{
				parts.add("mergePolicyCode");
			}
			if (this.mergeChainsChanged)
			{
				parts.add("merge policy chains (recordSelection/defaultFieldMerge/golden source)");
			}
			if (this.survivorsChanged)
			{
				parts.add("merge policy survivors");
			}
			if (this.relatedTablesChanged)
			{
				parts.add("merge policy related tables (alignForeignKeys)");
			}
			return "differs: " + String.join(", ", parts);
		}
	}

	/**
	 * @param pStoredMatchingPolicy the entity's own {@code matchingPolicies[]} entry, as found in a live {@code
	 *            get_matching_config} answer ({@link #findPolicyEntry}) - a {@link MissingNode} (never {@code
	 *            null}) reads every part as absent, which compares as CHANGED against any non-trivial desired
	 *            value (this class never treats "could not find our own entry" as silently "same").
	 * @param pDesiredMatchingPolicy {@code MatchingPolicyTranslator.buildMatchingPolicy}'s own output for the
	 *            CURRENT accepted plan.
	 * @param pDesiredMergePolicyCode the merge policy {@code pDesiredMatchingPolicy} SHOULD be linked to - by
	 *            convention always the entity name itself ({@code ebx.MatchingConfigurator} never links to
	 *            anything else); never read off {@code pDesiredMatchingPolicy} itself, which does not carry a
	 *            {@code mergePolicyCode} key at all (that link is only ever set by {@code
	 *            patch_matching_config}'s {@code set_merge_policy_link} op, not by the translator).
	 * @param pStoredMergePolicy the merge policy {@code pStoredMatchingPolicy} is ACTUALLY linked to right now
	 *            (found via ITS OWN {@code mergePolicyCode}, not assumed to already be {@code
	 *            pDesiredMergePolicyCode} - a mismatch there is exactly what {@link Diff#mergePolicyCodeChanged}
	 *            reports).
	 * @param pDesiredMergePolicy {@code MatchingPolicyTranslator.buildMergePolicy}'s own output for the CURRENT
	 *            accepted plan - never carries {@code relatedTables} itself (the translator deliberately never
	 *            emits it, see {@code MatchingPolicyTranslator}'s own Javadoc); the DESIRED {@code
	 *            relatedTables} is instead computed here, from {@code pStoredMergePolicy}'s own list plus
	 *            {@code pChildTablePaths}, via {@link RelatedTableAlignment#align}.
	 * @param pChildTablePaths lot 2, cascade (2026-09-23) - every promoted CHILD entity's own table path of the
	 *            entity this merge policy belongs to ({@code MasterModel.Result#childTablePaths}); {@code null}
	 *            is treated as the empty set (no cascade candidate at all - every {@code relatedTables[]} entry
	 *            then compares as unchanged, whatever it currently holds).
	 */
	public static Diff compare(final JsonNode pStoredMatchingPolicy, final ObjectNode pDesiredMatchingPolicy, final String pDesiredMergePolicyCode,
		final JsonNode pStoredMergePolicy, final ObjectNode pDesiredMergePolicy, final Set<String> pChildTablePaths)
	{
		return MatchingPolicyComparator.compareCore(pStoredMatchingPolicy, pDesiredMatchingPolicy, pDesiredMergePolicyCode, pStoredMergePolicy, pDesiredMergePolicy,
			pChildTablePaths);
	}

	private static Diff compareCore(final JsonNode pStoredMatchingPolicy, final ObjectNode pDesiredMatchingPolicy, final String pDesiredMergePolicyCode,
		final JsonNode pStoredMergePolicy, final ObjectNode pDesiredMergePolicy, final Set<String> pChildTablePaths)
	{
		final boolean treeChanged = !MatchingPolicyComparator.sameDecisionTree(pStoredMatchingPolicy.path("decisionTree"), pDesiredMatchingPolicy.path("decisionTree"));
		final boolean fieldsChanged = !MatchingPolicyComparator.sameCandidateFields(pStoredMatchingPolicy.path("fields"), pDesiredMatchingPolicy.path("fields"));
		final String storedMergeCode = pStoredMatchingPolicy.path("mergePolicyCode").asText(null);
		final boolean codeChanged = pDesiredMergePolicyCode == null ? storedMergeCode != null : !pDesiredMergePolicyCode.equals(storedMergeCode);
		final boolean chainsChanged = !MatchingPolicyComparator.sameChain(pStoredMergePolicy.path("recordSelectionFunctionChain"),
			pDesiredMergePolicy.path("recordSelectionFunctionChain"))
			|| !MatchingPolicyComparator.sameChain(pStoredMergePolicy.path("defaultFieldMergeFunctionChain"), pDesiredMergePolicy.path("defaultFieldMergeFunctionChain"))
			// lot M1 (2026-10-17): the source a created golden carries is part of the merge policy the plan stands for (a desired policy that names none compares equal)
			|| !MatchingPolicyComparator.sameGoldenSource(pStoredMergePolicy, pDesiredMergePolicy);
		final boolean survivorsChanged = !MatchingPolicyComparator.sameSurvivors(pStoredMergePolicy.path("survivors"), pDesiredMergePolicy.path("survivors"));
		final JsonNode storedRelatedTables = pStoredMergePolicy.path("relatedTables");
		final JsonNode desiredRelatedTables = RelatedTableAlignment.align(storedRelatedTables, pChildTablePaths);
		final boolean relatedTablesChanged = !MatchingPolicyComparator.sameRelatedTables(storedRelatedTables, desiredRelatedTables);
		return new Diff(treeChanged, fieldsChanged, codeChanged, chainsChanged, survivorsChanged, relatedTablesChanged);
	}

	/** Lot M1: {@code autoCreateNewGoldenSource} stored vs desired - an explicit JSON null and an absent key are the same "none"; a desired policy that names none is never a difference. */
	static boolean sameGoldenSource(final JsonNode pStoredMergePolicy, final ObjectNode pDesiredMergePolicy)
	{
		final String desired = MatchingPolicyComparator.text(pDesiredMergePolicy, "autoCreateNewGoldenSource");
		return desired.isEmpty() || desired.equals(MatchingPolicyComparator.text(pStoredMergePolicy, "autoCreateNewGoldenSource"));
	}

	/**
	 * The first entry of {@code pArray} whose {@code pField} equals {@code pValue} - a {@link MissingNode}
	 * (never {@code null}, never throws) when {@code pArray} is not iterable, {@code pValue} is {@code null},
	 * or no entry matches. Same idiom {@code master.MergeConfigPatchPlanner.findMergePolicy} already uses for
	 * the identical lookup (deliberately NOT reused from here - this class stays independent of that one, which
	 * a concurrent lot may still be changing; see the module's own final report).
	 */
	public static JsonNode findPolicyEntry(final JsonNode pArray, final String pField, final String pValue)
	{
		if (pArray != null && pValue != null)
		{
			for (final JsonNode entry : pArray)
			{
				if (pValue.equals(entry.path(pField).asText(null)))
				{
					return entry;
				}
			}
		}
		return MissingNode.getInstance();
	}

	// ------------------------------------------------------------------------------------------------- decision tree

	/**
	 * {@code true} when {@link #normalize}({@code pA}) deep-equals {@link #normalize}({@code pB}) - a general
	 * -purpose recursive JSON structural comparator (null-vs-absent collapsed, an internal decision node's own
	 * cosmetic {@code name} dropped). <b>No longer used internally by {@link #sameDecisionTree}/{@link
	 * #sameChain}</b> since the 2026-09-23 live round-trip fixes (class Javadoc, "read-back gaps"): a plain
	 * deep-equals-after-null-drop is not enough once the add-on's own read-back echoes a NON-null default
	 * value ({@code dateFieldPath: "System date"}) one side never set, re-orders a node's {@code fields[]}, or
	 * stores a param {@code value} as a JSON NUMBER where this class always emits a STRING - none of which
	 * {@code normalize} alone can tell apart from a genuine difference. Kept as a still-useful, still-tested
	 * pure utility (a caller that already knows two documents SHOULD be byte-for-byte identical, order and all,
	 * can still reach for it) - not removed only because nothing about {@link #sameDecisionTree}/{@link
	 * #sameChain} needing MORE tolerance makes this cruder tool wrong, just insufficient for THIS class' own
	 * two hardest comparisons. Package-visible: unit-tested directly.
	 */
	static boolean sameNormalized(final JsonNode pA, final JsonNode pB)
	{
		return MatchingPolicyComparator.normalize(pA).equals(MatchingPolicyComparator.normalize(pB));
	}

	/**
	 * Recursively rebuilds {@code pNode} with every {@code null}-valued key DROPPED (so a {@code null} and an
	 * absent key always compare equal) and, on any OBJECT that carries a non-null {@code function} key (i.e. an
	 * INTERNAL decision-tree node, never a leaf - {@code DecisionTreePlan#nestedLeaf} always writes a leaf's
	 * {@code function} as an explicit {@code null}), its own {@code name} key dropped too (a cosmetic display
	 * label, never part of the matching outcome - see class Javadoc). Arrays are rebuilt element-wise, order
	 * preserved. A scalar is returned as-is.
	 * <p>
	 * Package-visible (not {@code private}): unit-tested directly.
	 */
	static JsonNode normalize(final JsonNode pNode)
	{
		if (pNode == null || pNode.isMissingNode())
		{
			return MissingNode.getInstance();
		}
		if (pNode.isArray())
		{
			final ArrayNode out = MatchingPolicyComparator.MAPPER.createArrayNode();
			for (final JsonNode item : pNode)
			{
				out.add(MatchingPolicyComparator.normalize(item));
			}
			return out;
		}
		if (pNode.isObject())
		{
			final ObjectNode out = MatchingPolicyComparator.MAPPER.createObjectNode();
			final boolean internalDecisionNode = pNode.hasNonNull("function");
			final Iterator<Map.Entry<String, JsonNode>> fields = pNode.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> entry = fields.next();
				final JsonNode value = entry.getValue();
				if (value == null || value.isNull())
				{
					continue;
				}
				if (internalDecisionNode && "name".equals(entry.getKey()))
				{
					continue;
				}
				out.set(entry.getKey(), MatchingPolicyComparator.normalize(value));
			}
			return out;
		}
		return pNode;
	}

	/**
	 * <b>2026-09-23, live round-trip gaps (coordinator, PipelineStep 128) - {@code sameNormalized} alone was
	 * not enough for a decision tree.</b> Walks {@code pStored}/{@code pDesired} in PARALLEL (never
	 * independently-normalize-then-deep-equals, unlike {@link #sameNormalized}) so each of the following can be
	 * tolerated exactly where it actually occurs, never papered over everywhere:
	 * <ul>
	 * <li>a LEAF's outcome {@code name} is compared through {@link #canonicalLeafOutcome} - the add-on's own
	 * read-back uses DISPLAY strings ({@code "Match"}/{@code "Suspect"}/{@code "Not Match"}), never the
	 * documented enum tokens this class emits ({@code MATCHED}/{@code SUSPECTED}/{@code NOT_MATCHED}) - see
	 * {@code MatchingPolicyTranslator}'s own class Javadoc "a genuine, unresolved discrepancy" for why both
	 * forms exist at all; an INTERNAL node's own {@code function} and {@code name} label are handled exactly as
	 * {@link #normalize} already did (the label is never compared).</li>
	 * <li>an internal node's own {@code params[]} (node-level threshold params: {@code minimumScore}/{@code
	 * minimumNumberOfMatches}) are compared through {@link #sameParams} - a SET keyed by {@code name}, values
	 * compared NUMERICALLY where possible (the add-on may store a param {@code value} as a JSON number where
	 * this class always emits a STRING - {@code DecisionTreePlan#addParam}).</li>
	 * <li>an internal node's own {@code fields[]} (the fields THIS node compares - distinct from {@code
	 * matchingPolicy.fields[]}, the candidate gate {@link #sameCandidateFields} already handles) are compared
	 * through {@link #sameTreeFieldSet} - a SET keyed by {@code field} path (order may differ from ours), each
	 * entry's own {@code algorithm} compared exactly, {@code bothNullValueHandling}/{@code oneNullValueHandling}
	 * defaulted to {@code NOT_MATCH} when absent (the SAME default {@code DecisionTreePlan#nestedField} itself
	 * falls back to when unset - never {@code DO_NOTHING}, the DIFFERENT default the top-level candidate-gate
	 * field uses), and its own {@code params[]} compared the same numeric-tolerant way.</li>
	 * </ul>
	 * A missing node on one side only (a {@link MissingNode}/{@code null}) compares equal only when BOTH sides
	 * are missing - never silently "no tree at all" treated as matching a real tree.
	 * <p>
	 * Package-visible (not {@code private}): unit-tested directly, and against the exact live {@code Company}
	 * pair (`docs/MATCHING.md` &sect;19.11).
	 */
	static boolean sameDecisionTree(final JsonNode pStored, final JsonNode pDesired)
	{
		final boolean storedMissing = pStored == null || pStored.isMissingNode();
		final boolean desiredMissing = pDesired == null || pDesired.isMissingNode();
		if (storedMissing || desiredMissing)
		{
			return storedMissing && desiredMissing;
		}
		final boolean storedLeaf = !pStored.hasNonNull("function");
		final boolean desiredLeaf = !pDesired.hasNonNull("function");
		if (storedLeaf != desiredLeaf)
		{
			return false;
		}
		if (storedLeaf)
		{
			return java.util.Objects.equals(MatchingPolicyComparator.canonicalLeafOutcome(pStored.path("name").asText(null)),
				MatchingPolicyComparator.canonicalLeafOutcome(pDesired.path("name").asText(null)));
		}
		return java.util.Objects.equals(pStored.path("function").asText(null), pDesired.path("function").asText(null))
			&& MatchingPolicyComparator.sameParams(pStored.path("params"), pDesired.path("params"))
			&& MatchingPolicyComparator.sameTreeFieldSet(pStored.path("fields"), pDesired.path("fields"))
			&& MatchingPolicyComparator.sameDecisionTree(pStored.path("trueNode"), pDesired.path("trueNode"))
			&& MatchingPolicyComparator.sameDecisionTree(pStored.path("falseNode"), pDesired.path("falseNode"));
	}

	/**
	 * Maps EITHER the documented nested-shape enum token ({@code MATCHED}/{@code SUSPECTED}/{@code
	 * NOT_MATCHED}, what {@code DecisionTreePlan#nestedLeaf} writes) OR the add-on's own live DISPLAY string
	 * ({@code "Match"}/{@code "Suspect"}/{@code "Not Match"}, what a real {@code get_matching_config} read-back
	 * actually carries - `docs/MATCHING.md`, `MatchingPolicyTranslator`'s own "genuine, unresolved discrepancy"
	 * note) onto the SAME canonical token, case-insensitively. An unrecognised value (or {@code null}) is
	 * returned trimmed/verbatim (or {@code null}) - compared literally, never guessed at, so a truly unexpected
	 * leaf name still shows up as a real difference rather than being silently swallowed.
	 */
	static String canonicalLeafOutcome(final String pRawName)
	{
		if (pRawName == null)
		{
			return null;
		}
		final String trimmed = pRawName.trim();
		if ("MATCHED".equalsIgnoreCase(trimmed) || "Match".equalsIgnoreCase(trimmed))
		{
			return "MATCHED";
		}
		if ("SUSPECTED".equalsIgnoreCase(trimmed) || "Suspect".equalsIgnoreCase(trimmed))
		{
			return "SUSPECTED";
		}
		if ("NOT_MATCHED".equalsIgnoreCase(trimmed) || "Not Match".equalsIgnoreCase(trimmed))
		{
			return "NOT_MATCHED";
		}
		return trimmed;
	}

	/**
	 * {@code true} when {@code pStoredFields}/{@code pDesiredFields} (a decision-tree NODE's own {@code
	 * fields[]} - the fields THIS node compares, never {@code matchingPolicy.fields[]} itself, see {@link
	 * #sameCandidateFields}) carry the SAME set of field paths, each with the same {@code algorithm}, null
	 * -handling (defaulted to {@code NOT_MATCH} - {@code DecisionTreePlan#nestedField}'s own default, NOT
	 * {@link #sameCandidateFields}'s {@code DO_NOTHING}), and {@code params[]} (compared via {@link
	 * #sameParams}). Order-insensitive across fields - 2026-09-23 live finding (class Javadoc): a stored
	 * answer's node-level {@code fields[]} may list the same fields in a different order than this class
	 * builds them in.
	 */
	private static boolean sameTreeFieldSet(final JsonNode pStoredFields, final JsonNode pDesiredFields)
	{
		final Map<String, JsonNode> stored = MatchingPolicyComparator.indexByFieldPath(pStoredFields);
		final Map<String, JsonNode> desired = MatchingPolicyComparator.indexByFieldPath(pDesiredFields);
		if (!stored.keySet().equals(desired.keySet()))
		{
			return false;
		}
		for (final Map.Entry<String, JsonNode> entry : stored.entrySet())
		{
			final JsonNode a = entry.getValue();
			final JsonNode b = desired.get(entry.getKey());
			if (!java.util.Objects.equals(a.path("algorithm").asText(null), b.path("algorithm").asText(null))
				|| !MatchingPolicyComparator.treeNullHandling(a, "bothNullValueHandling").equals(MatchingPolicyComparator.treeNullHandling(b, "bothNullValueHandling"))
				|| !MatchingPolicyComparator.treeNullHandling(a, "oneNullValueHandling").equals(MatchingPolicyComparator.treeNullHandling(b, "oneNullValueHandling"))
				|| !MatchingPolicyComparator.sameParams(a.path("params"), b.path("params")))
			{
				return false;
			}
		}
		return true;
	}


	/**
	 * A decision-tree field's null-value handling, canonical: absent, blank or {@code NOT_MATCH} read {@code NOT_MATCH}, and so does {@code DO_NOTHING} - the
	 * add-on build 6.3.0.5-RC3 refuses {@code DO_NOTHING} on a tree field ("reserved for matching-policy fields", `docs/MATCHING.md` &sect;33.12) and the
	 * configurator translates it to {@code NOT_MATCH} before it writes, so a plan still holding {@code DO_NOTHING} must not read as a difference from the
	 * {@code NOT_MATCH} the add-on stores. {@code MATCH} stays {@code MATCH}.
	 */
	static String treeNullHandling(final JsonNode pTreeField, final String pName)
	{
		final String value = MatchingPolicyComparator.text(pTreeField, pName).trim();
		return value.isEmpty() || "DO_NOTHING".equals(value) ? "NOT_MATCH" : value;
	}

	private static Map<String, JsonNode> indexByFieldPath(final JsonNode pFields)
	{
		final Map<String, JsonNode> out = new LinkedHashMap<String, JsonNode>();
		for (final JsonNode field : pFields)
		{
			final String path = field.path("field").asText(null);
			if (path != null && !path.trim().isEmpty())
			{
				out.put(path, field);
			}
		}
		return out;
	}

	/**
	 * {@code true} when every entry of {@code pDesiredParams} ({@code {name, value}[]}, a decision-tree node's
	 * OR field's own params) is also present in {@code pStoredParams} with the SAME value, compared NUMERICALLY
	 * when both sides parse as a number - 2026-09-23 live finding (class Javadoc): the add-on's own read-back
	 * may store a param {@code value} as a JSON NUMBER ({@code 80}) where {@code DecisionTreePlan#addParam}
	 * always emits a STRING ({@code "80"}); a plain {@code JsonNode#equals} would see a {@code TextNode}/{@code
	 * IntNode} pair as different even though they mean the same threshold. Falls back to plain string equality
	 * when either side does not parse as a number (e.g. a {@code predicate} param's own expression text).
	 * Order-insensitive (a param set has no meaningful order of its own).
	 * <p>
	 * <b>Asymmetric on purpose since the SECOND live gap, 2026-09-23 13:30</b> (`docs/MATCHING.md` &sect;26,
	 * Contact table, `matching_config_schema`-documented algorithm defaults): a jarowinkler field's own {@code
	 * params[]} comes back from {@code get_matching_config} carrying {@code {"name":"jaroWinklerThreshold",
	 * "value":0.7}} even though {@code MatchingPolicyTranslator} never sends that param at all - the add-on
	 * fills in the algorithm's own default. The OLD rule ({@code stored.keySet().equals(desired.keySet())})
	 * read this as a spurious difference on EVERY jarowinkler field, forever (a re-{@code configure} of an
	 * otherwise byte-for-byte-unchanged plan would never resolve to {@code RESUME_SAME}). A param present in
	 * STORED but absent from DESIRED is now an add-on-filled default and IGNORED; a param present in DESIRED but
	 * absent from STORED (this class asked for something the stored answer does not carry at all) is still a
	 * real difference; a param present on both sides with a different value is still a real difference either
	 * way - only the "stored has more keys than desired" direction changed. Likely not specific to
	 * {@code jaroWinklerThreshold}/jarowinkler - other algorithms probably have their own undocumented defaults
	 * the same way; this rule change covers all of them without needing to enumerate which.
	 */
	static boolean sameParams(final JsonNode pStoredParams, final JsonNode pDesiredParams)
	{
		final Map<String, String> stored = MatchingPolicyComparator.indexParams(pStoredParams);
		final Map<String, String> desired = MatchingPolicyComparator.indexParams(pDesiredParams);
		for (final Map.Entry<String, String> entry : desired.entrySet())
		{
			if (!stored.containsKey(entry.getKey()) || !MatchingPolicyComparator.sameParamValue(stored.get(entry.getKey()), entry.getValue()))
			{
				return false;
			}
		}
		return true;
	}

	static Map<String, String> indexParams(final JsonNode pParams)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (final JsonNode param : pParams)
		{
			final String name = param.path("name").asText(null);
			if (name != null && !name.trim().isEmpty())
			{
				out.put(name, MatchingPolicyComparator.text(param, "value"));
			}
		}
		return out;
	}

	static boolean sameParamValue(final String pA, final String pB)
	{
		if (java.util.Objects.equals(pA, pB))
		{
			return true;
		}
		if (pA == null || pB == null)
		{
			return false;
		}
		try
		{
			return Double.parseDouble(pA) == Double.parseDouble(pB);
		}
		catch (final NumberFormatException ex)
		{
			return false;
		}
	}

	// ---------------------------------------------------------------------------------------------- candidate fields

	/**
	 * {@code true} when {@code pStoredFields}/{@code pDesiredFields} ({@code matchingPolicies[].fields[]})
	 * carry the SAME set of field paths, each with the same canonical {@code {weight, searchStrategy,
	 * bothNullValueHandling, oneNullValueHandling}} - order-insensitive (unlike the decision tree itself: this
	 * array is a candidate GATE, not a sequence). Package-visible: unit-tested directly.
	 */
	static boolean sameCandidateFields(final JsonNode pStoredFields, final JsonNode pDesiredFields)
	{
		final Map<String, ObjectNode> stored = MatchingPolicyComparator.indexCandidateFields(pStoredFields);
		final Map<String, ObjectNode> desired = MatchingPolicyComparator.indexCandidateFields(pDesiredFields);
		if (!stored.keySet().equals(desired.keySet()))
		{
			return false;
		}
		for (final Map.Entry<String, ObjectNode> entry : stored.entrySet())
		{
			if (!entry.getValue().equals(desired.get(entry.getKey())))
			{
				return false;
			}
		}
		return true;
	}

	/** Defaults mirror {@code MatchingPolicyTranslator.buildMatchingPolicy}'s own ({@code weight} 1, {@code searchStrategy} {@code @default}, both null-handling {@code DO_NOTHING}) so a key either side omits is never mistaken for a real difference. */
	private static Map<String, ObjectNode> indexCandidateFields(final JsonNode pFields)
	{
		final Map<String, ObjectNode> out = new LinkedHashMap<String, ObjectNode>();
		for (final JsonNode field : pFields)
		{
			// a native relation field (add-on 6.3.0.5-RC3) reads back with field = null and businessObjectField = the absolute path of the member table's field
			String path = field.path("field").asText(null);
			if (path == null || path.trim().isEmpty())
			{
				path = field.path("businessObjectField").asText(null);
			}
			if (path == null || path.trim().isEmpty())
			{
				continue;
			}
			final ObjectNode canonical = MatchingPolicyComparator.MAPPER.createObjectNode();
			canonical.put("weight", field.path("weight").asInt(1));
			canonical.put("searchStrategy", field.path("searchStrategy").asText("@default"));
			canonical.put("bothNullValueHandling", field.path("bothNullValueHandling").asText("DO_NOTHING"));
			canonical.put("oneNullValueHandling", field.path("oneNullValueHandling").asText("DO_NOTHING"));
			out.put(path, canonical);
		}
		return out;
	}

	// --------------------------------------------------------------------------------------------------- survivors

	/**
	 * {@code true} when {@code pStoredSurvivors}/{@code pDesiredSurvivors} ({@code mergePolicies[].survivors[]})
	 * carry the SAME set of field paths, each with the same {@code mergeFunctionChain} (compared WITH {@link
	 * #normalize}, so order WITHIN one field's own chain is significant, but the ORDER OF FIELDS in the array
	 * is not - a stored answer may list survivors in a different order than {@code
	 * MatchingPolicyTranslator.buildMergePolicy} builds them in). Package-visible: unit-tested directly.
	 */
	static boolean sameSurvivors(final JsonNode pStoredSurvivors, final JsonNode pDesiredSurvivors)
	{
		final Map<String, JsonNode> stored = MatchingPolicyComparator.indexSurvivors(pStoredSurvivors);
		final Map<String, JsonNode> desired = MatchingPolicyComparator.indexSurvivors(pDesiredSurvivors);
		if (!stored.keySet().equals(desired.keySet()))
		{
			return false;
		}
		for (final Map.Entry<String, JsonNode> entry : stored.entrySet())
		{
			if (!MatchingPolicyComparator.sameChain(entry.getValue(), desired.get(entry.getKey())))
			{
				return false;
			}
		}
		return true;
	}

	private static Map<String, JsonNode> indexSurvivors(final JsonNode pSurvivors)
	{
		final Map<String, JsonNode> out = new LinkedHashMap<String, JsonNode>();
		for (final JsonNode survivor : pSurvivors)
		{
			final String field = survivor.path("field").asText(null);
			if (field != null && !field.trim().isEmpty())
			{
				out.put(field, survivor.path("mergeFunctionChain"));
			}
		}
		return out;
	}

	// -------------------------------------------------------------------------------------------------------- chains

	/**
	 * {@code true} when {@code pStoredChain}/{@code pDesiredChain} ({@code recordSelectionFunctionChain}/
	 * {@code defaultFieldMergeFunctionChain}/one survivor's own {@code mergeFunctionChain}) carry the SAME
	 * entries IN THE SAME ORDER (a chain's own fallback semantics assign PRIORITY to position - unlike {@link
	 * #sameTreeFieldSet}/{@link #sameCandidateFields}, never a set), each entry compared through {@link
	 * #normalizeChainEntry} first so the 2026-09-23 live round-trip gap (class Javadoc "read-back gaps",
	 * coordinator, PipelineStep 128) does not read as a difference. Package-visible: unit-tested directly.
	 */
	static boolean sameChain(final JsonNode pStoredChain, final JsonNode pDesiredChain)
	{
		final List<JsonNode> stored = MatchingPolicyComparator.normalizeChainEntries(pStoredChain);
		final List<JsonNode> desired = MatchingPolicyComparator.normalizeChainEntries(pDesiredChain);
		return stored.equals(desired);
	}

	private static List<JsonNode> normalizeChainEntries(final JsonNode pChain)
	{
		final List<JsonNode> out = new ArrayList<JsonNode>();
		for (final JsonNode entry : pChain)
		{
			out.add(MatchingPolicyComparator.normalizeChainEntry(entry));
		}
		return out;
	}

	/**
	 * Canonicalises ONE chain entry ({@code {function, constantValue?, dateFieldPath?}}, `docs/MATCHING.md`
	 * &sect;14.3/&sect;19.11): {@code function} kept verbatim (an i18n key or a literal enum string, echoed
	 * back byte-for-byte by the add-on - never normalized); {@code constantValue} kept only when non-null/
	 * non-blank (an explicit JSON {@code null}, what a plain entry's read-back actually carries - `docs/
	 * MATCHING.md` &sect;14.2 - is dropped, same as an absent key); {@code dateFieldPath} ALWAYS backfilled to
	 * the add-on's own default {@code "System date"} when absent, blank, or explicit {@code null} - measured
	 * live (coordinator, 2026-09-23, and independently confirmed the same day by {@code
	 * master.MergeConfigPatchPlanner}'s own {@code normalizedDateFieldPath}): a date-aware entry (e.g. {@code
	 * lastUpdate}) this class emits with NO {@code dateFieldPath} at all comes back from the add-on carrying
	 * {@code "System date"} explicitly, and every OTHER entry comes back carrying {@code dateFieldPath: null}
	 * - backfilling the SAME default on both sides, unconditionally, makes both read as the identical "using
	 * the default" state, while a genuinely different, non-default {@code dateFieldPath} on either side still
	 * compares as a real difference against the backfilled default.
	 */
	private static ObjectNode normalizeChainEntry(final JsonNode pEntry)
	{
		final ObjectNode out = MatchingPolicyComparator.MAPPER.createObjectNode();
		out.put("function", MatchingPolicyComparator.text(pEntry, "function"));
		final String constantValue = MatchingPolicyComparator.text(pEntry, "constantValue");
		if (!constantValue.isEmpty())
		{
			out.put("constantValue", constantValue);
		}
		final String dateFieldPath = MatchingPolicyComparator.text(pEntry, "dateFieldPath");
		out.put("dateFieldPath", dateFieldPath.isEmpty() || "System date".equals(dateFieldPath) ? "System date" : dateFieldPath);
		return out;
	}

	/**
	 * {@code pField}'s text value off {@code pNode}, normalizing BOTH an absent field and an EXPLICIT JSON
	 * {@code null} to {@code ""} - the same idiom {@code master.MergeConfigPatchPlanner#text} already
	 * documents in full (independently found the same day, 2026-09-23): {@code NullNode#asText()} returns the
	 * four-character STRING {@code "null"}, never actual Java {@code null}, so the one-argument {@code
	 * asText(String)} fallback never fires for an explicit JSON null the way it does for a {@link
	 * MissingNode}. A separate copy on purpose (same discipline as {@link #findPolicyEntry}'s own Javadoc:
	 * this class stays independent of a file a concurrent lot may still be changing).
	 */
	static String text(final JsonNode pNode, final String pField)
	{
		final JsonNode value = pNode.path(pField);
		return value.isMissingNode() || value.isNull() ? "" : value.asText("");
	}

	// ---------------------------------------------------------------------------------------------- relatedTables

	/**
	 * {@code true} when every {@code path} present in BOTH {@code pStoredRelatedTables} and {@code
	 * pDesiredRelatedTables} carries the SAME {@code alignForeignKeys} value. Deliberately NOT a set-equality
	 * check like {@link #sameCandidateFields}/{@link #sameSurvivors}: a {@code path} present on only one side -
	 * in practice always {@code generate_merge_policy}'s own inverse-FK auto-additions the desired side has not
	 * (yet) been told about - is SKIPPED, never counted as a difference (class Javadoc "relatedTables
	 * alignment"; {@code RelatedTableAlignment} never adds or removes an entry, only realigns one already
	 * present on both sides). Package-visible: unit-tested directly.
	 */
	static boolean sameRelatedTables(final JsonNode pStoredRelatedTables, final JsonNode pDesiredRelatedTables)
	{
		final Map<String, String> stored = MatchingPolicyComparator.indexRelatedTables(pStoredRelatedTables);
		final Map<String, String> desired = MatchingPolicyComparator.indexRelatedTables(pDesiredRelatedTables);
		for (final Map.Entry<String, String> entry : stored.entrySet())
		{
			final String desiredValue = desired.get(entry.getKey());
			if (desiredValue != null && !java.util.Objects.equals(entry.getValue(), desiredValue))
			{
				return false;
			}
		}
		return true;
	}

	private static Map<String, String> indexRelatedTables(final JsonNode pRelatedTables)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pRelatedTables != null && pRelatedTables.isArray())
		{
			for (final JsonNode entry : pRelatedTables)
			{
				final String path = entry.path("path").asText(null);
				if (path != null && !path.trim().isEmpty())
				{
					out.put(path, entry.path("alignForeignKeys").asText(null));
				}
			}
		}
		return out;
	}
}
