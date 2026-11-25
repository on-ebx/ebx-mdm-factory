package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lot M18 (2026-10-18): the rows the Match and Merge add-on keeps in ITS OWN configuration dataset (dataspace {@code ebx-addon-mame}, dataset
 * {@code ebx-addon-mame-configuration}) for a master publication, and what the Factory does with them when the publication goes. Pure (no EBX class): the
 * reading and the writing are {@code ebx.MameConfigRows}; this class decides.
 * <p>
 * Measured 2026-10-18 after 25 test projects were deleted with the Factory's own route: the dataset still held 17 {@code /root/BusinessObjectConfiguration} rows and
 * 38 {@code /root/TableConfiguration} rows (36 of them linked to a business object by {@code FKBusinessObject}) for the deleted publications, and a business-object
 * configuration whose model is gone breaks the add-on at the next Tomcat start (CLAUDE.md section 8). {@code delete_matching_config} is refused on a table that
 * belongs to a business object ("use the administration screen"), and nothing deletes the business-object row. The documented procedure, done here:
 * <ol>
 * <li>unlink every {@code TableConfiguration} of the business object ({@code FKBusinessObject} to null);</li>
 * <li>delete the {@code BusinessObjectConfiguration} row;</li>
 * <li>then, while the model still exists, {@code delete_matching_config} on each member table (now a plain table), which removes its {@code TableConfiguration}
 * row and its metadataset.</li>
 * </ol>
 * The {@code TableConfiguration} rows themselves are NEVER deleted by Data Services or by this code: their hidden merge policies would point to nothing and the
 * next boot fails in {@code MergeRelationService} (measured). A table configuration whose model is already gone therefore stays, listed and reported.
 */
public final class MatchingConfigRows
{
	/** The prefix of the {@code dataModel} value of a configuration built on a DMA publication; any other value (a module XSD) is not the Factory's and never touched. */
	public static final String PUBLICATION_PREFIX = "urn:ebx:publication:";

	private MatchingConfigRows()
	{
	}

	/** One {@code /root/TableConfiguration} row. */
	public static final class TableConfig
	{
		public final String id;
		public final String dataModel;
		public final String table;
		/** The {@code FKBusinessObject} (a {@code BusinessObjectConfiguration} id); empty when the table belongs to no business object. */
		public final String businessObjectId;

		public TableConfig(final String pId, final String pDataModel, final String pTable, final String pBusinessObjectId)
		{
			this.id = pId == null ? "" : pId;
			this.dataModel = pDataModel == null ? "" : pDataModel;
			this.table = pTable == null ? "" : pTable;
			this.businessObjectId = pBusinessObjectId == null ? "" : pBusinessObjectId;
		}
	}

	/** One {@code /root/BusinessObjectConfiguration} row. */
	public static final class BusinessObject
	{
		public final String id;
		public final String dataModel;
		public final String mainTable;

		public BusinessObject(final String pId, final String pDataModel, final String pMainTable)
		{
			this.id = pId == null ? "" : pId;
			this.dataModel = pDataModel == null ? "" : pDataModel;
			this.mainTable = pMainTable == null ? "" : pMainTable;
		}
	}

	/** {@code urn:ebx:publication:<name>} to {@code <name>}; null for anything else (a module XSD, blank). */
	public static String publicationOf(final String pDataModel)
	{
		if (pDataModel == null || !pDataModel.startsWith(MatchingConfigRows.PUBLICATION_PREFIX))
		{
			return null;
		}
		final String name = pDataModel.substring(MatchingConfigRows.PUBLICATION_PREFIX.length()).trim();
		return name.isEmpty() ? null : name;
	}

	/** {@code <name>} to {@code urn:ebx:publication:<name>}. */
	public static String urnOf(final String pPublication)
	{
		return MatchingConfigRows.PUBLICATION_PREFIX + pPublication;
	}

	/** {@code /root/Client} to {@code Client}. */
	public static String entityOf(final String pTablePath)
	{
		if (pTablePath == null)
		{
			return "";
		}
		return pTablePath.startsWith("/root/") ? pTablePath.substring("/root/".length()) : pTablePath;
	}

	// ============================================================================================= the plan's business-object facts

	/**
	 * The business objects of one master publication as the deletion plan shows them: the main table and the tables of every {@code TableConfiguration} linked to
	 * it. Built from the two row lists by {@link #businessObjectsOf}.
	 */
	public static final class BusinessObjectFacts
	{
		public final String id;
		public final String mainTablePath;
		/** Every table whose {@code TableConfiguration} is linked to this business object, in row order (the main table included when it has one). */
		public final List<String> memberTablePaths;
		/** Ids of the linked {@code TableConfiguration} rows, same order. */
		public final List<String> memberConfigIds;

