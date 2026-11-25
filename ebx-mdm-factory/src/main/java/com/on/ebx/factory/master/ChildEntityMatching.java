package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lot M2 (2026-10-17, {@code docs/MATCHING.md} section 57): a CHILD entity - one whose master table holds a foreign key to ANOTHER promoted entity (an address, a phone,
 * a purchase of a person) - gets no matching plan of its own by default. Its records follow the parent: the parent's plan already uses child evidence
 * (section 33) and the master load re-points the children to the golden record.
 * <p>
 * Why (live, 2026-10-17, automated test {@code tools/e2e/b2c_master.sh}): the plans proposed for {@code Adresse} / {@code TelephoneClient} configured fine, then the run of the
 * table ended in a {@code NullPointerException} of the add-on ({@code CascadeMergingService.<init>}, {@code businessObjectModel} is null) and the whole run of that
 * table was rolled back. Read in {@code ebx-addon-mame.log} and in the add-on's bytecode ({@code MatchingQueueConsumer.doAutoMerge}): the merging phase of a table that
 * belongs to a business object asks the table for its business object model, which only the MAIN table of the object has - a MEMBER table answers null. It does not
 * depend on the tree: the failing {@code Adresse} policy had only {@code Suspect} leaves, the matching phase found "MATCH 0, SUSPECT 0", and the 300 groups the merging phase
 * processed were the 300 single-record groups.
 * <p>
 * Pure: no EBX class, Jackson only.
 */
