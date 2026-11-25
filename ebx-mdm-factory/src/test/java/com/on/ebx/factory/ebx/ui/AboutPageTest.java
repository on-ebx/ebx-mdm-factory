package com.on.ebx.factory.ebx.ui;

import java.io.InputStream;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free: run the main, a failure throws. The About screen's HTML from fixtures, its bundle keys in both locales, and its perspective entry. */
public final class AboutPageTest
{
	private static final String KEY = "ebx-mdm-factory@/WEB-INF/ebx/schemas/MdmFactory.xsd@About";

	public static void main(final String[] pArgs) throws Exception
	{
		final AboutPage.Model m = new AboutPage.Model();
		m.version = "1.1.0";
		m.commit = "abc1234";
		m.buildTime = "2026-10-07T08:00:00Z";
		m.buildTimeText = "Oct 7, 2026, 8:00:00 AM";
		m.ebxVersion = "6.3.0 (build 5)";
		m.agentJarSha256 = "aa11";
		m.mappingJarSha256 = "bb22";
		m.guideUrl = "/g-en.html?x=1&y=2";
		m.otherGuideUrl = "/g-fr.html";
		final String user = AboutPage.render(m);
		AboutPageTest.check("a definition list holds the version", user.contains("<dl") && user.contains("<dd>1.1.0</dd>") && user.contains("<dd>abc1234</dd>"));
		AboutPageTest.check("ON EBX line", user.contains("<dd>6.3.0 (build 5)</dd>"));
		AboutPageTest.check("no hash for a non administrator", !user.contains("aa11") && !user.contains("bb22"));
		AboutPageTest.check("guide links open in a new tab", user.contains("target=\"_blank\" rel=\"noopener\"") && user.contains("href=\"/g-en.html?x=1&amp;y=2\"") && user.contains("/g-fr.html"));
		AboutPageTest.check("copy line on the button", user.contains("data-line=\"ON EBX MDM Factory 1.1.0 (abc1234, built 2026-10-07T08:00:00Z)\""));
		AboutPageTest.check("no unstamped warning when stamped", !user.contains("mdmf-hint-warn"));
		AboutPageTest.check("no raw key leaks (en)", !user.contains("about."));
		m.administrator = true;
		final String admin = AboutPage.render(m);
		AboutPageTest.check("administrator sees both hashes", admin.contains("aa11") && admin.contains("bb22"));
		m.ebxVersion = null;
		AboutPageTest.check("ON EBX line omitted when unreadable", !AboutPage.render(m).contains("<dt>ON EBX</dt>"));

		final AboutPage.Model bare = new AboutPage.Model();
		final String unknown = AboutPage.render(bare);
		AboutPageTest.check("unstamped build: unknown + one-line reason, no crash", unknown.contains("<dd>unknown</dd>") && unknown.contains("mdmf-hint-warn")
			&& unknown.contains("data-line=\"ON EBX MDM Factory unknown\"") && !unknown.contains("<ul class=\"mdmf-about-links\"><li>"));

		final AboutPage.Model fr = new AboutPage.Model();
		fr.locale = Locale.FRANCE;
		fr.guideUrl = "/g-fr.html";
		final String frHtml = AboutPage.render(fr);
		AboutPageTest.check("French texts resolve", frHtml.contains("Compilée le") && frHtml.contains("utilisation (") && !frHtml.contains("about."));
		AboutPageTest.check("script is syntactically a closed function", AboutPage.script(fr).startsWith("(function(){") && AboutPage.script(fr).endsWith("})();"));
		AboutPageTest.check("label and title resolve in both locales", !"about.label".equals(Messages.get(AboutPage.class, Locale.FRANCE, "about.label"))
			&& "About".equals(Messages.get(AboutPage.class, Locale.ENGLISH, "about.label")));
		AboutPageTest.perspective();
		System.out.println("AboutPageTest OK");
	}

	/** The packaged perspective ends with the About item (key, FontAwesome icon), once; an installed perspective gets it by PerspectiveInstaller's completion by that key. */
	private static void perspective() throws Exception
	{
		final InputStream in = AboutPageTest.class.getResourceAsStream("/com/on/ebx/factory/ebx/MdmFactory.perspective.json");
		AboutPageTest.check("perspective resource found", in != null);
		final JsonNode root = new ObjectMapper().readTree(in);
		int count = 0;
		JsonNode last = null;
		JsonNode lastSection = null;
		for (final JsonNode section : root.path("items"))
		{
			lastSection = section;
			for (final JsonNode item : section.path("children"))
			{
				last = item;
				if (AboutPageTest.KEY.equals(item.path("serviceKey").asText()))
				{
					count++;
				}
			}
		}
		AboutPageTest.check("exactly one About item", count == 1);
		AboutPageTest.check("it is the last item of the last section", last != null && AboutPageTest.KEY.equals(last.path("serviceKey").asText()) && lastSection != null);
		AboutPageTest.check("fa-info-circle, labelled", "fa-info-circle".equals(last.path("iconCss").asText()) && "About".equals(last.path("labelEn").asText())
			&& "À propos".equals(last.path("labelFr").asText()));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
