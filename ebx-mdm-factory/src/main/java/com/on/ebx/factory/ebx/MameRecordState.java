package com.on.ebx.factory.ebx;

import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.addon.mame.common.util.metadata.MetadataHelper;
import com.orchestranetworks.addon.mame.model.State;

/**
 * Point lookup of one record's own Match and Merge state, through the add-on's public Java API
 * ({@code com.orchestranetworks.addon.mame.common.util.metadata.MetadataHelper} - CLAUDE.md &sect;8 fact
 * "A record's Match and Merge state and golden record are readable from Java through a public API"). Every
 * signature used here was verified by {@code javap} against {@code ebx-addons.jar} on this instance's server
 * project, 2026-09-21:
 * <pre>
 * public class com.orchestranetworks.addon.mame.common.util.metadata.MetadataHelper {
 *   public static java.math.BigDecimal getGroupId(Adaptation) throws InvalidParameterException, PathAccessException;
 *   public static State getState(Adaptation) throws InvalidParameterException, PathAccessException;
 *   public static boolean isAutoCreated(Adaptation) throws InvalidParameterException, PathAccessException;
 *   public static Adaptation getTargetRecord(Adaptation) throws InvalidParameterException, PathAccessException, InvalidStateException;
 * }
 * public final class com.orchestranetworks.addon.mame.model.State extends Enum&lt;State&gt; {
 *   UNSET, GOLDEN, MERGED, PENDING_APPROVAL, DELETED;
 * }
 * </pre>
 * <p>
 * <b>Same {@code LinkageError} seam as the sibling {@code ebx-connectors} module's own {@code
 * GoldenRecords}/{@code MameGoldenRecords}</b> (`~/Dev/ebx-connectors`, read in full before writing this
 * class, per the task brief): only {@link AddonMameRecordState} references {@code
 * com.orchestranetworks.addon.mame.*} types; {@link Holder#resolve} constructs it eagerly (forcing
 * classloading right there, not on the first record) and falls back to a no-op implementation - every record
 * {@link Result#UNAVAILABLE} - on {@link LinkageError}, so a server without {@code ebx-addons.jar} on its
 * classpath degrades to {@code RecordLineage.MergeInfo#ADDON_NOT_AVAILABLE} instead of a 500. {@code
 * ebx-addons.jar} is already on THIS module's compile/test classpath the same way {@code ebx-lib.jar} is -
 * both come from the {@code server_lib/*} wildcard {@code build.sh}/{@code tools/test_local.sh} already pass
 * to {@code javac} (confirmed: {@code ebx.MameMatchingApi}, merged earlier the same day, already imports
 * {@code com.orchestranetworks.addon.mame.service.*} from the identical wildcard with no separate classpath
 * entry) - no build file needed touching for this class either.
 * <p>
 * Deliberately narrower than {@code GoldenRecords}: this class answers ONE record's own state/group/
 * auto-created/golden-target, nothing about "does this table have Match and Merge configured at all" (that
 * is {@code ebx.RecordLineageReader}'s own job, decided from the entity's {@code MatchPlan.status} - see its
 * Javadoc for why {@code MatchPlan != APPLIED} is checked BEFORE this class is ever called, so {@code
 * RecordLineage.MergeInfo#NO_MATCHING_YET} never depends on the add-on answering anything).
 */
interface MameRecordState
{
	final class Result
	{
		/** False only when the add-on itself is absent from the classpath ({@link LinkageError}) - {@code RecordLineage.MergeInfo#ADDON_NOT_AVAILABLE}. */
		final boolean addonPresent;
		/** The add-on's own {@code State} name ({@code UNSET}/{@code GOLDEN}/{@code MERGED}/{@code PENDING_APPROVAL}/{@code DELETED}), or null when {@link #addonPresent} but this record carries no metadata - {@code RecordLineage.MergeInfo#NO_METADATA}. Meaningless (and always null) when {@code !addonPresent}. */
		final String state;
		/** {@code MetadataHelper.getGroupId}, as plain text ({@code BigDecimal.toPlainString()}) - null if unavailable or not read. */
		final String groupId;
		final boolean autoCreated;
		/** The formatted primary key of {@code MetadataHelper.getTargetRecord} - only set when {@link #state} is {@code MERGED} and the target could be resolved. */
		final String goldenKey;

		private Result(final boolean pAddonPresent, final String pState, final String pGroupId, final boolean pAutoCreated, final String pGoldenKey)
		{
			this.addonPresent = pAddonPresent;
			this.state = pState;
			this.groupId = pGroupId;
			this.autoCreated = pAutoCreated;
			this.goldenKey = pGoldenKey;
		}

		static Result addonAbsent()
		{
			return new Result(false, null, null, false, null);
		}

