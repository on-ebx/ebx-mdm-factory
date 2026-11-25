package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryRight;
import com.on.ebx.factory.rejects.RejectCsv;
import com.on.ebx.factory.rejects.RejectFilter;
import com.on.ebx.factory.rejects.RejectRef;
import com.on.ebx.factory.rejects.RejectRow;
import com.on.ebx.factory.rejects.RejectSql;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.ValueContextForUpdate;
import com.orchestranetworks.query.Query;
import com.orchestranetworks.query.QueryResult;
import com.orchestranetworks.query.Tuple;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;

/**
 * The steward's handling of the transfer rejects of a project (audit F-38, wave 10): summaries, pages and the CSV export are EBX SQL
 * ({@code Adaptation.createQuery}, text in {@link RejectSql}) - the database counts, filters and sorts; nothing reads every reject of a
 * project into memory - and the two actions, <i>acknowledge</i> (a comment is required) and <i>reopen</i>, record the user and the time on the
 * row. Reading is the caller's own business (the screen asks VIEW, the query runs under the caller's session, so EBX's own permissions
 * apply); an action asks {@link FactoryRight#STEWARD} here, whoever calls. The write itself runs with all privileges after that check,
 * like every other bookkeeping write of the factory (the rows are the factory's own; the dataset's permission rules keep them out of
 * everybody else's reach).
 * <p>
 * An acknowledged reject is set aside, not fixed: it keeps counting as "held back" in the load grids (the row was NOT loaded), and
 * {@link FactoryRetention} deletes it with the resolved ones when it is older than the retention; a later rejection of the same row
 * ({@code StagingLoader}/{@code MasterLoader}) opens it again. Open ones are never purged.
 */
public final class RejectReview
{
	/** Rows of a screen page. */
	public static final int PAGE_SIZE = 50;
	/** Rows of an export page (and of the key reads of a bulk action). */
	static final int EXPORT_PAGE = 1000;
	/** Rows per write transaction. */
	static final int BATCH = 500;
	/**
	 * F2-103: above this many rows an export or a bulk action is a job ({@link RejectBulkRun}), never done inside the request. Default 10 000, set by
	 * {@code -Debx.factory.rejectInlineMax=<n>} (read at each call).
	 */
	public static final int INLINE_MAX_DEFAULT = 10000;
	/** F2-103: an export file never holds more rows than this (default 1 000 000, {@code -Debx.factory.rejectExportMaxRows}). */
	public static final int EXPORT_MAX_DEFAULT = 1000000;
	/** The comment of a bulk action and of one row is cut here (the field's own limit). */
	public static final int COMMENT_MAX = 2000;

	private RejectReview()
	{
	}

	/** Counts of one (entity, stage) cell. */
	public static final class Summary
	{
		public String entityId = "";
		public String entityName = "";
		public String stage = "";
		public long open;
		public long acknowledged;
		public long resolved;

		public long total()
		{
			return this.open + this.acknowledged + this.resolved;
		}
	}

	/**
	 * Staging load rejects (2026-10-17): what the rows of a filter have in common - one KIND of reason of one entity ({@code reasonCode}, "" for the rejects written before reasons
	 * were coded), how many rows, and one row's own reasons and raw detail to word it and show an example.
	 */
	public static final class Group
	{
		public String entityId = "";
		public String entityName = "";
		public String reasonCode = "";
		public long count;
		public String sampleReasons = "";
		public String sampleDetail = "";
		public String sampleCategory = "";
		/** Staging last points (2026-10-17): the rows of the group by status (they add up to {@link #count}; all 0 for a group built without them). */
		public long open;
		public long acknowledged;
		public long resolved;

		/** True when a later load resolved every row of the group: nothing is left to look at. */
		public boolean resolvedOnly()
		{
			return this.resolved > 0L && this.open + this.acknowledged == 0L;
		}
	}

	// ====================================================================================================== reads

