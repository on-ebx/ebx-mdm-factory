package com.on.ebx.factory.ebx.ui;

import java.util.Date;
import java.util.Locale;

import com.on.ebx.factory.ebx.RejectReview;
import com.on.ebx.factory.rejects.RejectFilter;
import com.on.ebx.factory.rejects.RejectRow;

/** JUnit-free: run the main, a failure throws. Audit F-38 (wave 10): the transfer-reject review page and the Cockpit's use of the aggregate summary. */
public final class RejectReviewPageTest
{
	private static final Date NOW = new Date(1790000000000L);

	public static void main(final String[] pArgs)
	{
		RejectReviewPageTest.stewardSeesActionsAndCheckboxes();
		RejectReviewPageTest.viewerSeesNoAction();
		RejectReviewPageTest.valuesAreEscaped();
		RejectReviewPageTest.emptyStatesAndPager();
		RejectReviewPageTest.bothLocales();
		RejectReviewPageTest.cockpitUsesTheAggregateAndHidesAcknowledged();
		RejectReviewPageTest.codedReasonsAreWordedInTheViewersLanguage();
		RejectReviewPageTest.noDoubleEscaping();
		RejectReviewPageTest.groupsSummarizeIdenticalReasons();
		RejectReviewPageTest.resolvedGroupsAreNotListedLikeOpenOnes();
		RejectReviewPageTest.backLinkAndFilterLayout();
		System.out.println("RejectReviewPageTest OK");
	}

	private static RejectReviewPage.Model model(final boolean pSteward)
	{
		final RejectReviewPage.Model model = new RejectReviewPage.Model();
		model.projectCode = "Companies";
		model.projectLabel = "Companies";
		model.canSteward = pSteward;
		model.csvUrl = "/csv?a=1&b=2";
		model.cockpitUrl = "/cockpit";
		model.entities.add(new String[] { "7", "Supplier" });
		final RejectReview.Summary summary = new RejectReview.Summary();
		summary.entityId = "7";
		summary.entityName = "Supplier";
		summary.stage = "STAGING";
		summary.open = 3;
		summary.acknowledged = 2;
		summary.resolved = 5;
		model.summaries.add(summary);
		model.rows.add(RejectReviewPageTest.row("ERP", "S-1", "mandatory field missing", false, false));
		model.rows.add(RejectReviewPageTest.row("ERP", "S-2", "too long", false, true));
		model.rows.add(RejectReviewPageTest.row("CRM", "S-3", "fixed", true, false));
		model.total = 10;
		model.openMatching = 3;
		model.acknowledgedMatching = 2;
		return model;
	}

	private static RejectRow row(final String pSystem, final String pId, final String pReasons, final boolean pResolved, final boolean pAcknowledged)
	{
		final RejectRow row = new RejectRow();
		row.entityId = "7";
		row.entityName = "Supplier";
		row.stage = "STAGING";
		row.sourceSystem = pSystem;
		row.sourceId = pId;
		row.reasons = pReasons;
		row.rejectedAt = RejectReviewPageTest.NOW;
		row.resolved = pResolved;
		row.acknowledged = pAcknowledged;
		if (pAcknowledged)
		{
			row.reviewedBy = "steward1";
			row.reviewedAt = RejectReviewPageTest.NOW;
			row.reviewComment = "known issue of the ERP";
		}
		return row;
	}

	private static void stewardSeesActionsAndCheckboxes()
	{
		final String html = RejectReviewPage.render(RejectReviewPageTest.model(true));
		RejectReviewPageTest.check("a checkbox for an open and an acknowledged row, none for a resolved one", count(html, "name=\"mdmfRjSel\"") == 2);
		RejectReviewPageTest.check("the four actions", html.contains("mdmfRj('ACK_SELECTED')") && html.contains("mdmfRj('REOPEN_SELECTED')") && html.contains("mdmfRj('ACK_ALL')")
			&& html.contains("mdmfRj('REOPEN_ALL')"));
		RejectReviewPageTest.check("the bulk buttons say how many rows they touch", html.contains("all 3 open rows") && html.contains("all 2 acknowledged rows"));
		RejectReviewPageTest.check("a bulk action asks for a confirmation", html.contains("confirm("));
		RejectReviewPageTest.check("the comment field", html.contains("name=\"mdmfRjComment\""));
		RejectReviewPageTest.check("who reviewed and why is shown", html.contains("steward1") && html.contains("known issue of the ERP"));
		RejectReviewPageTest.check("the CSV link keeps its ampersand escaped", html.contains("href=\"/csv?a=1&amp;b=2\""));
		RejectReviewPageTest.check("the summary totals", html.contains("<b>3</b>") && html.contains("<b>2</b>") && html.contains("<b>5</b>"));
		RejectReviewPageTest.check("the script clicks the submit button", RejectReviewPage.script().contains("mdmfRjSubmit") && RejectReviewPage.script().contains("mdmfRjAction"));
	}

