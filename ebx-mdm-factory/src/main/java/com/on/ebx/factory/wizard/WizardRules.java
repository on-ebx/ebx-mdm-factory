package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.on.ebx.factory.landing.LandingReadiness;

/**
 * Pure rules of the "Assistant de projet" wizard: which of the 7 steps (mockup screen 2) is reachable
 * given a {@link WizardSnapshot} and why not otherwise ({@link #stepStates}), what step to reopen on
 * when the wizard is resumed ({@link #suggestedStep}), the source-system code proposed from a schema
 * name ({@link #suggestSourceSystemCode}), validation of the step 1/2 inputs ({@link #projectProblems}/
 * {@link #sourceProblems}), and whether each action button is enabled with why not otherwise
 * ({@link #actionGates}). No EBX class: unit-tested as is, against fixtures built by hand or copied from
 * {@code WizardData} in a real run.
 */
public final class WizardRules
{
	public static final int STEP_PROJECT = 1;
	public static final int STEP_SOURCES = 2;
	public static final int STEP_LANDING = 3;
	public static final int STEP_STAGING = 4;
	public static final int STEP_TRANSFERS = 5;
	public static final int STEP_MATCHING = 6;
	/**
	 * Distribution lot 3 (wizard UI, 2026-09-24, SPEC-distribution.md): new optional step between Matching
	 * and Summary - "where do golden records go". Reachable on the SAME prerequisite as Matching (the master
	 * is loaded) so it can be visited any time after that, independently of whether matching has run yet
	 * (SPEC-distribution.md: distribution targets and the data product both only need the master's golden
	 * records to exist, not a finished matching pass - a REALTIME target's own arming gate, {@code
	 * DistributionArmingGate}, separately requires a first matching run before it can arm). Never blocks
	 * {@link #STEP_SUMMARY} (see {@link #stepStates}) - a person can always skip it.
	 */
	public static final int STEP_DISTRIBUTION = 7;
	public static final int STEP_SUMMARY = 8;
	/** The number of steps of the CONSOLIDATION path (the wizard's original), kept for the callers that mean exactly that path; see {@link #steps}. */
	public static final int STEP_COUNT = 8;
	/**
	 * Lot C1-B (2026-10-12, docs/LOT-C1.md sec 6): the steps of the CENTRALISATION path. A step id is a stable name for a panel
	 * ({@code data-step}), not a position: {@link #steps} gives the order a style shows them in, and {@link #positionOf} the number a
	 * person reads. {@link #STEP_PROJECT} and {@link #STEP_SUMMARY} are shared by both paths.
	 */
	public static final int STEP_PACK = 9;
	public static final int STEP_SPECIFICATION = 10;
	public static final int STEP_PROPOSAL = 11;
	public static final int STEP_GENERATION = 12;
	/** The highest step id: the size (minus one) of the {@link #stepStates} array. */
	public static final int STEP_MAX = 12;

	private static final int[] STEPS_CONSOLIDATION = { STEP_PROJECT, STEP_SOURCES, STEP_LANDING, STEP_STAGING, STEP_TRANSFERS, STEP_MATCHING, STEP_DISTRIBUTION, STEP_SUMMARY };
	private static final int[] STEPS_CENTRALISATION = { STEP_PROJECT, STEP_PACK, STEP_SPECIFICATION, STEP_PROPOSAL, STEP_GENERATION, STEP_SUMMARY };

	/** The ordered step ids shown for an implementation style ({@code Project.style}); CONSOLIDATION and any unknown or empty code show the original 8. A copy. */
	public static int[] steps(final String pStyleCode)
	{
		return (WizardRules.CENTRALISATION.equals(pStyleCode) ? WizardRules.STEPS_CENTRALISATION : WizardRules.STEPS_CONSOLIDATION).clone();
	}

	/** {@link #steps} of the style of the project in the snapshot (CONSOLIDATION when there is no project yet). */
	public static int[] steps(final WizardSnapshot pSnapshot)
	{
		return WizardRules.steps(pSnapshot == null || pSnapshot.project == null ? null : pSnapshot.project.style);
	}

	/** 1-based position of step {@code pStep} in the list of {@code pStyleCode}, 0 when the style does not show it. */
	public static int positionOf(final String pStyleCode, final int pStep)
	{
		final int[] steps = WizardRules.steps(pStyleCode);
		for (int i = 0; i < steps.length; i++)
		{
			if (steps[i] == pStep)
			{
				return i + 1;
			}
		}
		return 0;
	}

	public static final String CENTRALISATION = "CENTRALISATION";

	/** Source-system code pattern, {@code ProjectSource.sourceSystem} in MdmFactory.xsd. Public: sources-landing lot SOURCES (2026-09-24) also validates/de-duplicates a code against this same pattern/length from {@code ebx.SourcesRun} (a different package) and, per the task brief, will from the Review screen's own code editor - one pattern, never re-declared. */
	public static final String CODE_PATTERN = "[A-Za-z][A-Za-z0-9_]*";
	public static final int CODE_MAX = 40;
	private static final int LABEL_MAX = 120;
	private static final int DESCRIPTION_MAX = 2000;

	private WizardRules()
	{
	}

	// ------------------------------------------------------------------------------------------------ step states

	/** Whether a step can be opened, and if not, the {@code wizard.blocked.*} message key explaining why. */
	public static final class StepState
	{
		public final boolean reachable;
		public final String blockedKey;
		/**
		 * Whether the step's own work is done, judged on the snapshot alone - never on where the user currently
		 * stands in the wizard (completed-rail lot, 2026-09-23: the product owner, on a fully configured project reopened
		 * on step 1, "on devrait voir que toutes les etapes de la configuration ont ete completees"; the rail
		 * used to tick only the steps BEFORE the current one, so a resumed wizard showed a finished project as
		 * untouched). The same evidence the summary step's zone table reads, so the two never disagree.
		 */
		public final boolean completed;

		private StepState(final boolean pReachable, final String pBlockedKey, final boolean pCompleted)
		{
			this.reachable = pReachable;
			this.blockedKey = pBlockedKey;
			this.completed = pCompleted;
		}

		static StepState reachable(final boolean pCompleted)
		{
			return new StepState(true, null, pCompleted);
		}

		/** A blocked step has nothing done by construction: its prerequisite is exactly what the previous step's completion means. */
		static StepState blocked(final String pKey)
		{
			return new StepState(false, pKey, false);
		}
	}

	/**
	 * Step-gating lot (2026-09-24, product owner: "the buttons to go to the next steps must not be shown as
	 * long as what is needed has not been done"): the ONE pure rule of what unlocks each step, used by the
	 * rail ({@link #stepStates} itself), the bottom "Next" button and every end-of-step call-to-action
	 * ({@code WizardPage}) - the three used to each judge reachability on their own, weaker, condition
	 * (Sources' own review-ticked table count for Landing, "any table loaded" for Staging, the mere
	 * existence of staging entities for Transfers/Matching), so a person could reach a step whose real
	 * prerequisite was not actually met. Now:
	 * <ul>
	 * <li>Sources: the project is saved.</li>
	 * <li>Landing: at least one application has at least one TICKED (selected) table ({@link
	 * SourceInfo#hasSelectedTable()} on at least one {@link WizardSnapshot#sources} entry) - a source added
	 * but not yet reviewed/ticked is not enough.</li>
	 * <li>Staging: EVERY ticked table, across every application, is {@code LOADED} ({@link
	 * #allTickedTablesLoaded}) - not merely "any" table loaded, the previous, looser rule.</li>
	 * <li>Transfers (the master build step): the staging is loaded - {@code STAGING_LOAD} {@link
	 * #isProjectStepDone done}, the same signal {@link ActionGates#generateMaster} already trusts (measured
	 * reliable: {@code StagingLoadRun} writes it only once the load genuinely finished) - not merely "a
	 * staging entity was proposed".</li>
	 * <li>Matching: the master is loaded - {@code MASTER_LOAD} {@link #isProjectStepDone done}, same idea.</li>
	 * <li>Summary: always reachable (a readiness view, never gated - PLAN.md: "a step never says nothing").</li>
	 * </ul>
	 * Going BACK is always allowed: this method (and every caller) only ever blocks moving FORWARD past an
	 * unmet prerequisite - a step already open, or an earlier one, is never re-blocked by it.
	 * <p>
	 * Index 0 is unused; steps are 1-based ({@link #STEP_PROJECT}..{@link #STEP_SUMMARY}) so a caller can
	 * index directly by step number.
	 */
	public static StepState[] stepStates(final WizardSnapshot pSnapshot)
	{
		final StepState[] out = new StepState[WizardRules.STEP_MAX + 1];
		final boolean hasProject = WizardRules.hasProject(pSnapshot);
		out[WizardRules.STEP_PROJECT] = StepState.reachable(hasProject);
		final boolean hasSource = hasProject && !pSnapshot.sources.isEmpty();
		out[WizardRules.STEP_SOURCES] = hasProject ? StepState.reachable(hasSource) : StepState.blocked("wizard.blocked.needProject");
		final boolean hasTickedTable = WizardRules.hasTickedLandingTable(pSnapshot);
		final boolean landingLoaded = WizardRules.anyTableLoaded(pSnapshot);
		if (!hasSource)
		{
			out[WizardRules.STEP_LANDING] = StepState.blocked("wizard.blocked.needSource");
		}
		else if (!hasTickedTable)
		{
			out[WizardRules.STEP_LANDING] = StepState.blocked("wizard.blocked.needTickedTable");
		}
		else
		{
			out[WizardRules.STEP_LANDING] = StepState.reachable(landingLoaded);
		}
		// UX lot D: a landing that lost some tables to a failed run (and loaded the others) does not stop the person from going on: the page says what the
		// staging will leave out. Any other gap (a table never run, one waiting for its key) keeps the step closed as before.
		final boolean allTickedLoaded = WizardRules.allTickedTablesLoaded(pSnapshot) || pSnapshot.landingGapIsKnown;
		final boolean stagingReady = !pSnapshot.stagingEntities.isEmpty();
		if (!hasTickedTable)
		{
			out[WizardRules.STEP_STAGING] = StepState.blocked("wizard.blocked.needLanding");
		}
		else if (!allTickedLoaded)
		{
			out[WizardRules.STEP_STAGING] = StepState.blocked("wizard.blocked.needLandingLoaded");
		}
		else
		{
			out[WizardRules.STEP_STAGING] = StepState.reachable(stagingReady);
		}
		// transfers and matching are "done" on the same PipelineStep / MatchPlan evidence the summary's zone table
		// and the pipeline-button gates already read - never on the mere existence of a button. Reachability is
		// now gated on the ZONE BEING LOADED (STAGING_LOAD / MASTER_LOAD DONE), not merely proposed/generated -
		// see this method's own Javadoc.
		final boolean stagingLoaded = WizardRules.isProjectStepDone(pSnapshot, "STAGING_LOAD");
		final boolean masterLoaded = WizardRules.isProjectStepDone(pSnapshot, "MASTER_LOAD");
		out[WizardRules.STEP_TRANSFERS] = stagingLoaded ? StepState.reachable(masterLoaded) : StepState.blocked("wizard.blocked.needStagingLoad");
		out[WizardRules.STEP_MATCHING] = masterLoaded ? StepState.reachable(WizardRules.matchPlansApplied(pSnapshot) > 0) : StepState.blocked("wizard.blocked.needMasterLoad");
		// Distribution lot 3 (2026-09-24): optional, same prerequisite as Matching (master loaded) - "done" is
		// WizardSnapshot.distributionActive (at least one target ARMED or PAUSED - see that field's own Javadoc),
		// a best-effort live read WizardData.readSnapshot adds; never gates the Summary step below.
		out[WizardRules.STEP_DISTRIBUTION] =
			masterLoaded ? StepState.reachable(pSnapshot.distributionActive) : StepState.blocked("wizard.blocked.needMasterLoad");
		// the summary always opens: an honest "what's missing" list is the point of it (PLAN.md: "a step never says
		// nothing" / the task's "the wizard can be closed and resumed at any time"); it is a view, not work, so it
		// is never "completed" itself
		out[WizardRules.STEP_SUMMARY] = StepState.reachable(false);
		WizardRules.accelStepStates(pSnapshot, out);
		return out;
	}

