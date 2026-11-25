package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationFilter;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.onwbp.adaptation.RequestSortCriteria;
import com.orchestranetworks.permissions.QueryPermissionException;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.SessionPermissions;

/**
 * What ONE user may see of the data a screen reads (2026-10-14, the product owner: "EBX permissions are respected in every case"; grid in
 * {@code explorer-designer/PERMISSIONS.md}, second pass in {@code docs/PERMISSIONS.md}). Public Javadoc APIs only: {@link SessionPermissions} and
 * {@link Request#setSession}.
 * <p>
 * Every user-facing read goes through one of these, built from the session EBX gives the screen (never from the page):
 * <ul>
 * <li>{@link #rows} / {@link #all}: a {@link Request} carrying the session ("the returned records will be filtered using the access rules on records");
 * a hidden table, or a filter or sort that reads a hidden node ({@link QueryPermissionException}), gives no row - answered like an unknown id;</li>
 * <li>{@link #lookup}: a record by key, null when the table or the record is hidden (same answer as an unknown key);</li>
 * <li>{@link #text} / {@link #value} / {@link #list}: a field of a row, empty when the field is hidden for the dataset or, for record-dependent rules
 * ({@code setSession} does not apply them, its Javadoc says so), for that record - checked per record up to {@link #MAX_RECORD_FIELD_CHECKS} calls per
 * view, beyond which only the dataset-level rules apply (logged once).</li>
 * </ul>
 * {@link #ENGINE} (a null session, or the system user's) reads everything exactly as before: background jobs, workflow steps and installers keep their
 * full view. A reader shared by both takes a {@code UserView} and the engine passes {@link #ENGINE}.
 */
public final class UserView
{
	/** Record-dependent field checks one view makes at most (a screen reads a project's rows; a huge table falls back to the dataset rules). */
	public static final int MAX_RECORD_FIELD_CHECKS = 50000;

	/** The engine's view: no session, every row and every field. */
	public static final UserView ENGINE = new UserView(null);

	private static volatile boolean boundLogged;

	private final Session session;
	private final SessionPermissions permissions;
	/** dataset-level answers, keyed home|dataset|node path */
	private final Map<String, Boolean> hiddenForDataset = new HashMap<String, Boolean>();
	private int recordChecks;

	private UserView(final Session pSession)
	{
		this.session = pSession;
		this.permissions = pSession == null ? null : pSession.getPermissions();
	}

	/** The view of {@code pSession}; {@link #ENGINE} for null or the system user (a job, a workflow step: everything). */
	public static UserView of(final Session pSession)
	{
		if (pSession == null)
		{
			return UserView.ENGINE;
		}
		try
		{
			if (pSession.isSystemUser())
			{
				return UserView.ENGINE;
			}
		}
		catch (final RuntimeException ex)
		{
			// a session that cannot say: treat it as a person (the safe side)
		}
		return new UserView(pSession);
	}

	// -------------------------------------------------------------------------------------------- scoped readers

	private static final ThreadLocal<UserView> SCOPE = new ThreadLocal<UserView>();

	/**
	 * The view a shared reader applies: the one {@link #scoped} installed around a user-facing READ on this thread, {@link #ENGINE} otherwise. The big
	 * readers shared by screens and engines ({@code WizardData}'s snapshot, {@code AccelData}, {@code KeyedRows}, {@code PipelineStepIndex}) read through
	 * it, so a background job, a workflow step or an event handler's own writes - never inside a scope - keep reading everything, and only the snapshot a
	 * screen asks for is filtered. A scope is only ever opened around a method that reads (no write, no job, no MCP call inside).
	 */
	public static UserView current()
	{
		final UserView view = UserView.SCOPE.get();
		return view == null ? UserView.ENGINE : view;
	}

	/** A read that may refuse with an {@link com.orchestranetworks.service.OperationException} (an unknown project, a missing dataset). */
	public interface Read<T>
	{
		T get() throws com.orchestranetworks.service.OperationException;
	}

