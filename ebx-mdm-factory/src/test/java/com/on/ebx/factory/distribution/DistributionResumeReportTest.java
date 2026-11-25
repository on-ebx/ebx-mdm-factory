package com.on.ebx.factory.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free: run the main, a failure throws. What the end of a load says about the distribution targets it paused. */
public final class DistributionResumeReportTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		DistributionResumeReportTest.aCleanResumeIsSilent();
		DistributionResumeReportTest.aTargetNotRearmedIsAWarning();
		DistributionResumeReportTest.aFailedCatchUpIsAWarningButTheTargetIsArmed();
		DistributionResumeReportTest.nothingToReadIsSilent();
		System.out.println("DistributionResumeReportTest OK");
	}

	private static JsonNode json(final String pText) throws Exception
	{
		return DistributionResumeReportTest.MAPPER.readTree(pText);
	}

	private static void aCleanResumeIsSilent() throws Exception
	{
		final JsonNode ok = DistributionResumeReportTest.json("{\"targets\":[{\"target\":\"7\",\"catchUp\":{\"status\":\"DONE\",\"rows\":12}},{\"target\":\"8\"}]}");
		DistributionResumeReportTest.check("no failure", DistributionResumeReport.failures(ok).isEmpty() && DistributionResumeReport.warning(ok).isEmpty());
	}

	private static void aTargetNotRearmedIsAWarning() throws Exception
	{
		final JsonNode bad = DistributionResumeReportTest.json("{\"targets\":[{\"target\":\"7\",\"error\":\"could not set state ARMED\"}]}");
		final String warning = DistributionResumeReport.warning(bad);
		DistributionResumeReportTest.check("names the target and the state: " + warning, warning.startsWith("WARNING:") && warning.contains("target 7") && warning.contains("PAUSED")
			&& warning.contains("could not set state ARMED"));
	}

	private static void aFailedCatchUpIsAWarningButTheTargetIsArmed() throws Exception
	{
		final JsonNode bad = DistributionResumeReportTest.json("{\"targets\":[{\"target\":\"9\",\"catchUp\":{\"status\":\"FAILED\",\"message\":\"Kafka down\"}}]}");
		final String warning = DistributionResumeReport.warning(bad);
		DistributionResumeReportTest.check("catch-up wording: " + warning, warning.contains("re-armed") && warning.contains("catch-up send failed") && warning.contains("Kafka down"));
		DistributionResumeReportTest.check("one failure", DistributionResumeReport.failures(bad).size() == 1);
	}

	private static void nothingToReadIsSilent() throws Exception
	{
		DistributionResumeReportTest.check("null", DistributionResumeReport.failures(null).isEmpty() && DistributionResumeReport.warning(null).isEmpty());
		DistributionResumeReportTest.check("no targets", DistributionResumeReport.warning(DistributionResumeReportTest.json("{\"targets\":[]}")).isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
