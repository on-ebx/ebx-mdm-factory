package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.stagingdesk.DeskCheck;
import com.on.ebx.factory.stagingdesk.DeskPreview;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;

/**
 * The EBX-bound half of the Validation desk's check view (docs/STAGING-DESK.md L4): reads the {@code QualityRule} rows behind the checks of a group - the study's figures, the
 * stored failure breakdown, the proposed condition - into {@link DeskCheck}, and the conditions of rules for the preview of real rows ({@link DeskPreview.RuleCondition}).
 * One lookup per rule and per condition attribute; nothing is scanned.
 */
public final class DeskCheckReader
{
	private DeskCheckReader()
	{
	}

	/**
	 * The checks of {@code pRuleIds}, by rule id. {@code pNames} gives, per rule id, {entity name, attribute name} as the desk already knows them. A rule that does not exist any more
	 * is left out.
	 */
	public static Map<String, DeskCheck> read(final Adaptation pFactory, final String pProjectId, final Map<String, String[]> pNames)
	{
		final Map<String, DeskCheck> out = new LinkedHashMap<String, DeskCheck>();
		final Map<String, String> attributeNames = new HashMap<String, String>();
		for (final Map.Entry<String, String[]> entry : pNames.entrySet())
		{
			final Adaptation rule = pFactory.getTable(FactoryPaths.QUALITY_RULE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(entry.getKey()));
			if (rule == null)
			{
				continue;
			}
			final String conditionId = rule.getString(FactoryPaths.QualityRule.CONDITION_ATTRIBUTE);
			final DeskCheck check = new DeskCheck(entry.getKey(), entry.getValue()[0], entry.getValue()[1], rule.getString(FactoryPaths.QualityRule.KIND),
				rule.getString(FactoryPaths.QualityRule.ARGUMENT), rule.getString(FactoryPaths.QualityRule.SEVERITY), rule.getString(FactoryPaths.QualityRule.RATIONALE),
				DeskCheckReader.longOf(rule, FactoryPaths.QualityRule.VALUES_SEEN), DeskCheckReader.longOf(rule, FactoryPaths.QualityRule.VALUES_FAILING),
				rule.getString(FactoryPaths.QualityRule.FAILURE_BREAKDOWN), DeskCheckReader.attributeName(pFactory, conditionId, attributeNames),
				DeskCheckReader.values(rule));
			check.measuring = RuleBreakdownRun.measuring(pFactory, pProjectId, entry.getKey());
			out.put(entry.getKey(), check);
		}
		return out;
	}

	/** Rule id to condition, for the rules of {@code pRuleIds} that carry a complete one (the attribute resolved, at least one value). */
	public static Map<String, DeskPreview.RuleCondition> conditions(final Adaptation pFactory, final Collection<String> pRuleIds)
	{
		final Map<String, DeskPreview.RuleCondition> out = new HashMap<String, DeskPreview.RuleCondition>();
		final Map<String, String> attributeNames = new HashMap<String, String>();
		for (final String ruleId : pRuleIds)
		{
			final Adaptation rule = pFactory.getTable(FactoryPaths.QUALITY_RULE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(ruleId));
			if (rule == null)
			{
				continue;
			}
			final String name = DeskCheckReader.attributeName(pFactory, rule.getString(FactoryPaths.QualityRule.CONDITION_ATTRIBUTE), attributeNames);
			final List<String> values = DeskCheckReader.values(rule);
			if (!name.isEmpty() && !values.isEmpty())
			{
				out.put(ruleId, new DeskPreview.RuleCondition(name, values));
			}
		}
		return out;
	}

	private static String attributeName(final Adaptation pFactory, final String pAttributeId, final Map<String, String> pCache)
	{
		if (pAttributeId == null || pAttributeId.trim().isEmpty())
		{
			return "";
		}
		if (pCache.containsKey(pAttributeId))
		{
			return pCache.get(pAttributeId);
		}
		final Adaptation attribute = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pAttributeId));
		final String name = attribute == null ? "" : attribute.getString(FactoryPaths.StagingAttribute.NAME);
		pCache.put(pAttributeId, name);
		return name;
	}

	private static List<String> values(final Adaptation pRule)
	{
		final List<String> out = new ArrayList<String>();
		final List<?> raw = pRule.getList(FactoryPaths.QualityRule.CONDITION_VALUES);
		if (raw != null)
		{
			for (final Object value : raw)
			{
				out.add(String.valueOf(value));
			}
		}
		return out;
	}

	private static Long longOf(final Adaptation pRule, final com.orchestranetworks.schema.Path pPath)
	{
		final Object value = pRule.get(pPath);
		return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
	}
}
