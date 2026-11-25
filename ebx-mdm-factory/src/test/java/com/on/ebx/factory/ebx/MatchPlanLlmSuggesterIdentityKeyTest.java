package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.master.MatchPlanLlmPass;
import com.on.ebx.factory.staging.StagingModel;

/**
 * JUnit-free, like every other test in this module: run the main, a failure throws. Covers {@link
 * MatchPlanLlmSuggester#chooseIdentityKeyField} - the pure precedence behind {@link
 * MatchPlanLlmSuggester#entityIdentityKeyField} - and the corresponding {@code userPrompt} sentence in {@link
 * MatchPlanLlmPass}.
 * <p>
 * <b>Coordinator fix, 2026-09-22 21:36 (agent run 29, project Companies):</b> {@code entityIdentityKeyField}
 * used to read the entity's identity key from its prior {@code MatchPlan.requirements.clusteringFields[0]} - on
 * {@code Company} that is {@code country}, a BLOCKING field, never an identity signal. Grouped by {@code
 * country}, {@code CrossSystemMeasures.divergingGroupsCount} answered a huge number for {@code name} (many
 * {@code nameKey} values share one country) and exactly 0 for {@code country} (a field never disagrees with its
 * own grouping key) - the model then wrote, verbatim, that {@code name} needed "most frequent" survivorship "due
 * to the high genuine divergence after folding" and {@code country} needed "most trusted source" because it
 * showed "no divergence": both rationales were artefacts of the wrong grouping key. The real numbers (grouping
 * by {@code nameKey}, the entity's own matching key, 33 groups shared across ERP/CRM): {@code cityKey} diverges
 * in 0 of 33 groups, raw {@code city} spelling differs in 32 of 33 (cosmetic only). Same package as {@link
 * MatchPlanLlmSuggester} on purpose, to reach its package-private static directly - the same convention {@link
 * MatchPlanLlmSuggesterFilterTest} already follows.
 */
