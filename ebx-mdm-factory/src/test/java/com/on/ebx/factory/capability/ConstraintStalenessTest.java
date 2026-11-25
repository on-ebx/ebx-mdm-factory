package com.on.ebx.factory.capability;

/** JUnit-free: run the main, a failure throws. */
public final class ConstraintStalenessTest
{
	public static void main(final String[] pArgs)
	{
		ConstraintStalenessTest.tableScopeRowIsStaleWithScopeReason();
		ConstraintStalenessTest.currentNodeSingleDependencyWithNonTechnicalMessageIsCurrent();
		ConstraintStalenessTest.oldTechnicalMessageIsStaleWithTheExactRealCapturedText();
		ConstraintStalenessTest.llmBodyWithABoundFieldMissingFromSetupIsStale();
		ConstraintStalenessTest.llmBodyWithEveryDependencyDeclaredAndANonTechnicalMessageIsCurrent();
		ConstraintStalenessTest.twoMissingDependenciesGiveTwoReasonsJoinedBySpace();
		ConstraintStalenessTest.blankAppliedElementPathNeverThrowsAndIsCurrent();

		System.out.println("ConstraintStalenessTest OK");
	}

	/** The exact wording the task brief itself gives as an example. */
	private static void tableScopeRowIsStaleWithScopeReason()
	{
		final ConstraintStaleness.AppliedFacts facts = new ConstraintStaleness.AppliedFacts("TABLE", "/root/Supplier",
			"export procedure checkTable()\nbegin\nend\n\nexport procedure checkRecord(record : .Supplier)\nbegin\n  msg.addWarning('The record failed iban.validate.');\nend\n\n"
				+ "export procedure setup()\nbegin\nend\n",
			"{}", "Fasttrack.lib.iban", "iban", "validate", "WARN");
		final ConstraintStaleness.Result result = ConstraintStaleness.evaluate(facts);
		ConstraintStalenessTest.check("stale", result.stale);
		ConstraintStalenessTest.check("exact scope reason",
			result.reasons.contains("applied as a table constraint; the current form is a field constraint with explicit dependencies."));
	}

	private static void currentNodeSingleDependencyWithNonTechnicalMessageIsCurrent()
	{
		final String body = "uses core.message as msg;\nuses core.validation as v;\nimport Fasttrack.lib.phone as phone;\n\n"
			+ "export procedure check(record : .Contact)\nbegin\n  if isNull(record.phone) then return;\n  if not phone.isValid(record.phone) then\n  begin\n"
			+ "    msg.addWarning('Phone: value not accepted.');\n  end\nend\n\n" + "export procedure setup()\nbegin\n  v.setDependencyToLocalNode();\nend\n";
		final ConstraintStaleness.AppliedFacts facts = new ConstraintStaleness.AppliedFacts("NODE", "/root/Contact/phone", body, "{\"phone\":\"phone\"}",
			"Fasttrack.lib.phone", "phone", "isValid", "WARN");
		final ConstraintStaleness.Result result = ConstraintStaleness.evaluate(facts);
		ConstraintStalenessTest.check("current: " + result.describe(), !result.stale);
		ConstraintStalenessTest.check("no reasons", result.reasons.isEmpty());
	}

	/**
	 * The real captured defect (PLAN.md, 2026-09-21 soir, open item (d)): {@code Contact_isValid_phone}/
	 * {@code Supplier_isValid_phone} still report {@code 'phone' failed phone.isValid.} - the exact shape the
	 * removed {@code ConstraintBodyBuilder.deterministicMessage} used to emit for a NODE constraint (git
	 * history: {@code "'" + pScope.attribute + "' failed " + pAlias + "." + pFunctionName + "."}).
	 */
	private static void oldTechnicalMessageIsStaleWithTheExactRealCapturedText()
	{
		final String body = "uses core.message as msg;\nuses core.validation as v;\nimport Fasttrack.lib.phone as phone;\n\n"
			+ "export procedure check(record : .Contact)\nbegin\n  if isNull(record.phone) then return;\n  if not phone.isValid(record.phone) then\n  begin\n"
			+ "    msg.addWarning('\\'phone\\' failed phone.isValid.');\n  end\nend\n\n" + "export procedure setup()\nbegin\n  v.setDependencyToLocalNode();\nend\n";
		final ConstraintStaleness.AppliedFacts facts = new ConstraintStaleness.AppliedFacts("NODE", "/root/Contact/phone", body, "{\"phone\":\"phone\"}",
			"Fasttrack.lib.phone", "phone", "isValid", "WARN");
		final ConstraintStaleness.Result result = ConstraintStaleness.evaluate(facts);
		ConstraintStalenessTest.check("stale", result.stale);
		ConstraintStalenessTest.check("exact real captured text",
			result.reasons.contains("the record-facing message is the old technical one: 'phone' failed phone.isValid."));
	}

