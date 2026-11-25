package com.on.ebx.factory.lineage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.factory.review.ProposalWording;

/**
 * Renders the "Lineage de l'enregistrement" / "Record lineage" screen (PLAN.md lot 4, mockup screen 5,
 * {@code docs/mockups/mdm-factory.html} &sect;{@code scr-lineage}) as an HTML fragment meant to be dropped
 * inside a {@code UserServicePane}: no {@code <html>}/{@code <head>}, the caller (the EBX-bound user
 * service) links the stylesheets - {@code factory/factory.css} (the shared tokens/components), {@code
 * factory/review.css} ({@code .mdmf-btn} - this screen reuses the same buttons, no button style of its
 * own) and this screen's own {@code factory/lineage.css} (the journey nodes/arrows, the field-differs
 * highlight) - and wraps this in the page chrome.
 * <p>
 * <b>Pure, like {@code review.ReviewPage}</b>: no EBX class anywhere in this file - built and unit-tested
 * (see {@code RecordLineagePageTest}) without an EBX runtime. Unlike {@link review.ReviewPage}, whose
 * {@code Input} is a typed value object, this page's main content is the {@link JsonNode} that {@code
 * lineage.RecordLineage#assemble}/{@code ebx.RecordLineageReader#read} already produce - rendering directly
 * from that JSON (rather than re-modelling it as a second, parallel Java hierarchy) keeps the redaction
 * rules that {@code RecordLineage#assemble} enforces (a restricted source/field/record field never carries
 * a value, {@code differs} is {@code null} rather than a confident {@code false} when a contribution is
 * hidden) as the ONLY place those rules are decided - this class only walks the JSON and shows exactly what
 * is there, never re-deriving or second-guessing what should be visible. Every value is HTML-escaped;
 * nothing here trusts that a caller already escaped anything.
 * <p>
 * <b>Where this deliberately does not match the mockup's exact table shape</b>: mockup screen 5's
 * "Champ par champ" table has a "Valeur retenue" / "Source retenue" / "Regle de survivance" shape - a
 * post-merge SURVIVORSHIP answer ({@code MergeValueLineageCompact}) that {@code
 * ebx.FieldMergeLineageSource}/{@code docs/RECORD-LINEAGE.md} document as NOT read this lot (an
 * unverified repeatable-group API, left as a named seam). This page shows what the read side actually
 * has instead: every contributing source's own raw/staged value and cleaning steps, and whether they
 * agree - never a fabricated "retained value"/"survivorship rule" the read side never computed. The
 * mockup's own two-source funnel layout (a hand-placed empty grid cell so a THIRD row can visually merge
 * two rows into one) is likewise not reproduced literally: a real record can have any number of
 * contributing sources, so {@link #renderJourney} lays out one row per source followed by one merge/golden
 * row (or the merge-absent notice), rather than a layout that only works for exactly two.
 */
public final class RecordLineagePage
{
	private RecordLineagePage()
	{
	}

	// ------------------------------------------------------------------------------------------------------- input

	public static final class ProjectOption
	{
		public final String code;
		public final String label;

		public ProjectOption(final String pCode, final String pLabel)
		{
			this.code = pCode;
			this.label = pLabel;
		}
	}

	/** One promoted master entity of the selected project (see {@code ebx.RecordLineageReader}'s own "entity is not a promoted master entity" 404 - only these names can ever resolve). */
	public static final class EntityOption
	{
		public final String name;
		public final String label;

		public EntityOption(final String pName, final String pLabel)
		{
			this.name = pName;
			this.label = pLabel;
		}
	}

	public static final class Input
	{
		public final Locale locale;
		/** Every project of the factory, for the selector shown when no project is resolved yet. */
		public final List<ProjectOption> projects;
		/** Null while no project is resolved yet: {@link #render} then only shows the selector. */
		public final String selectedProjectCode;
		public final String selectedProjectLabel;
		/** Every promoted master entity of {@link #selectedProjectCode}; empty (and irrelevant) while it is null. */
		public final List<EntityOption> entities;
		public final String selectedEntity;
		/** The key box's own current text, re-shown after a postback (success or failure) - never re-parsed here. */
		public final String keyText;
		public final String notice;
		/** {@code "ok"} or {@code "warn"}. */
		public final String noticeKind;
		/**
		 * The last successful {@code RecordLineage.assemble}/{@code RecordLineageReader.read} answer, or
		 * {@code null} when nothing has been looked up yet, or the last lookup failed (see {@link #notice}
		 * for why - an unknown record, a malformed key, ...).
		 */
		public final JsonNode lineage;
		/**
		 * {@code "ACCEPT"}/{@code "REJECT"}, or null when no suspect decision is awaiting confirmation - the
		 * "decide on a suspect" lot's own two-step confirmation (task brief, 2026-09-22: "a second click on
		 * 'Confirm?'/'Really?'"), the same server-tracked pending-state shape {@code
		 * review.ProposalReviewUserService}'s own bulk-accept/reject confirmation already uses (read in full
		 * before writing this - never a client-side {@code window.confirm()}). Meaningless unless {@link
		 * #pendingSuspectKey} is also set.
		 */
		public final String pendingSuspectAction;
		/** The suspect's own formatted master key the pending action above applies to - null when {@link #pendingSuspectAction} is. */
		public final String pendingSuspectKey;
		/**
		 * The screen was opened ON A RECORD ({@code ebx.ui.RecordLineageOnRecordUserService}, coordinator
		 * review, 2026-09-23) - project/entity/key are already fixed by the record being viewed, so {@link
		 * #renderHeader} skips the whole lookup card (title, lead sentence, project/entity/key form, "Look up"
		 * button) entirely: the result cards below already name the record ("BPCE &middot; 322", "Company
		 * &middot; 322 &middot; 2 source(s)"), and re-showing a form that would only ever restate the same
		 * three values is confusing, not useful, on this entry point. {@code false} for every other caller
		 * ({@code ebx.ui.RecordLineageUserService}, the {@code OnTableView} entry, where a steward genuinely
		 * picks project/entity/key) - the default on every existing constructor/fixture, so this screen's own
		 * output is unaffected by this field's addition.
		 * <p>
		 * <b>Never hides the notice/warning or the result cards</b>: both are rendered by {@link #render}
		 * independently of {@link #renderHeader} (the notice before it, the graphic/comparison/cross-reference
		 * panels after it), so a failed lookup from a record still shows its own message, and the hidden
		 * {@code mdmfProjectCode}/{@code mdmfEntity}/{@code mdmfKey} inputs {@link #renderHiddenFields} writes
		 * are likewise untouched by this flag - already rendered unconditionally, before {@link #renderHeader}
		 * is even called, from {@link #selectedProjectCode}/{@link #selectedEntity}/{@link #keyText}, which the
		 * record-bound caller already resolves before the first render - so the suspect decide/accept/reject
		 * round trip still posts the right project/entity/key even with the visible form gone.
		 */
		public final boolean recordBound;

		public Input(final Locale pLocale, final List<ProjectOption> pProjects, final String pSelectedProjectCode, final String pSelectedProjectLabel,
			final List<EntityOption> pEntities, final String pSelectedEntity, final String pKeyText, final String pNotice, final String pNoticeKind,
			final JsonNode pLineage)
		{
			this(pLocale, pProjects, pSelectedProjectCode, pSelectedProjectLabel, pEntities, pSelectedEntity, pKeyText, pNotice, pNoticeKind, pLineage, null, null);
		}

		/** Same as the 10-argument constructor, with {@link #pendingSuspectAction}/{@link #pendingSuspectKey} - kept as a separate overload so every existing caller/fixture is unaffected. */
		public Input(final Locale pLocale, final List<ProjectOption> pProjects, final String pSelectedProjectCode, final String pSelectedProjectLabel,
			final List<EntityOption> pEntities, final String pSelectedEntity, final String pKeyText, final String pNotice, final String pNoticeKind,
			final JsonNode pLineage, final String pPendingSuspectAction, final String pPendingSuspectKey)
		{
			this(pLocale, pProjects, pSelectedProjectCode, pSelectedProjectLabel, pEntities, pSelectedEntity, pKeyText, pNotice, pNoticeKind, pLineage,
				pPendingSuspectAction, pPendingSuspectKey, false);
		}

		/** Same as the 12-argument constructor, with {@link #recordBound} - kept as a separate overload so every existing caller/fixture is unaffected (defaults to {@code false}, the original "lookup form shown" behaviour). */
		public Input(final Locale pLocale, final List<ProjectOption> pProjects, final String pSelectedProjectCode, final String pSelectedProjectLabel,
			final List<EntityOption> pEntities, final String pSelectedEntity, final String pKeyText, final String pNotice, final String pNoticeKind,
			final JsonNode pLineage, final String pPendingSuspectAction, final String pPendingSuspectKey, final boolean pRecordBound)
		{
			this.locale = pLocale;
			this.projects = pProjects;
			this.selectedProjectCode = pSelectedProjectCode;
			this.selectedProjectLabel = pSelectedProjectLabel;
			this.entities = pEntities;
			this.selectedEntity = pSelectedEntity;
			this.keyText = pKeyText;
			this.notice = pNotice;
			this.noticeKind = pNoticeKind;
			this.lineage = pLineage;
			this.pendingSuspectAction = pPendingSuspectAction;
			this.pendingSuspectKey = pPendingSuspectKey;
			this.recordBound = pRecordBound;
		}
	}

	// ------------------------------------------------------------------------------------------------------ render

