package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free: run the main, a failure throws. fix/match-plan-requirements-length (2026-10-12): the plan is stored whole or refused, never cut. */
public final class StoredJsonFitTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		// the limits are the maxLen of tools/build_xsd.py (MdmFactory.xsd)
		StoredJsonFitTest.check("requirements limit", StoredJsonFit.REQUIREMENTS_MAX_LENGTH == 100000);
		StoredJsonFitTest.check("evidence limit", StoredJsonFit.EVIDENCE_MAX_LENGTH == 100000);
		StoredJsonFitTest.check("binding limit", StoredJsonFit.BINDING_MAX_LENGTH == 4000);

		// fits
		StoredJsonFitTest.check("null fits", StoredJsonFit.fits(null, 10));
		StoredJsonFitTest.check("exactly at the limit fits", StoredJsonFit.fits("0123456789", 10));
		StoredJsonFitTest.check("one over does not fit", !StoredJsonFit.fits("01234567890", 10));

		// the live incident: a 14-node plan, 4000-char field. A plan this size now fits the new limit whole, and would not have fit the old one.
		final String plan14 = StoredJsonFitTest.plan(14, 700);
		StoredJsonFitTest.check("the 14-node plan is over the old limit of 4000: " + plan14.length(), plan14.length() > 4000);
		StoredJsonFitTest.check("the 14-node plan fits the new limit", StoredJsonFit.fits(plan14, StoredJsonFit.REQUIREMENTS_MAX_LENGTH));
		StoredJsonFitTest.check("and is stored whole: still parseable JSON", StoredJsonFitTest.MAPPER.readTree(StoredJsonFit.requireFit(plan14, StoredJsonFit.REQUIREMENTS_MAX_LENGTH))
			.path("decisionTree").path("nodes").size() == 14);
		StoredJsonFitTest.check("a 100-node plan fits comfortably", StoredJsonFit.fits(StoredJsonFitTest.plan(100, 700), StoredJsonFit.REQUIREMENTS_MAX_LENGTH));
		StoredJsonFitTest.check("no refusal for a plan that fits", StoredJsonFit.planRefusal(plan14, "[]") == null);

		// node count: the tree, else the old tiers array, else 0
		StoredJsonFitTest.check("tree node count", StoredJsonFit.planNodeCount(plan14) == 14);
		StoredJsonFitTest.check("old tiers count", StoredJsonFit.planNodeCount("{\"tiers\":[{},{},{}]}") == 3);
		StoredJsonFitTest.check("unparseable counts 0", StoredJsonFit.planNodeCount("{\"decisionTree\":{\"nodes\":[{...(truncated)") == 0);
		StoredJsonFitTest.check("null counts 0", StoredJsonFit.planNodeCount(null) == 0 && StoredJsonFit.planNodeCount("") == 0);

		// refusal: the wording the person reads, with the three numbers
		final String huge = StoredJsonFitTest.plan(14, 9000);
		final String refusal = StoredJsonFit.planRefusal(huge, "[]");
		StoredJsonFitTest.check("a plan over the limit is refused", refusal != null);
		StoredJsonFitTest.check("refusal says the length, the limit and the node count: " + refusal,
			refusal.equals("plan of " + huge.length() + " characters exceeds the field limit of 100000; the model proposed 14 nodes"));
		final String directRefusal = StoredJsonFit.planRefusal(plan14, 4000);
		StoredJsonFitTest.check("same wording against the OLD limit: " + directRefusal,
			directRefusal.equals("plan of " + plan14.length() + " characters exceeds the field limit of 4000; the model proposed 14 nodes"));

		// evidence too big: refused with the plan's node count
		final String bigEvidence = StoredJsonFitTest.repeat('e', StoredJsonFit.EVIDENCE_MAX_LENGTH + 1);
		final String evidenceRefusal = StoredJsonFit.planRefusal(plan14, bigEvidence);
		StoredJsonFitTest.check("oversize evidence refuses the plan: " + evidenceRefusal,
			evidenceRefusal != null && evidenceRefusal.startsWith("evidence of the plan of 100001 characters exceeds the field limit of 100000")
				&& evidenceRefusal.endsWith("the model proposed 14 nodes"));
		StoredJsonFitTest.check("null evidence is fine", StoredJsonFit.planRefusal(plan14, null) == null);

		// a generic parsed JSON (binding)
		StoredJsonFitTest.check("binding refusal wording", StoredJsonFit.refusal("binding", StoredJsonFitTest.repeat('b', 4001), 4000)
			.equals("binding of 4001 characters exceeds the field limit of 4000"));

		// requireFit never hands back a cut: it throws when a caller forgot to test
		boolean threw = false;
		try
		{
			StoredJsonFit.requireFit(huge, 5000);
		}
		catch (final IllegalStateException ex)
		{
			threw = true;
		}
		StoredJsonFitTest.check("requireFit throws instead of cutting", threw);
		StoredJsonFitTest.check("requireFit returns the very same text when it fits", StoredJsonFit.requireFit(plan14, 100000) == plan14);

		System.out.println("StoredJsonFitTest OK");
	}

	/** A minified plan of {@code pNodes} nodes, each carrying {@code pFill} characters of filler (a long field list) - shaped like MatchPlanLlmPass's requirements. */
	private static String plan(final int pNodes, final int pFill)
	{
		final ObjectNode root = StoredJsonFitTest.MAPPER.createObjectNode();
		final ObjectNode tree = root.putObject("decisionTree");
		tree.put("root", "n1");
		final ArrayNode nodes = tree.putArray("nodes");
		for (int i = 1; i <= pNodes; i++)
		{
			final ObjectNode node = nodes.addObject();
			node.put("id", "n" + i);
			node.put("function", "ALL_FIELDS");
			node.put("note", StoredJsonFitTest.repeat('x', pFill));
		}
		root.putArray("clusteringFields").add("lastName").add("firstName");
		return root.toString();
	}

	private static String repeat(final char pChar, final int pCount)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pCount; i++)
		{
			out.append(pChar);
		}
		return out.toString();
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
