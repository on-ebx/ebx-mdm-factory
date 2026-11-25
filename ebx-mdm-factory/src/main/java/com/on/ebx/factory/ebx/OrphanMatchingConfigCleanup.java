package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.master.MasterNames;
import com.on.ebx.factory.project.MatchingConfigRows;
import com.on.ebx.factory.project.MatchingConfigRows.BusinessObject;
import com.on.ebx.factory.project.MatchingConfigRows.Finding;
import com.on.ebx.factory.project.MatchingConfigRows.ModelState;
import com.on.ebx.factory.project.MatchingConfigRows.TableConfig;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Lot M18 (2026-10-18): the orphan sweep of the Match and Merge add-on's own configuration dataset. Projects deleted before this lot (25 test projects on 2026-10-18)
 * left {@code BusinessObjectConfiguration} and {@code TableConfiguration} rows for publications that no longer exist, and a business-object row without its model breaks
 * the add-on at the next Tomcat start. The rules are {@link MatchingConfigRows} (pure, tested), the two writes are {@link MameConfigRows} (the documented
 * unlink-then-delete), the {@code delete_matching_config} of a table is {@link GeneratedObjectDeleter#deleteMatchingConfig} - the very code the project deletion runs.
 * <p>
 * Behind {@code GET /matching/orphans[?publication=<name>]} and {@code POST /matching/orphans/delete} (administrator): {@link #list} and {@link #delete}. A
 * publication whose model is gone ({@code get_publication}: {@code found=false}) is listed on its own; one whose model still exists is listed only when named, and
 * swept only when named AND it is not the master model of a live Factory project. For a publication whose model is gone the business-object rows are unlinked and
 * deleted and the table configurations STAY (deleting them leaves their hidden merge policies pointing to nothing and the next boot fails in {@code MergeRelationService},
 * measured 2026-10-18); for one that still exists the table configurations are then removed with {@code delete_matching_config} (which needs the model).
 * <p>
 * Lot F3 (2026-10-19): no distribution-trigger removal is needed before {@code delete_matching_config} here. The distribution triggers of the master model no longer reference
 * {@code Linked_Field_State}/{@code GoldenId}, so deleting a configuration cannot make the model stop compiling any more (it did while they did, measured 2026-10-18 22:17); a model
 * still carrying triggers from the older generator is repaired by "Install triggers" ({@code DistributionTriggerInstaller}, which rewrites the bodies that differ).
 */
public final class OrphanMatchingConfigCleanup
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** The data-model server's sentence for a publication it cannot find ({@code "Publication not found: '<name>'."}, measured 2026-10-18), lower case - thrown on some builds, the answer's {@code message} on this one. */
	static final String PUBLICATION_NOT_FOUND_MARKER = "publication not found";

	private OrphanMatchingConfigCleanup()
	{
	}

	// ============================================================================================================ evidence

	/** The state of the publication's model according to the data-model server: {@code get_publication} {@code found}. A server that cannot answer is {@link ModelState#UNKNOWN}. */
	static ModelState modelState(final McpClient pClient, final String pPublication)
	{
		final ObjectNode args = OrphanMatchingConfigCleanup.MAPPER.createObjectNode();
		args.put("publication_name", pPublication);
		try
		{
			final McpClient.ToolResult result = pClient.callTool("get_publication", args);
			if (result.json != null && result.json.has("found"))
			{
				return result.json.path("found").asBoolean(false) ? ModelState.EXISTS : ModelState.GONE;
			}
			return ModelState.UNKNOWN;
		}
		catch (final McpException ex)
		{
			final String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);
			return message.contains(OrphanMatchingConfigCleanup.PUBLICATION_NOT_FOUND_MARKER) ? ModelState.GONE : ModelState.UNKNOWN;
		}
	}

	/** The models of the projects that still exist: {@code <code>_Master} and {@code <code>_Domain} - never swept while their project is there. */
	static Set<String> liveProjectModels(final Adaptation pFactory)
	{
		final Set<String> out = new LinkedHashSet<String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String code = row.getString(FactoryPaths.Project.CODE);
				if (code != null && !code.trim().isEmpty())
				{
					out.add(MasterNames.modelName(code.trim()));
					out.add(code.trim() + "_Domain");
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static Map<String, ModelState> states(final Session pSession, final Adaptation pFactory, final List<BusinessObject> pBusinessObjects, final List<TableConfig> pTableConfigs,
		final Set<String> pOnly) throws OperationException
	{
		final Set<String> publications = new LinkedHashSet<String>();
		for (final BusinessObject row : pBusinessObjects)
		{
			publications.add(MatchingConfigRows.publicationOf(row.dataModel));
		}
		for (final TableConfig row : pTableConfigs)
		{
			publications.add(MatchingConfigRows.publicationOf(row.dataModel));
		}
		publications.remove(null);
		final Map<String, ModelState> out = new LinkedHashMap<String, ModelState>();
		if (publications.isEmpty())
		{
			return out;
		}
		final McpClient client = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
		for (final String publication : publications)
		{
			if (pOnly == null || pOnly.isEmpty() || pOnly.contains(publication))
			{
				out.put(publication, OrphanMatchingConfigCleanup.modelState(client, publication));
			}
		}
		return out;
	}

	// ================================================================================================================ list

	/**
	 * What the add-on's dataset holds for publications whose model is gone (and, when {@code pPublication} is given, for that publication whatever its model state).
	 * Read-only. Needs the data-model server (one {@code get_publication} per publication).
	 */
	public static ObjectNode list(final Session pSession, final Adaptation pFactory, final String pPublication) throws OperationException
	{
		final ObjectNode out = OrphanMatchingConfigCleanup.MAPPER.createObjectNode();
		final Adaptation dataset = MameConfigRows.dataset(pFactory.getHome().getRepository());
		final ArrayNode publications = out.putArray("publications");
		if (dataset == null)
		{
			out.put("error", "The Match and Merge add-on's configuration dataset is not available.");
			out.put("count", 0);
			out.put("eligibleCount", 0);
			out.put("confirmWith", "0");
			return out;
		}
		final String only = pPublication == null ? "" : pPublication.trim();
		final List<BusinessObject> businessObjects = MameConfigRows.businessObjects(dataset);
		final List<TableConfig> tableConfigs = MameConfigRows.tableConfigs(dataset);
		final Set<String> named = new LinkedHashSet<String>();
		if (!only.isEmpty())
		{
			named.add(only);
		}
		final List<Finding> findings = MatchingConfigRows.classify(businessObjects, tableConfigs, OrphanMatchingConfigCleanup.states(pSession, pFactory, businessObjects, tableConfigs, named),
			OrphanMatchingConfigCleanup.liveProjectModels(pFactory), named);
		int eligible = 0;
		for (final Finding finding : findings)
		{
			if (!only.isEmpty() && !only.equals(finding.publication))
			{
				continue;
			}
			eligible += finding.eligible ? 1 : 0;
			publications.add(OrphanMatchingConfigCleanup.toJson(finding));
		}
		out.put("count", publications.size());
		out.put("eligibleCount", eligible);
		out.put("confirmWith", String.valueOf(eligible));
		out.put("publication", only);
		final ObjectNode checked = out.putObject("rowsRead");
		checked.put("businessObjectConfigurations", businessObjects.size());
		checked.put("tableConfigurations", tableConfigs.size());
		return out;
	}

	private static ObjectNode toJson(final Finding pFinding)
	{
		final ObjectNode node = OrphanMatchingConfigCleanup.MAPPER.createObjectNode();
		node.put("publication", pFinding.publication);
		node.put("model", pFinding.modelState.name());
		node.put("ownedByLiveProject", pFinding.ownedByLiveProject);
		node.put("eligible", pFinding.eligible);
		node.put("action", pFinding.action.name());
		if (pFinding.reason == null)
		{
			node.putNull("reason");
		}
		else
		{
			node.put("reason", pFinding.reason);
		}
		final ArrayNode businessObjects = node.putArray("businessObjects");
		for (final BusinessObject row : pFinding.businessObjects)
		{
			final ObjectNode one = businessObjects.addObject();
			one.put("id", row.id);
			one.put("mainTable", row.mainTable);
		}
		final ArrayNode tableConfigs = node.putArray("tableConfigurations");
		for (final TableConfig row : pFinding.tableConfigs)
		{
			final ObjectNode one = tableConfigs.addObject();
			one.put("id", row.id);
			one.put("table", row.table);
			if (row.businessObjectId.isEmpty())
			{
				one.putNull("businessObject");
			}
			else
			{
				one.put("businessObject", row.businessObjectId);
			}
		}
		node.put("tableConfigurationsKept", pFinding.tableConfigsKept());
		return node;
	}

	// ========================================================================================================== delete

	/** The answer of {@link #delete}: a refusal (nothing changed) or the per-publication lines. */
	public static final class DeleteResult
	{
		/** Non-null: nothing was changed, this is why (HTTP 409). */
		public final String refusal;
		public final ObjectNode json;

		DeleteResult(final String pRefusal, final ObjectNode pJson)
		{
			this.refusal = pRefusal;
			this.json = pJson;
		}
	}

	/**
	 * Re-reads live, then cleans the named publications that are eligible now. Refuses (nothing changed) when {@code pConfirm} is not exactly the number of eligible names
	 * sent. Per publication: every business object is unlinked and deleted ({@link MameConfigRows#unlinkAndDelete}); when the model still exists, each table configuration is
	 * then removed with {@code delete_matching_config}; the add-on's rows are read back and reported.
	 */
	public static DeleteResult delete(final Session pSession, final Adaptation pFactory, final List<String> pPublications, final String pConfirm) throws OperationException
	{
		final Adaptation dataset = MameConfigRows.dataset(pFactory.getHome().getRepository());
		if (dataset == null)
		{
			return new DeleteResult("The Match and Merge add-on's configuration dataset is not available; nothing was changed.", null);
		}
		final Set<String> named = new LinkedHashSet<String>(pPublications);
		final List<BusinessObject> businessObjects = MameConfigRows.businessObjects(dataset);
		final List<TableConfig> tableConfigs = MameConfigRows.tableConfigs(dataset);
		final Map<String, Finding> eligible = new LinkedHashMap<String, Finding>();
		final Map<String, String[]> settled = new LinkedHashMap<String, String[]>();
		for (final Finding finding : MatchingConfigRows.classify(businessObjects, tableConfigs, OrphanMatchingConfigCleanup.states(pSession, pFactory, businessObjects, tableConfigs, named),
			OrphanMatchingConfigCleanup.liveProjectModels(pFactory), named))
		{
			if (!named.contains(finding.publication))
			{
				continue;
			}
			if (finding.eligible)
			{
				eligible.put(finding.publication, finding);
			}
			else
			{
				settled.put(finding.publication, new String[] { "KEPT", finding.reason == null ? "nothing to do." : finding.reason });
			}
		}
		final String refusal = MatchingConfigRows.confirmRefusal(pConfirm, eligible.size());
		if (refusal != null)
		{
			return new DeleteResult(refusal, null);
		}
		final String user = pSession.getUserReference().getUserId();
		for (final Finding finding : eligible.values())
		{
			settled.put(finding.publication, OrphanMatchingConfigCleanup.cleanOne(pSession, pFactory, dataset, finding, user));
		}
		final ObjectNode out = OrphanMatchingConfigCleanup.MAPPER.createObjectNode();
		final ArrayNode results = out.putArray("results");
		int cleaned = 0;
		int kept = 0;
		int failed = 0;
		int absent = 0;
		for (final String publication : pPublications)
		{
			String[] line = settled.get(publication);
			if (line == null)
			{
				line = new String[] { "ABSENT", "the add-on's dataset holds no row of this publication." };
			}
			final ObjectNode node = results.addObject();
			node.put("publication", publication);
			node.put("result", line[0]);
			if (line[1] != null && !line[1].isEmpty())
			{
				node.put("KEPT".equals(line[0]) ? "reason" : "message", line[1]);
			}
			node.put("rowsLeft", MameConfigRows.remaining(pFactory.getHome().getRepository(), publication).size());
			cleaned += "DELETED".equals(line[0]) ? 1 : 0;
			kept += "KEPT".equals(line[0]) ? 1 : 0;
			failed += "FAILED".equals(line[0]) ? 1 : 0;
			absent += "ABSENT".equals(line[0]) ? 1 : 0;
		}
		out.put("cleaned", cleaned);
		out.put("kept", kept);
		out.put("absent", absent);
		out.put("failed", failed);
		return new DeleteResult(null, out);
	}

	/** @return {result, message} */
	private static String[] cleanOne(final Session pSession, final Adaptation pFactory, final Adaptation pDataset, final Finding pFinding, final String pUser)
	{
		final StringBuilder note = new StringBuilder();
		try
		{
			int unlinked = 0;
			int businessObjects = 0;
			for (final BusinessObject row : pFinding.businessObjects)
			{
				final int n = MameConfigRows.unlinkAndDelete(pSession, pDataset, row.id);
				if (n >= 0)
				{
					businessObjects++;
					unlinked += n;
				}
			}
			note.append(businessObjects).append(" business object configuration(s) deleted, ").append(unlinked).append(" table configuration(s) unlinked");
			if (pFinding.action == MatchingConfigRows.Action.UNLINK_DELETE_AND_REMOVE_TABLE_CONFIGS)
			{
				int removed = 0;
				for (final TableConfig row : pFinding.tableConfigs)
				{
					final GeneratedObjectDeleter.Outcome outcome = GeneratedObjectDeleter.deleteMatchingConfig(pSession, pFactory, row.table, pFinding.publication);
					if ("FAILED".equals(outcome.result) || GeneratedObjectDeleter.RESULT_KEPT_BUSINESS_OBJECT.equals(outcome.result))
					{
						return OrphanMatchingConfigCleanup.logged(pUser, pFinding, new String[] { "FAILED", note + "; delete_matching_config on " + row.table + ": "
							+ (outcome.message == null ? outcome.result : outcome.message) });
					}
					removed += "DELETED".equals(outcome.result) ? 1 : 0;
				}
				note.append(", ").append(removed).append(" table configuration(s) removed with delete_matching_config");
			}
			else if (!pFinding.tableConfigs.isEmpty())
			{
				note.append("; ").append(pFinding.tableConfigs.size()).append(" table configuration(s) of the vanished model stay (deleting them breaks the next start)");
			}
			return OrphanMatchingConfigCleanup.logged(pUser, pFinding, new String[] { "DELETED", note.toString() });
		}
		catch (final OperationException ex)
		{
			return OrphanMatchingConfigCleanup.logged(pUser, pFinding, new String[] { "FAILED", note + "; " + ex.getMessage() });
		}
		catch (final McpException ex)
		{
			return OrphanMatchingConfigCleanup.logged(pUser, pFinding, new String[] { "FAILED", note + "; " + ex.getMessage() });
		}
		catch (final RuntimeException ex)
		{
			return OrphanMatchingConfigCleanup.logged(pUser, pFinding, new String[] { "FAILED", note + "; " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()) });
		}
	}

	/** One kernel.log line per attempted publication: who, which, what came of it. */
	private static String[] logged(final String pUser, final Finding pFinding, final String[] pLine)
	{
		LoggingCategory.getKernel().info("[mdm-factory] orphan matching configuration cleanup by " + pUser + ": publication " + pFinding.publication + " (model " + pFinding.modelState + ") -> " + pLine[0]
			+ (pLine[1] == null || pLine[1].isEmpty() ? "" : ": " + pLine[1]));
		return pLine;
	}
}