	private static void viewerSeesNoAction()
	{
		final String html = RejectReviewPage.render(RejectReviewPageTest.model(false));
		RejectReviewPageTest.check("no checkbox", !html.contains("mdmfRjSel"));
		RejectReviewPageTest.check("no acknowledge or reopen button", !html.contains("ACK_SELECTED") && !html.contains("ACK_ALL") && !html.contains("REOPEN"));
		RejectReviewPageTest.check("no comment field", !html.contains("mdmfRjComment"));
		RejectReviewPageTest.check("says why", html.contains("STEWARD"));
		RejectReviewPageTest.check("can still filter and export", html.contains("APPLY") && html.contains("/csv?"));
	}

	private static void valuesAreEscaped()
	{
		final RejectReviewPage.Model model = RejectReviewPageTest.model(true);
		model.rows.get(0).reasons = "<script>alert(1)</script>";
		model.rows.get(0).sourceId = "x\"'><b>";
		model.rows.get(1).reviewComment = "<img src=x onerror=alert(2)>";
		model.notice = "<b>bad</b>";
		model.filter.reasonContains = "\"><script>";
		model.entities.add(new String[] { "8", "A&B <C>" });
		final String html = RejectReviewPage.render(model);
		RejectReviewPageTest.check("no raw script tag", !html.contains("<script>"));
		RejectReviewPageTest.check("no raw image tag", !html.contains("<img"));
		RejectReviewPageTest.check("no raw bold from the notice", !html.contains("<b>bad</b>"));
		RejectReviewPageTest.check("the entity name is escaped", html.contains("A&amp;B &lt;C&gt;"));
		RejectReviewPageTest.check("the filter value is escaped in its attribute", html.contains("value=\"&quot;&gt;&lt;script&gt;\""));
	}

	private static void emptyStatesAndPager()
	{
		final RejectReviewPage.Model empty = new RejectReviewPage.Model();
		empty.projectCode = "P";
		final String html = RejectReviewPage.render(empty);
		RejectReviewPageTest.check("no rejects at all", html.contains(Messages.get(RejectReviewPage.class, Locale.ENGLISH, "rejects.summary.none")));
		RejectReviewPageTest.check("an empty list says so", html.contains(Messages.get(RejectReviewPage.class, Locale.ENGLISH, "rejects.list.none")));
		final RejectReviewPage.Model second = RejectReviewPageTest.model(true);
		second.offset = 50;
		second.total = 120;
		final String page = RejectReviewPage.render(second);
		RejectReviewPageTest.check("previous and next on a middle page", page.contains("PAGE_PREV") && page.contains("PAGE_NEXT"));
		RejectReviewPageTest.check("the range", page.contains("Rows 51 to 53 of 120"));
		RejectReviewPageTest.check("the offset travels", page.contains("name=\"mdmfRjOffset\" value=\"50\""));
		final RejectReviewPage.Model first = RejectReviewPageTest.model(true);
		first.total = 3;
		final String one = RejectReviewPage.render(first);
		RejectReviewPageTest.check("a single page has no pager", !one.contains("PAGE_PREV") && !one.contains("PAGE_NEXT"));
	}

	private static void bothLocales()
	{
		final RejectReviewPage.Model model = RejectReviewPageTest.model(true);
		model.locale = Locale.FRENCH;
		final String html = RejectReviewPage.render(model);
		RejectReviewPageTest.check("French labels", html.contains("Rejets de transfert") && html.contains("Prendre en compte") && html.contains("Appliquer le filtre"));
		for (final String key : new String[] { "rejects.label", "rejects.description", "rejects.title", "rejects.lead", "rejects.action.ackAll", "rejects.notice.commentRequired", "rejects.export",
			"cockpit.rejects.review", "cockpit.rejects.acknowledged", "cockpit.backup.title", "cockpit.backup.lead", "cockpit.backup.download", "wizard.step7.open.gotoRejects" })
		{
			for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
			{
				RejectReviewPageTest.check(key + " is defined in " + locale, !key.equals(Messages.get(RejectReviewPage.class, locale, key)));
			}
		}
	}

