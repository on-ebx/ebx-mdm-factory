package com.on.ebx.factory.ebx;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.rejects.RejectFilter;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * F2-103 (robustness audit 2026-10-23): the two long actions of the transfer-reject review as jobs of {@link FactoryJobQueue}, so a million rejects never hold an HTTP request.
 * <ul>
 * <li>{@link #EXPORT_STEP} - the CSV of a filter written to a bounded file ({@link RejectReview#exportMaxRows}, default 1 000 000 rows) in {@link #directory()}; the screen then
 * lists it for download ({@link #filesOf}). Pages by key, one page in memory.</li>
 * <li>{@link #BULK_STEP} - "acknowledge all" / "reopen all" of a filter, keyset pages of 500 keys each written before the next is read, with a counter ({@link #progressOf}).</li>
 * </ul>
 * The screen does these inline up to {@link RejectReview#inlineMax} rows (default 10 000) and queues above; the right is checked where the job is queued (STEWARD for both, {@link FactoryJobKinds}) and the job runs with the system session on behalf of the person recorded in its parameters (reviewer of the rows). Resource: STAGING,
 * the zone that writes and re-opens the rejects.
 * <p>
 * An export file is named {@code rejects-<project>-<time>-<owner>.csv} where the owner part is a hash of the login: a download is served only to that login ({@link #ownedBy}).
 */
public final class RejectBulkRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String EXPORT_STEP = "REJECT_EXPORT_RUN";
	public static final String BULK_STEP = "REJECT_BULK_RUN";
	/** A file older than this is deleted when a new export starts. */
	static final long KEEP_MILLIS = 24L * 3600L * 1000L;
	private static final Pattern FILE = Pattern.compile("rejects-[A-Za-z0-9_.]{1,64}-[0-9]{10,16}-[0-9a-f]{8}\\.csv");
	private static final ConcurrentMap<String, long[]> PROGRESS = new ConcurrentHashMap<String, long[]>();

	private RejectBulkRun()
	{
	}

	// ------------------------------------------------------------------------------------------------------ queuing

	/** Queues the export of the rows {@code pFilter} matches; the person is the one whose login owns the file. */
	public static ObjectNode startExport(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter) throws OperationException
	{
		final ObjectNode params = RejectBulkRun.params(pSession, pProjectCode, pFilter);
		return RunLauncher.launch(pSession, pFactory, pProjectCode, RejectBulkRun.EXPORT_STEP, params);
	}

	/** Queues "acknowledge all" ({@code pAcknowledge}) or "reopen all" of the rows {@code pFilter} matches; the comment is the one typed. */
	public static ObjectNode startBulk(final Session pSession, final Adaptation pFactory, final String pProjectCode, final RejectFilter pFilter, final boolean pAcknowledge,
		final String pComment) throws OperationException
	{
		final ObjectNode params = RejectBulkRun.params(pSession, pProjectCode, pFilter);
		params.put("acknowledge", pAcknowledge);
		params.put("comment", pComment == null ? "" : pComment);
		return RunLauncher.launch(pSession, pFactory, pProjectCode, RejectBulkRun.BULK_STEP, params);
	}

	private static ObjectNode params(final Session pSession, final String pProjectCode, final RejectFilter pFilter)
	{
		final ObjectNode params = RejectBulkRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("locale", (pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale()).toLanguageTag());
		params.put("user", pSession == null ? "" : Actor.current(pSession).userId == null ? "" : Actor.current(pSession).userId);
		params.put("entity", pFilter.entityId);
		params.put("stage", pFilter.stage);
		params.put("status", pFilter.status);
		params.put("reason", pFilter.reasonContains);
		params.put("code", pFilter.reasonCode);
		params.put("from", RejectFilter.dayText(pFilter.from));
		params.put("to", RejectFilter.dayText(pFilter.to));
		return params;
	}

	static RejectFilter filterOf(final JsonNode pParams)
	{
		return RejectFilter.parse(pParams.path("entity").asText(""), pParams.path("stage").asText(""), pParams.path("status").asText(""), pParams.path("reason").asText(""),
			pParams.path("from").asText(""), pParams.path("to").asText(""), pParams.path("code").asText(""));
	}

	// ------------------------------------------------------------------------------------------------------ progress

	/** {rows scanned, rows changed} of the running job of this kind for the project; null when none was seen since the start. */
	public static long[] progressOf(final String pProjectId, final String pKind)
	{
		final long[] value = RejectBulkRun.PROGRESS.get(pProjectId + "|" + pKind);
		return value == null ? null : value.clone();
	}

	private static RejectReview.BulkProgress progress(final FactoryJobs.Handle pHandle, final String pProjectId, final String pKind)
	{
		final String key = pProjectId + "|" + pKind;
		RejectBulkRun.PROGRESS.put(key, new long[] { 0L, 0L });
		return new RejectReview.BulkProgress()
		{
			@Override
			public void done(final long pScanned, final long pChanged)
			{
				RejectBulkRun.PROGRESS.put(key, new long[] { pScanned, pChanged });
			}

			@Override
			public boolean cancelled()
			{
				return pHandle.cancelled();
			}
		};
	}

	// ------------------------------------------------------------------------------------------------------ handlers

	static final FactoryJobQueue.Handler EXPORT_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams) throws Exception
		{
			final Locale locale = RejectBulkRun.localeOf(pParams);
			final String projectCode = pParams.path("projectCode").asText("");
			final String user = pParams.path("user").asText("");
			final File dir = RejectBulkRun.directory();
			RejectBulkRun.purge(dir, System.currentTimeMillis());
			final String name = RejectBulkRun.fileName(projectCode, user, System.currentTimeMillis());
			final File file = new File(dir, name);
			final long cap = RejectReview.exportMaxRows();
			final long rows;
			final long[] hidden = new long[1];
			// the job runs as the system session: only the rows the steward's own EBX rights let them read go into the file
			final RejectReview.Visibility visibility = RejectReview.visibilityFor(pFactory, projectCode, user);
			try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file.toPath())))
			{
				rows = RejectReview.exportCsv(pJobSession, pFactory, projectCode, RejectBulkRun.filterOf(pParams), out, cap, RejectBulkRun.progress(pHandle, pProjectId, RejectBulkRun.EXPORT_STEP),
					visibility, hidden);
			}
			catch (final IOException ex)
			{
				throw new FactoryJobQueue.ExpectedFailure(WizardMessages.get(locale, "rejects.export.job.failed", ex.getMessage()));
			}
			if (rows >= cap)
			{
				return WizardMessages.get(locale, "rejects.export.job.capped", Long.valueOf(rows), Long.valueOf(hidden[0]));
			}
			return WizardMessages.get(locale, "rejects.export.job.done", Long.valueOf(rows), Long.valueOf(hidden[0]));
		}
	};

	static final FactoryJobQueue.Handler BULK_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams) throws Exception
		{
			final Locale locale = RejectBulkRun.localeOf(pParams);
			final String projectCode = pParams.path("projectCode").asText("");
			final String user = pParams.path("user").asText("");
			final boolean acknowledge = pParams.path("acknowledge").asBoolean(false);
			final RejectReview.BulkProgress progress = RejectBulkRun.progress(pHandle, pProjectId, RejectBulkRun.BULK_STEP);
			final long[] hidden = new long[1];
			final RejectReview.Visibility visibility = RejectReview.visibilityFor(pFactory, projectCode, user);
			final int changed = acknowledge
				? RejectReview.acknowledgeMatching(pJobSession, pFactory, projectCode, RejectBulkRun.filterOf(pParams), pParams.path("comment").asText(""), user, progress, visibility, hidden)
				: RejectReview.reopenMatching(pJobSession, pFactory, projectCode, RejectBulkRun.filterOf(pParams), pParams.path("comment").asText(""), user, progress, visibility, hidden);
			return WizardMessages.get(locale, acknowledge ? "rejects.bulk.job.acknowledged" : "rejects.bulk.job.reopened", Integer.valueOf(changed), Long.valueOf(hidden[0]));
		}
	};

	// ------------------------------------------------------------------------------------------------------ files

	/** Where the files are written: {@code -Debx.factory.rejectExportDir}, else a folder of the JVM's temporary directory. */
	static File directory() throws IOException
	{
		final String configured = System.getProperty("ebx.factory.rejectExportDir", "");
		final File dir = configured.isEmpty() ? new File(System.getProperty("java.io.tmpdir"), "mdm-factory-reject-exports") : new File(configured);
		Files.createDirectories(dir.toPath());
		return dir;
	}

	/** The name of an export file: the project, the time and a hash of the owner's login (pure). */
	static String fileName(final String pProjectCode, final String pUser, final long pMillis)
	{
		return "rejects-" + (pProjectCode == null ? "" : pProjectCode).replaceAll("[^A-Za-z0-9_.]", "_") + "-" + pMillis + "-" + RejectBulkRun.ownerHash(pUser) + ".csv";
	}

	static String ownerHash(final String pUser)
	{
		return String.format("%08x", Integer.valueOf((pUser == null ? "" : pUser).hashCode()));
	}

	/** Whether a requested name has the shape this class writes (no path part, nothing else) - the only names a download accepts. */
	static boolean validName(final String pName)
	{
		return pName != null && RejectBulkRun.FILE.matcher(pName).matches();
	}

	/** Whether the file belongs to this login. */
	static boolean ownedBy(final String pName, final String pUser)
	{
		return RejectBulkRun.validName(pName) && pName.endsWith("-" + RejectBulkRun.ownerHash(pUser) + ".csv");
	}

	/** The file of that name when it is valid, owned by the login and present; null otherwise. */
	public static File fileFor(final String pName, final String pUser)
	{
		if (!RejectBulkRun.ownedBy(pName, pUser))
		{
			return null;
		}
		try
		{
			final File file = new File(RejectBulkRun.directory(), pName);
			return file.isFile() ? file : null;
		}
		catch (final IOException ex)
		{
			return null;
		}
	}

	/** The names of this login's export files of the project, newest first (at most five). */
	public static List<String> filesOf(final String pProjectCode, final String pUser)
	{
		final List<String> out = new ArrayList<String>();
		try
		{
			final String prefix = "rejects-" + (pProjectCode == null ? "" : pProjectCode).replaceAll("[^A-Za-z0-9_.]", "_") + "-";
			final String[] names = RejectBulkRun.directory().list();
			if (names != null)
			{
				for (final String name : names)
				{
					if (name.startsWith(prefix) && RejectBulkRun.ownedBy(name, pUser))
					{
						out.add(name);
					}
				}
			}
		}
		catch (final IOException ex)
		{
			return out;
		}
		Collections.sort(out, Collections.reverseOrder());
		return out.size() > 5 ? new ArrayList<String>(out.subList(0, 5)) : out;
	}

	private static void purge(final File pDir, final long pNow)
	{
		final File[] files = pDir.listFiles();
		if (files == null)
		{
			return;
		}
		for (final File file : files)
		{
			if (RejectBulkRun.validName(file.getName()) && pNow - file.lastModified() > RejectBulkRun.KEEP_MILLIS && !file.delete())
			{
				file.deleteOnExit();
			}
		}
	}

	private static Locale localeOf(final JsonNode pParams)
	{
		final String tag = pParams.path("locale").asText("");
		return tag.isEmpty() ? Locale.ENGLISH : Locale.forLanguageTag(tag);
	}
}
