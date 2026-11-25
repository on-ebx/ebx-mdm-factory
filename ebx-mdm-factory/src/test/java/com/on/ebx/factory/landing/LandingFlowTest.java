package com.on.ebx.factory.landing;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class LandingFlowTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		LandingFlowTest.checkDialectSelection();
		LandingFlowTest.checkLabel();
		LandingFlowTest.checkMappingShapeWithKey();
		LandingFlowTest.checkMappingShapeWithoutKey();
		LandingFlowTest.checkReservedWordColumn();
		LandingFlowTest.checkHostileColumnName();
		LandingFlowTest.checkUnsafeIdentifiersRefused();
		LandingFlowTest.checkNoColumns();
		LandingFlowTest.checkSyncMaxRowsIsUnconditionallyLarge();
		LandingFlowTest.checkLoadId();
		LandingFlowTest.checkReceivedAt();
		LandingFlowTest.checkSourceIdKeyFallback();
		LandingFlowTest.checkCompositeKeyEscapingAvoidsCollision();
		LandingFlowTest.checkLoadOutcome();
		LandingFlowTest.checkLoadOutcomeTruncatedFieldFailsRegardlessOfStatus();
		LandingFlowTest.checkLoadOutcomeAvailableExceedsCountFails();
		LandingFlowTest.checkLoadOutcomeShortfallAgainstCatalogWarnsOrFails();
		LandingFlowTest.checkLoadOutcomeNoSourceRowCountSkipsTheComparison();
		LandingFlowTest.checkFeedMappingWithKey();
		LandingFlowTest.checkFeedMappingWithoutKey();
		LandingFlowTest.checkFeedMappingCompositeKeyPredicate();
		LandingFlowTest.checkSourceIdPreview();
		LandingFlowTest.checkJdbcDialects();
		LandingFlowTest.checkUniquenessCheckSql();
		LandingFlowTest.checkRecipeMappingShape();
		LandingFlowTest.checkRecipeMappingWithoutKeyHashesEverything();
		LandingFlowTest.checkRecipeMappingIgnoresConnectorTypeAndFileFeed();
		System.out.println("LandingFlowTest OK");
	}

	/** Coordinator follow-up (2026-09-24): pins {@link LandingFlow#sourceIdPreview} against the SQL path's own escaping rule (SnowflakeDialect#escapeKeyPart) - escaped only for 2+ parts. */
	private static void checkSourceIdPreview()
	{
		LandingFlowTest.check("no parts -> empty", "".equals(LandingFlow.sourceIdPreview(java.util.Collections.<String> emptyList())));
		LandingFlowTest.check("one part, no escaping even with a pipe/backslash", "ACME|FR\\".equals(LandingFlow.sourceIdPreview(java.util.Arrays.asList("ACME|FR\\"))));
		LandingFlowTest.check("one null part -> empty string", "".equals(LandingFlow.sourceIdPreview(java.util.Collections.<String> singletonList(null))));
		LandingFlowTest.check("two plain parts joined with |", "C-0012|1".equals(LandingFlow.sourceIdPreview(java.util.Arrays.asList("C-0012", "1"))));
		// same case the mockup itself shows: a part containing '|' is escaped as '\|' before joining
		LandingFlowTest.check("a '|' inside a part is escaped, composite only", "ACME\\|FR|1".equals(LandingFlow.sourceIdPreview(java.util.Arrays.asList("ACME|FR", "1"))));
		// backslash doubled FIRST, then '|' escaped - reversing the order would double the backslash the pipe-escape just added (SnowflakeDialect#escapeKeyPart's own comment)
		LandingFlowTest.check("backslash doubled before a pipe is escaped",
			"a\\\\\\|b|2".equals(LandingFlow.sourceIdPreview(java.util.Arrays.asList("a\\|b", "2"))));
		LandingFlowTest.check("a null part inside a composite key is empty, not NPE", "|1".equals(LandingFlow.sourceIdPreview(java.util.Arrays.asList(null, "1"))));
	}

	private static void checkDialectSelection()
	{
		final SqlDialect snowflake = LandingFlow.dialectFor("snowflake");
		LandingFlowTest.check("snowflake dialect found", snowflake instanceof SnowflakeDialect);
		final SqlDialect caseInsensitive = LandingFlow.dialectFor("SnowFlake");
		LandingFlowTest.check("dialect lookup is case-insensitive", caseInsensitive instanceof SnowflakeDialect);
		try
		{
			LandingFlow.dialectFor("kafka");
			throw new AssertionError("an unknown connector type must be refused");
		}
		catch (final IllegalArgumentException ex)
		{
			LandingFlowTest.check("refusal names the type", ex.getMessage().contains("kafka"));
			LandingFlowTest.check("refusal names what is supported", ex.getMessage().contains("Snowflake"));
		}
	}

	/**
	 * PersVol volume test, 2026-09-24: H2 and PostgreSQL sources used to stop at "No landing SQL generator". The H2
	 * text below is exactly what was run against a live H2 2.1.212 server before the dialect was written.
	 */
	private static void checkJdbcDialects()
	{
		LandingFlowTest.check("h2 dialect found", LandingFlow.dialectFor("h2") instanceof H2Dialect);
		LandingFlowTest.check("postgresql dialect found", LandingFlow.dialectFor("PostgreSQL") instanceof PostgreSqlDialect);
		LandingFlowTest.check("greenplum uses the PostgreSQL dialect", LandingFlow.dialectFor("greenplum") instanceof PostgreSqlDialect);
		try
		{
			LandingFlow.dialectFor("sql-server");
			throw new AssertionError("sql-server has no || concatenation: it must stay refused");
		}
		catch (final IllegalArgumentException ex)
		{
			LandingFlowTest.check("refusal lists every supported dialect", ex.getMessage().contains("Snowflake, H2, PostgreSQL"));
		}
		LandingFlowTest.check("preview select on an H2 table, each part quoted", "select * from \"CRM\".\"CONTACT\"".equals(LandingFlow.selectAll("h2", "CRM.CONTACT")));
		final H2Dialect h2 = new H2Dialect();
		LandingFlowTest.check("h2 text cast", "CAST(s.\"BIRTH_DATE\" AS VARCHAR)".equals(h2.asText("s." + h2.quoteIdentifier("BIRTH_DATE"))));
		LandingFlowTest.check("h2 md5 as lower-case hex", "LOWER(RAWTOHEX(HASH('MD5', x)))".equals(h2.hash("x")));
		LandingFlowTest.check("h2 key escaping", "REPLACE(REPLACE(x, CHAR(92), CHAR(92) || CHAR(92)), CHAR(124), CHAR(92) || CHAR(124))".equals(h2.escapeKeyPart("x")));
		LandingFlowTest.check("h2 quoting doubles an embedded quote", "\"a\"\"b\"".equals(h2.quoteIdentifier("a\"b")));
		final PostgreSqlDialect pg = new PostgreSqlDialect();
		LandingFlowTest.check("postgresql md5", "MD5(x)".equals(pg.hash("x")));
		LandingFlowTest.check("postgresql key escaping", pg.escapeKeyPart("x").contains("CHR(92)") && pg.escapeKeyPart("x").contains("CHR(124)"));

		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("CONTACT_ID", "CONTACT_ID", true));
		columns.add(new LandingFlow.Column("BIRTH_DATE", "BIRTH_DATE", false));
		final ObjectNode mapping = LandingFlow.mapping(new LandingFlow.Table("PersVolCRM", "h2", "CRM", "PUBLIC.CONTACT", "PersVol_Landing_CRM", "CRM",
			"CONTACT", columns));
		final String sql = mapping.path("inputs").path("sql").asText();
		LandingFlowTest.check("h2 flow uses the query operation", "query".equals(mapping.path("operation").asText()));
		LandingFlowTest.check("h2 flow reads the quoted schema.table", sql.contains("from \"PUBLIC\".\"CONTACT\" s"));
		LandingFlowTest.check("h2 flow has no Snowflake function", !sql.contains("TO_VARCHAR") && !sql.contains("MD5("));
	}

	/** Volume-guards lot (2026-09-28, task item 4): the read-only COUNT(*)/COUNT(DISTINCT ...) query ebx.KeySourceUniqueness runs to verify a proposed key against the whole source. */
	private static void checkUniquenessCheckSql()
	{
		LandingFlowTest.check("single-column key, quoted", "select count(*) as totalCount, count(distinct \"CONTACT_ID\") as distinctCount from \"CRM\".\"CONTACT\""
			.equals(LandingFlow.uniquenessCheckSql("h2", "CRM.CONTACT", java.util.Collections.singletonList("CONTACT_ID"))));
		final String composite = LandingFlow.uniquenessCheckSql("h2", "CONTACT", java.util.Arrays.asList("FIRST_NAME", "LAST_NAME"));
		LandingFlowTest.check("composite key concatenates with COALESCE/'|', same convention as _sourceId: " + composite,
			composite.contains("COALESCE(\"FIRST_NAME\",'')") && composite.contains("COALESCE(\"LAST_NAME\",'')") && composite.contains("count(distinct"));
		try
		{
			LandingFlow.uniquenessCheckSql("h2", "CONTACT", java.util.Collections.<String> emptyList());
			throw new AssertionError("no key columns must be refused");
		}
		catch (final IllegalArgumentException expected)
		{
			// expected
		}
		try
		{
			LandingFlow.uniquenessCheckSql("sql-server", "CONTACT", java.util.Collections.singletonList("ID"));
			throw new AssertionError("an unsupported connector type must stay refused, same as dialectFor/selectAll");
		}
		catch (final IllegalArgumentException expected)
		{
			// expected
		}
	}

	private static void checkLabel()
	{
		LandingFlowTest.check("deterministic label, per project", "Landing: Companies SNOWFLAKE_ERP SUPPLIER".equals(LandingFlow.label("Companies", "SNOWFLAKE_ERP", "SUPPLIER")));
		LandingFlowTest.check("a table without a project keeps the legacy label", "Landing: SNOWFLAKE_ERP SUPPLIER".equals(LandingFlow.label(null, "SNOWFLAKE_ERP", "SUPPLIER")));
	}

	private static void checkMappingShapeWithKey()
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("SUPPLIER_ID", "SUPPLIER_ID", true));
		columns.add(new LandingFlow.Column("NAME1", "NAME1", false));
		columns.add(new LandingFlow.Column("REGISTRATION_NO", "REGISTRATION_NO", false));
		// landingDataset deliberately different from sourceSystem: proves "dataset" is read from its own
		// field, not assumed to equal sourceSystem (that assumption was the point of coordinator fix #3).
		final LandingFlow.Table table = new LandingFlow.Table("Snowflake", "snowflake", "SNOWFLAKE_ERP", "SNOWFLAKE_LEARNING_DB.ERP_SOURCE.SUPPLIER", "Demo_Landing",
			"SNOWFLAKE_ERP_LANDING_DATASET", "SUPPLIER", columns);

		final JsonNode mapping = LandingFlow.mapping(table);
		LandingFlowTest.check("connector", "Snowflake".equals(mapping.path("connector").asText()));
		LandingFlowTest.check("operation", "query".equals(mapping.path("operation").asText()));
		LandingFlowTest.check("direction", "INBOUND".equals(mapping.path("direction").asText()));
		LandingFlowTest.check("label", "Landing: SNOWFLAKE_ERP SUPPLIER".equals(mapping.path("label").asText()));
		LandingFlowTest.check("dataspace", "Demo_Landing".equals(mapping.path("dataspace").asText()));
		LandingFlowTest.check("dataset comes from its own field, not sourceSystem", "SNOWFLAKE_ERP_LANDING_DATASET".equals(mapping.path("dataset").asText()));
		LandingFlowTest.check("tablePath", "/root/SUPPLIER".equals(mapping.path("tablePath").asText()));
		LandingFlowTest.check("active", mapping.path("active").asBoolean());
		LandingFlowTest.check("itemsPath", "rows".equals(mapping.path("itemsPath").asText()));
		LandingFlowTest.check("keyPredicate", "./_sourceId='${SOURCE_ID}'".equals(mapping.path("keyPredicate").asText()));
		LandingFlowTest.check("createIfMissing", mapping.path("createIfMissing").asBoolean());
		// volume-operations lot 8a (2026-09-28, task 6)
		LandingFlowTest.check("provenance is off for a landing write", !mapping.path("provenance").asBoolean(true));
		LandingFlowTest.check("unchangedIfEqual points at the row hash", "./_rowHash".equals(mapping.path("unchangedIfEqual").asText()));

		final String sql = mapping.path("inputs").path("sql").asText();
		// every identifier is always quoted now (coordinator fix #1): a plain-looking name is not proof it
		// isn't a Snowflake reserved word, so nothing is ever emitted bare.
		LandingFlowTest.check("every source column cast to text and quoted: " + sql, sql.contains("TO_VARCHAR(s.\"SUPPLIER_ID\") as C1")
			&& sql.contains("TO_VARCHAR(s.\"NAME1\") as C2") && sql.contains("TO_VARCHAR(s.\"REGISTRATION_NO\") as C3"));
		LandingFlowTest.check("aliases are generated, not raw column names", !sql.contains(" as SUPPLIER_ID") && !sql.contains(" as NAME1"));
		// _sourceId falls back to '#' || row hash only when every key column is NULL (coordinator DECISION):
		// with one key column, that CASE condition is a single "... IS NULL", the ELSE the plain concatenation.
		LandingFlowTest.check("_sourceId is a CASE falling back to '#' || row hash only when the key is entirely NULL: " + sql,
			sql.contains("CASE WHEN TO_VARCHAR(s.\"SUPPLIER_ID\") IS NULL THEN '#' || MD5(")
				&& sql.contains(") ELSE COALESCE(TO_VARCHAR(s.\"SUPPLIER_ID\"),'') END as SOURCE_ID"));
		LandingFlowTest.check("_loadId is the run-time input placeholder", sql.contains("'${param.loadId}' as LOAD_ID"));
		// _receivedAt is minted by the factory (coordinator fix #2), not computed in Snowflake any more.
		LandingFlowTest.check("_receivedAt is the run-time input placeholder, not CURRENT_TIMESTAMP()",
			sql.contains("'${param.receivedAt}' as RECEIVED_AT") && !sql.contains("CURRENT_TIMESTAMP"));
		LandingFlowTest.check("_rowHash hashes every column, in position order: " + sql,
			sql.contains("MD5(COALESCE(TO_VARCHAR(s.\"SUPPLIER_ID\"),'') || '|' || COALESCE(TO_VARCHAR(s.\"NAME1\"),'') || '|' || COALESCE(TO_VARCHAR(s.\"REGISTRATION_NO\"),'')) as ROW_HASH"));
		LandingFlowTest.check("FROM clause is the qualified name, every segment quoted: " + sql,
			sql.endsWith("from \"SNOWFLAKE_LEARNING_DB\".\"ERP_SOURCE\".\"SUPPLIER\" s"));

		final JsonNode rules = mapping.path("rules");
		// SOURCE_ID + 3 columns + _sourceSystem + LOAD_ID + RECEIVED_AT + ROW_HASH + _status = 9
		LandingFlowTest.check("one rule per column plus the six technical rules: " + rules.size(), rules.size() == 9);
		LandingFlowTest.check("SOURCE_ID rule first", "SOURCE_ID".equals(rules.get(0).path("sourcePath").asText()) && "./_sourceId".equals(rules.get(0).path("targetPath").asText()));
		LandingFlowTest.check("column rule maps the generated alias to the field", "C1".equals(rules.get(1).path("sourcePath").asText()) && "./SUPPLIER_ID".equals(rules.get(1).path("targetPath").asText()));
		JsonNode sourceSystemRule = null;
		JsonNode receivedAtRule = null;
		JsonNode statusRule = null;
		for (final JsonNode rule : rules)
		{
			if ("./_sourceSystem".equals(rule.path("targetPath").asText()))
			{
				sourceSystemRule = rule;
			}
			if ("./_receivedAt".equals(rule.path("targetPath").asText()))
			{
				receivedAtRule = rule;
			}
			if ("./_status".equals(rule.path("targetPath").asText()))
			{
				statusRule = rule;
			}
		}
		LandingFlowTest.check("_sourceSystem is a constant rule with an empty source", sourceSystemRule != null && "".equals(sourceSystemRule.path("sourcePath").asText())
			&& "constant(SNOWFLAKE_ERP)".equals(sourceSystemRule.path("transformation").asText()));
		LandingFlowTest.check("_receivedAt rule has no transformation: the value already matches xs:dateTime exactly", receivedAtRule != null
			&& "RECEIVED_AT".equals(receivedAtRule.path("sourcePath").asText()) && "".equals(receivedAtRule.path("transformation").asText()));
		// 2026-09-23, incremental-transfer lot: ALWAYS, not IF_EMPTY - a reload always goes back to NEW, see LandingFlow's own Javadoc
		LandingFlowTest.check("_status defaults to NEW, ALWAYS so a reload always goes back to NEW", statusRule != null
			&& "constant(NEW)".equals(statusRule.path("transformation").asText()) && "ALWAYS".equals(statusRule.path("policy").asText()));
	}

	private static void checkMappingShapeWithoutKey()
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("A", "A", false));
		columns.add(new LandingFlow.Column("B", "B", false));
		final LandingFlow.Table table = new LandingFlow.Table("Snowflake", "snowflake", "SRC", "DB.SCHEMA.T", "Demo_Landing", "SRC", "T", columns);
		final String sql = LandingFlow.mapping(table).path("inputs").path("sql").asText();
		final String rowHashExpr = "MD5(COALESCE(TO_VARCHAR(s.\"A\"),'') || '|' || COALESCE(TO_VARCHAR(s.\"B\"),''))";
		LandingFlowTest.check("without any source key, _sourceId is the same MD5 expression as _rowHash, no CASE and no '#': " + sql,
			sql.contains(rowHashExpr + " as SOURCE_ID") && sql.contains(rowHashExpr + " as ROW_HASH") && !sql.contains("CASE WHEN"));
	}

	/** A column named like a Snowflake reserved word must still be quoted, or the statement fails to parse (coordinator fix #1). */
	private static void checkReservedWordColumn()
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("ORDER", "orderCode", true));
		columns.add(new LandingFlow.Column("GROUP", "groupCode", false));
		final LandingFlow.Table table = new LandingFlow.Table("Snowflake", "snowflake", "SRC", "DB.SCHEMA.T", "Demo_Landing", "SRC", "T", columns);
		final String sql = LandingFlow.mapping(table).path("inputs").path("sql").asText();
		LandingFlowTest.check("a column named like a reserved word is quoted, not emitted bare: " + sql,
			sql.contains("TO_VARCHAR(s.\"ORDER\") as C1") && sql.contains("TO_VARCHAR(s.\"GROUP\") as C2"));
		LandingFlowTest.check("no bare, unquoted reserved word anywhere near a column reference", !sql.contains("s.ORDER") && !sql.contains("s.GROUP"));
	}

	private static void checkHostileColumnName()
	{
		final String hostile = "X\" from dual; drop table T; --";
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column(hostile, "field1", true));
		final LandingFlow.Table table = new LandingFlow.Table("Snowflake", "snowflake", "SRC", "DB.SCHEMA.T", "Demo_Landing", "SRC", "T", columns);
		final String sql = LandingFlow.mapping(table).path("inputs").path("sql").asText();
		// the identifier must come back double-quoted with every embedded double quote doubled: this is what
		// stops the hostile text from ever closing the identifier and running as SQL of its own.
		final String quoted = "\"X\"\" from dual; drop table T; --\"";
		LandingFlowTest.check("hostile column name is safely quoted: " + sql, sql.contains("s." + quoted));
		// the hostile text itself contains " from dual" and "drop table" (lower case, like the SQL keywords
		// this generator writes), so a naive "contains" check on those words would prove nothing either way -
		// what actually matters is that the *real* FROM clause, naming the real table, is intact and unique.
		final String realFrom = " from \"DB\".\"SCHEMA\".\"T\" s";
		LandingFlowTest.check("the real FROM clause is intact and terminates the statement: " + sql, sql.endsWith(realFrom) && sql.indexOf(realFrom) == sql.lastIndexOf(realFrom));
		// the hostile column is also the sole key column, so it now appears in: C1, the CASE's "IS NULL"
		// condition, the row hash (used twice - once in the CASE's '#' branch, once as the ROW_HASH column),
		// and the CASE's ELSE concatenation = 5 occurrences, each individually quoted and escaped.
		int occurrences = 0;
		for (int from = 0; (from = sql.indexOf(quoted, from)) >= 0; from += quoted.length())
		{
			occurrences++;
		}
		LandingFlowTest.check("the quoted hostile identifier appears once per expression that reads it: " + occurrences, occurrences == 5);

		final String plain = SnowflakeDialect.class.getName(); // sanity: dialect class loads
		LandingFlowTest.check("dialect class present", plain.endsWith("SnowflakeDialect"));
	}

	/** A '${' or a control character in a catalog-scanned name is refused up front, not embedded (coordinator fix #2). */
	private static void checkUnsafeIdentifiersRefused()
	{
		final List<LandingFlow.Column> withPlaceholder = new ArrayList<LandingFlow.Column>();
		withPlaceholder.add(new LandingFlow.Column("A${param.loadId}B", "field1", true));
		final LandingFlow.Table placeholderTable = new LandingFlow.Table("Snowflake", "snowflake", "SRC", "DB.SCHEMA.T", "Demo_Landing", "SRC", "T", withPlaceholder);
		try
		{
			LandingFlow.mapping(placeholderTable);
			throw new AssertionError("a column name containing '${' must be refused");
		}
		catch (final IllegalArgumentException ex)
		{
			LandingFlowTest.check("refusal names the column", ex.getMessage().contains("A${param.loadId}B"));
			LandingFlowTest.check("refusal names the reason", ex.getMessage().contains("${"));
		}

		final List<LandingFlow.Column> plain = new ArrayList<LandingFlow.Column>();
		plain.add(new LandingFlow.Column("A", "field1", true));
		final LandingFlow.Table controlCharTable = new LandingFlow.Table("Snowflake", "snowflake", "SRC", "DB.SCHEMA.T" + '', "Demo_Landing", "SRC", "T", plain);
		try
		{
			LandingFlow.mapping(controlCharTable);
			throw new AssertionError("a source object containing a control character must be refused");
		}
		catch (final IllegalArgumentException ex)
		{
			LandingFlowTest.check("refusal names the source object", ex.getMessage().contains("Source object"));
			LandingFlowTest.check("refusal names the reason", ex.getMessage().contains("control character"));
		}

		final List<LandingFlow.Column> nulByte = new ArrayList<LandingFlow.Column>();
		nulByte.add(new LandingFlow.Column("A" + ' ' + "B", "field1", true));
		final LandingFlow.Table nulTable = new LandingFlow.Table("Snowflake", "snowflake", "SRC", "DB.SCHEMA.T", "Demo_Landing", "SRC", "T", nulByte);
		try
		{
			LandingFlow.mapping(nulTable);
			throw new AssertionError("a source column containing a NUL must be refused");
		}
		catch (final IllegalArgumentException ex)
		{
			LandingFlowTest.check("refusal names the column", ex.getMessage().contains("Source column"));
		}
	}

	private static void checkNoColumns()
	{
		final LandingFlow.Table table = new LandingFlow.Table("Snowflake", "snowflake", "SRC", "DB.SCHEMA.T", "Demo_Landing", "SRC", "T", new ArrayList<LandingFlow.Column>());
		try
		{
			LandingFlow.mapping(table);
			throw new AssertionError("a table with no column must be refused");
		}
		catch (final IllegalArgumentException ex)
		{
			LandingFlowTest.check("refusal names the table", ex.getMessage().contains("T"));
		}
	}

	/** Volume-guards lot (2026-09-28, task item 1): LandingFlow.max(long) is gone - connectors_sync now always gets LandingFlow.SYNC_MAX_ROWS, never sized from the catalog's own (possibly 0/stale) row count. */
	private static void checkSyncMaxRowsIsUnconditionallyLarge()
	{
		LandingFlowTest.check("SYNC_MAX_ROWS is the largest int - never a second, independent way to truncate a load", LandingFlow.SYNC_MAX_ROWS == Integer.MAX_VALUE);
	}

	private static void checkLoadId()
	{
		final Clock clock = Clock.fixed(Instant.parse("2026-09-20T14:32:10.500Z"), ZoneOffset.UTC);
		LandingFlowTest.check("load id format", "L20260920-143210-42".equals(LandingFlow.loadId("42", clock)));
	}

	/** receivedAt reads the clock's own zone, unlike loadId which forces UTC (coordinator fix #2). */
	private static void checkReceivedAt()
	{
		final Clock utc = Clock.fixed(Instant.parse("2026-09-20T14:32:10.500Z"), ZoneOffset.UTC);
		LandingFlowTest.check("receivedAt keeps seconds, no fraction, 'T' separator", "2026-09-20T14:32:10".equals(LandingFlow.receivedAt(utc)));

		// same instant, a clock in a different zone: receivedAt reads that zone's wall-clock time, not UTC -
		// this is exactly the behaviour the CURRENT_TIMESTAMP()-in-Snowflake-session-zone defect needed fixed.
		final Clock losAngeles = Clock.fixed(Instant.parse("2026-09-20T13:15:35Z"), ZoneId.of("America/Los_Angeles"));
		LandingFlowTest.check("receivedAt reads the clock's own zone: " + LandingFlow.receivedAt(losAngeles), "2026-09-20T06:15:35".equals(LandingFlow.receivedAt(losAngeles)));

		final Clock london = Clock.fixed(Instant.parse("2026-09-20T13:15:35Z"), ZoneId.of("Europe/London"));
		LandingFlowTest.check("a different zone gives a different local time for the same instant: " + LandingFlow.receivedAt(london), "2026-09-20T14:15:35".equals(LandingFlow.receivedAt(london)));
	}

	/** Coordinator DECISION: single key, composite key, no key, and the hostile-name case (above) still passing. */
	private static void checkSourceIdKeyFallback()
	{
		// single key column
		final List<LandingFlow.Column> single = new ArrayList<LandingFlow.Column>();
		single.add(new LandingFlow.Column("ID", "id", true));
		single.add(new LandingFlow.Column("NAME", "name", false));
		final String singleSql = LandingFlow.mapping(new LandingFlow.Table("Snowflake", "snowflake", "SRC", "DB.SCHEMA.T", "Demo_Landing", "SRC", "T", single))
			.path("inputs").path("sql").asText();
		LandingFlowTest.check("single key: CASE WHEN <the one column> IS NULL: " + singleSql,
			singleSql.contains("CASE WHEN TO_VARCHAR(s.\"ID\") IS NULL THEN '#' || MD5(")
				&& singleSql.contains(") ELSE COALESCE(TO_VARCHAR(s.\"ID\"),'') END as SOURCE_ID"));

		// composite key: two columns, AND'ed in the CASE condition, both COALESCEd and '|'-joined in the ELSE
		final List<LandingFlow.Column> composite = new ArrayList<LandingFlow.Column>();
		composite.add(new LandingFlow.Column("A_ID", "aId", true));
		composite.add(new LandingFlow.Column("B_ID", "bId", true));
		composite.add(new LandingFlow.Column("NAME", "name", false));
		final String compositeSql = LandingFlow.mapping(new LandingFlow.Table("Snowflake", "snowflake", "SRC", "DB.SCHEMA.T", "Demo_Landing", "SRC", "T", composite))
			.path("inputs").path("sql").asText();
		LandingFlowTest.check("composite key: every key column ANDed in the CASE condition (unescaped: the NULL check reads the raw column): " + compositeSql,
			compositeSql.contains("CASE WHEN TO_VARCHAR(s.\"A_ID\") IS NULL AND TO_VARCHAR(s.\"B_ID\") IS NULL THEN '#' || MD5("));
		LandingFlowTest.check("composite key: the ELSE is still the plain '|'-joined concatenation (a partially NULL key is a real identity), but each part is now escaped: " + compositeSql,
			compositeSql.contains(
				"ELSE COALESCE(REPLACE(REPLACE(TO_VARCHAR(s.\"A_ID\"), CHR(92), CHR(92) || CHR(92)), CHR(124), CHR(92) || CHR(124)),'') || '|' || "
					+ "COALESCE(REPLACE(REPLACE(TO_VARCHAR(s.\"B_ID\"), CHR(92), CHR(92) || CHR(92)), CHR(124), CHR(92) || CHR(124)),'') END as SOURCE_ID"));
		// _rowHash (and the row-hash fallback of _sourceId) are NOT escaped: they are not looked up by
		// keyPredicate, only compared for equality/change, so the '|' collision this lot fixes does not apply there.
		LandingFlowTest.check("_rowHash itself stays the plain, unescaped concatenation: " + compositeSql,
			compositeSql.contains("MD5(COALESCE(TO_VARCHAR(s.\"A_ID\"),'') || '|' || COALESCE(TO_VARCHAR(s.\"B_ID\"),'') || '|' || COALESCE(TO_VARCHAR(s.\"NAME\"),'')) as ROW_HASH"));

		// a SINGLE-column key is deliberately left unescaped (no separator to protect against - see
		// LandingFlow.mapping's own comment): re-assert the single-key case above carries no REPLACE/CHR at all.
		LandingFlowTest.check("single key: no escaping at all (nothing to disambiguate against): " + singleSql, !singleSql.contains("REPLACE(") && !singleSql.contains("CHR("));

		// three-column composite: every part escaped, not just the first two
		final List<LandingFlow.Column> triple = new ArrayList<LandingFlow.Column>();
		triple.add(new LandingFlow.Column("A", "a", true));
		triple.add(new LandingFlow.Column("B", "b", true));
		triple.add(new LandingFlow.Column("C", "c", true));
		final String tripleSql = LandingFlow.mapping(new LandingFlow.Table("Snowflake", "snowflake", "SRC", "DB.SCHEMA.T", "Demo_Landing", "SRC", "T", triple))
			.path("inputs").path("sql").asText();
		final int replaceCount = LandingFlowTest.count(tripleSql, "REPLACE(REPLACE(");
		LandingFlowTest.check("three-column composite key: all three parts escaped (3 REPLACE(REPLACE( total): " + tripleSql + " (found " + replaceCount + ")", replaceCount == 3);

		// no key at all: unchanged, no CASE, no '#' (already covered by checkMappingShapeWithoutKey, asserted
		// again here next to the key-fallback tests so the three shapes are easy to compare)
		final List<LandingFlow.Column> noKey = new ArrayList<LandingFlow.Column>();
		noKey.add(new LandingFlow.Column("A", "a", false));
		final String noKeySql = LandingFlow.mapping(new LandingFlow.Table("Snowflake", "snowflake", "SRC", "DB.SCHEMA.T", "Demo_Landing", "SRC", "T", noKey))
			.path("inputs").path("sql").asText();
		LandingFlowTest.check("no key at all: still no CASE, no '#' prefix - the whole row hash, unprefixed: " + noKeySql,
			!noKeySql.contains("CASE WHEN") && !noKeySql.contains("'#'"));
	}

	/**
	 * Composite-key lot (2026-09-24): with a composite key, two source rows whose key columns carry a
	 * literal {@code '|'} or {@code '\'} must never produce the same {@code _sourceId} as two DIFFERENT rows
	 * whose columns happen to concatenate to the same text ("A|B" + "C" colliding with "A" + "B|C"). This is
	 * evaluated against SQL text (no live database), but the escaping function is unambiguous string
	 * manipulation: apply the exact REPLACE/CHR expressions the generated SQL contains, in the same order
	 * (backslash doubled first, then '|' escaped), and check the two rows differ after joining - a real SQL
	 * engine's REPLACE/CHR behave identically for ASCII text, so this is a faithful simulation.
	 */
	private static void checkCompositeKeyEscapingAvoidsCollision()
	{
		// row 1: A_ID = "A|B", B_ID = "C" -> naively "A|B" + "|" + "C" = "A|B|C"
		// row 2: A_ID = "A", B_ID = "B|C" -> naively "A" + "|" + "B|C" = "A|B|C" (SAME as row 1, unescaped)
		LandingFlowTest.check("unescaped concatenation really would collide (sanity check on the test itself)",
			LandingFlowTest.naiveJoin("A|B", "C").equals(LandingFlowTest.naiveJoin("A", "B|C")));
		LandingFlowTest.check("escaped concatenation no longer collides",
			!LandingFlowTest.escapedJoin("A|B", "C").equals(LandingFlowTest.escapedJoin("A", "B|C")));

		// a value containing a literal backslash, escaped correctly (backslash doubled, independent of any '|')
		LandingFlowTest.check("a lone backslash round-trips through the escaping unambiguously",
			LandingFlowTest.escapeKeyPart("A\\B").equals("A\\\\B"));
		// a value containing both: backslash doubled FIRST, so the '|' escape's own backslash is not re-escaped
		LandingFlowTest.check("backslash-then-pipe: the pipe's escaping backslash is not itself doubled",
			LandingFlowTest.escapeKeyPart("A\\|B").equals("A\\\\\\|B"));
	}

	/** Mirrors {@code dialect.escapeKeyPart}'s Java-level semantics (backslash doubled, then '|' escaped) - what the generated SQL's REPLACE(REPLACE(...)) computes at runtime. */
	private static String escapeKeyPart(final String pValue)
	{
		return pValue.replace("\\", "\\\\").replace("|", "\\|");
	}

	private static String naiveJoin(final String pA, final String pB)
	{
		final String a = pA == null ? "" : pA;
		final String b = pB == null ? "" : pB;
		return a + "|" + b;
	}

	private static String escapedJoin(final String pA, final String pB)
	{
		return LandingFlowTest.escapeKeyPart(pA == null ? "" : pA) + "|" + LandingFlowTest.escapeKeyPart(pB == null ? "" : pB);
	}

	private static int count(final String pText, final String pNeedle)
	{
		int count = 0;
		int from = 0;
		int index;
		while ((index = pText.indexOf(pNeedle, from)) >= 0)
		{
			count++;
			from = index + pNeedle.length();
		}
		return count;
	}

	/** Pure decision logic LandingFlows.load hands off to LandingFlow.loadOutcome - the seam asked for to unit-test defect 1's fix apart from the EBX write. */
	private static void checkLoadOutcome()
	{
		final LandingFlow.LoadOutcome ok = LandingFlow.loadOutcome(LandingFlowTest.sync("OK", 55, 55, 0));
		LandingFlowTest.check("OK: ok=true, rows=count", ok.ok && ok.rows == 55 && "Loaded 55 of 55 row(s).".equals(ok.message));

		final LandingFlow.LoadOutcome noData = LandingFlow.loadOutcome(LandingFlowTest.sync("NO_DATA", 0, 0, 0));
		LandingFlowTest.check("NO_DATA: still ok=true (a legitimately empty source is not a failure): " + noData.message, noData.ok && noData.rows == 0
			&& noData.message.contains("no data returned"));

		final ObjectNode partial = LandingFlowTest.sync("PARTIAL", 10, 8, 2);
		partial.putArray("results").addObject().put("status", "ERROR").put("message", "row 4: constraint violated");
		final LandingFlow.LoadOutcome partialOutcome = LandingFlow.loadOutcome(partial);
		LandingFlowTest.check("PARTIAL: still ok=true, but the message says how many and why: " + partialOutcome.message,
			partialOutcome.ok && partialOutcome.rows == 10 && partialOutcome.message.contains("2 error(s)") && partialOutcome.message.contains("constraint violated"));

		final ObjectNode error = LandingFlowTest.sync("ERROR", 5, 0, 5);
		error.putArray("results").addObject().put("status", "ERROR").putArray("errors").add("connection refused");
		final LandingFlow.LoadOutcome errorOutcome = LandingFlow.loadOutcome(error);
		LandingFlowTest.check("ERROR: ok=false, message names the reason: " + errorOutcome.message,
			!errorOutcome.ok && errorOutcome.rows == 5 && errorOutcome.message.contains("connection refused"));

		final LandingFlow.LoadOutcome noResult = LandingFlow.loadOutcome(null);
		LandingFlowTest.check("a null sync answer does not throw: treated as ok=true, 0 rows", noResult.ok && noResult.rows == 0);
	}

	/** Volume-guards lot (2026-09-28, task item 1): truncated=true fails the load even though the connector's own status is OK. */
	private static void checkLoadOutcomeTruncatedFieldFailsRegardlessOfStatus()
	{
		final ObjectNode truncated = LandingFlowTest.sync("OK", 10000, 10000, 0);
		truncated.put("truncated", true);
		final LandingFlow.LoadOutcome outcome = LandingFlow.loadOutcome(truncated, 50000);
		LandingFlowTest.check("truncated=true fails the load even though status is OK: " + outcome.message,
			!outcome.ok && outcome.message.contains("FAILED") && outcome.message.toLowerCase(java.util.Locale.ROOT).contains("truncated"));
	}

	/** Volume-guards lot (2026-09-28, task item 1): available > count fails the load even with no truncated field at all. */
	private static void checkLoadOutcomeAvailableExceedsCountFails()
	{
		final ObjectNode partial = LandingFlowTest.sync("OK", 100, 100, 0);
		partial.put("available", 500);
		final LandingFlow.LoadOutcome outcome = LandingFlow.loadOutcome(partial, 0);
		LandingFlowTest.check("available > count fails: " + outcome.message, !outcome.ok && outcome.message.contains("FAILED") && outcome.message.contains("500"));
	}

	/**
	 * Volume-guards lot (2026-09-28, task item 1): no truncated/available field at all (an older Connectors
	 * build) falls back to comparing count against the catalog's own last-known row count - below the 95%
	 * tolerance fails, at or above it only warns.
	 */
	private static void checkLoadOutcomeShortfallAgainstCatalogWarnsOrFails()
	{
		final LandingFlow.LoadOutcome bigShortfall = LandingFlow.loadOutcome(LandingFlowTest.sync("OK", 80, 80, 0), 100);
		LandingFlowTest.check("80 of 100 (80%, below the 95% tolerance) fails: " + bigShortfall.message, !bigShortfall.ok && bigShortfall.message.contains("FAILED"));

		final LandingFlow.LoadOutcome smallShortfall = LandingFlow.loadOutcome(LandingFlowTest.sync("OK", 96, 96, 0), 100);
		LandingFlowTest.check("96 of 100 (96%, within tolerance - ordinary churn) only warns, still ok=true: " + smallShortfall.message,
			smallShortfall.ok && smallShortfall.message.contains("WARNING"));

		final LandingFlow.LoadOutcome exact = LandingFlow.loadOutcome(LandingFlowTest.sync("OK", 100, 100, 0), 100);
		LandingFlowTest.check("count == sourceRowCount: no warning at all: " + exact.message, exact.ok && !exact.message.contains("WARNING") && !exact.message.contains("FAILED"));

		final LandingFlow.LoadOutcome more = LandingFlow.loadOutcome(LandingFlowTest.sync("OK", 120, 120, 0), 100);
		LandingFlowTest.check("count > sourceRowCount (the source grew): no warning - never a guess in the other direction", more.ok && !more.message.contains("WARNING"));
	}

	/** A source row count of 0 (never profiled) skips the catalog comparison entirely - never guessed, never a false WARN/FAILED. */
	private static void checkLoadOutcomeNoSourceRowCountSkipsTheComparison()
	{
		final LandingFlow.LoadOutcome outcome = LandingFlow.loadOutcome(LandingFlowTest.sync("OK", 3, 3, 0), 0);
		LandingFlowTest.check("sourceRowCount 0: no comparison attempted, plain OK message: " + outcome.message,
			outcome.ok && "Loaded 3 of 3 row(s).".equals(outcome.message));
		// the 1-arg overload (the catalog-scan path, ebx.SourceOnboarder#runScan) behaves the same way (sourceRowCount 0 implicitly)
		final LandingFlow.LoadOutcome oneArg = LandingFlow.loadOutcome(LandingFlowTest.sync("OK", 3, 3, 0));
		LandingFlowTest.check("1-arg overload unchanged for a catalog scan: " + oneArg.message, oneArg.ok && "Loaded 3 of 3 row(s).".equals(oneArg.message));
	}

	/**
	 * A table whose connector has no SQL dialect but exposes a feed-style {@code read} operation
	 * ({@code Table#fileFeed = true}): {@code operation: "read"}, {@code inputs.path} instead of {@code inputs.sql},
	 * a direct (non-aliased) {@code sourcePath} per column, and the technical fields built with
	 * {@code runid}/{@code now}/{@code concat}/{@code hash} instead of SQL - see LandingFlow.feedMapping's Javadoc.
	 */
	private static void checkFeedMappingWithKey()
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("customerId", "customerId", true));
		columns.add(new LandingFlow.Column("name", "name", false));
		final LandingFlow.Table table = new LandingFlow.Table("SampleFiles", "sample-file", "FILE_SRC", "sample.csv", "Demo_Landing", "FILE_SRC_LANDING", "Sample",
			columns, true);

		final JsonNode mapping = LandingFlow.mapping(table);
		LandingFlowTest.check("operation is read, not query", "read".equals(mapping.path("operation").asText()));
		LandingFlowTest.check("direction", "INBOUND".equals(mapping.path("direction").asText()));
		LandingFlowTest.check("label", "Landing: FILE_SRC Sample".equals(mapping.path("label").asText()));
		LandingFlowTest.check("tablePath", "/root/Sample".equals(mapping.path("tablePath").asText()));
		// bug fixed live 2026-09-24: a feed row has no SOURCE_ID field to resolve '${SOURCE_ID}' from (unlike the
		// SQL path, where it is a real result column) - the predicate must read the RAW key column(s) instead,
		// joined the same bare way the concat(...) rule builds _sourceId itself, so a re-run's lookup actually finds
		// the row it already created instead of colliding on the primary key trying to insert it again.
		LandingFlowTest.check("keyPredicate reads the raw key column, not a field a feed row never has",
			"./_sourceId='${customerId}'".equals(mapping.path("keyPredicate").asText()));
		LandingFlowTest.check("no sql input", !mapping.path("inputs").has("sql"));
		LandingFlowTest.check("path input is the source object, passed through verbatim", "sample.csv".equals(mapping.path("inputs").path("path").asText()));
		// volume-operations lot 8a (2026-09-28, task 6)
		LandingFlowTest.check("provenance is off for a landing write", !mapping.path("provenance").asBoolean(true));
		LandingFlowTest.check("unchangedIfEqual points at the row hash", "./_rowHash".equals(mapping.path("unchangedIfEqual").asText()));

		final JsonNode rules = mapping.path("rules");
		// 2 columns + _sourceSystem + _loadId + _receivedAt + _rowHash + _sourceId + _status = 8
		LandingFlowTest.check("one rule per column plus the six technical rules: " + rules.size(), rules.size() == 8);
		LandingFlowTest.check("column rule's sourcePath is the source column verbatim, no aliasing",
			"customerId".equals(rules.get(0).path("sourcePath").asText()) && "./customerId".equals(rules.get(0).path("targetPath").asText()));
		LandingFlowTest.check("second column rule",
			"name".equals(rules.get(1).path("sourcePath").asText()) && "./name".equals(rules.get(1).path("targetPath").asText()));

		JsonNode loadIdRule = null;
		JsonNode receivedAtRule = null;
		JsonNode rowHashRule = null;
		JsonNode sourceIdRule = null;
		JsonNode sourceSystemRule = null;
		JsonNode statusRule = null;
		for (final JsonNode rule : rules)
		{
			final String targetPath = rule.path("targetPath").asText();
			if ("./_loadId".equals(targetPath))
			{
				loadIdRule = rule;
			}
			if ("./_receivedAt".equals(targetPath))
			{
				receivedAtRule = rule;
			}
			if ("./_rowHash".equals(targetPath))
			{
				rowHashRule = rule;
			}
			if ("./_sourceId".equals(targetPath))
			{
				sourceIdRule = rule;
			}
			if ("./_sourceSystem".equals(targetPath))
			{
				sourceSystemRule = rule;
			}
			if ("./_status".equals(targetPath))
			{
				statusRule = rule;
			}
		}
		LandingFlowTest.check("_loadId is runid(), not a client-minted value", loadIdRule != null && "runid".equals(loadIdRule.path("transformation").asText()));
		LandingFlowTest.check("_receivedAt is now(), not a client-minted value", receivedAtRule != null && "now".equals(receivedAtRule.path("transformation").asText()));
		LandingFlowTest.check("_rowHash concatenates every column in position order, then hashes: " + rowHashRule,
			rowHashRule != null && "concat(customerId,name)|hash".equals(rowHashRule.path("transformation").asText()));
		LandingFlowTest.check("with a declared key, _sourceId is the plain concatenation of the key columns, not hashed: " + sourceIdRule,
			sourceIdRule != null && "concat(customerId)".equals(sourceIdRule.path("transformation").asText()));
		LandingFlowTest.check("_sourceSystem is a constant rule with an empty source",
			sourceSystemRule != null && "".equals(sourceSystemRule.path("sourcePath").asText()) && "constant(FILE_SRC)".equals(sourceSystemRule.path("transformation").asText()));
		// 2026-09-23, incremental-transfer lot: ALWAYS, not IF_EMPTY - a reload always goes back to NEW, see LandingFlow's own Javadoc
		LandingFlowTest.check("_status defaults to NEW, ALWAYS so a reload always goes back to NEW",
			statusRule != null && "constant(NEW)".equals(statusRule.path("transformation").asText()) && "ALWAYS".equals(statusRule.path("policy").asText()));
	}

	/**
	 * Without any declared source key, the feed path's {@code _sourceId} falls back to the same
	 * {@code concat(...)|hash} transformation as {@code _rowHash} - matching the SQL path's own no-key fallback
	 * to the row hash (unlike the WITH-a-key case, this fallback is unconditional: see LandingFlow.feedMapping's
	 * Javadoc on the deliberately missing "every key column NULL" fallback).
	 */
	private static void checkFeedMappingWithoutKey()
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("a", "a", false));
		columns.add(new LandingFlow.Column("b", "b", false));
		final LandingFlow.Table table = new LandingFlow.Table("SampleFiles", "sample-file", "FILE_SRC", "other.csv", "Demo_Landing", "FILE_SRC_LANDING", "Other", columns,
			true);
		final JsonNode mapping = LandingFlow.mapping(table);

		JsonNode sourceIdRule = null;
		JsonNode rowHashRule = null;
		for (final JsonNode rule : mapping.path("rules"))
		{
			final String targetPath = rule.path("targetPath").asText();
			if ("./_sourceId".equals(targetPath))
			{
				sourceIdRule = rule;
			}
			if ("./_rowHash".equals(targetPath))
			{
				rowHashRule = rule;
			}
		}
		LandingFlowTest.check("without any source key, _sourceId is the same concat(...)|hash transformation as _rowHash",
			sourceIdRule != null && rowHashRule != null && "concat(a,b)|hash".equals(sourceIdRule.path("transformation").asText())
				&& sourceIdRule.path("transformation").asText().equals(rowHashRule.path("transformation").asText()));
		// no key at all: no raw column can stand in for a hash-based _sourceId (a predicate template only
		// substitutes raw item fields, never a rule's computed hash) - the placeholder stays the same
		// un-lookupable text it always was; this is a pre-existing, separate gap, not something this fix claims
		// to solve (see LandingFlow.feedKeyPredicate's Javadoc).
		LandingFlowTest.check("no key: keyPredicate falls back to the un-lookupable SOURCE_ID placeholder",
			"./_sourceId='${SOURCE_ID}'".equals(mapping.path("keyPredicate").asText()));
	}

	/** A composite feed key: the predicate joins each raw key column with a bare '|', matching concat(...)'s own join exactly. */
	private static void checkFeedMappingCompositeKeyPredicate()
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("orgId", "orgId", true));
		columns.add(new LandingFlow.Column("customerId", "customerId", true));
		columns.add(new LandingFlow.Column("name", "name", false));
		final LandingFlow.Table table = new LandingFlow.Table("SampleFiles", "sample-file", "FILE_SRC", "composite.csv", "Demo_Landing", "FILE_SRC_LANDING", "Composite",
			columns, true);
		final JsonNode mapping = LandingFlow.mapping(table);
		LandingFlowTest.check("composite feed key predicate joins the raw columns with a bare '|', same as concat(...)",
			"./_sourceId='${orgId}|${customerId}'".equals(mapping.path("keyPredicate").asText()));
		JsonNode sourceIdRule = null;
		for (final JsonNode rule : mapping.path("rules"))
		{
			if ("./_sourceId".equals(rule.path("targetPath").asText()))
			{
				sourceIdRule = rule;
			}
		}
		LandingFlowTest.check("sanity: the predicate's column order matches the _sourceId rule's own concat(...) order",
			sourceIdRule != null && "concat(orgId,customerId)".equals(sourceIdRule.path("transformation").asText()));
	}

	private static ObjectNode sync(final String pStatus, final long pCount, final int pOk, final int pErrors)
	{
		final ObjectNode node = LandingFlowTest.MAPPER.createObjectNode();
		node.put("status", pStatus);
		node.put("count", pCount);
		node.put("ok", pOk);
		node.put("errors", pErrors);
		return node;
	}

	/** Read-recipe lot (2026-09-24): the recipe's own connector/operation/inputs/itemsPath drive the mapping, and the technical rules match the feed path exactly (verified against the live Salesforce example, mapping 263 - see ReadRecipe's own Javadoc). */
	private static void checkRecipeMappingShape()
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("Id", "Id", true));
		columns.add(new LandingFlow.Column("Name", "Name", false));
		columns.add(new LandingFlow.Column("BillingCountry", "BillingCountry", false));
		final ReadRecipe recipe = ReadRecipe
			.parse("{\"connector\":\"Salesforce\",\"operation\":\"query\",\"inputs\":{\"soql\":\"SELECT Id, Name, BillingCountry FROM Account\"},\"itemsPath\":\"records\"}");
		final LandingFlow.Table table = new LandingFlow.Table("Salesforce", "salesforce", "SFDC", "Account", "Vague1_Landing", "SFDC", "Account", columns, false, recipe);

		final JsonNode mapping = LandingFlow.mapping(table);
		LandingFlowTest.check("connector from recipe", "Salesforce".equals(mapping.path("connector").asText()));
		LandingFlowTest.check("operation from recipe", "query".equals(mapping.path("operation").asText()));
		LandingFlowTest.check("direction", "INBOUND".equals(mapping.path("direction").asText()));
		LandingFlowTest.check("label", "Landing: SFDC Account".equals(mapping.path("label").asText()));
		LandingFlowTest.check("itemsPath from recipe", "records".equals(mapping.path("itemsPath").asText()));
		LandingFlowTest.check("createIfMissing", mapping.path("createIfMissing").asBoolean());
		LandingFlowTest.check("inputs come from the recipe verbatim, not generated SQL",
			"SELECT Id, Name, BillingCountry FROM Account".equals(mapping.path("inputs").path("soql").asText()));
		LandingFlowTest.check("keyPredicate uses the raw column path, feedKeyPredicate style", "./_sourceId='${Id}'".equals(mapping.path("keyPredicate").asText()));
		// volume-operations lot 8a (2026-09-28, task 6)
		LandingFlowTest.check("provenance is off for a landing write", !mapping.path("provenance").asBoolean(true));
		LandingFlowTest.check("unchangedIfEqual points at the row hash", "./_rowHash".equals(mapping.path("unchangedIfEqual").asText()));

		final JsonNode rules = mapping.path("rules");
		// 3 columns + _sourceSystem + _loadId + _receivedAt + _rowHash + _sourceId + _status = 9
		LandingFlowTest.check("one rule per column plus the six technical rules: " + rules.size(), rules.size() == 9);
		LandingFlowTest.check("column rule uses the source column verbatim as sourcePath",
			"Id".equals(rules.get(0).path("sourcePath").asText()) && "./Id".equals(rules.get(0).path("targetPath").asText()));

		JsonNode rowHashRule = null;
		JsonNode sourceIdRule = null;
		JsonNode loadIdRule = null;
		JsonNode receivedAtRule = null;
		for (final JsonNode rule : rules)
		{
			if ("./_rowHash".equals(rule.path("targetPath").asText()))
			{
				rowHashRule = rule;
			}
			if ("./_sourceId".equals(rule.path("targetPath").asText()))
			{
				sourceIdRule = rule;
			}
			if ("./_loadId".equals(rule.path("targetPath").asText()))
			{
				loadIdRule = rule;
			}
			if ("./_receivedAt".equals(rule.path("targetPath").asText()))
			{
				receivedAtRule = rule;
			}
		}
		LandingFlowTest.check("_rowHash hashes every column via concat/hash, same as the feed path",
			rowHashRule != null && "concat(Id,Name,BillingCountry)|hash".equals(rowHashRule.path("transformation").asText()));
		LandingFlowTest.check("_sourceId concatenates the declared key column only", sourceIdRule != null && "concat(Id)".equals(sourceIdRule.path("transformation").asText()));
		LandingFlowTest.check("_loadId is runid, not the SQL-path's run-time input placeholder", loadIdRule != null && "runid".equals(loadIdRule.path("transformation").asText()));
		LandingFlowTest.check("_receivedAt is now()", receivedAtRule != null && "now".equals(receivedAtRule.path("transformation").asText()));
	}

	/** Without a declared key, _sourceId falls back to hashing every column - same convention as the feed path, no CASE/'#' fallback (ReadRecipe's own Javadoc explains why: no conditional transformation exists). */
	private static void checkRecipeMappingWithoutKeyHashesEverything()
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("A", "A", false));
		columns.add(new LandingFlow.Column("B", "B", false));
		final ReadRecipe recipe = ReadRecipe.parse("{\"connector\":\"HubSpot\",\"operation\":\"list\",\"inputs\":{}}");
		final LandingFlow.Table table = new LandingFlow.Table("HubSpot", "hubspot", "HS", "contacts", "Demo_Landing", "HS", "Contacts", columns, false, recipe);
		final JsonNode mapping = LandingFlow.mapping(table);
		LandingFlowTest.check("no declared key: the old, un-lookupable placeholder keyPredicate (same known gap as the feed path)",
			"./_sourceId='${SOURCE_ID}'".equals(mapping.path("keyPredicate").asText()));
		JsonNode sourceIdRule = null;
		for (final JsonNode rule : mapping.path("rules"))
		{
			if ("./_sourceId".equals(rule.path("targetPath").asText()))
			{
				sourceIdRule = rule;
			}
		}
		LandingFlowTest.check("_sourceId hashes every column when no key is declared",
			sourceIdRule != null && "concat(A,B)|hash".equals(sourceIdRule.path("transformation").asText()));
	}

	/** A recipe bypasses connectorType/fileFeed entirely - dialectFor would refuse "not-a-real-dialect", proving the recipe path never reaches it. */
	private static void checkRecipeMappingIgnoresConnectorTypeAndFileFeed()
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("x", "x", true));
		final ReadRecipe recipe = ReadRecipe.parse("{\"connector\":\"Anything\",\"operation\":\"read\",\"inputs\":{}}");
		final LandingFlow.Table table = new LandingFlow.Table("Anything", "not-a-real-dialect", "SRC", "obj", "Demo_Landing", "SRC", "T", columns, false, recipe);
		// would throw IllegalArgumentException ("No landing SQL generator...") if the legacy dialectFor path were reached
		final JsonNode mapping = LandingFlow.mapping(table);
		LandingFlowTest.check("mapping built from the recipe, connectorType ignored", "Anything".equals(mapping.path("connector").asText()));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
