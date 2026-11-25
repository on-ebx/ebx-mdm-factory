package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.capability.ServiceInventory;
import com.on.ebx.factory.core.CatalogRightMessages;
import com.on.ebx.factory.deducedscan.OperationCatalog;
import com.on.ebx.factory.landing.LandingFlow;
import com.on.ebx.factory.landing.LoadDeadline;
import com.on.ebx.factory.wizard.AddApplicationOptions;
import com.on.ebx.factory.wizard.SourceOnboarding;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Live side of the "Create a catalog source" onboarding chain (source-onboarding lot, 2026-09-23; the pure
 * rule is {@link SourceOnboarding}, another agent's task - see its own class Javadoc for the five steps this
 * gathers facts for: connection -&gt; catalog operation -&gt; catalog source -&gt; scan flows -&gt; scan -&gt;
 * ready). Every method here degrades into {@link SourceOnboarding.Facts}'s own {@code *Error} fields instead
 * of throwing for a refused/failed MCP call - the one exception is {@link #connections}, whose own listing
 * failure the wizard shows as a panel-level notice (task brief), and the two write methods'
 * {@link #preflight} gate, which IS meant to stop the caller before any side effect (same convention as
 * {@code LandingLoadTaskBean}/{@code FactoryResource#preflight}).
 * <p>
 * No {@code PipelineStep} row is written by any method here: onboarding a connection into the catalog is not
 * a project pass (it happens before a project source even exists), so nothing here touches
 * {@link PipelineSteps} - only {@code kernel.log}, the same convention {@link LandingFlows} already follows
 * for its own tool-call detail.
 */
public final class SourceOnboarder
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Tables one catalog scan may import ({@code connectors_sync}'s {@code max} on the scan feed; audit C-35, wave 10). */
	static final int SCAN_MAX_TABLES = 10000;
	/** F2-05: milliseconds between two {@code connectors_run_get} while a scan or a sample run is followed. */
	static final long SCAN_POLL_MS = 5000L;
	private static final String LOG_PREFIX = "[mdm-factory] source onboarding: ";

	private SourceOnboarder()
	{
	}

	// ================================================================================================ connections

	/**
	 * Every Connectors connection ({@code connectors_list}), for the wizard's step-2 onboarding combobox:
	 * whether its TYPE offers a {@code catalog} operation, and the catalog {@code Source} id already naming it
	 * (a cross-reference against {@link CatalogReader#sources}, matched on {@code Source.connector}), if any.
	 * Sorted catalog-capable-and-not-yet-in-the-catalog first (the connections actually worth onboarding), then
	 * everything else, label order within each group - task brief.
	 *
	 * @throws OperationException the {@code ebx-connectors} server is not usable, or {@code connectors_list}
	 *         itself was refused/failed - a listing failure is shown in the panel, never left as a blank list
	 *         (task brief), unlike every per-connector failure below.
	 */
	public static List<SourceOnboarding.ConnectionOption> connections(final Session pSession, final Adaptation pFactory) throws OperationException
	{
		return SourceOnboarder.connections(pSession, pFactory, false);
	}

	/**
	 * Same; {@code pWithTableCounts} also counts, for each connection that already is a catalog source, how many of its tables hold a scanned column
	 * ({@link SourceOnboarding.ConnectionOption#scannedTables}) - what the wizard's single "Add an application" list shows as "scanned, N tables" (UX lot U2, 2026-10-13).
	 */
	public static List<SourceOnboarding.ConnectionOption> connections(final Session pSession, final Adaptation pFactory, final boolean pWithTableCounts) throws OperationException
	{
		return SourceOnboarder.connections(pSession, pFactory, CatalogReader.Reader.of(pSession), pWithTableCounts);
	}

	/** Same, the catalog read as {@code pReader} (audit F-02: the person's reader, whose {@code restricted()} the caller then reports). */
	public static List<SourceOnboarding.ConnectionOption> connections(final Session pSession, final Adaptation pFactory, final CatalogReader.Reader pReader)
		throws OperationException
	{
		return SourceOnboarder.connections(pSession, pFactory, pReader, false);
	}

	private static List<SourceOnboarding.ConnectionOption> connections(final Session pSession, final Adaptation pFactory, final CatalogReader.Reader pReader,
		final boolean pWithTableCounts) throws OperationException
	{
		final McpClient client = McpServers.connectors(pSession, pFactory);
		final ServiceInventory.ListResult listResult;
		try
		{
			final McpClient.ToolResult result = client.callTool("connectors_list", SourceOnboarder.MAPPER.createObjectNode());
			if (result.error || result.json == null)
			{
				throw EngineRefusal.coded("external.listConnections", "connectors_list failed: " + SourceOnboarder.shortText(result.text));
			}
			listResult = ServiceInventory.parseConnectorsList(result.json);
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.codedWithCause("external.listConnections", "connectors_list MCP call failed: " + ex.getMessage(), ex);
		}

		final Map<String, String> catalogSourceByConnector = new HashMap<String, String>();
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		if (catalog != null)
		{
			// audit F-02 (catalog reads): which connection is already a catalog source is shown in the combobox: read as the person
			for (final CatalogReader.Source source : CatalogReader.sources(pReader, catalog))
			{
				if (source.connector != null && !source.connector.trim().isEmpty())
				{
					catalogSourceByConnector.put(source.connector, source.id);
				}
			}
		}

		final List<SourceOnboarding.ConnectionOption> out = new ArrayList<SourceOnboarding.ConnectionOption>();
		for (final ServiceInventory.ConnectorRef connector : listResult.connectors)
		{
			final boolean catalogCapable = SourceOnboarder.catalogCapable(connector, listResult);
			out.add(new SourceOnboarding.ConnectionOption(connector.id, connector.label, connector.type, connector.active, catalogCapable,
				catalogSourceByConnector.get(connector.id)));
		}
		Collections.sort(out, new Comparator<SourceOnboarding.ConnectionOption>()
		{
			@Override
			public int compare(final SourceOnboarding.ConnectionOption pLeft, final SourceOnboarding.ConnectionOption pRight)
			{
				final boolean leftWorthOnboarding = pLeft.catalogCapable && pLeft.catalogSourceId.isEmpty();
				final boolean rightWorthOnboarding = pRight.catalogCapable && pRight.catalogSourceId.isEmpty();
				if (leftWorthOnboarding != rightWorthOnboarding)
				{
					return leftWorthOnboarding ? -1 : 1;
				}
				return pLeft.label.compareToIgnoreCase(pRight.label);
			}
		});
		if (pWithTableCounts && catalog != null)
		{
			for (final SourceOnboarding.ConnectionOption option : out)
			{
				if (!option.catalogSourceId.isEmpty())
				{
					int withColumns = 0;
					for (final com.on.ebx.factory.wizard.UnplannedAsset asset : CatalogReader.assetBriefs(pReader, catalog, option.catalogSourceId))
					{
						if (asset.hasColumns)
						{
							withColumns++;
						}
					}
					option.scannedTables = withColumns;
				}
			}
		}
		if (pWithTableCounts)
		{
			SourceOnboarder.readabilityInto(pSession, pFactory, out, listResult);
		}
		return out;
	}

	/**
	 * UX lot C1 (2026-10-13): for every active connection with no catalog operation, whether it can still be read for its tables (the deduced scan),
	 * {@link AddApplicationOptions#unreadableReason}. The type's operations come from the listing already in hand; a type that lists none (a REST connection
	 * keeps its operations per connection) is described, only when nothing else decides the row: no catalog source holding tables yet.
	 */
	private static void readabilityInto(final Session pSession, final Adaptation pFactory, final List<SourceOnboarding.ConnectionOption> pOptions,
		final ServiceInventory.ListResult pList)
	{
		for (final SourceOnboarding.ConnectionOption option : pOptions)
		{
			if (option.catalogCapable || !option.active || AddApplicationOptions.neverASource(option.type) || !option.catalogSourceId.isEmpty() && option.scannedTables > 0)
			{
				continue; // the row is decided without it: a catalog operation, switched off, or tables already found from the data
			}
			ServiceInventory.TypeRef type = null;
			for (final ServiceInventory.TypeRef candidate : pList.types)
			{
				if (candidate.type.equals(option.type))
				{
					type = candidate;
				}
			}
			final boolean lists = type != null && !type.operations.isEmpty();
			boolean hasRead = false;
			if (lists)
			{
				for (final ServiceInventory.OperationRef operation : type.operations)
				{
					if ("read".equals(operation.kind))
					{
						hasRead = true;
					}
				}
			}
			String error = null;
			boolean described = false;
			if (!lists)
			{
				final DeducedScanOperations.Described answer = DeducedScanOperations.describe(pSession, pFactory, option.id);
				error = answer.error;
				described = !OperationCatalog.hasNoReadOnlyOperation(OperationCatalog.readOnlyOperations(answer.operations));
			}
			option.unreadableReason = AddApplicationOptions.unreadableReason(lists, hasRead, error, described);
			option.readable = option.unreadableReason.isEmpty();
		}
	}

	/** Whether {@code pConnector}'s TYPE advertises an operation literally named {@code "catalog"} - not just any read/search operation ({@code ServiceInventory.readSearchOperationNames}' own contract), since a connector can offer {@code read}/{@code list} without {@code catalog} (measured on SourceBidon, task brief). */
	private static boolean catalogCapable(final ServiceInventory.ConnectorRef pConnector, final ServiceInventory.ListResult pList)
	{
		for (final ServiceInventory.TypeRef type : pList.types)
		{
			if (!type.type.equals(pConnector.type))
			{
				continue;
			}
			for (final ServiceInventory.OperationRef operation : type.operations)
			{
				if ("catalog".equals(operation.name))
				{
					return true;
				}
			}
		}
		return false;
	}

	// ===================================================================================================== facts

	/**
	 * Everything {@link SourceOnboarding#diagnose} needs for {@code pConnectorId}, gathered as far as reachable
	 * and never throwing - a refused/failed call at any step lands in the matching {@code Facts} field
	 * ({@code connectorError}/{@code planError}) and stops that branch of the read (later steps stay at their
	 * Java default, which {@link SourceOnboarding#diagnose} reads as WAITING, exactly like a step that was
	 * never reached because an earlier one is not DONE - see that class's own Javadoc). Deliberately mirrors
	 * {@link SourceOnboarding#diagnose}'s own step gating: no point reading the catalog-source plan for an
	 * inactive/non-catalog connection, and no point reading scan flows/scan/source for anything but a LINKED
	 * plan (CREATE/SET_CONNECTOR/SET_NAMESPACE/MANUAL all mean no {@code Source} row exists yet to query).
	 */
	public static SourceOnboarding.Facts facts(final Session pSession, final Adaptation pFactory, final String pConnectorId)
	{
		final SourceOnboarding.Facts facts = SourceOnboarder.readFacts(pSession, pFactory, pConnectorId);
		// wave 11 (audit F-48): every live read is kept under the user who made it. Facts carry what THAT session may see of the catalog (sourceInto reads it as the
		// session's person), so a display only ever reads facts its own viewer read: what the job's system session read never reaches a screen
		SourceOnboarder.remember(SourceOnboarder.viewerId(pSession), pConnectorId, facts);
		return facts;
	}

	/** The user a cached read belongs to: the session's user id, {@code ""} for a session without one (such a read is then shared only with other such sessions, i.e. none of a person's). */
	public static String viewerId(final Session pSession)
	{
		try
		{
			return pSession == null || pSession.getUserReference() == null ? "" : pSession.getUserReference().getUserId();
		}
		catch (final RuntimeException ex)
		{
			return "";
		}
	}

	/** One cache entry per (user, connection): a read of one user is never an answer for another. */
	static String cacheKey(final String pViewerId, final String pConnectorId)
	{
		return (pViewerId == null ? "" : pViewerId) + "\u0000" + pConnectorId;
	}

	/** Stores {@code pFacts} as read by {@code pViewerId} - what {@link #facts} does after every live read (package-visible for the offline test). */
	static void remember(final String pViewerId, final String pConnectorId, final SourceOnboarding.Facts pFacts)
	{
		if (pConnectorId != null && !pConnectorId.isEmpty())
		{
			SourceOnboarder.CACHE.put(SourceOnboarder.cacheKey(pViewerId, pConnectorId), pFacts);
		}
	}

	// ----------------------------------------------------------------------------------------- cached read (wave 11, audit F-48)

	private static final SourceFactsCache CACHE = new SourceFactsCache(new SourceFactsCache.Clock()
	{
		@Override
		public long now()
		{
			return System.currentTimeMillis();
		}
	});

	/** How old the facts of a connection may be, while its onboarding job is queued or running, before a display asks for them again (the job's own reads keep them fresh anyway). */
	public static final long CACHE_RUNNING_MAX_AGE_MS = 15000L;

	/** Same, once the job ended: what a finished connection reads does not change until a person acts, and a person's action reads it live and stores it. */
	public static final long CACHE_SETTLED_MAX_AGE_MS = 300000L;

	/** What {@link #cachedFacts} found: the facts and whether they are still within the age a display accepts without asking again. */
	public static final class Cached
	{
		public final SourceOnboarding.Facts facts;
		public final boolean fresh;

		Cached(final SourceOnboarding.Facts pFacts, final boolean pFresh)
		{
			this.facts = pFacts;
			this.fresh = pFresh;
		}
	}

	/**
	 * The last facts {@code pViewerId} (see {@link #viewerId}) read for {@code pConnectorId}, without any call - never another user's, which may have seen more of the catalog
	 * than this one's rights allow: {@code null} when this user read none since the module started. {@code fresh} is false once
	 * they are older than {@code pMaxAgeMillis} - the caller then shows them all the same (they are only a little stale) and asks {@link #refreshInBackground}.
	 * This is what the wizard's render path reads: it never calls Connectors (audit F-48).
	 */
	public static Cached cachedFacts(final String pViewerId, final String pConnectorId, final long pMaxAgeMillis)
	{
		final SourceFactsCache.Entry entry = SourceOnboarder.CACHE.get(SourceOnboarder.cacheKey(pViewerId, pConnectorId));
		return entry == null ? null : new Cached(entry.facts, SourceOnboarder.CACHE.fresh(entry, pMaxAgeMillis));
	}

	private static final java.util.Set<String> REFRESHING = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

	/** One thread, a short queue, threads that die when idle (so the pool never pins the module's class loader across a reload). */
	private static final java.util.concurrent.ThreadPoolExecutor REFRESHER = SourceOnboarder.newRefresher();

	private static java.util.concurrent.ThreadPoolExecutor newRefresher()
	{
		final java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(1, 1, 20L, java.util.concurrent.TimeUnit.SECONDS,
			new java.util.concurrent.ArrayBlockingQueue<Runnable>(16), new java.util.concurrent.ThreadFactory()
			{
				@Override
				public Thread newThread(final Runnable pTask)
				{
					final Thread thread = new Thread(pTask, "mdm-factory-source-facts");
					thread.setDaemon(true);
					// the web application's class loader, not the one of whichever thread asks first (CLAUDE.md sec 1: a pool thread's loader is not the module's)
					thread.setContextClassLoader(SourceOnboarder.class.getClassLoader());
					return thread;
				}
			}, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}

	/**
	 * Reads {@link #facts} for {@code pConnectorId} on a background thread, as {@code pSession}, and stores the result for {@link #cachedFacts}. At most one read
	 * of a connection is queued or running at a time, and a full queue refuses the request (the display asks again at its next poll). Never throws,
	 * never waits: it is called from the render path.
	 *
	 * @return true when a read was queued, false when one was already pending or the queue was full
	 */
	public static boolean refreshInBackground(final Session pSession, final Adaptation pFactory, final String pConnectorId)
	{
		if (pConnectorId == null || pConnectorId.isEmpty() || !SourceOnboarder.REFRESHING.add(pConnectorId))
		{
			return false;
		}
		try
		{
			SourceOnboarder.REFRESHER.execute(new Runnable()
			{
				@Override
				public void run()
				{
					try
					{
						SourceOnboarder.facts(pSession, pFactory, pConnectorId);
					}
					catch (final RuntimeException | LinkageError ex)
					{
						// facts() reports a refused call in the Facts it returns; this is a real failure (a stopped web application, a session that ended)
						LoggingCategory.getKernel().warn(SourceOnboarder.LOG_PREFIX + "background read of the facts of connector " + pConnectorId + " failed: " + ex);
					}
					finally
					{
						SourceOnboarder.REFRESHING.remove(pConnectorId);
					}
				}
			});
			return true;
		}
		catch (final RuntimeException ex) // a full queue (RejectedExecutionException): nothing was queued
		{
			SourceOnboarder.REFRESHING.remove(pConnectorId);
			return false;
		}
	}

	private static SourceOnboarding.Facts readFacts(final Session pSession, final Adaptation pFactory, final String pConnectorId)
	{
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = pConnectorId;

		final McpClient client;
		try
		{
			client = McpServers.connectors(pSession, pFactory);
		}
		catch (final OperationException ex)
		{
			facts.connectorFound = false;
			facts.connectorError = ex.getMessage();
			return facts;
		}

		SourceOnboarder.describeInto(client, facts);
		if (!facts.connectorFound || !facts.connectorActive || !facts.catalogOperation)
		{
			return facts;
		}

		SourceOnboarder.planInto(client, facts);
		if (!"LINKED".equals(facts.planAction))
		{
			// CREATE/SET_CONNECTOR/SET_NAMESPACE/MANUAL/an error/a state problem: no Source row exists yet to
			// read scan flows/scan/ready facts from - SourceOnboarding.diagnose reads the rest as WAITING anyway
			return facts;
		}

		SourceOnboarder.mappingsInto(client, facts);
		SourceOnboarder.sourceInto(pSession, pFactory, facts);
		return facts;
	}

	private static void describeInto(final McpClient pClient, final SourceOnboarding.Facts pFacts)
	{
		try
		{
			final ObjectNode args = SourceOnboarder.MAPPER.createObjectNode();
			args.put("connector", pFacts.connectorId);
			final McpClient.ToolResult result = pClient.callTool("connectors_describe", args);
			if (result.error || result.json == null)
			{
				pFacts.connectorFound = false;
				pFacts.connectorError = SourceOnboarder.shortText(result.text);
				return;
			}
			pFacts.connectorFound = true;
			pFacts.connectorType = result.json.path("type").asText(null);
			pFacts.connectorLabel = result.json.path("label").asText(null);
			pFacts.connectorActive = result.json.path("active").asBoolean(true);
			for (final JsonNode operation : result.json.path("operations"))
			{
				if ("catalog".equals(operation.path("name").asText("")))
				{
					pFacts.catalogOperation = true;
				}
				if ("sample".equals(operation.path("name").asText("")))
				{
					pFacts.sampleOperation = true; // lot S10b: the catalogue's classification can read this connection's values
				}
				// fix lot (2026-09-25): same "explicit true only" reading DeducedScanOperations#readOnlyFlag
				// already applies (a missing/absent/non-boolean flag is never trusted as read-only) - read here
				// too, from the SAME connectors_describe answer, rather than a second MCP round trip.
				if (operation.has("readOnly") && operation.path("readOnly").isBoolean() && operation.path("readOnly").asBoolean(false))
				{
					pFacts.hasReadOnlyOperation = true;
				}
			}
		}
		catch (final McpException ex)
		{
			pFacts.connectorFound = false;
			pFacts.connectorError = ex.getMessage();
		}
	}

	private static void planInto(final McpClient pClient, final SourceOnboarding.Facts pFacts)
	{
		try
		{
			final ObjectNode args = SourceOnboarder.MAPPER.createObjectNode();
			args.put("connector", pFacts.connectorId);
			args.put("apply", false);
			final McpClient.ToolResult result = pClient.callTool("connectors_catalog_source", args);
			if (result.error || result.json == null)
			{
				pFacts.planError = SourceOnboarder.shortText(result.text);
				return;
			}
			pFacts.catalogState = result.json.path("catalog").path("state").asText(null);
			pFacts.planAction = result.json.path("action").asText(null);
			pFacts.planSourceId = result.json.path("source").asText(null);
			pFacts.planNamespace = result.json.path("namespace").asText(null);
			pFacts.planMessage = result.json.path("message").asText(null);
		}
		catch (final McpException ex)
		{
			pFacts.planError = ex.getMessage();
		}
	}

	/** Looks up the parent scan mapping ({@code operation=catalog}, {@code direction=INBOUND}, {@code feed=true}, {@code child=false}, {@code table=/root/Asset} - task brief's own measured shape); a failed/refused call leaves {@code scanMappingId} null, which {@link SourceOnboarding#diagnose} already reads as "the catalog did not create the scan flows" rather than crashing. */
	private static void mappingsInto(final McpClient pClient, final SourceOnboarding.Facts pFacts)
	{
		try
		{
			final ObjectNode args = SourceOnboarder.MAPPER.createObjectNode();
			args.put("connector", pFacts.connectorId);
			final McpClient.ToolResult result = pClient.callTool("connectors_mappings_list", args);
			if (result.error || result.json == null)
			{
				return;
			}
			for (final JsonNode mapping : result.json.path("mappings"))
			{
				if ("catalog".equals(mapping.path("operation").asText("")) && "INBOUND".equals(mapping.path("direction").asText(""))
					&& mapping.path("feed").asBoolean(false) && !mapping.path("child").asBoolean(true) && "/root/Asset".equals(mapping.path("table").asText("")))
				{
					pFacts.scanMappingId = Integer.valueOf(mapping.path("id").asInt());
					return;
				}
			}
		}
		catch (final McpException ex)
		{
			// leave scanMappingId null - see this method's own Javadoc
		}
	}

	/** Audit F-02 (catalog reads): shown in the onboarding checklist and answered by {@code GET /sources/onboarding/{connector}}: read as the person. */
	private static void sourceInto(final Session pSession, final Adaptation pFactory, final SourceOnboarding.Facts pFacts)
	{
		final CatalogReader.Reader reader = CatalogReader.Reader.of(pSession);
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		if (catalog == null)
		{
			// the catalog module itself is not installed/packaged as expected - facts.catalogState already
			// surfaced this upstream (planInto reads it straight from connectors_catalog_source's own answer);
			// nothing more to add here, sourceExists simply stays false
			return;
		}
		final CatalogReader.SourceStatus status = CatalogReader.sourceById(reader, catalog, pFacts.planSourceId);
		pFacts.sourceExists = status.exists;
		pFacts.sourceActive = status.active;
		pFacts.lastScanStatus = status.lastScanStatus;
		if (status.exists)
		{
			pFacts.assetsScanned = CatalogReader.hasAssets(reader, catalog, pFacts.planSourceId);
			// UX lot U2 (2026-10-13): "objects exist" is not "scanned": a source known only from OpenLineage events has objects and no column
			pFacts.assetsWithColumns = pFacts.assetsScanned && CatalogReader.hasAssetWithColumns(reader, catalog, pFacts.planSourceId);
		}
		pFacts.catalogRestricted = reader.restricted();
	}

	// ================================================================================================ apply/scan

	/**
	 * Writes the catalog source ({@code connectors_catalog_source(apply=true)}), then re-reads {@link #facts}
	 * so the caller always gets a fresh diagnosis - a refusal of the write call itself is logged and left for
	 * the re-read to surface (the catalog-source step's own {@code planError}/plan-action reading), never
	 * thrown: the write attempt genuinely happened, so "what does the catalog look like now" is still the
	 * useful answer, not an exception hiding it.
	 *
	 * @throws OperationException the required MCP server(s) are not usable at all ({@link #preflight}) - the
	 *         one case this method DOES stop for, before attempting any write.
	 */
	public static SourceOnboarding.Facts applyCatalog(final Session pSession, final Adaptation pFactory, final String pConnectorId) throws OperationException
	{
		return SourceOnboarder.applyCatalog(pSession, pFactory, pConnectorId, null);
	}

	/**
	 * Lot C (2026-10-17): what the factory itself wrote into the catalogue during an onboarding chain - the one fact a project deletion needs to tell a
	 * catalogue source the factory created from one it only linked to. Filled by {@link #applyCatalog(Session, Adaptation, String, Created)}, read by
	 * {@code SourcesRun} to write the proof into its step ({@code project.ConnectionProvenance}).
	 */
	public static final class Created
	{
		/** The catalogue source id {@code connectors_catalog_source} answered ({@code source}), {@code ""} when none was learned. */
		public volatile String sourceId = "";
		/** {@code connectors_catalog_source(apply=true)} answered {@code action: CREATE} and {@code applied: true}: the factory wrote the Source row. */
		public volatile boolean sourceCreated;
	}

	/** Same, noting in {@code pCreated} (may be null) whether the call created the catalogue source. */
	public static SourceOnboarding.Facts applyCatalog(final Session pSession, final Adaptation pFactory, final String pConnectorId, final Created pCreated) throws OperationException
	{
		SourceOnboarder.preflight(pSession, pFactory);
		final McpClient client = McpServers.connectors(pSession, pFactory);
		final ObjectNode args = SourceOnboarder.MAPPER.createObjectNode();
		args.put("connector", pConnectorId);
		args.put("apply", true);
		String rightsRefusal = null;
		try
		{
			final McpClient.ToolResult result = client.callTool("connectors_catalog_source", args);
			if (result.error || result.json == null)
			{
				LoggingCategory.getKernel()
					.warn(SourceOnboarder.LOG_PREFIX + "connectors_catalog_source(apply=true) refused for connector " + pConnectorId + ": " + SourceOnboarder.shortText(result.text));
				// audit F-02 (catalog reads): creating the Source asks the catalog right ADMINISTER of the person - said on the step
				rightsRefusal = CatalogRightMessages.refused(CatalogRightMessages.CatalogWrite.REGISTER_SOURCE, result.text);
			}
			else
			{
				LoggingCategory.getKernel().info(SourceOnboarder.LOG_PREFIX + "connectors_catalog_source(apply=true) for connector " + pConnectorId + " -> action="
					+ result.json.path("action").asText("") + " source=" + result.json.path("source").asText(""));
				if (pCreated != null)
				{
					pCreated.sourceId = result.json.path("source").asText("");
					// CatalogDetection.catalogSource: action CREATE + applied true = a Source row written by this call (LINKED / SET_* only change an existing one)
					pCreated.sourceCreated = "CREATE".equals(result.json.path("action").asText("")) && result.json.path("applied").asBoolean(false);
				}
			}
		}
		catch (final McpException ex)
		{
			LoggingCategory.getKernel()
				.warn(SourceOnboarder.LOG_PREFIX + "connectors_catalog_source(apply=true) MCP call failed for connector " + pConnectorId + ": " + ex.getMessage());
			rightsRefusal = CatalogRightMessages.refused(CatalogRightMessages.CatalogWrite.REGISTER_SOURCE, ex.getMessage());
		}
		final SourceOnboarding.Facts after = SourceOnboarder.facts(pSession, pFactory, pConnectorId);
		if (rightsRefusal != null && !"LINKED".equals(after.planAction) && (after.planError == null || after.planError.isEmpty()))
		{
			after.planError = rightsRefusal; // the catalog-source step then shows it, instead of offering the same write again in silence
		}
		return after;
	}

	/**
	 * Runs the parent catalog scan mapping ({@code connectors_sync}, no {@code inputs}/{@code max} - a catalog
	 * scan is not a landing load, task brief) for the connection's current scan mapping (re-read fresh from
	 * {@link #facts}, never trusted from an earlier response - the mapping id can only have come from the
	 * catalog itself, so reading it again is the one way to know it is still current), then re-reads
	 * {@link #facts} so the caller gets the post-scan diagnosis with {@link SourceOnboarding.Facts#scanMessage}
	 * set to the run's own outcome text ({@link LandingFlow#loadOutcome}).
	 *
	 * @throws OperationException the required MCP server(s) are not usable ({@link #preflight}), or the
	 *         connection has no scan mapping yet at all (nothing to run - the caller should have offered
	 *         {@code CATALOG_APPLY} instead, per {@link SourceOnboarding#diagnose}'s own {@code scanFlows}
	 *         step; this is defensive, not expected to fire from the wizard's own gated button).
	 */
	public static SourceOnboarding.Facts runScan(final Session pSession, final Adaptation pFactory, final String pConnectorId) throws OperationException
	{
		SourceOnboarder.preflight(pSession, pFactory);
		final SourceOnboarding.Facts before = SourceOnboarder.facts(pSession, pFactory, pConnectorId);
		if (before.scanMappingId == null)
		{
			throw EngineRefusal.coded("missing.noScanMapping", "Connector " + pConnectorId + " has no catalog scan mapping yet: create/link the catalog source first.", pConnectorId);
		}

		final McpClient client = McpServers.connectors(pSession, pFactory);
		final ObjectNode args = SourceOnboarder.MAPPER.createObjectNode();
		args.put("mapping", before.scanMappingId.intValue());
		args.put("dryRun", false);
		// audit C-35 (wave 10): a catalog scan imports one item per table; without max Connectors took its API default (200)
		// and a larger source was catalogued in part, in silence - ask for every table, and say so if Connectors still cut
		args.put("max", SourceOnboarder.SCAN_MAX_TABLES);
		String scanMessage;
		boolean scanned = false;
		try
		{
			// F2-05: started async and followed with connectors_run_get - a scan of thousands of tables is no longer cut at the bridge's fixed 300 s read timeout
			final ConnectorsRunFollower.Answer result = ConnectorsRunFollower.sync(client, args, null, SourceOnboarder.SCAN_POLL_MS, LoadDeadline.maxMillis());
			if (result.error)
			{
				scanMessage = "connectors_sync refused: " + SourceOnboarder.shortText(result.text);
				LoggingCategory.getKernel().warn(SourceOnboarder.LOG_PREFIX + "connectors_sync refused for connector " + pConnectorId + " mapping " + before.scanMappingId + ": "
					+ scanMessage);
			}
			else
			{
				final LandingFlow.LoadOutcome outcome = LandingFlow.loadOutcome(result.json);
				scanMessage = outcome.message;
				scanned = outcome.ok;
				if (result.json != null && result.json.path("truncated").asBoolean(false))
				{
					scanMessage = (scanMessage == null ? "" : scanMessage + " ") + "Scan cut at " + SourceOnboarder.SCAN_MAX_TABLES
						+ " tables: narrow the source's schema filter or scan the rest from the catalog.";
				}
				LoggingCategory.getKernel().info(SourceOnboarder.LOG_PREFIX + "connectors_sync for connector " + pConnectorId + " mapping " + before.scanMappingId + " -> ok="
					+ outcome.ok + " rows=" + outcome.rows);
			}
		}
		catch (final McpException ex)
		{
			scanMessage = "connectors_sync failed: " + ex.getMessage();
			LoggingCategory.getKernel()
				.warn(SourceOnboarder.LOG_PREFIX + "connectors_sync MCP call failed for connector " + pConnectorId + " mapping " + before.scanMappingId + ": " + ex.getMessage());
		}

		// lot S10b (2026-10-19): the catalogue classifies the columns of a source (tags EMAIL / PHONE / PERSON_NAME...) from the values its sample flow reads; the scan alone never
		// triggered it. Run right after a scan that succeeded, before anything plans the landing from the catalogue's tags; it never fails the scan - a sentence in the message
		String samplingNote = null;
		if (scanned)
		{
			samplingNote = SourceOnboarder.sampleForClassification(pSession, pFactory, before);
		}
		final SourceOnboarding.Facts after = SourceOnboarder.facts(pSession, pFactory, pConnectorId);
		after.scanMessage = samplingNote == null ? scanMessage : (scanMessage == null || scanMessage.isEmpty() ? "" : scanMessage + " ") + samplingNote + ".";
		after.samplingNote = samplingNote;
		return after;
	}

	/**
	 * Lot S10b (2026-10-19): runs the data catalogue's sample flow of the connection's source ({@code Catalog sample: <SOURCE>}, an inbound {@code sample} mapping that writes one
	 * {@code ColumnSample} per text column; {@code ColumnSampleTrigger} classifies the values at once - a regex on them, the {@code Tag} table's name hints - and blanks them), the same
	 * way the Factory runs the catalog scan mapping ({@code connectors_sync} on the mapping id found with {@code connectors_mappings_list}). <b>The flow is the catalogue's own</b>: its
	 * {@code SourceTrigger} declares it (with the scan and profile flows) when the source is registered, for a connection whose type offers a {@code sample} operation - the Factory
	 * neither mirrors that document nor creates the flow, so it never makes one for a type that cannot run it. A sample-file connection offers {@code read}, {@code list} and
	 * {@code catalog} only (ebx-connectors {@code SampleFileConnector}): there is nothing to run for it, and the note says so.
	 * <p>
	 * Never throws and never fails the scan: the answer is one sentence ({@link SampleClassification}), {@code sampling for the catalogue's classification: 9 columns sampled} or
	 * {@code ...: skipped: <reason>}. The sampled values stay inside Connectors and the catalogue (blanked once classified); none goes to the language model by this call.
	 */
	static String sampleForClassification(final Session pSession, final Adaptation pFactory, final SourceOnboarding.Facts pFacts)
	{
		try
		{
			if (!pFacts.sampleOperation)
			{
				return SampleClassification.skipped("the connection" + (pFacts.connectorType == null ? "" : " (type " + pFacts.connectorType + ")")
					+ " offers no sample operation, the catalogue has no values to classify its columns from");
			}
			final McpClient client = McpServers.connectors(pSession, pFactory);
			final ObjectNode listArgs = SourceOnboarder.MAPPER.createObjectNode();
			listArgs.put("connector", pFacts.connectorId);
			final McpClient.ToolResult listed = client.callTool("connectors_mappings_list", listArgs);
			if (listed.error || listed.json == null)
			{
				return SampleClassification.skipped("the mappings of the connection could not be listed (" + SourceOnboarder.shortText(listed.text) + ")");
			}
			Integer mappingId = null;
			for (final JsonNode mapping : listed.json.path("mappings"))
			{
				if (SampleClassification.isSampleFlow(mapping))
				{
					mappingId = Integer.valueOf(mapping.path("id").asInt());
					break;
				}
			}
			if (mappingId == null)
			{
				return SampleClassification.skipped("no 'Catalog sample" + (pFacts.planSourceId == null ? "" : ": " + pFacts.planSourceId)
					+ "' flow: the catalogue declares it when the source is registered, and has not for this one");
			}
			final ObjectNode args = SourceOnboarder.MAPPER.createObjectNode();
			args.put("mapping", mappingId.intValue());
			args.put("dryRun", false);
			args.put("max", SampleClassification.MAX_COLUMNS);
			final ConnectorsRunFollower.Answer result = ConnectorsRunFollower.sync(client, args, null, SourceOnboarder.SCAN_POLL_MS, LoadDeadline.maxMillis());
			if (result.error)
			{
				LoggingCategory.getKernel().warn(SourceOnboarder.LOG_PREFIX + "sample flow of connector " + pFacts.connectorId + " refused: " + SourceOnboarder.shortText(result.text));
				return SampleClassification.skipped("connectors_sync refused (" + SourceOnboarder.shortText(result.text) + ")");
			}
			final String note = SampleClassification.outcome(result.json);
			LoggingCategory.getKernel().info(SourceOnboarder.LOG_PREFIX + "sample flow of connector " + pFacts.connectorId + " mapping " + mappingId + " -> " + note);
			return note;
		}
		catch (final McpException | OperationException | RuntimeException ex)
		{
			LoggingCategory.getKernel().warn(SourceOnboarder.LOG_PREFIX + "sample flow of connector " + pFacts.connectorId + " failed: " + ex.getMessage());
			return SampleClassification.skipped(ex.getMessage());
		}
	}

	/**
	 * UX lot U2 (2026-10-13): "Scan again" of an application card - the catalog scan of the connection run once more whatever the diagnosis
	 * says (a source whose last scan is OK but read no column reads "done" for any other chain here), creating the catalog source and its scan
	 * mapping first when they are missing. Same guards and same scan as {@link #runScan}.
	 *
	 * @throws OperationException see {@link #applyCatalog}/{@link #runScan}; also when the connection cannot be scanned into the catalog at all
	 *         (no catalog operation), which a card never offers
	 */
	public static SourceOnboarding.Facts rescan(final Session pSession, final Adaptation pFactory, final String pConnectorId) throws OperationException
	{
		SourceOnboarding.Facts facts = SourceOnboarder.facts(pSession, pFactory, pConnectorId);
		if (!facts.connectorFound || !facts.connectorActive || !facts.catalogOperation)
		{
			throw EngineRefusal.coded("missing.noCatalogScan", "Connection " + pConnectorId + " cannot be scanned into the catalog.", pConnectorId);
		}
		if (SourceOnboarding.diagnose(facts).nextAction == SourceOnboarding.Action.CATALOG_APPLY)
		{
			facts = SourceOnboarder.applyCatalog(pSession, pFactory, pConnectorId);
		}
		return SourceOnboarder.runScan(pSession, pFactory, pConnectorId);
	}

	/**
	 * sample-files lot (2026-09-23): "Do everything" - chains {@link #applyCatalog} (only when the diagnosis's
	 * next action is still {@code CATALOG_APPLY}) then {@link #runScan} (only when, after that, it is
	 * {@code SCAN_RUN}), each call re-reading fresh facts first (same "never trust the previous response"
	 * discipline as {@link #runScan}'s own mapping-id re-read). This naturally stops at the first step that
	 * comes back {@code BLOCKED} rather than {@code TODO}: a blocked catalogSource step leaves
	 * {@code nextAction} at {@code OPEN_CONNECTION}/{@code NONE}, never {@code CATALOG_APPLY}, so the scan call
	 * is simply never attempted - no separate "stop on blocked" branch is needed. Used both by the wizard's own
	 * "Do everything" button on the guided onboarding panel (task brief) and, file by file, by
	 * {@code SampleSourceImporter} for the "Sources from sample files" panel - one chain, two callers.
	 *
	 * @throws OperationException see {@link #applyCatalog}/{@link #runScan} - the required MCP server(s) are
	 *         not usable at all; a refusal of an individual write call itself is logged and left for the
	 *         returned facts to surface, exactly as in either method alone.
	 */
	public static SourceOnboarding.Facts onboardAll(final Session pSession, final Adaptation pFactory, final String pConnectorId) throws OperationException
	{
		return SourceOnboarder.onboardAll(pSession, pFactory, pConnectorId, null);
	}

	/** Same, noting in {@code pCreated} (may be null) what the chain wrote into the catalogue, even when a later step then fails (lot C, 2026-10-17). */
	public static SourceOnboarding.Facts onboardAll(final Session pSession, final Adaptation pFactory, final String pConnectorId, final Created pCreated) throws OperationException
	{
		SourceOnboarding.Facts facts = SourceOnboarder.facts(pSession, pFactory, pConnectorId);
		SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		if (diagnosis.nextAction == SourceOnboarding.Action.CATALOG_APPLY)
		{
			facts = SourceOnboarder.applyCatalog(pSession, pFactory, pConnectorId, pCreated);
			diagnosis = SourceOnboarding.diagnose(facts);
		}
		if (diagnosis.nextAction == SourceOnboarding.Action.SCAN_RUN)
		{
			facts = SourceOnboarder.runScan(pSession, pFactory, pConnectorId);
		}
		return facts;
	}

	/** Same convention as {@code LandingLoadTaskBean}/{@code FactoryResource#preflight}: stop before any write when the agent account cannot reach {@code ebx-connectors} at all. */
	private static void preflight(final Session pSession, final Adaptation pFactory) throws OperationException
	{
		final ObjectNode check = McpServers.preflight(pSession, pFactory, McpServers.CONNECTORS);
		if (!check.path("ready").asBoolean(false))
		{
			throw EngineRefusal.coded("external.mcpNotUsable", check.path("summary").asText("MCP server(s) not usable before the catalog onboarding write ran."));
		}
	}

	// ----------------------------------------------------------------------------------------------------- detail

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
