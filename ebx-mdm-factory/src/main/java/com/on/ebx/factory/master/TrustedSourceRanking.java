package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The project's own {@code ProjectSource.trustRank} ("lower wins", default 100 - the field's own XSD
 * default, mirrored here rather than re-guessed), turned into the ordered {@code sources}/{@code
 * tableTrustedSourceList} shape {@code match-merge}'s {@code set_trusted_sources} tool expects (trusted-
 * sources lot, 2026-09-22, `docs/MATCHING.md` "Survivorship and trusted sources").
 * <p>
 * <b>Tie-break, decided here since the wizard never asked the user to break one</b>: two sources with the
 * same {@code trustRank} (including the common case of neither ever having been set, both defaulting to
 * {@value #DEFAULT_TRUST_RANK}) order by their {@code code} alphabetically (plain {@link
 * String#compareTo(String)}, ordinal/case-sensitive - the same comparator {@code
 * WizardData}/{@code RecordLineageReader} already sort project rows with elsewhere in this codebase,
 * nothing more elaborate). This is a real, user-visible choice (two sources of equal trust get a
 * DETERMINISTIC but essentially arbitrary order) rather than a discovered contract - documented here so
 * nobody re-derives a different tie-break later and gets a silently different {@code
 * tableTrustedSourceList} for the exact same input.
 * <p>
 * Pure: no EBX class, Jackson only - unit-tested without a running instance. {@code ebx.MatchingConfigurator}
 * reads the project's {@code ProjectSource} rows (code = {@code sourceSystem}, the SAME identifier every
 * master row's own {@code sourceSystem} field carries - see that class' own Javadoc for how the two are
 * kept in step) and hands them to {@link #order}.
 */
public final class TrustedSourceRanking
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** {@code ProjectSource.trustRank}'s own XSD default - mirrored, not re-read, since this class has no EBX access of its own. */
	public static final int DEFAULT_TRUST_RANK = 100;

	private TrustedSourceRanking()
	{
	}

	/** One {@code ProjectSource} row's worth of trust information - {@code code} is its {@code sourceSystem} (the wire identifier master rows carry, never the catalog source id or a display label - see {@link #order}'s own Javadoc). */
	public static final class Source
	{
		public final String code;
		/** {@code null} reads as {@link #DEFAULT_TRUST_RANK} everywhere in this class - never guessed to be 0 or any other value. */
		public final Integer trustRank;
		/** Human-readable label {@code set_trusted_sources}' own {@code sources[].description} carries; falls back to {@link #code} when the caller has nothing better (see {@code ebx.MatchingConfigurator#readTrustedSources}). */
		public final String description;

		public Source(final String pCode, final Integer pTrustRank, final String pDescription)
		{
			this.code = pCode;
			this.trustRank = pTrustRank;
			this.description = pDescription == null || pDescription.trim().isEmpty() ? pCode : pDescription;
		}

		private int rank()
		{
			return this.trustRank == null ? TrustedSourceRanking.DEFAULT_TRUST_RANK : this.trustRank.intValue();
		}
	}

	/**
	 * @return a NEW list, most trusted first (ascending {@code trustRank}, ties broken alphabetically by
	 *         {@code code} - see class Javadoc). {@code pSources} itself is never mutated.
	 */
	public static List<Source> order(final List<Source> pSources)
	{
		final List<Source> ordered = new ArrayList<Source>(pSources);
		Collections.sort(ordered, new Comparator<Source>()
		{
			@Override
			public int compare(final Source pLeft, final Source pRight)
			{
				final int byRank = Integer.compare(pLeft.rank(), pRight.rank());
				return byRank != 0 ? byRank : pLeft.code.compareTo(pRight.code);
			}
		});
		return ordered;
	}

	/**
	 * {@code set_trusted_sources}'s own {@code trustedSources} argument object: {@code {"sourceField":
	 * pSourceFieldPath, "tableTrustedSourceList": [code, ...]}} - index 0 = most trusted. <b>{@code sources} is
	 * deliberately NOT sent</b> (coordinator hotfix, 2026-09-22 17:40, after a live {@code get_matching_config}
	 * on {@code /root/Company}): that member is the INSTANCE-WIDE source catalogue, not the table's - the live
	 * answer listed the PointOfSale project's {@code ERP_AMER}, {@code ERP_APAC}, {@code ERP_EU}, {@code GROUP_ERP},
	 * {@code MDM}, {@code POS_ENRICH} plus the built-in {@code Data Steward decision} - and the tool's own contract
	 * says a supplied member REPLACES the stored one wholesale, so sending this project's two codes would have
	 * wiped every other project's sources. The same contract says "any referenced code is auto-created if it
	 * doesn't exist", so {@code tableTrustedSourceList} alone is enough; the wizard's source description is
	 * therefore not pushed (the catalogue entry gets the bare code as its description). {@code pOrderedSources} MUST already be in
	 * most-trusted-first order ({@link #order}'s own output) - this method does not sort.
	 *
	 * @param pSourceFieldPath the record-relative, leading-slash field path the master table's own
	 *            {@code sourceSystem} field carries (e.g. {@code /sourceSystem}) - the same convention
	 *            {@link MatchingPolicyTranslator#toFieldPath} uses elsewhere in this package, passed in
	 *            rather than hardcoded here so a future non-standard field name is not silently ignored.
	 */
	public static ObjectNode buildTrustedSourcesPayload(final String pSourceFieldPath, final List<Source> pOrderedSources)
	{
		return TrustedSourceRanking.buildTrustedSourcesPayload(pSourceFieldPath, pOrderedSources, null);
	}

	/**
	 * Lot M1 (2026-10-17): the {@code set_trusted_sources} argument that REGISTERS the golden source on a table whose ranking a rewrite does not write - the STORED ranking with
	 * {@link MatchingPolicyTranslator#GOLDEN_SOURCE_CODE} appended, and nothing else ({@code sources} and {@code fieldTrustedSources} are not sent, so they stay as they are).
	 *
	 * @param pStoredTrustedSources the {@code trustedSources} object of a {@code get_matching_config} answer (a missing node reads as nothing registered)
	 * @param pDefaultSourceFieldPath the {@code sourceField} to send when the table stored none
	 * @return {@code null} when the code is already in the stored ranking or in the instance catalogue ({@code sources[].code}) - nothing to call
	 */
	public static ObjectNode registerGoldenSourcePayload(final com.fasterxml.jackson.databind.JsonNode pStoredTrustedSources, final String pDefaultSourceFieldPath)
	{
		final String code = MatchingPolicyTranslator.GOLDEN_SOURCE_CODE;
		boolean registered = false;
		final List<String> ranking = new ArrayList<String>();
		for (final com.fasterxml.jackson.databind.JsonNode entry : pStoredTrustedSources.path("tableTrustedSourceList"))
		{
			ranking.add(entry.asText());
			registered |= code.equals(entry.asText());
		}
		for (final com.fasterxml.jackson.databind.JsonNode entry : pStoredTrustedSources.path("sources"))
		{
			registered |= code.equals(entry.path("code").asText());
		}
		if (registered)
		{
			return null;
		}
		ranking.add(code);
		final ObjectNode payload = TrustedSourceRanking.MAPPER.createObjectNode();
		final String storedSourceField = pStoredTrustedSources.path("sourceField").asText("");
		payload.put("sourceField", storedSourceField.isEmpty() ? pDefaultSourceFieldPath : storedSourceField);
		final ArrayNode list = payload.putArray("tableTrustedSourceList");
		for (final String entry : ranking)
		{
			list.add(entry);
		}
		return payload;
	}

	/**
	 * Full form - survivorship lot (2026-09-22, `docs/MATCHING.md` &sect;15): adds {@code fieldTrustedSources}, the
	 * per-field trusted-source overrides an LLM-proposed survivorship rule can carry ({@code
	 * master.MatchingPolicyTranslator#extractFieldTrustedSourceOverrides}'s own output - {@code [{field,
	 * trustedSourceList}, ...]}). Omitted entirely when {@code pFieldTrustedSources} is null/empty, so a project
	 * with no field-level override sends exactly the same payload as before this lot.
	 * <p>
	 * <b>"A supplied fieldTrustedSources list deletes any override not present" (task brief's own contract,
	 * `docs/MATCHING.md` &sect;15) - the caller must always pass the FULL desired list, never a delta.</b> This
	 * method has no memory of a previous call to violate that with; the caller ({@code
	 * ebx.MatchingConfigurator#configureOne}/{@code #applySurvivorship}) is the one place this matters, since it is
	 * the one place that could otherwise be tempted to pass only what changed.
	 */
	public static ObjectNode buildTrustedSourcesPayload(final String pSourceFieldPath, final List<Source> pOrderedSources, final ArrayNode pFieldTrustedSources)
	{
		final ObjectNode trustedSources = TrustedSourceRanking.MAPPER.createObjectNode();
		trustedSources.put("sourceField", pSourceFieldPath);
		final ArrayNode tableTrustedSourceList = trustedSources.putArray("tableTrustedSourceList");
		boolean goldenSourceListed = false;
		for (final Source source : pOrderedSources)
		{
			tableTrustedSourceList.add(source.code);
			goldenSourceListed |= MatchingPolicyTranslator.GOLDEN_SOURCE_CODE.equals(source.code);
		}
		if (!goldenSourceListed)
		{
			// lot M1 (2026-10-17): the merge policy's autoCreateNewGoldenSource names a REGISTERED source; "any referenced code is auto-created if absent"
			// is how this payload registers it, and last so that it never outranks a real source (a golden record is the least trusted contributor)
			tableTrustedSourceList.add(MatchingPolicyTranslator.GOLDEN_SOURCE_CODE);
		}
		if (pFieldTrustedSources != null && pFieldTrustedSources.size() > 0)
		{
			trustedSources.set("fieldTrustedSources", pFieldTrustedSources);
		}
		return trustedSources;
	}
}
