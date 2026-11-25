package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JUnit-free: run the main, a failure throws. Exercises {@link AddApplicationOptions} (UX lot U2, 2026-10-13): the rows of the single "Add an application" list. */
public final class AddApplicationOptionsTest
{
	public static void main(final String[] pArgs)
	{
		AddApplicationOptionsTest.theLiveInstanceAsMeasured();
		AddApplicationOptionsTest.orderPutsWhatCanBeAddedFirst();
		AddApplicationOptionsTest.noCatalogOperationAndInactiveAreGreyedWithTheirReason();
		AddApplicationOptionsTest.theConnectionsWithoutCatalogOperationAsMeasured();
		AddApplicationOptionsTest.readabilityRule();
		AddApplicationOptionsTest.aConnectionAlreadyInTheProjectIsNotOfferedAgain();
		System.out.println("AddApplicationOptionsTest OK");
	}

	private static SourceOnboarding.ConnectionOption connection(final String pId, final String pLabel, final String pType, final boolean pActive, final boolean pCatalog, final String pSource,
		final int pTables)
	{
		final SourceOnboarding.ConnectionOption option = new SourceOnboarding.ConnectionOption(pId, pLabel, pType, pActive, pCatalog, pSource);
		option.scannedTables = pTables;
		return option;
	}

	private static AddApplicationOptions.Row row(final List<AddApplicationOptions.Row> pRows, final String pId)
	{
		for (final AddApplicationOptions.Row row : pRows)
		{
			if (row.id.equals(pId))
			{
				return row;
			}
		}
		throw new AssertionError("no row " + pId);
	}

	/**
	 * The instance of 2026-10-13: Salesforce (a connection, catalog source scanned, 17 tables), DemoCRM (a connection, scanned, 2 tables), a source declared by
	 * OpenLineage events with no connection ("Salesforce CRM (EU production)"), and a connection nobody scanned yet.
	 */
	private static void theLiveInstanceAsMeasured()
	{
		final List<SourceOnboarding.ConnectionOption> connections = Arrays.asList(AddApplicationOptionsTest.connection("Salesforce", "Salesforce", "salesforce", true, true, "SFDC", 17),
			AddApplicationOptionsTest.connection("DemoCRM", "DemoCRM", "jdbc", true, true, "DEMOCRM", 2), AddApplicationOptionsTest.connection("NewOne", "Brand new", "jdbc", true, true, "", -1));
		final List<WizardSnapshot.CatalogSourceInfo> sources = Arrays.asList(new WizardSnapshot.CatalogSourceInfo("SFDC", "salesforce://login.salesforce.com", "OTHER", "Salesforce"),
			new WizardSnapshot.CatalogSourceInfo("DEMOCRM", "Demo CRM", "DATABASE", "DemoCRM"), new WizardSnapshot.CatalogSourceInfo("OL", "Salesforce CRM (EU production)", "OTHER", ""),
			new WizardSnapshot.CatalogSourceInfo("GONE", "Old system", "DATABASE", "DeletedConnection"));
		final List<AddApplicationOptions.Row> rows = AddApplicationOptions.build(connections, sources, new HashSet<String>(), new HashSet<String>());
		AddApplicationOptionsTest.check("a source reached through its connection is listed once: " + rows.size(), rows.size() == 5);
		AddApplicationOptionsTest.check("Salesforce is scanned with 17 tables", AddApplicationOptionsTest.row(rows, "Salesforce").state == AddApplicationOptions.State.SCANNED
			&& AddApplicationOptionsTest.row(rows, "Salesforce").tables == 17 && AddApplicationOptionsTest.row(rows, "Salesforce").pickable());
		AddApplicationOptionsTest.check("a connection the catalog does not know: will be scanned", AddApplicationOptionsTest.row(rows, "NewOne").state == AddApplicationOptions.State.CONNECTION_ONLY
			&& AddApplicationOptionsTest.row(rows, "NewOne").pickable());
		final AddApplicationOptions.Row lineage = AddApplicationOptionsTest.row(rows, "OL");
		AddApplicationOptionsTest.check("the OpenLineage source is greyed, not pickable", lineage.state == AddApplicationOptions.State.LINEAGE_ONLY && !lineage.pickable());
		AddApplicationOptionsTest.check("a source whose connection was removed is greyed", AddApplicationOptionsTest.row(rows, "GONE").state == AddApplicationOptions.State.CONNECTION_MISSING);
		AddApplicationOptionsTest.check("chip keys are bundle keys", "wizard.src2.app.state.LINEAGE_ONLY".equals(lineage.chipKey()));
	}

