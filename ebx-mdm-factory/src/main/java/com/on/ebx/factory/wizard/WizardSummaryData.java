package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure computation behind the wizard's step 7 ("Summary" - {@code docs/mockups/wizard-step7-summary.dc.html}):
 * plain value objects in ({@link Input}), one {@link Output} out, no EBX type anywhere - {@code
 * WizardSummaryDataTest} exercises it directly, the same discipline {@code CockpitData} already
 * established for the Cockpit screen. {@link com.on.ebx.factory.ebx.ui.ProjectWizardUserService} is the one
 * place that gathers {@link Input} (reusing {@code CockpitData.compute} for the pipeline/volumes facts it
 * already knows how to read, plus a couple of small readers of this lot's own - {@code
 * com.on.ebx.factory.ebx.GoldenCompleteness}, {@code com.on.ebx.factory.ebx.WizardMatchSummary} - for what
 * {@code CockpitData} does not carry: golden-record fill rates and matched/suspect counts).
 * <p>
 * <b>Journey diagram, simplified from the mockup on purpose</b> (documented in {@code docs/WIZARD-SUMMARY.md}):
 * the mockup's SVG has hand-authored bezier control points; this class instead computes a generic
 * proportional "Sankey-style" band per SOURCE, carried at the SAME relative share through Landing and
 * Staging (three hops), then ONE single aggregate band from Master to Golden - never re-deriving a
 * per-source split past Master (a matched/merged golden record can no longer be attributed to one source).
 * Works for 1..~8 sources without a hardcoded layout; a stage with nothing loaded yet renders a single
 * grey, minimum-height node labelled "not loaded yet" rather than a zero-height (invisible) one.
 */
public final class WizardSummaryData
{
	private WizardSummaryData()
	{
	}

	// -------------------------------------------------------------------------------------------------------- input

	public static final class SourceInput
	{
		public String name = "";
		public long receivedRows;
	}

	/** One entity's row count at a zone (staging or master) - {@code null} rows means "not loaded yet" for that entity. */
	public static final class ZoneEntityInput
	{
		public String name = "";
		public Long rows;
		public Long rejected;
		/** Step 7 screen counts (2026-10-18): the entity's GOLDEN and MERGED rows (Match and Merge state); null = the table has no state (not known), never a 0 for "not known". */
		public Long golden;
		public Long merged;
	}

	public static final class CompletenessInput
	{
		public String attributeLabel = "";
		public int filled;
		public int total;
		/**
		 * UX lot U3 (2026-10-13): the master object this attribute belongs to - the summary groups the fill rates by object ("of the N golden records of
		 * Personne"); empty = not known (a caller that predates the grouping: one group without a name).
		 */
		public String entityName = "";
		/** The attribute's own name, without the object prefix {@link #attributeLabel} carries when several objects are read. Empty = use the label. */
		public String attribute = "";
	}

	/**
	 * UX lot U3: one master object as the matching step sees it - its plan (the plan's status, empty when the object has none) and the outcome of its last
	 * matching run (the run step's status, empty when it never ran). The summary's Matching card and its completeness card are scoped by these.
	 */
	public static final class MatchObjectInput
	{
		public String name = "";
		/** {@code APPLIED}/{@code ACCEPTED}/{@code PROPOSED}, or empty/other = no usable plan. */
		public String planStatus = "";
		/** {@code DONE}/{@code FAILED}/{@code RUNNING}, or empty = never run. */
		public String runStatus = "";

		public MatchObjectInput()
		{
		}

		public MatchObjectInput(final String pName, final String pPlanStatus, final String pRunStatus)
		{
			this.name = pName == null ? "" : pName;
			this.planStatus = pPlanStatus == null ? "" : pPlanStatus;
			this.runStatus = pRunStatus == null ? "" : pRunStatus;
		}

		boolean hasPlan()
		{
			return "APPLIED".equals(this.planStatus) || "ACCEPTED".equals(this.planStatus) || "PROPOSED".equals(this.planStatus);
		}

		boolean ranOk()
		{
			return "DONE".equals(this.runStatus);
		}
	}

	public static final class JournalStepInput
	{
		/** {@code LANDING}/{@code STAGING}/{@code MASTER}/{@code MATCHING} - resolved to a label by the caller (pure class, no message bundle). */
		public String stepLabel = "";
		public long seconds;
		public String rowsText = "";
	}

	/** One {@code MandatoryGap}: a mandatory business attribute of an entity every feeding mapping of which is rejected - the same condition {@code WizardSnapshot.AttributeInfo#mappingWarning} already flags. */
	public static final class MandatoryGapInput
	{
		public String entityName = "";
		public String attributeName = "";
	}

