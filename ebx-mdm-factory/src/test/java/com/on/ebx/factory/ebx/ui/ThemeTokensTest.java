package com.on.ebx.factory.ebx.ui;

import java.util.HashMap;
import java.util.Map;

/** JUnit-free: run the main, a failure throws. Audit F-44: the perspective's colours as CSS tokens - checking, contrast, absence. */
public final class ThemeTokensTest
{
	public static void main(final String[] pArgs)
	{
		ThemeTokensTest.sanitizeKeepsOnlyColours();
		ThemeTokensTest.noPerspectiveNoThemeNoTag();
		ThemeTokensTest.aFullThemeGivesEveryTokenInOrder();
		ThemeTokensTest.aBadValueDropsOnlyItsToken();
		ThemeTokensTest.textTokensAreReadableOnTheirBackground();
		ThemeTokensTest.aTextTokenNeverComesWithoutItsBackground();
		ThemeTokensTest.theGuideLinkCarriesTheTokensBeforeTheFragment();
		ThemeTokensTest.mainInkIsReadableOnWhite();
		System.out.println("ThemeTokensTest OK");
	}

	private static void sanitizeKeepsOnlyColours()
	{
		ThemeTokensTest.check("#rrggbb lower-cased", "#00aaff".equals(ThemeTokens.sanitize(" #00AAFF ")));
		ThemeTokensTest.check("#rgb", "#fa0".equals(ThemeTokens.sanitize("#FA0")));
		ThemeTokensTest.check("rgb() compacted", "rgb(10,20,255)".equals(ThemeTokens.sanitize("rgb( 10, 20 ,255 )")));
		ThemeTokensTest.check("named colour", "rebeccapurple".equals(ThemeTokens.sanitize("RebeccaPurple")));
		for (final String bad : new String[] { null, "", "#12345", "#1234567", "#ggg", "00aaff", "rgb(256,0,0)", "rgba(0,0,0,.5)", "hsl(0,0%,0%)", "notacolour",
			"red;}body{display:none", "#fff\"/><script>", "url(x)", "var(--x)", "expression(alert(1))", "inherit" })
		{
			ThemeTokensTest.check("refused: " + bad, ThemeTokens.sanitize(bad) == null);
		}
	}

	private static void noPerspectiveNoThemeNoTag()
	{
		ThemeTokensTest.check("null perspective", "".equals(ThemeTokens.styleTag((com.orchestranetworks.ui.perspective.Perspective) null)));
		ThemeTokensTest.check("null request", ThemeTokens.perspective(null) == null && "".equals(ThemeTokens.styleTagFor(null)));
		ThemeTokensTest.check("empty map", "".equals(ThemeTokens.styleTag(new HashMap<String, String>())));
		ThemeTokensTest.check("null map", "".equals(ThemeTokens.styleTag((Map<String, String>) null)));
		ThemeTokensTest.check("read(null) is empty", ThemeTokens.read(null).isEmpty());
		ThemeTokensTest.check("no query", "".equals(ThemeTokens.query(null)) && "u.html".equals(ThemeTokens.withQuery("u.html", null)));
	}

