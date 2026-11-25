package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PURE decision (no EBX import, tested without a running instance, same discipline as {@link MasterToolbars}
 * itself) behind {@code ebx.DmaToolbarWriter}'s migration logic (2026-09-22, toolbar module-level services
 * lot): given a {@link MasterToolbars.Node} DEFINITION and an {@link ExistingNode} read back off an already
 * published toolbar's rows, decide whether they still agree - and if not, say what differs, so {@code
 * DmaToolbarWriter} can report {@code REPLACED} with a reason instead of a bare status.
 * <p>
 * <b>Why this exists at all</b>: before this lot, a toolbar root found by NAME was left completely alone,
 * children never re-read or reconciled (the original brief's own instruction, when only a single write was
 * ever going to happen). This lot's own incident (`docs/MASTER-ZONE.md` &sect;12, measured live 2026-09-22)
 * changed the CONTENT of the Cockpit/Proposal review buttons under the SAME toolbar names
 * ({@code MdmFactoryTableTop}/{@code MdmFactoryRecordTop}) - a plain "already exists by name, leave it" write
 * would silently keep serving the broken, cross-model service keys forever on every already-generated master
 * model. {@code DmaToolbarWriter} now compares first and replaces only when something actually differs.
 * <p>
 * <b>Compares exactly the fields the task brief named</b> - {@code type}, {@code ordering}, {@code
 * actionButton/service/serviceKey}, {@code actionButton/service/target}, {@code actionButton/service/
 * serviceParameters} (name/value pairs, in order), and {@code menuGroup/menuBuilder/menuBuilderKey} - never
 * documentation labels, an {@code actionButton}'s own icon, {@code modalSize}, or {@code resizable}: those are
 * cosmetic and were never the subject of the incident this migration logic exists to catch. A definition field
 * that does not apply to a given node type (e.g. {@code serviceKey} on a {@code SEPARATOR}) is treated as
 * absent on both sides, so it never causes a spurious difference.
 * <p>
 * <b>One exception, added 2026-09-22 16:27-16:29 (the menu-button icon-default incident, {@code
 * DmaToolbarWriter}'s own Javadoc)</b>: a {@code MENU_BUTTON}'s {@code menuButton/display/icon/type} IS now
 * compared (against the definition's own {@link MasterToolbars.Node#iconType}, expected null - no menu-button
 * definition in {@code MasterToolbars} sets it) - unlike every other icon field, this one is not cosmetic: a
 * non-null value here is the exact symptom of the schema's own default surviving an old write that never wrote
 * under {@code ./menuButton/display/icon/*} at all, and {@code kernel.log} logs an error on every rendering of
 * a table or record carrying it. Without this exception, the two already-broken "Actions" menu buttons written
 * before the fix (table-top, record-top) would report {@code EXISTS} forever under the name-based match, and
 * the log noise would never clear.
 * <p>
 * <b>Children are compared POSITIONALLY, not by any identity</b> - {@code toolbarNode} rows carry no stable
 * business key of their own beyond the auto-increment {@code id} (which changes on every replace, {@code
 * DmaToolbarWriter}'s own Javadoc), so the Nth child of the definition is compared against the Nth child of
 * the existing subtree, in {@code ordering} order (the same order the definition itself already uses, 1000,
 * 2000, ...). A different child COUNT is itself a difference, reported before any per-child comparison is
 * attempted.
 */
public final class ToolbarSubtreeDiff
{
	private ToolbarSubtreeDiff()
	{
	}

	/**
	 * A read-back description of one existing {@code toolbarNode} row (and, recursively, its children) - the
	 * EBX-free shape {@code ebx.DmaToolbarWriter} builds from a real {@link com.onwbp.adaptation.Adaptation}
	 * before handing it to {@link #differences}, and the shape a test builds by hand to feed this class
	 * without any EBX runtime.
	 */
	public static final class ExistingNode
	{
		final String type;
		final Integer ordering;
		/** {@code actionButton/service/serviceKey} - null for any type other than {@code ACTION_BUTTON}. */
		final String serviceKey;
		/** {@code actionButton/service/target} - null for any type other than {@code ACTION_BUTTON}. */
		final String target;
		/** {@code actionButton/service/serviceParameters}, one {@code {name, value}} pair per entry, in read order - empty for any type other than {@code ACTION_BUTTON}. */
		final List<String[]> serviceParameters;
		/** {@code menuGroup/menuBuilder/menuBuilderKey} - null for any type other than {@code GROUP_MENU_ITEM}. */
		final String menuBuilderKey;
		/**
		 * {@code menuButton/display/icon/type} - null for any type other than {@code MENU_BUTTON}. Added
		 * 2026-09-22 16:27-16:29 (the icon-default incident, class Javadoc) - the one icon field this class
		 * does NOT treat as cosmetic, since a non-null value here is exactly the schema-defaulted-icon bug
		 * {@code DmaToolbarWriter}'s fix exists to catch and replace.
		 */
		final String menuButtonIconType;
		final List<ExistingNode> children;

		/** Pre-2026-09-22 16:27 shape, kept so every existing caller (this class's own tests included) that never mentions a menu button icon keeps compiling unchanged - delegates with {@code pMenuButtonIconType = null}. */
		public ExistingNode(final String pType, final Integer pOrdering, final String pServiceKey, final String pTarget, final List<String[]> pServiceParameters,
			final String pMenuBuilderKey, final List<ExistingNode> pChildren)
		{
			this(pType, pOrdering, pServiceKey, pTarget, pServiceParameters, pMenuBuilderKey, null, pChildren);
		}

		public ExistingNode(final String pType, final Integer pOrdering, final String pServiceKey, final String pTarget, final List<String[]> pServiceParameters,
			final String pMenuBuilderKey, final String pMenuButtonIconType, final List<ExistingNode> pChildren)
		{
			this.type = pType;
			this.ordering = pOrdering;
			this.serviceKey = pServiceKey;
			this.target = pTarget;
			this.serviceParameters = pServiceParameters == null ? Collections.<String[]> emptyList() : pServiceParameters;
			this.menuBuilderKey = pMenuBuilderKey;
			this.menuButtonIconType = pMenuButtonIconType;
			this.children = pChildren == null ? Collections.<ExistingNode> emptyList() : pChildren;
		}
	}

	/**
	 * Every way {@code pExisting} disagrees with {@code pDefinition}, as short human-readable lines (never
	 * empty when they disagree, always empty when they match) - {@code ebx.DmaToolbarWriter} uses {@code
	 * isEmpty()} as the match/replace decision and the lines themselves as the {@code REPLACED} outcome's own
	 * explanation. Recurses depth-first, own-node differences first, then each child in order - a child-count
	 * mismatch is reported once and short-circuits any further per-child comparison at that level (comparing
	 * children pairwise past the shorter list's end would only produce misleading "child N differs" noise).
	 */
	public static List<String> differences(final MasterToolbars.Node pDefinition, final ExistingNode pExisting)
	{
		return ToolbarSubtreeDiff.differences(pDefinition, pExisting, "");
	}

	private static List<String> differences(final MasterToolbars.Node pDefinition, final ExistingNode pExisting, final String pPath)
	{
		final List<String> out = new ArrayList<String>();
		if (pDefinition == null || pExisting == null)
		{
			out.add(pPath + ": one side is null");
			return out;
		}
		final String here = pPath.isEmpty() ? "root" : pPath;
		ToolbarSubtreeDiff.compare(out, here, "type", pDefinition.type, pExisting.type);
		ToolbarSubtreeDiff.compare(out, here, "ordering", pDefinition.ordering, pExisting.ordering);

		// only compared when the node's own type makes the field meaningful - a field irrelevant to this type
		// is skipped entirely, on BOTH sides, so it can never cause a spurious difference (class Javadoc).
		if ("ACTION_BUTTON".equals(pDefinition.type))
		{
			ToolbarSubtreeDiff.compare(out, here, "serviceKey", pDefinition.serviceKey, pExisting.serviceKey);
			ToolbarSubtreeDiff.compare(out, here, "target", pDefinition.target, pExisting.target);
			ToolbarSubtreeDiff.compareServiceParameters(out, here, pDefinition.serviceParameters, pExisting.serviceParameters);
		}
		if ("GROUP_MENU_ITEM".equals(pDefinition.type))
		{
			ToolbarSubtreeDiff.compare(out, here, "menuBuilderKey", pDefinition.menuBuilderKey, pExisting.menuBuilderKey);
		}
		if ("MENU_BUTTON".equals(pDefinition.type))
		{
			// class Javadoc's "one exception" - not cosmetic, this is the icon-default incident's own signal.
			ToolbarSubtreeDiff.compare(out, here, "menuButton icon type", pDefinition.iconType, pExisting.menuButtonIconType);
		}

		if (pDefinition.children.size() != pExisting.children.size())
		{
			out.add(here + ": " + pDefinition.children.size() + " child/children expected, " + pExisting.children.size() + " found");
			return out; // no reliable pairwise comparison past a count mismatch - see class Javadoc
		}
		for (int i = 0; i < pDefinition.children.size(); i++)
		{
			out.addAll(ToolbarSubtreeDiff.differences(pDefinition.children.get(i), pExisting.children.get(i), here + "/child[" + i + "]"));
		}
		return out;
	}

	private static void compareServiceParameters(final List<String> pOut, final String pHere, final List<MasterToolbars.ServiceParameter> pDefined,
		final List<String[]> pExisting)
	{
		if (pDefined.size() != pExisting.size())
		{
			pOut.add(pHere + ": " + pDefined.size() + " serviceParameter(s) expected, " + pExisting.size() + " found");
			return;
		}
		for (int i = 0; i < pDefined.size(); i++)
		{
			final MasterToolbars.ServiceParameter expected = pDefined.get(i);
			final String[] found = pExisting.get(i);
			final String foundName = found == null ? null : found[0];
			final String foundValue = found == null ? null : found[1];
			ToolbarSubtreeDiff.compare(pOut, pHere, "serviceParameters[" + i + "].name", expected.name, foundName);
			ToolbarSubtreeDiff.compare(pOut, pHere, "serviceParameters[" + i + "].value", expected.value, foundValue);
		}
	}

	private static void compare(final List<String> pOut, final String pHere, final String pField, final Object pExpected, final Object pFound)
	{
		if (!ToolbarSubtreeDiff.eq(pExpected, pFound))
		{
			pOut.add(pHere + "." + pField + ": expected " + ToolbarSubtreeDiff.show(pExpected) + ", found " + ToolbarSubtreeDiff.show(pFound));
		}
	}

	private static boolean eq(final Object pA, final Object pB)
	{
		return pA == null ? pB == null : pA.equals(pB);
	}

	private static String show(final Object pValue)
	{
		return pValue == null ? "null" : "\"" + pValue + "\"";
	}
}
