package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.core.WriterGuard.Expectation;
import com.on.ebx.factory.core.WriterGuard.Kind;
import com.on.ebx.factory.core.WriterGuard.Writer;
import com.on.ebx.factory.core.WriterShapes;
import com.on.ebx.factory.master.MasterToolbars;
import com.on.ebx.factory.master.ToolbarSubtreeDiff;
import com.on.ebx.factory.master.ToolbarSubtreeDiff.ExistingNode;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Writes a {@link MasterToolbars} definition into {@code ebx-dma/<model>/root/toolbarNode} - the internal
 * Java API technique documented in CLAUDE.md &sect;6/&sect;12 (a DMA model's toolbars are ordinary rows of a
 * technical dataset, {@code ebx-dma} is filtered from {@code data-access} discovery but a child dataspace of
 * every publication - {@code DMA_<timestamp>} - stays visible for inspection) and &sect;9 (a repeatable,
 * non-table complex group maps to a plain {@code java.util.List}, built with {@code
 * SchemaNode.createNewOccurrence()}/{@code executeWrite}, never a sub-occurrence table).
 * <p>
 * <b>Field paths and their exact Java-mapped types below were read live, 2026-09-22</b>, {@code
 * mcp__data-access__describe_table} on {@code DMA_2026-09-20T22:00:55.756}/{@code Companies}/
 * {@code /root/toolbarNode} - see {@link MasterToolbars}'s own Javadoc for the full citation. One trap this
 * class exists to avoid, found the hard way in the precedent this is modelled on ({@code ebx-perspective-
 * admin}'s {@code PerspectiveService.setLabel}, `~/Dev/ebx-perspective-admin`): the {@code
 * documentation/localizedDocumentations/locale} field's Java-mapped type is {@code java.util.Locale}, NOT
 * {@code String} - a plain {@code "en-US"} string throws {@code IllegalArgumentException} ("argument type
 * mismatch") deep inside the setter. {@link #locale(String)} is the one place this class converts a
 * {@code MasterToolbars.LocalizedText#locale} tag into that type.
 * <p>
 * <b>Idempotent by toolbar NAME AND CONTENT, since 2026-09-22 (toolbar module-level services lot) -
 * supersedes the original "leave it alone" rule below.</b> {@link #write} looks each of the three toolbar
 * roots up by {@code ./type='TOOLBAR' and ./toolbar/name=<quoted>} - a root NOT found is created exactly as
 * before. A root that IS found is now read back into a {@link ToolbarSubtreeDiff.ExistingNode} tree ({@link
 * #readSubtree}) and compared against the definition with {@link ToolbarSubtreeDiff#differences} - identical
 * (empty differences) reports {@code EXISTS} and leaves the rows untouched, exactly the original behaviour;
 * ANY difference deletes the whole existing subtree ({@link #deleteSubtree}, children first, then the root -
 * {@code ProcedureContext.doDelete(AdaptationName, boolean)}, confirmed by {@code javap} against {@code
 * ebx-lib.jar} to accept a RECORD's own {@link Adaptation#getAdaptationName()}, not only a dataset's - the
 * local Java API doc's own wording, "Deletes the specified dataset or record", read 2026-09-22) and recreates
 * it fresh from the definition, inside the SAME procedure, reporting {@code REPLACED} with the differences
 * found. <b>Why this changed</b>: this lot's own incident (`docs/MASTER-ZONE.md` &sect;12) changed the
 * CONTENT of the Cockpit/Proposal review buttons under the SAME toolbar names an earlier deploy had already
 * written on {@code Companies_Master} - the original "already exists by name, leave it alone forever" rule
 * would have left that broken content in place on every already-generated master model, with no way for a
 * later {@code master/generate} call to ever fix it. <b>The replaced root's own {@code id} changes</b> - this
 * is fine, {@code set_toolbars} binds a table to a toolbar by NAME (see {@code ebx.MasterGenerator}'s own
 * call, always re-run on every promoted table regardless of what {@link #write} reports), never by id.
 * <p>
 * Never throws to the caller beyond the {@link Result} it returns - one entry per toolbar, {@code CREATED}/
 * {@code EXISTS}/{@code REPLACED}/{@code FAILED}, so {@code ebx.MasterGenerator} can record it in the route's
 * own {@code detail}/{@code notes} without this class needing to know anything about that shape.
 * <p>
 * <b>The read-back comparison technique is INFERRED, not measured live</b> (this lot's own constraint,
 * read-only MCP tools throughout): {@link #readSubtree} reads {@code actionButton/service/serviceParameters}
 * (a repeatable group) the same way CLAUDE.md &sect;9 documents for any repeatable group read off a real
 * {@link Adaptation} - {@code Adaptation.getSchemaNode().getNode(path)} to resolve the sub-field {@code
 * SchemaNode}s, then {@code SchemaNode.executeRead(Object)} (the read counterpart of the {@code executeWrite}
 * {@link #localizedDocs}/{@link #serviceParams} already use, confirmed to exist by the same {@code javap}
 * pass) per occurrence - never independently exercised against a real {@code toolbarNode} row.
 * <p>
 * The session this writes with is whatever the caller (ultimately {@code ebx.MasterGenerator.generate}'s own
 * {@code pSession} parameter) hands in - never the factory's separate MCP agent account, the same rule
 * {@code ebx.PipelineSteps} already follows for its own {@code Project}/{@code PipelineStep} writes. Writing
 * into {@code ebx-dma} this way has never been exercised against a running instance by this lot (constrained
 * to read-only MCP tools) - the biggest single unverified assumption in this file is whether the session
 * handed to {@code master/generate} genuinely carries write rights on the technical {@code ebx-dma} home (an
 * administrator session does, on every instance checked so far in this codebase's other technical-dataspace
 * writes - {@code ebx-manager} in {@code ebx-perspective-admin} - but that is a different technical home, not
 * this one specifically).
 * <p>
 * <b>Menu button icon-default incident, measured live 2026-09-22, 16:27-16:29, {@code Companies_Master} -
 * fixed the same day.</b> With everything above already deployed and working (toolbars written, migrated by
 * {@link #replaceRoot}, bound, buttons functional), {@code kernel.log} logged, on EVERY rendering of a master
 * table or record (4 lines seen at 16:29, unchanged since the very first version of this class at 15:20):
 * {@code ERROR log.kernel ... Menu Button  is configured with an icon but it has no icon reference.} Read
 * directly off the publication snapshot {@code DMA_2026-09-22T16:27:48.469}/{@code Companies_Master}/
 * {@code /root/toolbarNode} with {@code preview_records}: the two {@code MENU_BUTTON} rows ("Actions", ids
 * 328 and 339) carried {@code menuButton/display/icon/type = "css"} with {@code
 * menuButton/display/icon/reference = null} - {@link #createNode}'s {@code MENU_BUTTON} branch never wrote
 * anything under {@code ./menuButton/display/icon/*} at all (only {@link #MB_DOCS}, {@link #MB_LAYOUT}, {@link
 * #MB_HIGHLIGHTED}), so the schema's own {@code icon/type} facet default ({@code "css"}, applied the moment
 * {@code getContextForNewOccurrence} instantiates the {@code display} group) silently survived onto every
 * MENU_BUTTON row this class ever created. By contrast, the toolbars EBX's own UI creates (model
 * {@code Companies}, row id 4 in the visible snapshot {@code DMA_2026-09-20T22:00:55.756}, dataset
 * {@code Companies}) have {@code icon/type = null} and {@code reference = null} on their "Actions" menu
 * button, and the shipped {@code webapps/ebx-assistant/WEB-INF/ebx/schemas/
 * mcp-servers-administration_1.0_toolbars.xml} writes a menu button {@code display} with {@code layout} +
 * {@code highlightedButton} and NO {@code icon} element at all - the bug was specific to this writer, not a
 * property of the data itself. Fixed by writing {@code pNode.iconType}/{@code pNode.iconReference}
 * unconditionally in the {@code MENU_BUTTON} branch of {@link #createNode} (both null today, since no
 * menu-button definition in {@code MasterToolbars} sets them - see that branch's own Javadoc for why an
 * explicit null there is expected to override the schema default, and for the caveat that this specific write
 * was never re-run live). {@link ToolbarSubtreeDiff} now also compares a {@code MENU_BUTTON}'s icon type
 * (against the definition's own {@code iconType}, expected null) so the next {@code master/generate} REPLACES
 * the two toolbars holding an "Actions" menu (table-top, record-top) instead of leaving the already-broken,
 * defaulted rows in place forever under the name-based {@code EXISTS} shortcut - {@link #readSubtree} reads it
 * back for exactly that comparison. {@link MasterToolbars#TABLE_ROW_NAME} has no menu button and is expected
 * to keep reporting {@code EXISTS} unaffected.
 */
final class DmaToolbarWriter
{
	private static final HomeKey EBX_DMA_HOME = HomeKey.forBranchName("ebx-dma");
	private static final Path TOOLBAR_NODE_TABLE = Path.parse("/root/toolbarNode");

	private static final Path ID = Path.parse("./id");
	private static final Path PARENT = Path.parse("./parent");
	private static final Path TYPE = Path.parse("./type");
	private static final Path ORDERING = Path.parse("./ordering");

	private static final Path TOOLBAR_NAME = Path.parse("./toolbar/name");
	private static final Path TOOLBAR_DOCS = Path.parse("./toolbar/documentation/localizedDocumentations");
	private static final Path TOOLBAR_LOCATION = Path.parse("./toolbar/location");

	private static final Path AB_SERVICE_KEY = Path.parse("./actionButton/service/serviceKey");
	private static final Path AB_TARGET = Path.parse("./actionButton/service/target");
	private static final Path AB_MODAL_SIZE = Path.parse("./actionButton/service/modalSize");
	private static final Path AB_RESIZABLE = Path.parse("./actionButton/service/resizable");
	private static final Path AB_SERVICE_PARAMETERS = Path.parse("./actionButton/service/serviceParameters");
	private static final Path AB_DOCS = Path.parse("./actionButton/documentation/localizedDocumentations");
	private static final Path AB_LAYOUT = Path.parse("./actionButton/display/layout");
	private static final Path AB_ICON_TYPE = Path.parse("./actionButton/display/icon/type");
	private static final Path AB_ICON_REFERENCE = Path.parse("./actionButton/display/icon/reference");
	private static final Path AB_HIGHLIGHTED = Path.parse("./actionButton/display/highlightedButton");

	private static final Path SEP_WIDTH = Path.parse("./separator/width");

	private static final Path MB_DOCS = Path.parse("./menuButton/documentation/localizedDocumentations");
	private static final Path MB_LAYOUT = Path.parse("./menuButton/display/layout");
	private static final Path MB_HIGHLIGHTED = Path.parse("./menuButton/display/highlightedButton");
	private static final Path MB_ICON_TYPE = Path.parse("./menuButton/display/icon/type");
	private static final Path MB_ICON_REFERENCE = Path.parse("./menuButton/display/icon/reference");

	private static final Path MG_TYPE = Path.parse("./menuGroup/type");
	private static final Path MG_BUILDER_KEY = Path.parse("./menuGroup/menuBuilder/menuBuilderKey");
	private static final Path MG_EXCLUDED_SERVICE_GROUPS = Path.parse("./menuGroup/menuBuilder/excludedServiceGroups");
	private static final Path MG_EXCLUDED_SERVICES = Path.parse("./menuGroup/menuBuilder/excludedServices");
	private static final Path MG_FILTERING_POLICY = Path.parse("./menuGroup/menuBuilder/filteringPolicy");

	private static final Path DOC_LOCALE = Path.parse("locale");
	private static final Path DOC_LABEL = Path.parse("label");
	private static final Path PARAM_NAME = Path.parse("name");
	private static final Path PARAM_VALUE = Path.parse("value");

	private DmaToolbarWriter()
	{
	}

	/**
	 * Audit F-06: every field of {@code /root/toolbarNode} this writer writes or reads back, with the kind it treats it as - measured on the
	 * 6.3.0 RC2 publication snapshot {@code DMA_2026-09-27T11:43:54.080} (read-only {@code describe_table}, 2026-10-07). What {@link WriterShapes#refusal}
	 * compares with the running DMA before {@link #write} touches a row; {@code DmaToolbarWriterGuardTest} keeps it in step with the {@code Path} constants above.
	 */
	static List<Expectation> expectations()
	{
		final String t = "/root/toolbarNode";
		final List<Expectation> out = new ArrayList<Expectation>();
		out.add(Expectation.of(t, Kind.TABLE));
		out.add(Expectation.of(t + "/id", Kind.OTHER));
		out.add(Expectation.of(t + "/parent", Kind.OTHER));
		out.add(Expectation.of(t + "/type", Kind.STRING));
		out.add(Expectation.of(t + "/ordering", Kind.INT));
		out.add(Expectation.of(t + "/toolbar/name", Kind.STRING));
		DmaToolbarWriter.addLocalizedDocs(out, t + "/toolbar/documentation/localizedDocumentations");
		out.add(Expectation.of(t + "/toolbar/location", Kind.LIST));
		out.add(Expectation.of(t + "/actionButton/service/serviceKey", Kind.STRING));
		out.add(Expectation.of(t + "/actionButton/service/target", Kind.STRING));
		out.add(Expectation.of(t + "/actionButton/service/modalSize", Kind.STRING));
		out.add(Expectation.of(t + "/actionButton/service/resizable", Kind.BOOLEAN));
		out.add(Expectation.of(t + "/actionButton/service/serviceParameters", Kind.LIST));
		out.add(Expectation.of(t + "/actionButton/service/serviceParameters/name", Kind.OTHER));
		out.add(Expectation.of(t + "/actionButton/service/serviceParameters/value", Kind.STRING));
		DmaToolbarWriter.addLocalizedDocs(out, t + "/actionButton/documentation/localizedDocumentations");
		out.add(Expectation.of(t + "/actionButton/display/layout", Kind.STRING));
		out.add(Expectation.of(t + "/actionButton/display/icon/type", Kind.STRING));
		out.add(Expectation.of(t + "/actionButton/display/icon/reference", Kind.STRING));
		out.add(Expectation.of(t + "/actionButton/display/highlightedButton", Kind.BOOLEAN));
		out.add(Expectation.of(t + "/separator/width", Kind.INT));
		DmaToolbarWriter.addLocalizedDocs(out, t + "/menuButton/documentation/localizedDocumentations");
		out.add(Expectation.of(t + "/menuButton/display/layout", Kind.STRING));
		out.add(Expectation.of(t + "/menuButton/display/highlightedButton", Kind.BOOLEAN));
		out.add(Expectation.of(t + "/menuButton/display/icon/type", Kind.STRING));
		out.add(Expectation.of(t + "/menuButton/display/icon/reference", Kind.STRING));
		out.add(Expectation.of(t + "/menuGroup/type", Kind.STRING));
		out.add(Expectation.of(t + "/menuGroup/menuBuilder/menuBuilderKey", Kind.STRING));
		out.add(Expectation.of(t + "/menuGroup/menuBuilder/excludedServiceGroups", Kind.LIST));
		out.add(Expectation.of(t + "/menuGroup/menuBuilder/excludedServices", Kind.LIST));
		out.add(Expectation.of(t + "/menuGroup/menuBuilder/filteringPolicy", Kind.STRING));
		return out;
	}

	private static void addLocalizedDocs(final List<Expectation> pOut, final String pGroupPath)
	{
		pOut.add(Expectation.of(pGroupPath, Kind.LIST));
		pOut.add(Expectation.of(pGroupPath + "/locale", Kind.LOCALE));
		pOut.add(Expectation.of(pGroupPath + "/label", Kind.STRING));
	}

	/** One toolbar's outcome - see class Javadoc. {@code status} is one of {@code CREATED}/{@code EXISTS}/{@code REPLACED}/{@code FAILED}. */
	static final class ToolbarOutcome
	{
		final String name;
		final String status;
		final String rowId;
		final String error;
		/** Non-null only on {@code REPLACED} - {@link ToolbarSubtreeDiff#differences}' own lines, joined, explaining what no longer matched. */
		final String detail;

		private ToolbarOutcome(final String pName, final String pStatus, final String pRowId, final String pError, final String pDetail)
		{
			this.name = pName;
			this.status = pStatus;
			this.rowId = pRowId;
			this.error = pError;
			this.detail = pDetail;
		}
	}

	static final class Result
	{
		final List<ToolbarOutcome> outcomes = new ArrayList<ToolbarOutcome>();

		/** True as soon as at least one toolbar root was newly {@code CREATED} or {@code REPLACED} this call - {@code ebx.MasterGenerator} uses this to force a republish on a regeneration call that would otherwise take the UP_TO_DATE fast path (the same defensive pattern already applied to the MAME add-on declaration and {@code MasterFacetRepair} - a model regenerated for the first time since this lot shipped, or since a later content fix, must not skip past a toolbar set it has never actually written in its current form). Renamed from {@code anyCreated} (2026-09-22, toolbar module-level services lot) now that a name match no longer guarantees nothing changed. */
		boolean anyChanged;
	}

	/**
	 * @param pSession write session (see class Javadoc - never the MCP agent account)
	 * @param pModelName the master data model's name, e.g. {@code Companies_Master} - also the dataset name
	 *        under {@code ebx-dma} ({@code ebx-dma/<pModelName>})
	 * @param pRoots the three (or fewer, in a test) toolbar definitions to ensure exist - {@link
	 *        MasterToolbars#build}'s own output, never re-derived here
	 */
	static Result write(final Session pSession, final String pModelName, final List<MasterToolbars.Node> pRoots) throws OperationException
	{
		final Repository repository = Repository.getDefault();
		final AdaptationHome dmaHome = repository.lookupHome(DmaToolbarWriter.EBX_DMA_HOME);
		if (dmaHome == null)
		{
			throw EngineRefusal.coded("missing.dmaSpace", "The technical dataspace ebx-dma does not exist on this repository.");
		}
		final Adaptation dataset = dmaHome.findAdaptationOrNull(AdaptationName.forName(pModelName));
		if (dataset == null)
		{
			throw EngineRefusal.coded("missing.dmaModel", "ebx-dma/" + pModelName + " does not exist yet - the master data model must be built and published first.", pModelName);
		}
		// audit F-06: the paths below were read off one build's toolbarNode table - refuse before writing a row when this build differs
		final String refusal = WriterShapes.refusal(Writer.DMA_TOOLBARS, pSession.getLocale(), dataset.getSchemaNode(), DmaToolbarWriter.expectations());
		if (refusal != null)
		{
			throw EngineRefusal.coded("conflict.refused", refusal);
		}
		final AdaptationTable toolbarTable = dataset.getTable(DmaToolbarWriter.TOOLBAR_NODE_TABLE);

		final Result result = new Result();
		for (final MasterToolbars.Node root : pRoots)
		{
			final Adaptation existingRoot = DmaToolbarWriter.findExistingRoot(toolbarTable, root.toolbarName);
			if (existingRoot == null)
			{
				DmaToolbarWriter.createRoot(pSession, dmaHome, toolbarTable, root, result);
				continue;
			}
			final List<String> differences = ToolbarSubtreeDiff.differences(root, DmaToolbarWriter.readSubtree(toolbarTable, existingRoot));
			if (differences.isEmpty())
			{
				result.outcomes.add(new ToolbarOutcome(root.toolbarName, "EXISTS", String.valueOf(existingRoot.get(DmaToolbarWriter.ID)), null, null));
				continue;
			}
			DmaToolbarWriter.replaceRoot(pSession, dmaHome, toolbarTable, root, existingRoot, differences, result);
		}
		return result;
	}

	/** {@code CREATED} - the original, unchanged code path (no prior root by this name). */
	private static void createRoot(final Session pSession, final AdaptationHome pDmaHome, final AdaptationTable pToolbarTable, final MasterToolbars.Node pRoot,
		final Result pResult)
	{
		final String[] createdId = new String[1];
		final ProcedureResult procedureResult = ProgrammaticService.createForSession(pSession, pDmaHome).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				createdId[0] = DmaToolbarWriter.createNode(pContext, pToolbarTable, pRoot, null);
			}
		});
		if (procedureResult.hasFailed())
		{
			pResult.outcomes.add(new ToolbarOutcome(pRoot.toolbarName, "FAILED", null, procedureResult.getExceptionFullMessage(Locale.ENGLISH), null));
		}
		else
		{
			pResult.outcomes.add(new ToolbarOutcome(pRoot.toolbarName, "CREATED", createdId[0], null, null));
			pResult.anyChanged = true;
		}
	}

	/**
	 * {@code REPLACED} - a root by this name exists but no longer matches the definition (class Javadoc's own
	 * migration section): deletes the whole existing subtree (children first, then the root - {@link
	 * #deleteSubtree}) and recreates it fresh from the definition, both inside the SAME procedure, so a
	 * mid-way failure never leaves the toolbar half-deleted.
	 */
	private static void replaceRoot(final Session pSession, final AdaptationHome pDmaHome, final AdaptationTable pToolbarTable, final MasterToolbars.Node pRoot,
		final Adaptation pExistingRoot, final List<String> pDifferences, final Result pResult)
	{
		final String[] createdId = new String[1];
		final ProcedureResult procedureResult = ProgrammaticService.createForSession(pSession, pDmaHome).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				DmaToolbarWriter.deleteSubtree(pContext, pToolbarTable, pExistingRoot);
				createdId[0] = DmaToolbarWriter.createNode(pContext, pToolbarTable, pRoot, null);
			}
		});
		if (procedureResult.hasFailed())
		{
			pResult.outcomes.add(new ToolbarOutcome(pRoot.toolbarName, "FAILED", null, procedureResult.getExceptionFullMessage(Locale.ENGLISH), null));
		}
		else
		{
			pResult.outcomes.add(new ToolbarOutcome(pRoot.toolbarName, "REPLACED", createdId[0], null, DmaToolbarWriter.join(pDifferences)));
			pResult.anyChanged = true;
		}
	}

	private static String join(final List<String> pLines)
	{
		final StringBuilder out = new StringBuilder();
		for (final String line : pLines)
		{
			if (out.length() > 0)
			{
				out.append(" | ");
			}
			out.append(line);
		}
		return out.toString();
	}

	private static Adaptation findExistingRoot(final AdaptationTable pTable, final String pName)
	{
		final RequestResult rows = pTable.createRequestResult("./type='TOOLBAR' and ./toolbar/name=" + CatalogReader.quote(pName));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	// ---------------------------------------------------------------------------------------------- read (comparison)

	/**
	 * Recursively reads one existing row (and, via {@code ./parent=<id>}, its children) into a {@link
	 * ToolbarSubtreeDiff.ExistingNode} tree, for {@link ToolbarSubtreeDiff#differences} to compare against the
	 * definition - class Javadoc's own "INFERRED, not measured live" caveat applies to this whole method.
	 * Children are gathered unordered (no assumption that {@code createRequestResult} supports an "order by"
	 * clause - none of this codebase's own XPath usage relies on one) then sorted by {@code ordering} in Java,
	 * matching the strictly-increasing order {@link MasterToolbars} itself builds a definition's children in
	 * ({@code MasterToolbarsTest.tableTop_orderingsStrictlyIncreasing}) - required for {@link
	 * ToolbarSubtreeDiff}'s own positional (not identity-based) child comparison to line up correctly.
	 */
	private static ExistingNode readSubtree(final AdaptationTable pTable, final Adaptation pRow)
	{
		final String type = pRow.getString(DmaToolbarWriter.TYPE);
		final Object rawOrdering = pRow.get(DmaToolbarWriter.ORDERING);
		final Integer ordering = rawOrdering instanceof Number ? Integer.valueOf(((Number) rawOrdering).intValue()) : null;

		String serviceKey = null;
		String target = null;
		List<String[]> serviceParameters = Collections.<String[]> emptyList();
		if ("ACTION_BUTTON".equals(type))
		{
			serviceKey = pRow.getString(DmaToolbarWriter.AB_SERVICE_KEY);
			target = pRow.getString(DmaToolbarWriter.AB_TARGET);
			serviceParameters = DmaToolbarWriter.readServiceParameters(pRow);
		}
		String menuBuilderKey = null;
		if ("GROUP_MENU_ITEM".equals(type))
		{
			menuBuilderKey = pRow.getString(DmaToolbarWriter.MG_BUILDER_KEY);
		}
		String menuButtonIconType = null;
		if ("MENU_BUTTON".equals(type))
		{
			// class Javadoc's "icon-default incident" - the one field the migration comparison must now catch:
			// a row written before the fix still carries the schema's own defaulted "css" here.
			menuButtonIconType = pRow.getString(DmaToolbarWriter.MB_ICON_TYPE);
		}

		final List<Adaptation> childRowsList = new ArrayList<Adaptation>();
		final String rowId = String.valueOf(pRow.get(DmaToolbarWriter.ID));
		final RequestResult childRows = pTable.createRequestResult("./parent=" + CatalogReader.quote(rowId));
		try
		{
			for (Adaptation child = childRows.nextAdaptation(); child != null; child = childRows.nextAdaptation())
			{
				childRowsList.add(child);
			}
		}
		finally
		{
			childRows.close();
		}
		Collections.sort(childRowsList, new Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pA, final Adaptation pB)
			{
				return DmaToolbarWriter.orderingOf(pA).compareTo(DmaToolbarWriter.orderingOf(pB));
			}
		});
		final List<ExistingNode> children = new ArrayList<ExistingNode>();
		for (final Adaptation child : childRowsList)
		{
			children.add(DmaToolbarWriter.readSubtree(pTable, child));
		}

		return new ExistingNode(type, ordering, serviceKey, target, serviceParameters, menuBuilderKey, menuButtonIconType, children);
	}

	private static Integer orderingOf(final Adaptation pRow)
	{
		final Object raw = pRow.get(DmaToolbarWriter.ORDERING);
		return raw instanceof Number ? Integer.valueOf(((Number) raw).intValue()) : Integer.valueOf(Integer.MAX_VALUE);
	}

	/**
	 * {@code actionButton/service/serviceParameters}: the read counterpart of {@link #serviceParams} -
	 * {@code Adaptation.getSchemaNode().getNode(path)} to resolve the sub-field {@code SchemaNode}s (CLAUDE.md
	 * &sect;9's own "guard generic reads" technique, applied here to a group rather than a scalar field), then
	 * {@code SchemaNode.executeRead(Object)} per occurrence - confirmed to exist by {@code javap} against
	 * {@code ebx-lib.jar} (symmetric with {@code executeWrite}), never independently exercised against a real
	 * row (class Javadoc's own caveat).
	 */
	private static List<String[]> readServiceParameters(final Adaptation pRow)
	{
		final List<?> raw = pRow.getList(DmaToolbarWriter.AB_SERVICE_PARAMETERS);
		if (raw == null || raw.isEmpty())
		{
			return Collections.<String[]> emptyList();
		}
		final SchemaNode groupNode = pRow.getSchemaNode().getNode(DmaToolbarWriter.AB_SERVICE_PARAMETERS);
		if (groupNode == null)
		{
			return Collections.<String[]> emptyList(); // defensive - CLAUDE.md sec 9's own "guard generic reads" rule
		}
		final SchemaNode nameNode = groupNode.getNode(DmaToolbarWriter.PARAM_NAME);
		final SchemaNode valueNode = groupNode.getNode(DmaToolbarWriter.PARAM_VALUE);
		final List<String[]> out = new ArrayList<String[]>();
		for (final Object occurrence : raw)
		{
			final Object name = nameNode.executeRead(occurrence);
			final Object value = valueNode.executeRead(occurrence);
			out.add(new String[] { name == null ? null : String.valueOf(name), value == null ? null : String.valueOf(value) });
		}
		return out;
	}

	// -------------------------------------------------------------------------------------------------------- delete

	/**
	 * Deletes {@code pRow} and, recursively, every row pointing at it via {@code parent} - children FIRST,
	 * then the row itself (class Javadoc's own migration section: this is not a genuine EBX hierarchy, just a
	 * self-referencing {@code parent} field, so {@code doDelete}'s own {@code isDeletingChildren} flag is not
	 * relied on - children are walked and deleted explicitly instead, re-querying live inside the procedure
	 * rather than trusting a list gathered before the transaction started).
	 */
	private static void deleteSubtree(final ProcedureContext pContext, final AdaptationTable pTable, final Adaptation pRow) throws Exception
	{
		final String rowId = String.valueOf(pRow.get(DmaToolbarWriter.ID));
		final List<Adaptation> children = new ArrayList<Adaptation>();
		final RequestResult childRows = pTable.createRequestResult("./parent=" + CatalogReader.quote(rowId));
		try
		{
			for (Adaptation child = childRows.nextAdaptation(); child != null; child = childRows.nextAdaptation())
			{
				children.add(child);
			}
		}
		finally
		{
			childRows.close();
		}
		for (final Adaptation child : children)
		{
			DmaToolbarWriter.deleteSubtree(pContext, pTable, child);
		}
		pContext.doDelete(pRow.getAdaptationName(), false);
	}

	/** Creates one row (and, recursively, its children) - {@code id} is auto-increment, never set explicitly; read back off the created {@link Adaptation} so children can point their own {@code parent} at it (formatted as a plain string, the same convention {@code ebx-perspective-admin}'s own self-referencing {@code menuItem.parent} field uses). */
	private static String createNode(final ProcedureContext pContext, final AdaptationTable pTable, final MasterToolbars.Node pNode, final String pParentId)
		throws Exception
	{
		final ValueContextForUpdate row = pContext.getContextForNewOccurrence(pTable);
		row.setValue(pNode.type, DmaToolbarWriter.TYPE);
		if (pParentId != null)
		{
			row.setValue(pParentId, DmaToolbarWriter.PARENT);
		}
		if (pNode.ordering != null)
		{
			row.setValue(pNode.ordering, DmaToolbarWriter.ORDERING);
		}

		if ("TOOLBAR".equals(pNode.type))
		{
			row.setValue(pNode.toolbarName, DmaToolbarWriter.TOOLBAR_NAME);
			row.setValue(DmaToolbarWriter.localizedDocs(row, DmaToolbarWriter.TOOLBAR_DOCS, pNode.documentation), DmaToolbarWriter.TOOLBAR_DOCS);
			row.setValue(pNode.locations, DmaToolbarWriter.TOOLBAR_LOCATION);
		}
		else if ("ACTION_BUTTON".equals(pNode.type))
		{
			row.setValue(pNode.serviceKey, DmaToolbarWriter.AB_SERVICE_KEY);
			row.setValue(pNode.target, DmaToolbarWriter.AB_TARGET);
			row.setValue(pNode.modalSize, DmaToolbarWriter.AB_MODAL_SIZE);
			row.setValue(Boolean.valueOf(pNode.resizable), DmaToolbarWriter.AB_RESIZABLE);
			if (!pNode.serviceParameters.isEmpty())
			{
				row.setValue(DmaToolbarWriter.serviceParams(row, pNode.serviceParameters), DmaToolbarWriter.AB_SERVICE_PARAMETERS);
			}
			row.setValue(DmaToolbarWriter.localizedDocs(row, DmaToolbarWriter.AB_DOCS, pNode.documentation), DmaToolbarWriter.AB_DOCS);
			row.setValue(pNode.layout, DmaToolbarWriter.AB_LAYOUT);
			row.setValue(pNode.iconType, DmaToolbarWriter.AB_ICON_TYPE);
			row.setValue(pNode.iconReference, DmaToolbarWriter.AB_ICON_REFERENCE);
			row.setValue(Boolean.valueOf(pNode.highlightedButton), DmaToolbarWriter.AB_HIGHLIGHTED);
		}
		else if ("SEPARATOR".equals(pNode.type))
		{
			row.setValue(pNode.separatorWidth, DmaToolbarWriter.SEP_WIDTH);
		}
		else if ("MENU_BUTTON".equals(pNode.type))
		{
			row.setValue(DmaToolbarWriter.localizedDocs(row, DmaToolbarWriter.MB_DOCS, pNode.documentation), DmaToolbarWriter.MB_DOCS);
			row.setValue(pNode.layout, DmaToolbarWriter.MB_LAYOUT);
			row.setValue(Boolean.valueOf(pNode.highlightedButton), DmaToolbarWriter.MB_HIGHLIGHTED);
			// icon - written LAST (class Javadoc's "icon-default incident, 2026-09-22 16:27-16:29"): the
			// `display` group's `icon/type` facet defaults to "css" the moment `getContextForNewOccurrence`
			// instantiates the group, and this class never used to write anything under
			// `./menuButton/display/icon/*` at all, so that default silently survived onto every MENU_BUTTON
			// row - kernel.log then logged, on every rendering of a master table or record, "Menu Button is
			// configured with an icon but it has no icon reference" (the two "Actions" menu buttons, rows 328
			// and 339 of `DMA_2026-09-22T16:27:48.469`/`Companies_Master`, had `icon/type="css"`,
			// `icon/reference=null`). `pNode.iconType`/`pNode.iconReference` are the SAME fields `MasterToolbars
			// .Node` already declares for "display (actionButton / menuButton)" - no menu-button definition
			// sets them today (see `MasterToolbars.actionsMenu`), so writing them unconditionally here (the
			// same unconditional-write pattern the ACTION_BUTTON branch below already uses for its own icon
			// fields) explicitly stores null and overrides the schema default, exactly like clearing any other
			// optional field on a brand-new occurrence. The local Java API doc of {@code
			// ValueContextForUpdate.setValue(Object, Path)} (`onHelp/advanced/Java_API/...`, read 2026-09-22)
			// does not special-case a schema-defaulted value at all - its only documented null-related failure
			// is passing {@code AdaptationValue.INHERIT_VALUE} under a terminal node, unrelated here - and this
			// codebase already relies on `setValue(null, path)` to blank an optional field elsewhere
			// (`CapabilityApplier`, `MasterReset`), just never before on a brand-new occurrence specifically;
			// nothing found suggests a freshly-created occurrence behaves differently from an existing record
			// for this call, and `icon` is optional (not `mandatory`) on this group, so no constraint blocks an
			// explicit null either. Unverified: this specific write was never re-run live (this lot's own
			// read-only-MCP-tools constraint, same as the rest of `docs/MASTER-ZONE.md` &sect;12) - if a null
			// somehow failed to stick on a new occurrence, the fallback would be to write the group's `display`
			// value as a whole (mirroring {@link #localizedDocs}'s own group-level `SchemaNode.executeWrite`
			// technique) rather than per-leaf, since {@code setValueEnablingPrivilegeForNode} only affects
			// permission evaluation, not value defaulting, and is not an alternative to this.
			row.setValue(pNode.iconType, DmaToolbarWriter.MB_ICON_TYPE);
			row.setValue(pNode.iconReference, DmaToolbarWriter.MB_ICON_REFERENCE);
		}
		else if ("GROUP_MENU_ITEM".equals(pNode.type))
		{
			row.setValue(pNode.menuGroupType, DmaToolbarWriter.MG_TYPE);
			row.setValue(pNode.menuBuilderKey, DmaToolbarWriter.MG_BUILDER_KEY);
			row.setValue(pNode.excludedServiceGroups, DmaToolbarWriter.MG_EXCLUDED_SERVICE_GROUPS);
			row.setValue(pNode.excludedServices, DmaToolbarWriter.MG_EXCLUDED_SERVICES);
			row.setValue(pNode.filteringPolicy, DmaToolbarWriter.MG_FILTERING_POLICY);
		}

		final Adaptation created = pContext.doCreateOccurrence(row, pTable);
		final String newId = String.valueOf(created.get(DmaToolbarWriter.ID));
		for (final MasterToolbars.Node child : pNode.children)
		{
			DmaToolbarWriter.createNode(pContext, pTable, child, newId);
		}
		return newId;
	}

	/** {@code documentation/localizedDocumentations}: {@code locale} (mapped to {@link Locale}, see class Javadoc) + {@code label} only - this table has no use for {@code description}. */
	private static List<Object> localizedDocs(final ValueContextForUpdate pRow, final Path pGroupPath, final List<MasterToolbars.LocalizedText> pTexts)
	{
		final SchemaNode groupNode = pRow.getNode(pGroupPath);
		final SchemaNode localeNode = groupNode.getNode(DmaToolbarWriter.DOC_LOCALE);
		final SchemaNode labelNode = groupNode.getNode(DmaToolbarWriter.DOC_LABEL);
		final List<Object> out = new ArrayList<Object>();
		for (final MasterToolbars.LocalizedText text : pTexts)
		{
			final Object occurrence = groupNode.createNewOccurrence();
			localeNode.executeWrite(DmaToolbarWriter.locale(text.locale), occurrence);
			if (text.label != null)
			{
				labelNode.executeWrite(text.label, occurrence);
			}
			out.add(occurrence);
		}
		return out;
	}

	/** {@code actionButton/service/serviceParameters}: {@code name} + {@code value}, both plain strings. */
	private static List<Object> serviceParams(final ValueContextForUpdate pRow, final List<MasterToolbars.ServiceParameter> pParams)
	{
		final SchemaNode groupNode = pRow.getNode(DmaToolbarWriter.AB_SERVICE_PARAMETERS);
		final SchemaNode nameNode = groupNode.getNode(DmaToolbarWriter.PARAM_NAME);
		final SchemaNode valueNode = groupNode.getNode(DmaToolbarWriter.PARAM_VALUE);
		final List<Object> out = new ArrayList<Object>();
		for (final MasterToolbars.ServiceParameter param : pParams)
		{
			final Object occurrence = groupNode.createNewOccurrence();
			nameNode.executeWrite(param.name, occurrence);
			valueNode.executeWrite(param.value, occurrence);
			out.add(occurrence);
		}
		return out;
	}

	/** {@code en-US}/{@code fr-FR} (BCP 47 tags, matching what {@code preview_records} showed on the real dataset) -&gt; {@link Locale#forLanguageTag(String)}, never a plain {@link String} (see class Javadoc). */
	private static Locale locale(final String pTag)
	{
		return Locale.forLanguageTag(pTag);
	}
}
