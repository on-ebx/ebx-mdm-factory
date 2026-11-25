package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;

/**
 * The landing data model of one source system, as a pure function of the source structures: one table
 * per source object, every source column a plain string without any constraint (a landing never refuses
 * what the source sends), followed by the technical fields of {@link FactoryPaths.Landing}. The key is
 * {@code _sourceId}: the identity of the row in its system.
 * <p>
 * {@link #tree} gives the JSON the {@code build_data_model} tool of the {@code data-model} MCP server
 * takes. No EBX class is needed here: this is unit-tested as is.
 */
public final class LandingModel
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Recognised file extensions {@link #baseNameOf} strips from a qualified name's trailing segment - exactly
	 * the set {@code sample-file}'s own upload widget accepts ({@code UploadedSampleFileWidgetFactory} in
	 * {@code ebx-connectors}, not checked out here), kept in sync by convention rather than by a shared constant.
	 */
	private static final Set<String> FILE_EXTENSIONS = new HashSet<String>(Arrays.asList("csv", "tsv", "json", "ndjson", "jsonl", "txt"));

	private LandingModel()
	{
	}

	/** A source column and the landing field generated for it. */
	public static final class Column
	{
		public final String sourceColumn;
		public final String fieldName;

		Column(final String pSourceColumn, final String pFieldName)
		{
			this.sourceColumn = pSourceColumn;
			this.fieldName = pFieldName;
		}
	}

	/** A source object and the landing table generated for it. */
	public static final class Table
	{
		public final String sourceObject;
		public final String tableName;
		public final List<Column> columns = new ArrayList<Column>();

		Table(final String pSourceObject, final String pTableName)
		{
			this.sourceObject = pSourceObject;
			this.tableName = pTableName;
		}
	}

	/**
	 * A table already named — by an earlier plan, stored as a {@code LandingTable} row and its
	 * {@code LandingColumn} rows — built into the same {@link Table} shape {@link Builder#table} produces,
	 * without running the naming/uniqueness rules again: the names were already decided once, when the plan
	 * was written, and must not change on a later, resumed run. The generator uses this, not the
	 * {@link Builder}, to reproduce the exact tree {@link #tree} builds from a plan.
	 */
	public static Table namedTable(final String pSourceObject, final String pTableName)
	{
		return new Table(pSourceObject, pTableName);
	}

	/** A column of a {@link #namedTable}, its field name already decided. */
	public static Column namedColumn(final String pSourceColumn, final String pFieldName)
	{
		return new Column(pSourceColumn, pFieldName);
	}

	/** Names the tables and fields of a landing model, keeping them unique. One instance per data model. */
	public static final class Builder
	{
		private final List<Table> tables = new ArrayList<Table>();
		private final Set<String> tableNames = new HashSet<String>();

		/** A table name already given to a source object by an earlier plan: it stays the name of that object only. */
		public void reserve(final String pTableName)
		{
			this.tableNames.add(pTableName.toLowerCase(Locale.ROOT));
		}

		/**
		 * @param pSourceObject qualified name at the source ({@code DB.SCHEMA.TABLE}); the table is named after its last
		 *        part, and after more of it when two objects of the source end alike.
		 */
		public Table table(final String pSourceObject, final List<String> pSourceColumns)
		{
			final String[] parts = LandingModel.baseNameOf(pSourceObject).split("[.]");
			String name = LandingModel.xmlName(parts[parts.length - 1]);
			for (int i = parts.length - 2; i >= 0 && this.tableNames.contains(name.toLowerCase(Locale.ROOT)); i--)
			{
				name = LandingModel.xmlName(parts[i]) + "_" + name;
			}
			name = LandingModel.unique(name, this.tableNames);
			final Table table = new Table(pSourceObject, name);
			final Set<String> fieldNames = new HashSet<String>();
			for (final String column : pSourceColumns)
			{
				table.columns.add(new Column(column, LandingModel.unique(LandingModel.fieldName(column), fieldNames)));
			}
			this.tables.add(table);
			return table;
		}

		public List<Table> tables()
		{
			return this.tables;
		}

		/** The model as the {@code model_json} argument of {@code build_data_model}. */
		public ObjectNode tree()
		{
			return LandingModel.tree(this.tables);
		}
	}

	/**
	 * A source column name as an EBX field name. A leading underscore is kept for the factory's own fields,
	 * so a source column that starts with one (or with a digit) gets a {@code c} in front.
	 */
	public static String fieldName(final String pSourceColumn)
	{
		final String name = LandingModel.xmlName(pSourceColumn);
		return name.startsWith("_") ? "c" + name : name;
	}

	/**
	 * {@code pQualifiedName} with a recognised file extension stripped off its trailing dot-separated segment,
	 * when there is more than one segment and the last one (case-insensitively) is one of {@link #FILE_EXTENSIONS}
	 * - otherwise returned unchanged. Both {@link Builder#table} and {@code LandingPlanner}'s relation-naming
	 * logic run their own "name a table/column after the qualified name's last dot-separated segment" logic on
	 * the result of this method, not on the raw qualified name.
	 * <p>
	 * A file-connector asset's {@code qualifiedName} is a plain path such as {@code sample.csv}: without this,
	 * that last-segment logic would name the table after the file's extension ({@code csv}), not the file
	 * itself ({@code sample}) - and two files sharing an extension ({@code orders.csv}, {@code customers.csv})
	 * would collide onto the same generated name before {@link #unique} ever gets a chance to disambiguate them.
	 * A genuine SQL qualified name ({@code DB.SCHEMA.TABLE}) is unaffected: {@code TABLE} is never one of the
	 * six recognised extensions, so nothing is stripped and every existing SQL-source behaviour is unchanged.
	 */
	public static String baseNameOf(final String pQualifiedName)
	{
		final String qualifiedName = pQualifiedName == null ? "" : pQualifiedName;
		final int lastDot = qualifiedName.lastIndexOf('.');
		if (lastDot < 0)
		{
			return qualifiedName;
		}
		final String extension = qualifiedName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
		return LandingModel.FILE_EXTENSIONS.contains(extension) ? qualifiedName.substring(0, lastDot) : qualifiedName;
	}

	/** Letters, digits and underscore only, starting with a letter or an underscore; never empty. */
	static String xmlName(final String pName)
	{
		final StringBuilder out = new StringBuilder();
		final String name = pName == null ? "" : pName.trim();
		for (int i = 0; i < name.length(); i++)
		{
			final char c = name.charAt(i);
			final boolean plain = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
			out.append(plain ? c : '_');
		}
		if (out.length() == 0)
		{
			return "column";
		}
		final char first = out.charAt(0);
		return first >= '0' && first <= '9' ? "c_" + out : out.toString();
	}

	/** EBX compares sibling names as they are, a database does not: uniqueness is decided without the case. */
	private static String unique(final String pName, final Set<String> pTaken)
	{
		String name = pName;
		for (int i = 2; !pTaken.add(name.toLowerCase(Locale.ROOT)); i++)
		{
			name = pName + "_" + i;
		}
		return name;
	}

	/** Where {@link #tree} puts a table of the given name: {@code /root/<tableName>}. Also what the generator compares a server's own answer against, so the two never drift apart. */
	public static String tablePath(final String pTableName)
	{
		return "/root/" + pTableName;
	}

	/** The model as the {@code model_json} argument of {@code build_data_model}, from any source of {@link Table}s. */
	public static ObjectNode tree(final List<Table> pTables)
	{
		final ObjectNode root = LandingModel.MAPPER.createObjectNode();
		final ArrayNode tables = root.putArray("tables");
		for (final Table table : pTables)
		{
			final String path = LandingModel.tablePath(table.tableName);
			final ObjectNode node = tables.addObject();
			node.put("path", path);
			node.put("label", table.sourceObject);
			node.putArray("primaryKey").add(FactoryPaths.Landing.SOURCE_ID);
			final ArrayNode fields = node.putArray("fields");
			LandingModel.field(fields, path, FactoryPaths.Landing.SOURCE_ID, "string", "Source id");
			for (final Column column : table.columns)
			{
				LandingModel.field(fields, path, column.fieldName, "string", column.sourceColumn);
			}
			LandingModel.field(fields, path, FactoryPaths.Landing.SOURCE_SYSTEM, "string", "Source system");
			LandingModel.field(fields, path, FactoryPaths.Landing.LOAD_ID, "string", "Load");
			LandingModel.field(fields, path, FactoryPaths.Landing.RECEIVED_AT, "dateTime", "Received");
			LandingModel.field(fields, path, FactoryPaths.Landing.ROW_HASH, "string", "Row hash");
			LandingModel.field(fields, path, FactoryPaths.Landing.STATUS, "string", "Status");
		}
		return root;
	}

	private static void field(final ArrayNode pFields, final String pTablePath, final String pName, final String pType, final String pLabel)
	{
		final ObjectNode field = pFields.addObject().putObject("field");
		field.put("name", pName);
		field.put("path", pTablePath + "/" + pName);
		field.put("type", pType);
		field.put("label", pLabel);
	}
}
