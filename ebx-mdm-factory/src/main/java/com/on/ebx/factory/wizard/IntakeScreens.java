package com.on.ebx.factory.wizard;

import java.text.DateFormatSymbols;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.intake.IntakeChainOutcome;
import com.on.ebx.factory.intake.IntakeReadiness;
import com.on.ebx.factory.intake.IntakeStatus;

/**
 * Automatic-intake wizard lot (2026-10-05, docs/AUTOMATIC-INTAKE.md section 6 and "The screens", mockups {@code docs/mockups/automatic-intake/}): the three intake
 * screens inside the Summary step (step 8), written as raw HTML like the rest of {@link WizardPage}, from ONE {@link Input} (the {@link IntakeStatus} the facade
 * {@code ebx.Intake#status} returned, plus the screen's own transient state). EBX-free, so {@code WizardPageTest} renders them from fixtures.
 * <ul>
 * <li>{@link #renderCardBlock}: the "Automatic intake" block of the Summary's first "Go to production" card (state OFF / ON / PAUSED).</li>
 * <li>{@link #renderScreen}: the sub-screen opened from that block - the set-up screen while the state is OFF ({@link #renderSetup}), the running screen
 * while it is ON or PAUSED ({@link #renderLive}). Same panel, no new wizard step.</li>
 * </ul>
 * The running screen is complete WITHOUT JavaScript (server-rendered from the status); {@code wizard.js} ("automatic intake live panel") only repaints it every 5 s
 * from {@code GET /ebx-mdm-factory/rest/v1/projects/{code}/intake}. {@link #cardBodyHtml}, {@link #chainRowHtml} and the label table ({@link #labels}) are
 * mirrored by that script: change them together. Every dynamic value goes through {@link WizardPage#esc}; nothing here writes a script element.
 */