		public BusinessObjectFacts(final String pId, final String pMainTablePath, final List<String> pMemberTablePaths, final List<String> pMemberConfigIds)
		{
			this.id = pId;
			this.mainTablePath = pMainTablePath;
			this.memberTablePaths = Collections.unmodifiableList(new ArrayList<String>(pMemberTablePaths));
			this.memberConfigIds = Collections.unmodifiableList(new ArrayList<String>(pMemberConfigIds));
		}

		/** {@code Client, Adresse}: the entity names, main table first. */
		public String membersText()
		{
			final List<String> names = new ArrayList<String>();
			names.add(MatchingConfigRows.entityOf(this.mainTablePath));
			for (final String path : this.memberTablePaths)
			{
				final String name = MatchingConfigRows.entityOf(path);
				if (!names.contains(name))
				{
					names.add(name);
				}
			}
			final StringBuilder sb = new StringBuilder();
			for (final String name : names)
			{
				sb.append(sb.length() == 0 ? "" : ", ").append(name);
			}
			return sb.toString();
		}
	}

	/** The business objects of {@code pPublication} (rows whose {@code dataModel} is its urn), each with its linked table configurations. */
	public static List<BusinessObjectFacts> businessObjectsOf(final String pPublication, final List<BusinessObject> pBusinessObjects, final List<TableConfig> pTableConfigs)
	{
		final String urn = MatchingConfigRows.urnOf(pPublication);
		final List<BusinessObjectFacts> out = new ArrayList<BusinessObjectFacts>();
		for (final BusinessObject bo : pBusinessObjects)
		{
			if (!urn.equals(bo.dataModel))
			{
				continue;
			}
			final List<String> tables = new ArrayList<String>();
			final List<String> ids = new ArrayList<String>();
			for (final TableConfig tc : pTableConfigs)
			{
				if (bo.id.equals(tc.businessObjectId) && urn.equals(tc.dataModel))
				{
					tables.add(tc.table);
					ids.add(tc.id);
				}
			}
			out.add(new BusinessObjectFacts(bo.id, bo.mainTable, tables, ids));
		}
		return out;
	}

	/** The sentence of the deletion plan's item for one business object. */
	public static String planDetail(final BusinessObjectFacts pFacts)
	{
		return "Business object of " + MatchingConfigRows.entityOf(pFacts.mainTablePath) + ": " + pFacts.membersText()
			+ ". Its configuration is unlinked from the member tables and deleted in the add-on's dataset before the members' matching configurations are.";
	}

	// ================================================================================================================ the orphan sweep

	/** Does the data model of a publication still exist on the instance. */
	public enum ModelState
	{
		EXISTS, GONE,
		/** The data-model server could not tell: never acted on. */
		UNKNOWN
	}

	/** What the sweep does with one publication. */
	public enum Action
	{
		/** The model is gone: unlink and delete the business-object rows; the table configurations stay (their deletion breaks the next boot). */
		UNLINK_AND_DELETE_BUSINESS_OBJECTS,
		/** The model still exists and the publication was named: unlink and delete the business-object rows, then {@code delete_matching_config} on each table. */
		UNLINK_DELETE_AND_REMOVE_TABLE_CONFIGS,
		/** Nothing to do: no business-object row and (model gone) only table configurations that stay. */
		NOTHING
	}

	/** One publication's rows in the add-on's dataset and the verdict. */
	public static final class Finding
	{
		public final String publication;
		public final ModelState modelState;
		public final List<BusinessObject> businessObjects;
		public final List<TableConfig> tableConfigs;
		/** True when a live Factory project owns the publication (its master model name): never swept. */
		public final boolean ownedByLiveProject;
		/** True when the caller named this publication (the only way a publication whose model still exists is swept). */
		public final boolean named;
		public final boolean eligible;
		public final Action action;
		/** Non-null when not eligible, or when something stays; plain English. */
		public final String reason;

		Finding(final String pPublication, final ModelState pState, final List<BusinessObject> pBusinessObjects, final List<TableConfig> pTableConfigs, final boolean pOwned,
			final boolean pNamed, final boolean pEligible, final Action pAction, final String pReason)
		{
			this.publication = pPublication;
			this.modelState = pState;
			this.businessObjects = Collections.unmodifiableList(pBusinessObjects);
			this.tableConfigs = Collections.unmodifiableList(pTableConfigs);
			this.ownedByLiveProject = pOwned;
			this.named = pNamed;
			this.eligible = pEligible;
			this.action = pAction;
			this.reason = pReason;
		}

		/** Table configurations that stay after the sweep (all of them when the model is gone). */
		public int tableConfigsKept()
		{
			return this.action == Action.UNLINK_AND_DELETE_BUSINESS_OBJECTS || this.action == Action.NOTHING ? this.tableConfigs.size() : 0;
		}
	}

