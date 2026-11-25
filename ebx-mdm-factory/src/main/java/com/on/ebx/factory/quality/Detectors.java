package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.staging.NoteTexts;

/**
 * Proposes {@code MappingStep} cleanup steps for ONE landing column, from its DISTINCT values (with row
 * counts, capped by the caller) plus what is known about the column - never from a live database call (that
 * lives in {@code ebx.QualitySuggester}). Pure: no EBX class.
 * <p>
 * <b>Domain-heuristics removal (2026-09-21, product owner review): every family that decided from a column
 * NAME or a catalog TAG vocabulary is gone.</b> The product owner's verdict on the first cut of this lot -
 * column names containing {@code siren}/{@code siret}/{@code iban}/{@code country}/{@code currency}, "looks
 * like a legal form", identifier tag hints - was that it is "of little interest": those heuristics only ever
 * work for party/company data, and the factory must work for ANY master-data domain (products, materials,
 * sites...). Removed from this class: {@code IDENTIFIER_TAG_HINTS}, {@code proposeNormaliseIdentifier} (its
 * only gate was a tag hint), {@code proposeCountry}/{@code proposeCurrency}/{@code proposeLegalFormTranscode}
 * (name-hinted lookups), {@code looksLikeLegalForm}, {@code nameHints}, {@code findScopeColumn}, {@code
 * confidenceFromResolution} (only used by the lookup families). <b>What survives is exactly what the product
 * owner's own rule of thumb keeps: a rule that decides from measured VALUES alone, with no domain
 * assumption</b> - {@link #proposeTrim}/{@link #proposeCollapseSpaces} (does trimming/collapsing actually
 * change anything on THIS data) and {@link #proposeEmptyToNull} (does a value equal one of a small, universal,
 * non-domain-specific empty-ish sentinel - {@code N/A}, {@code -}, {@code ?}... - true of a product code
 * column exactly as much as a company name column). Country/currency/legal-form transcoding and identifier
 * normalisation are not gone from the factory - they are now something the LANGUAGE MODEL proposes, from
 * metadata (and, where a project opts in, a sample of real values), after MEASURING the effect itself with
 * the {@code measure} tool - see {@code quality.QualityLlmPass}. The generic MAPPING_STEP toolbox vocabulary
 * ({@link CleaningSteps}) is unchanged: {@code COUNTRY_TO_ISO2}/{@code CURRENCY_TO_ISO}/{@code
 * TRANSCODE_REFERENCE}/{@code NORMALISE_IDENTIFIER} still execute exactly as before, for a row a person
 * accepted under the old heuristics AND for a row the language model proposes now - only the PROPOSER changed.
 * <p>
 * <b>A rule that would change nothing is never proposed</b> (unchanged principle): every {@code propose*}
 * method below returns nothing when its own {@code valuesChanged} tally is zero.
 * <p>
 * <b>Confidence</b>: a purely mechanical rule (TRIM/COLLAPSE_SPACES) always does exactly what it says, so it
 * is proposed at a fixed high confidence (100). {@code EMPTY_TO_NULL} is deliberately capped low (70)
 * regardless of its own hit rate: docs/NORMALISATION.md sec 1.3 found a real collision (a legal-form "NA"
 * that is a genuine abbreviation, not a null-like sentinel) that only a person, column by column, can rule
 * out - the same caution applies to any other domain (a product status code "NC" that happens to also read
 * as an empty-ish token, say).
 * <p>
 * <b>A real value is never stored as an example unless its OWN KIND makes it reference vocabulary by
 * nature</b> - safe BY CONSTRUCTION, not by a guess (coordinator review, 2026-09-20, of an earlier cut of
 * this lot). {@link #REAL_VALUE_KINDS} is the whole allow-list: only {@code EMPTY_TO_NULL} (and only the
 * MATCHED SENTINEL TOKEN itself, e.g. {@code "N/A"} - drawn from a small fixed list, {@link #EMPTY_SENTINELS}
 * or the step's own argument, never the surrounding real data). {@code TRIM}/{@code COLLAPSE_SPACES} store a
 * {@link Shapes#pair shape} instead.
 */
