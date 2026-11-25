package com.on.ebx.factory.ebx.ui;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** JUnit-free: run the main, a failure throws. Exercises {@link FactoryLinks} without any EBX type. */
public final class FactoryLinksTest
{
	private static final String DATASPACE = "BMdmFactory";
	private static final String DATASET = "MdmFactory";

	public static void main(final String[] pArgs)
	{
		FactoryLinksTest.cockpitUrlSelectsTheRecordByCode();
		FactoryLinksTest.cockpitUrlEncodesACodeCarryingAQuoteASpaceAndABracket();
		FactoryLinksTest.wizardUrlCarriesTheProjectCodeAsServiceParams();
		FactoryLinksTest.wizardUrlEncodesACodeCarryingAQuoteASpaceAndABracket();
		FactoryLinksTest.wizardUrlWithStepAddsTheStepToServiceParams();
		FactoryLinksTest.wizardUrlWithZeroOrNegativeStepOmitsIt();
		FactoryLinksTest.newProjectUrlHasNoServiceParams();
		FactoryLinksTest.advancedViewUrlUsesTheRealDataspaceAndDatasetKeys();
		FactoryLinksTest.deleteUrlCarriesTheProjectCodeAsServiceParams();
		FactoryLinksTest.explorerUrlHasNoProjectParameter();
		FactoryLinksTest.genericTableUrlTargetsAnyDatasetAndTable();
		FactoryLinksTest.genericTableUrlEncodesEachPathSegmentSeparately();

		System.out.println("FactoryLinksTest OK");
	}

	private static void cockpitUrlSelectsTheRecordByCode()
	{
		final String url = FactoryLinks.cockpitUrl(FactoryLinksTest.DATASPACE, FactoryLinksTest.DATASET, "Companies");
		FactoryLinksTest.check("the base is the ebx-ui table route, not /ebx/", url.startsWith("/ebx-ui/ui/advanced/data/BMdmFactory/MdmFactory/root/Project?"));
		FactoryLinksTest.check("targets the FactoryCockpit service", url.indexOf("service=FactoryCockpit") >= 0);
		FactoryLinksTest.check("in RECORD mode", url.endsWith("&serviceMode=RECORD"));
		FactoryLinksTest.check("the predicate names the code field", url.indexOf("predicate=./code") >= 0);
		// "./code='Companies'" URL-encoded, except its own leading slash restored: %3D for '=', %27 for the quotes
		FactoryLinksTest.check("the predicate's own slash stays literal (CLAUDE.md/TagReviewUserService: %2F next to service=...&serviceMode=RECORD fails to load)",
			url.indexOf("predicate=./code%3D%27Companies%27") >= 0);
		FactoryLinksTest.check("no other, unintended slash was restored", url.indexOf("%2F") < 0);
	}

	/** The exact case CLAUDE.md warns about: a code with a quote must be doubled before encoding, never string-concatenated raw. */
	private static void cockpitUrlEncodesACodeCarryingAQuoteASpaceAndABracket()
	{
		final String url = FactoryLinks.cockpitUrl(FactoryLinksTest.DATASPACE, FactoryLinksTest.DATASET, "It's 2 [x]");
		FactoryLinksTest.check("no raw quote in the URL", url.indexOf("'") < 0);
		FactoryLinksTest.check("no raw space in the URL", url.indexOf(' ') < 0);
		FactoryLinksTest.check("no raw bracket in the URL", url.indexOf("[") < 0 && url.indexOf("]") < 0);
		// FactoryHtml.quote doubles the embedded quote first: 'It''s 2 [x]' - URLEncoder turns "''" into two %27 in a row
		FactoryLinksTest.check("the doubled quote survives encoding as two consecutive %27", url.indexOf("%27%27") >= 0);
		FactoryLinksTest.check("a space is %20, never a raw '+' (the ebx-ui client does not form-decode a query value)", url.indexOf("%20") >= 0 && url.indexOf('+') < 0);
		FactoryLinksTest.check("brackets are percent-encoded", url.indexOf("%5B") >= 0 && url.indexOf("%5D") >= 0);
		FactoryLinksTest.check("the exact predicate", url.indexOf("predicate=./code%3D%27It%27%27s%202%20%5Bx%5D%27") >= 0);
		FactoryLinksTest.check("the service is still reachable at the end of the URL", url.endsWith("&service=FactoryCockpit&serviceMode=RECORD"));
	}

