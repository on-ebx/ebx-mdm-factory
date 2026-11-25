package com.on.ebx.factory.quality;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Executes one accepted-or-applied {@code QualityRule} against one already-typed value, at transfer time
 * (review-quality lot, task C, docs/NORMALISATION.md sec 2): runs AFTER a mapping's cleaning pipeline and its
 * own {@code AttributeMapping.transformation} (`ebx.StagingLoader#planRow`'s own ordering - see its Javadoc),
 * never before. Pure: no EBX class - {@code ebx.StagingLoader} supplies the lookups ({@link
 * CleaningSteps.Lookups}, the exact same ones the cleaning pipeline already loads once per run) and the
 * already-typed value, and only writes the counters this class's callers compute.
 * <p>
 * <b>Kinds executed here: {@code REFERENCE}, {@code CHECK_DIGIT}, {@code PATTERN}</b> - the three the task
 * scoped for per-VALUE execution. {@code UNIQUE} is deliberately NOT one of them: it needs the whole load's
 * worth of values for one entity to decide anything ("does this value repeat"), not a single value in
 * isolation - see {@link #duplicatesAmong} for that one, a separate, POST-PASS computation over the values
 * {@code StagingLoader} already wrote this run. {@code MANDATORY} is not executed either: never proposed by
 * {@code QualityDetectors} today (only a manual edit could create one), and the attribute-level
 * {@code mandatory} facet already covers the same ground through {@code
 * com.on.ebx.factory.transfer.PipelineCompat#checkConstraints} (target-mapping lot 1: retired the per-field
 * {@code transfer.Transformations#checkConstraints} it replaced) - treated as a no-op ({@link #passes}
 * answers {@code true}) rather than silently duplicating that check. {@code MODEL_CONSTRAINT} is never
 * executed at transfer at all (it is meant to become a real model facet or foreign key - a later, not-yet-
 * built lot, see {@code ebx.StagingGenerator}'s own report of it instead); the caller (task C's wiring in
 * {@code StagingLoader}) must never construct a {@link Rule} for one.
 * <p>
 * <b>An empty value never fails any rule here</b> - the same "empty is not a reject" convention {@link
 * com.on.ebx.factory.transfer.PipelineCompat} already uses one layer down: whether a value is REQUIRED is
 * the {@code mandatory} facet's job, not a quality rule's.
 */
public final class QualityRuleCheck
{
	private QualityRuleCheck()
	{
	}

	/** One accepted-or-applied {@code QualityRule}, down to what execution needs - {@code id}/{@code kind}/{@code argument}/{@code severity} only; the entity/attribute scoping is the caller's own bookkeeping (which field this rule applies to), not carried here. */
	public static final class Rule
	{
		public final String id;
		/** {@link QualityDetectors#REFERENCE}/{@link QualityDetectors#CHECK_DIGIT}/{@link QualityDetectors#PATTERN}/{@link QualityDetectors#UNIQUE}/{@link QualityDetectors#MANDATORY} - only the first three are evaluated by {@link #passes}; see the class Javadoc. */
		public final String kind;
		public final String argument;
		/** {@code REJECT}/{@code WARN} - a {@code MODEL_CONSTRAINT} row must never reach this class (the caller filters it out before it is even read for execution). */
		public final String severity;
		/**
		 * Volume lot 3 (2026-09-28, docs/VOLUME.md task 7): a {@link QualityDetectors#PATTERN} rule's own
		 * {@link #argument} compiled ONCE here, at construction - never per row/per value any more (a rule is
		 * read once per run, {@code ebx.StagingLoader.readQualityRules}, and reused for every row of every feed
		 * that touches its field). {@code null} for a non-{@code PATTERN} rule, a blank argument, or an argument
		 * that fails to compile ({@link PatternSyntaxException}) - {@link #matchesPattern} already treats both
		 * "nothing to compile" cases identically (always passes), so collapsing them into one {@code null}
		 * sentinel changes no behaviour, only when the cost of finding out is paid.
		 */
		private final Pattern compiledPattern;
		/**
		 * Why this PATTERN rule cannot validate anything ({@link RegexGuard#compileStored}: too long, a syntax error), or {@code null}: a rule with such a pattern is NOT VALIDATED, which is not the same as passing - {@link #verdict}
		 * answers {@link Verdict#NOT_VALIDATED} for it and the caller reports it. A blank argument is "nothing to check" (passes), not a problem.
		 */
		private final String patternProblem;
		/** Evaluations of this rule abandoned after the deadline; at {@link #MAX_TIMEOUTS} the rule stops evaluating (a pattern that times out on one value will on the next). */
		private final java.util.concurrent.atomic.AtomicInteger timeouts = new java.util.concurrent.atomic.AtomicInteger();

		/** Desk lot L4: the staging field (attribute name) whose produced value decides whether the rule applies, or {@code null} (always applies). */
		public final String conditionField;
		/** The values of {@link #conditionField} under which the rule applies (compared trimmed, case-insensitive); empty with a null field. */
		public final List<String> conditionValues;

		public Rule(final String pId, final String pKind, final String pArgument, final String pSeverity)
		{
			this(pId, pKind, pArgument, pSeverity, null, null);
		}

		/**
		 * Desk lot L4: a rule that applies only to rows whose {@code pConditionField} value is one of {@code pConditionValues}. A blank field or an empty value list means no
		 * condition. A {@code MODEL_CONSTRAINT} rule never reaches this class and can never carry a condition.
		 */
		public Rule(final String pId, final String pKind, final String pArgument, final String pSeverity, final String pConditionField, final List<String> pConditionValues)
		{
			this.id = pId;
			this.kind = pKind;
			this.argument = pArgument;
			this.severity = pSeverity;
			Pattern compiled = null;
			String problem = null;
			if (QualityDetectors.PATTERN.equals(pKind) && pArgument != null && !pArgument.trim().isEmpty())
			{
				try
				{
					compiled = RegexGuard.compileStored(pArgument); // a rule a person accepted: length cap and deadline only, never the shape refusal
				}
				catch (final PatternSyntaxException ex)
				{
					problem = ex instanceof RegexGuard.Refused ? ex.getMessage() : "not a valid regular expression: " + ex.getDescription();
				}
			}
			this.compiledPattern = compiled;
			this.patternProblem = problem;
			final boolean conditional = pConditionField != null && !pConditionField.trim().isEmpty() && pConditionValues != null && !pConditionValues.isEmpty();
			this.conditionField = conditional ? pConditionField.trim() : null;
			this.conditionValues = conditional ? java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(pConditionValues))
				: java.util.Collections.<String> emptyList();
		}

		/** Whether this rule carries a condition. */
		public boolean hasCondition()
		{
			return this.conditionField != null;
		}

		/**
		 * Whether the rule applies to a row whose condition field holds {@code pConditionFieldValue} (the produced staging value). Without a condition: always. With one: only when
		 * the value is present and is one of the listed values; a row whose condition value is empty is outside the condition, never flagged by it.
		 */
		public boolean appliesTo(final String pConditionFieldValue)
		{
			return !hasCondition() || QualityRuleCheck.valueIn(pConditionFieldValue, this.conditionValues);
		}

		/** Why the pattern of this rule cannot be evaluated (refused or invalid), the timeout notice once it kept timing out, or {@code null} when nothing is wrong. */
		public String problem()
		{
			if (this.patternProblem != null)
			{
				return this.patternProblem;
			}
			return this.timeouts.get() >= QualityRuleCheck.MAX_TIMEOUTS ? "the pattern repeatedly exceeded its time limit of " + RegexGuard.DEFAULT_BUDGET_MILLIS
				+ " ms on a value" : null;
		}

		/** How many evaluations of this rule were abandoned after the deadline. */
		public int timeoutCount()
		{
			return this.timeouts.get();
		}

		/**
		 * {@code UNIQUE} is WARN-only by construction wherever this codebase proposes it ({@code
		 * QualityLlmPass.Session#proposeQualityRule} refuses {@code REJECT} for every kind, {@code UNIQUE}
		 * included) - but the task's own instruction is explicit ("WARN only, never REJECT") rather than
		 * trusting the stored field blindly for this one kind, since nothing stops a row from being hand-edited
		 * over Data Services into {@code REJECT}: a uniqueness hint is evidence for Match and Merge, never
		 * grounds to drop a whole row of otherwise-valid staging data on its own (the same reasoning
		 * docs/NORMALISATION.md sec 2.1 already gives for why {@code sourceSystem + sourceId}, not a business
		 * key, is the staging primary key).
		 */
		public boolean isRejectSeverity()
		{
			return QualityDetectors.REJECT.equals(this.severity) && !QualityDetectors.UNIQUE.equals(this.kind);
		}
	}

	/**
	 * @param pValue the already-typed, already-cleaned value (post cleaning pipeline, post {@code
	 *            AttributeMapping.transformation}) - a non-{@link String} value (a parsed date/decimal/boolean)
	 *            never fails {@code REFERENCE}/{@code CHECK_DIGIT}/{@code PATTERN} (those are shape checks over
	 *            text; {@code StagingRules} never pairs one with a non-STRING attribute in the first place, so
	 *            this is a defensive no-op, not a real case)
	 * @return {@code true} when the value passes (or the rule kind is not one this class executes - see the
	 *         class Javadoc), {@code false} when it fails
	 */
	public static boolean passes(final Rule pRule, final Object pValue, final CleaningSteps.Lookups pLookups)
	{
		return QualityRuleCheck.verdict(pRule, pValue, pLookups) != Verdict.FAIL;
	}

	/** Desk lot L4: whether {@code pValue} (a produced staging value) is one of {@code pValues}, trimmed and case-insensitive; an empty or missing value is in none. */
	public static boolean valueIn(final String pValue, final List<String> pValues)
	{
		if (pValue == null || pValue.trim().isEmpty() || pValues == null)
		{
			return false;
		}
		final String value = pValue.trim();
		for (final String allowed : pValues)
		{
			if (allowed != null && allowed.trim().equalsIgnoreCase(value))
			{
				return true;
			}
		}
		return false;
	}

	/** What one rule said about one value: a value a rule could not examine is {@code NOT_VALIDATED}, never a pass. */
	public enum Verdict
	{
		PASS, FAIL,
		/** The rule's pattern is refused or invalid, or timed out: nothing was checked for this value. */
		NOT_VALIDATED
	}

	/** After this many abandoned evaluations a rule stops evaluating its pattern for the rest of its life (one run). */
	static final int MAX_TIMEOUTS = 3;

	/**
	 * {@link #passes} with the third answer: {@link Verdict#NOT_VALIDATED} when a {@code PATTERN} rule's pattern was refused or is invalid, when its
	 * evaluation on this value exceeded the time limit, or when it already did {@link #MAX_TIMEOUTS} times. {@link #passes} counts that as a pass (a
	 * defect of the model's pattern never blocks a transfer) - a caller that reports quality results should use this and report the rule ({@link
	 * Rule#problem}).
	 */
	public static Verdict verdict(final Rule pRule, final Object pValue, final CleaningSteps.Lookups pLookups)
	{
		if (!(pValue instanceof String))
		{
			return Verdict.PASS;
		}
		final String value = ((String) pValue).trim();
		if (value.isEmpty())
		{
			return Verdict.PASS;
		}
		if (QualityDetectors.REFERENCE.equals(pRule.kind))
		{
			return pLookups != null && pLookups.resolveFlat(pRule.argument, value) != null ? Verdict.PASS : Verdict.FAIL;
		}
		if (QualityDetectors.CHECK_DIGIT.equals(pRule.kind))
		{
			return QualityDetectors.isCheckDigitValid(pRule.argument, value) ? Verdict.PASS : Verdict.FAIL;
		}
		if (QualityDetectors.PATTERN.equals(pRule.kind))
		{
			return QualityRuleCheck.matchesPattern(pRule, value);
		}
		return Verdict.PASS; // UNIQUE/MANDATORY/an unrecognised future kind: not this method's job - see the class Javadoc
	}

	/**
	 * A blank argument has nothing to check (passes); a refused or invalid pattern, or one that keeps timing out, validates nothing
	 * ({@link Verdict#NOT_VALIDATED}, see {@link Rule#problem}); otherwise the match runs under {@link RegexGuard}'s deadline.
	 */
	private static Verdict matchesPattern(final Rule pRule, final String pValue)
	{
		if (pRule.compiledPattern == null)
		{
			return pRule.patternProblem == null ? Verdict.PASS : Verdict.NOT_VALIDATED;
		}
		if (pRule.timeouts.get() >= QualityRuleCheck.MAX_TIMEOUTS)
		{
			return Verdict.NOT_VALIDATED;
		}
		try
		{
			return RegexGuard.matches(pRule.compiledPattern, pValue.toUpperCase(Locale.ROOT)) ? Verdict.PASS : Verdict.FAIL;
		}
		catch (final RegexGuard.MatchTimeout ex)
		{
			pRule.timeouts.incrementAndGet();
			return Verdict.NOT_VALIDATED;
		}
	}

	// ------------------------------------------------------------------------------------------------------ unique

	/** One row's evidence for a {@code UNIQUE} check: the value actually staged, and the business key ({@code sourceSystem|sourceId}, EBX's own composite-key format) a person would use to find it. */
	public static final class RowValue
	{
		public final String sourceSystemAndId;
		public final String value;

		public RowValue(final String pSourceSystemAndId, final String pValue)
		{
			this.sourceSystemAndId = pSourceSystemAndId;
			this.value = pValue;
		}
	}

	/** {@link #duplicatesAmong}'s answer: how many of the entity's rows shared a value with at least one other row this load, and up to {@code pMaxExamples} of their keys - see the class Javadoc for why {@code UNIQUE} needs a whole-load pass rather than a per-row check. */
	public static final class UniqueOutcome
	{
		public final long checked;
		public final long failing;
		public final List<String> exampleKeys;

		UniqueOutcome(final long pChecked, final long pFailing, final List<String> pExampleKeys)
		{
			this.checked = pChecked;
			this.failing = pFailing;
			this.exampleKeys = pExampleKeys;
		}
	}

	/**
	 * Groups {@code pRows} by their own value (case/accent/punctuation-insensitive, {@link
	 * CleaningSteps#normalizeKey} - the exact same key both the cleaning pipeline and {@code QualityDetectors}'
	 * own proposal evidence already use, so a value found "duplicated" here is one that would have been
	 * detected the same way when this rule was first proposed) and counts every row whose value is shared by at
	 * least one other row. A blank/null value is never counted (nothing to compare - the same "empty is not a
	 * reject" rule as {@link #passes}). Pure: {@code ebx.StagingLoader} supplies {@code pRows} from a POST-PASS
	 * read of the entity's own just-staged rows for the attribute (see its own Javadoc for why this cannot be a
	 * per-row check the way {@code REFERENCE}/{@code CHECK_DIGIT}/{@code PATTERN} are).
	 */
	public static UniqueOutcome duplicatesAmong(final List<RowValue> pRows, final int pMaxExamples)
	{
		final Map<String, java.util.List<String>> byNormalisedValue = new LinkedHashMap<String, java.util.List<String>>();
		long checked = 0;
		for (final RowValue row : pRows)
		{
			if (row.value == null || row.value.trim().isEmpty())
			{
				continue;
			}
			checked++;
			final String key = CleaningSteps.normalizeKey(row.value);
			java.util.List<String> keys = byNormalisedValue.get(key);
			if (keys == null)
			{
				keys = new java.util.ArrayList<String>();
				byNormalisedValue.put(key, keys);
			}
			keys.add(row.sourceSystemAndId);
		}
		long failing = 0;
		final java.util.List<String> examples = new java.util.ArrayList<String>();
		for (final java.util.List<String> keys : byNormalisedValue.values())
		{
			if (keys.size() <= 1)
			{
				continue;
			}
			failing += keys.size();
			for (final String key : keys)
			{
				if (examples.size() < pMaxExamples)
				{
					examples.add(key);
				}
			}
		}
		return new UniqueOutcome(checked, failing, examples);
	}
}
