package com.on.ebx.factory.landing;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The inbound landing flow of one {@code LandingTable}, as a pure function of its plan: the
 * {@code connectors_mapping_upsert} {@code mapping} document (one root mapping per table, one rule per
 * source column plus the six technical fields), the small rules a load run needs (how many rows to ask for,
 * what load id and received-at moment to mint), and the decision {@code LandingFlows} makes from a
 * {@code connectors_sync} answer ({@link #loadOutcome}). {@link #mapping} builds one of two shapes,
 * decided by {@code Table#fileFeed}: the {@code query} operation (generated SQL, a {@link SqlDialect}) for a
 * table whose connector {@link #dialectFor} recognises, or the {@code read} operation (rule-transformation
 * primitives only: {@code hash}/{@code now}/{@code runid}/{@code concat}) for a feed-style connector with no
 * SQL dialect at all - see {@link #feedMapping}. No EBX class: see {@code docs/LANDING-FLOWS.md} for the
 * evidence this design rests on, in particular its "Recommended design" section, which this class implements
 * literally. {@code com.on.ebx.factory.ebx.LandingFlows} is the thin EBX/MCP layer that calls this and does
 * the actual {@code connectors_mapping_upsert} / {@code connectors_sync} calls and EBX writes.
 * <p>
 * <b>Volume-operations lot 8a (2026-09-28, task 6):</b> every mapping this class builds ({@link #mapping},
 * {@link #feedMapping}, {@link #recipeMapping}) now sets Connectors mapping lot 5's two new properties -
 * {@code "provenance": false} (skip writing a {@code FieldProvenance} row per changed field for a landing
 * write; CLAUDE.md &sect;15 "Where the time goes" measured that bookkeeping at 2/3 of a load's own per-row
 * cost, and landing rows have no consumer for it) and {@code "unchangedIfEqual": "./_rowHash"} (an incoming
 * row whose {@code _rowHash} already matches the existing record's is {@code NO_CHANGE}: nothing is written
 * at all, not even {@code _loadId}/{@code _receivedAt}). See {@code docs/LANDING-FLOWS.md}'s own "Lot 8a"
 * section for exactly what this changes for those two fields and for incremental staging's {@code _status}
 * read on an unchanged row. Not verified live this session (no EBX/Connectors instance reachable) - the two
 * property names and their meaning come from the Connectors lot 5 brief handed to this lot, not from a live
 * {@code connectors_mapping_upsert} probe.
 */
public final class LandingFlow
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Every dialect this class can generate SQL for; {@link #dialectFor} is the only place that chooses one. */
	private static final List<SqlDialect> DIALECTS = Collections.unmodifiableList(Arrays.<SqlDialect> asList(new SnowflakeDialect(), new H2Dialect(), new PostgreSqlDialect()));

	/** {@code L<yyyyMMdd>-<HHmmss>}, always UTC: the clock is a parameter so this is testable without the wall clock. */
	private static final DateTimeFormatter LOAD_ID_AT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

	/** {@code yyyy-MM-dd'T'HH:mm:ss}, no zone conversion applied (the clock's own zone is read as-is): see {@link #receivedAt}. */
	private static final DateTimeFormatter RECEIVED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

	/** Marks a {@code _sourceId} that is not a real key: see the comment on {@code sourceIdExpr} in {@link #mapping}. */
	private static final String NOT_A_REAL_KEY_PREFIX = "#";

	/** Alias of the change column in the generated SELECT, and the mapping's {@code checkpointValuePath} (volume wave 2, lot V2-E). */
	public static final String CHANGE_VALUE_ALIAS = "CHANGE_VALUE";

	/**
	 * Volume-guards lot (2026-09-28, task item 1): connectors_sync's {@code max} for every landing load -
	 * replaces the old {@code LandingFlow.max(long)}, which sized it from the catalog's last-known row count.
	 * That count can be 0 (never profiled) or stale (rows added at the source since the last scan,
	 * LandingFlows.java:939-940), and the connector clamps {@code max} to its own hard cap regardless (a
	 * separate Connectors-side lot raises that cap and adds the {@code truncated}/{@code available} fields
	 * {@link LoadOutcomeRule} reads). Asking for the largest {@code int} removes the factory's OWN sizing as a
	 * second, independent way to truncate a load: {@link LoadOutcomeRule} is what now catches an incomplete
	 * read, by comparing what actually came back to what was last seen at the source.
	 */
	public static final int SYNC_MAX_ROWS = Integer.MAX_VALUE;

	/**
	 * Coordinator follow-up (2026-09-24, docs/mockups/sources-landing/LandingComposite.dc.html "How a row is
	 * recognised"): a DISPLAY-ONLY preview of the {@code _sourceId} a composite key's parts would produce,
	 * mirroring the SQL path's own escaping rule EXACTLY ({@link SqlDialect#escapeKeyPart} - backslash
	 * doubled FIRST, then {@code '|'} escaped with a leading backslash; see {@link SnowflakeDialect
	 * #escapeKeyPart}'s own comment for why the order matters) - never used to build a real {@code
	 * _sourceId} (that stays entirely inside the generated SQL/feed rules, {@link #mapping}). A single-part
	 * key is left UNESCAPED, same as the real rule: with only one part there is no {@code '|'} separator to
	 * protect, so escaping it would only change the displayed value of every key containing a literal
	 * backslash or pipe for no reason (see this class' own "composite-key lot" note above {@link #mapping}).
	 * A {@code null} part is treated as an empty string, never a {@code NullPointerException}.
	 */
	public static String sourceIdPreview(final List<String> pParts)
	{
		if (pParts.size() <= 1)
		{
			return pParts.isEmpty() ? "" : LandingFlow.orEmpty(pParts.get(0));
		}
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pParts.size(); i++)
		{
			if (i > 0)
			{
				out.append('|');
			}
			out.append(LandingFlow.orEmpty(pParts.get(i)).replace("\\", "\\\\").replace("|", "\\|"));
		}
		return out.toString();
	}

	private static String orEmpty(final String pValue)
	{
		return pValue == null ? "" : pValue;
	}

	/**
	 * Documents a real, currently-unfixed gap (composite-key lot, 2026-09-24): a feed-based (file/SFTP/S3/...)
	 * table's composite {@code _sourceId} is built with the {@code concat} rule transformation, which has no
	 * per-part escaping - unlike the SQL path's {@code dialect.escapeKeyPart}, a {@code '|'} or {@code '\'}
	 * genuinely present in one key column's value can collide with the {@code '|'} {@code concat} joins with.
	 * See {@link #feedMapping}'s Javadoc for the full explanation and where the fix belongs (in
	 * {@code ebx-connectors}' {@code Transformations.register("concat", ...)}, not here). Referenced from this
	 * constant, not acted on: kept as a named, greppable marker of the gap rather than only prose.
	 */
	public static final String FILE_FEED_KEY_ESCAPE_GAP = "concat has no per-part escaping: see LandingFlow.feedMapping's Javadoc";

	private LandingFlow()
	{
	}

	/** One source column feeding one landing field, in the table's column position order. */
	public static final class Column
	{
		public final String sourceColumn;
		public final String fieldName;
		/** Whether this column is part of the source key that builds {@code _sourceId}. */
		public final boolean sourceKey;

		public Column(final String pSourceColumn, final String pFieldName, final boolean pSourceKey)
		{
			this.sourceColumn = pSourceColumn;
			this.fieldName = pFieldName;
			this.sourceKey = pSourceKey;
		}
	}

	/** What {@link #mapping} needs of one landing table and the source it reads from, everything already decided (names, key columns). */
	public static final class Table
	{
		/** {@code ebx-connectors} {@code Connector.id} (the mapping's own {@code connector} field), e.g. {@code "Snowflake"}: free-form, chosen by whoever configured the connector. */
		public final String connectorId;
		/** The connector's type as {@code connectors_describe} reports it (e.g. {@code "snowflake"}): selects the {@link SqlDialect}. */
		public final String connectorType;
		/** {@code ProjectSource.sourceSystem}: the value the {@code _sourceSystem} rule writes on every row. Not necessarily the dataset name - see {@link #landingDataset}. */
		public final String sourceSystem;
		/** Qualified name at the source, e.g. {@code DB.SCHEMA.TABLE}: the generated SQL's {@code FROM}. */
		public final String sourceObject;
		public final String landingDataspace;
		/** {@code ProjectSource.landingDataset}, as {@code LandingGenerator} actually named it - today equal to {@code sourceSystem}, but that is an implementation detail this class must not assume. */
		public final String landingDataset;
		/** Name of the generated EBX table: the mapping's {@code tablePath} is {@code /root/<landingTableName>}. */
		public final String landingTableName;
		/** In position order; at least one, or {@link #mapping} refuses. */
		public final List<Column> columns;
		/**
		 * True when this table's connector has no {@link SqlDialect} ({@link #dialectFor} would refuse it) but
		 * exposes a feed-style {@code read} operation instead (an {@code AbstractFileConnector}: {@code sample-file},
		 * {@code sftp}, {@code s3}, {@code azure-blob}, {@code gcs}, {@code ftp}): {@link #mapping} then builds the
		 * feed-based mapping (operation {@code read}, {@code inputs.path}) instead of the SQL one (operation
		 * {@code query}, {@code inputs.sql}). Decided by the caller ({@code LandingFlows}) from the connector's own
		 * advertised {@code operations[]} shape - never derived here from {@link #connectorType} by string-matching:
		 * this class stays a pure function of an already-fully-decided {@code Table} (see the class Javadoc).
		 */
		public final boolean fileFeed;
		/**
		 * Read-recipe lot (2026-09-24): the catalog's own {@link ReadRecipe} for this table's asset, when one
		 * exists and applies ({@link ReadRecipe.Eligibility.Kind#RECIPE}) - {@code null} for a legacy table
		 * ({@code connectorType}/{@code fileFeed} decide instead, see {@link #mapping}). Set by the caller
		 * ({@code ebx.LandingFlows}), never derived here.
		 */
		public final ReadRecipe recipe;
		/**
		 * Volume wave 2, lot V2-E (2026-10-03): the source column that carries a row's last change and how its values
		 * compare ({@link ChangeColumnRule#TIMESTAMP}/{@link ChangeColumnRule#MONOTONIC_ID}/{@link ChangeColumnRule
		 * #ORDERED_STRING}), or {@code null}/empty for a table reloaded in full. Only a SQL flow reads them - the generated one and a
		 * catalog recipe whose operation is a SQL {@code query} ({@link #changeColumnIgnoredReason}): a file feed and any other recipe
		 * ignore them and stay FULL. Set through {@link #withChangeColumn}.
		 */
		public final String changeColumn;
		public final String changeKind;
		/**
		 * Fix of 2026-10-03 (two projects with the same source system shared one mapping): the {@code Project.code} of the
		 * project this table belongs to, which {@link #label} puts into the mapping's label - {@code connectors_mapping_upsert}
		 * identifies a mapping by its label, so a label without the project made two projects' tables one mapping. Blank only
		 * for a table built without a project (unit tests): the label then keeps its old, project-less shape. Set through
		 * {@link #forProject}.
		 */
		public final String projectCode;
		/**
		 * The {@code LandingTable.inboundMapping} id this project's table already holds AND owns (its mapping's dataspace is the
		 * project's landing dataspace, checked by the caller), or blank for a table never generated: {@link #mapping} then adds
		 * {@code id} + {@code updateById} to the document, so Connectors updates THAT mapping in place - label included - instead
		 * of looking one up by label (which would miss it after a label change and create a second landing mapping on the table).
		 * Set through {@link #forProject}.
		 */
		public final String existingMappingId;

		/** Convenience for a SQL-dialect table: same as calling the other constructor with {@code pFileFeed = false} and no recipe. */
		public Table(final String pConnectorId, final String pConnectorType, final String pSourceSystem, final String pSourceObject, final String pLandingDataspace,
			final String pLandingDataset, final String pLandingTableName, final List<Column> pColumns)
		{
			this(pConnectorId, pConnectorType, pSourceSystem, pSourceObject, pLandingDataspace, pLandingDataset, pLandingTableName, pColumns, false);
		}

		/** Convenience for a legacy (no-recipe) table: same as calling the full constructor with {@code pRecipe = null}. */
		public Table(final String pConnectorId, final String pConnectorType, final String pSourceSystem, final String pSourceObject, final String pLandingDataspace,
			final String pLandingDataset, final String pLandingTableName, final List<Column> pColumns, final boolean pFileFeed)
		{
			this(pConnectorId, pConnectorType, pSourceSystem, pSourceObject, pLandingDataspace, pLandingDataset, pLandingTableName, pColumns, pFileFeed, null);
		}

		/** A table whose flow is built from the catalog's {@code readRecipe} ({@code pRecipe != null}): {@code pConnectorType}/{@code pFileFeed} are then ignored by {@link #mapping} (see {@link #recipe}'s Javadoc). */
		public Table(final String pConnectorId, final String pConnectorType, final String pSourceSystem, final String pSourceObject, final String pLandingDataspace,
			final String pLandingDataset, final String pLandingTableName, final List<Column> pColumns, final boolean pFileFeed, final ReadRecipe pRecipe)
		{
			this.connectorId = pConnectorId;
			this.connectorType = pConnectorType;
			this.sourceSystem = pSourceSystem;
			this.sourceObject = pSourceObject;
			this.landingDataspace = pLandingDataspace;
			this.landingDataset = pLandingDataset;
			this.landingTableName = pLandingTableName;
			this.columns = pColumns;
			this.fileFeed = pFileFeed;
			this.recipe = pRecipe;
			this.changeColumn = null;
			this.changeKind = null;
			this.projectCode = null;
			this.existingMappingId = null;
		}

		private Table(final Table pFrom, final String pChangeColumn, final String pChangeKind, final String pProjectCode, final String pExistingMappingId)
		{
			this.connectorId = pFrom.connectorId;
			this.connectorType = pFrom.connectorType;
			this.sourceSystem = pFrom.sourceSystem;
			this.sourceObject = pFrom.sourceObject;
			this.landingDataspace = pFrom.landingDataspace;
			this.landingDataset = pFrom.landingDataset;
			this.landingTableName = pFrom.landingTableName;
			this.columns = pFrom.columns;
			this.fileFeed = pFrom.fileFeed;
			this.recipe = pFrom.recipe;
			this.changeColumn = pChangeColumn;
			this.changeKind = pChangeKind;
			this.projectCode = pProjectCode;
			this.existingMappingId = pExistingMappingId;
		}

		/** The same table reloaded in full: no change column (what a flow falls back to when Connectors does not know row watermarks yet). */
		public Table withoutChangeColumn()
		{
			return new Table(this, null, null, this.projectCode, this.existingMappingId);
		}

		/** The same table owned by project {@code pProjectCode} (its label's project part), updating the mapping {@code pExistingMappingId} in place when that is not blank - see {@link #projectCode}, {@link #existingMappingId}. */
		public Table forProject(final String pProjectCode, final String pExistingMappingId)
		{
			return new Table(this, this.changeColumn, this.changeKind, pProjectCode == null ? null : pProjectCode.trim(),
				pExistingMappingId == null || pExistingMappingId.trim().isEmpty() ? null : pExistingMappingId.trim());
		}

		/** The same table reloaded incrementally through {@code pChangeColumn} (a blank column returns this table unchanged: a FULL reload). */
		public Table withChangeColumn(final String pChangeColumn, final String pChangeKind)
		{
			return pChangeColumn == null || pChangeColumn.trim().isEmpty() ? this
				: new Table(this, pChangeColumn.trim(), pChangeKind == null ? "" : pChangeKind.trim(), this.projectCode, this.existingMappingId);
		}
	}

	/**
	 * The dialect handling {@code pConnectorType}. Refuses rather than falling back to Snowflake SQL for a
	 * type it does not recognise: a silently-wrong SQL statement sent to an unrelated connector is worse than
	 * an explicit refusal naming what is actually supported.
	 */
	public static SqlDialect dialectFor(final String pConnectorType)
	{
		for (final SqlDialect dialect : LandingFlow.DIALECTS)
		{
			if (dialect.supports(pConnectorType))
			{
				return dialect;
			}
		}
		final StringBuilder supported = new StringBuilder();
		for (final SqlDialect dialect : LandingFlow.DIALECTS)
		{
			if (supported.length() > 0)
			{
				supported.append(", ");
			}
			supported.append(dialect.name());
		}
		throw new IllegalArgumentException(
			"No landing SQL generator for connector type '" + pConnectorType + "': supported types are " + supported + ".");
	}

	/** The longest label Connectors stores ({@code Mapping.label} is {@code maxLength 120}). */
	public static final int LABEL_MAX = 120;

	/**
	 * The deterministic label of the mapping: together with connector/operation/direction/tablePath, this is what makes {@code
	 * connectors_mapping_upsert} idempotent (see the knowledge base) - so it must be unique per PROJECT, not only per source
	 * system and table. Measured 2026-10-03 (Vol100k / Vol100kM): two projects reading the same source system under the same
	 * connector, each with its own landing dataspace, got one mapping between them because the label was {@code "Landing: <source
	 * system> <table>"}; the second project's generation rewrote the first's mapping (its dataspace, and with it the watermark
	 * checkpoint that is kept per mapping). The label is now {@code "Landing: <project code> <source system> <table>"}, cut with a
	 * short stable hash of the whole text when it would exceed {@link #LABEL_MAX}. A blank project code (a table built without
	 * {@link Table#forProject}, i.e. never in production) keeps the old project-less shape, {@link #legacyLabel}.
	 */
	public static String label(final String pProjectCode, final String pSourceSystem, final String pLandingTableName)
	{
		if (pProjectCode == null || pProjectCode.trim().isEmpty())
		{
			return LandingFlow.legacyLabel(pSourceSystem, pLandingTableName);
		}
		final String full = "Landing: " + pProjectCode.trim() + " " + pSourceSystem + " " + pLandingTableName;
		if (full.length() <= LandingFlow.LABEL_MAX)
		{
			return full;
		}
		final String hash = String.format(Locale.ROOT, "%08x", Integer.valueOf(full.hashCode()));
		return full.substring(0, LandingFlow.LABEL_MAX - hash.length() - 1) + "~" + hash;
	}

	/** The label before the project code was part of it ({@code "Landing: <source system> <table>"}): what a mapping generated earlier is named, and what a project-less table still gets. */
	public static String legacyLabel(final String pSourceSystem, final String pLandingTableName)
	{
		return "Landing: " + pSourceSystem + " " + pLandingTableName;
	}

	/**
	 * Sets the mapping's identity: its {@link #label} and, when the table already owns a mapping ({@link Table#existingMappingId}),
	 * {@code id} + {@code updateById} - the form {@code connectors_mapping_upsert} reads (Connectors {@code ConfigTransfer.importMapping}:
	 * the lookup by label comes first, and only a miss falls back to the id) to update that mapping in place and rename it, so
	 * the label change never leaves a second landing mapping behind the old one.
	 */
	private static void identify(final ObjectNode pMapping, final Table pTable)
	{
		pMapping.put("label", LandingFlow.label(pTable.projectCode, pTable.sourceSystem, pTable.landingTableName));
		if (pTable.existingMappingId != null && !pTable.existingMappingId.isEmpty())
		{
			pMapping.put("id", pTable.existingMappingId);
			pMapping.put("updateById", true);
		}
	}

	/**
	 * The {@code connectors_mapping_upsert} {@code mapping} document for one landing table: deterministic
	 * label, the {@code query} operation, the import-feed fields ({@code itemsPath}, {@code keyPredicate} on
	 * {@code _sourceId}, {@code createIfMissing}), the generated SQL, and one rule per column plus the
	 * technical rules. Callers wrap this as {@code {"mapping": <this>, "dryRun": ...}}.
	 */
	public static ObjectNode mapping(final Table pTable)
	{
		if (pTable == null)
		{
			throw new IllegalArgumentException("a landing table is required");
		}
		if (pTable.columns == null || pTable.columns.isEmpty())
		{
			throw new IllegalArgumentException("Landing table " + pTable.landingTableName + " has no column: nothing to generate a flow for");
		}
		// read-recipe lot (2026-09-24): a table with a catalog recipe is ALWAYS built from it, whatever
		// connectorType/fileFeed say - those two only still matter for the legacy fallback below, kept for
		// an asset scanned before readRecipe existed (see ReadRecipe.Eligibility's own Javadoc and
		// docs/LANDING-FLOWS.md's "Read-recipe lot" section for why removing the legacy generator entirely
		// was not done: most of this file's own pinned tests exercise it directly).
		if (pTable.recipe != null)
		{
			return LandingFlow.recipeMapping(pTable, pTable.recipe);
		}
		if (pTable.fileFeed)
		{
			return LandingFlow.feedMapping(pTable);
		}
		LandingFlow.requireSafeIdentifier(pTable.sourceObject, "Source object");
		for (final Column column : pTable.columns)
		{
			LandingFlow.requireSafeIdentifier(column.sourceColumn, "Source column");
		}
		final SqlDialect dialect = LandingFlow.dialectFor(pTable.connectorType);

		// one TO_VARCHAR(...) text expression per source column, kept unaliased: an alias defined earlier in
		// the same SELECT list is not something every SQL dialect lets a later item of the same list refer
		// back to, so _sourceId/_rowHash repeat the full expression instead of referring to C1, C2... by name.
		final List<String> textExprs = new ArrayList<String>();
		final List<String> keyTextExprs = new ArrayList<String>();
		final StringBuilder select = new StringBuilder();
		for (int i = 0; i < pTable.columns.size(); i++)
		{
			final Column column = pTable.columns.get(i);
			final String expr = dialect.asText("s." + dialect.quoteIdentifier(column.sourceColumn));
			textExprs.add(expr);
			if (column.sourceKey)
			{
				keyTextExprs.add(expr);
			}
			if (i > 0)
			{
				select.append(", ");
			}
			select.append(expr).append(" as C").append(i + 1);
		}

		final String rowHashExpr = dialect.hash(LandingFlow.concatCoalesce(textExprs));
		// composite-key lot (2026-09-24): a key of 2+ columns has its parts escaped (backslash doubled, then
		// '|' escaped - dialect.escapeKeyPart, the same convention EBX's own PrimaryKey uses) BEFORE they are
		// COALESCEd/'|'-joined, so a '|' or '\' genuinely present in one column's value can never be mistaken
		// for the separator between key parts - see requireSafeIdentifier's sibling concern, but this one is
		// about a VALUE colliding with the separator, not an identifier breaking out of quoting. A SINGLE-column
		// key is deliberately left unescaped: with only one part there is no separator to protect against, so
		// escaping it would only change every _sourceId already computed for a value containing '|'/'\' with
		// nothing gained - see docs/SOURCES-LANDING-KEY.md for the worked-out reasoning and which existing rows
		// this changes (composite keys whose value contains '|' or '\', never a single-column key).
		final List<String> escapedKeyTextExprs = new ArrayList<String>(keyTextExprs.size());
		if (keyTextExprs.size() >= 2)
		{
			for (final String expr : keyTextExprs)
			{
				escapedKeyTextExprs.add(dialect.escapeKeyPart(expr));
			}
		}
		else
		{
			escapedKeyTextExprs.addAll(keyTextExprs);
		}
		// with a declared source key: _sourceId is normally the plain concatenation of the key columns, not
		// hashed - it has to be a stable, human-diagnosable value a keyPredicate compares against, and it is
		// one EBX already trusts as an identity. Without any key at all, _sourceId falls back to the row hash
		// itself: not a real identity (a changed row becomes, in effect, a different id), but the least-wrong
		// choice with nothing declared or inferred as a key.
		//
		// A declared key that is entirely NULL for a given row is a real, if unusual, source data shape: every
		// key column COALESCEd to '' would otherwise produce the exact same _sourceId for every such row (one
		// key column: the empty string; several: '|' repeated), collapsing them onto a single landing record -
		// the first creating it, every later one silently updating it in place instead of a new row
		// (ConnectorService.importItem, ConnectorService.java:1034: an empty *_sourceId value* still gives a
		// non-empty resolved predicate "./_sourceId=''", looked up like any other value; it is only a
		// literally empty *predicate string* that importItem treats as "no lookup" at that same line). So a
		// declared key falls back to '#' || <row hash> - the '#' prefix marking, for a later reader, that this
		// _sourceId is not a real key - only when EVERY key column is NULL; a PARTIALLY null composite key is
		// still an identity and keeps the plain concatenation. Plain SQL-92 (CASE/IS NULL/AND), no dialect
		// method needed.
		final String sourceIdExpr;
		if (keyTextExprs.isEmpty())
		{
			sourceIdExpr = rowHashExpr;
		}
		else
		{
			final StringBuilder everyKeyColumnIsNull = new StringBuilder();
			for (int i = 0; i < keyTextExprs.size(); i++)
			{
				if (i > 0)
				{
					everyKeyColumnIsNull.append(" AND ");
				}
				everyKeyColumnIsNull.append(keyTextExprs.get(i)).append(" IS NULL");
			}
			sourceIdExpr = "CASE WHEN " + everyKeyColumnIsNull + " THEN '" + LandingFlow.NOT_A_REAL_KEY_PREFIX + "' || " + rowHashExpr + " ELSE "
				+ LandingFlow.concatCoalesce(escapedKeyTextExprs) + " END";
		}

		select.append(", ").append(sourceIdExpr).append(" as SOURCE_ID");
		// the load id and the received-at moment the factory minted, embedded as run-time input placeholders
		// Connectors substitutes into the SQL text before it runs (see the knowledge base): there is no
		// rule-level way to read either. _receivedAt is deliberately not CURRENT_TIMESTAMP() computed in
		// Snowflake: that runs in the Snowflake session's own time zone (America/Los_Angeles by default),
		// which EBX then stores as if it were server-local time - a real, observed defect. The moment a row
		// is received is the factory's business anyway, not the source's, so the factory mints it once per
		// table load in its own local time (see LandingFlows.load), exactly like the load id.
		select.append(", '${param.loadId}' as LOAD_ID");
		select.append(", '${param.receivedAt}' as RECEIVED_AT");
		select.append(", ").append(rowHashExpr).append(" as ROW_HASH");

		// volume wave 2, lot V2-E (2026-10-03): an INCREMENTAL landing - see watermarkWhere. The change column is also
		// selected as itself (CHANGE_VALUE: typed, not the TO_VARCHAR text of C<n>) because that field is what Connectors
		// reads the new checkpoint from (checkpointValuePath): the JDBC row carries a timestamp as ISO text and a number
		// as a number, which Watermark compares as such, whereas a driver-formatted text would not parse.
		final String changeColumn = pTable.changeColumn == null ? "" : pTable.changeColumn.trim();
		String where = "";
		if (!changeColumn.isEmpty())
		{
			final ChangeFilter filter = LandingFlow.changeFilter(pTable, dialect, changeColumn);
			where = " where " + filter.where;
			select.append(", ").append(filter.selectItem);
		}
		final String sql = "select " + select + " from " + LandingFlow.fromClause(dialect, pTable.sourceObject) + " s" + where;

		final ObjectNode mapping = LandingFlow.MAPPER.createObjectNode();
		mapping.put("connector", pTable.connectorId);
		mapping.put("operation", "query");
		mapping.put("direction", "INBOUND");
		LandingFlow.identify(mapping, pTable);
		mapping.put("dataspace", pTable.landingDataspace);
		mapping.put("dataset", pTable.landingDataset);
		mapping.put("tablePath", "/root/" + pTable.landingTableName);
		mapping.put("active", true);
		mapping.put("itemsPath", "rows");
		mapping.put("keyPredicate", "./_sourceId='${SOURCE_ID}'");
		mapping.put("createIfMissing", true);
		// volume-operations lot 8a (2026-09-28, task 6): Connectors mapping lot 5's two new properties, on
		// every landing mapping this class generates - see docs/LANDING-FLOWS.md's "Lot 8a" section for what
		// each one changes (provenance rows are no longer written per field; an unchanged row is left alone).
		mapping.put("provenance", false);
		mapping.put("unchangedIfEqual", "./_rowHash");
		if (!changeColumn.isEmpty())
		{
			// Connectors lot V2-D (docs/REFERENCE.md 2.14.7): checkpointBoundary stays empty on purpose - unchangedIfEqual is
			// set, so the boundary is INCLUSIVE by itself, matching the >= of the SQL; a row equal to the checkpoint comes
			// back once more and reports NO_CHANGE without a write.
			mapping.put("loadMode", "INCREMENTAL");
			mapping.put("checkpointStrategy", pTable.changeKind.trim());
			mapping.put("checkpointValuePath", LandingFlow.CHANGE_VALUE_ALIAS);
		}
		mapping.putObject("inputs").put("sql", sql);

		final ArrayNode rules = mapping.putArray("rules");
		LandingFlow.rule(rules, "SOURCE_ID", "./_sourceId", "", "ALWAYS");
		for (int i = 0; i < pTable.columns.size(); i++)
		{
			LandingFlow.rule(rules, "C" + (i + 1), "./" + pTable.columns.get(i).fieldName, "", "ALWAYS");
		}
		// pTable.landingTableName and every column.fieldName used as a targetPath here are safe by
		// construction too: both come from LandingModel.xmlName, which only ever emits [A-Za-z0-9_] (leading
		// digit/underscore handled separately) - see LandingModel.java. pTable.sourceSystem's own safety is
		// explained on addSourceSystemRule, shared by both mapping shapes.
		LandingFlow.addSourceSystemRule(rules, pTable.sourceSystem);
		LandingFlow.rule(rules, "LOAD_ID", "./_loadId", "", "ALWAYS");
		// no isodatetime here: LandingFlow.receivedAt already produces exactly the text an EBX xs:dateTime
		// field expects (seconds always present, no zone or fractional part to strip).
		LandingFlow.rule(rules, "RECEIVED_AT", "./_receivedAt", "", "ALWAYS");
		LandingFlow.rule(rules, "ROW_HASH", "./_rowHash", "", "ALWAYS");
		LandingFlow.addStatusRule(rules);

		return mapping;
	}

	/**
	 * The change column's kind, refused when it is not one Connectors reads as a row watermark or when the column is not
	 * one of the table's own: a flow that would silently read from the wrong column is worse than no flow.
	 */
	private static String requireChangeKind(final Table pTable, final String pChangeColumn)
	{
		boolean known = false;
		for (final Column column : pTable.columns)
		{
			if (column.sourceColumn.equals(pChangeColumn))
			{
				known = true;
				break;
			}
		}
		if (!known)
		{
			throw new IllegalArgumentException("Change column '" + pChangeColumn + "' is not a column of landing table " + pTable.landingTableName + ".");
		}
		final String kind = pTable.changeKind == null ? "" : pTable.changeKind.trim();
		if (!ChangeColumnRule.isKind(kind))
		{
			throw new IllegalArgumentException("Change column '" + pChangeColumn + "' of landing table " + pTable.landingTableName + " has no valid kind (TIMESTAMP, MONOTONIC_ID or ORDERED_STRING): '" + kind + "'.");
		}
		return kind;
	}

	/**
	 * What an incremental flow adds to a {@code select ... from <something> s}: the change column selected as itself under
	 * {@link #CHANGE_VALUE_ALIAS} ({@link #selectItem}) and the watermark filter on it ({@link #where}). One place builds both, for the
	 * generated-SQL flow ({@link #mapping}) and for a catalog-recipe SQL flow ({@link #recipeMapping}), so they cannot drift apart.
	 */
	private static final class ChangeFilter
	{
		/** {@code s."<col>" as CHANGE_VALUE}. */
		final String selectItem;
		/** {@code (${watermark.isSet} = 0 or s."<col>" >= <watermark>)}, see {@link LandingFlow#watermarkWhere}. */
		final String where;

		ChangeFilter(final String pSelectItem, final String pWhere)
		{
			this.selectItem = pSelectItem;
			this.where = pWhere;
		}
	}

	/** The {@link ChangeFilter} of {@code pChangeColumn} (already trimmed, non-empty) on a SQL source aliased {@code s}; refuses like {@link #requireChangeKind} and {@link #watermarkWhere}. */
	private static ChangeFilter changeFilter(final Table pTable, final SqlDialect pDialect, final String pChangeColumn)
	{
		final String quoted = "s." + pDialect.quoteIdentifier(pChangeColumn);
		return new ChangeFilter(quoted + " as " + LandingFlow.CHANGE_VALUE_ALIAS, LandingFlow.watermarkWhere(pDialect, quoted, LandingFlow.requireChangeKind(pTable, pChangeColumn)));
	}

	/**
	 * The SQL of a catalog recipe when it is one a watermark can wrap ({@code query} operation with an {@code inputs.sql} text), else
	 * {@code null}: a file {@code read}, a REST call or a SOQL query is not a SQL statement this class may rewrite.
	 */
	private static String recipeSql(final ReadRecipe pRecipe)
	{
		if (!"query".equals(pRecipe.operation))
		{
			return null;
		}
		final String sql = pRecipe.inputs.get("sql");
		return sql == null || sql.trim().isEmpty() ? null : sql;
	}

	/**
	 * Volume wave 2, lot V2-E, recipe gap (2026-10-03): why this table's flow cannot read a watermark, or {@code null} when it can - the
	 * generated-SQL flow, and a catalog-recipe flow whose operation is a SQL {@code query} on a connector type with a {@link SqlDialect}
	 * (the dialect quotes the change column and says how the watermark reaches the statement). Otherwise the text says what the flow is,
	 * for the table's detail ({@code changeColumnIgnored}).
	 */
	public static String changeColumnIgnoredReason(final Table pTable)
	{
		if (pTable.recipe != null)
		{
			if (LandingFlow.recipeSql(pTable.recipe) == null)
			{
				return "this flow is not a SQL query (catalog recipe: operation '" + pTable.recipe.operation + "'): the table is reloaded in full";
			}
		}
		else if (pTable.fileFeed)
		{
			return "this flow is not a SQL query (file feed): the table is reloaded in full";
		}
		for (final SqlDialect dialect : LandingFlow.DIALECTS)
		{
			if (dialect.supports(pTable.connectorType))
			{
				return null;
			}
		}
		return "no SQL dialect for connector type '" + pTable.connectorType + "' to rewrite the query: the table is reloaded in full";
	}

	/**
	 * {@code (${watermark.isSet} = 0 or <col> >= <watermark>)}: inclusive, because {@code unchangedIfEqual} turns the rows read
	 * again into {@code NO_CHANGE} (Connectors docs/REFERENCE.md 2.14.7, "Boundary"); {@code ${watermark.isSet} = 0} on a first run
	 * or a full refresh reads EVERYTHING, rows whose change column is NULL included ({@code >=} alone never returns a NULL).
	 * <p>
	 * How the value reaches the statement depends on the connector, not on the table: a JDBC-family connector binds
	 * {@code ${watermark}} as a typed prepared-statement parameter ({@link SqlDialect#bindsWatermark}); Snowflake's SQL API receives
	 * it as TEXT, so a timestamp is written as a quoted literal (Snowflake coerces it) and a number bare. A STRING watermark is
	 * SOURCE data and the engine never escapes it, so it is refused for a connector that cannot bind - one hostile value in the
	 * change column would otherwise run as SQL.
	 */
	static String watermarkWhere(final SqlDialect pDialect, final String pQuotedColumn, final String pKind)
	{
		final String operand;
		if (pDialect.bindsWatermark())
		{
			operand = "${watermark}";
		}
		else if (ChangeColumnRule.ORDERED_STRING.equals(pKind))
		{
			throw new IllegalArgumentException("A text change column (ORDERED_STRING) cannot be used with " + pDialect.name()
				+ ": its watermark is written into the statement as text, and a value read from the source must never be. Pick a date or a number column.");
		}
		else if (ChangeColumnRule.TIMESTAMP.equals(pKind))
		{
			operand = "'${watermark}'";
		}
		else
		{
			operand = "${watermark}";
		}
		return "(${watermark.isSet} = 0 or " + pQuotedColumn + " >= " + operand + ")";
	}

	/**
	 * The feed mapping's {@code keyPredicate} (bug found live 2026-09-24, project Vague1, SF/SAP sample-file
	 * re-runs: {@code UowErrorCannotCreateSincePrimaryKeyUsed} on the second load of an already-loaded table).
	 * <p>
	 * The SQL path's {@code keyPredicate} is {@code "./_sourceId='${SOURCE_ID}'"}, and that placeholder resolves
	 * correctly there because {@code SOURCE_ID} is a real column of the generated {@code SELECT} - present,
	 * under that exact name, in the query result row {@code ConnectorService.resolvePredicate} substitutes from
	 * ({@code ebx-connectors}' {@code ConnectorService.java:1128}: {@code Json.at(pItem, m.group(1).trim())} reads
	 * the RAW item the connector produced, never a rule's computed output - {@code MappingEngine.compute} runs
	 * afterwards, at write time). A feed ({@code read} operation) row has no such field: it is the file's own
	 * columns only, so {@code ${SOURCE_ID}} always resolves to {@code ""}, the predicate is always
	 * {@code "./_sourceId=''"}, it never matches an existing record, and every re-run therefore INSERTS instead
	 * of UPDATING - colliding on the primary key the moment the same source row appears twice, exactly what
	 * the live error reported.
	 * <p>
	 * The fix: build the predicate from the RAW key column(s) directly, in the same order and joined with the
	 * same bare {@code '|'} the {@code concat(...)} rule transformation uses for {@code _sourceId} itself (see
	 * {@link #feedMapping}'s {@code sourceIdTransformation} - deliberately unescaped, same known gap as
	 * {@link #FILE_FEED_KEY_ESCAPE_GAP}), so the predicate reproduces exactly the value the rule will (re)compute
	 * for this row and the lookup finds it. Without any declared key, {@code _sourceId} is a hash of every column
	 * ({@code concat(...)|hash}); there is no {@code hash} available to a plain predicate template (it only
	 * substitutes raw item fields verbatim), so no equivalent predicate can be built - the mapping falls back to
	 * the un-lookupable placeholder text, same as before this fix: a feed table with no declared key still cannot
	 * be safely re-run without duplicating rows, a pre-existing, separate gap (see {@link #feedMapping}'s Javadoc
	 * on the missing "every key column NULL" fallback for the same family of limitation).
	 */
	private static String feedKeyPredicate(final List<String> pKeyColumnPaths)
	{
		if (pKeyColumnPaths.isEmpty())
		{
			return "./_sourceId='${SOURCE_ID}'";
		}
		final StringBuilder value = new StringBuilder();
		for (int i = 0; i < pKeyColumnPaths.size(); i++)
		{
			if (i > 0)
			{
				value.append('|');
			}
			value.append("${").append(pKeyColumnPaths.get(i)).append('}');
		}
		return "./_sourceId='" + value + "'";
	}

	/** {@code concat(path1,path2,...)}: the argument list of the {@code concat} rule transformation, in {@code pPaths}' order. */
	private static String concatOf(final List<String> pPaths)
	{
		final StringBuilder out = new StringBuilder("concat(");
		for (int i = 0; i < pPaths.size(); i++)
		{
			if (i > 0)
			{
				out.append(",");
			}
			out.append(pPaths.get(i));
		}
		return out.append(")").toString();
	}

	/**
	 * The {@code _sourceSystem} rule, identical between the SQL and feed mapping shapes: a plain constant, no
	 * source value read. {@code pSourceSystem} is not re-validated here the way a catalog-scanned identifier
	 * is: it is {@code ProjectSource.sourceSystem}, constrained by the XSD pattern {@code [A-Za-z][A-Za-z0-9_]*}
	 * (tools/build_xsd.py), so it can never contain {@code '$'}, {@code '{'}, a control character or a quote -
	 * safe by construction as the argument of a {@code constant(...)} pipeline.
	 */
	private static void addSourceSystemRule(final ArrayNode pRules, final String pSourceSystem)
	{
		LandingFlow.rule(pRules, "", "./_sourceSystem", "constant(" + pSourceSystem + ")", "ALWAYS");
	}

	/**
	 * The {@code _status} rule, identical between the SQL and feed mapping shapes.
	 * <p>
	 * <b>Changed from {@code IF_EMPTY} to {@code ALWAYS}, 2026-09-23 (the product owner's incremental-transfer brief,
	 * {@code docs/INCREMENTAL-TRANSFER.md}).</b> Under {@code IF_EMPTY}, a reload never touched a row a
	 * downstream transfer had already moved past {@code NEW} - which meant {@code _status} could only ever
	 * mean "has this row EVER been transferred", and {@code ebx.StagingLoader} had no way to tell "reloaded
	 * with new content" from "reloaded, identical" from "never reloaded again" - so it read every landing row
	 * on every run, full stop (this class' own {@code docs/LANDING-FLOWS.md} history). {@code ALWAYS} makes
	 * {@code _status} mean "version received, not yet transferred": every (re)received row goes back to
	 * {@code NEW}, whatever a previous transfer already did with it, and {@code STAGED}/{@code REJECTED}
	 * becomes the outcome of the LAST transfer of that version, not a permanent mark. A row rejected because
	 * the source data was bad is thus retried automatically the next time the source resends it (fixed or
	 * not) - no separate "retry rejected rows" action needed. {@code ebx.StagingLoader} reads {@code
	 * transfer.IncrementalTransfer#landingPredicate} to decide which rows a scope-{@code new} run even looks
	 * at, and {@code transfer.IncrementalTransfer#shouldWrite} to skip the actual staging write for a row
	 * whose content, despite being reloaded, did not change (see that class' own Javadoc). A landing table
	 * whose flow was generated before this change still carries the OLD {@code IF_EMPTY} rule until its flow
	 * is regenerated - {@code docs/INCREMENTAL-TRANSFER.md} spells out the one-time
	 * {@code POST /sources/{id}/landing/flows?force=true} a project needs to pick up this rule
	 * ({@code connectors_mapping_upsert} is idempotent on the mapping's label - or on its id, for a table that already
	 * owns one ({@link Table#existingMappingId}) - so forcing safely overwrites the mapping already written in place - {@code ebx.LandingFlows#generate}'s own Javadoc).
	 */
	private static void addStatusRule(final ArrayNode pRules)
	{
		LandingFlow.rule(pRules, "", "./_status", "constant(NEW)", "ALWAYS");
	}

	/**
	 * The {@code connectors_mapping_upsert} {@code mapping} document for a table whose connector has no SQL
	 * dialect but exposes a feed-style {@code read} operation instead ({@code Table#fileFeed}: an
	 * {@code AbstractFileConnector}, e.g. {@code sample-file}/{@code sftp}/{@code s3}/{@code azure-blob}/
	 * {@code gcs}/{@code ftp}): {@code operation: "read"}, {@code inputs.path} instead of {@code inputs.sql},
	 * and every technical value that the SQL path computes in the generated SQL text is instead built with the
	 * {@code ebx-connectors} rule-transformation primitives {@code hash}/{@code now}/{@code runid}/
	 * {@code concat} (see {@code docs/LANDING-FLOWS.md}'s "Gaps" section, which proposed exactly this fix).
	 * <p>
	 * A feed row's own JSON field names ARE the source column names directly (no SELECT aliasing needed to
	 * dodge a reserved SQL word the way the SQL path's {@code C1}/{@code C2}/... aliases do), so every rule's
	 * {@code sourcePath} is {@code column.sourceColumn} verbatim.
	 * <p>
	 * Two deliberate, documented differences from the SQL path - not oversights, see {@code docs/LANDING-FLOWS.md}:
	 * <ul>
	 * <li>No "every key column NULL" fallback: the SQL path's {@code sourceIdExpr} falls back to
	 * {@code '#' || <row hash>} when every declared key column is NULL (see the comment in {@link #mapping} for
	 * the full reasoning), but the rule-transformation pipeline has no conditional/branching primitive to build
	 * the same fallback with. A real fix needs a real conditional-transformation primitive - out of scope here;
	 * a declared key's {@code _sourceId} is always the plain concatenation, even when every key column happens
	 * to be NULL for a given row.</li>
	 * <li><b>No escaping of a composite key's parts either ({@link #FILE_FEED_KEY_ESCAPE_GAP}, composite-key
	 * lot, 2026-09-24)</b>: the SQL path escapes each part of a 2+ column key (backslash doubled, then
	 * {@code '|'} escaped) before {@code '|'}-joining it into {@code _sourceId}, so a genuine {@code '|'} or
	 * {@code '\'} in one column's value can never collide with the join separator (see the comment on
	 * {@code escapedKeyTextExprs} in {@link #mapping}). The {@code concat(path1,path2,...)} rule
	 * transformation this feed path uses instead has no equivalent: it resolves each argument path directly
	 * against the document root ({@code ebx-connectors}' {@code Transformations.java}, {@code register("concat", ...)})
	 * and joins the raw values with a fixed {@code '|'}, with no per-argument escaping step and no way to
	 * chain one in front of it (a rule's own {@code sourcePath}/pipeline stage is bypassed entirely - {@code
	 * concat} re-reads each path itself). <b>The fix belongs in {@code ebx-connectors}</b>, not here: either
	 * {@code register("concat", ...)} escapes each piece the same way before joining it (the smallest,
	 * most consistent fix - every {@code concat} caller gets it, not just landing), or a new
	 * {@code replace}-before-{@code concat} composition becomes possible by extending {@code concat} to
	 * accept a per-argument transformation suffix. Left deliberately unescaped here rather than worked
	 * around: a composite key on a feed-based (file/SFTP/S3/...) source whose values can contain {@code '|'}
	 * or {@code '\'} is exposed to the same collision the SQL path now prevents - see
	 * {@code docs/SOURCES-LANDING-KEY.md} for the full writeup.</li>
	 * <li>{@code _loadId}/{@code _receivedAt} no longer carry the factory's own client-minted values (compare the
	 * SQL path's {@code '${param.loadId}'}/{@code '${param.receivedAt}'} run-time input placeholders, embedded
	 * as literal text the source then echoes back as an ordinary result column - a mechanism that fundamentally
	 * requires a text template the connector evaluates server-side, which a file-read connector has no
	 * equivalent of). {@code runid} gives every row of one sync run the same value (a real {@code ebx-connectors}
	 * run id - a different id, and a different format, from {@link #loadId}, but the same "one value per load"
	 * property); {@code now} reads server time at rule-evaluation time, near-identical across the rows of one
	 * run. {@code LandingFlows.load}'s existing {@code loadId}/{@code receivedAt} minting and its {@code inputs}
	 * string stay exactly as they are: those two values simply go unread by a feed mapping, which
	 * {@code connectors_sync} tolerates silently (see the knowledge base).</li>
	 * </ul>
	 */
	private static ObjectNode feedMapping(final Table pTable)
	{
		final ObjectNode mapping = LandingFlow.MAPPER.createObjectNode();
		mapping.put("connector", pTable.connectorId);
		mapping.put("operation", "read");
		mapping.put("direction", "INBOUND");
		LandingFlow.identify(mapping, pTable);
		mapping.put("dataspace", pTable.landingDataspace);
		mapping.put("dataset", pTable.landingDataset);
		mapping.put("tablePath", "/root/" + pTable.landingTableName);
		mapping.put("active", true);
		mapping.put("itemsPath", "rows");
		mapping.put("createIfMissing", true);
		// volume-operations lot 8a (2026-09-28, task 6): see mapping()'s own note - same two properties, same reasoning.
		mapping.put("provenance", false);
		mapping.put("unchangedIfEqual", "./_rowHash");
		// AbstractFileConnector's "read" needs exactly one input, "path": pTable.sourceObject already holds the
		// catalog-scanned file path verbatim (e.g. "sample.csv") - passed through as-is, no transformation.
		mapping.putObject("inputs").put("path", pTable.sourceObject);

		final List<String> columnPaths = new ArrayList<String>();
		final List<String> keyColumnPaths = new ArrayList<String>();
		for (final Column column : pTable.columns)
		{
			columnPaths.add(column.sourceColumn);
			if (column.sourceKey)
			{
				keyColumnPaths.add(column.sourceColumn);
			}
		}
		mapping.put("keyPredicate", LandingFlow.feedKeyPredicate(keyColumnPaths));

		final ArrayNode rules = mapping.putArray("rules");
		for (final Column column : pTable.columns)
		{
			LandingFlow.rule(rules, column.sourceColumn, "./" + column.fieldName, "", "ALWAYS");
		}
		LandingFlow.addSourceSystemRule(rules, pTable.sourceSystem);
		LandingFlow.rule(rules, "", "./_loadId", "runid", "ALWAYS");
		LandingFlow.rule(rules, "", "./_receivedAt", "now", "ALWAYS");
		LandingFlow.rule(rules, "", "./_rowHash", LandingFlow.concatOf(columnPaths) + "|hash", "ALWAYS");
		// see this method's own Javadoc: unlike the SQL path's sourceIdExpr, there is deliberately no "every
		// key column NULL" fallback here - a declared key is always the plain concatenation.
		final String sourceIdTransformation = keyColumnPaths.isEmpty() ? LandingFlow.concatOf(columnPaths) + "|hash" : LandingFlow.concatOf(keyColumnPaths);
		LandingFlow.rule(rules, "", "./_sourceId", sourceIdTransformation, "ALWAYS");
		LandingFlow.addStatusRule(rules);

		return mapping;
	}

	/**
	 * Read-recipe lot (2026-09-24): the {@code connectors_mapping_upsert} {@code mapping} document built
	 * entirely from the catalog's {@link ReadRecipe} - {@code connector}/{@code operation}/{@code inputs}/
	 * {@code itemsPath} come from the recipe verbatim (the connector that catalogued the asset is the one
	 * that knows how to read it back, see {@code ReadRecipe}'s own Javadoc), never decided here by connector
	 * type. Structurally identical to {@link #feedMapping} otherwise - same rule-transformation technical
	 * fields ({@code hash}/{@code now}/{@code runid}/{@code concat}, no SQL text anywhere), same {@link
	 * #feedKeyPredicate} - because a recipe's operation answer is exactly the same shape a feed item is: a
	 * flat JSON row whose own field names ARE {@code column.sourceColumn} (contract: "source path = the
	 * column name as it appears in an item"), no SELECT aliasing to dodge. Verified against the live,
	 * hand-built Salesforce example (project Vague1, mapping 263, {@code connectors_mapping_get}): same
	 * {@code constant(<sourceSystem>)}/{@code runid}/{@code now}/{@code concat(...)|hash}/{@code
	 * concat(<key>)}/{@code constant(NEW)} rule shapes, byte for byte.
	 */
	private static ObjectNode recipeMapping(final Table pTable, final ReadRecipe pRecipe)
	{
		final ObjectNode mapping = LandingFlow.MAPPER.createObjectNode();
		mapping.put("connector", pRecipe.connector);
		mapping.put("operation", pRecipe.operation);
		mapping.put("direction", "INBOUND");
		LandingFlow.identify(mapping, pTable);
		mapping.put("dataspace", pTable.landingDataspace);
		mapping.put("dataset", pTable.landingDataset);
		mapping.put("tablePath", "/root/" + pTable.landingTableName);
		mapping.put("active", true);
		mapping.put("itemsPath", pRecipe.itemsPath);
		mapping.put("createIfMissing", true);
		// volume-operations lot 8a (2026-09-28, task 6): see mapping()'s own note - same two properties, same reasoning.
		mapping.put("provenance", false);
		mapping.put("unchangedIfEqual", "./_rowHash");
		final String changeColumn = pTable.changeColumn == null ? "" : pTable.changeColumn.trim();
		final boolean incremental = !changeColumn.isEmpty() && LandingFlow.changeColumnIgnoredReason(pTable) == null;
		final ObjectNode inputs = mapping.putObject("inputs");
		for (final java.util.Map.Entry<String, String> input : pRecipe.inputs.entrySet())
		{
			inputs.put(input.getKey(), input.getValue());
		}
		if (incremental)
		{
			// volume wave 2, lot V2-E (recipe gap, 2026-10-03): the recipe's own SQL is NOT parsed, it is wrapped as a sub-select, so
			// whatever the catalog wrote (quoting, joins, an ORDER BY) is read as is: select s.*, s."<col>" as CHANGE_VALUE from (<recipe sql>) s
			// where <watermark filter>. The recipe row keeps its own column names (the rules read them), plus CHANGE_VALUE for the checkpoint.
			final SqlDialect dialect = LandingFlow.dialectFor(pTable.connectorType);
			final ChangeFilter filter = LandingFlow.changeFilter(pTable, dialect, changeColumn);
			final String inner = LandingFlow.recipeSql(pRecipe).trim().replaceAll("[;\\s]+$", "");
			inputs.put("sql", "select s.*, " + filter.selectItem + " from (" + inner + ") s where " + filter.where);
			mapping.put("loadMode", "INCREMENTAL");
			mapping.put("checkpointStrategy", pTable.changeKind.trim());
			mapping.put("checkpointValuePath", LandingFlow.CHANGE_VALUE_ALIAS);
		}

		final List<String> columnPaths = new ArrayList<String>();
		final List<String> keyColumnPaths = new ArrayList<String>();
		for (final Column column : pTable.columns)
		{
			columnPaths.add(column.sourceColumn);
			if (column.sourceKey)
			{
				keyColumnPaths.add(column.sourceColumn);
			}
		}
		mapping.put("keyPredicate", LandingFlow.feedKeyPredicate(keyColumnPaths));

		final ArrayNode rules = mapping.putArray("rules");
		for (final Column column : pTable.columns)
		{
			LandingFlow.rule(rules, column.sourceColumn, "./" + column.fieldName, "", "ALWAYS");
		}
		LandingFlow.addSourceSystemRule(rules, pTable.sourceSystem);
		LandingFlow.rule(rules, "", "./_loadId", "runid", "ALWAYS");
		LandingFlow.rule(rules, "", "./_receivedAt", "now", "ALWAYS");
		LandingFlow.rule(rules, "", "./_rowHash", LandingFlow.concatOf(columnPaths) + "|hash", "ALWAYS");
		final String sourceIdTransformation = keyColumnPaths.isEmpty() ? LandingFlow.concatOf(columnPaths) + "|hash" : LandingFlow.concatOf(keyColumnPaths);
		LandingFlow.rule(rules, "", "./_sourceId", sourceIdTransformation, "ALWAYS");
		LandingFlow.addStatusRule(rules);

		return mapping;
	}

	/** {@code L<yyyyMMdd>-<HHmmss>-<landingTableId>}, UTC, from {@code pClock}: testable without the wall clock. */
	public static String loadId(final String pLandingTableId, final Clock pClock)
	{
		return "L" + LandingFlow.LOAD_ID_AT.format(pClock.instant()) + "-" + pLandingTableId;
	}

	/**
	 * The {@code receivedAt} run-time input: {@code pClock}'s own wall-clock time (its zone, not forced to
	 * UTC the way {@link #loadId} is), {@code yyyy-MM-dd'T'HH:mm:ss}, seconds always present. In production
	 * {@code LandingFlows.load} passes a clock in the EBX server's local zone, minted once per table load -
	 * the same clock instance {@link #loadId} is called with, so both values describe the same moment.
	 * Testable without the wall clock, same as {@link #loadId}.
	 */
	public static String receivedAt(final Clock pClock)
	{
		return LandingFlow.RECEIVED_AT_FORMAT.format(LocalDateTime.now(pClock));
	}

	/** What one {@code connectors_sync} feed answer means for a {@code LandingTable} row: pure, so this decision is unit-tested apart from the EBX write ({@code LandingFlows.writeLoadOutcome}). */
	public static final class LoadOutcome
	{
		/** Whether the load itself succeeded ({@code OK}/{@code PARTIAL}/{@code NO_DATA}); {@code false} only for {@code ERROR}. */
		public final boolean ok;
		/** {@code count}: rows the feed processed, whether or not each one changed anything. */
		public final long rows;
		public final String message;
		/** Volume wave 2, lot V2-E: what the run said about its watermark ({@link WatermarkOutcome#NONE} for a run that carried none). */
		public final WatermarkOutcome watermark;

		LoadOutcome(final boolean pOk, final long pRows, final String pMessage)
		{
			this(pOk, pRows, pMessage, WatermarkOutcome.NONE);
		}

		LoadOutcome(final boolean pOk, final long pRows, final String pMessage, final WatermarkOutcome pWatermark)
		{
			this.ok = pOk;
			this.rows = pRows;
			this.message = pMessage;
			this.watermark = pWatermark == null ? WatermarkOutcome.NONE : pWatermark;
		}
	}

	/**
	 * Same as {@link #loadOutcome(JsonNode, long)} with no source row count to compare against (the catalog
	 * scan path - {@code ebx.SourceOnboarder#runScan} - which is not a landing load and has no meaningful
	 * "row count last seen" to check the answer against). {@link LoadOutcomeRule}'s {@code truncated}/{@code
	 * available} check still applies: a catalog scan can be truncated too, and this is a strictly safe
	 * addition (a source row count of 0 makes {@link LoadOutcomeRule#decide} skip its own count comparison
	 * entirely).
	 */
	public static LoadOutcome loadOutcome(final JsonNode pSyncResult)
	{
		return LandingFlow.loadOutcome(pSyncResult, 0L);
	}

	/**
	 * Reads a {@code connectors_sync} feed answer ({@code {status, count, ok, errors, truncated, available,
	 * results[]...}}, see {@code docs/LANDING-FLOWS.md} §5.1) into what {@code LandingTable} should record.
	 * Every status but {@code ERROR} is treated as a load that HAPPENED (a {@code PARTIAL} or a legitimately
	 * empty {@code NO_DATA} source is still "loaded", with the detail in the message) - only {@code ERROR}
	 * (every row failed) turns the table's own outcome {@code false} on the connector's own say-so.
	 * <p>
	 * <b>Volume-guards lot (2026-09-28, task item 1):</b> even a connector-reported {@code OK}/{@code PARTIAL}/
	 * {@code NO_DATA} status can still be an INCOMPLETE read - see {@link LoadOutcomeRule}'s own Javadoc for the
	 * precedence between the source's own {@code truncated}/{@code available} fields and the fallback
	 * comparison against {@code pSourceRowCount}. A {@link LoadOutcomeRule.Level#FAILED} verdict turns this
	 * outcome {@code false} exactly like an {@code ERROR} status does, whatever the connector itself reported;
	 * a {@link LoadOutcomeRule.Level#WARN} verdict stays {@code ok=true} but says so in the message, the same
	 * posture {@code PARTIAL} already has.
	 *
	 * @param pSourceRowCount the catalog's last-known row count for this table ({@code LandingTable.sourceRowCount}),
	 *            0 or negative when never profiled or not applicable (the count-vs-catalog comparison is then
	 *            skipped, never guessed) - see {@link LoadOutcomeRule#decide}.
	 */
	public static LoadOutcome loadOutcome(final JsonNode pSyncResult, final long pSourceRowCount)
	{
		return LandingFlow.loadOutcome(pSyncResult, pSourceRowCount, false);
	}

	/**
	 * Same, for a run that may have read through a feed watermark (volume wave 2, lot V2-E, 2026-10-03): {@code
	 * pFullRefreshRequested} is whether the caller asked {@code connectors_sync} for {@code fullRefresh=true}.
	 * <ul>
	 * <li>An incremental run that started from a stored checkpoint reads only the rows CHANGED since: {@code count}
	 * of 0 out of 500,000 is a clean "nothing changed" ({@code NO_DATA}), never "incomplete" - {@link
	 * LoadOutcomeRule#decide(Boolean, Long, long, long, boolean, long)} skips the comparison with the catalog's row
	 * count for it, and weighs {@code available} against {@code count + skippedByWatermark}.</li>
	 * <li>{@code NO_DATA} that comes with errors is NOT a clean load whatever the engine meant by it: nothing was
	 * written and something went wrong, so it fails like {@code ERROR} does.</li>
	 * </ul>
	 */
	public static LoadOutcome loadOutcome(final JsonNode pSyncResult, final long pSourceRowCount, final boolean pFullRefreshRequested)
	{
		final JsonNode result = pSyncResult == null ? LandingFlow.MAPPER.createObjectNode() : pSyncResult;
		final String status = result.path("status").asText("");
		final long rows = result.path("count").asLong(0);
		final WatermarkOutcome watermark = WatermarkOutcome.fromSync(result, pFullRefreshRequested);
		if ("ERROR".equals(status) && rows == 0)
		{
			// Connectors audit decision 4 (2026-10-06): a source read that fails ends ERROR with errors=1 and count=0
			final String message = "Load failed: the source could not be read (" + LandingFlow.firstError(result) + ").";
			return new LoadOutcome(false, rows, message, watermark);
		}
		if ("ERROR".equals(status))
		{
			final String message = "Load failed: " + result.path("errors").asInt(0) + " of " + rows + " row(s) in error (" + LandingFlow.firstError(result) + ").";
			return new LoadOutcome(false, rows, message, watermark);
		}
		if ("NO_DATA".equals(status) && result.path("errors").asInt(0) > 0)
		{
			final String message = "Load failed: no row was read and " + result.path("errors").asInt(0) + " error(s) were reported (" + LandingFlow.firstError(result) + ").";
			return new LoadOutcome(false, rows, message, watermark);
		}
		final StringBuilder message = new StringBuilder("Loaded ").append(result.path("ok").asInt(0)).append(" of ").append(rows).append(" row(s)");
		if ("PARTIAL".equals(status))
		{
			message.append(" (").append(result.path("errors").asInt(0)).append(" error(s): ").append(LandingFlow.firstError(result)).append(")");
		}
		else if ("NO_DATA".equals(status))
		{
			message.append(watermark.filtered() ? " (no row changed at the source since the watermark)" : " (no data returned by the source)");
		}
		LandingFlow.appendWatermark(message, watermark);

		// volume-guards lot (2026-09-28, task item 1): a truncated or otherwise incomplete read fails the load
		// outright, whatever the connector's own status said - see LoadOutcomeRule's own Javadoc.
		final Boolean truncated = result.hasNonNull("truncated") ? Boolean.valueOf(result.path("truncated").asBoolean()) : null;
		final Long available = result.hasNonNull("available") ? Long.valueOf(result.path("available").asLong()) : null;
		final LoadOutcomeRule.Decision decision = LoadOutcomeRule.decide(truncated, available, rows, watermark.skipped, watermark.filtered(), pSourceRowCount);
		if (decision.level == LoadOutcomeRule.Level.FAILED)
		{
			message.append(" - FAILED: ").append(decision.reason).append('.');
			return new LoadOutcome(false, rows, message.toString(), watermark);
		}
		if (decision.level == LoadOutcomeRule.Level.WARN)
		{
			message.append(" - WARNING: ").append(decision.reason).append('.');
			return new LoadOutcome(true, rows, message.toString(), watermark);
		}
		message.append('.');
		return new LoadOutcome(true, rows, message.toString(), watermark);
	}

	/** {@code " - <watermark tail>"} when the run carried a watermark, nothing otherwise: shared by the synchronous and the polled outcome so both read the same. */
	static void appendWatermark(final StringBuilder pMessage, final WatermarkOutcome pWatermark)
	{
		final String tail = pWatermark.describe();
		if (!tail.isEmpty())
		{
			pMessage.append(" - ").append(tail);
		}
	}

	/** The first per-row reason in a connectors_sync response worth surfacing (its own errors[], else a non-OK/non-NO_CHANGE message); "see detail" when nothing more specific is found. */
	private static String firstError(final JsonNode pResult)
	{
		for (final JsonNode item : pResult.path("results"))
		{
			if (item.path("errors").isArray() && item.path("errors").size() > 0)
			{
				return item.path("errors").get(0).asText();
			}
		}
		for (final JsonNode item : pResult.path("results"))
		{
			final String status = item.path("status").asText("");
			final String message = item.path("message").asText(null);
			if (message != null && !message.trim().isEmpty() && !"OK".equals(status) && !"NO_CHANGE".equals(status))
			{
				return message;
			}
		}
		return "see detail";
	}

	// ------------------------------------------------------------------------------------------------- SQL

	/**
	 * Refuses a catalog-scanned identifier (a source column, or the qualified source object naming a table,
	 * schema and database together) that could not survive being embedded in the generated SQL:
	 * <ul>
	 * <li>{@code ${...}} - ebx-connectors resolves placeholders in a mapping's {@code inputs} <i>text</i>
	 * (including our {@code sql} input) before running it, generically, for every {@code ${...}} found, not
	 * only the {@code ${param.loadId}} this class itself writes: {@code ConnectorService.java:775}
	 * ({@code inputs.put(input.getKey(), Expressions.resolve(input.getValue(), null, pExtraInputs).trim());}
	 * in the feed/query execution path), with the placeholder syntax and the nested-brace-aware resolution
	 * loop in {@code Expressions.java:24} ({@code PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}")}) and
	 * {@code Expressions.java:74-124}. An identifier containing {@code ${} would therefore be silently
	 * rewritten inside our own quoting (an unresolved {@code param.} key becomes the empty string,
	 * {@code Expressions.java:112-115}) rather than sent to Snowflake as the literal text it actually is.
	 * Checked, deliberately: {@code label} is stored as-is and never templated at sync time
	 * (see {@code ConfigTransfer.importMapping}); {@code keyPredicate} is resolved by a separate function
	 * reading placeholders from the <i>query result row's own JSON</i>, not from any identifier text
	 * ({@code ConnectorService.resolvePredicate}, {@code ConnectorService.java:1128-1144}); a rule's
	 * {@code constant(...)} argument only ever carries {@code pTable.sourceSystem} or a landing field name,
	 * both safe by construction (see the comment on the {@code _sourceSystem} rule in {@link #mapping}) - so
	 * only the SQL text itself needs this guard.</li>
	 * <li>a control character (below {@code 0x20}, NUL included) - no legitimate source identifier needs one,
	 * and one inside a quoted Snowflake identifier is exactly the kind of thing that reads differently to a
	 * human reviewing the generated SQL than to the engine executing it.</li>
	 * </ul>
	 */
	private static void requireSafeIdentifier(final String pIdentifier, final String pWhat)
	{
		final String identifier = pIdentifier == null ? "" : pIdentifier;
		if (identifier.contains("${"))
		{
			throw new IllegalArgumentException(
				pWhat + " '" + identifier + "' contains '${': ebx-connectors would substitute it as a placeholder in the generated SQL "
					+ "text before running it (see LandingFlow.requireSafeIdentifier's Javadoc for the evidence) - refused rather than silently corrupted.");
		}
		for (int i = 0; i < identifier.length(); i++)
		{
			final char c = identifier.charAt(i);
			if (c < 0x20)
			{
				throw new IllegalArgumentException(
					pWhat + " '" + identifier + "' contains a control character (0x" + Integer.toHexString(c) + "): refused.");
			}
		}
	}

	/** {@code COALESCE(expr,'')} of every expression, joined with {@code '|'}: what {@code _sourceId} (with a key) and {@code _rowHash} are built from. */
	private static String concatCoalesce(final List<String> pExprs)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pExprs.size(); i++)
		{
			if (i > 0)
			{
				out.append(" || '|' || ");
			}
			out.append("COALESCE(").append(pExprs.get(i)).append(",'')");
		}
		return out.toString();
	}

	/**
	 * {@code select * from <source object>} in {@code pConnectorType}'s dialect, for a short live sample of a
	 * SQL source ({@code ebx.LandingPreview}); the row count is bounded by the {@code query} operation's own
	 * {@code max} input, never by dialect-specific SQL. Refuses an unsupported type like {@link #dialectFor}.
	 */
	public static String selectAll(final String pConnectorType, final String pSourceObject)
	{
		return "select * from " + LandingFlow.fromClause(LandingFlow.dialectFor(pConnectorType), pSourceObject);
	}

	/**
	 * Volume-guards lot (2026-09-28, task item 4, "landing key applied only if unique at the source"):
	 * {@code select count(*) as totalCount, count(distinct <key expr>) as distinctCount from <source object>}
	 * in {@code pConnectorType}'s dialect - what {@code ebx.KeySourceUniqueness} runs (read-only, through the
	 * connector's own {@code query} operation) to verify a {@code RULE}/{@code LLM}-proposed key is actually
	 * unique across the WHOLE source before {@code ebx.LandingRun} auto-applies it, not merely across {@code
	 * KeyStudy.SAMPLE_MAX} (25) sample rows - a key unique in 25 rows can still collide at millions.
	 * <p>
	 * A single-column key is the column itself, quoted; a composite key concatenates every part with the same
	 * {@code COALESCE(...,'') || '|' || ...} convention {@code _sourceId} itself is built from ({@link
	 * #concatCoalesce}) - deliberately WITHOUT {@link SqlDialect#escapeKeyPart}, unlike the real {@code
	 * _sourceId}: this expression is used only to compare {@code COUNT(DISTINCT ...)} against {@code COUNT(*)},
	 * never persisted or used to look a row up, so the rare pathological collision escaping would prevent
	 * (a value already containing the {@code '|'} separator) would only make this specific check slightly
	 * optimistic about a key that is, separately, still checked unique on the {@link KeyStudy#SAMPLE_MAX}-row
	 * sample with escaping applied ({@code landing.KeyEvidence}) - never a silent data-corruption risk the way
	 * an unescaped real {@code _sourceId} would be.
	 * <p>
	 * Refuses an unsupported connector type like {@link #dialectFor} (the caller falls back to a bounded full
	 * read for a file/recipe source instead - see {@code ebx.KeySourceUniqueness}'s own Javadoc); refuses an
	 * empty {@code pKeyColumns} outright (nothing to check).
	 */
	public static String uniquenessCheckSql(final String pConnectorType, final String pSourceObject, final List<String> pKeyColumns)
	{
		if (pKeyColumns == null || pKeyColumns.isEmpty())
		{
			throw new IllegalArgumentException("uniquenessCheckSql needs at least one key column.");
		}
		final SqlDialect dialect = LandingFlow.dialectFor(pConnectorType);
		final List<String> exprs = new ArrayList<String>();
		for (final String column : pKeyColumns)
		{
			LandingFlow.requireSafeIdentifier(column, "Key column");
			exprs.add(dialect.quoteIdentifier(column));
		}
		final String keyExpr = exprs.size() == 1 ? exprs.get(0) : LandingFlow.concatCoalesce(exprs);
		return "select count(*) as totalCount, count(distinct " + keyExpr + ") as distinctCount from " + LandingFlow.fromClause(dialect, pSourceObject);
	}

	/** Every dot-separated part of a qualified source object name, quoted individually and rejoined: never a single opaque identifier that could carry SQL of its own. */
	private static String fromClause(final SqlDialect pDialect, final String pSourceObject)
	{
		final String[] parts = (pSourceObject == null ? "" : pSourceObject).split("[.]");
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < parts.length; i++)
		{
			if (i > 0)
			{
				out.append(".");
			}
			out.append(pDialect.quoteIdentifier(parts[i]));
		}
		return out.toString();
	}

	private static void rule(final ArrayNode pRules, final String pSourcePath, final String pTargetPath, final String pTransformation, final String pPolicy)
	{
		final ObjectNode rule = pRules.addObject();
		rule.put("sourcePath", pSourcePath);
		rule.put("targetPath", pTargetPath);
		rule.put("transformation", pTransformation);
		rule.put("policy", pPolicy);
	}
}
