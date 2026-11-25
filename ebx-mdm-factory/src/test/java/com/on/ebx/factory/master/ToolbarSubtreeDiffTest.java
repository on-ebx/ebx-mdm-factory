package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.on.ebx.factory.master.ToolbarSubtreeDiff.ExistingNode;

/**
 * JUnit-free, like the sibling tests: run the main, a failure throws. Guards {@link ToolbarSubtreeDiff} -
 * the pure comparison behind {@code ebx.DmaToolbarWriter}'s 2026-09-22 migration (a toolbar root found by
 * name is now content-compared, not just left alone) - without any EBX runtime, feeding it hand-built {@code
 * MasterToolbars.Node}/{@link ExistingNode} trees. Every test builds a tiny two-node tree (a root plus one
 * or two {@code ACTION_BUTTON}/{@code GROUP_MENU_ITEM} children) rather than the real three-toolbar shape -
 * {@link MasterToolbarsTest} already guards the real shape; this file only guards the comparison logic
 * itself.
 */
public final class ToolbarSubtreeDiffTest
{
	public static void main(final String[] pArgs)
	{
		ToolbarSubtreeDiffTest.identicalTrees_noDifferences();
		ToolbarSubtreeDiffTest.differentServiceKey_oneDifference();
		ToolbarSubtreeDiffTest.differentTarget_oneDifference();
		ToolbarSubtreeDiffTest.serviceKeyIgnoredOnNonActionButton();
		ToolbarSubtreeDiffTest.differentServiceParameterValue_reported();
		ToolbarSubtreeDiffTest.differentServiceParameterCount_reported();
		ToolbarSubtreeDiffTest.differentMenuBuilderKey_oneDifference();
		ToolbarSubtreeDiffTest.menuBuilderKeyIgnoredOnNonMenuGroup();
		ToolbarSubtreeDiffTest.differentOrdering_reported();
		ToolbarSubtreeDiffTest.differentType_reported();
		ToolbarSubtreeDiffTest.differentChildCount_oneDifference_noCrash();
		ToolbarSubtreeDiffTest.existingSubtreeHasExtraChildren_reported_noCrash();
		ToolbarSubtreeDiffTest.nestedChildDifference_pathIncludesChildIndex();
		ToolbarSubtreeDiffTest.documentationAndIconNeverCompared();
		ToolbarSubtreeDiffTest.menuButtonIconTypeDefaulted_reported();
		ToolbarSubtreeDiffTest.menuButtonIconTypeNull_identical();
		System.out.println("ToolbarSubtreeDiffTest OK");
	}

	// --------------------------------------------------------------------------------------------------------- root

