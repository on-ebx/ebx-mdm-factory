package com.on.ebx.factory.ebx;

import java.util.AbstractSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.ebx.MasterZoneActivation.Triple;

/** JUnit-free: run the main, a failure throws. */
public final class MasterZoneActivationTest
{
	public static void main(final String[] pArgs)
	{
		MasterZoneActivationTest.matches_exactTripleTrue();
		MasterZoneActivationTest.matches_differentDataspaceFalse();
		MasterZoneActivationTest.matches_differentDatasetFalse();
		MasterZoneActivationTest.matches_differentTablePathFalse();
		MasterZoneActivationTest.matches_emptySetFalse();
		MasterZoneActivationTest.matches_nullSetFalse();
		MasterZoneActivationTest.matches_nullArgumentFalse();
		MasterZoneActivationTest.matches_setThatThrowsIsDisabled();
		MasterZoneActivationTest.matches_liveTripleExactly();
		MasterZoneActivationTest.matches_tablePathWithLeadingDotNormalises();
		MasterZoneActivationTest.matches_whitespaceAroundDataspaceOrDatasetNormalises();
		MasterZoneActivationTest.matches_caseDifferenceStillMismatches();
		MasterZoneActivationTest.tripleEquality_valueBased();
		MasterZoneActivationTest.tripleEquality_hashCodeConsistentWithEquals();
		MasterZoneActivationTest.isFresh_justAfterCache();
		MasterZoneActivationTest.isFresh_exactlyAtTtlIsStale();
		MasterZoneActivationTest.isFresh_wellAfterTtlIsStale();
		MasterZoneActivationTest.isFresh_neverCachedIsStale();
		MasterZoneActivationTest.isFresh_zeroOrNegativeTtlAlwaysStale();
		MasterZoneActivationTest.isFresh_clockWentBackwardsIsStale();
		MasterZoneActivationTest.projectCodeFor_exactTripleReturnsCode();
		MasterZoneActivationTest.projectCodeFor_noMatchReturnsNull();
		MasterZoneActivationTest.projectCodeFor_nullMapReturnsNull();
		MasterZoneActivationTest.projectCodeFor_nullArgumentReturnsNull();
		MasterZoneActivationTest.projectCodeFor_leadingDotOnTablePathNormalises();
		System.out.println("MasterZoneActivationTest OK");
	}

	// ------------------------------------------------------------------------------------------------- projectCodeFor

	/** {@code MasterZoneActivation#projectCodeFor} has no caller today (2026-09-22, class Javadoc) - kept and tested anyway. */
	private static void projectCodeFor_exactTripleReturnsCode()
	{
		final Map<Triple, String> byTriple = new HashMap<Triple, String>();
		byTriple.put(new Triple("Companies_Master", "Master", "/root/Company"), "Companies");
		MasterZoneActivationTest.check("the owning project code is returned for an exact triple",
			"Companies".equals(MasterZoneActivation.projectCodeFor(byTriple, "Companies_Master", "Master", "/root/Company")));
	}

	private static void projectCodeFor_noMatchReturnsNull()
	{
		final Map<Triple, String> byTriple = new HashMap<Triple, String>();
		byTriple.put(new Triple("Companies_Master", "Master", "/root/Company"), "Companies");
		MasterZoneActivationTest.check("a triple not in the map answers null, never throws",
			MasterZoneActivation.projectCodeFor(byTriple, "Companies_Master", "Master", "/root/Supplier") == null);
	}

	private static void projectCodeFor_nullMapReturnsNull()
	{
		MasterZoneActivationTest.check("a null map answers null, never throws",
			MasterZoneActivation.projectCodeFor(null, "Companies_Master", "Master", "/root/Company") == null);
	}

	private static void projectCodeFor_nullArgumentReturnsNull()
	{
		final Map<Triple, String> byTriple = new HashMap<Triple, String>();
		byTriple.put(new Triple("Companies_Master", "Master", "/root/Company"), "Companies");
		MasterZoneActivationTest.check("a null dataspace key answers null", MasterZoneActivation.projectCodeFor(byTriple, null, "Master", "/root/Company") == null);
		MasterZoneActivationTest.check("a null dataset name answers null",
			MasterZoneActivation.projectCodeFor(byTriple, "Companies_Master", null, "/root/Company") == null);
		MasterZoneActivationTest.check("a null table path answers null", MasterZoneActivation.projectCodeFor(byTriple, "Companies_Master", "Master", null) == null);
	}

