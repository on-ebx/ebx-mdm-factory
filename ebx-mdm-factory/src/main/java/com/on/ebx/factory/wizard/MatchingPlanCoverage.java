package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.master.ChildEntityMatching;
import com.on.ebx.factory.staging.TechnicalKeyRule;

/**
 * UX lot U1 (2026-10-13, "Matching step", RAPPORT 2.11 / 3.1): which accepted business objects have NO matching plan after a proposal pass, and why.
 * <p>
 * Seen live on project UxCrmSf (3 accepted objects): the rule pass answered "0 plans written, 3 skipped" and the page kept "No matching plan proposed yet",
 * then the language-model pass wrote 2 plans and Organisation - the main object of a CRM reconciliation - got no card, no line, no reason. Every reason
 * existed (the rules' {@code skipped[]}, the model's own "X skipped: ..." note) but was never shown. This class is the pure rule that turns "accepted
 * objects" + "objects that have a plan" + "what the model said" into one {@link Gap} per object left without a plan, written in the person's words.
 * <p>
 * Where the model's words come from: the {@code MATCHING_SUGGEST_LLM} step keeps the pass's whole result in its detail ({@code notes[]} holds each
 * {@code skip_entity} as "Entity skipped: reason", {@code refused{}} the plans that did not fit their field). The rule pass keeps nothing (its only reason
 * for skipping an object is the absence of a validated identifier), so a gap seen with no model run is the rules' own.
 * <p>
 * Pure: no EBX class, unit-tested ({@code MatchingPlanCoverageTest}).
 */
public final class MatchingPlanCoverage
{
	/** The first words of the message of a proposal job that wrote nothing - the convention shared by the UX lots ("Recent tasks" shows such a task in amber). Not localized on purpose: it is what the banner recognises. */
	public static final String NOTHING_TO_DO = "Nothing to do:";

	/** The rules found no validated identifier on this object. */
	public static final String KIND_RULES = "RULES";
	/** The language model skipped this object and said why ({@link Gap#detail}). */
	public static final String KIND_MODEL_SKIPPED = "MODEL_SKIPPED";
	/** The language model ran and neither proposed nor skipped this object. */
	public static final String KIND_MODEL_SILENT = "MODEL_SILENT";
	/** The language model proposed a plan that could not be stored ({@link Gap#detail} = why). */
	public static final String KIND_MODEL_REFUSED = "MODEL_REFUSED";

	/**
	 * Lot M5 (2026-10-18): the proposal of this object ended on the run's token budget after some attempts, every one refused ({@link Gap#detail} = the last refusal,
	 * {@link Gap#attempts} = how many). Before the lot such an object read "proposed nothing and gave no reason".
	 */
	public static final String KIND_MODEL_BUDGET = "MODEL_BUDGET";

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String SKIPPED_MARK = " skipped:";

	private MatchingPlanCoverage()
	{
	}

	/** One accepted business object that has no matching plan. */
	public static final class Gap
	{
		public final String entity;
		public final String kind;
		/** The model's own text (skip reason, refusal) or empty. */
		public final String detail;
		/** Proposals the model made for the object before it ended without a plan (lot M5); 0 when not known. */
		public final int attempts;
		/** Lot M9: why the Factory's fallback plan was refused too; empty when none was tried. */
		public final String fallbackRefusal;

		public Gap(final String pEntity, final String pKind, final String pDetail)
		{
			this(pEntity, pKind, pDetail, 0);
		}

		public Gap(final String pEntity, final String pKind, final String pDetail, final int pAttempts)
		{
			this(pEntity, pKind, pDetail, pAttempts, null);
		}

		public Gap(final String pEntity, final String pKind, final String pDetail, final int pAttempts, final String pFallbackRefusal)
		{
			this.entity = pEntity;
			this.kind = pKind;
			this.detail = pDetail == null ? "" : pDetail.trim();
			this.attempts = pAttempts;
			this.fallbackRefusal = pFallbackRefusal == null ? "" : pFallbackRefusal.trim();
		}
	}

	/** Lot M5: what the model's proposals for one object met, as the language-model step stored it ({@code attempts{}} of the result). */
	public static final class Attempt
	{
		public final int count;
		public final String lastRefusal;
		/** Non-empty when the pass itself ended the proposal (the same refusal again, too many proposals): the object is "refused". */
		public final String endedBy;
		/** Lot M9: why the Factory's fallback plan was refused too ({@code attempts{}.fallback} of the result); empty when none was tried or it was proposed. */
		public final String fallbackRefusal;

