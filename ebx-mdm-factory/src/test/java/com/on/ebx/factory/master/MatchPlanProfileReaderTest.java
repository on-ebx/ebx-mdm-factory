package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Fixtures are the
 * {@code match-merge} MCP server's own response shapes, hand-built with placeholder values (never a real
 * one) - both {@code profile_table_for_matching}'s shape and {@code matching_config_schema}'s were captured
 * live by the coordinator (2026-09-21; the schema shape on a SECOND review, after the first revision's
 * guessed flat keys turned out not to exist - `docs/MATCHING.md` &sect;4.2.1 has the full story), quoted in
 * full in this class' fixtures.
 */
public final class MatchPlanProfileReaderTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanProfileReaderTest.exactField_fullStats();
		MatchPlanProfileReaderTest.sampledCardinality_readsButProvenanceSaysSampled();
		MatchPlanProfileReaderTest.notSupportedToken_readsNullNotAPlaceholder();
		MatchPlanProfileReaderTest.technicalFieldExcludedFromFieldsByName();
		MatchPlanProfileReaderTest.softMissResponse_rowCountAndFieldsAreEmptyAndDescribed();
		MatchPlanProfileReaderTest.vocabulary_readsRealNestedShape_stringWrappedData();
		MatchPlanProfileReaderTest.vocabulary_rc3TwoEnums_treeAndPolicyFieldReadSeparately();
		MatchPlanProfileReaderTest.vocabulary_olderSingleEnum_servesBoth();
		MatchPlanProfileReaderTest.resolveSchemaData_alreadyAnObject_passesThrough();
		MatchPlanProfileReaderTest.resolveSchemaData_malformedString_degradesToEmptyRatherThanThrow();
		MatchPlanProfileReaderTest.vocabulary_failsNamingTheExactMissingPath();
		MatchPlanProfileReaderTest.shortenI18nKey_recoversTheMiddleSegment();
		MatchPlanProfileReaderTest.fieldCapabilities_emptyAvailableMeansIncapable();
		MatchPlanProfileReaderTest.fieldCapabilities_absentNodeYieldsEmptyMap();
		MatchPlanProfileReaderTest.fieldCapabilities_capturedLiveShape_foundForName();
		MatchPlanProfileReaderTest.describeSoftMiss_surfacesMessageAndNextStep();
		MatchPlanProfileReaderTest.findFieldsMissingCapability_everyFieldCapable_emptyResult();
		MatchPlanProfileReaderTest.findFieldsMissingCapability_reportsEmptyAvailableAndAbsentField();
		MatchPlanProfileReaderTest.matchKeyField_readLikeAnyOtherField();
		System.out.println("MatchPlanProfileReaderTest OK");
	}

	// -------------------------------------------------------------------------------------- profile_table_for_matching

	/** The coordinator's own live example (docs/MATCHING.md), placeholders instead of the real value/token. */
	private static JsonNode profileResponse()
	{
		return MatchPlanProfileReaderTest.parse("{\"success\":true,\"data\":{\"table\":\"/root/Company\",\"rowCount\":107,\"fields\":["
			+ "{\"field\":\"/name\",\"type\":\"TypeName[xs:string]@23cef7c6\",\"searchStrategy\":\"@Name\",\"fillRate\":1,\"cardinality\":103,"
			+ "\"mostFrequentValue\":\"SENTINEL_VALUE_NEVER_REAL\",\"mostFrequentValueCount\":2,\"mostFrequentToken\":\"SENTINEL_TOKEN_NEVER_REAL\",\"mostFrequentTokenDocFrequency\":23,"
			+ "\"provenance\":{\"cardinality\":\"EXACT\",\"fillRate\":\"EXACT\",\"mostFrequentToken\":\"EXACT\",\"mostFrequentValue\":\"EXACT\"}},"
			+ "{\"field\":\"/vatNo\",\"type\":\"TypeName[xs:string]@1\",\"searchStrategy\":\"@Name\",\"fillRate\":0.4392523364485981,\"cardinality\":46,"
			+ "\"mostFrequentValue\":null,\"mostFrequentValueCount\":null,\"mostFrequentToken\":null,\"mostFrequentTokenDocFrequency\":null,"
			+ "\"provenance\":{\"cardinality\":\"SAMPLED\",\"fillRate\":\"EXACT\",\"mostFrequentToken\":\"NOT_SUPPORTED\",\"mostFrequentValue\":\"NOT_SUPPORTED\"}},"
			+ "{\"field\":\"/id\",\"type\":\"TypeName[xs:int]@3bf39448\",\"searchStrategy\":\"@Integer\",\"fillRate\":1,\"cardinality\":107,"
			+ "\"mostFrequentValue\":\"108\",\"mostFrequentValueCount\":1,\"mostFrequentToken\":null,\"mostFrequentTokenDocFrequency\":null,"
			+ "\"provenance\":{\"cardinality\":\"EXACT\",\"fillRate\":\"EXACT\",\"mostFrequentToken\":\"NOT_SUPPORTED\",\"mostFrequentValue\":\"EXACT\"}},"
			+ "{\"field\":\"/sourceSystem\",\"type\":\"TypeName[xs:string]@2\",\"searchStrategy\":\"@Name\",\"fillRate\":1,\"cardinality\":2,"
			+ "\"mostFrequentValue\":\"ERP\",\"mostFrequentValueCount\":55,\"mostFrequentToken\":\"ERP\",\"mostFrequentTokenDocFrequency\":55,"
			+ "\"provenance\":{\"cardinality\":\"EXACT\",\"fillRate\":\"EXACT\",\"mostFrequentToken\":\"EXACT\",\"mostFrequentValue\":\"EXACT\"}}"
			+ "]}}");
	}

	private static void exactField_fullStats()
	{
		final JsonNode data = MatchPlanProfileReaderTest.profileResponse().path("data");
		MatchPlanProfileReaderTest.check("rowCount read", MatchPlanProfileReader.rowCount(data) == 107);
		final Map<String, JsonNode> fields = MatchPlanProfileReader.fieldsByName(data, new LinkedHashSet<String>());
		final MatchPlanProfileReader.FieldStats name = MatchPlanProfileReader.readFieldStats(fields.get("name"));
		MatchPlanProfileReaderTest.check("fillRate 1.0", name.fillRate.doubleValue() == 1.0);
		MatchPlanProfileReaderTest.check("fillRate provenance EXACT", "EXACT".equals(name.fillRateProvenance));
		MatchPlanProfileReaderTest.check("cardinality 103", name.distinctCount.intValue() == 103);
		MatchPlanProfileReaderTest.check("cardinality provenance EXACT", "EXACT".equals(name.cardinalityProvenance));
		MatchPlanProfileReaderTest.check("mostFrequentValue read raw by this pure reader (gating happens elsewhere - see class Javadoc)",
			"SENTINEL_VALUE_NEVER_REAL".equals(name.mostFrequentValue));
		MatchPlanProfileReaderTest.check("mostFrequentValueCount is a plain number", name.mostFrequentValueCount.intValue() == 2);
	}

	/** {@code vatNo}: cardinality is only SAMPLED - a lower bound, never safe to divide by row count. Still read, provenance says so. */
	private static void sampledCardinality_readsButProvenanceSaysSampled()
	{
		final JsonNode data = MatchPlanProfileReaderTest.profileResponse().path("data");
		final Map<String, JsonNode> fields = MatchPlanProfileReader.fieldsByName(data, new LinkedHashSet<String>());
		final MatchPlanProfileReader.FieldStats vatNo = MatchPlanProfileReader.readFieldStats(fields.get("vatNo"));
		MatchPlanProfileReaderTest.check("cardinality still read as a number", vatNo.distinctCount.intValue() == 46);
		MatchPlanProfileReaderTest.check("cardinality provenance is SAMPLED, not EXACT", "SAMPLED".equals(vatNo.cardinalityProvenance));
		MatchPlanProfileReaderTest.check("fillRate provenance is independently EXACT (each statistic has its own provenance)", "EXACT".equals(vatNo.fillRateProvenance));

		final MatchPlanLlmPass.AttributeInfo attribute = new MatchPlanLlmPass.AttributeInfo("vatNo", "VAT", "STRING", 34, null, vatNo.fillRate, vatNo.fillRateProvenance,
			vatNo.distinctCount, vatNo.cardinalityProvenance, null, null, null, null, true, null, java.util.Collections.<String>emptyList(),
			java.util.Collections.<String>emptyList());
		MatchPlanProfileReaderTest.check("cardinalityReliable() is false for a SAMPLED statistic - a guard must never divide this by row count",
			!attribute.cardinalityReliable());
		MatchPlanProfileReaderTest.check("fillRateReliable() is true (its own provenance is EXACT)", attribute.fillRateReliable());
	}

	/** {@code vatNo}'s {@code mostFrequentToken} is {@code NOT_SUPPORTED} and the response carries {@code null} - must read back null, never a placeholder or the id field's own token. */
	private static void notSupportedToken_readsNullNotAPlaceholder()
	{
		final JsonNode data = MatchPlanProfileReaderTest.profileResponse().path("data");
		final Map<String, JsonNode> fields = MatchPlanProfileReader.fieldsByName(data, new LinkedHashSet<String>());
		final MatchPlanProfileReader.FieldStats vatNo = MatchPlanProfileReader.readFieldStats(fields.get("vatNo"));
		MatchPlanProfileReaderTest.check("mostFrequentToken is null (NOT_SUPPORTED)", vatNo.mostFrequentToken == null);
		MatchPlanProfileReaderTest.check("mostFrequentTokenDocFrequency is null too", vatNo.mostFrequentTokenDocFrequency == null);

		// the /id field: EXACT cardinality but its own mostFrequentToken is NOT_SUPPORTED (an int has no tokenization) - independent per-statistic provenance
		final MatchPlanProfileReader.FieldStats id = MatchPlanProfileReader.readFieldStats(data.path("fields").get(2));
		MatchPlanProfileReaderTest.check("id's mostFrequentToken is null despite mostFrequentValue being EXACT", id.mostFrequentToken == null && id.mostFrequentValue != null);
	}

	/** A technical field ({@code /id}, {@code /sourceSystem}, ...) must never reach a caller that only wants business attributes. */
	private static void technicalFieldExcludedFromFieldsByName()
	{
		final JsonNode data = MatchPlanProfileReaderTest.profileResponse().path("data");
		final Set<String> technical = new LinkedHashSet<String>(Arrays.asList("id", "sourceSystem", "sourceId", "_loadId", "_receivedAt", "_status"));
		final Map<String, JsonNode> fields = MatchPlanProfileReader.fieldsByName(data, technical);
		MatchPlanProfileReaderTest.check("business fields present", fields.containsKey("name") && fields.containsKey("vatNo"));
		MatchPlanProfileReaderTest.check("id excluded", !fields.containsKey("id"));
		MatchPlanProfileReaderTest.check("sourceSystem excluded", !fields.containsKey("sourceSystem"));
	}

	/**
	 * 2026-09-22 matching-key contract: a derived matching key (e.g. {@code cityKey}) is a plain field on the
	 * master table, so {@code profile_table_for_matching}'s response covers it exactly like any other business
	 * attribute - {@code fieldsByName}/{@code readFieldStats} need no special-casing at all, proven here with a
	 * standalone fixture (the shape master profiling would return once the key column exists).
	 */
	private static void matchKeyField_readLikeAnyOtherField()
	{
		final JsonNode data = MatchPlanProfileReaderTest.parse("{\"rowCount\":100,\"fields\":["
			+ "{\"field\":\"/cityKey\",\"fillRate\":0.99,\"cardinality\":40,\"mostFrequentValue\":null,\"mostFrequentValueCount\":null,"
			+ "\"mostFrequentToken\":null,\"mostFrequentTokenDocFrequency\":null,"
			+ "\"provenance\":{\"cardinality\":\"EXACT\",\"fillRate\":\"EXACT\",\"mostFrequentToken\":\"NOT_SUPPORTED\",\"mostFrequentValue\":\"NOT_SUPPORTED\"}}]}");
		final Map<String, JsonNode> fields = MatchPlanProfileReader.fieldsByName(data, new LinkedHashSet<String>());
		MatchPlanProfileReaderTest.check("cityKey is present, indexed by its bare (slash-stripped) name", fields.containsKey("cityKey"));
		final MatchPlanProfileReader.FieldStats stats = MatchPlanProfileReader.readFieldStats(fields.get("cityKey"));
		MatchPlanProfileReaderTest.check("fillRate read like any other field", stats.fillRate.doubleValue() == 0.99);
		MatchPlanProfileReaderTest.check("cardinality read like any other field", stats.distinctCount.intValue() == 40);
		MatchPlanProfileReaderTest.check("cardinality provenance EXACT", "EXACT".equals(stats.cardinalityProvenance));
	}

	/** A soft-miss response ({@code success:false}) carries no usable {@code data} - the reader must not crash reading rowCount/fields off it, and {@link MatchPlanProfileReader#describeSoftMiss} must surface the real {@code message}/{@code nextStep} (measured live shape), not a guessed hint. */
	private static void softMissResponse_rowCountAndFieldsAreEmptyAndDescribed()
	{
		final JsonNode softMiss = MatchPlanProfileReaderTest
			.parse("{\"success\":false,\"code\":\"TABLE_NOT_FOUND\",\"message\":\"Table not found: /root/Company\",\"nextStep\":\"Run master/generate then master/load.\"}");
		final JsonNode missingData = softMiss.path("data"); // MissingNode, not null - the same shape a caller gets if it forgets to check success first
		MatchPlanProfileReaderTest.check("rowCount defaults to 0 on a missing data node, never throws", MatchPlanProfileReader.rowCount(missingData) == 0);
		MatchPlanProfileReaderTest.check("fieldsByName is empty, never throws", MatchPlanProfileReader.fieldsByName(missingData, new LinkedHashSet<String>()).isEmpty());
		final String described = MatchPlanProfileReader.describeSoftMiss(softMiss);
		MatchPlanProfileReaderTest.check("describeSoftMiss surfaces the code", described.contains("TABLE_NOT_FOUND"));
		MatchPlanProfileReaderTest.check("describeSoftMiss surfaces the real message", described.contains("Table not found: /root/Company"));
		MatchPlanProfileReaderTest.check("describeSoftMiss surfaces the real nextStep", described.contains("Run master/generate then master/load."));
	}

	// ------------------------------------------------------------------------------------------- matching_config_schema

	/**
	 * The REAL shape, measured live by the coordinator 2026-09-21 (second revision - see class Javadoc): a
	 * JSON Schema of {@code MameConfig}, with the enums this class needs at deep, specific paths - AND the
	 * whole document is a JSON-encoded STRING inside {@code data} (unlike {@code profile_table_for_matching},
	 * whose {@code data} is already an object) - {@link MatchPlanProfileReader#resolveSchemaData} must unwrap
	 * it before {@link MatchPlanProfileReader#readVocabulary} can read anything.
	 */
	private static JsonNode schemaResponse()
	{
		// the shape of the add-on builds up to 2026-09-27: ONE null-handling definition, shared by the tree fields
		return MatchPlanProfileReaderTest.schemaResponse(new String[] { "DO_NOTHING", "NOT_MATCH", "MATCH" }, null);
	}

	/**
	 * @param pTreeNull the enum of {@code decisionTree.fields[].bothNullValueHandling/oneNullValueHandling}
	 * @param pPolicyFieldNull when non-null, the enum of {@code matchingPolicies[].fields[].bothNullValueHandling/oneNullValueHandling} (add-on build 6.3.0.5-RC3
	 *            of 2026-10-10 has both: tree {@code MATCH, NOT_MATCH}, policy field {@code DO_NOTHING, NOT_MATCH, MATCH}); null = the older single-enum shape
	 */
	private static JsonNode schemaResponse(final String[] pTreeNull, final String[] pPolicyFieldNull)
	{
		final ObjectNode fieldProperties = MatchPlanProfileReaderTest.MAPPER.createObjectNode();
		MatchPlanProfileReaderTest.putEnum(fieldProperties.putObject("algorithm"), "ebx-addon-mame@exact", "ebx-addon-mame@jarowinkler", "ebx-addon-mame@levenshtein",
			"ebx-addon-mame@beider-morse", "ebx-addon-mame@soundex", "ebx-addon-mame@range", "ebx-addon-mame@operator", "ebx-addon-mame@hybridFuzzy",
			"ebx-addon-mame@fuzzyFullText", "ebx-addon-mame@exactFullText", "ebx-addon-mame@phoneticFullText");
		MatchPlanProfileReaderTest.putEnum(fieldProperties.putObject("bothNullValueHandling"), pTreeNull);
		MatchPlanProfileReaderTest.putEnum(fieldProperties.putObject("oneNullValueHandling"), pTreeNull);

		final ObjectNode decisionTreeProperties = MatchPlanProfileReaderTest.MAPPER.createObjectNode();
		decisionTreeProperties.putObject("fields").putObject("items").set("properties", fieldProperties);
		MatchPlanProfileReaderTest.putEnum(decisionTreeProperties.putObject("function"), "All fields match", "At least N fields match", "Average", "Predicate");
		decisionTreeProperties.putObject("trueNode").putObject("properties").putObject("name").set("enum",
			MatchPlanProfileReaderTest.MAPPER.createArrayNode().add("MATCHED").add("SUSPECTED").add("NOT_MATCHED"));

		final ObjectNode matchingPolicyProperties = MatchPlanProfileReaderTest.MAPPER.createObjectNode();
		matchingPolicyProperties.putObject("decisionTree").set("properties", decisionTreeProperties);
		if (pPolicyFieldNull != null)
		{
			final ObjectNode policyFieldProperties = matchingPolicyProperties.putObject("fields").putObject("items").putObject("properties");
			MatchPlanProfileReaderTest.putEnum(policyFieldProperties.putObject("bothNullValueHandling"), pPolicyFieldNull);
			MatchPlanProfileReaderTest.putEnum(policyFieldProperties.putObject("oneNullValueHandling"), pPolicyFieldNull);
		}

		final ObjectNode mergePolicyProperties = MatchPlanProfileReaderTest.MAPPER.createObjectNode();
		final ArrayNode recordSelectionEnum = MatchPlanProfileReaderTest.MAPPER.createArrayNode();
		for (final String key : new String[] { "firstAcquired", "lastUpdate", "mostCompleted", "mostRecentlyAcquired", "mostTrustedSource", "wasGolden" })
		{
			recordSelectionEnum.add("mame.addon.reference.data.recordSelectionPolicy." + key + ".label");
		}
		mergePolicyProperties.putObject("recordSelectionFunctionChain").putObject("items").putObject("properties").putObject("function").set("enum", recordSelectionEnum);
		MatchPlanProfileReaderTest.putEnum(
			mergePolicyProperties.putObject("defaultFieldMergeFunctionChain").putObject("items").putObject("properties").putObject("function"), "last update", "longest", "max",
			"min", "most frequent", "most trusted source");
		MatchPlanProfileReaderTest.putEnum(mergePolicyProperties.putObject("mode"), "DISABLED", "DUPLICATES_AND_SINGLETONS", "ONLY_DUPLICATES", "ANY_DUPLICATES");

		final ObjectNode properties = MatchPlanProfileReaderTest.MAPPER.createObjectNode();
		properties.putObject("matchingPolicies").putObject("items").set("properties", matchingPolicyProperties);
		properties.putObject("mergePolicies").putObject("items").set("properties", mergePolicyProperties);

		final ObjectNode schemaDocument = MatchPlanProfileReaderTest.MAPPER.createObjectNode();
		schemaDocument.set("properties", properties);
		schemaDocument.putObject("patchOps");
		schemaDocument.putObject("errorCodes");
		schemaDocument.putObject("messageCodes");

		final ObjectNode response = MatchPlanProfileReaderTest.MAPPER.createObjectNode();
		response.put("success", true);
		response.put("data", schemaDocument.toString()); // string-wrapped, the measured real shape
		return response;
	}

	private static void putEnum(final ObjectNode pNode, final String... pValues)
	{
		final ArrayNode array = pNode.putArray("enum");
		for (final String value : pValues)
		{
			array.add(value);
		}
	}

	private static void vocabulary_readsRealNestedShape_stringWrappedData()
	{
		final JsonNode response = MatchPlanProfileReaderTest.schemaResponse();
		MatchPlanProfileReaderTest.check("data is indeed a JSON string in this fixture, matching the measured shape", response.path("data").isTextual());
		final JsonNode resolved = MatchPlanProfileReader.resolveSchemaData(response);
		MatchPlanProfileReaderTest.check("resolveSchemaData parses the string into a real object", resolved.isObject() && resolved.has("properties"));

		final MatchPlanProfileReader.VocabularyResult result = MatchPlanProfileReader.readVocabulary(resolved);
		MatchPlanProfileReaderTest.check("vocabulary found, no error", result.vocabulary != null && result.error == null);
		MatchPlanProfileReaderTest.check("all 11 algorithms read, correctly prefixed",
			result.vocabulary.algorithms.size() == 11 && result.vocabulary.algorithms.contains("ebx-addon-mame@exact")
				&& result.vocabulary.algorithms.contains("ebx-addon-mame@hybridFuzzy") && result.vocabulary.algorithms.contains("ebx-addon-mame@operator"));
		MatchPlanProfileReaderTest.check("null handling reads all three (DO_NOTHING, NOT_MATCH, MATCH)",
			result.vocabulary.nullHandlings.containsAll(Arrays.asList("DO_NOTHING", "NOT_MATCH", "MATCH")));
		MatchPlanProfileReaderTest.check("record selections are SHORTENED from their raw i18n keys",
			result.vocabulary.recordSelections.containsAll(Arrays.asList("firstAcquired", "lastUpdate", "mostCompleted", "mostRecentlyAcquired", "mostTrustedSource", "wasGolden"))
				&& !result.vocabulary.recordSelections.get(0).contains("."));
	}

	/** Add-on build 6.3.0.5-RC3 (2026-10-10): the tree enum is {@code MATCH, NOT_MATCH}, the matching-policy-field one still {@code DO_NOTHING, NOT_MATCH, MATCH}. */
	private static void vocabulary_rc3TwoEnums_treeAndPolicyFieldReadSeparately()
	{
		final JsonNode resolved = MatchPlanProfileReader
			.resolveSchemaData(MatchPlanProfileReaderTest.schemaResponse(new String[] { "MATCH", "NOT_MATCH" }, new String[] { "DO_NOTHING", "NOT_MATCH", "MATCH" }));
		final MatchPlanProfileReader.VocabularyResult result = MatchPlanProfileReader.readVocabulary(resolved);
		MatchPlanProfileReaderTest.check("vocabulary read", result.vocabulary != null && result.error == null);
		MatchPlanProfileReaderTest.check("tree enum is exactly MATCH, NOT_MATCH", result.vocabulary.treeNullHandlings.equals(Arrays.asList("MATCH", "NOT_MATCH")));
		MatchPlanProfileReaderTest.check("policy-field enum keeps DO_NOTHING", result.vocabulary.nullHandlings.equals(Arrays.asList("DO_NOTHING", "NOT_MATCH", "MATCH")));
		MatchPlanProfileReaderTest.check("the vocabulary says the tree forbids DO_NOTHING", result.vocabulary.treeForbidsDoNothing());
		MatchPlanProfileReaderTest.check("a prompt note is produced and names the tree values",
			result.vocabulary.promptNote() != null && result.vocabulary.promptNote().contains("MATCH, NOT_MATCH") && result.vocabulary.promptNote().contains("DO_NOTHING"));
		MatchPlanProfileReaderTest.check("record selections and algorithms still read", result.vocabulary.algorithms.size() == 11 && result.vocabulary.recordSelections.contains("mostCompleted"));
		MatchPlanProfileReaderTest.check("withRecordSelections keeps both enums", result.vocabulary.withRecordSelections(Arrays.asList("lastUpdate")).treeNullHandlings
			.equals(Arrays.asList("MATCH", "NOT_MATCH")));
	}

	/** Builds before RC3: the schema has the tree definition only - it serves for the policy fields too, and nothing is forbidden. */
	private static void vocabulary_olderSingleEnum_servesBoth()
	{
		final MatchPlanProfileReader.VocabularyResult result = MatchPlanProfileReader.readVocabulary(MatchPlanProfileReader.resolveSchemaData(MatchPlanProfileReaderTest.schemaResponse()));
		MatchPlanProfileReaderTest.check("vocabulary read", result.vocabulary != null);
		MatchPlanProfileReaderTest.check("one enum for both", result.vocabulary.treeNullHandlings.equals(result.vocabulary.nullHandlings)
			&& result.vocabulary.treeNullHandlings.contains("DO_NOTHING"));
		MatchPlanProfileReaderTest.check("DO_NOTHING allowed on a tree field, no prompt note", !result.vocabulary.treeForbidsDoNothing() && result.vocabulary.promptNote() == null);
		// a schema carrying ONLY the policy-field enum (tree path absent) is not an error either: it serves both
		final ObjectNode only = (ObjectNode) MatchPlanProfileReaderTest.parse("{\"properties\":{\"matchingPolicies\":{\"items\":{\"properties\":{\"decisionTree\":{\"properties\":{\"fields\":{\"items\":{\"properties\":"
			+ "{\"algorithm\":{\"enum\":[\"ebx-addon-mame@exact\"]}}}}}},\"fields\":{\"items\":{\"properties\":{\"bothNullValueHandling\":{\"enum\":[\"DO_NOTHING\",\"NOT_MATCH\"]}}}}}}},"
			+ "\"mergePolicies\":{\"items\":{\"properties\":{\"recordSelectionFunctionChain\":{\"items\":{\"properties\":{\"function\":{\"enum\":[\"mame.addon.reference.data.recordSelectionPolicy.mostCompleted.label\"]}}}}}}}}}");
		final MatchPlanProfileReader.VocabularyResult policyOnly = MatchPlanProfileReader.readVocabulary(only);
		MatchPlanProfileReaderTest.check("policy-only schema: vocabulary read, same list for the tree", policyOnly.vocabulary != null
			&& policyOnly.vocabulary.treeNullHandlings.equals(Arrays.asList("DO_NOTHING", "NOT_MATCH")));
	}

	/** {@code profile_table_for_matching}'s own {@code data} is already an object - {@code resolveSchemaData} must leave it untouched, not try to parse an object as a string. */
	private static void resolveSchemaData_alreadyAnObject_passesThrough()
	{
		final JsonNode response = MatchPlanProfileReaderTest.parse("{\"success\":true,\"data\":{\"rowCount\":107}}");
		final JsonNode resolved = MatchPlanProfileReader.resolveSchemaData(response);
		MatchPlanProfileReaderTest.check("object data is returned as is", resolved.path("rowCount").asInt() == 107);
	}

	/** A malformed (non-JSON) string in {@code data} must degrade to an empty document rather than throw - the caller then simply gets "path not found" from every lookup. */
	private static void resolveSchemaData_malformedString_degradesToEmptyRatherThanThrow()
	{
		final JsonNode response = MatchPlanProfileReaderTest.parse("{\"success\":true,\"data\":\"not valid json at all {{{\"}");
		final JsonNode resolved = MatchPlanProfileReader.resolveSchemaData(response);
		final MatchPlanProfileReader.VocabularyResult result = MatchPlanProfileReader.readVocabulary(resolved);
		MatchPlanProfileReaderTest.check("no vocabulary, but no exception thrown either", result.vocabulary == null && result.error != null);
	}

	/** The algorithm path is missing: fails naming that EXACT path, not a list of guessed candidates (the earlier revision's design). */
	private static void vocabulary_failsNamingTheExactMissingPath()
	{
		final JsonNode empty = MatchPlanProfileReaderTest.parse("{}");
		final MatchPlanProfileReader.VocabularyResult result = MatchPlanProfileReader.readVocabulary(empty);
		MatchPlanProfileReaderTest.check("no vocabulary produced", result.vocabulary == null);
		MatchPlanProfileReaderTest.check("error names the exact measured path",
			result.error != null && result.error.contains("properties.matchingPolicies.items.properties.decisionTree.properties.fields.items.properties.algorithm.enum"));
	}

	private static void shortenI18nKey_recoversTheMiddleSegment()
	{
		MatchPlanProfileReaderTest.check("recovers mostCompleted",
			"mostCompleted".equals(MatchPlanProfileReader.shortenI18nKey("mame.addon.reference.data.recordSelectionPolicy.mostCompleted.label")));
		MatchPlanProfileReaderTest.check("a key with no dots is returned as is", "bare".equals(MatchPlanProfileReader.shortenI18nKey("bare")));
	}

	/**
	 * Tolerance case only: a root-level {@code fields} array with no {@code fieldCapabilities} wrapper is NOT
	 * the measured shape (see {@link #fieldCapabilities_capturedLiveShape_foundForName} for that) - it is kept
	 * working as a fallback in case a future server build ever flattens the document (class Javadoc).
	 */
	private static void fieldCapabilities_emptyAvailableMeansIncapable()
	{
		final JsonNode data = MatchPlanProfileReaderTest.parse("{\"fields\":[{\"field\":\"/name\",\"available\":[\"@Name\"]},{\"field\":\"/notes\",\"available\":[]}]}");
		final Map<String, Boolean> capabilities = MatchPlanProfileReader.readFieldCapabilities(data);
		MatchPlanProfileReaderTest.check("a field with a non-empty available list is capable", Boolean.TRUE.equals(capabilities.get("name")));
		MatchPlanProfileReaderTest.check("a field with an empty available list is not", Boolean.FALSE.equals(capabilities.get("notes")));
	}

	/** No {@code fields} node at all (a genuine soft miss, e.g. {@code TABLE_NOT_FOUND}, resolved to an empty object by {@link MatchPlanProfileReader#resolveSchemaData}): an empty map, never a failure - the caller defaults every field to capable. */
	private static void fieldCapabilities_absentNodeYieldsEmptyMap()
	{
		final JsonNode data = MatchPlanProfileReaderTest.parse("{\"success\":false,\"code\":\"TABLE_NOT_FOUND\"}");
		MatchPlanProfileReaderTest.check("empty map, not a crash, when fields is absent", MatchPlanProfileReader.readFieldCapabilities(data).isEmpty());
	}

	/**
	 * The EXACT shape captured live by the coordinator, 2026-09-21 22:1x (class Javadoc, second correction):
	 * {@code matching_config_schema(table="/root/Company")}'s {@code data} (a JSON string, resolved first here
	 * exactly like a real caller would) parses to a document whose {@code fieldCapabilities} OBJECT (itself
	 * carrying {@code description}/{@code table} siblings) nests the actual {@code fields} array - there is NO
	 * top-level {@code fields} array in this document. The intervening {@code fix/validate-response-shape}
	 * revision of 2026-09-22 morning read a root {@code fields} array instead (on a wrong claim about this
	 * shape) and returned an empty map for this exact document, which is the live symptom quoted in the class
	 * Javadoc: {@code preClusteringCapabilityNotVerified: ["Company: matching_config_schema(table=...)
	 * returned no fieldCapabilities"]} even though the server genuinely answered capabilities for {@code
	 * /name}.
	 */
	private static void fieldCapabilities_capturedLiveShape_foundForName() throws Exception
	{
		final ObjectNode response = MatchPlanProfileReaderTest.MAPPER.createObjectNode();
		response.put("success", true);
		response.put("data", "{\"fieldCapabilities\":{\"description\":\"Pre-clustering search strategies available per field\",\"table\":\"/root/Company\",\"fields\":["
			+ "{\"field\":\"/name\",\"available\":[{\"searchStrategy\":\"@default\",\"resolvesTo\":\"@Name\"}],\"default\":\"@default\"},"
			+ "{\"field\":\"/city\",\"available\":[],\"default\":null}]}}");
		final JsonNode resolved = MatchPlanProfileReader.resolveSchemaData(response);
		final Map<String, Boolean> capabilities = MatchPlanProfileReader.readFieldCapabilities(resolved);
		MatchPlanProfileReaderTest.check("fieldCapabilities.fields found for /name (the bug used to report an empty map here)", Boolean.TRUE.equals(capabilities.get("name")));
		MatchPlanProfileReaderTest.check("an empty available list still reads as incapable", Boolean.FALSE.equals(capabilities.get("city")));
		MatchPlanProfileReaderTest.check("not empty overall - checkFieldCapabilities' own 'not verified' branch must NOT fire on this document", !capabilities.isEmpty());
	}

	/** {@code master.MatchingPolicyTranslator}'s own output shape - leading-slash field paths - checked against a fully-capable map. */
	private static void findFieldsMissingCapability_everyFieldCapable_emptyResult()
	{
		final ObjectNode policy = MatchPlanProfileReaderTest.MAPPER.createObjectNode();
		final ArrayNode fields = policy.putArray("fields");
		fields.addObject().put("field", "/city");
		fields.addObject().put("field", "/country");
		final Map<String, Boolean> capabilities = new LinkedHashMap<String, Boolean>();
		capabilities.put("city", Boolean.TRUE);
		capabilities.put("country", Boolean.TRUE);
		MatchPlanProfileReaderTest
			.check("no missing capability when every declared field is capable", MatchPlanProfileReader.findFieldsMissingCapability(policy, capabilities).isEmpty());
	}

	/** One field absent from the capabilities map, one present but with an empty {@code available} list (i.e. {@code Boolean.FALSE}) - both reported, by their bare name. */
	private static void findFieldsMissingCapability_reportsEmptyAvailableAndAbsentField()
	{
		final ObjectNode policy = MatchPlanProfileReaderTest.MAPPER.createObjectNode();
		final ArrayNode fields = policy.putArray("fields");
		fields.addObject().put("field", "/city");
		fields.addObject().put("field", "/notes");
		final Map<String, Boolean> capabilities = new LinkedHashMap<String, Boolean>();
		capabilities.put("notes", Boolean.FALSE);
		// "city" is deliberately absent from the map at all.
		final List<String> missing = MatchPlanProfileReader.findFieldsMissingCapability(policy, capabilities);
		MatchPlanProfileReaderTest.check("both fields reported", missing.size() == 2);
		MatchPlanProfileReaderTest.check("absent field named", missing.get(0).startsWith("city"));
		MatchPlanProfileReaderTest.check("empty-available field named", missing.get(1).startsWith("notes"));
	}

	private static void describeSoftMiss_surfacesMessageAndNextStep()
	{
		final JsonNode softMiss = MatchPlanProfileReaderTest
			.parse("{\"success\":false,\"code\":\"INSUFFICIENT_RIGHTS\",\"message\":\"Agent account cannot read this table\",\"nextStep\":\"Grant read rights.\"}");
		final String described = MatchPlanProfileReader.describeSoftMiss(softMiss);
		MatchPlanProfileReaderTest.check("code present", described.contains("INSUFFICIENT_RIGHTS"));
		MatchPlanProfileReaderTest.check("message present", described.contains("Agent account cannot read this table"));
		MatchPlanProfileReaderTest.check("nextStep present", described.contains("Grant read rights."));

		final JsonNode noExtras = MatchPlanProfileReaderTest.parse("{\"success\":false,\"code\":\"UNKNOWN_THING\"}");
		MatchPlanProfileReaderTest.check("a code alone, no message/nextStep, still describes cleanly", "UNKNOWN_THING".equals(MatchPlanProfileReader.describeSoftMiss(noExtras)));
	}

	// --------------------------------------------------------------------------------------------------------- helpers

	private static JsonNode parse(final String pJson)
	{
		try
		{
			return MatchPlanProfileReaderTest.MAPPER.readTree(pJson);
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