	private static void cockpitUsesTheAggregateAndHidesAcknowledged()
	{
		final CockpitData.Inputs in = new CockpitData.Inputs();
		in.now = RejectReviewPageTest.NOW;
		final CockpitData.RejectInfo open = new CockpitData.RejectInfo();
		open.entityName = "Supplier";
		open.stage = "STAGING";
		open.rejectedAt = RejectReviewPageTest.NOW;
		final CockpitData.RejectInfo acknowledged = new CockpitData.RejectInfo();
		acknowledged.entityName = "Supplier";
		acknowledged.stage = "STAGING";
		acknowledged.acknowledged = true;
		acknowledged.rejectedAt = RejectReviewPageTest.NOW;
		in.rejects.add(open);
		in.rejects.add(acknowledged);
		CockpitData.Snapshot s = CockpitData.compute(in);
		RejectReviewPageTest.check("an acknowledged reject is not counted as open (sample fallback)", s.rejects == 1 && s.rejectSummaries.size() == 1 && s.rejectSummaries.get(0).unresolvedCount == 1);
		in.rejectSummaries = new java.util.ArrayList<CockpitData.RejectSummary>();
		in.rejectSummaries.add(new CockpitData.RejectSummary("Supplier", "STAGING", 4000));
		in.rejectSummaries.add(new CockpitData.RejectSummary("Customer", "MASTER", 0));
		in.unresolvedRejectsTotal = Integer.valueOf(4000);
		s = CockpitData.compute(in);
		RejectReviewPageTest.check("the aggregate replaces the sample: its count, and no empty line", s.rejectSummaries.size() == 1 && s.rejectSummaries.get(0).unresolvedCount == 4000 && s.rejects == 4000);
		final CockpitPage.Model model = new CockpitPage.Model();
		model.snapshot = s;
		model.rejectsUrl = "/rejects?x=1&y=2";
		model.backupUrl = "/ebx-mdm-factory/rest/v1/projects/P/backup";
		final String html = CockpitPage.render(model);
		RejectReviewPageTest.check("the cockpit links to the review", html.contains("href=\"/rejects?x=1&amp;y=2\""));
		RejectReviewPageTest.check("and offers the backup", html.contains("/projects/P/backup"));
		model.backupUrl = "";
		RejectReviewPageTest.check("no backup link without it", !CockpitPage.render(model).contains("/backup"));
		s.latestRejects.add(acknowledged);
		RejectReviewPageTest.check("an acknowledged reject has its own chip", CockpitPage.render(model).contains(Messages.get(CockpitPage.class, Locale.ENGLISH, "cockpit.rejects.acknowledged")));
	}

	private static int count(final String pText, final String pNeedle)
	{
		int n = 0;
		for (int at = pText.indexOf(pNeedle); at >= 0; at = pText.indexOf(pNeedle, at + 1))
		{
			n++;
		}
		return n;
	}

	/** What the live test stored for 140 loyalty customers, and the same row once the loader classifies it (2026-10-17). */
	private static final String OLD_TEXT = "EBX refused the save: Unexpected error...\n\r &gt; Caused by: ODateFormat[objectConverters=null, stringConverters=null]"
		+ "conversion error while handling input string '10/06/1963'";

	private static RejectRow codedRow()
	{
		final com.on.ebx.factory.rejects.RejectReason reason = com.on.ebx.factory.rejects.RejectReason.convert(com.on.ebx.factory.rejects.RejectReason.CONVERT_DATE, "birthDate",
			"date_naissance", "10/06/1963", "Unexpected error...\n\r > Caused by: ODateFormat[x]conversion error while handling input string '10/06/1963'");
		final RejectRow row = RejectReviewPageTest.row("LOYALTY", "FID1", com.on.ebx.factory.rejects.RejectReasons.store(java.util.Arrays.asList(reason), 2000), false, false);
		row.category = "EBX_REFUSAL";
		row.reasonCode = reason.signature();
		row.detail = reason.detail;
		return row;
	}

