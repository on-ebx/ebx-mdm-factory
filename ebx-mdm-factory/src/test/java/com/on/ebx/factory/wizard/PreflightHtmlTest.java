package com.on.ebx.factory.wizard;

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.connect.core.Transformations;
import com.on.ebx.factory.landing.RunTexts;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.Detectors;
import com.on.ebx.factory.staging.NormalisationProfiler;
import com.on.ebx.factory.staging.PreflightCheck;
import com.on.ebx.factory.staging.PreflightFinding;
import com.on.ebx.factory.staging.PreflightLink;
import com.on.ebx.factory.staging.PreflightReport;
import com.on.ebx.factory.staging.TransformVerifier;

/**
 * Staging preflight lot H1 (2026-10-17): the report at the top of step 4 in the viewer's language, the job sentences the build writes when the check stops it, and the bundle that
 * carries them (both languages, same placeholders, the run texts equal to the English the code stores). JUnit-free: run the main, a failure throws.
 */
public final class PreflightHtmlTest
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
		PreflightHtmlTest.theBlockedReportInEnglish();
		PreflightHtmlTest.theBlockedReportInFrench();
		PreflightHtmlTest.aViewerWhoCannotActGetsNoButton();
		PreflightHtmlTest.anAttributeNotFedByASourceRendersInBothLanguages();
		PreflightHtmlTest.warningsAloneNeverOfferBuildAnyway();
		PreflightHtmlTest.anEmptyMandatoryAttributeBlocksPerSourceWithNoFix();
		PreflightHtmlTest.nothingFoundDrawsNothing();
		PreflightHtmlTest.aSampledCountSaysAtLeast();
		PreflightHtmlTest.theBlockAtTheTopOfStep4();
		PreflightHtmlTest.theJobSentenceReadsAmberAndInTheViewersLanguage();
		PreflightHtmlTest.theBundleKeepsBothLanguagesAligned();
		PreflightHtmlTest.anEntityNoMappingFeedsRendersAsAWarningInBothLanguages();
		PreflightHtmlTest.shapeOutliersRenderInBothLanguages();
		System.out.println("PreflightHtmlTest OK");
	}

	private static PreflightFinding birthDateFinding(final long pRows, final boolean pSampled)
	{
		final List<Detectors.ValueCount> values = Arrays.asList(new Detectors.ValueCount("17/06/1963", pRows));
		return PreflightCheck.check(new PreflightLink("m-birth", "Customer", "birthDate", "DATE", null, false, "LOYALTY", "Fidelite", "date_naissance", null, "isodate()", values,
			pSampled, -1L, -1L, false, false, NormalisationProfiler.profile(values, -1L)), PreflightHtmlTest.ENGINE, CleaningSteps.Lookups.empty());
	}

	private static PreflightReport blocked()
	{
		return PreflightReport.of("B2C", 1L, Arrays.asList(PreflightHtmlTest.birthDateFinding(140L, false)), null, null);
	}

	private static Map<String, String> matrix()
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		out.put("Customer", "/ebx-ui/matrix?x=1&y=2");
		return out;
	}

	private static void theBlockedReportInEnglish()
	{
		final String html = PreflightHtml.render(PreflightHtmlTest.blocked(), Locale.ENGLISH, PreflightHtmlTest.matrix(), true);
		check("the title counts the rows and the links: " + html, html.contains("Check before the build: 140 rows would be refused on 1 link. The build is on hold."));
		check("the line of the brief, with the format and the chain",
			html.contains("140 rows of LOYALTY would be refused: birthDate &lt;- date_naissance (dates written dd/MM/yyyy, transformation isodate()) - fix it in the matrix."));
		check("the proposed fix is shown", html.contains("Proposed fix: trim|date(dd/MM/yyyy,yyyy-MM-dd) - it converts every sampled value."));
		check("Apply the fix writes that link", html.contains("data-mdmf-action=\"STAGING_PREFLIGHT_FIX\" data-mdmf-id=\"m-birth\">Apply the fix</button>"));
		check("Build anyway is a secondary action", html.contains("data-mdmf-action=\"STAGING_LOAD_RUN_ANYWAY\">Build anyway</button>"));
		check("Check again is offered", html.contains("data-mdmf-action=\"STAGING_PREFLIGHT\">Check again</button>"));
		check("the matrix of the business object is linked, the URL escaped", html.contains("href=\"/ebx-ui/matrix?x=1&amp;y=2\"") && html.contains("Open the matrix"));
		check("the blocking class", html.contains("mdmf-notice-warn") && html.contains("data-mdmf-blocking=\"true\""));
		check("one fix: no 'apply every fix'", !html.contains("data-mdmf-id=\"*\""));
		check("no value in the block", !html.matches("(?s).*\\b17/06/1963\\b.*"));
	}

	private static void theBlockedReportInFrench()
	{
		final String html = PreflightHtml.render(PreflightHtmlTest.blocked(), Locale.FRENCH, PreflightHtmlTest.matrix(), true);
		check("French title: " + html, html.contains("140 lignes seraient refusées sur 1 lien"));
		check("French line", html.contains("140 lignes de LOYALTY seraient refusées : birthDate &lt;- date_naissance (dates écrites dd/MM/yyyy, transformation isodate())"));
		check("French buttons", html.contains("Appliquer la correction") && html.contains("Construire quand même") && html.contains("Contrôler à nouveau"));
		check("no placeholder is left", !html.contains("{0}") && !html.contains("{1}") && !html.contains("wizard.step4.preflight"));
	}

	private static void aViewerWhoCannotActGetsNoButton()
	{
		final String html = PreflightHtml.render(PreflightHtmlTest.blocked(), Locale.ENGLISH, PreflightHtmlTest.matrix(), false);
		check("the report is read", html.contains("would be refused: birthDate"));
		check("no button, no link to act", !html.contains("data-mdmf-action") && !html.contains("<button") && !html.contains("Open the matrix"));
	}

	/** Lot M7 (2026-10-18): the "nothing will be loaded into ContactEmail" warning, in the viewer's language, with no "Build anyway" and no fix button. */
	private static void anEntityNoMappingFeedsRendersAsAWarningInBothLanguages()
	{
		final PreflightReport report = PreflightReport.of("B2C", 1L, Arrays.asList(PreflightCheck.noFeed("ContactEmail")), null, null);
		final String html = PreflightHtml.render(report, Locale.ENGLISH, Collections.singletonMap("ContactEmail", "/matrix"), true);
		check("title for one warning: " + html, html.contains("Check before the build: nothing would be refused, 1 warning to look at."));
		check("the entity sentence names the fix", html.contains("data-mdmf-kind=\"NO_FEED\"")
			&& html.contains("Nothing will be loaded into ContactEmail: no accepted mapping feeds it - map at least one attribute to a landing column and accept the mapping, or reject the entity."));
		check("a warning: no 'Build anyway', no fix, no matrix button", !html.contains("STAGING_LOAD_RUN_ANYWAY") && !html.contains("STAGING_PREFLIGHT_FIX") && !html.contains("href=\"/matrix\""));
		final String french = PreflightHtml.render(report, Locale.FRENCH, null, false);
		check("in French: " + french, french.contains("Rien ne sera charg\u00E9 dans ContactEmail\u00A0: aucun lien accept\u00E9 ne l\u2019alimente"));
	}

	/** Lot M15 (2026-10-18): "Client.mobile: fed by ECOM and LOYALTY, not by STORES although STORES.TELEPHONE is filled (234 of 234)", a warning in both languages. */
	private static void anAttributeNotFedByASourceRendersInBothLanguages()
	{
		final PreflightFinding finding = new PreflightFinding("", "Client", "mobile", "STORES", "Magasins", "TELEPHONE", "STRING", "", "", "");
		finding.fedBy.addAll(Arrays.asList("ECOM", "LOYALTY"));
		finding.issues.add(new PreflightFinding.Issue(PreflightFinding.NOT_FED_BY_SOURCE, false, 234L, 234L, false, "detail"));
		final PreflightReport report = PreflightReport.of("B2C", 1L, Arrays.asList(finding), null, null);
		final String html = PreflightHtml.render(report, Locale.ENGLISH, Collections.singletonMap("Client", "/matrix"), true);
		check("the sentence in English: " + html, html.contains("data-mdmf-kind=\"NOT_FED_BY_SOURCE\"")
			&& html.contains("Client.mobile: fed by ECOM and LOYALTY, not by STORES although STORES.TELEPHONE is filled (234 of 234) \u2014 map it or say why."));
		check("a warning: no 'Build anyway', no fix, no matrix button", !html.contains("STAGING_LOAD_RUN_ANYWAY") && !html.contains("STAGING_PREFLIGHT_FIX") && !html.contains("href=\"/matrix\""));
		final String french = PreflightHtml.render(report, Locale.FRENCH, null, false);
		check("in French: " + french, french.contains("Client.mobile\u00A0: aliment\u00E9 par ECOM et LOYALTY, pas par STORES bien que STORES.TELEPHONE soit renseign\u00E9e (234 sur 234)"));
		check("three sources read 'A, B and C'", "A, B and C".equals(PreflightHtml.andList(Arrays.asList("A", "B", "C"), Locale.ENGLISH)) && "A".equals(PreflightHtml.andList(Arrays.asList("A"), Locale.ENGLISH)));
	}

	/** B2B test (2026-10-23): "Organisation.siren: 25 of 150 values from SAP.<column> do not have the shape 9(9) that SALESFORCE and WEB give it", both languages. */
	private static void shapeOutliersRenderInBothLanguages()
	{
		final PreflightFinding finding = new PreflightFinding("m-sap", "Organisation", "siren", "SAP", "SAP_BusinessPartner", "BPTaxNumber", "STRING", "", "", "");
		finding.fedBy.addAll(Arrays.asList("SALESFORCE", "WEB"));
		finding.expectedShape = "9(9)";
		finding.issues.add(new PreflightFinding.Issue(PreflightFinding.SHAPE_OUTLIERS, false, 25L, 150L, false, "detail"));
		final PreflightReport report = PreflightReport.of("B2B", 1L, Arrays.asList(finding), null, null);
		final String html = PreflightHtml.render(report, Locale.ENGLISH, null, false);
		check("the sentence in English: " + html, html.contains("data-mdmf-kind=\"SHAPE_OUTLIERS\"")
			&& html.contains("Organisation.siren: 25 of 150 values from SAP.BPTaxNumber do not have the shape 9(9) that SALESFORCE and WEB give it in most values"));
		final String french = PreflightHtml.render(report, Locale.FRENCH, null, false);
		check("in French: " + french, french.contains("Organisation.siren\u00A0: 25 valeurs sur 150 venues de SAP.BPTaxNumber n\u2019ont pas la forme 9(9) que SALESFORCE et WEB"));
	}

	private static void warningsAloneNeverOfferBuildAnyway()
	{
		final PreflightFinding duplicates = PreflightCheck.check(new PreflightLink("m2", "Customer", "email", "STRING", null, false, "WEB", "T", "email", null, "trim",
			Arrays.asList(new Detectors.ValueCount("a@x.fr", 3L)), false, 3L, 3L, true, false, null), PreflightHtmlTest.ENGINE, CleaningSteps.Lookups.empty());
		final PreflightReport report = PreflightReport.of("B2C", 1L, Arrays.asList(duplicates), Arrays.asList(new PreflightReport.Waiting("Customer", "phoneE164",
			Arrays.asList("defaultCountry"))), null);
		final String html = PreflightHtml.render(report, Locale.ENGLISH, null, true);
		check("title for warnings: " + html, html.contains("Check before the build: nothing would be refused, 2 warnings to look at."));
		check("the repeated business key", html.contains("2 rows of WEB repeat the value of the business key email inside the source"));
		check("the derivation waiting for its parameter", html.contains("Customer.phoneE164 is an accepted derivation that waits for the parameter(s) defaultCountry"));
		check("nothing stops the build: no 'Build anyway', plain notice", !html.contains("STAGING_LOAD_RUN_ANYWAY") && !html.contains("mdmf-notice-warn") && html.contains("data-mdmf-blocking=\"false\""));
		check("it can still be checked again", html.contains("STAGING_PREFLIGHT\">Check again"));
	}

	/** An empty mandatory attribute is refused by the loader: blocking, per source, no fix, the way out is said. */
	private static void anEmptyMandatoryAttributeBlocksPerSourceWithNoFix()
	{
		final PreflightFinding web = PreflightCheck.check(new PreflightLink("m-web", "Customer", "email", "STRING", null, true, "WEB", "T", "email", null, "trim",
			Arrays.asList(new Detectors.ValueCount("a@x.fr", 5L)), false, 12L, 5L, false, false, null), PreflightHtmlTest.ENGINE, CleaningSteps.Lookups.empty());
		final PreflightFinding shop = PreflightCheck.check(new PreflightLink("m-shop", "Customer", "email", "STRING", null, true, "SHOP", "T2", "EMAIL", null, "trim",
			Arrays.asList(new Detectors.ValueCount("c@x.fr", 4L)), false, 10L, 4L, false, false, null), PreflightHtmlTest.ENGINE, CleaningSteps.Lookups.empty());
		final PreflightReport report = PreflightReport.of("B2C", 1L, Arrays.asList(web, shop), null, null);
		final String html = PreflightHtml.render(report, Locale.ENGLISH, PreflightHtmlTest.matrix(), true);
		check("blocking, 13 rows on 2 links: " + html, html.contains("13 rows would be refused on 2 links") && html.contains("data-mdmf-blocking=\"true\""));
		check("7 rows of WEB", html.contains("7 rows of WEB would be refused: the mandatory attribute email stays empty for them (it reads email) - map a column or make the attribute optional in the matrix."));
		check("6 rows of SHOP, apart", html.contains("6 rows of SHOP would be refused: the mandatory attribute email stays empty for them (it reads EMAIL)"));
		check("no fix button, no proposed fix", !html.contains("STAGING_PREFLIGHT_FIX") && !html.contains("Proposed fix"));
		check("Build anyway is offered", html.contains("STAGING_LOAD_RUN_ANYWAY"));
		final String french = PreflightHtml.render(report, Locale.FRENCH, null, true);
		check("French: refused, map a column or make it optional", french.contains("seraient refus\u00e9es\u00a0: l\u2019attribut obligatoire email reste vide") && french.contains("associez une colonne ou rendez l\u2019attribut facultatif"));
	}

	private static void nothingFoundDrawsNothing()
	{
		check("null", PreflightHtml.render(null, Locale.ENGLISH, null, true).isEmpty());
		check("clean", PreflightHtml.render(PreflightReport.of("B2C", 1L, Collections.<PreflightFinding>emptyList(), null, null), Locale.ENGLISH, null, true).isEmpty());
	}

	private static void aSampledCountSaysAtLeast()
	{
		final PreflightReport report = PreflightReport.of("B2C", 1L, Arrays.asList(PreflightHtmlTest.birthDateFinding(140L, true)), null, null);
		check("at least 140 rows", PreflightHtml.render(report, Locale.ENGLISH, null, true).contains("at least 140 rows of LOYALTY would be refused"));
		check("au moins en français", PreflightHtml.render(report, Locale.FRENCH, null, true).contains("au moins 140 lignes de LOYALTY"));
	}

	/** The block is the FIRST thing step 4 says, above the rows a build left out; absent when no report is kept. */
	private static void theBlockAtTheTopOfStep4()
	{
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		final WizardPage.Model model = new WizardPage.Model();
		model.snapshot = snapshot;
		model.stepStates = WizardRules.stepStates(snapshot);
		model.administrator = true;
		model.designer = true;
		model.currentStep = WizardRules.STEP_STAGING;
		model.locale = Locale.ENGLISH;
		final String without = WizardPageTest.section(WizardPage.render(model), 4);
		check("no report, no block", !without.contains("mdmf-step4-preflight"));
		model.stagingPreflight = PreflightHtmlTest.blocked();
		final String with = WizardPageTest.section(WizardPage.render(model), 4);
		check("the block is in step 4", with.contains("id=\"mdmf-step4-preflight\"") && with.contains("140 rows of LOYALTY would be refused"));
		model.stagingLoadRunRunning = true;
		check("not while a build runs", !WizardPageTest.section(WizardPage.render(model), 4).contains("mdmf-step4-preflight"));
	}

	private static void theJobSentenceReadsAmberAndInTheViewersLanguage()
	{
		final String stored = RunTexts.preflightBlocked(140L, 1L).stored();
		check("stored English starts with 'Nothing to do'", stored.startsWith("Nothing to do: the build was not started - 140 row(s) on 1 link(s) would be refused."));
		check("a DONE job with that message shows 'Nothing to do' (amber), not a green Done", JobOutcome.NOTHING.equals(JobOutcome.display("STAGING_LOAD_RUN", "DONE", stored)));
		check("French", RunPanelText.display(stored, Locale.FRENCH).startsWith("Rien à faire : la construction n’a pas démarré - 140 ligne(s) sur 1 lien(s) seraient refusées."));
		check("English without marker", RunPanelText.display(stored, Locale.ENGLISH).startsWith("Nothing to do: the build was not started") && !RunPanelText.display(stored, Locale.ENGLISH).contains("[mdmf:"));
		check("the journal lines", RunPanelText.event(RunTexts.preflightChecking().stored(), Locale.FRENCH).startsWith("Contrôle des liens acceptés")
			&& RunPanelText.event(RunTexts.preflightSetAside().stored(), Locale.FRENCH).startsWith("Construire quand même")
			&& RunPanelText.event(RunTexts.preflightWarnings(3L).stored(), Locale.FRENCH).contains("3 avertissement(s)")
			&& RunPanelText.event(RunTexts.preflightUnavailable("boom").stored(), Locale.FRENCH).contains("(boom)"));
	}

	private static void theBundleKeepsBothLanguagesAligned()
	{
		final ResourceBundle en = ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", Locale.ENGLISH);
		final ResourceBundle fr = ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", Locale.FRENCH);
		final Pattern placeholder = Pattern.compile("\\{(\\d+)\\}");
		int seen = 0;
		final Enumeration<String> keys = en.getKeys();
		while (keys.hasMoreElements())
		{
			final String key = keys.nextElement();
			if (!key.startsWith(PreflightHtml.P) && !key.startsWith(RunTexts.STG + "preflight."))
			{
				continue;
			}
			seen++;
			check("a French text for " + key, fr.containsKey(key));
			check("the same placeholders in " + key, PreflightHtmlTest.placeholders(en.getString(key), placeholder).equals(PreflightHtmlTest.placeholders(fr.getString(key), placeholder)));
		}
		check("the whole block is there (" + seen + " keys)", seen >= 45);
		for (final Map.Entry<String, String> english : RunTexts.ENGLISH.entrySet())
		{
			if (english.getKey().startsWith(RunTexts.STG + "preflight."))
			{
				check("the code stores the English the bundle says: " + english.getKey(), english.getValue().equals(en.getString(english.getKey())));
			}
		}
	}

	private static Set<String> placeholders(final String pText, final Pattern pPattern)
	{
		final Set<String> out = new HashSet<String>();
		final Matcher matcher = pPattern.matcher(pText);
		while (matcher.find())
		{
			out.add(matcher.group(1));
		}
		return out;
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("PreflightHtmlTest: " + pLabel);
		}
	}
}
