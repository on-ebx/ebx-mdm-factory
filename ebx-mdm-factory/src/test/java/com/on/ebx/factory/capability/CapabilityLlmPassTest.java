package com.on.ebx.factory.capability;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.staging.NormalisationProfiler;
import com.on.ebx.factory.quality.Detectors;
import com.on.ebx.agent.AgentLoop;
import com.on.ebx.agent.LlmClient;
import com.on.ebx.agent.ToolSet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Domain-neutral fixtures
 * on purpose (same discipline as {@code quality.QualityLlmPassTest}): a "Product" entity with a phone-shaped
 * attribute and a registry-lookup-shaped connector, never party/company vocabulary that would anchor the
 * test (and, by extension, a reader's mental model) on one business domain. The {@code validate(rawIban,
 * addressCountryCode): json_object} fixture reproduces the coordinator's own real capture (2026-09-21) to
 * exercise literal binding and the any-return-type fix directly against the shape that motivated them.
 * <p>
 * Rewritten for the two-stage inventory (coordinator, 2026-09-21, second review): the compact catalogue
 * ({@link CapabilityLlmPass.LibrarySummary}/{@link CapabilityLlmPass.ConnectorSummary}) plus the detail tools
 * ({@code get_library_functions}/{@code get_service_details}) that must be called before {@code suggest_*}
 * accepts a target - a positive-path test now calls the matching detail tool first, exactly as the system
 * prompt instructs the model to.
 */
