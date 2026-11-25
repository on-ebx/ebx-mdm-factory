package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Second permissions pass (2026-10-14): the matching results block of the wizard (step 6) is computed by a background job with the engine's full view
 * and stored ({@code MatchResultsSnapshot}); before it reaches a person it is cut down to what that person's EBX permissions show. Pure: the EBX facts come
 * through {@link Visibility} ({@code ebx.MatchResultsPermissions} reads them), {@code MatchResultsMaskTest} pins the rules.
 * <ul>
 * <li>a pending pair, a "not the same" pair or a merged group is shown only when its records are visible to the person - otherwise it is left out, not
 * shown as "restricted" (that would still say it exists);</li>
 * <li>a merged group keeps only its visible members;</li>
 * <li>a compared field hidden on one side shows no value on that side, and its verdict becomes {@link MatchStats#MISSING} (an "equal" verdict would tell
 * the hidden value); a record whose label could come from a hidden field shows its key instead; a hidden source system shows nothing;</li>
 * <li>the pair total drops by the pairs left out. The KPIs and the band (whole-table aggregates computed by the job) are NOT recomputed per person: they
 * are left out entirely when anything of the block was hidden from that person (the counts would include what they cannot see).</li>
 * </ul>
 */
public final class MatchResultsMask
{
	/** What one person may see of the master table the block describes. */
	public interface Visibility
	{
		/** The record of this key exists and is visible. */
		boolean record(String pKey);

		/** The field of the record of this key is visible (record visible assumed). */
		boolean field(String pKey, String pFieldName);

		/** The master table of the child entity (an address) is visible - what the pair card's child summary rows are read from. Hidden unless an implementation says otherwise. */
		default boolean childTable(final String pEntity)
		{
			return false;
		}
	}

	/** The source-system field of a master table (master.MasterModel: a plain field on every master table). */
	public static final String SOURCE_FIELD = "sourceSystem";
	/** The record's key in its source system (a plain field on every master table). */
	public static final String SOURCE_ID_FIELD = "sourceId";

	private MatchResultsMask()
	{
	}

