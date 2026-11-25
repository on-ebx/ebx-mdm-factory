package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class ReviewPageTest
{
	public static void main(final String[] pArgs)
	{
		ReviewPageTest.noProjectShowsSelectorNotTable();
		ReviewPageTest.emptyStateWhenNoLineMatches();
		ReviewPageTest.aForgedStatusStaysInsideItsAttribute();
		ReviewPageTest.emptyStateOffersOneClickLinksToOtherMatchingStatuses();
		ReviewPageTest.emptyStateSilentWhenTrulyNothingMatchesAnyStatus();
		ReviewPageTest.mappingStepAndQualityNaturesRenderTheirOwnChip();
		ReviewPageTest.everyLineShowsItsShortReferenceWithATooltip();
		ReviewPageTest.referenceSearchBoxIsRenderedWithItsCurrentValue();
		ReviewPageTest.referenceSearchBoxEscapesUserInput();
		ReviewPageTest.everyValueIsEscaped();
		ReviewPageTest.appliedLineHasNoButtons();
		ReviewPageTest.openLineHasBothButtons();
		ReviewPageTest.acceptedLineOffersOnlyReject();
		ReviewPageTest.rejectedLineOffersOnlyAccept();
		ReviewPageTest.confirmationIsRendered();
		ReviewPageTest.frenchAndEnglishBothRender();
		ReviewPageTest.keyBadgeUsesTheRealFieldsNotTheAttributeName();
		ReviewPageTest.multipleMappingsOfOneAttributeShowDistinctSources();
		ReviewPageTest.technicalKeyLineHasNoAcceptRejectButtonsWhateverItsStatus();
		ReviewPageTest.appliedScriptConstraintOffersRemoveNotApplyTitle();
		ReviewPageTest.appliedScriptConstraintShowsExplanationAndEffect();
		ReviewPageTest.appliedExternalServiceStillHasNoButtons();
		ReviewPageTest.appliedScriptConstraintOffersMeasureAgain();
		ReviewPageTest.acceptedNotAppliedScriptConstraintOffersApplyButton();
		ReviewPageTest.acceptedScriptConstraintWithApplyMessageShowsItInline();
		ReviewPageTest.unappliedConstraintBannerShownAndPendingViewIncludesTheLine();
		ReviewPageTest.unappliedConstraintBannerAbsentWhenNothingIsStuck();
		ReviewPageTest.progressStripAlwaysRendered();
		ReviewPageTest.progressStripCarriesInlineDisplayNoneAsAStaleCssFallback();
		ReviewPageTest.decisionColumnAndWrapColumnsCarryTheirResponsiveClasses();
		ReviewPageTest.decisionCellSplitsStatusAndActionsOnSeparateRows();
		ReviewPageTest.selectableLinesGetACheckboxWithTheRightCanFlags();
		ReviewPageTest.technicalKeyAndAppliedLinesGetAnEmptySelectCellNotAMissingOne();
		ReviewPageTest.noSelectColumnAtAllWhenNothingIsSelectable();
		ReviewPageTest.selectionBarRendersItsThreeActions();
		ReviewPageTest.selectionCountSpanCarriesInlineGapAsAStaleCssFallback();
		ReviewPageTest.staleAppliedScriptConstraintShowsOutOfDateMarkerAndReapplyButton();
		ReviewPageTest.currentAppliedScriptConstraintShowsNoOutOfDateMarkerOrReapplyButton();
		ReviewPageTest.matchLineRendersExpandedBodyWithDecisionTreeEvidenceAndSimulateButton();
		ReviewPageTest.matchLineWithCanSimulateTrueEnablesTheButton();
		ReviewPageTest.matchLineWithNoRequirementsJsonRendersNoExpandedBody();
		ReviewPageTest.supersededLineShowsTreeCollapsedWithSupersededNoteAndPositionChip();
		ReviewPageTest.expandedMatchRowIdsReopensExactlyThatLine();
		ReviewPageTest.rejectedLineShowsTreeCollapsedByDefault();
		ReviewPageTest.mdmfCommentHiddenFieldAlwaysRendered();
		ReviewPageTest.matchProposedLineOffersAcceptAndBothToggles();
		ReviewPageTest.matchAcceptedLineOffersNoPlainAcceptButBothToggles();
		ReviewPageTest.matchRejectedLineShowsCommentAndOffersAcceptAndReproposeOnly();
		ReviewPageTest.matchAppliedLineOffersNothing();
		ReviewPageTest.matchInlinePanelsCarryTheRightFormControlIds();
		ReviewPageTest.matchCommentAndNoteAreEscaped();
		ReviewPageTest.matchCounterListsProposedAndSupersededLinesWithAnchors();
		ReviewPageTest.matchCounterSilentWhenNothingAwaitsReview();
		ReviewPageTest.matchLineRowCarriesAnAnchorIdForTheCounter();
		ReviewPageTest.expanderSummaryWordingInBothLocales();
		System.out.println("ReviewPageTest OK");
	}

	private static ReviewPage.Input input(final String pProjectCode, final List<ReviewLine> pAllLines, final ReviewFilter pFilter, final ReviewPage.Confirmation pConfirmation)
	{
		final List<ReviewLine> visible = pAllLines == null ? new ArrayList<ReviewLine>() : ReviewData.filter(pAllLines, pFilter == null ? ReviewFilter.defaultFilter() : pFilter);
		return new ReviewPage.Input(Locale.ENGLISH, Arrays.asList(new ReviewPage.ProjectOption("COMPANIES360", "Companies 360")), pProjectCode, "Companies 360", true,
			pAllLines == null ? new ArrayList<ReviewLine>() : pAllLines, visible, pFilter == null ? ReviewFilter.defaultFilter() : pFilter, 80, null, "ok", pConfirmation,
			null, null, null, 10);
	}

	private static void noProjectShowsSelectorNotTable()
	{
		final String html = ReviewPage.render(ReviewPageTest.input(null, null, null, null));
		ReviewPageTest.check("shows the project selector", html.contains("mdmfProjectSelect"));
		ReviewPageTest.check("does not render a proposal table with no project chosen", !html.contains("mdmf-table"));
	}

	private static void emptyStateWhenNoLineMatches()
	{
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", new ArrayList<ReviewLine>(), null, null));
		ReviewPageTest.check("empty state text shown", html.contains(ReviewMessages.get(Locale.ENGLISH, "review.empty")));
	}

	/** A status read from a stored row goes into attributes: it is escaped there, so a forged one cannot add an attribute of its own. */
	private static void aForgedStatusStaysInsideItsAttribute()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		final String forged = "ACCEPTED\" onmouseover=\"alert(1)";
		lines.add(new ReviewLine(ReviewLine.ENTITY, "e1", "Supplier", "New entity", "RULE", Integer.valueOf(90), "why", forged, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0), null));
		ReviewPageTest.check("the row is rendered", html.contains("data-mdmf-status=\""));
		ReviewPageTest.check("the forged status is escaped in the attribute", html.contains("data-mdmf-status=\"ACCEPTED&quot; onmouseover=&quot;alert(1)\""));
		ReviewPageTest.check("no attribute of its own comes out of it", !html.contains("onmouseover=\"alert(1)"));
	}

	/** Task B, product report 2026-09-21: Status=Pending with nothing pending must offer one-click links to the other statuses that actually match. */
	private static void emptyStateOffersOneClickLinksToOtherMatchingStatuses()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ENTITY, "e1", "Supplier", "New entity", "RULE", Integer.valueOf(90), "why", ReviewLine.ACCEPTED, false, false));
		lines.add(new ReviewLine(ReviewLine.ENTITY, "e2", "Account", "New entity", "RULE", Integer.valueOf(90), "why", ReviewLine.ACCEPTED, false, false));
		final ReviewFilter proposedOnly = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.PROPOSED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, proposedOnly, null));
		ReviewPageTest.check("empty state text still shown", html.contains(ReviewMessages.get(Locale.ENGLISH, "review.empty")));
		ReviewPageTest.check("the hint about other statuses is shown", html.contains(ReviewMessages.get(Locale.ENGLISH, "review.empty.otherStatuses")));
		ReviewPageTest.check("a one-click link to ACCEPTED, with the real count", html.contains("data-mdmf-filter-status=\"" + ReviewLine.ACCEPTED + "\"")
			&& html.contains(ReviewMessages.get(Locale.ENGLISH, "review.status." + ReviewLine.ACCEPTED) + " (2)"));
		ReviewPageTest.check("a one-click link to ALL too, since it differs from the current filter",
			html.contains("data-mdmf-filter-status=\"" + ReviewFilter.ALL + "\""));
	}

	/** A project with genuinely nothing anywhere must not show a broken or empty "other statuses" hint. */
	private static void emptyStateSilentWhenTrulyNothingMatchesAnyStatus()
	{
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", new ArrayList<ReviewLine>(), null, null));
		ReviewPageTest.check("no other-statuses hint when there is truly nothing anywhere",
			!html.contains(ReviewMessages.get(Locale.ENGLISH, "review.empty.otherStatuses")));
	}

	/** The two new natures (task B) render their own nature chip like every other nature, through the same generic table rendering. */
	private static void mappingStepAndQualityNaturesRenderTheirOwnChip()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MAPPING_STEP, "s1", "SUPPLIER.LEGAL_FORM → Company.legalForm", "Step 1: Trim leading/trailing spaces", "RULE",
			Integer.valueOf(100), "55 value(s) seen, 3 would change.", ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.QUALITY, "q1", "Company.vatNo", "WARN - Check digit must be valid: SIREN", "RULE", Integer.valueOf(90),
			"55 value(s) seen, 2 failing.", ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0), null));
		ReviewPageTest.check("MAPPING_STEP nature label shown", html.contains(ReviewMessages.get(Locale.ENGLISH, "review.nature.MAPPING_STEP")));
		ReviewPageTest.check("QUALITY nature label shown", html.contains(ReviewMessages.get(Locale.ENGLISH, "review.nature.QUALITY")));
		ReviewPageTest.check("the step's readable proposition shown, not raw JSON", html.contains("Trim leading/trailing spaces"));
		ReviewPageTest.check("the rule's readable proposition shown, severity first", html.contains("WARN - Check digit must be valid: SIREN"));
		ReviewPageTest.check("both filter buttons for the two new natures exist", html.contains("data-mdmf-filter-nature=\"" + ReviewLine.MAPPING_STEP + "\"")
			&& html.contains("data-mdmf-filter-nature=\"" + ReviewLine.QUALITY + "\""));
	}

	/** Coordinator report, 2026-09-21: "Je ne vois pas les Ids des choses a revoir dans l'ecran" - every line now carries its short reference, with a tooltip naming the table and key. */
	private static void everyLineShowsItsShortReferenceWithATooltip()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MAPPING_STEP, "8", "ACCOUNT.ACCOUNT_TYPE → Company.legalForm", "TRIM", "RULE", Integer.valueOf(90), "why",
			ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.MAPPING, "63", "ACCOUNT.ACCOUNT_TYPE → Company.legalForm", "TRIM", "RULE", Integer.valueOf(90), "why",
			ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0), null));
		ReviewPageTest.check("STEP-8 shown", html.contains(">STEP-8</code>"));
		ReviewPageTest.check("STEP-8's tooltip names the table and key", html.contains("title=\"MappingStep 8\""));
		ReviewPageTest.check("MAP-63 shown", html.contains(">MAP-63</code>"));
		ReviewPageTest.check("MAP-63's tooltip names the table and key", html.contains("title=\"AttributeMapping 63\""));
	}

	private static void referenceSearchBoxIsRenderedWithItsCurrentValue()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MAPPING_STEP, "8", "x", "y", "RULE", Integer.valueOf(90), "why", ReviewLine.PROPOSED, false, false));
		final ReviewFilter filter = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0, "STEP-8");
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, filter, null));
		ReviewPageTest.check("the reference box shows the current query", html.contains("id=\"mdmfReferenceInput\"") && html.contains("value=\"STEP-8\""));
		ReviewPageTest.check("the hidden field carries the same value so a row action does not silently clear it",
			html.contains("id=\"mdmfFilterReference\" name=\"mdmfFilterReference\" value=\"STEP-8\""));
	}

	/** Task brief: the Reference box is user input rendered back into the page - it must be escaped like every other value. */
	private static void referenceSearchBoxEscapesUserInput()
	{
		final String malicious = "\"><script>alert(1)</script>";
		final ReviewFilter filter = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0, malicious);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", new ArrayList<ReviewLine>(), filter, null));
		ReviewPageTest.check("the raw payload never appears anywhere in the page", !html.contains(malicious));
		ReviewPageTest.check("no live <script> tag was introduced", !html.contains("<script>alert(1)</script>"));
		ReviewPageTest.check("the escaped form is rendered instead", html.contains("&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;"));
	}

	private static void everyValueIsEscaped()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ENTITY, "e1", "<script>alert(1)</script>", "A & B \"quoted\"", "RULE", Integer.valueOf(90), "why <b>not</b>",
			ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, null, null));
		ReviewPageTest.check("raw script tag never appears", !html.contains("<script>alert(1)</script>"));
		ReviewPageTest.check("escaped element text appears instead", html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
		ReviewPageTest.check("ampersand escaped", html.contains("A &amp; B"));
		ReviewPageTest.check("quote escaped", html.contains("&quot;quoted&quot;"));
	}

	private static void appliedLineHasNoButtons()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "Supplier.legalName", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.APPLIED, false, false));
		final ReviewFilter showApplied = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.APPLIED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showApplied, null));
		ReviewPageTest.check("applied line rendered", html.contains("Supplier.legalName"));
		ReviewPageTest.check("no accept button for an applied line", !html.contains("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a1\""));
		ReviewPageTest.check("the applied title explains why", html.contains(ReviewMessages.get(Locale.ENGLISH, "review.applied.title")));
	}

	private static void openLineHasBothButtons()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "Supplier.legalName", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, null, null));
		ReviewPageTest.check("accept button present", html.contains("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a1\""));
		ReviewPageTest.check("reject button present", html.contains("data-mdmf-action=\"REJECT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a1\""));
	}

	/** {@link DecisionActions#forStatus}: an ACCEPTED row still offers Reject (product owner: "the Accepted tag seems to be set, but I can still Accept them" - fixed). */
	private static void acceptedLineOffersOnlyReject()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "Supplier.legalName", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.ACCEPTED, false, false));
		final ReviewFilter showAccepted = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.ACCEPTED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showAccepted, null));
		ReviewPageTest.check("no accept button for an already-accepted line", !html.contains("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a1\""));
		ReviewPageTest.check("reject is still offered (the only way to change an accepted line)",
			html.contains("data-mdmf-action=\"REJECT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a1\""));
	}

	/** {@link DecisionActions#forStatus}: a REJECTED row still offers Accept. */
	private static void rejectedLineOffersOnlyAccept()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "Supplier.legalName", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.REJECTED, false, false));
		final ReviewFilter showRejected = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.REJECTED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showRejected, null));
		ReviewPageTest.check("no reject button for an already-rejected line", !html.contains("data-mdmf-action=\"REJECT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a1\""));
		ReviewPageTest.check("accept is still offered (the only way to change a rejected line)",
			html.contains("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a1\""));
	}

	private static void confirmationIsRendered()
	{
		final List<ReviewRules.AffectedRow> rows = Arrays.asList(new ReviewRules.AffectedRow(ReviewLine.ATTRIBUTE, "a1", "supplierId", ReviewLine.PROPOSED));
		final ReviewPage.Confirmation confirmation = new ReviewPage.Confirmation("Reject 1 too?", "CASCADE_CONFIRM", "Reject it", rows);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", new ArrayList<ReviewLine>(), null, confirmation));
		ReviewPageTest.check("confirmation message shown", html.contains("Reject 1 too?"));
		ReviewPageTest.check("confirm action wired", html.contains("data-mdmf-action=\"CASCADE_CONFIRM\""));
		ReviewPageTest.check("affected row listed", html.contains("supplierId"));
	}

	private static void frenchAndEnglishBothRender()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.RELATION, "r1", "CONTACT.ACCOUNT_ID", "-> ACCOUNT", "RULE", Integer.valueOf(80), "why", ReviewLine.PROPOSED, false, false));
		final ReviewPage.Input englishInput = ReviewPageTest.input("COMPANIES360", lines, null, null);
		final String english = ReviewPage.render(englishInput);
		ReviewPageTest.check("english label", english.contains("Accept"));
		final ReviewPage.Input frenchInput = new ReviewPage.Input(Locale.FRENCH, englishInput.projects, englishInput.selectedProjectCode, englishInput.selectedProjectLabel,
			englishInput.projectLocked, englishInput.allLines, englishInput.visibleLines, englishInput.filter, englishInput.bulkThreshold, englishInput.notice,
			englishInput.noticeKind, englishInput.confirmation, englishInput.matchCanSimulate, englishInput.matchCannotSimulateReason, englishInput.matchSimulationResults,
			englishInput.matchMaxSimulationPairs);
		final String french = ReviewPage.render(frenchInput);
		ReviewPageTest.check("french label", french.contains("Accepter"));
		ReviewPageTest.check("french accented text renders as real UTF-8/HTML, not mojibake", french.contains("Entité") || french.contains("Rapprochement"));
	}

	/**
	 * {@code primaryKey} (the staging technical key) and {@code businessKey} (the source's own key) are two distinct fields since the
	 * 2026-09-20 staging pass - the badge now reads {@link ReviewLine#primaryKey}/{@link ReviewLine#businessKey} directly, not the
	 * attribute name at the end of the element text: a sourceSystem/sourceId line here carries primaryKey=true, businessKey=false, and a
	 * genuine source key (contactId) carries the opposite, on purpose - proving the badge no longer needs to guess from the name.
	 */
	private static void keyBadgeUsesTheRealFieldsNotTheAttributeName()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "Contact.sourceSystem", "STRING", "RULE", Integer.valueOf(100), "why", ReviewLine.PROPOSED, false, true));
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a2", "Contact.sourceId", "STRING", "RULE", Integer.valueOf(100), "why", ReviewLine.PROPOSED, false, true));
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a3", "Contact.contactId", "STRING", "RULE", Integer.valueOf(93), "why", ReviewLine.PROPOSED, true, false));
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a4", "Contact.email", "STRING", "RULE", Integer.valueOf(70), "why", ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, ReviewFilter.defaultFilter(), null));
		final String technical = ReviewMessages.get(Locale.ENGLISH, "review.key.technical");
		final String business = ReviewMessages.get(Locale.ENGLISH, "review.key.business");
		ReviewPageTest.check("sourceSystem/sourceId (primaryKey=true) say technical key: two occurrences", ReviewPageTest.count(html, technical) == 2);
		ReviewPageTest.check("contactId (businessKey=true, primaryKey=false) says business key: one occurrence", ReviewPageTest.count(html, business) == 1);
		ReviewPageTest.check("email (neither) gets no badge", ReviewPageTest.count(html, technical) + ReviewPageTest.count(html, business) == 3);
	}

	/**
	 * A canonical (LLM) attribute has one AttributeMapping line per source it aligns: two lines for the same target attribute must not
	 * read as duplicates - the element text of each must show its own source (coordinator review, 2026-09-20 staging pass).
	 */
	private static void multipleMappingsOfOneAttributeShowDistinctSources()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MAPPING, "m1", "SUPPLIER.NAME1 → Company.name", "TRIM", "LLM", Integer.valueOf(85), "why", ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.MAPPING, "m2", "ACCOUNT.NAME → Company.name", "TRIM", "LLM", Integer.valueOf(85), "why", ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, ReviewFilter.defaultFilter(), null));
		ReviewPageTest.check("both sources visible in the element text", html.contains("SUPPLIER.NAME1") && html.contains("ACCOUNT.NAME"));
		ReviewPageTest.check("both rows have their own row buttons (not merged into one)",
			html.contains("data-mdmf-id=\"m1\"") && html.contains("data-mdmf-id=\"m2\""));
	}

	/**
	 * The review screen must not offer Accept/Reject on a technical-key line (coordinator review, 2026-09-20): it shows a "follows the
	 * entity" chip instead, whatever the row's own status - not only when {@code APPLIED} (the ordinary reason a line loses its buttons),
	 * but also while still {@code PROPOSED} - exactly the {@code sourceSystem}/{@code sourceId} case that triggered this rule.
	 */
	private static void technicalKeyLineHasNoAcceptRejectButtonsWhateverItsStatus()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "tk1", "Account.sourceSystem", "STRING", "RULE", Integer.valueOf(100), "why", ReviewLine.PROPOSED, false, true));
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "tk2", "Account.sourceId", "STRING", "RULE", Integer.valueOf(100), "why", ReviewLine.ACCEPTED, false, true));
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "biz1", "Account.name", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.PROPOSED, false, false));
		final ReviewFilter showEverything = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showEverything, null));
		ReviewPageTest.check("no accept button for the PROPOSED technical key", !html.contains("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"tk1\""));
		ReviewPageTest.check("no reject button for the PROPOSED technical key", !html.contains("data-mdmf-action=\"REJECT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"tk1\""));
		ReviewPageTest.check("no accept button for the ACCEPTED technical key either", !html.contains("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"tk2\""));
		ReviewPageTest.check("the ordinary business attribute still has both buttons",
			html.contains("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"biz1\"")
				&& html.contains("data-mdmf-action=\"REJECT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"biz1\""));
		final String followsEntity = ReviewMessages.get(Locale.ENGLISH, "review.key.followsEntity");
		ReviewPageTest.check("the \"follows the entity\" chip appears once per technical-key line: " + ReviewPageTest.count(html, followsEntity),
			ReviewPageTest.count(html, followsEntity) == 2);
	}

	/** Apply lot (2026-09-21): an APPLIED SCRIPT_CONSTRAINT line offers Remove, not the generic "cannot be changed any more" title. */
	private static void appliedScriptConstraintOffersRemoveNotApplyTitle()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "c1", "Contact.phone", "WARN - Position isValid of Fasttrack.lib.phone (STAGING)", "LLM",
			Integer.valueOf(85), "why", ReviewLine.APPLIED, false, false));
		final ReviewFilter showApplied = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.APPLIED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showApplied, null));
		ReviewPageTest.check("remove button present", html.contains("data-mdmf-action=\"REMOVE\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\""));
		ReviewPageTest.check("no accept/reject on an applied script constraint",
			!html.contains("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\"")
				&& !html.contains("data-mdmf-action=\"REJECT\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\""));
		ReviewPageTest.check("the generic 'cannot be changed' title is NOT shown here (it CAN be, via Remove)",
			!html.contains(ReviewMessages.get(Locale.ENGLISH, "review.applied.title")));
	}

	/** The explanation and measured effect (apply lot) render for an applied SCRIPT_CONSTRAINT line, escaped like every other field. */
	private static void appliedScriptConstraintShowsExplanationAndEffect()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "c1", "Contact.phone", "WARN - Position isValid of Fasttrack.lib.phone (STAGING)", "LLM",
			Integer.valueOf(85), "why", ReviewLine.APPLIED, false, false, "Warns when Fasttrack.lib.phone.isValid(phone) <answers> false.",
			"303 record(s) checked, 4 reported."));
		final ReviewFilter showApplied = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.APPLIED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showApplied, null));
		ReviewPageTest.check("explanation shown, escaped", html.contains("Warns when Fasttrack.lib.phone.isValid(phone) &lt;answers&gt; false."));
		ReviewPageTest.check("measured effect shown", html.contains("303 record(s) checked, 4 reported."));
	}

	/** Re-apply lot (2026-09-21): a stale APPLIED SCRIPT_CONSTRAINT line shows the "Out of date" marker (reason in its tooltip) and a Re-apply button alongside Remove/Measure again. */
	private static void staleAppliedScriptConstraintShowsOutOfDateMarkerAndReapplyButton()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "c1", "Contact.phone", "WARN - Position isValid of Fasttrack.lib.phone (STAGING)", "LLM",
			Integer.valueOf(85), "why", ReviewLine.APPLIED, false, false, "Warns when Fasttrack.lib.phone.isValid(phone) answers false.",
			"55 record(s) checked, 1 reported.", true, "the record-facing message is the old technical one: 'phone' failed phone.isValid."));
		final ReviewFilter showApplied = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.APPLIED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showApplied, null));
		ReviewPageTest.check("out of date marker shown", html.contains(ReviewMessages.get(Locale.ENGLISH, "review.capability.stale")));
		ReviewPageTest.check("reason in the tooltip, escaped",
			html.contains("title=\"the record-facing message is the old technical one: &#39;phone&#39; failed phone.isValid.\""));
		ReviewPageTest.check("re-apply button present",
			html.contains("data-mdmf-action=\"REAPPLY\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\""));
		ReviewPageTest.check("remove and measure again still present",
			html.contains("data-mdmf-action=\"REMOVE\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\"")
				&& html.contains("data-mdmf-action=\"MEASURE\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\""));
	}

	/** The ordinary (current) APPLIED SCRIPT_CONSTRAINT case is unaffected: no marker, no Re-apply button. */
	private static void currentAppliedScriptConstraintShowsNoOutOfDateMarkerOrReapplyButton()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "c1", "Contact.phone", "WARN - Position isValid of Fasttrack.lib.phone (STAGING)", "LLM",
			Integer.valueOf(85), "why", ReviewLine.APPLIED, false, false));
		final ReviewFilter showApplied = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.APPLIED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showApplied, null));
		ReviewPageTest.check("no out of date marker", !html.contains(ReviewMessages.get(Locale.ENGLISH, "review.capability.stale")));
		ReviewPageTest.check("no re-apply button",
			!html.contains("data-mdmf-action=\"REAPPLY\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\""));
	}

	/** An APPLIED EXTERNAL_SERVICE line is unaffected by the apply lot: still no buttons, no explanation/effect block (those fields stay null for this nature). */
	private static void appliedExternalServiceStillHasNoButtons()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.EXTERNAL_SERVICE, "s1", "Contact.email", "Look into check of Melissa (ready)", "LLM", Integer.valueOf(70), "why",
			ReviewLine.APPLIED, false, false));
		final ReviewFilter showApplied = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.APPLIED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showApplied, null));
		ReviewPageTest.check("no remove button for EXTERNAL_SERVICE",
			!html.contains("data-mdmf-action=\"REMOVE\" data-mdmf-nature=\"" + ReviewLine.EXTERNAL_SERVICE + "\" data-mdmf-id=\"s1\""));
		ReviewPageTest.check("the generic applied title still shown for this nature", html.contains(ReviewMessages.get(Locale.ENGLISH, "review.applied.title")));
		ReviewPageTest.check("no explanation/effect markup for a nature that never sets them",
			!html.contains("mdmf-capability-explanation") && !html.contains("mdmf-capability-effect"));
	}

	/** Coordinator review, apply lot (2026-09-21): an APPLIED SCRIPT_CONSTRAINT line offers Measure again alongside Remove - the automatic measurement is bounded, this is the on-demand escape hatch. */
	private static void appliedScriptConstraintOffersMeasureAgain()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "c1", "Contact.phone", "WARN - Position isValid of Fasttrack.lib.phone (STAGING)", "LLM",
			Integer.valueOf(85), "why", ReviewLine.APPLIED, false, false));
		final ReviewFilter showApplied = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.APPLIED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showApplied, null));
		ReviewPageTest.check("measure-again button present",
			html.contains("data-mdmf-action=\"MEASURE\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\""));
		ReviewPageTest.check("remove button also present (both offered together)",
			html.contains("data-mdmf-action=\"REMOVE\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\""));
	}

	/**
	 * fix-apply-ux (2026-09-21), item 1: an ACCEPTED SCRIPT_CONSTRAINT that is NOT (yet) applied - accepted before
	 * this lot was deployed, or left over after a failed apply attempt - offers an Apply button alongside Reject,
	 * never a plain Accept (acceptance itself already triggers apply on a still-PROPOSED line, so offering both
	 * here would be confusing) and never Remove/Measure again (those are for an already-APPLIED row).
	 */
	private static void acceptedNotAppliedScriptConstraintOffersApplyButton()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "c1", "Contact.phone", "WARN - Position isValid of Fasttrack.lib.phone (STAGING)", "LLM",
			Integer.valueOf(85), "why", ReviewLine.ACCEPTED, false, false));
		final ReviewFilter showAccepted = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.ACCEPTED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showAccepted, null));
		ReviewPageTest.check("apply button present", html.contains("data-mdmf-action=\"APPLY\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\""));
		ReviewPageTest.check("reject still offered", html.contains("data-mdmf-action=\"REJECT\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\""));
		ReviewPageTest.check("no plain accept button", !html.contains("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\""));
		ReviewPageTest.check("no remove/measure buttons (those are for an already-applied row)",
			!html.contains("data-mdmf-action=\"REMOVE\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\"")
				&& !html.contains("data-mdmf-action=\"MEASURE\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\""));
	}

	/**
	 * fix-apply-ux (2026-09-21), item 1: a failed apply attempt leaves the line ACCEPTED with {@code applyMessage}
	 * explaining why (ebx.CapabilityApplier / ProposalReviews, "review.capability.applyFailed") - this must still
	 * show inline next to the Apply button so a reviewer sees the previous failure before retrying.
	 */
	private static void acceptedScriptConstraintWithApplyMessageShowsItInline()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "c1", "Contact.phone", "WARN - Position isValid of Fasttrack.lib.phone (STAGING)", "LLM",
			Integer.valueOf(85), ReviewMessages.get(Locale.ENGLISH, "review.capability.applyFailed", "publication refused: field 'body' is mandatory"),
			ReviewLine.ACCEPTED, false, false));
		final ReviewFilter showAccepted = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.ACCEPTED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showAccepted, null));
		ReviewPageTest.check("the previous apply failure is shown, escaped", html.contains("publication refused: field &#39;body&#39; is mandatory"));
		ReviewPageTest.check("apply button still offered so the reviewer can retry",
			html.contains("data-mdmf-action=\"APPLY\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\""));
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21), item 6: an ACCEPTED SCRIPT_CONSTRAINT line - not yet on
	 * the model - shows under the default/Pending filter too, AND the banner at the top names the count with
	 * a one-click link isolating exactly those lines (setting both the nature and status filter).
	 */
	private static void unappliedConstraintBannerShownAndPendingViewIncludesTheLine()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "c1", "Contact.phone", "WARN - Position isValid of Fasttrack.lib.phone (STAGING)", "LLM",
			Integer.valueOf(85), "why", ReviewLine.ACCEPTED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, ReviewFilter.defaultFilter(), null));
		ReviewPageTest.check("the banner names the count", html.contains(ReviewMessages.get(Locale.ENGLISH, "review.capability.unapplied.banner", Integer.valueOf(1))));
		ReviewPageTest.check("the banner's link filters on nature=SCRIPT_CONSTRAINT and status=ACCEPTED",
			html.contains("data-mdmf-action=\"FILTER\" data-mdmf-filter-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-filter-status=\""
				+ ReviewLine.ACCEPTED + "\""));
		ReviewPageTest.check("the line itself is visible under the default (Pending) filter", html.contains("data-mdmf-id=\"c1\""));
	}

	/** No unapplied constraint anywhere in the project: the banner is entirely absent, not shown with a count of zero. */
	private static void unappliedConstraintBannerAbsentWhenNothingIsStuck()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ENTITY, "e1", "Supplier", "New entity", "LLM", Integer.valueOf(84), "why", ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, ReviewFilter.defaultFilter(), null));
		ReviewPageTest.check("no banner at all", !html.contains("review.capability.unapplied") && !html.contains(
			ReviewMessages.get(Locale.ENGLISH, "review.capability.unapplied.link")));
	}

	/**
	 * fix-apply-ux (2026-09-21), item 4: the progress strip is always rendered - hidden by its own CSS
	 * until ProposalReviewUserService.pageJs switches it "on" - on BOTH the project-selector screen and the
	 * table screen, so the ids it needs are guaranteed present whichever one loads first.
	 */
	private static void progressStripAlwaysRendered()
	{
		final String noProject = ReviewPage.render(ReviewPageTest.input(null, null, null, null));
		ReviewPageTest.check("progress strip present on the project-selector screen", noProject.contains("id=\"mdmfProgress\"")
			&& noProject.contains("id=\"mdmfProgressLabel\""));
		final String withProject = ReviewPage.render(ReviewPageTest.input("COMPANIES360", new ArrayList<ReviewLine>(), null, null));
		ReviewPageTest.check("progress strip present on the table screen", withProject.contains("id=\"mdmfProgress\"")
			&& withProject.contains("id=\"mdmfProgressLabel\""));
	}

	/**
	 * fix/review-assets (2026-09-21): the progress strip carries an inline {@code style="display:none"} in
	 * ADDITION to its {@code mdmf-progress} class - a browser still serving a stale stylesheet with no
	 * {@code .mdmf-progress} rule at all must still start the strip hidden, and {@code pageJs}'s own
	 * {@code progress(...)} sets {@code box.style.display} directly so the same stale sheet still shows it
	 * (unstyled) once armed - see ReviewPage.renderProgress's own Javadoc.
	 */
	private static void progressStripCarriesInlineDisplayNoneAsAStaleCssFallback()
	{
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", new ArrayList<ReviewLine>(), null, null));
		ReviewPageTest.check("inline style=\"display:none\" present on the strip's own container",
			html.contains("<div id=\"mdmfProgress\" class=\"mdmf-progress\" style=\"display:none\">"));
	}

	/**
	 * fix-apply-ux (2026-09-21), item 5: the responsive-layout structural classes are present - review.css
	 * pins {@code mdmf-col-decide} to the right edge of the horizontally-scrolling table and lets
	 * {@code mdmf-col-wrap} (element/proposition) absorb the squeeze instead. This only checks the HTML
	 * carries the right classes in the right places; the actual visual result (whether it truly stays
	 * readable and reachable at the pane widths the product owner cares about) was never rendered by this
	 * lot - see the module's own report.
	 */
	private static void decisionColumnAndWrapColumnsCarryTheirResponsiveClasses()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "Supplier.legalName", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, null, null));
		ReviewPageTest.check("the decision header carries mdmf-col-decide", html.contains("<th class=\"mdmf-col-decide\">"));
		ReviewPageTest.check("the decision cell carries both mdmf-decide and mdmf-col-decide",
			html.contains("<td class=\"mdmf-decide mdmf-col-decide\">"));
		ReviewPageTest.check("the element and proposition cells carry mdmf-col-wrap: two occurrences",
			ReviewPageTest.count(html, "<td class=\"mdmf-col-wrap\">") == 2);
	}

	/**
	 * fix-apply-ux (2026-09-21), item 5: inside the decision cell, the status badge is on its own row
	 * (mdmf-decide-status-row) and the action buttons are in a separate wrapping row (mdmf-decide-actions) -
	 * never mixed into a single flex line the way the previous round rendered them (the actual defect a live
	 * screenshot showed: Accept clipped because nothing forced the badge onto its own line first).
	 */
	private static void decisionCellSplitsStatusAndActionsOnSeparateRows()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "Supplier.legalName", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, null, null));
		final int statusRowAt = html.indexOf("mdmf-decide-status-row");
		final int actionsRowAt = html.indexOf("mdmf-decide-actions");
		ReviewPageTest.check("status row present", statusRowAt >= 0);
		ReviewPageTest.check("actions row present", actionsRowAt >= 0);
		ReviewPageTest.check("status row renders before the actions row", statusRowAt < actionsRowAt);
		final int acceptAt = html.indexOf("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a1\"");
		ReviewPageTest.check("the Accept button itself sits inside the actions row, after it opens", acceptAt > actionsRowAt);
	}

	/**
	 * fix-apply-ux (2026-09-21), item 3: a decidable line gets a checkbox carrying exactly the
	 * accept/reject/apply flags {@link DecisionActions#forStatus(String, String)} computed for it - a
	 * PROPOSED line (accept+reject) and an ACCEPTED SCRIPT_CONSTRAINT line (reject+apply) are deliberately
	 * different combinations, both checked here so the test cannot pass by coincidence.
	 */
	private static void selectableLinesGetACheckboxWithTheRightCanFlags()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "Supplier.legalName", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "c1", "Contact.phone", "WARN - Position isValid of Fasttrack.lib.phone (STAGING)", "LLM",
			Integer.valueOf(85), "why", ReviewLine.ACCEPTED, false, false));
		// showEverything, not defaultFilter(): the default filter is status=PROPOSED only, which would hide c1
		final ReviewFilter showEverything = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showEverything, null));
		ReviewPageTest.check("PROPOSED attribute: checkbox can accept and reject, not apply",
			html.contains("<input type=\"checkbox\" class=\"mdmf-row-select\" data-mdmf-nature=\"" + ReviewLine.ATTRIBUTE + "\" data-mdmf-id=\"a1\" "
				+ "data-mdmf-can-accept=\"1\" data-mdmf-can-reject=\"1\" data-mdmf-can-apply=\"0\" aria-label=\"Select Supplier.legalName - STRING\"/>"));
		ReviewPageTest.check("ACCEPTED SCRIPT_CONSTRAINT: checkbox can reject and apply, not plain accept",
			html.contains("<input type=\"checkbox\" class=\"mdmf-row-select\" data-mdmf-nature=\"" + ReviewLine.SCRIPT_CONSTRAINT + "\" data-mdmf-id=\"c1\" "
				+ "data-mdmf-can-accept=\"0\" data-mdmf-can-reject=\"1\" data-mdmf-can-apply=\"1\" aria-label=\""));
	}

	/**
	 * fix-apply-ux (2026-09-21), item 3: a technical-key line and an APPLIED line both get an EMPTY select
	 * cell (never a missing one - the column must stay aligned) and no checkbox at all.
	 */
	private static void technicalKeyAndAppliedLinesGetAnEmptySelectCellNotAMissingOne()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "tk1", "Account.sourceSystem", "STRING", "RULE", Integer.valueOf(100), "why", ReviewLine.PROPOSED, false, true));
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "Supplier.legalName", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a2", "Supplier.city", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.APPLIED, false, false));
		final ReviewFilter showEverything = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showEverything, null));
		ReviewPageTest.check("no checkbox for the technical key", !html.contains("class=\"mdmf-row-select\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"tk1\""));
		ReviewPageTest.check("no checkbox for the applied line", !html.contains("class=\"mdmf-row-select\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a2\""));
		ReviewPageTest.check("three empty/filled select cells total (column stays aligned): at least 2 empty ones",
			ReviewPageTest.count(html, "<td class=\"mdmf-col-select\"></td>") >= 2);
	}

	/** fix-apply-ux (2026-09-21), item 3: a screen showing only non-decidable lines (here, only APPLIED) gets no select column and no selection bar at all - nothing there to select. */
	private static void noSelectColumnAtAllWhenNothingIsSelectable()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "Supplier.legalName", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.APPLIED, false, false));
		final ReviewFilter showApplied = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.APPLIED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showApplied, null));
		ReviewPageTest.check("no select column header", !html.contains("mdmf-col-select"));
		ReviewPageTest.check("no selection bar", !html.contains("mdmfSelectionBar"));
	}

	/** fix-apply-ux (2026-09-21), item 3: the selection bar carries its count span and all three action buttons (Apply selected included, even though it may show as hidden/inactive until JS finds an applicable row checked). */
	private static void selectionBarRendersItsThreeActions()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "Supplier.legalName", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, ReviewFilter.defaultFilter(), null));
		ReviewPageTest.check("selection bar present", html.contains("id=\"mdmfSelectionBar\""));
		ReviewPageTest.check("selection count span present", html.contains("id=\"mdmfSelectionCount\""));
		ReviewPageTest.check("accept selected", html.contains("data-mdmf-action=\"SELECT_ACCEPT_PREVIEW\""));
		ReviewPageTest.check("reject selected", html.contains("data-mdmf-action=\"SELECT_REJECT_PREVIEW\""));
		ReviewPageTest.check("apply selected", html.contains("data-mdmf-action=\"SELECT_APPLY_PREVIEW\"") && html.contains("id=\"mdmfSelectApplyBtn\""));
		ReviewPageTest.check("the select-all header checkbox is present", html.contains("id=\"mdmfSelectAll\""));
	}

	/**
	 * fix/review-assets (2026-09-21): product report - "1 selected" read as glued directly onto "Accept
	 * selected" (no spacing at all), the exact rendering a stale review.css (missing
	 * {@code .mdmf-selection-bar}'s own {@code gap}) would produce. The count span carries an inline
	 * {@code margin-right} as a markup-level fallback that does not depend on which stylesheet is cached.
	 */
	private static void selectionCountSpanCarriesInlineGapAsAStaleCssFallback()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "Supplier.legalName", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, ReviewFilter.defaultFilter(), null));
		ReviewPageTest.check("the count span carries an inline right-margin, independent of the stylesheet",
			html.contains("<span class=\"mdmf-hint\" id=\"mdmfSelectionCount\" style=\"margin-right:10px\"></span>"));
	}

	/** review-match-plan lot (2026-09-22): a MATCH line with real plan content renders the expanded body - decision tree, evidence table, simulate button - as a second, wide row right after its own. */
	private static void matchLineRendersExpandedBodyWithDecisionTreeEvidenceAndSimulateButton()
	{
		final String requirements = "{\"tiers\":[{\"outcome\":\"MATCH\",\"type\":\"EXACT\","
			+ "\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}],"
			+ "\"clusteringFields\":[\"vatNo\"],\"mergePolicy\":{\"mode\":\"ONLY_DUPLICATES\",\"recordSelection\":\"mostCompleted\",\"defaultFieldMerge\":\"longest\"}}";
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MATCH, "m1", "Company", "1 tier(s) - suspect only", "LLM", Integer.valueOf(80), "why", ReviewLine.APPLIED, false, false,
			null, null, false, null, requirements, null, false, null, null));
		// ReviewFilter.defaultFilter() only shows PROPOSED lines - this line is APPLIED, so an explicit "show
		// every status" filter is needed for it to appear in visibleLines at all (the empty state renders
		// otherwise, hiding the very block this test means to check).
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0),
			null));
		ReviewPageTest.check("the decision tree block is rendered", html.contains("mdmf-match-body"));
		ReviewPageTest.check("the field name is shown in the tree", html.contains("vatNo"));
		ReviewPageTest.check("the evidence block says nothing was recorded (no evidence JSON on this line)",
			html.contains("Evidence not recorded for this plan"));
		ReviewPageTest.check("the simulate button is present, carrying the row id",
			html.contains("Simulate on 10 pivots") && html.contains("data-mdmf-action=\"SIMULATE\"") && html.contains("data-mdmf-id=\"m1\""));
		// ReviewPageTest.input's own helper (below) never supplies a matchCanSimulate map - a rowId absent from
		// it reads as "cannot simulate" (Input's own documented default), so the button is disabled here even
		// though the line's status is APPLIED; ProposalReviewUserService.write is what actually derives this
		// map from each MATCH line's own status (canSimulate := APPLIED.equals(line.status)) before building Input.
		ReviewPageTest.check("disabled by default when the caller supplies no matchCanSimulate entry for this row",
			html.contains("disabled=\"disabled\""));
	}

	/** With a real {@code matchCanSimulate} entry (the shape {@code ProposalReviewUserService.write} actually builds), the button is enabled and carries no {@code disabled} attribute. */
	private static void matchLineWithCanSimulateTrueEnablesTheButton()
	{
		final String requirements = "{\"tiers\":[{\"outcome\":\"MATCH\",\"type\":\"EXACT\","
			+ "\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}],"
			+ "\"clusteringFields\":[\"vatNo\"],\"mergePolicy\":{}}";
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MATCH, "m1", "Company", "1 tier(s) - may merge automatically", "LLM", Integer.valueOf(80), "why", ReviewLine.APPLIED, false,
			false, null, null, false, null, requirements, null, true, null, null));
		final java.util.Map<String, Boolean> canSimulate = new java.util.HashMap<String, Boolean>();
		canSimulate.put("m1", Boolean.TRUE);
		final ReviewPage.Input input = new ReviewPage.Input(Locale.ENGLISH, Arrays.asList(new ReviewPage.ProjectOption("COMPANIES360", "Companies 360")), "COMPANIES360",
			"Companies 360", true, lines, lines, ReviewFilter.defaultFilter(), 80, null, "ok", null, canSimulate, null, null, 10);
		final String html = ReviewPage.render(input);
		ReviewPageTest.check("simulate button present", html.contains("Simulate on 10 pivots"));
		ReviewPageTest.check("no disabled attribute when matchCanSimulate says true", !html.contains("disabled=\"disabled\""));
		ReviewPageTest.check("autoMerge true + a MATCH-outcome tier: the title says may merge automatically", html.contains("may merge automatically"));
	}

	/** A MATCH line with no {@code matchRequirementsJson} (should not happen for a real row, but never assumed) renders nothing extra - no broken/empty block. */
	private static void matchLineWithNoRequirementsJsonRendersNoExpandedBody()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MATCH, "m2", "Company", "Matching plan proposed", "LLM", Integer.valueOf(80), "why", ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, ReviewFilter.defaultFilter(), null));
		ReviewPageTest.check("no expanded body block for a line with no plan content", !html.contains("mdmf-match-body"));
	}

	/**
	 * tree-diagram-labels fix lot (2026-09-22), item C ({@code docs/MATCHING.md} &sect;16.10): a SUPERSEDED
	 * line is still treated like PROPOSED for the tree/evidence themselves (still shown, still counted as
	 * "awaiting review" - {@link ReviewPage#matchLinesToReview}) and still carries its explicit "superseded -
	 * waiting for a new proposal" note, but - like every OTHER status now - renders COLLAPSED by default (this
	 * test used to assert the opposite, "open by default"; product report, the product owner: an expanded line pushed a
	 * second one far enough down the page that he never noticed it). The one line here is also the only one
	 * awaiting review, so its position chip reads "1 / 1".
	 */
	private static void supersededLineShowsTreeCollapsedWithSupersededNoteAndPositionChip()
	{
		final String requirements = "{\"tiers\":[{\"outcome\":\"MATCH\",\"type\":\"EXACT\","
			+ "\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}],"
			+ "\"clusteringFields\":[\"vatNo\"],\"mergePolicy\":{}}";
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MATCH, "m3", "Company", "1 tier(s) - suspect only", "LLM", Integer.valueOf(80), "why", ReviewLine.SUPERSEDED, false, false,
			null, null, false, null, requirements, null, false, null, null));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0),
			null));
		ReviewPageTest.check("the tree is still rendered for a SUPERSEDED line", html.contains("mdmf-match-body"));
		ReviewPageTest.check("the details block is collapsed by default (item C)",
			html.contains("<details><summary>") && !html.contains("<details open=\"open\"><summary>"));
		ReviewPageTest.check("the superseded note is shown", html.contains("Superseded - waiting for a new proposal."));
		ReviewPageTest.check("the position chip shows 1 / 1 (only line awaiting review)", html.contains("1 / 1"));
	}

	/**
	 * tree-diagram-labels fix lot (2026-09-22), item C: {@link ReviewPage.Input#expandedMatchRowIds} is the
	 * one exception to "collapsed by default" - {@code ebx.ui.ProposalReviewUserService} populates it with the
	 * rowId the CURRENT postback just acted on, so the reviewer sees the fresh result without re-clicking.
	 */
	private static void expandedMatchRowIdsReopensExactlyThatLine()
	{
		final String requirements = "{\"tiers\":[{\"outcome\":\"MATCH\",\"type\":\"EXACT\","
			+ "\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}],"
			+ "\"clusteringFields\":[\"vatNo\"],\"mergePolicy\":{}}";
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MATCH, "m5", "Company", "1 tier(s) - suspect only", "LLM", Integer.valueOf(80), "why", ReviewLine.PROPOSED, false, false,
			null, null, false, null, requirements, null, false, null, null));
		lines.add(new ReviewLine(ReviewLine.MATCH, "m6", "Contact", "1 tier(s) - suspect only", "LLM", Integer.valueOf(80), "why", ReviewLine.PROPOSED, false, false,
			null, null, false, null, requirements, null, false, null, null));
		final ReviewFilter filter = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0);
		final ReviewPage.Input input = new ReviewPage.Input(Locale.ENGLISH, Arrays.asList(new ReviewPage.ProjectOption("COMPANIES360", "Companies 360")),
			"COMPANIES360", "Companies 360", true, lines, lines, filter, 80, null, "ok", null, null, null, null, 10,
			java.util.Collections.singleton("m6"));
		final String html = ReviewPage.render(input);
		// each line also carries its own rationale <details class="mdmf-rationale">, a DIFFERENT pattern from
		// the match panel's bare <details>/<details open="open"> - searching the exact substring sidesteps
		// those entirely rather than counting generic "<details" occurrences.
		final int collapsedIdx = html.indexOf("<details><summary>");
		final int openIdx = html.indexOf("<details open=\"open\"><summary>");
		ReviewPageTest.check("m5 (not acted on) stays collapsed - a bare <details><summary> exists", collapsedIdx >= 0);
		ReviewPageTest.check("m6 (acted on) reopens - a <details open=\"open\"><summary> exists", openIdx >= 0);
		ReviewPageTest.check("m5 (collapsed) renders before m6 (open) - same order as the lines list", collapsedIdx < openIdx);
	}

	/**
	 * Merge follow-up (2026-09-22): a REJECTED-by-decision line still shows its old tree/evidence (so
	 * "Propose another" has something concrete to react to) but collapsed by default - it is a decided-
	 * against plan, not a live proposal.
	 */
	private static void rejectedLineShowsTreeCollapsedByDefault()
	{
		final String requirements = "{\"tiers\":[{\"outcome\":\"MATCH\",\"type\":\"EXACT\","
			+ "\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}],"
			+ "\"clusteringFields\":[\"vatNo\"],\"mergePolicy\":{}}";
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		final ReviewLine base = new ReviewLine(ReviewLine.MATCH, "m4", "Company", "1 tier(s) - suspect only", "LLM", Integer.valueOf(80), "why", ReviewLine.REJECTED,
			false, false, null, null, false, null, requirements, null, false, null, null);
		lines.add(ReviewLine.withMatchExtras(base, "too aggressive", null));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0),
			null));
		ReviewPageTest.check("the old tree is still rendered for a REJECTED line", html.contains("mdmf-match-body"));
		ReviewPageTest.check("the details block has no open attribute (collapsed by default)",
			html.contains("<details><summary>") && !html.contains("<details open=\"open\"><summary>"));
		ReviewPageTest.check("no superseded note on a REJECTED line", !html.contains("Superseded - waiting for a new proposal."));
	}

	// ------------------------------------------------------------------------------- reject/repropose lot (2026-09-22)

	private static void mdmfCommentHiddenFieldAlwaysRendered()
	{
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", new ArrayList<ReviewLine>(), null, null));
		ReviewPageTest.check("mdmfComment is a real, named, blank-on-render hidden field",
			html.contains("<input type=\"hidden\" id=\"mdmfComment\" name=\"mdmfComment\"/>"));
	}

	/** A PROPOSED MATCH line offers plain Accept, plus BOTH toggles (Propose another / No matching for this entity) - never a plain, unconfirmed Reject button. */
	private static void matchProposedLineOffersAcceptAndBothToggles()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MATCH, "42", "Company", "Requirements: {...}", "LLM", Integer.valueOf(80), "why", ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, ReviewFilter.defaultFilter(), null));
		ReviewPageTest.check("Accept button present", html.contains("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"MATCH\" data-mdmf-id=\"42\""));
		ReviewPageTest.check("no plain, unconfirmed Reject button for this line",
			!html.contains("data-mdmf-action=\"REJECT\" data-mdmf-nature=\"MATCH\" data-mdmf-id=\"42\">"));
		ReviewPageTest.check("Propose another toggle present", html.contains("data-mdmf-toggle=\"mdmf-match-repropose-42\""));
		ReviewPageTest.check("No matching for this entity toggle present", html.contains("data-mdmf-toggle=\"mdmf-match-noMatch-42\""));
	}

	/** ACCEPTED offers no plain Accept again, but both MATCH-only actions stay available - nothing is final before matching/configure applies it. */
	private static void matchAcceptedLineOffersNoPlainAcceptButBothToggles()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MATCH, "42", "Company", "Requirements: {...}", "LLM", Integer.valueOf(80), "why", ReviewLine.ACCEPTED, false, false));
		final ReviewFilter showAccepted = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.ACCEPTED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showAccepted, null));
		ReviewPageTest.check("no Accept button", !html.contains("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"MATCH\" data-mdmf-id=\"42\""));
		ReviewPageTest.check("still offers Propose another", html.contains("data-mdmf-toggle=\"mdmf-match-repropose-42\""));
		ReviewPageTest.check("still offers No matching for this entity", html.contains("data-mdmf-toggle=\"mdmf-match-noMatch-42\""));
	}

	/**
	 * A REJECTED line shows the reviewer's own comment (escaped) under the status, offers Accept and Propose
	 * another (the decision is reversible), but never a second "No matching" toggle.
	 */
	private static void matchRejectedLineShowsCommentAndOffersAcceptAndReproposeOnly()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		final ReviewLine base = new ReviewLine(ReviewLine.MATCH, "42", "Company", "Requirements: {...}", "LLM", Integer.valueOf(80), "why", ReviewLine.REJECTED, false, false);
		lines.add(ReviewLine.withMatchExtras(base, "too aggressive on legalForm", null));
		final ReviewFilter showRejected = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.REJECTED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showRejected, null));
		ReviewPageTest.check("the reviewer's comment is shown", html.contains("too aggressive on legalForm"));
		ReviewPageTest.check("Accept is offered", html.contains("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"MATCH\" data-mdmf-id=\"42\""));
		ReviewPageTest.check("Propose another is offered", html.contains("data-mdmf-toggle=\"mdmf-match-repropose-42\""));
		ReviewPageTest.check("no second No matching toggle", !html.contains("data-mdmf-toggle=\"mdmf-match-noMatch-42\""));
	}

	private static void matchAppliedLineOffersNothing()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MATCH, "42", "Company", "Requirements: {...}", "LLM", Integer.valueOf(80), "why", ReviewLine.APPLIED, false, false));
		final ReviewFilter showApplied = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.APPLIED, 0);
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, showApplied, null));
		ReviewPageTest.check("no Accept", !html.contains("data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"MATCH\" data-mdmf-id=\"42\""));
		ReviewPageTest.check("no Propose another toggle", !html.contains("data-mdmf-toggle=\"mdmf-match-repropose-42\""));
		ReviewPageTest.check("no No matching toggle", !html.contains("data-mdmf-toggle=\"mdmf-match-noMatch-42\""));
	}

	/**
	 * The two inline panels carry real form controls whose ids match exactly what their own toggle button (and
	 * {@code ProposalReviewUserService.pageJs}) reference - a textarea for both, plus a confirmation checkbox
	 * for "No matching for this entity" that the confirm button's {@code data-mdmf-requires-checkbox} names.
	 */
	private static void matchInlinePanelsCarryTheRightFormControlIds()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MATCH, "42", "Company", "Requirements: {...}", "LLM", Integer.valueOf(80), "why", ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, ReviewFilter.defaultFilter(), null));

		ReviewPageTest.check("the repropose panel exists, hidden by default",
			html.contains("<div class=\"mdmf-match-inline\" id=\"mdmf-match-repropose-42\" style=\"display:none\">"));
		ReviewPageTest.check("its textarea carries the id its own Send button reads from",
			html.contains("id=\"mdmf-match-repropose-42-text\"") && html.contains("data-mdmf-comment-from=\"mdmf-match-repropose-42-text\""));
		ReviewPageTest.check("its Send button posts REPROPOSE for this exact id",
			html.contains("data-mdmf-action=\"REPROPOSE\" data-mdmf-nature=\"MATCH\" data-mdmf-id=\"42\""));

		ReviewPageTest.check("the no-matching panel exists, hidden by default",
			html.contains("<div class=\"mdmf-match-inline\" id=\"mdmf-match-noMatch-42\" style=\"display:none\">"));
		ReviewPageTest.check("its checkbox carries the id its own confirm button requires",
			html.contains("id=\"mdmf-match-noMatch-42-confirm\"") && html.contains("data-mdmf-requires-checkbox=\"mdmf-match-noMatch-42-confirm\""));
		ReviewPageTest.check("its textarea carries the id its own confirm button reads from",
			html.contains("id=\"mdmf-match-noMatch-42-text\"") && html.contains("data-mdmf-comment-from=\"mdmf-match-noMatch-42-text\""));
		ReviewPageTest.check("its confirm button posts REJECT for this exact id, requiring the checkbox",
			html.contains(
				"data-mdmf-action=\"REJECT\" data-mdmf-nature=\"MATCH\" data-mdmf-id=\"42\" data-mdmf-comment-from=\"mdmf-match-noMatch-42-text\" data-mdmf-requires-checkbox=\"mdmf-match-noMatch-42-confirm\""));
	}

	/** Same "every value is escaped" discipline as every other field on this page (see {@link #everyValueIsEscaped}). */
	private static void matchCommentAndNoteAreEscaped()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		final ReviewLine base = new ReviewLine(ReviewLine.MATCH, "42", "Company", "Requirements", "LLM", Integer.valueOf(80), "why", ReviewLine.REJECTED, false, false);
		lines.add(ReviewLine.withMatchExtras(base, "<script>alert(1)</script>", "<b>same plan again</b>"));
		final String html = ReviewPage.render(
			ReviewPageTest.input("COMPANIES360", lines, new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.REJECTED, 0), null));
		ReviewPageTest.check("comment script tag escaped", !html.contains("<script>alert(1)</script>"));
		ReviewPageTest.check("comment escaped form present", html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
		ReviewPageTest.check("note bold tag escaped", !html.contains("<b>same plan again</b>"));
		ReviewPageTest.check("note escaped form present", html.contains("&lt;b&gt;same plan again&lt;/b&gt;"));
	}

	// ------------------------------------------------------------------------------- tree-diagram-labels fix lot (2026-09-22), item B

	/** Item B: the counter names every MATCH line still awaiting review (PROPOSED/SUPERSEDED), never an ACCEPTED/REJECTED/APPLIED one, each an anchor to its own row id. */
	private static void matchCounterListsProposedAndSupersededLinesWithAnchors()
	{
		final String requirements = "{\"tiers\":[{\"outcome\":\"MATCH\",\"type\":\"EXACT\","
			+ "\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}],"
			+ "\"clusteringFields\":[\"vatNo\"],\"mergePolicy\":{}}";
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MATCH, "m10", "Company", "1 tier(s) - suspect only", "LLM", Integer.valueOf(80), "why", ReviewLine.PROPOSED, false, false,
			null, null, false, null, requirements, null, false, null, null));
		lines.add(new ReviewLine(ReviewLine.MATCH, "m11", "Contact", "1 tier(s) - suspect only", "LLM", Integer.valueOf(80), "why", ReviewLine.SUPERSEDED, false, false,
			null, null, false, null, requirements, null, false, null, null));
		// an already-decided MATCH line must never be counted or named
		lines.add(new ReviewLine(ReviewLine.MATCH, "m12", "Supplier", "1 tier(s) - suspect only", "LLM", Integer.valueOf(80), "why", ReviewLine.ACCEPTED, false, false,
			null, null, false, null, requirements, null, false, null, null));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0),
			null));
		ReviewPageTest.check("the counter names the count", html.contains("2 matching proposal(s) to review:"));
		ReviewPageTest.check("Company is an anchor to its own row id", html.contains("<a href=\"#mdmf-line-m10\">Company</a>"));
		ReviewPageTest.check("Contact is an anchor to its own row id", html.contains("<a href=\"#mdmf-line-m11\">Contact</a>"));
		ReviewPageTest.check("the ACCEPTED line (Supplier) is never named in the counter", !html.contains(">Supplier</a>"));
		ReviewPageTest.check("both lines show a position chip out of 2", ReviewPageTest.count(html, "/ 2") == 2);
	}

	/** No counter line at all when nothing is awaiting review - never a useless "0 matching proposal(s)". */
	private static void matchCounterSilentWhenNothingAwaitsReview()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.RELATION, "r1", "CONTACT.ACCOUNT_ID", "-> ACCOUNT", "RULE", Integer.valueOf(80), "why", ReviewLine.PROPOSED, false, false));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, null, null));
		ReviewPageTest.check("no counter line when there is no Matching proposal at all", !html.contains("mdmf-match-counter"));
		ReviewPageTest.check("no counter line when there is no Matching proposal at all (text)", !html.contains("matching proposal(s) to review"));
	}

	/** Item B: a MATCH line's own collapsed row carries the anchor target id the counter (and any other link) can jump to. */
	private static void matchLineRowCarriesAnAnchorIdForTheCounter()
	{
		final String requirements = "{\"tiers\":[{\"outcome\":\"MATCH\",\"type\":\"EXACT\","
			+ "\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}],"
			+ "\"clusteringFields\":[\"vatNo\"],\"mergePolicy\":{}}";
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MATCH, "m13", "Company", "1 tier(s) - suspect only", "LLM", Integer.valueOf(80), "why", ReviewLine.PROPOSED, false, false,
			null, null, false, null, requirements, null, false, null, null));
		final String html = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0),
			null));
		ReviewPageTest.check("the collapsed row carries id=\"mdmf-line-m13\"", html.contains("id=\"mdmf-line-m13\""));
	}

	/** Item C: the expander's own label, in both locales - it is the ONLY affordance left to reach the panel now that every line collapses by default. */
	private static void expanderSummaryWordingInBothLocales()
	{
		final String requirements = "{\"tiers\":[{\"outcome\":\"MATCH\",\"type\":\"EXACT\","
			+ "\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}],"
			+ "\"clusteringFields\":[\"vatNo\"],\"mergePolicy\":{}}";
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MATCH, "m14", "Company", "1 tier(s) - suspect only", "LLM", Integer.valueOf(80), "why", ReviewLine.PROPOSED, false, false,
			null, null, false, null, requirements, null, false, null, null));
		final ReviewFilter filter = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0);
		final String english = ReviewPage.render(ReviewPageTest.input("COMPANIES360", lines, filter, null));
		ReviewPageTest.check("english expander label", english.contains("Show details") && english.contains("survivorship"));
		final ReviewPage.Input englishInput = ReviewPageTest.input("COMPANIES360", lines, filter, null);
		final ReviewPage.Input frenchInput = new ReviewPage.Input(Locale.FRENCH, englishInput.projects, englishInput.selectedProjectCode, englishInput.selectedProjectLabel,
			englishInput.projectLocked, englishInput.allLines, englishInput.visibleLines, englishInput.filter, englishInput.bulkThreshold, englishInput.notice,
			englishInput.noticeKind, englishInput.confirmation, englishInput.matchCanSimulate, englishInput.matchCannotSimulateReason, englishInput.matchSimulationResults,
			englishInput.matchMaxSimulationPairs);
		final String french = ReviewPage.render(frenchInput);
		ReviewPageTest.check("french expander label", french.contains("Afficher les détails") && french.contains("survivance"));
	}

	private static int count(final String pHaystack, final String pNeedle)
	{
		int count = 0;
		int from = 0;
		while (true)
		{
			final int at = pHaystack.indexOf(pNeedle, from);
			if (at < 0)
			{
				return count;
			}
			count++;
			from = at + pNeedle.length();
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