	/** The biggest groups of {@code pLimit} at most ({@link RejectSql#groups}), ONE aggregate query, following the filter. */
	public static List<Group> groups(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter, final int pLimit)
		throws OperationException
	{
		final Map<String, String> names = UserView.scoped(UserView.of(pSession), () -> RejectReview.entityNames(pFactory, pProjectCode));
		final List<Group> out = new ArrayList<Group>();
		RejectReview.run(pSession, pFactory, RejectSql.groups(pProjectCode, pFilter, pLimit), new TupleReader()
		{
			@Override
			public void read(final Tuple pTuple)
			{
				final Group group = new Group();
				group.entityId = RejectReview.text(pTuple.get("ENT"));
				group.entityName = names.containsKey(group.entityId) ? names.get(group.entityId) : group.entityId;
				group.reasonCode = RejectReview.text(pTuple.get("RCD"));
				group.count = RejectReview.number(pTuple.get("N"));
				group.open = RejectReview.number(pTuple.get("OPN"));
				group.acknowledged = RejectReview.number(pTuple.get("ACK"));
				group.resolved = RejectReview.number(pTuple.get("RSV"));
				group.sampleReasons = RejectReview.text(pTuple.get("RSN"));
				group.sampleDetail = RejectReview.text(pTuple.get("DET"));
				group.sampleCategory = RejectReview.text(pTuple.get("CAT"));
				out.add(group);
			}
		});
		return out;
	}

	/** How many groups the filter has altogether ({@link RejectSql#groupCount}). */
	public static long groupCount(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter) throws OperationException
	{
		final long[] total = new long[1];
		RejectReview.run(pSession, pFactory, RejectSql.groupCount(pProjectCode, pFilter), new TupleReader()
		{
			@Override
			public void read(final Tuple pTuple)
			{
				total[0] = RejectReview.number(pTuple.get("N"));
			}
		});
		return total[0];
	}

	/** Open / acknowledged / resolved per entity and stage, ONE aggregate query. Entity ids without a row any more keep their id as the name. */
	public static List<Summary> summary(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Map<String, String> names = UserView.scoped(UserView.of(pSession), () -> RejectReview.entityNames(pFactory, pProjectCode)); // the names the user may see
		final List<Summary> out = new ArrayList<Summary>();
		RejectReview.run(pSession, pFactory, RejectSql.summary(pProjectCode), new TupleReader()
		{
			@Override
			public void read(final Tuple pTuple)
			{
				final String entityId = RejectReview.text(pTuple.get("ENT"));
				RejectReview.addSummary(out, entityId, names.containsKey(entityId) ? names.get(entityId) : entityId, RejectReview.text(pTuple.get("STG")),
					RejectReview.number(pTuple.get("OPN")), RejectReview.number(pTuple.get("ACK")), RejectReview.number(pTuple.get("RSV")));
			}
		});
		return out;
	}

	/**
	 * Robustness audit F2-105: a tuple of an (entity, stage) already in {@code pOut} is ADDED to it, never listed again or overwritten, so the counts stay right even if the SQL
	 * engine split a group (product-bugs/2026-10-06, a GROUP BY on a foreign key). Pure, package-private for the test.
	 */
	static void addSummary(final List<Summary> pOut, final String pEntityId, final String pEntityName, final String pStage, final long pOpen, final long pAcknowledged,
		final long pResolved)
	{
		Summary summary = null;
		for (final Summary candidate : pOut)
		{
			if (candidate.entityId.equals(pEntityId) && candidate.stage.equals(pStage))
			{
				summary = candidate;
				break;
			}
		}
		if (summary == null)
		{
			summary = new Summary();
			summary.entityId = pEntityId;
			summary.entityName = pEntityName;
			summary.stage = pStage;
			pOut.add(summary);
		}
		summary.open += pOpen;
		summary.acknowledged += pAcknowledged;
		summary.resolved += pResolved;
	}

