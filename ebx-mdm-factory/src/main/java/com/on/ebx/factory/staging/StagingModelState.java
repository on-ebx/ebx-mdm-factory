package com.on.ebx.factory.staging;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides what the {@code STAGING_MODEL} step still has to do, from what a read-only look at the
 * data-model server already found under the staging model's name: the same pattern as {@code
 * LandingModelState} for the landing zone (nothing found -&gt; build, embed, publish; a model but no
 * publication -&gt; embed and publish only; both, and the tables match -&gt; nothing to do, the step is
 * simply {@code DONE}). Pure: no EBX or MCP class.
 * <p>
 * One thing is deliberately DIFFERENT from {@code LandingModelState}: a landing model's tables are fixed
 * for good once a source is planned, so a name collision there almost certainly means a foreign model of
 * the same name and the refusal says so bluntly (rename or delete it). A staging model's expected shape
 * changes over time as MORE entities/attributes get accepted (PLAN.md, 2026-09-20 staging pass, "à
 * trancher avant de générer le modèle de staging") - round 4 (2026-09-20) added regeneration for exactly
 * this: {@code Company} accepted, with its 6 attributes, AFTER the staging model was already built,
 * patched and published. {@link Decision#regenerate} is therefore now UNCONDITIONAL for {@link
 * Found#MODEL_AND_PUBLICATION} - this class only ever compares TABLE PATH SETS, which is too coarse a
 * signal on its own (a new OPTIONAL FIELD on an EXISTING table, e.g. the very next attribute of {@code
 * Account} to be accepted, changes nothing about the table set) - so once a model is actually published,
 * the field-level {@link StagingModelDiff} is always the one that decides UP_TO_DATE vs. a real change,
 * never a table-path comparison here; {@code build}/{@code patch}/{@code publish} are all false in that
 * case, the caller drives the additive-only patch and republish from the diff instead. {@link
 * Found#MODEL_ONLY} (never published - no dataset was ever built on it, so nothing has EVER been at risk
 * of losing data) keeps its ORIGINAL, narrower, out-of-scope-for-round-4 behaviour below: tables match ->
 * patch and publish; tables differ -> refuse outright, the same non-destructive "regeneration is not
 * supported yet" wording as before ({@code StagingGenerator}'s regeneration path is for an
 * already-published model specifically, per the coordinator's round-4 brief - a half-built, unpublished
 * model with drifted tables is simplest handled by a person, not this pass).
 * <p>
 * A second thing, added after a coordinator review of the first version (2026-09-20): {@link
 * Decision#patch} is its own field, not folded into {@link Decision#build}. A resumed run that finds
 * {@code MODEL_ONLY} (the model was built by an earlier, partial run, but never published - the run died,
 * or the publish call itself failed) must still apply the facets {@code build_data_model} cannot carry
 * (maxLength, mandatory+minOccurs) before publishing: skipping the patch there would publish a staging
 * model with no length limits and no mandatory fields, silently wrong, and {@code StagingGenerator} would
 * then mark every accepted line {@code APPLIED} regardless. {@link Decision#patch} is therefore true in
 * exactly the same two states as {@link Decision#publish} ({@code NOTHING} after the build, and {@code
 * MODEL_ONLY} with matching tables) and false only when nothing is left to do ({@code
 * MODEL_AND_PUBLICATION}, tables matching) or when the whole run is refused - kept as a separate, named
 * field (not merely "same value as publish") so the caller states its intent per step and the three-state
 * table is easy to re-verify by test.
 * <p>
 * Re-applying the patch on every not-yet-published run is safe: {@code apply_data_model_patch}'s own
 * contract (`data_model_schema`, `settableFacets`) documents {@code mandatory}, {@code maxLength} and
 * {@code minOccurs} - the only tokens {@code StagingModel} ever emits - as plain single-slot MODIFY
 * facets, replaying to the same value each time; only {@code validationRules}, {@code uniqueConstraints}
 * and {@code javaConstraints} are marked ADDITIVE ("each call adds/appends...") in that same contract, and
 * none of those three is used here. See {@code StagingGenerator.runModelStep}'s own Javadoc for the exact
 * lines quoted from the live tool contracts.
 */
public final class StagingModelState
{
	private StagingModelState()
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

	/** What {@code StagingGenerator} must still call, or why it must stop instead. */
	public static final class Decision
	{
		public final boolean build;
		/** Apply the facets {@code build_data_model} cannot carry - true whenever the model is not yet published, whether or not it was just built (see the class Javadoc). */
		public final boolean patch;
		public final boolean publish;
		/** True when an existing model's table set differs from the accepted proposals: not a refusal - see the class Javadoc - the caller must compute a {@link StagingModelDiff} and drive additive-only regeneration itself. */
		public final boolean regenerate;
		/** Non-null only when generation must stop outright (no existing tables reported at all: the server answer itself is unusable, not merely stale). */
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
	 * @param pPlannedTablePaths {@code /root/<Entity>} of every table this run's accepted entities would build; never null
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
			// unconditional: a table-path match here does not mean the field-level shape still matches too (a new optional
			// field on an existing table changes nothing about the table SET) - StagingGenerator's StagingModelDiff is the
			// one that decides UP_TO_DATE vs. a real (additive or refused) change, this class no longer does
			return new Decision(false, false, false, true, null);
		}
		// Found.MODEL_ONLY from here: original, narrower behaviour, unchanged by round 4 (see class Javadoc)
		if (pExistingTablePaths == null)
		{
			return new Decision(false, false, false, false,
				"The staging model of project " + pProjectCode + " (" + pModelName + ") exists but its tables could not be read: try again.");
		}
		final Set<String> planned = new HashSet<String>(pPlannedTablePaths);
		final Set<String> existing = new HashSet<String>(pExistingTablePaths);
		if (!planned.equals(existing))
		{
			return new Decision(false, false, false, false, "The staging model of project " + pProjectCode + " (" + pModelName
				+ ") already exists and differs from the accepted proposals: regeneration is not supported yet.");
		}
		return new Decision(false, true, true, false, null);
	}
}