public final class MatchPlanLlmSuggesterIdentityKeyTest
{
	/**
	 * A trimmed-down but faithful reproduction of run 29's Company plan: fuzzy anchor {@code name} (derived key
	 * {@code nameKey}), exact supporting field {@code country}, and - the bug - {@code country} listed as
	 * {@code clusteringFields[0]}.
	 */
	private static final String RUN29_COMPANY_REQUIREMENTS = "{"
		+ "\"entity\":\"Company\","
		+ "\"root\":\"N1\","
		+ "\"nodes\":[{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":85,"
		+ "\"fields\":[{\"field\":\"name\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":3},"
		+ "{\"field\":\"country\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":1,\"minimumScore\":100}],"
		+ "\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],"
		+ "\"clusteringFields\":[\"country\"],"
		+ "\"rationale\":\"Fuzzy match on name, exact on country.\",\"confidence\":70}";

	public static void main(final String[] pArgs)
	{
		MatchPlanLlmSuggesterIdentityKeyTest.run29Regression_anchorsDerivedKeyChosenOverClusteringField();
		MatchPlanLlmSuggesterIdentityKeyTest.neverFallsBackToClusteringFieldWhenNoAnchorKeyOrDerivedAttribute();
		MatchPlanLlmSuggesterIdentityKeyTest.fallbackToFirstAttributeWithADerivedKeyWhenNoFuzzyAnchor();
		MatchPlanLlmSuggesterIdentityKeyTest.fallbackToPriorExactSingleFieldIdentifier();
		MatchPlanLlmSuggesterIdentityKeyTest.exactIdentifierFallbackNeverUsedForAMultiFieldRoot();
		MatchPlanLlmSuggesterIdentityKeyTest.anchorSearchSkipsReferenceAndMatchKeyFields();
		MatchPlanLlmSuggesterIdentityKeyTest.contactWithNoPlanAndNoDerivedKeyIsNull();
		MatchPlanLlmSuggesterIdentityKeyTest.blankAndUnparsableRequirementsDegradeGracefully();
		MatchPlanLlmSuggesterIdentityKeyTest.promptSentenceNamesGroupingKeyPopulationAndAttribute();
		MatchPlanLlmSuggesterIdentityKeyTest.promptSentenceForNoIdentityKeyIsExplicit();
		System.out.println("MatchPlanLlmSuggesterIdentityKeyTest OK");
	}

	// --------------------------------------------------------------------------------------------------- fixtures

	private static StagingModel.AttributeInfo attribute(final String pName, final String pReferenceEntity)
	{
		return new StagingModel.AttributeInfo(pName, pName, "STRING", 80, false, false, pReferenceEntity, "ACCEPTED");
	}

	// -------------------------------------------------------------------------------------------- (1) anchor's key

	/** The exact scenario that exposed the bug: the fuzzy anchor's own derived key must win, never {@code clusteringFields[0]}. */
	private static void run29Regression_anchorsDerivedKeyChosenOverClusteringField()
	{
		final List<StagingModel.AttributeInfo> accepted = Arrays.asList(MatchPlanLlmSuggesterIdentityKeyTest.attribute("name", null),
			MatchPlanLlmSuggesterIdentityKeyTest.attribute("country", null));
		final Map<String, String> derivedFromByName = new HashMap<String, String>();
		derivedFromByName.put("nameKey", "name");
		final Map<String, String> matchKeyByName = new HashMap<String, String>();
		matchKeyByName.put("name", "nameKey");

		final String key = MatchPlanLlmSuggester.chooseIdentityKeyField(MatchPlanLlmSuggesterIdentityKeyTest.RUN29_COMPANY_REQUIREMENTS, accepted,
			derivedFromByName, matchKeyByName);
		MatchPlanLlmSuggesterIdentityKeyTest.check("run 29's Company plan resolves to the anchor's own derived key 'nameKey', never 'country'",
			"nameKey".equals(key));
	}

	// ------------------------------------------------------------------------------------- (2) never a clustering field

	/** Even with a matchable clusteringFields entry present, no derived key anywhere and no exact single-field root means "not measured", never a clustering field. */
	private static void neverFallsBackToClusteringFieldWhenNoAnchorKeyOrDerivedAttribute()
	{
		final List<StagingModel.AttributeInfo> accepted = Arrays.asList(MatchPlanLlmSuggesterIdentityKeyTest.attribute("name", null),
			MatchPlanLlmSuggesterIdentityKeyTest.attribute("country", null));
		// no attribute of this entity has a derived key at all
		final Map<String, String> derivedFromByName = Collections.emptyMap();
		final Map<String, String> matchKeyByName = Collections.emptyMap();

		final String key = MatchPlanLlmSuggesterIdentityKeyTest.chooseWithRun29Requirements(accepted, derivedFromByName, matchKeyByName);
		MatchPlanLlmSuggesterIdentityKeyTest.check("no identity key resolvable - and definitely not 'country', the clustering field", key == null);
	}

	private static String chooseWithRun29Requirements(final List<StagingModel.AttributeInfo> pAccepted, final Map<String, String> pDerivedFromByName,
		final Map<String, String> pMatchKeyByName)
	{
		return MatchPlanLlmSuggester.chooseIdentityKeyField(MatchPlanLlmSuggesterIdentityKeyTest.RUN29_COMPANY_REQUIREMENTS, pAccepted, pDerivedFromByName,
			pMatchKeyByName);
	}

	// --------------------------------------------------------------------------------------- (3) fallback order: b

	/** No fuzzy field anywhere in the tree (both fields exact) - falls back to the first accepted attribute (staging order) that has a derived key, even one never mentioned in the plan. */
	private static void fallbackToFirstAttributeWithADerivedKeyWhenNoFuzzyAnchor()
	{
		final String requirements = "{\"entity\":\"Company\",\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\","
			+ "\"fields\":[{\"field\":\"registrationNumber\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100},"
			+ "{\"field\":\"country\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}],"
			+ "\"clusteringFields\":[\"country\"],\"rationale\":\"r\",\"confidence\":80}";
		final List<StagingModel.AttributeInfo> accepted = Arrays.asList(MatchPlanLlmSuggesterIdentityKeyTest.attribute("registrationNumber", null),
			MatchPlanLlmSuggesterIdentityKeyTest.attribute("country", null), MatchPlanLlmSuggesterIdentityKeyTest.attribute("city", null));
		final Map<String, String> derivedFromByName = new HashMap<String, String>();
		derivedFromByName.put("cityKey", "city");
		final Map<String, String> matchKeyByName = new HashMap<String, String>();
		matchKeyByName.put("city", "cityKey");

		final String key = MatchPlanLlmSuggester.chooseIdentityKeyField(requirements, accepted, derivedFromByName, matchKeyByName);
		MatchPlanLlmSuggesterIdentityKeyTest.check("falls back to the only attribute with a derived key ('city' -> 'cityKey'), never the exact root fields",
			"cityKey".equals(key));
	}

	// --------------------------------------------------------------------------------------- (4) fallback order: c

	/** No derived key anywhere on the entity, but the prior plan is a real, validated single-field EXACT identifier - use that field itself. */
	private static void fallbackToPriorExactSingleFieldIdentifier()
	{
		final String requirements = "{\"entity\":\"Company\",\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\","
			+ "\"fields\":[{\"field\":\"registrationNumber\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
			+ "\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}],\"clusteringFields\":[],\"rationale\":\"r\",\"confidence\":90}";
		final List<StagingModel.AttributeInfo> accepted = Collections.singletonList(MatchPlanLlmSuggesterIdentityKeyTest.attribute("registrationNumber", null));

		final String key = MatchPlanLlmSuggester.chooseIdentityKeyField(requirements, accepted, Collections.<String, String>emptyMap(),
			Collections.<String, String>emptyMap());
		MatchPlanLlmSuggesterIdentityKeyTest.check("falls back to the prior plan's own single-field exact identifier", "registrationNumber".equals(key));
	}

	/** The exact-identifier fallback is narrow on purpose: a multi-field ALL_FIELDS root is not "one clean identifier", even if every field is exact. */
	private static void exactIdentifierFallbackNeverUsedForAMultiFieldRoot()
	{
		final String requirements = "{\"entity\":\"Company\",\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\","
			+ "\"fields\":[{\"field\":\"registrationNumber\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100},"
			+ "{\"field\":\"vatNumber\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}],"
			+ "\"clusteringFields\":[],\"rationale\":\"r\",\"confidence\":90}";
		final List<StagingModel.AttributeInfo> accepted = Arrays.asList(MatchPlanLlmSuggesterIdentityKeyTest.attribute("registrationNumber", null),
			MatchPlanLlmSuggesterIdentityKeyTest.attribute("vatNumber", null));

		final String key = MatchPlanLlmSuggester.chooseIdentityKeyField(requirements, accepted, Collections.<String, String>emptyMap(),
			Collections.<String, String>emptyMap());
		MatchPlanLlmSuggesterIdentityKeyTest.check("a two-field exact root is never treated as a single-field identifier", key == null);
	}

	// -------------------------------------------------------------------------------- (5) anchor search skips roles

	/** The anchor search must skip a REFERENCE field (a foreign key is never this entity's own identity) and a field that is itself a derived key (comparing a key against itself proves nothing), even if both are compared with a fuzzy algorithm ahead of the real anchor. */
	private static void anchorSearchSkipsReferenceAndMatchKeyFields()
	{
		final String requirements = "{\"entity\":\"Contact\",\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":80,"
			+ "\"fields\":["
			+ "{\"field\":\"accountId\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":1},"
			+ "{\"field\":\"nameKey\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":1},"
			+ "{\"field\":\"name\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":3}"
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],\"clusteringFields\":[],\"rationale\":\"r\",\"confidence\":60}";
		final List<StagingModel.AttributeInfo> accepted = Arrays.asList(MatchPlanLlmSuggesterIdentityKeyTest.attribute("accountId", "Account"),
			MatchPlanLlmSuggesterIdentityKeyTest.attribute("nameKey", null), MatchPlanLlmSuggesterIdentityKeyTest.attribute("name", null));
		final Map<String, String> derivedFromByName = new HashMap<String, String>();
		derivedFromByName.put("nameKey", "name");
		final Map<String, String> matchKeyByName = new HashMap<String, String>();
		matchKeyByName.put("name", "nameKey");

		final String key = MatchPlanLlmSuggester.chooseIdentityKeyField(requirements, accepted, derivedFromByName, matchKeyByName);
		MatchPlanLlmSuggesterIdentityKeyTest.check("skips the REFERENCE field and the derived key field itself, picks the real anchor 'name' -> 'nameKey'",
			"nameKey".equals(key));
	}

	// ------------------------------------------------------------------------------------------ (6) Contact -> null

	/** No prior plan at all, no derived key on any attribute - the ordinary case for a raw-identity entity like Contact (firstName/lastName, never folded): "not measured", never a fabricated key. */
	private static void contactWithNoPlanAndNoDerivedKeyIsNull()
	{
		final List<StagingModel.AttributeInfo> accepted = Arrays.asList(MatchPlanLlmSuggesterIdentityKeyTest.attribute("firstName", null),
			MatchPlanLlmSuggesterIdentityKeyTest.attribute("lastName", null));
		final String key = MatchPlanLlmSuggester.chooseIdentityKeyField(null, accepted, Collections.<String, String>emptyMap(),
			Collections.<String, String>emptyMap());
		MatchPlanLlmSuggesterIdentityKeyTest.check("Contact (raw firstName/lastName, no prior plan, no derived key) has no identity key yet", key == null);
	}

	private static void blankAndUnparsableRequirementsDegradeGracefully()
	{
		final List<StagingModel.AttributeInfo> accepted = Collections.singletonList(MatchPlanLlmSuggesterIdentityKeyTest.attribute("firstName", null));
		MatchPlanLlmSuggesterIdentityKeyTest.check("blank requirements degrade to null",
			MatchPlanLlmSuggester.chooseIdentityKeyField("", accepted, Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap()) == null);
		MatchPlanLlmSuggesterIdentityKeyTest.check("unparsable requirements degrade to null, never throw",
			MatchPlanLlmSuggester.chooseIdentityKeyField("{not json", accepted, Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap())
				== null);
	}

	// -------------------------------------------------------------------------------------------- prompt sentence

	/**
	 * The {@code userPrompt} sentence must name, in order: the grouping key, the population it produced, and the
	 * attribute actually being compared - so the model cannot mistake a blocking-key artefact for real
	 * divergence again (the whole point of run 29's fix). Uses the live numbers themselves (city/nameKey,
	 * 0 of 33 diverging, 32 of 33 cosmetic).
	 */
	private static void promptSentenceNamesGroupingKeyPopulationAndAttribute()
	{
		final MatchPlanLlmPass.AttributeInfo city = new MatchPlanLlmPass.AttributeInfo("city", "City", "STRING", 80, null, Double.valueOf(.98), "EXACT",
			Integer.valueOf(30), "EXACT", null, null, null, null, true, MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS, Collections.<String>emptyList(),
			Collections.<String>emptyList(), null, "cityKey", null, Integer.valueOf(33), Integer.valueOf(32), Integer.valueOf(0));
		final MatchPlanLlmPass.EntityInfo company = new MatchPlanLlmPass.EntityInfo("Company", "Company", 106, Collections.singletonList(city), null, null,
			null, "nameKey", Integer.valueOf(33));
		final MatchPlanLlmPass.ProjectInfo project = new MatchPlanLlmPass.ProjectInfo("Companies", "Companies", Collections.singletonList(company),
			Collections.<String>emptyList(), null, false);

		final String prompt = MatchPlanLlmPass.userPrompt(project);
		MatchPlanLlmSuggesterIdentityKeyTest.check(
			"names the grouping key, the shared population and the attribute being compared, unambiguously",
			prompt.contains("among the 33 group(s) of records sharing the same 'nameKey' across systems, the folded 'city' value differs in 0 of them"));
		MatchPlanLlmSuggesterIdentityKeyTest.check("still reports the separate cosmetic-variation number, never conflated with genuine divergence",
			prompt.contains("cosmetic spelling variants (same folded value, different raw spelling) among values sharing its own matching key across systems: 32 of 33"));
	}

	/** When the entity has no identity key at all, the prompt must say so explicitly rather than showing a fabricated/degenerate number - and must never mention a clustering field as the reason (there is none to blame any more: the rule never reads one). */
	private static void promptSentenceForNoIdentityKeyIsExplicit()
	{
		final MatchPlanLlmPass.AttributeInfo notes = new MatchPlanLlmPass.AttributeInfo("notes", "Notes", "STRING", 500, null, Double.valueOf(.5), "EXACT",
			Integer.valueOf(10), "EXACT", null, null, null, null, true, MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS, Collections.<String>emptyList(),
			Collections.<String>emptyList(), null, "notesKey", null, Integer.valueOf(5), null, null);
		final MatchPlanLlmPass.EntityInfo contact = new MatchPlanLlmPass.EntityInfo("Contact", "Contact", 50, Collections.singletonList(notes), null, null,
			null, null, null);
		final MatchPlanLlmPass.ProjectInfo project = new MatchPlanLlmPass.ProjectInfo("Companies", "Companies", Collections.singletonList(contact),
			Collections.<String>emptyList(), null, false);

		final String prompt = MatchPlanLlmPass.userPrompt(project);
		MatchPlanLlmSuggesterIdentityKeyTest.check("says explicitly that genuine divergence was not measured, with an honest reason",
			prompt.contains("genuine divergence after folding: not measured - this entity has no identity key yet (no fuzzy matching key, no attribute "
				+ "with a derived key, and no prior exact single-field identifier)"));
		MatchPlanLlmSuggesterIdentityKeyTest.check("never blames a missing clustering field any more - the rule never reads one",
			!prompt.contains("no prior clustering field"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
