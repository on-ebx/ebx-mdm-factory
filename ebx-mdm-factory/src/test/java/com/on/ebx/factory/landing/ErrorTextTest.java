package com.on.ebx.factory.landing;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link ErrorText} (audit F-23). */
public final class ErrorTextTest
{
	public static void main(final String[] pArgs)
	{
		ErrorTextTest.check("a message is kept as is", "boom".equals(ErrorText.of(new IllegalStateException("boom"))));
		ErrorTextTest.check("a NullPointerException is no longer 'null'", "java.lang.NullPointerException".equals(ErrorText.of(new NullPointerException())));
		ErrorTextTest.check("a blank message reads as none", "java.lang.IllegalStateException".equals(ErrorText.of(new IllegalStateException("  "))));
		final String wrapped = ErrorText.of(new RuntimeException((String) null, new java.io.IOException("disk full")));
		ErrorTextTest.check("a message-less wrapper names its cause: " + wrapped, wrapped.contains("RuntimeException") && wrapped.contains("IOException: disk full"));
		ErrorTextTest.check("a null throwable never throws", ErrorText.of(null).length() > 0);
		ErrorTextTest.check("a RuntimeException(Throwable) already carries its cause's text as its message: kept", "java.io.IOException: x".equals(ErrorText.of(new RuntimeException(new java.io.IOException("x")))));
		ErrorTextTest.check("an Error is named too", "java.lang.StackOverflowError".equals(ErrorText.of(new StackOverflowError())));
		System.out.println("ErrorTextTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
