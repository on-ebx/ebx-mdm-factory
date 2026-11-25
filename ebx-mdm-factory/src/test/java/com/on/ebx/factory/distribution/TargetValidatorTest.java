package com.on.ebx.factory.distribution;

import com.on.ebx.factory.distribution.TargetValidator.Result;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class TargetValidatorTest
{
	public static void main(final String[] pArgs)
	{
		TargetValidatorTest.validRealtimeTarget();
		TargetValidatorTest.validScheduledTarget();
		TargetValidatorTest.blankConnectorFails();
		TargetValidatorTest.blankOperationFails();
		TargetValidatorTest.unknownModeFails();
		TargetValidatorTest.scheduledWithoutCronFails();
		TargetValidatorTest.onDemandWithCronFails();
		TargetValidatorTest.unknownScopeFails();
		TargetValidatorTest.blankScopeIsAllowed();
		System.out.println("TargetValidatorTest OK");
	}

	private static void validRealtimeTarget()
	{
		final Result r = TargetValidator.validate("snowflake_crm", "upsertGolden", "REALTIME", "", "CHANGED");
		TargetValidatorTest.check("a well-formed REALTIME target is valid: " + r.errors, r.valid);
	}

	private static void validScheduledTarget()
	{
		final Result r = TargetValidator.validate("snowflake_crm", "upsertGolden", "SCHEDULED", "0 0 * * *", "ALL");
		TargetValidatorTest.check("a well-formed SCHEDULED target is valid: " + r.errors, r.valid);
	}

	private static void blankConnectorFails()
	{
		final Result r = TargetValidator.validate("", "upsertGolden", "ON_DEMAND", "", "CHANGED");
		TargetValidatorTest.check("a blank connector fails", !r.valid);
	}

	private static void blankOperationFails()
	{
		final Result r = TargetValidator.validate("snowflake_crm", "", "ON_DEMAND", "", "CHANGED");
		TargetValidatorTest.check("a blank operation fails", !r.valid);
	}

	private static void unknownModeFails()
	{
		final Result r = TargetValidator.validate("snowflake_crm", "upsertGolden", "WHENEVER", "", "CHANGED");
		TargetValidatorTest.check("an unknown mode fails", !r.valid);
	}

	private static void scheduledWithoutCronFails()
	{
		final Result r = TargetValidator.validate("snowflake_crm", "upsertGolden", "SCHEDULED", "", "CHANGED");
		TargetValidatorTest.check("SCHEDULED without a cron fails", !r.valid);
	}

	private static void onDemandWithCronFails()
	{
		final Result r = TargetValidator.validate("snowflake_crm", "upsertGolden", "ON_DEMAND", "0 0 * * *", "CHANGED");
		TargetValidatorTest.check("ON_DEMAND with a cron fails", !r.valid);
	}

	private static void unknownScopeFails()
	{
		final Result r = TargetValidator.validate("snowflake_crm", "upsertGolden", "ON_DEMAND", "", "SOME");
		TargetValidatorTest.check("an unknown scope fails", !r.valid);
	}

	private static void blankScopeIsAllowed()
	{
		final Result r = TargetValidator.validate("snowflake_crm", "upsertGolden", "REALTIME", "", "");
		TargetValidatorTest.check("a blank scope is allowed (REALTIME ignores it): " + r.errors, r.valid);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
