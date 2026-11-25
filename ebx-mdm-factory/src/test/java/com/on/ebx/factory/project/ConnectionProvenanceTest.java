package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.project.ConnectionProvenance.Evidence;
import com.on.ebx.factory.project.ConnectionProvenance.StepRow;

/**
 * JUnit-free: run the main, a failure throws. Lot C (2026-10-17): how the factory knows which connection and which catalogue source it created for a project
 * - the {@code detail} JSON of two {@code PipelineStep} kinds, read back with no EBX type - and the two small pure helpers the deletion uses.
 */
public final class ConnectionProvenanceTest
{
	private static final String ID = "EcomClientsE2e1017183516";

	public static void main(final String[] pArgs)
	{
		ConnectionProvenanceTest.detailRoundTrip();
		ConnectionProvenanceTest.createdConnectionAndCreatedSource();
		ConnectionProvenanceTest.foundConnectionIsNotCreated();
		ConnectionProvenanceTest.aLaterRunNeverErasesTheProofOfCreation();
		ConnectionProvenanceTest.aPickedConnectionHasNoConnectionStep();
		ConnectionProvenanceTest.linkedSourceIsRecordedButNotCreated();
		ConnectionProvenanceTest.unreadableDetailProvesNothing();
		ConnectionProvenanceTest.scopeStandsInWhenTheDetailNamesNoConnection();
		ConnectionProvenanceTest.otherStepsAreIgnored();
		ConnectionProvenanceTest.catalogFlowLabels();
		ConnectionProvenanceTest.orChunks();

		System.out.println("ConnectionProvenanceTest OK");
	}

	private static void detailRoundTrip()
	{
		final String connection = ConnectionProvenance.connectionDetail(ID, "sample-file", true);
		ConnectionProvenanceTest.check("connection detail: " + connection, connection.equals("{\"connection\":{\"id\":\"" + ID + "\",\"type\":\"sample-file\",\"created\":true}}"));
		final String run = ConnectionProvenance.sourcesRunDetail(ID, "ECOMCLIENTSE2E1017183516", true);
		ConnectionProvenanceTest.check("sources-run detail: " + run, run.equals("{\"connection\":{\"id\":\"" + ID + "\"},\"catalogSource\":{\"id\":\"ECOMCLIENTSE2E1017183516\",\"created\":true}}"));
		final String noSource = ConnectionProvenance.sourcesRunDetail(ID, "", false);
		ConnectionProvenanceTest.check("no catalogSource member when none was learned: " + noSource, noSource.equals("{\"connection\":{\"id\":\"" + ID + "\"}}"));
	}

	/** The e2e shape: the importer created the row, the sources job created the source. */
	private static void createdConnectionAndCreatedSource()
	{
		final Map<String, Evidence> evidence = ConnectionProvenance.read(ConnectionProvenanceTest.rows(
			new StepRow(ConnectionProvenance.STEP_CONNECTION, ID, ConnectionProvenance.connectionDetail(ID, "sample-file", true)),
			new StepRow(ConnectionProvenance.STEP_SOURCES_RUN, ID, ConnectionProvenance.sourcesRunDetail(ID, "ECOMCLIENTSE2E1017183516", true))));
		final Evidence one = evidence.get(ID);
		ConnectionProvenanceTest.check("one connection", evidence.size() == 1 && one != null);
		ConnectionProvenanceTest.check("seen and created", one.connectionSeen && one.connectionCreated && "sample-file".equals(one.connectionType));
		ConnectionProvenanceTest.check("source created", one.catalogSourceCreated && "ECOMCLIENTSE2E1017183516".equals(one.catalogSourceId));
		ConnectionProvenanceTest.check("a candidate", one.isCandidate());
	}

	private static void foundConnectionIsNotCreated()
	{
		final Evidence one = ConnectionProvenance.read(ConnectionProvenanceTest.rows(
			new StepRow(ConnectionProvenance.STEP_CONNECTION, ID, ConnectionProvenance.connectionDetail(ID, "sample-file", false)))).get(ID);
		ConnectionProvenanceTest.check("seen, not created", one.connectionSeen && !one.connectionCreated);
		ConnectionProvenanceTest.check("still a candidate: the plan lists it as found, kept", one.isCandidate());
	}

	/** The project uploaded the file twice: the second import FOUND the row the first created. */
	private static void aLaterRunNeverErasesTheProofOfCreation()
	{
		final Evidence one = ConnectionProvenance.read(ConnectionProvenanceTest.rows(
			new StepRow(ConnectionProvenance.STEP_CONNECTION, ID, ConnectionProvenance.connectionDetail(ID, "sample-file", true)),
			new StepRow(ConnectionProvenance.STEP_CONNECTION, ID, ConnectionProvenance.connectionDetail(ID, "sample-file", false)),
			new StepRow(ConnectionProvenance.STEP_SOURCES_RUN, ID, ConnectionProvenance.sourcesRunDetail(ID, "SRC", true)),
			new StepRow(ConnectionProvenance.STEP_SOURCES_RUN, ID, ConnectionProvenance.sourcesRunDetail(ID, "SRC", false)))).get(ID);
		ConnectionProvenanceTest.check("connection still created", one.connectionCreated);
		ConnectionProvenanceTest.check("source still created", one.catalogSourceCreated && "SRC".equals(one.catalogSourceId));
	}