	/**
	 * Groups the rows by publication and judges each: a publication is listed when its model is {@link ModelState#GONE}, or when {@code pNamed} names it (whatever
	 * its model state). Eligible when the model is gone, or named, existing, and not the master model of a live Factory project ({@code pLiveProjectModels}); a
	 * model whose state is {@link ModelState#UNKNOWN} is listed, never eligible. A row whose {@code dataModel} is not a publication urn is ignored.
	 *
	 * @param pModelStates publication name to state; a publication missing from the map is {@code UNKNOWN}
	 */
	public static List<Finding> classify(final List<BusinessObject> pBusinessObjects, final List<TableConfig> pTableConfigs, final Map<String, ModelState> pModelStates,
		final Set<String> pLiveProjectModels, final Set<String> pNamed)
	{
		final Map<String, List<BusinessObject>> bosBy = new LinkedHashMap<String, List<BusinessObject>>();
		final Map<String, List<TableConfig>> tcsBy = new LinkedHashMap<String, List<TableConfig>>();
		for (final BusinessObject bo : pBusinessObjects)
		{
			final String publication = MatchingConfigRows.publicationOf(bo.dataModel);
			if (publication != null)
			{
				MatchingConfigRows.listOf(bosBy, publication).add(bo);
			}
		}
		for (final TableConfig tc : pTableConfigs)
		{
			final String publication = MatchingConfigRows.publicationOf(tc.dataModel);
			if (publication != null)
			{
				MatchingConfigRows.listOf(tcsBy, publication).add(tc);
			}
		}
		final Set<String> publications = new java.util.TreeSet<String>();
		publications.addAll(bosBy.keySet());
		publications.addAll(tcsBy.keySet());
		final List<Finding> out = new ArrayList<Finding>();
		for (final String publication : publications)
		{
			final ModelState state = pModelStates.get(publication) == null ? ModelState.UNKNOWN : pModelStates.get(publication);
			final boolean named = pNamed != null && pNamed.contains(publication);
			if (state != ModelState.GONE && !named)
			{
				continue;
			}
			final List<BusinessObject> bos = bosBy.get(publication) == null ? new ArrayList<BusinessObject>() : bosBy.get(publication);
			final List<TableConfig> tcs = tcsBy.get(publication) == null ? new ArrayList<TableConfig>() : tcsBy.get(publication);
			final boolean owned = pLiveProjectModels != null && pLiveProjectModels.contains(publication);
			out.add(MatchingConfigRows.judge(publication, state, bos, tcs, owned, named));
		}
		return out;
	}

	private static Finding judge(final String pPublication, final ModelState pState, final List<BusinessObject> pBos, final List<TableConfig> pTcs, final boolean pOwned,
		final boolean pNamed)
	{
		if (pState == ModelState.UNKNOWN)
		{
			return new Finding(pPublication, pState, pBos, pTcs, pOwned, pNamed, false, Action.NOTHING,
				"the data-model server could not say whether the model " + pPublication + " exists: nothing is done until it can.");
		}
		if (pState == ModelState.EXISTS && pOwned)
		{
			return new Finding(pPublication, pState, pBos, pTcs, pOwned, pNamed, false, Action.NOTHING,
				"the model " + pPublication + " exists and is the master model of a live Factory project: delete that project instead.");
		}
		if (pState == ModelState.EXISTS)
		{
			return new Finding(pPublication, pState, pBos, pTcs, pOwned, pNamed, !pBos.isEmpty() || !pTcs.isEmpty(), Action.UNLINK_DELETE_AND_REMOVE_TABLE_CONFIGS,
				pBos.isEmpty() && pTcs.isEmpty() ? "no row of this publication in the add-on's dataset." : null);
		}
		if (pBos.isEmpty())
		{
			return new Finding(pPublication, pState, pBos, pTcs, pOwned, pNamed, false, Action.NOTHING,
				pTcs.size() + " table configuration(s) of a model that no longer exists stay: deleting them leaves their hidden merge policies pointing to nothing and the next "
					+ "start fails; they are harmless without a business-object row.");
		}
		return new Finding(pPublication, pState, pBos, pTcs, pOwned, pNamed, true, Action.UNLINK_AND_DELETE_BUSINESS_OBJECTS,
			pTcs.isEmpty() ? null : pTcs.size() + " table configuration(s) stay (unlinked from their business object): deleting them breaks the next start.");
	}

	// ------------------------------------------------------------------------------------------------------------- the sweep's request