	/** Same normalisation as {@link #matches_tablePathWithLeadingDotNormalises} - {@link Triple}'s own constructor is shared by both lookups. */
	private static void projectCodeFor_leadingDotOnTablePathNormalises()
	{
		final Map<Triple, String> byTriple = new HashMap<Triple, String>();
		byTriple.put(new Triple("Companies_Master", "Master", "./root/Company"), "Companies");
		MasterZoneActivationTest.check("a cached triple with a leading './' still answers a lookup without one",
			"Companies".equals(MasterZoneActivation.projectCodeFor(byTriple, "Companies_Master", "Master", "/root/Company")));
	}

	// --------------------------------------------------------------------------------------------------- matching

	private static void matches_exactTripleTrue()
	{
		final Set<Triple> allowed = MasterZoneActivationTest.set(new Triple("Companies_Master", "Master", "/root/Company"));
		MasterZoneActivationTest.check("exact triple matches", MasterZoneActivation.matches(allowed, "Companies_Master", "Master", "/root/Company"));
	}

	private static void matches_differentDataspaceFalse()
	{
		final Set<Triple> allowed = MasterZoneActivationTest.set(new Triple("Companies_Master", "Master", "/root/Company"));
		MasterZoneActivationTest.check("different dataspace never matches", !MasterZoneActivation.matches(allowed, "Other_Master", "Master", "/root/Company"));
	}

	private static void matches_differentDatasetFalse()
	{
		final Set<Triple> allowed = MasterZoneActivationTest.set(new Triple("Companies_Master", "Master", "/root/Company"));
		MasterZoneActivationTest.check("different dataset never matches", !MasterZoneActivation.matches(allowed, "Companies_Master", "Other", "/root/Company"));
	}

	/** The acceptance criterion itself: a landing or staging table (same dataspace/dataset naming family, different table) must never match. */
	private static void matches_differentTablePathFalse()
	{
		final Set<Triple> allowed = MasterZoneActivationTest.set(new Triple("Companies_Master", "Master", "/root/Company"));
		MasterZoneActivationTest.check("different table path never matches (e.g. a staging table)",
			!MasterZoneActivation.matches(allowed, "Companies_Master", "Master", "/root/Supplier"));
	}

	private static void matches_emptySetFalse()
	{
		MasterZoneActivationTest.check("empty allowed set never matches",
			!MasterZoneActivation.matches(new HashSet<Triple>(), "Companies_Master", "Master", "/root/Company"));
	}

	private static void matches_nullSetFalse()
	{
		MasterZoneActivationTest.check("null allowed set never matches, never throws",
			!MasterZoneActivation.matches(null, "Companies_Master", "Master", "/root/Company"));
	}

	private static void matches_nullArgumentFalse()
	{
		final Set<Triple> allowed = MasterZoneActivationTest.set(new Triple("Companies_Master", "Master", "/root/Company"));
		MasterZoneActivationTest.check("null dataspace key never matches", !MasterZoneActivation.matches(allowed, null, "Master", "/root/Company"));
		MasterZoneActivationTest.check("null dataset name never matches", !MasterZoneActivation.matches(allowed, "Companies_Master", null, "/root/Company"));
		MasterZoneActivationTest.check("null table path never matches", !MasterZoneActivation.matches(allowed, "Companies_Master", "Master", null));
	}

	/** "exception -> disabled": a {@code Set} whose {@code contains} throws must still answer false, never propagate. */
	private static void matches_setThatThrowsIsDisabled()
	{
		final Set<Triple> throwing = new AbstractSet<Triple>()
		{
			@Override
			public boolean contains(final Object pObject)
			{
				throw new RuntimeException("simulated failure reading the cache");
			}

			@Override
			public Iterator<Triple> iterator()
			{
				return new HashSet<Triple>().iterator();
			}

			@Override
			public int size()
			{
				return 0;
			}
		};
		MasterZoneActivationTest.check("an exception from the allowed set answers false, never throws",
			!MasterZoneActivation.matches(throwing, "Companies_Master", "Master", "/root/Company"));
	}

	/**
	 * The exact live triple the product owner measured, 2026-09-22, 10:19: dataspace {@code Companies_Master}, dataset
	 * {@code Master} ({@code Project} row: code {@code Companies}), table {@code /root/Company}. Kept as its
	 * own named test (distinct from {@link #matches_exactTripleTrue}, which predates this incident) so this
	 * specific acceptance case is never accidentally deleted/renamed away in a later refactor.
	 */
	private static void matches_liveTripleExactly()
	{
		final Set<Triple> allowed = MasterZoneActivationTest.set(new Triple("Companies_Master", "Master", "/root/Company"));
		MasterZoneActivationTest.check("the exact live triple from the 2026-09-22 10:19 measurement matches",
			MasterZoneActivation.matches(allowed, "Companies_Master", "Master", "/root/Company"));
	}

