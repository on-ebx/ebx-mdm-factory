package com.on.ebx.factory.master;

/**
 * JUnit-free, like the sibling tests: run the main, a failure throws. Moved here 2026-09-22 from
 * {@code ebx.RecordLineageReaderTest} when the naming convention itself moved to this shared, pure class - see
 * {@link MameMetadataset}'s own Javadoc for the convention and the one live example these cases are based on.
 */
public final class MameMetadatasetTest
{
	public static void main(final String[] pArgs)
	{
		MameMetadatasetTest.name_simpleTable();
		MameMetadatasetTest.name_nestedTableTakesLastStep();
		MameMetadatasetTest.name_trailingSlashIgnored();
		MameMetadatasetTest.name_blankInputsAnswerNull();
		MameMetadatasetTest.lastPathStep_variousShapes();
		System.out.println("MameMetadatasetTest OK");
	}

	/** The one live example measured 2026-09-22: dataset {@code Master}, table {@code /root/Company} -> {@code Master_Company_MDS} - NOT the older {@code <dataspace>_<dataset>_MDS} convention (see {@link MameMetadataset}'s own Javadoc). */
	private static void name_simpleTable()
	{
		MameMetadatasetTest.check("Master + /root/Company -> Master_Company_MDS", "Master_Company_MDS".equals(MameMetadataset.name("Master", "/root/Company")));
	}

	/** A nested table path takes its LAST step only, never the whole path. */
	private static void name_nestedTableTakesLastStep()
	{
		MameMetadatasetTest.check("nested path takes the last step", "Master_Contact_MDS".equals(MameMetadataset.name("Master", "/root/Company/Contact")));
	}

	/** A trailing slash on the table path does not change the derived step. */
	private static void name_trailingSlashIgnored()
	{
		MameMetadatasetTest.check("trailing slash ignored", "Master_Company_MDS".equals(MameMetadataset.name("Master", "/root/Company/")));
	}

	/** A blank dataset name or an unparsable table path answers null, never a malformed name. */
	private static void name_blankInputsAnswerNull()
	{
		MameMetadatasetTest.check("blank dataset name -> null", MameMetadataset.name("", "/root/Company") == null);
		MameMetadatasetTest.check("null dataset name -> null", MameMetadataset.name(null, "/root/Company") == null);
		MameMetadatasetTest.check("blank table path -> null", MameMetadataset.name("Master", "") == null);
		MameMetadatasetTest.check("null table path -> null", MameMetadataset.name("Master", null) == null);
		MameMetadatasetTest.check("a table path of only slashes -> null", MameMetadataset.name("Master", "///") == null);
	}

	private static void lastPathStep_variousShapes()
	{
		MameMetadatasetTest.check("bare name, no slash", "Company".equals(MameMetadataset.lastPathStep("Company")));
		MameMetadatasetTest.check("one slash", "Company".equals(MameMetadataset.lastPathStep("/Company")));
		MameMetadatasetTest.check("root-prefixed", "Company".equals(MameMetadataset.lastPathStep("/root/Company")));
		MameMetadatasetTest.check("nested", "Contact".equals(MameMetadataset.lastPathStep("/root/Company/Contact")));
		MameMetadatasetTest.check("trailing slash", "Company".equals(MameMetadataset.lastPathStep("/root/Company/")));
		MameMetadatasetTest.check("blank -> null", MameMetadataset.lastPathStep("") == null);
		MameMetadatasetTest.check("null -> null", MameMetadataset.lastPathStep(null) == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