	/** One open point already resolved to displayable text/action by the caller - this class only orders/colors it (a pure class has no message bundle of its own, unlike {@code CockpitData}'s equivalent rows which {@code WizardPage} localizes at render time). */
	public static final class OpenPointInput
	{
		/** {@code ERROR}/{@code WARN}/{@code INFO} - decides the dot color and the ordering (errors first). */
		public String severity = "INFO";
		public String title = "";
		public String detail = "";
		public String actionLabel = "";
		/** {@code >0}: the action is a client-side {@code GOTO_STEP}; {@code 0}: use {@link #href} instead. */
		public int gotoStep;
		public String href = "";
		/**
		 * Distribution-wizard lot (2026-09-24, coordinator review): non-empty for a "target not armed" point
		 * ({@code WIZARD-DISTRIBUTION.md}) - {@code gotoStep} still names the step ({@code
		 * WizardRules.STEP_DISTRIBUTION}), but the rendered button ALSO opens that one target's setup panel in
		 * the same click ({@code WizardPage#renderOpenPoints}: a real {@code DISTRIBUTION_TARGET_OPEN} submit,
		 * combined with the client-side step switch via the button's OWN {@code data-mdmf-goto-step} attribute -
		 * no {@code wizard.js} change needed, see that method's own comment). Empty for every other open point,
		 * unchanged behaviour.
		 */
		public String targetId = "";

		public OpenPointInput(final String pSeverity, final String pTitle, final String pDetail, final String pActionLabel, final int pGotoStep, final String pHref)
		{
			this(pSeverity, pTitle, pDetail, pActionLabel, pGotoStep, pHref, "");
		}

		public OpenPointInput(final String pSeverity, final String pTitle, final String pDetail, final String pActionLabel, final int pGotoStep, final String pHref,
			final String pTargetId)
		{
			this.severity = pSeverity;
			this.title = pTitle;
			this.detail = pDetail;
			this.actionLabel = pActionLabel;
			this.gotoStep = pGotoStep;
			this.href = pHref;
			this.targetId = pTargetId == null ? "" : pTargetId;
		}
	}

	public static final class Input
	{
		public String projectLabel = "";

		public final List<SourceInput> sources = new ArrayList<SourceInput>();

		// landing
		/** UX lot U3: the tables ticked AND loaded - what the Landing card counts (it used to count every table found, the unticked ones included). */
		public int landingTableCount;
		/** UX lot U3: tables found in the sources and not kept (unticked) - a side note of the Landing card, never part of its count. */
		public int landingOtherTableCount;
		public String landingDataspace = "";
		public Long landingLastLoadMillis;
		public String landingOpenUrl = "";
		public boolean landingLoaded;

		// staging
		public final List<ZoneEntityInput> stagingEntities = new ArrayList<ZoneEntityInput>();
		public int stagingFeedCount;
		public int stagingMappingCount;
		public int qualityRulesAccepted;
		public int constraintsApplied;
		public String stagingDataspace = "";
		public String stagingOpenUrl = "";
		public Long stagingLastLoadMillis;

		// master
		public final List<ZoneEntityInput> masterEntities = new ArrayList<ZoneEntityInput>();
		/** Step 7 screen counts: the main master object (the one whose golden records are quoted, see {@link MasterMain}); empty = no golden figure is quoted for a single object. */
		public String masterMainEntity = "";
		public String masterDataspace = "";
		public String masterDataset = "";
		public String masterOpenUrl = "";
		public Long masterLastLoadMillis;

		// matching
		public int matchPlansApplied;
		public int matchPlansTotal;
		/**
		 * UX lot U3: every master object with its plan and last run ({@link MatchObjectInput}); empty = the caller did not read them, the card then falls back
		 * to {@link #matchPlansApplied} out of {@link #matchPlansTotal}.
		 */
		public final List<MatchObjectInput> matchObjects = new ArrayList<MatchObjectInput>();
		/**
		 * Lot S7 (2026-10-18): the child objects that follow their parent with NO plan of their own (Match and Merge business-object members, "Adresse follows Personne"),
		 * each with the GOLDEN rows of its table; 0 = the table has no metadataset (no {@code Linked_Field_State}) or no golden row. They are not in {@link #matchObjects}
		 * (a follower has no plan), so the completeness card used to read "not matched yet" for them although the cascade merge had given them golden copies.
		 */
		public final java.util.Map<String, Long> followerGolden = new java.util.LinkedHashMap<String, Long>();
		public long goldenCount;
		public long pendingSuspects;
		public boolean matchingAvailable;

		// open points already resolved by the caller (localized text) - this class only orders/counts them
		public final List<OpenPointInput> openPoints = new ArrayList<OpenPointInput>();

		// automatic-intake wizard lot (2026-10-05): the intake's state ({@code OFF}/{@code ON}/{@code PAUSED}; empty = not read, treated as OFF) decides which of two
		// ALREADY-WORDED points joins the list - see {@link #withIntake}. The caller words both (the pause reason is data) and may set them whatever the state.
		public String intakeState = "";
		/** The fixed INFO point "no schedule configured": shown unless the intake is ON (then the intake IS the automation; while PAUSED nothing runs by itself, so it stays true). Null = none. */
		public OpenPointInput noSchedulePoint;
		/** The WARN point carrying the pause reason: shown only when the intake is PAUSED. Null = none. */
		public OpenPointInput intakePausedPoint;