	/** How many rows a filter matches. */
	public static long count(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter) throws OperationException
	{
		final long[] total = new long[1];
		RejectReview.run(pSession, pFactory, RejectSql.count(pProjectCode, pFilter), new TupleReader()
		{
			@Override
			public void read(final Tuple pTuple)
			{
				total[0] = RejectReview.number(pTuple.get("N"));
			}
		});
		return total[0];
	}

	/** One page of rows, most recent first, entity names filled. */
	public static List<RejectRow> page(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter, final int pOffset,
		final int pLimit) throws OperationException
	{
		final Map<String, String> names = UserView.scoped(UserView.of(pSession), () -> RejectReview.entityNames(pFactory, pProjectCode)); // the names the user may see
		final List<RejectRow> out = new ArrayList<RejectRow>();
		RejectReview.run(pSession, pFactory, RejectSql.page(pProjectCode, pFilter, pOffset, pLimit), new TupleReader()
		{
			@Override
			public void read(final Tuple pTuple)
			{
				out.add(RejectReview.row(pTuple, names));
			}
		});
		return out;
	}

	/** F2-103: the inline threshold of exports and bulk actions. */
	public static int inlineMax()
	{
		return RejectReview.positive("ebx.factory.rejectInlineMax", RejectReview.INLINE_MAX_DEFAULT);
	}

	/** F2-103: the row cap of an export file. */
	public static int exportMaxRows()
	{
		return RejectReview.positive("ebx.factory.rejectExportMaxRows", RejectReview.EXPORT_MAX_DEFAULT);
	}

	private static int positive(final String pProperty, final int pDefault)
	{
		final int value = Integer.getInteger(pProperty, Integer.valueOf(pDefault)).intValue();
		return value > 0 ? value : pDefault;
	}

	/**
	 * Streams the CSV of everything a filter matches (a header, then keyset pages of {@link #EXPORT_PAGE} rows, in key order) into {@code pOut}: UTF-8 with a byte-order mark
	 * (a spreadsheet then reads the accents), never more than one page in memory. Returns the number of rows written. F2-103: the pages follow the key ({@link RejectSql#exportPage}),
	 * not an {@code OFFSET}, so the cost is linear in the rows.
	 */
	public static long exportCsv(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter, final OutputStream pOut)
		throws OperationException, IOException
	{
		return RejectReview.exportCsv(pSession, pFactory, pProjectCode, pFilter, pOut, 0L, null);
	}

	/** The same with a row cap ({@code pMaxRows}, 0 = none) and a listener that hears each page and may stop the export ({@code pProgress}, null = nobody). */
	public static long exportCsv(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter, final OutputStream pOut,
		final long pMaxRows, final BulkProgress pProgress) throws OperationException, IOException
	{
		return RejectReview.exportCsv(pSession, pFactory, pProjectCode, pFilter, pOut, pMaxRows, pProgress, null, null);
	}

	/**
	 * The same, for a job that runs as the system session on behalf of a person: {@code pVisibility} hides the rows the person may not read (never written to the file), their number
	 * is added to {@code pHiddenOut[0]} (null = not counted).
	 */
	public static long exportCsv(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter, final OutputStream pOut,
		final long pMaxRows, final BulkProgress pProgress, final Visibility pVisibility, final long[] pHiddenOut) throws OperationException, IOException
	{
		final Map<String, String> names = UserView.scoped(UserView.of(pSession), () -> RejectReview.entityNames(pFactory, pProjectCode)); // the names the user may see
		return RejectReview.streamExport(new RowSource()
		{
			@Override
			public List<RejectRow> next(final RejectRef pAfter, final int pLimit) throws OperationException
			{
				final List<RejectRow> rows = new ArrayList<RejectRow>();
				RejectReview.run(pSession, pFactory, RejectSql.exportPage(pProjectCode, pFilter, pAfter, pLimit), new TupleReader()
				{
					@Override
					public void read(final Tuple pTuple)
					{
						rows.add(RejectReview.row(pTuple, names));
					}
				});
				return rows;
			}
		}, pOut, pMaxRows, pProgress, pVisibility, pHiddenOut);
	}

