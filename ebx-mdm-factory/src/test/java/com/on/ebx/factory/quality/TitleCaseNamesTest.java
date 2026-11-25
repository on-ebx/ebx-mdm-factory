package com.on.ebx.factory.quality;

/**
 * Staging polish (2026-10-17): the name-aware TITLE_CASE of {@link CleaningSteps#titleCase}. JUnit-free: run the main, a failure throws. The B2C values
 * are the real distinct values of the retest files (Magasins_Ventes.csv NOM / Fidelite_Membres.csv ville and adresse), the rest are the name shapes the
 * rule list of the method documents.
 */
public final class TitleCaseNamesTest
{
	public static void main(final String[] pArgs)
	{
		TitleCaseNamesTest.b2cFiles();
		TitleCaseNamesTest.hyphensAndApostrophes();
		TitleCaseNamesTest.mcButNotMac();
		TitleCaseNamesTest.particles();
		TitleCaseNamesTest.accentsAndUnicode();
		TitleCaseNamesTest.digitsAndMixedCaseAreKept();
		TitleCaseNamesTest.theB2cFilesAgree();
		TitleCaseNamesTest.theStepEngineUsesTheSameFunction();
		System.out.println("TitleCaseNamesTest OK");
	}

	private static void b2cFiles()
	{
		// Magasins_Ventes.csv NOM (written in capitals) -> the title case the other two sources already use
		final String[][] surnames = { { "FOURNIER", "Fournier" }, { "ANDR\u00c9", "Andr\u00e9" }, { "LEF\u00c8VRE", "Lef\u00e8vre" }, { "BERTRAND", "Bertrand" },
			{ "GARCIA", "Garcia" }, { "MARTIN", "Martin" } };
		for (final String[] pair : surnames)
		{
			TitleCaseNamesTest.is(pair[1], pair[0]);
			TitleCaseNamesTest.is(pair[1], pair[1]); // idempotent on the form the other sources write
		}
		// Fidelite_Membres.csv ville (capitals) and adresse (lower case articles, title case names)
		TitleCaseNamesTest.is("Montpellier", "MONTPELLIER");
		TitleCaseNamesTest.is("Toulouse", "TOULOUSE");
		TitleCaseNamesTest.is("Strasbourg", "STRASBOURG");
		TitleCaseNamesTest.is("113 Rue du Commerce", "113 rue du commerce");
		TitleCaseNamesTest.is("41 Place du March\u00e9", "41 PLACE DU MARCH\u00c9");
		TitleCaseNamesTest.is("18 Avenue Jean Jaur\u00e8s", "18 avenue jean jaur\u00e8s");
		TitleCaseNamesTest.is("7 Avenue de la Gare", "7 AVENUE DE LA GARE");
		TitleCaseNamesTest.is("66 Boulevard Voltaire", "66 BOULEVARD VOLTAIRE");
		TitleCaseNamesTest.is("Par-Bastille", "PAR-BASTILLE");
	}

	private static void hyphensAndApostrophes()
	{
		TitleCaseNamesTest.is("Jean-Pierre", "JEAN-PIERRE");
		TitleCaseNamesTest.is("Marie-Anne Dupont-Martin", "MARIE-ANNE DUPONT-MARTIN");
		TitleCaseNamesTest.is("O'Brien", "O'BRIEN");
		TitleCaseNamesTest.is("O\u2019Brien", "O\u2019BRIEN");
		TitleCaseNamesTest.is("N'Guyen", "N'GUYEN");
		// rule: d' / l' elision is a particle - lower case inside a name, capital as the first word
		TitleCaseNamesTest.is("D'Artagnan", "D'ARTAGNAN");
		TitleCaseNamesTest.is("Charles d'Artagnan", "CHARLES D'ARTAGNAN");
		TitleCaseNamesTest.is("Charles d\u2019Artagnan", "CHARLES D\u2019ARTAGNAN");
		TitleCaseNamesTest.is("Rue de l'Eglise", "RUE DE L'EGLISE");
		TitleCaseNamesTest.is("L'Oreal", "L'OREAL");
		TitleCaseNamesTest.is("J.R. Smith", "J.R. SMITH");
	}

	private static void mcButNotMac()
	{
		TitleCaseNamesTest.is("McDonald", "MCDONALD");
		TitleCaseNamesTest.is("McDonald", "mcdonald");
		TitleCaseNamesTest.is("John McGee", "JOHN MCGEE");
		TitleCaseNamesTest.is("Smith-McKay", "SMITH-MCKAY");
		// only Mc is safe
		TitleCaseNamesTest.is("Machin", "MACHIN");
		TitleCaseNamesTest.is("Macdonald", "MACDONALD");
		TitleCaseNamesTest.is("Macon", "MACON");
		// too short to be a Mc surname: an acronym stays a plain word
		TitleCaseNamesTest.is("Mca", "MCA");
	}