	private static void aFullThemeGivesEveryTokenInOrder()
	{
		final Map<String, String> raw = new HashMap<String, String>();
		raw.put(ThemeTokens.MAIN, "#00AAFF");
		raw.put(ThemeTokens.MAIN_TEXT, "#FFFFFF");
		raw.put(ThemeTokens.FOCUS, "#00DDDC");
		raw.put(ThemeTokens.HEADER, "#123456");
		raw.put(ThemeTokens.HEADER_TEXT, "#FFFFFF");
		raw.put(ThemeTokens.SECOND_HEADER, "#00DD00");
		raw.put(ThemeTokens.BUTTON, "#004499");
		raw.put(ThemeTokens.BUTTON_TEXT, "#FFFFFF");
		raw.put(ThemeTokens.TABLE_HEADER, "#F0F0F0");
		raw.put(ThemeTokens.TABLE_ODD, "#FFFFFF");
		raw.put(ThemeTokens.TABLE_EVEN, "#FAFAFA");
		raw.put(ThemeTokens.TABLE_BORDER, "#DDDDDD");
		raw.put(ThemeTokens.SELECTION, "#3366CC");
		final String tag = ThemeTokens.styleTag(raw);
		ThemeTokensTest.check("one style block on :root", tag.startsWith("<style data-ebx-theme=\"perspective\">:root{--ebx-main:#00aaff;") && tag.endsWith("}</style>"));
		int last = -1;
		for (final String name : ThemeTokens.ORDER)
		{
			final int at = tag.indexOf(name + ":");
			ThemeTokensTest.check("token " + name, at > last);
			last = at;
		}
		ThemeTokensTest.check("14 tokens", ThemeTokens.tokens(raw).size() == 14);
		ThemeTokensTest.check("the token names are the shared ones", "--ebx-main,--ebx-main-text,--ebx-main-ink,--ebx-focus,--ebx-header,--ebx-header-text,--ebx-second-header,--ebx-button,"
			.concat("--ebx-button-text,--ebx-table-header,--ebx-table-odd,--ebx-table-even,--ebx-table-border,--ebx-selection").equals(String.join(",", ThemeTokens.ORDER)));
	}

	private static void aBadValueDropsOnlyItsToken()
	{
		final Map<String, String> raw = new HashMap<String, String>();
		raw.put(ThemeTokens.MAIN, "#684bc0");
		raw.put(ThemeTokens.FOCUS, "red;}*{display:none}");
		raw.put(ThemeTokens.SELECTION, "");
		raw.put("--not-a-token", "#000");
		final String declarations = ThemeTokens.declarations(raw);
		ThemeTokensTest.check("main kept", declarations.contains("--ebx-main:#684bc0;"));
		ThemeTokensTest.check("focus dropped", !declarations.contains("--ebx-focus") && !declarations.contains("display"));
		ThemeTokensTest.check("empty selection dropped", !declarations.contains("--ebx-selection"));
		ThemeTokensTest.check("unknown name never written", !declarations.contains("--not-a-token"));
	}

	private static void textTokensAreReadableOnTheirBackground()
	{
		ThemeTokensTest.check("contrast black/white is 21", Math.abs(ThemeTokens.contrast("#000", "#fff") - 21) < 0.01);
		ThemeTokensTest.check("contrast of a non-colour is 0", ThemeTokens.contrast("nope", "#fff") == 0);
		// the theme's own text colour is kept when it reads
		ThemeTokensTest.check("white on dark kept", "#ffffff".equals(ThemeTokens.readableText("#FFFFFF", "#172033")));
		ThemeTokensTest.check("named kept when readable", "white".equals(ThemeTokens.readableText("white", "navy")));
		// replaced when it does not (white on a light yellow, a grey on grey)
		ThemeTokensTest.check("white on yellow becomes black", "#000000".equals(ThemeTokens.readableText("#ffffff", "#ffeb3b")));
		ThemeTokensTest.check("grey on grey becomes white", "#ffffff".equals(ThemeTokens.readableText("#777777", "#555555")));
		ThemeTokensTest.check("missing text colour chosen", ThemeTokens.readableText(null, "#00aaff") != null);
		ThemeTokensTest.check("garbage text colour replaced", "#000000".equals(ThemeTokens.readableText("javascript:1", "#ffffff")));
		final Map<String, String> raw = new HashMap<String, String>();
		raw.put(ThemeTokens.MAIN, "#00AAFF"); // the 6.3 schema's default main colour: a light blue
		raw.put(ThemeTokens.MAIN_TEXT, "#FFFFFF");
		raw.put(ThemeTokens.BUTTON, "#ffeb3b");
		raw.put(ThemeTokens.HEADER, "rgb(23,32,51)");
		final Map<String, String> tokens = ThemeTokens.tokens(raw);
		for (final String[] pair : new String[][] { { ThemeTokens.MAIN, ThemeTokens.MAIN_TEXT }, { ThemeTokens.BUTTON, ThemeTokens.BUTTON_TEXT },
			{ ThemeTokens.HEADER, ThemeTokens.HEADER_TEXT } })
		{
			ThemeTokensTest.check("readable " + pair[1], ThemeTokens.contrast(tokens.get(pair[1]), tokens.get(pair[0])) >= ThemeTokens.MIN_CONTRAST);
		}
	}