	/** A copy of {@code pInput} cut down to {@code pSeen}; the stored input is never modified. */
	public static MatchResultsView.Input apply(final MatchResultsView.Input pInput, final List<String> pTitleFields, final Visibility pSeen)
	{
		final MatchResultsView.Input out = new MatchResultsView.Input();
		out.entityId = pInput.entityId;
		out.entityName = pInput.entityName;
		out.colorBySource = pInput.colorBySource;
		out.lastRunText = pInput.lastRunText;
		out.lastRunDetails = pInput.lastRunDetails;
		out.followsParentLines = new ArrayList<String>(pInput.followsParentLines);
		out.maxDetailPairs = pInput.maxDetailPairs;
		out.selectedTab = pInput.selectedTab;
		out.selectedPairId = pInput.selectedPairId;
		out.bulkThresholdPercent = pInput.bulkThresholdPercent;
		out.canDecide = pInput.canDecide;
		out.lastDecisionError = pInput.lastDecisionError;
		out.snapshotState = pInput.snapshotState;
		out.refreshRunning = pInput.refreshRunning;
		out.deferPairs = pInput.deferPairs;
		out.planNodes = pInput.planNodes;
		out.fieldLabels = pInput.fieldLabels;
		boolean hidden = false;

		int dropped = 0;
		final Map<String, MatchResultsView.TreeDetail> trees = pInput.treeDetail == null ? null : new LinkedHashMap<String, MatchResultsView.TreeDetail>();
		for (final MatchStats.SuspectPair pair : pInput.pendingPairs)
		{
			if (!pSeen.record(pair.goldenKey) || !pSeen.record(pair.suspectKey))
			{
				dropped++;
				hidden = true;
				continue;
			}
			final MatchStats.SuspectPair masked = MatchResultsMask.pair(pair, pSeen);
			hidden |= masked != pair;
			out.pendingPairs.add(masked);
			if (trees != null && pInput.treeDetail.containsKey(pair.id))
			{
				trees.put(pair.id, pInput.treeDetail.get(pair.id));
			}
		}
		out.treeDetail = trees;
		// the groups: a group is shown only when every record of it is visible (a hidden record would be a column the person must not see, and leaving it out would let a merge of the
		// others look complete); hidden values are blanked column by column, and what was a hidden field's value tells nothing
		int groupsDropped = 0;
		for (final MatchStats.SuspectGroup group : pInput.pendingGroups)
		{
			boolean visible = true;
			for (final MatchStats.GroupMember member : group.members)
			{
				visible &= pSeen.record(member.key);
			}
			if (!visible)
			{
				groupsDropped++;
				hidden = true;
				continue;
			}
			final MatchStats.SuspectGroup masked = MatchResultsMask.group(group, pSeen);
			hidden |= masked != group;
			out.pendingGroups.add(masked);
		}
		out.pendingGroupsTotal = Math.max(out.pendingGroups.size(), pInput.pendingGroupsTotal - groupsDropped);
		out.pendingPairsTotal = Math.max(out.pendingPairs.size(), pInput.pendingPairsTotal - dropped);

		for (final MatchResultsView.GroupSummary group : pInput.mergedGroups)
		{
			if (!pSeen.record(group.goldenKey))
			{
				hidden = true;
				continue;
			}
			final List<String> keys = new ArrayList<String>();
			final List<String> sources = new ArrayList<String>();
			for (int i = 0; i < group.memberKeys.size(); i++)
			{
				final String key = group.memberKeys.get(i);
				if (!pSeen.record(key))
				{
					hidden = true;
					continue;
				}
				keys.add(key);
				final boolean sourceShown = pSeen.field(key, MatchResultsMask.SOURCE_FIELD);
				hidden |= !sourceShown;
				sources.add(sourceShown && i < group.memberSources.size() ? group.memberSources.get(i) : "");
			}
			final boolean labelShown = MatchResultsMask.titleShown(group.goldenKey, pTitleFields, pSeen);
			final boolean sourceShown = pSeen.field(group.goldenKey, MatchResultsMask.SOURCE_FIELD);
			hidden |= !labelShown || !sourceShown;
			out.mergedGroups.add(new MatchResultsView.GroupSummary(group.goldenKey, labelShown ? group.goldenLabel : group.goldenKey,
				sourceShown ? group.goldenSource : "", sources, keys));
		}

		for (final MatchResultsView.DistinctPair pair : pInput.distinctPairs)
		{
			if (pSeen.record(pair.sourceId) && pSeen.record(pair.targetId))
			{
				out.distinctPairs.add(pair);
			}
			else
			{
				hidden = true;
			}
		}

		// the whole-table aggregates were counted by the job over every record: shown only when nothing of the block was hidden from this person
		out.kpis = hidden ? null : pInput.kpis;
		out.band = hidden ? new ArrayList<MatchStats.BandSegment>() : pInput.band;
		out.goldensWithSuspects = hidden ? 0L : pInput.goldensWithSuspects;
		return out;
	}

	/** The group itself when nothing of it is hidden; otherwise a copy with the hidden values blanked (a hidden row of a column gives no value, a hidden source gives no column header). */
	private static MatchStats.SuspectGroup group(final MatchStats.SuspectGroup pGroup, final Visibility pSeen)
	{
		boolean changed = false;
		final List<MatchStats.GroupMember> members = new ArrayList<MatchStats.GroupMember>();
		for (final MatchStats.GroupMember member : pGroup.members)
		{
			final boolean source = pSeen.field(member.key, MatchResultsMask.SOURCE_FIELD);
			final boolean sourceId = member.sourceId.isEmpty() || pSeen.field(member.key, MatchResultsMask.SOURCE_ID_FIELD);
			// the label was built from the entity's fields by the job (its own field list is not stored): kept only when every field of the record is visible, else the key stands in
			boolean labelShown = true;
			for (final MatchStats.GroupRow row : pGroup.rows)
			{
				if (!row.name.startsWith(MatchStats.CHILD_PREFIX) && !pSeen.field(member.key, row.name))
				{
					labelShown = false;
				}
			}
			if (source && sourceId && labelShown)
			{
				members.add(member);
			}
			else
			{
				changed = true;
				members.add(new MatchStats.GroupMember(member.key, source ? member.source : "", source && sourceId ? member.sourceId : "", labelShown ? member.label : "", member.golden));
			}
		}
		final List<MatchStats.GroupRow> rows = new ArrayList<MatchStats.GroupRow>();
		for (final MatchStats.GroupRow row : pGroup.rows)
		{
			final boolean child = row.name.startsWith(MatchStats.CHILD_PREFIX);
			final List<String> values = new ArrayList<String>();
			final List<String> raws = new ArrayList<String>();
			boolean rowChanged = false;
			for (int i = 0; i < pGroup.members.size(); i++)
			{
				final String key = pGroup.members.get(i).key;
				final boolean shown = child ? pSeen.childTable(row.name.substring(MatchStats.CHILD_PREFIX.length())) : pSeen.field(key, row.name);
				final boolean rawShown = row.rawName.isEmpty() || pSeen.field(key, row.rawName);
				values.add(shown ? row.value(i) : "");
				raws.add(shown && rawShown ? row.raw(i) : "");
				rowChanged |= !shown || !rawShown;
			}
			changed |= rowChanged;
			rows.add(rowChanged ? new MatchStats.GroupRow(row.label, row.name, row.rawName, row.glance, values, raws) : row);
		}
		return changed ? new MatchStats.SuspectGroup(pGroup.id, members, rows, pGroup.pairs, pGroup.nodes) : pGroup;
	}