	private static void particles()
	{
		TitleCaseNamesTest.is("Jean de la Fontaine", "JEAN DE LA FONTAINE");
		TitleCaseNamesTest.is("Ludwig van Beethoven", "LUDWIG VAN BEETHOVEN");
		TitleCaseNamesTest.is("Carl von Linne", "CARL VON LINNE");
		TitleCaseNamesTest.is("Juan del Monte", "JUAN DEL MONTE");
		TitleCaseNamesTest.is("Maria da Silva dos Santos", "MARIA DA SILVA DOS SANTOS");
		TitleCaseNamesTest.is("Jean du Bois", "JEAN DU BOIS");
		TitleCaseNamesTest.is("Pierre des Pres", "PIERRE DES PRES");
		TitleCaseNamesTest.is("Saint-Jean-de-Luz", "SAINT-JEAN-DE-LUZ");
		TitleCaseNamesTest.is("Hans der Grosse", "HANS DER GROSSE");
		TitleCaseNamesTest.is("Jan den Haag", "JAN DEN HAAG");
		TitleCaseNamesTest.is("Leonardo di Caprio", "LEONARDO DI CAPRIO");
		// first (and last) word stay capitalised
		TitleCaseNamesTest.is("De Gaulle", "DE GAULLE");
		TitleCaseNamesTest.is("Van Gogh", "VAN GOGH");
		TitleCaseNamesTest.is("La Rochelle", "LA ROCHELLE");
		TitleCaseNamesTest.is("Thi Le", "THI LE");
		TitleCaseNamesTest.is("Van", "VAN");
		// documented limit: a middle Le is read as the article
		TitleCaseNamesTest.is("Nguyen le Hung", "NGUYEN LE HUNG");
		// a particle the source already wrote with a capital is mixed case, kept
		TitleCaseNamesTest.is("Jean De La Fontaine", "Jean De La Fontaine");
		// a longer word that merely starts like a particle is no particle
		TitleCaseNamesTest.is("Jean Dea Martin", "JEAN DEA MARTIN");
	}

	private static void accentsAndUnicode()
	{
		TitleCaseNamesTest.is("\u00c9lodie", "\u00c9LODIE");
		TitleCaseNamesTest.is("\u00c9lodie", "\u00e9lodie");
		TitleCaseNamesTest.is("\u00c9milie-\u00c8ve", "\u00c9MILIE-\u00c8VE");
		TitleCaseNamesTest.is("\u00d6zg\u00fcr", "\u00d6ZG\u00dcR");
		TitleCaseNamesTest.is("Stra\u00dfe", "stra\u00dfe");
		// Greek final sigma, lower-cased inside the word
		TitleCaseNamesTest.is("\u039f\u03b4\u03c5\u03c3\u03c3\u03b5\u03c5\u03c2", "\u039f\u0394\u03a5\u03a3\u03a3\u0395\u03a5\u03a3");
		// a supplementary-plane letter is handled by code point, not by char
		TitleCaseNamesTest.is(new String(Character.toChars(0x10400)) + "x", new String(Character.toChars(0x10400)) + "X");
		// a CJK value has no case: left as it is
		TitleCaseNamesTest.is("\u5c71\u7530 \u592a\u90ce", "\u5c71\u7530 \u592a\u90ce");
		// Turkish dotted capital I does not depend on the default locale
		final java.util.Locale previous = java.util.Locale.getDefault();
		try
		{
			java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
			TitleCaseNamesTest.is("Isik", "ISIK");
		}
		finally
		{
			java.util.Locale.setDefault(previous);
		}
	}

	private static void digitsAndMixedCaseAreKept()
	{
		TitleCaseNamesTest.is("3EME Etage", "3EME ETAGE");
		TitleCaseNamesTest.is("B2B Clients", "B2B CLIENTS");
		TitleCaseNamesTest.is("12a", "12a");
		TitleCaseNamesTest.is("Appt 4B", "APPT 4B");
		TitleCaseNamesTest.is("MacDonald", "MacDonald");
		TitleCaseNamesTest.is("DiCaprio", "DiCaprio");
		TitleCaseNamesTest.is("Jean MacDonald", "JEAN MacDonald");
		TitleCaseNamesTest.is("", "");
		TitleCaseNamesTest.is("   ", "   ");
		TitleCaseNamesTest.is("12-34", "12-34");
		TitleCaseNamesTest.is("-", "-");
		TitleCaseNamesTest.is("  Jean   Pierre ", "  JEAN   PIERRE ");
	}

