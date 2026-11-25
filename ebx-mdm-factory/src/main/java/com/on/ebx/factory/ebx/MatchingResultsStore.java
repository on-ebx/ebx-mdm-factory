package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.matching.MatchResultsSnapshot;
import com.on.ebx.factory.matching.MatchResultsView;
import com.on.ebx.factory.matching.PairRows;
import com.on.ebx.factory.review.MatchPlanFacts;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;

/**
 * Wave 2 (2026-09-30, lot V2-A): where the matching results of wizard step 6 are computed, stored and patched - the EBX-bound
 * half of {@link MatchResultsSnapshot}. Read that class first for why (a page write must never run the whole-table aggregates
 * behind the block) and for the JSON shape.
 * <ul>
 * <li><b>Computed</b> by {@link #compute}: at the end of {@code MatchingConfigurator.run} for each entity that ran (inside the
 * job, after the golden-key refresh), by the {@link MatchingResultsRun} job (a refresh a person asked for, or queued by a
 * decision), never by the wizard while it renders.</li>
 * <li><b>Stored</b> as the {@code results} member of the {@code detail} of the entity's latest {@code MATCHING_RUN}
 * {@code PipelineStep} row ({@link #store}). A run in progress or failed has no {@code results} - the block then reads
 * MISSING until the run ends. No XSD change (a new {@code PipelineStep.step} key would be stored but flagged invalid: the
 * field is a static enumeration).</li>
 * <li><b>Patched</b> by {@link #applyDecisions} when pairs are decided from the queue: the pairs leave the list and the
 * pending/different counts move at once, the snapshot is flagged stale and a {@link MatchingResultsRun} refresh is queued by
 * the caller.</li>
 * <li><b>Epoch</b>: {@link #epoch} is bumped by every decision and every stored snapshot of a project; the step-8 summary
 * cache keys on it (a decision changes golden counts without any new pipeline step row).</li>
 * </ul>
 */
public final class MatchingResultsStore
{
	static final String RUN_STEP = "MATCHING_RUN";
	private static final int DEFAULT_BULK_THRESHOLD = 80;
	private static final ConcurrentMap<String, AtomicLong> EPOCHS = new ConcurrentHashMap<String, AtomicLong>();

	private MatchingResultsStore()
	{
	}

	// ------------------------------------------------------------------------------------------------ epoch

	/** A number that changes whenever the matching outcome of the project may have changed (a stored snapshot, a decision). Process-wide, in memory. */
	public static long epoch(final String pProjectId)
	{
		final AtomicLong value = MatchingResultsStore.EPOCHS.get(pProjectId);
		return value == null ? 0L : value.get();
	}

	static void bump(final String pProjectId)
	{
		AtomicLong value = MatchingResultsStore.EPOCHS.get(pProjectId);
		if (value == null)
		{
			final AtomicLong created = new AtomicLong();
			value = MatchingResultsStore.EPOCHS.putIfAbsent(pProjectId, created);
			if (value == null)
			{
				value = created;
			}
		}
		value.incrementAndGet();
	}

	// ------------------------------------------------------------------------------------------------ compute

	/**
	 * The snapshot JSON of {@code pEntityName}, computed now (the whole-table aggregates and the pairs - see {@code
	 * MatchingResultsBuilder#build}), or null when the master table or its metadataset is not reachable (matching never
	 * activated) or the computation failed - a failure is logged, never thrown: a results block must not fail a matching run.
	 */
	public static String compute(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pProjectId, final String pEntityName)
	{
		try
		{
			final MatchingResultsBuilder.Context context = new MatchingResultsBuilder.Context(pProjectCode, pEntityName, pEntityName,
				MatchingResultsStore.compareFields(pFactory, pProjectId, pEntityName), MatchingResultsStore.DEFAULT_BULK_THRESHOLD, "PENDING", "", false, null, null,
				MatchingResultsStore.pairSpec(pFactory, pProjectId, pEntityName));
			final MatchResultsView.Input input = MatchingResultsBuilder.build(pSession, pFactory, context);
			if (input == null)
			{
				return null;
			}
			return MatchResultsSnapshot.encode(input, System.currentTimeMillis(), false);
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not compute the matching results of " + pEntityName + " (project " + pProjectCode + ")", ex);
			return null;
		}
	}

