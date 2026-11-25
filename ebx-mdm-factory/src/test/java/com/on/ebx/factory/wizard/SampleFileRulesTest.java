package com.on.ebx.factory.wizard;

/**
 * sample-files lot (2026-09-23): JUnit-free, same technique as {@code SourceOnboardingTest} - run the main, a
 * failure throws. Covers every rule of {@link SampleFileRules}, including the task brief's own two
 * {@link SampleFileRules#connectionId(String)} examples.
 */
public final class SampleFileRulesTest
{
	public static void main(final String[] pArgs)
	{
		SampleFileRulesTest.mimeForKnownExtensions();
		SampleFileRulesTest.mimeForUnknownExtensionIsNull();
		SampleFileRulesTest.connectionIdPersonnesSapSampleExample();
		SampleFileRulesTest.connectionIdClientsExportV2Example();
		SampleFileRulesTest.connectionIdStripsAccents();
		SampleFileRulesTest.connectionIdPrefixesFileWhenAllDigits();
		SampleFileRulesTest.connectionIdNeverExceedsMax();
		SampleFileRulesTest.hostIsNeverLocalhostAndDerivesFromConnectionId();
		SampleFileRulesTest.hostFallsBackToSampleWhenSlugIsEmpty();
		SampleFileRulesTest.sourceSystemCodeReusesWizardRules();
		SampleFileRulesTest.dataUrlRoundTripsFileNameWithSpacesAndAccents();
		SampleFileRulesTest.dataUrlRoundTripsFileNameWithSemicolonAndComma();
		SampleFileRulesTest.parseDataUrlRejectsMalformedInput();
		SampleFileRulesTest.parseDataUrlRejectsMissingNameParameter();
		SampleFileRulesTest.rawBytesOfKnownPayloads();
		SampleFileRulesTest.rawBytesOfEmptyPayloadIsZero();

		System.out.println("SampleFileRulesTest OK");
	}

	// ---------------------------------------------------------------------------------------------------- mimeFor

	private static void mimeForKnownExtensions()
	{
		SampleFileRulesTest.check("csv", "text/csv".equals(SampleFileRules.mimeFor("export.csv")));
		SampleFileRulesTest.check("txt", "text/csv".equals(SampleFileRules.mimeFor("export.txt")));
		SampleFileRulesTest.check("tsv", "text/tab-separated-values".equals(SampleFileRules.mimeFor("export.tsv")));
		SampleFileRulesTest.check("json", "application/json".equals(SampleFileRules.mimeFor("export.json")));
		SampleFileRulesTest.check("ndjson", "application/x-ndjson".equals(SampleFileRules.mimeFor("export.ndjson")));
		SampleFileRulesTest.check("jsonl", "application/x-ndjson".equals(SampleFileRules.mimeFor("export.jsonl")));
		SampleFileRulesTest.check("extension is case-insensitive", "application/json".equals(SampleFileRules.mimeFor("export.JSON")));
	}

	private static void mimeForUnknownExtensionIsNull()
	{
		SampleFileRulesTest.check("no dot at all", SampleFileRules.mimeFor("export") == null);
		SampleFileRulesTest.check("unknown extension", SampleFileRules.mimeFor("export.xlsx") == null);
		SampleFileRulesTest.check("null file name", SampleFileRules.mimeFor(null) == null);
	}

	// ----------------------------------------------------------------------------------------------- connectionId

	private static void connectionIdPersonnesSapSampleExample()
	{
		SampleFileRulesTest.check("task brief example 1", "PersonnesSapSample".equals(SampleFileRules.connectionId("Personnes_SAP_Sample.json")));
	}

	private static void connectionIdClientsExportV2Example()
	{
		SampleFileRulesTest.check("task brief example 2", "ClientsExportV2".equals(SampleFileRules.connectionId("clients export (v2).csv")));
	}

	private static void connectionIdStripsAccents()
	{
		SampleFileRulesTest.check("accents stripped from a genuinely accented base name", "ClientsEte".equals(SampleFileRules.connectionId("clients_été.csv")));
	}

	private static void connectionIdPrefixesFileWhenAllDigits()
	{
		final String id = SampleFileRules.connectionId("2026_export.csv");
		SampleFileRulesTest.check("starts with a letter", Character.isLetter(id.charAt(0)));
		SampleFileRulesTest.check("carries the File prefix", id.startsWith("File"));
	}

