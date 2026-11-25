package com.on.ebx.factory.master;

import com.on.ebx.factory.core.FactoryPaths;

/**
 * Lot M-INC (2026-10-12, docs/VOLUME.md "Lot M-INC"): the EBX-Script source of the two generated triggers of one STAGING table that journal every created or
 * modified record - {@code MasterJournalAfterCreate} (type {@code afterCreate}) and {@code MasterJournalAfterModify} (type {@code afterModify}). Authored by
 * {@code ebx.MasterJournal} through {@code update_script}. Pure string templating, no EBX class, unit-tested ({@code MasterJournalTriggerBodyTest}).
 * <p>
 * What a body does, in order, with the proven pieces of the sibling generated triggers ({@code IntakeTriggerBody}, {@code DistributionTriggerBody}):
 * <ol>
 * <li>reaches the journal dataset of its OWN dataspace, {@code db.findDataset(_ebx.dataspace, 'MdmFactoryMasterJournal')} (a table trigger writes through its ambient
 * {@code _ebx.transaction}, scoped to that dataspace) and returns at once when it is not there;</li>
 * <li>looks up the {@code Control} row of the entity by primary key and returns at once when there is none - an entity that no full master load has started to
 * record costs one lookup per written row, nothing else (the next master load of such an entity is full anyway, so nothing is lost);</li>
 * <li>looks up the {@code Change} row of the staging record by primary key: creates it ({@code changes = 1}) when missing, otherwise writes {@code changedAt} and
 * {@code changes + 1} - one row per staging record, however often it is written.</li>
 * </ol>
 * The staging key is NOT built here: the row carries {@code sourceSystem} and {@code sourceId} (the staging table's two primary-key fields) and the Java side computes
 * the key with {@code AdaptationTable.computePrimaryKey} (CLAUDE.md sec 9: never a hand-built composite key). The project code, the entity name and the staging
 * field names are baked in as escaped literals.
 * <p>
 * Rules of CLAUDE.md sec 2 followed: no literal list index, no try/catch, string literals escaped backslash-style, the two staging fields written as quoted
 * identifiers. <b>Not validated live</b> (nothing in this lot runs against an instance): the shape is {@code IntakeTriggerBody#wakeUp}'s (validated parts: {@code
 * db.findDataset/findTable<m.T>/cp.primaryKeyOf<m.T>/db.lookupRecord}, the {@code references model ... from module} line, {@code dt.now()}; the unvalidated parts it
 * also uses: {@code db.lookupRecordForUpdate(_ebx.transaction, ...)} and {@code db.recordOf(_ebx.transaction, ...)}, which {@code DistributionTriggerBody} proved for
 * {@code recordOf}); the composite {@code cp.primaryKeyOf<journal.Change>()} filled field by field is the shape {@code DistributionTriggerBody#recordKeyBlock} proved on
 * a master table. The installer reads the {@code update_script} validation and fails the install (never the load) when the body is refused.
 */
public final class MasterJournalTriggerBody
{
	public static final String AFTER_CREATE = "afterCreate";
	public static final String AFTER_MODIFY = "afterModify";

	/** The trigger types in install order, as {@code set_script_trigger}'s own (case-sensitive) vocabulary. */
	public static final String[] TYPES = { MasterJournalTriggerBody.AFTER_CREATE, MasterJournalTriggerBody.AFTER_MODIFY };

	/** The staging table's primary-key fields (fixed by the staging model: {@code sourceSystem}, then {@code sourceId}). */
	public static final String SOURCE_SYSTEM_FIELD = "sourceSystem";
	public static final String SOURCE_ID_FIELD = "sourceId";

	private MasterJournalTriggerBody()
	{
	}

	/** The name the script is declared under (and the {@code @trigger/<name>} idePath segment): {@code MasterJournalAfterCreate}, {@code MasterJournalAfterModify}. */
	public static String scriptName(final String pType)
	{
		return "MasterJournal" + Character.toUpperCase(pType.charAt(0)) + pType.substring(1);
	}

	/** The names of both triggers, create first. */
	public static String[] scriptNames()
	{
		return new String[] { MasterJournalTriggerBody.scriptName(MasterJournalTriggerBody.AFTER_CREATE), MasterJournalTriggerBody.scriptName(MasterJournalTriggerBody.AFTER_MODIFY) };
	}

