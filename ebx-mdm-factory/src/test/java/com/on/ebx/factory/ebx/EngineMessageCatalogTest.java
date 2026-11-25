package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.on.ebx.factory.ebx.ui.EngineNotice;
import com.on.ebx.factory.wizard.EngineMessageCodec;
import com.on.ebx.factory.wizard.WizardMessages;

/**
 * JUnit-free: run the main, a failure throws. Wave 10 (audit F-39): the engine messages a person can meet carry a code and a bundle
 * sentence in English and French. A SOURCE SCAN fails when a new {@code OperationException.createError("...")} is added to the
 * module without a code (use {@code EngineRefusal.coded("<kind>.<name>", english, args...)} and add {@code engine.<kind>.<name>}
 * to both {@code Messages_xx.properties}), unless it is one of the deliberate leftovers listed in {@link #LEFT_AS_LOGS}. The same
 * scan checks that every coded call has its two sentences and passes the arguments the sentence uses; the bundle check keeps the
 * apostrophe rule (a sentence with a placeholder doubles its apostrophes, a sentence without one never does) and the two
 * languages' placeholders equal. The source folder comes from {@code -Dmdmf.sourceDir} ({@code tools/test_local.sh} sets it).
 */
public final class EngineMessageCatalogTest
{
	/**
	 * The deliberate leftovers: internal consistency checks (a read-back that disagrees with what a tool just answered, a stored value that
	 * should be well-formed) that no screen action can provoke. They end in the generic localized sentence of the wizard with the English text
	 * under "Details" ({@code EngineNotice}). Key: file name, then the first 70 characters of the normalized argument; value: how many sites.
	 */
	private static final Map<String, Integer> LEFT_AS_LOGS = new HashMap<String, Integer>();

