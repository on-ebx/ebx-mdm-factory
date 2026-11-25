package com.on.ebx.factory.wizard;

import java.util.Locale;

import com.on.ebx.factory.intake.IntakeStatus;

/**
 * JUnit-free: run the main, a failure throws. Audit F2-108: the pipeline problem of the intake is a CODE, worded by the screen in the person's language - never an English sentence
 * that names the workflow model or a file of the repository.
 */
public final class IntakePipelineProblemTest
{
	public static void main(final String[] pArgs)
	{
		for (final String code : new String[] { IntakeStatus.Pipeline.NOT_PUBLISHED, IntakeStatus.Pipeline.PREDATES_INTAKE })
		{
			for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
			{
				final WizardPage.Model model = new WizardPage.Model();
				model.intake = new IntakeScreens.Input();
				model.intake.status = new IntakeStatus();
				model.intake.status.state = "OFF";
				model.intake.status.pipeline.problemCode = code;
				final StringBuilder out = new StringBuilder();
				IntakeScreens.renderScreen(out, model, locale);
				final String html = out.toString();
				final String sentence = WizardMessages.get(locale, "wizard.intake.pipeline.problem." + code);
				IntakePipelineProblemTest.check(code + "/" + locale + ": the sentence is shown", html.contains(WizardPage.esc(sentence)));
				IntakePipelineProblemTest.check(code + "/" + locale + ": no workflow model name, no repository path", !html.contains("MdmFactoryPipeline") && !html.contains("tools/") && !html.contains(".json"));
				IntakePipelineProblemTest.check(code + "/" + locale + ": the sentence is in the person's language", locale.equals(Locale.FRENCH) ? sentence.contains("administrateur") : sentence.contains("administrator"));
			}
		}
		final WizardPage.Model none = new WizardPage.Model();
		none.intake = new IntakeScreens.Input();
		none.intake.status = new IntakeStatus();
		none.intake.status.state = "OFF";
		final StringBuilder out = new StringBuilder();
		IntakeScreens.renderScreen(out, none, Locale.ENGLISH);
		IntakePipelineProblemTest.check("no code, no problem notice", !out.toString().contains("pipeline.problem") && !out.toString().contains("administrator to publish"));
		System.out.println("IntakePipelineProblemTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
