package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Section 4 "Survivance" of the Matching review line's expanded body (survivorship lot, 2026-09-22,
 * `docs/MATCHING.md` &sect;15) - who wins when two records of a group merge, field by field. The validated
 * mockup is {@code docs/mockups/review-match-plan-survivorship.html}; read it before touching this file.
 * <p>
 * <b>Pure, package-private, unit-tested without EBX</b> - the same discipline every other block class of
 * this package follows ({@link MatchPlanTreeDiagram}, {@link MatchSimulationBlock}): only {@code java.*}/
 * Jackson/{@code review.*}. Everything this class cannot derive from {@code MatchPlan.requirements}/{@code
 * evidence} alone (the wizard's trusted-source ranking and descriptions, the entity's structurally-
 * identified source-key attribute(s), its derived matching-key fields) is given explicitly through {@link
 * Input} - the same "give it as data, never re-derive it" discipline {@link MatchPlanView.Input} already
 * applies to {@code autoMerge}/{@code canSimulate}.
 * <p>
 * <b>What is shown, and why a field with no rule is simply absent</b>: a business row exists ONLY for a
 * field {@code MatchPlan.requirements.survivorship.fields[]} actually carries (task brief: "a field with no
 * rule = policy default" - the policy default is stated ONCE, in the fixed sentence above the table, never
 * repeated per field). The four fixed technical rows (`sourceSystem`, the source-id/source-key/`_loadId`
 * group, `_receivedAt`) and one row per derived matching-key field are always shown, greyed, attributed to
 * the Factory - {@link MatchingPolicyTranslator#buildMergePolicy} always writes them, whatever the plan.
 * <p>
 * <b>"most trusted source" resolves to a real source code when it can</b>: a chain entry {@code "most
 * trusted source"} with no per-field override renders the table's OWN top-ranked source ({@link
 * Input#sources}, index 0 - the same source {@code master.TrustedSourceRanking.order}'s own output puts
 * first); WITH a {@code trustedSourceList} override, it renders that override's own first code instead -
 * both are real, known facts, never a placeholder word.
 * <p>
 * <b>Public (not package-private), since 2026-09-22 18:20</b>: {@code ebx.ProposalReviews
 * #attachSurvivorshipInputs} builds a real {@link Input} (source cards, source-key field names, derived-key
 * fields) from live EBX reads and attaches it to a {@link ReviewLine} from OUTSIDE this package - the same
 * reason {@link MatchPlanView}/{@link ReviewLine} themselves are public. {@link #render}/{@link #emptyInput}
 * stay package-private: only {@link MatchPlanView} (same package) ever calls them.
 * <p>
 * <b>{@link Input#fieldsNotOnMaster}, 2026-09-23 (`docs/MATCHING.md` &sect;26)</b>: a business survivorship
 * field {@code ebx.ProposalReviews#buildSurvivorshipInput} finds is not (yet) a node of the entity's real
 * master table gets a "not on master" warning chip on its row - the same underlying gap {@code
 * master.MasterFieldFilter} silently corrects for the merge policy the add-on actually receives (dropping the
 * survivor rule there), surfaced here as a visible signal instead, before a reviewer even clicks Accept. A
 * caller with no way to check (no master zone yet) passes nothing (the original 4-argument constructor still
 * works, defaulting to "nothing flagged") - never a false positive from an absent check.
 */
public final class MatchSurvivorshipBlock
{
	/** The source a golden record created by a merge carries - mirrors {@code master.MatchingPolicyTranslator#GOLDEN_SOURCE_CODE} (this package never imports {@code master}; a test pins the two together). */
	static final String GOLDEN_SOURCE_CODE = "MDM";

	static final String ORIGIN_LLM = "LLM";
	static final String ORIGIN_FACTORY = "FACTORY";

	private MatchSurvivorshipBlock()
	{
	}

	// ------------------------------------------------------------------------------------------------------- input

	/** One project source, ranked - the review-side mirror of {@code master.TrustedSourceRanking.Source}, JSON/plain-value contract only (see class Javadoc). Public - see the enclosing class' own Javadoc. */
	public static final class SourceCard
	{
		public final String code;
		public final int rank;
		public final String description;
		/** Row count of this source system in the entity's master table - null when not measured. */
		public final Long recordCount;

		public SourceCard(final String pCode, final int pRank, final String pDescription, final Long pRecordCount)
		{
			this.code = pCode;
			this.rank = pRank;
			this.description = pDescription;
			this.recordCount = pRecordCount;
		}
	}

	/** One derived matching-key field (2026-09-22 matching-key contract) shown as its own technical row - e.g. {@code cityKey}, following {@code city}. Public - see the enclosing class' own Javadoc. */
	public static final class DerivedKeyField
	{
		public final String key;
		public final String source;

		public DerivedKeyField(final String pKey, final String pSource)
		{
			this.key = pKey;
			this.source = pSource;
		}
	}

	/** Public - see the enclosing class' own Javadoc (built from live EBX reads by {@code ebx.ProposalReviews#attachSurvivorshipInputs}, outside this package). */
	public static final class Input
	{
		/** Most trusted first (index 0 = rank 1) - {@code master.TrustedSourceRanking.order}'s own output, turned into {@link SourceCard}s. Empty when the project has no sources declared yet. */
		public final List<SourceCard> sources;
		/** {@code MatchPlan.requirements.mergePolicy.recordSelection} - the project's own fallback hint tried AFTER "most trusted source" (never "mostTrustedSource" itself here: {@code master.MatchingPolicyTranslator} collapses the chain to one entry in that case, and this block then shows only the trusted-source sentence, no fallback). {@code "mostCompleted"}/{@code "lastUpdate"}, or null. */
		public final String recordSelectionFallback;
		/** The entity's own structurally-identified source-key business attribute(s) (e.g. {@code supplierId}) - {@code ebx.MatchingConfigurator#readSourceKeyFieldNames}'s own output, never a name guess. */
		public final List<String> sourceKeyFieldNames;
		/** One entry per derived matching-key field of the entity. */
		public final List<DerivedKeyField> derivedKeyFields;
		/**
		 * master-field-filter lot (2026-09-23, `docs/MATCHING.md` &sect;26): bare (slash-less) field names of
		 * {@code MatchPlan.requirements.survivorship.fields[]} that {@code ebx.ProposalReviews
		 * #buildSurvivorshipInput} found are NOT (yet) a node of the entity's own master table - the SAME gap
		 * {@code master.MasterFieldFilter} fixes for the merge policy the add-on actually receives, surfaced here
		 * too so a data steward sees it before deciding, not only after a failed {@code matching/configure} call.
		 * Empty when every business rule's field already resolves, or when the caller could not check (no master
		 * zone yet, {@link #emptyInput}).
		 */
		public final Set<String> fieldsNotOnMaster;

		public Input(final List<SourceCard> pSources, final String pRecordSelectionFallback, final List<String> pSourceKeyFieldNames,
			final List<DerivedKeyField> pDerivedKeyFields)
		{
			this(pSources, pRecordSelectionFallback, pSourceKeyFieldNames, pDerivedKeyFields, null);
		}

		public Input(final List<SourceCard> pSources, final String pRecordSelectionFallback, final List<String> pSourceKeyFieldNames,
			final List<DerivedKeyField> pDerivedKeyFields, final Set<String> pFieldsNotOnMaster)
		{
			this.sources = pSources == null ? new ArrayList<SourceCard>() : pSources;
			this.recordSelectionFallback = pRecordSelectionFallback;
			this.sourceKeyFieldNames = pSourceKeyFieldNames == null ? new ArrayList<String>() : pSourceKeyFieldNames;
			this.derivedKeyFields = pDerivedKeyFields == null ? new ArrayList<DerivedKeyField>() : pDerivedKeyFields;
			this.fieldsNotOnMaster = pFieldsNotOnMaster == null ? Collections.<String> emptySet() : pFieldsNotOnMaster;
		}
	}

	/** No sources, no source-key fields, no derived keys - for a caller with nothing to give (every route/test predating this lot). Still renders the fixed sentences and the technical rows. */
	static Input emptyInput()
	{
		return new Input(null, null, null, null);
	}

	// ------------------------------------------------------------------------------------------------------ render

	static void render(final StringBuilder pOut, final JsonNode pRequirements, final List<MatchPlanFieldEvidence> pEvidence, final Input pInput, final Locale pLocale)
	{
		final Map<String, MatchPlanFieldEvidence> evidenceByField = new LinkedHashMap<String, MatchPlanFieldEvidence>();
		for (final MatchPlanFieldEvidence field : pEvidence)
		{
			evidenceByField.put(field.field, field);
		}
		final Map<String, Long> rowCountBySystem = new LinkedHashMap<String, Long>();
		for (final SourceCard source : pInput.sources)
		{
			if (source.recordCount != null)
			{
				rowCountBySystem.put(source.code, source.recordCount);
			}
		}

		pOut.append("<div class=\"mdmf-match-block mdmf-match-block-full\"><h4>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.title")))
			.append(" <span class=\"mdmf-chip mdmf-chip-target\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.afterAccept")))
			.append("</span></h4>");
		pOut.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.lead"))).append("</p>");

		MatchSurvivorshipBlock.renderSources(pOut, pInput.sources, pLocale);
		MatchSurvivorshipBlock.renderSentences(pOut, pInput, pLocale);

		final List<JsonNode> businessRules = MatchSurvivorshipBlock.businessRules(pRequirements);
		pOut.append("<table class=\"mdmf-table\"><tr><th>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.col.field"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.col.rule"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.col.why"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.col.proposedBy"))).append("</th></tr>");
		for (final JsonNode rule : businessRules)
		{
			MatchSurvivorshipBlock.renderBusinessRow(pOut, rule, evidenceByField.get(rule.path("field").asText(null)), rowCountBySystem, pInput.sources,
				pInput.fieldsNotOnMaster, pLocale);
		}
		MatchSurvivorshipBlock.renderTechnicalRows(pOut, pInput, pLocale);
		pOut.append("</table>");

		if (businessRules.isEmpty())
		{
			// Coordinator ask, 2026-09-22: a plan that predates this lot entirely (no evidence was ever written for
			// it - MatchPlanFieldEvidence's own Javadoc: null "for a MATCH plan written before this field existed")
			// says so explicitly, distinct from a plan the current pass DID consider and simply proposed nothing
			// field-specific for (evidence exists, e.g. matching-side ANCHOR_FUZZY/CLUSTERING entries, even with no
			// SURVIVORSHIP-tagged one). Not a perfect signal - a plan run between the review-match-plan and
			// survivorship lots also has non-empty evidence with nothing survivorship-specific in it, and would
			// read as "considered" here even though this lot itself never ran for it - but it is the only signal
			// actually available without a schema change, and the ONLY message renderable with data this pure
			// class actually has.
			pOut.append("<p class=\"mdmf-hint\">")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, pEvidence.isEmpty() ? "review.match.survivorship.notAvailable" : "review.match.survivorship.none")))
				.append("</p>");
		}
		pOut.append("<div class=\"mdmf-sim-note\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.decideNote"))).append("</div>");
		pOut.append("</div>");
	}

	private static List<JsonNode> businessRules(final JsonNode pRequirements)
	{
		final List<JsonNode> out = new ArrayList<JsonNode>();
		for (final JsonNode rule : pRequirements.path("survivorship").path("fields"))
		{
			out.add(rule);
		}
		return out;
	}

	// --------------------------------------------------------------------------------------------- sources / sentences

	private static void renderSources(final StringBuilder pOut, final List<SourceCard> pSources, final Locale pLocale)
	{
		if (pSources.isEmpty())
		{
			return;
		}
		pOut.append("<div class=\"mdmf-surv-sources\">");
		for (final SourceCard source : pSources)
		{
			pOut.append("<span class=\"mdmf-surv-src\"><span class=\"mdmf-surv-src-rank\">").append(source.rank).append("</span><span>")
				.append("<b>").append(ReviewPage.esc(source.code)).append("</b> <span class=\"mdmf-chip mdmf-chip-real\">")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.source.rank", Integer.valueOf(source.rank)))).append("</span>");
			if (source.description != null && !source.description.isEmpty())
			{
				pOut.append("<br><span class=\"mdmf-surv-src-d\">").append(ReviewPage.esc(source.description));
				if (source.recordCount != null)
				{
					pOut.append(" &middot; ").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.source.records", source.recordCount)));
				}
				pOut.append("</span>");
			}
			pOut.append("</span></span>");
		}
		pOut.append("<span class=\"mdmf-surv-src-note\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.sourcesNote"))).append("</span>");
		pOut.append("</div>");
	}

	private static void renderSentences(final StringBuilder pOut, final Input pInput, final Locale pLocale)
	{
		final String fallbackWords = MatchSurvivorshipBlock.recordSelectionFallbackWords(pInput.recordSelectionFallback, pLocale);
		final String survivorSentence = fallbackWords == null ? ReviewMessages.get(pLocale, "review.match.survivorship.survivorSentence.trustedOnly")
			: ReviewMessages.get(pLocale, "review.match.survivorship.survivorSentence", fallbackWords);
		pOut.append("<p class=\"mdmf-hint mdmf-surv-sentence\"><b>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.survivorLabel")))
			.append("</b> : ").append(ReviewPage.esc(survivorSentence)).append(" <span class=\"mdmf-chip mdmf-chip-real\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.fromWizard"))).append("</span> &middot; <b>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.defaultRuleLabel"))).append("</b> : ")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.defaultFieldRule"))).append(" <span class=\"mdmf-chip mdmf-chip-real\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.fromWizard"))).append("</span></p>");
	}

	private static String recordSelectionFallbackWords(final String pFallback, final Locale pLocale)
	{
		if ("mostCompleted".equals(pFallback))
		{
			return ReviewMessages.get(pLocale, "review.match.survivorship.recordSelection.mostCompleted");
		}
		if ("lastUpdate".equals(pFallback))
		{
			return ReviewMessages.get(pLocale, "review.match.survivorship.recordSelection.lastUpdate");
		}
		return null;
	}

	/**
	 * One-line "which value wins" summary - wizard step 6 lot (2026-09-24), board {@code
	 * docs/mockups/wizard-flow/MatchingPlan.dc.html} ("SAP first (trust order), except email and phone: most
	 * recent"). Reuses the SAME top-ranked source and fallback-function words {@link #renderSentences} already
	 * computes for the review screen's full survivorship block - never a second phrasing of the trust-order
	 * rule - then adds, when the plan's own {@code survivorship.fields[]} carries at least one field whose
	 * FIRST chain entry is not {@code "most trusted source"}, a short "except &lt;field(s)&gt;: &lt;rule&gt;"
	 * clause naming the first such field(s) sharing that rule (never a count alone when the actual field names
	 * are available - real data, never invented). Public: {@code review.MatchPlanView} (same package) and
	 * {@code wizard.WizardPage} (a different package, step 6's inline plan card) both call it.
	 */
	public static String survivorSummary(final JsonNode pRequirements, final Input pInput, final Locale pLocale)
	{
		final String base = pInput.sources.isEmpty() ? ReviewMessages.get(pLocale, "review.match.survivorship.survivorSentence.trustedOnly")
			: ReviewMessages.get(pLocale, "wizard.step6.plan.survivorship.base", pInput.sources.get(0).code);
		final List<JsonNode> rules = MatchSurvivorshipBlock.businessRules(pRequirements);
		String exceptionFunction = null;
		final List<String> exceptionFields = new ArrayList<String>();
		for (final JsonNode rule : rules)
		{
			final JsonNode chain = rule.path("chain");
			if (chain.size() == 0)
			{
				continue;
			}
			final String function = chain.get(0).path("function").asText("");
			if ("most trusted source".equals(function))
			{
				continue;
			}
			final String words = MatchSurvivorshipBlock.functionWords(function, chain.get(0), pInput.sources, pLocale);
			if (exceptionFunction == null)
			{
				exceptionFunction = words;
			}
			if (words.equals(exceptionFunction) && exceptionFields.size() < 2)
			{
				exceptionFields.add(rule.path("field").asText(""));
			}
		}
		if (exceptionFunction == null || exceptionFields.isEmpty())
		{
			return base;
		}
		final String fieldList = FieldWords.humanizeAll(exceptionFields, ReviewMessages.get(pLocale, "review.match.tree.clustering.and"));
		return ReviewMessages.get(pLocale, "wizard.step6.plan.survivorship.exception", base, fieldList, exceptionFunction);
	}

	// --------------------------------------------------------------------------------------------------- table rows

	private static void renderBusinessRow(final StringBuilder pOut, final JsonNode pRule, final MatchPlanFieldEvidence pEvidence, final Map<String, Long> pRowCountBySystem,
		final List<SourceCard> pSources, final Set<String> pFieldsNotOnMaster, final Locale pLocale)
	{
		final String field = pRule.path("field").asText("");
		final String confidence = pRule.path("confidence").asText(null);
		pOut.append("<tr><td class=\"k\">").append(ReviewPage.esc(field));
		// master-field-filter lot (2026-09-23, docs/MATCHING.md sec 26): the same "not on the master table yet" gap
		// master.MasterFieldFilter fixes for the merge policy the add-on actually receives, shown here too so a data
		// steward sees it before deciding rather than only after a failed matching/configure call.
		if (pFieldsNotOnMaster.contains(field))
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-warn\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.notOnMaster")))
				.append("</span>");
		}
		pOut.append("</td><td class=\"mdmf-surv-rule\">");
		MatchSurvivorshipBlock.renderChain(pOut, pRule.path("chain"), pSources, pLocale);
		pOut.append("</td><td>");
		MatchSurvivorshipBlock.renderWhy(pOut, pEvidence, pRule.path("rationale").asText(""), pRowCountBySystem);
		pOut.append("</td><td>");
		MatchSurvivorshipBlock.renderProposedBy(pOut, MatchSurvivorshipBlock.ORIGIN_LLM, confidence, pLocale);
		pOut.append("</td></tr>");
	}

	private static void renderChain(final StringBuilder pOut, final JsonNode pChain, final List<SourceCard> pSources, final Locale pLocale)
	{
		boolean first = true;
		for (final JsonNode entry : pChain)
		{
			final String function = entry.path("function").asText("");
			final String words = MatchSurvivorshipBlock.functionWords(function, entry, pSources, pLocale);
			if (first)
			{
				pOut.append("<span class=\"rule\">").append(ReviewPage.esc(words)).append("</span>");
			}
			else
			{
				pOut.append(" <span class=\"then\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.then"))).append(" ")
					.append(ReviewPage.esc(words)).append("</span>");
			}
			first = false;
		}
	}

	/** {@code "most trusted source"} resolves to a real source code when one is known - see class Javadoc. Every other function renders its fixed words. */
	private static String functionWords(final String pFunction, final JsonNode pChainEntry, final List<SourceCard> pSources, final Locale pLocale)
	{
		if ("most trusted source".equals(pFunction))
		{
			final JsonNode overrideList = pChainEntry.path("trustedSourceList");
			if (overrideList.isArray() && overrideList.size() > 0)
			{
				return overrideList.get(0).asText();
			}
			if (!pSources.isEmpty())
			{
				return pSources.get(0).code;
			}
			return ReviewMessages.get(pLocale, "review.match.survivorship.function.mostTrustedSource");
		}
		if ("most frequent".equals(pFunction))
		{
			return ReviewMessages.get(pLocale, "review.match.survivorship.function.mostFrequent");
		}
		if ("longest".equals(pFunction))
		{
			return ReviewMessages.get(pLocale, "review.match.survivorship.function.longest");
		}
		if ("last update".equals(pFunction))
		{
			return ReviewMessages.get(pLocale, "review.match.survivorship.function.lastUpdate");
		}
		if ("max".equals(pFunction))
		{
			return ReviewMessages.get(pLocale, "review.match.survivorship.function.max");
		}
		if ("min".equals(pFunction))
		{
			return ReviewMessages.get(pLocale, "review.match.survivorship.function.min");
		}
		if ("constant".equals(pFunction))
		{
			return ReviewMessages.get(pLocale, "review.match.survivorship.function.constant", pChainEntry.path("constantValue").asText(""));
		}
		if ("no merge".equals(pFunction))
		{
			return ReviewMessages.get(pLocale, "review.match.survivorship.function.noMerge");
		}
		return pFunction;
	}

	private static void renderWhy(final StringBuilder pOut, final MatchPlanFieldEvidence pEvidence, final String pRationale, final Map<String, Long> pRowCountBySystem)
	{
		if (pEvidence != null && pEvidence.filledBySystem != null)
		{
			for (final Map.Entry<String, Long> entry : pEvidence.filledBySystem.entrySet())
			{
				final Long total = pRowCountBySystem.get(entry.getKey());
				if (total == null || total.longValue() <= 0)
				{
					continue;
				}
				final long filled = entry.getValue() == null ? 0L : entry.getValue().longValue();
				final int percent = Math.max(0, Math.min(100, (int) Math.round(filled * 100.0 / total.longValue())));
				pOut.append("<span class=\"fill\"><span class=\"mini mdmf-mini-bar\"><i style=\"width:").append(percent).append("%\"></i></span>")
					.append(ReviewPage.esc(entry.getKey())).append(' ').append(percent).append("%</span> ");
			}
		}
		// Lot 1 (2026-09-23, quality-informed survivorship, docs/MATCHING.md sec 23): a second mini-bar, "valid
		// x/y", right next to the fill bar - same .mdmf-mini-bar visual, a distinct ".valid" wrapper class so it
		// can be told apart at a glance (factory.css). Only when the field actually carries a measured validity
		// rule (pEvidence.validBySystem non-null) - most fields render nothing here, exactly like the fill bar
		// renders nothing for a single-source entity.
		if (pEvidence != null && pEvidence.validBySystem != null)
		{
			for (final Map.Entry<String, MatchPlanFieldEvidence.Validity> entry : pEvidence.validBySystem.entrySet())
			{
				final MatchPlanFieldEvidence.Validity validity = entry.getValue();
				if (validity == null || validity.checked <= 0)
				{
					continue;
				}
				final int percent = Math.max(0, Math.min(100, (int) Math.round(validity.valid * 100.0 / validity.checked)));
				pOut.append("<span class=\"valid\"><span class=\"mini mdmf-mini-bar\"><i style=\"width:").append(percent).append("%\"></i></span>")
					.append(ReviewPage.esc(entry.getKey())).append(' ').append(validity.valid).append('/').append(validity.checked).append("</span> ");
			}
		}
		pOut.append(ReviewPage.esc(pRationale));
	}

	private static void renderProposedBy(final StringBuilder pOut, final String pOrigin, final String pConfidence, final Locale pLocale)
	{
		if (MatchSurvivorshipBlock.ORIGIN_LLM.equals(pOrigin))
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-accent\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.proposedBy.llm")))
				.append("</span>");
		}
		else
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-muted\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.proposedBy.factory")))
				.append("</span>");
		}
		if ("low".equals(pConfidence))
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-warn\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.survivorship.toVerify")))
				.append("</span>");
		}
	}

	// ----------------------------------------------------------------------------------------------- technical rows

	private static void renderTechnicalRows(final StringBuilder pOut, final Input pInput, final Locale pLocale)
	{
		// lot M1 (2026-10-17): a record keeps its own source (no merge); only a golden the add-on creates carries the golden source (autoCreateNewGoldenSource) -
		// never a constant survivor, which the add-on applied to singleton records in place
		MatchSurvivorshipBlock.renderTechnicalRow(pOut, "sourceSystem",
			ReviewMessages.get(pLocale, "review.match.survivorship.function.noMerge"),
			ReviewMessages.get(pLocale, "review.match.survivorship.tech.sourceSystem", MatchSurvivorshipBlock.GOLDEN_SOURCE_CODE), pLocale);
		final StringBuilder sourceIdGroup = new StringBuilder("sourceId");
		for (final String sourceKey : pInput.sourceKeyFieldNames)
		{
			sourceIdGroup.append(" · ").append(sourceKey);
		}
		sourceIdGroup.append(" · _loadId");
		MatchSurvivorshipBlock.renderTechnicalRow(pOut, sourceIdGroup.toString(), ReviewMessages.get(pLocale, "review.match.survivorship.function.noMerge"),
			ReviewMessages.get(pLocale, "review.match.survivorship.tech.sourceKeys"), pLocale);
		MatchSurvivorshipBlock.renderTechnicalRow(pOut, "_receivedAt", ReviewMessages.get(pLocale, "review.match.survivorship.function.max"),
			ReviewMessages.get(pLocale, "review.match.survivorship.tech.receivedAt"), pLocale);
		for (final DerivedKeyField derived : pInput.derivedKeyFields)
		{
			MatchSurvivorshipBlock.renderTechnicalRow(pOut, derived.key, ReviewMessages.get(pLocale, "review.match.survivorship.follows", derived.source),
				ReviewMessages.get(pLocale, "review.match.survivorship.tech.derivedKey"), pLocale);
		}
	}

	private static void renderTechnicalRow(final StringBuilder pOut, final String pField, final String pRule, final String pWhy, final Locale pLocale)
	{
		pOut.append("<tr class=\"mdmf-surv-tech\"><td class=\"k\">").append(ReviewPage.esc(pField)).append("</td><td><span class=\"rule\">").append(ReviewPage.esc(pRule))
			.append("</span></td><td>").append(ReviewPage.esc(pWhy)).append("</td><td>");
		MatchSurvivorshipBlock.renderProposedBy(pOut, MatchSurvivorshipBlock.ORIGIN_FACTORY, null, pLocale);
		pOut.append("</td></tr>");
	}
}