	private static void connectionIdNeverExceedsMax()
	{
		final StringBuilder longName = new StringBuilder();
		for (int i = 0; i < 20; i++)
		{
			longName.append("segment_");
		}
		longName.append(".csv");
		final String id = SampleFileRules.connectionId(longName.toString());
		SampleFileRulesTest.check("never longer than the field's own max length", id.length() <= SampleFileRules.CONNECTION_ID_MAX);
		SampleFileRulesTest.check("still starts with a letter", Character.isLetter(id.charAt(0)));
	}

	// ------------------------------------------------------------------------------------------------------- host

	private static void hostIsNeverLocalhostAndDerivesFromConnectionId()
	{
		final String host = SampleFileRules.host("PersonnesSapSample");
		SampleFileRulesTest.check("host", "https://personnes-sap-sample.sample-file.local/data".equals(host));
		SampleFileRulesTest.check("never localhost", host.indexOf("localhost") < 0);
	}

	private static void hostFallsBackToSampleWhenSlugIsEmpty()
	{
		final String host = SampleFileRules.host("");
		SampleFileRulesTest.check("falls back rather than producing a bare https://.sample-file.local/data",
			"https://sample.sample-file.local/data".equals(host));
	}

	// --------------------------------------------------------------------------------------------- sourceSystemCode

	private static void sourceSystemCodeReusesWizardRules()
	{
		SampleFileRulesTest.check("delegates to WizardRules.suggestSourceSystemCode on the base name",
			WizardRules.suggestSourceSystemCode("Personnes_SAP_Sample").equals(SampleFileRules.sourceSystemCode("Personnes_SAP_Sample.json")));
	}

	// --------------------------------------------------------------------------------------------------- data URL

	private static void dataUrlRoundTripsFileNameWithSpacesAndAccents()
	{
		final String fileName = "clients été (v2).csv";
		final String url = SampleFileRules.dataUrl("text/csv", fileName, "YWJj");
		final SampleFileRules.ParsedDataUrl parsed = SampleFileRules.parseDataUrl(url);
		SampleFileRulesTest.check("valid", parsed.valid);
		SampleFileRulesTest.check("mime carried through", "text/csv".equals(parsed.mime));
		SampleFileRulesTest.check("file name round-trips exactly", fileName.equals(parsed.fileName));
		SampleFileRulesTest.check("payload carried through", "YWJj".equals(parsed.payload));
	}

	private static void dataUrlRoundTripsFileNameWithSemicolonAndComma()
	{
		// the two characters the contract's own regex splits on (';' and ',') must round-trip when they are
		// part of the FILE NAME rather than contract syntax - only reachable through percent-encoding
		final String fileName = "a;b,c.csv";
		final String url = SampleFileRules.dataUrl("text/csv", fileName, "AAAA");
		final SampleFileRules.ParsedDataUrl parsed = SampleFileRules.parseDataUrl(url);
		SampleFileRulesTest.check("valid despite ';'/',' in the name", parsed.valid);
		SampleFileRulesTest.check("file name round-trips exactly", fileName.equals(parsed.fileName));
	}

	private static void parseDataUrlRejectsMalformedInput()
	{
		SampleFileRulesTest.check("null", !SampleFileRules.parseDataUrl(null).valid);
		SampleFileRulesTest.check("empty", !SampleFileRules.parseDataUrl("").valid);
		SampleFileRulesTest.check("no data: prefix", !SampleFileRules.parseDataUrl("text/csv;name=x;base64,AAAA").valid);
		SampleFileRulesTest.check("missing base64 marker", !SampleFileRules.parseDataUrl("data:text/csv;name=x,AAAA").valid);
	}

	private static void parseDataUrlRejectsMissingNameParameter()
	{
		// the OLD Connectors contract (no ;name=) - genuinely invalid under THIS contract, by design (the
		// dependency on the parallel Connectors lot, task brief)
		SampleFileRulesTest.check("old-style data URL with no ;name= is rejected", !SampleFileRules.parseDataUrl("data:text/csv;base64,AAAA").valid);
	}

	// ------------------------------------------------------------------------------------------------------ rawBytes

	private static void rawBytesOfKnownPayloads()
	{
		SampleFileRulesTest.check("3 raw bytes, no padding", SampleFileRules.rawBytes("YWJj") == 3L);
		SampleFileRulesTest.check("1 raw byte, two '=' padding", SampleFileRules.rawBytes("YQ==") == 1L);
		SampleFileRulesTest.check("2 raw bytes, one '=' padding", SampleFileRules.rawBytes("YWI=") == 2L);
	}

	private static void rawBytesOfEmptyPayloadIsZero()
	{
		SampleFileRulesTest.check("null", SampleFileRules.rawBytes(null) == 0L);
		SampleFileRulesTest.check("empty", SampleFileRules.rawBytes("") == 0L);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
