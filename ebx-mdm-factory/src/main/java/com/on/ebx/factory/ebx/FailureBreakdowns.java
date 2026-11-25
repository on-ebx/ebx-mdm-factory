package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.Detectors;
import com.on.ebx.factory.quality.FailureBreakdown;
import com.on.ebx.factory.quality.QualityDetectors;
import com.on.ebx.factory.quality.QualityLlmPass;
import com.on.ebx.factory.staging.MappingScope;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Validation desk lot L4 (2026-10-22): the EBX-bound half of {@link FailureBreakdown} - reads, for one column of an entity, the joint distinct counts with the other
 * attributes mapped from the SAME landing table ({@link #joints}), and measures a stored {@code QualityRule} on them ({@link #measureRule}, what the desk's "Measure"
 * button runs as a background job). The reads are the study's own: {@link QualitySuggester#distinctValues} with the grouping column as the scope field (the same cache, the same
 * bounds - a whole-table SQL group-by, a sample of the first rows above {@code mdm.factory.profile.maxRowsFullScan}), never a second reader.
 * <p>
 * <b>Which attributes are offered as a grouping:</b> another attribute of the entity, mapped from the same landing table as the column's mapping, not sensitive for the
 * instance's {@link LlmDataPolicy}, with at most {@link FailureBreakdown#MAX_GROUPS} distinct values; at most {@link #MAX_CANDIDATES} of them (the ones with the fewest
 * values first), so a wide entity costs a bounded number of reads.
 */
final class FailureBreakdowns
{
	/** The most grouping attributes read per column. */
	static final int MAX_CANDIDATES = 8;

	private FailureBreakdowns()
	{
	}

	/**
	 * The joint counts of {@code pColumnKey} ({@code entity.attribute}) with each candidate grouping attribute of {@code pMappingsByAttribute} (the entity's mappings grouped by
	 * {@code entity.attribute}). Empty when the column is not text, has no landing table, or nothing qualifies.
	 */
	static List<FailureBreakdown.Joint> joints(final Session pSession, final Adaptation pFactory, final String pColumnKey,
		final Map<String, List<QualitySuggester.MappingRow>> pMappingsByAttribute, final LlmDataPolicy pPolicy, final String pLandingDataspace,
		final Map<String, QualitySuggester.LandingTableRef> pLandingTableCache)
	{
		final List<FailureBreakdown.Joint> out = new ArrayList<FailureBreakdown.Joint>();
		final List<QualitySuggester.MappingRow> columnMappings = pMappingsByAttribute.get(pColumnKey);
		if (columnMappings == null || columnMappings.isEmpty() || pLandingDataspace == null || pLandingDataspace.trim().isEmpty()
			|| !Detectors.isTextLike(columnMappings.get(0).dataType))
		{
			return out;
		}
		// the candidates, the fewest known distinct values first
		final List<Map.Entry<String, List<QualitySuggester.MappingRow>>> candidates = new ArrayList<Map.Entry<String, List<QualitySuggester.MappingRow>>>();
		for (final Map.Entry<String, List<QualitySuggester.MappingRow>> entry : pMappingsByAttribute.entrySet())
		{
			if (entry.getKey().equals(pColumnKey) || QualityLlmSuggester.anySensitive(pPolicy, entry.getValue()))
			{
				continue;
			}
			final long known = FailureBreakdowns.knownDistinct(entry.getValue());
			if (known > FailureBreakdown.MAX_GROUPS)
			{
				continue;
			}
			candidates.add(entry);
		}
		Collections.sort(candidates, new Comparator<Map.Entry<String, List<QualitySuggester.MappingRow>>>()
		{
			@Override
			public int compare(final Map.Entry<String, List<QualitySuggester.MappingRow>> pLeft, final Map.Entry<String, List<QualitySuggester.MappingRow>> pRight)
			{
				final int byCount = Long.compare(FailureBreakdowns.knownDistinct(pLeft.getValue()), FailureBreakdowns.knownDistinct(pRight.getValue()));
				return byCount != 0 ? byCount : pLeft.getKey().compareTo(pRight.getKey());
			}
		});
		for (final Map.Entry<String, List<QualitySuggester.MappingRow>> candidate : candidates)
		{
			if (out.size() >= FailureBreakdowns.MAX_CANDIDATES)
			{
				break;
			}
			final List<Detectors.ValueCount> pairs = new ArrayList<Detectors.ValueCount>();
			boolean tooMany = false;
			for (final QualitySuggester.MappingRow mapping : columnMappings)
			{
				for (final QualitySuggester.MappingRow group : candidate.getValue())
				{
					if (mapping.landingTableId == null || !mapping.landingTableId.equals(group.landingTableId))
					{
						continue; // not the same landing rows: a value of the other table says nothing about this row
					}
					final QualitySuggester.LandingTableRef ref = QualitySuggester.landingTableRef(pFactory, pLandingDataspace, mapping, pLandingTableCache);
					if (ref == null)
					{
						continue;
					}
					if (QualitySuggester.exactAggregates(pSession, ref, group.fieldName).distinctCount > FailureBreakdown.MAX_GROUPS)
					{
						tooMany = true;
						break;
					}
					pairs.addAll(QualitySuggester.distinctValues(pSession, ref, mapping.fieldName, group.fieldName, QualitySuggester.DEFAULT_MAX_DISTINCT_VALUES).values);
				}
				if (tooMany)
				{
					break;
				}
			}
			final FailureBreakdown.Joint joint = new FailureBreakdown.Joint(candidate.getValue().get(0).attributeName, pairs);
			if (!tooMany && joint.groupCount() >= 2 && joint.groupCount() <= FailureBreakdown.MAX_GROUPS)
			{
				out.add(joint);
			}
		}
		return out;
	}

