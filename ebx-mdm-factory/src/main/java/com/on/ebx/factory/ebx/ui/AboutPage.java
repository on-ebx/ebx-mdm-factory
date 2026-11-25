package com.on.ebx.factory.ebx.ui;

import java.util.Locale;

import com.on.ebx.factory.core.AboutInfo;
import com.on.ebx.factory.core.ModuleVersion;

/**
 * The HTML of the "About" screen, from plain values only (no EBX object), so it renders from fixtures in {@code AboutPageTest}:
 * the product header, the version block (a {@code <dl>}), the documentation links (new tab, served by this module) and the
 * Copy button. The jar hashes are shown to administrators only; everything else is shown to whoever may open the perspective
 * (no secret in it).
 */
final class AboutPage
{
	private AboutPage()
	{
	}

	static final class Model
	{
		Locale locale = Locale.ENGLISH;
		String version = ModuleVersion.UNKNOWN;
		String commit = ModuleVersion.UNKNOWN;
		String buildTime = ModuleVersion.UNKNOWN;
		/** Already formatted in the person's locale. */
		String buildTimeText = ModuleVersion.UNKNOWN;
		/** The running ON EBX version; {@code null} omits the line. */
		String ebxVersion;
		boolean administrator;
		String agentJarSha256 = ModuleVersion.UNKNOWN;
		String mappingJarSha256 = ModuleVersion.UNKNOWN;
		/** Address of the guide in the person's language, then of the other one; empty hides the link. */
		String guideUrl = "";
		String otherGuideUrl = "";
		/** Language code ("en", "fr") of each, for the link text. */
		String guideLanguage = "en";
		String otherGuideLanguage = "fr";
	}

	private static String e(final Model pModel, final String pKey, final Object... pArgs)
	{
		return FactoryHtml.esc(Messages.get(AboutPage.class, pModel.locale, pKey, pArgs));
	}

	private static void row(final StringBuilder pOut, final String pLabel, final String pValue)
	{
		pOut.append("<dt>").append(pLabel).append("</dt><dd>").append(FactoryHtml.esc(pValue)).append("</dd>");
	}

	static String render(final Model pModel)
	{
		final StringBuilder sb = new StringBuilder(4096);
		sb.append("<div class=\"mdmf mdmf-about\">");
		sb.append("<section class=\"mdmf-panel\"><h1 class=\"mdmf-about-title\">").append(AboutPage.e(pModel, "about.product")).append("</h1>");
		sb.append("<p class=\"mdmf-lead\">").append(AboutPage.e(pModel, "about.description")).append("</p></section>");

		sb.append("<section class=\"mdmf-panel\" aria-labelledby=\"mdmf-about-version\"><h2 id=\"mdmf-about-version\">").append(AboutPage.e(pModel, "about.version.title"))
			.append("</h2><dl class=\"mdmf-about-dl\">");
		AboutPage.row(sb, AboutPage.e(pModel, "about.version.version"), pModel.version);
		AboutPage.row(sb, AboutPage.e(pModel, "about.version.commit"), pModel.commit);
		AboutPage.row(sb, AboutPage.e(pModel, "about.version.built"), pModel.buildTimeText);
		if (pModel.ebxVersion != null && !pModel.ebxVersion.isEmpty())
		{
			AboutPage.row(sb, AboutPage.e(pModel, "about.version.ebx"), pModel.ebxVersion);
		}
		if (pModel.administrator)
		{
			AboutPage.row(sb, AboutPage.e(pModel, "about.version.agentJar"), pModel.agentJarSha256);
			AboutPage.row(sb, AboutPage.e(pModel, "about.version.mappingJar"), pModel.mappingJarSha256);
		}
		sb.append("</dl>");
		if (!AboutInfo.stamped(pModel.version))
		{
			sb.append("<p class=\"mdmf-hint mdmf-hint-warn\">").append(AboutPage.e(pModel, "about.version.unstamped")).append("</p>");
		}
		final String line = AboutInfo.copyLine(pModel.version, pModel.commit, pModel.buildTime);
		sb.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn\" id=\"mdmf-about-copy\" data-line=\"").append(FactoryHtml.esc(line)).append("\">")
			.append(AboutPage.e(pModel, "about.copy")).append("</button><span id=\"mdmf-about-copied\" class=\"mdmf-hint\" role=\"status\" aria-live=\"polite\"></span></div>");
		sb.append("</section>");

		sb.append("<section class=\"mdmf-panel\" aria-labelledby=\"mdmf-about-docs\"><h2 id=\"mdmf-about-docs\">").append(AboutPage.e(pModel, "about.docs.title"))
			.append("</h2><ul class=\"mdmf-about-links\">");
		if (!pModel.guideUrl.isEmpty())
		{
			sb.append("<li><a href=\"").append(FactoryHtml.esc(pModel.guideUrl)).append("\" target=\"_blank\" rel=\"noopener\">")
				.append(AboutPage.e(pModel, "about.docs.guide", pModel.guideLanguage)).append("</a>");
			if (!pModel.otherGuideUrl.isEmpty())
			{
				sb.append(" <span class=\"mdmf-hint\">(<a href=\"").append(FactoryHtml.esc(pModel.otherGuideUrl)).append("\" target=\"_blank\" rel=\"noopener\">")
					.append(AboutPage.e(pModel, "about.docs.guide.other", pModel.otherGuideLanguage)).append("</a>)</span>");
			}
			sb.append("</li>");
		}
		sb.append("</ul><p class=\"mdmf-hint\">").append(AboutPage.e(pModel, "about.docs.note")).append("</p></section>");
		sb.append("</div>");
		return sb.toString();
	}

	/** Copies the support line; falls back to a hidden textarea where the clipboard API is not allowed (an iframe, http). */
	static String script(final Model pModel)
	{
		final String ok = FactoryHtml.jsStringBody(Messages.get(AboutPage.class, pModel.locale, "about.copied"));
		final String ko = FactoryHtml.jsStringBody(Messages.get(AboutPage.class, pModel.locale, "about.copy.failed"));
		return "(function(){var b=document.getElementById('mdmf-about-copy'),s=document.getElementById('mdmf-about-copied');if(!b){return;}"
			+ "function say(t){if(s){s.textContent=t;}}"
			+ "function legacy(t){try{var a=document.createElement('textarea');a.value=t;a.setAttribute('readonly','');a.style.position='fixed';a.style.opacity='0';"
			+ "document.body.appendChild(a);a.select();var r=document.execCommand('copy');document.body.removeChild(a);say(r?'" + ok + "':'" + ko + "');}catch(x){say('" + ko + "');}}"
			+ "b.addEventListener('click',function(){var t=b.getAttribute('data-line')||'';"
			+ "if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(t).then(function(){say('" + ok + "');},function(){legacy(t);});}else{legacy(t);}});})();";
	}
}
