package com.on.ebx.factory.quality;

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
import com.on.ebx.agent.AgentLoop;
import com.on.ebx.agent.LlmClient;
import com.on.ebx.agent.ToolSet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Domain-neutral fixtures
 * on purpose (task requirement): a product's GTIN, a site's postal code and country - never a company/SIREN/
 * IBAN example, so this test cannot silently regress into re-testing party/company data through the back
 * door. A single SENTINEL real value ({@link #SENTINEL}) is planted in the "name" column's fixture data and
 * never once expected to appear in a system prompt, a user prompt, or a tool result in {@code SHAPES_ONLY}
 * mode - {@link #sentinelNeverLeaksInShapesOnlyMode()} and {@link #agentLoopEndToEndNoSentinelLeak()} are the
 * two tests that check this directly, per the task's own instruction ("write a test that proves it").
 */
public final class QualityLlmPassTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** Planted only in the "name" column's real values - must never appear anywhere the model (or the fake HTTP provider) can see, in SHAPES_ONLY mode. */
	private static final String SENTINEL = "SECRET-DO-NOT-LEAK-99887766";

	public static void main(final String[] pArgs) throws Exception
	{
		QualityLlmPassTest.systemPromptIsDomainNeutral();
		QualityLlmPassTest.sentinelNeverLeaksInShapesOnlyMode();
		QualityLlmPassTest.sampleValuesModeShowsRealTopValuesInThePrompt();
		QualityLlmPassTest.measureUnknownColumnIsRefused();
		QualityLlmPassTest.measurePatternCountsCorrectly();
		QualityLlmPassTest.measureReferenceUnknownKindIsRefused();
		QualityLlmPassTest.measureCheckDigitUnknownAlgorithmIsRefused();
		QualityLlmPassTest.mappingStepNoOpIsRefused();
		QualityLlmPassTest.mappingStepTrimSucceedsOnDirtyData();
		QualityLlmPassTest.mappingStepTranscodeReferenceUnknownReferenceIsRefused();
		QualityLlmPassTest.mappingStepCountryToIso2BelowThresholdIsRefused();
		QualityLlmPassTest.mappingStepCountryToIso2AboveThresholdSucceeds();
		QualityLlmPassTest.mappingStepAlreadyExistingKindIsInformational();
		QualityLlmPassTest.ruleRejectSeverityIsRefused();
		QualityLlmPassTest.rulePatternInvalidRegexIsRefused();
		QualityLlmPassTest.catastrophicPatternIsRefusedBeforeItRuns();
		QualityLlmPassTest.rulePatternTooManyFailuresIsRefused();
		QualityLlmPassTest.rulePatternAcceptableFailuresSucceeds();
		QualityLlmPassTest.ruleCheckDigitOnRealGtinValues();
		QualityLlmPassTest.ruleReferenceModelConstraintNeedsHigherThresholdThanWarn();
		QualityLlmPassTest.ruleUniqueFindsDuplicates();
		QualityLlmPassTest.sampledColumnGateFollowsExactAggregates();
		QualityLlmPassTest.measureCallBudgetIsBounded();
		QualityLlmPassTest.ruleMandatoryNeedsFillRateProfile();
		QualityLlmPassTest.earlyStopOnRepeatedRefusal();
		QualityLlmPassTest.agentLoopEndToEndNoSentinelLeak();
		QualityLlmPassTest.columnsWithoutProposalOrNoteTracksCoverage();
		QualityLlmPassTest.columnsWithoutProposalOrNoteExemptsColumnsWithExistingProposals();
		QualityLlmPassTest.noteWithUnknownColumnIsRecordedButCoversNothing();
		QualityLlmPassTest.rulePatternDedupsIdenticalShapesAndCarriesMultiFormatCaveat();
		QualityLlmPassTest.rulePatternRefusalMessageCarriesMultiFormatCaveatAboveTenPercent();
		QualityLlmPassTest.ruleExamplesAreShapesNeverBeforeAfterPairs();
		QualityLlmPassTest.twoEntityRunsAreIndependentSecondStillGetsOwnRun();
		QualityLlmPassTest.fiveToolCallsInOneTurnAllExecutedOneTurnCounted();
		QualityLlmPassTest.budgetExceededStillWritesAlreadyAcceptedProposals();
		QualityLlmPassTest.patternAndCheckDigitRefusedOnNonStringAttribute();
		QualityLlmPassTest.checkDigitLegacyNameIsRefusedForProposal();
		QualityLlmPassTest.modelConstraintNeedsZeroFailingAndNoSampling();
		QualityLlmPassTest.noteListFormRecordsSeveralColumnsInOneCall();
		QualityLlmPassTest.noteListFormToleratesOneBadEntry();
		QualityLlmPassTest.noteEmptyCallIsRefused();
		QualityLlmPassTest.existingPairOneProposedOneRejectedReadsAsAlreadyExists();
		QualityLlmPassTest.existingPairOrderDoesNotMatter();
		QualityLlmPassTest.onlyRejectedTwinTellsModelItWasRejected();
		QualityLlmPassTest.rejectedTwinNeverBlocksADifferentArgument();
		QualityLlmPassTest.twentyTwoColumnPromptSizeIsMeasuredAndReported();
		QualityLlmPassTest.caseChangeIsRefusedAheadOfACaseReadingPipeline();
		QualityLlmPassTest.stepThatEmptiesARegexPipelineIsRefusedWithModeAwareExamples();
		QualityLlmPassTest.pipelineIsShownAndOrderRuleIsStatedInThePrompts();
		QualityLlmPassTest.aRunNarrowedToOneRuleAsksOnlyForItsCondition();
		System.out.println("QualityLlmPassTest OK");
	}

	// -------------------------------------------------------------------------------------------------------- prompt

	private static void systemPromptIsDomainNeutral()
	{
		final String system = QualityLlmPass.systemPrompt();
		QualityLlmPassTest.check("mentions two unrelated-domain examples: a product GTIN and a site postal code",
			system.contains("GTIN") && system.toLowerCase(Locale.ROOT).contains("postal"));
		QualityLlmPassTest.check("never mentions Company/party-data vocabulary",
			!system.contains("Company") && !system.contains("SIREN") && !system.contains("LEI") && !system.toLowerCase(Locale.ROOT).contains("legal form"));
		QualityLlmPassTest.check("names the four tools", system.contains("measure") && system.contains("propose_mapping_step") && system.contains("propose_quality_rule")
			&& system.contains("note"));
		QualityLlmPassTest.check("says never REJECT", system.contains("MODEL_CONSTRAINT") && system.toLowerCase(Locale.ROOT).contains("never reject"));
		// coordinator, 2026-09-21, round 3: a real DONE run answered entirely in prose and recorded nothing
		QualityLlmPassTest.check("says a plain text answer records nothing and every outcome must be a tool call",
			system.toLowerCase(Locale.ROOT).contains("records nothing") && system.toLowerCase(Locale.ROOT).contains("must go through a tool call"));
		QualityLlmPassTest.check("never invites a separate prose summary after the one note call",
			system.toLowerCase(Locale.ROOT).contains("do not add a separate prose summary"));
	}

	private static void sentinelNeverLeaksInShapesOnlyMode()
	{
		final QualityLlmPass.ProjectInfo project = QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20);
		final String prompt = QualityLlmPass.userPrompt(project);
		QualityLlmPassTest.check("mentions the columns and reference lists", prompt.contains("Product.gtin") && prompt.contains("Site.postalCode") && prompt.contains("COUNTRY"));
		QualityLlmPassTest.check("the sentinel real value never appears in the prompt", !prompt.contains(QualityLlmPassTest.SENTINEL));
		QualityLlmPassTest.check("no top values section in SHAPES_ONLY mode", !prompt.contains("Top values"));

		// the measure tool must not leak it either
		final QualityLlmPass.Session session = QualityLlmPassTest.session(project);
		final LocalToolResultCheck r = QualityLlmPassTest.callMeasure(session, "Product", "name", "PATTERN", "^[A-Z ]+$");
		QualityLlmPassTest.check("measure result does not contain the sentinel value", !r.text.contains(QualityLlmPassTest.SENTINEL));
	}

	private static void sampleValuesModeShowsRealTopValuesInThePrompt()
	{
		final QualityLlmPass.ProjectInfo project = QualityLlmPassTest.project(QualityLlmPass.Mode.SAMPLE_VALUES, 20);
		final String prompt = QualityLlmPass.userPrompt(project);
		QualityLlmPassTest.check("SAMPLE_VALUES mode shows top values", prompt.contains("Top values"));
		QualityLlmPassTest.check("the project's own top values (real, not shaped) appear", prompt.contains("4006381333931"));
	}

	// -------------------------------------------------------------------------------------------------------- measure

	private static void measureUnknownColumnIsRefused()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck r = QualityLlmPassTest.callMeasure(session, "NoSuchEntity", "x", "PATTERN", "^[0-9]+$");
		QualityLlmPassTest.check("unknown column refused", r.isError && r.text.contains("unknown column"));
	}

	private static void measurePatternCountsCorrectly()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck r = QualityLlmPassTest.callMeasure(session, "Site", "postalCode", "PATTERN", "[0-9]{5}");
		QualityLlmPassTest.check("counts conforming/non-conforming correctly: " + r.text, r.text.contains("of") && !r.isError);
	}

	private static void measureReferenceUnknownKindIsRefused()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck r = QualityLlmPassTest.callMeasure(session, "Site", "country", "REFERENCE", "NOT_A_REAL_REFERENCE");
		QualityLlmPassTest.check("unknown reference kind refused, known kinds listed", r.isError && r.text.contains("unknown reference list") && r.text.contains("COUNTRY"));
	}

	private static void measureCheckDigitUnknownAlgorithmIsRefused()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck r = QualityLlmPassTest.callMeasure(session, "Product", "gtin", "CHECK_DIGIT", "NOT_AN_ALGORITHM");
		QualityLlmPassTest.check("unknown algorithm refused", r.isError && r.text.contains("unknown check-digit algorithm"));
	}

	// -------------------------------------------------------------------------------------------------- mapping step

	private static void mappingStepNoOpIsRefused()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		// UPPER on a column already all-upper (postalCode digits - UPPER never changes digits)
		final LocalToolResultCheck r = QualityLlmPassTest.callStep(session, "Site", "postalCode", "UPPER", null, "would not change anything", 50);
		QualityLlmPassTest.check("a no-op step is refused", r.isError && r.text.toLowerCase(Locale.ROOT).contains("would not change"));
		QualityLlmPassTest.check("nothing recorded", session.proposedSteps().isEmpty());
	}

	private static void mappingStepTrimSucceedsOnDirtyData()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck r = QualityLlmPassTest.callStep(session, "Product", "name", "TRIM", null, "leading/trailing spaces observed", 90);
		QualityLlmPassTest.check("TRIM recorded", !r.isError && r.text.contains("TRIM"));
		QualityLlmPassTest.check("exactly one step recorded", session.proposedSteps().size() == 1);
		QualityLlmPassTest.check("measured evidence carried on the proposal", session.proposedSteps().get(0).valuesChanged > 0);
	}

	private static void mappingStepTranscodeReferenceUnknownReferenceIsRefused()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck r = QualityLlmPassTest.callStep(session, "Site", "country", "TRANSCODE_REFERENCE", "{\"reference\":\"NOPE\"}", "x", 80);
		QualityLlmPassTest.check("unknown reference in TRANSCODE_REFERENCE argument refused", r.isError && r.text.contains("Known reference lists"));
	}

	private static void mappingStepCountryToIso2BelowThresholdIsRefused()
	{
		// a country column whose lookup table resolves almost nothing (below the 50% threshold)
		final QualityLlmPass.ProjectInfo project = QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20);
		final QualityLlmPass.Session session = new QualityLlmPass.Session(project.columns, QualityLlmPassTest.evidence(), QualityLlmPassTest.sparseCountryLookups(),
			project.referenceBindings, project.mode, project.sampleSize);
		final LocalToolResultCheck r = QualityLlmPassTest.callStep(session, "Site", "country", "COUNTRY_TO_ISO2", "ISO2", "barely resolves", 60);
		QualityLlmPassTest.check("below-threshold lookup step refused: " + r.text, r.isError && r.text.contains("below"));
	}

	private static void mappingStepCountryToIso2AboveThresholdSucceeds()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck r = QualityLlmPassTest.callStep(session, "Site", "country", "COUNTRY_TO_ISO2", "ISO2", "resolves well against the country reference", 85);
		QualityLlmPassTest.check("above-threshold lookup step recorded: " + r.text, !r.isError && r.text.contains("COUNTRY_TO_ISO2"));
	}

	private static void mappingStepAlreadyExistingKindIsInformational()
	{
		final List<QualityLlmPass.ColumnInfo> columns = new ArrayList<QualityLlmPass.ColumnInfo>(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20).columns);
		// rebuild "Product.name" with an existing TRIM step already present
		for (int i = 0; i < columns.size(); i++)
		{
			if ("name".equals(columns.get(i).attribute))
			{
				final QualityLlmPass.ColumnInfo original = columns.get(i);
				columns.set(i, new QualityLlmPass.ColumnInfo(original.entity, original.attribute, original.sourceColumn, original.dataType, original.maxLength,
					original.rows, original.fillRatePercent, original.distinctCount, original.shapes, original.topValues,
					Arrays.asList(new QualityLlmPass.ExistingProposal("TRIM", "ACCEPTED")), original.existingRules));
			}
		}
		final QualityLlmPass.Session session = new QualityLlmPass.Session(columns, QualityLlmPassTest.evidence(), QualityLlmPassTest.countryLookups(),
			Collections.<QualityLlmPass.ReferenceBindingInfo>emptyList(), QualityLlmPass.Mode.SHAPES_ONLY, 20);
		final LocalToolResultCheck r = QualityLlmPassTest.callStep(session, "Product", "name", "TRIM", null, "already there", 80);
		QualityLlmPassTest.check("already-existing kind is informational, not an error", !r.isError && r.text.contains("already exists") && r.text.contains("ACCEPTED"));
		QualityLlmPassTest.check("nothing new recorded", session.proposedSteps().isEmpty());
	}

	// -------------------------------------------------------------------------------------------------- quality rule

	private static void ruleRejectSeverityIsRefused()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck r = QualityLlmPassTest.callRule(session, "Product", "gtin", "CHECK_DIGIT", "GS1_MOD10", "REJECT", "x", 90);
		QualityLlmPassTest.check("REJECT severity refused: " + r.text, r.isError && r.text.toLowerCase(Locale.ROOT).contains("never reject"));
	}

	private static void rulePatternInvalidRegexIsRefused()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck r = QualityLlmPassTest.callRule(session, "Site", "postalCode", "PATTERN", "[", "WARN", "x", 80);
		QualityLlmPassTest.check("invalid regex refused with the parser's own message", r.isError && r.text.contains("not a valid regular expression"));
	}

	/** A catastrophic pattern proposed by the model is refused with the reason, by the measure tool and by the rule tool alike - never compiled and run on the values. */
	private static void catastrophicPatternIsRefusedBeforeItRuns()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final long start = System.nanoTime();
		final LocalToolResultCheck measured = QualityLlmPassTest.callMeasure(session, "Site", "postalCode", "PATTERN", "(a+)+$");
		QualityLlmPassTest.check("measure refuses a repeated group holding a repetition: " + measured.text, measured.isError && measured.text.contains("exponential"));
		final LocalToolResultCheck rule = QualityLlmPassTest.callRule(session, "Site", "postalCode", "PATTERN", "(a|aa)+", "WARN", "x", 80);
		QualityLlmPassTest.check("the rule tool refuses it too: " + rule.text, rule.isError && rule.text.contains("exponential"));
		final LocalToolResultCheck email = QualityLlmPassTest.callMeasure(session, "Site", "postalCode", "PATTERN", "^[\\w.+-]+@([\\w-]+\\.)+[\\w-]{2,}$");
		QualityLlmPassTest.check("a full e-mail pattern (repeated group ending on a mandatory dot) is measured, not refused: " + email.text, !email.isError);
		final StringBuilder tooLong = new StringBuilder();
		for (int i = 0; i < RegexGuard.MAX_PATTERN_LENGTH + 1; i++)
		{
			tooLong.append('9');
		}
		final LocalToolResultCheck longPattern = QualityLlmPassTest.callMeasure(session, "Site", "postalCode", "PATTERN", tooLong.toString());
		QualityLlmPassTest.check("a pattern over the length cap is refused: " + longPattern.text, longPattern.isError && longPattern.text.contains("characters long"));
		QualityLlmPassTest.check("and none of it took time", (System.nanoTime() - start) / 1000000L < 1000L);
	}

	private static void rulePatternTooManyFailuresIsRefused()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		// postalCode has 2 failing values of 12 (see fixture) - a pattern only 5-digit codes match at all costs would still pass;
		// force a failure-heavy pattern instead (letters only) to exercise the threshold
		final LocalToolResultCheck r = QualityLlmPassTest.callRule(session, "Site", "postalCode", "PATTERN", "^[A-Z]+$", "WARN", "x", 80);
		QualityLlmPassTest.check("a pattern that fails almost everything is refused: " + r.text, r.isError && r.text.contains("fail this pattern"));
	}

	private static void rulePatternAcceptableFailuresSucceeds()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck r = QualityLlmPassTest.callRule(session, "Site", "postalCode", "PATTERN", "[0-9]{5}", "WARN", "matches a 5-digit postal code", 85);
		QualityLlmPassTest.check("PATTERN recorded: " + r.text, !r.isError && r.text.contains("PATTERN"));
		QualityLlmPassTest.check("one rule recorded", session.proposedRules().size() == 1);
	}

	/** Real, published GTIN values (see {@code CheckDigitsTest}): 10 valid EAN-13s, 2 with a flipped check digit - a domain-neutral, non-party-data check-digit scenario. */
	private static void ruleCheckDigitOnRealGtinValues()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck r = QualityLlmPassTest.callRule(session, "Product", "gtin", "CHECK_DIGIT", "GS1_MOD10", "WARN", "GS1 check digit on a GTIN column",
			85);
		QualityLlmPassTest.check("CHECK_DIGIT (GS1_MOD10) recorded: " + r.text, !r.isError && r.text.contains("GS1_MOD10"));
		QualityLlmPassTest.check("exactly the 2 synthetic bad rows counted failing", session.proposedRules().get(0).valuesFailing == 2);
	}

	private static void ruleReferenceModelConstraintNeedsHigherThresholdThanWarn()
	{
		// WARN: succeeds at the project's own resolution level (all countries resolve in the base fixture)
		final QualityLlmPass.Session warnSession = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck warnResult = QualityLlmPassTest.callRule(warnSession, "Site", "country", "REFERENCE", "COUNTRY", "WARN", "resolves well", 80);
		QualityLlmPassTest.check("WARN reference rule recorded: " + warnResult.text, !warnResult.isError);

		// MODEL_CONSTRAINT at a sparse lookup: refused (below the 90% bar) even though WARN would have passed at 50%
		final QualityLlmPass.ProjectInfo project = QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20);
		final QualityLlmPass.Session sparseSession = new QualityLlmPass.Session(project.columns, QualityLlmPassTest.evidence(),
			QualityLlmPassTest.sparseCountryLookups(), project.referenceBindings, project.mode, project.sampleSize);
		final LocalToolResultCheck constraintResult = QualityLlmPassTest.callRule(sparseSession, "Site", "country", "REFERENCE", "COUNTRY", "MODEL_CONSTRAINT",
			"x", 80);
		QualityLlmPassTest.check("MODEL_CONSTRAINT refused on sparse resolution: " + constraintResult.text,
			constraintResult.isError && constraintResult.text.contains("propose WARN instead"));
	}

	private static void ruleUniqueFindsDuplicates()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck r = QualityLlmPassTest.callRule(session, "Product", "gtin", "UNIQUE", null, "WARN", "some GTINs repeat", 70);
		QualityLlmPassTest.check("UNIQUE recorded: " + r.text, !r.isError && r.text.contains("UNIQUE"));
	}

	/**
	 * The coordinator's own required fixture (2026-09-21): "a fixture where the capped list and the exact
	 * aggregates disagree (sampled column), proving the gate/evidence follows the aggregates". {@code
	 * Product.batchCode}'s sample (3 distinct values, 6 rows, every one repeated) would - under the OLD,
	 * sample-only logic - have reported "3 of 3 distinct values duplicated" over only 6 seen rows; the fixed
	 * logic must report the EXACT figures from {@code ColumnEvidence.exactFilledRows}/{@code
	 * exactDistinctCount} (1000 seen, only 2 actually failing) instead, and every sample-based evidence
	 * sentence (TRIM's own "N of M" - a legitimately sample-based kind, kept as a sample by design - see the
	 * class Javadoc) must say plainly that it was measured on a sample, with its confidence capped.
	 */
	private static void sampledColumnGateFollowsExactAggregates()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));

		// UNIQUE: the gate/count must be the EXACT 1000/2, never the sample's own 6/6
		final LocalToolResultCheck unique = QualityLlmPassTest.callRule(session, "Product", "batchCode", "UNIQUE", null, "WARN", "measured exactly", 80);
		QualityLlmPassTest.check("UNIQUE recorded from the exact aggregates: " + unique.text, !unique.isError && unique.text.contains("2 of 1000"));
		final QualityLlmPass.ProposedRule uniqueRule = session.proposedRules().get(session.proposedRules().size() - 1);
		QualityLlmPassTest.check("valuesSeen is the EXACT filled count (1000), not the sample sum (6): " + uniqueRule.valuesSeen, uniqueRule.valuesSeen == 1000L);
		QualityLlmPassTest.check("valuesFailing is the EXACT duplicate count (2), not the sample's inflated 6: " + uniqueRule.valuesFailing, uniqueRule.valuesFailing == 2L);
		QualityLlmPassTest.check("UNIQUE's own confidence is NOT capped (the count is exact, only the illustration is sampled): " + uniqueRule.confidence,
			uniqueRule.confidence == 80);

		// measure (PATTERN) on the same sampled column must say so in its result text
		final LocalToolResultCheck measured = QualityLlmPassTest.callMeasure(session, "Product", "batchCode", "PATTERN", "^ ?[A-Z][0-9]{3}$");
		QualityLlmPassTest.check("measure result names the sample size and the exact total: " + measured.text,
			measured.text.contains("3 most frequent distinct value(s) out of 998") && measured.text.contains("(sample)"));

		// TRIM (a legitimately sample-based MappingStep kind) must be flagged sampled and confidence-capped
		final LocalToolResultCheck trim = QualityLlmPassTest.callStep(session, "Product", "batchCode", "TRIM", null, "one value has a leading space", 100);
		QualityLlmPassTest.check("TRIM recorded, sampled suffix present: " + trim.text, !trim.isError && trim.text.contains("(sample)"));
		final QualityLlmPass.ProposedStep trimStep = session.proposedSteps().get(session.proposedSteps().size() - 1);
		QualityLlmPassTest.check("TRIM's confidence is capped at 80 although 100 was requested: " + trimStep.confidence, trimStep.confidence == 80);
		QualityLlmPassTest.check("TRIM's stored rationale also carries the sampling caveat: " + trimStep.rationale, trimStep.rationale.contains("(sample)"));
	}

	/** Coordinator review, 2026-09-21: an unbounded {@code measure} would be an oracle over real values in {@link QualityLlmPass.Mode#SHAPES_ONLY} - every call counts against the cap, valid or refused. */
	private static void measureCallBudgetIsBounded()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		LocalToolResultCheck last = null;
		for (int i = 0; i < 11; i++)
		{
			last = QualityLlmPassTest.callMeasure(session, "Site", "postalCode", "PATTERN", "[0-9]{" + (i + 1) + "}");
		}
		QualityLlmPassTest.check("the 11th call on the same column is refused for exceeding the per-column budget: " + last.text,
			last.isError && last.text.contains("already been called") && last.text.contains("10"));
	}

	private static void ruleMandatoryNeedsFillRateProfile()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		// postalCode's fixture fill rate is 100.0 (see project())
		final LocalToolResultCheck ok = QualityLlmPassTest.callRule(session, "Site", "postalCode", "MANDATORY", null, "WARN", "always filled", 90);
		QualityLlmPassTest.check("MANDATORY recorded at 100% fill: " + ok.text, !ok.isError);

		// gtin's fixture fill rate is 80.0 (see project()) - below the 99.5% bar
		final LocalToolResultCheck refused = QualityLlmPassTest.callRule(session, "Product", "gtin", "MANDATORY", null, "WARN", "mostly filled", 90);
		QualityLlmPassTest.check("MANDATORY refused below the fill-rate bar: " + refused.text, refused.isError && refused.text.contains("fill rate"));
	}

	// -------------------------------------------------------------------------------------------------- circuit breaker

	private static void earlyStopOnRepeatedRefusal()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		LocalToolResultCheck r1 = QualityLlmPassTest.callStep(session, "Site", "postalCode", "UPPER", null, "x", 50);
		QualityLlmPassTest.check("1st no-op refusal: not stopped yet", r1.isError && !session.stoppedEarly());
		LocalToolResultCheck r2 = QualityLlmPassTest.callStep(session, "Site", "postalCode", "UPPER", null, "x", 50);
		QualityLlmPassTest.check("2nd: not stopped yet", r2.isError && !session.stoppedEarly());
		LocalToolResultCheck r3 = QualityLlmPassTest.callStep(session, "Site", "postalCode", "UPPER", null, "x", 50);
		QualityLlmPassTest.check("3rd: run stopped", r3.isError && session.stoppedEarly() && session.stopReason() != null);

		final LocalToolResultCheck r4 = QualityLlmPassTest.callStep(session, "Product", "name", "TRIM", null, "valid this time", 90);
		QualityLlmPassTest.check("a subsequent call, even a valid one, is refused because the run is over", r4.isError && r4.text.toLowerCase(Locale.ROOT).contains("run is over"));
		QualityLlmPassTest.check("nothing was recorded", session.proposedSteps().isEmpty());
	}

	// ----------------------------------------------------------------------------------------------- coverage (req. 2)

	/**
	 * Coordinator review, 2026-09-21: coverage must be checkable within one run. Every column of the fixture
	 * project starts uncovered; a proposal covers its own column, and a {@code note} naming a real column
	 * covers it too - purely informational, {@link QualityLlmPass.Session#columnsWithoutProposalOrNote()}
	 * never blocks or forces anything.
	 */
	private static void columnsWithoutProposalOrNoteTracksCoverage()
	{
		final QualityLlmPass.ProjectInfo project = QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20);
		final QualityLlmPass.Session session = QualityLlmPassTest.session(project);

		final List<String> initialGap = session.columnsWithoutProposalOrNote();
		QualityLlmPassTest.check("every column of the fixture starts uncovered: " + initialGap,
			initialGap.size() == 5 && initialGap.contains("gtin") && initialGap.contains("name") && initialGap.contains("postalCode")
				&& initialGap.contains("country") && initialGap.contains("batchCode"));

		QualityLlmPassTest.callStep(session, "Product", "name", "TRIM", null, "leading/trailing spaces observed", 90);
		QualityLlmPassTest.check("a proposal covers its own column", !session.columnsWithoutProposalOrNote().contains("name"));

		final com.fasterxml.jackson.databind.node.ObjectNode noteArgs = QualityLlmPassTest.MAPPER.createObjectNode();
		noteArgs.put("entity", "Site");
		noteArgs.put("attribute", "postalCode");
		noteArgs.put("text", "already looks clean, nothing to propose.");
		final com.on.ebx.agent.LocalTool.Result noteResult = session.note(noteArgs);
		QualityLlmPassTest.check("note tied to a real column succeeds", !noteResult.isError);
		QualityLlmPassTest.check("a note naming a real column covers it too", !session.columnsWithoutProposalOrNote().contains("postalCode"));

		final List<String> remainingGap = session.columnsWithoutProposalOrNote();
		QualityLlmPassTest.check("exactly the 3 still-untouched columns remain: " + remainingGap,
			remainingGap.size() == 3 && remainingGap.contains("gtin") && remainingGap.contains("country") && remainingGap.contains("batchCode"));
	}

	/** A column that already has an existing (pre-run) step or rule is exempt from the gap list even though this run never touches it - "never touch a decided row" extends to not nagging about it either. */
	private static void columnsWithoutProposalOrNoteExemptsColumnsWithExistingProposals()
	{
		final List<QualityLlmPass.ColumnInfo> columns = new ArrayList<QualityLlmPass.ColumnInfo>(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20).columns);
		for (int i = 0; i < columns.size(); i++)
		{
			if ("name".equals(columns.get(i).attribute))
			{
				final QualityLlmPass.ColumnInfo original = columns.get(i);
				columns.set(i, new QualityLlmPass.ColumnInfo(original.entity, original.attribute, original.sourceColumn, original.dataType, original.maxLength,
					original.rows, original.fillRatePercent, original.distinctCount, original.shapes, original.topValues,
					Arrays.asList(new QualityLlmPass.ExistingProposal("TRIM", "ACCEPTED")), original.existingRules));
			}
		}
		final QualityLlmPass.Session session = new QualityLlmPass.Session(columns, QualityLlmPassTest.evidence(), QualityLlmPassTest.countryLookups(),
			Collections.<QualityLlmPass.ReferenceBindingInfo>emptyList(), QualityLlmPass.Mode.SHAPES_ONLY, 20);
		final List<String> gap = session.columnsWithoutProposalOrNote();
		QualityLlmPassTest.check("'name' is exempt: it already has an existing (pre-run) step: " + gap, !gap.contains("name"));
		QualityLlmPassTest.check("the untouched columns without any existing proposal still show up: " + gap,
			gap.contains("gtin") && gap.contains("postalCode") && gap.contains("country") && gap.contains("batchCode"));
	}

	/** A {@code note} naming an unknown entity/attribute is still recorded as free text (never rejected), but covers nothing - only a real column name moves the needle. */
	private static void noteWithUnknownColumnIsRecordedButCoversNothing()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final int gapBefore = session.columnsWithoutProposalOrNote().size();
		final com.fasterxml.jackson.databind.node.ObjectNode strayArgs = QualityLlmPassTest.MAPPER.createObjectNode();
		strayArgs.put("entity", "NoSuchEntity");
		strayArgs.put("attribute", "x");
		strayArgs.put("text", "not a real column");
		final com.on.ebx.agent.LocalTool.Result r = session.note(strayArgs);
		QualityLlmPassTest.check("a note is never rejected for naming an unknown column", !r.isError);
		QualityLlmPassTest.check("nothing is covered by a note tied to a column that does not exist", session.columnsWithoutProposalOrNote().size() == gapBefore);
	}

	// -------------------------------------------------------------------------------------------- rule evidence (req. 3/4)

	/**
	 * Coordinator review, 2026-09-21 (real run): a PATTERN rule was accepted with 15 of 54 (28%) values
	 * failing - a single-format regex wrongly accepted on a genuinely multi-format column - and the same run
	 * had stored the identical failing SHAPE twice in {@code examples}. This fixture reproduces both: three
	 * distinct failing values ("AB1"/"CD2"/"EF3") all share the shape {@code AA9}, and the failure rate (3 of
	 * 11, ~27%) sits between the 10% multi-format-warning line and the 30% refusal line.
	 */
	private static void rulePatternDedupsIdenticalShapesAndCarriesMultiFormatCaveat()
	{
		final QualityLlmPass.ColumnInfo code = new QualityLlmPass.ColumnInfo("Widget", "code", "CODE", "STRING", Integer.valueOf(10), Long.valueOf(11),
			Double.valueOf(100.0), Long.valueOf(4), Arrays.asList(new QualityLlmPass.ShapeCount("999999", 8L)), Collections.<String>emptyList(),
			Collections.<QualityLlmPass.ExistingProposal>emptyList(), Collections.<QualityLlmPass.ExistingProposal>emptyList());
		final Map<String, QualityLlmPass.ColumnEvidence> evidence = new LinkedHashMap<String, QualityLlmPass.ColumnEvidence>();
		evidence.put("Widget.code", new QualityLlmPass.ColumnEvidence(
			Arrays.asList(new Detectors.ValueCount("100001", 8), new Detectors.ValueCount("AB1", 1), new Detectors.ValueCount("CD2", 1),
				new Detectors.ValueCount("EF3", 1)),
			Double.valueOf(100.0), 11L, 4L));
		final QualityLlmPass.Session session = new QualityLlmPass.Session(Arrays.asList(code), evidence, QualityLlmPassTest.countryLookups(),
			Collections.<QualityLlmPass.ReferenceBindingInfo>emptyList(), QualityLlmPass.Mode.SHAPES_ONLY, 20);

		final LocalToolResultCheck r = QualityLlmPassTest.callRule(session, "Widget", "code", "PATTERN", "^[0-9]{6}$", "WARN",
			"looks like a fixed 6-digit code", 95);
		QualityLlmPassTest.check("27% failure is under the 30% refusal bar, so PATTERN is still recorded: " + r.text, !r.isError);
		QualityLlmPassTest.check("the acceptance message itself warns above 10% failure: " + r.text, r.text.contains("multi-format column"));

		QualityLlmPassTest.check("exactly one rule recorded", session.proposedRules().size() == 1);
		final QualityLlmPass.ProposedRule rule = session.proposedRules().get(0);
		QualityLlmPassTest.check("all 3 failing values collapse to ONE deduplicated shape example, not three: " + rule.examples,
			rule.examples.size() == 1 && "AA9".equals(rule.examples.get(0)));
		QualityLlmPassTest.check("confidence is capped at the conforming share (72%), never the 95% requested: " + rule.confidence, rule.confidence == 72);
	}

	/** The refusal message (failure over the 30% bar) must ALSO carry the multi-format caveat, not only the acceptance message - both paths went through {@code multiFormatCaveat} independently. */
	private static void rulePatternRefusalMessageCarriesMultiFormatCaveatAboveTenPercent()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		// postalCode: "75001"x5, "94100"x5, "N/A"x2 (see evidence()) - a pattern matching only digits fails on
		// "N/A" alone (2 of 12, 16.7%: over 10%, under 30%) so use one matching letters only instead, which
		// fails on the two 5-digit groups (10 of 12, 83%: comfortably over both the 10% and 30% bars)
		final LocalToolResultCheck r = QualityLlmPassTest.callRule(session, "Site", "postalCode", "PATTERN", "^[A-Z]+$", "WARN", "letters only", 90);
		QualityLlmPassTest.check("refused for exceeding the 30% failure bar: " + r.text, r.isError && r.text.contains("fail this pattern"));
		QualityLlmPassTest.check("the refusal message ALSO carries the multi-format caveat above 10%: " + r.text, r.text.contains("multi-format column"));
	}

	/**
	 * Coordinator review, 2026-09-21: a rule's {@code examples} is a non-conforming SHAPE (or real value in
	 * SAMPLE_VALUES mode), never a "before -&gt; after" pair - that concept only applies to a {@code
	 * MappingStep}. Checked directly on PATTERN, REFERENCE and CHECK_DIGIT: none of their examples contain an
	 * arrow.
	 */
	private static void ruleExamplesAreShapesNeverBeforeAfterPairs()
	{
		final QualityLlmPass.Session patternSession = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		QualityLlmPassTest.callRule(patternSession, "Site", "postalCode", "PATTERN", "[0-9]{5}", "WARN", "5-digit postal code", 85);
		QualityLlmPassTest.check("PATTERN's examples never contain a before/after arrow",
			!QualityLlmPassTest.anyExampleContainsArrow(patternSession.proposedRules().get(patternSession.proposedRules().size() - 1).examples));

		final QualityLlmPass.Session referenceSession = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		QualityLlmPassTest.callRule(referenceSession, "Site", "country", "REFERENCE", "COUNTRY", "WARN", "resolves well", 80);
		if (!referenceSession.proposedRules().isEmpty())
		{
			QualityLlmPassTest.check("REFERENCE's examples never contain a before/after arrow",
				!QualityLlmPassTest.anyExampleContainsArrow(referenceSession.proposedRules().get(referenceSession.proposedRules().size() - 1).examples));
		}

		final QualityLlmPass.Session checkDigitSession = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		QualityLlmPassTest.callRule(checkDigitSession, "Product", "gtin", "CHECK_DIGIT", "GS1_MOD10", "WARN", "GS1 check digit", 85);
		QualityLlmPassTest.check("CHECK_DIGIT's examples never contain a before/after arrow",
			!QualityLlmPassTest.anyExampleContainsArrow(checkDigitSession.proposedRules().get(checkDigitSession.proposedRules().size() - 1).examples));
	}

	private static boolean anyExampleContainsArrow(final List<String> pExamples)
	{
		for (final String example : pExamples)
		{
			if (example.contains("->"))
			{
				return true;
			}
		}
		return false;
	}

	// -------------------------------------------------------------------------------------------- per-entity runs (req. 1)

	/**
	 * Coordinator review, 2026-09-21: the real orchestration loop lives in {@code ebx.QualityLlmSuggester},
	 * which touches EBX and cannot run in this harness. What CAN be proven here, at the pure layer, is the
	 * guarantee the whole fix rests on: a fresh {@link QualityLlmPass.Session} plus a fresh {@code AgentLoop}
	 * per entity means one entity's own early stop/failure carries no state into the next entity's run - the
	 * factory achieves "one entity's failure does not abort the others" simply by never sharing a
	 * {@link QualityLlmPass.Session} across entities. Entity "Site" is scripted to repeat the exact same no-op
	 * three times (tripping ITS OWN circuit breaker, producing nothing); entity "Product" - a completely
	 * separate {@link QualityLlmPass.Session}/{@code AgentLoop} pair, built and run afterwards - still
	 * completes normally and produces its own proposals, unaffected.
	 */
	private static void twoEntityRunsAreIndependentSecondStillGetsOwnRun() throws Exception
	{
		final QualityLlmPass.ProjectInfo fullProject = QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20);
		final List<QualityLlmPass.ColumnInfo> siteColumns = new ArrayList<QualityLlmPass.ColumnInfo>();
		final List<QualityLlmPass.ColumnInfo> productColumns = new ArrayList<QualityLlmPass.ColumnInfo>();
		for (final QualityLlmPass.ColumnInfo column : fullProject.columns)
		{
			if ("Site".equals(column.entity))
			{
				siteColumns.add(column);
			}
			else if ("Product".equals(column.entity))
			{
				productColumns.add(column);
			}
		}
		final QualityLlmPass.ProjectInfo siteProject = new QualityLlmPass.ProjectInfo(fullProject.code, fullProject.label, fullProject.mode, fullProject.sampleSize,
			siteColumns, fullProject.referenceBindings);
		final QualityLlmPass.ProjectInfo productProject = new QualityLlmPass.ProjectInfo(fullProject.code, fullProject.label, fullProject.mode, fullProject.sampleSize,
			productColumns, fullProject.referenceBindings);

		// entity run 1 ("Site"): the scripted model repeats the exact same no-op propose_mapping_step 3 times in
		// a row - THIS session's own circuit breaker trips, and it ends with a text turn instead of ever
		// succeeding - simulating "one entity's early stop"
		final QualityLlmPass.Session siteSession = new QualityLlmPass.Session(siteColumns, QualityLlmPassTest.evidence(), QualityLlmPassTest.countryLookups(),
			siteProject.referenceBindings, siteProject.mode, siteProject.sampleSize);
		final String noOpStep = "{\"entity\":\"Site\",\"attribute\":\"postalCode\",\"kind\":\"UPPER\",\"confidence\":50,\"rationale\":\"x\"}";
		final String[] siteScript = new String[] { QualityLlmPassTest.toolUse("s1", "factory__propose_mapping_step", noOpStep),
			QualityLlmPassTest.toolUse("s2", "factory__propose_mapping_step", noOpStep), QualityLlmPassTest.toolUse("s3", "factory__propose_mapping_step", noOpStep),
			QualityLlmPassTest.text("I could not find anything valid to propose for this entity.") };
		final AgentLoop.Result siteResult = QualityLlmPassTest.runScriptedAgentLoop(siteSession, siteProject, siteScript);
		QualityLlmPassTest.check("entity Site's own run still ends normally (DONE) even though nothing was proposed",
			siteResult.status == AgentLoop.Status.DONE);
		QualityLlmPassTest.check("entity Site's session tripped its own circuit breaker", siteSession.stoppedEarly());
		QualityLlmPassTest.check("entity Site produced nothing", siteSession.proposedSteps().isEmpty() && siteSession.proposedRules().isEmpty());

		// entity run 2 ("Product"): a COMPLETELY SEPARATE Session/AgentLoop pair, built and run only after
		// entity Site's run above finished - must be entirely unaffected by it
		final QualityLlmPass.Session productSession = new QualityLlmPass.Session(productColumns, QualityLlmPassTest.evidence(), QualityLlmPassTest.countryLookups(),
			productProject.referenceBindings, productProject.mode, productProject.sampleSize);
		final String[] productScript = new String[] {
			QualityLlmPassTest.toolUse("p1", "factory__propose_mapping_step",
				"{\"entity\":\"Product\",\"attribute\":\"name\",\"kind\":\"TRIM\",\"confidence\":90,\"rationale\":\"leading/trailing spaces observed\"}"),
			QualityLlmPassTest.toolUse("p2", "factory__propose_quality_rule",
				"{\"entity\":\"Product\",\"attribute\":\"gtin\",\"kind\":\"CHECK_DIGIT\",\"argument\":\"GS1_MOD10\",\"severity\":\"WARN\",\"confidence\":85,"
					+ "\"rationale\":\"A GTIN column should pass the GS1 check digit.\"}"),
			QualityLlmPassTest.text("Proposed TRIM on Product.name and a GS1_MOD10 check digit on Product.gtin.") };
		final AgentLoop.Result productResult = QualityLlmPassTest.runScriptedAgentLoop(productSession, productProject, productScript);
		QualityLlmPassTest.check("entity Product's own run completes DONE, wholly unaffected by entity Site's early stop",
			productResult.status == AgentLoop.Status.DONE);
		QualityLlmPassTest.check("entity Product's session never tripped any circuit breaker", !productSession.stoppedEarly());
		QualityLlmPassTest.check("entity Product still got its own proposals although entity Site produced none",
			productSession.proposedSteps().size() == 1 && productSession.proposedRules().size() == 1);
	}

	/**
	 * Coordinator review round 3, 2026-09-21: a real run made one tool call per turn (mostly {@code note}),
	 * and every turn re-sends the whole conversation, so cost grew roughly with the SQUARE of the column count.
	 * The library was already built for this (both {@code AgentLoop} and {@code LlmClient} let several tool
	 * calls share one turn - see the class Javadoc for the file:line finding) - what was missing was the
	 * prompt asking for it. This scripts ONE turn with 5 independent tool calls batched together (a measure, a
	 * TRIM step, two rules, and the single list-form note) followed by one final text turn, and checks all 5
	 * were executed and only 2 turns were spent.
	 */
	private static void fiveToolCallsInOneTurnAllExecutedOneTurnCounted() throws Exception
	{
		final QualityLlmPass.ProjectInfo project = QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20);
		final QualityLlmPass.Session session = QualityLlmPassTest.session(project);
		final String batch = QualityLlmPassTest.toolUseBatch(
			QualityLlmPassTest.toolUse("b1", "factory__measure", "{\"entity\":\"Site\",\"attribute\":\"postalCode\",\"kind\":\"PATTERN\",\"argument\":\"[0-9]{5}\"}"),
			QualityLlmPassTest.toolUse("b2", "factory__propose_mapping_step",
				"{\"entity\":\"Product\",\"attribute\":\"name\",\"kind\":\"TRIM\",\"confidence\":90,\"rationale\":\"leading/trailing spaces observed\"}"),
			QualityLlmPassTest.toolUse("b3", "factory__propose_quality_rule",
				"{\"entity\":\"Product\",\"attribute\":\"gtin\",\"kind\":\"CHECK_DIGIT\",\"argument\":\"GS1_MOD10\",\"severity\":\"WARN\",\"confidence\":85,"
					+ "\"rationale\":\"A GTIN column should pass the GS1 check digit.\"}"),
			QualityLlmPassTest.toolUse("b4", "factory__propose_quality_rule",
				"{\"entity\":\"Site\",\"attribute\":\"postalCode\",\"kind\":\"PATTERN\",\"argument\":\"[0-9]{5}\",\"severity\":\"WARN\",\"confidence\":85,"
					+ "\"rationale\":\"5-digit postal code.\"}"),
			QualityLlmPassTest.toolUse("b5", "factory__note",
				"{\"notes\":[{\"attribute\":\"country\",\"reason\":\"resolves well already, nothing to propose\"}]}"));
		final String[] script = new String[] { batch, QualityLlmPassTest.text("Proposed TRIM on Product.name, a GS1_MOD10 check digit on Product.gtin and "
			+ "a PATTERN rule on Site.postalCode; noted Site.country.") };
		final AgentLoop.Result result = QualityLlmPassTest.runScriptedAgentLoop(session, project, script);

		QualityLlmPassTest.check("the run completes DONE", result.status == AgentLoop.Status.DONE);
		QualityLlmPassTest.check("only 2 turns were spent - the batch turn plus the final text turn: " + result.steps, result.steps == 2);
		QualityLlmPassTest.check("all 5 batched calls actually ran: 1 step + 2 rules + 1 covered note",
			session.proposedSteps().size() == 1 && session.proposedRules().size() == 2 && !session.columnsWithoutProposalOrNote().contains("country"));
	}

	/**
	 * Coordinator review round 3, 2026-09-21, item 3: "when the step budget is exceeded, the proposals already
	 * accepted in that run are still written". {@code AgentLoop.run} never rolls back a tool call's own effect
	 * on {@code Session} just because a LATER turn never happens - two accepted {@code propose_*} calls, one
	 * per turn, with a 2-turn budget: the run ends {@code BUDGET} (a 3rd turn would have been needed to reach a
	 * plain text end), yet both proposals are already in {@code session.proposedSteps()}/{@code proposedRules()}
	 * - exactly what {@code ebx.QualityLlmSuggester.write} then persists regardless of the run's final status.
	 */
	private static void budgetExceededStillWritesAlreadyAcceptedProposals() throws Exception
	{
		final QualityLlmPass.ProjectInfo project = QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20);
		final QualityLlmPass.Session session = QualityLlmPassTest.session(project);
		final String[] script = new String[] {
			QualityLlmPassTest.toolUse("a1", "factory__propose_mapping_step",
				"{\"entity\":\"Product\",\"attribute\":\"name\",\"kind\":\"TRIM\",\"confidence\":90,\"rationale\":\"leading/trailing spaces observed\"}"),
			QualityLlmPassTest.toolUse("a2", "factory__propose_quality_rule",
				"{\"entity\":\"Product\",\"attribute\":\"gtin\",\"kind\":\"CHECK_DIGIT\",\"argument\":\"GS1_MOD10\",\"severity\":\"WARN\",\"confidence\":85,"
					+ "\"rationale\":\"A GTIN column should pass the GS1 check digit.\"}") };
		// budget of exactly 2 turns: both scripted turns are tool_use (never end_turn), so the loop is never
		// offered a chance to finish DONE - it must exhaust the budget after processing turn 2's own call
		final AgentLoop.Result result = QualityLlmPassTest.runScriptedAgentLoop(session, project, script, new AgentLoop.Budget(2, 1000000, 60000L));

		QualityLlmPassTest.check("the run ends BUDGET, not DONE: " + result.status, result.status == AgentLoop.Status.BUDGET);
		QualityLlmPassTest.check("both proposals accepted before the budget hit are still recorded: " + session.proposedSteps().size() + "/"
			+ session.proposedRules().size(), session.proposedSteps().size() == 1 && session.proposedRules().size() == 1);
	}

	// -------------------------------------------------------------------------------------- structural guards (round 3)

	/** Coordinator review round 3, 2026-09-21, item C: a real run proposed PATTERN on a TIMESTAMP attribute - a regex is meaningless once the value is typed. Type-based, never name-based. */
	private static void patternAndCheckDigitRefusedOnNonStringAttribute()
	{
		final QualityLlmPass.ColumnInfo timestampColumn = new QualityLlmPass.ColumnInfo("Contact", "lastModifiedDate", "LAST_MODIFIED", "TIMESTAMP", null,
			Long.valueOf(89), Double.valueOf(100.0), Long.valueOf(80), Arrays.asList(new QualityLlmPass.ShapeCount("9999-99-99T99:99:99", 80L)),
			Collections.<String>emptyList(), Collections.<QualityLlmPass.ExistingProposal>emptyList(), Collections.<QualityLlmPass.ExistingProposal>emptyList());
		final Map<String, QualityLlmPass.ColumnEvidence> evidence = new LinkedHashMap<String, QualityLlmPass.ColumnEvidence>();
		evidence.put("Contact.lastModifiedDate",
			new QualityLlmPass.ColumnEvidence(Arrays.asList(new Detectors.ValueCount("2026-09-14T10:00:00", 80)), Double.valueOf(100.0), 89L, 80L));
		final QualityLlmPass.Session session = new QualityLlmPass.Session(Arrays.asList(timestampColumn), evidence, QualityLlmPassTest.countryLookups(),
			Collections.<QualityLlmPass.ReferenceBindingInfo>emptyList(), QualityLlmPass.Mode.SHAPES_ONLY, 20);

		final LocalToolResultCheck measured = QualityLlmPassTest.callMeasure(session, "Contact", "lastModifiedDate", "PATTERN", "^[0-9-]+T[0-9:]+$");
		QualityLlmPassTest.check("measure refuses PATTERN on a TIMESTAMP column: " + measured.text,
			measured.isError && measured.text.contains("STRING attribute"));

		final LocalToolResultCheck proposed = QualityLlmPassTest.callRule(session, "Contact", "lastModifiedDate", "PATTERN", "^[0-9-]+T[0-9:]+$", "WARN",
			"looks like a timestamp shape", 90);
		QualityLlmPassTest.check("propose_quality_rule also refuses PATTERN on a TIMESTAMP column: " + proposed.text,
			proposed.isError && proposed.text.contains("STRING attribute"));

		final LocalToolResultCheck checkDigit = QualityLlmPassTest.callRule(session, "Contact", "lastModifiedDate", "CHECK_DIGIT", "LUHN", "WARN", "x", 80);
		QualityLlmPassTest.check("CHECK_DIGIT is refused the same way on a non-string column: " + checkDigit.text,
			checkDigit.isError && checkDigit.text.contains("STRING attribute"));
		QualityLlmPassTest.check("nothing was recorded", session.proposedRules().isEmpty());
	}

	/** Coordinator review round 3, 2026-09-21, item B: a real run proposed CHECK_DIGIT(SIREN) - a legacy, party-data-specific name that should never have been offered to a proposer, only kept for the EXECUTOR of a row decided before this lot. */
	private static void checkDigitLegacyNameIsRefusedForProposal()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final LocalToolResultCheck measured = QualityLlmPassTest.callMeasure(session, "Product", "gtin", "CHECK_DIGIT", "SIREN");
		QualityLlmPassTest.check("measure refuses the legacy name SIREN: " + measured.text,
			measured.isError && measured.text.contains("unknown check-digit algorithm") && !measured.text.contains("SIREN,"));

		final LocalToolResultCheck proposed = QualityLlmPassTest.callRule(session, "Product", "gtin", "CHECK_DIGIT", "SIREN", "WARN", "x", 80);
		QualityLlmPassTest.check("propose_quality_rule refuses it too: " + proposed.text, proposed.isError && proposed.text.contains("unknown check-digit algorithm"));
		QualityLlmPassTest.check("the generic names are still offered", proposed.text.contains("GS1_MOD10") && proposed.text.contains("LUHN"));
		QualityLlmPassTest.check("nothing was recorded", session.proposedRules().isEmpty());
	}

	/**
	 * Coordinator review round 3, 2026-09-21, item D: confirmed the code did NOT enforce this for PATTERN/
	 * CHECK_DIGIT before this fix (only REFERENCE had any MODEL_CONSTRAINT-specific bar). Now: MODEL_CONSTRAINT
	 * needs zero failing values measured over the FULL column - refused with any failure, and refused even at
	 * zero failure when the evidence was only sampled; WARN is unaffected either way.
	 */
	private static void modelConstraintNeedsZeroFailingAndNoSampling()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		// Product.gtin (not sampled - see evidence()): 2 of 12 fail the check digit - MODEL_CONSTRAINT refused
		final LocalToolResultCheck nonzeroFailing = QualityLlmPassTest.callRule(session, "Product", "gtin", "CHECK_DIGIT", "GS1_MOD10", "MODEL_CONSTRAINT",
			"x", 90);
		QualityLlmPassTest.check("MODEL_CONSTRAINT refused with nonzero failing: " + nonzeroFailing.text,
			nonzeroFailing.isError && nonzeroFailing.text.contains("zero failing"));
		QualityLlmPassTest.check("WARN at the same measurement still succeeds",
			!QualityLlmPassTest.callRule(session, "Product", "gtin", "CHECK_DIGIT", "GS1_MOD10", "WARN", "x", 90).isError);

		// Product.batchCode (SAMPLED - see evidence()/project()): even a pattern with 0 failing on the sample
		// is refused at MODEL_CONSTRAINT, because the sample never covered the full column
		final LocalToolResultCheck sampledZeroFailing = QualityLlmPassTest.callRule(session, "Product", "batchCode", "PATTERN", "^ ?[A-Z][0-9]{3}$",
			"MODEL_CONSTRAINT", "x", 90);
		QualityLlmPassTest.check("MODEL_CONSTRAINT refused on a sampled column even at zero measured failures: " + sampledZeroFailing.text,
			sampledZeroFailing.isError && sampledZeroFailing.text.contains("sampled"));

		// Site.postalCode (not sampled, 5-digit pattern matches all 12 of the fixture's values with a broader
		// regex) - 0 failing, full column: MODEL_CONSTRAINT succeeds
		final LocalToolResultCheck airtight = QualityLlmPassTest.callRule(session, "Site", "postalCode", "PATTERN", "^[0-9A-Z/]+$", "MODEL_CONSTRAINT",
			"every value is alphanumeric", 90);
		QualityLlmPassTest.check("MODEL_CONSTRAINT succeeds with zero failing over the full, unsampled column: " + airtight.text, !airtight.isError);
	}

	// ---------------------------------------------------------------------------------- note list form (round 3)

	/** Coordinator review round 3, 2026-09-21, item 1: the PREFERRED form - one call, several columns, never one call per column. */
	private static void noteListFormRecordsSeveralColumnsInOneCall()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final com.fasterxml.jackson.databind.node.ObjectNode args = QualityLlmPassTest.MAPPER.createObjectNode();
		final com.fasterxml.jackson.databind.node.ArrayNode notes = args.putArray("notes");
		notes.addObject().put("attribute", "country").put("reason", "resolves well already");
		notes.addObject().put("attribute", "postalCode").put("reason", "already clean 5-digit codes");
		final com.on.ebx.agent.LocalTool.Result r = session.note(args);
		QualityLlmPassTest.check("one call records both columns: " + r.text, !r.isError && r.text.contains("2"));
		final List<String> gap = session.columnsWithoutProposalOrNote();
		QualityLlmPassTest.check("both columns covered by the single list call: " + gap, !gap.contains("country") && !gap.contains("postalCode"));
		QualityLlmPassTest.check("the other columns are unaffected", gap.contains("gtin") && gap.contains("name") && gap.contains("batchCode"));
	}

	/** A {@code notes} entry naming an unknown attribute is still recorded (never rejected as a whole call) but covers nothing; a well-formed sibling entry in the SAME call is unaffected. */
	private static void noteListFormToleratesOneBadEntry()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final com.fasterxml.jackson.databind.node.ObjectNode args = QualityLlmPassTest.MAPPER.createObjectNode();
		final com.fasterxml.jackson.databind.node.ArrayNode notes = args.putArray("notes");
		notes.addObject().put("attribute", "noSuchAttribute").put("reason", "x");
		notes.addObject().put("attribute", "country").put("reason", "resolves well already");
		final com.on.ebx.agent.LocalTool.Result r = session.note(args);
		QualityLlmPassTest.check("the call still succeeds: " + r.text, !r.isError);
		QualityLlmPassTest.check("the real column is still covered", !session.columnsWithoutProposalOrNote().contains("country"));
	}

	/** {@code note} called with neither {@code notes} nor {@code text} is the one case actually refused. */
	private static void noteEmptyCallIsRefused()
	{
		final QualityLlmPass.Session session = QualityLlmPassTest.session(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20));
		final com.fasterxml.jackson.databind.node.ObjectNode args = QualityLlmPassTest.MAPPER.createObjectNode();
		final com.on.ebx.agent.LocalTool.Result r = session.note(args);
		QualityLlmPassTest.check("an empty note call is refused: " + r.text, r.isError);
	}

	// -------------------------------------------------------------------------------- rejected-twin identity (round 3)

	/**
	 * Coordinator review, 2026-09-21 (product owner decision on real rows 28/29, {@code Company.country}
	 * REFERENCE COUNTRY - a genuine duplicate): row 28 stayed PROPOSED, its exact twin 29 was REJECTED
	 * specifically to remove the duplicate, never to reject the rule itself. A non-rejected match must always
	 * win the lookup, in whatever order the existing rows happen to be given.
	 */
	private static void existingPairOneProposedOneRejectedReadsAsAlreadyExists()
	{
		final List<QualityLlmPass.ExistingProposal> pair = Arrays.asList(new QualityLlmPass.ExistingProposal("REFERENCE", "REJECTED", "COUNTRY"),
			new QualityLlmPass.ExistingProposal("REFERENCE", "PROPOSED", "COUNTRY"));
		final QualityLlmPass.Session session = QualityLlmPassTest.sessionWithExistingRulesOn("country", pair);
		final LocalToolResultCheck r = QualityLlmPassTest.callRule(session, "Site", "country", "REFERENCE", "COUNTRY", "WARN", "resolves well", 80);
		QualityLlmPassTest.check("reads as already-exists on the PROPOSED twin, never as rejected: " + r.text,
			!r.isError && r.text.contains("already exists") && r.text.contains("PROPOSED") && !r.text.contains("REJECTED"));
		QualityLlmPassTest.check("nothing new created", session.proposedRules().isEmpty());
	}

	/** The reverse order (PROPOSED listed first) must read identically - the match must not depend on row order. */
	private static void existingPairOrderDoesNotMatter()
	{
		final List<QualityLlmPass.ExistingProposal> pair = Arrays.asList(new QualityLlmPass.ExistingProposal("REFERENCE", "PROPOSED", "COUNTRY"),
			new QualityLlmPass.ExistingProposal("REFERENCE", "REJECTED", "COUNTRY"));
		final QualityLlmPass.Session session = QualityLlmPassTest.sessionWithExistingRulesOn("country", pair);
		final LocalToolResultCheck r = QualityLlmPassTest.callRule(session, "Site", "country", "REFERENCE", "COUNTRY", "WARN", "resolves well", 80);
		QualityLlmPassTest.check("still reads as already-exists, not rejected: " + r.text, !r.isError && r.text.contains("already exists") && !r.text.contains("REJECTED"));
	}

	/** With ONLY a rejected twin (no non-rejected row at all), the model must be told plainly it was rejected, and nothing is created. */
	private static void onlyRejectedTwinTellsModelItWasRejected()
	{
		final List<QualityLlmPass.ExistingProposal> onlyRejected = Arrays.asList(new QualityLlmPass.ExistingProposal("REFERENCE", "REJECTED", "COUNTRY"));
		final QualityLlmPass.Session session = QualityLlmPassTest.sessionWithExistingRulesOn("country", onlyRejected);
		final LocalToolResultCheck r = QualityLlmPassTest.callRule(session, "Site", "country", "REFERENCE", "COUNTRY", "WARN", "resolves well", 80);
		QualityLlmPassTest.check("told plainly it was rejected by a reviewer: " + r.text,
			!r.isError && r.text.toUpperCase(Locale.ROOT).contains("REJECTED") && r.text.toLowerCase(Locale.ROOT).contains("reviewer"));
		QualityLlmPassTest.check("nothing created", session.proposedRules().isEmpty());
	}

	/** A rejected row for a DIFFERENT argument (a different regex, a different reference kind...) never blocks a genuinely different hypothesis on the same kind. */
	private static void rejectedTwinNeverBlocksADifferentArgument()
	{
		final List<QualityLlmPass.ExistingProposal> rejectedOtherArgument = Arrays
			.asList(new QualityLlmPass.ExistingProposal("PATTERN", "REJECTED", "^[0-9]{4}$"));
		final QualityLlmPass.Session session = QualityLlmPassTest.sessionWithExistingRulesOn("postalCode", rejectedOtherArgument);
		final LocalToolResultCheck r = QualityLlmPassTest.callRule(session, "Site", "postalCode", "PATTERN", "[0-9]{5}", "WARN", "matches 5-digit codes", 85);
		QualityLlmPassTest.check("a different argument is a different hypothesis, not blocked by the rejected one: " + r.text, !r.isError);
		QualityLlmPassTest.check("recorded", session.proposedRules().size() == 1);
	}

	/** Builds the shared fixture project/evidence, with {@code pExisting} attached as {@code existingRules} on the single column named {@code pAttribute} (entity "Site", matching the shared fixture's own Site.country/Site.postalCode). */
	private static QualityLlmPass.Session sessionWithExistingRulesOn(final String pAttribute, final List<QualityLlmPass.ExistingProposal> pExisting)
	{
		final List<QualityLlmPass.ColumnInfo> columns = new ArrayList<QualityLlmPass.ColumnInfo>(QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20).columns);
		for (int i = 0; i < columns.size(); i++)
		{
			if (pAttribute.equals(columns.get(i).attribute))
			{
				final QualityLlmPass.ColumnInfo original = columns.get(i);
				columns.set(i, new QualityLlmPass.ColumnInfo(original.entity, original.attribute, original.sourceColumn, original.dataType, original.maxLength,
					original.rows, original.fillRatePercent, original.distinctCount, original.shapes, original.topValues, original.existingSteps, pExisting));
			}
		}
		return new QualityLlmPass.Session(columns, QualityLlmPassTest.evidence(), QualityLlmPassTest.countryLookups(),
			Collections.<QualityLlmPass.ReferenceBindingInfo>emptyList(), QualityLlmPass.Mode.SHAPES_ONLY, 20);
	}

	// -------------------------------------------------------------------------------------- prompt size (round 3, item 4)

	/**
	 * Coordinator review round 3, 2026-09-21, item 4: "report the measured size of the user prompt for the
	 * 22-column fixture in characters, so we know the per-turn floor". Builds a 22-column fixture roughly
	 * shaped like the real run's own Supplier entity (mixed STRING/other types, 8 shapes shown per column, a
	 * few columns already carrying an existing step/rule) and prints {@code userPrompt(...).length()} - this
	 * number is the per-turn floor every batched turn's own request pays at minimum, printed here rather than
	 * merely asserted so it is visible in the test's own console output without re-deriving it by hand.
	 */
	private static void twentyTwoColumnPromptSizeIsMeasuredAndReported()
	{
		final List<QualityLlmPass.ColumnInfo> columns = new ArrayList<QualityLlmPass.ColumnInfo>();
		final String[] types = { "STRING", "STRING", "STRING", "STRING", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP" };
		for (int i = 0; i < 22; i++)
		{
			final List<QualityLlmPass.ShapeCount> shapes = new ArrayList<QualityLlmPass.ShapeCount>();
			for (int s = 0; s < 8; s++)
			{
				shapes.add(new QualityLlmPass.ShapeCount("Aa9(" + (s + 3) + ") -9999", 100L - s * 10L));
			}
			final List<QualityLlmPass.ExistingProposal> existingSteps = i % 5 == 0
				? Arrays.asList(new QualityLlmPass.ExistingProposal("TRIM", "ACCEPTED")) : Collections.<QualityLlmPass.ExistingProposal>emptyList();
			final List<QualityLlmPass.ExistingProposal> existingRules = i % 7 == 0
				? Arrays.asList(new QualityLlmPass.ExistingProposal("MANDATORY", "PROPOSED", null)) : Collections.<QualityLlmPass.ExistingProposal>emptyList();
			columns.add(new QualityLlmPass.ColumnInfo("Supplier", "attribute" + i, "SOURCE_COLUMN_" + i, types[i % types.length], Integer.valueOf(120),
				Long.valueOf(21000), Double.valueOf(97.5), Long.valueOf(8500), shapes, Collections.<String>emptyList(), existingSteps, existingRules));
		}
		final QualityLlmPass.ProjectInfo project = new QualityLlmPass.ProjectInfo("Companies", "Companies MDM", QualityLlmPass.Mode.SHAPES_ONLY, 20, columns,
			Arrays.asList(new QualityLlmPass.ReferenceBindingInfo("COUNTRY", "Alpha2Code", Arrays.asList("Name", "Alpha3Code"), null)));
		final String prompt = QualityLlmPass.userPrompt(project);
		System.out.println("[round 3, item 4] 22-column user prompt size: " + prompt.length() + " characters");
		// exactly twice by design: once in the detailed per-column block, once in the closing checklist - never
		// a third time (which would mean the attribute was genuinely duplicated, the round-1/round-3 item A bug)
		QualityLlmPassTest.check("the 22-column prompt names each column in the detail block and the checklist, never a third time",
			QualityLlmPassTest.countOccurrences(prompt, "Supplier.attribute0") == 2);
		QualityLlmPassTest.check("a sane, bounded floor - well under 100 000 characters for 22 columns", prompt.length() < 100000);
	}

	private static int countOccurrences(final String pHaystack, final String pNeedle)
	{
		int count = 0;
		int index = 0;
		while ((index = pHaystack.indexOf(pNeedle, index)) >= 0)
		{
			count++;
			index += pNeedle.length();
		}
		return count;
	}

	/** Same as the 3-arg form, with a generous default budget (20 turns) - the shared plumbing behind {@link #agentLoopEndToEndNoSentinelLeak()} and {@link #twoEntityRunsAreIndependentSecondStillGetsOwnRun()}. */
	private static AgentLoop.Result runScriptedAgentLoop(final QualityLlmPass.Session pSession, final QualityLlmPass.ProjectInfo pProject, final String[] pScriptedTurns)
		throws Exception
	{
		return QualityLlmPassTest.runScriptedAgentLoop(pSession, pProject, pScriptedTurns, new AgentLoop.Budget(20, 1000000, 60000L));
	}

	/** Runs one scripted {@code AgentLoop} against a fresh fake HTTP server, one server instance per call so each run's own request index starts at zero - {@code pBudget} lets a test exercise a tight step budget (see {@link #budgetExceededStillWritesAlreadyAcceptedProposals()}). */
	private static AgentLoop.Result runScriptedAgentLoop(final QualityLlmPass.Session pSession, final QualityLlmPass.ProjectInfo pProject, final String[] pScriptedTurns,
		final AgentLoop.Budget pBudget) throws Exception
	{
		final Map<String, ToolSet.LocalGroup> groups = new LinkedHashMap<String, ToolSet.LocalGroup>();
		groups.put("factory", pSession.toolGroup());
		final ToolSet tools = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), groups);

		final List<JsonNode> received = new ArrayList<JsonNode>();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				QualityLlmPassTest.read(pExchange.getRequestBody());
				received.add(null);
				final int index = received.size() - 1;
				final String block = pScriptedTurns[Math.min(index, pScriptedTurns.length - 1)];
				final boolean toolUse = block.contains("\"tool_use\"");
				final String answer = "{\"content\":[" + block + "],\"stop_reason\":\"" + (toolUse ? "tool_use" : "end_turn")
					+ "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
				QualityLlmPassTest.answerJson(pExchange, answer);
			}
		});
		server.start();
		try
		{
			final LlmClient client = new LlmClient(LlmClient.Format.ANTHROPIC, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages", null,
				"claude-sonnet-5", 1024, 2000, 5000);
			final AgentLoop loop = new AgentLoop(client, tools, pBudget, null);
			return loop.run(QualityLlmPass.systemPrompt(), QualityLlmPass.userPrompt(pProject));
		}
		finally
		{
			server.stop(0);
		}
	}

	/** Joins several {@link #toolUse} blocks into ONE scripted turn - the fake provider's {@code content} array then carries several {@code tool_use} blocks at once, exactly like a real batched turn. */
	private static String toolUseBatch(final String... pBlocks)
	{
		final StringBuilder out = new StringBuilder();
		for (final String block : pBlocks)
		{
			if (out.length() > 0)
			{
				out.append(',');
			}
			out.append(block);
		}
		return out.toString();
	}

	// ---------------------------------------------------------------------------------------------------- end to end

	private static void agentLoopEndToEndNoSentinelLeak() throws Exception
	{
		final QualityLlmPass.ProjectInfo project = QualityLlmPassTest.project(QualityLlmPass.Mode.SHAPES_ONLY, 20);
		final QualityLlmPass.Session session = QualityLlmPassTest.session(project);
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
				final JsonNode body = QualityLlmPassTest.MAPPER.readTree(QualityLlmPassTest.read(pExchange.getRequestBody()));
				received.add(body);
				final int index = received.size() - 1;
				final String[] blocks = QualityLlmPassTest.scriptedTurns();
				final String block = blocks[Math.min(index, blocks.length - 1)];
				final boolean toolUse = block.contains("\"tool_use\"");
				final String answer = "{\"content\":[" + block + "],\"stop_reason\":\"" + (toolUse ? "tool_use" : "end_turn") + "\",\"usage\":{\"input_tokens\":40,\"output_tokens\":15}}";
				QualityLlmPassTest.answerJson(pExchange, answer);
			}
		});
		server.start();
		try
		{
			final LlmClient client = new LlmClient(LlmClient.Format.ANTHROPIC, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages", null,
				"claude-sonnet-5", 1024, 2000, 5000);
			final AgentLoop loop = new AgentLoop(client, tools, new AgentLoop.Budget(20, 1000000, 60000L), null);
			final AgentLoop.Result result = loop.run(QualityLlmPass.systemPrompt(), QualityLlmPass.userPrompt(project));

			QualityLlmPassTest.check("the run completes DONE", result.status == AgentLoop.Status.DONE);
			QualityLlmPassTest.check("one cleaning step and one quality rule proposed (the REFUSED PATTERN call on Product.name recorded nothing)",
				session.proposedSteps().size() == 1 && session.proposedRules().size() == 1);
			QualityLlmPassTest.check("the step is TRIM on Product.name", "TRIM".equals(session.proposedSteps().get(0).kind) && "name".equals(session.proposedSteps().get(0).attribute));
			QualityLlmPassTest.check("the rule is CHECK_DIGIT (GS1_MOD10) on Product.gtin, not the refused PATTERN on Product.name",
				"CHECK_DIGIT".equals(session.proposedRules().get(0).kind) && "GS1_MOD10".equals(session.proposedRules().get(0).argument));

			// the SHAPES_ONLY guarantee, checked against every request the fake provider actually received - the
			// scripted sequence below exercises all three shapes of call (measure, an ACCEPTED propose_*, a
			// REFUSED propose_*) on the SENTINEL column itself (Product.name), per the coordinator's own
			// instruction (2026-09-21): none of them may put the sentinel into a request to the provider
			for (final JsonNode request : received)
			{
				QualityLlmPassTest.check("no request to the provider ever contains the sentinel value", !request.toString().contains(QualityLlmPassTest.SENTINEL));
			}
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * Exercises, in order, on the SENTINEL column ({@code Product.name}): a {@code measure} call (c1), a
	 * REFUSED {@code propose_quality_rule} call (c2, an invalid regex - the tool-result text the fake provider
	 * receives next must not contain the sentinel either, checked by {@link #agentLoopEndToEndNoSentinelLeak}
	 * over every request/response pair), then an ACCEPTED {@code propose_mapping_step} (c3) - plus the
	 * unrelated GTIN check digit (c4) and a note (c5), matching the coordinator's own instruction to prove the
	 * leak-free property across all three call shapes, not just the accepted one.
	 */
	private static String[] scriptedTurns()
	{
		return new String[] {
			QualityLlmPassTest.toolUse("c1", "factory__measure", "{\"entity\":\"Product\",\"attribute\":\"name\",\"kind\":\"PATTERN\",\"argument\":\"^[A-Z][a-z]+ [A-Z][a-z]+$\"}"),
			QualityLlmPassTest.toolUse("c2", "factory__propose_quality_rule",
				"{\"entity\":\"Product\",\"attribute\":\"name\",\"kind\":\"PATTERN\",\"argument\":\"[\",\"severity\":\"WARN\",\"confidence\":60,"
					+ "\"rationale\":\"Testing an invalid pattern on purpose.\"}"),
			QualityLlmPassTest.toolUse("c3", "factory__propose_mapping_step",
				"{\"entity\":\"Product\",\"attribute\":\"name\",\"kind\":\"TRIM\",\"confidence\":90,\"rationale\":\"Some values have leading or trailing spaces.\"}"),
			QualityLlmPassTest.toolUse("c4", "factory__propose_quality_rule",
				"{\"entity\":\"Product\",\"attribute\":\"gtin\",\"kind\":\"CHECK_DIGIT\",\"argument\":\"GS1_MOD10\",\"severity\":\"WARN\",\"confidence\":85,"
					+ "\"rationale\":\"A GTIN column should pass the GS1 check digit.\"}"),
			QualityLlmPassTest.toolUse("c5", "factory__note", "{\"text\":\"postalCode already looks clean, nothing more to propose there.\"}"),
			QualityLlmPassTest.text("Proposed TRIM on Product.name and a GS1_MOD10 check digit on Product.gtin.") };
	}

	// ------------------------------------------------------------------------------- lot M8b: steps versus the mapping's pipeline

	private static final String CASE_READING_REGEX = "regex((?:^(?=[\\p{Lu}'-]+\\s)|(?<=\\s)(?=[\\p{L}'-]+$))([\\p{L}'-]+))";

	/** One STRING column {@code Personne.lastName} fed by a full-name column, with the given pipelines and sample. */
	private static QualityLlmPass.Session sessionOnColumnWithPipeline(final List<String> pPipelines, final boolean pSampleExact, final List<Detectors.ValueCount> pValues,
		final QualityLlmPass.Mode pMode)
	{
		final QualityLlmPass.ColumnInfo column = new QualityLlmPass.ColumnInfo("Personne", "lastName", "FULL_NAME", "STRING", Integer.valueOf(80), Long.valueOf(100),
			Double.valueOf(100.0), Long.valueOf(pValues.size()), Arrays.asList(new QualityLlmPass.ShapeCount("Aaaa Aaaa", 8L)), Collections.<String>emptyList(),
			Collections.<QualityLlmPass.ExistingProposal>emptyList(), Collections.<QualityLlmPass.ExistingProposal>emptyList(), pPipelines, pSampleExact);
		final Map<String, QualityLlmPass.ColumnEvidence> evidence = new LinkedHashMap<String, QualityLlmPass.ColumnEvidence>();
		long total = 0;
		for (final Detectors.ValueCount value : pValues)
		{
			total += value.count;
		}
		evidence.put("Personne.lastName", new QualityLlmPass.ColumnEvidence(pValues, Double.valueOf(100.0), total, pValues.size()));
		return new QualityLlmPass.Session(Arrays.asList(column), evidence, CleaningSteps.Lookups.empty(), Collections.<QualityLlmPass.ReferenceBindingInfo>emptyList(),
			pMode, 20);
	}

	private static void caseChangeIsRefusedAheadOfACaseReadingPipeline()
	{
		final List<Detectors.ValueCount> names = Arrays.asList(new Detectors.ValueCount("Aur\u00e9lie-Sophie Navret", 5), new Detectors.ValueCount("SALNIER Nathalie-Julie", 3));
		final QualityLlmPass.Session session = QualityLlmPassTest.sessionOnColumnWithPipeline(Arrays.asList(QualityLlmPassTest.CASE_READING_REGEX), true, names,
			QualityLlmPass.Mode.SHAPES_ONLY);
		LocalToolResultCheck r = QualityLlmPassTest.callStep(session, "Personne", "lastName", "UPPER", null, "names should be capitals", 80);
		QualityLlmPassTest.check("UPPER ahead of a regex reading case is refused: " + r.text, r.isError && r.text.contains("BEFORE the mapping's own pipeline")
			&& r.text.contains("regex(...)|upper") && r.text.contains("Personne.lastName"));
		QualityLlmPassTest.check("nothing recorded", session.proposedSteps().isEmpty());
		r = QualityLlmPassTest.callStep(session, "Personne", "lastName", "NORMALISE_IDENTIFIER", null, "x", 80);
		QualityLlmPassTest.check("NORMALISE_IDENTIFIER upper-cases too: refused", r.isError && r.text.contains("changes letter case"));
		r = QualityLlmPassTest.callStep(session, "Personne", "lastName", "TRIM", null, "spaces", 80);
		QualityLlmPassTest.check("a step that keeps case is not refused by the order rule (it may be refused as a no-op, never for case)",
			!r.text.contains("letter case"));

		// without a pipeline, or with one that does not read case, the same step goes through
		final QualityLlmPass.Session plain = QualityLlmPassTest.sessionOnColumnWithPipeline(Collections.<String>emptyList(), true, names, QualityLlmPass.Mode.SHAPES_ONLY);
		r = QualityLlmPassTest.callStep(plain, "Personne", "lastName", "UPPER", null, "capitals", 80);
		QualityLlmPassTest.check("UPPER on a column with no pipeline is recorded", !r.isError && plain.proposedSteps().size() == 1);
		final QualityLlmPass.Session structural = QualityLlmPassTest.sessionOnColumnWithPipeline(Arrays.asList("regex(^(\\p{L}+))"), true, names,
			QualityLlmPass.Mode.SHAPES_ONLY);
		r = QualityLlmPassTest.callStep(structural, "Personne", "lastName", "UPPER", null, "capitals", 80);
		QualityLlmPassTest.check("UPPER ahead of a regex that does not read case is recorded: " + r.text, !r.isError);
		// several mappings: the guard is structural, so a case-reading pipeline on ANY of them refuses
		final QualityLlmPass.Session several = QualityLlmPassTest.sessionOnColumnWithPipeline(Arrays.asList("trim", QualityLlmPassTest.CASE_READING_REGEX), false, names,
			QualityLlmPass.Mode.SHAPES_ONLY);
		r = QualityLlmPassTest.callStep(several, "Personne", "lastName", "UPPER", null, "capitals", 80);
		QualityLlmPassTest.check("the guard holds for an attribute with several mappings", r.isError);
	}

	private static void stepThatEmptiesARegexPipelineIsRefusedWithModeAwareExamples()
	{
		final List<Detectors.ValueCount> values = Arrays.asList(new Detectors.ValueCount("Dr Smith", 5), new Detectors.ValueCount("Dr Jones", 3));
		final List<String> pipelines = Arrays.asList("regex(^Dr (\\w+))");
		QualityLlmPass.Session session = QualityLlmPassTest.sessionOnColumnWithPipeline(pipelines, true, values, QualityLlmPass.Mode.SAMPLE_VALUES);
		LocalToolResultCheck r = QualityLlmPassTest.callStep(session, "Personne", "lastName", "LOWER", null, "lower case", 80);
		QualityLlmPassTest.check("LOWER that leaves the regex nothing to match is refused: " + r.text, r.isError && r.text.contains("yields nothing on any of the 2 non-empty sample")
			&& r.text.contains("where it yielded a value on 2 without it"));
		QualityLlmPassTest.check("SAMPLE_VALUES mode quotes the inputs", r.text.contains("Dr Smith") && r.text.contains("Dr Jones"));
		QualityLlmPassTest.check("nothing recorded", session.proposedSteps().isEmpty());

		session = QualityLlmPassTest.sessionOnColumnWithPipeline(pipelines, true, values, QualityLlmPass.Mode.SHAPES_ONLY);
		r = QualityLlmPassTest.callStep(session, "Personne", "lastName", "LOWER", null, "lower case", 80);
		QualityLlmPassTest.check("SHAPES_ONLY mode never quotes a value: " + r.text, r.isError && !r.text.contains("Smith") && !r.text.contains("Jones") && r.text.contains("Aa Aaaaa"));

		session = QualityLlmPassTest.sessionOnColumnWithPipeline(pipelines, true, values, QualityLlmPass.Mode.NONE);
		r = QualityLlmPassTest.callStep(session, "Personne", "lastName", "LOWER", null, "lower case", 80);
		QualityLlmPassTest.check("NONE mode shows no input at all: " + r.text, r.isError && !r.text.contains("Smith") && !r.text.contains("inputs that give nothing"));

		// not measurable: several mappings (the evidence is a union), or a pipeline with a step the check cannot reproduce in front of the regex
		session = QualityLlmPassTest.sessionOnColumnWithPipeline(pipelines, false, values, QualityLlmPass.Mode.SAMPLE_VALUES);
		r = QualityLlmPassTest.callStep(session, "Personne", "lastName", "LOWER", null, "lower case", 80);
		QualityLlmPassTest.check("an attribute with several mappings is not measured: the step is recorded", !r.isError);
		session = QualityLlmPassTest.sessionOnColumnWithPipeline(Arrays.asList("map(A=B)|regex(^Dr (\\w+))"), true, values, QualityLlmPass.Mode.SAMPLE_VALUES);
		r = QualityLlmPassTest.callStep(session, "Personne", "lastName", "LOWER", null, "lower case", 80);
		QualityLlmPassTest.check("a pipeline the check cannot reproduce is skipped silently", !r.isError);

		// a pipeline that already yielded nothing is not the step's fault
		final List<String> alreadyBroken = Arrays.asList("regex(^Prof (\\w+))");
		session = QualityLlmPassTest.sessionOnColumnWithPipeline(alreadyBroken, true, values, QualityLlmPass.Mode.SAMPLE_VALUES);
		r = QualityLlmPassTest.callStep(session, "Personne", "lastName", "LOWER", null, "lower case", 80);
		QualityLlmPassTest.check("a pipeline that yields nothing without the step either does not refuse the step", !r.isError);
	}

	private static void pipelineIsShownAndOrderRuleIsStatedInThePrompts()
	{
		final QualityLlmPass.ColumnInfo column = new QualityLlmPass.ColumnInfo("Personne", "lastName", "FULL_NAME", "STRING", Integer.valueOf(80), Long.valueOf(5),
			Double.valueOf(100.0), Long.valueOf(1), Collections.<QualityLlmPass.ShapeCount>emptyList(), Collections.<String>emptyList(),
			Collections.<QualityLlmPass.ExistingProposal>emptyList(), Collections.<QualityLlmPass.ExistingProposal>emptyList(), Arrays.asList("regex(^Dr (\\w+))|upper"), true);
		final String prompt = QualityLlmPass.userPrompt(new QualityLlmPass.ProjectInfo("P", "P", QualityLlmPass.Mode.SHAPES_ONLY, 20, Arrays.asList(column),
			Collections.<QualityLlmPass.ReferenceBindingInfo>emptyList()));
		QualityLlmPassTest.check("the mapping's pipeline is shown under its column", prompt.contains("Mapping pipeline") && prompt.contains("regex(^Dr (\\w+))|upper"));
		final String system = QualityLlmPass.systemPrompt();
		QualityLlmPassTest.check("the system prompt states that a step runs BEFORE the mapping's pipeline", system.contains("runs on the raw text BEFORE the mapping's own pipeline"));
		QualityLlmPassTest.check("no value in it", !prompt.contains("Smith"));
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	/** Product.gtin (10 valid EAN-13 + 2 flipped), Product.name (dirty text + the sentinel), Site.postalCode (mostly 5-digit), Site.country (mostly resolvable). */
	private static QualityLlmPass.ProjectInfo project(final QualityLlmPass.Mode pMode, final int pSampleSize)
	{
		final List<QualityLlmPass.ShapeCount> gtinShapes = Arrays.asList(new QualityLlmPass.ShapeCount("9(13)", 12L));
		final List<String> gtinTop = pMode == QualityLlmPass.Mode.SAMPLE_VALUES ? Arrays.asList("4006381333931", "036000291452") : Collections.<String>emptyList();
		final QualityLlmPass.ColumnInfo gtin = new QualityLlmPass.ColumnInfo("Product", "gtin", "GTIN_CODE", "STRING", Integer.valueOf(14), Long.valueOf(12),
			Double.valueOf(80.0), Long.valueOf(11), gtinShapes, gtinTop, Collections.<QualityLlmPass.ExistingProposal>emptyList(),
			Collections.<QualityLlmPass.ExistingProposal>emptyList());

		final QualityLlmPass.ColumnInfo name = new QualityLlmPass.ColumnInfo("Product", "name", "PRODUCT_NAME", "STRING", Integer.valueOf(120), Long.valueOf(12),
			Double.valueOf(95.0), Long.valueOf(10), Arrays.asList(new QualityLlmPass.ShapeCount("Aaaaa Aaaaa", 8L)), Collections.<String>emptyList(),
			Collections.<QualityLlmPass.ExistingProposal>emptyList(), Collections.<QualityLlmPass.ExistingProposal>emptyList());

		final QualityLlmPass.ColumnInfo postalCode = new QualityLlmPass.ColumnInfo("Site", "postalCode", "POSTAL_CODE", "STRING", Integer.valueOf(10), Long.valueOf(12),
			Double.valueOf(100.0), Long.valueOf(12), Arrays.asList(new QualityLlmPass.ShapeCount("99999", 10L)), Collections.<String>emptyList(),
			Collections.<QualityLlmPass.ExistingProposal>emptyList(), Collections.<QualityLlmPass.ExistingProposal>emptyList());

		final QualityLlmPass.ColumnInfo country = new QualityLlmPass.ColumnInfo("Site", "country", "COUNTRY_NAME", "STRING", Integer.valueOf(60), Long.valueOf(12),
			Double.valueOf(100.0), Long.valueOf(4), Arrays.asList(new QualityLlmPass.ShapeCount("Aaaaaa", 9L)), Collections.<String>emptyList(),
			Collections.<QualityLlmPass.ExistingProposal>emptyList(), Collections.<QualityLlmPass.ExistingProposal>emptyList());

		// a SAMPLED column (coordinator review, 2026-09-21): only 3 distinct values are actually READ (as if a
		// cap had truncated a much larger column), but exactDistinctCount (in evidence()) says the true total is
		// 998 - "the capped list and the exact aggregates disagree", exactly the fixture the coordinator asked
		// for; see sampledColumnGateFollowsExactAggregates()
		final QualityLlmPass.ColumnInfo batchCode = new QualityLlmPass.ColumnInfo("Product", "batchCode", "BATCH_CODE", "STRING", Integer.valueOf(10), Long.valueOf(1000),
			Double.valueOf(100.0), Long.valueOf(998), Arrays.asList(new QualityLlmPass.ShapeCount("A999", 6L)), Collections.<String>emptyList(),
			Collections.<QualityLlmPass.ExistingProposal>emptyList(), Collections.<QualityLlmPass.ExistingProposal>emptyList());

		final List<QualityLlmPass.ReferenceBindingInfo> referenceBindings = Arrays
			.asList(new QualityLlmPass.ReferenceBindingInfo("COUNTRY", "Alpha2Code", Arrays.asList("Name", "Alpha3Code"), null));

		return new QualityLlmPass.ProjectInfo("Retail", "Retail MDM", pMode, pSampleSize, Arrays.asList(gtin, name, postalCode, country, batchCode),
			referenceBindings);
	}

	private static Map<String, QualityLlmPass.ColumnEvidence> evidence()
	{
		final Map<String, QualityLlmPass.ColumnEvidence> out = new LinkedHashMap<String, QualityLlmPass.ColumnEvidence>();
		// 10 valid GTIN + 2 synthetic invalid (last digit flipped) - exact aggregates match the sample exactly (not sampled)
		out.put("Product.gtin", new QualityLlmPass.ColumnEvidence(Arrays.asList(new Detectors.ValueCount("4006381333931", 10), new Detectors.ValueCount("036000291453", 2)),
			Double.valueOf(80.0), 12L, 2L));
		// dirty text (leading spaces on some rows) plus the sentinel, planted once - exact aggregates match the sample (not sampled)
		out.put("Product.name", new QualityLlmPass.ColumnEvidence(
			Arrays.asList(new Detectors.ValueCount("Blue Widget", 5), new Detectors.ValueCount("  Red Widget", 4), new Detectors.ValueCount(QualityLlmPassTest.SENTINEL, 1)),
			Double.valueOf(95.0), 10L, 3L));
		// mostly 5-digit postal codes, 2 malformed - exact aggregates match the sample (not sampled)
		out.put("Site.postalCode",
			new QualityLlmPass.ColumnEvidence(Arrays.asList(new Detectors.ValueCount("75001", 5), new Detectors.ValueCount("94100", 5), new Detectors.ValueCount("N/A", 2)),
				Double.valueOf(100.0), 12L, 3L));
		// country names, all resolving against the fixture's country table - exact aggregates match the sample (not sampled)
		out.put("Site.country",
			new QualityLlmPass.ColumnEvidence(Arrays.asList(new Detectors.ValueCount("France", 6), new Detectors.ValueCount("Germany", 3), new Detectors.ValueCount("Italy", 3)),
				Double.valueOf(100.0), 12L, 3L));
		// SAMPLED (coordinator review, 2026-09-21): the 3 distinct values actually read sum to 6 rows, each with
		// count 2 (naively "everything is duplicated") - but the EXACT aggregates say 1000 filled rows, 998
		// distinct: only 2 rows are genuinely duplicated in the whole column. The old, capped-sample-only logic
		// would have reported the sample's own inflated picture (6 seen, "3 of 3 distinct values duplicated");
		// the fix must report the EXACT figures (1000 seen, 2 failing) instead - see
		// sampledColumnGateFollowsExactAggregates(). One of the three sample values ("  B001") also has a
		// leading space, so a TRIM step can be measured/proposed against the same sampled evidence.
		out.put("Product.batchCode", new QualityLlmPass.ColumnEvidence(
			Arrays.asList(new Detectors.ValueCount("  B001", 2), new Detectors.ValueCount("B002", 2), new Detectors.ValueCount("B003", 2)), Double.valueOf(100.0), 1000L,
			998L));
		return out;
	}

	private static CleaningSteps.Lookups countryLookups()
	{
		final Map<String, List<String>> rows = new LinkedHashMap<String, List<String>>();
		rows.put("FR", Arrays.asList("France", "FRA"));
		rows.put("DE", Arrays.asList("Germany", "DEU"));
		rows.put("IT", Arrays.asList("Italy", "ITA"));
		return new CleaningSteps.Lookups(Collections.singletonMap("COUNTRY", CleaningSteps.buildFlatTable(rows, null)), null);
	}

	/** Resolves only "Italy" (3 of the fixture's 12 seen values, 25%) - below both the mapping-step (50%) and the MODEL_CONSTRAINT (90%) thresholds, but still leaves something that actually changes (never triggers the separate "changes nothing" refusal instead of the threshold one). */
	private static CleaningSteps.Lookups sparseCountryLookups()
	{
		final Map<String, List<String>> rows = new LinkedHashMap<String, List<String>>();
		rows.put("IT", Arrays.asList("Italy", "ITA"));
		return new CleaningSteps.Lookups(Collections.singletonMap("COUNTRY", CleaningSteps.buildFlatTable(rows, null)), null);
	}

	private static QualityLlmPass.Session session(final QualityLlmPass.ProjectInfo pProject)
	{
		return new QualityLlmPass.Session(pProject.columns, QualityLlmPassTest.evidence(), QualityLlmPassTest.countryLookups(), pProject.referenceBindings,
			pProject.mode, pProject.sampleSize);
	}

	// --------------------------------------------------------------------------------------------------- call helpers

	private static final class LocalToolResultCheck
	{
		final String text;
		final boolean isError;

		LocalToolResultCheck(final String pText, final boolean pIsError)
		{
			this.text = pText;
			this.isError = pIsError;
		}
	}

	private static LocalToolResultCheck callMeasure(final QualityLlmPass.Session pSession, final String pEntity, final String pAttribute, final String pKind,
		final String pArgument)
	{
		final com.fasterxml.jackson.databind.node.ObjectNode args = QualityLlmPassTest.MAPPER.createObjectNode();
		args.put("entity", pEntity);
		args.put("attribute", pAttribute);
		args.put("kind", pKind);
		args.put("argument", pArgument);
		final com.on.ebx.agent.LocalTool.Result r = pSession.measure(args);
		return new LocalToolResultCheck(r.text, r.isError);
	}

	private static LocalToolResultCheck callStep(final QualityLlmPass.Session pSession, final String pEntity, final String pAttribute, final String pKind,
		final String pArgument, final String pRationale, final int pConfidence)
	{
		final com.fasterxml.jackson.databind.node.ObjectNode args = QualityLlmPassTest.MAPPER.createObjectNode();
		args.put("entity", pEntity);
		args.put("attribute", pAttribute);
		args.put("kind", pKind);
		if (pArgument != null)
		{
			args.put("argument", pArgument);
		}
		args.put("rationale", pRationale);
		args.put("confidence", pConfidence);
		final com.on.ebx.agent.LocalTool.Result r = pSession.proposeMappingStep(args);
		return new LocalToolResultCheck(r.text, r.isError);
	}

	private static LocalToolResultCheck callRule(final QualityLlmPass.Session pSession, final String pEntity, final String pAttribute, final String pKind,
		final String pArgument, final String pSeverity, final String pRationale, final int pConfidence)
	{
		final com.fasterxml.jackson.databind.node.ObjectNode args = QualityLlmPassTest.MAPPER.createObjectNode();
		args.put("entity", pEntity);
		args.put("attribute", pAttribute);
		args.put("kind", pKind);
		if (pArgument != null)
		{
			args.put("argument", pArgument);
		}
		args.put("severity", pSeverity);
		args.put("rationale", pRationale);
		args.put("confidence", pConfidence);
		final com.on.ebx.agent.LocalTool.Result r = pSession.proposeQualityRule(args);
		return new LocalToolResultCheck(r.text, r.isError);
	}

	/** Desk lot L4b: the pass narrowed to one existing rule - the prompt says so, the session refuses a step, another rule, and the same rule without a condition. */
	private static void aRunNarrowedToOneRuleAsksOnlyForItsCondition()
	{
		final QualityLlmPass.ProjectInfo project = QualityLlmPassTest.project(QualityLlmPass.Mode.SAMPLE_VALUES, 20);
		final RuleFocus ruleFocus = new RuleFocus("a1", "PATTERN", "^[0-9]{5}$");
		final String ordinary = QualityLlmPass.userPrompt(project);
		final String narrowed = QualityLlmPass.userPrompt(project, ruleFocus);
		QualityLlmPassTest.check("the narrowed prompt keeps the ordinary content", narrowed.contains("MAPPED COLUMNS") && narrowed.startsWith(ordinary.substring(0, 200)));
		QualityLlmPassTest.check("it says what the run is for", narrowed.contains("THIS RUN IS NARROWED") && narrowed.contains("^[0-9]{5}$") && narrowed.contains("conditionAttribute"));
		QualityLlmPassTest.check("it no longer asks to go through every column", !narrowed.contains("go through every column above"));
		QualityLlmPassTest.check("the ordinary prompt is unchanged by the overload", ordinary.equals(QualityLlmPass.userPrompt(project, null)));

		final QualityLlmPass.Session session = QualityLlmPassTest.session(project).restrictTo(ruleFocus);
		final LocalToolResultCheck step = QualityLlmPassTest.callStep(session, "Product", "name", "TRIM", null, "leading/trailing spaces observed", 90);
		QualityLlmPassTest.check("a cleaning step is refused: " + step.text, step.isError && step.text.contains("propose no cleaning step"));
		final LocalToolResultCheck other = QualityLlmPassTest.callRule(session, "Site", "postalCode", "PATTERN", "^[A-Z]+$", "WARN", "x", 80);
		QualityLlmPassTest.check("another rule is refused: " + other.text, other.isError && other.text.contains("only about the existing PATTERN rule"));
		final LocalToolResultCheck plain = QualityLlmPassTest.callRule(session, "Site", "postalCode", "PATTERN", "^[0-9]{5}$", "WARN", "x", 80);
		QualityLlmPassTest.check("the same rule without a condition is refused: " + plain.text, plain.isError && plain.text.contains("give conditionAttribute"));
		QualityLlmPassTest.check("nothing was recorded", session.proposedRules().isEmpty() && session.proposedSteps().isEmpty());
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
