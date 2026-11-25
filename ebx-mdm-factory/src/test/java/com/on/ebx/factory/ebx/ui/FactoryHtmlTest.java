package com.on.ebx.factory.ebx.ui;

/**
 * JUnit-free: run the main, a failure throws. Leave-guard lot (2026-09-23): the CONTRACT between {@link
 * FactoryHtml#originCaptureScript} / {@link FactoryHtml#voluntaryLeaveScript} and EBX's own {@code
 * onbeforeunload} guard ({@code EBX_Form.hasBeenModified}, read in {@code ebx_loader.js}/{@code
 * _ebx_global.js}, ebx-manager webapp) - every one of our own full-page ({@code target="_top"}) links must
 * mark its departure voluntary ({@code EBX_Form.setParentFormDiscardModifications} +
 * {@code ebx_isVoluntarySubmit=true}) so a genuinely modified form never blocks a navigation the person chose
 * from one of OUR links - the same exemption EBX's own buttons already get. Same package as {@code
 * FactoryHtml} on purpose, to reach its package-private methods directly.
 */
public final class FactoryHtmlTest
{
	public static void main(final String[] pArgs)
	{
		FactoryHtmlTest.originCaptureScriptAlsoMarksTheDepartureVoluntary();
		FactoryHtmlTest.originCaptureScriptWithStepUrlsRecordsTheBuiltUrlNotTheTopAddress();
		FactoryHtmlTest.wizardOriginKeepScriptKeepsTheOriginPerProject();
		FactoryHtmlTest.voluntaryLeaveScriptMarksTheDepartureVoluntaryWithoutTheOriginToken();
		FactoryHtmlTest.neitherScriptEverTouchesEbxOwnButtonBar();
		FactoryHtmlTest.perspectiveLinkRewriteScriptIsClickDelegatedNeverPreventsDefault();
		FactoryHtmlTest.jsonForScriptNeverLetsAScriptCloseTagThrough();
		FactoryHtmlTest.jsonForScriptEscapesJsonLineTerminators();
		FactoryHtmlTest.jsonForScriptNullIsTheLiteralJsonNull();
		FactoryHtmlTest.jsStringBodyKeepsAnyValueInsideItsLiteral();
		System.out.println("FactoryHtmlTest OK");
	}

	/** A value in a single-quoted JS literal can neither end the literal, nor end the statement (any JS line terminator, U+2028/U+2029 included), nor close a script element. */
	private static void jsStringBodyKeepsAnyValueInsideItsLiteral()
	{
		final String hostile = "it's a \\ test\r\nline2\nline3\r\u2028\u2029</script><b>";
		final String body = FactoryHtml.jsStringBody(hostile);
		FactoryHtmlTest.check("no raw line terminator survives", body.indexOf('\n') < 0 && body.indexOf('\r') < 0 && body.indexOf('\u2028') < 0 && body.indexOf('\u2029') < 0);
		FactoryHtmlTest.check("no raw angle bracket opens a tag", body.indexOf('<') < 0 && body.contains("\\x3C/script>"));
		FactoryHtmlTest.check("the quote and the backslash are escaped", body.startsWith("it\\'s a \\\\ test"));
		FactoryHtmlTest.check("CRLF is one space, each other terminator one space", body.contains("test line2 line3   \\x3C/script>\\x3Cb>"));
		FactoryHtmlTest.check("null is empty", "".equals(FactoryHtml.jsStringBody(null)));
		FactoryHtmlTest.check("plain text is untouched", "Hello world 12".equals(FactoryHtml.jsStringBody("Hello world 12")));
	}

	/**
	 * Security review 2026-09-24, finding I2: a JSON payload embedded in a {@code var x = <json>;} statement
	 * (handed to {@code UserServicePaneWriter#addJS}/{@code addJS_cr}) must never let a literal {@code
	 * </script>} - an LLM rationale, a source column label, a user comment can all carry one - close the
	 * enclosing {@code <script>} element early. Escaping {@code <}/{@code >} to {@code <}/{@code >}
	 * defeats it while leaving the JSON value itself intact once JSON.parse'd (or read back with {@code
	 * JSON.stringify(data)} for a round trip, since {@code <} decodes to the same {@code <} character).
	 */
	private static void jsonForScriptNeverLetsAScriptCloseTagThrough()
	{
		final String payload = "{\"rationale\":\"</script><script>alert(1)</script>\",\"col\":\"<img src=x onerror=alert(1)>\"}";
		final String safe = FactoryHtml.jsonForScript(payload);
		FactoryHtmlTest.check("no literal </script> survives", !safe.contains("</script>"));
		FactoryHtmlTest.check("no literal <script survives either (a naive close-tag-only filter would miss this)", !safe.contains("<script"));
		FactoryHtmlTest.check("no literal <img ... onerror= survives", !safe.contains("<img"));
		FactoryHtmlTest.check("< is escaped to \\u003c", safe.contains("\\u003cscript\\u003e"));
		FactoryHtmlTest.check("> is escaped to \\u003e", safe.contains("\\u003e"));
		FactoryHtmlTest.check("the JSON value survives, just escaped - not dropped or corrupted",
			safe.contains("rationale") && safe.contains("alert(1)") && safe.contains("onerror="));
	}

