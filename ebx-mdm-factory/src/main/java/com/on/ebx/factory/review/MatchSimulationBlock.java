package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Block 3 of the Matching review line's expanded body ("what the plan would give on your data"),
 * review-match-plan-simulation lot, 2026-09-22 - REPLACES the earlier "10 shared-key pairs" version entirely
 * (product-owner rejection, task brief: every one of those pairs shared the clustering key by construction, so
 * every verdict was Suspect and the per-field cells compared raw text the engine never actually compares). The
 * validated mockup is {@code docs/mockups/review-match-plan-simulation.html} - read it before touching this
 * file.
 * <p>
 * <b>Pure, package-private, unit-tested without EBX</b> - same discipline as {@link MatchPlanTreeDiagram}: only
 * {@code java.*}/Jackson/{@code review.*}. {@link MatchPlanView} still owns the button/lead-line/disabled-reason
 * chrome around this block (`renderSimulationBlock`); this class owns everything below it once a simulation has
 * actually run - the numeric summary, the three families of instructive pairs, and the per-field cells.
 * <p>
 * <b>What is REAL vs. what is DERIVED, read this before trusting a number</b>:
 * <ul>
 * <li>The verdict of every {@link MatchPlanView.SimulationPair} ({@code MATCHED}/{@code SUSPECTED}/
 * {@code NOT_MATCHED}) is the add-on's own answer ({@code MatchingOperations.simulateMatch}, via
 * {@code ebx.MameMatchingApi#simulate}) - never derived here.</li>
 * <li>Whether two records agree on an EXACT-algorithm field ({@link #exactFieldNames}) is a plain,
 * case-insensitive, trimmed comparison of the two real values the pivot/candidate records actually hold
 * (read directly off the {@code Adaptation}s the add-on itself just compared - {@code
 * ebx.MameMatchingApi.SimulationMatch#pivotRecord}/{@code targetRecord}) - real, not estimated.</li>
 * <li>A fuzzy/AVERAGE-tier field's own similarity score, when present in {@link
 * MatchPlanView.SimulationPair#fieldScores}, comes from the add-on's OWN decision-tree evaluation
 * ({@code MatchingOperations.executeDecisionTree}, via {@code ebx.MameMatchingApi#evaluatePair}) - real,
 * never a factory-side guess. It is ABSENT (map has no entry for that field) whenever that call was not
 * attempted (a bounded cap, {@code ebx.MatchPlanSimulationRunner#MAX_DETAIL_PAIRS}), failed for this pair, or -
 * per the add-on's own algorithm ({@link #fullTextFieldNames}, a HYPOTHESIS) - not something the add-on scores
 * per field at all. Diagnostic-visibility lot (2026-09-22, task brief item 1): rendered as a short "n/a" with
 * the reason in the cell's own {@code title} attribute, never a fabricated number - the aggregated, ACTUAL
 * failure detail (an exception, or a field the engine could have scored but did not) surfaces once per click in
 * a muted line above the table ({@code review.match.simulate.scoreDiagnostics}, {@link
 * MatchPlanView#renderSimulationBlock}), not repeated per row.</li>
 * <li>{@link #weightedAverageScore} (an AVERAGE tier's own combined score, from the real per-field scores
 * above and the plan's own declared weights) and everything built on it - {@link #isBorderline}, the "tier N
 * accepted"/"tier N refused" wording an AVERAGE tier contributes to {@link #leafWords} - is this class' OWN
 * re-combination, mirroring {@code master.MatchingPolicyTranslator}'s own "Average" node (weighted mean,
 * compared to the tier's {@code minimumScore}). <b>This formula was read from the add-on's method
 * signatures only (`javap`) - the exact combination/null-handling semantics of the real "Average" decision
 * function were never confirmed against a live run.</b> Treat {@link #isBorderline} and any AVERAGE-tier leaf
 * wording as an informed approximation, not an authoritative restatement of the engine's own arithmetic - the
 * verdict and the per-field scores it is built from remain real either way.</li>
 * <li>{@link #isBorderline}'s margin ({@link #BORDERLINE_MARGIN_UNITS}) is expressed in the SAME raw units as
 * a tier's own {@code minimumScore} (always an integer 0-100 in every plan {@code MatchingPolicyTranslator}
 * builds) - see {@code ebx.MameMatchingApi.FieldScore}'s own Javadoc for why this class never converts a
 * real score by any assumed 0-1/0-100 factor.</li>
 * </ul>
 * <p>
 * <b>2026-09-22, review-match-plan-simulation-probes lot - the "never compared" gap above is now closed.</b>
 * The random-pivot draw alone proved empty in practice (the product owner's own live run: 20 pivots, 14 candidates, all
 * SUSPECTED - every family empty, "an empty panel proves nothing"), because {@code
 * MatchingOperations.simulateMatch} only ever returns pairs the engine's own pre-clustering already retains.
 * {@code ebx.MatchPlanSimulationRunner} now ALSO probes, via live SQL, for exactly the two kinds of pair the
 * engine's own clustering would never volunteer - a shared-key pair from two DIFFERENT clustering groups
 * (probe set A, feeds family 2) and a same-exact-fields/different-key pair (probe set B, feeds family 1) -
 * and judges each with {@link MameMatchingApi#evaluatePair} instead of {@code simulateMatch} (the only add-on
 * call able to score a pair the engine never grouped together). Every probed pair still carries a real,
 * add-on-computed verdict; a probe pair {@code evaluatePair} could not judge is dropped, never shown with a
 * fabricated one (see {@code ebx.MatchPlanSimulationRunner}'s own class Javadoc, "what changed" and "never
 * an empty panel"). Each pair now also carries {@link MatchPlanView.SimulationPair#origin} (engine candidate
 * vs. one of the two probe kinds), shown as its own "Source" column, and family 1/2's own key test now prefers
 * {@link MatchPlanView.SimulationPair#sameKey} (the plan's real IDENTIFYING key, e.g. {@code nameKey}) over
 * the older {@link #exactFieldsEqual} test (the plan's exact-TIER fields, a related but different question) -
 * see {@link #analyze}'s own inline comment. A brand-new family 0 ("typical pairs the engine flagged") renders
 * ONLY when families 1-3 are all empty, so the panel is never just three "no pair of this kind" lines again.
 */
final class MatchSimulationBlock
{
	/** Task brief: "at most 2-3 rows each". */
	static final int MAX_FAMILY_ROWS = 3;
	/** ±0.03 on the mockup's own 0-1 display scale, expressed in the SAME raw units as a tier's {@code minimumScore} (0-100, see class Javadoc). */
	static final double BORDERLINE_MARGIN_UNITS = 3.0;

	private MatchSimulationBlock()
	{
	}

	// ===================================================================================================== render

	/**
	 * Lot M16 (docs/MATCHING.md sec 68): the candidate coverage the simulation measured, shown before the pairs: for each exact key of the plan, how many records that share its value with a
	 * pivot were never among the candidates the add-on evaluated - the facts the model is also given when it is asked for another proposal. Nothing is rendered when it was not measured.
	 */
	static void renderCoverage(final StringBuilder pOut, final MatchPlanView.SimulationResult pSimulation, final Locale pLocale)
	{
		if (pSimulation.coverage == null || pSimulation.coverage.keys.isEmpty())
		{
			return;
		}
		pOut.append("<div class=\"mdmf-sim-coverage\"><h5>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.coverage.title"))).append("</h5>");
		pOut.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.coverage.lead"))).append("</p><ul>");
		for (final com.on.ebx.factory.matching.CandidateCoverage.KeyCoverage key : pSimulation.coverage.keys)
		{
			if (key.sharing == 0)
			{
				continue;
			}
			final String line = key.missing == 0
				? ReviewMessages.get(pLocale, "review.match.simulate.coverage.ok", key.key, Integer.valueOf(key.sharing))
				: ReviewMessages.get(pLocale, "review.match.simulate.coverage.gap", key.key, Integer.valueOf(key.missing), Integer.valueOf(key.sharing), MatchSimulationBlock.systemCounts(key.missingBySystem));
			pOut.append("<li class=\"").append(key.missing == 0 ? "mdmf-sim-ok" : "mdmf-sim-gap").append("\">").append(ReviewPage.esc(line)).append("</li>");
		}
		pOut.append("</ul>");
		if (pSimulation.coverage.hasGap())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.coverage.next"))).append("</p>");
		}
		pOut.append("</div>");
	}

	/** {@code STORES 3, ECOM 1}: how many of the missing records belong to each source system. */
	private static String systemCounts(final java.util.Map<String, Integer> pCounts)
	{
		final StringBuilder out = new StringBuilder();
		for (final java.util.Map.Entry<String, Integer> entry : pCounts.entrySet())
		{
			out.append(out.length() == 0 ? "" : ", ").append(entry.getKey()).append(' ').append(entry.getValue());
		}
		return out.toString();
	}

	static void render(final StringBuilder pOut, final JsonNode pRequirements, final MatchPlanView.SimulationResult pSimulation, final Locale pLocale)
	{
		MatchSimulationBlock.renderCoverage(pOut, pSimulation, pLocale);
		if (pSimulation.pairs.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.none"))).append("</p>");
			return;
		}
		final Set<String> exactFields = MatchSimulationBlock.exactFieldNames(pRequirements);
		final Set<String> fullTextFields = MatchSimulationBlock.fullTextFieldNames(pRequirements);
		final JsonNode averageTier = MatchSimulationBlock.firstAverageTier(pRequirements);
		final List<String> comparedFields = MatchPlanView.comparedFieldNames(pRequirements);
		final Analysis analysis = MatchSimulationBlock.analyze(pRequirements, exactFields, averageTier, pSimulation.pairs, pSimulation.pivotCount);

		pOut.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.summaryLead",
			Integer.valueOf(pSimulation.pivotCount), Integer.valueOf(analysis.examined)))).append("</p>");

		// diagnostic-visibility lot (2026-09-22, task brief item 3): "only exact notion is the derived key" - no
		// OTHER exact-tier field is configured, so family 2's "same key" IS the plan's own identifying key
		// (ebx.MatchPlanSimulationRunner#keyFieldName) - name it explicitly in the card instead of the generic
		// "same exact key" wording, which would otherwise be misleading (there is no separate exact tier at all).
		final boolean namedKeyOnly = exactFields.isEmpty() && pSimulation.keyFieldName != null;
		MatchSimulationBlock.renderCards(pOut, analysis, pSimulation.nearMissApplicable, namedKeyOnly, pSimulation.keyFieldName, pLocale);
		// family 0, "never an empty panel" (task brief, 2026-09-22): rendered ONLY when families 1-3 are all
		// empty (Analysis.analyze only ever populates it in that case) - hidden the rest of the time to keep
		// the panel short, per the same brief.
		if (!analysis.family0.isEmpty())
		{
			MatchSimulationBlock.renderFamily(pOut, pRequirements, exactFields, fullTextFields, averageTier, comparedFields, "0", "review.match.simulate.family0.title",
				"review.match.simulate.family0.lead", null, null, analysis.family0, 0, pLocale);
		}
		// diagnostic-visibility lot (2026-09-22, task brief item 3): the near-miss probe cannot even be BUILT
		// when the plan has no exact (non-full-text) field besides the identifying key
		// (ebx.MatchPlanSimulationRunner#probeNearMissPairs bails out before ever querying) - family 1's empty
		// line must then say "not applicable", never claim "0 near-miss pairs were probed and none matched",
		// which would misreport a probe that never ran as a probe that found nothing.
		final String family1EmptyKey = pSimulation.nearMissApplicable ? "review.match.simulate.family1.empty" : "review.match.simulate.family1.notApplicable";
		final Object[] family1EmptyArgs = pSimulation.nearMissApplicable
			? new Object[] { Integer.valueOf(analysis.probeNearMissExamined), Integer.valueOf(analysis.examined) }
			: new Object[0];
		MatchSimulationBlock.renderFamily(pOut, pRequirements, exactFields, fullTextFields, averageTier, comparedFields, "1", "review.match.simulate.family1.title",
			"review.match.simulate.family1.lead", family1EmptyKey, family1EmptyArgs, analysis.family1, analysis.suspectCaughtByFuzzy, pLocale);
		MatchSimulationBlock.renderFamily(pOut, pRequirements, exactFields, fullTextFields, averageTier, comparedFields, "2", "review.match.simulate.family2.title",
			"review.match.simulate.family2.lead", "review.match.simulate.family2.empty",
			new Object[] { Integer.valueOf(analysis.probeSharedKeyExamined), Integer.valueOf(analysis.examined) }, analysis.family2, analysis.notMatchSameKey,
			pLocale);
		MatchSimulationBlock.renderFamily(pOut, pRequirements, exactFields, fullTextFields, averageTier, comparedFields, "3", "review.match.simulate.family3.title",
			"review.match.simulate.family3.lead", "review.match.simulate.family3.empty",
			new Object[] { Double.valueOf(MatchSimulationBlock.BORDERLINE_MARGIN_UNITS), Integer.valueOf(analysis.examined) }, analysis.family3,
			analysis.borderlineCount, pLocale);
		pOut.append("<div class=\"mdmf-hint mdmf-sim-note\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.note"))).append("</div>");
	}

	private static void renderCards(final StringBuilder pOut, final Analysis pAnalysis, final boolean pNearMissApplicable, final boolean pNamedKeyOnly,
		final String pKeyFieldName, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-sim-cards\">");
		MatchSimulationBlock.renderCard(pOut, "", String.valueOf(pAnalysis.examined), ReviewMessages.get(pLocale, "review.match.simulate.card.examined"),
			pAnalysis.pivotCount > 0 ? ReviewMessages.get(pLocale, "review.match.simulate.card.examined.detail", Integer.valueOf(pAnalysis.pivotCount)) : "");
		// review-match-plan-simulation-probes lot (2026-09-22): "N pairs probed by the factory", next to
		// "candidates examined" (task brief) - the sub-counts of every OTHER card already read off the
		// enlarged (engine + probes) set, since Analysis.analyze runs over the merged pair list unconditionally.
		// diagnostic-visibility lot: when the near-miss probe could not even be built (task brief item 3), say so
		// here rather than a bare "0 near miss" indistinguishable from "probed and found none".
		final String probedDetail = !pNearMissApplicable
			? ReviewMessages.get(pLocale, "review.match.simulate.card.probed.detail.nearMissNotApplicable", Integer.valueOf(pAnalysis.probeSharedKeyExamined))
			: pAnalysis.probedCount > 0 ? ReviewMessages.get(pLocale, "review.match.simulate.card.probed.detail",
				Integer.valueOf(pAnalysis.probeSharedKeyExamined), Integer.valueOf(pAnalysis.probeNearMissExamined)) : "";
		MatchSimulationBlock.renderCard(pOut, "", String.valueOf(pAnalysis.probedCount), ReviewMessages.get(pLocale, "review.match.simulate.card.probed"), probedDetail);
		MatchSimulationBlock.renderCard(pOut, "good", String.valueOf(pAnalysis.matchCount), ReviewMessages.get(pLocale, "review.match.simulate.card.match"), "");
		MatchSimulationBlock.renderCard(pOut, "warn", String.valueOf(pAnalysis.suspectCount), ReviewMessages.get(pLocale, "review.match.simulate.card.suspect"),
			ReviewMessages.get(pLocale, "review.match.simulate.card.suspect.detail", Integer.valueOf(pAnalysis.suspectCaughtByFuzzy)));
		// diagnostic-visibility lot (task brief item 3): name the identifying key explicitly ("same nameKey")
		// when it is the plan's ONLY exact notion, instead of the generic "same exact key" wording.
		final String notMatchDetail = pNamedKeyOnly
			? ReviewMessages.get(pLocale, "review.match.simulate.card.notMatch.detail.namedKey", Integer.valueOf(pAnalysis.notMatchSameKey), pKeyFieldName)
			: ReviewMessages.get(pLocale, "review.match.simulate.card.notMatch.detail", Integer.valueOf(pAnalysis.notMatchSameKey));
		MatchSimulationBlock.renderCard(pOut, "bad", String.valueOf(pAnalysis.notMatchCount), ReviewMessages.get(pLocale, "review.match.simulate.card.notMatch"),
			notMatchDetail);
		MatchSimulationBlock.renderCard(pOut, "", String.valueOf(pAnalysis.borderlineCount), ReviewMessages.get(pLocale, "review.match.simulate.card.borderline"),
			ReviewMessages.get(pLocale, "review.match.simulate.card.borderline.detail"));
		pOut.append("</div>");
	}

	private static void renderCard(final StringBuilder pOut, final String pTone, final String pNumber, final String pLabel, final String pDetail)
	{
		pOut.append("<div class=\"mdmf-sim-card").append(pTone.isEmpty() ? "" : " mdmf-sim-card-" + pTone).append("\"><div class=\"mdmf-sim-card-n\">")
			.append(ReviewPage.esc(pNumber)).append("</div><div class=\"mdmf-sim-card-l\">").append(ReviewPage.esc(pLabel)).append("</div>");
		if (!pDetail.isEmpty())
		{
			pOut.append("<div class=\"mdmf-sim-card-d\">").append(ReviewPage.esc(pDetail)).append("</div>");
		}
		pOut.append("</div>");
	}

	/**
	 * @param pEmptyKey the bundle key for the empty-family line, formatted with {@code pEmptyArgs} - task
	 *            brief, 2026-09-22: "an empty family must say why, with numbers", never the bare "no pair of
	 *            this kind" of the pre-probes lot. Null (with {@code pEmptyArgs} then ignored) only for family
	 *            0, which {@link #render} calls exclusively when {@code pRows} is already non-empty.
	 */
	private static void renderFamily(final StringBuilder pOut, final JsonNode pRequirements, final Set<String> pExactFields, final Set<String> pFullTextFields,
		final JsonNode pAverageTier, final List<String> pComparedFields, final String pNumber, final String pTitleKey, final String pLeadKey, final String pEmptyKey,
		final Object[] pEmptyArgs, final List<MatchPlanView.SimulationPair> pRows, final int pTotalCount, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-sim-family\"><h5><span class=\"mdmf-sim-family-num\">").append(ReviewPage.esc(pNumber)).append("</span> ")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, pTitleKey)));
		if (pTotalCount > 0)
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-accent\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.family.count",
				Integer.valueOf(pTotalCount)))).append("</span>");
		}
		pOut.append("</h5><p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, pLeadKey))).append("</p>");
		if (pRows.isEmpty())
		{
			// never invent a row - task brief: "an empty family must render an explicit line - never invent" -
			// and now must say why, with real numbers (pEmptyArgs), never the bare pre-probes-lot wording
			pOut.append("<p class=\"mdmf-hint mdmf-sim-family-empty\">")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, pEmptyKey, pEmptyArgs == null ? new Object[0] : pEmptyArgs))).append("</p></div>");
			return;
		}
		pOut.append("<table class=\"mdmf-table\"><tr><th>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.col.pivot"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.col.target"))).append("</th>");
		for (final String field : pComparedFields)
		{
			pOut.append("<th class=\"k\">").append(ReviewPage.esc(field)).append("</th>");
		}
		pOut.append("<th>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.col.verdict"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.col.leaf"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.col.origin"))).append("</th></tr>");
		for (final MatchPlanView.SimulationPair pair : pRows)
		{
			pOut.append("<tr><td>").append(ReviewPage.esc(pair.pivotLabel)).append(" <span class=\"id\">").append(ReviewPage.esc(pair.pivotSystem))
				.append("</span></td><td>").append(ReviewPage.esc(pair.targetLabel)).append(" <span class=\"id\">").append(ReviewPage.esc(pair.targetSystem))
				.append("</span></td>");
			for (final String field : pComparedFields)
			{
				MatchSimulationBlock.renderFieldCell(pOut, pRequirements, pExactFields, pFullTextFields, pAverageTier, field, pair, pLocale);
			}
			pOut.append("<td>");
			MatchSimulationBlock.renderVerdictChip(pOut, pair.verdict, pLocale);
			pOut.append("</td><td class=\"mdmf-hint\">").append(ReviewPage.esc(MatchSimulationBlock.leafWords(pRequirements, pair, pLocale)))
				.append("</td><td class=\"mdmf-hint\">").append(ReviewPage.esc(MatchSimulationBlock.originWords(pair.origin, pLocale))).append("</td></tr>");
		}
		pOut.append("</table></div>");
	}

	/**
	 * "Engine candidate" / "Factory probe: shared key" / "Factory probe: near miss" (task brief, 2026-09-22:
	 * every row must be tagged with its origin) - the raw {@link MatchPlanView.SimulationPair#origin} value
	 * shown as-is (never blank) when it is none of the three known constants, which cannot happen from {@code
	 * ebx.MatchPlanSimulationRunner} today but is a safer default than silently dropping the column's content.
	 */
	static String originWords(final String pOrigin, final Locale pLocale)
	{
		if (MatchPlanView.SimulationPair.ORIGIN_PROBE_SHARED_KEY.equals(pOrigin))
		{
			return ReviewMessages.get(pLocale, "review.match.simulate.origin.sharedKey");
		}
		if (MatchPlanView.SimulationPair.ORIGIN_PROBE_NEAR_MISS.equals(pOrigin))
		{
			return ReviewMessages.get(pLocale, "review.match.simulate.origin.nearMiss");
		}
		if (MatchPlanView.SimulationPair.ORIGIN_ENGINE.equals(pOrigin))
		{
			return ReviewMessages.get(pLocale, "review.match.simulate.origin.engine");
		}
		return pOrigin == null ? "?" : pOrigin;
	}

	/**
	 * One field cell: "{@code =}"/"{@code #}" on the two real, raw values for an EXACT-role field (task
	 * brief item 4 - the FOLDED value: every EXACT-algorithm field this codebase's own plan generation ever
	 * proposes is already either the entity's own identifier or an already-folded {@code <attr>Key} derived
	 * attribute, per {@code master.MatchPlanLlmPass}'s own field-role guards - see {@code
	 * ebx.MatchPlanSimulationRunner}'s own class Javadoc for the reasoning this class inherits rather than
	 * re-folding a raw value itself); the real engine score plus the tier's own threshold for a fuzzy field.
	 * <p>
	 * <b>2026-09-22 diagnostic-visibility lot</b>: when {@link MatchPlanView.SimulationPair#fieldScores} carries
	 * no entry for {@code pField}, the visible cell text is now a short "n/a" (never the pre-lot bare "score not
	 * exposed by the engine" on every such row - task brief item 1), with the REASON in the cell's own {@code
	 * title} attribute: {@code pField} named in {@code pFullTextFields} gets the "full-text algorithm" wording
	 * (a HYPOTHESIS, never measured live - see {@code ebx.MameMatchingApi.FieldScore}'s own Javadoc, task brief
	 * item 2), anything else keeps the older generic "not exposed" reason. The muted, once-per-click summary line
	 * ({@code review.match.simulate.scoreDiagnostics}, {@link MatchPlanView#renderSimulationBlock}) is where an
	 * actual FAILURE (an exception, or a field the add-on could have scored but didn't) gets its detail - this
	 * cell never repeats that detail per row, only points at it via the short reason.
	 */
	private static void renderFieldCell(final StringBuilder pOut, final JsonNode pRequirements, final Set<String> pExactFields, final Set<String> pFullTextFields,
		final JsonNode pAverageTier, final String pField, final MatchPlanView.SimulationPair pPair, final Locale pLocale)
	{
		pOut.append("<td>");
		if (pExactFields.contains(pField))
		{
			final String pivotValue = pPair.pivotValues.get(pField);
			final String targetValue = pPair.targetValues.get(pField);
			pOut.append(ReviewPage.esc(MatchSimulationBlock.exactCellWords(pivotValue, targetValue)));
		}
		else
		{
			final Double score = pPair.fieldScores.get(pField);
			if (score == null)
			{
				final String reasonKey = pFullTextFields.contains(pField) ? "review.match.simulate.scoreFullText" : "review.match.simulate.scoreNotExposed.reason";
				pOut.append("<span class=\"mdmf-hint\" title=\"").append(ReviewPage.esc(ReviewMessages.get(pLocale, reasonKey))).append("\">")
					.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.scoreNotExposed.short"))).append("</span>");
			}
			else
			{
				// binary-decision-tree-screen lot (2026-09-22): the threshold shown is the AVERAGE node this PAIR
				// actually reached (via its real enginePath) when known, not always the plan's first AVERAGE node
				// overall - a general tree can have more than one, at different thresholds (task brief item 3).
				final JsonNode effectiveAverageTier = MatchSimulationBlock.resolveAverageTierForPair(pRequirements, pPair, pAverageTier);
				final int minimumScore = effectiveAverageTier == null || effectiveAverageTier.isMissingNode() ? 90 : effectiveAverageTier.path("minimumScore").asInt(90);
				pOut.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.scoreVsThreshold", score, Integer.valueOf(minimumScore))));
			}
		}
		pOut.append("</td>");
	}

	/** "{@code = VALUE}" (case-insensitive/trimmed equal), "{@code VALUE # VALUE}" (different), or "{@code -}" (either side missing) - same convention {@code MatchPlanView}'s own retired {@code fieldComparisonWords} used, kept package-private here for direct unit testing. */
	static String exactCellWords(final String pPivotValue, final String pTargetValue)
	{
		if (pPivotValue == null || pTargetValue == null)
		{
			return "-";
		}
		if (pPivotValue.trim().equalsIgnoreCase(pTargetValue.trim()))
		{
			return "= " + pPivotValue;
		}
		return pPivotValue + " ≠ " + pTargetValue;
	}

	private static void renderVerdictChip(final StringBuilder pOut, final String pVerdict, final Locale pLocale)
	{
		final String normalized = MatchSimulationBlock.normalizedVerdict(pVerdict);
		final String chipClass = "MATCH".equals(normalized) ? "mdmf-chip-real" : "SUSPECT".equals(normalized) ? "mdmf-chip-warn"
			: "NOT_MATCH".equals(normalized) ? "mdmf-chip-muted" : "mdmf-chip-target";
		final String key = "MATCH".equals(normalized) ? "review.match.simulate.verdict.match"
			: "SUSPECT".equals(normalized) ? "review.match.simulate.verdict.suspect" : "NOT_MATCH".equals(normalized) ? "review.match.simulate.verdict.notMatch" : null;
		pOut.append("<span class=\"mdmf-chip ").append(chipClass).append("\">")
			.append(ReviewPage.esc(key == null ? (pVerdict == null || pVerdict.isEmpty() ? "?" : pVerdict) : ReviewMessages.get(pLocale, key))).append("</span>");
	}

	/** {@code MATCHED}/{@code MATCH} -&gt; {@code MATCH}; {@code SUSPECTED}/{@code SUSPECT} -&gt; {@code SUSPECT}; {@code NOT_MATCHED}/{@code NOT_MATCH} -&gt; {@code NOT_MATCH}; anything else -&gt; {@code ""}. */
	static String normalizedVerdict(final String pVerdict)
	{
		final String upper = pVerdict == null ? "" : pVerdict.trim().toUpperCase(Locale.ROOT);
		if ("MATCH".equals(upper) || "MATCHED".equals(upper))
		{
			return "MATCH";
		}
		if ("SUSPECT".equals(upper) || "SUSPECTED".equals(upper))
		{
			return "SUSPECT";
		}
		return "NOT_MATCH".equals(upper) || "NOT_MATCHED".equals(upper) ? "NOT_MATCH" : "";
	}

	// =================================================================================================== analysis

	static final class Analysis
	{
		final int examined;
		final int pivotCount;
		/** review-match-plan-simulation-probes lot (2026-09-22): how many of {@link #examined} pairs came from {@code ebx.MatchPlanSimulationRunner}'s own probe sets rather than the add-on's own {@code simulateMatch} clustering - {@link #probeSharedKeyExamined} + {@link #probeNearMissExamined}. Shown next to "candidates examined" so a reviewer sees the sample was actively widened, not merely lucky. */
		final int probedCount;
		/** How many {@code ORIGIN_PROBE_SHARED_KEY} pairs actually got a verdict (see {@code ebx.MatchPlanSimulationRunner}'s own detail-cap ordering - a probe pair that never reached {@code evaluatePair} is dropped before it ever reaches this class) - used to word family 2's empty state with a real number rather than a bare "no pair of this kind". */
		final int probeSharedKeyExamined;
		/** Same as {@link #probeSharedKeyExamined}, for {@code ORIGIN_PROBE_NEAR_MISS} - used to word family 1's empty state. */
		final int probeNearMissExamined;
		final int matchCount;
		final int suspectCount;
		final int suspectCaughtByFuzzy;
		final int notMatchCount;
		final int notMatchSameKey;
		final int borderlineCount;
		/** Up to {@link #MAX_FAMILY_ROWS} engine-candidate {@code SUSPECT} pairs with at least one real per-field score - the "never an empty panel" fallback (task brief, 2026-09-22): {@link #render} shows this ONLY when {@link #family1}/{@link #family2}/{@link #family3} are all empty, so the reviewer always sees at least a few real, scored pairs instead of three bare "no pair of this kind" lines. Empty when there is genuinely no scored SUSPECT engine candidate either - never fabricated. */
		final List<MatchPlanView.SimulationPair> family0;
		final List<MatchPlanView.SimulationPair> family1;
		final List<MatchPlanView.SimulationPair> family2;
		final List<MatchPlanView.SimulationPair> family3;

		Analysis(final int pExamined, final int pPivotCount, final int pProbedCount, final int pProbeSharedKeyExamined, final int pProbeNearMissExamined,
			final int pMatchCount, final int pSuspectCount, final int pSuspectCaughtByFuzzy, final int pNotMatchCount, final int pNotMatchSameKey,
			final int pBorderlineCount, final List<MatchPlanView.SimulationPair> pFamily0, final List<MatchPlanView.SimulationPair> pFamily1,
			final List<MatchPlanView.SimulationPair> pFamily2, final List<MatchPlanView.SimulationPair> pFamily3)
		{
			this.examined = pExamined;
			this.pivotCount = pPivotCount;
			this.probedCount = pProbedCount;
			this.probeSharedKeyExamined = pProbeSharedKeyExamined;
			this.probeNearMissExamined = pProbeNearMissExamined;
			this.matchCount = pMatchCount;
			this.suspectCount = pSuspectCount;
			this.suspectCaughtByFuzzy = pSuspectCaughtByFuzzy;
			this.notMatchCount = pNotMatchCount;
			this.notMatchSameKey = pNotMatchSameKey;
			this.borderlineCount = pBorderlineCount;
			this.family0 = pFamily0;
			this.family1 = pFamily1;
			this.family2 = pFamily2;
			this.family3 = pFamily3;
		}
	}

	/**
	 * The numeric summary plus the three families of instructive pairs, sorted deterministically (pivot label
	 * then target label, plain string order - task brief: "sort candidates deterministically for display",
	 * the DRAW itself is random, the DISPLAY never is) before either counting or selecting rows, so two
	 * renders of the SAME simulation result always agree.
	 */
	static Analysis analyze(final JsonNode pRequirements, final Set<String> pExactFields, final JsonNode pAverageTier,
		final List<MatchPlanView.SimulationPair> pPairs, final int pPivotCount)
	{
		final List<MatchPlanView.SimulationPair> sorted = new ArrayList<MatchPlanView.SimulationPair>(pPairs);
		java.util.Collections.sort(sorted, new java.util.Comparator<MatchPlanView.SimulationPair>()
		{
			@Override
			public int compare(final MatchPlanView.SimulationPair pA, final MatchPlanView.SimulationPair pB)
			{
				final int byPivot = MatchSimulationBlock.safe(pA.pivotLabel).compareTo(MatchSimulationBlock.safe(pB.pivotLabel));
				return byPivot != 0 ? byPivot : MatchSimulationBlock.safe(pA.targetLabel).compareTo(MatchSimulationBlock.safe(pB.targetLabel));
			}
		});

		int match = 0;
		int suspect = 0;
		int suspectCaughtByFuzzy = 0;
		int notMatch = 0;
		int notMatchSameKey = 0;
		int borderline = 0;
		int probedCount = 0;
		int probeSharedKeyExamined = 0;
		int probeNearMissExamined = 0;
		final List<MatchPlanView.SimulationPair> engineSuspects = new ArrayList<MatchPlanView.SimulationPair>();
		final List<MatchPlanView.SimulationPair> family1 = new ArrayList<MatchPlanView.SimulationPair>();
		final List<MatchPlanView.SimulationPair> family2 = new ArrayList<MatchPlanView.SimulationPair>();
		final List<MatchPlanView.SimulationPair> family3 = new ArrayList<MatchPlanView.SimulationPair>();

		for (final MatchPlanView.SimulationPair pair : sorted)
		{
			final String verdict = MatchSimulationBlock.normalizedVerdict(pair.verdict);
			// review-match-plan-simulation-probes lot (2026-09-22): family 1/2 now key on the plan's own
			// IDENTIFYING key field (ebx.MatchPlanSimulationRunner#keyFieldName, e.g. nameKey) when the runner
			// could compute it (pair.sameKey != null) - falling back to the older exact-TIER-fields test
			// (country/cityKey) otherwise, so every pre-existing fixture/test built before this lot (sameKey
			// always null there) keeps behaving exactly as before. See class Javadoc, "what is real vs. derived".
			final Boolean keyEqual = pair.sameKey != null ? pair.sameKey : MatchSimulationBlock.exactFieldsEqual(pExactFields, pair);
			// binary-decision-tree-screen lot (2026-09-22): borderline uses the threshold of the AVERAGE node this
			// pair actually reached (task brief item 3) - falls back to the plan's first AVERAGE node overall
			// (pAverageTier, unchanged) whenever no real enginePath is available, so every pre-lot fixture (none
			// of which ever sets enginePath) keeps behaving exactly as before.
			final boolean isBorderline = MatchSimulationBlock.isBorderline(MatchSimulationBlock.resolveAverageTierForPair(pRequirements, pair, pAverageTier), pair);
			if (isBorderline)
			{
				borderline++;
				if (family3.size() < MatchSimulationBlock.MAX_FAMILY_ROWS)
				{
					family3.add(pair);
				}
			}
			if (!MatchPlanView.SimulationPair.ORIGIN_ENGINE.equals(pair.origin))
			{
				probedCount++;
				if (MatchPlanView.SimulationPair.ORIGIN_PROBE_SHARED_KEY.equals(pair.origin))
				{
					probeSharedKeyExamined++;
				}
				else if (MatchPlanView.SimulationPair.ORIGIN_PROBE_NEAR_MISS.equals(pair.origin))
				{
					probeNearMissExamined++;
				}
			}
			if ("MATCH".equals(verdict) || "SUSPECT".equals(verdict))
			{
				if ("MATCH".equals(verdict))
				{
					match++;
				}
				else
				{
					suspect++;
					if (MatchPlanView.SimulationPair.ORIGIN_ENGINE.equals(pair.origin) && !pair.fieldScores.isEmpty()
						&& engineSuspects.size() < MatchSimulationBlock.MAX_FAMILY_ROWS)
					{
						engineSuspects.add(pair);
					}
				}
				if (Boolean.FALSE.equals(keyEqual))
				{
					suspectCaughtByFuzzy++;
					if (family1.size() < MatchSimulationBlock.MAX_FAMILY_ROWS)
					{
						family1.add(pair);
					}
				}
			}
			else if ("NOT_MATCH".equals(verdict))
			{
				notMatch++;
				if (Boolean.TRUE.equals(keyEqual))
				{
					notMatchSameKey++;
					if (family2.size() < MatchSimulationBlock.MAX_FAMILY_ROWS)
					{
						family2.add(pair);
					}
				}
			}
		}

		// family 0 ("never an empty panel", task brief 2026-09-22): only populated - and only ever RENDERED,
		// see render() - when families 1-3 are all empty, so the reviewer still sees a few real, scored pairs
		// instead of three bare empty lines.
		final List<MatchPlanView.SimulationPair> family0 = family1.isEmpty() && family2.isEmpty() && family3.isEmpty()
			? engineSuspects
			: new ArrayList<MatchPlanView.SimulationPair>();

		return new Analysis(sorted.size(), pPivotCount, probedCount, probeSharedKeyExamined, probeNearMissExamined, match, suspect, suspectCaughtByFuzzy, notMatch,
			notMatchSameKey, borderline, family0, family1, family2, family3);
	}

	private static String safe(final String pValue)
	{
		return pValue == null ? "" : pValue;
	}

	// ------------------------------------------------------------------------------------------------ field roles

	/**
	 * Every field named with the exact algorithm ({@code ...@exact}, case-insensitive suffix match - {@code
	 * review.ProposalWording#matchAlgorithmWords}'s own convention) anywhere across ALL of the plan's nodes
	 * (union, pre-order - binary-decision-tree-screen lot, 2026-09-22: unchanged behaviour for a plain cascade,
	 * which is exactly what this method already scanned end to end before the tree rework; a genuinely
	 * branching tree is now walked fully instead of answering an empty view).
	 */
	static LinkedHashSet<String> exactFieldNames(final JsonNode pRequirements)
	{
		final LinkedHashSet<String> out = new LinkedHashSet<String>();
		for (final ReviewDecisionTree.Node node : ReviewDecisionTree.readFrom(pRequirements).nodes)
		{
			for (final ReviewDecisionTree.Field field : node.fields)
			{
				if (MatchSimulationBlock.isExactAlgorithm(field.algorithm))
				{
					if (field.field != null && !field.field.isEmpty())
					{
						out.add(field.field);
					}
				}
			}
		}
		return out;
	}

	private static boolean isExactAlgorithm(final String pAlgorithm)
	{
		final String trimmed = pAlgorithm == null ? "" : pAlgorithm.trim();
		final int at = trimmed.lastIndexOf('@');
		final String suffix = (at >= 0 ? trimmed.substring(at + 1) : trimmed).toLowerCase(Locale.ROOT);
		return "exact".equals(suffix);
	}

	/**
	 * Every field named with a full-text algorithm ({@code fuzzyFullText}/{@code phoneticFullText}/{@code
	 * exactFullText}, case-insensitive suffix match - same convention as {@link #isExactAlgorithm}) anywhere
	 * across ALL of the plan's nodes (union, pre-order) - diagnostic-visibility lot, 2026-09-22, task brief item
	 * 2. These are Lucene-query algorithms this class HYPOTHESIZES (never measured live - see {@code
	 * ebx.MameMatchingApi.FieldScore}'s own Javadoc) the add-on's decision-tree evaluation does not expose a
	 * per-field {@code ScoreField} for at all, as distinct from a field the engine COULD score but the call
	 * failed/was never attempted for - {@link #renderFieldCell} words the two cases differently.
	 */
	static LinkedHashSet<String> fullTextFieldNames(final JsonNode pRequirements)
	{
		final LinkedHashSet<String> out = new LinkedHashSet<String>();
		for (final ReviewDecisionTree.Node node : ReviewDecisionTree.readFrom(pRequirements).nodes)
		{
			for (final ReviewDecisionTree.Field field : node.fields)
			{
				if (MatchSimulationBlock.isFullTextAlgorithm(field.algorithm))
				{
					if (field.field != null && !field.field.isEmpty())
					{
						out.add(field.field);
					}
				}
			}
		}
		return out;
	}

	private static boolean isFullTextAlgorithm(final String pAlgorithm)
	{
		final String trimmed = pAlgorithm == null ? "" : pAlgorithm.trim();
		final int at = trimmed.lastIndexOf('@');
		final String suffix = (at >= 0 ? trimmed.substring(at + 1) : trimmed).toLowerCase(Locale.ROOT);
		return "fuzzyfulltext".equals(suffix) || "phoneticfulltext".equals(suffix) || "exactfulltext".equals(suffix);
	}

	/**
	 * The first {@code AVERAGE} node of the plan in pre-order, converted to the same tier-JSON shape this class
	 * always used ({@code {type, minimumScore, fields:[{field, weight}]}}) - or a Jackson {@code MissingNode} if
	 * none. Binary-decision-tree-screen lot (2026-09-22): walks the real tree via {@link ReviewDecisionTree}
	 * instead of a cascade-only view - identical result for a plain cascade, and no longer an empty view for a
	 * genuinely branching tree. This remains the class-wide FALLBACK average node; {@link
	 * #resolveAverageTierForPair} prefers the node a specific pair's own real path actually reached, when known.
	 */
	static JsonNode firstAverageTier(final JsonNode pRequirements)
	{
		for (final ReviewDecisionTree.Node node : ReviewDecisionTree.readFrom(pRequirements).nodes)
		{
			if (ReviewDecisionTree.FUNCTION_AVERAGE.equals(node.function))
			{
				return MatchSimulationBlock.toTierJson(node);
			}
		}
		return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
	}

	/** {@code {type:"AVERAGE", minimumScore, fields:[{field, weight}]}} - the exact shape {@link #weightedAverageScore}/{@link #isBorderline}/{@link #renderFieldCell} already expect, so neither needed a signature change for this lot. */
	private static JsonNode toTierJson(final ReviewDecisionTree.Node pNode)
	{
		final ObjectNode out = JsonNodeFactory.instance.objectNode();
		out.put("type", pNode.function);
		out.put("minimumScore", pNode.minimumScore == null ? 90 : pNode.minimumScore.intValue());
		final com.fasterxml.jackson.databind.node.ArrayNode fields = out.putArray("fields");
		for (final ReviewDecisionTree.Field field : pNode.fields)
		{
			final ObjectNode fieldOut = fields.addObject();
			fieldOut.put("field", field.field);
			fieldOut.put("weight", field.weight == null ? 1 : field.weight.intValue());
		}
		return out;
	}

	/**
	 * {@code TRUE} when every EXACT-role field present on BOTH sides of the pair agrees (case-insensitive,
	 * trimmed) and at least one such field was actually comparable; {@code FALSE} when at least one disagrees;
	 * {@code null} ("not applicable/not measured") when the plan declares no exact field, or neither side has
	 * a value for any of them - never guessed either way.
	 */
	static Boolean exactFieldsEqual(final Set<String> pExactFields, final MatchPlanView.SimulationPair pPair)
	{
		boolean anyCompared = false;
		for (final String field : pExactFields)
		{
			final String pivotValue = pPair.pivotValues.get(field);
			final String targetValue = pPair.targetValues.get(field);
			if (pivotValue == null || targetValue == null)
			{
				continue;
			}
			anyCompared = true;
			if (!pivotValue.trim().equalsIgnoreCase(targetValue.trim()))
			{
				return Boolean.FALSE;
			}
		}
		return anyCompared ? Boolean.TRUE : null;
	}

	/**
	 * This class' own re-derivation of an AVERAGE tier's combined score (weighted mean of the real per-field
	 * scores {@code ebx.MameMatchingApi#evaluatePair} obtained, using the plan's own declared per-field
	 * weight) - see class Javadoc, "what is real vs. derived", for why this is an approximation of {@code
	 * master.MatchingPolicyTranslator}'s own "Average" node, not a restatement of it. {@code null} when the
	 * tier does not exist, or ANY of its fields is missing a real score for this pair (never a partial/guessed
	 * average).
	 */
	static Double weightedAverageScore(final JsonNode pAverageTier, final MatchPlanView.SimulationPair pPair)
	{
		if (pAverageTier == null || pAverageTier.isMissingNode())
		{
			return null;
		}
		double weightedSum = 0;
		double totalWeight = 0;
		boolean anyField = false;
		for (final JsonNode field : pAverageTier.path("fields"))
		{
			anyField = true;
			final String name = field.path("field").asText(null);
			final Double score = name == null ? null : pPair.fieldScores.get(name);
			if (score == null)
			{
				return null;
			}
			final int weight = Math.max(1, field.path("weight").asInt(1));
			weightedSum += score.doubleValue() * weight;
			totalWeight += weight;
		}
		return anyField && totalWeight > 0 ? Double.valueOf(weightedSum / totalWeight) : null;
	}

	/** Within {@link #BORDERLINE_MARGIN_UNITS} of the tier's own {@code minimumScore} - {@code false} (never fabricated as borderline) when {@link #weightedAverageScore} could not be computed. */
	static boolean isBorderline(final JsonNode pAverageTier, final MatchPlanView.SimulationPair pPair)
	{
		final Double score = MatchSimulationBlock.weightedAverageScore(pAverageTier, pPair);
		if (score == null)
		{
			return false;
		}
		final int minimumScore = pAverageTier.path("minimumScore").asInt(90);
		return Math.abs(score.doubleValue() - minimumScore) <= MatchSimulationBlock.BORDERLINE_MARGIN_UNITS;
	}

	// -------------------------------------------------------------------------------------------------- leaf words

	/**
	 * Binary-decision-tree-screen lot (2026-09-22, task brief item 3): the full NAMED PATH from the plan's
	 * root to the leaf {@code pPair}'s own real verdict reached, e.g. {@code "N1 does not pass -> N4 score 93
	 * -> N5 passes -> Suspect"} - never the other way around (the verdict is trusted; this text only explains
	 * it as far as this class can tell). Two sources, tried in order:
	 * <ol>
	 * <li>{@link MatchPlanView.SimulationPair#enginePath} ({@code ebx.MameMatchingApi.TreeEvaluation
	 * #nodeNames}, the add-on's OWN real visit order for this pair) matched against the plan's nodes by the
	 * label {@code master.DecisionTreePlan}/{@link ReviewDecisionTree#engineName} would have sent it as -
	 * {@link #matchEnginePath}. When a step's wanted name matches neither branch target unambiguously (a
	 * relabelled node, or two siblings that happen to render the same auto-label), the match falls back to
	 * whichever branch target is itself a node rather than a leaf and the whole path is marked approximate (the
	 * {@code review.match.simulate.path.approx} note appended) - "say so" rather than silently presenting a
	 * guess as certain.</li>
	 * <li>Failing that (no real path available, e.g. {@code evaluatePair} was never attempted for this pair),
	 * the pre-lot field/score heuristic - generalized here from a cascade's own tier-by-tier check to a real
	 * WALK of the tree (at each node, decide the branch the same way the pre-lot code decided a tier's own
	 * outcome, then continue into whichever node or leaf that branch names) - {@link #heuristicWalk}. Exactly
	 * reproduces the pre-lot behaviour for any plan that is, in fact, a plain cascade (a single node's own
	 * accept/refuse decision immediately reaches a leaf, precisely as before).</li>
	 * </ol>
	 * Neither source able to produce a path whose own concluded leaf agrees with {@code pPair}'s REAL verdict:
	 * the honest {@code review.match.simulate.leaf.unknown} fallback, exactly as the pre-lot code answered when
	 * it had no refusal to report either.
	 */
	static String leafWords(final JsonNode pRequirements, final MatchPlanView.SimulationPair pPair, final Locale pLocale)
	{
		final ReviewDecisionTree.Plan plan = ReviewDecisionTree.readFrom(pRequirements);
		if (plan.root != null && plan.byId(plan.root) != null)
		{
			final String verdictCategory = MatchSimulationBlock.normalizedVerdict(pPair.verdict);
			if (!pPair.enginePath.isEmpty())
			{
				final String viaEngine = MatchSimulationBlock.pathFromEnginePath(plan, pPair, verdictCategory, pLocale);
				if (viaEngine != null)
				{
					return viaEngine;
				}
			}
			final String viaHeuristic = MatchSimulationBlock.heuristicWalk(plan, pPair, verdictCategory, pLocale);
			if (viaHeuristic != null)
			{
				return viaHeuristic;
			}
		}
		return ReviewMessages.get(pLocale, "review.match.simulate.leaf.unknown", pPair.verdict == null ? "?" : pPair.verdict);
	}

	/** {@code MATCH}/{@code SUSPECT}/{@code NOT_MATCH} (as {@link #normalizedVerdict} already names them) -&gt; the leaf token {@link ReviewDecisionTree} uses for the same outcome. */
	private static boolean leafMatchesCategory(final String pLeafToken, final String pVerdictCategory)
	{
		if (ReviewDecisionTree.LEAF_MATCH.equals(pLeafToken))
		{
			return "MATCH".equals(pVerdictCategory);
		}
		if (ReviewDecisionTree.LEAF_SUSPECTED.equals(pLeafToken))
		{
			return "SUSPECT".equals(pVerdictCategory);
		}
		return ReviewDecisionTree.LEAF_NOT_MATCHED.equals(pLeafToken) && "NOT_MATCH".equals(pVerdictCategory);
	}

	private static String leafWord(final String pVerdictCategory, final Locale pLocale)
	{
		if ("MATCH".equals(pVerdictCategory))
		{
			return ReviewMessages.get(pLocale, "review.match.simulate.verdict.match");
		}
		if ("SUSPECT".equals(pVerdictCategory))
		{
			return ReviewMessages.get(pLocale, "review.match.simulate.verdict.suspect");
		}
		if ("NOT_MATCH".equals(pVerdictCategory))
		{
			return ReviewMessages.get(pLocale, "review.match.simulate.verdict.notMatch");
		}
		return pVerdictCategory;
	}

	private static String join(final List<String> pValues, final String pSeparator)
	{
		final StringBuilder out = new StringBuilder();
		for (final String value : pValues)
		{
			if (out.length() > 0)
			{
				out.append(pSeparator);
			}
			out.append(value);
		}
		return out.toString();
	}

	// ------------------------------------------------------------------------------------------- engine path match

	/** {@link #matchEnginePath}'s own result - see {@link #leafWords}. */
	private static final class PathMatch
	{
		final List<ReviewDecisionTree.Node> nodes;
		final boolean approximate;

		PathMatch(final List<ReviewDecisionTree.Node> pNodes, final boolean pApproximate)
		{
			this.nodes = pNodes;
			this.approximate = pApproximate;
		}
	}

	/**
	 * Matches {@code pEngineNames} (a real {@code TreeEvaluation.nodeNames} sequence) against {@code pPlan}'s
	 * own nodes, one root-to-somewhere path - {@link #leafWords}'s own class Javadoc, item 1. Returns every
	 * node it could resolve, even when it had to fall back positionally for one step (never a full failure
	 * just because one step was ambiguous) - {@link PathMatch#approximate} flags that this happened at least
	 * once.
	 */
	private static PathMatch matchEnginePath(final ReviewDecisionTree.Plan pPlan, final List<String> pEngineNames)
	{
		final List<ReviewDecisionTree.Node> matched = new ArrayList<ReviewDecisionTree.Node>();
		boolean approximate = false;
		if (pEngineNames.isEmpty() || pPlan.root == null)
		{
			return new PathMatch(matched, false);
		}
		ReviewDecisionTree.Node current = pPlan.byId(pPlan.root);
		if (current == null)
		{
			return new PathMatch(matched, false);
		}
		if (!ReviewDecisionTree.engineName(current).equals(pEngineNames.get(0)))
		{
			approximate = true;
		}
		matched.add(current);
		for (int i = 1; i < pEngineNames.size(); i++)
		{
			final String wanted = pEngineNames.get(i);
			final ReviewDecisionTree.Node trueNode = ReviewDecisionTree.isLeaf(current.onTrue) ? null : pPlan.byId(current.onTrue);
			final ReviewDecisionTree.Node falseNode = ReviewDecisionTree.isLeaf(current.onFalse) ? null : pPlan.byId(current.onFalse);
			final boolean trueMatches = trueNode != null && ReviewDecisionTree.engineName(trueNode).equals(wanted);
			final boolean falseMatches = falseNode != null && ReviewDecisionTree.engineName(falseNode).equals(wanted);
			final ReviewDecisionTree.Node next;
			if (trueMatches && !falseMatches)
			{
				next = trueNode;
			}
			else if (falseMatches && !trueMatches)
			{
				next = falseNode;
			}
			else
			{
				// ambiguous (both/neither label matched) - fall back to whichever branch target is itself a node
				// (a leaf has nothing further to name), flagged approximate rather than silently presented as sure.
				approximate = true;
				next = trueNode != null ? trueNode : falseNode;
			}
			if (next == null)
			{
				break;
			}
			matched.add(next);
			current = next;
		}
		return new PathMatch(matched, approximate);
	}

	private static String pathFromEnginePath(final ReviewDecisionTree.Plan pPlan, final MatchPlanView.SimulationPair pPair, final String pVerdictCategory,
		final Locale pLocale)
	{
		final PathMatch match = MatchSimulationBlock.matchEnginePath(pPlan, pPair.enginePath);
		if (match.nodes.isEmpty())
		{
			return null;
		}
		final List<String> steps = new ArrayList<String>();
		for (int i = 0; i < match.nodes.size(); i++)
		{
			final ReviewDecisionTree.Node node = match.nodes.get(i);
			final ReviewDecisionTree.Node next = i + 1 < match.nodes.size() ? match.nodes.get(i + 1) : null;
			final Boolean branchTrue;
			if (next != null)
			{
				branchTrue = node.onTrue.equals(next.id) ? Boolean.TRUE : node.onFalse.equals(next.id) ? Boolean.FALSE : null;
			}
			else
			{
				final boolean trueIsMatch = ReviewDecisionTree.isLeaf(node.onTrue) && MatchSimulationBlock.leafMatchesCategory(node.onTrue, pVerdictCategory);
				final boolean falseIsMatch = ReviewDecisionTree.isLeaf(node.onFalse) && MatchSimulationBlock.leafMatchesCategory(node.onFalse, pVerdictCategory);
				branchTrue = trueIsMatch && !falseIsMatch ? Boolean.TRUE : falseIsMatch && !trueIsMatch ? Boolean.FALSE : null;
			}
			if (branchTrue == null)
			{
				return null;
			}
			steps.add(MatchSimulationBlock.stepLabel(node, branchTrue.booleanValue(), pPair, pLocale));
		}
		steps.add(MatchSimulationBlock.leafWord(pVerdictCategory, pLocale));
		String joined = MatchSimulationBlock.join(steps, " → ");
		if (match.approximate)
		{
			joined = joined + " " + ReviewMessages.get(pLocale, "review.match.simulate.path.approx");
		}
		return joined;
	}

	// ------------------------------------------------------------------------------------------- heuristic walk

	/**
	 * The pre-lot field/score heuristic, generalized from "check every tier independently" to a real walk of
	 * the tree - see {@link #leafWords}'s own class Javadoc, item 2. Reproduces the pre-lot cascade behaviour
	 * exactly (a single accept/refuse decision per node immediately reaches a leaf for any plain cascade).
	 * Returns {@code null} - never a misleading "clean" path - as soon as either: a node's own acceptance
	 * cannot be told at all (missing field values/scores), or the walk DOES reach a leaf but that leaf
	 * disagrees with {@code pPair}'s own real verdict (this class' heuristic guessed a branch the real engine
	 * did not actually take - the caller then shows the honest "not enough data" message rather than a path
	 * that contradicts the trusted verdict).
	 */
	private static String heuristicWalk(final ReviewDecisionTree.Plan pPlan, final MatchPlanView.SimulationPair pPair, final String pVerdictCategory,
		final Locale pLocale)
	{
		final List<String> steps = new ArrayList<String>();
		ReviewDecisionTree.Node current = pPlan.byId(pPlan.root);
		int guard = 0;
		while (current != null && guard++ < 128)
		{
			final Boolean accepted = MatchSimulationBlock.nodeAccepted(current, pPair);
			if (accepted == null)
			{
				return null;
			}
			steps.add(MatchSimulationBlock.stepLabel(current, accepted.booleanValue(), pPair, pLocale));
			final String targetId = accepted.booleanValue() ? current.onTrue : current.onFalse;
			if (ReviewDecisionTree.isLeaf(targetId))
			{
				if (!MatchSimulationBlock.leafMatchesCategory(targetId, pVerdictCategory))
				{
					return null;
				}
				steps.add(MatchSimulationBlock.leafWord(pVerdictCategory, pLocale));
				return MatchSimulationBlock.join(steps, " → ");
			}
			current = pPlan.byId(targetId);
		}
		return null;
	}

	private static String stepLabel(final ReviewDecisionTree.Node pNode, final boolean pAcceptedTrue, final MatchPlanView.SimulationPair pPair, final Locale pLocale)
	{
		if (ReviewDecisionTree.FUNCTION_AVERAGE.equals(pNode.function))
		{
			final Double score = MatchSimulationBlock.weightedAverageScore(MatchSimulationBlock.toTierJson(pNode), pPair);
			return ReviewMessages.get(pLocale, "review.match.simulate.path.average", pNode.id,
				score == null ? "?" : MatchSimulationBlock.formatScore(score.doubleValue()));
		}
		return pNode.id + " " + ReviewMessages.get(pLocale, pAcceptedTrue ? "review.match.tree.branch.true" : "review.match.tree.branch.false");
	}

	private static String formatScore(final double pValue)
	{
		if (pValue == Math.rint(pValue))
		{
			return String.valueOf((long) pValue);
		}
		return String.format(Locale.ROOT, "%.1f", Double.valueOf(pValue));
	}

	/**
	 * Generalizes the pre-lot {@code exactTierAccepted}/{@code averageTierAccepted} to every function this
	 * codebase's tree can hold: {@code ALL_FIELDS} needs every COMPARABLE field to agree (mirrors the pre-lot
	 * {@code exactTierAccepted} exactly - a field neither side has a value for is simply skipped, never treated
	 * as a disagreement); {@code AT_LEAST_N} needs at least {@link ReviewDecisionTree.Node#minimumMatches} of
	 * its fields to agree; {@code AVERAGE} reuses {@link #weightedAverageScore} against the node's own
	 * threshold; {@code PREDICATE} can never be evaluated here (free-form logic no field/score comparison can
	 * reproduce) - always {@code null}. {@code null} whenever nothing reliable could be told either way.
	 */
	private static Boolean nodeAccepted(final ReviewDecisionTree.Node pNode, final MatchPlanView.SimulationPair pPair)
	{
		if (ReviewDecisionTree.FUNCTION_AVERAGE.equals(pNode.function))
		{
			final Double score = MatchSimulationBlock.weightedAverageScore(MatchSimulationBlock.toTierJson(pNode), pPair);
			if (score == null)
			{
				return null;
			}
			return Boolean.valueOf(score.doubleValue() >= (pNode.minimumScore == null ? 90 : pNode.minimumScore.intValue()));
		}
		if (ReviewDecisionTree.FUNCTION_PREDICATE.equals(pNode.function))
		{
			return null;
		}
		boolean anyCompared = false;
		int agree = 0;
		for (final ReviewDecisionTree.Field field : pNode.fields)
		{
			final String pivotValue = field.field == null ? null : pPair.pivotValues.get(field.field);
			final String targetValue = field.field == null ? null : pPair.targetValues.get(field.field);
			if (pivotValue == null || targetValue == null)
			{
				continue;
			}
			anyCompared = true;
			if (pivotValue.trim().equalsIgnoreCase(targetValue.trim()))
			{
				agree++;
			}
			else if (ReviewDecisionTree.FUNCTION_ALL_FIELDS.equals(pNode.function))
			{
				return Boolean.FALSE;
			}
		}
		if (!anyCompared)
		{
			return null;
		}
		if (ReviewDecisionTree.FUNCTION_ALL_FIELDS.equals(pNode.function))
		{
			return Boolean.TRUE;
		}
		final int needed = pNode.minimumMatches == null ? 1 : pNode.minimumMatches.intValue();
		return Boolean.valueOf(agree >= needed);
	}

	// ---------------------------------------------------------------------------------- per-pair average node

	/**
	 * The AVERAGE node {@code pPair}'s own real path actually reached (via {@link #matchEnginePath} on {@link
	 * MatchPlanView.SimulationPair#enginePath}), converted to the tier-JSON shape {@link #weightedAverageScore}/
	 * {@link #isBorderline} already expect - falls back to {@code pFallback} (the plan-wide {@link
	 * #firstAverageTier}) whenever no real path is available, or none of its nodes is an AVERAGE node. Task
	 * brief item 3: "borderline detection uses the threshold of the AVERAGE node actually reached".
	 */
	private static JsonNode resolveAverageTierForPair(final JsonNode pRequirements, final MatchPlanView.SimulationPair pPair, final JsonNode pFallback)
	{
		if (pPair.enginePath.isEmpty())
		{
			return pFallback;
		}
		final ReviewDecisionTree.Plan plan = ReviewDecisionTree.readFrom(pRequirements);
		final PathMatch match = MatchSimulationBlock.matchEnginePath(plan, pPair.enginePath);
		for (final ReviewDecisionTree.Node node : match.nodes)
		{
			if (ReviewDecisionTree.FUNCTION_AVERAGE.equals(node.function))
			{
				return MatchSimulationBlock.toTierJson(node);
			}
		}
		return pFallback;
	}
}
