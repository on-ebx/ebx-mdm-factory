package com.on.ebx.factory.staging;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.PromptData;
import com.on.ebx.factory.mappingmatrix.MatrixCompute;
import com.on.ebx.factory.quality.CaseOrderRule;
import com.on.ebx.factory.quality.ChildKeysDerivation;

/**
 * The semantic, language-model second pass of the staging suggestion, rewritten "target model first"
 * (docs/TARGET-MAPPING.md sec 4.2, target-mapping lot 2): a deterministic rule pass already proposed one
 * staging entity per landing table ({@link StagingRules}'s own output, {@code origin=RULE}). This pass no
 * longer reasons in "which rule-proposed entities describe the same thing and which of their attributes
 * align" - it proposes the TARGET model directly (one or more entities, at the finest grain any source has,
 * a flat source forcing a separate child entity the same as a two-table source would), then a tree of {@link
 * TransferFeed} mapping rules that reads every relevant landing table into it. A target entity fed by several
 * landing tables (one root feed per source) is exactly how two applications describing "the same real-world
 * object" get unified in this design - there is no separate "align these two entities" tool any more: the
 * unification falls out of two feeds mapping different columns into the SAME target attribute names.
 * <p>
 * Pure: no EBX class anywhere in this file, same discipline as the previous canonical-entity pass it
 * replaces - {@link #systemPrompt()}, {@link #userPrompt}, the tool JSON schemas and {@link Session}'s
 * decision logic are all plain JDK/Jackson, so an {@code AgentLoop} can be run against them (a fake HTTP
 * provider, real {@code ToolSet}/{@code AgentLoop}) with no EBX runtime at all - see the test. {@code
 * com.on.ebx.factory.ebx.StagingLlmSuggester} is the only place that touches EBX: it reads the project's
 * landing inventory and rule-proposed entities into the value objects below, builds a {@link Session}, runs
 * it through {@code AgentRuntime}, calls {@link Session#finalizeEntities()}, and - only then - writes {@link
 * Session#createdEntities()}/{@link Session#createdFeeds()} as real {@code StagingEntity}/{@code
 * StagingAttribute}/{@code TransferFeed}/{@code AttributeMapping} rows (origin {@code LLM}).
 * <p>
 * <b>Two-stage inventory</b> (the lesson of {@code capability.CapabilityLlmPass}'s own "coordinator,
 * 2026-09-21, blocking" incident: a real run with the whole catalogue dumped into every prompt built a
 * ~30k-token first request, refused outright by the provider's own per-minute limit): {@link #userPrompt}
 * inlines every landing table's full column detail only while the rendered text stays under {@link
 * #INVENTORY_CHAR_BUDGET} (roughly {@link #INVENTORY_TOKEN_BUDGET} tokens); past that, it falls back to a
 * COMPACT catalogue (table names, column counts, key column names only) and the model is expected to call the
 * new read-only local tool {@link Session#describeLandingTable} to see a table's real columns before mapping
 * it - {@link #isCompact} decides this the same way for both the prompt and the session (built from the SAME
 * {@link ProjectInfo}), so the two never disagree about which mode a given run is in.
 * <p>
 * <b>Refusal discipline carried over unchanged from the previous pass</b> (fix C, 2026-09-20, replayed here
 * verbatim since the failure mode is generic to any tool-calling loop, not specific to canonical entities): a
 * call refused three times in a row on the same repeated argument ends the run ({@link Session#refuse}), since
 * neither {@code AgentLoop} nor {@code LocalTool} offers a handler any other way to end a run early.
 * <p>
 * <b>Re-running this pass on the SAME project</b> (docs/TARGET-MAPPING.md, "Relance de la passe LLM" - measured
 * 2026-09-23 on project A, dataset {@code MdmFactory}): idempotence used to stop at the ENTITY name (an
 * already-{@code PROPOSED} entity of the exact same name is adopted, never recreated - see {@link
 * #proposeTargetEntity}), but a second run still (a) recreated a root feed already covering the same (entity,
 * landing table) pair, since {@link Session#proposeFeed}'s own duplicate check only ever looked at THIS run's
 * feeds, and (b) reproposed the SAME child entity under a different name ({@code Address} instead of the
 * already-existing {@code Adresse}, same seven attributes) - name-only idempotence has nothing to say about a
 * synonym or a translation. Both are now covered: {@link ExistingTargetEntity#feeds} carries every {@code
 * TransferFeed} the project already had before this run, so {@link Session#proposeFeed} can recognise an
 * already-covered pair (informational no-op, or an explicit refusal when a person {@code REJECTED} it) and
 * attach a NEW child feed to an OLDER parent feed it never created itself; {@link Session#proposeTargetEntity}
 * additionally refuses a new entity whose business attributes (or whose normalized name) exactly match an
 * existing one, naming it so the model reuses it instead. See {@link Session}'s own Javadoc for the mechanics.
 */
public final class StagingLlmPass
{
	/** Same pattern as {@code StagingEntity.name} / {@code StagingAttribute.name} in the XSD: PascalCase or camelCase, never checked here which. */
	public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
	/** A regex letter class limited to ASCII ({@code [A-Za-z]}, {@code [a-zA-Z]}, {@code [A-Z]}...) inside a pipeline - refused, rule (5) of the system prompt. */
	static final Pattern ASCII_LETTER_CLASS = Pattern.compile("\\[\\^?(A-Za-z|a-zA-Z|A-Z|a-z)");
	/** {@code TransferFeed.variant}: an upper-case role name, 40 characters at most (the XSD's own maxLength). */
	static final Pattern VARIANT_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
	/**
	 * A landing table some feed reads may still hold filled columns no rule reads (PersVol4 volume test, 2026-09-26: the
	 * CRM contact's OTHER_* address, 457 of 1194 rows, was left out while its MAILING_* twin was fed). The follow-up
	 * turn asks about them only when at least one is filled in this share of rows, in percent - below it, the column is
	 * only reported.
	 */
	public static final BigDecimal UNREAD_COLUMN_FOLLOW_UP_FILL_RATE = BigDecimal.valueOf(5);

	/**
	 * Evaluates a proposed rule on the landing column's REAL values, in Java - the values never reach the model
	 * (PersVol3 volume test, 2026-09-25: the model copied the value shape "99" into {@code regex(^99\s(.*))} and
	 * emptied the street of 2 436 addresses; nothing checked a rule against the data before it was recorded).
	 * Implemented by {@code ebx.StagingLlmSuggester}; a session without a probe (tests, a run with no readable
	 * landing) simply skips the check.
	 */
	public interface RuleProbe
	{
		/** The rule applied to a capped sample of {@code pLandingColumn}; null when the column cannot be sampled. */
		ProbeResult probe(String pLandingTableKey, String pLandingColumn, String pPipeline);

		/**
		 * Staging feed-conversion lot E (2026-10-17): whether the rule gives the typed target ({@code DATE}, {@code TIMESTAMP}, {@code
		 * DECIMAL}, {@code INTEGER}, {@code BOOLEAN}) the xs lexical form it stores, judged on the column's own distinct values (up to 2000,
		 * with their rows) by {@link TransformVerifier}, the very check the normalisation pass runs after the model - including the
		 * replacement the column's measured value profile proposes ({@link TransformVerifier#FIXED}). Counts and pipeline text only, never
		 * a value. {@code pPipeline} is blank for a rule without one. Null when it cannot be judged (no probe, unreadable column): the
		 * session then keeps its earlier checks.
		 */
		default TransformVerifier.Result verifyTyped(final String pLandingTableKey, final String pLandingColumn, final String pPipeline, final String pDataType)
		{
			return null;
		}
	}

	/**
	 * What {@link RuleProbe#probe} measured: filled input values tried, how many came out empty, the shapes on each side
	 * (never a value), and - since the lossy-map fix (PersVol4, 2026-09-26) - how many DISTINCT values went in and came
	 * out, how many of them were folded onto a shared output by unrelated inputs, and how many fell through a
	 * {@code map(...)} without being listed. Counts only: the values stay in Java.
	 */
	public static final class ProbeResult
	{
		public final int filled;
		public final int emptied;
		public final List<String> inputShapes;
		public final List<String> outputShapes;
		/** Distinct non-empty input values of the sample. */
		public final int distinctInputs;
		/** Distinct non-empty output values. */
		public final int distinctOutputs;
		/** Distinct inputs that reach an output which {@link StagingLlmPass#FOLD_MIN_UNRELATED} or more UNRELATED inputs share - see {@link #of}. */
		public final int foldedInputs;
		/** Sampled inputs not listed in a {@code map(...)} step of the pipeline (they reach its {@code *=} default, or are emptied without one). */
		public final int unlisted;
		/**
		 * Distinct inputs whose output is a PART of them ({@link StagingLlmPass#isExtraction}: a word, a year) - never
		 * counted in {@link #foldedInputs}: extracting a part of a value loses nothing of what the rule is meant to keep
		 * (PersVol5, 2026-09-26: a first name taken out of full names sharing it was refused as a fold).
		 */
		public final int extractedInputs;
		/** Filled inputs on which the pipeline THREW (an invalid pattern, a malformed step) - also counted in {@link #emptied}. */
		public final int failed;
		/** The first failure's message (one line), or null. */
		public final String failure;
		/**
		 * The case layout of the sampled INPUT values ({@link TokenCase}: where the all-upper-case tokens sit), or null
		 * when not measured (PersVol7, 2026-09-28: a full-name column written in two orders, the capitals marking one
		 * token in 30% of rows and none in the others, was split by position and 312 duplicates were missed).
		 */
		public final TokenCase inputTokenCase;
		/** Same measurement on the non-empty OUTPUT values of the rule, or null. */
		public final TokenCase outputTokenCase;
		/**
		 * For every input layout ({@link TokenCase#layoutOf}) held by a population of the sample, the POSITION the rule
		 * read its output from: {@code FIRST} or {@code LAST} when at least two thirds of that layout's outputs are its
		 * first or last token (letters and digits compared, case and accents ignored), {@code MIXED} otherwise. Empty
		 * when not measured. What tells an order-blind extraction (the same position from every layout) from an
		 * order-aware one (a different position per layout) - see {@link StagingLlmPass#orderRefusal}.
		 */
		public final Map<String, String> positionByLayout;
		/** {@code first-only: FIRST token in 17 of 17, neither: FIRST token in 33 of 33} - the counts behind {@link #positionByLayout}, or null. */
		public final String positionDetail;
		/**
		 * Each filled sampled input -> what the rule made of it (trimmed; null when emptied or failed). Stays in Java:
		 * {@link Session} compares it with the outputs of another rule on the SAME column (PersVol8, 2026-09-28: one
		 * regex proposed for two attributes fed both the same token) - see {@link StagingLlmPass#identicalRefusal}.
		 * Never rendered, never sent to the model.
		 */
		public final Map<String, String> outputByInput;

		public ProbeResult(final int pFilled, final int pEmptied, final List<String> pInputShapes, final List<String> pOutputShapes)
		{
			this(pFilled, pEmptied, pInputShapes, pOutputShapes, 0, 0, 0, 0);
		}

		public ProbeResult(final int pFilled, final int pEmptied, final List<String> pInputShapes, final List<String> pOutputShapes, final int pDistinctInputs,
			final int pDistinctOutputs, final int pFoldedInputs, final int pUnlisted)
		{
			this(pFilled, pEmptied, pInputShapes, pOutputShapes, pDistinctInputs, pDistinctOutputs, pFoldedInputs, pUnlisted, 0, 0, null);
		}

		public ProbeResult(final int pFilled, final int pEmptied, final List<String> pInputShapes, final List<String> pOutputShapes, final int pDistinctInputs,
			final int pDistinctOutputs, final int pFoldedInputs, final int pUnlisted, final int pExtractedInputs, final int pFailed, final String pFailure)
		{
			this(pFilled, pEmptied, pInputShapes, pOutputShapes, pDistinctInputs, pDistinctOutputs, pFoldedInputs, pUnlisted, pExtractedInputs, pFailed, pFailure,
				null, null, null, null);
		}

		public ProbeResult(final int pFilled, final int pEmptied, final List<String> pInputShapes, final List<String> pOutputShapes, final int pDistinctInputs,
			final int pDistinctOutputs, final int pFoldedInputs, final int pUnlisted, final int pExtractedInputs, final int pFailed, final String pFailure,
			final TokenCase pInputTokenCase, final TokenCase pOutputTokenCase, final Map<String, String> pPositionByLayout, final String pPositionDetail)
		{
			this(pFilled, pEmptied, pInputShapes, pOutputShapes, pDistinctInputs, pDistinctOutputs, pFoldedInputs, pUnlisted, pExtractedInputs, pFailed, pFailure,
				pInputTokenCase, pOutputTokenCase, pPositionByLayout, pPositionDetail, null);
		}

		public ProbeResult(final int pFilled, final int pEmptied, final List<String> pInputShapes, final List<String> pOutputShapes, final int pDistinctInputs,
			final int pDistinctOutputs, final int pFoldedInputs, final int pUnlisted, final int pExtractedInputs, final int pFailed, final String pFailure,
			final TokenCase pInputTokenCase, final TokenCase pOutputTokenCase, final Map<String, String> pPositionByLayout, final String pPositionDetail,
			final Map<String, String> pOutputByInput)
		{
			this.outputByInput = pOutputByInput == null ? new LinkedHashMap<String, String>() : pOutputByInput;
			this.inputTokenCase = pInputTokenCase;
			this.outputTokenCase = pOutputTokenCase;
			this.positionByLayout = pPositionByLayout == null ? new LinkedHashMap<String, String>() : pPositionByLayout;
			this.positionDetail = pPositionDetail;
			this.filled = pFilled;
			this.emptied = pEmptied;
			this.inputShapes = pInputShapes == null ? new ArrayList<String>() : pInputShapes;
			this.outputShapes = pOutputShapes == null ? new ArrayList<String>() : pOutputShapes;
			this.distinctInputs = pDistinctInputs;
			this.distinctOutputs = pDistinctOutputs;
			this.foldedInputs = pFoldedInputs;
			this.unlisted = pUnlisted;
			this.extractedInputs = pExtractedInputs;
			this.failed = pFailed;
			this.failure = pFailure;
		}

		/**
		 * The same measurement stripped of everything that describes how the VALUES look (their shapes, the case layout
		 * of their tokens, the token position read from each layout): what remains is counts. What the probe hands the
		 * session when the project's data policy forbids shapes for this column ({@code NONE}, or a sensitive column).
		 */
		public ProbeResult withoutValueShapes()
		{
			return new ProbeResult(this.filled, this.emptied, null, null, this.distinctInputs, this.distinctOutputs, this.foldedInputs, this.unlisted,
				this.extractedInputs, this.failed, this.failure, null, null, null, null, this.outputByInput);
		}

		/**
		 * Measures a probe from the sampled values and what the pipeline made of each: {@code pOutputs.get(i)} is the
		 * output of {@code pInputs.get(i)}, null or blank when emptied. Two inputs are RELATED when they hold the same
		 * letters and digits once case, accents, spacing, punctuation and character order are ignored ({@link
		 * StagingLlmPass#relatedKey}): {@code fr}/{@code FR}, {@code Hélène}/{@code HELENE}, {@code 2024-01-15}/{@code
		 * 15/01/2024}. {@link #foldedInputs} counts the distinct inputs landing on an output that at least {@link
		 * StagingLlmPass#FOLD_MIN_UNRELATED} unrelated inputs share - an input whose output is a part of it ({@link
		 * StagingLlmPass#isExtraction}) is neither counted nor makes a bucket shared.
		 */
		public static ProbeResult of(final List<String> pInputs, final List<String> pOutputs, final int pUnlisted, final List<String> pInputShapes,
			final List<String> pOutputShapes)
		{
			return ProbeResult.of(pInputs, pOutputs, null, pUnlisted, pInputShapes, pOutputShapes);
		}

		/**
		 * Same, with {@code pFailures.get(i)} the message of the exception the pipeline threw on {@code pInputs.get(i)}
		 * (null when it ran): such a value counts as emptied AND failed, so the refusal can say the pipeline is invalid
		 * rather than blame the value shapes (PersVol5: {@code regex(^\p{L}+)(0)} threw on every value and the model,
		 * told about shapes, retried the same syntax three times).
		 */
		public static ProbeResult of(final List<String> pInputs, final List<String> pOutputs, final List<String> pFailures, final int pUnlisted,
			final List<String> pInputShapes, final List<String> pOutputShapes)
		{
			int filled = 0;
			int emptied = 0;
			int failed = 0;
			String failure = null;
			final Set<String> distinctInputs = new LinkedHashSet<String>();
			final Set<String> extracted = new LinkedHashSet<String>();
			final Set<String> distinctOutputs = new LinkedHashSet<String>();
			final Map<String, String> outputByInput = new LinkedHashMap<String, String>();
			final Map<String, String> outputOfEveryInput = new LinkedHashMap<String, String>();
			final Map<String, Set<String>> relatedKeysByOutput = new LinkedHashMap<String, Set<String>>();
			for (int i = 0; i < pInputs.size(); i++)
			{
				final String input = pInputs.get(i);
				if (input == null || input.trim().isEmpty())
				{
					continue;
				}
				filled++;
				final String rawOutput = i < pOutputs.size() ? pOutputs.get(i) : null;
				outputOfEveryInput.put(input, rawOutput == null || rawOutput.trim().isEmpty() ? null : rawOutput.trim());
				final String error = pFailures != null && i < pFailures.size() ? pFailures.get(i) : null;
				if (error != null)
				{
					failed++;
					if (failure == null)
					{
						failure = error;
					}
				}
				final String output = i < pOutputs.size() ? pOutputs.get(i) : null;
				if (output == null || output.trim().isEmpty())
				{
					emptied++;
					continue;
				}
				distinctInputs.add(input);
				distinctOutputs.add(output);
				if (StagingLlmPass.isExtraction(input, output))
				{
					extracted.add(input);
					continue;
				}
				outputByInput.put(input, output);
				Set<String> keys = relatedKeysByOutput.get(output);
				if (keys == null)
				{
					keys = new LinkedHashSet<String>();
					relatedKeysByOutput.put(output, keys);
				}
				keys.add(StagingLlmPass.relatedKey(input));
			}
			int folded = 0;
			for (final Map.Entry<String, String> entry : outputByInput.entrySet())
			{
				if (relatedKeysByOutput.get(entry.getValue()).size() >= StagingLlmPass.FOLD_MIN_UNRELATED)
				{
					folded++;
				}
			}
			// PersVol7 (2026-09-28): where the capitals sit in the inputs, in the outputs, and which token position the
			// rule read from each input layout - what orderRefusal decides on
			final TokenCase inputCase = TokenCase.of(pInputs);
			final TokenCase outputCase = TokenCase.of(pOutputs);
			final Map<String, int[]> positionsByLayout = new LinkedHashMap<String, int[]>();
			for (int i = 0; i < pInputs.size(); i++)
			{
				final String input = pInputs.get(i);
				final String output = i < pOutputs.size() ? pOutputs.get(i) : null;
				final String layout = TokenCase.layoutOf(input);
				if (layout == null || output == null || output.trim().isEmpty())
				{
					continue;
				}
				int[] counts = positionsByLayout.get(layout);
				if (counts == null)
				{
					counts = new int[3]; // FIRST, LAST, other
					positionsByLayout.put(layout, counts);
				}
				final List<String> tokens = TokenCase.tokens(input);
				final String outputKey = TokenCase.foldedLetters(output.trim());
				if (outputKey.equals(TokenCase.foldedLetters(tokens.get(0))))
				{
					counts[0]++;
				}
				else if (outputKey.equals(TokenCase.foldedLetters(tokens.get(tokens.size() - 1))))
				{
					counts[1]++;
				}
				else
				{
					counts[2]++;
				}
			}
			final Map<String, String> positionByLayout = new LinkedHashMap<String, String>();
			final StringBuilder detail = new StringBuilder();
			for (final Map.Entry<String, int[]> entry : positionsByLayout.entrySet())
			{
				final int[] counts = entry.getValue();
				final int total = counts[0] + counts[1] + counts[2];
				if (inputCase.multiToken() == 0 || total * 100 < TokenCase.MIN_POPULATION_PERCENT * inputCase.multiToken())
				{
					continue; // not a population of the sample
				}
				final String position = counts[0] * 3 >= total * 2 ? "FIRST" : (counts[1] * 3 >= total * 2 ? "LAST" : "MIXED");
				positionByLayout.put(entry.getKey(), position);
				if (detail.length() > 0)
				{
					detail.append(", ");
				}
				detail.append(entry.getKey()).append(": ").append("MIXED".equals(position) ? "no single token position in " + total + " values"
					: position + " token in " + (position.equals("FIRST") ? counts[0] : counts[1]) + " of " + total);
			}
			return new ProbeResult(filled, emptied, pInputShapes, pOutputShapes, distinctInputs.size(), distinctOutputs.size(), folded, pUnlisted,
				extracted.size(), failed, failure, inputCase, outputCase, positionByLayout, detail.length() == 0 ? null : detail.toString(), outputOfEveryInput);
		}
	}

	/**
	 * Why a rule must not be recorded because it reads a token by its POSITION from a column whose values follow two
	 * case layouts, or null (PersVol7 volume test, 2026-09-28: {@code ECOM.CUSTOMER.FULL_NAME} held {@code Oliver Weber}
	 * in 70% of rows and {@code ROUSSEAU Stephane} - one token in capitals, first - in 30%; the model wrote one
	 * {@code regex} per attribute taking the first, then the last token of every value; the probe saw nothing emptied
	 * or folded, and the two attributes of 263 rows were crossed, 39 emptied, 312 true duplicates missed because
	 * their name keys never matched). Refused when: the sampled inputs follow {@link TokenCase#twoLayouts two layouts};
	 * every layout that is a population of the sample had its output read from the SAME token position
	 * ({@link ProbeResult#positionByLayout}, {@code FIRST} everywhere or {@code LAST} everywhere). A rule that reads a
	 * different position per layout, a rule whose output is not a token (a whole value, a concatenation), and a column
	 * of one layout all pass. The message names every number measured and the two regex forms the Connectors catalogue
	 * can express (Java lookaround and alternation inside ONE {@code regex(...)} step, whose {@code find()} keeps the
	 * text of the group; the catalogue has no conditional step), and the fallback: the column mapped whole onto one
	 * attribute. Domain-free: it says "the token in capitals", never what that token is.
	 */
	static String orderRefusal(final String pLandingColumn, final String pPipeline, final ProbeResult pResult)
	{
		if (pResult == null || pResult.inputTokenCase == null || !pResult.inputTokenCase.twoLayouts() || pResult.positionByLayout.size() < 2)
		{
			return null;
		}
		final Set<String> positions = new LinkedHashSet<String>(pResult.positionByLayout.values());
		if (positions.size() != 1 || positions.contains("MIXED"))
		{
			return null;
		}
		final String position = positions.iterator().next();
		final String outputCase = pResult.outputTokenCase == null ? "" : " Its output is " + pResult.outputTokenCase.renderBrief() + ".";
		return "tried on the real values of '" + pLandingColumn + "' (never shown to you): its multi-token values follow TWO case layouts - "
			+ pResult.inputTokenCase.render() + " - so they may be written in two orders, and pipeline '" + pPipeline + "' reads the " + position
			+ " token from every layout alike (" + pResult.positionDetail + ")." + outputCase + " Decide which attribute the token written in capitals "
			+ "belongs to, then give EACH attribute its OWN order-aware rule: the regex step runs a Java regex with find() and keeps the text of its group, so "
			+ "the condition goes inside the pattern with lookaround and alternation (the catalogue has no conditional step). Form A, the token in "
			+ "capitals when the value has one, else the last token: " + StagingLlmPass.REGEX_CAPITALS_ELSE_LAST + " ; form B, the token holding a "
			+ "lower-case letter after an optional leading token in capitals: " + StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS + " (\\p{L} for a letter, "
			+ "\\p{Lu} upper, \\p{Ll} lower; keep the apostrophe and hyphen in the classes). One attribute takes form A, the other form B - two "
			+ "attributes fed from one column must use two DIFFERENT rules, the same regex for both gives both the same token. If the split cannot be "
			+ "expressed, map the column WHOLE onto ONE attribute with no regex and say so in a note: the values are then compared whole.";
	}

	/** An order-aware extraction the Connectors catalogue can express: the all-upper-case token when the value has one, else the last token. Checked through the real jar in {@code StagingLlmPassTest}. */
	public static final String REGEX_CAPITALS_ELSE_LAST = "regex((?:^(?=[\\p{Lu}'-]+\\s)|(?<=\\s)(?=[\\p{L}'-]+$))([\\p{L}'-]+))";
	/** Its complement: the token holding a lower-case letter, after an optional leading all-upper-case token. */
	public static final String REGEX_LOWER_AFTER_CAPITALS = "regex(^(?:[\\p{Lu}'-]+\\s+)?([\\p{L}'-]*\\p{Ll}[\\p{L}'-]*))";

	/** Two rules on one column whose outputs are the same on this share of the filled sampled values (or more) feed the SAME part of it - see {@link #identicalRefusal}. */
	static final int IDENTICAL_OUTPUT_MIN_PERCENT = 80;

	/**
	 * {@code {identical, compared}}: among the filled sampled inputs both probes hold ({@link ProbeResult#outputByInput}),
	 * how many came out of the two rules as the same non-empty text, and how many were compared. Case and accents
	 * count: {@code Weber} and {@code WEBER} are two outputs (a rule may keep the token and change its form).
	 */
	static int[] identicalOutputs(final ProbeResult pResult, final ProbeResult pOther)
	{
		int identical = 0;
		int compared = 0;
		if (pResult == null || pOther == null)
		{
			return new int[] { 0, 0 };
		}
		for (final Map.Entry<String, String> entry : pResult.outputByInput.entrySet())
		{
			if (!pOther.outputByInput.containsKey(entry.getKey()))
			{
				continue;
			}
			compared++;
			final String output = entry.getValue();
			final String otherOutput = pOther.outputByInput.get(entry.getKey());
			if (output != null && output.equals(otherOutput))
			{
				identical++;
			}
		}
		return new int[] { identical, compared };
	}

	/**
	 * Why a rule must not be recorded because it gives ANOTHER attribute of the same feed the same output as a rule
	 * already proposed on the same landing column, or null (PersVol8 volume test, 2026-09-28, agent run 195:
	 * {@code ECOM.CUSTOMER.FULL_NAME} was proposed with ONE regex - the capitals-else-last form of sec 17 - for BOTH
	 * {@code firstName} and {@code lastName}, so both received the last name on every row; each rule was order-aware,
	 * so {@link #orderRefusal} never fired, and nothing compared two rules with each other. Same run, {@code
	 * CRM.CONTACT.MAILING_STREET}: {@code regex(^\d+,?\s*(.*)$)}, its comma unquoted, returned the house number, the
	 * same value as the {@code houseNumber} rule - now caught before probing by {@link #unquotedCommaRefusal}, and
	 * here as well should another form of it get through). Refused when the two probes share their filled inputs and
	 * at least {@link #IDENTICAL_OUTPUT_MIN_PERCENT} percent of them came out identical ({@link #identicalOutputs}).
	 * Domain-free: the message names the two attributes, the share, and the two regex forms of sec 17 labelled by
	 * what they extract; which part belongs to which attribute stays the model's call.
	 */
	static String identicalRefusal(final String pLandingColumn, final String pPipeline, final String pAttribute, final String pOtherAttribute,
		final String pOtherPipeline, final ProbeResult pResult, final ProbeResult pOtherResult)
	{
		final int[] counts = StagingLlmPass.identicalOutputs(pResult, pOtherResult);
		if (counts[1] == 0 || counts[0] * 100 < StagingLlmPass.IDENTICAL_OUTPUT_MIN_PERCENT * counts[1])
		{
			return null;
		}
		final int percent = counts[0] * 100 / counts[1];
		return "on the real values of '" + pLandingColumn + "' (never shown to you), pipeline '" + pPipeline + "' for attribute '" + pAttribute
			+ "' gives the same output as the rule already proposed for attribute '" + pOtherAttribute + "' ('" + pOtherPipeline + "') in " + counts[0] + " of "
			+ counts[1] + " values (" + percent + "%) - two attributes fed from one column must receive DIFFERENT parts of it, with two DIFFERENT rules. "
			+ "Give '" + pAttribute + "' a rule that extracts the other part (for a value written in two case layouts: the token in capitals, else the last "
			+ "token: " + StagingLlmPass.REGEX_CAPITALS_ELSE_LAST + " ; the token holding a lower-case letter after an optional leading token in capitals: "
			+ StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS + " - one form per attribute, never the same for both), or map the column whole onto ONE "
			+ "attribute and drop the rule of the other.";
	}

	/**
	 * Why a pipeline must not even be probed, or null: a {@code regex(...)} step whose pattern holds a comma outside
	 * quotes. Connectors' {@code Transformations.apply} (read 2026-09-28) cuts the pipeline into steps on {@code |}
	 * outside parentheses, takes the text between a step's first {@code (} and its LAST {@code )} as its arguments,
	 * and splits them on every comma outside single quotes (a {@code '} toggles the quoted state, nothing escapes
	 * it); {@code regex} then compiles the FIRST argument and reads the SECOND as the group number. So
	 * {@code regex(^\d+,?\s*(.*)$)} compiles {@code ^\d+} and the rule returns the house number - what the live run
	 * did on {@code CRM.CONTACT.MAILING_STREET} (PersVol8, 2026-09-28), accepted by the probe as an extraction. The
	 * split is mirrored here, not reimplemented: {@code regex(pattern)} and {@code regex(pattern,n)} with an integer
	 * {@code n} pass; any other comma - a {@code {1,3}} quantifier, a {@code [a,b]} class, an alternative written with
	 * a comma - is named by its 1-based position in the pipeline. A comma inside quotes never counts, so
	 * {@code regex('^\d{1,3},?\s*(.*)$')} and {@code regex('a,b',1)} pass.
	 */
	static String unquotedCommaRefusal(final String pPipeline)
	{
		if (pPipeline == null)
		{
			return null;
		}
		int depth = 0;
		int stepStart = 0;
		for (int i = 0; i <= pPipeline.length(); i++)
		{
			final char c = i < pPipeline.length() ? pPipeline.charAt(i) : '|';
			if (c == '(')
			{
				depth++;
			}
			else if (c == ')')
			{
				depth--;
			}
			if (c == '|' && depth == 0)
			{
				final String refusal = StagingLlmPass.unquotedCommaInStep(pPipeline, stepStart, i);
				if (refusal != null)
				{
					return refusal;
				}
				stepStart = i + 1;
			}
		}
		return null;
	}