		// "go to production" gates
		public boolean canLaunchPipeline;
		public String pipelineReason = "";
		public String pipelineHref = "";
		public boolean scheduleAvailable;
		public String scheduleHref = "";
		public boolean canCheckDelivery;
		public String deliveryReason = "";
		public String deliveryHref = "";
		public String deliveryDocHref = "";
		public String cockpitUrl = "";
		public String recordLineageUrl = "";

		// completeness
		public final List<CompletenessInput> completeness = new ArrayList<CompletenessInput>();
		public boolean completenessMasterFallback;

		// last full run
		public final List<JournalStepInput> journal = new ArrayList<JournalStepInput>();

		// distribution-wizard lot (2026-09-24, coordinator review): the "Distribute" card of "Go to production"
		// (board SummaryDistribution.dc.html) - a plain pass-through list, same "raw gate, no Output computation
		// needed" convention as canLaunchPipeline/cockpitUrl above (WizardPage#renderProductionCards already
		// reads Model.summaryInput directly, not the computed Output). Mode/state text is localized by the
		// renderer (WizardMessages.get("wizard.step7dist.mode."/"state." + code)), not pre-joined here, so it
		// never duplicates the wording step 7's own target cards already use.
		public final List<DistributionSummaryTarget> distributionTargets = new ArrayList<DistributionSummaryTarget>();
		public boolean distributionProductExists;
		/** Audit F-34 (step 8 card): no product is visible because the person's data catalog right hides it - "none yet" would be untrue. */
		public boolean distributionProductRestricted;
		public String distributionProductLabel = "";
		/** {@code ON_DEMAND}/{@code HOURLY}/{@code DAILY}/{@code WEEKLY} - only meaningful when {@link #distributionProductExists}. */
		public String distributionProductRefreshMode = "ON_DEMAND";
	}

	/** One row of the "Distribute" card - see {@link Input#distributionTargets}'s own Javadoc. */
	public static final class DistributionSummaryTarget
	{
		public String label = "";
		/** {@code REALTIME}/{@code ON_DEMAND}/{@code SCHEDULED}. */
		public String mode = "ON_DEMAND";
		/** {@code DRAFT}/{@code WAITING}/{@code ARMED}/{@code PAUSED}/{@code DISABLED}. */
		public String state = "DRAFT";
		/** Lot S2 (2026-10-18): RETIRE notices that reached this target (REALTIME only; -1 = not counted). */
		public int retiresSent = -1;
		public boolean retiresCapped;
		/** Step 7 screen counts (2026-10-18): the target made its first full send (even if it is disabled since), when it last sent, and the stored text of that send (its rows). */
		public boolean madeFirstFullSend;
		public String lastSendAt = "";
		public String lastMessage = "";
	}

	// ------------------------------------------------------------------------------------------------------- output

	public static final class JourneyNode
	{
		public final double x;
		public final double y;
		public final double w;
		public final double h;
		/** Short label, e.g. a source name or "Landing"; empty for a node whose label the caller localizes by kind. */
		public final String label;
		public final String detail;
		public final String color;
		public final boolean greyed;

		JourneyNode(final double pX, final double pY, final double pW, final double pH, final String pLabel, final String pDetail, final String pColor, final boolean pGreyed)
		{
			this.x = pX;
			this.y = pY;
			this.w = pW;
			this.h = pH;
			this.label = pLabel;
			this.detail = pDetail;
			this.color = pColor;
			this.greyed = pGreyed;
		}
	}

	public static final class JourneyFlow
	{
		public final String pathD;
		public final String color;

		JourneyFlow(final String pPathD, final String pColor)
		{
			this.pathD = pPathD;
			this.color = pColor;
		}
	}

	public static final class Journey
	{
		public final int width;
		public final int height;
		public final List<JourneyNode> nodes = new ArrayList<JourneyNode>();
		public final List<JourneyFlow> flows = new ArrayList<JourneyFlow>();

		Journey(final int pWidth, final int pHeight)
		{
			this.width = pWidth;
			this.height = pHeight;
		}
	}

	public static final class ZoneCard
	{
		/** {@code LANDING}/{@code STAGING}/{@code MASTER}/{@code MATCHING}. */
		public final String zoneKey;
		/** {@code good}/{@code warn}/{@code muted} - a rendering hint, not itself a chip label. */
		public final String statusKind;
		public final List<String> facts = new ArrayList<String>();
		/** UX lot U3: one line per master object on the Matching card (plan and last run); empty on the other cards. */
		public final List<ZoneDetail> details = new ArrayList<ZoneDetail>();
		/**
		 * Step 7 screen counts (2026-10-18): the Master card, one line per table - rows of THAT table, and for the main object (after a matching run) its golden and merged
		 * records. Rows of different tables are never added into one figure on screen (a master of 699 persons, 1068 addresses and 234 purchases did not hold 2001 "records",
		 * nor 980 golden ones). {@link #facts} keeps the totals for callers without lines.
		 */
		public final List<ZoneLine> lines = new ArrayList<ZoneLine>();
		public final String technicalName;
		public final String openUrl;
		/** {@code >0}: the "Open" action is a client-side {@code GOTO_STEP} to this wizard step (the Matching card has no
		 * dedicated screen of its own - it opens step 6 of this SAME wizard); {@code 0}: {@link #openUrl} is a real link. */
		public final int openGotoStep;

