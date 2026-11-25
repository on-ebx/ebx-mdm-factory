package com.on.ebx.factory.quality;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Reproduces the real
 * defect PLAN.md records for 2026-09-21 (city values only meeting after folding case and accents) as a
 * fixture, plus the structural gates {@link CrossSystemFold#propose} must apply before ever measuring
 * anything, plus (coordinator review, same day) the fix for measuring on RAW landing values instead of values
 * as a mapping's own ACCEPTED-or-APPLIED pipeline already leaves them.
 * <p>
 * <b>2026-09-22 redesign (product-owner decision, PLAN.md)</b>: {@link CrossSystemFold#propose} no longer
 * returns {@code MappingStep} proposals - it returns 0 or 1 {@link CrossSystemFold.AttributeProposal},
 * material for a NEW {@code StagingAttribute} row ({@code derivation FOLD_KEY}), never a pair written to
 * every mapping. Every fixture below is rewritten to the new shape; the MEASUREMENT rules themselves
 * (structural gates, accepted-pipeline replay, per-source-system grouping) are unchanged and still verified.
 * <p>
 * <b>NOT covered here, by design</b>: the idempotence rule "a {@code FOLD_KEY} attribute already derived from
 * this source, in ANY status including {@code REJECTED}, is never re-proposed" is real ({@code
 * ebx.QualitySuggester.writeDerivedFoldKeyAttribute}'s own Javadoc) but lives entirely on the EBX-bound side
 * (a live {@code StagingAttribute} table read) - {@link CrossSystemFold#propose} itself has, and needs, no
 * notion of what already exists in EBX, exactly like every other pure detector in this package (a rejected
 * {@code AttributeMapping}'s own filtering is likewise EBX-side, in {@code ebx.QualitySuggester.readAcceptedMappings}).
 * {@code ebx.QualitySuggester} is not itself unit-tested for the same reason every other EBX-bound generator
 * in this codebase is not (see its own class Javadoc); a real idempotence check needs a live instance.
 */
public final class CrossSystemFoldTest
{
	public static void main(final String[] pArgs)
	{
		CrossSystemFoldTest.tonightsCityDefectProposesOneFoldKeyAttribute();
		CrossSystemFoldTest.foldingChangesNothingProposesNothing();
		CrossSystemFoldTest.oneMappingIsNeverEnough();
		CrossSystemFoldTest.twoMappingsOfTheSameSystemAreNeverEnough();
		CrossSystemFoldTest.nonTextColumnIsNeverProposed();
		CrossSystemFoldTest.exampleShowsAShapeNeverARealCityName();
		CrossSystemFoldTest.rationaleNamesHowManyAcceptedStepsRanPerMapping();
		CrossSystemFoldTest.suggestedNameIsSourceAttributeNamePlusKey();
		CrossSystemFoldTest.applyAcceptedPipelineIsPassThroughOnAnEmptyPipeline();
		CrossSystemFoldTest.applyAcceptedPipelineDropsValuesTurnedNullAndMergesCollapsedCounts();
		CrossSystemFoldTest.hasUnsupportedScopedStepDetectsOnlyAScopedTranscodeReference();
		CrossSystemFoldTest.countryFixtureAlreadyNormalisedByAcceptedStepsProposesNothing();
		CrossSystemFoldTest.rawValueMeasurementOnTheSameCountryFixtureWouldHaveWronglyProposed();
		CrossSystemFoldTest.cityFixtureStillProposesWhenNeitherMappingHasAnAcceptedStep();
		CrossSystemFoldTest.mappingsAlreadyResolvedToTheSameFoldedValueYieldNoNewProposal();
		CrossSystemFoldTest.sharedCountIsBySourceSystemNotByMappingIndex();
		CrossSystemFoldTest.productExampleFromADifferentDomainStillProposes();
		System.out.println("CrossSystemFoldTest OK");
	}

	/**
	 * The real 2026-09-21 case: CRM writes {@code Paris}/{@code Zürich}, ERP writes {@code PARIS}/{@code
	 * ZURICH}. Raw text: zero values shared across the two systems. Folded (accents stripped, then
	 * upper-cased): both cities coincide - 2 shared. {@code sharedAfter (2) > sharedBefore (0)}, so ONE
	 * {@code FOLD_KEY} attribute proposal is returned (2026-09-22 redesign - not a {@code MappingStep} pair),
	 * carrying the exact counts in its rationale.
	 */
	private static void tonightsCityDefectProposesOneFoldKeyAttribute()
	{
		final CrossSystemFold.SystemValues crm = new CrossSystemFold.SystemValues("CRM",
			Arrays.asList(new Detectors.ValueCount("Paris", 30), new Detectors.ValueCount("Zürich", 12)), false);
		final CrossSystemFold.SystemValues erp = new CrossSystemFold.SystemValues("ERP",
			Arrays.asList(new Detectors.ValueCount("PARIS", 25), new Detectors.ValueCount("ZURICH", 8)), false);
		final Detectors.ColumnContext column = CrossSystemFoldTest.column("CITY", "city");

		final List<CrossSystemFold.AttributeProposal> proposals = CrossSystemFold.propose(Arrays.asList(crm, erp), column);
		CrossSystemFoldTest.check("exactly one FOLD_KEY attribute proposal: " + proposals.size(), proposals.size() == 1);
		final CrossSystemFold.AttributeProposal proposal = proposals.get(0);
		CrossSystemFoldTest.check("suggested name is cityKey: " + proposal.suggestedName, "cityKey".equals(proposal.suggestedName));
		CrossSystemFoldTest.check("rationale names 0 shared before, 2 after: " + proposal.rationale,
			proposal.rationale.contains("0 before") && proposal.rationale.contains("2 after"));
		CrossSystemFoldTest.check("sharedBefore/sharedAfter carried on the proposal itself: " + proposal.sharedBefore + "/" + proposal.sharedAfter,
			proposal.sharedBefore == 0 && proposal.sharedAfter == 2);
	}

	/** Two systems whose values already coincide exactly as raw text (or never coincide even after folding): the fold would fix nothing, so nothing is proposed - the same "never propose a no-op" principle every other detector in this package already follows. */
	private static void foldingChangesNothingProposesNothing()
	{
		final CrossSystemFold.SystemValues crm = new CrossSystemFold.SystemValues("CRM",
			Arrays.asList(new Detectors.ValueCount("PARIS", 10), new Detectors.ValueCount("ROME", 4)), false);
		final CrossSystemFold.SystemValues erp = new CrossSystemFold.SystemValues("ERP",
			Arrays.asList(new Detectors.ValueCount("PARIS", 9), new Detectors.ValueCount("BERLIN", 3)), false);
		final List<CrossSystemFold.AttributeProposal> proposals = CrossSystemFold.propose(Arrays.asList(crm, erp), CrossSystemFoldTest.column("CITY", "city"));
		CrossSystemFoldTest.check("PARIS already shared before folding (1 before, 1 after - no improvement): nothing proposed", proposals.isEmpty());

		final CrossSystemFold.SystemValues distinctEverywhere1 = new CrossSystemFold.SystemValues("CRM", Arrays.asList(new Detectors.ValueCount("Lyon", 5)),
			false);
		final CrossSystemFold.SystemValues distinctEverywhere2 = new CrossSystemFold.SystemValues("ERP",
			Arrays.asList(new Detectors.ValueCount("Marseille", 5)), false);
		final List<CrossSystemFold.AttributeProposal> neverCoincide = CrossSystemFold.propose(Arrays.asList(distinctEverywhere1, distinctEverywhere2),
			CrossSystemFoldTest.column("CITY", "city"));
		CrossSystemFoldTest.check("genuinely different values, folding does not help either: nothing proposed", neverCoincide.isEmpty());
	}

	private static void oneMappingIsNeverEnough()
	{
		final CrossSystemFold.SystemValues onlyOne = new CrossSystemFold.SystemValues("CRM", Arrays.asList(new Detectors.ValueCount("Zürich", 1)), false);
		CrossSystemFoldTest.check("a single mapping has nothing to compare against: no proposal",
			CrossSystemFold.propose(Arrays.asList(onlyOne), CrossSystemFoldTest.column("CITY", "city")).isEmpty());
		CrossSystemFoldTest.check("null input: no proposal, never throws", CrossSystemFold.propose(null, CrossSystemFoldTest.column("CITY", "city")).isEmpty());
	}

	/** The structural gate is DISTINCT source systems, not distinct mappings - two mappings of the SAME system (e.g. two columns of one ERP export) are not "fed by two source systems", however differently spelled their values are. Still proposes nothing under the 2026-09-22 redesign, unchanged. */
	private static void twoMappingsOfTheSameSystemAreNeverEnough()
	{
		final CrossSystemFold.SystemValues first = new CrossSystemFold.SystemValues("ERP", Arrays.asList(new Detectors.ValueCount("Zürich", 3)), false);
		final CrossSystemFold.SystemValues second = new CrossSystemFold.SystemValues("ERP", Arrays.asList(new Detectors.ValueCount("ZURICH", 2)), false);
		CrossSystemFoldTest.check("same sourceSystem on both mappings: structural gate not met, no proposal",
			CrossSystemFold.propose(Arrays.asList(first, second), CrossSystemFoldTest.column("CITY", "city")).isEmpty());
	}

	private static void nonTextColumnIsNeverProposed()
	{
		final CrossSystemFold.SystemValues crm = new CrossSystemFold.SystemValues("CRM", Arrays.asList(new Detectors.ValueCount("1", 1)), false);
		final CrossSystemFold.SystemValues erp = new CrossSystemFold.SystemValues("ERP", Arrays.asList(new Detectors.ValueCount("2", 1)), false);
		final Detectors.ColumnContext decimalColumn = new Detectors.ColumnContext("AMOUNT", "amount", "DECIMAL", Collections.<String>emptyList());
		CrossSystemFoldTest.check("a non-STRING column is never a candidate for this fold",
			CrossSystemFold.propose(Arrays.asList(crm, erp), decimalColumn).isEmpty());
	}

	/** Same redaction discipline as every other {@code MappingStep} kind not on {@code Detectors}' own real-value allow-list: an example is a SHAPE, never the real city name - even though it is now stored on a {@code StagingAttribute} rationale, not a {@code MappingStep} row. */
	private static void exampleShowsAShapeNeverARealCityName()
	{
		final CrossSystemFold.SystemValues crm = new CrossSystemFold.SystemValues("CRM", Arrays.asList(new Detectors.ValueCount("Zürich", 12)), false);
		final CrossSystemFold.SystemValues erp = new CrossSystemFold.SystemValues("ERP", Arrays.asList(new Detectors.ValueCount("ZURICH", 8)), false);
		final List<CrossSystemFold.AttributeProposal> proposals = CrossSystemFold.propose(Arrays.asList(crm, erp), CrossSystemFoldTest.column("CITY", "city"));
		CrossSystemFoldTest.check("proposed", proposals.size() == 1);
		final List<String> examples = proposals.get(0).examples;
		CrossSystemFoldTest.check("example never contains the real city name: " + examples,
			!CrossSystemFoldTest.anyExampleContains(examples, "Zurich") && !CrossSystemFoldTest.anyExampleContains(examples, "ZURICH"));
	}

	/** The rationale must name how many accepted steps each mapping's own pipeline already ran, not just the shared-count numbers - so a reviewer can tell the measurement reflects the data as actually loaded. */
	private static void rationaleNamesHowManyAcceptedStepsRanPerMapping()
	{
		final CrossSystemFold.SystemValues crm = new CrossSystemFold.SystemValues("CRM",
			Arrays.asList(new Detectors.ValueCount("Paris", 30), new Detectors.ValueCount("Zürich", 12)), false, 0);
		final CrossSystemFold.SystemValues erp = new CrossSystemFold.SystemValues("ERP",
			Arrays.asList(new Detectors.ValueCount("PARIS", 25), new Detectors.ValueCount("ZURICH", 8)), false, 1);
		final List<CrossSystemFold.AttributeProposal> proposals = CrossSystemFold.propose(Arrays.asList(crm, erp), CrossSystemFoldTest.column("CITY", "city"));
		CrossSystemFoldTest.check("proposed", proposals.size() == 1);
		CrossSystemFoldTest.check("rationale names each system's own accepted step count: " + proposals.get(0).rationale,
			proposals.get(0).rationale.contains("CRM: 0 accepted step(s)") && proposals.get(0).rationale.contains("ERP: 1 accepted step(s)"));
	}

	/** {@code suggestedName} is always {@code "<stagingAttributeName>Key"} - checked on a second domain (a product designation, not a company/city) so nothing here is accidentally keyed on party-data vocabulary. */
	private static void suggestedNameIsSourceAttributeNamePlusKey()
	{
		final CrossSystemFold.SystemValues erp = new CrossSystemFold.SystemValues("ERP",
			Arrays.asList(new Detectors.ValueCount("Écrou M8", 40), new Detectors.ValueCount("Rondelle", 15)), false);
		final CrossSystemFold.SystemValues plm = new CrossSystemFold.SystemValues("PLM",
			Arrays.asList(new Detectors.ValueCount("ECROU M8", 38), new Detectors.ValueCount("RONDELLE", 12)), false);
		final List<CrossSystemFold.AttributeProposal> proposals = CrossSystemFold.propose(Arrays.asList(erp, plm),
			CrossSystemFoldTest.column("DESIGNATION", "designation"));
		CrossSystemFoldTest.check("proposed on a product-designation attribute", proposals.size() == 1);
		CrossSystemFoldTest.check("suggested name is designationKey: " + proposals.get(0).suggestedName, "designationKey".equals(proposals.get(0).suggestedName));
	}

	// ------------------------------------------------------------------------------------------- applyAcceptedPipeline

	private static void applyAcceptedPipelineIsPassThroughOnAnEmptyPipeline()
	{
		final List<Detectors.ValueCount> raw = Arrays.asList(new Detectors.ValueCount("Zürich", 3));
		CrossSystemFoldTest.check("null steps: unchanged", CrossSystemFold.applyAcceptedPipeline(raw, null, null) == raw);
		CrossSystemFoldTest.check("empty steps: unchanged",
			CrossSystemFold.applyAcceptedPipeline(raw, Collections.<CleaningSteps.Step>emptyList(), null) == raw);
	}

	/** A value an accepted step turns null (e.g. EMPTY_TO_NULL matching it) never reaches the returned list; two raw values that collapse onto the same pipeline output (an accepted UPPER folding "acme"/"ACME" onto one key) have their counts summed, not duplicated. */
	private static void applyAcceptedPipelineDropsValuesTurnedNullAndMergesCollapsedCounts()
	{
		final List<CleaningSteps.Step> steps = Arrays.asList(new CleaningSteps.Step(CleaningSteps.EMPTY_TO_NULL, null), new CleaningSteps.Step(CleaningSteps.UPPER, null));
		final List<Detectors.ValueCount> raw = Arrays.asList(new Detectors.ValueCount("acme", 5), new Detectors.ValueCount("ACME", 3),
			new Detectors.ValueCount("N/A", 2));
		final List<Detectors.ValueCount> effective = CrossSystemFold.applyAcceptedPipeline(raw, steps, null);
		CrossSystemFoldTest.check("N/A dropped, acme/ACME merged into one ACME entry: " + CrossSystemFoldTest.valuesOf(effective), effective.size() == 1);
		CrossSystemFoldTest.check("counts summed (5+3=8): " + effective.get(0).count, "ACME".equals(effective.get(0).value) && effective.get(0).count == 8);
	}

	private static void hasUnsupportedScopedStepDetectsOnlyAScopedTranscodeReference()
	{
		CrossSystemFoldTest.check("null steps: supported", !CrossSystemFold.hasUnsupportedScopedStep(null));
		CrossSystemFoldTest.check("empty pipeline: supported", !CrossSystemFold.hasUnsupportedScopedStep(Collections.<CleaningSteps.Step>emptyList()));
		CrossSystemFoldTest.check("TRIM/UPPER: supported",
			!CrossSystemFold.hasUnsupportedScopedStep(Arrays.asList(new CleaningSteps.Step(CleaningSteps.TRIM, null), new CleaningSteps.Step(CleaningSteps.UPPER, null))));
		CrossSystemFoldTest.check("unscoped TRANSCODE_REFERENCE: supported",
			!CrossSystemFold.hasUnsupportedScopedStep(Arrays.asList(new CleaningSteps.Step(CleaningSteps.TRANSCODE_REFERENCE, "{\"reference\":\"COUNTRY\"}"))));
		CrossSystemFoldTest.check("scoped TRANSCODE_REFERENCE: NOT supported", CrossSystemFold.hasUnsupportedScopedStep(
			Arrays.asList(new CleaningSteps.Step(CleaningSteps.TRANSCODE_REFERENCE, "{\"reference\":\"LEGAL_FORM\",\"scopeField\":\"country\"}"))));
	}

	// --------------------------------------------------------------------------------------------- coordinator review

	/**
	 * The exact defect the coordinator measured live: {@code Company.country} fed by CRM ({@code Fr}/{@code
	 * France}/{@code Suisse}) and ERP ({@code FR}/{@code CH}), each mapping already carrying an ACCEPTED step
	 * that resolves it to the same ISO codes (COUNTRY_TO_ISO2 on CRM, unscoped TRANSCODE_REFERENCE on ERP).
	 * Measured AFTER those accepted steps (the fix), the values already coincide (FR/FR, CH/CH) - folding case
	 * and accents on top gains nothing, so NOTHING is proposed. Still true under the 2026-09-22 redesign.
	 */
	private static void countryFixtureAlreadyNormalisedByAcceptedStepsProposesNothing()
	{
		final CleaningSteps.Lookups lookups = CrossSystemFoldTest.countryLookups();
		final List<CleaningSteps.Step> crmSteps = Arrays.asList(new CleaningSteps.Step(CleaningSteps.COUNTRY_TO_ISO2, null));
		final List<CleaningSteps.Step> erpSteps = Arrays.asList(new CleaningSteps.Step(CleaningSteps.TRANSCODE_REFERENCE, "{\"reference\":\"COUNTRY\"}"));
		CrossSystemFoldTest.check("neither accepted pipeline is scoped", !CrossSystemFold.hasUnsupportedScopedStep(crmSteps) && !CrossSystemFold.hasUnsupportedScopedStep(erpSteps));

		final List<Detectors.ValueCount> crmRaw = Arrays.asList(new Detectors.ValueCount("Fr", 20), new Detectors.ValueCount("France", 15),
			new Detectors.ValueCount("Suisse", 5));
		final List<Detectors.ValueCount> erpRaw = Arrays.asList(new Detectors.ValueCount("FR", 18), new Detectors.ValueCount("CH", 4));
		final List<Detectors.ValueCount> crmEffective = CrossSystemFold.applyAcceptedPipeline(crmRaw, crmSteps, lookups);
		final List<Detectors.ValueCount> erpEffective = CrossSystemFold.applyAcceptedPipeline(erpRaw, erpSteps, lookups);
		CrossSystemFoldTest.check("CRM's three spellings collapse to FR/CH once its accepted COUNTRY_TO_ISO2 runs: " + CrossSystemFoldTest.valuesOf(crmEffective),
			crmEffective.size() == 2);
		CrossSystemFoldTest.check("ERP's codes resolve to themselves via its accepted TRANSCODE_REFERENCE: " + CrossSystemFoldTest.valuesOf(erpEffective),
			erpEffective.size() == 2);

		final CrossSystemFold.SystemValues crm = new CrossSystemFold.SystemValues("CRM", crmEffective, false, crmSteps.size());
		final CrossSystemFold.SystemValues erp = new CrossSystemFold.SystemValues("ERP", erpEffective, false, erpSteps.size());
		final List<CrossSystemFold.AttributeProposal> proposals = CrossSystemFold.propose(Arrays.asList(crm, erp), CrossSystemFoldTest.column("COUNTRY", "country"));
		CrossSystemFoldTest.check("already normalised by the accepted pipeline: NO fold proposed", proposals.isEmpty());
	}

	/**
	 * The same fixture as above, measured the WRONG way (on raw values, ignoring each mapping's own accepted
	 * pipeline) - reproduces the exact false positive the coordinator's review caught, so a future regression
	 * that goes back to measuring on raw values fails this test, not just the "real" one.
	 */
	private static void rawValueMeasurementOnTheSameCountryFixtureWouldHaveWronglyProposed()
	{
		final CrossSystemFold.SystemValues crmRaw = new CrossSystemFold.SystemValues("CRM",
			Arrays.asList(new Detectors.ValueCount("Fr", 20), new Detectors.ValueCount("France", 15), new Detectors.ValueCount("Suisse", 5)), false);
		final CrossSystemFold.SystemValues erpRaw = new CrossSystemFold.SystemValues("ERP",
			Arrays.asList(new Detectors.ValueCount("FR", 18), new Detectors.ValueCount("CH", 4)), false);
		final List<CrossSystemFold.AttributeProposal> wronglyProposed = CrossSystemFold.propose(Arrays.asList(crmRaw, erpRaw),
			CrossSystemFoldTest.column("COUNTRY", "country"));
		CrossSystemFoldTest.check("measuring on RAW text alone, 'Fr' folding to 'FR' looks like a gain - this is the exact bug the fix prevents",
			wronglyProposed.size() == 1);
	}

	/** Tonight's city defect (no accepted step on either mapping) still proposes once each mapping's EMPTY accepted pipeline is applied first - the fix must not accidentally suppress a genuine gain. */
	private static void cityFixtureStillProposesWhenNeitherMappingHasAnAcceptedStep()
	{
		final List<Detectors.ValueCount> crmRaw = Arrays.asList(new Detectors.ValueCount("Paris", 30), new Detectors.ValueCount("Zürich", 12));
		final List<Detectors.ValueCount> erpRaw = Arrays.asList(new Detectors.ValueCount("PARIS", 25), new Detectors.ValueCount("ZURICH", 8));
		final List<Detectors.ValueCount> crmEffective = CrossSystemFold.applyAcceptedPipeline(crmRaw, Collections.<CleaningSteps.Step>emptyList(), null);
		final List<Detectors.ValueCount> erpEffective = CrossSystemFold.applyAcceptedPipeline(erpRaw, null, null);
		CrossSystemFoldTest.check("empty/null accepted pipeline is a pure pass-through", crmEffective == crmRaw && erpEffective == erpRaw);

		final CrossSystemFold.SystemValues crm = new CrossSystemFold.SystemValues("CRM", crmEffective, false, 0);
		final CrossSystemFold.SystemValues erp = new CrossSystemFold.SystemValues("ERP", erpEffective, false, 0);
		final List<CrossSystemFold.AttributeProposal> proposals = CrossSystemFold.propose(Arrays.asList(crm, erp), CrossSystemFoldTest.column("CITY", "city"));
		CrossSystemFoldTest.check("still proposed: no accepted step existed to already close the gap", proposals.size() == 1);
	}

	/**
	 * A mapping whose accepted pipeline already resolves both systems onto the SAME folded value (e.g. a
	 * {@code STRIP_ACCENTS}+{@code UPPER} pair accepted under a build predating the 2026-09-22 redesign) must
	 * yield NO new proposal: the gain is then zero, falling out of the same before/after measurement, no
	 * special-cased idempotence check needed.
	 */
	private static void mappingsAlreadyResolvedToTheSameFoldedValueYieldNoNewProposal()
	{
		final List<CleaningSteps.Step> acceptedFold = Arrays.asList(new CleaningSteps.Step(CleaningSteps.STRIP_ACCENTS, null), new CleaningSteps.Step(CleaningSteps.UPPER, null));
		final List<Detectors.ValueCount> crmRaw = Arrays.asList(new Detectors.ValueCount("Zürich", 12));
		final List<Detectors.ValueCount> erpRaw = Arrays.asList(new Detectors.ValueCount("ZURICH", 8));
		final List<Detectors.ValueCount> crmEffective = CrossSystemFold.applyAcceptedPipeline(crmRaw, acceptedFold, null);
		final List<Detectors.ValueCount> erpEffective = CrossSystemFold.applyAcceptedPipeline(erpRaw, acceptedFold, null);
		CrossSystemFoldTest.check("both already resolve to ZURICH via their own accepted pipeline: " + CrossSystemFoldTest.valuesOf(crmEffective) + " / "
			+ CrossSystemFoldTest.valuesOf(erpEffective), "ZURICH".equals(crmEffective.get(0).value) && "ZURICH".equals(erpEffective.get(0).value));

		final CrossSystemFold.SystemValues crm = new CrossSystemFold.SystemValues("CRM", crmEffective, false, acceptedFold.size());
		final CrossSystemFold.SystemValues erp = new CrossSystemFold.SystemValues("ERP", erpEffective, false, acceptedFold.size());
		final List<CrossSystemFold.AttributeProposal> proposals = CrossSystemFold.propose(Arrays.asList(crm, erp), CrossSystemFoldTest.column("CITY", "city"));
		CrossSystemFoldTest.check("no new proposal on a re-run", proposals.isEmpty());
	}

	/**
	 * Coordinator review item 5: a value shared by two MAPPINGS of the SAME system must never count as "shared
	 * across systems" - only when folding onto the SAME key requires a genuinely different system to already
	 * carry it. Three mappings: two ERP columns (redundant, one spelled with an accent, the other without) plus
	 * one unrelated CRM value, satisfying the {@code >= 2 distinct systems} structural gate on their own but
	 * never actually meeting each other.
	 */
	private static void sharedCountIsBySourceSystemNotByMappingIndex()
	{
		final CrossSystemFold.SystemValues erp1 = new CrossSystemFold.SystemValues("ERP", Arrays.asList(new Detectors.ValueCount("Zürich", 6)), false);
		final CrossSystemFold.SystemValues erp2 = new CrossSystemFold.SystemValues("ERP", Arrays.asList(new Detectors.ValueCount("ZURICH", 4)), false);
		final CrossSystemFold.SystemValues crm = new CrossSystemFold.SystemValues("CRM", Arrays.asList(new Detectors.ValueCount("Paris", 9)), false);
		final List<CrossSystemFold.AttributeProposal> proposals = CrossSystemFold.propose(Arrays.asList(erp1, erp2, crm), CrossSystemFoldTest.column("CITY", "city"));
		CrossSystemFoldTest.check("two mappings of the SAME system folding onto the same key is not cross-system evidence: no proposal", proposals.isEmpty());
	}

	/** A second domain (site names, never only companies/cities): a Snowflake export and a manual spreadsheet disagree on accents/case for the same physical sites. */
	private static void productExampleFromADifferentDomainStillProposes()
	{
		final CrossSystemFold.SystemValues snowflake = new CrossSystemFold.SystemValues("SNOWFLAKE",
			Arrays.asList(new Detectors.ValueCount("Site de Montréal", 7), new Detectors.ValueCount("Site de Genève", 3)), false);
		final CrossSystemFold.SystemValues spreadsheet = new CrossSystemFold.SystemValues("SPREADSHEET",
			Arrays.asList(new Detectors.ValueCount("SITE DE MONTREAL", 6), new Detectors.ValueCount("SITE DE GENEVE", 2)), false);
		final List<CrossSystemFold.AttributeProposal> proposals = CrossSystemFold.propose(Arrays.asList(snowflake, spreadsheet),
			CrossSystemFoldTest.column("SITE_NAME", "siteName"));
		CrossSystemFoldTest.check("proposed on a site-name attribute", proposals.size() == 1);
		CrossSystemFoldTest.check("suggested name is siteNameKey: " + proposals.get(0).suggestedName, "siteNameKey".equals(proposals.get(0).suggestedName));
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	/** A tiny Country reference table (code -&gt; label), the shape {@code ebx.QualitySuggester.buildLookups} builds from a {@code ReferenceBinding} - used to prove {@code CleaningSteps.applyPipeline} resolves an accepted COUNTRY_TO_ISO2/TRANSCODE_REFERENCE step exactly as the loader would. */
	private static CleaningSteps.Lookups countryLookups()
	{
		final Map<String, List<String>> rows = new LinkedHashMap<String, List<String>>();
		rows.put("FR", Arrays.asList("France"));
		rows.put("CH", Arrays.asList("Suisse"));
		final Map<String, String> flat = CleaningSteps.buildFlatTable(rows, null);
		final Map<String, Map<String, String>> byKind = new LinkedHashMap<String, Map<String, String>>();
		byKind.put("COUNTRY", flat);
		return new CleaningSteps.Lookups(byKind, null);
	}

	private static List<String> valuesOf(final List<Detectors.ValueCount> pValues)
	{
		final List<String> out = new java.util.ArrayList<String>();
		for (final Detectors.ValueCount vc : pValues)
		{
			out.add(vc.value + "(" + vc.count + ")");
		}
		return out;
	}

	private static Detectors.ColumnContext column(final String pSourceColumnName, final String pStagingAttributeName)
	{
		return new Detectors.ColumnContext(pSourceColumnName, pStagingAttributeName, "STRING", Collections.<String>emptyList());
	}

	private static boolean anyExampleContains(final List<String> pExamples, final String pNeedle)
	{
		for (final String example : pExamples)
		{
			if (example.contains(pNeedle))
			{
				return true;
			}
		}
		return false;
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
