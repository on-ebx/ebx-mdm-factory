package com.on.ebx.factory.ebx.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * JUnit-free, reads the sources (the page script cannot run without a browser). Audit F-45: the feed designer creates a rule
 * without a mouse, names its fields, and the designer and the proposal review show where the focus is.
 */
public final class DesignerAccessibilitySourceTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		final Path main = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java")).getParent();
		final String js = DesignerAccessibilitySourceTest.read(main.resolve("webapp/www/common/jscripts/factory/feed-designer.js"));
		DesignerAccessibilitySourceTest.check(js.contains("role=\"button\" tabindex=\"0\" aria-label=\"' + esc(t(side === 'L' ? 'designer.kbd.sourceRow'"),
			"tree rows are focusable buttons with a name");
		DesignerAccessibilitySourceTest.check(js.contains("root.addEventListener('keydown'") && js.contains("e.key !== 'Enter' && e.key !== ' '") && js.contains("tg.click();"),
			"Enter/Space act like a click");
		DesignerAccessibilitySourceTest.check(js.contains("aria-live=\"polite\"") && js.contains("announce(t('designer.kbd.ruleCreated'"), "a live region says what happened");
		// every text field and select the script writes is tied to its <label> (a <label> of a read-only value, in the field inspector, labels no control)
		for (final String id : new String[] { "mdfdConstVal", "mdfdRaw", "mdfdSourceIdPipeline", "mdfdAddFieldName", "mdfdAddFieldLabel", "mdfdAddFieldType" })
		{
			DesignerAccessibilitySourceTest.check(js.contains("<label for=\"" + id + "\">"), "label for " + id);
		}
		DesignerAccessibilitySourceTest.check(js.contains("id=\"mdfdAddStep\" aria-label=") && js.contains("class=\"mdfdGuardSeverity\" aria-label=")
			&& js.contains("placeholder=\"' + esc(t('designer.search')) + '\" aria-label="), "the selects and the searches are named");
		final String designerCss = DesignerAccessibilitySourceTest.read(main.resolve("webapp/www/common/stylesheets/factory/feed-designer.css"));
		final String reviewCss = DesignerAccessibilitySourceTest.read(main.resolve("webapp/www/common/stylesheets/factory/review.css"));
		DesignerAccessibilitySourceTest.check(designerCss.contains(".mdfdRow:focus-visible") && reviewCss.contains(".mdmf-review button:focus-visible"), "focus styles");
		final String review = DesignerAccessibilitySourceTest.read(main.resolve("java/com/on/ebx/factory/review/ReviewPage.java"));
		DesignerAccessibilitySourceTest.check(review.contains("\\\" aria-label=\\\"\").append(ReviewPage.esc(ReviewMessages.get(pLocale, \"review.select.row\"") && review.contains("id=\\\"mdmfSelectAll\\\" title=")
			&& review.indexOf("aria-label") > 0, "the review's checkboxes are named");
		System.out.println("DesignerAccessibilitySourceTest OK");
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
