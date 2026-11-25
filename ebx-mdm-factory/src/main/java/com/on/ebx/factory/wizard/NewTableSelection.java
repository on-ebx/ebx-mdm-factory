package com.on.ebx.factory.wizard;

/**
 * UX lot C1 (2026-10-13): whether the tables a NEW application gets through the wizard start ticked. Lot U2 decided they start unticked (the person
 * keeps the ones they want: 17 Salesforce tables, 2 or 3 of them are the point), except a sample file, which IS the tables the person chose. Seen
 * live the same evening: the list pick still ticked all 17 - every wizard path that plans a new application's tables asks this ONE rule instead
 * of passing its own literal. REST, the accelerators and the step-3 Plan button keep the ticked default ({@code LandingPlanner.plan} 3-argument form),
 * and a table already planned is never touched whatever this answers. Pure, tested in {@code NewTableSelectionTest}.
 */
public final class NewTableSelection
{
	public static final String CONNECTOR_TYPE_SAMPLE_FILE = "sample-file";

	private NewTableSelection()
	{
	}

	/** @param pConnectorType the connection's type as Connectors names it, null or empty when unknown (a catalog source with no connection) */
	public static boolean selectNew(final String pConnectorType)
	{
		return NewTableSelection.CONNECTOR_TYPE_SAMPLE_FILE.equals(pConnectorType);
	}
}