public final class Detectors
{
	/**
	 * Every {@code MappingStep.kind} this class (or {@link CrossSystemFold}, its cross-mapping sibling - see
	 * that class's own Javadoc) can still propose on its own (origin {@code RULE}) - {@code
	 * ebx.QualitySuggester}'s withdrawal sweep uses this to recognise a {@code RULE}-origin, still-{@code
	 * PROPOSED} row of a kind this class used to propose before the 2026-09-21 removal (e.g. {@code
	 * COUNTRY_TO_ISO2}, {@code NORMALISE_IDENTIFIER}) but never will again: such a row is withdrawn exactly
	 * like one whose mapping was later rejected, since nothing will ever re-confirm it either. A row of
	 * origin {@code LLM}/{@code USER} is never subject to this check - only what THIS class itself might have
	 * produced can go stale THIS way.
	 * <p>
	 * <b>{@link CleaningSteps#STRIP_ACCENTS}/{@link CleaningSteps#UPPER} were listed here from 2026-09-21 to
	 * 2026-09-22</b> ({@link CrossSystemFold}, a sibling RULE-origin detector, used to write that exact pair as
	 * a {@code MappingStep}) and are REMOVED as of the 2026-09-22 product-owner decision: {@link
	 * CrossSystemFold} no longer proposes any {@code MappingStep} at all, only a derived {@code
	 * StagingAttribute} (see its own Javadoc for why - the old pair REWROTE the display attribute's stored
	 * value, a real data-quality loss). Removing them here is deliberate, not an oversight: any {@code
	 * STRIP_ACCENTS}/{@code UPPER} {@code MappingStep} row still {@code RULE}-origin and {@code PROPOSED} is now
	 * correctly withdrawn by {@code ebx.QualitySuggester#withdrawStaleProposals} the next time {@code
	 * quality/suggest} runs (nothing will ever re-confirm it again); one already {@code ACCEPTED}/{@code
	 * APPLIED} is left untouched but FLAGGED stale - which is exactly the right signal for a person to notice,
	 * since an accepted row of either kind is precisely the pre-2026-09-22 bug (a rewritten display value) and
	 * deserves a reviewer's attention, not a silent deletion.
	 */
	public static final java.util.Set<String> RULE_PROPOSABLE_KINDS = Collections
		.unmodifiableSet(new java.util.LinkedHashSet<String>(java.util.Arrays.asList(CleaningSteps.TRIM, CleaningSteps.COLLAPSE_SPACES, CleaningSteps.EMPTY_TO_NULL)));
	/** {@link #proposeEmptyToNull}'s sentinel set, kept in step with {@link CleaningSteps}'s own default so a proposed step's evidence matches what it would actually do once accepted. Deliberately domain-free: none of these tokens name a business vocabulary. */
	private static final List<String> EMPTY_SENTINELS = Collections
		.unmodifiableList(java.util.Arrays.asList("N/A", "NA", "-", "?", "NULL", "NONE", "UNKNOWN", "N.C.", "N.D."));
	/** The only {@code MappingStep.kind} whose examples may show a real value - see the class Javadoc. {@code TRIM}/{@code COLLAPSE_SPACES} are always {@link Shapes shaped}. */
	private static final java.util.Set<String> REAL_VALUE_KINDS = Collections.singleton(CleaningSteps.EMPTY_TO_NULL);
	/** Confidence ceiling for any proposal whose evidence is a SAMPLE, not the whole column (coordinator review, 2026-09-21) - a mechanical 100 is not warranted when up to {@code exactDistinctCount - valuesRead} distinct values were never even looked at. */
	private static final int SAMPLED_MAX_CONFIDENCE = 80;

	private Detectors()
	{
	}

	// -------------------------------------------------------------------------------------------------------- input