	private static void codedReasonsAreWordedInTheViewersLanguage()
	{
		final RejectReviewPage.Model model = RejectReviewPageTest.model(true);
		model.rows.clear();
		model.rows.add(RejectReviewPageTest.codedRow());
		model.locale = Locale.FRENCH;
		final String fr = RejectReviewPage.render(model);
		RejectReviewPageTest.check("French sentence naming field, column and value", fr.contains("birthDate (colonne source date_naissance)") && fr.contains("10/06/1963")
			&& fr.contains("n\u2019est pas une date valide"));
		RejectReviewPageTest.check("no marker on screen", !fr.contains("[mdmf:") && !fr.contains("mdmf:rejects"));
		RejectReviewPageTest.check("the raw text is folded detail", fr.contains("<details class=\"mdmf-reject-detail\"><summary>D\u00E9tail technique</summary><pre>Unexpected error...") && fr.contains("ODateFormat"));
		model.locale = Locale.ENGLISH;
		final String en = RejectReviewPage.render(model);
		RejectReviewPageTest.check("English sentence", en.contains("birthDate (source column date_naissance)") && en.contains("is not a valid date") && en.contains("<summary>Technical detail</summary>"));
	}

	private static void noDoubleEscaping()
	{
		final RejectReviewPage.Model model = RejectReviewPageTest.model(true);
		model.rows.clear();
		model.rows.add(RejectReviewPageTest.row("LOYALTY", "FID1", RejectReviewPageTest.OLD_TEXT, false, false)); // a reject stored before the lot: EBX's text, already escaped once
		final String html = RejectReviewPage.render(model);
		RejectReviewPageTest.check("no literal gt entity on screen (it used to show &amp;gt;)", !html.contains("&amp;gt;") && !html.contains("&amp;amp;"));
		RejectReviewPageTest.check("an old conversion refusal is still worded (value named)", html.contains("10/06/1963") && html.contains("is not a valid date"));
		RejectReviewPageTest.check("its raw text is one escaped, folded detail", html.contains("<pre>") && html.contains("&gt; Caused by: ODateFormat"));
		final RejectReviewPage.Model plain = RejectReviewPageTest.model(true);
		plain.rows.clear();
		plain.rows.add(RejectReviewPageTest.row("ERP", "S1", "a > b & c", false, false));
		final String text = RejectReviewPage.render(plain);
		RejectReviewPageTest.check("a plain text is escaped exactly once: " + text.substring(text.indexOf("a &")), text.contains("a &gt; b &amp; c"));
	}

	private static RejectReview.Group group(final String pEntityId, final String pEntity, final RejectRow pRow, final long pOpen, final long pAcknowledged, final long pResolved)
	{
		final RejectReview.Group group = new RejectReview.Group();
		group.entityId = pEntityId;
		group.entityName = pEntity;
		group.reasonCode = pRow.reasonCode;
		group.open = pOpen;
		group.acknowledged = pAcknowledged;
		group.resolved = pResolved;
		group.count = pOpen + pAcknowledged + pResolved;
		group.sampleReasons = pRow.reasons;
		group.sampleCategory = pRow.category;
		group.sampleDetail = pRow.detail;
		return group;
	}