		public Attempt(final int pCount, final String pLastRefusal, final String pEndedBy)
		{
			this(pCount, pLastRefusal, pEndedBy, null);
		}

		public Attempt(final int pCount, final String pLastRefusal, final String pEndedBy, final String pFallbackRefusal)
		{
			this.count = pCount;
			this.lastRefusal = pLastRefusal == null ? "" : pLastRefusal;
			this.endedBy = pEndedBy == null ? "" : pEndedBy;
			this.fallbackRefusal = pFallbackRefusal == null ? "" : pFallbackRefusal;
		}
	}

	/**
	 * Lot M9 (2026-10-18, {@code docs/MATCHING.md} &sect;63): an object whose plan is the Factory's FALLBACK - the language model proposed for it, every proposal was refused, and the
	 * Factory built a plan from the measured key facts ({@code master.MatchPlanFallback}). Such an object HAS a plan (never a gap); the job message and the card say so.
	 */
	public static final class FallbackInfo
	{
		public final String entity;
		/** Proposals the model made before the fallback. */
		public final int attempts;
		/** The last refusal the model read, as the Factory stored it. */
		public final String lastRefusal;

		public FallbackInfo(final String pEntity, final int pAttempts, final String pLastRefusal)
		{
			this.entity = pEntity == null ? "" : pEntity;
			this.attempts = pAttempts;
			this.lastRefusal = pLastRefusal == null ? "" : pLastRefusal;
		}
	}

	/** Lot M5: the proposals' ledger of a language-model run plus whether the run ended on its token budget. */
	public static final class ModelAttempts
	{
		public final Map<String, Attempt> byEntity;
		public final boolean budgetEnded;

		public ModelAttempts(final Map<String, Attempt> pByEntity, final boolean pBudgetEnded)
		{
			this.byEntity = pByEntity == null ? new LinkedHashMap<String, Attempt>() : pByEntity;
			this.budgetEnded = pBudgetEnded;
		}
	}

	/**
	 * The accepted business objects that need a plan: accepted or applied, not the member of another (canonical) object - a member is never promoted to the master - and, since lot
	 * M2 (2026-10-17), not a child that follows its parent ({@link #followers}).
	 */
	public static List<String> candidates(final WizardSnapshot pSnapshot)
	{
		final Map<String, List<String>> followers = MatchingPlanCoverage.parentsByChild(pSnapshot);
		final List<String> out = new ArrayList<String>();
		for (final String name : MatchingPlanCoverage.promoted(pSnapshot))
		{
			if (!followers.containsKey(name))
			{
				out.add(name);
			}
		}
		return out;
	}

