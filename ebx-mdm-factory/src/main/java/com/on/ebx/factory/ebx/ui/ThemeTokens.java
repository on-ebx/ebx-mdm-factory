package com.on.ebx.factory.ebx.ui;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orchestranetworks.ui.perspective.Perspective;
import com.orchestranetworks.ui.perspective.PerspectiveTheme;
import com.orchestranetworks.userservice.UserServiceRequest;

/**
 * Audit F-44: the colours of the EBX perspective that displays a Factory screen, as CSS custom properties. Each screen writes
 * {@link #styleTag(Perspective)} before its own markup; its stylesheets read the tokens through {@code var(--ebx-main,
 * <the Factory's own colour>)}, so a screen shown without a perspective (or with a theme that leaves a colour out) looks as
 * before. The token names are the same in the three modules (Connectors, catalog, Factory):
 * <ul>
 * <li>{@code --ebx-main} / {@code --ebx-main-text} - {@link PerspectiveTheme#getMainColor()} / {@link PerspectiveTheme#getMainTextIconColor()}</li>
 * <li>{@code --ebx-main-ink} - the main colour darkened (HSL lightness, hue and saturation kept) until its contrast on white reaches 4.5,
 * unchanged when it already does: the accent used as text or as a thin line on a light background (links, chip text)</li>
 * <li>{@code --ebx-focus} - {@link PerspectiveTheme#getFocusColor()}</li>
 * <li>{@code --ebx-header} / {@code --ebx-header-text} - {@link PerspectiveTheme#getHeaderColor()} / {@link PerspectiveTheme#getHeaderTextColor()}</li>
 * <li>{@code --ebx-second-header} - {@link PerspectiveTheme#getSecondHeaderColor()}</li>
 * <li>{@code --ebx-button} / {@code --ebx-button-text} - {@link PerspectiveTheme#getButtonsHighlightColor()} / {@link PerspectiveTheme#getButtonsHighlightTextColor()}</li>
 * <li>{@code --ebx-table-header}, {@code --ebx-table-odd}, {@code --ebx-table-even} - the table header and row backgrounds</li>
 * <li>{@code --ebx-table-border} - {@link PerspectiveTheme#getTableBodyInnerHorizontalBordersColor()} (the outer border when it is missing)</li>
 * <li>{@code --ebx-selection} - {@link PerspectiveTheme#getPerspectiveNavigationPaneSelectionColor()}</li>
 * </ul>
 * Every value is checked before it reaches the page: only {@code #rgb}, {@code #rrggbb}, {@code rgb(r,g,b)} and the CSS named colours
 * pass; anything else drops the token (and its fallback applies). A text token is the theme's own text colour when it reads on its
 * background with a WCAG contrast of at least 4.5, black or white otherwise (whichever reads better), and is never written without its
 * background. Checked on 6.3.0-rc2 by {@code javap}: the theme answers {@code #RRGGBB} strings, and the schema default when a
 * perspective does not set a colour.
 */
public final class ThemeTokens
{
	public static final String MAIN = "--ebx-main";
	public static final String MAIN_TEXT = "--ebx-main-text";
	/** The main colour darkened until it reads as text on white (computed, not a theme getter). */
	public static final String MAIN_INK = "--ebx-main-ink";
	public static final String FOCUS = "--ebx-focus";
	public static final String HEADER = "--ebx-header";
	public static final String HEADER_TEXT = "--ebx-header-text";
	public static final String SECOND_HEADER = "--ebx-second-header";
	public static final String BUTTON = "--ebx-button";
	public static final String BUTTON_TEXT = "--ebx-button-text";
	public static final String TABLE_HEADER = "--ebx-table-header";
	public static final String TABLE_ODD = "--ebx-table-odd";
	public static final String TABLE_EVEN = "--ebx-table-even";
	public static final String TABLE_BORDER = "--ebx-table-border";
	public static final String SELECTION = "--ebx-selection";

