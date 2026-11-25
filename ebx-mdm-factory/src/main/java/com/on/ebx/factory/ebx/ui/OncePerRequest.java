package com.on.ebx.factory.ebx.ui;

/**
 * F2-107 (robustness audit 2026-10-23): one expensive reading per request. The wizard's actions that only read (then at most queue a job) {@link #get keep} what they read, and the display
 * that follows {@link #take takes} it instead of reading a second time; an action that writes before the page is drawn simply does not use {@link #get} and the display reads fresh.
 * {@link #clear} at the start of every action means a value never outlives its request. {@link #reads} counts the real readings (what the test checks).
 */
final class OncePerRequest<T>
{
	/** The reading itself. */
	interface Reader<T>
	{
		T read();
	}

	private T kept;
	private int reads;

	/** The kept value, read now (and kept) when there is none. */
	T get(final Reader<T> pReader)
	{
		if (this.kept == null)
		{
			this.kept = this.reads(pReader);
		}
		return this.kept;
	}

	/** The kept value, removed, or a fresh reading when there is none. */
	T take(final Reader<T> pReader)
	{
		final T value = this.kept;
		this.kept = null;
		return value != null ? value : this.reads(pReader);
	}

	/** A reading that is not kept (the caller writes next, so the value would be stale). */
	T fresh(final Reader<T> pReader)
	{
		return this.reads(pReader);
	}

	void clear()
	{
		this.kept = null;
	}

	int reads()
	{
		return this.reads;
	}

	private T reads(final Reader<T> pReader)
	{
		this.reads++;
		return pReader.read();
	}
}
