package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Resolves an entity's SOURCE-KEY business attribute(s) - the ones {@code MatchingPolicyTranslator
 * .buildMergePolicy} gives a fixed {@code "no merge"} survivor rule (the same fixed rule the four TECHNICAL
 * fields get, `docs/MATCHING.md` &sect;14.3/&sect;19) - across BOTH shapes a {@code StagingEntity} can have:
 * <ul>
 * <li>a LEAF entity (fed by one landing table): the source key is whichever of its OWN attributes carries
 * {@code businessKey = true} (structurally, never by name - see {@link #isEligibleSourceKey}).</li>
 * <li>a CANONICAL entity ({@code StagingEntity.memberEntities} non-empty, one row per rule-proposed source
 * entity it unifies - `docs/MASTER-ZONE.md`, `ebx.MemberEntitiesBackfill`): the business-key FLAG lives on
 * each MEMBER's own attribute (e.g. {@code Supplier.supplierId}, {@code Account.accountId}), never on the
 * canonical entity's own attribute of the same name (measured live, 2026-09-23: {@code Company.supplierId}
 * itself carries {@code businessKey: false}). This class follows {@code memberEntities}, resolves each
 * member's OWN source key the same LEAF way, and maps it onto the canonical entity's attribute of the SAME
 * NAME - the only mapping mechanism available: neither {@code StagingAttribute.fromRelation}/{@code
 * targetPath} (which record how a REFERENCE attribute was deduced from a {@code LandingRelation}, a
 * different, same-entity-only concept - `docs/MATCHING.md` &sect;19.10) nor any other field records which
 * canonical attribute a member's attribute was unified INTO. Same-name matching is therefore not a fallback
 * of last resort here, it is the ONLY resolution this schema supports today - every caller records that in
 * its own recorded detail via {@link Result#notes} rather than silently presenting it as certain.</li>
 * </ul>
 * The two shapes are not mutually exclusive in this class' own logic: a canonical entity's result is the
 * UNION of its own directly-flagged business-key attributes (the leaf rule, applied to itself) and whatever
 * its members resolve to by same-name mapping - defensive, in case a future project ever flags a business key
 * directly on a canonical entity's own attribute too.
 * <p>
 * Pure: plain Java data in, plain Java data out, no EBX class - the caller ({@code
 * ebx.MatchingConfigurator#readSourceKeyFieldNames}, reused by the survivorship route and by {@code
 * ebx.MatchingPreviewPolicy}'s own preview generation, so all three now resolve the identical way) does every
 * {@code StagingEntity}/{@code StagingAttribute} read and hands in plain {@link AttributeInfo} lists.
 */
public final class SourceKeyResolver
{
	private SourceKeyResolver()
	{
	}

	/** One {@code StagingAttribute} row, as far as this resolver needs to know it - the exact fields {@code ebx.MatchingConfigurator}'s pre-existing leaf rule already reads. */
	public static final class AttributeInfo
	{
		public final String name;
		public final boolean businessKey;
		public final boolean primaryKey;
		/** {@code StagingAttribute.referenceEntity} non-blank - a business-key attribute that is ALSO a foreign key is excluded (see {@link #isEligibleSourceKey}'s own Javadoc for why). */
		public final boolean foreignKey;
		/** {@code TechnicalKeyRule.isAcceptedLike(status)} - computed by the caller, this class never sees the raw status string. */
		public final boolean acceptedLike;

		public AttributeInfo(final String pName, final boolean pBusinessKey, final boolean pPrimaryKey, final boolean pForeignKey, final boolean pAcceptedLike)
		{
			this.name = pName;
			this.businessKey = pBusinessKey;
			this.primaryKey = pPrimaryKey;
			this.foreignKey = pForeignKey;
			this.acceptedLike = pAcceptedLike;
		}
	}

	/** {@link #resolve}'s own result - the resolved field names (canonical, deduplicated, first-appearance order: own attributes first, then one per member in the order given) and a human-readable note per member-resolved entry (or per member entry that could NOT be resolved), for a caller's own recorded detail. */
	public static final class Result
	{
		public final List<String> fieldNames;
		public final List<String> notes;

		Result(final List<String> pFieldNames, final List<String> pNotes)
		{
			this.fieldNames = pFieldNames;
			this.notes = pNotes;
		}
	}

	/**
	 * {@code true} for an attribute this class will declare a stand-alone source-key survivor rule for on
	 * its OWN entity: {@code businessKey}, not also the technical row identity ({@code primaryKey} - already
	 * covered by its own fixed rule), not also a foreign key (deliberately excluded - see {@code
	 * ebx.MatchingConfigurator#readSourceKeyFieldNames}'s own pre-existing Javadoc for why: such an attribute
	 * is promoted as a {@code "...Ref"} field on the master table, a name this class has no reliable way to
	 * reproduce), and accepted-like.
	 */
	public static boolean isEligibleSourceKey(final AttributeInfo pAttribute)
	{
		return pAttribute.businessKey && SourceKeyResolver.isEligibleAsSurvivorTarget(pAttribute);
	}

	/**
	 * The STRUCTURAL half of {@link #isEligibleSourceKey}, WITHOUT the {@code businessKey} flag itself: not
	 * the technical row identity, not a foreign key, accepted-like. This is deliberately the check applied to
	 * the CANONICAL side of a same-name match (see {@link #resolve}) - the whole reason that mapping exists is
	 * that the canonical attribute's OWN {@code businessKey} flag is unreliable/absent for this exact shape
	 * (measured live, 2026-09-23: {@code Company.supplierId} itself carries {@code businessKey: false}), so
	 * requiring it there would make the mapping this class exists for structurally impossible to ever succeed.
	 */
	private static boolean isEligibleAsSurvivorTarget(final AttributeInfo pAttribute)
	{
		return !pAttribute.primaryKey && !pAttribute.foreignKey && pAttribute.acceptedLike;
	}

	/**
	 * @param pOwnAttributes the entity's OWN {@code StagingAttribute} rows (leaf or canonical alike).
	 * @param pMemberAttributesByEntityName empty for a LEAF entity (no {@code memberEntities}); for a
	 *            CANONICAL entity, one entry per member, keyed by the member's own {@code StagingEntity.name}
	 *            (used only for the human-readable note - never re-read from it), valued by THAT member's own
	 *            {@code StagingAttribute} rows. Iteration order is the order {@link Result#notes} is built in.
	 */
	public static Result resolve(final List<AttributeInfo> pOwnAttributes, final Map<String, List<AttributeInfo>> pMemberAttributesByEntityName)
	{
		final Map<String, AttributeInfo> ownByName = new LinkedHashMap<String, AttributeInfo>();
		for (final AttributeInfo attribute : pOwnAttributes)
		{
			if (attribute.name != null && !attribute.name.trim().isEmpty())
			{
				ownByName.put(attribute.name.trim(), attribute);
			}
		}

		final LinkedHashSet<String> names = new LinkedHashSet<String>();
		final List<String> notes = new ArrayList<String>();
		for (final AttributeInfo attribute : pOwnAttributes)
		{
			if (SourceKeyResolver.isEligibleSourceKey(attribute))
			{
				names.add(attribute.name.trim());
			}
		}

		for (final Map.Entry<String, List<AttributeInfo>> member : pMemberAttributesByEntityName.entrySet())
		{
			for (final AttributeInfo memberAttribute : member.getValue())
			{
				if (!SourceKeyResolver.isEligibleSourceKey(memberAttribute))
				{
					continue;
				}
				final String name = memberAttribute.name.trim();
				final AttributeInfo ownMatch = ownByName.get(name);
				if (ownMatch != null && SourceKeyResolver.isEligibleAsSurvivorTarget(ownMatch))
				{
					if (names.add(name))
					{
						notes.add("'" + name + "' resolved as a source key from member entity '" + member.getKey() + "' (its own business-key attribute of the "
							+ "same name) - matched by NAME, the only mapping this schema records (no per-attribute unification field exists).");
					}
				}
				else if (ownMatch != null)
				{
					notes.add("member entity '" + member.getKey() + "'s own business-key attribute '" + name
						+ "' matches a canonical attribute of the same name, but that attribute is not itself eligible (primary key, foreign key, or not "
						+ "accepted-like) - skipped.");
				}
				else
				{
					notes.add("member entity '" + member.getKey() + "'s own business-key attribute '" + name
						+ "' has no same-named attribute on the canonical entity at all - skipped.");
				}
			}
		}

		return new Result(new ArrayList<String>(names), notes);
	}
}