public final class CapabilityLlmPassTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		CapabilityLlmPassTest.systemPromptIsDomainNeutral();
		CapabilityLlmPassTest.systemPromptStatesReadinessAndPricingOrderingRule();
		CapabilityLlmPassTest.systemPromptInstructsNotToDuplicateAnExistingQualityRule();
		CapabilityLlmPassTest.userPromptReflectsEntityAndCompactCatalogue();
		CapabilityLlmPassTest.userPromptSaysMasterNotReachableWhenNotPromoted();
		CapabilityLlmPassTest.userPromptShowsProfileShapesAndEvidenceWhenPresent();
		CapabilityLlmPassTest.userPromptDelimitsCatalogueAsData();
		CapabilityLlmPassTest.getLibraryFunctionsUnknownLibraryIsRefused();
		CapabilityLlmPassTest.getLibraryFunctionsListsSignaturesAndDocs();
		CapabilityLlmPassTest.getLibraryFunctionsCapEnforced();
		CapabilityLlmPassTest.getLibraryFunctionsReportsRemainingBudget();
		CapabilityLlmPassTest.getServiceDetailsUnknownConnectorIsRefused();
		CapabilityLlmPassTest.getServiceDetailsListsOperationsAndReadiness();
		CapabilityLlmPassTest.getServiceDetailsDescribeFailureFallsBackToCoarseNames();
		CapabilityLlmPassTest.scriptRefusedBeforeLibraryDetailsFetched();
		CapabilityLlmPassTest.scriptUnknownAttributeIsRefused();
		CapabilityLlmPassTest.scriptUnknownZoneIsRefused();
		CapabilityLlmPassTest.scriptMasterZoneRefusedWhenNotPromoted();
		CapabilityLlmPassTest.scriptMasterZoneAcceptedWhenPromoted();
		CapabilityLlmPassTest.scriptUnknownFunctionIsRefused();
		CapabilityLlmPassTest.scriptAnyReturnTypeIsAccepted();
		CapabilityLlmPassTest.scriptImportNameStoredFromInventoryNotModel();
		CapabilityLlmPassTest.scriptBindingMissingParameterIsRefused();
		CapabilityLlmPassTest.scriptBindingExtraParameterIsRefused();
		CapabilityLlmPassTest.scriptBindingUnknownAttributeIsRefused();
		CapabilityLlmPassTest.scriptBindingIncompatibleTypeIsRefused();
		CapabilityLlmPassTest.scriptBindingMalformedEntryIsRefused();
		CapabilityLlmPassTest.scriptBindingLiteralAcceptedForUnmappableParameter();
		CapabilityLlmPassTest.scriptSucceedsAndRecordsBinding();
		CapabilityLlmPassTest.scriptAlreadyExistingIsInformational();
		CapabilityLlmPassTest.scriptMissingMessageIsRefused();
		CapabilityLlmPassTest.scriptBlankMessageIsRefused();
		CapabilityLlmPassTest.scriptMessageIsSanitisedToSingleLineAndBounded();
		CapabilityLlmPassTest.scriptMessageWithApostropheStoredVerbatimUnescaped();
		CapabilityLlmPassTest.sanitizeUserMessageCollapsesAndBounds();
		CapabilityLlmPassTest.serviceRefusedBeforeServiceDetailsFetched();
		CapabilityLlmPassTest.serviceUnknownConnectorOperationIsRefused();
		CapabilityLlmPassTest.serviceWriteOperationIsNeverACandidate();
		CapabilityLlmPassTest.serviceMissingRequiredInputIsRefused();
		CapabilityLlmPassTest.serviceEmptyRequiredInputsAcceptsAnyBinding();
		CapabilityLlmPassTest.serviceUnknownAttributeInBindingIsRefused();
		CapabilityLlmPassTest.serviceUnknownAttributeIsRefused();
		CapabilityLlmPassTest.serviceUnanchoredSuggestionIsRefused();
		CapabilityLlmPassTest.serviceAnchoredByFreeInputLabelWithoutAttribute();
		CapabilityLlmPassTest.serviceBindingAutoFilledFromAttributeWhenExactlyOneRequiredInput();
		CapabilityLlmPassTest.serviceBindingAutoFilledUnderValueKeyWhenNoRequiredInput();
		CapabilityLlmPassTest.servicePricingCopiedFromCatalogueOntoSuggestion();
		CapabilityLlmPassTest.serviceAlternativeToStoredWhenGiven();
		CapabilityLlmPassTest.serviceRequiredInputToBeProvidedAcceptedWithCappedConfidence();
		CapabilityLlmPassTest.serviceMissingRequiredInputRefusalOffersToBeProvided();
		CapabilityLlmPassTest.serviceLiteralBindingAcceptedForServiceInput();
		CapabilityLlmPassTest.serviceAllToBeProvidedWithNoAttributeIsStillUnanchored();
		CapabilityLlmPassTest.scriptParameterToBeProvidedAcceptedWithCappedConfidence();
		CapabilityLlmPassTest.derivationToBeProvidedParameterIsAcceptedAndNotCapped();
		CapabilityLlmPassTest.derivationWithoutARealAttributeIsStillRefused();
		CapabilityLlmPassTest.systemPromptAndSchemaOfADerivationGuideTheParameterChoice();
		CapabilityLlmPassTest.derivationBlankLiteralAcceptedAndCrossSystemKeyRecorded();
		CapabilityLlmPassTest.nationalNumbersNeedADefaultCountryNotABlankOne();
		CapabilityLlmPassTest.languageInstructionAndMeasuredFactsInThePrompt();
		CapabilityLlmPassTest.serviceReRunWithToBeProvidedTargetIsIdempotent();
		CapabilityLlmPassTest.scriptRejectedExistingTargetTellsModelExplicitly();
		CapabilityLlmPassTest.serviceRejectedExistingTargetTellsModelExplicitly();
		CapabilityLlmPassTest.attributesWithoutSuggestionOrNoteInitiallyListsEveryAttribute();
		CapabilityLlmPassTest.attributesWithoutSuggestionOrNoteExcludesASuggestedAttribute();
		CapabilityLlmPassTest.noteArrayWithGroupAndAttributesMarksAttributesCovered();
		CapabilityLlmPassTest.noteArrayUnknownAttributeNeverCountedNeverAnError();
		CapabilityLlmPassTest.noteLegacyTextFormStillWorks();
		CapabilityLlmPassTest.systemPromptInstructsGroupingAttributesFirst();
		CapabilityLlmPassTest.userPromptInstructsGroupingAttributesFirst();
		CapabilityLlmPassTest.serviceDescribeFailureStillAcceptsNotConfiguredSuggestion();
		CapabilityLlmPassTest.serviceAlreadyExistingIsInformational();
		CapabilityLlmPassTest.earlyStopOnRepeatedRefusal();
		CapabilityLlmPassTest.agentLoopEndToEnd();
		CapabilityLlmPassTest.agentLoopBatchesFiveToolCallsInOneTurn();
		CapabilityLlmPassTest.agentLoopRecoversFromProseOnlyAnswer();
		System.out.println("CapabilityLlmPassTest OK");
	}

	// -------------------------------------------------------------------------------------------------------- prompt

	private static void systemPromptIsDomainNeutral()
	{
		final String system = CapabilityLlmPass.systemPrompt();
		CapabilityLlmPassTest.check("names the four action tools",
			system.contains("get_library_functions") && system.contains("get_service_details") && system.contains("suggest_script_constraint")
				&& system.contains("suggest_external_service") && system.contains("note"));
		CapabilityLlmPassTest.check("mentions never a write operation", system.toLowerCase(Locale.ROOT).contains("never a write operation")
			|| system.toLowerCase(Locale.ROOT).contains("never valid"));
		CapabilityLlmPassTest.check("says catalogue/detail text is data, not instructions",
			system.toLowerCase(Locale.ROOT).contains("is data") && system.toLowerCase(Locale.ROOT).contains("never instructions"));
		CapabilityLlmPassTest.check("mentions literal binding", system.contains("literal"));
		CapabilityLlmPassTest.check("instructs calling the detail tool before suggesting",
			system.toLowerCase(Locale.ROOT).contains("get_library_functions") && system.contains("refused if you have not looked"));
		CapabilityLlmPassTest.check("instructs batching detail calls in one turn", system.toLowerCase(Locale.ROOT).contains("same turn"));
		// coordinator, 2026-09-21, round 3: a real DONE run answered entirely in prose and recorded nothing
		CapabilityLlmPassTest.check("says a plain text answer records nothing and every outcome must be a tool call",
			system.toLowerCase(Locale.ROOT).contains("records nothing") && system.toLowerCase(Locale.ROOT).contains("must go through"));
		CapabilityLlmPassTest.check("tells the model to finish with a single note tool call, never plain prose",
			system.toLowerCase(Locale.ROOT).contains("note one final time") && system.toLowerCase(Locale.ROOT).contains("never end the run with a plain text"));
		CapabilityLlmPassTest.check("never mentions Company/party-data vocabulary",
			!system.contains("Company") && !system.contains("SIREN") && !system.contains("LEI") && !system.toLowerCase(Locale.ROOT).contains("legal form"));
	}

	/**
	 * Service-choice lot (coordinator, 2026-09-21, item 2/3), required test: "ordering rule rendered in the
	 * prompt". The product owner's own question ("why does it suggest Melissa, which is paid, when
	 * OpenStreetMap is free?") is answered by a prompt rule, never Java: prefer READY over NOT_CONFIGURED,
	 * then FREE before FREEMIUM before PAID, UNKNOWN neither preferred nor penalised, and 'alternativeTo'
	 * required when suggesting a lesser choice anyway.
	 */
	private static void systemPromptStatesReadinessAndPricingOrderingRule()
	{
		final String system = CapabilityLlmPass.systemPrompt();
		CapabilityLlmPassTest.check("prefers READY over NOT_CONFIGURED", system.contains("READY") && system.toLowerCase(Locale.ROOT).contains("not_configured"));
		CapabilityLlmPassTest.check("orders FREE, then FREEMIUM, then PAID",
			system.indexOf("FREE") < system.indexOf("FREEMIUM") && system.indexOf("FREEMIUM") < system.indexOf("PAID"));
		CapabilityLlmPassTest.check("UNKNOWN pricing is neither preferred nor penalised", system.toLowerCase(Locale.ROOT).contains("neither preferred nor penalised"));
		CapabilityLlmPassTest.check("never hides a costlier/unconfigured alternative", system.toLowerCase(Locale.ROOT).contains("never hide it"));
		CapabilityLlmPassTest.check("requires alternativeTo when suggesting a lesser choice", system.contains("alternativeTo"));
		CapabilityLlmPassTest.check("every EXTERNAL_SERVICE suggestion must be anchored to a real attribute",
			system.toLowerCase(Locale.ROOT).contains("must be anchored to a real accepted attribute"));
	}

	/**
	 * Live fix (bug 8, docs/STAGING-PROPOSAL.md "live fixes"): measured live on project Personnes, the
	 * capability pass proposed a SCRIPT_CONSTRAINT duplicating a quality rule the quality pass had just
	 * proposed on the SAME field (email PATTERN + Fasttrack.lib.validator.email.isValid; same for phone).
	 * The instruction is generic - no domain example - so it is checked to name neither "phone" nor "email",
	 * unlike the pre-existing "phone-like attribute" SCRIPT_CONSTRAINT example a few sentences earlier.
	 */
	private static void systemPromptInstructsNotToDuplicateAnExistingQualityRule()
	{
		final String system = CapabilityLlmPass.systemPrompt();
		CapabilityLlmPassTest.check("tells the model an attribute may already carry a quality rule",
			system.contains("Quality rules already on this field"));
		final int dedupStart = system.indexOf("never propose a SCRIPT_CONSTRAINT");
		CapabilityLlmPassTest.check("tells the model never to propose a SCRIPT_CONSTRAINT checking the same thing", dedupStart >= 0);
		final int dedupEnd = system.indexOf("Prefer severity WARN");
		CapabilityLlmPassTest.check("the dedup sentence is found before the severity guidance that already existed", dedupEnd > dedupStart);
		final String dedupSentence = system.substring(dedupStart, dedupEnd);
		CapabilityLlmPassTest.check("requires saying what the new check adds that the existing rule cannot", dedupSentence.contains("what that is"));
		CapabilityLlmPassTest.check("the instruction itself is generic - no domain example (phone/email) hardcoded into it",
			!dedupSentence.toLowerCase(Locale.ROOT).contains("phone") && !dedupSentence.toLowerCase(Locale.ROOT).contains("email"));
	}

	private static void userPromptReflectsEntityAndCompactCatalogue()
	{
		final CapabilityLlmPass.EntityInfo entity = CapabilityLlmPassTest.entity(true);
		final String prompt = CapabilityLlmPass.userPrompt(entity, CapabilityLlmPassTest.libraries(), CapabilityLlmPassTest.connectors());
		CapabilityLlmPassTest.check("names the entity", prompt.contains("Entity: Product"));
		CapabilityLlmPassTest.check("lists the attribute", prompt.contains("phone"));
		CapabilityLlmPassTest.check("one compact line per library, with the import name and a function count",
			prompt.contains("- library/Fasttrack/validator/Phone (import as Fasttrack.validator.Phone): ") && prompt.contains("[3 function(s)]"));
		CapabilityLlmPassTest.check("never lists a full function signature in the compact catalogue (that is get_library_functions' job)",
			!prompt.contains("isValid("));
		CapabilityLlmPassTest.check("one compact line per connector, with its read/search operation names",
			prompt.contains("- REGISTRY (Company Registry, type registry, ") && prompt.contains("lookup, checkOnly"));
		CapabilityLlmPassTest.check("pricing shown in the compact catalogue line", prompt.contains("pricing PAID"));
		CapabilityLlmPassTest.check("says the master zone is reachable", prompt.contains("master zone: yes"));
		CapabilityLlmPassTest.check("tells the model to call the detail tools", prompt.contains("get_library_functions") && prompt.contains("get_service_details"));
	}

	private static void userPromptSaysMasterNotReachableWhenNotPromoted()
	{
		final CapabilityLlmPass.EntityInfo entity = CapabilityLlmPassTest.entity(false);
		final String prompt = CapabilityLlmPass.userPrompt(entity, CapabilityLlmPassTest.libraries(), CapabilityLlmPassTest.connectors());
		CapabilityLlmPassTest.check("says the master zone is not reachable", prompt.contains("master zone: no"));
	}

	/** Task brief: "exact profile numbers and value shapes exactly as QualityLlmSuggester obtains them" - never a real value, just numbers/shapes/kind-evidence. */
	private static void userPromptShowsProfileShapesAndEvidenceWhenPresent()
	{
		final CapabilityLlmPass.AttributeInfo phone = new CapabilityLlmPass.AttributeInfo("phone", "STRING", Integer.valueOf(20), Long.valueOf(1000), Double.valueOf(98.5),
			Long.valueOf(950), Arrays.asList(new CapabilityLlmPass.ShapeCount("999-999-9999", 800L), new CapabilityLlmPass.ShapeCount("(999) 999-9999", 150L)),
			Arrays.asList("TRIM"), Arrays.asList("PATTERN ([0-9]{3}-[0-9]{3}-[0-9]{4})"));
		final CapabilityLlmPass.EntityInfo entity = new CapabilityLlmPass.EntityInfo("Product", "Product", true, Arrays.asList(phone),
			Collections.<CapabilityLlmPass.ExistingSuggestion>emptyList());
		final String prompt = CapabilityLlmPass.userPrompt(entity, CapabilityLlmPassTest.libraries(), CapabilityLlmPassTest.connectors());
		CapabilityLlmPassTest.check("profile numbers shown", prompt.contains("1000 row(s)") && prompt.contains("98.5%") && prompt.contains("950"));
		CapabilityLlmPassTest.check("shapes shown, never a real phone number", prompt.contains("999-999-9999 (800)") && !prompt.contains("555-123-4567"));
		CapabilityLlmPassTest.check("accepted cleaning step evidence shown", prompt.contains("Accepted cleaning steps: TRIM"));
		// live fix (bug 8): the label no longer claims every listed rule is accepted - ebx.CapabilityLlmSuggester
		// now also feeds a PROPOSED one here (see its own Javadoc).
		CapabilityLlmPassTest.check("existing quality rule evidence shown",
			prompt.contains("Quality rules already on this field (accepted or proposed): PATTERN"));
	}

	/** Coordinator, 2026-09-21: catalogue text is untrusted data - the prompt must fence it clearly, never let it read as part of the instructions. */
	private static void userPromptDelimitsCatalogueAsData()
	{
		final CapabilityLlmPass.EntityInfo entity = CapabilityLlmPassTest.entity(true);
		final String prompt = CapabilityLlmPass.userPrompt(entity, CapabilityLlmPassTest.libraries(), CapabilityLlmPassTest.connectors());
		final int begin = prompt.indexOf("BEGIN CAPABILITY CATALOGUE");
		final int end = prompt.indexOf("END CAPABILITY CATALOGUE");
		CapabilityLlmPassTest.check("both delimiters present, in order", begin >= 0 && end > begin);
		CapabilityLlmPassTest.check("the library/connector listings sit between the delimiters",
			prompt.indexOf("Libraries (call get_library_functions") > begin && prompt.indexOf("Libraries (call get_library_functions") < end
				&& prompt.indexOf("Connectors (call get_service_details") > begin && prompt.indexOf("Connectors (call get_service_details") < end);
	}

	// ------------------------------------------------------------------------------------------------ detail tools

	private static void getLibraryFunctionsUnknownLibraryIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		final Result r = CapabilityLlmPassTest.callGetLibraryFunctions(session, "library/no/such/library");
		CapabilityLlmPassTest.check("unknown library refused: " + r.text, r.isError && r.text.contains("unknown library"));
	}

	private static void getLibraryFunctionsListsSignaturesAndDocs()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		final Result r = CapabilityLlmPassTest.callGetLibraryFunctions(session, "library/Fasttrack/validator/Phone");
		CapabilityLlmPassTest.check("full signature shown: " + r.text, !r.isError && r.text.contains("isValid(value: string): boolean"));
		CapabilityLlmPassTest.check("doc block shown", r.text.contains("Checks a phone number's format."));
	}

	/** Coverage lot, 2026-09-21: raised from 8 to 24 (see {@code Session.MAX_DETAIL_CALLS}'s own Javadoc for why). */
	private static void getLibraryFunctionsCapEnforced()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		Result last = null;
		for (int i = 0; i < 25; i++)
		{
			last = CapabilityLlmPassTest.callGetLibraryFunctions(session, "library/Fasttrack/validator/Phone");
		}
		CapabilityLlmPassTest.check("25th detail call over the cap of 24 is refused: " + last.text, last.isError && last.text.contains("capped at 24"));
	}

	/** Each successful detail call tells the model its own remaining budget (coverage lot, item 2d: "tell the model its remaining detail budget"). */
	private static void getLibraryFunctionsReportsRemainingBudget()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		final Result first = CapabilityLlmPassTest.callGetLibraryFunctions(session, "library/Fasttrack/validator/Phone");
		CapabilityLlmPassTest.check("23 of 24 remaining after the first call: " + first.text, first.text.contains("detail calls remaining this run: 23 of 24"));
		final Result second = CapabilityLlmPassTest.callGetServiceDetails(session, "REGISTRY");
		CapabilityLlmPassTest.check("budget shared across both detail tools - 22 remaining: " + second.text,
			second.text.contains("detail calls remaining this run: 22 of 24"));
	}

	private static void getServiceDetailsUnknownConnectorIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		final Result r = CapabilityLlmPassTest.callGetServiceDetails(session, "NO_SUCH_CONNECTOR");
		CapabilityLlmPassTest.check("unknown connector refused: " + r.text, r.isError && r.text.contains("unknown connector"));
	}

	private static void getServiceDetailsListsOperationsAndReadiness()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		final Result r = CapabilityLlmPassTest.callGetServiceDetails(session, "REGISTRY");
		CapabilityLlmPassTest.check("READY shown: " + r.text, !r.isError && r.text.contains("READY"));
		CapabilityLlmPassTest.check("pricing shown: " + r.text, r.text.contains("pricing PAID (per-lookup billing)"));
		CapabilityLlmPassTest.check("lookup operation with description and required input shown",
			r.text.contains("lookup (READ): looks up a company by name or id") && r.text.contains("required inputs: name"));
		CapabilityLlmPassTest.check("write operation never listed (never a candidate)", !r.text.contains("insert"));
	}

	/** Coordinator, 2026-09-21, item 2: a connector whose configuration is invalid falls back to the catalogue's own operation names rather than showing nothing. */
	private static void getServiceDetailsDescribeFailureFallsBackToCoarseNames()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		final Result r = CapabilityLlmPassTest.callGetServiceDetails(session, "BROKEN");
		CapabilityLlmPassTest.check("not configured, real reason shown: " + r.text,
			!r.isError && r.text.contains("NOT_CONFIGURED - authentication: An authentication secret is required"));
		CapabilityLlmPassTest.check("falls back to the coarse operation name from the catalogue", r.text.contains("lookup (READ)"));
	}

	// ------------------------------------------------------------------------------------------------ script tool

	/** Coordinator, 2026-09-21, item 1: a suggest_* call is refused when the model never called the matching detail tool this run - keeps a guess out. */
	private static void scriptRefusedBeforeLibraryDetailsFetched()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "x", 70);
		CapabilityLlmPassTest.check("refused before get_library_functions was called: " + r.text,
			r.isError && r.text.contains("get_library_functions('library/Fasttrack/validator/Phone')"));
	}

	private static void scriptUnknownAttributeIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final Result r = CapabilityLlmPassTest.callScript(session, "noSuchAttribute", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "x", 70);
		CapabilityLlmPassTest.check("unknown attribute refused: " + r.text, r.isError && r.text.contains("unknown attribute"));
	}

	private static void scriptUnknownZoneIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "PRODUCTION", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "x", 70);
		CapabilityLlmPassTest.check("unknown zone refused: " + r.text, r.isError && r.text.contains("STAGING or MASTER"));
	}

	private static void scriptMasterZoneRefusedWhenNotPromoted()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(false);
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "MASTER", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "x", 70);
		CapabilityLlmPassTest.check("MASTER refused for a non-promoted entity: " + r.text, r.isError && r.text.contains("not promoted"));
	}

	private static void scriptMasterZoneAcceptedWhenPromoted()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "MASTER", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "ERROR", "blocks a malformed phone at the master zone", 90);
		CapabilityLlmPassTest.check("MASTER accepted for a promoted entity: " + r.text, !r.isError);
		CapabilityLlmPassTest.check("one script constraint recorded", session.proposedScriptConstraints().size() == 1);
		CapabilityLlmPassTest.check("zone MASTER recorded", "MASTER".equals(session.proposedScriptConstraints().get(0).zone));
	}

	private static void scriptUnknownFunctionIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "noSuchFunction",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "x", 70);
		CapabilityLlmPassTest.check("unknown function refused: " + r.text, r.isError && r.text.contains("no function"));
	}

	/**
	 * Coordinator, 2026-09-21, BLOCKING fix: an earlier revision refused anything but boolean/string, which would have refused a real library's own documented entry point
	 * ({@code validate(...): json_object}) - a report is still accepted. Lot G2 (2026-10-17): a function that returns a plain VALUE (here a decimal) computes something, it
	 * cannot flag a record: refused, with the way out (a derivation).
	 */
	private static void scriptAnyReturnTypeIsAccepted()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		// checksum() returns 'decimal' in the fixture - a computed value, no verdict
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "checksum",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "computes a checksum", 70);
		CapabilityLlmPassTest.check("a decimal-returning function is refused as a constraint: " + r.text, r.isError && r.text.contains("suggest_script_derivation"));
		CapabilityLlmPassTest.check("nothing recorded", session.proposedScriptConstraints().isEmpty());
	}

	/** Coordinator, 2026-09-21: the import name is looked up from the inventory (get_script's own "name"), never chosen by the model - the tool schema has no such argument at all. */
	private static void scriptImportNameStoredFromInventoryNotModel()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "x", 70);
		CapabilityLlmPassTest.check("recorded: " + r.text, !r.isError);
		CapabilityLlmPassTest.check("import name copied from the FunctionRef looked up by the session",
			"Fasttrack.validator.Phone".equals(session.proposedScriptConstraints().get(0).libraryImportName));
	}

	private static void scriptBindingMissingParameterIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.MAPPER.createObjectNode(), "WARN", "x", 70);
		CapabilityLlmPassTest.check("missing parameter refused: " + r.text, r.isError && r.text.contains("missing parameter"));
	}

	private static void scriptBindingExtraParameterIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final ObjectNode binding = CapabilityLlmPassTest.MAPPER.createObjectNode();
		binding.put("value", "phone");
		binding.put("extra", "phone");
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid", binding, "WARN", "x", 70);
		CapabilityLlmPassTest.check("extra parameter refused: " + r.text, r.isError && r.text.contains("does not have"));
	}

	private static void scriptBindingUnknownAttributeIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "noSuchAttribute"), "WARN", "x", 70);
		CapabilityLlmPassTest.check("binding to an unknown attribute refused: " + r.text, r.isError && r.text.contains("not a real accepted attribute"));
	}

	private static void scriptBindingIncompatibleTypeIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		// weight() expects a 'decimal' parameter - postalCode is STRING, incompatible
		final Result r = CapabilityLlmPassTest.callScript(session, "postalCode", "STAGING", "library/Fasttrack/validator/Phone", "weight",
			CapabilityLlmPassTest.binding("amount", "postalCode"), "WARN", "x", 70);
		CapabilityLlmPassTest.check("incompatible type refused: " + r.text, r.isError && r.text.contains("not compatible"));
	}

	/** A binding entry that is neither a plain attribute-name string nor {@code {"literal":"..."}} is refused, naming both valid shapes. */
	private static void scriptBindingMalformedEntryIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final ObjectNode binding = CapabilityLlmPassTest.MAPPER.createObjectNode();
		binding.put("value", 42);
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid", binding, "WARN", "x", 70);
		CapabilityLlmPassTest.check("malformed binding entry refused: " + r.text, r.isError && r.text.contains("literal"));
	}

	/**
	 * Coordinator, 2026-09-21, real case: {@code validate(rawIban, addressCountryCode)} where the second
	 * parameter may have no matching attribute - bind it to a documented literal constant instead of dropping
	 * the whole proposal.
	 */
	private static void scriptBindingLiteralAcceptedForUnmappableParameter()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		CapabilityLlmPassTest.callGetLibraryFunctions(session, "library/Fasttrack/validator/IBAN");
		final ObjectNode binding = CapabilityLlmPassTest.MAPPER.createObjectNode();
		binding.put("rawIban", "phone"); // deliberately reusing the phone attribute as a stand-in string field
		binding.putObject("addressCountryCode").put("literal", "");
		final Result r = CapabilityLlmPassTest.callScript(session, null, "STAGING", "library/Fasttrack/validator/IBAN", "validate", binding, "WARN",
			"validates the IBAN, blank country fallback", 75);
		CapabilityLlmPassTest.check("recorded with a literal-bound parameter: " + r.text, !r.isError);
		final CapabilityLlmPass.ProposedScriptConstraint recorded = session.proposedScriptConstraints().get(session.proposedScriptConstraints().size() - 1);
		CapabilityLlmPassTest.check("attribute binding kept as a plain string", recorded.bindingJson.contains("\"rawIban\":\"phone\""));
		CapabilityLlmPassTest.check("literal binding stored as {\"literal\":\"...\"}: " + recorded.bindingJson, recorded.bindingJson.contains("\"addressCountryCode\":{\"literal\":\"\"}"));
	}

	private static void scriptSucceedsAndRecordsBinding()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "checks the phone format", 80);
		CapabilityLlmPassTest.check("recorded: " + r.text, !r.isError && r.text.contains("isValid"));
		CapabilityLlmPassTest.check("exactly one recorded", session.proposedScriptConstraints().size() == 1);
		CapabilityLlmPassTest.check("binding JSON carries the real parameter->attribute mapping",
			session.proposedScriptConstraints().get(0).bindingJson.contains("\"value\":\"phone\""));
	}

	/** Apply-ux fix (2026-09-21): 'message' is required - a real deployed run's own developer-jargon message ("'phone' failed phone.isValid.") must never happen again. */
	private static void scriptMissingMessageIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", null, "checks the phone format", 80);
		CapabilityLlmPassTest.check("refused: " + r.text, r.isError && r.text.contains("'message' is required"));
		CapabilityLlmPassTest.check("nothing recorded", session.proposedScriptConstraints().isEmpty());
	}

	private static void scriptBlankMessageIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "   ", "checks the phone format", 80);
		CapabilityLlmPassTest.check("refused: " + r.text, r.isError);
	}

	/** The sanitisation itself (single line, bounded to 160 characters) is unit-tested directly in CapabilityLlmPassTest's sibling below via CapabilityLlmPass.sanitizeUserMessage; this proves the tool wires it through end to end. */
	private static void scriptMessageIsSanitisedToSingleLineAndBounded()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final String multiLine = "Phone number\n  is not   recognised.\tCheck the format.";
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", multiLine, "checks the phone format", 80);
		CapabilityLlmPassTest.check("accepted: " + r.text, !r.isError);
		final String stored = session.proposedScriptConstraints().get(0).userMessage;
		CapabilityLlmPassTest.check("collapsed to a single line, no newline/tab: " + stored, !stored.contains("\n") && !stored.contains("\t"));
		CapabilityLlmPassTest.check("runs of whitespace collapsed: " + stored, stored.equals("Phone number is not recognised. Check the format."));

		final CapabilityLlmPass.Session session2 = CapabilityLlmPassTest.fetchedSession(true);
		final StringBuilder longMessage = new StringBuilder();
		while (longMessage.length() < 300)
		{
			longMessage.append("word ");
		}
		final Result r2 = CapabilityLlmPassTest.callScript(session2, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", longMessage.toString(), "checks the phone format", 80);
		CapabilityLlmPassTest.check("accepted (truncated, never refused for length): " + r2.text, !r2.isError);
		CapabilityLlmPassTest.check("bounded to 160 characters",
			session2.proposedScriptConstraints().get(0).userMessage.length() == CapabilityLlmPass.USER_MESSAGE_MAX_LENGTH);
	}

	/** Coordinator's own instruction: "tested with an apostrophe" - stored RAW (unescaped) here; ConstraintBodyBuilder.escapeEbxString does the EBX Script escaping later, at apply time. */
	private static void scriptMessageWithApostropheStoredVerbatimUnescaped()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "The number doesn't look valid.", "checks the phone format", 80);
		CapabilityLlmPassTest.check("accepted: " + r.text, !r.isError);
		CapabilityLlmPassTest.check("apostrophe kept as-is (not pre-escaped)", "The number doesn't look valid.".equals(session.proposedScriptConstraints().get(0).userMessage));
	}

	/** Direct unit test of the pure sanitiser itself, independent of the tool plumbing. */
	private static void sanitizeUserMessageCollapsesAndBounds()
	{
		CapabilityLlmPassTest.check("null -> empty, never throws", "".equals(CapabilityLlmPass.sanitizeUserMessage(null)));
		CapabilityLlmPassTest.check("blank -> empty", "".equals(CapabilityLlmPass.sanitizeUserMessage("   \n\t  ")));
		CapabilityLlmPassTest.check("trimmed and collapsed",
			"a b c".equals(CapabilityLlmPass.sanitizeUserMessage("  a   b\n\tc  ")));
		final StringBuilder tooLong = new StringBuilder();
		for (int i = 0; i < 200; i++)
		{
			tooLong.append('x');
		}
		CapabilityLlmPassTest.check("truncated, never refused", CapabilityLlmPass.sanitizeUserMessage(tooLong.toString()).length() == CapabilityLlmPass.USER_MESSAGE_MAX_LENGTH);
	}

	private static void scriptAlreadyExistingIsInformational()
	{
		final List<CapabilityLlmPass.ExistingSuggestion> existing = Arrays
			.asList(new CapabilityLlmPass.ExistingSuggestion(CapabilityLlmPass.SCRIPT_CONSTRAINT, "library/Fasttrack/validator/Phone", "isValid", null, null, "ACCEPTED"));
		final CapabilityLlmPass.EntityInfo entity = new CapabilityLlmPass.EntityInfo("Product", "Product", true, CapabilityLlmPassTest.entity(true).attributes, existing);
		final CapabilityLlmPass.Session session = new CapabilityLlmPass.Session(entity, CapabilityLlmPassTest.functionsByLibrary(), CapabilityLlmPassTest.connectors(),
			CapabilityLlmPassTest.describer());
		session.getLibraryFunctions(CapabilityLlmPassTest.libraryArgs("library/Fasttrack/validator/Phone"));
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "already there", 80);
		CapabilityLlmPassTest.check("already-existing is informational, not an error: " + r.text, !r.isError && r.text.contains("already suggested") && r.text.contains("ACCEPTED"));
		CapabilityLlmPassTest.check("nothing new recorded", session.proposedScriptConstraints().isEmpty());
	}

	// ----------------------------------------------------------------------------------------------- service tool

	/** Coordinator, 2026-09-21, item 1: same refusal-before-details discipline on the service side. */
	private static void serviceRefusedBeforeServiceDetailsFetched()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		final Result r = CapabilityLlmPassTest.callService(session, "phone", "REGISTRY", "lookup", CapabilityLlmPassTest.binding("name", "phone"), "x", 70);
		CapabilityLlmPassTest.check("refused before get_service_details was called: " + r.text, r.isError && r.text.contains("get_service_details('REGISTRY')"));
	}

	private static void serviceUnknownConnectorOperationIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "REGISTRY");
		final Result r = CapabilityLlmPassTest.callService(session, "phone", "REGISTRY", "noSuchOperation", CapabilityLlmPassTest.MAPPER.createObjectNode(), "x", 70);
		CapabilityLlmPassTest.check("unknown operation refused: " + r.text, r.isError && r.text.contains("no read or search operation"));
	}

	/** {@code describer()}'s REGISTRY fixture includes a write operation ("insert") precisely so this stays refused as "not in the candidate list", same as any unknown operation - {@code DescribedOperation.isCandidateKind}. */
	private static void serviceWriteOperationIsNeverACandidate()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "REGISTRY");
		final Result r = CapabilityLlmPassTest.callService(session, "phone", "REGISTRY", "insert", CapabilityLlmPassTest.MAPPER.createObjectNode(), "x", 70);
		CapabilityLlmPassTest.check("a write operation is never a valid target: " + r.text, r.isError && r.text.contains("no read or search operation"));
	}

	/**
	 * A binding that names a DIFFERENT input than the one required (never empty, so the service-choice lot's
	 * auto-fill - which only ever fires on a genuinely EMPTY binding - never kicks in and masks this refusal;
	 * {@link #serviceBindingAutoFilledFromAttributeWhenExactlyOneRequiredInput} covers the auto-fill path
	 * itself).
	 */
	private static void serviceMissingRequiredInputIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "REGISTRY");
		final Result r = CapabilityLlmPassTest.callService(session, "phone", "REGISTRY", "lookup", CapabilityLlmPassTest.binding("unrelatedInput", "postalCode"), "x", 70);
		CapabilityLlmPassTest.check("missing required input refused: " + r.text, r.isError && r.text.contains("missing"));
	}

	/** Coordinator, 2026-09-21: {@code requiredInputs} comes straight from {@code connectors_describe} - when it is genuinely empty, any binding of real attributes is accepted, no separate "unknown" gate any more. */
	private static void serviceEmptyRequiredInputsAcceptsAnyBinding()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "REGISTRY");
		final Result r = CapabilityLlmPassTest.callService(session, "phone", "REGISTRY", "checkOnly", CapabilityLlmPassTest.MAPPER.createObjectNode(),
			"no input required per the inventory", 65);
		CapabilityLlmPassTest.check("no required inputs -> nothing to bind, still recorded: " + r.text, !r.isError);
	}

	private static void serviceUnknownAttributeInBindingIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "REGISTRY");
		final Result r = CapabilityLlmPassTest.callService(session, "phone", "REGISTRY", "lookup", CapabilityLlmPassTest.binding("name", "noSuchAttribute"), "x", 70);
		CapabilityLlmPassTest.check("binding to an unknown attribute refused: " + r.text, r.isError && r.text.contains("not a real accepted attribute"));
	}

	/** Service-choice lot (coordinator, 2026-09-21, item 1), required test: the top-level 'attribute' argument itself naming an unknown attribute is refused - distinct from a binding entry naming one. */
	private static void serviceUnknownAttributeIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "REGISTRY");
		final Result r = CapabilityLlmPassTest.callService(session, "noSuchAttribute", "REGISTRY", "lookup", CapabilityLlmPassTest.binding("name", "phone"), "x", 70);
		CapabilityLlmPassTest.check("unknown attribute refused: " + r.text, r.isError && r.text.contains("unknown attribute"));
	}

	/**
	 * Service-choice lot (coordinator, 2026-09-21, item 1), required test: a suggestion with NEITHER 'attribute'
	 * NOR any real attribute in 'binding' is unanchored - the real capture's own VIES row (no attribute, empty
	 * binding, a rationale the model itself disbelieved) is exactly this case.
	 */
	private static void serviceUnanchoredSuggestionIsRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "REGISTRY");
		final Result r = CapabilityLlmPassTest.callService(session, null, "REGISTRY", "checkOnly", CapabilityLlmPassTest.MAPPER.createObjectNode(), "x", 70);
		CapabilityLlmPassTest.check("unanchored suggestion refused: " + r.text,
			r.isError && r.text.toLowerCase(Locale.ROOT).contains("names no attribute at all"));
		CapabilityLlmPassTest.check("nothing recorded", session.proposedServices().isEmpty());
	}

	/** A free-form binding label (not one of the operation's own required inputs, since checkOnly has none) still anchors the suggestion - no 'attribute' needed once binding names a real one. */
	private static void serviceAnchoredByFreeInputLabelWithoutAttribute()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "REGISTRY");
		final Result r = CapabilityLlmPassTest.callService(session, null, "REGISTRY", "checkOnly", CapabilityLlmPassTest.binding("query", "phone"),
			"reachability check keyed on the phone field", 60);
		CapabilityLlmPassTest.check("accepted via a free-form binding label: " + r.text, !r.isError);
		CapabilityLlmPassTest.check("recorded with no attribute of its own (entity-level)", session.proposedServices().get(0).attribute == null);
		CapabilityLlmPassTest.check("binding kept exactly as given", session.proposedServices().get(0).bindingJson.contains("\"query\":\"phone\""));
	}

	/** Service-choice lot, required test: 'attribute' given, 'binding' empty, exactly one required input - the factory auto-fills the binding under that input's own name, and says so on the record. */
	private static void serviceBindingAutoFilledFromAttributeWhenExactlyOneRequiredInput()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "REGISTRY");
		final Result r = CapabilityLlmPassTest.callService(session, "phone", "REGISTRY", "lookup", CapabilityLlmPassTest.MAPPER.createObjectNode(),
			"could verify the phone number", 70);
		CapabilityLlmPassTest.check("accepted, auto-fill noted in the result: " + r.text, !r.isError && r.text.toLowerCase(Locale.ROOT).contains("auto-filled"));
		CapabilityLlmPassTest.check("one service recorded", session.proposedServices().size() == 1);
		CapabilityLlmPassTest.check("binding auto-filled under the operation's own single required input ('name'): "
			+ session.proposedServices().get(0).bindingJson, session.proposedServices().get(0).bindingJson.contains("\"name\":\"phone\""));
	}

	/** Same auto-fill, but the operation has no (or more than one) required input - the factory falls back to the generic 'value' key. */
	private static void serviceBindingAutoFilledUnderValueKeyWhenNoRequiredInput()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "REGISTRY");
		final Result r = CapabilityLlmPassTest.callService(session, "phone", "REGISTRY", "checkOnly", CapabilityLlmPassTest.MAPPER.createObjectNode(),
			"reachability check on the phone field", 60);
		CapabilityLlmPassTest.check("accepted, auto-fill noted: " + r.text, !r.isError && r.text.toLowerCase(Locale.ROOT).contains("auto-filled"));
		CapabilityLlmPassTest.check("binding auto-filled under the generic 'value' key: " + session.proposedServices().get(0).bindingJson,
			session.proposedServices().get(0).bindingJson.contains("\"value\":\"phone\""));
	}

	/** Service-choice lot, required test: pricing is copied from the catalogue's own ConnectorSummary onto the stored suggestion - never a model argument (same discipline as ready/readinessDetail). */
	private static void servicePricingCopiedFromCatalogueOntoSuggestion()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "REGISTRY");
		final Result r = CapabilityLlmPassTest.callService(session, "phone", "REGISTRY", "lookup", CapabilityLlmPassTest.binding("name", "phone"), "x", 70);
		CapabilityLlmPassTest.check("recorded: " + r.text, !r.isError);
		CapabilityLlmPassTest.check("pricing PAID copied from the REGISTRY fixture's own ConnectorSummary", "PAID".equals(session.proposedServices().get(0).pricing));
		CapabilityLlmPassTest.check("pricingNote copied too", "per-lookup billing".equals(session.proposedServices().get(0).pricingNote));
	}

	/** {@code alternativeTo} is stored verbatim when the model supplies it, and left null otherwise - never inferred by this class (task brief: the MODEL reasons over readiness/pricing, this class only relays). */
	private static void serviceAlternativeToStoredWhenGiven()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "REGISTRY");
		final ObjectNode args = CapabilityLlmPassTest.MAPPER.createObjectNode();
		args.put("attribute", "phone");
		args.put("connectorId", "REGISTRY");
		args.put("operation", "lookup");
		args.set("binding", CapabilityLlmPassTest.binding("name", "phone"));
		args.put("alternativeTo", "OpenStreetMap/Nominatim (free)");
		args.put("rationale", "Melissa adds phone-carrier detail Nominatim does not have.");
		args.put("confidence", 65);
		final com.on.ebx.agent.LocalTool.Result raw = session.suggestExternalService(args);
		CapabilityLlmPassTest.check("recorded: " + raw.text, !raw.isError);
		CapabilityLlmPassTest.check("alternativeTo stored verbatim",
			"OpenStreetMap/Nominatim (free)".equals(session.proposedServices().get(0).alternativeTo));
	}

	// ------------------------------------------------------------------------------------- coverage lot: toBeProvided

	/**
	 * Coverage lot (coordinator, 2026-09-21, item 1), required test: the real GLEIF capture - a required input
	 * (registrationAuthority) with no real matching attribute - is now declared toBeProvided instead of being
	 * force-bound to the wrong attribute (the real run bound it to legalForm). Accepted, confidence capped.
	 */
	private static void serviceRequiredInputToBeProvidedAcceptedWithCappedConfidence()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "GLEIF");
		final ObjectNode binding = CapabilityLlmPassTest.MAPPER.createObjectNode();
		binding.put("registrationId", "phone"); // stand-in string attribute
		binding.putObject("registrationAuthority").put("toBeProvided", "no attribute names the issuing registration authority");
		final Result r = CapabilityLlmPassTest.callService(session, null, "GLEIF", "searchByRegistration", binding,
			"Could confirm the registration id against GLEIF.", 90);
		CapabilityLlmPassTest.check("accepted, confidence-cap noted: " + r.text, !r.isError && r.text.toLowerCase(Locale.ROOT).contains("confidence capped at 50"));
		CapabilityLlmPassTest.check("one service recorded", session.proposedServices().size() == 1);
		CapabilityLlmPassTest.check("confidence capped at 50 even though 90 was requested", session.proposedServices().get(0).confidence == 50);
		CapabilityLlmPassTest.check("bindingJson keeps the real attribute link", session.proposedServices().get(0).bindingJson.contains("\"registrationId\":\"phone\""));
		CapabilityLlmPassTest.check("bindingJson stores the toBeProvided entry as given: " + session.proposedServices().get(0).bindingJson,
			session.proposedServices().get(0).bindingJson.contains("\"registrationAuthority\":{\"toBeProvided\":\"no attribute names the issuing registration authority\"}"));
	}

	/** The refusal for a missing required input must offer the to-be-provided way explicitly, not just repeat "bind an attribute" (coordinator: "so the model stops forcing a link"). */
	private static void serviceMissingRequiredInputRefusalOffersToBeProvided()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "GLEIF");
		final Result r = CapabilityLlmPassTest.callService(session, null, "GLEIF", "searchByRegistration", CapabilityLlmPassTest.binding("registrationId", "phone"),
			"x", 70);
		CapabilityLlmPassTest.check("refused, offers toBeProvided explicitly: " + r.text,
			r.isError && r.text.contains("toBeProvided") && r.text.contains("registrationAuthority"));
	}

	/** Literal is now ALSO valid for a service input (previously attribute-only) - a fixed constant, no confidence cap (only toBeProvided caps). */
	private static void serviceLiteralBindingAcceptedForServiceInput()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "GLEIF");
		final ObjectNode binding = CapabilityLlmPassTest.MAPPER.createObjectNode();
		binding.put("registrationId", "phone");
		binding.putObject("registrationAuthority").put("literal", "RA000123"); // a fixed, documented authority code
		final Result r = CapabilityLlmPassTest.callService(session, null, "GLEIF", "searchByRegistration", binding, "Fixed registration authority for this market.",
			85);
		CapabilityLlmPassTest.check("accepted: " + r.text, !r.isError);
		CapabilityLlmPassTest.check("confidence NOT capped - only toBeProvided caps, a literal is a settled choice", session.proposedServices().get(0).confidence == 85);
		CapabilityLlmPassTest.check("bindingJson carries the literal shape: " + session.proposedServices().get(0).bindingJson,
			session.proposedServices().get(0).bindingJson.contains("\"registrationAuthority\":{\"literal\":\"RA000123\"}"));
	}

	/** The anchor rule stays: a literal/toBeProvided entry never anchors a suggestion by itself - all-toBeProvided with no real attribute link anywhere is still refused. */
	private static void serviceAllToBeProvidedWithNoAttributeIsStillUnanchored()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "GLEIF");
		final ObjectNode binding = CapabilityLlmPassTest.MAPPER.createObjectNode();
		binding.putObject("registrationId").put("toBeProvided", "not yet known");
		binding.putObject("registrationAuthority").put("toBeProvided", "not yet known either");
		final Result r = CapabilityLlmPassTest.callService(session, null, "GLEIF", "searchByRegistration", binding, "x", 70);
		CapabilityLlmPassTest.check("still refused as unanchored: " + r.text, r.isError && r.text.toLowerCase(Locale.ROOT).contains("names no attribute at all"));
		CapabilityLlmPassTest.check("nothing recorded", session.proposedServices().isEmpty());
	}

	/** Same toBeProvided/confidence-cap mechanism on the script side - the IBAN function's second parameter (addressCountryCode) left to-be-provided instead of a literal. */
	private static void scriptParameterToBeProvidedAcceptedWithCappedConfidence()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		CapabilityLlmPassTest.callGetLibraryFunctions(session, "library/Fasttrack/validator/IBAN");
		final ObjectNode binding = CapabilityLlmPassTest.MAPPER.createObjectNode();
		binding.put("rawIban", "phone");
		binding.putObject("addressCountryCode").put("toBeProvided", "no attribute carries the address country for this entity");
		final Result r = CapabilityLlmPassTest.callScript(session, null, "STAGING", "library/Fasttrack/validator/IBAN", "validate", binding, "WARN",
			"validates the IBAN, country still to be supplied", 95);
		CapabilityLlmPassTest.check("accepted, confidence-cap noted: " + r.text, !r.isError && r.text.toLowerCase(Locale.ROOT).contains("confidence capped at 50"));
		CapabilityLlmPassTest.check("confidence capped at 50", session.proposedScriptConstraints().get(0).confidence == 50);
		CapabilityLlmPassTest.check("bindingJson stores the toBeProvided entry: " + session.proposedScriptConstraints().get(0).bindingJson,
			session.proposedScriptConstraints().get(0).bindingJson.contains("\"addressCountryCode\":{\"toBeProvided\":\"no attribute carries the address country for this entity\"}"));
	}

	// ------------------------------------------------------------------------ fix/match-key-confidence (2026-09-26)

	/** A session whose only library holds a string-returning, two-parameter normalisation function (value, region) - the shape of the PersVol4 capture, domain-neutral. */
	private static CapabilityLlmPass.Session derivationSession()
	{
		final CapabilityLlmPass.FunctionRef canonical = new CapabilityLlmPass.FunctionRef("library/Fasttrack/lib/codes", "Fasttrack.lib.codes", "Codes",
			"Shared code helpers.", "canonical", Arrays.asList(new CapabilityLlmPass.ParamRef("value", "string"), new CapabilityLlmPass.ParamRef("region", "string")),
			"string", "The canonical form of a code. @param region leave null or blank to read it off the value itself.");
		final Map<String, List<CapabilityLlmPass.FunctionRef>> byLibrary = new LinkedHashMap<String, List<CapabilityLlmPass.FunctionRef>>();
		byLibrary.put(canonical.libraryIdePath, Collections.singletonList(canonical));
		final CapabilityLlmPass.Session session = new CapabilityLlmPass.Session(CapabilityLlmPassTest.entity(true), byLibrary, CapabilityLlmPassTest.connectors(),
			CapabilityLlmPassTest.describer());
		session.getLibraryFunctions(CapabilityLlmPassTest.libraryArgs(canonical.libraryIdePath));
		return session;
	}

	private static ObjectNode derivationArgs(final ObjectNode pBinding, final boolean pCrossSystemKey)
	{
		final ObjectNode args = CapabilityLlmPassTest.MAPPER.createObjectNode();
		args.put("newAttributeName", "phoneCanonical");
		args.put("libraryIdePath", "library/Fasttrack/lib/codes");
		args.put("functionName", "canonical");
		args.set("binding", pBinding);
		args.put("crossSystemKey", pCrossSystemKey);
		args.put("rationale", "one comparable shape across systems");
		args.put("confidence", 50);
		return args;
	}

	/**
	 * Lot P1 (2026-10-04): a derivation parameter the model cannot settle may be {@code toBeProvided} (measured on Vol1m: the forced blank country
	 * left 68,905 of 92,175 phones empty). It is ACCEPTED, stored as given, and - the PersVol4 regression this guards - NOT capped: accepting the
	 * attribute and applying its computation are two moments, a capped key attribute would fall out of the one-click accept and the matching plan.
	 * The stored rationale says which parameter a person must still provide; the block is at apply time ({@code DerivationApplier.prepare}).
	 */
	private static void derivationToBeProvidedParameterIsAcceptedAndNotCapped()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.derivationSession();
		final ObjectNode binding = CapabilityLlmPassTest.MAPPER.createObjectNode();
		binding.put("value", "phone");
		binding.putObject("region").put("toBeProvided", "no attribute carries the region; the national numbers need a default");
		final ObjectNode args = CapabilityLlmPassTest.derivationArgs(binding, true);
		args.put("confidence", 88);
		final com.on.ebx.agent.LocalTool.Result r = session.suggestScriptDerivation(args);
		CapabilityLlmPassTest.check("accepted, and the answer names the parameter left to a person: " + r.text, !r.isError && r.text.contains("region") && r.text.contains("person"));
		final CapabilityLlmPass.ProposedScriptDerivation d = session.proposedScriptDerivations().get(0);
		CapabilityLlmPassTest.check("confidence NOT capped (88 stays 88, not min(88, " + CapabilityLlmPass.TO_BE_PROVIDED_CONFIDENCE_CAP + ")): " + d.confidence, d.confidence == 88);
		CapabilityLlmPassTest.check("bound attributes = [phone] (the to-be-provided entry is no source): " + d.boundAttributes, d.boundAttributes.equals(Collections.singletonList("phone")));
		CapabilityLlmPassTest.check("the toBeProvided entry stored as given: " + d.bindingJson,
			d.bindingJson.contains("\"region\":{\"toBeProvided\":\"no attribute carries the region; the national numbers need a default\"}"));
		CapabilityLlmPassTest.check("rationale keeps the model's words and appends who must provide what: " + d.rationale,
			d.rationale.startsWith("one comparable shape across systems") && d.rationale.contains("A person must still provide region") && d.rationale.contains("(wizard, step 4)"));
	}

	/** Lot P1: a binding with NO real attribute is still refused, whether its entries are literals or to-be-provided - the derivation must read real data. */
	private static void derivationWithoutARealAttributeIsStillRefused()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.derivationSession();
		final ObjectNode binding = CapabilityLlmPassTest.MAPPER.createObjectNode();
		binding.putObject("value").put("toBeProvided", "which value");
		binding.putObject("region").put("literal", "FR");
		final com.on.ebx.agent.LocalTool.Result r = session.suggestScriptDerivation(CapabilityLlmPassTest.derivationArgs(binding, true));
		CapabilityLlmPassTest.check("refused: " + r.text, r.isError && r.text.contains("at least one real accepted attribute"));
		CapabilityLlmPassTest.check("nothing recorded", session.proposedScriptDerivations().isEmpty());
		final ObjectNode empty = CapabilityLlmPassTest.MAPPER.createObjectNode();
		empty.put("value", "phone");
		empty.putObject("region").put("toBeProvided", " ");
		CapabilityLlmPassTest.check("a toBeProvided with no reason is refused like everywhere else", session.suggestScriptDerivation(CapabilityLlmPassTest.derivationArgs(empty, true)).isError);
	}

	/** A blank literal is still a settled binding, stored as given, with the model's cross-system statement and every bound attribute kept for the measured-evidence decision. */
	private static void derivationBlankLiteralAcceptedAndCrossSystemKeyRecorded()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.derivationSession();
		final ObjectNode binding = CapabilityLlmPassTest.MAPPER.createObjectNode();
		binding.put("value", "phone");
		binding.putObject("region").put("literal", "");
		final com.on.ebx.agent.LocalTool.Result r = session.suggestScriptDerivation(CapabilityLlmPassTest.derivationArgs(binding, true));
		CapabilityLlmPassTest.check("accepted: " + r.text, !r.isError && r.text.contains("cross-system key"));
		final CapabilityLlmPass.ProposedScriptDerivation d = session.proposedScriptDerivations().get(0);
		CapabilityLlmPassTest.check("model number stored as given (the suggester decides from evidence)", d.confidence == 50);
		CapabilityLlmPassTest.check("crossSystemKey recorded", d.crossSystemKey);
		CapabilityLlmPassTest.check("bound attributes = [phone]: " + d.boundAttributes, d.boundAttributes.equals(Collections.singletonList("phone")));
		CapabilityLlmPassTest.check("blank literal stored: " + d.bindingJson, d.bindingJson.contains("\"region\":{\"literal\":\"\"}"));
		final ObjectNode other = CapabilityLlmPassTest.MAPPER.createObjectNode();
		other.put("value", "postalCode");
		other.putObject("region").put("literal", "");
		final ObjectNode args = CapabilityLlmPassTest.derivationArgs(other, false);
		args.put("newAttributeName", "postalCodeCanonical");
		args.remove("crossSystemKey");
		CapabilityLlmPassTest.check("crossSystemKey defaults to false when omitted", !session.suggestScriptDerivation(args).isError
			&& !session.proposedScriptDerivations().get(1).crossSystemKey);
	}

	/** The entity of {@link #derivationSession()}, whose phone attribute is measured (across every source feeding it) to hold national-format numbers. */
	private static CapabilityLlmPass.EntityInfo entityWithNationalPhones(final boolean pNational)
	{
		final String facts = NormalisationProfiler.phoneMix(Arrays.asList(NormalisationProfiler.profile(
			Arrays.asList(new Detectors.ValueCount("+33 6 01 31 09 18", 6L), new Detectors.ValueCount(pNational ? "06.01.31.09.18" : "+33 7 55 12 34 56", 4L)), -1L))).sentence();
		final List<CapabilityLlmPass.AttributeInfo> attributes = Arrays.asList(
			new CapabilityLlmPass.AttributeInfo("phone", "STRING", Integer.valueOf(20), Long.valueOf(10), Double.valueOf(100), Long.valueOf(2),
				Collections.<CapabilityLlmPass.ShapeCount>emptyList(), null, null, facts, pNational),
			new CapabilityLlmPass.AttributeInfo("country", "STRING", Integer.valueOf(2)));
		return new CapabilityLlmPass.EntityInfo("Person", "Person", true, attributes, Collections.<CapabilityLlmPass.ExistingSuggestion>emptyList());
	}

	private static CapabilityLlmPass.Session derivationSessionOn(final CapabilityLlmPass.EntityInfo pEntity)
	{
		final CapabilityLlmPass.FunctionRef canonical = new CapabilityLlmPass.FunctionRef("library/Fasttrack/lib/codes", "Fasttrack.lib.codes", "Codes",
			"Shared code helpers.", "canonical", Arrays.asList(new CapabilityLlmPass.ParamRef("value", "string"), new CapabilityLlmPass.ParamRef("region", "string")),
			"string", "The canonical form of a code. @param region the default country for national values.");
		final Map<String, List<CapabilityLlmPass.FunctionRef>> byLibrary = new LinkedHashMap<String, List<CapabilityLlmPass.FunctionRef>>();
		byLibrary.put(canonical.libraryIdePath, Collections.singletonList(canonical));
		final CapabilityLlmPass.Session session = new CapabilityLlmPass.Session(pEntity, byLibrary, CapabilityLlmPassTest.connectors(), CapabilityLlmPassTest.describer());
		session.getLibraryFunctions(CapabilityLlmPassTest.libraryArgs(canonical.libraryIdePath));
		return session;
	}

	/**
	 * Lot E (2026-10-17, coordinator addition (b)): the accepted {@code toE164WithDefault} derivation was bound to a blank default country and left all
	 * 394 phone numbers empty, on the grounds that "all profiled numbers include a leading plus" - true of the one mapping the shapes were read from,
	 * false of the sources in national format. When the phone attribute is measured to hold national numbers, a blank literal is refused (the honest ways
	 * out are listed); a country attribute, a literal the data establishes, or toBeProvided are accepted. Without national numbers a blank stays valid.
	 */
	private static void nationalNumbersNeedADefaultCountryNotABlankOne()
	{
		final CapabilityLlmPass.Session national = CapabilityLlmPassTest.derivationSessionOn(CapabilityLlmPassTest.entityWithNationalPhones(true));
		final ObjectNode blank = CapabilityLlmPassTest.MAPPER.createObjectNode();
		blank.put("value", "phone");
		blank.putObject("region").put("literal", "");
		final com.on.ebx.agent.LocalTool.Result refused = national.suggestScriptDerivation(CapabilityLlmPassTest.derivationArgs(blank, true));
		CapabilityLlmPassTest.check("a blank default is refused when national numbers exist: " + refused.text, refused.isError && refused.text.contains("national format")
			&& refused.text.contains("toBeProvided") && refused.text.contains("region"));
		CapabilityLlmPassTest.check("nothing recorded", national.proposedScriptDerivations().isEmpty());
		final ObjectNode attribute = CapabilityLlmPassTest.MAPPER.createObjectNode();
		attribute.put("value", "phone");
		attribute.put("region", "country");
		CapabilityLlmPassTest.check("bound to the country attribute: accepted", !national.suggestScriptDerivation(CapabilityLlmPassTest.derivationArgs(attribute, true)).isError);
		final ObjectNode literal = CapabilityLlmPassTest.MAPPER.createObjectNode();
		literal.put("value", "phone");
		literal.putObject("region").put("literal", "FR");
		final ObjectNode other = CapabilityLlmPassTest.derivationArgs(literal, true);
		other.put("newAttributeName", "phoneFr");
		CapabilityLlmPassTest.check("a literal the data establishes: accepted", !national.suggestScriptDerivation(other).isError);
		final ObjectNode toBeProvided = CapabilityLlmPassTest.MAPPER.createObjectNode();
		toBeProvided.put("value", "phone");
		toBeProvided.putObject("region").put("toBeProvided", "default country of the national numbers");
		final ObjectNode third = CapabilityLlmPassTest.derivationArgs(toBeProvided, true);
		third.put("newAttributeName", "phoneTbp");
		CapabilityLlmPassTest.check("toBeProvided: accepted", !national.suggestScriptDerivation(third).isError);

		final CapabilityLlmPass.Session international = CapabilityLlmPassTest.derivationSessionOn(CapabilityLlmPassTest.entityWithNationalPhones(false));
		CapabilityLlmPassTest.check("every number international: the blank default stays valid", !international.suggestScriptDerivation(CapabilityLlmPassTest.derivationArgs(blank, true)).isError);
	}

	private static void languageInstructionAndMeasuredFactsInThePrompt()
	{
		final CapabilityLlmPass.EntityInfo entity = CapabilityLlmPassTest.entityWithNationalPhones(true);
		final String prompt = CapabilityLlmPass.userPrompt(entity, Collections.<CapabilityLlmPass.LibrarySummary>emptyList(), Collections.<CapabilityLlmPass.ConnectorSummary>emptyList());
		CapabilityLlmPassTest.check("the cross-source phone facts reach the model, in a format, no number: " + prompt, prompt.contains("Measured across every source feeding it: phone-like across 1 source")
			&& prompt.contains("40% in national format") && !prompt.contains("01 31 09 18") && !prompt.contains("06.01"));
		CapabilityLlmPassTest.check("English is unchanged", CapabilityLlmPass.systemPrompt(java.util.Locale.ENGLISH).equals(CapabilityLlmPass.systemPrompt())
			&& CapabilityLlmPass.systemPrompt((java.util.Locale) null).equals(CapabilityLlmPass.systemPrompt()));
		final String french = CapabilityLlmPass.systemPrompt(java.util.Locale.FRENCH);
		CapabilityLlmPassTest.check("a French project asks for French rationales, labels and messages, identifiers untouched", french.startsWith(CapabilityLlmPass.systemPrompt())
			&& french.contains("LANGUAGE:") && french.contains("French") && french.contains("'newAttributeLabel'") && french.contains("newAttributeName"));
	}

	// -------------------------------------------------------------------------------------------- idempotence (item 3)

	/** "One suggestion per (entity, attribute set, target)": the SAME session, called twice with the identical target, records only once - the second call is informational. */
	/**
	 * "A re-run must not duplicate rows 6-9" (coordinator, item 3), confirmed with the NEW binding forms: a
	 * first run's suggestion (a to-be-provided GLEIF binding, mirroring the real capture) is persisted as an
	 * {@code ExistingSuggestion} the way {@code ebx.CapabilityLlmSuggester} would read it back on the next
	 * route call - a SECOND, fresh {@link CapabilityLlmPass.Session} (a genuine re-run, not the same session
	 * called twice: {@code ExistingSuggestion} carries no binding of its own, only the target key, exactly
	 * matching how re-run idempotence actually works - by target, never by binding shape) attempting the
	 * identical target is answered informationally, not duplicated.
	 */
	private static void serviceReRunWithToBeProvidedTargetIsIdempotent()
	{
		final CapabilityLlmPass.Session firstRun = CapabilityLlmPassTest.fetchedServiceSession(true, "GLEIF");
		final ObjectNode binding = CapabilityLlmPassTest.MAPPER.createObjectNode();
		binding.put("registrationId", "phone");
		binding.putObject("registrationAuthority").put("toBeProvided", "no attribute names it");
		final Result firstResult = CapabilityLlmPassTest.callService(firstRun, null, "GLEIF", "searchByRegistration", binding, "x", 90);
		CapabilityLlmPassTest.check("first run recorded it: " + firstResult.text, !firstResult.isError && firstRun.proposedServices().size() == 1);

		// what ebx.CapabilityLlmSuggester.readExisting would hand the NEXT run, after write() persisted the row above
		final List<CapabilityLlmPass.ExistingSuggestion> existing = Arrays
			.asList(new CapabilityLlmPass.ExistingSuggestion(CapabilityLlmPass.EXTERNAL_SERVICE, null, null, "GLEIF", "searchByRegistration", "PROPOSED"));
		final CapabilityLlmPass.EntityInfo entityForSecondRun = new CapabilityLlmPass.EntityInfo("Product", "Product", true,
			CapabilityLlmPassTest.entity(true).attributes, existing);
		final CapabilityLlmPass.Session secondRun = new CapabilityLlmPass.Session(entityForSecondRun, CapabilityLlmPassTest.functionsByLibrary(),
			CapabilityLlmPassTest.connectors(), CapabilityLlmPassTest.describer());
		secondRun.getServiceDetails(CapabilityLlmPassTest.connectorArgs("GLEIF"));
		final Result secondResult = CapabilityLlmPassTest.callService(secondRun, null, "GLEIF", "searchByRegistration", binding, "x", 90);
		CapabilityLlmPassTest.check("re-run informational, not duplicated: " + secondResult.text,
			!secondResult.isError && secondResult.text.contains("already suggested"));
		CapabilityLlmPassTest.check("nothing new recorded on the second run", secondRun.proposedServices().isEmpty());
	}

	/** A REJECTED existing script-constraint target is called out explicitly - "same rule as the quality pass" (coordinator, item 3). */
	private static void scriptRejectedExistingTargetTellsModelExplicitly()
	{
		final List<CapabilityLlmPass.ExistingSuggestion> existing = Arrays
			.asList(new CapabilityLlmPass.ExistingSuggestion(CapabilityLlmPass.SCRIPT_CONSTRAINT, "library/Fasttrack/validator/Phone", "isValid", null, null, "REJECTED"));
		final CapabilityLlmPass.EntityInfo entity = new CapabilityLlmPass.EntityInfo("Product", "Product", true, CapabilityLlmPassTest.entity(true).attributes, existing);
		final CapabilityLlmPass.Session session = new CapabilityLlmPass.Session(entity, CapabilityLlmPassTest.functionsByLibrary(), CapabilityLlmPassTest.connectors(),
			CapabilityLlmPassTest.describer());
		session.getLibraryFunctions(CapabilityLlmPassTest.libraryArgs("library/Fasttrack/validator/Phone"));
		final Result r = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "x", 80);
		CapabilityLlmPassTest.check("told explicitly it was rejected: " + r.text,
			!r.isError && r.text.contains("REJECTED by a reviewer") && r.text.toLowerCase(Locale.ROOT).contains("do not repropose"));
		CapabilityLlmPassTest.check("nothing new recorded", session.proposedScriptConstraints().isEmpty());
	}

	/** Same REJECTED-aware wording on the service side. */
	private static void serviceRejectedExistingTargetTellsModelExplicitly()
	{
		final List<CapabilityLlmPass.ExistingSuggestion> existing = Arrays
			.asList(new CapabilityLlmPass.ExistingSuggestion(CapabilityLlmPass.EXTERNAL_SERVICE, null, null, "REGISTRY", "lookup", "REJECTED"));
		final CapabilityLlmPass.EntityInfo entity = new CapabilityLlmPass.EntityInfo("Product", "Product", true, CapabilityLlmPassTest.entity(true).attributes, existing);
		final CapabilityLlmPass.Session session = new CapabilityLlmPass.Session(entity, CapabilityLlmPassTest.functionsByLibrary(), CapabilityLlmPassTest.connectors(),
			CapabilityLlmPassTest.describer());
		session.getServiceDetails(CapabilityLlmPassTest.connectorArgs("REGISTRY"));
		final Result r = CapabilityLlmPassTest.callService(session, "phone", "REGISTRY", "lookup", CapabilityLlmPassTest.binding("name", "phone"), "x", 70);
		CapabilityLlmPassTest.check("told explicitly it was rejected: " + r.text,
			!r.isError && r.text.contains("REJECTED by a reviewer") && r.text.toLowerCase(Locale.ROOT).contains("do not repropose"));
		CapabilityLlmPassTest.check("nothing new recorded", session.proposedServices().isEmpty());
	}

	// ------------------------------------------------------------------------------------- coverage lot: note/groups

	private static void attributesWithoutSuggestionOrNoteInitiallyListsEveryAttribute()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		final List<String> gaps = session.attributesWithoutSuggestionOrNote();
		CapabilityLlmPassTest.check("both fixture attributes listed initially: " + gaps, gaps.contains("phone") && gaps.contains("postalCode") && gaps.size() == 2);
	}

	private static void attributesWithoutSuggestionOrNoteExcludesASuggestedAttribute()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "x", 70);
		final List<String> gaps = session.attributesWithoutSuggestionOrNote();
		CapabilityLlmPassTest.check("phone no longer a gap, postalCode still is: " + gaps, !gaps.contains("phone") && gaps.contains("postalCode"));
	}

	/** A {@code notes[]} entry's own {@code attributes} marks them covered - group text itself is never checked, only relayed (coordinator: "the factory cannot know what an address is"). */
	private static void noteArrayWithGroupAndAttributesMarksAttributesCovered()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		final ObjectNode args = CapabilityLlmPassTest.MAPPER.createObjectNode();
		final com.fasterxml.jackson.databind.node.ArrayNode notes = args.putArray("notes");
		final ObjectNode entry = notes.addObject();
		entry.put("text", "No free-tier validator covers a bare postal code with no country to anchor it.");
		entry.put("group", "location");
		entry.putArray("attributes").add("postalCode");
		final com.on.ebx.agent.LocalTool.Result r = session.note(args);
		CapabilityLlmPassTest.check("recorded: " + r.text, !r.isError && r.text.contains("1 group"));
		CapabilityLlmPassTest.check("rendered note carries the group label and the attribute", session.notes().get(0).startsWith("location: ")
			&& session.notes().get(0).contains("[postalCode]"));
		final List<String> gaps = session.attributesWithoutSuggestionOrNote();
		CapabilityLlmPassTest.check("postalCode now covered by the note, phone still a gap: " + gaps, !gaps.contains("postalCode") && gaps.contains("phone"));
	}

	/** An unknown attribute name in a note's {@code attributes} is silently not counted - never an error (a note's own bookkeeping fields are never enforced). */
	private static void noteArrayUnknownAttributeNeverCountedNeverAnError()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		final ObjectNode args = CapabilityLlmPassTest.MAPPER.createObjectNode();
		final com.fasterxml.jackson.databind.node.ArrayNode notes = args.putArray("notes");
		final ObjectNode entry = notes.addObject();
		entry.put("text", "Nothing fits this group.");
		entry.putArray("attributes").add("noSuchAttribute");
		final com.on.ebx.agent.LocalTool.Result r = session.note(args);
		CapabilityLlmPassTest.check("still recorded, never refused over an unknown attribute name: " + r.text, !r.isError);
		final List<String> gaps = session.attributesWithoutSuggestionOrNote();
		CapabilityLlmPassTest.check("gap list unaffected by the unknown name", gaps.contains("phone") && gaps.contains("postalCode"));
	}

	/** The legacy single-{@code text} note form still works (backward compatibility, same convention {@code quality.QualityLlmPass} follows). */
	private static void noteLegacyTextFormStillWorks()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(true);
		final ObjectNode args = CapabilityLlmPassTest.MAPPER.createObjectNode();
		args.put("text", "Nothing fits this entity today.");
		final com.on.ebx.agent.LocalTool.Result r = session.note(args);
		CapabilityLlmPassTest.check("recorded: " + r.text, !r.isError);
		CapabilityLlmPassTest.check("plain text kept, no group prefix", "Nothing fits this entity today.".equals(session.notes().get(0)));
	}

	private static void systemPromptInstructsGroupingAttributesFirst()
	{
		final String system = CapabilityLlmPass.systemPrompt();
		CapabilityLlmPassTest.check("instructs grouping attributes by nature in the model's own words",
			system.toLowerCase(Locale.ROOT).contains("group") && system.toLowerCase(Locale.ROOT).contains("in your own words"));
		CapabilityLlmPassTest.check("says the factory cannot know what an address is",
			system.toLowerCase(Locale.ROOT).contains("does not know what") || system.toLowerCase(Locale.ROOT).contains("what \\\"an address\\\" is".replace("\\\"", "\"")));
		CapabilityLlmPassTest.check("mentions toBeProvided", system.contains("toBeProvided"));
		CapabilityLlmPassTest.check("mentions a REJECTED target is told explicitly", system.toLowerCase(Locale.ROOT).contains("rejected by a reviewer"));
	}

	/** Lot P1: the derivation contract no longer says "NEVER toBeProvided / bind blank" - it asks the model to settle a completing parameter honestly. */
	private static void systemPromptAndSchemaOfADerivationGuideTheParameterChoice()
	{
		final String system = CapabilityLlmPass.systemPrompt();
		CapabilityLlmPassTest.check("prompt: no more 'never toBeProvided' for a derivation", !system.contains("never toBeProvided") && !system.contains("never use toBeProvided"));
		CapabilityLlmPassTest.check("prompt: names the parameters that complete values (default country/currency/locale)",
			system.contains("a default country for national phone numbers") && system.contains("a default currency") && system.contains("a locale"));
		CapabilityLlmPassTest.check("prompt: prefer the variant that takes the default", system.contains("prefer the function variant that takes such a default"));
		CapabilityLlmPassTest.check("prompt: literal only when established, and say what establishes it", system.contains("ONLY when what you were given establishes the value")
			&& system.contains("say in the rationale what establishes it"));
		CapabilityLlmPassTest.check("prompt: a person supplies it before the apply, the attribute stays empty", system.contains("a person supplies it before the derivation is applied")
			&& system.contains("the new attribute stays empty"));
		CapabilityLlmPassTest.check("prompt: a blank literal only when the shapes show no value needs it", system.contains("only when the shapes show that no value needs the parameter"));
		CapabilityLlmPassTest.check("prompt: at least one real attribute stays", system.contains("AT LEAST ONE parameter must bind to a real accepted attribute"));
		final com.fasterxml.jackson.databind.JsonNode schema = CapabilityLlmPass.suggestScriptDerivationSchema();
		final String description = schema.path("properties").path("binding").path("description").asText("");
		CapabilityLlmPassTest.check("schema: the third shape is offered: " + description, description.contains("toBeProvided") && !description.contains("NEVER"));
		CapabilityLlmPassTest.check("schema: same guidance, blank literal only when no value needs it", description.contains("prefer the function variant that takes one")
			&& description.contains("A blank literal") && description.contains("AT LEAST ONE entry must name a real accepted attribute"));
	}

	private static void userPromptInstructsGroupingAttributesFirst()
	{
		final CapabilityLlmPass.EntityInfo entity = CapabilityLlmPassTest.entity(true);
		final String prompt = CapabilityLlmPass.userPrompt(entity, CapabilityLlmPassTest.libraries(), CapabilityLlmPassTest.connectors());
		CapabilityLlmPassTest.check("tells the model to group the accepted attributes first",
			prompt.toLowerCase(Locale.ROOT).contains("group the accepted attributes"));
	}

	/** Coordinator, 2026-09-21, item 2, required test: a connector whose describe call fails still accepts the suggestion, recorded NOT CONFIGURED with the real reason. */
	private static void serviceDescribeFailureStillAcceptsNotConfiguredSuggestion()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedServiceSession(true, "BROKEN");
		final Result r = CapabilityLlmPassTest.callService(session, "phone", "BROKEN", "lookup", CapabilityLlmPassTest.MAPPER.createObjectNode(),
			"could enrich this entity once configured", 55);
		CapabilityLlmPassTest.check("recorded even though connectors_describe failed: " + r.text, !r.isError && r.text.contains("connector not configured"));
		CapabilityLlmPassTest.check("one service recorded", session.proposedServices().size() == 1);
		CapabilityLlmPassTest.check("ready=false, real reason kept", !session.proposedServices().get(0).ready
			&& "authentication: An authentication secret is required".equals(session.proposedServices().get(0).readinessDetail));
	}

	private static void serviceAlreadyExistingIsInformational()
	{
		final List<CapabilityLlmPass.ExistingSuggestion> existing = Arrays
			.asList(new CapabilityLlmPass.ExistingSuggestion(CapabilityLlmPass.EXTERNAL_SERVICE, null, null, "REGISTRY", "lookup", "PROPOSED"));
		final CapabilityLlmPass.EntityInfo entity = new CapabilityLlmPass.EntityInfo("Product", "Product", true, CapabilityLlmPassTest.entity(true).attributes, existing);
		final CapabilityLlmPass.Session session = new CapabilityLlmPass.Session(entity, CapabilityLlmPassTest.functionsByLibrary(), CapabilityLlmPassTest.connectors(),
			CapabilityLlmPassTest.describer());
		session.getServiceDetails(CapabilityLlmPassTest.connectorArgs("REGISTRY"));
		final Result r = CapabilityLlmPassTest.callService(session, "phone", "REGISTRY", "lookup", CapabilityLlmPassTest.binding("name", "phone"), "already there", 60);
		CapabilityLlmPassTest.check("already-existing is informational: " + r.text, !r.isError && r.text.contains("already suggested"));
		CapabilityLlmPassTest.check("nothing new recorded", session.proposedServices().isEmpty());
	}

	// -------------------------------------------------------------------------------------------- circuit breaker

	private static void earlyStopOnRepeatedRefusal()
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.fetchedSession(true);
		Result r1 = CapabilityLlmPassTest.callScript(session, "phone", "PRODUCTION", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "x", 50);
		CapabilityLlmPassTest.check("1st: not stopped yet", r1.isError && !session.stoppedEarly());
		Result r2 = CapabilityLlmPassTest.callScript(session, "phone", "PRODUCTION", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "x", 50);
		CapabilityLlmPassTest.check("2nd: not stopped yet", r2.isError && !session.stoppedEarly());
		Result r3 = CapabilityLlmPassTest.callScript(session, "phone", "PRODUCTION", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "x", 50);
		CapabilityLlmPassTest.check("3rd: run stopped", r3.isError && session.stoppedEarly() && session.stopReason() != null);

		final Result r4 = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
			CapabilityLlmPassTest.binding("value", "phone"), "WARN", "valid this time", 80);
		CapabilityLlmPassTest.check("a subsequent call, even a valid one, is refused because the run is over", r4.isError && r4.text.toLowerCase(Locale.ROOT).contains("run is over"));
		CapabilityLlmPassTest.check("nothing was recorded", session.proposedScriptConstraints().isEmpty());
	}

	// ---------------------------------------------------------------------------------------------- end to end

	private static void agentLoopEndToEnd() throws Exception
	{
		final CapabilityLlmPass.EntityInfo entity = CapabilityLlmPassTest.entity(true);
		final CapabilityLlmPass.Session session = new CapabilityLlmPass.Session(entity, CapabilityLlmPassTest.functionsByLibrary(), CapabilityLlmPassTest.connectors(),
			CapabilityLlmPassTest.describer());
		final Map<String, ToolSet.LocalGroup> groups = new LinkedHashMap<String, ToolSet.LocalGroup>();
		groups.put("factory", session.toolGroup());
		final ToolSet tools = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), groups);

		final List<JsonNode> received = new ArrayList<JsonNode>();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				final JsonNode body = CapabilityLlmPassTest.MAPPER.readTree(CapabilityLlmPassTest.read(pExchange.getRequestBody()));
				received.add(body);
				final int index = received.size() - 1;
				final String[] blocks = CapabilityLlmPassTest.scriptedTurns();
				final String block = blocks[Math.min(index, blocks.length - 1)];
				final boolean toolUse = block.contains("\"tool_use\"");
				final String answer = "{\"content\":[" + block + "],\"stop_reason\":\"" + (toolUse ? "tool_use" : "end_turn") + "\",\"usage\":{\"input_tokens\":30,\"output_tokens\":10}}";
				CapabilityLlmPassTest.answerJson(pExchange, answer);
			}
		});
		server.start();
		try
		{
			final LlmClient client = new LlmClient(LlmClient.Format.ANTHROPIC, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages", null,
				"claude-sonnet-5", 1024, 2000, 5000);
			final AgentLoop loop = new AgentLoop(client, tools, new AgentLoop.Budget(20, 1000000, 60000L), null);
			final AgentLoop.Result result = loop.run(CapabilityLlmPass.systemPrompt(),
				CapabilityLlmPass.userPrompt(entity, CapabilityLlmPassTest.libraries(), CapabilityLlmPassTest.connectors()));

			CapabilityLlmPassTest.check("the run completes DONE", result.status == AgentLoop.Status.DONE);
			CapabilityLlmPassTest.check("one script constraint and one service proposed",
				session.proposedScriptConstraints().size() == 1 && session.proposedServices().size() == 1);
			CapabilityLlmPassTest.check("the script constraint is isValid on Product.phone",
				"isValid".equals(session.proposedScriptConstraints().get(0).functionName) && "phone".equals(session.proposedScriptConstraints().get(0).attribute));
			CapabilityLlmPassTest.check("the service is lookup of REGISTRY, not the refused write op",
				"lookup".equals(session.proposedServices().get(0).operation) && "REGISTRY".equals(session.proposedServices().get(0).connectorId));
		}
		finally
		{
			server.stop(0);
		}
	}

	private static String[] scriptedTurns()
	{
		return new String[] {
			CapabilityLlmPassTest.toolUse("g1", "factory__get_library_functions", "{\"library\":\"library/Fasttrack/validator/Phone\"}"),
			CapabilityLlmPassTest.toolUse("g2", "factory__get_service_details", "{\"connector\":\"REGISTRY\"}"),
			CapabilityLlmPassTest.toolUse("c1", "factory__suggest_script_constraint",
				"{\"attribute\":\"phone\",\"zone\":\"STAGING\",\"libraryIdePath\":\"library/Fasttrack/validator/Phone\",\"functionName\":\"isValid\","
					+ "\"binding\":{\"value\":\"phone\"},\"severity\":\"WARN\",\"message\":\"Phone number is not recognised: check the format.\",\"confidence\":80,"
					+ "\"rationale\":\"Checks the phone format.\"}"),
			CapabilityLlmPassTest.toolUse("c2", "factory__suggest_external_service",
				"{\"attribute\":\"phone\",\"connectorId\":\"REGISTRY\",\"operation\":\"insert\",\"binding\":{},\"confidence\":50,\"rationale\":\"Wrong on purpose.\"}"),
			CapabilityLlmPassTest.toolUse("c3", "factory__suggest_external_service",
				"{\"attribute\":\"phone\",\"connectorId\":\"REGISTRY\",\"operation\":\"lookup\",\"binding\":{\"name\":\"phone\"},\"confidence\":55,"
					+ "\"rationale\":\"Could confirm the phone number.\"}"),
			CapabilityLlmPassTest.toolUse("c4", "factory__note", "{\"text\":\"postalCode has no matching capability today.\"}"),
			CapabilityLlmPassTest.text("Proposed isValid on Product.phone and looked into REGISTRY#lookup.") };
	}

	/**
	 * Coordinator, 2026-09-21, required test: "5 tool calls in one turn all executed". {@code AgentLoop.run}
	 * already executes every entry of one turn's {@code toolCalls} before sending the next request ({@code
	 * AgentLoop.java}, the loop over {@code turn.toolCalls} inside one {@code step}) - this proves it end to
	 * end against this pass's own tools, with a single scripted response carrying 5 {@code tool_use} blocks.
	 */
	private static void agentLoopBatchesFiveToolCallsInOneTurn() throws Exception
	{
		final CapabilityLlmPass.EntityInfo entity = CapabilityLlmPassTest.entity(true);
		final CapabilityLlmPass.Session session = new CapabilityLlmPass.Session(entity, CapabilityLlmPassTest.functionsByLibrary(), CapabilityLlmPassTest.connectors(),
			CapabilityLlmPassTest.describer());
		final Map<String, ToolSet.LocalGroup> groups = new LinkedHashMap<String, ToolSet.LocalGroup>();
		groups.put("factory", session.toolGroup());
		final ToolSet tools = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), groups);

		final List<JsonNode> received = new ArrayList<JsonNode>();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				final JsonNode body = CapabilityLlmPassTest.MAPPER.readTree(CapabilityLlmPassTest.read(pExchange.getRequestBody()));
				received.add(body);
				final String answer;
				if (received.size() == 1)
				{
					// ONE turn, FIVE tool_use blocks - every one of them must be executed before the next request
					final String blocks = CapabilityLlmPassTest.toolUse("b1", "factory__get_library_functions", "{\"library\":\"library/Fasttrack/validator/Phone\"}")
						+ "," + CapabilityLlmPassTest.toolUse("b2", "factory__get_library_functions", "{\"library\":\"library/Fasttrack/validator/IBAN\"}") + ","
						+ CapabilityLlmPassTest.toolUse("b3", "factory__get_service_details", "{\"connector\":\"REGISTRY\"}") + ","
						+ CapabilityLlmPassTest.toolUse("b4", "factory__get_service_details", "{\"connector\":\"BROKEN\"}") + ","
						+ CapabilityLlmPassTest.toolUse("b5", "factory__note", "{\"text\":\"looked at everything in one batch.\"}");
					answer = "{\"content\":[" + blocks + "],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":30,\"output_tokens\":10}}";
				}
				else
				{
					answer = "{\"content\":[" + CapabilityLlmPassTest.text("Done.") + "],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
				}
				CapabilityLlmPassTest.answerJson(pExchange, answer);
			}
		});
		server.start();
		try
		{
			final LlmClient client = new LlmClient(LlmClient.Format.ANTHROPIC, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages", null,
				"claude-sonnet-5", 1024, 2000, 5000);
			final AgentLoop loop = new AgentLoop(client, tools, new AgentLoop.Budget(20, 1000000, 60000L), null);
			final AgentLoop.Result result = loop.run(CapabilityLlmPass.systemPrompt(),
				CapabilityLlmPass.userPrompt(entity, CapabilityLlmPassTest.libraries(), CapabilityLlmPassTest.connectors()));

			CapabilityLlmPassTest.check("the run completes DONE", result.status == AgentLoop.Status.DONE);
			CapabilityLlmPassTest.check("only 2 HTTP requests: one turn carrying all 5 calls, one final turn", received.size() == 2);
			CapabilityLlmPassTest.check("note from the batched turn recorded", session.notes().contains("looked at everything in one batch."));
			// proof all 5 calls actually ran: both libraries and both connectors are now usable by a suggest_* call
			final Result script = CapabilityLlmPassTest.callScript(session, "phone", "STAGING", "library/Fasttrack/validator/Phone", "isValid",
				CapabilityLlmPassTest.binding("value", "phone"), "WARN", "x", 70);
			CapabilityLlmPassTest.check("Phone library usable after the batch: " + script.text, !script.isError);
			final Result service = CapabilityLlmPassTest.callService(session, "phone", "REGISTRY", "lookup", CapabilityLlmPassTest.binding("name", "phone"), "x", 70);
			CapabilityLlmPassTest.check("REGISTRY connector usable after the batch: " + service.text, !service.isError);
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * Coordinator, 2026-09-21, round 3: a REAL deployed run ended DONE, 2 turns, its own text correctly
	 * describing a suggestion - and recorded NOTHING, because that description was prose, never a tool call.
	 * This proves the actual recovery mechanism end to end, with a fake HTTP provider and a real {@link
	 * AgentLoop}: two separate {@code loop.run(...)} calls against the SAME {@link CapabilityLlmPass.Session}
	 * (a fresh conversation each time - {@code AgentLoop.run} always starts one - but the Session's own state,
	 * here the library it already fetched details for, carries over as a plain Java object). {@code
	 * ebx.ProseAnswerRecoveryTest} covers the counting/decision logic ({@code ebx.ProseAnswerRecovery}) that
	 * sits on top of this mechanism in production; this test proves the mechanism itself works.
	 */
	private static void agentLoopRecoversFromProseOnlyAnswer() throws Exception
	{
		final CapabilityLlmPass.EntityInfo entity = CapabilityLlmPassTest.entity(true);
		final CapabilityLlmPass.Session session = new CapabilityLlmPass.Session(entity, CapabilityLlmPassTest.functionsByLibrary(), CapabilityLlmPassTest.connectors(),
			CapabilityLlmPassTest.describer());
		final Map<String, ToolSet.LocalGroup> groups = new LinkedHashMap<String, ToolSet.LocalGroup>();
		groups.put("factory", session.toolGroup());
		final ToolSet tools = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), groups);

		final List<JsonNode> received = new ArrayList<JsonNode>();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				final JsonNode body = CapabilityLlmPassTest.MAPPER.readTree(CapabilityLlmPassTest.read(pExchange.getRequestBody()));
				received.add(body);
				final String answer;
				if (received.size() == 1)
				{
					// turn 1 of the FIRST loop.run(): fetches the Phone library's details (real capture, 2026-09-21:
					// this half of the mechanism already worked live)
					final String blocks = CapabilityLlmPassTest.toolUse("g1", "factory__get_library_functions", "{\"library\":\"library/Fasttrack/validator/Phone\"}");
					answer = "{\"content\":[" + blocks + "],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":20,\"output_tokens\":8}}";
				}
				else if (received.size() == 2)
				{
					// turn 2 of the FIRST loop.run(): the real capture's own bug - describes a real suggestion in
					// PROSE, calling no tool at all. loop.run() ends here with stop_reason end_turn -> Status.DONE.
					answer = "{\"content\":[" + CapabilityLlmPassTest
						.text("Based on the details fetched, here is what I would suggest: 1. SCRIPT_CONSTRAINT isValid on Product.phone, library "
							+ "Fasttrack.validator.Phone, WARN severity.")
						+ "],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":40,\"output_tokens\":25}}";
				}
				else if (received.size() == 3)
				{
					// turn 1 of the SECOND loop.run() (the follow-up): the model now actually calls the tool. No
					// get_library_functions call this time - proves the Session's own fetched-library state
					// carried over from the FIRST loop.run(), even though the conversation itself is brand new.
					final String blocks = CapabilityLlmPassTest.toolUse("c1", "factory__suggest_script_constraint",
						"{\"attribute\":\"phone\",\"zone\":\"STAGING\",\"libraryIdePath\":\"library/Fasttrack/validator/Phone\",\"functionName\":\"isValid\","
							+ "\"binding\":{\"value\":\"phone\"},\"severity\":\"WARN\",\"message\":\"Phone number is not recognised: check the format.\","
							+ "\"confidence\":80,\"rationale\":\"Checks the phone format.\"}");
					answer = "{\"content\":[" + blocks + "],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":30,\"output_tokens\":12}}";
				}
				else
				{
					// turn 2 of the SECOND loop.run(): final wrap-up
					answer = "{\"content\":[" + CapabilityLlmPassTest.text("Recorded isValid on Product.phone as you asked.")
						+ "],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":6}}";
				}
				CapabilityLlmPassTest.answerJson(pExchange, answer);
			}
		});
		server.start();
		try
		{
			final LlmClient client = new LlmClient(LlmClient.Format.ANTHROPIC, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages", null,
				"claude-sonnet-5", 1024, 2000, 5000);
			final AgentLoop loop = new AgentLoop(client, tools, new AgentLoop.Budget(20, 1000000, 60000L), null);
			final String systemPrompt = CapabilityLlmPass.systemPrompt();
			final String userPrompt = CapabilityLlmPass.userPrompt(entity, CapabilityLlmPassTest.libraries(), CapabilityLlmPassTest.connectors());

			final AgentLoop.Result firstResult = loop.run(systemPrompt, userPrompt);
			CapabilityLlmPassTest.check("first run completes DONE", firstResult.status == AgentLoop.Status.DONE);
			CapabilityLlmPassTest.check("first run's own text describes the suggestion", firstResult.text.contains("isValid on Product.phone"));
			CapabilityLlmPassTest.check("nothing recorded after the first run - it was prose, not a tool call",
				session.proposedScriptConstraints().isEmpty() && session.proposedServices().isEmpty() && session.notes().isEmpty());

			// the follow-up task: quotes the first run's own text, same spirit as ebx.ProseAnswerRecovery.followUpTask
			// (that exact wording is unit-tested on its own, in ebx.ProseAnswerRecoveryTest - this string only needs
			// to be realistic enough to drive the fake provider through the same shape of exchange)
			final String followUpTask = "Nothing from your previous answer was recorded: a plain text answer is never saved, only a tool call is. Your previous "
				+ "answer, quoted for reference only: \"" + firstResult.text + "\". Record each item you described using the matching tool now.";
			final AgentLoop.Result followUpResult = loop.run(systemPrompt, followUpTask);

			CapabilityLlmPassTest.check("follow-up completes DONE", followUpResult.status == AgentLoop.Status.DONE);
			CapabilityLlmPassTest.check("exactly 4 HTTP requests total (2 turns per loop.run() call)", received.size() == 4);
			// the follow-up's scripted first turn calls suggest_script_constraint DIRECTLY, with no get_library_functions
			// call at all (unlike the very first loop.run()) - it only succeeds (checked below) because the Session's
			// own fetchedLibraries state, set during the FIRST loop.run(), is still there: a fresh conversation each
			// time, but the same Java Session object throughout. Had it not carried over, suggestScriptConstraint would
			// have been REFUSED ("you have not looked at ... yet this run"), and the check below would fail instead.
			CapabilityLlmPassTest.check("now recorded: one script constraint", session.proposedScriptConstraints().size() == 1);
			CapabilityLlmPassTest.check("it is isValid on Product.phone", "isValid".equals(session.proposedScriptConstraints().get(0).functionName)
				&& "phone".equals(session.proposedScriptConstraints().get(0).attribute));
		}
		finally
		{
			server.stop(0);
		}
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	private static CapabilityLlmPass.EntityInfo entity(final boolean pPromoted)
	{
		final List<CapabilityLlmPass.AttributeInfo> attributes = Arrays.asList(new CapabilityLlmPass.AttributeInfo("phone", "STRING", Integer.valueOf(20)),
			new CapabilityLlmPass.AttributeInfo("postalCode", "STRING", Integer.valueOf(10)));
		return new CapabilityLlmPass.EntityInfo("Product", "Product", pPromoted, attributes, Collections.<CapabilityLlmPass.ExistingSuggestion>emptyList());
	}

	private static List<CapabilityLlmPass.FunctionRef> functions()
	{
		final CapabilityLlmPass.FunctionRef isValid = new CapabilityLlmPass.FunctionRef("library/Fasttrack/validator/Phone", "Fasttrack.validator.Phone", "Phone",
			"Shared phone-number helpers.", "isValid", Arrays.asList(new CapabilityLlmPass.ParamRef("value", "string")), "boolean", "Checks a phone number's format.");
		final CapabilityLlmPass.FunctionRef checksum = new CapabilityLlmPass.FunctionRef("library/Fasttrack/validator/Phone", "Fasttrack.validator.Phone", "Phone",
			"Shared phone-number helpers.", "checksum", Arrays.asList(new CapabilityLlmPass.ParamRef("value", "string")), "decimal",
			"Returns a checksum - a non-boolean/string return type, on purpose (coordinator fixture).");
		final CapabilityLlmPass.FunctionRef weight = new CapabilityLlmPass.FunctionRef("library/Fasttrack/validator/Phone", "Fasttrack.validator.Phone", "Phone",
			"Shared phone-number helpers.", "weight", Arrays.asList(new CapabilityLlmPass.ParamRef("amount", "decimal")), "boolean", "Expects a decimal parameter.");
		// coordinator's own real fixture, 2026-09-21 (library/Fasttrack/lib/iban), domain reused here for the IBAN library name only
		final CapabilityLlmPass.FunctionRef validate = new CapabilityLlmPass.FunctionRef("library/Fasttrack/validator/IBAN", "Fasttrack.validator.iban", "IBAN",
			"Shared IBAN helpers: normalize, format, validate.", "validate",
			Arrays.asList(new CapabilityLlmPass.ParamRef("rawIban", "string"), new CapabilityLlmPass.ParamRef("addressCountryCode", "string")), "json_object",
			"Validates a raw IBAN, falling back to addressCountryCode when the IBAN itself carries no country.");
		return Arrays.asList(isValid, checksum, weight, validate);
	}

	private static Map<String, List<CapabilityLlmPass.FunctionRef>> functionsByLibrary()
	{
		final Map<String, List<CapabilityLlmPass.FunctionRef>> out = new LinkedHashMap<String, List<CapabilityLlmPass.FunctionRef>>();
		for (final CapabilityLlmPass.FunctionRef function : CapabilityLlmPassTest.functions())
		{
			List<CapabilityLlmPass.FunctionRef> forLibrary = out.get(function.libraryIdePath);
			if (forLibrary == null)
			{
				forLibrary = new ArrayList<CapabilityLlmPass.FunctionRef>();
				out.put(function.libraryIdePath, forLibrary);
			}
			forLibrary.add(function);
		}
		return out;
	}

	/** The compact catalogue line per library - what {@code ebx.CapabilityLlmSuggester.librarySummaries} would build from {@link #functionsByLibrary()}. */
	private static List<CapabilityLlmPass.LibrarySummary> libraries()
	{
		final List<CapabilityLlmPass.LibrarySummary> out = new ArrayList<CapabilityLlmPass.LibrarySummary>();
		for (final Map.Entry<String, List<CapabilityLlmPass.FunctionRef>> entry : CapabilityLlmPassTest.functionsByLibrary().entrySet())
		{
			final CapabilityLlmPass.FunctionRef first = entry.getValue().get(0);
			out.add(new CapabilityLlmPass.LibrarySummary(entry.getKey(), first.libraryImportName, CapabilityLlmPass.firstSentence(first.libraryDescription),
				entry.getValue().size()));
		}
		return out;
	}

	/**
	 * The compact catalogue line per connector - never carries kind/description/required inputs, only what
	 * {@code connectors_list} itself gives (coordinator, 2026-09-21). REGISTRY looks coarse-ready=false, on
	 * purpose (mirrors the real capture: "secrets not set"); BROKEN's live describe call will fail. Service-
	 * choice lot (2026-09-21): REGISTRY is tagged PAID (mirrors the real "Melissa" capture, a paid connector
	 * the model suggested) so pricing propagation can be exercised end to end.
	 */
	private static List<CapabilityLlmPass.ConnectorSummary> connectors()
	{
		return Arrays.asList(
			new CapabilityLlmPass.ConnectorSummary("REGISTRY", "Company Registry", "registry", false, "secrets not set for authType API_KEY_HEADER",
				Arrays.asList("lookup", "checkOnly"), "PAID", "per-lookup billing"),
			new CapabilityLlmPass.ConnectorSummary("BROKEN", "Broken Connector", "broken", false, "secrets not set for authType API_KEY_HEADER",
				Arrays.asList("lookup")),
			// coverage lot (coordinator, 2026-09-21): mirrors the real GLEIF capture - TWO required inputs, one of
			// which (registrationAuthority) has no real matching attribute in the fixture entity, exactly the shape
			// that used to force a wrong link
			new CapabilityLlmPass.ConnectorSummary("GLEIF", "GLEIF Registry", "gleif", true, null, Arrays.asList("searchByRegistration"), "FREE", null));
	}

	/**
	 * The lazy live seam - only ever called via {@code get_service_details} (coordinator, 2026-09-21, item 2).
	 * REGISTRY answers configured; BROKEN reproduces the real capture's "configuration is invalid" refusal.
	 * Filters to read/search operations only, exactly as the real {@code ebx.CapabilityLlmSuggester
	 * #describerFor} does before ever building a {@link CapabilityLlmPass.DescribeOutcome} - a write operation
	 * never reaches a {@link CapabilityLlmPass.Session} through this seam in production either.
	 */
	private static CapabilityLlmPass.ConnectorDescriber describer()
	{
		return new CapabilityLlmPass.ConnectorDescriber()
		{
			@Override
			public CapabilityLlmPass.DescribeOutcome describe(final String pConnectorId)
			{
				if ("REGISTRY".equals(pConnectorId))
				{
					final List<ServiceInventory.DescribedOperation> operations = Arrays.asList(
						new ServiceInventory.DescribedOperation("lookup", "READ", "looks up a company by name or id", Arrays.asList("name"),
							Collections.<String>emptyList()),
						new ServiceInventory.DescribedOperation("checkOnly", "READ", "checks reachability only, no input needed", Collections.<String>emptyList(),
							Collections.<String>emptyList()));
					// a real describerFor never even hands a WRITE operation to the Session - filtered before DescribeOutcome is built
					return CapabilityLlmPass.DescribeOutcome.configured(operations);
				}
				if ("BROKEN".equals(pConnectorId))
				{
					// real capture, 2026-09-21: "Connector 'X' configuration is invalid: authentication: An authentication secret is required"
					return CapabilityLlmPass.DescribeOutcome.notConfigured("authentication: An authentication secret is required");
				}
				if ("GLEIF".equals(pConnectorId))
				{
					final List<ServiceInventory.DescribedOperation> operations = Arrays.asList(new ServiceInventory.DescribedOperation("searchByRegistration",
						"SEARCH", "looks up a legal entity by its registration id and the registration authority that issued it",
						Arrays.asList("registrationId", "registrationAuthority"), Collections.<String>emptyList()));
					return CapabilityLlmPass.DescribeOutcome.configured(operations);
				}
				return CapabilityLlmPass.DescribeOutcome.notConfigured("no such connector in this fixture");
			}
		};
	}

	private static ObjectNode binding(final String pKey, final String pValue)
	{
		final ObjectNode node = CapabilityLlmPassTest.MAPPER.createObjectNode();
		node.put(pKey, pValue);
		return node;
	}

	private static CapabilityLlmPass.Session session(final boolean pPromoted)
	{
		return new CapabilityLlmPass.Session(CapabilityLlmPassTest.entity(pPromoted), CapabilityLlmPassTest.functionsByLibrary(), CapabilityLlmPassTest.connectors(),
			CapabilityLlmPassTest.describer());
	}

	/** A session that has already called {@code get_library_functions} on the Phone library - the state most script-tool tests need, since a bare {@link #session} now always refuses a first {@code suggest_script_constraint} call. */
	private static CapabilityLlmPass.Session fetchedSession(final boolean pPromoted)
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(pPromoted);
		session.getLibraryFunctions(CapabilityLlmPassTest.libraryArgs("library/Fasttrack/validator/Phone"));
		return session;
	}

	/** Same idea for the service side: {@code get_service_details} already called for {@code pConnectorId}. */
	private static CapabilityLlmPass.Session fetchedServiceSession(final boolean pPromoted, final String pConnectorId)
	{
		final CapabilityLlmPass.Session session = CapabilityLlmPassTest.session(pPromoted);
		session.getServiceDetails(CapabilityLlmPassTest.connectorArgs(pConnectorId));
		return session;
	}

	private static ObjectNode libraryArgs(final String pLibrary)
	{
		final ObjectNode node = CapabilityLlmPassTest.MAPPER.createObjectNode();
		node.put("library", pLibrary);
		return node;
	}

	private static ObjectNode connectorArgs(final String pConnectorId)
	{
		final ObjectNode node = CapabilityLlmPassTest.MAPPER.createObjectNode();
		node.put("connector", pConnectorId);
		return node;
	}

	// --------------------------------------------------------------------------------------------------- call helpers

	private static final class Result
	{
		final String text;
		final boolean isError;

		Result(final String pText, final boolean pIsError)
		{
			this.text = pText;
			this.isError = pIsError;
		}
	}

	private static Result callGetLibraryFunctions(final CapabilityLlmPass.Session pSession, final String pLibrary)
	{
		final com.on.ebx.agent.LocalTool.Result r = pSession.getLibraryFunctions(CapabilityLlmPassTest.libraryArgs(pLibrary));
		return new Result(r.text, r.isError);
	}

	private static Result callGetServiceDetails(final CapabilityLlmPass.Session pSession, final String pConnectorId)
	{
		final com.on.ebx.agent.LocalTool.Result r = pSession.getServiceDetails(CapabilityLlmPassTest.connectorArgs(pConnectorId));
		return new Result(r.text, r.isError);
	}

	/** The default 'message' every {@link #callScript} call carries - most tests are not about the message itself, so this keeps them unaffected by the apply-ux fix that made it required. */
	private static final String DEFAULT_TEST_MESSAGE = "Value is not accepted.";

	private static Result callScript(final CapabilityLlmPass.Session pSession, final String pAttribute, final String pZone, final String pLibraryIdePath,
		final String pFunctionName, final ObjectNode pBinding, final String pSeverity, final String pRationale, final int pConfidence)
	{
		return CapabilityLlmPassTest.callScript(pSession, pAttribute, pZone, pLibraryIdePath, pFunctionName, pBinding, pSeverity,
			CapabilityLlmPassTest.DEFAULT_TEST_MESSAGE, pRationale, pConfidence);
	}

	/** Full form - lets a message-specific test control 'message' directly (including omitting it, by passing null). */
	private static Result callScript(final CapabilityLlmPass.Session pSession, final String pAttribute, final String pZone, final String pLibraryIdePath,
		final String pFunctionName, final ObjectNode pBinding, final String pSeverity, final String pMessage, final String pRationale, final int pConfidence)
	{
		final ObjectNode args = CapabilityLlmPassTest.MAPPER.createObjectNode();
		if (pAttribute != null)
		{
			args.put("attribute", pAttribute);
		}
		args.put("zone", pZone);
		args.put("libraryIdePath", pLibraryIdePath);
		args.put("functionName", pFunctionName);
		args.set("binding", pBinding);
		args.put("severity", pSeverity);
		if (pMessage != null)
		{
			args.put("message", pMessage);
		}
		args.put("rationale", pRationale);
		args.put("confidence", pConfidence);
		final com.on.ebx.agent.LocalTool.Result r = pSession.suggestScriptConstraint(args);
		return new Result(r.text, r.isError);
	}

	private static Result callService(final CapabilityLlmPass.Session pSession, final String pAttribute, final String pConnectorId, final String pOperation,
		final ObjectNode pBinding, final String pRationale, final int pConfidence)
	{
		final ObjectNode args = CapabilityLlmPassTest.MAPPER.createObjectNode();
		if (pAttribute != null)
		{
			args.put("attribute", pAttribute);
		}
		args.put("connectorId", pConnectorId);
		args.put("operation", pOperation);
		args.set("binding", pBinding);
		args.put("rationale", pRationale);
		args.put("confidence", pConfidence);
		final com.on.ebx.agent.LocalTool.Result r = pSession.suggestExternalService(args);
		return new Result(r.text, r.isError);
	}

	// ------------------------------------------------------------------------------------------------------ plumbing

	private static String toolUse(final String pId, final String pName, final String pInputJson)
	{
		return "{\"type\":\"tool_use\",\"id\":\"" + pId + "\",\"name\":\"" + pName + "\",\"input\":" + pInputJson + "}";
	}

	private static String text(final String pText)
	{
		return "{\"type\":\"text\",\"text\":\"" + pText + "\"}";
	}

	private static void answerJson(final HttpExchange pExchange, final String pBody) throws IOException
	{
		final byte[] bytes = pBody.getBytes(StandardCharsets.UTF_8);
		pExchange.getResponseHeaders().add("Content-Type", "application/json");
		pExchange.sendResponseHeaders(200, bytes.length);
		pExchange.getResponseBody().write(bytes);
		pExchange.close();
	}

	private static String read(final InputStream pStream) throws IOException
	{
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final byte[] buffer = new byte[4096];
		for (int n = pStream.read(buffer); n >= 0; n = pStream.read(buffer))
		{
			out.write(buffer, 0, n);
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
