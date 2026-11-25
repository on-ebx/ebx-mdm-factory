package com.on.ebx.factory.core;

import java.time.ZoneId;
import java.util.Locale;

/** JUnit-free: run the main, a failure throws. */
public final class AboutInfoTest
{
	public static void main(final String[] pArgs)
	{
		AboutInfoTest.check("copy line, full", "ON EBX MDM Factory 1.1.0-24-g995cc10 (995cc10, built 2026-10-07T08:00:00Z)"
			.equals(AboutInfo.copyLine("1.1.0-24-g995cc10", "995cc10", "2026-10-07T08:00:00Z")));
		AboutInfoTest.check("copy line, no stamp", "ON EBX MDM Factory unknown".equals(AboutInfo.copyLine("unknown", "unknown", "unknown")));
		AboutInfoTest.check("copy line, null and blank", "ON EBX MDM Factory unknown".equals(AboutInfo.copyLine(null, " ", null)));
		AboutInfoTest.check("copy line, version only", "ON EBX MDM Factory 1.0.0 (abc)".equals(AboutInfo.copyLine("1.0.0", "abc", "unknown")));
		AboutInfoTest.check("stamped", AboutInfo.stamped("1.0.0") && !AboutInfo.stamped("unknown") && !AboutInfo.stamped("") && !AboutInfo.stamped(null));
		AboutInfoTest.check("guide fr", "factory/user-guide-fr.html".equals(AboutInfo.guide(Locale.FRANCE)) && "factory/user-guide-en.html".equals(AboutInfo.otherGuide(Locale.FRANCE)));
		AboutInfoTest.check("guide en, other locale and null", "factory/user-guide-en.html".equals(AboutInfo.guide(Locale.GERMANY))
			&& "factory/user-guide-en.html".equals(AboutInfo.guide(null)) && "factory/user-guide-fr.html".equals(AboutInfo.otherGuide(null)));
		AboutInfoTest.check("build time unknown", "unknown".equals(AboutInfo.buildTime("unknown", Locale.ENGLISH, ZoneId.of("UTC"))));
		AboutInfoTest.check("build time garbage is shown as is", "yesterday".equals(AboutInfo.buildTime("yesterday", Locale.ENGLISH, ZoneId.of("UTC"))));
		final String en = AboutInfo.buildTime("2026-10-07T08:00:00Z", Locale.ENGLISH, ZoneId.of("UTC"));
		final String fr = AboutInfo.buildTime("2026-10-07T08:00:00Z", Locale.FRANCE, ZoneId.of("UTC"));
		AboutInfoTest.check("build time formatted per locale (" + en + " / " + fr + ")", en.contains("2026") && fr.contains("2026") && !en.equals(fr) && !en.contains("T08"));
		System.out.println("AboutInfoTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
