package com.on.ebx.factory.ebx;

/** JUnit-free: run the main, a failure throws. {@link CatalogReader#quote} and {@link CatalogReader#equalsPredicate} with an absent value (audit F-52). */
public final class CatalogReaderQuoteTest
{
	public static void main(final String[] pArgs)
	{
		CatalogReaderQuoteTest.check("a value is quoted", "'abc'".equals(CatalogReader.quote("abc")));
		CatalogReaderQuoteTest.check("a quote inside is doubled", "'it''s'".equals(CatalogReader.quote("it's")));
		CatalogReaderQuoteTest.check("a null is NOT the text 'null'", !"'null'".equals(CatalogReader.quote(null)) && "''".equals(CatalogReader.quote(null)));
		CatalogReaderQuoteTest.check("the text \"null\" still quotes as itself", "'null'".equals(CatalogReader.quote("null")));

		CatalogReaderQuoteTest.check("a value: plain equality", "./sourceSystem='ERP'".equals(CatalogReader.equalsPredicate("./sourceSystem", "ERP")));
		CatalogReaderQuoteTest.check("a null value: is-null", "osd:is-null(./sourceSystem)".equals(CatalogReader.equalsPredicate("./sourceSystem", null)));
		CatalogReaderQuoteTest.check("an empty value: is-null too (EBX stores empty as null)", "osd:is-null(./sourceSystem)".equals(CatalogReader.equalsPredicate("./sourceSystem", "")));
		System.out.println("CatalogReaderQuoteTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
