package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * UX lot U2 (2026-10-13): the rows of the single "Add an application" combobox of step 2, which merges the Connectors connections and the
 * data catalog's sources. Measured live the same day: "Pick a source already in the catalog" and "Connect a live system" listed the same
 * systems (Salesforce in both), the unusable ones (a source declared by OpenLineage events, with no connection) looked like the usable
 * one, and nothing said which were already applications of the project. Each row says what picking it will do: a connection is added
 * (registered in the catalog and scanned when it is not yet, then its tables are found), one already in the project or one that cannot
 * be read is shown greyed with the reason. Pure, tested in {@code AddApplicationOptionsTest}.
 */
public final class AddApplicationOptions
{
	/** What a row means. The first two can be picked. */
	public enum State
	{
		/** In the catalog, scanned: picking it finds its tables ({@link Row#tables} says how many). */
		SCANNED,
		/** A connection the catalog does not know yet: picking it registers it, scans it, then finds its tables. */
		CONNECTION_ONLY,
		/** Already an application of this project. */
		IN_PROJECT,
		/** The connection is switched off in Connectors. */
		INACTIVE,
		/**
		 * UX lot C1 (2026-10-13): a connection with no catalog operation whose catalog source already holds tables (found from the data): picking it
		 * starts the deduced scan, which keeps the person's earlier decisions on those tables.
		 */
		FOUND_FROM_DATA,
		/** UX lot C1: a connection with no catalog operation and no tables yet, but an operation that lists records: picking it starts the deduced scan. */
		DEDUCED_READ,
		/** UX lot C1: no catalog operation and nothing to read it with; {@link Row#reason} says why. */
		CANNOT_READ,
		/** A catalog source with no connection (declared by lineage events): nothing to scan. */
		LINEAGE_ONLY,
		/** A catalog source whose connection no longer exists in Connectors. */
		CONNECTION_MISSING
	}

	/** The connection offers no operation that lists records (it looks one record up, or only sends). */
	public static final String REASON_NO_READ_OPERATION = "NO_READ_OPERATION";
	/** {@code connectors_describe} failed, so its operations are unknown. */
	public static final String REASON_DESCRIBE_FAILED = "DESCRIBE_FAILED";
	/** UX lot C4 (2026-10-14): the connection is a language model (an enrichment of Connectors, never a source of records). */
	public static final String REASON_NOT_AN_APPLICATION = "NOT_AN_APPLICATION";
	/**
	 * UX lot C4 (2026-10-14): the connection types that are never a source of records whatever their operations say. Measured on {@code connectors_list}
	 * the same day: an {@code llm} type lists {@code complete (read)}, so lot C1's readability rule offered "OpenAI - AI enrichment" as "will be read from its
	 * data". Connectors' type metadata (type, label, operations) carries no "enrichment" flag, so the types are named here; the lookup services of
	 * Connectors (GLEIF, Sirene, VIES...) look one record up and are refused at the click by the deduced scan itself.
	 */
	private static final Set<String> NOT_A_SOURCE_TYPES = new HashSet<String>(java.util.Arrays.asList("llm"));

	private AddApplicationOptions()
	{
	}

	/**
	 * UX lot C1 (2026-10-13): why a connection WITHOUT a catalog operation could not be read for its tables, "" when it can. The rule is the first
	 * gate of the deduced scan ({@code DeducedScanOperations}: a read-only operation that lists records must exist, else it is an enrichment, not a
	 * source). From {@code connectors_list}, which gives the TYPE's operations with a kind: a type that lists operations is read when one has kind
	 * {@code read} (a {@code search} needs a value to look for, so it is not a listing). A type that lists none (a REST connection, whose operations
	 * are its own) is read from {@code connectors_describe}: {@code pDescribeError} non-null means unknown, else {@code pDescribedReadOnly} says.
	 *
	 * @param pTypeListsOperations whether {@code connectors_list} gave the type any operation
	 * @param pTypeHasReadOperation whether one of them has kind {@code read}
	 * @param pDescribeError the {@code connectors_describe} failure, null when it answered (or was not called)
	 * @param pDescribedReadOnly whether the described connection has a read-only operation
	 */
	public static String unreadableReason(final boolean pTypeListsOperations, final boolean pTypeHasReadOperation, final String pDescribeError, final boolean pDescribedReadOnly)
	{
		if (pTypeListsOperations)
		{
			return pTypeHasReadOperation ? "" : AddApplicationOptions.REASON_NO_READ_OPERATION;
		}
		if (pDescribeError != null)
		{
			return AddApplicationOptions.REASON_DESCRIBE_FAILED;
		}
		return pDescribedReadOnly ? "" : AddApplicationOptions.REASON_NO_READ_OPERATION;
	}

	/** Whether a connection of this type can never be an application of a project (a language model). */
	public static boolean neverASource(final String pConnectionType)
	{
		return pConnectionType != null && AddApplicationOptions.NOT_A_SOURCE_TYPES.contains(pConnectionType);
	}

	/** Whether picking this connection starts the deduced scan (it has no catalog operation) rather than the catalog scan. */
	public static boolean usesDeducedScan(final SourceOnboarding.ConnectionOption pConnection)
	{
		return !pConnection.catalogCapable;
	}

	public static final class Row
	{
		/** The connection id for a pickable or connection row, the catalog source id for a row with no connection. */
		public final String id;
		public final String label;
		public final String type;
		public final State state;
		/** Scanned tables with columns, -1 when unknown (only meaningful for {@link State#SCANNED}). */
		public final int tables;
		/** For {@link State#CANNOT_READ}: the reason suffix of the message {@code wizard.src2.app.reason.*}; empty otherwise. */
		public final String reason;

