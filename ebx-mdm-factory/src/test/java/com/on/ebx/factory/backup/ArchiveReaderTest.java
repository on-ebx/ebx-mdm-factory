package com.on.ebx.factory.backup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free: run the main, a failure throws. Audit F-30 (wave 10): the stream walk of a project backup, on archives written by hand. */
public final class ArchiveReaderTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final class Seen implements ArchiveReader.Handler
	{
		final List<String> events = new ArrayList<String>();

		@Override
		public void header(final String pFormat, final Integer pVersion, final String pProject)
		{
			this.events.add("header:" + pFormat + ":" + pVersion + ":" + pProject);
		}

		@Override
		public void tableStart(final String pTable)
		{
			this.events.add("table:" + pTable);
		}

		@Override
		public void row(final String pTable, final JsonNode pRow)
		{
			this.events.add("row:" + pTable + ":" + pRow.path("key").asText() + ":" + pRow.path("values").size());
		}

		@Override
		public void field(final String pName, final JsonNode pValue)
		{
			this.events.add("field:" + pName + ":" + (pValue.isValueNode() ? pValue.asText() : pValue.size() + " items"));
		}
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String good = "{\"format\":\"mdm-factory-project-backup\",\"formatVersion\":1,\"project\":\"P\",\"createdAt\":\"2026-10-07T09:00:00.000\",\"excluded\":[],"
			+ "\"tables\":[{\"table\":\"Project\",\"path\":\"/root/Project\",\"rows\":[{\"key\":\"P\",\"values\":{\"code\":\"P\",\"label\":\"x\"}}]},"
			+ "{\"table\":\"ProjectSource\",\"path\":\"/root/ProjectSource\",\"rows\":[{\"key\":\"1\",\"values\":{\"project\":\"P\"}},{\"key\":\"2\",\"values\":{}}]},"
			+ "{\"table\":\"Xref\",\"path\":\"/root/Xref\",\"rows\":[]}],"
			+ "\"rowCounts\":{\"Project\":1},\"references\":[{\"kind\":\"k\",\"name\":\"n\",\"detail\":\"d\"}],\"unknownFutureField\":{\"a\":1},\"complete\":true}";
		final Seen seen = ArchiveReaderTest.run(good);
		ArchiveReaderTest.check("events: " + seen.events, seen.events.equals(java.util.Arrays.asList("field:createdAt:2026-10-07T09:00:00.000", "field:excluded:0 items",
			"header:mdm-factory-project-backup:1:P", "table:Project", "row:Project:P:2", "table:ProjectSource", "row:ProjectSource:1:1", "row:ProjectSource:2:0", "table:Xref",
			"field:rowCounts:1 items", "field:references:1 items", "field:unknownFutureField:1 items", "field:complete:true")));

		final Seen noTables = ArchiveReaderTest.run("{\"format\":\"x\",\"formatVersion\":1,\"project\":\"P\",\"complete\":true}");
		ArchiveReaderTest.check("an archive without tables still reports its header, last: " + noTables.events, noTables.events.get(noTables.events.size() - 1).startsWith("header:x:1:P"));

		ArchiveReaderTest.refused("not an object", "[1,2]");
		ArchiveReaderTest.refused("cut short in a row", good.substring(0, good.indexOf("ProjectSource") + 20));
		ArchiveReaderTest.refused("cut after the tables", good.substring(0, good.indexOf("\"rowCounts\"") - 1));
		ArchiveReaderTest.refused("rows before the name", "{\"format\":\"f\",\"formatVersion\":1,\"project\":\"P\",\"tables\":[{\"rows\":[],\"table\":\"Project\"}]}");
		ArchiveReaderTest.refused("tables is not a list", "{\"format\":\"f\",\"formatVersion\":1,\"project\":\"P\",\"tables\":{}}");
		ArchiveReaderTest.refused("rows is not a list", "{\"format\":\"f\",\"formatVersion\":1,\"project\":\"P\",\"tables\":[{\"table\":\"Project\",\"rows\":{}}]}");

		// a handler's refusal of the header stops the walk before any row
		final List<String> rows = new ArrayList<String>();
		boolean stopped = false;
		try
		{
			final JsonParser parser = ArchiveReaderTest.MAPPER.getFactory().createParser(good);
			parser.setCodec(ArchiveReaderTest.MAPPER);
			ArchiveReader.read(parser, new ArchiveReader.Handler()
			{
				@Override
				public void header(final String pFormat, final Integer pVersion, final String pProject) throws Exception
				{
					throw new IllegalStateException("refused");
				}

				@Override
				public void tableStart(final String pTable)
				{
					rows.add(pTable);
				}

				@Override
				public void row(final String pTable, final JsonNode pRow)
				{
					rows.add(pTable);
				}

				@Override
				public void field(final String pName, final JsonNode pValue)
				{
					// not interesting here
				}
			});
		}
		catch (final IllegalStateException ex)
		{
			stopped = true;
		}
		ArchiveReaderTest.check("a refused header stops before any table or row", stopped && rows.isEmpty());
		System.out.println("ArchiveReaderTest OK");
	}

	private static Seen run(final String pJson) throws Exception
	{
		final Seen seen = new Seen();
		final JsonParser parser = ArchiveReaderTest.MAPPER.getFactory().createParser(pJson);
		parser.setCodec(ArchiveReaderTest.MAPPER);
		ArchiveReader.read(parser, seen);
		return seen;
	}

	private static void refused(final String pWhat, final String pJson)
	{
		try
		{
			ArchiveReaderTest.run(pJson);
		}
		catch (final IOException ex)
		{
			return;
		}
		catch (final Exception ex)
		{
			throw new AssertionError(pWhat + ": unexpected " + ex);
		}
		throw new AssertionError(pWhat + ": was accepted");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
