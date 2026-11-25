package com.on.ebx.factory.backup;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * F2-11 (1.4.2): the two bounds of a project restore, which read an archive of any size before. Both are JVM properties with a wide default, read at each restore, published
 * under {@code restoreBounds} in {@code GET /rest/v1/status}:
 * <ul>
 * <li>{@value #MAX_BYTES_PROPERTY}: the bytes read from the request body, {@value #DEFAULT_MAX_BYTES} by default (2 GiB);</li>
 * <li>{@value #MAX_ROWS_PROPERTY}: the rows of all the tables of the archive, {@value #DEFAULT_MAX_ROWS} by default.</li>
 * </ul>
 * <b>Why these defaults</b> (reasoning, not a measure of a real archive - none was at hand): the archive is the JSON {@code {key, values}} of the rows of ONE project; the
 * configuration tables (project, sources, landing and staging tables, attributes, mappings, rules, steps) are a few thousand rows and well under 100 MB. The volume tables
 * ({@code Xref}, {@code TransferReject}, {@code DistributionEvent}, which {@code exclude} leaves out of a backup) are the only ones that reach millions of rows. A row costs
 * about 300 to 700 bytes of JSON (a key and 5 to 15 non-empty fields), so 5,000,000 rows are about 1.5 to 3.5 GB: the byte bound (2 GiB) and the row bound (5 million) refuse
 * about the same archive, one by size and one by count, and both sit far above a project with its volume tables left out or of ordinary size. A value that is absent, not a
 * number or not positive means the default - never "unlimited": to restore a larger archive, name a larger bound.
 * <p>
 * The archive is read as a stream, so the refusal comes when the bound is crossed, not after the whole file was read.
 */
public final class RestoreLimits
{
	public static final String MAX_BYTES_PROPERTY = "ebx.factory.restore.maxBytes";
	public static final String MAX_ROWS_PROPERTY = "ebx.factory.restore.maxRows";
	public static final long DEFAULT_MAX_BYTES = 2147483648L;
	public static final long DEFAULT_MAX_ROWS = 5000000L;

	/** Code of the refusal of an archive beyond a bound. */
	public static final String CODE = "MDMF-RESTORE-1";

	private RestoreLimits()
	{
	}

	/** The bound named by {@code pValue}; the default when it is absent, unreadable or not positive. */
	public static long parse(final String pValue, final long pDefault)
	{
		if (pValue == null)
		{
			return pDefault;
		}
		try
		{
			final long value = Long.parseLong(pValue.trim());
			return value > 0L ? value : pDefault;
		}
		catch (final NumberFormatException ex)
		{
			return pDefault;
		}
	}

	public static long maxBytes()
	{
		return RestoreLimits.parse(System.getProperty(RestoreLimits.MAX_BYTES_PROPERTY), RestoreLimits.DEFAULT_MAX_BYTES);
	}

	public static long maxRows()
	{
		return RestoreLimits.parse(System.getProperty(RestoreLimits.MAX_ROWS_PROPERTY), RestoreLimits.DEFAULT_MAX_ROWS);
	}

	/** The English sentence of the refusal ({@code pWhat}: "bytes" or "rows"). */
	public static String sentence(final String pWhat, final long pLimit)
	{
		return RestoreLimits.CODE + ": the archive goes beyond the restore bound of " + pLimit + " " + pWhat + " ("
			+ ("bytes".equals(pWhat) ? RestoreLimits.MAX_BYTES_PROPERTY : RestoreLimits.MAX_ROWS_PROPERTY)
			+ "). Back the project up again with 'exclude' for the volume tables (Xref, TransferReject, DistributionEvent), or ask a server administrator to raise the bound.";
	}

	/** The bounds in force, for {@code /status}. */
	public static ObjectNode statusJson()
	{
		final ObjectNode out = JsonNodeFactory.instance.objectNode();
		out.put("maxBytes", RestoreLimits.maxBytes());
		out.put("maxBytesProperty", RestoreLimits.MAX_BYTES_PROPERTY);
		out.put("maxRows", RestoreLimits.maxRows());
		out.put("maxRowsProperty", RestoreLimits.MAX_ROWS_PROPERTY);
		out.put("defaultMaxBytes", RestoreLimits.DEFAULT_MAX_BYTES);
		out.put("defaultMaxRows", RestoreLimits.DEFAULT_MAX_ROWS);
		out.put("refusalCode", RestoreLimits.CODE);
		return out;
	}

	/** Raised by {@link Bounded} when more bytes than the bound are read. */
	public static final class BytesExceeded extends IOException
	{
		private static final long serialVersionUID = 1L;
		public final long limit;

		BytesExceeded(final long pLimit)
		{
			super(RestoreLimits.sentence("bytes", pLimit));
			this.limit = pLimit;
		}
	}

	/** True when the failure, or one of its causes, is a {@link BytesExceeded}. */
	public static BytesExceeded bytesExceeded(final Throwable pFailure)
	{
		Throwable current = pFailure;
		for (int depth = 0; current != null && depth < 10; depth++)
		{
			if (current instanceof BytesExceeded)
			{
				return (BytesExceeded) current;
			}
			current = current.getCause();
		}
		return null;
	}

	/** Counts the bytes read and fails with {@link BytesExceeded} on the first byte beyond the bound. */
	public static final class Bounded extends FilterInputStream
	{
		private final long limit;
		private long read;

		public Bounded(final InputStream pIn, final long pLimit)
		{
			super(pIn);
			this.limit = pLimit;
		}

		public long bytesRead()
		{
			return this.read;
		}

		@Override
		public int read() throws IOException
		{
			final int b = super.read();
			if (b >= 0)
			{
				this.count(1L);
			}
			return b;
		}

		@Override
		public int read(final byte[] pBuffer, final int pOffset, final int pLength) throws IOException
		{
			final int n = super.read(pBuffer, pOffset, pLength);
			if (n > 0)
			{
				this.count(n);
			}
			return n;
		}

		@Override
		public long skip(final long pN) throws IOException
		{
			final long n = super.skip(pN);
			if (n > 0L)
			{
				this.count(n);
			}
			return n;
		}

		private void count(final long pN) throws IOException
		{
			this.read += pN;
			if (this.read > this.limit)
			{
				throw new BytesExceeded(this.limit);
			}
		}
	}
}
