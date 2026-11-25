package com.on.ebx.factory.distribution;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Distribution lot 1 follow-up (live BLOCKER, 2026-09-25, DistribTest, mapping 300): {@code
 * DistributionConfigurator} created the OUTBOUND mapping with {@code rules: []} - read back live with {@code
 * connectors_mapping_get}: {@code inputs {mergedRecords: exclude}, rules [], children []}. Connectors' {@code
 * MappingEngine.buildOutbound} builds the outbound document rule by rule, so with none at all every message
 * sent would be an empty JSON object {@code {}}. This class builds the missing {@code rules[]}: one rule per
 * business field of the master table (source {@code ./<field>}, target {@code <field>}, policy {@code ALWAYS},
 * no transformation), the table's own key ({@code id}) and {@code sourceSystem}/{@code sourceId} (a consumer
 * needs them to correlate a record back to its origin), and, when the target's {@code includeCrosswalk} flag
 * is set, the golden id as {@code goldenId} via the same {@code golden(...)} transformation {@code
 * ebx.DataProductPublication} already uses for a crosswalk column.
 * <p>
 * Never a MAME/technical linked field ({@code Linked_Field_*}, {@code GoldenId}, {@code GroupId}, {@code
 * HasSuspects}, {@code AutoCreated}, {@code FunctionalId}) nor the load-bookkeeping fields ({@code _loadId},
 * {@code _receivedAt}) - a consumer has no use for the add-on's own internal state, and {@code goldenId} is
 * covered by the crosswalk rule above instead (a plain pass-through of the technical {@code GoldenId} field
 * would read {@code null} for a still-GOLDEN record, unlike {@code golden(...)}, which resolves through a
 * MERGED chain).
 * <p>
 * <b>Pure</b>: takes plain strings, no {@code master.MasterModel} import (the {@code distribution} package
 * stays EBX/master-model-free, same layering {@code distribution.product.ProductPlan} already uses - the
 * caller, {@code ebx.DistributionConfigurator}, resolves the master model's field names and hands over plain
 * strings). Unit-tested directly.
 */
public final class DistributionMappingRules
{
	/** The fields every master table carries on its own ({@code master.MasterFieldNameRule.RESERVED}, minus {@code _loadId}/{@code _receivedAt} which are never sent) - kept, never re-derived from a business-field list. */
	private static final String[] ALWAYS_KEPT = { "id", "sourceSystem", "sourceId" };
	/** Bookkeeping fields every master table carries, never sent to a consumer. */
	private static final String[] BOOKKEEPING = { "_loadId", "_receivedAt" };
	/** MAME technical fields (CLAUDE.md sec 8) a table may carry once Match and Merge is activated on it - never sent as plain fields (the crosswalk rule covers {@code goldenId} through {@code golden(...)} instead). */
	private static final String[] MAME_TECHNICAL = { "GoldenId", "GroupId", "HasSuspects", "AutoCreated", "FunctionalId" };

	/**
	 * Lot S2 (2026-10-18): the member every mapped record carries to say what the message means, see {@link NoticeDocument}. The value is the constant
	 * {@code UPSERT}: Connectors builds the document from the record's current state when it sends, so CREATE and MODIFY cannot be told apart there.
	 */
	public static final String CHANGE_FIELD = NoticeDocument.CHANGE_MEMBER;

	private DistributionMappingRules()
	{
	}

	/** One {@code rules[]} entry - {@code transformation} is {@code ""} for a plain pass-through, never null. */
	public static final class Rule
	{
		public final String sourcePath;
		public final String targetPath;
		public final String transformation;
		public final String policy;

		public Rule(final String pSourcePath, final String pTargetPath, final String pTransformation, final String pPolicy)
		{
			this.sourcePath = pSourcePath;
			this.targetPath = pTargetPath;
			this.transformation = pTransformation == null ? "" : pTransformation;
			this.policy = pPolicy;
		}
	}

