package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sources-landing lot SOURCES (2026-09-24): pure mapping from {@link SourceOnboarding}'s six-step
 * connection-catalog-source diagnosis (connection, catalogOperation, catalogSource, scanFlows, scan, ready)
 * to the FOUR-stage horizontal pipeline the mockups ({@code docs/mockups/sources-landing/SourcesAdding.dc.html})
 * show on one application's own progress card: <b>Connection -&gt; Catalog -&gt; Scan -&gt; Ready</b>.
 * <p>
 * The grouping (brief, task SOURCES ss2): Connection = {@code connection}; Catalog = {@code catalogOperation}
 * + {@code catalogSource} + {@code scanFlows}; Scan = {@code scan}; Ready = {@code ready}. A grouped stage
 * combines several {@link SourceOnboarding.Step}s into one {@link Stage}, so its state and one-line text
 * follow a priority order, evaluated left to right over the group's own sub-steps:
 * <ol>
 * <li>any sub-step {@link SourceOnboarding.State#BLOCKED} -&gt; {@link StageState#NEEDS_PERSON} - a person
 * must act (an {@code OPEN_CONNECTION} link, or nothing to do but pick a different connection), text taken
 * from the FIRST blocked sub-step found;</li>
 * <li>else any sub-step {@link SourceOnboarding.State#WAITING} -&gt; {@link StageState#WAITING} - this stage
 * has not been reached yet because an earlier one is not done, text from the first waiting sub-step (always
 * the group's own {@code wizard.onboard.step.waiting} key, so every group renders it identically);</li>
 * <li>else any sub-step {@link SourceOnboarding.State#TODO} -&gt; {@link StageState#RUNNING} - everything left
 * to do here is automatic (a {@code CATALOG_APPLY}/{@code SCAN_RUN} action {@code ebx.SourcesRun} runs by
 * itself in the background, task brief ss2), text from the first TODO sub-step;</li>
 * <li>else every sub-step {@link SourceOnboarding.State#DONE} -&gt; {@link StageState#DONE}, text from the
 * LAST sub-step (the most specific: e.g. {@code scanFlows.done}'s mapping id rather than {@code
 * catalogOperation.done}'s bare connector type).</li>
 * </ol>
 * A single-step stage (Connection, Scan, Ready) uses the very same rule with a group of one, so there is only
 * one state/text algorithm in this class, not two.
 * <p>
 * {@link StageState#NEEDS_PERSON} is the ONLY state the card renders orange (brief ss2: "orange only when a
 * person must act"); {@link StageState#RUNNING} is real automatic work in flight or about to be picked up by
 * the next {@code ebx.SourcesRun} pass, never shown as an error.
 */
public final class ApplicationStages
{
	private static final String KEY_CONNECTION = "connection";
	private static final String KEY_CATALOG_OPERATION = "catalogOperation";
	private static final String KEY_CATALOG_SOURCE = "catalogSource";
	private static final String KEY_SCAN_FLOWS = "scanFlows";
	private static final String KEY_SCAN = "scan";
	private static final String KEY_READY = "ready";

	private ApplicationStages()
	{
	}

	public enum StageState
	{
		DONE, RUNNING, WAITING, NEEDS_PERSON
	}

	/** One of the four cards on an application's progress row. */
	public static final class Stage
	{
		public final String key;
		public final StageState state;
		/** Same {@code MessageFormat} convention as {@link SourceOnboarding.Step}: never pre-formatted, the UI resolves it in the viewer's locale. */
		public final String messageKey;
		public final Object[] args;
		/** Carried through from the representative sub-step, unmodified - {@code OPEN_CONNECTION} is the only action a {@link StageState#NEEDS_PERSON} stage can offer a link for; every other stage's action is {@code NONE} (the "Check again"/"Do everything" buttons live at the application level, not per stage - see {@code SourceOnboarding.Diagnosis#nextAction}). */
		public final SourceOnboarding.Action action;

		Stage(final String pKey, final StageState pState, final String pMessageKey, final Object[] pArgs, final SourceOnboarding.Action pAction)
		{
			this.key = pKey;
			this.state = pState;
			this.messageKey = pMessageKey;
			this.args = pArgs;
			this.action = pAction;
		}
	}

	/** The four stages, in card order, plus the source-level flags a card also needs. */
	public static final class Pipeline
	{
		public final List<Stage> stages;
		/** True once every stage is {@link StageState#DONE} - equivalent to {@link SourceOnboarding.Diagnosis#ready}, kept here too so a caller holding only a {@link Pipeline} never needs the original {@link SourceOnboarding.Diagnosis}. */
		public final boolean ready;
		/** True when at least one stage is {@link StageState#NEEDS_PERSON} - drives the card's own "needs attention" badge, independent of which stage it is. */
		public final boolean needsPerson;

		Pipeline(final List<Stage> pStages, final boolean pReady, final boolean pNeedsPerson)
		{
			this.stages = pStages;
			this.ready = pReady;
			this.needsPerson = pNeedsPerson;
		}

		public Stage stage(final String pKey)
		{
			for (final Stage stage : this.stages)
			{
				if (stage.key.equals(pKey))
				{
					return stage;
				}
			}
			return null;
		}
	}

	/**
	 * {@code null}-safe: a diagnosis with fewer/extra steps than {@link SourceOnboarding} currently emits
	 * (a future revision of that class) still produces a {@link Pipeline} rather than a
	 * {@code NullPointerException} - a missing group member is treated as WAITING, via {@link #waitingStub}.
	 */
	public static Pipeline from(final SourceOnboarding.Diagnosis pDiagnosis)
	{
		final List<Stage> stages = new ArrayList<Stage>();
		stages.add(ApplicationStages.group("connection", ApplicationStages.stepOrWaiting(pDiagnosis, ApplicationStages.KEY_CONNECTION)));
		stages.add(ApplicationStages.group("catalog", ApplicationStages.stepOrWaiting(pDiagnosis, ApplicationStages.KEY_CATALOG_OPERATION),
			ApplicationStages.stepOrWaiting(pDiagnosis, ApplicationStages.KEY_CATALOG_SOURCE), ApplicationStages.stepOrWaiting(pDiagnosis, ApplicationStages.KEY_SCAN_FLOWS)));
		stages.add(ApplicationStages.group("scan", ApplicationStages.stepOrWaiting(pDiagnosis, ApplicationStages.KEY_SCAN)));
		stages.add(ApplicationStages.group("ready", ApplicationStages.stepOrWaiting(pDiagnosis, ApplicationStages.KEY_READY)));

		boolean ready = true;
		boolean needsPerson = false;
		for (final Stage stage : stages)
		{
			if (stage.state != StageState.DONE)
			{
				ready = false;
			}
			if (stage.state == StageState.NEEDS_PERSON)
			{
				needsPerson = true;
			}
		}
		return new Pipeline(Collections.unmodifiableList(stages), ready, needsPerson);
	}

	// -------------------------------------------------------------------------------------------------- combination

	/**
	 * The group's state/text come from the FIRST sub-step, IN THE GIVEN ORDER, that is not {@link
	 * SourceOnboarding.State#DONE} - never from pooling every sub-step's state independently. This matters
	 * because {@link SourceOnboarding#diagnose}'s own cascade means a later sub-step reads {@link
	 * SourceOnboarding.State#WAITING} whenever an EARLIER one in the same group is not yet {@code DONE} (e.g.
	 * {@code scanFlows} is WAITING while {@code catalogSource} is still TODO) - such a trailing WAITING must
	 * never outrank the earlier step's own TODO/BLOCKED, or an application whose catalog write is genuinely
	 * about to run automatically would incorrectly show as "not reached yet" instead of {@link
	 * StageState#RUNNING}. Once every sub-step is DONE, the LAST one's text is used (most specific - see this
	 * class's own Javadoc).
	 */
	private static Stage group(final String pKey, final SourceOnboarding.Step... pSteps)
	{
		SourceOnboarding.Step last = null;
		for (final SourceOnboarding.Step step : pSteps)
		{
			last = step;
			if (step.state == SourceOnboarding.State.DONE)
			{
				continue;
			}
			final StageState state;
			switch (step.state)
			{
				case BLOCKED:
					state = StageState.NEEDS_PERSON;
					break;
				case TODO:
					state = StageState.RUNNING;
					break;
				default: // WAITING
					state = StageState.WAITING;
			}
			return new Stage(pKey, state, step.messageKey, step.args, step.action);
		}
		// every sub-step DONE (pSteps is never empty - from() always passes at least one)
		return new Stage(pKey, StageState.DONE, last.messageKey, last.args, SourceOnboarding.Action.NONE);
	}

	private static SourceOnboarding.Step stepOrWaiting(final SourceOnboarding.Diagnosis pDiagnosis, final String pKey)
	{
		for (final SourceOnboarding.Step step : pDiagnosis.steps)
		{
			if (step.key.equals(pKey))
			{
				return step;
			}
		}
		return ApplicationStages.waitingStub(pKey);
	}

	/** {@code SourceOnboarding.Step}'s constructor is package-private (same package, {@code com.on.ebx.factory.wizard}) - built directly rather than duplicating its shape here, for the defensive "step not found" case {@link #from} documents. */
	private static SourceOnboarding.Step waitingStub(final String pKey)
	{
		return new SourceOnboarding.Step(pKey, SourceOnboarding.State.WAITING, "wizard.onboard.step.waiting", new Object[0], SourceOnboarding.Action.NONE);
	}
}
