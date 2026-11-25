package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.LoadPauseRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Lifts, at the start of the module, the pauses the factory applied for a load that a stop of the server never let resume them: a REALTIME distribution target left
 * {@code PAUSED} and the Match and Merge matching of a master table left paused. What counts as one is decided by {@link LoadPauseLedger} (a row written before
 * each pause, deleted after its resume) and {@link LoadPauseRule}: only a row whose load is gone, and only a thing that still IS paused, is touched - a pause a person
 * set never has a row, and a thing somebody already resumed only has its row dropped.
 * <p>
 * It runs on its own daemon thread, never inside {@code handleRepositoryStartup}: the match-merge server and Connectors are web applications that are not up yet at that
 * point, so the first pass waits {@link #FIRST_PASS_DELAY_MS} and a thing that cannot be read yet is retried every {@link #RETRY_DELAY_MS} up to {@link #MAX_PASSES}
 * passes. Every pause lifted is logged at WARN with the step and the user that paused it; one that could not be lifted is logged at ERROR (and said on the interrupted
 * step's message) with the exact call that lifts it by hand, and its row stays so that the next start tries again. After a target is re-armed, the {@code changed}
 * catch-up send that the end of a load would have made is attempted at most {@link #MAX_CATCH_UP_ATTEMPTS} times.
 * <p>
 * Not exercised against a running instance when written: see docs/DISTRIBUTION.md "A pause that outlives its load".
 */
final class LoadPauseRecovery
{
	static final long FIRST_PASS_DELAY_MS = 30000L;
	static final long RETRY_DELAY_MS = 60000L;
	static final int MAX_PASSES = 30;
	static final int MAX_CATCH_UP_ATTEMPTS = 5;

	private static volatile Thread thread;

	/** What the passes of one start remember between them: the catch-up sends still owed (key {@code projectCode|targetId}, value = attempts made) and the rows already reported. */
	static final class State
	{
		final Map<String, Integer> catchUpAttempts = new LinkedHashMap<String, Integer>();
		final Set<String> reported = new HashSet<String>();
	}

	private LoadPauseRecovery()
	{
	}

	/** Module start: the first pass runs on its own thread after a delay; a start with an empty ledger ends the thread after that one read. */
	static synchronized void start(final Repository pRepository, final Session pSession)
	{
		LoadPauseRecovery.stop();
		final Thread worker = new Thread("ebx-mdm-factory-pause-recovery")
		{
			@Override
			public void run()
			{
				LoadPauseRecovery.loop(pRepository, pSession);
			}
		};
		worker.setDaemon(true);
		LoadPauseRecovery.thread = worker;
		worker.start();
	}

	/** Module stop: the thread must not outlive the class loader that started it. */
	static synchronized void stop()
	{
		final Thread worker = LoadPauseRecovery.thread;
		LoadPauseRecovery.thread = null;
		if (worker != null)
		{
			worker.interrupt();
		}
	}

	private static void loop(final Repository pRepository, final Session pSession)
	{
		final State state = new State();
		long delay = LoadPauseRecovery.FIRST_PASS_DELAY_MS;
		for (int pass = 1; pass <= LoadPauseRecovery.MAX_PASSES; pass++)
		{
			try
			{
				Thread.sleep(delay);
			}
			catch (final InterruptedException ex)
			{
				return;
			}
			if (LoadPauseRecovery.thread != Thread.currentThread())
			{
				return;
			}
			delay = LoadPauseRecovery.RETRY_DELAY_MS;
			boolean pending;
			try
			{
				final Adaptation factory = FactoryInstaller.dataset(pRepository);
				pending = LoadPauseRecovery.pass(pSession, factory, state, pass == LoadPauseRecovery.MAX_PASSES);
			}
			catch (final RuntimeException | LinkageError ex)
			{
				LoggingCategory.getKernel().error("[mdm-factory] the recovery of the pauses left by a load that never ended failed (pass " + pass + "): " + ex, ex);
				pending = true;
			}
			if (!pending)
			{
				return;
			}
		}
	}

	/**
	 * One pass over the ledger; true when something is left to retry (a row kept, or a catch-up not made yet). {@code pLast}: the last pass, so what cannot be done
	 * is said as final.
	 */
	static boolean pass(final Session pSession, final Adaptation pFactory, final State pState, final boolean pLast)
	{
		boolean pending = false;
		final List<LoadPauseLedger.Row> rows = LoadPauseLedger.readAll(pFactory);
		for (final LoadPauseLedger.Row row : rows)
		{
			try
			{
				pending |= LoadPauseRecovery.recoverRow(pSession, pFactory, row, pState, pLast);
			}
			catch (final RuntimeException ex)
			{
				LoggingCategory.getKernel().error("[mdm-factory] could not recover the " + row.kind + " pause " + row.target + " left by " + row.ownerStep + ": " + ex, ex);
				pending = true;
			}
		}
		// catch-up sends owed by targets re-armed in an earlier pass
		final List<String> owed = new ArrayList<String>(pState.catchUpAttempts.keySet());
		for (final String key : owed)
		{
			pending |= LoadPauseRecovery.catchUp(pSession, pFactory, key, pState.catchUpAttempts);
		}
		return pending;
	}

	private static boolean recoverRow(final Session pSession, final Adaptation pFactory, final LoadPauseLedger.Row pRow, final State pState,
		final boolean pLast)
	{
		final boolean alive = LoadPauseLedger.isLive(pRow.ownerToken);
		final Adaptation project = pRow.projectId == null ? null : pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRow.projectId));
		final String projectCode = project == null ? null : project.getString(FactoryPaths.Project.CODE);
		final String origin = pRow.ownerStep + " of project " + (projectCode == null ? pRow.projectId : projectCode) + " (started by " + pRow.pausedBy + ")";
		if (FactoryPaths.LoadPause.KIND_DISTRIBUTION_TARGET.equals(pRow.kind))
		{
			final Adaptation target = DistributionConfigurator.findTarget(pFactory, pRow.target);
			final LoadPauseRule.Action action = LoadPauseRule.decide(alive,
				LoadPauseRule.observeTarget(target != null, target == null ? null : target.getString(FactoryPaths.DistributionTarget.STATE)));
			if (action == LoadPauseRule.Action.KEEP)
			{
				return true;
			}
			if (action == LoadPauseRule.Action.DROP || projectCode == null)
			{
				LoadPauseLedger.delete(pSession, pFactory, pRow.id);
				return false;
			}
			try
			{
				DistributionPause.setState(pSession, pFactory, pRow.target, FactoryPaths.DistributionTarget.STATE_ARMED);
			}
			catch (final OperationException | RuntimeException ex)
			{
				if (pState.reported.add(pRow.id))
				{
					LoggingCategory.getKernel().error("[mdm-factory] distribution target " + pRow.target + " is still PAUSED: the load that paused it (" + origin + ") never ended and "
						+ "re-arming it failed: " + ex.getMessage() + ". Re-arm it from the project's Distribution screen; the module retries for a while and again at its next start.");
					LoadPauseRecovery.say(pSession, pFactory, pRow, "distribution target " + pRow.target + " is still PAUSED after the stop that interrupted this step (re-arming failed: "
						+ ex.getMessage() + ")");
				}
				return true;
			}
			LoggingCategory.getKernel().warn("[mdm-factory] distribution target " + pRow.target + " was left PAUSED by " + origin + ", a load that never ended: re-armed at module start.");
			LoadPauseLedger.delete(pSession, pFactory, pRow.id);
			pState.catchUpAttempts.put(projectCode + "|" + pRow.target, Integer.valueOf(0));
			return true;
		}
		if (FactoryPaths.LoadPause.KIND_MATCHING_TABLE.equals(pRow.kind))
		{
			if (alive)
			{
				return true;
			}
			if (projectCode == null)
			{
				LoadPauseLedger.delete(pSession, pFactory, pRow.id);
				return false;
			}
			final String[] message = new String[1];
			final MatchingLoadPause.Recovery recovery = MatchingLoadPause.recoverTable(pSession, pFactory, projectCode, pRow.projectId, pRow.target, message);
			switch (recovery)
			{
				case RESUMED:
					LoggingCategory.getKernel().warn("[mdm-factory] matching of " + pRow.target + " was left paused by " + origin + ", a load that never ended: resumed at module start. "
						+ "The records written during that load are not matched: run the matching on the whole table.");
					LoadPauseLedger.delete(pSession, pFactory, pRow.id);
					return false;
				case NOT_PAUSED:
					LoadPauseLedger.delete(pSession, pFactory, pRow.id);
					return false;
				case RESUME_FAILED:
					if (pState.reported.add(pRow.id))
					{
						LoggingCategory.getKernel().error("[mdm-factory] matching of " + pRow.target + " is still PAUSED: the load that paused it (" + origin + ") never ended and resuming it "
							+ "failed: " + message[0] + ". Resume it with set_table_settings {\"table\":\"" + pRow.target + "\",\"settings\":{\"pauseMatching\":false}} on the match-merge "
							+ "server; the module retries for a while and again at its next start.");
						LoadPauseRecovery.say(pSession, pFactory, pRow, "matching of " + pRow.target + " is still PAUSED after the stop that interrupted this step (resuming failed: "
							+ message[0] + ")");
					}
					return true;
				default:
					if (pLast && pState.reported.add(pRow.id))
					{
						LoggingCategory.getKernel().error("[mdm-factory] matching of " + pRow.target + " may still be PAUSED (left by " + origin + "): its state could not be read in "
							+ LoadPauseRecovery.MAX_PASSES + " passes (" + message[0] + "). The row is kept: the module tries again at its next start.");
						LoadPauseRecovery.say(pSession, pFactory, pRow, "matching of " + pRow.target + " may still be PAUSED after the stop that interrupted this step "
							+ "(its state could not be read at start)");
					}
					return true;
			}
		}
		LoggingCategory.getKernel().error("[mdm-factory] unknown kind '" + pRow.kind + "' in the load-pause row " + pRow.id + ": dropped.");
		LoadPauseLedger.delete(pSession, pFactory, pRow.id);
		return false;
	}

	/** The catch-up send a load makes after its targets are re-armed: {@code changed}, at most {@link #MAX_CATCH_UP_ATTEMPTS} times; true while it is still owed. */
	private static boolean catchUp(final Session pSession, final Adaptation pFactory, final String pKey, final Map<String, Integer> pAttempts)
	{
		final int separator = pKey.indexOf('|');
		final String projectCode = pKey.substring(0, separator);
		final String targetId = pKey.substring(separator + 1);
		final int attempt = pAttempts.get(pKey).intValue() + 1;
		pAttempts.put(pKey, Integer.valueOf(attempt));
		String failure = null;
		try
		{
			final com.fasterxml.jackson.databind.node.ObjectNode result = DistributionRunner.sendNow(pSession, pFactory, projectCode, targetId, "changed");
			if (!"DONE".equals(result.path("status").asText("DONE")))
			{
				failure = result.path("message").asText("no reason given");
			}
		}
		catch (final OperationException | RuntimeException ex)
		{
			failure = ex.getMessage();
		}
		if (failure == null)
		{
			LoggingCategory.getKernel().info("[mdm-factory] catch-up send of distribution target " + targetId + " (project " + projectCode + ") made at module start.");
			pAttempts.remove(pKey);
			return false;
		}
		if (attempt >= LoadPauseRecovery.MAX_CATCH_UP_ATTEMPTS)
		{
			LoggingCategory.getKernel().error("[mdm-factory] the catch-up send of distribution target " + targetId + " (project " + projectCode + ") failed " + attempt + " times (" + failure
				+ "): the target is ARMED again but the records written while it was paused are not sent. Run 'Send now' (changed) on it.");
			pAttempts.remove(pKey);
			return false;
		}
		return true;
	}

	/** Puts the problem on the interrupted step the load left (best-effort). */
	private static void say(final Session pSession, final Adaptation pFactory, final LoadPauseLedger.Row pRow, final String pSentence)
	{
		try
		{
			final String stepId = pRow.projectId == null || pRow.ownerStep == null ? null : PipelineSteps.latestStepId(pFactory, pRow.projectId, pRow.ownerStep);
			if (stepId != null)
			{
				PipelineSteps.amendEnded(pSession, pFactory, stepId, "WARNING: " + pSentence + ".", null, "pauseRecovery",
					new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("kind", pRow.kind).put("target", pRow.target).put("problem", pSentence));
			}
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not put the pause-recovery warning on the " + pRow.ownerStep + " step: " + ex);
		}
	}
}