	/** {@link #scoped} for a read that throws {@link com.orchestranetworks.service.OperationException}. */
	public static <T> T scopedRead(final UserView pView, final Read<T> pRead) throws com.orchestranetworks.service.OperationException
	{
		final UserView before = UserView.SCOPE.get();
		UserView.SCOPE.set(pView == null || pView.engine() ? null : pView);
		try
		{
			return pRead.get();
		}
		finally
		{
			if (before == null)
			{
				UserView.SCOPE.remove();
			}
			else
			{
				UserView.SCOPE.set(before);
			}
		}
	}

	/** Runs {@code pRead} with {@code pView} as {@link #current()} on this thread, then restores what was there (scopes nest). */
	public static <T> T scoped(final UserView pView, final java.util.function.Supplier<T> pRead)
	{
		final UserView before = UserView.SCOPE.get();
		UserView.SCOPE.set(pView == null || pView.engine() ? null : pView);
		try
		{
			return pRead.get();
		}
		finally
		{
			if (before == null)
			{
				UserView.SCOPE.remove();
			}
			else
			{
				UserView.SCOPE.set(before);
			}
		}
	}

	// ------------------------------------------------- shared readers: the same calls, through the current scope

	/** {@link #rows} of {@link #current()}: every row for the engine, the user's rows inside a screen's read scope. */
	public static RequestResult select(final AdaptationTable pTable, final String pPredicate)
	{
		return UserView.current().rows(pTable, pPredicate);
	}

	/** {@link #select(AdaptationTable, String)}, sorted. */
	public static RequestResult select(final AdaptationTable pTable, final String pPredicate, final RequestSortCriteria pSort)
	{
		return UserView.current().rows(pTable, pPredicate, pSort);
	}

	/** A record by key through {@link #current()}: exactly {@code lookupAdaptationByPrimaryKey} for the engine, null for a hidden record in a scope. */
	public static Adaptation byKey(final AdaptationTable pTable, final PrimaryKey pKey)
	{
		final UserView view = UserView.current();
		return view.engine() ? pTable.lookupAdaptationByPrimaryKey(pKey) : view.lookup(pTable, pKey);
	}

	/** A field through {@link #current()}: exactly {@code get} for the engine, null when hidden in a scope. */
	public static Object get(final Adaptation pRow, final Path pField)
	{
		final UserView view = UserView.current();
		return view.engine() ? pRow.get(pField) : view.value(pRow, pField);
	}

	/** A string field through {@link #current()}: exactly {@code getString} for the engine, null when hidden in a scope. */
	public static String string(final Adaptation pRow, final Path pField)
	{
		final UserView view = UserView.current();
		if (view.engine())
		{
			return pRow.getString(pField);
		}
		final Object value = view.value(pRow, pField);
		return value == null ? null : value instanceof String ? (String) value : String.valueOf(value);
	}

	/** A multi-valued field through {@link #current()}: exactly {@code getList} for the engine, null when hidden in a scope. */
	@SuppressWarnings("unchecked")
	public static <T> List<T> values(final Adaptation pRow, final Path pField)
	{
		final UserView view = UserView.current();
		if (view.engine())
		{
			return pRow.getList(pField);
		}
		final Object value = view.value(pRow, pField);
		return value instanceof List ? (List<T>) value : null;
	}

	/** True for the engine's view (no filter at all). */
	public boolean engine()
	{
		return this.session == null;
	}

	/** The person's session, null for the engine. */
	public Session session()
	{
		return this.session;
	}

	// ------------------------------------------------------------------------------------------------ containers

	/** The dataspace and the dataset are not hidden for the user. */
	public boolean datasetVisible(final Adaptation pDataset)
	{
		if (this.engine())
		{
			return true;
		}
		if (pDataset == null)
		{
			return false;
		}
		return !this.permissions.getHomeAccessPermission(pDataset.getHome()).isHidden() && !this.permissions.getAdaptationAccessPermission(pDataset).isHidden();
	}