	/** JSON line terminators U+2028/U+2029 are valid whitespace inside a JSON string but an illegal, statement-terminating line break inside a plain JS string - both must be escaped too. */
	private static void jsonForScriptEscapesJsonLineTerminators()
	{
		final String payload = "{\"note\":\"line1 line2 line3\"}";
		final String safe = FactoryHtml.jsonForScript(payload);
		FactoryHtmlTest.check("U+2028 is escaped", !safe.contains(" ") && safe.contains("\\u2028"));
		FactoryHtmlTest.check("U+2029 is escaped", !safe.contains(" ") && safe.contains("\\u2029"));
	}

	private static void jsonForScriptNullIsTheLiteralJsonNull()
	{
		FactoryHtmlTest.check("a null payload becomes the JS literal null, not the 4-letter string \"null\" or an NPE",
			"null".equals(FactoryHtml.jsonForScript(null)));
	}

	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug item 2): {@link FactoryHtml#perspectiveLinkRewriteScript} is a
	 * document-level, capture-phase click listener - never a one-shot {@code querySelectorAll} at page-load time
	 * - so it also catches a link the Matrix/Designer screens build with their own JS well after page load (the
	 * Matrix|Designer switch, an entity's "Open in the Designer" link). It must never call preventDefault (a
	 * plain click, a modifier-click and the browser's own "open in new tab" must all keep working).
	 */
	private static void perspectiveLinkRewriteScriptIsClickDelegatedNeverPreventsDefault()
	{
		final String script = FactoryHtml.perspectiveLinkRewriteScript();
		FactoryHtmlTest.check("listens on the document, not a one-shot element query at render time", script.contains("document.addEventListener('click'"));
		FactoryHtmlTest.check("registered in the CAPTURE phase (fires before the link's own default navigation)", script.trim().endsWith("},true);})();"));
		FactoryHtmlTest.check("matches any /ui/advanced/data/ link, wherever/whenever it was added to the DOM",
			script.contains("e.target.closest('a[href*=\"/ui/advanced/data/\"]')"));
		FactoryHtmlTest.check("only rewrites when the page itself is inside a perspective (ui/custom/<key>/)",
			script.contains("/\\/ui\\/custom\\/([^\\/]+)\\//.exec(window.top.location.pathname)"));
		FactoryHtmlTest.check("rewrites the href in place", script.contains("a.setAttribute('href'"));
		FactoryHtmlTest.check("never calls preventDefault - a plain click/modifier-click/new-tab must still work", !script.contains("preventDefault"));
	}

	/** fix/cockpit-return-to-wizard (2026-09-25): EBX drops serviceParams from the wizard's address, so the recorded URL is the server-built one for the current step. */
	private static void originCaptureScriptWithStepUrlsRecordsTheBuiltUrlNotTheTopAddress()
	{
		final String url1 = FactoryLinks.wizardUrl("MdmFactory", "MdmFactory", "A", 1);
		final String url2 = FactoryLinks.wizardUrl("MdmFactory", "MdmFactory", "A", 2);
		final String script = FactoryHtml.originCaptureScript("alice", "a[href*=\"service=FactoryCockpit\"]", java.util.Arrays.asList(url1, url2));
		FactoryHtmlTest.check("embeds one URL per step, in order", script.contains("urls=['" + url1 + "','" + url2 + "']"));
		FactoryHtmlTest.check("picks the URL from the wizard's own step field", script.contains("document.getElementById('mdmfStep')"));
		FactoryHtmlTest.check("records the chosen URL", script.contains("{url:target()}"));
		FactoryHtmlTest.check("keeps the perspective route", script.contains("'/ui/custom/'+m[1]+'/data/'"));
		FactoryHtmlTest.check("still marks the departure voluntary", script.contains("EBX_Form.setParentFormDiscardModifications(a)"));
		FactoryHtmlTest.check("keeps the token it was opened with as parent", script.contains("{url:target(),parent:parent}"));
		FactoryHtmlTest.check("the return puts the parent token back", FactoryHtml.originReturnScript("alice").contains("ST.set(key,JSON.stringify(state.parent))"));
		FactoryHtmlTest.check("no project: falls back to the top address", FactoryHtml.originCaptureScript("alice", ".x", java.util.Collections.<String>emptyList()).contains("urls=[]"));
	}

	/** fix/cockpit-return-to-wizard (2026-09-26): the wizard's explorer origin must survive its own postbacks, for its own project only. */
	private static void wizardOriginKeepScriptKeepsTheOriginPerProject()
	{
		final String script = FactoryHtml.wizardOriginKeepScript("alice", "A'B");
		FactoryHtmlTest.check("the project code is a safe JS string", script.contains("project='A\\'B'"));
		FactoryHtmlTest.check("a token present on arrival is kept with the project", script.contains("JSON.stringify({project:project,origin:now})"));
		FactoryHtmlTest.check("the kept token is put back only for the same project", script.contains("kept.project===project"));
		FactoryHtmlTest.check("no project: nothing written", FactoryHtml.wizardOriginKeepScript("alice", null).isEmpty() && FactoryHtml.wizardOriginKeepScript("alice", "").isEmpty());
	}

	private static void originCaptureScriptAlsoMarksTheDepartureVoluntary()
	{
		final String script = FactoryHtml.originCaptureScript("alice", ".mdmf-feed-designer-link");
		FactoryHtmlTest.check("arms the given selector", script.contains("querySelectorAll('.mdmf-feed-designer-link')"));
		FactoryHtmlTest.check("still records the return-to-explorer token", script.contains("ST.set(key"));
		FactoryHtmlTest.check("also discards EBX's own modification tracking for the clicked link's form",
			script.contains("EBX_Form.setParentFormDiscardModifications(a)"));
		FactoryHtmlTest.check("also flags the departure voluntary on the workspace form itself",
			script.contains("document.getElementById(EBX_Form.WorkspaceFormId)") && script.contains("f.ebx_isVoluntarySubmit=true"));
		FactoryHtmlTest.check("the voluntary marking is guarded (EBX_Form may not exist in a test rendering)",
			script.contains("try{EBX_Form.setParentFormDiscardModifications(a)"));
	}

	private static void voluntaryLeaveScriptMarksTheDepartureVoluntaryWithoutTheOriginToken()
	{
		final String script = FactoryHtml.voluntaryLeaveScript(".mdmf-leave-link");
		FactoryHtmlTest.check("arms the given selector", script.contains("querySelectorAll('.mdmf-leave-link')"));
		FactoryHtmlTest.check("marks the departure voluntary, same as originCaptureScript",
			script.contains("EBX_Form.setParentFormDiscardModifications(a)") && script.contains("f.ebx_isVoluntarySubmit=true"));
		FactoryHtmlTest.check("never touches sessionStorage - no return-to-explorer token needed for these links",
			!script.contains("sessionStorage"));
	}

	private static void neitherScriptEverTouchesEbxOwnButtonBar()
	{
		// the origin-capture/voluntary-leave scripts only ever arm <a> elements matched by the caller's own
		// selector - EBX's own Close/Cancel buttons are never part of that selector in any caller (WizardPage/
		// CockpitPage/ProjectExplorerPage/ProjectDeletePage each pass their OWN link classes, never a generic
		// "button" selector) - this test documents the contract rather than re-deriving every caller's selector.
		final String capture = FactoryHtml.originCaptureScript("alice", ".mdmc-btn, .mdmx-newproject");
		final String leave = FactoryHtml.voluntaryLeaveScript(".mdmf-leave-link");
		FactoryHtmlTest.check("originCaptureScript only queries elements matched by the caller's own selector, never EBX's own button bar",
			capture.contains("document.querySelectorAll('.mdmc-btn, .mdmx-newproject')") && !capture.contains("ebx_ActionsForward"));
		FactoryHtmlTest.check("voluntaryLeaveScript only queries elements matched by the caller's own selector, never EBX's own button bar",
			leave.contains("document.querySelectorAll('.mdmf-leave-link')") && !leave.contains("ebx_ActionsForward"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
