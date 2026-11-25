package com.on.ebx.factory.staging;

import java.io.File;
import java.util.List;
import java.util.Map;

import com.on.ebx.connect.core.Transformations;
import com.on.ebx.factory.quality.Detectors;

/**
 * JUnit-free: run the main, a failure throws. The chains are run by the real Connectors mapping engine
 * ({@code Transformations.apply}, the jar the loader uses), so what is asserted is what the load would do.
 */
public final class TransformVerifierTest
{
	/** The loader's engine, with no document: enough for a chain that reads its own value. */
	private static final TransformVerifier.Evaluator ENGINE = new TransformVerifier.Evaluator()
	{
		@Override
		public String apply(final String pPipeline, final String pValue) throws Exception
		{
			return Transformations.text(Transformations.apply(pPipeline, pValue, null));
		}
	};

	public static void main(final String[] pArgs) throws Exception
	{
		TransformVerifierTest.lexicalForms();
		TransformVerifierTest.theMeasuredDefectIsoDateOnADayFirstDateIsFixed();
		TransformVerifierTest.aChainThatAlreadyConvertsIsLeftAlone();
		TransformVerifierTest.anAmbiguousOrderIsNeverApplied();
		TransformVerifierTest.aMixtureNoPatternConvertsIsFlagged();
		TransformVerifierTest.oneBadValueFlagsTheLinkWithItsRate();
		TransformVerifierTest.timestampTargetFromDayFirstDates();
		TransformVerifierTest.decimalCommaIntoADecimalTarget();
		TransformVerifierTest.integerTargetRefusesADecimal();
		TransformVerifierTest.booleanWordsAreMappedOnlyWhenAllAreKnown();
		TransformVerifierTest.digitsLosesThePlusOfAPhoneAndIsFixed();
		TransformVerifierTest.plusKeptIsFine();
		TransformVerifierTest.stripCharsWithAPlusIsRepaired();
		TransformVerifierTest.chainsThatReadOtherColumnsAreNotJudged();
		TransformVerifierTest.noPipelineVerifiesTheValueAsItIs();
		TransformVerifierTest.invalidOutputsForTheMatrix();
		TransformVerifierTest.textTargetIsNotChecked();
		TransformVerifierTest.realLoyaltyFile();
		TransformVerifierTest.anODataDateIsFixedWithEpochdate();
		System.out.println("TransformVerifierTest OK");
	}

	/** B2B test (2026-10-23): date(dd/MM/yyyy) on SAP's /Date(ms)/ converted nothing; the profile's candidate, run by the real engine, converts every value. */
	private static void anODataDateIsFixedWithEpochdate() throws Exception
	{
		final List<Detectors.ValueCount> inputs = NormalisationProfilerTest.counts("/Date(1598227200000)/", 3, "/Date(1430438400000)/", 2);
		final NormalisationProfile profile = NormalisationProfiler.profile(inputs, -1L);
		final TransformVerifier.Result result = TransformVerifier.verify("DATE", "date(dd/MM/yyyy)", inputs, profile, TransformVerifierTest.ENGINE, false);
		check("the wrong chain fails on every row and the fix is epochdate: " + result.verdict + " " + result.replacementPipeline,
			TransformVerifier.FIXED.equals(result.verdict) && result.ratePercent == 0 && "trim|epochdate".equals(result.replacementPipeline) && result.replacementRatePercent == 100);
		check("the engine gives the xs:date", "2020-08-24".equals(TransformVerifierTest.ENGINE.apply("trim|epochdate", "/Date(1598227200000)/")));
	}

