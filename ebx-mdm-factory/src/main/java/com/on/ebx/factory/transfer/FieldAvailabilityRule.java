package com.on.ebx.factory.transfer;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Coordinator finding, 2026-09-21 (review of this lot's own task A fix): an attribute can be {@code ACCEPTED}
 * (or even feed a {@code MappingStep}/{@code QualityRule} already reviewed) WITHOUT its field actually
 * existing yet on the published staging table - {@code staging/generate} lowers an accepted attribute into a
 * real field, and nothing forces a person to re-run it before running {@code staging/load} again. Both the
 * pre-existing mapped-field write ({@code ebx.StagingLoader.planRow}'s own loop over {@code pFeed.fields},
 * gated only on {@code TechnicalKeyRule.isAcceptedLike(attribute.status)} in {@code readMappings} - {@code
 * ACCEPTED} passes that check exactly like {@code APPLIED}, with no check that the field was ever actually
 * built) and this lot's own {@link NullFillRule} seeding hit the same hazard: {@code ValueContextForUpdate
 * .setValue} on a path the table's schema does not define throws (the same family of failure {@code
 * Adaptation.get} has on an undefined path, `CLAUDE.md` sec 9, "Never build or split a composite primary key
 * as a string" bullet's neighbour - "Adaptation.get(path) throws... no child named 'x' is defined") - so a
 * single such field would fail the WHOLE batch (and, after {@code BatchRetry} isolates it, that one row) on
 * every load, forever, until someone thinks to re-run {@code staging/generate}.
 * <p>
 * This class is the PURE half of the fix: given the full set of field names a feed's plan (or a master
 * entity's field plan) might touch, and the subset the caller already confirmed exists on the live table
 * (read via EBX schema, computed ONCE per feed/entity, never per row - see {@code ebx.StagingLoader}/{@code
 * ebx.MasterLoader}'s own callers), which of them are safe to write and which must be skipped entirely this
 * run. No EBX class here - the schema read itself stays in the caller, this is only the set-difference
 * decision, unit-tested without a running instance.
 */
public final class FieldAvailabilityRule
{
	private FieldAvailabilityRule()
	{
	}

	/** {@link #classify}'s answer: every candidate field name sorted into exactly one of the two sets - never both, never neither. */
	public static final class Result
	{
		public final Set<String> writable;
		public final Set<String> missing;

		Result(final Set<String> pWritable, final Set<String> pMissing)
		{
			this.writable = pWritable;
			this.missing = pMissing;
		}
	}

	/**
	 * @param pCandidateFieldNames every field name the caller's plan might write this run (a mapped
	 *            attribute's own field, its {@code <attribute>Ref} foreign-key field, and every {@link
	 *            NullFillRule}-forced-null field alike - all of them can be missing for the same reason);
	 *            {@code null} reads as empty
	 * @param pExistingFieldNames the subset already confirmed, by the caller, to exist on the live table's
	 *            schema right now; {@code null} reads as "nothing confirmed", i.e. everything missing
	 * @return {@link Result#writable}/{@link Result#missing}, partitioning {@code pCandidateFieldNames} - a
	 *         name present in {@code pExistingFieldNames} but absent from {@code pCandidateFieldNames} is
	 *         simply never mentioned (the caller only ever asks about names it actually cares about)
	 */
	public static Result classify(final Collection<String> pCandidateFieldNames, final Set<String> pExistingFieldNames)
	{
		final Set<String> writable = new LinkedHashSet<String>();
		final Set<String> missing = new LinkedHashSet<String>();
		if (pCandidateFieldNames != null)
		{
			for (final String name : pCandidateFieldNames)
			{
				if (pExistingFieldNames != null && pExistingFieldNames.contains(name))
				{
					writable.add(name);
				}
				else
				{
					missing.add(name);
				}
			}
		}
		return new Result(writable, missing);
	}
}