	private static void orderPutsWhatCanBeAddedFirst()
	{
		final List<SourceOnboarding.ConnectionOption> connections = Arrays.asList(AddApplicationOptionsTest.connection("z", "Zeta", "jdbc", true, true, "", -1),
			AddApplicationOptionsTest.connection("a", "alpha", "jdbc", false, true, "", -1), AddApplicationOptionsTest.connection("s", "Sigma", "jdbc", true, true, "S", 3),
			AddApplicationOptionsTest.connection("p", "Pi", "jdbc", true, true, "P", 1));
		final Set<String> inProject = new HashSet<String>(Arrays.asList("p"));
		final List<AddApplicationOptions.Row> rows = AddApplicationOptions.build(connections, new ArrayList<WizardSnapshot.CatalogSourceInfo>(), inProject, new HashSet<String>());
		final StringBuilder order = new StringBuilder();
		for (final AddApplicationOptions.Row row : rows)
		{
			order.append(row.id).append(' ');
		}
		AddApplicationOptionsTest.check("scanned, to scan, already in project, greyed: " + order, order.toString().equals("s z p a "));
	}

	private static void noCatalogOperationAndInactiveAreGreyedWithTheirReason()
	{
		final SourceOnboarding.ConnectionOption lookup = AddApplicationOptionsTest.connection("lookup", "Lookup", "rest", true, false, "", -1);
		lookup.unreadableReason = AddApplicationOptions.REASON_NO_READ_OPERATION;
		final List<AddApplicationOptions.Row> rows = AddApplicationOptions.build(
			Arrays.asList(lookup, AddApplicationOptionsTest.connection("off", "Off", "jdbc", false, true, "", -1)),
			new ArrayList<WizardSnapshot.CatalogSourceInfo>(), new HashSet<String>(), new HashSet<String>());
		AddApplicationOptionsTest.check("nothing to read it with", AddApplicationOptionsTest.row(rows, "lookup").state == AddApplicationOptions.State.CANNOT_READ
			&& !AddApplicationOptionsTest.row(rows, "lookup").pickable() && "NO_READ_OPERATION".equals(AddApplicationOptionsTest.row(rows, "lookup").reason));
		AddApplicationOptionsTest.check("inactive", AddApplicationOptionsTest.row(rows, "off").state == AddApplicationOptions.State.INACTIVE && !AddApplicationOptionsTest.row(rows, "off").pickable());
	}

	private static SourceOnboarding.ConnectionOption readable(final String pId, final String pType, final String pSource, final int pTables, final boolean pReadable, final String pReason)
	{
		final SourceOnboarding.ConnectionOption option = AddApplicationOptionsTest.connection(pId, pId, pType, true, false, pSource, pTables);
		option.readable = pReadable;
		option.unreadableReason = pReason;
		return option;
	}

