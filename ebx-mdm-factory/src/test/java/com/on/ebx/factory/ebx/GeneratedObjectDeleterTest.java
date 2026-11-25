package com.on.ebx.factory.ebx;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Pins the ONE sentence per tool that
 * reads as "already gone" - the Connectors server's own "no mapping <id>" (2026-09-28: project PersVol4's deletion stopped
 * on four flows whose mappings were already removed) and the data-model server's "Data model not found" - and that nothing
 * broader does: a wording like "not found", "does not exist" or "unknown" inside a refused or failed call used to be read
 * as absent, and the project's rows were then deleted while its generated objects still existed.
 */
public final class GeneratedObjectDeleterTest
{
	public static void main(final String[] pArgs)
	{
		GeneratedObjectDeleterTest.check("connectors_mapping_delete wording", GeneratedObjectDeleter.mappingAbsentAnswer("no mapping 292"));
		GeneratedObjectDeleterTest.check("the wording is read case-insensitively and trimmed", GeneratedObjectDeleter.mappingAbsentAnswer("  No mapping 292 "));
		GeneratedObjectDeleterTest.check("generic not found is NOT absent", !GeneratedObjectDeleter.mappingAbsentAnswer("Data model X not found"));
		GeneratedObjectDeleterTest.check("generic does not exist is NOT absent", !GeneratedObjectDeleter.mappingAbsentAnswer("Dataspace does not exist"));
		GeneratedObjectDeleterTest.check("generic unknown is NOT absent", !GeneratedObjectDeleter.mappingAbsentAnswer("Unknown table /root/T"));
		GeneratedObjectDeleterTest.check("null is not absent", !GeneratedObjectDeleter.mappingAbsentAnswer(null));
		GeneratedObjectDeleterTest.check("a real failure is not absent", !GeneratedObjectDeleter.mappingAbsentAnswer("Access denied to TOOL 'connectors_mapping_delete'"));
		GeneratedObjectDeleterTest.check("'mapping id is required' is not absent", !GeneratedObjectDeleter.mappingAbsentAnswer("mapping id is required"));
		GeneratedObjectDeleterTest.check("a failed transaction that merely mentions the sentence is not absent",
			!GeneratedObjectDeleter.mappingAbsentAnswer("Procedure failed: unknown error while deleting (no mapping 292 in cache)"));
		// the data-model server answered as an error RESULT (not a throw): same single marker as the thrown case
		GeneratedObjectDeleterTest.check("a data-model error result 'Data model not found' is absent",
			GeneratedObjectDeleter.thrownMeansAbsent("Data model not found: 'PersVol4_Master'. Call list_data_models to see available model names.",
				GeneratedObjectDeleter.DATA_MODEL_ABSENT_MARKER));
		GeneratedObjectDeleterTest.check("a data-model error result 'unknown'/'does not exist' is not absent",
			!GeneratedObjectDeleter.thrownMeansAbsent("Unknown operation delete_data_model; the dataspace does not exist", GeneratedObjectDeleter.DATA_MODEL_ABSENT_MARKER));
		// the mapping read tool (LandingFlowOwnership): "No mapping '<id>'" only
		GeneratedObjectDeleterTest.check("connectors read-tool wording", GeneratedObjectDeleter.thrownMeansAbsent("No mapping '292'", GeneratedObjectDeleter.MAPPING_ABSENT_MARKER + "'"));
		GeneratedObjectDeleterTest.check("a read-tool refusal is not absent",
			!GeneratedObjectDeleter.thrownMeansAbsent("Unknown tool connectors_mapping_get", GeneratedObjectDeleter.MAPPING_ABSENT_MARKER + "'"));
		// thrown errors (2026-09-29, PersVol4: the data-model server THROWS for a model already deleted)
		final String thrownModel = "tools/call: Tool invocation failed: Data model not found: 'PersVol4_Master'. Call list_data_models to see available model names.";
		GeneratedObjectDeleterTest.check("thrown 'Data model not found' is absent", GeneratedObjectDeleter.thrownMeansAbsent(thrownModel, "data model not found"));
		GeneratedObjectDeleterTest.check("thrown 'no mapping' is absent", GeneratedObjectDeleter.thrownMeansAbsent("tools/call: no mapping 292", "no mapping "));
		GeneratedObjectDeleterTest.check("a missing MCP server is never an absent model",
			!GeneratedObjectDeleter.thrownMeansAbsent("MCP server not found: 'data-model'", "data model not found"));
		GeneratedObjectDeleterTest.check("a refused tool is never an absent model",
			!GeneratedObjectDeleter.thrownMeansAbsent("Access denied to TOOL 'execute_operation' on server 'data-model'", "data model not found"));
		GeneratedObjectDeleterTest.check("an unknown tool is never an absent model", !GeneratedObjectDeleter.thrownMeansAbsent("Unknown tool: execute_operation", "data model not found"));
		GeneratedObjectDeleterTest.check("a null message is never absent", !GeneratedObjectDeleter.thrownMeansAbsent(null, "data model not found"));
		// lot W13 (2026-10-12): delete_matching_config on a business-object member (measured on W13BO, RC3) is KEPT, not a failure
		final String memberRefusal = "Table /root/Personne is part of a business object; use the business-object tools instead";
		GeneratedObjectDeleterTest.check("the measured refusal is recognised", GeneratedObjectDeleter.isBusinessObjectMemberRefusal(memberRefusal));
		GeneratedObjectDeleterTest.check("case-insensitively, inside a thrown text", GeneratedObjectDeleter.isBusinessObjectMemberRefusal("tools/call: INVALID_PARAMS Table /root/X IS PART OF A BUSINESS OBJECT; ..."));
		GeneratedObjectDeleterTest.check("an unrelated refusal that mentions business objects stays a failure", !GeneratedObjectDeleter.isBusinessObjectMemberRefusal("Business object PersonneBO not found"));
		GeneratedObjectDeleterTest.check("a generic refusal stays a failure", !GeneratedObjectDeleter.isBusinessObjectMemberRefusal("Access denied to TOOL 'delete_matching_config'"));
		GeneratedObjectDeleterTest.check("null is not a refusal", !GeneratedObjectDeleter.isBusinessObjectMemberRefusal(null));
		final GeneratedObjectDeleter.Outcome kept = GeneratedObjectDeleter.Outcome.keptBusinessObject("/root/Personne");
		GeneratedObjectDeleterTest.check("KEPT_BUSINESS_OBJECT, neither deleted nor failed (the plan goes on): " + kept.result,
			GeneratedObjectDeleter.RESULT_KEPT_BUSINESS_OBJECT.equals(kept.result) && !"FAILED".equals(kept.result));
		GeneratedObjectDeleterTest.check("with the coded reason", "engine.external.matchingConfigKeptBusinessObject".equals(kept.code) && kept.message.contains("/root/Personne")
			&& kept.message.contains("administration screen"));
		GeneratedObjectDeleterTest.check("the other outcomes carry no code", GeneratedObjectDeleter.Outcome.failed("x").code == null && GeneratedObjectDeleter.Outcome.absent().code == null);
		// lot S9 (2026-10-18 22:17): "The configuration was deleted but table ... no longer resolves" is DELETED, pinned to the message prefix
		final String deletedUnresolved = "The configuration was deleted but table /root/Personne no longer resolves after the schema reset.";
		GeneratedObjectDeleterTest.check("the measured TABLE_NOT_FOUND message that says the configuration was deleted", GeneratedObjectDeleter.isConfigurationDeletedButTableUnresolved(deletedUnresolved));
		GeneratedObjectDeleterTest.check("surrounding whitespace is tolerated", GeneratedObjectDeleter.isConfigurationDeletedButTableUnresolved("  " + deletedUnresolved));
		GeneratedObjectDeleterTest.check("a plain 'Table not found' stays absent", !GeneratedObjectDeleter.isConfigurationDeletedButTableUnresolved("Table not found: /root/Adresse"));
		GeneratedObjectDeleterTest.check("a text that merely contains the sentence is not it", !GeneratedObjectDeleter.isConfigurationDeletedButTableUnresolved("Error: The configuration was deleted but table X"));
		GeneratedObjectDeleterTest.check("a configuration that was never there stays absent", !GeneratedObjectDeleter.isConfigurationDeletedButTableUnresolved("No matching configuration for /root/Adresse"));
		GeneratedObjectDeleterTest.check("null is not it", !GeneratedObjectDeleter.isConfigurationDeletedButTableUnresolved(null));
		GeneratedObjectDeleterTest.distributionTriggers();
		// lot M18 (second round): the add-on's answer travels with the outcome
		final GeneratedObjectDeleter.Outcome answered = GeneratedObjectDeleter.Outcome.keptBusinessObject("/root/Personne").withAnswer(memberRefusal);
		GeneratedObjectDeleterTest.check("an outcome keeps the add-on's own answer next to its sentence", memberRefusal.equals(answered.answer) && answered.message.contains("/root/Personne")
			&& GeneratedObjectDeleter.RESULT_KEPT_BUSINESS_OBJECT.equals(answered.result) && "engine.external.matchingConfigKeptBusinessObject".equals(answered.code));
		GeneratedObjectDeleterTest.check("no answer unless one was given", GeneratedObjectDeleter.Outcome.deleted("x").answer == null && GeneratedObjectDeleter.Outcome.failed("x").answer == null);
		// the exact sentence of MameConfigWriter.rejectIfBusinessObjectTable (6.3.0.5-RC3, javap -v 2026-10-18) is the refusal that is recognised
		GeneratedObjectDeleterTest.check("the sentence of rejectIfBusinessObjectTable is recognised",
			GeneratedObjectDeleter.isBusinessObjectMemberRefusal("Table /root/Personne is part of a business object; use the business-object tools instead."));
		final String orphan = GeneratedObjectDeleter.orphanedConfigurationNote(2, true, "Vol100k_Master");
		GeneratedObjectDeleterTest.check("model or dataspace deleted and configurations kept: orphan note naming the dataset and the filter: " + orphan, orphan != null
			&& orphan.contains("Bebx-addon-mame/ebx-addon-mame-configuration") && orphan.contains("TableConfiguration") && orphan.contains("urn:ebx:publication:Vol100k_Master")
			&& orphan.contains("Nothing was written"));
		GeneratedObjectDeleterTest.check("nothing kept: no note", GeneratedObjectDeleter.orphanedConfigurationNote(0, true, "M") == null);
		GeneratedObjectDeleterTest.check("model and dataspace kept: no note (the rows still point at a live model)", GeneratedObjectDeleter.orphanedConfigurationNote(2, false, "M") == null);
		System.out.println("GeneratedObjectDeleterTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	/** One scripted data-model server for the removal of the distribution triggers: records the calls, answers per "table name" key. */
	private static final class FakeOps implements GeneratedObjectDeleter.TriggerOps
	{
		final java.util.List<String> unsets = new java.util.ArrayList<String>();
		final java.util.Map<String, String> refusals = new java.util.HashMap<String, String>();
		final java.util.Set<String> gone = new java.util.HashSet<String>();
		String thrown;
		int publishes;
		boolean publishFails;

		@Override
		public String unset(final String pTablePath, final String pTriggerName) throws com.on.ebx.agent.McpException
		{
			this.unsets.add(pTablePath + " " + pTriggerName);
			if (this.thrown != null)
			{
				throw new com.on.ebx.agent.McpException(this.thrown, null);
			}
			return this.refusals.get(pTablePath + " " + pTriggerName);
		}

		@Override
		public boolean scriptGone(final String pTablePath, final String pTriggerName)
		{
			return this.gone.contains(pTablePath + " " + pTriggerName);
		}

		@Override
		public void publish() throws com.on.ebx.agent.McpException
		{
			this.publishes++;
			if (this.publishFails)
			{
				throw new com.on.ebx.agent.McpException("publish refused", null);
			}
		}
	}

	/** Lot S9 (extension, 2026-10-18): the distribution triggers are unset by name (never the whole list), then ONE publication; absent is not a failure. */
	private static void distributionTriggers()
	{
		final java.util.List<String> names = DistributionTriggerInstaller.scriptNames();
		GeneratedObjectDeleterTest.check("the three names the installer declares: " + names,
			names.equals(java.util.Arrays.asList("DistributionAfterCreate", "DistributionAfterModify", "DistributionAfterDelete")));
		final com.fasterxml.jackson.databind.node.ObjectNode args = GeneratedObjectDeleter.unsetTriggerArgs("Tiers_Master", "/root/Adresse", "DistributionAfterModify");
		GeneratedObjectDeleterTest.check("the unset call names the model, the table, the property and the ITEM: " + args,
			"Tiers_Master".equals(args.path("data_model_name").asText()) && "/root/Adresse".equals(args.path("element_path").asText()) && "triggers".equals(args.path("property").asText())
				&& "DistributionAfterModify".equals(args.path("item").asText()) && args.size() == 4);
		final java.util.List<String> tables = java.util.Arrays.asList("/root/Personne", "/root/Adresse");

		final FakeOps all = new FakeOps();
		final GeneratedObjectDeleter.Outcome removed = GeneratedObjectDeleter.removeDistributionTriggers("M", tables, names, all);
		GeneratedObjectDeleterTest.check("DELETED: " + removed.result + " " + removed.message, "DELETED".equals(removed.result));
		GeneratedObjectDeleterTest.check("one unset per table and name, in order: " + all.unsets, all.unsets.size() == 6 && all.unsets.get(0).equals("/root/Personne DistributionAfterCreate")
			&& all.unsets.get(5).equals("/root/Adresse DistributionAfterDelete"));
		GeneratedObjectDeleterTest.check("ONE publication", all.publishes == 1);

		final FakeOps none = new FakeOps();
		for (final String table : tables)
		{
			for (final String name : names)
			{
				none.refusals.put(table + " " + name, "Trigger 'x' does not exist on the element");
			}
		}
		final GeneratedObjectDeleter.Outcome absent = GeneratedObjectDeleter.removeDistributionTriggers("M", tables, names, none);
		GeneratedObjectDeleterTest.check("every trigger already removed reads SKIPPED_ABSENT: " + absent.result, "SKIPPED_ABSENT".equals(absent.result) && none.publishes == 0);

		final FakeOps goneByScript = new FakeOps();
		for (final String table : tables)
		{
			for (final String name : names)
			{
				goneByScript.refusals.put(table + " " + name, "Operation failed");
				goneByScript.gone.add(table + " " + name);
			}
		}
		GeneratedObjectDeleterTest.check("an unrecognised refusal whose script the scripting server says is gone is absent too",
			"SKIPPED_ABSENT".equals(GeneratedObjectDeleter.removeDistributionTriggers("M", tables, names, goneByScript).result));

		final FakeOps mixed = new FakeOps();
		mixed.refusals.put("/root/Adresse DistributionAfterDelete", "no such item");
		final GeneratedObjectDeleter.Outcome partial = GeneratedObjectDeleter.removeDistributionTriggers("M", tables, names, mixed);
		GeneratedObjectDeleterTest.check("some removed, one absent: DELETED, published once: " + partial.message, "DELETED".equals(partial.result) && mixed.publishes == 1 && partial.message.contains("1 already absent"));

		final FakeOps refused = new FakeOps();
		refused.refusals.put("/root/Personne DistributionAfterModify", "Access denied to TOOL 'unset_element_property'");
		final GeneratedObjectDeleter.Outcome failed = GeneratedObjectDeleter.removeDistributionTriggers("M", tables, names, refused);
		GeneratedObjectDeleterTest.check("a real refusal is a FAILURE and nothing is published: " + failed.result, "FAILED".equals(failed.result) && refused.publishes == 0
			&& failed.message.contains("Access denied") && failed.message.contains("kept"));

		final FakeOps modelGone = new FakeOps();
		modelGone.thrown = "tools/call: Tool invocation failed: Data model not found: 'M'. Call list_data_models to see available model names.";
		final GeneratedObjectDeleter.Outcome gone = GeneratedObjectDeleter.removeDistributionTriggers("M", tables, names, modelGone);
		GeneratedObjectDeleterTest.check("a model already gone is absent, after the first call: " + gone.result, "SKIPPED_ABSENT".equals(gone.result) && modelGone.unsets.size() == 1);

		final FakeOps otherThrow = new FakeOps();
		otherThrow.thrown = "MCP server not found: 'data-model'";
		GeneratedObjectDeleterTest.check("a missing MCP server is a failure, never absent", "FAILED".equals(GeneratedObjectDeleter.removeDistributionTriggers("M", tables, names, otherThrow).result));

		final FakeOps publishFails = new FakeOps();
		publishFails.publishFails = true;
		GeneratedObjectDeleterTest.check("a publication that fails is a failure", "FAILED".equals(GeneratedObjectDeleter.removeDistributionTriggers("M", tables, names, publishFails).result));
	}
}