	/** F2-103: the rows after a key, at most a limit - what the export reads (the SQL page in production, a fake in a test). */
	interface RowSource
	{
		List<RejectRow> next(RejectRef pAfter, int pLimit) throws OperationException;
	}

	/** F2-103 (robustness audit, permissions rule): whether the person a job acts for may read a reject; null everywhere means "all". */
	public interface Visibility
	{
		boolean sees(RejectRef pRef);
	}

	/**
	 * F2-103 (pure, tested): writes the header and the keyset pages through {@code pSource}; a row {@code pVisibility} refuses is neither written nor counted as written, only added
	 * to {@code pHiddenOut[0]}. Returns the rows written; the page cap {@code pMaxRows} counts rows written.
	 */
	static long streamExport(final RowSource pSource, final OutputStream pOut, final long pMaxRows, final BulkProgress pProgress, final Visibility pVisibility, final long[] pHiddenOut)
		throws OperationException, IOException
	{
		pOut.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
		pOut.write(RejectCsv.header().getBytes(StandardCharsets.UTF_8));
		long written = 0L;
		long scanned = 0L;
		RejectRef after = null;
		for (;;)
		{
			final int limit = RejectReview.EXPORT_PAGE;
			final List<RejectRow> rows = pSource.next(after, limit);
			for (final RejectRow row : rows)
			{
				if (pVisibility != null && !pVisibility.sees(new RejectRef(row.entityId, row.stage, row.sourceSystem, row.sourceId)))
				{
					if (pHiddenOut != null)
					{
						pHiddenOut[0]++;
					}
					continue;
				}
				if (pMaxRows > 0L && written >= pMaxRows)
				{
					return written;
				}
				pOut.write(RejectCsv.line(row).getBytes(StandardCharsets.UTF_8));
				written++;
			}
			scanned += rows.size();
			if (pProgress != null)
			{
				pProgress.done(scanned, written);
			}
			if (rows.size() < limit || (pMaxRows > 0L && written >= pMaxRows) || (pProgress != null && pProgress.cancelled()))
			{
				return written;
			}
			final RejectRow last = rows.get(rows.size() - 1);
			after = new RejectRef(last.entityId, last.stage, last.sourceSystem, last.sourceId);
		}
	}

	/**
	 * The rejects a person may read, by the public {@code SessionPermissions} of that login ({@code Repository.createSessionPermissionsForUser}): a reject the person's access rights
	 * hide - the record, or its table - is out. A row that no longer exists is out too. Used by the jobs, which run as the system session.
	 */
	public static Visibility visibilityFor(final Adaptation pFactory, final String pProjectCode, final String pUser)
	{
		final com.orchestranetworks.service.SessionPermissions permissions = pFactory.getHome().getRepository()
			.createSessionPermissionsForUser(com.orchestranetworks.service.UserReference.forUser(pUser));
		final AdaptationTable table = pFactory.getTable(FactoryPaths.TRANSFER_REJECT);
		return new Visibility()
		{
			@Override
			public boolean sees(final RejectRef pRef)
			{
				final Adaptation row = table.lookupAdaptationByPrimaryKey(table.computePrimaryKey(new Object[] { pProjectCode, pRef.entityId, pRef.stage, pRef.sourceSystem, pRef.sourceId }));
				return row != null && !permissions.getAdaptationAccessPermission(row).isHidden()
					&& !permissions.getNodeAccessPermission(table.getTableOccurrenceRootNode(), row).isHidden();
			}
		};
	}