	/**
	 * UX lot C1, the list of 2026-10-13 (project UxCrmSf2): DemoCRM is a rest connection with no catalog operation whose source DEMOCRM already holds 2
	 * tables found from the data (it was greyed "see More ways below"); GenericREST a rest connection with no source that can list; Slack and OpenAI cannot
	 * list anything; OL_SALESFORCE_EU_PROD is a catalog source declared by lineage events, with no connection.
	 */
	private static void theConnectionsWithoutCatalogOperationAsMeasured()
	{
		final List<SourceOnboarding.ConnectionOption> connections = Arrays.asList(
			AddApplicationOptionsTest.readable("DemoCRM", "rest", "DEMOCRM", 2, false, ""),
			AddApplicationOptionsTest.readable("GenericREST", "rest", "", -1, true, ""),
			AddApplicationOptionsTest.readable("Slack", "slack", "", -1, false, AddApplicationOptions.REASON_NO_READ_OPERATION),
			// UX lot C4 (2026-10-14): measured on connectors_list, the llm type lists "complete (read)": C1's rule called it readable and offered it
			AddApplicationOptionsTest.readable("OpenAI", "llm", "", -1, true, ""));
		final List<WizardSnapshot.CatalogSourceInfo> sources = Arrays.asList(new WizardSnapshot.CatalogSourceInfo("DEMOCRM", "Demo CRM (EBX script, test source)", "OTHER", "DemoCRM"),
			new WizardSnapshot.CatalogSourceInfo("OL_SALESFORCE_EU_PROD", "Salesforce CRM (EU production)", "OTHER", ""));
		final List<AddApplicationOptions.Row> rows = AddApplicationOptions.build(connections, sources, new HashSet<String>(), new HashSet<String>());
		final AddApplicationOptions.Row demo = AddApplicationOptionsTest.row(rows, "DemoCRM");
		AddApplicationOptionsTest.check("DemoCRM: pickable, found from the data, 2 tables", demo.state == AddApplicationOptions.State.FOUND_FROM_DATA && demo.pickable() && demo.tables == 2 && demo.showsTables()
			&& "wizard.src2.app.state.FOUND_FROM_DATA".equals(demo.chipKey()));
		final AddApplicationOptions.Row generic = AddApplicationOptionsTest.row(rows, "GenericREST");
		AddApplicationOptionsTest.check("GenericREST: pickable, will be read from its data", generic.state == AddApplicationOptions.State.DEDUCED_READ && generic.pickable() && !generic.showsTables());
		final AddApplicationOptions.Row slack = AddApplicationOptionsTest.row(rows, "Slack");
		AddApplicationOptionsTest.check("Slack: greyed with a reason, never 'see More ways'", slack.state == AddApplicationOptions.State.CANNOT_READ && !slack.pickable()
			&& "NO_READ_OPERATION".equals(slack.reason) && "wizard.src2.app.state.CANNOT_READ".equals(slack.chipKey()));
		final AddApplicationOptions.Row openAi = AddApplicationOptionsTest.row(rows, "OpenAI");
		AddApplicationOptionsTest.check("OpenAI (llm): greyed, a language model is never an application, whatever its operations say", openAi.state == AddApplicationOptions.State.CANNOT_READ
			&& !openAi.pickable() && "NOT_AN_APPLICATION".equals(openAi.reason) && "wizard.src2.app.state.CANNOT_READ".equals(openAi.chipKey()));
		AddApplicationOptionsTest.check("the llm type, and only it, is never a source", AddApplicationOptions.neverASource("llm") && !AddApplicationOptions.neverASource("rest")
			&& !AddApplicationOptions.neverASource("") && !AddApplicationOptions.neverASource(null));
		// an llm connection already in the project or switched off keeps the row it had (those rows come first)
		final List<AddApplicationOptions.Row> llmOff = AddApplicationOptions.build(Arrays.asList(AddApplicationOptionsTest.connection("Claude", "Claude", "llm", false, false, "", -1)),
			new ArrayList<WizardSnapshot.CatalogSourceInfo>(), new HashSet<String>(), new HashSet<String>());
		AddApplicationOptionsTest.check("a switched-off llm reads 'switched off', as before", AddApplicationOptionsTest.row(llmOff, "Claude").state == AddApplicationOptions.State.INACTIVE);
		AddApplicationOptionsTest.check("the lineage-only source stays greyed", AddApplicationOptionsTest.row(rows, "OL_SALESFORCE_EU_PROD").state == AddApplicationOptions.State.LINEAGE_ONLY);
		AddApplicationOptionsTest.check("DemoCRM is listed once (its source is reached through the connection)", rows.size() == 5);
		final StringBuilder order = new StringBuilder();
		for (final AddApplicationOptions.Row row : rows)
		{
			order.append(row.id).append(' ');
		}
		AddApplicationOptionsTest.check("pickable first: " + order, order.toString().startsWith("DemoCRM GenericREST "));
		// a source that exists with no table found yet is read from its data when it can be, else greyed
		final List<AddApplicationOptions.Row> empty = AddApplicationOptions.build(Arrays.asList(AddApplicationOptionsTest.readable("c", "rest", "SRC", 0, true, "")),
			new ArrayList<WizardSnapshot.CatalogSourceInfo>(), new HashSet<String>(), new HashSet<String>());
		AddApplicationOptionsTest.check("source with no table, readable", AddApplicationOptionsTest.row(empty, "c").state == AddApplicationOptions.State.DEDUCED_READ);
		// the pick: a connection with no catalog operation goes to the deduced scan, one with it to the catalog scan
		AddApplicationOptionsTest.check("pick path", AddApplicationOptions.usesDeducedScan(connections.get(0))
			&& !AddApplicationOptions.usesDeducedScan(AddApplicationOptionsTest.connection("Sf", "Sf", "salesforce", true, true, "S", 17)));
		// one already in the project stays "already in this project", whatever it can do
		final List<AddApplicationOptions.Row> inProject = AddApplicationOptions.build(connections, sources, new HashSet<String>(Arrays.asList("DemoCRM")), new HashSet<String>());
		AddApplicationOptionsTest.check("in project", AddApplicationOptionsTest.row(inProject, "DemoCRM").state == AddApplicationOptions.State.IN_PROJECT);
	}

