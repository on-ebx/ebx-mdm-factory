package com.on.ebx.factory.wizard;

import java.text.DateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Audit F-43: the "Recent tasks" list of a project - its last background jobs ({@code FactoryJob} rows) with their outcome, when they
 * ran, who asked, a link to the step they belong to and, for a task the server restart interrupted, "Run again" where its kind can
 * safely be started again with the same parameters. Shown by the wizard and by the Cockpit; it replaces the result lines the jobs
 * banner used to keep in the tab's {@code sessionStorage} (lost with the tab, invisible from another one). Pure: no EBX class.
 */
public final class RecentTasks
{
	public static final String INTERRUPTED = "INTERRUPTED";
	public static final String RERUN_ACTION = "JOB_RERUN";
	/**
	 * A list opens by itself only when its newest task ended this recently - the person most likely came for it. UX lot U3 (2026-10-13): it
	 * was 15 minutes, and since every action adds a row the list was open on every later step (10 rows, ~450 px above the step's title).
	 */
	static final long FRESH_MILLIS = 60L * 1000L;
	/**
	 * Landing test fixes (2026-10-17): a list that was open at the top of step 3 right after the step-2 tasks (a green "Done" 30 s old) pushed the step down for nothing. It now
	 * opens by itself only when its newest task needs attention - failed or interrupted, and ended less than this long ago - and stays folded after a success.
	 */
	static final long ATTENTION_MILLIS = 10L * 60L * 1000L;
	/** Pass as the current step to list every task (the Cockpit, which has no step). */
	public static final int ALL_STEPS = -1;
	/** Besides the tasks of the step shown, this many of the most recent ones (whatever their step) stay visible. */
	static final int ALWAYS_SHOWN = 3;
	/** A DONE job whose message starts with this did not change anything: it is shown amber, not green "Done" (convention of the UX lots). */
	public static final String NOTHING_TO_DO = "Nothing to do";

