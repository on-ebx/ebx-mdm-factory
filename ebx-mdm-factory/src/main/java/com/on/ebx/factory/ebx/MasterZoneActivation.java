package com.on.ebx.factory.ebx;

import java.util.Map;
import java.util.Set;

/**
 * The PURE core of the "Record lineage" record-level action's activation decision (coordinator review,
 * 2026-09-22): given the (dataspace, dataset, table) triples a project's promoted master zone occupies
 * right now, and the triple of the record actually being viewed, decide whether the action shows. No EBX
 * class anywhere in this file - unit-tested without an EBX runtime, the same discipline every other pure
 * model in this module follows ({@code lineage.RecordLineage}, {@code master.MasterModel}, ...).
 * <p>
 * {@link MasterZoneIndex} (EBX-bound: reads the factory's own {@code Project}/{@code StagingEntity} rows
 * with a system session, on a TTL-bounded cache) and {@code ebx.ui.RecordLineageOnRecordPermissionRule}
 * (EBX-bound: resolves a record's own dataspace/dataset/table from a {@code ServicePermissionRuleContext})
 * are the two classes this file has no dependency on and no knowledge of - they call into this class, never
 * the other way round.
 */
public final class MasterZoneActivation
{
	private MasterZoneActivation()
	{
	}

	/**
	 * One (dataspace key, dataset name, table path) triple - value equality, after normalising every field
	 * the SAME way regardless of which side (the cached "allowed" set, or the record currently being
	 * rendered) constructed it, so the two never drift apart over an incidental formatting difference the
	 * task brief still calls a match (coordinator review, 2026-09-22, "fact 2" - made robust on principle,
	 * independently of whichever concrete mismatch turned out to be the live bug): a dataspace key/dataset
	 * name is trimmed (never case-folded - EBX keys are case-sensitive, an actual case difference is a real
	 * mismatch, not formatting noise); a table path additionally drops one leading {@code "."} so
	 * {@code "./root/Company"} and {@code "/root/Company"} compare equal, matching {@code
	 * master.MasterModel#tablePath}'s own {@code "/root/<Entity>"} convention on both sides. {@code null} stays
	 * {@code null} (never becomes the string {@code "null"}) so {@link #matches}'s own null-argument guard is
	 * unaffected.
	 */
	public static final class Triple
	{
		public final String dataspaceKey;
		public final String datasetName;
		public final String tablePath;

		public Triple(final String pDataspaceKey, final String pDatasetName, final String pTablePath)
		{
			this.dataspaceKey = Triple.trim(pDataspaceKey);
			this.datasetName = Triple.trim(pDatasetName);
			this.tablePath = Triple.normalizeTablePath(pTablePath);
		}

		private static String trim(final String pValue)
		{
			return pValue == null ? null : pValue.trim();
		}

		/** Strips one leading {@code "."} (so {@code "./root/Company"} normalises to {@code "/root/Company"}) - never touches anything past the first character, so a genuinely different path is never accidentally folded into another one. */
		private static String normalizeTablePath(final String pValue)
		{
			final String trimmed = Triple.trim(pValue);
			return trimmed != null && trimmed.startsWith("./") ? trimmed.substring(1) : trimmed;
		}

		@Override
		public boolean equals(final Object pOther)
		{
			if (!(pOther instanceof Triple))
			{
				return false;
			}
			final Triple other = (Triple) pOther;
			return Triple.eq(this.dataspaceKey, other.dataspaceKey) && Triple.eq(this.datasetName, other.datasetName) && Triple.eq(this.tablePath, other.tablePath);
		}

		@Override
		public int hashCode()
		{
			int h = this.dataspaceKey == null ? 0 : this.dataspaceKey.hashCode();
			h = h * 31 + (this.datasetName == null ? 0 : this.datasetName.hashCode());
			h = h * 31 + (this.tablePath == null ? 0 : this.tablePath.hashCode());
			return h;
		}

		@Override
		public String toString()
		{
			return this.dataspaceKey + "/" + this.datasetName + this.tablePath;
		}

		private static boolean eq(final String pA, final String pB)
		{
			return pA == null ? pB == null : pA.equals(pB);
		}
	}

	/**
	 * Whether {@code (pDataspaceKey, pDatasetName, pTablePath)} is one of {@code pAllowedTriples} - the ONE
	 * question the activation rule asks. <b>Fails CLOSED, never throws</b>: a null {@code pAllowedTriples}, a
	 * null argument, or any unexpected failure computing {@link Triple#equals}/{@link Triple#hashCode}
	 * answers {@code false} rather than propagating - defense in depth on top of the EBX-bound caller's own
	 * try/catch (coordinator review, 2026-09-22: "must never throw ... any exception → disabled").
	 */
	public static boolean matches(final Set<Triple> pAllowedTriples, final String pDataspaceKey, final String pDatasetName, final String pTablePath)
	{
		if (pAllowedTriples == null || pDataspaceKey == null || pDatasetName == null || pTablePath == null)
		{
			return false;
		}
		try
		{
			return pAllowedTriples.contains(new Triple(pDataspaceKey, pDatasetName, pTablePath));
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	/**
	 * The project code owning {@code (pDataspaceKey, pDatasetName, pTablePath)}, or {@code null} when the
	 * triple is not currently a promoted master table of any project. Sibling of {@link #matches} - same
	 * fail-CLOSED contract (a {@code null} answer, never a thrown exception, on any null argument or
	 * unexpected failure reading {@code pProjectByTriple}), same normalisation (via {@link Triple}'s own
	 * constructor) so a lookup triple and a cached triple never drift apart over incidental formatting.
	 * {@code pProjectByTriple} is never mutated here.
	 * <p>
	 * <b>No caller today</b> (2026-09-22, later the same day): this was written for {@code
	 * ebx.ui.MasterZoneProjectResolution}, the lookup a master table's own Cockpit/Proposal review toolbar
	 * button needed to resolve its project - both the button and the resolution class were removed when
	 * the product owner decided those two screens belong on the factory's own {@code Project} table instead
	 * (`docs/MASTER-ZONE.md` &sect;12, {@code master.MasterToolbars}'s own Javadoc). Kept, tested, harmless.
	 */
	public static String projectCodeFor(final Map<Triple, String> pProjectByTriple, final String pDataspaceKey, final String pDatasetName, final String pTablePath)
	{
		if (pProjectByTriple == null || pDataspaceKey == null || pDatasetName == null || pTablePath == null)
		{
			return null;
		}
		try
		{
			return pProjectByTriple.get(new Triple(pDataspaceKey, pDatasetName, pTablePath));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * Whether a cache last refreshed at {@code pCachedAt} (epoch millis, 0 meaning "never") is still fresh
	 * enough at {@code pNow} to skip a refresh, given a TTL of {@code pTtlMillis}. A negative or zero
	 * {@code pTtlMillis} always answers {@code false} (always refresh) rather than looping or dividing by
	 * zero anywhere upstream. {@code pNow} before {@code pCachedAt} (a clock adjustment) is treated the same
	 * as "not fresh" - never a negative "age" that could make a stale cache look fresher than it is.
	 */
	public static boolean isFresh(final long pCachedAt, final long pNow, final long pTtlMillis)
	{
		if (pTtlMillis <= 0L || pCachedAt <= 0L || pNow < pCachedAt)
		{
			return false;
		}
		return pNow - pCachedAt < pTtlMillis;
	}
}
