package com.on.ebx.factory.core;

import java.util.Arrays;
import java.util.Collections;

/**
 * JUnit-free: run the main, a failure throws. {@link CatalogKeys} gives exactly the keys the data catalog's own
 * {@code com.on.ebx.catalog.core.CatalogKeys} gives (audit D-07): the expected strings below were produced by running
 * that class (ebx-data-catalog, branch fix/audit-wave5-natural-keys, 2026-10-06) on the same inputs - quotes, pipes,
 * backslashes, accents, spaces, colons and slashes are kept as spelled, the source id is trimmed.
 */
public final class CatalogKeysTest
{
	/** {source, qualifiedName, assetKey, columnKey of the column COLUMN} as the catalog's class answers them. */
	private static final String[][] PARITY = {
		{"DWH_PROD", "DB.SCHEMA.TABLE", "DWH_PROD:DB.SCHEMA.TABLE", "DWH_PROD:DB.SCHEMA.TABLE.Col 'x'|y\\z:\u00e9"},
		{" DEMOCRM ", "Company", "DEMOCRM:Company", "DEMOCRM:Company.Col 'x'|y\\z:\u00e9"},
		{"SRC", "O'Brien \"quoted\"", "SRC:O'Brien \"quoted\"", "SRC:O'Brien \"quoted\".Col 'x'|y\\z:\u00e9"},
		{"SRC", "a|b\\c", "SRC:a|b\\c", "SRC:a|b\\c.Col 'x'|y\\z:\u00e9"},
		{"SRC", "Soci\u00e9t\u00e9 G\u00e9n\u00e9rale Clients", "SRC:Soci\u00e9t\u00e9 G\u00e9n\u00e9rale Clients", "SRC:Soci\u00e9t\u00e9 G\u00e9n\u00e9rale Clients.Col 'x'|y\\z:\u00e9"},
		{"SRC", "ns:obj:with:colons", "SRC:ns:obj:with:colons", "SRC:ns:obj:with:colons.Col 'x'|y\\z:\u00e9"},
		{"SRC", "path/to/file name.csv", "SRC:path/to/file name.csv", "SRC:path/to/file name.csv.Col 'x'|y\\z:\u00e9"},
		{"SRC", " leading and trailing ", "SRC: leading and trailing ", "SRC: leading and trailing .Col 'x'|y\\z:\u00e9"},
	};
	private static final String COLUMN = "Col 'x'|y\\z:\u00e9";

	public static void main(final String[] pArgs)
	{
		for (final String[] row : CatalogKeysTest.PARITY)
		{
			CatalogKeysTest.check("assetKey(" + row[0] + ", " + row[1] + ")", row[2].equals(CatalogKeys.assetKey(row[0], row[1])));
			CatalogKeysTest.check("columnKey(" + row[2] + ")", row[3].equals(CatalogKeys.columnKey(row[2], CatalogKeysTest.COLUMN)));
			CatalogKeysTest.check("a real key is plausible: " + row[2], CatalogKeys.plausibleAssetKey(row[2]));
			final String token = CatalogKeys.domToken(row[2]);
			CatalogKeysTest.check("dom token is [A-Za-z0-9_] only: " + token, token.matches("[A-Za-z][A-Za-z0-9_]*"));
		}
		CatalogKeysTest.check("missing source", CatalogKeys.assetKey("", "x") == null && CatalogKeys.assetKey(null, "x") == null && CatalogKeys.assetKey("  ", "x") == null);
		CatalogKeysTest.check("missing name", CatalogKeys.assetKey("S", "") == null && CatalogKeys.assetKey("S", null) == null);
		CatalogKeysTest.check("a name of spaces is a name (kept as spelled)", "S:  ".equals(CatalogKeys.assetKey("S", "  ")));
		CatalogKeysTest.check("missing column name", CatalogKeys.columnKey("A", "") == null && CatalogKeys.columnKey(null, "c") == null);

		CatalogKeysTest.check("an old integer id is not a key", !CatalogKeys.plausibleAssetKey("51"));
		CatalogKeysTest.check("blank is not a key", !CatalogKeys.plausibleAssetKey("") && !CatalogKeys.plausibleAssetKey(null) && !CatalogKeys.plausibleAssetKey("   "));
		CatalogKeysTest.check("a leading colon is not a key", !CatalogKeys.plausibleAssetKey(":x"));
		CatalogKeysTest.check("a control character is not a key", !CatalogKeys.plausibleAssetKey("S:a\nb"));
		final StringBuilder longKey = new StringBuilder("S:");
		while (longKey.length() <= CatalogKeys.ASSET_KEY_MAX)
		{
			longKey.append('x');
		}
		CatalogKeysTest.check("longer than the catalog field is not a key", !CatalogKeys.plausibleAssetKey(longKey.toString()));

		CatalogKeysTest.check("dom tokens differ per key", !CatalogKeys.domToken("S:a").equals(CatalogKeys.domToken("S:b")));
		CatalogKeysTest.check("dom token is stable", CatalogKeys.domToken("S:a").equals(CatalogKeys.domToken("S:a")));

		// lookup order: stored key first, then the computed one; an old integer id is skipped; no duplicate
		CatalogKeysTest.check("stored then computed", Arrays.asList("S:Old", "S:T").equals(CatalogKeys.lookupOrder("S:Old", "S", "T")));
		CatalogKeysTest.check("stored equal to computed: once", Arrays.asList("S:T").equals(CatalogKeys.lookupOrder("S:T", " S ", "T")));
		CatalogKeysTest.check("old integer id: computed only", Arrays.asList("S:T").equals(CatalogKeys.lookupOrder("51", "S", "T")));
		CatalogKeysTest.check("nothing stored, no source: nothing", Collections.<String> emptyList().equals(CatalogKeys.lookupOrder(null, null, "T")));
		CatalogKeysTest.check("empty stored, source and object", Arrays.asList("S:a|b\\c").equals(CatalogKeys.lookupOrder("", "S", "a|b\\c")));
		System.out.println("CatalogKeysTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
