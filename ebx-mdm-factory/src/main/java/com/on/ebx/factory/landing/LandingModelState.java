package com.on.ebx.factory.landing;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides what the LANDING_MODEL step still has to do, from what a read-only look at the data-model
 * server already found under the model's name: nothing (build, embed, publish); a model but no
 * publication (a previous run built it, then failed before publishing: embed and publish only); or both
 * (a previous run went all the way: nothing to do, the step is simply DONE). This is what makes the step
 * resumable at all - {@code build_data_model} refuses a name that already exists, so a resumed run that
 * blindly built again would fail forever after any partial success.
 * <p>
 * A model of that name the factory did not build is a real risk, not a hypothetical: two projects that
 * happen to pick the same project code and source system land on the same generated name. Before skipping
 * a build, or publishing what is already there, the existing model's tables must be exactly the planned
 * ones (by path; column-level detail is not needed for this check) - anything else refuses instead of
 * silently adopting or republishing a model this generator did not build. Pure: no EBX or MCP class.
 */
public final class LandingModelState
{
	private LandingModelState()
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

	/** What {@code LandingGenerator} must still call, or why it must stop instead. */
	public static final class Decision
	{
		public final boolean build;
		public final boolean publish;
		/** Non-null only when generation must stop: an existing model of that name is not the one this project source would build. */
		public final String refusal;

		private Decision(final boolean pBuild, final boolean pPublish, final String pRefusal)
		{
			this.build = pBuild;
			this.publish = pPublish;
			this.refusal = pRefusal;
		}

		public boolean ok()
		{
			return this.refusal == null;
		}
	}

	/**
	 * @param pPlannedTablePaths {@code /root/<table>} of every table the plan selects; never null
	 * @param pExistingTablePaths the same, as the server reports the existing model's tables; null or empty when {@code pFound} is {@code NOTHING}
	 */
	public static Decision decide(final Found pFound, final String pModelName, final String pSourceSystem, final List<String> pPlannedTablePaths,
		final List<String> pExistingTablePaths)
	{
		if (pFound == Found.NOTHING)
		{
			return new Decision(true, true, null);
		}
		final Set<String> planned = new HashSet<String>(pPlannedTablePaths);
		final Set<String> existing = new HashSet<String>(pExistingTablePaths == null ? Collections.<String>emptyList() : pExistingTablePaths);
		if (!planned.equals(existing))
		{
			return new Decision(false, false,
				"A data model named " + pModelName + " already exists, and its tables do not match the landing plan of source system " + pSourceSystem
					+ ": it was not built by this generator. Rename the source system, or delete or rename the existing model, then try again.");
		}
		if (pFound == Found.MODEL_ONLY)
		{
			return new Decision(false, true, null);
		}
		return new Decision(false, false, null); // MODEL_AND_PUBLICATION: a previous run already finished this step
	}
}
