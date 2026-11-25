package com.on.ebx.factory.distribution.product;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure planner for the "Data product" step of the Distribution wizard (SPEC-distribution.md &sect;7c,
 * lot 2). Turns a project's master tables, their child relations, crosswalk availability, the catalogue
 * assets a person adds, and the choices made on the mockup board ({@code DataProduct.dc.html}) into:
 * the tables the product will carry (their published name, whether a child is folded into its parent
 * as a nested JSON column or kept as its own table), a contract preview (columns/types/source field),
 * the list of objects the write side ({@code ebx.DataProductPublication}) will create, and every
 * validation problem found before anything is written.
 * <p>
 * No EBX API, no Jackson: {@link #plan} takes and returns only the classes declared here, so it is
 * unit-tested directly ({@code ProductPlanTest}). {@code ebx.DataProductPublication} is the only caller;
 * it resolves {@link Candidate}s from the project's master model and the data catalog's asset list, then
 * re-runs this same planner server-side on the browser's {@link Draft} - the browser is never trusted,
 * same discipline as {@code ProductWizard.problems} in ebx-data-catalog (read for reference, not
 * imported: CLAUDE.md's own "reads the ... dataset as data" pattern, {@code ebx.CatalogReader}'s own
 * Javadoc - this module has no compile dependency on ebx-data-catalog or ebx-connectors beyond the
 * mapping-engine subset already vendored as {@code on-ebx-mapping.jar}, and neither ships the classes
 * this planner would need).
 * <p>
 * <b>Cross-references are a SECOND root mapping on the SAME master table, not a separate physical
 * table</b> (coordinator review, 2026-09-24 - corrects the first version of this class, which tried to
 * publish the Factory's own shared {@code Xref} table and could not scope it to one project). A master
 * table keeps one row PER SOURCE RECORD (its own {@code sourceSystem}/{@code sourceId} pass-through
 * fields, {@code MasterFieldNameRule.RESERVED}) together with its Match and Merge state, so a crosswalk
 * candidate ({@link #SOURCE_CROSSWALK}) always resolves to the SAME {@code dataspace}/{@code dataset}/
 * {@code tablePath} as its host {@link Candidate#parentKey} master entity - never a shared, unscoped
 * table - and is per-project by construction (the master table itself is per-project). The write side
 * builds it as a second Connectors OUTBOUND root mapping on that same EBX table, with input
 * {@code crosswalk=true} (which implies {@code mergedRecords=include} - every source record, not golden
 * only) and per-column transformations {@code golden(...)}/{@code mdmState(...)} (ebx-connectors'
 * {@code core/GoldenRecords}, {@code MameGoldenRecords}) resolving each row's own golden id and
 * consolidation state. The data catalog's own {@code PortCoverage} already has a dedicated rule for
 * exactly this shape (a mapping's {@code crosswalk} input, read from the {@code <mappingId>|crosswalk}
 * {@code MappingInput} row): such a mapping is excluded from its "published twice" check in both
 * directions (it targets the SAME EBX table as the master mapping) but still counts as covering that
 * table's own member asset - so no separate catalogue asset is needed for a crosswalk table.
 */
public final class ProductPlan
{
	private ProductPlan()
	{
	}

	public static final String SOURCE_MASTER = "MASTER";
	public static final String SOURCE_CROSSWALK = "CROSSWALK";
	public static final String SOURCE_CATALOG = "CATALOG_ASSET";

	public static final String TARGET_SNOWFLAKE = "SNOWFLAKE";
	public static final String TARGET_DATABRICKS = "DATABRICKS";
	public static final String TARGET_EBX_ONLY = "EBX_ONLY";

	public static final String NEST_OWN = "OWN";
	public static final String NEST_NESTED = "NESTED";

	/**
	 * Refresh choices offered by the screen, mapped to the data catalog's {@code OutputPort.maxLagMinutes}/
	 * {@code autoPublish} (coordinator review, 2026-09-24 - replaces a first version of this lot that
	 * created its own Scheduler REST schedule and workflow launcher; the catalogue already has a sweep
	 * task for exactly this, {@code PortSlaTask}/{@code PortSla}, lot 26 of ebx-data-catalog: it republishes
	 * an {@code autoPublish} port once its age nears {@code maxLagMinutes} - see {@link #maxLagMinutes}).
	 */
	public static final String REFRESH_ON_DEMAND = "ON_DEMAND";
	public static final String REFRESH_HOURLY = "HOURLY";
	public static final String REFRESH_DAILY = "DAILY";
	public static final String REFRESH_WEEKLY = "WEEKLY";

	/**
	 * Mirrors ebx-data-catalog's {@code ProductWizard.CODE_PATTERN} exactly (read there 2026-09-24, not
	 * imported - see the class Javadoc): the catalogue itself is the final authority on a product code,
	 * this is only an early, same-shaped check so a person sees the same refusal before round-tripping.
	 */
	public static final String CODE_PATTERN = "[A-Z][A-Z0-9_]{1,59}";

	// --------------------------------------------------------------------------------------------------------- input

	/** One column a candidate table would contribute to the contract. */
	public static final class Column
	{
		public final String name;
		/** {@code STRING}/{@code INTEGER}/{@code DECIMAL}/{@code BOOLEAN}/{@code DATE}/{@code TIMESTAMP}; blank/null reads as {@code STRING}. */
		public final String dataType;
		/** e.g. {@code Person.lastName} - shown in the contract preview's "From" column. */
		public final String sourceField;

		public Column(final String pName, final String pDataType, final String pSourceField)
		{
			this.name = pName;
			this.dataType = pDataType;
			this.sourceField = pSourceField;
		}
	}

	/**
	 * One table {@link #plan} may include in the product, as resolved by the caller before planning -
	 * this class never looks anything up itself.
	 */
	public static final class Candidate
	{
		/** {@link #SOURCE_MASTER}, {@link #SOURCE_CROSSWALK} or {@link #SOURCE_CATALOG}. */
		public final String source;
		/** The key a {@link TableChoice}/{@code addedCatalogAssetIds} entry names: a master entity name, a crosswalk key ({@code "<Entity>__xref"}), or a catalog asset id. */
		public final String key;
		/** Shown as the asset's display name ("Person", "Address", "Cross-references", "Country"). */
		public final String label;
		/** "Project master" / "Project matching" / "Catalogue - Reference" - the mockup's "From" chip text; informational only. */
		public final String origin;
		public final List<Column> columns;
		/** Rows, or -1 when unknown. */
		public final long rowCount;
		/** True for a MASTER candidate that is a CHILD of another included MASTER candidate ({@link #parentKey} then names it) - offered the OWN/NESTED choice. */
		public final boolean child;
		/**
		 * For a child MASTER candidate: the parent MASTER candidate's own {@link #key}. For a
		 * {@link #SOURCE_CROSSWALK} candidate: the HOST master entity's own {@link #key} (whose
		 * {@code dataspace}/{@code dataset}/{@code tablePath} this candidate's own fields already equal -
		 * see the class Javadoc). Null for a root master candidate or a catalogue asset.
		 */
		public final String parentKey;
		/** The field on this table that holds the parent's key, when {@link #child} is true (the foreign-key field name on the master table) - used to build the nested/child Connectors mapping. Null otherwise, including for a crosswalk candidate. */
		public final String parentLinkField;
		/** False only for {@link #SOURCE_CATALOG}: a non-EBX asset is listed but never published by EBX (mockup note). */
		public final boolean ebxTable;
		/**
		 * {@link #SOURCE_MASTER}/{@link #SOURCE_CROSSWALK} only: dataspace/dataset/tablePath of the real EBX
		 * table, for the write side. A {@link #SOURCE_CROSSWALK} candidate carries the SAME three values as
		 * its host master candidate (it is a second mapping on that SAME table, never a table of its own).
		 * Null for {@link #SOURCE_CATALOG} (resolved from the catalogue's own Asset row instead).
		 */
		public final String dataspace;
		public final String dataset;
		public final String tablePath;

		public Candidate(final String pSource, final String pKey, final String pLabel, final String pOrigin, final List<Column> pColumns, final long pRowCount,
			final boolean pChild, final String pParentKey, final String pParentLinkField, final boolean pEbxTable, final String pDataspace, final String pDataset,
			final String pTablePath)
		{
			this.source = pSource;
			this.key = pKey;
			this.label = pLabel;
			this.origin = pOrigin == null ? "" : pOrigin;
			this.columns = pColumns == null ? new ArrayList<Column>() : pColumns;
			this.rowCount = pRowCount;
			this.child = pChild;
			this.parentKey = pParentKey;
			this.parentLinkField = pParentLinkField;
			this.ebxTable = pEbxTable;
			this.dataspace = pDataspace;
			this.dataset = pDataset;
			this.tablePath = pTablePath;
		}
	}

	/** One table's ticked/unticked state and, for a child table, its OWN/NESTED choice, as posted by the screen. */
	public static final class TableChoice
	{
		public final String key;
		public final boolean included;
		/** {@link #NEST_OWN} (default) or {@link #NEST_NESTED}; ignored (and never a problem) for a non-child candidate. */
		public final String nestMode;

		public TableChoice(final String pKey, final boolean pIncluded, final String pNestMode)
		{
			this.key = pKey;
			this.included = pIncluded;
			this.nestMode = pNestMode == null || pNestMode.trim().isEmpty() ? ProductPlan.NEST_OWN : pNestMode.trim().toUpperCase(Locale.ROOT);
		}
	}

	/** Everything the "Publish as a data product" screen collects. Every text is trimmed, never null. */
	public static final class Draft
	{
		public String productName = "";
		public String code = "";
		public String owner = "";
		public String domain = "";
		public String description = "";
		/** {@link #TARGET_SNOWFLAKE}, {@link #TARGET_DATABRICKS} or {@link #TARGET_EBX_ONLY}. */
		public String target = ProductPlan.TARGET_EBX_ONLY;
		/** The Connectors connector id to publish through; required unless {@link #target} is {@link #TARGET_EBX_ONLY} (one connection can carry several products - the screen offers whichever reachable connections {@code ebx.DataProductPublication#detect} found). */
		public String connectorId = "";
		/** Required unless {@link #target} is {@link #TARGET_EBX_ONLY}, e.g. {@code EBX_DATA_PRODUCTS.PERSONNES}. */
		public String schema = "";
		/** {@link #REFRESH_ON_DEMAND} (default), {@link #REFRESH_HOURLY}, {@link #REFRESH_DAILY} or {@link #REFRESH_WEEKLY} - see {@link #maxLagMinutes}. */
		public String refreshMode = ProductPlan.REFRESH_ON_DEMAND;
		public boolean marketplaceListing;
		public List<TableChoice> tables = new ArrayList<TableChoice>();
		/** Catalogue asset ids added through "Add assets from the catalogue", beyond the default master/crosswalk candidates. */
		public List<String> addedCatalogAssetIds = new ArrayList<String>();
	}

	/** {@link Draft#refreshMode} in minutes ({@code OutputPort.maxLagMinutes}); null for {@link #REFRESH_ON_DEMAND} (no automatic republish - {@code autoPublish} stays false). Public and pure so both the planner and {@code ebx.DataProductPublication} read the same numbers. */
	public static Integer maxLagMinutes(final String pRefreshMode)
	{
		final String mode = pRefreshMode == null ? ProductPlan.REFRESH_ON_DEMAND : pRefreshMode.trim().toUpperCase(Locale.ROOT);
		if (ProductPlan.REFRESH_HOURLY.equals(mode))
		{
			return Integer.valueOf(60);
		}
		if (ProductPlan.REFRESH_DAILY.equals(mode))
		{
			return Integer.valueOf(24 * 60);
		}
		if (ProductPlan.REFRESH_WEEKLY.equals(mode))
		{
			return Integer.valueOf(7 * 24 * 60);
		}
		return null;
	}

	// -------------------------------------------------------------------------------------------------------- output

	public static final class PlannedTable
	{
		public final String source;
		public final String key;
		public final String label;
		/** The table/column-group name EBX will publish under, e.g. {@code PERSON}, {@code PERSON_XREF}. */
		public final String publishedName;
		/** True when a CHILD candidate was chosen NESTED: no table of its own, folded into {@link #parentPublishedName} as a JSON column. */
		public final boolean nested;
		/** Only set when {@link #nested}: the parent table's own {@link #publishedName}. */
		public final String parentPublishedName;
		public final List<Column> columns;
		public final long rowCount;
		public final boolean ebxTable;

		PlannedTable(final String pSource, final String pKey, final String pLabel, final String pPublishedName, final boolean pNested,
			final String pParentPublishedName, final List<Column> pColumns, final long pRowCount, final boolean pEbxTable)
		{
			this.source = pSource;
			this.key = pKey;
			this.label = pLabel;
			this.publishedName = pPublishedName;
			this.nested = pNested;
			this.parentPublishedName = pParentPublishedName;
			this.columns = pColumns;
			this.rowCount = pRowCount;
			this.ebxTable = pEbxTable;
		}
	}

	/** One reason the draft cannot be created yet: the field to mark and a message-bundle key ({@code distribution.product.error.*}) for a future screen to resolve, plus a plain English detail for a caller with no bundle (the REST answer). */
	public static final class Problem
	{
		public final String field;
		public final String key;
		public final String detail;

		public Problem(final String pField, final String pKey, final String pDetail)
		{
			this.field = pField;
			this.key = pKey;
			this.detail = pDetail;
		}

		@Override
		public String toString()
		{
			return this.field + ":" + this.key + ":" + this.detail;
		}
	}

	public static final class Result
	{
		/** Every table selected for the contract, including one nested child - order: masters/crosswalk first (candidate order), then added catalogue assets. */
		public final List<PlannedTable> tables;
		/** Sum of {@link PlannedTable#columns} sizes over every non-nested table (what the mockup's "N tables - M columns" chip shows); a nested child's columns are folded into its parent's own count as ONE json column, not added again. */
		public final int contractColumnCount;
		/** "What EBX will create", one bullet per line - empty when {@link #problems} is non-empty. */
		public final List<String> creationSteps;
		public final List<Problem> problems;
		/** Non-blocking notes. */
		public final List<String> warnings;

		Result(final List<PlannedTable> pTables, final int pContractColumnCount, final List<String> pCreationSteps, final List<Problem> pProblems,
			final List<String> pWarnings)
		{
			this.tables = pTables;
			this.contractColumnCount = pContractColumnCount;
			this.creationSteps = pCreationSteps;
			this.problems = pProblems;
			this.warnings = pWarnings;
		}

		public boolean ok()
		{
			return this.problems.isEmpty();
		}
	}

	// --------------------------------------------------------------------------------------------------------- plan

	public static Result plan(final Draft pDraft, final List<Candidate> pCandidates)
	{
		final List<Problem> problems = new ArrayList<Problem>();
		final List<String> warnings = new ArrayList<String>();

		ProductPlan.validateIdentity(pDraft, problems);
		ProductPlan.validateChannel(pDraft, problems);

		final java.util.Map<String, Candidate> byKey = new java.util.LinkedHashMap<String, Candidate>();
		for (final Candidate candidate : pCandidates)
		{
			byKey.put(candidate.key, candidate);
		}
		final java.util.Map<String, TableChoice> choiceByKey = new java.util.LinkedHashMap<String, TableChoice>();
		for (final TableChoice choice : pDraft.tables)
		{
			if (!byKey.containsKey(choice.key))
			{
				problems.add(new Problem("tables", "distribution.product.error.unknownTable", "Unknown table '" + choice.key + "'."));
				continue;
			}
			choiceByKey.put(choice.key, choice);
		}
		for (final String assetId : pDraft.addedCatalogAssetIds)
		{
			final Candidate candidate = byKey.get(assetId);
			if (candidate == null || !ProductPlan.SOURCE_CATALOG.equals(candidate.source))
			{
				problems.add(new Problem("addedCatalogAssetIds", "distribution.product.error.unknownAsset", "Unknown catalogue asset '" + assetId + "'."));
				continue;
			}
			if (!choiceByKey.containsKey(assetId))
			{
				choiceByKey.put(assetId, new TableChoice(assetId, true, ProductPlan.NEST_OWN));
			}
		}

		final List<PlannedTable> tables = new ArrayList<PlannedTable>();
		final Set<String> publishedNames = new LinkedHashSet<String>();
		int included = 0;
		for (final Candidate candidate : pCandidates)
		{
			final TableChoice choice = choiceByKey.get(candidate.key);
			final boolean isIncluded = choice != null && choice.included;
			if (!isIncluded)
			{
				continue;
			}
			included++;
			final String nestMode = choice.nestMode;
			if (ProductPlan.NEST_NESTED.equals(nestMode) && !candidate.child)
			{
				problems.add(new Problem("tables", "distribution.product.error.notChild",
					candidate.label + " is not a child table: it cannot be nested into another one."));
				continue;
			}
			final boolean nested = candidate.child && ProductPlan.NEST_NESTED.equals(nestMode);
			final String publishedName = nested ? null : ProductPlan.sanitize(candidate.label);
			if (!nested)
			{
				if (!publishedNames.add(publishedName))
				{
					problems.add(new Problem("tables", "distribution.product.error.duplicateTable", "Table " + publishedName + " would be published twice."));
					continue;
				}
			}
			final String parentPublishedName;
			if (nested)
			{
				final Candidate parent = byKey.get(candidate.parentKey);
				final TableChoice parentChoice = parent == null ? null : choiceByKey.get(parent.key);
				if (parent == null || parentChoice == null || !parentChoice.included)
				{
					problems.add(new Problem("tables", "distribution.product.error.parentNotIncluded",
						candidate.label + " is nested into " + (parent == null ? candidate.parentKey : parent.label) + ", which is not included."));
					continue;
				}
				parentPublishedName = ProductPlan.sanitize(parent.label);
			}
			else
			{
				parentPublishedName = null;
			}
			if (ProductPlan.SOURCE_CROSSWALK.equals(candidate.source))
			{
				final Candidate host = byKey.get(candidate.parentKey);
				if (host == null || choiceByKey.get(host.key) == null || !choiceByKey.get(host.key).included)
				{
					problems.add(new Problem("tables", "distribution.product.error.parentNotIncluded",
						candidate.label + " needs its master table (" + (host == null ? candidate.parentKey : host.label) + ") to be included too."));
					continue;
				}
			}
			tables.add(new PlannedTable(candidate.source, candidate.key, candidate.label, publishedName, nested, parentPublishedName, candidate.columns,
				candidate.rowCount, candidate.ebxTable));
		}
		if (included == 0)
		{
			problems.add(new Problem("tables", "distribution.product.error.noTable", "Select at least one table."));
		}

		int contractColumns = 0;
		for (final PlannedTable table : tables)
		{
			if (!table.nested)
			{
				contractColumns += table.columns.size();
			}
		}

		if (problems.isEmpty() && ProductPlan.maxLagMinutes(pDraft.refreshMode) != null && !ProductPlan.TARGET_EBX_ONLY.equals(pDraft.target))
		{
			warnings.add("Refresh: the actual republish cadence depends on the data catalog's own PortSlaTask sweep (its schedule and lead time), "
				+ "not exactly the maximum-lag value below - not verified live, see docs/DISTRIBUTION-PRODUCT.md.");
		}
		final List<String> creationSteps = problems.isEmpty() ? ProductPlan.creationSteps(pDraft, tables) : new ArrayList<String>();
		return new Result(tables, contractColumns, creationSteps, problems, warnings);
	}

	private static void validateIdentity(final Draft pDraft, final List<Problem> pProblems)
	{
		if (pDraft.productName == null || pDraft.productName.trim().isEmpty())
		{
			pProblems.add(new Problem("productName", "distribution.product.error.nameRequired", "Product name is required."));
		}
		final String code = pDraft.code == null ? "" : pDraft.code.trim();
		if (code.isEmpty())
		{
			pProblems.add(new Problem("code", "distribution.product.error.codeRequired", "Code is required."));
		}
		else if (!code.matches(ProductPlan.CODE_PATTERN))
		{
			pProblems.add(new Problem("code", "distribution.product.error.codeInvalid", "Code must match " + ProductPlan.CODE_PATTERN + "."));
		}
		if (pDraft.owner == null || pDraft.owner.trim().isEmpty())
		{
			pProblems.add(new Problem("owner", "distribution.product.error.ownerRequired", "Owner is required."));
		}
	}

	private static void validateChannel(final Draft pDraft, final List<Problem> pProblems)
	{
		final String target = pDraft.target == null ? "" : pDraft.target.trim().toUpperCase(Locale.ROOT);
		if (!ProductPlan.TARGET_SNOWFLAKE.equals(target) && !ProductPlan.TARGET_DATABRICKS.equals(target) && !ProductPlan.TARGET_EBX_ONLY.equals(target))
		{
			pProblems.add(new Problem("target", "distribution.product.error.target", "Target must be SNOWFLAKE, DATABRICKS or EBX_ONLY."));
			return;
		}
		if (!ProductPlan.TARGET_EBX_ONLY.equals(target) && (pDraft.schema == null || pDraft.schema.trim().isEmpty()))
		{
			pProblems.add(new Problem("schema", "distribution.product.error.schemaRequired", "A target schema is required for " + target + "."));
		}
		if (!ProductPlan.TARGET_EBX_ONLY.equals(target) && (pDraft.connectorId == null || pDraft.connectorId.trim().isEmpty()))
		{
			pProblems.add(new Problem("connectorId", "distribution.product.error.connectorRequired", "A connector is required for " + target + "."));
		}
	}

	private static List<String> creationSteps(final Draft pDraft, final List<PlannedTable> pTables)
	{
		final List<String> out = new ArrayList<String>();
		int assetCount = 0;
		int mappableTables = 0;
		for (final PlannedTable table : pTables)
		{
			if (!table.nested)
			{
				assetCount++;
			}
			if (!table.nested && table.ebxTable)
			{
				mappableTables++;
			}
		}
		final boolean ebxOnly = ProductPlan.TARGET_EBX_ONLY.equals(pDraft.target);
		final String portCode = pDraft.code + "." + pDraft.target;
		out.add("Data product " + pDraft.productName + " with its " + assetCount + " asset(s)"
			+ (ebxOnly ? "" : " and its output port " + portCode) + " in the Data Catalog.");
		if (!ebxOnly)
		{
			out.add("One publication mapping per publishable table (" + mappableTables + " of " + assetCount + "), a cross-references table adding a "
				+ "second mapping on its host master table.");
			final Integer maxLag = ProductPlan.maxLagMinutes(pDraft.refreshMode);
			if (maxLag == null)
			{
				out.add("On demand only: no automatic republish (autoPublish stays off); only the first publication runs, launched right away.");
			}
			else
			{
				out.add("Automatic republish: maxLagMinutes=" + maxLag + ", autoPublish=true (the catalogue's own PortSlaTask sweep republishes it "
					+ "before that lag is reached).");
			}
			if (pDraft.marketplaceListing)
			{
				out.add("Marketplace listing: requested - applied by the connector's own 'listing.enabled' setting when it publishes "
					+ "(this lot cannot create the listing itself; see docs/DISTRIBUTION-PRODUCT.md).");
			}
		}
		return out;
	}

	/** Upper case, {@code [^A-Za-z0-9_]} to {@code _}, a leading digit prefixed with {@code _}; mirrors ebx-connectors' {@code ProductPublisher.sanitizeIdentifier} (read there, not imported - see class Javadoc). Public: {@code ebx.DataProductPublication} reuses it to derive a published column/table name from a raw label before this planner even sees it (candidate resolution), not only inside {@link #plan}. */
	public static String sanitize(final String pRaw)
	{
		String id = pRaw == null ? "" : pRaw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Za-z0-9_]", "_");
		if (id.isEmpty())
		{
			id = "TABLE";
		}
		if (Character.isDigit(id.charAt(0)))
		{
			id = "_" + id;
		}
		return id;
	}
}
