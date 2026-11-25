package com.on.ebx.factory.master;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pure parsing of the {@code match-merge} MCP server's own JSON responses ({@code
 * profile_table_for_matching}, {@code matching_config_schema}) into the plain shapes {@link
 * MatchPlanLlmPass} and {@code ebx.MatchPlanLlmSuggester} need - no EBX class anywhere in this file, the
 * same convention every other pure class in this package follows, so this parsing is unit-tested with hand-
 * built JSON fixtures rather than a live MCP call (`docs/MATCHING.md`, coordinator review 2026-09-21:
 * neither the live instance nor a real language model may be called from this branch).
 * <p>
 * <b>This is a pure JSON-extraction utility, not a security boundary.</b> {@link #readFieldStats} always
 * extracts whatever {@code mostFrequentValue}/{@code mostFrequentToken} the response carries, regardless of
 * the project's value-sharing setting - the "never a real value unless the project opted in" guarantee is
 * enforced exactly once, at the one place an {@link MatchPlanLlmPass.AttributeInfo} is actually built from a
 * {@link FieldStats} ({@code ebx.MatchPlanLlmSuggester#toLlmEntity}), not here.
 * <p>
 * <b>{@code matching_config_schema}'s real shape, measured live by the coordinator, 2026-09-21 (second
 * revision - the first revision's guessed flat keys never existed).</b> Two things an earlier revision of
 * this class got wrong, both fixed here:
 * <ul>
 * <li>the response envelope is {@code {"success":true,"data":"<STRING>"}} - unlike {@code
 * profile_table_for_matching}, whose {@code data} is already an object, THIS tool's {@code data} is a JSON
 * document serialized as a STRING that must be parsed a second time ({@link #resolveSchemaData} handles
 * both shapes, so a future change to either tool does not silently break the other);</li>
 * <li>the parsed document is a JSON Schema of {@code MameConfig} (siblings: {@code patchOps}, {@code
 * errorCodes}, {@code messageCodes}, {@code businessObjectConfig}, {@code applyConfig}, {@code
 * mergeChains}), not a flat object with an {@code algorithms}/{@code nullHandlings} key - the enums this
 * class needs live deep inside it, at the exact dotted paths the constants below name. {@link #at} is a
 * small path-walking helper; every lookup fails naming the ONE path it walked, never a guess.</li>
 * </ul>
 * {@link #readFieldCapabilities} reads a DIFFERENT document than the vocabulary above - it is the table-
 * scoped {@code matching_config_schema(table=...)} answer, whose (already-{@link #resolveSchemaData
 * resolved}) {@code data} document carries the field-capabilities document under a {@code fieldCapabilities}
 * key: {@code {..."fieldCapabilities":{"description":...,"table":"/root/Company","fields":[{"field":"/name",
 * "available":[{"searchStrategy":"@default","resolvesTo":"@Name"}],"default":"@default"},...]}}}, captured
 * live by the coordinator 2026-09-21 22:1x and confirmed again 2026-09-22 (`docs/MATCHING.md`). <b>This is
 * the SECOND correction of this method, and the first correction was itself wrong:</b> the original guess
 * read a root {@code fieldCapabilities} key that did not exist; the {@code fix/validate-response-shape}
 * revision of 2026-09-22 morning "corrected" that to a root {@code fields} array on the strength of a claim
 * about the captured shape that turned out to be wrong too - there is no top-level {@code fields} array in
 * the real document, the array is nested one level down at {@code fieldCapabilities.fields}. That second,
 * wrong revision made every {@code preClusteringCapabilityNotVerified} note fire on every table (run 27,
 * 2026-09-22 12:5x), because {@code fieldCapabilities.fields} is never found by a plain root/{@code data}
 * lookup. {@link #fieldCapabilitiesArray} now reads, in order: (1) {@code fieldCapabilities.fields} (the
 * measured shape), (2) a root {@code fields} array (kept as a tolerance case only, in case some future
 * server build ever flattens the document), (3) {@code data.fieldCapabilities.fields} / {@code data.fields}
 * as a last resort, mirroring the {@code data}-or-root double read {@link MameAnswer#field} already applies
 * to every other match-merge answer, even though this particular resolved document has not been observed to
 * carry a further {@code data} wrapper of its own. Absence of every one of these (a genuine soft miss, e.g.
 * {@code TABLE_NOT_FOUND}) is still treated as "no information" (an empty map, so every field defaults
 * {@code preClusteringAvailable=true}) rather than a hard failure.
 */
public final class MatchPlanProfileReader
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Dotted paths into the parsed {@code matching_config_schema} document, measured live 2026-09-21 (see
	 * class Javadoc). {@code matchingPolicies.items.properties.decisionTree.properties.fields.items.
	 * properties} is the schema of one tier FIELD - {@code algorithm}/{@code bothNullValueHandling}/{@code
	 * oneNullValueHandling} all live there (the {@code oneNull...} enum is identical to {@code
	 * bothNull...}'s, not read separately).
	 */
	private static final String ALGORITHM_PATH =
		"properties.matchingPolicies.items.properties.decisionTree.properties.fields.items.properties.algorithm.enum";
	private static final String NULL_HANDLING_PATH =
		"properties.matchingPolicies.items.properties.decisionTree.properties.fields.items.properties.bothNullValueHandling.enum";
	private static final String TREE_ONE_NULL_HANDLING_PATH =
		"properties.matchingPolicies.items.properties.decisionTree.properties.fields.items.properties.oneNullValueHandling.enum";
	/**
	 * The matching-POLICY field enums (pre-processing / candidate fields), a different property from the decision-tree one since the add-on build
	 * 6.3.0.5-RC3 (2026-10-10): {@code [DO_NOTHING, NOT_MATCH, MATCH]} here, {@code [MATCH, NOT_MATCH]} for a tree field. On the builds before it only the tree
	 * path (one shared definition) is guaranteed, so a missing policy path is not an error.
	 */
	private static final String POLICY_FIELD_BOTH_NULL_PATH =
		"properties.matchingPolicies.items.properties.fields.items.properties.bothNullValueHandling.enum";
	private static final String POLICY_FIELD_ONE_NULL_PATH =
		"properties.matchingPolicies.items.properties.fields.items.properties.oneNullValueHandling.enum";
	/** {@code recordSelectionFunctionChain}'s own {@code function} enum: raw i18n keys, e.g. {@code mame.addon.reference.data.recordSelectionPolicy.mostCompleted.label} - {@link #shortenI18nKey} recovers the short middle segment this codebase's {@code requirements} JSON already uses. */
	private static final String RECORD_SELECTION_PATH = "properties.mergePolicies.items.properties.recordSelectionFunctionChain.items.properties.function.enum";

	private MatchPlanProfileReader()
	{
	}

	/**
	 * {@code data} is a plain object for {@code profile_table_for_matching} but a JSON-encoded STRING for
	 * {@code matching_config_schema} (see class Javadoc) - this normalises either shape into the actual
	 * document, or returns a {@code MissingNode}-like empty object if {@code data} is absent or the string
	 * fails to parse (callers then get "path not found" from {@link #at}, not a crash).
	 */
	public static JsonNode resolveSchemaData(final JsonNode pResponseJson)
	{
		final JsonNode data = pResponseJson.path("data");
		if (data.isTextual())
		{
			try
			{
				return MatchPlanProfileReader.MAPPER.readTree(data.asText());
			}
			catch (final IOException ex)
			{
				return MatchPlanProfileReader.MAPPER.createObjectNode();
			}
		}
		return data;
	}

	/** Walks {@code pPath} ({@code "a.b.c"}) through {@code pNode} one segment at a time - a missing segment anywhere along the way simply yields a {@code MissingNode}, exactly like a single {@link JsonNode#path}. */
	static JsonNode at(final JsonNode pNode, final String pPath)
	{
		JsonNode current = pNode;
		for (final String segment : pPath.split("\\."))
		{
			current = current.path(segment);
		}
		return current;
	}

	private static List<String> enumAt(final JsonNode pNode, final String pPath)
	{
		final JsonNode enumNode = MatchPlanProfileReader.at(pNode, pPath);
		if (!enumNode.isArray() || enumNode.size() == 0)
		{
			return null;
		}
		final List<String> out = new ArrayList<String>();
		for (final JsonNode item : enumNode)
		{
			if (item.isTextual())
			{
				out.add(item.asText());
			}
		}
		return out.isEmpty() ? null : out;
	}

	/** {@code "mame.addon.reference.data.recordSelectionPolicy.mostCompleted.label"} -&gt; {@code "mostCompleted"} - the second-to-last dot segment, since every one of these i18n keys ends {@code ".label"}. */
	static String shortenI18nKey(final String pKey)
	{
		final String[] parts = pKey.split("\\.");
		return parts.length >= 2 ? parts[parts.length - 2] : pKey;
	}

	// ------------------------------------------------------------------------------------------- matching_config_schema

	/** {@link #readVocabulary}'s own result: exactly one of {@link #vocabulary}/{@link #error} is non-null. */
	public static final class VocabularyResult
	{
		public final MatchPlanLlmPass.AllowedVocabulary vocabulary;
		public final String error;

		private VocabularyResult(final MatchPlanLlmPass.AllowedVocabulary pVocabulary, final String pError)
		{
			this.vocabulary = pVocabulary;
			this.error = pError;
		}
	}

	/**
	 * Reads the allowed algorithm/null-handling/record-selection vocabulary from an ALREADY-{@link
	 * #resolveSchemaData resolved} {@code matching_config_schema} document (the caller must resolve the
	 * string-wrapped {@code data} first - kept as a separate step so a fixture can hand this method a plain
	 * object directly). Each of the three enums is read from its own exact, measured path (see the {@code
	 * *_PATH} constants); a missing path fails naming exactly that path, never falling back to a guess.
	 */
	public static VocabularyResult readVocabulary(final JsonNode pSchemaDocument)
	{
		final List<String> algorithms = MatchPlanProfileReader.enumAt(pSchemaDocument, MatchPlanProfileReader.ALGORITHM_PATH);
		if (algorithms == null)
		{
			return new VocabularyResult(null, "no non-empty string array at '" + MatchPlanProfileReader.ALGORITHM_PATH
				+ "' - matching_config_schema's shape has changed since 2026-09-21, see docs/MATCHING.md \"not verified\".");
		}
		// two enums since add-on build 6.3.0.5-RC3: the tree one (read at the historical path) and the matching-policy-field one; one enum only
		// (older builds, or one of the two paths absent) serves for both.
		List<String> treeNullHandlings = MatchPlanProfileReader.enumAt(pSchemaDocument, MatchPlanProfileReader.NULL_HANDLING_PATH);
		if (treeNullHandlings == null)
		{
			treeNullHandlings = MatchPlanProfileReader.enumAt(pSchemaDocument, MatchPlanProfileReader.TREE_ONE_NULL_HANDLING_PATH);
		}
		List<String> policyNullHandlings = MatchPlanProfileReader.enumAt(pSchemaDocument, MatchPlanProfileReader.POLICY_FIELD_BOTH_NULL_PATH);
		if (policyNullHandlings == null)
		{
			policyNullHandlings = MatchPlanProfileReader.enumAt(pSchemaDocument, MatchPlanProfileReader.POLICY_FIELD_ONE_NULL_PATH);
		}
		if (treeNullHandlings == null && policyNullHandlings == null)
		{
			return new VocabularyResult(null, "no non-empty string array at '" + MatchPlanProfileReader.NULL_HANDLING_PATH
				+ "' - matching_config_schema's shape has changed since 2026-09-21, see docs/MATCHING.md \"not verified\".");
		}
		if (treeNullHandlings == null)
		{
			treeNullHandlings = policyNullHandlings;
		}
		if (policyNullHandlings == null)
		{
			policyNullHandlings = treeNullHandlings;
		}
		final List<String> rawRecordSelections = MatchPlanProfileReader.enumAt(pSchemaDocument, MatchPlanProfileReader.RECORD_SELECTION_PATH);
		if (rawRecordSelections == null)
		{
			return new VocabularyResult(null, "no non-empty string array at '" + MatchPlanProfileReader.RECORD_SELECTION_PATH
				+ "' - matching_config_schema's shape has changed since 2026-09-21, see docs/MATCHING.md \"not verified\".");
		}
		final List<String> recordSelections = new ArrayList<String>();
		for (final String rawKey : rawRecordSelections)
		{
			recordSelections.add(MatchPlanProfileReader.shortenI18nKey(rawKey));
		}
		return new VocabularyResult(new MatchPlanLlmPass.AllowedVocabulary(algorithms, policyNullHandlings, treeNullHandlings, recordSelections), null);
	}

	/**
	 * Locates the {@code fields} array of a resolved {@code matching_config_schema(table=...)} document,
	 * trying the measured shape first and degrading through tolerance cases - see class Javadoc for why each
	 * one exists. Returns a {@link com.fasterxml.jackson.databind.node.MissingNode}-like non-array node (never
	 * {@code null}) when none of the four locations carries an array, which {@link #readFieldCapabilities}
	 * then treats as "no information".
	 */
	private static JsonNode fieldCapabilitiesArray(final JsonNode pSchemaDocument)
	{
		final JsonNode nested = pSchemaDocument.path("fieldCapabilities").path("fields");
		if (nested.isArray())
		{
			return nested;
		}
		final JsonNode root = pSchemaDocument.path("fields");
		if (root.isArray())
		{
			return root;
		}
		final JsonNode dataNested = pSchemaDocument.path("data").path("fieldCapabilities").path("fields");
		if (dataNested.isArray())
		{
			return dataNested;
		}
		return pSchemaDocument.path("data").path("fields");
	}

	/**
	 * Per field, whether it offers at least one pre-clustering search strategy - an empty {@code available}
	 * list means false. Reads the resolved document's {@code fieldCapabilities.fields} array first (see class
	 * Javadoc for the corrected, measured shape), with fallbacks handled by {@link #fieldCapabilitiesArray}.
	 * Absence of the whole node, or of one particular field in it, is "no information" (not present in the
	 * returned map) - the caller defaults such a field to capable, since absence of information is not a
	 * prohibition (e.g. a genuine {@code TABLE_NOT_FOUND} soft miss, resolved to an empty object by {@link
	 * #resolveSchemaData}).
	 */
	public static Map<String, Boolean> readFieldCapabilities(final JsonNode pSchemaDocument)
	{
		final JsonNode node = MatchPlanProfileReader.fieldCapabilitiesArray(pSchemaDocument);
		final Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
		if (!node.isArray())
		{
			return out;
		}
		for (final JsonNode entry : node)
		{
			final String field = MatchPlanProfileReader.stripLeadingSlash(entry.path("field").asText(null));
			if (field == null)
			{
				continue;
			}
			final JsonNode available = entry.path("available");
			out.put(field, Boolean.valueOf(available.isArray() && available.size() > 0));
		}
		return out;
	}

	/**
	 * Cross-checks a built {@code matchingPolicy}'s own {@code fields[].field} (leading-slash record-relative
	 * paths, {@code master.MatchingPolicyTranslator}'s own output shape) against an ALREADY-{@link
	 * #readFieldCapabilities read} {@code fieldCapabilities} map - pure, so {@code
	 * ebx.MatchingConfigurator}'s own "refuse before calling generate_matching_policy" pre-flight guard
	 * (`docs/MATCHING.md` &sect;3.1, item 4 of the 2026-09-21 fix) is unit-tested without a live MCP call. A
	 * field the map does not mention at all, or that maps to {@code Boolean.FALSE} (an empty {@code available}
	 * list - a structural incapacity, never merely "unmeasurable"), is reported; the caller decides what to do
	 * with a non-empty result (refuse, in {@code MatchingConfigurator}'s case). An EMPTY {@code
	 * pFieldCapabilities} map (the common case on this instance today - see this class' own Javadoc: the
	 * table-scoped call has only ever been observed to soft-miss) is never itself a reason to report
	 * anything - the caller is expected to skip this check entirely rather than pass an empty map in, exactly
	 * like {@code MatchPlanLlmPass}'s own clustering guard already treats "no information" as "assume capable".
	 */
	public static List<String> findFieldsMissingCapability(final JsonNode pMatchingPolicy, final Map<String, Boolean> pFieldCapabilities)
	{
		final List<String> missing = new ArrayList<String>();
		for (final JsonNode field : pMatchingPolicy.path("fields"))
		{
			final String name = MatchPlanProfileReader.stripLeadingSlash(field.path("field").asText(null));
			if (name == null)
			{
				continue;
			}
			final Boolean capable = pFieldCapabilities.get(name);
			if (capable == null)
			{
				missing.add(name + " (not reported by fieldCapabilities)");
			}
			else if (!capable.booleanValue())
			{
				missing.add(name + " (fieldCapabilities reports no available pre-clustering search strategy)");
			}
		}
		return missing;
	}

	// ------------------------------------------------------------------------------------------- profile_table_for_matching

	/** One field's raw statistics, exactly as {@code profile_table_for_matching} reported them - see class Javadoc for why {@link #mostFrequentValue}/{@link #mostFrequentToken} are NOT gated here. */
	public static final class FieldStats
	{
		public final Double fillRate;
		public final String fillRateProvenance;
		public final Integer distinctCount;
		public final String cardinalityProvenance;
		public final String mostFrequentValue;
		public final Integer mostFrequentValueCount;
		public final String mostFrequentToken;
		public final Integer mostFrequentTokenDocFrequency;

		FieldStats(final Double pFillRate, final String pFillRateProvenance, final Integer pDistinctCount, final String pCardinalityProvenance,
			final String pMostFrequentValue, final Integer pMostFrequentValueCount, final String pMostFrequentToken, final Integer pMostFrequentTokenDocFrequency)
		{
			this.fillRate = pFillRate;
			this.fillRateProvenance = pFillRateProvenance;
			this.distinctCount = pDistinctCount;
			this.cardinalityProvenance = pCardinalityProvenance;
			this.mostFrequentValue = pMostFrequentValue;
			this.mostFrequentValueCount = pMostFrequentValueCount;
			this.mostFrequentToken = pMostFrequentToken;
			this.mostFrequentTokenDocFrequency = pMostFrequentTokenDocFrequency;
		}
	}

	/** Reads one entry of {@code profile_table_for_matching}'s {@code data.fields[]} array - null-safe: a statistic {@code profile_table_for_matching} reports as {@code null} (e.g. {@code mostFrequentToken} on a field whose provenance is {@code NOT_SUPPORTED}) reads back null here too, never a placeholder. */
	public static FieldStats readFieldStats(final JsonNode pFieldProfile)
	{
		final Double fillRate = pFieldProfile.has("fillRate") && !pFieldProfile.path("fillRate").isNull() ? Double.valueOf(pFieldProfile.path("fillRate").asDouble()) : null;
		final String fillRateProvenance = pFieldProfile.path("provenance").path("fillRate").asText(null);
		final Integer distinctCount = pFieldProfile.has("cardinality") && !pFieldProfile.path("cardinality").isNull() ? Integer.valueOf(pFieldProfile.path("cardinality").asInt())
			: null;
		final String cardinalityProvenance = pFieldProfile.path("provenance").path("cardinality").asText(null);
		final String mostFrequentValue = pFieldProfile.hasNonNull("mostFrequentValue") ? pFieldProfile.path("mostFrequentValue").asText() : null;
		final Integer mostFrequentValueCount = pFieldProfile.hasNonNull("mostFrequentValueCount") ? Integer.valueOf(pFieldProfile.path("mostFrequentValueCount").asInt()) : null;
		final String mostFrequentToken = pFieldProfile.hasNonNull("mostFrequentToken") ? pFieldProfile.path("mostFrequentToken").asText() : null;
		final Integer mostFrequentTokenDocFrequency = pFieldProfile.hasNonNull("mostFrequentTokenDocFrequency")
			? Integer.valueOf(pFieldProfile.path("mostFrequentTokenDocFrequency").asInt()) : null;
		return new FieldStats(fillRate, fillRateProvenance, distinctCount, cardinalityProvenance, mostFrequentValue, mostFrequentValueCount, mostFrequentToken,
			mostFrequentTokenDocFrequency);
	}

	/** {@code data.fields[]} indexed by field name (leading {@code /} stripped), excluding {@code pExcludeNames} (technical fields such as {@code id}/{@code sourceSystem}/{@code _loadId} - the caller further intersects this with its own accepted-attribute list, so an unrecognised field name here is simply never looked up). */
	public static Map<String, JsonNode> fieldsByName(final JsonNode pData, final Set<String> pExcludeNames)
	{
		final Map<String, JsonNode> out = new LinkedHashMap<String, JsonNode>();
		for (final JsonNode field : pData.path("fields"))
		{
			final String name = MatchPlanProfileReader.stripLeadingSlash(field.path("field").asText(null));
			if (name != null && !pExcludeNames.contains(name))
			{
				out.put(name, field);
			}
		}
		return out;
	}

	public static long rowCount(final JsonNode pData)
	{
		return pData.path("rowCount").asLong(0);
	}

	public static String stripLeadingSlash(final String pField)
	{
		return pField == null ? null : (pField.startsWith("/") ? pField.substring(1) : pField);
	}

	// ------------------------------------------------------------------------------------------------------------ misc

	/**
	 * Every {@code match-merge} tool's soft-miss shape, measured live 2026-09-21: {@code {"success":false,
	 * "code":"...","message":"...","nextStep":"..."}}. Surfaces the real {@code message}/{@code nextStep}
	 * the server gives - not a guessed hint - so a refusal tells a person exactly what the server said to do.
	 */
	public static String describeSoftMiss(final JsonNode pResponseJson)
	{
		final String code = pResponseJson.path("code").asText("UNKNOWN");
		final String message = pResponseJson.path("message").asText(null);
		final String nextStep = pResponseJson.path("nextStep").asText(null);
		final StringBuilder out = new StringBuilder(code);
		if (message != null && !message.trim().isEmpty())
		{
			out.append(": ").append(message.trim());
		}
		if (nextStep != null && !nextStep.trim().isEmpty())
		{
			out.append(" (next step: ").append(nextStep.trim()).append(')');
		}
		return out.toString();
	}

	public static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
