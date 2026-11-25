package com.on.ebx.factory.ebx;

import com.on.ebx.factory.landing.KeyPrimeRunner;

/** JUnit-free: run the main, a failure throws. F2-15: a procedure that fails (result or exception) is counted on the outcome and not swallowed. */
public final class KeyPrimerTest
{
	public static void main(final String[] pArgs)
	{
		final KeyPrimeRunner.Outcome ok = new KeyPrimeRunner.Outcome();
		KeyPrimerTest.check("a write that worked is not a failure", KeyPrimer.record("x", new KeyPrimer.Attempt()
		{
			@Override
			public String run()
			{
				return null;
			}
		}, ok) && ok.failed == 0);

		final KeyPrimeRunner.Outcome refused = new KeyPrimeRunner.Outcome();
		final boolean refusedResult = KeyPrimer.record("x", new KeyPrimer.Attempt()
		{
			@Override
			public String run()
			{
				return "message is too long";
			}
		}, refused);
		KeyPrimerTest.check("a procedure result that failed is reported", !refusedResult && refused.failed == 1);

		final KeyPrimeRunner.Outcome thrown = new KeyPrimeRunner.Outcome();
		final boolean thrownResult = KeyPrimer.record("x", new KeyPrimer.Attempt()
		{
			@Override
			public String run()
			{
				throw new IllegalStateException("boom");
			}
		}, thrown);
		KeyPrimerTest.check("a RuntimeException is reported, not swallowed", !thrownResult && thrown.failed == 1);
		System.out.println("KeyPrimerTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
