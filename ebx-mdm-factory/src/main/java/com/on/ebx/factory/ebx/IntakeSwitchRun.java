package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * F2-102 (robustness audit 2026-10-23): "Switch on" and "Switch off" of the automatic intake as jobs of {@link FactoryJobQueue}. Switching on declares, authors and publishes the three
 * triggers of every ticked landing table of every application (one publish pair per application: minutes on a project with several sources), and used to do it inside the wizard's
 * HTTP request: a reload or a cut left the intake half installed with nothing on screen to say so.
 * <ul>
 * <li>{@link #ON_STEP} / {@link #OFF_STEP}: resource {@link FactoryJobs#LANDING} (the landing models are republished), queued by the DESIGN right or the pipeline launcher role like
 * "Read now". The cheap checks ({@link Intake#prepareSwitchOn}) are made where the person clicks, the long half ({@link Intake#runSwitchOn}) in the job - for the PERSON who asked
 * ({@code user} parameter: {@code intakeEnabledBy} is who the chains run as, never the job's system session).</li>
 * <li>Readable state: after each application the job rewrites its own parameters with the applications whose triggers are installed ({@code installed}); {@link #stateLine} reads
 * them back for the set-up screen, so an installation cut by a restart or an error says which applications have their triggers and which do not. The state stays OFF until the
 * last application is done; running "Switch on" again regenerates every body and goes on (the installer is idempotent), nothing to clean first.</li>
 * </ul>
 */
public final class IntakeSwitchRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String ON_STEP = "INTAKE_SWITCH_ON";
	public static final String OFF_STEP = "INTAKE_SWITCH_OFF";

	private IntakeSwitchRun()
	{
	}

	/**
	 * Queues the switch-on. The cheap checks come first and throw their coded refusal; an intake already ON queues nothing ({@code started} false, {@code alreadyOn} true). The
	 * person's settings must be saved BEFORE this call (the wizard does it, as before).
	 */
	public static ObjectNode startOn(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		if (!Intake.prepareSwitchOn(pSession, pFactory, pProjectCode))
		{
			final ObjectNode out = IntakeSwitchRun.MAPPER.createObjectNode();
			out.put("started", false);
			out.put("alreadyOn", true);
			return out;
		}
		return IntakeSwitchRun.queueOn(pSession, pFactory, pProjectCode);
	}

	/** The queuing alone, after the checks: what a test drives with a fake launcher. */
	static ObjectNode queueOn(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final ObjectNode params = IntakeSwitchRun.params(pSession, pProjectCode);
		params.put("user", Actor.current(pSession).userId);
		params.putArray("installed");
		return IntakeSwitchRun.enqueue(pSession, pFactory, pProjectCode, IntakeSwitchRun.ON_STEP, params);
	}

	/** Queues the switch-off; refusals (a RUNNING chain) are raised here, already OFF queues nothing. */
	public static ObjectNode startOff(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		if (!Intake.prepareSwitchOff(pSession, pFactory, pProjectCode))
		{
			final ObjectNode out = IntakeSwitchRun.MAPPER.createObjectNode();
			out.put("started", false);
			out.put("alreadyOff", true);
			return out;
		}
		return IntakeSwitchRun.queueOff(pSession, pFactory, pProjectCode);
	}

	/** The queuing alone, after the checks. */
	static ObjectNode queueOff(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		return IntakeSwitchRun.enqueue(pSession, pFactory, pProjectCode, IntakeSwitchRun.OFF_STEP, IntakeSwitchRun.params(pSession, pProjectCode));
	}

	private static ObjectNode params(final Session pSession, final String pProjectCode)
	{
		final ObjectNode params = IntakeSwitchRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("locale", (pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale()).toLanguageTag());
		return params;
	}

	private static ObjectNode enqueue(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pKind, final ObjectNode pParams)
		throws OperationException
	{
		return RunLauncher.launch(pSession, pFactory, pProjectCode, pKind, pParams);
	}

	static final FactoryJobQueue.Handler ON_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final Locale locale = IntakeSwitchRun.localeFromParams(pParams);
			final String projectCode = pParams.path("projectCode").asText("");
			final String jobId = FactoryJobQueue.currentJobId();
			final ObjectNode live = pParams.deepCopy();
			final ArrayNode installed = live.putArray("installed");
			try
			{
				Intake.runSwitchOn(pJobSession, pFactory, projectCode, pParams.path("user").asText(""), new IntakeTriggerInstaller.Progress()
				{
					@Override
					public void sourceInstalled(final String pSourceSystem, final int pTables) throws OperationException
					{
						installed.add(pSourceSystem);
						IntakeSwitchRun.keepProgress(pFactory, jobId, live);
						if (pHandle.cancelled())
						{
							throw EngineRefusal.coded("conflict.stopped", "Stopped after " + pSourceSystem + ": the intake stays off, switch it on again to finish.", pSourceSystem);
						}
					}
				});
			}
			catch (final OperationException ex)
			{
				throw new FactoryJobQueue.ExpectedFailure(IntakeSwitchRun.failure(pFactory, projectCode, installedNames(installed), locale, ex.getMessage()));
			}
			return WizardMessages.get(locale, "wizard.intake.notice.switchedOn");
		}
	};

	static final FactoryJobQueue.Handler OFF_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final Locale locale = IntakeSwitchRun.localeFromParams(pParams);
			try
			{
				Intake.runSwitchOff(pJobSession, pFactory, pParams.path("projectCode").asText(""));
			}
			catch (final OperationException ex)
			{
				throw new FactoryJobQueue.ExpectedFailure(WizardMessages.get(locale, "wizard.intake.job.switchOffFailed", ex.getMessage()));
			}
			return WizardMessages.get(locale, "wizard.intake.notice.switchedOff");
		}
	};

	/** Pure: the names in the {@code installed} array, in order. */
	static List<String> installedNames(final JsonNode pInstalled)
	{
		final List<String> out = new ArrayList<String>();
		if (pInstalled != null)
		{
			for (final JsonNode name : pInstalled)
			{
				out.add(name.asText(""));
			}
		}
		return out;
	}

	private static void keepProgress(final Adaptation pFactory, final String pJobId, final ObjectNode pParams)
	{
		if (pJobId != null)
		{
			FactoryJobQueue.replaceParams(pFactory, pJobId, pParams.toString());
		}
	}

	/** The sentence of a failed switch-on: the cause, then which applications have their triggers and which do not. */
	private static String failure(final Adaptation pFactory, final String pProjectCode, final List<String> pInstalled, final Locale pLocale, final String pCause)
	{
		final List<String> all = IntakeSwitchRun.sourceNames(pFactory, pProjectCode);
		return WizardMessages.get(pLocale, "wizard.intake.job.switchOnFailed", pCause == null ? "" : pCause, IntakeSwitchRun.join(pInstalled, pLocale),
			IntakeSwitchRun.join(IntakeSwitchRun.missing(all, pInstalled), pLocale));
	}

	/** Pure: the names of {@code pAll} that are not in {@code pInstalled}, in order. */
	static List<String> missing(final List<String> pAll, final List<String> pInstalled)
	{
		final List<String> out = new ArrayList<String>();
		final Set<String> done = new LinkedHashSet<String>(pInstalled);
		for (final String name : pAll)
		{
			if (!done.contains(name))
			{
				out.add(name);
			}
		}
		return out;
	}

	private static String join(final List<String> pNames, final Locale pLocale)
	{
		if (pNames.isEmpty())
		{
			return WizardMessages.get(pLocale, "wizard.intake.job.none");
		}
		final StringBuilder out = new StringBuilder();
		for (final String name : pNames)
		{
			out.append(out.length() == 0 ? "" : ", ").append(name);
		}
		return out.toString();
	}

	private static List<String> sourceNames(final Adaptation pFactory, final String pProjectCode)
	{
		final List<String> names = new ArrayList<String>();
		final Adaptation project = IntakeSwitchRun.findProject(pFactory, pProjectCode);
		if (project != null)
		{
			for (final IntakeSources.Info source : IntakeSources.read(pFactory, project))
			{
				names.add(source.sourceSystem);
			}
		}
		return names;
	}

	/**
	 * The line the set-up screen shows about the installation of the triggers, or {@code ""} when there is nothing to say: the latest switch-on job of the project is waiting or
	 * running ("installing... installed on A, not yet on B") or ended without finishing ("interrupted: installed on A, not on B, switch on again"). A finished job says nothing (the
	 * intake is ON). Reads one job row.
	 */
	public static String stateLine(final Adaptation pFactory, final String pProjectId, final Locale pLocale)
	{
		final Adaptation row = FactoryJobQueue.latest(pFactory, pProjectId, IntakeSwitchRun.ON_STEP);
		if (row == null)
		{
			return "";
		}
		final String status = row.getString(FactoryPaths.FactoryJob.STATUS);
		if (FactoryJobQueue.DONE.equals(status))
		{
			return "";
		}
		JsonNode params;
		try
		{
			final String raw = row.getString(FactoryPaths.FactoryJob.PARAMS);
			params = IntakeSwitchRun.MAPPER.readTree(raw == null || raw.isEmpty() ? "{}" : raw);
		}
		catch (final java.io.IOException ex)
		{
			return "";
		}
		final List<String> installed = IntakeSwitchRun.installedNames(params.path("installed"));
		final List<String> missing = IntakeSwitchRun.missing(IntakeSwitchRun.sourceNames(pFactory, params.path("projectCode").asText("")), installed);
		final boolean live = FactoryJobQueue.QUEUED.equals(status) || FactoryJobQueue.RUNNING.equals(status);
		return WizardMessages.get(pLocale, live ? "wizard.intake.job.installing" : "wizard.intake.job.interrupted", IntakeSwitchRun.join(installed, pLocale),
			IntakeSwitchRun.join(missing, pLocale));
	}

	private static Locale localeFromParams(final JsonNode pParams)
	{
		final String tag = pParams.path("locale").asText("");
		return tag.isEmpty() ? Locale.ENGLISH : Locale.forLanguageTag(tag);
	}

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}
}
