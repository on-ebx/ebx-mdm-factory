package com.on.ebx.factory.ebx.ui;

import java.util.Date;
import java.util.Locale;

/** JUnit-free: run the main, a failure throws. Exercises {@link CockpitPage} without any EBX type. */
public final class CockpitPageTest
{
	/** A fixed instant, never {@code new Date()}: the "When" column's relative text must be reproducible. */
	private static final Date NOW = new Date(1790000000000L);

	public static void main(final String[] pArgs)
	{
		CockpitPageTest.everyValueIsEscaped();
		CockpitPageTest.emptyStatesRenderInBothLocales();
		CockpitPageTest.frenchRendersAccentedLabels();
		CockpitPageTest.driftAlertRendersTheRealMessage();
		CockpitPageTest.whenColumnShowsARelativeTimeWithAnAbsoluteTooltip();
		CockpitPageTest.whenColumnForAStillRunningStepSaysHowLongItHasBeenRunning();
		CockpitPageTest.runsBadgeOnlyAppearsWhenAStepRanMoreThanOnce();
		CockpitPageTest.stuckHintOnlyAppearsOnAStuckStep();
		CockpitPageTest.projectGroupUsesItsOwnLabelAboveTheSources();
		CockpitPageTest.lineageLinkCarriesTheProjectCodeUrlEncoded();

		CockpitPageTest.stagingVolumeDetailShowsFedByLastLoadAndRejectedCount();
		CockpitPageTest.stagingStatusChipsCoverEveryCase();

		CockpitPageTest.relationsSectionShowsEvidenceOrNeverChecked();
		CockpitPageTest.relationsEmptyStateWhenNoRelationAtAll();

		CockpitPageTest.agentRunLineRendersThePhaseStatusStepsAndTokens();
		CockpitPageTest.agentRunNoneStateWhenNoRunYet();

		CockpitPageTest.rejectsSectionRendersSummaryAndLatestList();
		CockpitPageTest.rejectsReasonsAreEscapedNeverRawMarkup();
		CockpitPageTest.rejectsAllResolvedStillShowsTheLatestListNotTheCalmNotice();
		CockpitPageTest.rejectsKpiTileReflectsTheRealCount();

		CockpitPageTest.deliveredBannerShowsTheOriginOnlyWhenDelivered();
		CockpitPageTest.instanceRoleHintShowsOnlyWhenKnown();

		System.out.println("CockpitPageTest OK");
	}

	private static void everyValueIsEscaped()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "<script>alert('x')</script>";
		s.pipeline.add(CockpitPageTest.blockWithOneStep("SUPPLIER<img src=x>", "LANDING_MODEL", "DONE", "note <b>bold</b>"));
		s.volumes.add(new CockpitData.VolumeRow("landing", "T&<>\"'", 3L, "LOADED"));
		s.alerts.add(new CockpitData.Alert("T&<>\"'", "DRIFTED", "msg <script>"));

		final CockpitPage.Model model = CockpitPageTest.model(s, Locale.ENGLISH);
		final String html = CockpitPage.render(model);

