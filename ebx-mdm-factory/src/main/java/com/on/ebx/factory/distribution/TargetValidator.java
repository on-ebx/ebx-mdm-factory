package com.on.ebx.factory.distribution;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.factory.core.FactoryPaths;

/**
 * Distribution lot 1 (engine): validates a {@code DistributionTarget}'s own configuration fields before
 * {@code rest.FactoryResource}'s upsert route writes the record - EBX's own static enumeration on
 * {@code mode}/{@code scope} is non-blocking (CLAUDE.md sec 2: "an out-of-enumeration value is silently
 * accepted and stored"), so a wrong value would otherwise sit unnoticed until a send is attempted. Pure -
 * no EBX class.
 */
public final class TargetValidator
{
	private TargetValidator()
	{
	}

	/** The validation outcome: {@link #valid} is true only when {@link #errors} is empty. */
	public static final class Result
	{
		public final boolean valid;
		public final List<String> errors;

		Result(final List<String> pErrors)
		{
			this.errors = pErrors;
			this.valid = pErrors.isEmpty();
		}
	}

	public static Result validate(final String pConnector, final String pOperation, final String pMode, final String pCron, final String pScope)
	{
		final List<String> errors = new ArrayList<String>();
		if (TargetValidator.blank(pConnector))
		{
			errors.add("connector is required");
		}
		if (TargetValidator.blank(pOperation))
		{
			errors.add("operation is required");
		}
		final boolean modeKnown = FactoryPaths.DistributionTarget.MODE_REALTIME.equals(pMode) || FactoryPaths.DistributionTarget.MODE_ON_DEMAND.equals(pMode)
			|| FactoryPaths.DistributionTarget.MODE_SCHEDULED.equals(pMode);
		if (TargetValidator.blank(pMode) || !modeKnown)
		{
			errors.add("mode must be REALTIME, ON_DEMAND or SCHEDULED");
		}
		else
		{
			final boolean scheduled = FactoryPaths.DistributionTarget.MODE_SCHEDULED.equals(pMode);
			if (scheduled && TargetValidator.blank(pCron))
			{
				errors.add("cron is required when mode is SCHEDULED");
			}
			if (!scheduled && !TargetValidator.blank(pCron))
			{
				errors.add("cron must be empty unless mode is SCHEDULED");
			}
		}
		if (!TargetValidator.blank(pScope) && !"CHANGED".equals(pScope) && !"ALL".equals(pScope))
		{
			errors.add("scope must be CHANGED or ALL");
		}
		return new Result(errors);
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}
}