	private static void aTextTokenNeverComesWithoutItsBackground()
	{
		final Map<String, String> raw = new HashMap<String, String>();
		raw.put(ThemeTokens.MAIN_TEXT, "#ffffff");
		raw.put(ThemeTokens.BUTTON, "not a colour");
		raw.put(ThemeTokens.BUTTON_TEXT, "#000000");
		ThemeTokensTest.check("no orphan text token", "".equals(ThemeTokens.styleTag(raw)));
	}

	private static void theGuideLinkCarriesTheTokensBeforeTheFragment()
	{
		final Map<String, String> raw = new HashMap<String, String>();
		raw.put(ThemeTokens.MAIN, "#684BC0");
		raw.put(ThemeTokens.FOCUS, "rgb(1,2,3)");
		ThemeTokensTest.check("query", "ebx-main=%23684bc0&ebx-main-text=%23ffffff&ebx-main-ink=%23684bc0&ebx-focus=rgb%281%2C2%2C3%29".equals(ThemeTokens.query(raw)));
		final String url = ThemeTokens.withQuery("/ebx-mdm-factory/www/common/html/factory/user-guide-en.html", raw);
		ThemeTokensTest.check("query appended", url.startsWith("/ebx-mdm-factory/www/common/html/factory/user-guide-en.html?ebx-main=%23684bc0&"));
		ThemeTokensTest.check("fragment kept last", ThemeTokens.withQuery("g.html?v=1#step-2", raw).matches("g\\.html\\?v=1&ebx-main=[^#]+#step-2"));
	}

	private static void mainInkIsReadableOnWhite()
	{
		for (final String light : new String[] { "#FFEB3B", "#00AAFF", "yellow", "#fff" })
		{
			final String ink = ThemeTokens.inkOnWhite(light);
			ThemeTokensTest.check(light + " -> " + ink + " reads on white", ThemeTokens.contrast(ink, "#ffffff") >= ThemeTokens.MIN_CONTRAST);
			ThemeTokensTest.check(light + " -> " + ink + " not black", !"#000000".equals(ink));
		}
		ThemeTokensTest.check("hue of the yellow kept", ThemeTokensTest.isYellowish(ThemeTokens.inkOnWhite("#FFEB3B")));
		ThemeTokensTest.check("dark navy unchanged", "#000080".equals(ThemeTokens.inkOnWhite("#000080")) && "navy".equals(ThemeTokens.inkOnWhite("Navy")));
		ThemeTokensTest.check("the violet unchanged", "#684bc0".equals(ThemeTokens.inkOnWhite("#684BC0")));
		ThemeTokensTest.check("not a colour", ThemeTokens.inkOnWhite("nope") == null);
		final Map<String, String> raw = new HashMap<String, String>();
		raw.put(ThemeTokens.MAIN, "#00AAFF");
		raw.put(ThemeTokens.MAIN_INK, "red"); // never taken from the theme
		final String ink = ThemeTokens.tokens(raw).get(ThemeTokens.MAIN_INK);
		ThemeTokensTest.check("emitted with main, computed", ink != null && !"red".equals(ink) && ThemeTokens.contrast(ink, "#fff") >= ThemeTokens.MIN_CONTRAST);
		final Map<String, String> noMain = new HashMap<String, String>();
		noMain.put(ThemeTokens.MAIN_INK, "#000000");
		noMain.put(ThemeTokens.FOCUS, "#123456");
		ThemeTokensTest.check("not emitted without main", !ThemeTokens.tokens(noMain).containsKey(ThemeTokens.MAIN_INK));
	}

	private static boolean isYellowish(final String pHex)
	{
		final int r = Integer.parseInt(pHex.substring(1, 3), 16);
		final int g = Integer.parseInt(pHex.substring(3, 5), 16);
		final int b = Integer.parseInt(pHex.substring(5, 7), 16);
		return r > b && g > b && Math.abs(r - g) < 40;
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