	/** A connection picked from the existing catalogue: only the sources job ran on it, and the source it linked was already there. */
	private static void aPickedConnectionHasNoConnectionStep()
	{
		final Evidence one = ConnectionProvenance.read(ConnectionProvenanceTest.rows(
			new StepRow(ConnectionProvenance.STEP_SOURCES_RUN, "Snowflake", ConnectionProvenance.sourcesRunDetail("Snowflake", "SNOWFLAKE", false)))).get("Snowflake");
		ConnectionProvenanceTest.check("not seen as a created connection", !one.connectionSeen && !one.connectionCreated);
		ConnectionProvenanceTest.check("not created source", !one.catalogSourceCreated);
		ConnectionProvenanceTest.check("not a candidate: nothing of it is ever listed for deletion", !one.isCandidate());
	}

	private static void linkedSourceIsRecordedButNotCreated()
	{
		final Evidence one = ConnectionProvenance.read(ConnectionProvenanceTest.rows(
			new StepRow(ConnectionProvenance.STEP_CONNECTION, ID, ConnectionProvenance.connectionDetail(ID, "sample-file", true)),
			new StepRow(ConnectionProvenance.STEP_SOURCES_RUN, ID, ConnectionProvenance.sourcesRunDetail(ID, "SRC", false)))).get(ID);
		ConnectionProvenanceTest.check("the source id is known", "SRC".equals(one.catalogSourceId));
		ConnectionProvenanceTest.check("but the factory did not create it", !one.catalogSourceCreated);
	}

	/** A step with no readable detail (an older step, a crash before the detail was written) proves nothing. */
	private static void unreadableDetailProvesNothing()
	{
		final Map<String, Evidence> evidence = ConnectionProvenance.read(ConnectionProvenanceTest.rows(
			new StepRow(ConnectionProvenance.STEP_CONNECTION, ID, ""),
			new StepRow(ConnectionProvenance.STEP_CONNECTION, ID, "not json"),
			new StepRow(ConnectionProvenance.STEP_SOURCES_RUN, ID, "[1,2]"),
			new StepRow(ConnectionProvenance.STEP_SOURCES_RUN, ID, null)));
		ConnectionProvenanceTest.check("no evidence at all: " + evidence.keySet(), evidence.isEmpty());
	}

	private static void scopeStandsInWhenTheDetailNamesNoConnection()
	{
		final Evidence one = ConnectionProvenance.read(ConnectionProvenanceTest.rows(
			new StepRow(ConnectionProvenance.STEP_SOURCES_RUN, ID, "{\"catalogSource\":{\"id\":\"SRC\",\"created\":true}}"))).get(ID);
		ConnectionProvenanceTest.check("the step scope names the connection", one != null && one.catalogSourceCreated && "SRC".equals(one.catalogSourceId));
	}

	private static void otherStepsAreIgnored()
	{
		final Map<String, Evidence> evidence = ConnectionProvenance.read(ConnectionProvenanceTest.rows(
			new StepRow("LANDING_FLOWS", ID, ConnectionProvenance.connectionDetail(ID, "sample-file", true))));
		ConnectionProvenanceTest.check("only the two step kinds count", evidence.isEmpty());
	}

	private static void catalogFlowLabels()
	{
		final String source = "ECOMCLIENTSE2E1017183516";
		for (final String kind : new String[] { "scan", "scan columns", "profile", "sample", "glossary", "usage", "lineage", "jobs" })
		{
			ConnectionProvenanceTest.check("catalogue flow: " + kind, ConnectionProvenance.isCatalogFlowLabel("Catalog " + kind + ": " + source, source));
		}
		ConnectionProvenanceTest.check("another source's flow is not ours", !ConnectionProvenance.isCatalogFlowLabel("Catalog scan: " + source + "_2", source));
		ConnectionProvenanceTest.check("not a prefix test: X does not claim the flows of AX", !ConnectionProvenance.isCatalogFlowLabel("Catalog scan: AX", "X"));
		ConnectionProvenanceTest.check("a landing flow is not a catalogue flow", !ConnectionProvenance.isCatalogFlowLabel("Landing: Ecom ECOM_CLIENTS ECOM_Clients", source));
		ConnectionProvenanceTest.check("the alert flow of the catalogue is not bound to a source", !ConnectionProvenance.isCatalogFlowLabel("Catalog alert: ChatWebhook", source));
		ConnectionProvenanceTest.check("an upper-case kind is not the catalogue's shape", !ConnectionProvenance.isCatalogFlowLabel("Catalog SCAN: " + source, source));
		ConnectionProvenanceTest.check("blank source id claims nothing", !ConnectionProvenance.isCatalogFlowLabel("Catalog scan: ", "") && !ConnectionProvenance.isCatalogFlowLabel("Catalog scan: x", null));
		ConnectionProvenanceTest.check("null label", !ConnectionProvenance.isCatalogFlowLabel(null, source));
	}

	private static void orChunks()
	{
		ConnectionProvenanceTest.check("no values, no chunk", ConnectionProvenance.orChunks("./asset", new ArrayList<String>(), 40).isEmpty());
		final List<String> chunks = ConnectionProvenance.orChunks("./asset", Arrays.asList("a", "b", "c'd"), 2);
		ConnectionProvenanceTest.check("two chunks: " + chunks, chunks.size() == 2);
		ConnectionProvenanceTest.check("first chunk: " + chunks.get(0), chunks.get(0).equals("./asset='a' or ./asset='b'"));
		ConnectionProvenanceTest.check("a quote is doubled: " + chunks.get(1), chunks.get(1).equals("./asset='c''d'"));
	}

	private static List<StepRow> rows(final StepRow... pRows)
	{
		return new ArrayList<StepRow>(Arrays.asList(pRows));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
