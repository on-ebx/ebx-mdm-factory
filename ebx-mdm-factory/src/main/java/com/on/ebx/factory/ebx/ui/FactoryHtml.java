package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.List;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;

/**
 * HTML/EBX-read helpers shared by the factory's screens, in the same spirit as {@code FormHtml} of
 * ebx-data-catalog: escaping and reading rows out of a {@link RequestResult}. Most of it is package-private
 * and static: the pure {@code *Page} classes never call it (they take plain values), only the {@code
 * *UserService} classes that gather those values from EBX do. {@link #jsonForScript} is the one exception -
 * public so a pure {@code *Page} class in another package (e.g. {@code lineage.RecordLineagePage}) can share
 * it too, security review 2026-09-24 finding I2.
 */
public final class FactoryHtml
{
	private FactoryHtml()
	{
	}

	/** HTML-escapes text for use in element content or a double-quoted attribute. */
	static String esc(final String pText)
	{
		if (pText == null)
		{
			return "";
		}
		return pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}

	/**
	 * Security review 2026-09-24, finding I2: escapes a JSON payload for safe embedding in a {@code var x =
	 * <json>;} statement handed to {@code UserServicePaneWriter#addJS}/{@code addJS_cr} (never a hand-written
	 * {@code <script>} tag - CLAUDE.md &sect;9). Jackson's {@code toString()} output does not escape {@code
	 * <}, {@code /} or the JSON line terminators U+2028/U+2029, so a value carrying a literal {@code
	 * </script>} (an LLM rationale, a source column label, a user comment) can close the enclosing script
	 * element early, and U+2028/U+2029 - valid JSON whitespace inside a string, but an illegal,
	 * statement-terminating line break in JS - can otherwise truncate the statement mid-value. Escaping
	 * {@code <}/{@code >}/{@code &} to their {@code \\uXXXX} forms defeats both a {@code </script>} and a
	 * {@code <!--} breakout; JSON.parse (and the browser's own JSON re-serialization) reads the escaped form
	 * back identically. Same technique {@code lineage.RecordLineagePage#graphicScript} used ad hoc before this
	 * fix - now shared so every {@code addJS}/{@code addJS_cr} call that embeds JSON goes through the one,
	 * audited helper instead of a second hand-rolled copy.
	 */
	public static String jsonForScript(final String pJson)
	{
		if (pJson == null)
		{
			return "null";
		}
		return pJson.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
	}

	/**
	 * The inside of a single-quoted JavaScript string literal (no quotes added): backslash and quote escaped, every line terminator - {@code \n},
	 * {@code \r} and the U+2028/U+2029 that JavaScript, unlike JSON, treats as one - replaced by a space ({@code \r\n} by one), and {@code <}
	 * written {@code \x3C} so that a value can never close an enclosing {@code <script>}. For text that is not known to be JS-safe: a localized
	 * label, a name, a stored value. Not an HTML escape: a caller that places the literal inside a double-quoted attribute still HTML-escapes it.
	 */
	public static String jsStringBody(final String pValue)
	{
		if (pValue == null)
		{
			return "";
		}
		final StringBuilder out = new StringBuilder(pValue.length() + 8);
		for (int i = 0; i < pValue.length(); i++)
		{
			final char c = pValue.charAt(i);
			switch (c)
			{
				case '\\':
					out.append("\\\\");
					break;
				case '\'':
					out.append("\\'");
					break;
				case '<':
					out.append("\\x3C");
					break;
				case '\r':
					if (i + 1 < pValue.length() && pValue.charAt(i + 1) == '\n')
					{
						break; // the \n that follows gives the one space
					}
					out.append(' ');
					break;
				case '\n':
				case '\u2028':
				case '\u2029':
					out.append(' ');
					break;
				default:
					out.append(c);
			}
		}
		return out.toString();
	}

	static String text(final Adaptation pRecord, final Path pPath)
	{
		if (pRecord == null)
		{
			return "";
		}
		final Object value = pRecord.get(pPath);
		return value == null ? "" : String.valueOf(value);
	}