	/**
	 * The fields compared side by side in a pair: the entity's own attributes that are neither the technical identity, nor the
	 * source's business key, nor a reference to another entity - in the order the staging attribute rows come. The rule the
	 * wizard applied to its own snapshot ({@code ProjectWizardUserService}), now shared so the stored pairs and a bulk merge
	 * use the same list.
	 */
	public static List<String> compareFields(final Adaptation pFactory, final String pProjectId, final String pEntityName)
	{
		final List<String> out = new ArrayList<String>();
		final String entityId = MatchingResultsStore.entityId(pFactory, pProjectId, pEntityName);
		if (entityId == null)
		{
			return out;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(entityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String reference = row.getString(FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
				if (MatchingResultsStore.isCompared(Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.PRIMARY_KEY)),
					Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.BUSINESS_KEY)), reference != null && !reference.isEmpty())
					&& !PairRows.isTechnicalKey(row.getString(FactoryPaths.StagingAttribute.DERIVATION)))
				{
					out.add(row.getString(FactoryPaths.StagingAttribute.NAME));
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
	 * Step 6 pair card (2026-10-18): what the card compares and how it names things - the entity's compared attributes in staging order with their labels, the keys the plan
	 * derived from raw fields ({@code telephoneE164} from {@code telephone}), the plan's fields, the fields that name a record, and the child entities (an address) the card
	 * summarises. Read once per computation, never per pair.
	 */
	public static PairRows.Spec pairSpec(final Adaptation pFactory, final String pProjectId, final String pEntityName)
	{
		final PairRows.Spec spec = new PairRows.Spec();
		final String entityId = MatchingResultsStore.entityId(pFactory, pProjectId, pEntityName);
		if (entityId == null)
		{
			return spec;
		}
		final String requirements = MatchingResultsStore.planRequirements(pFactory, entityId);
		final java.util.List<String> planFields = MatchPlanFacts.planFields(requirements);
		final java.util.Set<String> inPlan = new java.util.HashSet<String>(planFields);
		final java.util.List<Adaptation> rows = new ArrayList<Adaptation>();
		final java.util.Map<String, String> nameById = new java.util.HashMap<String, String>();
		final RequestResult attributes = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(entityId));
		try
		{
			for (Adaptation row = attributes.nextAdaptation(); row != null; row = attributes.nextAdaptation())
			{
				rows.add(row);
				nameById.put(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.StagingAttribute.NAME));
			}
		}
		finally
		{
			attributes.close();
		}
		final java.util.Set<String> derivedNames = new java.util.HashSet<String>();
		for (final Adaptation row : rows)
		{
			final String name = row.getString(FactoryPaths.StagingAttribute.NAME);
			final String reference = row.getString(FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
			if (!MatchingResultsStore.isCompared(Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.PRIMARY_KEY)),
				Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.BUSINESS_KEY)), reference != null && !reference.isEmpty()))
			{
				continue;
			}
			final String derivedFromId = row.getString(FactoryPaths.StagingAttribute.DERIVED_FROM);
			final String derivedFrom = derivedFromId == null || derivedFromId.isEmpty() || nameById.get(derivedFromId) == null ? "" : nameById.get(derivedFromId);
			if (!derivedFrom.isEmpty() || PairRows.isTechnicalKey(row.getString(FactoryPaths.StagingAttribute.DERIVATION)))
			{
				derivedNames.add(name); // a derived or technical key never names a record
			}
			// the keys derived from the child rows are a matching device, never a row of the card (the child summary stands in): PairRows leaves them out of the rows, the label stays for a rule's wording
			spec.attrs.add(new PairRows.Attr(name, row.getString(FactoryPaths.StagingAttribute.LABEL), derivedFrom, inPlan.contains(name),
				PairRows.isTechnicalKey(row.getString(FactoryPaths.StagingAttribute.DERIVATION))));
		}
		for (final PairRows.Attr attr : spec.attrs)
		{
			spec.labels.put(attr.name, attr.label);
		}
		for (final PairRows.Attr attr : spec.attrs)
		{
			if (!attr.derivedFrom.isEmpty() && spec.labels.containsKey(attr.derivedFrom))
			{
				spec.labels.put(attr.name, spec.labels.get(attr.derivedFrom)); // a derived key reads as the field it was made from
			}
		}
		spec.planNodeNames.addAll(MatchPlanFacts.nodes(requirements).keySet());
		for (final String name : MatchPlanFacts.similarityFields(requirements))
		{
			if (spec.nameFields.size() < 2 && spec.labels.containsKey(name) && !derivedNames.contains(name))
			{
				spec.nameFields.add(name);
			}
		}
		// the child entities: those with an attribute that references this entity
		final RequestResult referencing = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./referenceEntity=" + CatalogReader.quote(entityId));
		final java.util.Map<String, String> fkByChild = new java.util.LinkedHashMap<String, String>();
		try
		{
			for (Adaptation row = referencing.nextAdaptation(); row != null; row = referencing.nextAdaptation())
			{
				final String childId = row.getString(FactoryPaths.StagingAttribute.ENTITY);
				if (childId != null && !childId.equals(entityId) && !fkByChild.containsKey(childId))
				{
					fkByChild.put(childId, row.getString(FactoryPaths.StagingAttribute.NAME));
				}
			}
		}
		finally
		{
			referencing.close();
		}
		for (final java.util.Map.Entry<String, String> child : fkByChild.entrySet())
		{
			if (spec.children.size() >= 2)
			{
				break;
			}
			final Adaptation childEntity = MatchingResultsStore.row(pFactory.getTable(FactoryPaths.STAGING_ENTITY), child.getKey());
			if (childEntity == null)
			{
				continue;
			}
			final java.util.List<String> shown = new ArrayList<String>();
			final RequestResult childAttributes = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(child.getKey()));
			try
			{
				for (Adaptation row = childAttributes.nextAdaptation(); row != null && shown.size() < 4; row = childAttributes.nextAdaptation())
				{
					final String reference = row.getString(FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
					final String derivation = row.getString(FactoryPaths.StagingAttribute.DERIVATION);
					if (MatchingResultsStore.isCompared(Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.PRIMARY_KEY)),
						Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.BUSINESS_KEY)), reference != null && !reference.isEmpty())
						&& (derivation == null || derivation.isEmpty()))
					{
						shown.add(row.getString(FactoryPaths.StagingAttribute.NAME));
					}
				}
			}
			finally
			{
				childAttributes.close();
			}
			final String label = childEntity.getString(FactoryPaths.StagingEntity.LABEL);
			spec.children.add(new PairRows.Child(childEntity.getString(FactoryPaths.StagingEntity.NAME), label, child.getValue(), shown));
		}
		for (final PairRows.Attr attr : spec.attrs)
		{
			if (attr.technical && spec.children.size() == 1)
			{
				spec.labels.put(attr.name, spec.children.get(0).label); // "same value on <the child objects>" rather than the label of a technical key
			}
		}
		return spec;
	}

	/** The {@code requirements} JSON of the entity's plan (the accepted or applied one, else the latest), or null. */
	private static String planRequirements(final Adaptation pFactory, final String pEntityId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.MATCH_PLAN).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			String fallback = null;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String status = row.getString(FactoryPaths.MatchPlan.STATUS);
				final String requirements = row.getString(FactoryPaths.MatchPlan.REQUIREMENTS);
				if ("ACCEPTED".equals(status) || "APPLIED".equals(status))
				{
					return requirements;
				}
				fallback = requirements;
			}
			return fallback;
		}
		finally
		{
			rows.close();
		}
	}

	private static Adaptation row(final com.onwbp.adaptation.AdaptationTable pTable, final String pKey)
	{
		try
		{
			return pTable.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(pKey));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** Pure: the compared-field rule of {@link #compareFields}. */
	static boolean isCompared(final boolean pPrimaryKey, final boolean pBusinessKey, final boolean pHasReference)
	{
		return !pPrimaryKey && !pBusinessKey && !pHasReference;
	}

	private static String entityId(final Adaptation pFactory, final String pProjectId, final String pEntityName)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ENTITY)
			.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./name=" + CatalogReader.quote(pEntityName));
		try
		{
			final Adaptation row = rows.nextAdaptation();
			return row == null ? null : row.getOccurrencePrimaryKey().format();
		}
		finally
		{
			rows.close();
		}
	}

	// ------------------------------------------------------------------------------------------------ store

	/** The latest {@code MATCHING_RUN} row of the entity in this project (by start time), or null. */
	static Adaptation latestRunRow(final Adaptation pFactory, final String pProjectId, final String pEntityName)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./step="
			+ CatalogReader.quote(MatchingResultsStore.RUN_STEP) + " and " + PipelineSteps.scopeEquals("./scope", pEntityName));
		try
		{
			Adaptation latest = null;
			Date latestAt = null;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object startedAt = row.get(FactoryPaths.PipelineStep.STARTED_AT);
				final Date at = startedAt instanceof Date ? (Date) startedAt : null;
				if (latest == null || (at != null && (latestAt == null || at.after(latestAt))))
				{
					latest = row;
					latestAt = at;
				}
			}
			return latest;
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * Stores {@code pSnapshotJson} in the latest run row of the entity. False (nothing written) when the entity has no run row,
	 * when that row is not DONE (still RUNNING - its own end carries the results -, failed, stopped, or RESET by a step reset), or
	 * when the write failed.
	 */
	public static boolean store(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityName, final String pSnapshotJson)
	{
		final Adaptation row = MatchingResultsStore.latestRunRow(pFactory, pProjectId, pEntityName);
		if (row == null || !"DONE".equals(row.getString(FactoryPaths.PipelineStep.STATUS)))
		{
			return false; // still RUNNING (its own end carries the results), FAILED/STOPPED, or RESET by a step reset: nothing to describe
		}
		final String merged = MatchResultsSnapshot.mergeIntoDetail(row.getString(FactoryPaths.PipelineStep.DETAIL), pSnapshotJson);
		final boolean ok = PipelineSteps.updateDetail(pSession, pFactory, row.getOccurrencePrimaryKey().format(), merged);
		if (ok)
		{
			MatchingResultsStore.bump(pProjectId);
		}
		return ok;
	}

	/** The stored snapshot of the entity as its latest run row holds it, or null when there is none - a read of one row, for callers that do not already hold the run's detail. */
	public static MatchResultsSnapshot.Decoded read(final Adaptation pFactory, final String pProjectId, final String pEntityName)
	{
		final Adaptation row = MatchingResultsStore.latestRunRow(pFactory, pProjectId, pEntityName);
		return row == null || !"DONE".equals(row.getString(FactoryPaths.PipelineStep.STATUS)) ? null
			: MatchResultsSnapshot.fromDetail(row.getString(FactoryPaths.PipelineStep.DETAIL));
	}

	/**
	 * What every decision path does once pairs were decided: patches the stored snapshot ({@link #applyDecisions}) and queues a
	 * {@link MatchingResultsRun} refresh so the real figures replace the patched ones. Never throws - a bookkeeping failure must
	 * not turn a decision that went through into an error; it is logged.
	 */
	public static void afterDecisions(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pProjectId, final String pEntityName,
		final List<MatchResultsSnapshot.Decision> pDecisions)
	{
		try
		{
			MatchingResultsStore.applyDecisions(pSession, pFactory, pProjectId, pEntityName, pDecisions);
			MatchingResultsRun.start(pSession, pFactory, pProjectCode, pEntityName);
		}
		catch (final Exception ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not update the matching results of " + pEntityName + " after a decision (project " + pProjectCode + ")", ex);
		}
	}

	/**
	 * Patches the stored snapshot for decisions just made ({@link MatchResultsSnapshot#applyDecisions}: pairs leave the list,
	 * counts move, the block is flagged stale) and bumps the epoch. False when there was nothing stored to patch - the epoch is
	 * bumped anyway (the decisions happened). The caller queues the refresh job.
	 */
	public static boolean applyDecisions(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityName,
		final List<MatchResultsSnapshot.Decision> pDecisions)
	{
		MatchingResultsStore.bump(pProjectId);
		if (pDecisions == null || pDecisions.isEmpty())
		{
			return false;
		}
		final Adaptation row = MatchingResultsStore.latestRunRow(pFactory, pProjectId, pEntityName);
		if (row == null || !"DONE".equals(row.getString(FactoryPaths.PipelineStep.STATUS)))
		{
			return false;
		}
		final String detail = row.getString(FactoryPaths.PipelineStep.DETAIL);
		final MatchResultsSnapshot.Decoded decoded = MatchResultsSnapshot.fromDetail(detail);
		if (decoded == null)
		{
			return false;
		}
		final MatchResultsSnapshot.Decoded patched = MatchResultsSnapshot.applyDecisions(decoded, pDecisions);
		final String json = MatchResultsSnapshot.encode(patched.input, patched.computedAt, true);
		return PipelineSteps.updateDetail(pSession, pFactory, row.getOccurrencePrimaryKey().format(), MatchResultsSnapshot.mergeIntoDetail(detail, json));
	}
}
