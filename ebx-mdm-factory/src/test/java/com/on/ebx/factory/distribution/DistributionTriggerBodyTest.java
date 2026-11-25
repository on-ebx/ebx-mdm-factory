package com.on.ebx.factory.distribution;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. Checks content only - not run through the real EBX Script compiler (see DistributionTriggerBody's own "not verified" note). */
public final class DistributionTriggerBodyTest
{
	public static void main(final String[] pArgs)
	{
		DistributionTriggerBodyTest.afterCreateBodyHasTheRightEntryPointAndChange();
		DistributionTriggerBodyTest.afterModifyBodyHasTheRightEntryPointAndChange();
		DistributionTriggerBodyTest.afterDeleteBodyHasTheRightEntryPointAndChange();
		DistributionTriggerBodyTest.everyBodyOnlyQueriesArmedRealtimeTargets();
		DistributionTriggerBodyTest.everyBodyWritesThroughItsOwnAmbientTransaction();
		DistributionTriggerBodyTest.everyBodyComparesTheForeignKeyThroughItsTargetKey();
		DistributionTriggerBodyTest.projectIdAndTablePathAreEmbedded();
		DistributionTriggerBodyTest.escapesQuotesAndBackslashes();
		DistributionTriggerBodyTest.singlePrimaryKeyFieldUsesFormatPrimaryKey();
		DistributionTriggerBodyTest.compositePrimaryKeySetsEveryField();
		DistributionTriggerBodyTest.missingPrimaryKeyFallsBackToId();
		DistributionTriggerBodyTest.noBodyReferencesAMatchAndMergeField();
		DistributionTriggerBodyTest.afterModifyFixture();
		DistributionTriggerBodyTest.controlRowShortCircuitReadsByPrimaryKeyBeforeTheSqlFallback();
		DistributionTriggerBodyTest.controlRowShortCircuitReturnsOnMissingRowOrZeroCount();
		DistributionTriggerBodyTest.controlRowShortCircuitReusesTheSameOutboxDatasetLookup();
		DistributionTriggerBodyTest.noTypedSqlLiteralInAnyBody();
		DistributionTriggerBodyTest.targetLookupIsADynamicQueryReadWithDbField();
		System.out.println("DistributionTriggerBodyTest OK");
	}

	private static String body(final String pType)
	{
		return DistributionTriggerBody.body(pType, "42", "/root/Company", "Company", "Companies_Master", Arrays.asList("id"));
	}

	private static void afterCreateBodyHasTheRightEntryPointAndChange()
	{
		final String body = DistributionTriggerBodyTest.body(DistributionTriggerBody.AFTER_CREATE);
		DistributionTriggerBodyTest.check("declares onAfterCreate", body.contains("export procedure onAfterCreate()"));
		DistributionTriggerBodyTest.check("change is CREATE", body.contains("const changeValue := 'CREATE';"));
	}

	private static void afterModifyBodyHasTheRightEntryPointAndChange()
	{
		final String body = DistributionTriggerBodyTest.body(DistributionTriggerBody.AFTER_MODIFY);
		DistributionTriggerBodyTest.check("declares onAfterModify", body.contains("export procedure onAfterModify()"));
		DistributionTriggerBodyTest.check("change is MODIFY", body.contains("const changeValue := 'MODIFY';"));
		DistributionTriggerBodyTest.check("no Match and Merge field", !body.contains("Linked_Field_State"));
	}

	private static void afterDeleteBodyHasTheRightEntryPointAndChange()
	{
		final String body = DistributionTriggerBodyTest.body(DistributionTriggerBody.AFTER_DELETE);
		DistributionTriggerBodyTest.check("declares onAfterDelete", body.contains("export procedure onAfterDelete()"));
		DistributionTriggerBodyTest.check("change is DELETE", body.contains("const changeValue := 'DELETE';"));
	}

	private static void everyBodyOnlyQueriesArmedRealtimeTargets()
	{
		final String body = DistributionTriggerBodyTest.body(DistributionTriggerBody.AFTER_CREATE);
		DistributionTriggerBodyTest.check("binds mode=REALTIME", body.contains("db.setParameter(targetQuery, 1i, 'REALTIME');"));
		DistributionTriggerBodyTest.check("binds state=ARMED", body.contains("db.setParameter(targetQuery, 2i, 'ARMED');"));
	}

	/**
	 * Round 3 (PipelineStep 395, live): a table trigger cannot open {@code execute transaction} at all
	 * ("This type of script cannot create transactions") - the write goes through the trigger's own ambient
	 * {@code _ebx.transaction}, into an outbox dataset reached by name off {@code _ebx.dataspace} (never
	 * MdmFactory - a cross-dataspace write is impossible from a table trigger). See DistributionTriggerBody's
	 * own Javadoc for the live probe this was checked against.
	 */
	private static void everyBodyWritesThroughItsOwnAmbientTransaction()
	{
		final String body = DistributionTriggerBodyTest.body(DistributionTriggerBody.AFTER_CREATE);
		DistributionTriggerBodyTest.check("never opens its own transaction", !body.contains("execute transaction"));
		DistributionTriggerBodyTest.check("reaches the outbox dataset by name off _ebx.dataspace",
			body.contains("db.findDataset(_ebx.dataspace, 'DistributionOutbox')"));
		DistributionTriggerBodyTest.check("writes through the trigger's own ambient transaction", body.contains("db.recordOf(_ebx.transaction, outboxTable)"));
		DistributionTriggerBodyTest.check("references the packaged outbox model", body.contains("references model \"DistributionOutbox\" from module \"ebx-mdm-factory\" as outbox;"));
		DistributionTriggerBodyTest.check("never calls core.http (no outbound call from the trigger itself)", !body.contains("core.http"));
	}

	/** Round 3 (PipelineStep 395, live): {@code DistributionTarget.project} is a foreign key - comparing it directly against a bound string answered "Cannot apply '=' to arguments of type FKTYPE(...) = FKTYPE(...)"; fixed to compare through the target's own primary key. */
	private static void everyBodyComparesTheForeignKeyThroughItsTargetKey()
	{
		final String body = DistributionTriggerBodyTest.body(DistributionTriggerBody.AFTER_CREATE);
		DistributionTriggerBodyTest.check("compares through Project's own primary key", body.contains("t.project.code = ?"));
		DistributionTriggerBodyTest.check("never compares the raw foreign-key column", !body.contains("t.project = ?"));
	}

	private static void projectIdAndTablePathAreEmbedded()
	{
		final String body = DistributionTriggerBodyTest.body(DistributionTriggerBody.AFTER_CREATE);
		DistributionTriggerBodyTest.check("project id embedded", body.contains("'42'"));
		DistributionTriggerBodyTest.check("table path embedded", body.contains("'/root/Company'"));
	}

	private static void escapesQuotesAndBackslashes()
	{
		DistributionTriggerBodyTest.check("escapes a quote", "it\\'s".equals(DistributionTriggerBody.escape("it's")));
		DistributionTriggerBodyTest.check("escapes a backslash", "a\\\\b".equals(DistributionTriggerBody.escape("a\\b")));
	}

	private static void singlePrimaryKeyFieldUsesFormatPrimaryKey()
	{
		final String body = DistributionTriggerBody.body(DistributionTriggerBody.AFTER_CREATE, "42", "/root/Company", "Company", "Companies_Master", Arrays.asList("id"));
		DistributionTriggerBodyTest.check("builds a typed primary key", body.contains("cp.primaryKeyOf<master.Company>()"));
		DistributionTriggerBodyTest.check("sets the id field", body.contains("pk.id := _ebx.record.id;"));
		DistributionTriggerBodyTest.check("formats it with EBX's own function, never a hand-built pipe join", body.contains("cp.formatPrimaryKey(pk)"));
		DistributionTriggerBodyTest.check("references the master model", body.contains("references model \"Companies_Master\" as master;"));
	}

	private static void compositePrimaryKeySetsEveryField()
	{
		final String body = DistributionTriggerBody.body(DistributionTriggerBody.AFTER_CREATE, "42", "/root/Contact", "Contact", "Companies_Master",
			Arrays.asList("sourceSystem", "sourceId"));
		DistributionTriggerBodyTest.check("sets the first key field", body.contains("pk.sourceSystem := _ebx.record.sourceSystem;"));
		DistributionTriggerBodyTest.check("sets the second key field", body.contains("pk.sourceId := _ebx.record.sourceId;"));
	}

	private static void missingPrimaryKeyFallsBackToId()
	{
		final String bodyNull = DistributionTriggerBody.body(DistributionTriggerBody.AFTER_CREATE, "42", "/root/Company", "Company", "Companies_Master", null);
		DistributionTriggerBodyTest.check("null primary key list falls back to id", bodyNull.contains("pk.id := _ebx.record.id;"));
		final String bodyEmpty = DistributionTriggerBody.body(DistributionTriggerBody.AFTER_CREATE, "42", "/root/Company", "Company", "Companies_Master",
			Collections.<String>emptyList());
		DistributionTriggerBodyTest.check("empty primary key list falls back to id", bodyEmpty.contains("pk.id := _ebx.record.id;"));
	}

	/**
	 * Lot F3 (2026-10-19, measured 2026-10-18 22:17): a body that names Linked_Field_* or GoldenId stops the WHOLE master model compiling the moment the table's
	 * matching configuration is deleted. No generated body, of any type, on any table, may carry either word - nor write a RETIRE or a survivor.
	 */
	private static void noBodyReferencesAMatchAndMergeField()
	{
		for (final String type : new String[] { DistributionTriggerBody.AFTER_CREATE, DistributionTriggerBody.AFTER_MODIFY, DistributionTriggerBody.AFTER_DELETE })
		{
			for (final List<String> keys : Arrays.asList(Arrays.asList("id"), Arrays.asList("sourceSystem", "sourceId"), Collections.<String>emptyList()))
			{
				final String body = DistributionTriggerBody.body(type, "42", "/root/Company", "Company", "Companies_Master", keys);
				DistributionTriggerBodyTest.check(type + "/" + keys + ": no Linked_Field", !body.contains("Linked_Field"));
				DistributionTriggerBodyTest.check(type + "/" + keys + ": no GoldenId", !body.contains("GoldenId"));
				DistributionTriggerBodyTest.check(type + "/" + keys + ": no previousRecord comparison", !body.contains("previousRecord"));
				DistributionTriggerBodyTest.check(type + "/" + keys + ": never writes a RETIRE", !body.contains("RETIRE"));
				DistributionTriggerBodyTest.check(type + "/" + keys + ": survivor always empty", body.contains("const survivorValue := '';"));
			}
		}
	}

	/** The afterModify body as a fixture: printed when the test runs with {@code -Dprint.fixture=true}, and pinned on its exact opening and decision lines. */
	private static void afterModifyFixture()
	{
		final String body = DistributionTriggerBodyTest.body(DistributionTriggerBody.AFTER_MODIFY);
		if (Boolean.getBoolean("print.fixture"))
		{
			System.out.println(body);
		}
		DistributionTriggerBodyTest.check("afterModify enqueues MODIFY, nothing else", body.contains("const changeValue := 'MODIFY';\n\tconst survivorValue := '';\n"));
		DistributionTriggerBodyTest.check("afterModify entry point", body.contains("export procedure onAfterModify()\nbegin\n"));
	}

	/** Volume-operations lot 8b (task 1): the short-circuit reads the Control row (primary-key lookup, no SQL) BEFORE the SQL fallback's own `factoryDs`/`targetQuery` block is even reached. */
	private static void controlRowShortCircuitReadsByPrimaryKeyBeforeTheSqlFallback()
	{
		final String body = DistributionTriggerBodyTest.body(DistributionTriggerBody.AFTER_CREATE);
		DistributionTriggerBodyTest.check("looks up the Control table", body.contains("db.findTable<outbox.Control>(outboxDataset)"));
		DistributionTriggerBodyTest.check("builds a typed Control primary key", body.contains("cp.primaryKeyOf<outbox.Control>()"));
		DistributionTriggerBodyTest.check("keys it on the project id", body.contains("controlPk.project := '42';"));
		DistributionTriggerBodyTest.check("reads it with a plain lookup, never SQL for this part", body.contains("db.lookupRecord(controlTable, controlPk)"));
		final int controlLookupIndex = body.indexOf("db.lookupRecord(controlTable, controlPk)");
		final int sqlFallbackIndex = body.indexOf("db.findDataspace('MdmFactory')");
		DistributionTriggerBodyTest.check("control-row read happens strictly before the SQL fallback's own factoryDs lookup",
			controlLookupIndex >= 0 && sqlFallbackIndex >= 0 && controlLookupIndex < sqlFallbackIndex);
	}

	private static void controlRowShortCircuitReturnsOnMissingRowOrZeroCount()
	{
		final String body = DistributionTriggerBodyTest.body(DistributionTriggerBody.AFTER_CREATE);
		DistributionTriggerBodyTest.check("returns at once when the row is missing", body.contains("if isNull(controlRow) then return;"));
		DistributionTriggerBodyTest.check("returns at once when the count is zero", body.contains("if controlRow.armedRealtimeTargets = 0i then return;"));
	}

	/** The Control lookup and the Event write share the SAME `outboxDataset` lookup - only one `db.findDataset(_ebx.dataspace, 'DistributionOutbox')` call in the whole body. */
	private static void controlRowShortCircuitReusesTheSameOutboxDatasetLookup()
	{
		final String body = DistributionTriggerBodyTest.body(DistributionTriggerBody.AFTER_CREATE);
		final String needle = "db.findDataset(_ebx.dataspace, 'DistributionOutbox')";
		final int firstIndex = body.indexOf(needle);
		final int lastIndex = body.lastIndexOf(needle);
		DistributionTriggerBodyTest.check("db.findDataset(_ebx.dataspace, 'DistributionOutbox') is called exactly once", firstIndex >= 0 && firstIndex == lastIndex);
	}

	/** Lot S3 (2026-10-18, measured live): EVERY typed sql literal crashes the compiler of a data-model trigger script on 6.3.0-rc3, whatever the model - no body, of any type, with or without retire detection, may carry one. */
	private static void noTypedSqlLiteralInAnyBody()
	{
		for (final String type : new String[] { DistributionTriggerBody.AFTER_CREATE, DistributionTriggerBody.AFTER_MODIFY, DistributionTriggerBody.AFTER_DELETE })
		{
			for (final boolean mame : new boolean[] { false })
			{
				final String body = DistributionTriggerBody.body(type, "42", "/root/Company", "Company", "Companies_Master", Arrays.asList("id"));
				DistributionTriggerBodyTest.check(type + "/" + mame + ": no back quote at all (a typed sql literal is delimited by back quotes)", body.indexOf('`') < 0);
				DistributionTriggerBodyTest.check(type + "/" + mame + ": no 'sql ' literal opener", !body.matches("(?s).*\\bsql\\s*`.*"));
			}
		}
	}

	/** The createQuery / addDataset / setParameter x3 / executeQuery sequence, in that order, and every column read through db.field (untyped tuples). */
	private static void targetLookupIsADynamicQueryReadWithDbField()
	{
		final String body = DistributionTriggerBodyTest.body(DistributionTriggerBody.AFTER_CREATE);
		final int create = body.indexOf("var targetQuery := db.createQuery('select t.id as targetId from factory.\"DistributionTarget\" t where t.project.code = ? and t.mode = ? and t.state = ?');");
		final int dataset = body.indexOf("db.addDataset(targetQuery, 'factory', factoryDataset);");
		final int p0 = body.indexOf("db.setParameter(targetQuery, 0i, '42');");
		final int p1 = body.indexOf("db.setParameter(targetQuery, 1i, 'REALTIME');");
		final int p2 = body.indexOf("db.setParameter(targetQuery, 2i, 'ARMED');");
		final int exec = body.indexOf("db.executeQuery(targetQuery)");
		DistributionTriggerBodyTest.check("createQuery present", create >= 0);
		DistributionTriggerBodyTest.check("sequence createQuery < addDataset < setParameter 0,1,2 < executeQuery",
			create < dataset && dataset < p0 && p0 < p1 && p1 < p2 && p2 < exec);
		DistributionTriggerBodyTest.check("the column is read with db.field (int key), never tuple.targetId", body.contains("db.field<int>(tuple, 'targetId')") && !body.contains("tuple.targetId"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