	/** {@link #unquotedCommaRefusal} on the step {@code pPipeline[pFrom, pTo)}, Connectors' own argument split reproduced. */
	private static String unquotedCommaInStep(final String pPipeline, final int pFrom, final int pTo)
	{
		final String step = pPipeline.substring(pFrom, pTo);
		final int leading = step.length() - step.replaceAll("^\\s+", "").length();
		final String trimmed = step.trim();
		final int open = trimmed.indexOf('(');
		if (open < 0 || !"regex".equals(trimmed.substring(0, open).trim().toLowerCase(Locale.ROOT)))
		{
			return null;
		}
		final int close = trimmed.lastIndexOf(')');
		final int innerEnd = close < open ? trimmed.length() : close;
		final String inner = trimmed.substring(open + 1, innerEnd);
		final List<Integer> commas = new ArrayList<Integer>();
		boolean quoted = false;
		for (int i = 0; i < inner.length(); i++)
		{
			final char c = inner.charAt(i);
			if (c == '\'')
			{
				quoted = !quoted;
			}
			else if (c == ',' && !quoted)
			{
				commas.add(Integer.valueOf(i));
			}
		}
		if (commas.isEmpty())
		{
			return null;
		}
		final String afterFirst = inner.substring(commas.get(0).intValue() + 1).trim();
		if (commas.size() == 1 && afterFirst.matches("\\d+"))
		{
			return null; // regex(pattern,group)
		}
		// the first comma that is not followed by a lone group number is the one that cuts the pattern
		final int offending = commas.get(0).intValue();
		final int position = pFrom + leading + open + 1 + offending + 1;
		return "the comma at position " + position + " splits the arguments of regex(): Connectors reads '" + inner.substring(0, offending) + "' as the pattern "
			+ "and what follows the comma as the group number, so pipeline '" + pPipeline + "' does not mean what it says. Quote the whole pattern, "
			+ "regex('" + inner + "'), or write the alternative without a comma.";
	}

	/** Why a rule must not be recorded after its probe, or null: it turns at least half of the filled sampled values into nothing. */
	static String probeRefusal(final String pLandingColumn, final String pPipeline, final ProbeResult pResult)
	{
		if (pResult == null || pResult.filled == 0 || pResult.emptied * 2 < pResult.filled)
		{
			return null;
		}
		if (pResult.failed * 2 >= pResult.filled)
		{
			// PersVol5 (2026-09-26): regex(^\p{L}+)(0) threw on every value; the shapes hint below made the model retry the
			// same syntax until the circuit breaker stopped the run. Say what failed instead.
			return "tried on the real values of '" + pLandingColumn + "' (never shown to you), pipeline '" + pPipeline + "' is INVALID: it fails on "
				+ pResult.failed + " of " + pResult.filled + " filled values with: " + pResult.failure + ". Syntax: steps are separated by '|', "
				+ "a step is name(arg1,arg2) with nothing after its closing parenthesis; regex(pattern,group) takes the group number as its "
				+ "SECOND argument (regex(^(\\p{L}+)) or regex(^\\p{L}+,0)), and a pattern holding a comma must be quoted, "
				+ "regex('^\\d{1,3},?\\s*(.*)$'), since an unquoted comma ends the pattern.";
		}
		// live B2B run 2026-10-23: SAP's tax number holds a SIREN (digits only) or a VAT number (with letters) per row; a filter keeping one kind
		// empties the other by design and was refused, so the model left the column out and both identifiers were lost
		final int[] kinds = StagingLlmPass.identifierKinds(pResult);
		if (kinds != null)
		{
			final boolean keepsDigits = kinds[0] > 0;
			final boolean keepsLetters = kinds[1] > 0;
			if (keepsDigits != keepsLetters && (keepsDigits ? kinds[2] : kinds[3]) == 0)
			{
				return null; // a split by kind: every kept value is of one kind, every emptied value of the other
			}
			if (keepsDigits != keepsLetters)
			{
				final String kind = keepsDigits ? "digits-only" : "with letters";
				return "tried on the real values of '" + pLandingColumn + "' (never shown to you), pipeline '" + pPipeline + "' keeps " + (keepsDigits ? kinds[0] : kinds[1])
					+ " identifier(s) " + kind + " but EMPTIES " + (keepsDigits ? kinds[2] : kinds[3]) + " other value(s) " + kind + StagingLlmPass.inputShapesNote(pResult)
					+ ": a filter that splits a column by kind must keep EVERY value of its kind (every shape listed), e.g. "
					+ (keepsDigits ? "regex('^[0-9]+$')" : "regex('^\\p{L}{2}[\\p{L}0-9]+$')") + ". A shape is not a literal: '9' stands for ANY digit and 'A' for ANY letter.";
			}
		}
		return "tried on the real values of '" + pLandingColumn + "' (never shown to you), pipeline '" + pPipeline + "' leaves " + pResult.emptied + " of "
			+ pResult.filled + " filled values EMPTY" + StagingLlmPass.inputShapesNote(pResult) + ". A shape is not a literal: '9' "
			+ "stands for ANY digit and 'a'/'A' for ANY letter, so a regex must use \\d, \\p{L}, \\s..., never the shape itself.";
	}

	/**
	 * {@code {digits-only kept, with letters kept, digits-only emptied, with letters emptied}} over the sampled inputs of a probe ({@link RowFacts#identifierLike},
	 * {@link RowFacts#digitsOnly}); null when an input is not identifier-like or when a value failed (not a split by kind then). Never sent to the model.
	 */
	static int[] identifierKinds(final ProbeResult pResult)
	{
		if (pResult == null || pResult.failed > 0 || pResult.outputByInput.isEmpty())
		{
			return null;
		}
		final int[] kinds = new int[4];
		for (final Map.Entry<String, String> entry : pResult.outputByInput.entrySet())
		{
			final String input = entry.getKey().trim();
			if (!RowFacts.identifierLike(input))
			{
				return null;
			}
			final boolean kept = entry.getValue() != null && !entry.getValue().trim().isEmpty();
			kinds[(RowFacts.digitsOnly(input) ? 0 : 1) + (kept ? 0 : 2)]++;
		}
		return kinds;
	}

	/** {@code " (input shapes: ...)"}, or nothing when the probe carries no shape (the data policy withholds them for this column). */
	private static String inputShapesNote(final ProbeResult pResult)
	{
		return pResult.inputShapes.isEmpty() ? "" : " (input shapes: " + StagingLlmPass.join(pResult.inputShapes) + ")";
	}

	/**
	 * The confidence a rule flagged by the value check is capped at: one under the 60 % bar of "accept all" in the Mapping matrix and the
	 * wizard's bulk sweep, as {@code NormalisationSuggester.FLAG_CONFIDENCE_CAP} does for the links it flags after the model.
	 */
	static final int FLAG_CONFIDENCE_CAP = MatrixCompute.DEFAULT_BULK_THRESHOLD - 1;
	/** {@code AttributeMapping.rationale} is {@code maxLen=2000} in the XSD: a value-check note is cut to fit, never the model's own rationale. */
	static final int RATIONALE_MAX_LENGTH = 2000;

	/** An output shared by this many unrelated inputs (see {@link ProbeResult#of}) is a fold, not a normalisation. */
	static final int FOLD_MIN_UNRELATED = 3;
	/** Returned by the {@code *=} default {@link #withMapSentinel} adds: letters and digits only, so case steps after the map keep it recognisable. */
	public static final String MAP_SENTINEL = "zzunlisted7f3zz";

	/**
	 * Why a rule must not be recorded because it LOSES information, or null (PersVol4 volume test, 2026-09-26: the ERP
	 * country held 8 ISO codes and the model wrote {@code map(FR=France;DE=Germany;*=Other)}; nothing was emptied, so
	 * {@link #probeRefusal} let it through, and 750 of 2 217 addresses lost their country). Two cases:
	 * <ul>
	 * <li>a {@code map(...)} a sampled value is not listed in. Connectors' {@code map} (case-insensitive, trimmed key)
	 * NEVER keeps an unmatched value: it becomes the {@code *=} default, or null without one - so a partial map is lossy
	 * whatever its default.</li>
	 * <li>any other fold: at least 3 distinct inputs, at most half as many distinct outputs, and at least a third of the
	 * distinct inputs landing on an output that {@link #FOLD_MIN_UNRELATED} or more UNRELATED inputs share. Why this
	 * discriminator: the half ratio alone also fires on legitimate normalisers ({@code upper} merges {@code fr} and
	 * {@code FR}; a date format merges {@code 2024-01-15} and {@code 15/01/2024}; a harmonising map sends {@code FR} and
	 * {@code France} to one form). Those merge inputs that are RELATED (same letters and digits whatever case, accents,
	 * punctuation, order), or at most two unrelated forms of one value (code + label). A bucket that receives three or
	 * more unrelated values ({@code DE}, {@code IT}, {@code ES} -> {@code Other}; a first letter taken out of city names)
	 * has thrown distinct values away. An input whose output is a PART of it ({@link #isExtraction}: a first name out of
	 * a full name, a year out of a date) never counts (PersVol5, 2026-09-26: many full names share a first name, and the
	 * first-name extraction was refused until the ECOM source was left out of the staging).</li>
	 * </ul>
	 */
	static String lossyRefusal(final String pLandingColumn, final String pPipeline, final ProbeResult pResult)
	{
		if (pResult == null)
		{
			return null;
		}
		if (pResult.unlisted > 0)
		{
			return "tried on the real values of '" + pLandingColumn + "' (never shown to you), " + pResult.unlisted + " of " + pResult.filled
				+ " sampled distinct values are NOT listed in the map(...) of pipeline '" + pPipeline + "'. map never keeps an unlisted value: it "
				+ "becomes the '*=' default, or EMPTY without one, so those values would be LOST. Either list EVERY value the column holds"
				+ StagingLlmPass.inputShapesNote(pResult) + ", or drop map(...) and keep the source value as it is.";
		}
		if (pResult.distinctInputs >= 3 && pResult.distinctOutputs * 2 <= pResult.distinctInputs && pResult.foldedInputs > 0
			&& pResult.foldedInputs * 3 >= pResult.distinctInputs)
		{
			return "tried on the real values of '" + pLandingColumn + "' (never shown to you), pipeline '" + pPipeline + "' folds "
				+ pResult.distinctInputs + " distinct values into " + pResult.distinctOutputs + " distinct outputs: " + pResult.foldedInputs
				+ " of them land on an output shared by " + StagingLlmPass.FOLD_MIN_UNRELATED + " or more unrelated values that it is not a part "
				+ "of (" + pResult.extractedInputs + " whose output is a word or number of the value itself were not counted), so they can no "
				+ "longer be told apart. A rule may change the FORM of a value (case, spacing, format, a code for its label) or keep a PART of "
				+ "it (a word, a year), never replace different values by one they do not contain: map each value to its own output, or "
				+ "keep the source value.";
		}
		return null;
	}

	/**
	 * Whether {@code pOutput} is a PART of {@code pInput}: its words (runs of letters or digits, case and accents ignored)
	 * appear, in order, among the input's words. {@code Oliver} out of {@code Oliver Weber}, {@code 2024} out of {@code
	 * 15/01/2024}, {@code FR} out of {@code fr} are; {@code Other} out of {@code IT}, or the first letter {@code P} out
	 * of {@code Paris}, are not (a letter is not a word of the value). An extraction keeps what it takes as it was: it
	 * never merges distinct values into one they do not contain, so {@link ProbeResult#of} does not count it as a fold.
	 */
	static boolean isExtraction(final String pInput, final String pOutput)
	{
		final List<String> in = StagingLlmPass.words(pInput);
		final List<String> out = StagingLlmPass.words(pOutput);
		if (out.isEmpty() || out.size() > in.size())
		{
			return false;
		}
		int next = 0;
		for (final String word : out)
		{
			while (next < in.size() && !in.get(next).equals(word))
			{
				next++;
			}
			if (next == in.size())
			{
				return false;
			}
			next++;
		}
		return true;
	}