	/** Emission order of the tokens (stable output, easier to read in the page). */
	static final String[] ORDER = { ThemeTokens.MAIN, ThemeTokens.MAIN_TEXT, ThemeTokens.MAIN_INK, ThemeTokens.FOCUS, ThemeTokens.HEADER, ThemeTokens.HEADER_TEXT,
		ThemeTokens.SECOND_HEADER, ThemeTokens.BUTTON, ThemeTokens.BUTTON_TEXT, ThemeTokens.TABLE_HEADER, ThemeTokens.TABLE_ODD, ThemeTokens.TABLE_EVEN,
		ThemeTokens.TABLE_BORDER, ThemeTokens.SELECTION };

	/** WCAG AA for normal text. */
	static final double MIN_CONTRAST = 4.5;

	private static final Pattern HEX = Pattern.compile("#([0-9a-f]{3}|[0-9a-f]{6})");
	private static final Pattern RGB = Pattern.compile("rgb\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\)");
	private static final Map<String, String> NAMED = ThemeTokens.namedColours();

	private ThemeTokens()
	{
	}

	/** The perspective that displays a user service, {@code null} when there is none or it cannot be read. */
	public static Perspective perspective(final UserServiceRequest pRequest)
	{
		try
		{
			return pRequest == null ? null : pRequest.getCurrentPerspective();
		}
		catch (final RuntimeException | LinkageError ex)
		{
			return null;
		}
	}

	/** {@link #styleTag(Perspective)} of the perspective that displays a user service: what every Factory pane writes first. */
	public static String styleTagFor(final UserServiceRequest pRequest)
	{
		return ThemeTokens.styleTag(ThemeTokens.perspective(pRequest));
	}

	/**
	 * The {@code <style>} block for a perspective: empty when there is no perspective, no theme, or no usable colour. Never throws: a
	 * theme that cannot be read leaves the screen on its own colours.
	 */
	public static String styleTag(final Perspective pPerspective)
	{
		return ThemeTokens.styleTag(ThemeTokens.read(pPerspective));
	}

	/** The {@code <style>} block for raw values keyed by token name (what {@link #read} answers). Pure. */
	public static String styleTag(final Map<String, String> pRaw)
	{
		final String declarations = ThemeTokens.declarations(pRaw);
		return declarations.isEmpty() ? "" : "<style data-ebx-theme=\"perspective\">:root{" + declarations + "}</style>";
	}

	/** The checked tokens, {@code --ebx-main:#00aaff;...}, in {@link #ORDER}. Pure. */
	public static String declarations(final Map<String, String> pRaw)
	{
		final StringBuilder out = new StringBuilder();
		for (final Map.Entry<String, String> token : ThemeTokens.tokens(pRaw).entrySet())
		{
			out.append(token.getKey()).append(':').append(token.getValue()).append(';');
		}
		return out.toString();
	}

	/**
	 * The query string that hands the same tokens to a static page (the user guide), {@code ebx-main=%23684bc0&...}; empty when there is
	 * nothing to hand. The page checks each value again before using it. Pure.
	 */
	public static String query(final Map<String, String> pRaw)
	{
		final StringBuilder out = new StringBuilder();
		for (final Map.Entry<String, String> token : ThemeTokens.tokens(pRaw).entrySet())
		{
			if (out.length() > 0)
			{
				out.append('&');
			}
			out.append(token.getKey().substring(2)).append('=').append(ThemeTokens.encode(token.getValue()));
		}
		return out.toString();
	}

	/** {@code pUrl} with the tokens of {@code pRaw} as its query (before any fragment). Pure. */
	public static String withQuery(final String pUrl, final Map<String, String> pRaw)
	{
		final String query = ThemeTokens.query(pRaw);
		if (pUrl == null || pUrl.isEmpty() || query.isEmpty())
		{
			return pUrl;
		}
		final int hash = pUrl.indexOf('#');
		final String base = hash < 0 ? pUrl : pUrl.substring(0, hash);
		final String fragment = hash < 0 ? "" : pUrl.substring(hash);
		return base + (base.indexOf('?') < 0 ? '?' : '&') + query + fragment;
	}

