package com.on.ebx.factory.ebx;

import java.util.Date;
import java.util.Locale;

import com.on.ebx.factory.core.CatalogRightMessages;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Deduced-scan lot (2026-09-24, board 2e): "Confirm" / "Leave it out" - moves one deduced {@code Asset} from
 * {@code TO_CONFIRM} to {@code CONFIRMED}/{@code LEFT_OUT}, recording {@code scanConfirmedBy}/{@code At}
 * (contract sec 1). "Choose another key" is deliberately NOT a third write action here: board 2e's own link
 * goes to the landing's existing key chooser (the SAME key-editing machinery {@code WizardData#setTableKey}/
 * the landing key-study lot already built for every table, deduced or not) - once a deduced table's Asset row
 * exists with a proposed primary key ({@code Column.primaryKey}), the landing plan reads it exactly like any
 * source-declared key (task brief item 2 of the landing-key-study/composite-key lots), so re-choosing it is
 * the landing step's own job, not a new deduced-scan-specific editor.
 * <p>
 * A table with no {@code scan*} fields at all (older catalog - see {@link DeducedScanCatalogWriter}'s own
 * Javadoc) simply cannot be confirmed/left-out through this class: {@link #confirm}/{@link #leaveOut} both
 * refuse with a plain message rather than silently doing nothing, since a silent no-op here is exactly the
 * kind of bug CLAUDE.md warns about ("Continue to the landing" would stay disabled forever with no visible
 * reason).
 * <p>
 * Audit D-07 (2026-10-06): the asset is named by its key ({@code assetKey = source:qualifiedName}, a string), and an
 * asset the key no longer names is refused with a message, never skipped in silence. A rename moves the asset to the key
 * of its new name ({@link DeducedScanCatalog#rekey}) and answers that key, which the caller then confirms.
 */
public final class DeducedScanConfirm
{
	private static final Path ASSET = Path.parse("/root/Asset");
	private static final Path SCAN_STATE = Path.parse("./scanState");
	private static final Path SCAN_CONFIRMED_BY = Path.parse("./scanConfirmedBy");
	private static final Path SCAN_CONFIRMED_AT = Path.parse("./scanConfirmedAt");

	private DeducedScanConfirm()
	{
	}

	public static void confirm(final Session pSession, final Adaptation pCatalog, final String pAssetKey, final String pConfirmedBy) throws OperationException
	{
		DeducedScanConfirm.setState(pSession, pCatalog, pAssetKey, "CONFIRMED", pConfirmedBy, CatalogRightMessages.CatalogWrite.CONFIRM_DEDUCED_TABLE);
	}

	public static void leaveOut(final Session pSession, final Adaptation pCatalog, final String pAssetKey, final String pConfirmedBy) throws OperationException
	{
		DeducedScanConfirm.setState(pSession, pCatalog, pAssetKey, "LEFT_OUT", pConfirmedBy, CatalogRightMessages.CatalogWrite.LEAVE_OUT_DEDUCED_TABLE);
	}

	/**
	 * Board 2e's rename ("Table" text field, editable before confirming): {@code Asset.qualifiedName}, and since audit D-07
	 * the key with it - the asset and its columns move to {@code source:newName} ({@link DeducedScanCatalog#rekey};
	 * {@code physicalName}, what the source calls the object, is kept). Answers the asset's key after the rename (the same
	 * key when the name did not change). Refused when another table of the source already has that name.
	 */
	public static String rename(final Session pSession, final Adaptation pCatalog, final String pAssetKey, final String pNewName) throws OperationException
	{
		final Adaptation existing = DeducedScanConfirm.existing(pSession, pCatalog, pAssetKey);
		final String[] newKey = new String[] { pAssetKey };
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pCatalog.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				newKey[0] = CatalogReader.assetKeyOf(DeducedScanCatalog.rekey(pContext, pCatalog, existing, pNewName, false));
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("conflict.catalogWriteRefused", CatalogRightMessages.refusedOrRaw(CatalogRightMessages.CatalogWrite.RENAME_DEDUCED_TABLE,
				result.getExceptionFullMessage(pSession == null ? Locale.ENGLISH : pSession.getLocale())));
		}
		return newKey[0];
	}

	/**
	 * The asset row of a key, or a refusal a person can read - a Confirm / Leave it out / Rename on a key that names nothing
	 * never passes for done. Audit F-02 (catalog reads): read as the person (the catalog's rules apply), so a table their
	 * catalog right hides is refused as such, never as "no longer in the catalog".
	 */
	private static Adaptation existing(final Session pSession, final Adaptation pCatalog, final String pAssetKey) throws OperationException
	{
		final CatalogReader.Reader reader = CatalogReader.Reader.of(pSession);
		final Adaptation row = CatalogReader.assetRow(reader, pCatalog, pAssetKey);
		if (row == null && reader.restricted())
		{
			throw EngineRefusal.coded("conflict.catalogHidden", CatalogRightMessages.hidden("this deduced table (" + pAssetKey + ")"));
		}
		if (row == null)
		{
			throw EngineRefusal.coded("conflict.catalogTableGone", "This table is no longer in the catalog (" + pAssetKey + "): reload the page.", pAssetKey);
		}
		return row;
	}

	private static void setState(final Session pSession, final Adaptation pCatalog, final String pAssetKey, final String pState, final String pConfirmedBy,
		final CatalogRightMessages.CatalogWrite pWrite) throws OperationException
	{
		final AdaptationTable assetTable = pCatalog.getTable(DeducedScanConfirm.ASSET);
		if (assetTable.getTableOccurrenceRootNode().getNode(DeducedScanConfirm.SCAN_STATE) == null)
		{
			throw EngineRefusal.coded("missing.scanStateField", "This catalog does not have the deduced-scan fields yet (scanState): cannot confirm or leave out a table.");
		}
		final Adaptation row = DeducedScanConfirm.existing(pSession, pCatalog, pAssetKey);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pCatalog.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pState, DeducedScanConfirm.SCAN_STATE);
				value.setValue(pConfirmedBy == null ? "" : pConfirmedBy, DeducedScanConfirm.SCAN_CONFIRMED_BY);
				value.setValue(new Date(), DeducedScanConfirm.SCAN_CONFIRMED_AT);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("conflict.catalogWriteRefused", CatalogRightMessages.refusedOrRaw(pWrite, result.getExceptionFullMessage(pSession == null ? Locale.ENGLISH : pSession.getLocale())));
		}
	}
}
