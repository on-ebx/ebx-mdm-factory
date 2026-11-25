package com.on.ebx.factory.ebx;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.deducedscan.OperationCatalog;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Bug fix 2026-09-26
 * (found live on project DeducedTest/connection OpenFDA, PipelineStep 405 - see {@link
 * DeducedScanOperations}'s own Javadoc): exercises only {@link DeducedScanOperations#parseInto}, the pure
 * JSON-navigation part of {@link DeducedScanOperations#describe} - no live MCP call, no EBX session.
 * Fixtures are real {@code connectors_describe} answer shapes: OpenFDA (measured live, verbatim) and a
 * Salesforce-shaped one (a listing operation with only optional inputs, plus a write operation that must
 * stay excluded from anything read-only downstream).
 */
public final class DeducedScanOperationsTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		DeducedScanOperationsTest.openFda();
		DeducedScanOperationsTest.salesforceShaped();
		DeducedScanOperationsTest.legacyShapeFallsBackToOptionalInputs();
		DeducedScanOperationsTest.catalogOperationDetected();
		System.out.println("DeducedScanOperationsTest OK");
	}

	/** The exact live answer of {@code connectors_describe(connector="OpenFDA")}, 2026-09-26 - the fixture that reproduces the original bug: every operation needs an input to run at all, so the old (pre-fix) parsing, which read zero inputs for all three, let the model pick every one of them as a "listing". */
	private static void openFda() throws Exception
	{
		final String json = "{\"id\":\"OpenFDA\",\"label\":\"openFDA (US FDA devices and drugs)\",\"type\":\"openfda\","
			+ "\"operations\":["
			+ "{\"name\":\"udi\",\"kind\":\"READ\",\"description\":\"GUDID device record by device identifier (DI)\",\"readOnly\":true,\"requiredInputs\":[\"di\"],\"optionalInputs\":[]},"
			+ "{\"name\":\"deviceSearch\",\"kind\":\"SEARCH\",\"description\":\"GUDID device by brand name (optional company)\",\"readOnly\":true,\"requiredInputs\":[\"brand\"],\"optionalInputs\":[]},"
			+ "{\"name\":\"drug\",\"kind\":\"READ\",\"description\":\"Drug product by NDC (National Drug Code)\",\"readOnly\":true,\"requiredInputs\":[\"ndc\"],\"optionalInputs\":[]}"
			+ "]}";
		final DeducedScanOperations.Described described = DeducedScanOperationsTest.parse(json);
		DeducedScanOperationsTest.check("3 operations parsed", described.operations.size() == 3);
		DeducedScanOperationsTest.check("no catalog operation", !described.hasCatalogOperation);

		final OperationCatalog.OperationInfo udi = OperationCatalog.findByName(described.operations, "udi");
		DeducedScanOperationsTest.check("udi found", udi != null);
		DeducedScanOperationsTest.check("udi is readOnly", udi.readOnly);
		DeducedScanOperationsTest.check("udi kind is READ", "READ".equals(udi.kind));
		DeducedScanOperationsTest.check("udi description carried", udi.description != null && udi.description.contains("GUDID"));
		DeducedScanOperationsTest.check("udi required input is 'di' - THE bug: this used to read empty", udi.requiredInputNames.size() == 1 && "di".equals(udi.requiredInputNames.get(0)));
		DeducedScanOperationsTest.check("udi has no optional input", udi.optionalInputNames.isEmpty());

		final OperationCatalog.OperationInfo deviceSearch = OperationCatalog.findByName(described.operations, "deviceSearch");
		DeducedScanOperationsTest.check("deviceSearch kind is SEARCH", "SEARCH".equals(deviceSearch.kind));
		DeducedScanOperationsTest.check("deviceSearch required input is 'brand'", deviceSearch.requiredInputNames.contains("brand"));

		final OperationCatalog.OperationInfo drug = OperationCatalog.findByName(described.operations, "drug");
		DeducedScanOperationsTest.check("drug required input is 'ndc'", drug.requiredInputNames.contains("ndc"));

		// The callability guard (contract point 3): a model that (wrongly) chose all three with no input value
		// leaves nothing callable - the enrichment/lookup-only outcome, not "no table could be deduced".
		final java.util.LinkedHashMap<String, String> noInputs = new java.util.LinkedHashMap<String, String>();
		final List<OperationCatalog.ListChoice> allThreeChosen = java.util.Arrays.asList(new OperationCatalog.ListChoice("udi", noInputs),
			new OperationCatalog.ListChoice("deviceSearch", noInputs), new OperationCatalog.ListChoice("drug", noInputs));
		final OperationCatalog.CallabilityResult callability = OperationCatalog.filterCallable(allThreeChosen, described.operations);
		DeducedScanOperationsTest.check("none of the three OpenFDA lookups is callable with no input", callability.callable.isEmpty());
		DeducedScanOperationsTest.check("three reasons reported, one per operation", callability.notCallableReasons.size() == 3);
	}

	/** A Salesforce-shaped answer: a real listing operation (only OPTIONAL inputs - always callable) alongside a WRITE operation that must never even reach the read-only list. */
	private static void salesforceShaped() throws Exception
	{
		final String json = "{\"id\":\"SF\",\"operations\":["
			+ "{\"name\":\"listContacts\",\"kind\":\"SEARCH\",\"description\":\"List contact records, optionally paged\",\"readOnly\":true,\"requiredInputs\":[],\"optionalInputs\":[\"after\",\"pageSize\"]},"
			+ "{\"name\":\"upsertContact\",\"kind\":\"WRITE\",\"description\":\"Create or update a contact\",\"readOnly\":false,\"requiredInputs\":[\"body\"],\"optionalInputs\":[]}"
			+ "]}";
		final DeducedScanOperations.Described described = DeducedScanOperationsTest.parse(json);
		DeducedScanOperationsTest.check("2 operations parsed", described.operations.size() == 2);
		final List<OperationCatalog.OperationInfo> readOnly = OperationCatalog.readOnlyOperations(described.operations);
		DeducedScanOperationsTest.check("only the read-only one is kept", readOnly.size() == 1 && "listContacts".equals(readOnly.get(0).name));

		final OperationCatalog.OperationInfo listContacts = readOnly.get(0);
		DeducedScanOperationsTest.check("listContacts has no required input", listContacts.requiredInputNames.isEmpty());
		DeducedScanOperationsTest.check("listContacts has two optional inputs", listContacts.optionalInputNames.size() == 2);

		final java.util.LinkedHashMap<String, String> noInputs = new java.util.LinkedHashMap<String, String>();
		final OperationCatalog.CallabilityResult callability = OperationCatalog
			.filterCallable(java.util.Collections.singletonList(new OperationCatalog.ListChoice("listContacts", noInputs)), readOnly);
		DeducedScanOperationsTest.check("a listing operation with only optional inputs is callable even with none filled in", callability.callable.size() == 1);
	}

	/** A connector whose {@code connectors_describe} predates {@code requiredInputs}/{@code optionalInputs} (the legacy {@code inputs} array of names) - every declared input falls back to OPTIONAL, never blocking the callability guard. */
	private static void legacyShapeFallsBackToOptionalInputs() throws Exception
	{
		final String json = "{\"id\":\"Legacy\",\"operations\":[{\"name\":\"list_things\",\"readOnly\":true,\"inputs\":[\"after\"]}]}";
		final DeducedScanOperations.Described described = DeducedScanOperationsTest.parse(json);
		final OperationCatalog.OperationInfo listThings = OperationCatalog.findByName(described.operations, "list_things");
		DeducedScanOperationsTest.check("legacy 'inputs' read", listThings.inputNames.contains("after"));
		DeducedScanOperationsTest.check("legacy input treated as optional, not required", listThings.requiredInputNames.isEmpty());
	}

	private static void catalogOperationDetected() throws Exception
	{
		final String json = "{\"id\":\"Official\",\"operations\":[{\"name\":\"catalog\",\"readOnly\":true,\"requiredInputs\":[],\"optionalInputs\":[]}]}";
		final DeducedScanOperations.Described described = DeducedScanOperationsTest.parse(json);
		DeducedScanOperationsTest.check("a literal 'catalog' operation is detected", described.hasCatalogOperation);
	}

	private static DeducedScanOperations.Described parse(final String pJson) throws Exception
	{
		final JsonNode root = DeducedScanOperationsTest.MAPPER.readTree(pJson);
		final DeducedScanOperations.Described out = new DeducedScanOperations.Described();
		DeducedScanOperations.parseInto(root, out);
		return out;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
