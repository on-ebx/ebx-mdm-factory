package com.on.ebx.factory.review;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class ProposalWordingTest
{
	public static void main(final String[] pArgs)
	{
		ProposalWordingTest.transcodeReferenceWithScopeMatchesTheTaskExample();
		ProposalWordingTest.transcodeReferenceWithoutScope();
		ProposalWordingTest.malformedTranscodeArgumentDegradesGracefully();
		ProposalWordingTest.aCodeTableReadsAsWordsNeverAsJson();
		ProposalWordingTest.everyMappingStepKindReadsAsWords();
		ProposalWordingTest.stripAccentsReadsAsPlainWords();
		ProposalWordingTest.mappingStepPositionPrefixed();
		ProposalWordingTest.everyQualityRuleKindReadsAsWords();
		ProposalWordingTest.qualityRuleSeverityPrefixed();
		ProposalWordingTest.unknownKindShowsRawTextRatherThanBlank();
		ProposalWordingTest.evidenceTextsHandleNeverCheckedAndRealNumbers();
		ProposalWordingTest.scriptConstraintPropositionReadsCleanly();
		ProposalWordingTest.externalServicePropositionShowsNotReady();
		ProposalWordingTest.externalServicePropositionShowsPricing();
		ProposalWordingTest.externalServicePropositionHidesUnknownPricing();
		ProposalWordingTest.capabilityBindingRendersPlainAttributes();
		ProposalWordingTest.capabilityBindingRendersLiteralValue();
		ProposalWordingTest.capabilityBindingRendersToBeProvidedValue();
		ProposalWordingTest.capabilityBindingHandlesEmptyAndMalformed();
		ProposalWordingTest.capabilityMeasuredEffectRendersRealCounts();
		ProposalWordingTest.capabilityMeasuredEffectNullCheckedMeansNotMeasured();
		ProposalWordingTest.capabilityMeasuredEffectNegativeCheckedMeansNotMeasured();
		ProposalWordingTest.foldKeyAttributePropositionNamesSourceAndPurpose();
		ProposalWordingTest.foldKeyAttributePropositionOnASecondDomain();
		ProposalWordingTest.foldKeyAttributePropositionDegradesGracefullyOnBlankInput();
		ProposalWordingTest.matchAlgorithmWordsCoverEveryKnownCode();
		ProposalWordingTest.matchAlgorithmWordsResolveOnTheBareSuffix();
		ProposalWordingTest.matchAlgorithmWordsShowUnknownCodeAsIs();
		ProposalWordingTest.matchAlgorithmWordsDegradeGracefullyOnBlankInput();
		ProposalWordingTest.feedPropositionCountsMappings();
		ProposalWordingTest.feedMappingsDetailJoinsEveryMapping();
		ProposalWordingTest.feedMappingsDetailEmptyWhenNoMapping();
		ProposalWordingTest.feedChildDetailShowsTheGivenPipeline();
		ProposalWordingTest.feedChildDetailFallsBackToTheDefaultWording();
		System.out.println("ProposalWordingTest OK");
	}

	// --------------------------------------------------------------------------------------------- MatchPlan (review)

	/** Every algorithm code named in the review-match-plan task brief - exact wording, matched case-insensitively on the part after the module prefix. */
	private static void matchAlgorithmWordsCoverEveryKnownCode()
	{
		check("exact", "identical".equals(ProposalWording.matchAlgorithmWords("ebx-addon-mame@exact")));
		check("fuzzyFullText", "resembles (fuzzy, full text)".equals(ProposalWording.matchAlgorithmWords("ebx-addon-mame@fuzzyFullText")));
		check("levenshtein", "close spelling".equals(ProposalWording.matchAlgorithmWords("ebx-addon-mame@levenshtein")));
		check("jaroWinkler", "close spelling (Jaro-Winkler)".equals(ProposalWording.matchAlgorithmWords("ebx-addon-mame@jaroWinkler")));
		check("soundex", "sounds alike".equals(ProposalWording.matchAlgorithmWords("ebx-addon-mame@soundex")));
		check("beider-morse", "sounds alike".equals(ProposalWording.matchAlgorithmWords("ebx-addon-mame@beider-morse")));
		check("phoneticFullText", "sounds alike".equals(ProposalWording.matchAlgorithmWords("ebx-addon-mame@phoneticFullText")));
		check("exactFullText", "same words".equals(ProposalWording.matchAlgorithmWords("ebx-addon-mame@exactFullText")));
		check("range", "within a range".equals(ProposalWording.matchAlgorithmWords("ebx-addon-mame@range")));
	}

	private static void matchAlgorithmWordsResolveOnTheBareSuffix()
	{
		check("bare suffix, no module prefix", "identical".equals(ProposalWording.matchAlgorithmWords("exact")));
	}

	private static void matchAlgorithmWordsShowUnknownCodeAsIs()
	{
		check("unrecognised code shown as-is", "ebx-addon-mame@somethingNew".equals(ProposalWording.matchAlgorithmWords("ebx-addon-mame@somethingNew")));
	}

	private static void matchAlgorithmWordsDegradeGracefullyOnBlankInput()
	{
		check("null degrades to a placeholder, never throws", "?".equals(ProposalWording.matchAlgorithmWords(null)));
		check("blank degrades to a placeholder, never throws", "?".equals(ProposalWording.matchAlgorithmWords("  ")));
	}

	// ------------------------------------------------------------------------------- StagingAttribute (derived)

	/** The task's own worked example (2026-09-22, PLAN.md): a matching key must show its SOURCE and its PURPOSE, with the exact before/after counts. */
	private static void foldKeyAttributePropositionNamesSourceAndPurpose()
	{
		final String words = ProposalWording.foldKeyAttributeProposition("cityKey", "city", 0, 12);
		ProposalWordingTest.check("names the key, the source, and says 'without accents or case': " + words,
			words.contains("cityKey") && words.contains("city") && words.contains("without accents or case"));
		ProposalWordingTest.check("carries the exact counts (12 after, 0 before): " + words, words.contains("12") && words.contains("0"));
		ProposalWordingTest.check("never names a specific pair of systems like 'CRM and ERP' - must read for any project", !words.contains("CRM") && !words.contains("ERP"));
	}

	/** A second domain (a product designation, never only company/city vocabulary) - the wording itself is domain-free. */
	private static void foldKeyAttributePropositionOnASecondDomain()
	{
		final String words = ProposalWording.foldKeyAttributeProposition("designationKey", "designation", 0, 7);
		ProposalWordingTest.check("reads correctly for a product attribute too: " + words, words.contains("designationKey") && words.contains("designation"));
	}

	/** Blank/null input degrades to "?" rather than throwing or showing "null" literally - same discipline as every other wording function in this file. */
	private static void foldKeyAttributePropositionDegradesGracefullyOnBlankInput()
	{
		final String words = ProposalWording.foldKeyAttributeProposition(null, "", 0, 1);
		ProposalWordingTest.check("degrades to '?' rather than 'null': " + words, !words.contains("null") && words.contains("?"));
	}

	// -------------------------------------------------------------------------------------------- CapabilitySuggestion

	private static void scriptConstraintPropositionReadsCleanly()
	{
		final String words = ProposalWording.scriptConstraintProposition("library/Fasttrack/validator/Phone", "isValid", "STAGING", "WARN");
		ProposalWordingTest.check("severity leads, then position/function/library/zone: " + words,
			words.startsWith("WARN - ") && words.contains("isValid") && words.contains("library/Fasttrack/validator/Phone") && words.contains("STAGING"));
	}

	private static void externalServicePropositionShowsNotReady()
	{
		final String ready = ProposalWording.externalServiceProposition("REGISTRY", "lookup", true, "UNKNOWN");
		ProposalWordingTest.check("ready: says so, no 'not configured': " + ready,
			ready.contains("lookup") && ready.contains("REGISTRY") && ready.contains("ready") && !ready.toLowerCase(java.util.Locale.ROOT).contains("not configured"));
		final String notReady = ProposalWording.externalServiceProposition("REGISTRY", "lookup", false, "UNKNOWN");
		ProposalWordingTest.check("not ready: says so: " + notReady, notReady.toLowerCase(java.util.Locale.ROOT).contains("not configured"));
	}

	/** Service-choice lot, 2026-09-21: "a service line shows readiness and pricing". */
	private static void externalServicePropositionShowsPricing()
	{
		final String free = ProposalWording.externalServiceProposition("OSM", "geocode", true, "FREE");
		ProposalWordingTest.check("free, ready: " + free, free.contains("ready") && free.contains("free"));
		final String paidNotReady = ProposalWording.externalServiceProposition("Melissa", "verify", false, "PAID");
		ProposalWordingTest.check("not configured AND paid both shown: " + paidNotReady,
			paidNotReady.contains("not configured") && paidNotReady.contains("paid"));
		final String freemium = ProposalWording.externalServiceProposition("Clearbit", "enrich", true, "freemium");
		ProposalWordingTest.check("pricing matched case-insensitively: " + freemium, freemium.contains("freemium"));
	}

	/** UNKNOWN is "neither preferred nor penalised" (task brief) - showing it in every row would read as a judgment that was never made, so it is silent. */
	private static void externalServicePropositionHidesUnknownPricing()
	{
		final String unknown = ProposalWording.externalServiceProposition("REGISTRY", "lookup", true, "UNKNOWN");
		ProposalWordingTest.check("UNKNOWN pricing never shown: " + unknown, !unknown.toLowerCase(java.util.Locale.ROOT).contains("unknown"));
		final String nullPricing = ProposalWording.externalServiceProposition("REGISTRY", "lookup", true, null);
		ProposalWordingTest.check("null pricing degrades the same way as UNKNOWN: " + nullPricing, !nullPricing.toLowerCase(java.util.Locale.ROOT).contains("null"));
	}

	private static void capabilityBindingRendersPlainAttributes()
	{
		final String words = ProposalWording.capabilityBinding("{\"value\":\"phone\"}");
		ProposalWordingTest.check("plain attribute binding renders unquoted: " + words, "Binding: value=phone".equals(words));
		final String two = ProposalWording.capabilityBinding("{\"a\":\"x\",\"b\":\"y\"}");
		ProposalWordingTest.check("two entries, both rendered: " + two, "Binding: a=x, b=y".equals(two));
	}

	/** Coordinator, 2026-09-21: a script parameter may be bound to a documented literal constant ({"literal":"value"}) - the review screen must render it readably, not as raw nested JSON. */
	private static void capabilityBindingRendersLiteralValue()
	{
		final String words = ProposalWording.capabilityBinding("{\"rawIban\":\"phone\",\"addressCountryCode\":{\"literal\":\"\"}}");
		ProposalWordingTest.check("attribute entry unquoted, literal entry marked and unquoted: " + words,
			words.contains("rawIban=phone") && words.contains("addressCountryCode=(literal) "));
		final String nonEmptyLiteral = ProposalWording.capabilityBinding("{\"scope\":{\"literal\":\"FR\"}}");
		ProposalWordingTest.check("non-empty literal value shown: " + nonEmptyLiteral, "Binding: scope=(literal) FR".equals(nonEmptyLiteral));
	}

	/** Coverage lot (coordinator, 2026-09-21, item 1): "the review wording shows it (registrationAuthority: to be provided - ...)" - deliberately NOT the key=value shape the other two use, so it stands out as unfinished. */
	private static void capabilityBindingRendersToBeProvidedValue()
	{
		final String words = ProposalWording
			.capabilityBinding("{\"registrationId\":\"registrationNo\",\"registrationAuthority\":{\"toBeProvided\":\"a registration authority is not a legal form\"}}");
		ProposalWordingTest.check("attribute entry unaffected: " + words, words.contains("registrationId=registrationNo"));
		ProposalWordingTest.check("toBeProvided entry rendered as 'key: to be provided - reason', never 'key=...': " + words,
			words.contains("registrationAuthority: to be provided - a registration authority is not a legal form")
				&& !words.contains("registrationAuthority=to be provided"));
	}

	private static void capabilityBindingHandlesEmptyAndMalformed()
	{
		ProposalWordingTest.check("null binding degrades to empty string", "".equals(ProposalWording.capabilityBinding(null)));
		ProposalWordingTest.check("blank binding degrades to empty string", "".equals(ProposalWording.capabilityBinding("  ")));
		ProposalWordingTest.check("empty object degrades to empty string", "".equals(ProposalWording.capabilityBinding("{}")));
		ProposalWordingTest.check("garbage never throws", "".equals(ProposalWording.capabilityBinding("not json at all")));
	}

	/** The exact wording the task itself gives as the target: {"reference":"LEGAL_FORM","scopeField":"country"} -> "Transcode to reference LEGAL_FORM, scoped by COUNTRY". */
	private static void transcodeReferenceWithScopeMatchesTheTaskExample()
	{
		final String words = ProposalWording.mappingStepProposition("TRANSCODE_REFERENCE", "{\"reference\":\"LEGAL_FORM\",\"scopeField\":\"country\"}", null);
		ProposalWordingTest.check("matches the task's own example: " + words, "Transcode to reference LEGAL_FORM, scoped by COUNTRY".equals(words));
	}

	private static void transcodeReferenceWithoutScope()
	{
		final String words = ProposalWording.mappingStepProposition("COUNTRY_TO_ISO2", null, null);
		ProposalWordingTest.check("plain country conversion reads cleanly: " + words, "Convert country name to ISO 3166-1 alpha-2 code".equals(words));
		final String unscoped = ProposalWording.mappingStepProposition("TRANSCODE_REFERENCE", "{\"reference\":\"COUNTRY\"}", null);
		ProposalWordingTest.check("no scopeField: no 'scoped by' clause: " + unscoped, "Transcode to reference COUNTRY".equals(unscoped));
	}

	/** Codes transcoding lot H3 (2026-10-17): the proposition of a step that carries its own code table. */
	private static void aCodeTableReadsAsWordsNeverAsJson()
	{
		final String words = ProposalWording.mappingStepProposition("TRANSCODE_REFERENCE", "{\"codes\":{\"M.\":\"MR\",\"Mme\":\"MRS\"},\"targets\":[\"MR\",\"MRS\",\"MISS\"],\"counts\":{\"M.\":77}}", Integer.valueOf(2));
		check("it says what it maps and onto what", "Step 2: Map the 2 codes of this source onto the common list (MR, MRS, MISS)".equals(words));
		check("never the JSON", !words.contains("{") && !words.contains("\""));
		final String reference = ProposalWording.mappingStepProposition("TRANSCODE_REFERENCE", "{\"codes\":{\"M.\":\"MR\"},\"targets\":[\"MR\"],\"basedOn\":\"TITLE\"}", null);
		check("and the reference its targets come from", reference.endsWith(", the codes of the reference TITLE"));
		final StringBuilder many = new StringBuilder("{\"codes\":{\"a\":\"T0\"},\"targets\":[");
		for (int i = 0; i < 12; i++)
		{
			many.append(i > 0 ? "," : "").append("\"T").append(i).append('"');
		}
		check("a long list is cut", ProposalWording.mappingStepProposition("TRANSCODE_REFERENCE", many.append("]}").toString(), null).contains("T7, ..."));
	}

	private static void malformedTranscodeArgumentDegradesGracefully()
	{
		final String noArg = ProposalWording.mappingStepProposition("TRANSCODE_REFERENCE", null, null);
		ProposalWordingTest.check("no argument at all: still readable, never throws: " + noArg, "Transcode to a reference list".equals(noArg));
		final String garbage = ProposalWording.mappingStepProposition("TRANSCODE_REFERENCE", "not json at all", null);
		ProposalWordingTest.check("garbage argument: still readable: " + garbage, "Transcode to a reference list".equals(garbage));
	}

	private static void everyMappingStepKindReadsAsWords()
	{
		final String[] kinds = { "TRIM", "COLLAPSE_SPACES", "EMPTY_TO_NULL", "UPPER", "LOWER", "STRIP_CHARS", "STRIP_ACCENTS", "NORMALISE_IDENTIFIER",
			"COUNTRY_TO_ISO2", "CURRENCY_TO_ISO", "TRANSCODE_REFERENCE", "TITLE_CASE" };
		for (final String kind : kinds)
		{
			final String words = ProposalWording.mappingStepProposition(kind, null, null);
			ProposalWordingTest.check("kind " + kind + " produces non-blank, non-raw-JSON words: '" + words + "'",
				words != null && !words.trim().isEmpty() && !words.startsWith("{"));
		}
	}

	/** STRIP_ACCENTS (2026-09-21): plain words, with the worked example from the task brief itself. */
	private static void stripAccentsReadsAsPlainWords()
	{
		final String words = ProposalWording.mappingStepProposition("STRIP_ACCENTS", null, null);
		ProposalWordingTest.check("mentions accents: " + words, words.toLowerCase(java.util.Locale.ROOT).contains("accent"));
	}

	private static void mappingStepPositionPrefixed()
	{
		final String words = ProposalWording.mappingStepProposition("TRIM", null, Integer.valueOf(2));
		ProposalWordingTest.check("position 2 is prefixed: " + words, words.startsWith("Step 2: "));
		final String noPosition = ProposalWording.mappingStepProposition("TRIM", null, null);
		ProposalWordingTest.check("no position: no 'Step' prefix: " + noPosition, !noPosition.startsWith("Step"));
		final String zeroPosition = ProposalWording.mappingStepProposition("TRIM", null, Integer.valueOf(0));
		ProposalWordingTest.check("position 0 (defensive, should not happen): no prefix either: " + zeroPosition, !zeroPosition.startsWith("Step"));
	}

	private static void everyQualityRuleKindReadsAsWords()
	{
		final String[] kinds = { "MANDATORY", "PATTERN", "REFERENCE", "CHECK_DIGIT", "UNIQUE" };
		for (final String kind : kinds)
		{
			final String words = ProposalWording.qualityRuleProposition(kind, "ARG", "WARN");
			ProposalWordingTest.check("kind " + kind + " produces readable words: '" + words + "'", words != null && !words.trim().isEmpty());
		}
	}

	private static void qualityRuleSeverityPrefixed()
	{
		final String reject = ProposalWording.qualityRuleProposition("CHECK_DIGIT", "IBAN", "REJECT");
		ProposalWordingTest.check("REJECT severity leads the line: " + reject, reject.startsWith("REJECT - "));
		final String warn = ProposalWording.qualityRuleProposition("CHECK_DIGIT", "IBAN", "WARN");
		ProposalWordingTest.check("WARN severity leads the line: " + warn, warn.startsWith("WARN - "));
		final String modelConstraint = ProposalWording.qualityRuleProposition("REFERENCE", "COUNTRY", "MODEL_CONSTRAINT");
		ProposalWordingTest.check("MODEL_CONSTRAINT severity leads the line: " + modelConstraint, modelConstraint.startsWith("MODEL_CONSTRAINT - "));
	}

	private static void unknownKindShowsRawTextRatherThanBlank()
	{
		final String words = ProposalWording.mappingStepProposition("SOME_FUTURE_KIND", "arg", null);
		ProposalWordingTest.check("an unrecognised kind still shows something useful, never blank: " + words, words.contains("SOME_FUTURE_KIND") && words.contains("arg"));
	}

	private static void evidenceTextsHandleNeverCheckedAndRealNumbers()
	{
		ProposalWordingTest.check("never checked (null seen)", ProposalWording.mappingStepEvidence(null, null, null).toLowerCase(java.util.Locale.ROOT).contains("never"));
		final String real = ProposalWording.mappingStepEvidence(Long.valueOf(55), Long.valueOf(2), Long.valueOf(0));
		ProposalWordingTest.check("real numbers show through: " + real, real.contains("55") && real.contains("2") && !real.contains("unresolved"));
		final String withUnresolved = ProposalWording.mappingStepEvidence(Long.valueOf(52), Long.valueOf(36), Long.valueOf(6));
		ProposalWordingTest.check("unresolved only shown when > 0: " + withUnresolved, withUnresolved.contains("6 unresolved"));

		ProposalWordingTest.check("quality rule never checked", ProposalWording.qualityRuleEvidence(null, null).toLowerCase(java.util.Locale.ROOT).contains("never"));
		ProposalWordingTest.check("quality rule real numbers", ProposalWording.qualityRuleEvidence(Long.valueOf(55), Long.valueOf(3)).contains("55")
			&& ProposalWording.qualityRuleEvidence(Long.valueOf(55), Long.valueOf(3)).contains("3"));

		ProposalWordingTest.check("last load never executed", ProposalWording.qualityRuleLastLoad(null, null).toLowerCase(java.util.Locale.ROOT).contains("not executed"));
		ProposalWordingTest.check("last load real numbers", ProposalWording.qualityRuleLastLoad(Long.valueOf(303), Long.valueOf(5)).contains("303")
			&& ProposalWording.qualityRuleLastLoad(Long.valueOf(303), Long.valueOf(5)).contains("5"));
	}

	// ------------------------------------------------------------------------------------ CapabilitySuggestion (apply lot)

	private static void capabilityMeasuredEffectRendersRealCounts()
	{
		final String text = ProposalWording.capabilityMeasuredEffect(Long.valueOf(303), Long.valueOf(4));
		ProposalWordingTest.check("real counts: " + text, text.contains("303") && text.contains("4") && text.contains("checked") && text.contains("reported"));
	}

	private static void capabilityMeasuredEffectNullCheckedMeansNotMeasured()
	{
		final String text = ProposalWording.capabilityMeasuredEffect(null, null);
		ProposalWordingTest.check("null recordsChecked reads as not measured, never '0 checked': " + text, text.toLowerCase(java.util.Locale.ROOT).contains("not measured"));
	}

	private static void capabilityMeasuredEffectNegativeCheckedMeansNotMeasured()
	{
		// ebx.CapabilityApplier's own sentinel for "the count itself could not be obtained" (never a hard failure)
		final String text = ProposalWording.capabilityMeasuredEffect(Long.valueOf(-1), null);
		ProposalWordingTest.check("negative sentinel reads as not measured too: " + text, text.toLowerCase(java.util.Locale.ROOT).contains("not measured"));
	}

	// --------------------------------------------------------------------------------- feeds (target-mapping lot 3)

	private static void feedPropositionCountsMappings()
	{
		ProposalWordingTest.check("zero mappings", "0 mapping(s).".equals(ProposalWording.feedProposition(0)));
		ProposalWordingTest.check("three mappings", "3 mapping(s).".equals(ProposalWording.feedProposition(3)));
	}

	private static void feedMappingsDetailJoinsEveryMapping()
	{
		final java.util.List<ProposalWording.FeedMapping> mappings = new java.util.ArrayList<ProposalWording.FeedMapping>();
		mappings.add(new ProposalWording.FeedMapping("NAME1", "name", "trim|upper"));
		mappings.add(new ProposalWording.FeedMapping("ID", "sourceId", "TRIM"));
		final String detail = ProposalWording.feedMappingsDetail(mappings);
		ProposalWordingTest.check("joined with '; ': " + detail, "NAME1 -> name (trim|upper); ID -> sourceId (TRIM)".equals(detail));
	}

	private static void feedMappingsDetailEmptyWhenNoMapping()
	{
		ProposalWordingTest.check("null list -> empty, never null", "".equals(ProposalWording.feedMappingsDetail(null)));
		ProposalWordingTest.check("empty list -> empty", "".equals(ProposalWording.feedMappingsDetail(new java.util.ArrayList<ProposalWording.FeedMapping>())));
	}

	private static void feedChildDetailShowsTheGivenPipeline()
	{
		final String detail = ProposalWording.feedChildDetail("personneRef", "concat(ID)|suffix(#MAIN)");
		ProposalWordingTest.check("names the parent link and the given pipeline: " + detail,
			"Parent link: personneRef, source id: concat(ID)|suffix(#MAIN).".equals(detail));
	}

	private static void feedChildDetailFallsBackToTheDefaultWording()
	{
		ProposalWordingTest.check("blank pipeline falls back to the loader's own default",
			ProposalWording.feedChildDetail("personneRef", "").contains("default"));
		ProposalWordingTest.check("null pipeline falls back too", ProposalWording.feedChildDetail("personneRef", null).contains("default"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