	/** F2-103 (pure): the keys of a batch the person may read; the others are added to {@code pHiddenOut[0]}. */
	static List<RejectRef> visibleOnly(final List<RejectRef> pBatch, final Visibility pVisibility, final long[] pHiddenOut)
	{
		if (pVisibility == null)
		{
			return pBatch;
		}
		final List<RejectRef> out = new ArrayList<RejectRef>();
		for (final RejectRef ref : pBatch)
		{
			if (pVisibility.sees(ref))
			{
				out.add(ref);
			}
			else if (pHiddenOut != null)
			{
				pHiddenOut[0]++;
			}
		}
		return out;
	}

	/** F2-103: what a long export or bulk action tells its caller (the job): how far it is, and whether it must stop. */
	public interface BulkProgress
	{
		/** Rows read so far, rows changed so far. */
		void done(long pScanned, long pChanged);

		boolean cancelled();
	}

	// ==================================================================================================== actions

	/** Acknowledges the given rows; a comment is required. Returns how many rows changed (a row already acknowledged or resolved is left as it is). */
	public static int acknowledge(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<RejectRef> pRefs, final String pComment)
		throws OperationException
	{
		final String comment = RejectReview.requireComment(pSession, pComment);
		return RejectReview.write(pSession, pFactory, pProjectCode, pRefs, true, comment);
	}

	/** Reopens the given rows (acknowledged back to open); the comment is optional. */
	public static int reopen(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<RejectRef> pRefs, final String pComment)
		throws OperationException
	{
		FactoryAccess.require(pSession, FactoryRight.STEWARD, "reopening transfer rejects");
		return RejectReview.write(pSession, pFactory, pProjectCode, pRefs, false, RejectReview.cut(pComment));
	}

	/**
	 * Acknowledges every OPEN row the filter matches (the status of the filter is forced to open: a row already acknowledged or resolved is never touched).
	 * F2-103: the keys are read by keyset pages of {@link #BATCH} and each page is written before the next is read - never the whole key list in memory.
	 */
	public static int acknowledgeMatching(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter, final String pComment)
		throws OperationException
	{
		return RejectReview.acknowledgeMatching(pSession, pFactory, pProjectCode, pFilter, pComment, null, null, null, null);
	}

	/**
	 * The same, with a listener that hears each batch and may stop between two (the job's counter). {@code pOnBehalfOf}: the login recorded as the reviewer when a job runs it for a
	 * person (null = the session's own user); {@code pVisibility}: the rows that person may read - a hidden row is not modified, only counted in {@code pHiddenOut[0]}.
	 */
	public static int acknowledgeMatching(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter, final String pComment,
		final String pOnBehalfOf, final BulkProgress pProgress, final Visibility pVisibility, final long[] pHiddenOut) throws OperationException
	{
		final String comment = RejectReview.requireComment(pSession, pComment);
		final RejectFilter open = RejectReview.copy(pFilter);
		open.status = RejectFilter.OPEN;
		return RejectReview.sweepWrite(pSession, pFactory, pProjectCode, open, true, comment, pOnBehalfOf, pProgress, pVisibility, pHiddenOut);
	}

	/** Reopens every ACKNOWLEDGED row the filter matches. */
	public static int reopenMatching(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter, final String pComment)
		throws OperationException
	{
		return RejectReview.reopenMatching(pSession, pFactory, pProjectCode, pFilter, pComment, null, null, null, null);
	}

	public static int reopenMatching(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter, final String pComment,
		final String pOnBehalfOf, final BulkProgress pProgress, final Visibility pVisibility, final long[] pHiddenOut) throws OperationException
	{
		FactoryAccess.require(pSession, FactoryRight.STEWARD, "reopening transfer rejects");
		final RejectFilter acknowledged = RejectReview.copy(pFilter);
		acknowledged.status = RejectFilter.ACKNOWLEDGED;
		return RejectReview.sweepWrite(pSession, pFactory, pProjectCode, acknowledged, false, RejectReview.cut(pComment), pOnBehalfOf, pProgress, pVisibility, pHiddenOut);
	}