	/** The dataset and the table node are not hidden for the user. */
	public boolean tableVisible(final AdaptationTable pTable)
	{
		if (this.engine())
		{
			return true;
		}
		if (pTable == null)
		{
			return false;
		}
		final Adaptation dataset = pTable.getContainerAdaptation();
		return this.datasetVisible(dataset) && !this.hiddenForDataset(pTable.getTableNode(), dataset);
	}

	/** The field is defined on the table and not hidden for the whole dataset (record-dependent rules aside: see {@link #shows}). */
	public boolean fieldVisible(final AdaptationTable pTable, final Path pField)
	{
		if (this.engine())
		{
			return true;
		}
		final SchemaNode node = pTable.getTableNode().getNode(pField);
		return node != null && !this.hiddenForDataset(node, pTable.getContainerAdaptation());
	}

	// --------------------------------------------------------------------------------------------------- records

	/** The record exists, its table is visible and the record is not hidden for the user. */
	public boolean recordVisible(final Adaptation pRecord)
	{
		if (pRecord == null)
		{
			return false;
		}
		if (this.engine())
		{
			return true;
		}
		return this.tableVisible(pRecord.getContainerTable()) && !this.permissions.getAdaptationAccessPermission(pRecord).isHidden();
	}

	/** The user may modify the record (read-write access on it), the condition of any write a person triggers on it. */
	public boolean recordWritable(final Adaptation pRecord)
	{
		if (pRecord == null)
		{
			return false;
		}
		if (this.engine())
		{
			return true;
		}
		return this.recordVisible(pRecord) && this.permissions.getAdaptationAccessPermission(pRecord).isReadWrite();
	}

	/** The user may delete the record (EBX's own delete right on it). */
	public boolean recordDeletable(final Adaptation pRecord)
	{
		if (pRecord == null)
		{
			return false;
		}
		if (this.engine())
		{
			return true;
		}
		return this.recordVisible(pRecord) && this.permissions.getOccurrenceActionPermissionToDelete(pRecord, pRecord.getContainerTable()).isEnabled();
	}

	/** The user may create a record in the table (EBX's own "+" right). */
	public boolean canCreate(final AdaptationTable pTable)
	{
		if (this.engine())
		{
			return true;
		}
		return this.tableVisible(pTable) && this.permissions.getTableActionPermissionToCreateRootOccurrence(pTable).isEnabled();
	}

	/** A record by key, null when unknown, malformed or hidden from the user - the three are answered the same way. */
	public Adaptation lookup(final AdaptationTable pTable, final String pKey)
	{
		if (pTable == null || pKey == null || pKey.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return this.lookup(pTable, PrimaryKey.parseString(pKey));
		}
		catch (final RuntimeException malformed)
		{
			return null;
		}
	}

	/** Same as {@link #lookup(AdaptationTable, String)} with a parsed key. */
	public Adaptation lookup(final AdaptationTable pTable, final PrimaryKey pKey)
	{
		if (pTable == null || pKey == null)
		{
			return null;
		}
		final Adaptation record;
		try
		{
			record = pTable.lookupAdaptationByPrimaryKey(pKey);
		}
		catch (final RuntimeException malformed)
		{
			return null;
		}
		return this.recordVisible(record) ? record : null;
	}

	/** The rows of the table matching {@code pPredicate} (null = all) that the user may see; close it. */
	public RequestResult rows(final AdaptationTable pTable, final String pPredicate)
	{
		return this.rows(pTable, pPredicate, null);
	}

	/** Same as {@link #rows(AdaptationTable, String)}, sorted. */
	public RequestResult rows(final AdaptationTable pTable, final String pPredicate, final RequestSortCriteria pSort)
	{
		if (this.engine())
		{
			return pSort == null ? pTable.createRequestResult(pPredicate) : pTable.createRequestResult(pPredicate, pSort);
		}
		if (!this.tableVisible(pTable))
		{
			return UserView.none(pTable, pPredicate);
		}
		final Request request = pTable.createRequest();
		if (pPredicate != null)
		{
			request.setXPathFilter(pPredicate);
		}
		if (pSort != null)
		{
			request.setSortCriteria(pSort);
		}
		request.setSession(this.session);
		try
		{
			return request.execute();
		}
		catch (final QueryPermissionException forbidden)
		{
			// the filter or the sort reads a node hidden from the user: what the user cannot see cannot select rows either
			return UserView.none(pTable, pPredicate);
		}
	}

