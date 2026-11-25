package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Source drift (lot B, 2026-10-30, {@code docs/DRIFT.md}): what changed in a source's schema between the scan the landing was generated
 * from (its {@code LandingColumn} rows, a frozen copy) and the catalog's current scan, column by column - ADDED, REMOVED or RETYPED - and what each change
 * implies for the landing model, the staging zone and the mapping. Pure: no EBX class. A renamed column cannot be told from a gone one and a new one: it is
 * two changes, each decided on its own.
 */
public final class SchemaDrift
{
	public static final String ADDED = "ADDED";
	public static final String REMOVED = "REMOVED";
	public static final String RETYPED = "RETYPED";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private SchemaDrift()
	{
	}

	/** One {@code LandingColumn} row as the drift reads it. */
	public static final class Known
	{
		public final String sourceColumn;
		public final String fieldName;
		public final String sourceDataType;
		public final boolean sourceKey;

		public Known(final String pSourceColumn, final String pFieldName, final String pSourceDataType, final boolean pSourceKey)
		{
			this.sourceColumn = pSourceColumn;
			this.fieldName = pFieldName;
			this.sourceDataType = pSourceDataType;
			this.sourceKey = pSourceKey;
		}
	}

	/** One column of the current scan. {@code type} is what the planner stores as {@code sourceDataType}: the native type, else the normalised one. */
	public static final class Scanned
	{
		public final String name;
		public final String type;
		/** The catalog's normalised type (INTEGER, DECIMAL, STRING, ...), what the staging type is inferred from. */
		public final String dataType;

		public Scanned(final String pName, final String pType)
		{
			this(pName, pType, null);
		}

		public Scanned(final String pName, final String pType, final String pDataType)
		{
			this.name = pName;
			this.type = pType;
			this.dataType = pDataType;
		}
	}

	/** One change. {@code fieldName} is the landing field: the existing one, or the one an ADDED column would get. */
	public static final class Change
	{
		public final String kind;
		public final String sourceColumn;
		public final String fieldName;
		public final String oldType;
		public final String newType;
		public final boolean sourceKey;
		/** The scanned column's normalised type (ADDED and RETYPED), null otherwise. */
		public final String newDataType;

		public Change(final String pKind, final String pSourceColumn, final String pFieldName, final String pOldType, final String pNewType, final boolean pSourceKey)
		{
			this(pKind, pSourceColumn, pFieldName, pOldType, pNewType, pSourceKey, null);
		}

		public Change(final String pKind, final String pSourceColumn, final String pFieldName, final String pOldType, final String pNewType, final boolean pSourceKey,
			final String pNewDataType)
		{
			this.newDataType = pNewDataType;
			this.kind = pKind;
			this.sourceColumn = pSourceColumn;
			this.fieldName = pFieldName;
			this.oldType = pOldType;
			this.newType = pNewType;
			this.sourceKey = pSourceKey;
		}

		/** What identifies the change on file, so a rescan never records the same open change twice. */
		public String signature()
		{
			return this.kind + "|" + this.sourceColumn + "|" + (this.newType == null ? "" : this.newType);
		}
	}

	/**
	 * The changes, removed and retyped columns first in the landing's order, then the added ones in scan order. A type is compared without case and outer
	 * blanks; a landing column with no recorded type is never reported as retyped (nothing to compare).
	 */
	public static List<Change> diff(final List<Known> pKnown, final List<Scanned> pScanned)
	{
		final Map<String, String> scannedTypes = new HashMap<String, String>();
		final Map<String, String> dataTypes = new HashMap<String, String>();
		for (final Scanned column : pScanned)
		{
			scannedTypes.put(column.name, column.type);
			dataTypes.put(column.name, column.dataType);
		}
		final List<Change> out = new ArrayList<Change>();
		final Set<String> known = new HashSet<String>();
		final List<String> taken = new ArrayList<String>();
		for (final Known column : pKnown)
		{
			known.add(column.sourceColumn);
			taken.add(column.fieldName);
			if (!scannedTypes.containsKey(column.sourceColumn))
			{
				out.add(new Change(SchemaDrift.REMOVED, column.sourceColumn, column.fieldName, column.sourceDataType, null, column.sourceKey));
				continue;
			}
			final String before = SchemaDrift.type(column.sourceDataType);
			final String now = SchemaDrift.type(scannedTypes.get(column.sourceColumn));
			if (!before.isEmpty() && !now.isEmpty() && !before.equals(now))
			{
				out.add(new Change(SchemaDrift.RETYPED, column.sourceColumn, column.fieldName, column.sourceDataType, scannedTypes.get(column.sourceColumn), column.sourceKey,
					dataTypes.get(column.sourceColumn)));
			}
		}
		final List<String> added = new ArrayList<String>();
		final List<Scanned> addedColumns = new ArrayList<Scanned>();
		for (final Scanned column : pScanned)
		{
			if (!known.contains(column.name))
			{
				added.add(column.name);
				addedColumns.add(column);
			}
		}
		final List<String> names = ColumnRefresh.newFieldNames(taken, added);
		for (int i = 0; i < added.size(); i++)
		{
			out.add(new Change(SchemaDrift.ADDED, added.get(i), names.get(i), null, addedColumns.get(i).type, false, addedColumns.get(i).dataType));
		}
		return out;
	}

