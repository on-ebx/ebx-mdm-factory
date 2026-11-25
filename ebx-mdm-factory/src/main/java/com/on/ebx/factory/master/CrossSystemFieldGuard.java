package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Structural guard, added 2026-09-21 after `PLAN.md`'s "Rapprochement" entry recorded two REAL matching
 * plans that were accepted by every existing guard although they could never bring one ERP record and one
 * CRM record together: Plan A pre-clustered on {@code legalForm}, whose CRM values (Customer/Prospect/
 * Partner) and ERP values (SA/AG/PLC...) shared not a single value - and, after a reload, CRM's own
 * {@code legalForm} became entirely null (0 of 52 filled); Plan B compared {@code country}/{@code city}
 * with the EXACT algorithm while the two systems spelled the same country/city differently
 * (Fr/France/Suisse vs. FR/CH, Paris/Zurich vs. PARIS/ZURICH). Both plans passed every fill-rate/
 * distinctness/pre-clustering-capability check this codebase already had, because none of them ever asked
 * whether a field's values are actually WRITTEN THE SAME WAY across the systems being matched - the one
 * property that decides whether an exact comparison, or a clustering bucket, can ever bridge two systems at
 * all.
 * <p>
 * <b>Purely structural, on purpose</b> (the product owner's standing rule: suggestions come from the LLM, the Java
 * side only checks structure against measured data - no field-name list, no "country-like" detection). This
 * class knows nothing about field names or domains, only two numbers a caller measures live and hands in:
 * how many DISTINCT values of the field are shared by at least two different source systems ({@code
 * ebx.CrossSystemMeasures#sharedCount} - one shared helper both {@code ebx.MatchPlanLlmSuggester} and {@code
 * ebx.MatchPlanSuggester} call, coordinator review 2026-09-21 second pass - already used for the
 * accepted-tier rationale, see {@link MatchPlanLlmPass.CrossSystemUsageLookup}), and, new this lot, how many rows of EACH source system
 * PRESENT in the table have the field filled at all ({@link MatchPlanLlmPass.PerSystemFillLookup} - the "one
 * more measurement" `PLAN.md` asks for: {@code SELECT "sourceSystem", COUNT("field") ... GROUP BY
 * "sourceSystem"}, run live by the coordinator on the real demo master table, 2026-09-21). Used identically
 * by {@link MatchPlanLlmPass.Session} (the language-model pass - a clustering field, and any field compared
 * with the exact algorithm in any tier) and by {@link MatchPlanRules} (the deterministic pass, for the exact
 * same reason: a rule proposing a validated identifier as both an exact tier and a clustering field is
 * subject to the identical structural fact a model proposing the same field would be).
 * <p>
 * <b>A single source system present in the table makes the whole question moot.</b> Matching is a
 * cross-system concern; with nothing to bridge, this guard does not apply at all - never refuses, never
 * notes a low share - whenever the per-system fill measurement confirms only one source system holds rows
 * in the table ({@link Check#guardApplicable} false; every other member of {@link Check} is then
 * meaningless).
 * <p>
 * <b>A measurement that fails (the query was refused, a transport error, anything) never refuses the field
 * on its own.</b> {@link Check#unverifiedDetail} names what could not be measured; the field is allowed
 * through unrefused - the same "not verified, never refuse" discipline this codebase already applies to
 * {@code preClusteringCapabilityNotVerified} and to a {@code SAMPLED}/{@code NOT_SUPPORTED} statistic
 * elsewhere in {@link MatchPlanLlmPass}. <b>A shared-value count of zero, on its own, without a confirmed
 * source-system count, is treated as unverified too</b> - on a genuinely single-system table, "zero values
 * shared across at least two systems" is trivially true and meaningless, so this class refuses to interpret
 * that number unless the per-system fill measurement has independently confirmed at least two systems are
 * actually present.
 */
public final class CrossSystemFieldGuard
{
	/**
	 * INFERRED (this lot's own choice, not read from a product document): below this share of a field's own
	 * distinct values being genuinely cross-system, a field that otherwise PASSES the guard (at least one
	 * value is shared, every present system has some filled rows) still earns a NOTE, not a refusal -
	 * `PLAN.md` asks explicitly for a note here, "no threshold-based refusal". A quarter of a field's own
	 * distinct values actually crossing a system boundary is a reasonable line between "clearly a working
	 * cross-system field" and "technically passes, but barely" - re-tune if a real run shows it wrong.
	 */
	static final double LOW_SHARE_RATIO_FOR_NOTE = 0.25;

	private CrossSystemFieldGuard()
	{
	}

	/** What {@link #evaluate} decided about one field of one entity. */
	public static final class Check
	{
		/** False when the per-system fill measurement confirms fewer than two source systems hold rows in the table - the whole question is moot, nothing else on this object is meaningful. */
		public final boolean guardApplicable;
		/** True iff {@link #guardApplicable} and the field must be refused - zero shared values (with at least two systems confirmed present), or entirely empty for one of them. */
		public final boolean refuse;
		/** Non-null iff {@link #refuse} - the measured fact plus what to do next, worded for a small model to act on directly (never a real field VALUE - only the field name, source system names, and counts). */
		public final String refusalDetail;
		/** Non-null iff {@link #guardApplicable}, not {@link #refuse}, and something could not be measured - "not verified", never a refusal reason. */
		public final String unverifiedDetail;
		/**
		 * Non-null iff {@link #guardApplicable}, not {@link #refuse}, and everything needed was actually
		 * measured - the full measured picture (distinct-among-shared count, out of the field's own total
		 * distinct count when known, plus the per-system fill breakdown), e.g. {@code "city: 3 value(s)
		 * shared across systems out of 47 distinct; ERP 55 filled, CRM 52 filled."} - coordinator review,
		 * 2026-09-21, second pass: a measurement the guard actually made must reach the plan's own stored
		 * rationale whenever the guard accepts the field, not only when the share happens to be low, so a
		 * reviewer always sees what was checked. Ends with " Low share - review." when the share is below
		 * {@link #LOW_SHARE_RATIO_FOR_NOTE} (only ever an emphasis, never a refusal reason) and the field's
		 * own distinct count is known.
		 * <p>
		 * <b>2026-09-22, one exception: this can ALSO be non-null when {@link #guardApplicable} is false</b> -
		 * a genuinely single-source-system table, when the caller supplied a within-system duplicate count (see
		 * {@link #evaluate(String, Integer, Map, Integer, String, Integer)}'s own {@code pWithinSystemDuplicateCount}
		 * parameter): "no cross-system question to ask" is then worded as an actual within-system finding
		 * ("N value(s) repeated within this source, out of M distinct") instead of a bare "does not apply"
		 * sentence with nothing to show for it - single-source matching is exactly as legitimate as cross-system
		 * matching (CLAUDE.md &sect;8), so its own rationale/evidence text must never read like a lesser, emptier
		 * case. Null (the original behaviour, unchanged) when that parameter was not given - the 4-/5-arg
		 * overloads below always pass null, so every existing caller keeps reading {@code evidence == null} on a
		 * single-source table exactly as before.
		 */
		public final String evidence;
		/**
		 * 2026-10-12, per-path rule (`docs/MATCHING.md` &sect;4.2.3): the source systems for which the field is entirely empty when {@link #refuse}
		 * is true BECAUSE of that (never for the "no value is shared" refusal); an empty, unmodifiable list in every other case. A caller that can see
		 * the whole decision tree ({@code MatchPlanLlmPass.Session}, for the exact-algorithm fields of the tree) uses it to defer this refusal to
		 * {@link CrossSystemPathRule}: the field is refused only when every path to MATCH/SUSPECTED needs it. A caller that cannot (the clustering
		 * fields, the deterministic {@code MatchPlanRules}) keeps reading {@link #refuse} and is unchanged.
		 */
		public final List<String> emptySystems;

		private Check(final boolean pGuardApplicable, final boolean pRefuse, final String pRefusalDetail, final String pUnverifiedDetail, final String pEvidence,
			final List<String> pEmptySystems)
		{
			this.guardApplicable = pGuardApplicable;
			this.refuse = pRefuse;
			this.refusalDetail = pRefusalDetail;
			this.unverifiedDetail = pUnverifiedDetail;
			this.evidence = pEvidence;
			this.emptySystems = pEmptySystems == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<String>(pEmptySystems));
		}

		/** True iff {@link #refuse} and the refusal is the "entirely empty for a source system" one (see {@link #emptySystems}). */
		public boolean refusedOnEmptySystem()
		{
			return this.refuse && !this.emptySystems.isEmpty();
		}

		private static Check notApplicable(final String pEvidence)
		{
			return new Check(false, false, null, null, pEvidence, null);
		}

		private static Check refused(final String pDetail)
		{
			return new Check(true, true, pDetail, null, null, null);
		}

		private static Check refusedEmpty(final String pDetail, final List<String> pEmptySystems)
		{
			return new Check(true, true, pDetail, null, null, pEmptySystems);
		}

		private static Check unverified(final String pDetail)
		{
			return new Check(true, false, null, pDetail, null, null);
		}

		private static Check usable(final String pEvidence)
		{
			return new Check(true, false, null, null, pEvidence, null);
		}
	}

	/** Convenience for every caller built before the 2026-09-22 matching-key contract (no key name to give) - equivalent to passing a null {@code pMatchKeyName} and a null {@code pWithinSystemDuplicateCount}. */
	public static Check evaluate(final String pFieldName, final Integer pSharedCount, final Map<String, Long> pFilledCountBySystem, final Integer pDistinctCount)
	{
		return CrossSystemFieldGuard.evaluate(pFieldName, pSharedCount, pFilledCountBySystem, pDistinctCount, null, null);
	}

	/** Convenience for every caller built before the 2026-09-22 "child entities matched too" lot (no within-system duplicate count to give) - equivalent to passing a null {@code pWithinSystemDuplicateCount}. */
	public static Check evaluate(final String pFieldName, final Integer pSharedCount, final Map<String, Long> pFilledCountBySystem, final Integer pDistinctCount,
		final String pMatchKeyName)
	{
		return CrossSystemFieldGuard.evaluate(pFieldName, pSharedCount, pFilledCountBySystem, pDistinctCount, pMatchKeyName, null);
	}

	/**
	 * @param pFieldName the field being judged - used only to word {@link Check#refusalDetail}/{@link Check#evidence}/{@link Check#unverifiedDetail}, never to branch on (this class is purely structural).
	 * @param pSharedCount {@code ebx.CrossSystemMeasures#sharedCount}'s own answer - how many DISTINCT values of the field are shared by at least two source systems; null when not measured.
	 * @param pFilledCountBySystem one entry per source system PRESENT in the table (see {@link MatchPlanLlmPass.PerSystemFillLookup}), each mapped to how many of that system's rows have the field filled; null when not measured. A map with fewer than two entries means the table itself holds a single source system - see class Javadoc.
	 * @param pDistinctCount the field's own total distinct-value count, when reliably known (used only to word the evidence's "out of N distinct" phrase, and the low-share emphasis) - may be null.
	 * @param pMatchKeyName 2026-09-22 matching-key contract: the name of a derived matching key {@code pFieldName}
	 *            itself has, when its caller was unable to substitute the key in automatically before this call was
	 *            made ({@code master.MatchPlanLlmPass.Session}'s own field/clustering loops substitute a source
	 *            attribute's key BEFORE calling this method whenever the key attribute is resolvable - see that
	 *            class' own "Matching-key handling" comments - so in the ordinary case this method is already
	 *            evaluating the key itself, and this parameter is null: a key has no further key of its own). Null
	 *            in every other case. Used only to word a REFUSAL ("use '&lt;key&gt;' instead" - never to branch
	 *            on): a purely structural, caller-supplied field NAME, exactly like {@code pFieldName} itself -
	 *            never a real record value (see {@code evaluateHasNoValueCarryingParameter} in the test suite).
	 * @param pWithinSystemDuplicateCount 2026-09-22, "child entities matched too" lot (`docs/MATCHING.md`): how many
	 *            DISTINCT values of the field are repeated by two or more records of the SAME source system
	 *            ({@code ebx.CrossSystemMeasures#withinSystemDuplicateCount} - the within-system mirror of
	 *            {@code pSharedCount}'s own cross-system measurement). Used ONLY to word {@link Check#evidence} on
	 *            an otherwise-{@code notApplicable} (single-source-system) table - single-source matching is not a
	 *            lesser case (CLAUDE.md &sect;8), so its own evidence should show what WAS measured (within-system
	 *            duplicates) instead of a bare "does not apply". Null when not measured, or when the caller never
	 *            wired this measurement at all (every 4-/5-arg overload above) - never changes {@link
	 *            Check#guardApplicable}/{@link Check#refuse} either way, only the wording of a non-applicable
	 *            check's own evidence.
	 */
	public static Check evaluate(final String pFieldName, final Integer pSharedCount, final Map<String, Long> pFilledCountBySystem, final Integer pDistinctCount,
		final String pMatchKeyName, final Integer pWithinSystemDuplicateCount)
	{
		if (pFilledCountBySystem != null && pFilledCountBySystem.size() < 2)
		{
			return Check.notApplicable(
				CrossSystemFieldGuard.singleSourceEvidence(pFieldName, pFilledCountBySystem, pDistinctCount, pWithinSystemDuplicateCount));
		}
		// non-null AND size >= 2 from here on whenever the per-system measurement succeeded at all
		final boolean systemsConfirmedMulti = pFilledCountBySystem != null;
		if (systemsConfirmedMulti)
		{
			final List<String> emptySystems = new ArrayList<String>();
			for (final Map.Entry<String, Long> entry : pFilledCountBySystem.entrySet())
			{
				if (entry.getValue() == null || entry.getValue().longValue() == 0L)
				{
					emptySystems.add(entry.getKey());
				}
			}
			if (!emptySystems.isEmpty())
			{
				return Check.refusedEmpty("'" + pFieldName + "' cannot be used as a clustering field or with the exact algorithm: it is entirely empty for source system"
					+ (emptySystems.size() > 1 ? "s " : " ") + CrossSystemFieldGuard.join(emptySystems) + " (" + CrossSystemFieldGuard.describeFillCounts(pFilledCountBySystem)
					+ "), so it can never bridge " + (emptySystems.size() > 1 ? "those systems" : emptySystems.get(0))
					+ " with any other source system present here. Remove it, or choose a field that is actually filled in every source system present here; if it "
					+ "needs backfilling first, leave it out here and say so in the rationale." + CrossSystemFieldGuard.keyHint(pMatchKeyName), emptySystems);
			}
			if (pSharedCount != null && pSharedCount.intValue() == 0)
			{
				return Check.refused("'" + pFieldName + "' cannot be used as a clustering field or with the exact algorithm: no value is shared between any two of the "
					+ "source systems present here (" + CrossSystemFieldGuard.describeFillCounts(pFilledCountBySystem) + "), even though it is filled in every one of "
					+ "them. Remove it, or choose a field whose values are written the same way in every source system; if this field needs normalising first (its "
					+ "systems write the same real-world thing differently), leave it out here and say so in the rationale." + CrossSystemFieldGuard.keyHint(pMatchKeyName));
			}
		}

		final List<String> missing = new ArrayList<String>();
		if (!systemsConfirmedMulti)
		{
			// also covers the ambiguous case sharedCount==0 && !systemsConfirmedMulti: on a genuinely single-system
			// table "zero shared across >= 2 systems" is trivially true and meaningless, so it is never trusted on
			// its own - see class Javadoc
			missing.add("per-system fill counts");
		}
		if (pSharedCount == null)
		{
			missing.add("the cross-system shared-value count");
		}
		if (!missing.isEmpty())
		{
			return Check.unverified("cross-system usage of '" + pFieldName + "' could not be fully measured (" + CrossSystemFieldGuard.join(missing)
				+ " unavailable) - allowed through, not refused; review by hand.");
		}

		// Every number needed is present at this point (missing.isEmpty() just above required both) - the measurement
		// this call just made always reaches the plan's own rationale when the field is accepted, not only when the
		// share happens to be low (coordinator review, 2026-09-21, second pass: "a measurement made for the guard
		// must also reach the plan's stored rationale/evidence when accepted, so a reviewer always sees what was
		// checked").
		final StringBuilder evidence = new StringBuilder();
		evidence.append(pFieldName).append(": ").append(pSharedCount).append(" value(s) shared across systems");
		boolean lowShare = false;
		if (pDistinctCount != null && pDistinctCount.intValue() > 0)
		{
			evidence.append(" out of ").append(pDistinctCount).append(" distinct");
			lowShare = pSharedCount.doubleValue() / pDistinctCount.doubleValue() < CrossSystemFieldGuard.LOW_SHARE_RATIO_FOR_NOTE;
		}
		evidence.append("; ").append(CrossSystemFieldGuard.describeFillCounts(pFilledCountBySystem)).append('.');
		if (lowShare)
		{
			// an emphasis only - `PLAN.md`'s own "no threshold-based refusal" still holds: this never changes
			// `refuse`, only draws a reviewer's eye to a field worth a second look
			evidence.append(" Low share - review.");
		}
		return Check.usable(evidence.toString());
	}

	/**
	 * 2026-10-12, per-path rule: the same judgement as {@link #evaluate(String, Integer, Map, Integer, String, Integer)}, made on the source systems that
	 * HAVE some value only - for a field a caller has already decided to accept despite {@link Check#emptySystems} (another path of the tree covers
	 * those systems, {@link CrossSystemPathRule}). What it can still say is the other half of the guard: that the systems which do fill the field share
	 * no value at all (refused, same wording as ever), or the measured evidence of the usable ones. Null when fewer than two systems remain after
	 * removing the empty ones: nothing is left to bridge by this field, the per-path warning is then the whole story.
	 */
	public static Check evaluateFilledSystems(final String pFieldName, final Integer pSharedCount, final Map<String, Long> pFilledCountBySystem,
		final Integer pDistinctCount, final String pMatchKeyName)
	{
		if (pFilledCountBySystem == null)
		{
			return null;
		}
		final Map<String, Long> filledOnly = new java.util.LinkedHashMap<String, Long>();
		for (final Map.Entry<String, Long> entry : pFilledCountBySystem.entrySet())
		{
			if (entry.getValue() != null && entry.getValue().longValue() > 0L)
			{
				filledOnly.put(entry.getKey(), entry.getValue());
			}
		}
		if (filledOnly.size() < 2)
		{
			return null;
		}
		return CrossSystemFieldGuard.evaluate(pFieldName, pSharedCount, filledOnly, pDistinctCount, pMatchKeyName, null);
	}

	/**
	 * 2026-09-22, "child entities matched too" lot: the evidence text for an otherwise-{@code notApplicable}
	 * (single-source-system) field - null (the original, unchanged behaviour) when {@code pWithinSystemDuplicateCount}
	 * was never measured/wired, so a caller that has not adopted the new lookup keeps reading {@code evidence == null}
	 * exactly as before. When it WAS measured, this reads as an actual finding, not an apology for the cross-system
	 * question being moot: e.g. {@code "email: single source system (CRM): the cross-system usability guard does not "
	 * + "apply; 0 value(s) repeated within this source out of 89 distinct."}
	 */
	private static String singleSourceEvidence(final String pFieldName, final Map<String, Long> pFilledCountBySystem, final Integer pDistinctCount,
		final Integer pWithinSystemDuplicateCount)
	{
		if (pWithinSystemDuplicateCount == null)
		{
			return null;
		}
		final StringBuilder out = new StringBuilder();
		out.append(pFieldName).append(": single source system");
		if (pFilledCountBySystem != null && pFilledCountBySystem.size() == 1)
		{
			out.append(" (").append(pFilledCountBySystem.keySet().iterator().next()).append(')');
		}
		out.append(": the cross-system usability guard does not apply; ").append(pWithinSystemDuplicateCount)
			.append(" value(s) repeated within this source");
		if (pDistinctCount != null && pDistinctCount.intValue() > 0)
		{
			out.append(" out of ").append(pDistinctCount).append(" distinct");
		}
		out.append('.');
		return out.toString();
	}

	/** 2026-09-22 matching-key contract: an actionable pointer to a field's own key, appended to a refusal message only when {@code pMatchKeyName} is non-null - never changes whether the field is refused, only how the refusal is worded. */
	private static String keyHint(final String pMatchKeyName)
	{
		return pMatchKeyName == null ? "" : " A matching key ('" + pMatchKeyName + "') already exists for it: use that instead.";
	}

	private static String describeFillCounts(final Map<String, Long> pFilledCountBySystem)
	{
		final StringBuilder out = new StringBuilder();
		for (final Map.Entry<String, Long> entry : pFilledCountBySystem.entrySet())
		{
			if (out.length() > 0)
			{
				out.append(", ");
			}
			out.append(entry.getKey()).append(' ').append(entry.getValue()).append(" filled");
		}
		return out.toString();
	}

	private static String join(final List<String> pValues)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pValues.size(); i++)
		{
			if (i > 0)
			{
				out.append(i == pValues.size() - 1 ? " and " : ", ");
			}
			out.append(pValues.get(i));
		}
		return out.toString();
	}
}
