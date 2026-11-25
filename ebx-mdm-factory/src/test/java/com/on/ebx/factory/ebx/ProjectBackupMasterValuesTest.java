package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.on.ebx.factory.backup.BackupTables;
import com.on.ebx.factory.core.FactoryPaths;
import com.orchestranetworks.schema.Path;

/**
 * JUnit-free: run the main, a failure throws. F3-06 / gap 11 (1.4.2): the archive does not carry master values of the agent tables while the governed agents are closed, and
 * {@code BackupTables.NOT_INCLUDED} says exactly what the archive holds.
 */
public final class ProjectBackupMasterValuesTest
{
	public static void main(final String[] pArgs)
	{
		// the list is the fields AgentProposalReadRule hides from those who cannot decide, plus AgentRun.inputs
		final Set<String> expected = new HashSet<String>();
		for (final Path field : AgentProposalReadRule.VALUE_FIELDS)
		{
			expected.add("AgentProposal." + field.format().replace("./", ""));
		}
		expected.add("AgentRun." + FactoryPaths.AgentRun.INPUTS.format().replace("./", ""));
		ProjectBackupMasterValuesTest.check("the omitted fields are the proposal's value fields and AgentRun.inputs", expected.equals(new HashSet<String>(BackupTables.MASTER_VALUE_FIELDS)));

		ProjectBackupMasterValuesTest.check("closed agents: arguments, rationale, applyMessage, target, inputs are omitted", BackupTables.valueOmitted("AgentProposal", "arguments", false)
			&& BackupTables.valueOmitted("AgentProposal", "rationale", false) && BackupTables.valueOmitted("AgentProposal", "applyMessage", false)
			&& BackupTables.valueOmitted("AgentProposal", "target", false) && BackupTables.valueOmitted("AgentRun", "inputs", false));
		ProjectBackupMasterValuesTest.check("closed agents: the other fields stay (status, tool, goal, a field of another table named target)", !BackupTables.valueOmitted("AgentProposal", "status", false)
			&& !BackupTables.valueOmitted("AgentProposal", "tool", false) && !BackupTables.valueOmitted("AgentRun", "goal", false) && !BackupTables.valueOmitted("TransferReject", "target", false));
		ProjectBackupMasterValuesTest.check("open agents: nothing is omitted", !BackupTables.valueOmitted("AgentProposal", "arguments", true) && !BackupTables.valueOmitted("AgentRun", "inputs", true));

		final List<String> omitted = new ArrayList<String>();
		final List<ProjectBackup.Column> columns = new ArrayList<ProjectBackup.Column>();
		for (final String name : new String[] { "id", "arguments", "status", "target" })
		{
			columns.add(new ProjectBackup.Column(name, Path.parse("./" + name), null, false, false, String.class, null));
		}
		final List<ProjectBackup.Column> kept = ProjectBackup.withoutOmitted(columns, "AgentProposal", false, omitted);
		ProjectBackupMasterValuesTest.check("withoutOmitted drops the two fields and names them", kept.size() == 2 && omitted.size() == 2 && omitted.contains("AgentProposal.arguments")
			&& omitted.contains("AgentProposal.target"));
		ProjectBackupMasterValuesTest.check("withoutOmitted keeps every column when the agents are open", ProjectBackup.withoutOmitted(columns, "AgentProposal", true, new ArrayList<String>()).size() == 4);

		final String notIncluded = String.join("\n", BackupTables.NOT_INCLUDED);
		ProjectBackupMasterValuesTest.check("NOT_INCLUDED no longer says the master data are all absent: it names AgentProposal and AgentRun.inputs", notIncluded.contains("AgentProposal")
			&& notIncluded.contains("AgentRun.inputs") && notIncluded.contains("valuesOmitted") && notIncluded.contains("with one exception"));
		System.out.println("ProjectBackupMasterValuesTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new IllegalStateException("FAILED: " + pWhat);
		}
	}
}
