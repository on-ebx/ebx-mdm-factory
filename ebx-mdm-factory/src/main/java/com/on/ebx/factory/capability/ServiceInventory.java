package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure parsing of the {@code ebx-connectors} MCP server's own {@code connectors_list}/{@code
 * connectors_describe} JSON responses into the plain shapes {@code ebx.CapabilityLlmSuggester} needs - same
 * convention as {@link ScriptInventory} and {@code master.MatchPlanProfileReader}: no EBX class anywhere in
 * this file, unit-tested with hand-built fixtures, never against a live server.
 * <p>
 * <b>{@code connectors_list}'s shape was measured live, 2026-09-21</b> (task brief): {@code
 * {"types":[{"type","label","operations":["lei (read)","searchByName (search)","insert (write)", ...]}],
 * "connectors":[{"id","label","type","baseUrl","authType","active","trust","secretsSet"}],"count":105}}.
 * {@link #parseConnectorsList} reads exactly that shape - unchallenged by the coordinator's later capture,
 * still used to enumerate connectors and decide, PER CONNECTOR (never per operation), whether it is worth a
 * {@code connectors_describe} call at all ({@link #hasReadOrSearchOperation}).
 * <p>
 * <b>{@code connectors_describe}'s real shape and real argument contract - captured live by the coordinator,
 * 2026-09-21, for {@code {"connector":"VIES"}}</b>: a BARE object, no {@code success}/{@code data} envelope:
 * {@code {"id":"VIES","label":"VIES (EU VAT check)","type":"vies","typeLabel":"VIES (EU VAT validation)",
 * "baseUrl":"...","authType":"NONE","trust":80,"active":true,"properties":{},"operations":[{"name":"check",
 * "kind":"READ","description":"Validate an EU VAT number (input vat, or countryCode + vatNumber)",
 * "requiredInputs":[],"optionalInputs":[]}]}}. {@link #parseDescribeConnector} reads exactly that shape - no
 * {@code inputs}/{@code outputs}/{@code required} keys exist (an earlier revision of this class guessed a
 * shape with those keys; wrong, replaced entirely).
 * <p>
 * <b>The tool's real parameters are {@code connector} (required), {@code operation} (optional), {@code
 * inputs} (optional) - and passing {@code operation} makes Connectors CALL THE THIRD-PARTY SERVICE</b> (its
 * own tool description: "when an operation is given, [it describes] the fields of the documents it returns
 * or accepts (calls the service's catalog or a sample)"). This suggestion pass must NEVER call a connector on
 * real data (task brief) - {@link #describeRequestArgs} therefore builds the request with {@code connector}
 * ONLY, and is itself unit-tested to prove the built request never carries {@code operation} or {@code
 * inputs} (the guard the coordinator explicitly asked for).
 * <p>
 * <b>Outputs are unknown without calling the service</b> (there is no {@code outputs}/{@code returns} key at
 * all in the real shape) - this class exposes no output-related field; "which output could fill which
 * attribute" is left as free text in a suggestion's rationale, never something a structural guard checks
 * (coordinator, 2026-09-21).
 */
public final class ServiceInventory
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ServiceInventory()
	{
	}

	// ------------------------------------------------------------------------------------------ connectors_list

	public static final class OperationRef
	{
		public final String name;
		/** {@code read}/{@code search}/{@code write} - the word in parentheses of the raw {@code "name (kind)"} entry, as {@code connectors_list} gives it (lower case; {@code connectors_describe}'s own {@code kind} is upper case - see {@link DescribedOperation}). */
		public final String kind;

		public OperationRef(final String pName, final String pKind)
		{
			this.name = pName;
			this.kind = pKind;
		}

		public boolean isCandidateKind()
		{
			return "read".equals(this.kind) || "search".equals(this.kind);
		}
	}

	public static final class TypeRef
	{
		public final String type;
		public final String label;
		public final List<OperationRef> operations;

		public TypeRef(final String pType, final String pLabel, final List<OperationRef> pOperations)
		{
			this.type = pType;
			this.label = pLabel;
			this.operations = pOperations == null ? new ArrayList<OperationRef>() : pOperations;
		}
	}

	/** {@code CapabilitySuggestion.pricing}'s own enumeration - a plain structural tag, never a Java judgment of "cheap"/"expensive" (task brief, service-choice lot, 2026-09-21: "no pricing knowledge in Java, the factory only orders and relays what the data says"). */
	public static final String PRICING_FREE = "FREE";
	public static final String PRICING_FREEMIUM = "FREEMIUM";
	public static final String PRICING_PAID = "PAID";
	public static final String PRICING_UNKNOWN = "UNKNOWN";
	private static final List<String> PRICING_VALUES = java.util.Arrays
		.asList(ServiceInventory.PRICING_FREE, ServiceInventory.PRICING_FREEMIUM, ServiceInventory.PRICING_PAID, ServiceInventory.PRICING_UNKNOWN);

	public static final class ConnectorRef
	{
		public final String id;
		public final String label;
		public final String type;
		public final String authType;
		public final boolean active;
		public final boolean secretsSet;
		public final String trust;
		/** {@code FREE}/{@code FREEMIUM}/{@code PAID}/{@code UNKNOWN} - {@link #PRICING_UNKNOWN} when {@code connectors_list} did not report a {@code pricing} key for this connector at all (an older Connectors build, or a connector this Connectors lot has not tagged yet - see {@link ListResult#pricingReported}). */
		public final String pricing;
		/** Free text alongside {@link #pricing} (e.g. "first 1000 calls/month free") - null when none was given. */
		public final String pricingNote;

		public ConnectorRef(final String pId, final String pLabel, final String pType, final String pAuthType, final boolean pActive, final boolean pSecretsSet,
			final String pTrust)
		{
			this(pId, pLabel, pType, pAuthType, pActive, pSecretsSet, pTrust, ServiceInventory.PRICING_UNKNOWN, null);
		}

		public ConnectorRef(final String pId, final String pLabel, final String pType, final String pAuthType, final boolean pActive, final boolean pSecretsSet,
			final String pTrust, final String pPricing, final String pPricingNote)
		{
			this.id = pId;
			this.label = pLabel;
			this.type = pType;
			this.authType = pAuthType;
			this.active = pActive;
			this.secretsSet = pSecretsSet;
			this.trust = pTrust;
			this.pricing = pPricing == null ? ServiceInventory.PRICING_UNKNOWN : pPricing;
			this.pricingNote = pPricingNote;
		}

		/** {@code secretsSet:false} with an authType that needs one means "not ready to use" - task brief: never hide it, only say so. */
		public boolean isReady()
		{
			return this.secretsSet || this.authType == null || "NONE".equalsIgnoreCase(this.authType);
		}
	}

	public static final class ListResult
	{
		public final List<TypeRef> types;
		public final List<ConnectorRef> connectors;
		/** True when at least one connector in this response carried a {@code pricing} key at all - false means this Connectors build predates pricing entirely, so EVERY connector reads {@code UNKNOWN} for a reason that has nothing to do with that specific connector (service-choice lot, 2026-09-21: "parse them as OPTIONAL ... the response says pricing is not available from this Connectors build"). */
		public final boolean pricingReported;

		ListResult(final List<TypeRef> pTypes, final List<ConnectorRef> pConnectors, final boolean pPricingReported)
		{
			this.types = pTypes;
			this.connectors = pConnectors;
			this.pricingReported = pPricingReported;
		}
	}

	/** {@code "searchByName (search)"} -&gt; name {@code "searchByName"}, kind {@code "search"}. A malformed entry (no parenthesised kind) is kept with a null kind - never a candidate ({@link OperationRef#isCandidateKind}). */
	private static final Pattern OPERATION_ENTRY = Pattern.compile("^(.*?)\\s*\\(([A-Za-z]+)\\)\\s*$");

	public static ListResult parseConnectorsList(final JsonNode pResponseJson)
	{
		final List<TypeRef> types = new ArrayList<TypeRef>();
		for (final JsonNode typeNode : pResponseJson.path("types"))
		{
			final String type = typeNode.path("type").asText(null);
			if (type == null)
			{
				continue;
			}
			final List<OperationRef> operations = new ArrayList<OperationRef>();
			for (final JsonNode operationNode : typeNode.path("operations"))
			{
				final String raw = operationNode.asText(null);
				if (raw == null || raw.trim().isEmpty())
				{
					continue;
				}
				final Matcher matcher = ServiceInventory.OPERATION_ENTRY.matcher(raw.trim());
				if (matcher.matches())
				{
					operations.add(new OperationRef(matcher.group(1).trim(), matcher.group(2).trim().toLowerCase(java.util.Locale.ROOT)));
				}
				else
				{
					operations.add(new OperationRef(raw.trim(), null));
				}
			}
			types.add(new TypeRef(type, typeNode.path("label").asText(type), operations));
		}
		final List<ConnectorRef> connectors = new ArrayList<ConnectorRef>();
		boolean pricingReported = false;
		for (final JsonNode connectorNode : pResponseJson.path("connectors"))
		{
			final String id = connectorNode.path("id").asText(null);
			if (id == null)
			{
				continue;
			}
			if (connectorNode.has("pricing"))
			{
				pricingReported = true;
			}
			connectors.add(new ConnectorRef(id, connectorNode.path("label").asText(id), connectorNode.path("type").asText(null),
				connectorNode.path("authType").asText(null), connectorNode.path("active").asBoolean(true), connectorNode.path("secretsSet").asBoolean(false),
				connectorNode.path("trust").asText(null), ServiceInventory.normalizePricing(connectorNode.path("pricing").asText(null)),
				connectorNode.path("pricingNote").asText(null)));
		}
		return new ListResult(types, connectors, pricingReported);
	}

	/** {@code pRaw} upper-cased when it is one of {@link #PRICING_VALUES}, {@link #PRICING_UNKNOWN} otherwise (missing, blank, or a value this build does not recognize - never a hard error over a forward-compatible new tag). */
	private static String normalizePricing(final String pRaw)
	{
		if (pRaw == null || pRaw.trim().isEmpty())
		{
			return ServiceInventory.PRICING_UNKNOWN;
		}
		final String upper = pRaw.trim().toUpperCase(java.util.Locale.ROOT);
		return ServiceInventory.PRICING_VALUES.contains(upper) ? upper : ServiceInventory.PRICING_UNKNOWN;
	}

	/** Whether {@code pConnector}'s TYPE has at least one {@code read}/{@code search} operation per {@code connectors_list} - the pre-filter that decides whether a connector is worth a {@code connectors_describe} call at all (coordinator: "only for connectors that have at least one READ/SEARCH operation according to connectors_list"). A connector whose type is not found in {@code pList.types} is never worth describing (nothing to check it against). */
	public static boolean hasReadOrSearchOperation(final ConnectorRef pConnector, final ListResult pList)
	{
		return !ServiceInventory.readSearchOperationNames(pConnector, pList).isEmpty();
	}

	/**
	 * The {@code read}/{@code search} operation names of {@code pConnector}'s TYPE, per {@code
	 * connectors_list} only (never a {@code connectors_describe} call) - what the compact catalogue's own
	 * connector line shows (coordinator, 2026-09-21, item 1: "one line per CONNECTOR ... its read/search
	 * operation names"). Empty when the type is not found or has no read/search operation.
	 */
	public static List<String> readSearchOperationNames(final ConnectorRef pConnector, final ListResult pList)
	{
		final List<String> out = new ArrayList<String>();
		final TypeRef type = ServiceInventory.typeOf(pList, pConnector.type);
		if (type == null)
		{
			return out;
		}
		for (final OperationRef operation : type.operations)
		{
			if (operation.isCandidateKind())
			{
				out.add(operation.name);
			}
		}
		return out;
	}

	private static TypeRef typeOf(final ListResult pList, final String pType)
	{
		if (pType == null)
		{
			return null;
		}
		for (final TypeRef type : pList.types)
		{
			if (pType.equals(type.type))
			{
				return type;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------- connectors_describe

	/**
	 * The request body for {@code connectors_describe} of exactly ONE connector - {@code {"connector":
	 * pConnectorId}}, nothing else. Deliberately its own small, pure, unit-tested function (rather than built
	 * inline by the caller): the coordinator's own required test is "fails if the arguments sent to
	 * connectors_describe ever contain operation or inputs" - asserting on THIS method's output is how that
	 * is checked without needing a live MCP call.
	 */
	public static ObjectNode describeRequestArgs(final String pConnectorId)
	{
		final ObjectNode args = ServiceInventory.MAPPER.createObjectNode();
		args.put("connector", pConnectorId);
		return args;
	}

	/** One operation of a described connector - {@code kind} is UPPER CASE here ({@code connectors_describe}'s own convention), unlike {@link OperationRef#kind} (lower case, from {@code connectors_list}). */
	public static final class DescribedOperation
	{
		public final String name;
		public final String kind;
		public final String description;
		public final List<String> requiredInputs;
		public final List<String> optionalInputs;

		public DescribedOperation(final String pName, final String pKind, final String pDescription, final List<String> pRequiredInputs,
			final List<String> pOptionalInputs)
		{
			this.name = pName;
			this.kind = pKind;
			this.description = pDescription;
			this.requiredInputs = pRequiredInputs == null ? new ArrayList<String>() : pRequiredInputs;
			this.optionalInputs = pOptionalInputs == null ? new ArrayList<String>() : pOptionalInputs;
		}

		public boolean isCandidateKind()
		{
			return "READ".equalsIgnoreCase(this.kind) || "SEARCH".equalsIgnoreCase(this.kind);
		}
	}

	public static final class ConnectorDescription
	{
		public final String id;
		public final String label;
		public final String type;
		public final boolean active;
		public final List<DescribedOperation> operations;

		ConnectorDescription(final String pId, final String pLabel, final String pType, final boolean pActive, final List<DescribedOperation> pOperations)
		{
			this.id = pId;
			this.label = pLabel;
			this.type = pType;
			this.active = pActive;
			this.operations = pOperations == null ? new ArrayList<DescribedOperation>() : pOperations;
		}
	}

	/** Reads the BARE object {@code connectors_describe} answers (no {@code success}/{@code data} envelope - see class Javadoc) directly at the top level. */
	public static ConnectorDescription parseDescribeConnector(final JsonNode pResponseJson)
	{
		final List<DescribedOperation> operations = new ArrayList<DescribedOperation>();
		for (final JsonNode operationNode : pResponseJson.path("operations"))
		{
			final String name = operationNode.path("name").asText(null);
			if (name == null)
			{
				continue;
			}
			operations.add(new DescribedOperation(name, operationNode.path("kind").asText(null), operationNode.path("description").asText(null),
				ServiceInventory.textArray(operationNode.path("requiredInputs")), ServiceInventory.textArray(operationNode.path("optionalInputs"))));
		}
		return new ConnectorDescription(pResponseJson.path("id").asText(null), pResponseJson.path("label").asText(null), pResponseJson.path("type").asText(null),
			pResponseJson.path("active").asBoolean(true), operations);
	}

	private static List<String> textArray(final JsonNode pArrayNode)
	{
		final List<String> out = new ArrayList<String>();
		for (final JsonNode item : pArrayNode)
		{
			if (item.isTextual())
			{
				out.add(item.asText());
			}
		}
		return out;
	}
}
