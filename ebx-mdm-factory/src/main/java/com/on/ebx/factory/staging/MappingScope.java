package com.on.ebx.factory.staging;

/**
 * Which statuses a quality-rule / capability (script-check) suggestion pass is allowed to read and propose
 * over. Staging-proposal-run lot (2026-09-24): before this, {@code QualitySuggester.readAcceptedMappings}
 * (and the entity/attribute status checks {@code CapabilityLlmSuggester} makes alongside it) only ever
 * accepted {@link com.on.ebx.factory.staging.TechnicalKeyRule#isAcceptedLike}, i.e. {@code ACCEPTED}/{@code
 * APPLIED} - correct for every pre-existing caller (a person reviews structure first, then asks for quality
 * rules/checks on what they just accepted), but wrong for the one-click proposal run: right after "Propose
 * the staging" everything the structure pass just wrote is {@code PROPOSED}, so a quality/capability pass
 * scoped to accepted-only mappings would always find nothing to work on.
 * <p>
 * {@link #ACCEPTED_ONLY} is the default for every existing caller (unchanged behaviour, unchanged REST
 * routes). {@link #PROPOSAL} additionally includes {@code PROPOSED} - never {@code REJECTED}/{@code
 * SUPERSEDED}, which stay excluded in both scopes; the rows either scope produces are written {@code
 * PROPOSED} either way, so the reviewer still decides everything, whichever scope produced it.
 */
public enum MappingScope
{
	ACCEPTED_ONLY, PROPOSAL;

	/** True when {@code pStatus} is in scope: accepted-like in either scope, additionally {@code PROPOSED} for {@link #PROPOSAL}. */
	public boolean includes(final String pStatus)
	{
		if (TechnicalKeyRule.isAcceptedLike(pStatus))
		{
			return true;
		}
		return this == PROPOSAL && "PROPOSED".equals(pStatus);
	}

	/**
	 * Staging-proposal live-run fix (bug 4, docs/STAGING-PROPOSAL.md "live fixes"): {@link #includes(String)}
	 * alone only ever looked at the {@code AttributeMapping}'s OWN status - measured live on project Personnes,
	 * {@link #PROPOSAL} sent two rule-pass entities StagingLlmSuggester had already unified into a canonical
	 * one (status {@code SUPERSEDED} - see {@code ebx.StagingLlmSuggester}'s own Javadoc for why entity status
	 * can hold that value even though {@code StagingEntity.status}'s own XSD enumeration never declares it: a
	 * static enumeration is non-blocking, the knowledge base's own finding) to {@code QualityLlmSuggester}/
	 * {@code CapabilityLlmSuggester} anyway, wasting two full agent calls (~30k input tokens each) proposing
	 * quality rules and checks nobody would ever see (the superseded entity's own staging table is never
	 * generated). This is the one place that decides "is this mapping worth reading AT ALL, given the rest of
	 * the chain it hangs off", every scope-aware caller now goes through - the mapping's own status via {@link
	 * #includes(String)}, plus, for {@link #PROPOSAL} only, its entity/attribute must not be {@code REJECTED}/
	 * {@code SUPERSEDED} and its feed (if any) must not be {@code REJECTED}. {@link #ACCEPTED_ONLY}'s own
	 * behaviour is unchanged on purpose (task brief): a person reviewing entity-by-entity already only ever
	 * ACCEPTs a mapping whose entity/attribute/feed they also accepted, so this extra check is a no-op there in
	 * practice - not worth risking a regression on the one scope every pre-existing caller already relies on.
	 * {@code pEntityStatus}/{@code pAttributeStatus}/{@code pFeedStatus} may be null (a row whose entity/
	 * attribute could not be resolved, or a mapping written before {@code TransferFeed} existed - {@code
	 * FactoryPaths.AttributeMapping#FEED}'s own Javadoc, "empty only for a row written before this lot") - null
	 * is never {@code REJECTED}/{@code SUPERSEDED}, so it never excludes on its own.
	 */
	public boolean eligible(final String pMappingStatus, final String pEntityStatus, final String pAttributeStatus, final String pFeedStatus)
	{
		if (!this.includes(pMappingStatus))
		{
			return false;
		}
		if (this != PROPOSAL)
		{
			return true;
		}
		if (MappingScope.isRejectedOrSuperseded(pEntityStatus) || MappingScope.isRejectedOrSuperseded(pAttributeStatus))
		{
			return false;
		}
		return !"REJECTED".equals(pFeedStatus);
	}

	private static boolean isRejectedOrSuperseded(final String pStatus)
	{
		return "REJECTED".equals(pStatus) || "SUPERSEDED".equals(pStatus);
	}
}
