package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.CrossSystemFold;
import com.on.ebx.factory.quality.Detectors;
import com.on.ebx.factory.quality.QualityDetectors;
import com.on.ebx.factory.quality.QualityRuleCheck;
import com.on.ebx.factory.quality.ScanGuard;
import com.on.ebx.factory.quality.ValidValueDerivation;
import com.on.ebx.factory.quality.WithdrawalRule;
import com.on.ebx.factory.review.ProposalWording;
import com.on.ebx.factory.staging.MappingScope;
import com.on.ebx.factory.staging.NormalisationPass;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.query.CteQueryNotImplementableException;
import com.orchestranetworks.query.Query;
import com.orchestranetworks.query.QueryParseException;
import com.orchestranetworks.query.QueryResult;
import com.orchestranetworks.query.Tuple;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The detection pass of "Normalisation / qualite entre landing et staging" (PLAN.md, 2026-09-20 soir;
 * design in docs/NORMALISATION.md, lots 1/2/4): for every ACCEPTED-or-APPLIED {@code AttributeMapping} of a
 * project, reads its landing column's distinct values WITH COUNTS inside EBX, runs the pure detectors
 * ({@code quality.Detectors} for cleanup steps, {@code quality.QualityDetectors} for validation rules)
 * against them, and writes {@code MappingStep}/{@code QualityRule} proposals - origin {@code RULE}, status
 * {@code PROPOSED}. Thin on purpose, the same discipline {@code StagingSuggester}/{@code RelationChecker}
 * already follow: every decision that does not need EBX lives in the pure {@code quality} package, unit
 * tested without a running instance; this class only drives the reads/writes around it. Nothing leaves the
 * instance and no language model is called in this lot (the task's own scope).
 * <p>
 * <b>Domain-free by construction since the quality/LLM lot (2026-09-21, product owner review)</b>: this pass
 * used to also propose country/currency/legal-form transcoding, identifier check digits and a BIC shape check
 * - all decided from a column NAME or a catalog TAG, which only ever worked for party/company data. Those
 * families are gone from {@code quality.Detectors}/{@code quality.QualityDetectors} (see their own Javadoc);
 * without a language model configured, this route proposes only what is domain-free by nature: {@code TRIM}/
 * {@code COLLAPSE_SPACES}/{@code EMPTY_TO_NULL} (cleanup) and {@code UNIQUE} (a statistical near-uniqueness
 * signal, no name involved). The same rule families a name-hinted proposer used to cover - country/currency/
 * legal-form/identifier/pattern - are now proposed by {@code ebx.QualityLlmSuggester} instead, from metadata
 * (and, opt-in per project, a sample of real values), each one MEASURED against the real data before it is
 * ever stored. The EXECUTORS below ({@link #buildLookups}, {@code quality.CleaningSteps}, {@code
 * quality.QualityRuleCheck}) are unchanged: a row accepted under the old heuristics keeps executing exactly as
 * before. {@link #buildLookups} is called from {@link #suggest} itself again since the 2026-09-21 cross-system
 * fold fix below (it had briefly been dropped from this method when the name-hinted proposers were removed) -
 * not to propose a lookup-based step (this pass still never does), but to REPLAY a mapping's own already-
 * accepted {@code COUNTRY_TO_ISO2}/{@code CURRENCY_TO_ISO}/unscoped {@code TRANSCODE_REFERENCE} step before
 * {@link CrossSystemFold} ever compares its values against another mapping's.
 * <p>
 * <b>Idempotent, the same rule {@code MappingStep}/{@code QualityRule} share with every other proposal table
 * in this codebase</b>: a step (or rule) of the same {@code kind} already present for the same mapping (or
 * entity/attribute) is left ALONE, whatever its own status - a person's ACCEPTED/REJECTED decision, or an
 * already-APPLIED row, is never touched. The one exception is a still-{@code PROPOSED} row of a kind already
 * proposed: its evidence fields ({@code valuesSeen}/{@code valuesChanged}/{@code valuesUnresolved}/{@code
 * valuesFailing}/{@code examples}/{@code checkedAt}) are refreshed in place, so a relaunch after more landing
 * data has loaded shows current numbers without duplicating the row or losing a still-pending review.
 * <p>
 * <b>Distinct values, with counts, read INSIDE EBX</b> ({@link #distinctValues}): one {@code GROUP BY} SQL
 * statement per (mapping, or mapping+scope-column pair) via {@code Adaptation.createQuery} - the same
 * {@code Query<Tuple>}/{@code QueryResult<Tuple>} iterate-then-close shape {@code RelationChecker} already
 * established, every identifier double-quoted regardless of whether it looks like a reserved word (the
 * knowledge base's own "just always quote" rule) - falling back, for one column only, to a bounded
 * {@code RequestResult} table scan when the EBX SQL engine refuses the generated statement ({@link
 * QueryParseException}/{@link CteQueryNotImplementableException}, both unchecked - the same two exceptions
 * {@code RelationChecker} already catches for the same reason). Capped at {@code pMaxDistinctValues}
 * (default {@link #DEFAULT_MAX_DISTINCT_VALUES}) - a column with more distinct values than that is still
 * profiled, just on its most frequent ones, never the whole table.
 * <p>
 * <b>Reference lookups, loaded once per run</b> ({@link #buildLookups}): reads every {@code ReferenceBinding}
 * row and the reference table it names (small, fixed reference sets - the whole table is read, not capped),
 * building one {@link CleaningSteps.Lookups} shared by every mapping this run touches - never re-read per
 * column. A binding whose target dataspace/dataset/table cannot be found is skipped with a note, never
 * failing the whole run (the same "degrade gracefully when a module/dataset is missing" principle PLAN.md
 * states for the catalog and Connectors).
 * <p>
 * <b>Examples are safe BY CONSTRUCTION, not by a guess here</b> (coordinator review, 2026-09-20: an earlier
 * cut of this lot decided real-value-vs-shape from a catalog-tag/table-name heuristic in THIS class - wrong
 * once, and a real name/e-mail/phone/identifier lands in a proposals table, a review screen, and any export
 * of either). The decision now lives entirely in {@code quality.Detectors} itself, keyed on the step KIND
 * alone (a small, reviewed allow-list of kinds whose values are reference vocabulary by nature) - this class
 * writes {@code proposal.examples} exactly as the pure layer produced it, with no redaction step of its own
 * (see {@link #examplesText}, and docs/NORMALISATION.md for the one-paragraph summary of what is stored and
 * why).
 * <p>
 * <b>{@code QualityRule} proposals are gone from this pass entirely (coordinator review, 2026-09-21):</b>
 * {@code UNIQUE} - the last kind {@code QualityDetectors} itself still proposed - moved to the language
 * model, since "mostly distinct" is a statistic no Java rule can turn into "uniqueness is expected here"
 * without business knowledge. This route only ever writes {@code MappingStep} rows now; {@code QualityRule}
 * proposals come exclusively from {@code ebx.QualityLlmSuggester}. The withdrawal sweep below still applies to
 * both tables, since an old RULE-origin {@code UNIQUE} row must be swept the same way as any other removed
 * heuristic's leftovers.
 * <p>
 * <b>Every number that gates a decision or is shown as context is EXACT SQL, never the capped sample</b>
 * (coordinator review, 2026-09-21): {@link #exactAggregates} reads {@code COUNT(*)}/{@code COUNT}(non-blank)/
 * {@code COUNT(DISTINCT}(non-blank{@code )} for every mapped column over the WHOLE landing table, and {@link
 * Detectors#propose(List, Detectors.ColumnContext, long)} is given the exact distinct count so its own
 * evidence text says plainly when it is reporting on a SAMPLE (the {@link #distinctValues} cap, raised to
 * 2000, still gives the shapes/pattern-testing material - it was never meant to BE the whole column, only a
 * caller had been treating it that way).
 * <p>
 * <b>Withdrawal of stale proposals</b> (coordinator finding, 2026-09-21 on real project {@code Companies}):
 * {@link #readAcceptedMappings} already filters to accepted-like {@code AttributeMapping} rows only, so a
 * rejected mapping's landing column is never profiled again and no NEW rule/step is ever proposed from it -
 * but a rule/step PROPOSED from a mapping that was accepted at the time and later rejected is never revisited
 * by the create-or-refresh loop above once no accepted mapping feeds its attribute/mapping any more (it is
 * keyed on the attribute, not the mapping, and simply falls out of every future run's scope). {@link
 * #withdrawStaleProposals}, run once per {@link #suggest} call after the main pass, applies {@code
 * quality.WithdrawalRule#decide} to every {@code QualityRule}/{@code MappingStep} of the project: a
 * still-{@code PROPOSED} row is deleted (nobody decided on it - see that class's own Javadoc for why deletion,
 * not a status, is the right mechanism), a row a person already decided on is left untouched and only counted
 * ({@code rulesFlaggedStale}/{@code stepsFlaggedStale} in the response) - the review screen (task B) surfaces
 * the same coverage check on the line itself, computed the same way, so a reviewer sees exactly which decided
 * row's evidence is now stale.
 */
public final class QualitySuggester
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String STEP = "QUALITY_SUGGEST";
	/**
	 * The {@code GROUP BY} sample cap - a SAMPLE, never the whole column (coordinator review, 2026-09-21:
	 * raised from 200 to 2000 so it is actually useful for measurement; a gate or a context number must never
	 * be computed from it - see {@link #exactAggregates}). The prompt itself is unaffected: it only ever shows
	 * {@code QualityLlmSuggester.MAX_SHAPES_SHOWN} shapes and {@code Project.llmSampleSize} real values.
	 */
	static final int DEFAULT_MAX_DISTINCT_VALUES = 2000;
	private static final int RATIONALE_MAX_LENGTH = 2000;
	private static final int EXAMPLES_MAX_LENGTH = 2000;

	private QualitySuggester()
	{
	}

	// ============================================================================================== entry point

	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Integer pMaxDistinctValues)
		throws OperationException
	{
		return QualitySuggester.suggest(pSession, pFactory, pProjectCode, pMaxDistinctValues, MappingScope.ACCEPTED_ONLY);
	}

	/**
	 * Same pass over {@code pScope}'s mappings. {@link MappingScope#PROPOSAL} is what the one-click staging proposal
	 * run needs (PersVol2 volume test, 2026-09-25): run only from {@code quality/suggest} on ACCEPTED mappings, this
	 * pass - the only one that proposes {@code FOLD_KEY} matching keys ({@code CrossSystemFold}) - never ran in the
	 * wizard, so the persons of an ERP writing names in upper case without accents were compared raw and the
	 * matching found 48 % of the duplicates. Everything it writes is still PROPOSED, reviewed with the rest.
	 */
	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Integer pMaxDistinctValues,
		final MappingScope pScope) throws OperationException
	{
		final Adaptation project = QualitySuggester.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		// delivery lot 1: a project delivered here by EBX Staging is designed elsewhere - no suggestion on a target
		ProjectOrigin.refuseIfDelivered(pFactory, project);
		final String projectId = project.getOccurrencePrimaryKey().format();
		final String landingDataspace = project.getString(FactoryPaths.Project.LANDING_DATASPACE);
		if (landingDataspace == null || landingDataspace.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.noLandingTable", "Project " + pProjectCode + " has no landing zone yet: plan and generate the landing of at least one source first.", pProjectCode);
		}
		final int cap = pMaxDistinctValues != null && pMaxDistinctValues.intValue() > 0 ? pMaxDistinctValues.intValue() : QualitySuggester.DEFAULT_MAX_DISTINCT_VALUES;

		// this pass itself still proposes nothing name-hinted (country/currency/legal-form/check-digit/BIC -
		// product owner review, 2026-09-21, see quality.Detectors/quality.QualityDetectors) - but the cross-
		// system fold pass BELOW needs these same lookups to replay a mapping's own ACCEPTED-or-APPLIED
		// COUNTRY_TO_ISO2/CURRENCY_TO_ISO/TRANSCODE_REFERENCE step before comparing values (coordinator review,
		// 2026-09-21: measuring on raw values instead of already-normalised ones is a real defect - see
		// CrossSystemFold.applyAcceptedPipeline's own Javadoc)
		final List<String> notes = new ArrayList<String>();
		final CleaningSteps.Lookups lookups = QualitySuggester.buildLookups(pFactory, notes);
		final List<MappingRow> mappings = QualitySuggester.readMappings(pFactory, projectId, pScope);

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, QualitySuggester.STEP, "");
		final ObjectNode out = QualitySuggester.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("mappings", mappings.size());
		out.put("maxDistinctValues", cap);
		final ArrayNode columnsOut = out.putArray("columns");
		final Counters counters = new Counters();
		final Map<String, LandingTableRef> landingTableCache = new HashMap<String, LandingTableRef>();
		// kept from the main loop below so the cross-system fold pass (structural, task 2026-09-21) never
		// re-reads the landing data a second time: it reuses the SAME sample/exact-aggregate pair per mapping,
		// the same bound (cap) and sampling discipline as the single-column pass above - see CrossSystemFold's
		// own Javadoc for why this cannot be expressed as one more Detectors family.
		final Map<String, ColumnEvidence> evidenceByMappingId = new HashMap<String, ColumnEvidence>();
		// one entry per attribute the cross-system fold pass could not safely measure (coordinator review,
		// 2026-09-21: a scoped TRANSCODE_REFERENCE among a mapping's own accepted steps needs a row-level scope
		// value no distinct-value sample carries - see CrossSystemFold.hasUnsupportedScopedStep) - a MISSING
		// proposal is reported here rather than a false one silently written
		final List<String> crossSystemFoldNotMeasured = new ArrayList<String>();
		// filled in by proposeCrossSystemFolds, one attribute id per attribute it actually attempted to measure
		// this run (>= 2 measured mappings, no unsupported scoped step) - what withdrawStaleFoldKeyAttributes
		// below needs to tell "no gain any more" apart from "not even checked this run" for an EXISTING FOLD_KEY
		// attribute (see that method's own Javadoc).
		final Set<String> attributesMeasuredForFoldKey = new HashSet<String>();
		// subset of attributesMeasuredForFoldKey whose measurement found a genuine gain this run
		final Set<String> attributesWithFoldKeyGain = new HashSet<String>();
		// lot 1 (2026-09-23, quality-informed survivorship): the VALID_VALUE siblings of the four sets just
		// above - see proposeValidValueDerivations' own Javadoc.
		final List<String> validValueNotMeasured = new ArrayList<String>();
		final Set<String> attributesMeasuredForValidValue = new HashSet<String>();
		final Set<String> attributesWithValidValueGain = new HashSet<String>();

		try
		{
			for (final MappingRow mapping : mappings)
			{
				final LandingTableRef landingRef = QualitySuggester.landingTableRef(pFactory, landingDataspace, mapping, landingTableCache);
				if (landingRef == null)
				{
					counters.columnsSkippedNoLandingTable++;
					continue;
				}
				final ColumnQuery query = QualitySuggester.distinctValues(pSession, landingRef, mapping.fieldName, null, cap);
				// EXACT, never derived from query.values (see the class Javadoc) - the sole source of truth for
				// whether the sample above actually covered the whole column
				final ExactAggregates aggregates = QualitySuggester.exactAggregates(pSession, landingRef, mapping.fieldName);
				evidenceByMappingId.put(mapping.mappingId, new ColumnEvidence(query, aggregates));

				final Detectors.ColumnContext column = new Detectors.ColumnContext(mapping.sourceColumn, mapping.attributeName, mapping.dataType, mapping.tags);
				final List<Detectors.StepProposal> stepProposals = Detectors.propose(query.values, column, aggregates.distinctCount, query.sampledRows);

				final ObjectNode columnOut = columnsOut.addObject();
				columnOut.put("entity", mapping.entityName);
				columnOut.put("attribute", mapping.attributeName);
				columnOut.put("landingColumn", mapping.sourceColumn);
				columnOut.put("distinctValuesRead", query.values.size());
				columnOut.put("exactDistinctCount", aggregates.distinctCount);
				columnOut.put("sampled", aggregates.distinctCount > query.values.size() || query.sampled());
				if (query.sampled())
				{
					columnOut.put("sampleRows", query.sampledRows); // staging polish (2026-10-17): the column was read on its first rows only (ScanGuard)
				}
				if (aggregates.distinctCapReached)
				{
					columnOut.put("exactDistinctCapReached", true); // audit F-50: exactDistinctCount is a lower bound
				}
				columnOut.put("path", query.path);
				columnOut.put("stepsProposed", stepProposals.size());

				QualitySuggester.writeMappingSteps(pSession, pFactory, mapping, stepProposals, counters);
			}
			// 2026-09-22: no longer writes a MappingStep at all (see CrossSystemFold's own Javadoc), so the old
			// "position it after this mapping's own single-column steps" concern is gone - a FOLD_KEY
			// StagingAttribute has no pipeline position of its own. attributesMeasuredForFoldKey/
			// attributesWithFoldKeyGain are filled in as a side effect, for withdrawStaleFoldKeyAttributes below.
			// byAttribute is grouped ONCE and reused by both this pass and proposeValidValueDerivations below -
			// see groupMappingsByAttribute's own Javadoc.
			final Map<String, List<MappingRow>> byAttribute = QualitySuggester.groupMappingsByAttribute(mappings, evidenceByMappingId);
			QualitySuggester.proposeCrossSystemFolds(pSession, pFactory, byAttribute, evidenceByMappingId, lookups, counters, crossSystemFoldNotMeasured,
				attributesMeasuredForFoldKey, attributesWithFoldKeyGain);
			// lot 1 (2026-09-23, quality-informed survivorship, docs/MATCHING.md sec 23): the VALID_VALUE sibling
			// pass - see proposeValidValueDerivations' own Javadoc for why it needs no >= 2 mappings gate.
			QualitySuggester.proposeValidValueDerivations(pSession, pFactory, byAttribute, evidenceByMappingId, lookups, counters, validValueNotMeasured,
				attributesMeasuredForValidValue, attributesWithValidValueGain);
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), out.toString());
			throw ex;
		}
		catch (final RuntimeException | Error ex)
		{
			// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
			StepGuard.endUnexpected(pSession, pFactory, stepId, ex, out.toString());
			throw ex;
		}

		// coordinator finding, 2026-09-21 (real Companies data): a still-PROPOSED MappingStep/QualityRule whose
		// evidence came from a mapping later REJECTED is never revisited by the loop above once no accepted
		// mapping feeds its attribute any more - WithdrawalRule.decide answers what to do about it
		final Set<String> coveredAttributeIds = new HashSet<String>();
		final Set<String> coveredMappingIds = new HashSet<String>();
		for (final MappingRow mapping : mappings)
		{
			coveredAttributeIds.add(mapping.attributeId);
			coveredMappingIds.add(mapping.mappingId);
		}
		final WithdrawalCounters withdrawalCounters;
		final FoldKeyWithdrawalCounters foldKeyWithdrawalCounters;
		final ValidValueWithdrawalCounters validValueWithdrawalCounters;
		try
		{
			withdrawalCounters = QualitySuggester.withdrawStaleProposals(pSession, pFactory, projectId, coveredAttributeIds, coveredMappingIds);
			foldKeyWithdrawalCounters = QualitySuggester.withdrawStaleFoldKeyAttributes(pSession, pFactory, projectId, attributesMeasuredForFoldKey,
				attributesWithFoldKeyGain);
			validValueWithdrawalCounters = QualitySuggester.withdrawStaleValidValueAttributes(pSession, pFactory, projectId, attributesMeasuredForValidValue,
				attributesWithValidValueGain);
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), out.toString());
			throw ex;
		}
		catch (final RuntimeException | Error ex)
		{
			// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
			StepGuard.endUnexpected(pSession, pFactory, stepId, ex, out.toString());
			throw ex;
		}

		out.put("stepsCreated", counters.stepsCreated);
		out.put("stepsRefreshed", counters.stepsRefreshed);
		out.put("stepsLeftAlone", counters.stepsLeftAlone);
		out.put("stepsWithdrawn", withdrawalCounters.stepsWithdrawn);
		out.put("stepsFlaggedStale", withdrawalCounters.stepsFlagged);
		// no rulesCreated/rulesRefreshed/rulesLeftAlone any more - this pass never writes a QualityRule (see the
		// class Javadoc); rulesWithdrawn/rulesFlaggedStale stay meaningful, the withdrawal sweep still covers
		// QualityRule rows (an old RULE-origin UNIQUE row must be swept exactly like any other removed heuristic)
		out.put("rulesWithdrawn", withdrawalCounters.rulesWithdrawn);
		out.put("rulesFlaggedStale", withdrawalCounters.rulesFlagged);
		out.put("columnsSkippedNoLandingTable", counters.columnsSkippedNoLandingTable);
		out.put("foldKeyAttributesProposed", counters.foldKeyAttributesProposed);
		out.put("foldKeyAttributesLeftAlone", counters.foldKeyAttributesLeftAlone);
		out.put("foldKeyAttributesNameCollision", counters.foldKeyAttributesNameCollision);
		out.put("foldKeyAttributesWithdrawn", foldKeyWithdrawalCounters.withdrawn);
		out.put("foldKeyAttributesFlaggedStale", foldKeyWithdrawalCounters.flagged);
		// lot 1 (2026-09-23, quality-informed survivorship) - the VALID_VALUE siblings of the foldKeyAttributes* counters above.
		out.put("validValueAttributesProposed", counters.validValueAttributesProposed);
		out.put("validValueAttributesLeftAlone", counters.validValueAttributesLeftAlone);
		out.put("validValueAttributesNameCollision", counters.validValueAttributesNameCollision);
		out.put("validValueAttributesWithdrawn", validValueWithdrawalCounters.withdrawn);
		out.put("validValueAttributesFlaggedStale", validValueWithdrawalCounters.flagged);
		final ArrayNode notesNode = out.putArray("notes");
		for (final String note : notes)
		{
			notesNode.add(note);
		}
		final ArrayNode crossSystemFoldNotMeasuredNode = out.putArray("crossSystemFoldNotMeasured");
		for (final String entry : crossSystemFoldNotMeasured)
		{
			crossSystemFoldNotMeasuredNode.add(entry);
		}
		final ArrayNode validValueNotMeasuredNode = out.putArray("validValueNotMeasured");
		for (final String entry : validValueNotMeasured)
		{
			validValueNotMeasuredNode.add(entry);
		}
		final String message = counters.stepsCreated + " cleaning step(s), " + counters.foldKeyAttributesProposed + " matching key attribute(s) and "
			+ counters.validValueAttributesProposed + " valid-value attribute(s) proposed, over " + mappings.size() + " mapped column(s).";
		PipelineSteps.end(pSession, pFactory, stepId, true, message, out.toString());
		return out;
	}

	private static final class Counters
	{
		int stepsCreated;
		int stepsRefreshed;
		int stepsLeftAlone;
		int columnsSkippedNoLandingTable;
		/** New {@code FOLD_KEY} {@code StagingAttribute} rows created this run (2026-09-22 redesign - replaces the pre-2026-09-22 per-mapping {@code STRIP_ACCENTS}/{@code UPPER} {@code MappingStep} pair count). */
		int foldKeyAttributesProposed;
		/** A {@code FOLD_KEY} attribute already existed for this source attribute, in ANY status - idempotence (see {@code CrossSystemFold}'s own Javadoc): nothing written, not even a re-proposal of a REJECTED one. */
		int foldKeyAttributesLeftAlone;
		/** A gain was measured, but an attribute already named {@code "<source>Key"} exists on the entity (any status/origin) - skipped rather than risk two {@code StagingAttribute} rows with the same name on one entity (a duplicate field name the staging model has no way to build). */
		int foldKeyAttributesNameCollision;
		/** Lot 1 (2026-09-23, quality-informed survivorship) - the {@code VALID_VALUE} sibling of {@link #foldKeyAttributesProposed}. */
		int validValueAttributesProposed;
		/** The {@code VALID_VALUE} sibling of {@link #foldKeyAttributesLeftAlone}. */
		int validValueAttributesLeftAlone;
		/** The {@code VALID_VALUE} sibling of {@link #foldKeyAttributesNameCollision}. */
		int validValueAttributesNameCollision;
	}

	/** One mapping's own {@link ColumnQuery}/{@link ExactAggregates} pair, kept from the main per-mapping loop so {@link #proposeCrossSystemFolds} never re-reads the landing data a second time. */
	private static final class ColumnEvidence
	{
		final ColumnQuery query;
		final ExactAggregates aggregates;

		ColumnEvidence(final ColumnQuery pQuery, final ExactAggregates pAggregates)
		{
			this.query = pQuery;
			this.aggregates = pAggregates;
		}
	}

	/**
	 * One entry per attribute fed by at least one MEASURED mapping (a landing table resolved - {@link
	 * #evidenceByMappingId} covers exactly those) - shared by {@link #proposeCrossSystemFolds} and (lot 1,
	 * 2026-09-23) {@link #proposeValidValueDerivations}, so the grouping itself is computed once per {@code
	 * suggest()} call, never twice for two passes that both need "this attribute's own accepted mappings".
	 */
	private static Map<String, List<MappingRow>> groupMappingsByAttribute(final List<MappingRow> pMappings, final Map<String, ColumnEvidence> pEvidenceByMappingId)
	{
		final Map<String, List<MappingRow>> byAttribute = new LinkedHashMap<String, List<MappingRow>>();
		for (final MappingRow mapping : pMappings)
		{
			if (!pEvidenceByMappingId.containsKey(mapping.mappingId))
			{
				continue; // no landing table resolved for this mapping (already counted as columnsSkippedNoLandingTable) - nothing measured
			}
			List<MappingRow> attributeMappings = byAttribute.get(mapping.attributeId);
			if (attributeMappings == null)
			{
				attributeMappings = new ArrayList<MappingRow>();
				byAttribute.put(mapping.attributeId, attributeMappings);
			}
			attributeMappings.add(mapping);
		}
		return byAttribute;
	}

	/**
	 * For every attribute fed by two or more mappings that were actually measured ({@code pByAttribute}, {@link
	 * #groupMappingsByAttribute}'s own output), asks {@link CrossSystemFold#propose} whether a matching key
	 * (accents and case folded away) would make their values coincide - measured AFTER each mapping's own
	 * ACCEPTED-or-APPLIED {@code MappingStep} pipeline, never on raw landing text (coordinator review,
	 * 2026-09-21: {@code Company.country}'s CRM/ERP mappings already carry an accepted {@code
	 * COUNTRY_TO_ISO2}/{@code TRANSCODE_REFERENCE} that resolves them to the same ISO codes - measuring on raw
	 * text would have proposed a redundant, misleadingly-justified key; see {@link
	 * CrossSystemFold#applyAcceptedPipeline}'s own Javadoc for the exact worked example). When folding still
	 * gains, ONE {@code StagingAttribute} row ({@code derivation} {@code FOLD_KEY}) is written via {@link
	 * #writeDerivedFoldKeyAttribute} - a 2026-09-22 redesign from the previous {@code MappingStep} pair written
	 * to every mapping (see {@link CrossSystemFold}'s own Javadoc for why the old shape rewrote the display
	 * attribute's stored value); this method only decides WHICH attribute gets a proposal.
	 * <p>
	 * A single-mapping attribute, or one whose mappings all come from the same {@code sourceSystem}, is a no-op
	 * inside {@link CrossSystemFold#propose} itself (the structural gate) - this method still calls it for
	 * every attribute with two-or-more measured mappings rather than pre-filtering by distinct system count, so
	 * the one place that decides "is this really cross-system" stays {@link CrossSystemFold}, never duplicated
	 * here.
	 * <p>
	 * <b>An attribute with a SCOPED {@code TRANSCODE_REFERENCE} among any of its mappings' accepted steps is
	 * skipped entirely</b>, never measured on a guess: {@link CrossSystemFold#hasUnsupportedScopedStep} answers
	 * why (the row-level scope value it needs is not carried by a plain single-column distinct-value sample) -
	 * {@code pNotMeasured} records one line per skipped attribute instead of silently doing nothing or silently
	 * measuring it wrong; such an attribute is also never added to {@code pAttributesMeasured} (see that
	 * parameter's own Javadoc), so {@link #withdrawStaleFoldKeyAttributes} leaves any of its existing rows alone.
	 *
	 * @param pAttributesMeasured out-param: every attribute id this call actually reached the measurement point
	 * for (>= 2 measured mappings, no unsupported scoped step) - whether or not a gain was found. Consumed by
	 * {@link #withdrawStaleFoldKeyAttributes}, which must tell "measured, no gain any more" (withdraw/flag)
	 * apart from "not even checked this run" (leave alone) for an EXISTING {@code FOLD_KEY} row.
	 * @param pAttributesWithGain out-param, subset of {@code pAttributesMeasured}: the attributes whose
	 * measurement found a genuine gain this run (a new proposal was written, or would have been but for
	 * idempotence/a name collision - either way the evidence is fresh).
	 */
	private static void proposeCrossSystemFolds(final Session pSession, final Adaptation pFactory, final Map<String, List<MappingRow>> pByAttribute,
		final Map<String, ColumnEvidence> pEvidenceByMappingId, final CleaningSteps.Lookups pLookups, final Counters pCounters, final List<String> pNotMeasured,
		final Set<String> pAttributesMeasured, final Set<String> pAttributesWithGain) throws OperationException
	{
		for (final List<MappingRow> attributeMappings : pByAttribute.values())
		{
			if (attributeMappings.size() < 2)
			{
				continue; // CrossSystemFold's own gate needs at least two mappings to compare in the first place
			}
			// every mapping of one StagingAttribute shares the same source column name/type/tags read off the
			// StagingAttribute itself - any one of them is a valid representative for the column context/messages
			final MappingRow representative = attributeMappings.get(0);
			final String attributeLabel = representative.entityName + "." + representative.attributeName;

			// task D's own reader, reused verbatim (not re-implemented) so "accepted pipeline" means EXACTLY what
			// ebx.StagingLoader means by it - see StagingLoader.readCleaningSteps' own Javadoc
			final Map<String, List<CleaningSteps.Step>> acceptedStepsByMapping = new LinkedHashMap<String, List<CleaningSteps.Step>>();
			boolean hasUnsupportedScopedStep = false;
			String unsupportedStepKind = null;
			for (final MappingRow mapping : attributeMappings)
			{
				final List<CleaningSteps.Step> steps = StagingLoader.readCleaningSteps(pFactory, mapping.mappingId);
				acceptedStepsByMapping.put(mapping.mappingId, steps);
				if (CrossSystemFold.hasUnsupportedScopedStep(steps))
				{
					hasUnsupportedScopedStep = true;
					unsupportedStepKind = CleaningSteps.TRANSCODE_REFERENCE;
				}
			}
			if (hasUnsupportedScopedStep)
			{
				pNotMeasured.add(attributeLabel + ": step " + unsupportedStepKind
					+ " cannot be evaluated here (a scoped reference needs a row-level scope value no distinct-value sample carries) - no matching key proposed.");
				continue;
			}
			// measured this run (whether or not a gain is found below) - see this method's own Javadoc on pAttributesMeasured
			pAttributesMeasured.add(representative.attributeId);

			final List<CrossSystemFold.SystemValues> bySystem = new ArrayList<CrossSystemFold.SystemValues>();
			for (final MappingRow mapping : attributeMappings)
			{
				final ColumnEvidence evidence = pEvidenceByMappingId.get(mapping.mappingId);
				final boolean sampled = evidence.aggregates.distinctCount > evidence.query.values.size() || evidence.query.sampled(); // staging polish (2026-10-17): or a row sample
				final List<CleaningSteps.Step> acceptedSteps = acceptedStepsByMapping.get(mapping.mappingId);
				// coordinator review, 2026-09-21: measured AFTER the mapping's own accepted pipeline, exactly as
				// ebx.StagingLoader would leave the value before its own (unchanged) transformation - never raw
				final List<Detectors.ValueCount> effectiveValues = CrossSystemFold.applyAcceptedPipeline(evidence.query.values, acceptedSteps, pLookups);
				bySystem.add(new CrossSystemFold.SystemValues(mapping.sourceSystem, effectiveValues, sampled, acceptedSteps.size()));
			}
			final Detectors.ColumnContext column = new Detectors.ColumnContext(representative.sourceColumn, representative.attributeName, representative.dataType,
				representative.tags);
			final List<CrossSystemFold.AttributeProposal> proposals = CrossSystemFold.propose(bySystem, column);
			if (proposals.isEmpty())
			{
				continue;
			}
			pAttributesWithGain.add(representative.attributeId);
			QualitySuggester.writeDerivedFoldKeyAttribute(pSession, pFactory, representative, proposals.get(0), pCounters);
		}
	}

	/**
	 * Lot 1 (2026-09-23, quality-informed survivorship, `docs/MATCHING.md` &sect;23): the {@code VALID_VALUE}
	 * sibling of {@link #proposeCrossSystemFolds}, reusing the SAME {@code pByAttribute} grouping ({@link
	 * #groupMappingsByAttribute}) so this run never re-reads the landing data a second time. For every
	 * attribute of {@code pByAttribute} that carries at least one accepted VALIDITY {@code QualityRule}
	 * ({@code ebx.StagingLoader#readQualityRules}'s own {@code REFERENCE}/{@code CHECK_DIGIT}/{@code PATTERN}
	 * filter - read ONCE per attribute, via its representative mapping's {@code attributeId}), asks {@link
	 * ValidValueDerivation#propose} whether the measured validity is below 100% on at least one source system;
	 * when it is, ONE {@code StagingAttribute} row ({@code derivation} {@code VALID_VALUE}) is written via
	 * {@link #writeDerivedValidValueAttribute}.
	 * <p>
	 * <b>Never gated on {@code attributeMappings.size() >= 2}</b>, unlike {@link #proposeCrossSystemFolds} -
	 * {@link ValidValueDerivation}'s own Javadoc explains why a single-source attribute still benefits from a
	 * {@code *Valid} field. A SCOPED {@code TRANSCODE_REFERENCE} among the attribute's own accepted steps is
	 * skipped the same way as the fold pass (the accepted-pipeline replay this method also needs cannot
	 * evaluate it) - {@code pNotMeasured} records why, and the attribute is left out of {@code
	 * pAttributesMeasured} so {@link #withdrawStaleValidValueAttributes} leaves any existing row alone rather
	 * than treat "could not measure" as "measured, no gain".
	 *
	 * @param pAttributesMeasured out-param, the {@code VALID_VALUE} sibling of {@code
	 * proposeCrossSystemFolds}'s own {@code pAttributesMeasured} - every attribute id this call actually
	 * reached the measurement point for (carries a validity rule, no unsupported scoped step), whether or not
	 * a gain was found.
	 * @param pAttributesWithGain out-param, subset of {@code pAttributesMeasured} whose measurement found a
	 * genuine gain (validity below 100% on at least one system) this run.
	 */
	private static void proposeValidValueDerivations(final Session pSession, final Adaptation pFactory, final Map<String, List<MappingRow>> pByAttribute,
		final Map<String, ColumnEvidence> pEvidenceByMappingId, final CleaningSteps.Lookups pLookups, final Counters pCounters, final List<String> pNotMeasured,
		final Set<String> pAttributesMeasured, final Set<String> pAttributesWithGain) throws OperationException
	{
		for (final List<MappingRow> attributeMappings : pByAttribute.values())
		{
			if (attributeMappings.isEmpty())
			{
				continue;
			}
			final MappingRow representative = attributeMappings.get(0);
			final List<QualityRuleCheck.Rule> validityRules = StagingLoader.readQualityRules(pFactory, representative.attributeId);
			if (validityRules.isEmpty())
			{
				continue; // most attributes: nothing to test, never even worth reading the accepted pipeline below
			}
			final String attributeLabel = representative.entityName + "." + representative.attributeName;

			final Map<String, List<CleaningSteps.Step>> acceptedStepsByMapping = new LinkedHashMap<String, List<CleaningSteps.Step>>();
			boolean hasUnsupportedScopedStep = false;
			String unsupportedStepKind = null;
			for (final MappingRow mapping : attributeMappings)
			{
				final List<CleaningSteps.Step> steps = StagingLoader.readCleaningSteps(pFactory, mapping.mappingId);
				acceptedStepsByMapping.put(mapping.mappingId, steps);
				if (CrossSystemFold.hasUnsupportedScopedStep(steps))
				{
					hasUnsupportedScopedStep = true;
					unsupportedStepKind = CleaningSteps.TRANSCODE_REFERENCE;
				}
			}
			if (hasUnsupportedScopedStep)
			{
				pNotMeasured.add(attributeLabel + ": step " + unsupportedStepKind
					+ " cannot be evaluated here (a scoped reference needs a row-level scope value no distinct-value sample carries) - no valid-value field "
					+ "proposed.");
				continue;
			}
			pAttributesMeasured.add(representative.attributeId);

			final List<CrossSystemFold.SystemValues> bySystem = new ArrayList<CrossSystemFold.SystemValues>();
			for (final MappingRow mapping : attributeMappings)
			{
				final ColumnEvidence evidence = pEvidenceByMappingId.get(mapping.mappingId);
				final boolean sampled = evidence.aggregates.distinctCount > evidence.query.values.size() || evidence.query.sampled(); // staging polish (2026-10-17): or a row sample
				final List<CleaningSteps.Step> acceptedSteps = acceptedStepsByMapping.get(mapping.mappingId);
				final List<Detectors.ValueCount> effectiveValues = CrossSystemFold.applyAcceptedPipeline(evidence.query.values, acceptedSteps, pLookups);
				bySystem.add(new CrossSystemFold.SystemValues(mapping.sourceSystem, effectiveValues, sampled, acceptedSteps.size()));
			}
			final Detectors.ColumnContext column = new Detectors.ColumnContext(representative.sourceColumn, representative.attributeName, representative.dataType,
				representative.tags);
			final List<ValidValueDerivation.AttributeProposal> proposals = ValidValueDerivation.propose(bySystem, validityRules, pLookups, column);
			if (proposals.isEmpty())
			{
				continue;
			}
			pAttributesWithGain.add(representative.attributeId);
			QualitySuggester.writeDerivedValidValueAttribute(pSession, pFactory, representative, proposals.get(0), pCounters);
		}
	}

	// ============================================================================================ FOLD_KEY writing

	/**
	 * Writes ONE new {@code StagingAttribute} row for {@code pProposal} (2026-09-22 redesign - see
	 * {@link CrossSystemFold}'s own Javadoc): {@code name} = {@link CrossSystemFold.AttributeProposal#suggestedName},
	 * {@code dataType} {@code STRING}, {@code maxLength} copied from the SOURCE attribute's own (or left empty
	 * when the source has none), {@code mandatory}/{@code businessKey}/{@code enumerated} all {@code false},
	 * {@code referenceEntity} unset, {@code derivedFrom} = the source attribute, {@code derivation}
	 * {@code FOLD_KEY}, {@code origin} {@code RULE}, {@code status} {@code PROPOSED} - the SAME proposal shape
	 * (and the SAME human review) as any other {@code StagingAttribute} row {@code ebx.StagingSuggester} writes.
	 * <p>
	 * <b>Idempotent</b> ({@link #foldKeyAttributeExists}): a {@code FOLD_KEY} attribute already derived from
	 * this source, in ANY status (including {@code REJECTED} - a person already said no), is never re-proposed
	 * - {@link Counters#foldKeyAttributesLeftAlone} counts this, nothing is written or refreshed (unlike a
	 * {@code MappingStep}'s evidence, a derived attribute's own evidence is a one-time proposal text, not a
	 * running measurement a person expects to see updated).
	 * <p>
	 * <b>Name-collision guard</b> ({@link #attributeNameExists}): {@code StagingAttribute} has no unique
	 * constraint on {@code (entity, name)} at the model level (checked in {@code tools/build_xsd.py} - none
	 * exists for ANY attribute, not just derived ones), so a genuine collision (an ordinary attribute, or an
	 * earlier proposal under a different mechanism, already named {@code "<source>Key"}) would otherwise create
	 * two {@code StagingAttribute} rows the staging model has no way to build as two distinct fields. Skipped
	 * with {@link Counters#foldKeyAttributesNameCollision} rather than risk that, however unlikely in practice.
	 */
	private static void writeDerivedFoldKeyAttribute(final Session pSession, final Adaptation pFactory, final MappingRow pRepresentative,
		final CrossSystemFold.AttributeProposal pProposal, final Counters pCounters) throws OperationException
	{
		if (QualitySuggester.foldKeyAttributeExists(pFactory, pRepresentative.attributeId))
		{
			pCounters.foldKeyAttributesLeftAlone++;
			return;
		}
		if (QualitySuggester.attributeNameExists(pFactory, pRepresentative.entityId, pProposal.suggestedName))
		{
			pCounters.foldKeyAttributesNameCollision++;
			return;
		}
		final Adaptation sourceRow = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRepresentative.attributeId));
		if (sourceRow == null)
		{
			return; // defensive: the source row disappeared between the read pass earlier in suggest() and here
		}
		final Object rawMaxLength = sourceRow.get(FactoryPaths.StagingAttribute.MAX_LENGTH);
		final Integer maxLength = rawMaxLength instanceof Number ? Integer.valueOf(((Number) rawMaxLength).intValue()) : null;
		final String sourceLabel = sourceRow.getString(FactoryPaths.StagingAttribute.LABEL);
		final String sourceDisplayName = sourceLabel == null || sourceLabel.trim().isEmpty() ? pRepresentative.attributeName : sourceLabel;
		final String headline = ProposalWording.foldKeyAttributeProposition(pProposal.suggestedName, sourceDisplayName, pProposal.sharedBefore, pProposal.sharedAfter);
		final String exampleText = QualitySuggester.examplesText(pProposal.examples);
		final String rationale = headline + " " + pProposal.rationale + (exampleText == null ? "" : " Examples: " + exampleText);
		final String label = "Matching key of " + sourceDisplayName;
		final AdaptationTable attributesTable = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE);

		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContextForNewOccurrence(attributesTable);
				value.setValue(pRepresentative.entityId, FactoryPaths.StagingAttribute.ENTITY);
				value.setValue(pProposal.suggestedName, FactoryPaths.StagingAttribute.NAME);
				value.setValue(label, FactoryPaths.StagingAttribute.LABEL);
				value.setValue("STRING", FactoryPaths.StagingAttribute.DATA_TYPE);
				if (maxLength != null)
				{
					value.setValue(maxLength, FactoryPaths.StagingAttribute.MAX_LENGTH);
				}
				value.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.MANDATORY);
				value.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.BUSINESS_KEY);
				value.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.PRIMARY_KEY);
				value.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.ENUMERATED);
				value.setValue(pRepresentative.attributeId, FactoryPaths.StagingAttribute.DERIVED_FROM);
				value.setValue(CrossSystemFold.DERIVATION_FOLD_KEY, FactoryPaths.StagingAttribute.DERIVATION);
				value.setValue("RULE", FactoryPaths.StagingAttribute.ORIGIN);
				value.setValue(Integer.valueOf(pProposal.confidence), FactoryPaths.StagingAttribute.CONFIDENCE);
				value.setValue(TextLimits.truncate(rationale, QualitySuggester.RATIONALE_MAX_LENGTH), FactoryPaths.StagingAttribute.RATIONALE);
				value.setValue("PROPOSED", FactoryPaths.StagingAttribute.STATUS);
				pContext.doCreateOccurrence(value, attributesTable);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveMatchKeyAttribute", "Writing the matching-key attribute " + pProposal.suggestedName + " failed: " + result.getExceptionFullMessage(Locale.ENGLISH), pProposal.suggestedName);
		}
		pCounters.foldKeyAttributesProposed++;
	}

	/**
	 * {@code true} when a {@code StagingAttribute} row already exists with {@code derivedFrom =
	 * pSourceAttributeId} and {@code derivation = FOLD_KEY} - in ANY status AND ANY origin, deliberately, unlike
	 * {@link #withdrawStaleFoldKeyAttributes}'s own {@code RULE}-only scope (coordinator fix, 2026-09-22): the
	 * two checks answer different questions. This one only ever prevents a DUPLICATE proposal - "is there
	 * already something here, whoever/whatever put it there" - so a person's own hand-made ({@code USER}) key,
	 * or one the language model ({@code LLM}) already proposed, must count exactly the same as a {@code RULE}
	 * one: creating a SECOND {@code "<source>Key"} attribute because the first one happens to be {@code USER}/
	 * {@code LLM}-origin would be a duplicate-field bug, not a legitimate re-proposal. A person's {@code
	 * REJECTED} key (of ANY origin) must likewise never be re-proposed - "no" stays "no". See {@link
	 * #writeDerivedFoldKeyAttribute}'s own Javadoc on idempotence.
	 */
	private static boolean foldKeyAttributeExists(final Adaptation pFactory, final String pSourceAttributeId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult(
			"./derivedFrom=" + CatalogReader.quote(pSourceAttributeId) + " and ./derivation='" + CrossSystemFold.DERIVATION_FOLD_KEY + "'");
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	/** {@code true} when {@code pEntityId} already has a {@code StagingAttribute} named {@code pName} (case-sensitive, any status/origin) - see {@link #writeDerivedFoldKeyAttribute}'s own Javadoc on the name-collision guard. */
	private static boolean attributeNameExists(final Adaptation pFactory, final String pEntityId, final String pName)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE)
			.createRequestResult("./entity=" + CatalogReader.quote(pEntityId) + " and ./name=" + CatalogReader.quote(pName));
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	// ========================================================================================== VALID_VALUE writing

	/**
	 * Lot 1 (2026-09-23, quality-informed survivorship): the {@code VALID_VALUE} sibling of {@link
	 * #writeDerivedFoldKeyAttribute} - {@code name} = {@link ValidValueDerivation.AttributeProposal#suggestedName},
	 * {@code dataType}/{@code maxLength} copied from the SOURCE attribute exactly the same way, {@code
	 * derivation} {@code VALID_VALUE}, {@code origin} {@code RULE}, {@code status} {@code PROPOSED}. Same
	 * idempotence ({@link #validValueAttributeExists}, counted in {@link Counters#validValueAttributesLeftAlone})
	 * and name-collision guard ({@link #attributeNameExists}, reused verbatim - the SAME check regardless of
	 * which derivation is colliding, {@link Counters#validValueAttributesNameCollision}) as the fold-key writer.
	 */
	private static void writeDerivedValidValueAttribute(final Session pSession, final Adaptation pFactory, final MappingRow pRepresentative,
		final ValidValueDerivation.AttributeProposal pProposal, final Counters pCounters) throws OperationException
	{
		if (QualitySuggester.validValueAttributeExists(pFactory, pRepresentative.attributeId))
		{
			pCounters.validValueAttributesLeftAlone++;
			return;
		}
		if (QualitySuggester.attributeNameExists(pFactory, pRepresentative.entityId, pProposal.suggestedName))
		{
			pCounters.validValueAttributesNameCollision++;
			return;
		}
		final Adaptation sourceRow = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRepresentative.attributeId));
		if (sourceRow == null)
		{
			return; // defensive: the source row disappeared between the read pass earlier in suggest() and here
		}
		final Object rawMaxLength = sourceRow.get(FactoryPaths.StagingAttribute.MAX_LENGTH);
		final Integer maxLength = rawMaxLength instanceof Number ? Integer.valueOf(((Number) rawMaxLength).intValue()) : null;
		final String sourceLabel = sourceRow.getString(FactoryPaths.StagingAttribute.LABEL);
		final String sourceDisplayName = sourceLabel == null || sourceLabel.trim().isEmpty() ? pRepresentative.attributeName : sourceLabel;
		final String rationale = TextLimits.truncate(pProposal.rationale, QualitySuggester.RATIONALE_MAX_LENGTH);
		final String label = "Valid " + sourceDisplayName;
		final AdaptationTable attributesTable = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE);

		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContextForNewOccurrence(attributesTable);
				value.setValue(pRepresentative.entityId, FactoryPaths.StagingAttribute.ENTITY);
				value.setValue(pProposal.suggestedName, FactoryPaths.StagingAttribute.NAME);
				value.setValue(label, FactoryPaths.StagingAttribute.LABEL);
				value.setValue("STRING", FactoryPaths.StagingAttribute.DATA_TYPE);
				if (maxLength != null)
				{
					value.setValue(maxLength, FactoryPaths.StagingAttribute.MAX_LENGTH);
				}
				value.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.MANDATORY);
				value.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.BUSINESS_KEY);
				value.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.PRIMARY_KEY);
				value.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.ENUMERATED);
				value.setValue(pRepresentative.attributeId, FactoryPaths.StagingAttribute.DERIVED_FROM);
				value.setValue(ValidValueDerivation.DERIVATION_VALID_VALUE, FactoryPaths.StagingAttribute.DERIVATION);
				value.setValue("RULE", FactoryPaths.StagingAttribute.ORIGIN);
				value.setValue(Integer.valueOf(pProposal.confidence), FactoryPaths.StagingAttribute.CONFIDENCE);
				value.setValue(rationale, FactoryPaths.StagingAttribute.RATIONALE);
				value.setValue("PROPOSED", FactoryPaths.StagingAttribute.STATUS);
				pContext.doCreateOccurrence(value, attributesTable);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveValidValueAttribute", "Writing the valid-value attribute " + pProposal.suggestedName + " failed: " + result.getExceptionFullMessage(Locale.ENGLISH), pProposal.suggestedName);
		}
		pCounters.validValueAttributesProposed++;
	}

	/** {@code true} when a {@code StagingAttribute} row already exists with {@code derivedFrom = pSourceAttributeId} and {@code derivation = VALID_VALUE} - in ANY status AND ANY origin, the {@code VALID_VALUE} sibling of {@link #foldKeyAttributeExists} (same reasoning: idempotence only, never a "who proposed it" filter). */
	private static boolean validValueAttributeExists(final Adaptation pFactory, final String pSourceAttributeId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult(
			"./derivedFrom=" + CatalogReader.quote(pSourceAttributeId) + " and ./derivation='" + ValidValueDerivation.DERIVATION_VALID_VALUE + "'");
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	// ========================================================================================== VALID_VALUE withdrawal

	private static final class ValidValueWithdrawalCounters
	{
		int withdrawn;
		/** A person already decided (ACCEPTED/APPLIED/REJECTED) on a row whose evidence is now gone - never touched, only counted; same posture as {@link FoldKeyWithdrawalCounters#flagged}. */
		int flagged;
	}

	/**
	 * The {@code VALID_VALUE} sibling of {@link #withdrawStaleFoldKeyAttributes}, same discipline throughout:
	 * an existing row is left COMPLETELY alone when its source attribute is not in {@code
	 * pAttributesMeasured} (never even reached the measurement point this run); never deletes an
	 * ACCEPTED/APPLIED row (flagged instead); a {@code REJECTED} row is never deleted or flagged; only a
	 * {@code RULE}-origin row is ever withdrawn or flagged (a {@code USER}/{@code LLM} row is always {@code
	 * KEEP}, whatever {@code pStatus}/{@code pCovered} say).
	 */
	private static ValidValueWithdrawalCounters withdrawStaleValidValueAttributes(final Session pSession, final Adaptation pFactory, final String pProjectId,
		final Set<String> pAttributesMeasured, final Set<String> pAttributesWithGain) throws OperationException
	{
		final ValidValueWithdrawalCounters counters = new ValidValueWithdrawalCounters();
		final Set<String> projectAttributeIds = QualitySuggester.projectAttributeIds(pFactory, pProjectId);
		final List<Adaptation> toDelete = new ArrayList<Adaptation>();
		final AdaptationTable attributesTable = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE);
		final RequestResult rows = attributesTable.createRequestResult("./derivation='" + ValidValueDerivation.DERIVATION_VALID_VALUE + "'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!projectAttributeIds.contains(row.getOccurrencePrimaryKey().format()))
				{
					continue; // another project's row
				}
				final String sourceAttributeId = row.getString(FactoryPaths.StagingAttribute.DERIVED_FROM);
				if (sourceAttributeId == null || !pAttributesMeasured.contains(sourceAttributeId))
				{
					continue; // not measured this run - see this method's own Javadoc
				}
				final boolean covered = pAttributesWithGain.contains(sourceAttributeId);
				final WithdrawalRule.Action action = WithdrawalRule.decide(row.getString(FactoryPaths.StagingAttribute.STATUS), covered,
					row.getString(FactoryPaths.StagingAttribute.ORIGIN));
				if (action == WithdrawalRule.Action.WITHDRAW)
				{
					toDelete.add(row);
				}
				else if (action == WithdrawalRule.Action.WARN)
				{
					counters.flagged++;
				}
			}
		}
		finally
		{
			rows.close();
		}
		if (toDelete.isEmpty())
		{
			return counters;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Adaptation row : toDelete)
				{
					pContext.doDelete(row.getAdaptationName(), false);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.withdrawProposals", "Withdrawing stale valid-value attribute proposals failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		counters.withdrawn = toDelete.size();
		return counters;
	}

	// ========================================================================================= FOLD_KEY withdrawal

	private static final class FoldKeyWithdrawalCounters
	{
		int withdrawn;
		/** A person already decided (ACCEPTED/APPLIED/REJECTED) on a row whose evidence is now gone - never touched, only counted; same posture as {@link WithdrawalCounters#stepsFlagged}. */
		int flagged;
	}

	/**
	 * The {@code FOLD_KEY} sibling of {@link #withdrawStaleProposals}, for the SAME reason
	 * ({@link WithdrawalRule}'s own Javadoc: a proposal whose evidence evaporated is either withdrawn, if
	 * nobody decided on it yet, or flagged, if a person already did) - kept as its own method rather than
	 * folded into {@link #withdrawStaleProposals} because it sweeps a different TABLE ({@code StagingAttribute}
	 * rows, filtered to {@code derivation = FOLD_KEY}) with a different "is this still covered" test.
	 * <p>
	 * <b>An existing {@code FOLD_KEY} row is left COMPLETELY alone (not withdrawn, not flagged) when its own
	 * source attribute is not in {@code pAttributesMeasured}</b> - i.e. this run's {@link
	 * #proposeCrossSystemFolds} never even reached the measurement point for it (fewer than two accepted
	 * mappings now, no landing table resolved, or a newly-added scoped {@code TRANSCODE_REFERENCE} step this
	 * pass cannot evaluate - see that method's own Javadoc). Silence, not a verdict, is the honest answer when
	 * there is no fresh evidence either way; unlike {@link #withdrawStaleProposals}'s {@code MappingStep}/
	 * {@code QualityRule} sweep (which treats "not covered by an accepted mapping this run" as conclusive,
	 * because a rejected/missing mapping IS conclusive), a {@code FOLD_KEY} attribute can legitimately go
	 * unmeasured for a run without its underlying gain having disappeared.
	 * <p>
	 * <b>Never deletes an ACCEPTED/APPLIED row</b> ({@link WithdrawalRule#decide}'s own contract: {@code WARN},
	 * not {@code WITHDRAW}, for anything a person already decided) - only a still-{@code PROPOSED} row nobody
	 * has looked at yet is ever removed. A {@code REJECTED} row is likewise never deleted (a person's "no" is
	 * never erased) and never flagged as stale either, by the same {@code WithdrawalRule} contract every other
	 * sweep in this class already follows.
	 * <p>
	 * <b>Only a {@code RULE}-origin row is ever withdrawn or flagged</b> (coordinator fix, 2026-09-22, before
	 * merge - the exact same reasoning {@link #withdrawStaleProposals} already states for {@code MappingStep}/
	 * {@code QualityRule}: "a row of origin {@code LLM}/{@code USER} is never subject to this check - only
	 * what THIS pass itself might have produced can go stale THIS way"). A hand-written ({@code USER}) or
	 * language-model-proposed ({@code LLM}) derived attribute is a deliberate decision by someone/something
	 * else, not a re-confirmable measurement of this deterministic sweep; this class has no way to know
	 * whether ITS OWN reason for existing (a person's intent, an LLM's own evidence) is still valid, so it
	 * must never touch it - deleting a person's still-{@code PROPOSED} hand-made key the moment a fold
	 * measures zero gain would be a real, surprising data-loss bug, not a cleanup. The gate itself is the
	 * pure {@link WithdrawalRule#decide(String, boolean, String)} overload (unconditional {@code KEEP} for a
	 * non-{@code RULE} origin, whatever {@code pStatus}/{@code pCovered} say - see its own Javadoc for why this
	 * cannot be expressed by folding origin into {@code pCovered} the way {@link #withdrawStaleProposals} does
	 * for its own, narrower, kind-obsolescence check): a non-{@code RULE} row is therefore neither withdrawn
	 * nor counted in {@link FoldKeyWithdrawalCounters#flagged}.
	 */
	private static FoldKeyWithdrawalCounters withdrawStaleFoldKeyAttributes(final Session pSession, final Adaptation pFactory, final String pProjectId,
		final Set<String> pAttributesMeasured, final Set<String> pAttributesWithGain) throws OperationException
	{
		final FoldKeyWithdrawalCounters counters = new FoldKeyWithdrawalCounters();
		final Set<String> projectAttributeIds = QualitySuggester.projectAttributeIds(pFactory, pProjectId);
		final List<Adaptation> toDelete = new ArrayList<Adaptation>();
		final AdaptationTable attributesTable = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE);
		final RequestResult rows = attributesTable.createRequestResult("./derivation='" + CrossSystemFold.DERIVATION_FOLD_KEY + "'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!projectAttributeIds.contains(row.getOccurrencePrimaryKey().format()))
				{
					continue; // another project's row
				}
				final String sourceAttributeId = row.getString(FactoryPaths.StagingAttribute.DERIVED_FROM);
				if (sourceAttributeId == null || !pAttributesMeasured.contains(sourceAttributeId))
				{
					continue; // not measured this run - see this method's own Javadoc (an evidence-freshness gate, not a WithdrawalRule question)
				}
				final boolean covered = pAttributesWithGain.contains(sourceAttributeId);
				// origin-gated: a USER/LLM row is always Action.KEEP here, whatever pStatus/pCovered say - see WithdrawalRule.decide(status,covered,origin)'s own Javadoc
				final WithdrawalRule.Action action = WithdrawalRule.decide(row.getString(FactoryPaths.StagingAttribute.STATUS), covered,
					row.getString(FactoryPaths.StagingAttribute.ORIGIN));
				if (action == WithdrawalRule.Action.WITHDRAW)
				{
					toDelete.add(row);
				}
				else if (action == WithdrawalRule.Action.WARN)
				{
					counters.flagged++;
				}
			}
		}
		finally
		{
			rows.close();
		}
		if (toDelete.isEmpty())
		{
			return counters;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Adaptation row : toDelete)
				{
					pContext.doDelete(row.getAdaptationName(), false);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.withdrawProposals", "Withdrawing stale matching-key attribute proposals failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		counters.withdrawn = toDelete.size();
		return counters;
	}

	// ==================================================================================================== reading

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

	/** One ACCEPTED-or-APPLIED {@code AttributeMapping}, resolved down to what the detectors and the writers need - a separate, simpler reader from {@code StagingLoader}'s own (that one also needs the foreign-key plan, this one does not). */
	static final class MappingRow
	{
		final String mappingId;
		final String entityId;
		final String entityName;
		final String attributeId;
		final String attributeName;
		final String dataType;
		final String landingTableId;
		final String landingTableName;
		final String sourceColumn;
		final String fieldName;
		/** {@code ProjectSource.sourceSystem} of this mapping's landing table - or the landing table name itself when the project source could not be resolved. Used only structurally, by {@code CrossSystemFold}, to tell how many DISTINCT source systems feed one staging attribute; never read as business vocabulary. */
		final String sourceSystem;
		final List<String> tags;
		/** {@code LandingColumn.fillRate}/{@code maxLength}/{@code distinctCount} - the frozen catalog profile at landing-generation time; null when never profiled. Read once here so {@code ebx.QualityLlmSuggester} does not need a second pass over {@code LandingColumn} for the same row. */
		final Double fillRatePercent;
		final Integer observedMaxLength;
		final Long distinctCount;
		/** {@code LandingTable.sourceRowCount} - "how many rows" context for the language model; null when never profiled. */
		final Long sourceRowCount;
		/** Lot M8b (2026-10-04): {@code AttributeMapping.pipeline} - the mapping's own Connectors pipeline, or {@code null} when it has none (a type-driven mapping). */
		final String pipeline;
		/** Lot G2 (2026-10-17): {@code AttributeMapping.confidence}, or {@code null} - the capability pass reads an attribute from its most trusted mapping. */
		final Integer confidence;

		MappingRow(final String pMappingId, final String pEntityId, final String pEntityName, final String pAttributeId, final String pAttributeName,
			final String pDataType, final String pLandingTableId, final String pLandingTableName, final String pSourceColumn, final String pFieldName,
			final String pSourceSystem, final List<String> pTags, final Double pFillRatePercent, final Integer pObservedMaxLength, final Long pDistinctCount,
			final Long pSourceRowCount)
		{
			this(pMappingId, pEntityId, pEntityName, pAttributeId, pAttributeName, pDataType, pLandingTableId, pLandingTableName, pSourceColumn, pFieldName,
				pSourceSystem, pTags, pFillRatePercent, pObservedMaxLength, pDistinctCount, pSourceRowCount, null);
		}

		MappingRow(final String pMappingId, final String pEntityId, final String pEntityName, final String pAttributeId, final String pAttributeName,
			final String pDataType, final String pLandingTableId, final String pLandingTableName, final String pSourceColumn, final String pFieldName,
			final String pSourceSystem, final List<String> pTags, final Double pFillRatePercent, final Integer pObservedMaxLength, final Long pDistinctCount,
			final Long pSourceRowCount, final String pPipeline)
		{
			this(pMappingId, pEntityId, pEntityName, pAttributeId, pAttributeName, pDataType, pLandingTableId, pLandingTableName, pSourceColumn, pFieldName,
				pSourceSystem, pTags, pFillRatePercent, pObservedMaxLength, pDistinctCount, pSourceRowCount, pPipeline, null);
		}

		MappingRow(final String pMappingId, final String pEntityId, final String pEntityName, final String pAttributeId, final String pAttributeName,
			final String pDataType, final String pLandingTableId, final String pLandingTableName, final String pSourceColumn, final String pFieldName,
			final String pSourceSystem, final List<String> pTags, final Double pFillRatePercent, final Integer pObservedMaxLength, final Long pDistinctCount,
			final Long pSourceRowCount, final String pPipeline, final Integer pConfidence)
		{
			this.confidence = pConfidence;
			this.pipeline = pPipeline;
			this.mappingId = pMappingId;
			this.entityId = pEntityId;
			this.entityName = pEntityName;
			this.attributeId = pAttributeId;
			this.attributeName = pAttributeName;
			this.dataType = pDataType;
			this.landingTableId = pLandingTableId;
			this.landingTableName = pLandingTableName;
			this.sourceColumn = pSourceColumn;
			this.fieldName = pFieldName;
			this.sourceSystem = pSourceSystem;
			this.tags = pTags;
			this.fillRatePercent = pFillRatePercent;
			this.observedMaxLength = pObservedMaxLength;
			this.distinctCount = pDistinctCount;
			this.sourceRowCount = pSourceRowCount;
		}
	}

	/** {@link MappingScope#ACCEPTED_ONLY} - unchanged behaviour for every pre-existing caller. */
	static List<MappingRow> readAcceptedMappings(final Adaptation pFactory, final String pProjectId)
	{
		return QualitySuggester.readMappings(pFactory, pProjectId, MappingScope.ACCEPTED_ONLY);
	}

	/**
	 * Staging-proposal-run lot (2026-09-24): {@code pScope} widens what counts as "in scope" - see {@link
	 * MappingScope}'s own Javadoc for why. Same method as {@link #readAcceptedMappings(Adaptation, String)},
	 * renamed internally to {@link #readMappings} since it is no longer accepted-only by construction; the old
	 * name is kept as the accepted-only overload above so no existing caller needs to change.
	 */
	static List<MappingRow> readMappings(final Adaptation pFactory, final String pProjectId, final MappingScope pScope)
	{
		final Map<String, String> sourceSystemByLandingTableId = new HashMap<String, String>();
		final Map<String, String> entityNameById = new HashMap<String, String>();
		// staging-proposal live fix (bug 4): entity status, read alongside its name - MappingScope.eligible
		// needs it to exclude a SUPERSEDED/REJECTED entity's own mappings from the PROPOSAL scope (see its
		// own Javadoc).
		final Map<String, String> entityStatusById = new HashMap<String, String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				final String entityId = row.getOccurrencePrimaryKey().format();
				entityNameById.put(entityId, row.getString(FactoryPaths.StagingEntity.NAME));
				entityStatusById.put(entityId, row.getString(FactoryPaths.StagingEntity.STATUS));
			}
		}
		finally
		{
			entityRows.close();
		}

		// staging-proposal live fix (bug 4): TransferFeed status, looked up once per feed id and cached - a
		// mapping's own feed can be REJECTED independently of its entity/attribute (docs/TARGET-MAPPING.md).
		final Map<String, String> feedStatusById = new HashMap<String, String>();

		final List<MappingRow> out = new ArrayList<MappingRow>();
		final RequestResult attributeRows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult(null);
		try
		{
			for (Adaptation attributeRow = attributeRows.nextAdaptation(); attributeRow != null; attributeRow = attributeRows.nextAdaptation())
			{
				final String entityId = attributeRow.getString(FactoryPaths.StagingAttribute.ENTITY);
				if (!entityNameById.containsKey(entityId))
				{
					continue; // an attribute of another project
				}
				if (Boolean.TRUE.equals(attributeRow.get(FactoryPaths.StagingAttribute.PRIMARY_KEY)))
				{
					continue; // sourceSystem/sourceId: never mapped, nothing to profile
				}
				final String attributeId = attributeRow.getOccurrencePrimaryKey().format();
				final String attributeName = attributeRow.getString(FactoryPaths.StagingAttribute.NAME);
				final String dataType = attributeRow.getString(FactoryPaths.StagingAttribute.DATA_TYPE);

				final RequestResult mappingRows = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING)
					.createRequestResult("./attribute=" + CatalogReader.quote(attributeId));
				try
				{
					for (Adaptation mappingRow = mappingRows.nextAdaptation(); mappingRow != null; mappingRow = mappingRows.nextAdaptation())
					{
						// staging-proposal live fix (bug 4): MappingScope.eligible, not includes() alone - see
						// its own Javadoc (the mapping's entity/attribute/feed must also be in scope, PROPOSAL
						// only).
						final String feedId = mappingRow.getString(FactoryPaths.AttributeMapping.FEED);
						final String feedStatus = QualitySuggester.feedStatusOf(pFactory, feedId, feedStatusById);
						if (!pScope.eligible(mappingRow.getString(FactoryPaths.AttributeMapping.STATUS), entityStatusById.get(entityId),
							attributeRow.getString(FactoryPaths.StagingAttribute.STATUS), feedStatus))
						{
							continue;
						}
						final String landingColumnId = mappingRow.getString(FactoryPaths.AttributeMapping.LANDING_COLUMN);
						final Adaptation columnRow = pFactory.getTable(FactoryPaths.LANDING_COLUMN).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(landingColumnId));
						if (columnRow == null)
						{
							continue;
						}
						final String landingTableId = columnRow.getString(FactoryPaths.LandingColumn.LANDING_TABLE);
						final Adaptation tableRow = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(landingTableId));
						if (tableRow == null)
						{
							continue;
						}
						final List<String> tags = new ArrayList<String>();
						final List<?> rawTags = columnRow.getList(FactoryPaths.LandingColumn.TAGS);
						if (rawTags != null)
						{
							for (final Object tag : rawTags)
							{
								tags.add(String.valueOf(tag));
							}
						}
						final Object rawFillRate = columnRow.get(FactoryPaths.LandingColumn.FILL_RATE);
						final Double fillRatePercent = rawFillRate instanceof Number ? Double.valueOf(((Number) rawFillRate).doubleValue()) : null;
						final Object rawMaxLength = columnRow.get(FactoryPaths.LandingColumn.MAX_LENGTH);
						final Integer observedMaxLength = rawMaxLength instanceof Number ? Integer.valueOf(((Number) rawMaxLength).intValue()) : null;
						final Object rawDistinctCount = columnRow.get(FactoryPaths.LandingColumn.DISTINCT_COUNT);
						final Long distinctCount = rawDistinctCount instanceof Number ? Long.valueOf(((Number) rawDistinctCount).longValue()) : null;
						final Object rawSourceRowCount = tableRow.get(FactoryPaths.LandingTable.SOURCE_ROW_COUNT);
						final Long sourceRowCount = rawSourceRowCount instanceof Number ? Long.valueOf(((Number) rawSourceRowCount).longValue()) : null;
						final String tableName = tableRow.getString(FactoryPaths.LandingTable.TABLE_NAME);
						final String sourceSystem = QualitySuggester.sourceSystemOf(pFactory, landingTableId, tableRow, tableName, sourceSystemByLandingTableId);
						out.add(new MappingRow(mappingRow.getOccurrencePrimaryKey().format(), entityId, entityNameById.get(entityId), attributeId, attributeName,
							dataType, landingTableId, tableName, columnRow.getString(FactoryPaths.LandingColumn.SOURCE_COLUMN),
							columnRow.getString(FactoryPaths.LandingColumn.FIELD_NAME), sourceSystem, tags, fillRatePercent, observedMaxLength, distinctCount,
							sourceRowCount, mappingRow.getString(FactoryPaths.AttributeMapping.PIPELINE),
							mappingRow.get(FactoryPaths.AttributeMapping.CONFIDENCE) instanceof Number
								? Integer.valueOf(((Number) mappingRow.get(FactoryPaths.AttributeMapping.CONFIDENCE)).intValue()) : null));
					}
				}
				finally
				{
					mappingRows.close();
				}
			}
		}
		finally
		{
			attributeRows.close();
		}
		return out;
	}

	/**
	 * {@code ProjectSource.sourceSystem} of {@code pLandingTableId}'s own project source, cached per landing
	 * table (several mappings of the same table share one lookup) - falls back to {@code pTableName} when the
	 * project source cannot be resolved, so {@link com.on.ebx.factory.quality.CrossSystemFold}'s own "how many
	 * DISTINCT systems feed this attribute" count still has SOMETHING to key on rather than grouping every
	 * unresolved mapping under one blank identifier.
	 */
	private static String sourceSystemOf(final Adaptation pFactory, final String pLandingTableId, final Adaptation pTableRow, final String pTableName,
		final Map<String, String> pCache)
	{
		final String cached = pCache.get(pLandingTableId);
		if (cached != null)
		{
			return cached;
		}
		final String projectSourceId = pTableRow.getString(FactoryPaths.LandingTable.PROJECT_SOURCE);
		final Adaptation sourceRow = projectSourceId == null ? null
			: pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectSourceId));
		final String sourceSystem = sourceRow == null ? null : sourceRow.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
		final String resolved = sourceSystem == null || sourceSystem.trim().isEmpty() ? pTableName : sourceSystem;
		pCache.put(pLandingTableId, resolved);
		return resolved;
	}

	/**
	 * Staging-proposal live fix (bug 4): {@code TransferFeed.status} of {@code pFeedId}, cached per feed id -
	 * several mappings usually share one feed. Null for a blank {@code pFeedId} (a mapping written before
	 * {@code TransferFeed} existed, {@code FactoryPaths.AttributeMapping#FEED}'s own Javadoc) or an
	 * unresolvable one - {@link MappingScope#eligible} treats null as "never excludes on its own".
	 */
	private static String feedStatusOf(final Adaptation pFactory, final String pFeedId, final Map<String, String> pCache)
	{
		if (pFeedId == null || pFeedId.trim().isEmpty())
		{
			return null;
		}
		if (pCache.containsKey(pFeedId))
		{
			return pCache.get(pFeedId);
		}
		final Adaptation feedRow = pFactory.getTable(FactoryPaths.TRANSFER_FEED).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pFeedId));
		final String status = feedRow == null ? null : feedRow.getString(FactoryPaths.TransferFeed.STATUS);
		pCache.put(pFeedId, status);
		return status;
	}

	/** The landing table AND the dataset that owns it - {@code Adaptation.createQuery} is called on the dataset, never on the table alone. */
	static final class LandingTableRef
	{
		final Adaptation dataset;
		final AdaptationTable table;

		LandingTableRef(final Adaptation pDataset, final AdaptationTable pTable)
		{
			this.dataset = pDataset;
			this.table = pTable;
		}
	}

	static LandingTableRef landingTableRef(final Adaptation pFactory, final String pLandingDataspace, final MappingRow pMapping,
		final Map<String, LandingTableRef> pCache)
	{
		if (pCache.containsKey(pMapping.landingTableId))
		{
			return pCache.get(pMapping.landingTableId);
		}
		final Adaptation tableRow = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pMapping.landingTableId));
		final String projectSourceId = tableRow == null ? null : tableRow.getString(FactoryPaths.LandingTable.PROJECT_SOURCE);
		final Adaptation sourceRow = projectSourceId == null ? null
			: pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectSourceId));
		final String landingDatasetName = sourceRow == null ? null : sourceRow.getString(FactoryPaths.ProjectSource.LANDING_DATASET);
		LandingTableRef result = null;
		if (landingDatasetName != null)
		{
			final Repository repository = pFactory.getHome().getRepository();
			final AdaptationHome home = repository.lookupHome(HomeKey.forBranchName(pLandingDataspace));
			final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(landingDatasetName));
			if (dataset != null)
			{
				result = new LandingTableRef(dataset, dataset.getTable(Path.parse("/root/" + pMapping.landingTableName)));
			}
		}
		pCache.put(pMapping.landingTableId, result);
		return result;
	}

	// ============================================================================================= distinct values

	static final class ColumnQuery
	{
		final List<Detectors.ValueCount> values;
		/** {@code "SQL"}, {@code "SCAN"} or {@code "SAMPLE"} - the same reporting shape {@code RelationChecker} already uses; SAMPLE = the first rows only (staging polish, 2026-10-17). */
		final String path;
		/** Staging polish (2026-10-17): 0 when the whole table was read, otherwise the rows read (a sample of the first rows in key order, see {@link ScanGuard}). */
		final long sampledRows;
		/** The table's row count when it was counted, else -1. */
		final long tableRows;
		/** The cap the read was made with ({@link #coversCap}); {@link Integer#MAX_VALUE} for a result built by hand. */
		final int cap;

		ColumnQuery(final List<Detectors.ValueCount> pValues, final String pPath)
		{
			this(pValues, pPath, 0L, -1L, Integer.MAX_VALUE);
		}

		ColumnQuery(final List<Detectors.ValueCount> pValues, final String pPath, final long pSampledRows, final long pTableRows, final int pCap)
		{
			this.values = pValues;
			this.path = pPath;
			this.sampledRows = pSampledRows;
			this.tableRows = pTableRows;
			this.cap = pCap;
		}

		boolean sampled()
		{
			return this.sampledRows > 0L;
		}

		/**
		 * Whether a read made with {@link #cap} answers a request for {@code pCap} values: the values are the most frequent first, so the first {@code pCap}
		 * of a longer list are exactly the {@code pCap} most frequent; and a list shorter than its cap holds EVERY distinct value, whatever is asked.
		 */
		boolean coversCap(final int pCap)
		{
			return pCap <= this.cap || this.values.size() < this.cap;
		}

		/** The same read cut to {@code pCap} values (this when nothing to cut). */
		ColumnQuery withCap(final int pCap)
		{
			if (pCap >= this.values.size())
			{
				return this;
			}
			return new ColumnQuery(new ArrayList<Detectors.ValueCount>(this.values.subList(0, Math.max(0, pCap))), this.path, this.sampledRows, this.tableRows, pCap);
		}
	}

	/**
	 * One {@code GROUP BY} on {@code pField} (and, when {@code pScopeField} is set, jointly on both - a single
	 * SQL statement, not one query per scope value), capped and ordered by frequency descending, falling back
	 * to a bounded {@code RequestResult} scan when the SQL engine refuses the statement (see the class
	 * Javadoc). Every identifier is double-quoted, unconditionally - {@code pField}/{@code pScopeField} are
	 * {@code LandingColumn.fieldName} values (a generated, XML-safe name), never a raw source name, but the
	 * knowledge base's own rule is to quote regardless.
	 */
	static ColumnQuery distinctValues(final Session pSession, final LandingTableRef pLandingTable, final String pField, final String pScopeField,
		final int pCap)
	{
		// staging polish (2026-10-17): read once per column and table state (the same column is asked by the normalisation pass, the staging pass' shapes and
		// typed checks, the capability pass), and on a SAMPLE of the first rows above mdm.factory.profile.maxRowsFullScan rows (ScanGuard)
		final ScanGuard.Decision decision = ColumnReads.decision(pSession, pLandingTable);
		final String key = ColumnReads.columnKey(pSession, pLandingTable, pField, pScopeField);
		final long stamp = ColumnReads.stamp(pLandingTable);
		final ColumnQuery cached = ColumnReads.DISTINCT.get(key, stamp);
		if (cached != null && cached.sampled() == decision.sampled && cached.coversCap(pCap))
		{
			return cached.withCap(pCap);
		}
		final ColumnQuery read = decision.sampled
			? QualitySuggester.scan(pLandingTable.table, pField, pScopeField, pCap, decision.rowsToRead, decision.tableRows, "SAMPLE")
			: QualitySuggester.distinctValuesWholeTable(pSession, pLandingTable, pField, pScopeField, pCap, decision.tableRows);
		ColumnReads.DISTINCT.put(key, stamp, read, read.values.size());
		return read;
	}

	private static ColumnQuery distinctValuesWholeTable(final Session pSession, final LandingTableRef pLandingTable, final String pField, final String pScopeField,
		final int pCap, final long pTableRows)
	{
		final String tableName = pLandingTable.table.getTablePath().getLastStep().format();
		final String sql = QualitySuggester.buildGroupBySql(tableName, pField, pScopeField, pCap);
		try
		{
			return new ColumnQuery(QualitySuggester.runGroupBy(pLandingTable.dataset, pSession, sql, pScopeField), "SQL", 0L, pTableRows, pCap);
		}
		catch (final QueryParseException ex)
		{
			return QualitySuggester.scan(pLandingTable.table, pField, pScopeField, pCap, Long.MAX_VALUE, pTableRows, "SCAN");
		}
		catch (final CteQueryNotImplementableException ex)
		{
			return QualitySuggester.scan(pLandingTable.table, pField, pScopeField, pCap, Long.MAX_VALUE, pTableRows, "SCAN");
		}
	}

	private static String buildGroupBySql(final String pTableName, final String pField, final String pScopeField, final int pCap)
	{
		final String table = QualitySuggester.quote(pTableName);
		final String field = QualitySuggester.quote(pField);
		final StringBuilder select = new StringBuilder("SELECT ").append(field).append(" AS ").append(QualitySuggester.quote("V"));
		final StringBuilder where = new StringBuilder(field).append(" IS NOT NULL AND ").append(field).append(" <> ''");
		final StringBuilder groupBy = new StringBuilder(field);
		if (pScopeField != null)
		{
			final String scope = QualitySuggester.quote(pScopeField);
			select.append(", ").append(scope).append(" AS ").append(QualitySuggester.quote("S"));
			where.append(" AND ").append(scope).append(" IS NOT NULL AND ").append(scope).append(" <> ''");
			groupBy.append(", ").append(scope);
		}
		select.append(", COUNT(*) AS ").append(QualitySuggester.quote("N"));
		return select + " FROM " + table + " WHERE " + where + " GROUP BY " + groupBy + " ORDER BY " + QualitySuggester.quote("N") + " DESC FETCH NEXT " + pCap
			+ " ROWS ONLY";
	}

	private static List<Detectors.ValueCount> runGroupBy(final Adaptation pLandingDataset, final Session pSession, final String pSql, final String pScopeField)
	{
		final List<Detectors.ValueCount> out = new ArrayList<Detectors.ValueCount>();
		final Query<Tuple> query = pLandingDataset.createQuery(pSql);
		query.setSession(pSession);
		final QueryResult<Tuple> result = query.getResult();
		try
		{
			for (final Tuple row : result)
			{
				final Object value = row.get("V");
				final Object scope = pScopeField == null ? null : row.get("S");
				final Object count = row.get("N");
				out.add(new Detectors.ValueCount(value == null ? null : String.valueOf(value), scope == null ? null : String.valueOf(scope),
					count instanceof Number ? ((Number) count).longValue() : 0L));
			}
		}
		finally
		{
			result.close();
		}
		return out;
	}

	// ============================================================================================= exact aggregates

	/** {@code COUNT(*)} / {@code COUNT} of non-blank / {@code COUNT(DISTINCT)} of non-blank, EXACT - never derived from {@link #distinctValues}'s capped sample. See {@link #exactAggregates}'s own Javadoc for why. */
	static final class ExactAggregates
	{
		final long rows;
		final long filled;
		final long distinctCount;
		/** Audit F-50: true when the fallback scan stopped counting distinct values at {@link DistinctTally#CAP}: {@link #distinctCount} is then a lower bound, not the exact count. Always false on the SQL path. */
		final boolean distinctCapReached;
		/** Staging polish (2026-10-17): 0 for the exact figures; otherwise {@link #rows}, {@link #filled} and {@link #distinctCount} come from the first {@code sampledRows} rows only ({@link ScanGuard}). */
		final long sampledRows;
		/** The table's row count when it was counted, else -1 (only set on a sample read). */
		final long tableRows;

		ExactAggregates(final long pRows, final long pFilled, final long pDistinctCount)
		{
			this(pRows, pFilled, pDistinctCount, false);
		}

		ExactAggregates(final long pRows, final long pFilled, final long pDistinctCount, final boolean pDistinctCapReached)
		{
			this(pRows, pFilled, pDistinctCount, pDistinctCapReached, 0L, -1L);
		}

		ExactAggregates(final long pRows, final long pFilled, final long pDistinctCount, final boolean pDistinctCapReached, final long pSampledRows,
			final long pTableRows)
		{
			this.rows = pRows;
			this.filled = pFilled;
			this.distinctCount = pDistinctCount;
			this.distinctCapReached = pDistinctCapReached;
			this.sampledRows = pSampledRows;
			this.tableRows = pTableRows;
		}

		boolean sampled()
		{
			return this.sampledRows > 0L;
		}

		/** The table's rows: the counted total on a sample read, {@link #rows} otherwise. */
		long totalRows()
		{
			return this.sampledRows > 0L && this.tableRows >= 0L ? this.tableRows : this.rows;
		}
	}

	/**
	 * Audit F-50 (2026-10-06): the distinct values of a column on the fallback scan, BOUNDED. {@link #scanExactAggregates} kept every distinct value
	 * of the column in a {@code HashSet}: on a landing table of millions of rows with a near-unique column (an identifier, an e-mail) that is the
	 * whole column in heap, on the path taken exactly when the SQL aggregate failed. Past {@link #CAP} distinct values the tally stops adding (a new
	 * value is no longer remembered, so it is counted only up to the cap) and says so; the count is then the cap, a LOWER bound - never a wrong exact
	 * figure - and the caller reports it. The cap is a heap bound (about 500,000 short strings, tens of MB), not a data limit.
	 */
	static final class DistinctTally
	{
		static final int CAP = 500000;

		private final java.util.Set<String> values = new java.util.HashSet<String>();
		private final int cap;
		private boolean capReached;

		DistinctTally()
		{
			this(DistinctTally.CAP);
		}

		DistinctTally(final int pCap)
		{
			this.cap = pCap;
		}

		void add(final String pValue)
		{
			if (this.values.size() >= this.cap)
			{
				if (!this.values.contains(pValue))
				{
					this.capReached = true;
				}
				return;
			}
			this.values.add(pValue);
		}

		long count()
		{
			return this.values.size();
		}

		boolean capReached()
		{
			return this.capReached;
		}
	}

	/**
	 * EXACT {@code COUNT(*)}/{@code COUNT}(non-blank)/{@code COUNT(DISTINCT}(non-blank{@code )} for {@code
	 * pField}, computed by EBX SQL over the WHOLE landing table - never derived from {@link #distinctValues}'s
	 * capped, most-frequent-first GROUP BY sample. Coordinator review, 2026-09-21: a gate or a context number
	 * (row count, fill rate, distinct count, a near-uniqueness ratio) computed on that sample is simply WRONG
	 * at volume, since a highly-duplicated identifier's own duplicate values are exactly the ones the sample
	 * over-represents (they rank highest by frequency) while genuinely-unique long-tail values fall out of the
	 * cap entirely - see {@code quality.QualityLlmPass}'s own Javadoc for the worked example. Two lightweight
	 * aggregate queries (no {@code GROUP BY}, one row back each): {@code COUNT(*)} unfiltered for {@link
	 * ExactAggregates#rows}, then the SAME non-blank {@code WHERE} clause {@link #buildGroupBySql} already
	 * uses, with a plain {@code COUNT(*)} and a {@code COUNT(DISTINCT "field")} - the latter confirmed
	 * reachable on this EBX SQL dialect (see `docs/QUALITY-LLM.md`). Falls back to a full, UNCAPPED {@code
	 * RequestResult} scan on the same two exceptions {@link #distinctValues} already catches - exact by
	 * construction, so unlike {@link #scan} (a bounded approximation on purpose) this fallback must
	 * read every row.
	 */
	static ExactAggregates exactAggregates(final Session pSession, final LandingTableRef pLandingTable, final String pField)
	{
		// staging polish (2026-10-17): the same exact figures are asked by several passes of one run; read once per table state
		final String key = ColumnReads.columnKey(pSession, pLandingTable, pField, null) + "|exact";
		final long stamp = ColumnReads.stamp(pLandingTable);
		final ExactAggregates cached = ColumnReads.AGGREGATES.get(key, stamp);
		if (cached != null)
		{
			return cached;
		}
		final ExactAggregates read = QualitySuggester.readExactAggregates(pSession, pLandingTable, pField);
		ColumnReads.AGGREGATES.put(key, stamp, read, 1L);
		return read;
	}

	/**
	 * Staging polish (2026-10-17): the aggregates of a column for a PROPOSAL, bounded by {@link ScanGuard}: the exact ones ({@link #exactAggregates}) while the
	 * table holds at most {@code mdm.factory.profile.maxRowsFullScan} rows, otherwise the figures of its first rows ({@link ExactAggregates#sampledRows} says
	 * so, {@link ExactAggregates#tableRows} keeps the counted total). A pass whose verdict needs the EXACT figure (the quality pass' UNIQUE and fill rules)
	 * keeps calling {@link #exactAggregates}.
	 */
	static ExactAggregates boundedAggregates(final Session pSession, final LandingTableRef pLandingTable, final String pField)
	{
		final ScanGuard.Decision decision = ColumnReads.decision(pSession, pLandingTable);
		if (!decision.sampled)
		{
			return QualitySuggester.exactAggregates(pSession, pLandingTable, pField);
		}
		final String key = ColumnReads.columnKey(pSession, pLandingTable, pField, null) + "|sample";
		final long stamp = ColumnReads.stamp(pLandingTable);
		final ExactAggregates cached = ColumnReads.AGGREGATES.get(key, stamp);
		if (cached != null)
		{
			return cached;
		}
		final ExactAggregates read = QualitySuggester.scanExactAggregates(pLandingTable.table, pField, decision.rowsToRead, decision.tableRows);
		ColumnReads.AGGREGATES.put(key, stamp, read, 1L);
		return read;
	}

	private static ExactAggregates readExactAggregates(final Session pSession, final LandingTableRef pLandingTable, final String pField)
	{
		final String tableName = QualitySuggester.quote(pLandingTable.table.getTablePath().getLastStep().format());
		final String field = QualitySuggester.quote(pField);
		try
		{
			final long rows = QualitySuggester.runSingleCount(pLandingTable.dataset, pSession,
				"SELECT COUNT(*) AS " + QualitySuggester.quote("N") + " FROM " + tableName, "N");
			final String filledSql = "SELECT COUNT(*) AS " + QualitySuggester.quote("N") + ", COUNT(DISTINCT " + field + ") AS " + QualitySuggester.quote("D")
				+ " FROM " + tableName + " WHERE " + field + " IS NOT NULL AND " + field + " <> ''";
			final long[] filledDistinct = QualitySuggester.runFilledDistinct(pLandingTable.dataset, pSession, filledSql);
			return new ExactAggregates(rows, filledDistinct[0], filledDistinct[1]);
		}
		catch (final QueryParseException ex)
		{
			return QualitySuggester.scanExactAggregates(pLandingTable.table, pField, Long.MAX_VALUE, -1L);
		}
		catch (final CteQueryNotImplementableException ex)
		{
			return QualitySuggester.scanExactAggregates(pLandingTable.table, pField, Long.MAX_VALUE, -1L);
		}
	}

	private static long runSingleCount(final Adaptation pLandingDataset, final Session pSession, final String pSql, final String pAlias)
	{
		final Query<Tuple> query = pLandingDataset.createQuery(pSql);
		query.setSession(pSession);
		final QueryResult<Tuple> result = query.getResult();
		try
		{
			for (final Tuple row : result)
			{
				final Object value = row.get(pAlias);
				return value instanceof Number ? ((Number) value).longValue() : 0L;
			}
			return 0L;
		}
		finally
		{
			result.close();
		}
	}

	private static long[] runFilledDistinct(final Adaptation pLandingDataset, final Session pSession, final String pSql)
	{
		final Query<Tuple> query = pLandingDataset.createQuery(pSql);
		query.setSession(pSession);
		final QueryResult<Tuple> result = query.getResult();
		try
		{
			for (final Tuple row : result)
			{
				final Object filled = row.get("N");
				final Object distinctCount = row.get("D");
				return new long[] { filled instanceof Number ? ((Number) filled).longValue() : 0L,
					distinctCount instanceof Number ? ((Number) distinctCount).longValue() : 0L };
			}
			return new long[] { 0L, 0L };
		}
		finally
		{
			result.close();
		}
	}

	/** Full scan of every row (unlike {@link #scan}, a bounded approximation on purpose): rows and filled are exact. Distinct values are tallied in a {@link DistinctTally}, bounded since audit F-50: past its cap the distinct count is a lower bound and {@link ExactAggregates#distinctCapReached} says so. Staging polish (2026-10-17): {@code pRowLimit} stops the scan after that many rows - the sample read of {@link ScanGuard}. */
	private static ExactAggregates scanExactAggregates(final AdaptationTable pLandingTable, final String pField, final long pRowLimit, final long pTableRows)
	{
		final Path fieldPath = Path.parse("./" + pField);
		final DistinctTally distinctValues = new DistinctTally();
		long rows = 0;
		long filled = 0;
		boolean limited = false;
		final RequestResult requestRows = pLandingTable.createRequestResult(null);
		try
		{
			for (Adaptation row = requestRows.nextAdaptation(); row != null; row = requestRows.nextAdaptation())
			{
				if (rows >= pRowLimit)
				{
					limited = true;
					break;
				}
				rows++;
				final String value = row.getString(fieldPath);
				if (value != null && !value.isEmpty())
				{
					filled++;
					distinctValues.add(value); // bounded - audit F-50
				}
			}
		}
		finally
		{
			requestRows.close();
		}
		return new ExactAggregates(rows, filled, distinctValues.count(), distinctValues.capReached(), limited ? rows : 0L, limited ? pTableRows : -1L);
	}

	/**
	 * Bounded record-by-record scan (the same fallback shape {@code RelationChecker.scanFallback} uses): tallies in memory, then keeps only the {@code pCap}
	 * most frequent entries. Staging polish (2026-10-17): stops after {@code pRowLimit} rows (the table's own order, which is the primary key's) - the SAMPLE
	 * read of {@link ScanGuard}; {@link ColumnQuery#sampledRows} is then the rows read, 0 when the scan went to the end of the table.
	 */
	private static ColumnQuery scan(final AdaptationTable pLandingTable, final String pField, final String pScopeField, final int pCap, final long pRowLimit,
		final long pTableRows, final String pPath)
	{
		final Map<String, Long> counts = new LinkedHashMap<String, Long>();
		final Map<String, String[]> parts = new LinkedHashMap<String, String[]>();
		final Path fieldPath = Path.parse("./" + pField);
		final Path scopePath = pScopeField == null ? null : Path.parse("./" + pScopeField);
		final RequestResult rows = pLandingTable.createRequestResult(null);
		long rowsRead = 0L;
		boolean limited = false;
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (rowsRead >= pRowLimit)
				{
					limited = true;
					break;
				}
				rowsRead++;
				final String value = row.getString(fieldPath);
				if (value == null || value.isEmpty())
				{
					continue;
				}
				final String scope = scopePath == null ? null : row.getString(scopePath);
				if (scopePath != null && (scope == null || scope.isEmpty()))
				{
					continue;
				}
				final String key = value + "\u0001" + (scope == null ? "" : scope);
				final Long existing = counts.get(key);
				counts.put(key, Long.valueOf((existing == null ? 0L : existing.longValue()) + 1));
				parts.put(key, new String[] { value, scope });
			}
		}
		finally
		{
			rows.close();
		}
		final List<Map.Entry<String, Long>> sorted = new ArrayList<Map.Entry<String, Long>>(counts.entrySet());
		QualitySuggester.sortByCountDesc(sorted);
		final List<Detectors.ValueCount> out = new ArrayList<Detectors.ValueCount>();
		for (final Map.Entry<String, Long> entry : sorted)
		{
			if (out.size() >= pCap)
			{
				break;
			}
			final String[] valueScope = parts.get(entry.getKey());
			out.add(new Detectors.ValueCount(valueScope[0], valueScope[1], entry.getValue().longValue()));
		}
		return new ColumnQuery(out, limited ? pPath : "SCAN", limited ? rowsRead : 0L, pTableRows, pCap);
	}

	private static void sortByCountDesc(final List<Map.Entry<String, Long>> pEntries)
	{
		java.util.Collections.sort(pEntries, new java.util.Comparator<Map.Entry<String, Long>>()
		{
			@Override
			public int compare(final Map.Entry<String, Long> pLeft, final Map.Entry<String, Long> pRight)
			{
				return pRight.getValue().compareTo(pLeft.getValue());
			}
		});
	}

	private static String quote(final String pIdentifier)
	{
		return "\"" + pIdentifier.replace("\"", "\"\"") + "\""; // a quote inside a name is doubled (security review, 2026-09-24)
	}

	// ============================================================================================== reference data

	/** Package-visible: {@code StagingLoader}'s own execution hook (task D) loads the SAME reference lookups, once per transfer run, rather than re-deriving how to read a {@code ReferenceBinding}. */
	static CleaningSteps.Lookups buildLookups(final Adaptation pFactory, final List<String> pNotes)
	{
		final Map<String, Map<String, String>> flat = new LinkedHashMap<String, Map<String, String>>();
		final Map<String, Map<String, Map<String, String>>> scoped = new LinkedHashMap<String, Map<String, Map<String, String>>>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.REFERENCE_BINDING).createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				QualitySuggester.loadOneBinding(pFactory, row, flat, scoped, pNotes);
			}
		}
		finally
		{
			rows.close();
		}
		return new CleaningSteps.Lookups(flat, scoped);
	}

	private static void loadOneBinding(final Adaptation pFactory, final Adaptation pBindingRow, final Map<String, Map<String, String>> pFlat,
		final Map<String, Map<String, Map<String, String>>> pScoped, final List<String> pNotes)
	{
		final String kind = pBindingRow.getString(FactoryPaths.ReferenceBinding.KIND);
		final String dataspace = pBindingRow.getString(FactoryPaths.ReferenceBinding.DATASPACE);
		final String dataset = pBindingRow.getString(FactoryPaths.ReferenceBinding.DATASET);
		final String tablePath = pBindingRow.getString(FactoryPaths.ReferenceBinding.TABLE_PATH);
		final String codeField = pBindingRow.getString(FactoryPaths.ReferenceBinding.CODE_FIELD);
		final String scopeField = pBindingRow.getString(FactoryPaths.ReferenceBinding.SCOPE_FIELD);
		final List<String> labelFields = new ArrayList<String>();
		final List<?> rawLabelFields = pBindingRow.getList(FactoryPaths.ReferenceBinding.LABEL_FIELDS);
		if (rawLabelFields != null)
		{
			for (final Object field : rawLabelFields)
			{
				labelFields.add(String.valueOf(field));
			}
		}

		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome home = repository.lookupHome(HomeKey.forBranchName(dataspace));
		final Adaptation target = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(dataset));
		if (target == null)
		{
			pNotes.add("ReferenceBinding " + kind + ": dataset " + dataspace + "/" + dataset + " not found - skipped, degrading gracefully.");
			return;
		}
		final AdaptationTable refTable;
		try
		{
			refTable = target.getTable(Path.parse(tablePath));
		}
		catch (final RuntimeException ex)
		{
			pNotes.add("ReferenceBinding " + kind + ": table " + tablePath + " not found in " + dataspace + "/" + dataset + " - skipped.");
			return;
		}

		final Map<String, String> synonyms = QualitySuggester.parseSynonyms(pBindingRow.getString(FactoryPaths.ReferenceBinding.SYNONYMS));
		final boolean isScoped = scopeField != null && !scopeField.trim().isEmpty();
		final Map<String, List<String>> flatRows = isScoped ? null : new LinkedHashMap<String, List<String>>();
		final Map<String, Map<String, List<String>>> scopedRows = isScoped ? new LinkedHashMap<String, Map<String, List<String>>>() : null;

		final RequestResult refRows = refTable.createRequestResult(null);
		try
		{
			for (Adaptation refRow = refRows.nextAdaptation(); refRow != null; refRow = refRows.nextAdaptation())
			{
				final String code = refRow.getString(Path.parse("./" + codeField));
				if (code == null)
				{
					continue;
				}
				final List<String> labels = new ArrayList<String>();
				for (final String labelField : labelFields)
				{
					labels.add(refRow.getString(Path.parse("./" + labelField)));
				}
				if (isScoped)
				{
					final String scopeValue = refRow.getString(Path.parse("./" + scopeField));
					if (scopeValue == null)
					{
						continue;
					}
					final String scopeKey = CleaningSteps.normalizeKey(scopeValue);
					Map<String, List<String>> byScope = scopedRows.get(scopeKey);
					if (byScope == null)
					{
						byScope = new LinkedHashMap<String, List<String>>();
						scopedRows.put(scopeKey, byScope);
					}
					byScope.put(code, labels);
				}
				else
				{
					flatRows.put(code, labels);
				}
			}
		}
		finally
		{
			refRows.close();
		}

		if (isScoped)
		{
			final Map<String, Map<String, String>> byScopeFlat = new LinkedHashMap<String, Map<String, String>>();
			for (final Map.Entry<String, Map<String, List<String>>> entry : scopedRows.entrySet())
			{
				byScopeFlat.put(entry.getKey(), CleaningSteps.buildFlatTable(entry.getValue(), null));
			}
			pScoped.put(kind, byScopeFlat);
		}
		else
		{
			pFlat.put(kind, CleaningSteps.buildFlatTable(flatRows, synonyms));
		}
	}

	private static Map<String, String> parseSynonyms(final String pJson)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pJson == null || pJson.trim().isEmpty())
		{
			return out;
		}
		try
		{
			final JsonNode node = QualitySuggester.MAPPER.readTree(pJson);
			if (node != null && node.isObject())
			{
				final java.util.Iterator<String> names = node.fieldNames();
				while (names.hasNext())
				{
					final String name = names.next();
					out.put(name, node.get(name).asText());
				}
			}
		}
		catch (final java.io.IOException ex)
		{
			// malformed synonyms text: degrade to "no synonyms" rather than failing the whole run
		}
		return out;
	}

	// =================================================================================================== writing

	private static void writeMappingSteps(final Session pSession, final Adaptation pFactory, final MappingRow pMapping,
		final List<Detectors.StepProposal> pProposals, final Counters pCounters) throws OperationException
	{
		if (pProposals.isEmpty())
		{
			return;
		}
		final AdaptationTable stepsTable = pFactory.getTable(FactoryPaths.MAPPING_STEP);
		final Map<String, Adaptation> existingByKind = new HashMap<String, Adaptation>();
		int nextPosition = 0;
		final RequestResult existingRows = stepsTable.createRequestResult("./mapping=" + CatalogReader.quote(pMapping.mappingId));
		try
		{
			for (Adaptation row = existingRows.nextAdaptation(); row != null; row = existingRows.nextAdaptation())
			{
				existingByKind.put(row.getString(FactoryPaths.MappingStep.KIND), row);
				final Object rawPosition = row.get(FactoryPaths.MappingStep.POSITION);
				final int position = rawPosition instanceof Number ? ((Number) rawPosition).intValue() : 0;
				nextPosition = Math.max(nextPosition, position);
			}
		}
		finally
		{
			existingRows.close();
		}

		final List<Detectors.StepProposal> toCreate = new ArrayList<Detectors.StepProposal>();
		for (final Detectors.StepProposal proposal : pProposals)
		{
			final Adaptation existing = existingByKind.get(proposal.kind);
			if (existing == null)
			{
				toCreate.add(proposal);
				continue;
			}
			if ("PROPOSED".equals(existing.getString(FactoryPaths.MappingStep.STATUS)))
			{
				QualitySuggester.refreshMappingStep(pSession, pFactory, existing, proposal);
				pCounters.stepsRefreshed++;
			}
			else
			{
				pCounters.stepsLeftAlone++;
			}
		}
		if (toCreate.isEmpty())
		{
			return;
		}
		final int startPosition = nextPosition;
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				int position = startPosition;
				for (final Detectors.StepProposal proposal : toCreate)
				{
					position++;
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(stepsTable);
					value.setValue(pMapping.mappingId, FactoryPaths.MappingStep.MAPPING);
					value.setValue(Integer.valueOf(position), FactoryPaths.MappingStep.POSITION);
					value.setValue(proposal.kind, FactoryPaths.MappingStep.KIND);
					if (proposal.argument != null)
					{
						value.setValue(proposal.argument, FactoryPaths.MappingStep.ARGUMENT);
					}
					value.setValue("RULE", FactoryPaths.MappingStep.ORIGIN);
					value.setValue(Integer.valueOf(proposal.confidence), FactoryPaths.MappingStep.CONFIDENCE);
					value.setValue(proposal.stored(QualitySuggester.RATIONALE_MAX_LENGTH), FactoryPaths.MappingStep.RATIONALE);
					value.setValue("PROPOSED", FactoryPaths.MappingStep.STATUS);
					value.setValue(BigDecimal.valueOf(proposal.valuesSeen), FactoryPaths.MappingStep.VALUES_SEEN);
					value.setValue(BigDecimal.valueOf(proposal.valuesChanged), FactoryPaths.MappingStep.VALUES_CHANGED);
					value.setValue(BigDecimal.valueOf(proposal.valuesUnresolved), FactoryPaths.MappingStep.VALUES_UNRESOLVED);
					value.setValue(QualitySuggester.examplesText(proposal.examples), FactoryPaths.MappingStep.EXAMPLES);
					value.setValue(new Date(), FactoryPaths.MappingStep.CHECKED_AT);
					pContext.doCreateOccurrence(value, stepsTable);
					pCounters.stepsCreated++;
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveMappingSteps", "Writing the mapping steps of " + pMapping.entityName + "." + pMapping.attributeName + " failed: "
				+ result.getExceptionFullMessage(Locale.ENGLISH), pMapping.entityName, pMapping.attributeName);
		}
	}

	private static void refreshMappingStep(final Session pSession, final Adaptation pFactory, final Adaptation pExisting, final Detectors.StepProposal pProposal)
		throws OperationException
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(pExisting.getAdaptationName());
				value.setValue(BigDecimal.valueOf(pProposal.valuesSeen), FactoryPaths.MappingStep.VALUES_SEEN);
				value.setValue(BigDecimal.valueOf(pProposal.valuesChanged), FactoryPaths.MappingStep.VALUES_CHANGED);
				value.setValue(BigDecimal.valueOf(pProposal.valuesUnresolved), FactoryPaths.MappingStep.VALUES_UNRESOLVED);
				value.setValue(QualitySuggester.examplesText(pProposal.examples), FactoryPaths.MappingStep.EXAMPLES);
				value.setValue(new Date(), FactoryPaths.MappingStep.CHECKED_AT);
				pContext.doModifyContent(pExisting, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.refreshEvidence", "Refreshing a mapping step's evidence failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	// ============================================================================================== withdrawal

	private static final class WithdrawalCounters
	{
		int stepsWithdrawn;
		/** A row a person already decided on, whose evidence is gone - never deleted, only counted here; the review screen (task B) is where a reviewer actually sees which one. */
		int stepsFlagged;
		int rulesWithdrawn;
		int rulesFlagged;
	}

	/**
	 * Every {@code StagingAttribute} of the project (its {@code entity} points at a project {@code
	 * StagingEntity}) - whatever the attribute's own status. Deliberately a full scan filtered in Java, the
	 * same shape {@link #readAcceptedMappings} itself already uses to read every {@code StagingAttribute} once
	 * per run: these tables are the factory's own small proposal tables, never landing/staging business data.
	 */
	private static Set<String> projectAttributeIds(final Adaptation pFactory, final String pProjectId)
	{
		final Set<String> entityIds = new HashSet<String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				entityIds.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			entityRows.close();
		}
		final Set<String> out = new HashSet<String>();
		final RequestResult attributeRows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult(null);
		try
		{
			for (Adaptation row = attributeRows.nextAdaptation(); row != null; row = attributeRows.nextAdaptation())
			{
				if (entityIds.contains(row.getString(FactoryPaths.StagingAttribute.ENTITY)))
				{
					out.add(row.getOccurrencePrimaryKey().format());
				}
			}
		}
		finally
		{
			attributeRows.close();
		}
		return out;
	}

	/** Every {@code AttributeMapping} whose {@code attribute} belongs to the project, whatever the mapping's own status - the scope a {@code MappingStep} withdrawal decision is limited to (never another project's mapping). */
	private static Set<String> projectMappingIds(final Adaptation pFactory, final Set<String> pProjectAttributeIds)
	{
		final Set<String> out = new HashSet<String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (pProjectAttributeIds.contains(row.getString(FactoryPaths.AttributeMapping.ATTRIBUTE)))
				{
					out.add(row.getOccurrencePrimaryKey().format());
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Applies {@link WithdrawalRule#decide} to every {@code QualityRule}/{@code MappingStep} row in the
	 * project's scope: a still-{@code PROPOSED} row whose attribute/mapping is no longer fed by an
	 * accepted-like mapping THIS RUN is deleted outright (nobody decided on it - see {@link WithdrawalRule}'s
	 * own Javadoc); a row a person already decided on is left completely untouched, only counted, so the
	 * review screen can flag it instead (task B: a coverage warning on the line, computed the same way at
	 * render time, never persisted here). {@code pCoveredAttributeIds}/{@code pCoveredMappingIds} are exactly
	 * the sets the main pass above just finished profiling - the SAME accepted-mapping read, never a second,
	 * possibly-inconsistent one.
	 * <p>
	 * <b>A second, independent reason a {@code RULE}-origin row is "uncovered" (quality/LLM lot,
	 * 2026-09-21)</b>: its own {@code kind} is one this build's {@link Detectors}/{@link QualityDetectors} no
	 * longer proposes at all (the domain-heuristics removal - e.g. an old {@code COUNTRY_TO_ISO2} or {@code
	 * CHECK_DIGIT} row proposed under a Java heuristic this build deleted). Nothing will ever re-confirm such
	 * a row again, exactly the same situation as a mapping later rejected - so it is withdrawn (if still
	 * {@code PROPOSED}) or flagged (if already decided) the same way, via {@link Detectors#RULE_PROPOSABLE_KINDS}/
	 * {@link QualityDetectors#RULE_PROPOSABLE_KINDS}. A row of origin {@code LLM}/{@code USER} is never
	 * subject to this second check - only a {@code kind} THIS class's own RULE pass might once have produced
	 * can go stale this way; an LLM-proposed {@code CHECK_DIGIT} row, for instance, is a legitimate proposal
	 * under the CURRENT system and must never be swept just because the OLD RULE pass no longer covers that
	 * kind.
	 */
	private static WithdrawalCounters withdrawStaleProposals(final Session pSession, final Adaptation pFactory, final String pProjectId,
		final Set<String> pCoveredAttributeIds, final Set<String> pCoveredMappingIds) throws OperationException
	{
		final WithdrawalCounters counters = new WithdrawalCounters();
		final Set<String> projectAttributeIds = QualitySuggester.projectAttributeIds(pFactory, pProjectId);
		final Set<String> projectMappingIds = QualitySuggester.projectMappingIds(pFactory, projectAttributeIds);

		final List<Adaptation> rulesToDelete = new ArrayList<Adaptation>();
		final AdaptationTable rulesTable = pFactory.getTable(FactoryPaths.QUALITY_RULE);
		final RequestResult ruleRows = rulesTable.createRequestResult(null);
		try
		{
			for (Adaptation row = ruleRows.nextAdaptation(); row != null; row = ruleRows.nextAdaptation())
			{
				final String attributeId = row.getString(FactoryPaths.QualityRule.ATTRIBUTE);
				if (!projectAttributeIds.contains(attributeId))
				{
					continue; // another project's row
				}
				final boolean mappingCovered = pCoveredAttributeIds.contains(attributeId);
				final boolean covered = mappingCovered && QualitySuggester.kindStillRuleProposable("RULE".equals(row.getString(FactoryPaths.QualityRule.ORIGIN)),
					row.getString(FactoryPaths.QualityRule.KIND), QualityDetectors.RULE_PROPOSABLE_KINDS);
				final WithdrawalRule.Action action = WithdrawalRule.decide(row.getString(FactoryPaths.QualityRule.STATUS), covered);
				if (action == WithdrawalRule.Action.WITHDRAW)
				{
					rulesToDelete.add(row);
				}
				else if (action == WithdrawalRule.Action.WARN)
				{
					counters.rulesFlagged++;
				}
			}
		}
		finally
		{
			ruleRows.close();
		}

		final List<Adaptation> stepsToDelete = new ArrayList<Adaptation>();
		final AdaptationTable stepsTable = pFactory.getTable(FactoryPaths.MAPPING_STEP);
		final RequestResult stepRows = stepsTable.createRequestResult(null);
		try
		{
			for (Adaptation row = stepRows.nextAdaptation(); row != null; row = stepRows.nextAdaptation())
			{
				final String mappingId = row.getString(FactoryPaths.MappingStep.MAPPING);
				if (!projectMappingIds.contains(mappingId))
				{
					continue; // another project's row
				}
				final boolean mappingCovered = pCoveredMappingIds.contains(mappingId);
				// staging normalisation lot D (2026-10-17): a row of the normalisation pass (UPPER/LOWER/TITLE_CASE/STRIP_CHARS/transcoding...) is kept
				// while its mapping is covered, whatever its kind; ebx.NormalisationSuggester withdraws its own rows itself
				final boolean covered = mappingCovered && (NormalisationPass.isOwnRationale(row.getString(FactoryPaths.MappingStep.RATIONALE))
					|| QualitySuggester.kindStillRuleProposable("RULE".equals(row.getString(FactoryPaths.MappingStep.ORIGIN)), row.getString(FactoryPaths.MappingStep.KIND),
						Detectors.RULE_PROPOSABLE_KINDS));
				final WithdrawalRule.Action action = WithdrawalRule.decide(row.getString(FactoryPaths.MappingStep.STATUS), covered);
				if (action == WithdrawalRule.Action.WITHDRAW)
				{
					stepsToDelete.add(row);
				}
				else if (action == WithdrawalRule.Action.WARN)
				{
					counters.stepsFlagged++;
				}
			}
		}
		finally
		{
			stepRows.close();
		}

		if (rulesToDelete.isEmpty() && stepsToDelete.isEmpty())
		{
			return counters;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Adaptation row : rulesToDelete)
				{
					pContext.doDelete(row.getAdaptationName(), false);
				}
				for (final Adaptation row : stepsToDelete)
				{
					pContext.doDelete(row.getAdaptationName(), false);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.withdrawProposals", "Withdrawing stale mapping step/quality rule proposals failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		counters.rulesWithdrawn = rulesToDelete.size();
		counters.stepsWithdrawn = stepsToDelete.size();
		return counters;
	}

	/** {@code true} unless {@code pIsRuleOrigin} and {@code pKind} is no longer one the current RULE pass could ever propose - see {@link #withdrawStaleProposals}'s own Javadoc for why this only ever applies to {@code RULE}-origin rows. */
	private static boolean kindStillRuleProposable(final boolean pIsRuleOrigin, final String pKind, final java.util.Set<String> pProposableKinds)
	{
		return !pIsRuleOrigin || pProposableKinds.contains(pKind);
	}

	/** Joins the ALREADY-SAFE examples the pure layer produced (see the class Javadoc) - no redaction decision of any kind is made here any more. */
	private static String examplesText(final List<String> pExamples)
	{
		if (pExamples == null || pExamples.isEmpty())
		{
			return null;
		}
		final StringBuilder out = new StringBuilder();
		for (final String example : pExamples)
		{
			if (out.length() > 0)
			{
				out.append("; ");
			}
			out.append(example);
		}
		return TextLimits.truncate(out.toString(), QualitySuggester.EXAMPLES_MAX_LENGTH);
	}

}
