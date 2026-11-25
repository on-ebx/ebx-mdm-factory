package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.wizard.EngineMessageCodec;

/**
 * Key rationale i18n (2026-10-17): the sentences the Factory itself BUILDS about a landing table's key ({@link SourceKeyRule}, {@link KeyCertainty},
 * {@link KeyPrimeRunner}, {@link FlattenedDocument}, the "waiting for a person" note of the landing run) used to be stored in English and shown as
 * stored, so a French wizard read English. Each of them is now a CODE (a message key of the module bundle, {@code wizard.keytext.*}) plus its
 * arguments; the stored text is still the English sentence, followed by the marker {@link EngineMessageCodec} already uses
 * ({@code " [mdmf:wizard.keytext.genericId|s:customerId]"}), so a reader that does not know the marker (REST, MCP, logs) shows English and a wizard
 * screen words the code in the viewer's language. A text stored BEFORE this lot has no marker: {@link #parse} recognises the fixed English templates
 * and gives back the same code and arguments, so those rows display in French without any data migration. Anything else (the language model's own
 * rationale, a connector's error) is free text and is shown as it is. The English of a code lives here ONCE ({@link #ENGLISH}); the bundle's
 * {@code Messages_en} must say the same (a test pins it). Pure, EBX-free.
 */
public final class KeyTexts
{
	/** Prefix of every message key of this class in the module bundle. */
	public static final String P = "wizard.keytext.";

	public static final String DECLARED = KeyTexts.P + "declared";
	public static final String NONE = KeyTexts.P + "none";
	public static final String NAMED = KeyTexts.P + "named";
	public static final String NAMED_SAMPLE = KeyTexts.P + "namedSample";
	public static final String NO_PROFILE = KeyTexts.P + "noProfile";
	public static final String GENERIC_ID = KeyTexts.P + "genericId";
	public static final String GENERIC_ID_SAMPLE = KeyTexts.P + "genericIdSample";
	public static final String CHOSEN = KeyTexts.P + "chosen";
	public static final String DEDUCED = KeyTexts.P + "deduced";
	public static final String CHANGED_AFTER_LOAD = KeyTexts.P + "changedAfterLoad";
	public static final String NOT_MEASURED = KeyTexts.P + "notMeasured";
	public static final String NOT_MEASURED_TIMEOUT = KeyTexts.P + "notMeasuredTimeout";
	public static final String FLATTENED = KeyTexts.P + "flattened";
	public static final String FLATTENED_LOAD = KeyTexts.P + "flattenedLoad";
	/** "Not applied, waiting for a person - {reason} Proposal: {summary}." (the proposal's own rationale follows as a separate sentence). */
	public static final String PENDING = KeyTexts.P + "pending";

	/** Reasons of {@link #PENDING}: their codes are {@code wizard.keytext.why.<reason>}. */
	public static final String WHY_NO_EVIDENCE = "noEvidence";
	public static final String WHY_NOT_UNIQUE = "notUnique";
	public static final String WHY_LLM = "llm";
	public static final String WHY_SAMPLE_ONLY = "sampleOnly";
	public static final String WHY_NONE_CERTAIN = "noneCertain";

	/** The English of every code, {@code {0}}-style placeholders (plain substitution, no apostrophe rule: the bundle's English is the same text). */
	public static final Map<String, String> ENGLISH = new LinkedHashMap<String, String>();

