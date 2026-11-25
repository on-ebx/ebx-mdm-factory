package com.on.ebx.factory.ebx.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * JUnit-free: run the main, a failure throws. Wave 11 (audit F-42): {@code wizard.js} is a static file of the web application, fetched under a build-stamped
 * address, instead of 99 KB copied into every display. This checks what the page still writes inline, the loader and its way out when the file cannot be
 * fetched, that the one source of the file is the one the inline form (and the other tests) read, and that the new step navigation has its pieces.
 */
public final class WizardScriptLoadingTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		WizardScriptLoadingTest.theInlinePartIsSmallAndTheFileIsNotInIt();
		WizardScriptLoadingTest.theInlineFormIsTheSameBytesAsBefore();
		WizardScriptLoadingTest.theLoaderAsksAgainThenSaysSo();
		WizardScriptLoadingTest.theStaticFileIsTheOneSourceOfTheClassPathCopy();
		WizardScriptLoadingTest.theWriterUsesTheLoaderUnlessToldOtherwise();
		WizardScriptLoadingTest.theNewNavigationPiecesAreInTheScript();
		WizardScriptLoadingTest.dumpForTheParser();
		System.out.println("WizardScriptLoadingTest OK");
	}

	private static void theInlinePartIsSmallAndTheFileIsNotInIt()
	{
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final String whole = ProjectWizardUserService.script(locale);
			final String inline = ProjectWizardUserService.scriptHead(locale) + ProjectWizardUserService.loaderScript("/ebx-mdm-factory/www/common/jscripts/factory/wizard.js?v=20261007120000")
				+ ProjectWizardUserService.scriptTail();
			// 32 000 since the Validation desk lots L3/L4/L5 (2026-10-22): each background-job kind adds its label to mdmfJobLabels, which the page must
			// carry inline; the real guard is the quarter of the whole script just below
			WizardScriptLoadingTest.check(locale + ": the inline part is under 32 000 characters (" + inline.length() + ")", inline.length() < 32000);
			WizardScriptLoadingTest.check(locale + ": and under a quarter of the whole script (" + inline.length() + " of " + whole.length() + ")", inline.length() * 4 < whole.length());
			WizardScriptLoadingTest.check(locale + ": wizard.js is not in it", !inline.contains("function showStep(") && !inline.contains("window.mdmfWizardReady = true;"));
			WizardScriptLoadingTest.check(locale + ": the labels wizard.js reads are", inline.contains("var mdmfWizardProgressLabels=") && inline.contains("var mdmfJobLabels=") && inline.contains("var mdmfWizardLabels={leaveDirty:"));
			WizardScriptLoadingTest.check(locale + ": so are the notice, the combobox and the two small block scripts", inline.contains("window.mdmfJobNotice =") && inline.contains("window.mdmfInitCombo = function")
				&& inline.contains("data-mdmf-match-tab-select"));
		}
		final String fr = ProjectWizardUserService.scriptHead(Locale.FRENCH);
		WizardScriptLoadingTest.check("the confirmation is in French", fr.contains("Vous avez modifi"));
	}

	private static void theInlineFormIsTheSameBytesAsBefore()
	{
		final String script = ProjectWizardUserService.script(Locale.ENGLISH);
		WizardScriptLoadingTest.check("head, then wizard.js, then the tail", script.startsWith(ProjectWizardUserService.scriptHead(Locale.ENGLISH)) && script.endsWith(ProjectWizardUserService.scriptTail()));
		WizardScriptLoadingTest.check("wizard.js sits between them", script.indexOf("window.mdmfWizardReady = true;") > script.indexOf("window.mdmfInitCombo = function")
			&& script.indexOf("window.mdmfWizardReady = true;") < script.lastIndexOf("data-mdmf-match-tab-select"));
	}

	private static void theLoaderAsksAgainThenSaysSo()
	{
		final String loader = ProjectWizardUserService.loaderScript("/m/www/common/jscripts/factory/wizard.js?v=42");
		WizardScriptLoadingTest.check("it loads the address given", loader.contains("var url=\"/m/www/common/jscripts/factory/wizard.js?v=42\"") || loader.contains("var url='/m/www/common/jscripts/factory/wizard.js?v=42'"));
		WizardScriptLoadingTest.check("scripts run in the order they were added", loader.contains("s.async=false"));
		WizardScriptLoadingTest.check("an error or a file that did not set the flag asks once more", loader.contains("s.onerror=again") && loader.contains("if(!window.mdmfWizardReady)again()"));
		WizardScriptLoadingTest.check("the second ask carries a changing query no cache holds", loader.contains("'r='+(new Date().getTime())") && loader.contains("url.indexOf('?')<0?'?':'&'"));
		WizardScriptLoadingTest.check("then the page's own notice is shown", loader.contains("document.getElementById('mdmfScriptFail')") && loader.contains("n.hidden=false"));
		WizardScriptLoadingTest.check("it asks only once", loader.contains("if(retried){fail();return;}retried=true;"));
		WizardScriptLoadingTest.check("an address with a quote cannot leave the string", !ProjectWizardUserService.loaderScript("/a'b\"c").contains("a'b\"c"));
	}

	private static void theStaticFileIsTheOneSourceOfTheClassPathCopy() throws IOException
	{
		final String file = WizardScriptLoadingTest.text(WizardScriptLoadingTest.webapp("www/common/jscripts/factory/wizard.js"));
		WizardScriptLoadingTest.check("wizard.js sets the flag the loader checks, before it looks for its page", file.indexOf("window.mdmfWizardReady = true;") > 0
			&& file.indexOf("window.mdmfWizardReady = true;") < file.indexOf("var root = document.querySelector"));
		WizardScriptLoadingTest.check("the copy build.sh puts on the class path is the same bytes", file.equals(FactoryHtml.resourceText("wizard.js")));
		WizardScriptLoadingTest.check("there is no second source under src/main/resources", !Files.exists(WizardScriptLoadingTest.webapp("../resources/com/on/ebx/factory/ebx/ui/wizard.js")));
	}

	private static void theWriterUsesTheLoaderUnlessToldOtherwise() throws IOException
	{
		final String source = WizardScriptLoadingTest.text(Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"), "com/on/ebx/factory/ebx/ui/ProjectWizardUserService.java"));
		WizardScriptLoadingTest.check("the page's write goes through writeScripts", source.contains("this.writeScripts(pWriter);") && !source.contains("pWriter.addJS_cr(ProjectWizardUserService.script(this.locale));\n\t\t// project explorer"));
		WizardScriptLoadingTest.check("the address is the static file's, stamped with the build",
			source.contains("pWriter.getURLForResource(ResourceType.JSCRIPT, \"factory/wizard.js\") + \"?v=\" + Assets.version()"));
		WizardScriptLoadingTest.check("the former page is one property away", source.contains("\"ebx.factory.wizard.inlineScripts\"") && source.contains("\"ebx.factory.wizard.allSteps\""));
		WizardScriptLoadingTest.check("the notice the loader shows is written next to the scripts", source.contains("id=\\\"mdmfScriptFail\\\"") && source.contains("\"wizard.script.failed\""));
	}

	private static void theNewNavigationPiecesAreInTheScript()
	{
		final String script = ProjectWizardUserService.script(Locale.ENGLISH);
		WizardScriptLoadingTest.check("every step but the shown one loads itself when opened", script.contains("function loadDeferredStep(step)") && script.contains("[data-mdmf-deferred-step]"));
		WizardScriptLoadingTest.check("Back, Next and the rail ask before dropping an unsaved edit", script.contains("function confirmLeave(step)") && script.contains("if (!confirmLeave(target)) { e.stopImmediatePropagation(); return; }")
			&& script.contains("if (!prev || !confirmLeave(prev)) return;") && script.contains("if (!nxt || !confirmLeave(nxt)) return;")); // lot C1-B: the neighbour comes from the style's step list
		WizardScriptLoadingTest.check("a search box is not an edit", script.contains("data-mdmf-no-dirty") && script.contains(".mdmf-combo"));
		WizardScriptLoadingTest.check("opening a step starts at its top", script.contains("if (action !== \"STEP_VIEW\") try {"));
		WizardScriptLoadingTest.check("the step 4 filter box posts its text", script.contains("data-mdmf-filter-input") && script.contains("set(\"mdmfStagingFilter\""));
		WizardScriptLoadingTest.check("a card waiting for its facts is asked for again, a bounded number of times", script.contains("data-mdmf-sources-checking") && script.contains("n >= 20"));
	}

	private static void dumpForTheParser() throws IOException
	{
		final String dir = System.getProperty("mdmf.jsDump");
		if (dir == null || dir.isEmpty())
		{
			return;
		}
		Files.createDirectories(Paths.get(dir));
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			Files.write(Paths.get(dir, "wizard-inline-part-" + locale.getLanguage() + ".js"),
				(ProjectWizardUserService.scriptHead(locale) + ProjectWizardUserService.loaderScript("/m/www/common/jscripts/factory/wizard.js?v=1") + ProjectWizardUserService.scriptTail())
					.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static Path webapp(final String pRelative)
	{
		return Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java")).resolve("../webapp").resolve(pRelative).normalize();
	}

	private static String text(final Path pPath) throws IOException
	{
		return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