	/** The rule behind {@code readable}: a type that lists operations is read when one is a {@code read}; a type that lists none is described. */
	private static void readabilityRule()
	{
		AddApplicationOptionsTest.check("type with a read operation", AddApplicationOptions.unreadableReason(true, true, null, false).isEmpty());
		AddApplicationOptionsTest.check("type with only write/search operations", "NO_READ_OPERATION".equals(AddApplicationOptions.unreadableReason(true, false, null, false)));
		AddApplicationOptionsTest.check("a described connection with a read-only operation", AddApplicationOptions.unreadableReason(false, false, null, true).isEmpty());
		AddApplicationOptionsTest.check("a described connection with none", "NO_READ_OPERATION".equals(AddApplicationOptions.unreadableReason(false, false, null, false)));
		AddApplicationOptionsTest.check("a describe that failed is unknown, not 'no operation'", "DESCRIBE_FAILED".equals(AddApplicationOptions.unreadableReason(false, false, "refused", false)));
	}

	/** Measured: "Use this source" on a system already in the project showed the add form again. Here the row says so and cannot be picked. */
	private static void aConnectionAlreadyInTheProjectIsNotOfferedAgain()
	{
		final List<SourceOnboarding.ConnectionOption> connections = Arrays.asList(AddApplicationOptionsTest.connection("Salesforce", "Salesforce", "salesforce", true, true, "SFDC", 17));
		AddApplicationOptions.Row row = AddApplicationOptionsTest.row(AddApplicationOptions.build(connections, new ArrayList<WizardSnapshot.CatalogSourceInfo>(),
			new HashSet<String>(Arrays.asList("Salesforce")), new HashSet<String>()), "Salesforce");
		AddApplicationOptionsTest.check("by connection id", row.state == AddApplicationOptions.State.IN_PROJECT && !row.pickable());
		row = AddApplicationOptionsTest.row(AddApplicationOptions.build(connections, new ArrayList<WizardSnapshot.CatalogSourceInfo>(), new HashSet<String>(),
			new HashSet<String>(Arrays.asList("SFDC"))), "Salesforce");
		AddApplicationOptionsTest.check("by catalog source id", row.state == AddApplicationOptions.State.IN_PROJECT);
		// a scanned catalog source with nothing in it (declared by events) is to be scanned first, not "scanned, 0 tables"
		final List<SourceOnboarding.ConnectionOption> empty = Arrays.asList(AddApplicationOptionsTest.connection("c", "C", "jdbc", true, true, "SRC", 0));
		AddApplicationOptionsTest.check("in the catalog but no column read: will be scanned",
			AddApplicationOptionsTest.row(AddApplicationOptions.build(empty, new ArrayList<WizardSnapshot.CatalogSourceInfo>(), new HashSet<String>(), new HashSet<String>()), "c").state
				== AddApplicationOptions.State.CONNECTION_ONLY);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