	static String type(final String pType)
	{
		return pType == null ? "" : pType.trim().toUpperCase(Locale.ROOT);
	}

	/** The landing model change: every landing field is text, so only an ADDED column changes the model. */
	public static String landingImpact(final String pTableName, final Change pChange)
	{
		if (SchemaDrift.ADDED.equals(pChange.kind))
		{
			return "Add the text field " + pChange.fieldName + " to the landing table " + pTableName + ", then republish the landing model.";
		}
		if (SchemaDrift.REMOVED.equals(pChange.kind))
		{
			return "Keep the field " + pChange.fieldName + " of " + pTableName + " (the rows already loaded keep their values); the landing column is retired and no longer read.";
		}
		return "No model change: the landing stores " + pChange.fieldName + " as text whatever the source type.";
	}

	/** The staging change, from the attributes that read the column today ({@code pMappedAttributes}, may be empty). */
	public static String stagingImpact(final Change pChange, final List<String> pMappedAttributes)
	{
		final boolean mapped = pMappedAttributes != null && !pMappedAttributes.isEmpty();
		if (SchemaDrift.ADDED.equals(pChange.kind))
		{
			return "No staging attribute reads the new column: map it in the mapping matrix if the entity needs it.";
		}
		if (SchemaDrift.REMOVED.equals(pChange.kind))
		{
			return mapped ? "The staging attributes " + pMappedAttributes + " lose their source: their mappings from this column are removed and must be remapped."
				: "No staging attribute reads this column.";
		}
		return mapped ? "Check the type and transformation of the staging attributes " + pMappedAttributes + ": the source now declares " + pChange.newType + " (was "
			+ pChange.oldType + ")." : "No staging attribute reads this column.";
	}

	/** The mapping change: what the landing flow (the Connectors mapping) does. */
	public static String mappingImpact(final Change pChange)
	{
		if (SchemaDrift.ADDED.equals(pChange.kind))
		{
			return "The landing flow is regenerated to read " + pChange.sourceColumn + " into " + pChange.fieldName + ".";
		}
		if (SchemaDrift.REMOVED.equals(pChange.kind))
		{
			return "The landing flow is regenerated without " + pChange.sourceColumn + (pChange.sourceKey ? "; it was part of the source key, which must be chosen again" : "")
				+ ".";
		}
		return "The landing flow is unchanged; the recorded source type is updated.";
	}