	static
	{
		final Map<String, String> m = KeyTexts.ENGLISH;
		m.put(KeyTexts.DECLARED, "Primary key declared at the source.");
		m.put(KeyTexts.NONE, "No key declared at the source and no column named like the identifier of the table: pick the key columns.");
		m.put(KeyTexts.NAMED, "No key declared at the source; {0} is named like the identifier and its profile shows no null and only distinct values.");
		m.put(KeyTexts.NAMED_SAMPLE, "No key declared at the source; {0} is named like the identifier and the sample shows no null and only distinct values.");
		m.put(KeyTexts.NO_PROFILE, "No key declared at the source and no profile yet; {0} is named like the identifier of the table. Profile the table to confirm it.");
		m.put(KeyTexts.GENERIC_ID, "No key declared at the source; {0} is the only column named like an identifier (Id, _id, Number) and its profile shows no empty value and only distinct values. Confirm it.");
		m.put(KeyTexts.GENERIC_ID_SAMPLE, "No key declared at the source; {0} is the only column named like an identifier (Id, _id, Number) and the sample shows no empty value and only distinct values. Confirm it.");
		m.put(KeyTexts.CHOSEN, "Key chosen by a person in the wizard.");
		m.put(KeyTexts.DEDUCED, "Key columns proposed by the language model during a deduced scan, confirmed by a person.");
		m.put(KeyTexts.CHANGED_AFTER_LOAD, "Key changed after load: a person picked a new key once the landing table already existed.");
		m.put(KeyTexts.NOT_MEASURED_TIMEOUT, "Key not measured: the sample was not read within {0} s");
		m.put(KeyTexts.NOT_MEASURED, "Key not measured: {0}");
		m.put(KeyTexts.FLATTENED_LOAD, FlattenedDocument.MESSAGE + " (1 row was loaded.)");
		m.put(KeyTexts.FLATTENED, FlattenedDocument.MESSAGE);
		m.put(KeyTexts.PENDING, "Not applied, waiting for a person - {0} Proposal: {1}.");
		m.put(KeyTexts.P + "why." + KeyTexts.WHY_NO_EVIDENCE, "No sample evidence yet.");
		m.put(KeyTexts.P + "why." + KeyTexts.WHY_NOT_UNIQUE, "Not unique (or empty on some rows) in the sample.");
		m.put(KeyTexts.P + "why." + KeyTexts.WHY_LLM, "Suggested by the language model ({0}%{1}, {2}waiting for a person to confirm it.");
		m.put(KeyTexts.P + "why.llm.low", ", low confidence");
		m.put(KeyTexts.P + "why.llm.sourceUnique", "unique across the whole source,");
		m.put(KeyTexts.P + "why.llm.sourceNotUnique", "NOT unique across the whole source,");
		m.put(KeyTexts.P + "why." + KeyTexts.WHY_SAMPLE_ONLY, "Unique in the sample but not across the whole source.");
		m.put(KeyTexts.P + "why." + KeyTexts.WHY_NONE_CERTAIN, "No column is certain.");
		m.put(KeyTexts.P + "sum.none", "No key.");
		m.put(KeyTexts.P + "sum.with", "{0} ({1})");
		m.put(KeyTexts.P + "origin.SOURCE", "declared by the source");
		m.put(KeyTexts.P + "origin.USER", "chosen by a person");
		m.put(KeyTexts.P + "origin.DEDUCED", "proposed by the language model during a deduced scan, confirmed by a person");
		m.put(KeyTexts.P + "origin.LLM", "proposed by the language model");
		m.put(KeyTexts.P + "origin.RULE", "proposed by the naming rule");
	}

	private KeyTexts()
	{
	}

	/** Looks a message up in some language; null when the key is unknown there. */
	public interface Words
	{
		String get(String pKey, Object... pArgs);
	}

	/** The built-in English. */
	public static final Words ENGLISH_WORDS = new Words()
	{
		@Override
		public String get(final String pKey, final Object... pArgs)
		{
			final String pattern = KeyTexts.ENGLISH.get(pKey);
			if (pattern == null)
			{
				return null;
			}
			String out = pattern;
			if (pArgs != null)
			{
				for (int i = 0; i < pArgs.length; i++)
				{
					out = out.replace("{" + i + "}", String.valueOf(pArgs[i]));
				}
			}
			return out;
		}
	};

	/** A coded sentence: its key, arguments (String or Number), and English. */
	public static final class Text
	{
		public final String key;
		public final Object[] args;
		public final String english;

		Text(final String pKey, final Object[] pArgs, final String pEnglish)
		{
			this.key = pKey;
			this.args = pArgs;
			this.english = pEnglish;
		}

		/** The English sentence followed by the code marker, within {@code pMax} characters (the marker is never cut). */
		public String stored(final int pMax)
		{
			return EngineMessageCodec.encode(this.english, this.key, this.args, pMax);
		}

