package com.on.ebx.factory.ebx;

/** JUnit-free: run the main, a failure throws. F3-12 (1.4.2): the project written on a SourceDrift row is checked against the project of its landing table before the second permissions pass. */
public final class SourceDriftProjectCrossCheckTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		SourceDriftProjectCrossCheckTest.check("the same project passes", SourceDrifts.sameProject("P1", "P1") && SourceDrifts.sameProject(" P1", "P1 "));
		SourceDriftProjectCrossCheckTest.check("another project is a mismatch", !SourceDrifts.sameProject("P1", "P2"));
		SourceDriftProjectCrossCheckTest.check("a landing table with no project (broken link) is a mismatch", !SourceDrifts.sameProject("P1", null) && !SourceDrifts.sameProject(null, "P1")
			&& !SourceDrifts.sameProject("", ""));
		final String source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("mdmf.sourceDir"), "com/on/ebx/factory/ebx/SourceDrifts.java")),
			java.nio.charset.StandardCharsets.UTF_8);
		final int gate = source.indexOf("public static String projectOrRefuse");
		final String body = gate < 0 ? "" : source.substring(gate, source.indexOf("static boolean sameProject", gate));
		SourceDriftProjectCrossCheckTest.check("the route gate refuses a mismatch with a code and answers the REAL project", body.contains("landingProjectOf(") && body.contains("invalid.driftProjectMismatch")
			&& body.contains("return real;"));
		System.out.println("SourceDriftProjectCrossCheckTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new IllegalStateException("FAILED: " + pWhat);
		}
	}
}