	private static void lexicalForms()
	{
		check("valid ISO date", TransformVerifier.isValidFor("DATE", "1963-06-10"));
		check("a text date is no xs:date", !TransformVerifier.isValidFor("DATE", "10/06/1963"));
		check("a calendar impossibility is no date", !TransformVerifier.isValidFor("DATE", "2026-02-30"));
		check("a time part is no xs:date", !TransformVerifier.isValidFor("DATE", "1963-06-10T00:00:00"));
		check("valid xs:dateTime", TransformVerifier.isValidFor("TIMESTAMP", "2025-08-01T10:33:00"));
		check("a zone suffix is not accepted (flagged to a person)", !TransformVerifier.isValidFor("TIMESTAMP", "2025-08-01T10:33:00Z"));
		check("decimal", TransformVerifier.isValidFor("DECIMAL", "117.7") && TransformVerifier.isValidFor("DECIMAL", "-12"));
		check("a decimal comma is no xs:decimal", !TransformVerifier.isValidFor("DECIMAL", "117,70"));
		check("integer", TransformVerifier.isValidFor("INTEGER", "42") && !TransformVerifier.isValidFor("INTEGER", "42.5"));
		check("an integer beyond xs:int", !TransformVerifier.isValidFor("INTEGER", "99999999999"));
		check("boolean", TransformVerifier.isValidFor("BOOLEAN", "true") && !TransformVerifier.isValidFor("BOOLEAN", "oui"));
		check("a text target takes anything", TransformVerifier.isValidFor("STRING", "anything"));
	}

