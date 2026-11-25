package com.on.ebx.factory.distribution;

/** JUnit-free: run the main, a failure throws. The validate-only IDE route's answer read into one sentence (lot S3). */
public final class ScriptValidateReportTest
{
	public static void main(final String[] pArgs)
	{
		final String source = "export procedure onAfterCreate()\nbegin\n\tvar x := 1;\n\tvar y := bad;\nend\n";
		final String ok = ScriptValidateReport.describe(200, "{\"isSuccess\":true,\"messages\":[]}", source);
		ScriptValidateReportTest.check("ok report: " + ok, ok.contains("HTTP 200") && ok.contains("isSuccess=true") && !ok.contains("first failing"));

		final String crash = ScriptValidateReport.describe(500, "", source);
		ScriptValidateReportTest.check("bare 500 says the compiler crashed: " + crash, crash.contains("HTTP 500") && crash.contains("compiler crashed"));

		final String html = ScriptValidateReport.describe(404, "<html>nope</html>", source);
		ScriptValidateReportTest.check("non-JSON body is quoted: " + html, html.contains("HTTP 404") && html.contains("<html>nope</html>"));

		final String failed = ScriptValidateReport.describe(200,
			"{\"isSuccess\":false,\"messages\":[{\"startLine\":4,\"startColumn\":11,\"message\":\"Cannot resolve symbol 'bad'\"},{\"line\":1,\"message\":\"second\"}]}", source);
		ScriptValidateReportTest.check("failure names line:column and message: " + failed, failed.contains("isSuccess=false") && failed.contains("4:11: Cannot resolve symbol 'bad'") && failed.contains("1: second"));
		ScriptValidateReportTest.check("failure quotes the first failing statement: " + failed, failed.contains("first failing statement (line 4): var y := bad;"));

		final String plain = ScriptValidateReport.describe(200, "{\"isSuccess\":false,\"messages\":[\"boom\"]}", source);
		ScriptValidateReportTest.check("string messages kept, no statement: " + plain, plain.contains("boom") && !plain.contains("first failing"));

		ScriptValidateReportTest.check("out of range line gives no statement", ScriptValidateReport.statementAt(source, 99).isEmpty() && ScriptValidateReport.statementAt(null, 1).isEmpty());
		System.out.println("ScriptValidateReportTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
