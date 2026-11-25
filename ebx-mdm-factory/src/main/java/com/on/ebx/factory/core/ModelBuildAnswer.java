package com.on.ebx.factory.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * How to read the answer of {@code data-model.build_data_model} - the one call that builds a generated
 * landing / staging / master model (wave 2, lot V2-C: the rehearsal call that used to precede it is gone from
 * the default path, see {@link ModelRehearsal}). The tool's contract: <i>"Create a NEW model from a Tier-1
 * JSON tree, then run a single terminal validate + compile. Atomic: any op failure rolls the partial model
 * back (delete-on-fail)"</i>; the answer carries {@code built}, {@code valid}, {@code compiled},
 * {@code validationFindings}, {@code compileFindings} and {@code deletedOnFailure} (read live with
 * {@code dry_run: true}, 2026-09-30: an op that fails - unknown field type - answers {@code built:false,
 * compiled:false, valid:false} with a {@code BUILD_OP_FAILED} finding; a model that builds answers {@code
 * valid:true, compiled:true} with the compile findings, warnings included).
 * <p>
 * What the rehearsal protected against and this class now covers: the rehearsal built a throwaway copy, so a
 * model that failed the terminal validate/compile left NOTHING under the real name. A real build only rolls
 * back on an OP failure ({@code deletedOnFailure:true}); a model that builds but does not validate or compile
 * is documented to be created, and would be left behind - and the next "Generate" would find it as a
 * {@code MODEL_ONLY} leftover. So a failed answer that did not delete the model itself needs a cleanup:
 * {@link #cleanupNeeded()} says so, and the caller verifies the model exists, then deletes it.
 * <p>
 * Pure: no EBX or MCP class, unit-tested as is. The success test is the one the dry-run answer was checked
 * with: {@code compiled} and {@code valid}.
 */
public final class ModelBuildAnswer
{
	private final boolean toolError;
	private final boolean valid;
	private final boolean compiled;
	private final boolean deletedOnFailure;

	private ModelBuildAnswer(final boolean pToolError, final boolean pValid, final boolean pCompiled, final boolean pDeletedOnFailure)
	{
		this.toolError = pToolError;
		this.valid = pValid;
		this.compiled = pCompiled;
		this.deletedOnFailure = pDeletedOnFailure;
	}

	/**
	 * @param pToolError the MCP result's own error flag
	 * @param pAnswer the {@code json} of the result; null when the call answered no JSON
	 */
	public static ModelBuildAnswer read(final boolean pToolError, final JsonNode pAnswer)
	{
		if (pAnswer == null)
		{
			return new ModelBuildAnswer(pToolError, false, false, false);
		}
		return new ModelBuildAnswer(pToolError, pAnswer.path("valid").asBoolean(false), pAnswer.path("compiled").asBoolean(false),
			pAnswer.path("deletedOnFailure").asBoolean(false));
	}

	/**
	 * The model was created and validates and compiles. {@code valid} and {@code compiled} are the two gates the
	 * dry-run answer was checked with before; the {@code built} field is deliberately NOT part of it: a dry run
	 * always answers {@code built:false} (nothing is created under the name), and a failed op answers {@code
	 * valid:false, compiled:false} anyway - so the two gates cover both, and a wrongly read {@code built} can
	 * never make a good model look failed (a failed answer triggers a deletion).
	 */
	public boolean ok()
	{
		return this.gatesPassed() && !this.toolError;
	}

	/** {@code valid} and {@code compiled} both true - what a rehearsal (whose {@code built} is always false) is judged by. */
	public boolean gatesPassed()
	{
		return this.valid && this.compiled;
	}

	public boolean toolError()
	{
		return this.toolError;
	}

	public boolean deletedOnFailure()
	{
		return this.deletedOnFailure;
	}

	/**
	 * A failed build whose model the tool did not remove itself: it may still exist under the real name (a model
	 * that built but failed the terminal gate, or an answer we could not read). The caller must look, and delete.
	 */
	public boolean cleanupNeeded()
	{
		return !this.ok() && !this.deletedOnFailure;
	}

	/**
	 * @param pModelFound whether {@code get_data_model} finds the model under the built name after the failed
	 * build (true is safe to delete: the build only runs when the name was verified free just before)
	 */
	public boolean mustDelete(final boolean pModelFound)
	{
		return this.cleanupNeeded() && pModelFound;
	}
}
