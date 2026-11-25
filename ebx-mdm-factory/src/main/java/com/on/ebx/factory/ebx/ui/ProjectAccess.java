package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.permissions.QueryPermissionException;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.SessionPermissions;

/**
 * The EBX permissions of one user on the project explorer (2026-10-14, the product owner: "EBX permissions are respected in every case"; grid in
 * {@code explorer-designer/PERMISSIONS.md}). Public Javadoc APIs only: {@link SessionPermissions} and {@link Request#setSession}.
 * <p>
 * Layers, each read from EBX with the user's own session (taken from the EBX context, never from the page):
 * <ol>
 * <li><b>dataspace, dataset, table</b> - {@link #refusal}: the service shows EBX's refusal instead of the explorer when the dataspace, the dataset or the
 * {@code Project} table is hidden for the user; every AJAX request checks it again;</li>
 * <li><b>records</b> - the projects, and the rows of {@code ProjectSource}, {@code StagingEntity} and {@code PipelineStep} the cards count, are those a
 * {@link Request} carrying the session returns ("the returned records will be filtered using the access rules on records"): one keys-only read per table
 * and per user, kept {@link #TTL_MS}. When the user sees every child row, the shared counts are reused as they are;</li>
 * <li><b>fields</b> - {@link SessionPermissions#getNodeAccessPermission} on the dataset for every field the card shows, filters, searches, sorts or
 * computes from (the child tables included: a count needs its table and its {@code project} field). {@code setSession} does not apply record-dependent
 * field rules (its Javadoc says so): they are evaluated per record with the same method on the record, for every visible project up to
 * {@link #MAX_RECORD_CHECKS} (eleven fields each, during the same read); past that bound only the cards of the page being answered are checked
 * ({@link #finishPage}), so search, counts and sort may then still use a value a record-dependent rule hides - the one approximation left, logged
 * once. Record-dependent rules on the CHILD tables' fields are not evaluated (the counts and the source systems follow the dataset-level rules only);</li>
 * <li><b>actions</b> - Wizard: {@code getTableActionPermissionForService}; Cockpit: {@code getNodeActionPermissionForService} per record; Delete:
 * administrator AND its service permission; New project: wizard permission AND {@code getTableActionPermissionToCreateRootOccurrence} (the explorer
 * launches no workflow, so no {@code isUserAuthorizedToLaunch} applies here).</li>
 * </ol>
 * Cache: per user and dataset, tied to the {@link ProjectIndex} instance it was computed from (a new index = a new token) AND to a 60-second life: a
 * change of role or permission moves no table date, so the life bounds how long a revoked right can still show.
 */
final class ProjectAccess
{
	/** How long one user's permission facts are reused. */
	static final long TTL_MS = 60L * 1000L;
	/** Projects whose record-dependent field rules and Cockpit right are evaluated while reading the visible keys (cost: one record x eleven nodes each). */
	static final int MAX_RECORD_CHECKS = 5000;
	/** Users x datasets kept at most; expired entries are dropped first. */
	private static final int MAX_ENTRIES = 500;

	private static final ConcurrentHashMap<String, Snapshot> CACHE = new ConcurrentHashMap<String, Snapshot>();
	private static volatile boolean boundLogged;

	/** The Project fields a card reads, by {@code ProjectView.F_*} name. */
	private static final Map<String, Path> PROJECT_FIELDS = new LinkedHashMap<String, Path>();
	static
	{
		ProjectAccess.PROJECT_FIELDS.put(ProjectView.F_CODE, FactoryPaths.Project.CODE);
		ProjectAccess.PROJECT_FIELDS.put(ProjectView.F_LABEL, FactoryPaths.Project.LABEL);
		ProjectAccess.PROJECT_FIELDS.put(ProjectView.F_DESCRIPTION, FactoryPaths.Project.DESCRIPTION);
		ProjectAccess.PROJECT_FIELDS.put(ProjectView.F_STATUS, FactoryPaths.Project.STATUS);
		ProjectAccess.PROJECT_FIELDS.put(ProjectView.F_STYLE, FactoryPaths.Project.STYLE);
		ProjectAccess.PROJECT_FIELDS.put(ProjectView.F_MODE, FactoryPaths.Project.MODE);
		ProjectAccess.PROJECT_FIELDS.put(ProjectView.F_OWNER, FactoryPaths.Project.OWNER);
		ProjectAccess.PROJECT_FIELDS.put(ProjectView.F_CREATED, FactoryPaths.Project.CREATED_AT);
		ProjectAccess.PROJECT_FIELDS.put(ProjectView.F_LANDING, FactoryPaths.Project.LANDING_DATASPACE);
		ProjectAccess.PROJECT_FIELDS.put(ProjectView.F_STAGING_DS, FactoryPaths.Project.STAGING_DATASPACE);
		ProjectAccess.PROJECT_FIELDS.put(ProjectView.F_MASTER, FactoryPaths.Project.MASTER_DATASPACE);
	}

