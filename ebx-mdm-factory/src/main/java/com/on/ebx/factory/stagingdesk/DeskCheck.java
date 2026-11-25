package com.on.ebx.factory.stagingdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.on.ebx.factory.quality.FailureBreakdown;
import com.on.ebx.factory.quality.QualityDetectors;

/**
 * Validation desk lot L4 (docs/STAGING-DESK.md): one quality rule as the desk's check view shows it - the stored facts of its {@code QualityRule} row, read by {@code
 * ebx.DeskCheckReader} - and the arithmetic of its three choices. Plain data, no EBX type, unit-tested in {@code DeskCheckTest}; {@link DeskCheckHtml} draws it.
 * <p>
 * Nothing is measured here: {@link #seen}/{@link #failing} are the study's {@code valuesSeen}/{@code valuesFailing}, {@link #breakdownJson} the stored {@code failureBreakdown}
 * ({@link FailureBreakdown}), and the figures of the condition are the breakdown's own groups added up - so "how many values the rule would flag with the condition" is exactly the
 * failing count of the groups the condition names.
 */
public final class DeskCheck
{
	public final String ruleId;
	public final String entityName;
	public final String attributeName;
	/** {@code QualityRule.kind}: PATTERN, REFERENCE, CHECK_DIGIT, MANDATORY or UNIQUE. */
	public final String kind;
	public final String argument;
	public final String severity;
	public final String rationale;
	/** {@code valuesSeen} / {@code valuesFailing} of the study, or {@code null} when the rule was never measured. */
	public final Long seen;
	public final Long failing;
	/** {@code QualityRule.failureBreakdown}: blank = never measured, the marker = measured with nothing to show, else the breakdown. */
	public final String breakdownJson;
	/** The attribute the rule is conditioned on and its values; "" and empty for an unconditional rule. */
	public final String conditionAttribute;
	public final List<String> conditionValues;
	/** A "Measure" job of this rule is queued or running. */
	public boolean measuring;

	public DeskCheck(final String pRuleId, final String pEntityName, final String pAttributeName, final String pKind, final String pArgument, final String pSeverity,
		final String pRationale, final Long pSeen, final Long pFailing, final String pBreakdownJson, final String pConditionAttribute, final List<String> pConditionValues)
	{
		this.ruleId = pRuleId;
		this.entityName = pEntityName == null ? "" : pEntityName;
		this.attributeName = pAttributeName == null ? "" : pAttributeName;
		this.kind = pKind == null ? "" : pKind;
		this.argument = pArgument == null ? "" : pArgument;
		this.severity = pSeverity == null ? "" : pSeverity;
		this.rationale = pRationale == null ? "" : pRationale;
		this.seen = pSeen;
		this.failing = pFailing;
		this.breakdownJson = pBreakdownJson == null ? "" : pBreakdownJson;
		this.conditionAttribute = pConditionAttribute == null ? "" : pConditionAttribute;
		this.conditionValues = pConditionValues == null ? Collections.<String> emptyList() : new ArrayList<String>(pConditionValues);
	}

	/** The study measured the rule on the landing values. */
	public boolean measured()
	{
		return this.seen != null && this.seen.longValue() > 0;
	}

	public long seenCount()
	{
		return this.seen == null ? 0L : this.seen.longValue();
	}

	public long failingCount()
	{
		return this.failing == null ? 0L : this.failing.longValue();
	}

	public long passingCount()
	{
		return Math.max(0L, this.seenCount() - this.failingCount());
	}

	/** A rule the transfer evaluates per value, i.e. one whose failures can be broken down. */
	public boolean breakable()
	{
		return QualityDetectors.PATTERN.equals(this.kind) || QualityDetectors.REFERENCE.equals(this.kind) || QualityDetectors.CHECK_DIGIT.equals(this.kind);
	}

	/** Measured and nothing fails: nothing to condition, the rule is simply safe. */
	public boolean alreadySafe()
	{
		return this.measured() && this.failingCount() == 0L;
	}

	/** The stored breakdown, or {@code null} (never measured, or measured with nothing to show). */
	public FailureBreakdown.Result breakdown()
	{
		return FailureBreakdown.parse(this.breakdownJson);
	}

	/** The breakdown was measured (stored, possibly empty). */
	public boolean breakdownMeasured()
	{
		return FailureBreakdown.measured(this.breakdownJson);
	}

	/** The "Measure" button is offered: a rule that can be broken down, that fails somewhere, and was never measured for it. */
	public boolean canMeasure()
	{
		return this.breakable() && !this.breakdownMeasured() && !this.alreadySafe();
	}

	public boolean hasCondition()
	{
		return !this.conditionAttribute.isEmpty() && !this.conditionValues.isEmpty();
	}

	/**
	 * {seen, failing} within the proposed condition, from the breakdown's groups; {@code null} when the rule has no condition or the stored breakdown is by another field (it then
	 * says nothing about the condition).
	 */
	public long[] withinCondition()
	{
		final FailureBreakdown.Result breakdown = this.breakdown();
		if (!this.hasCondition() || breakdown == null || !breakdown.attribute.equalsIgnoreCase(this.conditionAttribute))
		{
			return null;
		}
		return breakdown.within(this.conditionValues);
	}

	/** How many correct-looking values the rule flags as proposed (without the condition): everything it fails. */
	public long flaggedAsProposed()
	{
		return this.failingCount();
	}

	/** Groups where some values fail, and the sum of their failing values: for "48 would be flagged . 7 groups". */
	public int failingGroups()
	{
		final FailureBreakdown.Result breakdown = this.breakdown();
		int count = 0;
		if (breakdown != null)
		{
			for (final FailureBreakdown.Group group : breakdown.groups)
			{
				count += group.failing > 0 ? 1 : 0;
			}
		}
		return count;
	}

	/** The groups where nothing fails, as "FR, DE", for the "passes" chip. */
	public String cleanGroups()
	{
		final FailureBreakdown.Result breakdown = this.breakdown();
		return breakdown == null ? "" : String.join(", ", breakdown.cleanValues());
	}
}
