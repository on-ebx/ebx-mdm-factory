package com.on.ebx.factory.wizard;

/** JUnit-free: run the main, a failure throws. F3-13 (production review, 2026-11-12): Centralisation cannot be chosen or switched to server-side, an existing project keeps its style. */
public final class WizardStyleServerTest
{
	public static void main(final String[] pArgs)
	{
		WizardStyleServerTest.check("a new project can start in Consolidation", WizardRules.styleMayBeSaved(null, "CONSOLIDATION"));
		WizardStyleServerTest.check("a new project cannot start in Centralisation (coming soon)", !WizardRules.styleMayBeSaved(null, "CENTRALISATION"));
		WizardStyleServerTest.check("nor in the other informational styles, nor in an unknown or empty one", !WizardRules.styleMayBeSaved(null, "REGISTRY")
			&& !WizardRules.styleMayBeSaved(null, "COEXISTENCE") && !WizardRules.styleMayBeSaved(null, "MADEUP") && !WizardRules.styleMayBeSaved(null, "")
			&& !WizardRules.styleMayBeSaved(null, null));
		WizardStyleServerTest.check("a Consolidation project cannot be switched to Centralisation (Data Services, forged request)", !WizardRules.styleMayBeSaved("CONSOLIDATION", "CENTRALISATION"));
		WizardStyleServerTest.check("an existing Centralisation project keeps working (same style saved again)", WizardRules.styleMayBeSaved("CENTRALISATION", "CENTRALISATION"));
		WizardStyleServerTest.check("an existing Centralisation project is not switched back either", !WizardRules.styleMayBeSaved("CENTRALISATION", "CONSOLIDATION"));
		final WizardRules.ProjectDraft draft = new WizardRules.ProjectDraft();
		draft.code = "P1";
		draft.label = "A project";
		draft.mode = "GREENFIELD";
		draft.style = "CENTRALISATION";
		boolean refused = false;
		for (final WizardRules.Problem problem : WizardRules.projectProblems(draft, new java.util.HashSet<String>(), null))
		{
			refused |= "wizard.error.styleNotYet".equals(problem.key);
		}
		WizardStyleServerTest.check("projectProblems refuses a Centralisation draft server-side", refused);
		System.out.println("WizardStyleServerTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new IllegalStateException("FAILED: " + pWhat);
		}
	}
}
