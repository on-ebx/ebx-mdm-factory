package com.on.ebx.factory.landing;

/**
 * H2 2.x through {@code ebx-connectors}' JDBC {@code query} operation ({@code H2Connector}, type {@code "h2"}).
 * Every primitive was run against a real H2 2.1.212 server before being written (PersVol volume test,
 * 2026-09-24): {@code CAST(x AS VARCHAR)} renders a DATE as {@code 1979-09-07} and a TIMESTAMP as
 * {@code 2026-04-09 10:38:00} (the same text Snowflake's {@code TO_VARCHAR} gives), and
 * {@code LOWER(RAWTOHEX(HASH('MD5', x)))} is the MD5 of the UTF-8 text, lower-case hex - byte for byte what
 * Snowflake's {@code MD5(x)} answers, so a {@code _rowHash} means the same thing whatever the source.
 */
public final class H2Dialect implements SqlDialect
{
	@Override
	public String name()
	{
		return "H2";
	}

	@Override
	public boolean supports(final String pConnectorType)
	{
		return pConnectorType != null && "h2".equalsIgnoreCase(pConnectorType.trim());
	}

	@Override
	public String quoteIdentifier(final String pIdentifier)
	{
		// same rule as SnowflakeDialect: always quoted (H2 upper-cases an unquoted name exactly like the catalog
		// stores it, and reserved words - VALUE, USER, KEY... - fail bare), an embedded double quote doubled.
		final String identifier = pIdentifier == null ? "" : pIdentifier;
		return '"' + identifier.replace("\"", "\"\"") + '"';
	}

	@Override
	public String asText(final String pExpr)
	{
		return "CAST(" + pExpr + " AS VARCHAR)";
	}

	@Override
	public String hash(final String pExpr)
	{
		return "LOWER(RAWTOHEX(HASH('MD5', " + pExpr + ")))";
	}

	@Override
	public String escapeKeyPart(final String pExpr)
	{
		// CHAR(92) = '\', CHAR(124) = '|': H2 string literals have no backslash escaping, but the numeric form
		// keeps every dialect's escapeKeyPart written the same way (see SqlDialect#escapeKeyPart).
		final String backslash = "CHAR(92)";
		final String pipe = "CHAR(124)";
		return "REPLACE(REPLACE(" + pExpr + ", " + backslash + ", " + backslash + " || " + backslash + "), " + pipe + ", " + backslash + " || " + pipe + ")";
	}
}
