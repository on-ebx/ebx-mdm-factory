package com.on.ebx.factory.wizard;

import java.util.Locale;

/**
 * JUnit-free, same technique and same defect guarded against as {@code
 * com.on.ebx.factory.ebx.ui.CockpitMessagesTest} on the {@code connection.*}/{@code cockpit.*} keys of
 * the very same shared bundle: {@code java.text.MessageFormat} silently eats a single, un-doubled
 * apostrophe in a pattern that also has a placeholder. Every {@code wizard.*} key formatted with one or
 * more {@code {n}} arguments is checked here, in both locales.
 */
public final class WizardMessagesTest
{
	/** Every {@code wizard.*} key formatted with one or more {@code {n}} arguments, and how many. */
	private static final Object[][] ARG_KEYS = { { "wizard.nav.progress", Integer.valueOf(2) }, { "wizard.step1.dataspaces", Integer.valueOf(2) },
		{ "wizard.step2.applications", Integer.valueOf(1) }, { "wizard.step2.catalogSource", Integer.valueOf(1) }, { "wizard.step2.schemas", Integer.valueOf(1) },
		{ "wizard.step2.connector", Integer.valueOf(1) }, { "wizard.step3.loadOrder", Integer.valueOf(1) },
		{ "wizard.step3.cycle", Integer.valueOf(1) }, { "wizard.step4.entity", Integer.valueOf(1) },
		// wizard-visual-pass ROUND 3 (2026-09-24): singular/plural via the bundle (.one/.other) for every
		// counted-noun message - see docs/WIZARD-VISUAL.md, "no bare (s)".
		{ "wizard.step3.lastLoad.one", Integer.valueOf(2) }, { "wizard.step3.lastLoad.other", Integer.valueOf(2) },
		{ "wizard.step7.lead", Integer.valueOf(1) }, { "wizard.notice.planned", Integer.valueOf(2) },
		{ "wizard.notice.stagingSuggested.one", Integer.valueOf(1) }, { "wizard.notice.stagingSuggested.other", Integer.valueOf(1) },
		{ "wizard.notice.bulk.one", Integer.valueOf(2) }, { "wizard.notice.bulk.other", Integer.valueOf(2) },
		{ "wizard.step4.llm.summary", Integer.valueOf(6) },
		{ "wizard.notice.generatedPartial.one", Integer.valueOf(1) }, { "wizard.notice.generatedPartial.other", Integer.valueOf(1) },
		{ "wizard.style.diagram.title", Integer.valueOf(1) }, { "wizard.step4.entity.unifies", Integer.valueOf(1) },
		{ "wizard.step4.entity.waitingHint.one", Integer.valueOf(2) }, { "wizard.step4.entity.waitingHint.other", Integer.valueOf(2) },
		// staging-llm-chained lot (2026-09-23): {5} of the summary is the per-source entities the model superseded
		{ "wizard.notice.stagingSuggestedChained.one", Integer.valueOf(2) }, { "wizard.notice.stagingSuggestedChained.other", Integer.valueOf(2) },
		{ "wizard.step4.entity.supersededBy", Integer.valueOf(1) },
		{ "wizard.step4.entity.memberOf", Integer.valueOf(1) },
		// pipeline-buttons lot (2026-09-23)
		{ "wizard.step4.attribute.mappings.one", Integer.valueOf(2) }, { "wizard.step4.attribute.mappings.other", Integer.valueOf(2) },
		{ "wizard.step4.waiting.title.one", Integer.valueOf(1) }, { "wizard.step4.waiting.title.other", Integer.valueOf(1) },
		{ "wizard.step4.waiting.match", Integer.valueOf(1) }, { "wizard.step5.dataspace", Integer.valueOf(2) },
		{ "wizard.step6.plan.comment", Integer.valueOf(1) },
		{ "wizard.notice.mappingsReopened.one", Integer.valueOf(1) }, { "wizard.notice.mappingsReopened.other", Integer.valueOf(1) },
		{ "wizard.notice.qualitySuggested", Integer.valueOf(2) }, { "wizard.notice.qualitySuggestedLlm", Integer.valueOf(3) },
		{ "wizard.notice.capabilitiesSuggested", Integer.valueOf(2) }, { "wizard.notice.stagingModelGenerated", Integer.valueOf(2) },
		{ "wizard.notice.stagingLoaded.one", Integer.valueOf(4) }, { "wizard.notice.stagingLoaded.other", Integer.valueOf(4) },
		{ "wizard.notice.masterGenerated", Integer.valueOf(2) },
		{ "wizard.notice.masterLoaded.one", Integer.valueOf(4) }, { "wizard.notice.masterLoaded.other", Integer.valueOf(4) },
		{ "wizard.notice.matchingSuggested", Integer.valueOf(3) },
		{ "wizard.notice.matchingSuggestedLlm.one", Integer.valueOf(1) }, { "wizard.notice.matchingSuggestedLlm.other", Integer.valueOf(1) },
		{ "wizard.notice.matchingConfigured.one", Integer.valueOf(3) }, { "wizard.notice.matchingConfigured.other", Integer.valueOf(3) },
		{ "wizard.notice.matchingRun.one", Integer.valueOf(3) }, { "wizard.notice.matchingRun.other", Integer.valueOf(3) },
		// wizard-visual-pass ROUND 3: new/changed multi-noun messages, args are now pre-pluralized phrases
		// (never raw counts) - see WizardPage#countWords / WizardMessages#countWords.
		{ "wizard.step3.keyStudy.llm.ran", Integer.valueOf(4) }, { "wizard.step4.grid.sublabel", Integer.valueOf(2) },
		{ "wizard.step4.run.done.summary", Integer.valueOf(5) },
		{ "wizard.step4.build.heldBack.banner.one", Integer.valueOf(1) }, { "wizard.step4.build.heldBack.banner.other", Integer.valueOf(1) },
		{ "wizard.step4.build.heldBack.review.one", Integer.valueOf(1) }, { "wizard.step4.build.heldBack.review.other", Integer.valueOf(1) },
		{ "wizard.step5.grid.sublabel.one", Integer.valueOf(1) }, { "wizard.step5.grid.sublabel.other", Integer.valueOf(1) },
		{ "wizard.stepReset.done", Integer.valueOf(2) },
		// inline-reviews lot (2026-09-23)
		{ "wizard.track.runningSince", Integer.valueOf(1) }, { "wizard.notice.qualitySuggestedLlmZeroReason", Integer.valueOf(1) },
		{ "wizard.notice.capabilitiesSuggestedZeroReason", Integer.valueOf(1) },
		// source-combobox lot (2026-09-23)
		{ "wizard.field.catalogSource.hint", Integer.valueOf(1) },
		// threshold-left-out / apply-visibility lot (2026-09-23)
		{ "wizard.notice.bulkLeftOut.one", Integer.valueOf(4) }, { "wizard.notice.bulkLeftOut.other", Integer.valueOf(4) },
		{ "wizard.notice.bulkApplyDeferred.one", Integer.valueOf(1) }, { "wizard.notice.bulkApplyDeferred.other", Integer.valueOf(1) },
		{ "wizard.capability.appliedInfo", Integer.valueOf(2) },
		// source-onboarding lot (2026-09-23): every com.on.ebx.factory.wizard.SourceOnboarding message key that
		// carries an argument - guards the exact apostrophe-eats-the-placeholder trap this class's own Javadoc
		// warns about (java.text.MessageFormat), which a plain "does it resolve" check would not catch
		{ "wizard.onboard.step.connection.done", Integer.valueOf(1) }, { "wizard.onboard.step.connection.notFound", Integer.valueOf(2) },
		{ "wizard.onboard.step.connection.inactive", Integer.valueOf(1) }, { "wizard.onboard.step.catalogOperation.done", Integer.valueOf(1) },
		// fix lot (2026-09-25, bug 1): the old single "unsupported" dead-end key is now two - deduced offers
		// the deduced-scan path, enrichmentOnly is the true dead end (no read-only operation at all)
		{ "wizard.onboard.step.catalogOperation.deduced", Integer.valueOf(1) }, { "wizard.onboard.step.catalogOperation.enrichmentOnly", Integer.valueOf(1) },
		{ "wizard.onboard.step.catalogSource.linked", Integer.valueOf(1) },
		{ "wizard.onboard.step.catalogSource.todo", Integer.valueOf(1) }, { "wizard.onboard.step.catalogSource.manual", Integer.valueOf(1) },
		{ "wizard.onboard.step.catalogSource.manualSampleFileHint", Integer.valueOf(2) }, { "wizard.onboard.step.catalogSource.catalogUnavailable", Integer.valueOf(1) },
		{ "wizard.onboard.step.catalogSource.toolError", Integer.valueOf(1) },
		{ "wizard.onboard.step.scan.done", Integer.valueOf(1) }, { "wizard.onboard.step.scan.emptyResult", Integer.valueOf(1) },
		{ "wizard.onboard.step.ready.done", Integer.valueOf(1) }, { "wizard.onboard.step.ready.sourceInactive", Integer.valueOf(1) },
		{ "wizard.onboard.step.ready.sourceMissing", Integer.valueOf(1) },
		// source-onboarding lot (2026-09-23): the panel's own argument-bearing keys (WizardPage, ProjectWizardUserService)
		{ "wizard.onboard.selected", Integer.valueOf(1) }, { "wizard.onboard.scanMessage", Integer.valueOf(1) },
		{ "wizard.onboard.notice.picked", Integer.valueOf(1) },
		// fix lot (2026-09-25, bug 1): the deduced-scan entry point's own notice
		{ "wizard.onboard.notice.deducedStarted", Integer.valueOf(1) },
		{ "wizard.onboard.combo.hint.one", Integer.valueOf(1) }, { "wizard.onboard.combo.hint.other", Integer.valueOf(1) },
		// sample-files lot (2026-09-23): only the keys ACTUALLY formatted with args server-side - the panel's own
		// four error.extension/error.size/error.total/error.duplicate keys are deliberately excluded here, they are
		// NEVER passed through WizardMessages.get with args (see their own comment in Messages_en.properties): their
		// {0}/{1} is filled by wizard.js, client-side only, so MessageFormat never touches them in real use.
		{ "wizard.step2.sampleFiles.error.empty", Integer.valueOf(1) }, { "wizard.step2.sampleFiles.report.applicationAdded", Integer.valueOf(1) },
		{ "wizard.step2.sampleFiles.report.applicationExists", Integer.valueOf(1) }, { "wizard.step2.sampleFiles.report.error", Integer.valueOf(1) },
		// target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): the feed tree's own argument-bearing keys
		{ "wizard.step4.feed.childOf", Integer.valueOf(1) }, { "wizard.step4.feed.parentLink", Integer.valueOf(1) },
		// wizard-summary lot (2026-09-24): step 7's own argument-bearing keys (WizardPage#renderStep7 and friends)
		{ "wizard.step7.verdict.OPERATIONAL", Integer.valueOf(1) }, { "wizard.step7.verdict.NOT_READY", Integer.valueOf(1) },
		{ "wizard.step7.verdict.BLOCKED", Integer.valueOf(1) }, { "wizard.step7.verdict.openCount.one", Integer.valueOf(1) },
		{ "wizard.step7.verdict.openCount.other", Integer.valueOf(1) },
		{ "wizard.step7.journey.rows.one", Integer.valueOf(1) }, { "wizard.step7.journey.rows.other", Integer.valueOf(1) },
		{ "wizard.step7.zoneFact.landing.0", Integer.valueOf(1) },
		{ "wizard.step7.zoneFact.staging.0", Integer.valueOf(1) }, { "wizard.step7.zoneFact.staging.1", Integer.valueOf(2) },
		{ "wizard.step7.zoneFact.staging.2", Integer.valueOf(2) }, { "wizard.step7.zoneFact.master.0", Integer.valueOf(1) },
		{ "wizard.step7.zoneFact.matching.0", Integer.valueOf(2) }, { "wizard.step7.zoneFact.matching.1", Integer.valueOf(1) },
		{ "wizard.step7.prod.chain.launched", Integer.valueOf(1) }, { "wizard.step7.prod.deliver.checkResult", Integer.valueOf(3) },
		{ "wizard.step7.open.rejects.title.one", Integer.valueOf(1) }, { "wizard.step7.open.rejects.title.other", Integer.valueOf(1) },
		{ "wizard.step7.open.suspects.title.one", Integer.valueOf(1) }, { "wizard.step7.open.suspects.title.other", Integer.valueOf(1) },
		{ "wizard.step7.open.mappingGap.title", Integer.valueOf(2) }, { "wizard.step7.open.proposed.title.one", Integer.valueOf(2) },
		{ "wizard.step7.open.proposed.title.other", Integer.valueOf(2) },
		// step 6/7 visual round (2026-09-24)
		{ "wizard.step6.plan.strip.example", Integer.valueOf(4) }, { "wizard.step6.plan.survivorship.exception", Integer.valueOf(3) },
		{ "wizard.step6.results.chip.missing", Integer.valueOf(2) }, { "wizard.step6.results.bulk.merge.one", Integer.valueOf(2) },
		{ "wizard.step6.results.bulk.merge.other", Integer.valueOf(2) },
		// wizard-llm-value-sharing lot (2026-09-28): step 1's control - both carry a French apostrophe next to a placeholder
		{ "wizard.field.llmSharing.capped", Integer.valueOf(2) }, { "wizard.field.llmSharing.consequence.SAMPLE_VALUES", Integer.valueOf(2) },
		// automatic-intake wizard lot (2026-10-05): every argument-bearing key of the intake screens (the others are checked by intakeKeysExistInBothLocalesWithTheSamePlaceholders)
		{ "wizard.intake.card.error", Integer.valueOf(1) }, { "wizard.intake.paused.reason", Integer.valueOf(1) }, { "wizard.intake.openPoint.paused.detail", Integer.valueOf(1) },
		{ "wizard.intake.unit.seconds.one", Integer.valueOf(1) }, { "wizard.intake.unit.seconds.other", Integer.valueOf(1) },
		{ "wizard.intake.unit.minutes.one", Integer.valueOf(1) }, { "wizard.intake.unit.minutes.other", Integer.valueOf(1) },
		{ "wizard.intake.ready.LANDING_LOADED.ko.one", Integer.valueOf(1) }, { "wizard.intake.ready.LANDING_LOADED.ko.other", Integer.valueOf(1) },
		{ "wizard.intake.ready.REJECTS_REVIEWED.ko.one", Integer.valueOf(1) }, { "wizard.intake.ready.REJECTS_REVIEWED.ko.other", Integer.valueOf(1) },
		{ "wizard.intake.live.footer", Integer.valueOf(1) }, { "wizard.intake.src.lastLine", Integer.valueOf(2) }, { "wizard.intake.src.atLatest", Integer.valueOf(1) },
		{ "wizard.intake.src.why", Integer.valueOf(1) }, { "wizard.intake.rows.new", Integer.valueOf(1) }, { "wizard.intake.rows.written", Integer.valueOf(1) },
		{ "wizard.intake.rows.unchanged", Integer.valueOf(1) }, { "wizard.intake.result.DONE_WITH_REJECTS", Integer.valueOf(1) },
		{ "wizard.intake.result.STOPPED", Integer.valueOf(1) }, { "wizard.intake.result.FAILED", Integer.valueOf(1) },
		{ "wizard.intake.heldBack.one", Integer.valueOf(1) }, { "wizard.intake.heldBack.other", Integer.valueOf(1) },
		// lot C1-B: every argument-bearing key of the CENTRALISATION screens (the French ones carry apostrophes next to placeholders)
		{ "wizard.accel.pack.basis", Integer.valueOf(3) }, { "wizard.accel.spec.options", Integer.valueOf(1) }, { "wizard.accel.pass.head", Integer.valueOf(2) },
		{ "wizard.accel.pass.counts", Integer.valueOf(2) }, { "wizard.accel.check.text", Integer.valueOf(2) }, { "wizard.accel.kept.show", Integer.valueOf(1) },
		{ "wizard.accel.generate.left", Integer.valueOf(1) }, { "wizard.accel.decisions.kept", Integer.valueOf(1) }, { "wizard.accel.gen.count", Integer.valueOf(2) },
		{ "wizard.accel.draft.text", Integer.valueOf(1) }, { "wizard.accel.trace.decidedCounts", Integer.valueOf(3) }, { "wizard.accel.suggested", Integer.valueOf(1) },
		{ "wizard.accel.packsSaved", Integer.valueOf(1) }, { "wizard.accel.passQueued", Integer.valueOf(1) },
		// UX lot U3 (2026-10-13): counters, live proposal list, summary scoping, Recent tasks - the French ones use the typographic apostrophe, checked all the same
		{ "wizard.recent.showAll", Integer.valueOf(1) }, { "wizard.jobnotice.nothing", Integer.valueOf(1) }, { "wizard.step4.run.now", Integer.valueOf(1) },
		{ "wizard.step4.run.count", Integer.valueOf(2) }, { "wizard.step4.run.runningFor", Integer.valueOf(1) }, { "wizard.step4.counts.accepted", Integer.valueOf(1) },
		{ "wizard.step4.counts.waiting", Integer.valueOf(1) }, { "wizard.step4.counts.rejected", Integer.valueOf(1) }, { "wizard.step4.counts.setAside", Integer.valueOf(1) },
		{ "wizard.step7.zoneFact.landing.1", Integer.valueOf(1) }, { "wizard.step7.zoneFact.master.1", Integer.valueOf(1) }, { "wizard.step7.zoneDetail.line", Integer.valueOf(3) },
		{ "wizard.step7.completeness.group.matched", Integer.valueOf(1) }, { "wizard.step7.open.matchFailed.title", Integer.valueOf(1) },
		{ "wizard.step7.open.noPlan.title", Integer.valueOf(1) },
		// UX lot D (2026-10-14): partial failures - the French ones use the typographic apostrophe
		{ "wizard.partial.more", Integer.valueOf(1) }, { "wizard.partial.landing.title.one", Integer.valueOf(3) }, { "wizard.partial.landing.title.other", Integer.valueOf(3) }, { "wizard.partial.landing.titleNoTotal.one", Integer.valueOf(2) }, { "wizard.partial.landing.titleNoTotal.other", Integer.valueOf(2) }, { "wizard.partial.landing.retry.other", Integer.valueOf(1) }, { "wizard.partial.landing.warning", Integer.valueOf(1) }, { "wizard.partial.configure.title.one", Integer.valueOf(2) }, { "wizard.partial.configure.title.other", Integer.valueOf(2) }, { "wizard.partial.configure.retry.other", Integer.valueOf(1) }, { "wizard.partial.run.title.one", Integer.valueOf(2) }, { "wizard.partial.run.title.other", Integer.valueOf(2) }, { "wizard.partial.run.warning", Integer.valueOf(1) }, { "wizard.partial.open.landing.title.one", Integer.valueOf(2) }, { "wizard.partial.open.landing.title.other", Integer.valueOf(2) }, { "wizard.partial.open.configure.title.one", Integer.valueOf(2) }, { "wizard.partial.open.configure.title.other", Integer.valueOf(2) }, { "wizard.partial.open.run.title.one", Integer.valueOf(2) }, { "wizard.partial.open.run.title.other", Integer.valueOf(2) },
		// lot M6 (2026-10-18): the configure note when the add-on's business-object validation threw and the main table was validated alone (the French one has apostrophes next to a placeholder)
		{ "wizard.step6.configure.note.validateBusinessObjectFallback", Integer.valueOf(3) },
		// step 6 screen leftovers (2026-10-18): the queue per group and the rule that linked its records (the French ones carry apostrophes next to placeholders)
		{ "wizard.step6.results.group.title", Integer.valueOf(1) }, { "wizard.step6.results.group.merge", Integer.valueOf(1) }, { "wizard.step6.results.groups.counts", Integer.valueOf(2) },
		{ "wizard.step6.results.groups.more", Integer.valueOf(1) }, { "wizard.step6.results.group.failed", Integer.valueOf(2) }, { "wizard.step6.results.group.merged", Integer.valueOf(2) },
		{ "wizard.step6.results.group.kept", Integer.valueOf(1) }, { "wizard.step6.results.why.node", Integer.valueOf(1) }, { "wizard.step6.results.why.allFields", Integer.valueOf(2) },
		{ "wizard.step6.results.why.atLeastN", Integer.valueOf(3) }, { "wizard.step6.results.why.average", Integer.valueOf(3) }, { "wizard.step6.results.why.predicate", Integer.valueOf(2) },
		{ "wizard.step6.results.why.notEnough", Integer.valueOf(2) }, { "wizard.step6.run.written.one", Integer.valueOf(1) }, { "wizard.step6.run.written.many", Integer.valueOf(1) },
		{ "wizard.step6.run.refreshed.one", Integer.valueOf(1) }, { "wizard.step6.run.refreshed.many", Integer.valueOf(1) }, { "wizard.step6.run.unchanged.one", Integer.valueOf(1) },
		{ "wizard.step6.run.unchanged.many", Integer.valueOf(1) } };