	/** One distinct value of the column being profiled, with how many rows carry it; {@link #scopeValue} is set only when the caller grouped by a second (scope) column too - kept for a caller (e.g. {@code QualityLlmPass}'s {@code measure} tool) that wants a scoped reference lookup; this class's own rules never read it any more. */
	public static final class ValueCount
	{
		public final String value;
		public final String scopeValue;
		public final long count;

		public ValueCount(final String pValue, final String pScopeValue, final long pCount)
		{
			this.value = pValue;
			this.scopeValue = pScopeValue;
			this.count = pCount;
		}

		public ValueCount(final String pValue, final long pCount)
		{
			this(pValue, null, pCount);
		}
	}

	/** What is known of the column being profiled, without its values - name, inferred type, catalog tags, and the staging attribute name it is mapped to (for the rationale text only). Tags/name are carried for a caller that wants them (e.g. {@code QualityLlmPass}'s metadata context) - this class's own rules no longer read them. */
	public static final class ColumnContext
	{
		public final String sourceColumnName;
		public final String stagingAttributeName;
		/** {@code STRING}/{@code INTEGER}/...; blank or null reads as {@code STRING} (the same convention {@code StagingRules} uses). */
		public final String inferredType;
		public final List<String> tags;

		public ColumnContext(final String pSourceColumnName, final String pStagingAttributeName, final String pInferredType, final List<String> pTags)
		{
			this.sourceColumnName = pSourceColumnName;
			this.stagingAttributeName = pStagingAttributeName;
			this.inferredType = pInferredType;
			this.tags = pTags == null ? Collections.<String>emptyList() : pTags;
		}
	}

	// -------------------------------------------------------------------------------------------------------- output

	public static final class StepProposal
	{
		public final String kind;
		public final String argument;
		public final int confidence;
		public final String rationale;
		public final long valuesSeen;
		public final long valuesChanged;
		public final long valuesUnresolved;
		/** "before -> after", top values first, at most 5 - see the class Javadoc on redaction. */
		public final List<String> examples;
		/** Staging last points (2026-10-17): the rationale as a coded note (null for a proposal built from a plain text) - stored in English with its marker, worded in the viewer's language. */
		public final NoteTexts.Note note;

		public StepProposal(final String pKind, final String pArgument, final int pConfidence, final String pRationale, final long pValuesSeen,
			final long pValuesChanged, final long pValuesUnresolved, final List<String> pExamples)
		{
			this(pKind, pArgument, pConfidence, pRationale, null, pValuesSeen, pValuesChanged, pValuesUnresolved, pExamples);
		}

		public StepProposal(final String pKind, final String pArgument, final int pConfidence, final NoteTexts.Note pNote, final long pValuesSeen,
			final long pValuesChanged, final long pValuesUnresolved, final List<String> pExamples)
		{
			this(pKind, pArgument, pConfidence, pNote.english(), pNote, pValuesSeen, pValuesChanged, pValuesUnresolved, pExamples);
		}

		private StepProposal(final String pKind, final String pArgument, final int pConfidence, final String pRationale, final NoteTexts.Note pNote,
			final long pValuesSeen, final long pValuesChanged, final long pValuesUnresolved, final List<String> pExamples)
		{
			this.note = pNote;
			this.kind = pKind;
			this.argument = pArgument;
			this.confidence = pConfidence;
			this.rationale = pRationale;
			this.valuesSeen = pValuesSeen;
			this.valuesChanged = pValuesChanged;
			this.valuesUnresolved = pValuesUnresolved;
			this.examples = pExamples;
		}

		/** The rationale as it is stored: the English, and the marker of its note when it has one, within {@code pMax} characters. */
		public String stored(final int pMax)
		{
			return this.note == null ? com.on.ebx.factory.landing.TextLimits.truncate(this.rationale, pMax) : this.note.stored(pMax);
		}
	}

	// -------------------------------------------------------------------------------------------------------- rules