	/** {@code Triple}'s own normalisation (coordinator review, 2026-09-22, "fact 2" - made robust on principle): a leading {@code "./"} on either side of the comparison must not, by itself, cause a miss. */
	private static void matches_tablePathWithLeadingDotNormalises()
	{
		final Set<Triple> allowedWithDot = MasterZoneActivationTest.set(new Triple("Companies_Master", "Master", "./root/Company"));
		MasterZoneActivationTest.check("a cached triple with a leading './' still matches a lookup without one",
			MasterZoneActivation.matches(allowedWithDot, "Companies_Master", "Master", "/root/Company"));
		final Set<Triple> allowedWithoutDot = MasterZoneActivationTest.set(new Triple("Companies_Master", "Master", "/root/Company"));
		MasterZoneActivationTest.check("a cached triple without a leading './' still matches a lookup that has one",
			MasterZoneActivation.matches(allowedWithoutDot, "Companies_Master", "Master", "./root/Company"));
	}

	/** Same normalisation, for incidental leading/trailing whitespace on the dataspace key or dataset name. */
	private static void matches_whitespaceAroundDataspaceOrDatasetNormalises()
	{
		final Set<Triple> allowed = MasterZoneActivationTest.set(new Triple(" Companies_Master ", " Master ", "/root/Company"));
		MasterZoneActivationTest.check("leading/trailing whitespace on the dataspace key or dataset name never causes a miss",
			MasterZoneActivation.matches(allowed, "Companies_Master", "Master", "/root/Company"));
	}

	/** Normalisation trims and drops a leading "./" - it never case-folds: an actual case difference is a real mismatch (EBX keys are case-sensitive), not formatting noise. */
	private static void matches_caseDifferenceStillMismatches()
	{
		final Set<Triple> allowed = MasterZoneActivationTest.set(new Triple("Companies_Master", "Master", "/root/Company"));
		MasterZoneActivationTest.check("a genuine case difference on the dataset name is never normalised away",
			!MasterZoneActivation.matches(allowed, "Companies_Master", "master", "/root/Company"));
	}

	private static void tripleEquality_valueBased()
	{
		final Triple a = new Triple("Companies_Master", "Master", "/root/Company");
		final Triple b = new Triple("Companies_Master", "Master", "/root/Company");
		MasterZoneActivationTest.check("value-equal triples are equal", a.equals(b));
		MasterZoneActivationTest.check("value-equal triples share a hash code", a.hashCode() == b.hashCode());
		MasterZoneActivationTest.check("not equal to null", !a.equals(null));
		MasterZoneActivationTest.check("not equal to an unrelated type", !a.equals("Companies_Master/Master/root/Company"));
	}

	private static void tripleEquality_hashCodeConsistentWithEquals()
	{
		final Set<Triple> set = new HashSet<Triple>();
		set.add(new Triple("Companies_Master", "Master", "/root/Company"));
		MasterZoneActivationTest.check("a value-equal triple is found through a HashSet",
			set.contains(new Triple("Companies_Master", "Master", "/root/Company")));
	}

	// --------------------------------------------------------------------------------------------------------- TTL

	private static void isFresh_justAfterCache()
	{
		MasterZoneActivationTest.check("just refreshed, well within TTL, is fresh", MasterZoneActivation.isFresh(1_000L, 1_500L, 30_000L));
	}

	private static void isFresh_exactlyAtTtlIsStale()
	{
		MasterZoneActivationTest.check("exactly at the TTL boundary is stale (strict less-than)", !MasterZoneActivation.isFresh(1_000L, 31_000L, 30_000L));
	}

	private static void isFresh_wellAfterTtlIsStale()
	{
		MasterZoneActivationTest.check("well past the TTL is stale", !MasterZoneActivation.isFresh(1_000L, 100_000L, 30_000L));
	}

	private static void isFresh_neverCachedIsStale()
	{
		MasterZoneActivationTest.check("cachedAt == 0 (never cached, or just invalidated) is always stale", !MasterZoneActivation.isFresh(0L, 5_000L, 30_000L));
	}

	private static void isFresh_zeroOrNegativeTtlAlwaysStale()
	{
		MasterZoneActivationTest.check("a zero TTL always refreshes", !MasterZoneActivation.isFresh(1_000L, 1_000L, 0L));
		MasterZoneActivationTest.check("a negative TTL always refreshes", !MasterZoneActivation.isFresh(1_000L, 1_000L, -1L));
	}

	private static void isFresh_clockWentBackwardsIsStale()
	{
		MasterZoneActivationTest.check("now before cachedAt (a clock adjustment) is treated as stale, never a negative age making it look fresher",
			!MasterZoneActivation.isFresh(10_000L, 1_000L, 30_000L));
	}

	// --------------------------------------------------------------------------------------------------------- misc

	private static Set<Triple> set(final Triple pTriple)
	{
		final Set<Triple> out = new HashSet<Triple>();
		out.add(pTriple);
		return out;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