	/** F2-103: where a sweep gets its keys from - the page of keys after {@code pAfter} (null = the first), at most {@code pLimit}. */
	interface KeySource
	{
		List<RejectRef> next(RejectRef pAfter, int pLimit) throws OperationException;
	}

	/** F2-103: what a sweep does with one batch of keys - returns how many rows changed. */
	interface BatchWriter
	{
		int write(List<RejectRef> pBatch) throws OperationException;
	}

	/**
	 * F2-103 (pure, tested): walks the keys page by page ({@code pBatch} per page, each strictly after the last key of the one before) and hands each page to the writer, so a million
	 * rejects are 2 000 writes of 500 and never a list of a million keys. The writes change what the filter matches, but the walk only moves forward in key order, so it neither
	 * skips nor repeats. Returns the rows changed; {@code pProgress} hears each batch and may stop the walk between two batches.
	 */
	static int sweep(final KeySource pSource, final BatchWriter pWriter, final int pBatch, final BulkProgress pProgress) throws OperationException
	{
		int changed = 0;
		long scanned = 0L;
		RejectRef after = null;
		for (;;)
		{
			final List<RejectRef> keys = pSource.next(after, pBatch);
			if (keys.isEmpty())
			{
				return changed;
			}
			changed += pWriter.write(keys);
			scanned += keys.size();
			if (pProgress != null)
			{
				pProgress.done(scanned, changed);
				if (pProgress.cancelled())
				{
					return changed;
				}
			}
			if (keys.size() < pBatch)
			{
				return changed;
			}
			after = keys.get(keys.size() - 1);
		}
	}

	private static int sweepWrite(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter, final boolean pAcknowledge,
		final String pComment, final String pOnBehalfOf, final BulkProgress pProgress, final Visibility pVisibility, final long[] pHiddenOut) throws OperationException
	{
		final int changed = RejectReview.sweep(new KeySource()
		{
			@Override
			public List<RejectRef> next(final RejectRef pAfter, final int pLimit) throws OperationException
			{
				return RejectReview.keys(pSession, pFactory, pProjectCode, pFilter, pAfter, pLimit);
			}
		}, new BatchWriter()
		{
			@Override
			public int write(final List<RejectRef> pBatch) throws OperationException
			{
				final List<RejectRef> visible = RejectReview.visibleOnly(pBatch, pVisibility, pHiddenOut);
				return visible.isEmpty() ? 0 : RejectReview.writeBatch(pSession, pFactory, pProjectCode, visible, pAcknowledge, pComment, pOnBehalfOf);
			}
		}, RejectReview.BATCH, pProgress);
		RejectReview.logChanged(pOnBehalfOf != null ? pOnBehalfOf : RejectReview.userOf(pSession), pProjectCode, changed, pAcknowledge);
		return changed;
	}

	private static String requireComment(final Session pSession, final String pComment) throws OperationException
	{
		FactoryAccess.require(pSession, FactoryRight.STEWARD, "acknowledging transfer rejects");
		final String comment = RejectReview.cut(pComment);
		if (comment.isEmpty())
		{
			throw EngineRefusal.coded("invalid.rejectComment", "A comment is required to acknowledge a transfer reject.");
		}
		return comment;
	}

	private static List<RejectRef> keys(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter, final RejectRef pAfter,
		final int pLimit) throws OperationException
	{
		final List<RejectRef> out = new ArrayList<RejectRef>();
		RejectReview.run(pSession, pFactory, RejectSql.keys(pProjectCode, pFilter, pAfter, pLimit), new TupleReader()
		{
			@Override
			public void read(final Tuple pTuple)
			{
				out.add(new RejectRef(RejectReview.text(pTuple.get("ENT")), RejectReview.text(pTuple.get("STG")), RejectReview.text(pTuple.get("SYS")),
					RejectReview.text(pTuple.get("SID"))));
			}
		});
		return out;
	}

