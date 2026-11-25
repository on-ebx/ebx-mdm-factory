package com.on.ebx.factory.landing;

/**
 * PostgreSQL and Greenplum (its fork, same SQL) through {@code ebx-connectors}' JDBC {@code query} operation
 * (types {@code "postgresql"} and {@code "greenplum"}). Standard PostgreSQL functions only: {@code CAST(x AS
 * VARCHAR)} (ISO text for DATE/TIMESTAMP), {@code MD5(text)} (lower-case hex, as Snowflake's), {@code CHR(n)}.
 * Written from the PostgreSQL documentation and unit-tested on the generated text; not yet run against a live
 * PostgreSQL server (none on this instance) - {@link H2Dialect} is the one measured live.
 */
public final class PostgreSqlDialect implements SqlDialect
{
	@Override
	public String name()
	{
		return "PostgreSQL";
	}

	@Override
	public boolean supports(final String pConnectorType)
	{
		if (pConnectorType == null)
		{
			return false;
		}
		final String type = pConnectorType.trim();
		return "postgresql".equalsIgnoreCase(type) || "greenplum".equalsIgnoreCase(type);
	}

	@Override
	public String quoteIdentifier(final String pIdentifier)
	{
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
		return "MD5(" + pExpr + ")";
	}

	@Override
	public String escapeKeyPart(final String pExpr)
	{
		final String backslash = "CHR(92)";
		final String pipe = "CHR(124)";
		return "REPLACE(REPLACE(" + pExpr + ", " + backslash + ", " + backslash + " || " + backslash + "), " + pipe + ", " + backslash + " || " + pipe + ")";
	}
}
