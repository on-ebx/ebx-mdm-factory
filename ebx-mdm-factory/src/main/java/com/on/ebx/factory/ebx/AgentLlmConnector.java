package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;

/**
 * Wave 11 (audit F-26): the ONE class of this module that reads the {@code /root/Connector} and {@code /root/ConnectorProperty} tables of
 * ebx-connectors by path and decrypts a connector's API key ({@link McpServers#secret}).
 * <p>
 * <b>Wave 12: this is now the FALLBACK.</b> The agent loop asks the model through {@code llm.chat} of the Connectors contract 1.1.0 ({@link AgentRoute},
 * {@code ContractLlmChat} of the agent library) and Connectors uses the connection's own client and key: the key is not read. {@link #load} (which decrypts the key
 * with {@link McpServers#secret}) is reached only when that way is not available: {@code ebx.factory.connectors.via=direct}, a Connectors without contract 1.1.0
 * (absent, 1.0.0, another major, switched off) or a connection {@code llm.chat} does not serve (Google) - see {@link AgentRuntime#run}, which logs once which way is
 * taken - and by the direct connectivity test. The same goes for what the contract covers since wave 11 (one-turn questions, the connectivity test, the list of
 * language model connections): they come through here only with {@code via=direct}, an older Connectors, or no session.
 * <p>
 * <b>What would remove it</b>: nothing the module needs is left on it once every deployment runs Connectors 1.1.0 or later; the class, {@link AgentRuntime#settingsFor},
 * {@code ConnectorInfo}/{@code ProviderSettings} and the key decryption in {@link McpServers#secret} (for connectors) can then be deleted - see {@code docs/CONNECTORS-COUPLING.md}.
 * Until then nothing else may read the key: {@code ConnectorsCouplingScanTest} fails when another class does, or when {@link #load} is called from anywhere but the two direct
 * ways of {@link AgentRuntime}.
 */
final class AgentLlmConnector
{
	// where ebx-connectors keeps its own connectors, read as data only: com.on.ebx.connect.core.ConnectorsPaths
	// has the identical two constants, but this module has no compile dependency on ebx-connectors.
	private static final String CONNECTORS_DATASPACE = "Connectors";
	private static final String CONNECTORS_DATASET = "Connectors";
	private static final Path CONNECTOR_TABLE = Path.parse("/root/Connector");
	private static final Path CONNECTOR_PROPERTY_TABLE = Path.parse("/root/ConnectorProperty");
	private static final Path C_LABEL = Path.parse("./label");
	private static final Path C_TYPE = Path.parse("./type");
	private static final Path C_ACTIVE = Path.parse("./active");
	private static final Path C_BASE_URL = Path.parse("./baseUrl");
	private static final Path C_AUTH_TYPE = Path.parse("./authType");
	private static final Path C_API_KEY_HEADER = Path.parse("./apiKeyHeader");
	private static final Path C_API_KEY = Path.parse("./apiKey");
	private static final Path C_CONNECT_TIMEOUT = Path.parse("./connectTimeoutMs");
	private static final Path C_READ_TIMEOUT = Path.parse("./readTimeoutMs");
	private static final Path CP_CONNECTOR = Path.parse("./connector");
	private static final Path CP_KEY = Path.parse("./key");
	private static final Path CP_VALUE = Path.parse("./value");

	private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10000;
	private static final int DEFAULT_READ_TIMEOUT_MS = 120000;

	private AgentLlmConnector()
	{
	}

