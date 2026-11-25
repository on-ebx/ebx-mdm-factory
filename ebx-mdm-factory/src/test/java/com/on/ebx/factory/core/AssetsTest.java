package com.on.ebx.factory.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class AssetsTest
{
	public static void main(final String[] pArgs)
	{
		AssetsTest.presentFileWithBuildKeyIsUsedVerbatim();
		AssetsTest.presentFileWithBlankBuildKeyFallsBack();
		AssetsTest.presentFileWithNoBuildKeyFallsBack();
		AssetsTest.absentFileFallsBack();
		AssetsTest.unreadableStreamFallsBackRatherThanThrowing();
		AssetsTest.versionIsNeverBlank();
		System.out.println("AssetsTest OK");
	}

	private static InputStream properties(final String pContent)
	{
		return new ByteArrayInputStream(pContent.getBytes(StandardCharsets.ISO_8859_1));
	}

	private static void presentFileWithBuildKeyIsUsedVerbatim()
	{
		final String resolved = Assets.resolve(AssetsTest.properties("build=20260921163000\n"), 999L);
		AssetsTest.check("the real build stamp is used, not the fallback", "20260921163000".equals(resolved));
	}

	private static void presentFileWithBlankBuildKeyFallsBack()
	{
		final String resolved = Assets.resolve(AssetsTest.properties("build=\n"), 42L);
		AssetsTest.check("a blank build value falls back", "42".equals(resolved));
	}

	private static void presentFileWithNoBuildKeyFallsBack()
	{
		final String resolved = Assets.resolve(AssetsTest.properties("somethingElse=x\n"), 43L);
		AssetsTest.check("a file with no build key falls back", "43".equals(resolved));
	}

	/** The Eclipse-Clean case (Assets' own Javadoc): build.properties genuinely does not exist on the classpath - Class#getResourceAsStream then returns null, never throws. */
	private static void absentFileFallsBack()
	{
		final String resolved = Assets.resolve(null, 44L);
		AssetsTest.check("a missing file falls back", "44".equals(resolved));
	}

	/** A stream that throws on read (a corrupted/unreadable file, in principle) must fall back, never fail class initialisation over a cache-busting value. */
	private static void unreadableStreamFallsBackRatherThanThrowing()
	{
		final InputStream broken = new InputStream()
		{
			@Override
			public int read() throws IOException
			{
				throw new IOException("simulated read failure");
			}
		};
		final String resolved = Assets.resolve(broken, 45L);
		AssetsTest.check("an unreadable stream falls back rather than throwing", "45".equals(resolved));
	}

	/** {@link Assets#version()} itself (the real classpath lookup, whatever build.sh or the Eclipse-Clean fallback produced) is never null/blank. */
	private static void versionIsNeverBlank()
	{
		final String version = Assets.version();
		AssetsTest.check("Assets.version() is never null/blank", version != null && !version.trim().isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