	/**
	 * Lot C1-B: the four steps of the CENTRALISATION path (always computed, shown only when {@link #steps} lists them). Pack needs the
	 * project; Specification needs a start: a pack (the specification itself is then optional: the pack alone is a valid start) or the blank start
	 * ("Skip the packs, start blank": the need alone, the language model proposes the whole model); Proposal needs the
	 * proposal to have been started, i.e. a difference on file or a job running; Generation needs every difference decided.
	 */
	private static void accelStepStates(final WizardSnapshot pSnapshot, final StepState[] pOut)
	{
		final boolean hasProject = WizardRules.hasProject(pSnapshot);
		final boolean hasPack = WizardRules.hasAccelStart(pSnapshot);
		final boolean hasDifferences = !pSnapshot.differences.isEmpty();
		final boolean decided = hasDifferences && WizardRules.differencesToDecide(pSnapshot) == 0;
		pOut[WizardRules.STEP_PACK] = hasProject ? StepState.reachable(hasPack) : StepState.blocked("wizard.blocked.needProject");
		pOut[WizardRules.STEP_SPECIFICATION] = hasPack ? StepState.reachable(hasDifferences) : StepState.blocked("wizard.blocked.needPack");
		pOut[WizardRules.STEP_PROPOSAL] = hasDifferences || pSnapshot.accelJob != null ? StepState.reachable(decided) : StepState.blocked("wizard.blocked.needProposal");
		final boolean generated = pSnapshot.project != null && !pSnapshot.project.domainModel.isEmpty();
		pOut[WizardRules.STEP_GENERATION] = decided ? StepState.reachable(generated) : StepState.blocked("wizard.blocked.needDecisions");
	}

	/** Lot C1-B: the CENTRALISATION path has a start: at least one pack chosen, or the blank start (no pack, the need alone). */
	public static boolean hasAccelStart(final WizardSnapshot pSnapshot)
	{
		return !pSnapshot.projectPacks.isEmpty() || pSnapshot.accelBlankStart;
	}

	/** Lot C1-B: how many differences still wait for a decision. A KEEP line is the pack left as it is: it never blocks the generation. */
	public static int differencesToDecide(final WizardSnapshot pSnapshot)
	{
		int count = 0;
		for (final WizardSnapshot.DifferenceInfo difference : pSnapshot.differences)
		{
			if ("PROPOSED".equals(difference.status) && !"KEEP".equals(difference.nature))
			{
				count++;
			}
		}
		return count;
	}

	private static boolean hasProject(final WizardSnapshot pSnapshot)
	{
		return pSnapshot.project != null && !pSnapshot.project.code.isEmpty();
	}