	/** The {@code operations_json} of {@code apply_data_model_patch} for one change on the landing table {@code pTableName}; empty when the model does not change. */
	public static ArrayNode operations(final String pTableName, final Change pChange)
	{
		final ArrayNode out = SchemaDrift.MAPPER.createArrayNode();
		if (SchemaDrift.ADDED.equals(pChange.kind))
		{
			final ObjectNode op = out.addObject();
			op.put("op", "create_field");
			final ObjectNode arguments = op.putObject("arguments");
			arguments.put("field_path", LandingModel.tablePath(pTableName) + "/" + pChange.fieldName);
			arguments.put("field_type", "string");
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------ revalidation (F3-01)

	/** The verdict of {@link #revalidate}: the change recomputed from the real structures, or the code of the refusal and what it is about. */
	public static final class Verdict
	{
		/** The change as the server computes it now; null when refused. */
		public final Change change;
		/** The bundle code of the refusal (without the {@code engine.} prefix), null when accepted. */
		public final String refusal;
		public final String about;

		private Verdict(final Change pChange, final String pRefusal, final String pAbout)
		{
			this.change = pChange;
			this.refusal = pRefusal;
			this.about = pAbout;
		}

		public boolean ok()
		{
			return this.change != null;
		}
	}

	public static final String REFUSAL_KIND = "invalid.driftKind";
	public static final String REFUSAL_FIELD = "invalid.driftField";
	public static final String REFUSAL_NOT_IN_SOURCE = "invalid.driftColumnNotInSource";
	public static final String REFUSAL_STALE = "conflict.driftStale";

	/** Whether {@code pName} has the form of a landing field name ({@link LandingModel#fieldName} writes letters, digits and underscores, starting with a letter). */
	public static boolean isFieldName(final String pName)
	{
		return pName != null && pName.length() <= 80 && pName.matches("[A-Za-z][A-Za-z0-9_]*");
	}

	/** Whether {@code pName} has the form of a landing table name ({@link LandingModel}'s {@code xmlName}, possibly suffixed by a number). */
	public static boolean isTableName(final String pName)
	{
		return pName != null && pName.length() <= 120 && pName.matches("[A-Za-z_][A-Za-z0-9_]*");
	}

	/**
	 * F3-01: what a stored {@code SourceDrift} row is allowed to say is WHICH change it is about (kind, source column, landing field); everything the server
	 * executes is recomputed here from the real structures, never read from the row. The three designating values are revalidated: the kind is a known one,
	 * the landing field and the table have the form of a name, the column is in the scanned source when the change adds it, and the change still exists
	 * ({@link #diff} of the landing's frozen columns against the scan, with the same landing field). Otherwise the verdict is a refusal; a change that
	 * is no longer there is {@link #REFUSAL_STALE} ("replan").
	 */
	public static Verdict revalidate(final String pTableName, final String pKind, final String pSourceColumn, final String pFieldName, final List<Known> pKnown,
		final List<Scanned> pScanned)
	{
		if (!SchemaDrift.ADDED.equals(pKind) && !SchemaDrift.REMOVED.equals(pKind) && !SchemaDrift.RETYPED.equals(pKind))
		{
			return new Verdict(null, SchemaDrift.REFUSAL_KIND, String.valueOf(pKind));
		}
		if (!SchemaDrift.isTableName(pTableName))
		{
			return new Verdict(null, SchemaDrift.REFUSAL_FIELD, "table " + pTableName);
		}
		if (!SchemaDrift.isFieldName(pFieldName))
		{
			return new Verdict(null, SchemaDrift.REFUSAL_FIELD, String.valueOf(pFieldName));
		}
		if (pSourceColumn == null || pSourceColumn.trim().isEmpty() || pSourceColumn.length() > 255)
		{
			return new Verdict(null, SchemaDrift.REFUSAL_FIELD, "source column");
		}
		if (!SchemaDrift.ADDED.equals(pKind))
		{
			boolean landingHasIt = false;
			for (final Known column : pKnown)
			{
				landingHasIt |= pSourceColumn.equals(column.sourceColumn);
			}
			if (!landingHasIt)
			{
				return new Verdict(null, SchemaDrift.REFUSAL_STALE, pSourceColumn);
			}
		}
		else
		{
			boolean scanned = false;
			for (final Scanned column : pScanned)
			{
				scanned |= pSourceColumn.equals(column.name);
			}
			if (!scanned)
			{
				return new Verdict(null, SchemaDrift.REFUSAL_NOT_IN_SOURCE, pSourceColumn);
			}
		}
		for (final Change change : SchemaDrift.diff(pKnown, pScanned))
		{
			if (change.kind.equals(pKind) && change.sourceColumn.equals(pSourceColumn))
			{
				return change.fieldName.equals(pFieldName) ? new Verdict(change, null, null) : new Verdict(null, SchemaDrift.REFUSAL_STALE, pSourceColumn);
			}
		}
		return new Verdict(null, SchemaDrift.REFUSAL_STALE, pSourceColumn);
	}

	/**
	 * F3-113: whether the apply message of a FAILED ADDED row says the model was already patched and republished (the message {@code apply} writes before it
	 * goes on to the landing column), in which case a retry must not patch the model again - the server would refuse a field that exists.
	 */
	public static boolean modelAlreadyPatched(final String pApplyMessage, final String pFieldName, final String pModel)
	{
		return pApplyMessage != null && pFieldName != null && pModel != null && pApplyMessage.startsWith("Field " + pFieldName + " added to " + pModel + " and republished.");
	}

	/**
	 * The v1.3.0 warning, which a landing plan keeps writing when source drift is closed: added and removed columns only (a retyped column alone is not
	 * a warning), in the exact words of the tag; null when no column was added or removed.
	 */
	public static String closedMessage(final List<Change> pChanges)
	{
		final List<String> added = new ArrayList<String>();
		final List<String> removed = new ArrayList<String>();
		for (final Change change : pChanges)
		{
			if (ADDED.equals(change.kind))
			{
				added.add(change.sourceColumn);
			}
			else if (REMOVED.equals(change.kind))
			{
				removed.add(change.sourceColumn);
			}
		}
		if (added.isEmpty() && removed.isEmpty())
		{
			return null;
		}
		return "The source changed since the landing was generated. New columns: " + added + "; columns gone: " + removed + ".";
	}

	/** One line for the landing table's message: {@code New columns: [A]; columns gone: [B]; retyped: [C (INT -> VARCHAR)].} */
	public static String summary(final List<Change> pChanges)
	{
		final List<String> added = new ArrayList<String>();
		final List<String> removed = new ArrayList<String>();
		final List<String> retyped = new ArrayList<String>();
		for (final Change change : pChanges)
		{
			if (SchemaDrift.ADDED.equals(change.kind))
			{
				added.add(change.sourceColumn);
			}
			else if (SchemaDrift.REMOVED.equals(change.kind))
			{
				removed.add(change.sourceColumn);
			}
			else
			{
				retyped.add(change.sourceColumn + " (" + change.oldType + " -> " + change.newType + ")");
			}
		}
		return "The source changed since the landing was generated. New columns: " + added + "; columns gone: " + removed + "; retyped: " + retyped
			+ ". Each change waits for a decision (source drift).";
	}
}
