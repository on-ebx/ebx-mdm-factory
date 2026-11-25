package com.on.ebx.factory.backup;

import java.util.List;
import java.util.Map;

/**
 * What a restored row must NOT bring back to life (audit F-30): a backup is a record of a project, not a running one. Applied to the decoded values of
 * a row before it is written (and reported in the dry run). Pure.
 * <ul>
 * <li>{@code Project}: the automatic intake goes {@code OFF} (it would start chains for a project whose datasets may not exist here), with who switched it on forgotten;</li>
 * <li>{@code DistributionTarget}: the state goes {@code DISABLED} (a target armed against triggers that are not installed on this instance would send nothing, or the wrong thing;
 * a person re-arms it), {@code armedViaOverride} off, the scheduler key forgotten (the schedule is not restored);</li>
 * <li>{@code FactoryJob} (F2-03): a QUEUED or RUNNING row becomes {@code INTERRUPTED}, never replayed, and its {@code requestedBy} is forgotten;</li>
 * <li>{@code PipelineStep}, {@code AgentRun}, {@code IntakeChain}: a row that was still {@code RUNNING} becomes {@code INTERRUPTED} / {@code FAILED}: nothing runs it any more, and a RUNNING
 * row is what the engine reads as "a run is in progress".</li>
 * </ul>
 */
public final class RestoreAdjust
{
	private RestoreAdjust()
	{
	}

	/** Changes {@code pValues} in place; every change is described in {@code pNotes} once per kind. */
	public static void apply(final String pTable, final Map<String, Object> pValues, final List<String> pNotes)
	{
		if ("Project".equals(pTable))
		{
			final Object state = pValues.get("intakeState");
			if (state != null && !"OFF".equals(String.valueOf(state)))
			{
				pValues.put("intakeState", "OFF");
				RestoreAdjust.note(pNotes, "The automatic intake of the project is OFF after the restore (switch it on again once the pipeline has been rebuilt).");
			}
			pValues.put("intakePauseReason", null);
			pValues.put("intakeEnabledBy", null);
			pValues.put("intakeEnabledAt", null);
		}
		else if ("DistributionTarget".equals(pTable))
		{
			final Object state = pValues.get("state");
			if (state != null && !"DRAFT".equals(String.valueOf(state)) && !"DISABLED".equals(String.valueOf(state)))
			{
				pValues.put("state", "DISABLED");
				RestoreAdjust.note(pNotes, "Distribution targets are DISABLED after the restore: arm them again on purpose (first full send) once the master tables exist.");
			}
			pValues.put("armedViaOverride", Boolean.FALSE);
			pValues.put("scheduleKey", null);
		}
		else if ("PipelineStep".equals(pTable) || "AgentRun".equals(pTable))
		{
			if ("RUNNING".equals(String.valueOf(pValues.get("status"))))
			{
				pValues.put("status", "INTERRUPTED");
				RestoreAdjust.note(pNotes, "Steps and agent runs that were RUNNING in the backup are INTERRUPTED after the restore.");
			}
		}
		else if ("FactoryJob".equals(pTable))
		{
			// F2-03: the backup leaves QUEUED/RUNNING rows out, but an archive is a file: an edited one must not start a task, least of all one that names
			// another login (the job runs with the system session). Never replayed; requestedBy of a restored row is not trusted either.
			final String status = String.valueOf(pValues.get("status"));
			if ("QUEUED".equals(status) || "RUNNING".equals(status))
			{
				pValues.put("status", "INTERRUPTED");
				pValues.put("message", "Restored from a backup while the job was not finished: it is never run again.");
				pValues.put("requestedBy", null);
				RestoreAdjust.note(pNotes, "Jobs that were QUEUED or RUNNING in the archive are INTERRUPTED after the restore and are never run.");
			}
		}
		else if ("IntakeChain".equals(pTable))
		{
			if ("RUNNING".equals(String.valueOf(pValues.get("status"))))
			{
				pValues.put("status", "FAILED");
				pValues.put("message", "Restored from a backup while the chain was running.");
				RestoreAdjust.note(pNotes, "Intake chains that were RUNNING in the backup are FAILED after the restore.");
			}
		}
	}

	private static void note(final List<String> pNotes, final String pNote)
	{
		if (!pNotes.contains(pNote))
		{
			pNotes.add(pNote);
		}
	}
}
