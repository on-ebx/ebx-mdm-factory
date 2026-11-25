package com.on.ebx.factory.core;

import java.security.CodeSource;

/**
 * JUnit-free. X3-08 (1.4.1): the suite really runs on the Jackson the package ships. {@code tools/test_local.sh} puts the module's pinned jars (WEB-INF/lib) BEFORE the server
 * {@code lib/} (a FastTrack lib/ holds Jackson 2.14.2) and passes the pinned version as {@code -Dmdmf.expectedJackson}; this test prints the version and the jar the three
 * Jackson artifacts were loaded from (the line to cite as proof in the journal of the suite) and fails when they are not the pinned version. Run by hand without the property it
 * only prints.
 */
public final class JacksonLoadedTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final String core = com.fasterxml.jackson.core.json.PackageVersion.VERSION.toString();
		final String databind = com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString();
		final String annotations = com.fasterxml.jackson.annotation.JsonProperty.class.getProtectionDomain().getCodeSource() == null ? "?" : JacksonLoadedTest.location(com.fasterxml.jackson.annotation.JsonProperty.class);
		System.out.println("Jackson loaded by the tests: jackson-core " + core + " from " + JacksonLoadedTest.location(com.fasterxml.jackson.core.JsonFactory.class)
			+ " ; jackson-databind " + databind + " from " + JacksonLoadedTest.location(com.fasterxml.jackson.databind.ObjectMapper.class)
			+ " ; jackson-annotations from " + annotations);
		final String expected = System.getProperty("mdmf.expectedJackson");
		if (expected != null && !expected.isEmpty())
		{
			JacksonLoadedTest.check("jackson-core is " + expected + " (was " + core + ")", expected.equals(core));
			JacksonLoadedTest.check("jackson-databind is " + expected + " (was " + databind + ")", expected.equals(databind));
			for (final Class<?> type : new Class<?>[] { com.fasterxml.jackson.core.JsonFactory.class, com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.annotation.JsonProperty.class })
			{
				JacksonLoadedTest.check(type.getSimpleName() + " comes from the pinned jar " + JacksonLoadedTest.location(type), JacksonLoadedTest.location(type).contains(expected + ".jar"));
			}
		}
		System.out.println("JacksonLoadedTest OK");
	}

	private static String location(final Class<?> pType)
	{
		final CodeSource source = pType.getProtectionDomain().getCodeSource();
		return source == null ? "?" : source.getLocation().toString();
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