	/** The pair itself when nothing of it is hidden; otherwise a copy with the hidden values blanked. */
	private static MatchStats.SuspectPair pair(final MatchStats.SuspectPair pPair, final Visibility pSeen)
	{
		boolean changed = false;
		final List<MatchStats.FieldComparison> fields = new ArrayList<MatchStats.FieldComparison>();
		for (final MatchStats.FieldComparison field : pPair.fields)
		{
			final boolean child = field.name.startsWith(MatchStats.CHILD_PREFIX);
			final boolean left = child ? pSeen.childTable(field.name.substring(MatchStats.CHILD_PREFIX.length())) : pSeen.field(pPair.goldenKey, field.name);
			final boolean right = child ? left : pSeen.field(pPair.suspectKey, field.name);
			final boolean rawLeft = field.rawName.isEmpty() || pSeen.field(pPair.goldenKey, field.rawName);
			final boolean rawRight = field.rawName.isEmpty() || pSeen.field(pPair.suspectKey, field.rawName);
			if (left && right && rawLeft && rawRight)
			{
				fields.add(field);
				continue;
			}
			changed = true;
			fields.add(field.hidden(left, right, left && rawLeft, right && rawRight));
		}
		final boolean goldenLabel = MatchResultsMask.labelShown(pPair.goldenKey, pPair.fields, pSeen);
		final boolean suspectLabel = MatchResultsMask.labelShown(pPair.suspectKey, pPair.fields, pSeen);
		final boolean goldenSource = pSeen.field(pPair.goldenKey, MatchResultsMask.SOURCE_FIELD);
		final boolean suspectSource = pSeen.field(pPair.suspectKey, MatchResultsMask.SOURCE_FIELD);
		final boolean goldenSourceId = pPair.goldenSourceId.isEmpty() || pSeen.field(pPair.goldenKey, MatchResultsMask.SOURCE_ID_FIELD);
		final boolean suspectSourceId = pPair.suspectSourceId.isEmpty() || pSeen.field(pPair.suspectKey, MatchResultsMask.SOURCE_ID_FIELD);
		if (!changed && goldenLabel && suspectLabel && goldenSource && suspectSource && goldenSourceId && suspectSourceId)
		{
			return pPair;
		}
		return new MatchStats.SuspectPair(pPair.id, pPair.goldenKey, pPair.suspectKey, goldenLabel ? pPair.goldenLabel : pPair.goldenKey,
			suspectLabel ? pPair.suspectLabel : pPair.suspectKey, goldenSource ? pPair.goldenSource : "", suspectSource ? pPair.suspectSource : "",
			goldenSource && goldenSourceId ? pPair.goldenSourceId : "", suspectSource && suspectSourceId ? pPair.suspectSourceId : "", fields, pPair.decision);
	}

	/** A merged group's golden label was built from the entity's compared fields ({@code MatchingResultsBuilder.titleOf}): kept only when all are visible. */
	private static boolean titleShown(final String pKey, final List<String> pTitleFields, final Visibility pSeen)
	{
		if (pTitleFields == null)
		{
			return false; // unknown title fields: the key, never a label that might quote a hidden value
		}
		for (final String field : pTitleFields)
		{
			if (!pSeen.field(pKey, field))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * The engine built a pair's labels from the compared fields ({@code MatchingResultsBuilder.titleOf}): a label is kept only when every compared field of
	 * its record is visible - otherwise the key stands in for it.
	 */
	private static boolean labelShown(final String pKey, final List<MatchStats.FieldComparison> pFields, final Visibility pSeen)
	{
		for (final MatchStats.FieldComparison field : pFields)
		{
			// the child summary rows are not fields of the record; the sourceId shown with the label is read from the source field already checked
			if (!field.name.startsWith(MatchStats.CHILD_PREFIX) && !pSeen.field(pKey, field.name))
			{
				return false;
			}
		}
		return true;
	}
}