	/**
	 * @param pValues distinct values of the column, with row counts - a SAMPLE, capped by the caller ({@code
	 * ebx.QualitySuggester.DEFAULT_MAX_DISTINCT_VALUES}), most-frequent-first.
	 * @param pExactDistinctCount the column's TRUE distinct-value count, read by EXACT SQL ({@code
	 * ebx.QualitySuggester#exactAggregates}) - never derived from {@code pValues} (coordinator review,
	 * 2026-09-21: a count/ratio computed on the capped sample is simply wrong at volume). {@code pValues} is
	 * flagged SAMPLED when {@code pExactDistinctCount > pValues.size()} - every proposal's rationale then says
	 * so explicitly and its confidence is capped at {@link #SAMPLED_MAX_CONFIDENCE}. A negative value means
	 * "unknown" (only a caller that already knows {@code pValues} is the WHOLE column - typically a test fixture
	 * - may pass this; see the 2-arg overload).
	 */
	public static List<StepProposal> propose(final List<ValueCount> pValues, final ColumnContext pColumn, final long pExactDistinctCount)
	{
		return Detectors.propose(pValues, pColumn, pExactDistinctCount, 0L);
	}

	/**
	 * Staging polish (2026-10-17): the same, for values read on a SAMPLE OF ROWS ({@code pSampleRows} &gt; 0, {@link ScanGuard}): always flagged sampled, and
	 * the rationale says "on a sample of N rows" instead of "the most frequent distinct values", since the rows beyond the sample were not looked at at all.
	 */
	public static List<StepProposal> propose(final List<ValueCount> pValues, final ColumnContext pColumn, final long pExactDistinctCount, final long pSampleRows)
	{
		final List<StepProposal> out = new ArrayList<StepProposal>();
		if (pValues == null || pValues.isEmpty() || !Detectors.isTextLike(pColumn.inferredType))
		{
			return out;
		}
		final long totalSeen = Detectors.sumCounts(pValues);
		if (totalSeen == 0)
		{
			return out;
		}
		final boolean sampled = pSampleRows > 0L || pExactDistinctCount >= 0 && pExactDistinctCount > pValues.size();
		final NoteTexts.Note suffix = pSampleRows > 0L ? Detectors.rowSampleNote(pSampleRows) : Detectors.sampledNote(sampled, pValues.size(), pExactDistinctCount);

		// CHAINED, in the same order the pipeline would actually run at transfer time (docs/NORMALISATION.md sec
		// 3.2: MappingStep.position, ascending) - each family's OWN evidence is computed on what the PRECEDING
		// families would already have done to the value, not on the untouched raw value a second time.
		List<ValueCount> working = pValues;
		working = Detectors.proposeTrim(working, totalSeen, sampled, suffix, out);
		working = Detectors.proposeCollapseSpaces(working, totalSeen, sampled, suffix, out);
		Detectors.proposeEmptyToNull(working, totalSeen, sampled, suffix, out);
		return out;
	}

	/** Backward-compatible 2-arg form: treats {@code pValues} as the whole column, never flagged sampled - only safe when the caller already knows that (e.g. a test fixture smaller than any real cap). Prefer the 3-arg form wherever an exact distinct count is available. */
	public static List<StepProposal> propose(final List<ValueCount> pValues, final ColumnContext pColumn)
	{
		return Detectors.propose(pValues, pColumn, -1L);
	}

	// --------------------------------------------------------------------------------------------- whitespace/case

	/** @return the chained working values for the NEXT family in the pipeline - see {@link #propose}'s own Javadoc on chaining. A null entry (already nulled by an earlier step) is passed through unchanged, never re-examined. */
	private static List<ValueCount> proposeTrim(final List<ValueCount> pValues, final long pTotalSeen, final boolean pSampled, final NoteTexts.Note pSuffix,
		final List<StepProposal> pOut)
	{
		long changed = 0;
		final List<String> examples = new ArrayList<String>();
		final List<ValueCount> working = new ArrayList<ValueCount>(pValues.size());
		for (final ValueCount vc : pValues)
		{
			if (vc.value == null)
			{
				working.add(vc);
				continue;
			}
			final String trimmed = vc.value.trim();
			if (!trimmed.equals(vc.value))
			{
				changed += vc.count;
				Detectors.addExample(examples, CleaningSteps.TRIM, vc.value, trimmed);
			}
			working.add(trimmed.equals(vc.value) ? vc : new ValueCount(trimmed, vc.scopeValue, vc.count));
		}
		if (changed > 0)
		{
			pOut.add(new StepProposal(CleaningSteps.TRIM, null, Detectors.cappedConfidence(100, pSampled),
				NoteTexts.Note.of(NoteTexts.R_TRIM, Long.valueOf(changed), Long.valueOf(pTotalSeen)).addAll(pSuffix), pTotalSeen, changed, 0, examples));
		}
		return working;
	}

