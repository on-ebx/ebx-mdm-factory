package com.on.ebx.factory.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * The pure logic of the "About" screen: the one-line text the Copy button puts on the clipboard, the build time shown in
 * the person's locale, and the choice of the user guide by locale. No EBX type, so the plain {@code main()} test runs without
 * the server libraries. Everything tolerates {@code "unknown"} ({@link ModuleVersion#UNKNOWN}): a build without a version stamp
 * must show a screen, never fail it.
 */
public final class AboutInfo
{
	public static final String PRODUCT = "ON EBX MDM Factory";

	private AboutInfo()
	{
	}

	/** True when the build stamped a version ({@code version.properties} present and non-blank). */
	public static boolean stamped(final String pVersion)
	{
		return pVersion != null && !pVersion.trim().isEmpty() && !ModuleVersion.UNKNOWN.equals(pVersion.trim());
	}

	/**
	 * The support line: {@code ON EBX MDM Factory 1.1.0 (abc1234, built 2026-10-07T08:00:00Z)}. The build time stays in the
	 * UTC form the build wrote, so that two people read the same line whatever their locale. An unknown commit or time is left out.
	 */
	public static String copyLine(final String pVersion, final String pCommit, final String pBuildTime)
	{
		final StringBuilder out = new StringBuilder(AboutInfo.PRODUCT).append(' ');
		out.append(AboutInfo.stamped(pVersion) ? pVersion.trim() : ModuleVersion.UNKNOWN);
		final StringBuilder detail = new StringBuilder();
		if (AboutInfo.stamped(pCommit))
		{
			detail.append(pCommit.trim());
		}
		if (AboutInfo.stamped(pBuildTime))
		{
			detail.append(detail.length() == 0 ? "" : ", ").append("built ").append(pBuildTime.trim());
		}
		if (detail.length() > 0)
		{
			out.append(" (").append(detail).append(')');
		}
		return out.toString();
	}

	/**
	 * The build time (an ISO-8601 instant, as {@code build.sh} writes it) formatted for a locale and a zone; the raw text when it
	 * is not an instant, {@code "unknown"} when absent.
	 */
	public static String buildTime(final String pBuildTime, final Locale pLocale, final ZoneId pZone)
	{
		if (!AboutInfo.stamped(pBuildTime))
		{
			return ModuleVersion.UNKNOWN;
		}
		try
		{
			return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(pLocale == null ? Locale.ENGLISH : pLocale)
				.withZone(pZone == null ? ZoneId.systemDefault() : pZone).format(Instant.parse(pBuildTime.trim()));
		}
		catch (final RuntimeException ex)
		{
			return pBuildTime.trim();
		}
	}

	/** The user guide page of a locale, relative to {@code www/common/html}: French for French, English otherwise. */
	public static String guide(final Locale pLocale)
	{
		return "factory/user-guide-" + (pLocale != null && "fr".equals(pLocale.getLanguage()) ? "fr" : "en") + ".html";
	}

	/** The guide in the other language, offered next to {@link #guide}. */
	public static String otherGuide(final Locale pLocale)
	{
		return "factory/user-guide-" + (pLocale != null && "fr".equals(pLocale.getLanguage()) ? "en" : "fr") + ".html";
	}
}
