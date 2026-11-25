package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.matching.B2cKeyFixtureTest;
import com.on.ebx.factory.matching.KeyFacts;

/**
 * JUnit-free: run the main, a failure throws. Lot F1 (2026-10-19, {@code docs/MATCHING.md} &sect;70): the FEEDBACK TURN of the language-model matching pass, on the B2C scenario's entity. The model
 * proposes a plan; the Factory opens the entity for one more answer ({@code Session#beginFeedback}); the model revises it (a proposal judged by every check) or keeps it ({@code keep_match_plan},
 * with a rationale) or says nothing; whichever it does, the plan it answers with stands, and the Factory sets nothing. Also: the prompt of the turn, the tools it offers, the system prompt that
 * announces it, and how the next "Propose another" reads the stored statements.
 */
public final class MatchPlanLlmPassFeedbackTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanLlmPassFeedbackTest.theSystemPromptAnnouncesTheTurnAndSetsNothing();
		MatchPlanLlmPassFeedbackTest.theFeedbackTurnOffersProposeKeepAndNote();
		MatchPlanLlmPassFeedbackTest.aPlanAlreadyRecordedCannotBeProposedAgainWithoutTheTurn();
		MatchPlanLlmPassFeedbackTest.theModelRevisesItsPlan();
		MatchPlanLlmPassFeedbackTest.theModelKeepsItsPlanWithARationale();
		MatchPlanLlmPassFeedbackTest.proposingTheSamePlanAgainIsKeepingIt();
		MatchPlanLlmPassFeedbackTest.noAnswerLeavesThePlanAsItWas();
		MatchPlanLlmPassFeedbackTest.aRefusedRevisionLosesNothingAndIsNotCountedAgainstTheFirstProposal();
		MatchPlanLlmPassFeedbackTest.keepIsRefusedOutsideTheTurnOrTwice();
		MatchPlanLlmPassFeedbackTest.anEntityWithoutAPlanOfTheModelIsNotOpened();
		MatchPlanLlmPassFeedbackTest.theSettledPlanCarriesTheRationaleAndNothingElseChanges();
		MatchPlanLlmPassFeedbackTest.thePromptOfTheTurnHoldsThePlanTheCountsAndTheFacts();
		MatchPlanLlmPassFeedbackTest.theStoredStatementsReachTheNextProposal();
		MatchPlanLlmPassFeedbackTest.theContractOfTheTurnIsUnambiguous();
		MatchPlanLlmPassFeedbackTest.aKeepThatAnnouncesAChangeIsRefusedOnceThenAccepted();
		MatchPlanLlmPassFeedbackTest.aProposeAfterTheRefusalIsAcceptedAndStored();
		MatchPlanLlmPassFeedbackTest.aPlainKeepIsNeverRefused();
		MatchPlanLlmPassFeedbackTest.theSignalsAreTheModelsOwnWords();
		MatchPlanLlmPassFeedbackTest.theTurnGivesWhatReProposingNeeds();
		System.out.println("MatchPlanLlmPassFeedbackTest OK");
	}

	// ------------------------------------------------------------------------------------------------------ fixtures (the B2C master scenario's entity, as in MatchPlanLlmPassKeyFactsTest)

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static MatchPlanLlmPass.AllowedVocabulary vocabulary()
	{
		return new MatchPlanLlmPass.AllowedVocabulary(Arrays.asList("ebx-addon-mame@exact", "ebx-addon-mame@exactFullText", "ebx-addon-mame@jarowinkler"), Arrays.asList("MATCH", "NOT_MATCH"),
			Arrays.asList("mostCompleted", "lastUpdate", "mostTrustedSource"));
	}

	private static MatchPlanLlmPass.AttributeInfo plain(final String pName, final String pType, final int pDistinct, final double pFill)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, pType, 255, null, Double.valueOf(pFill), "EXACT", Integer.valueOf(pDistinct), "EXACT", null, null, null, null, true,
			MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS, Collections.<String>emptyList(), Collections.<String>emptyList());
	}

	private static MatchPlanLlmPass.AttributeInfo matchKey(final String pName, final String pDerivedFrom, final int pDistinct, final double pFill)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 255, null, Double.valueOf(pFill), "EXACT", Integer.valueOf(pDistinct), "EXACT", null, null, null, null, true,
			MatchPlanLlmPass.AttributeInfo.ROLE_MATCH_KEY, Collections.<String>emptyList(), Collections.<String>emptyList(), pDerivedFrom, null);
	}

	private static MatchPlanLlmPass.AttributeInfo childKeys(final String pName)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 2000, null, Double.valueOf(0.56), "EXACT", Integer.valueOf(300), "EXACT", null, null, null, null, true, null,
			Collections.<String>emptyList(), Collections.<String>emptyList(), null, null, null, null, null, null, null, null, null, MatchPlanLlmPass.AttributeInfo.DERIVATION_CHILD_KEYS, "AdresseClient");
	}

	private static MatchPlanLlmPass.EntityInfo client()
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanLlmPassFeedbackTest.matchKey("emailKey", "email", 207, 0.73));
		attributes.add(MatchPlanLlmPassFeedbackTest.matchKey("fullNameKey", "name", 187, 1.0));
		attributes.add(MatchPlanLlmPassFeedbackTest.matchKey("mobileE164", "mobile", 212, 1.0));
		attributes.add(MatchPlanLlmPassFeedbackTest.plain("firstName", "STRING", 150, 1.0));
		attributes.add(MatchPlanLlmPassFeedbackTest.plain("lastName", "STRING", 150, 1.0));
		attributes.add(MatchPlanLlmPassFeedbackTest.plain("birthDate", "DATE", 195, 0.56));
		attributes.add(MatchPlanLlmPassFeedbackTest.childKeys("addressKeys"));
		final MatchPlanLlmPass.EntityInfo entity = new MatchPlanLlmPass.EntityInfo("Client", "Client", 534, attributes);
		entity.keyFacts = B2cKeyFixtureTest.facts();
		return entity;
	}

	private static MatchPlanLlmPass.Session session()
	{
		final Map<String, MatchPlanLlmPass.EntityInfo> entities = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		entities.put("Client", MatchPlanLlmPassFeedbackTest.client());
		return new MatchPlanLlmPass.Session(entities, new LinkedHashMap<String, String>(), new ArrayList<MatchPlanLlmPass.ExistingPlan>(), MatchPlanLlmPassFeedbackTest.vocabulary())
			.requireFilteredCandidateSearch();
	}

	private static String key(final String pName)
	{
		return "{\"field\":\"" + pName + "\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}";
	}

	private static String node(final String pId, final String pFields, final String pOnTrue, final String pOnFalse)
	{
		return "{\"id\":\"" + pId + "\",\"function\":\"ALL_FIELDS\",\"fields\":[" + pFields + "],\"onTrue\":\"" + pOnTrue + "\",\"onFalse\":\"" + pOnFalse + "\"}";
	}

	private static String call(final String pNodes, final String pClustering, final String pRationale)
	{
		return "{\"entity\":\"Client\",\"root\":\"N1\",\"nodes\":[" + pNodes + "],\"clusteringFields\":[" + pClustering + "],\"rationale\":\"" + pRationale + "\",\"confidence\":80}";
	}

	/** First proposal: e-mail + name and phone + name end in MATCH, the e-mail alone and the phone alone in a review. */
	private static String firstPlan()
	{
		return MatchPlanLlmPassFeedbackTest.call(MatchPlanLlmPassFeedbackTest.node("N1", MatchPlanLlmPassFeedbackTest.key("emailKey") + "," + MatchPlanLlmPassFeedbackTest.key("fullNameKey"), "MATCH", "N2") + ","
			+ MatchPlanLlmPassFeedbackTest.node("N2", MatchPlanLlmPassFeedbackTest.key("mobileE164") + "," + MatchPlanLlmPassFeedbackTest.key("fullNameKey"), "MATCH", "N3") + ","
			+ MatchPlanLlmPassFeedbackTest.node("N3", MatchPlanLlmPassFeedbackTest.key("emailKey"), "SUSPECTED", "N4") + ","
			+ MatchPlanLlmPassFeedbackTest.node("N4", MatchPlanLlmPassFeedbackTest.key("mobileE164"), "SUSPECTED", "NOT_MATCHED"), "\"emailKey\",\"mobileE164\",\"fullNameKey\"", "keys first, weaker paths after.");
	}

	/** A revision: the phone alone no longer ends in a review. */
	private static String revisedPlan()
	{
		return MatchPlanLlmPassFeedbackTest.call(MatchPlanLlmPassFeedbackTest.node("N1", MatchPlanLlmPassFeedbackTest.key("emailKey") + "," + MatchPlanLlmPassFeedbackTest.key("fullNameKey"), "MATCH", "N2") + ","
			+ MatchPlanLlmPassFeedbackTest.node("N2", MatchPlanLlmPassFeedbackTest.key("mobileE164") + "," + MatchPlanLlmPassFeedbackTest.key("fullNameKey"), "MATCH", "N3") + ","
			+ MatchPlanLlmPassFeedbackTest.node("N3", MatchPlanLlmPassFeedbackTest.key("emailKey"), "SUSPECTED", "NOT_MATCHED"), "\"emailKey\",\"mobileE164\",\"fullNameKey\"",
			"after the simulation: the phone alone is not enough.");
	}

	private static JsonNode parse(final String pJson)
	{
		try
		{
			return MatchPlanLlmPassFeedbackTest.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	private static LocalTool.Result propose(final MatchPlanLlmPass.Session pSession, final String pCall)
	{
		return pSession.proposeMatchPlan(MatchPlanLlmPassFeedbackTest.parse(pCall));
	}

	private static LocalTool.Result keep(final MatchPlanLlmPass.Session pSession, final String pEntity, final String pRationale)
	{
		return pSession.keepMatchPlan(MatchPlanLlmPassFeedbackTest.parse("{\"entity\":\"" + pEntity + "\",\"rationale\":\"" + pRationale + "\"}"));
	}

	/** A session in which the first plan is accepted and the entity is opened for the feedback. */
	private static MatchPlanLlmPass.Session opened()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.session();
		final LocalTool.Result first = MatchPlanLlmPassFeedbackTest.propose(session, MatchPlanLlmPassFeedbackTest.firstPlan());
		check("the first plan is accepted: " + first.text, !first.isError);
		check("the entity is opened for one more answer", session.beginFeedback(Arrays.asList("Client")).equals(Arrays.asList("Client")));
		return session;
	}

	private static List<String> toolNames(final ToolSet.LocalGroup pGroup)
	{
		final List<String> out = new ArrayList<String>();
		for (final LocalTool tool : pGroup.tools)
		{
			out.add(tool.name);
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------------- tests

	private static void theSystemPromptAnnouncesTheTurnAndSetsNothing()
	{
		final String prompt = MatchPlanLlmPass.systemPrompt();
		check("the turn is announced", prompt.contains("SIMULATION FEEDBACK") && prompt.contains("SECOND turn") && prompt.contains("COUNTS only (never a value)"));
		check("both answers are named and either stands", prompt.contains("propose_match_plan (a revised plan") && prompt.contains("keep_match_plan (why the plan stands") && prompt.contains("Either answer stands and is recorded"));
		check("the Factory sets nothing", prompt.contains("the Factory sets no weight, threshold or leaf"));
		check("the cost of both sides is told", prompt.contains("a pair left to a steward costs a person's time: weigh both"));
		check("still no column name in the contract", !prompt.contains("emailKey") && !prompt.contains("mobileE164"));
	}

	private static void theFeedbackTurnOffersProposeKeepAndNote()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.session();
		final List<String> proposal = MatchPlanLlmPassFeedbackTest.toolNames(session.toolGroup());
		final List<String> feedback = MatchPlanLlmPassFeedbackTest.toolNames(session.feedbackToolGroup());
		check("the proposal turn is unchanged: " + proposal, proposal.equals(Arrays.asList("propose_match_plan", "skip_entity", "note")));
		check("the feedback turn: propose, keep, note - no skip: " + feedback, feedback.equals(Arrays.asList("propose_match_plan", "keep_match_plan", "note")));
	}

	private static void aPlanAlreadyRecordedCannotBeProposedAgainWithoutTheTurn()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.session();
		check("accepted", !MatchPlanLlmPassFeedbackTest.propose(session, MatchPlanLlmPassFeedbackTest.firstPlan()).isError);
		final LocalTool.Result again = MatchPlanLlmPassFeedbackTest.propose(session, MatchPlanLlmPassFeedbackTest.revisedPlan());
		check("one proposal per entity per turn: " + again.text, again.isError && again.text.contains("was already recorded"));
	}

	private static void theModelRevisesItsPlan()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.opened();
		final LocalTool.Result revised = MatchPlanLlmPassFeedbackTest.propose(session, MatchPlanLlmPassFeedbackTest.revisedPlan());
		check("a revision goes through every check of a proposal and is accepted: " + revised.text, !revised.isError);
		final MatchPlanLlmPass.Session.FeedbackOutcome outcome = session.feedbackOutcomes().get("Client");
		check("recorded as REVISED", MatchPlanLlmPass.Session.FeedbackOutcome.REVISED.equals(outcome.kind) && outcome.before != outcome.after);
		check("the plan that stands is the revision", session.createdPlans().size() == 1 && session.createdPlan("Client").rationale.contains("after the simulation"));
		check("and it differs from the first in its tree", !MatchPlanLlmPass.sameStructure(outcome.before.requirementsJson, outcome.after.requirementsJson));
		final LocalTool.Result twice = MatchPlanLlmPassFeedbackTest.propose(session, MatchPlanLlmPassFeedbackTest.firstPlan());
		check("one answer per entity: a second revision is refused", twice.isError && twice.text.contains("was already recorded"));
	}

	private static void theModelKeepsItsPlanWithARationale()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.opened();
		final LocalTool.Result kept = MatchPlanLlmPassFeedbackTest.keep(session, "Client", "the pairs that agree on the phone are family members sharing one line");
		check("accepted: " + kept.text, !kept.isError);
		final MatchPlanLlmPass.Session.FeedbackOutcome outcome = session.feedbackOutcomes().get("Client");
		check("recorded as KEPT with the model's words", MatchPlanLlmPass.Session.FeedbackOutcome.KEPT.equals(outcome.kind) && outcome.keptRationale.contains("family members") && outcome.before == outcome.after);
		check("the plan is the one proposed first", session.createdPlan("Client") == outcome.before);
		final LocalTool.Result late = MatchPlanLlmPassFeedbackTest.propose(session, MatchPlanLlmPassFeedbackTest.revisedPlan());
		check("after keeping, the entity is closed again", late.isError && late.text.contains("was already recorded"));
	}

	private static void proposingTheSamePlanAgainIsKeepingIt()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.opened();
		final LocalTool.Result again = MatchPlanLlmPassFeedbackTest.propose(session, MatchPlanLlmPassFeedbackTest.firstPlan());
		check("accepted: " + again.text, !again.isError);
		final MatchPlanLlmPass.Session.FeedbackOutcome outcome = session.feedbackOutcomes().get("Client");
		check("the same structure is a plan kept, without a rationale of its own", MatchPlanLlmPass.Session.FeedbackOutcome.KEPT.equals(outcome.kind) && outcome.keptRationale.isEmpty());
		check("the plan that stands is the first one", session.settleFeedback("Client", "r") != null && session.createdPlan("Client").requirementsJson.equals(outcome.before.requirementsJson));
	}

	private static void noAnswerLeavesThePlanAsItWas()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.opened();
		final MatchPlanLlmPass.Session.FeedbackOutcome outcome = session.feedbackOutcomes().get("Client");
		check("NO_ANSWER: the plan stands, said as such", MatchPlanLlmPass.Session.FeedbackOutcome.NO_ANSWER.equals(outcome.kind) && session.createdPlan("Client") == outcome.before);
	}

	/** A revision the checks refuse leaves the first plan in place; the counters of the first proposal do not carry over (a fresh ledger), so a second, valid revision is still possible. */
	private static void aRefusedRevisionLosesNothingAndIsNotCountedAgainstTheFirstProposal()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.opened();
		final String bad = MatchPlanLlmPassFeedbackTest.call(MatchPlanLlmPassFeedbackTest.node("N1", MatchPlanLlmPassFeedbackTest.key("noSuchField"), "SUSPECTED", "NOT_MATCHED"), "\"emailKey\"", "x");
		final LocalTool.Result refused = MatchPlanLlmPassFeedbackTest.propose(session, bad);
		check("refused: " + refused.text, refused.isError);
		check("the first plan is still there", session.createdPlans().size() == 1 && MatchPlanLlmPass.Session.FeedbackOutcome.NO_ANSWER.equals(session.feedbackOutcomes().get("Client").kind));
		check("a valid revision after the refusal is accepted", !MatchPlanLlmPassFeedbackTest.propose(session, MatchPlanLlmPassFeedbackTest.revisedPlan()).isError
			&& MatchPlanLlmPass.Session.FeedbackOutcome.REVISED.equals(session.feedbackOutcomes().get("Client").kind));
		check("and the entity is not listed as left without a plan", session.unplannedAttempts().isEmpty());
	}

	private static void keepIsRefusedOutsideTheTurnOrTwice()
	{
		final MatchPlanLlmPass.Session closed = MatchPlanLlmPassFeedbackTest.session();
		check("no turn opened: refused", MatchPlanLlmPassFeedbackTest.keep(closed, "Client", "x").isError);
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.opened();
		check("unknown entity", MatchPlanLlmPassFeedbackTest.keep(session, "Nobody", "x").isError);
		check("an empty rationale", MatchPlanLlmPassFeedbackTest.keep(session, "Client", "  ").isError);
		check("the first answer", !MatchPlanLlmPassFeedbackTest.keep(session, "Client", "ok").isError);
		final LocalTool.Result twice = MatchPlanLlmPassFeedbackTest.keep(session, "Client", "again");
		check("one answer per entity: " + twice.text, twice.isError && twice.text.contains("already answered"));
	}

	private static void anEntityWithoutAPlanOfTheModelIsNotOpened()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.session();
		check("no plan accepted: nothing to give feedback on", session.beginFeedback(Arrays.asList("Client", "Nobody")).isEmpty());
		check("and no outcome", session.feedbackOutcomes().isEmpty());
	}

	/** The settled plan is the plan that stands with a new rationale - the tree, the clustering, the weights, the confidence and the evidence are the ones the model proposed. */
	private static void theSettledPlanCarriesTheRationaleAndNothingElseChanges()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.opened();
		MatchPlanLlmPassFeedbackTest.keep(session, "Client", "kept");
		final MatchPlanLlmPass.Session.FeedbackOutcome outcome = session.feedbackOutcomes().get("Client");
		final MatchPlanLlmPass.CreatedPlan settled = session.settleFeedback("Client", "Simulation: line. original");
		check("the rationale", "Simulation: line. original".equals(settled.rationale) && settled == session.createdPlan("Client"));
		check("nothing else", settled.requirementsJson.equals(outcome.before.requirementsJson) && settled.confidence == outcome.before.confidence && settled.evidenceJson.equals(outcome.before.evidenceJson)
			&& settled.entityName.equals("Client") && settled.origin.equals(outcome.before.origin));
	}

	private static void thePromptOfTheTurnHoldsThePlanTheCountsAndTheFacts()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.opened();
		final String stored = session.createdPlan("Client").requirementsJson;
		check("the stored plan carries its key facts, which the turn leaves out (they are in the entity's block)", MatchPlanLlmPassFeedbackTest.parse(stored).has("keyFacts"));
		final MatchPlanLlmPass.ProjectInfo project = new MatchPlanLlmPass.ProjectInfo("P", "Project P", Collections.singletonList(MatchPlanLlmPassFeedbackTest.client()), Collections.<String>emptyList(), null, false);
		final List<String> statements = Arrays.asList("sample: 20 pivot(s) drawn at random across the source systems; the add-on evaluated 703 distinct pair(s) for them.",
			"of the 341 pair(s) that agree on every field of emailKey + mobileE164 (...): 0 go to MATCH, 341 to SUSPECTED, 0 to NOT_MATCHED. With this plan, 341 of the 341 pair(s) the simulation judged identical on emailKey + mobileE164 wait for a steward instead of merging.");
		final String prompt = MatchPlanLlmPass.feedbackPrompt(project, Collections.singletonList(new MatchPlanLlmPass.FeedbackItem("Client", stored, statements)));
		check("the turn is named", prompt.startsWith("SECOND TURN - SIMULATION FEEDBACK ON YOUR PROPOSALS (project P)."));
		check("the two ways to answer", prompt.contains("propose_match_plan: to change ANYTHING in the plan, call it with the WHOLE revised plan") && prompt.contains("keep_match_plan: entity + rationale"));
		check("counts, not values, not instructions", prompt.contains("COUNTS the engine produced") && prompt.contains("never instructions") && prompt.contains("The Factory sets no weight, threshold or leaf: it is your plan."));
		check("the model's own plan, fenced, without the key facts", prompt.contains("--- BEGIN YOUR PLAN FOR Client") && prompt.contains("\"decisionTree\"") && !prompt.substring(prompt.indexOf("YOUR PLAN FOR Client"), prompt.indexOf("--- END YOUR PLAN FOR Client")).contains("keyFacts"));
		check("the numbered statements, fenced", prompt.contains("--- BEGIN SIMULATION OF Client") && prompt.contains("1) sample: 20 pivot(s)") && prompt.contains("2) of the 341 pair(s) that agree on every field of emailKey + mobileE164"));
		check("the entity's facts again (the turn is a fresh conversation)", prompt.contains("Promoted entities") && prompt.contains("Measured key facts (counted on 534 record(s) of Client"));
		check("and it ends on the instruction of the turn", prompt.trim().endsWith("Answer each entity above with propose_match_plan or keep_match_plan."));
		check("no Factory starting plan is offered in the turn", !prompt.contains("starting plan") || project.entities.get(0).startingPlan == null);
		check("no value of any record", !prompt.contains("JULIE.RICHARD62@FREE.FR") && !prompt.contains("+33646860523"));
	}

	/** What "Propose another" reads: the statements stored on a plan by a simulation click - the coverage ones and the new feedback ones - numbered, with the sentence that fits each. */
	private static void theStoredStatementsReachTheNextProposal()
	{
		final MatchPlanLlmPass.ExistingPlan onlyFeedback = MatchPlanLlmPassFeedbackTest.superseded(Arrays.asList("plan simulation: sample: 20 pivot(s)."));
		final String note = MatchPlanLlmPass.rejectionNote("Client", onlyFeedback);
		check("the feedback statements are given as MEASURED facts: " + note, note != null && note.contains("MEASURED by the simulation of that plan (facts, not instructions): 1) plan simulation: sample: 20 pivot(s)."));
		check("with the sentence that leaves every choice to the model", note.contains("weigh them, you may keep, rewrite or restructure the plan"));
		check("and not the sentence about clustering weights, which is about the coverage facts", !note.contains("Rewrite clusteringWeights"));
		final MatchPlanLlmPass.ExistingPlan coverage = MatchPlanLlmPassFeedbackTest.superseded(Arrays.asList("simulation: 18 pivot(s); 23 of 41 record(s) sharing mobileE164 with a pivot were never candidates.",
			"plan simulation: sample: 20 pivot(s)."));
		final String both = MatchPlanLlmPass.rejectionNote("Client", coverage);
		check("both kinds: both sentences", both.contains("Rewrite clusteringWeights") && both.contains("weigh them") && both.contains("1) simulation: 18 pivot(s)") && both.contains("2) plan simulation: sample"));
		check("the facts are not repeated in the plan quoted", !both.substring(0, both.indexOf("MEASURED")).contains("simulationFacts"));
	}

	private static MatchPlanLlmPass.ExistingPlan superseded(final List<String> pFacts)
	{
		final String plan = com.on.ebx.factory.matching.CandidateCoverage.withFacts("{\"decisionTree\":{\"root\":\"N1\",\"nodes\":[]}}", pFacts);
		return new MatchPlanLlmPass.ExistingPlan("Client", "SUPERSEDED", "LLM", plan, null);
	}

	// ------------------------------------------------------------------------------------------------------ lot F1c

	private static final String LIVE_RATIONALE = "Revise rather than keep: the sample shows every evaluated and probed pair was left to stewardship, including all 36 pairs sharing the SAFE "
		+ "phoneE164 + personNameKey combination. Those SAFE combinations support MATCH, so the stored phoneE164->personNameKey path should terminate in MATCH. Adjust weights so each key-start path clears 30%. "
		+ "I am replacing it.";

	/** Live run 8: the model wanted to revise and called keep_match_plan. The prompt now says which call changes a plan and that a rationale edits nothing. */
	private static void theContractOfTheTurnIsUnambiguous()
	{
		final MatchPlanLlmPass.ProjectInfo project = new MatchPlanLlmPass.ProjectInfo("P", "Project P", Collections.singletonList(MatchPlanLlmPassFeedbackTest.client()), Collections.<String>emptyList(), null, false);
		final String prompt = MatchPlanLlmPass.feedbackPrompt(project, Collections.singletonList(new MatchPlanLlmPass.FeedbackItem("Client", "{}", Arrays.asList("s"))));
		check("changing = propose with the whole plan", prompt.contains("to change ANYTHING in the plan, call it with the WHOLE revised plan - every argument of a proposal: all nodes, the clustering fields, clusteringWeights"));
		check("it replaces the stored proposal", prompt.contains("it replaces the stored proposal"));
		check("keeping = exactly as it is, and why it is right as it is", prompt.contains("the stored plan stays EXACTLY as it is; the rationale must say why it is right as it is"));
		check("a description of changes changes nothing", prompt.contains("A description of changes in a rationale changes NOTHING: a plan is never edited from a rationale, only by propose_match_plan."));
		check("clusteringWeights can be rewritten in the same call", prompt.contains("You may rewrite clusteringWeights in the same call"));
		check("the system prompt says it too", MatchPlanLlmPass.systemPrompt().contains("To change ANYTHING in a plan, call propose_match_plan with the WHOLE revised plan; keep_match_plan means the stored plan stays exactly as it is"));
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.session();
		check("the tool descriptions", MatchPlanLlmPassFeedbackTest.descriptionOf(session, "keep_match_plan").contains("EXACTLY as it is") && MatchPlanLlmPassFeedbackTest.descriptionOf(session, "keep_match_plan").contains("call propose_match_plan"));
	}

	private static String descriptionOf(final MatchPlanLlmPass.Session pSession, final String pTool)
	{
		for (final LocalTool tool : pSession.feedbackToolGroup().tools)
		{
			if (pTool.equals(tool.name))
			{
				return tool.description;
			}
		}
		return "";
	}

	private static void aKeepThatAnnouncesAChangeIsRefusedOnceThenAccepted()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.opened();
		final LocalTool.Result first = MatchPlanLlmPassFeedbackTest.keep(session, "Client", MatchPlanLlmPassFeedbackTest.LIVE_RATIONALE);
		check("refused the first time", first.isError);
		check("with the exact text and the model's own sentence quoted: " + first.text, first.text.startsWith("Your rationale says you want to change the plan (\"Revise rather than keep: the sample shows")
			&& first.text.endsWith("). To change it, call propose_match_plan with the full revised plan now; to keep it, call keep_match_plan with a rationale that keeps it."));
		check("nothing recorded by the refusal: still no answer", MatchPlanLlmPass.Session.FeedbackOutcome.NO_ANSWER.equals(session.feedbackOutcomes().get("Client").kind));
		final LocalTool.Result second = MatchPlanLlmPassFeedbackTest.keep(session, "Client", MatchPlanLlmPassFeedbackTest.LIVE_RATIONALE);
		check("the same signal, the second time: accepted (never a loop): " + second.text, !second.isError);
		final MatchPlanLlmPass.Session.FeedbackOutcome outcome = session.feedbackOutcomes().get("Client");
		check("KEPT, said to have been refused once, with the model's words", MatchPlanLlmPass.Session.FeedbackOutcome.KEPT.equals(outcome.kind) && outcome.keepRefusedOnce && outcome.keptRationale.startsWith("Revise rather than keep"));
		check("a third call is the usual 'already answered'", MatchPlanLlmPassFeedbackTest.keep(session, "Client", "x").text.contains("already answered"));
	}

	private static void aProposeAfterTheRefusalIsAcceptedAndStored()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.opened();
		check("refused", MatchPlanLlmPassFeedbackTest.keep(session, "Client", MatchPlanLlmPassFeedbackTest.LIVE_RATIONALE).isError);
		final LocalTool.Result revised = MatchPlanLlmPassFeedbackTest.propose(session, MatchPlanLlmPassFeedbackTest.revisedPlan());
		check("the full revised plan is accepted: " + revised.text, !revised.isError);
		final MatchPlanLlmPass.Session.FeedbackOutcome outcome = session.feedbackOutcomes().get("Client");
		check("REVISED, the refusal is remembered", MatchPlanLlmPass.Session.FeedbackOutcome.REVISED.equals(outcome.kind) && outcome.keepRefusedOnce && session.createdPlan("Client") == outcome.after);
		check("the plan that stands is the revision", session.createdPlan("Client").rationale.contains("after the simulation"));
	}

	private static void aPlainKeepIsNeverRefused()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassFeedbackTest.opened();
		check("accepted at once", !MatchPlanLlmPassFeedbackTest.keep(session, "Client", "The phone alone is shared by relatives in this sample; the plan sends them to a steward on purpose, and the weights hold every path above 30%.").isError);
		final MatchPlanLlmPass.Session.FeedbackOutcome outcome = session.feedbackOutcomes().get("Client");
		check("KEPT without a refusal", MatchPlanLlmPass.Session.FeedbackOutcome.KEPT.equals(outcome.kind) && !outcome.keepRefusedOnce);
	}

	private static void theSignalsAreTheModelsOwnWords()
	{
		final String[] changing = { "I will revise the plan.", "Revising it is better.", "This plan was revised.", "I am replacing it", "the path should terminate in MATCH", "Adjust weights to clear 30%", "adjusting the weights helps",
			"Adding a path for STORES is needed.", "add another path", "instead of keeping it", "rather than keep the plan" };
		for (final String text : changing)
		{
			check("announces a change: " + text, MatchPlanLlmPass.announcedChange(text) != null);
		}
		final String[] keeping = { "The plan is right as it is.", "Namesakes share the name key, so the plan keeps it in a review.", "Every path holds more than 30% of the weight.", "", "No weight needs adjusting here." };
		for (final String text : keeping)
		{
			check("keeps: '" + text + "' -> " + MatchPlanLlmPass.announcedChange(text), MatchPlanLlmPass.announcedChange(text) == null);
		}
		check("the quoted sentence is the one with the signal", "Then we replace nothing here.".equals("Then we replace nothing here.") && MatchPlanLlmPass.announcedChange("Fine so far. I am replacing it now. Done.").equals("I am replacing it now."));
		check("a long sentence is cut", MatchPlanLlmPass.announcedChange("We should revise " + MatchPlanLlmPassFeedbackTest.repeat("x", 600)).length() <= 240);
		check("null", MatchPlanLlmPass.announcedChange(null) == null);
	}

	private static String repeat(final String pText, final int pTimes)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pTimes; i++)
		{
			out.append(pText);
		}
		return out.toString();
	}

	/** The turn is a fresh conversation: to re-propose, the model must find in it the field list, the allowed vocabulary, the key facts and the checklist, not only the counts. */
	private static void theTurnGivesWhatReProposingNeeds()
	{
		final MatchPlanLlmPass.ProjectInfo project = new MatchPlanLlmPass.ProjectInfo("P", "Project P", Collections.singletonList(MatchPlanLlmPassFeedbackTest.client()), Collections.<String>emptyList(), null, false);
		project.vocabularyNote = "VOCABULARY-NOTE-MARK";
		final String prompt = MatchPlanLlmPass.feedbackPrompt(project, Collections.singletonList(new MatchPlanLlmPass.FeedbackItem("Client", "{}", Arrays.asList("s"))));
		check("the field list with roles and fill rates", prompt.contains("Attributes:") && prompt.contains("emailKey (type STRING") && prompt.contains("fill rate") && prompt.contains("role MATCH_KEY"));
		check("the allowed vocabulary note", prompt.contains("Add-on vocabulary note: VOCABULARY-NOTE-MARK"));
		check("the key facts", prompt.contains("Measured key facts (counted on 534 record(s) of Client"));
		check("the checklist of the entity", prompt.contains("MATCH paths on Client"));
		check("the system prompt is the same one (the contract, the 30% rule)", MatchPlanLlmPass.systemPrompt().contains("HOW THE ADD-ON FINDS CANDIDATES"));
	}
}
