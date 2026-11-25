package com.on.ebx.factory.landing;

/**
 * The handful of SQL primitives {@link LandingFlow} needs to compute, in the source's own SQL, the
 * technical fields no {@code ebx-connectors} rule transformation can build on its own (a composite
 * {@code _sourceId}, {@code _rowHash}, and quoting a source identifier safely) - see
 * {@code docs/LANDING-FLOWS.md} §3 for why each of these has no rule-transformation equivalent today.
 * {@code _receivedAt} is minted by the factory itself, not computed in SQL (see {@link LandingFlow#receivedAt}),
 * so it needs no dialect primitive at all. Deliberately small and closed over that one need, not a general
 * SQL builder: a new source family gets a new implementation, {@link LandingFlow#dialectFor(String)} is the
 * only place that chooses between them.
 */
public interface SqlDialect
{
	/**
	 * Whether the connector this dialect serves binds {@code ${watermark}} as a typed prepared-statement parameter (every
	 * JDBC-family connector: {@code JdbcConnector.bindsWatermark}, Connectors lot V2-D) - the default - or receives it as
	 * plain text written into the statement (Snowflake's SQL API), which {@link LandingFlow#watermarkWhere} then has to
	 * quote itself. Volume wave 2, lot V2-E.
	 */
	default boolean bindsWatermark()
	{
		return true;
	}

	/** A name for error messages ({@code "no landing SQL generator for connector type X: supported types are ..."}). */
	String name();

	/** Whether this dialect handles a connector of this type, as {@code connectors_describe} reports it ({@code type}); case-insensitive. */
	boolean supports(String pConnectorType);

	/**
	 * A source identifier (a column or a table/schema/database name), always quoted: a name that would read
	 * as a plain, unquoted-safe identifier can still be a reserved word (Snowflake's {@code ORDER},
	 * {@code GROUP}...) and fail to parse unquoted, so nothing is ever emitted bare. Any character the
	 * quoting itself uses is escaped so the identifier can never end the quoting early and let the rest of
	 * its text run as SQL. The catalog's column and table names are untrusted input (scanned from a live
	 * source, not written by the factory): this is the one function that stands between them and the
	 * generated statement.
	 */
	String quoteIdentifier(String pIdentifier);

	/** {@code pExpr} cast to text, so a numeric or date/time source column is not silently reformatted by the query engine's own coercion (see the knowledge base). */
	String asText(String pExpr);

	/** A hash of {@code pExpr}, stable and different whenever the row's data is: what {@code _rowHash} (and, without a source key, {@code _sourceId}) is built from. */
	String hash(String pExpr);

	/**
	 * {@code pExpr} (already text) with every backslash doubled, then every {@code '|'} escaped with a
	 * leading backslash - the same convention EBX's own {@code PrimaryKey} uses to make a composite key's
	 * string form unambiguous (CLAUDE.md §9, "Never build or split a composite primary key as a string").
	 * {@link LandingFlow#mapping} runs this on every part of a composite (2+ column) source key BEFORE
	 * {@code COALESCE}/{@code '|'}-joining it into {@code _sourceId}, so a {@code '|'} or {@code '\'}
	 * genuinely present in one column's value can never be mistaken for the separator between key parts -
	 * without it, {@code "A|B"} in one column plus {@code "C"} in the next produces the exact same
	 * {@code _sourceId} as {@code "A"} plus {@code "B|C"}, a real collision (composite-key lot, 2026-09-24).
	 * <p>
	 * Implementations should build the two nested {@code REPLACE} calls with a numeric character function
	 * (Snowflake's {@code CHR(92)}/{@code CHR(124)}) rather than a backslash/pipe character written directly
	 * inside a SQL string literal: Snowflake treats a backslash as an escape character INSIDE a
	 * single-quoted string constant by default (its "String Constants" documentation), so a literal
	 * one-backslash-character search/replacement argument cannot be written reliably as {@code '\'} - it
	 * would itself need escaping, and the doubled form this method computes would need doubling again just
	 * to survive being written as a SQL literal. {@code CHR(...)} sidesteps that entirely: the argument is a
	 * small integer, never a character a string literal's own escaping rules could reinterpret.
	 */
	String escapeKeyPart(String pExpr);
}
