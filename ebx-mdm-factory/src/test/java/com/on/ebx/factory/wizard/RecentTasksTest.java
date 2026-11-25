package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** JUnit-free: run the main, a failure throws. Audit F-43: the Recent tasks list of the wizard and the Cockpit. */
public final class RecentTasksTest
{
	private static final Date NOW = new Date(1790000000000L);

	public static void main(final String[] pArgs)
	{
		RecentTasksTest.interruptedTaskSaysSoAndOffersRunAgain();
		RecentTasksTest.runAgainOnlyForSafeKindsAndAllowedPeople();
		RecentTasksTest.failureIsFoldedSuccessIsShown();
		RecentTasksTest.codedFailureReadsInThePersonsLanguage();
		RecentTasksTest.cockpitLinksToTheWizard();
		RecentTasksTest.opensByItselfOnlyWhenFresh();
		RecentTasksTest.kindsMapToTheirStep();
		RecentTasksTest.wizardRendersTheListForAProjectOnly();
		RecentTasksTest.nothingToDoReadsAmberNotGreen();
		RecentTasksTest.aJobThatEndedWithFailuresIsNeverGreen();
		RecentTasksTest.onlyTheCurrentStepAndTheThreeLatestShowTheRestBehindShowAll();
		RecentTasksTest.theCockpitStillListsEverything();
		RecentTasksTest.durationsReadLikeTheLiveList();
		RecentTasksTest.theWizardNoticeCarriesAnIdForTheClientSideStepChange();
		System.out.println("RecentTasksTest OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static RecentTasks.Task task(final String pId, final String pKind, final String pStatus, final String pMessage, final long pEndedMinutesAgo)
	{
		final RecentTasks.Task task = new RecentTasks.Task();
		task.jobId = pId;
		task.kind = pKind;
		task.status = pStatus;
		task.message = pMessage;
		task.requestedBy = "alice";
		task.endedAt = new Date(RecentTasksTest.NOW.getTime() - pEndedMinutesAgo * 60000L);
		task.startedAt = new Date(task.endedAt.getTime() - 192000L);
		task.mayRerun = true;
		return task;
	}

	private static List<RecentTasks.Task> list(final RecentTasks.Task... pTasks)
	{
		final List<RecentTasks.Task> out = new ArrayList<RecentTasks.Task>();
		for (final RecentTasks.Task task : pTasks)
		{
			out.add(task);
		}
		return out;
	}

	private static void interruptedTaskSaysSoAndOffersRunAgain()
	{
		final String html = RecentTasks.render(RecentTasksTest.list(RecentTasksTest.task("42", "STAGING_LOAD_RUN", "INTERRUPTED",
			"Interrupted: the server restarted during this job.", 60)), Locale.FRENCH, RecentTasksTest.NOW, null);
		RecentTasksTest.check(html.contains("Interrompu par un redémarrage du serveur"), "localized status: " + html);
		RecentTasksTest.check(html.contains("Le serveur a redémarré pendant cette tâche"), "explained in French");
		RecentTasksTest.check(!html.contains("Interrupted: the server restarted"), "the engine's English sentence is not shown");
		RecentTasksTest.check(html.contains("data-mdmf-action=\"JOB_RERUN\" data-mdmf-id=\"42\">Relancer</button>"), "Run again: " + html);
		RecentTasksTest.check(html.contains("3 min 12 s"), "duration");
	}

	private static void runAgainOnlyForSafeKindsAndAllowedPeople()
	{
		final RecentTasks.Task llm = RecentTasksTest.task("1", "STAGING_SUGGEST_LLM", "INTERRUPTED", "", 60);
		final RecentTasks.Task send = RecentTasksTest.task("2", "DISTRIBUTION_SEND_RUN", "INTERRUPTED", "", 60);
		final RecentTasks.Task notAllowed = RecentTasksTest.task("3", "MATCHING_RUN", "INTERRUPTED", "", 60);
		notAllowed.mayRerun = false;
		final RecentTasks.Task failed = RecentTasksTest.task("4", "MATCHING_RUN", "FAILED", "boom", 60);
		final String html = RecentTasks.render(RecentTasksTest.list(llm, send, notAllowed, failed), Locale.ENGLISH, RecentTasksTest.NOW, null);
		RecentTasksTest.check(!html.contains("JOB_RERUN"), "no Run again for a language-model pass, a send, a person without the right, or a failure: " + html);
		RecentTasksTest.check(html.contains("Start it again from its step."), "the manual hint for a kind that may not run twice");
	}

	private static void failureIsFoldedSuccessIsShown()
	{
		final String html = RecentTasks.render(RecentTasksTest.list(RecentTasksTest.task("5", "MASTER_BUILD_RUN", "DONE", "Master build finished.", 60),
			RecentTasksTest.task("6", "MATCHING_CONFIGURE", "FAILED", "apply_matching_config <refused>", 70)), Locale.ENGLISH, RecentTasksTest.NOW, null);
		RecentTasksTest.check(html.contains("<div class=\"mdmf-hint\">Master build finished.</div>"), "a success's sentence is shown");
		RecentTasksTest.check(html.contains("<details class=\"mdmf-notice-details\"><summary>Details</summary>"), "a failure is folded");
		RecentTasksTest.check(html.contains("apply_matching_config &lt;refused&gt;"), "escaped");
		RecentTasksTest.check(html.contains("Building the master") && html.contains("Configuring the matching"), "kind labels");
		RecentTasksTest.check(html.contains("data-mdmf-action=\"GOTO_STEP\" data-mdmf-goto-step=\"5\">Open step 5</button>"), "open the master step");
	}

	private static void codedFailureReadsInThePersonsLanguage()
	{
		// wave 10 (F-39): a failed job stores its code after the engine's English text; the table words it in the reader's language
		final String stored = EngineMessageCodec.encode("Project ZZ has no master zone yet: run POST /projects/ZZ/master/generate first", "engine.missing.noMasterZone",
			new Object[] { "ZZ" }, 2000);
		final String fr = RecentTasks.render(RecentTasksTest.list(RecentTasksTest.task("9", "MATCHING_CONFIGURE", "FAILED", stored, 70)), Locale.FRENCH, RecentTasksTest.NOW, null);
		RecentTasksTest.check(fr.contains("Le projet ZZ n&#39;a pas encore de zone master."), "the French sentence: " + fr);
		RecentTasksTest.check(fr.contains("Project ZZ has no master zone yet: run POST") && !fr.contains("mdmf:"), "the English text is folded, the marker never shown: " + fr);
		RecentTasksTest.check(fr.contains("<details class=\"mdmf-notice-details\">"), "folded");
	}

	private static void cockpitLinksToTheWizard()
	{
		final String html = RecentTasks.render(RecentTasksTest.list(RecentTasksTest.task("7", "MATCHING_RUN", "INTERRUPTED", "", 60),
			RecentTasksTest.task("8", "LANDING_RUN", "DONE", "", 70)), Locale.ENGLISH, RecentTasksTest.NOW, pStep -> "/wizard?step=" + pStep);
		RecentTasksTest.check(!html.contains("data-mdmf-action"), "the Cockpit has no wizard actions");
		RecentTasksTest.check(html.contains("href=\"/wizard?step=6\">Run again in the wizard</a>"), "interrupted: link to run again: " + html);
		RecentTasksTest.check(html.contains("href=\"/wizard?step=3\">Open step 3</a>"), "done: link to its step");
	}

	private static void opensByItselfOnlyWhenFresh()
	{
		// landing test fixes (2026-10-17): a success, even 30 s old, no longer opens the list at the top of the next step; a recent failure does
		final RecentTasks.Task justNow = RecentTasksTest.task("9", "MATCHING_RUN", "DONE", "", 0);
		justNow.endedAt = new Date(RecentTasksTest.NOW.getTime() - 30000L);
		final String fresh = RecentTasks.render(RecentTasksTest.list(justNow), Locale.ENGLISH, RecentTasksTest.NOW, null);
		RecentTasksTest.check(!fresh.contains("open=\"open\"") && fresh.contains("Recent tasks (1)"), "30 s ago, done: folded");
		final RecentTasks.Task failed = RecentTasksTest.task("9", "MATCHING_RUN", "FAILED", "boom", 0);
		failed.endedAt = new Date(RecentTasksTest.NOW.getTime() - 30000L);
		RecentTasksTest.check(RecentTasks.render(RecentTasksTest.list(failed), Locale.ENGLISH, RecentTasksTest.NOW, null).contains("id=\"mdmfRecentTasks\" open=\"open\""), "30 s ago, failed: open");
		final RecentTasks.Task interrupted = RecentTasksTest.task("9", "MATCHING_RUN", "INTERRUPTED", "", 0);
		interrupted.endedAt = new Date(RecentTasksTest.NOW.getTime() - 30000L);
		RecentTasksTest.check(RecentTasks.render(RecentTasksTest.list(interrupted), Locale.ENGLISH, RecentTasksTest.NOW, null).contains("open=\"open\""), "interrupted: open");
		final RecentTasks.Task old = RecentTasksTest.task("9", "MATCHING_RUN", "FAILED", "boom", 0);
		old.endedAt = new Date(RecentTasksTest.NOW.getTime() - 3600000L);
		RecentTasksTest.check(!RecentTasks.render(RecentTasksTest.list(old), Locale.ENGLISH, RecentTasksTest.NOW, null).contains("open=\"open\""), "a failure an hour old: folded");
		RecentTasksTest.check(RecentTasks.render(new ArrayList<RecentTasks.Task>(), Locale.ENGLISH, RecentTasksTest.NOW, null).isEmpty(), "no task, no list");
	}

	private static void nothingToDoReadsAmberNotGreen()
	{
		final RecentTasks.Task idle = RecentTasksTest.task("20", "LANDING_PLAN_RUN", "DONE", "Nothing to do: 0 table(s) to plan.", 60);
		final RecentTasks.Task real = RecentTasksTest.task("21", "LANDING_PLAN_RUN", "DONE", "17 table(s) planned.", 70);
		final RecentTasks.Task failed = RecentTasksTest.task("22", "LANDING_PLAN_RUN", "FAILED", "Nothing to do but it failed", 80);
		RecentTasksTest.check(RecentTasks.nothingToDo(idle) && !RecentTasks.nothingToDo(real) && !RecentTasks.nothingToDo(failed), "only a DONE job starting with the phrase");
		final String html = RecentTasks.render(RecentTasksTest.list(idle), Locale.ENGLISH, RecentTasksTest.NOW, null);
		RecentTasksTest.check(html.contains("class=\"mdmf-recent-nothing\"") && html.contains("<span class=\"mdmf-recent-status\">Nothing to do</span>"), "amber row: " + html);
		RecentTasksTest.check(!html.contains(">Done</span>") && html.contains("Nothing to do: 0 table(s) to plan."), "not a green Done, the sentence is kept");
		final String fr = RecentTasks.render(RecentTasksTest.list(idle), Locale.FRENCH, RecentTasksTest.NOW, null);
		RecentTasksTest.check(fr.contains("<span class=\"mdmf-recent-status\">Rien à faire</span>"), "French: " + fr);
		final String done = RecentTasks.render(RecentTasksTest.list(real), Locale.ENGLISH, RecentTasksTest.NOW, null);
		RecentTasksTest.check(done.contains("mdmf-recent-done") && done.contains(">Done</span>"), "a real Done stays green");
	}

	/** UX lot C2: the measured Recent tasks of UxCrmSf2 (img-after/a17): a failed landing run read green "Done". */
	private static void aJobThatEndedWithFailuresIsNeverGreen()
	{
		final String message = "Landing run finished with failures on: SALESFORCE, DEMOCRM.";
		final String partial = RecentTasks.render(RecentTasksTest.list(RecentTasksTest.task("30", "LANDING_RUN", "DONE", message, 60)), Locale.ENGLISH, RecentTasksTest.NOW, null);
		RecentTasksTest.check(partial.contains("class=\"mdmf-recent-partial\"") && partial.contains("<span class=\"mdmf-recent-status\">Finished with failures</span>")
			&& partial.contains(message) && !partial.contains(">Done<"), "amber, with its sentence: " + partial);
		final String failed = RecentTasks.render(RecentTasksTest.list(RecentTasksTest.task("31", "LANDING_RUN", "FAILED", message, 60)), Locale.ENGLISH, RecentTasksTest.NOW, null);
		RecentTasksTest.check(failed.contains("class=\"mdmf-recent-failed\"") && failed.contains(">Failed<") && failed.contains("SALESFORCE, DEMOCRM"), "a FAILED job reads red Failed with its sentence: " + failed);
		final String good = RecentTasks.render(RecentTasksTest.list(RecentTasksTest.task("32", "LANDING_RUN", "DONE", "Landing run finished: 16 of 16 stages done.", 60)), Locale.ENGLISH,
			RecentTasksTest.NOW, null);
		RecentTasksTest.check(good.contains("class=\"mdmf-recent-done\"") && good.contains(">Done<"), "the success stays green");
		final String french = RecentTasks.render(RecentTasksTest.list(RecentTasksTest.task("33", "LANDING_RUN", "DONE", message, 60)), Locale.FRENCH, RecentTasksTest.NOW, null);
		RecentTasksTest.check(french.contains("Termin\u00e9 avec des \u00e9checs"), "French label: " + french);
	}

	private static void onlyTheCurrentStepAndTheThreeLatestShowTheRestBehindShowAll()
	{
		final List<RecentTasks.Task> tasks = RecentTasksTest.list(RecentTasksTest.task("1", "MATCHING_RUN", "DONE", "", 6), RecentTasksTest.task("2", "MASTER_BUILD_RUN", "DONE", "", 7),
			RecentTasksTest.task("3", "LANDING_RUN", "DONE", "", 8), RecentTasksTest.task("4", "STAGING_LOAD_RUN", "DONE", "", 9), RecentTasksTest.task("5", "SOURCES_RUN", "DONE", "", 10),
			RecentTasksTest.task("6", "LANDING_PLAN_RUN", "DONE", "", 11));
		final boolean[] onStep4 = RecentTasks.visible(tasks, 4);
		RecentTasksTest.check(onStep4[0] && onStep4[1] && onStep4[2] && onStep4[3] && !onStep4[4] && !onStep4[5], "the 3 latest + the step's own (staging load): " + java.util.Arrays.toString(onStep4));
		final boolean[] onStep2 = RecentTasks.visible(tasks, 2);
		RecentTasksTest.check(onStep2[0] && onStep2[1] && onStep2[2] && !onStep2[3] && onStep2[4] && !onStep2[5], "on step 2 the sources task shows: " + java.util.Arrays.toString(onStep2));
		final String html = RecentTasks.render(tasks, Locale.ENGLISH, RecentTasksTest.NOW, null, null, 4);
		final int more = html.indexOf("<tbody class=\"mdmf-recent-more\" id=\"mdmfRecentMore\" hidden=\"hidden\">");
		RecentTasksTest.check(more > 0, "a second, hidden table body: " + html);
		final String shown = html.substring(0, more);
		final String hiddenPart = html.substring(more);
		RecentTasksTest.check(shown.contains("data-mdmf-job=\"4\"") && !shown.contains("data-mdmf-job=\"5\"") && hiddenPart.contains("data-mdmf-job=\"5\"") && hiddenPart.contains("data-mdmf-job=\"6\""),
			"jobs 5 and 6 are behind the button");
		RecentTasksTest.check(html.contains("data-mdmf-toggle=\"mdmfRecentMore\">Show all (6)</button>"), "the button names the total");
		RecentTasksTest.check(html.contains("Recent tasks (6)"), "the title still counts every task");
		final String few = RecentTasks.render(RecentTasksTest.list(tasks.get(0), tasks.get(4)), Locale.ENGLISH, RecentTasksTest.NOW, null, null, 4);
		RecentTasksTest.check(!few.contains("mdmfRecentMore") && !few.contains("Show all"), "nothing to hide, no button");
	}

	private static void theCockpitStillListsEverything()
	{
		final List<RecentTasks.Task> tasks = RecentTasksTest.list(RecentTasksTest.task("1", "MATCHING_RUN", "DONE", "", 6), RecentTasksTest.task("2", "MASTER_BUILD_RUN", "DONE", "", 7),
			RecentTasksTest.task("3", "LANDING_RUN", "DONE", "", 8), RecentTasksTest.task("4", "STAGING_LOAD_RUN", "DONE", "", 9), RecentTasksTest.task("5", "SOURCES_RUN", "DONE", "", 10));
		final String html = RecentTasks.render(tasks, Locale.ENGLISH, RecentTasksTest.NOW, pStep -> "/wizard?step=" + pStep);
		RecentTasksTest.check(!html.contains("mdmfRecentMore") && html.contains("data-mdmf-job=\"5\""), "no step, no folding");
	}

	private static void durationsReadLikeTheLiveList()
	{
		RecentTasksTest.check("42 s".equals(RecentTasks.durationOf(42000L)) && "3 min 12 s".equals(RecentTasks.durationOf(192000L)) && "1 h 5 min".equals(RecentTasks.durationOf(3900000L))
			&& "0 s".equals(RecentTasks.durationOf(-5L)), "the format wizard.js mirrors");
	}

	private static void theWizardNoticeCarriesAnIdForTheClientSideStepChange()
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.snapshot = new WizardSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.notice = "Planning the landing in the background.";
		final String html = WizardPage.render(model);
		RecentTasksTest.check(html.contains("<div id=\"mdmfNotice\" class=\"mdmf-notice mdmf-notice-ok\" role=\"alert\">Planning the landing in the background."), "wizard.js removes this element when the step changes");
		model.notice = null;
		RecentTasksTest.check(!WizardPage.render(model).contains("mdmfNotice\""), "no notice, no element");
	}

	private static void kindsMapToTheirStep()
	{
		RecentTasksTest.check(RecentTasks.stepOf("SOURCES_RUN") == 2 && RecentTasks.stepOf("ONBOARD_SCAN_RUN") == 2, "sources");
		RecentTasksTest.check(RecentTasks.stepOf("LANDING_KEY_CHANGE_RUN") == 3, "landing");
		RecentTasksTest.check(RecentTasks.stepOf("CAPABILITY_APPLY_STAGING") == 4 && RecentTasks.stepOf("QUALITY_SUGGEST_LLM") == 4, "staging");
		RecentTasksTest.check(RecentTasks.stepOf("CAPABILITY_MEASURE_MASTER") == 5 && RecentTasks.stepOf("MASTER_BUILD_RUN") == 5, "master");
		RecentTasksTest.check(RecentTasks.stepOf("GOLDEN_KEY_REFRESH_RUN") == 6 && RecentTasks.stepOf("MATCHING_SUGGEST_LLM") == 6, "matching");
		RecentTasksTest.check(RecentTasks.stepOf("ACCEL_PROPOSAL") == 11 && RecentTasks.stepOf("ACCEL_GENERATE_RUN") == 12, "accelerators");
		RecentTasksTest.check(RecentTasks.stepOf("DISTRIBUTION_SEND_RUN") == 7, "distribution");
		RecentTasksTest.check(RecentTasks.stepOf("STEP_RESET_RUN") == 8 && RecentTasks.stepOf("MASTER_RESET_RUN") == 8, "whole project: summary");
	}

	private static void wizardRendersTheListForAProjectOnly()
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.snapshot = new WizardSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.recentTasks.add(RecentTasksTest.task("10", "MATCHING_RUN", "DONE", "", 5));
		RecentTasksTest.check(!WizardPage.render(model).contains("mdmfRecentTasks"), "no project, no list");
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.code = "P1";
		project.mode = "GREENFIELD";
		model.snapshot.project = project;
		model.stepStates = WizardRules.stepStates(model.snapshot);
		RecentTasksTest.check(WizardPage.render(model).contains("mdmfRecentTasks"), "a project: the list");
	}
}
