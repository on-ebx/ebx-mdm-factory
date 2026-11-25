package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Structural evidence beats text - a second, independent guard on top of {@code ebx.MemberEntitiesBackfill}
 * (`docs/MASTER-ZONE.md`, 2026-09-21 incident: {@code Company}, origin {@code LLM}, {@code memberEntities}
 * empty although its rationale still read {@code "Unifies Supplier, Account: ..."}, promoted {@code Supplier}/
 * {@code Account} to master alongside it). The backfill fixes the common case (a rationale that still parses
 * cleanly); this class catches what it CANNOT fix - a row the backfill left with {@code memberEntities} still
 * empty, but whose own attribute mappings draw from a landing table some OTHER accepted entity of the same
 * project also draws from. Two accepted entities independently fed by the SAME landing table is exactly the
 * shape a genuine canonical-entity unification has ({@code Company}'s attributes are mapped from both
 * {@code SUPPLIER} and {@code ACCOUNT} landing columns, the same tables {@code Supplier} and {@code Account}
 * themselves are fed from) - an {@code LLM}-origin entity in that shape with no recorded members is refused
 * outright by {@code ebx.MasterGenerator} rather than silently promoted as if it were unrelated to them.
 * <p>
 * <b>By design, running {@code ebx.MemberEntitiesBackfill} first makes this refusal disappear</b> on the
 * project it was written for: once {@code Company.memberEntities} is backfilled, {@link EntityInfo#hasMembers}
 * is true and {@link #check} no longer looks at it at all (see the loop's own guard). A row the backfill could
 * not resolve (a hand-typed canonical entity, or one naming something that no longer exists) still shows up
 * here if it has the same landing-table overlap shape - the refusal then tells a person what to do, since a
 * silent guess at membership is exactly what this whole incident was.
 * <p>
 * Pure: the caller ({@code ebx.MasterGenerator}) does the EBX reads (which entities are accepted-like, which
 * landing tables their accepted attribute mappings draw from) and hands in plain {@link EntityInfo} values;
 * this class only compares sets and builds messages, so it is unit-tested without an EBX runtime.
 */
public final class MembershipConsistencyCheck
{
	private MembershipConsistencyCheck()
	{
	}

	/** One accepted-like {@code StagingEntity}, as far as this check needs to know it. */
	public static final class EntityInfo
	{
		public final String name;
		/** {@code RULE}/{@code LLM}/{@code USER}. */
		public final String origin;
		/** True when {@code StagingEntity.memberEntities} is non-empty - see the class Javadoc for why that alone closes the question for this entity. */
		public final boolean hasMembers;
		/** Names of the landing tables this entity's own ACCEPTED-like, non-technical-key attribute mappings draw from - never null, possibly empty (no mapped attribute yet). */
		public final Set<String> landingTables;

		public EntityInfo(final String pName, final String pOrigin, final boolean pHasMembers, final Set<String> pLandingTables)
		{
			this.name = pName;
			this.origin = pOrigin;
			this.hasMembers = pHasMembers;
			this.landingTables = pLandingTables;
		}
	}

	public static final class Result
	{
		public final boolean consistent;
		/** One actionable message per inconsistent entity - empty (never null) when {@link #consistent}. */
		public final List<String> refusals;

		private Result(final boolean pConsistent, final List<String> pRefusals)
		{
			this.consistent = pConsistent;
			this.refusals = pRefusals;
		}
	}

	public static Result check(final List<EntityInfo> pEntities)
	{
		final List<String> refusals = new ArrayList<String>();
		for (final EntityInfo entity : pEntities)
		{
			if (!"LLM".equals(entity.origin) || entity.hasMembers || entity.landingTables.isEmpty())
			{
				continue;
			}
			final List<String> overlapping = new ArrayList<String>();
			for (final EntityInfo other : pEntities)
			{
				if (other.name.equals(entity.name))
				{
					continue;
				}
				if (!Collections.disjoint(entity.landingTables, other.landingTables))
				{
					overlapping.add(other.name);
				}
			}
			if (!overlapping.isEmpty())
			{
				refusals.add(entity.name + ": a language-model entity whose attributes are mapped from the same landing table(s) as " + overlapping
					+ ", but memberEntities is empty - this looks like an unresolved canonical unification, not an independent entity. The "
					+ "backfill from its rationale could not resolve every member name: set memberEntities (Unified entities) by hand on that entity, "
					+ "before generating the master model.");
			}
		}
		return new Result(refusals.isEmpty(), refusals);
	}
}
