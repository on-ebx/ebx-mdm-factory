package com.on.ebx.factory.staging;

/**
 * Staging-live lot (2026-09-27, docs/mockups/staging-live/): what {@code ebx.StagingLoader} tells the running
 * screen while it loads - one cell per business object and application, real row counts after each batch.
 * Counters are cumulative for the cell, never deltas. {@link #NONE} for every caller nobody watches.
 */
public interface StagingLoadListener
{
	StagingLoadListener NONE = new StagingLoadListener()
	{
		@Override
		public void planned(final String pEntity, final String pSource)
		{
			// nobody is watching
		}

		@Override
		public void started(final String pEntity, final String pSource, final long pTotalRows)
		{
			// nobody is watching
		}

		@Override
		public void progress(final String pEntity, final String pSource, final long pRead, final long pWritten, final long pHeld)
		{
			// nobody is watching
		}

		@Override
		public void ended(final String pEntity, final String pSource, final boolean pOk)
		{
			// nobody is watching
		}

		@Override
		public void note(final String pText)
		{
			// nobody is watching
		}
	};

	/** A cell the load will reach (declared before any row is read). */
	void planned(String pEntity, String pSource);

	/** The cell starts; {@code pTotalRows} the landing rows to read, -1 when not known (a child entity). */
	void started(String pEntity, String pSource, long pTotalRows);

	void progress(String pEntity, String pSource, long pRead, long pWritten, long pHeld);

	/**
	 * Lot V3-S (2026-10-04): the same, with the rows found already up to date (read, compared, not written) - what a scope {@code all} load of unchanged
	 * data reports instead of a screen full of "0 written". A listener that does not care keeps the five-argument form: this one forwards to it.
	 */
	default void progress(final String pEntity, final String pSource, final long pRead, final long pWritten, final long pUnchanged, final long pHeld)
	{
		this.progress(pEntity, pSource, pRead, pWritten, pHeld);
	}

	void ended(String pEntity, String pSource, boolean pOk);

	/** A phase of the load that belongs to no cell ("Checking uniqueness on Personne."). */
	void note(String pText);
}
