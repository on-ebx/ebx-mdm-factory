package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.MergeLogState;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Lot S2 (2026-10-18): the one reader/writer of {@code DistributionTarget.mergeLogWatermark} for its two users, {@link MergeLogWatcher} and
 * {@link MatchingStateSweeper}. Each change is a read-modify-write of the whole text against the CURRENT value of the field, so one user never drops
 * what the other stored beside its own part ({@link MergeLogState}).
 */
final class MergeLogStateStore
{
	/** One change to the parsed state. */
	interface Edit
	{
		void apply(MergeLogState pState);
	}

	private MergeLogStateStore()
	{
	}

	static MergeLogState read(final Adaptation pTarget)
	{
		return MergeLogState.parse(pTarget.getString(FactoryPaths.DistributionTarget.MERGE_LOG_WATERMARK));
	}

	/** True when the field was written (or already held the edited text). Logged, never thrown. */
	static boolean update(final Session pSession, final Adaptation pFactory, final String pTargetId, final Edit pEdit, final LoggingCategory pLog)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final AdaptationTable targets = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET);
				final Adaptation target = targets.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTargetId));
				if (target == null)
				{
					return;
				}
				final MergeLogState state = MergeLogStateStore.read(target);
				pEdit.apply(state);
				final String json = state.format();
				if (json.equals(target.getString(FactoryPaths.DistributionTarget.MERGE_LOG_WATERMARK)))
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(target.getAdaptationName());
				value.setValue(json, FactoryPaths.DistributionTarget.MERGE_LOG_WATERMARK);
				pContext.doModifyContent(target, value);
			}
		});
		if (result.hasFailed() && pLog != null)
		{
			pLog.error("[mdm-factory] merge log state: could not store it for target " + pTargetId + "; the next pass decides the same again (at-least-once, the dispatcher folds duplicates): "
				+ result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		return !result.hasFailed();
	}
}
