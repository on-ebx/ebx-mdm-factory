package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LocalTool;

/**
 * Validation desk lot L4: where a rule fails. The fixture is the DemoBala address case: {@code postalCode ^[0-9]{5}$} passes 287 of 335 values and the 48 that fail are correct
 * postal codes of other countries (CH 13, BE 9, SE 8, PT 6, PL 5, NL 4, IE 3), France 247 values / 0 failing. JUnit-free: a failure throws.
 */
public final class FailureBreakdownTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String PATTERN = "^[0-9]{5}$";

	public static void main(final String[] pArgs)
	{
		FailureBreakdownTest.bestSeparatingAttributeIsTheCountry();
		FailureBreakdownTest.nothingFailingGivesNoBreakdown();
		FailureBreakdownTest.anAttributeThatDoesNotSeparateIsNotOffered();
		FailureBreakdownTest.tooManyGroupsAreNotOffered();
		FailureBreakdownTest.jsonRoundTripAndCap();
		FailureBreakdownTest.markerMeansMeasuredWithoutResult();
		FailureBreakdownTest.promptTextShowsValuesOnlyWhenShared();
		FailureBreakdownTest.conditionProblems();
		FailureBreakdownTest.measureCarriesTheBreakdown();
		FailureBreakdownTest.proposeWithAConditionIsRecordedAndJudgedWithinIt();
		FailureBreakdownTest.proposeRefusesBadConditions();
		FailureBreakdownTest.aConditionOnAnExistingOpenRuleUpdatesIt();
		FailureBreakdownTest.existingRuleIsShownWithItsBreakdown();
		System.out.println("FailureBreakdownTest OK");
	}

	// ----------------------------------------------------------------------------------------------------------- fixture

	private static void add(final List<Detectors.ValueCount> pPairs, final String pValue, final String pGroup, final long pCount)
	{
		pPairs.add(new Detectors.ValueCount(pValue, pGroup, pCount));
	}

	/** (postal code, country) pairs of the case: 335 values, 287 pass. */
	private static List<Detectors.ValueCount> byCountry()
	{
		final List<Detectors.ValueCount> pairs = new ArrayList<Detectors.ValueCount>();
		FailureBreakdownTest.add(pairs, "75001", "FR", 150);
		FailureBreakdownTest.add(pairs, "69002", "FR", 97);
		FailureBreakdownTest.add(pairs, "10115", "DE", 20);
		FailureBreakdownTest.add(pairs, "00100", "IT", 10);
		FailureBreakdownTest.add(pairs, "28001", "ES", 10);
		FailureBreakdownTest.add(pairs, "1201", "CH", 13);
		FailureBreakdownTest.add(pairs, "1000", "BE", 9);
		FailureBreakdownTest.add(pairs, "111 20", "SE", 8);
		FailureBreakdownTest.add(pairs, "1000-001", "PT", 6);
		FailureBreakdownTest.add(pairs, "00-001", "PL", 5);
		FailureBreakdownTest.add(pairs, "1011AB", "NL", 4);
		FailureBreakdownTest.add(pairs, "D02", "IE", 3);
		return pairs;
	}

	/** The same rows grouped by the source system: failures spread over both. */
	private static List<Detectors.ValueCount> bySource()
	{
		final List<Detectors.ValueCount> pairs = new ArrayList<Detectors.ValueCount>();
		FailureBreakdownTest.add(pairs, "75001", "SAP", 100);
		FailureBreakdownTest.add(pairs, "1201", "SAP", 20);
		FailureBreakdownTest.add(pairs, "69002", "SF", 100);
		FailureBreakdownTest.add(pairs, "1000", "SF", 28);
		FailureBreakdownTest.add(pairs, "10115", "SF", 20);
		return pairs;
	}

	private static List<Detectors.ValueCount> constant()
	{
		final List<Detectors.ValueCount> pairs = new ArrayList<Detectors.ValueCount>();
		FailureBreakdownTest.add(pairs, "75001", "MAILING", 300);
		FailureBreakdownTest.add(pairs, "1201", "MAILING", 35);
		return pairs;
	}

	private static FailureBreakdown.Judge patternJudge()
	{
		return QualityLlmPass.judge(QualityDetectors.PATTERN, FailureBreakdownTest.PATTERN, null);
	}

	private static List<FailureBreakdown.Joint> joints()
	{
		return Arrays.asList(new FailureBreakdown.Joint("addressType", FailureBreakdownTest.constant()),
			new FailureBreakdown.Joint("sourceSystem", FailureBreakdownTest.bySource()), new FailureBreakdown.Joint("countryCode", FailureBreakdownTest.byCountry()));
	}

	private static final FailureBreakdown.Shower REAL = new FailureBreakdown.Shower()
	{
		@Override
		public String show(final String pValue)
		{
			return pValue;
		}
	};

	// ------------------------------------------------------------------------------------------------------ pure parts

	private static void bestSeparatingAttributeIsTheCountry()
	{
		final FailureBreakdown.Result result = FailureBreakdown.best(FailureBreakdownTest.joints(), FailureBreakdownTest.patternJudge(), FailureBreakdownTest.REAL);
		FailureBreakdownTest.check("the country separates failing from passing values", result != null && "countryCode".equals(result.attribute));
		long seen = 0;
		long failing = 0;
		for (final FailureBreakdown.Group group : result.groups)
		{
			seen += group.seen;
			failing += group.failing;
		}
		FailureBreakdownTest.check("287 of 335 pass, 48 fail: " + seen + "/" + failing, seen == 335 && failing == 48);
		final long[] france = result.within(Arrays.asList("fr"));
		FailureBreakdownTest.check("France: 247 values, 0 failing (case ignored)", france[0] == 247 && france[1] == 0);
		FailureBreakdownTest.check("Switzerland fails every value", result.within(Arrays.asList("CH"))[1] == 13);
		FailureBreakdownTest.check("the groups that fail come first, most failing first: " + result.groups.get(0).value, "CH".equals(result.groups.get(0).value));
		FailureBreakdownTest.check("the clean groups", result.cleanValues().containsAll(Arrays.asList("FR", "DE", "IT", "ES")) && result.cleanValues().size() == 4);
		FailureBreakdownTest.check("an example per failing group", result.groups.get(0).examples.equals(Arrays.asList("1201")));
		// the shape, not the value, when the project does not share values
		final FailureBreakdown.Result shapes = FailureBreakdown.best(FailureBreakdownTest.joints(), FailureBreakdownTest.patternJudge(),
			QualityLlmPass.shower(QualityLlmPass.Mode.SHAPES_ONLY));
		FailureBreakdownTest.check("shapes only: the example is a shape", shapes.groups.get(0).examples.equals(Arrays.asList("9999")));
		final FailureBreakdown.Result none = FailureBreakdown.best(FailureBreakdownTest.joints(), FailureBreakdownTest.patternJudge(), QualityLlmPass.shower(QualityLlmPass.Mode.NONE));
		FailureBreakdownTest.check("metadata only: no example at all", none.groups.get(0).examples.isEmpty());
	}

	private static void nothingFailingGivesNoBreakdown()
	{
		final List<Detectors.ValueCount> pairs = new ArrayList<Detectors.ValueCount>();
		FailureBreakdownTest.add(pairs, "75001", "FR", 10);
		FailureBreakdownTest.add(pairs, "10115", "DE", 10);
		FailureBreakdownTest.check("nothing fails: no breakdown",
			FailureBreakdown.best(Arrays.asList(new FailureBreakdown.Joint("countryCode", pairs)), FailureBreakdownTest.patternJudge(), FailureBreakdownTest.REAL) == null);
		FailureBreakdownTest.check("no joint: no breakdown", FailureBreakdown.best(Collections.<FailureBreakdown.Joint> emptyList(), FailureBreakdownTest.patternJudge(), null) == null);
	}

	private static void anAttributeThatDoesNotSeparateIsNotOffered()
	{
		final FailureBreakdown.Result source = FailureBreakdown.best(Arrays.asList(new FailureBreakdown.Joint("sourceSystem", FailureBreakdownTest.bySource())),
			FailureBreakdownTest.patternJudge(), FailureBreakdownTest.REAL);
		FailureBreakdownTest.check("failures spread over both sources: nothing is offered", source == null);
		FailureBreakdownTest.check("a single group never separates", FailureBreakdown.best(Arrays.asList(new FailureBreakdown.Joint("addressType", FailureBreakdownTest.constant())),
			FailureBreakdownTest.patternJudge(), FailureBreakdownTest.REAL) == null);
	}

	private static void tooManyGroupsAreNotOffered()
	{
		final List<Detectors.ValueCount> pairs = new ArrayList<Detectors.ValueCount>();
		for (int i = 0; i < FailureBreakdown.MAX_GROUPS + 1; i++)
		{
			FailureBreakdownTest.add(pairs, i % 2 == 0 ? "75001" : "1201", "G" + i, 3);
		}
		FailureBreakdownTest.check("31 groups is too many",
			FailureBreakdown.groupBy(new FailureBreakdown.Joint("g", pairs), FailureBreakdownTest.patternJudge(), FailureBreakdownTest.REAL) == null);
		FailureBreakdownTest.check("the joint counts them", new FailureBreakdown.Joint("g", pairs).groupCount() == 31);
	}

	private static void jsonRoundTripAndCap()
	{
		final FailureBreakdown.Result result = FailureBreakdown.best(FailureBreakdownTest.joints(), FailureBreakdownTest.patternJudge(), FailureBreakdownTest.REAL);
		final String json = FailureBreakdown.toJson(result);
		final FailureBreakdown.Result back = FailureBreakdown.parse(json);
		FailureBreakdownTest.check("round trip keeps the groups", back != null && back.groups.size() == result.groups.size() && "countryCode".equals(back.attribute));
		FailureBreakdownTest.check("round trip keeps the counts", back.within(Arrays.asList("CH"))[1] == 13 && back.within(Arrays.asList("FR"))[0] == 247);
		// 30 groups with 5 long examples each do not fit 4000 characters: examples go first, the clean groups last
		final List<Detectors.ValueCount> pairs = new ArrayList<Detectors.ValueCount>();
		final StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 40; i++)
		{
			longText.append('x');
		}
		for (int g = 0; g < FailureBreakdown.MAX_GROUPS; g++)
		{
			for (int e = 0; e < 6; e++)
			{
				FailureBreakdownTest.add(pairs, longText + "-" + g + "-" + e, "group-number-" + g, 2);
			}
			FailureBreakdownTest.add(pairs, "75001", "group-number-" + g, 5);
		}
		final FailureBreakdown.Result big = FailureBreakdown.groupBy(new FailureBreakdown.Joint("g", pairs), FailureBreakdownTest.patternJudge(), FailureBreakdownTest.REAL);
		FailureBreakdownTest.check("at most 5 examples per group", big.groups.get(0).examples.size() == FailureBreakdown.MAX_EXAMPLES_PER_GROUP);
		final String bigJson = FailureBreakdown.toJson(big);
		FailureBreakdownTest.check("the stored text fits the field: " + bigJson.length(), bigJson.length() <= FailureBreakdown.MAX_JSON_LENGTH);
		FailureBreakdownTest.check("and is still a breakdown", FailureBreakdown.parse(bigJson) != null);
		FailureBreakdownTest.check("a blank or malformed text is none", FailureBreakdown.parse("") == null && FailureBreakdown.parse("{not json") == null && FailureBreakdown.parse(null) == null);
	}

	private static void markerMeansMeasuredWithoutResult()
	{
		FailureBreakdownTest.check("the marker is measured", FailureBreakdown.measured(FailureBreakdown.NOT_FOUND_JSON));
		FailureBreakdownTest.check("the marker is no breakdown", FailureBreakdown.parse(FailureBreakdown.NOT_FOUND_JSON) == null);
		FailureBreakdownTest.check("blank is not measured", !FailureBreakdown.measured("") && !FailureBreakdown.measured(null) && !FailureBreakdown.measured("{oops"));
	}

	private static void promptTextShowsValuesOnlyWhenShared()
	{
		final FailureBreakdown.Result result = FailureBreakdown.best(FailureBreakdownTest.joints(), FailureBreakdownTest.patternJudge(), FailureBreakdownTest.REAL);
		final String shown = FailureBreakdown.promptText(result, true);
		FailureBreakdownTest.check("names the field and the failing groups: " + shown, shown.contains("countryCode=CH: 13/13 fail") && shown.contains("48 of 335") && shown.contains("no failure for countryCode in FR (247)"));
		final String hidden = FailureBreakdown.promptText(result, false);
		FailureBreakdownTest.check("without value sharing no value of the field is written: " + hidden, hidden.contains("48 of 335") && !hidden.contains("CH") && !hidden.contains("FR") && hidden.contains("not shown"));
	}

	private static void conditionProblems()
	{
		final FailureBreakdown.Result result = FailureBreakdown.best(FailureBreakdownTest.joints(), FailureBreakdownTest.patternJudge(), FailureBreakdownTest.REAL);
		FailureBreakdownTest.check("a measured value is fine", FailureBreakdown.conditionProblem(result, Arrays.asList("FR", "de")) == null);
		FailureBreakdownTest.check("a value never measured is refused", FailureBreakdown.conditionProblem(result, Arrays.asList("FR", "XX")).contains("XX"));
		FailureBreakdownTest.check("an empty list is refused", FailureBreakdown.conditionProblem(result, Collections.<String> emptyList()) != null);
	}

	// ---------------------------------------------------------------------------------------------------------- session

	private static QualityLlmPass.Session session(final QualityLlmPass.Mode pMode, final List<Detectors.ValueCount> pValues, final List<QualityLlmPass.ExistingProposal> pExisting)
	{
		final QualityLlmPass.ColumnInfo postalCode = new QualityLlmPass.ColumnInfo("Adresse", "postalCode", "PLZ", "STRING", Integer.valueOf(10), Long.valueOf(335),
			Double.valueOf(100.0), Long.valueOf(pValues.size()), Arrays.asList(new QualityLlmPass.ShapeCount("99999", 287L)), Collections.<String> emptyList(),
			Collections.<QualityLlmPass.ExistingProposal> emptyList(), pExisting);
		long total = 0;
		for (final Detectors.ValueCount value : pValues)
		{
			total += value.count;
		}
		final Map<String, QualityLlmPass.ColumnEvidence> evidence = new LinkedHashMap<String, QualityLlmPass.ColumnEvidence>();
		evidence.put("Adresse.postalCode", new QualityLlmPass.ColumnEvidence(pValues, Double.valueOf(100.0), total, pValues.size(), FailureBreakdownTest.joints()));
		return new QualityLlmPass.Session(Arrays.asList(postalCode), evidence, CleaningSteps.Lookups.empty(), Collections.<QualityLlmPass.ReferenceBindingInfo> emptyList(), pMode, 20);
	}

	/** The distinct postal codes of the case, without the group. */
	private static List<Detectors.ValueCount> values()
	{
		final List<Detectors.ValueCount> out = new ArrayList<Detectors.ValueCount>();
		for (final Detectors.ValueCount pair : FailureBreakdownTest.byCountry())
		{
			out.add(new Detectors.ValueCount(pair.value, pair.count));
		}
		return out;
	}

	private static ObjectNode rule(final String pSeverity, final String pKind, final String pConditionAttribute, final String... pConditionValues)
	{
		final ObjectNode args = FailureBreakdownTest.MAPPER.createObjectNode();
		args.put("entity", "Adresse");
		args.put("attribute", "postalCode");
		args.put("kind", pKind);
		args.put("argument", FailureBreakdownTest.PATTERN);
		args.put("severity", pSeverity);
		args.put("rationale", "five digits in France");
		args.put("confidence", 85);
		if (pConditionAttribute != null)
		{
			args.put("conditionAttribute", pConditionAttribute);
		}
		if (pConditionValues.length > 0)
		{
			final ArrayNode array = args.putArray("conditionValues");
			for (final String value : pConditionValues)
			{
				array.add(value);
			}
		}
		return args;
	}

	private static void measureCarriesTheBreakdown()
	{
		final ObjectNode args = FailureBreakdownTest.MAPPER.createObjectNode();
		args.put("entity", "Adresse");
		args.put("attribute", "postalCode");
		args.put("kind", "PATTERN");
		args.put("argument", FailureBreakdownTest.PATTERN);
		final LocalTool.Result real = FailureBreakdownTest.session(QualityLlmPass.Mode.SAMPLE_VALUES, FailureBreakdownTest.values(), null).measure(args);
		FailureBreakdownTest.check("the measure says where it fails: " + real.text, !real.isError && real.text.contains("287 of 335") && real.text.contains("countryCode=CH: 13/13 fail"));
		final LocalTool.Result shapes = FailureBreakdownTest.session(QualityLlmPass.Mode.SHAPES_ONLY, FailureBreakdownTest.values(), null).measure(args);
		FailureBreakdownTest.check("shapes only: the concentration, no value of the other field: " + shapes.text, shapes.text.contains("48 of 335") && !shapes.text.contains("countryCode=CH") && shapes.text.contains("not shown"));
	}

	private static void proposeWithAConditionIsRecordedAndJudgedWithinIt()
	{
		final QualityLlmPass.Session session = FailureBreakdownTest.session(QualityLlmPass.Mode.SAMPLE_VALUES, FailureBreakdownTest.values(), null);
		final LocalTool.Result result = session.proposeQualityRule(FailureBreakdownTest.rule("WARN", "PATTERN", "countryCode", "fr"));
		FailureBreakdownTest.check("recorded: " + result.text, !result.isError && session.proposedRules().size() == 1);
		final QualityLlmPass.ProposedRule rule = session.proposedRules().get(0);
		FailureBreakdownTest.check("the condition, with the measured spelling", "countryCode".equals(rule.conditionAttribute) && rule.conditionValues.equals(Arrays.asList("FR")));
		FailureBreakdownTest.check("the study's figures stay the whole column's", rule.valuesSeen == 335 && rule.valuesFailing == 48);
		final FailureBreakdown.Result stored = FailureBreakdown.parse(rule.failureBreakdown);
		FailureBreakdownTest.check("the breakdown is stored with the rule", stored != null && stored.within(rule.conditionValues)[0] == 247 && stored.within(rule.conditionValues)[1] == 0);
		FailureBreakdownTest.check("not an update of an existing rule", !rule.updatesExisting);
		// a pattern that fails on most values overall is refused, and accepted under the condition that makes it valid
		final List<Detectors.ValueCount> mostlyForeign = new ArrayList<Detectors.ValueCount>();
		mostlyForeign.add(new Detectors.ValueCount("75001", 100));
		mostlyForeign.add(new Detectors.ValueCount("1201", 300));
		final List<FailureBreakdown.Joint> joint = Arrays.asList(new FailureBreakdown.Joint("countryCode", Arrays.asList(new Detectors.ValueCount("75001", "FR", 100L),
			new Detectors.ValueCount("1201", "CH", 300L))));
		final QualityLlmPass.ColumnInfo column = new QualityLlmPass.ColumnInfo("Adresse", "postalCode", "PLZ", "STRING", Integer.valueOf(10), Long.valueOf(400), Double.valueOf(100.0),
			Long.valueOf(2), Arrays.asList(new QualityLlmPass.ShapeCount("99999", 100L)), Collections.<String> emptyList(), Collections.<QualityLlmPass.ExistingProposal> emptyList(),
			Collections.<QualityLlmPass.ExistingProposal> emptyList());
		final Map<String, QualityLlmPass.ColumnEvidence> evidence = new LinkedHashMap<String, QualityLlmPass.ColumnEvidence>();
		evidence.put("Adresse.postalCode", new QualityLlmPass.ColumnEvidence(mostlyForeign, Double.valueOf(100.0), 400, 2, joint));
		final QualityLlmPass.Session foreign = new QualityLlmPass.Session(Arrays.asList(column), evidence, CleaningSteps.Lookups.empty(),
			Collections.<QualityLlmPass.ReferenceBindingInfo> emptyList(), QualityLlmPass.Mode.SAMPLE_VALUES, 20);
		final LocalTool.Result plain = foreign.proposeQualityRule(FailureBreakdownTest.rule("WARN", "PATTERN", null));
		FailureBreakdownTest.check("without the condition it fails 75 %: refused: " + plain.text, plain.isError && foreign.proposedRules().isEmpty());
		final LocalTool.Result conditioned = foreign.proposeQualityRule(FailureBreakdownTest.rule("WARN", "PATTERN", "countryCode", "FR"));
		FailureBreakdownTest.check("with the condition it fails nothing: accepted: " + conditioned.text, !conditioned.isError && foreign.proposedRules().size() == 1);
	}

	/** One fresh session per refusal: three identical refusals in a row end a run (the circuit breaker), which is not what is tested here. */
	private static LocalTool.Result refused(final ObjectNode pArgs)
	{
		final QualityLlmPass.Session session = FailureBreakdownTest.session(QualityLlmPass.Mode.SAMPLE_VALUES, FailureBreakdownTest.values(), null);
		final LocalTool.Result result = session.proposeQualityRule(pArgs);
		FailureBreakdownTest.check("nothing was recorded: " + result.text, session.proposedRules().isEmpty());
		return result;
	}

	private static void proposeRefusesBadConditions()
	{
		final LocalTool.Result unknownAttribute = FailureBreakdownTest.refused(FailureBreakdownTest.rule("WARN", "PATTERN", "colour", "FR"));
		FailureBreakdownTest.check("an attribute that was not measured against this column is refused: " + unknownAttribute.text, unknownAttribute.isError && unknownAttribute.text.contains("countryCode"));
		final LocalTool.Result unknownValue = FailureBreakdownTest.refused(FailureBreakdownTest.rule("WARN", "PATTERN", "countryCode", "FR", "XX"));
		FailureBreakdownTest.check("a value that was never measured is refused: " + unknownValue.text, unknownValue.isError && unknownValue.text.contains("XX"));
		FailureBreakdownTest.check("an attribute without values is refused", FailureBreakdownTest.refused(FailureBreakdownTest.rule("WARN", "PATTERN", "countryCode")).isError);
		FailureBreakdownTest.check("values without an attribute are refused", FailureBreakdownTest.refused(FailureBreakdownTest.rule("WARN", "PATTERN", null, "FR")).isError);
		final LocalTool.Result model = FailureBreakdownTest.refused(FailureBreakdownTest.rule("MODEL_CONSTRAINT", "PATTERN", "countryCode", "FR"));
		FailureBreakdownTest.check("a model constraint can never carry a condition: " + model.text, model.isError && model.text.contains("MODEL_CONSTRAINT"));
		FailureBreakdownTest.check("a mandatory rule has no per-value condition", FailureBreakdownTest.refused(FailureBreakdownTest.rule("WARN", "MANDATORY", "countryCode", "FR")).isError);
	}

	private static void aConditionOnAnExistingOpenRuleUpdatesIt()
	{
		final List<QualityLlmPass.ExistingProposal> existing = Arrays.asList(new QualityLlmPass.ExistingProposal("PATTERN", "PROPOSED", FailureBreakdownTest.PATTERN, false, ""));
		final QualityLlmPass.Session session = FailureBreakdownTest.session(QualityLlmPass.Mode.SAMPLE_VALUES, FailureBreakdownTest.values(), existing);
		final LocalTool.Result again = session.proposeQualityRule(FailureBreakdownTest.rule("WARN", "PATTERN", null));
		FailureBreakdownTest.check("the same rule again, no condition: already there: " + again.text, !again.isError && again.text.contains("already exists") && session.proposedRules().isEmpty());
		final LocalTool.Result withCondition = session.proposeQualityRule(FailureBreakdownTest.rule("WARN", "PATTERN", "countryCode", "FR"));
		FailureBreakdownTest.check("with a condition it is an update of the open rule: " + withCondition.text, !withCondition.isError && session.proposedRules().size() == 1
			&& session.proposedRules().get(0).updatesExisting);
		// a rule already carrying a condition, or already decided, is not touched
		final List<QualityLlmPass.ExistingProposal> decided = Arrays.asList(new QualityLlmPass.ExistingProposal("PATTERN", "ACCEPTED", FailureBreakdownTest.PATTERN, false, ""));
		final QualityLlmPass.Session accepted = FailureBreakdownTest.session(QualityLlmPass.Mode.SAMPLE_VALUES, FailureBreakdownTest.values(), decided);
		final LocalTool.Result tooLate = accepted.proposeQualityRule(FailureBreakdownTest.rule("WARN", "PATTERN", "countryCode", "FR"));
		FailureBreakdownTest.check("an accepted rule is left as decided: " + tooLate.text, tooLate.text.contains("already exists") && accepted.proposedRules().isEmpty());
	}

	private static void existingRuleIsShownWithItsBreakdown()
	{
		final FailureBreakdown.Result result = FailureBreakdown.best(FailureBreakdownTest.joints(), FailureBreakdownTest.patternJudge(), FailureBreakdownTest.REAL);
		final String text = FailureBreakdown.promptText(result, true);
		final QualityLlmPass.ColumnInfo column = new QualityLlmPass.ColumnInfo("Adresse", "postalCode", "PLZ", "STRING", Integer.valueOf(10), Long.valueOf(335), Double.valueOf(100.0),
			Long.valueOf(12), Arrays.asList(new QualityLlmPass.ShapeCount("99999", 287L)), Collections.<String> emptyList(), Collections.<QualityLlmPass.ExistingProposal> emptyList(),
			Arrays.asList(new QualityLlmPass.ExistingProposal("PATTERN", "PROPOSED", FailureBreakdownTest.PATTERN, false, text)));
		final String prompt = QualityLlmPass.userPrompt(new QualityLlmPass.ProjectInfo("P", "Project", QualityLlmPass.Mode.SAMPLE_VALUES, 20, Arrays.asList(column),
			Collections.<QualityLlmPass.ReferenceBindingInfo> emptyList()));
		FailureBreakdownTest.check("the open rule is listed with its argument, 'no condition yet' and where it fails", prompt.contains("PATTERN(PROPOSED)") && prompt.contains("no condition yet")
			&& prompt.contains("countryCode=CH: 13/13 fail"));
		final String system = QualityLlmPass.systemPrompt();
		FailureBreakdownTest.check("the system prompt tells the model what to do with a breakdown", system.contains("conditionAttribute") && system.contains("never a warning that fires on correct data"));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