	/**
	 * @param pBusinessFieldNames every business field name of the master table (staging-promoted attributes and
	 *            foreign keys, {@code master.MasterModel.FieldInfo#masterField}/{@code ForeignKeyInfo#fieldName})
	 *            - a name already equal to one of {@link #ALWAYS_KEPT}/{@link #BOOKKEEPING}/{@link
	 *            #MAME_TECHNICAL} (case-insensitively) is skipped, never duplicated
	 * @param pIncludeCrosswalk whether to add the {@code goldenId} rule
	 * @param pMasterTablePath the master table's own path (e.g. {@code /root/Company}) - the {@code golden(...)} transformation's first argument
	 * @param pMasterDataspace the master dataspace key - {@code golden(...)}'s second argument
	 * @param pMasterDataset the master dataset name - {@code golden(...)}'s third argument
	 */
	public static List<Rule> build(final List<String> pBusinessFieldNames, final boolean pIncludeCrosswalk, final String pMasterTablePath,
		final String pMasterDataspace, final String pMasterDataset)
	{
		final List<Rule> out = new ArrayList<Rule>();
		for (final String kept : DistributionMappingRules.ALWAYS_KEPT)
		{
			out.add(new Rule("./" + kept, kept, "", "ALWAYS"));
		}
		final Set<String> seen = new LinkedHashSet<String>();
		for (final String kept : DistributionMappingRules.ALWAYS_KEPT)
		{
			seen.add(kept.toLowerCase(Locale.ROOT));
		}
		if (pBusinessFieldNames != null)
		{
			for (final String field : pBusinessFieldNames)
			{
				if (field == null || field.trim().isEmpty())
				{
					continue;
				}
				final String lower = field.toLowerCase(Locale.ROOT);
				if (!seen.add(lower) || DistributionMappingRules.isExcluded(lower))
				{
					continue;
				}
				out.add(new Rule("./" + field, field, "", "ALWAYS"));
			}
		}
		out.add(new Rule("./id", DistributionMappingRules.CHANGE_FIELD, "constant(" + NoticeDocument.UPSERT + ")", "ALWAYS"));
		if (pIncludeCrosswalk)
		{
			out.add(new Rule("./id", "goldenId", "golden(" + pMasterTablePath + "," + pMasterDataspace + "," + pMasterDataset + ")", "ALWAYS"));
		}
		return out;
	}

	private static boolean isExcluded(final String pLowerName)
	{
		for (final String bookkeeping : DistributionMappingRules.BOOKKEEPING)
		{
			if (bookkeeping.toLowerCase(Locale.ROOT).equals(pLowerName))
			{
				return true;
			}
		}
		for (final String technical : DistributionMappingRules.MAME_TECHNICAL)
		{
			if (technical.toLowerCase(Locale.ROOT).equals(pLowerName))
			{
				return true;
			}
		}
		return pLowerName.startsWith("linked_field_");
	}

	/**
	 * Re-configuring an already-configured target (Configure re-run) must stay idempotent on OUR OWN rules -
	 * update in place, never append a second copy - while keeping a rule a person added BY HAND directly on
	 * the mapping, cheaply detectable as "every existing rule whose targetPath is not one WE generate" (this
	 * class owns a fixed, known set of target field names, so anything else on the mapping was not put there by
	 * this class). {@code pGenerated} always wins on a targetPath it also owns; a hand-added rule survives,
	 * appended after ours, in its original relative order.
	 */
	public static List<Rule> merge(final List<Rule> pGenerated, final List<Rule> pExisting)
	{
		final List<Rule> out = new ArrayList<Rule>(pGenerated);
		if (pExisting == null || pExisting.isEmpty())
		{
			return out;
		}
		final Set<String> ownedTargets = new LinkedHashSet<String>();
		for (final Rule rule : pGenerated)
		{
			ownedTargets.add(rule.targetPath);
		}
		for (final Rule existingRule : pExisting)
		{
			if (!ownedTargets.contains(existingRule.targetPath))
			{
				out.add(existingRule);
			}
		}
		return out;
	}
}
