package com.on.ebx.factory.staging;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import com.on.ebx.connect.core.Transformations;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.Detectors;

/**
 * Staging polish (2026-10-17, docs/UX-STAGING-POLISH.md sec 2): what a column read on the first rows of a big landing table ({@code quality.ScanGuard})
 * does to the verdicts - the profile says so, the normalisation steps and the verifier's sentence name "a sample of N rows", no code list is claimed.
 * JUnit-free: run the main, a failure throws.
 */
public final class ProfileRowSampleTest
{
	private static final TransformVerifier.Evaluator ENGINE = new TransformVerifier.Evaluator()
	{
		@Override
		public String apply(final String pPipeline, final String pValue) throws Exception
		{
			return Transformations.text(Transformations.apply(pPipeline, pValue, null));
		}
	};

	public static void main(final String[] pArgs)
	{
		ProfileRowSampleTest.theProfileKnowsItIsASample();
		ProfileRowSampleTest.noCodeListFromARowSample();
		ProfileRowSampleTest.theCaseStepSaysWhereItWasMeasured();
		ProfileRowSampleTest.theVerifierSaysWhereItWasMeasured();
		System.out.println("ProfileRowSampleTest OK");
	}

	private static void theProfileKnowsItIsASample()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("PARIS", 30, "LYON", 30);
		final NormalisationProfile whole = NormalisationProfiler.profile(values, 2L);
		check("a whole column is not sampled", !whole.sampled && whole.sampleRows == 0L);
		check("0 rows of sample changes nothing", !NormalisationProfiler.profile(values, 2L).markRowSample(0L).sampled);
		final NormalisationProfile sampled = NormalisationProfiler.profile(values, 2L).markRowSample(50000L);
		check("a row sample is sampled whatever the distinct counts say", sampled.sampled && sampled.sampleRows == 50000L);
	}

	/** Two repeated values are a code list on the whole column; on a sample the other values may simply be beyond it. */
	private static void noCodeListFromARowSample()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("Bronze", 500, "Argent", 500);
		check("whole column: a code list", NormalisationProfiler.profile(values, 2L).codes.size() == 2);
		check("row sample: none", NormalisationProfiler.profile(values, 2L).markRowSample(50000L).codes.isEmpty());
	}

	private static void theCaseStepSaysWhereItWasMeasured()
	{
		final List<Detectors.ValueCount> tickets = NormalisationProfilerTest.counts("FOURNIER", 100, "DURAND", 134);
		final List<Detectors.ValueCount> web = NormalisationProfilerTest.counts("Richard", 60, "Martin", 60, "Dupont", 40);
		final NormalisationPass.Result whole = NormalisationPass.propose(Arrays.asList(ProfileRowSampleTest.column("web", "lastName", "WEB", web, 0L),
			ProfileRowSampleTest.column("tickets", "lastName", "TICKETS", tickets, 0L)), CleaningSteps.Lookups.empty());
		final NormalisationPass.Result sampled = NormalisationPass.propose(Arrays.asList(ProfileRowSampleTest.column("web", "lastName", "WEB", web, 50000L),
			ProfileRowSampleTest.column("tickets", "lastName", "TICKETS", tickets, 50000L)), CleaningSteps.Lookups.empty());
		check("whole: a title-case step with no sample wording", whole.stepsOf("tickets").size() == 1 && !whole.stepsOf("tickets").get(0).rationale.contains("sample"));
		check("sampled: the same step", sampled.stepsOf("tickets").size() == 1 && CleaningSteps.TITLE_CASE.equals(sampled.stepsOf("tickets").get(0).kind));
		check("sampled: the rationale says where it was measured", sampled.stepsOf("tickets").get(0).rationale.contains("a sample of 50,000 rows"));
		check("sampled: never more confident than the whole read", sampled.stepsOf("tickets").get(0).confidence <= whole.stepsOf("tickets").get(0).confidence);
	}

	private static void theVerifierSaysWhereItWasMeasured()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("10/06/1963", 40, "17/02/1998", 30, "03/03/1981", 30);
		final NormalisationProfile profile = NormalisationProfiler.profile(values, -1L).markRowSample(50000L);
		final TransformVerifier.Result sampled = TransformVerifier.verify("DATE", "isodate()", values, profile, ENGINE, true, 50000L);
		check("the verdict is the same", TransformVerifier.FIXED.equals(sampled.verdict) && sampled.sampled);
		check("the sentence names the rows", sampled.reason.contains("(on a sample of 50,000 rows)") && !sampled.reason.contains("most frequent values"));
		final TransformVerifier.Result topValues = TransformVerifier.verify("DATE", "isodate()", values, profile, ENGINE, true);
		check("the old call keeps its wording", topValues.reason.contains("(most frequent values)") && !topValues.reason.contains("sample of"));
		final TransformVerifier.Result whole = TransformVerifier.verify("DATE", "isodate()", values, profile, ENGINE, false);
		check("a whole read says neither", !whole.reason.contains("sample") && !whole.reason.contains("most frequent"));
	}

	private static NormalisationPass.Column column(final String pMappingId, final String pAttribute, final String pSource, final List<Detectors.ValueCount> pValues,
		final long pSampleRows)
	{
		return new NormalisationPass.Column(pMappingId, pAttribute, pAttribute, pAttribute, pSource, "STRING", "",
			NormalisationProfiler.profile(pValues, -1L).markRowSample(pSampleRows), pValues, -1L, new LinkedHashSet<String>());
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError("ProfileRowSampleTest: " + pWhat);
		}
	}
}