	private static int write(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<RejectRef> pRefs, final boolean pAcknowledge,
		final String pComment) throws OperationException
	{
		int changed = 0;
		for (int from = 0; from < pRefs.size(); from += RejectReview.BATCH)
		{
			changed += RejectReview.writeBatch(pSession, pFactory, pProjectCode, pRefs.subList(from, Math.min(pRefs.size(), from + RejectReview.BATCH)), pAcknowledge, pComment, null);
		}
		RejectReview.logChanged(RejectReview.userOf(pSession), pProjectCode, changed, pAcknowledge);
		return changed;
	}

	/** One write transaction for one batch of at most {@link #BATCH} rows; returns how many rows changed. */
	private static int writeBatch(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<RejectRef> pBatch, final boolean pAcknowledge,
		final String pComment, final String pOnBehalfOf) throws OperationException
	{
		final AdaptationTable table = pFactory.getTable(FactoryPaths.TRANSFER_REJECT);
		// second permissions pass (2026-10-14): the refs come from the page (a selection) or from a query run with the user's session; each row is
		// looked up again with the user's view and a row the user cannot see is skipped like a row that is gone - the write itself is the module's
		// own bookkeeping (who reviewed, when), done with all privileges only for rows the user sees
		final UserView view = UserView.of(pSession);
		final String user = pOnBehalfOf != null ? pOnBehalfOf : RejectReview.userOf(pSession);
		final Date now = new Date();
		{
			final List<RejectRef> batch = pBatch;
			final int[] count = new int[1];
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true); // the right was checked above; the rows are the factory's own bookkeeping
					pContext.setExecutionInformation("mdm-factory:reject-review:" + (pAcknowledge ? "acknowledge" : "reopen") + ":" + user);
					for (final RejectRef ref : batch)
					{
						final Adaptation row = table
							.lookupAdaptationByPrimaryKey(table.computePrimaryKey(new Object[] { pProjectCode, ref.entityId, ref.stage, ref.sourceSystem, ref.sourceId }));
						if (row == null || !view.recordVisible(row) || Boolean.TRUE.equals(row.get(FactoryPaths.TransferReject.RESOLVED)))
						{
							continue; // gone (retention, a reset) or fixed by a load meanwhile
						}
						final boolean already = Boolean.TRUE.equals(row.get(FactoryPaths.TransferReject.ACKNOWLEDGED));
						if (already == pAcknowledge)
						{
							continue;
						}
						final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
						value.setValue(Boolean.valueOf(pAcknowledge), FactoryPaths.TransferReject.ACKNOWLEDGED);
						value.setValue(user, FactoryPaths.TransferReject.REVIEWED_BY);
						value.setValue(now, FactoryPaths.TransferReject.REVIEWED_AT);
						value.setValue(pComment.isEmpty() ? null : pComment, FactoryPaths.TransferReject.REVIEW_COMMENT);
						pContext.doModifyContent(row, value);
						count[0]++;
					}
				}
			});
			if (result.hasFailed())
			{
				throw EngineRefusal.coded("unexpected.rejectChange", "Could not " + (pAcknowledge ? "acknowledge" : "reopen") + " the transfer rejects of this batch: "
					+ result.getExceptionFullMessage(Locale.ENGLISH));
			}
			return count[0];
		}
	}

	private static String userOf(final Session pSession)
	{
		return Actor.current(pSession).userId == null ? "" : Actor.current(pSession).userId;
	}

	private static void logChanged(final String pUser, final String pProjectCode, final int pChanged, final boolean pAcknowledge)
	{
		LoggingCategory.getKernel().info("[mdm-factory] transfer rejects of " + pProjectCode + ": " + pChanged + " " + (pAcknowledge ? "acknowledged" : "reopened") + " by " + pUser);
	}


	// ===================================================================================================== helpers

	/** Entity id (formatted primary key) -> entity name for one project: a handful of rows (for the screen's drop-down). */
	public static Map<String, String> entityNamesOf(final Adaptation pFactory, final String pProjectCode)
	{
		return RejectReview.entityNames(pFactory, pProjectCode);
	}

	/** Entity id (formatted primary key) -> entity name for one project: a handful of rows. */
	static Map<String, String> entityNames(final Adaptation pFactory, final String pProjectCode)
	{
		final Map<String, String> out = new HashMap<String, String>();
		// second permissions pass (2026-10-14): through UserView.current() - the user's view inside the screen's read scope
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ENTITY), "./project=" + CatalogReader.quote(pProjectCode));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.put(row.getOccurrencePrimaryKey().format(), UserView.string(row, FactoryPaths.StagingEntity.NAME));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private interface TupleReader
	{
		void read(Tuple pTuple);
	}

	private static void run(final Session pSession, final Adaptation pFactory, final String pSql, final TupleReader pReader) throws OperationException
	{
		try
		{
			final Query<Tuple> query = pFactory.createQuery(pSql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				for (final Tuple tuple : result)
				{
					pReader.read(tuple);
				}
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex) // includes QueryPermissionException
		{
			throw EngineRefusal.coded("external.rejectRead", "The transfer rejects could not be read: " + ex.getMessage());
		}
	}

	private static RejectRow row(final Tuple pTuple, final Map<String, String> pNames)
	{
		final RejectRow row = new RejectRow();
		row.entityId = RejectReview.text(pTuple.get("ENT"));
		row.entityName = pNames.containsKey(row.entityId) ? pNames.get(row.entityId) : row.entityId;
		row.stage = RejectReview.text(pTuple.get("STG"));
		row.sourceSystem = RejectReview.text(pTuple.get("SYS"));
		row.sourceId = RejectReview.text(pTuple.get("SID"));
		row.landingTable = RejectReview.text(pTuple.get("LDT"));
		row.loadId = RejectReview.text(pTuple.get("LID"));
		row.reasons = RejectReview.text(pTuple.get("RSN"));
		row.rejectedAt = RejectReview.date(pTuple.get("RAT"));
		row.resolved = Boolean.TRUE.equals(pTuple.get("RES"));
		row.acknowledged = Boolean.TRUE.equals(pTuple.get("ACK"));
		row.reviewedBy = RejectReview.text(pTuple.get("RBY"));
		row.reviewedAt = RejectReview.date(pTuple.get("RVA"));
		row.reviewComment = RejectReview.text(pTuple.get("RCM"));
		row.category = RejectReview.text(pTuple.get("CAT"));
		row.reasonCode = RejectReview.text(pTuple.get("RCD"));
		row.detail = RejectReview.text(pTuple.get("DET"));
		return row;
	}

	private static String text(final Object pValue)
	{
		return pValue == null ? "" : String.valueOf(pValue);
	}

	private static long number(final Object pValue)
	{
		return pValue instanceof Number ? ((Number) pValue).longValue() : 0L;
	}

	private static Date date(final Object pValue)
	{
		return pValue instanceof Date ? (Date) pValue : null;
	}

	private static String cut(final String pComment)
	{
		final String trimmed = pComment == null ? "" : pComment.trim();
		return trimmed.length() > RejectReview.COMMENT_MAX ? trimmed.substring(0, RejectReview.COMMENT_MAX) : trimmed;
	}

	private static RejectFilter copy(final RejectFilter pFilter)
	{
		final RejectFilter out = new RejectFilter();
		if (pFilter != null)
		{
			out.entityId = pFilter.entityId;
			out.stage = pFilter.stage;
			out.status = pFilter.status;
			out.reasonContains = pFilter.reasonContains;
			out.reasonCode = pFilter.reasonCode;
			out.from = pFilter.from;
			out.to = pFilter.to;
		}
		return out;
	}
}
