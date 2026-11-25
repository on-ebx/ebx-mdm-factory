package com.on.ebx.factory.wizard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;

/**
 * JUnit-free: run the main, a failure throws. Audit F-40: the wizard links to a user guide the module serves, in both
 * languages, at the section of the step shown; nothing unfinished is offered; no message sends a person to a repository
 * file, a REST route or a technical table.
 */
public final class WizardHelpTest
{
	private static final String[] ANCHORS = { "step-1", "step-2", "step-3", "step-4", "step-5", "step-6", "step-7", "step-8", "tasks", "schedule", "delivery",
		"rights", "problems" };

	public static void main(final String[] pArgs) throws IOException
	{
		WizardHelpTest.helpLinkFollowsTheStep();
		WizardHelpTest.noHelpLinkWithoutAGuide();
		WizardHelpTest.guidesExistWithEveryAnchor();
		WizardHelpTest.messagesSendNobodyToTheRepository();
		System.out.println("WizardHelpTest OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static WizardPage.Model model()
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.snapshot = new WizardSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.administrator = true;
		model.designer = true;
		return model;
	}

	private static void helpLinkFollowsTheStep()
	{
		final WizardPage.Model model = WizardHelpTest.model();
		model.helpUrl = "/ebx-mdm-factory/www/common/html/factory/user-guide-fr.html";
		model.locale = Locale.FRENCH;
		final String html = WizardPage.render(model);
		WizardHelpTest.check(html.contains("class=\"mdmf-wiz-help\" href=\"/ebx-mdm-factory/www/common/html/factory/user-guide-fr.html#step-1\""), "help link of step 1");
		WizardHelpTest.check(html.contains("target=\"_blank\" rel=\"noopener\""), "opens in a new tab");
		WizardHelpTest.check(html.contains(">Aide</a>"), "localized label");
	}

	private static void noHelpLinkWithoutAGuide()
	{
		final String html = WizardPage.render(WizardHelpTest.model());
		WizardHelpTest.check(!html.contains("mdmf-wiz-help"), "no guide, no link");
	}

	private static Path webRoot()
	{
		final String sources = System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java");
		return Paths.get(sources).getParent().resolve("webapp/www/common/html/factory");
	}

	private static void guidesExistWithEveryAnchor() throws IOException
	{
		for (final String lang : new String[] { "en", "fr" })
		{
			final Path guide = WizardHelpTest.webRoot().resolve("user-guide-" + lang + ".html");
			WizardHelpTest.check(Files.isRegularFile(guide), "missing " + guide);
			final String text = new String(Files.readAllBytes(guide), StandardCharsets.UTF_8);
			WizardHelpTest.check(text.contains("<html lang=\"" + lang + "\">"), lang + ": html lang");
			for (final String anchor : WizardHelpTest.ANCHORS)
			{
				WizardHelpTest.check(text.contains("id=\"" + anchor + "\""), lang + ": no section " + anchor);
			}
			WizardHelpTest.check(!text.contains("docs/") && !text.contains("/rest/"), lang + ": the guide names a repository file or a route");
		}
	}

	private static void messagesSendNobodyToTheRepository() throws IOException
	{
		final Path bundles = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java")).getParent()
			.resolve("resources/com/on/ebx/factory/ebx/ui");
		for (final String lang : new String[] { "en", "fr" })
		{
			final Properties messages = new Properties();
			try (java.io.InputStream in = Files.newInputStream(bundles.resolve("Messages_" + lang + ".properties")))
			{
				messages.load(in);
			}
			for (final String key : messages.stringPropertyNames())
			{
				if (!key.startsWith("wizard."))
				{
					continue;
				}
				final String value = messages.getProperty(key);
				WizardHelpTest.check(!value.contains("this lot") && !value.contains("dans ce lot"), lang + " " + key + " says 'this lot': " + value);
				WizardHelpTest.check(!value.contains("docs/") && !value.contains(".md "), lang + " " + key + " names a repository file: " + value);
				WizardHelpTest.check(!value.contains("PipelineStep") && !value.contains("master/generate") && !value.contains("staging/generate"),
					lang + " " + key + " names a table or a route: " + value);
			}
		}
	}
}
