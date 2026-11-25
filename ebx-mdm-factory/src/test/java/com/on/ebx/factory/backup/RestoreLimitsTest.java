package com.on.ebx.factory.backup;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free: run the main, a failure throws. F2-11 (1.4.2): the two bounds of a project restore - parsing, the byte-bounded stream, the status block, the wiring. */
public final class RestoreLimitsTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		RestoreLimitsTest.check("default when absent, unreadable, zero or negative", RestoreLimits.parse(null, 7L) == 7L && RestoreLimits.parse("abc", 7L) == 7L
			&& RestoreLimits.parse("0", 7L) == 7L && RestoreLimits.parse("-5", 7L) == 7L && RestoreLimits.parse("", 7L) == 7L);
		RestoreLimitsTest.check("a positive number is taken, spaces ignored", RestoreLimits.parse(" 12 ", 7L) == 12L);
		RestoreLimitsTest.check("defaults are wide: 2 GiB and 5 million rows", RestoreLimits.DEFAULT_MAX_BYTES == 2L * 1024L * 1024L * 1024L && RestoreLimits.DEFAULT_MAX_ROWS == 5000000L);

		// a stream under the bound is read whole; one byte over is refused with the typed exception, also through Jackson's own wrapping
		final byte[] json = "{\"format\":\"x\",\"tables\":[{\"table\":\"Project\",\"rows\":[]}]}".getBytes(StandardCharsets.UTF_8);
		final RestoreLimits.Bounded fits = new RestoreLimits.Bounded(new ByteArrayInputStream(json), json.length);
		int count = 0;
		while (fits.read() >= 0)
		{
			count++;
		}
		RestoreLimitsTest.check("a stream of exactly the bound is read whole", count == json.length && fits.bytesRead() == json.length);
		boolean refused = false;
		try
		{
			final byte[] buffer = new byte[16];
			final RestoreLimits.Bounded tooSmall = new RestoreLimits.Bounded(new ByteArrayInputStream(json), json.length - 1);
			while (tooSmall.read(buffer, 0, buffer.length) >= 0)
			{
				// drain
			}
		}
		catch (final RestoreLimits.BytesExceeded ex)
		{
			refused = ex.getMessage().startsWith(RestoreLimits.CODE) && ex.getMessage().contains(RestoreLimits.MAX_BYTES_PROPERTY);
		}
		RestoreLimitsTest.check("a stream one byte over the bound is refused, coded, naming the property", refused);
		boolean throughJackson = false;
		try (JsonParser parser = new ObjectMapper().getFactory().createParser(new RestoreLimits.Bounded(new ByteArrayInputStream(json), 20)))
		{
			while (parser.nextToken() != null)
			{
				// walk
			}
		}
		catch (final IOException ex)
		{
			throughJackson = RestoreLimits.bytesExceeded(ex) != null;
		}
		RestoreLimitsTest.check("the refusal is recognised along Jackson's exception chain", throughJackson);
		RestoreLimitsTest.check("another IOException is not taken for a bound", RestoreLimits.bytesExceeded(new IOException("boom")) == null);

		final ObjectNode status = RestoreLimits.statusJson();
		RestoreLimitsTest.check("/status publishes both bounds, their properties and the code", status.path("maxBytes").asLong() > 0L && status.path("maxRows").asLong() > 0L
			&& RestoreLimits.MAX_BYTES_PROPERTY.equals(status.path("maxBytesProperty").asText()) && RestoreLimits.MAX_ROWS_PROPERTY.equals(status.path("maxRowsProperty").asText())
			&& RestoreLimits.CODE.equals(status.path("refusalCode").asText()));

		final String restore = RestoreLimitsTest.source("com/on/ebx/factory/ebx/ProjectRestore.java");
		RestoreLimitsTest.check("ProjectRestore reads through the bounded stream and counts rows", restore.contains("RestoreLimits.Bounded(pIn") && restore.contains("pRun.totalRead > pRun.maxRows"));
		RestoreLimitsTest.check("FactoryResource publishes restoreBounds", RestoreLimitsTest.source("com/on/ebx/factory/rest/FactoryResource.java").contains("\"restoreBounds\""));
		System.out.println("RestoreLimitsTest OK");
	}

	private static String source(final String pRelative) throws IOException
	{
		return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("mdmf.sourceDir"), pRelative)), StandardCharsets.UTF_8);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new IllegalStateException("FAILED: " + pWhat);
		}
	}
}
