package com.on.ebx.factory.ebx.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JUnit-free, reads the sources. Audit F-44: no brand colour of the Factory (its violet and the feed designer's blue, with their
 * tints) is written anywhere in the module except as the fallback of a theme token - {@code var(--ebx-main,#684bc0)} - or on a line
 * that says why it stays ({@code theme-fixed}: a legend or type colour that carries a meaning). Semantic colours (good, warn, bad,
 * info) and the neutral greys are not brand colours and are not checked. Also checks that every screen writes the tokens.
 */
public final class ThemeColourSourceTest
{
	/** The brand literals found by the F-44 audit (lower case), and the start of the two brand rgba() forms. */
	static final String[] BRAND = { "#684bc0", "#f0eefa", "#d9d2f2", "#b9a9ea", "#f3effb", "#e4ddf7", "#fbfaff", "#cdc3ee", "#5b21b6", "#9f7aea", "#faf8ff", "#cbb7ef",
		"#59447a", "#f4f0fa", "#3b2a7a", "#f4f1fc", "#f6f5fb", "rgba(104,75,192", "rgba(64,38,101", "#1a73e8", "#e8f0fe", "#c6dafc", "#174ea6" };
	private static final Pattern FALLBACK = Pattern.compile("var\\(--ebx-[a-z-]+,\\s*(#[0-9a-fA-F]{3,8}|rgba?\\([^)]*\\))\\s*\\)");

	public static void main(final String[] pArgs) throws IOException
	{
		final Path main = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java")).getParent();
		final List<Path> files;
		try (Stream<Path> walk = Files.walk(main))
		{
			files = walk.filter(Files::isRegularFile).filter(p -> p.toString().matches(".*\\.(css|js|html|java)$")).collect(Collectors.toList());
		}
		ThemeColourSourceTest.check(files.size() > 50, "the module's sources were found (" + files.size() + ")");
		final List<String> offences = new ArrayList<String>();
		for (final Path file : files)
		{
			final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				final String offence = ThemeColourSourceTest.brandOutsideFallback(lines.get(i));
				if (offence != null)
				{
					offences.add(main.relativize(file) + ":" + (i + 1) + " " + offence);
				}
			}
		}
		ThemeColourSourceTest.check(offences.isEmpty(), "brand colour outside a var(--ebx-...) fallback:\n" + String.join("\n", offences));

		// the scan itself: a fallback passes, a bare literal does not, a theme-fixed line passes
		ThemeColourSourceTest.check(ThemeColourSourceTest.brandOutsideFallback(".a{--accent:var(--ebx-main,#684bc0)}") == null, "fallback allowed");
		ThemeColourSourceTest.check(ThemeColourSourceTest.brandOutsideFallback(".a{color:#684BC0}") != null, "bare literal refused");
		ThemeColourSourceTest.check(ThemeColourSourceTest.brandOutsideFallback(".a{box-shadow:0 0 0 3px rgba(104, 75, 192, .3)}") != null, "bare rgba refused");
		ThemeColourSourceTest.check(ThemeColourSourceTest.brandOutsideFallback(".a{color:#684bc0} /* theme-fixed: legend */") == null, "theme-fixed allowed");

		// every screen writes the tokens: the guarded panes through ScreenFailure.guard, the wizard on its own
		final String guard = ThemeColourSourceTest.read(main.resolve("java/com/on/ebx/factory/ebx/ui/ScreenFailure.java"));
		ThemeColourSourceTest.check(guard.contains("pWriter.add(ThemeTokens.styleTagFor(pPaneContext));\n\t\t\t\t\tpInner.writePane(pPaneContext, pWriter);"),
			"ScreenFailure.guard writes the tokens before the screen");
		try (Stream<Path> walk = Files.walk(main.resolve("java/com/on/ebx/factory/ebx/ui")))
		{
			for (final Path service : walk.filter(p -> p.getFileName().toString().endsWith("UserService.java")).collect(Collectors.toList()))
			{
				final String source = ThemeColourSourceTest.read(service);
				final int panes = ThemeColourSourceTest.count(source, "pConfigurator.setContent(");
				final int themed = ThemeColourSourceTest.count(source, "pConfigurator.setContent(ScreenFailure.guard(") + ThemeColourSourceTest.count(source, "ThemeTokens.styleTag");
				ThemeColourSourceTest.check(panes == 0 || themed >= panes, service.getFileName() + ": every pane writes the theme tokens (" + themed + "/" + panes + ")");
			}
		}
		final String wizard = ThemeColourSourceTest.read(main.resolve("java/com/on/ebx/factory/ebx/ui/ProjectWizardUserService.java"));
		ThemeColourSourceTest.check(wizard.contains("ThemeTokens.withQuery(pWriter.getURLForResource(ResourceType.HTML"), "the user guide link carries the tokens");

		// the brand aliases read the theme first, the old colour second
		final String factoryCss = ThemeColourSourceTest.read(main.resolve("webapp/www/common/stylesheets/factory/factory.css"));
		ThemeColourSourceTest.check(factoryCss.contains("--accent:var(--ebx-main,#684bc0)") && factoryCss.contains("--btn:var(--ebx-button,var(--accent))")
			&& factoryCss.contains("--th-bg:var(--ebx-table-header,#f8fafc)") && factoryCss.contains("--focus:var(--ebx-focus,var(--accent))") && factoryCss.contains("--accent-fg:var(--ebx-main-ink,#684bc0)"), "factory.css aliases");
		ThemeColourSourceTest.check(ThemeColourSourceTest.read(main.resolve("webapp/www/common/stylesheets/factory/feed-designer.css")).contains("--mdfd-accent:var(--ebx-main,#1a73e8)"),
			"feed-designer.css alias");
		ThemeColourSourceTest.check(ThemeColourSourceTest.read(main.resolve("java/com/on/ebx/factory/mappingmatrix/MatrixHtml.java")).contains("#mdmm{--accent:var(--ebx-main,#684bc0)"),
			"matrix alias");
		for (final String guide : new String[] { "user-guide-en.html", "user-guide-fr.html" })
		{
			final String html = ThemeColourSourceTest.read(main.resolve("webapp/www/common/html/factory/" + guide));
			ThemeColourSourceTest.check(html.contains("--accent:var(--ebx-main,#684bc0)") && html.contains("document.documentElement.style.setProperty(\"--ebx-\"+n,v)"),
				guide + " reads the tokens of its link");
		}
		System.out.println("ThemeColourSourceTest OK");
	}

	/** The first brand literal of a line that is not a theme token's fallback, or {@code null}. Pure. */
	static String brandOutsideFallback(final String pLine)
	{
		if (pLine.contains("theme-fixed"))
		{
			return null;
		}
		final Matcher fallback = ThemeColourSourceTest.FALLBACK.matcher(pLine);
		final String rest = fallback.replaceAll("var(--ebx)").toLowerCase(Locale.ROOT).replaceAll("rgba\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)", "rgba($1,$2,$3");
		for (final String brand : ThemeColourSourceTest.BRAND)
		{
			final int at = rest.indexOf(brand);
			if (at >= 0 && (brand.startsWith("rgba") || at + brand.length() >= rest.length() || !Character.isLetterOrDigit(rest.charAt(at + brand.length()))))
			{
				return brand;
			}
		}
		return null;
	}

	private static int count(final String pText, final String pWhat)
	{
		int n = 0;
		for (int at = pText.indexOf(pWhat); at >= 0; at = pText.indexOf(pWhat, at + 1))
		{
			n++;
		}
		return n;
	}

	private static String read(final Path pPath) throws IOException
	{
		return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8);
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
