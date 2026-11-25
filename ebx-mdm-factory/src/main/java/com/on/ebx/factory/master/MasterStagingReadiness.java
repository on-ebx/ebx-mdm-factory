package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Whether {@code ebx.MasterGenerator} may proceed past its "the project has loaded staging data" guard - a
 * coordinator review, 2026-09-20, of a live incident: {@code PipelineSteps.isDone(..., "STAGING_LOAD", "")}
 * answered {@code false} even though the step genuinely was {@code DONE} (a separate bug, fixed in {@code
 * ebx.PipelineSteps.scopeEquals} - see its own Javadoc), and the guard trusted that single signal alone. Even
 * with that bug fixed, step HISTORY is not the only fact that should ever gate this: a {@code PipelineStep}
 * row can be cleaned up or never recorded by an older run, while the actual data - {@code
 * StagingEntity.lastLoadWritten} - remains the ground truth of whether anything was ever transferred.
 * <p>
 * {@link #decide} therefore accepts EITHER signal: the {@code STAGING_LOAD} step reporting {@code DONE}, OR at
 * least one PROMOTED entity with {@code lastLoadWritten > 0} (the coordinator's own wording: "at least one").
 * A project is refused only when NEITHER signal holds for ANY promoted entity - in which case every promoted
 * entity name is reported back, not just a generic refusal, so the message can say exactly which entities are
 * not loaded.
 * <p>
 * Pure: no EBX class, unit-tested. {@code ebx.MasterGenerator} is the only caller, supplying the step's own
 * {@code isDone} result and each promoted entity's {@code lastLoadWritten} read directly off {@code
 * StagingEntity}.
 */
public final class MasterStagingReadiness
{
	private MasterStagingReadiness()
	{
	}

	/** One promoted entity's own load evidence, as read from {@code StagingEntity.lastLoadWritten} plus {@code lastLoadUnchanged} since lot V3-S (2026-10-04: rows a load found up to date are loaded rows too; 0 when never loaded or the fields are null). */
	public static final class EntityLoad
	{
		public final String name;
		public final long written;

		public EntityLoad(final String pName, final long pWritten)
		{
			this.name = pName;
			this.written = pWritten;
		}
	}

	public static final class Decision
	{
		public final boolean ready;
		/** Every promoted entity name with no load evidence at all - populated only when {@link #ready} is false (in which case it names every promoted entity, since none has evidence). Empty (never null) when {@link #ready}. */
		public final List<String> notLoaded;

		private Decision(final boolean pReady, final List<String> pNotLoaded)
		{
			this.ready = pReady;
			this.notLoaded = pNotLoaded;
		}
	}

	public static Decision decide(final boolean pStagingLoadStepDone, final List<EntityLoad> pPromotedEntities)
	{
		final List<String> notLoaded = new ArrayList<String>();
		boolean anyWritten = false;
		for (final EntityLoad entity : pPromotedEntities)
		{
			if (entity.written > 0)
			{
				anyWritten = true;
			}
			else
			{
				notLoaded.add(entity.name);
			}
		}
		final boolean ready = pStagingLoadStepDone || anyWritten;
		return new Decision(ready, ready ? Collections.<String>emptyList() : notLoaded);
	}
}