	/**
	 * The checked tokens, in {@link #ORDER}: a background token whose value does not pass {@link #sanitize} is left out; a text token is
	 * written only with its background, and only readable (see the class comment). Pure.
	 */
	public static Map<String, String> tokens(final Map<String, String> pRaw)
	{
		final Map<String, String> clean = new HashMap<String, String>();
		if (pRaw != null)
		{
			for (final Map.Entry<String, String> entry : pRaw.entrySet())
			{
				final String value = ThemeTokens.sanitize(entry.getValue());
				if (value != null)
				{
					clean.put(entry.getKey(), value);
				}
			}
		}
		ThemeTokens.textOn(clean, pRaw, ThemeTokens.MAIN, ThemeTokens.MAIN_TEXT);
		clean.remove(ThemeTokens.MAIN_INK); // computed only, never taken from the caller
		if (clean.containsKey(ThemeTokens.MAIN))
		{
			clean.put(ThemeTokens.MAIN_INK, ThemeTokens.inkOnWhite(clean.get(ThemeTokens.MAIN)));
		}
		ThemeTokens.textOn(clean, pRaw, ThemeTokens.HEADER, ThemeTokens.HEADER_TEXT);
		ThemeTokens.textOn(clean, pRaw, ThemeTokens.BUTTON, ThemeTokens.BUTTON_TEXT);
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (final String name : ThemeTokens.ORDER)
		{
			if (clean.containsKey(name))
			{
				out.put(name, clean.get(name));
			}
		}
		return Collections.unmodifiableMap(out);
	}

	private static void textOn(final Map<String, String> pClean, final Map<String, String> pRaw, final String pBackground, final String pText)
	{
		final String background = pClean.get(pBackground);
		if (background == null)
		{
			pClean.remove(pText);
			return;
		}
		pClean.put(pText, ThemeTokens.readableText(pRaw == null ? null : pRaw.get(pText), background));
	}

	/**
	 * A text colour that reads on {@code pBackground} (a checked colour): {@code pPreferred} when it passes {@link #sanitize} and reaches
	 * {@link #MIN_CONTRAST}, otherwise {@code #000000} or {@code #ffffff}, whichever contrasts more. Pure.
	 */
	public static String readableText(final String pPreferred, final String pBackground)
	{
		final int[] background = ThemeTokens.rgb(ThemeTokens.sanitize(pBackground));
		if (background == null)
		{
			return null;
		}
		final String preferred = ThemeTokens.sanitize(pPreferred);
		final int[] text = ThemeTokens.rgb(preferred);
		if (text != null && ThemeTokens.contrast(text, background) >= ThemeTokens.MIN_CONTRAST)
		{
			return preferred;
		}
		return ThemeTokens.contrast(new int[] { 0, 0, 0 }, background) >= ThemeTokens.contrast(new int[] { 255, 255, 255 }, background) ? "#000000" : "#ffffff";
	}

	/**
	 * {@code pColour} darkened in HSL lightness (hue and saturation kept) until its contrast on white reaches {@link #MIN_CONTRAST};
	 * returned as given (checked) when it already does, as {@code #rrggbb} otherwise; {@code null} for a value that is not a colour. Pure.
	 */
	public static String inkOnWhite(final String pColour)
	{
		final String clean = ThemeTokens.sanitize(pColour);
		final int[] rgb = ThemeTokens.rgb(clean);
		if (rgb == null)
		{
			return null;
		}
		final int[] white = { 255, 255, 255 };
		if (ThemeTokens.contrast(rgb, white) >= ThemeTokens.MIN_CONTRAST)
		{
			return clean;
		}
		final double[] hsl = ThemeTokens.toHsl(rgb);
		for (double l = hsl[2]; l > 0; l -= 0.005)
		{
			final int[] candidate = ThemeTokens.fromHsl(hsl[0], hsl[1], Math.max(0, l));
			if (ThemeTokens.contrast(candidate, white) >= ThemeTokens.MIN_CONTRAST)
			{
				return String.format(Locale.ROOT, "#%02x%02x%02x", Integer.valueOf(candidate[0]), Integer.valueOf(candidate[1]), Integer.valueOf(candidate[2]));
			}
		}
		return "#000000";
	}

	private static double[] toHsl(final int[] pRgb)
	{
		final double r = pRgb[0] / 255.0;
		final double g = pRgb[1] / 255.0;
		final double b = pRgb[2] / 255.0;
		final double max = Math.max(r, Math.max(g, b));
		final double min = Math.min(r, Math.min(g, b));
		final double l = (max + min) / 2;
		if (max == min)
		{
			return new double[] { 0, 0, l };
		}
		final double d = max - min;
		final double s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
		final double h = max == r ? (g - b) / d + (g < b ? 6 : 0) : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
		return new double[] { h / 6, s, l };
	}