	/** The runs of letters or digits of a value, lower-cased, accents removed. */
	private static List<String> words(final String pValue)
	{
		final List<String> out = new ArrayList<String>();
		if (pValue == null)
		{
			return out;
		}
		final String folded = Normalizer.normalize(pValue, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
		for (final String word : folded.split("[^\\p{L}\\p{N}]+"))
		{
			if (!word.isEmpty())
			{
				out.add(word);
			}
		}
		return out;
	}

	/**
	 * The letters and digits of a value, case, accents and order ignored: two values with the same key are variants of
	 * one another, not different values (see {@link ProbeResult#of}). A value with no letter or digit keys on itself.
	 */
	static String relatedKey(final String pValue)
	{
		final String folded = Normalizer.normalize(pValue.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
		final String alnum = folded.replaceAll("[^\\p{L}\\p{N}]", "");
		if (alnum.isEmpty())
		{
			return folded;
		}
		final char[] chars = alnum.toCharArray();
		java.util.Arrays.sort(chars);
		return new String(chars);
	}

	/**
	 * {@code pPipeline} with a last {@code '*=}{@link #MAP_SENTINEL}{@code '} argument added to every {@code map(...)}
	 * step, or null when it has none. Connectors' {@code map} keeps the LAST {@code *=} it reads and only uses it when no
	 * key matches, so running this variant tells, value by value, which sampled inputs are not listed (their output
	 * holds the sentinel), whatever default the model wrote. Steps are split on {@code |} outside parentheses and
	 * quotes, as Connectors does.
	 */
	public static String withMapSentinel(final String pPipeline)
	{
		if (pPipeline == null)
		{
			return null;
		}
		final List<String> steps = new ArrayList<String>();
		int depth = 0;
		boolean quoted = false;
		int start = 0;
		for (int i = 0; i < pPipeline.length(); i++)
		{
			final char c = pPipeline.charAt(i);
			if (c == '\'')
			{
				quoted = !quoted;
			}
			else if (!quoted && c == '(')
			{
				depth++;
			}
			else if (!quoted && c == ')')
			{
				depth--;
			}
			else if (!quoted && depth == 0 && c == '|')
			{
				steps.add(pPipeline.substring(start, i));
				start = i + 1;
			}
		}
		steps.add(pPipeline.substring(start));
		boolean found = false;
		final StringBuilder out = new StringBuilder();
		for (final String step : steps)
		{
			String rewritten = step;
			final String trimmed = step.trim();
			final int open = trimmed.indexOf('(');
			final int close = trimmed.lastIndexOf(')');
			if (open > 0 && close > open && "map".equals(trimmed.substring(0, open).trim().toLowerCase(Locale.ROOT)))
			{
				final String inner = trimmed.substring(open + 1, close);
				rewritten = trimmed.substring(0, close) + (inner.trim().isEmpty() ? "" : ",") + "'*=" + StagingLlmPass.MAP_SENTINEL + "'"
					+ trimmed.substring(close);
				found = true;
			}
			if (out.length() > 0)
			{
				out.append('|');
			}
			out.append(rewritten);
		}
		return found ? out.toString() : null;
	}

	/** A value shape that reads as a short CODE (at most three marks once run lengths are dropped, no lower-case letter): {@code AA}, {@code 999}, {@code A9}. */
	static boolean codeLike(final String pShape)
	{
		final String shape = StagingLlmPass.dominant(pShape).replaceAll("\\(\\d+\\)", "");
		return !shape.isEmpty() && shape.length() <= 3 && shape.indexOf('a') < 0;
	}

	/** A value shape that reads as WORDS: it holds a lower-case letter. */
	static boolean wordLike(final String pShape)
	{
		return StagingLlmPass.dominant(pShape).indexOf('a') >= 0;
	}

	/** {@code "AA (100%)"} -> {@code "AA"}. */
	private static String dominant(final String pShapeWithShare)
	{
		if (pShapeWithShare == null)
		{
			return "";
		}
		final int share = pShapeWithShare.lastIndexOf(" (");
		return share < 0 ? pShapeWithShare : pShapeWithShare.substring(0, share);
	}

	/** A feed's address in a run: {@code "<entity>|<sourceSystem>.<table>"}, plus {@code "~<variant>"} for one of several children of one row. */
	public static String feedKey(final String pEntityName, final String pLandingTableKey, final String pVariant)
	{
		return pEntityName + "|" + pLandingTableKey + (pVariant == null ? "" : "~" + pVariant);
	}

	/** Suffixes that turn a parent's name (or the link attribute's) into "the parent's key" - see {@link #duplicatesParentLink}. */
	private static final List<String> PARENT_KEY_SUFFIXES = java.util.Arrays.asList("", "id", "ref", "key", "code", "fk", "no", "number");

	/**
	 * 2026-09-26 (docs/TARGET-MAPPING.md sec 14): true when {@code pAttributeName}, an attribute of a child entity
	 * OTHER than its {@code pParentLinkAttribute}, is a second reference to the parent - named after the parent
	 * entity or after the link attribute, optionally followed by Id/Ref/Key/Code/Fk/No/Number (case, underscores
	 * and accents ignored): {@code personneId}, {@code personne_ref}, {@code personneRef} (the very name of the link
	 * field the engine generates). Pure, see {@code StagingLlmPassTest}.
	 */
	static boolean duplicatesParentLink(final String pAttributeName, final String pParentEntity, final String pParentLinkAttribute)
	{
		if (pAttributeName == null || pParentEntity == null || pAttributeName.equals(pParentLinkAttribute))
		{
			return false;
		}
		final String candidate = StagingLlmPass.compact(pAttributeName);
		final List<String> stems = new ArrayList<String>();
		stems.add(StagingLlmPass.compact(pParentEntity));
		if (pParentLinkAttribute != null)
		{
			stems.add(StagingLlmPass.compact(pParentLinkAttribute));
		}
		for (final String stem : stems)
		{
			for (final String suffix : StagingLlmPass.PARENT_KEY_SUFFIXES)
			{
				if (!stem.isEmpty() && candidate.equals(stem + suffix))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Lower case, accents and every non-alphanumeric character removed: {@code "Personne_Ref"} -> {@code "personneref"}. */
	private static String compact(final String pName)
	{
		return Normalizer.normalize(pName, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}
	private static final List<String> DATA_TYPES = java.util.Arrays.asList("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP");
	private static final int DEFAULT_STRING_LENGTH = 255;

	/**
	 * The Connectors mapping engine's own registered step names ({@code core.Transformations}, README of
	 * {@code ~/Dev/wt/ebx-connectors-mapping-jar/ebx-connectors}, "Mapping engine jar" table, read 2026-09-23),
	 * minus the ones that make no sense from this pass (task brief): {@code fk}/{@code lookup}/{@code golden}/
	 * {@code mdmstate} all resolve against a live EBX table at RUN time, which nothing about a PROPOSAL should
	 * ever depend on; {@code runid}/{@code now}/{@code hash} produce a value with no relation at all to the
	 * source column they would be "mapping" - none of the seven belong in a pipeline this pass proposes.
	 */
	private static final Set<String> EXCLUDED_TRANSFORMATIONS = new LinkedHashSet<String>(
		java.util.Arrays.asList("fk", "lookup", "golden", "mdmstate", "runid", "now", "hash"));
	/** Every {@code core.Transformations} name, in the README's own order - {@link #ALLOWED_TRANSFORMATIONS} is this list minus {@link #EXCLUDED_TRANSFORMATIONS}. */
	private static final List<String> ALL_TRANSFORMATIONS = java.util.Arrays.asList("trim", "upper", "lower", "capitalize", "left", "right", "substring",
		"replace", "regex", "vatfr", "digits", "prefix", "suffix", "default", "constant", "nullifempty", "nullif", "isodate", "isodatetime", "date", "map",
		"join", "first", "count", "boolean", "number", "round", "fk", "lookup", "golden", "mdmstate", "hash", "now", "runid", "concat",
		"epochdate", "joinwidth");
	private static final List<String> ALLOWED_TRANSFORMATIONS = StagingLlmPass.allowedTransformations();
	/** One line per allowed transformation, for {@link #systemPrompt()} - name plus its arguments and a short behaviour summary (same README table). */
	private static final String TRANSFORMATION_CATALOGUE = "- trim: removes leading/trailing whitespace.\n" + "- upper: uppercases (Locale.ROOT).\n"
		+ "- lower: lowercases (Locale.ROOT).\n" + "- capitalize: capitalizes the first letter of each word (space/-/').\n"
		+ "- left(n): first n characters.\n" + "- right(n): last n characters.\n" + "- substring(start[,end]): 0-based, end exclusive.\n"
		+ "- replace(old,new): literal (non-regex) replace.\n" + "- regex(pattern[,group]): first regex match, group defaults to 1 (or 0, whole match); quote a pattern holding a comma: regex('^\\d{1,3},(.*)').\n"
		+ "- vatfr: French SIREN/SIRET number to its VAT number.\n" + "- digits: strips everything but 0-9.\n" + "- prefix(text): prepends text.\n"
		+ "- suffix(text): appends text.\n" + "- default(value): value when the input is null/blank/empty.\n" + "- constant(value): always value, input ignored.\n"
		+ "- nullifempty: null when blank/empty.\n" + "- nullif(value): null when the input equals value.\n"
		+ "- isodate: first 10 characters (a substring, not a real parse).\n"
		+ "- isodatetime: first 19 characters, space at index 10 turned into T (a substring, not a real parse).\n"
		+ "- date(inPattern[,outPattern]): strict date parse and reformat.\n"
		+ "- epochdate([outPattern]): an OData /Date(ms)/ value (or bare epoch milliseconds) to yyyy-MM-dd (or outPattern), in UTC.\n"
		+ "- map(K1=V1;K2=V2;...[;*=fallback]): case-insensitive lookup, null on no match with no fallback.\n"
		+ "- join([separator]): joins a list, skipping null/empty elements.\n" + "- first: first element of a list.\n" + "- count: size of a list.\n"
		+ "- boolean: true/false from common words (true/1/yes/oui/y/o/active are true).\n" + "- number: parses a decimal, tolerant of thousand separators.\n"
		+ "- round([scale]): rounds a decimal to scale (half up).\n"
		+ "- concat(path1,path2,...): joins the value at each dotted path of the row document with '|'.\n"
		+ "- joinwidth(width,path1,path2,...): rebuilds ONE value its source cut into fixed-width pieces (columns of the row document): a piece of the full width is "
		+ "followed directly by the next, a shorter one by a space.\n";

	/** Roughly the token budget past which {@link #userPrompt} switches to the compact catalogue - see the class Javadoc. */
	static final int INVENTORY_TOKEN_BUDGET = 20000;
	private static final int CHARS_PER_TOKEN_ESTIMATE = 4;
	static final int INVENTORY_CHAR_BUDGET = StagingLlmPass.INVENTORY_TOKEN_BUDGET * StagingLlmPass.CHARS_PER_TOKEN_ESTIMATE;

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final JsonNode PROPOSE_TARGET_ENTITY_SCHEMA = StagingLlmPass.schema("{"
		+ "\"type\":\"object\","
		+ "\"properties\":{"
		+ "\"name\":{\"type\":\"string\",\"description\":\"PascalCase target entity name, e.g. Personne. Must not already be the name of a "
		+ "rule-proposed entity, nor of an earlier target entity in this run.\"},"
		+ "\"label\":{\"type\":\"string\"},"
		+ "\"attributes\":{\"type\":\"array\",\"description\":\"At least one attribute of this target entity.\",\"items\":{\"type\":\"object\","
		+ "\"properties\":{"
		+ "\"name\":{\"type\":\"string\",\"description\":\"camelCase attribute name, e.g. firstName.\"},"
		+ "\"label\":{\"type\":\"string\"},"
		+ "\"dataType\":{\"type\":\"string\",\"enum\":[\"STRING\",\"INTEGER\",\"DECIMAL\",\"BOOLEAN\",\"DATE\",\"TIMESTAMP\"]},"
		+ "\"maxLength\":{\"type\":\"integer\",\"description\":\"STRING only; 255 when omitted.\"},"
		+ "\"mandatory\":{\"type\":\"boolean\"},"
		+ "\"businessKey\":{\"type\":\"boolean\",\"description\":\"Part of this entity's own business key in its source. Never true when parentEntity "
		+ "is given: a child entity's identity is its parent's key plus its own role, never a business key of its own.\"},"
		+ "\"rationale\":{\"type\":\"string\",\"description\":\"Why THIS field belongs here and which source column(s) it comes from - one "
		+ "sentence, specific to this attribute. Never repeat the entity's own rationale verbatim.\"},"
		+ "\"confidence\":{\"type\":\"integer\",\"description\":\"0 (guess) to 100 (certain) for THIS attribute on its own - independent of the "
		+ "entity's overall confidence.\"}"
		+ "},\"required\":[\"name\",\"label\",\"dataType\",\"rationale\",\"confidence\"]}},"
		+ "\"parentEntity\":{\"type\":\"string\",\"description\":\"Name of an earlier target entity (this run, or an existing one still pending review) "
		+ "that this entity is a child of - e.g. Adresse is a child of Personne. Omit for a top-level entity.\"},"
		+ "\"parentLinkAttribute\":{\"type\":\"string\",\"description\":\"Required when parentEntity is given: the name of ONE of this entity's OWN "
		+ "attributes (declared above, in this same call) that plays the role of the reference back to its parent - e.g. personne. It becomes a reference "
		+ "to the parent entity; never a business key.\"},"
		+ "\"confidence\":{\"type\":\"integer\",\"description\":\"0 (guess) to 100 (certain).\"},"
		+ "\"rationale\":{\"type\":\"string\"}"
		+ "},\"required\":[\"name\",\"label\",\"attributes\",\"confidence\",\"rationale\"]}");
	/** The JSON schema of {@code propose_feed}, as text (for the tests). */
	static String toolSchemaText()
	{
		return StagingLlmPass.PROPOSE_FEED_SCHEMA.toString();
	}

	private static final JsonNode PROPOSE_FEED_SCHEMA = StagingLlmPass.schema("{"
		+ "\"type\":\"object\","
		+ "\"properties\":{"
		+ "\"entity\":{\"type\":\"string\",\"description\":\"Name of a target entity proposed with propose_target_entity in this run, or an existing one "
		+ "still pending review.\"},"
		+ "\"landingTable\":{\"type\":\"string\",\"description\":\"A landing table from the inventory, exactly as given there ('<sourceSystem>.<table>').\"},"
		+ "\"parentFeed\":{\"type\":\"string\",\"description\":\"Only for a CHILD feed reading the SAME flat landing row as an earlier feed of THIS run: "
		+ "that earlier feed's own entity and landing table, exactly as '<entity>|<sourceSystem>.<table>'. Omit for a root feed - the ordinary case, "
		+ "including a feed of a child entity that has its own separate landing table (map its own reference attribute like any other column instead).\"},"
		+ "\"rules\":{\"type\":\"array\",\"description\":\"How this feed's columns map onto the entity's attributes.\",\"items\":{\"type\":\"object\","
		+ "\"properties\":{"
		+ "\"attribute\":{\"type\":\"string\",\"description\":\"Name of one of the entity's own attributes.\"},"
		+ "\"landingColumn\":{\"type\":\"string\",\"description\":\"A column of landingTable, exactly as given in the inventory.\"},"
		+ "\"pipeline\":{\"type\":\"string\",\"description\":\"Optional Connectors pipeline (e.g. trim|upper, constant(MAIN)); omit for the type-driven "
		+ "default. Steps run left to right; a case change (upper, lower) goes AFTER a regex that reads letter case (\\\\p{Lu}, \\\\p{Ll}), never before it. Cleaning "
		+ "steps attached to the mapping later run on the raw text BEFORE this pipeline.\"},"
		+ "\"rationale\":{\"type\":\"string\",\"description\":\"Why THIS column maps to THIS attribute - one sentence, specific to this rule. "
		+ "Never repeat the feed's own rationale verbatim.\"},"
		+ "\"confidence\":{\"type\":\"integer\",\"description\":\"0 (guess) to 100 (certain) for THIS rule on its own - independent of the feed's "
		+ "overall confidence.\"},"
		+ "\"remove\":{\"type\":\"boolean\",\"description\":\"Only when you call propose_feed AGAIN on a feed you already proposed in this run: true "
		+ "drops this attribute's existing rule from that feed (give the same landingColumn). A rule without it that names an attribute the feed "
		+ "already maps REPLACES that rule.\"}"
		+ "},\"required\":[\"attribute\",\"landingColumn\",\"rationale\",\"confidence\"]}},"
		+ "\"variant\":{\"type\":\"string\",\"description\":\"CHILD feed only, required when the same landing row feeds SEVERAL children of this "
		+ "entity: the role of this one, upper case (MAILING, OTHER, BILLING). Each variant also needs a rule mapping the discriminating attribute "
		+ "with constant(<role>). Name the feed later as parentFeed with '<entity>|<sourceSystem>.<table>~<variant>'.\"},"
		+ "\"sourceIdPipeline\":{\"type\":\"string\",\"description\":\"Optional Connectors pipeline computing this feed's own sourceId, e.g. "
		+ "concat(ID)|suffix(#MAIN). Usually omitted: a CHILD feed without one gets '<parent sourceId>#<n>'. When given it must read a column of the "
		+ "row - never a constant. Never set it on a root feed: a root feed's sourceId is always the landing row's own key.\"},"
		+ "\"confidence\":{\"type\":\"integer\"},"
		+ "\"rationale\":{\"type\":\"string\"}"
		+ "},\"required\":[\"entity\",\"landingTable\",\"rules\",\"confidence\",\"rationale\"]}");
	private static final JsonNode PROPOSE_CHILD_KEYS_SCHEMA = StagingLlmPass.schema("{"
		+ "\"type\":\"object\","
		+ "\"properties\":{"
		+ "\"entity\":{\"type\":\"string\",\"description\":\"The PARENT entity that will carry the key attribute: a target entity proposed in this run, or an "
		+ "existing one of the project.\"},"
		+ "\"name\":{\"type\":\"string\",\"description\":\"camelCase name of the new attribute on the parent, e.g. addressKeys. Not already an attribute of "
		+ "the parent.\"},"
		+ "\"label\":{\"type\":\"string\"},"
		+ "\"childEntity\":{\"type\":\"string\",\"description\":\"A CHILD entity of 'entity' (proposed with parentEntity = 'entity'): the one whose records make "
		+ "the keys.\"},"
		+ "\"fields\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"Attributes of the child entity that together make ONE key: the ones that "
		+ "identify the child record, most selective first. Never its link to the parent, never a technical identity attribute.\"},"
		+ "\"confidence\":{\"type\":\"integer\",\"description\":\"0 (guess) to 100 (certain).\"},"
		+ "\"rationale\":{\"type\":\"string\",\"description\":\"One or two sentences: which child records identify the parent here and why these fields "
		+ "identify one of them.\"}"
		+ "},\"required\":[\"entity\",\"name\",\"childEntity\",\"fields\",\"confidence\",\"rationale\"]}");
	private static final JsonNode DESCRIBE_LANDING_TABLE_SCHEMA = StagingLlmPass.schema(
		"{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\",\"description\":\"A landing table, exactly as named in the catalogue "
			+ "('<sourceSystem>.<table>').\"}},\"required\":[\"table\"]}");
	private static final JsonNode NOTE_SCHEMA = StagingLlmPass
		.schema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}");

	private StagingLlmPass()
	{
	}

	private static List<String> allowedTransformations()
	{
		final List<String> out = new ArrayList<String>();
		for (final String name : StagingLlmPass.ALL_TRANSFORMATIONS)
		{
			if (!StagingLlmPass.EXCLUDED_TRANSFORMATIONS.contains(name))
			{
				out.add(name);
			}
		}
		return out;
	}

	private static JsonNode schema(final String pJson)
	{
		try
		{
			return StagingLlmPass.MAPPER.readTree(pJson);
		}
		catch (final IOException ex)
		{
			// the four schemas above are fixed constants of this class: a parse failure here is a bug in this file, not a runtime condition
			throw new IllegalStateException("invalid built-in tool schema: " + ex.getMessage(), ex);
		}
	}

	// ------------------------------------------------------------------------------------------------------ prompt

	/**
	 * Fixed: no project data is ever interpolated here (that all goes into {@link #userPrompt}), including the
	 * transformation catalogue itself - it never changes across projects, so it is rendered once, here, rather
	 * than repeated in every {@link #userPrompt}. Two illustrative examples are spelled out, deliberately from
	 * two different business domains (task brief, "never one") - persons/addresses and products/variants -
	 * neither ever a real project's data, both fixed text reviewed once.
	 */
	public static String systemPrompt()
	{
		return "You are the semantic staging-design pass of the ON EBX MDM Factory. A deterministic rule pass already proposed one staging entity per "
			+ "landing table, with rule-proposed attributes and their mappings ('what a rule pass would do', listed below). Your task is different: design "
			+ "the TARGET model directly - the staging shape every source, however differently structured, must land in - then a tree of feeds that reads "
			+ "the landing tables into it. Propose ONE target entity per real-world business concept, at the FINEST grain any source forces: a source with "
			+ "a separate child table (an address, a variant, a line item) needs a separate target entity for it, even when another source embeds the same "
			+ "information as a flat row.\n\n"
			+ "You see metadata only: table and column names, source systems, data types, key flags, catalog tags (applied, or suggested by the catalogue from the values it sampled or from the column name and still pending a steward's review), fill rates, relations between "
			+ "landing tables and, for text columns, the most frequent value SHAPES (every letter written a or A, every digit 9, punctuation and "
			+ "spaces kept, a run of more than 4 identical marks written with its length: '9(8)' is an 8-digit value such as a yyyyMMdd date, "
			+ "'Aaaa Aa(6)' two capitalised words). A ', measured: ...' note beside a column says how its dates and numbers are written, as a format and a "
			+ "share of the rows ('date written dd/MM/yyyy (100%)'), never a value - it is given even for a sensitive column whose shapes are withheld, "
			+ "so use it to convert such a column. You never see a real "
			+ "value or any other data content - only counts: a table's row count and key, and, for a column the catalog tagged as an identity "
			+ "(e-mail, phone, name, official identifier), how many distinct values it holds and on how many rows a value repeats. A text column the catalogue gave no tag, whose sampled values "
			+ "are mostly WRITTEN like an e-mail, a phone number or two capitalised words (measurement thresholds: " + IdentityShapes.thresholdsText() + " of the values read), carries the same counts "
			+ "under 'identity-like by shape' with the share measured: that is a measure of how the values look, not a tag, and not a statement of what the column is - weigh it with the column "
			+ "name and the other columns. On a large landing table, fill rates and distinct-value counts are measured on a "
			+ "bounded sample of its rows, not necessarily the whole table - treat them as informative, not exact.\n\n"
			+ "Answer ONLY by calling the tools below - never by writing a proposal as free text. For every tool call, give a rationale of one or two "
			+ "sentences and a confidence from 0 (guess) to 100 (certain).\n\n"
			+ "Tools:\n"
			+ "- propose_target_entity: propose one entity of the target model, with its attributes. Give parentEntity/parentLinkAttribute to declare it "
			+ "as a child of an entity you already proposed (or one still pending review) - e.g. Adresse is a child of Personne.\n"
			+ "- propose_feed: propose one feed reading a landing table into a target entity's attributes, using a Connectors-style rule pipeline per "
			+ "column. A target entity fed by SEVERAL sources just needs one feed per source, each mapping that source's own columns into the SAME "
			+ "attribute names - this is how two applications describing the same business object end up unified, without any separate 'align these two' "
			+ "step.\n"
			+ "- propose_child_keys: on a PARENT entity, add a technical matching key built from the records of one of its child entities - one opaque token "
			+ "per child record, so that the matching can bring two parent records together because they share a child record (the same address, say) with "
			+ "an exact comparison on the parent table itself, without searching the child table for every record. Propose it when a child entity carries "
			+ "identifying data of its parent (the addresses, phones or identifiers of a person; the sites of an organisation). Name the child entity - already "
			+ "proposed with this entity as its parentEntity - and the child's attributes that make ONE key: the ones that identify the child record, most "
			+ "selective first (a postal code, a house number and a street, not a country every record shares, not a free comment); never its link to the "
			+ "parent. It is a technical matching key: never an identifier of the parent, never a business attribute, nothing is mapped into it and nobody "
			+ "edits it - the load fills it from the child records. Measured on a test of one million source rows (persons and their addresses) it raised the "
			+ "recall of the matching from 90.8 % to 99.15 % at a precision of 99.84 %. Call it once per parent and child pair at most, after the child entity "
			+ "and its feeds are proposed; leave it out when the child records identify nothing of the parent.\n"
			+ "- describe_landing_table: read-only, the exact columns (name, type, key flag, tags, fill rate, value shapes) of one landing table. Call it before mapping "
			+ "a table whenever the catalogue below only lists table names and column counts (a large project) - never guess a column name.\n"
			+ "- note: record something you deliberately chose not to cover, and why (a landing table you leave unmapped on purpose, a source you could "
			+ "not fit into the model).\n\n"
			+ "If the user message below lists entities under 'TARGET ENTITIES ALREADY PROPOSED IN AN EARLIER RUN', this project was already worked "
			+ "on: reuse those exact names for the same real-world concept - never propose a synonym or a translation of one of them (an entity "
			+ "refused for having the same attributes, or the same name once lowercased, as an existing one must be dropped, not renamed and "
			+ "retried) - and add only what is genuinely missing: a feed on a landing table not yet listed there, or a brand new entity for a "
			+ "concept none of them covers. Never repropose a feed already listed there under an entity. If they already cover every landing "
			+ "table, propose nothing at all and end with a note saying so.\n\n"
			+ "Two structural rules: (1) identifiers of different systems are never the same attribute - the staging keeps each source's own key through "
			+ "sourceSystem + sourceId, never as a shared attribute; a target entity's attributes hold BUSINESS data, not row identifiers. (2) a CHILD feed "
			+ "(parentFeed given) can only read the SAME landing table as its parent, one child row per parent row. Whenever a table feeds a parent "
			+ "entity, every child entity read from that same table MUST be a CHILD feed of that parent feed - never a root feed - and the parent's feeds "
			+ "are proposed first. Leave sourceIdPipeline out on a child feed: the engine gives each child '<parent sourceId>#<n>', unique and stable. "
			+ "If you do give one, it must read a column (concat(ID)|suffix(#MAIN)) - a constant would give every child the same sourceId. A "
			+ "child-type entity that has its OWN separate landing table (a genuine two-table source) is instead fed by its own ROOT feed (omit "
			+ "parentFeed) with its reference attribute mapped from the column holding the parent's key. Do NOT map the link-to-parent attribute "
			+ "(parentLinkAttribute) in a CHILD feed's own rules - the engine sets it from the parent row automatically; a rule on it there is "
			+ "dropped, not refused, but it is still wasted effort. The link-to-parent attribute is a dedicated attribute named after the parent "
			+ "(personne, produit), never a business field such as a street number. It is the child's ONLY reference to its parent: never declare a "
			+ "second attribute for the parent's key (personneId, personneRef) - a root feed of a separate table maps that key onto the link "
			+ "attribute itself.\n"
			+ "(3) A flat row can hold SEVERAL children of the same entity (a mailing address AND another address): propose one CHILD feed per role, "
			+ "each with its own variant (MAILING, OTHER...) and a discriminating attribute of the child entity (e.g. addressType) mapped with "
			+ "constant(MAILING), constant(OTHER)... A source whose child table has a type column (ADDRESS_TYPE) maps that column into the SAME "
			+ "discriminating attribute. (4) Every source of a target entity must be fed into it: a table left out needs a note saying why. A value "
			+ "stored differently must be converted by the rule, never left as is: a text column shaped '9(8)' feeding a DATE attribute needs "
			+ "date(yyyyMMdd); a full name in one column feeds first and last name through regex(...) when its casing tells the parts apart (look at its shapes: 'Aaaa Aaaa' and "
			+ "'AAAA Aaaa' are different orders); an address line '99, aaaa Aaaa' feeds houseNumber with regex(^(\\d+)) and the street with "
			+ "regex('^\\d+,?\\s*(.*)$') (quoted: an unquoted comma ends the pattern). When one source splits a value (house number apart from the street), the target entity keeps it split "
			+ "and every other source is split the same way. (5) In a regex, a letter is \\p{L} (an uppercase one \\p{Lu}), never [A-Za-z]: "
			+ "names carry accents, apostrophes, hyphens and spaces (Hélène, O'Brien, Van den Berg). When a column's shapes or its 'token case' figures show two layouts (a "
			+ "full name as 'Aaaa Aaaa' AND as 'AAAA Aaaa' - surname in capitals first: 'first token all upper case in 30%...'), the rule must "
			+ "read the token by its CASE, not its position - one regex per attribute with lookaround/alternation inside the pattern: "
			+ "the token in capitals, else the last token: " + StagingLlmPass.REGEX_CAPITALS_ELSE_LAST + " ; the token holding a lower-case letter "
			+ "after an optional leading token in capitals: " + StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS + " - one form for one attribute, the other "
			+ "form for the other - or the column must stay unsplit (mapped whole onto one attribute) for that source with a note; a position-only "
			+ "split of such a column is refused. Two attributes fed from one column must use two DIFFERENT rules extracting different parts: the "
			+ "same pipeline for both gives both the same value and is refused; every rule is tried on the real values. When a column's description says \"ORDER UNKNOWN\" (multi-token values whose casing does not tell the parts apart: the same column may hold them first part first in some rows and last part first in others, and nothing in the values says which), do NOT split it with a regex that reads a part by its position or its case at an ordinary confidence: EITHER map the whole value onto ONE full-value attribute of the target entity (propose that attribute if the entity lacks it) with no regex and leave the split to a later step, comparing the values with an order-independent key, OR split with the assumption stated in the rationale and a LOW confidence (at most 40), since a person will review it. (6) Harmonise codes across sources: when one source writes a country, a gender or a status as a "
			+ "code and another as a label ('FR' against 'France'), map every source to ONE form with map(...), the same form for all - but only with a COMPLETE map listing every value: map never keeps "
			+ "an unlisted value (it becomes the '*=' default, or empty), so a partial map or a catch-all such as '*=Other' loses data and is "
			+ "refused. If you cannot list every value, keep the source values unchanged. (7) Order of the steps: cleaning steps that a later pass may attach to a "
			+ "mapping (trim, case, accents...) run on the raw text BEFORE the mapping's own pipeline, and a pipeline runs left to right. So when a regex reads "
			+ "letter case (\\p{Lu}, \\p{Ll}), the case change goes at the END of the pipeline - regex(...)|upper, never upper|regex(...) - and a "
			+ "pipeline that changes case in front of such a regex is refused.\n"
			+ "(8) A fact is carried ONCE. When a separate entity carries the facts of a source (a transaction with its date, its place and its amount; a "
			+ "line item; an address with its street, city and postal code), the parent entity does NOT get the same fields from the same columns: a "
			+ "parent holds what describes ITSELF. When an address (or any other detail) entity exists as a child, the parent keeps none of those fields. "
			+ "(9) Every attribute you declare is fed by at least one rule of some feed; never declare an attribute that no source column feeds (a field "
			+ "kept 'in case' stays empty on every row). (10) Set businessKey only on the attributes that identify a record of the entity in its own "
			+ "source. When a landing table is NOT one row per record of the entity you feed from it (a row per ticket, line or event that merely "
			+ "mentions the entity, with no identifier of it), still feed the entity if it needs those fields, but say so in the rationale of that feed "
			+ "(one row = one <event>, duplicates are merged at matching) - the person reviewing must see it. A landing table whose rows each carry the "
			+ "identity attributes of an entity but hold no identifier of that entity is still a SOURCE of that entity: every such table left out of the "
			+ "entity's feeds leaves the records seen only in it out of the master.\n\n"
			+ "Example 1 (persons/addresses): a flat 'CUSTOMER' table carrying a person's own fields AND their address inline still becomes two target "
			+ "entities, Personne and Adresse (parentEntity Personne, parentLinkAttribute personne) - Personne gets a root feed on CUSTOMER, Adresse gets "
			+ "a CHILD feed on the SAME CUSTOMER table (parentFeed = the Personne feed), with no sourceIdPipeline. A second FLAT source 'CONTACT' "
			+ "does exactly the same: a Personne root feed on CONTACT, then an Adresse CHILD feed on CONTACT. A third source with separate 'PERSON' and "
			+ "'ADDRESS' tables (ADDRESS carrying its own PERSON_ID column) feeds the SAME two entities differently: Adresse gets its OWN root feed on "
			+ "ADDRESS, with personne mapped from the PERSON_ID column like any other attribute. Last, "
			+ "Personne gets propose_child_keys (name addressKeys, childEntity Adresse, fields zip then street): two person records written from two sources "
			+ "at the same address then carry the same key.\n"
			+ "Example 2 (products/variants): a 'PRODUCT' table and a separate 'VARIANT' table (carrying its own PRODUCT_ID column) become Produit and "
			+ "Variante (parentEntity Produit, parentLinkAttribute produit) - Variante is fed by its own ROOT feed on VARIANT, produit mapped from "
			+ "PRODUCT_ID; there is no child feed here, because there is no flat, single-table source for this example.\n\n"
			+ "Transformation catalogue for a feed rule's pipeline (combine steps with '|', e.g. trim|upper) - only these names are valid:\n"
			+ StagingLlmPass.TRANSFORMATION_CATALOGUE + "\n"
			+ "A rule that is refused, corrected or flagged is never a reason to give up its source: the answer says which rule and why (counts and "
			+ "pipelines only - the real values stay on the server); fix that one rule if you can, and carry on with the rest of the feed and the next "
			+ "table. A value check on a typed attribute (date, number, boolean) may replace your pipeline with the one the column's measured format "
			+ "gives, or record the rule flagged for a person: the feed stands either way.\n"
			+ "When a tool call is rejected because of a mistake, correct it in your next call - but never repeat the exact same call expecting a "
			+ "different answer: a refusal repeated three times in a row ends the run outright. Every target entity needs at least one feed: an entity none of "
			+ "whose attributes is fed from a landing column is never built (map at least one attribute of it to a landing column with propose_feed, or do "
			+ "not propose it; if you still think it belongs to the model, say why in a note naming it - it then waits for a person). Every "
			+ "landing table you intend to use should end up read by some feed or explained by a note. When one target entity takes over the "
			+ "per-table entities of several sources, every filled column those per-table entities read must still be read by a live entity "
			+ "(propose_feed again for the same entity and landing table adds the missing rules to the feed you already proposed) or named in a note "
			+ "with the reason it is left out. When you have proposed everything you are "
			+ "confident about, answer with a short final summary of what you proposed." + PromptData.SYSTEM_NOTICE;
	}

	/**
	 * Fix/staging-proposal-quality (2026-10-17, item 3): the system prompt plus the language the person works in. Labels and rationales were written
	 * in English on a French project (TiersB2CRetestLanding: "Customer", "storeCode", every justification); technical names stay English
	 * identifiers. English (or an unknown locale) reads exactly as {@link #systemPrompt()} - nothing is added.
	 */
	public static String systemPrompt(final java.util.Locale pLocale)
	{
		return StagingLlmPass.systemPrompt() + StagingLlmPass.languageInstruction(pLocale);
	}

	/** The paragraph {@link #systemPrompt(java.util.Locale)} adds, or the empty string for English / no locale. */
	static String languageInstruction(final java.util.Locale pLocale)
	{
		if (pLocale == null || pLocale.getLanguage().isEmpty() || "en".equals(pLocale.getLanguage()))
		{
			return "";
		}
		final String language = pLocale.getDisplayLanguage(java.util.Locale.ENGLISH);
		return "\n\nLANGUAGE: the person reviewing your proposals works in " + language + " (" + pLocale.toLanguageTag() + "). Write the 'label' of every "
			+ "entity and attribute, every rationale, every note and your final summary in " + language + ", in plain business wording. The technical "
			+ "identifiers stay exactly as specified above, in English: entity names (PascalCase), attribute names (camelCase), variants, pipelines, "
			+ "landing table and column names - never translate those.";
	}

	/** The one extra turn given when the model ended with landing tables neither fed nor explained - see {@link Session#tablesNotYetCovered()}. */
	public static String followUpPrompt(final Session pSession, final List<String> pTableKeys)
	{
		return StagingLlmPass.followUpPrompt(pSession, pTableKeys, null);
	}

	/**
	 * The one extra turn, on landing tables neither fed nor explained AND/OR on filled columns of read tables that no feed
	 * reads (see {@link Session#unreadColumns(boolean)}) - one turn for both, never two.
	 */
	public static String followUpPrompt(final Session pSession, final List<String> pTableKeys,
		final Map<String, List<StagingRules.LandingColumnInfo>> pUnreadColumns)
	{
		return StagingLlmPass.followUpPrompt(pSession, pTableKeys, pUnreadColumns, null);
	}

	/**
	 * Lot M7 (2026-10-18): the refusal an entity no landing column feeds is given - in the follow-up turn and, when the run ends with it still unfed, on
	 * its row (same words, {@code ProposalChecks.KEY_NO_FEED}). Structural, domain-free: the technical key and the link to the parent never count as fed.
	 */
	public static String noFeedRefusal(final String pEntityName)
	{
		return "map at least one attribute of " + pEntityName + " to a landing column, or do not propose it";
	}

	/**
	 * The follow-up prompt with, besides the tables and columns left out, the target entities of this run that no feed reads yet ({@link
	 * Session#entitiesNotYetFed()}): the model is asked to feed them or to say why they should not be kept (lot M7).
	 */
	public static String followUpPrompt(final Session pSession, final List<String> pTableKeys,
		final Map<String, List<StagingRules.LandingColumnInfo>> pUnreadColumns, final List<String> pEntitiesWithoutFeed)
	{
		return StagingLlmPass.followUpPrompt(pSession, pTableKeys, pUnreadColumns, pEntitiesWithoutFeed, null);
	}

	/**
	 * Lot M15 (2026-10-18): the follow-up prompt with, besides the rest, the columns that fed a superseded per-table entity and that no live entity reads ({@link
	 * Session#columnsLeftBehind()}). Each column is listed once: in the "left behind" paragraph, not again among the unread columns.
	 */
	public static String followUpPrompt(final Session pSession, final List<String> pTableKeys,
		final Map<String, List<StagingRules.LandingColumnInfo>> pUnreadColumns, final List<String> pEntitiesWithoutFeed, final List<LeftBehind> pLeftBehind)
	{
		return StagingLlmPass.followUpPrompt(pSession, pTableKeys, pUnreadColumns, pEntitiesWithoutFeed, pLeftBehind, null);
	}

	/**
	 * Lot S10 (2026-10-18): the follow-up prompt with, besides the rest, the numeric feedback on the landing tables whose rows carry identity attributes and feed no
	 * entity that holds the identity of the other tables ({@link Session#identityGaps()}, {@link IdentityCoverage}). Facts and a question, never a decision: the model
	 * feeds the table or says in a note why not, and whichever it chooses stands.
	 */
	public static String followUpPrompt(final Session pSession, final List<String> pTableKeys,
		final Map<String, List<StagingRules.LandingColumnInfo>> pUnreadColumns, final List<String> pEntitiesWithoutFeed, final List<LeftBehind> pLeftBehind,
		final List<IdentityCoverage.Gap> pIdentityGaps)
	{
		return StagingLlmPass.followUpPrompt(pSession, pTableKeys, pUnreadColumns, pEntitiesWithoutFeed, pLeftBehind, pIdentityGaps, null);
	}

	/**
	 * Lot F2 (2026-10-19): the follow-up prompt with, besides the rest, the coverage of each proposed entity by the landing tables that carry its kind of columns
	 * ({@link Session#entityCoverage()}): how many it is fed by, how many rows it would receive, which tables it is not fed from. A count with a question, never a decision: the model
	 * adds feeds or notes why not, and whichever it chooses stands. No second turn: this paragraph rides on the one follow-up turn.
	 */
	public static String followUpPrompt(final Session pSession, final List<String> pTableKeys,
		final Map<String, List<StagingRules.LandingColumnInfo>> pUnreadColumns, final List<String> pEntitiesWithoutFeed, final List<LeftBehind> pLeftBehind,
		final List<IdentityCoverage.Gap> pIdentityGaps, final List<IdentityCoverage.EntityCoverage> pCoverage)
	{
		return StagingLlmPass.followUpPrompt(pSession, pTableKeys, pUnreadColumns, pEntitiesWithoutFeed, pLeftBehind, pIdentityGaps, pCoverage, null);
	}

	/**
	 * Lot F2b (2026-10-19): the follow-up prompt with, besides the rest, the spans of the date columns feeding one date attribute when they do not overlap
	 * ({@link Session#dateSpanDisjoints()}): a fact and a request, the model fixes the mapping or says in a note why the sources belong together.
	 */
	public static String followUpPrompt(final Session pSession, final List<String> pTableKeys,
		final Map<String, List<StagingRules.LandingColumnInfo>> pUnreadColumns, final List<String> pEntitiesWithoutFeed, final List<LeftBehind> pLeftBehind,
		final List<IdentityCoverage.Gap> pIdentityGaps, final List<IdentityCoverage.EntityCoverage> pCoverage, final List<DateSpans.Disjoint> pDateSpans)
	{
		final StringBuilder out = new StringBuilder();
		out.append("EARLIER IN THIS RUN you already proposed (it stands, do not propose it again; name these entities and feeds as they are):\n")
			.append(pSession.summaryOfThisRun()).append('\n');
		if (pTableKeys != null && !pTableKeys.isEmpty())
		{
			out.append("You ended without feeding or explaining these landing tables: " + StagingLlmPass.join(pTableKeys) + ". Every one of them describes "
				+ "records that must reach the target model, or be left out for a stated reason. For each: call describe_landing_table if you have not, "
				+ "then propose_feed into the target entities you already proposed (converting values as rule (4) says: split a full name or an "
				+ "address line with regex(...), parse a text date with date(...)), and child feeds for what the row embeds - or call note with the "
				+ "table name and the reason it cannot be fed.\n");
		}
		out.append(StagingLlmPass.unreadColumnsPrompt(pSession, StagingLlmPass.withoutColumns(pUnreadColumns, pLeftBehind)));
		out.append(StagingLlmPass.leftBehindPrompt(pLeftBehind));
		out.append(StagingLlmPass.identityGapsPrompt(pIdentityGaps));
		out.append(StagingLlmPass.entityCoveragePrompt(pCoverage));
		out.append(StagingLlmPass.dateSpansPrompt(pDateSpans));
		if (pEntitiesWithoutFeed != null && !pEntitiesWithoutFeed.isEmpty())
		{
			out.append("You proposed these target entities and fed NONE of their attributes from any landing column: " + StagingLlmPass.join(pEntitiesWithoutFeed)
				+ ". An entity no landing column feeds is never built - it is refused as it stands. For each one: ");
			for (final String entity : pEntitiesWithoutFeed)
			{
				out.append(StagingLlmPass.noFeedRefusal(entity)).append("; ");
			}
			out.append("to feed it, call propose_feed with at least one rule reading a real column of a landing table into one of its attributes "
				+ "(the technical key and the link to the parent do not count). If you keep it without a feed, call note with the entity name and "
				+ "the reason: it then stays proposed for a person and is never accepted in bulk.\n");
		}
		out.append("Then answer with a short summary.");
		return out.toString();
	}

	/**
	 * The follow-up turn's paragraph on filled columns no feed reads, per table: what each table's feeds already read,
	 * then the unread columns with their type, fill rate and value shapes. Empty when there is none.
	 */
	static String unreadColumnsPrompt(final Session pSession, final Map<String, List<StagingRules.LandingColumnInfo>> pUnreadColumns)
	{
		if (pUnreadColumns == null || pUnreadColumns.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder();
		for (final Map.Entry<String, List<StagingRules.LandingColumnInfo>> entry : pUnreadColumns.entrySet())
		{
			out.append("- ").append(entry.getKey()).append(", already read by:\n");
			for (final String feedLine : pSession.feedsReading(entry.getKey()))
			{
				out.append("    - ").append(feedLine).append('\n');
			}
			out.append("  not read by any feed:\n");
			for (final StagingRules.LandingColumnInfo column : entry.getValue())
			{
				out.append("    - ").append(StagingLlmPass.describeColumn(column)).append('\n');
			}
		}
		final String listing = out.toString();
		out.setLength(0);
		out.append("These landing tables ARE read by your feeds, but some of their filled columns are read by no rule of any feed (a "
			+ "constant(...) rule reads nothing): their values would never reach the staging.\n");
		out.append(PromptData.fence("UNREAD COLUMNS", listing));
		out.append("For each group of these columns, decide: when they hold another record of an entity you already feed from this row "
			+ "(a second address, a second phone...), propose one more CHILD feed of the same parent feed with its own variant and a "
			+ "constant(<variant>) rule on the discriminating attribute (rule (3)); when they belong to an entity or attribute already fed, "
			+ "propose the feed that reads them. Otherwise call note with the table key and EVERY such column name written out in full "
			+ "(no wildcard), saying why they are left out on purpose.\n");
		return out.toString();
	}

	private static String joinAttributes(final List<CreatedFeedRule> pRules)
	{
		final List<String> names = new ArrayList<String>();
		for (final CreatedFeedRule rule : pRules)
		{
			names.add(rule.attribute);
		}
		return StagingLlmPass.join(names);
	}

	/** Pure: {@code pColumns} without the columns the {@code pLeftBehind} groups list (each column is asked about once). */
	public static Map<String, List<StagingRules.LandingColumnInfo>> withoutColumns(final Map<String, List<StagingRules.LandingColumnInfo>> pColumns,
		final List<LeftBehind> pLeftBehind)
	{
		if (pColumns == null || pLeftBehind == null || pLeftBehind.isEmpty())
		{
			return pColumns;
		}
		final Map<String, List<StagingRules.LandingColumnInfo>> out = new LinkedHashMap<String, List<StagingRules.LandingColumnInfo>>();
		for (final Map.Entry<String, List<StagingRules.LandingColumnInfo>> entry : pColumns.entrySet())
		{
			final List<StagingRules.LandingColumnInfo> kept = new ArrayList<StagingRules.LandingColumnInfo>(entry.getValue());
			for (final LeftBehind group : pLeftBehind)
			{
				if (group.landingTableKey.equals(entry.getKey()))
				{
					kept.removeAll(group.columns);
				}
			}
			if (!kept.isEmpty())
			{
				out.put(entry.getKey(), kept);
			}
		}
		return out;
	}

	/**
	 * Lot M15: the follow-up turn's paragraph on columns that fed a superseded per-table entity and that no live entity reads - the model names each one
	 * with its replaced entity and its canonical, and is asked to map it or say why. Empty when there is none.
	 */
	static String leftBehindPrompt(final List<LeftBehind> pLeftBehind)
	{
		if (pLeftBehind == null || pLeftBehind.isEmpty())
		{
			return "";
		}
		final StringBuilder listing = new StringBuilder();
		for (final LeftBehind group : pLeftBehind)
		{
			listing.append("- columns fed to the superseded entity ").append(group.supersededEntity).append(" and not to its canonical ").append(group.canonicalEntity)
				.append(", in ").append(group.landingTableKey).append(":\n");
			for (final StagingRules.LandingColumnInfo column : group.columns)
			{
				listing.append("    - ").append(StagingLlmPass.describeColumn(column)).append('\n');
			}
		}
		final StringBuilder out = new StringBuilder();
		out.append("Your target entities take over the per-table entities of the rule pass, which are SUPERSEDED: the load never reads a superseded entity. "
			+ "These filled columns were read by a superseded entity and are read by NO live entity, so their values would never reach the staging.\n");
		out.append(PromptData.fence("COLUMNS LEFT BEHIND", listing.toString()));
		out.append("For each column: map it to an attribute of its canonical entity (call propose_feed again with the same entity and landing table - the rule is "
			+ "added to the feed you already proposed; add the attribute with propose_target_entity first only if the entity truly lacks it), or call note with "
			+ "the table key and the column name written out in full and the reason it is left out.\n");
		return out.toString();
	}

	/**
	 * Lot S10: the follow-up turn's paragraph on landing tables whose rows carry identity attributes and feed no entity that holds them: per table the counts the Factory
	 * measured (rows, and the identity values seen in no other source) and the two answers - feed it, or say in a note why not. Empty when there is none.
	 */
	static String identityGapsPrompt(final List<IdentityCoverage.Gap> pGaps)
	{
		if (pGaps == null || pGaps.isEmpty())
		{
			return "";
		}
		final StringBuilder listing = new StringBuilder();
		for (final IdentityCoverage.Gap gap : pGaps)
		{
			listing.append("- ").append(gap.followUpText()).append('\n');
		}
		final StringBuilder out = new StringBuilder();
		out.append("These landing tables carry the identity attributes (catalog tags, or value shapes measured where the catalogue gave none) of an entity that other sources feed, yet none of your feeds reads them into it. "
			+ "Counts measured by the Factory on the landing tables:\n");
		out.append(PromptData.fence("IDENTITY WITHOUT A FEED", listing.toString()));
		boolean suggestedOnly = false;
		for (final IdentityCoverage.Gap gap : pGaps)
		{
			suggestedOnly |= gap.suggestedOnly();
		}
		boolean shaped = false;
		for (final IdentityCoverage.Gap gap : pGaps)
		{
			shaped |= gap.hasShapes();
		}
		if (shaped)
		{
			out.append("A column listed under 'measured from value shapes' has no tag in the catalogue: the Factory counted how many of its sampled values are WRITTEN like an e-mail, a phone number "
				+ "or two capitalised words (the share is given; reported from " + IdentityShapes.thresholdsText() + "). That measures how the values look, not what the column is - weigh it with the "
				+ "column name and the other columns.\n");
		}
		if (suggestedOnly)
		{
			out.append("A tag 'suggested by the catalogue' is the catalogue's own measurement (a regex on the values it sampled, a name hint, its dictionary or model) still waiting "
				+ "for a steward's review: firmer than a guess of yours, less firm than an applied tag.\n");
		}
		out.append("For each table, decide: propose the root feed it needs (call propose_feed with the entity named, mapping the table's identity columns and the rest of "
			+ "what the entity holds), or call note with the table key and the reason it must not feed that entity. Either answer stands; the counts are given so that "
			+ "the choice is made knowing what the master would miss.\n");
		return out.toString();
	}

	/**
	 * Lot F2: the follow-up turn's paragraph on the coverage of the entities it proposed - per entity the landing tables that carry its kind of columns, how many feed it and how many rows it
	 * would receive. When a carrying table feeds an entity nothing, the two answers (feed it, or note why not) are stated; either stands. Empty when there is no coverage line.
	 */
	static String entityCoveragePrompt(final List<IdentityCoverage.EntityCoverage> pCoverage)
	{
		if (pCoverage == null || pCoverage.isEmpty())
		{
			return "";
		}
		final StringBuilder listing = new StringBuilder();
		boolean partial = false;
		for (final IdentityCoverage.EntityCoverage coverage : pCoverage)
		{
			listing.append("- ").append(coverage.followUpText()).append('\n');
			partial |= coverage.partial();
		}
		final StringBuilder out = new StringBuilder();
		out.append("Coverage of the entities you proposed, counted by the Factory from your feeds and the rows of the landing tables. A landing table 'carries the kind of columns' of an entity when "
			+ "it holds a column of one of the identity kinds (e-mail, phone, name, official identifier...) that the entity's own feeds read:\n");
		out.append(PromptData.fence("ENTITY COVERAGE", listing.toString()));
		if (partial)
		{
			out.append("Where a table is listed 'not fed', either call propose_feed to read it into that entity, or call note with the table key and the reason it does not belong there. "
				+ "Either answer stands; the counts show what each entity receives as it is.\n");
		}
		return out.toString();
	}

	/**
	 * Lot F2b: the follow-up turn's paragraph on a date attribute fed by several columns whose spans fall in groups that do not overlap (run 8, 2026-10-19: a birth date fed from a purchase
	 * date). The spans are counted by the Factory; the two answers are a corrected feed or a note, and either stands. Empty when there is none.
	 */
	static String dateSpansPrompt(final List<DateSpans.Disjoint> pDisjoint)
	{
		if (pDisjoint == null || pDisjoint.isEmpty())
		{
			return "";
		}
		final StringBuilder listing = new StringBuilder();
		for (final DateSpans.Disjoint one : pDisjoint)
		{
			listing.append("- ").append(one.followUpText()).append('\n');
		}
		return "Date attributes you fed from several columns, with the span of each column's values (earliest to latest month, and the rows read; counted by the Factory):\n"
			+ PromptData.fence("DATE SPANS", listing.toString())
			+ "A span says when the values of a column fall, not what the column is. Where the spans of the columns feeding one attribute do not overlap, the answers are "
			+ "propose_feed again on the same entity and table (a rule for an attribute the feed already maps replaces the old one; add \"remove\": true to drop it), or call note with "
			+ "the entity.attribute and the reason the sources belong together. Either answer stands.\n";
	}

	/** {@code NAME (type T, fill rate N%, value shapes: ...)} - one landing column as the follow-up turn lists it. */
	static String describeColumn(final StagingRules.LandingColumnInfo pColumn)
	{
		final StringBuilder text = new StringBuilder();
		text.append(pColumn.fieldName).append(" (type ").append(StagingLlmPass.typeOf(pColumn));
		if (pColumn.fillRate != null)
		{
			text.append(", fill rate ").append(pColumn.fillRate).append('%');
		}
		if (!pColumn.shapes.isEmpty())
		{
			text.append(", value shapes: ").append(StagingLlmPass.join(pColumn.shapes));
		}
		text.append(StagingLlmPass.tokenCaseText(pColumn));
		return text.append(')').toString();
	}

	/**
	 * What the model reads about a column's token case, appended to its description: {@code , token case: first token all
	 * upper case in ...} when most values hold several tokens, then, when {@link TokenCase#orderUnknown} (multi-token
	 * values whose casing does not tell the parts apart), {@code , ORDER UNKNOWN: <evidence>} - the Vol100kN gap of
	 * 2026-10-12 (see {@link PartOrder}): a full-name column written in both orders with no marker, which the model
	 * split with a casing regex proposed for another source. Empty when there is nothing to say.
	 */
	static String tokenCaseText(final StagingRules.LandingColumnInfo pColumn)
	{
		// staging normalisation lot D (2026-10-17): the measured value profile (formats, case, phone shape...) rides with the token case
		// lot F2b (2026-10-19): ... and the span of a date column (earliest and latest month, rows in the last 24 months)
		// B2B test (2026-10-23): ... and what the row-level pass measured (own key, keys of another source, a value cut at a fixed width, identifier shapes and separators)
		final String facts = (pColumn.profileFacts == null ? "" : pColumn.profileFacts) + StagingLlmPass.dateSpanText(pColumn)
			+ (pColumn.rowFacts == null ? "" : pColumn.rowFacts);
		if (!StagingLlmPass.showsTokenCase(pColumn))
		{
			return facts;
		}
		final StringBuilder text = new StringBuilder(", token case: ").append(pColumn.tokenCase.render());
		final String evidence = pColumn.tokenCase.renderOrderEvidence();
		if (evidence != null)
		{
			text.append(", ORDER UNKNOWN: ").append(evidence);
		}
		return text.append(facts).toString();
	}

	/**
	 * Lot F2b (2026-10-19): {@code , values from 1951-03 to 2005-12 (filled rows: 160)} (+ {@code , 234 rows in the last 24 months}) for a date column measured by {@link DateSpans}: the
	 * span of its values at month precision, a count-like fact given for a sensitive column too (no day, no value). Empty for any other column.
	 */
	static String dateSpanText(final StagingRules.LandingColumnInfo pColumn)
	{
		return pColumn.dateSpan == null ? "" : ", " + pColumn.dateSpan.inventoryText();
	}

	/**
	 * Whether a column's {@link TokenCase} is worth a mention to the model: measured, and most of its values hold several
	 * tokens (a code or a single word has no layout). PersVol7 (2026-09-28): the model saw the top three shapes of a full
	 * name column, which vary with the name lengths, and never the 30% of values with a leading token in capitals.
	 */
	static boolean showsTokenCase(final StagingRules.LandingColumnInfo pColumn)
	{
		return pColumn.tokenCase != null && pColumn.tokenCase.mostlyMultiToken();
	}

	/**
	 * Pure: for every landing table of {@code pTablesByKey} that some feed reads (a key of {@code pReadColumnsByTable},
	 * the value holding the field names its feeds read), the columns filled in at least one row (fill rate above 0) that
	 * none of them reads. Never lists a technical column (a name starting with an underscore), a key column, or a column
	 * of a table with no profile. Tables no feed reads are {@link Session#tablesNotYetCovered()}'s concern, not this one's.
	 * Domain-free on purpose: grouping the columns (a second address...) is the model's job.
	 */
	public static Map<String, List<StagingRules.LandingColumnInfo>> unreadFilledColumns(final Map<String, StagingRules.LandingTableInfo> pTablesByKey,
		final Map<String, Set<String>> pReadColumnsByTable)
	{
		final Map<String, List<StagingRules.LandingColumnInfo>> out = new LinkedHashMap<String, List<StagingRules.LandingColumnInfo>>();
		for (final Map.Entry<String, StagingRules.LandingTableInfo> entry : pTablesByKey.entrySet())
		{
			final Set<String> read = pReadColumnsByTable.get(entry.getKey());
			if (read == null)
			{
				continue;
			}
			final List<StagingRules.LandingColumnInfo> unread = new ArrayList<StagingRules.LandingColumnInfo>();
			for (final StagingRules.LandingColumnInfo column : entry.getValue().columns)
			{
				if (column.fieldName == null || column.fieldName.startsWith("_") || column.sourceKey || read.contains(column.fieldName)
					|| column.fillRate == null || column.fillRate.signum() <= 0)
				{
					continue;
				}
				unread.add(column);
			}
			if (!unread.isEmpty())
			{
				out.put(entry.getKey(), unread);
			}
		}
		return out;
	}

	/** Pure: whether at least one of these unread columns is filled in {@link #UNREAD_COLUMN_FOLLOW_UP_FILL_RATE} percent of rows or more. */
	public static boolean worthAFollowUp(final Map<String, List<StagingRules.LandingColumnInfo>> pUnreadColumns)
	{
		for (final List<StagingRules.LandingColumnInfo> columns : pUnreadColumns.values())
		{
			for (final StagingRules.LandingColumnInfo column : columns)
			{
				if (column.fillRate != null && column.fillRate.compareTo(StagingLlmPass.UNREAD_COLUMN_FOLLOW_UP_FILL_RATE) >= 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Pure: whether one of {@code pNotes} names this column of this table - the table (its key or its bare name) and the
	 * column's full field name, both as whole words. How a column left out on purpose is told from a forgotten one.
	 */
	public static boolean explainedByNote(final String pLandingTableKey, final String pColumnFieldName, final List<String> pNotes)
	{
		final int dot = pLandingTableKey.indexOf('.');
		final String tableName = dot < 0 ? pLandingTableKey : pLandingTableKey.substring(dot + 1);
		final Pattern table = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(tableName) + "(?![A-Za-z0-9_])");
		final Pattern column = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(pColumnFieldName) + "(?![A-Za-z0-9_])");
		for (final String note : pNotes)
		{
			if (table.matcher(note).find() && column.matcher(note).find())
			{
				return true;
			}
		}
		return false;
	}

	/** {@code "CRM.CONTACT: OTHER_STREET, OTHER_CITY; ERP.X: Y"} - for the run outcome and the pipeline step message. */
	public static String renderColumns(final Map<String, List<StagingRules.LandingColumnInfo>> pColumnsByTable)
	{
		final StringBuilder out = new StringBuilder();
		for (final Map.Entry<String, List<StagingRules.LandingColumnInfo>> entry : pColumnsByTable.entrySet())
		{
			if (out.length() > 0)
			{
				out.append("; ");
			}
			out.append(entry.getKey()).append(": ");
			final List<String> names = new ArrayList<String>();
			for (final StagingRules.LandingColumnInfo column : entry.getValue())
			{
				names.add(column.fieldName);
			}
			out.append(StagingLlmPass.join(names));
		}
		return out.toString();
	}

	// ------------------------------------------------------------------------------------------------ value objects

	/**
	 * Lot M15 (2026-10-18): filled landing columns that fed the per-table entity {@link #supersededEntity} and that no live entity reads - what {@link
	 * Session#columnsLeftBehind()} answers, for the follow-up turn and the record step's {@link ProposalChecks#SUPERSEDED_COLUMN_LEFT_BEHIND} finding.
	 */
	public static final class LeftBehind
	{
		/** The target entity that reads the table (through a root feed) and replaced the per-table entity. */
		public final String canonicalEntity;
		/** The per-table (rule) entity that read these columns, now superseded. */
		public final String supersededEntity;
		public final String landingTableKey;
		public final String sourceSystem;
		public final List<StagingRules.LandingColumnInfo> columns;

		LeftBehind(final String pCanonicalEntity, final String pSupersededEntity, final String pLandingTableKey, final String pSourceSystem,
			final List<StagingRules.LandingColumnInfo> pColumns)
		{
			this.canonicalEntity = pCanonicalEntity;
			this.supersededEntity = pSupersededEntity;
			this.landingTableKey = pLandingTableKey;
			this.sourceSystem = pSourceSystem;
			this.columns = pColumns;
		}

		/** {@code "STORES.TELEPHONE, STORES.MAGASIN"} - source system and column, as a person knows them. */
		public String columnNames()
		{
			final List<String> names = new ArrayList<String>();
			for (final StagingRules.LandingColumnInfo column : this.columns)
			{
				names.add(this.sourceSystem + "." + column.fieldName);
			}
			return StagingLlmPass.join(names);
		}
	}

	/** Pure: the columns of these left-behind groups by landing table key, in the shape {@link #worthAFollowUp} and {@link #renderColumns} read. */
	public static Map<String, List<StagingRules.LandingColumnInfo>> columnsOf(final List<LeftBehind> pLeftBehind)
	{
		final Map<String, List<StagingRules.LandingColumnInfo>> out = new LinkedHashMap<String, List<StagingRules.LandingColumnInfo>>();
		if (pLeftBehind != null)
		{
			for (final LeftBehind group : pLeftBehind)
			{
				out.put(group.landingTableKey, group.columns);
			}
		}
		return out;
	}

	/** One rule-proposed entity, named for context only ("what a rule pass would do") - {@link Session} also uses it for the name-collision refusal and for computing {@link CreatedEntity#memberEntities}. */
	public static final class RuleEntityInfo
	{
		public final String name;
		public final String sourceSystem;
		public final String tableName;
		/**
		 * Lot M15 (2026-10-18): the field names of the landing columns this rule entity's own live mappings read (not rejected by a person, a {@code
		 * constant(...)} rule excluded) - what a canonical entity that absorbs it must still read ({@link Session#columnsLeftBehind()}); null when unknown
		 * (nothing is then claimed about this entity: the unread columns of its table are {@link Session#unreadColumns}'s concern alone).
		 */
		public final List<String> readColumns;

		public RuleEntityInfo(final String pName, final String pSourceSystem, final String pTableName)
		{
			this(pName, pSourceSystem, pTableName, null);
		}

		public RuleEntityInfo(final String pName, final String pSourceSystem, final String pTableName, final List<String> pReadColumns)
		{
			this.name = pName;
			this.sourceSystem = pSourceSystem;
			this.tableName = pTableName;
			this.readColumns = pReadColumns == null ? null : new ArrayList<String>(pReadColumns);
		}

		String landingTableKey()
		{
			return StagingLlmPass.landingTableKey(this.sourceSystem, this.tableName);
		}
	}

	/** A project's landing inventory and rule-proposed entities, exactly what {@link #userPrompt} renders and {@link Session} validates against. */
	public static final class ProjectInfo
	{
		public final String code;
		public final String label;
		public final List<StagingRules.LandingTableInfo> tables;
		public final List<StagingRules.RelationInfo> relations;
		public final List<RuleEntityInfo> ruleEntities;

		public ProjectInfo(final String pCode, final String pLabel, final List<StagingRules.LandingTableInfo> pTables,
			final List<StagingRules.RelationInfo> pRelations, final List<RuleEntityInfo> pRuleEntities)
		{
			this.code = pCode;
			this.label = pLabel;
			this.tables = pTables == null ? new ArrayList<StagingRules.LandingTableInfo>() : pTables;
			this.relations = pRelations == null ? new ArrayList<StagingRules.RelationInfo>() : pRelations;
			this.ruleEntities = pRuleEntities == null ? new ArrayList<RuleEntityInfo>() : pRuleEntities;
		}
	}

	private static String landingTableKey(final String pSourceSystem, final String pTableName)
	{
		return pSourceSystem + "." + pTableName;
	}

	/** Whether {@link #userPrompt} renders the compact catalogue for this project - see the class Javadoc. {@link Session} calls this too, so both always agree. */
	public static boolean isCompact(final ProjectInfo pProject)
	{
		return StagingLlmPass.renderFull(pProject).length() > StagingLlmPass.INVENTORY_CHAR_BUDGET;
	}

	/** No prior target entities - a fresh project, or a caller/test that does not exercise re-run idempotence. */
	public static String userPrompt(final ProjectInfo pProject)
	{
		return StagingLlmPass.userPrompt(pProject, java.util.Collections.<ExistingTargetEntity>emptyList());
	}

	/**
	 * {@code pPriorEntities} - every {@code origin=LLM} target entity the project already had before this run
	 * (see {@link Session}'s own constructor parameter) - is rendered as a trailing section AFTER the
	 * full-vs-compact decision (docs/TARGET-MAPPING.md, "Relance de la passe LLM"): it never counts against
	 * {@link #INVENTORY_CHAR_BUDGET}, so {@link #isCompact}/{@link Session}'s own {@code compact} flag stay
	 * exactly the landing-inventory-only decision they already were - the class Javadoc's "the two never
	 * disagree" invariant is unaffected by how many prior entities a project has accumulated.
	 */
	public static String userPrompt(final ProjectInfo pProject, final List<ExistingTargetEntity> pPriorEntities)
	{
		final String full = StagingLlmPass.renderFull(pProject);
		final String base = full.length() <= StagingLlmPass.INVENTORY_CHAR_BUDGET ? full : StagingLlmPass.renderCompact(pProject);
		// audit F-28: the names of the sources' tables and columns are data; the closing instruction stays outside the fence
		return PromptData.fence("LANDING INVENTORY", base) + StagingLlmPass.renderPriorEntities(pPriorEntities) + StagingLlmPass.TASK_LINE;
	}

	/** The "TARGET ENTITIES ALREADY PROPOSED IN AN EARLIER RUN" section - see {@link #userPrompt(ProjectInfo, List)}. Empty string when there is nothing to report, same as every other empty-input render in this class. */
	private static String renderPriorEntities(final List<ExistingTargetEntity> pPriorEntities)
	{
		if (pPriorEntities == null || pPriorEntities.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder();
		for (final ExistingTargetEntity entity : pPriorEntities)
		{
			out.append("- ").append(entity.name).append(" (").append(entity.status).append("), attributes: ")
				.append(StagingLlmPass.join(entity.attributeNames));
			if (entity.parentEntity != null)
			{
				out.append(", child of ").append(entity.parentEntity).append(" via ").append(entity.parentLinkAttribute);
			}
			out.append('\n');
			for (final ExistingFeed feed : entity.feeds)
			{
				out.append("    - feed on ").append(feed.landingTableKey).append(" (").append(feed.status)
					.append(feed.parentFeedId == null ? ", root" : ", child").append(")\n");
			}
		}
		return "\nTARGET ENTITIES ALREADY PROPOSED IN AN EARLIER RUN - reuse these exact names for the same concept, never a synonym or a "
			+ "translation; do not repropose a feed listed below. If they already cover everything, propose nothing new and end with a note.\n"
			+ PromptData.fence("EARLIER ENTITIES", out.toString());
	}

	private static String renderFull(final ProjectInfo pProject)
	{
		final StringBuilder out = new StringBuilder();
		StagingLlmPass.renderHeader(out, pProject);
		out.append("Landing tables:\n");
		for (final StagingRules.LandingTableInfo table : pProject.tables)
		{
			out.append("\n- ").append(table.sourceSystem).append('.').append(table.name).append(" (").append(table.columns.size()).append(" columns")
				.append(StagingLlmPass.tableFacts(table)).append(")\n");
			for (final StagingRules.LandingColumnInfo column : table.columns)
			{
				out.append("    - ").append(column.fieldName).append(" (type ").append(StagingLlmPass.typeOf(column));
				if (column.sourceKey)
				{
					out.append(", key");
				}
				out.append(StagingLlmPass.tagsText(column));
				if (column.fillRate != null)
				{
					out.append(", fill rate ").append(column.fillRate).append('%');
				}
				out.append(StagingLlmPass.identityText(column));
				if (!column.shapes.isEmpty())
				{
					out.append(", value shapes: ").append(StagingLlmPass.join(column.shapes));
				}
				out.append(StagingLlmPass.tokenCaseText(column));
				out.append(")\n");
			}
		}
		StagingLlmPass.renderRelationsAndRuleEntities(out, pProject);
		return out.toString();
	}

	private static String renderCompact(final ProjectInfo pProject)
	{
		final StringBuilder out = new StringBuilder();
		StagingLlmPass.renderHeader(out, pProject);
		out.append("Landing tables (compact - call describe_landing_table(table) to see a table's real columns before mapping it):\n");
		for (final StagingRules.LandingTableInfo table : pProject.tables)
		{
			final List<String> keyColumns = new ArrayList<String>();
			for (final StagingRules.LandingColumnInfo column : table.columns)
			{
				if (column.sourceKey)
				{
					keyColumns.add(column.fieldName);
				}
			}
			out.append("- ").append(table.sourceSystem).append('.').append(table.name).append(" (").append(table.columns.size()).append(" columns")
				.append(StagingLlmPass.rowsText(table)).append(", key: ").append(keyColumns.isEmpty() ? "none" : StagingLlmPass.join(keyColumns))
				.append(StagingLlmPass.keyDetails(table)).append(")\n");
		}
		StagingLlmPass.renderRelationsAndRuleEntities(out, pProject);
		return out.toString();
	}

	private static void renderHeader(final StringBuilder pOut, final ProjectInfo pProject)
	{
		pOut.append("Project: ").append(pProject.code).append(" - ").append(pProject.label).append("\n\n");
	}

	private static void renderRelationsAndRuleEntities(final StringBuilder pOut, final ProjectInfo pProject)
	{
		pOut.append("\nRelations between landing tables:\n");
		if (pProject.relations.isEmpty())
		{
			pOut.append("(none)\n");
		}
		else
		{
			for (final StagingRules.RelationInfo relation : pProject.relations)
			{
				pOut.append("- ").append(relation.sourceSystem).append('.').append(relation.fromTable).append('.')
					.append(StagingLlmPass.join(relation.fromColumns)).append(" -> ").append(relation.sourceSystem).append('.').append(relation.toTable)
					.append('\n');
			}
		}
		pOut.append("\nWhat a rule pass would propose (one entity per landing table):\n");
		if (pProject.ruleEntities.isEmpty())
		{
			pOut.append("(none)\n");
		}
		else
		{
			for (final RuleEntityInfo ruleEntity : pProject.ruleEntities)
			{
				pOut.append("- ").append(ruleEntity.name).append(": ").append(ruleEntity.landingTableKey()).append('\n');
			}
		}
	}

	/** The closing instruction of {@link #userPrompt(ProjectInfo, List)}, after the fenced inventory. */
	private static final String TASK_LINE = "\nPropose the target model with propose_target_entity, then feed it with propose_feed.\n";

	/**
	 * Lot S10 (2026-10-18): the facts the inventory gives about a landing table beyond its columns - {@code , 234 rows, key: NUM_TICKET (set by a person; 234 distinct
	 * values over 234 rows; carries no identity tag)}. Only what the Factory already knows or measured (the rows of the landing table, the landing key, who set it, the key
	 * column's distinct count, the catalog's tags); never a verdict on what the table is.
	 */
	static String tableFacts(final StagingRules.LandingTableInfo pTable)
	{
		final StringBuilder out = new StringBuilder(StagingLlmPass.rowsText(pTable));
		final List<String> keyNames = new ArrayList<String>();
		for (final StagingRules.LandingColumnInfo column : pTable.columns)
		{
			if (column.sourceKey)
			{
				keyNames.add(column.fieldName);
			}
		}
		if (!keyNames.isEmpty())
		{
			out.append(", key: ").append(String.join(" + ", keyNames)).append(StagingLlmPass.keyDetails(pTable));
		}
		return out.toString();
	}

	/** {@code , 234 rows}; empty when the landing table's rows are not known. */
	static String rowsText(final StagingRules.LandingTableInfo pTable)
	{
		final Long rows = pTable.rows();
		return rows == null ? "" : ", " + rows + " row" + (rows.longValue() == 1L ? "" : "s");
	}

	/**
	 * {@code  (set by a person; 234 distinct values over 234 rows; carries no identity tag)}: who set the key, how many distinct values a single key column holds against the
	 * table's rows (not said when either is a sample), and - only on a table that has identity-tagged columns - that the key itself carries no identity tag. Empty when none is known.
	 */
	static String keyDetails(final StagingRules.LandingTableInfo pTable)
	{
		final List<StagingRules.LandingColumnInfo> keys = new ArrayList<StagingRules.LandingColumnInfo>();
		boolean anyIdentity = false;
		for (final StagingRules.LandingColumnInfo column : pTable.columns)
		{
			if (column.sourceKey)
			{
				keys.add(column);
			}
			anyIdentity |= IdentityFacts.kindOf(column) != null;
		}
		if (keys.isEmpty())
		{
			return "";
		}
		final List<String> parts = new ArrayList<String>();
		final String origin = StagingLlmPass.keyOriginWords(keys.get(0).keyOrigin);
		if (origin != null)
		{
			parts.add(origin);
		}
		final Long rows = pTable.rows();
		final boolean sampled = pTable.identity != null && pTable.identity.sampled;
		if (keys.size() == 1 && keys.get(0).distinctCount != null && rows != null && !sampled)
		{
			parts.add(keys.get(0).distinctCount + " distinct value" + (keys.get(0).distinctCount.longValue() == 1L ? "" : "s") + " over " + rows + " row" + (rows.longValue() == 1L ? "" : "s"));
		}
		if (anyIdentity)
		{
			boolean keyTagged = false;
			for (final StagingRules.LandingColumnInfo key : keys)
			{
				keyTagged |= IdentityFacts.tagKindOf(key) != null;
			}
			if (!keyTagged)
			{
				parts.add("carries no identity tag");
			}
		}
		return parts.isEmpty() ? "" : " (" + String.join("; ", parts) + ")";
	}

	/** The words for a {@code LandingColumn.sourceKeyOrigin}; null for an unknown one. */
	static String keyOriginWords(final String pOrigin)
	{
		if (pOrigin == null)
		{
			return null;
		}
		switch (pOrigin)
		{
			case "SOURCE":
				return "declared by the source";
			case "USER":
				return "set by a person";
			case "RULE":
				return "guessed from the column names";
			case "LLM":
				return "proposed by the key study";
			case "DEDUCED":
				return "deduced from the data and confirmed by a person";
			default:
				return null;
		}
	}

	/** Most suggestions of the catalogue shown beside one column. */
	static final int SUGGESTIONS_SHOWN = 4;

	/**
	 * Lot S10b (2026-10-19): the catalogue's tags of a column as measured facts - {@code , tags: EMAIL (regex matched 100% of sampled values, applied), PERSONAL_DATA} for the tags
	 * applied (with how the catalogue found one when its suggestion row says so), then {@code , suggested PHONE (regex matched 96% of sampled values, pending review)} for each
	 * suggestion that is not an applied tag: its origin, its measured share or name hint, and where a steward stands on it. The catalogue's evidence text is never carried (it may
	 * quote a column name or, for a model, anything). Empty when the column has neither.
	 */
	static String tagsText(final StagingRules.LandingColumnInfo pColumn)
	{
		final StringBuilder out = new StringBuilder();
		if (!pColumn.tags.isEmpty())
		{
			final List<String> applied = new ArrayList<String>();
			for (final String tag : pColumn.tags)
			{
				String detail = null;
				for (final StagingRules.TagSuggestionInfo suggestion : pColumn.suggestions)
				{
					if (suggestion.applied() && suggestion.tag.equalsIgnoreCase(tag))
					{
						detail = suggestion.words();
					}
				}
				applied.add(detail == null ? tag : tag + " (" + detail + ")");
			}
			out.append(", tags: ").append(StagingLlmPass.join(applied));
		}
		int shown = 0;
		for (final StagingRules.TagSuggestionInfo suggestion : pColumn.suggestions)
		{
			boolean onColumn = false;
			for (final String tag : pColumn.tags)
			{
				onColumn |= suggestion.tag.equalsIgnoreCase(tag);
			}
			if (onColumn || suggestion.applied() || shown >= StagingLlmPass.SUGGESTIONS_SHOWN)
			{
				continue;
			}
			shown++;
			out.append(", suggested ").append(suggestion.tag).append(" (").append(suggestion.words()).append(')');
		}
		return out.toString();
	}

	/**
	 * {@code , identity: 198 distinct values over 230 filled rows, repeated on 46 rows} for a column the catalog tagged as an identity and that was measured; for a column the catalogue
	 * gave no identity tag but whose values are mostly written like an e-mail, a phone number or two capitalised words (lot F2, {@link IdentityShapes}), {@code , identity-like by shape
	 * (e-mail-shaped in 98% of sampled values; no catalogue tag): 89 distinct values over 131 filled rows, repeated on 75 rows} - a measure of how the values look, labelled as one;
	 * empty otherwise.
	 */
	static String identityText(final StagingRules.LandingColumnInfo pColumn)
	{
		if (pColumn.identity == null)
		{
			return "";
		}
		if (IdentityFacts.shapeOnly(pColumn))
		{
			return ", identity-like by shape (" + IdentityFacts.shapeWords(pColumn) + "; no catalogue tag): " + pColumn.identity.inventoryText();
		}
		return ", identity: " + pColumn.identity.inventoryText();
	}

	private static String typeOf(final StagingRules.LandingColumnInfo pColumn)
	{
		return pColumn.inferredType == null || pColumn.inferredType.trim().isEmpty() ? "STRING" : pColumn.inferredType.trim().toUpperCase(Locale.ROOT);
	}

	public static String join(final List<String> pValues)
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

	/**
	 * Recovers the member list a rationale of the RETIRED canonical-entity pass was built with ({@code
	 * "Unifies A, B, C: <given rationale>"}) - kept ONLY as a one-time backfill fallback for an old LLM-origin
	 * row written before {@code StagingEntity.memberEntities} was a structured field ({@code
	 * ebx.MemberEntitiesBackfill}'s own Javadoc has the incident this exists to never repeat). The pass
	 * rewritten in this file never produces a rationale of this shape any more - {@link Session} computes
	 * {@code memberEntities} structurally, from the feed tree (see {@link Session#finalizeEntities()}) - so
	 * this method's only remaining caller is that backfill, for data written before this rewrite. Pure.
	 */
	public static List<String> parseMembers(final String pRationale)
	{
		final List<String> out = new ArrayList<String>();
		if (pRationale == null || !pRationale.startsWith("Unifies "))
		{
			return out;
		}
		final int colon = pRationale.indexOf(": ");
		if (colon < 0)
		{
			return out;
		}
		final String list = pRationale.substring("Unifies ".length(), colon);
		for (final String part : list.split(", "))
		{
			if (!part.trim().isEmpty())
			{
				out.add(part.trim());
			}
		}
		return out;
	}

	// -------------------------------------------------------------------------------------------------- proposals

	/** One attribute of a proposed target entity - what feeds one {@code StagingAttribute} row (origin {@code LLM}). */
	public static final class CreatedAttribute
	{
		public final String name;
		public final String label;
		public final String dataType;
		/** Only for {@code STRING}. */
		public final Integer maxLength;
		public final boolean mandatory;
		public final boolean businessKey;
		/**
		 * Per-field-rationale lot (2026-09-24): this attribute's OWN rationale/confidence, distinct from the
		 * owning {@link CreatedEntity#rationale}/{@link CreatedEntity#confidence} - {@link Session} falls back to
		 * the entity's own values only when the model left one blank (defensive; the schema marks both required).
		 */
		public final String rationale;
		public final int confidence;

		CreatedAttribute(final String pName, final String pLabel, final String pDataType, final Integer pMaxLength, final boolean pMandatory,
			final boolean pBusinessKey, final String pRationale, final int pConfidence)
		{
			this.name = pName;
			this.label = pLabel;
			this.dataType = pDataType;
			this.maxLength = pMaxLength;
			this.mandatory = pMandatory;
			this.businessKey = pBusinessKey;
			this.rationale = pRationale;
			this.confidence = pConfidence;
		}
	}

	/** A proposed target entity - what feeds one {@code StagingEntity} row (origin {@code LLM}) plus its {@link CreatedAttribute}s. */
	public static final class CreatedEntity
	{
		public final String name;
		public final String label;
		public final String rationale;
		public final int confidence;
		public final List<CreatedAttribute> attributes;
		/** Null for a top-level entity. */
		public final String parentEntity;
		/** Null unless {@link #parentEntity} is set: name of the attribute (one of {@link #attributes}) that carries the reference back to the parent. */
		public final String parentLinkAttribute;
		/**
		 * Null unless {@link #parentEntity} is set: the exact {@code <attribute>Ref} field name {@link
		 * StagingModel#foreignKeyFieldName} will generate once this entity is built - computed once, here (by
		 * {@link Session#proposeTargetEntity}), so every child feed of this entity reuses the SAME value as
		 * {@code TransferFeed.parentLinkField} (docs/TARGET-MAPPING.md sec 2.1).
		 */
		public final String parentLinkField;
		/**
		 * Non-null when this entity already existed (status {@code PROPOSED}) before this run: the writer
		 * creates no new {@code StagingEntity}/{@code StagingAttribute} rows for it, only feeds/mappings - see
		 * {@link Session#proposeFeed}'s lazy adoption, the same posture the retired pass called "fix F".
		 */
		public final String existingId;
		/**
		 * Computed only by {@link Session#finalizeEntities()} (empty until then): the rule-proposed entities
		 * whose landing table is read by at least one of this entity's OWN feeds (docs/TARGET-MAPPING.md sec
		 * 2.3/4.2) - what the writer supersedes, and what {@code MasterModel} still reads as {@code
		 * memberEntities} until lot 4 retires that field.
		 */
		public final List<String> memberEntities = new ArrayList<String>();

		CreatedEntity(final String pName, final String pLabel, final String pRationale, final int pConfidence, final List<CreatedAttribute> pAttributes,
			final String pParentEntity, final String pParentLinkAttribute, final String pParentLinkField, final String pExistingId)
		{
			this.name = pName;
			this.label = pLabel;
			this.rationale = pRationale;
			this.confidence = pConfidence;
			this.attributes = pAttributes == null ? new ArrayList<CreatedAttribute>() : pAttributes;
			this.parentEntity = pParentEntity;
			this.parentLinkAttribute = pParentLinkAttribute;
			this.parentLinkField = pParentLinkField;
			this.existingId = pExistingId;
		}
	}

	/** One rule (one {@code AttributeMapping} row, origin {@code LLM}) of a {@link CreatedFeed}. */
	public static final class CreatedFeedRule
	{
		public final String attribute;
		/** A column of the feed's own landing table, by {@code LandingColumn.fieldName}. */
		public final String landingColumn;
		/** The pipeline the model gave, or null for the type-driven default ({@code StagingRules.typeDrivenMapping}, resolved by the writer). */
		public final String pipeline;
		/**
		 * Per-field-rationale lot (2026-09-24): this rule's OWN rationale/confidence, distinct from the owning
		 * {@link CreatedFeed#rationale}/{@link CreatedFeed#confidence} - {@link Session} falls back to the feed's
		 * own values only when the model left one blank (defensive; the schema marks both required).
		 */
		public final String rationale;
		public final int confidence;

		CreatedFeedRule(final String pAttribute, final String pLandingColumn, final String pPipeline, final String pRationale, final int pConfidence)
		{
			this.attribute = pAttribute;
			this.landingColumn = pLandingColumn;
			this.pipeline = pPipeline;
			this.rationale = pRationale;
			this.confidence = pConfidence;
		}
	}

	/** A proposed {@code TransferFeed} row (origin {@code LLM}) plus its {@link CreatedFeedRule}s. */
	public static final class CreatedFeed
	{
		/** {@code "<entityName>|<sourceSystem>.<table>"} - this feed's own address, also how a LATER feed of this run may name it as its {@code parentFeed}. */
		public final String feedKey;
		public final String entityName;
		public final String landingTableKey;
		/** {@link #feedKey} of the parent feed WHEN IT WAS CREATED IN THIS SAME RUN, else null - see {@link #parentFeedId}. */
		public final String parentFeedKey;
		/**
		 * Id of the pre-existing {@code TransferFeed} row this feed is a child of, WHEN THE PARENT CAME FROM AN
		 * EARLIER RUN (docs/TARGET-MAPPING.md, "Relance de la passe LLM") - else null. At most one of {@link
		 * #parentFeedKey}/{@link #parentFeedId} is ever set; both null means a root feed. Kept apart rather than
		 * folded into one field because the writer resolves them completely differently: {@link #parentFeedKey}
		 * is looked up in the id map it builds as it writes THIS run's own feeds, {@link #parentFeedId} is
		 * already a real, persisted primary key and needs no lookup at all.
		 */
		public final String parentFeedId;
		public final List<CreatedFeedRule> rules;
		/** Null/blank means the default of docs/TARGET-MAPPING.md sec 2.1/3.3 (the loader's own concern, not this pass's). */
		public final String sourceIdPipeline;
		/** {@code TransferFeed.variant}: the role of this child when one row feeds several children of the same entity; null otherwise. */
		public final String variant;
		public final int confidence;
		public final String rationale;

		CreatedFeed(final String pFeedKey, final String pEntityName, final String pLandingTableKey, final String pParentFeedKey,
			final String pParentFeedId, final List<CreatedFeedRule> pRules, final String pSourceIdPipeline, final int pConfidence, final String pRationale,
			final String pVariant)
		{
			this.variant = pVariant;
			this.feedKey = pFeedKey;
			this.entityName = pEntityName;
			this.landingTableKey = pLandingTableKey;
			this.parentFeedKey = pParentFeedKey;
			this.parentFeedId = pParentFeedId;
			this.rules = pRules == null ? new ArrayList<CreatedFeedRule>() : pRules;
			this.sourceIdPipeline = pSourceIdPipeline;
			this.confidence = pConfidence;
			this.rationale = pRationale;
		}
	}

	/**
	 * A proposed {@code CHILD_KEYS} attribute of a parent entity (lot M8b, 2026-10-04) - what feeds one {@code StagingAttribute} row with {@code
	 * derivation = CHILD_KEYS}, origin {@code LLM}, status {@code PROPOSED}. The parent may be an entity of this run or one that already existed.
	 */
	public static final class CreatedChildKeys
	{
		public final String parentEntity;
		public final String name;
		public final String label;
		public final String childEntity;
		public final List<String> fields;
		/** {@code StagingAttribute.derivationArgument}, see {@link ChildKeysProposalRule.Proposal#argument}. */
		public final String argument;
		public final int confidence;
		public final String rationale;

		CreatedChildKeys(final String pParentEntity, final String pName, final String pLabel, final String pChildEntity, final List<String> pFields,
			final String pArgument, final int pConfidence, final String pRationale)
		{
			this.parentEntity = pParentEntity;
			this.name = pName;
			this.label = pLabel;
			this.childEntity = pChildEntity;
			this.fields = pFields;
			this.argument = pArgument;
			this.confidence = pConfidence;
			this.rationale = pRationale;
		}

		/** {@code StagingAttribute.maxLength} of the attribute - the text length the derivation documents ({@link ChildKeysDerivation#DEFAULT_MAX_LENGTH}). */
		public int maxLength()
		{
			return ChildKeysDerivation.DEFAULT_MAX_LENGTH;
		}
	}

	/** One {@code StagingEntity} row this pass already wrote in an earlier run (origin {@code LLM}, status {@code PROPOSED}), read back so a re-run is idempotent. */
	public static final class ExistingTargetEntity
	{
		public final String id;
		public final String name;
		/** {@code PROPOSED} / {@code ACCEPTED} / {@code REJECTED} / {@code APPLIED}. */
		public final String status;
		public final List<String> attributeNames;
		/** Null for a top-level entity - resolved by the caller from whichever attribute of this entity carries {@code StagingAttribute.referenceEntity}. */
		public final String parentEntity;
		/** Null unless {@link #parentEntity} is set. */
		public final String parentLinkAttribute;
		/**
		 * Every {@code TransferFeed} row this entity already has, from an earlier run - what makes {@link
		 * Session#proposeFeed} idempotent across separate runs the same way {@link #attributeNames} already made
		 * {@link Session#proposeTargetEntity} idempotent (docs/TARGET-MAPPING.md, "Relance de la passe LLM",
		 * measured 2026-09-23 on project A: two identical root feeds were recreated on a second run because
		 * nothing here told the session they already existed).
		 */
		public final List<ExistingFeed> feeds;
		/**
		 * Lot M8b (2026-10-04): the names of the child entities this entity already has a {@code CHILD_KEYS} attribute for (read off its
		 * {@code StagingAttribute} rows, any status but {@code REJECTED}) - what makes {@code propose_child_keys} idempotent across runs.
		 */
		public final List<String> childKeysChildren;

		public ExistingTargetEntity(final String pId, final String pName, final String pStatus, final List<String> pAttributeNames,
			final String pParentEntity, final String pParentLinkAttribute)
		{
			this(pId, pName, pStatus, pAttributeNames, pParentEntity, pParentLinkAttribute, null);
		}

		public ExistingTargetEntity(final String pId, final String pName, final String pStatus, final List<String> pAttributeNames,
			final String pParentEntity, final String pParentLinkAttribute, final List<ExistingFeed> pFeeds)
		{
			this(pId, pName, pStatus, pAttributeNames, pParentEntity, pParentLinkAttribute, pFeeds, null);
		}

		public ExistingTargetEntity(final String pId, final String pName, final String pStatus, final List<String> pAttributeNames,
			final String pParentEntity, final String pParentLinkAttribute, final List<ExistingFeed> pFeeds, final List<String> pChildKeysChildren)
		{
			this.childKeysChildren = pChildKeysChildren == null ? new ArrayList<String>() : pChildKeysChildren;
			this.id = pId;
			this.name = pName;
			this.status = pStatus;
			this.attributeNames = pAttributeNames == null ? new ArrayList<String>() : pAttributeNames;
			this.parentEntity = pParentEntity;
			this.parentLinkAttribute = pParentLinkAttribute;
			this.feeds = pFeeds == null ? new ArrayList<ExistingFeed>() : pFeeds;
		}
	}

	/**
	 * One {@code TransferFeed} row an earlier run of this pass (or the rule pass, for the technical-identity
	 * root feed of a rule-proposed entity - never relevant here, since {@link ExistingTargetEntity} only ever
	 * holds {@code origin=LLM} entities) already wrote, read back for {@link Session#proposeFeed}'s own
	 * cross-run idempotence. {@link #landingTableKey} is exactly the {@code "<sourceSystem>.<table>"} string
	 * the session's own catalogue keys a landing table with - never a raw EBX id - so it can be compared
	 * directly against a {@code propose_feed} call's {@code landingTable} argument.
	 */
	public static final class ExistingFeed
	{
		public final String id;
		public final String landingTableKey;
		/** Id of the parent {@code TransferFeed} row, or null for a root feed. */
		public final String parentFeedId;
		/** {@code PROPOSED} / {@code ACCEPTED} / {@code REJECTED} / {@code APPLIED}. */
		public final String status;
		/** {@code TransferFeed.variant}: null for the only child of its kind (and every root feed). */
		public final String variant;
		/**
		 * Field names of the landing columns its proposed or accepted mappings read (a {@code constant(...)} rule excluded),
		 * for {@link Session#unreadColumns(boolean)}; null when unknown - the table is then never reported as having unread
		 * columns, rather than reported wrongly.
		 */
		public final List<String> readColumns;

		public ExistingFeed(final String pId, final String pLandingTableKey, final String pParentFeedId, final String pStatus)
		{
			this(pId, pLandingTableKey, pParentFeedId, pStatus, null);
		}

		public ExistingFeed(final String pId, final String pLandingTableKey, final String pParentFeedId, final String pStatus, final String pVariant)
		{
			this(pId, pLandingTableKey, pParentFeedId, pStatus, pVariant, null);
		}

		public ExistingFeed(final String pId, final String pLandingTableKey, final String pParentFeedId, final String pStatus, final String pVariant,
			final List<String> pReadColumns)
		{
			this.id = pId;
			this.landingTableKey = pLandingTableKey;
			this.parentFeedId = pParentFeedId;
			this.status = pStatus;
			this.variant = pVariant == null || pVariant.trim().isEmpty() ? null : pVariant.trim();
			this.readColumns = pReadColumns;
		}
	}

	// ----------------------------------------------------------------------------------------------------- session

	/**
	 * Backs the four local tools for one agent run: validates every {@code propose_target_entity}/{@code
	 * propose_feed} call against the project's real landing inventory (given at construction, read-only) and
	 * against what earlier runs already proposed and a reviewer already decided (also read-only), and against
	 * what has already been proposed earlier in the SAME run (built up as calls come in); accumulates {@link
	 * CreatedEntity}/{@link CreatedFeed} value objects and {@link #notes()} - never touching EBX. {@link
	 * #finalizeEntities()} must be called once, after the run, before {@link #createdEntities()}/{@link
	 * #createdFeeds()} are read for writing. One {@link Session} per run; not thread-safe (an {@code
	 * AgentLoop} run is already single-threaded).
	 */
	public static final class Session
	{
		private static final String TOOL_ENTITY = "propose_target_entity";
		private static final String TOOL_FEED = "propose_feed";
		private static final String TOOL_DESCRIBE = "describe_landing_table";
		private static final String TOOL_CHILD_KEYS = "propose_child_keys";
		/** A call refused this many times in a row (same tool, same repeated argument) ends the run: see {@link #refuse}. */
		private static final int REFUSAL_STOP_THRESHOLD = 3;
		private static final String STOPPED_MESSAGE = "The run is over: no more tool calls will be executed. Answer now with your final summary of what you proposed.";

		private final Map<String, StagingRules.LandingTableInfo> tablesByKey = new LinkedHashMap<String, StagingRules.LandingTableInfo>();
		private final Map<String, RuleEntityInfo> ruleEntitiesByName = new LinkedHashMap<String, RuleEntityInfo>();
		private final Map<String, String> ruleEntityNameByLandingTableKey = new LinkedHashMap<String, String>();
		private final Map<String, ExistingTargetEntity> priorByName = new LinkedHashMap<String, ExistingTargetEntity>();
		/** {@code "<entityName>|<landingTableKey>"} -> the prior run's own feed for that pair - see {@link #proposeFeed}'s cross-run idempotence. */
		private final Map<String, ExistingFeed> priorFeedsByKey = new LinkedHashMap<String, ExistingFeed>();
		private final boolean compact;

		private final Map<String, CreatedEntity> created = new LinkedHashMap<String, CreatedEntity>();
		private final Map<String, CreatedFeed> feedsByFeedKey = new LinkedHashMap<String, CreatedFeed>();
		private final List<CreatedFeed> feedsInOrder = new ArrayList<CreatedFeed>();
		/** Lot M8b: the {@code CHILD_KEYS} attributes proposed this run, in call order. */
		private final List<CreatedChildKeys> childKeys = new ArrayList<CreatedChildKeys>();
		private final Set<String> describedTables = new LinkedHashSet<String>();
		private final Map<String, Integer> refusalCounts = new LinkedHashMap<String, Integer>();
		private final List<String> notes = new ArrayList<String>();
		/** See {@link RuleProbe}; null = no check. */
		private RuleProbe ruleProbe;
		/** {@code "<entity>|<attribute>"} -> source system -> the dominant output shape of the rule feeding it from that system (harmony check, rule (6)). */
		private final Map<String, Map<String, String>> outputShapeBySystem = new LinkedHashMap<String, Map<String, String>>();
		/** {@code "<landingTableKey>|<column>"} already refused ONCE by {@link StagingLlmPass#orderRefusal}: from then on a rule on that column is kept and the decision recorded. */
		private final Set<String> orderRefused = new LinkedHashSet<String>();
		/** Decisions on a two-layout column taken by the feed being proposed, moved to {@link #orderDecisions} when it is recorded. */
		private final List<String> pendingOrderDecisions = new ArrayList<String>();
		/** See {@link #orderDecisions()}. */
		private final List<String> orderDecisions = new ArrayList<String>();
		/**
		 * Landing column -> {@code {attribute, pipeline, ProbeResult}} of the FIRST probed rule of the propose_feed call
		 * being handled that read it - what a later rule of the same call on the same column is compared with
		 * ({@link StagingLlmPass#identicalRefusal}). Cleared at every call: the values stay in Java.
		 */
		private final Map<String, Object[]> probedInCall = new LinkedHashMap<String, Object[]>();
		/** {@code "<feedKey>|<column>|<attribute>"} already refused ONCE by {@link StagingLlmPass#identicalRefusal}: sent again, the rule is kept and the decision recorded. */
		private final Set<String> identicalRefused = new LinkedHashSet<String>();
		/** Decisions of the feed being proposed on two rules of one column giving one output, moved to {@link #identicalOutputDecisions} when it is recorded. */
		private final List<String> pendingIdenticalDecisions = new ArrayList<String>();
		/** See {@link #identicalOutputDecisions()}. */
		private final List<String> identicalOutputDecisions = new ArrayList<String>();
		/** Value-conversion decisions of the feed being proposed (a rule corrected or flagged), moved to {@link #conversionDecisions} when it is recorded. */
		private final List<String> pendingConversionDecisions = new ArrayList<String>();
		/** See {@link #conversionDecisions()}. */
		private final List<String> conversionDecisions = new ArrayList<String>();
		private List<String> entitiesWithoutFeed = new ArrayList<String>();
		private List<String> tablesWithoutFeedOrNote = new ArrayList<String>();
		private boolean finalized;
		private boolean modellingReviewed;
		private boolean stopped;
		private String stopReason;

		/**
		 * @param pProject the project's landing inventory and rule-proposed entities - the only tables/columns/
		 * entities a call may ever reference.
		 * @param pPriorEntities every {@code origin=LLM} {@code StagingEntity} the project already had BEFORE
		 * this run, still {@code PROPOSED} or already decided - what makes {@code propose_target_entity}/{@code
		 * propose_feed} idempotent across separate runs, and what stops a reviewer's decision from being
		 * resurrected.
		 */
		public Session(final ProjectInfo pProject, final List<ExistingTargetEntity> pPriorEntities)
		{
			for (final StagingRules.LandingTableInfo table : pProject.tables)
			{
				this.tablesByKey.put(StagingLlmPass.landingTableKey(table.sourceSystem, table.name), table);
			}
			for (final RuleEntityInfo ruleEntity : pProject.ruleEntities)
			{
				this.ruleEntitiesByName.put(ruleEntity.name, ruleEntity);
				this.ruleEntityNameByLandingTableKey.put(ruleEntity.landingTableKey(), ruleEntity.name);
			}
			if (pPriorEntities != null)
			{
				for (final ExistingTargetEntity existing : pPriorEntities)
				{
					this.priorByName.put(existing.name, existing);
					for (final ExistingFeed feed : existing.feeds)
					{
						this.priorFeedsByKey.put(StagingLlmPass.feedKey(existing.name, feed.landingTableKey, feed.variant), feed);
					}
				}
			}
			this.compact = StagingLlmPass.isCompact(pProject);
		}

		/** Wires the real-value check of every proposed rule - see {@link RuleProbe}. */
		public void setRuleProbe(final RuleProbe pProbe)
		{
			this.ruleProbe = pProbe;
		}

		/** Every target entity genuinely created or fed in this run - what {@code StagingLlmSuggester} writes once the run is over. Call {@link #finalizeEntities()} first. */
		public List<CreatedEntity> createdEntities()
		{
			return new ArrayList<CreatedEntity>(this.created.values());
		}

		/**
		 * Fix/staging-proposal-quality (2026-10-17, item 2): runs {@link ProposalChecks} on this run's proposals, in place, and answers the findings.
		 * Call it once, after the run and before {@link #createdEntities()} / {@link #createdFeeds()} are read for writing; a second call finds
		 * nothing new (the sentences are already on the objects - the checks look at the structure, which has not changed, and would prefix again,
		 * so the call is refused after the first).
		 */
		public List<ProposalChecks.Finding> reviewModelling(final ProposalChecks.Words pWords)
		{
			if (this.modellingReviewed)
			{
				return new ArrayList<ProposalChecks.Finding>();
			}
			this.modellingReviewed = true;
			final List<CreatedEntity> entities = new ArrayList<CreatedEntity>(this.created.values());
			final List<ProposalChecks.Finding> findings = ProposalChecks.review(entities, this.feedsInOrder, this.tablesByKey, pWords, this.notes, this.columnsLeftBehind(),
				this.identityGaps(), this.entityCoverage(), this.dateSpanDisjoints());
			// lot M7: an entity the checks flagged (no feed) is a new value object - put it back under its name
			for (final CreatedEntity entity : entities)
			{
				this.created.put(entity.name, entity);
			}
			for (final CreatedFeed feed : this.feedsInOrder)
			{
				this.feedsByFeedKey.put(feed.feedKey, feed);
			}
			return findings;
		}

		/** Lot M8b: every {@code CHILD_KEYS} attribute proposed this run, in call order. */
		public List<CreatedChildKeys> createdChildKeys()
		{
			return new ArrayList<CreatedChildKeys>(this.childKeys);
		}

		/** Every feed proposed this run, in call order (a child always after the parent it names - {@link #proposeFeed} refuses an unknown {@code parentFeed}). */
		public List<CreatedFeed> createdFeeds()
		{
			return new ArrayList<CreatedFeed>(this.feedsInOrder);
		}

		public List<String> notes()
		{
			return this.notes;
		}

		/**
		 * What the run decided on every column whose values follow two case layouts ({@link TokenCase#twoLayouts}) - the
		 * run outcome carries them (PersVol7, 2026-09-28): {@code order-aware rule} (a different token position per
		 * layout), {@code kept whole} (no extraction: the values are compared whole), or {@code order-blind rule kept
		 * after one refusal} (the model insisted; the attributes of one layout may be crossed).
		 */
		public List<String> orderDecisions()
		{
			return this.orderDecisions;
		}

		/**
		 * PersVol8 (2026-09-28): every rule kept, after its one refusal, although it gives another attribute of the same
		 * feed the same output as another rule on the same column - {@code Personne.lastName <- ECOM.CUSTOMER.FULL_NAME
		 * regex(...): same output as firstName in 48 of 50 values, kept after one refusal}. For the outcome.
		 */
		public List<String> identicalOutputDecisions()
		{
			return this.identicalOutputDecisions;
		}

		/**
		 * Staging feed-conversion lot E (2026-10-17): every rule of a recorded feed whose conversion to the typed target the value check
		 * CORRECTED (a pipeline replaced by the one the column's measured format gives) or FLAGGED (nothing converts every sampled value:
		 * recorded for a person, confidence lowered) - {@code Personne.birthDate <- LOYALTY.MEMBERS.date_naissance: corrected ...}. Pipeline
		 * texts and counts, never a value. For the outcome.
		 */
		public List<String> conversionDecisions()
		{
			return this.conversionDecisions;
		}

		/** Whether a repeated refusal forced this run to a stop; {@code createdEntities()}/{@code createdFeeds()}/{@code notes()} still hold whatever was validly proposed before that. */
		public boolean stoppedEarly()
		{
			return this.stopped;
		}

		public String stopReason()
		{
			return this.stopReason;
		}

		/** Target entities with zero feed (root or child) anywhere in this run - a gap for a person to see, never a reason to drop anything. Valid only after {@link #finalizeEntities()}. */
		public List<String> entitiesWithoutFeed()
		{
			return this.entitiesWithoutFeed;
		}

		/** Landing tables of the project read by no feed of this run and named in no note - same "never blocking" posture. Valid only after {@link #finalizeEntities()}. */
		public List<String> tablesWithoutFeedOrNote()
		{
			return this.tablesWithoutFeedOrNote;
		}

		/**
		 * Must be called exactly once, after the run, before {@link #createdEntities()}/{@link #createdFeeds()}
		 * are read for writing (docs/TARGET-MAPPING.md sec 4.2, "Session.finalizeEntities()"). Computes, for
		 * every created entity, {@link CreatedEntity#memberEntities} - the rule-proposed entities whose landing
		 * table is read by at least one of THIS entity's own feeds - and the two gap lists ({@link
		 * #entitiesWithoutFeed()}/{@link #tablesWithoutFeedOrNote()}). Never drops anything: unlike the retired
		 * canonical-entity pass, there is no "padding trick" to defend against here (a feed can only be
		 * proposed against a real landing table/column, so a fabricated entity has no way to look genuine
		 * without doing real, checkable work) - every gap is reported for a person to decide, exactly as the
		 * design asks ("never blocking").
		 */
		public void finalizeEntities()
		{
			if (this.finalized)
			{
				return;
			}
			this.finalized = true;
			final Set<String> tablesRead = new LinkedHashSet<String>();
			// a table an EARLIER run already reads (any entity, not only ones touched this run) is not a gap -
			// docs/TARGET-MAPPING.md, "Relance de la passe LLM": without this, a genuinely empty re-run (nothing
			// left to propose, see the class Javadoc) would report every landing table as "without a feed or
			// note", which is exactly backwards - they are all already fed, just not by a feed created THIS run.
			for (final ExistingTargetEntity prior : this.priorByName.values())
			{
				for (final ExistingFeed feed : prior.feeds)
				{
					tablesRead.add(feed.landingTableKey);
				}
			}
			for (final CreatedEntity entity : this.created.values())
			{
				final Set<String> members = new LinkedHashSet<String>();
				boolean hasFeed = false;
				for (final CreatedFeed feed : this.feedsInOrder)
				{
					if (!feed.entityName.equals(entity.name))
					{
						continue;
					}
					hasFeed = true;
					tablesRead.add(feed.landingTableKey);
					final String ruleEntityName = this.ruleEntityNameByLandingTableKey.get(feed.landingTableKey);
					if (ruleEntityName != null)
					{
						members.add(ruleEntityName);
					}
				}
				entity.memberEntities.addAll(members);
				if (!hasFeed && entity.existingId != null)
				{
					// adopted from an earlier run (Session.resolveEntityForFeed, or the idempotent-adoption branch
					// of proposeTargetEntity) and fed nothing NEW this run - still not a gap when it already had a
					// feed before this run started (the "relance à vide" case: everything already covered).
					final ExistingTargetEntity prior = this.priorByName.get(entity.name);
					hasFeed = prior != null && !prior.feeds.isEmpty();
				}
				if (!hasFeed)
				{
					this.entitiesWithoutFeed.add(entity.name);
				}
			}
			this.tablesWithoutFeedOrNote.addAll(this.uncoveredTables(tablesRead));
		}

		/**
		 * The landing tables no feed of a TARGET entity reads (this run's or an earlier run's) and no note mentions -
		 * what {@link #finalizeEntities()} reports as {@link #tablesWithoutFeedOrNote()}, computed here without
		 * finalizing so {@code StagingLlmSuggester} can give the model one more turn on them before the run is written.
		 * PersVol volume test, 2026-09-24: the model ended without a word on ECOM.CUSTOMER (a full name in one column,
		 * one address line), the gap was only logged, and 799 customers would never have met their CRM/ERP twins.
		 */
		/** What this run proposed so far, one line per entity (attributes with types) and per feed (its address) - the context a follow-up turn starts from. */
		public String summaryOfThisRun()
		{
			final StringBuilder out = new StringBuilder();
			for (final CreatedEntity entity : this.created.values())
			{
				out.append("- entity ").append(entity.name);
				if (entity.parentEntity != null)
				{
					out.append(" (child of ").append(entity.parentEntity).append(" via ").append(entity.parentLinkAttribute).append(')');
				}
				out.append(':');
				for (final CreatedAttribute attribute : entity.attributes)
				{
					out.append(' ').append(attribute.name).append(' ').append(attribute.dataType).append(',');
				}
				out.setLength(out.length() - 1);
				out.append('\n');
			}
			for (final CreatedFeed feed : this.feedsInOrder)
			{
				out.append("- feed ").append(feed.feedKey);
				if (feed.parentFeedKey != null)
				{
					out.append(" (child of ").append(feed.parentFeedKey).append(')');
				}
				out.append('\n');
			}
			for (final CreatedChildKeys keys : this.childKeys)
			{
				out.append("- key attribute ").append(keys.parentEntity).append('.').append(keys.name).append(" (keys of the ").append(keys.childEntity)
					.append(" records)\n");
			}
			return out.toString();
		}

		/**
		 * The landing columns read, per landing table, by a LIVE entity: the feeds of this run, and the non-rejected feeds of an earlier run's entity that is neither
		 * a rule entity, nor REJECTED, nor SUPERSEDED (lot M15: a column mapped only to a superseded entity counts as read by nobody - the load never reads it).
		 * A {@code constant(...)} rule reads nothing; a column named by a pipeline counts as read. A table read by an earlier-run feed whose columns are unknown is
		 * left out of the map (nothing can be judged on it).
		 */
		private Map<String, Set<String>> liveReadByTable()
		{
			final Map<String, Set<String>> readByTable = new LinkedHashMap<String, Set<String>>();
			final Set<String> unknownTables = new LinkedHashSet<String>();
			for (final ExistingTargetEntity prior : this.priorByName.values())
			{
				if (!this.readsForUnreadColumns(prior))
				{
					continue;
				}
				for (final ExistingFeed feed : prior.feeds)
				{
					if ("REJECTED".equals(feed.status))
					{
						continue;
					}
					if (feed.readColumns == null)
					{
						unknownTables.add(feed.landingTableKey);
						continue;
					}
					Session.readSetOf(readByTable, feed.landingTableKey).addAll(feed.readColumns);
				}
			}
			for (final CreatedFeed feed : this.feedsInOrder)
			{
				final Set<String> read = Session.readSetOf(readByTable, feed.landingTableKey);
				final StagingRules.LandingTableInfo table = this.tablesByKey.get(feed.landingTableKey);
				for (final CreatedFeedRule rule : feed.rules)
				{
					final String lower = rule.pipeline == null ? "" : rule.pipeline.trim().toLowerCase(Locale.ROOT);
					if (lower.startsWith("constant("))
					{
						continue;
					}
					read.add(rule.landingColumn);
					Session.addReferencedColumns(read, rule.pipeline, table);
				}
				Session.addReferencedColumns(read, feed.sourceIdPipeline, table);
			}
			for (final String tableKey : unknownTables)
			{
				readByTable.remove(tableKey);
			}
			return readByTable;
		}

		/**
		 * Filled columns of the landing tables some feed reads (this run's, or a non-rejected feed of an earlier run) that no
		 * rule reads - those a note names ({@code pExplained} true: left out on purpose) or those none names (false: still
		 * to feed or explain). A {@code constant(...)} rule reads nothing; a column named by a pipeline (a concat, a child's
		 * sourceIdPipeline) counts as read. A table read by an earlier-run feed whose columns are unknown is skipped.
		 */
		public Map<String, List<StagingRules.LandingColumnInfo>> unreadColumns(final boolean pExplained)
		{
			final Map<String, Set<String>> readByTable = this.liveReadByTable();
			final Map<String, List<StagingRules.LandingColumnInfo>> out = new LinkedHashMap<String, List<StagingRules.LandingColumnInfo>>();
			for (final Map.Entry<String, List<StagingRules.LandingColumnInfo>> entry : StagingLlmPass.unreadFilledColumns(this.tablesByKey, readByTable)
				.entrySet())
			{
				final List<StagingRules.LandingColumnInfo> kept = new ArrayList<StagingRules.LandingColumnInfo>();
				for (final StagingRules.LandingColumnInfo column : entry.getValue())
				{
					if (StagingLlmPass.explainedByNote(entry.getKey(), column.fieldName, this.notes) == pExplained)
					{
						kept.add(column);
					}
				}
				if (!kept.isEmpty())
				{
					out.put(entry.getKey(), kept);
				}
			}
			return out;
		}

		/**
		 * Whether an earlier run's entity's feeds count as readers for {@link #unreadColumns}: never a rule-pass entity (one
		 * per landing table, its feed reads every column - it would hide every forgotten column of its table), never one a
		 * person REJECTED or a newer proposal SUPERSEDED. The caller only hands origin=LLM entities to this session; this
		 * guard keeps it true whatever a future caller passes.
		 */
		private boolean readsForUnreadColumns(final ExistingTargetEntity pPrior)
		{
			return !this.ruleEntitiesByName.containsKey(pPrior.name) && !"REJECTED".equals(pPrior.status) && !"SUPERSEDED".equals(pPrior.status);
		}

		/**
		 * Lot M15 (2026-10-18): the filled landing columns that fed a per-table (rule) entity this run's canonical entity takes over and that NO live entity reads
		 * - a column mapped only to a SUPERSEDED entity is read by nobody (the load never reads a superseded entity). Measured on E2EMASTER20261018090711: the
		 * unification into {@code Client} superseded {@code MagasinsVentes...}, and STORES.TELEPHONE (234 of 234 tickets) was mapped only to that one: {@code
		 * Client.mobile} stayed empty for every STORES row and the matching plan had no phone key for the source.
		 * <p>
		 * For each landing table that a NEW or adopted target entity reads through a ROOT feed (the first entity in creation order when several do) and that has a
		 * rule entity (the entity it replaces): the columns that rule entity's live mappings read ({@link RuleEntityInfo#readColumns}; none when unknown), filled in at least one row, that no live feed reads ({@link #liveReadByTable()}) and that no note names ({@link
		 * StagingLlmPass#explainedByNote}). Domain-free: no column or entity name is read for its meaning. Empty when nothing is left behind.
		 */
		public List<LeftBehind> columnsLeftBehind()
		{
			final List<LeftBehind> out = new ArrayList<LeftBehind>();
			final Map<String, Set<String>> readByTable = this.liveReadByTable();
			final Set<String> done = new LinkedHashSet<String>();
			for (final CreatedEntity canonical : this.created.values())
			{
				for (final CreatedFeed feed : this.feedsInOrder)
				{
					if (!feed.entityName.equals(canonical.name) || feed.parentFeedKey != null || feed.parentFeedId != null || !done.add(feed.landingTableKey))
					{
						continue;
					}
					final String memberName = this.ruleEntityNameByLandingTableKey.get(feed.landingTableKey);
					final StagingRules.LandingTableInfo table = this.tablesByKey.get(feed.landingTableKey);
					if (memberName == null || table == null || !readByTable.containsKey(feed.landingTableKey))
					{
						continue;
					}
					final RuleEntityInfo member = this.ruleEntitiesByName.get(memberName);
					final Map<String, Set<String>> one = new LinkedHashMap<String, Set<String>>();
					one.put(feed.landingTableKey, readByTable.get(feed.landingTableKey));
					final Map<String, StagingRules.LandingTableInfo> oneTable = new LinkedHashMap<String, StagingRules.LandingTableInfo>();
					oneTable.put(feed.landingTableKey, table);
					final List<StagingRules.LandingColumnInfo> kept = new ArrayList<StagingRules.LandingColumnInfo>();
					final List<StagingRules.LandingColumnInfo> unread = StagingLlmPass.unreadFilledColumns(oneTable, one).get(feed.landingTableKey);
					if (unread != null)
					{
						for (final StagingRules.LandingColumnInfo column : unread)
						{
							if (member.readColumns != null && member.readColumns.contains(column.fieldName)
								&& !StagingLlmPass.explainedByNote(feed.landingTableKey, column.fieldName, this.notes))
							{
								kept.add(column);
							}
						}
					}
					if (!kept.isEmpty())
					{
						out.add(new LeftBehind(canonical.name, memberName, feed.landingTableKey, table.sourceSystem, kept));
					}
				}
			}
			return out;
		}

		/**
		 * Lot S10 (2026-10-18): the landing tables whose rows carry identity attributes (catalog identity tags) and feed no entity that holds the identity of the other
		 * tables - see {@link IdentityCoverage#gaps}. The reads are this run's feeds and the live feeds of earlier runs; a {@code constant(...)} rule reads nothing. Computed
		 * without finalizing, so the follow-up turn can ask about them before the run is written, and the record step can flag what is still open.
		 */
		public List<IdentityCoverage.Gap> identityGaps()
		{
			return IdentityCoverage.gaps(this.tablesByKey, this.identityReads());
		}

		/**
		 * Lot F2 (2026-10-19): for every target entity the proposal feeds whose feeds read identity columns, how many of the landing tables carrying its kind of columns it is fed by
		 * and how many rows it would receive - {@link IdentityCoverage#entityCoverage}. Counts from the feeds proposed (this run's and the live ones of earlier runs) and the landing
		 * tables' rows; computed without finalizing, like {@link #identityGaps()}.
		 */
		public List<IdentityCoverage.EntityCoverage> entityCoverage()
		{
			return IdentityCoverage.entityCoverage(this.tablesByKey, this.identityReads());
		}

		/**
		 * Lot F2b (2026-10-19): the date attributes ({@code DATE}/{@code TIMESTAMP}) of the entities of this run that are fed by several columns whose spans ({@link DateSpans}) fall in
		 * groups that do not overlap. Rules that read no column ({@code constant(...)}) are left out; a string attribute is never looked at.
		 */
		public List<DateSpans.Disjoint> dateSpanDisjoints()
		{
			final Set<String> dateAttributes = new LinkedHashSet<String>();
			for (final CreatedEntity entity : this.created.values())
			{
				for (final CreatedAttribute attribute : entity.attributes)
				{
					if ("DATE".equals(attribute.dataType) || "TIMESTAMP".equals(attribute.dataType))
					{
						dateAttributes.add(entity.name + "|" + attribute.name);
					}
				}
			}
			final List<DateSpans.Rule> rules = new ArrayList<DateSpans.Rule>();
			for (final CreatedFeed feed : this.feedsInOrder)
			{
				for (final CreatedFeedRule rule : feed.rules)
				{
					final String lower = rule.pipeline == null ? "" : rule.pipeline.trim().toLowerCase(Locale.ROOT);
					if (!lower.startsWith("constant("))
					{
						rules.add(new DateSpans.Rule(feed.entityName, rule.attribute, feed.landingTableKey, rule.landingColumn));
					}
				}
			}
			return DateSpans.disjoint(dateAttributes, rules, this.tablesByKey);
		}

		/** The feeds the identity checks read: the live feeds of earlier runs (columns as stored) and this run's (a {@code constant(...)} rule reads nothing). */
		private List<IdentityCoverage.FeedRead> identityReads()
		{
			final List<IdentityCoverage.FeedRead> reads = new ArrayList<IdentityCoverage.FeedRead>();
			for (final ExistingTargetEntity prior : this.priorByName.values())
			{
				if (!this.readsForUnreadColumns(prior))
				{
					continue;
				}
				for (final ExistingFeed feed : prior.feeds)
				{
					if (!"REJECTED".equals(feed.status))
					{
						reads.add(new IdentityCoverage.FeedRead(prior.name, feed.landingTableKey, feed.parentFeedId == null,
							feed.readColumns == null ? null : new LinkedHashSet<String>(feed.readColumns)));
					}
				}
			}
			for (final CreatedFeed feed : this.feedsInOrder)
			{
				final Set<String> read = new LinkedHashSet<String>();
				final StagingRules.LandingTableInfo table = this.tablesByKey.get(feed.landingTableKey);
				for (final CreatedFeedRule rule : feed.rules)
				{
					final String lower = rule.pipeline == null ? "" : rule.pipeline.trim().toLowerCase(Locale.ROOT);
					if (lower.startsWith("constant("))
					{
						continue;
					}
					read.add(rule.landingColumn);
					Session.addReferencedColumns(read, rule.pipeline, table);
				}
				Session.addReferencedColumns(read, feed.sourceIdPipeline, table);
				reads.add(new IdentityCoverage.FeedRead(feed.entityName, feed.landingTableKey, feed.parentFeedKey == null && feed.parentFeedId == null, read));
			}
			return reads;
		}

		/** One line per feed reading this landing table (this run's and an earlier run's): its address, variant and the columns it reads. */
		List<String> feedsReading(final String pLandingTableKey)
		{
			final List<String> out = new ArrayList<String>();
			for (final ExistingTargetEntity prior : this.priorByName.values())
			{
				for (final ExistingFeed feed : prior.feeds)
				{
					if (feed.landingTableKey.equals(pLandingTableKey) && !"REJECTED".equals(feed.status))
					{
						out.add("feed " + StagingLlmPass.feedKey(prior.name, feed.landingTableKey, feed.variant) + " (earlier run"
							+ (feed.variant == null ? "" : ", variant " + feed.variant) + ", columns "
							+ (feed.readColumns == null ? "unknown" : StagingLlmPass.join(feed.readColumns)) + ")");
					}
				}
			}
			for (final CreatedFeed feed : this.feedsInOrder)
			{
				if (!feed.landingTableKey.equals(pLandingTableKey))
				{
					continue;
				}
				final List<String> columns = new ArrayList<String>();
				for (final CreatedFeedRule rule : feed.rules)
				{
					columns.add(rule.landingColumn + (rule.pipeline == null ? "" : " via " + rule.pipeline) + " -> " + rule.attribute);
				}
				out.add("feed " + feed.feedKey + (feed.parentFeedKey == null ? "" : " (child of " + feed.parentFeedKey + ")")
					+ (feed.variant == null ? "" : ", variant " + feed.variant) + ", columns " + StagingLlmPass.join(columns));
			}
			return out;
		}

		private static Set<String> readSetOf(final Map<String, Set<String>> pReadByTable, final String pLandingTableKey)
		{
			Set<String> read = pReadByTable.get(pLandingTableKey);
			if (read == null)
			{
				read = new LinkedHashSet<String>();
				pReadByTable.put(pLandingTableKey, read);
			}
			return read;
		}

		private static void addReferencedColumns(final Set<String> pRead, final String pPipeline, final StagingRules.LandingTableInfo pTable)
		{
			if (pPipeline == null || pPipeline.trim().isEmpty() || pTable == null)
			{
				return;
			}
			for (final StagingRules.LandingColumnInfo column : pTable.columns)
			{
				if (Session.referencesColumn(pPipeline, column.fieldName))
				{
					pRead.add(column.fieldName);
				}
			}
		}

		/**
		 * Lot M7 (2026-10-18): the target entities of this run that no feed reads so far - a NEW entity with no feed, or an adopted one that had no feed
		 * before this run and got none - computed without finalizing, so {@code StagingLlmSuggester} can give the model one more turn on them (feed
		 * them, or say why) before the run is written. The same list {@link #finalizeEntities()} then reports as {@link #entitiesWithoutFeed()}.
		 */
		public List<String> entitiesNotYetFed()
		{
			final List<String> out = new ArrayList<String>();
			for (final CreatedEntity entity : this.created.values())
			{
				boolean hasFeed = false;
				for (final CreatedFeed feed : this.feedsInOrder)
				{
					if (feed.entityName.equals(entity.name))
					{
						hasFeed = true;
						break;
					}
				}
				if (!hasFeed && entity.existingId != null)
				{
					final ExistingTargetEntity prior = this.priorByName.get(entity.name);
					hasFeed = prior != null && !prior.feeds.isEmpty();
				}
				if (!hasFeed)
				{
					out.add(entity.name);
				}
			}
			return out;
		}

		public List<String> tablesNotYetCovered()
		{
			final Set<String> tablesRead = new LinkedHashSet<String>();
			for (final ExistingTargetEntity prior : this.priorByName.values())
			{
				for (final ExistingFeed feed : prior.feeds)
				{
					tablesRead.add(feed.landingTableKey);
				}
			}
			for (final CreatedFeed feed : this.feedsInOrder)
			{
				tablesRead.add(feed.landingTableKey);
			}
			return this.uncoveredTables(tablesRead);
		}

		private List<String> uncoveredTables(final Set<String> pTablesRead)
		{
			final List<String> out = new ArrayList<String>();
			for (final String tableKey : this.tablesByKey.keySet())
			{
				if (pTablesRead.contains(tableKey))
				{
					continue;
				}
				boolean explained = false;
				for (final String note : this.notes)
				{
					if (note.contains(tableKey))
					{
						explained = true;
						break;
					}
				}
				if (!explained)
				{
					out.add(tableKey);
				}
			}
			return out;
		}

		/** The four local tools, wired to this session's handlers, ready to give to {@code ToolSet.build} under a {@code "factory"} group. */
		public ToolSet.LocalGroup toolGroup()
		{
			final List<LocalTool> tools = new ArrayList<LocalTool>();
			tools.add(new LocalTool(Session.TOOL_ENTITY, "Propose one entity of the target model, with its attributes.",
				StagingLlmPass.PROPOSE_TARGET_ENTITY_SCHEMA, new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.proposeTargetEntity(pArguments);
					}
				}));
			tools.add(new LocalTool(Session.TOOL_FEED, "Propose one feed reading a landing table into a target entity's attributes.",
				StagingLlmPass.PROPOSE_FEED_SCHEMA, new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.proposeFeed(pArguments);
					}
				}));
			tools.add(new LocalTool(Session.TOOL_CHILD_KEYS, "Propose, on a parent entity, a technical matching key built from the records of one of its child entities.",
				StagingLlmPass.PROPOSE_CHILD_KEYS_SCHEMA, new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.proposeChildKeys(pArguments);
					}
				}));
			tools.add(new LocalTool(Session.TOOL_DESCRIBE, "Read-only: the exact columns of one landing table.", StagingLlmPass.DESCRIBE_LANDING_TABLE_SCHEMA,
				new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.describeLandingTable(pArguments);
					}
				}));
			tools.add(new LocalTool("note", "Record something you deliberately chose not to cover, and why.", StagingLlmPass.NOTE_SCHEMA,
				new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.note(pArguments);
					}
				}));
			return new ToolSet.LocalGroup(tools);
		}

