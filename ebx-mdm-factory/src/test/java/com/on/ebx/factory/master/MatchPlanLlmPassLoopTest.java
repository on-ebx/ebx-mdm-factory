package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.factory.matching.KeyFacts;

/**
 * JUnit-free: run the main, a failure throws. Lot M5 (2026-10-18, {@code docs/MATCHING.md} &sect;60): the matching language-model pass looped on the same refusal until the
 * token budget died (live run 433: "'mobileE164' is not near-unique enough ..." four times in a row inside four different trees). The breaker on the refusal itself, the cap on
 * proposals per entity, the way out every MATCH-terminal refusal names, and the verdict line the prompt gives each entity.
 */
public final class MatchPlanLlmPassLoopTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanLlmPassLoopTest.theSameRefusalTwiceInARowEndsThatEntityWhateverTheRestOfTheTreeSays();
		MatchPlanLlmPassLoopTest.theSameRefusalThreeTimesInTheRunEndsTheEntityEvenIfNotInARow();
		MatchPlanLlmPassLoopTest.tooManyRefusedProposalsEndTheEntityAndTheCapIsSettable();
		MatchPlanLlmPassLoopTest.anEntityThatEndedNeverStopsTheOthersAndIsNotNotedTwice();
		MatchPlanLlmPassLoopTest.anEntityThatRecoversLeavesNoLedgerEntry();
		MatchPlanLlmPassLoopTest.theThreeStrikesBreakerStillCoversASkipThatIsRefused();
		MatchPlanLlmPassLoopTest.aNodeSeveralBranchesLeadToIsCopiedNotRefused();
		MatchPlanLlmPassLoopTest.aRealCycleIsStillRefused();
		MatchPlanLlmPassLoopTest.theClonerCopiesWholeSubtreesWithFreshIds();
		MatchPlanLlmPassLoopTest.theFilterRefusalNamesTheRewriteAndTheSecondUnfilteredPlanIsKeptWithAWarning();
		MatchPlanLlmPassLoopTest.aSkipAfterRefusalsIsRecordedWithTheFactorysLastRefusal();
		MatchPlanLlmPassLoopTest.theRefusalNamesTheWayOutFromTheFacts();
		MatchPlanLlmPassLoopTest.thePromptSaysPerEntityWhetherAnyPairIsSafe();
		MatchPlanLlmPassLoopTest.theContractTellsTheModelWhatEndsAnEntity();
		System.out.println("MatchPlanLlmPassLoopTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	private static MatchPlanLlmPass.AllowedVocabulary vocabulary()
	{
		return new MatchPlanLlmPass.AllowedVocabulary(Arrays.asList("ebx-addon-mame@exact", "ebx-addon-mame@exactFullText", "ebx-addon-mame@jarowinkler"),
			Arrays.asList("MATCH", "NOT_MATCH"), Arrays.asList("mostCompleted", "lastUpdate", "mostTrustedSource"));
	}

	private static MatchPlanLlmPass.AttributeInfo plain(final String pName, final int pDistinct, final double pFill)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 255, null, Double.valueOf(pFill), "EXACT", Integer.valueOf(pDistinct), "EXACT", null, null, null, null, true,
			MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS, Collections.<String>emptyList(), Collections.<String>emptyList());
	}

	/** Person: 534 records, a phone filled everywhere but shared (212 distinct = 39.7% of the filled values), a name key, a birth year. */
	private static MatchPlanLlmPass.EntityInfo person(final KeyFacts pFacts)
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanLlmPassLoopTest.plain("mobile", 212, 1.0));
		attributes.add(MatchPlanLlmPassLoopTest.plain("nameKey", 187, 1.0));
		attributes.add(MatchPlanLlmPassLoopTest.plain("firstName", 150, 1.0));
		attributes.add(MatchPlanLlmPassLoopTest.plain("lastName", 150, 1.0));
		final MatchPlanLlmPass.EntityInfo entity = new MatchPlanLlmPass.EntityInfo("Person", "Person", 534, attributes);
		entity.keyFacts = pFacts;
		return entity;
	}

	private static MatchPlanLlmPass.EntityInfo store()
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanLlmPassLoopTest.plain("storeCode", 100, 1.0));
		return new MatchPlanLlmPass.EntityInfo("Store", "Store", 100, attributes);
	}

	private static MatchPlanLlmPass.Session session(final KeyFacts pFacts)
	{
		final Map<String, MatchPlanLlmPass.EntityInfo> entities = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		entities.put("Person", MatchPlanLlmPassLoopTest.person(pFacts));
		entities.put("Store", MatchPlanLlmPassLoopTest.store());
		return new MatchPlanLlmPass.Session(entities, new LinkedHashMap<String, String>(), new ArrayList<MatchPlanLlmPass.ExistingPlan>(), MatchPlanLlmPassLoopTest.vocabulary());
	}

	private static String key(final String pName)
	{
		return "{\"field\":\"" + pName + "\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}";
	}

	private static String fuzzy(final String pName)
	{
		return "{\"field\":\"" + pName + "\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"minimumScore\":85,\"weight\":3,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}";
	}

	/** The refusal of the live run: the exact MATCH-terminal node on the phone, then the model varies everything after it. */
	private static String phoneMatch(final String pSecondNodeField, final String pRationale)
	{
		return "{\"entity\":\"Person\",\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanLlmPassLoopTest.key("mobile") + "],\"onTrue\":\"MATCH\",\"onFalse\":\"N2\"},"
			+ "{\"id\":\"N2\",\"function\":\"AVERAGE\",\"minimumScore\":85,\"fields\":[" + MatchPlanLlmPassLoopTest.fuzzy(pSecondNodeField)
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],\"clusteringFields\":[\"mobile\"],\"rationale\":\"" + pRationale + "\",\"confidence\":70}";
	}

	private static String unknownField(final String pEntity, final String pField)
	{
		return "{\"entity\":\"" + pEntity + "\",\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanLlmPassLoopTest.key(pField)
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],\"clusteringFields\":[],\"rationale\":\"x\",\"confidence\":50}";
	}

	private static String validStore()
	{
		return "{\"entity\":\"Store\",\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanLlmPassLoopTest.key("storeCode")
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],\"clusteringFields\":[],\"rationale\":\"the store code is shared by the two systems\",\"confidence\":70}";
	}

	private static LocalTool.Result submit(final MatchPlanLlmPass.Session pSession, final String pCall)
	{
		try
		{
			return pSession.proposeMatchPlan(MatchPlanLlmPassLoopTest.MAPPER.readTree(pCall));
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	/** Keys {@code mobile} and {@code nameKey}: twelve groups of two records that share both (SAFE both ways), over two source systems. */
	private static KeyFacts safeFacts()
	{
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		for (int i = 0; i < 12; i++)
		{
			rows.add(new KeyFacts.Row("A", new String[] { "m" + i, "n" + i }));
			rows.add(new KeyFacts.Row("B", new String[] { "m" + i, "n" + i }));
		}
		return KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("mobile", "mobile", false), new KeyFacts.KeySpec("nameKey", "name", false)), false);
	}

	/** The same two keys but the people sharing a phone have different names: nothing is SAFE. */
	private static KeyFacts unsafeFacts()
	{
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		for (int i = 0; i < 12; i++)
		{
			rows.add(new KeyFacts.Row("A", new String[] { "m" + i, "n" + i }));
			rows.add(new KeyFacts.Row("B", new String[] { "m" + i, "other" + i }));
		}
		return KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("mobile", "mobile", false), new KeyFacts.KeySpec("nameKey", "name", false)), false);
	}

	// ------------------------------------------------------------------------------------------------------- tests

	private static void theSameRefusalTwiceInARowEndsThatEntityWhateverTheRestOfTheTreeSays()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassLoopTest.session(MatchPlanLlmPassLoopTest.unsafeFacts());
		final LocalTool.Result first = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.phoneMatch("firstName", "first try"));
		MatchPlanLlmPassLoopTest.check(first.isError && first.text.contains("'mobile' is not near-unique enough") && !first.text.contains("Giving up"), "the first refusal is plain: " + first.text);
		// the model changes the second node, the rationale and the clustering fields: the call is not the same call, the refusal is
		final LocalTool.Result second = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.phoneMatch("lastName", "second try, a different tree"));
		MatchPlanLlmPassLoopTest.check(second.isError && second.text.contains("Giving up on Person") && second.text.contains("2 times in a row"), "the same refusal twice in a row ends the entity: " + second.text);
		MatchPlanLlmPassLoopTest.check(!session.stoppedEarly(), "never a run-wide stop");
		final List<MatchPlanLlmPass.Session.Attempt> attempts = session.unplannedAttempts();
		MatchPlanLlmPassLoopTest.check(attempts.size() == 1 && "Person".equals(attempts.get(0).entity) && attempts.get(0).attempts == 2
			&& MatchPlanLlmPass.Session.ENDED_SAME_REFUSAL.equals(attempts.get(0).endedBy), "the ledger says who ended it after how many proposals");
		MatchPlanLlmPassLoopTest.check(attempts.get(0).lastRefusal.contains("'mobile' is not near-unique enough") && attempts.get(0).lastRefusal.contains("39.7% distinct"),
			"and keeps the LAST refusal, the text the model read: " + attempts.get(0).lastRefusal);
		MatchPlanLlmPassLoopTest.check(session.createdPlans().isEmpty(), "no plan was recorded");
		boolean noted = false;
		for (final String note : session.notes())
		{
			noted |= note.startsWith("Person refused: the same refusal came back 2 times in a row") && note.contains("Last refusal: ") && note.contains("'mobile' is not near-unique enough");
		}
		MatchPlanLlmPassLoopTest.check(noted, "a note carries the reason: " + session.notes());
	}

	private static void theSameRefusalThreeTimesInTheRunEndsTheEntityEvenIfNotInARow()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassLoopTest.session(null);
		final LocalTool.Result a1 = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.unknownField("Person", "zz1"));
		final LocalTool.Result b1 = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.unknownField("Person", "zz2"));
		final LocalTool.Result a2 = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.unknownField("Person", "zz1"));
		final LocalTool.Result b2 = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.unknownField("Person", "zz2"));
		MatchPlanLlmPassLoopTest.check(a1.isError && b1.isError && a2.isError && b2.isError && !a2.text.contains("Giving up") && !b2.text.contains("Giving up"),
			"alternating between two refusals never repeats one in a row, and two of each are tolerated");
		final LocalTool.Result a3 = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.unknownField("Person", "zz1"));
		MatchPlanLlmPassLoopTest.check(a3.isError && a3.text.contains("Giving up on Person") && a3.text.contains("3 times in this run"), "the third time in the run ends it: " + a3.text);
		final MatchPlanLlmPass.Session.Attempt attempt = session.unplannedAttempts().get(0);
		MatchPlanLlmPassLoopTest.check(attempt.attempts == 5 && attempt.lastRefusal.contains("'zz1'"), "five proposals, the last refusal is the one that ended it");
	}

	private static void tooManyRefusedProposalsEndTheEntityAndTheCapIsSettable()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassLoopTest.session(null);
		for (int i = 1; i <= 5; i++)
		{
			final LocalTool.Result r = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.unknownField("Person", "field" + i));
			MatchPlanLlmPassLoopTest.check(r.isError && !r.text.contains("Giving up"), "refusal " + i + " (all different) is plain");
		}
		final LocalTool.Result sixth = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.unknownField("Person", "field6"));
		MatchPlanLlmPassLoopTest.check(sixth.isError && sixth.text.contains("Giving up on Person") && sixth.text.contains("6 proposals were refused"), "the sixth ends it: " + sixth.text);
		MatchPlanLlmPassLoopTest.check(MatchPlanLlmPass.Session.ENDED_MAX_ATTEMPTS.equals(session.unplannedAttempts().get(0).endedBy), "recorded as ended by the cap");

		final MatchPlanLlmPass.Session small = MatchPlanLlmPassLoopTest.session(null).maxAttemptsPerEntity(2);
		MatchPlanLlmPassLoopTest.check(!MatchPlanLlmPassLoopTest.submit(small, MatchPlanLlmPassLoopTest.unknownField("Person", "a")).text.contains("Giving up"), "cap 2: the first is plain");
		final LocalTool.Result second = MatchPlanLlmPassLoopTest.submit(small, MatchPlanLlmPassLoopTest.unknownField("Person", "b"));
		MatchPlanLlmPassLoopTest.check(second.text.contains("Giving up on Person") && second.text.contains("2 proposals were refused"), "cap 2: the second ends it: " + second.text);
	}

	private static void anEntityThatEndedNeverStopsTheOthersAndIsNotNotedTwice()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassLoopTest.session(null);
		MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.unknownField("Person", "zz"));
		MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.unknownField("Person", "zz"));
		final int notes = session.notes().size();
		final LocalTool.Result later = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.unknownField("Person", "zz"));
		MatchPlanLlmPassLoopTest.check(later.isError && later.text.contains("already recorded") && session.notes().size() == notes, "a later call for the ended entity is refused as recorded, with no second note");
		final LocalTool.Result store = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.validStore());
		MatchPlanLlmPassLoopTest.check(!store.isError && session.createdPlans().size() == 1, "the next entity is proposed normally: " + store.text);
		MatchPlanLlmPassLoopTest.check(session.unplannedAttempts().size() == 1 && "Person".equals(session.unplannedAttempts().get(0).entity), "only Person is in the ledger of the unplanned");
	}

	private static void anEntityThatRecoversLeavesNoLedgerEntry()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassLoopTest.session(null);
		MatchPlanLlmPassLoopTest.check(MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.unknownField("Store", "nope")).isError, "refused once");
		MatchPlanLlmPassLoopTest.check(!MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.validStore()).isError, "then corrected");
		MatchPlanLlmPassLoopTest.check(session.unplannedAttempts().isEmpty(), "an entity that got its plan is not an unplanned attempt");
	}

	private static void theThreeStrikesBreakerStillCoversASkipThatIsRefused()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassLoopTest.session(null);
		final String skip = "{\"entity\":\"Person\",\"reason\":\"\"}";
		try
		{
			MatchPlanLlmPassLoopTest.check(!session.skipEntity(MatchPlanLlmPassLoopTest.MAPPER.readTree(skip)).text.contains("Giving up"), "1st");
			MatchPlanLlmPassLoopTest.check(!session.skipEntity(MatchPlanLlmPassLoopTest.MAPPER.readTree(skip)).text.contains("Giving up"), "2nd");
			MatchPlanLlmPassLoopTest.check(session.skipEntity(MatchPlanLlmPassLoopTest.MAPPER.readTree(skip)).text.contains("Giving up on Person"), "3rd identical skip call ends it");
		}
		catch (final java.io.IOException ex)
		{
			throw new AssertionError(ex);
		}
		boolean noted = false;
		for (final String note : session.notes())
		{
			noted |= note.startsWith("Person skipped: refused 3 times in a row");
		}
		MatchPlanLlmPassLoopTest.check(noted && session.unplannedAttempts().isEmpty(), "noted as before, and not a proposal refusal: no ledger entry");
	}

	private static String exactNode(final String pId, final String pField, final String pOnTrue, final String pOnFalse)
	{
		return "{\"id\":\"" + pId + "\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanLlmPassLoopTest.key(pField) + "],\"onTrue\":\"" + pOnTrue + "\",\"onFalse\":\"" + pOnFalse + "\"}";
	}

	private static String averageNode(final String pId, final String pField, final String pOnTrue, final String pOnFalse)
	{
		return "{\"id\":\"" + pId + "\",\"function\":\"AVERAGE\",\"minimumScore\":85,\"fields\":[" + MatchPlanLlmPassLoopTest.fuzzy(pField) + "],\"onTrue\":\"" + pOnTrue
			+ "\",\"onFalse\":\"" + pOnFalse + "\"}";
	}

	private static String planOf(final String pNodes, final String pRationale)
	{
		return "{\"entity\":\"Person\",\"root\":\"N1\",\"nodes\":[" + pNodes + "],\"clusteringFields\":[],\"rationale\":\"" + pRationale + "\",\"confidence\":70}";
	}

	/** (a) Live run 454: N7, N8, N11 each referenced by two parents were refused as NODE_SHARED three times. */
	private static void aNodeSeveralBranchesLeadToIsCopiedNotRefused()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassLoopTest.session(null);
		// N4 is the common fall-through of N2.onFalse and N3.onTrue
		final String nodes = MatchPlanLlmPassLoopTest.exactNode("N1", "nameKey", "N2", "N3") + "," + MatchPlanLlmPassLoopTest.averageNode("N2", "firstName", "SUSPECTED", "N4") + ","
			+ MatchPlanLlmPassLoopTest.exactNode("N3", "mobile", "N4", "NOT_MATCHED") + "," + MatchPlanLlmPassLoopTest.averageNode("N4", "lastName", "SUSPECTED", "NOT_MATCHED");
		final LocalTool.Result result = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.planOf(nodes, "names, then phone, then the family name"));
		MatchPlanLlmPassLoopTest.check(!result.isError, "accepted: " + result.text);
		final MatchPlanLlmPass.CreatedPlan plan = session.createdPlans().get(0);
		try
		{
			final com.fasterxml.jackson.databind.JsonNode tree = MatchPlanLlmPassLoopTest.MAPPER.readTree(plan.requirementsJson).path("decisionTree");
			final Map<String, Integer> parents = new LinkedHashMap<String, Integer>();
			for (final com.fasterxml.jackson.databind.JsonNode node : tree.path("nodes"))
			{
				for (final String edge : new String[] { "onTrue", "onFalse" })
				{
					final String target = node.path(edge).asText();
					parents.put(target, Integer.valueOf((parents.containsKey(target) ? parents.get(target).intValue() : 0) + 1));
				}
			}
			MatchPlanLlmPassLoopTest.check(tree.path("nodes").size() == 5, "one copy was added: " + tree.path("nodes").size());
			for (final com.fasterxml.jackson.databind.JsonNode node : tree.path("nodes"))
			{
				final String id = node.path("id").asText();
				MatchPlanLlmPassLoopTest.check(!"N1".equals(id) ? parents.get(id).intValue() == 1 : true, "node " + id + " has exactly one parent in the stored tree");
			}
			MatchPlanLlmPassLoopTest.check("N5".equals(tree.path("nodes").get(2).path("onTrue").asText()) && "lastName".equals(tree.path("nodes").get(4).path("fields").get(0).path("field").asText()),
				"N3.onTrue now leads to the copy N5, which compares the same field: " + plan.requirementsJson);
		}
		catch (final java.io.IOException ex)
		{
			throw new AssertionError(ex);
		}
		MatchPlanLlmPassLoopTest.check(plan.rationale.contains("Node N4 was reached from several branches; the Factory copied it as N5"), "and the justification says so: " + plan.rationale);
	}

	private static void aRealCycleIsStillRefused()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassLoopTest.session(null);
		final String nodes = MatchPlanLlmPassLoopTest.exactNode("N1", "nameKey", "N2", "NOT_MATCHED") + "," + MatchPlanLlmPassLoopTest.averageNode("N2", "firstName", "SUSPECTED", "N1");
		final LocalTool.Result result = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.planOf(nodes, "a loop"));
		MatchPlanLlmPassLoopTest.check(result.isError && result.text.contains("not valid") && session.createdPlans().isEmpty(), "a branch back to a node of its own path is refused: " + result.text);
	}

	private static void theClonerCopiesWholeSubtreesWithFreshIds() throws Exception
	{
		// N1 -> (N2, N3); N2 and N3 both lead to N4; N4 -> (N5, N6) with leaves: the copy of N4 carries copies of N5 and N6
		final com.fasterxml.jackson.databind.node.ArrayNode nodes = (com.fasterxml.jackson.databind.node.ArrayNode) MatchPlanLlmPassLoopTest.MAPPER.readTree("["
			+ "{\"id\":\"N1\",\"onTrue\":\"N2\",\"onFalse\":\"N3\"},{\"id\":\"N2\",\"onTrue\":\"N4\",\"onFalse\":\"NOT_MATCHED\"},{\"id\":\"N3\",\"onTrue\":\"N4\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"N4\",\"onTrue\":\"N5\",\"onFalse\":\"N6\"},{\"id\":\"N5\",\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"},{\"id\":\"N6\",\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]");
		final List<String> notes = SharedNodeCloner.declone("N1", nodes);
		MatchPlanLlmPassLoopTest.check(nodes.size() == 9 && notes.size() == 1 && notes.get(0).contains("Node N4 was reached from several branches; the Factory copied it as N7"), "three nodes copied, one note: " + nodes + " / " + notes);
		MatchPlanLlmPassLoopTest.check("N7".equals(nodes.get(2).path("onTrue").asText()) && "N8".equals(nodes.get(8).path("onTrue").asText()) && "N9".equals(nodes.get(8).path("onFalse").asText()),
			"N3 leads to the copy, whose children are copies too: " + nodes);
		// idempotent: a tree is left alone
		MatchPlanLlmPassLoopTest.check(SharedNodeCloner.declone("N1", nodes).isEmpty() && nodes.size() == 9, "a tree is left as it is");
	}

	/** (b) Live run 454: "candidate search NOT filtered" came twice with no concrete rewrite and the entity ended with no plan. */
	private static void theFilterRefusalNamesTheRewriteAndTheSecondUnfilteredPlanIsKeptWithAWarning()
	{
		// lot M10a: on facts where no key identifies a record (unsafeFacts: the people sharing a phone have different names) the path rule does not apply, so this stays a test of the filter alone
		final String unfiltered = MatchPlanLlmPassLoopTest.planOf(MatchPlanLlmPassLoopTest.averageNode("N1", "firstName", "SUSPECTED", "NOT_MATCHED"), "similar first names");
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassLoopTest.session(MatchPlanLlmPassLoopTest.unsafeFacts()).requireFilteredCandidateSearch();
		final LocalTool.Result first = MatchPlanLlmPassLoopTest.submit(session, unfiltered);
		MatchPlanLlmPassLoopTest.check(first.isError && first.text.contains("would NOT be filtered") && first.text.contains("wrap path N1 under an ALL_FIELDS node on mobile (exact, bothNullValueHandling "
			+ "and oneNullValueHandling NOT_MATCH, minimumScore 100)") && first.text.contains("Keys filled on this entity: mobile 100%, nameKey 100%.")
			&& first.text.contains("kept as it is, with a warning"), "the refusal names the rewrite with the entity's own keys: " + first.text);
		MatchPlanLlmPassLoopTest.check(session.createdPlans().isEmpty(), "nothing recorded yet");
		final LocalTool.Result second = MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.planOf(MatchPlanLlmPassLoopTest.averageNode("N1", "lastName", "SUSPECTED", "NOT_MATCHED"),
			"similar last names"));
		MatchPlanLlmPassLoopTest.check(!second.isError && session.createdPlans().size() == 1, "the second unfiltered proposal is kept: " + second.text);
		final MatchPlanLlmPass.CreatedPlan plan = session.createdPlans().get(0);
		MatchPlanLlmPassLoopTest.check(plan.rationale.contains("WARNING - candidate search NOT filtered (kept by the Factory after 2 proposal(s)"), "the justification says it: " + plan.rationale);
		MatchPlanLlmPassLoopTest.check(plan.requirementsJson.contains("\"unfilteredAcceptedBy\":\"FACTORY\"") && plan.requirementsJson.contains("\"unfilteredCandidateSearchReason\":\"the proposals for this object"),
			"and the plan stores who kept it and why: " + plan.requirementsJson);
		MatchPlanLlmPassLoopTest.check(session.unplannedAttempts().isEmpty(), "the entity has a plan: no gap");
		// the model's own opt-out stays the model's: no FACTORY mark
		final MatchPlanLlmPass.Session optOut = MatchPlanLlmPassLoopTest.session(MatchPlanLlmPassLoopTest.unsafeFacts()).requireFilteredCandidateSearch();
		final String optOutCall = unfiltered.replace("\"confidence\":70", "\"confidence\":70,\"acceptUnfilteredCandidateSearch\":true,\"unfilteredCandidateSearchReason\":\"no key\"");
		MatchPlanLlmPassLoopTest.check(!MatchPlanLlmPassLoopTest.submit(optOut, optOutCall).isError && !optOut.createdPlans().get(0).requirementsJson.contains("unfilteredAcceptedBy"), "the model's opt-out is not the Factory's");
		// the cap: when the entity was about to end, the unfiltered tree is kept at once instead of ending with nothing
		final MatchPlanLlmPass.Session capped = MatchPlanLlmPassLoopTest.session(MatchPlanLlmPassLoopTest.unsafeFacts()).requireFilteredCandidateSearch().maxAttemptsPerEntity(1);
		MatchPlanLlmPassLoopTest.check(!MatchPlanLlmPassLoopTest.submit(capped, unfiltered).isError && capped.createdPlans().size() == 1, "at the cap, an unfiltered but valid tree beats no plan");
		// the rewrite needs a key: none measured, none named
		final LocalTool.Result noKeys = MatchPlanLlmPassLoopTest.submit(MatchPlanLlmPassLoopTest.session(null).requireFilteredCandidateSearch(), unfiltered);
		MatchPlanLlmPassLoopTest.check(noKeys.isError && !noKeys.text.contains("Rewrite, with the exact keys"), "without a measured key there is no rewrite to name: " + noKeys.text);
	}

	/** (d) Live run 454: the model's prose after seven refusals was shown to the user as the reason. */
	private static void aSkipAfterRefusalsIsRecordedWithTheFactorysLastRefusal() throws Exception
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassLoopTest.session(null);
		MatchPlanLlmPassLoopTest.check(MatchPlanLlmPassLoopTest.submit(session, MatchPlanLlmPassLoopTest.unknownField("Person", "zz")).isError, "refused once");
		final String prose = "Repeated validation shows a candidate-filter constraint conflict with the required branches";
		final LocalTool.Result skipped = session.skipEntity(MatchPlanLlmPassLoopTest.MAPPER.readTree("{\"entity\":\"Person\",\"reason\":\"" + prose + "\"}"));
		MatchPlanLlmPassLoopTest.check(!skipped.isError, "the skip is recorded");
		final List<MatchPlanLlmPass.Session.Attempt> attempts = session.unplannedAttempts();
		MatchPlanLlmPassLoopTest.check(attempts.size() == 1 && MatchPlanLlmPass.Session.ENDED_SKIPPED_AFTER_REFUSAL.equals(attempts.get(0).endedBy) && attempts.get(0).lastRefusal.contains("'zz'"),
			"the entity is a refused one, with the Factory's last refusal");
		boolean proseKept = false;
		for (final String note : session.notes())
		{
			proseKept |= note.equals("Person skipped: " + prose);
		}
		MatchPlanLlmPassLoopTest.check(proseKept, "the model's words stay in the notes, as an audit trail");
	}

	private static void theRefusalNamesTheWayOutFromTheFacts()
	{
		// a SAFE pair exists and the refused field belongs to it: pair it with the partner
		final LocalTool.Result withPartner = MatchPlanLlmPassLoopTest.submit(MatchPlanLlmPassLoopTest.session(MatchPlanLlmPassLoopTest.safeFacts()), MatchPlanLlmPassLoopTest.phoneMatch("firstName", "x"));
		MatchPlanLlmPassLoopTest.check(withPartner.isError && withPartner.text.contains("Pair it with nameKey.") && withPartner.text.contains("mark these pairs of independent keys SAFE")
			&& withPartner.text.contains("mobile + nameKey (12 of 12 comparable pairs agree)") && withPartner.text.contains("Otherwise end this path in SUSPECTED"), "pair it with the SAFE partner: " + withPartner.text);
		// facts complete, nothing SAFE: say so and send the path to SUSPECTED
		final LocalTool.Result none = MatchPlanLlmPassLoopTest.submit(MatchPlanLlmPassLoopTest.session(MatchPlanLlmPassLoopTest.unsafeFacts()), MatchPlanLlmPassLoopTest.phoneMatch("firstName", "x"));
		MatchPlanLlmPassLoopTest.check(none.isError && none.text.contains("No SAFE pair of independent keys is measured on this entity: end this path in SUSPECTED"), "no pair is safe: " + none.text);
		// no facts: still a way out, without claiming a measurement
		final LocalTool.Result unmeasured = MatchPlanLlmPassLoopTest.submit(MatchPlanLlmPassLoopTest.session(null), MatchPlanLlmPassLoopTest.phoneMatch("firstName", "x"));
		MatchPlanLlmPassLoopTest.check(unmeasured.isError && unmeasured.text.contains("end this path in SUSPECTED") && !unmeasured.text.contains("SAFE"), "no facts: " + unmeasured.text);
		// a sample: the same
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		for (int i = 0; i < 12; i++)
		{
			rows.add(new KeyFacts.Row("A", new String[] { "m" + i, "n" + i }));
			rows.add(new KeyFacts.Row("B", new String[] { "m" + i, "n" + i }));
		}
		final KeyFacts sampled = KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("mobile", "mobile", false), new KeyFacts.KeySpec("nameKey", "name", false)), true);
		final LocalTool.Result sample = MatchPlanLlmPassLoopTest.submit(MatchPlanLlmPassLoopTest.session(sampled), MatchPlanLlmPassLoopTest.phoneMatch("firstName", "x"));
		MatchPlanLlmPassLoopTest.check(sample.isError && sample.text.contains("sample of the table only") && sample.text.contains("end this path in SUSPECTED"), "a sample: " + sample.text);
		// a SAFE pair elsewhere but not for this field: the pairs, and the way out
		final MatchPlanLlmPass.EntityInfo entity = MatchPlanLlmPassLoopTest.person(MatchPlanLlmPassLoopTest.safeFacts());
		final String other = MeasuredMatchPath.wayOut(entity, "lastName");
		MatchPlanLlmPassLoopTest.check(other.contains("mark these pairs of independent keys SAFE") && other.contains("'lastName' is not part of any of them: end this path in SUSPECTED"), "another field: " + other);
	}

	private static void thePromptSaysPerEntityWhetherAnyPairIsSafe()
	{
		final MatchPlanLlmPass.EntityInfo safe = MatchPlanLlmPassLoopTest.person(MatchPlanLlmPassLoopTest.safeFacts());
		final MatchPlanLlmPass.EntityInfo unsafe = MatchPlanLlmPassLoopTest.person(MatchPlanLlmPassLoopTest.unsafeFacts());
		final MatchPlanLlmPass.EntityInfo none = MatchPlanLlmPassLoopTest.person(null);
		final String safePrompt = MatchPlanLlmPass.userPrompt(new MatchPlanLlmPass.ProjectInfo("P", "P", Collections.singletonList(safe), Collections.<String>emptyList(), null, false));
		final String unsafePrompt = MatchPlanLlmPass.userPrompt(new MatchPlanLlmPass.ProjectInfo("P", "P", Collections.singletonList(unsafe), Collections.<String>emptyList(), null, false));
		final String nonePrompt = MatchPlanLlmPass.userPrompt(new MatchPlanLlmPass.ProjectInfo("P", "P", Collections.singletonList(none), Collections.<String>emptyList(), null, false));
		MatchPlanLlmPassLoopTest.check(safePrompt.contains("MATCH paths on Person: 2 pair(s) of independent keys are marked SAFE (mobile + nameKey (12 of 12 comparable pairs agree)"), "SAFE pairs listed: " + safePrompt);
		MatchPlanLlmPassLoopTest.check(unsafePrompt.contains("MATCH paths on Person: NO pair of independent keys is marked SAFE on this entity") && unsafePrompt.contains("end every such path in SUSPECTED"),
			"none SAFE: " + unsafePrompt);
		MatchPlanLlmPassLoopTest.check(nonePrompt.contains("MATCH paths on Person: no key facts were measured on it") && !nonePrompt.contains("Measured key facts"), "nothing measured: " + nonePrompt);
	}

	private static void theContractTellsTheModelWhatEndsAnEntity()
	{
		final String prompt = MatchPlanLlmPass.systemPrompt();
		MatchPlanLlmPassLoopTest.check(prompt.contains("the same refusal twice in a row (or three times in the run)") && prompt.contains("recorded as refused with the last reason"), "the new breaker is stated");
		MatchPlanLlmPassLoopTest.check(!prompt.contains("a refusal repeated three times in a row ends the run outright"), "and the old, false sentence is gone");
		MatchPlanLlmPassLoopTest.check(prompt.contains("'MATCH paths on <entity>'") && prompt.contains("end such a path in SUSPECTED at once"), "the per-entity verdict line is announced");
	}
}
