package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like the sibling {@code master} tests: run the main, a failure throws.
 * <p>
 * Covers {@link TrustedSourceRanking#order} (ascending {@code trustRank}, ties broken alphabetically by
 * {@code code}, a missing rank defaulting to {@link TrustedSourceRanking#DEFAULT_TRUST_RANK}) and {@link
 * TrustedSourceRanking#buildTrustedSourcesPayload} ({@code set_trusted_sources}'s own argument shape).
 */
public final class TrustedSourceRankingTest
{
	public static void main(final String[] pArgs)
	{
		TrustedSourceRankingTest.lowerRankWins();
		TrustedSourceRankingTest.tieBrokenAlphabeticallyByCode();
		TrustedSourceRankingTest.missingRankDefaultsTo100();
		TrustedSourceRankingTest.inputListNeverMutated();
		TrustedSourceRankingTest.payloadShape_indexZeroIsMostTrusted();
		TrustedSourceRankingTest.payloadDescription_fallsBackToCodeWhenBlank();
		TrustedSourceRankingTest.payloadWithFieldTrustedSources_carriesTheOverrides();
		TrustedSourceRankingTest.payloadWithNoFieldTrustedSources_omitsTheKeyEntirely();
		TrustedSourceRankingTest.lotM1_payloadRegistersTheGoldenSourceLast_once();
		TrustedSourceRankingTest.lotM1_registerGoldenSourcePayload_storedRankingPlusTheCode();
		System.out.println("TrustedSourceRankingTest OK");
	}

	/** Survivorship lot (2026-09-22, `docs/MATCHING.md` &sect;15): the 3-arg overload adds {@code fieldTrustedSources} verbatim, alongside the unchanged table-level shape. */
	private static void payloadWithFieldTrustedSources_carriesTheOverrides()
	{
		final List<TrustedSourceRanking.Source> ordered = Arrays.asList(new TrustedSourceRanking.Source("ERP", Integer.valueOf(10), "ERP system"),
			new TrustedSourceRanking.Source("CRM", Integer.valueOf(50), "CRM system"));
		final ArrayNode overrides = new ObjectMapper().createArrayNode();
		overrides.addObject().put("field", "/vatNo").putArray("trustedSourceList").add("ERP").add("CRM");
		final ObjectNode payload = TrustedSourceRanking.buildTrustedSourcesPayload("/sourceSystem", ordered, overrides);
		TrustedSourceRankingTest.check("table-level shape unchanged", "ERP".equals(payload.path("tableTrustedSourceList").get(0).asText()));
		TrustedSourceRankingTest.check("fieldTrustedSources carried over verbatim",
			"/vatNo".equals(payload.path("fieldTrustedSources").get(0).path("field").asText()));
	}

	/** Both the 2-arg overload and a null/empty 3rd argument must omit {@code fieldTrustedSources} entirely - a project with no override sends exactly the pre-lot payload. */
	private static void payloadWithNoFieldTrustedSources_omitsTheKeyEntirely()
	{
		final List<TrustedSourceRanking.Source> ordered = Arrays.asList(new TrustedSourceRanking.Source("ERP", Integer.valueOf(10), null));
		TrustedSourceRankingTest.check("2-arg overload never carries the key", !TrustedSourceRanking.buildTrustedSourcesPayload("/sourceSystem", ordered).has("fieldTrustedSources"));
		TrustedSourceRankingTest.check("null 3rd argument never carries the key",
			!TrustedSourceRanking.buildTrustedSourcesPayload("/sourceSystem", ordered, null).has("fieldTrustedSources"));
		TrustedSourceRankingTest.check("empty 3rd argument never carries the key",
			!TrustedSourceRanking.buildTrustedSourcesPayload("/sourceSystem", ordered, new ObjectMapper().createArrayNode()).has("fieldTrustedSources"));
	}

	/** Lot M1 (2026-10-17): {@code autoCreateNewGoldenSource} names a REGISTERED source; the payload registers it, LAST, and never twice (nor above a real source). */
	private static void lotM1_payloadRegistersTheGoldenSourceLast_once()
	{
		final List<TrustedSourceRanking.Source> ordered = Arrays.asList(new TrustedSourceRanking.Source("ERP", Integer.valueOf(10), null),
			new TrustedSourceRanking.Source("CRM", Integer.valueOf(50), null));
		final ObjectNode payload = TrustedSourceRanking.buildTrustedSourcesPayload("/sourceSystem", ordered);
		TrustedSourceRankingTest.check("the list ends with the golden source: [ERP, CRM, MDM]",
			payload.path("tableTrustedSourceList").size() == 3 && "MDM".equals(payload.path("tableTrustedSourceList").get(2).asText())
				&& "ERP".equals(payload.path("tableTrustedSourceList").get(0).asText()));
		final List<TrustedSourceRanking.Source> withOwn = Arrays.asList(new TrustedSourceRanking.Source("MDM", Integer.valueOf(1), null),
			new TrustedSourceRanking.Source("ERP", Integer.valueOf(10), null));
		final ObjectNode second = TrustedSourceRanking.buildTrustedSourcesPayload("/sourceSystem", withOwn);
		TrustedSourceRankingTest.check("a project that already has a source called MDM is not given a second entry", second.path("tableTrustedSourceList").size() == 2);
		TrustedSourceRankingTest.check("the golden source is exactly what the merge policy names",
			com.on.ebx.factory.master.MatchingPolicyTranslator.GOLDEN_SOURCE_CODE.equals("MDM"));
	}

	/** Lot M1: a REWRITE does not write the ranking - it only appends the golden source to the STORED one, and only when it is not registered yet. */
	private static void lotM1_registerGoldenSourcePayload_storedRankingPlusTheCode()
	{
		try
		{
			final ObjectMapper mapper = new ObjectMapper();
			final ObjectNode payload = TrustedSourceRanking.registerGoldenSourcePayload(
				mapper.readTree("{\"sourceField\":\"/sourceSystem\",\"tableTrustedSourceList\":[\"LOYALTY\",\"STORES\",\"ECOM\"],\"sources\":[{\"code\":\"ECOM\"}],\"fieldTrustedSources\":[{\"field\":\"/x\"}]}"),
				"/fallback");
			TrustedSourceRankingTest.check("stored ranking kept in order, the golden source appended",
				payload != null && payload.path("tableTrustedSourceList").size() == 4 && "LOYALTY".equals(payload.path("tableTrustedSourceList").get(0).asText())
					&& "MDM".equals(payload.path("tableTrustedSourceList").get(3).asText()));
			TrustedSourceRankingTest.check("the stored source field is kept; nothing else is sent (no sources, no fieldTrustedSources)",
				"/sourceSystem".equals(payload.path("sourceField").asText()) && !payload.has("sources") && !payload.has("fieldTrustedSources"));
			TrustedSourceRankingTest.check("no stored source field: the default one is sent",
				"/fallback".equals(TrustedSourceRanking.registerGoldenSourcePayload(mapper.readTree("{}"), "/fallback").path("sourceField").asText()));
			TrustedSourceRankingTest.check("already in the stored ranking: nothing to call",
				TrustedSourceRanking.registerGoldenSourcePayload(mapper.readTree("{\"tableTrustedSourceList\":[\"ECOM\",\"MDM\"]}"), "/s") == null);
			TrustedSourceRankingTest.check("already in the instance catalogue (another project registered it): nothing to call",
				TrustedSourceRanking.registerGoldenSourcePayload(mapper.readTree("{\"tableTrustedSourceList\":[\"ECOM\"],\"sources\":[{\"code\":\"MDM\"}]}"), "/s") == null);
		}
		catch (final java.io.IOException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	private static void lowerRankWins()
	{
		final List<TrustedSourceRanking.Source> sources = Arrays.asList(new TrustedSourceRanking.Source("CRM", Integer.valueOf(50), "CRM system"),
			new TrustedSourceRanking.Source("ERP", Integer.valueOf(10), "ERP system"));
		final List<TrustedSourceRanking.Source> ordered = TrustedSourceRanking.order(sources);
		TrustedSourceRankingTest.check("ERP (rank 10) comes before CRM (rank 50)", "ERP".equals(ordered.get(0).code) && "CRM".equals(ordered.get(1).code));
	}

	private static void tieBrokenAlphabeticallyByCode()
	{
		final List<TrustedSourceRanking.Source> sources = Arrays.asList(new TrustedSourceRanking.Source("ZETA", Integer.valueOf(50), null),
			new TrustedSourceRanking.Source("ALPHA", Integer.valueOf(50), null));
		final List<TrustedSourceRanking.Source> ordered = TrustedSourceRanking.order(sources);
		TrustedSourceRankingTest.check("same rank: ALPHA before ZETA, alphabetically", "ALPHA".equals(ordered.get(0).code) && "ZETA".equals(ordered.get(1).code));
	}

	private static void missingRankDefaultsTo100()
	{
		final List<TrustedSourceRanking.Source> sources = Arrays.asList(new TrustedSourceRanking.Source("HIGH_TRUST", Integer.valueOf(1), null),
			new TrustedSourceRanking.Source("NO_RANK", null, null));
		final List<TrustedSourceRanking.Source> ordered = TrustedSourceRanking.order(sources);
		TrustedSourceRankingTest.check("an explicit low rank beats an unranked (default 100) source",
			"HIGH_TRUST".equals(ordered.get(0).code) && "NO_RANK".equals(ordered.get(1).code));
		TrustedSourceRankingTest.check("DEFAULT_TRUST_RANK is 100, matching ProjectSource.trustRank's own XSD default",
			TrustedSourceRanking.DEFAULT_TRUST_RANK == 100);
	}

	private static void inputListNeverMutated()
	{
		final List<TrustedSourceRanking.Source> sources = new ArrayList<TrustedSourceRanking.Source>(
			Arrays.asList(new TrustedSourceRanking.Source("B", Integer.valueOf(2), null), new TrustedSourceRanking.Source("A", Integer.valueOf(1), null)));
		TrustedSourceRanking.order(sources);
		TrustedSourceRankingTest.check("the caller's own list keeps its original order", "B".equals(sources.get(0).code) && "A".equals(sources.get(1).code));
	}

	private static void payloadShape_indexZeroIsMostTrusted()
	{
		final List<TrustedSourceRanking.Source> ordered = Arrays.asList(new TrustedSourceRanking.Source("ERP", Integer.valueOf(10), "ERP system"),
			new TrustedSourceRanking.Source("CRM", Integer.valueOf(50), "CRM system"));
		final ObjectNode payload = TrustedSourceRanking.buildTrustedSourcesPayload("/sourceSystem", ordered);
		TrustedSourceRankingTest.check("sourceField carries the leading-slash field path", "/sourceSystem".equals(payload.path("sourceField").asText()));
		TrustedSourceRankingTest.check("sources[0] is ERP with its description",
			// hotfix 2026-09-22: the instance-wide "sources" catalogue must never be sent (it would replace every other project's sources)
			!payload.has("sources"));
		TrustedSourceRankingTest.check("tableTrustedSourceList is [ERP, CRM] in that order",
			"ERP".equals(payload.path("tableTrustedSourceList").get(0).asText()) && "CRM".equals(payload.path("tableTrustedSourceList").get(1).asText()));
	}

	private static void payloadDescription_fallsBackToCodeWhenBlank()
	{
		final TrustedSourceRanking.Source noLabel = new TrustedSourceRanking.Source("ERP", null, "  ");
		TrustedSourceRankingTest.check("a blank description falls back to the code itself", "ERP".equals(noLabel.description));
		final TrustedSourceRanking.Source nullLabel = new TrustedSourceRanking.Source("ERP", null, null);
		TrustedSourceRankingTest.check("a null description also falls back to the code", "ERP".equals(nullLabel.description));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