		/** {@link #stored} within the field limit of a message. */
		public String stored()
		{
			return this.stored(TextLimits.MESSAGE_MAX_LENGTH);
		}
	}

	/** A sentence recognised in a stored English text: the code, its arguments, and the text that followed the fixed part (only {@link #PENDING} has one). */
	public static final class Parsed
	{
		public final String key;
		public final Object[] args;
		public final String tail;

		Parsed(final String pKey, final Object[] pArgs, final String pTail)
		{
			this.key = pKey;
			this.args = pArgs;
			this.tail = pTail;
		}
	}

	private static Text text(final String pKey, final Object... pArgs)
	{
		return new Text(pKey, pArgs, KeyTexts.render(KeyTexts.ENGLISH_WORDS, pKey, pArgs));
	}

	public static Text declared()
	{
		return KeyTexts.text(KeyTexts.DECLARED);
	}

	public static Text none()
	{
		return KeyTexts.text(KeyTexts.NONE);
	}

	/** @param pFromSample whether the null/distinct figures were measured on the sample rows (otherwise on the catalog's profile) */
	public static Text named(final String pColumn, final boolean pFromSample)
	{
		return KeyTexts.text(pFromSample ? KeyTexts.NAMED_SAMPLE : KeyTexts.NAMED, pColumn);
	}

	public static Text noProfile(final String pColumn)
	{
		return KeyTexts.text(KeyTexts.NO_PROFILE, pColumn);
	}

	public static Text genericId(final String pColumn, final boolean pFromSample)
	{
		return KeyTexts.text(pFromSample ? KeyTexts.GENERIC_ID_SAMPLE : KeyTexts.GENERIC_ID, pColumn);
	}

	public static Text chosen()
	{
		return KeyTexts.text(KeyTexts.CHOSEN);
	}

	public static Text deduced()
	{
		return KeyTexts.text(KeyTexts.DEDUCED);
	}

	public static Text changedAfterLoad()
	{
		return KeyTexts.text(KeyTexts.CHANGED_AFTER_LOAD);
	}

	/** "Key not measured: the sample was not read within N s". */
	public static Text notMeasuredTimeout(final long pSeconds)
	{
		return KeyTexts.text(KeyTexts.NOT_MEASURED_TIMEOUT, Long.valueOf(pSeconds));
	}

	/** "Key not measured: {reason}" - the reason is free text (an exception message). */
	public static Text notMeasured(final String pReason)
	{
		return KeyTexts.text(KeyTexts.NOT_MEASURED, pReason == null ? "" : pReason);
	}

	public static Text flattened()
	{
		return KeyTexts.text(KeyTexts.FLATTENED);
	}

	public static Text flattenedLoad()
	{
		return KeyTexts.text(KeyTexts.FLATTENED_LOAD);
	}

	/**
	 * The note of a key proposal kept on the table while it waits for a person: the coded head, then the proposal's own rationale as a separate sentence
	 * (its coded marker, if any, is dropped - {@link #parse} gives it back from the English).
	 *
	 * @param pReason one of the {@code WHY_*} values
	 * @param pConfidence the proposal's confidence (only the {@link #WHY_LLM} reason shows it)
	 * @param pUniqueAtSource {@code TRUE}/{@code FALSE}/{@code null} (not measured), only for {@link #WHY_LLM}
	 * @param pColumnsSummary the proposal's columns as shown ({@code a+b}); empty = no key
	 * @param pOrigin {@code SOURCE}, {@code USER}, {@code DEDUCED}, {@code LLM} or anything else (the naming rule)
	 * @param pRationale the proposal's rationale, as stored (may carry a marker)
	 */
	public static Text pending(final String pReason, final int pConfidence, final Boolean pUniqueAtSource, final String pColumnsSummary, final String pOrigin,
		final String pRationale)
	{
		final Object[] args = new Object[] { pReason, Long.valueOf(pConfidence), pUniqueAtSource == null ? "" : pUniqueAtSource.booleanValue() ? "T" : "F",
			pColumnsSummary == null ? "" : pColumnsSummary, KeyTexts.originOf(pOrigin) };
		final String head = KeyTexts.render(KeyTexts.ENGLISH_WORDS, KeyTexts.PENDING, args);
		final String rationale = pRationale == null ? "" : EngineMessageCodec.decode(pRationale).raw;
		return new Text(KeyTexts.PENDING, args, rationale.isEmpty() ? head : head + " " + rationale);
	}