		ZoneCard(final String pZoneKey, final String pStatusKind, final String pTechnicalName, final String pOpenUrl, final int pOpenGotoStep)
		{
			this.zoneKey = pZoneKey;
			this.statusKind = pStatusKind;
			this.technicalName = pTechnicalName;
			this.openUrl = pOpenUrl;
			this.openGotoStep = pOpenGotoStep;
		}
	}

	/** One master table of the Master card: its rows, and (main object only, after a matching run) its golden and merged records. */
	public static final class ZoneLine
	{
		public final String object;
		public final long rows;
		/** Null = no golden figure for this object. */
		public final Long golden;
		public final Long merged;

		ZoneLine(final String pObject, final long pRows, final Long pGolden, final Long pMerged)
		{
			this.object = pObject;
			this.rows = pRows;
			this.golden = pGolden;
			this.merged = pMerged;
		}
	}

	/** One object's line of the Matching card: its plan and the outcome of its last run, as keys the renderer words. */
	public static final class ZoneDetail
	{
		public final String object;
		/** {@code APPLIED}/{@code ACCEPTED}/{@code PROPOSED}/{@code NONE}. */
		public final String plan;
		/** {@code DONE}/{@code FAILED}/{@code RUNNING}/{@code NOT_RUN}. */
		public final String run;

		ZoneDetail(final String pObject, final String pPlan, final String pRun)
		{
			this.object = pObject;
			this.plan = pPlan;
			this.run = pRun;
		}
	}

	/**
	 * UX lot U3: the fill rates of ONE master object, scoped to what they are measured on - the golden records of a matched object ({@link #matched},
	 * {@link #scopeCount} of them), or nothing at all for an object no matching run has produced goldens for yet (no bar: "not matched yet", never a
	 * 0 % that reads as an empty column - Organisation read "name 0 %" with 62 named organisations).
	 */
	public static final class CompletenessGroup
	{
		public final String entityName;
		public final boolean matched;
		public final long scopeCount;
		public final List<CompletenessRow> rows = new ArrayList<CompletenessRow>();

		CompletenessGroup(final String pEntityName, final boolean pMatched, final long pScopeCount)
		{
			this.entityName = pEntityName;
			this.matched = pMatched;
			this.scopeCount = pScopeCount;
		}
	}

	public static final class CompletenessRow
	{
		public final String label;
		public final int pct;

		CompletenessRow(final String pLabel, final int pPct)
		{
			this.label = pLabel;
			this.pct = pPct;
		}
	}

	public static final class JournalRow
	{
		public final String stepLabel;
		public final String timeText;
		public final String rowsText;
		public final int barPct;

		JournalRow(final String pStepLabel, final String pTimeText, final String pRowsText, final int pBarPct)
		{
			this.stepLabel = pStepLabel;
			this.timeText = pTimeText;
			this.rowsText = pRowsText;
			this.barPct = pBarPct;
		}
	}

	public static final class Output
	{
		/** {@code OPERATIONAL}/{@code NOT_READY}/{@code BLOCKED}. */
		public String verdict = "NOT_READY";
		public int openPointCount;
		public Journey journey;
		public final List<ZoneCard> zones = new ArrayList<ZoneCard>();
		/** Already ordered: ERROR first, then WARN, then INFO; stable within a severity. */
		public final List<OpenPointInput> openPoints = new ArrayList<OpenPointInput>();
		public final List<CompletenessRow> completeness = new ArrayList<CompletenessRow>();
		/** UX lot U3: {@link #completeness} grouped by master object, each group scoped (see {@link CompletenessGroup}). */
		public final List<CompletenessGroup> completenessGroups = new ArrayList<CompletenessGroup>();
		public boolean completenessMasterFallback;
		public final List<JournalRow> journal = new ArrayList<JournalRow>();
	}

	// ----------------------------------------------------------------------------------------------------- compute

	private static final String[] SOURCE_COLORS = { "#175cd3", "#0e9384", "#b54708", "#6941c6", "#c11574", "#026aa2", "#a15c07", "#3538cd" };
	private static final String MASTER_COLOR = "#344054";
	private static final String GOLDEN_COLOR = "#684bc0"; // theme-fixed (audit F-44): the golden legend colour, SourceColorPalette.GOLDEN