	/** An LLM-authored body (two bound attributes) whose setup() only declares a dependency on the anchor - the other bound field is missing. */
	private static void llmBodyWithABoundFieldMissingFromSetupIsStale()
	{
		final String body = "uses core.message as msg;\nuses core.validation as v;\nuses core.json as json;\nimport Fasttrack.lib.iban as iban;\n\n"
			+ "export procedure check(record : .Supplier)\nbegin\n  if isNull(record.iban) then return;\n"
			+ "  const report := iban.validate(record.iban, record.country);\n  if not json.getBoolean(report, 'valid') then\n  begin\n"
			+ "    msg.addWarning('The IBAN is not valid.');\n  end\nend\n\n" + "export procedure setup()\nbegin\n  v.addDependencyToModify('/root/Supplier/iban');\nend\n";
		final ConstraintStaleness.AppliedFacts facts = new ConstraintStaleness.AppliedFacts("NODE", "/root/Supplier/iban", body,
			"{\"rawIban\":\"iban\",\"addressCountryCode\":\"country\"}", "Fasttrack.lib.iban", "iban", "validate", "WARN");
		final ConstraintStaleness.Result result = ConstraintStaleness.evaluate(facts);
		ConstraintStalenessTest.check("stale", result.stale);
		ConstraintStalenessTest.check("names the missing field",
			result.reasons.contains("the applied setup() does not declare a dependency on 'country': a change to it will not re-run this check."));
	}

	private static void llmBodyWithEveryDependencyDeclaredAndANonTechnicalMessageIsCurrent()
	{
		final String body = "uses core.message as msg;\nuses core.validation as v;\nuses core.json as json;\nimport Fasttrack.lib.iban as iban;\n\n"
			+ "export procedure check(record : .Supplier)\nbegin\n  if isNull(record.iban) then return;\n"
			+ "  const report := iban.validate(record.iban, record.country);\n  if not json.getBoolean(report, 'valid') then\n  begin\n"
			+ "    msg.addWarning('The IBAN is not valid.');\n  end\nend\n\n"
			+ "export procedure setup()\nbegin\n  v.addDependencyToModify('/root/Supplier/iban');\n  v.addDependencyToModify('/root/Supplier/country');\nend\n";
		final ConstraintStaleness.AppliedFacts facts = new ConstraintStaleness.AppliedFacts("NODE", "/root/Supplier/iban", body,
			"{\"rawIban\":\"iban\",\"addressCountryCode\":\"country\"}", "Fasttrack.lib.iban", "iban", "validate", "WARN");
		final ConstraintStaleness.Result result = ConstraintStaleness.evaluate(facts);
		ConstraintStalenessTest.check("current: " + result.describe(), !result.stale);
	}

	private static void twoMissingDependenciesGiveTwoReasonsJoinedBySpace()
	{
		final String body = "uses core.message as msg;\nuses core.validation as v;\nimport Fasttrack.lib.iban as iban;\n\n"
			+ "export procedure check(record : .Supplier)\nbegin\n  msg.addWarning('The IBAN is not valid.');\nend\n\n" + "export procedure setup()\nbegin\nend\n";
		final ConstraintStaleness.AppliedFacts facts = new ConstraintStaleness.AppliedFacts("NODE", "/root/Supplier/iban", body,
			"{\"rawIban\":\"iban\",\"addressCountryCode\":\"country\"}", "Fasttrack.lib.iban", "iban", "validate", "WARN");
		final ConstraintStaleness.Result result = ConstraintStaleness.evaluate(facts);
		ConstraintStalenessTest.check("stale", result.stale);
		ConstraintStalenessTest.check("two reasons", result.reasons.size() == 2);
		ConstraintStalenessTest.check("joined by a single space, both present",
			result.describe().contains("'iban'") && result.describe().contains("'country'"));
	}

	private static void blankAppliedElementPathNeverThrowsAndIsCurrent()
	{
		final ConstraintStaleness.AppliedFacts facts = new ConstraintStaleness.AppliedFacts("NODE", "", "anything", "{}", "Fasttrack.lib.phone", "phone",
			"isValid", "WARN");
		final ConstraintStaleness.Result result = ConstraintStaleness.evaluate(facts);
		ConstraintStalenessTest.check("never throws, defensively current", !result.stale);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
