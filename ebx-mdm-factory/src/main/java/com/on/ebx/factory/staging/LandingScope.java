package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 2026-10-12 (fix/scope-value-and-landing-selected): the meaning of {@code LandingTable.selected} ("In scope?") for the staging passes. Measured live that
 * day on project Vol100kN: a stray landing table set to {@code selected = false} before {@code POST /projects/{code}/staging/suggest} was still counted
 * ({@code landingTables: 6}) and given a rule entity, and the language-model pass then had to reason about it. The field already took a table out of the
 * landing generation, the landing flows, the column profiling, the distribution and the intake (see {@code docs/SOURCES-LANDING-LANDING.md}); the two
 * staging passes read every row of the table. They now read only the in-scope rows - a table is in scope unless the flag is explicitly {@code false}
 * (a row written before the field existed reads null, and stays in scope, like everywhere else in the module).
 * <p>
 * Pure: no EBX class, unit-tested ({@code LandingScopeTest}).
 */
public final class LandingScope
{
	private LandingScope()
	{
	}

	/** {@code true} unless the raw value of {@code LandingTable.selected} is {@code Boolean.FALSE}; null (never set) stays in scope. */
	public static boolean isInScope(final Object pRawSelected)
	{
		return !Boolean.FALSE.equals(pRawSelected);
	}

	/**
	 * Whether a rule-proposed entity still belongs in the language-model pass's inventory: its landing table (key {@code <sourceSystem>.<table>}) must be one of
	 * the in-scope tables the pass reads. A rule entity written by an earlier run for a table that has since left the scope stays in the factory dataset (nothing
	 * deletes it) but is not offered to the model.
	 */
	public static boolean keepsRuleEntity(final Set<String> pInScopeTableKeys, final StagingLlmPass.RuleEntityInfo pRuleEntity)
	{
		return pRuleEntity != null && pInScopeTableKeys.contains(pRuleEntity.landingTableKey());
	}

	/** The rule entities whose landing table is in scope, in their original order. */
	public static List<StagingLlmPass.RuleEntityInfo> keepInScope(final Set<String> pInScopeTableKeys, final List<StagingLlmPass.RuleEntityInfo> pRuleEntities)
	{
		final List<StagingLlmPass.RuleEntityInfo> out = new ArrayList<StagingLlmPass.RuleEntityInfo>();
		for (final StagingLlmPass.RuleEntityInfo entity : pRuleEntities)
		{
			if (LandingScope.keepsRuleEntity(pInScopeTableKeys, entity))
			{
				out.add(entity);
			}
		}
		return out;
	}
}