	/** Publications accepted by one deletion call. */
	public static final int MAX_PUBLICATIONS = 100;
	private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.\\-]{1,120}");

	/** Why a deletion body {@code {"publications":[...],"confirm":"n"}} is unusable; null when it is fine. */
	public static String bodyProblem(final com.fasterxml.jackson.databind.JsonNode pBody)
	{
		if (pBody == null || !pBody.isObject())
		{
			return "The body must be a JSON object {\"publications\":[...],\"confirm\":\"<count>\"}.";
		}
		final com.fasterxml.jackson.databind.JsonNode names = pBody.path("publications");
		if (!names.isArray() || names.size() == 0)
		{
			return "\"publications\" must be a non-empty array of publication names (for example E2EMASTER20261018_Master).";
		}
		if (names.size() > MatchingConfigRows.MAX_PUBLICATIONS)
		{
			return "At most " + MatchingConfigRows.MAX_PUBLICATIONS + " publications per call.";
		}
		for (final com.fasterxml.jackson.databind.JsonNode one : names)
		{
			if (!one.isTextual() || !MatchingConfigRows.isPublicationName(one.asText().trim()))
			{
				return "Every publication must be a bare name of letters, digits, '_', '.' or '-' (not a urn).";
			}
		}
		return null;
	}

	/** A bare publication name: no urn prefix, no space, no slash. */
	public static boolean isPublicationName(final String pName)
	{
		return pName != null && MatchingConfigRows.NAME.matcher(pName).matches();
	}

	/** The names of a deletion body, deduplicated, in order; null when {@link #bodyProblem} finds the body unusable. */
	public static List<String> namesOf(final com.fasterxml.jackson.databind.JsonNode pBody)
	{
		if (MatchingConfigRows.bodyProblem(pBody) != null)
		{
			return null;
		}
		final Set<String> names = new java.util.LinkedHashSet<String>();
		for (final com.fasterxml.jackson.databind.JsonNode one : pBody.path("publications"))
		{
			names.add(one.asText().trim());
		}
		return new ArrayList<String>(names);
	}

	/** The confirm guard: {@code confirm} must be exactly the number of the named publications that are eligible NOW (decimal digits). Null when it matches. */
	public static String confirmRefusal(final String pConfirm, final int pEligibleCount)
	{
		final String confirm = pConfirm == null ? "" : pConfirm.trim();
		if (!confirm.matches("[0-9]{1,6}"))
		{
			return "confirm must be the number of publications to clean (" + pEligibleCount + " of the names sent are eligible now); nothing was changed.";
		}
		if (Integer.parseInt(confirm) != pEligibleCount)
		{
			return "confirm says " + confirm + " but " + pEligibleCount + " of the names sent are eligible now; nothing was changed.";
		}
		return null;
	}

	private static <T> List<T> listOf(final Map<String, List<T>> pMap, final String pKey)
	{
		List<T> list = pMap.get(pKey);
		if (list == null)
		{
			list = new ArrayList<T>();
			pMap.put(pKey, list);
		}
		return list;
	}

	/**
	 * The e2e cleanup check: every row the add-on still holds for {@code pPublication}, one sentence each; empty when the add-on holds nothing of it. A table
	 * configuration of a publication whose model is gone counts: after a project deletion with the {@code matching} option NOTHING should remain.
	 */
	public static List<String> remaining(final String pPublication, final List<BusinessObject> pBusinessObjects, final List<TableConfig> pTableConfigs)
	{
		final String urn = MatchingConfigRows.urnOf(pPublication);
		final List<String> out = new ArrayList<String>();
		for (final BusinessObject bo : pBusinessObjects)
		{
			if (urn.equals(bo.dataModel))
			{
				out.add("business object configuration " + bo.id + " (main table " + bo.mainTable + ")");
			}
		}
		for (final TableConfig tc : pTableConfigs)
		{
			if (urn.equals(tc.dataModel))
			{
				out.add("table configuration " + tc.id + " (" + tc.table + (tc.businessObjectId.isEmpty() ? "" : ", linked to business object " + tc.businessObjectId) + ")");
			}
		}
		return out;
	}

	/** The sentence of the deletion answer when rows of the publication are still in the add-on's dataset after the matching items ran. */
	public static String leftSentence(final String pPublication, final int pBusinessObjects, final int pTableConfigs, final boolean pModelOrDataspaceDeleted)
	{
		return "The add-on's dataset Bebx-addon-mame/ebx-addon-mame-configuration still holds " + pBusinessObjects + " business-object configuration row(s) and " + pTableConfigs
			+ " table configuration row(s) of " + MatchingConfigRows.urnOf(pPublication) + (pModelOrDataspaceDeleted ? ", whose model or dataspace was deleted" : "")
			+ ". A business-object row left without its model breaks the add-on at the next start: GET /matching/orphans?publication=" + pPublication
			+ " lists them and POST /matching/orphans/delete removes them."
			+ (pTableConfigs > 0 ? " Table configurations that stay behind with \"Table not found\" usually mean the master model no longer compiles: the distribution triggers of a real-time "
				+ "target read the add-on's linked field (Linked_Field_State), which disappears with the main table's configuration." : "");
	}
}