	public static void main(final String[] pArgs)
	{
		WizardMessagesTest.everyArgKeyFormatsCleanInFrench();
		WizardMessagesTest.everyArgKeyFormatsCleanInEnglish();
		WizardMessagesTest.everyStatusOriginPipelineStepKeyResolvesInBothLocales();
		WizardMessagesTest.everyStyleCardKeyResolvesInBothLocales();
		WizardMessagesTest.proposalRunSkipReasonKeyResolvesInBothLocales();
		WizardMessagesTest.intakeKeysExistInBothLocalesWithTheSamePlaceholders();
		WizardMessagesTest.noArglessMessageHoldsADoubledApostrophe();
		WizardMessagesTest.planRunSummaryHasProperPlurals();

		System.out.println("WizardMessagesTest OK");
	}

	/**
	 * Step 6 screen leftovers (2026-10-18): a message read WITHOUT arguments is shown as it is stored, so a doubled apostrophe (the {@code MessageFormat} escape) in it renders literally
	 * ("D''o\u00F9 viennent les golden records"). Every message of both bundles that has no {@code {n}} placeholder must hold none.
	 */
	private static void noArglessMessageHoldsADoubledApostrophe()
	{
		final java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("\\{\\d");
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", locale, WizardMessagesTest.class.getClassLoader());
			for (final String key : java.util.Collections.list(bundle.getKeys()))
			{
				final String value = bundle.getString(key);
				WizardMessagesTest.check(key + " (" + locale + "): no doubled apostrophe in a message without argument: [" + value + "]",
					value.indexOf("''") < 0 || placeholder.matcher(value).find());
			}
		}
		WizardMessagesTest.check("the reported title", WizardMessages.get(Locale.FRENCH, "wizard.step6.results.band.title").equals("D'o\u00F9 viennent les golden records"));
	}

	/** The plan run's summary says each count with its own singular or plural, never a bare "(s)". */
	private static void planRunSummaryHasProperPlurals()
	{
		final java.util.function.BiFunction<Locale, long[], String> summary = (locale, counts) -> WizardMessages.get(locale, "wizard.step6.run.summary",
			WizardMessages.plural(locale, counts[0], "wizard.step6.run.written.one", "wizard.step6.run.written.many"),
			WizardMessages.plural(locale, counts[1], "wizard.step6.run.refreshed.one", "wizard.step6.run.refreshed.many"),
			WizardMessages.plural(locale, counts[2], "wizard.step6.run.unchanged.one", "wizard.step6.run.unchanged.many"), "run", "DONE", "");
		WizardMessagesTest.check("French, one and many", summary.apply(Locale.FRENCH, new long[] { 1, 0, 0 })
			.equals("1 plan de rapprochement \u00E9crit, 0 plans de rapprochement actualis\u00E9s, 0 plans de rapprochement inchang\u00E9s."));
		WizardMessagesTest.check("French, many and one", summary.apply(Locale.FRENCH, new long[] { 3, 1, 2 })
			.equals("3 plans de rapprochement \u00E9crits, 1 plan de rapprochement actualis\u00E9, 2 plans de rapprochement inchang\u00E9s."));
		WizardMessagesTest.check("English", summary.apply(Locale.ENGLISH, new long[] { 1, 2, 0 }).equals("1 matching plan written, 2 matching plans refreshed, 0 matching plans unchanged."));
		WizardMessagesTest.check("no bare (s)", !summary.apply(Locale.FRENCH, new long[] { 1, 1, 1 }).contains("(s)"));
	}

	private static void everyArgKeyFormatsCleanInFrench()
	{
		WizardMessagesTest.everyArgKeyFormatsClean(Locale.FRENCH);
	}

	private static void everyArgKeyFormatsCleanInEnglish()
	{
		WizardMessagesTest.everyArgKeyFormatsClean(Locale.ENGLISH);
	}

	private static void everyArgKeyFormatsClean(final Locale pLocale)
	{
		for (final Object[] entry : WizardMessagesTest.ARG_KEYS)
		{
			final String key = (String) entry[0];
			final int argCount = ((Integer) entry[1]).intValue();
			final Object[] args = new Object[argCount];
			for (int i = 0; i < argCount; i++)
			{
				args[i] = "ARG" + i;
			}
			final String formatted = WizardMessages.get(pLocale, key, args);
			WizardMessagesTest.check(key + " (" + pLocale + "): not the raw key back (bundle must resolve it)", !key.equals(formatted));
			WizardMessagesTest.check(key + " (" + pLocale + "): no unresolved {n} placeholder left: [" + formatted + "]", formatted.indexOf('{') < 0);
			for (int i = 0; i < argCount; i++)
			{
				WizardMessagesTest.check(key + " (" + pLocale + "): argument " + i + " substituted: [" + formatted + "]", formatted.indexOf("ARG" + i) >= 0);
			}
		}
	}

	private static final String[] STATUSES = { "DRAFT", "LANDING", "STAGING", "LIVE", "ARCHIVED", "SELECTED", "GENERATED", "LOADED", "FAILED", "DRIFTED", "PROPOSED",
		"ACCEPTED", "REJECTED", "APPLIED" };
	private static final String[] ORIGINS = { "RULE", "LLM", "USER", "DECLARED" };
	private static final String[] PIPELINE_STEPS = { "LANDING_MODEL", "LANDING_DATASET", "LANDING_FLOWS", "LANDING_LOAD",
		// pipeline-buttons lot (2026-09-23)
		"STAGING_SUGGEST", "QUALITY_SUGGEST", "QUALITY_SUGGEST_LLM", "CAPABILITY_SUGGEST_LLM", "STAGING_MODEL", "STAGING_DATASET", "STAGING_LOAD",
		"MASTER_MODEL", "MASTER_DATASET", "MASTER_LOAD", "MATCHING_SUGGEST_LLM" };
	/** source-onboarding lot, per-step name keys (coordinator review, 2026-09-23) - SourceOnboarding.Step's own six keys, checked against wizard.onboard.step.<key>.name. */
	private static final String[] ONBOARD_STEP_KEYS = { "connection", "catalogOperation", "catalogSource", "scanFlows", "scan", "ready" };

	private static void everyStatusOriginPipelineStepKeyResolvesInBothLocales()
	{
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			for (final String status : WizardMessagesTest.STATUSES)
			{
				WizardMessagesTest.checkResolves(locale, "wizard.status." + status);
			}
			for (final String origin : WizardMessagesTest.ORIGINS)
			{
				WizardMessagesTest.checkResolves(locale, "wizard.origin." + origin);
			}
			for (final String step : WizardMessagesTest.PIPELINE_STEPS)
			{
				WizardMessagesTest.checkResolves(locale, "wizard.pipelineStep." + step);
			}
			for (final String onboardStep : WizardMessagesTest.ONBOARD_STEP_KEYS)
			{
				WizardMessagesTest.checkResolves(locale, "wizard.onboard.step." + onboardStep + ".name");
			}
		}
	}

	/**
	 * Staging-proposal live-run bug fix (2026-09-24): {@code ebx.StagingProposalRun} resolves {@code
	 * wizard.gate.llmProposalsPending} through this exact call ({@code WizardMessages.get(locale, key)}, no
	 * args) before ever storing it as a {@code staging.ProposalProgress.Stage#summary} - a run measured
	 * live on project Personnes showed the raw key instead of text because that one call site passed the
	 * key straight through. Guards the key resolves to real text in both locales, so it can never again
	 * silently regress to showing the bare key in the "while running" screen.
	 */
	private static void proposalRunSkipReasonKeyResolvesInBothLocales()
	{
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			WizardMessagesTest.checkResolves(locale, "wizard.gate.llmProposalsPending");
		}
	}

	/**
	 * Driven directly off {@link WizardRules#IMPLEMENTATION_STYLES} (not a hand-copied list of codes), so a
	 * catalogue change can never silently leave a card's label/when/consequence key unresolved in either
	 * locale. Also covers the three enum-value keys ({@code wizard.style.authoring/golden/flow.*}) and the
	 * two axis-caption keys.
	 */
	private static void everyStyleCardKeyResolvesInBothLocales()
	{
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			WizardMessagesTest.checkResolves(locale, "wizard.field.style");
			WizardMessagesTest.checkResolves(locale, "wizard.style.axis.intro");
			WizardMessagesTest.checkResolves(locale, "wizard.style.axis.sources");
			WizardMessagesTest.checkResolves(locale, "wizard.style.axis.hub");
			WizardMessagesTest.checkResolves(locale, "wizard.style.mostCommon");
			WizardMessagesTest.checkResolves(locale, "wizard.style.row.golden");
			WizardMessagesTest.checkResolves(locale, "wizard.style.row.flows");
			WizardMessagesTest.checkResolves(locale, "wizard.style.when.label");
			// the diagram's own labels (WizardStyleDiagram.Labels), resolved once here by WizardPage
			WizardMessagesTest.checkResolves(locale, "wizard.style.column.sources");
			WizardMessagesTest.checkResolves(locale, "wizard.style.column.hub");
			WizardMessagesTest.checkResolves(locale, "wizard.style.column.consumers");
			WizardMessagesTest.checkResolves(locale, "wizard.style.tag.created");
			WizardMessagesTest.checkResolves(locale, "wizard.style.tag.copy");
			WizardMessagesTest.checkResolves(locale, "wizard.style.tag.keysOnly");
			WizardMessagesTest.checkResolves(locale, "wizard.style.tag.subscribers");
			WizardMessagesTest.checkResolves(locale, "wizard.style.tag.read");
			WizardMessagesTest.checkResolves(locale, "wizard.style.arrow.keys");
			WizardMessagesTest.checkResolves(locale, "wizard.style.diagram.app1");
			WizardMessagesTest.checkResolves(locale, "wizard.style.diagram.app2");
			for (final WizardRules.Authoring authoring : WizardRules.Authoring.values())
			{
				WizardMessagesTest.checkResolves(locale, "wizard.style.authoring." + authoring.name());
			}
			for (final WizardRules.GoldenRecord golden : WizardRules.GoldenRecord.values())
			{
				WizardMessagesTest.checkResolves(locale, "wizard.style.golden." + golden.name());
			}
			for (final WizardRules.Flow flow : WizardRules.Flow.values())
			{
				WizardMessagesTest.checkResolves(locale, "wizard.style.flow." + flow.name());
			}
			for (final WizardRules.ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
			{
				WizardMessagesTest.checkResolves(locale, style.labelKey);
				WizardMessagesTest.checkResolves(locale, style.whenKey);
				WizardMessagesTest.checkResolves(locale, style.consequenceKey);
				WizardMessagesTest.checkResolves(locale, style.headlineKey);
			}
		}
	}

	/**
	 * Automatic-intake wizard lot (2026-10-05): every {@code wizard.intake.*} key of the English bundle exists in the French one with the SAME set of {@code {n}} placeholders
	 * (a French sentence that lost or gained an argument would print a stray "{1}" or drop a value), and none is blank. The argument-bearing keys are also formatted by
	 * {@link #everyArgKeyFormatsClean} through {@link #ARG_KEYS} (so an apostrophe next to a placeholder cannot eat it).
	 */
	private static void intakeKeysExistInBothLocalesWithTheSamePlaceholders()
	{
		final java.util.ResourceBundle en = java.util.ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", Locale.ENGLISH);
		final java.util.ResourceBundle fr = java.util.ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", Locale.FRENCH);
		final java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("\\{(\\d+)\\}");
		int seen = 0;
		for (final String key : java.util.Collections.list(en.getKeys()))
		{
			if (!key.startsWith("wizard.intake."))
			{
				continue;
			}
			seen++;
			WizardMessagesTest.check(key + ": present in French", fr.containsKey(key));
			WizardMessagesTest.check(key + ": not blank in English", !en.getString(key).trim().isEmpty());
			WizardMessagesTest.check(key + ": not blank in French", !fr.getString(key).trim().isEmpty());
			WizardMessagesTest.check(key + ": same placeholders in both languages", WizardMessagesTest.placeholders(placeholder, en.getString(key)).equals(WizardMessagesTest.placeholders(placeholder, fr.getString(key))));
		}
		for (final String key : java.util.Collections.list(fr.getKeys()))
		{
			WizardMessagesTest.check(key + ": every French intake key also exists in English", !key.startsWith("wizard.intake.") || en.containsKey(key));
		}
		WizardMessagesTest.check("the intake keys were found (" + seen + ")", seen > 100);
	}

	private static java.util.Set<String> placeholders(final java.util.regex.Pattern pPattern, final String pText)
	{
		final java.util.Set<String> out = new java.util.TreeSet<String>();
		final java.util.regex.Matcher matcher = pPattern.matcher(pText);
		while (matcher.find())
		{
			out.add(matcher.group(1));
		}
		return out;
	}

	private static void checkResolves(final Locale pLocale, final String pKey)
	{
		final String formatted = WizardMessages.get(pLocale, pKey);
		WizardMessagesTest.check(pKey + " (" + pLocale + ") resolves to real text, not the raw key", !pKey.equals(formatted));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