	private static String originOf(final String pOrigin)
	{
		return "SOURCE".equals(pOrigin) || "USER".equals(pOrigin) || "DEDUCED".equals(pOrigin) || "LLM".equals(pOrigin) ? pOrigin : "RULE";
	}

	/**
	 * The sentence of a code in some language, or null for a code this class does not know (or one the language lacks).
	 * {@link #PENDING} is its head only; the rationale that followed it is the caller's ({@link #parse}'s tail).
	 */
	public static String render(final Words pWords, final String pKey, final Object[] pArgs)
	{
		if (pKey == null || !pKey.startsWith(KeyTexts.P) || !KeyTexts.ENGLISH.containsKey(pKey))
		{
			return null;
		}
		final Object[] args = pArgs == null ? new Object[0] : pArgs;
		if (!KeyTexts.PENDING.equals(pKey))
		{
			return pWords.get(pKey, args);
		}
		if (args.length < 5)
		{
			return null;
		}
		final String reasonKey = KeyTexts.P + "why." + args[0];
		final String reason;
		if (KeyTexts.WHY_LLM.equals(String.valueOf(args[0])))
		{
			final long confidence = args[1] instanceof Number ? ((Number) args[1]).longValue() : 0L;
			final String low = confidence < KeyCertainty.LLM_CONFIDENCE_MIN ? pWords.get(KeyTexts.P + "why.llm.low") : "";
			final String atSource = String.valueOf(args[2]);
			final String source = "T".equals(atSource) ? pWords.get(KeyTexts.P + "why.llm.sourceUnique") : "F".equals(atSource) ? pWords.get(KeyTexts.P + "why.llm.sourceNotUnique") : null;
			if (low == null)
			{
				return null;
			}
			reason = pWords.get(reasonKey, String.valueOf(confidence), low, source == null ? "" : source + " ");
		}
		else
		{
			reason = pWords.get(reasonKey);
		}
		final String columns = String.valueOf(args[3]);
		final String summary;
		if (columns.isEmpty())
		{
			summary = pWords.get(KeyTexts.P + "sum.none");
		}
		else
		{
			final String origin = pWords.get(KeyTexts.P + "origin." + args[4]);
			summary = origin == null ? null : pWords.get(KeyTexts.P + "sum.with", columns, origin);
		}
		if (reason == null || summary == null)
		{
			return null;
		}
		return pWords.get(KeyTexts.PENDING, reason, summary);
	}

	// ---------------------------------------------------------------------------------------------------- reading what was stored before the marker existed

	private static final class Template
	{
		final String key;
		final Pattern pattern;

		Template(final String pKey, final String pEnglish)
		{
			this.key = pKey;
			final String[] parts = pEnglish.split("\\{0\\}", -1);
			final StringBuilder regex = new StringBuilder("\\A");
			for (int i = 0; i < parts.length; i++)
			{
				if (i > 0)
				{
					regex.append("(.+)");
				}
				regex.append(Pattern.quote(parts[i]));
			}
			regex.append("\\z");
			this.pattern = Pattern.compile(regex.toString(), Pattern.DOTALL);
		}
	}

	private static final List<Template> TEMPLATES = new ArrayList<Template>();

	static
	{
		for (final String key : new String[] { KeyTexts.DECLARED, KeyTexts.NONE, KeyTexts.NAMED, KeyTexts.NAMED_SAMPLE, KeyTexts.NO_PROFILE, KeyTexts.GENERIC_ID,
			KeyTexts.GENERIC_ID_SAMPLE, KeyTexts.CHOSEN, KeyTexts.DEDUCED, KeyTexts.CHANGED_AFTER_LOAD, KeyTexts.NOT_MEASURED_TIMEOUT, KeyTexts.NOT_MEASURED,
			KeyTexts.FLATTENED_LOAD, KeyTexts.FLATTENED })
		{
			KeyTexts.TEMPLATES.add(new Template(key, KeyTexts.ENGLISH.get(key)));
		}
	}

