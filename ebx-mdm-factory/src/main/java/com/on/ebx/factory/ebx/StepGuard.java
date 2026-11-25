package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * The guarantee that a pipeline step ends: whatever is thrown between {@code PipelineSteps.begin} and the step's normal end - an {@link OperationException} nobody
 * handled, a {@link RuntimeException} out of a lookup or a parse, an {@link Error} such as a {@code LinkageError} or a {@code VerifyError} after a version upgrade -
 * the step row ends {@code FAILED} with the reason and the stack goes to the log, instead of staying {@code RUNNING} until the next restart marks it INTERRUPTED (every
 * reader of the step then believes the work is still going on, and reset and deletion refuse). The step bodies call {@link #endUnexpected} from a {@code catch} that
 * rethrows: the exception still reaches the caller, only the bookkeeping is made right.
 * <p>
 * It only ends a step that is still {@code RUNNING}: a body that already ended its step (a normal end, then something failing in what follows) keeps the outcome it
 * recorded. The decision is {@link #handle}, on an {@link Ender} so that it is unit-tested with a fake step.
 */
final class StepGuard
{
	private StepGuard()
	{
	}

	/** What the guard needs from a step: whether it is still RUNNING, how to end it FAILED, and where to write the stack. */
	interface Ender
	{
		boolean isRunning();

		void endFailed(String pMessage, String pDetailJson);

		void log(String pMessage, Throwable pCause);
	}

	/** The step's FAILED message for {@code pCause}: an {@link OperationException}'s own message (an expected refusal), else the class and message of what broke. */
	static String message(final Throwable pCause)
	{
		if (pCause instanceof OperationException)
		{
			final String text = pCause.getMessage();
			return text == null || text.isEmpty() ? "Failed: " + pCause.getClass().getName() : text;
		}
		return "Unexpected error: " + pCause.getClass().getName() + (pCause.getMessage() == null ? "" : ": " + pCause.getMessage());
	}

	/**
	 * Ends the step FAILED when it is still RUNNING and logs the stack of an unexpected failure; never throws, whatever the ender does (a bookkeeping failure must not
	 * replace the exception the caller is about to receive).
	 *
	 * @return true when this call ended the step
	 */
	static boolean handle(final Ender pEnder, final Throwable pCause, final String pDetailJson)
	{
		try
		{
			if (!(pCause instanceof OperationException))
			{
				pEnder.log("pipeline step failed unexpectedly: " + pCause, pCause);
			}
			if (!pEnder.isRunning())
			{
				return false;
			}
			pEnder.endFailed(StepGuard.message(pCause), pDetailJson);
			return true;
		}
		catch (final RuntimeException | LinkageError secondary)
		{
			return false;
		}
	}

	/** {@link #handle} on a real {@code PipelineStep} row; call it from a {@code catch} that rethrows. */
	static boolean endUnexpected(final Session pSession, final Adaptation pFactory, final String pStepId, final Throwable pCause, final String pDetailJson)
	{
		return StepGuard.handle(new Ender()
		{
			@Override
			public boolean isRunning()
			{
				final Adaptation row = pFactory.getTable(FactoryPaths.PIPELINE_STEP).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pStepId));
				return row != null && "RUNNING".equals(row.getString(FactoryPaths.PipelineStep.STATUS));
			}

			@Override
			public void endFailed(final String pMessage, final String pDetail)
			{
				PipelineSteps.end(pSession, pFactory, pStepId, false, pMessage, pDetail);
			}

			@Override
			public void log(final String pMessage, final Throwable pThrowable)
			{
				LoggingCategory.getKernel().error("[mdm-factory] " + pMessage + " (step " + pStepId + ")", pThrowable);
			}
		}, pCause, pDetailJson);
	}
}