	/**
	 * A UTF-8 text resource of this package ({@code wizard.js}, {@code combo.js}), read whole. Throws
	 * {@link IllegalStateException} when it is missing from the build.
	 */
	static String resourceText(final String pName)
	{
		try (java.io.InputStream in = FactoryHtml.class.getResourceAsStream(pName))
		{
			if (in == null)
			{
				throw new IllegalStateException("Missing " + pName + " resource");
			}
			final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			final byte[] buffer = new byte[4096];
			int n;
			while ((n = in.read(buffer)) != -1)
			{
				bytes.write(buffer, 0, n);
			}
			return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (final java.io.IOException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Second permissions pass (2026-10-14): the whole pane of a screen EBX's permissions close (dataset, record or service refused, or a project the user
	 * cannot see, answered like an unknown one) - one escaped notice, no data.
	 */
	static void writeClosed(final com.orchestranetworks.userservice.UserServicePaneWriter pWriter, final String pMessage)
	{
		pWriter.add("<div class=\"mdmf\"><div class=\"mdmf-notice mdmf-notice-warn\" role=\"alert\">").add(FactoryHtml.esc(pMessage)).add("</div></div>");
	}

	/** Drains a {@link RequestResult} into a list, closing it either way. */
	static List<Adaptation> rows(final RequestResult pRows)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		try
		{
			for (Adaptation row = pRows.nextAdaptation(); row != null; row = pRows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			pRows.close();
		}
		return out;
	}

	/** {@code 'value'}, with an embedded quote doubled, for an EBX XPath predicate. */
	static String quote(final String pValue)
	{
		return "'" + String.valueOf(pValue).replace("'", "''") + "'";
	}

	/**
	 * Wave 13 (security): the text of {@code userstore.js} - {@code window.mdmfStore}, the one place that builds a storage key. Idempotent: written once
	 * or ten times on a page, the first definition wins.
	 */
	static String userStoreLib()
	{
		String lib = FactoryHtml.userStoreCache;
		if (lib == null)
		{
			// comments and indentation left out: the wizard's inline part of the page is capped (WizardScriptLoadingTest), every byte of it counts
			lib = FactoryHtml.resourceText("userstore.js").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^[ \\t]+", "").replaceAll("(?m)[ \\t]+$", "").replaceAll("\\n{2,}", "\n").trim() + "\n";
			FactoryHtml.userStoreCache = lib;
		}
		return lib;
	}

	private static volatile String userStoreCache;

	/**
	 * Wave 13 (security): the opening of every script of ours that touches browser storage - the store library, then {@code (function(){var ST=...}} with
	 * {@code ST} = the SESSION storage of {@code pUser}, keys {@code mdmf:<user>:<name>}; the other users' keys of the module are removed on the way
	 * (CLAUDE.md &sect;9 "Browser storage in a custom EBX screen must be keyed by the EBX user"). The caller closes with {@code })();}. Empty user: an inert store.
	 */
	static String storeOpen(final String pUser)
	{
		return FactoryHtml.userStoreLib() + "(function(){var ST=window.mdmfStore.forUser(" + ProposalReviewUserService.jsString(pUser == null ? "" : pUser) + ").session;";
	}

	/** Wave 13 (security): the login of a session, "" for none - the value written in a {@code data-user} attribute and passed to {@link #storeOpen}. */
	static String userId(final com.orchestranetworks.service.Session pSession)
	{
		return pSession == null || pSession.getUserReference() == null ? "" : pSession.getUserReference().getUserId();
	}

	/** Wave 13 (security): {@code  data-user="<login>"} (leading space, escaped) for the container of a screen whose script keeps something in the browser; nothing for no user. */
	static String userAttr(final String pUser)
	{
		return pUser == null || pUser.isEmpty() ? "" : " data-user=\"" + FactoryHtml.esc(pUser) + "\"";
	}

	/**
	 * Name of the per-user sessionStorage entry ({@code mdmf:<user>:origin}, see {@link #storeOpen}) carrying where a Wizard/Cockpit/New-project link was opened from (the project
	 * explorer), so that screen's Close button can return there instead of landing on the native {@code
	 * Project} table - the coordinator measured live, 2026-09-23, that the new {@code ebx-ui} link forms in
	 * {@link FactoryLinks} open correctly inside the perspective but Close otherwise lands on the plain table.
	 * Same technique as ebx-connectors' {@code ConnectorCatalogUserService}/{@code ConnectorForm}
	 * ({@code ftccRecordOrigin}) and ebx-data-catalog's {@code FormHtml} ({@code ftdcOrigin}), simplified: no
	 * per-record id is checked back on the far side, since any of these three links always means "return to
	 * the explorer that was open a moment ago" (there is exactly one explorer, unlike the catalog's per-record
	 * card origin).
	 */
	static final String ORIGIN_KEY = "origin";

	/**
	 * Leave-guard lot (2026-09-23): marks a departure through the clicked element {@code a} as voluntary, so
	 * EBX's own {@code onbeforeunload} guard ({@code EBX_Form.hasBeenModified}, read in {@code
	 * ebx_loader.js}/{@code _ebx_global.js}, ebx-manager webapp) never shows "Quitter le site Web ?" for a
	 * full-page navigation the person chose from one of OUR links - the same exemption EBX's own buttons get
	 * via {@code EBX_Form.setParentFormDiscardModifications}. Reused verbatim by both
	 * {@link #originCaptureScript} (our own return-to-explorer links) and {@link #voluntaryLeaveScript} (every
	 * other {@code target="_top"} link of ours that does not need the origin-capture token at all) - one place,
	 * never two drifting copies. Guarded with {@code try/catch}: {@code EBX_Form} may not exist in a
	 * stand-alone/test rendering of this page. Never applied to EBX's own Close/Cancel buttons - those already
	 * set {@code ebx_isVoluntarySubmit} themselves.
	 */
	private static final String VOLUNTARY_LEAVE_JS =
		"try{EBX_Form.setParentFormDiscardModifications(a);var f=document.getElementById(EBX_Form.WorkspaceFormId);if(f){f.ebx_isVoluntarySubmit=true;}}catch(e){}";

	/**
	 * Written into the explorer page: clears any stale token first (so a Close reached from elsewhere - the
	 * perspective menu, a toolbar - is never hijacked by an old click), then arms every link matched by
	 * {@code pLinkSelector} to record the explorer's own current top-window URL just before it navigates away.
	 * {@link #originReturnScript()} reads it back on the far side. Leave-guard lot (2026-09-23): the same click
	 * also marks the departure voluntary ({@link #VOLUNTARY_LEAVE_JS}), so a genuinely modified form does not
	 * block a navigation the person chose from one of these links.
	 */
	static String originCaptureScript(final String pUser, final String pLinkSelector)
	{
		return FactoryHtml.storeOpen(pUser) + "var key='" + FactoryHtml.ORIGIN_KEY + "';ST.remove(key);"
			+ "document.querySelectorAll('" + pLinkSelector + "').forEach(function(a){"
			+ "a.addEventListener('click',function(){ST.set(key,JSON.stringify({url:window.top.location.href}));"
			+ FactoryHtml.VOLUNTARY_LEAVE_JS + "});});})();";
	}

	/**
	 * Server-paged project explorer (2026-10-11): the same capture as {@link #originCaptureScript(String)} for links that arrive after the page has
	 * loaded (the cards of each "query" answer), listened to once on the element matching {@code pRootSelector}. Does not clear the token: the
	 * page's own {@link #originCaptureScript(String)} does that once, at load.
	 */
	static String originCaptureDelegatedScript(final String pUser, final String pRootSelector, final String pLinkSelector)
	{
		return FactoryHtml.storeOpen(pUser) + "var root=document.querySelector('" + pRootSelector + "');if(!root)return;var key='" + FactoryHtml.ORIGIN_KEY + "';"
			+ "root.addEventListener('click',function(e){var a=e.target.closest?e.target.closest('" + pLinkSelector + "'):null;if(!a||!root.contains(a))return;"
			+ "ST.set(key,JSON.stringify({url:window.top.location.href}));" + FactoryHtml.VOLUNTARY_LEAVE_JS + "});})();";
	}

	/**
	 * fix/cockpit-return-to-wizard (2026-09-25): same as {@link #originCaptureScript(String)}, but the URL
	 * recorded is chosen from {@code pUrlsByStep} (index 0 = step 1) by the wizard's own hidden {@code mdmfStep}
	 * field at click time, instead of the top window's current address. Measured live the same day: EBX
	 * rewrites the wizard's address to {@code ?service=ProjectWizard&serviceMode=TABLE_VIEW} once loaded,
	 * dropping {@code serviceParams}, so returning to {@code window.top.location.href} re-opened the wizard on
	 * a NEW project. The URLs are built server-side by {@link FactoryLinks#wizardUrl(String, String, String,
	 * int)} (project + step) and rewritten to the perspective route here, like every other link of ours; with
	 * no project (empty list) or no step field, it falls back to the top window's address.
	 */
	static String originCaptureScript(final String pUser, final String pLinkSelector, final java.util.List<String> pUrlsByStep)
	{
		final StringBuilder urls = new StringBuilder("[");
		for (final String url : pUrlsByStep)
		{
			urls.append(urls.length() > 1 ? "," : "").append(ProposalReviewUserService.jsString(url));
		}
		urls.append("]");
		// the token this page was itself opened with (the explorer's) is kept as "parent" of the one recorded
		// here, so originReturnScript puts it back on the way back and the wizard's own Close still returns to
		// the explorer after a round trip through the Cockpit/Designer/Matrix (fix/cockpit-return-to-wizard)
		return FactoryHtml.storeOpen(pUser) + "var key='" + FactoryHtml.ORIGIN_KEY + "',urls=" + urls + ",parent=null;"
			+ "try{parent=JSON.parse(ST.take(key)||'null');}catch(e){}"
			+ "function target(){var f=document.getElementById('mdmfStep'),s=f?parseInt(f.value,10):0,u=s>=1&&s<=urls.length?urls[s-1]:'';"
			+ "if(!u)return window.top.location.href;var m=/\\/ui\\/custom\\/([^\\/]+)\\//.exec(window.top.location.pathname);"
			+ "if(m)u=u.split('/ui/advanced/data/').join('/ui/custom/'+m[1]+'/data/');return new URL(u,window.top.location.origin).href;}"
			+ "document.querySelectorAll('" + pLinkSelector + "').forEach(function(a){"
			+ "a.addEventListener('click',function(){ST.set(key,JSON.stringify(parent&&parent.url?{url:target(),parent:parent}:{url:target()}));"
			+ FactoryHtml.VOLUNTARY_LEAVE_JS + "});});})();";
	}

	/** Name of the per-user sessionStorage entry where the wizard keeps, per project, the origin it was opened with - see {@link #wizardOriginKeepScript}. */
	static final String WIZARD_ORIGIN_KEY = "wizardOrigin";

	/**
	 * fix/cockpit-return-to-wizard (2026-09-26), measured live: the wizard re-renders its whole page on every
	 * postback (a rail click to steps 7-8 included), and {@link #originCaptureScript(String, java.util.List)}
	 * consumes the {@link #ORIGIN_KEY} token at the first render, so after one postback its Close no longer went
	 * back to the explorer. Written BEFORE {@link #originReturnScript()} on the wizard: a token present on
	 * arrival is copied under {@link #WIZARD_ORIGIN_KEY} with the project code; a render that finds none puts the
	 * kept one back when it belongs to the same project. The project explorer clears it on load. Nothing is
	 * written for a wizard with no project yet.
	 */
	static String wizardOriginKeepScript(final String pUser, final String pProjectCode)
	{
		if (pProjectCode == null || pProjectCode.isEmpty())
		{
			return "";
		}
		return FactoryHtml.storeOpen(pUser) + "var key='" + FactoryHtml.ORIGIN_KEY + "',keep='" + FactoryHtml.WIZARD_ORIGIN_KEY + "',project="
			+ ProposalReviewUserService.jsString(pProjectCode) + ";try{var now=JSON.parse(ST.get(key)||'null');"
			+ "if(now&&now.url){ST.set(keep,JSON.stringify({project:project,origin:now}));return;}"
			+ "var kept=JSON.parse(ST.get(keep)||'null');"
			+ "if(kept&&kept.project===project&&kept.origin&&kept.origin.url){ST.set(key,JSON.stringify(kept.origin));}"
			+ "}catch(e){}})();";
	}

	/**
	 * Leave-guard lot (2026-09-23): written on a page's own full-page ({@code target="_top"}) links that do
	 * NOT go through {@link #originCaptureScript} (no "return to the explorer" token needed - a native table
	 * view, the Connectors table, a record's own URL, etc.) - only the voluntary-leave marking, so EBX's
	 * leave-page guard never fires for a navigation the person chose from one of our links. A selector matching
	 * nothing is a no-op. Never applied to EBX's own Close/Cancel buttons.
	 */
	static String voluntaryLeaveScript(final String pLinkSelector)
	{
		return "(function(){document.querySelectorAll('" + pLinkSelector + "').forEach(function(a){"
			+ "a.addEventListener('click',function(){" + FactoryHtml.VOLUNTARY_LEAVE_JS + "});});})();";
	}

	/**
	 * Written into the Wizard/Cockpit page: when {@link #originCaptureScript} left a token behind, rewires
	 * the shell's Close button (the last {@code button[type=button]} of {@code .ebx_ActionsForward}: the wizard and
	 * the cockpit both register Close with {@code setRightButtons}, and EBX renders the right-hand buttons in
	 * {@code .ebx_ActionsForward} while {@code .ebx_ActionsBack} only holds the left-hand submit and the status
	 * flag - measured live by the coordinator 2026-09-23, the first version bound the wrong bar and Close did nothing) to navigate back to the stored explorer URL and clear the token, instead of the plain EBX
	 * close action (which lands on the native table). Left entirely untouched when no token is present - a
	 * Wizard or Cockpit opened from the perspective menu or a toolbar, never from the explorer, keeps its
	 * ordinary Close. The bar is rendered asynchronously by the EBX shell, so a plain {@code MutationObserver}
	 * retries {@code bind()} until it succeeds, exactly like the two sibling scripts this one is copied from.
	 */
	static String originReturnScript(final String pUser)
	{
		return FactoryHtml.originReturnScript(pUser, null);
	}

	/**
	 * Staging load rejects (2026-10-17): an in-page "back" link that goes where the screen was opened from, like the Close button ({@link #originReturnScript}) already does. The
	 * link ({@code pLinkSelector}) is rendered pointing at its default (the Cockpit); when the per-user origin token holds an address of this EBX
	 * ({@code /ebx-ui/}), the link is pointed at it, worded from its own {@code data-label-wizard} (the origin is the wizard) or {@code data-label-back} (any other origin; an
	 * origin that is the Cockpit keeps the default wording), and a click gives the token back to its parent (or clears it), as Close does. No token: nothing changes.
	 */
	static String originBackLinkScript(final String pUser, final String pLinkSelector)
	{
		return FactoryHtml.storeOpen(pUser) + "var key='" + FactoryHtml.ORIGIN_KEY + "',a=document.querySelector('" + pLinkSelector + "');if(!a)return;var state=null;"
			+ "try{state=JSON.parse(ST.get(key)||'null');}catch(e){}if(!state||!state.url)return;var topWindow=window.top,target;"
			+ "try{target=new URL(state.url,topWindow.location.origin);}catch(e){return;}"
			+ "if(target.origin!==topWindow.location.origin||target.pathname.indexOf('/ebx-ui/')!==0)return;"
			+ "a.setAttribute('href',target.href);"
			+ "if(target.href.indexOf('service=FactoryCockpit')<0){var label=a.getAttribute(target.href.indexOf('service=ProjectWizard')>=0?'data-label-wizard':'data-label-back');if(label){a.textContent=label;}}"
			+ "a.addEventListener('click',function(){if(state.parent&&state.parent.url){ST.set(key,JSON.stringify(state.parent));}else{ST.remove(key);}" + FactoryHtml.VOLUNTARY_LEAVE_JS
			+ "});})();";
	}

	/**
	 * fix/matrix-close-to-wizard (2026-10-07, reported live: Close on the Mapping matrix left the wizard): same as
	 * {@link #originReturnScript()}, but with no token present Close goes to {@code pFallbackUrl} (rewritten into
	 * the current perspective, like every other link of ours) instead of EBX's plain close action. For a screen
	 * that always belongs to a project's wizard step (the Mapping matrix), whatever link opened it - a link
	 * forgetting the capture class, a bookmark, a reload in another tab. {@code null}/empty: no fallback.
	 */
	static String originReturnScript(final String pUser, final String pFallbackUrl)
	{
		final String fallback = pFallbackUrl == null || pFallbackUrl.isEmpty() ? "null" : ProposalReviewUserService.jsString(pFallbackUrl);
		return FactoryHtml.storeOpen(pUser) + "if(document.documentElement.dataset.mdmfReturnBound)return;"
			+ "var key='" + FactoryHtml.ORIGIN_KEY + "',topWindow=window.top,state=null,fallback=" + fallback + ";try{state=JSON.parse(ST.get(key)||'null');}catch(e){ST.remove(key);}"
			+ "if((!state||!state.url)&&fallback){var m=/\\/ui\\/custom\\/([^\\/]+)\\//.exec(topWindow.location.pathname);"
			+ "state={url:m?fallback.split('/ui/advanced/data/').join('/ui/custom/'+m[1]+'/data/'):fallback};}"
			+ "if(!state||!state.url)return;var target;try{target=new URL(state.url,topWindow.location.origin);}catch(e){ST.remove(key);return;}"
			+ "if(target.origin!==topWindow.location.origin||target.pathname.indexOf('/ebx-ui/')!==0)return;"
			+ "document.documentElement.dataset.mdmfReturnBound='true';"
			+ "function bind(){var bar=document.querySelector('.ebx_ActionsForward');if(!bar)return false;"
			+ "var buttons=bar.querySelectorAll('button[type=button]');if(!buttons.length)return false;"
			+ "var close=buttons[buttons.length-1];close.addEventListener('click',function(event){"
			+ "event.preventDefault();event.stopImmediatePropagation();"
			+ "if(state.parent&&state.parent.url){ST.set(key,JSON.stringify(state.parent));}else{ST.remove(key);}"
			+ "topWindow.location.href=target.href;},true);return true;}"
			+ "if(!bind()){var observer=new MutationObserver(function(){if(bind())observer.disconnect();});"
			+ "observer.observe(document.documentElement,{childList:true,subtree:true});}})();";
	}

	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug item 2): rewrites {@code ui/advanced/data/} to {@code
	 * ui/custom/&lt;perspectiveKey&gt;/data/} on EVERY {@code /ebx-ui/...} link of the page, the same
	 * perspective-preserving rewrite {@code ProjectExplorerUserService}/{@code ProjectWizardUserService} already
	 * apply to their own static links (CLAUDE.md &sect;9's own "the ebx-ui routes drop plain URL parameters" /
	 * "never open an /ebx-ui/... route inside the modal iframe" notes) - but as a document-level, CAPTURE-phase
	 * click listener instead of a one-shot {@code querySelectorAll} at page-load time. The Mapping matrix and
	 * the Feed designer both build at least one of their own navigation links (the Matrix|Designer switch,
	 * {@code MatrixHtml}'s "Open this feed in the Designer" entity link) from JavaScript AFTER an AJAX response,
	 * well after page load - a one-shot rewrite would simply never see them. A click listener sees every link
	 * at the moment it is actually clicked, whenever it was added to the DOM, so ONE call covers the switch,
	 * every entity/feed link, and any link a future screen adds the same way - no second wiring needed as the
	 * screen grows. Rewriting the href in a capture-phase listener (before the browser's own default navigation
	 * runs) is enough to redirect the click; it never calls preventDefault, so a plain click, a modifier-click
	 * (open in new tab) and the browser's own "open in new tab" context-menu entry (which reads the CURRENT
	 * href at menu-open time, unaffected by this listener) all keep working normally.
	 */
	static String perspectiveLinkRewriteScript()
	{
		return "(function(){document.addEventListener('click',function(e){"
			+ "var a=e.target&&e.target.closest?e.target.closest('a[href*=\"/ui/advanced/data/\"]'):null;if(!a)return;"
			+ "var m=/\\/ui\\/custom\\/([^\\/]+)\\//.exec(window.top.location.pathname);if(!m)return;"
			+ "var o='/ui/advanced/data/',n='/ui/custom/'+m[1]+'/data/';"
			+ "a.setAttribute('href',a.getAttribute('href').split(o).join(n));"
			+ "},true);})();";
	}
}