	private static final int SVG_WIDTH = 1162;
	private static final int SVG_HEIGHT = 250;
	private static final double PLOT_TOP = 22;
	private static final double PLOT_BOTTOM = 214.5;
	private static final double NODE_W = 14;
	private static final double MIN_H = 8;
	private static final double X_SOURCE = 120;
	private static final double X_LANDING = 390;
	private static final double X_STAGING = 640;
	private static final double X_MASTER = 890;
	private static final double X_GOLDEN = 1060;

	public static Output compute(final Input pIn)
	{
		final Output out = new Output();
		out.journey = WizardSummaryData.journey(pIn);
		out.zones.addAll(WizardSummaryData.zones(pIn));
		out.openPoints.addAll(WizardSummaryData.orderedOpenPoints(pIn));
		out.openPointCount = out.openPoints.size();
		out.verdict = WizardSummaryData.verdict(pIn, out.openPoints);
		out.completeness.addAll(WizardSummaryData.completeness(pIn));
		out.completenessGroups.addAll(WizardSummaryData.completenessGroups(pIn));
		out.completenessMasterFallback = pIn.completenessMasterFallback;
		out.journal.addAll(WizardSummaryData.journal(pIn));
		return out;
	}

	/** Operational: every zone loaded, no ERROR-severity open point. Blocked: no project/source data at all (nothing loaded past sources). Otherwise not ready. */
	private static String verdict(final Input pIn, final List<OpenPointInput> pOrderedPoints)
	{
		boolean hasError = false;
		for (final OpenPointInput point : pOrderedPoints)
		{
			if ("ERROR".equals(point.severity))
			{
				hasError = true;
				break;
			}
		}
		final boolean everyZoneLoaded = pIn.landingLoaded && !pIn.stagingEntities.isEmpty() && !pIn.masterEntities.isEmpty();
		if (everyZoneLoaded && !hasError)
		{
			return "OPERATIONAL";
		}
		if (!pIn.landingLoaded && pIn.stagingEntities.isEmpty() && pIn.masterEntities.isEmpty())
		{
			return "BLOCKED";
		}
		return "NOT_READY";
	}

	/**
	 * The open points of {@link Input#openPoints} plus, depending on the automatic intake's state, the two intake-related ones: the "no schedule" INFO point
	 * unless the intake is ON (ON means the intake IS the schedule; OFF, unknown and PAUSED leave the chain launched by hand, so it stays true), and the
	 * intake-paused WARN point only when PAUSED.
	 */
	static List<OpenPointInput> withIntake(final Input pIn)
	{
		final List<OpenPointInput> all = new ArrayList<OpenPointInput>(pIn.openPoints);
		final boolean on = "ON".equals(pIn.intakeState);
		final boolean paused = "PAUSED".equals(pIn.intakeState);
		if (pIn.noSchedulePoint != null && !on)
		{
			all.add(pIn.noSchedulePoint);
		}
		if (pIn.intakePausedPoint != null && paused)
		{
			all.add(pIn.intakePausedPoint);
		}
		return all;
	}

	private static List<OpenPointInput> orderedOpenPoints(final Input pIn)
	{
		final List<OpenPointInput> errors = new ArrayList<OpenPointInput>();
		final List<OpenPointInput> warns = new ArrayList<OpenPointInput>();
		final List<OpenPointInput> infos = new ArrayList<OpenPointInput>();
		final List<OpenPointInput> every = WizardSummaryData.withIntake(pIn);
		for (final OpenPointInput point : every)
		{
			if ("ERROR".equals(point.severity))
			{
				errors.add(point);
			}
			else if ("WARN".equals(point.severity))
			{
				warns.add(point);
			}
			else
			{
				infos.add(point);
			}
		}
		final List<OpenPointInput> out = new ArrayList<OpenPointInput>(every.size());
		out.addAll(errors);
		out.addAll(warns);
		out.addAll(infos);
		return out;
	}

	// --------------------------------------------------------------------------------------------------------- zones

