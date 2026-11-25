package com.on.ebx.factory.intake;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.on.ebx.factory.core.FactoryPaths;

/**
 * Automatic intake lot (docs/AUTOMATIC-INTAKE.md): builds the EBX-Script source of the three generated triggers of one LANDING table, authored by
 * {@code ebx.IntakeTriggerInstaller} through {@code update_script}. Pure string templating, no EBX class.
 * <ul>
 * <li>{@code IntakeAfterCreate} / {@code IntakeAfterModify} (type {@code afterCreate} / {@code afterModify}) - the WAKE-UP: when the saved record's {@code _status}
 * is {@code NEW} or empty, touch the signal row of this source in the dataset {@link FactoryPaths#INTAKE_SIGNAL_DATASET} of the SAME dataspace (a table trigger
 * can only write through its own ambient transaction, which is scoped to its dataspace - the proven mechanism of {@code DistributionTriggerBody}'s outbox):
 * look it up by primary key (no SQL), create it if missing, otherwise write {@code lastRowAt := now} <b>only when the stored value is older than 5 seconds</b>, so a
 * mass load does not write the signal once per row. The installer pre-creates the row, so the create branch is a fallback.</li>
 * <li>{@code IntakeBeforeModify} (type {@code beforeModify}) - keeps the queue truthful for rows an application pushes straight into the landing table (it does not
 * set our technical fields): when the writer does NOT change {@code _status} itself, the resulting status is not already NEW/empty, and at least one SOURCE column
 * (the generator enumerates them: {@link #sourceColumns}, never a {@code _...} technical field) changed, set {@code _ebx.nextRecord._status := 'NEW'}.</li>
 * </ul>
 * Rules of CLAUDE.md sec 2 followed: no literal list index, no try/catch, a typed null-safe helper {@code function differs(a: string, b: string): boolean}, every
 * column written as a QUOTED identifier ({@code "FROM"}: a column named like a keyword compiles), string literals escaped backslash-style ({@link #escape}).
 * <p>
 * <b>Validated</b> (2026-10-05, {@code validate_draft} as a {@code library}, answered {@code isSuccess: true}, no error): the helper functions, the record-typed
 * comparison with quoted columns against the real landing table {@code Vol100k_Landing_VOL100KCRM/CONTACT}, {@code dt.addSeconds(x, 5) < dt.now()} on datetimes,
 * {@code db.findDataspace/findDataset/findTable<m.T>/cp.primaryKeyOf/db.lookupRecord} with a string primary key and a dateTime field, modelled on the deployed
 * {@code DistributionOutbox.Control} (the model of the signal is not deployed yet). <b>Not validated</b>: the three trigger entry points themselves (a data-model
 * script type cannot be validated without being declared on a published model), {@code _ebx.nextRecord."_status" := 'NEW'} (same family as the proven
 * {@code _ebx.nextRecord.f} write of a derivation trigger, CLAUDE.md sec 5), {@code db.lookupRecordForUpdate(_ebx.transaction, ...)} and {@code db.recordOf(_ebx.transaction, ...)}
 * (signatures read with {@code get_api_help}; the second is the proven distribution outbox write), and the references to {@code IntakeSignal}.
 */
public final class IntakeTriggerBody
{
	public static final String AFTER_CREATE = "afterCreate";
	public static final String AFTER_MODIFY = "afterModify";
	public static final String BEFORE_MODIFY = "beforeModify";

	/** The trigger types in install order, as {@code set_script_trigger}'s own (case-sensitive) vocabulary. */
	public static final String[] TYPES = { IntakeTriggerBody.AFTER_CREATE, IntakeTriggerBody.AFTER_MODIFY, IntakeTriggerBody.BEFORE_MODIFY };

	/** The signal row is touched at most once per this many seconds per source. */
	public static final int THROTTLE_SECONDS = 5;

	private IntakeTriggerBody()
	{
	}

	/** The name the script is declared under (and the {@code @trigger/<name>} idePath segment): {@code IntakeAfterCreate}, {@code IntakeAfterModify}, {@code IntakeBeforeModify}. */
	public static String scriptName(final String pType)
	{
		return "Intake" + Character.toUpperCase(pType.charAt(0)) + pType.substring(1);
	}

	/**
	 * The columns the before-modify rule watches, from every field name of the landing table: everything except the technical fields the factory adds
	 * ({@link FactoryPaths.Landing}). A name that is empty or holds a double quote (it cannot be written as a quoted identifier) is dropped. Order kept, no duplicates.
	 */
	public static List<String> sourceColumns(final Collection<String> pAllFieldNames)
	{
		final List<String> out = new ArrayList<String>();
		for (final String name : pAllFieldNames)
		{
			if (name == null || name.isEmpty() || name.indexOf('"') >= 0 || IntakeTriggerBody.isTechnical(name) || out.contains(name))
			{
				continue;
			}
			out.add(name);
		}
		return out;
	}

	static boolean isTechnical(final String pName)
	{
		return FactoryPaths.Landing.SOURCE_ID.equals(pName) || FactoryPaths.Landing.SOURCE_SYSTEM.equals(pName) || FactoryPaths.Landing.LOAD_ID.equals(pName)
			|| FactoryPaths.Landing.RECEIVED_AT.equals(pName) || FactoryPaths.Landing.ROW_HASH.equals(pName) || FactoryPaths.Landing.STATUS.equals(pName);
	}