	/**
	 * (Direct way of {@link AgentRuntime#llmConnectors}.) Every connector of type llm in ebx-connectors, sorted active first then by label, with its provider and model
	 * properties - read as data, like {@link #loadConnector}. Empty when ebx-connectors is not installed. Never
	 * reads nor returns the API key.
	 */
	static List<AgentRuntime.LlmConnectorOption> listDirect(final Repository pRepository)
	{
		final List<AgentRuntime.LlmConnectorOption> out = new ArrayList<AgentRuntime.LlmConnectorOption>();
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(AgentLlmConnector.CONNECTORS_DATASPACE));
		final Adaptation connectors = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(AgentLlmConnector.CONNECTORS_DATASET));
		if (connectors == null)
		{
			return out;
		}
		final Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
		final RequestResult propertyRows = connectors.getTable(AgentLlmConnector.CONNECTOR_PROPERTY_TABLE)
			.createRequestResult("./key='provider' or ./key='model'");
		try
		{
			for (Adaptation propertyRow = propertyRows.nextAdaptation(); propertyRow != null; propertyRow = propertyRows.nextAdaptation())
			{
				final String connector = propertyRow.getString(AgentLlmConnector.CP_CONNECTOR);
				final Object value = propertyRow.get(AgentLlmConnector.CP_VALUE);
				if (connector != null && value != null)
				{
					Map<String, String> map = properties.get(connector);
					if (map == null)
					{
						map = new HashMap<String, String>();
						properties.put(connector, map);
					}
					map.put(propertyRow.getString(AgentLlmConnector.CP_KEY), String.valueOf(value).trim());
				}
			}
		}
		finally
		{
			propertyRows.close();
		}
		final RequestResult rows = connectors.getTable(AgentLlmConnector.CONNECTOR_TABLE).createRequestResult("./type='llm'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String id = row.getOccurrencePrimaryKey().format();
				final Map<String, String> map = properties.containsKey(id) ? properties.get(id) : new HashMap<String, String>();
				out.add(new AgentRuntime.LlmConnectorOption(id, row.getString(AgentLlmConnector.C_LABEL), map.get("provider"), map.get("model"),
					!Boolean.FALSE.equals(row.get(AgentLlmConnector.C_ACTIVE))));
			}
		}
		finally
		{
			rows.close();
		}
		out.sort(new java.util.Comparator<AgentRuntime.LlmConnectorOption>()
		{
			@Override
			public int compare(final AgentRuntime.LlmConnectorOption pA, final AgentRuntime.LlmConnectorOption pB)
			{
				if (pA.active != pB.active)
				{
					return pA.active ? -1 : 1;
				}
				return pA.label.compareToIgnoreCase(pB.label);
			}
		});
		return out;
	}



	/**
	 * Wave 11: the base URL of the connection {@code pConnectorId} - ONLY that column, never the key - for the destination checks
	 * ({@link CredentialPolicy#requireLlmDestination}) of a call that goes through the Connectors contract, whose {@code llm.connectors} does not carry it.
	 * Null when ebx-connectors, the row or the URL is not there.
	 */
	static String baseUrlOf(final Repository pRepository, final String pConnectorId)
	{
		final AdaptationHome home = pRepository == null ? null : pRepository.lookupHome(HomeKey.forBranchName(AgentLlmConnector.CONNECTORS_DATASPACE));
		final Adaptation connectors = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(AgentLlmConnector.CONNECTORS_DATASET));
		if (connectors == null)
		{
			return null;
		}
		final Adaptation row = connectors.getTable(AgentLlmConnector.CONNECTOR_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pConnectorId));
		final String url = row == null ? null : row.getString(AgentLlmConnector.C_BASE_URL);
		return url == null || url.trim().isEmpty() ? null : url.trim();
	}

	/**
	 * Reads the connector row and its properties as data - the module has no compile dependency on
	 * ebx-connectors (same rule {@code LlmTagging} of the data catalog follows). The API key is decrypted (or
	 * dereferenced through an {@code env:}/{@code file:} reference) with {@link McpServers#secret}, shared
	 * rather than duplicated: it never appears in the {@link OperationException} messages this method throws.
	 */
	static AgentRuntime.ConnectorInfo load(final Repository pRepository, final String pConnectorId) throws OperationException
	{
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(AgentLlmConnector.CONNECTORS_DATASPACE));
		final Adaptation connectors = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(AgentLlmConnector.CONNECTORS_DATASET));
		if (connectors == null)
		{
			throw EngineRefusal.llmConnectionUnusable("noConnectors", pConnectorId, "ebx-connectors is not installed: cannot read the language model connector " + pConnectorId);
		}
		final Adaptation row = connectors.getTable(AgentLlmConnector.CONNECTOR_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pConnectorId));
		if (row == null)
		{
			throw EngineRefusal.llmConnectionUnusable("missing", pConnectorId, "no connector '" + pConnectorId + "' in ebx-connectors");
		}
		final String type = String.valueOf(row.getString(AgentLlmConnector.C_TYPE)).toLowerCase(Locale.ENGLISH);
		if (!"llm".equals(type))
		{
			throw EngineRefusal.llmConnectionUnusable("notLlm", pConnectorId, "connector " + pConnectorId + " is of type " + type + ", not llm");
		}
		if (Boolean.FALSE.equals(row.get(AgentLlmConnector.C_ACTIVE)))
		{
			throw EngineRefusal.llmConnectionUnusable("inactive", pConnectorId, "connector " + pConnectorId + " is inactive");
		}

		final Map<String, String> properties = new LinkedHashMap<String, String>();
		final RequestResult propertyRows = connectors.getTable(AgentLlmConnector.CONNECTOR_PROPERTY_TABLE)
			.createRequestResult("./connector=" + CatalogReader.quote(pConnectorId));
		try
		{
			for (Adaptation propertyRow = propertyRows.nextAdaptation(); propertyRow != null; propertyRow = propertyRows.nextAdaptation())
			{
				final String key = propertyRow.getString(AgentLlmConnector.CP_KEY);
				final Object value = propertyRow.get(AgentLlmConnector.CP_VALUE);
				if (key != null && value != null)
				{
					properties.put(key.trim(), String.valueOf(value).trim());
				}
			}
		}
		finally
		{
			propertyRows.close();
		}

		final String authType = String.valueOf(row.getString(AgentLlmConnector.C_AUTH_TYPE)).toUpperCase(Locale.ENGLISH);
		String apiKey = null;
		if (!"NONE".equals(authType))
		{
			McpServers.requireReadableSecret(row.getString(AgentLlmConnector.C_API_KEY));
			apiKey = McpServers.secret(row.getString(AgentLlmConnector.C_API_KEY));
			if (apiKey == null || apiKey.trim().isEmpty() || "TO_BE_SET".equals(apiKey.trim()) || "A_RENSEIGNER".equals(apiKey.trim()))
			{
				throw EngineRefusal.coded("missing.apiKey", "the API key of connector " + pConnectorId + " is not set: enter it in the connector record (Connectors > Connector > " + pConnectorId + ")", pConnectorId);
			}
		}
		return new AgentRuntime.ConnectorInfo(row.getString(AgentLlmConnector.C_BASE_URL), properties.get("provider"), properties.get("model"), authType,
			row.getString(AgentLlmConnector.C_API_KEY_HEADER), apiKey, properties.get("openai.maxTokensParam"), properties.get("azure.apiVersion"),
			properties.get("anthropic.version"), AgentLlmConnector.intProperty(properties.get("maxTokens")),
			AgentLlmConnector.millis(row.get(AgentLlmConnector.C_CONNECT_TIMEOUT), AgentLlmConnector.DEFAULT_CONNECT_TIMEOUT_MS),
			AgentLlmConnector.millis(row.get(AgentLlmConnector.C_READ_TIMEOUT), AgentLlmConnector.DEFAULT_READ_TIMEOUT_MS), properties.get("openai.reasoningEffort"));
	}

	private static Integer intProperty(final String pValue)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return Integer.valueOf((int) Double.parseDouble(pValue.trim()));
		}
		catch (final NumberFormatException ex)
		{
			return null;
		}
	}

	private static int millis(final Object pValue, final int pDefault)
	{
		return pValue instanceof Number && ((Number) pValue).intValue() > 0 ? ((Number) pValue).intValue() : pDefault;
	}
}