	private static List<ZoneCard> zones(final Input pIn)
	{
		final List<ZoneCard> out = new ArrayList<ZoneCard>(4);

		final ZoneCard landing = new ZoneCard("LANDING", pIn.landingLoaded ? "good" : "muted", pIn.landingDataspace, pIn.landingOpenUrl, 0);
		landing.facts.add(String.valueOf(pIn.landingTableCount));
		if (pIn.landingOtherTableCount > 0)
		{
			landing.facts.add(String.valueOf(pIn.landingOtherTableCount));
		}
		out.add(landing);

		final ZoneCard staging = new ZoneCard("STAGING", pIn.stagingEntities.isEmpty() ? "muted" : "good", pIn.stagingDataspace, pIn.stagingOpenUrl, 0);
		staging.facts.add(String.valueOf(pIn.stagingEntities.size()));
		staging.facts.add(pIn.stagingFeedCount + "/" + pIn.stagingMappingCount);
		staging.facts.add(pIn.qualityRulesAccepted + "/" + pIn.constraintsApplied);
		out.add(staging);

		// UX lot U3: the master's figure is its rows; golden records are a separate figure that exists only once a matching run produced them (the card
		// used to show 30 - the pivots of the last Personne run - as "30 records" of a master that held 194)
		final ZoneCard master = new ZoneCard("MASTER", pIn.masterEntities.isEmpty() ? "muted" : "good", pIn.masterDataspace, pIn.masterOpenUrl, 0);
		master.facts.add(String.valueOf(WizardSummaryData.sumRows(pIn.masterEntities)));
		if (WizardSummaryData.anyRunOk(pIn) && pIn.matchingAvailable)
		{
			master.facts.add(String.valueOf(pIn.goldenCount));
		}
		final boolean goldenShown = WizardSummaryData.anyRunOk(pIn) && pIn.matchingAvailable;
		for (final ZoneEntityInput entity : pIn.masterEntities)
		{
			final boolean main = goldenShown && entity.golden != null && entity.name.equals(pIn.masterMainEntity);
			master.lines.add(new ZoneLine(entity.name, entity.rows == null ? 0L : entity.rows.longValue(), main ? entity.golden : null, main ? (entity.merged == null ? Long.valueOf(0L) : entity.merged) : null));
		}
		out.add(master);

		final boolean anyFailed = !WizardSummaryData.failedRuns(pIn).isEmpty();
		final int objects = pIn.matchObjects.isEmpty() ? pIn.matchPlansTotal : pIn.matchObjects.size();
		final int applied = pIn.matchObjects.isEmpty() ? pIn.matchPlansApplied : WizardSummaryData.plansWith(pIn, "APPLIED");
		final ZoneCard matching = new ZoneCard("MATCHING", anyFailed ? "failed" : pIn.pendingSuspects > 0 ? "warn" : (applied > 0 ? "good" : "muted"), "", "",
			objects > 0 ? WizardRules.STEP_MATCHING : 0);
		matching.facts.add(applied + "/" + objects);
		matching.facts.add(String.valueOf(pIn.pendingSuspects));
		for (final MatchObjectInput object : pIn.matchObjects)
		{
			matching.details.add(new ZoneDetail(object.name, object.hasPlan() ? object.planStatus : "NONE",
				object.runStatus.isEmpty() ? "NOT_RUN" : "DONE".equals(object.runStatus) || "FAILED".equals(object.runStatus) ? object.runStatus : "RUNNING"));
		}
		out.add(matching);

		return out;
	}

	private static boolean anyRunOk(final Input pIn)
	{
		for (final MatchObjectInput object : pIn.matchObjects)
		{
			if (object.ranOk())
			{
				return true;
			}
		}
		return false;
	}

	private static int plansWith(final Input pIn, final String pStatus)
	{
		int count = 0;
		for (final MatchObjectInput object : pIn.matchObjects)
		{
			if (pStatus.equals(object.planStatus))
			{
				count++;
			}
		}
		return count;
	}

	/** The master objects whose last matching run FAILED, in order - each is an open point of the summary (the Matching card used to say "2 plans applied out of 2" after a failed run). */
	public static List<String> failedRuns(final Input pIn)
	{
		final List<String> out = new ArrayList<String>();
		for (final MatchObjectInput object : pIn.matchObjects)
		{
			if ("FAILED".equals(object.runStatus))
			{
				out.add(object.name);
			}
		}
		return out;
	}

	/**
	 * The master objects with no usable plan, in order - but only when some other object has one: a project that never configured matching has nothing
	 * "missing", a project that configured it for two objects out of three has a gap worth listing.
	 */
	public static List<String> withoutPlan(final Input pIn)
	{
		final List<String> out = new ArrayList<String>();
		boolean anyPlan = false;
		for (final MatchObjectInput object : pIn.matchObjects)
		{
			anyPlan |= object.hasPlan();
		}
		if (!anyPlan)
		{
			return out;
		}
		for (final MatchObjectInput object : pIn.matchObjects)
		{
			if (!object.hasPlan())
			{
				out.add(object.name);
			}
		}
		return out;
	}

	private static long sumRows(final List<ZoneEntityInput> pEntities)
	{
		long total = 0;
		for (final ZoneEntityInput entity : pEntities)
		{
			if (entity.rows != null)
			{
				total += entity.rows.longValue();
			}
		}
		return total;
	}

	// ------------------------------------------------------------------------------------------------ completeness

	private static List<CompletenessRow> completeness(final Input pIn)
	{
		final List<CompletenessRow> out = new ArrayList<CompletenessRow>(pIn.completeness.size());
		for (final CompletenessInput row : pIn.completeness)
		{
			final int pct = row.total <= 0 ? 0 : (int) Math.round(row.filled * 100.0 / row.total);
			out.add(new CompletenessRow(row.attributeLabel, pct));
		}
		return out;
	}

