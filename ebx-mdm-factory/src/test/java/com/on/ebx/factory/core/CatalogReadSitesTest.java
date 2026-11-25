package com.on.ebx.factory.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JUnit-free: run the main, a failure throws. Audit F-02 (catalog reads): every read of the data catalog's dataset is
 * decided in {@link CatalogReadSites}. The sources ({@code -Dmdmf.sourceDir}) are scanned, comments left out:
 * <ul>
 * <li>each {@code Reader.of(} / {@code Reader.system(} of a class is a site: the counts per class equal the table's;</li>
 * <li>a system read names its reason by a {@code *_REASON} constant;</li>
 * <li>no raw read of a catalog table outside {@code CatalogReader} (a {@code createRequestResult},
 * {@code lookupAdaptationByPrimaryKey} or {@code createRequest} on a catalog table), except a lookup wrapped in
 * {@code CatalogReader.visible} or one of the write-side lookups the table allows;</li>
 * <li>every public read of {@code CatalogReader} on the catalog takes a reader first.</li>
 * </ul>
 */
public final class CatalogReadSitesTest
{
	private static final Pattern READER_OF = Pattern.compile("\\bReader\\.of\\(");
	private static final Pattern READER_SYSTEM = Pattern.compile("\\bReader\\.system\\(");
	private static final Pattern SYSTEM_WITH_REASON = Pattern.compile("\\bReader\\.system\\(\\s*(\\w+\\.)?[A-Z][A-Z0-9_]*_REASON\\s*\\)");
	/** A read on a catalog table reached from the catalog dataset itself. */
	private static final Pattern RAW_ON_DATASET = Pattern
		.compile("\\b(pCatalog|catalog|catalogForPending)\\s*\\.\\s*getTable\\([^;]*?\\)\\s*\\.\\s*(createRequestResult|lookupAdaptationByPrimaryKey|createRequest)\\s*\\(");
	/** A read on a variable holding a catalog table, in the classes that only work on the catalog. */
	private static final Pattern RAW_ON_TABLE = Pattern
		.compile("\\b(assetTable|columnTable|sourceTable|assets|sources|products|ports|pPorts|pTable|table)\\s*\\.\\s*(createRequestResult|lookupAdaptationByPrimaryKey|createRequest)\\s*\\(");
	private static final String[] CATALOG_ONLY_CLASSES = { "DeducedScanCatalog", "DeducedScanCatalogWriter", "DeducedScanConfirm", "DataProductPublication" };

	public static void main(final String[] pArgs) throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"));
		final Map<String, String> sources = CatalogReadSitesTest.read(root);
		CatalogReadSitesTest.everySiteIsDecided(sources);
		CatalogReadSitesTest.systemReadsNameTheirReason(sources);
		CatalogReadSitesTest.noRawCatalogRead(sources);
		CatalogReadSitesTest.catalogReaderTakesAReader(sources.get("CatalogReader"));
		CatalogReadSitesTest.tableIsComplete();
		System.out.println("CatalogReadSitesTest OK (" + CatalogReadSites.sites().size() + " sites)");
	}

	private static void everySiteIsDecided(final Map<String, String> pSources)
	{
		final Map<String, int[]> found = new TreeMap<String, int[]>();
		for (final Map.Entry<String, String> entry : pSources.entrySet())
		{
			final String code = entry.getValue();
			final int person = CatalogReadSitesTest.count(CatalogReadSitesTest.READER_OF, code);
			final int system = CatalogReadSitesTest.count(CatalogReadSitesTest.READER_SYSTEM, code);
			if (person > 0 || system > 0)
			{
				found.put(entry.getKey(), new int[] { person, system });
			}
		}
		for (final Map.Entry<String, int[]> entry : found.entrySet())
		{
			final int person = CatalogReadSites.count(entry.getKey(), CatalogReadSites.Mode.PERSON);
			final int system = CatalogReadSites.count(entry.getKey(), CatalogReadSites.Mode.SYSTEM);
			CatalogReadSitesTest.check(entry.getKey() + ": " + entry.getValue()[0] + " Reader.of site(s) in the sources, " + person + " decided as PERSON",
				person == entry.getValue()[0]);
			CatalogReadSitesTest.check(entry.getKey() + ": " + entry.getValue()[1] + " Reader.system site(s) in the sources, " + system + " decided as SYSTEM",
				system == entry.getValue()[1]);
		}
		for (final CatalogReadSites.Site site : CatalogReadSites.sites())
		{
			CatalogReadSitesTest.check("decided site of " + site.className + " exists in the sources", found.containsKey(site.className));
		}
		// two: the snapshot itself, and (UX lot U2) the reasons of an application with no planned table - both shown to the person
		CatalogReadSitesTest.check("the wizard snapshot reads as the person", CatalogReadSites.count("WizardData", CatalogReadSites.Mode.PERSON) == 2
			&& CatalogReadSites.count("WizardData", CatalogReadSites.Mode.SYSTEM) == 0);
		CatalogReadSitesTest.check("the landing plan reads as the person", CatalogReadSites.count("LandingPlanner", CatalogReadSites.Mode.PERSON) == 1
			&& CatalogReadSites.count("LandingPlanner", CatalogReadSites.Mode.SYSTEM) == 0);
		CatalogReadSitesTest.check("the boards of the wizard read as the person", CatalogReadSites.count("ProjectWizardUserService", CatalogReadSites.Mode.SYSTEM) == 0);
	}

	private static void systemReadsNameTheirReason(final Map<String, String> pSources)
	{
		for (final Map.Entry<String, String> entry : pSources.entrySet())
		{
			final String code = entry.getValue();
			final int system = CatalogReadSitesTest.count(CatalogReadSitesTest.READER_SYSTEM, code);
			CatalogReadSitesTest.check(entry.getKey() + ": every Reader.system names a *_REASON constant",
				system == CatalogReadSitesTest.count(CatalogReadSitesTest.SYSTEM_WITH_REASON, code));
		}
	}

	private static void noRawCatalogRead(final Map<String, String> pSources)
	{
		for (final Map.Entry<String, String> entry : pSources.entrySet())
		{
			if ("CatalogReader".equals(entry.getKey()))
			{
				continue; // the mechanism itself
			}
			final String code = entry.getValue();
			final List<String> raw = new ArrayList<String>();
			CatalogReadSitesTest.collectRaw(CatalogReadSitesTest.RAW_ON_DATASET, code, raw);
			for (final String catalogOnly : CatalogReadSitesTest.CATALOG_ONLY_CLASSES)
			{
				if (catalogOnly.equals(entry.getKey()))
				{
					CatalogReadSitesTest.collectRaw(CatalogReadSitesTest.RAW_ON_TABLE, code, raw);
				}
			}
			final CatalogReadSites.WriteSide allowed = CatalogReadSites.WRITE_SIDE.get(entry.getKey());
			final int allowedCount = allowed == null ? 0 : allowed.lookups;
			CatalogReadSitesTest.check(entry.getKey() + ": " + raw.size() + " raw catalog read(s), " + allowedCount + " allowed write-side lookup(s): " + raw,
				raw.size() == allowedCount);
		}
		for (final Map.Entry<String, CatalogReadSites.WriteSide> entry : CatalogReadSites.WRITE_SIDE.entrySet())
		{
			CatalogReadSitesTest.check("write-side lookups of " + entry.getKey() + " say why", entry.getValue().reason != null && entry.getValue().reason.length() > 20);
		}
	}

	/** The statements matching {@code pPattern} that are not wrapped in {@code CatalogReader.visible}. */
	private static void collectRaw(final Pattern pPattern, final String pCode, final List<String> pOut)
	{
		final Matcher matcher = pPattern.matcher(pCode);
		while (matcher.find())
		{
			int start = matcher.start();
			while (start > 0 && ";{}".indexOf(pCode.charAt(start - 1)) < 0)
			{
				start--;
			}
			int end = pCode.indexOf(';', matcher.end());
			end = end < 0 ? pCode.length() : end;
			final String statement = pCode.substring(start, end).replaceAll("\\s+", " ").trim();
			if (!statement.contains("CatalogReader.visible("))
			{
				pOut.add(statement);
			}
		}
	}

	private static void catalogReaderTakesAReader(final String pCode)
	{
		CatalogReadSitesTest.check("CatalogReader found", pCode != null);
		final Matcher matcher = Pattern.compile("public static [\\w<>.\\[\\]]+ (\\w+)\\(([^)]*)\\)").matcher(pCode);
		int checked = 0;
		while (matcher.find())
		{
			final String parameters = matcher.group(2);
			if (parameters.contains("Adaptation pCatalog"))
			{
				checked++;
				CatalogReadSitesTest.check("CatalogReader." + matcher.group(1) + " takes a reader first: (" + parameters + ")",
					parameters.trim().startsWith("final Reader pReader") || parameters.trim().startsWith("final Session pSession"));
			}
		}
		CatalogReadSitesTest.check("CatalogReader's reads were found (" + checked + ")", checked >= 12);
	}

	private static void tableIsComplete()
	{
		for (final CatalogReadSites.Site site : CatalogReadSites.sites())
		{
			CatalogReadSitesTest.check("site of " + site.className + " says what it reads", site.what != null && site.what.length() > 10);
			CatalogReadSitesTest.check("site of " + site.className + " says why", site.reason != null && site.reason.length() > 10);
		}
	}

	// ------------------------------------------------------------------------------------------------------ helpers

	private static Map<String, String> read(final Path pRoot) throws IOException
	{
		final Map<String, String> out = new TreeMap<String, String>();
		final List<Path> files;
		try (Stream<Path> walk = Files.walk(pRoot))
		{
			files = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
		}
		for (final Path file : files)
		{
			final String name = file.getFileName().toString().replace(".java", "");
			if ("CatalogReadSites".equals(name))
			{
				continue;
			}
			final String code = CatalogReadSitesTest.stripComments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
			CatalogReadSitesTest.check("one class per simple name: " + name, !out.containsKey(name) || !code.contains("Reader."));
			if (!out.containsKey(name))
			{
				out.put(name, code);
			}
		}
		CatalogReadSitesTest.check("sources found under " + pRoot, out.containsKey("CatalogReader") && out.containsKey("WizardData"));
		return out;
	}

	/** Comments out, string and char literals kept (a "//" in a URL is not a comment). */
	static String stripComments(final String pCode)
	{
		final StringBuilder out = new StringBuilder(pCode.length());
		int i = 0;
		final int n = pCode.length();
		while (i < n)
		{
			final char c = pCode.charAt(i);
			if (c == '/' && i + 1 < n && pCode.charAt(i + 1) == '/')
			{
				while (i < n && pCode.charAt(i) != '\n')
				{
					i++;
				}
			}
			else if (c == '/' && i + 1 < n && pCode.charAt(i + 1) == '*')
			{
				final int close = pCode.indexOf("*/", i + 2);
				i = close < 0 ? n : close + 2;
				out.append(' ');
			}
			else if (c == '"' || c == '\'')
			{
				out.append(c);
				i++;
				while (i < n && pCode.charAt(i) != c)
				{
					if (pCode.charAt(i) == '\\' && i + 1 < n)
					{
						out.append(pCode.charAt(i));
						i++;
					}
					out.append(pCode.charAt(i));
					i++;
				}
				if (i < n)
				{
					out.append(pCode.charAt(i));
					i++;
				}
			}
			else
			{
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	private static int count(final Pattern pPattern, final String pCode)
	{
		final Matcher matcher = pPattern.matcher(pCode);
		int count = 0;
		while (matcher.find())
		{
			count++;
		}
		return count;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
