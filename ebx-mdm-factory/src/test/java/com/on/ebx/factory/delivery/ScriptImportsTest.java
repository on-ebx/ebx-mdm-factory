package com.on.ebx.factory.delivery;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** JUnit-free: run the main, a failure throws. Sources are the real headers of the Fasttrack libraries read on 2026-09-22. */
public final class ScriptImportsTest
{
	public static void main(final String[] pArgs)
	{
		final String phone = "/** Fasttrack.lib.phone */\n\nuses core.string as str;\n\nimport Fasttrack.lib.validator.regex as rx;\n\nfunction f(): string\nbegin\n  return '';\nend\n";
		final String regex = "uses core.string as str;\n\nfunction subjectMark(): string\nbegin\n  return '\\t';\nend\n";
		final String iban = "uses core.string as str;\nuses core.dictionary as dict;\nuses core.json as json;\n";
		final String twoImports = "import A.b as ab;\nimport C.d as cd;\nimport A.b as again;\n// import Not.this as no\n";

		final List<String> phoneImports = ScriptImports.importsOf(phone);
		ScriptImportsTest.check("uses lines are not imports; the one import is found", phoneImports.equals(Arrays.asList("Fasttrack.lib.validator.regex")));
		ScriptImportsTest.check("no import", ScriptImports.importsOf(iban).isEmpty() && ScriptImports.importsOf(null).isEmpty());
		ScriptImportsTest.check("each import once, in order, a commented one ignored", ScriptImports.importsOf(twoImports).equals(Arrays.asList("A.b", "C.d")));
		ScriptImportsTest.check("ide path", "library/Fasttrack/lib/phone".equals(ScriptImports.idePath("Fasttrack.lib.phone")));
		ScriptImportsTest.check("staging element id", "definition.library.Fasttrack.lib.phone".equals(ScriptImports.stagingElementId("Fasttrack.lib.phone")));

		final Map<String, String> sources = new HashMap<String, String>();
		sources.put("Fasttrack.lib.phone", phone);
		sources.put("Fasttrack.lib.validator.regex", regex);
		sources.put("Fasttrack.lib.iban", iban);
		final ScriptImports.Closure closure = ScriptImports.closure(Arrays.asList("Fasttrack.lib.phone", "Fasttrack.lib.iban", "Fasttrack.lib.phone"), sources::get);
		ScriptImportsTest.check("closure: roots first, then the transitive import, each once",
			closure.libraries.equals(Arrays.asList("Fasttrack.lib.phone", "Fasttrack.lib.iban", "Fasttrack.lib.validator.regex")) && closure.missing.isEmpty());
		final ScriptImports.Closure missing = ScriptImports.closure(Arrays.asList("Fasttrack.lib.phone", "Nowhere.lib"), sources::get);
		ScriptImportsTest.check("a library without a source is reported missing, the others still closed",
			missing.missing.equals(Arrays.asList("Nowhere.lib")) && missing.libraries.contains("Fasttrack.lib.validator.regex"));
		final Map<String, String> cyclic = new HashMap<String, String>();
		cyclic.put("A", "import B as b;"); cyclic.put("B", "import A as a;");
		ScriptImportsTest.check("a cycle ends", ScriptImports.closure(Arrays.asList("A"), cyclic::get).libraries.equals(Arrays.asList("A", "B")));
		System.out.println("ScriptImportsTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
