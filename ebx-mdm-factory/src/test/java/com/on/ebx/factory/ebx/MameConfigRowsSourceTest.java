package com.on.ebx.factory.ebx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * JUnit-free. Lot M18 (2026-10-18): the writes into the Match and Merge add-on's own dataset need an EBX session, so their discipline is pinned by reading the source (like
 * {@link ProjectDeleterKindsTest}): only the two writes the documented procedure allows - FKBusinessObject set to null on a table configuration, a business-object row
 * deleted - never a table configuration deleted (its hidden merge policy would point to nothing and the next boot fails in {@code MergeRelationService}, measured
 * 2026-10-18); the unlink comes before the delete; and the sweep asks for {@code confirm} before its first write.
 */
public final class MameConfigRowsSourceTest
{
	private MameConfigRowsSourceTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String root = System.getProperty("mdmf.sourceDir", "src/main/java") + "/com/on/ebx/factory/ebx/";
		final String rows = new String(Files.readAllBytes(Paths.get(root + "MameConfigRows.java")), StandardCharsets.UTF_8);
		MameConfigRowsSourceTest.check("exactly one doDelete in the add-on writer", rows.indexOf("doDelete(") >= 0 && rows.indexOf("doDelete(", rows.indexOf("doDelete(") + 1) < 0);
		final int delete = rows.indexOf("doDelete(");
		final int boLookup = rows.lastIndexOf("boTable.lookupAdaptationByPrimaryKey(boKey)", delete);
		MameConfigRowsSourceTest.check("the one doDelete deletes the row looked up in the business-object table", boLookup > 0 && delete - boLookup < 200);
		MameConfigRowsSourceTest.check("no table-configuration row is ever deleted", !rows.contains("tcTable.lookupAdaptationByPrimaryKey") && rows.indexOf("doDelete(") == delete);
		MameConfigRowsSourceTest.check("the unlink sets FKBusinessObject to null", rows.contains("value.setValue(null, MameConfigRows.FK_BUSINESS_OBJECT)"));
		MameConfigRowsSourceTest.check("the unlink comes before the delete and is read back", rows.indexOf("FK_BUSINESS_OBJECT)") < delete && rows.indexOf("countEq(tcTable") < delete);
		MameConfigRowsSourceTest.check("the row is deleted only if nothing is linked any more", rows.indexOf("table configurations are still linked") < delete);

		final String sweep = new String(Files.readAllBytes(Paths.get(root + "OrphanMatchingConfigCleanup.java")), StandardCharsets.UTF_8);
		final int confirm = sweep.indexOf("MatchingConfigRows.confirmRefusal(pConfirm");
		final int firstWrite = sweep.indexOf("OrphanMatchingConfigCleanup.cleanOne(pSession");
		MameConfigRowsSourceTest.check("the confirm guard is checked before the first write", confirm > 0 && firstWrite > confirm);
		MameConfigRowsSourceTest.check("the sweep never deletes a table configuration itself", !sweep.contains("doDelete(") && !sweep.contains("forEachBatched"));
		MameConfigRowsSourceTest.check("a table configuration goes through delete_matching_config, only when the model still exists",
			sweep.contains("GeneratedObjectDeleter.deleteMatchingConfig(") && sweep.indexOf("UNLINK_DELETE_AND_REMOVE_TABLE_CONFIGS") < sweep.indexOf("GeneratedObjectDeleter.deleteMatchingConfig("));

		// second round (live 2026-10-18 15:56): the MAIN table is refused while the published MODEL still declares the object (rejectIfBusinessObjectTable, javap) - the deletion
		// removes the declaration and republishes, once, and only after that very refusal
		final String deleter = new String(Files.readAllBytes(Paths.get(root + "GeneratedObjectDeleter.java")), StandardCharsets.UTF_8);
		final int flow = deleter.indexOf("static Outcome deleteMatchingConfigUndeclaringBusinessObject(");
		final int flowEnd = deleter.indexOf("static Outcome undeclareBusinessObject(", flow);
		final String body = deleter.substring(flow, flowEnd);
		final int first = body.indexOf("GeneratedObjectDeleter.deleteMatchingConfig(");
		final int guard = body.indexOf("RESULT_KEPT_BUSINESS_OBJECT.equals(first.result)");
		final int undeclare = body.indexOf("GeneratedObjectDeleter.undeclareBusinessObject(");
		final int second = body.indexOf("GeneratedObjectDeleter.deleteMatchingConfig(", undeclare);
		MameConfigRowsSourceTest.check("delete, then (only after the business-object refusal) undeclare, then delete again", first >= 0 && guard > first && undeclare > guard && second > undeclare
			&& body.indexOf("GeneratedObjectDeleter.deleteMatchingConfig(", second + 1) < 0);
		final int undeclareBody = deleter.indexOf("static Outcome undeclareBusinessObject(");
		final String undeclareText = deleter.substring(undeclareBody, deleter.indexOf("Lot M18 (2026-10-18): the configuration of a Match and Merge business object", undeclareBody) < 0 ? deleter.length()
			: deleter.indexOf("Lot M18 (2026-10-18): the configuration of a Match and Merge business object", undeclareBody));
		MameConfigRowsSourceTest.check("the model's object is removed with delete_business_object, then the model is republished",
			undeclareText.indexOf("get_data_model_business_objects") >= 0 && undeclareText.indexOf("delete_business_object") > undeclareText.indexOf("get_data_model_business_objects")
			&& undeclareText.indexOf("publish_data_model") > undeclareText.indexOf("delete_business_object"));
		final String projectDeleter = new String(Files.readAllBytes(Paths.get(root + "ProjectDeleter.java")), StandardCharsets.UTF_8);
		MameConfigRowsSourceTest.check("the project deletion's MATCHING_CONFIG case uses it", projectDeleter.indexOf("case MATCHING_CONFIG:") >= 0
			&& projectDeleter.indexOf("deleteMatchingConfigUndeclaringBusinessObject(", projectDeleter.indexOf("case MATCHING_CONFIG:")) > 0);
		final String stepResetter = new String(Files.readAllBytes(Paths.get(root + "StepResetter.java")), StandardCharsets.UTF_8);
		MameConfigRowsSourceTest.check("a step reset keeps the model: it never undeclares a business object", !stepResetter.contains("UndeclaringBusinessObject") && !stepResetter.contains("undeclareBusinessObject"));
		MameConfigRowsSourceTest.check("every matching item's outcome is logged and quoted in the answer", projectDeleter.contains("add-on answer: ") && projectDeleter.contains("g.put(\"answer\""));
		System.out.println("MameConfigRowsSourceTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
