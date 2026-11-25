package com.on.ebx.factory.wizard;

/**
 * Pure-logic tests for {@link WizardSummaryData} - no EBX class anywhere, run by {@code tools/test_local.sh}
 * (JUnit-free "main throws on failure" convention this codebase uses throughout, see {@code
 * WizardPageTest}/{@code WizardRulesTest} for the same shape).
 */
public final class WizardSummaryDataTest
{
	private WizardSummaryDataTest()
	{
	}

	public static void main(final String[] pArgs)
	{
		WizardSummaryDataTest.verdictBlockedWhenNothingLoaded();
		WizardSummaryDataTest.verdictNotReadyWhenPartial();
		WizardSummaryDataTest.verdictOperationalWhenEveryZoneLoadedAndNoError();
		WizardSummaryDataTest.verdictNotOperationalWithAnError();
		WizardSummaryDataTest.openPointsOrderedBySeverity();
		WizardSummaryDataTest.targetNotArmedOpenPointCarriesTargetIdAndOrdersAsWarn();
		WizardSummaryDataTest.distributionTargetsAndProductPassThroughUnchanged();
		WizardSummaryDataTest.noSchedulePointIsHiddenOnlyWhileTheIntakeIsOn();
		WizardSummaryDataTest.pausedIntakeAddsItsWarnPointCarryingTheReason();
		WizardSummaryDataTest.intakePointsAreIgnoredWhenNotSet();
		WizardSummaryDataTest.completenessPercentages();
		WizardSummaryDataTest.journalBarsProportional();
		WizardSummaryDataTest.landingCountsTheKeptAndLoadedTablesAndNotesTheOthers();
		WizardSummaryDataTest.masterCountsRowsAndShowsGoldenRecordsOnlyAfterAMatchingRun();
		WizardSummaryDataTest.matchingCardSaysPlansOfObjectsAndTheLastRunOfEach();
		WizardSummaryDataTest.aFailedRunAndAnObjectWithoutPlanAreListed();
		WizardSummaryDataTest.completenessIsScopedPerObjectAndUnmatchedHasNoBar();
		WizardSummaryDataTest.completenessWithoutObjectsKeepsTheOldBehaviour();
		WizardSummaryDataTest.journeyGeometryForOneSource();
		WizardSummaryDataTest.journeyGeometryForEightSources();
		WizardSummaryDataTest.journeyHandlesZeroSourcesWithoutCrashing();
		System.out.println("WizardSummaryDataTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static WizardSummaryData.Input baseInput()
	{
		final WizardSummaryData.Input in = new WizardSummaryData.Input();
		in.projectLabel = "Demo";
		return in;
	}

	private static void verdictBlockedWhenNothingLoaded()
	{
		final WizardSummaryData.Output out = WizardSummaryData.compute(WizardSummaryDataTest.baseInput());
		WizardSummaryDataTest.check("BLOCKED when nothing loaded", "BLOCKED".equals(out.verdict));
	}

	private static void verdictNotReadyWhenPartial()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		in.landingLoaded = true;
		final WizardSummaryData.Output out = WizardSummaryData.compute(in);
		WizardSummaryDataTest.check("NOT_READY once landing loaded but staging/master empty", "NOT_READY".equals(out.verdict));
	}

	private static void verdictOperationalWhenEveryZoneLoadedAndNoError()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		in.landingLoaded = true;
		in.stagingEntities.add(WizardSummaryDataTest.zoneEntity("Contact", 10L));
		in.masterEntities.add(WizardSummaryDataTest.zoneEntity("Contact", 10L));
		final WizardSummaryData.Output out = WizardSummaryData.compute(in);
		WizardSummaryDataTest.check("OPERATIONAL when every zone loaded, no error", "OPERATIONAL".equals(out.verdict));
	}

