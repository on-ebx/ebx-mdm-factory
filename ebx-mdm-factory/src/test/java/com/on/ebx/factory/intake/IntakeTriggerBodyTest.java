package com.on.ebx.factory.intake;

import java.util.Arrays;
import java.util.List;

/** JUnit-free: the generated trigger sources. Content only - the real EBX Script compiler is not run here (see {@link IntakeTriggerBody}'s own note on what was validated). */
public final class IntakeTriggerBodyTest
{
	public static void main(final String[] pArgs)
	{
		IntakeTriggerBodyTest.names();
		IntakeTriggerBodyTest.sourceColumnsExcludeTechnicalFields();
		IntakeTriggerBodyTest.wakeUpBody();
		IntakeTriggerBodyTest.sourceLiteralIsEscaped();
		IntakeTriggerBodyTest.beforeModifyEnumeratesTheColumns();
		IntakeTriggerBodyTest.tableWithoutSourceColumn();
		IntakeTriggerBodyTest.columnsNeedingQuoting();
		IntakeTriggerBodyTest.unknownType();
		System.out.println("IntakeTriggerBodyTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static int count(final String pText, final String pPart)
	{
		int n = 0;
		for (int i = pText.indexOf(pPart); i >= 0; i = pText.indexOf(pPart, i + pPart.length()))
		{
			n++;
		}
		return n;
	}

	private static int lines(final String pText, final String pTrimmed)
	{
		int n = 0;
		for (final String line : pText.split("\n"))
		{
			if (line.trim().equals(pTrimmed))
			{
				n++;
			}
		}
		return n;
	}

	private static void names()
	{
		check("IntakeAfterCreate".equals(IntakeTriggerBody.scriptName("afterCreate")), "name 1");
		check("IntakeAfterModify".equals(IntakeTriggerBody.scriptName("afterModify")), "name 2");
		check("IntakeBeforeModify".equals(IntakeTriggerBody.scriptName("beforeModify")), "name 3");
		check(IntakeTriggerBody.TYPES.length == 3, "three triggers");
	}

	private static void sourceColumnsExcludeTechnicalFields()
	{
		final List<String> all = Arrays.asList("_sourceId", "NAME", "EMAIL", "_sourceSystem", "_loadId", "_receivedAt", "_rowHash", "_status", "NAME", "", "bad\"name");
		final List<String> columns = IntakeTriggerBody.sourceColumns(all);
		check(columns.equals(Arrays.asList("NAME", "EMAIL")), "only source columns, once, quotable: " + columns);
		check(IntakeTriggerBody.sourceColumns(Arrays.asList("_sourceId", "_status")).isEmpty(), "none left");
		check(IntakeTriggerBody.sourceColumns(Arrays.asList("_custom")).equals(Arrays.asList("_custom")), "a source column that starts with an underscore is still a source column");
	}

	private static void wakeUpBody()
	{
		for (final String type : new String[] { "afterCreate", "afterModify" })
		{
			final String body = IntakeTriggerBody.body(type, "Salesforce", Arrays.asList("NAME"));
			check(body.contains("references model \"IntakeSignal\" from module \"ebx-mdm-factory\" as signal;"), "references the packaged model");
			check(body.contains("export procedure " + ("afterCreate".equals(type) ? "onAfterCreate" : "onAfterModify") + "()"), "entry point of " + type);
			check(body.contains("isNull(status) or status = 'NEW'"), "waiting = NEW or empty");
			check(body.contains("db.findDataset(_ebx.dataspace, 'MdmFactoryIntakeSignal')"), "dataset of the SAME dataspace");
			check(body.contains("signalPk.sourceSystem := 'Salesforce';"), "primary key lookup with the baked literal");
			check(body.contains("db.lookupRecord(signalTable, signalPk)"), "lookup by primary key");
			check(body.contains("db.recordOf(_ebx.transaction, signalTable)"), "creates through the ambient transaction");
			check(body.contains("dt.addSeconds(existing.lastRowAt, 5) > moment then return;"), "5 second throttle");
			check(body.contains("db.lookupRecordForUpdate(_ebx.transaction, signalTable, signalPk)"), "updates through the ambient transaction");
			check(!body.contains("execute transaction") && !body.contains("sql `") && !body.contains("try") && !body.contains("catch"), "no transaction block, no SQL, no try/catch");
			check(!body.contains("[0i]"), "no literal list index");
			check(IntakeTriggerBodyTest.lines(body, "begin") == IntakeTriggerBodyTest.lines(body, "end"), "begin/end balanced");
		}
	}

	private static void sourceLiteralIsEscaped()
	{
		final String body = IntakeTriggerBody.body("afterCreate", "O'Brien\\x", Arrays.asList("A"));
		check(body.contains("signalPk.sourceSystem := 'O\\'Brien\\\\x';"), "backslash-style escaping, never doubled quotes");
		check(body.contains("created.sourceSystem := 'O\\'Brien\\\\x';"), "same literal for the create branch");
		check(IntakeTriggerBody.escape("a\nb").equals("a\\nb"), "newline escaped");
		check(IntakeTriggerBody.escape(null).isEmpty(), "null");
	}

	private static void beforeModifyEnumeratesTheColumns()
	{
		final String body = IntakeTriggerBody.body("beforeModify", "CRM", Arrays.asList("NAME", "EMAIL", "PHONE"));
		check(body.contains("function differs(a: string, b: string): boolean"), "typed null-safe helper");
		check(body.contains("if isNull(a) and isNull(b) then return false;") && body.contains("if isNull(a) or isNull(b) then return true;"), "null-safe");
		check(body.contains("export procedure onBeforeModify()"), "entry point");
		check(body.contains("if differs(_ebx.record.\"_status\", _ebx.nextRecord.\"_status\") then return;"), "a writer that sets _status itself is left alone");
		check(body.contains("if isNull(_ebx.nextRecord.\"_status\") or _ebx.nextRecord.\"_status\" = 'NEW' then return;"), "already NEW or empty: nothing");
		for (final String column : new String[] { "NAME", "EMAIL", "PHONE" })
		{
			check(body.contains("if differs(_ebx.record.\"" + column + "\", _ebx.nextRecord.\"" + column + "\") then"), "column " + column + " watched");
		}
		check(count(body, "_ebx.nextRecord.\"_status\" := 'NEW';") == 3, "one requeue per column");
		check(!body.contains("\"_loadId\"") && !body.contains("\"_rowHash\"") && !body.contains("\"_receivedAt\"") && !body.contains("\"_sourceId\""), "no technical field watched");
		check(!body.contains("db.") && !body.contains("references model"), "no database access at all");
	}

	private static void tableWithoutSourceColumn()
	{
		final String body = IntakeTriggerBody.body("beforeModify", "CRM", Arrays.<String> asList());
		check(body.contains("export procedure onBeforeModify()") && body.contains("return;") && !body.contains("differs(_ebx.record"), "a table with zero source columns gets a no-op");
		check(IntakeTriggerBody.body("beforeModify", "CRM", null).contains("export procedure onBeforeModify()"), "null column list too");
	}

	private static void columnsNeedingQuoting()
	{
		// keywords and digits-first names: always written as quoted identifiers
		final String body = IntakeTriggerBody.body("beforeModify", "CRM", Arrays.asList("from", "d", "t", "dt", "order", "c_1abc"));
		for (final String column : new String[] { "from", "d", "t", "dt", "order", "c_1abc" })
		{
			check(body.contains("_ebx.record.\"" + column + "\""), "quoted " + column);
		}
		check(!body.contains("_ebx.record.from") && !body.contains("_ebx.record.d,") && !body.contains("_ebx.record.order"), "never a bare identifier");
	}

	private static void unknownType()
	{
		boolean thrown = false;
		try
		{
			IntakeTriggerBody.body("afterDelete", "CRM", null);
		}
		catch (final IllegalArgumentException ex)
		{
			thrown = true;
		}
		check(thrown, "unknown type refused");
	}
}
