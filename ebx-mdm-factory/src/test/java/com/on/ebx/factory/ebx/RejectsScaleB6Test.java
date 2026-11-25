package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryRight;
import com.on.ebx.factory.rejects.RejectFilter;
import com.on.ebx.factory.rejects.RejectRef;
import com.on.ebx.factory.rejects.RejectRow;
import com.on.ebx.factory.rejects.RejectSql;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * JUnit-free: run the main, a failure throws. F2-103 (robustness audit 2026-10-23): the export and the bulk actions of the transfer-reject review page by key, not by a sorted
 * {@code OFFSET}; above the inline threshold they are jobs that hand the request back at once; a bulk action on 1 200 rejects is three batches of 500.
 */
public final class RejectsScaleB6Test
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		RejectsScaleB6Test.sqlHasNoGrowingOffset();
		RejectsScaleB6Test.sweepIsBatchesOf500();
		RejectsScaleB6Test.sweepFollowsKeysAndStops();
		RejectsScaleB6Test.longActionsAreQueuedAtOnce();
		RejectsScaleB6Test.kindsAndTextsExist();
		RejectsScaleB6Test.downloadNamesAreSafe();
		RejectsScaleB6Test.enginesUseTheKeyset();
		RejectsScaleB6Test.jobsOnlyTouchWhatThePersonMayRead();
		System.out.println("RejectsScaleB6Test OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static void sqlHasNoGrowingOffset()
	{
		final RejectFilter filter = new RejectFilter();
		filter.status = RejectFilter.OPEN;
		final RejectRef last = new RejectRef("E1", "STAGING", "SAP", "O'Neil|7");
		for (final String sql : new String[] { RejectSql.exportPage("P", filter, null, 1000), RejectSql.exportPage("P", filter, last, 1000), RejectSql.keys("P", filter, null, 500),
			RejectSql.keys("P", filter, last, 500) })
		{
			RejectsScaleB6Test.check(!sql.contains("OFFSET"), "no OFFSET in: " + sql);
			RejectsScaleB6Test.check(sql.contains("FETCH NEXT") && sql.contains("ORDER BY t.\"entity\".\"id\", t.\"stage\", t.\"sourceSystem\", t.\"sourceId\""), "ordered by the key: " + sql);
		}
		final String after = RejectSql.keys("P", filter, last, 500);
		RejectsScaleB6Test.check(after.contains("t.\"sourceId\" > 'O''Neil|7'") && after.contains("t.\"entity\".\"id\" > 'E1'"), "the page starts strictly after the last key, quote doubled: " + after);
		RejectsScaleB6Test.check(!RejectSql.keys("P", filter, null, 500).contains(" > '"), "the first page has no lower bound");
		RejectsScaleB6Test.check(RejectSql.exportPage("P", filter, null, 10).contains("t.\"detail\" AS DET"), "the export page carries every column");
		RejectsScaleB6Test.check(RejectSql.page("P", filter, 50, 50).contains("OFFSET 50 ROWS"), "the screen's small page keeps its offset");
	}

	private static List<RejectRef> keys(final int pCount)
	{
		final List<RejectRef> out = new ArrayList<RejectRef>();
		for (int i = 0; i < pCount; i++)
		{
			out.add(new RejectRef("E", "STAGING", "SAP", String.format("%07d", Integer.valueOf(i))));
		}
		return out;
	}

	private static RejectReview.KeySource source(final List<RejectRef> pAll, final List<Integer> pSizes)
	{
		return new RejectReview.KeySource()
		{
			@Override
			public List<RejectRef> next(final RejectRef pAfter, final int pLimit)
			{
				int from = 0;
				while (pAfter != null && from < pAll.size() && !pAll.get(from).sourceId.equals(pAfter.sourceId))
				{
					from++;
				}
				from = pAfter == null ? 0 : from + 1;
				final List<RejectRef> page = new ArrayList<RejectRef>(pAll.subList(Math.min(from, pAll.size()), Math.min(pAll.size(), from + pLimit)));
				pSizes.add(Integer.valueOf(page.size()));
				return page;
			}
		};
	}

	private static void sweepIsBatchesOf500() throws OperationException
	{
		final List<Integer> pages = new ArrayList<Integer>();
		final List<Integer> writes = new ArrayList<Integer>();
		final List<long[]> progress = new ArrayList<long[]>();
		final int changed = RejectReview.sweep(RejectsScaleB6Test.source(RejectsScaleB6Test.keys(1200), pages), new RejectReview.BatchWriter()
		{
			@Override
			public int write(final List<RejectRef> pBatch)
			{
				writes.add(Integer.valueOf(pBatch.size()));
				return pBatch.size();
			}
		}, RejectReview.BATCH, new RejectReview.BulkProgress()
		{
			@Override
			public void done(final long pScanned, final long pChanged)
			{
				progress.add(new long[] { pScanned, pChanged });
			}

			@Override
			public boolean cancelled()
			{
				return false;
			}
		});
		RejectsScaleB6Test.check(changed == 1200, "all 1200 changed: " + changed);
		RejectsScaleB6Test.check(writes.size() == 3 && writes.get(0) == 500 && writes.get(1) == 500 && writes.get(2) == 200, "1200 rejects = 3 batches (500, 500, 200): " + writes);
		RejectsScaleB6Test.check(pages.size() == 3, "three key pages read, no extra empty read after a short page: " + pages);
		RejectsScaleB6Test.check(progress.size() == 3 && progress.get(2)[0] == 1200L && progress.get(2)[1] == 1200L && progress.get(0)[0] == 500L, "the counter follows each batch");
		RejectsScaleB6Test.check(RejectReview.BATCH == 500, "the batch is 500");
	}

	private static void sweepFollowsKeysAndStops() throws OperationException
	{
		// exactly 1000 keys: the last full page is followed by one empty read, then the walk ends
		final List<Integer> pages = new ArrayList<Integer>();
		final int[] writes = new int[1];
		RejectReview.sweep(RejectsScaleB6Test.source(RejectsScaleB6Test.keys(1000), pages), new RejectReview.BatchWriter()
		{
			@Override
			public int write(final List<RejectRef> pBatch)
			{
				writes[0]++;
				return 0;
			}
		}, 500, null);
		RejectsScaleB6Test.check(writes[0] == 2 && pages.size() == 3, "1000 keys: two writes, the third read is empty: " + pages);
		// a stop between two batches ends the walk
		final int[] done = new int[1];
		RejectReview.sweep(RejectsScaleB6Test.source(RejectsScaleB6Test.keys(1200), new ArrayList<Integer>()), new RejectReview.BatchWriter()
		{
			@Override
			public int write(final List<RejectRef> pBatch)
			{
				done[0]++;
				return pBatch.size();
			}
		}, 500, new RejectReview.BulkProgress()
		{
			@Override
			public void done(final long pScanned, final long pChanged)
			{
			}

			@Override
			public boolean cancelled()
			{
				return true;
			}
		});
		RejectsScaleB6Test.check(done[0] == 1, "stopped after the first batch");
	}

	private static final class FakeLauncher implements RunLauncher.Launcher
	{
		final List<String> kinds = new ArrayList<String>();
		final List<ObjectNode> params = new ArrayList<ObjectNode>();

		@Override
		public ObjectNode launch(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pKind, final ObjectNode pParams)
		{
			this.kinds.add(pKind);
			this.params.add(pParams);
			final ObjectNode out = RejectsScaleB6Test.MAPPER.createObjectNode();
			out.put("started", true);
			out.put("jobId", String.valueOf(this.kinds.size()));
			return out;
		}
	}

	private static void longActionsAreQueuedAtOnce() throws OperationException
	{
		final FakeLauncher fake = new FakeLauncher();
		RunLauncher.use(fake);
		try
		{
			final RejectFilter filter = new RejectFilter();
			filter.entityId = "E1";
			filter.reasonContains = "bad";
			final long start = System.nanoTime();
			final ObjectNode export = RejectBulkRun.startExport(null, null, "P", filter);
			final ObjectNode bulk = RejectBulkRun.startBulk(null, null, "P", filter, true, "known issue");
			final long millis = (System.nanoTime() - start) / 1000000L;
			RejectsScaleB6Test.check(millis < 100, "both hand the request back in under 100 ms (took " + millis + " ms)");
			RejectsScaleB6Test.check(fake.kinds.size() == 2 && fake.kinds.get(0).equals(RejectBulkRun.EXPORT_STEP) && fake.kinds.get(1).equals(RejectBulkRun.BULK_STEP),
				"one FactoryJob row per action: " + fake.kinds);
			RejectsScaleB6Test.check(export.path("started").asBoolean(false) && bulk.path("started").asBoolean(false), "answered as started");
			final RejectFilter back = RejectBulkRun.filterOf(fake.params.get(1));
			RejectsScaleB6Test.check("E1".equals(back.entityId) && "bad".equals(back.reasonContains), "the filter travels in the job's parameters");
			RejectsScaleB6Test.check(fake.params.get(1).path("acknowledge").asBoolean(false) && "known issue".equals(fake.params.get(1).path("comment").asText()), "the bulk job carries its action and comment");
		}
		finally
		{
			RunLauncher.reset();
		}
		RejectsScaleB6Test.check(RejectReview.inlineMax() == 10000 && RejectReview.exportMaxRows() == 1000000, "defaults: 10 000 rows inline, 1 000 000 per file");
		System.setProperty("ebx.factory.rejectInlineMax", "250");
		try
		{
			RejectsScaleB6Test.check(RejectReview.inlineMax() == 250, "the threshold is adjustable");
		}
		finally
		{
			System.clearProperty("ebx.factory.rejectInlineMax");
		}
	}

	private static void kindsAndTextsExist()
	{
		FactoryJobKinds.registerAll();
		RejectsScaleB6Test.check(FactoryJobs.STAGING.equals(FactoryJobQueue.resourceOf(RejectBulkRun.EXPORT_STEP)) && FactoryJobQueue.rightOf(RejectBulkRun.EXPORT_STEP) == FactoryRight.STEWARD, "the export job is queued by STEWARD");
		RejectsScaleB6Test.check(FactoryJobs.STAGING.equals(FactoryJobQueue.resourceOf(RejectBulkRun.BULK_STEP)) && FactoryJobQueue.rightOf(RejectBulkRun.BULK_STEP) == FactoryRight.STEWARD, "the bulk action is queued by STEWARD");
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final ResourceBundle bundle = ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", locale);
			for (final String key : new String[] { "wizard.jobs.kind.REJECT_EXPORT_RUN", "wizard.jobs.kind.REJECT_BULK_RUN", "rejects.export.job", "rejects.export.job.queued", "rejects.export.job.done",
				"rejects.export.job.capped", "rejects.export.job.failed", "rejects.export.files", "rejects.export.file", "rejects.export.tooBig", "rejects.export.needSteward", "rejects.bulk.queued", "rejects.bulk.job.acknowledged",
				"rejects.bulk.job.reopened" })
			{
				RejectsScaleB6Test.check(bundle.containsKey(key) && !bundle.getString(key).isEmpty(), key + " in " + locale);
			}
		}
	}

	private static void downloadNamesAreSafe()
	{
		final String name = RejectBulkRun.fileName("My Project/1", "jdoe", 1761300000000L);
		RejectsScaleB6Test.check(RejectBulkRun.validName(name) && RejectBulkRun.ownedBy(name, "jdoe") && !RejectBulkRun.ownedBy(name, "other"), "a file is served to its owner only: " + name);
		for (final String bad : new String[] { "../etc/passwd", "rejects-P-1761300000000-00000000.csv/../x", "rejects-P-1761300000000-zzzzzzzz.csv", "", null })
		{
			RejectsScaleB6Test.check(!RejectBulkRun.validName(bad), "refused: " + bad);
		}
	}

	private static String read(final String pRelative) throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"));
		return new String(Files.readAllBytes(root.resolve("com/on/ebx/factory/" + pRelative)), StandardCharsets.UTF_8);
	}

	private static void enginesUseTheKeyset() throws IOException
	{
		final String review = RejectsScaleB6Test.read("ebx/RejectReview.java");
		RejectsScaleB6Test.check(!review.contains("RejectSql.page(pProjectCode, pFilter, offset") && review.contains("RejectSql.exportPage(") && review.contains("RejectSql.keys(pProjectCode, pFilter, pAfter"),
			"export and bulk keys read by keyset");
		final String sql = RejectsScaleB6Test.read("rejects/RejectSql.java");
		final int keys = sql.indexOf("public static String keys(");
		RejectsScaleB6Test.check(!sql.substring(keys, sql.indexOf("public static String summary(")).contains("OFFSET"), "the key and export queries carry no OFFSET");
		final String service = RejectsScaleB6Test.read("ebx/ui/TransferRejectReviewUserService.java");
		RejectsScaleB6Test.check(service.contains("RejectBulkRun.startExport(") && service.contains("RejectBulkRun.startBulk(") && service.contains("RejectReview.inlineMax()"),
			"the screen queues above the threshold");
		final String kinds = RejectsScaleB6Test.read("ebx/FactoryJobKinds.java");
		RejectsScaleB6Test.check(kinds.contains("FactoryJobQueue.register(RejectBulkRun.EXPORT_STEP,") && kinds.contains("FactoryJobQueue.register(RejectBulkRun.BULK_STEP,"), "both kinds are registered");
	}

	/** The permissions rule: a job acts for a person and never exports or changes a row that person may not read; the final counts say how many were left out. */
	private static void jobsOnlyTouchWhatThePersonMayRead() throws Exception
	{
		final List<RejectRow> all = new ArrayList<RejectRow>();
		for (final RejectRef ref : RejectsScaleB6Test.keys(1200))
		{
			final RejectRow row = new RejectRow();
			row.entityId = ref.entityId;
			row.stage = ref.stage;
			row.sourceSystem = ref.sourceSystem;
			row.sourceId = ref.sourceId;
			all.add(row);
		}
		// 200 of the 1200 are hidden (every sixth)
		final RejectReview.Visibility hideSixth = new RejectReview.Visibility()
		{
			@Override
			public boolean sees(final RejectRef pRef)
			{
				return Integer.parseInt(pRef.sourceId) % 6 != 0;
			}
		};
		final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		final long[] hidden = new long[1];
		final long[] last = new long[2];
		final long written = RejectReview.streamExport(new RejectReview.RowSource()
		{
			@Override
			public List<RejectRow> next(final RejectRef pAfter, final int pLimit)
			{
				int from = 0;
				while (pAfter != null && !all.get(from).sourceId.equals(pAfter.sourceId))
				{
					from++;
				}
				from = pAfter == null ? 0 : from + 1;
				return new ArrayList<RejectRow>(all.subList(Math.min(from, all.size()), Math.min(all.size(), from + pLimit)));
			}
		}, out, 0L, new RejectReview.BulkProgress()
		{
			@Override
			public void done(final long pScanned, final long pChanged)
			{
				last[0] = pScanned;
				last[1] = pChanged;
			}

			@Override
			public boolean cancelled()
			{
				return false;
			}
		}, hideSixth, hidden);
		RejectsScaleB6Test.check(written == 1000 && hidden[0] == 200L, "1200 rows, 200 hidden: 1000 written, 200 counted as left out (" + written + "/" + hidden[0] + ")");
		RejectsScaleB6Test.check(last[0] == 1200L && last[1] == 1000L, "the counter reads 1200 scanned, 1000 written");
		final String csv = new String(out.toByteArray(), StandardCharsets.UTF_8);
		RejectsScaleB6Test.check(!csv.contains("0000006") && csv.contains("0000007"), "a hidden row is not in the file");
		// the bulk path: a batch is reduced to what the person reads before it is written
		final long[] hiddenBulk = new long[1];
		final List<RejectRef> visible = RejectReview.visibleOnly(RejectsScaleB6Test.keys(1200), hideSixth, hiddenBulk);
		RejectsScaleB6Test.check(visible.size() == 1000 && hiddenBulk[0] == 200L, "bulk: 1000 kept, 200 left out");
		RejectsScaleB6Test.check(RejectReview.visibleOnly(RejectsScaleB6Test.keys(3), null, null).size() == 3, "no visibility = every row");
		final String run = RejectsScaleB6Test.read("ebx/RejectBulkRun.java");
		RejectsScaleB6Test.check(run.contains("RejectReview.visibilityFor(pFactory, projectCode, user)") && run.split("visibilityFor\\(").length == 3, "both handlers act under the steward's own rights");
		RejectsScaleB6Test.check(RejectsScaleB6Test.read("ebx/RejectReview.java").contains("createSessionPermissionsForUser(") , "the public SessionPermissions API is the filter");
	}
}