	private ProjectAccess()
	{
	}

	/** One user's view of one index. */
	static final class Snapshot
	{
		final ProjectIndex index;
		final long at;
		final ProjectView.Access access;
		final ProjectView.Cards cards;
		boolean canWizard;
		boolean canCreate;
		boolean canDelete;

		Snapshot(final ProjectIndex pIndex, final ProjectView.Access pAccess, final ProjectView.Cards pCards)
		{
			this.index = pIndex;
			this.at = System.currentTimeMillis();
			this.access = pAccess;
			this.cards = pCards;
		}
	}

	// ------------------------------------------------------------------------------------------------- dataset

	/**
	 * Null when the user may see the dataspace, the dataset and the {@code Project} table; otherwise EBX's own reason when it gives one, else a
	 * localized refusal naming what is closed. Evaluated on every call, never cached (cheap, and it is the first lock).
	 */
	static String refusal(final Session pSession, final Adaptation pDataset, final java.util.Locale pLocale)
	{
		if (pSession == null || pDataset == null)
		{
			return Messages.get(ProjectAccess.class, pLocale, "explorer.access.noSession");
		}
		final SessionPermissions permissions = pSession.getPermissions();
		if (permissions.getHomeAccessPermission(pDataset.getHome()).isHidden())
		{
			return Messages.get(ProjectAccess.class, pLocale, "explorer.access.dataspace", pDataset.getHome().getKey().getName());
		}
		if (permissions.getAdaptationAccessPermission(pDataset).isHidden())
		{
			return Messages.get(ProjectAccess.class, pLocale, "explorer.access.dataset", pDataset.getAdaptationName().getStringName());
		}
		final AdaptationTable projects = pDataset.getTable(FactoryPaths.PROJECT);
		if (permissions.getNodeAccessPermission(projects.getTableNode(), pDataset).isHidden())
		{
			return Messages.get(ProjectAccess.class, pLocale, "explorer.access.table", pDataset.getAdaptationName().getStringName());
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------- other screens

	/**
	 * Second pass (2026-10-14): the {@code Project} record a screen or a link opens (Wizard, Cockpit, Delete, Feed designer, Mapping matrix, review
	 * screens, REST routes), read with the user's session: null when the dataset is closed to the user, or the key is unknown, malformed or names a
	 * record the user cannot see - the three are answered the same way, so a hidden project is never confirmed to exist.
	 */
	static Adaptation openProject(final Session pSession, final Adaptation pDataset, final String pKey)
	{
		if (pDataset == null || ProjectAccess.refusal(pSession, pDataset, java.util.Locale.ENGLISH) != null)
		{
			return null;
		}
		return com.on.ebx.factory.ebx.UserView.of(pSession).lookup(pDataset.getTable(FactoryPaths.PROJECT), pKey);
	}

	/** {@link #openProject} for a record EBX itself handed to a record service: still checked, a URL can name any record. */
	static boolean projectOpens(final Session pSession, final Adaptation pDataset, final Adaptation pRecord)
	{
		return pRecord != null && ProjectAccess.openProject(pSession, pDataset, pRecord.getOccurrencePrimaryKey().format()) != null;
	}

	/** The service is enabled for the user on this record (EBX's service permissions; a launch by URL is checked like the Actions menu). */
	static boolean serviceOnRecord(final Session pSession, final com.orchestranetworks.service.ServiceKey pService, final Adaptation pRecord)
	{
		if (pSession == null || pRecord == null)
		{
			return false;
		}
		if (com.on.ebx.factory.ebx.UserView.of(pSession).engine())
		{
			return true;
		}
		return pSession.getPermissions().getNodeActionPermissionForService(pService, pRecord.getContainerTable().getTableNode(), pRecord).isEnabled();
	}

	/** The service is enabled for the user on the table (EBX's service permissions). */
	static boolean serviceOnTable(final Session pSession, final com.orchestranetworks.service.ServiceKey pService, final AdaptationTable pTable)
	{
		if (pSession == null || pTable == null)
		{
			return false;
		}
		if (com.on.ebx.factory.ebx.UserView.of(pSession).engine())
		{
			return true;
		}
		return pSession.getPermissions().getTableActionPermissionForService(pService, pTable).isEnabled();
	}

	// ------------------------------------------------------------------------------------------------ snapshot

	/** The user's view of {@code pIndex}: from the cache while it is the same index and younger than {@link #TTL_MS}, otherwise read again. */
	static Snapshot of(final Session pSession, final Adaptation pDataset, final ProjectIndex pIndex)
	{
		final String key = FactoryHtml.userId(pSession) + "|" + pDataset.getHome().getKey().format() + "|" + pDataset.getAdaptationName().getStringName();
		final long now = System.currentTimeMillis();
		final Snapshot cached = ProjectAccess.CACHE.get(key);
		if (cached != null && cached.index == pIndex && ExplorerFacts.isFresh(now, cached.at, ProjectAccess.TTL_MS))
		{
			return cached;
		}
		final Snapshot built = ProjectAccess.build(pSession, pDataset, pIndex);
		ProjectAccess.trim(now);
		ProjectAccess.CACHE.put(key, built);
		return built;
	}

	/** Drops the expired entries, then any entry while the cache is over its size (a few users never fill it; a crowd only costs re-reads). */
	private static void trim(final long pNow)
	{
		for (final Iterator<Snapshot> it = ProjectAccess.CACHE.values().iterator(); it.hasNext();)
		{
			if (!ExplorerFacts.isFresh(pNow, it.next().at, ProjectAccess.TTL_MS))
			{
				it.remove();
			}
		}
		for (final Iterator<String> it = ProjectAccess.CACHE.keySet().iterator(); it.hasNext() && ProjectAccess.CACHE.size() >= ProjectAccess.MAX_ENTRIES;)
		{
			it.next();
			it.remove();
		}
	}

	private static Snapshot build(final Session pSession, final Adaptation pDataset, final ProjectIndex pIndex)
	{
		final long start = System.currentTimeMillis();
		final SessionPermissions permissions = pSession.getPermissions();
		final AdaptationTable projects = pDataset.getTable(FactoryPaths.PROJECT);
		final SchemaNode tableNode = projects.getTableNode();
		final ProjectView.Access access = new ProjectView.Access();

		// fields, for the whole dataset
		final Map<String, SchemaNode> nodes = new LinkedHashMap<String, SchemaNode>();
		for (final Map.Entry<String, Path> field : ProjectAccess.PROJECT_FIELDS.entrySet())
		{
			final SchemaNode node = tableNode.getNode(field.getValue());
			if (node == null || permissions.getNodeAccessPermission(node, pDataset).isHidden())
			{
				access.hidden.add(field.getKey());
			}
			else
			{
				nodes.put(field.getKey(), node);
			}
		}
		final AdaptationTable sources = ProjectAccess.readableTable(permissions, pDataset, FactoryPaths.PROJECT_SOURCE, FactoryPaths.ProjectSource.PROJECT);
		if (sources == null)
		{
			access.hidden.add(ProjectView.F_SOURCES);
			access.hidden.add(ProjectView.F_SOURCE_SYSTEMS);
		}
		else if (!ProjectAccess.fieldsVisible(permissions, pDataset, sources, FactoryPaths.ProjectSource.SOURCE_SYSTEM))
		{
			access.hidden.add(ProjectView.F_SOURCE_SYSTEMS);
		}
		final AdaptationTable entities = ProjectAccess.readableTable(permissions, pDataset, FactoryPaths.STAGING_ENTITY, FactoryPaths.StagingEntity.PROJECT);
		if (entities == null)
		{
			access.hidden.add(ProjectView.F_STAGING);
		}
		// the latest step is chosen on its start time: a hidden start time hides the whole activity line
		final AdaptationTable steps = ProjectAccess.readableTable(permissions, pDataset, FactoryPaths.PIPELINE_STEP, FactoryPaths.PipelineStep.PROJECT,
			FactoryPaths.PipelineStep.STEP, FactoryPaths.PipelineStep.STARTED_AT);
		if (steps == null)
		{
			access.hidden.add(ProjectView.F_ACTIVITY);
		}

		// records: the projects the session returns, with their record-dependent field rules and Cockpit right
		access.visibleProjects = new HashSet<String>();
		access.cockpitAllowed = new HashSet<String>();
		final RequestResult rows = ProjectAccess.sessionRows(projects, pSession);
		if (rows != null)
		{
			try
			{
				for (Adaptation record = rows.nextAdaptation(); record != null; record = rows.nextAdaptation())
				{
					final String key = record.getOccurrencePrimaryKey().format();
					access.visibleProjects.add(key);
					if (access.checkedRecords.size() < ProjectAccess.MAX_RECORD_CHECKS)
					{
						ProjectAccess.checkRecord(permissions, tableNode, nodes, record, key, access);
					}
				}
			}
			finally
			{
				rows.close();
			}
		}
		if (access.visibleProjects.size() > access.checkedRecords.size() && !ProjectAccess.boundLogged)
		{
			ProjectAccess.boundLogged = true;
			LoggingCategory.getKernel().info("[mdm-factory] project explorer: " + access.visibleProjects.size() + " visible projects, record-dependent field rules "
				+ "evaluated on the first " + ProjectAccess.MAX_RECORD_CHECKS + " while searching and counting, on the shown page only beyond");
		}

		// child rows: null = the user sees all of them (the shared counts are then exact)
		access.visibleSources = sources == null ? null : ProjectAccess.visibleOrAll(sources, pSession, pIndex.sourceRows);
		access.visibleEntities = entities == null ? null : ProjectAccess.visibleOrAll(entities, pSession, pIndex.entityRows);
		access.visibleSteps = steps == null ? null : ProjectAccess.visibleOrAll(steps, pSession, pIndex.stepRows);

		final Snapshot out = new Snapshot(pIndex, access, ProjectView.build(pIndex.cards, pIndex.sourceRows, pIndex.entityRows, pIndex.stepRows, access));
		out.canWizard = permissions.getTableActionPermissionForService(ProjectWizardServiceDeclaration.SERVICE_KEY, projects).isEnabled();
		out.canCreate = out.canWizard && permissions.getTableActionPermissionToCreateRootOccurrence(projects).isEnabled();
		out.canDelete = pSession.isUserInRole(Profile.ADMINISTRATOR)
			&& permissions.getTableActionPermissionForService(ProjectDeleteServiceDeclaration.SERVICE_KEY, projects).isEnabled();
		final long took = System.currentTimeMillis() - start;
		if (took > 2000L)
		{
			LoggingCategory.getKernel().info("[mdm-factory] project explorer permissions of " + FactoryHtml.userId(pSession) + " read in " + took + " ms ("
				+ access.visibleProjects.size() + " projects)");
		}
		return out;
	}

	/** Record-dependent rules of the fields not already hidden for the whole dataset, and the Cockpit right on the record. */
	private static void checkRecord(final SessionPermissions pPermissions, final SchemaNode pTableNode, final Map<String, SchemaNode> pNodes, final Adaptation pRecord,
		final String pKey, final ProjectView.Access pAccess)
	{
		pAccess.checkedRecords.add(pKey);
		Set<String> own = null;
		for (final Map.Entry<String, SchemaNode> field : pNodes.entrySet())
		{
			if (pPermissions.getNodeAccessPermission(field.getValue(), pRecord).isHidden())
			{
				if (own == null)
				{
					own = new HashSet<String>();
				}
				own.add(field.getKey());
			}
		}
		if (own != null)
		{
			pAccess.hiddenByRecord.put(pKey, own);
		}
		if (pPermissions.getNodeActionPermissionForService(FactoryCockpitServiceDeclaration.SERVICE_KEY, pTableNode, pRecord).isEnabled())
		{
			pAccess.cockpitAllowed.add(pKey);
		}
	}

	/**
	 * Past {@link #MAX_RECORD_CHECKS}: the cards of the page being answered whose record was not checked while reading the keys are checked now, on a
	 * copy (the user's snapshot is left as it is). Answers the list to write.
	 */
	static List<ProjectExplorerPage.Card> finishPage(final Session pSession, final Adaptation pDataset, final Snapshot pSnapshot, final List<ProjectExplorerPage.Card> pPage)
	{
		final List<ProjectExplorerPage.Card> out = new ArrayList<ProjectExplorerPage.Card>(pPage.size());
		final AdaptationTable projects = pDataset.getTable(FactoryPaths.PROJECT);
		final SchemaNode tableNode = projects.getTableNode();
		final SessionPermissions permissions = pSession.getPermissions();
		for (final ProjectExplorerPage.Card card : pPage)
		{
			if (pSnapshot.access.checkedRecords.contains(card.key))
			{
				out.add(card);
				continue;
			}
			final ProjectExplorerPage.Card copy = card.copy();
			final Adaptation record = projects.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(card.key));
			if (record == null)
			{
				continue; // deleted since the keys were read
			}
			final Set<String> own = new HashSet<String>();
			for (final Map.Entry<String, Path> field : ProjectAccess.PROJECT_FIELDS.entrySet())
			{
				final SchemaNode node = tableNode.getNode(field.getValue());
				if (node != null && !copy.hidden.contains(field.getKey()) && permissions.getNodeAccessPermission(node, record).isHidden())
				{
					own.add(field.getKey());
				}
			}
			ProjectView.mask(copy, own);
			copy.canCockpit = permissions.getNodeActionPermissionForService(FactoryCockpitServiceDeclaration.SERVICE_KEY, tableNode, record).isEnabled();
			out.add(copy);
		}
		return out;
	}

	// -------------------------------------------------------------------------------------------------- reads

	/** The table when the user may see it and every listed field of it, for the dataset; null otherwise (or when the model has no such table). */
	private static AdaptationTable readableTable(final SessionPermissions pPermissions, final Adaptation pDataset, final Path pTable, final Path... pFields)
	{
		final AdaptationTable table;
		try
		{
			table = pDataset.getTable(pTable);
		}
		catch (final RuntimeException notInThisModel)
		{
			return null;
		}
		if (table == null || pPermissions.getNodeAccessPermission(table.getTableNode(), pDataset).isHidden())
		{
			return null;
		}
		return ProjectAccess.fieldsVisible(pPermissions, pDataset, table, pFields) ? table : null;
	}

	private static boolean fieldsVisible(final SessionPermissions pPermissions, final Adaptation pDataset, final AdaptationTable pTable, final Path... pFields)
	{
		for (final Path field : pFields)
		{
			final SchemaNode node = pTable.getTableNode().getNode(field);
			if (node == null || pPermissions.getNodeAccessPermission(node, pDataset).isHidden())
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Every row of the table the session returns ({@code Request.setSession}: access rules on records, DMA permissions and scripted rules applied). No XPath
	 * filter is used, so a {@link QueryPermissionException} (a filter reading a hidden node) should not happen; if it does, nothing is visible.
	 */
	private static RequestResult sessionRows(final AdaptationTable pTable, final Session pSession)
	{
		final Request request = pTable.createRequest();
		request.setSession(pSession);
		try
		{
			return request.execute();
		}
		catch (final QueryPermissionException forbidden)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] project explorer: " + pTable.getTablePath().format() + " refused for " + FactoryHtml.userId(pSession) + ": "
				+ forbidden.getMessage());
			return null;
		}
	}

	/** The keys of the child rows the session returns; null when that is exactly every row of the index (the shared counts are then the user's). */
	private static Set<String> visibleOrAll(final AdaptationTable pTable, final Session pSession, final List<ProjectView.Row> pIndexRows)
	{
		final Set<String> keys = new HashSet<String>();
		final RequestResult rows = ProjectAccess.sessionRows(pTable, pSession);
		if (rows == null)
		{
			return keys;
		}
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				keys.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		if (keys.size() != pIndexRows.size())
		{
			return keys;
		}
		for (final ProjectView.Row row : pIndexRows)
		{
			if (!keys.contains(row.key))
			{
				return keys;
			}
		}
		return null;
	}
}
