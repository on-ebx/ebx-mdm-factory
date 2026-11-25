package com.on.ebx.factory.staging;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.on.ebx.connect.core.Transformations;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.Detectors;

/**
 * Staging preflight lot H1 (2026-10-17): the pure decision of the check before the build, run on the real B2C files of 2026-10-17 with the REAL Connectors engine
 * ({@code Transformations.apply}, the code the load runs). The measured defect: an accepted {@code isodate()} link on the loyalty {@code date_naissance} ({@code dd/MM/yyyy})
 * refused 140 rows at the load - the check must say so BEFORE, with the fix that converts them. JUnit-free: run the main, a failure throws.
 */
public final class PreflightCheckTest
{
	private static final TransformVerifier.Evaluator ENGINE = new TransformVerifier.Evaluator()
	{
		@Override
		public String apply(final String pPipeline, final String pValue) throws Exception
		{
			return Transformations.text(Transformations.apply(pPipeline, pValue, null));
		}
	};

	private static final CleaningSteps.Lookups NO_LOOKUPS = CleaningSteps.Lookups.empty();

	public static void main(final String[] pArgs) throws Exception
	{
		PreflightCheckTest.theMeasuredDefectOnTheRealLoyaltyFile();
		PreflightCheckTest.theFixedPipelineReplaysClean();
		PreflightCheckTest.aTypeDrivenLinkWithNoPipelineIsRefusedToo();
		PreflightCheckTest.anAmbiguousOrderIsNeverOfferedAsAFix();
		PreflightCheckTest.theRealTicketAmountsAndWebDatesAreFine();
		PreflightCheckTest.digitsLosesThePlusAndIsOnlyAWarning();
		PreflightCheckTest.aCleaningStepThatDropsThePlusIsAWarningWithNoPipelineFix();
		PreflightCheckTest.aValueLongerThanTheFieldIsBlocking();
		PreflightCheckTest.aSampledColumnSaysAtLeast();
		PreflightCheckTest.anEmptyMandatoryAttributeIsBlockingPerSource();
		PreflightCheckTest.duplicatesAndALostPlusStayWarnings();
		PreflightCheckTest.aChainThatCannotBeReplayedIsNotClaimed();
		PreflightCheckTest.theReportDecidesAndRoundTrips();
		PreflightCheckTest.anEntityNoMappingFeedsIsAWarningNeverBlocking();
		PreflightCheckTest.anAttributeTheOtherSourcesFeedIsSaidWhenASourceLeavesItsFilledColumnOut();
		PreflightCheckTest.thePartnerNumberOfASubRecordIsNotTakenForAPhone();
		PreflightCheckTest.vatNumbersInASirenAttributeAreSaid();
		System.out.println("PreflightCheckTest OK");
	}

	/**
	 * B2B test (2026-10-23): "Organisation.contactPhone: fed by SALESFORCE and WEB, not by SAP although SAP.to_BusinessPartnerTax_results_0__BusinessPartner is filled (150 of 150)".
	 * That column is the partner number repeated in the tax sub-record (0001690977 = the row's own BusinessPartner).
	 */
	private static void thePartnerNumberOfASubRecordIsNotTakenForAPhone()
	{
		final List<PreflightLink> links = Arrays.asList(PreflightCheckTest.phoneLink("Organisation", "contactPhone", "SALESFORCE", "Phone", PreflightCheckTest.phones()),
			PreflightCheckTest.phoneLink("Organisation", "contactPhone", "WEB", "contactPhone", PreflightCheckTest.phones()),
			PreflightCheckTest.phoneLink("Organisation", "name", "SAP", "OrganizationBPName1", PreflightCheckTest.words()));
		final List<Detectors.ValueCount> partner = NormalisationProfilerTest.counts("0001690977", 1, "0001691014", 1, "0001691051", 1, "0001691088", 1);
		final java.util.Set<String> keys = new java.util.HashSet<String>(Arrays.asList("0001690977", "0001691014", "0001691051", "0001691088"));
		check("the column holds its table's keys", PreflightCheck.holdsTableKeys(partner, keys) && !PreflightCheck.holdsTableKeys(partner, new java.util.HashSet<String>())
			&& !PreflightCheck.holdsTableKeys(NormalisationProfilerTest.counts("0612345678", 4), keys));
		final NormalisationProfile profile = NormalisationProfiler.profile(partner, -1L);
		check("and its profile is no longer phone-like", !profile.isPhoneLike());
		final List<PreflightCheck.UnmappedColumn> unmapped = Arrays.asList(new PreflightCheck.UnmappedColumn("Organisation", "SAP", "SAP_BusinessPartner",
			"to_BusinessPartnerTax_results_0__BusinessPartner", 150L, 150L, profile, true));
		check("no warning names it", PreflightCheck.notFedBySource(links, unmapped).isEmpty());
		// even a column whose values happen to look like phones is skipped once it is known to repeat the keys
		final List<PreflightCheck.UnmappedColumn> phoneLooking = Arrays.asList(new PreflightCheck.UnmappedColumn("Organisation", "SAP", "SAP_BusinessPartner", "copyOfKey", 150L,
			150L, PreflightCheckTest.phones(), true));
		check("an identifier column is never offered for a phone attribute", PreflightCheck.notFedBySource(links, phoneLooking).isEmpty());
	}

