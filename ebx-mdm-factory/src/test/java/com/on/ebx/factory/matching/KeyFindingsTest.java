package com.on.ebx.factory.matching;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free: run the main, a failure throws. Lot M3 ({@code docs/MATCHING.md} &sect;56): {@link KeyFindings} on the REAL plan the language model proposed for the B2C
 * master scenario (project E2EMASTER20261017212252, MatchPlan 55: e-mail key, then names, then address keys - not one MATCH leaf, no phone) with the measured facts of the
 * 534 records. The plan finds 341 of the 565 true pairs: every ticket without an e-mail (103 of 234) is out of reach of both of its candidate keys.
 */
public final class KeyFindingsTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		KeyFindingsTest.theLivePlanGetsTheSafePathAndTheUnreachableTickets();
		KeyFindingsTest.aPlanThatAlreadyRequiresBothKeysSaysSo();
		KeyFindingsTest.aPlanWithAMatchLeafGetsNoSafePathFinding();
		KeyFindingsTest.aPlanThatReachesEveryTicketGetsNoCoverageFinding();
		KeyFindingsTest.aPlanWithoutFactsOrWithUnmeasuredKeysSaysNothing();
		KeyFindingsTest.aKeyThatIsNotSafeIsNeverOffered();
		KeyFindingsTest.smallSystemsAreNotJudged();
		System.out.println("KeyFindingsTest OK");
	}

	private static String key(final String pField, final String pAlgorithm, final int pMinimumScore)
	{
		return "{\"field\":\"" + pField + "\",\"algorithm\":\"" + pAlgorithm + "\",\"minimumScore\":" + pMinimumScore + ",\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}";
	}

	/** The tree of MatchPlan 55 as stored; {@code pN1} the fields of N1, {@code pN1True} where N1 passes. */
	private static ObjectNode plan(final String pN1Fields, final String pN1True, final String pN2OrphanFields)
	{
		try
		{
			final String tree = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":["
				+ "{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + pN1Fields + "],\"onTrue\":\"" + pN1True + "\",\"onFalse\":\"N2\"},"
				+ "{\"id\":\"N2\",\"function\":\"AVERAGE\",\"minimumScore\":82,\"fields\":["
				+ "{\"field\":\"fullNameKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":5,\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"},"
				+ "{\"field\":\"firstName\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":3,\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}],\"onTrue\":\"N3\",\"onFalse\":\"N4\"},"
				+ "{\"id\":\"N3\",\"function\":\"ALL_FIELDS\",\"fields\":[" + key("addressKeys", "ebx-addon-mame@exactFullText", 50) + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"},"
				+ "{\"id\":\"N4\",\"function\":\"ALL_FIELDS\",\"fields\":[" + key("addressKeys", "ebx-addon-mame@exactFullText", 50) + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}"
				+ pN2OrphanFields + "]}}";
			final ObjectNode node = (ObjectNode) KeyFindingsTest.MAPPER.readTree(tree);
			node.set("keyFacts", B2cKeyFixtureTest.facts().toJson());
			return node;
		}
		catch (final Exception ex)
		{
			throw new AssertionError("bad fixture: " + ex);
		}
	}

	private static ObjectNode livePlan()
	{
		return KeyFindingsTest.plan(KeyFindingsTest.key("emailKey", "ebx-addon-mame@exact", 100), "SUSPECTED", "");
	}

	private static void theLivePlanGetsTheSafePathAndTheUnreachableTickets()
	{
		final List<KeyFindings.Finding> findings = KeyFindings.check(KeyFindingsTest.livePlan());
		B2cKeyFixtureTest.check(findings.size() == 2, "one safe path and one unreachable source, got " + findings.size());
		final KeyFindings.Finding safe = findings.get(0);
		B2cKeyFixtureTest.check(KeyFindings.KIND_SAFE_AUTOMATIC_PATH.equals(safe.kind) && "N1".equals(safe.path) && "emailKey".equals(safe.key), "the e-mail path: " + safe.text());
		B2cKeyFixtureTest.check(safe.partner != null && !safe.partnerInPath && safe.pairs == 341 && safe.agree == 341 && safe.conflicts == 0, "341 of 341, none differ: " + safe.text());
		B2cKeyFixtureTest.check(safe.text().contains("Path N1 is safe to merge automatically: 341 pair(s) of records share emailKey") && safe.text().contains("require emailKey and "), safe.text());
		final KeyFindings.Finding unreachable = findings.get(1);
		B2cKeyFixtureTest.check(KeyFindings.KIND_UNREACHABLE_SOURCE.equals(unreachable.kind) && "STORES".equals(unreachable.system), "STORES: " + unreachable.text());
		B2cKeyFixtureTest.check(unreachable.unreached == 103 && unreachable.pairs == 234, "103 of 234 tickets fill neither key: " + unreachable.text());
		B2cKeyFixtureTest.check(unreachable.filterKeys.equals(Arrays.asList("emailKey", "addressKeys")), "the filter keys: " + unreachable.filterKeys);
		B2cKeyFixtureTest.check(unreachable.alternatives.contains("fullNameKey") && unreachable.alternatives.contains("mobileE164") && !unreachable.alternatives.contains("birthDate"),
			"the keys the tickets do fill: " + unreachable.alternatives);
		B2cKeyFixtureTest.check(unreachable.text().contains("103 of 234 record(s) fill none of the candidate-filter keys (emailKey, addressKeys)"), unreachable.text());
		// the address paths are not offered: six namesakes share an address
		for (final KeyFindings.Finding finding : findings)
		{
			B2cKeyFixtureTest.check(!"addressKeys".equals(finding.key), "the address key is not safe on this data");
		}
	}

	private static void aPlanThatAlreadyRequiresBothKeysSaysSo()
	{
		final ObjectNode plan = KeyFindingsTest.plan(KeyFindingsTest.key("emailKey", "ebx-addon-mame@exact", 100) + "," + KeyFindingsTest.key("fullNameKey", "ebx-addon-mame@exact", 100), "SUSPECTED", "");
		final KeyFindings.Finding safe = KeyFindings.check(plan).get(0);
		B2cKeyFixtureTest.check(safe.partnerInPath && !safe.text().contains("require"), "both keys are already required: " + safe.text());
	}

	private static void aPlanWithAMatchLeafGetsNoSafePathFinding()
	{
		final ObjectNode plan = KeyFindingsTest.plan(KeyFindingsTest.key("emailKey", "ebx-addon-mame@exact", 100) + "," + KeyFindingsTest.key("fullNameKey", "ebx-addon-mame@exact", 100), "MATCH", "");
		for (final KeyFindings.Finding finding : KeyFindings.check(plan))
		{
			B2cKeyFixtureTest.check(!KeyFindings.KIND_SAFE_AUTOMATIC_PATH.equals(finding.kind), "the plan has a MATCH leaf already: " + finding.text());
		}
	}

	private static void aPlanThatReachesEveryTicketGetsNoCoverageFinding()
	{
		final ObjectNode plan = KeyFindingsTest.plan(KeyFindingsTest.key("emailKey", "ebx-addon-mame@exact", 100), "SUSPECTED", "");
		// a fifth node: the phone, an exact key every ticket fills
		final com.fasterxml.jackson.databind.node.ArrayNode nodes = (com.fasterxml.jackson.databind.node.ArrayNode) plan.path("decisionTree").path("nodes");
		((ObjectNode) nodes.get(3)).put("onFalse", "N5");
		try
		{
			nodes.add(KeyFindingsTest.MAPPER.readTree("{\"id\":\"N5\",\"function\":\"ALL_FIELDS\",\"fields\":[" + KeyFindingsTest.key("mobileE164", "ebx-addon-mame@exact", 100)
				+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}"));
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
		for (final KeyFindings.Finding finding : KeyFindings.check(plan))
		{
			B2cKeyFixtureTest.check(!KeyFindings.KIND_UNREACHABLE_SOURCE.equals(finding.kind), "every ticket carries a phone: " + finding.text());
		}
	}

	private static void aPlanWithoutFactsOrWithUnmeasuredKeysSaysNothing()
	{
		final ObjectNode plan = KeyFindingsTest.livePlan();
		plan.remove("keyFacts");
		B2cKeyFixtureTest.check(KeyFindings.check(plan).isEmpty(), "a plan from before this lot has no finding");
		final ObjectNode other = KeyFindingsTest.plan(KeyFindingsTest.key("someOtherKey", "ebx-addon-mame@exact", 100), "SUSPECTED", "");
		for (final KeyFindings.Finding finding : KeyFindings.check(other))
		{
			B2cKeyFixtureTest.check(!KeyFindings.KIND_UNREACHABLE_SOURCE.equals(finding.kind), "a filter key that was not measured: nothing can be said about the reach");
		}
		B2cKeyFixtureTest.check(KeyFindings.check(null).isEmpty() && KeyFindings.check(KeyFindingsTest.MAPPER.createObjectNode()).isEmpty(), "no plan, no finding");
	}

	private static void aKeyThatIsNotSafeIsNeverOffered()
	{
		// the name key alone: 129 namesakes with another e-mail or phone
		final ObjectNode plan = KeyFindingsTest.plan(KeyFindingsTest.key("fullNameKey", "ebx-addon-mame@exact", 100), "SUSPECTED", "");
		for (final KeyFindings.Finding finding : KeyFindings.check(plan))
		{
			B2cKeyFixtureTest.check(!(KeyFindings.KIND_SAFE_AUTOMATIC_PATH.equals(finding.kind) && "N1".equals(finding.path)), "the name key is not safe: " + finding.text());
		}
	}

	private static void smallSystemsAreNotJudged()
	{
		final List<KeyFacts.Row> rows = new java.util.ArrayList<KeyFacts.Row>();
		for (int i = 0; i < 10; i++)
		{
			rows.add(new KeyFacts.Row("TINY", new String[] { null, "n" + i }));
		}
		final KeyFacts facts = KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("k", "k", false), new KeyFacts.KeySpec("n", "n", false)), false);
		B2cKeyFixtureTest.check(KeyFindings.unreachableSources(facts, Arrays.asList("k")).isEmpty(), "ten records are an anecdote");
	}
}