	/** The largest catalog-profiled distinct count among the mappings of an attribute, or 0 when none was profiled (unknown: the exact count decides later). */
	private static long knownDistinct(final List<QualitySuggester.MappingRow> pMappings)
	{
		long max = 0;
		for (final QualitySuggester.MappingRow mapping : pMappings)
		{
			if (mapping.distinctCount != null)
			{
				max = Math.max(max, mapping.distinctCount.longValue());
			}
		}
		return max;
	}

	/** Writes {@code QualityRule.failureBreakdown}; {@code pJson} null clears it. */
	static void store(final Session pSession, final Adaptation pFactory, final String pRuleId, final String pJson) throws OperationException
	{
		final Adaptation rule = pFactory.getTable(FactoryPaths.QUALITY_RULE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRuleId));
		if (rule == null)
		{
			return;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(rule.getAdaptationName());
				value.setValue(pJson, FactoryPaths.QualityRule.FAILURE_BREAKDOWN);
				pContext.doModifyContent(rule, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveQualityProposals", "Writing the failure breakdown failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
	}

	/**
	 * Measures one stored rule: where it fails, by the best separating attribute of its entity, on the whole landing tables like the study's own figures. Writes the breakdown, or
	 * the "measured, nothing found" marker when nothing fails or no attribute separates the failures. The rule kinds the transfer evaluates per value only; any other is a no-op.
	 *
	 * @return the number of groups of the stored breakdown, 0 when none
	 */
	static int measureRule(final Session pSession, final Adaptation pFactory, final String pRuleId) throws OperationException
	{
		final Adaptation rule = pFactory.getTable(FactoryPaths.QUALITY_RULE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRuleId));
		if (rule == null)
		{
			throw EngineRefusal.coded("missing.qualityRule", "The check " + pRuleId + " does not exist any more.", pRuleId);
		}
		final String kind = rule.getString(FactoryPaths.QualityRule.KIND);
		if (!QualityDetectors.PATTERN.equals(kind) && !QualityDetectors.REFERENCE.equals(kind) && !QualityDetectors.CHECK_DIGIT.equals(kind))
		{
			return 0;
		}
		final Adaptation entity = pFactory.getTable(FactoryPaths.STAGING_ENTITY).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(rule.getString(FactoryPaths.QualityRule.ENTITY)));
		final Adaptation attribute = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE)
			.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(rule.getString(FactoryPaths.QualityRule.ATTRIBUTE)));
		if (entity == null || attribute == null)
		{
			return 0;
		}
		final String projectId = entity.getString(FactoryPaths.StagingEntity.PROJECT);
		final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectId));
		final String entityName = entity.getString(FactoryPaths.StagingEntity.NAME);
		final String key = entityName + "." + attribute.getString(FactoryPaths.StagingAttribute.NAME);

		final Map<String, List<QualitySuggester.MappingRow>> byAttribute = new LinkedHashMap<String, List<QualitySuggester.MappingRow>>();
		for (final QualitySuggester.MappingRow mapping : QualitySuggester.readMappings(pFactory, projectId, MappingScope.PROPOSAL))
		{
			if (!entityName.equals(mapping.entityName))
			{
				continue;
			}
			final String attributeKey = mapping.entityName + "." + mapping.attributeName;
			List<QualitySuggester.MappingRow> list = byAttribute.get(attributeKey);
			if (list == null)
			{
				list = new ArrayList<QualitySuggester.MappingRow>();
				byAttribute.put(attributeKey, list);
			}
			list.add(mapping);
		}
		if (!byAttribute.containsKey(key))
		{
			return 0;
		}
		final LlmDataPolicy policy = LlmDataPolicy.of(pFactory);
		QualityLlmPass.Mode mode = QualityLlmPass.Mode.valueOf(policy.levelFor(project).name());
		if (mode == QualityLlmPass.Mode.SAMPLE_VALUES && QualityLlmSuggester.anySensitive(policy, byAttribute.get(key)))
		{
			mode = QualityLlmPass.Mode.SHAPES_ONLY; // a sensitive column never shows a real value, not even as an example
		}
		final List<String> notes = new ArrayList<String>();
		final CleaningSteps.Lookups lookups = QualitySuggester.buildLookups(pFactory, notes);
		final List<FailureBreakdown.Joint> joints = FailureBreakdowns.joints(pSession, pFactory, key, byAttribute, policy,
			project.getString(FactoryPaths.Project.LANDING_DATASPACE), new java.util.HashMap<String, QualitySuggester.LandingTableRef>());
		final FailureBreakdown.Result result = QualityLlmPass.breakdown(kind, rule.getString(FactoryPaths.QualityRule.ARGUMENT),
			new QualityLlmPass.ColumnEvidence(null, null, 0, 0, joints), lookups, mode);
		FailureBreakdowns.store(pSession, pFactory, pRuleId, result == null ? FailureBreakdown.NOT_FOUND_JSON : FailureBreakdown.toJson(result));
		return result == null ? 0 : result.groups.size();
	}
}
