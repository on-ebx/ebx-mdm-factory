package com.on.ebx.factory.staging;

/** JUnit-free: run the main, a failure throws. Staging-proposal live-run fix, bug 4. */
public final class MappingScopeTest
{
	public static void main(final String[] pArgs)
	{
		MappingScopeTest.acceptedOnlyBehaviourUnchanged();
		MappingScopeTest.proposalIncludesAnOrdinaryProposedRow();
		MappingScopeTest.proposalExcludesASupersededEntity();
		MappingScopeTest.proposalExcludesARejectedEntity();
		MappingScopeTest.proposalExcludesARejectedAttribute();
		MappingScopeTest.proposalExcludesASupersededAttribute();
		MappingScopeTest.proposalExcludesARejectedFeed();
		MappingScopeTest.nullEntityAttributeFeedNeverExcludeOnTheirOwn();
		MappingScopeTest.eligibleStillHonoursTheMappingsOwnStatus();

		System.out.println("MappingScopeTest OK");
	}

	private static void acceptedOnlyBehaviourUnchanged()
	{
		// ACCEPTED_ONLY never looked at entity/attribute/feed status before this fix - eligible() must not
		// change that, even when they are REJECTED/SUPERSEDED (task brief: "must not change").
		MappingScopeTest.check("ACCEPTED_ONLY: an ACCEPTED mapping is eligible whatever its entity/attribute/feed status",
			MappingScope.ACCEPTED_ONLY.eligible("ACCEPTED", "SUPERSEDED", "REJECTED", "REJECTED"));
		MappingScopeTest.check("ACCEPTED_ONLY: a PROPOSED mapping is still out of scope (unchanged)",
			!MappingScope.ACCEPTED_ONLY.eligible("PROPOSED", "ACCEPTED", "ACCEPTED", "ACCEPTED"));
	}

	private static void proposalIncludesAnOrdinaryProposedRow()
	{
		MappingScopeTest.check("PROPOSAL: a PROPOSED mapping on a PROPOSED entity/attribute/feed is eligible",
			MappingScope.PROPOSAL.eligible("PROPOSED", "PROPOSED", "PROPOSED", "PROPOSED"));
	}

	private static void proposalExcludesASupersededEntity()
	{
		// the exact bug measured live: StagingEntity 22/23 SUPERSEDED by StagingLlmSuggester's canonical merge.
		MappingScopeTest.check("PROPOSAL: excludes a mapping whose entity was SUPERSEDED",
			!MappingScope.PROPOSAL.eligible("PROPOSED", "SUPERSEDED", "PROPOSED", "PROPOSED"));
	}

	private static void proposalExcludesARejectedEntity()
	{
		MappingScopeTest.check("PROPOSAL: excludes a mapping whose entity was REJECTED",
			!MappingScope.PROPOSAL.eligible("PROPOSED", "REJECTED", "PROPOSED", "PROPOSED"));
	}

	private static void proposalExcludesARejectedAttribute()
	{
		MappingScopeTest.check("PROPOSAL: excludes a mapping whose attribute was REJECTED",
			!MappingScope.PROPOSAL.eligible("PROPOSED", "PROPOSED", "REJECTED", "PROPOSED"));
	}

	private static void proposalExcludesASupersededAttribute()
	{
		MappingScopeTest.check("PROPOSAL: excludes a mapping whose attribute was SUPERSEDED",
			!MappingScope.PROPOSAL.eligible("PROPOSED", "PROPOSED", "SUPERSEDED", "PROPOSED"));
	}

	private static void proposalExcludesARejectedFeed()
	{
		MappingScopeTest.check("PROPOSAL: excludes a mapping whose feed was REJECTED",
			!MappingScope.PROPOSAL.eligible("PROPOSED", "PROPOSED", "PROPOSED", "REJECTED"));
	}

	private static void nullEntityAttributeFeedNeverExcludeOnTheirOwn()
	{
		MappingScopeTest.check("PROPOSAL: a null entity/attribute/feed status (unresolved row, or pre-TransferFeed mapping) never excludes on its own",
			MappingScope.PROPOSAL.eligible("PROPOSED", null, null, null));
	}

	private static void eligibleStillHonoursTheMappingsOwnStatus()
	{
		MappingScopeTest.check("PROPOSAL: a REJECTED mapping is excluded whatever its entity/attribute/feed status",
			!MappingScope.PROPOSAL.eligible("REJECTED", "PROPOSED", "PROPOSED", "PROPOSED"));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
