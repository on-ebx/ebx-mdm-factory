package com.on.ebx.factory.capability;

import java.util.Arrays;

/** JUnit-free: run the main, a failure throws. The token-based guard of a model-written constraint body (audit F-08), with the bypasses the audit lists. */
public final class ScriptGuardTest
{
	private static final String LIB = "Fasttrack.lib.iban";
	private static final String TAIL = "\nexport procedure check(record : .Contact)\nbegin\n  const r := iban.validate(record.iban, 'FR');\nend\n\nexport procedure setup()\nbegin\nend\n";

	public static void main(final String[] pArgs)
	{
		ScriptGuardTest.tokenizer();
		ScriptGuardTest.acceptedBodies();
		ScriptGuardTest.importBypasses();
		ScriptGuardTest.usesBypasses();
		ScriptGuardTest.forbiddenAccessBypasses();
		ScriptGuardTest.commentsAndStringsDoNotCount();
		ScriptGuardTest.entryPoints();
		System.out.println("ScriptGuardTest OK");
	}

	private static String node(final String pSource)
	{
		return ScriptGuard.firstViolation(pSource, ScriptGuardTest.LIB, "validate", true);
	}

	private static void tokenizer()
	{
		final java.util.List<ScriptGuard.Token> tokens = ScriptGuard.tokenize("a /* b */ . // c\n d 'x // y' + 1.5 \"q\" `s`");
		final StringBuilder shape = new StringBuilder();
		for (final ScriptGuard.Token token : tokens)
		{
			shape.append(token.kind.name()).append(' ');
		}
		ScriptGuardTest.check("comments vanish, a string is one token, 1.5 is one number, quotes are flagged: " + shape,
			shape.toString().equals("WORD SYMBOL WORD STRING SYMBOL NUMBER QUOTE WORD QUOTE QUOTE WORD QUOTE "));
		ScriptGuardTest.check("an unterminated comment and string end at the end of the text", ScriptGuard.tokenize("a /* b").size() == 1 && ScriptGuard.tokenize("a 'b").size() == 2);
		ScriptGuardTest.check("an escaped quote does not end the string", ScriptGuard.tokenize("x := 'it\\'s' ; y").size() == 6);
	}