		static Result noMetadata()
		{
			return new Result(true, null, null, false, null);
		}

		static Result of(final String pState, final String pGroupId, final boolean pAutoCreated, final String pGoldenKey)
		{
			return new Result(true, pState, pGroupId, pAutoCreated, pGoldenKey);
		}
	}

	Result read(Adaptation pRecord);

	/**
	 * Volume lot 3 (2026-09-28, docs/VOLUME.md task 5, "a MameRecordState read variant that fetches only the
	 * state"): {@code ebx.MasterLoader.RecordMetadataMergeCheck.isMergedAway} only ever needs the record's
	 * {@code state} name, never its group id/auto-created flag/golden target - the full {@link #read} pays for
	 * all four add-on calls ({@code getState}, {@code getGroupId}, {@code isAutoCreated}, and, only when
	 * {@code MERGED}, {@code getTargetRecord}) on every single call, most of it thrown away by that one caller.
	 * Default implementation delegates to {@link #read} for any implementation (a future one, or a test double)
	 * that does not override it - correct, just not the point of this method; {@link AddonMameRecordState}
	 * overrides it with the single, cheaper {@code MetadataHelper.getState} call.
	 *
	 * @return the add-on's own {@code State} name ({@code UNSET}/{@code GOLDEN}/{@code MERGED}/{@code
	 *         PENDING_APPROVAL}/{@code DELETED}), or {@code null} when the add-on is absent, the record carries
	 *         no metadata, or the lookup itself failed - never throws.
	 */
	default String readStateOnly(final Adaptation pRecord)
	{
		return this.read(pRecord).state;
	}

	static MameRecordState get()
	{
		return Holder.INSTANCE;
	}

	/** Resolved once (initialization-on-demand holder), like {@code GoldenRecords.Holder}. */
	final class Holder
	{
		static final MameRecordState INSTANCE = Holder.resolve();

		private Holder()
		{
		}

		private static MameRecordState resolve()
		{
			try
			{
				return new AddonMameRecordState();
			}
			catch (final LinkageError ex)
			{
				return new MameRecordState()
				{
					@Override
					public Result read(final Adaptation pRecord)
					{
						return Result.addonAbsent();
					}
				};
			}
		}
	}
}

/**
 * The only class of this module referencing {@code com.orchestranetworks.addon.mame.*} - see {@link
 * MameRecordState}'s own Javadoc for why, and for the exact {@code javap} signatures this class relies on.
 */
final class AddonMameRecordState implements MameRecordState
{
	AddonMameRecordState()
	{
		// forces classloading of the add-on right here (constructor time, from Holder.resolve), not lazily on
		// the first real record lookup - same trick as MameGoldenRecords, so a missing ebx-addons.jar throws
		// NoClassDefFoundError exactly where the LinkageError catch is, never surfacing mid-read.
		State.UNSET.name();
	}

	/** Volume lot 3 (2026-09-28, task 5): the ONE add-on call {@link #read} would otherwise pay three more for - see {@link MameRecordState#readStateOnly}'s own Javadoc for the caller this exists for. */
	@Override
	public String readStateOnly(final Adaptation pRecord)
	{
		if (pRecord == null)
		{
			return null;
		}
		try
		{
			final State state = MetadataHelper.getState(pRecord);
			return state == null ? null : state.name();
		}
		catch (final Exception ex) // same "no metadata, not a hard failure" posture as read() below
		{
			return null;
		}
	}

	@Override
	public Result read(final Adaptation pRecord)
	{
		if (pRecord == null)
		{
			return Result.noMetadata();
		}
		final State state;
		try
		{
			state = MetadataHelper.getState(pRecord);
		}
		catch (final Exception ex) // InvalidParameterException (checked) / PathAccessException (unchecked) - undocumented for a record the add-on never touched: treated as "no metadata", not a hard failure
		{
			return Result.noMetadata();
		}
		if (state == null)
		{
			return Result.noMetadata();
		}
		String groupId = null;
		try
		{
			final java.math.BigDecimal raw = MetadataHelper.getGroupId(pRecord);
			groupId = raw == null ? null : raw.toPlainString();
		}
		catch (final Exception ex)
		{
			groupId = null;
		}
		boolean autoCreated = false;
		try
		{
			autoCreated = MetadataHelper.isAutoCreated(pRecord);
		}
		catch (final Exception ex)
		{
			autoCreated = false;
		}
		String goldenKey = null;
		if (state == State.MERGED)
		{
			try
			{
				final Adaptation target = MetadataHelper.getTargetRecord(pRecord);
				goldenKey = target == null ? null : target.getOccurrencePrimaryKey().format();
			}
			catch (final Exception ex)
			{
				goldenKey = null;
			}
		}
		return Result.of(state.name(), groupId, autoCreated, goldenKey);
	}
}
