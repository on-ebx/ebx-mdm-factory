package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Guards {@link
 * MasterToolbars}'s SHAPE (never touches EBX) - see that class' own Javadoc for what each modelled fact was
 * read live versus assumed.
 * <p>
 * <b>Updated, 2026-09-22, 16:40 (Cockpit/Proposal review removed, the product owner's decision)</b>: the earlier
 * module-level Cockpit/Proposal review buttons are GONE - the decision and its reasoning are documented in
 * {@link MasterToolbars}'s own class Javadoc. This file's tests for those buttons (separator-prefixed
 * ordering including both of them, record-top's "Cockpit but never Proposal review" scope) are replaced by
 * the assertion below that NEITHER key is present anywhere in any of the three toolbars; every other test
 * (default-part reproduction, orderings, {@code set_toolbars} argument vocabulary) is unchanged except for
 * the now-shorter children lists.
 */
public final class MasterToolbarsTest
{
	private static final String MODULE = "ebx-mdm-factory";
	private static final String RECORD_LINEAGE_KEY = MasterToolbarsTest.MODULE + "@RecordLineageOnRecord";
	/** No longer written anywhere - {@link #noOnMasterServiceKeyAnywhere} asserts it stays that way. */
	private static final String COCKPIT_KEY = MasterToolbarsTest.MODULE + "@FactoryCockpitOnMaster";
	/** No longer written anywhere - {@link #noOnMasterServiceKeyAnywhere} asserts it stays that way. */
	private static final String PROPOSAL_REVIEW_KEY = MasterToolbarsTest.MODULE + "@ProposalReviewOnMaster";

	public static void main(final String[] pArgs) throws Exception
	{
		MasterToolbarsTest.threeToolbarsHaveUniqueNamesAndRightLocations();
		MasterToolbarsTest.tableTop_defaultPartThenOneSeparatorPrefixedRecordLineageButton();
		MasterToolbarsTest.tableTop_orderingsStrictlyIncreasing();
		MasterToolbarsTest.recordTop_sameDefaultPart_recordLineageOnly();
		MasterToolbarsTest.tableRow_singleIconOnlyRecordLineageButton_noDefaultPart();
		MasterToolbarsTest.recordLineageButton_isModuleLevel_bareTwoPartKey_targetCurrentContext_noServiceParameters();
		MasterToolbarsTest.noOnMasterServiceKeyAnywhere();
		MasterToolbarsTest.setToolbarsArguments_usesOnlyRealContractArgumentNames();
		MasterToolbarsTest.legacyArguments_areTheOldShape();
		MasterToolbarsTest.refusalClassification();
		MasterToolbarsTest.readBack_parsesBindingsAndFlagsDiscrepancies();
		System.out.println("MasterToolbarsTest OK");
	}

	// --------------------------------------------------------------------------------------------------------- shape

	private static void threeToolbarsHaveUniqueNamesAndRightLocations()
	{
		final List<MasterToolbars.Node> roots = MasterToolbars.build(MasterToolbarsTest.MODULE);
		MasterToolbarsTest.check("build() returns exactly three toolbars", roots.size() == 3);
		final Set<String> names = new HashSet<String>();
		for (final MasterToolbars.Node root : roots)
		{
			MasterToolbarsTest.check("every root is type TOOLBAR", "TOOLBAR".equals(root.type));
			MasterToolbarsTest.check("a toolbar root's own ordering is null (fixed position, class Javadoc)", root.ordering == null);
			MasterToolbarsTest.check("toolbar name \"" + root.toolbarName + "\" is unique among the three", names.add(root.toolbarName));
		}
		MasterToolbarsTest.check("TABLE_TOP_NAME constant matches the built root", names.contains(MasterToolbars.TABLE_TOP_NAME));
		MasterToolbarsTest.check("TABLE_ROW_NAME constant matches the built root", names.contains(MasterToolbars.TABLE_ROW_NAME));
		MasterToolbarsTest.check("RECORD_TOP_NAME constant matches the built root", names.contains(MasterToolbars.RECORD_TOP_NAME));

		MasterToolbarsTest.check("tableTop is bound to TABLE_VIEW_TOP", MasterToolbarsTest.locations(MasterToolbarsTest.tableTop()).equals(Arrays.asList("TABLE_VIEW_TOP")));
		MasterToolbarsTest.check("tableRow is bound to TABLE_VIEW_ROW", MasterToolbarsTest.locations(MasterToolbarsTest.tableRow()).equals(Arrays.asList("TABLE_VIEW_ROW")));
		MasterToolbarsTest.check("recordTop is bound to RECORD_VIEW_TOP",
			MasterToolbarsTest.locations(MasterToolbarsTest.recordTop()).equals(Arrays.asList("RECORD_VIEW_TOP")));
	}

	private static void tableTop_defaultPartThenOneSeparatorPrefixedRecordLineageButton()
	{
		final MasterToolbars.Node root = MasterToolbarsTest.tableTop();
		final List<MasterToolbars.Node> c = root.children;
		MasterToolbarsTest.check("table-top has exactly 5 children: create, sep, actions, sep, lineage", c.size() == 5);
		MasterToolbarsTest.check("first child is the default @creation button (mirrors the real Companies toolbar)",
			"ACTION_BUTTON".equals(c.get(0).type) && "@creation".equals(c.get(0).serviceKey));
		MasterToolbarsTest.check("second child is a separator", "SEPARATOR".equals(c.get(1).type));
		MasterToolbarsTest.check("third child is the default Actions menu", "MENU_BUTTON".equals(c.get(2).type));
		MasterToolbarsTest.check("Actions menu's own child is the standard @actions_menu_with_separator group",
			c.get(2).children.size() == 1 && "@actions_menu_with_separator".equals(c.get(2).children.get(0).menuBuilderKey));
		MasterToolbarsTest.check("a separator precedes Record lineage", "SEPARATOR".equals(c.get(3).type));
		MasterToolbarsTest.check("last child is Record lineage, immediately after its separator",
			"ACTION_BUTTON".equals(c.get(4).type) && MasterToolbarsTest.RECORD_LINEAGE_KEY.equals(c.get(4).serviceKey));
	}

	private static void tableTop_orderingsStrictlyIncreasing()
	{
		MasterToolbarsTest.checkStrictlyIncreasingOrderings(MasterToolbarsTest.tableTop());
		MasterToolbarsTest.checkStrictlyIncreasingOrderings(MasterToolbarsTest.tableRow());
		MasterToolbarsTest.checkStrictlyIncreasingOrderings(MasterToolbarsTest.recordTop());
	}

	private static void checkStrictlyIncreasingOrderings(final MasterToolbars.Node pRoot)
	{
		int previous = Integer.MIN_VALUE;
		for (final MasterToolbars.Node child : pRoot.children)
		{
			MasterToolbarsTest.check(pRoot.toolbarName + ": every direct child has an ordering", child.ordering != null);
			final int ordering = child.ordering.intValue();
			MasterToolbarsTest.check(pRoot.toolbarName + ": orderings strictly increase (" + previous + " -> " + ordering + ")", ordering > previous);
			previous = ordering;
		}
	}

	private static void recordTop_sameDefaultPart_recordLineageOnly()
	{
		final MasterToolbars.Node root = MasterToolbarsTest.recordTop();
		final List<MasterToolbars.Node> c = root.children;
		MasterToolbarsTest.check("record-top has exactly 5 children: create, sep, actions, sep, lineage", c.size() == 5);
		MasterToolbarsTest.check("last child is Record lineage", MasterToolbarsTest.RECORD_LINEAGE_KEY.equals(c.get(c.size() - 1).serviceKey));
	}

	private static void tableRow_singleIconOnlyRecordLineageButton_noDefaultPart()
	{
		final MasterToolbars.Node root = MasterToolbarsTest.tableRow();
		MasterToolbarsTest.check("row toolbar has exactly one child (mirrors the real 'Record row' toolbar, no default part)",
			root.children.size() == 1);
		final MasterToolbars.Node onlyChild = root.children.get(0);
		MasterToolbarsTest.check("that one child is Record lineage", MasterToolbarsTest.RECORD_LINEAGE_KEY.equals(onlyChild.serviceKey));
		MasterToolbarsTest.check("it is icon-only in the row toolbar", "ICON_ONLY".equals(onlyChild.layout));
		MasterToolbarsTest.check("it targets the current context (an OnRecord service, never a web component)",
			"c".equals(onlyChild.target));
	}

	// ------------------------------------------------------------------------------------------- service keys

	private static void recordLineageButton_isModuleLevel_bareTwoPartKey_targetCurrentContext_noServiceParameters()
	{
		final MasterToolbars.Node tableTop = MasterToolbarsTest.tableTop();
		final MasterToolbars.Node button = MasterToolbarsTest.findByServiceKey(tableTop, MasterToolbarsTest.RECORD_LINEAGE_KEY);
		MasterToolbarsTest.check("\"" + MasterToolbarsTest.RECORD_LINEAGE_KEY + "\" is present as a bare <module>@<service> key", button != null);
		MasterToolbarsTest.check("targets the current context, never a web component", "c".equals(button.target));
		MasterToolbarsTest.check("carries no serviceParameters", button.serviceParameters.isEmpty());
		MasterToolbarsTest.check("is not resizable, same convention as every other current-context button", !button.resizable);
	}

	/**
	 * the product owner's decision, 2026-09-22 16:40 (class Javadoc, `docs/MASTER-ZONE.md` &sect;12): Cockpit and
	 * Proposal review were removed from every master table toolbar - neither key may appear anywhere in any
	 * of the three trees, ever again.
	 */
	private static void noOnMasterServiceKeyAnywhere()
	{
		for (final MasterToolbars.Node root : MasterToolbars.build(MasterToolbarsTest.MODULE))
		{
			MasterToolbarsTest.check(root.toolbarName + " never carries the Cockpit OnMaster key",
				MasterToolbarsTest.findByServiceKey(root, MasterToolbarsTest.COCKPIT_KEY) == null);
			MasterToolbarsTest.check(root.toolbarName + " never carries the Proposal review OnMaster key",
				MasterToolbarsTest.findByServiceKey(root, MasterToolbarsTest.PROPOSAL_REVIEW_KEY) == null);
		}
	}

	// --------------------------------------------------------------------------------------------- set_toolbars args

	/**
	 * Guards the same incident class as {@code core.PatchOpVocabularyTest} (`docs/MASTER-ZONE.md` &sect;10): an
	 * argument name {@code set_toolbars} does not know about fails the call ("Operation 'set_toolbars' does not
	 * take ...", measured live 2026-10-12 on project Vol100kN, when the server dropped {@code tabular_view_top} /
	 * {@code tabular_view_row} / {@code record_top}). {@code allowed} is a literal, DATED copy of {@code
	 * list_operations(name="set_toolbars")}'s own argument list, read live 2026-10-12 - update it if the contract changes.
	 */
	private static void setToolbarsArguments_usesOnlyRealContractArgumentNames()
	{
		final Set<String> allowed = new HashSet<String>(Arrays.asList("element_path", "type_name", "locations", "toolbar"));
		final Set<String> locationVocabulary = new HashSet<String>(Arrays.asList("TABLE_VIEW_TOP", "TABLE_VIEW_ROW", "TILE_VIEW", "RECORD_VIEW_TOP",
			"HIERARCHICAL_VIEW_TOP", "ASSOCIATION_TOP", "ASSOCIATION_ROW"));
		final List<MasterToolbars.Binding> bindings = MasterToolbars.bindings();
		MasterToolbarsTest.check("three bindings: one per toolbar", bindings.size() == 3);
		final Set<String> seenLocations = new HashSet<String>();
		for (final MasterToolbars.Binding binding : bindings)
		{
			final ObjectNode args = MasterToolbars.setToolbarsArguments("/root/Widget", binding);
			MasterToolbarsTest.check("exactly element_path + locations + toolbar", args.size() == 3);
			MasterToolbarsTest.check("element_path is the table's path", "/root/Widget".equals(args.path("element_path").asText(null)));
			final Iterator<String> fieldNames = args.fieldNames();
			while (fieldNames.hasNext())
			{
				final String name = fieldNames.next();
				MasterToolbarsTest.check("set_toolbars argument \"" + name + "\" is not in the tool's own real argument list", allowed.contains(name));
			}
			final String locations = args.path("locations").asText("");
			MasterToolbarsTest.check("locations \"" + locations + "\" belongs to the documented vocabulary", locationVocabulary.contains(locations));
			MasterToolbarsTest.check("each location is bound once", seenLocations.add(locations));
			MasterToolbarsTest.check("toolbar is a non-blank name (blank would detach)", !args.path("toolbar").asText("").isEmpty());
		}
		final MasterToolbars.Binding top = bindings.get(0);
		final MasterToolbars.Binding row = bindings.get(1);
		final MasterToolbars.Binding record = bindings.get(2);
		MasterToolbarsTest.check("table top: TABLE_VIEW_TOP -> MdmFactoryTableTop", "TABLE_VIEW_TOP".equals(top.locations) && MasterToolbars.TABLE_TOP_NAME.equals(top.toolbar));
		MasterToolbarsTest.check("table row: TABLE_VIEW_ROW -> MdmFactoryTableRow", "TABLE_VIEW_ROW".equals(row.locations) && MasterToolbars.TABLE_ROW_NAME.equals(row.toolbar));
		MasterToolbarsTest.check("record top: RECORD_VIEW_TOP -> MdmFactoryRecordTop", "RECORD_VIEW_TOP".equals(record.locations) && MasterToolbars.RECORD_TOP_NAME.equals(record.toolbar));
		MasterToolbarsTest.check("the exact JSON of the first call",
			"{\"element_path\":\"/root/Widget\",\"locations\":\"TABLE_VIEW_TOP\",\"toolbar\":\"MdmFactoryTableTop\"}".equals(MasterToolbars.setToolbarsArguments("/root/Widget", top).toString()));
		// the toolbar must declare the location it is attached to (the create_toolbar call gives the location list)
		for (final MasterToolbars.Binding binding : bindings)
		{
			boolean declared = false;
			for (final MasterToolbars.Node root : MasterToolbars.build(MasterToolbarsTest.MODULE))
			{
				if (binding.toolbar.equals(root.toolbarName))
				{
					declared = root.locations.contains(binding.locations);
				}
			}
			MasterToolbarsTest.check(binding.toolbar + " declares " + binding.locations, declared);
		}
	}

	/** The old shape stays available as the fallback, and is exactly what the 2026-10-12 server refused. */
	private static void legacyArguments_areTheOldShape()
	{
		final ObjectNode args = MasterToolbars.legacySetToolbarsArguments("/root/Widget");
		MasterToolbarsTest.check("legacy: element_path + 3 location arguments", args.size() == 4);
		MasterToolbarsTest.check("legacy tabular_view_top", MasterToolbars.TABLE_TOP_NAME.equals(args.path("tabular_view_top").asText(null)));
		MasterToolbarsTest.check("legacy tabular_view_row", MasterToolbars.TABLE_ROW_NAME.equals(args.path("tabular_view_row").asText(null)));
		MasterToolbarsTest.check("legacy record_top", MasterToolbars.RECORD_TOP_NAME.equals(args.path("record_top").asText(null)));
	}

	/** Fixture: the refusal measured live 2026-10-12 10:16 on Vol100kN. Only a wrong-argument-names refusal may trigger the fallback. */
	private static void refusalClassification()
	{
		final String measured = "Operation 'set_toolbars' does not take 'tabular_view_top', 'tabular_view_row', 'record_top'. It takes element_path, type_name, "
			+ "locations, toolbar; call list_operations with name='set_toolbars' for the full contract.";
		MasterToolbarsTest.check("the measured refusal is a shape refusal", MasterToolbars.isArgumentShapeRefusal(measured));
		MasterToolbarsTest.check("case does not matter", MasterToolbars.isArgumentShapeRefusal(measured.toUpperCase(java.util.Locale.ROOT)));
		MasterToolbarsTest.check("the mirror refusal (new shape sent to an old server) is one too",
			MasterToolbars.isArgumentShapeRefusal("Operation 'set_toolbars' does not take 'locations', 'toolbar'. It takes element_path, tabular_view_top"));
		MasterToolbarsTest.check("an unknown toolbar is not a shape problem", !MasterToolbars.isArgumentShapeRefusal("Toolbar 'X' does not exist on model M"));
		MasterToolbarsTest.check("another operation's argument refusal is not ours", !MasterToolbars.isArgumentShapeRefusal("Operation 'create_toolbar' does not take 'foo'."));
		MasterToolbarsTest.check("null is not a refusal", !MasterToolbars.isArgumentShapeRefusal(null));
		MasterToolbarsTest.check("empty is not a refusal", !MasterToolbars.isArgumentShapeRefusal(""));
	}

	/** Read-back: tolerant of the describe_table shape (string or {name} values, camel or snake keys, nested anywhere). */
	private static void readBack_parsesBindingsAndFlagsDiscrepancies() throws Exception
	{
		final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		final com.fasterxml.jackson.databind.JsonNode full = mapper.readTree("{\"data\":{\"tableProperties\":{\"toolbars\":{"
			+ "\"tableViewTop\":\"MdmFactoryTableTop\",\"tableViewRow\":{\"name\":\"MdmFactoryTableRow\"},\"recordViewTop\":\"MdmFactoryRecordTop\",\"tileView\":null}}}}");
		final java.util.Map<String, String> bound = MasterToolbars.boundToolbars(full);
		MasterToolbarsTest.check("three bindings read", bound.size() == 3);
		MasterToolbarsTest.check("no discrepancy when all three are bound", MasterToolbars.bindingDiscrepancies(bound).isEmpty());
		MasterToolbarsTest.check("the generation note, in binding order",
			"Toolbars bound on /root/Person: TABLE_VIEW_TOP=MdmFactoryTableTop, TABLE_VIEW_ROW=MdmFactoryTableRow, RECORD_VIEW_TOP=MdmFactoryRecordTop."
				.equals(MasterToolbars.boundNote("/root/Person", bound)));

		final com.fasterxml.jackson.databind.JsonNode partial = mapper.readTree("{\"tableProperties\":{\"toolbars\":{\"TABLE_VIEW_TOP\":\"Other\",\"RECORD_VIEW_TOP\":\"MdmFactoryRecordTop\"}}}");
		final String note = MasterToolbars.boundNote("/root/Person", MasterToolbars.boundToolbars(partial));
		MasterToolbarsTest.check("a wrong toolbar is reported: " + note, note.contains("TABLE_VIEW_TOP (found Other)"));
		MasterToolbarsTest.check("a missing one is reported: " + note, note.contains("TABLE_VIEW_ROW (nothing bound)") && note.contains("NOT as expected"));

		final java.util.Map<String, String> none = MasterToolbars.boundToolbars(mapper.readTree("{\"data\":{\"name\":\"Person\"}}"));
		MasterToolbarsTest.check("no toolbars node: empty", none.isEmpty());
		MasterToolbarsTest.check("no toolbars node: said plainly", MasterToolbars.boundNote("/root/Person", none).contains("none read back"));
		MasterToolbarsTest.check("null json: empty", MasterToolbars.boundToolbars(null).isEmpty());
	}

	// --------------------------------------------------------------------------------------------------------- helpers

	private static MasterToolbars.Node tableTop()
	{
		return MasterToolbars.tableTop(MasterToolbarsTest.MODULE);
	}

	private static MasterToolbars.Node tableRow()
	{
		return MasterToolbars.tableRow(MasterToolbarsTest.MODULE);
	}

	private static MasterToolbars.Node recordTop()
	{
		return MasterToolbars.recordTop(MasterToolbarsTest.MODULE);
	}

	private static List<String> locations(final MasterToolbars.Node pRoot)
	{
		return pRoot.locations;
	}

	private static MasterToolbars.Node findByServiceKey(final MasterToolbars.Node pRoot, final String pServiceKey)
	{
		for (final MasterToolbars.Node child : pRoot.children)
		{
			if (pServiceKey.equals(child.serviceKey))
			{
				return child;
			}
			final MasterToolbars.Node inChild = MasterToolbarsTest.findByServiceKey(child, pServiceKey);
			if (inChild != null)
			{
				return inChild;
			}
		}
		return null;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