	private static List<String> promoted(final WizardSnapshot pSnapshot)
	{
		final List<String> out = new ArrayList<String>();
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if (("ACCEPTED".equals(entity.status) || "APPLIED".equals(entity.status)) && entity.supersededBy.isEmpty())
			{
				out.add(entity.name);
			}
		}
		return out;
	}

	/** child entity -&gt; its parents, from the snapshot's accepted attributes (same rule as {@link ChildEntityMatching#parentsByChild(Collection, Map, Map)}). */
	private static Map<String, List<String>> parentsByChild(final WizardSnapshot pSnapshot)
	{
		final Map<String, String> canonicalOfMember = new LinkedHashMap<String, String>();
		final Map<String, List<String>> references = new LinkedHashMap<String, List<String>>();
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if (!entity.supersededBy.isEmpty())
			{
				canonicalOfMember.put(entity.name, entity.supersededBy);
			}
			final List<String> referenced = new ArrayList<String>();
			for (final WizardSnapshot.AttributeInfo attribute : entity.attributes)
			{
				if (!attribute.primaryKey && attribute.referenceEntityName != null && !attribute.referenceEntityName.isEmpty()
					&& TechnicalKeyRule.isAcceptedLike(attribute.status))
				{
					referenced.add(attribute.referenceEntityName);
				}
			}
			references.put(entity.name, referenced);
		}
		return ChildEntityMatching.parentsByChild(MatchingPlanCoverage.promoted(pSnapshot), references, canonicalOfMember);
	}

	/** An accepted object that follows its parent(s): no matching plan of its own (lot M2). */
	public static final class Follower
	{
		public final String entity;
		public final List<String> parents;

		public Follower(final String pEntity, final List<String> pParents)
		{
			this.entity = pEntity;
			this.parents = pParents;
		}
	}

	/** The accepted objects that follow their parent, in the staging's order - whether or not an own plan row (an earlier pass wrote one) exists. */
	public static List<Follower> followers(final WizardSnapshot pSnapshot)
	{
		final Map<String, List<String>> parentsByChild = MatchingPlanCoverage.parentsByChild(pSnapshot);
		final List<Follower> out = new ArrayList<Follower>();
		for (final String name : MatchingPlanCoverage.promoted(pSnapshot))
		{
			final List<String> parents = parentsByChild.get(name);
			if (parents != null)
			{
				out.add(new Follower(name, parents));
			}
		}
		return out;
	}

	/** {@code pBase} followed by one sentence per follower that has no plan row ("Adresse follows Personne: ..."); unchanged when none. */
	public static String withFollows(final Locale pLocale, final String pBase, final List<Follower> pFollowers, final Set<String> pWithPlan)
	{
		final StringBuilder out = new StringBuilder(pBase);
		for (final Follower follower : pFollowers)
		{
			if (pWithPlan.contains(follower.entity))
			{
				continue;
			}
			out.append(' ').append(WizardMessages.get(pLocale, "wizard.matching.follows.sentence", follower.entity, String.join(", ", follower.parents)));
		}
		return out.toString();
	}

	/** The names of the objects that have a plan, whatever its status (a rejected plan is a decision, not a gap). */
	public static Set<String> withPlan(final WizardSnapshot pSnapshot)
	{
		final Set<String> out = new HashSet<String>();
		for (final WizardSnapshot.MatchPlanInfo plan : pSnapshot.matchPlans)
		{
			out.add(plan.entityName);
		}
		return out;
	}

	/** The latest language-model proposal step of the project, or null when it never ran. */
	public static WizardSnapshot.PipelineStepInfo modelStep(final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.PipelineStepInfo step : pSnapshot.projectSteps)
		{
			if ("MATCHING_SUGGEST_LLM".equals(step.step))
			{
				return step;
			}
		}
		return null;
	}

	/** Whether the rule pass was run on this project: a plan of origin RULE exists, or "Recent tasks" lists a finished rule proposal. */
	public static boolean rulesRan(final WizardSnapshot pSnapshot, final List<RecentTasks.Task> pRecentTasks)
	{
		for (final WizardSnapshot.MatchPlanInfo plan : pSnapshot.matchPlans)
		{
			// lot M9: the Factory's fallback plan is stored with origin RULE but comes from the language-model pass, never from the rule pass
			if ("RULE".equals(plan.origin) && MatchingPlanCoverage.fallbackOf(plan.requirements) == null)
			{
				return true;
			}
		}
		if (pRecentTasks != null)
		{
			for (final RecentTasks.Task task : pRecentTasks)
			{
				if ("MATCHING_SUGGEST_RULES".equals(task.kind) && "DONE".equals(task.status))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * One {@link Gap} per candidate that has no plan, in the candidates' order. With {@code pModelRan} false the reason is the rules'; once the model ran its own
	 * skip note wins, then a refusal, then "said nothing".
	 *
	 * @param pModelNotes the model run's {@code notes[]} ("Entity skipped: reason" lines among them)
	 * @param pModelRefused entity -&gt; why its plan was not stored
	 */
	public static List<Gap> gaps(final List<String> pCandidates, final Set<String> pWithPlan, final boolean pModelRan, final List<String> pModelNotes,
		final Map<String, String> pModelRefused)
	{
		return MatchingPlanCoverage.gaps(pCandidates, pWithPlan, pModelRan, pModelNotes, pModelRefused, null);
	}

	/**
	 * Lot M5: as {@link #gaps(List, Set, boolean, List, Map)}, with the ledger of the model's attempts. Order of the reasons, first match wins: the pass ended the proposal (the
	 * last refusal), the model's own skip, a plan that did not fit, then an object proposed for and refused with the run ended on its budget ({@link #KIND_MODEL_BUDGET}) or by the
	 * model giving up ({@link #KIND_MODEL_REFUSED}, last refusal) - and only an object never proposed for at all is "silent".
	 */
	public static List<Gap> gaps(final List<String> pCandidates, final Set<String> pWithPlan, final boolean pModelRan, final List<String> pModelNotes,
		final Map<String, String> pModelRefused, final ModelAttempts pAttempts)
	{
		return MatchingPlanCoverage.gaps(pCandidates, pWithPlan, pModelRan, pModelNotes, pModelRefused, pAttempts, null);
	}

	/**
	 * Lot M17 (2026-10-18, {@code docs/MATCHING.md} &sect;69): as the lot M5 form, and an object the facts mark as needing no plan ({@code pNoPlan}, see {@link #noPlanNeeded(JsonNode)}) is
	 * never a gap: whatever the model did with it (skipped it, said nothing, ran out of budget on it), it is a measured fact, worded by {@link #noPlans} and {@link #noPlanReason}.
	 */
	public static List<Gap> gaps(final List<String> pCandidates, final Set<String> pWithPlan, final boolean pModelRan, final List<String> pModelNotes,
		final Map<String, String> pModelRefused, final ModelAttempts pAttempts, final Map<String, String> pNoPlan)
	{
		final List<Gap> out = new ArrayList<Gap>();
		for (final String entity : pCandidates)
		{
			if (pWithPlan.contains(entity) || pNoPlan != null && pNoPlan.containsKey(entity))
			{
				continue;
			}
			if (!pModelRan)
			{
				out.add(new Gap(entity, MatchingPlanCoverage.KIND_RULES, ""));
				continue;
			}
			final Attempt attempt = pAttempts == null ? null : pAttempts.byEntity.get(entity);
			if (attempt != null && !attempt.endedBy.isEmpty() && !attempt.lastRefusal.isEmpty())
			{
				out.add(new Gap(entity, MatchingPlanCoverage.KIND_MODEL_REFUSED, attempt.lastRefusal, attempt.count, attempt.fallbackRefusal));
				continue;
			}
			final String skipped = MatchingPlanCoverage.skipNote(entity, pModelNotes);
			if (skipped != null)
			{
				out.add(new Gap(entity, MatchingPlanCoverage.KIND_MODEL_SKIPPED, skipped));
				continue;
			}
			final String refused = pModelRefused == null ? null : pModelRefused.get(entity);
			if (refused != null && !refused.trim().isEmpty())
			{
				out.add(new Gap(entity, MatchingPlanCoverage.KIND_MODEL_REFUSED, refused));
				continue;
			}
			if (attempt != null && !attempt.lastRefusal.isEmpty())
			{
				out.add(new Gap(entity, pAttempts.budgetEnded ? MatchingPlanCoverage.KIND_MODEL_BUDGET : MatchingPlanCoverage.KIND_MODEL_REFUSED, attempt.lastRefusal, attempt.count,
					attempt.fallbackRefusal));
				continue;
			}
			out.add(new Gap(entity, MatchingPlanCoverage.KIND_MODEL_SILENT, ""));
		}
		return out;
	}

	/** Lot M17: an accepted object without a plan that the facts mark as needing none - one source system feeds it ({@link #source}, "STORES, 48 rows") and no key is shared by two of its rows. */
	public static final class NoPlan
	{
		public final String entity;
		public final String source;

		public NoPlan(final String pEntity, final String pSource)
		{
			this.entity = pEntity;
			this.source = pSource == null ? "" : pSource;
		}
	}

	/** Lot M17: {@code noPlanNeeded{}} of a language-model result (a step's detail JSON): entity -&gt; "STORES, 48 rows"; empty when unreadable or absent (a result written before the lot). */
	public static Map<String, String> noPlanNeeded(final String pDetailJson)
	{
		return MatchingPlanCoverage.noPlanNeeded(MatchingPlanCoverage.parse(pDetailJson));
	}

	/** Lot M17: {@code noPlanNeeded{}} of a language-model result already parsed. */
	public static Map<String, String> noPlanNeeded(final JsonNode pResult)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pResult != null && pResult.path("noPlanNeeded").isObject())
		{
			final Iterator<Map.Entry<String, JsonNode>> fields = pResult.path("noPlanNeeded").fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				out.put(field.getKey(), field.getValue().asText(""));
			}
		}
		return out;
	}

	/** Lot M17: the candidates that have no plan and that the facts mark as needing none, in the candidates' order. */
	public static List<NoPlan> noPlans(final List<String> pCandidates, final Set<String> pWithPlan, final Map<String, String> pNoPlan)
	{
		final List<NoPlan> out = new ArrayList<NoPlan>();
		if (pNoPlan == null)
		{
			return out;
		}
		for (final String entity : pCandidates)
		{
			if (!pWithPlan.contains(entity) && pNoPlan.containsKey(entity))
			{
				out.add(new NoPlan(entity, pNoPlan.get(entity)));
			}
		}
		return out;
	}

	/** Lot M17: "Pas de plan - une seule source, aucune cl&eacute; partag&eacute;e (fait mesur&eacute;)" in the person's words. */
	public static String noPlanReason(final Locale pLocale)
	{
		return WizardMessages.get(pLocale, "wizard.matching.noPlan.reason");
	}

	/** Lot M17: "Entity: reason (source, rows)." for each object, one sentence each. */
	public static String noPlanSentences(final Locale pLocale, final List<NoPlan> pNoPlans)
	{
		final StringBuilder out = new StringBuilder();
		for (final NoPlan noPlan : pNoPlans)
		{
			out.append(out.length() == 0 ? "" : " ").append(WizardMessages.get(pLocale, "wizard.matching.noPlan.sentence", noPlan.entity, MatchingPlanCoverage.noPlanReason(pLocale)));
		}
		return out.toString();
	}

	/** Lot M17: {@code pBase} followed by one sentence per object that needs no plan; unchanged when none. */
	public static String withNoPlan(final Locale pLocale, final String pBase, final List<NoPlan> pNoPlans)
	{
		return pNoPlans.isEmpty() ? pBase : pBase + " " + MatchingPlanCoverage.noPlanSentences(pLocale, pNoPlans);
	}

	/** The ledger of the model's attempts and the budget flag of a language-model result (a step's detail JSON); empty when unreadable. */
	public static ModelAttempts modelAttempts(final String pDetailJson)
	{
		return MatchingPlanCoverage.modelAttempts(MatchingPlanCoverage.parse(pDetailJson));
	}

	/** The ledger of the model's attempts and the budget flag of a language-model result already parsed; the run ended on its budget when {@code status} is BUDGET. */
	public static ModelAttempts modelAttempts(final JsonNode pResult)
	{
		final Map<String, Attempt> byEntity = new LinkedHashMap<String, Attempt>();
		if (pResult != null && pResult.path("attempts").isObject())
		{
			final Iterator<Map.Entry<String, JsonNode>> fields = pResult.path("attempts").fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				byEntity.put(field.getKey(), new Attempt(field.getValue().path("count").asInt(0), field.getValue().path("lastRefusal").asText(""),
					field.getValue().path("endedBy").asText(""), field.getValue().path("fallback").asText("")));
			}
		}
		final boolean budget = pResult != null && ("BUDGET".equals(pResult.path("status").asText("")) || "BUDGET".equals(pResult.path("agentRun").path("status").asText("")));
		return new ModelAttempts(byEntity, budget);
	}

	/** The reason the model gave when it skipped the entity ("Entity skipped: reason"), or null. */
	static String skipNote(final String pEntity, final List<String> pNotes)
	{
		if (pNotes == null)
		{
			return null;
		}
		final String prefix = pEntity + MatchingPlanCoverage.SKIPPED_MARK;
		for (final String note : pNotes)
		{
			if (note != null && note.startsWith(prefix))
			{
				final String reason = note.substring(prefix.length()).trim();
				return reason.isEmpty() ? null : reason;
			}
		}
		return null;
	}

	/** Lot M9: the objects whose plan is the Factory's fallback, as the language-model step stored them ({@code fallback{}} of the result, the entries with {@code proposed} true). */
	public static List<FallbackInfo> fallbacks(final JsonNode pResult)
	{
		final List<FallbackInfo> out = new ArrayList<FallbackInfo>();
		if (pResult != null && pResult.path("fallback").isObject())
		{
			final Iterator<Map.Entry<String, JsonNode>> fields = pResult.path("fallback").fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				if (field.getValue().path("proposed").asBoolean(false))
				{
					out.add(new FallbackInfo(field.getKey(), field.getValue().path("attempts").asInt(0), field.getValue().path("lastRefusal").asText("")));
				}
			}
		}
		return out;
	}

	/** Lot M9: the fallback marker of a stored plan ({@code MatchPlan.requirements}, JSON with a {@code fallback} object), or null for a plan the model or a person wrote. */
	public static FallbackInfo fallbackOf(final String pRequirementsJson)
	{
		final JsonNode requirements = MatchingPlanCoverage.parse(pRequirementsJson);
		if (requirements == null || !requirements.path("fallback").isObject())
		{
			return null;
		}
		final JsonNode fallback = requirements.path("fallback");
		return new FallbackInfo("", fallback.path("afterRefusedProposals").asInt(0), fallback.path("lastRefusal").asText(""));
	}

	/** Lot M9: {@code pBase} followed by one sentence per object that got the Factory's fallback plan ("Client: the language model made 5 proposal(s) and none was accepted; ..."); unchanged when none. */
	public static String withFallbacks(final Locale pLocale, final String pBase, final List<FallbackInfo> pFallbacks)
	{
		final StringBuilder out = new StringBuilder(pBase);
		for (final FallbackInfo fallback : pFallbacks)
		{
			out.append(' ').append(WizardMessages.get(pLocale, "wizard.matching.fallback.sentence", fallback.entity, Integer.valueOf(fallback.attempts), fallback.lastRefusal));
		}
		return out.toString();
	}

	/** The {@code notes[]} of a language-model result (a step's detail JSON); empty when unreadable. */
	public static List<String> modelNotes(final String pDetailJson)
	{
		return MatchingPlanCoverage.modelNotes(MatchingPlanCoverage.parse(pDetailJson));
	}

	/** The {@code notes[]} of a language-model result already parsed (the job holds the result object itself). */
	public static List<String> modelNotes(final JsonNode pResult)
	{
		final List<String> out = new ArrayList<String>();
		final JsonNode root = pResult;
		if (root != null && root.path("notes").isArray())
		{
			for (final JsonNode note : root.path("notes"))
			{
				out.add(note.asText(""));
			}
		}
		return out;
	}

	/** The {@code refused{}} of a language-model result: entity -&gt; reason; empty when unreadable. */
	public static Map<String, String> modelRefused(final String pDetailJson)
	{
		return MatchingPlanCoverage.modelRefused(MatchingPlanCoverage.parse(pDetailJson));
	}

	/** The {@code refused{}} of a language-model result already parsed. */
	public static Map<String, String> modelRefused(final JsonNode pResult)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		final JsonNode root = pResult;
		if (root != null && root.path("refused").isObject())
		{
			final Iterator<Map.Entry<String, JsonNode>> fields = root.path("refused").fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				out.put(field.getKey(), field.getValue().asText(""));
			}
		}
		return out;
	}

	private static JsonNode parse(final String pJson)
	{
		if (pJson == null || pJson.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return MatchingPlanCoverage.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			return null;
		}
	}

	/**
	 * Whether the rule pass must be followed by the language-model pass in the same job: the person may use the model, a connector is configured, and at least
	 * one accepted object got no plan from the rules. Same chaining as step 4's "Propose the staging".
	 */
	public static boolean chainModel(final boolean pAllowed, final boolean pConnectorConfigured, final int pObjectsWithoutPlan)
	{
		return pAllowed && pConnectorConfigured && pObjectsWithoutPlan > 0;
	}

	/** The reason of a gap in the person's words - followed, when the Factory's fallback plan was refused too (lot M9), by that refusal. */
	public static String reasonText(final Locale pLocale, final Gap pGap)
	{
		final String reason = MatchingPlanCoverage.modelReasonText(pLocale, pGap);
		if (pGap.fallbackRefusal.isEmpty())
		{
			return reason;
		}
		return reason + (reason.endsWith(".") ? " " : ". ") + WizardMessages.get(pLocale, "wizard.matching.gap.reason.fallbackRefused", pGap.fallbackRefusal);
	}

	private static String modelReasonText(final Locale pLocale, final Gap pGap)
	{
		if (MatchingPlanCoverage.KIND_MODEL_SKIPPED.equals(pGap.kind))
		{
			return WizardMessages.get(pLocale, "wizard.matching.gap.reason.modelSkipped", pGap.detail);
		}
		if (MatchingPlanCoverage.KIND_MODEL_REFUSED.equals(pGap.kind) && pGap.attempts > 0)
		{
			return WizardMessages.get(pLocale, "wizard.matching.gap.reason.modelRefusedAfter", Integer.valueOf(pGap.attempts), pGap.detail);
		}
		if (MatchingPlanCoverage.KIND_MODEL_REFUSED.equals(pGap.kind))
		{
			return WizardMessages.get(pLocale, "wizard.matching.gap.reason.modelRefused", pGap.detail);
		}
		if (MatchingPlanCoverage.KIND_MODEL_BUDGET.equals(pGap.kind))
		{
			return WizardMessages.get(pLocale, "wizard.matching.gap.reason.modelBudget", Integer.valueOf(pGap.attempts), pGap.detail);
		}
		if (MatchingPlanCoverage.KIND_MODEL_SILENT.equals(pGap.kind))
		{
			return WizardMessages.get(pLocale, "wizard.matching.gap.reason.modelSilent");
		}
		return WizardMessages.get(pLocale, "wizard.matching.gap.reason.rules");
	}

	/** "Entity: reason. Entity: reason." - the objects left without a plan, one sentence each, in the person's words. */
	public static String gapSentences(final Locale pLocale, final List<Gap> pGaps)
	{
		final StringBuilder out = new StringBuilder();
		for (final Gap gap : pGaps)
		{
			final String reason = MatchingPlanCoverage.reasonText(pLocale, gap).trim();
			out.append(out.length() == 0 ? "" : " ").append(gap.entity).append(": ").append(reason).append(reason.endsWith(".") ? "" : ".");
		}
		return out.toString();
	}

	/**
	 * The message of a proposal job that wrote no plan: {@link #NOTHING_TO_DO}, then why. With objects left without a plan, one sentence per object, then (unless
	 * the language model already took part) what to do next; with none, either "no accepted object" or "every object already has a plan".
	 *
	 * @param pCandidates how many accepted objects need a plan
	 * @param pModelTried whether the language model took part in the job
	 * @param pModelAvailable whether a connector is configured (decides the hint)
	 */
	public static String nothingToDo(final Locale pLocale, final int pCandidates, final List<Gap> pGaps, final boolean pModelTried, final boolean pModelAvailable)
	{
		return MatchingPlanCoverage.nothingToDo(pLocale, pCandidates, pGaps, null, pModelTried, pModelAvailable);
	}

	/**
	 * Lot M17: as above; the objects the facts mark as needing no plan ({@code pNoPlans}) are not objects "without a plan" - with no other gap the message says every object that needs a
	 * plan has one, then names them with the measured fact.
	 */
	public static String nothingToDo(final Locale pLocale, final int pCandidates, final List<Gap> pGaps, final List<NoPlan> pNoPlans, final boolean pModelTried,
		final boolean pModelAvailable)
	{
		final StringBuilder out = new StringBuilder(MatchingPlanCoverage.NOTHING_TO_DO).append(' ');
		if (pCandidates == 0)
		{
			return out.append(WizardMessages.get(pLocale, "wizard.matching.nothingToDo.noObjects")).toString();
		}
		if (pGaps.isEmpty())
		{
			if (pNoPlans != null && !pNoPlans.isEmpty())
			{
				return out.append(WizardMessages.get(pLocale, "wizard.matching.nothingToDo.noPlanOnly")).append(' ').append(MatchingPlanCoverage.noPlanSentences(pLocale, pNoPlans)).toString();
			}
			return out.append(WizardMessages.get(pLocale, "wizard.matching.nothingToDo.allCovered")).toString();
		}
		out.append(WizardMessages.get(pLocale, "wizard.matching.nothingToDo.lead")).append(' ').append(MatchingPlanCoverage.gapSentences(pLocale, pGaps));
		if (pNoPlans != null && !pNoPlans.isEmpty())
		{
			out.append(' ').append(MatchingPlanCoverage.noPlanSentences(pLocale, pNoPlans));
		}
		if (!pModelTried)
		{
			out.append(' ').append(WizardMessages.get(pLocale, pModelAvailable ? "wizard.matching.nothingToDo.askModel" : "wizard.matching.nothingToDo.noModel"));
		}
		return out.toString();
	}

	/** {@code pBase} (the job's usual sentence) followed by the objects that still have no plan; unchanged when none. */
	public static String withGaps(final Locale pLocale, final String pBase, final List<Gap> pGaps)
	{
		return pGaps.isEmpty() ? pBase : pBase + " " + MatchingPlanCoverage.gapSentences(pLocale, pGaps);
	}

	/** Entity names of the gaps, for tests and callers that only need the list. */
	public static List<String> names(final List<Gap> pGaps)
	{
		final List<String> out = new ArrayList<String>();
		for (final Gap gap : pGaps)
		{
			out.add(gap.entity);
		}
		return Collections.unmodifiableList(out);
	}
}
