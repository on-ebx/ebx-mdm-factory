package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Proposes ONE derived {@code StagingAttribute} (name {@code "<sourceName>Valid"}, {@code derivation}
 * {@code VALID_VALUE}) per attribute that carries at least one accepted VALIDITY quality rule ({@code
 * REFERENCE}/{@code CHECK_DIGIT}/{@code PATTERN} - never {@code UNIQUE}, which is a whole-load duplicate
 * question, not a per-value shape/reference check) whose measured validity is BELOW 100% on at least one
 * source system - lot 1, "quality-informed survivorship" ({@code docs/MATCHING.md} &sect;23, {@code PLAN.md}).
 * <p>
 * <b>Why this exists, and what problem it solves.</b> {@code docs/MATCHING.md} &sect;14/&sect;15 already let a
 * merge policy prefer a SOURCE over another for a whole field ({@code most trusted source}) or grab the
 * LONGEST value - but neither can express "take whichever contributing record's value actually PASSES this
 * field's own quality rule, empty if none does": the add-on's merge-function vocabulary
 * ({@code matching_config_schema}'s own {@code defaultFieldMergeFunctionChain}/{@code survivors[].
 * mergeFunctionChain} enum) has no conditional/predicate function at all (CLAUDE.md &sect;7's "no PREDICATE
 * node" restriction is the matching-tree analogue of the same limitation). The derived-field mechanism
 * {@code quality.CrossSystemFold} already established (a technical field computed once at load time, sitting
 * NEXT TO the display attribute, never replacing its stored value) is reused verbatim: a {@code *Valid} field
 * holds the source value UNCHANGED when it passes every one of the attribute's own validity rules, or
 * {@code null} otherwise ({@code staging.DerivedFieldRule#apply}) - a plain {@code longest} survivor rule on
 * THAT field then always prefers a passing value over an empty one (an empty string/null is never the
 * "longest" candidate once at least one contributing record has a real value) without the add-on ever needing
 * to evaluate the rule itself. See {@code master.MatchingPolicyTranslator#addBusinessSurvivors}' own Javadoc
 * for the survivor side of this (lot 1, task E) - a {@code *Valid} field ALWAYS gets an explicit,
 * non-overridable {@code longest}-only survivor, next to (never instead of) the technical/source-key ones.
 * <p>
 * <b>Same measured-not-guessed discipline as {@link CrossSystemFold}, one gate removed.</b> Structural only:
 * an attribute's own accepted {@code QualityRule}s decide whether this class even looks at it (the caller,
 * {@code ebx.QualitySuggester}, reads them once per attribute via {@code ebx.StagingLoader#readQualityRules} -
 * the SAME reader {@code ebx.StagingLoader.planRow} itself uses at transfer time, so "carries a validity rule"
 * always means exactly what actually runs on load). Unlike {@link CrossSystemFold}, this class does NOT
 * require two or more source systems: a single-source attribute whose one contributing system is only 80%
 * valid still benefits from a {@code *Valid} field (the golden record then shows the clean value, or an
 * honest empty, instead of silently keeping an invalid one) - unproven at real project scale but a deliberate,
 * documented scoping choice (lot 1 design note, not a limitation carried over from {@link CrossSystemFold} by
 * accident).
 * <p>
 * <b>Measured on the SAME accepted-pipeline-applied sample {@link CrossSystemFold} itself measures</b> - this
 * class takes {@link CrossSystemFold.SystemValues} directly (never a second, differently-shaped input): {@code
 * ebx.QualitySuggester} already builds one {@code SystemValues} per mapping of an attribute (after that
 * mapping's own accepted {@code MappingStep} pipeline, via {@link CrossSystemFold#applyAcceptedPipeline}) for
 * the fold-key pass, and this class reuses it verbatim rather than re-reading the landing data. A rule is
 * evaluated on the value AS IT WOULD ACTUALLY BE STAGED (post-cleaning, pre-quality-check - exactly {@code
 * StagingLoader.planRow}'s own ordering, {@code docs/NORMALISATION.md} sec 2), never on raw landing text.
 * <p>
 * <b>Bounded the same way</b>: {@link CrossSystemFold.SystemValues#values} is already the caller's own
 * capped, most-frequent-first sample ({@code ebx.QualitySuggester.DEFAULT_MAX_DISTINCT_VALUES}) - this class
 * never issues its own query and never invents a different cap.
 */
public final class ValidValueDerivation
{
	/** {@code StagingAttribute.derivation} value this class proposes - the sibling of {@link CrossSystemFold#DERIVATION_FOLD_KEY}. */
	public static final String DERIVATION_VALID_VALUE = "VALID_VALUE";

	/**
	 * Deliberately below {@link CrossSystemFold}'s own 85: this verdict rests on the SAME sampled evidence, plus
	 * a second, independent judgement (does the value pass the rule) layered on top - one more thing that could
	 * be wrong about a small sample than a plain coincidence count.
	 */
	private static final int BASE_CONFIDENCE = 75;

	private ValidValueDerivation()
	{
	}

	// ------------------------------------------------------------------------------------------------------- output

	/**
	 * One {@code VALID_VALUE} derived-attribute proposal - what {@code ebx.QualitySuggester} needs to write a
	 * single new {@code StagingAttribute} row (name, evidence); everything about WHERE it is written (entity,
	 * {@code derivedFrom}, {@code maxLength} copied from the source attribute, label) is the caller's own
	 * concern, exactly like {@link CrossSystemFold.AttributeProposal}.
	 */
	public static final class AttributeProposal
	{
		/** {@code "<sourceName>Valid"} - never checked for collision here (see the caller's own idempotence/uniqueness handling, the same as {@link CrossSystemFold.AttributeProposal#suggestedName}). */
		public final String suggestedName;
		public final int confidence;
		public final String rationale;

		public AttributeProposal(final String pSuggestedName, final int pConfidence, final String pRationale)
		{
			this.suggestedName = pSuggestedName;
			this.confidence = pConfidence;
			this.rationale = pRationale;
		}
	}

	// ------------------------------------------------------------------------------------------------------- rules

	/**
	 * @param pByMapping one entry per accepted mapping of ONE staging attribute - the SAME list {@code
	 *            ebx.QualitySuggester} already built for {@link CrossSystemFold#propose} on this attribute; may
	 *            be a single entry (single-source attribute - see class Javadoc on why this is never gated on
	 *            system count, unlike {@link CrossSystemFold}).
	 * @param pRules the attribute's own accepted VALIDITY rules ({@code ebx.StagingLoader#readQualityRules}'s
	 *            own output - already filtered to {@code REFERENCE}/{@code CHECK_DIGIT}/{@code PATTERN}); empty
	 *            or {@code null}: nothing to test, always answers no proposal.
	 * @param pColumn the attribute's own type/name context - {@link Detectors.ColumnContext#inferredType} gates
	 *            text-like only (a validity rule is a shape/reference check over text, same convention every
	 *            detector in this package uses), {@link Detectors.ColumnContext#stagingAttributeName} names the
	 *            derived attribute ({@code "<name>Valid"}).
	 * @return zero or one {@link AttributeProposal} - a {@code List} only for symmetry with {@link
	 *         CrossSystemFold#propose} (0 or 1 element, never more).
	 */
	public static List<AttributeProposal> propose(final List<CrossSystemFold.SystemValues> pByMapping, final List<QualityRuleCheck.Rule> pRules,
		final CleaningSteps.Lookups pLookups, final Detectors.ColumnContext pColumn)
	{
		final List<AttributeProposal> out = new ArrayList<AttributeProposal>();
		if (pByMapping == null || pByMapping.isEmpty() || pRules == null || pRules.isEmpty() || pColumn == null || !Detectors.isTextLike(pColumn.inferredType))
		{
			return out;
		}
		final List<ValidityAggregator.DistinctValue> distinctValues = new ArrayList<ValidityAggregator.DistinctValue>();
		boolean anySampled = false;
		int mappingIndex = 0;
		for (final CrossSystemFold.SystemValues system : pByMapping)
		{
			anySampled = anySampled || system.sampled;
			final String systemKey = system.systemId == null || system.systemId.trim().isEmpty() ? "?mapping" + mappingIndex : system.systemId.trim();
			mappingIndex++;
			if (system.values == null)
			{
				continue;
			}
			for (final Detectors.ValueCount valueCount : system.values)
			{
				if (valueCount.value == null || valueCount.value.trim().isEmpty())
				{
					continue;
				}
				distinctValues.add(new ValidityAggregator.DistinctValue(systemKey, valueCount.value.trim(), valueCount.count));
			}
		}
		final Map<String, ValidityAggregator.SystemValidity> bySystem = ValidityAggregator.aggregate(distinctValues, pRules, pLookups);
		if (bySystem.isEmpty())
		{
			return out; // no non-blank sampled value at all on any system - nothing measured, nothing to gain
		}
		boolean anyGain = false;
		final StringBuilder perSystemClause = new StringBuilder();
		for (final Map.Entry<String, ValidityAggregator.SystemValidity> entry : bySystem.entrySet())
		{
			final ValidityAggregator.SystemValidity validity = entry.getValue();
			if (validity.checked <= 0)
			{
				continue;
			}
			if (perSystemClause.length() > 0)
			{
				perSystemClause.append("; ");
			}
			perSystemClause.append(entry.getKey()).append(": ").append(validity.valid).append('/').append(validity.checked).append(" valid");
			if (validity.valid < validity.checked)
			{
				anyGain = true;
			}
		}
		if (!anyGain)
		{
			return out; // every system is already 100% valid on the sample measured - nothing for a *Valid field to fix
		}

		final String suffix = anySampled ? " Measured on a sample of each system's most frequent distinct values, capped the same way as every other cleaning-step "
			+ "proposal." : "";
		final String ruleNames = ValidValueDerivation.ruleNames(pRules);
		final String rationale = "Fails its own " + ruleNames + " rule on at least one source system (" + perSystemClause + ") - a field carrying the value "
			+ "only when it passes, empty otherwise, lets the merge policy prefer a clean value over an invalid one with a plain 'longest' rule." + suffix;
		final int confidence = Detectors.cappedConfidence(ValidValueDerivation.BASE_CONFIDENCE, anySampled);
		final String suggestedName = (pColumn.stagingAttributeName == null ? "value" : pColumn.stagingAttributeName) + "Valid";

		out.add(new AttributeProposal(suggestedName, confidence, rationale));
		return out;
	}

	private static String ruleNames(final List<QualityRuleCheck.Rule> pRules)
	{
		final StringBuilder out = new StringBuilder();
		for (final QualityRuleCheck.Rule rule : pRules)
		{
			if (out.length() > 0)
			{
				out.append('/');
			}
			out.append(rule.kind);
		}
		return out.toString();
	}
}