	/**
	 * @param pType {@link #AFTER_CREATE}, {@link #AFTER_MODIFY} or {@link #BEFORE_MODIFY}
	 * @param pSourceSystem the source system = the landing dataset name = the signal row's key; baked in as an escaped literal
	 * @param pSourceColumns {@link #sourceColumns} of the table; only used by {@link #BEFORE_MODIFY}
	 */
	public static String body(final String pType, final String pSourceSystem, final List<String> pSourceColumns)
	{
		if (IntakeTriggerBody.BEFORE_MODIFY.equals(pType))
		{
			return IntakeTriggerBody.beforeModify(pSourceColumns);
		}
		if (IntakeTriggerBody.AFTER_CREATE.equals(pType) || IntakeTriggerBody.AFTER_MODIFY.equals(pType))
		{
			return IntakeTriggerBody.wakeUp(pType, pSourceSystem);
		}
		throw new IllegalArgumentException("Unknown intake trigger type: " + pType);
	}

	private static String wakeUp(final String pType, final String pSourceSystem)
	{
		final String entryPoint = IntakeTriggerBody.AFTER_CREATE.equals(pType) ? "onAfterCreate" : "onAfterModify";
		final String source = IntakeTriggerBody.escape(pSourceSystem);
		final StringBuilder sb = new StringBuilder();
		sb.append("// Generated by ebx-mdm-factory (automatic intake, docs/AUTOMATIC-INTAKE.md). Do not edit: the intake installer rewrites it.\n");
		sb.append("// Wake-up only: touches the signal row of this source; the queue is the landing table itself (rows at _status NEW or empty).\n");
		sb.append("uses core.data as db;\n");
		sb.append("uses core.datetime as dt;\n");
		sb.append("uses core.complex as cp;\n");
		sb.append("references model \"IntakeSignal\" from module \"" + FactoryPaths.MODULE_NAME + "\" as signal;\n\n");
		sb.append("function isWaiting(status: string): boolean\n");
		sb.append("begin\n");
		sb.append("\treturn isNull(status) or status = 'NEW';\n");
		sb.append("end\n\n");
		sb.append("export procedure " + entryPoint + "()\n");
		sb.append("begin\n");
		sb.append("\tif not isWaiting(_ebx.record.\"" + FactoryPaths.Landing.STATUS + "\") then return;\n");
		sb.append("\tconst signalDataset := db.findDataset(_ebx.dataspace, '" + IntakeTriggerBody.escape(FactoryPaths.INTAKE_SIGNAL_DATASET) + "');\n");
		sb.append("\tif isNull(signalDataset) then return;\n");
		sb.append("\tconst signalTable := db.findTable<signal.Signal>(signalDataset);\n");
		sb.append("\tvar signalPk := cp.primaryKeyOf<signal.Signal>();\n");
		sb.append("\tsignalPk.sourceSystem := '" + source + "';\n");
		sb.append("\tconst moment := dt.now();\n");
		sb.append("\tconst existing := db.lookupRecord(signalTable, signalPk);\n");
		sb.append("\tif isNull(existing) then\n");
		sb.append("\tbegin\n");
		sb.append("\t\tvar created := db.recordOf(_ebx.transaction, signalTable);\n");
		sb.append("\t\tcreated.sourceSystem := '" + source + "';\n");
		sb.append("\t\tcreated.lastRowAt := moment;\n");
		sb.append("\t\tdb.saveRecord(created);\n");
		sb.append("\t\treturn;\n");
		sb.append("\tend\n");
		// throttle: a mass load must not write the signal once per row
		sb.append("\tif not isNull(existing.lastRowAt) then\n");
		sb.append("\tbegin\n");
		sb.append("\t\tif dt.addSeconds(existing.lastRowAt, " + IntakeTriggerBody.THROTTLE_SECONDS + ") > moment then return;\n");
		sb.append("\tend\n");
		sb.append("\tvar touched := db.lookupRecordForUpdate(_ebx.transaction, signalTable, signalPk);\n");
		sb.append("\tif isNull(touched) then return;\n");
		sb.append("\ttouched.lastRowAt := moment;\n");
		sb.append("\tdb.saveRecord(touched);\n");
		sb.append("end\n");
		return sb.toString();
	}

	private static String beforeModify(final List<String> pSourceColumns)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("// Generated by ebx-mdm-factory (automatic intake, docs/AUTOMATIC-INTAKE.md). Do not edit: the intake installer rewrites it.\n");
		sb.append("// Keeps the queue truthful for rows an application writes straight into this table: a change of a SOURCE column puts the row back at _status NEW,\n");
		sb.append("// unless the writer sets _status itself (the factory's own writes move _status NEW -> STAGED/REJECTED, so they are left alone).\n\n");
		sb.append("function differs(a: string, b: string): boolean\n");
		sb.append("begin\n");
		sb.append("\tif isNull(a) and isNull(b) then return false;\n");
		sb.append("\tif isNull(a) or isNull(b) then return true;\n");
		sb.append("\treturn a <> b;\n");
		sb.append("end\n\n");
		sb.append("export procedure onBeforeModify()\n");
		sb.append("begin\n");
		if (pSourceColumns == null || pSourceColumns.isEmpty())
		{
			sb.append("\t// this table has no source column: nothing to watch\n");
			sb.append("\treturn;\n");
			sb.append("end\n");
			return sb.toString();
		}
		final String status = "\"" + FactoryPaths.Landing.STATUS + "\"";
		sb.append("\tif differs(_ebx.record." + status + ", _ebx.nextRecord." + status + ") then return;\n");
		sb.append("\tif isNull(_ebx.nextRecord." + status + ") or _ebx.nextRecord." + status + " = 'NEW' then return;\n");
		for (final String column : pSourceColumns)
		{
			final String quoted = "\"" + column + "\"";
			sb.append("\tif differs(_ebx.record." + quoted + ", _ebx.nextRecord." + quoted + ") then\n");
			sb.append("\tbegin\n");
			sb.append("\t\t_ebx.nextRecord." + status + " := 'NEW';\n");
			sb.append("\t\treturn;\n");
			sb.append("\tend\n");
		}
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