	private static int[] fromHsl(final double pH, final double pS, final double pL)
	{
		if (pS == 0)
		{
			final int v = (int) Math.round(pL * 255);
			return new int[] { v, v, v };
		}
		final double q = pL < 0.5 ? pL * (1 + pS) : pL + pS - pL * pS;
		final double p = 2 * pL - q;
		return new int[] { (int) Math.round(ThemeTokens.hue(p, q, pH + 1.0 / 3) * 255), (int) Math.round(ThemeTokens.hue(p, q, pH) * 255),
			(int) Math.round(ThemeTokens.hue(p, q, pH - 1.0 / 3) * 255) };
	}

	private static double hue(final double pP, final double pQ, final double pT)
	{
		double t = pT;
		if (t < 0)
		{
			t += 1;
		}
		if (t > 1)
		{
			t -= 1;
		}
		if (t < 1.0 / 6)
		{
			return pP + (pQ - pP) * 6 * t;
		}
		if (t < 1.0 / 2)
		{
			return pQ;
		}
		if (t < 2.0 / 3)
		{
			return pP + (pQ - pP) * (2.0 / 3 - t) * 6;
		}
		return pP;
	}

	/** WCAG 2 contrast ratio of two checked colours, 1 to 21; 0 when one of them is not a colour. Pure. */
	public static double contrast(final String pA, final String pB)
	{
		final int[] a = ThemeTokens.rgb(ThemeTokens.sanitize(pA));
		final int[] b = ThemeTokens.rgb(ThemeTokens.sanitize(pB));
		return a == null || b == null ? 0 : ThemeTokens.contrast(a, b);
	}

	private static double contrast(final int[] pA, final int[] pB)
	{
		final double la = ThemeTokens.luminance(pA);
		final double lb = ThemeTokens.luminance(pB);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double luminance(final int[] pRgb)
	{
		final double[] c = new double[3];
		for (int i = 0; i < 3; i++)
		{
			final double v = pRgb[i] / 255.0;
			c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
		}
		return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
	}

	/**
	 * The colour in a canonical, safe form ({@code #rgb}/{@code #rrggbb} lower-cased, {@code rgb(r,g,b)} without spaces, a known CSS
	 * colour name lower-cased), or {@code null} for anything else - a value that is not one of those never reaches a page. Pure.
	 */
	public static String sanitize(final String pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		final String value = pValue.trim().toLowerCase(Locale.ROOT);
		if (ThemeTokens.HEX.matcher(value).matches())
		{
			return value;
		}
		final Matcher rgb = ThemeTokens.RGB.matcher(value);
		if (rgb.matches())
		{
			final int r = Integer.parseInt(rgb.group(1));
			final int g = Integer.parseInt(rgb.group(2));
			final int b = Integer.parseInt(rgb.group(3));
			return r > 255 || g > 255 || b > 255 ? null : "rgb(" + r + "," + g + "," + b + ")";
		}
		return ThemeTokens.NAMED.containsKey(value) ? value : null;
	}

	/** The red, green and blue of a value {@link #sanitize} accepted; {@code null} otherwise. Pure. */
	static int[] rgb(final String pClean)
	{
		if (pClean == null)
		{
			return null;
		}
		String hex = null;
		if (pClean.startsWith("#"))
		{
			hex = pClean.substring(1);
		}
		else if (pClean.startsWith("rgb("))
		{
			final Matcher rgb = ThemeTokens.RGB.matcher(pClean);
			return rgb.matches() ? new int[] { Integer.parseInt(rgb.group(1)), Integer.parseInt(rgb.group(2)), Integer.parseInt(rgb.group(3)) } : null;
		}
		else
		{
			hex = ThemeTokens.NAMED.get(pClean);
		}
		if (hex == null)
		{
			return null;
		}
		if (hex.length() == 3)
		{
			hex = new String(new char[] { hex.charAt(0), hex.charAt(0), hex.charAt(1), hex.charAt(1), hex.charAt(2), hex.charAt(2) });
		}
		return new int[] { Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16), Integer.parseInt(hex.substring(4, 6), 16) };
	}