	/** {@link #rows} drained into a list. */
	public List<Adaptation> all(final AdaptationTable pTable, final String pPredicate)
	{
		return UserView.drain(this.rows(pTable, pPredicate, null));
	}

	/** {@link #rows} drained into a list, sorted. */
	public List<Adaptation> all(final AdaptationTable pTable, final String pPredicate, final RequestSortCriteria pSort)
	{
		return UserView.drain(this.rows(pTable, pPredicate, pSort));
	}

	/** An empty result of the table (no session: it must not fail again); only the rows the predicate already selects are scanned. */
	private static RequestResult none(final AdaptationTable pTable, final String pPredicate)
	{
		final Request request = pTable.createRequest();
		if (pPredicate != null)
		{
			try
			{
				request.setXPathFilter(pPredicate);
			}
			catch (final RuntimeException ex)
			{
				// a malformed predicate: the reject-all filter alone is enough
			}
		}
		request.setSpecificFilter(UserView.REJECT_ALL);
		return request.execute();
	}

	private static final AdaptationFilter REJECT_ALL = new AdaptationFilter()
	{
		@Override
		public boolean accept(final Adaptation pRecord)
		{
			return false;
		}
	};

	private static List<Adaptation> drain(final RequestResult pRows)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		try
		{
			for (Adaptation row = pRows.nextAdaptation(); row != null; row = pRows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			pRows.close();
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------- fields

	/** The field of this row is shown to the user: defined, not hidden for the dataset, not hidden on this record. */
	public boolean shows(final Adaptation pRow, final Path pField)
	{
		if (pRow == null)
		{
			return false;
		}
		if (this.engine())
		{
			return true;
		}
		final AdaptationTable table = pRow.getContainerTable();
		if (table == null)
		{
			return true; // not a record: the dataset's own nodes are not read through this view
		}
		final SchemaNode node = table.getTableNode().getNode(pField);
		if (node == null || this.hiddenForDataset(node, table.getContainerAdaptation()))
		{
			return false;
		}
		if (this.recordChecks >= UserView.MAX_RECORD_FIELD_CHECKS)
		{
			if (!UserView.boundLogged)
			{
				UserView.boundLogged = true;
				LoggingCategory.getKernel().info("[mdm-factory] permissions: more than " + UserView.MAX_RECORD_FIELD_CHECKS
					+ " record-dependent field checks in one screen; the rest of it follows the dataset-level field rules only");
			}
			return true;
		}
		this.recordChecks++;
		return !this.permissions.getNodeAccessPermission(node, pRow).isHidden();
	}

	/** The value of the field, null when it is empty or hidden from the user. */
	public Object value(final Adaptation pRow, final Path pField)
	{
		return this.shows(pRow, pField) ? pRow.get(pField) : null;
	}

	/** The value of the field as text, "" when it is empty or hidden from the user. */
	public String text(final Adaptation pRow, final Path pField)
	{
		final Object value = this.value(pRow, pField);
		return value == null ? "" : String.valueOf(value);
	}

	/** The values of a multi-valued field, an empty list when it is empty or hidden from the user. */
	public List<?> list(final Adaptation pRow, final Path pField)
	{
		final Object value = this.value(pRow, pField);
		return value instanceof List ? (List<?>) value : Collections.emptyList();
	}

	private boolean hiddenForDataset(final SchemaNode pNode, final Adaptation pDataset)
	{
		final String key = pDataset.getHome().getKey().format() + "|" + pDataset.getAdaptationName().getStringName() + "|" + pNode.getPathInSchema().format();
		Boolean hidden = this.hiddenForDataset.get(key);
		if (hidden == null)
		{
			hidden = Boolean.valueOf(this.permissions.getNodeAccessPermission(pNode, pDataset).isHidden());
			this.hiddenForDataset.put(key, hidden);
		}
		return hidden.booleanValue();
	}
}