		// ---- propose_target_entity ----

		LocalTool.Result proposeTargetEntity(final JsonNode pArguments)
		{
			if (this.stopped)
			{
				return LocalTool.Result.error(Session.STOPPED_MESSAGE);
			}
			final String name = Session.textOf(pArguments, "name");
			final String key = name == null ? "(unnamed)" : name;
			if (name == null || !StagingLlmPass.NAME_PATTERN.matcher(name).matches())
			{
				return this.refuse(Session.TOOL_ENTITY, key,
					"'name' must start with a letter and contain only letters, digits and underscore (PascalCase), e.g. Personne. Retry with a valid name.");
			}
			if (this.ruleEntitiesByName.containsKey(name))
			{
				return this.refuse(Session.TOOL_ENTITY, key,
					"'" + name + "' is already the name of a rule-proposed entity: a target entity needs its own, different name.");
			}
			final ExistingTargetEntity prior = this.priorByName.get(name);
			if (prior != null)
			{
				if (!"PROPOSED".equals(prior.status))
				{
					return this.refuse(Session.TOOL_ENTITY, key,
						"'" + name + "' was already reviewed by a person (status " + prior.status + "): do not propose it again.");
				}
				this.refusalCounts.remove(Session.TOOL_ENTITY + "|" + key);
				return LocalTool.Result.ok("Target entity '" + name + "' already exists for this project (from an earlier run) with "
					+ prior.attributeNames.size() + " attribute(s): " + StagingLlmPass.join(prior.attributeNames)
					+ ". Nothing to add here - propose_feed can still feed it if it has no feed yet.");
			}
			if (this.created.containsKey(name))
			{
				return this.refuse(Session.TOOL_ENTITY, key, "'" + name + "' was already proposed earlier in this run: propose a different, not-yet-used name.");
			}
			final JsonNode attributesNode = pArguments.path("attributes");
			if (!attributesNode.isArray() || attributesNode.size() == 0)
			{
				return this.refuse(Session.TOOL_ENTITY, key, "'attributes' must list at least one attribute. Add at least one and retry.");
			}
			final String parentEntity = Session.textOf(pArguments, "parentEntity");
			final String parentLinkAttribute = Session.textOf(pArguments, "parentLinkAttribute");
			if (parentEntity != null)
			{
				if (parentEntity.equals(name))
				{
					return this.refuse(Session.TOOL_ENTITY, key, "an entity cannot be its own parent.");
				}
				if (!this.created.containsKey(parentEntity) && !Session.isPendingPrior(this.priorByName.get(parentEntity)))
				{
					return this.refuse(Session.TOOL_ENTITY, key, "unknown parent entity '" + parentEntity
						+ "': it must be a target entity already proposed in this run, or an existing one still pending review.");
				}
				if (parentLinkAttribute == null || parentLinkAttribute.trim().isEmpty())
				{
					return this.refuse(Session.TOOL_ENTITY, key, "'parentLinkAttribute' is required when 'parentEntity' is given: name one of this "
						+ "entity's own attributes that plays the role of the reference back to '" + parentEntity + "'.");
				}
			}
			// per-field-rationale lot (2026-09-24): the ENTITY's own rationale/confidence are parsed here, ahead
			// of the attribute loop below (moved up from just after it), so each attribute can fall back to them
			// only when the model left its OWN rationale/confidence blank - never as the primary source any more.
			final String entityRationale = Session.textOf(pArguments, "rationale");
			if (entityRationale == null || entityRationale.trim().isEmpty())
			{
				return this.refuse(Session.TOOL_ENTITY, key, "'rationale' must not be empty. Give a one or two sentence rationale and retry.");
			}
			final int entityConfidence = Session.confidenceOf(pArguments);
			final Set<String> seenAttributeNames = new LinkedHashSet<String>();
			final List<CreatedAttribute> attributes = new ArrayList<CreatedAttribute>();
			boolean parentLinkAttributeFound = false;
			for (final JsonNode attributeNode : attributesNode)
			{
				final String attributeName = attributeNode.path("name").asText(null);
				if (attributeName == null || !StagingLlmPass.NAME_PATTERN.matcher(attributeName).matches())
				{
					return this.refuse(Session.TOOL_ENTITY, key, "every attribute 'name' must start with a letter and contain only letters, digits and "
						+ "underscore (camelCase), e.g. firstName. Retry with valid names.");
				}
				if (!seenAttributeNames.add(attributeName))
				{
					return this.refuse(Session.TOOL_ENTITY, key, "attribute '" + attributeName + "' is listed twice in the same call.");
				}
				final String label = attributeNode.path("label").asText(attributeName);
				final String dataType = attributeNode.path("dataType").asText(null);
				if (dataType == null || !StagingLlmPass.DATA_TYPES.contains(dataType))
				{
					return this.refuse(Session.TOOL_ENTITY, key,
						"attribute '" + attributeName + "': 'dataType' must be one of " + StagingLlmPass.DATA_TYPES + ".");
				}
				final boolean businessKey = attributeNode.path("businessKey").asBoolean(false);
				if (parentEntity != null && businessKey)
				{
					return this.refuse(Session.TOOL_ENTITY, key, "attribute '" + attributeName + "' cannot be a business key: '" + name
						+ "' is a child entity (parentEntity '" + parentEntity + "') - its identity is its parent's key plus its own role, never a "
						+ "business key of its own.");
				}
				final boolean mandatory = attributeNode.path("mandatory").asBoolean(false);
				final Integer maxLength = "STRING".equals(dataType)
					? Integer.valueOf(attributeNode.path("maxLength").isIntegralNumber() ? attributeNode.path("maxLength").asInt() : StagingLlmPass.DEFAULT_STRING_LENGTH)
					: null;
				// per-field-rationale lot (2026-09-24): this attribute's OWN rationale/confidence - fall back to the
				// entity's (parsed above) only when the model left one blank, defensive since the schema requires both.
				final String attributeRationaleRaw = attributeNode.path("rationale").asText(null);
				final String attributeRationale = attributeRationaleRaw == null || attributeRationaleRaw.trim().isEmpty() ? entityRationale.trim()
					: attributeRationaleRaw.trim();
				final int attributeConfidence = attributeNode.path("confidence").isIntegralNumber() ? Session.confidenceOf(attributeNode) : entityConfidence;
				attributes
					.add(new CreatedAttribute(attributeName, label, dataType, maxLength, mandatory, businessKey, attributeRationale, attributeConfidence));
				if (attributeName.equals(parentLinkAttribute))
				{
					parentLinkAttributeFound = true;
				}
			}
			if (parentEntity != null && !parentLinkAttributeFound)
			{
				return this.refuse(Session.TOOL_ENTITY, key,
					"'parentLinkAttribute' (" + parentLinkAttribute + ") must be one of this entity's own attributes, declared in this same call.");
			}
			// 2026-09-26 (docs/TARGET-MAPPING.md sec 14, PersVol4): a child reaches its parent through ONE attribute,
			// parentLinkAttribute, whose foreign key field (parentLinkField, e.g. personneRef) the engine fills for every
			// feed - a key-linked ROOT feed maps the parent's key onto parentLinkAttribute itself. A second attribute
			// carrying the parent's key would be filled by that root feed only and read as "unfilled" elsewhere.
			if (parentEntity != null)
			{
				for (final CreatedAttribute attribute : attributes)
				{
					if (StagingLlmPass.duplicatesParentLink(attribute.name, parentEntity, parentLinkAttribute))
					{
						return this.refuse(Session.TOOL_ENTITY, key, "attribute '" + attribute.name + "' duplicates the link to the parent '" + parentEntity
							+ "': a child entity reaches its parent through ONE attribute only, parentLinkAttribute '" + parentLinkAttribute + "' (the engine "
							+ "derives its link field '" + parentLinkAttribute + "Ref' from it and fills it for every feed). Drop '" + attribute.name
							+ "': a ROOT feed of a separate landing table maps the column holding the parent's key onto '" + parentLinkAttribute
							+ "' itself, and a CHILD feed never maps it at all.");
					}
				}
			}
			final List<String> newAttributeNames = new ArrayList<String>();
			for (final CreatedAttribute attribute : attributes)
			{
				newAttributeNames.add(attribute.name);
			}
			final String duplicateOf = this
				.findSemanticDuplicate(name, Session.attributeSignature(newAttributeNames, parentEntity == null ? null : parentLinkAttribute));
			if (duplicateOf != null)
			{
				return this.refuse(Session.TOOL_ENTITY, key, "'" + name + "' has exactly the attributes of the existing entity '" + duplicateOf
					+ "': reuse '" + duplicateOf + "' (propose_feed on it), do not propose a synonym or a translation.");
			}
			final String label = Session.textOf(pArguments, "label");
			String parentLinkField = null;
			if (parentEntity != null)
			{
				final Set<String> siblingNames = new LinkedHashSet<String>();
				siblingNames.add("sourcesystem");
				siblingNames.add("sourceid");
				for (final CreatedAttribute attribute : attributes)
				{
					siblingNames.add(attribute.name.toLowerCase(Locale.ROOT));
				}
				parentLinkField = StagingModel.foreignKeyFieldName(parentLinkAttribute, siblingNames);
			}
			final CreatedEntity entity = new CreatedEntity(name, label == null || label.trim().isEmpty() ? name : label.trim(), entityRationale.trim(), entityConfidence,
				attributes, parentEntity, parentEntity == null ? null : parentLinkAttribute, parentLinkField, null);
			this.created.put(name, entity);
			this.refusalCounts.remove(Session.TOOL_ENTITY + "|" + key);
			final List<String> attributeNames = new ArrayList<String>();
			for (final CreatedAttribute attribute : attributes)
			{
				attributeNames.add(attribute.name);
			}
			return LocalTool.Result.ok("Target entity '" + name + "' recorded with " + attributes.size() + " attribute(s): "
				+ StagingLlmPass.join(attributeNames) + (parentEntity == null ? "" : " (child of " + parentEntity + ")")
				+ ". Feed it with propose_feed - it needs at least one feed.");
		}

