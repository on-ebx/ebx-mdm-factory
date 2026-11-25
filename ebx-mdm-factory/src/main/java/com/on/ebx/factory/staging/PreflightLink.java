package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.Detectors;

/**
 * Staging preflight lot H1 (2026-10-17): one ACCEPTED link of the project, exactly as the loader will run it, with the landing values it will read - the input of
 * {@link PreflightCheck}. A plain value object: nothing here reads EBX (the reader is {@code ebx.StagingPreflight}), so the whole decision is tested on the real B2C files.
 * <p>
 * The values are the distinct values of the landing column with their rows ({@link Detectors.ValueCount}), the bounded sample (2000 distinct values) every verification of the
 * proposal already reads: no connector call, nothing leaves the instance. {@link #sampled} says the column has more distinct values than were read, so every count of the
 * report is then a lower bound ("at least N").
 */
public final class PreflightLink
{
	public final String mappingId;
	public final String entityName;
	public final String attributeName;
	/** {@code STRING} / {@code INTEGER} / {@code DECIMAL} / {@code BOOLEAN} / {@code DATE} / {@code TIMESTAMP}; blank reads as {@code STRING}. */
	public final String dataType;
	public final Integer maxLength;
	public final boolean mandatory;
	/** The source system the rows come from ({@code ProjectSource.sourceSystem}): what the report names. */
	public final String sourceSystem;
	public final String tableName;
	/** The landing field the link reads (a {@code LandingColumn.fieldName}). */
	public final String column;
	/** The ACCEPTED cleaning steps in position order, the loader's {@code FieldPlan.cleaningSteps}. Never null. */
	public final List<CleaningSteps.Step> steps;
	/** The chain the loader runs after the steps (the mapping's pipeline, or what its type-driven transformation translates to); {@code ""} = none; null = it cannot be translated (not verifiable). */
	public final String pipeline;
	/** The raw distinct values of the column with their rows. Never null. */
	public final List<Detectors.ValueCount> values;
	/** More distinct values exist than {@link #values} holds: the counts are lower bounds. */
	public final boolean sampled;
	/** Exact {@code COUNT(*)} of the landing table, or -1 when unknown. */
	public final long rows;
	/** Exact count of the rows where the column is filled, or -1 when unknown. */
	public final long filledRows;
	/** The attribute is the ONLY business key of its entity: a duplicate of its value inside one source is worth saying. */
	public final boolean singleBusinessKey;
	/** The feed reads a sub-element of the landing row (a collection): the column's values are not the feed's values, nothing is claimed. */
	public final boolean collectionFeed;
	/** The measured profile of the column (candidates for a fix, the phone check); may be null. */
	public final NormalisationProfile profile;

	public PreflightLink(final String pMappingId, final String pEntityName, final String pAttributeName, final String pDataType, final Integer pMaxLength,
		final boolean pMandatory, final String pSourceSystem, final String pTableName, final String pColumn, final List<CleaningSteps.Step> pSteps, final String pPipeline,
		final List<Detectors.ValueCount> pValues, final boolean pSampled, final long pRows, final long pFilledRows, final boolean pSingleBusinessKey,
		final boolean pCollectionFeed, final NormalisationProfile pProfile)
	{
		this.mappingId = pMappingId == null ? "" : pMappingId;
		this.entityName = pEntityName == null ? "" : pEntityName;
		this.attributeName = pAttributeName == null ? "" : pAttributeName;
		this.dataType = pDataType == null || pDataType.trim().isEmpty() ? "STRING" : pDataType.trim();
		this.maxLength = pMaxLength;
		this.mandatory = pMandatory;
		this.sourceSystem = pSourceSystem == null ? "" : pSourceSystem;
		this.tableName = pTableName == null ? "" : pTableName;
		this.column = pColumn == null ? "" : pColumn;
		this.steps = pSteps == null ? Collections.<CleaningSteps.Step>emptyList() : new ArrayList<CleaningSteps.Step>(pSteps);
		this.pipeline = pPipeline == null ? null : pPipeline.trim();
		this.values = pValues == null ? Collections.<Detectors.ValueCount>emptyList() : pValues;
		this.sampled = pSampled;
		this.rows = pRows;
		this.filledRows = pFilledRows;
		this.singleBusinessKey = pSingleBusinessKey;
		this.collectionFeed = pCollectionFeed;
		this.profile = pProfile;
	}
}