	/**
	 * @param pType {@link #AFTER_CREATE} or {@link #AFTER_MODIFY}
	 * @param pProjectCode the project code, baked in as an escaped literal (the journal's {@code project} key field)
	 * @param pEntityName the staging entity = the table name, baked in as an escaped literal (the journal's {@code entity} key field)
	 */
	public static String body(final String pType, final String pProjectCode, final String pEntityName)
	{
		if (!MasterJournalTriggerBody.AFTER_CREATE.equals(pType) && !MasterJournalTriggerBody.AFTER_MODIFY.equals(pType))
		{
			throw new IllegalArgumentException("Unknown master journal trigger type: " + pType);
		}
		final String entryPoint = MasterJournalTriggerBody.AFTER_CREATE.equals(pType) ? "onAfterCreate" : "onAfterModify";
		final String project = MasterJournalTriggerBody.escape(pProjectCode);
		final String entity = MasterJournalTriggerBody.escape(pEntityName);
		final StringBuilder sb = new StringBuilder();
		sb.append("// Generated by ebx-mdm-factory (incremental master load, docs/VOLUME.md lot M-INC). Do not edit: the master load rewrites it.\n");
		sb.append("// Journals this staging record in the dataset " + FactoryPaths.MASTER_JOURNAL_DATASET + " of the same dataspace, but only while the entity is recording\n");
		sb.append("// (a Control row exists): the next master load reads these rows instead of the whole staging table.\n");
		sb.append("uses core.data as db;\n");
		sb.append("uses core.datetime as dt;\n");
		sb.append("uses core.complex as cp;\n");
		sb.append("references model \"MasterJournal\" from module \"" + FactoryPaths.MODULE_NAME + "\" as journal;\n\n");
		sb.append("export procedure " + entryPoint + "()\n");
		sb.append("begin\n");
		sb.append("\tconst journalDataset := db.findDataset(_ebx.dataspace, '" + MasterJournalTriggerBody.escape(FactoryPaths.MASTER_JOURNAL_DATASET) + "');\n");
		sb.append("\tif isNull(journalDataset) then return;\n");
		sb.append("\tconst controlTable := db.findTable<journal.Control>(journalDataset);\n");
		sb.append("\tvar controlPk := cp.primaryKeyOf<journal.Control>();\n");
		sb.append("\tcontrolPk.project := '" + project + "';\n");
		sb.append("\tcontrolPk.entity := '" + entity + "';\n");
		sb.append("\tconst control := db.lookupRecord(controlTable, controlPk);\n");
		sb.append("\tif isNull(control) then return;\n");
		sb.append("\tconst changeTable := db.findTable<journal.Change>(journalDataset);\n");
		sb.append("\tvar changePk := cp.primaryKeyOf<journal.Change>();\n");
		sb.append("\tchangePk.project := '" + project + "';\n");
		sb.append("\tchangePk.entity := '" + entity + "';\n");
		sb.append("\tchangePk.sourceSystem := _ebx.record.\"" + MasterJournalTriggerBody.SOURCE_SYSTEM_FIELD + "\";\n");
		sb.append("\tchangePk.sourceId := _ebx.record.\"" + MasterJournalTriggerBody.SOURCE_ID_FIELD + "\";\n");
		sb.append("\tconst moment := dt.now();\n");
		sb.append("\tconst existing := db.lookupRecord(changeTable, changePk);\n");
		sb.append("\tif isNull(existing) then\n");
		sb.append("\tbegin\n");
		sb.append("\t\tvar created := db.recordOf(_ebx.transaction, changeTable);\n");
		sb.append("\t\tcreated.project := '" + project + "';\n");
		sb.append("\t\tcreated.entity := '" + entity + "';\n");
		sb.append("\t\tcreated.sourceSystem := _ebx.record.\"" + MasterJournalTriggerBody.SOURCE_SYSTEM_FIELD + "\";\n");
		sb.append("\t\tcreated.sourceId := _ebx.record.\"" + MasterJournalTriggerBody.SOURCE_ID_FIELD + "\";\n");
		sb.append("\t\tcreated.changedAt := moment;\n");
		sb.append("\t\tcreated.changes := 1i;\n");
		sb.append("\t\tdb.saveRecord(created);\n");
		sb.append("\t\treturn;\n");
		sb.append("\tend\n");
		sb.append("\tvar touched := db.lookupRecordForUpdate(_ebx.transaction, changeTable, changePk);\n");
		sb.append("\tif isNull(touched) then return;\n");
		sb.append("\tvar nextCount := 1i;\n");
		sb.append("\tif not isNull(touched.changes) then nextCount := touched.changes + 1i;\n");
		sb.append("\ttouched.changedAt := moment;\n");
		sb.append("\ttouched.changes := nextCount;\n");
		sb.append("\tdb.saveRecord(touched);\n");
		sb.append("end\n");
		return sb.toString();
	}

	/** EBX Script string-literal escaping (backslash-style, CLAUDE.md sec 2 - never doubled quotes). */
	static String escape(final String pValue)
	{
		if (pValue == null)
		{
			return "";
		}
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < pValue.length(); i++)
		{
			final char c = pValue.charAt(i);
			switch (c)
			{
				case '\\':
					sb.append("\\\\");
					break;
				case '\'':
					sb.append("\\'");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					sb.append(c);
			}
		}
		return sb.toString();
	}
}