	/**
	 * UX lot U3: the fill rates grouped by master object, in the order the objects first appear. A group is "matched" when its object's last matching run
	 * is DONE (the percentages are measured on its golden records, {@link CompletenessGroup#scopeCount} of them); otherwise it carries no row at all - the
	 * renderer says "not matched yet". A caller that did not read the objects ({@link Input#matchObjects} empty) gets the old behaviour: matched unless the
	 * whole completeness is the master fallback.
	 */
	private static List<CompletenessGroup> completenessGroups(final Input pIn)
	{
		final List<CompletenessGroup> out = new ArrayList<CompletenessGroup>();
		final java.util.Map<String, CompletenessGroup> byName = new java.util.LinkedHashMap<String, CompletenessGroup>();
		for (final CompletenessInput row : pIn.completeness)
		{
			CompletenessGroup group = byName.get(row.entityName);
			if (group == null)
			{
				boolean matched = !pIn.completenessMasterFallback;
				if (!pIn.matchObjects.isEmpty())
				{
					matched = false;
					for (final MatchObjectInput object : pIn.matchObjects)
					{
						if (object.name.equals(row.entityName) && object.ranOk())
						{
							matched = true;
						}
					}
				}
				// lot S7: a follower without a plan is matched when its own table holds golden records (the cascade merge's golden copies)
				final Long followerGolden = pIn.followerGolden.get(row.entityName);
				if (followerGolden != null)
				{
					matched = followerGolden.longValue() > 0;
				}
				long scope = 0;
				for (final CompletenessInput other : pIn.completeness)
				{
					if (other.entityName.equals(row.entityName))
					{
						scope = Math.max(scope, other.total);
					}
				}
				group = new CompletenessGroup(row.entityName, matched, scope);
				byName.put(row.entityName, group);
				out.add(group);
			}
			if (group.matched)
			{
				final int pct = row.total <= 0 ? 0 : (int) Math.round(row.filled * 100.0 / row.total);
				group.rows.add(new CompletenessRow(row.attribute.isEmpty() ? row.attributeLabel : row.attribute, pct));
			}
		}
		return out;
	}

	// ----------------------------------------------------------------------------------------------------- journal

	private static List<JournalRow> journal(final Input pIn)
	{
		long maxSeconds = 1;
		for (final JournalStepInput step : pIn.journal)
		{
			if (step.seconds > maxSeconds)
			{
				maxSeconds = step.seconds;
			}
		}
		final List<JournalRow> out = new ArrayList<JournalRow>(pIn.journal.size());
		for (final JournalStepInput step : pIn.journal)
		{
			final int pct = (int) Math.max(2, Math.round(step.seconds * 100.0 / maxSeconds));
			out.add(new JournalRow(step.stepLabel, WizardSummaryData.durationText(step.seconds), step.rowsText, pct));
		}
		return out;
	}

	private static String durationText(final long pSeconds)
	{
		if (pSeconds >= 60)
		{
			final long minutes = pSeconds / 60;
			final long rest = pSeconds % 60;
			return minutes + " min " + (rest < 10 ? "0" + rest : String.valueOf(rest));
		}
		return pSeconds + " s";
	}

	// ----------------------------------------------------------------------------------------------------- journey

	private static String bandPath(final double pX1, final double pX2, final double pY0a, final double pY1a, final double pY0b, final double pY1b)
	{
		final double mid = (pX1 + pX2) / 2;
		final StringBuilder sb = new StringBuilder(96);
		sb.append("M ").append(WizardSummaryData.f(pX1)).append(' ').append(WizardSummaryData.f(pY0a)).append(" C ").append(WizardSummaryData.f(mid)).append(' ')
			.append(WizardSummaryData.f(pY0a)).append(' ').append(WizardSummaryData.f(mid)).append(' ').append(WizardSummaryData.f(pY0b)).append(' ').append(WizardSummaryData.f(pX2))
			.append(' ').append(WizardSummaryData.f(pY0b)).append(" L ").append(WizardSummaryData.f(pX2)).append(' ').append(WizardSummaryData.f(pY1b)).append(" C ")
			.append(WizardSummaryData.f(mid)).append(' ').append(WizardSummaryData.f(pY1b)).append(' ').append(WizardSummaryData.f(mid)).append(' ').append(WizardSummaryData.f(pY1a))
			.append(' ').append(WizardSummaryData.f(pX1)).append(' ').append(WizardSummaryData.f(pY1a)).append(" Z");
		return sb.toString();
	}

	private static String f(final double pValue)
	{
		final double rounded = Math.round(pValue * 10.0) / 10.0;
		if (rounded == Math.floor(rounded))
		{
			return String.valueOf((long) rounded);
		}
		return String.valueOf(rounded);
	}