public final class ChildEntityMatching
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Flipped to {@code false} the day the add-on no longer fails the merging phase of a business object MEMBER table (6.3.0.5-RC3 does): the configure of a child's own
	 * plan is then no longer refused for being a member.
	 */
	public static final boolean MEMBER_MERGE_DEFECT_PRESENT = true;

	private ChildEntityMatching()
	{
	}

	// ------------------------------------------------------------------------------------------------ who is a child

	/** Child entity name to the promoted entities it holds a foreign key to (ordered, distinct, never itself); an entity with no such key is absent. */
	public static Map<String, List<String>> parentsByChild(final MasterModel.Result pMasterModel)
	{
		final Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
		if (pMasterModel == null)
		{
			return out;
		}
		for (final MasterModel.ForeignKeyInfo fk : pMasterModel.foreignKeys)
		{
			if (fk.entityName == null || fk.targetEntityName == null || fk.entityName.equals(fk.targetEntityName))
			{
				continue;
			}
			ChildEntityMatching.addDistinct(out, fk.entityName, fk.targetEntityName);
		}
		return ChildEntityMatching.withoutMutualReferences(out);
	}

	/**
	 * The same answer from raw references, for a caller that has no master model (the wizard's snapshot): {@code pReferencesByEntity} maps an entity to the entities its
	 * accepted attributes reference; a reference to a MEMBER entity is read through {@code pCanonicalOfMember} (a member is never promoted, its canonical entity is);
	 * only a promoted target counts.
	 */
	public static Map<String, List<String>> parentsByChild(final Collection<String> pPromoted, final Map<String, List<String>> pReferencesByEntity,
		final Map<String, String> pCanonicalOfMember)
	{
		final Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
		for (final String entity : pPromoted)
		{
			final List<String> references = pReferencesByEntity.get(entity);
			if (references == null)
			{
				continue;
			}
			for (final String reference : references)
			{
				final String target = pCanonicalOfMember.containsKey(reference) ? pCanonicalOfMember.get(reference) : reference;
				if (target != null && !target.equals(entity) && pPromoted.contains(target))
				{
					ChildEntityMatching.addDistinct(out, entity, target);
				}
			}
		}
		return ChildEntityMatching.withoutMutualReferences(out);
	}

	/**
	 * Two entities that reference each other are neither the other's child (each would follow the other and nobody would be matched): the mutual edge is dropped, both
	 * keep their own plan. A longer cycle is not looked for - the master model's own ordering already degrades gracefully on it, and it has not been seen.
	 */
	private static Map<String, List<String>> withoutMutualReferences(final Map<String, List<String>> pParentsByChild)
	{
		final Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
		for (final Map.Entry<String, List<String>> entry : pParentsByChild.entrySet())
		{
			final List<String> kept = new ArrayList<String>();
			for (final String parent : entry.getValue())
			{
				final List<String> parentsOfParent = pParentsByChild.get(parent);
				if (parentsOfParent == null || !parentsOfParent.contains(entry.getKey()))
				{
					kept.add(parent);
				}
			}
			if (!kept.isEmpty())
			{
				out.put(entry.getKey(), kept);
			}
		}
		return out;
	}

	private static void addDistinct(final Map<String, List<String>> pOut, final String pChild, final String pParent)
	{
		List<String> parents = pOut.get(pChild);
		if (parents == null)
		{
			parents = new ArrayList<String>();
			pOut.put(pChild, parents);
		}
		if (!parents.contains(pParent))
		{
			parents.add(pParent);
		}
	}

	/** The parents of {@code pEntity}, empty when it is not a child. */
	public static List<String> parentsOf(final MasterModel.Result pMasterModel, final String pEntity)
	{
		final List<String> parents = ChildEntityMatching.parentsByChild(pMasterModel).get(pEntity);
		return parents == null ? Collections.<String>emptyList() : parents;
	}

	/** Whether the entity is a child that follows its parent (no plan of its own by default). */
	public static boolean follows(final MasterModel.Result pMasterModel, final String pEntity)
	{
		return !ChildEntityMatching.parentsOf(pMasterModel, pEntity).isEmpty();
	}

	// ------------------------------------------------------------------------------------------------ wording

	/** {@code "Adresse follows Personne"} - the one-line statement the proposal, the job messages and the wizard share (English; the wizard has its own localized card). */
	public static String followsText(final String pEntity, final List<String> pParents)
	{
		return pEntity + " follows " + String.join(", ", pParents);
	}

	/** The proposal passes' entry for a child they left without a plan. */
	public static String followsNote(final String pEntity, final List<String> pParents)
	{
		return ChildEntityMatching.followsText(pEntity, pParents) + ": no matching plan of its own (its records follow the parent's matching and the master load re-points them to the golden record).";
	}

	// ------------------------------------------------------------------------------------------------ refusals

	/** Whether a plan's requirements hold a leaf that merges by itself: some node routes to {@code MATCH}. */
	public static boolean hasMatchLeaf(final String pRequirementsJson)
	{
		if (pRequirementsJson == null || pRequirementsJson.trim().isEmpty())
		{
			return false;
		}
		final JsonNode requirements;
		try
		{
			requirements = ChildEntityMatching.MAPPER.readTree(pRequirementsJson);
		}
		catch (final Exception ex)
		{
			return false;
		}
		for (final DecisionTreePlan.Node node : DecisionTreePlan.readFrom(requirements).nodes)
		{
			if (DecisionTreePlan.LEAF_MATCH.equals(node.onTrue) || DecisionTreePlan.LEAF_MATCH.equals(node.onFalse))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Why the plan of a child cannot be accepted (or configured), or null when it may: it holds a {@code MATCH} leaf. A child's own plan is for suspects only - a pair
	 * of addresses is never merged on its own, whatever the parents are.
	 */
	public static String matchLeafRefusal(final String pEntity, final List<String> pParents, final String pRequirementsJson)
	{
		if (pParents == null || pParents.isEmpty() || !ChildEntityMatching.hasMatchLeaf(pRequirementsJson))
		{
			return null;
		}
		return pEntity + " is a child of " + String.join(", ", pParents) + ": its own matching plan may only send pairs to a steward (SUSPECTED), never merge them on its own (a MATCH leaf)."
			+ " Two children with the same values can belong to different parents, and on 6.3.0.5-RC3 the add-on's automatic merge fails on a child table (NullPointerException in"
			+ " CascadeMergingService, no business object model on a member table) and rolls the whole run of the table back. Propose another plan with SUSPECTED leaves only,"
			+ " or leave " + pEntity + " to follow its parent.";
	}

	/**
	 * Why a child's own plan is not configured: its table is a MEMBER of a business object and the add-on's merging phase fails on such a table whatever the tree says
	 * (live 2026-10-17: a plan with {@code Suspect} leaves only, no pair found, still ended in the NullPointerException). Null when the table is not a member, or when
	 * {@link #MEMBER_MERGE_DEFECT_PRESENT} is off.
	 */
	public static String memberRunRefusal(final String pEntity, final List<String> pParents, final boolean pMemberOfBusinessObject, final String pBusinessObject)
	{
		if (!ChildEntityMatching.MEMBER_MERGE_DEFECT_PRESENT || !pMemberOfBusinessObject || pParents == null || pParents.isEmpty())
		{
			return null;
		}
		return pEntity + " is a child of " + String.join(", ", pParents) + " and its table is a member of the business object " + pBusinessObject
			+ ": on 6.3.0.5-RC3 the add-on's merging phase fails on a business object member table (NullPointerException in CascadeMergingService, even when the policy has SUSPECTED"
			+ " leaves only and no pair was found) and rolls the whole run of the table back, so its own plan is not configured. " + pEntity
			+ " follows its parent: the parent's matching already uses its values and the master load re-points it to the golden record.";
	}

	/**
	 * Lot M4 (2026-10-18, {@code docs/MATCHING.md} section 59): does the matching RUN launch on this entity's own table? Not for a child entity (a member of its parent's business
	 * object) while the add-on's merging phase fails on a member table - whatever the status of its plan: a plan an earlier pass APPLIED (kept as it is, {@link #keptAsIsNote}) is not
	 * run alone any more than the inert policy the configure writes on a member. The run of the parent covers it.
	 */
	public static boolean runsAlone(final List<String> pParents)
	{
		return pParents == null || pParents.isEmpty() || !ChildEntityMatching.MEMBER_MERGE_DEFECT_PRESENT;
	}

	/** The reason of a configure that finds a child's own plan already applied: it is left exactly as it is. */
	public static String keptAsIsNote(final String pEntity, final List<String> pParents)
	{
		return pEntity + " (child of " + String.join(", ", pParents) + "): own plan already configured, left as it is. New projects give a child no plan of its own.";
	}

	/** One line for the configure job when a Simulate preview policy could not be removed. */
	public static String previewLeftNote(final String pEntity, final String pPreviewCode, final String pReason)
	{
		return pEntity + ": preview policy " + pPreviewCode + " could not be removed (" + pReason + "); it only runs when someone simulates (manual modes, never the default policy)"
			+ " and does no harm, remove it from the Match and Merge administration screen if you want the table clean.";
	}
}
