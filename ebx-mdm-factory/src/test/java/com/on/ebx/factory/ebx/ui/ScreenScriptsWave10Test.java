package com.on.ebx.factory.ebx.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * JUnit-free: run the main, a failure throws. Wave 10 (audit F-49 and the end-of-task notice): what the wizard and the proposal review write into their pages -
 * every action locks the buttons until the page answers, the review never gives a button back while a pass of its request still runs, and both screens carry the
 * end-of-task notice with its labels in the person's language. With {@code -Dmdmf.jsDump=<dir>} the scripts are also written there, for {@code tools/jobnotice_test.js}
 * to parse (a syntax error in a script built from Java strings is invisible to every other test).
 */
public final class ScreenScriptsWave10Test
{
	public static void main(final String[] pArgs) throws IOException
	{
		ScreenScriptsWave10Test.reviewLocksEveryActionAndKeepsTheLongTimer();
		ScreenScriptsWave10Test.wizardCarriesTheNotice();
		ScreenScriptsWave10Test.noticeLabelsInBothLanguages();
		ScreenScriptsWave10Test.jobsUrlIsEncoded();
		ScreenScriptsWave10Test.dumpForTheParser();
		System.out.println("ScreenScriptsWave10Test OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static void reviewLocksEveryActionAndKeepsTheLongTimer()
	{
		ScreenScriptsWave10Test.check(ProposalReviewUserService.PAGE_JS_PROGRESS_TIMEOUT_MS >= 900000, "the strip's timer is the long one: no button comes back while a pass of the request runs");
		ScreenScriptsWave10Test.check(ProposalReviewUserService.PAGE_JS_LOCK_TIMEOUT_MS >= 60000 && ProposalReviewUserService.PAGE_JS_LOCK_TIMEOUT_MS < ProposalReviewUserService.PAGE_JS_PROGRESS_TIMEOUT_MS,
			"a quick action's lock is shorter than the long pass's");
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final String script = ProposalReviewUserService.pageJs(locale);
			ScreenScriptsWave10Test.check(!script.contains("}," + 45000 + ");"), "no 45 s timer");
			ScreenScriptsWave10Test.check(script.contains("function lockAll(ms)") && script.contains("function unlockAll()"), "lock and unlock helpers");
			ScreenScriptsWave10Test.check(script.contains("if(!locked)lockAll(" + ProposalReviewUserService.PAGE_JS_LOCK_TIMEOUT_MS + ");var s=document.getElementById('mdmfSubmit');if(s)s.click();"),
				"every action click locks before it submits");
			ScreenScriptsWave10Test.check(script.contains("lockAll(" + ProposalReviewUserService.PAGE_JS_PROGRESS_TIMEOUT_MS + ");"), "the strip locks for as long as its own timer");
			ScreenScriptsWave10Test.check(script.contains("if(action==='BULK_ACCEPT_CONFIRM'){progress(progressLabel.bulk);}") && script.contains("SELECT_REJECT_CONFIRM"),
				"what still runs in the request keeps its strip");
			ScreenScriptsWave10Test.check(!script.contains("progress(progressLabel.apply)") && !script.contains("progress(progressLabel.remove)") && !script.contains("progress(progressLabel.measure)"),
				"apply, remove and measure are queued: no strip");
			ScreenScriptsWave10Test.check(script.indexOf("set('mdmfAutoMergeValue'") > 0 && script.indexOf("lockAll(", script.indexOf("set('mdmfAutoMergeValue'")) > 0, "the auto-merge checkbox locks too");
			ScreenScriptsWave10Test.check(script.contains("pageshow"), "a page restored from the back/forward cache gets its buttons back");
		}
	}

	private static void wizardCarriesTheNotice() throws IOException
	{
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final String script = ProjectWizardUserService.script(locale);
			ScreenScriptsWave10Test.check(script.contains("window.mdmfJobNoticeLabels=") && script.indexOf("window.mdmfJobNotice =") > script.indexOf("window.mdmfJobNoticeLabels="),
				"the labels come before the notice script");
			ScreenScriptsWave10Test.check(script.indexOf("window.mdmfJobNotice =") < script.lastIndexOf("mdmfJobNotice.check(data"), "the notice script comes before the banner that calls it");
			ScreenScriptsWave10Test.check(script.contains("lockButtons(LOCK_TIMEOUT_MS)"), "every action locks the buttons");
			ScreenScriptsWave10Test.check(script.contains("STAGING_SUGGEST_RULES") && script.contains("LANDING_KEY_STUDY_RUN") && script.contains("CAPABILITY_APPLY_ALL_MASTER"), "the new kinds are named in the banner's labels");
		}
		final String wizardJs = ScreenScriptsWave10Test.resource("wizard.js");
		ScreenScriptsWave10Test.check(wizardJs.contains("var LOCK_TIMEOUT_MS = 120000;") && wizardJs.contains("var PROGRESS_TIMEOUT_MS = 900000;"), "quick actions lock for 2 minutes, the toast ones for 15");
		ScreenScriptsWave10Test.check(wizardJs.contains("if (!locked) lockButtons(LOCK_TIMEOUT_MS);"), "the one place every action goes through");
		ScreenScriptsWave10Test.check(!wizardJs.contains("action === \"STAGING_SUGGEST\") progress("), "the advanced Propose the staging is queued: no toast");
		final int ended = wizardJs.indexOf("if (ended && !editing()) { rerender(); return; }");
		final int notice = wizardJs.indexOf("mdmfJobNotice.check(data");
		ScreenScriptsWave10Test.check(ended > 0 && notice > ended, "the notice is announced from the banner's own answer, after the re-render decision (the new page announces what a re-render interrupted)");
	}