	/** @return the chained working values for the NEXT family - see {@link #propose}'s own Javadoc on chaining. */
	private static List<ValueCount> proposeCollapseSpaces(final List<ValueCount> pValues, final long pTotalSeen, final boolean pSampled, final NoteTexts.Note pSuffix,
		final List<StepProposal> pOut)
	{
		long changed = 0;
		final List<String> examples = new ArrayList<String>();
		final List<ValueCount> working = new ArrayList<ValueCount>(pValues.size());
		for (final ValueCount vc : pValues)
		{
			if (vc.value == null)
			{
				working.add(vc);
				continue;
			}
			// pValues is already TRIM's own output by the time this runs; .trim() here is defensive, not load-bearing
			final String base = vc.value.trim();
			final String collapsed = base.replaceAll("\\s+", " ");
			if (!collapsed.equals(base))
			{
				changed += vc.count;
				Detectors.addExample(examples, CleaningSteps.COLLAPSE_SPACES, vc.value, collapsed);
			}
			working.add(collapsed.equals(vc.value) ? vc : new ValueCount(collapsed, vc.scopeValue, vc.count));
		}
		if (changed > 0)
		{
			pOut.add(new StepProposal(CleaningSteps.COLLAPSE_SPACES, null, Detectors.cappedConfidence(100, pSampled),
				NoteTexts.Note.of(NoteTexts.R_COLLAPSE, Long.valueOf(changed), Long.valueOf(pTotalSeen)).addAll(pSuffix), pTotalSeen, changed, 0, examples));
		}
		return working;
	}

	// ------------------------------------------------------------------------------------------------- empty-ish

	/** Proposes {@code EMPTY_TO_NULL} on {@code pValues} (already {@code TRIM}/{@code COLLAPSE_SPACES}'s own output) - see {@link #propose}'s own Javadoc on chaining. */
	private static void proposeEmptyToNull(final List<ValueCount> pValues, final long pTotalSeen, final boolean pSampled, final NoteTexts.Note pSuffix,
		final List<StepProposal> pOut)
	{
		long changed = 0;
		final List<String> matchedSentinels = new ArrayList<String>();
		// the MATCHED SENTINEL TOKEN itself (never the surrounding real column value) is safe to show as an
		// example: it is drawn from the small, fixed, published EMPTY_SENTINELS list - see the class Javadoc's
		// REAL_VALUE_KINDS note
		final List<String> examples = new ArrayList<String>();
		for (final ValueCount vc : pValues)
		{
			if (vc.value == null)
			{
				continue;
			}
			final String trimmed = vc.value.trim();
			for (final String sentinel : Detectors.EMPTY_SENTINELS)
			{
				if (trimmed.equalsIgnoreCase(sentinel))
				{
					changed += vc.count;
					matchedSentinels.add("'" + vc.value + "'");
					if (examples.size() < 5 && !examples.contains(vc.value))
					{
						examples.add(vc.value);
					}
					break;
				}
			}
		}
		if (changed > 0)
		{
			final NoteTexts.Note rationale = NoteTexts.Note.of(NoteTexts.R_EMPTY_TO_NULL, Long.valueOf(changed), Long.valueOf(pTotalSeen), Detectors.join(matchedSentinels))
				.addAll(pSuffix);
			// already capped at 70 regardless of sampling (the collision-caution cap, unchanged) - cappedConfidence
			// is still applied for documentation symmetry with the other two families, but is a no-op here since
			// 70 < SAMPLED_MAX_CONFIDENCE
			pOut.add(new StepProposal(CleaningSteps.EMPTY_TO_NULL, null, Detectors.cappedConfidence(70, pSampled), rationale, pTotalSeen, changed, 0, examples));
		}
	}

