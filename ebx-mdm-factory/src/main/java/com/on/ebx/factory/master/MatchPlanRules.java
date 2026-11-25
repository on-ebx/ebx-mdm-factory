package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Deterministic (no language model, no MCP call) proposal of a {@code MatchPlan} per PROMOTED staging
 * entity, from the entity's own accepted attributes and the accepted {@code QualityRule} evidence already
 * gathered on it (`docs/MATCHING.md` &sect;2). Pure: no EBX class anywhere in this file, so unit-tested
 * without a running instance, the same convention as {@link MasterModel}/{@code staging.StagingRules}.
 * <p>
 * <b>Domain-free by design (2026-09-21 rework).</b> An earlier version of this class imitated the live
 * {@code Companies} matching policy field-role by field-role - fuzzy anchor = the first attribute whose
 * name contained {@code "name"}, blocking = an attribute named exactly {@code country}, supporting = an
 * attribute named exactly {@code city}. That is company-specific naming knowledge, useless (and actively
 * misleading) on a product, material or site repository, and the product owner decided to remove it
 * outright rather than generalize it: field-ROLE assignment beyond the one structural signal below is now
 * the job of the language-model pass ({@code ebx.MatchPlanLlmSuggester}/{@code master.MatchPlanLlmPass}),
 * which reasons from real per-attribute metadata and profiling numbers instead of a field's name. This
 * class keeps only what is genuinely structural - true of every domain, not particular to company data:
 * <ul>
 * <li><b>EXACT, MATCH-terminal</b> - an attribute carrying an ACCEPTED-or-APPLIED {@code QualityRule} of
 * kind {@code CHECK_DIGIT}: a validated identifier (whatever KIND of identifier it validates - a VAT
 * number, a GTIN, a serial number, anything a check-digit rule was ever attached to; this class never
 * branches on the specific {@code identifierKind} string) is exactly the kind of field a decision tree can
 * safely terminate {@code Match} on with {@code minimumScore 100}, algorithm {@code ebx-addon-mame@exact}.
 * Each one becomes its own exact-match tier (an entity can carry more than one validated identifier).</li>
 * <li><b>2026-09-22, reversed (`docs/MATCHING.md`): a child entity now gets a plan proposed exactly like any
 * other entity.</b> An earlier revision of this class skipped an entity carrying an accepted attribute whose
 * {@code referenceEntity} names a DIFFERENT entity (a self-reference does not count) outright, on the theory
 * that de-duplicating a child independently of its parent risks a suspect queue that never converges. the product owner
 * reversed that decision: a child (a Contact, say) is exactly the kind of data that needs deduplication - the
 * same person entered twice, or attached to two parents that later merge. {@link #childLink} (kept, renamed
 * from the old {@code childOf}) is now used for two things only: (a) informational rationale wording, and
 * (b) offering the child's own reference attribute to {@link #propose} as an ADDITIONAL clustering candidate
 * (never a tier field - this class has no fuzzy/weighted AVERAGE tier machinery of its own, only the EXACT
 * identifier tier, and forcing a parent-reference field into an "all fields match" tier would make a mismatched
 * parent wrongly exclude an otherwise-good candidate - the opposite of "supporting"; the language-model pass,
 * which DOES build weighted tiers, offers it there too, as a genuine lower-weight supporting field - see
 * {@code MatchPlanLlmPass}'s own Javadoc), subject to the SAME {@link CrossSystemFieldGuard} discipline as any
 * other clustering field - never special-cased to always pass or always fail. Structural, not domain
 * knowledge: true of a Contact/Company pair exactly as much as a LineItem/Order pair. <b>This method itself
 * trusts {@code AttributeInfo#referenceEntity} at face value</b> - it is the CALLER's job (only real one:
 * {@code ebx.MatchPlanSuggester#toRuleEntity}) to hand in an already-RESOLVED name (via {@code
 * master.MasterModel.Result#resolveReference} - a reference to a MEMBER entity, e.g. this project's {@code
 * Contact.accountId -> Account} where {@code Account} is a member of the canonical {@code Company}, must
 * arrive here already redirected to {@code Company}, coordinator review 2026-09-21), never the raw, possibly-
 * never-promoted {@code StagingAttribute.referenceEntity}.</li>
 * <li><b>{@code autoMerge} and the merge-policy hints are fixed defaults</b> - {@link #propose} never
 * decides {@code autoMerge}: every generated plan defaults to {@code false} (the schema's own default,
 * `tools/build_xsd.py` {@code MatchPlan.autoMerge}), and the merge-policy hints ({@code ONLY_DUPLICATES} /
 * {@code mostCompleted} / {@code longest}) are the same generic, safe defaults regardless of what the
 * entity represents - every match this rule proposes is routed to the suspects for a human to confirm.
 * These are policy defaults, not a recipe read off company field names, so they stay here.</li>
 * </ul>
 * <b>An entity with no validated identifier is skipped, named, rather than emit a matching plan with
 * nothing to match on</b> - this class no longer guesses a fuzzy anchor from a field's name; a data steward
 * (or the LLM pass, which DOES look at fuzzy candidates through real metadata and profiling) supplies one
 * instead. See `docs/MATCHING.md` for the full story of what was removed and why.
 * <p>
 * <b>2026-09-21, "Rapprochement" - a validated identifier this class would propose as BOTH an exact tier and
 * a clustering field is subject to the same structural cross-system usability guard the language-model pass
 * enforces</b> ({@link CrossSystemFieldGuard} - "a rule must not propose what the guard would refuse"): when
 * a caller wires the two live lookups (see the {@link #propose(List, MatchPlanLlmPass.CrossSystemUsageLookup,
 * MatchPlanLlmPass.PerSystemFillLookup)} overload), an identifier that cannot actually bridge two source
 * systems (zero shared values, or entirely empty for one of them) is excluded from the proposal - not
 * refused outright, since another validated identifier on the same entity might still pass. The 1-arg {@link
 * #propose(List)} overload passes no lookups at all and keeps this class' original, unguarded behaviour
 * exactly (both null is treated as "the guard was never wired", not as "every measurement failed" - see
 * {@code proposeOne}'s own comment).
 * <p>
 * <b>2026-09-22, matching-key contract (product-owner decision, `PLAN.md`) - a validated identifier compared
 * or clustered on here is substituted with its own derived matching key when it has one</b> ({@link
 * AttributeInfo#matchKey}, exactly the substitution {@link MatchPlanLlmPass.Session#proposeMatchPlan} applies
 * to the language-model route): the exact tier's own field, and the matching {@code clusteringFields} entry,
 * name the key (e.g. {@code cityKey}) instead of the source attribute (e.g. {@code city}) whenever one
 * exists - the display attribute keeps its accents/case, the normalised key is what gets compared. This is
 * purely defensive symmetry today: per the other agent's own contract a derived attribute never carries a
 * quality rule, so a validated identifier ({@link AttributeInfo#identifierKind} set) is never ITSELF a
 * derived key - only a source attribute might happen to also be a validated identifier AND have its own key.
 */
public final class MatchPlanRules
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The exact algorithm code {@code matching_config_schema} enumerates for an exact match (`docs/MATCHING.md` &sect;3). */
	private static final String ALGORITHM_EXACT = "ebx-addon-mame@exact";

	private MatchPlanRules()
	{
	}

	// --------------------------------------------------------------------------------------------------------- input

	public static final class AttributeInfo
	{
		public final String name;
		public final String dataType;
		/** Null unless this attribute is a foreign key to another staging entity (its target entity's name). */
		public final String referenceEntity;
		/** Non-null (any kind string, e.g. {@code IBAN}/{@code LEI}/{@code GTIN}) when an ACCEPTED-or-APPLIED {@code QualityRule} of kind CHECK_DIGIT names it; null otherwise. Never branched on by kind - see class Javadoc. */
		public final String identifierKind;
		/**
		 * Matching-key contract, 2026-09-22 (`docs/MATCHING.md`): non-null only when this attribute has its own
		 * derived matching key ({@code StagingAttribute.derivation} = {@code FOLD_KEY} on a sibling attribute
		 * whose {@code derivedFrom} names this one) - the key's own NAME. Per that contract a derived attribute
		 * never carries a quality rule, so {@link #identifierKind} is never set on the KEY itself; this field is
		 * instead read on the SOURCE, to let {@link #propose} compare/cluster via the key when one exists.
		 */
		public final String matchKey;

		/** Convenience for every caller built before the 2026-09-22 matching-key contract (no key to give) - equivalent to passing a null {@code pMatchKey}. */
		public AttributeInfo(final String pName, final String pDataType, final String pReferenceEntity, final String pIdentifierKind)
		{
			this(pName, pDataType, pReferenceEntity, pIdentifierKind, null);
		}

		public AttributeInfo(final String pName, final String pDataType, final String pReferenceEntity, final String pIdentifierKind, final String pMatchKey)
		{
			this.name = pName;
			this.dataType = pDataType;
			this.referenceEntity = pReferenceEntity;
			this.identifierKind = pIdentifierKind;
			this.matchKey = pMatchKey;
		}
	}

	public static final class EntityInfo
	{
		public final String name;
		public final List<AttributeInfo> attributes;

		public EntityInfo(final String pName, final List<AttributeInfo> pAttributes)
		{
			this.name = pName;
			this.attributes = pAttributes == null ? new ArrayList<AttributeInfo>() : pAttributes;
		}
	}

	// -------------------------------------------------------------------------------------------------------- output

	public static final class Proposal
	{
		public final String entityName;
		public final boolean propose;
		/** Non-null only when {@link #propose} is false: why this entity was skipped. */
		public final String skipReason;
		/** Non-null only when {@link #propose} is true: the structured requirements JSON for {@code MatchPlan.requirements} - see class Javadoc and `docs/MATCHING.md` &sect;3 for the shape. */
		public final String requirementsJson;
		public final String rationale;
		public final int confidence;
		/** Non-null only when {@link #propose} is true: {@code MatchPlanEvidence.toJson}'s own shape, for {@code MatchPlan.evidence} - see that class' Javadoc. */
		public final String evidenceJson;

		private Proposal(final String pEntityName, final boolean pPropose, final String pSkipReason, final String pRequirementsJson, final String pRationale,
			final int pConfidence, final String pEvidenceJson)
		{
			this.entityName = pEntityName;
			this.propose = pPropose;
			this.skipReason = pSkipReason;
			this.requirementsJson = pRequirementsJson;
			this.rationale = pRationale;
			this.confidence = pConfidence;
			this.evidenceJson = pEvidenceJson;
		}
	}

	// --------------------------------------------------------------------------------------------------------- build

	public static List<Proposal> propose(final List<EntityInfo> pEntities)
	{
		return MatchPlanRules.propose(pEntities, null, null, null);
	}

	/**
	 * @param pSharedCountLookup the first of the two live lookups {@link CrossSystemFieldGuard} needs (how many
	 * distinct values of a field are shared by at least two source systems).
	 * @param pPerSystemFillLookup the second (per source system, how many rows have a field filled) -
	 * `docs/MATCHING.md`, `PLAN.md` "Rapprochement", 2026-09-21. Both null (the {@link #propose(List)}
	 * overload's own behaviour) means "the guard was never wired for this call", never checked at all, exactly
	 * this class' original behaviour before this guard existed. Either non-null activates it for every
	 * candidate identifier of every entity. Equivalent to passing a null {@code pWithinSystemDuplicateLookup} -
	 * see the 4-arg overload.
	 */
	public static List<Proposal> propose(final List<EntityInfo> pEntities, final MatchPlanLlmPass.CrossSystemUsageLookup pSharedCountLookup,
		final MatchPlanLlmPass.PerSystemFillLookup pPerSystemFillLookup)
	{
		return MatchPlanRules.propose(pEntities, pSharedCountLookup, pPerSystemFillLookup, null);
	}

	/**
	 * @param pWithinSystemDuplicateLookup 2026-09-22, "child entities matched too": the within-system mirror of
	 * {@code pSharedCountLookup} - see {@link MatchPlanLlmPass.WithinSystemDuplicateLookup}'s own Javadoc. Null
	 * (every caller before this lot) means "not wired", read the same as the other two lookups being null:
	 * a single-source-system finding then falls back to the old generic "does not apply" wording.
	 */
	public static List<Proposal> propose(final List<EntityInfo> pEntities, final MatchPlanLlmPass.CrossSystemUsageLookup pSharedCountLookup,
		final MatchPlanLlmPass.PerSystemFillLookup pPerSystemFillLookup, final MatchPlanLlmPass.WithinSystemDuplicateLookup pWithinSystemDuplicateLookup)
	{
		final List<Proposal> out = new ArrayList<Proposal>();
		for (final EntityInfo entity : pEntities)
		{
			out.add(MatchPlanRules.proposeOne(entity, pSharedCountLookup, pPerSystemFillLookup, pWithinSystemDuplicateLookup));
		}
		return out;
	}

	private static Proposal proposeOne(final EntityInfo pEntity, final MatchPlanLlmPass.CrossSystemUsageLookup pSharedCountLookup,
		final MatchPlanLlmPass.PerSystemFillLookup pPerSystemFillLookup, final MatchPlanLlmPass.WithinSystemDuplicateLookup pWithinSystemDuplicateLookup)
	{
		// 2026-09-22, reversed: a child entity (an accepted attribute referencing a different entity) is no longer
		// skipped - it is proposed exactly like any other entity, provided it too carries a validated identifier
		// (the "no validated identifier" skip below applies equally to a child and a non-child). childLink is kept
		// only for (a) informational rationale wording and (b) offering the reference attribute as an ADDITIONAL
		// clustering candidate once the entity is known to be proposed - see class Javadoc.
		final ChildLink childLink = MatchPlanRules.childLink(pEntity);

		final List<AttributeInfo> candidateIdentifiers = new ArrayList<AttributeInfo>();
		for (final AttributeInfo attribute : pEntity.attributes)
		{
			if (attribute.identifierKind != null && !attribute.identifierKind.trim().isEmpty())
			{
				candidateIdentifiers.add(attribute);
			}
		}

		if (candidateIdentifiers.isEmpty())
		{
			return new Proposal(pEntity.name, false,
				"no validated identifier (an accepted CHECK_DIGIT quality rule) found on any attribute: nothing to match on deterministically - "
					+ "use the language-model matching pass (matching/suggest-llm) to propose fuzzy/blocking fields from the real attribute metadata instead.",
				null, null, 0, null);
		}

		// Cross-system usability guard (CrossSystemFieldGuard, `PLAN.md` "Rapprochement", 2026-09-21): this class
		// proposes each validated identifier as BOTH an exact tier and a clustering field (below), so it must not
		// propose an identifier the guard would itself refuse the language-model pass for proposing. "Not wired"
		// (both lookups null - MatchPlanRules.propose(List)'s own contract) is deliberately different from "every
		// measurement failed" (a real lookup answering null every time): the former never touches
		// CrossSystemFieldGuard at all, preserving this class' original, unguarded behaviour byte-for-byte.
		final boolean guardWired = pSharedCountLookup != null || pPerSystemFillLookup != null;
		final List<AttributeInfo> usableIdentifiers = new ArrayList<AttributeInfo>();
		final List<String> excludedIdentifiers = new ArrayList<String>();
		final List<String> notVerifiedIdentifiers = new ArrayList<String>();
		/** {@link CrossSystemFieldGuard.Check#evidence} of every identifier the guard actually measured and accepted - always recorded when the guard is wired, not only when the share is low (coordinator review, 2026-09-21, second pass: "a measurement made for the guard must reach the plan's stored rationale when accepted", both routes). No distinct-value-count in this pure pass (unlike the language-model pass' own profiling), so the evidence text has no "out of N distinct" phrase - see {@code evaluate}'s own {@code pDistinctCount} parameter, passed null below. */
		final List<String> usableEvidence = new ArrayList<String>();
		/** review-match-plan lot (2026-09-22): the SAME per-field facts as {@code usableEvidence}/{@code excludedIdentifiers}/{@code notVerifiedIdentifiers} above, structured instead of prose - see {@link MatchPlanEvidence}. Every candidate identifier gets exactly one entry, whatever it does (accepted, refused, not verified, or the guard not wired at all). */
		final List<MatchPlanEvidence.Field> evidenceFields = new ArrayList<MatchPlanEvidence.Field>();
		/** 2026-09-22: per-field single-source-system findings (a real within-system-duplicate count when {@code pWithinSystemDuplicateLookup} answered one, else a generic fallback) - replaces the old single blanket "does not apply" sentence, see {@code MatchPlanLlmPass.Session}'s own identical change for the full reasoning (CLAUDE.md &sect;8). */
		final List<String> singleSourceNotes = new ArrayList<String>();
		boolean singleSourceSystem = false;
		for (final AttributeInfo candidate : candidateIdentifiers)
		{
			final String role = MatchPlanEvidence.combineRole(MatchPlanEvidence.ROLE_SUPPORTING_EXACT, true);
			if (!guardWired)
			{
				usableIdentifiers.add(candidate);
				evidenceFields.add(new MatchPlanEvidence.Field(candidate.matchKey == null ? candidate.name : candidate.matchKey, role, null, null, null,
					MatchPlanEvidence.VERDICT_NOT_MEASURED, "The cross-system usability guard was not run for this pass."));
				continue;
			}
			// Matching-key substitution (2026-09-22 contract, `docs/MATCHING.md`, applied identically to the
			// language-model pass' own Session.proposeMatchPlan): a validated identifier compared/clustered via its
			// own derived matching key, when it has one, is measured on the KEY - never the source - exactly the
			// field name that will actually be compared/clustered below. A validated identifier itself is never a
			// derived key (the other agent's contract: a derived attribute carries no quality rule), so this is
			// purely defensive symmetry, not a case observed to occur in practice yet.
			final String comparedField = candidate.matchKey == null ? candidate.name : candidate.matchKey;
			final Integer shared = pSharedCountLookup == null ? null : pSharedCountLookup.sharedCount(pEntity.name, comparedField);
			final Map<String, Long> perSystem = pPerSystemFillLookup == null ? null : pPerSystemFillLookup.filledCountBySystem(pEntity.name, comparedField);
			// 2026-09-22: only measured once perSystem already shows a single-source table - see
			// MatchPlanLlmPass.WithinSystemDuplicateLookup's own Javadoc for why this is never measured up front.
			final Integer withinSystemDuplicate = perSystem != null && perSystem.size() < 2 && pWithinSystemDuplicateLookup != null
				? pWithinSystemDuplicateLookup.duplicateCount(pEntity.name, comparedField) : null;
			final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate(comparedField, shared, perSystem, null, null, withinSystemDuplicate);
			if (check.refuse)
			{
				excludedIdentifiers.add(candidate.name + " (" + candidate.identifierKind + "): " + check.refusalDetail);
				evidenceFields.add(new MatchPlanEvidence.Field(comparedField, MatchPlanEvidence.ROLE_REJECTED, shared, null, perSystem,
					MatchPlanEvidence.VERDICT_REFUSED, check.refusalDetail));
				continue;
			}
			if (!check.guardApplicable)
			{
				singleSourceSystem = true;
				final String note = check.evidence != null ? check.evidence
					: "This entity's master table holds rows from a single source system: the cross-system usability guard does not apply.";
				if (!singleSourceNotes.contains(note))
				{
					singleSourceNotes.add(note);
				}
				evidenceFields.add(new MatchPlanEvidence.Field(comparedField, role, null, null, null, MatchPlanEvidence.VERDICT_NOT_MEASURED, note));
			}
			else if (check.unverifiedDetail != null)
			{
				notVerifiedIdentifiers.add(check.unverifiedDetail);
				evidenceFields.add(new MatchPlanEvidence.Field(comparedField, role, shared, null, perSystem, MatchPlanEvidence.VERDICT_NOT_MEASURED, check.unverifiedDetail));
			}
			else if (check.evidence != null)
			{
				usableEvidence.add(check.evidence);
				evidenceFields.add(new MatchPlanEvidence.Field(comparedField, role, shared, null, perSystem, MatchPlanEvidence.VERDICT_OK,
					check.evidence.contains("Low share") ? "Low share - review." : ""));
			}
			usableIdentifiers.add(candidate);
		}

		if (usableIdentifiers.isEmpty())
		{
			return new Proposal(pEntity.name, false,
				"every validated identifier failed the cross-system usability guard: " + MatchPlanRules.join(excludedIdentifiers) + " Use the language-model "
					+ "matching pass (matching/suggest-llm) instead, or fix the underlying data so at least one identifier's values agree across systems, then "
					+ "re-run matching/suggest.",
				null, null, 0, null);
		}

		final ObjectNode requirements = MatchPlanRules.MAPPER.createObjectNode();
		// 2026-09-22, binary-decision-tree-model lot (`docs/MATCHING.md` &sect;16): built as a plain cascade, exactly
		// as before (one EXACT tier per usable identifier, chained), then converted to the flat {root, nodes[]}
		// shape DecisionTreePlan.fromTiers/toJson define - requirements.decisionTree, not requirements.tiers, is
		// what a MatchPlan row now stores; DecisionTreePlan.readFrom's own tiers[] fallback is for pre-lot rows only.
		final ArrayNode tiers = MatchPlanRules.MAPPER.createArrayNode();
		final ArrayNode clusteringFields = requirements.putArray("clusteringFields");
		final StringBuilder rationale = new StringBuilder();

		for (final AttributeInfo exact : usableIdentifiers)
		{
			// Same substitution the guard measurement above already used (2026-09-22 contract) - the field actually
			// compared/clustered is the identifier's own matching key when it has one, never the source directly.
			final String comparedField = exact.matchKey == null ? exact.name : exact.matchKey;
			final ObjectNode tier = tiers.addObject();
			tier.put("outcome", "MATCH");
			tier.put("type", "EXACT");
			final ArrayNode fields = tier.putArray("fields");
			final ObjectNode field = fields.addObject();
			field.put("field", comparedField);
			field.put("algorithm", MatchPlanRules.ALGORITHM_EXACT);
			field.put("minimumScore", 100);
			clusteringFields.add(comparedField);
			if (rationale.length() > 0)
			{
				rationale.append(' ');
			}
			rationale.append(exact.name).append(" carries an accepted ").append(exact.identifierKind)
				.append(" check-digit rule: exact match on it terminates Match.");
			if (exact.matchKey != null)
			{
				rationale.append(" Compared/clustered via its matching key '").append(exact.matchKey).append("' (accent/case-insensitive), never the source field directly.");
			}
		}
		rationale.append(" No fuzzy/blocking tier is proposed by this deterministic pass any more (2026-09-21): a data steward, or the "
			+ "language-model matching pass, supplies field roles beyond a validated identifier.");
		if (!excludedIdentifiers.isEmpty())
		{
			rationale.append(" Excluded by the cross-system usability guard: ").append(MatchPlanRules.join(excludedIdentifiers));
		}
		if (!singleSourceNotes.isEmpty())
		{
			rationale.append(" Single-source usability: ").append(MatchPlanRules.join(singleSourceNotes));
		}
		if (!notVerifiedIdentifiers.isEmpty())
		{
			rationale.append(" Not verified: ").append(MatchPlanRules.join(notVerifiedIdentifiers));
		}
		if (!usableEvidence.isEmpty())
		{
			rationale.append(" Cross-system usability evidence: ").append(MatchPlanRules.join(usableEvidence));
		}

		// 2026-09-22, "child entities matched too": offer the child's own reference attribute (the link to its
		// parent) as an ADDITIONAL clustering candidate - never a tier field, see class Javadoc for why this class
		// never builds one. Only reached once the entity is known to be proposed (usableIdentifiers non-empty,
		// checked above) - a child with no usable identifier is skipped exactly like any other entity, before this
		// point, so there is nothing to attach the reference field to.
		if (childLink != null)
		{
			final AttributeInfo referenceAttribute = MatchPlanRules.attributeNamed(pEntity, childLink.attributeName);
			final String comparedReferenceField = referenceAttribute != null && referenceAttribute.matchKey != null ? referenceAttribute.matchKey
				: childLink.attributeName;
			if (!guardWired)
			{
				rationale.append(" This entity is a child of ").append(childLink.parent).append(" via '").append(childLink.attributeName)
					.append("': its parent reference may be offered as a supporting clustering field once the cross-system usability guard is wired "
						+ "(matching/suggest run against a project with a master zone).");
			}
			else
			{
				final Integer refShared = pSharedCountLookup == null ? null : pSharedCountLookup.sharedCount(pEntity.name, comparedReferenceField);
				final Map<String, Long> refPerSystem = pPerSystemFillLookup == null ? null : pPerSystemFillLookup.filledCountBySystem(pEntity.name, comparedReferenceField);
				final Integer refWithinSystemDuplicate = refPerSystem != null && refPerSystem.size() < 2 && pWithinSystemDuplicateLookup != null
					? pWithinSystemDuplicateLookup.duplicateCount(pEntity.name, comparedReferenceField) : null;
				final CrossSystemFieldGuard.Check refCheck = CrossSystemFieldGuard.evaluate(comparedReferenceField, refShared, refPerSystem, null, null,
					refWithinSystemDuplicate);
				if (refCheck.refuse)
				{
					rationale.append(" Parent reference '").append(comparedReferenceField).append("' (child of ").append(childLink.parent)
						.append(") excluded from clustering: ").append(refCheck.refusalDetail);
					evidenceFields.add(new MatchPlanEvidence.Field(comparedReferenceField, MatchPlanEvidence.ROLE_REJECTED, refShared, null, refPerSystem,
						MatchPlanEvidence.VERDICT_REFUSED, refCheck.refusalDetail));
				}
				else
				{
					clusteringFields.add(comparedReferenceField);
					rationale.append(" Parent reference '").append(comparedReferenceField).append("' (child of ").append(childLink.parent)
						.append(") offered as a supporting clustering field: a shared parent is a plus, never required.");
					final String verdict = refCheck.guardApplicable && refCheck.unverifiedDetail == null ? MatchPlanEvidence.VERDICT_OK : MatchPlanEvidence.VERDICT_NOT_MEASURED;
					final String note = refCheck.evidence != null ? refCheck.evidence
						: refCheck.unverifiedDetail != null ? refCheck.unverifiedDetail : "This entity's master table holds rows from a single source system.";
					evidenceFields.add(new MatchPlanEvidence.Field(comparedReferenceField, MatchPlanEvidence.ROLE_CLUSTERING, refShared, null, refPerSystem, verdict, note));
				}
			}
		}

		requirements.set("decisionTree", DecisionTreePlan.toJson(DecisionTreePlan.fromTiers(tiers)));

		final ObjectNode mergeHints = requirements.putObject("mergePolicy");
		mergeHints.put("mode", "ONLY_DUPLICATES");
		mergeHints.put("recordSelection", "mostCompleted");
		mergeHints.put("defaultFieldMerge", "longest");

		return new Proposal(pEntity.name, true, null, requirements.toString(), rationale.toString(), 85, MatchPlanEvidence.toJson(evidenceFields));
	}

	private static String join(final List<String> pValues)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pValues.size(); i++)
		{
			if (i > 0)
			{
				out.append("; ");
			}
			out.append(pValues.get(i));
		}
		return out.toString();
	}

	/** The (different) entity a child points to through an accepted reference attribute, and which attribute does it - see {@link #childLink}. */
	private static final class ChildLink
	{
		final String parent;
		final String attributeName;

		ChildLink(final String pParent, final String pAttributeName)
		{
			this.parent = pParent;
			this.attributeName = pAttributeName;
		}
	}

	/**
	 * @return the parent entity (and the attribute that names it) a child points to through an accepted reference
	 * attribute, or null when this entity is not a child (renamed from {@code childOf}, 2026-09-22 - no longer used
	 * to skip an entity, only for informational rationale wording and to offer the reference attribute as a
	 * clustering candidate, see class Javadoc and {@link #proposeOne}).
	 */
	private static ChildLink childLink(final EntityInfo pEntity)
	{
		for (final AttributeInfo attribute : pEntity.attributes)
		{
			if (attribute.referenceEntity != null && !attribute.referenceEntity.equals(pEntity.name))
			{
				return new ChildLink(attribute.referenceEntity, attribute.name);
			}
		}
		return null;
	}

	private static AttributeInfo attributeNamed(final EntityInfo pEntity, final String pName)
	{
		for (final AttributeInfo attribute : pEntity.attributes)
		{
			if (attribute.name.equals(pName))
			{
				return attribute;
			}
		}
		return null;
	}
}
