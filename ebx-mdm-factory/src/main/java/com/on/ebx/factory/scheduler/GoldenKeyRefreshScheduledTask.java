package com.on.ebx.factory.scheduler;

import com.on.ebx.factory.ebx.EngineRefusal;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.ebx.FactoryInstaller;
import com.on.ebx.factory.ebx.MatchingConfigurator;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.scheduler.ScheduledExecutionContext;
import com.orchestranetworks.scheduler.ScheduledTask;
import com.orchestranetworks.scheduler.ScheduledTaskInterruption;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * A Task Scheduler task that keeps {@code Xref.goldenKey} fresh on a schedule, by calling {@link
 * MatchingConfigurator#refreshGoldenKeys} directly - the SAME call {@code FactoryResource}'s {@code POST
 * /projects/{code}/matching/refresh-golden-keys} route and a manual admin click already use
 * (`docs/MATCHING.md`, "Keeping Xref.goldenKey fresh (2026-09-23)"). Never touches the add-on's matching
 * engine itself - only re-reads {@code RecordMetadata} and rewrites a stale cache, so running it far more
 * often than a real matching pass is safe, which matters since nothing else reacts to a steward's
 * Unmerge/Change-state click through the add-on's own standard services (no hook exists on that path at
 * all - the add-on's public API has no listener/callback type, confirmed by listing {@code
 * ebx-addons.jar}).
 * <p>
 * <b>Unlike {@code com.on.ebx.factory.workflow.MatchingRunTaskBean}, this class needs NO {@code
 * module.xml} declaration.</b> A workflow {@code scriptTask} bean is resolved through a module's own
 * classloader via a {@code <beans>} block the module descriptor declares (`docs/MATCHING.md` &sect;18,
 * confirmed live 2026-09-22) - a Task Scheduler task is a completely different registration mechanism,
 * entirely at RUNTIME: a plain row on the Scheduler REST API's own {@code /tasks} endpoint (CLAUDE.md
 * &sect;2, "taskScheduler" bullet), body {@code [{"key":"...","classToInstantiate":"com.on.ebx.factory.
 * scheduler.GoldenKeyRefreshScheduledTask","module":"<this module's module.xml name>","recover":...}]} -
 * a BARE JSON ARRAY, never the Data Services {@code {"rows":[...]}} envelope. <b>Never set {@code
 * userReference} on that row</b> - CLAUDE.md's own measured trap: the row is accepted and looks identical
 * to a UI-created one, but every execution then dies inside EBX before this class' {@link #execute} ever
 * runs, with {@code ClassCastException: String cannot be cast to UserReference}
 * ({@code ExecutableTask.createSession}); a {@code PUT} of the task without the field fixes it. The
 * SCHEDULE itself (the cron expression, {@code taskKey}, and the {@code parameters} map this class' own
 * bean properties below become) is a separate {@code POST .../schedules} call - see that same CLAUDE.md
 * bullet and `docs/MATCHING.md`'s own section on this lot for the exact sequence.
 * <p>
 * <b>Bean properties are the schedule's {@code parameters} map</b>, plain private fields with public
 * getter/setter pairs - the same "plain bean property" convention {@code MatchingRunTaskBean} already uses
 * for a workflow {@code beanParams} binding (`docs/MATCHING.md` &sect;18.1), reused here for the
 * scheduler's own {@code parameters} map instead.
 * <p>
 * <b>No permission check here, on purpose</b> - same reasoning as {@code MatchingRunTaskBean}'s own class
 * Javadoc: a scheduled task runs under whatever session the Scheduler itself opens for it (the built-in
 * {@code ExecutableTask.createSession}), not a caller this class could authorize or refuse; restricting
 * who may CREATE or MODIFY the schedule is the Scheduler's/Administration's own job, not this class'.
 * <p>
 * <b>Never run live this session</b> (task constraint, no EBX instance reachable) - {@code
 * com.orchestranetworks.scheduler.ScheduledTask}/{@code ScheduledExecutionContext}'s exact method
 * signatures were confirmed by {@code javap} against {@code ebx-lib.jar} (2026-09-23: a single {@code
 * execute(ScheduledExecutionContext)} entry point, {@code getRepository}/{@code getSession}, {@code
 * addExecutionInformation(String)} for a one-line summary - matching CLAUDE.md's own quote exactly, no
 * drift) - nothing below was exercised against a running scheduler.
 */
public final class GoldenKeyRefreshScheduledTask extends ScheduledTask
{
	private String projectCode;
	private String entity;

	public String getProjectCode()
	{
		return this.projectCode;
	}

	public void setProjectCode(final String pProjectCode)
	{
		this.projectCode = pProjectCode;
	}

	/** Optional - blank/null refreshes every promoted entity of the project ({@link MatchingConfigurator#refreshGoldenKeys}'s own blank-means-all convention). */
	public String getEntity()
	{
		return this.entity;
	}

	public void setEntity(final String pEntity)
	{
		this.entity = pEntity;
	}

	/** Pure, unit-testable: {@code projectCode} is required (blank/null both refused), mirrors {@code MatchingRunTaskBean#isScopeAll}'s own "extract the plain-string decision, test it directly" discipline. */
	static boolean hasRequiredProjectCode(final String pProjectCode)
	{
		return pProjectCode != null && !pProjectCode.trim().isEmpty();
	}

	/**
	 * Pure, unit-testable: the one-line {@link ScheduledExecutionContext#addExecutionInformation} summary,
	 * built from the already-computed counts of {@link MatchingConfigurator#refreshGoldenKeys}'s own top-level
	 * {@code refreshed}/{@code refused} arrays - never the full result JSON (which could carry one entry per
	 * entity and is not what "one-line summary" the task brief asked for means).
	 *
	 * @param pEntityOrNull the {@code entity} bean property as configured - blank/null renders as "every
	 *            promoted entity", matching {@link MatchingConfigurator#refreshGoldenKeys}'s own convention.
	 */
	static String summary(final String pProjectCode, final String pEntityOrNull, final int pRefreshedCount, final int pRefusedCount)
	{
		final String scope = pEntityOrNull == null || pEntityOrNull.trim().isEmpty() ? "every promoted entity" : "entity " + pEntityOrNull.trim();
		return "Xref.goldenKey refresh for project " + pProjectCode + " (" + scope + "): " + pRefreshedCount + " entit" + (pRefreshedCount == 1 ? "y" : "ies")
			+ " refreshed, " + pRefusedCount + " refused.";
	}

	@Override
	public void execute(final ScheduledExecutionContext pContext) throws OperationException, ScheduledTaskInterruption
	{
		if (!GoldenKeyRefreshScheduledTask.hasRequiredProjectCode(this.projectCode))
		{
			throw EngineRefusal.coded("invalid.taskNoProject", "GoldenKeyRefreshScheduledTask: 'projectCode' is required", "GoldenKeyRefreshScheduledTask");
		}
		final Repository repository = pContext.getRepository();
		final Session session = pContext.getSession();
		FactoryInstaller.ensure(repository, session);
		final Adaptation dataset = FactoryInstaller.dataset(repository);
		if (dataset == null)
		{
			throw EngineRefusal.coded("missing.taskNoDataset", "GoldenKeyRefreshScheduledTask: the MDM Factory dataset is not installed", "GoldenKeyRefreshScheduledTask");
		}
		final ObjectNode result = MatchingConfigurator.refreshGoldenKeys(session, dataset, this.projectCode, this.entity);
		final int refreshedCount = result.has("refreshed") && result.get("refreshed").isArray() ? result.get("refreshed").size() : 0;
		final int refusedCount = result.has("refused") && result.get("refused").isArray() ? result.get("refused").size() : 0;
		pContext.addExecutionInformation(GoldenKeyRefreshScheduledTask.summary(this.projectCode, this.entity, refreshedCount, refusedCount));
	}
}