	/** Fidelite_Membres.date_naissance mapped with isodate() at 48 %: every row of the source refused at the load. */
	private static void theMeasuredDefectIsoDateOnADayFirstDateIsFixed()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("10/06/1963", 40, "17/02/1998", 30, "03/03/1981", 30, "28/12/1975", 40);
		final NormalisationProfile profile = NormalisationProfiler.profile(values, -1L);
		final TransformVerifier.Result result = TransformVerifier.verify("DATE", "isodate()", values, profile, ENGINE, false);
		check("verdict FIXED", TransformVerifier.FIXED.equals(result.verdict));
		check("the chain as it was converted nothing", result.okRows == 0L && result.ratePercent == 0 && result.checkedRows == 140L);
		check("the replacement is date(dd/MM/yyyy,yyyy-MM-dd)", "trim|date(dd/MM/yyyy,yyyy-MM-dd)".equals(result.replacementPipeline));
		check("the replacement converts 100%", result.replacementRatePercent == 100);
		check("the reason holds counts and a pipeline but no value", result.reason.contains("0 of 140") && !result.reason.contains("1963"));
		check("a fixed link needs nobody", !result.needsAPerson());
		final TransformVerifier.Result again = TransformVerifier.verify("DATE", result.replacementPipeline, values, profile, ENGINE, false);
		check("and the replacement itself verifies OK", TransformVerifier.OK.equals(again.verdict));
	}

	private static void aChainThatAlreadyConvertsIsLeftAlone()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("2026-08-27", 10, "2025-01-31", 5);
		final NormalisationProfile profile = NormalisationProfiler.profile(values, -1L);
		final TransformVerifier.Result result = TransformVerifier.verify("DATE", "isodate()", values, profile, ENGINE, false);
		check("ISO dates through isodate() are fine", TransformVerifier.OK.equals(result.verdict) && result.ratePercent == 100 && result.replacementPipeline == null);
		final List<Detectors.ValueCount> stamps = NormalisationProfilerTest.counts("2025-08-01T10:33:00Z", 5);
		final TransformVerifier.Result cut = TransformVerifier.verify("DATE", "isodate()", stamps, NormalisationProfiler.profile(stamps, -1L), ENGINE, false);
		check("an ISO date-time cut to its date is fine", TransformVerifier.OK.equals(cut.verdict));
	}

	private static void anAmbiguousOrderIsNeverApplied()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("01/02/2020", 5, "03/04/2021", 5);
		final TransformVerifier.Result result = TransformVerifier.verify("DATE", "isodate()", values, NormalisationProfiler.profile(values, -1L), ENGINE, false);
		check("AMBIGUOUS, not FIXED", TransformVerifier.AMBIGUOUS.equals(result.verdict));
		check("the candidate is named but flagged", result.replacementPipeline != null && result.reason.contains("not provable") && result.needsAPerson());
	}

	private static void aMixtureNoPatternConvertsIsFlagged()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("2020-05-01", 5, "17/02/1998", 5);
		final TransformVerifier.Result result = TransformVerifier.verify("DATE", "isodate()", values, NormalisationProfiler.profile(values, -1L), ENGINE, false);
		check("FAILING when ISO and day-first are mixed", TransformVerifier.FAILING.equals(result.verdict) && result.needsAPerson());
		check("the rate is the share of ISO rows", result.ratePercent == 50);
	}

	private static void oneBadValueFlagsTheLinkWithItsRate()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("17/02/1998", 98, "not a date", 2);
		final TransformVerifier.Result result = TransformVerifier.verify("DATE", "trim|date(dd/MM/yyyy,yyyy-MM-dd)", values, NormalisationProfiler.profile(values, -1L), ENGINE,
			false);
		check("98% is not 100%: FAILING with rate 98", TransformVerifier.FAILING.equals(result.verdict) && result.ratePercent == 98 && result.failedRows == 2L);
	}

	private static void timestampTargetFromDayFirstDates()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("17/02/1998 09:05:00", 4, "25/12/1975 23:59:59", 4);
		final TransformVerifier.Result result = TransformVerifier.verify("TIMESTAMP", "isodate()", values, NormalisationProfiler.profile(values, -1L), ENGINE, false);
		check("FIXED with the quoted in-pattern", TransformVerifier.FIXED.equals(result.verdict)
			&& "trim|date('dd/MM/yyyy HH:mm:ss',yyyy-MM-dd'T'HH:mm:ss)".equals(result.replacementPipeline));
	}

	/** Magasins_Ventes.MONTANT_TTC: 117,70 */
	private static void decimalCommaIntoADecimalTarget()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("117,70", 3, "233,04", 3, "9,00", 1);
		final TransformVerifier.Result bare = TransformVerifier.verify("DECIMAL", "", values, NormalisationProfiler.profile(values, -1L), ENGINE, false);
		check("a decimal comma left as it is would be refused", TransformVerifier.FIXED.equals(bare.verdict) && "trim|number".equals(bare.replacementPipeline));
		final TransformVerifier.Result viaNumber = TransformVerifier.verify("DECIMAL", "number", values, NormalisationProfiler.profile(values, -1L), ENGINE, false);
		check("number handles the comma", TransformVerifier.OK.equals(viaNumber.verdict));
		final List<Detectors.ValueCount> thousands = NormalisationProfilerTest.counts("1.234,56", 2, "10,5", 1);
		final TransformVerifier.Result grouped = TransformVerifier.verify("DECIMAL", "number", thousands, NormalisationProfiler.profile(thousands, -1L), ENGINE, false);
		check("number alone throws on 1.234,56: the thousand-dot variant is found", TransformVerifier.FIXED.equals(grouped.verdict)
			&& "trim|replace('.','')|replace(',','.')|number".equals(grouped.replacementPipeline));
	}

	private static void integerTargetRefusesADecimal()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("12", 3, "13.5", 1);
		final TransformVerifier.Result result = TransformVerifier.verify("INTEGER", "number", values, NormalisationProfiler.profile(values, -1L), ENGINE, false);
		check("13.5 is no integer: FAILING 75%", TransformVerifier.FAILING.equals(result.verdict) && result.ratePercent == 75);
	}

	private static void booleanWordsAreMappedOnlyWhenAllAreKnown()
	{
		final List<Detectors.ValueCount> words = NormalisationProfilerTest.counts("Oui", 3, "non", 2);
		final TransformVerifier.Result fixed = TransformVerifier.verify("BOOLEAN", "", words, NormalisationProfiler.profile(words, -1L), ENGINE, false);
		check("oui/non mapped", TransformVerifier.FIXED.equals(fixed.verdict) && "trim|map('non=false;oui=true')".equals(fixed.replacementPipeline));
		final List<Detectors.ValueCount> unknown = NormalisationProfilerTest.counts("Oui", 3, "peut-etre", 1);
		final TransformVerifier.Result flagged = TransformVerifier.verify("BOOLEAN", "", unknown, NormalisationProfiler.profile(unknown, -1L), ENGINE, false);
		check("a word outside the vocabulary: no map, FAILING", TransformVerifier.FAILING.equals(flagged.verdict) && flagged.replacementPipeline == null);
	}

	/** ECOM_Clients.mobile "+33 6 46 86 05 23" through digits(): 33646860523. */
	private static void digitsLosesThePlusOfAPhoneAndIsFixed()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("+33 6 46 86 05 23", 5, "+33 6 15 99 47 40", 5);
		final NormalisationProfile profile = NormalisationProfiler.profile(values, -1L);
		final TransformVerifier.Result raw = TransformVerifier.verify("STRING", "digits", values, profile, ENGINE, false);
		check("digits() drops the + of every row", TransformVerifier.FIXED.equals(raw.verdict) && raw.lostPlusRows == 10L && raw.ratePercent == 0);
		check("the fix is the pipeline without digits", "trim".equals(raw.replacementPipeline));
		check("the reason says the + is lost", raw.reason.contains("drops the leading +"));
		final List<Detectors.ValueCount> cleaned = NormalisationProfilerTest.counts("+33646860523", 5, "+33615994740", 5);
		final TransformVerifier.Result afterStrip = TransformVerifier.verify("STRING", "trim|digits", cleaned, NormalisationProfiler.profile(cleaned, -1L), ENGINE, false);
		check("still lost after a strip step: pipeline without digits", TransformVerifier.FIXED.equals(afterStrip.verdict) && "trim".equals(afterStrip.replacementPipeline));
	}

	private static void plusKeptIsFine()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("+33646860523", 5, "0660041941", 5);
		final TransformVerifier.Result result = TransformVerifier.verify("STRING", "trim", values, NormalisationProfiler.profile(values, -1L), ENGINE, false);
		check("+33 kept: OK", TransformVerifier.OK.equals(result.verdict) && result.lostPlusRows == 0L);
	}

	private static void stripCharsWithAPlusIsRepaired()
	{
		final NormalisationProfile phones = NormalisationProfiler.profile(NormalisationProfilerTest.counts("+33 6 46 86 05 23", 5), -1L);
		check("the plus is removed from the argument", " .()-".equals(TransformVerifier.repairStripChars(" .+()-", phones)));
		check("an argument without + is left", TransformVerifier.repairStripChars(" .()-", phones) == null);
		final NormalisationProfile notPhones = NormalisationProfiler.profile(NormalisationProfilerTest.counts("a+b", 5), -1L);
		check("a column that is not a phone is not touched", TransformVerifier.repairStripChars("+", notPhones) == null);
	}

	private static void chainsThatReadOtherColumnsAreNotJudged()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("a", 3);
		check("concat is not verifiable", TransformVerifier.UNVERIFIABLE.equals(TransformVerifier.verify("DATE", "concat(a,b)", values, null, ENGINE, false).verdict));
		check("fk is not verifiable", TransformVerifier.UNVERIFIABLE.equals(TransformVerifier.verify("INTEGER", "fk(T,/id)", values, null, ENGINE, false).verdict));
		final TransformVerifier.Evaluator needsContext = new TransformVerifier.Evaluator()
		{
			@Override
			public String apply(final String pPipeline, final String pValue) throws Exception
			{
				throw new NullPointerException("no context");
			}
		};
		check("an engine that needs a context is not a failure of the value",
			TransformVerifier.UNVERIFIABLE.equals(TransformVerifier.verify("DATE", "trim", values, null, needsContext, false).verdict));
	}

	private static void noPipelineVerifiesTheValueAsItIs()
	{
		final List<Detectors.ValueCount> iso = NormalisationProfilerTest.counts("2020-01-02", 3);
		check("no pipeline, ISO date: OK", TransformVerifier.OK.equals(TransformVerifier.verify("DATE", "", iso, null, ENGINE, false).verdict));
		final List<Detectors.ValueCount> text = NormalisationProfilerTest.counts("02/01/2020", 3);
		check("no pipeline, text date, no profile: FAILING", TransformVerifier.FAILING.equals(TransformVerifier.verify("DATE", null, text, null, ENGINE, false).verdict));
	}

	private static void invalidOutputsForTheMatrix()
	{
		check("two of three invalid", TransformVerifier.invalidOutputs("DATE", java.util.Arrays.asList("10/06/1963", "1963-06-10", "17/02/1998")) == 2);
		check("empty outputs are not counted (the emptied flag owns them)", TransformVerifier.invalidOutputs("DATE", java.util.Arrays.asList("", null, "1963-06-10")) == 0);
		check("a text target never fails", TransformVerifier.invalidOutputs("STRING", java.util.Arrays.asList("whatever")) == 0);
	}

	private static void textTargetIsNotChecked()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("Dupont", 3);
		check("STRING, not phone-like: NOT_CHECKED", TransformVerifier.NOT_CHECKED.equals(TransformVerifier.verify("STRING", "trim", values,
			NormalisationProfiler.profile(values, -1L), ENGINE, false).verdict));
	}

	/** The real loyalty file when present: date_naissance through isodate() is the measured 140-row refusal. */
	private static void realLoyaltyFile() throws Exception
	{
		final File file = new File(System.getProperty("mdmf.b2cDir", "test-landing-b2c-2026-10-17/fichiers"), "Fidelite_Membres.csv");
		if (!file.isFile())
		{
			System.out.println("  (loyalty file not found: real-file check skipped)");
			return;
		}
		final List<Map<String, String>> members = NormalisationProfilerTest.csv(file, ',');
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.column(members, "date_naissance");
		final NormalisationProfile profile = NormalisationProfiler.profile(values, -1L);
		final TransformVerifier.Result result = TransformVerifier.verify("DATE", "isodate()", values, profile, ENGINE, false);
		check("real file: isodate() converts nothing", result.checkedRows == 140L && result.okRows == 0L);
		check("real file: FIXED with date(dd/MM/yyyy,yyyy-MM-dd)", TransformVerifier.FIXED.equals(result.verdict)
			&& "trim|date(dd/MM/yyyy,yyyy-MM-dd)".equals(result.replacementPipeline));
		// the other links of the same live proposal, on the real files
		final File dir = file.getParentFile();
		final List<Detectors.ValueCount> amounts = NormalisationProfilerTest.column(NormalisationProfilerTest.csv(new File(dir, "Magasins_Ventes.csv"), ';'), "MONTANT_TTC");
		final TransformVerifier.Result amount = TransformVerifier.verify("DECIMAL", "number", amounts, NormalisationProfiler.profile(amounts, -1L), ENGINE, false);
		check("real file: number() on the ticket amounts (decimal comma) is fine", TransformVerifier.OK.equals(amount.verdict));
		final File web = new File(dir, "ECOM_Clients.json");
		final List<Detectors.ValueCount> created = NormalisationPassTest.jsonStrings(web, "createdAt");
		final TransformVerifier.Result stamp = TransformVerifier.verify("TIMESTAMP", "isodatetime", created, NormalisationProfiler.profile(created, -1L), ENGINE, false);
		check("real file: isodatetime on the web createdAt (ISO with Z) is fine", TransformVerifier.OK.equals(stamp.verdict));
		final List<Detectors.ValueCount> birth = NormalisationPassTest.jsonStrings(web, "birthDate");
		final TransformVerifier.Result iso = TransformVerifier.verify("DATE", "isodate()", birth, NormalisationProfiler.profile(birth, -1L), ENGINE, false);
		check("real file: isodate() on the web birth dates (already ISO) is fine", TransformVerifier.OK.equals(iso.verdict));
		final List<Detectors.ValueCount> mobiles = NormalisationPassTest.jsonStrings(web, "mobile");
		final TransformVerifier.Result mobile = TransformVerifier.verify("STRING", "digits()", mobiles, NormalisationProfiler.profile(mobiles, -1L), ENGINE, false);
		check("real file: digits() on the web mobile numbers drops the + of every row", TransformVerifier.FIXED.equals(mobile.verdict) && mobile.lostPlusRows == 160L
			&& "trim".equals(mobile.replacementPipeline));
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("TransformVerifierTest: " + pLabel);
		}
	}
}