	/** B2B test (2026-10-23): SAP's tax number gave Organisation.siren 25 VAT numbers among 150 values; Salesforce and the website gave nine digits only. */
	private static void vatNumbersInASirenAttributeAreSaid() throws Exception
	{
		final TransformVerifier.Evaluator same = new TransformVerifier.Evaluator()
		{
			@Override
			public String apply(final String pPipeline, final String pValue)
			{
				return pValue.trim();
			}
		};
		final PreflightLink sap = new PreflightLink("m-sap", "Organisation", "siren", "STRING", null, false, "SAP", "SAP_BusinessPartner",
			"to_BusinessPartnerTax_results_0__BPTaxNumber", null, "", NormalisationProfilerTest.counts("552100554", 125, "DE123456789", 10, "ESB1234567X", 8, "IT12345678901", 4,
				"BE0801007390", 3), false, 150L, 150L, false, false, null);
		final PreflightLink sf = new PreflightLink("m-sf", "Organisation", "siren", "STRING", null, false, "SALESFORCE", "SF_Accounts", "SIRET__c", null, "",
			NormalisationProfilerTest.counts("934462136", 78), false, 120L, 78L, false, false, null);
		final PreflightLink web = new PreflightLink("m-web", "Organisation", "siren", "STRING", null, false, "WEB", "WEB_AccountRequests", "siren", null, "",
			NormalisationProfilerTest.counts("552100554", 63), false, 90L, 63L, false, false, null);
		final List<PreflightFinding> replays = new ArrayList<PreflightFinding>();
		for (final PreflightLink link : Arrays.asList(sap, sf, web))
		{
			replays.add(PreflightCheck.check(link, same, CleaningSteps.Lookups.empty()));
		}
		final List<PreflightFinding> outliers = PreflightCheck.shapeOutliers(replays);
		check("one warning, on the SAP link: " + outliers.size(), outliers.size() == 1 && "SAP".equals(outliers.get(0).sourceSystem));
		final PreflightFinding finding = outliers.get(0);
		final PreflightFinding.Issue issue = finding.issue(PreflightFinding.SHAPE_OUTLIERS);
		check("25 of 150, expected 9(9), the other sources named: " + (issue == null ? null : issue.detail), issue != null && !issue.blocking && issue.rows == 25L
			&& issue.ofRows == 150L && "9(9)".equals(finding.expectedShape) && finding.fedBy.equals(Arrays.asList("SALESFORCE", "WEB"))
			&& issue.detail.startsWith("25 of 150 values SAP.to_BusinessPartnerTax_results_0__BPTaxNumber gives Organisation.siren do not have the shape 9(9)"));
		final PreflightReport back = PreflightReport.fromJson(PreflightReport.of("B2B", 1L, outliers, null, null).toJson().toString());
		check("it survives its JSON with its shape", back != null && "9(9)".equals(back.findings.get(0).expectedShape) && back.findings.get(0).issue(PreflightFinding.SHAPE_OUTLIERS).rows == 25L);
		// names are not identifiers: nothing is compared
		final PreflightLink n1 = new PreflightLink("m-n1", "Organisation", "name", "STRING", null, false, "SAP", "T", "n", null, "",
			NormalisationProfilerTest.counts("Valmor Logistique SAS", 3, "Fiozel SNC", 2), false, 5L, 5L, false, false, null);
		final PreflightLink n2 = new PreflightLink("m-n2", "Organisation", "name", "STRING", null, false, "WEB", "T2", "n", null, "",
			NormalisationProfilerTest.counts("valmor", 3), false, 3L, 3L, false, false, null);
		check("names say nothing", PreflightCheck.shapeOutliers(Arrays.asList(PreflightCheck.check(n1, same, CleaningSteps.Lookups.empty()),
			PreflightCheck.check(n2, same, CleaningSteps.Lookups.empty()))).isEmpty());
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	private static File dir()
	{
		return new File(System.getProperty("mdmf.b2cDir", "test-landing-b2c-2026-10-17/fichiers"));
	}

	private static boolean haveFiles()
	{
		final boolean present = new File(PreflightCheckTest.dir(), "Fidelite_Membres.csv").isFile() && new File(PreflightCheckTest.dir(), "Magasins_Ventes.csv").isFile()
			&& new File(PreflightCheckTest.dir(), "ECOM_Clients.json").isFile();
		if (!present)
		{
			System.out.println("  (B2C files not found: real-file checks skipped)");
		}
		return present;
	}

	private static List<Detectors.ValueCount> loyalty(final String pColumn) throws Exception
	{
		return NormalisationProfilerTest.column(NormalisationProfilerTest.csv(new File(PreflightCheckTest.dir(), "Fidelite_Membres.csv"), ','), pColumn);
	}

	private static List<Detectors.ValueCount> tickets(final String pColumn) throws Exception
	{
		return NormalisationProfilerTest.column(NormalisationProfilerTest.csv(new File(PreflightCheckTest.dir(), "Magasins_Ventes.csv"), ';'), pColumn);
	}

	private static PreflightLink link(final String pSource, final String pAttribute, final String pColumn, final String pType, final Integer pMaxLength, final boolean pMandatory,
		final List<CleaningSteps.Step> pSteps, final String pPipeline, final List<Detectors.ValueCount> pValues, final boolean pBusinessKey)
	{
		return new PreflightLink("m-" + pAttribute, "Customer", pAttribute, pType, pMaxLength, pMandatory, pSource, "T_" + pSource, pColumn, pSteps, pPipeline, pValues, false, -1L,
			-1L, pBusinessKey, false, NormalisationProfiler.profile(pValues, -1L));
	}

	private static long total(final List<Detectors.ValueCount> pValues)
	{
		long total = 0L;
		for (final Detectors.ValueCount value : pValues)
		{
			total += value.count;
		}
		return total;
	}

	// ------------------------------------------------------------------------------------------------------- the cases

	/** The defect of the live test: ONE accepted isodate() link on dd/MM/yyyy = 140 refused rows. The check says 140, names the format, and proposes the fix. */
	private static void theMeasuredDefectOnTheRealLoyaltyFile() throws Exception
	{
		if (!PreflightCheckTest.haveFiles())
		{
			return;
		}
		final List<Detectors.ValueCount> birth = PreflightCheckTest.loyalty("date_naissance");
		final PreflightFinding finding = PreflightCheck.check(PreflightCheckTest.link("LOYALTY", "birthDate", "date_naissance", "DATE", null, false, null, "isodate()", birth, false),
			PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		final PreflightFinding.Issue conversion = finding.issue(PreflightFinding.CONVERSION);
		check("isodate() on dd/MM/yyyy: a blocking CONVERSION issue", conversion != null && conversion.blocking);
		check("140 blocking rows (the live count), got " + finding.blockingRows(), finding.blockingRows() == 140L && conversion.rows == 140L && conversion.ofRows == 140L);
		check("the finding blocks", finding.blocking());
		check("it says how the dates are written", "dd/MM/yyyy".equals(finding.written) && "date".equals(finding.writtenKind));
		check("the fix is the proven day-first pattern, got " + finding.fixPipeline, "trim|date(dd/MM/yyyy,yyyy-MM-dd)".equals(finding.fixPipeline));
		check("and it can be applied in one click", finding.fixable());
		check("the finding names the chain it judged", "isodate()".equals(finding.pipeline));
		check("no value in any text of the finding", !finding.toJson().toString().matches(".*\\d{2}/\\d{2}/\\d{4}.*"));

		final PreflightReport report = PreflightReport.of("B2C", 1L, Arrays.asList(finding), null, null);
		check("the report blocks with 140 rows on 1 link", report.blocking() && report.blockingRows() == 140L && report.blockingLinks() == 1);
		check("the report offers the one fix", report.fixable().size() == 1 && report.byMapping("m-birthDate") == finding);
	}

	private static void theFixedPipelineReplaysClean() throws Exception
	{
		if (!PreflightCheckTest.haveFiles())
		{
			return;
		}
		final PreflightFinding finding = PreflightCheck.check(PreflightCheckTest.link("LOYALTY", "birthDate", "date_naissance", "DATE", null, false, null,
			"trim|date(dd/MM/yyyy,yyyy-MM-dd)", PreflightCheckTest.loyalty("date_naissance"), false), PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		check("the fixed link replays clean", finding.issues.isEmpty() && !finding.blocking() && !finding.fixable());
		final PreflightReport report = PreflightReport.of("B2C", 1L, Arrays.asList(finding), null, null);
		check("a clean report neither blocks nor warns", !report.blocking() && report.clean() && report.warnings() == 0 && report.linksChecked == 1);
	}

	/** An old row with no pipeline (type-driven, nothing translated): the raw text goes to a DATE field and EBX refuses it. */
	private static void aTypeDrivenLinkWithNoPipelineIsRefusedToo() throws Exception
	{
		if (!PreflightCheckTest.haveFiles())
		{
			return;
		}
		final PreflightFinding finding = PreflightCheck.check(PreflightCheckTest.link("LOYALTY", "birthDate", "date_naissance", "DATE", null, false, null, "",
			PreflightCheckTest.loyalty("date_naissance"), false), PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		check("no pipeline into a DATE: 140 refused rows, " + finding.blockingRows(), finding.blockingRows() == 140L);
		check("and the same fix is proposed", "trim|date(dd/MM/yyyy,yyyy-MM-dd)".equals(finding.fixPipeline));
	}

	/** Nothing above 12 in either position: dd/MM or MM/dd cannot be proven. The candidate is kept, never offered. */
	private static void anAmbiguousOrderIsNeverOfferedAsAFix()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("01/02/2020", 3, "03/04/2021", 2, "05/06/2022", 1);
		final PreflightFinding finding = PreflightCheck.check(PreflightCheckTest.link("S", "d", "d", "DATE", null, false, null, "isodate()", values, false),
			PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		check("6 refused rows", finding.blockingRows() == 6L);
		check("an ambiguous candidate is recorded", finding.ambiguousPipeline != null && finding.ambiguousPipeline.contains("date("));
		check("but it is not a fix", finding.fixPipeline == null && !finding.fixable());
	}

	private static void theRealTicketAmountsAndWebDatesAreFine() throws Exception
	{
		if (!PreflightCheckTest.haveFiles())
		{
			return;
		}
		final PreflightFinding amount = PreflightCheck.check(PreflightCheckTest.link("TICKETS", "amount", "MONTANT_TTC", "DECIMAL", null, false, null, "number",
			PreflightCheckTest.tickets("MONTANT_TTC"), false), PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		check("number() on the ticket amounts (decimal comma) is fine", amount.issues.isEmpty());
		check("and the column is described as a decimal comma", "decimal comma".equals(amount.written) && "number".equals(amount.writtenKind));
		final List<Detectors.ValueCount> raw = PreflightCheckTest.tickets("MONTANT_TTC");
		final PreflightFinding noNumber = PreflightCheck.check(PreflightCheckTest.link("TICKETS", "amount", "MONTANT_TTC", "DECIMAL", null, false, null, "", raw, false),
			PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		check("the raw decimal comma into a DECIMAL is refused (all rows with a comma)", noNumber.blockingRows() > 0L && noNumber.blockingRows() <= PreflightCheckTest.total(raw));
		check("number() is its fix", noNumber.fixable() && "trim|number".equals(noNumber.fixPipeline));
		final List<Detectors.ValueCount> birth = NormalisationPassTest.jsonStrings(new File(PreflightCheckTest.dir(), "ECOM_Clients.json"), "birthDate");
		check("isodate() on the already ISO web birth dates is fine", PreflightCheck.check(PreflightCheckTest.link("WEB", "birthDate", "birthDate", "DATE", null, false, null,
			"isodate()", birth, false), PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS).issues.isEmpty());
	}

	/** digits() drops the + of every web mobile: nothing is refused (a warning), the fix is the chain without digits. */
	private static void digitsLosesThePlusAndIsOnlyAWarning() throws Exception
	{
		if (!PreflightCheckTest.haveFiles())
		{
			return;
		}
		final List<Detectors.ValueCount> mobiles = NormalisationPassTest.jsonStrings(new File(PreflightCheckTest.dir(), "ECOM_Clients.json"), "mobile");
		final PreflightFinding finding = PreflightCheck.check(PreflightCheckTest.link("WEB", "mobile", "mobile", "STRING", null, false, null, "digits()", mobiles, false),
			PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		final PreflightFinding.Issue plus = finding.issue(PreflightFinding.LOST_PLUS);
		check("the lost + is reported on 160 rows, got " + (plus == null ? "none" : String.valueOf(plus.rows)), plus != null && plus.rows == 160L);
		check("as a warning, never a block", !plus.blocking && !finding.blocking() && finding.blockingRows() == 0L);
		check("the fix keeps the + (the chain without digits)", "trim".equals(finding.fixPipeline));
		final PreflightReport report = PreflightReport.of("B2C", 1L, Arrays.asList(finding), null, null);
		check("a report with only that warning does not stop the build", !report.blocking() && report.warnings() == 1 && !report.clean() && report.fixable().isEmpty());
	}

	/** A cleaning step (STRIP_CHARS with a +) removes the + before the chain: reported on the raw values, nothing a pipeline can fix. */
	private static void aCleaningStepThatDropsThePlusIsAWarningWithNoPipelineFix() throws Exception
	{
		if (!PreflightCheckTest.haveFiles())
		{
			return;
		}
		final List<Detectors.ValueCount> mobiles = NormalisationPassTest.jsonStrings(new File(PreflightCheckTest.dir(), "ECOM_Clients.json"), "mobile");
		final List<CleaningSteps.Step> steps = new ArrayList<CleaningSteps.Step>();
		steps.add(new CleaningSteps.Step(CleaningSteps.STRIP_CHARS, " .+()-"));
		final PreflightFinding finding = PreflightCheck.check(PreflightCheckTest.link("WEB", "mobile", "mobile", "STRING", null, false, steps, "", mobiles, false),
			PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		final PreflightFinding.Issue plus = finding.issue(PreflightFinding.LOST_PLUS);
		check("the step's loss of the + is seen on the raw values", plus != null && plus.rows == 160L && !plus.blocking);
		check("no pipeline fix is invented", !finding.fixable() && finding.fixPipeline == null);
		final List<CleaningSteps.Step> kept = new ArrayList<CleaningSteps.Step>();
		kept.add(new CleaningSteps.Step(CleaningSteps.STRIP_CHARS, " .()-"));
		check("without the + in the step's characters the link is clean", PreflightCheck.check(PreflightCheckTest.link("WEB", "mobile", "mobile", "STRING", null, false, kept, "",
			mobiles, false), PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS).issues.isEmpty());
	}

	private static void aValueLongerThanTheFieldIsBlocking() throws Exception
	{
		if (!PreflightCheckTest.haveFiles())
		{
			return;
		}
		final List<Detectors.ValueCount> names = PreflightCheckTest.loyalty("nom");
		long longer = 0L;
		for (final Detectors.ValueCount value : names)
		{
			if (value.value.length() > 6)
			{
				longer += value.count;
			}
		}
		final PreflightFinding finding = PreflightCheck.check(PreflightCheckTest.link("LOYALTY", "lastName", "nom", "STRING", Integer.valueOf(6), false, null, "trim", names, false),
			PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		final PreflightFinding.Issue tooLong = finding.issue(PreflightFinding.TOO_LONG);
		check("names over 6 characters are counted: " + longer, longer > 0L && tooLong != null && tooLong.blocking && tooLong.rows == longer);
		check("a too-long value has no pipeline fix", !finding.fixable());
		check("a maximum large enough is clean", PreflightCheck.check(PreflightCheckTest.link("LOYALTY", "lastName", "nom", "STRING", Integer.valueOf(40), false, null, "trim", names,
			false), PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS).issues.isEmpty());
	}

	/** More distinct values than were read: every figure is a lower bound and says so. */
	private static void aSampledColumnSaysAtLeast()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("10/06/1963", 5, "17/02/1998", 3);
		final PreflightLink link = new PreflightLink("m", "Customer", "birthDate", "DATE", null, false, "LOYALTY", "T", "d", null, "isodate()", values, true, 1000L, 900L, false,
			false, NormalisationProfiler.profile(values, 500L));
		final PreflightFinding finding = PreflightCheck.check(link, PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		final PreflightFinding.Issue conversion = finding.issue(PreflightFinding.CONVERSION);
		check("8 rows seen, 900 filled, at least", conversion != null && conversion.rows == 8L && conversion.ofRows == 900L && conversion.atLeast);
	}

	/** The loader refuses a row that leaves a mandatory attribute empty: blocking, counted per source, no fix. */
	private static void anEmptyMandatoryAttributeIsBlockingPerSource()
	{
		final List<Detectors.ValueCount> web = NormalisationProfilerTest.counts("a@x.fr", 3, "b@x.fr", 2);
		final List<Detectors.ValueCount> shop = NormalisationProfilerTest.counts("c@x.fr", 4);
		// WEB: 12 rows, 5 filled -> 7 empty; SHOP: 10 rows, 4 filled -> 6 empty; the same mandatory attribute, two sources
		final PreflightFinding fromWeb = PreflightCheck.check(new PreflightLink("m-web", "Customer", "email", "STRING", null, true, "WEB", "T_WEB", "email", null, "trim", web, false,
			12L, 5L, false, false, NormalisationProfiler.profile(web, -1L)), PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		final PreflightFinding fromShop = PreflightCheck.check(new PreflightLink("m-shop", "Customer", "email", "STRING", null, true, "SHOP", "T_SHOP", "EMAIL", null, "trim", shop,
			false, 10L, 4L, false, false, NormalisationProfiler.profile(shop, -1L)), PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		final PreflightFinding.Issue webIssue = fromWeb.issue(PreflightFinding.MANDATORY_EMPTY);
		final PreflightFinding.Issue shopIssue = fromShop.issue(PreflightFinding.MANDATORY_EMPTY);
		check("WEB: 7 of 12 rows refused", webIssue != null && webIssue.blocking && webIssue.rows == 7L && webIssue.ofRows == 12L);
		check("SHOP: 6 of 10 rows refused, counted apart", shopIssue != null && shopIssue.blocking && shopIssue.rows == 6L && shopIssue.ofRows == 10L);
		check("each finding names its own source", "WEB".equals(fromWeb.sourceSystem) && "SHOP".equals(fromShop.sourceSystem) && fromWeb.blocking() && fromShop.blocking());
		check("no fix to offer", !fromWeb.fixable() && fromWeb.fixPipeline == null && !fromWeb.fixableBlocking());
		final PreflightReport report = PreflightReport.of("B2C", 1L, Arrays.asList(fromWeb, fromShop), null, null);
		check("the report blocks with 13 rows on 2 links and offers no fix", report.blocking() && report.blockingRows() == 13L && report.blockingLinks() == 2 && report.fixable().isEmpty());
		final List<CleaningSteps.Step> nulling = new ArrayList<CleaningSteps.Step>();
		nulling.add(new CleaningSteps.Step(CleaningSteps.EMPTY_TO_NULL, null));
		final PreflightFinding placeholders = PreflightCheck.check(new PreflightLink("m", "Customer", "email", "STRING", null, true, "WEB", "T", "email", nulling, "trim",
			NormalisationProfilerTest.counts("N/A", 4, "d@x.fr", 2), false, 6L, 6L, false, false, null), PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		check("a placeholder a cleaning step turns empty counts too (4 rows)", placeholders.issue(PreflightFinding.MANDATORY_EMPTY) != null
			&& placeholders.issue(PreflightFinding.MANDATORY_EMPTY).rows == 4L);
		check("a non mandatory attribute left empty is nothing", PreflightCheck.check(new PreflightLink("m", "Customer", "email", "STRING", null, false, "WEB", "T", "email", null,
			"trim", web, false, 12L, 5L, false, false, null), PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS).issues.isEmpty());
	}

	/** A repeated single business key and a lost + are information for a person: they never stop the build. */
	private static void duplicatesAndALostPlusStayWarnings()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("A@x.fr", 3, "a@x.fr", 1, "b@x.fr", 1);
		final PreflightLink link = new PreflightLink("m", "Customer", "email", "STRING", null, false, "WEB", "T", "email", null, "trim|lower", values, false, 12L, 5L, true, false,
			NormalisationProfiler.profile(values, -1L));
		final PreflightFinding finding = PreflightCheck.check(link, PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		final PreflightFinding.Issue duplicates = finding.issue(PreflightFinding.DUPLICATES);
		check("the business key repeats on 3 rows after the lower-casing (A@x.fr and a@x.fr merge)", duplicates != null && duplicates.rows == 3L && !duplicates.blocking);
		check("it does not block", !finding.blocking() && finding.blockingRows() == 0L);
		final PreflightReport report = PreflightReport.of("B2C", 1L, Arrays.asList(finding), Arrays.asList(new PreflightReport.Waiting("Customer", "phoneE164",
			Arrays.asList("defaultCountry"))), null);
		check("a warning and a derivation waiting for a parameter, none blocking: " + report.warnings(), report.warnings() == 2 && !report.blocking() && !report.clean());
	}

	private static void aChainThatCannotBeReplayedIsNotClaimed()
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("x", 4);
		final PreflightFinding concat = PreflightCheck.check(PreflightCheckTest.link("S", "fullName", "a", "STRING", null, false, null, "concat(${a},${b})", values, false),
			PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		check("a concat is NOT_CHECKED, nothing claimed", concat.onlyNotChecked() && !concat.blocking());
		final PreflightLink collection = new PreflightLink("m", "Address", "city", "STRING", null, false, "WEB", "T", "city", null, "trim", values, false, -1L, -1L, false, true, null);
		check("a collection feed is NOT_CHECKED", PreflightCheck.check(collection, PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS).onlyNotChecked());
		final PreflightLink old = new PreflightLink("m", "Address", "city", "STRING", null, false, "WEB", "T", "city", null, null, values, false, -1L, -1L, false, false, null);
		check("a transformation that cannot be translated is NOT_CHECKED", PreflightCheck.check(old, PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS).onlyNotChecked());
		final PreflightReport report = PreflightReport.of("B2C", 1L, Arrays.asList(concat), null, null);
		check("not-checked links are counted, not listed", report.linksNotChecked == 1 && report.linksChecked == 0 && report.findings.isEmpty() && report.clean());
	}

	/** Lot M7 (2026-10-18, E2EMASTER20261017235630): an accepted entity no accepted mapping feeds (ContactEmail) is a warning of the report - counted, never blocking, kept through JSON. */
	private static void anEntityNoMappingFeedsIsAWarningNeverBlocking()
	{
		final PreflightFinding finding = PreflightCheck.noFeed("ContactEmail");
		check("an entity-level finding: no attribute, no column, no mapping", "ContactEmail".equals(finding.entityName) && finding.attributeName.isEmpty() && finding.column.isEmpty()
			&& finding.mappingId.isEmpty() && !finding.fixable());
		check("one NO_FEED issue, a warning", finding.issues.size() == 1 && PreflightFinding.NO_FEED.equals(finding.issues.get(0).kind) && !finding.blocking() && finding.blockingRows() == 0L
			&& finding.issue(PreflightFinding.NO_FEED).detail.contains("nothing will be loaded into it") && !finding.onlyNotChecked());
		final PreflightReport report = PreflightReport.of("B2C", 7L, Arrays.asList(finding), null, null);
		check("the report counts it as a warning and does not block the build", !report.blocking() && report.warnings() == 1 && !report.clean() && report.linksChecked == 1
			&& report.byEntity().containsKey("ContactEmail"));
		final PreflightReport back = PreflightReport.fromJson(report.toJson().toString());
		check("it survives its JSON", back != null && back.findings.size() == 1 && PreflightFinding.NO_FEED.equals(back.findings.get(0).issues.get(0).kind)
			&& "ContactEmail".equals(back.findings.get(0).entityName) && back.warnings() == 1 && !back.blocking());
	}

	private static NormalisationProfile phones()
	{
		return NormalisationProfiler.profile(NormalisationProfilerTest.counts("+33612345678", 60, "+33698765432", 50, "0612345678", 40), 150L);
	}

	private static NormalisationProfile words()
	{
		return NormalisationProfiler.profile(NormalisationProfilerTest.counts("Paris", 60, "Lyon", 50, "Nantes", 40), 150L);
	}

	private static PreflightLink phoneLink(final String pEntity, final String pAttribute, final String pSource, final String pColumn, final NormalisationProfile pProfile)
	{
		return new PreflightLink("m-" + pSource + pAttribute, pEntity, pAttribute, "STRING", null, false, pSource, "T_" + pSource, pColumn, null, "", null, false, 300L, 300L, false,
			false, pProfile);
	}

	/**
	 * Lot M15 (2026-10-18, E2EMASTER20261018090711): Client.mobile was fed by ECOM and LOYALTY, never by STORES, whose phone column (234 of 234 rows) was left unmapped:
	 * nothing said so. A WARNING (never blocking) names the attribute, the sources that feed it, the source that does not and its filled column - from the measured
	 * profile of the values, no name read - and nothing is said for a column of another kind, an empty one, an attribute no other source feeds, or a source that feeds the
	 * attribute through another table.
	 */
	private static void anAttributeTheOtherSourcesFeedIsSaidWhenASourceLeavesItsFilledColumnOut()
	{
		final List<PreflightLink> links = Arrays.asList(PreflightCheckTest.phoneLink("Client", "mobile", "ECOM", "mobile", PreflightCheckTest.phones()),
			PreflightCheckTest.phoneLink("Client", "mobile", "LOYALTY", "telephone", PreflightCheckTest.phones()),
			PreflightCheckTest.phoneLink("Client", "email", "ECOM", "email", PreflightCheckTest.words()),
			PreflightCheckTest.phoneLink("Client", "email", "STORES", "email", PreflightCheckTest.words()),
			PreflightCheckTest.phoneLink("Client", "lastName", "STORES", "nom", PreflightCheckTest.words()));
		final Map<String, java.util.Set<String>> toLookAt = PreflightCheck.sourcesToLookAt(links);
		check("the sources worth a look: STORES feeds Client without feeding the phone attribute the others feed: " + toLookAt,
			toLookAt.size() == 1 && toLookAt.get("Client").equals(new java.util.LinkedHashSet<String>(Arrays.asList("STORES"))));

		final List<PreflightCheck.UnmappedColumn> unmapped = Arrays.asList(
			new PreflightCheck.UnmappedColumn("Client", "STORES", "Magasins", "TELEPHONE", 234L, 234L, PreflightCheckTest.phones()),
			new PreflightCheck.UnmappedColumn("Client", "STORES", "Magasins", "MAGASIN", 234L, 234L, PreflightCheckTest.words()),
			new PreflightCheck.UnmappedColumn("Client", "STORES", "Magasins", "EMPTY_PHONE", 234L, 0L, PreflightCheckTest.phones()),
			new PreflightCheck.UnmappedColumn("Client", "ECOM", "Clients", "FAX", 300L, 100L, PreflightCheckTest.phones()));
		final List<PreflightFinding> findings = PreflightCheck.notFedBySource(links, unmapped);
		check("one warning: Client.mobile, STORES.TELEPHONE (the word column, the empty one and the source that feeds the attribute say nothing): " + findings.size(),
			findings.size() == 1);
		final PreflightFinding finding = findings.get(0);
		check("it names the entity, the attribute, the unmapped column and the sources that feed it", "Client".equals(finding.entityName) && "mobile".equals(finding.attributeName)
			&& "STORES".equals(finding.sourceSystem) && "Magasins".equals(finding.tableName) && "TELEPHONE".equals(finding.column)
			&& finding.fedBy.equals(Arrays.asList("ECOM", "LOYALTY")));
		final PreflightFinding.Issue issue = finding.issue(PreflightFinding.NOT_FED_BY_SOURCE);
		check("a warning with the filled rows and the table's rows: " + (issue == null ? null : issue.detail), issue != null && !issue.blocking && issue.rows == 234L && issue.ofRows == 234L
			&& issue.detail.equals("Client.mobile: fed by ECOM and LOYALTY, not by STORES although STORES.TELEPHONE is filled (234 of 234) - map it or say why.")
			&& !finding.blocking() && !finding.onlyNotChecked() && !finding.fixable());

		final PreflightReport report = PreflightReport.of("B2C", 9L, findings, null, null);
		check("the report counts one warning and does not block", !report.blocking() && report.warnings() == 1 && !report.clean() && report.byEntity().containsKey("Client"));
		final PreflightReport back = PreflightReport.fromJson(report.toJson().toString());
		check("it survives its JSON, with the sources that feed the attribute", back != null && back.findings.size() == 1
			&& back.findings.get(0).fedBy.equals(Arrays.asList("ECOM", "LOYALTY")) && "TELEPHONE".equals(back.findings.get(0).column)
			&& back.findings.get(0).issue(PreflightFinding.NOT_FED_BY_SOURCE).rows == 234L);

		// nothing is claimed when a link of the attribute has no measured profile, when one source feeds it through another table, or when no other source feeds it
		final List<PreflightLink> blind = Arrays.asList(PreflightCheckTest.phoneLink("Client", "mobile", "ECOM", "mobile", null),
			PreflightCheckTest.phoneLink("Client", "mobile", "LOYALTY", "telephone", PreflightCheckTest.phones()), PreflightCheckTest.phoneLink("Client", "email", "STORES", "email", PreflightCheckTest.words()));
		check("an attribute with a link nobody measured is not claimed", PreflightCheck.notFedBySource(blind, unmapped).isEmpty() && PreflightCheck.sourcesToLookAt(blind).isEmpty());
		final List<PreflightLink> otherTable = Arrays.asList(PreflightCheckTest.phoneLink("Client", "mobile", "ECOM", "mobile", PreflightCheckTest.phones()),
			PreflightCheckTest.phoneLink("Client", "mobile", "STORES", "gsm", PreflightCheckTest.phones()));
		check("a source that feeds the attribute (from another table) is not reproached", PreflightCheck.notFedBySource(otherTable, unmapped).isEmpty());
		final List<PreflightLink> alone = Arrays.asList(PreflightCheckTest.phoneLink("Client", "mobile", "STORES", "gsm", PreflightCheckTest.phones()));
		check("nothing is said when no other source feeds anything", PreflightCheck.sourcesToLookAt(alone).isEmpty());
	}

	private static void theReportDecidesAndRoundTrips() throws Exception
	{
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("17/06/1963", 140);
		final PreflightFinding blocking = PreflightCheck.check(PreflightCheckTest.link("LOYALTY", "birthDate", "date_naissance", "DATE", null, false, null, "isodate()", values, false),
			PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		final PreflightFinding warn = PreflightCheck.check(new PreflightLink("m2", "Customer", "email", "STRING", null, false, "WEB", "T", "email", null, "trim",
			NormalisationProfilerTest.counts("a@x.fr", 3), false, 3L, 3L, true, false, null), PreflightCheckTest.ENGINE, PreflightCheckTest.NO_LOOKUPS);
		final PreflightReport report = PreflightReport.of("B2C", 42L, Arrays.asList(warn, blocking), Arrays.asList(new PreflightReport.Waiting("Customer", "phoneE164",
			Arrays.asList("defaultCountry"))), Arrays.asList("a note"));
		check("the blocking link comes first", report.findings.get(0) == blocking && report.findings.get(1) == warn);
		check("one entity group", report.byEntity().size() == 1 && report.byEntity().get("Customer").size() == 2);
		final PreflightReport back = PreflightReport.fromJson(report.toJson().toString());
		check("the report survives its JSON", back != null && back.blocking() && back.blockingRows() == 140L && back.at == 42L && back.warnings() == report.warnings()
			&& back.findings.size() == 2 && back.derivationsWaiting.size() == 1 && back.notes.size() == 1 && back.projectCode.equals("B2C"));
		final PreflightFinding restored = back.byMapping("m-birthDate");
		check("with its fix and its format", restored != null && restored.fixable() && "trim|date(dd/MM/yyyy,yyyy-MM-dd)".equals(restored.fixPipeline) && "dd/MM/yyyy".equals(restored.written));
		check("a non-report text is null", PreflightReport.fromJson("{\"truncated\":true}") == null && PreflightReport.fromJson("") == null && PreflightReport.fromJson("not json") == null);
		final List<PreflightFinding> many = new ArrayList<PreflightFinding>();
		for (int i = 0; i < PreflightReport.MAX_FINDINGS + 5; i++)
		{
			final PreflightFinding one = new PreflightFinding("m" + i, "E", "a" + i, "S", "T", "c", "DATE", "p", "", "");
			one.issues.add(new PreflightFinding.Issue(PreflightFinding.CONVERSION, true, 1L, 1L, false, ""));
			many.add(one);
		}
		final PreflightReport capped = PreflightReport.of("B2C", 1L, many, null, null);
		check("the stored report is bounded and says how many are not listed", capped.findings.size() == PreflightReport.MAX_FINDINGS && capped.notes.size() == 1);
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("PreflightCheckTest: " + pLabel);
		}
	}
}
