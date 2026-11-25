package com.on.ebx.factory.ebx;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryRight;
import com.on.ebx.factory.landing.KeyStudyReport;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Audit F-34 (wave 10): the "Study the key" of a landing table when the language model will take part - a live sample of the source, then a model call -
 * as a job of {@link FactoryJobQueue} ({@link #STEP}, resource {@link FactoryJobs#LANDING}, the resource of the landing generation that reads the same sample).
 * Without a model (a person with no DESIGN right, no connector configured) the study stays in the request: a 25-row sample and the naming rule are quick.
 * <p>
 * A study is not written anywhere ({@link KeyStudy#study} writes nothing - "Apply this key" does, later, from the report): the job parks its {@link
 * KeyStudyReport} in memory under the person who asked, the screen it was asked from ({@code pSlot}) and the table, and the wizard takes it from there at its
 * next display ({@link #take}) - the display the jobs banner triggers when the job ends. A report nobody takes is dropped after {@link #KEEP_MILLIS}; a
 * restart loses the ones in memory (the person asks again). Like the job registry, this is per server (single node).
 */
public final class KeyStudyRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "LANDING_KEY_STUDY_RUN";
	/** The study panel under the Advanced per-source table. */
	public static final String SLOT_PANEL = "panel";
	/** The key chooser under a WAITING table of the landing run. */
	public static final String SLOT_CHOOSER = "chooser";
	/** How long a finished study waits to be taken. */
	static final long KEEP_MILLIS = 30L * 60L * 1000L;
	/** The most reports kept at once. */
	static final int KEEP_MAX = 50;

	private static final class Kept
	{
		final KeyStudyReport report;
		final long at;

		Kept(final KeyStudyReport pReport, final long pAt)
		{
			this.report = pReport;
			this.at = pAt;
		}
	}

	private static final Map<String, Kept> RESULTS = new LinkedHashMap<String, Kept>();

	private KeyStudyRun()
	{
	}

	/** Pure: the key a report is kept under. */
	static String key(final String pProjectId, final String pUser, final String pSlot, final String pTableId)
	{
		return pProjectId + '\n' + (pUser == null ? "" : pUser) + '\n' + pSlot + '\n' + pTableId;
	}

	/**
	 * Whether this study should be queued rather than run in the request: the language model takes part when the person holds the DESIGN right in person
	 * ({@link FactoryAccess#hasInPerson}, what {@link KeyStudy#study} itself reads) and a connector is configured.
	 */
	public static boolean usesLanguageModel(final Session pSession, final Adaptation pFactory)
	{
		return FactoryAccess.hasInPerson(pSession, FactoryRight.DESIGN) && KeyStudy.llmConfigured(pFactory);
	}

	/** Queues the study of {@code pTableId} for the screen {@code pSlot}. The person's DESIGN right (the language model is theirs to spend) travels in the parameters. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pTableId, final String pSlot)
		throws OperationException
	{
		final ObjectNode params = KeyStudyRun.MAPPER.createObjectNode();
		params.put("tableId", pTableId);
		params.put("slot", pSlot);
		params.put("user", Actor.current(pSession).userId == null ? "" : Actor.current(pSession).userId);
		params.put("designer", FactoryAccess.hasInPerson(pSession, FactoryRight.DESIGN));
		params.put("locale", (pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale()).toLanguageTag());
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, pProjectId, KeyStudyRun.STEP, params);
		final ObjectNode out = KeyStudyRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String tag = pParams.path("locale").asText("");
			final Locale locale = tag.isEmpty() ? Locale.ENGLISH : Locale.forLanguageTag(tag);
			final String tableId = pParams.path("tableId").asText("");
			final KeyStudyReport report = KeyStudy.study(pJobSession, pFactory, tableId, true, pParams.path("designer").asBoolean(false));
			KeyStudyRun.keep(pProjectId, pParams.path("user").asText(""), pParams.path("slot").asText(KeyStudyRun.SLOT_PANEL), tableId, report, System.currentTimeMillis());
			return report.proposal == null ? WizardMessages.get(locale, "wizard.step3.keyStudy.jobDoneNone", report.tableName)
				: WizardMessages.get(locale, "wizard.step3.keyStudy.jobDone", report.tableName, report.proposal.columnsCsv().replace(",", ", "));
		}
	};

	static void keep(final String pProjectId, final String pUser, final String pSlot, final String pTableId, final KeyStudyReport pReport, final long pNow)
	{
		synchronized (KeyStudyRun.RESULTS)
		{
			KeyStudyRun.purge(pNow);
			KeyStudyRun.RESULTS.put(KeyStudyRun.key(pProjectId, pUser, pSlot, pTableId), new Kept(pReport, pNow));
			while (KeyStudyRun.RESULTS.size() > KeyStudyRun.KEEP_MAX)
			{
				final Iterator<String> oldest = KeyStudyRun.RESULTS.keySet().iterator();
				oldest.next();
				oldest.remove();
			}
		}
	}

	/** The finished study of this table for this person and screen, removed from memory; null when there is none (not finished, failed, expired, or taken already). */
	public static KeyStudyReport take(final String pProjectId, final String pUser, final String pSlot, final String pTableId)
	{
		return KeyStudyRun.take(pProjectId, pUser, pSlot, pTableId, System.currentTimeMillis());
	}

	static KeyStudyReport take(final String pProjectId, final String pUser, final String pSlot, final String pTableId, final long pNow)
	{
		synchronized (KeyStudyRun.RESULTS)
		{
			KeyStudyRun.purge(pNow);
			final Kept kept = KeyStudyRun.RESULTS.remove(KeyStudyRun.key(pProjectId, pUser, pSlot, pTableId));
			return kept == null ? null : kept.report;
		}
	}

	/** How long the landing run's own proposal for a waiting table stays ready for the key chooser. */
	static final long RUN_PROPOSAL_KEEP_MILLIS = 24L * 60L * 60L * 1000L;

	private static final Map<String, Kept> RUN_PROPOSALS = new LinkedHashMap<String, Kept>();

	/**
	 * 2026-10-07 (a language-model key is only a suggestion): the proposal the landing run studied for a table it left waiting, so "Choose the key"
	 * opens on it at once instead of studying the table again (one model call, not two). Kept per table, for every person of the project (the
	 * chooser already checks the right); lost at a restart, the chooser then studies again.
	 */
	public static void keepRunProposal(final String pTableId, final KeyStudyReport pReport, final long pNow)
	{
		if (pTableId == null || pReport == null || pReport.proposal == null)
		{
			return;
		}
		synchronized (KeyStudyRun.RUN_PROPOSALS)
		{
			KeyStudyRun.purgeRunProposals(pNow);
			KeyStudyRun.RUN_PROPOSALS.remove(pTableId);
			KeyStudyRun.RUN_PROPOSALS.put(pTableId, new Kept(pReport, pNow));
			while (KeyStudyRun.RUN_PROPOSALS.size() > KeyStudyRun.KEEP_MAX * 10)
			{
				final Iterator<String> oldest = KeyStudyRun.RUN_PROPOSALS.keySet().iterator();
				oldest.next();
				oldest.remove();
			}
		}
	}

	/** The landing run's proposal for this table, left in memory (several people may open the chooser); null when there is none or it expired. */
	public static KeyStudyReport runProposal(final String pTableId, final long pNow)
	{
		synchronized (KeyStudyRun.RUN_PROPOSALS)
		{
			KeyStudyRun.purgeRunProposals(pNow);
			final Kept kept = pTableId == null ? null : KeyStudyRun.RUN_PROPOSALS.get(pTableId);
			return kept == null ? null : kept.report;
		}
	}

	/** Forgets the run's proposal once a key was chosen for the table. */
	public static void forgetRunProposal(final String pTableId)
	{
		synchronized (KeyStudyRun.RUN_PROPOSALS)
		{
			KeyStudyRun.RUN_PROPOSALS.remove(pTableId);
		}
	}

	private static void purgeRunProposals(final long pNow)
	{
		final Iterator<Kept> all = KeyStudyRun.RUN_PROPOSALS.values().iterator();
		while (all.hasNext())
		{
			if (pNow - all.next().at > KeyStudyRun.RUN_PROPOSAL_KEEP_MILLIS)
			{
				all.remove();
			}
		}
	}

	/** Whether a study job of the project is waiting or running. */
	public static boolean active(final Adaptation pFactory, final String pProjectId)
	{
		for (final Adaptation row : FactoryJobQueue.active(pFactory, pProjectId))
		{
			if (KeyStudyRun.STEP.equals(row.getString(com.on.ebx.factory.core.FactoryPaths.FactoryJob.KIND)))
			{
				return true;
			}
		}
		return false;
	}

	private static void purge(final long pNow)
	{
		final Iterator<Kept> all = KeyStudyRun.RESULTS.values().iterator();
		while (all.hasNext())
		{
			if (pNow - all.next().at > KeyStudyRun.KEEP_MILLIS)
			{
				all.remove();
			}
		}
	}
}
