package com.on.ebx.factory.master;

import com.on.ebx.factory.core.FactoryPaths;

/** JUnit-free: run the main, a failure throws. {@link MasterJournalTriggerBody} (lot M-INC, 2026-10-12): the generated staging triggers. */
public final class MasterJournalTriggerBodyTest
{
	public static void main(final String[] pArgs)
	{
		check("names", "MasterJournalAfterCreate".equals(MasterJournalTriggerBody.scriptName("afterCreate")) && "MasterJournalAfterModify".equals(MasterJournalTriggerBody.scriptName("afterModify")));
		check("both names, create first", MasterJournalTriggerBody.scriptNames().length == 2 && "MasterJournalAfterCreate".equals(MasterJournalTriggerBody.scriptNames()[0]));
		check("types are the case-sensitive vocabulary of set_script_trigger", MasterJournalTriggerBody.TYPES.length == 2 && "afterCreate".equals(MasterJournalTriggerBody.TYPES[0]) && "afterModify".equals(MasterJournalTriggerBody.TYPES[1]));

		final String create = MasterJournalTriggerBody.body("afterCreate", "Personnes", "Personne");
		final String modify = MasterJournalTriggerBody.body("afterModify", "Personnes", "Personne");
		check("create entry point", create.contains("export procedure onAfterCreate()") && !create.contains("onAfterModify"));
		check("modify entry point", modify.contains("export procedure onAfterModify()") && !modify.contains("onAfterCreate"));
		check("the two bodies differ only by their entry point", create.replace("onAfterCreate", "X").equals(modify.replace("onAfterModify", "X")));
		for (final String body : new String[] { create, modify })
		{
			check("references the packaged journal model of this module", body.contains("references model \"MasterJournal\" from module \"" + FactoryPaths.MODULE_NAME + "\" as journal;"));
			check("reaches the journal dataset of its own dataspace", body.contains("db.findDataset(_ebx.dataspace, '" + FactoryPaths.MASTER_JOURNAL_DATASET + "')"));
			check("returns at once without a dataset", body.contains("if isNull(journalDataset) then return;"));
			check("looks the Control row up by primary key and returns without one",
				body.contains("db.findTable<journal.Control>(journalDataset)") && body.contains("cp.primaryKeyOf<journal.Control>()") && body.contains("if isNull(control) then return;"));
			check("the Control lookup comes before any Change access", body.indexOf("if isNull(control) then return;") < body.indexOf("journal.Change"));
			check("bakes in the project and the entity", body.contains("controlPk.project := 'Personnes';") && body.contains("controlPk.entity := 'Personne';"));
			check("the Change key is the staging record's two key fields, quoted", body.contains("changePk.sourceSystem := _ebx.record.\"sourceSystem\";") && body.contains("changePk.sourceId := _ebx.record.\"sourceId\";"));
			check("no hand-built composite key", !body.contains("|\"") && !body.contains("'|'") && !body.contains("formatPrimaryKey"));
			check("creates through the ambient transaction", body.contains("db.recordOf(_ebx.transaction, changeTable)") && body.contains("created.changes := 1i;"));
			check("updates through the ambient transaction and counts", body.contains("db.lookupRecordForUpdate(_ebx.transaction, changeTable, changePk)")
				&& body.contains("nextCount := touched.changes + 1i;") && body.contains("touched.changes := nextCount;"));
			check("stamps the time", body.contains("const moment := dt.now();") && body.contains("created.changedAt := moment;") && body.contains("touched.changedAt := moment;"));
			check("saves both", body.indexOf("db.saveRecord(created);") > 0 && body.indexOf("db.saveRecord(touched);") > body.indexOf("db.saveRecord(created);"));
			check("no try/catch, no literal list index", !body.contains("try") && !body.contains("[0i]"));
			check("no execute transaction block (a table trigger cannot open one)", !body.contains("execute transaction"));
		}

		// quotes and backslashes in a project or entity name are escaped backslash-style
		final String odd = MasterJournalTriggerBody.body("afterCreate", "Pro'jet", "En\\tité");
		check("a quote is escaped", odd.contains("controlPk.project := 'Pro\\'jet';"));
		check("a backslash is escaped", odd.contains("controlPk.entity := 'En\\\\tité';"));
		check("escape of newline and tab", "a\\nb\\tc".equals(MasterJournalTriggerBody.escape("a\nb\tc")) && "".equals(MasterJournalTriggerBody.escape(null)));

		boolean refused = false;
		try
		{
			MasterJournalTriggerBody.body("beforeModify", "P", "E");
		}
		catch (final IllegalArgumentException ex)
		{
			refused = true;
		}
		check("an unknown type is refused (the journal never installs a before-trigger)", refused);
		System.out.println("MasterJournalTriggerBodyTest OK");
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
