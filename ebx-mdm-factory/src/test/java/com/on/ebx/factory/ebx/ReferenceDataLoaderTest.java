package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.accel.Pack;
import com.on.ebx.factory.accel.PackCatalog;
import com.on.ebx.factory.accel.ReferenceTable;

/** JUnit-free: the pure half of the loader (column map, blanks, duplicates, conversions) on the real CSVs of the reference-data pack. */
public final class ReferenceDataLoaderTest
{
	public static void main(final String[] pArgs)
	{
		final Pack pack = PackCatalog.load().pack("reference-data");
		ReferenceDataLoaderTest.check("pack", pack != null && pack.referenceData().size() == 9);
		for (final Map.Entry<String, ReferenceTable> e : pack.referenceData().entrySet())
		{
			final ReferenceDataLoader.Result r = new ReferenceDataLoader.Result();
			final List<Map<String, String>> rows = ReferenceDataLoader.rows(e.getValue(), r);
			ReferenceDataLoaderTest.check(e.getKey() + " has rows", !rows.isEmpty());
			ReferenceDataLoaderTest.check(e.getKey() + " every row has its key " + e.getValue().key(), allHaveKey(rows, e.getValue().key()));
			ReferenceDataLoaderTest.check(e.getKey() + " accounting", rows.size() + r.duplicatesInFile + r.withoutKey == e.getValue().rows().size());
			for (final Map<String, String> row : rows)
			{
				for (final String field : row.keySet())
				{
					ReferenceDataLoaderTest.check(e.getKey() + " field " + field + " is a mapped field name", e.getValue().fieldNames().contains(field));
				}
			}
		}
		final ReferenceTable country = pack.referenceData().get("/root/ReferenceData/Country");
		final Map<String, String> ad = ReferenceDataLoader.rows(country, null).get(0);
		ReferenceDataLoaderTest.check("country map: Alpha-2 Code -> alpha2, Name FR -> nameFr, Dial -> dialCode", "AD".equals(ad.get("alpha2")) && "Andorre".equals(ad.get("nameFr"))
				&& "376".equals(ad.get("dialCode")) && "Andorra".equals(ad.get("name")) && !ad.containsKey("WMO") && !ad.containsKey("ITU"));
		final ReferenceTable currency = pack.referenceData().get("/root/ReferenceData/Currency");
		final Map<String, String> aed = ReferenceDataLoader.rows(currency, null).get(0);
		ReferenceDataLoaderTest.check("currency map", "AED".equals(aed.get("code")) && "UAE Dirham".equals(aed.get("name")) && "2".equals(aed.get("decimals")) && "true".equals(aed.get("active"))
				&& !aed.containsKey("symbol"));
		ReferenceDataLoaderTest.check("a blank cell is not written (Symbol is empty for AED)", !aed.containsKey("symbol"));
		final ReferenceTable forms = pack.referenceData().get("/root/ReferenceData/LegalForm");
		ReferenceDataLoaderTest.check("legal forms keep their volume", ReferenceDataLoader.rows(forms, null).size() > 3000);
		ReferenceDataLoaderTest.check("no CSV header leaks when a map is declared", !ReferenceDataLoader.rows(forms, null).get(0).containsKey("ELF code"));
		ReferenceDataLoaderTest.check("convert int", Integer.valueOf(2).equals(ReferenceDataLoader.convert("2", Integer.class)));
		ReferenceDataLoaderTest.check("convert decimal", new BigDecimal("1.5").equals(ReferenceDataLoader.convert("1.5", BigDecimal.class)));
		ReferenceDataLoaderTest.check("convert boolean", Boolean.TRUE.equals(ReferenceDataLoader.convert("true", Boolean.class)) && Boolean.FALSE.equals(ReferenceDataLoader.convert("false", Boolean.class)));
		ReferenceDataLoaderTest.check("convert string and foreign key stay text", "FR".equals(ReferenceDataLoader.convert("FR", String.class)) && "FR".equals(ReferenceDataLoader.convert("FR", null)));
		System.out.println("ReferenceDataLoaderTest OK");
	}

	private static boolean allHaveKey(final List<Map<String, String>> pRows, final String pKey)
	{
		for (final Map<String, String> r : pRows)
		{
			if (r.get(pKey) == null || r.get(pKey).isEmpty())
			{
				return false;
			}
		}
		return true;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