		private static boolean isPendingPrior(final ExistingTargetEntity pPrior)
		{
			return pPrior != null && "PROPOSED".equals(pPrior.status);
		}

		// ---- propose_child_keys (lot M8b, 2026-10-04) ----

		LocalTool.Result proposeChildKeys(final JsonNode pArguments)
		{
			if (this.stopped)
			{
				return LocalTool.Result.error(Session.STOPPED_MESSAGE);
			}
			final String entity = Session.textOf(pArguments, "entity");
			final String name = Session.textOf(pArguments, "name");
			final String childEntity = Session.textOf(pArguments, "childEntity");
			final JsonNode fieldsNode = pArguments.path("fields");
			// the circuit breaker counts the SAME call repeated: the fields are part of what makes it the same
			final String key = entity + "." + name + "<-" + childEntity + fieldsNode;
			if (!fieldsNode.isArray() || fieldsNode.size() == 0)
			{
				return this.refuse(Session.TOOL_CHILD_KEYS, key, "'fields' must list at least one attribute of the child entity (the ones that identify a child record, "
					+ "most selective first).");
			}
			final List<String> fields = new ArrayList<String>();
			for (final JsonNode field : fieldsNode)
			{
				if (!field.isTextual())
				{
					return this.refuse(Session.TOOL_CHILD_KEYS, key, "'fields' must be a list of attribute names (strings).");
				}
				fields.add(field.asText().trim());
			}
			final String rationale = Session.textOf(pArguments, "rationale");
			if (rationale == null || rationale.trim().isEmpty())
			{
				return this.refuse(Session.TOOL_CHILD_KEYS, key, "'rationale' must not be empty. Give a one or two sentence rationale and retry.");
			}
			for (final CreatedChildKeys earlier : this.childKeys)
			{
				if (earlier.parentEntity.equals(entity) && earlier.childEntity.equals(childEntity))
				{
					return this.refuse(Session.TOOL_CHILD_KEYS, key, "'" + entity + "' already has a key attribute for the records of '" + childEntity + "' in this run ('"
						+ earlier.name + "'): one per parent and child pair.");
				}
				if (earlier.parentEntity.equals(entity) && earlier.name.equalsIgnoreCase(name == null ? "" : name))
				{
					return this.refuse(Session.TOOL_CHILD_KEYS, key, "'" + name + "' was already proposed on '" + entity + "' in this run: choose another name.");
				}
			}
			final ExistingTargetEntity prior = this.priorByName.get(entity);
			if (prior != null && childEntity != null && prior.childKeysChildren.contains(childEntity))
			{
				this.refusalCounts.remove(Session.TOOL_CHILD_KEYS + "|" + key);
				return LocalTool.Result.ok("'" + entity + "' already has a key attribute for the records of '" + childEntity + "' (from an earlier run). Nothing to add.");
			}
			final ChildKeysProposalRule.Check check = ChildKeysProposalRule.check(entity, name, childEntity, fields, this.entityShapes());
			if (!check.accepted())
			{
				return this.refuse(Session.TOOL_CHILD_KEYS, key, check.refusal);
			}
			final String label = Session.textOf(pArguments, "label");
			this.childKeys.add(new CreatedChildKeys(entity, name, label == null || label.trim().isEmpty() ? name : label.trim(), childEntity, check.proposal.fields,
				check.proposal.argument, Session.confidenceOf(pArguments), rationale.trim()));
			this.refusalCounts.remove(Session.TOOL_CHILD_KEYS + "|" + key);
			return LocalTool.Result.ok("Key attribute '" + name + "' recorded on '" + entity + "': one token per '" + childEntity + "' record, built from "
				+ StagingLlmPass.join(check.proposal.fields) + " (linked to '" + entity + "' through '" + check.proposal.referenceAttribute
				+ "'). It is a technical matching key, filled by the load from the child records - no feed maps into it.");
		}