	private static void verdictNotOperationalWithAnError()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		in.landingLoaded = true;
		in.stagingEntities.add(WizardSummaryDataTest.zoneEntity("Contact", 10L));
		in.masterEntities.add(WizardSummaryDataTest.zoneEntity("Contact", 10L));
		in.openPoints.add(new WizardSummaryData.OpenPointInput("ERROR", "title", "detail", "action", 3, ""));
		final WizardSummaryData.Output out = WizardSummaryData.compute(in);
		WizardSummaryDataTest.check("an ERROR open point keeps it out of OPERATIONAL", !"OPERATIONAL".equals(out.verdict));
	}

	private static void openPointsOrderedBySeverity()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		in.openPoints.add(new WizardSummaryData.OpenPointInput("INFO", "info", "", "", 0, ""));
		in.openPoints.add(new WizardSummaryData.OpenPointInput("ERROR", "error", "", "", 0, ""));
		in.openPoints.add(new WizardSummaryData.OpenPointInput("WARN", "warn", "", "", 0, ""));
		final WizardSummaryData.Output out = WizardSummaryData.compute(in);
		WizardSummaryDataTest.check("3 open points", out.openPointCount == 3);
		WizardSummaryDataTest.check("errors first", "error".equals(out.openPoints.get(0).title));
		WizardSummaryDataTest.check("warns second", "warn".equals(out.openPoints.get(1).title));
		WizardSummaryDataTest.check("infos last", "info".equals(out.openPoints.get(2).title));
	}

	/**
	 * Coordinator review (2026-09-24, distribution-wizard lot): a "&lt;target&gt; not armed" open point (board
	 * {@code SummaryDistribution.dc.html}) carries its target's id ({@link WizardSummaryData.OpenPointInput#targetId})
	 * through {@link WizardSummaryData#compute} unchanged, and orders as WARN like any other WARN point (the
	 * caller, {@code ProjectWizardUserService#buildSummary}, always uses WARN for it - this class does not
	 * decide severity, only orders/counts what it is given).
	 */
	private static void targetNotArmedOpenPointCarriesTargetIdAndOrdersAsWarn()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		in.openPoints.add(new WizardSummaryData.OpenPointInput("ERROR", "error", "", "", 0, ""));
		in.openPoints.add(new WizardSummaryData.OpenPointInput("WARN", "Salesforce CRM not armed", "Real time starts after the first full send.", "Run it",
			WizardRules.STEP_DISTRIBUTION, "", "42"));
		final WizardSummaryData.Output out = WizardSummaryData.compute(in);
		WizardSummaryDataTest.check("2 open points", out.openPointCount == 2);
		WizardSummaryDataTest.check("error still first", "error".equals(out.openPoints.get(0).title));
		final WizardSummaryData.OpenPointInput notArmed = out.openPoints.get(1);
		WizardSummaryDataTest.check("the not-armed point is second (WARN)", "Salesforce CRM not armed".equals(notArmed.title));
		WizardSummaryDataTest.check("its targetId survives compute() unchanged", "42".equals(notArmed.targetId));
		WizardSummaryDataTest.check("its gotoStep is STEP_DISTRIBUTION", notArmed.gotoStep == WizardRules.STEP_DISTRIBUTION);
		WizardSummaryDataTest.check("the 6-arg constructor still defaults targetId to empty (no accidental combined button elsewhere)",
			out.openPoints.get(0).targetId.isEmpty());
	}

	/** {@link WizardSummaryData.Input#distributionTargets}/{@code distributionProductExists} are plain pass-through fields (no computation) - {@code WizardPage#renderProductionCards} reads {@code Model.summaryInput} directly, never {@code Output}; this test only pins that {@code compute()} does not need them and does not clear/reset them. */
	private static void distributionTargetsAndProductPassThroughUnchanged()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		final WizardSummaryData.DistributionSummaryTarget target = new WizardSummaryData.DistributionSummaryTarget();
		target.label = "Kafka";
		target.mode = "SCHEDULED";
		target.state = "WAITING";
		in.distributionTargets.add(target);
		in.distributionProductExists = true;
		in.distributionProductLabel = "Person 360";
		in.distributionProductRefreshMode = "DAILY";
		WizardSummaryData.compute(in); // must not throw and must not mutate the input
		WizardSummaryDataTest.check("distribution target list untouched by compute()", in.distributionTargets.size() == 1);
		WizardSummaryDataTest.check("distribution target label untouched", "Kafka".equals(in.distributionTargets.get(0).label));
		WizardSummaryDataTest.check("product fields untouched", in.distributionProductExists && "Person 360".equals(in.distributionProductLabel)
			&& "DAILY".equals(in.distributionProductRefreshMode));
	}

	/** Automatic-intake wizard lot (2026-10-05): the fixed "no schedule" INFO point is not shown when the intake IS the automation; OFF, unknown (not read) and PAUSED keep it. */
	private static void noSchedulePointIsHiddenOnlyWhileTheIntakeIsOn()
	{
		for (final String state : new String[] { "", "OFF", "PAUSED", "ON" })
		{
			final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
			in.intakeState = state;
			in.noSchedulePoint = new WizardSummaryData.OpenPointInput("INFO", "No schedule configured", "Loads only run when someone launches them.", "Schedule...", 0, "");
			final WizardSummaryData.Output out = WizardSummaryData.compute(in);
			final boolean shown = out.openPointCount == 1 && "No schedule configured".equals(out.openPoints.get(0).title);
			WizardSummaryDataTest.check("state [" + state + "]: the no-schedule point is " + ("ON".equals(state) ? "hidden" : "shown"), "ON".equals(state) ? out.openPointCount == 0 : shown);
		}
	}

	/** PAUSED adds the WARN point carrying the (already worded) pause reason, ordered as a WARN; ON and OFF never show it. */
	private static void pausedIntakeAddsItsWarnPointCarryingTheReason()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		in.intakeState = "PAUSED";
		in.openPoints.add(new WizardSummaryData.OpenPointInput("INFO", "info", "", "", 0, ""));
		in.openPoints.add(new WizardSummaryData.OpenPointInput("ERROR", "error", "", "", 0, ""));
		in.intakePausedPoint = new WizardSummaryData.OpenPointInput("WARN", "Automatic intake is paused", "Reason: stopped: 6 % of the rows held back.", "", 0, "");
		final WizardSummaryData.Output out = WizardSummaryData.compute(in);
		WizardSummaryDataTest.check("error, paused (WARN), info", out.openPointCount == 3 && "error".equals(out.openPoints.get(0).title)
			&& "Automatic intake is paused".equals(out.openPoints.get(1).title) && "info".equals(out.openPoints.get(2).title));
		WizardSummaryDataTest.check("the pause reason is carried", out.openPoints.get(1).detail.contains("6 % of the rows held back"));
		for (final String state : new String[] { "ON", "OFF", "" })
		{
			in.intakeState = state;
			WizardSummaryDataTest.check("no paused point in state [" + state + "]", WizardSummaryData.compute(in).openPointCount == 2);
		}
	}

	/** A caller that sets nothing (no intake read, an older fixture) gets exactly the points it passed. */
	private static void intakePointsAreIgnoredWhenNotSet()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		in.intakeState = "PAUSED";
		in.openPoints.add(new WizardSummaryData.OpenPointInput("INFO", "info", "", "", 0, ""));
		WizardSummaryDataTest.check("only the passed point", WizardSummaryData.compute(in).openPointCount == 1);
	}

	private static void completenessPercentages()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		final WizardSummaryData.CompletenessInput full = new WizardSummaryData.CompletenessInput();
		full.attributeLabel = "Name";
		full.filled = 10;
		full.total = 10;
		in.completeness.add(full);
		final WizardSummaryData.CompletenessInput half = new WizardSummaryData.CompletenessInput();
		half.attributeLabel = "Email";
		half.filled = 5;
		half.total = 10;
		in.completeness.add(half);
		final WizardSummaryData.CompletenessInput empty = new WizardSummaryData.CompletenessInput();
		empty.attributeLabel = "Fax";
		empty.filled = 0;
		empty.total = 0;
		in.completeness.add(empty);
		final WizardSummaryData.Output out = WizardSummaryData.compute(in);
		WizardSummaryDataTest.check("100%", out.completeness.get(0).pct == 100);
		WizardSummaryDataTest.check("50%", out.completeness.get(1).pct == 50);
		WizardSummaryDataTest.check("0 total -> 0%, never a division error", out.completeness.get(2).pct == 0);
	}

	// ------------------------------------------------------------------------------------------ UX lot U3 (2026-10-13), project UxCrmSf

	private static WizardSummaryData.ZoneCard zone(final WizardSummaryData.Output pOut, final String pKey)
	{
		for (final WizardSummaryData.ZoneCard card : pOut.zones)
		{
			if (pKey.equals(card.zoneKey))
			{
				return card;
			}
		}
		throw new AssertionError("no zone " + pKey);
	}

	private static void landingCountsTheKeptAndLoadedTablesAndNotesTheOthers()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		in.landingLoaded = true;
		in.landingTableCount = 4; // ticked and loaded - the caller no longer counts the 15 unticked ones
		in.landingOtherTableCount = 15;
		final WizardSummaryData.ZoneCard card = WizardSummaryDataTest.zone(WizardSummaryData.compute(in), "LANDING");
		WizardSummaryDataTest.check("the count is the 4 kept tables", "4".equals(card.facts.get(0)));
		WizardSummaryDataTest.check("the 15 others are a side note", card.facts.size() == 2 && "15".equals(card.facts.get(1)));
		in.landingOtherTableCount = 0;
		WizardSummaryDataTest.check("no side note when every table found was kept", WizardSummaryDataTest.zone(WizardSummaryData.compute(in), "LANDING").facts.size() == 1);
	}

	private static void masterCountsRowsAndShowsGoldenRecordsOnlyAfterAMatchingRun()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		in.masterEntities.add(WizardSummaryDataTest.zoneEntity("Person", 120L));
		in.masterEntities.add(WizardSummaryDataTest.zoneEntity("Organisation", 62L));
		in.masterEntities.add(WizardSummaryDataTest.zoneEntity("Address", 12L));
		in.matchingAvailable = true;
		in.goldenCount = 30;
		in.matchObjects.add(new WizardSummaryData.MatchObjectInput("Person", "APPLIED", ""));
		WizardSummaryDataTest.check("no run yet: the master is its 194 rows, no golden figure",
			WizardSummaryDataTest.zone(WizardSummaryData.compute(in), "MASTER").facts.size() == 1 && "194".equals(WizardSummaryDataTest.zone(WizardSummaryData.compute(in), "MASTER").facts.get(0)));
		in.matchObjects.clear();
		in.matchObjects.add(new WizardSummaryData.MatchObjectInput("Person", "APPLIED", "DONE"));
		final WizardSummaryData.ZoneCard master = WizardSummaryDataTest.zone(WizardSummaryData.compute(in), "MASTER");
		WizardSummaryDataTest.check("after a run: the rows stay the first figure, the golden records a separate one",
			master.facts.size() == 2 && "194".equals(master.facts.get(0)) && "30".equals(master.facts.get(1)));
		in.matchingAvailable = false;
		WizardSummaryDataTest.check("no golden figure without matching on the master", WizardSummaryDataTest.zone(WizardSummaryData.compute(in), "MASTER").facts.size() == 1);
	}

	private static WizardSummaryData.Input uxCrmSf()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		in.masterEntities.add(WizardSummaryDataTest.zoneEntity("Person", 120L));
		in.masterEntities.add(WizardSummaryDataTest.zoneEntity("Address", 12L));
		in.masterEntities.add(WizardSummaryDataTest.zoneEntity("Organisation", 62L));
		in.matchObjects.add(new WizardSummaryData.MatchObjectInput("Person", "APPLIED", "DONE"));
		in.matchObjects.add(new WizardSummaryData.MatchObjectInput("Address", "APPLIED", "FAILED"));
		in.matchObjects.add(new WizardSummaryData.MatchObjectInput("Organisation", "", ""));
		in.matchPlansApplied = 2;
		in.matchPlansTotal = 2;
		return in;
	}

	private static void matchingCardSaysPlansOfObjectsAndTheLastRunOfEach()
	{
		final WizardSummaryData.ZoneCard card = WizardSummaryDataTest.zone(WizardSummaryData.compute(WizardSummaryDataTest.uxCrmSf()), "MATCHING");
		WizardSummaryDataTest.check("2 plans applied out of the 3 business objects, not 'out of 2': " + card.facts, "2/3".equals(card.facts.get(0)));
		WizardSummaryDataTest.check("one line per object", card.details.size() == 3);
		WizardSummaryDataTest.check("Person: plan applied, run done", "APPLIED".equals(card.details.get(0).plan) && "DONE".equals(card.details.get(0).run));
		WizardSummaryDataTest.check("Address: run failed", "FAILED".equals(card.details.get(1).run));
		WizardSummaryDataTest.check("Organisation: no plan, not run", "NONE".equals(card.details.get(2).plan) && "NOT_RUN".equals(card.details.get(2).run));
		WizardSummaryDataTest.check("a failed run turns the card to failed, never 'good'", "failed".equals(card.statusKind));
		final WizardSummaryData.Input running = WizardSummaryDataTest.baseInput();
		running.matchObjects.add(new WizardSummaryData.MatchObjectInput("Person", "ACCEPTED", "RUNNING"));
		final WizardSummaryData.ZoneCard runningCard = WizardSummaryDataTest.zone(WizardSummaryData.compute(running), "MATCHING");
		WizardSummaryDataTest.check("a plan accepted but not applied is not counted as applied; a run in progress reads RUNNING",
			"0/1".equals(runningCard.facts.get(0)) && "ACCEPTED".equals(runningCard.details.get(0).plan) && "RUNNING".equals(runningCard.details.get(0).run));
		// callers that did not read the objects keep the old "applied out of plans" card
		final WizardSummaryData.Input legacy = WizardSummaryDataTest.baseInput();
		legacy.matchPlansApplied = 1;
		legacy.matchPlansTotal = 2;
		final WizardSummaryData.ZoneCard legacyCard = WizardSummaryDataTest.zone(WizardSummaryData.compute(legacy), "MATCHING");
		WizardSummaryDataTest.check("fallback", "1/2".equals(legacyCard.facts.get(0)) && legacyCard.details.isEmpty());
	}

	private static void aFailedRunAndAnObjectWithoutPlanAreListed()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.uxCrmSf();
		WizardSummaryDataTest.check("the failed run: Address", WizardSummaryData.failedRuns(in).size() == 1 && "Address".equals(WizardSummaryData.failedRuns(in).get(0)));
		WizardSummaryDataTest.check("the object without plan: Organisation", WizardSummaryData.withoutPlan(in).size() == 1 && "Organisation".equals(WizardSummaryData.withoutPlan(in).get(0)));
		// matching never configured: nothing is "missing", every object would only be noise
		final WizardSummaryData.Input none = WizardSummaryDataTest.baseInput();
		none.matchObjects.add(new WizardSummaryData.MatchObjectInput("Person", "", ""));
		none.matchObjects.add(new WizardSummaryData.MatchObjectInput("Address", "REJECTED", ""));
		WizardSummaryDataTest.check("no plan anywhere: no gap listed", WizardSummaryData.withoutPlan(none).isEmpty() && WizardSummaryData.failedRuns(none).isEmpty());
	}

	private static WizardSummaryData.CompletenessInput fill(final String pEntity, final String pAttribute, final int pFilled, final int pTotal)
	{
		final WizardSummaryData.CompletenessInput row = new WizardSummaryData.CompletenessInput();
		row.entityName = pEntity;
		row.attribute = pAttribute;
		row.attributeLabel = pEntity + " - " + pAttribute;
		row.filled = pFilled;
		row.total = pTotal;
		return row;
	}

	private static void completenessIsScopedPerObjectAndUnmatchedHasNoBar()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.uxCrmSf();
		in.completeness.add(WizardSummaryDataTest.fill("Person", "name", 30, 30));
		in.completeness.add(WizardSummaryDataTest.fill("Person", "email", 15, 30));
		in.completeness.add(WizardSummaryDataTest.fill("Organisation", "name", 0, 0)); // what the reader gives an object with no golden record
		final WizardSummaryData.Output out = WizardSummaryData.compute(in);
		WizardSummaryDataTest.check("two groups, in the order the objects appear", out.completenessGroups.size() == 2 && "Person".equals(out.completenessGroups.get(0).entityName));
		final WizardSummaryData.CompletenessGroup person = out.completenessGroups.get(0);
		WizardSummaryDataTest.check("Person: matched, measured on its 30 golden records, plain attribute names",
			person.matched && person.scopeCount == 30 && person.rows.size() == 2 && "name".equals(person.rows.get(0).label) && person.rows.get(1).pct == 50);
		final WizardSummaryData.CompletenessGroup organisation = out.completenessGroups.get(1);
		WizardSummaryDataTest.check("Organisation: not matched yet, no bar at all (it read 'name 0 %' with 62 named organisations)", !organisation.matched && organisation.rows.isEmpty());
		WizardSummaryDataTest.check("the flat list is unchanged for existing readers", out.completeness.size() == 3);
		// an object whose only run FAILED has no golden records either
		final WizardSummaryData.Input failed = WizardSummaryDataTest.uxCrmSf();
		failed.completeness.add(WizardSummaryDataTest.fill("Address", "street", 0, 0));
		WizardSummaryDataTest.check("a failed run is not 'matched'", !WizardSummaryData.compute(failed).completenessGroups.get(0).matched);
	}

	private static void completenessWithoutObjectsKeepsTheOldBehaviour()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		final WizardSummaryData.CompletenessInput legacy = new WizardSummaryData.CompletenessInput();
		legacy.attributeLabel = "Name";
		legacy.filled = 1;
		legacy.total = 2;
		in.completeness.add(legacy);
		WizardSummaryDataTest.check("no object read: one unnamed matched group", WizardSummaryData.compute(in).completenessGroups.size() == 1
			&& WizardSummaryData.compute(in).completenessGroups.get(0).matched && WizardSummaryData.compute(in).completenessGroups.get(0).rows.get(0).pct == 50
			&& "Name".equals(WizardSummaryData.compute(in).completenessGroups.get(0).rows.get(0).label));
		in.completenessMasterFallback = true;
		WizardSummaryDataTest.check("master fallback with no object read: unmatched, no bar", !WizardSummaryData.compute(in).completenessGroups.get(0).matched);
	}

	private static void journalBarsProportional()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		final WizardSummaryData.JournalStepInput a = new WizardSummaryData.JournalStepInput();
		a.stepLabel = "LANDING";
		a.seconds = 170;
		a.rowsText = "345 rows";
		in.journal.add(a);
		final WizardSummaryData.JournalStepInput b = new WizardSummaryData.JournalStepInput();
		b.stepLabel = "STAGING";
		b.seconds = 85;
		b.rowsText = "690 rows";
		in.journal.add(b);
		final WizardSummaryData.JournalStepInput c = new WizardSummaryData.JournalStepInput();
		c.stepLabel = "MASTER";
		c.seconds = 30;
		c.rowsText = "345 rows";
		in.journal.add(c);
		final WizardSummaryData.Output out = WizardSummaryData.compute(in);
		WizardSummaryDataTest.check("longest step reaches 100%", out.journal.get(0).barPct == 100);
		WizardSummaryDataTest.check("half as long is about half the bar", out.journal.get(1).barPct >= 48 && out.journal.get(1).barPct <= 52);
		WizardSummaryDataTest.check("duration text carries minutes", out.journal.get(0).timeText.contains("min"));
		WizardSummaryDataTest.check("a short one stays in seconds", out.journal.get(2).timeText.equals("30 s"));
	}

	private static void journeyGeometryForOneSource()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		in.sources.add(WizardSummaryDataTest.sourceInput("ERP", 100));
		final WizardSummaryData.Output out = WizardSummaryData.compute(in);
		// 1 source + landing + staging + master + golden = 5 nodes
		WizardSummaryDataTest.check("5 journey nodes for 1 source", out.journey.nodes.size() == 5);
		WizardSummaryDataTest.check("3 flows for 1 source (source->landing->staging->master) + 1 master->golden = 4", out.journey.flows.size() == 4);
		for (final WizardSummaryData.JourneyNode node : out.journey.nodes)
		{
			WizardSummaryDataTest.check("node stays inside the canvas horizontally", node.x >= 0 && node.x + node.w <= out.journey.width);
			WizardSummaryDataTest.check("node stays inside the canvas vertically", node.y >= 0 && node.y + node.h <= out.journey.height);
			WizardSummaryDataTest.check("node has a positive height", node.h > 0);
		}
		for (final WizardSummaryData.JourneyFlow flow : out.journey.flows)
		{
			WizardSummaryDataTest.check("every flow path starts with a moveto", flow.pathD.startsWith("M "));
		}
	}

	private static void journeyGeometryForEightSources()
	{
		final WizardSummaryData.Input in = WizardSummaryDataTest.baseInput();
		for (int i = 0; i < 8; i++)
		{
			in.sources.add(WizardSummaryDataTest.sourceInput("Source" + i, 10 + i));
		}
		final WizardSummaryData.Output out = WizardSummaryData.compute(in);
		WizardSummaryDataTest.check("8 sources + 4 zone nodes", out.journey.nodes.size() == 12);
		for (final WizardSummaryData.JourneyNode node : out.journey.nodes)
		{
			WizardSummaryDataTest.check("no negative/NaN height with 8 sources", node.h > 0 && !Double.isNaN(node.h));
			WizardSummaryDataTest.check("node stays inside the canvas vertically (8 sources)", node.y >= 0 && node.y + node.h <= out.journey.height + 0.01);
		}
	}

	private static void journeyHandlesZeroSourcesWithoutCrashing()
	{
		final WizardSummaryData.Output out = WizardSummaryData.compute(WizardSummaryDataTest.baseInput());
		// landing + staging + master + golden = 4 nodes, no source node, no flows
		WizardSummaryDataTest.check("4 zone nodes, no sources", out.journey.nodes.size() == 4);
		WizardSummaryDataTest.check("one flow: master -> golden", out.journey.flows.size() == 1);
	}

	private static WizardSummaryData.SourceInput sourceInput(final String pName, final long pRows)
	{
		final WizardSummaryData.SourceInput source = new WizardSummaryData.SourceInput();
		source.name = pName;
		source.receivedRows = pRows;
		return source;
	}

	private static WizardSummaryData.ZoneEntityInput zoneEntity(final String pName, final long pRows)
	{
		final WizardSummaryData.ZoneEntityInput entity = new WizardSummaryData.ZoneEntityInput();
		entity.name = pName;
		entity.rows = Long.valueOf(pRows);
		return entity;
	}
}
