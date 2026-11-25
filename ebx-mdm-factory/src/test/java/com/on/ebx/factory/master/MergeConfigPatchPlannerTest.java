package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like the sibling {@code master} tests: run the main, a failure throws.
 * <p>
 * The fixture for "current" is the plain shape `docs/MASTER-ZONE.md` &sect;1.1 recorded live on the real,
 * unmodified {@code Companies} table BEFORE this lot: {@code recordSelectionFunctionChain: [mostCompleted]},
 * {@code defaultFieldMergeFunctionChain: [longest]}, {@code survivors: []} - turning into {@link
 * MatchingPolicyTranslator#buildMergePolicy}'s desired trusted-source shape is exactly the real-world case
 * {@code POST /projects/{code}/matching/survivorship} exists to handle.
 */
public final class MergeConfigPatchPlannerTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		MergeConfigPatchPlannerTest.identicalConfig_emptyOpsList();
		MergeConfigPatchPlannerTest.realCompaniesFixture_producesExpectedOps();
		MergeConfigPatchPlannerTest.missingCurrentPolicy_everyDesiredEntryIsAnAddition();
		MergeConfigPatchPlannerTest.survivorFieldRemoved_wholeFieldRemoval();
		MergeConfigPatchPlannerTest.chainShrinks_removedFromTheEndBackwards();
		MergeConfigPatchPlannerTest.findMergePolicy_locatesByCode_missingWhenAbsent();
		MergeConfigPatchPlannerTest.findMergePolicy_neverMatchesAPreviewEntry();
		MergeConfigPatchPlannerTest.businessSurvivorRule_addedAsASingleSurvivorFieldOp();
		MergeConfigPatchPlannerTest.systemDateAndExplicitNullNormalization_noSpuriousRewrite();
		MergeConfigPatchPlannerTest.legacyConstantSourceSystem_isReplacedByNoMergeAndAGoldenSource();
		System.out.println("MergeConfigPatchPlannerTest OK");
	}

	/**
	 * Survivorship lot (2026-09-22, `docs/MATCHING.md` &sect;15): {@link MatchingPolicyTranslator#buildMergePolicy}
	 * already folds an LLM-proposed business survivor rule into its own {@code survivors[]} output (see {@code
	 * MatchingPolicyTranslatorTest}) - this planner needs NO code change to pick it up: {@link
	 * MergeConfigPatchPlanner#plan}'s own {@code planSurvivors} already diffs {@code survivors[]} generically,
	 * field by field. A "current" config with the four technical survivors but none for the new business field
	 * must emit exactly one {@code add_survivor_field} for it.
	 */
	private static void businessSurvivorRule_addedAsASingleSurvivorFieldOp()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode currentRequirements = MergeConfigPatchPlannerTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode current = MatchingPolicyTranslator.buildMergePolicy("Company", currentRequirements, Collections.<String> emptyList(), Collections.<String> emptyList());

		final ObjectNode desiredRequirements = (ObjectNode) MergeConfigPatchPlannerTest
			.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode fieldRule = desiredRequirements.putObject("survivorship").putArray("fields").addObject();
		fieldRule.put("field", "city");
		fieldRule.putArray("chain").addObject().put("function", "most frequent");
		fieldRule.put("rationale", "identical on shared records");
		fieldRule.put("confidence", "high");
		final ObjectNode desired = MatchingPolicyTranslator.buildMergePolicy("Company", desiredRequirements, Collections.<String> emptyList(), Collections.<String> emptyList());

		final ArrayNode ops = MergeConfigPatchPlanner.plan("Company", current, desired);
		MergeConfigPatchPlannerTest.check("exactly one add_survivor_field op, for /city",
			MergeConfigPatchPlannerTest.hasOp(ops, "add_survivor_field", null, "/city", null));
		MergeConfigPatchPlannerTest.check("nothing else changed (the two chains and the other four survivors are already identical)", ops.size() == 1);
	}

	private static void identicalConfig_emptyOpsList()
	{
		final ObjectNode desired = MergeConfigPatchPlannerTest.chainOnlyPolicy("Company", "mostCompleted", "longest");
		final JsonNode current = MergeConfigPatchPlannerTest.parse(
			"{\"code\":\"Company\",\"recordSelectionFunctionChain\":[{\"function\":\"mostCompleted\"}],"
				+ "\"defaultFieldMergeFunctionChain\":[{\"function\":\"longest\"}],\"survivors\":[]}");
		final ArrayNode ops = MergeConfigPatchPlanner.plan("Company", current, desired);
		MergeConfigPatchPlannerTest.check("an already-matching config produces no ops at all", ops.size() == 0);
	}

	/**
	 * The real, documented pre-lot Companies shape turning into the trusted-source desired shape built by
	 * {@link MatchingPolicyTranslator#buildMergePolicy} - pinning the exact op list the two live refusals
	 * (`docs/MATCHING.md` &sect;14.6, "measured live, 2026-09-22 23:40" and "2026-09-23 01:10") forced this
	 * class to correct: positions are 1-BASED, an appended entry carries NO position at all, and every
	 * chain-entry {@code value} - {@code add_survivor_field} included - is a JSON STRING (a bare function
	 * name when the entry has no parameter, the entry object JSON-ENCODED AS A STRING when it does), never a
	 * raw JSON object.
	 */
	private static void realCompaniesFixture_producesExpectedOps()
	{
		final ObjectNode requirements = MergeConfigPatchPlannerTest.MAPPER.createObjectNode();
		requirements.putArray("tiers");
		requirements.putArray("clusteringFields");
		requirements.putObject("mergePolicy");
		final ObjectNode desired = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Arrays.asList("supplierId"), Collections.<String> emptyList());

		final JsonNode current = MergeConfigPatchPlannerTest.parse(
			"{\"code\":\"Company\",\"mode\":\"ONLY_DUPLICATES\",\"recordSelectionFunctionChain\":"
				+ "[{\"function\":\"mame.addon.reference.data.recordSelectionPolicy.mostCompleted.label\"}],"
				+ "\"defaultFieldMergeFunctionChain\":[{\"function\":\"longest\"}],\"survivors\":[]}");

		final ArrayNode ops = MergeConfigPatchPlanner.plan("Company", current, desired);

		MergeConfigPatchPlannerTest.check("record_selection: position 1 changes (mostCompleted -> mostTrustedSource) and a second entry is APPENDED (no position)",
			MergeConfigPatchPlannerTest.hasOp(ops, "set_chain_entry", "record_selection", null, Integer.valueOf(1))
				&& MergeConfigPatchPlannerTest.hasAppendOp(ops, "add_chain_entry", "record_selection", null));
		MergeConfigPatchPlannerTest.check("default_field_merge: position 1 changes (longest -> most trusted source) and a second entry is APPENDED (no position)",
			MergeConfigPatchPlannerTest.hasOp(ops, "set_chain_entry", "default_field_merge", null, Integer.valueOf(1))
				&& MergeConfigPatchPlannerTest.hasAppendOp(ops, "add_chain_entry", "default_field_merge", null));
		MergeConfigPatchPlannerTest.check("five add_survivor_field ops - one per desired survivor, none existed before",
			MergeConfigPatchPlannerTest.countOps(ops, "add_survivor_field") == 5);
		MergeConfigPatchPlannerTest.check("every add_survivor_field value is a bare function-name STRING, never a {mergeFunctionChain:...} envelope",
			MergeConfigPatchPlannerTest.everyAddSurvivorFieldValueIsAString(ops));
		MergeConfigPatchPlannerTest.check(
			"lot M1: no follow-up set_chain_entry for /sourceSystem either - its single entry (no merge) has no constantValue/dateFieldPath, the golden source is a policy param",
			!MergeConfigPatchPlannerTest.hasOp(ops, "set_chain_entry", "survivor_field", "/sourceSystem", Integer.valueOf(1)));
		MergeConfigPatchPlannerTest.check("lot M1: exactly one set_merge_policy_param autoCreateNewGoldenSource = MDM (a REGISTERED source code, as a plain string)",
			MergeConfigPatchPlannerTest.countOps(ops, "set_merge_policy_param") == 1
				&& "autoCreateNewGoldenSource".equals(MergeConfigPatchPlannerTest.firstOp(ops, "set_merge_policy_param", null, null, null).path("target").path("paramName").asText())
				&& "MDM".equals(MergeConfigPatchPlannerTest.firstOp(ops, "set_merge_policy_param", null, null, null).path("value").asText()));
		MergeConfigPatchPlannerTest.check(
			"no such follow-up set_chain_entry for /sourceId, /_loadId, /_receivedAt or /supplierId - their single entry has no constantValue/dateFieldPath",
			!MergeConfigPatchPlannerTest.hasOp(ops, "set_chain_entry", "survivor_field", "/sourceId", null)
				&& !MergeConfigPatchPlannerTest.hasOp(ops, "set_chain_entry", "survivor_field", "/_loadId", null)
				&& !MergeConfigPatchPlannerTest.hasOp(ops, "set_chain_entry", "survivor_field", "/_receivedAt", null)
				&& !MergeConfigPatchPlannerTest.hasOp(ops, "set_chain_entry", "survivor_field", "/supplierId", null));
		MergeConfigPatchPlannerTest.check("no remove_chain_entry/remove_survivor_field op at all - nothing to remove from an empty starting point",
			MergeConfigPatchPlannerTest.countOps(ops, "remove_chain_entry") == 0 && MergeConfigPatchPlannerTest.countOps(ops, "remove_survivor_field") == 0);

		// 2026-09-23 01:10 measurements: EVERY chain-op value is a JSON STRING (never a raw object), a
		// parameter-less entry's value is the BARE function name (not JSON-quoted), and a parameterized
		// entry's value parses back, as JSON, to exactly that entry - the live contract `entryValueString`
		// implements (class Javadoc).
		final JsonNode recordSelectionSet = MergeConfigPatchPlannerTest.firstOp(ops, "set_chain_entry", "record_selection", null, Integer.valueOf(1));
		MergeConfigPatchPlannerTest.check("record_selection position 1's raw value is the bare i18n key, not JSON-quoted",
			"mame.addon.reference.data.recordSelectionPolicy.mostTrustedSource.label".equals(recordSelectionSet.path("value").asText()));
		final JsonNode recordSelectionAppend = MergeConfigPatchPlannerTest.firstOp(ops, "add_chain_entry", "record_selection", null, null);
		MergeConfigPatchPlannerTest.check("record_selection's appended entry is likewise a bare i18n key string",
			"mame.addon.reference.data.recordSelectionPolicy.mostCompleted.label".equals(recordSelectionAppend.path("value").asText()));
		final JsonNode defaultFieldMergeSet = MergeConfigPatchPlannerTest.firstOp(ops, "set_chain_entry", "default_field_merge", null, Integer.valueOf(1));
		MergeConfigPatchPlannerTest.check("default_field_merge position 1's raw value is the bare literal string \"most trusted source\" - "
			+ "the EXACT shape measured accepted live 2026-09-23 01:10", "most trusted source".equals(defaultFieldMergeSet.path("value").asText()));

		MergeConfigPatchPlannerTest.check("lot M1: the /sourceSystem survivor is created as the bare function name \"no merge\" - never the constant it used to be",
			"no merge".equals(MergeConfigPatchPlannerTest.firstOp(ops, "add_survivor_field", null, "/sourceSystem", null).path("value").asText()));
	}

	/**
	 * Lot M1 (2026-10-17), the GUARD for tables configured before the fix: the stored policy of E2EMASTER20261017212914 (a {@code constant "MDM"} survivor on
	 * {@code /sourceSystem}, no {@code autoCreateNewGoldenSource}) must be turned into the safe shape by a re-configure - one {@code set_chain_entry} replacing the
	 * constant by {@code no merge} and one {@code set_merge_policy_param}; and once converted, a second plan is empty.
	 */
	private static void legacyConstantSourceSystem_isReplacedByNoMergeAndAGoldenSource()
	{
		final ObjectNode requirements = MergeConfigPatchPlannerTest.MAPPER.createObjectNode();
		requirements.putArray("tiers");
		requirements.putArray("clusteringFields");
		requirements.putObject("mergePolicy");
		final ObjectNode desired = MatchingPolicyTranslator.buildMergePolicy("Personne", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		final ObjectNode legacy = desired.deepCopy();
		legacy.remove("autoCreateNewGoldenSource");
		final ObjectNode legacySourceSystem = (ObjectNode) legacy.path("survivors").get(0).path("mergeFunctionChain").get(0);
		MergeConfigPatchPlannerTest.check("fixture: the first survivor is /sourceSystem", "/sourceSystem".equals(legacy.path("survivors").get(0).path("field").asText()));
		legacySourceSystem.put("function", "constant").put("constantValue", "MDM");
		final ArrayNode ops = MergeConfigPatchPlanner.plan("Personne", legacy, desired);
		MergeConfigPatchPlannerTest.check("the constant on /sourceSystem is replaced in place (set_chain_entry survivor_field position 1) by the bare string \"no merge\"",
			MergeConfigPatchPlannerTest.hasOp(ops, "set_chain_entry", "survivor_field", "/sourceSystem", Integer.valueOf(1))
				&& "no merge".equals(MergeConfigPatchPlannerTest.firstOp(ops, "set_chain_entry", "survivor_field", "/sourceSystem", Integer.valueOf(1)).path("value").asText()));
		MergeConfigPatchPlannerTest.check("the golden source is written once, as a plain string",
			MergeConfigPatchPlannerTest.countOps(ops, "set_merge_policy_param") == 1
				&& "MDM".equals(MergeConfigPatchPlannerTest.firstOp(ops, "set_merge_policy_param", null, null, null).path("value").asText()));
		MergeConfigPatchPlannerTest.check("nothing else is touched: exactly two ops", ops.size() == 2);
		// converted: the stored shape now equals the desired one (explicit nulls as the add-on reads them back) - no op left
		final ObjectNode converted = desired.deepCopy();
		MergeConfigPatchPlannerTest.check("a stored policy that already carries the golden source and the no-merge survivor needs no op", MergeConfigPatchPlanner.plan("Personne", converted, desired).size() == 0);
	}

	private static void missingCurrentPolicy_everyDesiredEntryIsAnAddition()
	{
		final ObjectNode desired = MergeConfigPatchPlannerTest.chainOnlyPolicy("Company", "mostCompleted", "longest");
		final ArrayNode ops = MergeConfigPatchPlanner.plan("Company", null, desired);
		MergeConfigPatchPlannerTest.check("both chains fully added as APPENDS (no position sent at all - null current reads as empty)",
			MergeConfigPatchPlannerTest.hasAppendOp(ops, "add_chain_entry", "record_selection", null)
				&& MergeConfigPatchPlannerTest.hasAppendOp(ops, "add_chain_entry", "default_field_merge", null));
		MergeConfigPatchPlannerTest.check("no set_chain_entry/remove_chain_entry - there was nothing to overwrite or shrink",
			MergeConfigPatchPlannerTest.countOps(ops, "set_chain_entry") == 0 && MergeConfigPatchPlannerTest.countOps(ops, "remove_chain_entry") == 0);
	}

	private static void survivorFieldRemoved_wholeFieldRemoval()
	{
		final ObjectNode desired = MergeConfigPatchPlannerTest.chainOnlyPolicy("Company", "mostCompleted", "longest");
		// desired carries no survivors at all in this fixture (chainOnlyPolicy leaves it empty) - a current
		// config with one survivor field must be removed WHOLESALE, never drained via survivor_field chain ops.
		final JsonNode current = MergeConfigPatchPlannerTest.parse("{\"code\":\"Company\",\"recordSelectionFunctionChain\":[{\"function\":\"mostCompleted\"}],"
			+ "\"defaultFieldMergeFunctionChain\":[{\"function\":\"longest\"}],"
			+ "\"survivors\":[{\"field\":\"/vatNo\",\"mergeFunctionChain\":[{\"function\":\"longest\"}]}]}");
		final ArrayNode ops = MergeConfigPatchPlanner.plan("Company", current, desired);
		MergeConfigPatchPlannerTest.check("exactly one remove_survivor_field op, for /vatNo", MergeConfigPatchPlannerTest.countOps(ops, "remove_survivor_field") == 1);
		boolean found = false;
		for (final JsonNode op : ops)
		{
			if ("remove_survivor_field".equals(op.path("op").asText()) && "/vatNo".equals(op.path("target").path("survivorField").asText()))
			{
				found = true;
			}
		}
		MergeConfigPatchPlannerTest.check("the removal targets /vatNo specifically", found);
	}

	/** A chain shrinking from 3 entries to 1 must remove from the END backwards (1-based positions 3, then 2) - never renumber a not-yet-processed position. */
	private static void chainShrinks_removedFromTheEndBackwards()
	{
		final JsonNode current = MergeConfigPatchPlannerTest.parse("{\"code\":\"Company\","
			+ "\"recordSelectionFunctionChain\":[{\"function\":\"a\"},{\"function\":\"b\"},{\"function\":\"c\"}],"
			+ "\"defaultFieldMergeFunctionChain\":[{\"function\":\"longest\"}],\"survivors\":[]}");
		final ObjectNode desired = MergeConfigPatchPlannerTest.MAPPER.createObjectNode();
		desired.putArray("recordSelectionFunctionChain").addObject().put("function", "a");
		desired.putArray("defaultFieldMergeFunctionChain").addObject().put("function", "longest");
		desired.putArray("survivors");

		final ArrayNode ops = MergeConfigPatchPlanner.plan("Company", current, desired);
		MergeConfigPatchPlannerTest.check("no change at position 1 (already 'a')", !MergeConfigPatchPlannerTest.hasOp(ops, "set_chain_entry", "record_selection", null, Integer.valueOf(1)));
		MergeConfigPatchPlannerTest.check("removals in DESCENDING 1-based position order: 3 before 2", MergeConfigPatchPlannerTest.removalOrderDescending(ops));
	}

	private static void findMergePolicy_locatesByCode_missingWhenAbsent()
	{
		final JsonNode answer = MergeConfigPatchPlannerTest.parse(
			"{\"success\":true,\"data\":{\"mergePolicies\":[{\"code\":\"Company\",\"mode\":\"ONLY_DUPLICATES\"},{\"code\":\"Other\",\"mode\":\"DISABLED\"}]}}");
		final JsonNode found = MergeConfigPatchPlanner.findMergePolicy(answer, "Company");
		MergeConfigPatchPlannerTest.check("locates the Company entry", "ONLY_DUPLICATES".equals(found.path("mode").asText()));
		final JsonNode missing = MergeConfigPatchPlanner.findMergePolicy(answer, "DoesNotExist");
		MergeConfigPatchPlannerTest.check("a code not present reads as MissingNode, not null, not a thrown exception", missing.isMissingNode());
	}

	/**
	 * simulation-preview-policy lot (2026-09-22), task brief item 5: {@code get_matching_config} readers must
	 * ignore a preview policy ({@code <Entity>__preview}, {@code ebx.MatchingPreviewPolicy}) when looking for
	 * the REAL one. {@link MergeConfigPatchPlanner#findMergePolicy} already does, by construction (it matches
	 * by EXACT code, and a preview entry's code is never equal to the plain entity name it looks up) - this
	 * locks that invariant down with a fixture carrying BOTH a real and a preview entry for the same table, so
	 * a future change to the lookup (e.g. "first entry" instead of "exact code match") would fail this test
	 * rather than silently start resolving the wrong policy. {@code master} never imports {@code ebx} (layering
	 * convention this codebase already follows) - the preview code is spelled out literally here rather than
	 * referencing {@code ebx.MatchingPreviewPolicy}'s own constant.
	 */
	private static void findMergePolicy_neverMatchesAPreviewEntry()
	{
		final JsonNode answer = MergeConfigPatchPlannerTest.parse(
			"{\"success\":true,\"data\":{\"mergePolicies\":[{\"code\":\"Company\",\"mode\":\"ONLY_DUPLICATES\"},"
				+ "{\"code\":\"Company__preview\",\"mode\":\"DISABLED\"}]}}");
		final JsonNode found = MergeConfigPatchPlanner.findMergePolicy(answer, "Company");
		MergeConfigPatchPlannerTest.check("locates the REAL Company entry, never the preview one", "ONLY_DUPLICATES".equals(found.path("mode").asText()));
		final JsonNode previewLookedUpDirectly = MergeConfigPatchPlanner.findMergePolicy(answer, "Company__preview");
		MergeConfigPatchPlannerTest.check("a lookup BY the preview code still finds it - the reader only ever needs to skip it when looking for the real one",
			"DISABLED".equals(previewLookedUpDirectly.path("mode").asText()));
	}

	/**
	 * 2026-09-23 01:10 measurement (class Javadoc): a real {@code get_matching_config} answer stores
	 * {@code constantValue}/{@code dateFieldPath} as EXPLICIT JSON {@code null} on a plain entry, and stamps
	 * {@code dateFieldPath: "System date"} onto a date-aware entry ({@code lastUpdate}) that was created with
	 * none - a config already matching the desired shape modulo exactly these two quirks must still produce
	 * NO ops at all (a genuinely idempotent re-apply), never a spurious rewrite of an already-correct entry.
	 */
	private static void systemDateAndExplicitNullNormalization_noSpuriousRewrite()
	{
		final ObjectNode requirements = MergeConfigPatchPlannerTest.MAPPER.createObjectNode();
		requirements.putArray("tiers");
		requirements.putArray("clusteringFields");
		requirements.putObject("mergePolicy").put("recordSelection", "lastUpdate");
		final ObjectNode desired = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());

		final ObjectNode current = MergeConfigPatchPlannerTest.MAPPER.createObjectNode();
		current.put("code", "Company");
		current.put("autoCreateNewGoldenSource", "MDM");
		final ArrayNode recordSelection = current.putArray("recordSelectionFunctionChain");
		recordSelection.addObject().put("function", "mame.addon.reference.data.recordSelectionPolicy.mostTrustedSource.label").putNull("constantValue")
			.putNull("dateFieldPath");
		// the add-on's own default for a date-aware entry created with none (class Javadoc) - must compare
		// EQUAL to "no dateFieldPath at all", which is exactly what `desired`'s own lastUpdate entry has.
		recordSelection.addObject().put("function", "mame.addon.reference.data.recordSelectionPolicy.lastUpdate.label").putNull("constantValue")
			.put("dateFieldPath", "System date");
		final ArrayNode defaultFieldMerge = current.putArray("defaultFieldMergeFunctionChain");
		defaultFieldMerge.addObject().put("function", "most trusted source").putNull("constantValue").putNull("dateFieldPath");
		defaultFieldMerge.addObject().put("function", "longest").putNull("constantValue").putNull("dateFieldPath");
		final ArrayNode survivors = current.putArray("survivors");
		survivors.addObject().put("field", "/sourceSystem").putArray("mergeFunctionChain").addObject().put("function", "no merge").putNull("constantValue")
			.putNull("dateFieldPath");
		survivors.addObject().put("field", "/sourceId").putArray("mergeFunctionChain").addObject().put("function", "no merge").putNull("constantValue")
			.putNull("dateFieldPath");
		survivors.addObject().put("field", "/_loadId").putArray("mergeFunctionChain").addObject().put("function", "no merge").putNull("constantValue")
			.putNull("dateFieldPath");
		survivors.addObject().put("field", "/_receivedAt").putArray("mergeFunctionChain").addObject().put("function", "max").putNull("constantValue")
			.putNull("dateFieldPath");

		final ArrayNode ops = MergeConfigPatchPlanner.plan("Company", current, desired);
		MergeConfigPatchPlannerTest.check(
			"a config that already matches modulo explicit JSON null and the add-on's own \"System date\" default produces NO ops - "
				+ "actual ops: " + ops,
			ops.size() == 0);
	}

	/** Parses a chain op's own {@code value} back into an entry-shaped node: a JSON-object-LOOKING string parses as JSON; any other string (a bare function name) becomes {@code {function: <value>}}. Fails the surrounding test outright if {@code value} is not textual at all (a raw JSON object is exactly the shape the live tool refuses, 2026-09-23 01:10). */
	private static JsonNode parseChainOpValue(final JsonNode pOp)
	{
		final JsonNode value = pOp.path("value");
		MergeConfigPatchPlannerTest.check("chain op value must be a JSON STRING, never a JSON object: " + pOp, value.isTextual());
		final String text = value.asText();
		if (text.startsWith("{"))
		{
			return MergeConfigPatchPlannerTest.parse(text);
		}
		final ObjectNode bareFunction = MergeConfigPatchPlannerTest.MAPPER.createObjectNode();
		bareFunction.put("function", text);
		return bareFunction;
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	private static ObjectNode chainOnlyPolicy(final String pCode, final String pRecordSelectionFunction, final String pDefaultFieldMergeFunction)
	{
		final ObjectNode policy = MergeConfigPatchPlannerTest.MAPPER.createObjectNode();
		policy.put("code", pCode);
		policy.putArray("recordSelectionFunctionChain").addObject().put("function", pRecordSelectionFunction);
		policy.putArray("defaultFieldMergeFunctionChain").addObject().put("function", pDefaultFieldMergeFunction);
		policy.putArray("survivors");
		return policy;
	}

	private static boolean hasOp(final ArrayNode pOps, final String pOp, final String pChain, final String pSurvivorField, final Integer pPosition)
	{
		for (final JsonNode op : pOps)
		{
			if (!pOp.equals(op.path("op").asText()))
			{
				continue;
			}
			final JsonNode target = op.path("target");
			final boolean chainMatches = pChain == null || pChain.equals(target.path("chain").asText(null));
			final boolean survivorMatches = pSurvivorField == null ? !target.has("survivorField") : pSurvivorField.equals(target.path("survivorField").asText(null));
			final boolean positionMatches = pPosition == null || pPosition.intValue() == target.path("position").asInt(-1);
			if (chainMatches && survivorMatches && positionMatches)
			{
				return true;
			}
		}
		return false;
	}

	/** Same matching rules as {@link #hasOp} (a {@code null} filter is a wildcard, {@code pPosition == null} matches any position INCLUDING none), but returns the first matching op node itself rather than a boolean - so a test can go on to inspect its {@code value}. {@code null} if nothing matches. */
	private static JsonNode firstOp(final ArrayNode pOps, final String pOp, final String pChain, final String pSurvivorField, final Integer pPosition)
	{
		for (final JsonNode op : pOps)
		{
			if (!pOp.equals(op.path("op").asText()))
			{
				continue;
			}
			final JsonNode target = op.path("target");
			final boolean chainMatches = pChain == null || pChain.equals(target.path("chain").asText(null));
			final boolean survivorMatches = pSurvivorField == null ? !target.has("survivorField") : pSurvivorField.equals(target.path("survivorField").asText(null));
			final boolean positionMatches = pPosition == null || pPosition.intValue() == target.path("position").asInt(-1);
			if (chainMatches && survivorMatches && positionMatches)
			{
				return op;
			}
		}
		return null;
	}

	/** Like {@link #hasOp}, but requires {@code target} to carry NO {@code position} at all - the append shape the live contract demands ("appends when target.position is omitted"), distinct from {@code hasOp(pOps, pOp, pChain, pSurvivorField, null)}'s "any position, including none" wildcard. */
	private static boolean hasAppendOp(final ArrayNode pOps, final String pOp, final String pChain, final String pSurvivorField)
	{
		for (final JsonNode op : pOps)
		{
			if (!pOp.equals(op.path("op").asText()))
			{
				continue;
			}
			final JsonNode target = op.path("target");
			final boolean chainMatches = pChain == null || pChain.equals(target.path("chain").asText(null));
			final boolean survivorMatches = pSurvivorField == null ? !target.has("survivorField") : pSurvivorField.equals(target.path("survivorField").asText(null));
			if (chainMatches && survivorMatches && !target.has("position"))
			{
				return true;
			}
		}
		return false;
	}

	/** True only if the ops list carries at least one {@code add_survivor_field} and EVERY one of them has a textual (bare function-name STRING) {@code value} - the live contract's own domain, never the {@code {mergeFunctionChain:[...]}} envelope this class used to send. */
	private static boolean everyAddSurvivorFieldValueIsAString(final ArrayNode pOps)
	{
		boolean sawOne = false;
		for (final JsonNode op : pOps)
		{
			if ("add_survivor_field".equals(op.path("op").asText()))
			{
				sawOne = true;
				if (!op.path("value").isTextual())
				{
					return false;
				}
			}
		}
		return sawOne;
	}

	private static int countOps(final ArrayNode pOps, final String pOp)
	{
		int count = 0;
		for (final JsonNode op : pOps)
		{
			if (pOp.equals(op.path("op").asText()))
			{
				count++;
			}
		}
		return count;
	}

	private static boolean removalOrderDescending(final ArrayNode pOps)
	{
		int lastPosition = Integer.MAX_VALUE;
		boolean sawOne = false;
		for (final JsonNode op : pOps)
		{
			if ("remove_chain_entry".equals(op.path("op").asText()) && "record_selection".equals(op.path("target").path("chain").asText()))
			{
				sawOne = true;
				final int position = op.path("target").path("position").asInt();
				if (position >= lastPosition)
				{
					return false;
				}
				lastPosition = position;
			}
		}
		return sawOne;
	}

	private static JsonNode parse(final String pJson)
	{
		try
		{
			return MergeConfigPatchPlannerTest.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