	private static void theStepEngineUsesTheSameFunction()
	{
		final CleaningSteps.Result result = CleaningSteps.applyStep(CleaningSteps.TITLE_CASE, null, "MCDONALD", null, null);
		TitleCaseNamesTest.check("applyStep gives the name-aware result", "McDonald".equals(result.value) && result.changed);
		TitleCaseNamesTest.check("a value already right is not 'changed'", !CleaningSteps.applyStep(CleaningSteps.TITLE_CASE, null, "McDonald", null, null).changed);
		// idempotent on everything the rules produce
		for (final String raw : new String[] { "JEAN DE LA FONTAINE", "D'ARTAGNAN", "CHARLES D'ARTAGNAN", "MCDONALD", "O'BRIEN", "SAINT-JEAN-DE-LUZ", "NGUYEN LE HUNG" })
		{
			final String once = CleaningSteps.titleCase(raw);
			TitleCaseNamesTest.check("idempotent on " + once, once.equals(CleaningSteps.titleCase(once)));
		}
	}

	/**
	 * The real files of the B2C retest: the surnames the store tickets write in capitals, title-cased, must be the very spelling the loyalty file writes
	 * for the same people (and the cities the loyalty file writes in capitals, the spelling of the web shop). Skipped when the files are not there.
	 */
	private static void theB2cFilesAgree()
	{
		final java.io.File dir = new java.io.File(System.getProperty("mdmf.b2cDir", "test-landing-b2c-2026-10-17/fichiers"));
		final java.io.File tickets = new java.io.File(dir, "Magasins_Ventes.csv");
		final java.io.File loyalty = new java.io.File(dir, "Fidelite_Membres.csv");
		if (!tickets.isFile() || !loyalty.isFile())
		{
			System.out.println("  (B2C files not found: real-file check skipped)");
			return;
		}
		try
		{
			final java.util.Set<String> loyaltySurnames = TitleCaseNamesTest.column(loyalty, ",", "nom");
			final java.util.Set<String> ticketSurnames = TitleCaseNamesTest.column(tickets, ";", "NOM");
			TitleCaseNamesTest.check("the ticket file has surnames", !ticketSurnames.isEmpty() && !loyaltySurnames.isEmpty());
			for (final String surname : ticketSurnames)
			{
				final String titled = CleaningSteps.titleCase(surname);
				TitleCaseNamesTest.check("ticket surname " + surname + " -> " + titled + " is not a loyalty spelling", loyaltySurnames.contains(titled));
				TitleCaseNamesTest.check("idempotent on " + titled, titled.equals(CleaningSteps.titleCase(titled)));
			}
			final java.util.Set<String> capitalCities = TitleCaseNamesTest.column(loyalty, ",", "ville");
			for (final String city : capitalCities)
			{
				final String titled = CleaningSteps.titleCase(city);
				TitleCaseNamesTest.check("city " + city + " -> " + titled, Character.isUpperCase(titled.codePointAt(0)) && titled.substring(1).equals(titled.substring(1).toLowerCase(java.util.Locale.ROOT)));
			}
		}
		catch (final java.io.IOException ex)
		{
			throw new AssertionError("reading the B2C files: " + ex);
		}
	}

	private static java.util.Set<String> column(final java.io.File pFile, final String pSeparator, final String pColumn) throws java.io.IOException
	{
		final java.util.List<String> lines = java.nio.file.Files.readAllLines(pFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
		final java.util.List<String> header = java.util.Arrays.asList(lines.get(0).split(pSeparator, -1));
		final int index = header.indexOf(pColumn);
		final java.util.Set<String> out = new java.util.LinkedHashSet<String>();
		for (int i = 1; i < lines.size(); i++)
		{
			final String[] cells = lines.get(i).split(pSeparator, -1);
			if (index >= 0 && index < cells.length && !cells[index].isEmpty())
			{
				out.add(cells[index]);
			}
		}
		return out;
	}

	private static void is(final String pExpected, final String pInput)
	{
		final String actual = CleaningSteps.titleCase(pInput);
		TitleCaseNamesTest.check("titleCase(\"" + pInput + "\") expected \"" + pExpected + "\" but was \"" + actual + "\"", pExpected != null && pExpected.equals(actual));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