		/** The entities a {@code CHILD_KEYS} proposal may name, as {@link StagingModel.EntityInfo}: this run's, then the project's earlier ones that a person did not reject. */
		private List<StagingModel.EntityInfo> entityShapes()
		{
			final List<StagingModel.EntityInfo> out = new ArrayList<StagingModel.EntityInfo>();
			final Set<String> done = new LinkedHashSet<String>();
			for (final CreatedEntity entity : this.created.values())
			{
				if (entity.existingId != null)
				{
					continue; // an adopted prior entity: its attributes are the prior's own, below
				}
				final List<StagingModel.AttributeInfo> attributes = new ArrayList<StagingModel.AttributeInfo>();
				for (final CreatedAttribute attribute : entity.attributes)
				{
					attributes.add(new StagingModel.AttributeInfo(attribute.name, attribute.label, attribute.dataType, attribute.maxLength, attribute.mandatory, false,
						attribute.name.equals(entity.parentLinkAttribute) ? entity.parentEntity : null, "PROPOSED"));
				}
				out.add(new StagingModel.EntityInfo(entity.name, entity.label, "PROPOSED", attributes, null));
				done.add(entity.name);
			}
			for (final ExistingTargetEntity prior : this.priorByName.values())
			{
				if (done.contains(prior.name) || "REJECTED".equals(prior.status))
				{
					continue;
				}
				final List<StagingModel.AttributeInfo> attributes = new ArrayList<StagingModel.AttributeInfo>();
				for (final String attributeName : prior.attributeNames)
				{
					final boolean technical = "sourceSystem".equals(attributeName) || "sourceId".equals(attributeName);
					attributes.add(new StagingModel.AttributeInfo(attributeName, attributeName, "STRING", null, false, technical,
						attributeName.equals(prior.parentLinkAttribute) ? prior.parentEntity : null, prior.status));
				}
				out.add(new StagingModel.EntityInfo(prior.name, prior.name, prior.status, attributes, null));
			}
			return out;
		}

