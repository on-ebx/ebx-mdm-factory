package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.addon.mame.common.NotFoundException;
import com.orchestranetworks.addon.mame.common.util.iterator.CloseableIterator;
import com.orchestranetworks.addon.mame.service.OperationsFactory;
import com.orchestranetworks.addon.mame.service.group.Group;
import com.orchestranetworks.addon.mame.service.group.GroupOperations;
import com.orchestranetworks.service.Session;

/**
 * Point lookup of ONE Match and Merge GROUP's own confirmed matches and pending suspects, through the
 * add-on's public Java API - facts measured live 2026-09-22 (instance 6.3.0-dev, add-on MAME 6.3.0-DEV),
 * `javap` against `ebx-addons.jar` (the same server project `ebx.MameMatchingApi`/`ebx.MameRecordState`
 * already compile against - no classpath change needed here either):
 * <pre>
 * public class com.orchestranetworks.addon.mame.service.OperationsFactory {
 *   public static GroupOperations getGroupOperations(Session);
 * }
 * public interface com.orchestranetworks.addon.mame.service.group.GroupOperations {
 *   Groups getGroupsWithActionRequired(AdaptationTable) throws InvalidStateException;
 *   Group getGroup(AdaptationTable, BigDecimal) throws NotFoundException;
 * }
 * public interface com.orchestranetworks.addon.mame.service.group.Group {
 *   AdaptationTable getTable();
 *   RecordIterable getMatches(); RecordIterable getSuspects();
 *   CloseableIterator&lt;Adaptation&gt; getMatchedRecords();
 *   CloseableIterator&lt;Adaptation&gt; getSuspectRecords();
 *   BigDecimal getGroupId(); String getGroupLabel();
 *   void removeMatches(Set&lt;Adaptation&gt;); void acceptSuspects(Set&lt;Adaptation&gt;);
 *   void acceptSuspectsAndMergeGroups(Set&lt;Adaptation&gt;); void rejectSuspects(Set&lt;Adaptation&gt;);
 *   List&lt;Adaptation&gt; getTransitiveMatches(String,String) throws InvalidParameterException;
 * }
 * public interface com.orchestranetworks.addon.mame.common.util.iterator.CloseableIterator&lt;T&gt;
 *   extends java.util.Iterator&lt;T&gt;, java.lang.AutoCloseable { void close(); }
 * </pre>
 * This class only ever reads (`getMatchedRecords`/`getSuspectRecords`/`getGroupLabel`); the write
 * operations of {@code Group} (`removeMatches`, `acceptSuspects`, `acceptSuspectsAndMergeGroups`,
 * `rejectSuspects`) are documented, not called - see {@code docs/RECORD-LINEAGE.md} "Confirming a
 * suspect" for the call sequence a later lot would use.
 * <p>
 * <b>Same {@code LinkageError} seam as the sibling {@code ebx.MameRecordState}</b> (read in full before
 * writing this class, per the task brief): only {@link AddonMameGroups} references {@code
 * com.orchestranetworks.addon.mame.*} types; {@link Holder#resolve} constructs it eagerly (forcing
 * classloading right there, not on the first real lookup) and falls back to a no-op implementation -
 * every group {@link Result#addonPresent} false - on {@link LinkageError}, so a server without {@code
 * ebx-addons.jar} degrades to {@code RecordLineage.MergeInfo#group} staying {@code null} instead of a
 * 500. {@link Result} and this interface's own {@link #read} signature stay free of add-on types (only
 * {@code Session}/{@code AdaptationTable}/{@code BigDecimal}/{@code Adaptation}/{@code String}/{@code
 * boolean} in and out), same discipline as {@code ebx.MameMatchingApi}'s own launch/simulate methods.
 * <p>
 * <b>Bounded, never a full scan of a possibly-huge group</b>: {@link #CAP} (50) records read per list
 * (matched and suspects each capped independently), with a {@code truncated} flag on {@link Result} set
 * the moment one more item than the cap is seen - the group could genuinely be larger (a real data-quality
 * cluster), but this screen only ever needs a representative sample, matching the task brief's own "cap at
 * 50 records each with a truncated flag".
 * <p>
 * <b>Every failure degrades to a {@link Result} field, never an exception reaching {@code
 * ebx.RecordLineageReader}</b>: {@code NotFoundException} (the add-on's own checked exception for "no such
 * group") answers {@link Result#found} false with no {@link Result#detail}; any other {@link
 * RuntimeException} (from {@code OperationsFactory.getGroupOperations}, {@code getGroup}, or iterating
 * either {@code CloseableIterator}) answers {@link Result#found} false with a {@link Result#detail} (the
 * exception's class and message, never a record value) - same discipline as {@code
 * RecordLineage.MergeInfo#METADATA_UNREADABLE}.
 */
interface MameGroups
{
	/** Read cap per list - see class Javadoc. */
	int CAP = 50;

