package com.on.ebx.factory.ebx;

import java.util.Locale;

import com.on.ebx.factory.wizard.WizardSnapshot;

/**
 * JUnit-free (lot C1-B): the hooks are wired to the passes and the generator; a run that cannot start answers a coded refusal the wizard shows as a notice. No EBX
 * repository is needed.
 */
public final class AccelWizardTest
{
	private AccelWizardTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		AccelWizardTest.aBlockedAnswerBecomesTheCodedRefusal();
		AccelWizardTest.theCatalogueStandInReadsTheShippedPacks();
		System.out.println("AccelWizardTest OK");
	}

	private static void aBlockedAnswerBecomesTheCodedRefusal() throws Exception
	{
		final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		AccelHooks.requireStarted(mapper.createObjectNode().put("started", true));
		final String[][] cases = { { "accel.pass.noModel", "" }, { "accel.pass.noPack", "" }, { "accel.pass.skip.waiting", "1" } };
		for (final String[] one : cases)
		{
			try
			{
				AccelHooks.requireStarted(mapper.createObjectNode().put("started", false).put("reasonKey", one[0]).put("reasonArg", one[1]));
				throw new AssertionError(one[0] + " should refuse");
			}
			catch (final EngineRefusal ex)
			{
				AccelWizardTest.check(one[0] + " keeps its key", one[0].equals(ex.code()));
				AccelWizardTest.check(one[0] + " has an English sentence", !ex.sentence(Locale.ENGLISH).equals(one[0]) && !ex.sentence(Locale.ENGLISH).isEmpty());
				AccelWizardTest.check(one[0] + " has a French sentence", !ex.sentence(Locale.FRENCH).equals(one[0]) && !ex.sentence(Locale.FRENCH).equals(ex.sentence(Locale.ENGLISH)));
			}
		}
	}

	private static void theCatalogueStandInReadsTheShippedPacks()
	{
		final java.util.List<WizardSnapshot.PackInfo> packs = AccelWizard.catalogue(Locale.ENGLISH);
		AccelWizardTest.check("the two shipped packs", packs.size() == 2);
		WizardSnapshot.PackInfo company = null;
		WizardSnapshot.PackInfo reference = null;
		for (final WizardSnapshot.PackInfo pack : packs)
		{
			company = "company".equals(pack.key) ? pack : company;
			reference = "reference-data".equals(pack.key) ? pack : reference;
		}
		AccelWizardTest.check("company is there with a label, a version, tables and fields", company != null && !company.label.isEmpty() && !company.version.isEmpty() && company.tables > 0 && company.fields > 0);
		AccelWizardTest.check("company requires the reference data and brings blocks and options", company.requires.contains("reference-data") && !company.blocks.isEmpty() && !company.options.isEmpty());
		AccelWizardTest.check("the reference data carries rows", reference != null && reference.referenceRows > 1000);
		AccelWizardTest.check("the French catalogue has French labels", !AccelWizard.catalogue(Locale.FRENCH).get(0).label.equals(packs.get(0).label));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
