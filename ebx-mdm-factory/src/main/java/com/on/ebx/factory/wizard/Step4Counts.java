package com.on.ebx.factory.wizard;

import com.on.ebx.factory.review.ReviewLine;

/**
 * UX lot U3 (2026-10-13, RAPPORT 3.3): THE counts of step 4's proposal - one set, computed at render from the decisions as they stand now, used by
 * the "Proposal ready for review" block, its "N proposals waiting for review" line and the "Your decisions" side card. Before, three places
 * counted the same proposal three ways ("40 fields, 6 checks" fixed at the run, "57 proposals waiting", "31 fields accepted, 11 checks accepted
 * and 8 set aside") and the first never moved after a decision. Pure (no EBX type), built from a {@link WizardSnapshot} only.
 * <p>
 * Definitions, one per nature (a SUPERSEDED business object - absorbed by a canonical one - is hidden everywhere in step 4 and counts nowhere):
 * <ul>
 * <li>business objects: the staging entities, by their own status;</li>
 * <li>fields: the entities' attributes except the technical key ({@code primaryKey}: it has no decision of its own, it follows its entity);</li>
 * <li>source links: the attribute mappings ({@code AttributeMapping});</li>
 * <li>cleaning steps: the {@code MAPPING_STEP} proposal lines;</li>
 * <li>quality rules: the {@code QUALITY} proposal lines;</li>
 * <li>checks: the {@code SCRIPT_CONSTRAINT} and {@code SCRIPT_DERIVATION} proposal lines and the connector-service suggestions
 * ({@code EXTERNAL_SERVICE}) - what the "decisions left" card calls "checks and services".</li>
 * </ul>
 * A row is counted once, in the bucket of its status: {@code PROPOSED} = waiting, {@code ACCEPTED}/{@code APPLIED} = accepted, {@code REJECTED},
 * {@code SET_ASIDE}; {@link Row#total} is their sum, so a total can never disagree with its parts.
 * <p>
 * Staging-screens lot (2026-10-17): a connector look-up suggestion ({@code EXTERNAL_SERVICE}) still {@code PROPOSED} is OPTIONAL: it waits in
 * {@link Row#optionalWaiting}, not in {@link Row#waiting}, so {@link #waiting()} - what keeps step 4 in "Review the proposal" and "Build and load" out of
 * the page - never counts it. Measured live (project Tiers B2C, 2026-10-17): the 3 suggestions left after the whole review kept the build button hidden
 * behind "Review the proposal", and "Nothing flagged" sat above "Build and load will appear here after the review".
 */
public final class Step4Counts
{
	/** One nature's counts. */
	public static final class Row
	{
		/** Still {@code PROPOSED} and blocking: the review is not over while any is left. */
		public int waiting;
		/** Still {@code PROPOSED} but optional (a connector look-up suggestion): listed, never blocking. Counted in {@link #total}. */
		public int optionalWaiting;
		public int accepted;
		public int rejected;
		public int setAside;

		public int total()
		{
			return this.waiting + this.optionalWaiting + this.accepted + this.rejected + this.setAside;
		}

		void add(final String pStatus)
		{
			this.add(pStatus, false);
		}

		void add(final String pStatus, final boolean pOptional)
		{
			if (ReviewLine.PROPOSED.equals(pStatus))
			{
				if (pOptional)
				{
					this.optionalWaiting++;
				}
				else
				{
					this.waiting++;
				}
			}
			else if (ReviewLine.ACCEPTED.equals(pStatus) || ReviewLine.APPLIED.equals(pStatus))
			{
				this.accepted++;
			}
			else if (ReviewLine.REJECTED.equals(pStatus))
			{
				this.rejected++;
			}
			else if (ReviewLine.SET_ASIDE.equals(pStatus))
			{
				this.setAside++;
			}
		}
	}

	public final Row businessObjects = new Row();
	public final Row fields = new Row();
	public final Row sourceLinks = new Row();
	public final Row cleaningSteps = new Row();
	public final Row qualityRules = new Row();
	public final Row checks = new Row();

	private Step4Counts()
	{
	}

	/** The natures in display order, with the bundle key of their name ({@code wizard.step4.counts.<key>}). */
	public String[] keys()
	{
		return new String[] { "businessObjects", "fields", "sourceLinks", "cleaningSteps", "qualityRules", "checks" };
	}

	public Row row(final String pKey)
	{
		if ("businessObjects".equals(pKey))
		{
			return this.businessObjects;
		}
		if ("fields".equals(pKey))
		{
			return this.fields;
		}
		if ("sourceLinks".equals(pKey))
		{
			return this.sourceLinks;
		}
		if ("cleaningSteps".equals(pKey))
		{
			return this.cleaningSteps;
		}
		if ("qualityRules".equals(pKey))
		{
			return this.qualityRules;
		}
		return this.checks;
	}

	/** Optional look-up suggestions still waiting - never part of {@link #waiting()}, never a reason to keep the build button away. */
	public int optionalWaiting()
	{
		return this.businessObjects.optionalWaiting + this.fields.optionalWaiting + this.sourceLinks.optionalWaiting + this.cleaningSteps.optionalWaiting
			+ this.qualityRules.optionalWaiting + this.checks.optionalWaiting;
	}

	/** Everything still waiting for a decision that the review has to settle, all natures - the one "N proposals waiting for review" number (optional suggestions apart, {@link #optionalWaiting()}). */
	public int waiting()
	{
		return this.businessObjects.waiting + this.fields.waiting + this.sourceLinks.waiting + this.cleaningSteps.waiting + this.qualityRules.waiting
			+ this.checks.waiting;
	}

	public static Step4Counts of(final WizardSnapshot pSnapshot)
	{
		final Step4Counts out = new Step4Counts();
		if (pSnapshot == null)
		{
			return out;
		}
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if (ReviewLine.SUPERSEDED.equals(entity.status))
			{
				continue;
			}
			out.businessObjects.add(entity.status);
			for (final WizardSnapshot.AttributeInfo attribute : entity.attributes)
			{
				if (!attribute.followsEntity())
				{
					out.fields.add(attribute.status);
				}
				for (final WizardSnapshot.MappingInfo mapping : attribute.mappings)
				{
					out.sourceLinks.add(mapping.status);
				}
			}
			for (final WizardSnapshot.ProposalLineInfo info : entity.proposalLines)
			{
				final String nature = info.line.nature;
				if (ReviewLine.MAPPING_STEP.equals(nature))
				{
					out.cleaningSteps.add(info.line.status);
				}
				else if (ReviewLine.QUALITY.equals(nature))
				{
					out.qualityRules.add(info.line.status);
				}
				else if (ReviewLine.SCRIPT_CONSTRAINT.equals(nature) || ReviewLine.SCRIPT_DERIVATION.equals(nature))
				{
					out.checks.add(info.line.status);
				}
			}
			for (final WizardSnapshot.CapabilityInfo capability : entity.capabilities)
			{
				out.checks.add(capability.status, true);
			}
		}
		return out;
	}
}
