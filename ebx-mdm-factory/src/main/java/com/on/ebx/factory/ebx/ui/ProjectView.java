package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What ONE user may see of the shared {@link ProjectIndex} (2026-10-14, "respect EBX permissions in every case", grid in
 * {@code explorer-designer/PERMISSIONS.md}): the index is a cache of values, never an authority. This class turns it into the
 * cards of one user from the permission facts {@code ProjectAccess} read from EBX - the project records the user's session
 * returns, the child rows ({@code ProjectSource}, {@code StagingEntity}, {@code PipelineStep}) it returns, and the fields that
 * are hidden for the dataset or for one record. Pure: no EBX type, fixture-tested by {@code ProjectViewTest}.
 * <p>
 * Rules:
 * <ul>
 * <li>a project the session does not return is not in the view: never shown, never counted, never searched, never a
 * favorite;</li>
 * <li>a hidden field is blanked on the card's copy, so it is not displayed, faceted, searched or sorted on; a value computed
 * from a hidden field disappears with it ({@link #F_SOURCES}, {@link #F_STAGING}, {@link #F_ACTIVITY} read other tables, see
 * {@link Access#hidden});</li>
 * <li>a hidden status puts the card in the {@link #HIDDEN_STATUS} group instead of revealing its status by the group it is
 * in;</li>
 * <li>child counts only count the child rows the session returns: when every row is returned (the common case) the shared
 * aggregates are reused as they are, otherwise they are recomputed from the visible rows.</li>
 * </ul>
 */
final class ProjectView
{
	// What a card shows, named once. The first eleven are fields of Project; the last four are values read in other tables.
	static final String F_CODE = "code";
	static final String F_LABEL = "label";
	static final String F_DESCRIPTION = "description";
	static final String F_STATUS = "status";
	static final String F_STYLE = "style";
	static final String F_MODE = "mode";
	static final String F_OWNER = "owner";
	static final String F_CREATED = "createdAt";
	static final String F_LANDING = "landingDataspace";
	static final String F_STAGING_DS = "stagingDataspace";
	static final String F_MASTER = "masterDataspace";
	/** Count of {@code ProjectSource} rows: needs the table and its {@code project} field. */
	static final String F_SOURCES = "sources";
	/** {@code ProjectSource.sourceSystem}: the systems shown, the "source system" filter and part of the search. */
	static final String F_SOURCE_SYSTEMS = "sourceSystems";
	/** Count of {@code StagingEntity} rows: needs the table and its {@code project} field. */
	static final String F_STAGING = "staging";
	/** Latest {@code PipelineStep}: needs the table and its {@code project}, {@code step} and {@code startedAt} fields (the latest is chosen on the start time). */
	static final String F_ACTIVITY = "activity";

	/** The group of a card whose status is hidden for the user (never one of the statuses, so the group tells nothing). */
	static final String HIDDEN_STATUS = "HIDDEN";

	private ProjectView()
	{
	}

	/** One row of a child table, as the shared index keeps it: its primary key (formatted), the project it belongs to, and the value the card uses. */
	static final class Row
	{
		final String key;
		final String project;
		/** {@code sourceSystem} for a source, {@code step} for a pipeline step, null for an entity. */
		final String value;
		/** {@code startedAt} of a pipeline step. */
		final Date at;

		Row(final String pKey, final String pProject, final String pValue, final Date pAt)
		{
			this.key = pKey;
			this.project = pProject;
			this.value = pValue;
			this.at = pAt;
		}
	}

	/** The permission facts of one user, as read from EBX by {@code ProjectAccess}. */
	static final class Access
	{
		/** Primary keys of the {@code Project} records the session returns. */
		Set<String> visibleProjects = Collections.emptySet();
		/** Keys of the child rows the session returns; null = every row (the shared aggregates are then exact for this user). */
		Set<String> visibleSources;
		Set<String> visibleEntities;
		Set<String> visibleSteps;
		/** The {@code F_*} names hidden for the whole dataset (field hidden, or a table/field a computed value needs). */
		final Set<String> hidden = new HashSet<String>();
		/** Per project key, the {@code F_*} names hidden for that record only (record-dependent rules on fields). */
		final Map<String, Set<String>> hiddenByRecord = new HashMap<String, Set<String>>();
		/** Project keys whose record-dependent rules were evaluated (all visible ones, unless there were more than {@code ProjectAccess.MAX_RECORD_CHECKS}). */
		final Set<String> checkedRecords = new HashSet<String>();
		/** Project keys on which the user may run the Cockpit (service permission per record); null = not evaluated (then decided per page). */
		Set<String> cockpitAllowed;
	}

	/** The cards of one user, in index order, and their search keys (parallel). */
	static final class Cards
	{
		final List<ProjectExplorerPage.Card> cards = new ArrayList<ProjectExplorerPage.Card>();
		final List<String> searchKeys = new ArrayList<String>();
	}

	/**
	 * The user's cards. {@code pCards} are the index's cards (facts computed over every row); {@code pSources}, {@code pEntities} and {@code pSteps} are the
	 * index's child rows, read only when the user does not see all of them.
	 */
	static Cards build(final List<ProjectExplorerPage.Card> pCards, final List<Row> pSources, final List<Row> pEntities, final List<Row> pSteps, final Access pAccess)
	{
		final ExplorerFacts sources = pAccess.visibleSources == null ? null : ProjectView.sourceFacts(pSources, pAccess.visibleSources);
		final ExplorerFacts entities = pAccess.visibleEntities == null ? null : ProjectView.entityFacts(pEntities, pAccess.visibleEntities);
		final ExplorerFacts steps = pAccess.visibleSteps == null ? null : ProjectView.stepFacts(pSteps, pAccess.visibleSteps);
		final Cards out = new Cards();
		for (final ProjectExplorerPage.Card shared : pCards)
		{
			if (!pAccess.visibleProjects.contains(shared.key))
			{
				continue;
			}
			final ProjectExplorerPage.Card card = shared.copy();
			if (sources != null)
			{
				final ExplorerFacts.ProjectFacts of = sources.of(card.key);
				card.sourceCount = of.sourceCount;
				card.sourceSystems.clear();
				card.sourceSystems.addAll(of.sourceSystems);
			}
			if (entities != null)
			{
				card.stagingEntityCount = entities.of(card.key).stagingEntityCount;
			}
			if (steps != null)
			{
				final ExplorerFacts.ProjectFacts of = steps.of(card.key);
				card.lastActivityStep = of.lastActivityStep;
				card.lastActivityAt = of.lastActivityAt;
			}
			final Set<String> hidden = new HashSet<String>(pAccess.hidden);
			final Set<String> own = pAccess.hiddenByRecord.get(card.key);
			if (own != null)
			{
				hidden.addAll(own);
			}
			ProjectView.mask(card, hidden);
			if (pAccess.cockpitAllowed != null)
			{
				card.canCockpit = pAccess.cockpitAllowed.contains(card.key);
			}
			out.cards.add(card);
			out.searchKeys.add(ProjectExplorerQuery.searchKey(card));
		}
		return out;
	}

	/**
	 * Blanks on {@code pCard} (a copy) every value named in {@code pHidden} and records the names on the card, so the page drops the line instead of
	 * showing an empty or a "none" value (a "No source" would itself be a value). Also recomputes {@code sortName}: sorting on a hidden label would leak it.
	 */
	static void mask(final ProjectExplorerPage.Card pCard, final Set<String> pHidden)
	{
		pCard.hidden.addAll(pHidden);
		if (pHidden.contains(ProjectView.F_CODE))
		{
			pCard.code = ""; // the primary key still travels in the links (EBX's own record URLs carry it too), it is no longer displayed or searched
		}
		if (pHidden.contains(ProjectView.F_LABEL))
		{
			pCard.label = "";
		}
		if (pHidden.contains(ProjectView.F_DESCRIPTION))
		{
			pCard.description = "";
		}
		if (pHidden.contains(ProjectView.F_STATUS))
		{
			pCard.status = ProjectView.HIDDEN_STATUS;
		}
		if (pHidden.contains(ProjectView.F_STYLE))
		{
			pCard.style = "";
		}
		if (pHidden.contains(ProjectView.F_MODE))
		{
			pCard.mode = "";
		}
		if (pHidden.contains(ProjectView.F_OWNER))
		{
			pCard.owner = "";
		}
		if (pHidden.contains(ProjectView.F_CREATED))
		{
			pCard.createdAt = null;
		}
		if (pHidden.contains(ProjectView.F_LANDING))
		{
			pCard.hasLanding = false;
		}
		if (pHidden.contains(ProjectView.F_STAGING_DS))
		{
			pCard.hasStaging = false;
		}
		if (pHidden.contains(ProjectView.F_MASTER))
		{
			pCard.hasMaster = false;
		}
		if (pHidden.contains(ProjectView.F_SOURCES))
		{
			pCard.sourceCount = 0;
			pCard.sourceSystems.clear();
		}
		if (pHidden.contains(ProjectView.F_SOURCE_SYSTEMS))
		{
			pCard.sourceSystems.clear();
		}
		if (pHidden.contains(ProjectView.F_STAGING))
		{
			pCard.stagingEntityCount = 0;
		}
		if (pHidden.contains(ProjectView.F_ACTIVITY))
		{
			pCard.lastActivityStep = null;
			pCard.lastActivityAt = null;
		}
		// the shared index sorts on the label; a hidden label falls back to the code, a hidden code to nothing (ties keep the index order)
		pCard.sortName = !pCard.label.isEmpty() ? pCard.label.toLowerCase(java.util.Locale.ROOT) : pCard.code.toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * The facet selection of a request without the facets the user cannot see (a filter on a hidden value would match nothing anyway; dropping it keeps
	 * the page from being stuck on an empty result it cannot undo).
	 */
	static Map<String, Set<String>> selection(final Map<String, Set<String>> pSelection, final Set<String> pHidden)
	{
		final Map<String, Set<String>> out = new LinkedHashMap<String, Set<String>>(pSelection);
		if (pHidden.contains(ProjectView.F_STATUS))
		{
			out.remove("status");
		}
		if (pHidden.contains(ProjectView.F_STYLE))
		{
			out.remove("style");
		}
		if (pHidden.contains(ProjectView.F_MODE))
		{
			out.remove("mode");
		}
		if (pHidden.contains(ProjectView.F_OWNER))
		{
			out.remove("owner");
		}
		if (pHidden.contains(ProjectView.F_SOURCES) || pHidden.contains(ProjectView.F_SOURCE_SYSTEMS))
		{
			out.remove("source");
		}
		if (pHidden.contains(ProjectView.F_LANDING) && pHidden.contains(ProjectView.F_STAGING_DS) && pHidden.contains(ProjectView.F_MASTER))
		{
			out.remove("zone");
		}
		return out;
	}

	/** The sort of a request, {@code name} when the criterion reads a hidden value. */
	static String sort(final String pSort, final Set<String> pHidden)
	{
		if ("created".equals(pSort) && pHidden.contains(ProjectView.F_CREATED))
		{
			return "name";
		}
		if ("sources".equals(pSort) && pHidden.contains(ProjectView.F_SOURCES))
		{
			return "name";
		}
		return pSort;
	}

	/** The favorites the page may show: the user's pins on projects it sees (a pin on a project that became invisible is neither shown nor counted). */
	static Set<String> favorites(final Set<String> pPinned, final Set<String> pVisibleProjects)
	{
		final Set<String> out = new HashSet<String>();
		if (pPinned != null)
		{
			for (final String key : pPinned)
			{
				if (pVisibleProjects.contains(key))
				{
					out.add(key);
				}
			}
		}
		return out;
	}

	private static ExplorerFacts sourceFacts(final List<Row> pRows, final Set<String> pVisible)
	{
		final ExplorerFacts out = new ExplorerFacts();
		for (final Row row : pRows)
		{
			if (pVisible.contains(row.key))
			{
				out.offerSource(row.project, row.value);
			}
		}
		return out;
	}

	private static ExplorerFacts entityFacts(final List<Row> pRows, final Set<String> pVisible)
	{
		final ExplorerFacts out = new ExplorerFacts();
		for (final Row row : pRows)
		{
			if (pVisible.contains(row.key))
			{
				out.offerEntity(row.project);
			}
		}
		return out;
	}

	private static ExplorerFacts stepFacts(final List<Row> pRows, final Set<String> pVisible)
	{
		final ExplorerFacts out = new ExplorerFacts();
		for (final Row row : pRows)
		{
			if (pVisible.contains(row.key))
			{
				out.offerStep(row.project, row.value, row.at);
			}
		}
		return out;
	}
}
