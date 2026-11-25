package com.on.ebx.factory.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class PublishDecisionTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		PublishDecisionTest.publishedTrueReadsAsPublished();
		PublishDecisionTest.publishedFalseReadsAsNotPublished();
		PublishDecisionTest.missingPublishedFieldReadsAsNotPublished();
		PublishDecisionTest.nullResponseReadsAsNotPublished();
		System.out.println("PublishDecisionTest OK");
	}

	private static void publishedTrueReadsAsPublished()
	{
		final ObjectNode response = PublishDecisionTest.MAPPER.createObjectNode();
		response.put("published", true);
		response.put("created", true);
		PublishDecisionTest.check("published:true -> published", PublishDecision.published(response));
	}

	/**
	 * The exact defect: a gate block (validate/compile refused) is a normal, non-error response with
	 * {@code published:false} - never a transport error. A caller trusting "no error" alone would read this
	 * as success.
	 */
	private static void publishedFalseReadsAsNotPublished()
	{
		final ObjectNode response = PublishDecisionTest.MAPPER.createObjectNode();
		response.put("published", false);
		response.put("found", true);
		response.put("code", "COMPILE_FAILED");
		PublishDecisionTest.check("published:false -> not published, even with no transport error", !PublishDecision.published(response));
	}

	private static void missingPublishedFieldReadsAsNotPublished()
	{
		final ObjectNode response = PublishDecisionTest.MAPPER.createObjectNode();
		response.put("someOtherField", "x");
		PublishDecisionTest.check("no published field at all -> not published (never assumed true)", !PublishDecision.published(response));
	}

	private static void nullResponseReadsAsNotPublished()
	{
		PublishDecisionTest.check("null response -> not published", !PublishDecision.published(null));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
