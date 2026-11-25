package com.on.ebx.factory.mappingmatrix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class MatrixHtmlTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		MatrixHtmlTest.rendersTitleAndOverviewForEveryEntity();
		MatrixHtmlTest.rendersOneMatrixColumnPerFeedWithToneClasses();
		MatrixHtmlTest.scalesTo8SourcesAnd6Entities();
		MatrixHtmlTest.noEntityRendersAFriendlyMessage();
		MatrixHtmlTest.rendersEntityAcceptRejectOnlyWhenProposed();
		MatrixHtmlTest.rendersFeedRejectedCommentAndHidesActionsOnceDecided();
		MatrixHtmlTest.renderHasNoScriptTagAndScriptHasTheHandlers();
		MatrixHtmlTest.overviewCurvesStartAndEndAtTheCardEdges();
		MatrixHtmlTest.sourceCardsShowTheirLandingTableNames();
		MatrixHtmlTest.cellShowsTheSampleOnItsOwnThirdLine();
		MatrixHtmlTest.flaggedCellGetsAttentionToneAndRowChip();
		MatrixHtmlTest.guardColumnRendersChipsOrNoneProposed();
		MatrixHtmlTest.suggestedFixDataIsEmbeddedOnTheFlaggedCell();
		MatrixHtmlTest.scriptStringsComeFromTheBundleNotHardcodedEnglish();
		MatrixHtmlTest.backToWizardLinkOnlyWhenStateCarriesAUrl();
		MatrixHtmlTest.summaryLineIsPluralSafeWithNoBareS();
		MatrixHtmlTest.overviewSitsInAHorizontallyScrollableWrapper();
		MatrixHtmlTest.footerLoadLinkShowsTheReasonWhenDisabled();
		MatrixHtmlTest.footerLoadLinkIsARealWizardLinkWhenReady();
		MatrixHtmlTest.cellRationaleAndCommentsAreHtmlEscapedInDataCellAttribute();
		MatrixHtmlTest.entityIdWithADoubleQuoteCannotBreakOutOfTheOnclickAttribute();
		MatrixHtmlTest.scriptEscapesEveryInspectorFieldBeforeAssigningInnerHtml();
		MatrixHtmlTest.scriptEColorRejectsANonHexColour();
		MatrixHtmlTest.aCellWithoutConfidenceStillWritesValidJson();
		MatrixHtmlTest.theCodeTableOfACellIsShownAndDecidedFromTheInspector();
		MatrixHtmlTest.derivedKeyRowRendersOneComputedCellInsteadOfNotProvidedPerSource();
		MatrixHtmlTest.footerSaysWhoNeedsYouAndOffersTheListInPlainWords();
		MatrixHtmlTest.designerSwitchFollowsTheHighlightedSource();
		MatrixHtmlTest.leftToDecidePanelListsEveryItemWithKindReasonAndItsOwnButtons();
		MatrixHtmlTest.tabsCarryTheirCounterAndTheFirstItemToDecideIsSelected();
		MatrixHtmlTest.onlyWhatIsLeftHidesRowsWithNothingLeft();
		MatrixHtmlTest.inspectorIsPinnedInADockAboveTheFooterAndTheScriptWalksTheList();
		MatrixHtmlTest.panelStateAndScrollFlagSurviveThroughHiddenFieldsAndDataIsEscaped();
		System.out.println("MatrixHtmlTest OK");
	}

	/**
	 * Security review 2026-09-24, finding I1: a cell's {@code rationale}/{@code reviewComment} (LLM text, a
	 * reviewer's free-text comment) end up in the {@code data-cell} JSON attribute {@link
	 * #cellJson}/{@link #jsonEscape} builds, and from there straight into {@code insp.innerHTML=html} in the
	 * script ({@link #script}). {@code data-cell} itself is HTML-escaped by {@link #render} ({@code
	 * MatrixHtml.esc(MatrixHtml.cellJson(...))}), so a value carrying real markup must never appear raw in the
	 * rendered page - only in its {@code &lt;}/{@code &gt;}/{@code &quot;} escaped form.
	 */
	private static void cellRationaleAndCommentsAreHtmlEscapedInDataCellAttribute()
	{
		final String imgPayload = "<img src=x onerror=alert(1)>";
		final String scriptPayload = "</script><script>alert(document.cookie)</script>";
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "salutation", "STRING", false, false, false);
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("a1", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", imgPayload, null, Integer.valueOf(90), "LLM", imgPayload, scriptPayload, 0, null, null));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "sap", "t1", "BUT_PARTNER", "ACCEPTED", null, "", false, null, cells);
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Personne", java.util.Arrays.asList(attr), java.util.Arrays.asList(feed));
		final MatrixModel.Project project = new MatrixModel.Project("P", java.util.Collections.<MatrixModel.Source>emptyList(), java.util.Arrays.asList(entity));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		final String html = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("the <img ...onerror=...> payload never appears raw in the page", !html.contains(imgPayload));
		MatrixHtmlTest.check("the </script><script> payload never appears raw in the page", !html.contains(scriptPayload));
		MatrixHtmlTest.check("it is HTML-escaped instead (rationale)", html.contains("&lt;img src=x onerror=alert(1)&gt;"));
		MatrixHtmlTest.check("it is HTML-escaped instead (reviewComment)",
			html.contains("&lt;/script&gt;&lt;script&gt;alert(document.cookie)&lt;/script&gt;"));
	}

	/**
	 * Security review 2026-09-24, finding I3: {@link #js}'s result is dropped straight inside a double-quoted
	 * {@code onclick="...('...')"} attribute by plain Java string concatenation (never re-escaped for HTML on
	 * its own) - a value carrying a {@code "} could otherwise close the attribute early and inject an
	 * arbitrary attribute (here, a second {@code onmouseover}). {@link #jsAttr} must HTML-escape it first.
	 */
	private static void entityIdWithADoubleQuoteCannotBreakOutOfTheOnclickAttribute()
	{
		final String evilId = "e1\" onmouseover=\"alert(1)";
		final MatrixModel.Entity evil =
			new MatrixModel.Entity(evilId, "Personne", java.util.Collections.<MatrixModel.Attr>emptyList(), java.util.Collections.<MatrixModel.Feed>emptyList());
		final MatrixModel.Project project = new MatrixModel.Project("P", java.util.Collections.<MatrixModel.Source>emptyList(), java.util.Arrays.asList(evil));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = evilId;
		final String html = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("the raw double quote never breaks out of the onclick attribute",
			!html.contains("mdmmOpenEntity('e1\" onmouseover=\"alert(1)')"));
		MatrixHtmlTest.check("it is HTML-escaped instead, the attribute stays intact",
			html.contains("mdmmOpenEntity('e1&quot; onmouseover=&quot;alert(1)')"));
	}

	/**
	 * Security review 2026-09-24, finding I1: {@link #script}'s {@code window.mdmmSelect}/{@code
	 * window.mdmmSelectGuard} build the inspector's HTML by plain JS string concatenation from the {@code
	 * data}/{@code g} object read off {@code data-cell}/{@code data-guard} (real source data, LLM text, review
	 * comments), then assign it with {@code insp.innerHTML=html}. Every such field must go through the
	 * script's own {@code e()} (HTML-escape) or, inside an {@code onclick="...('...')"} attribute, {@code
	 * eAttr()} (JS-string-escape then HTML-escape) - never dropped in raw. This is a text-content regression
	 * guard on the generated JS source itself (there is no JS engine in this JUnit-free harness to execute
	 * {@code insp.innerHTML=html} against a real DOM and inspect the result).
	 */
	private static void scriptEscapesEveryInspectorFieldBeforeAssigningInnerHtml()
	{
		final String script = MatrixHtml.script(Locale.ENGLISH);
		// text/attribute fields - wrapped in e()
		MatrixHtmlTest.check("srcName is escaped", script.contains("+e(data.srcName)+"));
		MatrixHtmlTest.check("col is escaped", script.contains("+e(data.col||'-')+"));
		MatrixHtmlTest.check("entity/attr are escaped", script.contains("+e(data.entity)+'.'+e(data.attr)+"));
		MatrixHtmlTest.check("statusLabel is escaped", script.contains("+e(data.statusLabel)+"));
		MatrixHtmlTest.check("explain is escaped", script.contains("+e(data.explain)+"));
		MatrixHtmlTest.check("feedComment is escaped", script.contains("+e(data.feedComment)+"));
		MatrixHtmlTest.check("attnReason is escaped", script.contains("+e(data.attnReason)+"));
		MatrixHtmlTest.check("rationale is escaped", script.contains("+e(data.rationale)+"));
		MatrixHtmlTest.check("reviewComment is escaped", script.contains("+e(data.reviewComment)+"));
		MatrixHtmlTest.check("sample values are escaped", script.contains("e(data.sampleIn||'')") && script.contains("+e(data.sampleOut||'')"));
		MatrixHtmlTest.check("origin is escaped", script.contains("+e(data.origin||'')+"));
		MatrixHtmlTest.check("altCol/altRationale are escaped", script.contains("+e(data.altCol||'')+") && script.contains("+e(data.altRationale)+"));
		MatrixHtmlTest.check("designerUrl (a plain href, not a JS-string context) is escaped with e(), not eAttr()",
			script.contains("href=\"'+e(data.designerUrl)+'\">"));
		MatrixHtmlTest.check("guard label/entity/attr/statusLabel/what/rationale are escaped",
			script.contains("+e(g.kindLabel)+") && script.contains("+e(g.label)+") && script.contains("+e(g.entity)+'.'+e(g.attr)+")
				&& script.contains("+e(g.statusLabel)+") && script.contains("+e(g.what)+") && script.contains("+e(g.rationale)+"));
		// ids embedded inside onclick="...('...')" - wrapped in eAttr(), the JS-string-then-HTML-escape helper
		MatrixHtmlTest.check("altMappingId is eAttr-escaped", script.contains("eAttr(data.altMappingId)"));
		MatrixHtmlTest.check("removeFieldAttrId is eAttr-escaped", script.contains("eAttr(data.removeFieldAttrId)"));
		MatrixHtmlTest.check("removeDuplicatesEntityId is eAttr-escaped", script.contains("eAttr(data.removeDuplicatesEntityId)"));
		MatrixHtmlTest.check("attrId/mappingId are eAttr-escaped", script.contains("eAttr(data.attrId)") && script.contains("eAttr(data.mappingId)"));
		MatrixHtmlTest.check("guard id/nature are eAttr-escaped", script.contains("eAttr(g.id)") && script.contains("eAttr(g.nature)"));
		// regression guard: none of these fields is ever concatenated RAW (the exact pre-fix pattern) any more
		MatrixHtmlTest.check("altMappingId is never concatenated raw", !script.contains("'+data.altMappingId+'"));
		MatrixHtmlTest.check("mappingId is never concatenated raw", !script.contains("'+data.mappingId+'"));
		MatrixHtmlTest.check("srcName is never concatenated raw", !script.contains("'\">'+data.srcName+'</span>'"));
		MatrixHtmlTest.check("g.id is never concatenated raw", !script.contains("+g.id+"));
	}

	/**
	 * Security review 2026-09-24, finding I1: {@code data.srcColor} is dropped straight into a {@code
	 * style="background:..."} attribute by {@link #script} - only a well-formed hex colour may pass through,
	 * or a CSS/attribute-injection payload (e.g. {@code red;}</style><script>...} on a build that also allows
	 * unescaped style content) rides along. {@code eColor} is the generated JS function that enforces this;
	 * there being no JS engine here, the assertions check the generated source text for the exact guard
	 * (the regex itself, applied before the value ever reaches the style attribute).
	 */
	private static void scriptEColorRejectsANonHexColour()
	{
		final String script = MatrixHtml.script(Locale.ENGLISH);
		MatrixHtmlTest.check("eColor is defined and only accepts a well-formed hex colour (3-8 hex digits after #), else a neutral default",
			script.contains("function eColor(s){return /^#[0-9a-fA-F]{3,8}$/.test(s||'')?s:'#98a2b3';}"));
		MatrixHtmlTest.check("srcColor is passed through eColor before reaching the style attribute",
			script.contains("style=\"background:'+eColor(data.srcColor)+'\">"));
		MatrixHtmlTest.check("srcColor is never dropped into the style attribute raw (the exact pre-fix pattern)",
			!script.contains("style=\"background:'+data.srcColor+'\">"));
	}

	/** fix/wizard-live-wave1 (2026-09-24, live bug: "Load the staging" stayed disabled with no reason shown). */
	private static void footerLoadLinkShowsTheReasonWhenDisabled()
	{
		// twoSourceTwoEntityFixture's "personne" entity has one PROPOSED mapping (email) - readyToLoad is false.
		final MatrixModel.Project project = MatrixHtmlTest.twoSourceTwoEntityFixture();
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "personne";
		final String html = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("the load link is marked disabled", html.contains("aria-disabled=\"true\""));
		// staging-screens lot (2026-10-17): the footer's load control is a BUTTON that builds the staging (it was a link back to the wizard)
		final int linkStart = html.indexOf("<button type=\"button\" class=\"mdmmBtn\" id=\"mdmmBuild\" aria-disabled=\"true\" disabled=\"disabled\" title=\"");
		MatrixHtmlTest.check("the disabled build button carries a title explaining why (never a bare disabled button)", linkStart >= 0);
		final String titleAttr = html.substring(linkStart, html.indexOf("\">", linkStart));
		MatrixHtmlTest.check("the reason names what is left, plural-safe (no bare \"(s)\")", titleAttr.contains("item") && !titleAttr.contains("(s)"));
		// integration 2026-09-30: the title says exactly what the footer's clickable count says - never a second, larger number
		final String title = titleAttr.substring(titleAttr.indexOf("title=\"") + 7);
		MatrixHtmlTest.check("the footer's own count gives the same reason", html.contains("onclick=\"mdmmQueueToggle()\">" + title + "</button>"));
		MatrixHtmlTest.check("no onclick while disabled", !html.contains("id=\"mdmmBuild\" aria-disabled=\"true\" onclick"));
	}

	/** Once ready, the link must be a REAL navigable link to the wizard's own step 4 - never javascript:void(0) when a wizard URL is known. */
	private static void footerLoadLinkIsARealWizardLinkWhenReady()
	{
		final MatrixModel.Attr done = new MatrixModel.Attr("a1", "nom", "STRING", true, false, false, "ACCEPTED", Integer.valueOf(90));
		final MatrixModel.Entity doneEntity =
			new MatrixModel.Entity("e1", "Person", java.util.Arrays.asList(done), java.util.Collections.<MatrixModel.Feed>emptyList(), "ACCEPTED", Integer.valueOf(100));
		final MatrixModel.Project project = new MatrixModel.Project("Personnes", java.util.Collections.<MatrixModel.Source>emptyList(), java.util.Arrays.asList(doneEntity));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		state.wizardUrl = "/ebx-ui/ui/custom/MdmFactory/data/BFoo/MdmFactory/root/Project?service=ProjectWizard";
		final String html = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("the load link is marked enabled", html.contains("aria-disabled=\"false\""));
		MatrixHtmlTest.check("the build button posts LOAD_STAGING (the service starts the build, the page then goes back to step 4 through the back link)",
			html.contains("<button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary mdmmBtnBuild\" id=\"mdmmBuild\" aria-disabled=\"false\" onclick=\"mdmmLoadStaging()\">Build and load the staging</button>")
				&& html.contains("<a id=\"mdmmBackLink\" class=\"mdmf-matrix-designer-link\" target=\"_top\" href=\"" + state.wizardUrl + "\">"));
		MatrixHtmlTest.check("never the inert javascript:void(0) form in this case", !html.contains("javascript:void(0)"));
	}

	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug item 2): the top-left "Back to the wizard" link only renders
	 * when the caller (ebx.ui.MappingMatrixUserService) actually resolved a project and set {@link
	 * MatrixHtml.State#wizardUrl} - never invented, never shown for a null/blank URL.
	 */
	private static void backToWizardLinkOnlyWhenStateCarriesAUrl()
	{
		final MatrixModel.Project project = MatrixHtmlTest.twoSourceTwoEntityFixture();
		final MatrixHtml.State withUrl = new MatrixHtml.State();
		withUrl.activeEntityId = "personne";
		// no '&' on purpose: MatrixHtml.esc HTML-escapes the href attribute value (& becomes &amp;), this
		// assertion compares the raw URL as-is, same convention as WizardPageTest's own stagingRejectsUrl fixture.
		withUrl.wizardUrl = "/ebx-ui/ui/custom/MdmFactory/data/BFoo/MdmFactory/root/Project?service=ProjectWizard";
		final String htmlWithUrl = MatrixHtml.render(project, withUrl);
		MatrixHtmlTest.check("renders the back link with the given URL", htmlWithUrl.contains("mdmmBackToWizard") && htmlWithUrl.contains(withUrl.wizardUrl));
		MatrixHtmlTest.check("the link is a full-page departure, armed the same way as the mode-switch link (perspective rewrite + voluntary-leave)",
			htmlWithUrl.contains("<a id=\"mdmmBackLink\" class=\"mdmf-matrix-designer-link\" target=\"_top\" href=\"" + withUrl.wizardUrl + "\">"));

		final MatrixHtml.State withoutUrl = new MatrixHtml.State();
		withoutUrl.activeEntityId = "personne";
		final String htmlWithoutUrl = MatrixHtml.render(project, withoutUrl);
		// "mdmmBackToWizard" alone would also match the class's own CSS rule (always present in the <style>
		// block) - the actual markup is the "<div class=\"mdmmBackToWizard\">" wrapper, omitted entirely here.
		MatrixHtmlTest.check("no back link when the state carries no URL", !htmlWithoutUrl.contains("<div class=\"mdmmBackToWizard\">"));
	}

	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug item 12): the guidance summary and the attention-point line
	 * (renderGuidance's two "mdmmSummary" divs) never read a bare "(s)" - scoped to just those two divs, not the
	 * whole page: other, unrelated corners of this screen (the field-count corner chip, the footer's flagged-
	 * proposal count...) still have their own "(s)" wording, out of this bug's scope.
	 */
	private static void summaryLineIsPluralSafeWithNoBareS()
	{
		final MatrixModel.Project project = MatrixHtmlTest.twoSourceTwoEntityFixture();
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "personne";
		final String html = MatrixHtml.render(project, state);
		final int first = html.indexOf("<div class=\"mdmmSummary\"");
		final int afterSecond = html.indexOf("</div>", html.indexOf("<div class=\"mdmmSummary\"", first + 1)) + "</div>".length();
		final String summaryBlock = html.substring(first, afterSecond);
		MatrixHtmlTest.check("no bare \"(s)\" in the guidance summary/attention line", !summaryBlock.contains("(s)"));
		MatrixHtmlTest.check("uses the shared wizard.unit.* plural vocabulary (business object)", summaryBlock.contains("business object"));
	}

	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug item 13): the overview's own fixed-pixel-coordinate box
	 * (.mdmmOv, unchanged width so its SVG and absolutely-positioned cards stay in sync) now sits inside a
	 * SEPARATE wrapper (.mdmmOvWrap) that is the one allowed to fit/scroll within its own card - see
	 * renderOverview's own Javadoc for why the box itself cannot simply be clamped with max-width.
	 */
	private static void overviewSitsInAHorizontallyScrollableWrapper()
	{
		final MatrixModel.Project project = MatrixHtmlTest.twoSourceTwoEntityFixture();
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "personne";
		final String html = MatrixHtml.render(project, state);
		final int wrapOpen = html.indexOf("<div class=\"mdmmOvWrap\">");
		final int ovOpen = html.indexOf("<div class=\"mdmmOv\"", wrapOpen);
		MatrixHtmlTest.check("the fixed-width overview box is nested inside the scrollable wrapper", wrapOpen >= 0 && ovOpen > wrapOpen);
		MatrixHtmlTest.check("the wrapper is styled to scroll horizontally, never to clip", html.contains(".mdmmOvWrap{overflow-x:auto"));
	}

	/** Delta 2: a flagged cell gets its own tone ("attn") and the row gets a "needs your eye" chip. */
	private static void flaggedCellGetsAttentionToneAndRowChip()
	{
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "mobilePhone", "STRING", false, false, false);
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("a1", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "MobilePhone", null, Integer.valueOf(90), "LLM", null, null, 0, null, null,
			"EMPTY_COLUMN", "empty in this source (0 / 180)", null));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "sf", "t1", "Contact", "ACCEPTED", null, "", false, null, cells);
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Personne", java.util.Arrays.asList(attr), java.util.Arrays.asList(feed));
		final MatrixModel.Project project = new MatrixModel.Project("P", java.util.Collections.<MatrixModel.Source>emptyList(), java.util.Arrays.asList(entity));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		final String html = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("the flagged cell renders the 'attn' tone", html.contains("mdmmCell attn"));
		MatrixHtmlTest.check("the row header shows the 'needs your eye' chip", html.contains("mdmmFlagChip attn"));
		MatrixHtmlTest.check("the cell's own JSON detail carries the attention reason", html.contains("empty in this source (0 / 180)"));
	}

	/** Delta 1: the "Rules and checks" column - a chip per guard, "none proposed" when there is none. */
	private static void guardColumnRendersChipsOrNoneProposed()
	{
		final MatrixModel.Project project = MatrixHtmlTest.twoSourceTwoEntityFixture();
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "personne";
		final String html = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("the guard column header is rendered", html.contains("mdmmGuardHead"));
		MatrixHtmlTest.check("a field with no guard shows 'none proposed'", html.contains("mdmmGuardNone"));

		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "email", "STRING", false, false, false);
		final com.on.ebx.factory.guard.Guard guard = new com.on.ebx.factory.guard.Guard("g1", com.on.ebx.factory.review.ReviewLine.QUALITY, "e1", "a1", "PATTERN",
			"^[^@]+@[^@]+$", "WARN", null, com.on.ebx.factory.review.ReviewLine.PROPOSED, "LLM", Integer.valueOf(80), "looks like an email rule", null, null, null);
		final MatrixModel.Attr attrWithGuard =
			new MatrixModel.Attr("a1", "email", "STRING", false, false, false, "", null, java.util.Arrays.asList(guard));
		final MatrixModel.Entity entityWithGuard = new MatrixModel.Entity("e1", "Personne", java.util.Arrays.asList(attrWithGuard),
			java.util.Collections.<MatrixModel.Feed>emptyList());
		final MatrixModel.Project projectWithGuard =
			new MatrixModel.Project("P", java.util.Collections.<MatrixModel.Source>emptyList(), java.util.Arrays.asList(entityWithGuard));
		final MatrixHtml.State s2 = new MatrixHtml.State();
		s2.activeEntityId = "e1";
		final String htmlWithGuard = MatrixHtml.render(projectWithGuard, s2);
		MatrixHtmlTest.check("a rule chip is rendered for the field's own guard", htmlWithGuard.contains("mdmmGChip"));
		// data-guard is an HTML attribute value, so its embedded JSON is HTML-escaped (same convention data-cell already follows).
		MatrixHtmlTest.check("the chip's own JSON detail carries the guard id", htmlWithGuard.contains("&quot;id&quot;:&quot;g1&quot;"));
	}

	/** Delta 3: a flagged cell whose attribute has a PROPOSED alternativeTo mapping embeds the suggested fix. */
	private static void suggestedFixDataIsEmbeddedOnTheFlaggedCell()
	{
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "salutation", "STRING", false, false, false);
		final MatrixModel.Alternative alt = new MatrixModel.Alternative("mAlt", "Sex", java.util.Arrays.asList("map()"),
			MatrixModel.Sample.of("1", "Mr."), "Sex looks like the real source of the salutation");
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("a1", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "BusinessPartnerCategory", null, Integer.valueOf(90), "LLM", null, null, 0, null, null,
			"CONSTANT_COLUMN", "always the same value", alt));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "sap", "t1", "BUT_PARTNER", "ACCEPTED", null, "", false, null, cells);
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Personne", java.util.Arrays.asList(attr), java.util.Arrays.asList(feed));
		final MatrixModel.Project project = new MatrixModel.Project("P", java.util.Collections.<MatrixModel.Source>emptyList(), java.util.Arrays.asList(entity));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		final String html = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("the suggested alternative's mapping id is embedded", html.contains("&quot;altMappingId&quot;:&quot;mAlt&quot;"));
		MatrixHtmlTest.check("the suggested alternative's column is embedded", html.contains("&quot;altCol&quot;:&quot;Sex&quot;"));
	}

	/** Delta 5: the script's own former hard-coded English strings now come from the bundle - FR must differ from EN. */
	private static void scriptStringsComeFromTheBundleNotHardcodedEnglish()
	{
		final String en = MatrixHtml.script(Locale.ENGLISH);
		final String fr = MatrixHtml.script(java.util.Locale.FRENCH);
		MatrixHtmlTest.check("the English script carries the English 'Accept' string", en.contains("accept:'Accept'"));
		MatrixHtmlTest.check("the French script does NOT carry the English 'Accept' string", !fr.contains("accept:'Accept'"));
		MatrixHtmlTest.check("the French script carries the French translation instead", fr.contains("accept:'Accepter'"));
	}

	private static void rendersTitleAndOverviewForEveryEntity()
	{
		final MatrixModel.Project project = MatrixHtmlTest.twoSourceTwoEntityFixture();
		final MatrixHtml.State state = new MatrixHtml.State();
		state.locale = Locale.ENGLISH;
		state.activeEntityId = "personne";
		final String html = MatrixHtml.render(project, state);

		MatrixHtmlTest.check("renders the project label in the eyebrow", html.contains("Personnes"));
		MatrixHtmlTest.check("renders one source card per source", html.contains("SALESFORCE") && html.contains("SAP"));
		MatrixHtmlTest.check("renders one entity card per entity", html.contains("Personne") && html.contains("Adresse"));
		MatrixHtmlTest.check("the active entity tab carries the 'on' class", html.contains("mdmmTab on"));
		MatrixHtmlTest.check("the hidden mdmmEntity field carries the active entity", html.contains("id=\"mdmmEntity\" name=\"mdmmEntity\" value=\"personne\""));
	}

	private static void rendersOneMatrixColumnPerFeedWithToneClasses()
	{
		final MatrixModel.Project project = MatrixHtmlTest.twoSourceTwoEntityFixture();
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "personne";
		final String html = MatrixHtml.render(project, state);

		MatrixHtmlTest.check("an accepted mapping renders the 'good' tone", html.contains("mdmmCell good"));
		// live fix (bug 5): a plain to-validate cell now renders the neutral 'prop' tone, not 'warn' - see
		// MatrixHtml#toneClass's own comment (a flagged cell alone gets the amber-dashed 'attn' tone).
		MatrixHtmlTest.check("a proposed mapping renders the neutral 'prop' tone", html.contains("mdmmCell prop"));
		MatrixHtmlTest.check("a mandatory attribute with no cell on the second feed renders 'missing'", html.contains("mdmmCell missing"));
		MatrixHtmlTest.check("the missing cell's line1 reads Missing", html.contains("Missing"));
		MatrixHtmlTest.check("the inspector shell is present", html.contains("id=\"mdmmInspector\""));
		MatrixHtmlTest.check("each cell embeds its own JSON detail for client-side selection (no round trip)", html.contains("data-cell=\""));
		MatrixHtmlTest.check("the bulk-accept-per-feed button is rendered", html.contains("mdmmBulkFeed("));
	}

	/** docs/MAPPING-MATRIX.md: "must work for 1 to ~8 sources and 1 to ~6 entities". */
	private static void scalesTo8SourcesAnd6Entities()
	{
		final List<MatrixModel.Source> sources = new ArrayList<MatrixModel.Source>();
		for (int i = 0; i < 8; i++)
		{
			sources.add(new MatrixModel.Source("s" + i, "SRC" + i, "#123456"));
		}
		final List<MatrixModel.Entity> entities = new ArrayList<MatrixModel.Entity>();
		for (int e = 0; e < 6; e++)
		{
			final List<MatrixModel.Attr> attrs = java.util.Collections.singletonList(new MatrixModel.Attr("a", "name", "STRING", false, false, false));
			final List<MatrixModel.Feed> feeds = new ArrayList<MatrixModel.Feed>();
			for (int i = 0; i < 8; i++)
			{
				feeds.add(new MatrixModel.Feed("f" + e + "_" + i, "s" + i, "t" + i, "Table" + i, "ACCEPTED", null, "", false, null, null));
			}
			entities.add(new MatrixModel.Entity("e" + e, "Entity" + e, attrs, feeds));
		}
		final MatrixModel.Project project = new MatrixModel.Project("Big", sources, entities);
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e0";
		final String html = MatrixHtml.render(project, state); // must not throw for 8 sources / 6 entities
		MatrixHtmlTest.check("every one of the 8 sources gets its own card", MatrixHtmlTest.count(html, "onclick=\"mdmmFocus(") == 8);
		MatrixHtmlTest.check("every one of the 6 entities gets its own card", MatrixHtmlTest.count(html, "onclick=\"mdmmOpenEntity(") == 6 + 6 /* overview cards + toolbar tabs */);
	}

	private static void noEntityRendersAFriendlyMessage()
	{
		final MatrixModel.Project project = new MatrixModel.Project("Empty", java.util.Collections.<MatrixModel.Source>emptyList(),
			new ArrayList<MatrixModel.Entity>());
		final MatrixHtml.State state = new MatrixHtml.State();
		final String html = MatrixHtml.render(project, state); // no entity at all - must not throw
		MatrixHtmlTest.check("shows a friendly empty message rather than an empty matrix", html.contains("mdmmEmpty"));
	}


	/**
	 * fix/mapping-matrix-live (2026-09-24): the whole reported live symptom list traces back to ONE bug - the
	 * interaction script was written as a hand-authored {@code <script>} tag, which EBX never executes
	 * (CLAUDE.md sec 9). {@link MatrixHtml#render} must never contain one, and {@link MatrixHtml#script} must
	 * carry the actual handlers {@code ebx.ui.MappingMatrixUserService#write} feeds to {@code addJS_cr}.
	 */
	private static void renderHasNoScriptTagAndScriptHasTheHandlers()
	{
		final MatrixModel.Project project = MatrixHtmlTest.twoSourceTwoEntityFixture();
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "personne";
		final String html = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("the rendered HTML never contains a <script tag - that is why nothing ran live", !html.toLowerCase(java.util.Locale.ROOT).contains("<script"));

		final String js = MatrixHtml.script(Locale.ENGLISH);
		MatrixHtmlTest.check("the script defines the entity-card/tab handler", js.contains("window.mdmmOpenEntity=function"));
		MatrixHtmlTest.check("the script defines the chip handler (entity open + source focus in one click)", js.contains("window.mdmmOpenEntityFocus=function"));
		// fix/matrix-goto-entity (2026-09-27): setupObjectContext re-reads mdmmEntity AFTER the event on a postback, so
		// both entity handlers must write the target id into that hidden field before submitting, or the switch is undone.
		MatrixHtmlTest.check("the entity-card/tab handler writes the target entity into mdmmEntity before submitting",
			js.contains("window.mdmmOpenEntity=function(id){byId('mdmmEntity').value=id;submitAction('GOTO_ENTITY',id"));
		MatrixHtmlTest.check("the chip handler writes the target entity into mdmmEntity before submitting",
			js.contains("window.mdmmOpenEntityFocus=function(entityId,sourceId){byId('mdmmEntity').value=entityId;"));
		MatrixHtmlTest.check("the script defines cell selection", js.contains("window.mdmmSelect=function"));
		MatrixHtmlTest.check("the script defines the source-focus toggle", js.contains("window.mdmmFocus=function"));
		MatrixHtmlTest.check("the script defines the only-todo toggle", js.contains("window.mdmmToggleTodo=function"));
		MatrixHtmlTest.check("the script defines entity accept/reject", js.contains("window.mdmmAcceptEntity=function") && js.contains("window.mdmmRejectEntity=function"));
		MatrixHtmlTest.check("the script defines attribute accept/reject", js.contains("window.mdmmAcceptAttribute=function") && js.contains("window.mdmmRejectAttribute=function"));
		MatrixHtmlTest.check("the script defines feed accept/reject", js.contains("window.mdmmAcceptFeed=function") && js.contains("window.mdmmRejectFeed=function"));
		MatrixHtmlTest.check("the script defines the mapping accept/reject and the two-step cell action",
			js.contains("window.mdmmAccept=function") && js.contains("window.mdmmReject=function") && js.contains("window.mdmmAcceptAttrThenMapping=function"));
		MatrixHtmlTest.check("the script itself never contains a <script tag either (it IS the raw JS addJS_cr expects)",
			!js.toLowerCase(java.util.Locale.ROOT).contains("<script"));
	}

	/**
	 * fix/mapping-matrix-live: "the overview curves do not start at the source cards (visible gap of ~50px)" -
	 * the SVG and the cards must share one px coordinate system. A curve's path starts at the source card's
	 * own right edge (left:10px + width:200px = 210) and ends at the entity card's own left edge (920).
	 */
	private static void overviewCurvesStartAndEndAtTheCardEdges()
	{
		final MatrixModel.Project project = MatrixHtmlTest.twoSourceTwoEntityFixture();
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "personne";
		final String html = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("at least one curve path is rendered, starting at x=210 (the source card's own right edge)",
			html.contains("<path d=\"M 210 "));
		MatrixHtmlTest.check("every curve path ends at x=920 (the entity card's own left edge)", MatrixHtmlTest.count(html, " 920 ") > 0);
		MatrixHtmlTest.check("the SVG declares explicit pixel width/height (never a scaled viewBox) so 1 unit = 1 real px, the same unit the cards use",
			html.contains("<svg class=\"mdmmOvSvg\" width=\"1180\" height=\""));
		MatrixHtmlTest.check("the overview container is pinned to the very same 1180px width",
			html.contains("mdmmOv\" style=\"height:") && html.contains("width:1180px"));
	}

	/** fix/mapping-matrix-live: "the source cards show only the source tag" - the landing table(s) must be shown too. */
	private static void sourceCardsShowTheirLandingTableNames()
	{
		final MatrixModel.Project project = MatrixHtmlTest.twoSourceTwoEntityFixture();
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "personne";
		final String html = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("the SAP source card shows its own landing table name next to the tag", html.contains("mdmmSrcTbl\">BUT_PARTNER<"));
	}

	/** fix/mapping-matrix-live (coordinator: "the product owner explicitly asked for example values that help"): the sample shows directly on the cell, not only in the inspector. */
	private static void cellShowsTheSampleOnItsOwnThirdLine()
	{
		final MatrixModel.Project project = MatrixHtmlTest.twoSourceTwoEntityFixture();
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "personne";
		final String html = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("the accepted mapping's own sample ('Dupont' -> 'DUPONT') is visible right on the cell, third line",
			html.contains("class=\"c3\">Dupont → DUPONT<"));
	}

	private static MatrixModel.Project twoSourceTwoEntityFixture()
	{
		final MatrixModel.Source sf = new MatrixModel.Source("sf", "SALESFORCE", "#175cd3");
		final MatrixModel.Source sap = new MatrixModel.Source("sap", "SAP", "#0e9384");

		final MatrixModel.Attr nom = new MatrixModel.Attr("nom", "nom", "STRING", true, false, false);
		final MatrixModel.Attr email = new MatrixModel.Attr("email", "email", "STRING", false, false, false);

		final Map<String, MatrixModel.Cell> sfCells = new LinkedHashMap<String, MatrixModel.Cell>();
		sfCells.put("nom", new MatrixModel.Cell(MatrixModel.ACCEPTED, "m1", "LastName", java.util.Arrays.asList("trim()", "upper()"), Integer.valueOf(100), "RULE",
			null, null, 0, null, MatrixModel.Sample.of("Dupont", "DUPONT")));
		sfCells.put("email", new MatrixModel.Cell(MatrixModel.PROPOSED, "m2", "Email", java.util.Arrays.asList("trim()", "lower()"), Integer.valueOf(70), "LLM",
			"looks like an email", null, 0, null, MatrixModel.Sample.unavailable("no sample row")));
		final MatrixModel.Feed sfFeed = new MatrixModel.Feed("f1", "sf", "t1", "Contact", "ACCEPTED", null, "", false, null, sfCells);

		// SAP feed covers nothing at all for either attribute -> nom (mandatory) reads MISSING, email reads NONE
		final MatrixModel.Feed sapFeed = new MatrixModel.Feed("f2", "sap", "t2", "BUT_PARTNER", "ACCEPTED", null, "", false, null, null);

		final MatrixModel.Entity personne = new MatrixModel.Entity("personne", "Personne", java.util.Arrays.asList(nom, email),
			java.util.Arrays.asList(sfFeed, sapFeed));
		final MatrixModel.Entity adresse = new MatrixModel.Entity("adresse", "Adresse", java.util.Collections.<MatrixModel.Attr>emptyList(),
			java.util.Collections.<MatrixModel.Feed>emptyList());

		return new MatrixModel.Project("Personnes", java.util.Arrays.asList(sf, sap), java.util.Arrays.asList(personne, adresse));
	}


	/** Coordinator review, 2026-09-23: "entity: Accept / Reject in the toolbar when the active entity is not decided". */
	private static void rendersEntityAcceptRejectOnlyWhenProposed()
	{
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "nom", "STRING", false, false, false, "PROPOSED", Integer.valueOf(90));
		final MatrixModel.Entity proposedEntity = new MatrixModel.Entity("e1", "Personne", java.util.Arrays.asList(attr),
			java.util.Collections.<MatrixModel.Feed>emptyList(), "PROPOSED", Integer.valueOf(80));
		final MatrixModel.Project proposedProject = new MatrixModel.Project("P", java.util.Collections.<MatrixModel.Source>emptyList(),
			java.util.Arrays.asList(proposedEntity));
		final MatrixHtml.State s1 = new MatrixHtml.State();
		s1.activeEntityId = "e1";
		final String proposedHtml = MatrixHtml.render(proposedProject, s1);
		MatrixHtmlTest.check("a PROPOSED entity gets a toolbar Accept button", proposedHtml.contains("mdmmAcceptEntity('e1')"));
		MatrixHtmlTest.check("a PROPOSED entity gets a toolbar Reject button", proposedHtml.contains("mdmmRejectEntity('e1')"));
		MatrixHtmlTest.check("a PROPOSED, non-key attribute gets its own row-header Accept button", proposedHtml.contains("mdmmAcceptAttribute('a1')"));

		final MatrixModel.Entity acceptedEntity = new MatrixModel.Entity("e1", "Personne", java.util.Collections.<MatrixModel.Attr>emptyList(),
			java.util.Collections.<MatrixModel.Feed>emptyList(), "ACCEPTED", Integer.valueOf(80));
		final MatrixModel.Project acceptedProject = new MatrixModel.Project("P", java.util.Collections.<MatrixModel.Source>emptyList(),
			java.util.Arrays.asList(acceptedEntity));
		final MatrixHtml.State s2 = new MatrixHtml.State();
		s2.activeEntityId = "e1";
		final String acceptedHtml = MatrixHtml.render(acceptedProject, s2);
		MatrixHtmlTest.check("an already-ACCEPTED entity gets no toolbar Accept/Reject buttons", !acceptedHtml.contains("mdmmAcceptEntity("));
		MatrixHtmlTest.check("no Reject button either", !acceptedHtml.contains("mdmmRejectEntity("));
	}

	/** Coordinator review: a rejected feed's own reviewComment shows in the header, and its Accept/Reject actions disappear once decided. */
	private static void rendersFeedRejectedCommentAndHidesActionsOnceDecided()
	{
		final MatrixModel.Feed rejectedFeed = new MatrixModel.Feed("f1", "s1", "t1", "Table", "REJECTED", null, "", false, null, null, Integer.valueOf(70),
			"Numbers look wrong for this source");
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Personne", java.util.Collections.<MatrixModel.Attr>emptyList(),
			java.util.Arrays.asList(rejectedFeed), "ACCEPTED", Integer.valueOf(100));
		final MatrixModel.Source source = new MatrixModel.Source("s1", "SAP", "#0e9384");
		final MatrixModel.Project project = new MatrixModel.Project("P", java.util.Arrays.asList(source), java.util.Arrays.asList(entity));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		final String html = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("the rejected feed's own comment is shown (as a title tooltip)", html.contains("Numbers look wrong for this source"));
		MatrixHtmlTest.check("a REJECTED feed gets no more Accept/Reject actions of its own", !html.contains("mdmmAcceptFeed(") && !html.contains("mdmmRejectFeed("));
	}

	private static int count(final String pHaystack, final String pNeedle)
	{
		int count = 0;
		int index = 0;
		while ((index = pHaystack.indexOf(pNeedle, index)) >= 0)
		{
			count++;
			index += pNeedle.length();
		}
		return count;
	}

	/**
	 * fix/matrix-perspective-loss (2026-09-27, live on project RET): a link with no confidence wrote
	 * {@code "confidence":,} into its {@code data-cell} JSON; the inspector's JSON.parse threw at load, and since
	 * EBX runs every addJS of the page as one script, the perspective rewrite registered after it was never
	 * installed - the Designer switch left the perspective. Every data-cell must parse.
	 */
	/**
	 * Codes transcoding lot H3 (2026-10-17): a mapping with a code table (a {@code TRANSCODE_REFERENCE} step carrying its own) hands the table to the inspector as
	 * {@code codeLists} in the cell's JSON - observed code, target, rows - and the script draws it as a two-column table with Accept / Edit / Reject, every value escaped.
	 */
	private static void theCodeTableOfACellIsShownAndDecidedFromTheInspector() throws java.io.IOException
	{
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "title", "STRING", false, false, false);
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		final String hostile = "<b>x</b> \"q\"";
		final List<MatrixModel.CodeRow> rows = java.util.Arrays.asList(new MatrixModel.CodeRow("M.", "MR", 77L), new MatrixModel.CodeRow("Mme", "", 63L), new MatrixModel.CodeRow(hostile, "MRS", 1L));
		final MatrixModel.CodeList open = new MatrixModel.CodeList("s1", MatrixModel.PROPOSED, Integer.valueOf(59), "", java.util.Arrays.asList("MR", "MRS"), rows, 141L, 63L);
		final MatrixModel.CodeList decided = new MatrixModel.CodeList("s2", MatrixModel.ACCEPTED, null, "TITLE", java.util.Arrays.asList("MR"), rows, 141L, 0L);
		cells.put("a1", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "civilite", null, Integer.valueOf(90), "LLM", null, null, 0, null, null, "", "", null,
			java.util.Arrays.asList(open, decided)));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "sap", "t1", "LOYALTY", "ACCEPTED", null, "", false, null, cells);
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Person", java.util.Arrays.asList(attr), java.util.Arrays.asList(feed));
		final MatrixModel.Project project = new MatrixModel.Project("P", java.util.Collections.<MatrixModel.Source>emptyList(), java.util.Arrays.asList(entity));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		final String html = MatrixHtml.render(project, state);
		final java.util.regex.Matcher m = java.util.regex.Pattern.compile("data-cell=\"([^\"]*)\"").matcher(html);
		MatrixHtmlTest.check("a cell is rendered", m.find());
		final String json = m.group(1).replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&amp;", "&");
		final com.fasterxml.jackson.databind.JsonNode cell = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
		final com.fasterxml.jackson.databind.JsonNode lists = cell.path("codeLists");
		MatrixHtmlTest.check("two code tables, in order", lists.isArray() && lists.size() == 2 && "s1".equals(lists.get(0).path("id").asText()));
		MatrixHtmlTest.check("the proposal is open, the accepted one is not", lists.get(0).path("open").asBoolean() && !lists.get(1).path("open").asBoolean());
		MatrixHtmlTest.check("status, confidence, targets, reference", "PROPOSED".equals(lists.get(0).path("status").asText()) && lists.get(0).path("confidence").asInt() == 59
			&& lists.get(0).path("targets").size() == 2 && "TITLE".equals(lists.get(1).path("basedOn").asText()) && lists.get(1).path("confidence").isNull());
		MatrixHtmlTest.check("rows: observed code, target (empty = none yet), rows of the source", lists.get(0).path("rows").size() == 3 && "M.".equals(lists.get(0).path("rows").get(0).path("c").asText())
			&& "MR".equals(lists.get(0).path("rows").get(0).path("t").asText()) && lists.get(0).path("rows").get(1).path("t").asText().isEmpty()
			&& lists.get(0).path("rows").get(0).path("n").asLong() == 77L);
		MatrixHtmlTest.check("a hostile code survives the JSON round trip", hostile.equals(lists.get(0).path("rows").get(2).path("c").asText()));
		MatrixHtmlTest.check("counts of rows still without a target", lists.get(0).path("unresolved").asLong() == 63L && lists.get(0).path("total").asLong() == 141L);
		MatrixHtmlTest.check("the hostile code never appears raw in the page", !html.contains("<b>x</b>"));

		final String script = MatrixHtml.script(Locale.ENGLISH);
		MatrixHtmlTest.check("the script draws the table and posts the three actions", script.contains("function codeBlock(btn)") && script.contains("ACCEPT_CODES") && script.contains("REJECT_CODES")
			&& script.contains("submitAction('SAVE_CODES'") && script.contains("window.mdmmCodesEdit") && script.contains("window.mdmmCodesSave"));
		MatrixHtmlTest.check("every code and target is escaped before it reaches innerHTML", script.contains("e(r.c)") && script.contains("e(r.t)") && script.contains("e(l.statusLabel)")
			&& !script.contains("+r.c+") && !script.contains("+r.t+") && !script.contains("+l.id+"));
		MatrixHtmlTest.check("the edit reads the code from an attribute, not from a JS string", script.contains("getAttribute('data-code')") && script.contains("data-target"));
		MatrixHtmlTest.check("it wraps the cell selection, the cell inspector above is untouched", script.contains("var codeBase=window.mdmmSelect;window.mdmmSelect=function(b){codeBase(b);codeBlock(b);};"));
		MatrixHtmlTest.check("its strings come from the bundle, in both languages", script.contains("codesTitle:'Code table'") && MatrixHtml.script(Locale.FRENCH).contains("codesTitle:'Table de codes'")
			&& MatrixHtml.script(Locale.FRENCH).contains("codesEdit:'Modifier'"));
		final String dir = System.getProperty("mdmf.jsDump");
		if (dir != null && !dir.isEmpty())
		{
			java.nio.file.Files.createDirectories(java.nio.file.Paths.get(dir));
			for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
			{
				java.nio.file.Files.write(java.nio.file.Paths.get(dir, "matrix-" + locale.getLanguage() + ".js"), MatrixHtml.script(locale).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
		}
		MatrixHtmlTest.check("a cell without a table writes an empty list", MatrixHtml.codeListsJson(null, Locale.ENGLISH).equals("[]"));
	}

	private static void aCellWithoutConfidenceStillWritesValidJson()
	{
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "addressType", "STRING", false, false, false);
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("a1", new MatrixModel.Cell(MatrixModel.ACCEPTED, "m1", "TYPE", null, null, "USER", null, null, 0, null, null));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "sap", "t1", "BUT_PARTNER", "ACCEPTED", null, "", false, null, cells);
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Adresse", java.util.Arrays.asList(attr), java.util.Arrays.asList(feed));
		final MatrixModel.Project project = new MatrixModel.Project("P", java.util.Collections.<MatrixModel.Source>emptyList(), java.util.Arrays.asList(entity));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		final String html = MatrixHtml.render(project, state);
		final java.util.regex.Matcher m = java.util.regex.Pattern.compile("data-cell=\"([^\"]*)\"").matcher(html);
		int seen = 0;
		while (m.find())
		{
			seen++;
			final String json = m.group(1).replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&amp;", "&");
			MatrixHtmlTest.check("no empty unquoted value: " + json, !json.contains("\":,") && !json.endsWith("\":}"));
			try
			{
				new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
			}
			catch (final Exception ex)
			{
				throw new AssertionError("data-cell is not valid JSON: " + json, ex);
			}
		}
		MatrixHtmlTest.check("at least one data-cell rendered", seen > 0);
	}

	/**
	 * fix/matrix-derived-key-rows (2026-09-27, the product owner on the Personnes matrix: "should firstNameKey /
	 * lastNameKey be validated? Aren't they technical artefacts automatically added?"): a FOLD_KEY matching
	 * key has no source link by design, so its row must not read "0 / 2 source links", "not provided by this
	 * source" per source and "none proposed" - it shows what it is ("Matching key of firstName"), ONE cell
	 * spanning every source ("computed at load"), and keeps its own Accept/Reject (rejecting it is a real
	 * decision: the matching plan falls back to the raw field).
	 */
	private static void derivedKeyRowRendersOneComputedCellInsteadOfNotProvidedPerSource()
	{
		final MatrixModel.Attr firstName = new MatrixModel.Attr("a1", "firstName", "STRING", false, false, false, "ACCEPTED", Integer.valueOf(95));
		final MatrixModel.Attr key = new MatrixModel.Attr("a2", "firstNameKey", "STRING", false, false, false, "PROPOSED", Integer.valueOf(90), null, "FOLD_KEY", "firstName");
		final Map<String, MatrixModel.Cell> sapCells = new LinkedHashMap<String, MatrixModel.Cell>();
		sapCells.put("a1", new MatrixModel.Cell(MatrixModel.ACCEPTED, "m1", "VORNAME", null, Integer.valueOf(95), "RULE", null, null, 0, null, null));
		final Map<String, MatrixModel.Cell> sfdcCells = new LinkedHashMap<String, MatrixModel.Cell>();
		sfdcCells.put("a1", new MatrixModel.Cell(MatrixModel.ACCEPTED, "m2", "FirstName", null, Integer.valueOf(95), "RULE", null, null, 0, null, null));
		final MatrixModel.Feed sap = new MatrixModel.Feed("f1", "sap", "t1", "BUT_PARTNER", "ACCEPTED", null, "", false, null, sapCells);
		final MatrixModel.Feed sfdc = new MatrixModel.Feed("f2", "sfdc", "t2", "Contact", "ACCEPTED", null, "", false, null, sfdcCells);
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Personne", java.util.Arrays.asList(firstName, key), java.util.Arrays.asList(sap, sfdc), "ACCEPTED",
			Integer.valueOf(100));
		final MatrixModel.Project project = new MatrixModel.Project("P", java.util.Arrays.asList(new MatrixModel.Source("sap", "SAP", "#1a73e8"),
			new MatrixModel.Source("sfdc", "Salesforce", "#00a1e0")), java.util.Arrays.asList(entity));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		final String html = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("the row says what it is: a matching key of firstName", html.contains("Matching key of firstName"));
		MatrixHtmlTest.check("one merged cell spans the two source columns", html.contains("class=\"mdmmCell link derived todo\" style=\"grid-column:span 2\""));
		MatrixHtmlTest.check("the merged cell reads 'computed at load'", html.contains("computed at load"));
		MatrixHtmlTest.check("and says where it comes from", html.contains("from firstName, accents stripped, upper case"));
		MatrixHtmlTest.check("no 'not provided by this source' anywhere: firstName is covered by both feeds, the key has no source link", !html.contains("not provided by this source"));
		MatrixHtmlTest.check("no '0 / 2 source links' on the key row", !html.contains("0 / 2 source links"));
		MatrixHtmlTest.check("the ordinary row keeps its coverage counter", html.contains("2 / 2 source links"));
		MatrixHtmlTest.check("the key row's rules column reads 'not applicable'; 'none proposed' appears once, for the ordinary firstName row only",
			html.contains("not applicable") && html.split("none proposed", -1).length == 2);
		MatrixHtmlTest.check("the key row keeps its own Accept/Reject (it is PROPOSED)", html.contains("mdmmAcceptAttribute(&#39;a2&#39;)") || html.contains("mdmmAcceptAttribute('a2')"));
		MatrixHtmlTest.check("the inspector JSON explains it as a structural cell", html.contains("&quot;isStruct&quot;:true") && html.contains("Matching key: a technical field"));
		MatrixHtmlTest.check("the script tones DERIVED like KEY/ENGINE", MatrixHtml.script(java.util.Locale.ENGLISH).contains("s==='DERIVED'"));

		// same row once ACCEPTED: no Accept/Reject any more, still one merged cell, not marked "todo"
		final MatrixModel.Attr accepted = new MatrixModel.Attr("a2", "firstNameKey", "STRING", false, false, false, "ACCEPTED", Integer.valueOf(90), null, "FOLD_KEY", "firstName");
		final MatrixModel.Entity entity2 = new MatrixModel.Entity("e1", "Personne", java.util.Arrays.asList(firstName, accepted), java.util.Arrays.asList(sap, sfdc), "ACCEPTED",
			Integer.valueOf(100));
		final String html2 = MatrixHtml.render(new MatrixModel.Project("P", project.sources, java.util.Arrays.asList(entity2)), state);
		MatrixHtmlTest.check("an accepted key row has no decision buttons", !html2.contains("mdmmAcceptAttribute("));
		MatrixHtmlTest.check("and its merged cell is not a to-do", html2.contains("class=\"mdmmCell link derived\" style=\"grid-column:span 2\""));
	}

	private static String renderPersVol9(final boolean pOrdinaryAccepted, final boolean pKeysAccepted, final String pMode, final String pSelected)
	{
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		state.queueMode = pMode;
		state.selectedCellKey = pSelected.isEmpty() ? null : pSelected;
		return MatrixHtml.render(MatrixQueueFixtureTest.persVol9Like(pOrdinaryAccepted, pKeysAccepted), state);
	}

	/** Matrix review queue lot: plain words, a clickable count, the bulk button with its bar - never "unflagged" or "in bulk". */
	private static void footerSaysWhoNeedsYouAndOffersTheListInPlainWords()
	{
		final String html = MatrixHtmlTest.renderPersVol9(true, false, "", "");
		MatrixHtmlTest.check("the footer's count is a button that opens the list, worded 'items need you'",
			html.contains("<button type=\"button\" class=\"mdmmFootCount\" aria-expanded=\"false\" onclick=\"mdmmQueueToggle()\">2 items need you</button>"));
		MatrixHtmlTest.check("what the bulk still takes is said in one sentence", html.contains("The others go with the button."));
		MatrixHtmlTest.check("the bulk button names its count and its bar", html.contains("Accept the 3 proposals at 60 % or above"));
		MatrixHtmlTest.check("no jargon left: never 'unflagged' nor 'in bulk'", !html.contains("unflagged") && !html.contains("in bulk") && !html.contains("Accept the rest together"));
		final String done = MatrixHtmlTest.renderPersVol9(true, true, "", "");
		MatrixHtmlTest.check("with the keys accepted, nothing goes with the button and the hint is gone", done.contains(">2 items need you</button>")
			&& !done.contains("The others go with the button.") && done.contains("Nothing left for this button"));
		final String untouched = MatrixHtmlTest.renderPersVol9(false, false, "", "");
		MatrixHtmlTest.check("before any click, most items go with the button", untouched.contains("items need you") && untouched.contains("The others go with the button."));
		final MatrixModel.Attr accepted = new MatrixModel.Attr("a1", "nom", "STRING", true, false, false, "PROPOSED", Integer.valueOf(90));
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "P", java.util.Arrays.asList(accepted), java.util.Collections.<MatrixModel.Feed>emptyList(), "ACCEPTED", Integer.valueOf(100));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		final String allWithButton = MatrixHtml.render(new MatrixModel.Project("P", null, java.util.Arrays.asList(entity)), state);
		MatrixHtmlTest.check("when everything left goes with the button, the count says so and still opens the list",
			allWithButton.contains("1 item left, it goes with the button</button>") && allWithButton.contains("mdmmQueueAllOn"));
	}

	private static void leftToDecidePanelListsEveryItemWithKindReasonAndItsOwnButtons()
	{
		final String html = MatrixHtmlTest.renderPersVol9(true, false, "open", "");
		MatrixHtmlTest.check("the panel is open when the state says so", html.contains("<div id=\"mdmmQueue\" class=\"mdmmQueue mdmmQueueOpen\">"));
		for (final String key : new String[] { "attr|a5", "attr|a6", "attr|a7", "attr|a8", "guard|g5" })
		{
			MatrixHtmlTest.check("every counted item is listed: " + key, html.contains("class=\"mdmmQItem") && html.contains("data-key=\"" + key + "\" data-entity="));
		}
		// integration 2026-09-30: the function of a computed field still proposed is decided with the field - no line of its own
		MatrixHtmlTest.check("the computed field's derivation has no line of its own", !html.contains("data-key=\"guard|g3\" data-entity="));
		MatrixHtmlTest.check("grouped by business object, with its count", html.contains("Personne <span class=\"mdmmChip warn\">4</span>")
			&& html.contains("Adresse <span class=\"mdmmChip warn\">1</span>"));
		MatrixHtmlTest.check("kind chips in plain words", html.contains(">Matching key</span>") && html.contains(">Computed field</span>") && html.contains(">Check</span>"));
		MatrixHtmlTest.check("why for a check under the bar", html.contains("confidence 15 %, under the 60 % bar"));
		MatrixHtmlTest.check("why for a computed field", html.contains("computed field: check what it computes before accepting"));
		MatrixHtmlTest.check("why for what the button takes", html.contains("goes with the Accept button"));
		MatrixHtmlTest.check("the items the button takes start hidden (class bulk), the others do not",
			html.contains("<div class=\"mdmmQItem bulk\" data-key=\"attr|a5\"") && html.contains("<div class=\"mdmmQItem\" data-key=\"attr|a8\""));
		MatrixHtmlTest.check("the list has a way to show them", html.contains("Also show the 3 that go with the button"));
		// a connector service: Set aside + Reject, never Accept - the wizard card's rule; since UX lot U6 it is an optional suggestion, grouped per field,
		// outside the list's items (class mdmmSItem, never mdmmQItem) and never counted
		MatrixHtmlTest.check("a look-up suggestion is not a list item", !html.contains("data-key=\"guard|g1\" data-entity=\"e1\" data-item=\"{&quot;key&quot;:&quot;guard|g1&quot;")
			|| html.indexOf("mdmmSItem\" data-key=\"guard|g1\"") >= 0);
		final int g1 = html.indexOf("mdmmSItem\" data-key=\"guard|g1\"");
		MatrixHtmlTest.check("it sits in the optional section, as a line of its own", g1 > 0 && !html.contains("mdmmQItem\" data-key=\"guard|g1\"") && !html.contains("mdmmQItem bulk\" data-key=\"guard|g1\""));
		final String g1Line = html.substring(g1, html.indexOf("</div>", g1));
		MatrixHtmlTest.check("an external service offers Set aside and Reject, never Accept", g1Line.contains(",'SETASIDE')") && g1Line.contains(",'REJECT')") && !g1Line.contains(",'ACCEPT')"));
		final int g5 = html.indexOf("data-key=\"guard|g5\" data-entity=");
		final String g5Line = html.substring(g5, html.indexOf("</div>", g5));
		MatrixHtmlTest.check("a script check offers Accept and Reject", g5Line.contains(",'ACCEPT')") && g5Line.contains(",'REJECT')") && !g5Line.contains("SETASIDE"));
		MatrixHtmlTest.check("a click on the line goes to the item", html.contains("onclick=\"mdmmQueueGo(this)\""));
		MatrixHtmlTest.check("the line's buttons dispatch through mdmmItemDecide (the script maps them to the existing submitAction names)",
			html.contains("onclick=\"mdmmItemDecide(event,'attr|a8','ACCEPT')\""));
		// read-only: the buttons are disabled, never hidden
		final MatrixHtml.State readOnly = new MatrixHtml.State();
		readOnly.activeEntityId = "e1";
		readOnly.readOnly = true;
		MatrixHtmlTest.check("read only disables the line buttons", MatrixHtml.render(MatrixQueueFixtureTest.persVol9Like(true, false), readOnly).contains(
			"<button type=\"button\" class=\"mdmmBtn mdmmBtnXs mdmmBtnPrimary\" disabled=\"disabled\" onclick=\"mdmmItemDecide(event,'attr|a8','ACCEPT')\">"));
	}

	private static void tabsCarryTheirCounterAndTheFirstItemToDecideIsSelected()
	{
		final String html = MatrixHtmlTest.renderPersVol9(true, false, "", "");
		MatrixHtmlTest.check("each tab carries the number left in ITS object", html.contains(">Personne <span class=\"mdmmTabCount\"") && html.contains(">4</span></button>")
			&& html.contains(">Adresse <span class=\"mdmmTabCount\"") && html.contains(">1</span></button>"));
		MatrixHtmlTest.check("the first thing to decide is selected on first load, not the first data cell",
			html.contains("<input type=\"hidden\" id=\"mdmmSelected\" name=\"mdmmSelected\" value=\"attr|a5\"/>"));
		final String kept = MatrixHtmlTest.renderPersVol9(true, false, "", "attr|a7");
		MatrixHtmlTest.check("a selection the object owns is kept", kept.contains("name=\"mdmmSelected\" value=\"attr|a7\""));
		final String stale = MatrixHtmlTest.renderPersVol9(true, false, "", "guard|g4");
		MatrixHtmlTest.check("a selection left over from another object's tab is replaced by this object's first item", stale.contains("name=\"mdmmSelected\" value=\"attr|a5\""));
		final String decided = MatrixHtmlTest.renderPersVol9(true, true, "", "");
		MatrixHtmlTest.check("Adresse still counts its check once the keys are accepted", decided.contains(">Adresse <span class=\"mdmmTabCount\""));
		final MatrixModel.Attr done = new MatrixModel.Attr("a1", "nom", "STRING", true, false, false, "ACCEPTED", Integer.valueOf(90));
		final MatrixModel.Entity clean = new MatrixModel.Entity("e1", "Clean", java.util.Arrays.asList(done), java.util.Collections.<MatrixModel.Feed>emptyList(), "ACCEPTED", Integer.valueOf(100));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		MatrixHtmlTest.check("a counter is hidden at 0", !MatrixHtml.render(new MatrixModel.Project("P", null, java.util.Arrays.asList(clean)), state).contains("class=\"mdmmTabCount\" title"));
	}

	private static void onlyWhatIsLeftHidesRowsWithNothingLeft()
	{
		final String html = MatrixHtmlTest.renderPersVol9(true, false, "", "");
		MatrixHtmlTest.check("a row with nothing left is marked done (lastName)", html.contains("<div class=\"mdmmRow mdmmRowDone\"><div class=\"mdmmAttr\" data-key=\"attr|a2\">"));
		MatrixHtmlTest.check("a row whose only leftover is an optional look-up is done too (firstName)", html.contains("<div class=\"mdmmRow mdmmRowDone\"><div class=\"mdmmAttr\" data-key=\"attr|a1\">"));
		MatrixHtmlTest.check("so does a computed row that waits (phoneE164)", html.contains("<div class=\"mdmmRow\"><div class=\"mdmmAttr\" data-key=\"attr|a8\">"));
		MatrixHtmlTest.check("the filter HIDES a done row, it no longer only dims cells", html.contains("#mdmm.mdmmOnlyTodo .mdmmRow.mdmmRowDone{display:none}"));
		MatrixHtmlTest.check("the wrapper is display:contents so the grid is untouched", html.contains("#mdmm .mdmmRow{display:contents}"));
		int rows = 0;
		int done = 0;
		for (int at = html.indexOf("<div class=\"mdmmRow"); at >= 0; at = html.indexOf("<div class=\"mdmmRow", at + 10))
		{
			rows++;
			if (html.startsWith("<div class=\"mdmmRow mdmmRowDone\">", at))
			{
				done++;
			}
		}
		MatrixHtmlTest.check("Personne has 9 rows, 5 of them done (source key, firstName, lastName, email, phone)", rows == 9 && done == 5);
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		state.onlyTodo = true;
		MatrixHtmlTest.check("the toggle state is on the root", MatrixHtml.render(MatrixQueueFixtureTest.persVol9Like(true, false), state).contains("class=\"mdmmRoot mdmmOnlyTodo\""));
	}

	private static void inspectorIsPinnedInADockAboveTheFooterAndTheScriptWalksTheList()
	{
		final String html = MatrixHtmlTest.renderPersVol9(true, false, "", "");
		final int dock = html.indexOf("<div class=\"mdmmDock\">");
		final int bar = html.indexOf("<div class=\"mdmmQBar\">");
		final int inspector = html.indexOf("<div id=\"mdmmInspector\"");
		final int footer = html.indexOf("<div class=\"mdmmFooter\">");
		MatrixHtmlTest.check("one dock holds the list panel, the walk bar, the inspector and the footer, in that order",
			dock > 0 && html.indexOf("<div id=\"mdmmQueue\"") > dock && bar > html.indexOf("<div id=\"mdmmQueue\"") && inspector > bar && footer > inspector);
		MatrixHtmlTest.check("the dock is sticky at the bottom of the pane", html.contains("#mdmm .mdmmDock{position:sticky;bottom:60px;"));
		MatrixHtmlTest.check("the list panel opens above it, inside it", html.contains("#mdmm .mdmmQueue{display:none;position:absolute;left:0;right:0;bottom:100%;"));
		MatrixHtmlTest.check("the walk bar has previous / next and a position", html.contains("id=\"mdmmQPrev\"") && html.contains("id=\"mdmmQNext\"") && html.contains("id=\"mdmmQPos\">5 to decide<"));
		final String script = MatrixHtml.script(Locale.ENGLISH);
		for (final String fn : new String[] { "window.mdmmQueueGo=", "window.mdmmQueueStep=", "window.mdmmQueueToggle=", "window.mdmmQueueAll=", "window.mdmmItemDecide=", "window.mdmmShowItem=",
			"function selectKey(", "function refreshBar(",
			// integration 2026-09-30: a computed field's item opens its computed cell (what it computes), and that cell keeps the walk's position
			"keySel('derived|'+key.substring(5))", "function qkey(" })
		{
			MatrixHtmlTest.check("the script defines " + fn, script.contains(fn));
		}
		MatrixHtmlTest.check("a tab switch carries its target through the hidden fields BEFORE the submit (setupObjectContext re-reads them after the event)",
			script.contains("byId('mdmmEntity').value=it.entityId;byId('mdmmSelected').value=it.key;submitAction('GOTO_ENTITY'"));
		MatrixHtmlTest.check("scrolling goes through scrollIntoView, never window.scrollTo", script.contains("scrollIntoView") && !script.contains("window.scrollTo"));
		MatrixHtmlTest.check("every decision posts through the existing submitAction names",
			script.contains("'ACCEPT_ENTITY'") && script.contains("'ACCEPT_ATTRIBUTE'") && script.contains("'ACCEPT_FEED'") && script.contains("'ACCEPT_MAPPING'")
				&& script.contains("'ACCEPT_ATTR_THEN_MAPPING'") && script.contains("'ACCEPT_GUARD'") && script.contains("'SET_ASIDE_GUARD'"));
		MatrixHtmlTest.check("the guard inspector offers Set aside for a connector service", script.contains("g.nature==='EXTERNAL_SERVICE'?") && script.contains("'SETASIDE\\')"));
		MatrixHtmlTest.check("the new inspector text is escaped like the rest", script.contains("+e(it.title)+") && script.contains("+e(it.why.charAt(0).toUpperCase()+it.why.slice(1))+")
			&& script.contains("eAttr(it.key)") && !script.contains("'+it.title+'") && !script.contains("+it.why+"));
		MatrixHtmlTest.check("the new strings come from the bundle", script.contains("setAside:'Set aside'") && script.contains("queuePos:'{0} of {1}'"));
		MatrixHtmlTest.check("the French script carries the French strings", MatrixHtml.script(Locale.FRENCH).contains("setAside:'Mettre de c\u00f4t\u00e9'"));
	}

	private static void panelStateAndScrollFlagSurviveThroughHiddenFieldsAndDataIsEscaped()
	{
		final String open = MatrixHtmlTest.renderPersVol9(true, false, "all", "");
		MatrixHtmlTest.check("the panel mode is a hidden field, re-read by setupObjectContext", open.contains("<input type=\"hidden\" id=\"mdmmQueueMode\" name=\"mdmmQueue\" value=\"all\"/>"));
		MatrixHtmlTest.check("'all' checks the box and shows what the button takes", open.contains("checked=\"checked\"") && open.contains("mdmmQueueOpen"));
		final String closed = MatrixHtmlTest.renderPersVol9(true, false, "", "");
		MatrixHtmlTest.check("closed stays closed", closed.contains("name=\"mdmmQueue\" value=\"\"/>") && !closed.contains("mdmmQueue mdmmQueueOpen"));
		MatrixHtmlTest.check("a first load does not scroll", closed.contains("data-scroll=\"0\""));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		state.scrollToSelection = true;
		MatrixHtmlTest.check("a render after a postback scrolls to the selection", MatrixHtml.render(MatrixQueueFixtureTest.persVol9Like(true, false), state).contains("data-scroll=\"1\""));
		// data dropped in the list is escaped (security review 2026-09-24)
		final String evil = "<img src=x onerror=alert(1)>\"'";
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", evil, "STRING", false, false, false, "PROPOSED", Integer.valueOf(40));
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", evil, java.util.Arrays.asList(attr), java.util.Collections.<MatrixModel.Feed>emptyList(), "ACCEPTED", Integer.valueOf(100));
		final MatrixHtml.State evilState = new MatrixHtml.State();
		evilState.activeEntityId = "e1";
		evilState.queueMode = "open";
		final String html = MatrixHtml.render(new MatrixModel.Project("P", null, java.util.Arrays.asList(entity)), evilState);
		MatrixHtmlTest.check("a hostile name never appears raw in the page", !html.contains("<img src=x onerror=alert(1)>"));
		MatrixHtmlTest.check("it is escaped in the list, its group title and its data", html.contains("&lt;img src=x onerror=alert(1)&gt;&quot;&#39;"));
		MatrixHtmlTest.check("the data-item attribute stays one attribute", !html.contains("data-item=\"{\"key\":"));
	}

	/**
	 * fix/designer-all-sources (2026-10-19): the Matrix's Designer switch used to open the object's FIRST source
	 * whatever source was highlighted; it now opens the highlighted one, and carries every source's link so the
	 * script can follow a new highlight without a round trip.
	 */
	private static void designerSwitchFollowsTheHighlightedSource()
	{
		final MatrixModel.Feed crm = new MatrixModel.Feed("f1", "crm", "t1", "CONTACT", "ACCEPTED", null, "", false, null, null, null, "", "/designer?feed=f1");
		final MatrixModel.Feed ecom = new MatrixModel.Feed("f3", "ecom", "t3", "CUSTOMER", "ACCEPTED", null, "", false, null, null, null, "", "/designer?feed=f3");
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Personne", java.util.Collections.<MatrixModel.Attr>emptyList(),
			java.util.Arrays.asList(crm, ecom), "ACCEPTED", Integer.valueOf(100));
		final MatrixModel.Project project = new MatrixModel.Project("P", java.util.Arrays.asList(new MatrixModel.Source("crm", "CRM", "#1a73e8"),
			new MatrixModel.Source("ecom", "ECOM", "#e8710a")), java.util.Arrays.asList(entity));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		final String plain = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("no highlight: the switch opens the first source", plain.contains("id=\"mdmmDesignerSwitch\" class=\"mdmf-matrix-designer-link\" target=\"_top\" href=\"/designer?feed=f1\""));
		MatrixHtmlTest.check("the switch carries every source's designer link", plain.contains("data-by-source=\"{&quot;crm&quot;:&quot;/designer?feed=f1&quot;,&quot;ecom&quot;:&quot;/designer?feed=f3&quot;}\""));
		state.focusSourceId = "ecom";
		final String focused = MatrixHtml.render(project, state);
		MatrixHtmlTest.check("ECOM highlighted: the switch opens ECOM", focused.contains("id=\"mdmmDesignerSwitch\" class=\"mdmf-matrix-designer-link\" target=\"_top\" href=\"/designer?feed=f3\""));
		MatrixHtmlTest.check("the focus script re-points the switch", MatrixHtml.script(java.util.Locale.ENGLISH).contains("byId('mdmmDesignerSwitch')"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