	public static String render(final Input pInput)
	{
		final Locale locale = pInput.locale == null ? Locale.ENGLISH : pInput.locale;
		final StringBuilder out = new StringBuilder();
		out.append("<div class=\"mdmf mdmf-lineage\">");
		RecordLineagePage.renderHiddenFields(out, pInput);
		if (pInput.notice != null)
		{
			out.append("<div class=\"mdmf-notice mdmf-notice-").append("warn".equals(pInput.noticeKind) ? "warn" : "ok").append("\">")
				.append(RecordLineagePage.esc(pInput.notice)).append("</div>");
		}
		RecordLineagePage.renderHeader(out, pInput, locale);
		if (pInput.lineage != null)
		{
			// a COMPACT header only when the graphic renders (coordinator review, 2026-09-22: the full
			// field/value table duplicates "champ par champ" - part 2 already shows every field, with its
			// provenance, which this table never did); the full table moves into the <noscript> fallback below.
			RecordLineagePage.renderRecordHeaderCompact(out, pInput.lineage, locale);
			RecordLineagePage.renderLineageStatus(out, pInput.lineage, locale);
			RecordLineagePage.renderGraphicContainer(out, pInput.lineage, locale);
			// "Compare with suspects" panel (task brief, 2026-09-22, "decide on a suspect" lot) - a native
			// <details> disclosure (opened by a click on its own <summary>, keyboard-reachable with no
			// JavaScript at all - see method Javadoc) rather than a custom show/hide toggle; the ring/suspect-
			// lane click handlers in lineage-graphic.js additionally OPEN it programmatically, see that
			// file's own comment. Renders nothing when there is no suspect to compare against.
			RecordLineagePage.renderComparisonPanel(out, pInput.lineage, locale, pInput.pendingSuspectAction, pInput.pendingSuspectKey);
			// "Cross-references" table (the product owner's request, 2026-09-23) - after the graphic and the existing
			// panels above, per the task brief; OUTSIDE the <noscript> block below (plain HTML, needs no
			// script to render or read, so it is shown whichever way the graphic itself renders).
			RecordLineagePage.renderCrossReferencesPanel(out, pInput.lineage, locale);
			// server-rendered fallback (task brief: "works without JavaScript ... the v1 server-rendered
			// content is an acceptable fallback - keep it rather than deleting it") - a browser with
			// JavaScript disabled shows exactly this instead of the graphic screen above; one with JavaScript
			// enabled never renders this block's content at all (<noscript>), so the two never appear together.
			out.append("<noscript><p class=\"mdmf-hint\">").append(RecordLineagePage.esc(RecordLineagePage.m(locale, "lineage.graphic.js.required"))).append("</p>");
			RecordLineagePage.renderRecordPanel(out, pInput.lineage, locale);
			RecordLineagePage.renderJourney(out, pInput.lineage, locale);
			RecordLineagePage.renderFieldTable(out, pInput.lineage, locale);
			out.append("</noscript>");
		}
		else
		{
			RecordLineagePage.renderEmptyHint(out, locale);
		}
		out.append("</div>");
		return out.toString();
	}

	/**
	 * Companion to {@link #render} - the client-side script text (data assignment + the {@code
	 * lineage-graphic.js} resource) for the caller ({@code ebx.ui.RecordLineageUserService}) to hand to {@code
	 * UserServicePaneWriter#addJS_cr} (CLAUDE.md's own "JavaScript goes through addJS, not a hand-written
	 * &lt;script&gt; tag" rule - so this class, despite being pure/EBX-free, never writes a literal {@code
	 * <script>} tag itself; {@link #render}'s own returned HTML only ever has the inert container the script
	 * below fills in). Null when {@code pLineage} is null (nothing to draw yet) - the caller must skip the
	 * {@code addJS_cr} call entirely in that case, same guard as {@link #render}'s own {@code
	 * renderGraphicContainer} call.
	 * <p>
	 * <b>Embedding is XSS-safe by construction, like the catalog module's own {@code LineageJourneyPane}</b>
	 * (same technique, reused - see this module's report for the exact file/line): {@code <}, {@code >} and
	 * {@code &} are escaped to their {@code \\u00XX} forms so a value/key containing {@code "&gt;&lt;script&gt;}
	 * can never break out of the {@code var data = ...;} JavaScript statement, and U+2028/U+2029 (valid JSON
	 * whitespace that is an illegal, statement-terminating line break inside a plain JS string) are escaped
	 * the same way. The script itself only ever reads {@code data} via {@code textContent}/{@code
	 * setAttribute} (see {@code lineage-graphic.js}) - never {@code innerHTML} with a data-derived string.
	 */
	public static String graphicScript(final JsonNode pLineage, final Locale pLocale)
	{
		if (pLineage == null)
		{
			return null;
		}
		final Locale locale = pLocale == null ? Locale.ENGLISH : pLocale;
		// security review 2026-09-24, finding I2: shared with ebx.ui.FeedDesignerUserService now, instead of
		// each keeping its own copy of the same escaping chain (com.on.ebx.factory.ebx.ui.FactoryHtml#jsonForScript).
		final String json = com.on.ebx.factory.ebx.ui.FactoryHtml.jsonForScript(RecordLineageGraphic.build(pLineage, locale).toString());
		return "(function(){var root=document.getElementById('mdmfLineageGraphicRoot');if(!root)return;var data=" + json + ";" + RecordLineagePage.graphicJs() + "})();";
	}

	private static volatile String graphicJsCache;

	/** Loads {@code lineage-graphic.js} once (same pattern as the catalog module's {@code LineageJourneyPane#script()}) - a plain classpath resource read, no EBX type involved, so this stays callable from a pure class. */
	private static String graphicJs()
	{
		String cached = RecordLineagePage.graphicJsCache;
		if (cached != null)
		{
			return cached;
		}
		final java.io.InputStream in = RecordLineagePage.class.getResourceAsStream("lineage-graphic.js");
		if (in == null)
		{
			throw new IllegalStateException("Missing lineage-graphic.js resource next to RecordLineagePage");
		}
		try
		{
			final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			final byte[] buffer = new byte[4096];
			int n;
			while ((n = in.read(buffer)) != -1)
			{
				bytes.write(buffer, 0, n);
			}
			cached = new String(bytes.toByteArray(), "UTF-8");
			RecordLineagePage.graphicJsCache = cached;
			return cached;
		}
		catch (final java.io.IOException ex)
		{
			throw new IllegalStateException("Cannot load lineage-graphic.js", ex);
		}
		finally
		{
			try
			{
				in.close();
			}
			catch (final java.io.IOException ex)
			{
				// nothing more to do
			}
		}
	}

	// ------------------------------------------------------------------------------------------------- graphic screen

