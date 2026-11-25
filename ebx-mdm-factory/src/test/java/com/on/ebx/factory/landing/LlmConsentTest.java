package com.on.ebx.factory.landing;

import java.util.Arrays;

import com.on.ebx.factory.wizard.WizardPage;

/** JUnit-free: run the main, a failure throws. Landing test fixes (2026-10-17, defect 7): the re-run checkbox follows the last choice. */
public final class LlmConsentTest
{
	public static void main(final String[] pArgs)
	{
		LlmConsentTest.check("no run yet: unchecked", !LlmConsent.lastChoice(null) && !LlmConsent.lastChoice("") && !LlmConsent.lastChoice("{not json"));
		// the walkthrough said No on every page: useLlm travels as true (hidden field) with an EMPTY list of applications that said yes
		LlmConsentTest.check("walkthrough No everywhere: unchecked", !LlmConsent.lastChoice("{\"projectCode\":\"B2C\",\"useLlm\":true,\"llmSourceIds\":[]}"));
		LlmConsentTest.check("walkthrough Yes on one application: checked", LlmConsent.lastChoice("{\"useLlm\":true,\"llmSourceIds\":[\"3\"]}")
			&& LlmConsent.lastSources("{\"useLlm\":true,\"llmSourceIds\":[\"3\",\"5\"]}").equals(Arrays.asList("3", "5")));
		LlmConsentTest.check("one-click run, box ticked: checked", LlmConsent.lastChoice("{\"useLlm\":true}") && LlmConsent.lastSources("{\"useLlm\":true}").isEmpty());
		LlmConsentTest.check("one-click run, box unticked: unchecked", !LlmConsent.lastChoice("{\"useLlm\":false}") && LlmConsent.lastSources("{\"useLlm\":false,\"llmSourceIds\":[\"3\"]}").isEmpty());

		// the form renders what the model carries
		final WizardPage.Model fresh = new WizardPage.Model();
		LlmConsentTest.check("a model nobody answered for defaults to unchecked", !fresh.landingRunUseLlm);
		System.out.println("LlmConsentTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
