package com.on.ebx.factory.quality;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.PromptData;

/**
 * The language-model proposer of normalisation/quality rules, the pattern {@code staging.StagingLlmPass}
 * already established applied to a DIFFERENT proposal family: instead of hard-coded Java heuristics that
 * decide a rule from a column NAME or a business vocabulary (the first cut of this lot, {@code
 * quality.Detectors}/{@code quality.QualityDetectors} before 2026-09-21 - country/currency/legal-form
 * transcoding, SIREN/SIRET/IBAN/LEI check digits, a BIC shape check, all name-hinted), the language model now
 * PROPOSES a {@code MappingStep} or a {@code QualityRule} from metadata, and the factory MEASURES the
 * proposal against the real distinct values before it is ever stored - the same "propose, measure, store the
 * evidence" discipline, just moved from a Java {@code if (name.contains("siren"))} to a model call. This
 * makes the factory work for ANY master-data domain (products, materials, sites...), not only party/company
 * data - the product owner's explicit verdict on the first cut (PLAN.md task brief, 2026-09-21).
 * <p>
 * Pure: no EBX class anywhere in this file, including the {@link Session} that backs the four local tools the
 * model is given - {@link #systemPrompt()}, {@link #userPrompt}, the tool JSON schemas and {@link Session}'s
 * decision logic are all plain JDK/Jackson, so an {@code AgentLoop} can be run against them (a fake HTTP
 * provider, real {@code ToolSet}/{@code AgentLoop}) with no EBX runtime at all - see the test. {@code
 * com.on.ebx.factory.ebx.QualityLlmSuggester} is the only place that touches EBX: it reads every project's
 * ACCEPTED-or-APPLIED mapped columns (the same scope {@code ebx.QualitySuggester} already reads) into the
 * plain value objects below - distinct values WITH counts, bounded, the exact same evidence {@code
 * ebx.QualitySuggester} already fetches for its own RULE pass - builds a {@link Session}, runs it through
 * {@code ebx.AgentRuntime}, and - only then - writes {@link Session#proposedSteps()}/{@link
 * Session#proposedRules()} as real {@code MappingStep}/{@code QualityRule} rows (origin {@code LLM}). Nothing
 * in {@link Session} performs a write of any kind; "recording a proposal" during the run means appending to an
 * in-memory list, exactly like {@code StagingLlmPass.Session}.
 * <p>
 * <b>What may reach the model is a per-project setting ({@code Project.llmValueSharing}), a decision already
 * taken by the product owner, not reopened here.</b> {@link Mode#SHAPES_ONLY} (default): every value shown to
 * the model - in the {@link #userPrompt}, AND in every {@code measure} tool result - is a {@link Shapes shape}
 * (a letter becomes {@code A}/{@code a}, a digit becomes {@code 9}), never a real value; fill rate/distinct
 * count/row count are plain numbers, not values, and are always shown. {@link Mode#SAMPLE_VALUES} (opt-in): in
 * addition to shapes, the top {@code Project.llmSampleSize} most frequent distinct values of each column are
 * sent in the prompt, and a {@code measure} result may show real non-conforming values instead of shapes - the
 * project has explicitly accepted that real values reach the model. {@code QualityLlmPassTest} proves the
 * {@link Mode#SHAPES_ONLY} guarantee directly: a sentinel value planted in the fixture data must never appear
 * in the system prompt, the user prompt, or any tool result the fake client receives.
 * <p>
 * <b>Measure before write, on every proposal, with a documented threshold per kind</b> (see {@link
 * Session#proposeMappingStep}/{@link Session#proposeQualityRule}): a step that would change nothing is
 * refused (the existing "never propose a no-op" principle, unchanged from before this lot); a lookup-based
 * step or a {@code REFERENCE}/{@code PATTERN}/{@code CHECK_DIGIT} rule is refused unless the measured
 * resolution/match/pass ratio clears the threshold documented on the constant. {@code REJECT} severity is
 * never accepted from the model (a human raises the severity, {@code docs/NORMALISATION.md} sec 2.2's own
 * rule, unchanged) - propose {@code WARN} and let a person escalate. {@code MANDATORY} needs the column's own
 * fill-rate profile ({@code LandingColumn.fillRate}, already read by the caller) to be measurable at all.
 * <p>
 * <b>Idempotent, never touches a decided row</b>: {@link Session} is given, per column, the {@code kind}s
 * already present (whatever their {@code origin}/{@code status}) - a {@code propose_mapping_step}/{@code
 * propose_quality_rule} call for a kind already present is answered informationally ("already exists, status
 * X") rather than refused as a mistake (not counted toward {@link Session#refuse the circuit breaker}), and
 * never creates a second row nor touches the existing one.
 * <p>
 * <b>Circuit breaker after 3 refusals of the same kind, like {@code StagingLlmPass}</b> (see {@link
 * Session#refuse}): the repeat key is (tool, entity, attribute, kind) - the same conceptual mistake, retried
 * on the same target, three times in a row, ends the run so the model is forced to its final summary instead
 * of spending its whole budget on one column.
 * <p>
 * <b>Coordinator review, 2026-09-21, two further corrections:</b> (1) {@link ColumnEvidence#values} is a
 * SAMPLE (the capped {@code GROUP BY} {@code ebx.QualitySuggester} already ran for its own RULE pass) - it is
 * never treated as the whole column for a gate or a context number any more. {@link ColumnEvidence
 * #exactFilledRows}/{@link ColumnEvidence#exactDistinctCount} are EXACT SQL aggregates over the WHOLE landing
 * table; {@code UNIQUE} (the one kind whose verdict is a plain count, not a ratio over a threshold) uses them
 * directly for its duplicate count, while {@code PATTERN}/{@code REFERENCE}/{@code CHECK_DIGIT} and the
 * lookup-based {@code MappingStep} kinds stay sample-based BY DESIGN (testing a regex/reference/check-digit
 * against every row of a hundred-million-row table on every {@code measure} call is not something this pass
 * does) but say so explicitly in every evidence sentence and tool result ({@link Session#sampled}, {@link
 * Detectors#sampledSuffix}) and cap their stored confidence ({@link Detectors#cappedConfidence}). (2) {@code
 * UNIQUE} moved here from {@code quality.QualityDetectors}'s own deterministic RULE pass entirely - "mostly
 * distinct" is a statistic no Java rule can turn into "uniqueness is expected on this column" without
 * business knowledge (a product description is exactly as "mostly unique" as a company VAT number). (3) the
 * {@code measure} tool is now bounded ({@link Session#MAX_MEASURE_CALLS_PER_COLUMN}/{@link
 * Session#MAX_MEASURE_CALLS_PER_RUN}): in {@link Mode#SHAPES_ONLY}, an unbounded regex-counting tool is
 * effectively an oracle over the real values.
 * <p>
 * <b>Coordinator review round 2, 2026-09-21 (first REAL run - OpenAI, project Companies, {@code SHAPES_ONLY}):
 * one agent call given all 60 mapped columns of 4 entities in one turn only ever covered the first entity
 * before declaring itself done, budget and refusals both to spare.</b> Four fixes, all in this file except the
 * first (which only this file's own {@link #systemPrompt()}/{@link #userPrompt} and {@link Session} support -
 * the actual per-entity LOOP lives in {@code ebx.QualityLlmSuggester}, see its own class Javadoc and
 * {@code docs/QUALITY-LLM.md} section 1.1): (1) the caller now runs one agent call per entity, each with a
 * FRESH {@link Session} scoped to only that entity's columns - {@link #userPrompt} names the entity up front
 * when every column shares it, and always ends with an explicit column checklist. (2) {@link Session#note}'s
 * schema ({@link #NOTE_SCHEMA}) grew optional {@code entity}/{@code attribute} fields, and {@link
 * Session#columnsWithoutProposalOrNote()} - pure, directly testable - reports which of this run's own columns
 * ended with neither a proposal nor a note naming them (informational only; nothing forces the model to close
 * the gap). (3) a {@code QualityRule}'s stored {@code examples} is one DEDUPLICATED non-conforming SHAPE per
 * failing value ({@link Session#addValueExample}), never a "before → after" pair - that concept only applies
 * to a {@code MappingStep} ({@link Session#addStepExample}, now used exclusively by {@link
 * Session#proposeMappingStep}); the first cut of {@code proposePattern}/{@code proposeReference}/
 * {@code proposeCheckDigit} wrongly called the {@code MappingStep} helper with a value joined to itself, and
 * without deduplication. (4) {@code PATTERN}'s own refusal AND acceptance messages now both warn explicitly
 * above {@link #PATTERN_MULTI_FORMAT_WARNING_PERCENT} (10%) failure ({@link #multiFormatCaveat}) that a
 * single-format regex may be wrongly matching a genuinely multi-format column, and a {@code PATTERN} rule's
 * stored confidence is capped at the conforming share on top of the existing sampling cap - a real run had
 * accepted PATTERN at 15 of 54 (28%) failing, under the unchanged 30% refusal bar but with no warning at all.
 */