	private static void noticeLabelsInBothLanguages()
	{
		final String en = JobNotice.labels(Locale.ENGLISH);
		final String fr = JobNotice.labels(Locale.FRENCH);
		ScreenScriptsWave10Test.check(en.contains("done:'{0}: finished.'") && en.contains("failed:'{0}: failed.'") && en.contains("close:'Close'"), "English labels: " + en);
		ScreenScriptsWave10Test.check(fr.contains("done:'{0} : terminée.'") && fr.contains("close:'Fermer'") && fr.contains("open:'Tâches récentes'"), "French labels: " + fr);
		ScreenScriptsWave10Test.check(en.contains("kinds:{") && en.contains("DISTRIBUTION_CONFIGURE_RUN:'Configuring a distribution target'"), "the kinds' names travel with them");
		ScreenScriptsWave10Test.check(JobNotice.watchCall("/x/jobs").equals("window.mdmfJobNotice.watch('/x/jobs',{});\n"), "the watch call");
	}

	private static void jobsUrlIsEncoded()
	{
		ScreenScriptsWave10Test.check("/ebx-mdm-factory/rest/v1/projects/ABC/jobs".equals(JobNotice.jobsUrl("ABC")), "plain code");
		ScreenScriptsWave10Test.check("/ebx-mdm-factory/rest/v1/projects/a%20b%2Fc/jobs".equals(JobNotice.jobsUrl("a b/c")), "a code is free text: " + JobNotice.jobsUrl("a b/c"));
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
			Files.write(Paths.get(dir, "wizard-" + locale.getLanguage() + ".js"), ProjectWizardUserService.script(locale).getBytes(StandardCharsets.UTF_8));
			Files.write(Paths.get(dir, "review-" + locale.getLanguage() + ".js"),
				(ProposalReviewUserService.pageJs(locale) + "\n" + JobNotice.script(locale) + JobNotice.watchCall(JobNotice.jobsUrl("P 1"))).getBytes(StandardCharsets.UTF_8));
		}
	}

	private static String resource(final String pName) throws IOException
	{
		// wave 11: wizard.js is a static file of the web application (www/common/jscripts/factory), the other scripts are class path resources
		final String sourceDir = System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java");
		final Path root = "wizard.js".equals(pName) ? Paths.get(sourceDir).resolve("../webapp/www/common/jscripts/factory").normalize()
			: Paths.get(sourceDir).resolve("../resources/com/on/ebx/factory/ebx/ui").normalize();
		return new String(Files.readAllBytes(root.resolve(pName)), StandardCharsets.UTF_8);
	}
}
