package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.schema.Path;

/**
 * Resolves a MASTER record's own {@code Xref} row by DERIVING its primary key, instead of a predicate scan on
 * {@code masterTable}/{@code masterKey} - volume lot 2a (2026-09-28, {@code docs/VOLUME.md}).
 * <p>
 * <b>Why derive rather than scan.</b> {@code Xref}'s primary key is now the natural key {@code (project,
 * stagingTable, stagingKey)} (this lot) - a staging-side caller (that already knows the staging table/key)
 * gets an indexed PK lookup for free. A MASTER-side caller only has the master record/key, not the staging
 * key - but a master record carries its own {@code sourceSystem}/{@code sourceId} (plain, reserved fields
 * {@code master.MasterModel} always writes verbatim, never renamed by {@code MasterFieldNameRule} - see that
 * class' own Javadoc, "sourceSystem/sourceId move to plain, OPTIONAL fields"), and the STAGING table's own
 * primary key is exactly {@code (sourceSystem, sourceId)} ({@code ebx.StagingLoader}/{@code
 * ebx.MasterLoader.planRow}). So {@code stagingTable.computePrimaryKey(new Object[]{sourceSystem,
 * sourceId}).format()} reconstructs the EXACT {@code stagingKey} value {@code Xref.STAGING_KEY} was written
 * with, and the natural key can be computed and looked up directly - CLAUDE.md &sect;9, never split/build a
 * composite key as a string, always {@code computePrimaryKey(Object[])}/{@code PrimaryKey.format()}.
 * <p>
 * <b>A declared {@code <index>} was considered and rejected</b> (coordinator, 2026-09-28, verified live by
 * {@code javap} on the RC instance's own {@code ebx-lib.jar}: {@code CfTableFunction.setIndex} - the bean
 * behind {@code osd:table} - only sets a deprecated-marker boolean and DISCARDS the value, so {@code
 * <index name="...">...</index>} is a silent no-op on EBX 6.3). The primary key is the ONLY indexed access
 * path this schema can offer - this class exists so a master-side caller reaches it too.
 * <p>
 * Returns {@code null} - never throws - for every case that means "this master record has no {@code Xref}
 * row of its own": a null master record/table, a blank {@code sourceSystem}/{@code sourceId} (an add-on
 * auto-created golden, which never had a source row and therefore never had an {@code Xref} row either), an
 * unresolvable staging table (the project's staging zone was itself removed), or simply no primary-key match.
 */
final class XrefByMasterRecord
{
	private static final Path SOURCE_SYSTEM = Path.parse("./sourceSystem");
	private static final Path SOURCE_ID = Path.parse("./sourceId");

	private XrefByMasterRecord()
	{
	}

	/** Looks the master record up by {@code pMasterKey} first (a plain, already-indexed PK lookup), then delegates to {@link #lookupFor}. */
	static Adaptation lookup(final Adaptation pFactory, final String pProjectId, final String pEntityName, final AdaptationTable pMasterTable,
		final AdaptationTable pStagingTable, final String pMasterKey)
	{
		if (pMasterTable == null || XrefByMasterRecord.blank(pMasterKey))
		{
			return null;
		}
		final Adaptation masterRecord;
		try
		{
			masterRecord = pMasterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pMasterKey));
		}
		catch (final RuntimeException ex)
		{
			return null; // a malformed key - never a reason to fail the whole caller
		}
		return XrefByMasterRecord.lookupFor(pFactory, pProjectId, pEntityName, pStagingTable, masterRecord);
	}

	/** Same lookup, when the master {@link Adaptation} is already at hand - avoids a redundant PK lookup on the master table. */
	static Adaptation lookupFor(final Adaptation pFactory, final String pProjectId, final String pEntityName, final AdaptationTable pStagingTable,
		final Adaptation pMasterRecord)
	{
		if (pFactory == null || pStagingTable == null || pMasterRecord == null || XrefByMasterRecord.blank(pProjectId) || XrefByMasterRecord.blank(pEntityName))
		{
			return null;
		}
		final String sourceSystem = pMasterRecord.getString(XrefByMasterRecord.SOURCE_SYSTEM);
		final String sourceId = pMasterRecord.getString(XrefByMasterRecord.SOURCE_ID);
		if (XrefByMasterRecord.blank(sourceSystem) || XrefByMasterRecord.blank(sourceId))
		{
			return null; // an add-on auto-created golden - never had a source row, never had an Xref row
		}
		final String stagingKey;
		try
		{
			stagingKey = pStagingTable.computePrimaryKey(new Object[] { sourceSystem, sourceId }).format();
		}
		catch (final RuntimeException ex)
		{
			return null; // the staging table's own primary key shape does not match (sourceSystem, sourceId) - should not happen, never a hard failure here
		}
		final AdaptationTable xrefTable = pFactory.getTable(FactoryPaths.XREF);
		try
		{
			return xrefTable.lookupAdaptationByPrimaryKey(xrefTable.computePrimaryKey(new Object[] { pProjectId, pEntityName, stagingKey }));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}
}