		CockpitPageTest.check("no raw <script> tag leaked from the project label", html.indexOf("<script>alert") < 0);
		CockpitPageTest.check("the label is present, escaped", html.indexOf("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;") >= 0);
		CockpitPageTest.check("no raw <img tag from a table name", html.indexOf("<img src=x>") < 0);
		CockpitPageTest.check("the step message is escaped, not interpreted as markup", html.indexOf("note &lt;b&gt;bold&lt;/b&gt;") >= 0);
		CockpitPageTest.check("a volume table name with HTML-sensitive characters is escaped",
			html.indexOf("T&amp;&lt;&gt;&quot;&#39;") >= 0);
		CockpitPageTest.check("no raw <script> from an alert message", html.indexOf("msg <script>") < 0);
	}

	private static void emptyStatesRenderInBothLocales()
	{
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final CockpitData.Snapshot s = new CockpitData.Snapshot();
			s.project.label = "Empty project";
			s.volumes.add(new CockpitData.VolumeRow("staging", null, null, "NONE"));
			s.volumes.add(new CockpitData.VolumeRow("master", null, null, "NONE"));
			final String html = CockpitPage.render(CockpitPageTest.model(s, locale));
			// compared against the escaped text, like the page itself renders it: a raw apostrophe (French
			// "l'instant") would falsely fail this check once escaped to &#39; by the page
			CockpitPageTest.check("no pipeline block renders the 'no source' hint (" + locale + ")",
				html.indexOf(CockpitPage.esc(Messages.get(CockpitPage.class, locale, "cockpit.pipeline.none"))) >= 0);
			CockpitPageTest.check("no load ever ran renders the 'no load' hint (" + locale + ")",
				html.indexOf(CockpitPage.esc(Messages.get(CockpitPage.class, locale, "cockpit.loads.none"))) >= 0);
			CockpitPageTest.check("rejects are reported honestly as zero, by design (" + locale + ")",
				html.indexOf(CockpitPage.esc(Messages.get(CockpitPage.class, locale, "cockpit.rejects.none"))) >= 0);
			CockpitPageTest.check("no alert renders the 'no drift' notice (" + locale + ")",
				html.indexOf(CockpitPage.esc(Messages.get(CockpitPage.class, locale, "cockpit.alerts.none"))) >= 0);
			CockpitPageTest.check("the em dash for a null row count, never a fabricated 0 (" + locale + ")", html.indexOf("—") >= 0);
			// the row count itself is an em dash (checked above); the stats panel legitimately shows 0 for an
			// empty project (0 sources, 0 rows loaded), which is honest, not the bug this test class guards against
		}
	}

	private static void frenchRendersAccentedLabels()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "Companies 360";
		final String html = CockpitPage.render(CockpitPageTest.model(s, Locale.FRENCH));
		CockpitPageTest.check("the French step label is used, correctly accented", html.indexOf("État") >= 0 || html.indexOf("Étape") >= 0);
		CockpitPageTest.check("no stray backslash-u escape leaked into the rendered page", html.indexOf("\\u") < 0);
	}

	private static void driftAlertRendersTheRealMessage()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "P";
		s.alerts.add(new CockpitData.Alert("SUPPLIER", "DRIFTED", "new source column DISCOUNT_CODE"));
		final String html = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("the alert names the real table", html.indexOf("SUPPLIER") >= 0);
		CockpitPageTest.check("the alert carries the real message, not a fabricated example", html.indexOf("new source column DISCOUNT_CODE") >= 0);
		CockpitPageTest.check("the 'no drift' notice is absent once there is a real alert",
			html.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.alerts.none")) < 0);
	}

	private static void whenColumnShowsARelativeTimeWithAnAbsoluteTooltip()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "P";
		final Date endedAt = new Date(CockpitPageTest.NOW.getTime() - 2L * 60L * 60L * 1000L); // 2 hours before NOW
		s.pipeline.add(CockpitPageTest.blockWithStep("ERP", CockpitPageTest.row("LANDING_LOAD", "DONE", "", 1, null, endedAt, false)));

		final String en = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("the relative hint (English) is shown", en.indexOf(">2 h ago<") >= 0);

		final String fr = CockpitPage.render(CockpitPageTest.model(s, Locale.FRENCH));
		CockpitPageTest.check("the relative hint (French) is shown", fr.indexOf(">il y a 2 h<") >= 0);
		CockpitPageTest.check("the absolute time is available as a tooltip, not hidden", fr.indexOf("title=\"") >= 0);
	}

	private static void whenColumnForAStillRunningStepSaysHowLongItHasBeenRunning()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "P";
		final Date startedAt = new Date(CockpitPageTest.NOW.getTime() - 10L * 60L * 1000L); // 10 minutes before NOW
		s.pipeline.add(CockpitPageTest.blockWithStep("ERP", CockpitPageTest.row("LANDING_LOAD", "RUNNING", "", 1, startedAt, null, false)));

		final String html = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("a step with no end time yet shows how long it has been running",
			html.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.pipeline.when.running", "10 min ago")) >= 0);
	}

	private static void runsBadgeOnlyAppearsWhenAStepRanMoreThanOnce()
	{
		final CockpitData.Snapshot ranOnce = new CockpitData.Snapshot();
		ranOnce.project.label = "P";
		ranOnce.pipeline.add(CockpitPageTest.blockWithStep("ERP", CockpitPageTest.row("LANDING_LOAD", "DONE", "", 1, null, CockpitPageTest.NOW, false)));
		final String htmlOnce = CockpitPage.render(CockpitPageTest.model(ranOnce, Locale.ENGLISH));
		CockpitPageTest.check("no ×N badge for a step that ran exactly once", htmlOnce.indexOf("×") < 0);

		final CockpitData.Snapshot ranThrice = new CockpitData.Snapshot();
		ranThrice.project.label = "P";
		ranThrice.pipeline.add(CockpitPageTest.blockWithStep("ERP", CockpitPageTest.row("LANDING_LOAD", "DONE", "", 3, null, CockpitPageTest.NOW, false)));
		final String htmlThrice = CockpitPage.render(CockpitPageTest.model(ranThrice, Locale.ENGLISH));
		CockpitPageTest.check("a ×3 badge for a step that ran three times", htmlThrice.indexOf("×3") >= 0);
		CockpitPageTest.check("the badge explains itself in a title", htmlThrice.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.pipeline.runs.title", "3")) >= 0);
	}

	private static void stuckHintOnlyAppearsOnAStuckStep()
	{
		final CockpitData.Snapshot fine = new CockpitData.Snapshot();
		fine.project.label = "P";
		fine.pipeline.add(CockpitPageTest.blockWithStep("ERP", CockpitPageTest.row("LANDING_LOAD", "RUNNING", "", 1, CockpitPageTest.NOW, null, false)));
		final String htmlFine = CockpitPage.render(CockpitPageTest.model(fine, Locale.ENGLISH));
		CockpitPageTest.check("no stuck hint on a step that is not flagged stuck",
			htmlFine.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.pipeline.stuck")) < 0);

		final CockpitData.Snapshot stuck = new CockpitData.Snapshot();
		stuck.project.label = "P";
		stuck.pipeline.add(CockpitPageTest.blockWithStep("ERP", CockpitPageTest.row("LANDING_LOAD", "RUNNING", "", 1, CockpitPageTest.NOW, null, true)));
		final String htmlStuck = CockpitPage.render(CockpitPageTest.model(stuck, Locale.ENGLISH));
		CockpitPageTest.check("the stuck hint shows on a step flagged stuck",
			htmlStuck.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.pipeline.stuck")) >= 0);
	}

	private static void projectGroupUsesItsOwnLabelAboveTheSources()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "P";
		s.pipeline.add(CockpitData.SourceBlock.project());
		s.pipeline.get(0).steps.add(CockpitPageTest.row("STAGING_SUGGEST", "DONE", "", 1, null, CockpitPageTest.NOW, false));
		s.pipeline.add(CockpitPageTest.blockWithStep("ERP", CockpitPageTest.row("LANDING_MODEL", "DONE", "", 1, null, CockpitPageTest.NOW, false)));

		final String en = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("the project group uses its own label, not a blank or a source name",
			en.indexOf(">Project<") >= 0);
		CockpitPageTest.check("the group label comes before the ERP source's own group in the output", en.indexOf(">Project<") < en.indexOf(">ERP<"));

		final String fr = CockpitPage.render(CockpitPageTest.model(s, Locale.FRENCH));
		CockpitPageTest.check("the French project group label is used", fr.indexOf(">Projet<") >= 0);
	}

	private static void stagingVolumeDetailShowsFedByLastLoadAndRejectedCount()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "P";
		final Date lastAt = new Date(CockpitPageTest.NOW.getTime() - 2L * 60L * 60L * 1000L); // 2 hours before NOW
		s.volumes.add(new CockpitData.VolumeRow("staging", "Company", Long.valueOf(107L), "LOADED", Long.valueOf(0L), lastAt, "Supplier (ERP), Account (CRM)"));
		final String html = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("the fed-by hint is shown",
			html.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.volumes.fedBy", "Supplier (ERP), Account (CRM)")) >= 0);
		CockpitPageTest.check("the last-load relative hint is shown", html.indexOf(">2 h ago<") >= 0);
		CockpitPageTest.check("the rejected count hint is shown", html.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.volumes.rejectedCount", "0")) >= 0);
	}

	private static void stagingStatusChipsCoverEveryCase()
	{
		final CockpitData.Snapshot loaded = new CockpitData.Snapshot();
		loaded.project.label = "P";
		loaded.volumes.add(new CockpitData.VolumeRow("staging", "Company", Long.valueOf(107L), "LOADED"));
		final String htmlLoaded = CockpitPage.render(CockpitPageTest.model(loaded, Locale.ENGLISH));
		CockpitPageTest.check("LOADED reuses the shared status chip", htmlLoaded.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "status.LOADED")) >= 0);

		final CockpitData.Snapshot generated = new CockpitData.Snapshot();
		generated.project.label = "P";
		generated.volumes.add(new CockpitData.VolumeRow("staging", "Account", null, "GENERATED"));
		final String htmlGenerated = CockpitPage.render(CockpitPageTest.model(generated, Locale.ENGLISH));
		CockpitPageTest.check("GENERATED reuses the shared status chip, distinct wording from NOT_GENERATED",
			htmlGenerated.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "status.GENERATED")) >= 0);

		final CockpitData.Snapshot notGenerated = new CockpitData.Snapshot();
		notGenerated.project.label = "P";
		notGenerated.volumes.add(new CockpitData.VolumeRow("staging", "Account", null, "NOT_GENERATED"));
		final String htmlNotGenerated = CockpitPage.render(CockpitPageTest.model(notGenerated, Locale.ENGLISH));
		CockpitPageTest.check("NOT_GENERATED shows its own dedicated wording, an accepted entity but no staging dataset yet",
			htmlNotGenerated.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.volumes.status.notGenerated")) >= 0);

		final CockpitData.Snapshot none = new CockpitData.Snapshot();
		none.project.label = "P";
		none.volumes.add(new CockpitData.VolumeRow("staging", null, null, "NONE"));
		final String htmlNone = CockpitPage.render(CockpitPageTest.model(none, Locale.ENGLISH));
		CockpitPageTest.check("NONE (nothing accepted yet) shows the not-proposed wording",
			htmlNone.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.volumes.status.notProposed")) >= 0);
	}

	private static void relationsSectionShowsEvidenceOrNeverChecked()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "P";
		final Date checkedAt = new Date(CockpitPageTest.NOW.getTime() - 60L * 60L * 1000L);
		s.relations.add(new CockpitData.RelationEvidence("CONTACT", "ACCOUNT", "ACCEPTED", checkedAt, Long.valueOf(52L), Long.valueOf(0L)));
		s.relations.add(new CockpitData.RelationEvidence("ACCOUNT", "ACCOUNT", "PROPOSED", null, null, null));
		final String html = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("the checked relation shows its distinct-value count", html.indexOf("52") >= 0);
		CockpitPageTest.check("the relation names appear", html.indexOf("CONTACT") >= 0 && html.indexOf("ACCOUNT") >= 0);
		CockpitPageTest.check("the never-checked relation says so",
			html.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.relations.never")) >= 0);
	}

	private static void relationsEmptyStateWhenNoRelationAtAll()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "P";
		final String html = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("no relation at all shows the empty hint", html.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.relations.none")) >= 0);
	}

	private static void agentRunLineRendersThePhaseStatusStepsAndTokens()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "P";
		final CockpitData.AgentRunInfo run = new CockpitData.AgentRunInfo();
		run.phase = "STAGING_LLM";
		run.status = "DONE";
		run.steps = Integer.valueOf(12);
		run.inputTokens = Long.valueOf(45000L);
		run.outputTokens = Long.valueOf(3000L);
		s.lastAgentRun = run;
		final String html = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("the phase is shown", html.indexOf("STAGING_LLM") >= 0);
		CockpitPageTest.check("the status label is shown", html.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "status.DONE")) >= 0);
		CockpitPageTest.check("the step count is shown", html.indexOf("12") >= 0);
		CockpitPageTest.check("input tokens are shown", html.indexOf("45000") >= 0);
		CockpitPageTest.check("output tokens are shown", html.indexOf("3000") >= 0);
	}

	private static void agentRunNoneStateWhenNoRunYet()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "P";
		final String html = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("no run yet shows the none hint", html.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.agent.none")) >= 0);
	}

	private static void rejectsSectionRendersSummaryAndLatestList()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "P";
		s.rejectSummaries.add(new CockpitData.RejectSummary("Supplier", "STAGING", 2));
		final CockpitData.RejectInfo reject = CockpitPageTest.reject("Supplier", "STAGING", "ERP", "SUP-001", "mandatory field missing", CockpitPageTest.NOW, false);
		s.latestRejects.add(reject);
		final String html = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("the summary's entity name is shown", html.indexOf("Supplier") >= 0);
		CockpitPageTest.check("the summary's stage label is shown", html.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.rejects.stage.STAGING")) >= 0);
		CockpitPageTest.check("the unresolved count is shown", html.indexOf(">2<") >= 0);
		CockpitPageTest.check("the latest reject's sourceId is shown, as an identifier", html.indexOf("SUP-001") >= 0);
		CockpitPageTest.check("the latest reject's reasons are shown", html.indexOf("mandatory field missing") >= 0);
		CockpitPageTest.check("the unresolved chip is shown", html.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.rejects.unresolved")) >= 0);
	}

	private static void rejectsReasonsAreEscapedNeverRawMarkup()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "P";
		s.latestRejects
			.add(CockpitPageTest.reject("Supplier", "STAGING", "ERP", "<script>alert(1)</script>", "<b>bad</b> value", CockpitPageTest.NOW, false));
		final String html = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("no raw script tag from sourceId", html.indexOf("<script>alert(1)</script>") < 0);
		CockpitPageTest.check("sourceId is escaped", html.indexOf("&lt;script&gt;alert(1)&lt;/script&gt;") >= 0);
		CockpitPageTest.check("no raw bold tag from the engine's reasons text", html.indexOf("<b>bad</b>") < 0);
		CockpitPageTest.check("reasons text is escaped", html.indexOf("&lt;b&gt;bad&lt;/b&gt;") >= 0);
	}

	private static void rejectsAllResolvedStillShowsTheLatestListNotTheCalmNotice()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "P";
		// rejectSummaries stays empty (nothing unresolved to group), but latestRejects is not: history, not silence
		s.latestRejects.add(CockpitPageTest.reject("Supplier", "STAGING", "ERP", "SUP-001", "fixed now", CockpitPageTest.NOW, true));
		final String html = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("the calm 'all resolved' notice replaces the summary table",
			html.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.rejects.summary.none")) >= 0);
		CockpitPageTest.check("the latest list still shows the resolved reject", html.indexOf("SUP-001") >= 0);
		CockpitPageTest.check("the resolved chip is shown", html.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.rejects.resolved")) >= 0);
		CockpitPageTest.check("the 'nothing at all recorded' notice must NOT show: there IS a reject, just a resolved one",
			html.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.rejects.none")) < 0);
	}

	private static void rejectsKpiTileReflectsTheRealCount()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.label = "P";
		s.rejects = 3;
		final String html = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("the KPI tile shows the real count, not a hardcoded 0", html.indexOf("<b>3</b>") >= 0);
	}

	/** Delivery lot 2 (docs/DELIVERY.md sec 6.2): the info banner only when the project was delivered, and it names the real origin. */
	private static void deliveredBannerShowsTheOriginOnlyWhenDelivered()
	{
		final CockpitData.Snapshot notDelivered = new CockpitData.Snapshot();
		notDelivered.project.label = "P";
		final String htmlNotDelivered = CockpitPage.render(CockpitPageTest.model(notDelivered, Locale.ENGLISH));
		CockpitPageTest.check("no delivered banner for a local project", htmlNotDelivered.indexOf("mdmf-notice-info") < 0);

		final CockpitData.Snapshot delivered = new CockpitData.Snapshot();
		delivered.project.label = "P";
		delivered.delivered = true;
		delivered.originRepository = "a1b2c3";
		final String html = CockpitPage.render(CockpitPageTest.model(delivered, Locale.ENGLISH));
		CockpitPageTest.check("the info banner is shown", html.indexOf("mdmf-notice-info") >= 0);
		CockpitPageTest.check("the real origin instance id is shown", html.indexOf("a1b2c3") >= 0);
	}

	/** The instance role hint shows the real role and only when one is known (never for an empty string). */
	private static void instanceRoleHintShowsOnlyWhenKnown()
	{
		final CockpitData.Snapshot unknown = new CockpitData.Snapshot();
		unknown.project.label = "P";
		final String htmlUnknown = CockpitPage.render(CockpitPageTest.model(unknown, Locale.ENGLISH));
		CockpitPageTest.check("no role hint when the role is unknown (settings row written before the field existed)",
			htmlUnknown.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.instanceRole.DEVELOPMENT")) < 0
				&& htmlUnknown.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.instanceRole.TARGET")) < 0);

		final CockpitData.Snapshot target = new CockpitData.Snapshot();
		target.project.label = "P";
		target.instanceRole = "TARGET";
		final String htmlTarget = CockpitPage.render(CockpitPageTest.model(target, Locale.ENGLISH));
		CockpitPageTest.check("the TARGET role hint is shown", htmlTarget.indexOf(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.instanceRole.TARGET")) >= 0);
	}

	private static CockpitData.RejectInfo reject(final String pEntityName, final String pStage, final String pSourceSystem, final String pSourceId, final String pReasons,
		final Date pRejectedAt, final boolean pResolved)
	{
		final CockpitData.RejectInfo r = new CockpitData.RejectInfo();
		r.entityName = pEntityName;
		r.stage = pStage;
		r.sourceSystem = pSourceSystem;
		r.sourceId = pSourceId;
		r.reasons = pReasons;
		r.rejectedAt = pRejectedAt;
		r.resolved = pResolved;
		return r;
	}

	private static CockpitData.SourceBlock blockWithOneStep(final String pSourceSystem, final String pStep, final String pStatus, final String pMessage)
	{
		return CockpitPageTest.blockWithStep(pSourceSystem, CockpitPageTest.row(pStep, pStatus, pMessage, 1, null, CockpitPageTest.NOW, false));
	}

	private static CockpitData.SourceBlock blockWithStep(final String pSourceSystem, final CockpitData.StepRow pRow)
	{
		final CockpitData.SourceBlock block = new CockpitData.SourceBlock(pSourceSystem);
		block.steps.add(pRow);
		return block;
	}

	/** Lot 4 (record lineage): the Cockpit's own link to RecordLineage carries the CURRENT project's code, URL-encoded (a code with a space or '&' must not break the query string). */
	private static void lineageLinkCarriesTheProjectCodeUrlEncoded()
	{
		final CockpitData.Snapshot s = new CockpitData.Snapshot();
		s.project.code = "Companies 360";
		s.project.label = "Companies 360";
		final String html = CockpitPage.render(CockpitPageTest.model(s, Locale.ENGLISH));
		CockpitPageTest.check("the link targets RecordLineage with the project code URL-encoded",
			html.indexOf("service=RecordLineage&amp;project=Companies+360") >= 0);
		CockpitPageTest.check("the link navigates the top window, never a nested EBX shell", html.indexOf("target=\"_top\"") >= 0);
		// leave-guard lot (2026-09-23): carries the class FactoryCockpitUserService's own voluntaryLeaveScript
		// call arms, so this full-page link never triggers EBX's own "Quitter le site Web ?" prompt.
		CockpitPageTest.check("the link carries the voluntary-leave marker class", html.indexOf("<a class=\"mdmf-leave-link\" href=\"") >= 0);
	}

	private static CockpitData.StepRow row(final String pStep, final String pStatus, final String pMessage, final int pRuns, final Date pStartedAt, final Date pEndedAt,
		final boolean pStuck)
	{
		return new CockpitData.StepRow(pStep, pStatus, pMessage, pRuns, pStartedAt, pEndedAt, pStuck);
	}

	private static CockpitPage.Model model(final CockpitData.Snapshot pSnapshot, final Locale pLocale)
	{
		final CockpitPage.Model model = new CockpitPage.Model();
		model.locale = pLocale;
		model.snapshot = pSnapshot;
		model.now = CockpitPageTest.NOW;
		return model;
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
