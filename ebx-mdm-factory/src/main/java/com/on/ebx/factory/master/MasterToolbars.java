package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The DEFINITION (plain Java objects) of the three DMA toolbars {@code ebx.MasterGenerator} writes into a
 * project's master model - {@code ebx.DmaToolbarWriter} is what actually turns a {@link Node} tree into rows
 * of {@code ebx-dma/<model>/root/toolbarNode}; this class only decides their SHAPE, so it can be unit-tested
 * without a running instance (no EBX import anywhere in this file), the same convention as {@link
 * MasterModel}/{@code staging.StagingModel} themselves.
 * <p>
 * <b>The task this closes</b> (the product owner, 2026-09-22): a steward looking at a promoted master table today only
 * reaches "Record lineage" through the record's generic Actions menu; every promoted table of a project's
 * master model must instead get a TABLE toolbar and a RECORD toolbar with a DIRECT button for "Record
 * lineage".
 * <p>
 * <b>Where each fact below comes from - read live, 2026-09-22, by the coordinator and this lot together, not
 * guessed</b>:
 * <ul>
 * <li>The {@code toolbarNode} table shape ({@code type}, {@code parent}, {@code ordering}, one group per
 * type) - {@code mcp__data-access__describe_table} on {@code DMA_2026-09-20T22:00:55.756}/{@code Companies}/
 * {@code /root/toolbarNode}, and the SAME table's real rows - {@code mcp__data-access__preview_records} on
 * the same dataset (toolbar {@code "Companies"} at {@code TABLE_VIEW_TOP}, {@code "CompanyRecordToolbar"} at
 * {@code RECORD_VIEW_TOP}): the default-part reproduction below (an {@code @creation} button, a separator, an
 * "Actions" {@code MENU_BUTTON} whose one child is a {@code GROUP_MENU_ITEM} of
 * {@code menuBuilderKey=@actions_menu_with_separator}, {@code excludedServices=[@inherit, @overwrite,
 * @gridEditor, @workflowLauncher]}, {@code filteringPolicy=SMART_FILTERING}) is copied verbatim from those two
 * real toolbars, field for field.
 * <li>A {@code TABLE_VIEW_ROW} example - the same instance's shipped export
 * {@code CompaniesForMohcine_toolbars.xml} (a "Record row" toolbar, one {@code ICON_ONLY} {@code
 * ACTION_BUTTON}, {@code target=c}, no default-part reproduction - a row toolbar decorates one already-visible
 * record, not a blank table) - {@link #tableRow} mirrors that shape exactly.
 * <li>{@code set_toolbars}'s argument names - <b>CHANGED 2026-10-12</b>: the server now takes {@code element_path}
 * (or {@code type_name}), {@code locations}, {@code toolbar}, one call per toolbar (see {@link #bindings} and
 * {@link #setToolbarsArguments}); the 2026-09-22 shape ({@code element_path}, {@code tabular_view_top}, {@code
 * tabular_view_row}, {@code record_top}, plus {@code tile_view}/{@code hierarchy_view_top}) survives only as
 * {@link #legacySetToolbarsArguments} - {@code mcp__data-model__list_operations(name="set_toolbars")}, category {@code customforms},
 * behavior {@code ADDITIVE_RETRYABLE}; its own contract warns a wrong toolbar name or a toolbar bound to a
 * location it was not built for fails SILENTLY (no toolbar shown, nothing reported) - the reason {@code
 * ebx.MasterGenerator} always re-runs it on every promoted table rather than trying to detect "already bound".
 * </ul>
 * <p>
 * <b>Cockpit and Proposal review buttons REMOVED, the product owner's decision, 2026-09-22 16:40</b> - they existed on
 * these toolbars only briefly, between 15:19 and 16:40 the same day (created, then made to work as
 * module-level services after a first failed cross-model attempt, `docs/MASTER-ZONE.md` &sect;12.7-&sect;12.9).
 * the product owner's own reasoning: <i>"Cockpit et la revue des suggestions sont des choses à faire en amont de la
 * génération de la table master. Aucun intérêt de les mettre là."</i> - both screens belong to the staging
 * phase, before a master table even exists, so a button for them on the master table itself never made sense.
 * They move to the factory's own {@code Project} table/record instead (a separate lot, not this one). This
 * class now writes ONLY "Record lineage" on all three toolbars; see {@code
 * ebx.ui.FactoryCockpitOnMasterServiceDeclaration}/{@code ProposalReviewOnMasterServiceDeclaration} in git
 * history (removed by this same commit) for the module-level services this decision made unnecessary.
 * <p>
 * <b>Record lineage in the TABLE-TOP toolbar - the one call this class had to make without a live example.</b>
 * {@code RecordLineageOnRecordServiceDeclaration}'s own Javadoc documents where EBX offers an
 * {@code OnRecord} service registered with {@code setAvailableAsToolbarAction(true)}: "the record form's own
 * Actions menu, and the table view toolbar when exactly one record is selected". The local Java API doc for
 * {@code UserServiceDeclaration.OnRecord}/{@code ActivationContextOnRecord} does not add anything more
 * specific than the interface contract itself, and no {@code *_toolbars.xml} export in this instance places an
 * {@code OnRecord} service as a direct {@code ACTION_BUTTON} at {@code TABLE_VIEW_TOP} (every real example
 * found is either a table-level/module-level service at {@code c}, or the row toolbar's own per-record
 * button). Given that Javadoc line is the existing, already-shipped documentation of this exact service's own
 * behaviour (not this lot's invention), {@link #tableTop} includes "Record lineage" as a direct
 * {@code ACTION_BUTTON} - <b>confirmed live, 2026-09-22, 15:19-15:22</b>: the button rendered and WORKED from
 * the table top, the row, and the record form alike (`docs/MASTER-ZONE.md` &sect;12).
 * <p>
 * Icon is a plain FontAwesome 4 class ({@code type=css}), the same convention as every real button read
 * above: {@code fa-sitemap} (Record lineage) - chosen for legibility, not measured against any real toolbar
 * (none of the factory's services had an icon before this lot).
 */
public final class MasterToolbars
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Bound to {@code TABLE_VIEW_TOP} via {@code set_toolbars}. */
	public static final String TABLE_TOP_NAME = "MdmFactoryTableTop";
	/** Bound to {@code TABLE_VIEW_ROW} via {@code set_toolbars}. */
	public static final String TABLE_ROW_NAME = "MdmFactoryTableRow";
	/** Bound to {@code RECORD_VIEW_TOP} via {@code set_toolbars}. */
	public static final String RECORD_TOP_NAME = "MdmFactoryRecordTop";

	private static final String LOCALE_EN = "en-US";
	private static final String LOCALE_FR = "fr-FR";

	private static final String TARGET_CURRENT_CONTEXT = "c";

	private static final String MODAL_SIZE_LARGE = "l";
	private static final int SEPARATOR_WIDTH = 20;

	/** Must match {@code ebx.ui.RecordLineageOnRecordServiceDeclaration.SERVICE_NAME} - not imported (this class stays EBX-free), guarded instead by {@code MasterToolbarsTest}. */
	private static final String RECORD_LINEAGE_SERVICE = "RecordLineageOnRecord";

	private MasterToolbars()
	{
	}

	// --------------------------------------------------------------------------------------------------------- model

	/** One row of {@code toolbarNode} (a {@code TOOLBAR} root, or a child of a {@code TOOLBAR}/{@code MENU_BUTTON}). */
	public static final class Node
	{
		public final String type;
		/** Null for a {@code TOOLBAR} root (its position is fixed - the dataset's own convention, confirmed live). */
		public final Integer ordering;
		public final List<Node> children = new ArrayList<Node>();

		// toolbar
		public String toolbarName;
		public List<String> locations = Collections.<String> emptyList();

		// shared by toolbar / actionButton / menuButton / actionMenuItem / subMenuItem / menuGroup
		public List<LocalizedText> documentation = Collections.<LocalizedText> emptyList();

		// actionButton / actionMenuItem
		public String serviceKey;
		public String target;
		public String modalSize;
		public boolean resizable;
		public List<ServiceParameter> serviceParameters = Collections.<ServiceParameter> emptyList();

		// display (actionButton / menuButton)
		public String layout;
		public String iconType;
		public String iconReference;
		public boolean highlightedButton;

		// separator
		public Integer separatorWidth;

		// menuGroup (GROUP_MENU_ITEM)
		public String menuGroupType;
		public String menuBuilderKey;
		public List<String> excludedServiceGroups = Collections.<String> emptyList();
		public List<String> excludedServices = Collections.<String> emptyList();
		public String filteringPolicy;

		/** Package-private, not {@code private} (2026-09-22, {@code ToolbarSubtreeDiffTest}): lets that test build small, isolated node trees directly, without a special test-only factory method. */
		Node(final String pType, final Integer pOrdering)
		{
			this.type = pType;
			this.ordering = pOrdering;
		}
	}

	public static final class LocalizedText
	{
		public final String locale;
		public final String label;

		public LocalizedText(final String pLocale, final String pLabel)
		{
			this.locale = pLocale;
			this.label = pLabel;
		}
	}

	public static final class ServiceParameter
	{
		public final String name;
		public final String value;

		public ServiceParameter(final String pName, final String pValue)
		{
			this.name = pName;
			this.value = pValue;
		}
	}

	// --------------------------------------------------------------------------------------------------------- build

	/**
	 * The three toolbars of one project's master model, in the order {@code ebx.DmaToolbarWriter} should
	 * create them - independent of each other (no cross-references), so the order does not matter for
	 * correctness, only for a stable, readable {@code detail} log.
	 * <p>
	 * <b>No project code parameter</b>: the one button these toolbars carry (Record lineage) is a MODULE-level
	 * service, bare {@code <moduleName>@RecordLineageOnRecord}, {@code target=c} - an {@code OnRecord} service
	 * that resolves its own project from the record being viewed, so no project-scoped parameter needs to
	 * travel with the button.
	 *
	 * @param pModuleName the factory module's own name (from {@code module.xml}, not hardcoded at any call
	 *        site) - needed by the Record lineage button, a module-level service
	 */
	public static List<Node> build(final String pModuleName)
	{
		final List<Node> out = new ArrayList<Node>();
		out.add(MasterToolbars.tableTop(pModuleName));
		out.add(MasterToolbars.tableRow(pModuleName));
		out.add(MasterToolbars.recordTop(pModuleName));
		return out;
	}

	/**
	 * {@code TABLE_VIEW_TOP}: the default part (Create, separator, Actions menu) reproduced verbatim from the
	 * real {@code Companies} toolbar (class Javadoc), then one separator-prefixed Record lineage button
	 * (the product owner's brief, "above all Record lineage"). Cockpit/Proposal review were here between 2026-09-22
	 * 15:19 and 16:40 - removed, class Javadoc.
	 */
	static Node tableTop(final String pModuleName)
	{
		final Node root = MasterToolbars.toolbarRoot(MasterToolbars.TABLE_TOP_NAME, "TABLE_VIEW_TOP");
		root.children.add(MasterToolbars.creationButton(1000));
		root.children.add(MasterToolbars.separator(2000));
		root.children.add(MasterToolbars.actionsMenu(3000));
		root.children.add(MasterToolbars.separator(4000));
		root.children.add(MasterToolbars.recordLineageButton(pModuleName, 5000, "TEXT_AND_ICON_LEFT"));
		return root;
	}

	/**
	 * {@code TABLE_VIEW_ROW}: mirrors the instance's own "Record row" toolbar (class Javadoc) - one
	 * icon-only button, no default-part reproduction (a row toolbar decorates an already-visible record, it
	 * has no "create" button of its own in the real example either).
	 */
	static Node tableRow(final String pModuleName)
	{
		final Node root = MasterToolbars.toolbarRoot(MasterToolbars.TABLE_ROW_NAME, "TABLE_VIEW_ROW");
		root.children.add(MasterToolbars.recordLineageButton(pModuleName, 1000, "ICON_ONLY"));
		return root;
	}

	/**
	 * {@code RECORD_VIEW_TOP}: the default part reproduced verbatim from the real {@code CompanyRecordToolbar}
	 * (class Javadoc), then Record lineage. Cockpit was here between 2026-09-22 15:19 and 16:40 - removed,
	 * class Javadoc (Proposal review was never offered here in the first place - reviewing staging proposals
	 * from an already-promoted golden record made no sense, the original brief's own scope).
	 */
	static Node recordTop(final String pModuleName)
	{
		final Node root = MasterToolbars.toolbarRoot(MasterToolbars.RECORD_TOP_NAME, "RECORD_VIEW_TOP");
		root.children.add(MasterToolbars.creationButton(1000));
		root.children.add(MasterToolbars.separator(2000));
		root.children.add(MasterToolbars.actionsMenu(3000));
		root.children.add(MasterToolbars.separator(4000));
		root.children.add(MasterToolbars.recordLineageButton(pModuleName, 5000, "TEXT_AND_ICON_LEFT"));
		return root;
	}

	// ------------------------------------------------------------------------------------------------------ pieces

	private static Node toolbarRoot(final String pName, final String pLocation)
	{
		final Node node = new Node("TOOLBAR", null);
		node.toolbarName = pName;
		node.locations = Collections.singletonList(pLocation);
		node.documentation = Arrays.asList(new LocalizedText(MasterToolbars.LOCALE_EN, null), new LocalizedText(MasterToolbars.LOCALE_FR, null));
		return node;
	}

	/** {@code @creation}, {@code target=c} - copied verbatim from the real Companies/CompanyRecordToolbar roots. */
	private static Node creationButton(final int pOrdering)
	{
		final Node node = new Node("ACTION_BUTTON", Integer.valueOf(pOrdering));
		node.serviceKey = "@creation";
		node.target = MasterToolbars.TARGET_CURRENT_CONTEXT;
		node.modalSize = MasterToolbars.MODAL_SIZE_LARGE;
		node.resizable = false;
		node.documentation = Arrays.asList(new LocalizedText(MasterToolbars.LOCALE_EN, "Create a record"),
			new LocalizedText(MasterToolbars.LOCALE_FR, "Créer un enregistrement"));
		node.layout = "ICON_ONLY";
		node.iconType = "css";
		node.iconReference = "ebx_Add";
		node.highlightedButton = false;
		return node;
	}

	private static Node separator(final int pOrdering)
	{
		final Node node = new Node("SEPARATOR", Integer.valueOf(pOrdering));
		node.separatorWidth = Integer.valueOf(MasterToolbars.SEPARATOR_WIDTH);
		return node;
	}

	/** "Actions" {@code MENU_BUTTON} - copied verbatim from the real toolbars (class Javadoc). */
	private static Node actionsMenu(final int pOrdering)
	{
		final Node menu = new Node("MENU_BUTTON", Integer.valueOf(pOrdering));
		menu.documentation = Arrays.asList(new LocalizedText(MasterToolbars.LOCALE_EN, "Actions"), new LocalizedText(MasterToolbars.LOCALE_FR, "Actions"));
		menu.layout = "TEXT_ONLY";
		menu.highlightedButton = false;

		final Node group = new Node("GROUP_MENU_ITEM", Integer.valueOf(1000));
		group.menuGroupType = "MENU_BUILDER";
		group.menuBuilderKey = "@actions_menu_with_separator";
		group.excludedServiceGroups = Collections.<String> emptyList();
		group.excludedServices = Arrays.asList("@inherit", "@overwrite", "@gridEditor", "@workflowLauncher");
		group.filteringPolicy = "SMART_FILTERING";
		menu.children.add(group);
		return menu;
	}

	/**
	 * {@code <moduleName>@RecordLineageOnRecord}, {@code target=c} - a MODULE-level service
	 * ({@code RecordLineageOnRecordServiceDeclaration}, {@code ServiceKey.forModuleServiceName}), so the
	 * toolbar's {@code serviceKey} needs the {@code <module>@} prefix (CLAUDE.md &sect;12 "ServiceKey
	 * format"/&sect;9 "the module part is null/not applicable" rule, applied in reverse: a module-registered
	 * service DOES carry the prefix). <b>Confirmed working live, 2026-09-22</b> (class Javadoc).
	 */
	private static Node recordLineageButton(final String pModuleName, final int pOrdering, final String pLayout)
	{
		final Node node = new Node("ACTION_BUTTON", Integer.valueOf(pOrdering));
		node.serviceKey = pModuleName + "@" + MasterToolbars.RECORD_LINEAGE_SERVICE;
		node.target = MasterToolbars.TARGET_CURRENT_CONTEXT;
		node.modalSize = MasterToolbars.MODAL_SIZE_LARGE;
		node.resizable = false;
		node.documentation = Arrays.asList(new LocalizedText(MasterToolbars.LOCALE_EN, "Record lineage"),
			new LocalizedText(MasterToolbars.LOCALE_FR, "Lineage de l'enregistrement"));
		node.layout = pLayout;
		node.iconType = "css";
		node.iconReference = "fa-sitemap";
		node.highlightedButton = false;
		return node;
	}

	// ------------------------------------------------------------------------------------------ set_toolbars contract

	/** The three locations this class binds, in binding order. */
	public static final String LOCATION_TABLE_TOP = "TABLE_VIEW_TOP";
	public static final String LOCATION_TABLE_ROW = "TABLE_VIEW_ROW";
	public static final String LOCATION_RECORD_TOP = "RECORD_VIEW_TOP";

	/** One {@code set_toolbars} call of the NEW contract: a toolbar name and the location(s) it is attached to. */
	public static final class Binding
	{
		/** One location, or several comma-separated ({@code set_toolbars}' own {@code locations} syntax). */
		public final String locations;
		public final String toolbar;

		Binding(final String pLocations, final String pToolbar)
		{
			this.locations = pLocations;
			this.toolbar = pToolbar;
		}
	}

	/**
	 * The bindings to make on every promoted table, one {@code set_toolbars} call each (each location holds ONE
	 * toolbar; attaching replaces what was there and leaves the other locations alone). Each toolbar must declare
	 * the location it is attached to - {@link #build} gives every root exactly one location, the same constants.
	 */
	public static List<Binding> bindings()
	{
		return Collections.unmodifiableList(Arrays.asList(
			new Binding(MasterToolbars.LOCATION_TABLE_TOP, MasterToolbars.TABLE_TOP_NAME),
			new Binding(MasterToolbars.LOCATION_TABLE_ROW, MasterToolbars.TABLE_ROW_NAME),
			new Binding(MasterToolbars.LOCATION_RECORD_TOP, MasterToolbars.RECORD_TOP_NAME)));
	}

	/**
	 * The {@code execute_operation(set_toolbars)} arguments of ONE binding, NEW contract (the {@code data-model}
	 * server of 2026-10-12, read live with {@code list_operations(name="set_toolbars")}): {@code element_path}
	 * (or {@code type_name}, exactly one), {@code locations} (required, comma-separated among {@code TABLE_VIEW_TOP,
	 * TABLE_VIEW_ROW, TILE_VIEW, RECORD_VIEW_TOP, HIERARCHICAL_VIEW_TOP, ASSOCIATION_TOP, ASSOCIATION_ROW}),
	 * {@code toolbar} (required, an existing toolbar's name; blank detaches). Pure, so {@code MasterToolbarsTest}
	 * can guard the argument names against that contract.
	 */
	public static ObjectNode setToolbarsArguments(final String pTablePath, final Binding pBinding)
	{
		final ObjectNode args = MasterToolbars.MAPPER.createObjectNode();
		args.put("element_path", pTablePath);
		args.put("locations", pBinding.locations);
		args.put("toolbar", pBinding.toolbar);
		return args;
	}

	/**
	 * The OLD contract (EBX 6.3.0-dev builds before the 2026-10-12 server; {@code element_path} plus one argument
	 * per location, read-modify-write, all three in ONE call) - kept only as the one-shot fallback of {@code
	 * ebx.MasterGenerator#applyToolbars} when a server refuses the new arguments.
	 */
	public static ObjectNode legacySetToolbarsArguments(final String pTablePath)
	{
		final ObjectNode args = MasterToolbars.MAPPER.createObjectNode();
		args.put("element_path", pTablePath);
		args.put("tabular_view_top", MasterToolbars.TABLE_TOP_NAME);
		args.put("tabular_view_row", MasterToolbars.TABLE_ROW_NAME);
		args.put("record_top", MasterToolbars.RECORD_TOP_NAME);
		return args;
	}

	/**
	 * True when {@code pRefusalText} is the {@code data-model} server telling us the argument NAMES are not the ones
	 * it takes - measured live 2026-10-12 for the old shape sent to the new server: {@code Operation 'set_toolbars'
	 * does not take 'tabular_view_top', 'tabular_view_row', 'record_top'. It takes element_path, type_name,
	 * locations, toolbar; call list_operations with name='set_toolbars' for the full contract.} The same wording is
	 * expected the other way round. Any other refusal (unknown toolbar, unknown table...) is NOT a shape problem and
	 * must not trigger the fallback.
	 */
	public static boolean isArgumentShapeRefusal(final String pRefusalText)
	{
		if (pRefusalText == null)
		{
			return false;
		}
		final String text = pRefusalText.toLowerCase(java.util.Locale.ROOT);
		return text.contains("set_toolbars") && text.contains("does not take");
	}

	// ----------------------------------------------------------------------------------------------- read-back

	/**
	 * What {@code describe_table} says is bound on a table: location (the {@code TABLE_VIEW_TOP}-style constants) ->
	 * toolbar name, only for locations that carry one. Tolerant on purpose (the exact answer shape was NOT read live
	 * by this lot): it looks for a node named {@code toolbars} (the documented path is {@code
	 * tableProperties.toolbars}) anywhere in the answer, matches its keys case- and separator-insensitively
	 * ({@code tableViewTop}, {@code TABLE_VIEW_TOP}, {@code tabularViewTop}...), and takes a value that is either a
	 * string or an object with {@code name}/{@code toolbar}/{@code toolbarName}. Empty when nothing recognisable.
	 */
	public static java.util.Map<String, String> boundToolbars(final com.fasterxml.jackson.databind.JsonNode pDescribeTableJson)
	{
		final java.util.Map<String, String> bound = new java.util.LinkedHashMap<String, String>();
		final com.fasterxml.jackson.databind.JsonNode toolbars = MasterToolbars.findNode(pDescribeTableJson, "toolbars");
		if (toolbars == null || !toolbars.isObject())
		{
			return bound;
		}
		final java.util.Map<String, String> found = new java.util.HashMap<String, String>();
		final java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = toolbars.fields();
		while (fields.hasNext())
		{
			final java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field = fields.next();
			final String location = MasterToolbars.locationOfKey(field.getKey());
			final String name = MasterToolbars.toolbarNameOf(field.getValue());
			if (location != null && name != null && !name.isEmpty())
			{
				found.put(location, name);
			}
		}
		for (final String location : new String[] { MasterToolbars.LOCATION_TABLE_TOP, MasterToolbars.LOCATION_TABLE_ROW, MasterToolbars.LOCATION_RECORD_TOP,
			"TILE_VIEW", "HIERARCHICAL_VIEW_TOP", "ASSOCIATION_TOP", "ASSOCIATION_ROW" })
		{
			if (found.containsKey(location))
			{
				bound.put(location, found.get(location));
			}
		}
		return bound;
	}

	/** The locations {@link #bindings} wants that {@code pBound} does not carry with the expected toolbar, as {@code LOCATION (found X)} / {@code LOCATION (nothing bound)}. */
	public static List<String> bindingDiscrepancies(final java.util.Map<String, String> pBound)
	{
		final List<String> problems = new ArrayList<String>();
		for (final Binding binding : MasterToolbars.bindings())
		{
			final String found = pBound.get(binding.locations);
			if (!binding.toolbar.equals(found))
			{
				problems.add(binding.locations + (found == null ? " (nothing bound)" : " (found " + found + ")"));
			}
		}
		return problems;
	}

	/** {@code Toolbars bound on /root/Person: TABLE_VIEW_TOP=MdmFactoryTableTop, ...} - the generation note; says so plainly when nothing could be read. */
	public static String boundNote(final String pTablePath, final java.util.Map<String, String> pBound)
	{
		if (pBound.isEmpty())
		{
			return "Toolbars bound on " + pTablePath + ": none read back (describe_table shows no toolbar binding).";
		}
		final StringBuilder note = new StringBuilder("Toolbars bound on ").append(pTablePath).append(':');
		String separator = " ";
		for (final java.util.Map.Entry<String, String> entry : pBound.entrySet())
		{
			note.append(separator).append(entry.getKey()).append('=').append(entry.getValue());
			separator = ", ";
		}
		final List<String> problems = MasterToolbars.bindingDiscrepancies(pBound);
		if (!problems.isEmpty())
		{
			note.append(" - NOT as expected: ").append(String.join("; ", problems)).append('.');
		}
		else
		{
			note.append('.');
		}
		return note.toString();
	}

	private static com.fasterxml.jackson.databind.JsonNode findNode(final com.fasterxml.jackson.databind.JsonNode pRoot, final String pName)
	{
		if (pRoot == null)
		{
			return null;
		}
		if (pRoot.isObject())
		{
			final com.fasterxml.jackson.databind.JsonNode direct = pRoot.get(pName);
			if (direct != null && direct.isObject())
			{
				return direct;
			}
		}
		for (final com.fasterxml.jackson.databind.JsonNode child : pRoot)
		{
			final com.fasterxml.jackson.databind.JsonNode inChild = MasterToolbars.findNode(child, pName);
			if (inChild != null)
			{
				return inChild;
			}
		}
		return null;
	}

	private static String locationOfKey(final String pKey)
	{
		final String normalized = pKey.replaceAll("[^A-Za-z]", "").toLowerCase(java.util.Locale.ROOT);
		switch (normalized)
		{
			case "tableviewtop":
			case "tabularviewtop":
				return MasterToolbars.LOCATION_TABLE_TOP;
			case "tableviewrow":
			case "tabularviewrow":
				return MasterToolbars.LOCATION_TABLE_ROW;
			case "recordviewtop":
			case "recordtop":
				return MasterToolbars.LOCATION_RECORD_TOP;
			case "tileview":
				return "TILE_VIEW";
			case "hierarchicalviewtop":
			case "hierarchyviewtop":
				return "HIERARCHICAL_VIEW_TOP";
			case "associationtop":
				return "ASSOCIATION_TOP";
			case "associationrow":
				return "ASSOCIATION_ROW";
			default:
				return null;
		}
	}

	private static String toolbarNameOf(final com.fasterxml.jackson.databind.JsonNode pValue)
	{
		if (pValue == null || pValue.isNull())
		{
			return null;
		}
		if (pValue.isTextual())
		{
			return pValue.asText();
		}
		if (pValue.isObject())
		{
			for (final String key : new String[] { "name", "toolbar", "toolbarName" })
			{
				if (pValue.path(key).isTextual())
				{
					return pValue.path(key).asText();
				}
			}
		}
		return null;
	}
}