	private static void wizardUrlCarriesTheProjectCodeAsServiceParams()
	{
		final String url = FactoryLinks.wizardUrl(FactoryLinksTest.DATASPACE, FactoryLinksTest.DATASET, "Companies");
		FactoryLinksTest.check("targets ProjectWizard", url.indexOf("service=ProjectWizard") >= 0);
		FactoryLinksTest.check("TABLE_VIEW mode (ProjectWizard is OnTableView, no record to select)", url.indexOf("serviceMode=TABLE_VIEW") >= 0);
		FactoryLinksTest.check("no plain project= parameter (silently dropped by the ebx-ui route - CLAUDE.md's own note)", url.indexOf("&project=") < 0);
		FactoryLinksTest.check("the code travels as serviceParams, the (URL-encoded) base64 of {\"project\":\"Companies\"}",
			url.endsWith("&serviceParams=" + FactoryLinksTest.expectedServiceParams("{\"project\":\"Companies\"}")));
	}

	/** Same quote/space/bracket code as the cockpit case, through the JSON+base64 path instead of the predicate path. */
	private static void wizardUrlEncodesACodeCarryingAQuoteASpaceAndABracket()
	{
		final String url = FactoryLinks.wizardUrl(FactoryLinksTest.DATASPACE, FactoryLinksTest.DATASET, "It's 2 [x]");
		// no JSON escaping is needed for a quote/space/bracket (only \\ and " are special in JSON) - the whole
		// point of carrying it inside a base64 blob is that none of this needs URL-encoding of its own
		final String expectedJson = "{\"project\":\"It's 2 [x]\"}";
		FactoryLinksTest.check("the exact serviceParams payload", url.endsWith("&serviceParams=" + FactoryLinksTest.expectedServiceParams(expectedJson)));
		FactoryLinksTest.check("no raw quote, space or bracket leaked outside the base64 blob itself",
			url.substring(0, url.indexOf("serviceParams=")).indexOf("'") < 0);
	}

	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug item 2): the Mapping matrix/Feed designer screens' own "Back
	 * to the wizard" link uses this overload with {@code WizardRules.STEP_STAGING} so the wizard reopens on
	 * step 4 instead of step 1 - {@code ProjectWizardUserService#setupObjectContext} resolves the extra
	 * {@code "step"} field the same one-time way it already resolves {@code "project"}.
	 */
	private static void wizardUrlWithStepAddsTheStepToServiceParams()
	{
		final String url = FactoryLinks.wizardUrl(FactoryLinksTest.DATASPACE, FactoryLinksTest.DATASET, "Companies", 4);
		final String expectedJson = "{\"project\":\"Companies\",\"step\":4}";
		FactoryLinksTest.check("the exact serviceParams payload, step appended as a bare JSON number",
			url.endsWith("&serviceParams=" + FactoryLinksTest.expectedServiceParams(expectedJson)));
	}

	/** {@code pStep <= 0} ("no preference") must produce the EXACT SAME url as the no-step overload - never a stray {@code "step":0}. */
	private static void wizardUrlWithZeroOrNegativeStepOmitsIt()
	{
		final String withoutStepOverload = FactoryLinks.wizardUrl(FactoryLinksTest.DATASPACE, FactoryLinksTest.DATASET, "Companies");
		FactoryLinksTest.check("step 0 omits the field entirely", FactoryLinks.wizardUrl(FactoryLinksTest.DATASPACE, FactoryLinksTest.DATASET, "Companies", 0)
			.equals(withoutStepOverload));
		FactoryLinksTest.check("a negative step also omits the field", FactoryLinks.wizardUrl(FactoryLinksTest.DATASPACE, FactoryLinksTest.DATASET, "Companies", -1)
			.equals(withoutStepOverload));
	}

	private static void newProjectUrlHasNoServiceParams()
	{
		final String url = FactoryLinks.newProjectUrl(FactoryLinksTest.DATASPACE, FactoryLinksTest.DATASET);
		FactoryLinksTest.check("targets ProjectWizard", url.indexOf("service=ProjectWizard") >= 0);
		FactoryLinksTest.check("carries no serviceParams at all - a brand new project", url.indexOf("serviceParams=") < 0);
		FactoryLinksTest.check("the exact URL", url.equals("/ebx-ui/ui/advanced/data/BMdmFactory/MdmFactory/root/Project?service=ProjectWizard&serviceMode=TABLE_VIEW"));
	}

	private static void advancedViewUrlUsesTheRealDataspaceAndDatasetKeys()
	{
		final String url = FactoryLinks.advancedViewUrl("BMdmFactory", "MdmFactory");
		FactoryLinksTest.check("the generic, perspective-agnostic base", url.startsWith("/ebx-ui/ui/advanced/data/"));
		FactoryLinksTest.check("the real dataspace key is used, not a literal guess", url.indexOf("/BMdmFactory/") >= 0);
		FactoryLinksTest.check("the real dataset name is used", url.indexOf("/MdmFactory/root/Project") >= 0);
		FactoryLinksTest.check("no query string at all - the plain table route", url.indexOf('?') < 0);
	}

	/** Wizard summary lot (2026-09-24): unlike every other link this class builds, this one targets a GENERATED zone dataset/table, not the factory's own {@code Project}. */
	private static void genericTableUrlTargetsAnyDatasetAndTable()
	{
		final String url = FactoryLinks.genericTableUrl("BPersonnes_Landing", "Personnes_Landing_SAP", "/root/BusinessPartner");
		FactoryLinksTest.check("the generic, perspective-agnostic base", url.startsWith("/ebx-ui/ui/advanced/data/"));
		FactoryLinksTest.check("the target dataspace, never the factory's own", url.indexOf("/BPersonnes_Landing/") >= 0);
		FactoryLinksTest.check("the target dataset", url.indexOf("/Personnes_Landing_SAP/") >= 0);
		FactoryLinksTest.check("the target table path, not /root/Project", url.endsWith("/root/BusinessPartner"));
		FactoryLinksTest.check("no query string", url.indexOf('?') < 0);
	}

	/** Each path segment (dataspace/dataset/table parts) is encoded on its OWN, not the whole path as one string - a segment carrying a space must not turn the path's own slashes into %2F. */
	private static void genericTableUrlEncodesEachPathSegmentSeparately()
	{
		final String url = FactoryLinks.genericTableUrl("B Test Space", "My Dataset", "/root/My Table");
		FactoryLinksTest.check("dataspace segment encoded, real slashes intact", url.indexOf("/B%20Test%20Space/") >= 0);
		FactoryLinksTest.check("dataset segment encoded", url.indexOf("/My%20Dataset/root/") >= 0);
		FactoryLinksTest.check("table segment encoded, /root/ kept as real path separators", url.endsWith("/root/My%20Table"));
		FactoryLinksTest.check("no stray %2F anywhere (a whole-path encode would produce one)", url.indexOf("%2F") < 0);
	}

	/** The base64 of {@code pJson}, itself URL-encoded like any other query value - {@link FactoryLinks#queryValue} runs on the base64 STRING, not on the raw JSON, so the padding {@code =} always becomes {@code %3D}. */
	private static String expectedServiceParams(final String pJson)
	{
		final String base64 = Base64.getEncoder().encodeToString(pJson.getBytes(StandardCharsets.UTF_8));
		try
		{
			return URLEncoder.encode(base64, "UTF-8");
		}
		catch (final UnsupportedEncodingException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	/** project-deletion lot (2026-09-23): same {@code ebx-ui}/serviceParams shape as {@link #wizardUrlCarriesTheProjectCodeAsServiceParams} - {@code ProjectDelete} is {@code OnTableView} too. */
	private static void deleteUrlCarriesTheProjectCodeAsServiceParams()
	{
		final String url = FactoryLinks.deleteUrl(FactoryLinksTest.DATASPACE, FactoryLinksTest.DATASET, "Companies");
		FactoryLinksTest.check("targets ProjectDelete", url.indexOf("service=ProjectDelete") >= 0);
		FactoryLinksTest.check("TABLE_VIEW mode (ProjectDelete is OnTableView, no record to select)", url.indexOf("serviceMode=TABLE_VIEW") >= 0);
		FactoryLinksTest.check("no plain project= parameter (silently dropped by the ebx-ui route)", url.indexOf("&project=") < 0);
		FactoryLinksTest.check("the code travels as serviceParams, the (URL-encoded) base64 of {\"project\":\"Companies\"}",
			url.endsWith("&serviceParams=" + FactoryLinksTest.expectedServiceParams("{\"project\":\"Companies\"}")));
	}

	private static void explorerUrlHasNoProjectParameter()
	{
		final String url = FactoryLinks.explorerUrl(FactoryLinksTest.DATASPACE, FactoryLinksTest.DATASET);
		FactoryLinksTest.check("targets ProjectExplorer", url.indexOf("service=ProjectExplorer") >= 0);
		FactoryLinksTest.check("carries no serviceParams at all - nothing selected", url.indexOf("serviceParams=") < 0);
		FactoryLinksTest.check("the exact URL", url.equals("/ebx-ui/ui/advanced/data/BMdmFactory/MdmFactory/root/Project?service=ProjectExplorer&serviceMode=TABLE_VIEW"));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
