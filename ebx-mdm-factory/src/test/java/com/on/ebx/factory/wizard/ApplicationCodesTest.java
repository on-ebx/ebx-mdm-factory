package com.on.ebx.factory.wizard;

import java.util.HashSet;
import java.util.Set;

/** JUnit-free: run the main, a failure throws. Exercises {@link ApplicationCodes} (UX lot U2, 2026-10-13). */
public final class ApplicationCodesTest
{
	public static void main(final String[] pArgs)
	{
		ApplicationCodesTest.salesforceWithoutSchemaGetsACode();
		ApplicationCodesTest.aSpecificSchemaStillWins();
		ApplicationCodesTest.genericSchemaFallsBackOnTheName();
		ApplicationCodesTest.slugs();
		ApplicationCodesTest.uniqueAndWithinLimits();
		System.out.println("ApplicationCodesTest OK");
	}

	private static Set<String> none()
	{
		return new HashSet<String>();
	}

	/** Measured live: no schema, catalog label "salesforce://login.salesforce.com", connection label "Salesforce" - the field stayed empty. */
	private static void salesforceWithoutSchemaGetsACode()
	{
		final String code = ApplicationCodes.propose("", "salesforce://login.salesforce.com", "Salesforce", "Salesforce", ApplicationCodesTest.none());
		ApplicationCodesTest.check("name of the connection, not of the URL: " + code, "SALESFORCE".equals(code));
		ApplicationCodesTest.check("only a technical label left: its scheme", "SALESFORCE".equals(ApplicationCodes.propose(null, "salesforce://login.salesforce.com", "", "", ApplicationCodesTest.none())));
		ApplicationCodesTest.check("a human catalog label wins over the connection id", "SALESFORCE_CRM_EU_PRODUCTION".equals(ApplicationCodes.propose("", "Salesforce CRM (EU production)", "", "SF1", ApplicationCodesTest.none())));
		ApplicationCodesTest.check("nothing at all: a fixed fallback", "APP".equals(ApplicationCodes.propose("", "", "", "", ApplicationCodesTest.none())));
	}

	private static void aSpecificSchemaStillWins()
	{
		ApplicationCodesTest.check("ERP_SOURCE gives ERP", "ERP".equals(ApplicationCodes.propose("ERP_SOURCE", "Snowflake demo", "Snowflake", "SNOW", ApplicationCodesTest.none())));
	}

	private static void genericSchemaFallsBackOnTheName()
	{
		ApplicationCodesTest.check("PUBLIC is not an application", "DEMOCRM".equals(ApplicationCodes.propose("PUBLIC", "DemoCRM", "Demo CRM", "DemoCRM", ApplicationCodesTest.none())));
	}

	private static void slugs()
	{
		ApplicationCodesTest.check("spaces and punctuation", "MY_CRM_EU".equals(ApplicationCodes.slug("  my CRM (EU)! ")));
		ApplicationCodesTest.check("starts with a letter", "S_2024_ERP".equals(ApplicationCodes.slug("2024 ERP")));
		ApplicationCodesTest.check("no letter or digit", "".equals(ApplicationCodes.slug("()--")) && "".equals(ApplicationCodes.slug(null)));
		ApplicationCodesTest.check("accents are not letters of a code", "CAF".equals(ApplicationCodes.slug("Café")));
	}

	private static void uniqueAndWithinLimits()
	{
		final Set<String> taken = new HashSet<String>();
		taken.add("SALESFORCE");
		ApplicationCodesTest.check("taken: suffix", "SALESFORCE_2".equals(ApplicationCodes.propose("", "", "Salesforce", "", taken)));
		final StringBuilder longName = new StringBuilder();
		for (int i = 0; i < 12; i++)
		{
			longName.append("Customer ");
		}
		final String code = ApplicationCodes.propose("", longName.toString(), "", "", ApplicationCodesTest.none());
		ApplicationCodesTest.check("within the limit and the pattern: " + code, code.length() <= WizardRules.CODE_MAX && code.matches(WizardRules.CODE_PATTERN));
		final Set<String> takenLong = new HashSet<String>();
		takenLong.add(code);
		final String second = ApplicationCodes.propose("", longName.toString(), "", "", takenLong);
		ApplicationCodesTest.check("a suffixed long code still fits: " + second, second.length() <= WizardRules.CODE_MAX && !second.equals(code) && second.matches(WizardRules.CODE_PATTERN));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