	/**
	 * Staging last points, item 2 (2026-10-17): live, the 140 date rejects of a loyalty file were listed under "why the rows were set aside" exactly like the open ones
	 * after the fix resolved them. Open groups come first with the primary button and their own counts; a group a later load resolved entirely is behind a fold
	 * ("Resolved (N)"), its button shows resolved rows, and the fold is open only when the person filters on resolved rows.
	 */
	private static void resolvedGroupsAreNotListedLikeOpenOnes()
	{
		final RejectRow coded = RejectReviewPageTest.codedRow();
		final RejectReviewPage.Model model = RejectReviewPageTest.model(true);
		model.locale = Locale.FRENCH;
		model.groups.add(RejectReviewPageTest.group("7", "Customer", coded, 12, 3, 140));
		model.groups.add(RejectReviewPageTest.group("8", "Order", coded, 0, 0, 140));
		model.groupTotal = 2;
		final String html = RejectReviewPage.render(model);
		final int fold = html.indexOf("<details class=\"mdmf-rejects-resolved\"");
		RejectReviewPageTest.check("a fold for the resolved groups: " + html, fold > 0 && html.contains("R\u00E9solus (1)") && !html.contains("<details class=\"mdmf-rejects-resolved\" open"));
		final String open = html.substring(0, fold);
		final String resolved = html.substring(fold, html.indexOf("</details>", fold));
		RejectReviewPageTest.check("the open group is outside the fold with its counts, open against resolved", open.contains("Customer") && open.contains("12 ouvertes, 3 prises en compte, 140 r\u00E9solues")
			&& !open.contains("Order"));
		RejectReviewPageTest.check("its button is the primary one and asks for any status", open.contains("class=\"mdmf-btn mdmf-btn-primary mdmf-btn-sm\" onclick=\"mdmfRjGroup('7','" + coded.reasonCode + "','')\"")
			&& open.contains("Voir ces lignes"));
		RejectReviewPageTest.check("the resolved group is in the fold, counted as resolved, with no primary button", resolved.contains("Order") && resolved.contains("140 r\u00E9solues")
			&& !resolved.contains("mdmf-btn-primary") && !resolved.contains("ouvertes") && !resolved.contains("Voir ces lignes"));
		RejectReviewPageTest.check("its button shows the resolved rows only", resolved.contains("mdmfRjGroup('8','" + coded.reasonCode + "','RESOLVED')") && resolved.contains("Voir les lignes r\u00E9solues"));
		RejectReviewPageTest.check("no 'what to do' for a resolved group (nothing to do)", !resolved.contains("Que faire"));
		RejectReviewPageTest.check("the fold says why it is there", resolved.contains("Un chargement ult\u00E9rieur a \u00E9crit ces lignes"));
		// every group resolved: the fold alone, with the sentence that nothing is left
		final RejectReviewPage.Model allResolved = RejectReviewPageTest.model(true);
		allResolved.locale = Locale.FRENCH;
		allResolved.groups.add(RejectReviewPageTest.group("7", "Customer", coded, 0, 0, 140));
		allResolved.groupTotal = 1;
		final String done = RejectReviewPage.render(allResolved);
		RejectReviewPageTest.check("nothing left to look at is said", done.contains("Aucun groupe n\u2019a de ligne \u00E0 regarder") && done.contains("R\u00E9solus (1)") && !done.contains("mdmf-btn-primary mdmf-btn-sm\" onclick=\"mdmfRjGroup"));
		// filtering on resolved rows opens the fold and does not say that nothing is left
		allResolved.filter.status = RejectFilter.RESOLVED;
		final String filtered = RejectReviewPage.render(allResolved);
		RejectReviewPageTest.check("the resolved filter opens the fold", filtered.contains("<details class=\"mdmf-rejects-resolved\" open") && !filtered.contains("Aucun groupe n\u2019a de ligne"));
		// English, one acknowledged-only group is still pending (not resolved)
		final RejectReviewPage.Model en = RejectReviewPageTest.model(true);
		en.groups.add(RejectReviewPageTest.group("7", "Customer", coded, 0, 5, 0));
		final String enHtml = RejectReviewPage.render(en);
		RejectReviewPageTest.check("an acknowledged group stays in the main list", !enHtml.contains("mdmf-rejects-resolved") && enHtml.contains("5 acknowledged"));
		// the resolvedOnly rule
		RejectReviewPageTest.check("resolvedOnly: needs resolved rows and nothing else", RejectReviewPageTest.group("1", "E", coded, 0, 0, 3).resolvedOnly()
			&& !RejectReviewPageTest.group("1", "E", coded, 1, 0, 3).resolvedOnly() && !RejectReviewPageTest.group("1", "E", coded, 0, 1, 3).resolvedOnly()
			&& !new RejectReview.Group().resolvedOnly());
		RejectReviewPageTest.check("the script lets the helper set the status", RejectReviewPage.script().contains("[name=mdmfRjStatus]") && RejectReviewPage.script().contains("typeof st==='string'"));
	}

