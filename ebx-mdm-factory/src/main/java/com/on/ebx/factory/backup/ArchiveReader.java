package com.on.ebx.factory.backup;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Walks a project backup ({@link BackupFormat}) as a stream and tells a {@link Handler} what it meets, one table and one row at a time - never the
 * whole document in memory (audit F-30, wave 10). Pure (Jackson only), so the token handling is tested offline with archives written by hand.
 * <p>
 * The header fields ({@code format}, {@code formatVersion}, {@code project}) are expected before {@code tables}: {@link Handler#header} is called once,
 * just before the first table (or at the end when the archive has none), so the handler can refuse the archive before it reads a row. Every other
 * top-level field is handed over as a tree through {@link Handler#field}.
 */
public final class ArchiveReader
{
	private ArchiveReader()
	{
	}

	/** What the reader reports. Any exception stops the walk and goes back to the caller. */
	public interface Handler
	{
		void header(String pFormat, Integer pVersion, String pProject) throws Exception;

		void tableStart(String pTable) throws Exception;

		void row(String pTable, JsonNode pRow) throws Exception;

		/** A top-level field other than the header and {@code tables}: {@code createdAt}, {@code references}, {@code complete}... */
		void field(String pName, JsonNode pValue) throws Exception;
	}

	/** The reader's own refusal: the document is not shaped like an archive. */
	public static final class Malformed extends IOException
	{
		private static final long serialVersionUID = 1L;

		Malformed(final String pMessage)
		{
			super(pMessage);
		}
	}

	/** {@code pParser} must have a codec ({@code setCodec}); it is read to the end of the root object. */
	public static void read(final JsonParser pParser, final Handler pHandler) throws Exception
	{
		if (pParser.nextToken() != JsonToken.START_OBJECT)
		{
			throw new Malformed("The archive is not a JSON object.");
		}
		String format = null;
		Integer version = null;
		String project = null;
		boolean headerSent = false;
		while (pParser.nextToken() == JsonToken.FIELD_NAME)
		{
			final String field = pParser.getCurrentName();
			pParser.nextToken();
			if ("tables".equals(field))
			{
				if (!headerSent)
				{
					pHandler.header(format, version, project);
					headerSent = true;
				}
				ArchiveReader.tables(pParser, pHandler);
				continue;
			}
			final JsonNode node = pParser.readValueAsTree();
			if ("format".equals(field))
			{
				format = node.asText();
			}
			else if ("formatVersion".equals(field))
			{
				version = node.isInt() ? Integer.valueOf(node.asInt()) : null;
			}
			else if ("project".equals(field))
			{
				project = node.asText();
			}
			else
			{
				pHandler.field(field, node);
			}
		}
		if (pParser.currentToken() != JsonToken.END_OBJECT)
		{
			throw new Malformed("The archive ends before its closing brace: the file is cut short.");
		}
		if (!headerSent)
		{
			pHandler.header(format, version, project);
		}
	}

	private static void tables(final JsonParser pParser, final Handler pHandler) throws Exception
	{
		if (pParser.currentToken() != JsonToken.START_ARRAY)
		{
			throw new Malformed("The archive's 'tables' is not a list.");
		}
		while (pParser.nextToken() == JsonToken.START_OBJECT)
		{
			String table = null;
			while (pParser.nextToken() == JsonToken.FIELD_NAME)
			{
				final String field = pParser.getCurrentName();
				pParser.nextToken();
				if ("table".equals(field))
				{
					table = pParser.getText();
					pHandler.tableStart(table);
				}
				else if ("rows".equals(field))
				{
					if (table == null)
					{
						throw new Malformed("A table of the archive has its rows before its name.");
					}
					if (pParser.currentToken() != JsonToken.START_ARRAY)
					{
						throw new Malformed("The rows of table '" + table + "' are not a list.");
					}
					while (pParser.nextToken() == JsonToken.START_OBJECT)
					{
						pHandler.row(table, pParser.<JsonNode> readValueAsTree());
					}
				}
				else
				{
					pParser.skipChildren();
				}
			}
		}
	}
}
