package com.on.ebx.factory.landing;

/**
 * Snowflake through its SQL REST API {@code query} operation (see {@code ebx-connectors}'
 * {@code SnowflakeConnector}, whose {@code getType()} answers {@code "snowflake"}: that is the string
 * {@link #supports} compares against, not the connector's own id, which the factory leaves free-form).
 */
public final class SnowflakeDialect implements SqlDialect
{
	@Override
	public String name()
	{
		return "Snowflake";
	}

	@Override
	public boolean supports(final String pConnectorType)
	{
		return pConnectorType != null && "snowflake".equalsIgnoreCase(pConnectorType.trim());
	}

	@Override
	public boolean bindsWatermark()
	{
		// the SQL API connector is not a JDBC one: ${watermark} is written into the statement as text (Connectors lot V2-D)
		return false;
	}

	@Override
	public String quoteIdentifier(final String pIdentifier)
	{
		// always quoted, never left bare: a name that would otherwise read as a plain, unquoted-safe
		// identifier can still be a Snowflake reserved word (ORDER, FROM, TABLE, GROUP, START...) and fail to
		// parse unquoted. The catalog holds names exactly as Snowflake's information schema stores them
		// (upper case for an object created unquoted, exact case otherwise), so the quoted form is always the
		// correct one to send back, not merely a safer one. A double quote inside the identifier is doubled,
		// exactly as Snowflake's own quoted-identifier escaping works: this is what keeps a hostile
		// catalog-scanned name (e.g. containing '"; drop table ...') from ever closing the quoting early and
		// running as SQL of its own.
		final String identifier = pIdentifier == null ? "" : pIdentifier;
		return '"' + identifier.replace("\"", "\"\"") + '"';
	}

	@Override
	public String asText(final String pExpr)
	{
		return "TO_VARCHAR(" + pExpr + ")";
	}

	@Override
	public String hash(final String pExpr)
	{
		return "MD5(" + pExpr + ")";
	}

	@Override
	public String escapeKeyPart(final String pExpr)
	{
		// CHR(92) = '\', CHR(124) = '|' - see SqlDialect#escapeKeyPart's Javadoc for why these are built with
		// CHR(...) rather than a backslash/pipe written directly inside a SQL string literal (Snowflake's own
		// default backslash-escaping of string constants would otherwise have to be escaped itself). Backslash
		// doubled FIRST, then '|' escaped with a leading backslash - reversing the order would double the
		// backslash the pipe-escape just added.
		final String backslash = "CHR(92)";
		final String pipe = "CHR(124)";
		return "REPLACE(REPLACE(" + pExpr + ", " + backslash + ", " + backslash + " || " + backslash + "), " + pipe + ", " + backslash + " || " + pipe + ")";
	}
}
