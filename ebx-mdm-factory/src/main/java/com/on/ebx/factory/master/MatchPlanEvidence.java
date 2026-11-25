package com.on.ebx.factory.master;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The structured, field-by-field evidence a matching plan's own cross-system usability check
 * ({@link CrossSystemFieldGuard}) already computes - persisted verbatim as {@code MatchPlan.evidence}
 * (a JSON array, {@code tools/build_xsd.py}) by both {@code ebx.MatchPlanLlmSuggester} (from
 * {@link MatchPlanLlmPass.Session#proposeMatchPlan}) and {@code ebx.MatchPlanSuggester} (from
 * {@link MatchPlanRules}) whenever they write or refresh a plan.
 * <p>
 * Before this class, the SAME numbers existed only as prose folded into {@code MatchPlan.rationale}
 * ("Cross-system usability evidence: country: 9 value(s) shared..."; see {@link
 * CrossSystemFieldGuard.Check#evidence} and the two callers' own rationale-building code) -
 * unparseable by a renderer without re-deriving the guard's own sentence grammar. This class is the
 * STRUCTURED sibling: one {@link Field} per field the plan mentions (in a tier, in {@code
 * clusteringFields}, or considered and excluded), read by {@code review.MatchPlanView} for the review
 * screen's "evidence, field by field" block (`docs/mockups/review-match-plan.html`, block 2) -
 * {@code rationale}'s own prose is kept untouched, additively, for whoever still reads it as free text.
 * <p>
 * Pure: Jackson only, no EBX class. {@code review.MatchPlanView} parses the SAME JSON shape
 * independently (its own Jackson read, no dependency on this class) rather than sharing {@link Field}
 * as a Java type across the master/review package boundary - the review package stays free of every
 * other domain package's classes (see its own classes' Javadocs, none of them import outside {@code
 * java.*}/{@code review.*}), and JSON is the agreed contract, documented here and in
 * {@code docs/MATCHING.md}.
 */
public final class MatchPlanEvidence
{
	/** The field is compared with a non-exact algorithm in some tier (the mockup's "anchor, fuzzy" - the fuzzy tier's own comparison field). The cross-system usability guard never evaluates this algorithm family, so a plain anchor field's own verdict is ordinarily {@link #VERDICT_NOT_MEASURED}. */
	public static final String ROLE_ANCHOR_FUZZY = "ANCHOR_FUZZY";
	/** The field is compared with the exact algorithm in some tier (a MATCH-terminal tier's identifier, or an EXACT-tier supporting field) - the guard evaluates every field with this role. */
	public static final String ROLE_SUPPORTING_EXACT = "SUPPORTING_EXACT";
	/** The field blocks candidate pairs before any comparison runs ({@code clusteringFields}) - combinable with {@link #ROLE_SUPPORTING_EXACT} when the same field does both (comma-joined, e.g. {@code "SUPPORTING_EXACT,CLUSTERING"}). */
	public static final String ROLE_CLUSTERING = "CLUSTERING";
	/** The field was considered and left out of the plan entirely - never appears in a tier or in {@code clusteringFields}. Never combined with another role. */
	public static final String ROLE_REJECTED = "REJECTED";
	/** Survivorship lot (2026-09-22, `docs/MATCHING.md` &sect;15): the field carries an LLM-proposed per-field survivor rule ({@code MatchPlan.requirements.survivorship.fields[]}) - combinable with any of the roles above (e.g. a field used as a fuzzy anchor AND given its own survivor rule). */
	public static final String ROLE_SURVIVORSHIP = "SURVIVORSHIP";

	/** The guard measured this field and accepted it, or the field's role needs no guard (a fuzzy anchor) and nothing contradicted it. */
	public static final String VERDICT_OK = "OK";
	/** The guard would refuse this field (it cannot bridge two systems as proposed) - {@link #ROLE_REJECTED} fields carry this verdict. */
	public static final String VERDICT_REFUSED = "REFUSED";
	/** Nothing reliable could be measured (a SAMPLED/NOT_SUPPORTED/FAILED statistic, a single-source-system table, or a role - fuzzy comparison - the guard does not evaluate at all). Never a refusal. */
	public static final String VERDICT_NOT_MEASURED = "NOT_MEASURED";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MatchPlanEvidence()
	{
	}

	/** One field's evidence row - every numeric member nullable (see {@link #VERDICT_NOT_MEASURED}). */
	public static final class Field
	{
		public final String field;
		/** One of {@link #ROLE_ANCHOR_FUZZY}/{@link #ROLE_SUPPORTING_EXACT}/{@link #ROLE_CLUSTERING}/{@link #ROLE_REJECTED}, or a comma-joined combination of the first three (never combined with {@link #ROLE_REJECTED}). */
		public final String role;
		/** {@link CrossSystemFieldGuard}'s own shared-value count - null when not measured. */
		public final Integer sharedAcrossSystems;
		/** The field's own total distinct-value count, when reliably known - null otherwise. */
		public final Integer distinct;
		/** One entry per source system present in the table, each mapped to how many of that system's rows have the field filled - null when not measured. */
		public final Map<String, Long> filledBySystem;
		/** {@link #VERDICT_OK}/{@link #VERDICT_REFUSED}/{@link #VERDICT_NOT_MEASURED}. */
		public final String verdict;
		/** A short, plain-words reason - the guard's own refusal/unverified detail, or a one-line note for a role the guard does not evaluate (e.g. a fuzzy anchor). Never null (empty when there is nothing more to say). */
		public final String note;
		/**
		 * Survivorship lot (2026-09-22, `docs/MATCHING.md` &sect;15): {@code "high"}/{@code "medium"}/{@code "low"} -
		 * the language model's own confidence in a {@link #ROLE_SURVIVORSHIP} rule, null for every other role (never
		 * guessed). {@code review.MatchSurvivorshipBlock} shows a "to verify" chip on {@code "low"}.
		 */
		public final String confidence;
		/**
		 * Lot 1 (2026-09-23, quality-informed survivorship, `docs/MATCHING.md` &sect;23): one entry per source
		 * system present in the table, each mapped to how many of that system's values were CHECKED against the
		 * field's own accepted validity rule(s) and how many PASSED - {@code ebx.CrossSystemMeasures
		 * #perSystemValidityCounts}'s own output, folded in by {@code master.MatchPlanLlmPass
		 * #mergeSurvivorshipEvidence} exactly like {@link #filledBySystem} is. Null when the field carries no
		 * validity rule, or was never measured (single-source entity, the per-attribute measurement cap, or the
		 * query failed) - never a fabricated 100%.
		 */
		public final Map<String, Validity> validBySystem;

		/** Convenience for every caller built before the survivorship lot (no confidence to give) - equivalent to passing null. */
		public Field(final String pField, final String pRole, final Integer pSharedAcrossSystems, final Integer pDistinct, final Map<String, Long> pFilledBySystem,
			final String pVerdict, final String pNote)
		{
			this(pField, pRole, pSharedAcrossSystems, pDistinct, pFilledBySystem, pVerdict, pNote, null);
		}

		/** Convenience for every caller built before lot 1 (no per-system validity to give) - equivalent to passing null. */
		public Field(final String pField, final String pRole, final Integer pSharedAcrossSystems, final Integer pDistinct, final Map<String, Long> pFilledBySystem,
			final String pVerdict, final String pNote, final String pConfidence)
		{
			this(pField, pRole, pSharedAcrossSystems, pDistinct, pFilledBySystem, pVerdict, pNote, pConfidence, null);
		}

		/** Full form - see {@link #confidence} (survivorship lot, 2026-09-22) and {@link #validBySystem} (lot 1, 2026-09-23). */
		public Field(final String pField, final String pRole, final Integer pSharedAcrossSystems, final Integer pDistinct, final Map<String, Long> pFilledBySystem,
			final String pVerdict, final String pNote, final String pConfidence, final Map<String, Validity> pValidBySystem)
		{
			this.field = pField;
			this.role = pRole;
			this.sharedAcrossSystems = pSharedAcrossSystems;
			this.distinct = pDistinct;
			this.filledBySystem = pFilledBySystem;
			this.verdict = pVerdict;
			this.note = pNote == null ? "" : pNote;
			this.confidence = pConfidence;
			this.validBySystem = pValidBySystem;
		}
	}

	/** One source system's own {@code checked}/{@code valid} totals for a field's own validity rule(s) - the wire shape {@link Field#validBySystem} carries, mirroring {@code quality.ValidityAggregator.SystemValidity} (never imported here: this class stays pure/self-contained, JSON is the contract - see the class Javadoc). Lot 1, 2026-09-23. */
	public static final class Validity
	{
		public final long checked;
		public final long valid;

		public Validity(final long pChecked, final long pValid)
		{
			this.checked = pChecked;
			this.valid = pValid;
		}
	}

	/** {@code [{field,role,sharedAcrossSystems,distinct,filledBySystem,verdict,note}, ...]} - stable field order, never re-sorted (the order the caller measured/considered fields in). */
	public static String toJson(final List<Field> pFields)
	{
		final ArrayNode array = MatchPlanEvidence.MAPPER.createArrayNode();
		for (final Field entry : pFields)
		{
			final ObjectNode node = array.addObject();
			node.put("field", entry.field);
			node.put("role", entry.role);
			if (entry.sharedAcrossSystems == null)
			{
				node.putNull("sharedAcrossSystems");
			}
			else
			{
				node.put("sharedAcrossSystems", entry.sharedAcrossSystems.intValue());
			}
			if (entry.distinct == null)
			{
				node.putNull("distinct");
			}
			else
			{
				node.put("distinct", entry.distinct.intValue());
			}
			final ObjectNode filled = node.putObject("filledBySystem");
			if (entry.filledBySystem != null)
			{
				for (final Map.Entry<String, Long> system : entry.filledBySystem.entrySet())
				{
					filled.put(system.getKey(), system.getValue() == null ? 0L : system.getValue().longValue());
				}
			}
			node.put("verdict", entry.verdict);
			node.put("note", entry.note);
			if (entry.confidence == null)
			{
				node.putNull("confidence");
			}
			else
			{
				node.put("confidence", entry.confidence);
			}
			// lot 1, 2026-09-23: absent (never an empty object) when not measured, distinct from "measured, zero
			// systems" - mirrors filledBySystem's own null-vs-empty-object convention just above, but this member
			// is genuinely optional (most fields carry no validity rule at all), so it is omitted rather than
			// written as {} when null.
			if (entry.validBySystem != null)
			{
				final ObjectNode validNode = node.putObject("validBySystem");
				for (final Map.Entry<String, Validity> system : entry.validBySystem.entrySet())
				{
					final ObjectNode systemNode = validNode.putObject(system.getKey());
					systemNode.put("checked", system.getValue() == null ? 0L : system.getValue().checked);
					systemNode.put("valid", system.getValue() == null ? 0L : system.getValue().valid);
				}
			}
		}
		return array.toString();
	}

	/** {@link #ROLE_SUPPORTING_EXACT} plus {@link #ROLE_CLUSTERING} when {@code pAlsoClustering} - the one combination this codebase ever produces (a field compared as an identifier and used to block on). */
	public static String combineRole(final String pRole, final boolean pAlsoClustering)
	{
		if (!pAlsoClustering || pRole.contains(MatchPlanEvidence.ROLE_CLUSTERING))
		{
			return pRole;
		}
		return pRole + "," + MatchPlanEvidence.ROLE_CLUSTERING;
	}
}
