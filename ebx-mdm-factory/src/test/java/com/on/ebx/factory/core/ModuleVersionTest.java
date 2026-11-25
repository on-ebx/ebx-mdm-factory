package com.on.ebx.factory.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** JUnit-free: run the main, a failure throws. */
public final class ModuleVersionTest
{
	public static void main(final String[] pArgs)
	{
		ModuleVersionTest.presentFileIsReadVerbatim();
		ModuleVersionTest.packagedJarsAreListedByFileName();
		ModuleVersionTest.blankAndMissingKeysAreUnknown();
		ModuleVersionTest.absentResourceIsUnknown();
		ModuleVersionTest.unreadableStreamIsUnknownNotAnException();
		ModuleVersionTest.gettersNeverReturnNullOrBlank();
		System.out.println("ModuleVersionTest OK");
	}

	private static Properties parse(final String pContent)
	{
		return ModuleVersion.parse(new ByteArrayInputStream(pContent.getBytes(StandardCharsets.ISO_8859_1)));
	}

	private static void presentFileIsReadVerbatim()
	{
		final Properties props = ModuleVersionTest.parse("version=1.2.0-3-gabcdef12\ncommit=abc123\nbuildTime=2026-10-06T10:00:00Z\nagentJarSha256=aa\nmappingJarSha256=bb\n");
		ModuleVersionTest.check("version", "1.2.0-3-gabcdef12".equals(ModuleVersion.value(props, "version")));
		ModuleVersionTest.check("commit", "abc123".equals(ModuleVersion.value(props, "commit")));
		ModuleVersionTest.check("buildTime", "2026-10-06T10:00:00Z".equals(ModuleVersion.value(props, "buildTime")));
		ModuleVersionTest.check("agent hash", "aa".equals(ModuleVersion.value(props, "agentJarSha256")));
		ModuleVersionTest.check("mapping hash", "bb".equals(ModuleVersion.value(props, "mappingJarSha256")));
	}

	/** X3-07 / X3-08: the Jackson jars of WEB-INF/lib (build.sh lists them, in a package AND in a tools/install.sh installation) are listed by file name; a version.properties without them lists nothing and nothing fails. */
	private static void packagedJarsAreListedByFileName()
	{
		final Properties props = ModuleVersionTest.parse("version=1\nembeddedJar.jackson-core-2.18.12.jar=aa\nembeddedJar.jackson-databind-2.18.12.jar=bb\nembeddedJar.jackson-annotations-2.18.12.jar=dd\nembeddedJar.blank.jar=  \nembeddedJar.=cc\nagentJarSha256=zz\n");
		final java.util.Map<String, String> jars = ModuleVersion.packagedJars(props);
		ModuleVersionTest.check("three jars listed", jars.size() == 3);
		ModuleVersionTest.check("core hash", "aa".equals(jars.get("jackson-core-2.18.12.jar")));
		ModuleVersionTest.check("databind hash", "bb".equals(jars.get("jackson-databind-2.18.12.jar")));
		ModuleVersionTest.check("annotations hash", "dd".equals(jars.get("jackson-annotations-2.18.12.jar")));
		ModuleVersionTest.check("none when version.properties lists none", ModuleVersion.packagedJars(ModuleVersionTest.parse("version=1\n")).isEmpty());
		ModuleVersionTest.check("none when the resource is absent", ModuleVersion.packagedJars(ModuleVersion.parse(null)).isEmpty());
	}

	private static void blankAndMissingKeysAreUnknown()
	{
		final Properties props = ModuleVersionTest.parse("version=\nagentJarSha256=   \n");
		ModuleVersionTest.check("blank version", ModuleVersion.UNKNOWN.equals(ModuleVersion.value(props, "version")));
		ModuleVersionTest.check("blank hash", ModuleVersion.UNKNOWN.equals(ModuleVersion.value(props, "agentJarSha256")));
		ModuleVersionTest.check("missing key", ModuleVersion.UNKNOWN.equals(ModuleVersion.value(props, "commit")));
	}

	private static void absentResourceIsUnknown()
	{
		ModuleVersionTest.check("null stream", ModuleVersion.UNKNOWN.equals(ModuleVersion.value(ModuleVersion.parse(null), "version")));
	}

	private static void unreadableStreamIsUnknownNotAnException()
	{
		final InputStream broken = new InputStream()
		{
			@Override
			public int read() throws IOException
			{
				throw new IOException("simulated read failure");
			}
		};
		ModuleVersionTest.check("broken stream", ModuleVersion.UNKNOWN.equals(ModuleVersion.value(ModuleVersion.parse(broken), "version")));
	}

	/** The real classpath lookup, whatever build.sh produced (or nothing, in an Eclipse-Clean state). */
	private static void gettersNeverReturnNullOrBlank()
	{
		for (final String value : new String[] { ModuleVersion.version(), ModuleVersion.commit(), ModuleVersion.buildTime(), ModuleVersion.agentJarSha256(), ModuleVersion.mappingJarSha256() })
		{
			ModuleVersionTest.check("getter answers something", value != null && !value.trim().isEmpty());
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