		// ---- semantic-duplicate guard (docs/TARGET-MAPPING.md, "Relance de la passe LLM") ----

		/**
		 * Whichever existing target entity (this run's own earlier proposals, or the project's prior ones still
		 * {@code PROPOSED}/{@code ACCEPTED}/{@code APPLIED}) is the SAME real-world concept as a new one about to
		 * be created under a DIFFERENT name - either because the two describe exactly the same business
		 * attributes, or because their names normalize to the same string ({@link #normalizedName}) - or null
		 * when there is none. Never compared against a rule-proposed entity (already refused unconditionally,
		 * by name, earlier in {@link #proposeTargetEntity}) nor against an entity a person already {@code
		 * REJECTED} or that was {@code SUPERSEDED} (a person's decision is never resurrected by this check).
		 */
		private String findSemanticDuplicate(final String pName, final Set<String> pSignature)
		{
			final String normalizedNew = Session.normalizedName(pName);
			for (final ExistingTargetEntity candidate : this.priorByName.values())
			{
				if (Session.isActiveForDuplicateCheck(candidate.status)
					&& Session.matchesDuplicate(pSignature, normalizedNew,
						Session.attributeSignature(candidate.attributeNames, candidate.parentLinkAttribute), Session.normalizedName(candidate.name)))
				{
					return candidate.name;
				}
			}
			for (final CreatedEntity candidate : this.created.values())
			{
				if (candidate.existingId != null)
				{
					// an adopted prior entity is already represented (by its real attributes) in priorByName above -
					// comparing this bare adoption stub (attributes empty, see resolveEntityForFeed) would only ever
					// match on the empty signature, a false positive this loop must not produce
					continue;
				}
				final List<String> candidateAttributeNames = new ArrayList<String>();
				for (final CreatedAttribute attribute : candidate.attributes)
				{
					candidateAttributeNames.add(attribute.name);
				}
				if (Session.matchesDuplicate(pSignature, normalizedNew,
					Session.attributeSignature(candidateAttributeNames, candidate.parentLinkAttribute), Session.normalizedName(candidate.name)))
				{
					return candidate.name;
				}
			}
			return null;
		}

		/**
		 * {@code pNewSignature}/{@code pCandidateSignature} equal (and non-empty - a child entity whose only
		 * attribute is its own parent-link, so its signature is empty once that attribute is excluded, must never
		 * match another such entity just because both are empty), OR the two normalized names equal.
		 */
		private static boolean matchesDuplicate(final Set<String> pNewSignature, final String pNormalizedNew, final Set<String> pCandidateSignature,
			final String pNormalizedCandidate)
		{
			if (!pNewSignature.isEmpty() && pNewSignature.equals(pCandidateSignature))
			{
				return true;
			}
			return pNormalizedNew.equals(pNormalizedCandidate);
		}

		private static boolean isActiveForDuplicateCheck(final String pStatus)
		{
			return "PROPOSED".equals(pStatus) || "ACCEPTED".equals(pStatus) || "APPLIED".equals(pStatus);
		}

		/**
		 * An entity's business-attribute "signature": lowercased attribute names, minus the two technical key
		 * attributes ({@code sourceSystem}/{@code sourceId}, structural on every entity) and minus whichever
		 * attribute plays the parent-link role (also structural - a translation of the same concept keeps the
		 * same business shape even though its link attribute may be named differently, e.g. {@code personne} vs
		 * {@code person}).
		 */
		private static Set<String> attributeSignature(final List<String> pAttributeNames, final String pParentLinkAttribute)
		{
			final Set<String> out = new LinkedHashSet<String>();
			for (final String attributeName : pAttributeNames)
			{
				final String lower = attributeName.toLowerCase(Locale.ROOT);
				if ("sourcesystem".equals(lower) || "sourceid".equals(lower))
				{
					continue;
				}
				if (pParentLinkAttribute != null && attributeName.equals(pParentLinkAttribute))
				{
					continue;
				}
				out.add(lower);
			}
			return out;
		}