		Row(final String pId, final String pLabel, final String pType, final State pState, final int pTables)
		{
			this(pId, pLabel, pType, pState, pTables, "");
		}

		Row(final String pId, final String pLabel, final String pType, final State pState, final int pTables, final String pReason)
		{
			this.reason = pReason == null ? "" : pReason;
			this.id = pId;
			this.label = pLabel;
			this.type = pType;
			this.state = pState;
			this.tables = pTables;
		}

		public boolean pickable()
		{
			return this.state == State.SCANNED || this.state == State.CONNECTION_ONLY || this.state == State.FOUND_FROM_DATA || this.state == State.DEDUCED_READ;
		}

		/** Whether the chip carries the table count ({@code wizard.src2.app.state.<STATE>.tables}). */
		public boolean showsTables()
		{
			return (this.state == State.SCANNED || this.state == State.FOUND_FROM_DATA) && this.tables >= 0;
		}

		/** The message key of the chip of this row ({@code wizard.src2.app.state.*}). */
		public String chipKey()
		{
			return "wizard.src2.app.state." + this.state.name();
		}
	}

	/**
	 * @param pConnections every connection of Connectors ({@code SourceOnboarder#connections}), with {@link SourceOnboarding.ConnectionOption#scannedTables} when counted
	 * @param pCatalogSources every source of the catalog (the picker's own list)
	 * @param pProjectConnectionIds the connection ids of the project's applications
	 * @param pProjectCatalogSourceIds the catalog source ids of the project's applications
	 * @return scanned connections first (the quickest to add), then connections to scan, then those already in the project, then the greyed ones; label order within each group
	 */
	public static List<Row> build(final List<SourceOnboarding.ConnectionOption> pConnections, final List<WizardSnapshot.CatalogSourceInfo> pCatalogSources,
		final Set<String> pProjectConnectionIds, final Set<String> pProjectCatalogSourceIds)
	{
		final List<Row> rows = new ArrayList<Row>();
		final Set<String> sourcesOfConnections = new HashSet<String>();
		final Set<String> knownConnections = new HashSet<String>();
		for (final SourceOnboarding.ConnectionOption connection : pConnections)
		{
			knownConnections.add(connection.id);
			if (!connection.catalogSourceId.isEmpty())
			{
				sourcesOfConnections.add(connection.catalogSourceId);
			}
			final boolean inProject = pProjectConnectionIds.contains(connection.id)
				|| !connection.catalogSourceId.isEmpty() && pProjectCatalogSourceIds.contains(connection.catalogSourceId);
			final State state;
			String reason = "";
			if (inProject)
			{
				state = State.IN_PROJECT;
			}
			else if (!connection.active)
			{
				state = State.INACTIVE;
			}
			else if (AddApplicationOptions.neverASource(connection.type))
			{
				state = State.CANNOT_READ;
				reason = AddApplicationOptions.REASON_NOT_AN_APPLICATION;
			}
			else if (!connection.catalogCapable)
			{
				// UX lot C1: no catalog operation is no longer a dead end - the tables found from the data, or a deduced scan to run, or a real reason
				if (!connection.catalogSourceId.isEmpty() && connection.scannedTables > 0)
				{
					state = State.FOUND_FROM_DATA;
				}
				else if (connection.readable)
				{
					state = State.DEDUCED_READ;
				}
				else
				{
					state = State.CANNOT_READ;
					reason = connection.unreadableReason.isEmpty() ? AddApplicationOptions.REASON_NO_READ_OPERATION : connection.unreadableReason;
				}
			}
			else if (connection.catalogSourceId.isEmpty())
			{
				state = State.CONNECTION_ONLY;
			}
			else if (connection.scannedTables == 0)
			{
				// in the catalog but nothing with a column was ever read (declared by lineage events): picking it scans it first
				state = State.CONNECTION_ONLY;
			}
			else
			{
				state = State.SCANNED;
			}
			rows.add(new Row(connection.id, connection.label, connection.type, state, connection.scannedTables, reason));
		}
		for (final WizardSnapshot.CatalogSourceInfo source : pCatalogSources)
		{
			if (sourcesOfConnections.contains(source.id))
			{
				continue; // reached through its connection, above
			}
			final String label = source.label.isEmpty() ? source.id : source.label;
			if (pProjectCatalogSourceIds.contains(source.id))
			{
				rows.add(new Row(source.id, label, source.platform, State.IN_PROJECT, -1));
			}
			else if (source.connector.isEmpty())
			{
				rows.add(new Row(source.id, label, source.platform, State.LINEAGE_ONLY, -1));
			}
			else if (!knownConnections.contains(source.connector))
			{
				rows.add(new Row(source.id, label, source.platform, State.CONNECTION_MISSING, -1));
			}
		}
		Collections.sort(rows, new Comparator<Row>()
		{
			@Override
			public int compare(final Row pLeft, final Row pRight)
			{
				final int left = AddApplicationOptions.group(pLeft.state);
				final int right = AddApplicationOptions.group(pRight.state);
				return left != right ? Integer.compare(left, right) : pLeft.label.compareToIgnoreCase(pRight.label);
			}
		});
		return rows;
	}

	private static int group(final State pState)
	{
		switch (pState)
		{
			case SCANNED:
			case FOUND_FROM_DATA:
				return 0;
			case CONNECTION_ONLY:
			case DEDUCED_READ:
				return 1;
			case IN_PROJECT:
				return 2;
			default:
				return 3;
		}
	}
}
