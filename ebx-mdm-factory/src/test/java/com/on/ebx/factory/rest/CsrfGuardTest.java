package com.on.ebx.factory.rest;

/** JUnit-free: run the main, a failure throws. {@link CsrfGuard} (security review, 2026-09-24): which methods are checked. */
public final class CsrfGuardTest
{
	public static void main(final String[] pArgs)
	{
		for (final String method : new String[] { "POST", "put", "PATCH", "DELETE" })
		{
			CsrfGuardTest.check(method + " is checked", CsrfGuard.mustCheck(method));
		}
		for (final String method : new String[] { "GET", "HEAD", "OPTIONS", null })
		{
			CsrfGuardTest.check(method + " is not checked", !CsrfGuard.mustCheck(method));
		}
		System.out.println("CsrfGuardTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