	/**
	 * The kinds a person may start again from the list: each one recomputes its result from the project's current state and its own
	 * parameters, so running it twice is harmless. Left out on purpose: the language-model passes (a second spend and a second set of
	 * proposals), the distribution sends (a target would receive the records twice), the proposals and bulk decisions, the key change,
	 * the source and onboarding writes, and the administrator's resets and deletion - each has its own button on its step.
	 */
	static final Set<String> RERUNNABLE = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("STAGING_LOAD_RUN", "MASTER_BUILD_RUN",
		"MATCHING_CONFIGURE", "MATCHING_RUN", "MATCHING_RESULTS_RUN", "GOLDEN_KEY_REFRESH_RUN", "CAPABILITY_APPLY_STAGING", "CAPABILITY_APPLY_MASTER",
		"CAPABILITY_MEASURE_STAGING", "CAPABILITY_MEASURE_MASTER", "LANDING_PLAN_RUN", "LANDING_GENERATE_RUN", "LANDING_READ_RUN", "ONBOARD_SCAN_RUN")));

	/** One finished job. */
	public static final class Task
	{
		public String jobId = "";
		public String kind = "";
		public String status = "";
		public String requestedBy = "";
		public String message = "";
		public Date requestedAt;
		public Date startedAt;
		public Date endedAt;
		/** Whether the person viewing may queue a job of this kind ({@code FactoryJobQueue#mayQueue}) - set by the reader. */
		public boolean mayRerun;
	}

	/** Builds the address of a step of the wizard, for a screen that is not the wizard (the Cockpit); null in the wizard itself. */
	public interface StepLink
	{
		String url(int pStep);
	}

	private RecentTasks()
	{
	}

	public static boolean rerunnable(final String pKind)
	{
		return pKind != null && RecentTasks.RERUNNABLE.contains(pKind);
	}

	/** Whether the list offers "Run again" for this task: interrupted by a restart, of a kind that may run twice, and the person may queue it. */
	public static boolean offersRerun(final Task pTask)
	{
		return RecentTasks.INTERRUPTED.equals(pTask.status) && RecentTasks.rerunnable(pTask.kind) && pTask.mayRerun;
	}

	/** Whether a task ended DONE having done nothing ({@link #NOTHING_TO_DO} message): it reads amber "Nothing to do", never a green "Done". */
	public static boolean nothingToDo(final Task pTask)
	{
		return pTask != null && "DONE".equals(pTask.status) && pTask.message != null && pTask.message.trim().startsWith(RecentTasks.NOTHING_TO_DO);
	}

	/**
	 * Which tasks of the list (newest first) are visible without a click: the {@link #ALWAYS_SHOWN} most recent plus every task that belongs to
	 * {@code pCurrentStep}; with {@link #ALL_STEPS} all of them. Pure, so the rule is tested without rendering.
	 */
	static boolean[] visible(final List<Task> pTasks, final int pCurrentStep)
	{
		final boolean[] out = new boolean[pTasks == null ? 0 : pTasks.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = pCurrentStep == RecentTasks.ALL_STEPS || i < RecentTasks.ALWAYS_SHOWN || RecentTasks.stepOf(pTasks.get(i).kind) == pCurrentStep;
		}
		return out;
	}

	/** The wizard step a job kind belongs to; {@link WizardRules#STEP_SUMMARY} for the whole-project and pipeline kinds. */
	public static int stepOf(final String pKind)
	{
		final String kind = pKind == null ? "" : pKind;
		if (kind.startsWith("SOURCES_") || kind.startsWith("ONBOARD_") || kind.startsWith("DEDUCED_"))
		{
			return WizardRules.STEP_SOURCES;
		}
		if (kind.startsWith("LANDING_"))
		{
			return WizardRules.STEP_LANDING;
		}
		if (kind.startsWith("STAGING_") || kind.startsWith("QUALITY_SUGGEST_") || kind.equals("CAPABILITY_SUGGEST_LLM") || kind.endsWith("_STAGING"))
		{
			return WizardRules.STEP_STAGING;
		}
		if (kind.startsWith("MASTER_BUILD") || kind.endsWith("_MASTER"))
		{
			return WizardRules.STEP_TRANSFERS;
		}
		if (kind.startsWith("MATCHING_") || kind.startsWith("GOLDEN_KEY"))
		{
			return WizardRules.STEP_MATCHING;
		}
		if (kind.startsWith("DISTRIBUTION_"))
		{
			return WizardRules.STEP_DISTRIBUTION;
		}
		// Centralisation (lot C1): the adaptation proposal and the domain generation have their own steps (the first real run showed
		// "Open step 8" on every generation task)
		if (kind.equals("ACCEL_PROPOSAL"))
		{
			return WizardRules.STEP_PROPOSAL;
		}
		if (kind.startsWith("ACCEL_"))
		{
			return WizardRules.STEP_GENERATION;
		}
		return WizardRules.STEP_SUMMARY;
	}

	/** The label of a kind ({@code wizard.jobs.kind.*}), the kind itself when the bundle has none. */
	public static String kindLabel(final String pKind, final Locale pLocale)
	{
		final String key = "wizard.jobs.kind." + pKind;
		final String label = WizardMessages.get(pLocale, key);
		return key.equals(label) ? pKind : label;
	}

	/** The duration of a task, {@code "3 min 12 s"} style; empty when it never started or never ended. */
	static String duration(final Date pStart, final Date pEnd)
	{
		if (pStart == null || pEnd == null)
		{
			return "";
		}
		return RecentTasks.durationOf(pEnd.getTime() - pStart.getTime());
	}

	/** {@code "42 s"}, {@code "3 min 12 s"}, {@code "1 h 5 min"} - also what the live lists show for a stage (wizard.js mirrors it). */
	static String durationOf(final long pMillis)
	{
		final long seconds = Math.max(0L, pMillis / 1000L);
		if (seconds < 60L)
		{
			return seconds + " s";
		}
		final long minutes = seconds / 60L;
		if (minutes < 60L)
		{
			return minutes + " min " + seconds % 60L + " s";
		}
		return minutes / 60L + " h " + minutes % 60L + " min";
	}

	/** Whether a task asks to be looked at: failed or interrupted, and recent. */
	static boolean needsAttention(final Task pTask, final Date pNow)
	{
		final boolean bad = "FAILED".equals(pTask.status) || RecentTasks.INTERRUPTED.equals(pTask.status);
		final long ended = pTask.endedAt != null ? pTask.endedAt.getTime() : pTask.requestedAt != null ? pTask.requestedAt.getTime() : -1L;
		return bad && pNow != null && ended >= 0L && pNow.getTime() - ended < RecentTasks.ATTENTION_MILLIS;
	}

	/** The list as HTML, empty when there is no task. {@code pLinks} null = the wizard (step buttons and "Run again"); else plain links. */
	public static String render(final List<Task> pTasks, final Locale pLocale, final Date pNow, final StepLink pLinks)
	{
		return RecentTasks.render(pTasks, pLocale, pNow, pLinks, null);
	}

	/**
	 * Same, with the project's style: the "Open step N" button then shows the step's POSITION in that style's rail (the number the rail
	 * itself shows, "Step 5 of 6"), not the absolute step id; null keeps the absolute id.
	 */
	public static String render(final List<Task> pTasks, final Locale pLocale, final Date pNow, final StepLink pLinks, final String pStyleCode)
	{
		return RecentTasks.render(pTasks, pLocale, pNow, pLinks, pStyleCode, RecentTasks.ALL_STEPS);
	}

	/**
	 * Same, for the screen of one step: the tasks of {@code pCurrentStep} and the three most recent ones show, the others sit in a second
	 * table body behind a "Show all (N)" button ({@code data-mdmf-toggle}, no round trip). {@link #ALL_STEPS} lists everything.
	 */
	public static String render(final List<Task> pTasks, final Locale pLocale, final Date pNow, final StepLink pLinks, final String pStyleCode,
		final int pCurrentStep)
	{
		if (pTasks == null || pTasks.isEmpty())
		{
			return "";
		}
		final Locale locale = pLocale == null ? Locale.ENGLISH : pLocale;
		final Task newest = pTasks.get(0);
		final boolean fresh = RecentTasks.needsAttention(newest, pNow);
		final DateFormat when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale);
		final StringBuilder out = new StringBuilder();
		out.append("<details class=\"mdmf-recent-tasks\" id=\"mdmfRecentTasks\"").append(fresh ? " open=\"open\"" : "").append("><summary>")
			.append(WizardPage.esc(WizardMessages.get(locale, "wizard.recent.title", Integer.valueOf(pTasks.size())))).append("</summary>");
		out.append("<table class=\"mdmf-recent-table\"><thead><tr>");
		for (final String column : new String[] { "task", "outcome", "started", "duration", "by", "actions" })
		{
			out.append("<th scope=\"col\">").append(WizardPage.esc(WizardMessages.get(locale, "wizard.recent.col." + column))).append("</th>");
		}
		out.append("</tr></thead><tbody>");
		final boolean[] shown = RecentTasks.visible(pTasks, pCurrentStep);
		int hidden = 0;
		for (int i = 0; i < shown.length; i++)
		{
			if (shown[i])
			{
				RecentTasks.row(out, pTasks.get(i), locale, when, pLinks, pStyleCode);
			}
			else
			{
				hidden++;
			}
		}
		out.append("</tbody>");
		if (hidden > 0)
		{
			out.append("<tbody class=\"mdmf-recent-more\" id=\"mdmfRecentMore\" hidden=\"hidden\">");
			for (int i = 0; i < shown.length; i++)
			{
				if (!shown[i])
				{
					RecentTasks.row(out, pTasks.get(i), locale, when, pLinks, pStyleCode);
				}
			}
			out.append("</tbody>");
		}
		out.append("</table>");
		if (hidden > 0)
		{
			out.append("<p class=\"mdmf-recent-all\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-toggle=\"mdmfRecentMore\">")
				.append(WizardPage.esc(WizardMessages.get(locale, "wizard.recent.showAll", Integer.valueOf(pTasks.size())))).append("</button></p>");
		}
		out.append("</details>");
		return out.toString();
	}

	private static void row(final StringBuilder pOut, final Task pTask, final Locale pLocale, final DateFormat pWhen, final StepLink pLinks, final String pStyleCode)
	{
		final String status = pTask.status == null ? "" : pTask.status;
		// UX lot C2: a DONE job that did nothing or ended with failures reads amber ("Nothing to do", "Finished with failures"), never green "Done"
		final String shown = JobOutcome.display(pTask.kind, status, pTask.message);
		pOut.append("<tr class=\"mdmf-recent-").append(WizardPage.esc(shown.toLowerCase(Locale.ROOT))).append("\" data-mdmf-job=\"").append(WizardPage.esc(pTask.jobId))
			.append("\">");
		pOut.append("<td>").append(WizardPage.esc(RecentTasks.kindLabel(pTask.kind, pLocale))).append("</td>");
		// the outcome: the status in the person's language; a finished job's own sentence; a failure's engine text folded (audit F-39);
		// an interruption explained, since its stored message is the engine's English sentence
		pOut.append("<td><span class=\"mdmf-recent-status\">").append(WizardPage.esc(RecentTasks.statusLabel(shown, pLocale))).append("</span>");
		final String message = pTask.message == null ? "" : pTask.message.trim();
		if (RecentTasks.INTERRUPTED.equals(status))
		{
			pOut.append("<div class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, RecentTasks.rerunnable(pTask.kind)
				? "wizard.recent.interrupted.rerun" : "wizard.recent.interrupted.manual"))).append("</div>");
		}
		else if (JobOutcome.completed(status) && !message.isEmpty())
		{
			// run panel i18n (2026-10-17): the sentences the landing run stored with a code (and its older English) read in the person's language
			pOut.append("<div class=\"mdmf-hint\">").append(WizardPage.esc(RunPanelText.display(message, pLocale))).append("</div>");
		}
		else if (!message.isEmpty())
		{
			// wave 10 (F-39): a failure stored with its code reads as its sentence in the person's language, the engine's text folded
			final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(message);
			final String sentence = decoded.sentence(pLocale);
			if (sentence != null)
			{
				pOut.append("<div class=\"mdmf-hint mdmf-ma-error\">").append(WizardPage.esc(sentence)).append("</div>");
			}
			WizardPage.renderNoticeDetail(pOut, decoded.raw, pLocale);
		}
		pOut.append("</td>");
		final Date started = pTask.startedAt != null ? pTask.startedAt : pTask.requestedAt;
		pOut.append("<td>").append(started == null ? "" : WizardPage.esc(pWhen.format(started))).append("</td>");
		pOut.append("<td>").append(WizardPage.esc(RecentTasks.duration(pTask.startedAt, pTask.endedAt))).append("</td>");
		pOut.append("<td>").append(WizardPage.esc(pTask.requestedBy == null ? "" : pTask.requestedBy)).append("</td>");
		pOut.append("<td class=\"mdmf-recent-actions\">");
		final int step = RecentTasks.stepOf(pTask.kind);
		final int position = pStyleCode == null ? 0 : WizardRules.positionOf(pStyleCode, step);
		final String stepName = WizardMessages.get(pLocale, "wizard.recent.open", Integer.valueOf(position > 0 ? position : step));
		if (pLinks == null)
		{
			// the same buttons as the rail (WizardPage#renderSteps): steps 7 and 8 build their model only when shown, hence STEP_VIEW
			final String action = step == WizardRules.STEP_DISTRIBUTION || step == WizardRules.STEP_SUMMARY ? "STEP_VIEW" : "GOTO_STEP";
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"").append(action).append("\" data-mdmf-goto-step=\"").append(step)
				.append("\">").append(WizardPage.esc(stepName)).append("</button>");
			if (RecentTasks.offersRerun(pTask))
			{
				pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"").append(RecentTasks.RERUN_ACTION)
					.append("\" data-mdmf-id=\"").append(WizardPage.esc(pTask.jobId)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.recent.rerun")))
					.append("</button>");
			}
		}
		else
		{
			final String url = pLinks.url(step);
			if (url != null && !url.isEmpty())
			{
				pOut.append("<a class=\"mdmf-btn mdmf-btn-sm mdmf-leave-link\" target=\"_top\" href=\"").append(WizardPage.esc(url)).append("\">")
					.append(WizardPage.esc(RecentTasks.offersRerun(pTask) ? WizardMessages.get(pLocale, "wizard.recent.rerunInWizard") : stepName)).append("</a>");
			}
		}
		pOut.append("</td></tr>");
	}

	private static String statusLabel(final String pStatus, final Locale pLocale)
	{
		final String key = "wizard.jobs.status." + pStatus;
		final String label = WizardMessages.get(pLocale, key);
		return key.equals(label) ? pStatus : label;
	}
}
