package com.on.ebx.factory.matching;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The B2C master scenario's 534 person records (tools/e2e/gen_b2c.py: web shop ECOM 160, store tickets STORES 234, loyalty members LOYALTY 140; 565 true pairs) reduced to
 * the keys the Factory derives - written by {@code python3 tools/e2e/b2c_missed_pairs.py <dir> --tsv ...} into {@code b2c_person_keys.tsv}. Shared by the lot M3 tests
 * ({@code KeyFactsTest}, {@code KeyFindingsTest}, {@code MatchPlanLlmPassKeyFactsTest}). The file is found through {@code -Dmdmf.sourceDir} (as {@code tools/test_local.sh} sets it).
 */
public final class B2cKeyFixtureTest
{
	static final String[] KEY_NAMES = { "emailKey", "fullNameKey", "mobileE164", "addressKeys", "birthDate" };

	private B2cKeyFixtureTest()
	{
	}

	/** The fixture is a test of its own too: the file is what tools/e2e/gen_b2c.py + b2c_missed_pairs.py write (534 records, three sources, 217 people at most). */
	public static void main(final String[] pArgs)
	{
		final List<Line> lines = B2cKeyFixtureTest.lines();
		B2cKeyFixtureTest.check(lines.size() == 534, "534 records, got " + lines.size());
		int stores = 0;
		int storesWithoutEmail = 0;
		for (final Line line : lines)
		{
			if ("STORES".equals(line.system))
			{
				stores++;
				if (line.keys[0] == null)
				{
					storesWithoutEmail++;
				}
			}
		}
		B2cKeyFixtureTest.check(stores == 234 && storesWithoutEmail == 103, "234 tickets, 103 of them without an e-mail: " + stores + "/" + storesWithoutEmail);
		System.out.println("B2cKeyFixtureTest OK");
	}

	/** The columns of the TSV, in order. */
	static final class Line
	{
		final String system;
		final int person;
		final String[] keys; // emailKey, nameKey, e164, postal, addressKey, birth

		Line(final String pSystem, final int pPerson, final String[] pKeys)
		{
			this.system = pSystem;
			this.person = pPerson;
			this.keys = pKeys;
		}
	}

	static List<Line> lines()
	{
		final String sourceDir = System.getProperty("mdmf.sourceDir");
		final Path file = sourceDir == null ? Paths.get("ebx-mdm-factory/src/test/resources/com/on/ebx/factory/matching/b2c_person_keys.tsv")
			: Paths.get(sourceDir).resolve("../../test/resources/com/on/ebx/factory/matching/b2c_person_keys.tsv").normalize();
		final List<Line> out = new ArrayList<Line>();
		try
		{
			final List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 1; i < all.size(); i++)
			{
				final String[] cells = all.get(i).split("\t", -1);
				final String[] keys = new String[6];
				for (int k = 0; k < 6; k++)
				{
					keys[k] = cells[2 + k].isEmpty() ? null : cells[2 + k];
				}
				out.add(new Line(cells[0], Integer.parseInt(cells[1]), keys));
			}
		}
		catch (final IOException ex)
		{
			throw new AssertionError("fixture not readable: " + file + ": " + ex);
		}
		return out;
	}

	/** The records of the fixture as plain cells {system, person, emailKey, nameKey, e164, postal, addressKey, birth} (a missing key is null) - for the tests of other packages (lot F1). */
	public static List<String[]> records()
	{
		final List<String[]> out = new ArrayList<String[]>();
		for (final Line line : B2cKeyFixtureTest.lines())
		{
			out.add(new String[] { line.system, String.valueOf(line.person), line.keys[0], line.keys[1], line.keys[2], line.keys[3], line.keys[4], line.keys[5] });
		}
		return out;
	}

	/** Rows for {@link #specs()}: emailKey, fullNameKey, mobileE164, addressKeys, birthDate. */
	public static List<KeyFacts.Row> rows()
	{
		final List<KeyFacts.Row> out = new ArrayList<KeyFacts.Row>();
		for (final Line line : B2cKeyFixtureTest.lines())
		{
			out.add(new KeyFacts.Row(line.system, new String[] { line.keys[0], line.keys[1], line.keys[2], line.keys[4], line.keys[5] }));
		}
		return out;
	}

	/** The five keys: the e-mail key (derived from email), the harmonised name key, the E.164 phone (derived from mobile), the child keys of the address, the birth date. */
	public static List<KeyFacts.KeySpec> specs()
	{
		return Arrays.asList(new KeyFacts.KeySpec("emailKey", "email", false), new KeyFacts.KeySpec("fullNameKey", "name", false),
			new KeyFacts.KeySpec("mobileE164", "mobile", false), new KeyFacts.KeySpec("addressKeys", "addressKeys", true), new KeyFacts.KeySpec("birthDate", "birthDate", false));
	}

	public static KeyFacts facts()
	{
		return KeyFacts.compute(B2cKeyFixtureTest.rows(), B2cKeyFixtureTest.specs(), false);
	}

	public static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