	/**
	 * The raw colours of a perspective's theme, keyed by token name; empty when there is no perspective or no theme. Each getter is read
	 * on its own: one that fails (or does not exist on an older EBX) only loses its token.
	 */
	public static Map<String, String> read(final Perspective pPerspective)
	{
		final Map<String, String> out = new HashMap<String, String>();
		final PerspectiveTheme theme;
		try
		{
			theme = pPerspective == null ? null : pPerspective.getTheme();
		}
		catch (final RuntimeException | LinkageError ex)
		{
			return out;
		}
		if (theme == null)
		{
			return out;
		}
		ThemeTokens.put(out, ThemeTokens.MAIN, theme, 0);
		ThemeTokens.put(out, ThemeTokens.MAIN_TEXT, theme, 1);
		ThemeTokens.put(out, ThemeTokens.FOCUS, theme, 2);
		ThemeTokens.put(out, ThemeTokens.HEADER, theme, 3);
		ThemeTokens.put(out, ThemeTokens.HEADER_TEXT, theme, 4);
		ThemeTokens.put(out, ThemeTokens.SECOND_HEADER, theme, 5);
		ThemeTokens.put(out, ThemeTokens.BUTTON, theme, 6);
		ThemeTokens.put(out, ThemeTokens.BUTTON_TEXT, theme, 7);
		ThemeTokens.put(out, ThemeTokens.TABLE_HEADER, theme, 8);
		ThemeTokens.put(out, ThemeTokens.TABLE_ODD, theme, 9);
		ThemeTokens.put(out, ThemeTokens.TABLE_EVEN, theme, 10);
		ThemeTokens.put(out, ThemeTokens.TABLE_BORDER, theme, 11);
		if (ThemeTokens.sanitize(out.get(ThemeTokens.TABLE_BORDER)) == null)
		{
			ThemeTokens.put(out, ThemeTokens.TABLE_BORDER, theme, 12);
		}
		ThemeTokens.put(out, ThemeTokens.SELECTION, theme, 13);
		return out;
	}

	private static void put(final Map<String, String> pOut, final String pToken, final PerspectiveTheme pTheme, final int pGetter)
	{
		try
		{
			final String value = ThemeTokens.get(pTheme, pGetter);
			if (value != null)
			{
				pOut.put(pToken, value);
			}
		}
		catch (final RuntimeException | LinkageError ex)
		{
			// this colour stays the screen's own
		}
	}

	private static String get(final PerspectiveTheme pTheme, final int pGetter)
	{
		switch (pGetter)
		{
		case 0:
			return pTheme.getMainColor();
		case 1:
			return pTheme.getMainTextIconColor();
		case 2:
			return pTheme.getFocusColor();
		case 3:
			return pTheme.getHeaderColor();
		case 4:
			return pTheme.getHeaderTextColor();
		case 5:
			return pTheme.getSecondHeaderColor();
		case 6:
			return pTheme.getButtonsHighlightColor();
		case 7:
			return pTheme.getButtonsHighlightTextColor();
		case 8:
			return pTheme.getTableHeaderBackgroundColor();
		case 9:
			return pTheme.getTableOddBackgroundColor();
		case 10:
			return pTheme.getTableEvenBackgroundColor();
		case 11:
			return pTheme.getTableBodyInnerHorizontalBordersColor();
		case 12:
			return pTheme.getTableBodyOuterBorderColor();
		default:
			return pTheme.getPerspectiveNavigationPaneSelectionColor();
		}
	}

