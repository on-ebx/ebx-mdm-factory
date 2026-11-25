package com.on.ebx.factory.master;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides what the {@code MASTER_MODEL} step still has to do, from what a read-only look at the data-model
 * server already found under the master model's name - the same three-state pattern
 * {@code staging.StagingModelState} already established for the staging zone (nothing found -&gt; build,
 * embed, publish; a model but no publication -&gt; embed and publish only; both, tables matching -&gt; nothing
 * to do; both, tables not matching -&gt; a resumable REGENERATE, driven by {@code staging.StagingModelDiff}
 * exactly like staging's own regeneration).
 * <p>
 * Deliberately NOT reused as-is (a separate, near-identical class rather than a generic
 * {@code ModelState<T>}): the refusal wording and the caller ({@code ebx.MasterGenerator} vs.
 * {@code ebx.StagingGenerator}) differ, and the coordinator's brief for this generator asks for
 * {@code master.MasterModelState} by name, alongside "reuse of the diff approach" for the one piece that
 * genuinely has no reason to differ ({@code StagingModelDiff}, generalised - see its own Javadoc). Pure: no
 * EBX or MCP class.
 */
public final class MasterModelState
{
	private MasterModelState()
	{
	}

	/** What a read-only lookup of the model name found. */
	public enum Found
	{
		/** No data model of that name exists. */
		NOTHING,
		/** The model exists; not published under that name (or a publication row exists but carries no version yet). */
		MODEL_ONLY,
		/** The model exists and is published under that name. */
		MODEL_AND_PUBLICATION
	}

	/** What {@code MasterGenerator} must still call, or why it must stop instead. */
	public static final class Decision
	{
		public final boolean build;
		/** Apply the facets {@code build_data_model} cannot carry (maxLength, and the id field's auto-increment) - true whenever the model is not yet published. */
		public final boolean patch;
		public final boolean publish;
		/** True when an existing model's table set differs from the promoted entities: the caller must compute a {@code StagingModelDiff} and drive additive-only regeneration itself. */
		public final boolean regenerate;
		/** Non-null only when generation must stop outright (no existing tables reported at all - the server answer itself is unusable, not merely stale). */
		public final String refusal;

		private Decision(final boolean pBuild, final boolean pPatch, final boolean pPublish, final boolean pRegenerate, final String pRefusal)
		{
			this.build = pBuild;
			this.patch = pPatch;
			this.publish = pPublish;
			this.regenerate = pRegenerate;
			this.refusal = pRefusal;
		}

		public boolean ok()
		{
			return this.refusal == null;
		}
	}

	/**
	 * @param pPlannedTablePaths {@code /root/<Entity>} of every table this run's promoted entities would build; never null
	 * @param pExistingTablePaths the same, as the server reports the existing model's tables; null or empty when {@code pFound} is {@code NOTHING}
	 */
	public static Decision decide(final Found pFound, final String pModelName, final String pProjectCode, final List<String> pPlannedTablePaths,
		final List<String> pExistingTablePaths)
	{
		if (pFound == Found.NOTHING)
		{
			return new Decision(true, true, true, false, null);
		}
		if (pFound == Found.MODEL_AND_PUBLICATION)
		{
			// unconditional, same reasoning as StagingModelState: a matching table-path SET does not mean the
			// field-level shape still matches too - MasterGenerator's own StagingModelDiff-based regeneration
			// decides UP_TO_DATE vs. a real change, this class does not
			return new Decision(false, false, false, true, null);
		}
		// Found.MODEL_ONLY from here: narrower behaviour, same as StagingModelState
		if (pExistingTablePaths == null)
		{
			return new Decision(false, false, false, false,
				"The master model of project " + pProjectCode + " (" + pModelName + ") exists but its tables could not be read: try again.");
		}
		final Set<String> planned = new HashSet<String>(pPlannedTablePaths);
		final Set<String> existing = new HashSet<String>(pExistingTablePaths);
		if (!planned.equals(existing))
		{
			return new Decision(false, false, false, false, "The master model of project " + pProjectCode + " (" + pModelName
				+ ") already exists and differs from the currently promoted entities: refusing to adopt it - regeneration only applies once it is published.");
		}
		return new Decision(false, true, true, false, null);
	}
}
