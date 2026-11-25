package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.wizard.SourceOnboarding;

/**
 * JUnit-free. Lot S10b (2026-10-19): the sentence about the sampling that follows a catalogue scan (never a failure of the scan), and the decisions that need no Connectors.
 */
public final class SampleClassificationTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		SampleClassificationTest.outcomeSentences();
		SampleClassificationTest.skippedSentences();
		SampleClassificationTest.theSampleFlowIsRecognisedByWhatItDoes();
		SampleClassificationTest.aConnectionWithoutSampleOperationIsSkippedNotFailed();
		System.out.println("SampleClassificationTest OK");
	}

	private static JsonNode json(final String pText) throws Exception
	{
		return SampleClassificationTest.MAPPER.readTree(pText);
	}

	private static void outcomeSentences() throws Exception
	{
		SampleClassificationTest.check("columns sampled", "sampling for the catalogue's classification: 9 columns sampled".equals(SampleClassification.outcome(SampleClassificationTest.json("{\"status\":\"OK\",\"count\":9,\"ok\":9}"))));
		SampleClassificationTest.check("one column", SampleClassification.outcome(SampleClassificationTest.json("{\"status\":\"OK\",\"count\":1,\"ok\":1}")).endsWith("1 column sampled"));
		final String partial = SampleClassification.outcome(SampleClassificationTest.json("{\"status\":\"PARTIAL\",\"count\":9,\"ok\":7,\"errors\":2,\"truncated\":true}"));
		SampleClassificationTest.check("errors and a cut are told: " + partial, partial.equals("sampling for the catalogue's classification: 7 columns sampled, 2 with errors, cut at 20000 columns"));
		final String failed = SampleClassification.outcome(SampleClassificationTest.json("{\"status\":\"ERROR\",\"count\":0,\"errors\":1}"));
		SampleClassificationTest.check("a run that read nothing is a skip, not a column count: " + failed, failed.equals("sampling for the catalogue's classification: skipped: the sample flow failed (1 error(s))"));
		SampleClassificationTest.check("no answer", SampleClassification.outcome(null).contains("skipped: Connectors gave no answer"));
	}

	private static void skippedSentences()
	{
		SampleClassificationTest.check("skipped with a reason", "sampling for the catalogue's classification: skipped: no reason".equals(SampleClassification.skipped("no reason")));
		SampleClassificationTest.check("skipped with nothing to say", SampleClassification.skipped(null).endsWith("skipped: no reason given"));
		final StringBuilder long400 = new StringBuilder();
		for (int i = 0; i < 400; i++)
		{
			long400.append('x');
		}
		SampleClassificationTest.check("a long reason is cut", SampleClassification.skipped(long400.toString()).length() < 400);
	}

	private static void theSampleFlowIsRecognisedByWhatItDoes() throws Exception
	{
		SampleClassificationTest.check("the sample flow", SampleClassification.isSampleFlow(SampleClassificationTest.json(
			"{\"id\":7,\"operation\":\"sample\",\"direction\":\"INBOUND\",\"feed\":true,\"child\":false,\"table\":\"/root/ColumnSample\"}")));
		SampleClassificationTest.check("not the scan", !SampleClassification.isSampleFlow(SampleClassificationTest.json(
			"{\"id\":5,\"operation\":\"catalog\",\"direction\":\"INBOUND\",\"feed\":true,\"child\":false,\"table\":\"/root/Asset\"}")));
		SampleClassificationTest.check("not the profile", !SampleClassification.isSampleFlow(SampleClassificationTest.json(
			"{\"id\":6,\"operation\":\"profile\",\"direction\":\"INBOUND\",\"feed\":true,\"child\":false,\"table\":\"/root/ProfileSnapshot\"}")));
	}

	/** A sample-file connection offers read, list and catalog only: nothing is run, nothing is created, nothing fails - the step's message says why. */
	private static void aConnectionWithoutSampleOperationIsSkippedNotFailed()
	{
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = "EcomClients";
		facts.connectorType = "sample-file";
		facts.sampleOperation = false;
		final String note = SourceOnboarder.sampleForClassification(null, null, facts);
		SampleClassificationTest.check("skipped, and the type is named: " + note,
			note.equals("sampling for the catalogue's classification: skipped: the connection (type sample-file) offers no sample operation, the catalogue has no values to classify its columns from"));
		// any failure on the way is a sentence too, never an exception: with no session at all the Connectors server cannot be reached
		facts.sampleOperation = true;
		final String failed = SourceOnboarder.sampleForClassification(null, null, facts);
		SampleClassificationTest.check("a failure to reach Connectors is a skipped note: " + failed, failed.startsWith("sampling for the catalogue's classification: skipped: "));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