	private static String encode(final String pValue)
	{
		try
		{
			return URLEncoder.encode(pValue, "UTF-8");
		}
		catch (final UnsupportedEncodingException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	/** The CSS named colours (CSS Color Module Level 4), name to hexadecimal without the sign. */
	private static Map<String, String> namedColours()
	{
		final String table = ""
			+ "aliceblue:f0f8ff,antiquewhite:faebd7,aqua:00ffff,aquamarine:7fffd4,azure:f0ffff,beige:f5f5dc,"
			+ "bisque:ffe4c4,black:000000,blanchedalmond:ffebcd,blue:0000ff,blueviolet:8a2be2,brown:a52a2a,"
			+ "burlywood:deb887,cadetblue:5f9ea0,chartreuse:7fff00,chocolate:d2691e,coral:ff7f50,"
			+ "cornflowerblue:6495ed,cornsilk:fff8dc,crimson:dc143c,cyan:00ffff,darkblue:00008b,darkcyan:008b8b,"
			+ "darkgoldenrod:b8860b,darkgray:a9a9a9,darkgreen:006400,darkgrey:a9a9a9,darkkhaki:bdb76b,"
			+ "darkmagenta:8b008b,darkolivegreen:556b2f,darkorange:ff8c00,darkorchid:9932cc,darkred:8b0000,"
			+ "darksalmon:e9967a,darkseagreen:8fbc8f,darkslateblue:483d8b,darkslategray:2f4f4f,darkslategrey:2f4f4f,"
			+ "darkturquoise:00ced1,darkviolet:9400d3,deeppink:ff1493,deepskyblue:00bfff,dimgray:696969,"
			+ "dimgrey:696969,dodgerblue:1e90ff,firebrick:b22222,floralwhite:fffaf0,forestgreen:228b22,"
			+ "fuchsia:ff00ff,gainsboro:dcdcdc,ghostwhite:f8f8ff,gold:ffd700,goldenrod:daa520,gray:808080,"
			+ "green:008000,greenyellow:adff2f,grey:808080,honeydew:f0fff0,hotpink:ff69b4,indianred:cd5c5c,"
			+ "indigo:4b0082,ivory:fffff0,khaki:f0e68c,lavender:e6e6fa,lavenderblush:fff0f5,lawngreen:7cfc00,"
			+ "lemonchiffon:fffacd,lightblue:add8e6,lightcoral:f08080,lightcyan:e0ffff,lightgoldenrodyellow:fafad2,"
			+ "lightgray:d3d3d3,lightgreen:90ee90,lightgrey:d3d3d3,lightpink:ffb6c1,lightsalmon:ffa07a,"
			+ "lightseagreen:20b2aa,lightskyblue:87cefa,lightslategray:778899,lightslategrey:778899,"
			+ "lightsteelblue:b0c4de,lightyellow:ffffe0,lime:00ff00,limegreen:32cd32,linen:faf0e6,magenta:ff00ff,"
			+ "maroon:800000,mediumaquamarine:66cdaa,mediumblue:0000cd,mediumorchid:ba55d3,mediumpurple:9370db,"
			+ "mediumseagreen:3cb371,mediumslateblue:7b68ee,mediumspringgreen:00fa9a,mediumturquoise:48d1cc,"
			+ "mediumvioletred:c71585,midnightblue:191970,mintcream:f5fffa,mistyrose:ffe4e1,moccasin:ffe4b5,"
			+ "navajowhite:ffdead,navy:000080,oldlace:fdf5e6,olive:808000,olivedrab:6b8e23,orange:ffa500,"
			+ "orangered:ff4500,orchid:da70d6,palegoldenrod:eee8aa,palegreen:98fb98,paleturquoise:afeeee,"
			+ "palevioletred:db7093,papayawhip:ffefd5,peachpuff:ffdab9,peru:cd853f,pink:ffc0cb,plum:dda0dd,"
			+ "powderblue:b0e0e6,purple:800080,rebeccapurple:663399,red:ff0000,rosybrown:bc8f8f,royalblue:4169e1,"
			+ "saddlebrown:8b4513,salmon:fa8072,sandybrown:f4a460,seagreen:2e8b57,seashell:fff5ee,sienna:a0522d,"
			+ "silver:c0c0c0,skyblue:87ceeb,slateblue:6a5acd,slategray:708090,slategrey:708090,snow:fffafa,"
			+ "springgreen:00ff7f,steelblue:4682b4,tan:d2b48c,teal:008080,thistle:d8bfd8,tomato:ff6347,"
			+ "turquoise:40e0d0,violet:ee82ee,wheat:f5deb3,white:ffffff,whitesmoke:f5f5f5,yellow:ffff00,"
			+ "yellowgreen:9acd32";
		final Map<String, String> out = new HashMap<String, String>();
		for (final String pair : table.split(","))
		{
			final int colon = pair.indexOf(':');
			out.put(pair.substring(0, colon), pair.substring(colon + 1));
		}
		return Collections.unmodifiableMap(out);
	}
}