		/**
		 * Lowercase, accents stripped, a single trivial trailing 's' plural dropped - catches {@code Adresse}/
		 * {@code Adresses} or {@code Address}/{@code Addresses} as the same name. Deliberately does NOT bridge a
		 * genuine cross-language spelling difference such as {@code Address}/{@code Adresse} (different letters,
		 * not a plural of one another) - that pair is instead caught by {@link #matchesDuplicate}'s attribute-
		 * signature comparison, since in practice a true synonym/translation ends up describing the exact same
		 * attributes (the 2026-09-23 measurement on project A: both named city/country/person/postalCode/street).
		 */
		private static String normalizedName(final String pName)
		{
			String normalized = Normalizer.normalize(pName, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
			if (normalized.length() > 1 && normalized.endsWith("s"))
			{
				normalized = normalized.substring(0, normalized.length() - 1);
			}
			return normalized;
		}

		// ---- propose_feed ----

		LocalTool.Result proposeFeed(final JsonNode pArguments)
		{
			if (this.stopped)
			{
				return LocalTool.Result.error(Session.STOPPED_MESSAGE);
			}
			final String entityName = Session.textOf(pArguments, "entity");
			final String landingTableKey = Session.textOf(pArguments, "landingTable");
			final String parentFeedKey = Session.textOf(pArguments, "parentFeed");
			final String variantArgument = Session.textOf(pArguments, "variant");
			final String variant = variantArgument == null || variantArgument.trim().isEmpty() ? null : variantArgument.trim();
			final String key = entityName + "|" + landingTableKey + "|" + parentFeedKey + "|" + variant;

			final CreatedEntity entity = this.resolveEntityForFeed(entityName);
			if (entity == null)
			{
				return this.refuse(Session.TOOL_FEED, key, "'" + entityName
					+ "' is not a target entity proposed earlier in this run, nor an existing one still pending review: call propose_target_entity first.");
			}
			final StagingRules.LandingTableInfo table = landingTableKey == null ? null : this.tablesByKey.get(landingTableKey);
			if (table == null)
			{
				return this.refuse(Session.TOOL_FEED, key,
					"unknown landing table '" + landingTableKey + "'. Use one of the exact table keys ('<sourceSystem>.<table>') listed in the catalogue.");
			}
			if (this.compact && !this.describedTables.contains(landingTableKey))
			{
				return this.refuse(Session.TOOL_FEED, key,
					"call describe_landing_table('" + landingTableKey + "') first to see its real columns before mapping it.");
			}

			// the pair (entity, landingTable) IS this feed's own address ({@link CreatedFeed#feedKey}, also how a
			// LATER feed of this run may name it as its own parentFeed) - so it doubles as the uniqueness key too:
			// at most one feed per pair, whatever its parent (a documented simplification of the design's own
			// (entity, landingTable, parentFeed) triple, never exercised by a real feed tree: a SECOND feed on the
			// exact same pair, under a DIFFERENT parent, has no legitimate use here and would make parentFeed
			// references from a later child ambiguous anyway).
			final String feedKey = StagingLlmPass.feedKey(entityName, landingTableKey, variant);
			if (variant != null && (parentFeedKey == null || parentFeedKey.trim().isEmpty()))
			{
				return this.refuse(Session.TOOL_FEED, key, "'variant' is for a CHILD feed only (several children of one entity in the same landing row): "
					+ "give parentFeed, or omit variant for a root feed.");
			}
			if (variant != null && !StagingLlmPass.VARIANT_PATTERN.matcher(variant).matches())
			{
				return this.refuse(Session.TOOL_FEED, key, "variant '" + variant + "' must be an upper-case role name such as MAILING or OTHER "
					+ "(letters, digits, underscore, 40 characters at most).");
			}

			// cross-RUN idempotence (docs/TARGET-MAPPING.md, "Relance de la passe LLM", measured 2026-09-23 on
			// project A: two identical root feeds were recreated on a second run because nothing told the session
			// this pair already had one) - checked BEFORE parentFeed/rules validation, same "nothing to validate,
			// there is nothing new to do" posture as propose_target_entity's own idempotent-adoption branch.
			final ExistingFeed priorFeed = this.priorFeedsByKey.get(feedKey);
			if (priorFeed != null)
			{
				if ("REJECTED".equals(priorFeed.status))
				{
					return this.refuse(Session.TOOL_FEED, key, "a person rejected this feed, do not propose it again.");
				}
				this.refusalCounts.remove(Session.TOOL_FEED + "|" + key);
				return LocalTool.Result.ok("A feed for (" + entityName + ", " + landingTableKey + ") already exists from an earlier run (status "
					+ priorFeed.status + "), nothing to do - do not propose it again. To attach a NEW child feed to it, name it as parentFeed '" + feedKey
					+ "'.");
			}

			ParentFeedRef parentFeed = null;
			if (parentFeedKey != null && !parentFeedKey.trim().isEmpty())
			{
				parentFeed = this.resolveParentFeedRef(parentFeedKey);
				if (parentFeed == null)
				{
					return this.refuse(Session.TOOL_FEED, key, "unknown parent feed '" + parentFeedKey
						+ "': it must name an entity and landing table already proposed with propose_feed earlier in this run, or a feed of an EARLIER "
						+ "run still listed under that entity above, exactly as '<entity>|<sourceSystem>.<table>' - never one a person rejected.");
				}
				if (entity.parentEntity == null)
				{
					return this.refuse(Session.TOOL_FEED, key, "'" + entityName
						+ "' has no parentEntity declared: a child feed can only feed an entity created with parentEntity/parentLinkAttribute. Omit "
						+ "parentFeed for a root feed instead.");
				}
				if (!parentFeed.entityName.equals(entity.parentEntity))
				{
					return this.refuse(Session.TOOL_FEED, key, "parent feed '" + parentFeedKey + "' feeds '" + parentFeed.entityName + "', not '"
						+ entity.parentEntity + "' ('" + entityName + "'s own declared parent).");
				}
				if (!parentFeed.landingTableKey.equals(landingTableKey))
				{
					return this.refuse(Session.TOOL_FEED, key,
						"a child feed must read the SAME landing table as its parent feed (" + parentFeed.landingTableKey + "): landing rows are flat "
							+ "today, so a child reading a DIFFERENT table must be proposed as its own root feed instead (omit parentFeed) with its "
							+ "reference attribute mapped like any ordinary column.");
				}
			}

			// lot M15 (2026-10-18): a second call for a feed already proposed IN THIS RUN adds the rules it brings for attributes the feed does not map yet
			// (the follow-up turn asks for it: STORES.TELEPHONE was left out of the Client feed and the model answered that the feed "cannot be reproposed");
			// when it brings nothing new, it is refused as before
			final CreatedFeed sameRunFeed = this.feedsByFeedKey.get(feedKey);

			final JsonNode rulesNode = pArguments.path("rules");
			if (!rulesNode.isArray() || rulesNode.size() == 0)
			{
				return this.refuse(Session.TOOL_FEED, key, "'rules' must list at least one column-to-attribute mapping.");
			}
			final Set<String> entityAttributeNames = this.attributeNamesOf(entity);
			final Set<String> tableColumnNames = new LinkedHashSet<String>();
			for (final StagingRules.LandingColumnInfo column : table.columns)
			{
				tableColumnNames.add(column.fieldName);
			}
			// per-field-rationale lot (2026-09-24): the FEED's own rationale/confidence are parsed here, ahead of
			// the rule loop below (moved up from just after it), so each rule can fall back to them only when the
			// model left its OWN rationale/confidence blank - never as the primary source any more.
			final String feedRationale = Session.textOf(pArguments, "rationale");
			if (feedRationale == null || feedRationale.trim().isEmpty())
			{
				return this.refuse(Session.TOOL_FEED, key, "'rationale' must not be empty. Give a one or two sentence rationale and retry.");
			}
			final int feedConfidence = Session.confidenceOf(pArguments);
			final Set<String> usedAttributes = new LinkedHashSet<String>();
			final List<String> removals = new ArrayList<String>();
			final List<CreatedFeedRule> rules = new ArrayList<CreatedFeedRule>();
			this.pendingOrderDecisions.clear();
			this.pendingIdenticalDecisions.clear();
			this.probedInCall.clear();
			this.pendingConversionDecisions.clear();
			final List<String> conversionAnswers = new ArrayList<String>();
			// structural rule (2) of the system prompt: a CHILD feed's own mapping of the entity's parent-link
			// attribute is dropped, never refused - see this loop's own note below for why.
			final List<String> droppedLinkAttributes = new ArrayList<String>();
			for (final JsonNode ruleNode : rulesNode)
			{
				final String attributeName = ruleNode.path("attribute").asText(null);
				final String landingColumn = ruleNode.path("landingColumn").asText(null);
				final String pipeline = ruleNode.path("pipeline").isTextual() ? ruleNode.path("pipeline").asText() : null;
				if (attributeName == null || !entityAttributeNames.contains(attributeName))
				{
					return this.refuse(Session.TOOL_FEED, key,
						"'" + attributeName + "' is not one of '" + entityName + "'s own attributes. Use one of the exact attribute names it was proposed with.");
				}
				if (!usedAttributes.add(attributeName))
				{
					return this.refuse(Session.TOOL_FEED, key, "attribute '" + attributeName + "' already has a rule in this call.");
				}
				// lot F2b (2026-10-19): "remove": true on a feed already proposed in this run drops the rule of that attribute (the answer to a span that does not belong)
				if (ruleNode.path("remove").asBoolean(false))
				{
					if (sameRunFeed == null)
					{
						return this.refuse(Session.TOOL_FEED, key, "'remove' drops a rule from a feed you already proposed in this run; there is none for (" + entityName + ", "
							+ landingTableKey + ") yet.");
					}
					removals.add(attributeName);
					continue;
				}
				// bug fix, docs/TARGET-MAPPING.md sec 3.3/4.2 (project Personnes, 2026-09-23): on a CHILD feed, the
				// link-to-parent attribute is written by StagingLoader from the parent row it is recursively
				// processing (TransferPlan.Feed#parentLinkField), never resolved by a lookup - a rule mapping it
				// from a landing column here is harmless but redundant, and used to make StagingLoader treat it as
				// an ORDINARY foreign key, rejecting every child row as an "unknown parent" on a first load (the
				// parent of the SAME landing row is still buffered, not yet written, at that point). Drop the rule
				// rather than refuse the call: a refusal would cost the model a turn over something harmless, and
				// StagingLlmSuggester never writes a mapping for it anyway (defensive: it re-checks the same
				// condition in case a rule slips through some other path, e.g. a run recorded before this fix).
				if (parentFeed != null && attributeName.equals(entity.parentLinkAttribute))
				{
					droppedLinkAttributes.add(attributeName);
					continue;
				}
				if (landingColumn == null || !tableColumnNames.contains(landingColumn))
				{
					return this.refuse(Session.TOOL_FEED, key,
						"'" + landingColumn + "' is not a column of '" + landingTableKey + "'. Use one of the exact column names listed for that table.");
				}
				if (pipeline != null)
				{
					final String badStep = Session.firstUnknownStep(pipeline);
					if (badStep != null)
					{
						return this.refuse(Session.TOOL_FEED, key, "pipeline '" + pipeline + "' names '" + badStep
							+ "', which is not in the given transformation catalogue (it may be one of the excluded, EBX-lookup-only names).");
					}
					// PersVol2 volume test (2026-09-25): regex(^[A-Za-z]+) cut every accented or compound name of a web shop
					if (StagingLlmPass.ASCII_LETTER_CLASS.matcher(pipeline).find())
					{
						return this.refuse(Session.TOOL_FEED, key, "pipeline '" + pipeline + "' matches letters with an ASCII class such as [A-Za-z]: "
							+ "accented, apostrophe or hyphenated names would be cut. Use \\p{L} for a letter (\\p{Lu} upper case), rule (5).");
					}
					// PersVol8 (2026-09-28): regex(^\d+,?\s*(.*)$) - the comma cuts the pattern and the street rule returned the
					// house number; the probe read it as an extraction. Structural, so checked before any value is touched.
					final String comma = StagingLlmPass.unquotedCommaRefusal(pipeline);
					if (comma != null)
					{
						return this.refuse(Session.TOOL_FEED, key, comma);
					}
					// lot M8b (2026-10-04, Vol1m): a case change in front of a regex that reads letter case made the pattern return the first token
					// as the last name on 153,937 rows. Structural, so checked before any value is touched.
					final String caseOrder = CaseOrderRule.pipelineRefusal(pipeline);
					if (caseOrder != null)
					{
						return this.refuse(Session.TOOL_FEED, key, caseOrder);
					}
				}
				// staging feed-conversion lot E (2026-10-17): a rule that does not convert the column to its typed target is CORRECTED from the
				// column's measured value profile, or recorded FLAGGED - never a reason to refuse the whole feed (TiersB2CRetestStaging, loyalty
				// date_naissance written dd/MM/yyyy mapped with date(yyyy-MM-dd): the refusal made the model drop the source)
				final TypedOutcome typed = this.typedOutcome(entity, attributeName, table, landingTableKey, landingColumn, pipeline);
				final String rulePipeline = typed != null && typed.correctedPipeline != null ? typed.correctedPipeline : pipeline;
				if (typed == null || !typed.flagged)
				{
					final String probeRefusal = this.probeRule(key, entity, attributeName, table, landingTableKey, landingColumn, rulePipeline);
					if (probeRefusal != null)
					{
						return this.refuse(Session.TOOL_FEED, key, probeRefusal);
					}
				}
				if (typed == null)
				{
					final String conversionRefusal = this.conversionRefusal(entity, attributeName, table, landingColumn, pipeline);
					if (conversionRefusal != null)
					{
						return this.refuse(Session.TOOL_FEED, key, conversionRefusal);
					}
				}
				// per-field-rationale lot (2026-09-24): this rule's OWN rationale/confidence - fall back to the
				// feed's (parsed above) only when the model left one blank, defensive since the schema requires both.
				final String ruleRationaleRaw = ruleNode.path("rationale").asText(null);
				String ruleRationale = ruleRationaleRaw == null || ruleRationaleRaw.trim().isEmpty() ? feedRationale.trim() : ruleRationaleRaw.trim();
				int ruleConfidence = ruleNode.path("confidence").isIntegralNumber() ? Session.confidenceOf(ruleNode) : feedConfidence;
				if (typed != null && typed.note != null)
				{
					// marked like the normalisation pass's verifier marks a mapping (CheckNote): a fix keeps the confidence, a flag lowers it
					// below the bulk-accept bar and keeps the one it had in the note, so a clean re-check gives it back
					if (typed.flagged)
					{
						final int capped = Math.min(ruleConfidence, Math.min(typed.ratePercent, StagingLlmPass.FLAG_CONFIDENCE_CAP));
						ruleRationale = CheckNote.with(ruleRationale, typed.coded, Integer.valueOf(ruleConfidence), StagingLlmPass.RATIONALE_MAX_LENGTH);
						ruleConfidence = capped;
					}
					else
					{
						ruleRationale = CheckNote.with(ruleRationale, NoteTexts.Note.of(NoteTexts.N_FIXED).addAll(typed.coded), null, StagingLlmPass.RATIONALE_MAX_LENGTH);
					}
					final String ruleName = entityName + "." + attributeName + " <- " + landingTableKey + "." + landingColumn;
					this.pendingConversionDecisions.add(ruleName + ": " + (typed.flagged ? "flagged for a person, confidence " + ruleConfidence + " - "
						: "corrected - ") + typed.note);
					conversionAnswers.add((typed.flagged ? "FLAGGED rule " : "CORRECTED rule ") + attributeName + " <- " + landingColumn + ": " + typed.note
						+ (typed.flagged ? " It is recorded with its confidence lowered to " + ruleConfidence + " for a person to settle; the feed and its other rules stand."
							: " The corrected pipeline is the one recorded (checked on the real values, which are never shown to you)."));
				}
				rules.add(new CreatedFeedRule(attributeName, landingColumn, rulePipeline, ruleRationale, ruleConfidence));
			}
			if (variant != null && sameRunFeed == null && !Session.hasConstantRule(rules))
			{
				return this.refuse(Session.TOOL_FEED, key, "a feed with variant " + variant + " needs a rule mapping the discriminating attribute with "
					+ "constant(" + variant + "), so each child row says which role it plays (e.g. addressType).");
			}
			if (rules.isEmpty() && removals.isEmpty())
			{
				// every incoming rule named the link-to-parent attribute and was dropped above - nothing left to feed.
				return this.refuse(Session.TOOL_FEED, key, "every rule in this call mapped '" + entity.parentLinkAttribute
					+ "', the link-to-parent attribute the engine already sets from the parent row: propose at least one rule for a REAL attribute of '"
					+ entityName + "'.");
			}

			// a child-type entity (parentEntity declared) fed by a ROOT feed must still reach its parent, or every row
			// it writes is an orphan (project Personnes, 2026-09-24: Adresse <- SAP had no parent feed and no rule
			// on 'personne'; project Vague1: the link attribute was 'houseNumber', mapped from the street number).
			if (parentFeed == null && entity.parentEntity != null && sameRunFeed == null)
			{
				final String refusal = this.rootFeedOfChildRefusal(entity, landingTableKey, table, rules);
				if (refusal != null)
				{
					return this.refuse(Session.TOOL_FEED, key, refusal);
				}
			}

			final String sourceIdPipeline = Session.textOf(pArguments, "sourceIdPipeline");
			if (sourceIdPipeline != null && !sourceIdPipeline.trim().isEmpty())
			{
				final String badStep = Session.firstUnknownStep(sourceIdPipeline);
				if (badStep != null)
				{
					return this.refuse(Session.TOOL_FEED, key, "sourceIdPipeline '" + sourceIdPipeline + "' names '" + badStep
						+ "', which is not in the given transformation catalogue.");
				}
				final String comma = StagingLlmPass.unquotedCommaRefusal(sourceIdPipeline);
				if (comma != null)
				{
					return this.refuse(Session.TOOL_FEED, key, "sourceIdPipeline: " + comma);
				}
				// a child's sourceId that reads no column is the SAME for every child row (constant('MAIN') on
				// project Personnes, 2026-09-24): every address would collapse onto one staging row.
				if (parentFeed != null && !Session.referencesAnyColumn(sourceIdPipeline, table))
				{
					return this.refuse(Session.TOOL_FEED, key, "sourceIdPipeline '" + sourceIdPipeline
						+ "' reads no column of " + landingTableKey + ", so every child row would get the SAME sourceId. Omit it (the engine then "
						+ "gives each child '<parent sourceId>#<n>'), or build it from the row's key, e.g. concat(ID)|suffix(#MAIN).");
				}
				if (parentFeed == null)
				{
					for (final StagingRules.LandingColumnInfo column : table.columns)
					{
						if (column.sourceKey && Session.referencesColumn(sourceIdPipeline, column.fieldName))
						{
							return this.refuse(Session.TOOL_FEED, key, "a root feed's sourceId is always the landing row's own key: do not recompute it "
								+ "from '" + column.fieldName + "' via sourceIdPipeline. Only a CHILD feed needs one - omit it here.");
						}
					}
				}
			}

			if (sameRunFeed != null)
			{
				// a second call on a feed of this run: an attribute the feed does not map yet is ADDED (lot M15); one it already maps from the same column with the same
				// pipeline is left as it is; one it maps differently is REPLACED, never duplicated (lot F2b); "remove" drops it
				final Map<String, Integer> mappedAt = new java.util.LinkedHashMap<String, Integer>();
				for (int i = 0; i < sameRunFeed.rules.size(); i++)
				{
					mappedAt.put(sameRunFeed.rules.get(i).attribute, Integer.valueOf(i));
				}
				final List<CreatedFeedRule> added = new ArrayList<CreatedFeedRule>();
				final Map<Integer, CreatedFeedRule> replaced = new java.util.LinkedHashMap<Integer, CreatedFeedRule>();
				for (final CreatedFeedRule rule : rules)
				{
					final Integer at = mappedAt.get(rule.attribute);
					if (at == null)
					{
						added.add(rule);
					}
					else
					{
						final CreatedFeedRule old = sameRunFeed.rules.get(at.intValue());
						if (!old.landingColumn.equals(rule.landingColumn) || !java.util.Objects.equals(old.pipeline, rule.pipeline))
						{
							replaced.put(at, rule);
						}
					}
				}
				final Set<Integer> dropped = new java.util.TreeSet<Integer>();
				for (final String attribute : removals)
				{
					final Integer at = mappedAt.get(attribute);
					if (at == null)
					{
						return this.refuse(Session.TOOL_FEED, key, "'remove' names '" + attribute + "', which this feed does not map.");
					}
					dropped.add(at);
				}
				if (added.isEmpty() && replaced.isEmpty() && dropped.isEmpty())
				{
					return this.refuse(Session.TOOL_FEED, key, "a feed for (" + entityName + ", " + landingTableKey + (variant == null ? "" : ", variant " + variant)
						+ ") was already proposed in this run, with a rule for every attribute of this call." + (parentFeed == null ? ""
							: " If this row holds ANOTHER " + entityName + " (a second address...), propose it with its own variant (MAILING, OTHER...) and "
								+ "a discriminating attribute mapped with constant(<variant>)."));
				}
				if (sameRunFeed.rules.size() - dropped.size() + added.size() < 1)
				{
					return this.refuse(Session.TOOL_FEED, key, "removing these rules would leave the feed (" + entityName + ", " + landingTableKey
						+ ") with none: a feed needs at least one rule. Say in a note why this table must not feed the entity instead.");
				}
				for (final Map.Entry<Integer, CreatedFeedRule> entry : replaced.entrySet())
				{
					sameRunFeed.rules.set(entry.getKey().intValue(), entry.getValue());
				}
				final List<CreatedFeedRule> kept = new ArrayList<CreatedFeedRule>();
				for (int i = 0; i < sameRunFeed.rules.size(); i++)
				{
					if (!dropped.contains(Integer.valueOf(i)))
					{
						kept.add(sameRunFeed.rules.get(i));
					}
				}
				sameRunFeed.rules.clear();
				sameRunFeed.rules.addAll(kept);
				sameRunFeed.rules.addAll(added);
				this.refusalCounts.remove(Session.TOOL_FEED + "|" + key);
				this.orderDecisions.addAll(this.pendingOrderDecisions);
				this.pendingOrderDecisions.clear();
				this.identicalOutputDecisions.addAll(this.pendingIdenticalDecisions);
				this.pendingIdenticalDecisions.clear();
				this.conversionDecisions.addAll(this.pendingConversionDecisions);
				this.pendingConversionDecisions.clear();
				final List<String> did = new ArrayList<String>();
				if (!added.isEmpty())
				{
					did.add("extended with " + added.size() + " rule(s) for " + StagingLlmPass.joinAttributes(added));
				}
				if (!replaced.isEmpty())
				{
					did.add("" + replaced.size() + " rule(s) REPLACED for " + StagingLlmPass.joinAttributes(new ArrayList<CreatedFeedRule>(replaced.values())));
				}
				if (!dropped.isEmpty())
				{
					did.add("rule(s) removed for " + StagingLlmPass.join(removals));
				}
				final StringBuilder extended = new StringBuilder("Feed for '" + entityName + "' on '" + landingTableKey + "' (already proposed in this run) " + String.join(", ", did)
					+ "; its other rules stand.");
				final List<CreatedFeedRule> touched = new ArrayList<CreatedFeedRule>(added);
				touched.addAll(replaced.values());
				for (final CreatedFeedRule rule : touched)
				{
					final String harmony = this.harmonyNote(entityName, rule.attribute);
					if (harmony != null)
					{
						extended.append(' ').append(harmony);
					}
				}
				for (final String answer : conversionAnswers)
				{
					extended.append(' ').append(answer);
				}
				return LocalTool.Result.ok(extended.toString());
			}

			final CreatedFeed feed = new CreatedFeed(feedKey, entityName, landingTableKey, parentFeed == null ? null : parentFeed.sameRunKey,
				parentFeed == null ? null : parentFeed.existingId, rules,
				sourceIdPipeline == null || sourceIdPipeline.trim().isEmpty() ? null : sourceIdPipeline.trim(), feedConfidence, feedRationale.trim(), variant);
			this.feedsByFeedKey.put(feedKey, feed);
			this.feedsInOrder.add(feed);
			this.refusalCounts.remove(Session.TOOL_FEED + "|" + key);
			this.orderDecisions.addAll(this.pendingOrderDecisions);
			this.pendingOrderDecisions.clear();
			this.identicalOutputDecisions.addAll(this.pendingIdenticalDecisions);
			this.pendingIdenticalDecisions.clear();
			this.conversionDecisions.addAll(this.pendingConversionDecisions);
			this.pendingConversionDecisions.clear();
			final StringBuilder message = new StringBuilder(
				"Feed for '" + entityName + "' on '" + landingTableKey + "' recorded with " + rules.size() + " rule(s)"
					+ (parentFeed == null ? "" : " (child of " + parentFeedKey + ")") + (variant == null ? "" : ", variant " + variant + ", address it as '"
						+ feedKey + "'") + ".");
			for (final CreatedFeedRule rule : rules)
			{
				final String harmony = this.harmonyNote(entityName, rule.attribute);
				if (harmony != null)
				{
					message.append(' ').append(harmony);
				}
			}
			for (final String answer : conversionAnswers)
			{
				message.append(' ').append(answer);
			}
			if (!conversionAnswers.isEmpty())
			{
				message.append(" The source is NOT dropped: this feed is recorded, go on with the next table.");
			}
			if (!droppedLinkAttributes.isEmpty())
			{
				message.append(" Note: the link to the parent is set by the engine from the parent row; rule(s) on ")
					.append(StagingLlmPass.join(droppedLinkAttributes)).append(" ignored.");
			}
			return LocalTool.Result.ok(message.toString());
		}

		/**
		 * What a {@code parentFeed} argument of {@code propose_feed} resolved to, whichever run created it -
		 * see {@link #resolveParentFeedRef}. Exactly one of {@link #sameRunKey}/{@link #existingId} is set.
		 */
		private static final class ParentFeedRef
		{
			final String entityName;
			final String landingTableKey;
			/** Set only when the parent feed was created THIS run - the key {@link CreatedFeed#parentFeedKey} carries. */
			final String sameRunKey;
			/** Set only when the parent feed is a PRE-EXISTING row from an earlier run - its own persisted id, {@link CreatedFeed#parentFeedId}. */
			final String existingId;

			ParentFeedRef(final String pEntityName, final String pLandingTableKey, final String pSameRunKey, final String pExistingId)
			{
				this.entityName = pEntityName;
				this.landingTableKey = pLandingTableKey;
				this.sameRunKey = pSameRunKey;
				this.existingId = pExistingId;
			}
		}

		/**
		 * Resolves a {@code parentFeed} argument against BOTH this run's own feeds ({@link #feedsByFeedKey}) and
		 * the project's prior ones ({@link #priorFeedsByKey}) - docs/TARGET-MAPPING.md, "Relance de la passe LLM":
		 * a child feed proposed this run may need to attach to a root feed an EARLIER run already created, not
		 * only to one this run just created. A prior feed a person {@code REJECTED} is treated as unknown - never
		 * a valid attachment point for a brand new child, same posture as {@code propose_feed}'s own REJECTED
		 * check on the pair being proposed. Null when {@code pParentFeedKey} names neither.
		 */
		private ParentFeedRef resolveParentFeedRef(final String pParentFeedKey)
		{
			final CreatedFeed sameRun = this.feedsByFeedKey.get(pParentFeedKey);
			if (sameRun != null)
			{
				return new ParentFeedRef(sameRun.entityName, sameRun.landingTableKey, sameRun.feedKey, null);
			}
			final ExistingFeed prior = this.priorFeedsByKey.get(pParentFeedKey);
			if (prior == null || "REJECTED".equals(prior.status))
			{
				return null;
			}
			final int separator = pParentFeedKey.indexOf('|');
			final String entityName = separator < 0 ? pParentFeedKey : pParentFeedKey.substring(0, separator);
			return new ParentFeedRef(entityName, prior.landingTableKey, null, prior.id);
		}

		/** Resolves {@code propose_feed}'s {@code entity} argument, lazily adopting a still-pending prior entity into {@link #created} on first use - same posture as the retired pass' "fix F". */
		private CreatedEntity resolveEntityForFeed(final String pEntityName)
		{
			if (pEntityName == null)
			{
				return null;
			}
			CreatedEntity entity = this.created.get(pEntityName);
			if (entity != null)
			{
				return entity;
			}
			final ExistingTargetEntity prior = this.priorByName.get(pEntityName);
			if (prior == null || !"PROPOSED".equals(prior.status))
			{
				return null;
			}
			String parentLinkField = null;
			if (prior.parentEntity != null && prior.parentLinkAttribute != null)
			{
				final Set<String> siblingNames = new LinkedHashSet<String>();
				siblingNames.add("sourcesystem");
				siblingNames.add("sourceid");
				for (final String attributeName : prior.attributeNames)
				{
					siblingNames.add(attributeName.toLowerCase(Locale.ROOT));
				}
				parentLinkField = StagingModel.foreignKeyFieldName(prior.parentLinkAttribute, siblingNames);
			}
			entity = new CreatedEntity(prior.name, null, null, 0, java.util.Collections.<CreatedAttribute>emptyList(), prior.parentEntity,
				prior.parentLinkAttribute, parentLinkField, prior.id);
			this.created.put(pEntityName, entity);
			return entity;
		}

		private Set<String> attributeNamesOf(final CreatedEntity pEntity)
		{
			final Set<String> names = new LinkedHashSet<String>();
			for (final CreatedAttribute attribute : pEntity.attributes)
			{
				names.add(attribute.name);
			}
			if (pEntity.existingId != null)
			{
				final ExistingTargetEntity prior = this.priorByName.get(pEntity.name);
				if (prior != null)
				{
					names.addAll(prior.attributeNames);
				}
			}
			return names;
		}

		/** The first pipeline step name not in {@link StagingLlmPass#ALLOWED_TRANSFORMATIONS}, or null when every step is valid (including an empty/blank pipeline). */
		private static String firstUnknownStep(final String pPipeline)
		{
			for (final String step : Session.pipelineStepNames(pPipeline))
			{
				if (!StagingLlmPass.ALLOWED_TRANSFORMATIONS.contains(step))
				{
					return step;
				}
			}
			return null;
		}

		/** Splits a Connectors pipeline into its step names (before any {@code (...)}), lower-cased - quote-aware so a quoted argument's own {@code |} never splits it. */
		private static List<String> pipelineStepNames(final String pPipeline)
		{
			final List<String> names = new ArrayList<String>();
			if (pPipeline == null || pPipeline.trim().isEmpty())
			{
				return names;
			}
			final String text = pPipeline.trim();
			boolean inQuote = false;
			int start = 0;
			final List<String> steps = new ArrayList<String>();
			for (int i = 0; i < text.length(); i++)
			{
				final char c = text.charAt(i);
				if (c == '\'')
				{
					inQuote = !inQuote;
				}
				else if (c == '|' && !inQuote)
				{
					steps.add(text.substring(start, i));
					start = i + 1;
				}
			}
			steps.add(text.substring(start));
			for (final String step : steps)
			{
				final String trimmed = step.trim();
				final int paren = trimmed.indexOf('(');
				names.add((paren < 0 ? trimmed : trimmed.substring(0, paren)).trim().toLowerCase(Locale.ROOT));
			}
			return names;
		}

		private static boolean referencesColumn(final String pPipeline, final String pColumnFieldName)
		{
			return Pattern.compile("\\b" + Pattern.quote(pColumnFieldName) + "\\b").matcher(pPipeline).find();
		}

		/**
		 * Runs {@link #ruleProbe} on one rule and remembers the dominant output shape per source system for {@link
		 * #harmonyNote}; returns the refusal text, or null. A constant or a concat of several columns is not probed (a
		 * one-column sample cannot judge it); a rule with no pipeline keeps the column's own shapes.
		 */
		private String probeRule(final String pFeedKey, final CreatedEntity pEntity, final String pAttributeName, final StagingRules.LandingTableInfo pTable,
			final String pLandingTableKey, final String pLandingColumn, final String pPipeline)
		{
			List<String> outputShapes = null;
			final String lower = pPipeline == null ? "" : pPipeline.trim().toLowerCase(java.util.Locale.ROOT);
			final String ruleName = pEntity.name + "." + pAttributeName + " <- " + pLandingTableKey + "." + pLandingColumn;
			if (pPipeline == null || pPipeline.trim().isEmpty())
			{
				for (final StagingRules.LandingColumnInfo column : pTable.columns)
				{
					if (column.fieldName.equals(pLandingColumn))
					{
						outputShapes = column.shapes;
						if (StagingLlmPass.showsTokenCase(column) && column.tokenCase.twoLayouts())
						{
							// PersVol7 (2026-09-28): a two-layout column mapped whole is a decision worth the outcome
							this.pendingOrderDecisions.add(ruleName + ": kept whole (" + column.tokenCase.render() + ")");
						}
					}
				}
			}
			else if (this.ruleProbe != null && !lower.startsWith("constant(") && !lower.contains("concat(") && !lower.contains("joinwidth("))
			{
				final ProbeResult result = this.ruleProbe.probe(pLandingTableKey, pLandingColumn, pPipeline);
				final String refusal = StagingLlmPass.probeRefusal(pLandingColumn, pPipeline, result);
				if (refusal != null)
				{
					return refusal;
				}
				// PersVol4 volume test (2026-09-26): map(FR=France;DE=Germany;*=Other) emptied nothing but lost 750 countries
				final String lossy = StagingLlmPass.lossyRefusal(pLandingColumn, pPipeline, result);
				if (lossy != null)
				{
					return lossy;
				}
				// PersVol7 volume test (2026-09-28): a first/last-token regex on a column written in two orders crossed the
				// attributes of 30% of the rows - refused ONCE with the numbers; kept, and recorded, if the model insists
				final String order = StagingLlmPass.orderRefusal(pLandingColumn, pPipeline, result);
				if (order != null)
				{
					if (this.orderRefused.add(pLandingTableKey + "|" + pLandingColumn))
					{
						return order;
					}
					this.pendingOrderDecisions.add(ruleName + " " + pPipeline + ": order-blind rule kept after one refusal (" + result.inputTokenCase.render()
						+ "; " + result.positionDetail + ")");
				}
				else if (result != null && PartOrder.splitsUnknownOrder(result.inputTokenCase, result.outputByInput))
				{
					// Vol100kN (2026-10-12): a casing split of a multi-token column whose casing does not tell the parts apart
					// (LOYALTY.MEMBER.FULL_NAME) took the last token as the first one on every row written the other way round -
					// not refused (the model may split with a stated assumption), but recorded where the order decisions are
					this.pendingOrderDecisions.add(PartOrder.decision(ruleName, pPipeline, result.inputTokenCase, result.outputByInput));
				}
				else if (result != null && result.inputTokenCase != null && result.inputTokenCase.twoLayouts() && result.positionByLayout.size() >= 2)
				{
					final boolean extraction = result.positionByLayout.containsValue("FIRST") || result.positionByLayout.containsValue("LAST");
					this.pendingOrderDecisions.add(ruleName + " " + pPipeline + (extraction ? ": order-aware rule (" : ": kept whole (")
						+ result.inputTokenCase.render() + "; " + result.positionDetail + ")");
				}
				// PersVol8 volume test (2026-09-28): the same order-aware regex proposed for two attributes gave both the last
				// name - compared with the first probed rule of this call on the same column; refused ONCE per attribute
				final Object[] earlier = this.probedInCall.get(pLandingColumn);
				if (earlier == null)
				{
					if (result != null)
					{
						this.probedInCall.put(pLandingColumn, new Object[] { pAttributeName, pPipeline, result });
					}
				}
				else if (!pAttributeName.equals(earlier[0]))
				{
					final String identical = StagingLlmPass.identicalRefusal(pLandingColumn, pPipeline, pAttributeName, (String) earlier[0], (String) earlier[1],
						result, (ProbeResult) earlier[2]);
					if (identical != null)
					{
						if (this.identicalRefused.add(pFeedKey + "|" + pLandingColumn + "|" + pAttributeName))
						{
							return identical;
						}
						final int[] counts = StagingLlmPass.identicalOutputs(result, (ProbeResult) earlier[2]);
						this.pendingIdenticalDecisions.add(ruleName + " " + pPipeline + ": same output as " + earlier[0] + " in " + counts[0] + " of " + counts[1]
							+ " values, kept after one refusal");
					}
				}
				outputShapes = result == null ? null : result.outputShapes;
			}
			if (outputShapes != null && !outputShapes.isEmpty())
			{
				final String attributeKey = pEntity.name + "|" + pAttributeName;
				Map<String, String> bySystem = this.outputShapeBySystem.get(attributeKey);
				if (bySystem == null)
				{
					bySystem = new LinkedHashMap<String, String>();
					this.outputShapeBySystem.put(attributeKey, bySystem);
				}
				bySystem.put(pTable.sourceSystem, outputShapes.get(0));
			}
			return null;
		}

		/**
		 * Rule (6) made visible (PersVol2/PersVol3, 2026-09-25: the ERP fed the country as an ISO code, CRM and web shop as
		 * a country name, twice, although the prompt asked for one form): when the SAME attribute receives code-like
		 * values from one source and words from another, the recorded feed's answer says so. A note, never a refusal -
		 * which form to keep is the model's call (map(...) on one side). Since PersVol4 (2026-09-26, the note was read as
		 * "map the two biggest codes and send the rest to *=Other"), it asks for a COMPLETE map or no harmonisation at all,
		 * and {@link StagingLlmPass#lossyRefusal} refuses a partial one.
		 */
		private String harmonyNote(final String pEntityName, final String pAttributeName)
		{
			final Map<String, String> bySystem = this.outputShapeBySystem.get(pEntityName + "|" + pAttributeName);
			if (bySystem == null || bySystem.size() < 2)
			{
				return null;
			}
			String codeSystem = null;
			String wordSystem = null;
			for (final Map.Entry<String, String> entry : bySystem.entrySet())
			{
				if (StagingLlmPass.codeLike(entry.getValue()))
				{
					codeSystem = entry.getKey();
				}
				else if (StagingLlmPass.wordLike(entry.getValue()))
				{
					wordSystem = entry.getKey();
				}
			}
			if (codeSystem == null || wordSystem == null)
			{
				return null;
			}
			return "Note: '" + pAttributeName + "' receives short codes from " + codeSystem + " (" + bySystem.get(codeSystem) + ") but words from " + wordSystem
				+ " (" + bySystem.get(wordSystem) + "): harmonise them to ONE form only with a COMPLETE map(...) listing "
				+ "EVERY value of one of the feeds (an unlisted value falls to '*=' or is emptied: it is lost, and such a map is refused); if you cannot "
				+ "list every value, leave both forms as they are, rule (6).";
		}

		private static boolean hasConstantRule(final List<CreatedFeedRule> pRules)
		{
			for (final CreatedFeedRule rule : pRules)
			{
				if (rule.pipeline != null && rule.pipeline.trim().toLowerCase(java.util.Locale.ROOT).startsWith("constant("))
				{
					return true;
				}
			}
			return false;
		}

		/** What the value check decided for one rule of a feed whose attribute has a typed target; see {@link #typedOutcome}. */
		private static final class TypedOutcome
		{
			/** The pipeline that replaces the model's, or null. */
			final String correctedPipeline;
			/** True when nothing converts every sampled value: the rule is recorded with a lowered confidence. */
			final boolean flagged;
			/** Measured success rate of the model's own pipeline, 0-100. */
			final int ratePercent;
			/** The {@link TransformVerifier} sentence (counts and pipeline text, no value), or null when the rule converts as it is. */
			final String note;
			/** Staging last points (2026-10-17): the same sentence as a coded note, null when {@link #note} is. */
			final NoteTexts.Note coded;

			TypedOutcome(final String pCorrectedPipeline, final boolean pFlagged, final int pRatePercent, final NoteTexts.Note pNote)
			{
				this.correctedPipeline = pCorrectedPipeline;
				this.flagged = pFlagged;
				this.ratePercent = pRatePercent;
				this.coded = pNote;
				this.note = pNote == null ? null : pNote.english();
			}
		}

		/**
		 * Staging feed-conversion lot E (2026-10-17): the value check of a rule whose attribute is typed ({@code DATE}, {@code TIMESTAMP},
		 * {@code DECIMAL}, {@code INTEGER}, {@code BOOLEAN}) and whose column is text or goes through a pipeline - {@link
		 * RuleProbe#verifyTyped}, i.e. {@link TransformVerifier} on the column's own distinct values. Null when it cannot be judged (no probe,
		 * an attribute of an earlier run whose type is not known here, a column already of the target type with no pipeline, a chain that
		 * reads other columns): the session keeps its earlier checks, refusals included. Otherwise:
		 * <ul>
		 * <li>{@code OK}: the rule converts every sampled value - recorded as it is (and {@link #conversionRefusal}, a guess from the
		 * pipeline text, no longer applies);</li>
		 * <li>{@code FIXED}: the rule fails and the column's measured profile gives a pipeline that converts 100 % of the non-empty sampled
		 * values - that pipeline replaces the model's;</li>
		 * <li>{@code FAILING} / {@code AMBIGUOUS} (a day/month order no value proves): no candidate may be applied - the rule is kept,
		 * flagged. Same convention as the verifier of the normalisation pass: its confidence goes below the bulk-accept bar, it carries a
		 * "Value check" note, and the feed's other rules are unaffected. A person settles it in the Mapping matrix.</li>
		 * </ul>
		 */
		private TypedOutcome typedOutcome(final CreatedEntity pEntity, final String pAttributeName, final StagingRules.LandingTableInfo pTable,
			final String pLandingTableKey, final String pLandingColumn, final String pPipeline)
		{
			if (this.ruleProbe == null)
			{
				return null;
			}
			String dataType = null;
			for (final CreatedAttribute attribute : pEntity.attributes)
			{
				if (attribute.name.equals(pAttributeName))
				{
					dataType = attribute.dataType;
				}
			}
			if (!TransformVerifier.isTyped(dataType))
			{
				return null;
			}
			StagingRules.LandingColumnInfo column = null;
			for (final StagingRules.LandingColumnInfo candidate : pTable.columns)
			{
				if (candidate.fieldName.equals(pLandingColumn))
				{
					column = candidate;
				}
			}
			final boolean noPipeline = pPipeline == null || pPipeline.trim().isEmpty();
			if (column == null || (noPipeline && !"STRING".equals(StagingLlmPass.typeOf(column))))
			{
				return null; // a column already of the target type, copied as is: the type-driven mapping converts it
			}
			final TransformVerifier.Result result = this.ruleProbe.verifyTyped(pLandingTableKey, pLandingColumn, noPipeline ? "" : pPipeline, dataType);
			if (result == null)
			{
				return null;
			}
			if (TransformVerifier.OK.equals(result.verdict))
			{
				return new TypedOutcome(null, false, 100, null);
			}
			if (TransformVerifier.FIXED.equals(result.verdict) && result.replacementPipeline != null)
			{
				return new TypedOutcome(result.replacementPipeline, false, result.ratePercent, result.note);
			}
			if (TransformVerifier.FAILING.equals(result.verdict) || TransformVerifier.AMBIGUOUS.equals(result.verdict))
			{
				return new TypedOutcome(null, true, result.ratePercent, result.note);
			}
			return null; // UNVERIFIABLE, NOT_CHECKED
		}

		/**
		 * Why a rule cannot copy a TEXT column into a DATE/TIMESTAMP attribute as is, or null: EBX refuses a value such as
		 * {@code 19790907} in a date field, so every such row would be rejected at load (PersVol volume test, 2026-09-24: the
		 * ERP birth date, text {@code yyyyMMdd}, was mapped with the type-driven TRIM). The pipeline must parse it -
		 * {@code date(...)}, {@code isodate} or {@code isodatetime}. Silent when the attribute's type is not known here (an
		 * attribute of an entity adopted from an earlier run).
		 */
		private String conversionRefusal(final CreatedEntity pEntity, final String pAttributeName, final StagingRules.LandingTableInfo pTable,
			final String pLandingColumn, final String pPipeline)
		{
			String dataType = null;
			for (final CreatedAttribute attribute : pEntity.attributes)
			{
				if (attribute.name.equals(pAttributeName))
				{
					dataType = attribute.dataType;
				}
			}
			if (!"DATE".equals(dataType) && !"TIMESTAMP".equals(dataType))
			{
				return null;
			}
			StagingRules.LandingColumnInfo column = null;
			for (final StagingRules.LandingColumnInfo candidate : pTable.columns)
			{
				if (candidate.fieldName.equals(pLandingColumn))
				{
					column = candidate;
				}
			}
			if (column == null || !"STRING".equals(StagingLlmPass.typeOf(column)))
			{
				return null;
			}
			final String pipeline = pPipeline == null ? "" : pPipeline.toLowerCase(java.util.Locale.ROOT);
			if (pipeline.contains("date(") || pipeline.contains("isodate") || pipeline.contains("constant("))
			{
				return null;
			}
			return "'" + pLandingColumn + "' is TEXT and '" + pAttributeName + "' is a " + dataType + ": the rule must parse it, e.g. date(yyyyMMdd) for "
				+ "a value shaped 9(8), date(dd/MM/yyyy) for 99/99/9999, isodate for 9999-99-99"
				+ (column.shapes.isEmpty() ? "" : " - its value shapes are " + StagingLlmPass.join(column.shapes)) + ".";
		}

		private static boolean referencesAnyColumn(final String pPipeline, final StagingRules.LandingTableInfo pTable)
		{
			for (final StagingRules.LandingColumnInfo column : pTable.columns)
			{
				if (Session.referencesColumn(pPipeline, column.fieldName))
				{
					return true;
				}
			}
			return false;
		}

		/**
		 * Why a ROOT feed of a child-type entity cannot be recorded, or null when it can. Such a feed is only the
		 * two-table case (an ADDRESS table carrying its own PERSON_ID column), so: the parent must not be fed from
		 * this same table (then it is the flat case, a CHILD feed), the parent must already have a feed somewhere
		 * (proposed first, so the flat case above is always detectable), and the link attribute must be mapped from
		 * a real column that is not the row's own key (the row's own key is the flat case again).
		 */
		private String rootFeedOfChildRefusal(final CreatedEntity pEntity, final String pLandingTableKey, final StagingRules.LandingTableInfo pTable,
			final List<CreatedFeedRule> pRules)
		{
			final String parent = pEntity.parentEntity;
			final String link = pEntity.parentLinkAttribute;
			if (this.hasFeedOf(parent, pLandingTableKey))
			{
				return "'" + pEntity.name + "' is a child of '" + parent + "', which is already fed from " + pLandingTableKey
					+ ": this feed must be a CHILD feed of that one (parentFeed '" + parent + "|" + pLandingTableKey
					+ "'), so each row is linked to the " + parent + " read from the same landing row.";
			}
			if (!this.hasFeedOf(parent, null))
			{
				return "'" + pEntity.name + "' is a child of '" + parent + "', which has no feed yet: propose the feeds of '" + parent
					+ "' first, then this one - as a CHILD feed when " + parent + " is read from this same table, as a root feed mapping '"
					+ link + "' from the column holding the parent's key otherwise.";
			}
			CreatedFeedRule linkRule = null;
			for (final CreatedFeedRule rule : pRules)
			{
				if (rule.attribute.equals(link))
				{
					linkRule = rule;
				}
			}
			if (linkRule == null)
			{
				return "a root feed of '" + pEntity.name + "' must map '" + link + "' (its link to '" + parent
					+ "') from the column of " + pLandingTableKey + " holding the parent's own key (e.g. PERSON_ID); otherwise every row it writes "
					+ "has no parent.";
			}
			for (final StagingRules.LandingColumnInfo column : pTable.columns)
			{
				if (column.fieldName.equals(linkRule.landingColumn) && column.sourceKey)
				{
					return "'" + linkRule.landingColumn + "' is the key of " + pLandingTableKey + " itself, not a reference to a '" + parent
						+ "': if this row also describes the " + parent + ", feed " + parent + " from this table and make this a CHILD feed.";
				}
			}
			return null;
		}

		/** Whether {@code pEntity} has a feed (this run, or an earlier one a person did not reject) on {@code pLandingTableKey} - on any table when that is null. */
		private boolean hasFeedOf(final String pEntity, final String pLandingTableKey)
		{
			for (final CreatedFeed feed : this.feedsInOrder)
			{
				if (feed.entityName.equals(pEntity) && (pLandingTableKey == null || pLandingTableKey.equals(feed.landingTableKey)))
				{
					return true;
				}
			}
			for (final Map.Entry<String, ExistingFeed> prior : this.priorFeedsByKey.entrySet())
			{
				if (prior.getKey().startsWith(pEntity + "|") && !"REJECTED".equals(prior.getValue().status)
					&& (pLandingTableKey == null || pLandingTableKey.equals(prior.getValue().landingTableKey)))
				{
					return true;
				}
			}
			return false;
		}

		// ---- describe_landing_table ----

		LocalTool.Result describeLandingTable(final JsonNode pArguments)
		{
			if (this.stopped)
			{
				return LocalTool.Result.error(Session.STOPPED_MESSAGE);
			}
			final String tableKey = Session.textOf(pArguments, "table");
			final StagingRules.LandingTableInfo table = tableKey == null ? null : this.tablesByKey.get(tableKey);
			if (table == null)
			{
				return this.refuse(Session.TOOL_DESCRIBE, String.valueOf(tableKey),
					"unknown landing table '" + tableKey + "'. Use one of the exact table keys ('<sourceSystem>.<table>') listed in the catalogue.");
			}
			this.describedTables.add(tableKey);
			this.refusalCounts.remove(Session.TOOL_DESCRIBE + "|" + tableKey);
			final StringBuilder text = new StringBuilder();
			text.append(tableKey).append(" (").append(table.columns.size()).append(" columns").append(StagingLlmPass.tableFacts(table)).append("):\n");
			for (final StagingRules.LandingColumnInfo column : table.columns)
			{
				text.append("- ").append(column.fieldName).append(" (type ").append(StagingLlmPass.typeOf(column));
				if (column.sourceKey)
				{
					text.append(", key");
				}
				text.append(StagingLlmPass.tagsText(column));
				if (column.fillRate != null)
				{
					text.append(", fill rate ").append(column.fillRate).append('%');
				}
				text.append(StagingLlmPass.identityText(column));
				if (!column.shapes.isEmpty())
				{
					text.append(", value shapes: ").append(StagingLlmPass.join(column.shapes));
				}
				text.append(StagingLlmPass.tokenCaseText(column));
				text.append(")\n");
			}
			return LocalTool.Result.ok(PromptData.fence("LANDING TABLE " + tableKey, text.toString()));
		}

		// ---- note ----

		LocalTool.Result note(final JsonNode pArguments)
		{
			if (this.stopped)
			{
				return LocalTool.Result.error(Session.STOPPED_MESSAGE);
			}
			final String text = Session.textOf(pArguments, "text");
			if (text == null || text.trim().isEmpty())
			{
				return LocalTool.Result.error("'text' must not be empty.");
			}
			this.notes.add(text.trim());
			return LocalTool.Result.ok("noted");
		}

		// ---- refusal bookkeeping ----

		/**
		 * Every refusal in this class goes through here instead of a raw {@code LocalTool.Result.error}: past
		 * {@link #REFUSAL_STOP_THRESHOLD} identical repeats of the same {@code pTool}/{@code pKey}, the run is
		 * stopped so every further call - of any tool, not only the repeated one - is refused outright,
		 * forcing the model to a final text turn instead of spending its whole budget on the same mistake.
		 * Same mechanism, and same reasoning, as the retired canonical-entity pass's own {@code refuse} (fix
		 * C, 2026-09-20): neither {@code AgentLoop} nor {@code LocalTool} offers a handler any other way to end
		 * a run early.
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
					.error(pMessage + " Stop proposing this: it was refused " + count + " times in a row. The run ends now - answer with your final summary.");
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