	private static boolean anyTableLoaded(final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				if ("LOADED".equals(table.status))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Step-gating lot: at least one application has at least one selected ("ticked") table - see {@link #stepStates}. */
	private static boolean hasTickedLandingTable(final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			if (source.hasSelectedTable())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Step-gating lot: EVERY selected ("ticked") table of EVERY application is {@code LOADED} - the Staging
	 * step's real prerequisite (see {@link #stepStates}), stricter than {@link #anyTableLoaded}. False when
	 * there is no ticked table at all (nothing to be "all loaded" about yet - the caller only reaches this
	 * once {@link #hasTickedLandingTable} is already true, but the method stays correct standalone too).
	 * <p>
	 * Distribution round 4 (2026-09-26, task brief item 2): the actual rule now lives in
	 * {@code landing.LandingReadiness#allTickedTablesLoaded}, a pure, dependency-free class -
	 * {@code ebx.DistributionRunner}'s own "landing loaded from every source" arming-checklist fact shares
	 * it too, rather than re-deriving a second, looser notion from {@code ProjectSource.status} (the bug that
	 * left DistribTest's checklist item unticked although its one landing table was genuinely loaded).
	 */
	static boolean allTickedTablesLoaded(final WizardSnapshot pSnapshot)
	{
		final List<LandingReadiness.Table> tables = new ArrayList<LandingReadiness.Table>();
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				tables.add(new LandingReadiness.Table(table.selected, "LOADED".equals(table.status)));
			}
		}
		return LandingReadiness.allTickedTablesLoaded(tables);
	}

	/** Fix lot (2026-09-26): whether ANY application of the project has at least one landing table planned yet -
	 * ticked or not, loaded or not. Used only to gate {@link #suggestedStep}'s own Landing check (see its own
	 * comment): a project whose only applications so far have zero tables (a deduced scan still running, or one
	 * that skipped its official scan) has no landing work to suggest yet. */
	static boolean hasAnyLandingTable(final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			if (!source.tables.isEmpty())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The step a resumed wizard should open on: the first one that still has work to do, in step order.
	 * <p>
	 * <b>Bug fixed live 2026-09-24 (project Vague1, landing not fully loaded):</b> this used to check
	 * {@link #anyTableLoaded} - true as soon as a SINGLE ticked table was loaded - so a project with some
	 * tables loaded and others still not (exactly the "reopen mid-landing" case) skipped past Landing
	 * straight to whatever came next, landing on Summary when staging happened to be non-empty too. Reuses
	 * the SAME stricter prerequisite {@link #stepStates} already gates the Staging step's reachability on
	 * ({@link #allTickedTablesLoaded}, step-gating lot, commit 5efdb86) rather than a second, looser notion
	 * of "landing done" - the rail and the resume point must agree on what "done" means.
	 * <p>
	 * <b>Live bug fix (2026-09-25, DistribTest project, task brief item 5):</b> once staging entities existed
	 * this used to return {@link #STEP_SUMMARY} outright, whatever state the master/matching steps were
	 * actually in - reopening a project whose master was not yet built landed on Summary, whose own banner
	 * read "operational" with the master "Loaded - 0 records" (a stale/empty read, the sibling of item 6's own
	 * live incident). Resume on the first step that still has real work, walking the SAME {@code
	 * PipelineStep}/{@code MatchPlan} evidence {@link #stepStates} already gates Transfers/Matching's own
	 * reachability on ({@link #isProjectStepDone}/{@link #matchPlansApplied}) - never a second, looser notion.
	 * {@link #STEP_DISTRIBUTION} is deliberately never a resume stop: it is optional and never blocks (its own
	 * {@code stepStates} entry only tracks whether a target is armed, which is not "unfinished work" the same
	 * way an unloaded staging/master zone is).
	 */
	public static int suggestedStep(final WizardSnapshot pSnapshot)
	{
		if (!WizardRules.hasProject(pSnapshot))
		{
			return WizardRules.STEP_PROJECT;
		}
		if (WizardRules.CENTRALISATION.equals(pSnapshot.project.style))
		{
			return WizardRules.suggestedAccelStep(pSnapshot);
		}
		if (pSnapshot.sources.isEmpty())
		{
			return WizardRules.STEP_SOURCES;
		}
		// fix lot (2026-09-26): a brand new application with NO landing table at all yet (a deduced scan still
		// running, or one that has not been planned yet) used to fall straight into "!allTickedTablesLoaded" -
		// vacuously true whenever there is nothing ticked - and jump to Landing before there is anything there to
		// work on. Only suggest Landing once at least one table is actually known; otherwise there is still
		// sources-step work to do (wait for the scan, confirm a deduced table, tick a table to load).
		// UX lot D: a gap that a failed run explains no longer pulls the person back to the landing once they have gone on and proposed the staging
		// (before that, they land on step 3 where the missing tables are listed)
		if (WizardRules.hasAnyLandingTable(pSnapshot) && !WizardRules.allTickedTablesLoaded(pSnapshot) && !(pSnapshot.landingGapIsKnown && !pSnapshot.stagingEntities.isEmpty()))
		{
			return WizardRules.STEP_LANDING;
		}
		if (pSnapshot.stagingEntities.isEmpty())
		{
			return WizardRules.STEP_STAGING;
		}
		if (!WizardRules.isProjectStepDone(pSnapshot, "STAGING_LOAD"))
		{
			return WizardRules.STEP_STAGING;
		}
		if (!WizardRules.isProjectStepDone(pSnapshot, "MASTER_LOAD"))
		{
			return WizardRules.STEP_TRANSFERS;
		}
		if (WizardRules.matchPlansApplied(pSnapshot) == 0)
		{
			return WizardRules.STEP_MATCHING;
		}
		return WizardRules.STEP_SUMMARY;
	}

	/** Lot C1-B: the first step of the CENTRALISATION path that still has work to do. */
	private static int suggestedAccelStep(final WizardSnapshot pSnapshot)
	{
		if (!WizardRules.hasAccelStart(pSnapshot))
		{
			return WizardRules.STEP_PACK;
		}
		if (pSnapshot.differences.isEmpty())
		{
			return pSnapshot.accelJob != null ? WizardRules.STEP_PROPOSAL : WizardRules.STEP_SPECIFICATION;
		}
		if (WizardRules.differencesToDecide(pSnapshot) > 0)
		{
			return WizardRules.STEP_PROPOSAL;
		}
		return pSnapshot.project.domainModel.isEmpty() ? WizardRules.STEP_GENERATION : WizardRules.STEP_SUMMARY;
	}

	// ------------------------------------------------------------------------------------------------ implementation style

	/** Where the master data is authored - the diagram's pencil mark, and the row labelled "Authoring" on every card. */
	public enum Authoring
	{
		SOURCES, HUB, BOTH
	}

	/** Whether the hub stores golden attributes at all, and if so whether the business may edit them there - the diagram's filled/outline hub box, and the row labelled "Golden record". */
	public enum GoldenRecord
	{
		/** Registry: no attributes in the hub, only keys/cross-references - drawn as an outline hub with a small keys mark. */
		NONE,
		/** Consolidation: the hub is authoritative but read-only for the business (matched and merged from the sources). */
		READ_ONLY,
		/** Coexistence/Centralisation: stewards (or, for Centralisation, the only authors) may edit the golden record directly in the hub. */
		EDITABLE
	}

	/** The direction(s) of the arrow(s) between the source applications and the hub - the row labelled "Flows". Hub -&gt; consumers is drawn on every diagram regardless, so it is not part of this enum. */
	public enum Flow
	{
		IN_ONLY, BOTH, OUT_ONLY
	}

	/**
	 * The four MDM implementation styles shown as cards on step 1, redesigned 2026-09-21 around one axis -
	 * "where is the master data created and maintained" - from {@code REGISTRY} (sources only) to
	 * {@code CENTRALISATION} (hub only), {@link #IMPLEMENTATION_STYLES} listed in that axis order. Only
	 * {@code CONSOLIDATION} is selectable, matching what the factory actually builds (sources
	 * feed a golden record in the hub, downstream systems read it, nothing is written back); the other three
	 * are informational only, disabled with one "when to choose it" sentence each. {@code CENTRALISATION} (built by lot C1-B:
	 * pack, specification, proposal, generation) is shown beside Consolidation as a {@link ImplementationStyle#comingSoon} card
	 * since 2026-11-10 (product owner: "not ready, coming soon for now"): the code stays, a new project cannot choose it, an
	 * existing CENTRALISATION project keeps its own path ({@link #steps} reads the code, never the flag). {@code Project.style} now
	 * carries this choice ({@code FactoryPaths.Project.STYLE}, added by another agent in this lot); a value
	 * other than {@code CONSOLIDATION} is refused server-side exactly like {@code TARGET_EXISTS} is for
	 * {@link ProjectDraft#mode} ({@link #projectProblems}), and {@code WizardData#saveProject} still hard-
	 * writes {@code CONSOLIDATION} regardless, the same defence in depth as the mode field.
	 */
	public static final class ImplementationStyle
	{
		public final String code;
		public final String labelKey;
		/** The card's headline fact, in plain words ("Data created in: ...") - product owner, 2026-09-21: "that is the whole point of the choice". */
		public final String headlineKey;
		/** "When to choose it" - shown on every card, selectable or not. */
		public final String whenKey;
		/** The sentence tying this style to what the wizard will actually build, shown below the cards for whichever style is selected. */
		public final String consequenceKey;
		public final boolean selectable;
		/**
		 * 2026-11-10: a style the factory already builds but that is not ready for users: its card stays in the main row (not in the
		 * "other styles, coming later" fold), disabled, with a "Coming soon" chip; {@link #selectable} is false, so step 1 refuses it
		 * and a saved project never carries it. Projects created while it was selectable keep working (every path reads the code).
		 */
		public final boolean comingSoon;
		public final Authoring authoring;
		public final GoldenRecord goldenRecord;
		public final Flow flow;

		private ImplementationStyle(final String pCode, final String pLabelKey, final String pHeadlineKey, final String pWhenKey, final String pConsequenceKey,
			final boolean pSelectable, final Authoring pAuthoring, final GoldenRecord pGoldenRecord, final Flow pFlow)
		{
			this(pCode, pLabelKey, pHeadlineKey, pWhenKey, pConsequenceKey, pSelectable, false, pAuthoring, pGoldenRecord, pFlow);
		}

		private ImplementationStyle(final String pCode, final String pLabelKey, final String pHeadlineKey, final String pWhenKey, final String pConsequenceKey,
			final boolean pSelectable, final boolean pComingSoon, final Authoring pAuthoring, final GoldenRecord pGoldenRecord, final Flow pFlow)
		{
			this.code = pCode;
			this.labelKey = pLabelKey;
			this.headlineKey = pHeadlineKey;
			this.whenKey = pWhenKey;
			this.consequenceKey = pConsequenceKey;
			this.selectable = pSelectable;
			this.comingSoon = pComingSoon;
			this.authoring = pAuthoring;
			this.goldenRecord = pGoldenRecord;
			this.flow = pFlow;
		}
	}

	/** Axis order: sources-only (Registry) .. hub-only (Centralisation) - see the class Javadoc. */
	public static final List<ImplementationStyle> IMPLEMENTATION_STYLES;
	static
	{
		final List<ImplementationStyle> styles = new ArrayList<ImplementationStyle>();
		styles.add(new ImplementationStyle("REGISTRY", "wizard.style.registry.label", "wizard.style.registry.headline", "wizard.style.registry.when",
			"wizard.style.registry.consequence", false, Authoring.SOURCES, GoldenRecord.NONE, Flow.IN_ONLY));
		styles.add(new ImplementationStyle("CONSOLIDATION", "wizard.style.consolidation.label", "wizard.style.consolidation.headline", "wizard.style.consolidation.when",
			"wizard.style.consolidation.consequence", true, Authoring.SOURCES, GoldenRecord.READ_ONLY, Flow.IN_ONLY));
		styles.add(new ImplementationStyle("COEXISTENCE", "wizard.style.coexistence.label", "wizard.style.coexistence.headline", "wizard.style.coexistence.when",
			"wizard.style.coexistence.consequence", false, Authoring.BOTH, GoldenRecord.EDITABLE, Flow.BOTH));
		styles.add(new ImplementationStyle("CENTRALISATION", "wizard.style.centralisation.label", "wizard.style.centralisation.headline", "wizard.style.centralisation.when",
			"wizard.style.centralisation.consequence", false, true, Authoring.HUB, GoldenRecord.EDITABLE, Flow.OUT_ONLY));
		IMPLEMENTATION_STYLES = java.util.Collections.unmodifiableList(styles);
	}

	/** The style named by {@code pCode}, or {@link #defaultStyle()} when {@code pCode} matches none - a snapshot read from a forged or stale row must still render something. */
	public static ImplementationStyle styleByCode(final String pCode)
	{
		for (final ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			if (style.code.equals(pCode))
			{
				return style;
			}
		}
		return WizardRules.defaultStyle();
	}

	/**
	 * {@code CONSOLIDATION}: the only selectable style today, pre-selected for a new project's draft
	 * ({@code WizardRules.ProjectDraft#style} already defaults to it) and the {@code Project.style} XSD
	 * default - made explicit here, product owner request 2026-09-21, so a new project's default and the
	 * "usual case" chip on its card ({@code wizard.style.mostCommon}) both read off the one place that
	 * decides it, rather than each hard-coding the string {@code "CONSOLIDATION"} independently.
	 */
	public static ImplementationStyle defaultStyle()
	{
		return WizardRules.IMPLEMENTATION_STYLES.get(1);
	}

	// ------------------------------------------------------------------------------------------------- source code

	/**
	 * {@code "ERP_SOURCE"} gives {@code "ERP"}: the last dot-separated segment, a trailing {@code _SOURCE}/
	 * {@code _SRC}/{@code _DB}/{@code _SCHEMA} stripped once, upper-cased, sanitised to
	 * {@link #CODE_PATTERN}. Never returns an empty string (falls back to {@code "SRC"}), so it is always
	 * safe to pre-fill the step 2 field with.
	 */
	public static String suggestSourceSystemCode(final String pSchemaName)
	{
		if (pSchemaName == null)
		{
			return "";
		}
		String name = pSchemaName.trim();
		final int dot = name.lastIndexOf('.');
		if (dot >= 0 && dot + 1 < name.length())
		{
			name = name.substring(dot + 1);
		}
		String upper = name.toUpperCase(Locale.ENGLISH);
		for (final String suffix : new String[] { "_SOURCE", "_SCHEMA", "_SRC", "_DB" })
		{
			if (upper.endsWith(suffix) && upper.length() > suffix.length())
			{
				upper = upper.substring(0, upper.length() - suffix.length());
				break;
			}
		}
		upper = upper.replaceAll("[^A-Z0-9_]+", "_").replaceAll("^_+|_+$", "");
		if (upper.isEmpty())
		{
			return "SRC";
		}
		if (!Character.isLetter(upper.charAt(0)))
		{
			upper = "S_" + upper;
		}
		return upper.length() > WizardRules.CODE_MAX ? upper.substring(0, WizardRules.CODE_MAX).replaceAll("_+$", "") : upper;
	}

	/**
	 * Schema names that name a database default/technical schema, not an application - {@link
	 * #suggestSourceSystemCode} on one of these gives the same code (e.g. {@code PUBLIC}) for every source
	 * pointed at that kind of database, so {@link #proposeApplicationCode} never trusts it as-is.
	 */
	private static final Set<String> GENERIC_SCHEMA_CODES = new java.util.HashSet<String>(
		java.util.Arrays.asList("PUBLIC", "DBO", "DEFAULT", "MAIN", "SYS"));

	/**
	 * {@code "PersVolCRM"} gives {@code "PERSVOLCRM"}: upper-cased, sanitised to {@link #CODE_PATTERN}. Same
	 * shape as {@link #suggestSourceSystemCode} but for a Connectors connection id rather than a schema name -
	 * no dot-segment or {@code _SOURCE}-style suffix stripping, since a connection id carries none of that.
	 * Never returns an empty string for a non-blank input with at least one letter/digit (falls back to
	 * {@code "SRC"} otherwise), so it is always safe to use as a fallback base code.
	 */
	public static String codeFromConnectionId(final String pConnectionId)
	{
		if (pConnectionId == null)
		{
			return "";
		}
		String upper = pConnectionId.trim().toUpperCase(Locale.ENGLISH).replaceAll("[^A-Z0-9_]+", "");
		if (upper.isEmpty())
		{
			return "";
		}
		if (!Character.isLetter(upper.charAt(0)))
		{
			upper = "S_" + upper;
		}
		return upper.length() > WizardRules.CODE_MAX ? upper.substring(0, WizardRules.CODE_MAX).replaceAll("_+$", "") : upper;
	}

	/**
	 * The step 2 "add an application" form's proposed {@code sourceSystem} code, bug 6 follow-up (project
	 * PersVol, volume test, 2026-09-24): {@link #suggestSourceSystemCode} on a schema name proposes the SAME
	 * code for every source of a database that exposes only one, generically-named schema (three H2 sources
	 * each with one schema {@code PUBLIC} all proposed {@code "PUBLIC"}) - two sources of one project can
	 * never share a code ({@link #sourceProblems}), so that proposal was always going to be refused or, worse,
	 * silently reused if unchecked.
	 * <p>
	 * Falls back to a code derived from the Connectors connection id ({@link #codeFromConnectionId}) instead
	 * of the schema name when the schema-derived code is one of {@link #GENERIC_SCHEMA_CODES} (case-insensitive)
	 * OR is already used by another source of this project; the result is then de-duplicated against {@code
	 * pExistingCodes} the same way {@code SourcesRun#uniqueCode} does for the guided-onboarding path ({@code
	 * "_2"}, {@code "_3"}, ...) so the two proposal paths never disagree on the convention.
	 *
	 * @param pExistingCodes every {@code sourceSystem} code already used by another source of this project -
	 *        never mutated.
	 */
	public static String proposeApplicationCode(final String pSchemaName, final String pConnectionId, final Set<String> pExistingCodes)
	{
		final String schemaCode = WizardRules.suggestSourceSystemCode(pSchemaName);
		final boolean generic = WizardRules.GENERIC_SCHEMA_CODES.contains(schemaCode.toUpperCase(Locale.ENGLISH));
		String base = schemaCode;
		if (generic || pExistingCodes.contains(schemaCode))
		{
			final String connectionCode = WizardRules.codeFromConnectionId(pConnectionId);
			if (!connectionCode.isEmpty())
			{
				base = connectionCode;
			}
		}
		return WizardRules.uniqueCode(base, pExistingCodes);
	}

	/**
	 * {@code pBaseCode} if it is not already in {@code pExistingCodes}, else {@code pBaseCode + "_2"}, {@code
	 * "_3"}, ... - same convention and same stopping point (999) as {@code SourcesRun#uniqueCode}, which this
	 * package cannot call (package-private, and {@code ebx.*} is EBX-bound while this class stays EBX-free);
	 * kept as a small private duplicate rather than widening that method's visibility across packages for one
	 * caller.
	 */
	private static String uniqueCode(final String pBaseCode, final Set<String> pExistingCodes)
	{
		String base = pBaseCode == null || pBaseCode.trim().isEmpty() ? "SRC" : pBaseCode.trim();
		if (base.length() > WizardRules.CODE_MAX)
		{
			base = base.substring(0, WizardRules.CODE_MAX);
		}
		if (!pExistingCodes.contains(base))
		{
			return base;
		}
		for (int suffixNum = 2; suffixNum < 1000; suffixNum++)
		{
			final String suffix = "_" + suffixNum;
			final int keep = Math.max(1, WizardRules.CODE_MAX - suffix.length());
			final String candidate = (base.length() > keep ? base.substring(0, keep) : base) + suffix;
			if (!pExistingCodes.contains(candidate))
			{
				return candidate;
			}
		}
		return base + "_999";
	}

	// ------------------------------------------------------------------------------------------------- trust rank

	/**
	 * The {@code trustRank} to give a NEW source of a project so the "when two applications disagree" order
	 * (step 2, lower {@code trustRank} wins - {@link SourceDraft#trustRank}'s own Javadoc, confirmed by {@code
	 * TrustedSourceRanking}) shows it after every source already in the project, instead of tied with them at
	 * the shared default 100 ({@code ProjectSource.trustRank}'s own XSD default). Volume-test bug (project
	 * PersVol, 2026-09-24): three sources added one after another all kept {@code trustRank = 100} because
	 * nothing ever proposed a different value unless a person clicked the reorder arrows first, so "when two
	 * applications disagree" showed an order that was never actually stored.
	 * <p>
	 * Same scale/convention the reorder arrows use ({@code WizardData#reorderSourceTrust}: current {@code
	 * trustRank} ascending, renumbered 1..n on every move) - one more than the highest {@code trustRank} among
	 * {@code pExistingTrustRanks}, or the field's own default (100) when the project has no source yet, so a
	 * project's first source keeps today's familiar default and every one after it gets a strictly higher
	 * (less trusted) rank than every source added before it.
	 *
	 * @param pExistingTrustRanks every {@code trustRank} already stored by another source of this project -
	 *        never mutated, empty for a project's first source.
	 */
	public static int nextTrustRank(final java.util.Collection<Integer> pExistingTrustRanks)
	{
		Integer max = null;
		for (final Integer rank : pExistingTrustRanks)
		{
			if (rank != null && (max == null || rank.intValue() > max.intValue()))
			{
				max = rank;
			}
		}
		return max == null ? 100 : max.intValue() + 1;
	}

	// ------------------------------------------------------------------------------------------------- validation

	/** One refusal: the step to reopen, the field to mark, the {@code wizard.error.*} message key. */
	public static final class Problem
	{
		public final int step;
		public final String field;
		public final String key;

		public Problem(final int pStep, final String pField, final String pKey)
		{
			this.step = pStep;
			this.field = pField;
			this.key = pKey;
		}
	}

	/** What step 1 posts. */
	public static final class ProjectDraft
	{
		public String code = "";
		public String label = "";
		public String description = "";
		public String mode = "GREENFIELD";
		public String style = "CONSOLIDATION";
		/**
		 * wizard-llm-value-sharing lot (2026-09-28): {@code Project.llmValueSharing} as posted by step 1's
		 * control - empty when nothing was posted yet (the saved value, or {@link #LLM_LEVEL_DEFAULT} for a
		 * new project, then applies), never a value {@link #LLM_LEVELS} does not list once validated.
		 */
		public String llmValueSharing = "";
		/** {@code Project.llmSampleSize} as posted (raw text: validated by {@link #projectProblems}); empty when nothing was posted yet. */
		public String llmSampleSize = "";
	}

	/** wizard-llm-value-sharing lot (2026-09-28): the three {@code ebx.LlmDataPolicy.Level}s, least to most sharing - the order IS the ranking. */
	public static final String[] LLM_LEVELS = { "NONE", "SHAPES_ONLY", "SAMPLE_VALUES" };
	public static final String LLM_LEVEL_DEFAULT = "SHAPES_ONLY";
	public static final int LLM_SAMPLE_SIZE_DEFAULT = 20;
	/** Same bound as {@code ebx.QualityLlmSuggester.MAX_SAMPLE_SIZE}: the most distinct real values per column that may ever reach the model. */
	public static final int LLM_SAMPLE_SIZE_MAX = 50;

	/** Rank of a level in {@link #LLM_LEVELS} ({@code 0} = NONE); an empty value ranks as {@link #LLM_LEVEL_DEFAULT}, an unknown one as NONE (fails closed, like {@code ebx.LlmDataPolicy}). */
	public static int llmLevelRank(final String pLevel)
	{
		final String level = pLevel == null || pLevel.trim().isEmpty() ? WizardRules.LLM_LEVEL_DEFAULT : pLevel.trim();
		for (int i = 0; i < WizardRules.LLM_LEVELS.length; i++)
		{
			if (WizardRules.LLM_LEVELS[i].equals(level))
			{
				return i;
			}
		}
		return 0;
	}

	/** Whether {@code pLevel} may be chosen under the instance cap {@code pMax} (same rule as {@code ebx.LlmDataPolicy#levelFor}: the more restrictive wins). */
	public static boolean llmLevelAllowed(final String pLevel, final String pMax)
	{
		return WizardRules.llmLevelRank(pLevel) <= WizardRules.llmLevelRank(pMax);
	}

	/** The level that actually applies for a project asking {@code pChoice} under the cap {@code pMax}: the more restrictive of the two, as a {@link #LLM_LEVELS} name. */
	public static String effectiveLlmLevel(final String pChoice, final String pMax)
	{
		return WizardRules.LLM_LEVELS[Math.min(WizardRules.llmLevelRank(pChoice), WizardRules.llmLevelRank(pMax))];
	}

	/** {@code pRaw} as a sample size: empty is the default, anything else must be a whole number from 1 to {@link #LLM_SAMPLE_SIZE_MAX}; {@code -1} when it is not. */
	public static int llmSampleSize(final String pRaw)
	{
		if (pRaw == null || pRaw.trim().isEmpty())
		{
			return WizardRules.LLM_SAMPLE_SIZE_DEFAULT;
		}
		try
		{
			final int size = Integer.parseInt(pRaw.trim());
			return size >= 1 && size <= WizardRules.LLM_SAMPLE_SIZE_MAX ? size : -1;
		}
		catch (final NumberFormatException ex)
		{
			return -1;
		}
	}

	/**
	 * @param pExistingCodes every {@code Project.code} already on file, for the uniqueness check; when
	 *        {@code pOwnCode} is one of them (editing the project being resumed) it is not counted against
	 *        itself.
	 */
	public static List<Problem> projectProblems(final ProjectDraft pDraft, final Set<String> pExistingCodes, final String pOwnCode)
	{
		return WizardRules.projectProblems(pDraft, pExistingCodes, pOwnCode, WizardRules.LLM_LEVELS[WizardRules.LLM_LEVELS.length - 1]);
	}

	/**
	 * wizard-llm-value-sharing lot (2026-09-28): the same checks plus the language-model fields - a level
	 * outside {@link #LLM_LEVELS}, a level above the instance cap {@code pLlmValueSharingMax} (a choice the
	 * instance would silently lower is REFUSED, never accepted as asked - the control disables it too, but a
	 * forged post must land on the same rule), and a sample size outside 1..{@link #LLM_SAMPLE_SIZE_MAX}
	 * (checked only when it matters, i.e. SAMPLE_VALUES is chosen).
	 */
	public static List<Problem> projectProblems(final ProjectDraft pDraft, final Set<String> pExistingCodes, final String pOwnCode, final String pLlmValueSharingMax)
	{
		final List<Problem> out = new ArrayList<Problem>();
		final String level = pDraft.llmValueSharing == null || pDraft.llmValueSharing.trim().isEmpty() ? WizardRules.LLM_LEVEL_DEFAULT : pDraft.llmValueSharing.trim();
		if (!Arrays.asList(WizardRules.LLM_LEVELS).contains(level))
		{
			out.add(new Problem(WizardRules.STEP_PROJECT, "llmValueSharing", "wizard.error.llmSharingInvalid"));
		}
		else if (!WizardRules.llmLevelAllowed(level, pLlmValueSharingMax))
		{
			out.add(new Problem(WizardRules.STEP_PROJECT, "llmValueSharing", "wizard.error.llmSharingCapped"));
		}
		else if ("SAMPLE_VALUES".equals(level) && WizardRules.llmSampleSize(pDraft.llmSampleSize) < 0)
		{
			out.add(new Problem(WizardRules.STEP_PROJECT, "llmSampleSize", "wizard.error.llmSampleSizeInvalid"));
		}
		if (pDraft.code.isEmpty())
		{
			out.add(new Problem(WizardRules.STEP_PROJECT, "code", "wizard.error.codeRequired"));
		}
		else if (!pDraft.code.matches(WizardRules.CODE_PATTERN) || pDraft.code.length() > WizardRules.CODE_MAX)
		{
			out.add(new Problem(WizardRules.STEP_PROJECT, "code", "wizard.error.codeInvalid"));
		}
		else if (pExistingCodes.contains(pDraft.code) && !pDraft.code.equals(pOwnCode))
		{
			out.add(new Problem(WizardRules.STEP_PROJECT, "code", "wizard.error.codeTaken"));
		}
		if (pDraft.label.isEmpty())
		{
			out.add(new Problem(WizardRules.STEP_PROJECT, "label", "wizard.error.labelRequired"));
		}
		else if (pDraft.label.length() > WizardRules.LABEL_MAX)
		{
			out.add(new Problem(WizardRules.STEP_PROJECT, "label", "wizard.error.labelLong"));
		}
		if (pDraft.description.length() > WizardRules.DESCRIPTION_MAX)
		{
			out.add(new Problem(WizardRules.STEP_PROJECT, "description", "wizard.error.descriptionLong"));
		}
		if (!"GREENFIELD".equals(pDraft.mode) && !"TARGET_EXISTS".equals(pDraft.mode))
		{
			out.add(new Problem(WizardRules.STEP_PROJECT, "mode", "wizard.error.mode"));
		}
		else if ("TARGET_EXISTS".equals(pDraft.mode))
		{
			// disabled with "a venir" in the UI (this task's scope); a forged request is still refused server-side
			out.add(new Problem(WizardRules.STEP_PROJECT, "mode", "wizard.error.modeNotYet"));
		}
		if (WizardRules.styleCodeUnknown(pDraft.style))
		{
			out.add(new Problem(WizardRules.STEP_PROJECT, "style", "wizard.error.style"));
		}
		else if (!WizardRules.styleMayBeSaved(null, pDraft.style))
		{
			// only Consolidation is selectable in the UI (every other card's radio is disabled, so it never posts; Centralisation is
			// "coming soon" since 2026-11-10); refused here too, the same defence in depth as TARGET_EXISTS above
			out.add(new Problem(WizardRules.STEP_PROJECT, "style", "wizard.error.styleNotYet"));
		}
		return out;
	}

	/** Whether {@code pStyle} names a style whose card can be chosen ({@link ImplementationStyle#selectable}); false for an unknown code. */
	public static boolean styleSelectable(final String pStyle)
	{
		for (final ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			if (style.code.equals(pStyle))
			{
				return style.selectable;
			}
		}
		return false;
	}

	/**
	 * Production review of 2026-11-12 (F3-13): the one decision of "may a project carry this style". The style is written once, at creation, and never changes
	 * (the wizard shows it fixed, {@code saveProject} does not touch it on an update): a project that ALREADY has {@code pCurrentStyle} keeps it whatever that is -
	 * an existing CENTRALISATION project keeps its whole path - while a style that does not exist yet ({@code pCurrentStyle} null: a project being created) must be
	 * {@link #styleSelectable selectable}, and a project never switches to another style, selectable or not. The data model's access rule closes the same door
	 * for Data Services and the EBX screens (the field is read only on a saved project).
	 */
	public static boolean styleMayBeSaved(final String pCurrentStyle, final String pNewStyle)
	{
		if (pCurrentStyle != null && !pCurrentStyle.trim().isEmpty())
		{
			return pCurrentStyle.trim().equals(pNewStyle == null ? null : pNewStyle.trim());
		}
		return WizardRules.styleSelectable(pNewStyle);
	}

	private static boolean styleCodeUnknown(final String pStyle)
	{
		for (final ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			if (style.code.equals(pStyle))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * What step 2's "Ajouter une application" mini-form posts. One project can (and the demo does) hold
	 * several {@link SourceDraft}s sharing the very same {@link #catalogSourceId} - one scanned platform
	 * often hosts more than one application (PLAN.md: {@code ERP_SOURCE}/{@code CRM_SOURCE} in the same
	 * Snowflake account) - distinguished by {@link #schemas} and required, by {@link #sourceProblems}, to
	 * carry distinct {@link #sourceSystem} codes.
	 */
	public static final class SourceDraft
	{
		public String catalogSourceId = "";
		public String sourceSystem = "";
		public String connector = "";
		/** Lower wins; feeds the most-trusted-source order of the eventual merge policy ({@code ProjectSource.trustRank}, default 100). */
		public int trustRank = 100;
		public final List<String> schemas = new ArrayList<String>();
	}

	/**
	 * @param pExistingSourceSystemCodes every {@code sourceSystem} code already used by another source of
	 *        THIS project (never across projects: two different projects may each have an {@code ERP}) - a
	 *        source system code is unique per project, not globally, so the same catalog platform can be
	 *        added to two different projects, or twice to the same one under two different codes.
	 */
	public static List<Problem> sourceProblems(final SourceDraft pDraft, final Set<String> pExistingSourceSystemCodes)
	{
		final List<Problem> out = new ArrayList<Problem>();
		if (pDraft.catalogSourceId.isEmpty())
		{
			out.add(new Problem(WizardRules.STEP_SOURCES, "catalogSourceId", "wizard.error.catalogSourceRequired"));
		}
		if (pDraft.sourceSystem.isEmpty())
		{
			out.add(new Problem(WizardRules.STEP_SOURCES, "sourceSystem", "wizard.error.sourceSystemRequired"));
		}
		else if (!pDraft.sourceSystem.matches(WizardRules.CODE_PATTERN) || pDraft.sourceSystem.length() > WizardRules.CODE_MAX)
		{
			out.add(new Problem(WizardRules.STEP_SOURCES, "sourceSystem", "wizard.error.sourceSystemInvalid"));
		}
		// two sources of the SAME project can never share a system code (product owner, 2026-09-20) - the same
		// catalog source used twice (ERP_SOURCE/CRM_SOURCE demo) is fine, only the code has to differ
		else if (pExistingSourceSystemCodes.contains(pDraft.sourceSystem))
		{
			out.add(new Problem(WizardRules.STEP_SOURCES, "sourceSystem", "wizard.error.sourceSystemTaken"));
		}
		return out;
	}

	// -------------------------------------------------------------------------------------------------- action gates

	/** Whether one action button is enabled, and if not, the {@code wizard.gate.*} key explaining why (shown as the button's own hint, per the task: "non-administrators see the state and a sentence explaining why"). */
	public static final class Gate
	{
		public final boolean enabled;
		public final String reasonKey;
		/**
		 * Target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3, "the gate message names what is missing"): plain,
		 * already-formatted text naming exactly what is missing, appended AFTER {@link #reasonKey}'s own generic
		 * sentence - never a replacement for it. Null for every other gate (an unparameterized reason is enough
		 * for them); the same "computed diagnostic detail is plain English, static chrome is localized" split
		 * {@code ProposalWording}'s rationale-appended text already follows elsewhere in this module.
		 */
		public final String detail;

		private Gate(final boolean pEnabled, final String pReasonKey, final String pDetail)
		{
			this.enabled = pEnabled;
			this.reasonKey = pReasonKey;
			this.detail = pDetail;
		}

		static Gate enabled()
		{
			return new Gate(true, null, null);
		}

		static Gate disabled(final String pKey)
		{
			return new Gate(false, pKey, null);
		}

		/** See {@link #detail}. */
		static Gate disabled(final String pKey, final String pDetail)
		{
			return new Gate(false, pKey, pDetail);
		}
	}

	/** Every action gate the wizard's buttons need, computed once per rendering from the snapshot and the session's administrator flag. */
	public static final class ActionGates
	{
		public Gate planLanding;
		public Gate generateLanding;
		public Gate suggestStaging;
		public Gate suggestStagingLlm;
		// pipeline-buttons lot (2026-09-23): every route from quality suggestion onward, given its own button
		public Gate suggestQuality;
		public Gate suggestQualityLlm;
		public Gate suggestCapabilitiesLlm;
		public Gate reopenMappings;
		public Gate generateStaging;
		public Gate loadStaging;
		public Gate generateMaster;
		public Gate loadMaster;
		public Gate suggestMatching;
		public Gate suggestMatchingLlm;
		public Gate configureMatching;
		public Gate runMatching;
		// lot C1-B: the CENTRALISATION path
		public Gate accelSuggestPacks;
		public Gate accelRunProposal;
		public Gate accelGenerate;
	}

	/**
	 * Landing-walkthrough lot (2026-10-16): the project-wide gates - every gate that is not about one application (all of them but {@link ActionGates#planLanding}).
	 * Before this lot every caller passed {@code snapshot.sources.get(0)}, and "Prepare the landing" answered "pick the tables first" whenever the FIRST
	 * application had no ticked table, however many the others had (live on project Zefz: Salesforce first with 0 of 17 ticked blocked the run although
	 * Snowflake and the SAP file had ticked tables).
	 */
	public static ActionGates actionGates(final WizardSnapshot pSnapshot, final boolean pAdministrator)
	{
		return WizardRules.actionGates(pSnapshot, pAdministrator, null);
	}

	/**
	 * @param pSource the application a per-application button is about ({@link ActionGates#planLanding} only); null for the project as a whole. {@link
	 *            ActionGates#generateLanding} is project-wide since the landing-walkthrough lot (2026-10-16): enabled as soon as ANY application of the
	 *            project (or {@code pSource}, which tests may pass without adding it to the snapshot) has a ticked table.
	 */
	public static ActionGates actionGates(final WizardSnapshot pSnapshot, final boolean pAdministrator, final WizardSnapshot.SourceInfo pSource)
	{
		final ActionGates out = new ActionGates();
		final boolean anySource = pSource != null || !pSnapshot.sources.isEmpty();
		out.planLanding = anySource ? Gate.enabled() : Gate.disabled("wizard.gate.noSource");
		if (!anySource)
		{
			out.generateLanding = Gate.disabled("wizard.gate.noSource");
		}
		else if (!pAdministrator)
		{
			out.generateLanding = Gate.disabled("wizard.gate.adminOnly");
		}
		else if (!WizardRules.hasTickedLandingTable(pSnapshot) && (pSource == null || !pSource.hasSelectedTable()))
		{
			out.generateLanding = Gate.disabled("wizard.gate.needPlan");
		}
		else
		{
			out.generateLanding = Gate.enabled();
		}
		if (pSnapshot.sources.isEmpty())
		{
			out.suggestStaging = Gate.disabled("wizard.gate.noSource");
		}
		else if (pSnapshot.project != null && "TARGET_EXISTS".equals(pSnapshot.project.mode))
		{
			out.suggestStaging = Gate.disabled("wizard.gate.targetExistsNotYet");
		}
		else if (!WizardRules.anyTableLoaded(pSnapshot) && !WizardRules.anyTableGenerated(pSnapshot))
		{
			out.suggestStaging = Gate.disabled("wizard.gate.needLanding");
		}
		else
		{
			out.suggestStaging = Gate.enabled();
		}
		if (!pAdministrator)
		{
			out.suggestStagingLlm = Gate.disabled("wizard.gate.adminOnly");
		}
		else if (!pSnapshot.llmConfigured)
		{
			out.suggestStagingLlm = Gate.disabled("wizard.gate.noLlm");
		}
		else if (WizardRules.countRuleEntities(pSnapshot) < 2)
		{
			out.suggestStagingLlm = Gate.disabled("wizard.gate.needTwoEntities");
		}
		// staging-focus lot (2026-09-23, the product owner on project A: a second click on "Affiner avec le modele de
		// langage" 15 seconds after "Proposer le staging" had already chained it created a duplicate canonical
		// entity - StagingLlmSuggester itself is not idempotent yet, feature/staging-llm-rerun's job, not this
		// one): refuse a re-run while the PREVIOUS run's own proposals are still undecided, so a person (or the
		// suggestStaging chain below) cannot pile a second model pass on top of one nobody has reviewed yet.
		// Once every LLM-origin entity is ACCEPTED/REJECTED/APPLIED, the gate opens again.
		else if (WizardRules.hasPendingLlmStagingEntity(pSnapshot))
		{
			out.suggestStagingLlm = Gate.disabled("wizard.gate.llmProposalsPending");
		}
		else
		{
			out.suggestStagingLlm = Gate.enabled();
		}

		final boolean hasStagingEntity = !pSnapshot.stagingEntities.isEmpty();
		final boolean hasAcceptedEntity = WizardRules.hasAcceptedLikeEntity(pSnapshot);
		final boolean anyMappingWarning = WizardRules.anyAttributeMappingWarning(pSnapshot);
		// STAGING_DATASET, not STAGING_MODEL: the second (final) step of StagingGenerator.generate - the dataset
		// itself must exist to write into, and MODEL alone (the first step) does not guarantee DATASET succeeded
		final boolean stagingModelDone = WizardRules.isProjectStepDone(pSnapshot, "STAGING_DATASET");
		final boolean stagingLoadDone = WizardRules.isProjectStepDone(pSnapshot, "STAGING_LOAD");
		final boolean masterModelDone = WizardRules.isProjectStepDone(pSnapshot, "MASTER_DATASET");
		final boolean masterLoadDone = WizardRules.isProjectStepDone(pSnapshot, "MASTER_LOAD");

		out.suggestQuality = !hasStagingEntity ? Gate.disabled("wizard.gate.needStagingEntities") : Gate.enabled();
		out.suggestQualityLlm = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly")
			: !pSnapshot.llmConfigured ? Gate.disabled("wizard.gate.noLlm") : !hasStagingEntity ? Gate.disabled("wizard.gate.needStagingEntities") : Gate.enabled();
		out.suggestCapabilitiesLlm = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly")
			: !pSnapshot.llmConfigured ? Gate.disabled("wizard.gate.noLlm") : !hasAcceptedEntity ? Gate.disabled("wizard.gate.needAcceptedEntities") : Gate.enabled();
		out.reopenMappings = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly") : !anyMappingWarning ? Gate.disabled("wizard.gate.noOrphanedMappings") : Gate.enabled();
		out.generateStaging = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly") : !hasAcceptedEntity ? Gate.disabled("wizard.gate.needAcceptedEntities") : Gate.enabled();
		// target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): "Load the staging" additionally needs every
		// accepted entity to have an accepted feed, and a child feed's own parent feed accepted too - checked
		// LAST (after the pre-existing mapping-warning check) so an existing "needMappings" refusal keeps its
		// own wording exactly as before; the new reason only ever fires once that one is already satisfied.
		final String feedProblem = WizardRules.feedCoverageProblem(pSnapshot);
		out.loadStaging = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly") : !stagingModelDone ? Gate.disabled("wizard.gate.needStagingModel")
			: anyMappingWarning ? Gate.disabled("wizard.gate.needMappings") : feedProblem != null ? Gate.disabled("wizard.gate.needFeeds", feedProblem) : Gate.enabled();
		out.generateMaster = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly") : !stagingLoadDone ? Gate.disabled("wizard.gate.needStagingLoad") : Gate.enabled();
		out.loadMaster = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly") : !masterModelDone ? Gate.disabled("wizard.gate.needMasterModel") : Gate.enabled();
		out.suggestMatching = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly") : !masterLoadDone ? Gate.disabled("wizard.gate.needMasterLoad") : Gate.enabled();
		out.suggestMatchingLlm = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly")
			: !pSnapshot.llmConfigured ? Gate.disabled("wizard.gate.noLlm") : !masterLoadDone ? Gate.disabled("wizard.gate.needMasterLoad") : Gate.enabled();
		out.configureMatching = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly")
			: !WizardRules.hasMatchPlanWithStatus(pSnapshot, "ACCEPTED") ? Gate.disabled("wizard.gate.needAcceptedMatchPlan") : Gate.enabled();
		out.runMatching = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly")
			: !WizardRules.hasMatchPlanWithStatus(pSnapshot, "APPLIED") ? Gate.disabled("wizard.gate.needConfiguredMatching") : Gate.enabled();
		out.accelSuggestPacks = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly") : !WizardRules.hasProject(pSnapshot) ? Gate.disabled("wizard.blocked.needProject") : Gate.enabled();
		out.accelRunProposal = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly") : !WizardRules.hasAccelStart(pSnapshot) ? Gate.disabled("wizard.blocked.needPack")
			: !pSnapshot.llmConfigured ? Gate.disabled("wizard.gate.noLlm") : pSnapshot.accelJob != null ? Gate.disabled("wizard.gate.accelJobRunning") : Gate.enabled();
		out.accelGenerate = !pAdministrator ? Gate.disabled("wizard.gate.adminOnly") : pSnapshot.differences.isEmpty() ? Gate.disabled("wizard.blocked.needProposal")
			: WizardRules.differencesToDecide(pSnapshot) > 0 ? Gate.disabled("wizard.blocked.needDecisions") : pSnapshot.accelJob != null ? Gate.disabled("wizard.gate.accelJobRunning") : Gate.enabled();
		return out;
	}

	private static boolean hasAcceptedLikeEntity(final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if (WizardRules.isAcceptedLike(entity.status))
			{
				return true;
			}
		}
		return false;
	}

	/** Whether ANY attribute of the project carries {@link WizardSnapshot.AttributeInfo#mappingWarning} - see {@link ActionGates#loadStaging}/{@link ActionGates#reopenMappings}'s own Javadoc. */
	private static boolean anyAttributeMappingWarning(final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			for (final WizardSnapshot.AttributeInfo attribute : entity.attributes)
			{
				if (attribute.mappingWarning != null && !attribute.mappingWarning.isEmpty())
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): what, if anything, blocks "Load the staging" on
	 * feed coverage - every {@link #isAcceptedLike accepted-like} entity needs at least one accepted-like feed
	 * of its own (a still-{@code PROPOSED}/{@code REJECTED} entity has nothing to load from, and one with no
	 * feed at all - a project mid-way through the target-mapping backfill - reads the same way: no accepted
	 * feed), and every accepted-like CHILD feed needs its OWN parent feed accepted too (the loader resolves the
	 * parent's staging key at load time, docs/TARGET-MAPPING.md sec 3.1 - a child feed accepted while its
	 * parent stays {@code PROPOSED}/{@code REJECTED} would write orphan rows). A child feed's parent lives on a
	 * DIFFERENT entity's card ({@code TransferFeed.entity}), so parent feeds are indexed by id ACROSS every
	 * entity first. Returns plain, already-formatted English text naming the FIRST entity/feed found in that
	 * state - never every one of them, the same "name one, not a list" convention {@code
	 * ReviewRules.canAcceptStagingAttribute}'s own refusal messages already use - or null when nothing is
	 * missing. Pure: no EBX class.
	 */
	static String feedCoverageProblem(final WizardSnapshot pSnapshot)
	{
		final java.util.Map<String, WizardSnapshot.FeedInfo> feedsById = new java.util.HashMap<String, WizardSnapshot.FeedInfo>();
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			for (final WizardSnapshot.FeedInfo feed : entity.feeds)
			{
				feedsById.put(feed.id, feed);
			}
		}
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if (!WizardRules.isAcceptedLike(entity.status))
			{
				continue;
			}
			boolean hasAcceptedFeed = false;
			for (final WizardSnapshot.FeedInfo feed : entity.feeds)
			{
				if (WizardRules.isAcceptedLike(feed.status))
				{
					hasAcceptedFeed = true;
					break;
				}
			}
			if (!hasAcceptedFeed)
			{
				return "Entity '" + entity.name + "' has no accepted feed yet.";
			}
		}
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			for (final WizardSnapshot.FeedInfo feed : entity.feeds)
			{
				if (feed.parentFeedId.isEmpty() || !WizardRules.isAcceptedLike(feed.status))
				{
					continue;
				}
				final WizardSnapshot.FeedInfo parentFeed = feedsById.get(feed.parentFeedId);
				if (parentFeed == null || !WizardRules.isAcceptedLike(parentFeed.status))
				{
					return "Entity '" + entity.name + "': its feed from " + feed.landingTableName + " is a child feed whose parent feed is not accepted yet.";
				}
			}
		}
		return null;
	}

	/** Whether {@code pStep} (a {@link WizardSnapshot#projectSteps} entry) is {@code DONE}; false when the step never ran. */
	public static boolean isProjectStepDone(final WizardSnapshot pSnapshot, final String pStep)
	{
		for (final WizardSnapshot.PipelineStepInfo step : pSnapshot.projectSteps)
		{
			if (step.step.equals(pStep))
			{
				return "DONE".equals(step.status);
			}
		}
		return false;
	}

	/** How many match plans reached {@code APPLIED} - the matching step's completion, and the summary's matching zone detail. */
	public static int matchPlansApplied(final WizardSnapshot pSnapshot)
	{
		int count = 0;
		for (final WizardSnapshot.MatchPlanInfo plan : pSnapshot.matchPlans)
		{
			if ("APPLIED".equals(plan.status))
			{
				count++;
			}
		}
		return count;
	}

	private static boolean hasMatchPlanWithStatus(final WizardSnapshot pSnapshot, final String pStatus)
	{
		for (final WizardSnapshot.MatchPlanInfo plan : pSnapshot.matchPlans)
		{
			if (pStatus.equals(plan.status))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean anyTableGenerated(final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				if ("GENERATED".equals(table.status) || "LOADED".equals(table.status) || "DRIFTED".equals(table.status))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static int countRuleEntities(final WizardSnapshot pSnapshot)
	{
		int count = 0;
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if ("RULE".equals(entity.origin))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * Staging-focus lot (2026-09-23): whether at least one {@code StagingEntity} of {@code origin LLM} is
	 * still {@code PROPOSED} - gates {@link ActionGates#suggestStagingLlm} so a person (or {@code
	 * suggestStaging}'s own chaining) cannot run the language-model pass again while its previous canonical
	 * entities have not been decided yet. Only origin {@code LLM} counts: a rule entity left {@code PROPOSED}
	 * (no model configured yet, or the model has not run) never blocks the FIRST run. {@code SUPERSEDED} is
	 * deliberately not {@code PROPOSED}, so a rule entity the model already absorbed is not what this checks
	 * either - only the canonical entities the model itself created are.
	 */
	static boolean hasPendingLlmStagingEntity(final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if ("LLM".equals(entity.origin) && "PROPOSED".equals(entity.status))
			{
				return true;
			}
		}
		return false;
	}

	// --------------------------------------------------------------------------------------------- entity generation hint

	/**
	 * Whether {@code com.on.ebx.factory.staging.StagingModel#build} would skip this entity for lack of an
	 * accepted business attribute, and if so how many are still waiting - the exact same condition
	 * {@code StagingModel} itself uses (an accepted-like entity, technical key excluded from the count since
	 * it has no decision of its own - {@code TechnicalKeyRule} - and never counts as the "business"
	 * attribute that unblocks generation). Shown on the entity's card so a reviewer sees the skip coming
	 * instead of discovering it only after running the generator.
	 */
	public static final class EntityHint
	{
		public final boolean waitingForAttribute;
		public final int waitingCount;

		private EntityHint(final boolean pWaiting, final int pCount)
		{
			this.waitingForAttribute = pWaiting;
			this.waitingCount = pCount;
		}

		private static final EntityHint NONE = new EntityHint(false, 0);
	}

	public static EntityHint entityHint(final WizardSnapshot.StagingEntityInfo pEntity)
	{
		if (!WizardRules.isAcceptedLike(pEntity.status))
		{
			return EntityHint.NONE;
		}
		boolean hasAcceptedBusinessAttribute = false;
		int waiting = 0;
		for (final WizardSnapshot.AttributeInfo attribute : pEntity.attributes)
		{
			if (attribute.followsEntity())
			{
				continue;
			}
			if (WizardRules.isAcceptedLike(attribute.status))
			{
				hasAcceptedBusinessAttribute = true;
			}
			if ("PROPOSED".equals(attribute.status))
			{
				waiting++;
			}
		}
		return hasAcceptedBusinessAttribute ? EntityHint.NONE : new EntityHint(true, waiting);
	}

	private static boolean isAcceptedLike(final String pStatus)
	{
		return "ACCEPTED".equals(pStatus) || "APPLIED".equals(pStatus);
	}

	/**
	 * Block-accept lot (2026-09-23): whether {@code pEntity}'s own card has ANYTHING still {@code PROPOSED} to
	 * decide - the entity itself, one of its non-technical-key attributes, one of their mappings, or one of
	 * the inline proposal lines ({@code MAPPING_STEP}/{@code QUALITY}/{@code SCRIPT_CONSTRAINT}) grouped under
	 * it - gates whether {@code WizardPage} draws the card's own "Accept all of this entity" button at all
	 * (never shown on a card with nothing left to decide, same "no dead button" discipline as every other
	 * gate in this class).
	 */
	public static boolean hasDecidableProposals(final WizardSnapshot.StagingEntityInfo pEntity)
	{
		if ("PROPOSED".equals(pEntity.status))
		{
			return true;
		}
		for (final WizardSnapshot.AttributeInfo attribute : pEntity.attributes)
		{
			if (!attribute.followsEntity() && "PROPOSED".equals(attribute.status))
			{
				return true;
			}
			for (final WizardSnapshot.MappingInfo mapping : attribute.mappings)
			{
				if ("PROPOSED".equals(mapping.status))
				{
					return true;
				}
			}
		}
		for (final WizardSnapshot.ProposalLineInfo proposalLine : pEntity.proposalLines)
		{
			if ("PROPOSED".equals(proposalLine.line.status))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Quality-block-bulk lot (2026-09-23): whether {@code pEntity} has at least one {@code MAPPING_STEP}/
	 * {@code QUALITY} proposal line still {@code PROPOSED} - gates the entity card's own "Accept all quality
	 * proposals of this entity" / "Reject all" buttons, the narrower sibling of {@link #hasDecidableProposals}
	 * (which also counts the entity/attributes/mappings/{@code SCRIPT_CONSTRAINT}).
	 */
	public static boolean hasDecidableQualityProposals(final WizardSnapshot.StagingEntityInfo pEntity)
	{
		for (final WizardSnapshot.ProposalLineInfo proposalLine : pEntity.proposalLines)
		{
			if ("PROPOSED".equals(proposalLine.line.status)
				&& (com.on.ebx.factory.review.ReviewLine.MAPPING_STEP.equals(proposalLine.line.nature) || com.on.ebx.factory.review.ReviewLine.QUALITY.equals(proposalLine.line.nature)))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Threshold-left-out lot (2026-09-23, coordinator finding on project Personnes): the pure classification a
	 * bulk sweep needs for ONE row - {@code ProjectWizardUserService.bulkStaging}'s own "counting helper", unit-
	 * tested directly. A row that is not {@code PROPOSED} is neither {@link #eligible} nor {@link
	 * #leftOutBelowThreshold} (nothing to do with it either way - it was already decided, or applied). Product
	 * report: "Quand je clique sur Accept all, toutes les cleaning steps de Sample n'ont pas ete acceptees" -
	 * MappingStep id 22 (confidence 70, threshold 80) was silently left out, and the notice never said so.
	 */
	public static final class BulkEligibility
	{
		/** Attempt to decide this row (call {@code ProposalReviews.decide}) - true when PROPOSED and (at/above the threshold, or the person ticked "include below threshold"). */
		public final boolean eligible;
		/** PROPOSED, below the threshold, and NOT included - the row a plain "Accept all" used to silently drop, now counted and named in the notice. */
		public final boolean leftOutBelowThreshold;

		private BulkEligibility(final boolean pEligible, final boolean pLeftOutBelowThreshold)
		{
			this.eligible = pEligible;
			this.leftOutBelowThreshold = pLeftOutBelowThreshold;
		}
	}

	public static BulkEligibility bulkEligibility(final String pStatus, final Integer pConfidence, final int pThreshold, final boolean pIncludeBelowThreshold)
	{
		if (!"PROPOSED".equals(pStatus))
		{
			return new BulkEligibility(false, false);
		}
		final int confidence = pConfidence == null ? 0 : pConfidence.intValue();
		if (confidence >= pThreshold || pIncludeBelowThreshold)
		{
			return new BulkEligibility(true, false);
		}
		return new BulkEligibility(false, true);
	}

	/**
	 * Apply-visibility lot (2026-09-23, coordinator finding on project Personnes): whether {@code
	 * CapabilitySuggestion.zone} {@code pZone}'s own data model has been generated - {@code MASTER} needs
	 * {@code MASTER_DATASET} done, {@code STAGING} needs {@code STAGING_DATASET} done, the exact same
	 * PipelineStep signal {@link #actionGates}'s own {@code loadMaster}/{@code loadStaging} gates already
	 * trust for "the zone's model genuinely exists" - never a live {@code get_data_model} MCP call from this
	 * pure, EBX-free class (that is what {@code ebx.CapabilityApplier} itself checks, at apply time; this is
	 * the same trade-off the rest of this class already makes for every other project-state gate). "Pourquoi
	 * le bouton Apply des script constraints ne s'enleve jamais ?" - ids 10-14, zone MASTER, applyMessage
	 * "model 'Personnes_Master' does not exist yet": every attempt failed the exact same way because the gate
	 * did not exist to stop the attempt before it started.
	 */
	public static Gate capabilityApplyGate(final WizardSnapshot pSnapshot, final String pZone)
	{
		if ("MASTER".equals(pZone))
		{
			return WizardRules.isProjectStepDone(pSnapshot, "MASTER_DATASET") ? Gate.enabled() : Gate.disabled("wizard.gate.needMasterModelForApply");
		}
		return WizardRules.isProjectStepDone(pSnapshot, "STAGING_DATASET") ? Gate.enabled() : Gate.disabled("wizard.gate.needStagingModelForApply");
	}

	/**
	 * Deduced-scan lot (2026-09-24, read-recipe-contract.md sec "Why"): whether a table still carries {@code
	 * Asset.scanState=TO_CONFIRM}. Since fix/continue-landing-warning (2026-09-26) it only drives a WARNING
	 * next to "Continue to the landing" ({@code wizard.deduced.pendingAtLanding}), never a disabled button: the
	 * contract's rule, "a DEDUCED table is never loaded before a person confirms it", is enforced per table by
	 * {@code ReadRecipe.resolve} at flow generation. Takes only the {@code scanState} strings (never the whole
	 * {@link WizardSnapshot.TableInfo}) so it stays a one-line, EBX-free decision - an OFFICIAL asset's {@code
	 * scanState} is always {@code CONFIRMED} per the contract, and a legacy row's is blank/absent, so checking
	 * for the literal string {@code "TO_CONFIRM"} alone is enough.
	 */
	public static boolean hasUnconfirmedDeducedTable(final List<String> pTickedTableScanStates)
	{
		if (pTickedTableScanStates == null)
		{
			return false;
		}
		for (final String scanState : pTickedTableScanStates)
		{
			if ("TO_CONFIRM".equals(scanState))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Fix lot (2026-09-26, defect 12): the "Add more applications" {@code <details>} of step 2 (task brief:
	 * "you can come back and add an application at any time") is a plain HTML disclosure re-rendered from
	 * scratch on every postback - with no {@code open} attribute it collapses after EVERY round trip, so
	 * adding a second application meant reopening it after each action. Kept open whenever the person opened
	 * it ({@code Model#onboardOpen}), or an onboarding panel inside it is mid-flow: a connection picked in the
	 * "Connect a live system" combobox, a diagnosis already run for it, a catalog source picked in the other
	 * combobox, or a sample-files import report just shown - any of these means the person is actively working
	 * inside the panel and a fresh collapse would hide what they were just looking at.
	 */
	public static boolean addMoreApplicationsOpen(final boolean pOnboardOpen, final String pOnboardConnectorId, final boolean pOnboardDiagnosisPresent,
		final String pPickedCatalogSourceId, final boolean pSampleFilesReportPresent)
	{
		return pOnboardOpen || pOnboardConnectorId != null && !pOnboardConnectorId.trim().isEmpty() || pOnboardDiagnosisPresent
			|| pPickedCatalogSourceId != null && !pPickedCatalogSourceId.trim().isEmpty() || pSampleFilesReportPresent;
	}

	/**
	 * Fix lot (2026-09-27, round 5, task item 1): whether the Review card of {@code pSource} must offer a
	 * deduced scan action - the ORIGINAL "Run a deduced scan" button ({@code DEDUCED_SCAN_START}) only ever
	 * rendered when the application had NO landing table at all ({@code pSource.tables.isEmpty()}, the same
	 * "the official scan found nothing" heuristic {@code renderReviewCard} already uses for its own "no table
	 * yet" hint) - so an application already onboarded through a deduced scan, whose source has since gained a
	 * new column or a new listable table, had no way back into the wizard's own UI: a person had to post the
	 * action by hand. This widens the same button to ALSO render whenever the application already holds at
	 * least one {@code DEDUCED} asset ({@link WizardSnapshot.TableInfo#scanOrigin}, kept regardless of the
	 * table's own {@code scanState} - CONFIRMED included, per that field's own contract Javadoc) - the second
	 * click then reconciles rather than creating a duplicate (defect 9, {@code AssetReconciliation}), so this
	 * is always safe to offer, never a wrong write. {@code pSource.tables.isEmpty()} is deliberately NOT folded
	 * in here: that is a distinct condition (no table at all, deduced or official) with its own existing
	 * caller and its own "Start" wording; this method answers only the "there is already a deduced table"
	 * half - see {@code WizardPage#renderReviewCard}, which OR's the two together.
	 */
	public static boolean hasDeducedAsset(final WizardSnapshot.SourceInfo pSource)
	{
		if (pSource == null)
		{
			return false;
		}
		for (final WizardSnapshot.TableInfo table : pSource.tables)
		{
			if ("DEDUCED".equals(table.scanOrigin))
			{
				return true;
			}
		}
		return false;
	}
	// ------------------------------------------------------------------------------------- one primary button per step (UX lot U4)

	/**
	 * UX lot U4 (2026-10-13, RAPPORT 2.2 / 3.4): the project code proposed from the label a person types - the code is the most binding field of step 1
	 * (prefix of everything the project generates, immutable), so it is filled in for them and stays editable. Words (letters and digits, accents
	 * dropped) are joined with their first letter upper-cased and the rest left as typed ({@code "Ux CRM sf"} gives {@code "UxCRMSf"}); a leading digit
	 * run is dropped (a code starts with a letter, {@link #CODE_PATTERN}); at most {@link #CODE_MAX} characters. Empty when the label holds no letter.
	 * {@code wizard.js} (block "project code from the label") mirrors this method character for character.
	 */
	public static String projectCodeFromLabel(final String pLabel)
	{
		if (pLabel == null)
		{
			return "";
		}
		final String plain = java.text.Normalizer.normalize(pLabel, java.text.Normalizer.Form.NFD).replaceAll("[\\u0300-\\u036f]", "");
		final StringBuilder out = new StringBuilder();
		final String[] words = plain.split("[^A-Za-z0-9]+");
		for (final String word : words)
		{
			if (word.isEmpty())
			{
				continue;
			}
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		int start = 0;
		while (start < out.length() && !Character.isLetter(out.charAt(start)))
		{
			start++;
		}
		final String code = out.substring(start);
		return code.length() > WizardRules.CODE_MAX ? code.substring(0, WizardRules.CODE_MAX) : code;
	}

	/** Step 1's bottom "Next" is the way to step 2 and exists only once the project does: before that "Create the project" is the one forward gesture. */
	public static boolean nextButtonVisible(final boolean pProjectSaved)
	{
		return pProjectSaved;
	}

	/** The line shown under the step rail when a person clicks a step that is not open yet: the same reason the hover title gives, never a silent no-op. Null for an open step. */
	public static String railBlockedNote(final StepState pState)
	{
		return pState == null || pState.reachable ? null : pState.blockedKey;
	}

	/**
	 * The one thing step 4 asks of a person right now - the pure rule behind its single primary button (and what hides: the build block stays
	 * out of the page until it is the next gesture, the "Apply" buttons of the derivations until the staging exists).
	 * <ul>
	 * <li>{@code PROPOSE}: nothing proposed yet - or every business object was rejected: "Propose the staging" (again);</li>
	 * <li>{@code RUNNING}: the proposal run is running: the live list, no button;</li>
	 * <li>{@code REVIEW}: points wait for a decision ({@link Step4Counts#waiting}): "Review N points", whether or not the staging was built before;</li>
	 * <li>{@code BUILD}: nothing left to review, an accepted business object, staging not loaded: "Build and load";</li>
	 * <li>{@code LOADING}: the build and load runs: its live panel, no button;</li>
	 * <li>{@code CONTINUE}: nothing left to review and the staging is loaded: "Continue to the master" ("Build again" is secondary).</li>
	 * </ul>
	 * A running job always wins over what is on screen (a second click on a button of the same step must never be offered while it runs).
	 */
	public enum Step4Primary
	{
		PROPOSE, RUNNING, REVIEW, BUILD, LOADING, CONTINUE
	}

	public static Step4Primary step4Primary(final boolean pProposalRunning, final boolean pStagingLoadRunning, final boolean pHasBusinessObject, final boolean pHasAcceptedBusinessObject,
		final int pPointsToReview, final boolean pStagingLoaded)
	{
		if (pStagingLoadRunning)
		{
			return Step4Primary.LOADING;
		}
		if (pProposalRunning)
		{
			return Step4Primary.RUNNING;
		}
		if (!pHasBusinessObject)
		{
			return Step4Primary.PROPOSE;
		}
		if (pPointsToReview > 0)
		{
			return Step4Primary.REVIEW;
		}
		if (!pHasAcceptedBusinessObject)
		{
			return Step4Primary.PROPOSE;
		}
		return pStagingLoaded ? Step4Primary.CONTINUE : Step4Primary.BUILD;
	}

	/** What the line under "Review the proposal" says (UX lot C3). */
	public enum ReviewNoteKind
	{
		/** The matrix figure is not known (no matrix link, or the read failed): the button stands alone. */
		NONE,
		/** {@code attention} points need a person; {@code others} go with the bulk button (0 when nothing else waits). */
		FLAGGED,
		/** Nothing is flagged: one click accepts everything. */
		NOTHING_FLAGGED
	}

	public static final class ReviewNote
	{
		public final ReviewNoteKind kind;
		public final int attention;
		public final int others;

		ReviewNote(final ReviewNoteKind pKind, final int pAttention, final int pOthers)
		{
			this.kind = pKind;
			this.attention = pAttention;
			this.others = pOthers;
		}
	}

	/**
	 * The line under "Review the proposal", from the Mapping matrix' OWN figures and nothing else: {@code pNeedYou} = {@code MatrixCounter.Tally#needYou} (the header's
	 * "N points need your attention"), {@code pAcceptAll} = {@code MatrixCounter.Tally#acceptAll} (the N of the footer's "Accept the N proposals", the other proposals
	 * that go with one click); negative = unknown (the matrix could not be read): no line. History: lot C3 subtracted the matrix figure from this screen's own tally
	 * ({@link Step4Counts#waiting}: "the other 96" next to "Accept the 93", the tally counting the cleaning steps and the optional look-ups the matrix files elsewhere);
	 * the staging-screens lot quoted the button's N when known and subtracted otherwise; staging polish (2026-10-17) removed the subtraction: one source, the
	 * {@code Tally}, and the kind of sentence comes from the same rule as the matrix header ({@code MatrixCounter#headlineOf}).
	 */
	public static ReviewNote step4MatrixNote(final int pNeedYou, final int pAcceptAll)
	{
		if (pNeedYou < 0 || pAcceptAll < 0)
		{
			return new ReviewNote(ReviewNoteKind.NONE, 0, 0);
		}
		switch (com.on.ebx.factory.mappingmatrix.MatrixCounter.headlineOf(pNeedYou, pAcceptAll))
		{
			case NEED_YOU:
				return new ReviewNote(ReviewNoteKind.FLAGGED, pNeedYou, pAcceptAll);
			case ONLY_BUTTON:
				return new ReviewNote(ReviewNoteKind.NOTHING_FLAGGED, 0, pAcceptAll);
			default:
				return new ReviewNote(ReviewNoteKind.NONE, 0, 0); // the matrix has nothing left to decide: it says so itself
		}
	}

	/** {@link #step4Primary} computed from a snapshot (a SUPERSEDED business object counts nowhere, same rule as {@link Step4Counts}). */
	public static Step4Primary step4Primary(final WizardSnapshot pSnapshot, final boolean pProposalRunning, final boolean pStagingLoadRunning)
	{
		boolean any = false;
		boolean accepted = false;
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if ("SUPERSEDED".equals(entity.status))
			{
				continue;
			}
			any = true;
			accepted |= WizardRules.isAcceptedLike(entity.status);
		}
		return WizardRules.step4Primary(pProposalRunning, pStagingLoadRunning, any, accepted, Step4Counts.of(pSnapshot).waiting(),
			WizardRules.isProjectStepDone(pSnapshot, "STAGING_LOAD"));
	}

	/** True when a business object (not SUPERSEDED) was accepted - what the build of the staging needs. */
	public static boolean hasAcceptedBusinessObject(final WizardSnapshot pSnapshot)
	{
		return WizardRules.hasAcceptedLikeEntity(pSnapshot);
	}

	/**
	 * Whether the staging tables exist, i.e. whether an accepted derivation can be applied (its constraint targets the staging model): the same evidence
	 * as {@link ActionGates#loadStaging}. Before that the "decisions left" card says "will be applied after the first build" instead of showing buttons
	 * that can only answer "Generate the staging zone first".
	 */
	public static boolean stagingExists(final WizardSnapshot pSnapshot)
	{
		return WizardRules.isProjectStepDone(pSnapshot, "STAGING_DATASET");
	}

	/**
	 * Step 1: the "Advanced" fold (staging mode, what may reach the language model) is closed - its defaults are one sentence outside it - unless
	 * something inside needs the person: a refused value of one of its fields, or a choice above the instance cap.
	 */
	public static boolean step1AdvancedOpen(final boolean pFieldError, final boolean pLlmCapped)
	{
		return pFieldError || pLlmCapped;
	}
}