public final class IntakeScreens
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Quiet period choices (seconds), longest wait choices (minutes): the lists of the mockup; a stored value outside them is added, never replaced. */
	static final int[] QUIET_CHOICES = { 10, 30, 60, 120, 300, 900 };
	static final int[] MAX_WAIT_CHOICES = { 2, 5, 10, 30, 60 };

	/** The live screen's own refresh interval (wizard.js reads it from {@code data-mdmf-in-interval}). */
	static final int POLL_SECONDS = 5;

	private IntakeScreens()
	{
	}

	// ------------------------------------------------------------------------------------------------------- input

	/** One application's values as typed (kept as text so a refused entry is shown again exactly as typed). */
	public static final class SourceDraft
	{
		public String sourceSystem = "";
		public String quietSeconds = "";
		public String maxWaitMinutes = "";
		public String minRows = "";
		public boolean batchSignal;
	}

	/** What the person typed in the settings form, kept when the save/switch-on was refused so nothing typed is lost. */
	public static final class Draft
	{
		public boolean reviewRejects = true;
		public boolean stopOn;
		public String stopPercent = "";
		public final List<SourceDraft> sources = new ArrayList<SourceDraft>();
	}

	/** Everything the screens need; built by {@code ProjectWizardUserService#buildIntake}. */
	public static final class Input
	{
		/** The facade's answer; null when it could not be read ({@link #error} says why). */
		public IntakeStatus status;
		/** The sentence {@code Intake.status} refused with (or the unexpected failure's message): shown instead of the block's button / the screens. */
		public String error;
		/** The sub-screen is open (set-up or running, by {@link IntakeStatus#state}). */
		public boolean open;
		/** Running screen only: the settings panel is showing. */
		public boolean settingsOpen;
		/** Null when nothing is being re-shown; otherwise what was typed (a refused save). */
		public Draft draft;
		/**
		 * F2-102: about the installation of the triggers when the latest switch-on job is waiting, running or ended without finishing - "installing, installed on A, not yet on B"
		 * or "interrupted, ...". Empty/null: nothing to say.
		 */
		public String installLine;
		/** Offset to add to an instant before formatting it as a clock/date (the server's zone, so the first paint and the script's repaint agree). */
		public long zoneOffsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis());
	}

	// ------------------------------------------------------------------------------------------------ summary card

	/**
	 * The separated "Automatic intake" block under Launch now / Schedule in the Summary's "Chain the loads" card: one line and, by state, "Set up" (OFF), the
	 * On chip and "Open" (ON), the Paused chip, the reason and "Open" (PAUSED). Renders nothing when the intake was not read for this render (no input).
	 */
	static void renderCardBlock(final StringBuilder pOut, final WizardPage.Model pModel, final java.util.Locale pLocale)
	{
		final Input in = pModel.intake;
		if (in == null)
		{
			return;
		}
		pOut.append("<div class=\"mdmf-intake-card\" id=\"mdmf-intake-card\"><span class=\"mdmf-prod-name\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.card.title"))).append("</span><p>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.card.text"))).append("</p>");
		if (in.status == null)
		{
			pOut.append("<p class=\"mdmf-hint mdmf-intake-error\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.card.error", in.error == null ? "" : in.error)))
				.append("</p></div>");
			return;
		}
		final IntakeStatus s = in.status;
		pOut.append("<div class=\"mdmf-actions\">");
		if ("ON".equals(s.state))
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-real\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.state.ON"))).append("</span>");
		}
		else if ("PAUSED".equals(s.state))
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-warn\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.state.PAUSED"))).append("</span>");
		}
		else if (!s.mayOperate)
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-muted\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.state.OFF"))).append("</span>");
		}
		// "Set up" only for someone who can set it up; a reader always sees "Open" (the read-only view)
		final String label = "OFF".equals(s.state) && s.mayOperate ? "wizard.intake.card.setUp" : "wizard.intake.card.open";
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"INTAKE_OPEN\" data-mdmf-anchor=\"mdmfIntakeScreen\" data-mdmf-anchor-block=\"start\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, label))).append("</button></div>");
		if ("PAUSED".equals(s.state) && s.pauseReason != null && !s.pauseReason.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.paused.reason", IntakeScreens.pauseReasonText(pLocale, s.pauseReason)))).append("</p>");
		}
		pOut.append("</div>");
	}

	// ------------------------------------------------------------------------------------------------------ screens

	/** The sub-screen, in place of the Summary's own content: set-up while OFF, running while ON/PAUSED; an error notice and Back when the status was not read. */
	static void renderScreen(final StringBuilder pOut, final WizardPage.Model pModel, final java.util.Locale pLocale)
	{
		final Input in = pModel.intake;
		pOut.append("<div class=\"mdmf-intake\" id=\"mdmfIntakeScreen\">");
		if (in.status == null)
		{
			WizardPage.renderPageHead(pOut, WizardMessages.get(pLocale, "wizard.intake.setup.title"), "");
			pOut.append("<div class=\"mdmf-notice mdmf-notice-bad\" role=\"alert\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.card.error", in.error == null ? "" : in.error))).append("</div>");
			pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"INTAKE_CLOSE\" data-mdmf-anchor=\"mdmf-intake-card\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.back"))).append("</button></div></div>");
			return;
		}
		if ("OFF".equals(in.status.state))
		{
			IntakeScreens.renderSetup(pOut, pModel, pLocale);
		}
		else
		{
			IntakeScreens.renderLive(pOut, pModel, pLocale);
		}
		pOut.append("</div>");
	}

	/** The notice both screens start with: the engine thread is not running on this server, so nothing starts by itself. */
	private static void renderEngineNotice(final StringBuilder pOut, final IntakeStatus pStatus, final java.util.Locale pLocale)
	{
		if (!pStatus.engineRunning)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-bad\" role=\"alert\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.engineDown"))).append("</div>");
		}
	}

	// ------------------------------------------------------------------------------------------------------ set-up

	private static void renderSetup(final StringBuilder pOut, final WizardPage.Model pModel, final java.util.Locale pLocale)
	{
		final Input in = pModel.intake;
		final IntakeStatus s = in.status;
		final boolean readOnly = !s.mayOperate;
		WizardPage.renderPageHead(pOut, WizardMessages.get(pLocale, "wizard.intake.setup.title"), WizardMessages.get(pLocale, "wizard.intake.setup.lead"));
		IntakeScreens.renderEngineNotice(pOut, s, pLocale);
		WizardPage.render2ColStart(pOut);

		pOut.append("<span class=\"mdmf-eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.setup.when"))).append("</span>");
		IntakeScreens.renderSourceSettings(pOut, in, pLocale, readOnly);

		pOut.append("<span class=\"mdmf-eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.setup.then"))).append("</span>");
		IntakeScreens.renderFlow(pOut, pModel, pLocale);
		IntakeScreens.renderOptions(pOut, in, pLocale, readOnly);

		WizardPage.render2ColSplit(pOut);
		IntakeScreens.renderReadiness(pOut, s, pLocale);
		WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.intake.why.title");
		pOut.append("<span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.why.text1"))).append("</span><span>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.why.text2"))).append("</span>");
		WizardPage.renderSideCardClose(pOut);
		WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.intake.one.title");
		pOut.append("<span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.one.text"))).append("</span>");
		WizardPage.renderSideCardClose(pOut);
		WizardPage.render2ColEnd(pOut);

		// the pipeline problem says exactly what to import / publish: an error notice right above the button
		if (s.pipeline != null && s.pipeline.problemCode != null && !s.pipeline.problemCode.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-bad\" role=\"alert\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.pipeline.problem." + s.pipeline.problemCode))).append("</div>");
		}
		if (in.installLine != null && !in.installLine.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\" role=\"status\">").append(WizardPage.esc(in.installLine)).append("</div>");
		}
		pOut.append("<div class=\"mdmf-actions mdmf-intake-footer\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"INTAKE_CLOSE\" data-mdmf-anchor=\"mdmf-intake-card\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.back"))).append("</button>");
		pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.setup.footer"))).append("</span>");
		final String blocker = IntakeScreens.switchOnBlocker(s, pLocale);
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"INTAKE_SAVE\"").append(readOnly ? " disabled=\"disabled\"" : "").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.save"))).append("</button>");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"INTAKE_SWITCH_ON\" data-mdmf-confirm=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.switchOn.confirm"))).append("\"").append(blocker != null ? " disabled=\"disabled\" title=\"" + WizardPage.esc(blocker) + "\"" : "")
			.append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.switchOn"))).append("</button>");
		pOut.append("</div>");
		if (blocker != null)
		{
			pOut.append("<p class=\"mdmf-hint mdmf-intake-blocker\">").append(WizardPage.esc(blocker)).append("</p>");
		}
	}

	/** Why "Switch on" is disabled (the first blocking readiness line, or that the caller may not operate); null when it can be pressed. */
	static String switchOnBlocker(final IntakeStatus pStatus, final java.util.Locale pLocale)
	{
		if (!pStatus.mayOperate)
		{
			return WizardMessages.get(pLocale, "wizard.intake.notOperator");
		}
		if (pStatus.ready)
		{
			return null;
		}
		for (final IntakeReadiness.Line line : pStatus.readiness)
		{
			if (line.blocking && !line.ok)
			{
				return IntakeScreens.readinessText(pLocale, line);
			}
		}
		// not ready, yet no blocking line to quote: say so rather than leave a silently disabled button
		return WizardMessages.get(pLocale, "wizard.intake.notReady");
	}

	/** One card per application: the three settings as real controls, the batch-complete checkbox and the call, and what EBX knows about when the application is done. */
	private static void renderSourceSettings(final StringBuilder pOut, final Input pIn, final java.util.Locale pLocale, final boolean pReadOnly)
	{
		final IntakeStatus s = pIn.status;
		pOut.append("<input type=\"hidden\" id=\"mdmfIntakeCount\" name=\"mdmfIntakeCount\" value=\"").append(s.sources.size()).append("\"/>");
		for (int i = 0; i < s.sources.size(); i++)
		{
			final IntakeStatus.Source src = s.sources.get(i);
			final SourceDraft draft = IntakeScreens.draftOf(pIn.draft, src.sourceSystem);
			final String quiet = draft != null && !draft.quietSeconds.isEmpty() ? draft.quietSeconds : String.valueOf(src.quietSeconds);
			final String maxWait = draft != null && !draft.maxWaitMinutes.isEmpty() ? draft.maxWaitMinutes : String.valueOf(src.maxWaitMinutes);
			final String minRows = draft != null ? draft.minRows : String.valueOf(src.minRows);
			final boolean batch = draft != null ? draft.batchSignal : src.batchSignal;
			final String name = src.label == null || src.label.isEmpty() ? src.sourceSystem : src.label;
			final boolean connectors = IntakeStatus.KIND_CONNECTORS.equals(src.kind);

			pOut.append("<div class=\"mdmf-panel mdmf-intake-src\" data-in-settings=\"").append(WizardPage.esc(src.sourceSystem)).append("\">");
			pOut.append("<input type=\"hidden\" id=\"mdmfIntakeSrc").append(i).append("\" name=\"mdmfIntakeSrc").append(i).append("\" value=\"").append(WizardPage.esc(src.sourceSystem))
				.append("\"/>");
			pOut.append("<div class=\"mdmf-intake-src-head\"><span class=\"mdmf-intake-src-name\">").append(WizardPage.esc(name)).append("</span>");
			if (!name.equals(src.sourceSystem))
			{
				pOut.append("<span class=\"mdmf-mono\">").append(WizardPage.esc(src.sourceSystem)).append("</span>");
			}
			pOut.append("<span class=\"mdmf-chip ").append(connectors ? "mdmf-chip-real" : "mdmf-chip-muted").append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, connectors ? "wizard.intake.kind.CONNECTORS" : "wizard.intake.kind.PUSHED"))).append("</span></div>");
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, connectors ? "wizard.intake.kind.CONNECTORS.text" : "wizard.intake.kind.PUSHED.text")))
				.append("</p>");

			pOut.append("<div class=\"mdmf-intake-fields\">");
			IntakeScreens.renderSelect(pOut, pLocale, "mdmfIntakeQuiet" + i, "wizard.intake.field.quiet", IntakeScreens.QUIET_CHOICES, quiet, true, pReadOnly);
			IntakeScreens.renderSelect(pOut, pLocale, "mdmfIntakeMaxWait" + i, "wizard.intake.field.maxWait", IntakeScreens.MAX_WAIT_CHOICES, maxWait, false, pReadOnly);
			pOut.append("<div class=\"mdmf-field\"><label for=\"mdmfIntakeMinRows").append(i).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.field.minRows")))
				.append("</label><input type=\"number\" min=\"1\" step=\"1\" id=\"mdmfIntakeMinRows").append(i).append("\" name=\"mdmfIntakeMinRows").append(i).append("\" value=\"")
				.append(WizardPage.esc(minRows)).append("\"").append(pReadOnly ? " disabled=\"disabled\"" : "").append("/><span class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.field.minRows.unit"))).append("</span></div>");
			pOut.append("</div>");

			pOut.append("<div class=\"mdmf-intake-batch\"><label class=\"mdmf-hint\"><input type=\"checkbox\" id=\"mdmfIntakeBatch").append(i).append("\" name=\"mdmfIntakeBatch").append(i)
				.append("\" value=\"true\"").append(batch ? " checked=\"checked\"" : "").append(pReadOnly ? " disabled=\"disabled\"" : "").append("/> <b>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.batch.label"))).append("</b> &middot; ").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.batch.optional")))
				.append("</label><span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.batch.callPre"))).append(" <span class=\"mdmf-mono\">POST ")
				.append(WizardPage.esc(src.batchCompletePath)).append("</span> ").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.batch.callPost"))).append("</span></div>");
			pOut.append("</div>");
		}
	}

	/** A {@code <label>} + {@code <select>}; a current value outside {@code pChoices} is added as an option (selected), never silently replaced. */
	private static void renderSelect(final StringBuilder pOut, final java.util.Locale pLocale, final String pId, final String pLabelKey, final int[] pChoices, final String pCurrent,
		final boolean pSeconds, final boolean pReadOnly)
	{
		int current = -1;
		try
		{
			current = Integer.parseInt(pCurrent.trim());
		}
		catch (final NumberFormatException ex)
		{
			current = -1;
		}
		pOut.append("<div class=\"mdmf-field\"><label for=\"").append(pId).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, pLabelKey))).append("</label><select id=\"")
			.append(pId).append("\" name=\"").append(pId).append("\"").append(pReadOnly ? " disabled=\"disabled\"" : "").append(">");
		for (final int value : IntakeScreens.withCurrent(pChoices, current))
		{
			pOut.append("<option value=\"").append(value).append("\"").append(value == current ? " selected=\"selected\"" : "").append(">")
				.append(WizardPage.esc(pSeconds ? IntakeScreens.quietLabel(pLocale, value) : IntakeScreens.minutesLabel(pLocale, value))).append("</option>");
		}
		pOut.append("</select></div>");
	}

	/** The choices, sorted, plus {@code pCurrent} when it is not one of them (and positive). */
	static List<Integer> withCurrent(final int[] pChoices, final int pCurrent)
	{
		final List<Integer> out = new ArrayList<Integer>();
		for (final int choice : pChoices)
		{
			out.add(Integer.valueOf(choice));
		}
		if (pCurrent > 0 && !out.contains(Integer.valueOf(pCurrent)))
		{
			out.add(Integer.valueOf(pCurrent));
		}
		Collections.sort(out);
		return out;
	}

	/** 10 seconds, 60 seconds, 2 minutes: whole minutes from two minutes up, seconds below. */
	static String quietLabel(final java.util.Locale pLocale, final int pSeconds)
	{
		if (pSeconds >= 120 && pSeconds % 60 == 0)
		{
			return IntakeScreens.minutesLabel(pLocale, pSeconds / 60);
		}
		return WizardMessages.plural(pLocale, pSeconds, "wizard.intake.unit.seconds.one", "wizard.intake.unit.seconds.other");
	}

	static String minutesLabel(final java.util.Locale pLocale, final int pMinutes)
	{
		return WizardMessages.plural(pLocale, pMinutes, "wizard.intake.unit.minutes.one", "wizard.intake.unit.minutes.other");
	}

	private static SourceDraft draftOf(final Draft pDraft, final String pSourceSystem)
	{
		if (pDraft == null)
		{
			return null;
		}
		for (final SourceDraft draft : pDraft.sources)
		{
			if (draft.sourceSystem.equals(pSourceSystem))
			{
				return draft;
			}
		}
		return null;
	}

	/** Staging -> Master -> Matching -> Distribution: four boxes, the matching and distribution lines read from the project's own settings. */
	private static void renderFlow(final StringBuilder pOut, final WizardPage.Model pModel, final java.util.Locale pLocale)
	{
		final String trigger = pModel.snapshot.project != null && "REALTIME".equals(pModel.snapshot.project.matchingTrigger) ? "REALTIME" : "MANUAL";
		pOut.append("<div class=\"mdmf-zone-grid mdmf-intake-flow\">");
		IntakeScreens.flowBox(pOut, WizardMessages.get(pLocale, "wizard.intake.flow.staging"), WizardMessages.get(pLocale, "wizard.intake.flow.staging.text"), false);
		IntakeScreens.flowBox(pOut, WizardMessages.get(pLocale, "wizard.intake.flow.master"), WizardMessages.get(pLocale, "wizard.intake.flow.master.text"), true);
		IntakeScreens.flowBox(pOut, WizardMessages.get(pLocale, "wizard.intake.flow.matching"), WizardMessages.get(pLocale, "wizard.intake.flow.matching." + trigger), true);
		IntakeScreens.flowBox(pOut, WizardMessages.get(pLocale, "wizard.intake.flow.distribution"), IntakeScreens.distributionLine(pModel.summaryInput, pLocale), true);
		pOut.append("</div>");
	}

	private static void flowBox(final StringBuilder pOut, final String pTitle, final String pText, final boolean pWithArrow)
	{
		if (pWithArrow)
		{
			pOut.append(WizardPage.ZONE_ARROW);
		}
		pOut.append("<div class=\"mdmf-panel mdmf-zone-card\"><span class=\"mdmf-zone-name\">").append(WizardPage.esc(pTitle)).append("</span><span class=\"mdmf-hint\">")
			.append(WizardPage.esc(pText)).append("</span></div>");
	}

	/** One short line from the distribution targets the summary already holds ("Salesforce - in real time, Kafka - on a schedule"), or "nothing set up". */
	static String distributionLine(final WizardSummaryData.Input pSummary, final java.util.Locale pLocale)
	{
		final List<String> parts = new ArrayList<String>();
		if (pSummary != null)
		{
			for (final WizardSummaryData.DistributionSummaryTarget target : pSummary.distributionTargets)
			{
				parts.add(target.label + " · " + WizardMessages.get(pLocale, "wizard.step7dist.mode." + target.mode));
			}
			if (pSummary.distributionProductExists)
			{
				parts.add(pSummary.distributionProductLabel);
			}
		}
		if (parts.isEmpty())
		{
			return WizardMessages.get(pLocale, "wizard.intake.flow.distribution.none");
		}
		final StringBuilder out = new StringBuilder();
		for (final String part : parts)
		{
			if (out.length() > 0)
			{
				out.append(", ");
			}
			out.append(part);
		}
		return out.toString();
	}

	/** The two project-wide options: review task for held-back rows, and the stop threshold (a checkbox and a number 1-100, empty when unchecked). */
	private static void renderOptions(final StringBuilder pOut, final Input pIn, final java.util.Locale pLocale, final boolean pReadOnly)
	{
		final IntakeStatus s = pIn.status;
		final Draft d = pIn.draft;
		final boolean review = d != null ? d.reviewRejects : s.reviewRejects;
		final boolean stopOn = d != null ? d.stopOn : s.stopRejectPercent != null;
		final String percent = d != null ? d.stopPercent : (s.stopRejectPercent == null ? "" : String.valueOf(s.stopRejectPercent));
		pOut.append("<div class=\"mdmf-panel mdmf-intake-options\">");
		pOut.append("<div class=\"mdmf-intake-opt\"><label class=\"mdmf-hint\"><input type=\"checkbox\" id=\"mdmfIntakeReview\" name=\"mdmfIntakeReview\" value=\"true\"")
			.append(review ? " checked=\"checked\"" : "").append(pReadOnly ? " disabled=\"disabled\"" : "").append("/> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.opt.review"))).append("</label></div>");
		pOut.append("<div class=\"mdmf-intake-opt\"><label class=\"mdmf-hint\" for=\"mdmfIntakeStopOn\"><input type=\"checkbox\" id=\"mdmfIntakeStopOn\" name=\"mdmfIntakeStopOn\" value=\"true\"")
			.append(stopOn ? " checked=\"checked\"" : "").append(pReadOnly ? " disabled=\"disabled\"" : "").append("/> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.opt.stop.pre"))).append("</label> <input type=\"number\" class=\"mdmf-intake-num\" min=\"1\" max=\"100\" step=\"1\" ")
			.append("id=\"mdmfIntakeStopPct\" name=\"mdmfIntakeStopPct\" aria-label=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.opt.stop.aria"))).append("\" value=\"")
			.append(WizardPage.esc(percent)).append("\"").append(pReadOnly ? " disabled=\"disabled\"" : "").append("/> <span class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.opt.stop.post"))).append("</span></div>");
		pOut.append("</div>");
	}

	/** The "Switched on only after the first load" checklist, texts localized by the line's key (the status text is only the fallback for an unknown key). */
	private static void renderReadiness(final StringBuilder pOut, final IntakeStatus pStatus, final java.util.Locale pLocale)
	{
		WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.intake.ready.title");
		pOut.append("<span><span class=\"mdmf-chip ").append(pStatus.ready ? "mdmf-chip-real" : "mdmf-chip-warn").append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, pStatus.ready ? "wizard.intake.ready.chip.ok" : "wizard.intake.ready.chip.blocked"))).append("</span></span>");
		for (final IntakeReadiness.Line line : pStatus.readiness)
		{
			final String kind = line.ok ? "ok" : (line.blocking ? "blocking" : "info");
			pOut.append("<span class=\"mdmf-intake-ready mdmf-intake-ready-").append(kind).append("\" data-in-ready=\"").append(WizardPage.esc(line.key)).append("\"><b>")
				.append(line.ok ? "✓" : (line.blocking ? "!" : "•")).append("</b> ").append(WizardPage.esc(IntakeScreens.readinessText(pLocale, line))).append("</span>");
		}
		pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.ready.firstLoad"))).append("</span>");
		WizardPage.renderSideCardClose(pOut);
	}

	/**
	 * A pause reason as stored ({@code CODE:argument}, see {@link IntakeChainOutcome#pauseReason}) in the viewer's language; a value stored before F2-108 (an English
	 * sentence) or an unknown code is returned as it is. Public: the wizard's open point uses it too.
	 */
	public static String pauseReasonText(final java.util.Locale pLocale, final String pStored)
	{
		if (pStored == null)
		{
			return "";
		}
		final int colon = pStored.indexOf(':');
		if (colon > 0)
		{
			final String code = pStored.substring(0, colon);
			final String argument = pStored.substring(colon + 1);
			if (IntakeChainOutcome.REASON_STOPPED.equals(code) || IntakeChainOutcome.REASON_PAUSED_BY.equals(code))
			{
				return WizardMessages.get(pLocale, "wizard.intake.pauseReason." + code, IntakeChainOutcome.REASON_PAUSED_BY.equals(code) && argument.isEmpty()
					? WizardMessages.get(pLocale, "wizard.intake.pauseReason.system") : argument);
			}
			if (IntakeChainOutcome.REASON_FAILED.equals(code) || IntakeChainOutcome.REASON_LAUNCH_FAILED.equals(code))
			{
				return WizardMessages.get(pLocale, "wizard.intake.pauseReason." + code + (argument.isEmpty() ? ".unknown" : ""), argument);
			}
		}
		return pStored;
	}

	/** One readiness line in the page's language; falls back to the status's own English text for a key this screen does not know, or a count it cannot word. */
	static String readinessText(final java.util.Locale pLocale, final IntakeReadiness.Line pLine)
	{
		final long count = pLine.count == null ? 0L : pLine.count.longValue();
		if (IntakeReadiness.PIPELINE_MODEL.equals(pLine.key))
		{
			return WizardMessages.get(pLocale, pLine.ok ? "wizard.intake.ready.PIPELINE_MODEL.ok" : "wizard.intake.ready.PIPELINE_MODEL.ko");
		}
		if (IntakeReadiness.LANDING_LOADED.equals(pLine.key))
		{
			if (pLine.ok)
			{
				return WizardMessages.get(pLocale, "wizard.intake.ready.LANDING_LOADED.ok");
			}
			// "no source yet" carries a count of 0: only the status text says so
			return count > 0 ? WizardMessages.plural(pLocale, count, "wizard.intake.ready.LANDING_LOADED.ko.one", "wizard.intake.ready.LANDING_LOADED.ko.other")
				: WizardMessages.get(pLocale, "wizard.intake.ready.LANDING_LOADED.none");
		}
		if (IntakeReadiness.STAGING_AND_MASTER_LOADED.equals(pLine.key))
		{
			return WizardMessages.get(pLocale, pLine.ok ? "wizard.intake.ready.STAGING_AND_MASTER_LOADED.ok" : "wizard.intake.ready.STAGING_AND_MASTER_LOADED.ko");
		}
		if (IntakeReadiness.REJECTS_REVIEWED.equals(pLine.key))
		{
			return pLine.ok ? WizardMessages.get(pLocale, "wizard.intake.ready.REJECTS_REVIEWED.ok")
				: WizardMessages.plural(pLocale, count, "wizard.intake.ready.REJECTS_REVIEWED.ko.one", "wizard.intake.ready.REJECTS_REVIEWED.ko.other");
		}
		return pLine.text == null ? pLine.key : pLine.text;
	}

	// ----------------------------------------------------------------------------------------------------- running

	private static void renderLive(final StringBuilder pOut, final WizardPage.Model pModel, final java.util.Locale pLocale)
	{
		final Input in = pModel.intake;
		final IntakeStatus s = in.status;
		final boolean paused = "PAUSED".equals(s.state);
		final long now = IntakeScreens.millis(s.serverTime, System.currentTimeMillis());
		final String url = WizardPage.JOBS_REST + "projects/" + WizardPage.urlSegment(s.project == null ? "" : s.project) + "/intake";
		pOut.append("<div class=\"mdmf-intake-live\" id=\"mdmfIntakeLive\" data-mdmf-in-url=\"").append(WizardPage.esc(url)).append("\" data-mdmf-in-server-now=\"").append(now)
			.append("\" data-mdmf-in-tz=\"").append(in.zoneOffsetMs).append("\" data-mdmf-in-interval=\"").append(IntakeScreens.POLL_SECONDS).append("\" data-mdmf-in-state=\"")
			.append(WizardPage.esc(s.state)).append("\" data-mdmf-in-engine=\"").append(s.engineRunning).append("\" data-mdmf-in-sources=\"").append(WizardPage.esc(IntakeScreens.sourceCodes(s)))
			.append("\" data-mdmf-in-running=\"").append(WizardPage.esc(IntakeScreens.runningChainIds(s))).append("\" data-mdmf-in-settings-open=\"").append(in.settingsOpen)
			.append("\" data-mdmf-in-labels=\"").append(WizardPage.esc(IntakeScreens.labels(pLocale).toString())).append("\">");

		// header: title, state chip, what it does, and the actions of whoever may operate
		pOut.append("<div class=\"mdmf-panel mdmf-intake-head\"><div class=\"mdmf-intake-headtext\"><div class=\"mdmf-intake-title\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.live.title"))).append(" <span class=\"mdmf-chip ").append(paused ? "mdmf-chip-warn" : "mdmf-chip-real").append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, paused ? "wizard.intake.state.PAUSED" : "wizard.intake.state.ON"))).append("</span></div><span class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, paused ? "wizard.intake.live.lead.PAUSED" : "wizard.intake.live.lead.ON"))).append("</span></div>");
		if (s.mayOperate)
		{
			pOut.append("<div class=\"mdmf-actions\">");
			if (paused)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"INTAKE_RESUME\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.btn.resume")))
					.append("</button>");
			}
			else
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"INTAKE_PAUSE\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.btn.pause"))).append("</button>");
			}
			pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"").append(in.settingsOpen ? "INTAKE_SETTINGS_CLOSE\"" : "INTAKE_SETTINGS_OPEN\" data-mdmf-anchor=\"mdmfIntakeSettings\" data-mdmf-anchor-block=\"start\"").append(">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, in.settingsOpen ? "wizard.intake.btn.settingsHide" : "wizard.intake.btn.settings"))).append("</button>");
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-danger\" data-mdmf-action=\"INTAKE_SWITCH_OFF\" data-mdmf-confirm=\"")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.switchOff.confirm"))).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.btn.switchOff")))
				.append("</button></div>");
		}
		pOut.append("</div>");

		IntakeScreens.renderEngineNotice(pOut, s, pLocale);
		if (paused)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\" role=\"alert\"><b>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.paused.reason", IntakeScreens.pauseReasonText(pLocale, s.pauseReason)))).append("</b> ")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.paused.todo"))).append("</div>");
		}
		if (s.pipeline != null && s.pipeline.problemCode != null && !s.pipeline.problemCode.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-bad\" role=\"alert\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.pipeline.problem." + s.pipeline.problemCode))).append("</div>");
		}

		if (in.settingsOpen && s.mayOperate)
		{
			IntakeScreens.renderSettingsPanel(pOut, pModel, pLocale);
		}

		// process now: one button for the project, so it says so
		pOut.append("<div class=\"mdmf-actions mdmf-intake-process\"><button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"INTAKE_PROCESS_NOW\"")
			.append(s.mayOperate && !paused ? "" : " disabled=\"disabled\"").append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.btn.processNow")))
			.append("</button><span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.processNow.hint")));
		if (s.queuedAfterChain)
		{
			pOut.append(" ").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.processNow.queued")));
		}
		pOut.append("</span></div>");

		pOut.append("<div class=\"mdmf-intake-cards\" id=\"mdmfIntakeSources\">");
		for (final IntakeStatus.Source src : s.sources)
		{
			IntakeScreens.renderSourceCard(pOut, src, pLocale, now, in.zoneOffsetMs, s.mayOperate);
		}
		pOut.append("</div>");

		IntakeScreens.renderChains(pOut, pModel, pLocale, now);

		pOut.append("<div class=\"mdmf-actions mdmf-intake-footer\"><button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"INTAKE_CLOSE\" data-mdmf-anchor=\"mdmf-intake-card\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.back"))).append("</button><span class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.live.footer", IntakeScreens.POLL_SECONDS))).append("</span><span class=\"mdmf-hint mdmf-intake-stale\" data-in-hint hidden=\"hidden\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.notRefreshed"))).append("</span></div>");
		pOut.append("</div>");
	}

	/** The settings panel of the running screen: the same cards and options as the set-up, saved with {@code INTAKE_SAVE}, without leaving the screen. */
	private static void renderSettingsPanel(final StringBuilder pOut, final WizardPage.Model pModel, final java.util.Locale pLocale)
	{
		final Input in = pModel.intake;
		pOut.append("<div class=\"mdmf-panel mdmf-intake-settings\" id=\"mdmfIntakeSettings\"><span class=\"mdmf-eyebrow\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.settings.title"))).append("</span>");
		IntakeScreens.renderSourceSettings(pOut, in, pLocale, false);
		IntakeScreens.renderOptions(pOut, in, pLocale, false);
		pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"INTAKE_SAVE\" data-mdmf-anchor=\"mdmfIntakeSettings\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.save"))).append("</button><button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"INTAKE_SETTINGS_CLOSE\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.btn.settingsHide"))).append("</button><span class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.settings.hint"))).append("</span></div></div>");
	}

	private static String chipClass(final String pState)
	{
		if ("RECEIVING".equals(pState))
		{
			return "mdmf-chip-accent";
		}
		if ("DUE".equals(pState))
		{
			return "mdmf-chip-warn";
		}
		if ("RUNNING".equals(pState))
		{
			return "mdmf-chip-real";
		}
		return "mdmf-chip-muted";
	}

	/** One application's live card: name, state chip, the waiting rows, the quiet-period line and bar. The body is repainted by the script ({@link #cardBodyHtml}). */
	private static void renderSourceCard(final StringBuilder pOut, final IntakeStatus.Source pSrc, final java.util.Locale pLocale, final long pNow, final long pZoneOffsetMs,
		final boolean pMayOperate)
	{
		final String name = pSrc.label == null || pSrc.label.isEmpty() ? pSrc.sourceSystem : pSrc.label;
		pOut.append("<div class=\"mdmf-panel mdmf-intake-src mdmf-intake-livecard\" data-in-source=\"").append(WizardPage.esc(pSrc.sourceSystem)).append("\"><div class=\"mdmf-intake-src-head\">")
			.append("<span class=\"mdmf-intake-src-name\">").append(WizardPage.esc(name)).append("</span><span class=\"mdmf-chip ").append(chipClass(pSrc.state)).append("\" data-in-chip>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.src.state." + (pSrc.state == null ? "IDLE" : pSrc.state)))).append("</span></div>");
		pOut.append(IntakeScreens.cardBodyHtml(pSrc, pLocale, pNow, pZoneOffsetMs));
		if (IntakeStatus.KIND_CONNECTORS.equals(pSrc.kind))
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.src.connectors"))).append("</p>");
			if (pMayOperate)
			{
				// "Read now": a plain landing load of this one application (a background job); the chain follows the read by itself. Hidden for a reader.
				pOut.append("<div class=\"mdmf-actions mdmf-intake-readnow\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"INTAKE_READ_NOW\" data-mdmf-intake-source=\"")
					.append(WizardPage.esc(pSrc.sourceSystem)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.btn.readNow"))).append("</button><span class=\"mdmf-hint\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.readNow.hint"))).append("</span></div>");
			}
		}
		pOut.append("</div>");
	}

	/**
	 * The part of a live card that changes: the number of rows waiting (null = "rows arriving", never 0), the line "Last row N s ago - starts after Q of quiet" with the
	 * "at the latest" clock, the quiet-period bar (elapsed since the last row over the quiet period, against {@code pNow} = the server's clock) and the reason.
	 * MIRRORED by wizard.js ({@code cardBody}).
	 */
	static String cardBodyHtml(final IntakeStatus.Source pSrc, final java.util.Locale pLocale, final long pNow, final long pZoneOffsetMs)
	{
		final String state = pSrc.state == null ? "IDLE" : pSrc.state;
		final long last = IntakeScreens.millis(pSrc.lastRowAt, -1L);
		final long latest = IntakeScreens.millis(pSrc.latestAt, -1L);
		final boolean waiting = "RECEIVING".equals(state) || "DUE".equals(state);
		final StringBuilder out = new StringBuilder();
		out.append("<div class=\"mdmf-intake-body\" data-in-body data-in-last=\"").append(last).append("\" data-in-quiet=\"").append(pSrc.quietSeconds).append("\" data-in-line-state=\"")
			.append(WizardPage.esc(state)).append("\">");
		out.append("<div class=\"mdmf-intake-count\">");
		if (pSrc.rowsWaiting != null)
		{
			out.append("<b class=\"mdmf-intake-n\">").append(pSrc.rowsWaiting.longValue()).append("</b> <span>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.src.rowsWaiting"))).append("</span>");
		}
		else if (!"IDLE".equals(state))
		{
			out.append("<span class=\"mdmf-intake-arriving\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.src.rowsArriving"))).append("</span>");
		}
		else
		{
			out.append("<span class=\"mdmf-intake-arriving\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.src.nothing"))).append("</span>");
		}
		out.append("</div>");
		out.append("<div class=\"mdmf-intake-line\"><span class=\"mdmf-intake-lastline\">");
		if (waiting && last >= 0)
		{
			out.append(WizardPage.esc(IntakeScreens.lastLine(pLocale, Math.max(0L, pNow - last) / 1000L, pSrc.quietSeconds)));
		}
		else if ("RUNNING".equals(state))
		{
			out.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.src.running")));
		}
		else if ("DUE".equals(state))
		{
			out.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.src.due")));
		}
		out.append("</span><span class=\"mdmf-intake-latest\">");
		if (waiting && latest >= 0)
		{
			out.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.src.atLatest", IntakeScreens.clock(latest, pZoneOffsetMs))));
		}
		out.append("</span></div>");
		if (waiting)
		{
			final int percent = "DUE".equals(state) ? 100 : IntakeScreens.quietPercent(last, pNow, pSrc.quietSeconds);
			out.append("<div class=\"mdmf-intake-bar\"><i style=\"width:").append(percent).append("%\"></i></div>");
		}
		if (pSrc.reason != null && !pSrc.reason.isEmpty() && !"IDLE".equals(state))
		{
			out.append("<p class=\"mdmf-hint mdmf-intake-reason\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.src.why", IntakeScreens.reasonText(pLocale, pSrc.reason))))
				.append("</p>");
		}
		out.append("</div>");
		return out.toString();
	}

	static String lastLine(final java.util.Locale pLocale, final long pAgoSeconds, final int pQuietSeconds)
	{
		return WizardMessages.get(pLocale, "wizard.intake.src.lastLine", IntakeScreens.duration(pAgoSeconds), IntakeScreens.quietText(pQuietSeconds));
	}

	/** The quiet period as the mockup words it: "60 s" up to two minutes, then {@link #duration}. Mirrored by the script's {@code qdur}. */
	static String quietText(final int pSeconds)
	{
		return pSeconds < 120 ? pSeconds + " s" : IntakeScreens.duration(pSeconds);
	}

	/** Elapsed since the last row over the quiet period, 0-100 (0 when the last row is unknown). */
	static int quietPercent(final long pLastMs, final long pNowMs, final int pQuietSeconds)
	{
		if (pLastMs < 0 || pQuietSeconds <= 0)
		{
			return 0;
		}
		final long elapsed = Math.max(0L, pNowMs - pLastMs);
		return (int) Math.min(100L, elapsed * 100L / (pQuietSeconds * 1000L));
	}

	/** A reason code in words; an unknown code is shown as it is. */
	static String reasonText(final java.util.Locale pLocale, final String pReason)
	{
		final String key = "wizard.intake.reason." + pReason;
		final String text = WizardMessages.get(pLocale, key);
		return key.equals(text) ? pReason : text;
	}

	// ------------------------------------------------------------------------------------------------------ chains

	private static void renderChains(final StringBuilder pOut, final WizardPage.Model pModel, final java.util.Locale pLocale, final long pNow)
	{
		final Input in = pModel.intake;
		final IntakeStatus s = in.status;
		pOut.append("<div class=\"mdmf-panel mdmf-intake-chains\" id=\"mdmfIntakeChains\"><div class=\"mdmf-intake-chains-head\"><span class=\"mdmf-eyebrow\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.chains.title"))).append("</span>");
		final String cockpitUrl = pModel.summaryInput == null ? "" : pModel.summaryInput.cockpitUrl;
		if (!cockpitUrl.isEmpty())
		{
			pOut.append("<a href=\"").append(WizardPage.esc(cockpitUrl)).append("\" target=\"_top\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.chains.cockpit"))).append("</a>");
		}
		pOut.append("</div>");
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr>");
		for (final String column : new String[] { "started", "apps", "rows", "held", "took", "result" })
		{
			pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.col." + column))).append("</th>");
		}
		pOut.append("</tr></thead><tbody data-in-chains>");
		for (final IntakeStatus.Chain chain : s.chains)
		{
			pOut.append(IntakeScreens.chainRowHtml(chain, pLocale, in.zoneOffsetMs));
		}
		pOut.append("</tbody></table></div>");
		pOut.append("<p class=\"mdmf-hint mdmf-intake-empty\" data-in-chains-empty").append(s.chains.isEmpty() ? "" : " hidden=\"hidden\"").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.chains.empty"))).append("</p>");
		final Long held = s.rejectReview == null ? null : s.rejectReview.heldBack;
		if (held != null && held.longValue() > 0)
		{
			pOut.append("<p class=\"mdmf-hint mdmf-intake-held\">").append(WizardPage.esc(WizardMessages.plural(pLocale, held.longValue(), "wizard.intake.heldBack.one", "wizard.intake.heldBack.other")));
			if (!cockpitUrl.isEmpty())
			{
				pOut.append(" <a href=\"").append(WizardPage.esc(cockpitUrl)).append("\" target=\"_top\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.intake.heldBack.review"))).append("</a>");
			}
			pOut.append("</p>");
		}
		if (s.rejectReview != null && s.rejectReview.warning != null && !s.rejectReview.warning.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint mdmf-intake-error\">").append(WizardPage.esc(IntakeStatus.REVIEW_NOT_PUBLISHED.equals(s.rejectReview.warning) ? WizardMessages.get(pLocale, "wizard.intake.review.notPublished") : s.rejectReview.warning)).append("</p>");
		}
		pOut.append("</div>");
	}

	/** One row of "Recent chains". MIRRORED by wizard.js ({@code chainRow}). */
	static String chainRowHtml(final IntakeStatus.Chain pChain, final java.util.Locale pLocale, final long pZoneOffsetMs)
	{
		final StringBuilder out = new StringBuilder();
		out.append("<tr data-in-chain=\"").append(WizardPage.esc(pChain.id)).append("\"><td>").append(WizardPage.esc(IntakeScreens.dateTime(IntakeScreens.millis(pChain.startedAt, -1L), pZoneOffsetMs, pLocale)))
			.append("</td><td>").append(WizardPage.esc(IntakeScreens.joinSources(pChain.sources))).append("</td><td>").append(WizardPage.esc(IntakeScreens.rowsText(pChain, pLocale)))
			.append("</td><td class=\"num\">").append(pChain.rejected == null ? "—" : String.valueOf(pChain.rejected.longValue())).append("</td><td>");
		final long started = IntakeScreens.millis(pChain.startedAt, -1L);
		final long ended = IntakeScreens.millis(pChain.endedAt, -1L);
		out.append(started >= 0 && ended >= started ? WizardPage.esc(IntakeScreens.duration((ended - started) / 1000L)) : "—");
		out.append("</td><td><span class=\"mdmf-chip ").append(IntakeScreens.resultClass(pChain.status)).append("\">").append(WizardPage.esc(IntakeScreens.resultText(pChain, pLocale))).append("</span></td></tr>");
		return out.toString();
	}

	/** "300 new - 300 written - 12 unchanged": the rows counted at launch, then what the load reports once it is known. */
	static String rowsText(final IntakeStatus.Chain pChain, final java.util.Locale pLocale)
	{
		final List<String> parts = new ArrayList<String>();
		if (pChain.rowsWaiting != null)
		{
			parts.add(WizardMessages.get(pLocale, "wizard.intake.rows.new", String.valueOf(pChain.rowsWaiting.longValue())));
		}
		if (pChain.written != null)
		{
			parts.add(WizardMessages.get(pLocale, "wizard.intake.rows.written", String.valueOf(pChain.written.longValue())));
			if (pChain.unchanged != null)
			{
				parts.add(WizardMessages.get(pLocale, "wizard.intake.rows.unchanged", String.valueOf(pChain.unchanged.longValue())));
			}
		}
		if (parts.isEmpty())
		{
			return "—";
		}
		final StringBuilder out = new StringBuilder();
		for (final String part : parts)
		{
			if (out.length() > 0)
			{
				out.append(" · ");
			}
			out.append(part);
		}
		return out.toString();
	}

	private static String joinSources(final List<String> pSources)
	{
		final StringBuilder out = new StringBuilder();
		for (final String source : pSources)
		{
			if (out.length() > 0)
			{
				out.append(", ");
			}
			out.append(source);
		}
		return out.length() == 0 ? "—" : out.toString();
	}

	static String resultClass(final String pStatus)
	{
		if ("DONE".equals(pStatus))
		{
			return "mdmf-chip-real";
		}
		if ("DONE_WITH_REJECTS".equals(pStatus))
		{
			return "mdmf-chip-warn";
		}
		if ("STOPPED".equals(pStatus) || "FAILED".equals(pStatus))
		{
			return "mdmf-chip-bad";
		}
		return "mdmf-chip-accent";
	}

	/** Running / Done / Done - n to review / Stopped: reason / Failed: message. */
	static String resultText(final IntakeStatus.Chain pChain, final java.util.Locale pLocale)
	{
		final String status = pChain.status == null ? "RUNNING" : pChain.status;
		final boolean hasMessage = pChain.message != null && !pChain.message.isEmpty();
		if ("DONE_WITH_REJECTS".equals(status))
		{
			return WizardMessages.get(pLocale, "wizard.intake.result.DONE_WITH_REJECTS", pChain.rejected == null ? "" : String.valueOf(pChain.rejected.longValue()));
		}
		if (("STOPPED".equals(status) || "FAILED".equals(status)) && hasMessage)
		{
			return WizardMessages.get(pLocale, "wizard.intake.result." + status, pChain.message);
		}
		final String key = "wizard.intake.result." + status + ("STOPPED".equals(status) || "FAILED".equals(status) ? ".plain" : "");
		final String text = WizardMessages.get(pLocale, key);
		return key.equals(text) ? status : text;
	}

	// ------------------------------------------------------------------------------------------------------ helpers

	private static String sourceCodes(final IntakeStatus pStatus)
	{
		final StringBuilder out = new StringBuilder();
		for (final IntakeStatus.Source source : pStatus.sources)
		{
			if (out.length() > 0)
			{
				out.append(',');
			}
			out.append(source.sourceSystem);
		}
		return out.toString();
	}

	/** The ids of the chains the page shows as running: the script reloads the step once any of them has ended. */
	private static String runningChainIds(final IntakeStatus pStatus)
	{
		final StringBuilder out = new StringBuilder();
		for (final IntakeStatus.Chain chain : pStatus.chains)
		{
			if (IntakeStatus.STATE_RUNNING.equals(chain.status))
			{
				if (out.length() > 0)
				{
					out.append(',');
				}
				out.append(chain.id);
			}
		}
		return out.toString();
	}

	static long millis(final Instant pInstant, final long pFallback)
	{
		return pInstant == null ? pFallback : pInstant.toEpochMilli();
	}

	/** 12 s, 1 min 12 s, 3 min, 1 h 05 min: mirrors the script's {@code dur}. */
	static String duration(final long pSeconds)
	{
		final long s = Math.max(0L, pSeconds);
		if (s < 60)
		{
			return s + " s";
		}
		final long minutes = s / 60;
		final long rest = s % 60;
		if (minutes < 60)
		{
			return rest == 0 ? minutes + " min" : minutes + " min " + rest + " s";
		}
		final long hours = minutes / 60;
		final long m = minutes % 60;
		return m == 0 ? hours + " h" : hours + " h " + (m < 10 ? "0" : "") + m + " min";
	}

	/** HH:mm in the server's zone ({@code pZoneOffsetMs} added to the instant). */
	static String clock(final long pMs, final long pZoneOffsetMs)
	{
		final long local = pMs + pZoneOffsetMs;
		final long minuteOfDay = Math.floorMod(Math.floorDiv(local, 60000L), 1440L);
		final long h = minuteOfDay / 60;
		final long m = minuteOfDay % 60;
		return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
	}

	/** "24 Sep 14:32": day, short month name of the locale, clock - mirrors the script's {@code dateTime}. */
	static String dateTime(final long pMs, final long pZoneOffsetMs, final java.util.Locale pLocale)
	{
		if (pMs < 0)
		{
			return "—";
		}
		final long local = pMs + pZoneOffsetMs;
		final java.util.Calendar calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("GMT"), Locale.ROOT);
		calendar.setTimeInMillis(local);
		final String[] months = DateFormatSymbols.getInstance(pLocale == null ? Locale.ENGLISH : pLocale).getShortMonths();
		return calendar.get(java.util.Calendar.DAY_OF_MONTH) + " " + months[calendar.get(java.util.Calendar.MONTH)] + " " + IntakeScreens.clock(pMs, pZoneOffsetMs);
	}

	/** The words the script needs to repaint: states, patterns with {0}/{1}, the result chips, the month names. */
	static ObjectNode labels(final java.util.Locale pLocale)
	{
		final ObjectNode out = IntakeScreens.MAPPER.createObjectNode();
		final ObjectNode state = out.putObject("state");
		for (final String key : new String[] { "IDLE", "RECEIVING", "DUE", "RUNNING" })
		{
			state.put(key, WizardMessages.get(pLocale, "wizard.intake.src.state." + key));
		}
		final String[] simple = { "rowsWaiting", "rowsArriving", "nothing", "running", "due", "atLatest", "lastLine", "why" };
		for (final String key : simple)
		{
			out.put(key, WizardMessages.get(pLocale, "wizard.intake.src." + key));
		}
		final ObjectNode reason = out.putObject("reason");
		for (final String key : new String[] { "QUIET", "MAX_WAIT", "BATCH_COMPLETE", "MANUAL", "SWEEP" })
		{
			reason.put(key, WizardMessages.get(pLocale, "wizard.intake.reason." + key));
		}
		final ObjectNode result = out.putObject("result");
		for (final String key : new String[] { "RUNNING", "DONE", "DONE_WITH_REJECTS", "STOPPED", "FAILED", "STOPPED.plain", "FAILED.plain" })
		{
			result.put(key, WizardMessages.get(pLocale, "wizard.intake.result." + key));
		}
		out.put("rowsNew", WizardMessages.get(pLocale, "wizard.intake.rows.new"));
		out.put("rowsWritten", WizardMessages.get(pLocale, "wizard.intake.rows.written"));
		out.put("rowsUnchanged", WizardMessages.get(pLocale, "wizard.intake.rows.unchanged"));
		out.put("dash", "—");
		final ArrayNode months = out.putArray("months");
		final String[] shortMonths = DateFormatSymbols.getInstance(pLocale == null ? Locale.ENGLISH : pLocale).getShortMonths();
		for (int i = 0; i < 12; i++) // getShortMonths() has a 13th (empty) entry for calendars with a thirteenth month
		{
			months.add(shortMonths[i]);
		}
		return out;
	}
}
