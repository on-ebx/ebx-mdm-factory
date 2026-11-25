package com.on.ebx.factory.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * A cache-busting query value shared by every {@code <link rel="stylesheet">} the module's screens write
 * for {@code factory/factory.css}, {@code factory/review.css}, {@code factory/wizard.css} (Connexion,
 * Cockpit, Review, the project wizard - {@code grep getURLForResource} in {@code ebx.ui} finds every call
 * site). fix/review-assets (2026-09-21): every one of them used to append a FIXED {@code ?v=1} - a real
 * browser then keeps serving whatever it cached under that exact URL forever, across every actual CSS
 * change. Product report: the review screen's selection bar (no border/background/spacing) and progress
 * strip (never appeared at all) were both already shipped in {@code review.css} - the screenshot matched
 * exactly what the OLD stylesheet, still sitting in the browser's cache, would render.
 * <p>
 * {@code ebx-mdm-factory/build.sh} writes {@code com/on/ebx/factory/build.properties} (a single
 * {@code build=<yyyyMMddHHmmss>} line, this compile's own wall-clock time) into {@code target/classes} on
 * EVERY run, last, after the {@code src/main/resources} copy - deliberately NOT itself a file under
 * {@code src/main/resources}. {@link #version()} reads it back once, at class load, and every caller
 * appends {@code ?v=<that value>} instead of the old fixed {@code ?v=1} - a real code/CSS change always
 * means a re-run of {@code build.sh} (a webapp redeploy needs a Tomcat restart anyway to pick up a class
 * change, CLAUDE.md &sect;9/&sect;10), which always writes a new, different {@code build=...} value, which
 * always changes every stylesheet URL, which always forces the browser to re-fetch.
 * <p>
 * <b>Eclipse-only rebuild (F5, no {@code build.sh} run)</b>: {@code build.properties} sits directly under
 * {@code target/classes}, outside {@code src/main/resources} - {@code ebx-mdm-factory/.classpath}
 * declares {@code src/main/resources} (output {@code target/classes}) as the ONLY resource source Eclipse
 * manages there, so a plain incremental F5 build leaves an EXISTING {@code build.properties} alone
 * (Eclipse only touches the files it generates from tracked source), while a full "Project &gt; Clean"
 * wipes the WHOLE output folder - {@code build.properties} included - and Eclipse never regenerates it
 * (nothing under {@code src/main/resources} produces that filename). Either way, once it is missing,
 * {@link #resolve} falls back to THIS CLASS'S OWN LOAD TIME instead - still correct for the case that
 * actually matters (a real deploy: Tomcat has to restart to pick up any Eclipse-rebuilt {@code .class}
 * file at all, and a restart reloads this class, producing a new fallback value each time). The one
 * workflow this fallback does NOT catch: editing {@code review.css} directly and expecting the browser to
 * see it without any Tomcat restart at all - the CSS itself lives under {@code src/main/webapp/www/...}
 * (plain {@code docBase}, served live, no restart needed for a CSS-only edit - CLAUDE.md &sect;9's own
 * {@code reloadable}/{@code PostResources} notes are about CLASS reloading, not static file serving), so
 * its own timestamp never changes independently of a class reload. A developer relying on THAT specific
 * workflow should still hard-refresh once, or simply run {@code build.sh} (which touches nothing else).
 */
public final class Assets
{
	private static final String BUILD_PROPERTIES_RESOURCE = "/com/on/ebx/factory/build.properties";

	private static final String VERSION = Assets.resolve(Assets.class.getResourceAsStream(Assets.BUILD_PROPERTIES_RESOURCE), System.currentTimeMillis());

	private Assets()
	{
	}

	/** The value every {@code <link>} tag appends as {@code ?v=<version()>} - never empty, never {@code null}. */
	public static String version()
	{
		return Assets.VERSION;
	}

	/**
	 * Pure (once handed a stream, never touches the classpath itself): {@code pBuildPropertiesStream} may be
	 * {@code null} (the resource does not exist - the Eclipse-Clean case above) or present but missing/blank
	 * its {@code build} key (a hand-edited or corrupted file) - either way this falls back to
	 * {@code pFallbackMillis}, formatted the same way a real fallback would be. Closes the stream itself
	 * (the caller handed it over to be consumed exactly once); an {@link IOException} while reading also
	 * falls back rather than failing class initialisation.
	 */
	static String resolve(final InputStream pBuildPropertiesStream, final long pFallbackMillis)
	{
		if (pBuildPropertiesStream != null)
		{
			try
			{
				final Properties props = new Properties();
				props.load(pBuildPropertiesStream);
				final String build = props.getProperty("build");
				if (build != null && !build.trim().isEmpty())
				{
					return build.trim();
				}
			}
			catch (final IOException ex)
			{
				// falls through to the fallback below - a corrupted/unreadable file is not worth failing class
				// initialisation over, this is a cache-busting value, not a correctness-critical one
			}
			finally
			{
				try
				{
					pBuildPropertiesStream.close();
				}
				catch (final IOException ex)
				{
					// nothing to do
				}
			}
		}
		return String.valueOf(pFallbackMillis);
	}
}
