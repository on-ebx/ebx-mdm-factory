package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like every other test in this module: run the main, a failure throws. Volume wave 2, lot V2-E (2026-10-03): the mapping a
 * landing table gets with and without a change column ({@link LandingFlow#mapping}), the load outcome of an incremental run
 * ({@link LandingFlow#loadOutcome(JsonNode, long, boolean)}, {@link RunStateOutcome#outcome(JsonNode, long, boolean)},
 * {@link WatermarkOutcome}, {@link LoadOutcomeRule}) and the rules that decide a full refresh ({@link ChangeColumnRule}).
 */
public final class LandingFlowWatermarkTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		LandingFlowWatermarkTest.checkMappingWithoutChangeColumnIsExactlyTheFullOne();
		LandingFlowWatermarkTest.checkMappingWithChangeColumnH2();
		LandingFlowWatermarkTest.checkMappingWithChangeColumnPostgreSql();
		LandingFlowWatermarkTest.checkMappingWithChangeColumnSnowflakeWritesTheValueAsText();
		LandingFlowWatermarkTest.checkSnowflakeRefusesATextChangeColumn();
		LandingFlowWatermarkTest.checkNumberChangeColumn();
		LandingFlowWatermarkTest.checkChangeColumnMustBeAColumnOfTheTable();
		LandingFlowWatermarkTest.checkChangeColumnNeedsAValidKind();
		LandingFlowWatermarkTest.checkHostileChangeColumnIsQuoted();
		LandingFlowWatermarkTest.checkFileAndRecipeFlowsStayFull();
		LandingFlowWatermarkTest.checkSqlRecipeIsWrappedWithTheWatermark();
		LandingFlowWatermarkTest.checkSqlRecipeOnSnowflake();
		LandingFlowWatermarkTest.checkNonSqlRecipeStaysFull();
		LandingFlowWatermarkTest.checkQuietIncrementalRunIsASuccess();
		LandingFlowWatermarkTest.checkFirstIncrementalRunIsJudgedOnTheCatalogCount();
		LandingFlowWatermarkTest.checkFullRefreshIsJudgedOnTheCatalogCount();
		LandingFlowWatermarkTest.checkNoDataWithErrorsIsNotClean();
		LandingFlowWatermarkTest.checkTruncatedStillFailsAnIncrementalRun();
		LandingFlowWatermarkTest.checkAvailableCountsWhatTheWatermarkSkipped();
		LandingFlowWatermarkTest.checkSkippedRowsAreSaidInTheMessage();
		LandingFlowWatermarkTest.checkPolledQuietRun();
		LandingFlowWatermarkTest.checkPolledDoneWithErrorsAndNoRowIsNotClean();
		LandingFlowWatermarkTest.checkPolledFullRefreshAndFirstRun();
		LandingFlowWatermarkTest.checkPolledWithoutWatermarkIsUnchanged();
		LandingFlowWatermarkTest.checkWatermarkOutcomeReading();
		LandingFlowWatermarkTest.checkFullRefreshDecision();
		LandingFlowWatermarkTest.checkColumnToRecord();
		System.out.println("LandingFlowWatermarkTest OK");
	}

	private static List<LandingFlow.Column> columns()
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("CONTACT_ID", "CONTACT_ID", true));
		columns.add(new LandingFlow.Column("NAME", "NAME", false));
		columns.add(new LandingFlow.Column("LAST_MODIFIED", "LAST_MODIFIED", false));
		return columns;
	}

	private static LandingFlow.Table table(final String pType)
	{
		return new LandingFlow.Table("Conn", pType, "CRM", "PUBLIC.CONTACT", "Landing_CRM", "CRM", "CONTACT", LandingFlowWatermarkTest.columns());
	}

	private static ObjectNode json(final String pText) throws Exception
	{
		return (ObjectNode) LandingFlowWatermarkTest.MAPPER.readTree(pText);
	}

	/** A table with no change column generates exactly what it did before the lot: no WHERE, no CHANGE_VALUE, none of the three fields. */
	private static void checkMappingWithoutChangeColumnIsExactlyTheFullOne()
	{
		for (final String type : new String[] { "h2", "postgresql", "snowflake" })
		{
			final ObjectNode mapping = LandingFlow.mapping(LandingFlowWatermarkTest.table(type));
			final String sql = mapping.path("inputs").path("sql").asText();
			check(type + ": no WHERE", !sql.contains(" where ") && !sql.contains("watermark"));
			check(type + ": no CHANGE_VALUE column", !sql.contains("CHANGE_VALUE"));
			check(type + ": no loadMode", !mapping.has("loadMode"));
			check(type + ": no checkpointStrategy", !mapping.has("checkpointStrategy"));
			check(type + ": no checkpointValuePath", !mapping.has("checkpointValuePath"));
			check(type + ": unchangedIfEqual and provenance kept", "./_rowHash".equals(mapping.path("unchangedIfEqual").asText()) && !mapping.path("provenance").asBoolean(true));
		}
		final LandingFlow.Table blank = LandingFlowWatermarkTest.table("h2").withChangeColumn("  ", "TIMESTAMP");
		check("a blank change column leaves the table as it was", !LandingFlow.mapping(blank).has("loadMode"));
		check("null too", !LandingFlow.mapping(LandingFlowWatermarkTest.table("h2").withChangeColumn(null, null)).has("loadMode"));
	}

	private static void checkMappingWithChangeColumnH2()
	{
		final ObjectNode mapping = LandingFlow.mapping(LandingFlowWatermarkTest.table("h2").withChangeColumn("LAST_MODIFIED", "TIMESTAMP"));
		final String sql = mapping.path("inputs").path("sql").asText();
		check("loadMode INCREMENTAL: " + mapping, "INCREMENTAL".equals(mapping.path("loadMode").asText()));
		check("checkpointStrategy is the kind", "TIMESTAMP".equals(mapping.path("checkpointStrategy").asText()));
		check("checkpointValuePath is the typed alias, not the TO_VARCHAR text of C3", "CHANGE_VALUE".equals(mapping.path("checkpointValuePath").asText()));
		check("checkpointBoundary left empty (INCLUSIVE comes with unchangedIfEqual)", !mapping.has("checkpointBoundary"));
		check("the change column is selected typed, after ROW_HASH", sql.contains(" as ROW_HASH, s.\"LAST_MODIFIED\" as CHANGE_VALUE from "));
		check("inclusive WHERE on the quoted column with the watermark placeholder: " + sql,
			sql.endsWith(" s where (${watermark.isSet} = 0 or s.\"LAST_MODIFIED\" >= ${watermark})"));
		check("no ORDER BY (the checkpoint only advances after a DONE run, order is not needed)", !sql.contains("order by"));
		check("provenance false and unchangedIfEqual kept", !mapping.path("provenance").asBoolean(true) && "./_rowHash".equals(mapping.path("unchangedIfEqual").asText()));
		check("no input is named watermark (reserved)", !mapping.path("inputs").has("watermark") && !mapping.path("inputs").has("watermarkType"));
		check("the rules are the ones of the full mapping (no rule for CHANGE_VALUE)", mapping.path("rules").size() == LandingFlow.mapping(LandingFlowWatermarkTest.table("h2")).path("rules").size());
		check("key and run-time placeholders untouched", sql.contains("'${param.loadId}' as LOAD_ID") && "./_sourceId='${SOURCE_ID}'".equals(mapping.path("keyPredicate").asText()));
	}

	private static void checkMappingWithChangeColumnPostgreSql()
	{
		final String sql = LandingFlow.mapping(LandingFlowWatermarkTest.table("postgresql").withChangeColumn("LAST_MODIFIED", "TIMESTAMP")).path("inputs").path("sql").asText();
		check("a JDBC connector binds: the bare placeholder", sql.endsWith("(${watermark.isSet} = 0 or s.\"LAST_MODIFIED\" >= ${watermark})"));
	}

	/** Snowflake's SQL API gets the value as text: a timestamp is a quoted literal, never an unquoted token. */
	private static void checkMappingWithChangeColumnSnowflakeWritesTheValueAsText()
	{
		final ObjectNode mapping = LandingFlow.mapping(LandingFlowWatermarkTest.table("snowflake").withChangeColumn("LAST_MODIFIED", "TIMESTAMP"));
		final String sql = mapping.path("inputs").path("sql").asText();
		check("quoted literal for a Snowflake timestamp: " + sql, sql.endsWith("(${watermark.isSet} = 0 or s.\"LAST_MODIFIED\" >= '${watermark}')"));
		check("same three fields", "INCREMENTAL".equals(mapping.path("loadMode").asText()) && "TIMESTAMP".equals(mapping.path("checkpointStrategy").asText())
			&& "CHANGE_VALUE".equals(mapping.path("checkpointValuePath").asText()));
		check("Snowflake does not bind", !new SnowflakeDialect().bindsWatermark() && new H2Dialect().bindsWatermark() && new PostgreSqlDialect().bindsWatermark());
	}

	/** A text watermark is source data: written into Snowflake's statement as text it would run as SQL, so it is refused there (and fine where it is bound). */
	private static void checkSnowflakeRefusesATextChangeColumn()
	{
		try
		{
			LandingFlow.mapping(LandingFlowWatermarkTest.table("snowflake").withChangeColumn("NAME", "ORDERED_STRING"));
			throw new AssertionError("ORDERED_STRING on Snowflake must be refused");
		}
		catch (final IllegalArgumentException ex)
		{
			check("the refusal says why: " + ex.getMessage(), ex.getMessage().contains("ORDERED_STRING") && ex.getMessage().contains("Snowflake"));
		}
		final ObjectNode bound = LandingFlow.mapping(LandingFlowWatermarkTest.table("h2").withChangeColumn("NAME", "ORDERED_STRING"));
		check("bound by a JDBC connector, a text watermark is fine", "ORDERED_STRING".equals(bound.path("checkpointStrategy").asText()));
	}

	private static void checkNumberChangeColumn()
	{
		final ObjectNode snowflake = LandingFlow.mapping(LandingFlowWatermarkTest.table("snowflake").withChangeColumn("CONTACT_ID", "MONOTONIC_ID"));
		check("a number is written bare: " + snowflake.path("inputs").path("sql").asText(),
			snowflake.path("inputs").path("sql").asText().endsWith("s.\"CONTACT_ID\" >= ${watermark})") && "MONOTONIC_ID".equals(snowflake.path("checkpointStrategy").asText()));
	}

	private static void checkChangeColumnMustBeAColumnOfTheTable()
	{
		try
		{
			LandingFlow.mapping(LandingFlowWatermarkTest.table("h2").withChangeColumn("GONE", "TIMESTAMP"));
			throw new AssertionError("a column that is not in the table must be refused");
		}
		catch (final IllegalArgumentException ex)
		{
			check("names the column and the table: " + ex.getMessage(), ex.getMessage().contains("GONE") && ex.getMessage().contains("CONTACT"));
		}
	}

	private static void checkChangeColumnNeedsAValidKind()
	{
		for (final String kind : new String[] { "", "EPOCH", null })
		{
			try
			{
				LandingFlow.mapping(LandingFlowWatermarkTest.table("h2").withChangeColumn("LAST_MODIFIED", kind));
				throw new AssertionError("kind '" + kind + "' must be refused");
			}
			catch (final IllegalArgumentException ex)
			{
				check("names the column: " + ex.getMessage(), ex.getMessage().contains("LAST_MODIFIED"));
			}
		}
	}

	private static void checkHostileChangeColumnIsQuoted()
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("ID", "ID", true));
		columns.add(new LandingFlow.Column("TS\" or 1=1 --", "TS", false));
		final LandingFlow.Table table = new LandingFlow.Table("Conn", "h2", "CRM", "PUBLIC.T", "Landing_CRM", "CRM", "T", columns).withChangeColumn("TS\" or 1=1 --", "TIMESTAMP");
		final String sql = LandingFlow.mapping(table).path("inputs").path("sql").asText();
		check("the quote is doubled in the WHERE and in the select: " + sql, sql.contains("s.\"TS\"\" or 1=1 --\" >= ${watermark}"));
		final List<LandingFlow.Column> placeholder = new ArrayList<LandingFlow.Column>();
		placeholder.add(new LandingFlow.Column("A${x}", "A", true));
		try
		{
			LandingFlow.mapping(new LandingFlow.Table("Conn", "h2", "CRM", "PUBLIC.T", "L", "CRM", "T", placeholder).withChangeColumn("A${x}", "TIMESTAMP"));
			throw new AssertionError("a placeholder in the column name stays refused");
		}
		catch (final IllegalArgumentException ex)
		{
			check("refused like before", ex.getMessage().contains("${"));
		}
	}

	/** Only the generated SQL reads a watermark: a file feed and a recipe flow carry no loadMode even when the table has a change column. */
	private static void checkFileAndRecipeFlowsStayFull()
	{
		final LandingFlow.Table file = new LandingFlow.Table("Files", "sample-file", "PERS", "persons.json", "Landing_PERS", "PERS", "PERSONS", LandingFlowWatermarkTest.columns(), true)
			.withChangeColumn("LAST_MODIFIED", "TIMESTAMP");
		final ObjectNode mapping = LandingFlow.mapping(file);
		check("file feed: read operation, no watermark field", "read".equals(mapping.path("operation").asText()) && !mapping.has("loadMode") && !mapping.has("checkpointValuePath"));
	}

	private static final String RECIPE_SQL = "SELECT \"CONTACT_ID\", \"NAME\", \"LAST_MODIFIED\" FROM \"PUBLIC\".\"CONTACT\"";

	private static LandingFlow.Table recipeTable(final String pType, final String pOperation, final String pInputs)
	{
		final ReadRecipe recipe = ReadRecipe.parse("{\"connector\":\"Conn\",\"operation\":\"" + pOperation + "\",\"inputs\":" + pInputs + ",\"itemsPath\":\"rows\"}");
		return new LandingFlow.Table("Conn", pType, "CRM", "PUBLIC.CONTACT", "Landing_CRM", "CRM", "CONTACT", LandingFlowWatermarkTest.columns(), false, recipe);
	}

	private static String sqlInput(final String pSql)
	{
		return "{\"sql\":\"" + pSql.replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
	}

	/** The catalog's recipe SQL is wrapped as a sub-select, never parsed: the case of every JDBC source that went through the catalog (measured live 2026-10-03). */
	private static void checkSqlRecipeIsWrappedWithTheWatermark()
	{
		final LandingFlow.Table full = LandingFlowWatermarkTest.recipeTable("h2", "query", LandingFlowWatermarkTest.sqlInput(LandingFlowWatermarkTest.RECIPE_SQL));
		check("a SQL recipe can read a watermark", LandingFlow.changeColumnIgnoredReason(full) == null);
		final ObjectNode untouched = LandingFlow.mapping(full.withChangeColumn("LAST_MODIFIED", "TIMESTAMP").withoutChangeColumn());
		check("without a change column the recipe SQL is verbatim: " + untouched, LandingFlowWatermarkTest.RECIPE_SQL.equals(untouched.path("inputs").path("sql").asText()) && !untouched.has("loadMode"));
		final ObjectNode mapping = LandingFlow.mapping(full.withChangeColumn("LAST_MODIFIED", "TIMESTAMP"));
		final String sql = mapping.path("inputs").path("sql").asText();
		check("wrapped SQL: " + sql, ("select s.*, s.\"LAST_MODIFIED\" as CHANGE_VALUE from (" + LandingFlowWatermarkTest.RECIPE_SQL
			+ ") s where (${watermark.isSet} = 0 or s.\"LAST_MODIFIED\" >= ${watermark})").equals(sql));
		check("INCREMENTAL, kind, typed alias", "INCREMENTAL".equals(mapping.path("loadMode").asText()) && "TIMESTAMP".equals(mapping.path("checkpointStrategy").asText())
			&& "CHANGE_VALUE".equals(mapping.path("checkpointValuePath").asText()) && !mapping.has("checkpointBoundary"));
		check("recipe shape kept: connector, query, itemsPath rows, provenance false, unchangedIfEqual",
			"Conn".equals(mapping.path("connector").asText()) && "query".equals(mapping.path("operation").asText()) && "rows".equals(mapping.path("itemsPath").asText())
				&& !mapping.path("provenance").asBoolean(true) && "./_rowHash".equals(mapping.path("unchangedIfEqual").asText()));
		check("the rules are the recipe's own (none for CHANGE_VALUE)", mapping.path("rules").size() == LandingFlow.mapping(full).path("rules").size());
		check("key predicate untouched", LandingFlow.mapping(full).path("keyPredicate").asText().equals(mapping.path("keyPredicate").asText()));
		final String semicolon = LandingFlow.mapping(LandingFlowWatermarkTest.recipeTable("postgresql", "query", LandingFlowWatermarkTest.sqlInput("select * from t ;  \n"))
			.withChangeColumn("LAST_MODIFIED", "MONOTONIC_ID")).path("inputs").path("sql").asText();
		check("a trailing semicolon is dropped before wrapping: " + semicolon, semicolon.equals("select s.*, s.\"LAST_MODIFIED\" as CHANGE_VALUE from (select * from t) s where (${watermark.isSet} = 0 or s.\"LAST_MODIFIED\" >= ${watermark})"));
		boolean refused = false;
		try
		{
			LandingFlow.mapping(full.withChangeColumn("NOPE", "TIMESTAMP"));
		}
		catch (final IllegalArgumentException ex)
		{
			refused = ex.getMessage().contains("not a column");
		}
		check("a change column that is not a column of the table is refused for a recipe too", refused);
	}

	private static void checkSqlRecipeOnSnowflake()
	{
		final LandingFlow.Table table = LandingFlowWatermarkTest.recipeTable("snowflake", "query", LandingFlowWatermarkTest.sqlInput(LandingFlowWatermarkTest.RECIPE_SQL));
		final String sql = LandingFlow.mapping(table.withChangeColumn("LAST_MODIFIED", "TIMESTAMP")).path("inputs").path("sql").asText();
		check("Snowflake timestamp: quoted literal: " + sql, sql.endsWith(") s where (${watermark.isSet} = 0 or s.\"LAST_MODIFIED\" >= '${watermark}')"));
		final String number = LandingFlow.mapping(table.withChangeColumn("LAST_MODIFIED", "MONOTONIC_ID")).path("inputs").path("sql").asText();
		check("Snowflake number: bare: " + number, number.endsWith(">= ${watermark})"));
		boolean refused = false;
		try
		{
			LandingFlow.mapping(table.withChangeColumn("LAST_MODIFIED", "ORDERED_STRING"));
		}
		catch (final IllegalArgumentException ex)
		{
			refused = ex.getMessage().contains("ORDERED_STRING");
		}
		check("ORDERED_STRING refused on Snowflake for a recipe too", refused);
	}

	/** A file read, a REST call, or a query with no sql text, or a connector type with no dialect: FULL, and the reason says "not a SQL query" where it applies. */
	private static void checkNonSqlRecipeStaysFull()
	{
		final LandingFlow.Table file = LandingFlowWatermarkTest.recipeTable("sample-file", "read", "{\"path\":\"persons.json\"}").withChangeColumn("LAST_MODIFIED", "TIMESTAMP");
		final String reason = LandingFlow.changeColumnIgnoredReason(file);
		check("file recipe: reason says not a SQL query: " + reason, reason != null && reason.contains("not a SQL query"));
		final ObjectNode mapping = LandingFlow.mapping(file);
		check("file recipe: FULL, recipe inputs verbatim", !mapping.has("loadMode") && !mapping.has("checkpointStrategy") && !mapping.has("checkpointValuePath")
			&& "persons.json".equals(mapping.path("inputs").path("path").asText()) && "read".equals(mapping.path("operation").asText()));
		final LandingFlow.Table soql = LandingFlowWatermarkTest.recipeTable("salesforce", "query", "{\"soql\":\"select Id from Contact\"}").withChangeColumn("LAST_MODIFIED", "TIMESTAMP");
		check("a query without inputs.sql is not rewritten", LandingFlow.changeColumnIgnoredReason(soql).contains("not a SQL query") && !LandingFlow.mapping(soql).has("loadMode"));
		final LandingFlow.Table unknown = LandingFlowWatermarkTest.recipeTable("oracle", "query", LandingFlowWatermarkTest.sqlInput(LandingFlowWatermarkTest.RECIPE_SQL)).withChangeColumn("LAST_MODIFIED", "TIMESTAMP");
		check("a SQL recipe on a type with no dialect stays FULL, reason names the type: " + LandingFlow.changeColumnIgnoredReason(unknown),
			LandingFlow.changeColumnIgnoredReason(unknown).contains("'oracle'") && !LandingFlow.mapping(unknown).has("loadMode"));
		final LandingFlow.Table legacyFile = new LandingFlow.Table("Files", "sample-file", "PERS", "persons.json", "Landing_PERS", "PERS", "PERSONS", LandingFlowWatermarkTest.columns(), true);
		check("legacy file feed: not a SQL query", LandingFlow.changeColumnIgnoredReason(legacyFile).contains("not a SQL query"));
		check("legacy generated SQL: can", LandingFlow.changeColumnIgnoredReason(LandingFlowWatermarkTest.table("h2")) == null);
	}

	private static String sync(final String pStatus, final int pCount, final int pOk, final int pErrors, final String pExtra)
	{
		return "{\"status\":\"" + pStatus + "\",\"count\":" + pCount + ",\"ok\":" + pOk + ",\"errors\":" + pErrors + (pExtra.isEmpty() ? "" : "," + pExtra) + "}";
	}

	private static final String WM_FIELDS = "\"watermarkField\":\"CHANGE_VALUE\",\"watermarkBefore\":\"2026-09-30T08:41:07.5\",\"watermarkAfter\":\"2026-10-02T10:00\","
		+ "\"skippedByWatermark\":0,\"watermarkAdvanced\":true,\"fullRefresh\":false";

	/** 0 changed rows out of 500,000 is a success, not an incomplete read - and says so. */
	private static void checkQuietIncrementalRunIsASuccess() throws Exception
	{
		final String answer = LandingFlowWatermarkTest.sync("NO_DATA", 0, 0, 0, "\"watermarkField\":\"CHANGE_VALUE\",\"watermarkBefore\":\"2026-09-30T08:41:07.5\","
			+ "\"watermarkAfter\":\"2026-09-30T08:41:07.5\",\"skippedByWatermark\":0,\"watermarkAdvanced\":false,\"fullRefresh\":false");
		final LandingFlow.LoadOutcome outcome = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(answer), 500000L, false);
		check("a quiet night is OK: " + outcome.message, outcome.ok);
		check("zero rows read", outcome.rows == 0);
		check("it says nothing changed since the watermark: " + outcome.message, outcome.message.contains("no row changed at the source since the watermark")
			&& outcome.message.contains("changed since the watermark 2026-09-30T08:41:07.5"));
		check("no WARNING/FAILED in it", !outcome.message.contains("WARNING") && !outcome.message.contains("FAILED"));
		check("the watermark is on the outcome and filtered", outcome.watermark.incremental && outcome.watermark.filtered());
		// the same answer WITHOUT watermark fields, 0 of 500,000: still a failure (nothing explains it)
		final LandingFlow.LoadOutcome plain = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(LandingFlowWatermarkTest.sync("NO_DATA", 0, 0, 0, "")), 500000L, false);
		check("without a watermark the catalog comparison still fails 0 of 500,000: " + plain.message, !plain.ok && plain.message.contains("FAILED"));
		// a handful of changed rows is fine too
		final LandingFlow.LoadOutcome some = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(LandingFlowWatermarkTest.sync("OK", 12, 12, 0, LandingFlowWatermarkTest.WM_FIELDS)), 500000L, false);
		check("12 changed rows of 500,000: OK: " + some.message, some.ok && some.message.startsWith("Loaded 12 of 12 row(s)")
			&& some.message.contains("watermark now 2026-10-02T10:00"));
	}

	/** No stored checkpoint: the first incremental run reads everything, so a shortfall against the catalog still counts. */
	private static void checkFirstIncrementalRunIsJudgedOnTheCatalogCount() throws Exception
	{
		final String first = "\"watermarkField\":\"CHANGE_VALUE\",\"watermarkBefore\":null,\"watermarkAfter\":\"2026-10-02T10:00\",\"skippedByWatermark\":0,\"watermarkAdvanced\":true,\"fullRefresh\":false";
		final LandingFlow.LoadOutcome outcome = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(LandingFlowWatermarkTest.sync("OK", 40, 40, 0, first)), 100L, false);
		check("40 of 100 on a first run is FAILED: " + outcome.message, !outcome.ok && outcome.message.contains("FAILED"));
		check("not 'filtered'", !outcome.watermark.filtered());
		check("says it was the first incremental load: " + outcome.message, outcome.message.contains("first incremental load"));
	}

	private static void checkFullRefreshIsJudgedOnTheCatalogCount() throws Exception
	{
		final String answer = LandingFlowWatermarkTest.sync("OK", 40, 40, 0, LandingFlowWatermarkTest.WM_FIELDS.replace("\"fullRefresh\":false", "\"fullRefresh\":true"));
		final LandingFlow.LoadOutcome outcome = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(answer), 100L, false);
		check("a full refresh reading 40 of 100 is FAILED: " + outcome.message, !outcome.ok && outcome.message.contains("full refresh, watermark ignored"));
		// the Factory asked for it even though the answer did not say so
		final LandingFlow.LoadOutcome asked = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(LandingFlowWatermarkTest.sync("OK", 40, 40, 0, LandingFlowWatermarkTest.WM_FIELDS)), 100L, true);
		check("asked-for full refresh counts as one: " + asked.message, !asked.ok && asked.watermark.fullRefresh);
	}

	/** The engine can end a run NO_DATA with errors on the side: nothing written, something wrong - never a clean load. */
	private static void checkNoDataWithErrorsIsNotClean() throws Exception
	{
		final String answer = "{\"status\":\"NO_DATA\",\"count\":0,\"ok\":0,\"errors\":2,\"results\":[{\"status\":\"ERROR\",\"errors\":[\"ORA-00942: table or view does not exist\"]}]}";
		final LandingFlow.LoadOutcome outcome = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(answer), 0L, false);
		check("NO_DATA with errors fails: " + outcome.message, !outcome.ok);
		check("names the error: " + outcome.message, outcome.message.contains("ORA-00942") && outcome.message.contains("2 error(s)"));
		final LandingFlow.LoadOutcome incremental = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(
			LandingFlowWatermarkTest.sync("NO_DATA", 0, 0, 1, LandingFlowWatermarkTest.WM_FIELDS)), 0L, false);
		check("also under a watermark: " + incremental.message, !incremental.ok);
		// Connectors audit decision 4 (2026-10-06): a failed source read now ends ERROR, count 0, errors 1
		final String readFailed = "{\"status\":\"ERROR\",\"count\":0,\"ok\":0,\"errors\":1,\"results\":[{\"status\":\"ERROR\",\"message\":\"Connection refused\"}],\"errorSamples\":[{\"status\":\"ERROR\",\"message\":\"Connection refused\"}]}";
		final LandingFlow.LoadOutcome failedRead = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(readFailed), 0L, false);
		check("a failed source read fails, without '1 of 0 row(s)': " + failedRead.message,
			!failedRead.ok && failedRead.message.startsWith("Load failed: the source could not be read") && !failedRead.message.contains("of 0 row(s)"));
		final LandingFlow.LoadOutcome clean = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(LandingFlowWatermarkTest.sync("NO_DATA", 0, 0, 0, "")), 0L, false);
		check("NO_DATA without errors and no catalog count stays what it was: ok, 'no data returned by the source': " + clean.message,
			clean.ok && clean.message.contains("no data returned by the source"));
	}

	private static void checkTruncatedStillFailsAnIncrementalRun() throws Exception
	{
		final LandingFlow.LoadOutcome outcome = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(
			LandingFlowWatermarkTest.sync("OK", 10, 10, 0, LandingFlowWatermarkTest.WM_FIELDS + ",\"truncated\":true")), 500000L, false);
		check("truncated=true fails whatever the watermark: " + outcome.message, !outcome.ok && outcome.message.contains("truncated"));
	}

	/** Rows the watermark dropped were returned by the source: available counts them. */
	private static void checkAvailableCountsWhatTheWatermarkSkipped() throws Exception
	{
		final String skipped = "\"watermarkField\":\"CHANGE_VALUE\",\"watermarkBefore\":\"2026-09-30T08:41:07.5\",\"watermarkAfter\":\"2026-10-02T10:00\",\"skippedByWatermark\":90,"
			+ "\"watermarkAdvanced\":true,\"fullRefresh\":false,\"available\":100";
		final LandingFlow.LoadOutcome ok = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(LandingFlowWatermarkTest.sync("OK", 10, 10, 0, skipped)), 0L, false);
		check("available 100 = 10 read + 90 skipped: OK: " + ok.message, ok.ok);
		final LandingFlow.LoadOutcome cut = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(
			LandingFlowWatermarkTest.sync("OK", 10, 10, 0, skipped.replace("\"available\":100", "\"available\":500"))), 0L, false);
		check("available 500 > 10 + 90: FAILED: " + cut.message, !cut.ok && cut.message.contains("500"));
		check("LoadOutcomeRule direct: skipped counted", LoadOutcomeRule.decide(null, Long.valueOf(100), 10, 90, true, 0).level == LoadOutcomeRule.Level.OK
			&& LoadOutcomeRule.decide(null, Long.valueOf(100), 10, 0, true, 0).level == LoadOutcomeRule.Level.FAILED);
		check("LoadOutcomeRule direct: the four-argument form is unchanged", LoadOutcomeRule.decide(null, null, 80, 100).level == LoadOutcomeRule.Level.FAILED
			&& LoadOutcomeRule.decide(null, null, 0, 100).level == LoadOutcomeRule.Level.FAILED);
		check("LoadOutcomeRule direct: filtered skips the catalog comparison", LoadOutcomeRule.decide(null, null, 0, 0, true, 100).level == LoadOutcomeRule.Level.OK
			&& LoadOutcomeRule.decide(null, null, 0, 0, false, 100).level == LoadOutcomeRule.Level.FAILED);
		check("LoadOutcomeRule direct: filtered still fails truncated", LoadOutcomeRule.decide(Boolean.TRUE, null, 0, 0, true, 100).level == LoadOutcomeRule.Level.FAILED);
	}

	private static void checkSkippedRowsAreSaidInTheMessage() throws Exception
	{
		final String answer = LandingFlowWatermarkTest.sync("OK", 3, 3, 0, LandingFlowWatermarkTest.WM_FIELDS.replace("\"skippedByWatermark\":0", "\"skippedByWatermark\":41"));
		final LandingFlow.LoadOutcome outcome = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(answer), 0L, false);
		check("Loaded X of Y, Z unchanged skipped by the watermark since V: " + outcome.message,
			outcome.message.startsWith("Loaded 3 of 3 row(s) - changed since the watermark 2026-09-30T08:41:07.5, 41 unchanged rows skipped by the watermark, watermark now 2026-10-02T10:00"));
		final LandingFlow.LoadOutcome one = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(
			LandingFlowWatermarkTest.sync("OK", 3, 3, 0, LandingFlowWatermarkTest.WM_FIELDS.replace("\"skippedByWatermark\":0", "\"skippedByWatermark\":1"))), 0L, false);
		check("singular", one.message.contains("1 unchanged row skipped"));
		final LandingFlow.LoadOutcome note = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(
			LandingFlowWatermarkTest.sync("OK", 3, 3, 0, LandingFlowWatermarkTest.WM_FIELDS + ",\"watermarkNote\":\"Stored checkpoint ignored\"")), 0L, false);
		check("an engine note is carried: " + note.message, note.message.contains("(Stored checkpoint ignored)"));
	}

	private static ObjectNode execution(final String pStatus, final int pCreated, final int pUpdated, final int pNoChange, final int pErrors, final String pExtra) throws Exception
	{
		return LandingFlowWatermarkTest.json("{\"status\":\"" + pStatus + "\",\"created\":" + pCreated + ",\"updated\":" + pUpdated + ",\"noChange\":" + pNoChange + ",\"errorCount\":" + pErrors
			+ (pExtra.isEmpty() ? "" : "," + pExtra) + "}");
	}

	/** The polled path (connectors_run_get execution{}): DONE with 0 rows and a checkpoint is a quiet night, not a shortfall. */
	private static void checkPolledQuietRun() throws Exception
	{
		final ObjectNode execution = LandingFlowWatermarkTest.execution("DONE", 0, 0, 0, 0,
			"\"watermarkBefore\":\"2026-09-30T08:41:07.5\",\"watermarkAfter\":\"2026-09-30T08:41:07.5\",\"skippedByWatermark\":0");
		final LandingFlow.LoadOutcome outcome = RunStateOutcome.outcome(execution, 500000L, false);
		check("polled quiet run OK: " + outcome.message, outcome.ok && outcome.rows == 0);
		check("polled message: " + outcome.message, outcome.message.contains("no row changed at the source since the watermark")
			&& outcome.message.contains("changed since the watermark 2026-09-30T08:41:07.5"));
		final LandingFlow.LoadOutcome changed = RunStateOutcome.outcome(LandingFlowWatermarkTest.execution("DONE", 2, 5, 40, 0,
			"\"watermarkBefore\":\"2026-09-30T08:41:07.5\",\"watermarkAfter\":\"2026-10-02T10:00\",\"skippedByWatermark\":7"), 500000L, false);
		check("47 rows read (7 changed, 40 re-read at the boundary) of 500,000: OK, with the numbers: " + changed.message,
			changed.ok && changed.rows == 47 && changed.message.contains("7 unchanged rows skipped") && changed.message.contains("watermark now 2026-10-02T10:00"));
		check("polled truncated still fails", !RunStateOutcome.outcome(LandingFlowWatermarkTest.execution("DONE", 2, 0, 0, 0,
			"\"watermarkBefore\":\"2026-09-30T08:41:07.5\",\"skippedByWatermark\":0,\"truncated\":true"), 0L, false).ok);
	}

	private static void checkPolledDoneWithErrorsAndNoRowIsNotClean() throws Exception
	{
		final LandingFlow.LoadOutcome outcome = RunStateOutcome.outcome(LandingFlowWatermarkTest.execution("DONE", 0, 0, 0, 3, "\"message\":\"connection reset\""), 0L, false);
		check("DONE, no row, 3 errors fails: " + outcome.message, !outcome.ok && outcome.message.contains("3 error(s)") && outcome.message.contains("connection reset"));
		final LandingFlow.LoadOutcome partial = RunStateOutcome.outcome(LandingFlowWatermarkTest.execution("PARTIAL", 5, 0, 0, 1, ""), 0L, false);
		check("rows read plus an error stays the old PARTIAL posture (ok): " + partial.message, partial.ok && partial.message.contains("1 error(s)"));
	}

	private static void checkPolledFullRefreshAndFirstRun() throws Exception
	{
		final ObjectNode execution = LandingFlowWatermarkTest.execution("DONE", 40, 0, 0, 0, "\"watermarkBefore\":\"2026-09-30T08:41:07.5\",\"watermarkAfter\":\"2026-10-02T10:00\",\"skippedByWatermark\":0");
		final LandingFlow.LoadOutcome asked = RunStateOutcome.outcome(execution, 100L, true);
		check("a requested full refresh is judged on the catalog count: " + asked.message, !asked.ok && asked.message.contains("full refresh, watermark ignored"));
		final ObjectNode first = LandingFlowWatermarkTest.execution("DONE", 40, 0, 0, 0, "\"watermarkBefore\":null,\"watermarkAfter\":\"2026-10-02T10:00\",\"skippedByWatermark\":0");
		final LandingFlow.LoadOutcome outcome = RunStateOutcome.outcome(first, 100L, false);
		check("a first incremental run is judged on the catalog count too: " + outcome.message, !outcome.ok && outcome.message.contains("first incremental load"));
	}

	/** An execution with no watermark field at all (FULL mapping, older Connectors): the message and the verdict are the ones of before the lot. */
	private static void checkPolledWithoutWatermarkIsUnchanged() throws Exception
	{
		final LandingFlow.LoadOutcome outcome = RunStateOutcome.outcome(LandingFlowWatermarkTest.execution("DONE", 100, 0, 0, 0, ""), 100L, false);
		check("unchanged message: " + outcome.message, outcome.ok && "Loaded 100 row(s).".equals(outcome.message));
		check("no watermark on it", !outcome.watermark.incremental);
		final LandingFlow.LoadOutcome sync = LandingFlow.loadOutcome(LandingFlowWatermarkTest.json(LandingFlowWatermarkTest.sync("OK", 100, 100, 0, "")), 100L, false);
		check("unchanged sync message: " + sync.message, sync.ok && "Loaded 100 of 100 row(s).".equals(sync.message));
	}

	private static void checkWatermarkOutcomeReading() throws Exception
	{
		check("null answers NONE", WatermarkOutcome.fromSync(null, false) == WatermarkOutcome.NONE && WatermarkOutcome.fromExecution(null, false) == WatermarkOutcome.NONE);
		check("a sync answer with no watermarkField is NONE", WatermarkOutcome.fromSync(LandingFlowWatermarkTest.json("{\"status\":\"OK\"}"), true) == WatermarkOutcome.NONE);
		check("an execution with no watermark field is NONE", WatermarkOutcome.fromExecution(LandingFlowWatermarkTest.json("{\"status\":\"DONE\"}"), true) == WatermarkOutcome.NONE);
		final WatermarkOutcome none = WatermarkOutcome.NONE;
		check("NONE says nothing", none.describe().isEmpty() && !none.filtered() && none.checkpointSettled(0));
		final WatermarkOutcome emptyFull = WatermarkOutcome.fromSync(LandingFlowWatermarkTest.json(
			"{\"watermarkField\":\"X\",\"watermarkBefore\":\"2026-01-01\",\"watermarkAfter\":\"2026-01-01\",\"skippedByWatermark\":0,\"fullRefresh\":true}"), false);
		check("a full refresh over an empty source did not reset the checkpoint", !emptyFull.checkpointSettled(0) && emptyFull.checkpointSettled(5));
		final WatermarkOutcome quiet = WatermarkOutcome.fromExecution(LandingFlowWatermarkTest.json("{\"watermarkBefore\":\"2026-01-01\"}"), false);
		check("an incremental run that read nothing keeps its checkpoint: settled", quiet.checkpointSettled(0) && quiet.filtered());
		check("a first run (before empty) is not filtered", !WatermarkOutcome.fromExecution(LandingFlowWatermarkTest.json("{\"watermarkBefore\":\"\",\"watermarkAfter\":\"2026\"}"), false).filtered());
	}

	private static void checkFullRefreshDecision()
	{
		check("no change column: never", !ChangeColumnRule.fullRefreshNeeded("", "", 0) && !ChangeColumnRule.fullRefreshNeeded(null, "A", 0) && !ChangeColumnRule.fullRefreshNeeded("  ", "A", 10));
		check("never loaded with a change column: full (harmless on a first run)", ChangeColumnRule.fullRefreshNeeded("LAST_MODIFIED", null, -1));
		check("column changed since the last load: full", ChangeColumnRule.fullRefreshNeeded("UPDATED_AT", "LAST_MODIFIED", 1000));
		check("cleared then set again: full", ChangeColumnRule.fullRefreshNeeded("LAST_MODIFIED", "", 1000));
		check("same column, landing filled: incremental", !ChangeColumnRule.fullRefreshNeeded("LAST_MODIFIED", "LAST_MODIFIED", 1000));
		check("same column, landing empty (key change, rebuilt dataset, reset): full", ChangeColumnRule.fullRefreshNeeded("LAST_MODIFIED", "LAST_MODIFIED", 0));
		check("same column, landing not countable: no decision from the count", !ChangeColumnRule.fullRefreshNeeded("LAST_MODIFIED", "LAST_MODIFIED", -1));
		check("whitespace is not a difference", !ChangeColumnRule.fullRefreshNeeded(" LAST_MODIFIED ", "LAST_MODIFIED", 5));
	}

	private static void checkColumnToRecord() throws Exception
	{
		final WatermarkOutcome incremental = WatermarkOutcome.fromSync(LandingFlowWatermarkTest.json(
			"{\"watermarkField\":\"X\",\"watermarkBefore\":null,\"watermarkAfter\":\"2026-01-01\",\"skippedByWatermark\":0,\"fullRefresh\":false}"), false);
		check("a successful load records its column", "LAST_MODIFIED".equals(ChangeColumnRule.columnToRecord("LAST_MODIFIED", true, incremental, 10)));
		check("a failed load leaves it", ChangeColumnRule.columnToRecord("LAST_MODIFIED", false, incremental, 10) == null);
		check("a full table records an empty column (clears it)", "".equals(ChangeColumnRule.columnToRecord(null, true, WatermarkOutcome.NONE, 10)));
		final WatermarkOutcome emptyFull = WatermarkOutcome.fromSync(LandingFlowWatermarkTest.json(
			"{\"watermarkField\":\"X\",\"watermarkBefore\":\"2026-01-01\",\"watermarkAfter\":\"2026-01-01\",\"skippedByWatermark\":0,\"fullRefresh\":true}"), false);
		check("a full refresh of an empty source does not claim the column", ChangeColumnRule.columnToRecord("LAST_MODIFIED", true, emptyFull, 0) == null);
		check("a null watermark is tolerated", "A".equals(ChangeColumnRule.columnToRecord("A", true, null, 0)));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