	final class Result
	{
		/** False only when the add-on itself is absent from the classpath ({@link LinkageError}) - every other field is then meaningless (all null/empty/false). */
		final boolean addonPresent;
		/** True once a real {@code Group} was resolved for the requested id - false for "no such group" (add-on's own {@code NotFoundException}) and for every failure carrying a {@link #detail}. */
		final boolean found;
		/** {@code Group.getGroupLabel()} - null if unavailable or not resolved. */
		final String groupLabel;
		final List<Adaptation> matchedRecords;
		final boolean matchedTruncated;
		final List<Adaptation> suspectRecords;
		final boolean suspectTruncated;
		/** Non-null only when {@link #addonPresent} but {@link #found} is false for a reason OTHER than a plain "no such group" - an exception class + message, never a record value. */
		final String detail;

		private Result(final boolean pAddonPresent, final boolean pFound, final String pGroupLabel, final List<Adaptation> pMatchedRecords,
			final boolean pMatchedTruncated, final List<Adaptation> pSuspectRecords, final boolean pSuspectTruncated, final String pDetail)
		{
			this.addonPresent = pAddonPresent;
			this.found = pFound;
			this.groupLabel = pGroupLabel;
			this.matchedRecords = pMatchedRecords == null ? Collections.<Adaptation>emptyList() : pMatchedRecords;
			this.matchedTruncated = pMatchedTruncated;
			this.suspectRecords = pSuspectRecords == null ? Collections.<Adaptation>emptyList() : pSuspectRecords;
			this.suspectTruncated = pSuspectTruncated;
			this.detail = pDetail;
		}

		static Result addonAbsent()
		{
			return new Result(false, false, null, null, false, null, false, null);
		}

		static Result notFound()
		{
			return new Result(true, false, null, null, false, null, false, null);
		}

		static Result unreadable(final String pDetail)
		{
			return new Result(true, false, null, null, false, null, false, pDetail);
		}

		static Result of(final String pGroupLabel, final List<Adaptation> pMatchedRecords, final boolean pMatchedTruncated, final List<Adaptation> pSuspectRecords,
			final boolean pSuspectTruncated)
		{
			return new Result(true, true, pGroupLabel, pMatchedRecords, pMatchedTruncated, pSuspectRecords, pSuspectTruncated, null);
		}
	}

	Result read(Session pSession, AdaptationTable pTable, BigDecimal pGroupId);

	static MameGroups get()
	{
		return Holder.INSTANCE;
	}

	/** Resolved once (initialization-on-demand holder), like {@code MameRecordState.Holder}. */
	final class Holder
	{
		static final MameGroups INSTANCE = Holder.resolve();

		private Holder()
		{
		}

		private static MameGroups resolve()
		{
			try
			{
				return new AddonMameGroups();
			}
			catch (final LinkageError ex)
			{
				return new MameGroups()
				{
					@Override
					public Result read(final Session pSession, final AdaptationTable pTable, final BigDecimal pGroupId)
					{
						return Result.addonAbsent();
					}
				};
			}
		}
	}
}

/**
 * The only class of this module referencing {@code com.orchestranetworks.addon.mame.service.group.*} - see
 * {@link MameGroups}'s own Javadoc for why, and for the exact {@code javap} signatures this class relies
 * on.
 */
final class AddonMameGroups implements MameGroups
{
	AddonMameGroups()
	{
		// forces classloading of the add-on's OperationsFactory right here (constructor time, from
		// Holder.resolve), not lazily on the first real group lookup - same trick as AddonMameRecordState.
		OperationsFactory.class.getName();
	}

	@Override
	public Result read(final Session pSession, final AdaptationTable pTable, final BigDecimal pGroupId)
	{
		if (pTable == null || pGroupId == null)
		{
			return Result.notFound();
		}
		try
		{
			final GroupOperations operations = OperationsFactory.getGroupOperations(pSession);
			final Group group;
			try
			{
				group = operations.getGroup(pTable, pGroupId);
			}
			catch (final NotFoundException ex)
			{
				return Result.notFound();
			}
			if (group == null)
			{
				return Result.notFound();
			}
			String label;
			try
			{
				label = group.getGroupLabel();
			}
			catch (final RuntimeException ex)
			{
				label = null;
			}
			final Capped matched = AddonMameGroups.collect(group.getMatchedRecords());
			final Capped suspects = AddonMameGroups.collect(group.getSuspectRecords());
			return Result.of(label, matched.items, matched.truncated, suspects.items, suspects.truncated);
		}
		catch (final RuntimeException ex)
		{
			return Result.unreadable(ex.getClass().getName() + ": " + ex.getMessage());
		}
	}

	private static final class Capped
	{
		final List<Adaptation> items = new ArrayList<Adaptation>();
		boolean truncated;
	}

	/** Reads up to {@link MameGroups#CAP} items, closing the iterator either way - see class Javadoc for why this never throws past this point (the caller wraps the whole {@link #read} body). */
	private static Capped collect(final CloseableIterator<Adaptation> pIterator)
	{
		final Capped out = new Capped();
		if (pIterator == null)
		{
			return out;
		}
		try
		{
			while (pIterator.hasNext())
			{
				final Adaptation next = pIterator.next();
				if (out.items.size() >= MameGroups.CAP)
				{
					out.truncated = true;
					break;
				}
				out.items.add(next);
			}
		}
		finally
		{
			pIterator.close();
		}
		return out;
	}
}