	/**
	 * The inert container the client script ({@link #graphicScript}) fills in with SVG - three panels
	 * ("parcours"/"champ par champ"/"frise", the validated mockup's own numbering and section titles) plus a
	 * shared detail panel, each carrying its own "data already readable" / "after the first golden record"
	 * badge (task brief) computed server-side from whether {@code merge.present} - never left for the script
	 * to guess, since the script only draws what is already known to be drawable.
	 */
	private static void renderGraphicContainer(final StringBuilder pOut, final JsonNode pLineage, final Locale pLocale)
	{
		final boolean mergePresent = pLineage.path("merge").path("present").asBoolean(false);
		pOut.append("<div id=\"mdmfLineageGraphicRoot\" class=\"mdmf-lineage-graphic\">");

		pOut.append("<div class=\"mdmf-panel\"><h3 class=\"mdmf-subtitle\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.parcours.title")))
			.append(" <span class=\"mdmf-chip mdmf-chip-real\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.badge.readable")))
			.append("</span> <span class=\"mdmf-chip mdmf-chip-target\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.badge.afterGolden"))).append("</span></h3><p class=\"mdmf-hint\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.parcours.lead"))).append("</p>")
			.append("<div data-mdmf-legend class=\"mdmf-legend\"></div>")
			.append("<div class=\"mdmf-parcours-scroll\"><div data-mdmf-parcours class=\"mdmf-parcours-canvas\" role=\"img\" aria-label=\"")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.parcours.title"))).append("\"></div></div>")
			.append("<div data-mdmf-more-sources class=\"mdmf-more-sources\" hidden=\"hidden\"></div></div>");

		pOut.append("<div class=\"mdmf-panel\"><h3 class=\"mdmf-subtitle\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.fields.title")))
			.append(" <span class=\"mdmf-chip mdmf-chip-real\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.badge.readable")))
			.append("</span> <span class=\"mdmf-chip mdmf-chip-target\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.badge.afterGolden"))).append("</span></h3><p class=\"mdmf-hint\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.fields.lead"))).append("</p>")
			// filled in by lineage-graphic.js's buildFieldsTable ONLY for a one-source record (coordinator
			// review, 2026-09-22) - a plain placeholder here, ABOVE the horizontally-scrolling table, so the
			// note never scrolls away with it; empty/hidden otherwise, never a blank line.
			.append("<p data-mdmf-fields-note class=\"mdmf-hint\" hidden=\"hidden\"></p>")
			.append("<div class=\"mdmf-fields-scroll\"><div data-mdmf-fields class=\"mdmf-fields-canvas\"></div></div></div>");

		pOut.append("<div class=\"mdmf-panel\"><h3 class=\"mdmf-subtitle\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.frise.title")));
		if (mergePresent)
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-real\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.badge.readable")))
				.append("</span>");
		}
		pOut.append("</h3><p class=\"mdmf-hint\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.frise.lead"))).append("</p>")
			.append("<div data-mdmf-frise class=\"mdmf-frise-canvas\" role=\"img\" aria-label=\"")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.frise.title"))).append("\"></div></div>");

		pOut.append("<div class=\"mdmf-panel mdmf-detail-panel\" data-mdmf-detail aria-live=\"polite\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.detail.placeholder"))).append("</div>");
		pOut.append("</div>");
	}

	/**
	 * The fields the page's own JavaScript (written by the EBX-bound user service, which owns the single
	 * real submit button - see CLAUDE.md, "posting a user service's own inputs") fills in before clicking
	 * it. Always pre-filled from the CURRENT state, like {@code review.ReviewPage#renderHiddenFields}, so
	 * an action that does not touch one of them (e.g. changing only the key) still posts the others.
	 */
	private static void renderHiddenFields(final StringBuilder pOut, final Input pInput)
	{
		pOut.append("<input type=\"hidden\" id=\"mdmfAction\" name=\"mdmfAction\"/>")
			.append("<input type=\"hidden\" id=\"mdmfProjectCode\" name=\"mdmfProjectCode\" value=\"").append(RecordLineagePage.esc(pInput.selectedProjectCode))
			.append("\"/>").append("<input type=\"hidden\" id=\"mdmfEntity\" name=\"mdmfEntity\" value=\"").append(RecordLineagePage.esc(pInput.selectedEntity))
			.append("\"/>").append("<input type=\"hidden\" id=\"mdmfKey\" name=\"mdmfKey\" value=\"").append(RecordLineagePage.esc(pInput.keyText)).append("\"/>")
			// "decide on a suspect" lot (2026-09-22) - which suspect a ACCEPT_SUSPECT/REJECT_SUSPECT/
			// CONFIRM_SUSPECT_DECISION/CANCEL_SUSPECT_DECISION click applies to; re-shown from the CURRENT
			// pending state so a postback that does not touch it (should not happen, defensive only) still
			// carries the right value.
			.append("<input type=\"hidden\" id=\"mdmfSuspectKey\" name=\"mdmfSuspectKey\" value=\"").append(RecordLineagePage.esc(pInput.pendingSuspectKey))
			.append("\"/>");
	}

	// -------------------------------------------------------------------------------------------------- the header

	private static void renderHeader(final StringBuilder pOut, final Input pInput, final Locale pLocale)
	{
		if (pInput.recordBound)
		{
			// the screen was opened ON A RECORD - project/entity/key are already fixed, and the result cards
			// below already name the record, so the whole lookup card (title, lead, project/entity/key form)
			// would only ever restate the same three values - see Input#recordBound's own Javadoc for why the
			// notice/warning above and the hidden mdmfProjectCode/mdmfEntity/mdmfKey inputs are unaffected.
			return;
		}
		pOut.append("<div class=\"mdmf-panel\"><h2>").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.title"))).append("</h2><p class=\"mdmf-lead\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.lead"))).append("</p>");
		if (pInput.selectedProjectCode == null)
		{
			RecordLineagePage.renderProjectSelector(pOut, pInput, pLocale);
			pOut.append("</div>");
			return;
		}
		pOut.append("<p class=\"mdmf-hint\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.project.current", RecordLineagePage.labelOrCode(pInput))))
			.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"CHANGE_PROJECT\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.project.change"))).append("</button></p>");
		if (pInput.entities.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.entity.none")))
				.append("</div></div>");
			return;
		}
		pOut.append("<div class=\"mdmf-grid-2\"><div class=\"mdmf-field\"><label for=\"mdmfEntitySelect\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.entity.label"))).append("</label><select id=\"mdmfEntitySelect\">");
		for (final EntityOption entity : pInput.entities)
		{
			final boolean selected = entity.name.equals(pInput.selectedEntity);
			pOut.append("<option value=\"").append(RecordLineagePage.esc(entity.name)).append("\"").append(selected ? " selected=\"selected\"" : "").append(">")
				.append(RecordLineagePage.esc(entity.label)).append("</option>");
		}
		pOut.append("</select></div><div class=\"mdmf-field\"><label for=\"mdmfKeyInput\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.key.label"))).append("</label><input type=\"text\" id=\"mdmfKeyInput\" placeholder=\"")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.key.placeholder"))).append("\" value=\"")
			.append(RecordLineagePage.esc(pInput.keyText)).append("\"/><p class=\"mdmf-hint\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.key.hint"))).append("</p></div></div>")
			.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"LOOKUP\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.lookup.button"))).append("</button></div></div>");
	}

	private static String labelOrCode(final Input pInput)
	{
		return pInput.selectedProjectLabel == null || pInput.selectedProjectLabel.isEmpty() ? pInput.selectedProjectCode : pInput.selectedProjectLabel;
	}

	private static void renderProjectSelector(final StringBuilder pOut, final Input pInput, final Locale pLocale)
	{
		if (pInput.projects.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.project.none")))
				.append("</div>");
			return;
		}
		pOut.append("<div class=\"mdmf-field\"><label for=\"mdmfProjectSelect\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.project.label"))).append("</label><select id=\"mdmfProjectSelect\">");
		for (final ProjectOption project : pInput.projects)
		{
			pOut.append("<option value=\"").append(RecordLineagePage.esc(project.code)).append("\">").append(RecordLineagePage.esc(project.label)).append(" (")
				.append(RecordLineagePage.esc(project.code)).append(")</option>");
		}
		pOut.append("</select></div><div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"SELECT_PROJECT\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.project.load"))).append("</button></div>");
	}

	private static void renderEmptyHint(final StringBuilder pOut, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-notice mdmf-notice-target\"><p><b>").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.empty.hint.title")))
			.append("</b></p><p>").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.empty.hint.body"))).append("</p><p class=\"mdmf-hint\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.empty.hint.url"))).append("</p></div>");
	}

	// ------------------------------------------------------------------------------------------------ record panel

	/**
	 * The record's own NAME (coordinator review, 2026-09-22: "the golden card and the page header show the
	 * primary key where the mockup shows the record's NAME") as the title, "{entity} &middot; {key} &middot;
	 * {N} source(s)" underneath, plus an "open in EBX" link when one was built - and NOTHING else (the
	 * field/value table this panel used to always show is a plain duplicate of "champ par champ", part 2 of
	 * the graphic below, which shows every field WITH its provenance - a table with values only, no
	 * provenance, added nothing once the graphic exists). The full table stays available as part of the
	 * {@code <noscript>} fallback ({@link #renderRecordPanel}, called there instead, which keeps its own
	 * ORIGINAL "Record {0} #{1}" title - never renamed, so a reader who only ever sees the fallback still
	 * gets an unambiguous heading).
	 */
	/**
	 * F2-08 / F2-112: what the screen must say instead of staying quiet. "Merge lineage unavailable on this version" when the add-on's lineage could not be read (never an empty list
	 * that reads as "nothing merged"), and "N of M contributors" with a "next" button ({@code NEXT_CONTRIBUTORS}) when the golden has more contributors than one page. Visible, not in the
	 * {@code noscript} fallback. Package-visible for the test.
	 */
	static void renderLineageStatus(final StringBuilder pOut, final JsonNode pLineage, final Locale pLocale)
	{
		final String note = pLineage.path("fieldLineageNote").asText("");
		if (note.startsWith(RecordLineage.MERGE_LINEAGE_UNREADABLE_PREFIX))
		{
			pOut.append("<p class=\"mdmf-notice mdmf-notice-warn\">")
				.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.unreadable.summary", note.substring(RecordLineage.MERGE_LINEAGE_UNREADABLE_PREFIX.length()))))
				.append("</p>");
		}
		final JsonNode contributors = pLineage.path("contributors");
		final int total = contributors.path("total").asInt(0);
		final int shown = contributors.path("shown").asInt(0);
		if (total > shown)
		{
			pOut.append("<p class=\"mdmf-hint mdmf-contributors\">")
				.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.contributors.of", shown, total)));
			if (contributors.hasNonNull("nextOffset"))
			{
				pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"NEXT_CONTRIBUTORS\">")
					.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.contributors.next"))).append("</button>");
			}
			pOut.append("</p>");
		}
	}

	private static void renderRecordHeaderCompact(final StringBuilder pOut, final JsonNode pLineage, final Locale pLocale)
	{
		final String entity = pLineage.path("entity").asText("");
		final String masterKey = pLineage.path("masterKey").asText("");
		final String masterTitle = pLineage.hasNonNull("masterLabel") ? pLineage.path("masterLabel").asText() : masterKey;
		final int sourceCount = pLineage.path("sources").size();
		pOut.append("<div class=\"mdmf-panel\"><h2>").append(RecordLineagePage.esc(masterTitle));
		// the EBX record id, small and muted next to the title (task brief, 2026-09-22 - the product owner's own
		// live screenshots of golden 322 "BPCE" and a suspect of "HSBC BANK" named the record by its title
		// ONLY, nowhere its own id) - only when the title above is a real NAME: when masterLabel is absent
		// masterTitle already fell back to the bare masterKey two lines up, and showing the chip too would
		// read as "1 · 1".
		if (pLineage.hasNonNull("masterLabel"))
		{
			pOut.append(RecordLineagePage.idChip(pLocale, masterKey));
		}
		pOut.append("</h2><p class=\"mdmf-lead\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.record.compact.subtitle", entity, masterKey, String.valueOf(sourceCount))))
			.append("</p>");
		if (pLineage.hasNonNull("masterUrl"))
		{
			// a plain link, not routed through the graphic script's own fixUrl perspective rewrite (that
			// treatment stays on the golden card's own click handler inside the graphic) - "target=_top" at
			// least breaks out of any iframe pane, same as every other plain link this module writes outside
			// the graphic canvas.
			pOut.append("<p class=\"mdmf-hint\"><a href=\"").append(RecordLineagePage.esc(pLineage.path("masterUrl").asText())).append("\" target=\"_top\">")
				.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.open"))).append("</a></p>");
		}
		pOut.append("</div>");
	}

	// -------------------------------------------------------------------------------------- compare with suspects

	/**
	 * Column-width budget shared by BOTH compare tables (the main plan-field rows and the "Other fields"
	 * rows) - percentages of the table's own width, field-name column first, then the anchor ("This record")
	 * column, with the rest split evenly across however many suspect columns there are. Kept as two named
	 * constants (rather than inlined in {@link #renderCompareColgroup}) purely so the split is visible in one
	 * place without reading the arithmetic.
	 */
	private static final double COMPARE_FIELD_COL_PCT = 20.0;
	private static final double COMPARE_RECORD_COL_PCT = 20.0;

	/**
	 * Per-column minimum, in pixels, used only to give the WHOLE table a floor width
	 * ({@link #renderCompareTableOpen}) on a narrow viewport - never applied to an individual cell (that was
	 * the pre-fix behaviour, and part of the bug: see the class Javadoc below). Same value the pre-fix CSS
	 * used per cell (140px), kept only as "how wide is comfortable for one column" - not tied to the current
	 * percentage split.
	 */
	private static final int COMPARE_COL_MIN_PX = 140;

	/**
	 * Bug fixed 2026-09-22 (the product owner's screenshot of "Compare with suspects (1)"): the main comparison table
	 * and the "Other fields (N)" table below it are two SEPARATE {@code <table>} elements (see {@link
	 * #renderComparisonPanel}) - one for the fields the entity's {@code MatchPlan} prioritises, one for every
	 * other business field, collapsed under a disclosure. Each used to size its own columns from its own
	 * content ({@code table-layout: auto}, the default): "cityKey" is a lot narrower than "Supplier ID", so
	 * the two tables' field-name columns - and, cascading from there, every column after it - ended up
	 * different widths. The value column started at x&asymp;398 in the upper table and x&asymp;363 in the
	 * lower one in that screenshot; the first suspect column started at x&asymp;828 versus x&asymp;754.
	 * <p>
	 * <b>Fix - approach (b) from the task brief</b> ("keep two tables but give both {@code table-layout:
	 * fixed}, identical {@code colgroup} widths ... so the browser cannot size them independently"), not
	 * approach (a) (one {@code <table>} with the "Other fields" header as a full-width row): the panel
	 * already renders its two parts as a native {@code <details>}/{@code <summary>} pair with NO script
	 * required to open it ({@link #renderComparisonPanel}'s own Javadoc, "no script required at all") -
	 * folding "Other fields" into a {@code <tbody>} toggled by a group row would need a click handler to
	 * show/hide it (a plain {@code <tr>} cannot itself be a disclosure widget), trading a documented,
	 * already-shipped no-JS behaviour for a new one just to solve a CSS problem. Two tables, one shared
	 * {@code <colgroup>} (this method + {@link #renderCompareColgroup}) and {@code table-layout: fixed} on
	 * {@code .mdmf-compare-table} (lineage.css) keeps that behaviour unchanged: column width becomes a
	 * function of the STRUCTURE (field-name column, anchor column, one column per suspect - always the same
	 * {@code suspects.size()} for both tables in one render) rather than of whichever field labels a given
	 * table happens to render, so the two tables line up for any number of pending suspects, not only the
	 * one-suspect case photographed.
	 */
	private static void renderCompareTableOpen(final StringBuilder pOut, final int pSuspectCount)
	{
		final int totalColumns = 2 + pSuspectCount; // field name + "this record" + one per suspect
		pOut.append("<table class=\"mdmf-compare-table\" style=\"min-width:").append(totalColumns * RecordLineagePage.COMPARE_COL_MIN_PX).append("px\">");
		RecordLineagePage.renderCompareColgroup(pOut, pSuspectCount);
	}

	/**
	 * The {@code <colgroup>} itself - see {@link #renderCompareTableOpen} for why this exists and what bug it
	 * fixes. Widths are percentages of the table (not the viewport), formatted with {@link Locale#ROOT}
	 * deliberately: {@code String.format} without an explicit locale renders a decimal comma instead of a
	 * point under a French default JVM locale (this codebase's own instance runs one), which would make every
	 * {@code width:NN,NNNN%} an invalid CSS declaration - silently ignored by the browser, not an error
	 * anywhere in this build.
	 */
	private static void renderCompareColgroup(final StringBuilder pOut, final int pSuspectCount)
	{
		final double suspectColumnPct = pSuspectCount > 0 ? (100.0 - RecordLineagePage.COMPARE_FIELD_COL_PCT - RecordLineagePage.COMPARE_RECORD_COL_PCT) / pSuspectCount : 0.0;
		pOut.append("<colgroup><col style=\"width:").append(RecordLineagePage.comparePct(RecordLineagePage.COMPARE_FIELD_COL_PCT)).append("\"/>")
			.append("<col style=\"width:").append(RecordLineagePage.comparePct(RecordLineagePage.COMPARE_RECORD_COL_PCT)).append("\"/>");
		for (int i = 0; i < pSuspectCount; i++)
		{
			pOut.append("<col style=\"width:").append(RecordLineagePage.comparePct(suspectColumnPct)).append("\"/>");
		}
		pOut.append("</colgroup>");
	}

	private static String comparePct(final double pPercent)
	{
		return String.format(Locale.ROOT, "%.4f%%", pPercent);
	}

	/**
	 * "Compare with suspects" (task brief, 2026-09-22, "decide on a suspect" lot, part A/B): columns = the
	 * looked-up record (its own values already sit in {@code record.fields}/{@code record.restrictedFields} -
	 * never repeated here, read straight from there) | each pending suspect this reader could resolve field
	 * values for ({@code comparison.suspects[]}); rows = first the fields the entity's own {@code MatchPlan}
	 * compares ({@code comparison.planFields[]}, in plan order), then every other business field collapsed
	 * under a nested "other fields (N)" disclosure. A per-cell chip - {@code =} identical, {@code ~=} equal
	 * after case/accent/whitespace fold, {@code !=} different, empty, or a lock for anything restricted -
	 * comes straight from {@code lineage.RecordLineage#assemble}'s own {@code chip} computation; this method
	 * never re-derives agreement itself.
	 * <p>
	 * <b>A native {@code <details>}/{@code <summary>}, not a custom JS toggle</b>: opens on a plain click on
	 * its own summary, and on Space/Enter when it has focus - no script required at all for "opened by a
	 * click ... reachable by keyboard" (task brief). {@code lineage-graphic.js}'s ring/suspect-lane click
	 * handlers ADDITIONALLY set {@code .open = true} and scroll to it - a nice-to-have on top of the native
	 * behaviour, never a replacement for it (see that file's own {@code openCompare} comment).
	 * <p>
	 * Renders NOTHING when {@code comparison.suspects} is empty (add-on absent, no group, or no pending
	 * suspect at all) - an empty panel would have nothing to decide.
	 */
	private static void renderComparisonPanel(final StringBuilder pOut, final JsonNode pLineage, final Locale pLocale, final String pPendingAction,
		final String pPendingKey)
	{
		final JsonNode suspects = pLineage.path("comparison").path("suspects");
		if (suspects.size() == 0)
		{
			return;
		}
		final boolean canAcceptSuspects = pLineage.path("merge").path("group").path("actions").path("canAcceptSuspects").asBoolean(false);
		final Map<String, JsonNode> groupMemberByKey = new LinkedHashMap<String, JsonNode>();
		for (final JsonNode member : pLineage.path("merge").path("group").path("suspects"))
		{
			if (member.hasNonNull("masterKey"))
			{
				groupMemberByKey.put(member.get("masterKey").asText(), member);
			}
		}
		final Map<String, JsonNode> sourceByKey = new LinkedHashMap<String, JsonNode>();
		for (final JsonNode source : pLineage.path("suspectSources"))
		{
			if (source.hasNonNull("masterKey"))
			{
				sourceByKey.put(source.get("masterKey").asText(), source);
			}
		}

		// closed by default (task brief: "opened by a click" - on its own summary, or on the matching ring/a
		// suspect lane via lineage-graphic.js's openCompare) - its content stays in the DOM either way, so a
		// keyboard user can still Tab to the summary and press Enter/Space to reveal it without ever touching
		// the SVG above. Forced OPEN when a decision on one of these suspects is already pending confirmation
		// (a postback from this very panel) - never make a steward re-open it just to see the "Confirm?"
		// prompt their own previous click produced.
		final boolean forceOpen = pPendingAction != null && pPendingKey != null;
		pOut.append("<details id=\"mdmfCompare\" class=\"mdmf-panel mdmf-compare\"").append(forceOpen ? " open=\"open\"" : "").append("><summary class=\"mdmf-subtitle\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.title", String.valueOf(suspects.size())))).append("</summary>")
			.append("<p class=\"mdmf-hint\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.lead"))).append("</p>");
		if (!canAcceptSuspects)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-target\">")
				.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.readOnlyNote"))).append("</div>");
		}

		// MOVED here, right under the panel's own header (fix, 2026-09-22 - the product owner's report: he clicked a
		// suspect lane, the panel opened, but the yellow "Confirm this suspect is a duplicate?" prompt
		// rendered at the BOTTOM of the panel, below "Other fields (N)" - out of the viewport, he never saw
		// it). This loop used to run last, right before the closing `</details>` below - moved verbatim
		// (same helpers, same data), never re-implemented, so Accept/Reject/Confirm/Cancel keep behaving
		// exactly as before; only WHERE they render changed. Placing the decision(s) first also means
		// `pendingSuspectScrollScript`'s own scroll-into-view (see that method's own Javadoc) lands the
		// reviewer exactly here, on the very first thing the panel shows, rather than merely at the panel's
		// own top with the prompt still several screens further down.
		for (final JsonNode suspect : suspects)
		{
			if (suspect.path("restricted").asBoolean(false))
			{
				continue; // nothing to decide about a record this session cannot even see
			}
			final String key = suspect.path("masterKey").asText("");
			final String label = RecordLineagePage.suspectLabel(groupMemberByKey.get(key));
			RecordLineagePage.renderSuspectDecision(pOut, key, label, canAcceptSuspects, pPendingAction, pPendingKey, pLocale);
		}

		pOut.append("<div class=\"mdmf-compare-scroll\">");
		RecordLineagePage.renderCompareTableOpen(pOut, suspects.size());
		pOut.append("<thead><tr><th>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.col.field"))).append("</th><th class=\"mdmf-compare-anchor-col\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.col.record")))
			.append(RecordLineagePage.idChip(pLocale, RecordLineagePage.textOrNull(pLineage, "masterKey")));
		final String masterUrl = pLineage.hasNonNull("masterUrl") ? pLineage.path("masterUrl").asText() : null;
		if (masterUrl != null)
		{
			pOut.append("<br/><a href=\"").append(RecordLineagePage.esc(masterUrl)).append("\" target=\"_top\">")
				.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.open"))).append("</a>");
		}
		pOut.append("</th>");
		for (final JsonNode suspect : suspects)
		{
			final String key = suspect.path("masterKey").asText("");
			pOut.append("<th data-mdmf-compare-col=\"").append(RecordLineagePage.esc(key)).append("\">");
			if (suspect.path("restricted").asBoolean(false))
			{
				pOut.append("<span class=\"mdmf-chip mdmf-chip-target\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.locked")))
					.append("</span>");
			}
			else
			{
				RecordLineagePage.renderSuspectHeader(pOut, key, groupMemberByKey.get(key), sourceByKey.get(key), pLocale);
			}
			pOut.append("</th>");
		}
		pOut.append("</tr></thead><tbody>");

		final Set<String> planFieldNames = new LinkedHashSet<String>();
		final JsonNode planFields = pLineage.path("comparison").path("planFields");
		for (final JsonNode planField : planFields)
		{
			final String field = planField.path("field").asText(null);
			if (field != null)
			{
				planFieldNames.add(field);
				RecordLineagePage.renderComparisonRow(pOut, pLineage, field, planField.path("algorithm").asText(null), suspects, pLocale);
			}
		}
		final List<JsonNode> otherFields = new ArrayList<JsonNode>();
		for (final JsonNode field : pLineage.path("fields"))
		{
			final String name = field.path("field").asText(null);
			if (name != null && !planFieldNames.contains(name))
			{
				otherFields.add(field);
			}
		}
		pOut.append("</tbody></table></div>");
		if (!otherFields.isEmpty())
		{
			pOut.append("<details class=\"mdmf-compare-other\"><summary>")
				.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.otherFields", String.valueOf(otherFields.size()))))
				.append("</summary><div class=\"mdmf-compare-scroll\">");
			RecordLineagePage.renderCompareTableOpen(pOut, suspects.size());
			pOut.append("<tbody>");
			for (final JsonNode field : otherFields)
			{
				RecordLineagePage.renderComparisonRow(pOut, pLineage, field.path("field").asText(""), null, suspects, pLocale);
			}
			pOut.append("</tbody></table></div></details>");
		}

		pOut.append("<p class=\"mdmf-hint\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.legend"))).append("</p>");
		pOut.append("</details>");
	}

	private static void renderSuspectHeader(final StringBuilder pOut, final String pKey, final JsonNode pGroupMember, final JsonNode pSource, final Locale pLocale)
	{
		final String system = pGroupMember != null && pGroupMember.hasNonNull("sourceSystem") ? pGroupMember.get("sourceSystem").asText()
			: pSource != null ? pSource.path("sourceSystem").asText("") : "";
		final boolean hasSystem = !system.isEmpty();
		pOut.append("<b>").append(RecordLineagePage.esc(hasSystem ? system : pKey)).append("</b>");
		if (hasSystem)
		{
			// the suspect's own EBX id next to its system name (task brief, 2026-09-22) - skipped when the
			// title above IS the bare key already (no system name known), same "never show it twice" guard
			// as renderRecordHeaderCompact's own masterLabel check.
			pOut.append(RecordLineagePage.idChip(pLocale, pKey));
		}
		if (pSource != null)
		{
			final String sourceId = pSource.path("sourceId").asText("");
			if (!sourceId.isEmpty())
			{
				pOut.append("<br/><span class=\"mdmf-hint\">").append(RecordLineagePage.esc(sourceId)).append("</span>");
			}
			final String receivedAt = pSource.path("landing").path("receivedAt").asText("");
			if (!receivedAt.isEmpty())
			{
				pOut.append("<br/><span class=\"mdmf-hint\">")
					.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.receivedOn", receivedAt.length() >= 10 ? receivedAt.substring(0, 10) : receivedAt)))
					.append("</span>");
			}
			final String url = pSource.path("staging").hasNonNull("url") ? pSource.path("staging").path("url").asText()
				: pSource.path("landing").hasNonNull("url") ? pSource.path("landing").path("url").asText() : null;
			if (url != null)
			{
				pOut.append("<br/><a href=\"").append(RecordLineagePage.esc(url)).append("\" target=\"_top\">")
					.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.open"))).append("</a>");
			}
		}
	}

	/**
	 * The suspect's own human title for the decision block ({@link #renderSuspectDecision}) - the group
	 * member's own record label when known, else its source system, else {@code null} (nothing beyond the
	 * bare key). Never appends the key itself any more (task brief, 2026-09-22: the key now always renders
	 * through the SAME {@link #idChip} every other record name on this page uses, not hand-rolled here) -
	 * {@link #renderSuspectDecision} is the one place that decides whether to print the chip, exactly
	 * mirroring the "never show the key twice" guard {@link #renderSuspectHeader}/
	 * {@link #renderRecordHeaderCompact} already apply.
	 */
	private static String suspectLabel(final JsonNode pGroupMember)
	{
		if (pGroupMember != null && pGroupMember.hasNonNull("label"))
		{
			return pGroupMember.get("label").asText();
		}
		if (pGroupMember != null && pGroupMember.hasNonNull("sourceSystem"))
		{
			return pGroupMember.get("sourceSystem").asText();
		}
		return null;
	}

	private static void renderComparisonRow(final StringBuilder pOut, final JsonNode pLineage, final String pField, final String pAlgorithm, final JsonNode pSuspects,
		final Locale pLocale)
	{
		final String label = RecordLineagePage.fieldLabelFor(pLineage, pField);
		pOut.append("<tr><td><b>").append(RecordLineagePage.esc(label)).append("</b>");
		if (pAlgorithm != null)
		{
			pOut.append("<br/><span class=\"mdmf-hint\">").append(RecordLineagePage.esc(AlgorithmWords.words(pAlgorithm))).append("</span>");
		}
		pOut.append("</td><td class=\"mdmf-compare-anchor-col\">").append(RecordLineagePage.renderAnchorCell(pLineage, pField, pLocale)).append("</td>");
		for (final JsonNode suspect : pSuspects)
		{
			pOut.append("<td>");
			if (suspect.path("restricted").asBoolean(false))
			{
				pOut.append("<span class=\"mdmf-chip mdmf-chip-target\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.locked")))
					.append("</span>");
			}
			else
			{
				final JsonNode fieldNode = suspect.path("fields").path(pField);
				pOut.append(RecordLineagePage.renderSuspectCell(fieldNode, pLocale));
			}
			pOut.append("</td>");
		}
		pOut.append("</tr>");
	}

	private static String fieldLabelFor(final JsonNode pLineage, final String pField)
	{
		for (final JsonNode field : pLineage.path("fields"))
		{
			if (pField.equals(field.path("field").asText("")))
			{
				return field.hasNonNull("fieldLabel") ? field.path("fieldLabel").asText() : pField;
			}
		}
		return pField;
	}

	private static String renderAnchorCell(final JsonNode pLineage, final String pField, final Locale pLocale)
	{
		final JsonNode recordFields = pLineage.path("record").path("fields");
		for (final JsonNode restricted : pLineage.path("record").path("restrictedFields"))
		{
			if (pField.equals(restricted.asText()))
			{
				return "<span class=\"mdmf-chip mdmf-chip-target\">" + RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.locked")) + "</span>";
			}
		}
		final JsonNode value = recordFields.get(pField);
		if (value == null || value.isNull())
		{
			return "<i>" + RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.fields.contribution.noValue")) + "</i>";
		}
		return RecordLineagePage.esc(value.asText());
	}

	private static String renderSuspectCell(final JsonNode pFieldNode, final Locale pLocale)
	{
		if (pFieldNode.isMissingNode())
		{
			return "<i>" + RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.fields.contribution.noValue")) + "</i>";
		}
		if (pFieldNode.path("restricted").asBoolean(false))
		{
			return "<span class=\"mdmf-chip mdmf-chip-target\">" + RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.locked")) + "</span>";
		}
		final String value = pFieldNode.hasNonNull("value") ? pFieldNode.path("value").asText() : null;
		final String chip = pFieldNode.path("chip").asText("UNKNOWN");
		final StringBuilder out = new StringBuilder();
		if (value == null)
		{
			out.append("<i>").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.fields.contribution.noValue"))).append("</i>");
		}
		else
		{
			out.append(RecordLineagePage.esc(value));
		}
		out.append(" <span class=\"mdmf-chip mdmf-chip-cmp mdmf-chip-cmp-").append(chip.toLowerCase(java.util.Locale.ENGLISH)).append("\" title=\"")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.chip." + chip))).append("\">")
			.append(RecordLineagePage.esc(RecordLineagePage.chipSymbol(chip, pLocale))).append("</span>");
		return out.toString();
	}

	/** {@code =}/{@code ~=}/{@code !=} as real symbols (task brief's own vocabulary), {@code empty}/{@code unknown} as short localized words - never a bare glyph with no accessible meaning (a {@code title} attribute above already carries the full sentence). */
	private static String chipSymbol(final String pChip, final Locale pLocale)
	{
		if ("EQ".equals(pChip))
		{
			return "=";
		}
		if ("EQUIVALENT".equals(pChip))
		{
			return "≈"; // ~=
		}
		if ("DIFF".equals(pChip))
		{
			return "≠"; // !=
		}
		if ("EMPTY".equals(pChip))
		{
			return RecordLineagePage.m(pLocale, "lineage.compare.chip.EMPTY");
		}
		return RecordLineagePage.m(pLocale, "lineage.compare.chip.UNKNOWN");
	}

	/**
	 * Two buttons ("Confirm duplicate"/"Not the same") per suspect, or - once the FIRST click already set
	 * {@link Input#pendingSuspectAction}/{@link Input#pendingSuspectKey} for THIS suspect - a plain-word
	 * confirmation ("Confirm?"/"Really?") with its own Confirm/Cancel pair (task brief, 2026-09-22: "each
	 * with a confirmation - a second click"). Every button is a plain {@code data-mdmf-action} one, posted
	 * through the SAME hidden-submit mechanism every other action on this screen already uses ({@code
	 * AbstractRecordLineageUserService#pageJs}) - {@code data-mdmf-suspect-key} carries which suspect the
	 * click applies to, read by that script's own generic "does this button carry a suspect key" rule.
	 */
	private static void renderSuspectDecision(final StringBuilder pOut, final String pKey, final String pLabel, final boolean pCanAct, final String pPendingAction,
		final String pPendingKey, final Locale pLocale)
	{
		// the id chip only when there IS a title beyond the bare key (see suspectLabel's own Javadoc) - task
		// brief, 2026-09-22, coordinator's live example: "HSBC BANK · 274 - Confirm this suspect is a
		// duplicate?" (previously just "HSBC BANK": the record a confirmation applied to was not identified
		// by its own EBX id anywhere in the prompt).
		pOut.append("<div class=\"mdmf-compare-decision\"><b>").append(RecordLineagePage.esc(pLabel != null ? pLabel : pKey)).append("</b>");
		if (pLabel != null)
		{
			pOut.append(RecordLineagePage.idChip(pLocale, pKey));
		}
		pOut.append(" ");
		if (!pCanAct)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.readOnlyNote"))).append("</span></div>");
			return;
		}
		final boolean pending = pKey.equals(pPendingKey) && pPendingAction != null;
		if (pending)
		{
			final boolean accept = "ACCEPT".equals(pPendingAction);
			pOut.append("<span class=\"mdmf-notice mdmf-notice-warn mdmf-compare-confirm\">")
				.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, accept ? "lineage.compare.confirmAccept" : "lineage.compare.confirmReject")))
				.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"CONFIRM_SUSPECT_DECISION\" data-mdmf-suspect-key=\"")
				.append(RecordLineagePage.esc(pKey)).append("\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.confirmButton")))
				.append("</button> <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"CANCEL_SUSPECT_DECISION\" data-mdmf-suspect-key=\"")
				.append(RecordLineagePage.esc(pKey)).append("\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.cancelButton")))
				.append("</button></span></div>");
			return;
		}
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"ACCEPT_SUSPECT\" data-mdmf-suspect-key=\"")
			.append(RecordLineagePage.esc(pKey)).append("\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.acceptButton")))
			.append("</button> <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"REJECT_SUSPECT\" data-mdmf-suspect-key=\"")
			.append(RecordLineagePage.esc(pKey)).append("\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.compare.rejectButton")))
			.append("</button></div>");
	}

	// ---------------------------------------------------------------------------------------- cross-references

	/**
	 * "Cross-references" table (the product owner's request, 2026-09-23): one row per {@code Xref} that fed the record
	 * being looked at - the golden's own row (if any - an auto-created golden has none) plus one row for every
	 * record merged into it. A thin HTML rendering of {@link CrossReferences#project}'s own projection over
	 * the SAME already-embedded {@code pLineage} JSON the graphic/compare panels above already read from -
	 * never a second read, and never re-deriving the redaction rules {@code RecordLineage#assemble} already
	 * enforced upstream (a restricted row here shows ONLY its source system, exactly like every other
	 * restricted thing on this screen - see {@link #renderCrossReferenceRow}).
	 * <p>
	 * <b>Deliberately reads {@code sources[]} through {@link CrossReferences}, never {@code
	 * suspectSources[]}</b>: a pending suspect has not been confirmed as a duplicate yet, so it is not a
	 * cross-reference of this golden record - the exact same distinction the "champ par champ" field table
	 * (which also only ever reads {@code sources[]}) already draws, and the SAME projection the REST route
	 * ({@code rest.FactoryResource#recordCrossReferences}) uses, so the table and the API can never disagree
	 * about what counts as a row.
	 * <p>
	 * <b>Placed after the graphic and the "Compare with suspects" panel, and OUTSIDE the
	 * &lt;noscript&gt; block</b> (task brief, 2026-09-23: "placed after the journey graphic and the existing
	 * panels ... keep the existing panels exactly where they are"): plain HTML, needs no script to render or
	 * to read, so - unlike the graphic itself - it is shown whether or not JavaScript can run, and it never
	 * duplicates content the noscript fallback's own journey/field tables below already show (those list
	 * every CONTRIBUTION per field; this one lists every Xref row once, with its landing/staging keys and
	 * "open in EBX" links, which neither of those two tables shows).
	 */
	private static void renderCrossReferencesPanel(final StringBuilder pOut, final JsonNode pLineage, final Locale pLocale)
	{
		final JsonNode xrefs = CrossReferences.project(pLineage).path("xrefs");
		pOut.append("<div class=\"mdmf-panel\"><h3 class=\"mdmf-subtitle\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.xref.title")))
			.append("</h3><p class=\"mdmf-hint\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.xref.lead"))).append("</p>");
		if (xrefs.size() == 0)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.xref.none"))).append("</p></div>");
			return;
		}
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table mdmf-xref-table\">")
			// NO <colgroup> / table-layout:fixed here, unlike .mdmf-compare-table (whose own colgroup exists
			// for a real reason - see renderCompareTableOpen: two SEPARATE <table>s that must stay pixel-aligned
			// with each other). This table has no sibling to align with, so a fixed 7-column split was only ever
			// a guess at proportions - and a wrong one at pane widths under ~800px, where "SOURCE SYSTEM"/
			// "SOURCE ID" overlapped and a source id wrapped one character per line (measured live 2026-09-23,
			// `docs/RECORD-LINEAGE.md` "Cross-references (2026-09-23)"). Left to the browser's own table layout,
			// each column sizes to its actual content/header instead of a hard-coded percentage.
			.append("<thead><tr><th>").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.xref.col.sourceSystem"))).append("</th><th>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.xref.col.sourceId"))).append("</th><th>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.xref.col.masterRecord"))).append("</th><th>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.xref.col.stagingRecord"))).append("</th><th>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.xref.col.landingRecord"))).append("</th><th>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.xref.col.loadId"))).append("</th><th>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.xref.col.receivedAt"))).append("</th></tr></thead><tbody>");
		for (final JsonNode xref : xrefs)
		{
			RecordLineagePage.renderCrossReferenceRow(pOut, xref, pLocale);
		}
		pOut.append("</tbody></table></div></div>");
	}

	/**
	 * One row - a restricted one (task brief: "restricted sources show the system name only, as the rest of
	 * the page does") collapses everything past the source system into a single dashed marker, the SAME
	 * {@code mdmf-chip-target} treatment {@link #renderFieldTable}/{@link #renderComparisonPanel} already use
	 * for something the caller's session may not see. A visible row's master-record cell carries the row's own
	 * id chip and, only for the golden entry ({@code golden: true} - the SAME flag {@link
	 * RecordLineage.SourceChain#golden} already carries through this whole screen), a small accent chip - the
	 * "mark for the golden/anchor vs a merged member" the task brief asked for. The staging/landing cells each
	 * carry their own "open in EBX" link right under the key, exactly the way {@link #renderSuspectHeader}
	 * already attaches one under a suspect's own key - never a separate "open" column with two ambiguous links
	 * crammed into one cell.
	 */
	private static void renderCrossReferenceRow(final StringBuilder pOut, final JsonNode pXref, final Locale pLocale)
	{
		final String masterKey = RecordLineagePage.textOrNull(pXref, "masterKey");
		final boolean golden = pXref.path("golden").asBoolean(false);
		pOut.append("<tr><td>").append(RecordLineagePage.esc(pXref.path("sourceSystem").asText(""))).append("</td>");
		if (pXref.path("restricted").asBoolean(false))
		{
			pOut.append("<td colspan=\"6\"><span class=\"mdmf-chip mdmf-chip-target\">")
				.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.record.restricted"))).append("</span></td></tr>");
			return;
		}
		pOut.append("<td>").append(RecordLineagePage.esc(pXref.path("sourceId").asText(""))).append("</td>");
		pOut.append("<td>").append(RecordLineagePage.esc(masterKey));
		if (golden)
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-accent\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.xref.golden")))
				.append("</span>");
		}
		pOut.append("</td>");
		pOut.append("<td>").append(RecordLineagePage.esc(pXref.path("stagingTable").asText(""))).append(" ")
			.append(RecordLineagePage.esc(pXref.path("stagingKey").asText("")));
		RecordLineagePage.appendOpenLink(pOut, RecordLineagePage.textOrNull(pXref, "stagingUrl"), pLocale);
		pOut.append("</td>");
		pOut.append("<td>").append(RecordLineagePage.esc(pXref.path("landingTable").asText(""))).append(" ")
			.append(RecordLineagePage.esc(pXref.path("landingKey").asText("")));
		RecordLineagePage.appendOpenLink(pOut, RecordLineagePage.textOrNull(pXref, "landingUrl"), pLocale);
		pOut.append("</td>");
		pOut.append("<td>").append(RecordLineagePage.esc(pXref.path("loadId").asText(""))).append("</td>");
		final String receivedAt = pXref.path("receivedAt").asText("");
		pOut.append("<td>").append(RecordLineagePage.esc(receivedAt.length() >= 10 ? receivedAt.substring(0, 10) : receivedAt)).append("</td></tr>");
	}

	/** {@code <br/><a href="...">Open in EBX</a>} when {@code pUrl} is non-null - the exact same link markup/label ({@code lineage.graphic.open}) {@link #renderSuspectHeader}/{@link #renderRecordHeaderCompact} already use, never a second wording for the same action. Nothing when {@code pUrl} is null (could not be built, or the row is restricted - never reached then anyway). */
	private static void appendOpenLink(final StringBuilder pOut, final String pUrl, final Locale pLocale)
	{
		if (pUrl == null)
		{
			return;
		}
		pOut.append("<br/><a href=\"").append(RecordLineagePage.esc(pUrl)).append("\" target=\"_top\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.graphic.open"))).append("</a>");
	}

	private static void renderRecordPanel(final StringBuilder pOut, final JsonNode pLineage, final Locale pLocale)
	{
		final String entity = pLineage.path("entity").asText("");
		final String masterKey = pLineage.path("masterKey").asText("");
		final String project = pLineage.path("project").asText("");
		final String masterTable = pLineage.path("masterTable").asText("");
		pOut.append("<div class=\"mdmf-panel\"><h2>").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.record.title", entity, masterKey)))
			.append("</h2><p class=\"mdmf-lead\">")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.record.subtitle", project, entity, masterTable))).append("</p>");

		final JsonNode fields = pLineage.path("record").path("fields");
		final JsonNode restrictedFields = pLineage.path("record").path("restrictedFields");
		if (fields.size() == 0 && restrictedFields.size() == 0)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.record.none"))).append("</p></div>");
			return;
		}
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.record.field"))).append("</th><th>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.record.value"))).append("</th></tr></thead><tbody>");
		final Iterator<Map.Entry<String, JsonNode>> visible = fields.fields();
		while (visible.hasNext())
		{
			final Map.Entry<String, JsonNode> field = visible.next();
			final JsonNode value = field.getValue();
			pOut.append("<tr><td>").append(RecordLineagePage.esc(field.getKey())).append("</td><td>")
				.append(RecordLineagePage.esc(value == null || value.isNull() ? "" : value.asText())).append("</td></tr>");
		}
		for (final JsonNode restrictedField : restrictedFields)
		{
			pOut.append("<tr><td>").append(RecordLineagePage.esc(restrictedField.asText())).append("</td><td><span class=\"mdmf-chip mdmf-chip-target\">")
				.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.record.restricted"))).append("</span></td></tr>");
		}
		pOut.append("</tbody></table></div></div>");
	}

	// ---------------------------------------------------------------------------------------------------- journey

	private static void renderJourney(final StringBuilder pOut, final JsonNode pLineage, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-panel\"><h3 class=\"mdmf-subtitle\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.journey.title")))
			.append("</h3><div class=\"mdmf-journey\">");
		for (final JsonNode source : pLineage.path("sources"))
		{
			RecordLineagePage.renderSourceRow(pOut, source, pLineage, pLocale);
		}
		final JsonNode merge = pLineage.path("merge");
		final boolean present = merge.path("present").asBoolean(false);
		if (present)
		{
			RecordLineagePage.renderMergeRow(pOut, merge, pLineage, pLocale);
		}
		pOut.append("</div>");
		if (!present)
		{
			RecordLineagePage.renderMergeAbsentNotice(pOut, merge, pLocale);
		}
		pOut.append("</div>");
	}

	private static void renderSourceRow(final StringBuilder pOut, final JsonNode pSource, final JsonNode pLineage, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-jrow\"><div class=\"mdmf-jnode\"><span>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.journey.sourceSystem"))).append("</span><b>")
			.append(RecordLineagePage.esc(pSource.path("sourceSystem").asText(""))).append("</b></div>")
			.append("<span class=\"mdmf-jarrow\" aria-hidden=\"true\">&#8594;</span>");
		if (pSource.path("restricted").asBoolean(false))
		{
			pOut.append("<div class=\"mdmf-jnode mdmf-jnode-restricted\"><span>")
				.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.journey.restrictedSource"))).append("</span><b>")
				.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.journey.restrictedSource.hint"))).append("</b></div></div>");
			return;
		}
		final JsonNode landing = pSource.path("landing");
		pOut.append("<div class=\"mdmf-jnode\"><span>").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.journey.landing"))).append("</span><b>")
			.append(RecordLineagePage.esc(landing.path("table").asText(""))).append(" · ").append(RecordLineagePage.esc(pSource.path("sourceId").asText("")))
			.append("</b>")
			// the LANDING row's own EBX id - a DIFFERENT table's key than the master/staging ones sitting next
			// to it (task brief, 2026-09-22: "for a source-zone node whose key is a different table's key,
			// show that table's key the same way") - landing.key, never sourceId (the business identifier
			// already shown above, which is not an EBX primary key at all).
			.append(RecordLineagePage.idChip(pLocale, RecordLineagePage.textOrNull(landing, "key")))
			.append("<small>_loadId ").append(RecordLineagePage.esc(landing.path("loadId").asText(""))).append(" · _status ")
			.append(RecordLineagePage.esc(landing.path("status").asText(""))).append("</small></div>")
			.append("<span class=\"mdmf-jarrow\" aria-hidden=\"true\">&#8594;</span>");
		final JsonNode staging = pSource.path("staging");
		final String cleaningSummary = RecordLineagePage.cleaningSummaryForSource(pLineage, pSource.path("masterKey").asText(""), pLocale);
		pOut.append("<div class=\"mdmf-jnode\"><span>").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.journey.staging"))).append("</span><b>")
			.append(RecordLineagePage.esc(staging.path("table").asText(""))).append("</b>")
			.append(RecordLineagePage.idChip(pLocale, RecordLineagePage.textOrNull(staging, "key")))
			.append("<small>").append(RecordLineagePage.esc(cleaningSummary)).append("</small></div></div>");
	}

	/**
	 * The distinct cleaning-step kinds ({@code MappingStep.kind}, human words via {@code
	 * review.ProposalWording}) applied to ANY field this source contributed a value to, first-seen order,
	 * de-duplicated - the mockup's own "transformations : TRIM, VAT en majuscules" summary line under the
	 * staging node, not a per-field breakdown (that lives in the field-by-field table below).
	 */
	private static String cleaningSummaryForSource(final JsonNode pLineage, final String pMasterKey, final Locale pLocale)
	{
		final Set<String> kinds = new LinkedHashSet<String>();
		for (final JsonNode field : pLineage.path("fields"))
		{
			for (final JsonNode contribution : field.path("contributions"))
			{
				if (contribution.path("restricted").asBoolean(false) || !pMasterKey.equals(contribution.path("masterKey").asText("")))
				{
					continue;
				}
				for (final JsonNode step : contribution.path("cleaningSteps"))
				{
					kinds.add(step.asText());
				}
			}
		}
		if (kinds.isEmpty())
		{
			return RecordLineagePage.m(pLocale, "lineage.journey.transformations.none");
		}
		final StringBuilder words = new StringBuilder();
		for (final String kind : kinds)
		{
			if (words.length() > 0)
			{
				words.append(", ");
			}
			words.append(ProposalWording.mappingStepProposition(kind, null, null));
		}
		return RecordLineagePage.m(pLocale, "lineage.journey.transformations", words.toString());
	}

	private static void renderMergeRow(final StringBuilder pOut, final JsonNode pMerge, final JsonNode pLineage, final Locale pLocale)
	{
		final String groupId = pMerge.path("groupId").asText("");
		pOut.append("<div class=\"mdmf-jrow\" style=\"justify-content:center\"><div class=\"mdmf-jnode\"><span>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.journey.matchGroup"))).append("</span><b>")
			.append(RecordLineagePage.esc(RecordLineagePage.stateWords(pMerge.path("state").asText(null), pLocale))).append("</b><small>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.journey.group", groupId)));
		if (pMerge.path("autoCreated").asBoolean(false))
		{
			pOut.append(" · ").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.journey.autoCreated")));
		}
		pOut.append("</small></div><span class=\"mdmf-jarrow\" aria-hidden=\"true\">&#8594;</span>")
			.append("<div class=\"mdmf-jnode mdmf-jnode-golden\"><span>").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.journey.golden")))
			// the golden's own EBX id as a chip, not baked into the <b> title (task brief, 2026-09-22 - was
			// one bold run "{masterTable} · {goldenKey}"; RecordLineagePageTest's golden-node fixtures were
			// updated to match, search "mdmf-id" there).
			.append("</span><b>").append(RecordLineagePage.esc(pLineage.path("masterTable").asText(""))).append("</b>")
			.append(RecordLineagePage.idChip(pLocale, RecordLineagePage.textOrNull(pMerge, "goldenKey"))).append("</div></div>");
	}

	private static void renderMergeAbsentNotice(final StringBuilder pOut, final JsonNode pMerge, final Locale pLocale)
	{
		final String reason = pMerge.path("reason").asText(null);
		pOut.append("<div class=\"mdmf-notice mdmf-notice-target\"><p>").append(RecordLineagePage.esc(RecordLineagePage.reasonWords(reason, pLocale))).append("</p>");
		if (pMerge.hasNonNull("detail"))
		{
			pOut.append("<p class=\"mdmf-hint\">")
				.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.merge.reason.detail", pMerge.path("detail").asText("")))).append("</p>");
		}
		pOut.append("</div>");
	}

	private static String reasonWords(final String pReason, final Locale pLocale)
	{
		if ("NO_MATCHING_YET".equals(pReason))
		{
			return RecordLineagePage.m(pLocale, "lineage.merge.reason.NO_MATCHING_YET");
		}
		if ("ADDON_NOT_AVAILABLE".equals(pReason))
		{
			return RecordLineagePage.m(pLocale, "lineage.merge.reason.ADDON_NOT_AVAILABLE");
		}
		if ("NO_METADATA".equals(pReason))
		{
			return RecordLineagePage.m(pLocale, "lineage.merge.reason.NO_METADATA");
		}
		if ("METADATA_UNREADABLE".equals(pReason))
		{
			return RecordLineagePage.m(pLocale, "lineage.merge.reason.METADATA_UNREADABLE");
		}
		return RecordLineagePage.m(pLocale, "lineage.merge.reason.fallback", pReason == null ? "" : pReason);
	}

	private static String stateWords(final String pState, final Locale pLocale)
	{
		if ("GOLDEN".equals(pState))
		{
			return RecordLineagePage.m(pLocale, "lineage.state.GOLDEN");
		}
		if ("MERGED".equals(pState))
		{
			return RecordLineagePage.m(pLocale, "lineage.state.MERGED");
		}
		if ("PENDING_APPROVAL".equals(pState))
		{
			return RecordLineagePage.m(pLocale, "lineage.state.PENDING_APPROVAL");
		}
		if ("UNSET".equals(pState))
		{
			return RecordLineagePage.m(pLocale, "lineage.state.UNSET");
		}
		if ("DELETED".equals(pState))
		{
			return RecordLineagePage.m(pLocale, "lineage.state.DELETED");
		}
		return pState == null ? "" : pState;
	}

	// ------------------------------------------------------------------------------------------------- field table

	private static void renderFieldTable(final StringBuilder pOut, final JsonNode pLineage, final Locale pLocale)
	{
		final JsonNode fields = pLineage.path("fields");
		pOut.append("<div class=\"mdmf-panel\"><h3 class=\"mdmf-subtitle\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.fields.title")))
			.append("</h3>");
		if (fields.size() == 0)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.fields.none"))).append("</p></div>");
			return;
		}
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.fields.col.field"))).append("</th><th>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.fields.col.contributions"))).append("</th><th>")
			.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.fields.col.differs"))).append("</th></tr></thead><tbody>");
		for (final JsonNode field : fields)
		{
			final JsonNode differs = field.path("differs");
			final boolean differsTrue = differs.isBoolean() && differs.asBoolean();
			pOut.append("<tr").append(differsTrue ? " class=\"mdmf-field-differs\"" : "").append("><td>").append(RecordLineagePage.esc(field.path("field").asText("")))
				.append("</td><td>");
			for (final JsonNode contribution : field.path("contributions"))
			{
				RecordLineagePage.renderContribution(pOut, contribution, pLocale);
			}
			pOut.append("</td><td>");
			if (!differs.isBoolean())
			{
				pOut.append("<span class=\"mdmf-chip mdmf-chip-target\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.fields.differs.unknown")))
					.append("</span>");
			}
			else if (differsTrue)
			{
				pOut.append("<span class=\"mdmf-chip mdmf-chip-warn\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.fields.differs.yes")))
					.append("</span>");
			}
			else
			{
				pOut.append("<span class=\"mdmf-chip mdmf-chip-real\">").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.fields.differs.no")))
					.append("</span>");
			}
			pOut.append("</td></tr>");
		}
		pOut.append("</tbody></table></div>");
		if (pLineage.hasNonNull("fieldLineageNote"))
		{
			final String note = pLineage.path("fieldLineageNote").asText("");
			if (note.startsWith(RecordLineage.MERGE_LINEAGE_UNREADABLE_PREFIX))
			{
				// F2-08: said once, above the graphic (renderLineageStatus)
			}
			else if (note.startsWith(RecordLineage.MAME_UNAVAILABLE_PREFIX))
			{
				// audit F-27: the add-on's metadata is not what this module reads - a warning, not a hint
				pOut.append("<p class=\"mdmf-notice mdmf-notice-warn\">")
					.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "mame.unavailable.summary", note.substring(RecordLineage.MAME_UNAVAILABLE_PREFIX.length()))))
					.append("</p>");
			}
			else
			{
				pOut.append("<p class=\"mdmf-hint\">").append(RecordLineagePage.esc(note)).append("</p>");
			}
		}
		pOut.append("</div>");
	}

	private static void renderContribution(final StringBuilder pOut, final JsonNode pContribution, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-contribution\"><span class=\"mdmf-chip mdmf-chip-accent\">")
			.append(RecordLineagePage.esc(pContribution.path("sourceSystem").asText(""))).append("</span> ");
		if (pContribution.path("restricted").asBoolean(false))
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-target\">")
				.append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.fields.contribution.restricted"))).append("</span></div>");
			return;
		}
		final JsonNode stagingValue = pContribution.path("stagingValue");
		final JsonNode rawValue = pContribution.path("rawValue");
		final String shown = stagingValue.isTextual() ? stagingValue.asText() : rawValue.isTextual() ? rawValue.asText() : null;
		if (shown == null)
		{
			pOut.append("<i>").append(RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.fields.contribution.noValue"))).append("</i>");
		}
		else
		{
			pOut.append(RecordLineagePage.esc(shown));
		}
		final List<String> steps = new ArrayList<String>();
		for (final JsonNode step : pContribution.path("cleaningSteps"))
		{
			steps.add(step.asText());
		}
		if (!steps.isEmpty())
		{
			pOut.append("<small>").append(RecordLineagePage.esc(RecordLineagePage.cleaningWords(steps))).append("</small>");
		}
		pOut.append("</div>");
	}

	/** Every step kind of ONE contribution, in the order the read side already carries them ({@code MappingStep.position}) - unlike {@link #cleaningSummaryForSource}, no de-duplication here: a single contribution's own pipeline is shown as-is. */
	private static String cleaningWords(final List<String> pKinds)
	{
		final StringBuilder words = new StringBuilder();
		for (final String kind : pKinds)
		{
			if (words.length() > 0)
			{
				words.append(" → ");
			}
			words.append(ProposalWording.mappingStepProposition(kind, null, null));
		}
		return words.toString();
	}

	// ------------------------------------------------------------------------------------------------------- misc

	private static String m(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		return LineageMessages.get(pLocale, pKey, pArgs);
	}

	static String esc(final String pText)
	{
		if (pText == null)
		{
			return "";
		}
		return pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}

	// ---------------------------------------------------------------------------------------------- the id chip

	/**
	 * A small, muted "&middot; {key}" chip next to a record's own title, wherever this screen names one -
	 * task brief, 2026-09-22 (coordinator review of the live "BPCE"/"HSBC BANK" screens: "show the EBX id
	 * everywhere a record is named ... small and muted, monospace, never as the main title"). {@code pKey}
	 * is already a record's own formatted primary key ({@code Adaptation.getOccurrencePrimaryKey().format()}
	 * - {@code masterKey}/{@code goldenKey}/{@code landingKey}/{@code stagingKey} everywhere in the JSON this
	 * page reads are already exactly that value, built once by {@code ebx.RecordLineageReader}; this method
	 * never computes or re-derives one, only renders whichever key a call site already has). Null/empty
	 * renders nothing, so a call site never has to guard it itself before calling. The CSS class
	 * ({@code .mdmf-id}, {@code factory/lineage.css}) carries the actual styling (monospace, muted colour);
	 * this method only ever emits the class and the tooltip text, never inline styling of its own.
	 */
	private static String idChip(final Locale pLocale, final String pKey)
	{
		if (pKey == null || pKey.isEmpty())
		{
			return "";
		}
		return "<span class=\"mdmf-id\" title=\"" + RecordLineagePage.esc(RecordLineagePage.m(pLocale, "lineage.id.tooltip")) + "\">· "
			+ RecordLineagePage.esc(pKey) + "</span>";
	}

	/** {@code pNode.path(pField).asText()} when the field is present and non-null, {@code null} otherwise - the same "hasNonNull guard, never rely on asText's own default-value semantics for a JSON null" idiom already used ad hoc at several call sites (e.g. {@code masterUrl} above); factored out once {@link #idChip} needed it at four more. */
	private static String textOrNull(final JsonNode pNode, final String pField)
	{
		return pNode.hasNonNull(pField) ? pNode.path(pField).asText() : null;
	}

	/**
	 * Companion to {@link #render} - a scroll-into-view nudge for the ONE render where the "Compare with
	 * suspects" panel ({@code id="mdmfCompare"}, {@link #renderComparisonPanel}) just became forced-open by
	 * a postback: an {@code ACCEPT_SUSPECT}/{@code REJECT_SUSPECT} click sets {@code
	 * AbstractRecordLineageUserService#pendingSuspectAction}/{@code pendingSuspectKey}, which makes {@code
	 * renderComparisonPanel}'s own {@code forceOpen} render the panel already {@code open="open"} - but a
	 * fresh server-rendered page never auto-scrolls anywhere on its own. the product owner's own report, 2026-09-22:
	 * he clicked a suspect lane, the panel opened, but he never saw the confirm prompt - which was ALSO
	 * rendered at the bottom of the panel at the time (the other half of that fix, {@link
	 * #renderComparisonPanel}'s own class comment on where the decision loop moved to).
	 * <p>
	 * Returns {@code null} whenever nothing is pending (the normal case, every OTHER render of this screen -
	 * a plain lookup, a Cancel, a Confirm that just completed) - the caller ({@code
	 * AbstractRecordLineageUserService#write}) must skip its {@code addJS_cr} call entirely then, the same
	 * "null means nothing to do" convention {@link #graphicScript} already uses for "nothing looked up yet".
	 * This is what makes the nudge run "once, only when the panel was just opened, never on every render" -
	 * there is no client-side flag to track that; the SERVER simply never emits the script except on the one
	 * render where it is true.
	 * <p>
	 * {@code block:'start'} (not {@code 'nearest'}, unlike {@code lineage-graphic.js}'s own {@code
	 * openCompare}, which only nudges a panel a suspect-lane click reveals while everything else on the page
	 * is already in view) - this is the FIRST thing the reviewer should see right after the postback, so
	 * pulling it to the very top of the viewport is deliberate. Guarded ({@code if(el&&el.scrollIntoView)})
	 * the same defensive style every other inline script on this screen uses.
	 */
	public static String pendingSuspectScrollScript(final String pPendingSuspectAction, final String pPendingSuspectKey)
	{
		if (pPendingSuspectAction == null || pPendingSuspectKey == null)
		{
			return null;
		}
		return "(function(){var el=document.getElementById('mdmfCompare');if(el&&el.scrollIntoView){el.scrollIntoView({behavior:'smooth',block:'start'});}})();";
	}
}
