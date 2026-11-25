package com.on.ebx.factory.landing;

/** JUnit-free: run the main, a failure throws. */
public final class RowCountFallbackTest
{
	public static void main(final String[] pArgs)
	{
		RowCountFallbackTest.check("lastLoadRows wins when both are set",
			Long.valueOf(180L).equals(RowCountFallback.pick(Long.valueOf(180L), Long.valueOf(200L))));
		RowCountFallbackTest.check("sourceRowCount is the fallback when lastLoadRows is null (never loaded yet)",
			Long.valueOf(200L).equals(RowCountFallback.pick(null, Long.valueOf(200L))));
		RowCountFallbackTest.check("lastLoadRows alone is enough (sample-file source, no catalog scan)",
			Long.valueOf(180L).equals(RowCountFallback.pick(Long.valueOf(180L), null)));
		RowCountFallbackTest.check("neither known yet gives null", RowCountFallback.pick(null, null) == null);
		RowCountFallbackTest.check("a lastLoadRows of 0 is still preferred over a stale sourceRowCount (an empty load is a real answer)",
			Long.valueOf(0L).equals(RowCountFallback.pick(Long.valueOf(0L), Long.valueOf(200L))));

		System.out.println("RowCountFallbackTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
