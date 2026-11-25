package com.on.ebx.factory.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Fixtures reproduce the
 * {@code connectors_list}/{@code connectors_describe} shapes captured live (task brief 2026-09-21 for {@code
 * connectors_list}; coordinator, same day, for {@code connectors_describe} against a real connector, VIES -
 * domain-neutral by construction: a VAT-number validation service, the coordinator's own worked example,
 * never party/company-specific logic in this file).
 */
public final class ServiceInventoryTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		ServiceInventoryTest.parseConnectorsListRealShape();
		ServiceInventoryTest.hasReadOrSearchOperationTrueFalse();
		ServiceInventoryTest.readinessFollowsSecretsSetAndAuthType();
		ServiceInventoryTest.describeRequestArgsIsConnectorOnly();
		ServiceInventoryTest.parseDescribeConnectorRealShape();
		ServiceInventoryTest.pricingAbsentMeansUnknownAndNotReported();
		ServiceInventoryTest.pricingPresentIsParsedAndStored();
		ServiceInventoryTest.pricingUnrecognizedTagDegradesToUnknown();
		System.out.println("ServiceInventoryTest OK");
	}

	// ---------------------------------------------------------------------------------------------- connectors_list

	/** The exact shape given in the task brief: {@code {"types":[{"type","label","operations":["lei (read)","searchByName (search)","insert (write)"]}],"connectors":[...],"count":105}}. */
	private static void parseConnectorsListRealShape() throws Exception
	{
		final String json = "{\"types\":[{\"type\":\"registry\",\"label\":\"Company Registry\",\"operations\":[\"lei (read)\",\"searchByName (search)\",\"insert (write)\"]}],"
			+ "\"connectors\":[{\"id\":\"REGISTRY\",\"label\":\"Registry\",\"type\":\"registry\",\"baseUrl\":\"https://registry.example\",\"authType\":\"API_KEY_HEADER\","
			+ "\"active\":true,\"trust\":\"HIGH\",\"secretsSet\":false}],\"count\":1}";
		final JsonNode node = ServiceInventoryTest.MAPPER.readTree(json);
		final ServiceInventory.ListResult result = ServiceInventory.parseConnectorsList(node);
		ServiceInventoryTest.check("one type parsed", result.types.size() == 1);
		ServiceInventoryTest.check("three operations parsed", result.types.get(0).operations.size() == 3);
		ServiceInventoryTest.check("kind parsed from the parenthesised word", "read".equals(result.types.get(0).operations.get(0).kind)
			&& "search".equals(result.types.get(0).operations.get(1).kind) && "write".equals(result.types.get(0).operations.get(2).kind));
		ServiceInventoryTest.check("operation name excludes the parenthesised kind", "lei".equals(result.types.get(0).operations.get(0).name));
		ServiceInventoryTest.check("one connector parsed", result.connectors.size() == 1);
		ServiceInventoryTest.check("secretsSet carried", !result.connectors.get(0).secretsSet);
	}

	private static void hasReadOrSearchOperationTrueFalse() throws Exception
	{
		final ServiceInventory.ListResult withReadSearch = ServiceInventoryTest.fixtureList(true, true, "API_KEY_HEADER");
		ServiceInventoryTest.check("the registry connector's type has read/search operations",
			ServiceInventory.hasReadOrSearchOperation(withReadSearch.connectors.get(0), withReadSearch));

		final String writeOnlyJson = "{\"types\":[{\"type\":\"sink\",\"label\":\"Sink\",\"operations\":[\"insert (write)\"]}],"
			+ "\"connectors\":[{\"id\":\"SINK\",\"label\":\"Sink\",\"type\":\"sink\",\"authType\":\"NONE\",\"active\":true,\"secretsSet\":true}],\"count\":1}";
		final ServiceInventory.ListResult writeOnly = ServiceInventory.parseConnectorsList(ServiceInventoryTest.MAPPER.readTree(writeOnlyJson));
		ServiceInventoryTest.check("a write-only connector never qualifies for describe", !ServiceInventory.hasReadOrSearchOperation(writeOnly.connectors.get(0), writeOnly));
	}

	private static void readinessFollowsSecretsSetAndAuthType()
	{
		final ServiceInventory.ConnectorRef readyBySecrets = new ServiceInventory.ConnectorRef("A", "A", "registry", "API_KEY_HEADER", true, true, "HIGH");
		ServiceInventoryTest.check("ready when secrets are set", readyBySecrets.isReady());
		final ServiceInventory.ConnectorRef notReady = new ServiceInventory.ConnectorRef("B", "B", "registry", "API_KEY_HEADER", true, false, "HIGH");
		ServiceInventoryTest.check("not ready when secrets are needed but not set", !notReady.isReady());
		final ServiceInventory.ConnectorRef noneNeeded = new ServiceInventory.ConnectorRef("C", "C", "registry", "NONE", true, false, "HIGH");
		ServiceInventoryTest.check("ready when authType NONE, whatever secretsSet says", noneNeeded.isReady());
	}

	// ------------------------------------------------------------------------------------------- connectors_describe

	/**
	 * Coordinator, 2026-09-21, BLOCKING: passing {@code operation} to {@code connectors_describe} makes
	 * Connectors CALL THE THIRD-PARTY SERVICE - this suggestion pass must never do that. This test fails if
	 * the built request ever carries {@code operation} or {@code inputs}, exactly the check the coordinator
	 * asked for.
	 */
	private static void describeRequestArgsIsConnectorOnly()
	{
		final ObjectNode args = ServiceInventory.describeRequestArgs("VIES");
		ServiceInventoryTest.check("exactly one field: 'connector'", args.size() == 1 && args.has("connector"));
		ServiceInventoryTest.check("'connector' carries the given id", "VIES".equals(args.path("connector").asText()));
		ServiceInventoryTest.check("never carries 'operation'", !args.has("operation"));
		ServiceInventoryTest.check("never carries 'inputs'", !args.has("inputs"));
	}

	/**
	 * The real shape captured live by the coordinator, 2026-09-21, for {@code connectors_describe({"connector":
	 * "VIES"})}: a BARE object (no {@code success}/{@code data} envelope), {@code kind} upper case, no {@code
	 * inputs}/{@code outputs}/{@code required} keys anywhere.
	 */
	private static void parseDescribeConnectorRealShape() throws Exception
	{
		final String json = "{\"id\":\"VIES\",\"label\":\"VIES (EU VAT check)\",\"type\":\"vies\",\"typeLabel\":\"VIES (EU VAT validation)\","
			+ "\"baseUrl\":\"https://ec.europa.eu\",\"authType\":\"NONE\",\"trust\":80,\"active\":true,\"properties\":{},"
			+ "\"operations\":[{\"name\":\"check\",\"kind\":\"READ\",\"description\":\"Validate an EU VAT number (input vat, or countryCode + vatNumber)\","
			+ "\"requiredInputs\":[],\"optionalInputs\":[]}]}";
		final ServiceInventory.ConnectorDescription description = ServiceInventory.parseDescribeConnector(ServiceInventoryTest.MAPPER.readTree(json));
		ServiceInventoryTest.check("id parsed", "VIES".equals(description.id));
		ServiceInventoryTest.check("one operation parsed", description.operations.size() == 1);
		final ServiceInventory.DescribedOperation operation = description.operations.get(0);
		ServiceInventoryTest.check("name", "check".equals(operation.name));
		ServiceInventoryTest.check("kind is upper case READ, a candidate", "READ".equals(operation.kind) && operation.isCandidateKind());
		ServiceInventoryTest.check("description reaches the caller (must reach the LLM)", operation.description != null && operation.description.contains("Validate an EU VAT number"));
		ServiceInventoryTest.check("requiredInputs is an empty list, not null/unknown - the description is the only place inputs are documented here",
			operation.requiredInputs.isEmpty());
		ServiceInventoryTest.check("optionalInputs is an empty list too", operation.optionalInputs.isEmpty());
	}

	// ------------------------------------------------------------------------------------------------------- pricing

	/**
	 * Service-choice lot (coordinator, 2026-09-21, item 3), required test: "pricing absent => UNKNOWN + note in
	 * response". An older Connectors build's {@code connectors_list} - no {@code pricing} key on any connector
	 * at all - must never fail to parse: every connector reads {@link ServiceInventory#PRICING_UNKNOWN}, and
	 * {@link ServiceInventory.ListResult#pricingReported} is false so the caller ({@code
	 * ebx.CapabilityLlmSuggester.buildServiceCatalogue}) can add its own one-line explanation to the response
	 * (not itself re-tested here - that line is a two-statement EBX-bound conditional, reviewed by hand;
	 * everything it depends on is proven pure, here).
	 */
	private static void pricingAbsentMeansUnknownAndNotReported() throws Exception
	{
		final String json = "{\"types\":[{\"type\":\"registry\",\"label\":\"Company Registry\",\"operations\":[\"lookup (read)\"]}],"
			+ "\"connectors\":[{\"id\":\"REGISTRY\",\"label\":\"Registry\",\"type\":\"registry\",\"authType\":\"NONE\",\"active\":true,\"secretsSet\":true}],\"count\":1}";
		final ServiceInventory.ListResult result = ServiceInventory.parseConnectorsList(ServiceInventoryTest.MAPPER.readTree(json));
		ServiceInventoryTest.check("pricingReported is false: no connector carried a 'pricing' key", !result.pricingReported);
		ServiceInventoryTest.check("the connector itself still reads UNKNOWN, never null or an exception",
			ServiceInventory.PRICING_UNKNOWN.equals(result.connectors.get(0).pricing));
		ServiceInventoryTest.check("pricingNote is null, not an empty string", result.connectors.get(0).pricingNote == null);
	}

	/** "pricing present => stored" (coordinator, required test): a connector that DOES carry pricing/pricingNote is parsed as given, and pricingReported becomes true for the whole response. */
	private static void pricingPresentIsParsedAndStored() throws Exception
	{
		final String json = "{\"types\":[{\"type\":\"melissa\",\"label\":\"Melissa\",\"operations\":[\"verify (read)\"]}],"
			+ "\"connectors\":[{\"id\":\"MELISSA\",\"label\":\"Melissa\",\"type\":\"melissa\",\"authType\":\"API_KEY_HEADER\",\"active\":true,\"secretsSet\":false,"
			+ "\"pricing\":\"paid\",\"pricingNote\":\"per-call billing\"}],\"count\":1}";
		final ServiceInventory.ListResult result = ServiceInventory.parseConnectorsList(ServiceInventoryTest.MAPPER.readTree(json));
		ServiceInventoryTest.check("pricingReported is true: at least one connector carried the key", result.pricingReported);
		ServiceInventoryTest.check("pricing normalised to upper case", ServiceInventory.PRICING_PAID.equals(result.connectors.get(0).pricing));
		ServiceInventoryTest.check("pricingNote carried as given", "per-call billing".equals(result.connectors.get(0).pricingNote));
	}

	/** A pricing tag this build does not recognize (a typo, or a future tag not yet known here) degrades to UNKNOWN rather than being stored verbatim or throwing - the enum is closed on the read side. */
	private static void pricingUnrecognizedTagDegradesToUnknown() throws Exception
	{
		final String json = "{\"types\":[{\"type\":\"registry\",\"label\":\"Registry\",\"operations\":[\"lookup (read)\"]}],"
			+ "\"connectors\":[{\"id\":\"REGISTRY\",\"label\":\"Registry\",\"type\":\"registry\",\"authType\":\"NONE\",\"active\":true,\"secretsSet\":true,"
			+ "\"pricing\":\"SUBSCRIPTION\"}],\"count\":1}";
		final ServiceInventory.ListResult result = ServiceInventory.parseConnectorsList(ServiceInventoryTest.MAPPER.readTree(json));
		ServiceInventoryTest.check("still reported (the key was present) even though the value itself was not recognised", result.pricingReported);
		ServiceInventoryTest.check("unrecognised tag degrades to UNKNOWN, not stored verbatim", ServiceInventory.PRICING_UNKNOWN.equals(result.connectors.get(0).pricing));
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	private static ServiceInventory.ListResult fixtureList(final boolean pActive, final boolean pSecretsSet, final String pAuthType) throws Exception
	{
		final String json = "{\"types\":[{\"type\":\"registry\",\"label\":\"Company Registry\",\"operations\":[\"lei (read)\",\"searchByName (search)\",\"insert (write)\"]}],"
			+ "\"connectors\":[{\"id\":\"REGISTRY\",\"label\":\"Registry\",\"type\":\"registry\",\"baseUrl\":\"https://registry.example\",\"authType\":\"" + pAuthType
			+ "\",\"active\":" + pActive + ",\"trust\":\"HIGH\",\"secretsSet\":" + pSecretsSet + "}],\"count\":1}";
		return ServiceInventory.parseConnectorsList(ServiceInventoryTest.MAPPER.readTree(json));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
