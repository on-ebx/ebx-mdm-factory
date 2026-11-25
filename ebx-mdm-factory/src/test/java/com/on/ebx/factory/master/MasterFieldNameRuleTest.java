package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import com.on.ebx.factory.staging.StagingModel;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class MasterFieldNameRuleTest
{
	public static void main(final String[] pArgs)
	{
		MasterFieldNameRuleTest.ordinaryNameUnchanged();
		MasterFieldNameRuleTest.liveCase_salesforceIdRenamedIdSrc_caseInsensitive();
		MasterFieldNameRuleTest.everyReservedNameRenamed();
		MasterFieldNameRuleTest.siblingClashAppendsSuffixAgain_deterministic();
		MasterFieldNameRuleTest.nullAndEmptySiblingsTolerated();
		MasterFieldNameRuleTest.masterFieldNames_primaryKeyAttributesNeverRenamed();
		System.out.println("MasterFieldNameRuleTest OK");
	}

	private static void ordinaryNameUnchanged()
	{
		MasterFieldNameRuleTest.check("name is not reserved", !MasterFieldNameRule.isReserved("name"));
		MasterFieldNameRuleTest.check("an ordinary attribute keeps its name", "name".equals(MasterFieldNameRule.masterFieldName("name", Arrays.asList("name", "Id"))));
		MasterFieldNameRuleTest.check("a name merely CONTAINING a reserved word is not reserved (identifier, siteId, idCard)",
			!MasterFieldNameRule.isReserved("identifier") && !MasterFieldNameRule.isReserved("siteId") && !MasterFieldNameRule.isReserved("idCard"));
		MasterFieldNameRuleTest.check("not renamed", !MasterFieldNameRule.isRenamed("identifier", Collections.<String>emptyList()));
	}

	/** The exact live refusal of 2026-09-23 (ScratchMatch / SalesforceSample / attribute Id): EBX resolved Id against the table's own id primary key. */
	private static void liveCase_salesforceIdRenamedIdSrc_caseInsensitive()
	{
		MasterFieldNameRuleTest.check("Id is reserved (case-insensitively)", MasterFieldNameRule.isReserved("Id") && MasterFieldNameRule.isReserved("ID"));
		MasterFieldNameRuleTest.check("Id -> Id_src (original casing kept, suffix appended)",
			"Id_src".equals(MasterFieldNameRule.masterFieldName("Id", Arrays.asList("sourceSystem", "sourceId", "Id", "Name", "Phone"))));
		MasterFieldNameRuleTest.check("reported as renamed", MasterFieldNameRule.isRenamed("Id", Collections.<String>emptyList()));
		MasterFieldNameRuleTest.check("no siblings at all: same answer (the rule never depends on siblings unless the suffixed name clashes)",
			"Id_src".equals(MasterFieldNameRule.masterFieldName("Id", null)));
	}

	private static void everyReservedNameRenamed()
	{
		for (final String reserved : MasterFieldNameRule.RESERVED)
		{
			final String upper = reserved.toUpperCase(java.util.Locale.ROOT);
			MasterFieldNameRuleTest.check(reserved + " -> " + reserved + "_src", (reserved + "_src").equals(MasterFieldNameRule.masterFieldName(reserved, null)));
			MasterFieldNameRuleTest.check(upper + " (other case) -> " + upper + "_src", (upper + "_src").equals(MasterFieldNameRule.masterFieldName(upper, null)));
		}
		MasterFieldNameRuleTest.check("exactly the five fields MasterModel puts on every table",
			MasterFieldNameRule.RESERVED.equals(Arrays.asList("id", "sourceSystem", "sourceId", "_loadId", "_receivedAt")));
	}

	/** An entity that also carries an attribute literally named Id_src: the suffix is appended again, never an exception, never a duplicate. */
	private static void siblingClashAppendsSuffixAgain_deterministic()
	{
		final java.util.List<String> siblings = Arrays.asList("Id", "id_SRC", "name");
		MasterFieldNameRuleTest.check("Id -> Id_src_src when a sibling already takes Id_src (case-insensitively)",
			"Id_src_src".equals(MasterFieldNameRule.masterFieldName("Id", siblings)));
		MasterFieldNameRuleTest.check("the sibling Id_src itself is not reserved and keeps its name",
			"id_SRC".equals(MasterFieldNameRule.masterFieldName("id_SRC", siblings)));
		MasterFieldNameRuleTest.check("same input, same answer", MasterFieldNameRule.masterFieldName("Id", siblings).equals(MasterFieldNameRule.masterFieldName("Id", siblings)));
	}

	private static void nullAndEmptySiblingsTolerated()
	{
		MasterFieldNameRuleTest.check("null attribute name -> null", MasterFieldNameRule.masterFieldName(null, null) == null);
		MasterFieldNameRuleTest.check("null is not reserved", !MasterFieldNameRule.isReserved(null));
		MasterFieldNameRuleTest.check("null is not renamed", !MasterFieldNameRule.isRenamed(null, null));
		MasterFieldNameRuleTest.check("a null sibling entry is skipped", "Id_src".equals(MasterFieldNameRule.masterFieldName("Id", Arrays.asList((String) null, "Id"))));
	}

	/** The convenience map used by the EBX-bound readers: sourceSystem/sourceId (primaryKey) are the master fields of that name - never suffixed. */
	private static void masterFieldNames_primaryKeyAttributesNeverRenamed()
	{
		final Map<String, String> names = MasterFieldNameRule.masterFieldNames(Arrays.asList(
			new StagingModel.AttributeInfo("sourceSystem", "Source system", "STRING", Integer.valueOf(40), true, true, null, "ACCEPTED"),
			new StagingModel.AttributeInfo("sourceId", "Source id", "STRING", Integer.valueOf(255), true, true, null, "ACCEPTED"),
			new StagingModel.AttributeInfo("Id", "Id", "STRING", Integer.valueOf(18), false, false, null, "ACCEPTED"),
			new StagingModel.AttributeInfo("Name", "Name", "STRING", Integer.valueOf(255), false, false, null, "ACCEPTED"),
			new StagingModel.AttributeInfo("_loadId", "Load", "STRING", Integer.valueOf(40), false, false, null, "PROPOSED")));
		MasterFieldNameRuleTest.check("sourceSystem unchanged", "sourceSystem".equals(names.get("sourceSystem")));
		MasterFieldNameRuleTest.check("sourceId unchanged", "sourceId".equals(names.get("sourceId")));
		MasterFieldNameRuleTest.check("Id -> Id_src", "Id_src".equals(names.get("Id")));
		MasterFieldNameRuleTest.check("Name unchanged", "Name".equals(names.get("Name")));
		MasterFieldNameRuleTest.check("every attribute mapped whatever its status (callers filter on acceptance themselves)", "_loadId_src".equals(names.get("_loadId")));
		MasterFieldNameRuleTest.check("null list -> empty map", MasterFieldNameRule.masterFieldNames(null).isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
