package com.on.ebx.factory.matching;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Covers {@link CandidateFilterCheck} (lot M8a, {@code docs/MATCHING.md}
 * &sect;51): the add-on's rule for deriving candidate filters from a decision tree, on the measured "E3" tree of the 1 M-row test and on the shapes the
 * measurements showed fail (variant D, the weighted-average name path, a relation-field path).
 */
public final class CandidateFilterCheckTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		CandidateFilterCheckTest.e3IsFilteredWithThreeKeySets();
		CandidateFilterCheckTest.theOriginalTreeIsNotFiltered();
		CandidateFilterCheckTest.nullHandlingMustBeNotMatchOnBothSides();
		CandidateFilterCheckTest.algorithmMustBeExactOrExactFullText();
		CandidateFilterCheckTest.minimumScoreMustBeAboveZero_absentMeansTheDefault();
		CandidateFilterCheckTest.aRelationFieldNeverCounts();
		CandidateFilterCheckTest.aNodeLeftThroughOnFalseGivesNoKey();
		CandidateFilterCheckTest.severalKeysInOneNodeFormOneSet();
		CandidateFilterCheckTest.legacyTiersAreRead();
		CandidateFilterCheckTest.noTreeOrNoMatchPathIsNotApplicable();
		CandidateFilterCheckTest.warningSaysWhichOfTheTwoProblemsApplies();
		CandidateFilterCheckTest.jvmOptionIsReadAsTrueOnly();
		System.out.println("CandidateFilterCheckTest OK");
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	private static JsonNode json(final String pJson)
	{
		try
		{
			return CandidateFilterCheckTest.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			throw new AssertionError("bad fixture: " + ex);
		}
	}

	/** One field object of the stored plan shape ({@code field, algorithm, minimumScore, bothNull, oneNull}). */
	private static String key(final String pField, final String pAlgorithm, final int pMinimumScore)
	{
		return "{\"field\":\"" + pField + "\",\"algorithm\":\"" + pAlgorithm + "\",\"minimumScore\":" + pMinimumScore + ",\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}";
	}

	private static String similar(final String pField, final int pWeight)
	{
		return "{\"field\":\"" + pField + "\",\"algorithm\":\"ebx-addon-mame@jaroWinkler\",\"weight\":" + pWeight + ",\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}";
	}

	private static String relation(final String pField)
	{
		return "{\"field\":\"" + pField + "\",\"via\":\"Adresse\",\"dataType\":\"STRING\",\"algorithm\":\"ebx-addon-mame@jaroWinkler\",\"weight\":1,\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"DO_NOTHING\"}";
	}

	/**
	 * The measured "E3" plan (project Vol1m, 2026-10-04: 8 min 21, recall 99.15 %, precision 99.84 %), as {@code MatchPlan.requirements}: P1 e-mail key -&gt;
	 * SUSPECTED; else P2 phone key -&gt; P2B names &gt;= 82 -&gt; P3 the four address fields &gt;= 76 -&gt; SUSPECTED; else P5 address keys (exactFullText, 20) -&gt; P5B names
	 * &gt;= 82 -&gt; SUSPECTED. Public: the translator test reuses it.
	 */
	public static ObjectNode e3Requirements()
	{
		final String tree = "{\"root\":\"P1\",\"nodes\":["
			+ "{\"id\":\"P1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + CandidateFilterCheckTest.key("emailKey", "ebx-addon-mame@exact", 100) + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"P2\"},"
			+ "{\"id\":\"P2\",\"function\":\"ALL_FIELDS\",\"fields\":[" + CandidateFilterCheckTest.key("phoneE164", "ebx-addon-mame@exact", 100) + "],\"onTrue\":\"P2B\",\"onFalse\":\"P5\"},"
			+ "{\"id\":\"P2B\",\"function\":\"AVERAGE\",\"minimumScore\":82,\"fields\":[" + CandidateFilterCheckTest.similar("lastName", 5) + ","
			+ CandidateFilterCheckTest.similar("firstName", 4) + "],\"onTrue\":\"P3\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"P3\",\"function\":\"AVERAGE\",\"minimumScore\":76,\"fields\":[" + CandidateFilterCheckTest.relation("street") + "," + CandidateFilterCheckTest.relation("houseNumber")
			+ "," + CandidateFilterCheckTest.relation("postalCode") + "," + CandidateFilterCheckTest.relation("city") + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"P5\",\"function\":\"ALL_FIELDS\",\"fields\":[" + CandidateFilterCheckTest.key("adresseKeys", "ebx-addon-mame@exactFullText", 20)
			+ "],\"onTrue\":\"P5B\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"P5B\",\"function\":\"AVERAGE\",\"minimumScore\":82,\"fields\":[" + CandidateFilterCheckTest.similar("lastName", 5) + "," + CandidateFilterCheckTest.similar("firstName", 4)
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}";
		final ObjectNode requirements = CandidateFilterCheckTest.MAPPER.createObjectNode();
		requirements.set("decisionTree", CandidateFilterCheckTest.json(tree));
		requirements.putArray("clusteringFields").add("emailKey").add("phoneE164").add("adresseKeys").add("lastNameKey");
		return requirements;
	}

	/** A one-node tree: {@code pField} alone in an ALL_FIELDS node routed to SUSPECTED. */
	private static JsonNode single(final String pFieldJson)
	{
		return CandidateFilterCheckTest.json("{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + pFieldJson
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}");
	}

	// ------------------------------------------------------------------------------------------------------- tests

	private static void e3IsFilteredWithThreeKeySets()
	{
		final CandidateFilterCheck.Result result = CandidateFilterCheck.check(CandidateFilterCheckTest.e3Requirements());
		CandidateFilterCheckTest.check("E3 is FILTERED", result.filtered() && CandidateFilterCheck.VERDICT_FILTERED.equals(result.verdict));
		CandidateFilterCheckTest.check("three paths reach a suspect: " + result.paths.size(), result.paths.size() == 3);
		CandidateFilterCheckTest.check("the key sets are {emailKey}, {phoneE164}, {adresseKeys}: " + result.keySets(),
			result.keySets().equals(Arrays.asList(Arrays.asList("emailKey"), Arrays.asList("phoneE164"), Arrays.asList("adresseKeys"))));
		CandidateFilterCheckTest.check("phrase", "emailKey OR phoneE164 OR adresseKeys".equals(result.filtersPhrase()));
		CandidateFilterCheckTest.check("the long path is P1 > P2 > P2B > P3: " + result.paths.get(1).label(), "P1 > P2 > P2B > P3".equals(result.paths.get(1).label()));
		CandidateFilterCheckTest.check("no rejection on a path that has a key", result.paths.get(1).rejections.isEmpty());
		CandidateFilterCheckTest.check("filtered explanation names the filters", result.explain().contains("emailKey OR phoneE164 OR adresseKeys"));
	}

	private static void theOriginalTreeIsNotFiltered()
	{
		// the tree of the reference run: names by AVERAGE, addresses by relation fields - no path has a key (variant C: "no set deduced")
		final JsonNode tree = CandidateFilterCheckTest.json("{\"root\":\"N1\",\"nodes\":["
			+ "{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":82,\"fields\":[" + CandidateFilterCheckTest.similar("lastName", 5) + "," + CandidateFilterCheckTest.similar("firstName", 4)
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"N2\"},"
			+ "{\"id\":\"N2\",\"function\":\"ALL_FIELDS\",\"fields\":[" + CandidateFilterCheckTest.relation("street") + "," + CandidateFilterCheckTest.relation("city")
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}");
		final CandidateFilterCheck.Result result = CandidateFilterCheck.checkTree(tree);
		CandidateFilterCheckTest.check("NOT_FILTERED", result.notFiltered());
		CandidateFilterCheckTest.check("two paths without a key: " + result.pathsWithoutKey().size(), result.pathsWithoutKey().size() == 2);
		final CandidateFilterCheck.PathResult first = result.paths.get(0);
		CandidateFilterCheckTest.check("first path N1: " + first.label(), "N1".equals(first.label()));
		CandidateFilterCheckTest.check("N1 is AVERAGE: " + first.rejections.get(0).code, CandidateFilterCheck.REASON_NOT_ALL_FIELDS.equals(first.rejections.get(0).code));
		final CandidateFilterCheck.PathResult second = result.paths.get(1);
		CandidateFilterCheckTest.check("second path crosses N1 then N2: " + second.label(), "N1 > N2".equals(second.label()));
		CandidateFilterCheckTest.check("N1 (left through onFalse) is not blamed, N2's two relation fields are (relation, null handling, algorithm each): " + second.rejections.size(),
			second.rejections.size() == 6 && "N2".equals(second.rejections.get(0).nodeId) && CandidateFilterCheck.REASON_RELATION_FIELD.equals(second.rejections.get(0).code)
				&& CandidateFilterCheck.REASON_NULL_HANDLING.equals(second.rejections.get(1).code));
		CandidateFilterCheckTest.check("explanation names both paths", result.explain().contains("Path N1 (SUSPECTED)") && result.explain().contains("Path N1 > N2 (SUSPECTED)"));
		CandidateFilterCheckTest.check("explanation says why", result.explain().contains("not an 'all fields match'") && result.explain().contains("related records 'Adresse'"));
	}

	private static void nullHandlingMustBeNotMatchOnBothSides()
	{
		CandidateFilterCheckTest.check("both NOT_MATCH: filtered",
			CandidateFilterCheck.checkTree(CandidateFilterCheckTest.single(CandidateFilterCheckTest.key("emailKey", "ebx-addon-mame@exact", 100))).filtered());
		CandidateFilterCheckTest.check("both absent = NOT_MATCH (the translator's default): filtered",
			CandidateFilterCheck.checkTree(CandidateFilterCheckTest.single("{\"field\":\"emailKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}")).filtered());
		for (final String bad : new String[] { "{\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"NOT_MATCH\"}", "{\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"DO_NOTHING\"}",
			"{\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"DO_NOTHING\"}" })
		{
			final String field = "{\"field\":\"emailKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100," + bad.substring(1);
			final CandidateFilterCheck.Result result = CandidateFilterCheck.checkTree(CandidateFilterCheckTest.single(field));
			CandidateFilterCheckTest.check(bad + " is not filtered", result.notFiltered());
			CandidateFilterCheckTest.check(bad + " is blamed on null handling", CandidateFilterCheck.REASON_NULL_HANDLING.equals(result.paths.get(0).rejections.get(0).code));
		}
		CandidateFilterCheckTest.check("the add-on's long property names are read too", CandidateFilterCheck.checkTree(CandidateFilterCheckTest.single(
			"{\"field\":\"emailKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}")).filtered());
	}

	private static void algorithmMustBeExactOrExactFullText()
	{
		CandidateFilterCheckTest.check("exactFullText: filtered",
			CandidateFilterCheck.checkTree(CandidateFilterCheckTest.single(CandidateFilterCheckTest.key("k", "ebx-addon-mame@exactFullText", 20))).filtered());
		CandidateFilterCheckTest.check("exact in another case: filtered",
			CandidateFilterCheck.checkTree(CandidateFilterCheckTest.single(CandidateFilterCheckTest.key("k", "ebx-addon-mame@EXACT", 100))).filtered());
		final CandidateFilterCheck.Result fuzzy = CandidateFilterCheck.checkTree(CandidateFilterCheckTest.single(CandidateFilterCheckTest.key("k", "ebx-addon-mame@jaroWinkler", 90)));
		CandidateFilterCheckTest.check("jaroWinkler: not filtered", fuzzy.notFiltered());
		CandidateFilterCheckTest.check("jaroWinkler is blamed on the algorithm", CandidateFilterCheck.REASON_ALGORITHM.equals(fuzzy.paths.get(0).rejections.get(0).code));
	}

	private static void minimumScoreMustBeAboveZero_absentMeansTheDefault()
	{
		CandidateFilterCheckTest.check("absent minimumScore = 100: filtered", CandidateFilterCheck.checkTree(CandidateFilterCheckTest.single(
			"{\"field\":\"k\",\"algorithm\":\"ebx-addon-mame@exact\",\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}")).filtered());
		final CandidateFilterCheck.Result zero = CandidateFilterCheck.checkTree(CandidateFilterCheckTest.single(CandidateFilterCheckTest.key("k", "ebx-addon-mame@exactFullText", 0)));
		CandidateFilterCheckTest.check("minimumScore 0: not filtered", zero.notFiltered());
		CandidateFilterCheckTest.check("blamed on the minimum score", CandidateFilterCheck.REASON_MINIMUM_SCORE.equals(zero.paths.get(0).rejections.get(0).code));
	}

	private static void aRelationFieldNeverCounts()
	{
		final CandidateFilterCheck.Result result = CandidateFilterCheck.checkTree(CandidateFilterCheckTest.single(
			"{\"field\":\"postalCode\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}"));
		CandidateFilterCheckTest.check("an exact, NOT_MATCH relation field is still not a key", result.notFiltered());
		CandidateFilterCheckTest.check("blamed on the relation", CandidateFilterCheck.REASON_RELATION_FIELD.equals(result.paths.get(0).rejections.get(0).code));
	}

	private static void aNodeLeftThroughOnFalseGivesNoKey()
	{
		// the only ALL_FIELDS key node sits on the onFalse edge of the path to the suspect: it adds nothing to that path's set
		final JsonNode tree = CandidateFilterCheckTest.json("{\"root\":\"N1\",\"nodes\":["
			+ "{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + CandidateFilterCheckTest.key("emailKey", "ebx-addon-mame@exact", 100) + "],\"onTrue\":\"NOT_MATCHED\",\"onFalse\":\"N2\"},"
			+ "{\"id\":\"N2\",\"function\":\"AVERAGE\",\"minimumScore\":80,\"fields\":[" + CandidateFilterCheckTest.similar("lastName", 1) + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}");
		final CandidateFilterCheck.Result result = CandidateFilterCheck.checkTree(tree);
		CandidateFilterCheckTest.check("not filtered", result.notFiltered());
		CandidateFilterCheckTest.check("one path, N1 > N2", result.paths.size() == 1 && "N1 > N2".equals(result.paths.get(0).label()));
		CandidateFilterCheckTest.check("only N2 is blamed", result.paths.get(0).rejections.size() == 1 && "N2".equals(result.paths.get(0).rejections.get(0).nodeId));

		final JsonNode allFalse = CandidateFilterCheckTest.json("{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":["
			+ CandidateFilterCheckTest.key("emailKey", "ebx-addon-mame@exact", 100) + "],\"onTrue\":\"NOT_MATCHED\",\"onFalse\":\"SUSPECTED\"}]}");
		final CandidateFilterCheck.Result none = CandidateFilterCheck.checkTree(allFalse);
		CandidateFilterCheckTest.check("a path that passes no node is blamed as such",
			none.notFiltered() && CandidateFilterCheck.REASON_NO_PASSED_NODE.equals(none.paths.get(0).rejections.get(0).code));
	}

	private static void severalKeysInOneNodeFormOneSet()
	{
		final CandidateFilterCheck.Result result = CandidateFilterCheck.checkTree(CandidateFilterCheckTest.single(
			CandidateFilterCheckTest.key("emailKey", "ebx-addon-mame@exact", 100) + "," + CandidateFilterCheckTest.key("phoneE164", "ebx-addon-mame@exact", 100)));
		CandidateFilterCheckTest.check("filtered", result.filtered());
		CandidateFilterCheckTest.check("one set of two fields: " + result.keySets(), result.keySets().equals(Arrays.asList(Arrays.asList("emailKey", "phoneE164"))));
		CandidateFilterCheckTest.check("phrase of a set of two: " + result.filtersPhrase(), "(emailKey AND phoneE164)".equals(result.filtersPhrase()));
		// a node with one good key and one fuzzy field: the fuzzy one is blamed nowhere (the path has a key), the set holds the key only
		final CandidateFilterCheck.Result mixed = CandidateFilterCheck.checkTree(CandidateFilterCheckTest.single(
			CandidateFilterCheckTest.key("emailKey", "ebx-addon-mame@exact", 100) + "," + CandidateFilterCheckTest.key("lastName", "ebx-addon-mame@jaroWinkler", 90)));
		CandidateFilterCheckTest.check("mixed node: the set holds the eligible field only", mixed.keySets().equals(Arrays.asList(Arrays.asList("emailKey"))));
	}

	private static void legacyTiersAreRead()
	{
		final ObjectNode requirements = CandidateFilterCheckTest.MAPPER.createObjectNode();
		requirements.set("tiers", CandidateFilterCheckTest.json("[{\"type\":\"EXACT\",\"outcome\":\"SUSPECTED\",\"fields\":[{\"field\":\"gtin\",\"algorithm\":\"ebx-addon-mame@exact\"}]},"
			+ "{\"type\":\"AVERAGE\",\"outcome\":\"SUSPECTED\",\"minimumScore\":85,\"fields\":[{\"field\":\"name\",\"algorithm\":\"ebx-addon-mame@jaroWinkler\",\"weight\":2}]}]"));
		final CandidateFilterCheck.Result result = CandidateFilterCheck.check(requirements);
		CandidateFilterCheckTest.check("the AVERAGE tier has no key: not filtered", result.notFiltered());
		CandidateFilterCheckTest.check("path T1 has the gtin key, path T1 > T2 has none", result.paths.size() == 2 && result.paths.get(0).hasKey() && !result.paths.get(1).hasKey());
	}

	private static void noTreeOrNoMatchPathIsNotApplicable()
	{
		CandidateFilterCheckTest.check("null", CandidateFilterCheck.VERDICT_NOT_APPLICABLE.equals(CandidateFilterCheck.check(null).verdict));
		CandidateFilterCheckTest.check("empty object", CandidateFilterCheck.VERDICT_NOT_APPLICABLE.equals(CandidateFilterCheck.check(CandidateFilterCheckTest.json("{}")).verdict));
		CandidateFilterCheckTest.check("no nodes", CandidateFilterCheck.VERDICT_NOT_APPLICABLE.equals(CandidateFilterCheck.checkTree(CandidateFilterCheckTest.json("{\"root\":\"N1\",\"nodes\":[]}")).verdict));
		final JsonNode neverMatches = CandidateFilterCheckTest.json("{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[],\"onTrue\":\"NOT_MATCHED\",\"onFalse\":\"NOT_MATCHED\"}]}");
		CandidateFilterCheckTest.check("a tree that never reaches a match", CandidateFilterCheck.VERDICT_NOT_APPLICABLE.equals(CandidateFilterCheck.checkTree(neverMatches).verdict));
		final JsonNode cycle = CandidateFilterCheckTest.json("{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[],\"onTrue\":\"N1\",\"onFalse\":\"N1\"}]}");
		CandidateFilterCheckTest.check("a cycle terminates", CandidateFilterCheck.VERDICT_NOT_APPLICABLE.equals(CandidateFilterCheck.checkTree(cycle).verdict));
		CandidateFilterCheckTest.check("explain of NOT_APPLICABLE is empty", CandidateFilterCheck.check(null).explain().isEmpty());
	}

	private static void warningSaysWhichOfTheTwoProblemsApplies()
	{
		final CandidateFilterCheck.Result filtered = CandidateFilterCheck.check(CandidateFilterCheckTest.e3Requirements());
		CandidateFilterCheckTest.check("filtered + option on: nothing to say", CandidateFilterCheck.warning(filtered, true) == null);
		final String off = CandidateFilterCheck.warning(filtered, false);
		CandidateFilterCheckTest.check("filtered + option off: names the option: " + off, off != null && off.contains("candidate filters are OFF on this server")
			&& off.contains("-Debx.addon.mame.matching.mandatoryFieldSets.enabled=true"));
		final CandidateFilterCheck.Result notFiltered = CandidateFilterCheck.checkTree(CandidateFilterCheckTest.single(CandidateFilterCheckTest.key("k", "ebx-addon-mame@jaroWinkler", 90)));
		for (final boolean option : new boolean[] { true, false })
		{
			final String said = CandidateFilterCheck.warning(notFiltered, option);
			CandidateFilterCheckTest.check("not filtered says so whatever the option (" + option + ")", said != null && said.contains("NOT filtered") && !said.contains("OFF on this server"));
		}
		CandidateFilterCheckTest.check("not applicable: nothing to say", CandidateFilterCheck.warning(CandidateFilterCheck.check(null), false) == null);
	}

	private static void jvmOptionIsReadAsTrueOnly()
	{
		CandidateFilterCheckTest.check("true", CandidateFilterCheck.jvmOptionEnabled("true"));
		CandidateFilterCheckTest.check(" TRUE ", CandidateFilterCheck.jvmOptionEnabled(" TRUE "));
		CandidateFilterCheckTest.check("false", !CandidateFilterCheck.jvmOptionEnabled("false"));
		CandidateFilterCheckTest.check("null", !CandidateFilterCheck.jvmOptionEnabled(null));
		CandidateFilterCheckTest.check("anything else", !CandidateFilterCheck.jvmOptionEnabled("1"));
		final List<String> unused = Arrays.asList(CandidateFilterCheck.JVM_OPTION);
		CandidateFilterCheckTest.check("the option's name", "ebx.addon.mame.matching.mandatoryFieldSets.enabled".equals(unused.get(0)));
	}

	private static void check(final String pDescription, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pDescription);
		}
	}
}