public final class QualityLlmPass
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** {@code Project.llmValueSharing}, mirrored here so the pure layer never depends on the XSD-generated enum text directly. */
	public enum Mode
	{
		/** Lot B (2026-09-24, ebx.LlmDataPolicy): metadata and statistics only - no value, no shape, no example. */
		NONE, SHAPES_ONLY, SAMPLE_VALUES
	}

	/**
	 * {@code MappingStep.kind} values this pass may propose - the same generic toolbox vocabulary {@code
	 * CleaningSteps} executes, {@code MdmFactory.xsd}'s {@code MAPPING_STEP_KIND}. {@link
	 * CleaningSteps#STRIP_ACCENTS} (2026-09-21) is offered here too even though the deterministic pass only
	 * ever proposes it structurally, across two mappings at once ({@code CrossSystemFold}, which this
	 * single-column tool set cannot express) - a model may still reasonably propose it on ONE column whose
	 * sample shows accented values (e.g. a free-text name field), same as it already can propose {@code UPPER}.
	 */
	private static final List<String> MAPPING_STEP_KINDS = Collections.unmodifiableList(java.util.Arrays.asList(CleaningSteps.TRIM, CleaningSteps.COLLAPSE_SPACES,
		CleaningSteps.EMPTY_TO_NULL, CleaningSteps.UPPER, CleaningSteps.LOWER, CleaningSteps.STRIP_CHARS, CleaningSteps.STRIP_ACCENTS, CleaningSteps.COUNTRY_TO_ISO2,
		CleaningSteps.CURRENCY_TO_ISO, CleaningSteps.TRANSCODE_REFERENCE, CleaningSteps.NORMALISE_IDENTIFIER));
	/** Lookup-based {@code MappingStep} kinds: need {@link CleaningSteps.Lookups} and a resolution-ratio threshold, not just "did anything change". */
	private static final List<String> LOOKUP_STEP_KINDS = Collections
		.unmodifiableList(java.util.Arrays.asList(CleaningSteps.COUNTRY_TO_ISO2, CleaningSteps.CURRENCY_TO_ISO, CleaningSteps.TRANSCODE_REFERENCE));
	/** {@code QualityRule.kind} values this pass may propose - {@code MdmFactory.xsd}'s {@code QUALITY_RULE_KIND}. */
	private static final List<String> QUALITY_RULE_KINDS = Collections.unmodifiableList(
		java.util.Arrays.asList(QualityDetectors.MANDATORY, QualityDetectors.PATTERN, QualityDetectors.REFERENCE, QualityDetectors.CHECK_DIGIT,
			QualityDetectors.UNIQUE));
	/** Severities the model may propose - never {@code REJECT}, see the class Javadoc. */
	private static final List<String> PROPOSABLE_SEVERITIES = Collections.unmodifiableList(java.util.Arrays.asList(QualityDetectors.WARN, QualityDetectors.MODEL_CONSTRAINT));
	private static final List<String> MEASURE_KINDS = Collections
		.unmodifiableList(java.util.Arrays.asList(QualityDetectors.PATTERN, QualityDetectors.REFERENCE, QualityDetectors.CHECK_DIGIT));

	// -------------------------------------------------------------------------------------------------- thresholds

	/** A lookup-based {@code MappingStep} (COUNTRY_TO_ISO2/CURRENCY_TO_ISO/TRANSCODE_REFERENCE) is refused below this resolution share - it would leave too much of the column untouched to be worth a review line. */
	private static final int STEP_MIN_RESOLUTION_PERCENT = 50;
	/** A {@code PATTERN} quality rule is refused if more than this share of measured values fail it - past this point the pattern is probably wrong for the column, not the data. */
	private static final int PATTERN_MAX_FAILURE_PERCENT = 30;
	/** Above this failure share (accepted or refused), {@link #multiFormatCaveat} appends a warning - a single-format pattern that already fails on more than one in ten measured values is a real sign the column mixes formats, even when it still clears {@link #PATTERN_MAX_FAILURE_PERCENT} (coordinator review, 2026-09-21: a real run accepted 28% failing with no warning at all). */
	private static final int PATTERN_MULTI_FORMAT_WARNING_PERCENT = 10;
	/** A {@code REFERENCE} quality rule proposed at {@code WARN} needs at least this resolution share. */
	private static final int REFERENCE_MIN_RESOLUTION_FOR_WARN_PERCENT = 50;
	/** A {@code REFERENCE} quality rule proposed at {@code MODEL_CONSTRAINT} (a real facet/foreign key) needs at least this resolution share - the same 90% bar the pre-2026-09-21 RULE pass used for the same severity. */
	private static final int REFERENCE_MIN_RESOLUTION_FOR_CONSTRAINT_PERCENT = 90;
	/** A {@code CHECK_DIGIT} quality rule is refused if more than this share of measured values fail it - past this point the column probably does not use this algorithm. */
	private static final int CHECK_DIGIT_MAX_FAILURE_PERCENT = 40;
	/** A {@code MANDATORY} quality rule needs the column's own profiled fill rate to be at least this percent. */
	private static final double MANDATORY_MIN_FILL_RATE_PERCENT = 99.5;
	private static final String IDENTIFIER_SEPARATORS = " .-";
	/** A {@code note}'s {@code reason}/{@code text} is truncated to this length before being stored - a note is a short "why", never a paragraph (coordinator review, 2026-09-21: unbounded reasons in a batched {@code notes} list add up). */
	private static final int MAX_NOTE_REASON_LENGTH = 300;

	private static final JsonNode MEASURE_SCHEMA = QualityLlmPass.schema("{"
		+ "\"type\":\"object\","
		+ "\"properties\":{"
		+ "\"entity\":{\"type\":\"string\"},"
		+ "\"attribute\":{\"type\":\"string\"},"
		+ "\"kind\":{\"type\":\"string\",\"enum\":[\"PATTERN\",\"REFERENCE\",\"CHECK_DIGIT\"],\"description\":\"What to test.\"},"
		+ "\"argument\":{\"type\":\"string\",\"description\":\"A regex for PATTERN; one of the listed reference kinds for REFERENCE; one of the listed check-digit algorithms for CHECK_DIGIT.\"}"
		+ "},\"required\":[\"entity\",\"attribute\",\"kind\",\"argument\"]}");
	private static final JsonNode PROPOSE_MAPPING_STEP_SCHEMA = QualityLlmPass.schema("{"
		+ "\"type\":\"object\","
		+ "\"properties\":{"
		+ "\"entity\":{\"type\":\"string\"},"
		+ "\"attribute\":{\"type\":\"string\"},"
		+ "\"kind\":{\"type\":\"string\",\"enum\":" + QualityLlmPass.enumJson(QualityLlmPass.MAPPING_STEP_KINDS) + ",\"description\":\"Runs on the raw text BEFORE "
		+ "the mapping's own pipeline. UPPER/LOWER/NORMALISE_IDENTIFIER change letter case: refused on a column whose pipeline has a regex reading letter case.\"},"
		+ "\"argument\":{\"type\":\"string\",\"description\":\"Meaning depends on kind: a pipe-delimited sentinel list for EMPTY_TO_NULL, the characters to "
		+ "strip for STRIP_CHARS/NORMALISE_IDENTIFIER, {\\\"reference\\\":\\\"<one of the listed reference kinds>\\\"} for TRANSCODE_REFERENCE; unused otherwise.\"},"
		+ "\"confidence\":{\"type\":\"integer\",\"description\":\"0 (guess) to 100 (certain).\"},"
		+ "\"rationale\":{\"type\":\"string\",\"description\":\"One or two sentences: what you measured and why this step is worth proposing.\"}"
		+ "},\"required\":[\"entity\",\"attribute\",\"kind\",\"rationale\",\"confidence\"]}");
	private static final JsonNode PROPOSE_QUALITY_RULE_SCHEMA = QualityLlmPass.schema("{"
		+ "\"type\":\"object\","
		+ "\"properties\":{"
		+ "\"entity\":{\"type\":\"string\"},"
		+ "\"attribute\":{\"type\":\"string\"},"
		+ "\"kind\":{\"type\":\"string\",\"enum\":" + QualityLlmPass.enumJson(QualityLlmPass.QUALITY_RULE_KINDS) + "},"
		+ "\"argument\":{\"type\":\"string\",\"description\":\"The regex for PATTERN; one of the listed reference kinds for REFERENCE; one of the listed "
		+ "check-digit algorithms for CHECK_DIGIT; unused for MANDATORY/UNIQUE.\"},"
		+ "\"severity\":{\"type\":\"string\",\"enum\":[\"WARN\",\"MODEL_CONSTRAINT\"],\"description\":\"Never REJECT: a person raises the severity once the "
		+ "data is judged trustworthy enough for that.\"},"
		+ "\"confidence\":{\"type\":\"integer\",\"description\":\"0 (guess) to 100 (certain).\"},"
		+ "\"conditionAttribute\":{\"type\":\"string\",\"description\":\"Optional, PATTERN/REFERENCE/CHECK_DIGIT at WARN only: another attribute of the same entity "
		+ "named in the failure breakdown of the measure result. With conditionValues, the rule then applies only to rows whose value of that attribute is one of them.\"},"
		+ "\"conditionValues\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"Optional, with conditionAttribute: values of that attribute "
		+ "taken from the failure breakdown (values actually measured) under which the rule is valid.\"},"
		+ "\"rationale\":{\"type\":\"string\",\"description\":\"One or two sentences: what you measured and why this rule is worth proposing.\"}"
		+ "},\"required\":[\"entity\",\"attribute\",\"kind\",\"severity\",\"rationale\",\"confidence\"]}");
	/**
	 * Two forms, both accepted (coordinator review round 3, 2026-09-21: the first cut's prompt asked for one
	 * {@code note} call per column, which the model followed literally - one turn per column, and every turn
	 * re-sends the whole conversation, so cost grew roughly with the SQUARE of the column count). The
	 * PREFERRED, and the only one {@link #systemPrompt()} now asks for, is {@code notes}: a whole list of
	 * columns left alone in ONE call. {@code entity}/{@code attribute} (top-level, for the legacy single-note
	 * form) are OPTIONAL - naming a real column of this run marks it covered by {@link
	 * Session#columnsWithoutProposalOrNote()}; omitted, or naming an unknown column, the note is still recorded
	 * as free text but marks nothing covered. Same rule per {@code notes} entry, where {@code entity} is itself
	 * optional (a run's columns almost always share one entity - see {@code ebx.QualityLlmSuggester} - so
	 * {@code attribute} alone already resolves unambiguously; only given to disambiguate a same-named attribute
	 * across two entities, which a test fixture may do on purpose but a real per-entity run never does).
	 */
	private static final JsonNode NOTE_SCHEMA = QualityLlmPass.schema("{"
		+ "\"type\":\"object\","
		+ "\"properties\":{"
		+ "\"notes\":{\"type\":\"array\",\"description\":\"PREFERRED: every column you are leaving alone, ONE call for the whole run - one entry per column, "
		+ "never one call per column.\",\"items\":{\"type\":\"object\",\"properties\":{"
		+ "\"entity\":{\"type\":\"string\",\"description\":\"Optional: only needed if the attribute name alone is ambiguous across entities in this run.\"},"
		+ "\"attribute\":{\"type\":\"string\"},"
		+ "\"reason\":{\"type\":\"string\",\"description\":\"Short: why this column needs no proposal.\"}"
		+ "},\"required\":[\"attribute\",\"reason\"]}},"
		+ "\"entity\":{\"type\":\"string\",\"description\":\"Legacy single-note form: optional entity of the one column this note is about. Prefer notes instead.\"},"
		+ "\"attribute\":{\"type\":\"string\",\"description\":\"Legacy single-note form: optional attribute of the one column this note is about.\"},"
		+ "\"text\":{\"type\":\"string\",\"description\":\"Legacy single-note form: the note text. Prefer notes instead.\"}"
		+ "}}");

	private QualityLlmPass()
	{
	}

	private static JsonNode schema(final String pJson)
	{
		try
		{
			return QualityLlmPass.MAPPER.readTree(pJson);
		}
		catch (final IOException ex)
		{
			// the schemas above are fixed constants of this class: a parse failure here is a bug in this file, not a runtime condition
			throw new IllegalStateException("invalid built-in tool schema: " + ex.getMessage(), ex);
		}
	}

	/**
	 * "" when {@code pFailing}/{@code pSeen} is at or below {@link #PATTERN_MULTI_FORMAT_WARNING_PERCENT};
	 * otherwise a sentence flagging the failure share and suggesting an alternation or a {@code note} instead -
	 * appended to BOTH the refusal message (always &gt; 30%, so always above 10% too) and the acceptance message
	 * (10-30%), per the coordinator's own instruction (2026-09-21): a real run accepted a single-format
	 * {@code PATTERN} with 28% of values failing and said nothing about it.
	 */
	private static String multiFormatCaveat(final long pFailing, final long pSeen)
	{
		if (pSeen == 0)
		{
			return "";
		}
		final long failurePercent = pFailing * 100L / pSeen;
		if (failurePercent <= QualityLlmPass.PATTERN_MULTI_FORMAT_WARNING_PERCENT)
		{
			return "";
		}
		return " " + failurePercent + "% of measured values fail this pattern (above " + QualityLlmPass.PATTERN_MULTI_FORMAT_WARNING_PERCENT
			+ "%): this may be a multi-format column - consider an alternation covering the dominant failing shapes, or call note instead if it should be left alone.";
	}

	private static String enumJson(final List<String> pValues)
	{
		final StringBuilder out = new StringBuilder("[");
		for (int i = 0; i < pValues.size(); i++)
		{
			if (i > 0)
			{
				out.append(',');
			}
			out.append('"').append(pValues.get(i)).append('"');
		}
		return out.append(']').toString();
	}

	// ------------------------------------------------------------------------------------------------------ prompt

	/**
	 * Fixed: no project data is ever interpolated into the system prompt (that all goes into {@link
	 * #userPrompt}), so this string is safe to review once and trust for every project, whatever its domain.
	 * <b>Domain-neutral by construction</b> (task requirement): no "Company", no SIREN/LEI - the two worked
	 * examples are a product GTIN and a site postal code, deliberately from two unrelated domains, so the
	 * model does not anchor on party/company data the way the pre-2026-09-21 Java heuristics did.
	 */
	public static String systemPrompt()
	{
		return "You are the normalisation/quality-rule proposer of the ON EBX MDM Factory. A deterministic pass already proposed the staging entities, "
			+ "attributes and mappings; your job is different: for columns that are already mapped, propose a cleanup step (a MappingStep, run on the raw "
			+ "value before it is parsed into its typed staging attribute) and/or a validation rule (a QualityRule) - the SAME toolbox for any master-data "
			+ "domain, never one hard-coded to a particular business vocabulary. For example, a product's GTIN column might need a GS1_MOD10 check-digit "
			+ "rule, while a site's postal code column might need a PATTERN rule matching that country's postal-code shape - the tools do not know or care "
			+ "which domain a column belongs to; you decide from its name, its type, its profile, and the shapes of its values. PATTERN and CHECK_DIGIT only "
			+ "ever make sense on a STRING column - never propose either for a number, date or boolean attribute, whatever the shapes suggest.\n\n"
			+ "You see metadata for every mapped column: entity/attribute names, the source column name(s) (an attribute fed by more than one source mapping "
			+ "is shown ONCE, with every contributing source column listed), data type, length, row count, fill rate, distinct count, and the SHAPES of its "
			+ "most frequent values with how often each shape occurs (a shape replaces every letter with A/a and every digit with 9, keeping punctuation and "
			+ "spacing - so \"FR76 3000\" reads as \"AA99 9999\"). Only when the project has explicitly opted in do you also see a sample of real distinct "
			+ "values; otherwise you never see one, anywhere, including in a measure result - assume you are in shapes-only mode unless the prompt says "
			+ "otherwise. A column already carrying an existing PROPOSED/ACCEPTED/APPLIED step or rule of a given kind (listed under that column below) needs "
			+ "no new measure call for that same kind - it is already covered; spend your measure budget on a different kind or a different column instead.\n\n"
			+ "Always MEASURE before you propose: the measure tool lets you test a regex, a reference list, or a check-digit algorithm against the column's "
			+ "real values and tells you how many pass. The factory measures every proposal itself anyway and refuses one that does not hold well enough on "
			+ "the data, so measuring first saves you a wasted call - never propose something you have not at least reasoned through from the shapes and "
			+ "profile you were given. measure itself is bounded (a limited number of calls per column and per run), so do not use it to probe every "
			+ "possible hypothesis - reason from the shapes first, measure the one or two you are genuinely unsure about.\n\n"
			+ "BATCH YOUR TOOL CALLS - this matters a lot for cost: you may, and should, call SEVERAL tools in the SAME turn whenever they do not depend on "
			+ "each other's results. Every round trip re-sends this entire conversation, so one turn making five independent calls costs far less than five "
			+ "turns making one call each. This run covers ONE entity's columns, listed at the end of this message - work through ALL of them, but do it in "
			+ "as few turns as the tools allow: (1) in ONE turn, call measure for every hypothesis you genuinely need to check, across every column at once; "
			+ "(2) in ONE turn, call propose_mapping_step/propose_quality_rule for everything you are ready to propose, across every column at once; (3) "
			+ "finally, call note EXACTLY ONCE for the whole run, with its notes list carrying one entry per column you are deliberately leaving alone (see "
			+ "the note tool below) - never once per column, never spread across several turns. A column that already needs no proposal AND no measuring can "
			+ "go straight into that one note call without ever costing a turn of its own. Cover every column, but never call a tool one at a time when "
			+ "nothing stops you from batching it with the others.\n\n"
			+ "Tools:\n"
			+ "- measure: read-only, test a PATTERN (regex), REFERENCE (one of the listed reference lists) or CHECK_DIGIT (one of the listed algorithms - a "
			+ "generic algorithm name only, never a business-identifier name) against a column's real values; returns counts and, only in real-value mode, "
			+ "a few non-conforming examples. STRING columns only.\n"
			+ "- propose_mapping_step: propose one cleanup step for a column (trim, case, empty-to-null, country/currency/reference transcoding, identifier "
			+ "normalisation...). Never propose one that would not actually change any measured value. A cleaning step runs on the raw text BEFORE the "
			+ "mapping's own pipeline (shown under the column when it has one), which then works on the step's result: never propose a step that changes "
			+ "letter case on a column whose pipeline has a regex reading letter case (\\p{Lu}, \\p{Ll}, [A-Z]) - the regex would no longer tell one token from another - "
			+ "and never one that leaves a pipeline's regex with nothing to match; both are refused.\n"
			+ "- propose_quality_rule: propose one validation rule for a column (MANDATORY, PATTERN, REFERENCE, CHECK_DIGIT, UNIQUE). Severity is WARN or "
			+ "MODEL_CONSTRAINT only - never REJECT: a person raises the severity once they trust the data enough for that. MODEL_CONSTRAINT itself is only "
			+ "for a PATTERN/CHECK_DIGIT you measured with ZERO failures over the full column (never a sample) - propose WARN for anything less airtight.\n"
			+ "- note: call this ONCE per run. Pass a notes list - one {attribute, reason} entry per column you deliberately leave alone, reason short. Only "
			+ "give entity too if the same attribute name is genuinely ambiguous across entities in this run, which normally never happens.\n\n"
			+ "When a measure result (or an existing rule listed under a column) carries a failure breakdown - the failing values grouped by another field of the "
			+ "same rows - read it: if the failures are concentrated in some values of that other field, propose the CONDITION under which the rule is valid "
			+ "(propose_quality_rule with conditionAttribute and conditionValues, taken from the breakdown), or a pattern that covers the failing shapes; never a "
			+ "warning that fires on correct data. A rule that fails on correct values of another group is not a data problem, it is a rule meant for one group.\n"
			+ "Give a rationale of one or two sentences and a confidence from 0 (guess) to 100 (certain) with every proposal. A kind already proposed for a "
			+ "column - by you earlier in this run, or before it started - is answered informationally, not as an error - move on to a different kind or "
			+ "column. When a tool call is refused because the measured data does not support it, correct your hypothesis (a different algorithm, a narrower "
			+ "pattern, or drop it) on your next call - but never repeat the exact same call expecting a different answer: a refusal repeated three times in "
			+ "a row on the same column and kind ends the run outright. A plain text answer records NOTHING: every outcome of this run - each proposal, and "
			+ "your one wrap-up - must go through a tool call. Once every column has a proposal, or is named in your one note call, the run is complete - do "
			+ "not add a separate prose summary afterward, the note call above already IS the wrap-up." + PromptData.SYSTEM_NOTICE;
	}

	/** One reference list available on this instance - never its contents, only what it is and how it is keyed. */
	public static final class ReferenceBindingInfo
	{
		public final String kind;
		public final String codeField;
		public final List<String> labelFields;
		public final String scopeField;

		public ReferenceBindingInfo(final String pKind, final String pCodeField, final List<String> pLabelFields, final String pScopeField)
		{
			this.kind = pKind;
			this.codeField = pCodeField;
			this.labelFields = pLabelFields == null ? new ArrayList<String>() : pLabelFields;
			this.scopeField = pScopeField;
		}
	}

	/** One shape and how many of the column's seen values had it - never the values themselves. */
	public static final class ShapeCount
	{
		public final String shape;
		public final long count;

		public ShapeCount(final String pShape, final long pCount)
		{
			this.shape = pShape;
			this.count = pCount;
		}
	}

	/** One existing {@code MappingStep}/{@code QualityRule} of a column, shown so the model does not repeat a kind already covered. */
	public static final class ExistingProposal
	{
		public final String kind;
		public final String status;
		/**
		 * Null when the kind carries none (MANDATORY/UNIQUE). Coordinator review round 3, 2026-09-21: identity
		 * for "does this already exist" is {@code (kind, argument)}, never {@code kind} alone - two PATTERN
		 * rows with two different regexes are two different hypotheses, not a duplicate of each other, and a
		 * rejected pair found live on real data (rows 28/29, {@code Company.country} REFERENCE COUNTRY) proved
		 * the argument has to be part of the match or the rejected twin and the still-open original become
		 * indistinguishable.
		 */
		public final String argument;
		/** Desk lot L4: whether this existing rule already carries a condition (QualityRule.conditionAttribute). */
		public final boolean hasCondition;
		/** Desk lot L4: where this existing rule fails, as the prompt should read it (see {@link FailureBreakdown#promptText}), or {@code null}. */
		public final String breakdownText;

		/** Legacy 2-arg form: {@link #argument} defaults to null - only correct for a kind that never carries one. Kept so every existing call site (tests included) still compiles; {@code ebx.QualityLlmSuggester} uses the 3-arg form. */
		public ExistingProposal(final String pKind, final String pStatus)
		{
			this(pKind, pStatus, null);
		}

		public ExistingProposal(final String pKind, final String pStatus, final String pArgument)
		{
			this(pKind, pStatus, pArgument, false, null);
		}

		public ExistingProposal(final String pKind, final String pStatus, final String pArgument, final boolean pHasCondition, final String pBreakdownText)
		{
			this.kind = pKind;
			this.status = pStatus;
			this.argument = pArgument;
			this.hasCondition = pHasCondition;
			this.breakdownText = pBreakdownText;
		}
	}

	/** One mapped column - the caller ({@code ebx.QualityLlmSuggester}) builds this from the exact same evidence {@code ebx.QualitySuggester}'s RULE pass already reads (distinct values with counts), turned into shapes/top-values here rather than raw values before this object is ever built. */
	public static final class ColumnInfo
	{
		public final String entity;
		public final String attribute;
		public final String sourceColumn;
		public final String dataType;
		public final Integer maxLength;
		public final Long rows;
		public final Double fillRatePercent;
		public final Long distinctCount;
		public final List<ShapeCount> shapes;
		/** Only populated by the caller in {@link Mode#SAMPLE_VALUES}; always empty in {@link Mode#SHAPES_ONLY}. */
		public final List<String> topValues;
		public final List<ExistingProposal> existingSteps;
		public final List<ExistingProposal> existingRules;
		/**
		 * Lot M8b (2026-10-04): the pipelines ({@code AttributeMapping.pipeline}) of the mappings that feed this attribute - never a value, the model's own
		 * regex text - so a cleaning step is never proposed in front of a pipeline it would break ({@link CaseOrderRule}). Empty when no mapping has one.
		 */
		public final List<String> mappingPipelines;
		/**
		 * Lot M8b: whether {@link ColumnEvidence} of this column comes from ONE mapping, i.e. {@link #mappingPipelines} can be measured on it. An attribute
		 * fed by several mappings has the UNION of their values as evidence, which is no sample of any one pipeline's own column.
		 */
		public final boolean pipelineSampleExact;

		public ColumnInfo(final String pEntity, final String pAttribute, final String pSourceColumn, final String pDataType, final Integer pMaxLength,
			final Long pRows, final Double pFillRatePercent, final Long pDistinctCount, final List<ShapeCount> pShapes, final List<String> pTopValues,
			final List<ExistingProposal> pExistingSteps, final List<ExistingProposal> pExistingRules)
		{
			this(pEntity, pAttribute, pSourceColumn, pDataType, pMaxLength, pRows, pFillRatePercent, pDistinctCount, pShapes, pTopValues, pExistingSteps,
				pExistingRules, null, false);
		}

		public ColumnInfo(final String pEntity, final String pAttribute, final String pSourceColumn, final String pDataType, final Integer pMaxLength,
			final Long pRows, final Double pFillRatePercent, final Long pDistinctCount, final List<ShapeCount> pShapes, final List<String> pTopValues,
			final List<ExistingProposal> pExistingSteps, final List<ExistingProposal> pExistingRules, final List<String> pMappingPipelines,
			final boolean pPipelineSampleExact)
		{
			this.mappingPipelines = pMappingPipelines == null ? new ArrayList<String>() : pMappingPipelines;
			this.pipelineSampleExact = pPipelineSampleExact;
			this.entity = pEntity;
			this.attribute = pAttribute;
			this.sourceColumn = pSourceColumn;
			this.dataType = pDataType;
			this.maxLength = pMaxLength;
			this.rows = pRows;
			this.fillRatePercent = pFillRatePercent;
			this.distinctCount = pDistinctCount;
			this.shapes = pShapes == null ? new ArrayList<ShapeCount>() : pShapes;
			this.topValues = pTopValues == null ? new ArrayList<String>() : pTopValues;
			this.existingSteps = pExistingSteps == null ? new ArrayList<ExistingProposal>() : pExistingSteps;
			this.existingRules = pExistingRules == null ? new ArrayList<ExistingProposal>() : pExistingRules;
		}

		String key()
		{
			return this.entity + "." + this.attribute;
		}
	}

	/** A project's mapped columns and reference lists - exactly what {@link #userPrompt} renders. */
	public static final class ProjectInfo
	{
		public final String code;
		public final String label;
		public final Mode mode;
		public final int sampleSize;
		public final List<ColumnInfo> columns;
		public final List<ReferenceBindingInfo> referenceBindings;

		public ProjectInfo(final String pCode, final String pLabel, final Mode pMode, final int pSampleSize, final List<ColumnInfo> pColumns,
			final List<ReferenceBindingInfo> pReferenceBindings)
		{
			this.code = pCode;
			this.label = pLabel;
			this.mode = pMode;
			this.sampleSize = pSampleSize;
			this.columns = pColumns == null ? new ArrayList<ColumnInfo>() : pColumns;
			this.referenceBindings = pReferenceBindings == null ? new ArrayList<ReferenceBindingInfo>() : pReferenceBindings;
		}
	}

	/**
	 * The only place project data enters a prompt: entity/attribute/source-column names, data type, length,
	 * row count, fill rate, distinct count, value SHAPES with frequencies, the reference lists available, and
	 * what is already proposed/decided per column - never a real value, UNLESS {@code pProject.mode} is {@link
	 * Mode#SAMPLE_VALUES}, in which case the top {@code pProject.sampleSize} most frequent distinct values of
	 * each column are also shown (the caller only ever populates {@link ColumnInfo#topValues} in that mode -
	 * this method does not itself gate on the mode a second time, it renders exactly what it was given).
	 * <p>
	 * <b>Coordinator review, 2026-09-21: a real run given 60 columns over 4 entities in ONE call only ever
	 * covered the first entity.</b> Real usage now gives this method one entity's columns per call ({@code
	 * ebx.QualityLlmSuggester} runs one agent per accepted entity, sequentially) - when every column shares the
	 * same {@link ColumnInfo#entity}, the prompt names it explicitly up front, and always ends with an explicit
	 * checklist of every column to work through, in order, so coverage is something the model (and a reader of
	 * the trace) can literally count off - the factory itself never trusts this alone, see {@link
	 * Session#columnsWithoutProposalOrNote()} for the actual, checked answer.
	 */
	public static String userPrompt(final ProjectInfo pProject)
	{
		return QualityLlmPass.userPrompt(pProject, null);
	}

	/**
	 * Desk lot L4b: {@link #userPrompt(ProjectInfo)} for a run NARROWED to one existing rule ({@link RuleFocus}): the same prompt (the column, its breakdown, the
	 * existing rule) plus {@link RuleFocus#promptBlock}, which says what this run is for. {@code null} focus = the ordinary prompt.
	 */
	public static String userPrompt(final ProjectInfo pProject, final RuleFocus pFocus)
	{
		final StringBuilder out = new StringBuilder();
		out.append("Project: ").append(pProject.code).append(" - ").append(pProject.label).append('\n');
		final java.util.Set<String> entityNames = new java.util.LinkedHashSet<String>();
		for (final ColumnInfo column : pProject.columns)
		{
			entityNames.add(column.entity);
		}
		if (entityNames.size() == 1)
		{
			out.append("Entity: ").append(entityNames.iterator().next()).append(" - ").append(pProject.columns.size())
				.append(" column(s) in this run, ALL listed below and in the checklist at the end.\n");
		}
		out.append("Value sharing mode: ").append(pProject.mode).append(pProject.mode == Mode.NONE
			? " (metadata and statistics only - you see no value and no shape; a measure result reports counts only)\n"
			: pProject.mode == Mode.SHAPES_ONLY ? " (metadata and shapes only - no real value below is a value from the data)\n"
				: " (this project opted in: a sample of real distinct values is shown per column)\n");

		out.append("\nAvailable reference lists:\n");
		if (pProject.referenceBindings.isEmpty())
		{
			out.append("(none configured on this instance)\n");
		}
		else
		{
			for (final ReferenceBindingInfo binding : pProject.referenceBindings)
			{
				out.append("- ").append(binding.kind).append(" (code field ").append(binding.codeField).append(", label fields ")
					.append(QualityLlmPass.join(binding.labelFields)).append(binding.scopeField != null ? ", scoped by " + binding.scopeField : "")
					.append(")\n");
			}
		}

		out.append("\nMapped columns (names, source columns and values come from the sources):\n");
		final int inventoryStart = out.length();
		for (final ColumnInfo column : pProject.columns)
		{
			out.append("\n- ").append(column.entity).append('.').append(column.attribute).append(" (source column ").append(column.sourceColumn)
				.append(", type ").append(column.dataType);
			if (column.maxLength != null)
			{
				out.append(", maxLength ").append(column.maxLength);
			}
			out.append(")\n");
			out.append("  Profile: ");
			out.append(column.rows != null ? column.rows + " row(s)" : "row count unknown");
			out.append(", fill rate ").append(column.fillRatePercent != null ? column.fillRatePercent + "%" : "unknown");
			out.append(", distinct values ").append(column.distinctCount != null ? String.valueOf(column.distinctCount) : "unknown").append('\n');
			out.append("  Shapes (most frequent first): ");
			if (column.shapes.isEmpty())
			{
				out.append("(none)");
			}
			else
			{
				for (int i = 0; i < column.shapes.size(); i++)
				{
					if (i > 0)
					{
						out.append(", ");
					}
					out.append(column.shapes.get(i).shape).append(" (").append(column.shapes.get(i).count).append(')');
				}
			}
			out.append('\n');
			if (!column.topValues.isEmpty())
			{
				out.append("  Top values: ").append(QualityLlmPass.join(column.topValues)).append('\n');
			}
			if (!column.mappingPipelines.isEmpty())
			{
				out.append("  Mapping pipeline (runs AFTER the cleaning steps, on their result): ").append(QualityLlmPass.join(column.mappingPipelines)).append('\n');
			}
			out.append("  Existing cleaning steps: ").append(QualityLlmPass.joinExisting(column.existingSteps)).append('\n');
			out.append("  Existing quality rules: ").append(QualityLlmPass.joinExisting(column.existingRules)).append('\n');
		}
		out.append("\nColumns to work through, in order - for each, propose cleaning steps first, then quality rules, or call note if you leave it alone:\n");
		for (final ColumnInfo column : pProject.columns)
		{
			out.append("- ").append(column.entity).append('.').append(column.attribute).append('\n');
		}
		final String inventory = out.substring(inventoryStart);
		out.setLength(inventoryStart);
		out.append(PromptData.fence("MAPPED COLUMNS", inventory));
		if (pFocus != null)
		{
			out.append('\n').append(pFocus.promptBlock(pProject.columns.isEmpty() ? null : pProject.columns.get(0).entity,
				pProject.columns.isEmpty() ? null : pProject.columns.get(0).attribute)).append('\n');
			return out.toString();
		}
		out.append("\nMeasure first, propose with the tools you have, and go through every column above, then finish with the single list-shaped note.\n");
		return out.toString();
	}

	private static String joinExisting(final List<ExistingProposal> pExisting)
	{
		if (pExisting.isEmpty())
		{
			return "none";
		}
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pExisting.size(); i++)
		{
			if (i > 0)
			{
				out.append(", ");
			}
			final ExistingProposal existing = pExisting.get(i);
			out.append(existing.kind).append('(').append(existing.status).append(')');
			if (existing.breakdownText != null)
			{
				// desk lot L4: the rule is open and measured to fail somewhere - the model may add a condition to it (propose_quality_rule with conditionAttribute)
				out.append(" [argument ").append(existing.argument).append(existing.hasCondition ? ", already has a condition" : ", no condition yet").append(']')
					.append(existing.breakdownText);
			}
		}
		return out.toString();
	}

	private static String join(final List<String> pValues)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pValues.size(); i++)
		{
			if (i > 0)
			{
				out.append(", ");
			}
			out.append(pValues.get(i));
		}
		return out.toString();
	}

	// -------------------------------------------------------------------------------------------------- proposals

	public static final class ProposedStep
	{
		public final String entity;
		public final String attribute;
		public final String kind;
		public final String argument;
		public final int confidence;
		public final String rationale;
		public final long valuesSeen;
		public final long valuesChanged;
		public final long valuesUnresolved;
		public final List<String> examples;

		ProposedStep(final String pEntity, final String pAttribute, final String pKind, final String pArgument, final int pConfidence, final String pRationale,
			final long pValuesSeen, final long pValuesChanged, final long pValuesUnresolved, final List<String> pExamples)
		{
			this.entity = pEntity;
			this.attribute = pAttribute;
			this.kind = pKind;
			this.argument = pArgument;
			this.confidence = pConfidence;
			this.rationale = pRationale;
			this.valuesSeen = pValuesSeen;
			this.valuesChanged = pValuesChanged;
			this.valuesUnresolved = pValuesUnresolved;
			this.examples = pExamples;
		}
	}

	public static final class ProposedRule
	{
		public final String entity;
		public final String attribute;
		public final String kind;
		public final String argument;
		public final String severity;
		public final int confidence;
		public final String rationale;
		public final long valuesSeen;
		public final long valuesFailing;
		public final List<String> examples;
		/** Desk lot L4: {@code QualityRule.failureBreakdown} JSON, or {@code null}. */
		public final String failureBreakdown;
		/** Desk lot L4: the attribute (of the same entity) the rule is conditioned on, or {@code null}; with {@link #conditionValues}. */
		public final String conditionAttribute;
		public final List<String> conditionValues;
		/** Desk lot L4: the model added a condition to a rule that already exists (PROPOSED, unconditional): the writer updates that row instead of creating one. */
		public final boolean updatesExisting;

		ProposedRule(final String pEntity, final String pAttribute, final String pKind, final String pArgument, final String pSeverity, final int pConfidence,
			final String pRationale, final long pValuesSeen, final long pValuesFailing, final List<String> pExamples)
		{
			this(pEntity, pAttribute, pKind, pArgument, pSeverity, pConfidence, pRationale, pValuesSeen, pValuesFailing, pExamples, null, null, null, false);
		}

		ProposedRule(final String pEntity, final String pAttribute, final String pKind, final String pArgument, final String pSeverity, final int pConfidence,
			final String pRationale, final long pValuesSeen, final long pValuesFailing, final List<String> pExamples, final String pFailureBreakdown,
			final String pConditionAttribute, final List<String> pConditionValues, final boolean pUpdatesExisting)
		{
			this.failureBreakdown = pFailureBreakdown;
			this.conditionAttribute = pConditionAttribute;
			this.conditionValues = pConditionValues == null ? Collections.<String> emptyList() : pConditionValues;
			this.updatesExisting = pUpdatesExisting;
			this.entity = pEntity;
			this.attribute = pAttribute;
			this.kind = pKind;
			this.argument = pArgument;
			this.severity = pSeverity;
			this.confidence = pConfidence;
			this.rationale = pRationale;
			this.valuesSeen = pValuesSeen;
			this.valuesFailing = pValuesFailing;
			this.examples = pExamples;
		}
	}

	// ------------------------------------------------------------------------------------------------ failure breakdown

	/** Desk lot L4: how a failing value is stored or shown as an example under {@code pMode} - the value (opted in), its shape, or nothing (metadata only). */
	public static FailureBreakdown.Shower shower(final Mode pMode)
	{
		return new FailureBreakdown.Shower()
		{
			@Override
			public String show(final String pValue)
			{
				return pMode == Mode.NONE ? null : pMode == Mode.SAMPLE_VALUES ? pValue : Shapes.of(pValue);
			}
		};
	}

	/** Desk lot L4: the transfer engine's own verdict on a value, as a {@link FailureBreakdown.Judge}; {@code null} when the rule cannot be evaluated (invalid or refused pattern). */
	public static FailureBreakdown.Judge judge(final String pKind, final String pArgument, final CleaningSteps.Lookups pLookups)
	{
		final QualityRuleCheck.Rule rule = new QualityRuleCheck.Rule("breakdown", pKind, pArgument, QualityDetectors.WARN);
		if (rule.problem() != null)
		{
			return null;
		}
		final CleaningSteps.Lookups lookups = pLookups == null ? CleaningSteps.Lookups.empty() : pLookups;
		return new FailureBreakdown.Judge()
		{
			@Override
			public boolean fails(final String pValue)
			{
				return QualityRuleCheck.verdict(rule, pValue, lookups) == QualityRuleCheck.Verdict.FAIL;
			}
		};
	}

	/**
	 * Desk lot L4: where the rule fails, by the best separating field among {@code pEvidence.joints}; {@code null} when no joint counts were read, nothing fails, the rule cannot
	 * be evaluated, or no field separates the failures better than the column as one group.
	 */
	public static FailureBreakdown.Result breakdown(final String pKind, final String pArgument, final ColumnEvidence pEvidence, final CleaningSteps.Lookups pLookups,
		final Mode pMode)
	{
		if (pEvidence == null || pEvidence.joints.isEmpty())
		{
			return null;
		}
		final FailureBreakdown.Judge judge = QualityLlmPass.judge(pKind, pArgument, pLookups);
		return judge == null ? null : FailureBreakdown.best(pEvidence.joints, judge, QualityLlmPass.shower(pMode));
	}

	// ---------------------------------------------------------------------------------------------------- session

	/**
	 * What the pass measures a candidate against, for ONE mapped column - the real distinct values, kept
	 * private to this class (never handed to a caller, never serialised into a prompt or a tool result
	 * directly - only {@link Shapes shaped} or, in {@link Mode#SAMPLE_VALUES}, copied out bounded and
	 * explicitly).
	 * <p>
	 * <b>{@link #values} is a SAMPLE, capped by the caller ({@code ebx.QualitySuggester
	 * .DEFAULT_MAX_DISTINCT_VALUES}) - {@link #exactFilledRows}/{@link #exactDistinctCount} are the TRUE
	 * totals, read by EXACT SQL ({@code ebx.QualitySuggester#exactAggregates}), never derived from {@link
	 * #values}</b> (coordinator review, 2026-09-21: a gate or a context number computed on the top-N-most-
	 * frequent sample is simply wrong at volume - a highly-duplicated identifier's own duplicate values are
	 * exactly the ones a frequency-ordered sample over-represents, while the genuinely-unique long tail falls
	 * out of the cap entirely). Every gate/count in this class that claims to be exact (the {@code UNIQUE}
	 * duplicate count) uses these two fields; every gate that stays sample-based by design ({@code PATTERN}/
	 * {@code REFERENCE}/{@code CHECK_DIGIT}/lookup-based {@code MappingStep}s) says so explicitly in its own
	 * evidence text and caps its confidence - see {@link Session#sampled}.
	 */
	public static final class ColumnEvidence
	{
		public final List<Detectors.ValueCount> values;
		public final Double fillRatePercent;
		/** EXACT {@code COUNT} of non-blank rows for this column, read by SQL - never derived from {@link #values}. */
		public final long exactFilledRows;
		/** EXACT {@code COUNT(DISTINCT)} of non-blank rows for this column, read by SQL - never derived from {@link #values}. */
		public final long exactDistinctCount;
		/** Desk lot L4: the joint counts with each candidate grouping attribute of the same entity (see {@link FailureBreakdown.Joint}); empty when none was read. */
		public final List<FailureBreakdown.Joint> joints;

		public ColumnEvidence(final List<Detectors.ValueCount> pValues, final Double pFillRatePercent, final long pExactFilledRows, final long pExactDistinctCount)
		{
			this(pValues, pFillRatePercent, pExactFilledRows, pExactDistinctCount, null);
		}

		public ColumnEvidence(final List<Detectors.ValueCount> pValues, final Double pFillRatePercent, final long pExactFilledRows, final long pExactDistinctCount,
			final List<FailureBreakdown.Joint> pJoints)
		{
			this.joints = pJoints == null ? new ArrayList<FailureBreakdown.Joint>() : pJoints;
			this.values = pValues == null ? new ArrayList<Detectors.ValueCount>() : pValues;
			this.fillRatePercent = pFillRatePercent;
			this.exactFilledRows = pExactFilledRows;
			this.exactDistinctCount = pExactDistinctCount;
		}
	}

	/**
	 * Backs the four local tools for one agent run: validates every call against the project's mapped columns
	 * and real evidence (given at construction, read-only, never re-fetched), the reference lists actually
	 * configured on this instance, and what is already proposed/decided (also given at construction) - never
	 * touching EBX. One {@link Session} per run; not thread-safe (an {@code AgentLoop} run is already
	 * single-threaded).
	 */
	public static final class Session
	{
		private static final String TOOL_MEASURE = "measure";
		private static final String TOOL_STEP = "propose_mapping_step";
		private static final String TOOL_RULE = "propose_quality_rule";
		private static final int REFUSAL_STOP_THRESHOLD = 3;
		private static final String STOPPED_MESSAGE = "The run is over: no more tool calls will be executed. Answer now with your final summary of what you proposed.";
		/**
		 * Bounds on {@code measure} (coordinator review, 2026-09-21): in {@link Mode#SHAPES_ONLY}, an unbounded
		 * regex-counting tool is effectively an oracle over the real values - a model could binary-search a
		 * pattern to exfiltrate a column's content one bit at a time, call by call, without ever seeing a value
		 * directly. Both caps count every call, valid or refused - see {@link #measure}.
		 */
		private static final int MAX_MEASURE_CALLS_PER_COLUMN = 10;
		private static final int MAX_MEASURE_CALLS_PER_RUN = 100;

		private final Map<String, ColumnInfo> columnsByKey;
		private final Map<String, ColumnEvidence> evidenceByKey;
		private final CleaningSteps.Lookups lookups;
		private final java.util.Set<String> referenceKinds;
		private final Mode mode;
		private final int sampleSize;
		private final List<ProposedStep> steps = new ArrayList<ProposedStep>();
		private final List<ProposedRule> rules = new ArrayList<ProposedRule>();
		private final List<String> notes = new ArrayList<String>();
		private final Map<String, Integer> refusalCounts = new LinkedHashMap<String, Integer>();
		private final Map<String, Integer> measureCallsByColumn = new LinkedHashMap<String, Integer>();
		/** {@code entity.attribute} keys of a column a {@code note} call named explicitly - see {@link #note}/{@link #columnsWithoutProposalOrNote()}. */
		private final java.util.Set<String> notedColumnKeys = new java.util.LinkedHashSet<String>();
		private int totalMeasureCalls;
		/** Desk lot L4b: when set, the run is for ONE existing rule - no cleaning step, no other rule, only that rule given a condition. */
		private RuleFocus focus;
		private boolean stopped;
		private String stopReason;

		public Session(final List<ColumnInfo> pColumns, final Map<String, ColumnEvidence> pEvidenceByKey, final CleaningSteps.Lookups pLookups,
			final List<ReferenceBindingInfo> pReferenceBindings, final Mode pMode, final int pSampleSize)
		{
			this.columnsByKey = new LinkedHashMap<String, ColumnInfo>();
			if (pColumns != null)
			{
				for (final ColumnInfo column : pColumns)
				{
					this.columnsByKey.put(column.key(), column);
				}
			}
			this.evidenceByKey = pEvidenceByKey == null ? new LinkedHashMap<String, ColumnEvidence>() : pEvidenceByKey;
			this.lookups = pLookups == null ? CleaningSteps.Lookups.empty() : pLookups;
			this.referenceKinds = new java.util.LinkedHashSet<String>();
			if (pReferenceBindings != null)
			{
				for (final ReferenceBindingInfo binding : pReferenceBindings)
				{
					this.referenceKinds.add(binding.kind);
				}
			}
			this.mode = pMode == null ? Mode.SHAPES_ONLY : pMode;
			this.sampleSize = pSampleSize;
		}

		/** Desk lot L4b: narrows this session to {@code pFocus}'s rule (see {@link RuleFocus}); the tools refuse everything else. */
		public Session restrictTo(final RuleFocus pFocus)
		{
			this.focus = pFocus;
			return this;
		}

		public List<ProposedStep> proposedSteps()
		{
			return new ArrayList<ProposedStep>(this.steps);
		}

		public List<ProposedRule> proposedRules()
		{
			return new ArrayList<ProposedRule>(this.rules);
		}

		public List<String> notes()
		{
			return this.notes;
		}

		public boolean stoppedEarly()
		{
			return this.stopped;
		}

		public String stopReason()
		{
			return this.stopReason;
		}

		/**
		 * Every mapped column of this run that has NEITHER an existing {@code MappingStep}/{@code QualityRule}
		 * (from before this run) NOR a newly proposed one this run NOR a {@code note} tied to it - the coverage
		 * gap the factory reports so a run that quietly stops after one or two columns is visible instead of
		 * looking complete (coordinator review, 2026-09-21: "60 columns sent, 6 rules created, all on the first
		 * entity, nothing on the other three - the model simply declared itself done"). Never enforced - the
		 * caller decides what to do with the list (a warning in the response, a reason to re-run). Attribute
		 * names only, in the order the columns were given to this {@link Session} (unique within one entity's
		 * run, which is what a real caller always scopes a {@link Session} to - see {@code
		 * ebx.QualityLlmSuggester}).
		 */
		public List<String> columnsWithoutProposalOrNote()
		{
			final java.util.Set<String> covered = new java.util.LinkedHashSet<String>();
			for (final ProposedStep step : this.steps)
			{
				covered.add(step.entity + "." + step.attribute);
			}
			for (final ProposedRule rule : this.rules)
			{
				covered.add(rule.entity + "." + rule.attribute);
			}
			covered.addAll(this.notedColumnKeys);
			final List<String> out = new ArrayList<String>();
			for (final ColumnInfo column : this.columnsByKey.values())
			{
				final boolean hasExisting = !column.existingSteps.isEmpty() || !column.existingRules.isEmpty();
				if (!hasExisting && !covered.contains(column.key()))
				{
					out.add(column.attribute);
				}
			}
			return out;
		}

		public ToolSet.LocalGroup toolGroup()
		{
			final List<LocalTool> tools = new ArrayList<LocalTool>();
			tools.add(new LocalTool(Session.TOOL_MEASURE, "Read-only: test a PATTERN/REFERENCE/CHECK_DIGIT hypothesis against a column's real values before proposing.",
				QualityLlmPass.MEASURE_SCHEMA, new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.measure(pArguments);
					}
				}));
			tools.add(new LocalTool(Session.TOOL_STEP, "Propose one pre-parse cleanup step for a mapped column.", QualityLlmPass.PROPOSE_MAPPING_STEP_SCHEMA,
				new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.proposeMappingStep(pArguments);
					}
				}));
			tools.add(new LocalTool(Session.TOOL_RULE, "Propose one validation rule for a mapped column.", QualityLlmPass.PROPOSE_QUALITY_RULE_SCHEMA,
				new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.proposeQualityRule(pArguments);
					}
				}));
			tools.add(new LocalTool("note", "Call ONCE per run, with a notes list: every column you deliberately chose not to propose anything for, and why.",
				QualityLlmPass.NOTE_SCHEMA, new LocalTool.Handler()
			{
				@Override
				public LocalTool.Result handle(final JsonNode pArguments)
				{
					return Session.this.note(pArguments);
				}
			}));
			return new ToolSet.LocalGroup(tools);
		}

		// ---- measure ----

		LocalTool.Result measure(final JsonNode pArguments)
		{
			if (this.stopped)
			{
				return LocalTool.Result.error(Session.STOPPED_MESSAGE);
			}
			final String entity = Session.textOf(pArguments, "entity");
			final String attribute = Session.textOf(pArguments, "attribute");
			final String kind = Session.textOf(pArguments, "kind");
			final String argument = Session.textOf(pArguments, "argument");
			final String key = entity + "." + attribute + "." + kind;
			final String columnKey = entity + "." + attribute;

			// budget check FIRST, before any other validation - every call counts, valid or not (see the field
			// Javadoc: an unbounded oracle is an unbounded oracle whether or not each individual call is well-formed)
			this.totalMeasureCalls++;
			if (this.totalMeasureCalls > Session.MAX_MEASURE_CALLS_PER_RUN)
			{
				return this.refuse(Session.TOOL_MEASURE, key, "measure has already been called " + Session.MAX_MEASURE_CALLS_PER_RUN
					+ " times this run: propose something with what you have measured so far, or move on without it.");
			}
			final Integer previousColumnCalls = this.measureCallsByColumn.get(columnKey);
			final int columnCalls = (previousColumnCalls == null ? 0 : previousColumnCalls.intValue()) + 1;
			this.measureCallsByColumn.put(columnKey, Integer.valueOf(columnCalls));
			if (columnCalls > Session.MAX_MEASURE_CALLS_PER_COLUMN)
			{
				return this.refuse(Session.TOOL_MEASURE, key, "measure has already been called " + Session.MAX_MEASURE_CALLS_PER_COLUMN + " times on '" + columnKey
					+ "': propose something with what you have measured, or move to a different column.");
			}

			final ColumnInfo column = entity == null || attribute == null ? null : this.columnsByKey.get(columnKey);
			if (column == null)
			{
				return this.refuse(Session.TOOL_MEASURE, key, "unknown column '" + entity + "." + attribute
					+ "'. Use one of the exact entity/attribute names listed in the prompt.");
			}
			if (kind == null || !QualityLlmPass.MEASURE_KINDS.contains(kind))
			{
				return this.refuse(Session.TOOL_MEASURE, key, "'kind' must be one of " + QualityLlmPass.MEASURE_KINDS + ".");
			}
			// structural, type-based guard (coordinator review round 3, 2026-09-21: a real run proposed a
			// PATTERN on a TIMESTAMP attribute, row 27) - never name-based: a regex or a check-digit algorithm
			// is meaningless once the transfer has already parsed the value into its typed staging attribute
			if ((QualityDetectors.PATTERN.equals(kind) || QualityDetectors.CHECK_DIGIT.equals(kind)) && !Detectors.isTextLike(column.dataType))
			{
				return this.refuse(Session.TOOL_MEASURE, key, "'" + entity + "." + attribute + "' is typed " + column.dataType
					+ ", not a string: PATTERN/CHECK_DIGIT only make sense on a STRING attribute - the value is already parsed by the time either would run. "
					+ "Propose a different kind, or drop it.");
			}
			final ColumnEvidence evidence = this.evidenceByKey.get(column.key());
			if (evidence == null || evidence.values.isEmpty())
			{
				return this.refuse(Session.TOOL_MEASURE, key, "no measured values are available for '" + entity + "." + attribute + "'.");
			}

			if (QualityDetectors.PATTERN.equals(kind))
			{
				final Pattern pattern;
				try
				{
					pattern = RegexGuard.compile(argument == null ? "" : argument);
				}
				catch (final PatternSyntaxException ex)
				{
					return this.refuse(Session.TOOL_MEASURE, key, "'" + argument + "' is not a valid regular expression: " + ex.getMessage()
						+ ". Fix the pattern and measure again.");
				}
				return this.measurePattern(key, evidence, pattern);
			}
			if (QualityDetectors.REFERENCE.equals(kind))
			{
				if (argument == null || !this.referenceKinds.contains(argument))
				{
					return this.refuse(Session.TOOL_MEASURE, key,
						"unknown reference list '" + argument + "'. Use one of the reference lists named in the prompt: " + this.referenceKinds + ".");
				}
				return this.measureReference(key, evidence, argument);
			}
			// CHECK_DIGIT - PROPOSABLE list only (coordinator review round 3, 2026-09-21): a legacy name
			// (SIREN/SIRET/IBAN/LEI) stays valid for the EXECUTOR on a row decided before this lot, never
			// choosable again here - a real run picked SIREN straight off the wider list
			if (argument == null || !QualityDetectors.CHECK_DIGIT_ALGORITHMS_PROPOSABLE.contains(argument))
			{
				return this.refuse(Session.TOOL_MEASURE, key,
					"unknown check-digit algorithm '" + argument + "'. Use one of " + QualityDetectors.CHECK_DIGIT_ALGORITHMS_PROPOSABLE + ".");
			}
			return this.measureCheckDigit(key, evidence, argument);
		}

		private LocalTool.Result measurePattern(final String pKey, final ColumnEvidence pEvidence, final Pattern pPattern)
		{
			long seen = 0;
			long failing = 0;
			final List<String> examples = new ArrayList<String>();
			for (final Detectors.ValueCount vc : pEvidence.values)
			{
				if (vc.value == null)
				{
					continue;
				}
				seen += vc.count;
				final boolean matches;
				try
				{
					matches = RegexGuard.matches(pPattern, vc.value.trim().toUpperCase(Locale.ROOT));
				}
				catch (final RegexGuard.MatchTimeout ex)
				{
					return this.refuse(Session.TOOL_MEASURE, pKey, "this pattern ran past its time limit on a measured value (" + ex.getMessage()
						+ "): it backtracks too much. Write a simpler one, with character classes and no repeated group inside a repeated group.");
				}
				if (!matches)
				{
					failing += vc.count;
					this.addValueExample(examples, vc.value);
				}
			}
			this.refusalCounts.remove(Session.TOOL_MEASURE + "|" + pKey);
			return LocalTool.Result.ok(this.measureSummary(pEvidence, seen, seen - failing, failing, examples) + this.breakdownSuffix(QualityDetectors.PATTERN, pPattern.pattern(), pEvidence));
		}

		private LocalTool.Result measureReference(final String pKey, final ColumnEvidence pEvidence, final String pReferenceKind)
		{
			long seen = 0;
			long unresolved = 0;
			final List<String> examples = new ArrayList<String>();
			for (final Detectors.ValueCount vc : pEvidence.values)
			{
				if (vc.value == null)
				{
					continue;
				}
				seen += vc.count;
				if (this.lookups.resolveFlat(pReferenceKind, vc.value) == null)
				{
					unresolved += vc.count;
					this.addValueExample(examples, vc.value);
				}
			}
			this.refusalCounts.remove(Session.TOOL_MEASURE + "|" + pKey);
			return LocalTool.Result.ok(this.measureSummary(pEvidence, seen, seen - unresolved, unresolved, examples) + this.breakdownSuffix(QualityDetectors.REFERENCE, pReferenceKind, pEvidence));
		}

		private LocalTool.Result measureCheckDigit(final String pKey, final ColumnEvidence pEvidence, final String pAlgorithm)
		{
			long seen = 0;
			long failing = 0;
			final List<String> examples = new ArrayList<String>();
			for (final Detectors.ValueCount vc : pEvidence.values)
			{
				if (vc.value == null)
				{
					continue;
				}
				seen += vc.count;
				if (!QualityDetectors.isCheckDigitValid(pAlgorithm, vc.value))
				{
					failing += vc.count;
					this.addValueExample(examples, vc.value);
				}
			}
			this.refusalCounts.remove(Session.TOOL_MEASURE + "|" + pKey);
			return LocalTool.Result.ok(this.measureSummary(pEvidence, seen, seen - failing, failing, examples) + this.breakdownSuffix(QualityDetectors.CHECK_DIGIT, pAlgorithm, pEvidence));
		}

		/** {@code pSeen}/{@code pOk}/{@code pFailing} are always over the SAMPLE ({@code pEvidence.values}) - the sampling caveat (see {@link #sampled}) is appended so the model is never told a sample result as if it were the whole column, per the {@link Mode#SHAPES_ONLY} honesty guarantee. */
		private String measureSummary(final ColumnEvidence pEvidence, final long pSeen, final long pOk, final long pFailing, final List<String> pExamples)
		{
			final StringBuilder out = new StringBuilder();
			out.append(pOk).append(" of ").append(pSeen).append(" measured value(s) conform, ").append(pFailing).append(" do not.");
			if (!pExamples.isEmpty())
			{
				out.append(" Non-conforming: ").append(QualityLlmPass.join(pExamples)).append('.');
			}
			out.append(Detectors.sampledSuffix(Session.sampled(pEvidence), pEvidence.values.size(), pEvidence.exactDistinctCount));
			return out.toString();
		}

		/** {@code true} when {@link ColumnEvidence#values} is a SAMPLE, not the whole column - the exact source of truth is {@link ColumnEvidence#exactDistinctCount}, never derived from {@code values} itself. Package-private static so every {@code measure*}/{@code propose*} method shares the exact same test. */
		private static boolean sampled(final ColumnEvidence pEvidence)
		{
			return pEvidence.exactDistinctCount > pEvidence.values.size();
		}

		/**
		 * A real value only in {@link Mode#SAMPLE_VALUES} (bounded to 5, DEDUPLICATED); a {@link Shapes shape}
		 * otherwise - the {@link Mode#SHAPES_ONLY} guarantee, enforced right here, once. Shared by {@code
		 * measure} AND every {@code QualityRule} kind's own non-conforming evidence (coordinator review,
		 * 2026-09-21: a rule's evidence is one VALUE that failed, never a before/after pair - that concept only
		 * applies to a {@code MappingStep}, see {@link #addStepExample}; the same run had also stored the same
		 * shape twice, hence the dedup here).
		 */
		private void addValueExample(final List<String> pExamples, final String pValue)
		{
			if (pExamples.size() >= 5)
			{
				return;
			}
			if (this.mode == Mode.NONE)
			{
				return; // lot B: no example at all, the counts say enough
			}
			final String shown = this.mode == Mode.SAMPLE_VALUES ? pValue : Shapes.of(pValue);
			if (!pExamples.contains(shown))
			{
				pExamples.add(shown);
			}
		}


		// ---- desk lot L4: where a rule fails ----

		/** The failure breakdown of the rule on this column's joint counts, as the model reads it - empty when nothing fails or no other field separates the failures. */
		private String breakdownSuffix(final String pKind, final String pArgument, final ColumnEvidence pEvidence)
		{
			final FailureBreakdown.Result result = QualityLlmPass.breakdown(pKind, pArgument, pEvidence, this.lookups, this.mode);
			return result == null ? "" : FailureBreakdown.promptText(result, this.mode == Mode.SAMPLE_VALUES);
		}

		/** What a condition given with a proposal comes to once validated against the measured groups. */
		private static final class Condition
		{
			final String attribute;
			final List<String> values;
			final FailureBreakdown.Result result;
			/** Rows seen and failing within the condition. */
			final long seen;
			final long failing;

			Condition(final String pAttribute, final List<String> pValues, final FailureBreakdown.Result pResult, final long pSeen, final long pFailing)
			{
				this.attribute = pAttribute;
				this.values = pValues;
				this.result = pResult;
				this.seen = pSeen;
				this.failing = pFailing;
			}
		}

		/** The condition active during one {@code propose_quality_rule} call (the gates of the propose methods read the figures within it), or {@code null}. */
		private Condition activeCondition;

		/** {@code {seen, failing}} to judge a proposal by: the figures within the active condition, otherwise the ones measured on the whole column. */
		private long[] gateFigures(final long pSeen, final long pFailing)
		{
			return this.activeCondition == null ? new long[] { pSeen, pFailing } : new long[] { this.activeCondition.seen, this.activeCondition.failing };
		}

		private static List<String> stringList(final JsonNode pNode)
		{
			final List<String> out = new ArrayList<String>();
			if (pNode != null && pNode.isArray())
			{
				for (final JsonNode item : pNode)
				{
					if (item.isTextual() || item.isNumber())
					{
						out.add(item.asText());
					}
				}
			}
			return out;
		}

		// ---- propose_mapping_step ----

		LocalTool.Result proposeMappingStep(final JsonNode pArguments)
		{
			if (this.stopped)
			{
				return LocalTool.Result.error(Session.STOPPED_MESSAGE);
			}
			final String entity = Session.textOf(pArguments, "entity");
			final String attribute = Session.textOf(pArguments, "attribute");
			final String kind = Session.textOf(pArguments, "kind");
			final String argument = Session.textOf(pArguments, "argument");
			final String key = entity + "." + attribute + "." + kind;
			if (this.focus != null)
			{
				return this.refuse(Session.TOOL_STEP, key, RuleFocus.REFUSAL_STEP);
			}

			final ColumnInfo column = entity == null || attribute == null ? null : this.columnsByKey.get(entity + "." + attribute);
			if (column == null)
			{
				return this.refuse(Session.TOOL_STEP, key, "unknown column '" + entity + "." + attribute
					+ "'. Use one of the exact entity/attribute names listed in the prompt.");
			}
			if (kind == null || !QualityLlmPass.MAPPING_STEP_KINDS.contains(kind))
			{
				return this.refuse(Session.TOOL_STEP, key, "'kind' must be one of " + QualityLlmPass.MAPPING_STEP_KINDS + ".");
			}
			for (final ExistingProposal existing : column.existingSteps)
			{
				if (existing.kind.equals(kind))
				{
					this.refusalCounts.remove(Session.TOOL_STEP + "|" + key);
					return LocalTool.Result.ok(kind + " already exists on " + entity + "." + attribute + " (status " + existing.status
						+ ") - nothing to do. Propose a different kind, or move to another column.");
				}
			}
			final String rationale = Session.textOf(pArguments, "rationale");
			if (rationale == null || rationale.trim().isEmpty())
			{
				return this.refuse(Session.TOOL_STEP, key, "'rationale' must not be empty. Give a one or two sentence rationale and retry.");
			}
			// lot M8b (2026-10-04, Vol1m): a case change in front of a regex that reads letter case - structural, no value touched
			final String caseRefusal = CaseOrderRule.stepRefusal(entity + "." + attribute, kind, column.mappingPipelines);
			if (caseRefusal != null)
			{
				return this.refuse(Session.TOOL_STEP, key, caseRefusal);
			}
			String normalisedArgument = argument;
			if (CleaningSteps.TRANSCODE_REFERENCE.equals(kind))
			{
				final String referenceKind = CleaningSteps.jsonStringField(argument == null ? "" : argument, "reference");
				if (referenceKind == null || !this.referenceKinds.contains(referenceKind))
				{
					return this.refuse(Session.TOOL_STEP, key, "'argument' must be {\"reference\":\"<one of the listed reference lists>\"}; got '" + argument
						+ "'. Known reference lists: " + this.referenceKinds + ".");
				}
				normalisedArgument = "{\"reference\":\"" + referenceKind + "\"}";
			}

			final ColumnEvidence evidence = this.evidenceByKey.get(column.key());
			if (evidence == null || evidence.values.isEmpty())
			{
				return this.refuse(Session.TOOL_STEP, key, "no measured values are available for '" + entity + "." + attribute + "': cannot verify this step's effect.");
			}
			long changed = 0;
			long unresolved = 0;
			long seen = 0;
			final List<String> examples = new ArrayList<String>();
			for (final Detectors.ValueCount vc : evidence.values)
			{
				if (vc.value == null)
				{
					continue;
				}
				seen += vc.count;
				final CleaningSteps.Result result = CleaningSteps.applyStep(kind, normalisedArgument, vc.value, null, this.lookups);
				if (result.unresolved)
				{
					unresolved += vc.count;
				}
				else if (result.changed)
				{
					changed += vc.count;
					this.addStepExample(examples, kind, vc.value, result.value);
				}
			}
			if (changed == 0)
			{
				return this.refuse(Session.TOOL_STEP, key,
					"this step would not change any of the " + seen + " measured value(s) of '" + entity + "." + attribute
						+ "': propose a different kind, or drop it - a step that changes nothing is never worth a review line.");
			}
			final String pipelineRefusal = this.pipelineYieldRefusal(column, evidence, kind, normalisedArgument);
			if (pipelineRefusal != null)
			{
				return this.refuse(Session.TOOL_STEP, key, pipelineRefusal);
			}
			if (QualityLlmPass.LOOKUP_STEP_KINDS.contains(kind))
			{
				final long resolved = seen - unresolved;
				if (seen == 0 || resolved * 100L < seen * QualityLlmPass.STEP_MIN_RESOLUTION_PERCENT)
				{
					return this.refuse(Session.TOOL_STEP, key, "only " + resolved + " of " + seen + " measured value(s) resolve against this lookup (below "
						+ QualityLlmPass.STEP_MIN_RESOLUTION_PERCENT + "%): measure first, try a different reference list, or drop it.");
				}
			}
			final boolean sampled = Session.sampled(evidence);
			final String suffix = Detectors.sampledSuffix(sampled, evidence.values.size(), evidence.exactDistinctCount);
			final int confidence = Detectors.cappedConfidence(Session.confidenceOf(pArguments), sampled);
			this.steps.add(
				new ProposedStep(entity, attribute, kind, normalisedArgument, confidence, rationale.trim() + suffix, seen, changed, unresolved, examples));
			this.refusalCounts.remove(Session.TOOL_STEP + "|" + key);
			return LocalTool.Result.ok(kind + " recorded on " + entity + "." + attribute + ": " + changed + " of " + seen + " measured value(s) would change." + suffix);
		}

		/**
		 * Lot M8b (2026-10-04): a step that turns a regex pipeline which yielded values on the sample into one that yields nothing on every one. Measured in
		 * Java ({@link RegexSampleCheck}, the existing non-rejected steps of the column in order, then this one, then the pipeline up to its last regex)
		 * on the evidence values, only for a column with ONE mapping (its evidence is that mapping's own sample) and a pipeline the check can reproduce;
		 * anything else answers {@code null} and is skipped silently. A pipeline that already yielded nothing before this step is not this step's fault.
		 * What the message shows of the two inputs follows the project's value-sharing mode: the values in {@link Mode#SAMPLE_VALUES}, their shapes in
		 * {@link Mode#SHAPES_ONLY}, nothing in {@link Mode#NONE}.
		 */
		private String pipelineYieldRefusal(final ColumnInfo pColumn, final ColumnEvidence pEvidence, final String pKind, final String pArgument)
		{
			if (!pColumn.pipelineSampleExact || pColumn.mappingPipelines.isEmpty() || pEvidence == null)
			{
				return null;
			}
			final List<String> samples = new ArrayList<String>();
			for (final Detectors.ValueCount vc : pEvidence.values)
			{
				if (vc.value != null)
				{
					samples.add(vc.value);
				}
			}
			final List<CleaningSteps.Step> before = new ArrayList<CleaningSteps.Step>();
			for (final ExistingProposal existing : pColumn.existingSteps)
			{
				if (!"REJECTED".equals(existing.status))
				{
					before.add(new CleaningSteps.Step(existing.kind, existing.argument));
				}
			}
			final List<CleaningSteps.Step> after = new ArrayList<CleaningSteps.Step>(before);
			after.add(new CleaningSteps.Step(pKind, pArgument));
			for (final String pipeline : pColumn.mappingPipelines)
			{
				final RegexSampleCheck.Outcome without = RegexSampleCheck.measure(before, pipeline, samples, this.lookups);
				final RegexSampleCheck.Outcome with = RegexSampleCheck.measure(after, pipeline, samples, this.lookups);
				if (without == null || with == null || without.yielded == 0 || !with.yieldedNothing())
				{
					continue;
				}
				final List<String> shown = new ArrayList<String>();
				for (final String input : with.emptyInputs)
				{
					this.addValueExample(shown, input);
				}
				return "with " + pKind + " ahead of it, the mapping's pipeline '" + pipeline + "' yields nothing on any of the " + with.tried + " non-empty sample value(s) of '"
					+ pColumn.entity + "." + pColumn.attribute + "', where it yielded a value on " + without.yielded + " without it" + (shown.isEmpty() ? ""
						: " (inputs that give nothing: " + QualityLlmPass.join(shown) + ")")
					+ ". A cleaning step runs on the raw text BEFORE the mapping's own pipeline: drop this step, or move its effect to the END of the pipeline (a pipeline step after its regex).";
			}
			return null;
		}

		/** A real "before -> after" pair only for {@code EMPTY_TO_NULL}'s matched sentinel token (a small, fixed, published list - never real column data) and, in {@link Mode#SAMPLE_VALUES}, every kind; a {@link Shapes shape} otherwise. */
		private void addStepExample(final List<String> pExamples, final String pKind, final String pBefore, final String pAfter)
		{
			if (pExamples.size() >= 5)
			{
				return;
			}
			if (this.mode == Mode.NONE)
			{
				return; // lot B: no example at all
			}
			final boolean realValueSafe = this.mode == Mode.SAMPLE_VALUES || CleaningSteps.EMPTY_TO_NULL.equals(pKind);
			pExamples.add(realValueSafe ? pBefore + " -> " + pAfter : Shapes.pair(pBefore, pAfter));
		}

		// ---- propose_quality_rule ----

		LocalTool.Result proposeQualityRule(final JsonNode pArguments)
		{
			if (this.stopped)
			{
				return LocalTool.Result.error(Session.STOPPED_MESSAGE);
			}
			final String entity = Session.textOf(pArguments, "entity");
			final String attribute = Session.textOf(pArguments, "attribute");
			final String kind = Session.textOf(pArguments, "kind");
			final String argument = Session.textOf(pArguments, "argument");
			final String severity = Session.textOf(pArguments, "severity");
			final String key = entity + "." + attribute + "." + kind;

			final ColumnInfo column = entity == null || attribute == null ? null : this.columnsByKey.get(entity + "." + attribute);
			if (column == null)
			{
				return this.refuse(Session.TOOL_RULE, key, "unknown column '" + entity + "." + attribute
					+ "'. Use one of the exact entity/attribute names listed in the prompt.");
			}
			if (this.focus != null)
			{
				final String problem = this.focus.proposalProblem(kind, argument, Session.textOf(pArguments, "conditionAttribute"));
				if (problem != null)
				{
					return this.refuse(Session.TOOL_RULE, key, problem);
				}
			}
			if (kind == null || !QualityLlmPass.QUALITY_RULE_KINDS.contains(kind))
			{
				return this.refuse(Session.TOOL_RULE, key, "'kind' must be one of " + QualityLlmPass.QUALITY_RULE_KINDS + ".");
			}
			// structural, type-based guard (coordinator review round 3, 2026-09-21) - same rule as measure(),
			// repeated here since a model can call propose_quality_rule directly without measuring first
			if ((QualityDetectors.PATTERN.equals(kind) || QualityDetectors.CHECK_DIGIT.equals(kind)) && !Detectors.isTextLike(column.dataType))
			{
				return this.refuse(Session.TOOL_RULE, key, "'" + entity + "." + attribute + "' is typed " + column.dataType
					+ ", not a string: PATTERN/CHECK_DIGIT only make sense on a STRING attribute - the value is already parsed by the time either would run. "
					+ "Propose a different kind, or drop it.");
			}
			// desk lot L4: an optional condition (another attribute of the entity + values of it actually measured)
			final String conditionAttribute = Session.textOf(pArguments, "conditionAttribute");
			final List<String> conditionValues = Session.stringList(pArguments.path("conditionValues"));
			Condition condition = null;
			if (conditionAttribute != null || !conditionValues.isEmpty())
			{
				final LocalTool.Result[] refusal = new LocalTool.Result[1];
				condition = this.readCondition(key, entity, attribute, kind, argument, severity, conditionAttribute, conditionValues, refusal);
				if (condition == null)
				{
					return refusal[0];
				}
			}
			// identity is (kind, argument), never kind alone (coordinator review round 3, 2026-09-21: two PATTERN
			// rows with two different regexes are two different hypotheses) - and a NON-REJECTED match always
			// wins over a REJECTED one, checked in that order, regardless of which order EBX happens to return
			// the rows in: a real pair (rows 28/29, Company.country REFERENCE COUNTRY - one PROPOSED, its exact
			// duplicate REJECTED to remove the duplicate, not the rule) must read as "already exists, status
			// PROPOSED", never as "was rejected"
			// desk lot L4: the same rule, still open and unconditional, may be given a condition: that updates it instead of being "already there"
			boolean updatesExisting = false;
			for (final ExistingProposal existing : column.existingRules)
			{
				if (condition != null && existing.kind.equals(kind) && java.util.Objects.equals(existing.argument, argument) && "PROPOSED".equals(existing.status)
					&& !existing.hasCondition)
				{
					updatesExisting = true;
				}
			}
			for (final ExistingProposal existing : column.existingRules)
			{
				if (updatesExisting)
				{
					break;
				}
				if (existing.kind.equals(kind) && java.util.Objects.equals(existing.argument, argument) && !"REJECTED".equals(existing.status))
				{
					this.refusalCounts.remove(Session.TOOL_RULE + "|" + key);
					return LocalTool.Result.ok(kind + " already exists on " + entity + "." + attribute + " (status " + existing.status
						+ ") - nothing to do. Propose a different kind, or move to another column.");
				}
			}
			for (final ExistingProposal existing : column.existingRules)
			{
				if (!updatesExisting && existing.kind.equals(kind) && java.util.Objects.equals(existing.argument, argument) && "REJECTED".equals(existing.status))
				{
					this.refusalCounts.remove(Session.TOOL_RULE + "|" + key);
					return LocalTool.Result.ok(kind + (argument == null ? "" : " (" + argument + ")") + " on " + entity + "." + attribute
						+ " was already proposed and REJECTED by a reviewer - do not repropose the exact same form. Try a genuinely different hypothesis "
						+ "(a different argument), or move on.");
				}
			}
			// same-run dedup (coordinator review round 3, 2026-09-21): a real run wrote the identical
			// (attribute, kind, argument) rule TWICE in one run - column.existingRules only knows what existed
			// BEFORE this run started, so a kind+argument this run already proposed for this column must be
			// checked separately, the same "informational, not an error" way as the pre-existing check above
			for (final ProposedRule already : this.rules)
			{
				if (!updatesExisting && already.entity.equals(entity) && already.attribute.equals(attribute) && already.kind.equals(kind)
					&& java.util.Objects.equals(already.argument, argument))
				{
					this.refusalCounts.remove(Session.TOOL_RULE + "|" + key);
					return LocalTool.Result.ok(kind + " was already proposed on " + entity + "." + attribute
						+ " earlier in this run - nothing to do. Propose a different kind, or move to another column.");
				}
			}
			if (severity == null || !QualityLlmPass.PROPOSABLE_SEVERITIES.contains(severity))
			{
				return this.refuse(Session.TOOL_RULE, key,
					"'severity' must be WARN or MODEL_CONSTRAINT - never REJECT: propose WARN and a person will raise the severity once the data is "
						+ "judged trustworthy enough for that.");
			}
			final String rationale = Session.textOf(pArguments, "rationale");
			if (rationale == null || rationale.trim().isEmpty())
			{
				return this.refuse(Session.TOOL_RULE, key, "'rationale' must not be empty. Give a one or two sentence rationale and retry.");
			}
			final ColumnEvidence evidence = this.evidenceByKey.get(column.key());

			if (QualityDetectors.MANDATORY.equals(kind))
			{
				return this.proposeMandatory(key, entity, attribute, severity, rationale, pArguments, column);
			}
			if (QualityDetectors.UNIQUE.equals(kind))
			{
				return this.proposeUnique(key, entity, attribute, severity, rationale, pArguments, evidence);
			}
			final int before = this.rules.size();
			this.activeCondition = condition;
			final LocalTool.Result result;
			try
			{
				if (QualityDetectors.PATTERN.equals(kind))
				{
					result = this.proposePattern(key, entity, attribute, argument, severity, rationale, pArguments, evidence);
				}
				else if (QualityDetectors.REFERENCE.equals(kind))
				{
					result = this.proposeReference(key, entity, attribute, argument, severity, rationale, pArguments, evidence);
				}
				else
				{
					// CHECK_DIGIT
					result = this.proposeCheckDigit(key, entity, attribute, argument, severity, rationale, pArguments, evidence);
				}
			}
			finally
			{
				this.activeCondition = null;
			}
			if (this.rules.size() > before)
			{
				// desk lot L4: the measured fact and the condition go onto the rule the propose method just recorded
				final ProposedRule recorded = this.rules.remove(this.rules.size() - 1);
				final FailureBreakdown.Result breakdown = condition != null ? condition.result
					: QualityLlmPass.breakdown(kind, argument, evidence, this.lookups, this.mode);
				this.rules.add(new ProposedRule(recorded.entity, recorded.attribute, recorded.kind, recorded.argument, recorded.severity, recorded.confidence,
					recorded.rationale, recorded.valuesSeen, recorded.valuesFailing, recorded.examples, FailureBreakdown.toJson(breakdown),
					condition == null ? null : condition.attribute, condition == null ? null : condition.values, updatesExisting));
			}
			return result;
		}

		/**
		 * Validates a condition proposed with a rule: PATTERN/REFERENCE/CHECK_DIGIT at WARN only (a MODEL_CONSTRAINT becomes a model facet, which cannot be conditional), an
		 * attribute for which this column has joint counts (so one that exists in the entity and was measured), values that occur in its measured groups. {@code null} with the
		 * reason in {@code pRefusal[0]} otherwise.
		 */
		private Condition readCondition(final String pKey, final String pEntity, final String pAttribute, final String pKind, final String pArgument, final String pSeverity,
			final String pConditionAttribute, final List<String> pConditionValues, final LocalTool.Result[] pRefusal)
		{
			final String where = " (conditionAttribute and conditionValues go together)";
			if (pConditionAttribute == null || pConditionValues.isEmpty())
			{
				pRefusal[0] = this.refuse(Session.TOOL_RULE, pKey, "give both conditionAttribute and conditionValues, or neither" + where + ".");
				return null;
			}
			if (!QualityLlmPass.MEASURE_KINDS.contains(pKind) || !QualityDetectors.WARN.equals(pSeverity))
			{
				pRefusal[0] = this.refuse(Session.TOOL_RULE, pKey, "a condition is only possible on a PATTERN, REFERENCE or CHECK_DIGIT rule at WARN: a MODEL_CONSTRAINT "
					+ "becomes a facet of the model, which cannot depend on another field.");
				return null;
			}
			final ColumnInfo column = this.columnsByKey.get(pEntity + "." + pAttribute);
			final ColumnEvidence evidence = column == null ? null : this.evidenceByKey.get(column.key());
			if (evidence == null || evidence.joints.isEmpty())
			{
				pRefusal[0] = this.refuse(Session.TOOL_RULE, pKey, "no other field of '" + pEntity + "' was measured against this column, so no condition can be checked: "
					+ "propose the rule without one.");
				return null;
			}
			FailureBreakdown.Joint joint = null;
			final List<String> names = new ArrayList<String>();
			for (final FailureBreakdown.Joint candidate : evidence.joints)
			{
				names.add(candidate.groupAttribute);
				if (candidate.groupAttribute.equalsIgnoreCase(pConditionAttribute.trim()))
				{
					joint = candidate;
				}
			}
			if (joint == null)
			{
				pRefusal[0] = this.refuse(Session.TOOL_RULE, pKey, "'" + pConditionAttribute + "' is not a field measured against this column. Use one of " + names + ".");
				return null;
			}
			final FailureBreakdown.Judge judge = this.judgeFor(pKind, pArgument);
			if (judge == null)
			{
				pRefusal[0] = this.refuse(Session.TOOL_RULE, pKey, "the rule itself cannot be evaluated; fix its argument before adding a condition.");
				return null;
			}
			final FailureBreakdown.Result result = FailureBreakdown.groupBy(joint, judge, QualityLlmPass.shower(this.mode));
			if (result == null)
			{
				pRefusal[0] = this.refuse(Session.TOOL_RULE, pKey, "'" + pConditionAttribute + "' has no usable groups on these rows.");
				return null;
			}
			final String problem = FailureBreakdown.conditionProblem(result, pConditionValues);
			if (problem != null)
			{
				pRefusal[0] = this.refuse(Session.TOOL_RULE, pKey, problem + ".");
				return null;
			}
			final List<String> canonical = new ArrayList<String>();
			for (final String value : result.values())
			{
				if (FailureBreakdown.contains(pConditionValues, value))
				{
					canonical.add(value);
				}
			}
			final long[] within = result.within(canonical);
			return new Condition(joint.groupAttribute, canonical, result, within[0], within[1]);
		}

		private FailureBreakdown.Judge judgeFor(final String pKind, final String pArgument)
		{
			return QualityLlmPass.judge(pKind, pArgument, this.lookups);
		}

		private LocalTool.Result proposeMandatory(final String pKey, final String pEntity, final String pAttribute, final String pSeverity,
			final String pRationale, final JsonNode pArguments, final ColumnInfo pColumn)
		{
			if (pColumn.fillRatePercent == null)
			{
				return this.refuse(Session.TOOL_RULE, pKey,
					"no fill-rate profile is available for '" + pEntity + "." + pAttribute + "': cannot verify this rule, propose something else.");
			}
			if (pColumn.fillRatePercent.doubleValue() < QualityLlmPass.MANDATORY_MIN_FILL_RATE_PERCENT)
			{
				return this.refuse(Session.TOOL_RULE, pKey,
					"the fill rate of '" + pEntity + "." + pAttribute + "' is only " + pColumn.fillRatePercent + "% (needs >= "
						+ QualityLlmPass.MANDATORY_MIN_FILL_RATE_PERCENT + "%): this column has real blanks, do not propose MANDATORY.");
			}
			final int confidence = Session.confidenceOf(pArguments);
			this.rules.add(new ProposedRule(pEntity, pAttribute, QualityDetectors.MANDATORY, null, pSeverity, confidence, pRationale.trim(),
				pColumn.rows == null ? 0 : pColumn.rows.longValue(), 0, java.util.Collections.<String>emptyList()));
			this.refusalCounts.remove(Session.TOOL_RULE + "|" + pKey);
			return LocalTool.Result.ok("MANDATORY recorded on " + pEntity + "." + pAttribute + " (fill rate " + pColumn.fillRatePercent + "%).");
		}

		/**
		 * {@code UNIQUE} is the one kind whose COUNT is EXACT (coordinator review, 2026-09-21), never derived
		 * from the capped sample: {@code duplicateRows = exactFilledRows - exactDistinctCount} (a value's normal
		 * identity here is "distinct" in the plain SQL sense - {@code NORMALISE_IDENTIFIER}-normalised
		 * duplicates that only differ by punctuation/case would be undercounted by the exact SQL {@code
		 * COUNT(DISTINCT)}, which is a raw-string comparison; this is a deliberate, documented under-count, not
		 * an over-count, so it never causes a false "this column has duplicates" verdict - see {@code
		 * docs/QUALITY-LLM.md}). Only the ILLUSTRATIVE examples (which values look duplicated) still come from
		 * the sample, exactly as before - see the class Javadoc's own note that this part was already fine.
		 */
		private LocalTool.Result proposeUnique(final String pKey, final String pEntity, final String pAttribute, final String pSeverity, final String pRationale,
			final JsonNode pArguments, final ColumnEvidence pEvidence)
		{
			if (pEvidence == null || pEvidence.values.isEmpty())
			{
				return this.refuse(Session.TOOL_RULE, pKey, "no measured values are available for '" + pEntity + "." + pAttribute + "'.");
			}
			final long exactDuplicateRows = pEvidence.exactFilledRows - pEvidence.exactDistinctCount;
			if (exactDuplicateRows <= 0)
			{
				return this.refuse(Session.TOOL_RULE, pKey, "no duplicate value was found among the " + pEvidence.exactFilledRows + " (exact) filled value(s) of '"
					+ pEntity + "." + pAttribute + "': nothing to propose here.");
			}
			final Map<String, Long> countByValue = new LinkedHashMap<String, Long>();
			for (final Detectors.ValueCount vc : pEvidence.values)
			{
				if (vc.value == null)
				{
					continue;
				}
				final String norm = CleaningSteps.applyStep(CleaningSteps.NORMALISE_IDENTIFIER, QualityLlmPass.IDENTIFIER_SEPARATORS, vc.value, null, null).value;
				final Long existing = countByValue.get(norm);
				countByValue.put(norm, Long.valueOf((existing == null ? 0L : existing.longValue()) + vc.count));
			}
			final List<String> examples = new ArrayList<String>();
			for (final Map.Entry<String, Long> entry : countByValue.entrySet())
			{
				if (entry.getValue().longValue() > 1 && examples.size() < 5 && this.mode != Mode.NONE)
				{
					examples.add(Shapes.of(entry.getKey()));
				}
			}
			// the count is exact and needs no sampling caveat; the ILLUSTRATIONS may still be drawn from a
			// truncated sample, worth one honest word about (never a confidence cap: the count itself is exact)
			final boolean sampled = Session.sampled(pEvidence);
			final String exampleCaveat = sampled
				? " (examples drawn from the " + pEvidence.values.size() + " most frequent distinct value(s) out of " + pEvidence.exactDistinctCount
					+ "; the duplicate count itself is exact)"
				: "";
			final int confidence = Session.confidenceOf(pArguments);
			this.rules.add(new ProposedRule(pEntity, pAttribute, QualityDetectors.UNIQUE, null, pSeverity, confidence, pRationale.trim() + exampleCaveat,
				pEvidence.exactFilledRows, exactDuplicateRows, examples));
			this.refusalCounts.remove(Session.TOOL_RULE + "|" + pKey);
			return LocalTool.Result.ok(
				"UNIQUE recorded on " + pEntity + "." + pAttribute + ": " + exactDuplicateRows + " of " + pEvidence.exactFilledRows + " (exact) value(s) share a value.");
		}

		private LocalTool.Result proposePattern(final String pKey, final String pEntity, final String pAttribute, final String pArgument, final String pSeverity,
			final String pRationale, final JsonNode pArguments, final ColumnEvidence pEvidence)
		{
			final Pattern pattern;
			try
			{
				pattern = RegexGuard.compile(pArgument == null ? "" : pArgument);
			}
			catch (final PatternSyntaxException ex)
			{
				return this.refuse(Session.TOOL_RULE, pKey, "'" + pArgument + "' is not a valid regular expression: " + ex.getMessage() + ". Measure it first.");
			}
			if (pEvidence == null || pEvidence.values.isEmpty())
			{
				return this.refuse(Session.TOOL_RULE, pKey, "no measured values are available for '" + pEntity + "." + pAttribute + "'.");
			}
			long seen = 0;
			long failing = 0;
			final List<String> examples = new ArrayList<String>();
			for (final Detectors.ValueCount vc : pEvidence.values)
			{
				if (vc.value == null)
				{
					continue;
				}
				seen += vc.count;
				final boolean matches;
				try
				{
					matches = RegexGuard.matches(pattern, vc.value.trim().toUpperCase(Locale.ROOT));
				}
				catch (final RegexGuard.MatchTimeout ex)
				{
					return this.refuse(Session.TOOL_RULE, pKey, "this pattern ran past its time limit on a measured value (" + ex.getMessage()
						+ "): it backtracks too much. Measure a simpler one, with character classes and no repeated group inside a repeated group.");
				}
				if (!matches)
				{
					failing += vc.count;
					this.addValueExample(examples, vc.value);
				}
			}
			// desk lot L4: with a condition, the rule is judged on the rows it covers
			final long[] gate = this.gateFigures(seen, failing);
			final String multiFormatCaveat = QualityLlmPass.multiFormatCaveat(gate[1], gate[0]);
			if (gate[0] == 0 || gate[1] * 100L > gate[0] * QualityLlmPass.PATTERN_MAX_FAILURE_PERCENT)
			{
				return this.refuse(Session.TOOL_RULE, pKey,
					gate[1] + " of " + gate[0] + " measured value(s) fail this pattern (more than " + QualityLlmPass.PATTERN_MAX_FAILURE_PERCENT
						+ "%): measure a different pattern, or this column probably is not what you think it is." + multiFormatCaveat);
			}
			final boolean sampled = Session.sampled(pEvidence);
			final LocalTool.Result constraintRefusal = this.modelConstraintGuard(pKey, pSeverity, failing, sampled);
			if (constraintRefusal != null)
			{
				return constraintRefusal;
			}
			final String suffix = Detectors.sampledSuffix(sampled, pEvidence.values.size(), pEvidence.exactDistinctCount) + multiFormatCaveat;
			// coordinator review, 2026-09-21: stored confidence must never exceed the conforming share - a
			// pattern that only 72% of values match is not "83% certain" whatever the model asked for
			final long conformingPercent = gate[0] == 0 ? 0 : (gate[0] - gate[1]) * 100L / gate[0];
			final int confidence = Math.min(Detectors.cappedConfidence(Session.confidenceOf(pArguments), sampled), (int) conformingPercent);
			this.rules.add(new ProposedRule(pEntity, pAttribute, QualityDetectors.PATTERN, pArgument, pSeverity, confidence, pRationale.trim() + suffix, seen,
				failing, examples));
			this.refusalCounts.remove(Session.TOOL_RULE + "|" + pKey);
			return LocalTool.Result.ok("PATTERN recorded on " + pEntity + "." + pAttribute + ": " + failing + " of " + seen + " measured value(s) fail it." + suffix);
		}

		/**
		 * {@code MODEL_CONSTRAINT} (a real facet/foreign key on the model) is refused for {@code PATTERN}/
		 * {@code CHECK_DIGIT} unless the evidence is airtight: zero failing values, measured over the FULL
		 * column, never a sample. Coordinator review round 3, 2026-09-21: asked to CONFIRM this was already
		 * enforced - it was not; only {@code REFERENCE} had any {@code MODEL_CONSTRAINT}-specific bar (a 90%
		 * resolution threshold, {@link #REFERENCE_MIN_RESOLUTION_FOR_CONSTRAINT_PERCENT}), so a {@code PATTERN}/
		 * {@code CHECK_DIGIT} proposal at {@code MODEL_CONSTRAINT} with a nonzero failure count under the
		 * ordinary WARN-level threshold would have been silently accepted. @return null when the proposal may
		 * proceed, a refusal otherwise.
		 */
		private LocalTool.Result modelConstraintGuard(final String pKey, final String pSeverity, final long pFailing, final boolean pSampled)
		{
			if (!QualityDetectors.MODEL_CONSTRAINT.equals(pSeverity) || (pFailing == 0 && !pSampled))
			{
				return null;
			}
			return this.refuse(Session.TOOL_RULE, pKey, "MODEL_CONSTRAINT needs zero failing values measured over the FULL column, never a sample: "
				+ (pSampled ? "this column was only sampled" : pFailing + " value(s) fail") + " - propose WARN instead, or measure the whole column first.");
		}

		private LocalTool.Result proposeReference(final String pKey, final String pEntity, final String pAttribute, final String pArgument, final String pSeverity,
			final String pRationale, final JsonNode pArguments, final ColumnEvidence pEvidence)
		{
			if (pArgument == null || !this.referenceKinds.contains(pArgument))
			{
				return this.refuse(Session.TOOL_RULE, pKey, "unknown reference list '" + pArgument + "'. Use one of " + this.referenceKinds + ".");
			}
			if (pEvidence == null || pEvidence.values.isEmpty())
			{
				return this.refuse(Session.TOOL_RULE, pKey, "no measured values are available for '" + pEntity + "." + pAttribute + "'.");
			}
			long seen = 0;
			long unresolved = 0;
			final List<String> examples = new ArrayList<String>();
			for (final Detectors.ValueCount vc : pEvidence.values)
			{
				if (vc.value == null)
				{
					continue;
				}
				seen += vc.count;
				if (this.lookups.resolveFlat(pArgument, vc.value) == null)
				{
					unresolved += vc.count;
					this.addValueExample(examples, vc.value);
				}
			}
			final long[] gate = this.gateFigures(seen, unresolved);
			final long resolved = gate[0] - gate[1];
			final int threshold = QualityDetectors.MODEL_CONSTRAINT.equals(pSeverity) ? QualityLlmPass.REFERENCE_MIN_RESOLUTION_FOR_CONSTRAINT_PERCENT
				: QualityLlmPass.REFERENCE_MIN_RESOLUTION_FOR_WARN_PERCENT;
			if (gate[0] == 0 || resolved * 100L < gate[0] * threshold)
			{
				return this.refuse(Session.TOOL_RULE, pKey,
					"only " + resolved + " of " + gate[0] + " measured value(s) resolve against '" + pArgument + "' (needs >= " + threshold + "% for severity "
						+ pSeverity + "): measure first, try a different reference list, or " + (QualityDetectors.MODEL_CONSTRAINT.equals(pSeverity)
							? "propose WARN instead." : "drop it."));
			}
			final boolean sampled = Session.sampled(pEvidence);
			final String suffix = Detectors.sampledSuffix(sampled, pEvidence.values.size(), pEvidence.exactDistinctCount);
			final int confidence = Detectors.cappedConfidence(Session.confidenceOf(pArguments), sampled);
			this.rules.add(new ProposedRule(pEntity, pAttribute, QualityDetectors.REFERENCE, pArgument, pSeverity, confidence, pRationale.trim() + suffix, seen,
				unresolved, examples));
			this.refusalCounts.remove(Session.TOOL_RULE + "|" + pKey);
			return LocalTool.Result.ok("REFERENCE recorded on " + pEntity + "." + pAttribute + ": " + resolved + " of " + gate[0] + " measured value(s) resolve." + suffix);
		}

		private LocalTool.Result proposeCheckDigit(final String pKey, final String pEntity, final String pAttribute, final String pArgument, final String pSeverity,
			final String pRationale, final JsonNode pArguments, final ColumnEvidence pEvidence)
		{
			// PROPOSABLE list only (coordinator review round 3, 2026-09-21) - see the matching comment in measure()
			if (pArgument == null || !QualityDetectors.CHECK_DIGIT_ALGORITHMS_PROPOSABLE.contains(pArgument))
			{
				return this.refuse(Session.TOOL_RULE, pKey,
					"unknown check-digit algorithm '" + pArgument + "'. Use one of " + QualityDetectors.CHECK_DIGIT_ALGORITHMS_PROPOSABLE + ".");
			}
			if (pEvidence == null || pEvidence.values.isEmpty())
			{
				return this.refuse(Session.TOOL_RULE, pKey, "no measured values are available for '" + pEntity + "." + pAttribute + "'.");
			}
			long seen = 0;
			long failing = 0;
			final List<String> examples = new ArrayList<String>();
			for (final Detectors.ValueCount vc : pEvidence.values)
			{
				if (vc.value == null)
				{
					continue;
				}
				seen += vc.count;
				if (!QualityDetectors.isCheckDigitValid(pArgument, vc.value))
				{
					failing += vc.count;
					this.addValueExample(examples, vc.value);
				}
			}
			final long[] gate = this.gateFigures(seen, failing);
			if (gate[0] == 0 || gate[1] * 100L > gate[0] * QualityLlmPass.CHECK_DIGIT_MAX_FAILURE_PERCENT)
			{
				return this.refuse(Session.TOOL_RULE, pKey,
					gate[1] + " of " + gate[0] + " measured value(s) fail the " + pArgument + " check digit (more than " + QualityLlmPass.CHECK_DIGIT_MAX_FAILURE_PERCENT
						+ "%): measure a different algorithm, or this probably is not a " + pArgument + " column.");
			}
			final boolean sampled = Session.sampled(pEvidence);
			final LocalTool.Result constraintRefusal = this.modelConstraintGuard(pKey, pSeverity, failing, sampled);
			if (constraintRefusal != null)
			{
				return constraintRefusal;
			}
			final String suffix = Detectors.sampledSuffix(sampled, pEvidence.values.size(), pEvidence.exactDistinctCount);
			final int confidence = Detectors.cappedConfidence(Session.confidenceOf(pArguments), sampled);
			this.rules.add(new ProposedRule(pEntity, pAttribute, QualityDetectors.CHECK_DIGIT, pArgument, pSeverity, confidence, pRationale.trim() + suffix, seen,
				failing, examples));
			this.refusalCounts.remove(Session.TOOL_RULE + "|" + pKey);
			return LocalTool.Result.ok("CHECK_DIGIT (" + pArgument + ") recorded on " + pEntity + "." + pAttribute + ": " + failing + " of " + seen
				+ " measured value(s) fail it." + suffix);
		}

		// ---- note ----

		/**
		 * Two forms - see {@link #NOTE_SCHEMA}'s own Javadoc for why. The PREFERRED {@code notes} array is tried
		 * first: one call records every column in the list, each independently resolved and truncated, and never
		 * refused as a whole for one bad entry (a model that gets one name slightly wrong should not lose the
		 * other nine notes in the same call). Falls back to the legacy single {@code text}/{@code entity}/
		 * {@code attribute} form when {@code notes} is absent or empty; an empty call (neither given) is the only
		 * case actually refused.
		 * <p>
		 * {@code entity}/{@code attribute}, when they resolve to a real column of THIS run, mark that column
		 * covered ({@link #notedColumnKeys}, read by {@link #columnsWithoutProposalOrNote()}) - never enforced (a
		 * note is never refused for missing them, and an unresolved entity/attribute pair is not an error either),
		 * only used to make the coverage gap visible afterwards. An entry's own {@code entity} is optional -
		 * {@link #resolveColumnKey} falls back to matching the {@code attribute} alone against this run's columns
		 * when it is unambiguous, which is the normal case (a real run's columns all share one entity - see
		 * {@code ebx.QualityLlmSuggester}).
		 */
		LocalTool.Result note(final JsonNode pArguments)
		{
			if (this.stopped)
			{
				return LocalTool.Result.error(Session.STOPPED_MESSAGE);
			}
			final JsonNode notesArray = pArguments.path("notes");
			if (notesArray.isArray() && notesArray.size() > 0)
			{
				int recorded = 0;
				for (final JsonNode item : notesArray)
				{
					final String attribute = Session.textOf(item, "attribute");
					final String reason = Session.firstNonBlank(Session.textOf(item, "reason"), Session.textOf(item, "text"));
					if (attribute == null || reason == null || reason.trim().isEmpty())
					{
						continue;
					}
					final String entity = Session.textOf(item, "entity");
					this.recordNote(entity, attribute, reason);
					recorded++;
				}
				if (recorded > 0)
				{
					return LocalTool.Result.ok("noted " + recorded + " column(s)");
				}
				// every entry was malformed (no attribute, or no reason/text): fall through to the legacy form
				// below in case the model ALSO sent a top-level text (unlikely, but never lose a genuine note)
			}
			final String text = Session.textOf(pArguments, "text");
			if (text == null || text.trim().isEmpty())
			{
				return LocalTool.Result.error("Either 'notes' (a list of {attribute, reason}) or 'text' must be given, and non-empty.");
			}
			final String entity = Session.textOf(pArguments, "entity");
			final String attribute = Session.textOf(pArguments, "attribute");
			final String columnKey = this.recordNote(entity, attribute, text);
			return LocalTool.Result.ok(columnKey == null ? "noted" : "noted for " + columnKey);
		}

		/** Resolves {@code pEntity}/{@code pAttribute} to a column of this run (see {@link #resolveColumnKey}), records the truncated reason, and marks the column covered when resolved. @return the resolved column key, or null when it did not resolve (the note is still recorded as free text). */
		private String recordNote(final String pEntity, final String pAttribute, final String pReasonOrText)
		{
			final String reason = Session.truncateNote(pReasonOrText);
			final String columnKey = this.resolveColumnKey(pEntity, pAttribute);
			if (columnKey != null)
			{
				this.notedColumnKeys.add(columnKey);
				this.notes.add(columnKey + ": " + reason);
				return columnKey;
			}
			this.notes.add(pAttribute == null ? reason : pAttribute + ": " + reason);
			return null;
		}

		/**
		 * {@code pEntity}.{@code pAttribute} when both are given and name a real column of this run; otherwise,
		 * when {@code pAttribute} alone matches EXACTLY ONE column's attribute name across every entity of this
		 * run, that column's key - the normal case, since a real per-entity run's columns all share one entity
		 * (see {@code ebx.QualityLlmSuggester}). Null when nothing resolves (an unknown attribute, or an
		 * ambiguous one across more than one entity with no {@code entity} given to disambiguate) - never an
		 * error, the caller still records the note as free text.
		 */
		private String resolveColumnKey(final String pEntity, final String pAttribute)
		{
			if (pAttribute == null)
			{
				return null;
			}
			if (pEntity != null)
			{
				final String columnKey = pEntity + "." + pAttribute;
				return this.columnsByKey.containsKey(columnKey) ? columnKey : null;
			}
			String onlyMatch = null;
			for (final ColumnInfo column : this.columnsByKey.values())
			{
				if (pAttribute.equals(column.attribute))
				{
					if (onlyMatch != null)
					{
						return null; // ambiguous across entities: needs an explicit entity to disambiguate
					}
					onlyMatch = column.key();
				}
			}
			return onlyMatch;
		}

		private static String truncateNote(final String pReason)
		{
			final String trimmed = pReason.trim();
			return trimmed.length() > QualityLlmPass.MAX_NOTE_REASON_LENGTH ? trimmed.substring(0, QualityLlmPass.MAX_NOTE_REASON_LENGTH) + "..." : trimmed;
		}

		private static String firstNonBlank(final String pFirst, final String pSecond)
		{
			if (pFirst != null && !pFirst.trim().isEmpty())
			{
				return pFirst;
			}
			return pSecond != null && !pSecond.trim().isEmpty() ? pSecond : null;
		}

		// ---- refusal bookkeeping ----

		/**
		 * Every refusal in this class goes through here: past {@link #REFUSAL_STOP_THRESHOLD} identical repeats
		 * of the same {@code pTool}/{@code pKey} (tool, entity, attribute, kind), the run is stopped so every
		 * further call - of any tool - is refused outright, forcing the model to a final text turn. Same
		 * mechanism as {@code StagingLlmPass.Session#refuse} (see its own Javadoc for why this is the only
		 * available fallback: neither {@code LocalTool.Result} nor {@code AgentLoop} offer a handler-driven stop).
		 */
		private LocalTool.Result refuse(final String pTool, final String pKey, final String pMessage)
		{
			final String countKey = pTool + "|" + pKey;
			final Integer previous = this.refusalCounts.get(countKey);
			final int count = (previous == null ? 0 : previous.intValue()) + 1;
			this.refusalCounts.put(countKey, Integer.valueOf(count));
			if (count >= Session.REFUSAL_STOP_THRESHOLD)
			{
				this.stopped = true;
				this.stopReason = "'" + pKey + "' was refused " + count + " times in a row by " + pTool + " without a genuine change.";
				this.notes.add("Run stopped early: " + this.stopReason);
				return LocalTool.Result
					.error(pMessage + " Stop proposing '" + pKey + "': this was refused " + count + " times in a row. The run ends now - answer with your final summary.");
			}
			return LocalTool.Result.error(pMessage);
		}

		private static String textOf(final JsonNode pArguments, final String pField)
		{
			final JsonNode value = pArguments.path(pField);
			return value.isTextual() ? value.asText() : null;
		}

		/** Clamped to 0-100 rather than refused: an out-of-range confidence is not a mistake worth making the model retry over. */
		private static int confidenceOf(final JsonNode pArguments)
		{
			final int raw = pArguments.path("confidence").asInt(0);
			return Math.max(0, Math.min(100, raw));
		}
	}
}
