package com.on.ebx.factory.rejects;

import java.util.HashMap;
import java.util.Map;

/**
 * EBX refusal classes lot H4 (2026-10-17): the parent cascade of the MASTER stage, as the staging stage has it ({@code StagingLoader#rejectOrphans}). The rows a master load
 * rejects are remembered per entity and staging key, so a child row whose parent is among them is rejected with "parent rejected: <the parent's reason>" - the same reason code,
 * category ({@link RejectCategory#PARENT_REJECTED}) and wording the review screen, the wizard banner ({@link RejectCauses}) and the Cockpit give a child of a rejected staging
 * parent - instead of "not yet transferred to master", which is true but hides the cause. A grandchild passes the ROOT cause on ("parent rejected: parent rejected" is never built).
 * A parent that is not among the rejected rows of this load (not loaded yet, or rejected by an earlier load) gives {@link RejectReason#unknownMasterParent}.
 * <p>
 * Bounded: past {@link #MAX} remembered rows the rest are not kept (their children then read "has not reached master", which is also true). Pure, EBX-free.
 */
public final class RejectedParents
{
	/** The most rejected rows remembered over a whole load. */
	public static final int MAX = 100000;

	private final Map<String, Map<String, RejectReason>> byEntity = new HashMap<String, Map<String, RejectReason>>();
	private int kept;

	/** Remembers that the row {@code pStagingKey} of {@code pEntityName} was rejected for {@code pReason}. */
	public void record(final String pEntityName, final String pStagingKey, final RejectReason pReason)
	{
		if (pStagingKey == null || pReason == null || this.kept >= RejectedParents.MAX)
		{
			return;
		}
		Map<String, RejectReason> byKey = this.byEntity.get(pEntityName);
		if (byKey == null)
		{
			byKey = new HashMap<String, RejectReason>();
			this.byEntity.put(pEntityName, byKey);
		}
		if (byKey.put(pStagingKey, pReason) == null)
		{
			this.kept++;
		}
	}

	/** The reason this load rejected the row {@code pStagingKey} of {@code pEntityName}, null when it did not. */
	public RejectReason rejected(final String pEntityName, final String pStagingKey)
	{
		final Map<String, RejectReason> byKey = this.byEntity.get(pEntityName);
		return byKey == null || pStagingKey == null ? null : byKey.get(pStagingKey);
	}

	/**
	 * Why a row cannot take its parent: the parent's own reason when this load rejected it, else "the parent has not reached master".
	 *
	 * @param pField the master field holding the reference
	 * @param pStagingField the staging field the reference is read from
	 * @param pParentEntity the parent's entity
	 * @param pParentStagingKey the parent's staging key (what the child's reference holds)
	 */
	public RejectReason forChild(final String pField, final String pStagingField, final String pParentEntity, final String pParentStagingKey)
	{
		final RejectReason parent = this.rejected(pParentEntity, pParentStagingKey);
		if (parent == null)
		{
			return RejectReason.unknownMasterParent(pField, pStagingField, pParentEntity, pParentStagingKey);
		}
		return RejectReason.PARENT.equals(parent.code) ? parent : RejectReason.parentRejected(pParentEntity, parent);
	}

	/** The number of rows remembered. */
	public int size()
	{
		return this.kept;
	}
}
