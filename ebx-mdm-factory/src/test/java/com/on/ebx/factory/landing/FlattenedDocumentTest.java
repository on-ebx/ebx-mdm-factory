package com.on.ebx.factory.landing;

import java.util.Arrays;

/** JUnit-free: run the main, a failure throws. Landing test fixes (2026-10-17, defect 1). */
public final class FlattenedDocumentTest
{
	public static void main(final String[] pArgs)
	{
		final java.util.List<String> flat = Arrays.asList("customers[0].customerId", "customers[0].email", "customers[0].address.city");
		FlattenedDocumentTest.check("array paths", FlattenedDocument.looksFlattened(flat));
		FlattenedDocumentTest.check("field names", FlattenedDocument.looksFlattened(Arrays.asList("customers_0__customerId", "customers_0__email")));
		FlattenedDocumentTest.check("a flat file is not", !FlattenedDocument.looksFlattened(Arrays.asList("customerId", "email", "city")));
		FlattenedDocumentTest.check("one indexed column among many is not", !FlattenedDocument.looksFlattened(Arrays.asList("a", "b", "c", "tags[0].x")));
		FlattenedDocumentTest.check("no column", !FlattenedDocument.looksFlattened(Arrays.<String> asList()) && !FlattenedDocument.looksFlattened(null));

		final LandingFlow.LoadOutcome oneRow = new LandingFlow.LoadOutcome(true, 1L, "OK: 1 row");
		final LandingFlow.LoadOutcome adjusted = FlattenedDocument.apply(oneRow, flat);
		FlattenedDocumentTest.check("one row into flattened columns fails with the message", !adjusted.ok && adjusted.rows == 1L && adjusted.message.startsWith(FlattenedDocument.MESSAGE));
		FlattenedDocumentTest.check("the message names the cause and the remedy", FlattenedDocument.MESSAGE.contains("customers[0]") && FlattenedDocument.MESSAGE.contains("rescan after the connection is fixed"));
		FlattenedDocumentTest.check("160 rows are fine", FlattenedDocument.apply(new LandingFlow.LoadOutcome(true, 160L, "OK"), flat).ok);
		FlattenedDocumentTest.check("one row of a flat table is fine", FlattenedDocument.apply(oneRow, Arrays.asList("id", "name")) == oneRow);
		final LandingFlow.LoadOutcome failed = new LandingFlow.LoadOutcome(false, 1L, "boom");
		FlattenedDocumentTest.check("an outcome already failed keeps its own message", FlattenedDocument.apply(failed, flat) == failed);
		System.out.println("FlattenedDocumentTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