	static
	{
		LEFT_AS_LOGS.put("DerivationTriggers.java|\"trigger '\" + name + \"' is \" + (state == State.MISSING ? \"not reported", 1);
		LEFT_AS_LOGS.put("GuardWrites.java|\"A guard of nature '\" + pNature + \"' has no severity to set.\"", 1);
		LEFT_AS_LOGS.put("LandingFlowOwnership.java|\"connectors_mapping_get \" + pMappingId + \" did not answer a dataspace:", 1);
		LEFT_AS_LOGS.put("LandingFlowOwnership.java|\"connectors_mapping_get \" + pMappingId + \" failed: \" + text", 1);
		LEFT_AS_LOGS.put("MatchingCatchUp.java|\"Could not read the primary key of \" + pTablePath + \": getPrimaryKeySp", 1);
		LEFT_AS_LOGS.put("MatchingConfigurator.java|\"Cannot write the execution mode of matching policy \" + pPolicyCode + ", 1);
		LEFT_AS_LOGS.put("MatchingConfigurator.java|\"Could not read the primary key of \" + pTablePath + \": getPrimaryKeySp", 1);
		LEFT_AS_LOGS.put("MatchingConfigurator.java|\"MatchPlan.requirements is not valid JSON: \" + ex.getMessage(), ex", 1);
		LEFT_AS_LOGS.put("MatchingConfigurator.java|\"patch_matching_config (set_execution_mode) answered success but match", 1);
		LEFT_AS_LOGS.put("MatchingConfigurator.java|\"set_table_settings for \" + pTablePath + \" answered success but the ta", 1);
		LEFT_AS_LOGS.put("MatchingPreviewPolicy.java|\"MatchPlan.requirements is not valid JSON: \" + ex.getMessage(), ex", 1);
		LEFT_AS_LOGS.put("MatchingPreviewPolicy.java|\"generate_matching_policy/generate_merge_policy/patch_matching_config ", 1);
	}

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)\\}");
	private static final Pattern CODED = Pattern.compile("EngineRefusal\\s*\\.\\s*(coded|codedWithCause)\\s*\\(");
	private static final Pattern CREATE_ERROR = Pattern.compile("OperationException\\s*\\.\\s*createError\\s*\\(");

	public static void main(final String[] pArgs) throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"));
		final Properties en = EngineMessageCatalogTest.load(root, "en");
		final Properties fr = EngineMessageCatalogTest.load(root, "fr");
		EngineMessageCatalogTest.noUncodedCreateError(root);
		EngineMessageCatalogTest.everyCodedCallHasItsSentences(root, en, fr);
		EngineMessageCatalogTest.bundleKeepsItsRules(en, fr);
		EngineMessageCatalogTest.waveTenBlockHasNoDuplicateKey(root);
		EngineMessageCatalogTest.sentencesRenderInBothLanguages(en);
		EngineMessageCatalogTest.aFailureWithoutMessageIsNeverNull();
		EngineMessageCatalogTest.codecRoundTrip();
		EngineMessageCatalogTest.storedCodedFailureIsWordedLaterInTheReadersLanguage();
		EngineMessageCatalogTest.codedRefusalFoldsItsEnglishTextUnderDetails();
		System.out.println("EngineMessageCatalogTest OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	// ============================================================================================ the source scan

	private static List<Path> javaFiles(final Path pRoot) throws IOException
	{
		try (Stream<Path> walk = Files.walk(pRoot))
		{
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
		}
	}

	/** The text between the parenthesis that opens at {@code pOpen} (exclusive) and its match, string literals and character literals skipped. */
	private static String argumentText(final String pSource, final int pOpen)
	{
		int depth = 1;
		int i = pOpen;
		boolean inString = false;
		boolean inChar = false;
		while (i < pSource.length() && depth > 0)
		{
			final char c = pSource.charAt(i);
			if (inString || inChar)
			{
				if (c == '\\')
				{
					i++;
				}
				else if (inString && c == '"')
				{
					inString = false;
				}
				else if (inChar && c == '\'')
				{
					inChar = false;
				}
			}
			else if (c == '"')
			{
				inString = true;
			}
			else if (c == '\'')
			{
				inChar = true;
			}
			else if (c == '(')
			{
				depth++;
			}
			else if (c == ')')
			{
				depth--;
			}
			i++;
		}
		return pSource.substring(pOpen, i - 1);
	}

	/** The top-level comma separated parts of an argument list. */
	private static List<String> split(final String pArguments)
	{
		final List<String> parts = new ArrayList<String>();
		int depth = 0;
		int start = 0;
		boolean inString = false;
		boolean inChar = false;
		for (int i = 0; i < pArguments.length(); i++)
		{
			final char c = pArguments.charAt(i);
			if (inString || inChar)
			{
				if (c == '\\')
				{
					i++;
				}
				else if (inString && c == '"')
				{
					inString = false;
				}
				else if (inChar && c == '\'')
				{
					inChar = false;
				}
			}
			else if (c == '"')
			{
				inString = true;
			}
			else if (c == '\'')
			{
				inChar = true;
			}
			else if (c == '(' || c == '[' || c == '{')
			{
				depth++;
			}
			else if (c == ')' || c == ']' || c == '}')
			{
				depth--;
			}
			else if (c == ',' && depth == 0)
			{
				parts.add(pArguments.substring(start, i));
				start = i + 1;
			}
		}
		parts.add(pArguments.substring(start));
		return parts;
	}

	private static void noUncodedCreateError(final Path pRoot) throws IOException
	{
		final Map<String, Integer> seen = new TreeMap<String, Integer>();
		for (final Path file : EngineMessageCatalogTest.javaFiles(pRoot))
		{
			final String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			final Matcher matcher = EngineMessageCatalogTest.CREATE_ERROR.matcher(source);
			while (matcher.find())
			{
				final String argument = EngineMessageCatalogTest.argumentText(source, matcher.end()).replaceAll("\\s+", " ").trim();
				final String key = file.getFileName() + "|" + argument.substring(0, Math.min(argument.length(), 70));
				seen.merge(key, 1, Integer::sum);
			}
		}
		final List<String> uncoded = new ArrayList<String>();
		for (final Map.Entry<String, Integer> entry : seen.entrySet())
		{
			final Integer allowed = EngineMessageCatalogTest.allowance(entry.getKey());
			if (allowed == null || entry.getValue().intValue() > allowed.intValue())
			{
				uncoded.add(entry.getKey());
			}
		}
		EngineMessageCatalogTest.check(uncoded.isEmpty(), "OperationException.createError(...) without a code (use EngineRefusal.coded(\"<kind>.<name>\", english, args...) and add "
			+ "engine.<kind>.<name> to both Messages_xx.properties, or list a deliberate leftover in EngineMessageCatalogTest.LEFT_AS_LOGS): " + uncoded);
		for (final Map.Entry<String, Integer> allowed : EngineMessageCatalogTest.LEFT_AS_LOGS.entrySet())
		{
			EngineMessageCatalogTest.check(EngineMessageCatalogTest.seenCount(seen, allowed.getKey()) > 0, "an allow-listed leftover no longer exists (remove it from LEFT_AS_LOGS): " + allowed.getKey());
		}
	}

	/** The allowed count for a scanned key: the allow-list entries hold the first 70 characters of the argument, like the scan. */
	private static Integer allowance(final String pScanned)
	{
		for (final Map.Entry<String, Integer> entry : EngineMessageCatalogTest.LEFT_AS_LOGS.entrySet())
		{
			if (pScanned.startsWith(entry.getKey()))
			{
				return entry.getValue();
			}
		}
		return null;
	}

	private static int seenCount(final Map<String, Integer> pSeen, final String pAllowKey)
	{
		int count = 0;
		for (final Map.Entry<String, Integer> entry : pSeen.entrySet())
		{
			if (entry.getKey().startsWith(pAllowKey))
			{
				count += entry.getValue().intValue();
			}
		}
		return count;
	}

	private static void everyCodedCallHasItsSentences(final Path pRoot, final Properties pEn, final Properties pFr) throws IOException
	{
		int calls = 0;
		final List<String> problems = new ArrayList<String>();
		for (final Path file : EngineMessageCatalogTest.javaFiles(pRoot))
		{
			final String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			final Matcher matcher = EngineMessageCatalogTest.CODED.matcher(source);
			while (matcher.find())
			{
				final boolean withCause = "codedWithCause".equals(matcher.group(1));
				final List<String> parts = EngineMessageCatalogTest.split(EngineMessageCatalogTest.argumentText(source, matcher.end()));
				final String first = parts.get(0).trim();
				if (!first.startsWith("\"") || !first.endsWith("\""))
				{
					continue; // a code that is a variable (the factory methods of EngineRefusal itself)
				}
				calls++;
				final String key = "engine." + first.substring(1, first.length() - 1);
				final String where = file.getFileName() + " " + key;
				if (pEn.getProperty(key) == null || pFr.getProperty(key) == null)
				{
					problems.add("missing sentence (en and fr) for " + where);
					continue;
				}
				int needed = 0;
				final Matcher placeholders = EngineMessageCatalogTest.PLACEHOLDER.matcher(pEn.getProperty(key));
				while (placeholders.find())
				{
					needed = Math.max(needed, Integer.parseInt(placeholders.group(1)) + 1);
				}
				final int given = parts.size() - (withCause ? 3 : 2);
				if (given < needed)
				{
					problems.add(where + ": the sentence uses " + needed + " argument(s), the call passes " + given);
				}
			}
		}
		EngineMessageCatalogTest.check(calls > 400, "the scan found only " + calls + " coded calls - did the call shape change?");
		EngineMessageCatalogTest.check(problems.isEmpty(), problems.toString());
	}

	// ============================================================================================ the bundle

	private static Properties load(final Path pRoot, final String pLanguage) throws IOException
	{
		final Path file = pRoot.resolve("../resources/com/on/ebx/factory/ebx/ui/Messages_" + pLanguage + ".properties").normalize();
		final Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(file))
		{
			properties.load(in);
		}
		return properties;
	}

	private static List<Integer> placeholders(final String pPattern)
	{
		final List<Integer> out = new ArrayList<Integer>();
		final Matcher matcher = EngineMessageCatalogTest.PLACEHOLDER.matcher(pPattern);
		while (matcher.find())
		{
			final Integer index = Integer.valueOf(matcher.group(1));
			if (!out.contains(index))
			{
				out.add(index);
			}
		}
		java.util.Collections.sort(out);
		return out;
	}

	private static void bundleKeepsItsRules(final Properties pEn, final Properties pFr)
	{
		int keys = 0;
		for (final String key : pEn.stringPropertyNames())
		{
			if (!key.startsWith("engine."))
			{
				continue;
			}
			keys++;
			final String en = pEn.getProperty(key);
			final String fr = pFr.getProperty(key);
			EngineMessageCatalogTest.check(fr != null, key + " has no French sentence");
			EngineMessageCatalogTest.check(EngineMessageCatalogTest.placeholders(en).equals(EngineMessageCatalogTest.placeholders(fr)), key + ": the placeholders differ between en and fr");
			for (final String text : new String[] { en, fr })
			{
				if (!EngineMessageCatalogTest.placeholders(text).isEmpty())
				{
					// MessageFormat: a sentence with a placeholder doubles every apostrophe; a lone one would swallow the placeholder
					EngineMessageCatalogTest.check(!text.replace("''", "").contains("'"), key + ": a single apostrophe in a sentence with a placeholder: " + text);
				}
				else
				{
					EngineMessageCatalogTest.check(!text.contains("''"), key + ": a doubled apostrophe in a sentence without placeholder (shown as is): " + text);
				}
				EngineMessageCatalogTest.check(!text.trim().isEmpty(), key + " is blank");
			}
		}
		for (final String key : pFr.stringPropertyNames())
		{
			if (key.startsWith("engine."))
			{
				EngineMessageCatalogTest.check(pEn.getProperty(key) != null, key + " has no English sentence");
			}
		}
		EngineMessageCatalogTest.check(keys > 250, "only " + keys + " engine.* keys");
	}

	private static void waveTenBlockHasNoDuplicateKey(final Path pRoot) throws IOException
	{
		for (final String language : new String[] { "en", "fr" })
		{
			final Path file = pRoot.resolve("../resources/com/on/ebx/factory/ebx/ui/Messages_" + language + ".properties").normalize();
			final List<String> lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
			final java.util.Set<String> before = new java.util.HashSet<String>();
			final java.util.Set<String> wave = new java.util.HashSet<String>();
			boolean inBlock = false;
			for (final String line : lines)
			{
				if (line.startsWith("# --- wave 10:"))
				{
					inBlock = true;
				}
				if (line.startsWith("#") || line.indexOf('=') <= 0)
				{
					continue;
				}
				final String key = line.substring(0, line.indexOf('=')).trim();
				// 2026-10-07: no key is defined twice anywhere in the file (two old duplicates were found and removed; the last one used to win silently)
				EngineMessageCatalogTest.check(!before.contains(key) || inBlock, "key " + key + " is defined twice in Messages_" + language);
				if (inBlock)
				{
					// every key of the wave-10 blocks (all agents') is defined once, and not already defined earlier in the file
					EngineMessageCatalogTest.check(wave.add(key), "duplicate key " + key + " in the wave 10 blocks of Messages_" + language);
					EngineMessageCatalogTest.check(!before.contains(key), "key " + key + " of a wave 10 block is also defined earlier in Messages_" + language);
				}
				else
				{
					before.add(key);
				}
			}
			EngineMessageCatalogTest.check(inBlock, "Messages_" + language + " has no wave 10 block");
		}
	}

	private static void sentencesRenderInBothLanguages(final Properties pEn)
	{
		for (final String key : pEn.stringPropertyNames())
		{
			if (!key.startsWith("engine."))
			{
				continue;
			}
			final List<Integer> indexes = EngineMessageCatalogTest.placeholders(pEn.getProperty(key));
			final Object[] args = new Object[indexes.isEmpty() ? 0 : indexes.get(indexes.size() - 1) + 1];
			for (int i = 0; i < args.length; i++)
			{
				args[i] = "ARG" + i;
			}
			for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
			{
				final String text = WizardMessages.get(locale, key, args);
				EngineMessageCatalogTest.check(!text.equals(key), key + " is not found in " + locale);
				EngineMessageCatalogTest.check(!text.contains("{") && !text.contains("''"), key + " in " + locale + " renders badly: " + text);
				for (final Integer index : indexes)
				{
					EngineMessageCatalogTest.check(text.contains("ARG" + index), key + " in " + locale + " lost {" + index + "}: " + text);
				}
			}
		}
		// a French sentence with an apostrophe and a placeholder really comes out with ONE apostrophe and its argument
		final String fr = WizardMessages.get(Locale.FRENCH, "engine.missing.landingZone", "SAP");
		EngineMessageCatalogTest.check(fr.startsWith("L'application SAP"), fr);
	}

	// ============================================================================================ F-23: never "null"

	private static void aFailureWithoutMessageIsNeverNull()
	{
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final EngineNotice notice = EngineNotice.of(new NullPointerException(), locale);
			EngineMessageCatalogTest.check(!"null".equals(notice.sentence) && notice.sentence.contains("NullPointerException"), "sentence: " + notice.sentence);
			EngineMessageCatalogTest.check(notice.detail != null && notice.detail.contains("NullPointerException"), "detail: " + notice.detail);
			final String inline = EngineNotice.inline(new IllegalStateException("   "), locale);
			EngineMessageCatalogTest.check(!inline.equals("null") && inline.contains("IllegalStateException"), "inline: " + inline);
		}
		EngineMessageCatalogTest.check(EngineNotice.of(new NullPointerException(), Locale.FRENCH).sentence.startsWith("L'action a "), "French sentence");
	}

	// ============================================================================================ the codec

	private static void codecRoundTrip()
	{
		final String stored = EngineMessageCodec.encode("The raw text", "engine.missing.noMasterZone", new Object[] { "P|1]", Long.valueOf(42), "é à" }, 2000);
		final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(stored);
		EngineMessageCatalogTest.check("The raw text".equals(decoded.raw) && decoded.coded(), stored);
		EngineMessageCatalogTest.check("engine.missing.noMasterZone".equals(decoded.key) && decoded.args.length == 3, stored);
		EngineMessageCatalogTest.check("P|1]".equals(decoded.args[0]) && Long.valueOf(42).equals(decoded.args[1]) && "é à".equals(decoded.args[2]), Arrays.toString(decoded.args));
		// a text with no marker, a damaged marker and null decode to themselves
		EngineMessageCatalogTest.check("plain".equals(EngineMessageCodec.decode("plain").raw) && !EngineMessageCodec.decode("plain").coded(), "plain");
		EngineMessageCatalogTest.check(!EngineMessageCodec.decode("x [mdmf:engine.a|zz]").coded(), "damaged marker");
		EngineMessageCatalogTest.check("".equals(EngineMessageCodec.decode(null).raw), "null");
		// no code: unchanged; a long text gives way to the marker, never the contrary
		EngineMessageCatalogTest.check("t".equals(EngineMessageCodec.encode("t", null, null, 100)), "uncoded");
		final StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 400; i++)
		{
			longText.append("abcdefghij");
		}
		final String cut = EngineMessageCodec.encode(longText.toString(), "engine.missing.noMasterZone", new Object[] { "P" }, 300);
		EngineMessageCatalogTest.check(cut.length() <= 300 && EngineMessageCodec.decode(cut).coded(), "length " + cut.length());
	}

	private static void storedCodedFailureIsWordedLaterInTheReadersLanguage()
	{
		final EngineRefusal refusal = EngineRefusal.coded("missing.noMasterZone", "Project ZZ has no master zone yet: run POST /projects/ZZ/master/generate first", "ZZ");
		final String stored = EngineRefusal.storedText(refusal, 2000);
		EngineMessageCatalogTest.check(stored.startsWith("Project ZZ has no master zone yet: run POST"), stored);
		final EngineNotice fr = EngineNotice.ofText(stored, Locale.FRENCH);
		EngineMessageCatalogTest.check(fr.sentence.startsWith("Le projet ZZ n'a pas encore de zone master"), fr.sentence);
		EngineMessageCatalogTest.check(fr.detail != null && fr.detail.startsWith("Project ZZ has no master zone yet") && !fr.detail.contains("mdmf:"), String.valueOf(fr.detail));
		final EngineNotice en = EngineNotice.ofText(stored, Locale.ENGLISH);
		EngineMessageCatalogTest.check(en.sentence.startsWith("Project ZZ has no master zone yet. Build"), en.sentence);
		// an uncoded stored text keeps its old behaviour
		EngineMessageCatalogTest.check("boom".equals(EngineNotice.ofText("boom", Locale.ENGLISH).sentence), "uncoded english");
		EngineMessageCatalogTest.check("boom".equals(EngineNotice.ofText("boom", Locale.FRENCH).detail), "uncoded french");
		// a job kind argument is labelled in the reader's language after the round trip
		final String jobs = EngineRefusal.storedText(EngineRefusal.jobsPending("2 job(s) of this project are waiting or running (MATCHING_RUN running).", Arrays.asList("MATCHING_RUN")), 2000);
		final EngineNotice jobsFr = EngineNotice.ofText(jobs, Locale.FRENCH);
		EngineMessageCatalogTest.check(!jobsFr.sentence.contains("MATCHING_RUN"), jobsFr.sentence);
		// a caller that wrapped the coded refusal in its own context still stores the code
		final com.orchestranetworks.service.OperationException wrapped = com.orchestranetworks.service.OperationException.createError("Could not do it: " + refusal.getMessage(), refusal);
		EngineMessageCatalogTest.check(EngineMessageCodec.decode(EngineRefusal.storedText(wrapped, 2000)).coded(), "wrapped");
	}

	private static void codedRefusalFoldsItsEnglishTextUnderDetails()
	{
		final EngineRefusal refusal = EngineRefusal.coded("external.publishModel", "publish_data_model could not publish M1: Schema has validation errors", "M1");
		EngineMessageCatalogTest.check("publish_data_model could not publish M1: Schema has validation errors".equals(refusal.getMessage()), "getMessage is the engine text");
		EngineMessageCatalogTest.check("engine.external.publishModel".equals(refusal.code()) && refusal.rawInDetail(), refusal.code());
		final EngineNotice fr = EngineNotice.of(refusal, Locale.FRENCH);
		EngineMessageCatalogTest.check("Le modèle de données M1 n'a pas pu être publié.".equals(fr.sentence), fr.sentence);
		EngineMessageCatalogTest.check(refusal.getMessage().equals(fr.detail), String.valueOf(fr.detail));
		final EngineNotice en = EngineNotice.of(refusal, Locale.ENGLISH);
		EngineMessageCatalogTest.check("The data model M1 could not be published.".equals(en.sentence) && refusal.getMessage().equals(en.detail), en.sentence);
	}
}