	private static final String ORIGINS = "declared by the source|chosen by a person|proposed by the language model during a deduced scan, confirmed by a person"
		+ "|proposed by the language model|proposed by the naming rule";
	private static final Pattern PENDING_PATTERN = Pattern.compile("\\ANot applied, waiting for a person - (No sample evidence yet\\.|Not unique \\(or empty on some rows\\) in the sample\\."
		+ "|Suggested by the language model \\((\\d+)%(?:, low confidence)?,( unique across the whole source,| NOT unique across the whole source,)? waiting for a person to confirm it\\."
		+ "|Unique in the sample but not across the whole source\\.|No column is certain\\.)"
		+ " Proposal: (?:No key\\.|(.+?) \\((" + KeyTexts.ORIGINS + ")\\))\\.(?: (.*))?\\z", Pattern.DOTALL);

	/**
	 * Recognises a stored English text built by this module (without or without its marker): the code, the arguments and, for a waiting proposal, the
	 * rationale that follows. Null when the text is none of the fixed templates (free text).
	 */
	public static Parsed parse(final String pEnglish)
	{
		if (pEnglish == null || pEnglish.isEmpty())
		{
			return null;
		}
		final String text = EngineMessageCodec.decode(pEnglish).raw;
		for (final Template template : KeyTexts.TEMPLATES)
		{
			final Matcher matcher = template.pattern.matcher(text);
			if (matcher.matches())
			{
				final Object[] args = new Object[matcher.groupCount()];
				for (int i = 0; i < args.length; i++)
				{
					final String value = matcher.group(i + 1);
					args[i] = KeyTexts.NOT_MEASURED_TIMEOUT.equals(template.key) ? (Object) Long.valueOf(KeyTexts.number(value)) : value;
				}
				return new Parsed(template.key, args, "");
			}
		}
		final Matcher pending = KeyTexts.PENDING_PATTERN.matcher(text);
		if (pending.matches())
		{
			final String why = pending.group(1);
			final String reason = why.startsWith("No sample") ? KeyTexts.WHY_NO_EVIDENCE : why.startsWith("Not unique") ? KeyTexts.WHY_NOT_UNIQUE
				: why.startsWith("Suggested") ? KeyTexts.WHY_LLM : why.startsWith("Unique in") ? KeyTexts.WHY_SAMPLE_ONLY : KeyTexts.WHY_NONE_CERTAIN;
			final String atSource = pending.group(3) == null ? "" : pending.group(3).contains("NOT") ? "F" : "T";
			final String columns = pending.group(4) == null ? "" : pending.group(4);
			final String origin = pending.group(5) == null ? "RULE" : KeyTexts.originFromSummary(pending.group(5));
			final String tail = pending.group(6) == null ? "" : pending.group(6);
			return new Parsed(KeyTexts.PENDING, new Object[] { reason, Long.valueOf(pending.group(2) == null ? 0L : KeyTexts.number(pending.group(2))), atSource, columns, origin },
				tail);
		}
		return null;
	}

	private static long number(final String pValue)
	{
		try
		{
			return Long.parseLong(pValue.trim());
		}
		catch (final NumberFormatException ex)
		{
			return 0L;
		}
	}

	private static String originFromSummary(final String pOriginText)
	{
		for (final String origin : new String[] { "SOURCE", "USER", "DEDUCED", "LLM", "RULE" })
		{
			if (KeyTexts.ENGLISH.get(KeyTexts.P + "origin." + origin).equals(pOriginText))
			{
				return origin;
			}
		}
		return "RULE";
	}

	/**
	 * Keeps a stored text within {@code pMax} characters without cutting its marker: the text gives way to it. A text without marker is cut at the end.
	 */
	public static String fit(final String pStored, final int pMax)
	{
		return EngineMessageCodec.fit(pStored, pMax);
	}
}
