package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.staging.StagingModel;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;

/**
 * One project's own {@code StagingEntity}/{@code StagingAttribute} rows, read into {@code
 * StagingModel.EntityInfo}/{@code AttributeInfo} - the exact shape {@code MasterModel.buildFromStaging}
 * needs to answer "which entities of this project are actually promoted to the master zone"
 * ({@code MasterModel.Result#builtEntities}) and, from there, each one's table path
 * ({@code MasterModel.tablePath(entityName)}).
 * <p>
 * <b>Extracted, not a third copy</b> (coordinator review, 2026-09-22, "Record lineage" record-level action
 * lot): before this class, {@code RecordLineageReader.read} and {@code RecordLineageUserService}'s own
 * project/entity selector each carried their own private, near-identical version of this reader. The
 * module-level activation cache this lot adds ({@link MasterZoneIndex}) needed the exact same resolution
 * again, for every project at once - rather than writing a third copy, this class is now the ONE place that
 * reads a project's entities this way, and the two pre-existing call sites were changed to use it (their own
 * behaviour is unchanged: same predicate, same fields, same {@code memberEntities} resolution).
 * {@code ebx.StagingLoader}/{@code ebx.MasterLoader}/{@code ebx.MasterGenerator} each keep their OWN separate
 * copy of a similar reader - not touched here, matching this codebase's own established "self-contained, if
 * slightly repeated, thin reader" choice for classes this lot must not edit (see {@code
 * RecordLineageUserService}'s own Javadoc, and {@code FactoryCockpitUserService}'s for the same reasoning).
 */
public final class ProjectMasterEntities
{
	private ProjectMasterEntities()
	{
	}

	/**
	 * {@link #readEntities}'s answer: the entities themselves, plus the id-&gt;name map some callers (a
	 * {@code MatchPlan}/{@code AttributeMapping} lookup keyed by entity id, as {@code RecordLineageReader.read}
	 * needs) still require. <b>id-&gt;name, not the other way round</b> - {@code RecordLineageReader}'s own
	 * pre-existing {@code readEntities} took a name-&gt;id output parameter instead (its long-standing
	 * contract, kept for that class's own callers); its wrapper around this method inverts this map, once,
	 * rather than this shared helper guessing which direction a given caller wants.
	 */
	public static final class Result
	{
		public final List<StagingModel.EntityInfo> entities;
		public final Map<String, String> entityIdToName;

		Result(final List<StagingModel.EntityInfo> pEntities, final Map<String, String> pEntityIdToName)
		{
			this.entities = pEntities;
			this.entityIdToName = pEntityIdToName;
		}
	}

	/** Every {@code StagingEntity} of the project, whatever its status - {@code MasterModel.buildFromStaging} is the one that decides which of them are actually promoted. */
	public static Result readEntities(final Adaptation pFactory, final String pProjectId)
	{
		final List<Adaptation> entityRowsList = new ArrayList<Adaptation>();
		final Map<String, String> entityIdToName = new LinkedHashMap<String, String>();
		final RequestResult entityRows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ENTITY), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				entityRowsList.add(row);
				final String id = row.getOccurrencePrimaryKey().format();
				final String name = UserView.string(row, FactoryPaths.StagingEntity.NAME);
				entityIdToName.put(id, name);
			}
		}
		finally
		{
			entityRows.close();
		}

		final List<StagingModel.EntityInfo> out = new ArrayList<StagingModel.EntityInfo>();
		for (final Adaptation row : entityRowsList)
		{
			final String entityId = row.getOccurrencePrimaryKey().format();
			final List<String> memberNames = new ArrayList<String>();
			final List<?> rawMemberIds = UserView.values(row, FactoryPaths.StagingEntity.MEMBER_ENTITIES);
			if (rawMemberIds != null)
			{
				for (final Object rawMemberId : rawMemberIds)
				{
					final String memberName = entityIdToName.get(String.valueOf(rawMemberId));
					if (memberName != null)
					{
						memberNames.add(memberName);
					}
				}
			}
			out.add(new StagingModel.EntityInfo(UserView.string(row, FactoryPaths.StagingEntity.NAME), UserView.string(row, FactoryPaths.StagingEntity.LABEL),
				UserView.string(row, FactoryPaths.StagingEntity.STATUS), ProjectMasterEntities.readAttributes(pFactory, entityId, entityIdToName), memberNames));
		}
		return new Result(out, entityIdToName);
	}

	private static List<StagingModel.AttributeInfo> readAttributes(final Adaptation pFactory, final String pEntityId, final Map<String, String> pEntityIdToName)
	{
		final List<StagingModel.AttributeInfo> out = new ArrayList<StagingModel.AttributeInfo>();
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object rawMaxLength = UserView.get(row, FactoryPaths.StagingAttribute.MAX_LENGTH);
				final Integer maxLength = rawMaxLength instanceof Number ? Integer.valueOf(((Number) rawMaxLength).intValue()) : null;
				final boolean mandatory = Boolean.TRUE.equals(UserView.get(row, FactoryPaths.StagingAttribute.MANDATORY));
				final boolean primaryKey = Boolean.TRUE.equals(UserView.get(row, FactoryPaths.StagingAttribute.PRIMARY_KEY));
				final String referenceEntityId = UserView.string(row, FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
				final String referenceEntityName = referenceEntityId == null ? null : pEntityIdToName.get(referenceEntityId);
				out.add(new StagingModel.AttributeInfo(UserView.string(row, FactoryPaths.StagingAttribute.NAME), UserView.string(row, FactoryPaths.StagingAttribute.LABEL),
					UserView.string(row, FactoryPaths.StagingAttribute.DATA_TYPE), maxLength, mandatory, primaryKey, referenceEntityName,
					UserView.string(row, FactoryPaths.StagingAttribute.STATUS)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Every promoted master table path of one project ({@code MasterModel.tablePath(entityName)} for each name in {@code MasterModel.buildFromStaging(...).builtEntities}) - the one computation {@link MasterZoneIndex} needs, for every project, on a cache refresh. */
	public static List<String> promotedMasterTablePaths(final Adaptation pFactory, final String pProjectId)
	{
		final Result entities = ProjectMasterEntities.readEntities(pFactory, pProjectId);
		final com.on.ebx.factory.master.MasterModel.Result masterModel = com.on.ebx.factory.master.MasterModel.buildFromStaging(entities.entities);
		final List<String> out = new ArrayList<String>();
		for (final String entityName : masterModel.builtEntities)
		{
			out.add(com.on.ebx.factory.master.MasterModel.tablePath(entityName));
		}
		return out;
	}
}