	// ------------------------------------------------------------------------------------------------------ helpers

	/** Package-visible: {@code QualityLlmPass} shares the same "blank/unknown type counts as STRING" convention ({@code StagingModel}/{@code MasterModel} apply the identical rule elsewhere in this codebase) for its own PATTERN/CHECK_DIGIT type guard. */
	public static boolean isTextLike(final String pInferredType)
	{
		final String type = pInferredType == null ? "" : pInferredType.trim().toUpperCase(Locale.ROOT);
		return type.isEmpty() || "STRING".equals(type);
	}

	private static long sumCounts(final List<ValueCount> pValues)
	{
		long total = 0;
		for (final ValueCount vc : pValues)
		{
			total += vc.count;
		}
		return total;
	}

	/** "" when not sampled; otherwise a sentence naming exactly how many distinct values were read against how many exist - never silently implying the sample was the whole column. Package-visible: {@code QualityLlmPass} builds the same sentence for its own evidence. */
	static String sampledSuffix(final boolean pSampled, final int pValuesRead, final long pExactDistinctCount)
	{
		final NoteTexts.Note note = Detectors.sampledNote(pSampled, pValuesRead, pExactDistinctCount);
		return note.isEmpty() ? "" : " " + note.english();
	}

	/** The coded form of {@link #sampledSuffix}: empty note when the values are the whole column. */
	static NoteTexts.Note sampledNote(final boolean pSampled, final int pValuesRead, final long pExactDistinctCount)
	{
		return pSampled ? NoteTexts.Note.of(NoteTexts.R_SAMPLE_DISTINCT, Integer.valueOf(pValuesRead), Long.valueOf(pExactDistinctCount)) : new NoteTexts.Note();
	}

	/** Staging polish (2026-10-17): the sentence of a measure made on the first rows of a big table only - never implying the rest was looked at. */
	public static String rowSampleSuffix(final long pSampleRows)
	{
		return " " + Detectors.rowSampleNote(pSampleRows).english();
	}

	/** The coded form of {@link #rowSampleSuffix}. */
	public static NoteTexts.Note rowSampleNote(final long pSampleRows)
	{
		return NoteTexts.Note.of(NoteTexts.R_SAMPLE_ROWS, String.format(java.util.Locale.ROOT, "%,d", Long.valueOf(pSampleRows)));
	}

	/** Package-visible: {@code QualityLlmPass} shares the same ceiling for its own sampled evidence. */
	static int cappedConfidence(final int pConfidence, final boolean pSampled)
	{
		return pSampled ? Math.min(pConfidence, Detectors.SAMPLED_MAX_CONFIDENCE) : pConfidence;
	}

	/** {@code pKind} decides real value vs {@link Shapes shaped} - see {@link #REAL_VALUE_KINDS} and the class Javadoc. Never both: exactly one of the two ever reaches {@code pExamples}. Package-visible: {@code QualityLlmPass} shares the same allow-list discipline for its own evidence. */
	static void addExample(final List<String> pExamples, final String pKind, final String pBefore, final String pAfter)
	{
		if (pExamples.size() >= 5)
		{
			return;
		}
		pExamples.add(Detectors.REAL_VALUE_KINDS.contains(pKind) ? pBefore + " -> " + pAfter : Shapes.pair(pBefore, pAfter));
	}

	private static String join(final List<String> pValues)
	{
		final StringBuilder out = new StringBuilder();
		for (final String value : pValues)
		{
			if (out.length() > 0)
			{
				out.append(", ");
			}
			out.append(value);
		}
		return out.toString();
	}
}
