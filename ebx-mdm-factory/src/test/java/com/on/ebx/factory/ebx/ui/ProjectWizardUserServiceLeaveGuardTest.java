package com.on.ebx.factory.ebx.ui;

import java.util.Locale;

/**
 * JUnit-free: run the main, a failure throws. Leave-guard lot (2026-09-23): checks the actual delivered
 * script ({@link ProjectWizardUserService#script}, which embeds {@code wizard.js} verbatim - see that
 * method's own Javadoc) carries the re-snapshot fix for the "Quitter le site Web ?" bug seen live by the
 * product owner (clicking the step 4 "Designer" link showed EBX's leave-page prompt with nothing actually
 * unsaved) - never just the raw resource file on disk, since a change to how the script is assembled could
 * otherwise drop the fix silently. Same package as {@code ProjectWizardUserService} on purpose, to reach its
 * package-private {@code script} method directly.
 */
public final class ProjectWizardUserServiceLeaveGuardTest
{
	public static void main(final String[] pArgs)
	{
		ProjectWizardUserServiceLeaveGuardTest.scriptReSnapshotsEbxOwnBaselineAfterLoadTimeWrites();
		System.out.println("ProjectWizardUserServiceLeaveGuardTest OK");
	}

	/**
	 * Cause (read in EBX's own {@code ebx_loader.js}/{@code _ebx_global.js}, ebx-manager webapp, read-only):
	 * {@code EBX_Form.hasBeenModified} compares {@code form.ebx_initialSerialize.value} (EBX's own baseline,
	 * taken by the {@code EBX_Form_InitFocus} loader task) against the form's CURRENT serialization. That
	 * baseline task is spliced in right after "destroy loading layer", while this whole script runs as the
	 * PAGE SCRIPT EXTENSION loader task ({@code UserServicePaneWriter.addJS_cr} -&gt; {@code
	 * UIBuilder.addJSExtension} -&gt; {@code EBX_Loader.PageScriptExtensionId}), which {@code
	 * EBX_Loader.initTasks} places LAST - after the baseline task. So {@code showStep(current)}'s own {@code
	 * set("mdmfStep", ...)} (the one load-time hidden-field write {@code wizard.js} makes - every other {@code
	 * set(...)}/{@code .value=} in that file only runs from a click/input/change listener) always lands AFTER
	 * EBX's baseline was taken, making the form read as "modified" even though nothing the person did actually
	 * changed. The fix re-snapshots EBX's own baseline once more, synchronously, right after that write.
	 */
	private static void scriptReSnapshotsEbxOwnBaselineAfterLoadTimeWrites()
	{
		final String script = ProjectWizardUserService.script(Locale.ENGLISH);
		ProjectWizardUserServiceLeaveGuardTest.check("re-reads the workspace form", script.contains("document.getElementById(EBX_Form.WorkspaceFormId)"));
		ProjectWizardUserServiceLeaveGuardTest.check("re-snapshots EBX's own baseline against the form's current serialization",
			script.contains("mdmfForm.ebx_initialSerialize.value = EBX_Form.serialize(mdmfForm)"));
		ProjectWizardUserServiceLeaveGuardTest.check("the re-snapshot is guarded (EBX_Form may not exist in a stand-alone/test rendering)",
			script.contains("var mdmfForm = document.getElementById(EBX_Form.WorkspaceFormId);") && script.contains("} catch (ignoredForm) {"));
		// the fix must run AFTER showStep(current) - the one load-time hidden-field write this file makes - never
		// before it, or the snapshot would still predate that write and the bug would resurface exactly as before.
		ProjectWizardUserServiceLeaveGuardTest.check("the re-snapshot runs after showStep(current), the one load-time hidden-field write",
			script.indexOf("showStep(current);") >= 0
				&& script.indexOf("showStep(current);") < script.indexOf("mdmfForm.ebx_initialSerialize.value = EBX_Form.serialize(mdmfForm)"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
