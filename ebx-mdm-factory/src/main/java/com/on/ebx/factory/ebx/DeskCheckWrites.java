package com.on.ebx.factory.ebx;

import java.util.ArrayList;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Validation desk lot L4: the one write of the check view that is not an ordinary decision - "Accept as proposed" of a rule that carries a PROPOSED condition drops that condition
 * first (the person chose the rule without it), then the matrix' own handler accepts the row. Runs with the person's own session, so EBX checks the write; the rule must be the
 * project's and still PROPOSED (a rule already decided keeps the condition it was decided with).
 */
public final class DeskCheckWrites
{
	public static final String OK = "";
	public static final String NOT_FOUND = "NOT_FOUND";
	public static final String NOT_PROPOSED = "NOT_PROPOSED";
	public static final String FAILED = "FAILED";

	private DeskCheckWrites()
	{
	}

	/** {@link #OK} when the condition is gone (or there was none), else the reason code. */
	public static String clearCondition(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pRuleId)
	{
		final Adaptation rule = pRuleId == null || pRuleId.isEmpty() ? null : pFactory.getTable(FactoryPaths.QUALITY_RULE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRuleId));
		final Adaptation entity = rule == null ? null
			: pFactory.getTable(FactoryPaths.STAGING_ENTITY).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(rule.getString(FactoryPaths.QualityRule.ENTITY)));
		if (entity == null || !pProjectId.equals(entity.getString(FactoryPaths.StagingEntity.PROJECT)))
		{
			return DeskCheckWrites.NOT_FOUND;
		}
		if (!"PROPOSED".equals(rule.getString(FactoryPaths.QualityRule.STATUS)))
		{
			return DeskCheckWrites.NOT_PROPOSED;
		}
		final String conditionAttribute = rule.getString(FactoryPaths.QualityRule.CONDITION_ATTRIBUTE);
		if (conditionAttribute == null || conditionAttribute.trim().isEmpty())
		{
			return DeskCheckWrites.OK;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(rule.getAdaptationName());
				value.setValue(null, FactoryPaths.QualityRule.CONDITION_ATTRIBUTE);
				value.setValue(new ArrayList<String>(), FactoryPaths.QualityRule.CONDITION_VALUES);
				pContext.doModifyContent(rule, value);
			}
		});
		return result.hasFailed() ? DeskCheckWrites.FAILED : DeskCheckWrites.OK;
	}
}
