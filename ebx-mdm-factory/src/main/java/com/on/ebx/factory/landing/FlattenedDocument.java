package com.on.ebx.factory.landing;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Landing test fixes (2026-10-17, defect 1, the Factory's side): a file holding one JSON document whose rows sit under a key the
 * reader does not know ({@code {"customers":[...]}}) was read as ONE record whose columns are {@code customers[0].customerId},
 * {@code customers[0].email}... (fields {@code customers_0__customerId}), planned, loaded as one row and reported LOADED. The reader is fixed
 * in ebx-connectors; this is the defence on the Factory's side: the table is flagged when it is planned and a load that gave exactly one row
 * with that shape is a failure, never a success. Pure: no EBX class.
 */
public final class FlattenedDocument
{
	/** The message the table carries and the failed load reports. */
	public static final String MESSAGE = "This file looks like one document holding a list (customers[0]...): the rows were not recognised; rescan after the connection is fixed.";

	private static final Pattern INDEXED = Pattern.compile(".*\\[\\d+\\]\\..*");
	private static final Pattern INDEXED_FIELD = Pattern.compile(".*_\\d+__.*");

	private FlattenedDocument()
	{
	}

	/** At least half of the columns (and at least one) are paths into an array element: {@code x[0].y} or the field name {@code x_0__y}. */
	public static boolean looksFlattened(final List<String> pColumns)
	{
		if (pColumns == null || pColumns.isEmpty())
		{
			return false;
		}
		int indexed = 0;
		for (final String column : pColumns)
		{
			if (column != null && (FlattenedDocument.INDEXED.matcher(column).matches() || FlattenedDocument.INDEXED_FIELD.matcher(column).matches()))
			{
				indexed++;
			}
		}
		return indexed > 0 && indexed * 2 >= pColumns.size();
	}

	/**
	 * A load that read exactly one row into columns of that shape did not read the rows of the file: it ends as a failure carrying {@link #MESSAGE}.
	 * Anything else is returned as it is.
	 */
	public static LandingFlow.LoadOutcome apply(final LandingFlow.LoadOutcome pOutcome, final List<String> pSourceColumns)
	{
		if (pOutcome.ok && pOutcome.rows == 1L && FlattenedDocument.looksFlattened(pSourceColumns))
		{
			return new LandingFlow.LoadOutcome(false, pOutcome.rows, FlattenedDocument.MESSAGE + " (1 row was loaded.)", pOutcome.watermark);
		}
		return pOutcome;
	}
}