	private static void acceptedBodies()
	{
		final String source = "uses core.message as msg;\nuses core.json as json;\nimport " + ScriptGuardTest.LIB + " as iban;\n\n"
			+ "export procedure check(record : .Contact)\nbegin\n  if isNull(record.iban) then return;\n"
			+ "  const report := iban.validate(record.iban, 'FR');\n  if json.getString(report, 'status') <> 'OK' then\n  begin\n"
			+ "    msg.addWarning('IBAN failed');\n  end\nend\n\nexport procedure setup()\nbegin\nend\n";
		ScriptGuardTest.check("the module's own good body passes: " + ScriptGuardTest.node(source), ScriptGuardTest.node(source) == null);
		ScriptGuardTest.check("a call written with spaces around the dot passes", ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\n"
			+ "export procedure check(record : .Contact)\nbegin\n  const r := iban . validate (record.iban);\nend\nexport procedure setup()\nbegin\nend\n") == null);
		ScriptGuardTest.check("a field that happens to be called db or core is not the module", ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\n"
			+ "export procedure check(record : .Contact)\nbegin\n  const r := iban.validate(record.db.core.data);\nend\nexport procedure setup()\nbegin\nend\n") == null);
		ScriptGuardTest.check("every allow-listed uses passes", ScriptGuardTest.node("uses core.message as m;\nuses core.validation;\nuses core.json as j;\nuses core.string as s;\nimport "
			+ ScriptGuardTest.LIB + " as iban;" + ScriptGuardTest.TAIL) == null);
		ScriptGuardTest.check("a message that talks about the database in a string passes", ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\n"
			+ "export procedure check(record : .Contact)\nbegin\n  const r := iban.validate(record.iban);\n  msg.addWarning('db.find(x) and core.http are not used');\nend\nexport procedure setup()\nbegin\nend\n") == null);
	}

	private static void importBypasses()
	{
		final String twoOnOneLine = "import " + ScriptGuardTest.LIB + " as iban; import Fasttrack.lib.phone as phone;" + ScriptGuardTest.TAIL;
		final String refused = ScriptGuardTest.node(twoOnOneLine);
		ScriptGuardTest.check("two imports on one line are counted: " + refused, refused != null && refused.contains("more than one library"));
		final String secondOnSameLineAsCode = ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\nconst z := 1; import Fasttrack.lib.phone as phone;" + ScriptGuardTest.TAIL);
		ScriptGuardTest.check("an import after other code on a line is counted", secondOnSameLineAsCode != null && secondOnSameLineAsCode.contains("more than one"));
		final String wrongLibrary = ScriptGuardTest.node("import Fasttrack.lib.phone as iban;" + ScriptGuardTest.TAIL);
		ScriptGuardTest.check("the wrong library is refused with the old sentence: " + wrongLibrary, wrongLibrary != null && wrongLibrary.contains("imports 'Fasttrack.lib.phone', not '"));
		final String none = ScriptGuardTest.node("export procedure check(record : .Contact)\nbegin\nend\n");
		ScriptGuardTest.check("no import keeps its sentence", none != null && none.startsWith("no line starts with 'import " + ScriptGuardTest.LIB + " as'"));
		ScriptGuardTest.check("an import with no semicolon is refused, not skipped", ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban\n" + ScriptGuardTest.TAIL) != null);
		ScriptGuardTest.check("an import split over lines is still the import", ScriptGuardTest.node("import\n" + ScriptGuardTest.LIB + "\nas\niban\n;" + ScriptGuardTest.TAIL) == null);
		ScriptGuardTest.check("an import in a comment does not count as a second one", ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\n// import Fasttrack.lib.phone as phone;\n"
			+ ScriptGuardTest.TAIL) == null);
	}

	private static void usesBypasses()
	{
		final String bare = ScriptGuardTest.node("uses core.http;\nimport " + ScriptGuardTest.LIB + " as iban;" + ScriptGuardTest.TAIL);
		ScriptGuardTest.check("uses without as is checked against the allow-list: " + bare, bare != null && bare.contains("uses core.http"));
		final String sameLine = ScriptGuardTest.node("uses core.message as msg; uses core.data as d;\nimport " + ScriptGuardTest.LIB + " as iban;" + ScriptGuardTest.TAIL);
		ScriptGuardTest.check("a second uses on the same line is checked: " + sameLine, sameLine != null && sameLine.contains("uses core.data as d"));
		final String afterCode = ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\nconst x := 1; uses core.file as f;" + ScriptGuardTest.TAIL);
		ScriptGuardTest.check("a uses after code is checked: " + afterCode, afterCode != null && afterCode.contains("uses core.file as f"));
		final String spaced = ScriptGuardTest.node("uses core /* nothing */ . http as h;\nimport " + ScriptGuardTest.LIB + " as iban;" + ScriptGuardTest.TAIL);
		ScriptGuardTest.check("a name split by a comment is read as one name: " + spaced, spaced != null && spaced.contains("uses core.http as h"));
		ScriptGuardTest.check("a uses with no name is refused", ScriptGuardTest.node("uses ;\nimport " + ScriptGuardTest.LIB + " as iban;" + ScriptGuardTest.TAIL) != null);
	}

	private static void forbiddenAccessBypasses()
	{
		final String[] accesses = { "core.http.get('x')", "core . http . get('x')", "core /* hidden */ . http", "core.\nhttp", "core // c\n.data", "db.findTable('/root/T')", "db .\n findTable('/root/T')",
			"db /* c */ . field<int>(t, 'a')", "core.file.read('x')" };
		for (final String access : accesses)
		{
			final String source = "import " + ScriptGuardTest.LIB + " as iban;\nexport procedure check(record : .Contact)\nbegin\n  const r := iban.validate(record.iban);\n  const x := " + access
				+ ";\nend\nexport procedure setup()\nbegin\nend\n";
			final String refused = ScriptGuardTest.node(source);
			ScriptGuardTest.check("refused: " + access.replace('\n', ' ') + " -> " + refused, refused != null);
		}
		final String http = ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\nexport procedure check(record : .Contact)\nbegin\n  const r := iban.validate(1);\n  core .\n http.get('x');\nend\n"
			+ "export procedure setup()\nbegin\nend\n");
		ScriptGuardTest.check("the original database/outbound sentence is kept: " + http, ScriptGuard.DATABASE_MESSAGE.equals(http));
		final String sql = ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\nexport procedure check(record : .Contact)\nbegin\n  const r := iban.validate(1);\n  const q := sql `select 1`;\nend\n"
			+ "export procedure setup()\nbegin\nend\n");
		ScriptGuardTest.check("an embedded SQL literal is refused: " + sql, sql != null && sql.contains("SQL"));
		final String dq = ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\nexport procedure check(record : .Contact)\nbegin\n  const r := iban.validate(1);\n  const q := \"x\";\nend\n"
			+ "export procedure setup()\nbegin\nend\n");
		ScriptGuardTest.check("a double-quoted literal is refused: " + dq, dq != null && dq.contains("double-quoted"));
	}

	private static void commentsAndStringsDoNotCount()
	{
		final String commentedCall = ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\nexport procedure check(record : .Contact)\nbegin\n  // iban.validate(record.iban);\n  /* iban.validate(x) */\nend\n"
			+ "export procedure setup()\nbegin\nend\n");
		ScriptGuardTest.check("a call that is only in a comment is not a call: " + commentedCall, commentedCall != null && commentedCall.contains("never calls"));
		final String stringCall = ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\nexport procedure check(record : .Contact)\nbegin\n  const s := 'iban.validate(x)';\nend\n"
			+ "export procedure setup()\nbegin\nend\n");
		ScriptGuardTest.check("a call that is only in a string is not a call", stringCall != null && stringCall.contains("never calls"));
		final String commentedEntry = ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\n// export procedure check(record : .Contact)\nconst r := iban.validate(1);\n"
			+ "export procedure setup()\nbegin\nend\n");
		ScriptGuardTest.check("an entry point that is only in a comment is not declared: " + commentedEntry, commentedEntry != null && commentedEntry.contains("export procedure check("));
		final String commentOpener = ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\n/* uses core.http as h; */\n" + ScriptGuardTest.TAIL);
		ScriptGuardTest.check("a uses inside a comment is no uses", commentOpener == null);
		final String quoteInString = ScriptGuardTest.node("import " + ScriptGuardTest.LIB + " as iban;\nexport procedure check(record : .Contact)\nbegin\n  const r := iban.validate(1);\n  const s := 'say \"hi\" and `x`';\nend\n"
			+ "export procedure setup()\nbegin\nend\n");
		ScriptGuardTest.check("quotes inside a single-quoted string are just text: " + quoteInString, quoteInString == null);
	}

	private static void entryPoints()
	{
		final String head = "import " + ScriptGuardTest.LIB + " as iban;\n";
		final String noSetup = ScriptGuard.firstViolation(head + "export procedure check(r : .C)\nbegin\n const x := iban.validate(1);\nend\n", ScriptGuardTest.LIB, "validate", true);
		ScriptGuardTest.check("node: setup is required, old sentence: " + noSetup, "no line starts with 'export procedure setup(': a node constraint must define it too.".equals(noSetup));
		final String tableBody = head + "export procedure checkTable()\nbegin\nend\nexport procedure checkRecord(r : .C)\nbegin\n const x := iban.validate(1);\nend\nexport procedure setup()\nbegin\nend\n";
		ScriptGuardTest.check("table: all three declared passes", ScriptGuard.firstViolation(tableBody, ScriptGuardTest.LIB, "validate", false) == null);
		final String noCheckTable = ScriptGuard.firstViolation(head + "export procedure checkRecord(r : .C)\nbegin\n const x := iban.validate(1);\nend\nexport procedure setup()\nbegin\nend\n",
			ScriptGuardTest.LIB, "validate", false);
		ScriptGuardTest.check("table: checkTable is required, old sentence: " + noCheckTable,
			"no line starts with 'export procedure checkTable(': a table constraint must define it, even if empty.".equals(noCheckTable));
		final String noCheckRecord = ScriptGuard.firstViolation(head + "export procedure checkTable()\nbegin\nend\nexport procedure setup()\nbegin\n const x := iban.validate(1);\nend\n",
			ScriptGuardTest.LIB, "validate", false);
		ScriptGuardTest.check("table: checkRecord, old sentence: " + noCheckRecord, "no line starts with 'export procedure checkRecord(': a table constraint must define it.".equals(noCheckRecord));
		final String noTableSetup = ScriptGuard.firstViolation(head + "export procedure checkTable()\nbegin\nend\nexport procedure checkRecord(r : .C)\nbegin\n const x := iban.validate(1);\nend\n",
			ScriptGuardTest.LIB, "validate", false);
		ScriptGuardTest.check("table: setup, old sentence: " + noTableSetup, "no line starts with 'export procedure setup(': a table constraint must define it too, even if empty.".equals(noTableSetup));
		ScriptGuardTest.check("checkTable is not satisfied by checkTableX", ScriptGuard.firstViolation(head + "export procedure checkTableX()\nbegin\nend\nexport procedure checkRecord(r : .C)\nbegin\n const x := iban.validate(1);\nend\nexport procedure setup()\nbegin\nend\n",
			ScriptGuardTest.LIB, "validate", false) != null);
		ScriptGuardTest.check("allow-list is the four modules", ScriptGuard.ALLOWED_USES.equals(new java.util.LinkedHashSet<String>(Arrays.asList("core.message", "core.validation", "core.json", "core.string"))));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
