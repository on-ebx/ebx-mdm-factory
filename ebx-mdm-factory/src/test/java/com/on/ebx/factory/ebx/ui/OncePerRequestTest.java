package com.on.ebx.factory.ebx.ui;

/**
 * JUnit-free: run the main, a failure throws. F2-107: a render is one reading - an action that only reads keeps its snapshot for the display that follows, a plain display
 * reads once, nothing outlives its request.
 */
public final class OncePerRequestTest
{
	public static void main(final String[] pArgs)
	{
		final int[] counter = new int[1];
		final OncePerRequest.Reader<String> reader = new OncePerRequest.Reader<String>()
		{
			@Override
			public String read()
			{
				counter[0]++;
				return "snapshot-" + counter[0];
			}
		};
		final OncePerRequest<String> once = new OncePerRequest<String>();

		// a plain display: one reading
		OncePerRequestTest.check("a display with no action reads once", "snapshot-1".equals(once.take(reader)) && once.reads() == 1);

		// a read-only action (even two helpers of it) then the display: still ONE reading, and the display gets the very object the action read
		once.clear();
		final String forAction = once.get(reader);
		OncePerRequestTest.check("a second helper of the same action reads nothing", forAction == once.get(reader) && once.reads() == 2);
		OncePerRequestTest.check("the display takes what the action read, no second snapshot", forAction == once.take(reader) && once.reads() == 2);

		// consumed: the next display (a poll) reads again, it never serves the old value
		OncePerRequestTest.check("a value is consumed by its display", !forAction.equals(once.take(reader)) && once.reads() == 3);

		// an action that writes first reads fresh, and the display then reads again (fresh is not kept)
		once.clear();
		once.fresh(reader);
		OncePerRequestTest.check("a reading made before a write is not kept for the display", once.take(reader) != null && once.reads() == 5);

		// the start of the next request drops whatever an aborted one left
		once.clear();
		final String left = once.get(reader);
		once.clear();
		OncePerRequestTest.check("clear drops a value left by an earlier request", !left.equals(once.take(reader)));

		// the wizard wires it this way
		System.out.println("OncePerRequestTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
