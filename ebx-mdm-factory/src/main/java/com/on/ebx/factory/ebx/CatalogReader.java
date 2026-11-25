package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.core.CatalogKeys;
import com.on.ebx.factory.core.CatalogRightMessages;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.SchemaFilter;
import com.on.ebx.factory.staging.StagingRules;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaLocation;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Reads the scanned structures of a source in the data catalog (module ebx-data-catalog) as data: no
 * class of that module is used, only its dataset, found where the catalog says it lives
 * ({@code -Debx.catalog.dataspace} / {@code -Debx.catalog.dataset}, default {@code DataCatalog}) and
 * checked to be on the catalog's own packaged model. Without the catalog, {@link #dataset} is null and
 * the factory says so instead of failing.
 * <p>
 * Audit D-07 (2026-10-06): {@code Asset} and {@code Column} have no integer {@code /id} any more; they are keyed by their
 * natural keys {@code assetKey = source:qualifiedName} and {@code columnKey = assetKey.name} ({@link CatalogKeys}), and a
 * foreign key to them ({@code Column.asset}, {@code ProfileSnapshot.asset}/{@code column}) holds that string. Every lookup
 * by key goes through {@link #assetRow} ({@code computePrimaryKey}, never {@code PrimaryKey.parseString} of a key that
 * may hold {@code |} or {@code \}). {@code LandingTable.catalogAsset} holds the asset key; {@link #resolveAssetKey} finds
 * the asset of a landing table even when that value is empty or still the digits of a pre-D-07 integer id.
 */
public final class CatalogReader
{
	private static final String MODULE = "ebx-data-catalog";
	private static final String MODEL_SUFFIX = "/DataCatalog.xsd";

	private static final Path SOURCE = Path.parse("/root/Source");
	private static final Path ASSET = Path.parse("/root/Asset");
	private static final Path COLUMN = Path.parse("/root/Column");
	private static final Path PROFILE_SNAPSHOT = Path.parse("/root/ProfileSnapshot");

	private CatalogReader()
	{
	}

	// ------------------------------------------------------------------ who reads (audit F-02, catalog reads)

	/**
	 * Audit F-02 (catalog reads): who a read of the catalog is done for. Since wave 7 of ebx-data-catalog every table of its
	 * dataset carries record access rules that follow the catalog right of the SESSION (rows hidden without a right); a
	 * request with no session is not filtered. Every read of this class (and of {@link DeducedScanCatalog},
	 * {@link DataProductPublication}...) therefore takes a reader, decided at the call site and listed in
	 * {@code core.CatalogReadSites}:
	 * <ul>
	 * <li>{@link #of(Session)} - the result is shown to a person or returned by a REST route on their behalf: the request
	 * runs with their session ({@code Request.setSession}), a record looked up by key is dropped when hidden for them,
	 * and when a read finds nothing while the table's rows are hidden from the person ({@link CatalogReader#hiddenTable}),
	 * {@link #restricted()} turns true so the caller says "your catalog right does not let you see..." instead of "nothing
	 * found". A null session reads nothing
	 * (fail closed). The system session of a job reads everything (the catalog's rules let the system user through).</li>
	 * <li>{@link #system(String)} - a read done for the project, whose result is never listed to anybody (a background job
	 * on the system session, a find-or-create decision before a write, a uniqueness check): no session, with the reason.</li>
	 * </ul>
	 * Not thread-safe: one reader per request or per snapshot.
	 */
	public static final class Reader
	{
		private final Session session;
		private final boolean system;
		private final String reason;
		private boolean restricted;
		/** Per table path: whether this person's catalog right hides the table's rows (see {@link CatalogReader#hiddenTable}). */
		private final Map<String, Boolean> hiddenTables = new HashMap<String, Boolean>();

		private Reader(final Session pSession, final boolean pSystem, final String pReason)
		{
			this.session = pSession;
			this.system = pSystem;
			this.reason = pReason;
		}

		/** A read for the person of {@code pSession}: the catalog's rules filter it. */
		public static Reader of(final Session pSession)
		{
			return new Reader(pSession, false, null);
		}

		/** A read for the project, never shown as such: {@code pWhy} says why it may ignore the person's catalog right. */
		public static Reader system(final String pWhy)
		{
			if (pWhy == null || pWhy.trim().isEmpty())
			{
				throw new IllegalArgumentException("a system read of the catalog says why");
			}
			return new Reader(null, true, pWhy);
		}

		/** True once a read found nothing (or dropped a record) that the catalog holds but hides from this person. */
		public boolean restricted()
		{
			return this.restricted;
		}

		public boolean isSystem()
		{
			return this.system;
		}

		/** The session of a person's read, null for a system read. */
		public Session session()
		{
			return this.session;
		}

		/** Why a system read ignores the person's right; null for a person's read. */
		public String reason()
		{
			return this.reason;
		}

		void markRestricted()
		{
			this.restricted = true;
		}
	}

	/**
	 * The rows of one read, iterated like a {@link RequestResult}. On {@link #close()}, a person's read that returned nothing
	 * checks whether the table's rows are hidden from that person ({@link CatalogReader#hiddenTable}); if so the reader is
	 * marked restricted.
	 */
	public static final class Rows
	{
		private final RequestResult result;
		private final Reader reader;
		private final AdaptationTable table;
		private int count;

		Rows(final RequestResult pResult, final Reader pReader, final AdaptationTable pTable)
		{
			this.result = pResult;
			this.reader = pReader;
			this.table = pTable;
		}

		public Adaptation next()
		{
			if (this.result == null)
			{
				return null;
			}
			final Adaptation row = this.result.nextAdaptation();
			if (row != null)
			{
				this.count++;
			}
			return row;
		}

		public void close()
		{
			if (this.result != null)
			{
				this.result.close();
			}
			if (this.count == 0 && !this.reader.system && !this.reader.restricted && CatalogReader.hiddenTable(this.reader, this.table))
			{
				this.reader.markRestricted();
			}
		}
	}

	/**
	 * The rows of {@code pTable} matching {@code pPredicate} (null: every row), as {@code pReader} may read them: with the
	 * person's session (EBX applies the catalog's access rules), or without a session for a system read. A person's read
	 * EBX refuses outright ({@code QueryPermissionException}: the table or a field of the predicate is hidden) reads nothing.
	 */
	public static Rows rows(final Reader pReader, final AdaptationTable pTable, final String pPredicate)
	{
		if (pReader.system)
		{
			return new Rows(pTable.createRequestResult(pPredicate), pReader, pTable);
		}
		if (pReader.session == null)
		{
			return new Rows(null, pReader, pTable); // fail closed: a person's read without their session reads nothing
		}
		try
		{
			final Request request = pTable.createRequest();
			if (pPredicate != null)
			{
				request.setXPathFilter(pPredicate);
			}
			request.setSession(pReader.session);
			return new Rows(request.execute(), pReader, pTable);
		}
		catch (final RuntimeException ex)
		{
			return new Rows(null, pReader, pTable);
		}
	}

	/** {@code pRow} when {@code pReader} may see it, null otherwise (the reader is then marked restricted). A null row stays null. */
	public static Adaptation visible(final Reader pReader, final Adaptation pRow)
	{
		if (pRow == null || pReader.system)
		{
			return pRow;
		}
		if (CatalogReader.hiddenFor(pReader.session, pRow))
		{
			pReader.markRestricted();
			return null;
		}
		return pRow;
	}

	/**
	 * Audit F-02 (catalog reads): the gate of a background job whose system-session reads of the catalog end up in the
	 * project (an onboarding job adding an application and planning its landing, a PLAN job, a deduced scan): queued only
	 * when the person who asks can see the catalog - its sources, and the assets of {@code pSourceId} when one is given.
	 * Refused with a message naming the right otherwise (a person with no catalog right sees none of the catalog's rows).
	 * Nothing to check without a catalog. An administrator and the system session always pass.
	 */
	public static void requireVisible(final Session pSession, final Adaptation pCatalog, final String pSourceId, final String pWhat) throws OperationException
	{
		if (pCatalog == null)
		{
			return;
		}
		final Reader reader = Reader.of(pSession);
		final Rows sources = CatalogReader.rows(reader, pCatalog.getTable(CatalogReader.SOURCE), null);
		try
		{
			sources.next();
		}
		finally
		{
			sources.close();
		}
		if (!reader.restricted() && pSourceId != null && !pSourceId.trim().isEmpty())
		{
			final Rows assets = CatalogReader.rows(reader, pCatalog.getTable(CatalogReader.ASSET), "./source=" + CatalogReader.quote(pSourceId.trim()));
			try
			{
				assets.next();
			}
			finally
			{
				assets.close();
			}
		}
		if (reader.restricted())
		{
			throw EngineRefusal.coded("conflict.catalogHidden", CatalogRightMessages.hidden(pWhat) + " Nothing was queued.");
		}
	}

	/**
	 * Whether {@code pReader}'s person cannot see the rows of {@code pTable} that exist: the first row of the table (read
	 * without a session, one row only) is hidden for them. The catalog hides a whole table from a session without the right
	 * it asks (ebx-data-catalog {@code CatalogTableRights}), so one row tells, and an empty answer of a person's read is then
	 * "hidden", not "nothing found". False for an empty table. Answered once per table and reader. Never throws.
	 */
	static boolean hiddenTable(final Reader pReader, final AdaptationTable pTable)
	{
		final String key = String.valueOf(pTable.getTablePath());
		final Boolean known = pReader.hiddenTables.get(key);
		if (known != null)
		{
			return known.booleanValue();
		}
		boolean hidden = false;
		try
		{
			final RequestResult any = pTable.createRequestResult(null);
			try
			{
				final Adaptation first = any.nextAdaptation();
				hidden = first != null && CatalogReader.hiddenFor(pReader.session, first);
			}
			finally
			{
				any.close();
			}
		}
		catch (final RuntimeException ex)
		{
			hidden = false;
		}
		pReader.hiddenTables.put(key, Boolean.valueOf(hidden));
		return hidden;
	}

	/** Whether {@code pRow} is hidden for {@code pSession} (a null session sees nothing). Never throws: a permission that cannot be read hides. */
	private static boolean hiddenFor(final Session pSession, final Adaptation pRow)
	{
		if (pSession == null)
		{
			return true;
		}
		try
		{
			final AccessPermission permission = pSession.getPermissions().getAdaptationAccessPermission(pRow);
			return permission == null || permission.isHidden();
		}
		catch (final RuntimeException ex)
		{
			return true;
		}
	}

	/** A scanned table or view of a source. */
	public static final class Asset
	{
		/** {@code Asset.assetKey} (audit D-07): what {@code LandingTable.catalogAsset} holds. */
		public String key;
		public String qualifiedName;
		public String schemaName;
		public BigDecimal rowCount;
		/** Fix lot (2026-09-26): {@code Asset.scanOrigin} ({@code DEDUCED}/{@code OFFICIAL}/{@code ""} on an
		 * older row or a catalog without the field) - so {@code LandingPlanner} can tell a deduced-scan-proposed
		 * key apart from a genuine source-declared one, both of which land on {@code Column.primaryKey=true}. */
		public String scanOrigin;
		/** {@code Asset.kind} ({@code TABLE}/{@code VIEW}/{@code FILE}, {@code null} when the row declares none) - volume wave 2, lot V2-E: only a table or a view of a database can be read through a change column. */
		public String kind;
		public final List<Column> columns = new ArrayList<Column>();
	}

	/** A scanned column with the latest profile the catalog holds; rates are 0-1, null when never profiled. */
	public static final class Column
	{
		/** {@code Column.columnKey} (audit D-07); what {@code ProfileSnapshot.column} points at. */
		public String key;
		public String name;
		public Integer position;
		public String dataType;
		public String nativeType;
		public Boolean nullable;
		public boolean primaryKey;
		public BigDecimal nullRate;
		public BigDecimal distinctCount;
		public BigDecimal uniqueRate;
		public Integer maxLength;
		public final List<String> tags = new ArrayList<String>();
	}

	/** A source of the catalog (id, label, platform, connector) - the "Source du catalogue" the wizard's step 2 picks from. */
	public static final class Source
	{
		public final String id;
		public final String label;
		public final String platform;
		public final String connector;

		public Source(final String pId, final String pLabel, final String pPlatform, final String pConnector)
		{
			this.id = pId;
			this.label = pLabel;
			this.platform = pPlatform;
			this.connector = pConnector;
		}
	}

	/**
	 * Every active source of the catalog, for the wizard's "add an application" picker (step 2): id, label,
	 * platform and connector, in label order. {@code EBX} (the catalog's own self-scan) is excluded, same
	 * exclusion the sibling {@code ProductWizardUserService} applies to its own platform picker - never a
	 * target to land a project's sources from.
	 */
	public static List<Source> sources(final Reader pReader, final Adaptation pCatalog)
	{
		final List<Source> out = new ArrayList<Source>();
		final Rows rows = CatalogReader.rows(pReader, pCatalog.getTable(CatalogReader.SOURCE), null);
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				final String platform = CatalogReader.text(row, "./platform");
				if ("EBX".equals(platform))
				{
					continue;
				}
				if (Boolean.FALSE.equals(CatalogReader.value(row, "./active")))
				{
					continue;
				}
				out.add(new Source(row.getOccurrencePrimaryKey().format(), CatalogReader.text(row, "./label"), platform == null ? "" : platform,
					CatalogReader.text(row, "./connector")));
			}
		}
		finally
		{
			rows.close();
		}
		Collections.sort(out, new Comparator<Source>()
		{
			@Override
			public int compare(final Source pLeft, final Source pRight)
			{
				return String.valueOf(pLeft.label).compareToIgnoreCase(String.valueOf(pRight.label));
			}
		});
		return out;
	}

	/**
	 * source-onboarding lot (2026-09-23): one catalog {@code Source} row by id, plain and single-purpose -
	 * {@code ebx.SourceOnboarder#facts} needs exactly {@code active}/{@code lastScanStatus} to read the
	 * {@code ready} step of {@code SourceOnboarding.diagnose}, nothing else {@link Source} already carries.
	 */
	public static final class SourceStatus
	{
		public final String id;
		public final boolean exists;
		public final boolean active;
		/** {@code Source.lastScanStatus}: NEVER/OK/PARTIAL/ERROR, null when the row does not exist or the field itself does. */
		public final String lastScanStatus;
		/** Sources-landing lot SOURCES (2026-09-24): {@code Source.platform}, e.g. {@code "sample-file"} - the Review screen's own "kind" chip. Empty when the row does not exist or the field itself does. */
		public final String platform;
		/** Same lot: {@code Source.label} - falls back to {@link #id} elsewhere; kept raw (possibly empty) here, same convention as {@link #lastScanStatus}. */
		public final String label;

		private SourceStatus(final String pId, final boolean pExists, final boolean pActive, final String pLastScanStatus, final String pPlatform, final String pLabel)
		{
			this.id = pId;
			this.exists = pExists;
			this.active = pActive;
			this.lastScanStatus = pLastScanStatus;
			this.platform = pPlatform == null ? "" : pPlatform;
			this.label = pLabel == null ? "" : pLabel;
		}
	}

	/** A blank/null {@code pSourceId} answers "does not exist" rather than throwing - {@code PrimaryKey.parseString} of an empty string is not a meaningful lookup, and the caller (a plan that never resolved a source id, e.g. MANUAL/CREATE) should read this the same as a genuine miss. */
	public static SourceStatus sourceById(final Reader pReader, final Adaptation pCatalog, final String pSourceId)
	{
		if (pSourceId == null || pSourceId.trim().isEmpty())
		{
			return new SourceStatus(pSourceId, false, false, null, null, null);
		}
		final Adaptation row = CatalogReader.visible(pReader, pCatalog.getTable(CatalogReader.SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pSourceId)));
		if (row == null)
		{
			return new SourceStatus(pSourceId, false, false, null, null, null);
		}
		// active has no declared default in every packaged version of the catalog model: absent/null reads as
		// active (the same "not explicitly false" rule CatalogReader.sources already applies when filtering the
		// picker), never as inactive by default.
		final boolean active = !Boolean.FALSE.equals(CatalogReader.value(row, "./active"));
		return new SourceStatus(pSourceId, true, active, CatalogReader.text(row, "./lastScanStatus"), CatalogReader.text(row, "./platform"),
			CatalogReader.text(row, "./label"));
	}

	/** Whether at least one non-deleted table/view/file asset row exists for {@code pSourceId} - the same eligibility {@link #isScannedTableOrView} already applies to {@link #assets}/{@link #distinctSchemas}, without reading every column of every asset (source-onboarding lot, 2026-09-23: only a boolean is needed here). */
	public static boolean hasAssets(final Reader pReader, final Adaptation pCatalog, final String pSourceId)
	{
		final Rows rows = CatalogReader.rows(pReader, pCatalog.getTable(CatalogReader.ASSET), "./source=" + CatalogReader.quote(pSourceId));
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				if (CatalogReader.isScannedTableOrView(row))
				{
					return true;
				}
			}
			return false;
		}
		finally
		{
			rows.close();
		}
	}

	/** UX lot U2 (2026-10-13): whether the asset of {@code pAssetKey} holds at least one scanned column - the same eligibility {@link #columns} applies (a non-deleted row with a data type), stopping at the first. */
	private static boolean hasColumn(final Reader pReader, final Adaptation pCatalog, final String pAssetKey)
	{
		final Rows rows = CatalogReader.rows(pReader, pCatalog.getTable(CatalogReader.COLUMN), "./asset=" + CatalogReader.quote(pAssetKey));
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				final String dataType = CatalogReader.text(row, "./dataType");
				if (dataType != null && !dataType.trim().isEmpty() && !"DELETED".equals(CatalogReader.text(row, "./status")))
				{
					return true;
				}
			}
			return false;
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * UX lot U2 (2026-10-13): whether at least one scanned asset of {@code pSourceId} holds a column - what the onboarding diagnosis
	 * needs to say "ready" ({@code SourceOnboarding.Facts#assetsWithColumns}): a source whose only asset came from OpenLineage events has none.
	 */
	public static boolean hasAssetWithColumns(final Reader pReader, final Adaptation pCatalog, final String pSourceId)
	{
		for (final com.on.ebx.factory.wizard.UnplannedAsset asset : CatalogReader.assetBriefs(pReader, pCatalog, pSourceId))
		{
			if (asset.hasColumns)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * UX lot U2 (2026-10-13): every scanned table/view/file asset of {@code pSourceId} with whether it holds a scanned column, name order,
	 * without reading the columns themselves (one existence query per asset). What the card of an application with no planned table
	 * lists as the reason ("no column scanned yet") and what {@link #hasAssetWithColumns} reads.
	 */
	public static List<com.on.ebx.factory.wizard.UnplannedAsset> assetBriefs(final Reader pReader, final Adaptation pCatalog, final String pSourceId)
	{
		final List<String[]> found = new ArrayList<String[]>();
		final Rows rows = CatalogReader.rows(pReader, pCatalog.getTable(CatalogReader.ASSET), "./source=" + CatalogReader.quote(pSourceId));
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				if (CatalogReader.isScannedTableOrView(row))
				{
					// UX lot C4 (2026-10-14): the decision of a table found from the data (an empty state of such a table counts as confirmed, the catalog's rule)
					String scanState = "";
					if ("DEDUCED".equals(CatalogReader.text(row, "./scanOrigin")))
					{
						final String stored = CatalogReader.text(row, "./scanState");
						scanState = stored == null || stored.isEmpty() ? "CONFIRMED" : stored;
					}
					found.add(new String[] { CatalogReader.assetKeyOf(row), CatalogReader.text(row, "./qualifiedName"), scanState });
				}
			}
		}
		finally
		{
			rows.close();
		}
		final List<com.on.ebx.factory.wizard.UnplannedAsset> out = new ArrayList<com.on.ebx.factory.wizard.UnplannedAsset>();
		for (final String[] asset : found)
		{
			out.add(new com.on.ebx.factory.wizard.UnplannedAsset(asset[1] == null ? asset[0] : asset[1], CatalogReader.hasColumn(pReader, pCatalog, asset[0]), asset[2]));
		}
		Collections.sort(out, new Comparator<com.on.ebx.factory.wizard.UnplannedAsset>()
		{
			@Override
			public int compare(final com.on.ebx.factory.wizard.UnplannedAsset pLeft, final com.on.ebx.factory.wizard.UnplannedAsset pRight)
			{
				return pLeft.name.compareToIgnoreCase(pRight.name);
			}
		});
		return out;
	}

	public static Adaptation dataset(final Repository pRepository)
	{
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(CatalogReader.key("ebx.catalog.dataspace")));
		final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(CatalogReader.key("ebx.catalog.dataset")));
		if (dataset == null)
		{
			return null;
		}
		try
		{
			final SchemaLocation location = dataset.getSchemaLocation();
			final String path = String.valueOf(location.getPathInModule());
			return location.isPackagedInModule() && CatalogReader.MODULE.equals(location.getModuleName()) && path.endsWith(CatalogReader.MODEL_SUFFIX) ? dataset : null;
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static String key(final String pProperty)
	{
		final String value = System.getProperty(pProperty);
		return value == null || value.trim().isEmpty() ? "DataCatalog" : value.trim();
	}

	/**
	 * Live fix (2026-09-24, project Vague1): {@code Asset.rowCount}, read fresh, straight off the catalog
	 * row - never the value {@code LandingPlanner} copied once into {@code LandingTable.sourceRowCount} at
	 * planning time, which a later catalog rescan (e.g. after a Salesforce source's row counts were updated)
	 * never touches again. {@code pAssetKey} is {@code LandingTable.catalogAsset} (resolved by {@link #resolveAssetKey}), same as {@link
	 * #primaryKeyColumns}. Null when the asset row is gone (deleted/renamed since planning) or was never
	 * profiled with a row count - the caller falls back to the planned snapshot the same way {@code
	 * com.on.ebx.factory.wizard.TableRowCount} already falls back to a table's last load count.
	 */
	/** Deduced-scan lot (2026-09-24): one asset's {@code scanOrigin}/{@code scanState}/{@code scanConfidence}/{@code scanRationale} - see {@link #scanInfo}'s own Javadoc. */
	public static final class ScanInfo
	{
		/** {@code ""} (legacy row) or {@code "OFFICIAL"}/{@code "DEDUCED"} - contract: empty is treated as OFFICIAL. */
		public final String scanOrigin;
		/** {@code ""} (legacy row, treated as CONFIRMED) or {@code "CONFIRMED"}/{@code "TO_CONFIRM"}/{@code "LEFT_OUT"}. */
		public final String scanState;
		public final int scanConfidence;
		public final String scanRationale;
		/** UX lot U2 (2026-10-13): {@code Asset.scanConfirmedBy} (the user who confirmed the deduced table, {@code ""} when none) and {@code Asset.scanConfirmedAt} in epoch millis (0 when none). The catalog is shared by every project: the project that confirmed is not recorded. */
		public String confirmedBy = "";
		public long confirmedAt;

		public ScanInfo(final String pScanOrigin, final String pScanState, final int pScanConfidence, final String pScanRationale)
		{
			this.scanOrigin = pScanOrigin == null ? "" : pScanOrigin;
			this.scanState = pScanState == null ? "" : pScanState;
			this.scanConfidence = pScanConfidence;
			this.scanRationale = pScanRationale == null ? "" : pScanRationale;
		}
	}

	/**
	 * Deduced-scan lot (2026-09-24, read-recipe-contract.md sec 1): reads the four {@code scan*} fields the
	 * contract adds to {@code Asset} - {@link CatalogReader#value}/{@link CatalogReader#text}'s own
	 * schema-node guard means this answers an all-empty {@link ScanInfo} on a catalog that has not yet been
	 * upgraded with these fields (a different module/lot - see the deduced-scan task brief: "the new Asset
	 * fields do not exist yet on the live instance"), never throws. {@code pAssetKey} is {@code
	 * LandingTable.catalogAsset}, same convention as {@link #rowCount}/{@link #primaryKeyColumns}.
	 */
	public static ScanInfo scanInfo(final Reader pReader, final Adaptation pCatalog, final String pAssetKey)
	{
		final Adaptation row = CatalogReader.assetRow(pReader, pCatalog, pAssetKey);
		if (row == null)
		{
			return new ScanInfo("", "", 0, "");
		}
		final Object confidence = CatalogReader.value(row, "./scanConfidence");
		final ScanInfo info = new ScanInfo(CatalogReader.text(row, "./scanOrigin"), CatalogReader.text(row, "./scanState"),
			confidence instanceof Number ? ((Number) confidence).intValue() : 0, CatalogReader.text(row, "./scanRationale"));
		// UX lot U2: who confirmed it and when - read the same guarded way (an older catalog has neither field)
		final String by = CatalogReader.text(row, "./scanConfirmedBy");
		info.confirmedBy = by == null ? "" : by;
		final Object at = CatalogReader.value(row, "./scanConfirmedAt");
		info.confirmedAt = at instanceof java.util.Date ? ((java.util.Date) at).getTime() : 0L;
		return info;
	}

	/** Deduced-scan lot (2026-09-24): {@code Asset.readRecipe} (raw JSON text) for one asset - guarded the same way as {@link #scanInfo}. Empty string when the field does not exist yet, the asset row is gone, or the recipe was never written. */
	public static String readRecipeJson(final Reader pReader, final Adaptation pCatalog, final String pAssetKey)
	{
		final Adaptation row = CatalogReader.assetRow(pReader, pCatalog, pAssetKey);
		if (row == null)
		{
			return "";
		}
		final String text = CatalogReader.text(row, "./readRecipe");
		return text == null ? "" : text;
	}

	public static BigDecimal rowCount(final Reader pReader, final Adaptation pCatalog, final String pAssetKey)
	{
		final Adaptation row = CatalogReader.assetRow(pReader, pCatalog, pAssetKey);
		return row == null ? null : (BigDecimal) CatalogReader.value(row, "./rowCount");
	}

	/**
	 * Read-recipe lot (2026-09-24): {@code Asset.readRecipe}/{@code scanOrigin}/{@code scanState} - three
	 * fields added to the catalog's {@code Asset} table by the "read recipe + deduced scan" lot's shared
	 * contract, NOT YET present on every instance's {@code DataCatalog} model (they are added by a sibling
	 * lot in {@code ebx-data-catalog}, a separate repository this one never touches). Read the same
	 * defensive way every other optional field of this class already is ({@link #value}/{@link #text}: a
	 * missing schema node answers {@code null}, never {@code Adaptation.get}'s own thrown
	 * {@code PathAccessException} - CLAUDE.md section 9, "Adaptation.get(path) throws on a path the table
	 * does not define"), so calling this against an instance whose catalog predates the three fields answers
	 * an {@link AssetRecipe} with every string blank - exactly "no recipe (legacy row)", not a crash.
	 */
	public static final class AssetRecipe
	{
		/** False only when {@code pAssetKey} no longer names a row (deleted/renamed since planning, or not resolved). */
		public final boolean exists;
		/** {@code Asset.readRecipe} raw JSON text (see {@code com.on.ebx.factory.landing.ReadRecipe}), {@code ""} when absent or the field itself does not exist yet. */
		public final String readRecipe;
		/** {@code Asset.scanOrigin}: {@code OFFICIAL}/{@code DEDUCED}, {@code ""} when absent (contract: "empty = legacy row, treated as OFFICIAL"). */
		public final String scanOrigin;
		/** {@code Asset.scanState}: {@code CONFIRMED}/{@code TO_CONFIRM}/{@code LEFT_OUT}, {@code ""} when absent (contract: "empty = CONFIRMED"). */
		public final String scanState;

		private AssetRecipe(final boolean pExists, final String pReadRecipe, final String pScanOrigin, final String pScanState)
		{
			this.exists = pExists;
			this.readRecipe = pReadRecipe == null ? "" : pReadRecipe;
			this.scanOrigin = pScanOrigin == null ? "" : pScanOrigin;
			this.scanState = pScanState == null ? "" : pScanState;
		}
	}

	public static AssetRecipe assetRecipe(final Reader pReader, final Adaptation pCatalog, final String pAssetKey)
	{
		final Adaptation row = CatalogReader.assetRow(pReader, pCatalog, pAssetKey);
		if (row == null)
		{
			return new AssetRecipe(false, "", "", "");
		}
		return new AssetRecipe(true, CatalogReader.text(row, "./readRecipe"), CatalogReader.text(row, "./scanOrigin"), CatalogReader.text(row, "./scanState"));
	}

	/**
	 * Sources-landing lot SOURCES (2026-09-24): the PRIMARY KEY column names the source itself declared for
	 * one asset ({@code Column.primaryKey}, task brief item 2: "key declared by the source"), in column
	 * (position) order - empty when the asset has none, is unknown, or was never scanned with that
	 * information. {@code pAssetKey} is {@code LandingTable.catalogAsset} (set by {@link
	 * com.on.ebx.factory.ebx.LandingPlanner}, the same {@code Asset.assetKey} {@link #assets} itself returns) - a
	 * single-asset read rather than re-scanning every asset of the source, since the caller (the Review
	 * screen, one call per table already-known-to-exist) already knows exactly which one it wants.
	 */
	public static List<String> primaryKeyColumns(final Reader pReader, final Adaptation pCatalog, final String pAssetKey)
	{
		final List<String> none = new ArrayList<String>();
		if (pAssetKey == null || pAssetKey.isEmpty())
		{
			return none;
		}
		final Asset asset = new Asset();
		asset.key = pAssetKey;
		CatalogReader.columns(pReader, pCatalog, asset);
		final List<String> out = new ArrayList<String>();
		for (final Column column : asset.columns)
		{
			if (column.primaryKey)
			{
				out.add(column.name);
			}
		}
		return out;
	}

	/**
	 * The tables and views of a source that are still there, with their scanned columns in position order.
	 * A column without a data type was never seen by a scan: the catalog creates such rows from declared
	 * lineage ({@code VAT[].value}, a column of another table named by a mapping). They are not structure.
	 *
	 * @param pSchemas schema filter entries, matched by {@link SchemaFilter} (case-insensitive, trailing
	 *        dot-separated segments); empty = every schema
	 */
	public static List<Asset> assets(final Reader pReader, final Adaptation pCatalog, final String pSourceId, final List<String> pSchemas)
	{
		final List<Asset> assets = new ArrayList<Asset>();
		final Rows rows = CatalogReader.rows(pReader, pCatalog.getTable(CatalogReader.ASSET), "./source=" + CatalogReader.quote(pSourceId));
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				if (!CatalogReader.isScannedTableOrView(row))
				{
					continue;
				}
				final String schema = CatalogReader.text(row, "./schemaName");
				if (!SchemaFilter.keeps(pSchemas, schema))
				{
					continue;
				}
				final Asset asset = new Asset();
				asset.key = CatalogReader.assetKeyOf(row);
				asset.qualifiedName = CatalogReader.text(row, "./qualifiedName");
				asset.schemaName = schema;
				asset.rowCount = (BigDecimal) CatalogReader.value(row, "./rowCount");
				asset.scanOrigin = CatalogReader.text(row, "./scanOrigin");
				asset.kind = CatalogReader.text(row, "./kind");
				assets.add(asset);
			}
		}
		finally
		{
			rows.close();
		}
		for (final Asset asset : assets)
		{
			CatalogReader.columns(pReader, pCatalog, asset);
		}
		Collections.sort(assets, new Comparator<Asset>()
		{
			@Override
			public int compare(final Asset pLeft, final Asset pRight)
			{
				return String.valueOf(pLeft.qualifiedName).compareTo(String.valueOf(pRight.qualifiedName));
			}
		});
		return assets;
	}

	/**
	 * The distinct schema names scanned for a source (tables and views only, not deleted), in whichever
	 * form the catalog stores them - bare, or database-qualified. What a {@code schemaFilter} is compared
	 * against, and what explains an empty plan: a filter entry that matches none of these is a typo or a
	 * schema the source no longer has, not silence.
	 */
	public static List<String> distinctSchemas(final Reader pReader, final Adaptation pCatalog, final String pSourceId)
	{
		final Set<String> schemas = new LinkedHashSet<String>();
		final Rows rows = CatalogReader.rows(pReader, pCatalog.getTable(CatalogReader.ASSET), "./source=" + CatalogReader.quote(pSourceId));
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				if (!CatalogReader.isScannedTableOrView(row))
				{
					continue;
				}
				final String schema = CatalogReader.text(row, "./schemaName");
				if (schema != null && !schema.trim().isEmpty())
				{
					schemas.add(schema);
				}
			}
		}
		finally
		{
			rows.close();
		}
		final List<String> out = new ArrayList<String>(schemas);
		Collections.sort(out);
		return out;
	}

	/**
	 * Not deleted, and either no declared kind, a plain table/view, or a file - what both {@link #assets}
	 * and {@link #distinctSchemas} scan over. {@code FILE} is what a file-based connector's own
	 * {@code catalog} operation declares for every entry it scans (sample-file, S3, SFTP, GCS, Azure
	 * Blob - see {@code AbstractFileConnector.catalog}): without it here, a file source's assets are
	 * scanned and columned in the data catalog but never land, with no error - the plan just reports
	 * zero tables.
	 */
	private static boolean isScannedTableOrView(final Adaptation pRow)
	{
		final String kind = CatalogReader.text(pRow, "./kind");
		return !"DELETED".equals(CatalogReader.text(pRow, "./status"))
			&& (kind == null || "TABLE".equals(kind) || "VIEW".equals(kind) || "FILE".equals(kind));
	}

	private static void columns(final Reader pReader, final Adaptation pCatalog, final Asset pAsset)
	{
		final Map<String, Column> byKey = new HashMap<String, Column>();
		final Rows rows = CatalogReader.rows(pReader, pCatalog.getTable(CatalogReader.COLUMN), "./asset=" + CatalogReader.quote(pAsset.key));
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				final String dataType = CatalogReader.text(row, "./dataType");
				if (dataType == null || dataType.trim().isEmpty() || "DELETED".equals(CatalogReader.text(row, "./status")))
				{
					continue;
				}
				final Column column = new Column();
				column.key = CatalogReader.columnKeyOf(row);
				column.name = CatalogReader.text(row, "./name");
				column.position = (Integer) CatalogReader.value(row, "./position");
				column.dataType = dataType.trim();
				column.nativeType = CatalogReader.text(row, "./nativeType");
				column.nullable = (Boolean) CatalogReader.value(row, "./nullable");
				column.primaryKey = Boolean.TRUE.equals(CatalogReader.value(row, "./primaryKey"));
				column.nullRate = (BigDecimal) CatalogReader.value(row, "./lastNullRate");
				column.distinctCount = (BigDecimal) CatalogReader.value(row, "./lastDistinctCount");
				final Object tags = CatalogReader.value(row, "./tags");
				if (tags instanceof List)
				{
					for (final Object tag : (List<?>) tags)
					{
						column.tags.add(String.valueOf(tag));
					}
				}
				byKey.put(column.key, column);
				pAsset.columns.add(column);
			}
		}
		finally
		{
			rows.close();
		}
		CatalogReader.latestProfiles(pReader, pCatalog, pAsset, byKey);
		Collections.sort(pAsset.columns, new Comparator<Column>()
		{
			@Override
			public int compare(final Column pLeft, final Column pRight)
			{
				final int left = pLeft.position == null ? Integer.MAX_VALUE : pLeft.position.intValue();
				final int right = pRight.position == null ? Integer.MAX_VALUE : pRight.position.intValue();
				return left != right ? Integer.compare(left, right) : String.valueOf(pLeft.key).compareTo(String.valueOf(pRight.key));
			}
		});
	}

	/** Longest value and unique rate live on the snapshots only: the latest one of each column is kept. */
	private static void latestProfiles(final Reader pReader, final Adaptation pCatalog, final Asset pAsset, final Map<String, Column> pColumns)
	{
		final Map<String, Date> latest = new HashMap<String, Date>();
		final Rows rows = CatalogReader.rows(pReader, pCatalog.getTable(CatalogReader.PROFILE_SNAPSHOT), "./asset=" + CatalogReader.quote(pAsset.key));
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				final String columnKey = CatalogReader.text(row, "./column");
				final Date at = (Date) CatalogReader.value(row, "./profiledAt");
				if (columnKey == null || at == null)
				{
					continue;
				}
				// audit D-07: ProfileSnapshot.column holds the column KEY (a string such as "SRC:DB.S.T.NAME"); it used to
				// be parsed as an integer id here, which now failed for every row and silently dropped every profile.
				final Column column = pColumns.get(columnKey);
				final Date known = latest.get(columnKey);
				if (column == null || known != null && !at.after(known))
				{
					continue;
				}
				latest.put(columnKey, at);
				column.uniqueRate = (BigDecimal) CatalogReader.value(row, "./uniqueRate");
				column.maxLength = (Integer) CatalogReader.value(row, "./maxLength");
				final BigDecimal nullRate = (BigDecimal) CatalogReader.value(row, "./nullRate");
				if (nullRate != null)
				{
					column.nullRate = nullRate;
				}
			}
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * The {@code Asset} row of a key, or null (blank key, or no such row). By {@code computePrimaryKey} on the raw value:
	 * the key is a single string field stored as is, and {@code PrimaryKey.parseString} would read a {@code |} or a
	 * {@code \} in it as an escape (CLAUDE.md sec 9).
	 */
	public static Adaptation assetRow(final Reader pReader, final Adaptation pCatalog, final String pAssetKey)
	{
		if (pCatalog == null || pAssetKey == null || pAssetKey.trim().isEmpty())
		{
			return null;
		}
		final AdaptationTable table = pCatalog.getTable(CatalogReader.ASSET);
		return CatalogReader.visible(pReader, table.lookupAdaptationByPrimaryKey(table.computePrimaryKey(new Object[] { pAssetKey })));
	}

	/** {@code Asset.assetKey} of a row; its formatted primary key on a catalog without the field (cannot happen after D-07, kept for safety). */
	static String assetKeyOf(final Adaptation pRow)
	{
		final String key = CatalogReader.text(pRow, "./assetKey");
		return key != null && !key.isEmpty() ? key : pRow.getOccurrencePrimaryKey().format();
	}

	/** {@code Column.columnKey} of a row, same fallback as {@link #assetKeyOf}. */
	static String columnKeyOf(final Adaptation pRow)
	{
		final String key = CatalogReader.text(pRow, "./columnKey");
		return key != null && !key.isEmpty() ? key : pRow.getOccurrencePrimaryKey().format();
	}

	/**
	 * {@code LandingTable.catalogAsset} as stored, {@code ""} when empty. A string since audit D-07; a {@link Number} only
	 * when read on a model not yet upgraded (then its digits, which {@link CatalogKeys#plausibleAssetKey} refuses).
	 */
	public static String storedAssetKey(final Adaptation pLandingTable)
	{
		if (pLandingTable.getSchemaNode().getNode(FactoryPaths.LandingTable.CATALOG_ASSET) == null)
		{
			return "";
		}
		final Object value = pLandingTable.get(FactoryPaths.LandingTable.CATALOG_ASSET);
		return value == null ? "" : String.valueOf(value);
	}

	/**
	 * The key of the {@code Asset} a landing table was planned from, read only (nothing is written): the stored
	 * {@code catalogAsset} when it names a row, otherwise the key the project source's catalog source and the table's
	 * {@code sourceObject} give ({@link CatalogKeys#lookupOrder}) - primary-key lookups only, no predicate scan (this runs
	 * for every landing table of every wizard snapshot). Null when none answers. Since D-07 every asset is keyed
	 * {@code source:qualifiedName} (a scan writes it so, and a deduced asset is re-keyed when renamed:
	 * {@link DeducedScanCatalog#rekey}), so the computed key finds any asset the stored value does not. This is what makes a landing table planned
	 * before audit D-07 (its {@code catalogAsset} converted by EBX from {@code xs:int} to the digits of an id that no
	 * longer exists) find its asset again after the catalog rescan, before the next landing plan writes the key back
	 * ({@code LandingPlanner}).
	 */
	public static String resolveAssetKey(final Reader pReader, final Adaptation pCatalog, final Adaptation pFactory, final Adaptation pLandingTable)
	{
		if (pCatalog == null || pLandingTable == null)
		{
			return null;
		}
		final String stored = CatalogReader.storedAssetKey(pLandingTable);
		if (CatalogKeys.plausibleAssetKey(stored) && CatalogReader.assetRow(pReader, pCatalog, stored) != null)
		{
			return stored; // the common case: one lookup, no project-source read
		}
		final String sourceObject = pLandingTable.getString(FactoryPaths.LandingTable.SOURCE_OBJECT);
		final String catalogSource = CatalogReader.catalogSourceOf(pFactory, pLandingTable);
		for (final String candidate : CatalogKeys.lookupOrder(stored, catalogSource, sourceObject))
		{
			if (!candidate.equals(stored) && CatalogReader.assetRow(pReader, pCatalog, candidate) != null)
			{
				return candidate;
			}
		}
		return null;
	}

	/** {@code ProjectSource.catalogSource} of a landing table's project source, null when unknown. */
	private static String catalogSourceOf(final Adaptation pFactory, final Adaptation pLandingTable)
	{
		final String projectSourceId = pLandingTable.getString(FactoryPaths.LandingTable.PROJECT_SOURCE);
		if (pFactory == null || projectSourceId == null || projectSourceId.trim().isEmpty())
		{
			return null;
		}
		final Adaptation projectSource = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectSourceId));
		final String catalogSource = projectSource == null ? null : projectSource.getString(FactoryPaths.ProjectSource.CATALOG_SOURCE);
		return catalogSource == null || catalogSource.trim().isEmpty() ? null : catalogSource.trim();
	}

	private static final Path TAG_SUGGESTION = Path.parse("/root/TagSuggestion");
	/** Column keys put in one predicate when reading their suggestions. */
	private static final int SUGGESTION_KEYS_PER_QUERY = 40;

	/** Lot S10b (2026-10-19): what the catalogue holds as tags for one column - the applied tags ({@code Column.tags}) and its {@code TagSuggestion} rows of any status. */
	public static final class ColumnTagFacts
	{
		public final List<String> applied = new ArrayList<String>();
		public final List<StagingRules.TagSuggestionInfo> suggestions = new ArrayList<StagingRules.TagSuggestionInfo>();
	}

	/**
	 * Lot S10b: the tags of every column of an asset, by column NAME (the landing column's {@code sourceColumn}): {@code Column.tags} as applied, and the {@code TagSuggestion} rows
	 * (tag, origin, confidence, status - the free-text evidence is not read: it may quote a column name or whatever a model wrote). Read as {@code pReader} may read them. Empty when
	 * the asset has no column or the catalogue lacks the table (an older one); never throws.
	 */
	public static Map<String, ColumnTagFacts> columnTagFacts(final Reader pReader, final Adaptation pCatalog, final String pAssetKey)
	{
		final Map<String, ColumnTagFacts> byName = new java.util.LinkedHashMap<String, ColumnTagFacts>();
		if (pCatalog == null || pAssetKey == null || pAssetKey.trim().isEmpty())
		{
			return byName;
		}
		try
		{
			final Map<String, ColumnTagFacts> byKey = new java.util.LinkedHashMap<String, ColumnTagFacts>();
			final Rows columns = CatalogReader.rows(pReader, pCatalog.getTable(CatalogReader.COLUMN), "./asset=" + CatalogReader.quote(pAssetKey));
			try
			{
				for (Adaptation row = columns.next(); row != null; row = columns.next())
				{
					if ("DELETED".equals(CatalogReader.text(row, "./status")))
					{
						continue;
					}
					final ColumnTagFacts facts = new ColumnTagFacts();
					final Object tags = CatalogReader.value(row, "./tags");
					if (tags instanceof List)
					{
						for (final Object tag : (List<?>) tags)
						{
							facts.applied.add(String.valueOf(tag));
						}
					}
					byKey.put(CatalogReader.columnKeyOf(row), facts);
					final String name = CatalogReader.text(row, "./name");
					if (name != null)
					{
						byName.put(name, facts);
					}
				}
			}
			finally
			{
				columns.close();
			}
			CatalogReader.suggestionsInto(pReader, pCatalog, byKey);
		}
		catch (final RuntimeException ex)
		{
			// an older catalogue without the table, a field it lacks: no suggestion is not an error here
		}
		return byName;
	}

	private static void suggestionsInto(final Reader pReader, final Adaptation pCatalog, final Map<String, ColumnTagFacts> pByKey)
	{
		final List<String> keys = new ArrayList<String>(pByKey.keySet());
		final AdaptationTable table = pCatalog.getTable(CatalogReader.TAG_SUGGESTION);
		for (int from = 0; from < keys.size(); from += CatalogReader.SUGGESTION_KEYS_PER_QUERY)
		{
			final StringBuilder predicate = new StringBuilder();
			for (final String key : keys.subList(from, Math.min(keys.size(), from + CatalogReader.SUGGESTION_KEYS_PER_QUERY)))
			{
				predicate.append(predicate.length() == 0 ? "" : " or ").append("./column=").append(CatalogReader.quote(key));
			}
			final Rows rows = CatalogReader.rows(pReader, table, predicate.toString());
			try
			{
				for (Adaptation row = rows.next(); row != null; row = rows.next())
				{
					final Object columnKey = CatalogReader.value(row, "./column");
					final ColumnTagFacts facts = columnKey == null ? null : pByKey.get(String.valueOf(columnKey));
					final String tag = CatalogReader.text(row, "./tag");
					if (facts == null || tag == null)
					{
						continue;
					}
					final Object confidence = CatalogReader.value(row, "./confidence");
					facts.suggestions.add(new StagingRules.TagSuggestionInfo(tag, CatalogReader.text(row, "./origin"), confidence instanceof Number ? ((Number) confidence).intValue() : 0,
						CatalogReader.text(row, "./status")));
				}
			}
			finally
			{
				rows.close();
			}
		}
	}

	/** Adaptation.get throws on a path the table does not define: an older catalog may lack a field. */
	private static Object value(final Adaptation pRow, final String pPath)
	{
		final Path path = Path.parse(pPath);
		return pRow.getSchemaNode().getNode(path) == null ? null : pRow.get(path);
	}

	private static String text(final Adaptation pRow, final String pPath)
	{
		final Path path = Path.parse(pPath);
		return pRow.getSchemaNode().getNode(path) == null ? null : pRow.getString(path);
	}

	/**
	 * An XPath string literal. A null value is NOT the text {@code 'null'} (audit F-52: {@code String.valueOf(null)} used to make a predicate on an
	 * absent id match the rows whose field really holds "null"): it quotes as the empty literal, which matches no row - EBX stores an empty string as
	 * null, so {@code ./f=''} finds nothing (CLAUDE.md sec 9). A caller for whom a null value means "the field is empty" uses {@link #equalsPredicate}.
	 */
	static String quote(final String pValue)
	{
		return "'" + (pValue == null ? "" : pValue).replace("'", "''") + "'";
	}

	/** {@code pPath = 'value'}, or {@code osd:is-null(pPath)} when the value is null or empty - for an optional field where "no value" is a real state to find. */
	static String equalsPredicate(final String pPath, final String pValue)
	{
		return pValue == null || pValue.isEmpty() ? "osd:is-null(" + pPath + ")" : pPath + "=" + CatalogReader.quote(pValue);
	}
}