	private static void identicalTrees_noDifferences()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.actionButtonRoot("Companies@Foo", "c");
		final ExistingNode existing = ToolbarSubtreeDiffTest.actionButtonExisting("Companies@Foo", "c");
		ToolbarSubtreeDiffTest.check("identical trees produce no differences", ToolbarSubtreeDiff.differences(definition, existing).isEmpty());
	}

	private static void differentServiceKey_oneDifference()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.actionButtonRoot("Companies@Foo", "c");
		final ExistingNode existing = ToolbarSubtreeDiffTest.actionButtonExisting("ebx-mdm-factory@/WEB-INF/ebx/schemas/MdmFactory.xsd@FactoryCockpit", "s");
		final List<String> differences = ToolbarSubtreeDiff.differences(definition, existing);
		ToolbarSubtreeDiffTest.check("a different serviceKey is reported", ToolbarSubtreeDiffTest.anyContains(differences, "serviceKey"));
	}

	private static void differentTarget_oneDifference()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.actionButtonRoot("Companies@Foo", "c");
		final ExistingNode existing = ToolbarSubtreeDiffTest.actionButtonExisting("Companies@Foo", "s");
		final List<String> differences = ToolbarSubtreeDiff.differences(definition, existing);
		ToolbarSubtreeDiffTest.check("a different target is reported", ToolbarSubtreeDiffTest.anyContains(differences, "target"));
		ToolbarSubtreeDiffTest.check("exactly one difference (serviceKey matches)", differences.size() == 1);
	}

	private static void serviceKeyIgnoredOnNonActionButton()
	{
		// a SEPARATOR has no meaningful serviceKey - even if the existing row somehow carried a stray value
		// (should never happen from a real read, class Javadoc's own "never causes a spurious difference"),
		// the comparison must not look at it at all.
		final MasterToolbars.Node definition = new MasterToolbars.Node("SEPARATOR", Integer.valueOf(1000));
		final ExistingNode existing = new ExistingNode("SEPARATOR", Integer.valueOf(1000), "some-stray-value", "c", null, null, null);
		ToolbarSubtreeDiffTest.check("serviceKey is never compared on a SEPARATOR", ToolbarSubtreeDiff.differences(definition, existing).isEmpty());
	}

	private static void differentServiceParameterValue_reported()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.actionButtonWithParam("Companies@Foo", "c", "xpath", "/root/Project[./code='A']");
		final ExistingNode existing = ToolbarSubtreeDiffTest.actionButtonExistingWithParam("Companies@Foo", "c", "xpath", "/root/Project[./code='B']");
		final List<String> differences = ToolbarSubtreeDiff.differences(definition, existing);
		ToolbarSubtreeDiffTest.check("a different serviceParameter value is reported", ToolbarSubtreeDiffTest.anyContains(differences, "serviceParameters[0].value"));
	}

	private static void differentServiceParameterCount_reported()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.actionButtonWithParam("Companies@Foo", "c", "xpath", "/root/Project[./code='A']");
		final ExistingNode existing = ToolbarSubtreeDiffTest.actionButtonExisting("Companies@Foo", "c"); // no parameters at all
		final List<String> differences = ToolbarSubtreeDiff.differences(definition, existing);
		ToolbarSubtreeDiffTest.check("a serviceParameter count mismatch is reported",
			ToolbarSubtreeDiffTest.anyContains(differences, "serviceParameter(s) expected"));
	}

	private static void differentMenuBuilderKey_oneDifference()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.menuGroupRoot("@actions_menu_with_separator");
		final ExistingNode existing = ToolbarSubtreeDiffTest.menuGroupExisting("@some_other_builder");
		final List<String> differences = ToolbarSubtreeDiff.differences(definition, existing);
		ToolbarSubtreeDiffTest.check("a different menuBuilderKey is reported", ToolbarSubtreeDiffTest.anyContains(differences, "menuBuilderKey"));
	}

	private static void menuBuilderKeyIgnoredOnNonMenuGroup()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.actionButtonRoot("Companies@Foo", "c");
		final ExistingNode existing = new ExistingNode("ACTION_BUTTON", Integer.valueOf(1000), "Companies@Foo", "c", null, "some-stray-builder-key", null);
		ToolbarSubtreeDiffTest.check("menuBuilderKey is never compared on an ACTION_BUTTON", ToolbarSubtreeDiff.differences(definition, existing).isEmpty());
	}

	private static void differentOrdering_reported()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.actionButtonRoot("Companies@Foo", "c");
		final ExistingNode existing = new ExistingNode("ACTION_BUTTON", Integer.valueOf(2000), "Companies@Foo", "c", null, null, null);
		final List<String> differences = ToolbarSubtreeDiff.differences(definition, existing);
		ToolbarSubtreeDiffTest.check("a different ordering is reported", ToolbarSubtreeDiffTest.anyContains(differences, ".ordering"));
	}

	private static void differentType_reported()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.actionButtonRoot("Companies@Foo", "c");
		final ExistingNode existing = new ExistingNode("SEPARATOR", Integer.valueOf(1000), null, null, null, null, null);
		final List<String> differences = ToolbarSubtreeDiff.differences(definition, existing);
		ToolbarSubtreeDiffTest.check("a different type is reported", ToolbarSubtreeDiffTest.anyContains(differences, ".type"));
	}

	private static void differentChildCount_oneDifference_noCrash()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.actionButtonRoot("Companies@Foo", "c");
		definition.children.add(ToolbarSubtreeDiffTest.actionButtonRoot("Companies@Bar", "c"));
		final ExistingNode existing = ToolbarSubtreeDiffTest.actionButtonExisting("Companies@Foo", "c"); // no children
		final List<String> differences = ToolbarSubtreeDiff.differences(definition, existing);
		ToolbarSubtreeDiffTest.check("a child-count mismatch is reported and nothing throws",
			ToolbarSubtreeDiffTest.anyContains(differences, "child/children expected"));
	}

	/**
	 * The exact shape of the 2026-09-22 16:40 migration ({@code master.MasterToolbars}'s own class Javadoc:
	 * Cockpit/Proposal review buttons removed from every master-table toolbar, the product owner's decision) - the
	 * EXISTING row (written while those buttons still existed) has MORE children than the new, narrower
	 * definition, the opposite direction from {@link #differentChildCount_oneDifference_noCrash} (which has
	 * the definition carrying more). Must be reported the same way, so {@code DmaToolbarWriter} replaces the
	 * stale, too-wide subtree instead of reporting a spurious {@code EXISTS}.
	 */
	private static void existingSubtreeHasExtraChildren_reported_noCrash()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.actionButtonRoot("Companies@Foo", "c");
		final ExistingNode extraChild = ToolbarSubtreeDiffTest.actionButtonExisting("Companies@Bar", "c");
		final ExistingNode existing = new ExistingNode("ACTION_BUTTON", Integer.valueOf(1000), "Companies@Foo", "c", null, null, Arrays.asList(extraChild));
		final List<String> differences = ToolbarSubtreeDiff.differences(definition, existing);
		ToolbarSubtreeDiffTest.check("an existing subtree with MORE children than the definition is reported, and nothing throws",
			ToolbarSubtreeDiffTest.anyContains(differences, "child/children expected"));
	}

	private static void nestedChildDifference_pathIncludesChildIndex()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.actionButtonRoot("Companies@Foo", "c");
		definition.children.add(ToolbarSubtreeDiffTest.actionButtonRoot("Companies@Bar", "c"));
		final ExistingNode child = ToolbarSubtreeDiffTest.actionButtonExisting("Companies@WRONG", "c");
		final ExistingNode existing = new ExistingNode("ACTION_BUTTON", Integer.valueOf(1000), "Companies@Foo", "c", null, null, Arrays.asList(child));
		final List<String> differences = ToolbarSubtreeDiff.differences(definition, existing);
		ToolbarSubtreeDiffTest.check("a nested child difference names the child index", ToolbarSubtreeDiffTest.anyContains(differences, "child[0]"));
	}

	private static void documentationAndIconNeverCompared()
	{
		// two nodes that differ only in documentation/icon/modalSize/resizable/highlightedButton/layout - all
		// deliberately out of scope (class Javadoc) - must never be reported.
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.actionButtonRoot("Companies@Foo", "c");
		definition.documentation = Arrays.asList(new MasterToolbars.LocalizedText("en-US", "English label"));
		definition.iconReference = "fa-one";
		definition.modalSize = "l";
		definition.resizable = true;
		definition.highlightedButton = true;
		definition.layout = "ICON_ONLY";
		final ExistingNode existing = ToolbarSubtreeDiffTest.actionButtonExisting("Companies@Foo", "c");
		ToolbarSubtreeDiffTest.check("cosmetic-only fields never produce a difference", ToolbarSubtreeDiff.differences(definition, existing).isEmpty());
	}

	/**
	 * Reproduces the actual 2026-09-22 16:27-16:29 incident ({@code DmaToolbarWriter}'s own Javadoc): a
	 * {@code MENU_BUTTON} row written before the fix carries {@code menuButton/display/icon/type = "css"} (the
	 * schema's own default surviving a field the old code never wrote at all), even though the definition
	 * never sets a menu-button icon - this must be reported as a difference, so {@code DmaToolbarWriter}
	 * replaces the row instead of leaving the defaulted icon (and the resulting {@code kernel.log} error)
	 * forever under a name-only {@code EXISTS} match.
	 */
	private static void menuButtonIconTypeDefaulted_reported()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.menuButtonRoot();
		final ExistingNode existing = ToolbarSubtreeDiffTest.menuButtonExisting("css");
		final List<String> differences = ToolbarSubtreeDiff.differences(definition, existing);
		ToolbarSubtreeDiffTest.check("a defaulted menu button icon type is reported", ToolbarSubtreeDiffTest.anyContains(differences, "menuButton icon type"));
	}

	/** The fixed shape (both sides null) must NOT be reported - this is what a correctly-written row looks like after the fix. */
	private static void menuButtonIconTypeNull_identical()
	{
		final MasterToolbars.Node definition = ToolbarSubtreeDiffTest.menuButtonRoot();
		final ExistingNode existing = ToolbarSubtreeDiffTest.menuButtonExisting(null);
		ToolbarSubtreeDiffTest.check("a null menu button icon type matches the definition", ToolbarSubtreeDiff.differences(definition, existing).isEmpty());
	}

	// --------------------------------------------------------------------------------------------------------- helpers

	/** A single {@code ACTION_BUTTON} "root" (this test never builds a real {@code TOOLBAR} wrapper - {@code differences} does not care what the top-level type is). */
	private static MasterToolbars.Node actionButtonRoot(final String pServiceKey, final String pTarget)
	{
		return ToolbarSubtreeDiffTest.build("ACTION_BUTTON", 1000, pServiceKey, pTarget, null, null);
	}

	private static ExistingNode actionButtonExisting(final String pServiceKey, final String pTarget)
	{
		return new ExistingNode("ACTION_BUTTON", Integer.valueOf(1000), pServiceKey, pTarget, null, null, null);
	}

	private static MasterToolbars.Node actionButtonWithParam(final String pServiceKey, final String pTarget, final String pParamName, final String pParamValue)
	{
		final MasterToolbars.Node node = ToolbarSubtreeDiffTest.actionButtonRoot(pServiceKey, pTarget);
		node.serviceParameters = Arrays.asList(new MasterToolbars.ServiceParameter(pParamName, pParamValue));
		return node;
	}

	private static ExistingNode actionButtonExistingWithParam(final String pServiceKey, final String pTarget, final String pParamName, final String pParamValue)
	{
		final List<String[]> params = new ArrayList<String[]>();
		params.add(new String[] { pParamName, pParamValue });
		return new ExistingNode("ACTION_BUTTON", Integer.valueOf(1000), pServiceKey, pTarget, params, null, null);
	}

	private static MasterToolbars.Node menuGroupRoot(final String pMenuBuilderKey)
	{
		return ToolbarSubtreeDiffTest.build("GROUP_MENU_ITEM", 1000, null, null, pMenuBuilderKey, null);
	}

	private static ExistingNode menuGroupExisting(final String pMenuBuilderKey)
	{
		return new ExistingNode("GROUP_MENU_ITEM", Integer.valueOf(1000), null, null, null, pMenuBuilderKey, null);
	}

	/** A bare {@code MENU_BUTTON} "root" whose {@code iconType} is left at its field default (null) - {@code MasterToolbars.actionsMenu} never sets one either. */
	private static MasterToolbars.Node menuButtonRoot()
	{
		return new MasterToolbars.Node("MENU_BUTTON", Integer.valueOf(1000));
	}

	private static ExistingNode menuButtonExisting(final String pIconType)
	{
		return new ExistingNode("MENU_BUTTON", Integer.valueOf(1000), null, null, null, null, pIconType, null);
	}

	/** Builds a bare {@link MasterToolbars.Node} - the constructor is package-private (2026-09-22, for exactly this test), no special factory needed. */
	private static MasterToolbars.Node build(final String pType, final int pOrdering, final String pServiceKey, final String pTarget, final String pMenuBuilderKey,
		final List<MasterToolbars.ServiceParameter> pServiceParameters)
	{
		final MasterToolbars.Node node = new MasterToolbars.Node(pType, Integer.valueOf(pOrdering));
		node.serviceKey = pServiceKey;
		node.target = pTarget;
		node.menuBuilderKey = pMenuBuilderKey;
		if (pServiceParameters != null)
		{
			node.serviceParameters = pServiceParameters;
		}
		return node;
	}

	private static boolean anyContains(final List<String> pLines, final String pFragment)
	{
		for (final String line : pLines)
		{
			if (line.contains(pFragment))
			{
				return true;
			}
		}
		return false;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