	private static void groupsSummarizeIdenticalReasons()
	{
		final RejectReviewPage.Model model = RejectReviewPageTest.model(true);
		final RejectRow coded = RejectReviewPageTest.codedRow();
		final RejectReview.Group group = new RejectReview.Group();
		group.entityId = "7";
		group.entityName = "Customer";
		group.reasonCode = coded.reasonCode;
		group.count = 140;
		group.sampleReasons = coded.reasons;
		group.sampleCategory = coded.category;
		group.sampleDetail = coded.detail;
		final RejectReview.Group old = new RejectReview.Group();
		old.entityId = "8";
		old.entityName = "Supplier";
		old.count = 1;
		old.sampleReasons = "mandatory field missing";
		model.groups.add(group);
		model.groups.add(old);
		model.groupTotal = 12;
		model.locale = Locale.FRENCH;
		final String html = RejectReviewPage.render(model);
		RejectReviewPageTest.check("the summary of why", html.contains("Pourquoi les lignes ont \u00E9t\u00E9 mises de c\u00F4t\u00E9"));
		RejectReviewPageTest.check("140 rows with one sentence, no value in the group wording", html.contains("140 lignes") && html.contains("<b>birthDate (colonne source date_naissance) : date qui n\u2019est pas au format attendu"));
		RejectReviewPageTest.check("what to do, naming the source column", html.contains("Que faire") && html.contains("format de date de la colonne source date_naissance"));
		RejectReviewPageTest.check("an example with the value", html.contains("Par exemple") && html.contains("10/06/1963"));
		RejectReviewPageTest.check("a button per group sets the entity and the kind of reason", html.contains("mdmfRjGroup('7','" + group.reasonCode + "','')"));
		RejectReviewPageTest.check("a reject with no code is its own group, filtered by the uncoded marker", html.contains("mdmfRjGroup('8','-','')") && html.contains("Autres motifs"));
		RejectReviewPageTest.check("the groups left out are said: " + html, html.contains("Et 10 autres sortes de motifs"));
		RejectReviewPageTest.check("the script has the group helper", RejectReviewPage.script().contains("window.mdmfRjGroup=function(e,c,st)") && RejectReviewPage.script().contains("mdmfRjCode"));
		final RejectReviewPage.Model none = RejectReviewPageTest.model(true);
		RejectReviewPageTest.check("no group, no summary of why", !RejectReviewPage.render(none).contains("Why the rows were set aside"));
		final RejectReviewPage.Model filtered = RejectReviewPageTest.model(true);
		filtered.filter.reasonCode = "convert.date|birthDate|date_naissance";
		final String chip = RejectReviewPage.render(filtered);
		RejectReviewPageTest.check("the kind of reason travels with the form and is shown while it holds", chip.contains("name=\"mdmfRjCode\" value=\"convert.date|birthDate|date_naissance\"")
			&& chip.contains("A single kind of reason"));
		RejectReviewPageTest.check("an empty code is still posted (the helper sets it)", RejectReviewPage.render(RejectReviewPageTest.model(true)).contains("name=\"mdmfRjCode\" value=\"\""));
	}

	private static void backLinkAndFilterLayout()
	{
		final String html = RejectReviewPage.render(RejectReviewPageTest.model(true));
		RejectReviewPageTest.check("the back link is addressable and carries its wordings", html.contains("id=\"mdmfRjBack\"") && html.contains("data-label-wizard=\"Back to the wizard\"")
			&& html.contains("data-label-back=\"Back\"") && html.contains("href=\"/cockpit\"") && html.contains(">Back to the Cockpit</a>"));
		final RejectReviewPage.Model fr = RejectReviewPageTest.model(true);
		fr.locale = Locale.FRENCH;
		RejectReviewPageTest.check("French wordings", RejectReviewPage.render(fr).contains("data-label-wizard=\"Retour \u00E0 l\u2019assistant\""));
		// the labels: text first, control second, one label per control (the css lays them out as columns)
		final int filter = html.indexOf("mdmf-rejects-filter");
		RejectReviewPageTest.check("every filter label wraps its own control", filter > 0 && count(html.substring(filter), "<label>") >= 6);
		final String css = RejectReviewPageTest.stylesheet();
		RejectReviewPageTest.check("the css puts each label above its control", css.contains(".mdmf-rejects-filter label{display:flex;flex-direction:column"));
		RejectReviewPageTest.check("and keeps the controls at a fixed width", css.contains(".mdmf-rejects-filter select") && css.contains("width:auto !important"));
	}

	private static String stylesheet()
	{
		try
		{
			return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"))
				.resolve("../webapp/www/common/stylesheets/factory/factory.css").normalize()), java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (final java.io.IOException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
