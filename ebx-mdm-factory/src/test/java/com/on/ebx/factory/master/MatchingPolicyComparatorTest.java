package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free: run the main, a failure throws (same convention as every other test in this codebase - see
 * {@code ebx.MatchingConfiguratorTest}'s own class Javadoc).
 * <p>
 * {@link #compare_liveRun31Pair_differsOnEverythingButMergePolicyCode} builds BOTH sides through the real
 * {@link MatchingPolicyTranslator} (never hand-typed JSON pretending to be its output) from the exact live
 * pair the compare-then-rewrite task brief describes, 2026-09-22 23:39 (`docs/MATCHING.md` &sect;19 "the
 * gap"): the stored {@code Company} policy (AVERAGE &ge;80 on {@code name} fuzzyFullText weight 10 / {@code
 * country} exact weight 5 / {@code cityKey} exact weight 5, {@code clusteringFields:["country"]}) against
 * accepted plan run 31 (one AVERAGE &ge;80 node on {@code name} fuzzyFullText weight 3 ONLY, {@code
 * clusteringFields:["nameKey"]}, survivorship {@code name} -&gt; most trusted source, record selection {@code
 * lastUpdate}, default field merge {@code longest}).
 */
public final class MatchingPolicyComparatorTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchingPolicyComparatorTest.findPolicyEntry_found();
		MatchingPolicyComparatorTest.findPolicyEntry_notFound_missingNode();
		MatchingPolicyComparatorTest.findPolicyEntry_nullValue_missingNode();
		MatchingPolicyComparatorTest.normalize_dropsNullValuedKeys();
		MatchingPolicyComparatorTest.normalize_dropsLabelOnInternalNodeOnly_keepsLeafName();
		MatchingPolicyComparatorTest.sameNormalized_identicalArrays_true();
		MatchingPolicyComparatorTest.sameNormalized_differentArrays_false();
		MatchingPolicyComparatorTest.sameCandidateFields_orderInsensitive_true();
		MatchingPolicyComparatorTest.sameCandidateFields_weightDiffers_false();
		MatchingPolicyComparatorTest.sameCandidateFields_missingField_false();
		MatchingPolicyComparatorTest.sameSurvivors_orderInsensitiveAcrossFields_true();
		MatchingPolicyComparatorTest.sameSurvivors_chainOrderWithinFieldMatters_false();
		MatchingPolicyComparatorTest.goldenSource_storedWithoutIt_isADifference();
		MatchingPolicyComparatorTest.compare_identicalTranslatorOutput_same();
		MatchingPolicyComparatorTest.compare_liveRun31Pair_differsOnEverythingButMergePolicyCode();
		MatchingPolicyComparatorTest.compare_relationField_readBackShape_same();
		MatchingPolicyComparatorTest.compare_relationField_changedChildField_differsInTreeAndGate();
		MatchingPolicyComparatorTest.compare_relationField_changedWeight_differsInGateOnly();
		MatchingPolicyComparatorTest.compare_matchingOnCreationUpdateDifference_neverCountedAsDrift();
		MatchingPolicyComparatorTest.sameRelatedTables_childFlippedToAutomatic_differs();
		MatchingPolicyComparatorTest.sameRelatedTables_nonChildStaysManual_same();
		MatchingPolicyComparatorTest.sameRelatedTables_alreadyAutomatic_same();
		MatchingPolicyComparatorTest.sameRelatedTables_extraStoredEntry_neverCountedAsDiff();
		MatchingPolicyComparatorTest.sameRelatedTables_extraDesiredEntry_neverCountedAsDiff();
		MatchingPolicyComparatorTest.compare_relatedTablesAlignmentSurfacesAsDiff();
		MatchingPolicyComparatorTest.sameDecisionTree_toleratesDisplayLeafNames();
		MatchingPolicyComparatorTest.sameDecisionTree_toleratesNumericParamValues();
		MatchingPolicyComparatorTest.sameDecisionTree_fieldsOrderInsensitive_andNullHandlingDefaultsToNotMatch();
		MatchingPolicyComparatorTest.sameDecisionTree_aRealDifferenceIsStillCaught();
		MatchingPolicyComparatorTest.sameChain_toleratesDateFieldPathDefaultAndExplicitNulls();
		MatchingPolicyComparatorTest.sameChain_aRealDifferenceIsStillCaught();
		MatchingPolicyComparatorTest.compare_liveStoredPolicyReadBack_matchesRun31Plan_same();
		MatchingPolicyComparatorTest.sameDecisionTree_storedExtraParam_isAddonDefault_same();
		MatchingPolicyComparatorTest.sameDecisionTree_desiredExtraParam_notInStored_differs();
		MatchingPolicyComparatorTest.sameDecisionTree_sharedParamDifferentValue_stillDiffers();
		System.out.println("MatchingPolicyComparatorTest OK");
	}

	// ------------------------------------------------------------------------------------------------ findPolicyEntry

	private static void findPolicyEntry_found() throws Exception
	{
		final JsonNode array = MatchingPolicyComparatorTest.parse("[{\"code\":\"A\",\"x\":1},{\"code\":\"B\",\"x\":2}]");
		final JsonNode found = MatchingPolicyComparator.findPolicyEntry(array, "code", "B");
		MatchingPolicyComparatorTest.check("finds the entry by field", found.path("x").asInt() == 2);
	}

	private static void findPolicyEntry_notFound_missingNode() throws Exception
	{
		final JsonNode array = MatchingPolicyComparatorTest.parse("[{\"code\":\"A\"}]");
		MatchingPolicyComparatorTest.check("no match: MissingNode, never null", MatchingPolicyComparator.findPolicyEntry(array, "code", "Z") instanceof MissingNode);
	}

	private static void findPolicyEntry_nullValue_missingNode()
	{
		MatchingPolicyComparatorTest.check("null value: MissingNode, never throws",
			MatchingPolicyComparator.findPolicyEntry(MatchingPolicyComparatorTest.MAPPER.createArrayNode(), "code", null) instanceof MissingNode);
	}

	// ---------------------------------------------------------------------------------------------------- normalize

	private static void normalize_dropsNullValuedKeys() throws Exception
	{
		final JsonNode withNull = MatchingPolicyComparatorTest.parse("{\"a\":1,\"b\":null}");
		final JsonNode withoutKey = MatchingPolicyComparatorTest.parse("{\"a\":1}");
		MatchingPolicyComparatorTest.check("a null-valued key compares equal to the same key being absent",
			MatchingPolicyComparator.normalize(withNull).equals(MatchingPolicyComparator.normalize(withoutKey)));
	}

	private static void normalize_dropsLabelOnInternalNodeOnly_keepsLeafName() throws Exception
	{
		final JsonNode internalA = MatchingPolicyComparatorTest.parse("{\"name\":\"Custom label A\",\"function\":\"All fields match\",\"fields\":[],\"params\":[]}");
		final JsonNode internalB = MatchingPolicyComparatorTest.parse("{\"name\":\"Custom label B\",\"function\":\"All fields match\",\"fields\":[],\"params\":[]}");
		MatchingPolicyComparatorTest.check("an internal decision node's own display label is cosmetic - ignored",
			MatchingPolicyComparator.normalize(internalA).equals(MatchingPolicyComparator.normalize(internalB)));

		final JsonNode leafMatched = MatchingPolicyComparatorTest.parse("{\"name\":\"MATCHED\",\"function\":null}");
		final JsonNode leafSuspected = MatchingPolicyComparatorTest.parse("{\"name\":\"SUSPECTED\",\"function\":null}");
		MatchingPolicyComparatorTest.check("a leaf's own name IS the outcome - never ignored",
			!MatchingPolicyComparator.normalize(leafMatched).equals(MatchingPolicyComparator.normalize(leafSuspected)));

		final JsonNode leafNoFunctionKeyAtAll = MatchingPolicyComparatorTest.parse("{\"name\":\"MATCHED\"}");
		MatchingPolicyComparatorTest.check("a leaf's function may be explicit null or simply absent - both read the same way",
			MatchingPolicyComparator.normalize(leafMatched).equals(MatchingPolicyComparator.normalize(leafNoFunctionKeyAtAll)));
	}

	private static void sameNormalized_identicalArrays_true() throws Exception
	{
		final JsonNode a = MatchingPolicyComparatorTest.parse("[{\"function\":\"longest\"}]");
		final JsonNode b = MatchingPolicyComparatorTest.parse("[{\"function\":\"longest\"}]");
		MatchingPolicyComparatorTest.check("identical arrays are same", MatchingPolicyComparator.sameNormalized(a, b));
	}

	private static void sameNormalized_differentArrays_false() throws Exception
	{
		final JsonNode a = MatchingPolicyComparatorTest.parse("[{\"function\":\"longest\"}]");
		final JsonNode b = MatchingPolicyComparatorTest.parse("[{\"function\":\"mostCompleted\"}]");
		MatchingPolicyComparatorTest.check("different function values differ", !MatchingPolicyComparator.sameNormalized(a, b));
	}

	// ------------------------------------------------------------------------------------------------ candidateFields

	private static void sameCandidateFields_orderInsensitive_true() throws Exception
	{
		final JsonNode a = MatchingPolicyComparatorTest.parse("[{\"field\":\"/x\",\"weight\":1},{\"field\":\"/y\",\"weight\":2}]");
		final JsonNode b = MatchingPolicyComparatorTest.parse("[{\"field\":\"/y\",\"weight\":2},{\"field\":\"/x\",\"weight\":1}]");
		MatchingPolicyComparatorTest.check("field order does not matter", MatchingPolicyComparator.sameCandidateFields(a, b));
	}

	private static void sameCandidateFields_weightDiffers_false() throws Exception
	{
		final JsonNode a = MatchingPolicyComparatorTest.parse("[{\"field\":\"/x\",\"weight\":1}]");
		final JsonNode b = MatchingPolicyComparatorTest.parse("[{\"field\":\"/x\",\"weight\":9}]");
		MatchingPolicyComparatorTest.check("a changed weight on the same field is a real difference", !MatchingPolicyComparator.sameCandidateFields(a, b));
	}

	private static void sameCandidateFields_missingField_false() throws Exception
	{
		final JsonNode a = MatchingPolicyComparatorTest.parse("[{\"field\":\"/x\",\"weight\":1},{\"field\":\"/y\",\"weight\":1}]");
		final JsonNode b = MatchingPolicyComparatorTest.parse("[{\"field\":\"/x\",\"weight\":1}]");
		MatchingPolicyComparatorTest.check("a dropped candidate field is a real difference", !MatchingPolicyComparator.sameCandidateFields(a, b));
	}

	// ----------------------------------------------------------------------------------------------------- survivors

	private static void sameSurvivors_orderInsensitiveAcrossFields_true() throws Exception
	{
		final JsonNode a = MatchingPolicyComparatorTest.parse(
			"[{\"field\":\"/sourceSystem\",\"mergeFunctionChain\":[{\"function\":\"constant\",\"constantValue\":\"MDM\"}]},"
				+ "{\"field\":\"/sourceId\",\"mergeFunctionChain\":[{\"function\":\"no merge\"}]}]");
		final JsonNode b = MatchingPolicyComparatorTest.parse(
			"[{\"field\":\"/sourceId\",\"mergeFunctionChain\":[{\"function\":\"no merge\"}]},"
				+ "{\"field\":\"/sourceSystem\",\"mergeFunctionChain\":[{\"function\":\"constant\",\"constantValue\":\"MDM\"}]}]");
		MatchingPolicyComparatorTest.check("survivor order across fields does not matter", MatchingPolicyComparator.sameSurvivors(a, b));
	}

	private static void sameSurvivors_chainOrderWithinFieldMatters_false() throws Exception
	{
		final JsonNode a = MatchingPolicyComparatorTest.parse("[{\"field\":\"/x\",\"mergeFunctionChain\":[{\"function\":\"most trusted source\"},{\"function\":\"longest\"}]}]");
		final JsonNode b = MatchingPolicyComparatorTest.parse("[{\"field\":\"/x\",\"mergeFunctionChain\":[{\"function\":\"longest\"},{\"function\":\"most trusted source\"}]}]");
		MatchingPolicyComparatorTest.check("chain order WITHIN one field's own survivor rule is significant", !MatchingPolicyComparator.sameSurvivors(a, b));
	}

	/** Lot M1 (2026-10-17): a table configured before the fix (no autoCreateNewGoldenSource, a constant survivor) is NOT "same" as the plan - the re-configure rewrites it. */
	private static void goldenSource_storedWithoutIt_isADifference() throws Exception
	{
		final com.fasterxml.jackson.databind.node.ObjectNode desired = (com.fasterxml.jackson.databind.node.ObjectNode) MatchingPolicyComparatorTest.parse(
			"{\"code\":\"X\",\"autoCreateNewGoldenSource\":\"MDM\"}");
		MatchingPolicyComparatorTest.check("stored without the golden source: a difference", !MatchingPolicyComparator.sameGoldenSource(MatchingPolicyComparatorTest.parse("{\"code\":\"X\"}"), desired));
		MatchingPolicyComparatorTest.check("stored with an explicit JSON null: a difference",
			!MatchingPolicyComparator.sameGoldenSource(MatchingPolicyComparatorTest.parse("{\"code\":\"X\",\"autoCreateNewGoldenSource\":null}"), desired));
		MatchingPolicyComparatorTest.check("stored with another source: a difference",
			!MatchingPolicyComparator.sameGoldenSource(MatchingPolicyComparatorTest.parse("{\"code\":\"X\",\"autoCreateNewGoldenSource\":\"CRM\"}"), desired));
		MatchingPolicyComparatorTest.check("stored with MDM: same", MatchingPolicyComparator.sameGoldenSource(MatchingPolicyComparatorTest.parse("{\"code\":\"X\",\"autoCreateNewGoldenSource\":\"MDM\"}"), desired));
		MatchingPolicyComparatorTest.check("a desired policy that names none is never a difference",
			MatchingPolicyComparator.sameGoldenSource(MatchingPolicyComparatorTest.parse("{\"code\":\"X\"}"), (com.fasterxml.jackson.databind.node.ObjectNode) MatchingPolicyComparatorTest.parse("{\"code\":\"X\"}")));
	}

	// ------------------------------------------------------------------------------------------------ relatedTables

	/** Cascade lot (2026-09-23, `docs/MATCHING.md` &sect;12.4/&sect;22): the desired side is what {@link RelatedTableAlignment#align} would build - a promoted child's own entry flipped to NONE (lot MT). */
	private static void sameRelatedTables_childFlippedToAutomatic_differs() throws Exception
	{
		final JsonNode stored = MatchingPolicyComparatorTest.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"MANUAL\"}]");
		final Set<String> childTablePaths = Collections.singleton("/root/Contact");
		final JsonNode desired = RelatedTableAlignment.align(stored, childTablePaths);
		MatchingPolicyComparatorTest.check("a stored MANUAL vs a desired NONE on the same path is a real difference",
			!MatchingPolicyComparator.sameRelatedTables(stored, desired));
	}

	private static void sameRelatedTables_nonChildStaysManual_same() throws Exception
	{
		final JsonNode stored = MatchingPolicyComparatorTest.parse("[{\"name\":\"Roles\",\"path\":\"/root/Roles\",\"alignForeignKeys\":\"MANUAL\"}]");
		final JsonNode desired = RelatedTableAlignment.align(stored, Collections.<String>emptySet());
		MatchingPolicyComparatorTest.check("a table that is not a promoted child of this entity is never realigned - same",
			MatchingPolicyComparator.sameRelatedTables(stored, desired));
	}

	private static void sameRelatedTables_alreadyAutomatic_same() throws Exception
	{
		final JsonNode stored = MatchingPolicyComparatorTest.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"NONE\"}]");
		final JsonNode desired = RelatedTableAlignment.align(stored, Collections.singleton("/root/Contact"));
		MatchingPolicyComparatorTest.check("already NONE: aligning again changes nothing - same",
			MatchingPolicyComparator.sameRelatedTables(stored, desired));
		final JsonNode storedAutomatic = MatchingPolicyComparatorTest.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"AUTOMATIC\"}]");
		MatchingPolicyComparatorTest.check("a stored AUTOMATIC (configured before lot MT) differs from the desired NONE - the re-configure rewrites it",
			!MatchingPolicyComparator.sameRelatedTables(storedAutomatic, RelatedTableAlignment.align(storedAutomatic, Collections.singleton("/root/Contact"))));
	}

	/**
	 * Coordinator review, 2026-09-23: an entry present in the STORED list but absent from the DESIRED one -
	 * typically {@code generate_merge_policy}'s own inverse-FK auto-addition the caller's {@code
	 * pChildTablePaths} does not (yet) know about, modelled directly here without going through {@link
	 * RelatedTableAlignment#align} at all, so the "desired never even mentions this path" case is exercised
	 * for real, not just by construction - must NEVER be counted as a difference.
	 */
	private static void sameRelatedTables_extraStoredEntry_neverCountedAsDiff() throws Exception
	{
		final JsonNode stored = MatchingPolicyComparatorTest
			.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"AUTOMATIC\"},"
				+ "{\"name\":\"BrandNewTable\",\"path\":\"/root/BrandNewTable\",\"alignForeignKeys\":\"MANUAL\"}]");
		final JsonNode desired = MatchingPolicyComparatorTest.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"AUTOMATIC\"}]");
		MatchingPolicyComparatorTest.check("a stored-only entry (the add-on's own auto-addition) is never a difference",
			MatchingPolicyComparator.sameRelatedTables(stored, desired));
	}

	/** Same coordinator review - the symmetric case, an entry present in DESIRED only. */
	private static void sameRelatedTables_extraDesiredEntry_neverCountedAsDiff() throws Exception
	{
		final JsonNode stored = MatchingPolicyComparatorTest.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"AUTOMATIC\"}]");
		final JsonNode desired = MatchingPolicyComparatorTest
			.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"AUTOMATIC\"},"
				+ "{\"name\":\"SomethingElse\",\"path\":\"/root/SomethingElse\",\"alignForeignKeys\":\"AUTOMATIC\"}]");
		MatchingPolicyComparatorTest.check("a desired-only entry is never a difference either",
			MatchingPolicyComparator.sameRelatedTables(stored, desired));
	}

	// ------------------------------------------------------------------------------------------------------- compare

	private static void compare_identicalTranslatorOutput_same() throws Exception
	{
		final JsonNode requirements = MatchingPolicyComparatorTest.run31Requirements();
		final ObjectNode matchingA = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, false);
		final ObjectNode mergeA = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, new ArrayList<String>(), new ArrayList<String>());
		final ObjectNode matchingB = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, false);
		final ObjectNode mergeB = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, new ArrayList<String>(), new ArrayList<String>());
		matchingA.put("mergePolicyCode", "Company");
		final MatchingPolicyComparator.Diff diff = MatchingPolicyComparator.compare(matchingA, matchingB, "Company", mergeA, mergeB, Collections.<String>emptySet());
		MatchingPolicyComparatorTest.check("re-translating the SAME requirements twice reports SAME: " + diff.summary(), diff.same());
	}

	private static void compare_liveRun31Pair_differsOnEverythingButMergePolicyCode() throws Exception
	{
		final JsonNode storedRequirements = MatchingPolicyComparatorTest.storedCompanyRequirements();
		final ObjectNode storedMatchingPolicy = MatchingPolicyTranslator.buildMatchingPolicy("Company", storedRequirements, false);
		storedMatchingPolicy.put("mergePolicyCode", "Company");
		final ObjectNode storedMergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", storedRequirements, new ArrayList<String>(), new ArrayList<String>());

		final JsonNode desiredRequirements = MatchingPolicyComparatorTest.run31Requirements();
		final ObjectNode desiredMatchingPolicy = MatchingPolicyTranslator.buildMatchingPolicy("Company", desiredRequirements, false);
		final ObjectNode desiredMergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", desiredRequirements, new ArrayList<String>(), new ArrayList<String>());

		final MatchingPolicyComparator.Diff diff = MatchingPolicyComparator.compare(storedMatchingPolicy, desiredMatchingPolicy, "Company", storedMergePolicy,
			desiredMergePolicy, Collections.<String>emptySet());
		MatchingPolicyComparatorTest.check("the tree shrank from 3 fields to 1 - must be reported changed", diff.decisionTreeChanged);
		MatchingPolicyComparatorTest.check("the candidate-gate fields changed (country/cityKey dropped, nameKey added)", diff.candidateFieldsChanged);
		MatchingPolicyComparatorTest.check("record selection changed (mostCompleted -> lastUpdate, both behind mostTrustedSource)", diff.mergeChainsChanged);
		MatchingPolicyComparatorTest.check("run 31 adds a business survivor rule on name the stored policy never had", diff.survivorsChanged);
		MatchingPolicyComparatorTest.check("both sides link to the SAME merge policy code (Company) - not itself a difference here",
			!diff.mergePolicyCodeChanged);
		MatchingPolicyComparatorTest.check("no child table paths supplied - relatedTables never flagged by this fixture", !diff.relatedTablesChanged);
		MatchingPolicyComparatorTest.check("overall: differs", !diff.same());
	}

	/**
	 * Cascade lot (2026-09-23): the ONLY thing that differs between stored and desired is a promoted child's
	 * own {@code relatedTables} alignment - proves {@link MatchingPolicyComparator#compare} surfaces it as
	 * {@code relatedTablesChanged} (and therefore {@code !diff.same()}), the exact signal {@code
	 * ebx.MatchingConfigurator#decideConfigureAction} turns into a {@code REWRITE}.
	 */
	private static void compare_relatedTablesAlignmentSurfacesAsDiff() throws Exception
	{
		final JsonNode requirements = MatchingPolicyComparatorTest.run31Requirements();
		final ObjectNode matchingPolicy = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, false);
		matchingPolicy.put("mergePolicyCode", "Company");
		final ObjectNode storedMergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, new ArrayList<String>(), new ArrayList<String>());
		storedMergePolicy.set("relatedTables",
			MatchingPolicyComparatorTest.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"MANUAL\"}]"));
		// the desired side is built from the SAME requirements (matchingPolicy re-used for both, mirroring
		// ebx.MatchingConfigurator's own single desiredMatchingPolicy object) - only childTablePaths differs.
		final MatchingPolicyComparator.Diff diffWithoutChild = MatchingPolicyComparator.compare(matchingPolicy, matchingPolicy, "Company", storedMergePolicy,
			storedMergePolicy, Collections.<String>emptySet());
		MatchingPolicyComparatorTest.check("no child table paths known: MANUAL stays MANUAL - same", diffWithoutChild.same());

		final MatchingPolicyComparator.Diff diffWithChild = MatchingPolicyComparator.compare(matchingPolicy, matchingPolicy, "Company", storedMergePolicy,
			storedMergePolicy, Collections.singleton("/root/Contact"));
		MatchingPolicyComparatorTest.check("Contact is a promoted child: MANUAL should become NONE - differs", diffWithChild.relatedTablesChanged);
		MatchingPolicyComparatorTest.check("nothing else about the two sides changed", !diffWithChild.decisionTreeChanged && !diffWithChild.candidateFieldsChanged
			&& !diffWithChild.mergePolicyCodeChanged && !diffWithChild.mergeChainsChanged && !diffWithChild.survivorsChanged);
		MatchingPolicyComparatorTest.check("overall: differs, because of relatedTables alone", !diffWithChild.same());
	}

	// ---------------------------------------------------------------------------------------- sameDecisionTree/sameChain

	/** 2026-09-23 live gap (PipelineStep 128): a leaf's outcome comes back as a DISPLAY string, never our own enum token. */
	private static void sameDecisionTree_toleratesDisplayLeafNames() throws Exception
	{
		final JsonNode ours = MatchingPolicyComparatorTest.parse("{\"name\":\"MATCHED\",\"function\":null}");
		final JsonNode readBack = MatchingPolicyComparatorTest.parse("{\"name\":\"Match\",\"function\":null,\"fields\":[],\"params\":[],\"trueNode\":null,\"falseNode\":null}");
		MatchingPolicyComparatorTest.check("MATCHED == Match", MatchingPolicyComparator.sameDecisionTree(ours, readBack));

		final JsonNode oursSuspect = MatchingPolicyComparatorTest.parse("{\"name\":\"SUSPECTED\",\"function\":null}");
		final JsonNode readBackSuspect = MatchingPolicyComparatorTest.parse("{\"name\":\"Suspect\",\"function\":null}");
		MatchingPolicyComparatorTest.check("SUSPECTED == Suspect", MatchingPolicyComparator.sameDecisionTree(oursSuspect, readBackSuspect));

		final JsonNode oursNotMatched = MatchingPolicyComparatorTest.parse("{\"name\":\"NOT_MATCHED\",\"function\":null}");
		final JsonNode readBackNotMatch = MatchingPolicyComparatorTest.parse("{\"name\":\"Not Match\",\"function\":null}");
		MatchingPolicyComparatorTest.check("NOT_MATCHED == Not Match", MatchingPolicyComparator.sameDecisionTree(oursNotMatched, readBackNotMatch));

		MatchingPolicyComparatorTest.check("but a genuinely DIFFERENT outcome is still caught",
			!MatchingPolicyComparator.sameDecisionTree(ours, readBackSuspect));
	}

	/** 2026-09-23 live gap: a param value may come back as a JSON NUMBER where this class always emits a STRING. */
	private static void sameDecisionTree_toleratesNumericParamValues() throws Exception
	{
		final JsonNode ours = MatchingPolicyComparatorTest.parse("{\"name\":\"n\",\"function\":\"Average\",\"params\":[{\"name\":\"minimumScore\",\"value\":\"80\"}],"
			+ "\"fields\":[{\"field\":\"/name\",\"algorithm\":\"fuzzyFullText\",\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\","
			+ "\"params\":[{\"name\":\"weight\",\"value\":\"3\"}]}],\"trueNode\":{\"name\":\"Match\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}");
		final JsonNode readBack = MatchingPolicyComparatorTest.parse("{\"name\":\"n\",\"function\":\"Average\",\"params\":[{\"name\":\"minimumScore\",\"value\":80}],"
			+ "\"fields\":[{\"field\":\"/name\",\"algorithm\":\"fuzzyFullText\",\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\","
			+ "\"params\":[{\"name\":\"weight\",\"value\":3}]}],\"trueNode\":{\"name\":\"Match\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}");
		MatchingPolicyComparatorTest.check("\"80\"/\"3\" (string) == 80/3 (number)", MatchingPolicyComparator.sameDecisionTree(ours, readBack));
	}

	/** 2026-09-23 live gap: a node's own {@code fields[]} may come back in a different order, and a null-handling key equal to its own default (NOT_MATCH) may be omitted entirely. */
	private static void sameDecisionTree_fieldsOrderInsensitive_andNullHandlingDefaultsToNotMatch() throws Exception
	{
		final JsonNode ours = MatchingPolicyComparatorTest.parse("{\"name\":\"n\",\"function\":\"All fields match\",\"params\":[],\"fields\":["
			+ "{\"field\":\"/country\",\"algorithm\":\"exact\",\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\",\"params\":[{\"name\":\"minimumScore\",\"value\":\"100\"}]},"
			+ "{\"field\":\"/city\",\"algorithm\":\"fuzzyFullText\",\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\",\"params\":[{\"name\":\"minimumScore\",\"value\":\"100\"}]}],"
			+ "\"trueNode\":{\"name\":\"Match\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}");
		// same two fields, REVERSED order, and both null-handling keys OMITTED entirely (their own default).
		final JsonNode readBack = MatchingPolicyComparatorTest.parse("{\"name\":\"n\",\"function\":\"All fields match\",\"params\":[],\"fields\":["
			+ "{\"field\":\"/city\",\"algorithm\":\"fuzzyFullText\",\"params\":[{\"name\":\"minimumScore\",\"value\":100}]},"
			+ "{\"field\":\"/country\",\"algorithm\":\"exact\",\"params\":[{\"name\":\"minimumScore\",\"value\":100}]}],"
			+ "\"trueNode\":{\"name\":\"Match\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}");
		MatchingPolicyComparatorTest.check("reordered fields + omitted (default) null-handling: still same", MatchingPolicyComparator.sameDecisionTree(ours, readBack));
	}

	private static void sameDecisionTree_aRealDifferenceIsStillCaught() throws Exception
	{
		final JsonNode ours = MatchingPolicyComparatorTest.parse("{\"name\":\"n\",\"function\":\"Average\",\"params\":[{\"name\":\"minimumScore\",\"value\":\"80\"}],"
			+ "\"fields\":[{\"field\":\"/name\",\"algorithm\":\"fuzzyFullText\",\"params\":[{\"name\":\"weight\",\"value\":\"3\"}]}],"
			+ "\"trueNode\":{\"name\":\"Match\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}");
		final JsonNode differentWeight = MatchingPolicyComparatorTest.parse("{\"name\":\"n\",\"function\":\"Average\",\"params\":[{\"name\":\"minimumScore\",\"value\":80}],"
			+ "\"fields\":[{\"field\":\"/name\",\"algorithm\":\"fuzzyFullText\",\"params\":[{\"name\":\"weight\",\"value\":10}]}],"
			+ "\"trueNode\":{\"name\":\"Match\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}");
		MatchingPolicyComparatorTest.check("a genuinely different field weight is still caught, tolerance is not blanket",
			!MatchingPolicyComparator.sameDecisionTree(ours, differentWeight));
	}

	/** 2026-09-23 live gap: {@code dateFieldPath: "System date"} on a date-aware entry we emit without one, {@code constantValue}/{@code dateFieldPath}: explicit null elsewhere. */
	private static void sameChain_toleratesDateFieldPathDefaultAndExplicitNulls() throws Exception
	{
		final JsonNode ours = MatchingPolicyComparatorTest.parse("[{\"function\":\"mame.addon.reference.data.recordSelectionPolicy.mostTrustedSource.label\"},"
			+ "{\"function\":\"mame.addon.reference.data.recordSelectionPolicy.lastUpdate.label\"}]");
		final JsonNode readBack = MatchingPolicyComparatorTest.parse("[{\"constantValue\":null,\"dateFieldPath\":null,"
			+ "\"function\":\"mame.addon.reference.data.recordSelectionPolicy.mostTrustedSource.label\"},"
			+ "{\"constantValue\":null,\"dateFieldPath\":\"System date\",\"function\":\"mame.addon.reference.data.recordSelectionPolicy.lastUpdate.label\"}]");
		MatchingPolicyComparatorTest.check("an entry we sent bare, read back with explicit nulls plus the add-on's own System date default: same",
			MatchingPolicyComparator.sameChain(ours, readBack));
	}

	private static void sameChain_aRealDifferenceIsStillCaught() throws Exception
	{
		final JsonNode ours = MatchingPolicyComparatorTest.parse("[{\"function\":\"longest\"}]");
		final JsonNode readBack = MatchingPolicyComparatorTest.parse("[{\"constantValue\":null,\"dateFieldPath\":null,\"function\":\"mostCompleted\"}]");
		MatchingPolicyComparatorTest.check("a genuinely different function is still caught", !MatchingPolicyComparator.sameChain(ours, readBack));
	}

	// ------------------------------------------------------------------------------------------ sameParams (§26 gap)

	/**
	 * 2026-09-23 13:30 live gap (Contact, `docs/MATCHING.md` &sect;26): the add-on fills {@code
	 * jaroWinklerThreshold: 0.7} onto every jarowinkler field's own params - a STORED-only param must never read
	 * as a difference. {@link MatchingPolicyComparator#sameDecisionTree}'s own contract is {@code (pStored,
	 * pDesired)} - the STORED side (the live read-back, carrying the extra param) goes FIRST here, unlike the
	 * other {@code sameDecisionTree_*} tests above, where the comparison stayed symmetric either way and the
	 * argument order was never load-bearing.
	 */
	private static void sameDecisionTree_storedExtraParam_isAddonDefault_same() throws Exception
	{
		final JsonNode stored = MatchingPolicyComparatorTest.parse("{\"name\":\"n\",\"function\":\"Average\",\"params\":[{\"name\":\"minimumScore\",\"value\":80}],"
			+ "\"fields\":[{\"field\":\"/name\",\"algorithm\":\"jarowinkler\",\"params\":[{\"name\":\"weight\",\"value\":3},"
			+ "{\"name\":\"jaroWinklerThreshold\",\"value\":0.7}]}],"
			+ "\"trueNode\":{\"name\":\"Match\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}");
		final JsonNode desired = MatchingPolicyComparatorTest.parse("{\"name\":\"n\",\"function\":\"Average\",\"params\":[{\"name\":\"minimumScore\",\"value\":\"80\"}],"
			+ "\"fields\":[{\"field\":\"/name\",\"algorithm\":\"jarowinkler\",\"params\":[{\"name\":\"weight\",\"value\":\"3\"}]}],"
			+ "\"trueNode\":{\"name\":\"Match\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}");
		MatchingPolicyComparatorTest.check("a stored-only jaroWinklerThreshold (an add-on algorithm default we never sent) is not a difference",
			MatchingPolicyComparator.sameDecisionTree(stored, desired));
	}

	/** The opposite direction is still a real difference: a param the DESIRED side asks for that the STORED (live) answer does not carry at all is never an add-on default. */
	private static void sameDecisionTree_desiredExtraParam_notInStored_differs() throws Exception
	{
		final JsonNode stored = MatchingPolicyComparatorTest.parse("{\"name\":\"n\",\"function\":\"Average\",\"params\":[{\"name\":\"minimumScore\",\"value\":80}],\"fields\":[],"
			+ "\"trueNode\":{\"name\":\"Match\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}");
		final JsonNode desired = MatchingPolicyComparatorTest.parse("{\"name\":\"n\",\"function\":\"Average\","
			+ "\"params\":[{\"name\":\"minimumScore\",\"value\":\"80\"},{\"name\":\"minimumNumberOfMatches\",\"value\":\"2\"}],\"fields\":[],"
			+ "\"trueNode\":{\"name\":\"Match\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}");
		MatchingPolicyComparatorTest.check("a param the desired side wants that the stored answer lacks entirely is still a real difference",
			!MatchingPolicyComparator.sameDecisionTree(stored, desired));
	}

	/** A param present on both sides with a genuinely different value is still a difference - the numeric-tolerant equality check itself is unchanged, only which KEYS must match changed. */
	private static void sameDecisionTree_sharedParamDifferentValue_stillDiffers() throws Exception
	{
		final JsonNode stored = MatchingPolicyComparatorTest.parse("{\"name\":\"n\",\"function\":\"Average\",\"params\":[{\"name\":\"minimumScore\",\"value\":90}],\"fields\":[],"
			+ "\"trueNode\":{\"name\":\"Match\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}");
		final JsonNode desired = MatchingPolicyComparatorTest.parse("{\"name\":\"n\",\"function\":\"Average\",\"params\":[{\"name\":\"minimumScore\",\"value\":\"80\"}],\"fields\":[],"
			+ "\"trueNode\":{\"name\":\"Match\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}");
		MatchingPolicyComparatorTest.check("80 vs 90 on a shared param is still a real difference", !MatchingPolicyComparator.sameDecisionTree(stored, desired));
	}

	// ------------------------------------------------------------------------------------------------ live pair (§19.11)

	/**
	 * The exact regression this lot exists for (coordinator, PipelineStep 128, 2026-09-23 ~09:30, `docs/
	 * MATCHING.md` &sect;19.11): a live re-{@code configure} of {@code Company} answered {@code "differs:
	 * decisionTree, merge policy chains ..."} even though the stored policy was BYTE-FOR-BYTE what this
	 * project's own translator had just written for the SAME plan (run 31) - a pure read-back artifact, not a
	 * real change. Builds the DESIRED side through the real translator, then applies to a deep copy EXACTLY the
	 * read-back mutations measured live and documented at `docs/MATCHING.md` &sect;14.2/&sect;14.6/&sect;19.11
	 * (leaf outcomes as display strings, param values as JSON numbers, a date-aware chain entry's own {@code
	 * dateFieldPath} filled with {@code "System date"}, explicit {@code null} on {@code constantValue}/{@code
	 * dateFieldPath} everywhere else) - nothing else. Expects {@link MatchingPolicyComparator.Diff#same()}.
	 */
	private static void compare_liveStoredPolicyReadBack_matchesRun31Plan_same() throws Exception
	{
		final JsonNode requirements = MatchingPolicyComparatorTest.run31Requirements();
		final ObjectNode desiredMatchingPolicy = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, false);
		final ObjectNode desiredMergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, java.util.Collections.singletonList("supplierId"),
			new ArrayList<String>());

		final ObjectNode storedMatchingPolicy = desiredMatchingPolicy.deepCopy();
		storedMatchingPolicy.put("mergePolicyCode", "Company");
		storedMatchingPolicy.set("decisionTree", MatchingPolicyComparatorTest.readBackTree(desiredMatchingPolicy.path("decisionTree")));

		final ObjectNode storedMergePolicy = desiredMergePolicy.deepCopy();
		storedMergePolicy.set("recordSelectionFunctionChain", MatchingPolicyComparatorTest.readBackChain(desiredMergePolicy.path("recordSelectionFunctionChain")));
		storedMergePolicy.set("defaultFieldMergeFunctionChain", MatchingPolicyComparatorTest.readBackChain(desiredMergePolicy.path("defaultFieldMergeFunctionChain")));
		final com.fasterxml.jackson.databind.node.ArrayNode storedSurvivors = MatchingPolicyComparatorTest.MAPPER.createArrayNode();
		for (final JsonNode survivor : desiredMergePolicy.path("survivors"))
		{
			final ObjectNode copy = survivor.deepCopy();
			copy.set("mergeFunctionChain", MatchingPolicyComparatorTest.readBackChain(survivor.path("mergeFunctionChain")));
			storedSurvivors.add(copy);
		}
		storedMergePolicy.set("survivors", storedSurvivors);

		final MatchingPolicyComparator.Diff diff = MatchingPolicyComparator.compare(storedMatchingPolicy, desiredMatchingPolicy, "Company", storedMergePolicy,
			desiredMergePolicy, Collections.<String>emptySet());
		MatchingPolicyComparatorTest.check("decisionTree: read-back artifacts tolerated - same: " + diff.summary(), !diff.decisionTreeChanged);
		MatchingPolicyComparatorTest.check("merge policy chains: read-back artifacts tolerated - same: " + diff.summary(), !diff.mergeChainsChanged);
		MatchingPolicyComparatorTest.check("merge policy survivors: read-back artifacts tolerated - same: " + diff.summary(), !diff.survivorsChanged);
		MatchingPolicyComparatorTest.check("candidate-gate fields untouched by this fixture - same", !diff.candidateFieldsChanged);
		MatchingPolicyComparatorTest.check("mergePolicyCode matches - same", !diff.mergePolicyCodeChanged);
		MatchingPolicyComparatorTest.check("neither side has relatedTables - same", !diff.relatedTablesChanged);
		MatchingPolicyComparatorTest.check("overall: SAME, exactly what a genuinely unmodified re-configure must report", diff.same());
	}

	/** Recursively rewrites a translator-built {@code decisionTree} into the shape a live read-back actually carries - see {@link #compare_liveStoredPolicyReadBack_matchesRun31Plan_same}'s own Javadoc. */
	private static JsonNode readBackTree(final JsonNode pNode)
	{
		final ObjectNode out = pNode.deepCopy();
		if (!out.hasNonNull("function"))
		{
			out.put("name", MatchingPolicyComparatorTest.readBackLeafName(out.path("name").asText()));
			return out;
		}
		out.set("params", MatchingPolicyComparatorTest.readBackParams(out.path("params")));
		final java.util.List<JsonNode> fields = new ArrayList<JsonNode>();
		for (final JsonNode field : out.path("fields"))
		{
			fields.add(field);
		}
		java.util.Collections.reverse(fields);
		final com.fasterxml.jackson.databind.node.ArrayNode reordered = MatchingPolicyComparatorTest.MAPPER.createArrayNode();
		for (final JsonNode field : fields)
		{
			final ObjectNode fieldCopy = field.deepCopy();
			fieldCopy.set("params", MatchingPolicyComparatorTest.readBackParams(fieldCopy.path("params")));
			if ("NOT_MATCH".equals(fieldCopy.path("bothNullValueHandling").asText(null)))
			{
				fieldCopy.remove("bothNullValueHandling");
			}
			if ("NOT_MATCH".equals(fieldCopy.path("oneNullValueHandling").asText(null)))
			{
				fieldCopy.remove("oneNullValueHandling");
			}
			reordered.add(fieldCopy);
		}
		out.set("fields", reordered);
		out.set("trueNode", MatchingPolicyComparatorTest.readBackTree(out.path("trueNode")));
		out.set("falseNode", MatchingPolicyComparatorTest.readBackTree(out.path("falseNode")));
		return out;
	}

	private static String readBackLeafName(final String pCanonical)
	{
		if ("MATCHED".equals(pCanonical))
		{
			return "Match";
		}
		if ("SUSPECTED".equals(pCanonical))
		{
			return "Suspect";
		}
		if ("NOT_MATCHED".equals(pCanonical))
		{
			return "Not Match";
		}
		return pCanonical;
	}

	private static com.fasterxml.jackson.databind.node.ArrayNode readBackParams(final JsonNode pParams)
	{
		final com.fasterxml.jackson.databind.node.ArrayNode out = MatchingPolicyComparatorTest.MAPPER.createArrayNode();
		for (final JsonNode param : pParams)
		{
			final ObjectNode copy = MatchingPolicyComparatorTest.MAPPER.createObjectNode();
			copy.put("name", param.path("name").asText());
			try
			{
				copy.put("value", Integer.parseInt(param.path("value").asText()));
			}
			catch (final NumberFormatException ex)
			{
				copy.put("value", param.path("value").asText());
			}
			out.add(copy);
		}
		return out;
	}

	/** Rewrites a translator-built chain into the shape a live read-back actually carries - explicit nulls, {@code "System date"} on the one date-aware ({@code lastUpdate}) entry. */
	private static com.fasterxml.jackson.databind.node.ArrayNode readBackChain(final JsonNode pChain)
	{
		final com.fasterxml.jackson.databind.node.ArrayNode out = MatchingPolicyComparatorTest.MAPPER.createArrayNode();
		for (final JsonNode entry : pChain)
		{
			final ObjectNode copy = MatchingPolicyComparatorTest.MAPPER.createObjectNode();
			final String function = entry.path("function").asText("");
			copy.put("function", function);
			if (entry.hasNonNull("constantValue"))
			{
				copy.put("constantValue", entry.path("constantValue").asText());
			}
			else
			{
				copy.putNull("constantValue");
			}
			if (function.toLowerCase(java.util.Locale.ENGLISH).contains("lastupdate"))
			{
				copy.put("dateFieldPath", "System date");
			}
			else
			{
				copy.putNull("dateFieldPath");
			}
			out.add(copy);
		}
		return out;
	}

	// --------------------------------------------------------------------------------------------------- fixtures

	/** The stored {@code Company} policy per the compare-then-rewrite task brief: AVERAGE >=80 on name/country/cityKey, clustering country. */
	private static JsonNode storedCompanyRequirements() throws Exception
	{
		return MatchingPolicyComparatorTest.parse("{"
			+ "\"clusteringFields\":[\"country\"],"
			+ "\"decisionTree\":{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":80,\"fields\":["
			+ "{\"field\":\"name\",\"algorithm\":\"fuzzyFullText\",\"weight\":10},"
			+ "{\"field\":\"country\",\"algorithm\":\"exact\",\"weight\":5},"
			+ "{\"field\":\"cityKey\",\"algorithm\":\"exact\",\"weight\":5}],"
			+ "\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}]}"
			+ "}");
	}

	/** Accepted plan run 31 per the compare-then-rewrite task brief: one AVERAGE >=80 node on name only, clustering nameKey, survivorship name -> most trusted source, record selection lastUpdate, default field merge longest. */
	private static JsonNode run31Requirements() throws Exception
	{
		return MatchingPolicyComparatorTest.parse("{"
			+ "\"clusteringFields\":[\"nameKey\"],"
			+ "\"decisionTree\":{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":80,\"fields\":["
			+ "{\"field\":\"name\",\"algorithm\":\"fuzzyFullText\",\"weight\":3}],"
			+ "\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}]},"
			+ "\"survivorship\":{\"fields\":[{\"field\":\"name\",\"chain\":[{\"function\":\"most trusted source\"}]}]},"
			+ "\"mergePolicy\":{\"recordSelection\":\"lastUpdate\",\"defaultFieldMerge\":\"longest\"}"
			+ "}");
	}

	// ------------------------------------------------------------------------------------------- relation fields

	/** A relation-field-bearing plan translated by {@link MatchingPolicyTranslator#translate} (the desired side of every comparison below). */
	private static ObjectNode relationFieldDesired() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		node.fields.add(new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@exact"));
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		relationField.dataType = "STRING";
		node.fields.add(relationField);
		node.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		node.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(node);
		final ObjectNode requirements = MatchingPolicyComparatorTest.MAPPER.createObjectNode();
		requirements.set("decisionTree", DecisionTreePlan.toJson(plan));
		requirements.putArray("clusteringFields").add("firstName");
		final java.util.Map<String, String> childInfo = new java.util.HashMap<String, String>();
		childInfo.put("Adresse", "/root/Adresse");
		final ObjectNode desired = MatchingPolicyTranslator.translate("Personne", requirements, true, childInfo).matchingPolicy;
		desired.put("mergePolicyCode", "Personne");
		return desired;
	}

	/**
	 * What the add-on 6.3.0.5-RC3 reads back for the desired policy (shape measured on W13BO, 2026-10-12): the policy field has {@code field: null} plus the
	 * server-derived {@code useRelationships}/{@code location}, the tree field has {@code field} only (no {@code businessObjectField}).
	 */
	private static ObjectNode relationFieldReadBack(final ObjectNode pDesired)
	{
		final ObjectNode live = pDesired.deepCopy();
		for (final JsonNode field : live.path("fields"))
		{
			if (field.hasNonNull("businessObjectField"))
			{
				final ObjectNode node = (ObjectNode) field;
				node.putNull("field");
				node.put("useRelationships", "BY_FOREIGN_KEY");
				final ObjectNode location = node.putObject("location");
				location.put("associationTable", "/root/Adresse/personneRef");
				location.put("mode", "SAME_DATASET");
			}
			else
			{
				((ObjectNode) field).putNull("businessObjectField");
				((ObjectNode) field).put("useRelationships", "NO");
			}
		}
		for (final JsonNode field : live.path("decisionTree").path("fields"))
		{
			((ObjectNode) field).remove("businessObjectField");
		}
		return live;
	}

	private static void compare_relationField_readBackShape_same() throws Exception
	{
		final ObjectNode desired = MatchingPolicyComparatorTest.relationFieldDesired();
		final ObjectNode live = MatchingPolicyComparatorTest.relationFieldReadBack(desired);
		final ObjectNode mergePolicy = MatchingPolicyComparatorTest.MAPPER.createObjectNode();
		final MatchingPolicyComparator.Diff diff = MatchingPolicyComparator.compare(live, desired, "Personne", mergePolicy, mergePolicy, Collections.<String>emptySet());
		MatchingPolicyComparatorTest.check("the read-back of a native relation field compares as unchanged: " + diff.summary(), diff.same());
	}

	private static void compare_relationField_changedChildField_differsInTreeAndGate() throws Exception
	{
		final ObjectNode desired = MatchingPolicyComparatorTest.relationFieldDesired();
		final ObjectNode live = MatchingPolicyComparatorTest.relationFieldReadBack(desired);
		for (final JsonNode field : live.path("decisionTree").path("fields"))
		{
			if ("/root/Adresse/cityKey".equals(field.path("field").asText()))
			{
				((ObjectNode) field).put("field", "/root/Adresse/streetKey");
			}
		}
		for (final JsonNode field : live.path("fields"))
		{
			if ("/root/Adresse/cityKey".equals(field.path("businessObjectField").asText()))
			{
				((ObjectNode) field).put("businessObjectField", "/root/Adresse/streetKey");
			}
		}
		final ObjectNode mergePolicy = MatchingPolicyComparatorTest.MAPPER.createObjectNode();
		final MatchingPolicyComparator.Diff diff = MatchingPolicyComparator.compare(live, desired, "Personne", mergePolicy, mergePolicy, Collections.<String>emptySet());
		MatchingPolicyComparatorTest.check("another child field in the tree is a tree difference: " + diff.summary(), diff.decisionTreeChanged);
		MatchingPolicyComparatorTest.check("and a different business-object field in the gate is a gate difference: " + diff.summary(), diff.candidateFieldsChanged);
	}

	private static void compare_relationField_changedWeight_differsInGateOnly() throws Exception
	{
		final ObjectNode desired = MatchingPolicyComparatorTest.relationFieldDesired();
		final ObjectNode live = MatchingPolicyComparatorTest.relationFieldReadBack(desired);
		for (final JsonNode field : live.path("fields"))
		{
			if (field.hasNonNull("businessObjectField"))
			{
				((ObjectNode) field).put("weight", 1);
			}
		}
		final ObjectNode mergePolicy = MatchingPolicyComparatorTest.MAPPER.createObjectNode();
		final MatchingPolicyComparator.Diff diff = MatchingPolicyComparator.compare(live, desired, "Personne", mergePolicy, mergePolicy, Collections.<String>emptySet());
		MatchingPolicyComparatorTest.check("a stored relation weight of 1 against the desired 0 is a gate difference only: " + diff.summary(),
			diff.candidateFieldsChanged && !diff.decisionTreeChanged);
	}

	/**
	 * Coordinator review item 2 (2026-09-26, docs/MATCHING.md &sect;33.8): {@code matchingOnCreation}/{@code
	 * matchingOnUpdate} flip between AFTER_SUBMISSION (1/1, an ordinary policy) and MANUAL (0/0, a policy whose
	 * tree carries a relation field) - {@link MatchingPolicyComparator#compare} must never see that flip as drift
	 * (it is a deliberately excluded field, per this class' own Javadoc "Deliberately NOT compared"), so a
	 * re-configure of an already-correct relation-field policy never spuriously rewrites it.
	 */
	private static void compare_matchingOnCreationUpdateDifference_neverCountedAsDrift() throws Exception
	{
		final JsonNode requirements = MatchingPolicyComparatorTest.run31Requirements();
		final ObjectNode stored = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, false);
		stored.put("mergePolicyCode", "Company");
		stored.put("matchingOnCreation", 1);
		stored.put("matchingOnUpdate", 1);
		final ObjectNode desired = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, false);
		desired.put("matchingOnCreation", 0);
		desired.put("matchingOnUpdate", 0);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, new ArrayList<String>(), new ArrayList<String>());
		final MatchingPolicyComparator.Diff diff = MatchingPolicyComparator.compare(stored, desired, "Company", mergePolicy, mergePolicy, Collections.<String>emptySet());
		MatchingPolicyComparatorTest.check("matchingOnCreation/matchingOnUpdate differing (1/1 vs 0/0) is never itself reported as drift: " + diff.summary(),
			diff.same());
	}

	private static JsonNode parse(final String pJson) throws Exception
	{
		return MatchingPolicyComparatorTest.MAPPER.readTree(pJson);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