	/**
	 * One band per source, carried at its own row-share through Landing and Staging (three hops), then a
	 * SINGLE aggregate band Master to Golden - see the class Javadoc. Works for zero sources (an empty
	 * journey, every node greyed at {@link #MIN_H}) up to as many as the project has.
	 */
	private static Journey journey(final Input pIn)
	{
		final Journey out = new Journey(WizardSummaryData.SVG_WIDTH, WizardSummaryData.SVG_HEIGHT);
		final int n = pIn.sources.size();
		final double plotH = WizardSummaryData.PLOT_BOTTOM - WizardSummaryData.PLOT_TOP;
		long totalSourceRows = 0;
		for (final SourceInput source : pIn.sources)
		{
			totalSourceRows += Math.max(0, source.receivedRows);
		}
		final boolean anyRows = totalSourceRows > 0;
		final double gap = n > 1 ? Math.min(8, plotH / (n * 4.0)) : 0;
		final double usable = Math.max(1, plotH - gap * Math.max(0, n - 1));
		final double scale = anyRows ? usable / totalSourceRows : 0;

		// per-source bands, same y-range carried through the three hops (source -> landing -> staging)
		double cursor = WizardSummaryData.PLOT_TOP;
		final double[] y0 = new double[n];
		final double[] y1 = new double[n];
		for (int i = 0; i < n; i++)
		{
			final SourceInput source = pIn.sources.get(i);
			final double h = anyRows ? Math.max(WizardSummaryData.MIN_H, source.receivedRows * scale) : usable / Math.max(1, n);
			y0[i] = cursor;
			y1[i] = cursor + h;
			cursor += h + gap;
			final String color = WizardSummaryData.SOURCE_COLORS[i % WizardSummaryData.SOURCE_COLORS.length];
			out.nodes.add(new JourneyNode(WizardSummaryData.X_SOURCE, y0[i], WizardSummaryData.NODE_W, h, source.name, String.valueOf(source.receivedRows), color, !anyRows));
			out.flows.add(new JourneyFlow(WizardSummaryData.bandPath(WizardSummaryData.X_SOURCE + WizardSummaryData.NODE_W, WizardSummaryData.X_LANDING, y0[i], y1[i], y0[i], y1[i]),
				color));
			out.flows
				.add(new JourneyFlow(WizardSummaryData.bandPath(WizardSummaryData.X_LANDING + WizardSummaryData.NODE_W, WizardSummaryData.X_STAGING, y0[i], y1[i], y0[i], y1[i]), color));
			out.flows
				.add(new JourneyFlow(WizardSummaryData.bandPath(WizardSummaryData.X_STAGING + WizardSummaryData.NODE_W, WizardSummaryData.X_MASTER, y0[i], y1[i], y0[i], y1[i]), color));
		}
		final double landingTop = n > 0 ? y0[0] : WizardSummaryData.PLOT_TOP;
		final double landingBottom = n > 0 ? y1[n - 1] : WizardSummaryData.PLOT_TOP + WizardSummaryData.MIN_H;
		out.nodes.add(new JourneyNode(WizardSummaryData.X_LANDING, landingTop, WizardSummaryData.NODE_W, landingBottom - landingTop, "", String.valueOf(totalSourceRows),
			WizardSummaryData.MASTER_COLOR, !pIn.landingLoaded));
		final long stagingRows = WizardSummaryData.sumRows(pIn.stagingEntities);
		final boolean stagingLoaded = !pIn.stagingEntities.isEmpty();
		out.nodes.add(new JourneyNode(WizardSummaryData.X_STAGING, landingTop, WizardSummaryData.NODE_W, landingBottom - landingTop, "", String.valueOf(stagingRows),
			WizardSummaryData.MASTER_COLOR, !stagingLoaded));
		final long masterRows = WizardSummaryData.sumRows(pIn.masterEntities);
		final boolean masterLoaded = !pIn.masterEntities.isEmpty();
		out.nodes.add(new JourneyNode(WizardSummaryData.X_MASTER, landingTop, WizardSummaryData.NODE_W, landingBottom - landingTop, "", String.valueOf(masterRows),
			WizardSummaryData.MASTER_COLOR, !masterLoaded));

		final boolean goldenLoaded = masterLoaded && pIn.matchingAvailable && pIn.goldenCount > 0;
		final double goldenH = goldenLoaded ? Math.max(WizardSummaryData.MIN_H, landingBottom - landingTop) : WizardSummaryData.MIN_H * 2;
		final double goldenTop = (WizardSummaryData.PLOT_TOP + WizardSummaryData.PLOT_BOTTOM - goldenH) / 2;
		out.nodes.add(new JourneyNode(WizardSummaryData.X_GOLDEN, goldenTop, WizardSummaryData.NODE_W, goldenH, "", String.valueOf(pIn.goldenCount), WizardSummaryData.GOLDEN_COLOR,
			!goldenLoaded));
		out.flows.add(new JourneyFlow(
			WizardSummaryData.bandPath(WizardSummaryData.X_MASTER + WizardSummaryData.NODE_W, WizardSummaryData.X_GOLDEN, landingTop, landingBottom, goldenTop, goldenTop + goldenH),
			WizardSummaryData.GOLDEN_COLOR));

		return out;
	}
}
