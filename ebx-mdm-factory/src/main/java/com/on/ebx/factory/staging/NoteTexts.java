package com.on.ebx.factory.staging;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.wizard.EngineMessageCodec;

/**
 * Staging last points, item 1 (2026-10-17): the sentences the staging proposal passes WRITE about a link or a step - the "Value check" notes of
 * the verifier, the normalisation steps' rationales ("Normalisation: 160 of 160 rows are phone numbers written with separators..."), the code
 * table checks, the codes to align, the chain check, the preflight fix - used to be stored in English and printed as stored, so a French viewer
 * read English inside the matrix card, the cell inspector, the step 4 lists and the Proposal review.
 * <p>
 * Each of them is now a NOTE: an ordered list of segments, a segment being a message key of the module bundle ({@code staging.note.<key>}) plus its
 * arguments. The stored text is still the English sentence (what REST, MCP and logs have always read), followed by ONE marker of {@link
 * EngineMessageCodec}: {@code " [mdmf:staging.note.seq|s:v.gives|n:4|s:'trim'|s:@@f.type.DATE|n:3|n:140]"} - the key of each segment, the number of its
 * arguments, then the arguments. A screen words the segments in the viewer's language ({@code wizard.RationaleText}); a text stored before this change
 * has no marker and stays readable (plain English).
 * <p>
 * Arguments are plain texts or numbers (counts, pipelines, source names - data, shown as they are), or a FRAGMENT: a text that starts with {@value #FRAG}
 * names a bundle key without arguments ({@code @@f.type.DATE}) and is replaced by its words, so a word that is language (a type, a letter case, "the
 * value as it is") is translated and a name is not. The English of every key lives here ONCE ({@link #ENGLISH}); {@code Messages_en} must say the same
 * (a test pins it). Segments are joined by a space, except in front of a segment that starts with {@code , ; . )}. Pure, EBX-free.
 */
public final class NoteTexts
{
	/** Prefix of every message key of this class in the module bundle. */
	public static final String P = "staging.note.";
	/** The key of the marker of a stored note. */
	public static final String SEQ = NoteTexts.P + "seq";
	/** An argument that starts with this names a fragment (a bundle key under {@link #P}, without arguments). */
	public static final String FRAG = "@@";

	// ------------------------------------------------------------------------------------------------ keys

	public static final String DOT = "dot";
	// the verifier's sentence (TransformVerifier)
	public static final String V_GIVES = "v.gives";
	public static final String V_DROPS_PLUS = "v.dropsPlus";
	public static final String V_EMPTIED = "v.emptied";
	public static final String V_LOST_PLUS = "v.lostPlus";
	public static final String V_SAMPLE_FREQ = "v.sampleFreq";
	public static final String V_SAMPLE_ROWS = "v.sampleRows";
	public static final String V_FIXED_BY = "v.fixedBy";
	public static final String V_AMBIGUOUS = "v.ambiguous";
	public static final String V_NO_PATTERN = "v.noPattern";
	// the notes the normalisation run appends to a link or a step (NormalisationSuggester, StagingPreflight)
	public static final String N_FIXED = "n.fixed";
	public static final String N_WITHDRAWN = "n.withdrawn";
	public static final String N_PLUS_REPAIR = "n.plusRepair";
	public static final String N_STEP_DROPS_PLUS = "n.stepDropsPlus";
	public static final String N_NOT_WITHDRAWABLE = "n.notWithdrawable";
	public static final String N_REDUNDANT = "n.redundant";
	public static final String N_CODE_GAP = "n.codeGap";
	public static final String N_CODE_CONFLICT = "n.codeConflict";
	public static final String N_ALIGN_FIRST = "n.alignFirst";
	public static final String N_ALIGN_NEXT = "n.alignNext";
	public static final String N_ALIGN_TAIL = "n.alignTail";
	public static final String N_PREFLIGHT_FIX = "n.preflightFix";
	public static final String N_PREFLIGHT_FIX_REPLACES = "n.preflightFixReplaces";
	// the rationale of a step the rule pass writes (NormalisationPass, quality.Detectors)
	public static final String R_PREFIX = "r.prefix";
	public static final String R_TRIM = "r.trim";
	public static final String R_COLLAPSE = "r.collapse";
	public static final String R_EMPTY_TO_NULL = "r.emptyToNull";
	public static final String R_SAMPLE_DISTINCT = "r.sampleDistinct";
	public static final String R_SAMPLE_ROWS = "r.sampleRows";
	public static final String R_PHONE = "r.phone";
	public static final String R_REF_RESOLVES = "r.refResolves";
	public static final String R_CASE_ALIGN = "r.caseAlign";
	public static final String M_SAMPLE_ROWS = "m.sampleRows";
	public static final String M_SAMPLE_DISTINCT = "m.sampleDistinct";
	// the rationale of a code table step (CodeTranscodingPass)
	public static final String C_PREFIX = "c.prefix";
	public static final String C_HEAD = "c.head";
	public static final String C_BASED_ON = "c.basedOn";
	public static final String C_NOT_SHARED = "c.notShared";
	public static final String C_UNRESOLVED = "c.unresolved";
	/** Added to the note of a code table step a person edited ({@code ebx.CodeTableEdits}). */
	public static final String C_EDITED = "c.edited";
	// fragments
	public static final String F_CHAIN_RAW = "f.chainRaw";
	public static final String F_TYPE = "f.type.";
	public static final String F_NO_TRANSFORMATION = "f.noTransformation";
	public static final String F_CASE = "f.case.";
	public static final String F_CASE_WHY_TITLE = "f.caseWhyTitle";
	public static final String F_CASE_WHY_MOST = "f.caseWhyMost";

	/** The English of every key (short, without {@link #P}), {@code {0}}-style placeholders in the bundle's own dialect (an apostrophe is doubled in a text that has arguments). */
	public static final Map<String, String> ENGLISH = new LinkedHashMap<String, String>();

	static
	{
		final Map<String, String> m = NoteTexts.ENGLISH;
		m.put(NoteTexts.DOT, ".");
		m.put(NoteTexts.V_GIVES, "{0} gives a valid {1} for {2} of {3} rows");
		m.put(NoteTexts.V_DROPS_PLUS, "{0} drops the leading + of {1} of {2} rows");
		m.put(NoteTexts.V_EMPTIED, "({0} come out empty)");
		m.put(NoteTexts.V_LOST_PLUS, "({0} lose their leading +)");
		m.put(NoteTexts.V_SAMPLE_FREQ, "(most frequent values)");
		m.put(NoteTexts.V_SAMPLE_ROWS, "(on a sample of {0} rows)");
		m.put(NoteTexts.V_FIXED_BY, "''{0}'' converts all of them.");
		m.put(NoteTexts.V_AMBIGUOUS, "''{0}'' would convert all of them, but the day/month order is not provable (no value has a day above 12 or a month above 12): a person must confirm it.");
		m.put(NoteTexts.V_NO_PATTERN, "No pattern measured on the column converts all of them: a person must look at the values.");
		m.put(NoteTexts.N_FIXED, "fixed.");
		m.put(NoteTexts.N_WITHDRAWN, "withdrawn:");
		m.put(NoteTexts.N_PLUS_REPAIR, "the + was removed from the characters to strip: it carries the country of a phone number (the E.164 field reads it).");
		m.put(NoteTexts.N_STEP_DROPS_PLUS, "step {0} drops the leading + of {1} of {2} rows of a phone-like column (the + carries the country of the number)");
		m.put(NoteTexts.N_NOT_WITHDRAWABLE, "and it is not a proposal to withdraw: look at it.");
		m.put(NoteTexts.N_REDUNDANT, "step {0} changes none of the {1} rows that reach it once the steps before it have run.");
		m.put(NoteTexts.N_CODE_GAP, "code table: {0} of {1} rows ({2} distinct codes) have no target code in this source''s table; a person completes the table or rejects it.");
		m.put(NoteTexts.N_CODE_CONFLICT, "code table: {0} pair(s) of codes read the same once case and punctuation are ignored but go to different targets; a person corrects the table.");
		m.put(NoteTexts.N_ALIGN_FIRST, "codes to align: {0} distinct in {1}");
		m.put(NoteTexts.N_ALIGN_NEXT, ", {0} distinct in {1}");
		m.put(NoteTexts.N_ALIGN_TAIL, ", {0} in common out of {1}. No reference or mapping exists for them: a person decides which code means which.");
		m.put(NoteTexts.N_PREFLIGHT_FIX, "fixed before the build: {0} gave {1} refused row(s); ''{2}'' converts every sampled value.");
		m.put(NoteTexts.N_PREFLIGHT_FIX_REPLACES, "fixed before the build: {0} gave {1} refused row(s); ''{2}'' converts every sampled value (it replaces the type-driven transformation).");
		m.put(NoteTexts.R_PREFIX, "Normalisation:");
		m.put(NoteTexts.R_TRIM, "{0} of {1} value(s) had leading or trailing spaces.");
		m.put(NoteTexts.R_COLLAPSE, "{0} of {1} value(s) had a run of two or more inner spaces.");
		m.put(NoteTexts.R_EMPTY_TO_NULL, "{0} of {1} value(s) equal a known empty-ish sentinel ({2}); review before accepting on a column where one of these could be a real code "
			+ "(e.g. a legal-form abbreviation \"NA\" for a US national bank, or a status code \"NC\" for \"not classified\").");
		m.put(NoteTexts.R_SAMPLE_DISTINCT, "Measured on the {0} most frequent distinct value(s) out of {1} (sample).");
		m.put(NoteTexts.R_SAMPLE_ROWS, "Measured on a sample of {0} rows (sample).");
		m.put(NoteTexts.R_PHONE, "{0} of {1} rows are phone numbers written with separators; they are removed and the leading + is kept");
		m.put(NoteTexts.R_REF_RESOLVES, "the configured reference {0} resolves {1} of {2} rows and would rewrite {3} of them to the reference''s code");
		m.put(NoteTexts.R_CASE_ALIGN, "the sources of this field do not write it the same way: {0} of {1} rows across {2} sources are not in {3}{4}; "
			+ "{5} of this mapping''s {6} rows change so the stored values agree");
		m.put(NoteTexts.M_SAMPLE_ROWS, "(measured on a sample of {0} rows)");
		m.put(NoteTexts.M_SAMPLE_DISTINCT, "(measured on the {0} most frequent distinct values out of {1})");
		m.put(NoteTexts.C_PREFIX, "Code transcoding:");
		m.put(NoteTexts.C_HEAD, "the sources of this field do not write its short code list the same way; {0} codes of {1} ({2} rows) are mapped onto a common list of {3} codes");
		m.put(NoteTexts.C_BASED_ON, "(the codes of the reference {0})");
		m.put(NoteTexts.C_NOT_SHARED, "The codes were not shared with the language model: it proposed the common list only, {0} of {1} rows already have a target code "
			+ "and a person fills in the rest.");
		m.put(NoteTexts.C_UNRESOLVED, "{0} of {1} rows have no target code yet.");
		m.put(NoteTexts.C_EDITED, "Edited by a person.");
		m.put(NoteTexts.F_CHAIN_RAW, "the value as it is");
		m.put(NoteTexts.F_TYPE + "DATE", "date (yyyy-MM-dd)");
		m.put(NoteTexts.F_TYPE + "TIMESTAMP", "date-time");
		m.put(NoteTexts.F_TYPE + "DECIMAL", "decimal number");
		m.put(NoteTexts.F_TYPE + "INTEGER", "integer");
		m.put(NoteTexts.F_TYPE + "BOOLEAN", "boolean");
		m.put(NoteTexts.F_TYPE + "OTHER", "value");
		m.put(NoteTexts.F_NO_TRANSFORMATION, "no transformation");
		m.put(NoteTexts.F_CASE + "UPPER", "capitals");
		m.put(NoteTexts.F_CASE + "LOWER", "lower case");
		m.put(NoteTexts.F_CASE + "TITLE", "title case");
		m.put(NoteTexts.F_CASE_WHY_TITLE, ", the form a source that writes it in title case keeps (capitals or lower case would destroy that case, whatever the row counts)");
		m.put(NoteTexts.F_CASE_WHY_MOST, ", the case most rows use");
	}

	private NoteTexts()
	{
	}

	/** The fragment argument that names the bundle key {@code pShortKey}. */
	public static String frag(final String pShortKey)
	{
		return NoteTexts.FRAG + pShortKey;
	}

	// ------------------------------------------------------------------------------------------------ words

	/** Looks a message up in some language; null when the key is unknown there. */
	public interface Words
	{
		String get(String pKey, Object... pArgs);
	}

	/** The built-in English (what is stored). */
	public static final Words ENGLISH_WORDS = new Words()
	{
		@Override
		public String get(final String pKey, final Object... pArgs)
		{
			final String pattern = pKey.startsWith(NoteTexts.P) ? NoteTexts.ENGLISH.get(pKey.substring(NoteTexts.P.length())) : null;
			if (pattern == null)
			{
				return null;
			}
			if (pArgs == null || pArgs.length == 0)
			{
				return pattern;
			}
			try
			{
				return new MessageFormat(pattern, Locale.ENGLISH).format(pArgs);
			}
			catch (final IllegalArgumentException ex)
			{
				return pattern;
			}
		}
	};

	// ------------------------------------------------------------------------------------------------ note

	/** One sentence (or part of one): a key and its arguments (text or number). */
	public static final class Seg
	{
		public final String key;
		public final Object[] args;

		Seg(final String pKey, final Object[] pArgs)
		{
			this.key = pKey;
			this.args = pArgs;
		}
	}

	/** An ordered list of segments. Mutable while it is built, then read. */
	public static final class Note
	{
		private final List<Seg> segs = new ArrayList<Seg>();

		/** An empty note to {@link #add} to. */
		public Note()
		{
		}

		/** A note of one segment. */
		public static Note of(final String pKey, final Object... pArgs)
		{
			return new Note().add(pKey, pArgs);
		}

		public Note add(final String pKey, final Object... pArgs)
		{
			final Object[] args = new Object[pArgs == null ? 0 : pArgs.length];
			for (int i = 0; i < args.length; i++)
			{
				final Object arg = pArgs[i];
				args[i] = arg instanceof Number ? (Object) Long.valueOf(((Number) arg).longValue()) : (Object) (arg == null ? "" : String.valueOf(arg));
			}
			this.segs.add(new Seg(pKey, args));
			return this;
		}

		/** Appends every segment of {@code pOther}; returns this. */
		public Note addAll(final Note pOther)
		{
			if (pOther != null)
			{
				this.segs.addAll(pOther.segs);
			}
			return this;
		}

		public boolean isEmpty()
		{
			return this.segs.isEmpty();
		}

		public List<Seg> segments()
		{
			return java.util.Collections.unmodifiableList(this.segs);
		}

		/** The note in the English that is stored. */
		public String english()
		{
			final String text = NoteTexts.render(NoteTexts.ENGLISH_WORDS, this);
			return text == null ? "" : text;
		}

		/** The English followed by the marker, within {@code pMax} characters (the marker is never cut, the text gives way to it). */
		public String stored(final int pMax)
		{
			return this.stored("", pMax);
		}

		/**
		 * The English, then {@code pTail} (free text that follows the note - the language model's own words), then the marker, within {@code pMax} characters.
		 * A note with no segment is just the tail.
		 */
		public String stored(final String pTail, final int pMax)
		{
			return this.storedWith("", pTail, pMax);
		}

		/**
		 * Like {@link #stored(String, int)} with {@code pHead} written in front of the English (the " || Value check [was 85]: " of a {@link CheckNote}). When even the
		 * marker does not fit, the plain English is returned, cut with "..." - a text without a marker is still readable.
		 */
		public String storedWith(final String pHead, final String pTail, final int pMax)
		{
			final String english = this.english();
			final String tail = pTail == null ? "" : pTail.trim();
			final String text = (pHead == null ? "" : pHead) + (tail.isEmpty() ? english : english.isEmpty() ? tail : english + " " + tail);
			if (this.segs.isEmpty())
			{
				return text;
			}
			final int markerLength = EngineMessageCodec.encode("", NoteTexts.SEQ, this.flat(), Integer.MAX_VALUE).length();
			if (markerLength + (pHead == null ? 0 : pHead.length()) + 12 > pMax)
			{
				return text.length() > pMax ? text.substring(0, Math.max(0, pMax - 3)) + "..." : text;
			}
			return EngineMessageCodec.encode(text, NoteTexts.SEQ, this.flat(), pMax);
		}

		private Object[] flat()
		{
			final List<Object> out = new ArrayList<Object>();
			for (final Seg seg : this.segs)
			{
				out.add(seg.key);
				out.add(Long.valueOf(seg.args.length));
				for (final Object arg : seg.args)
				{
					out.add(arg);
				}
			}
			return out.toArray();
		}
	}

	/** The note a decoded marker holds, or null when it is not one of ours or is damaged. */
	public static Note noteOf(final EngineMessageCodec.Decoded pDecoded)
	{
		if (pDecoded == null || !NoteTexts.SEQ.equals(pDecoded.key))
		{
			return null;
		}
		final Note note = new Note();
		final Object[] args = pDecoded.args;
		int i = 0;
		while (i < args.length)
		{
			if (!(args[i] instanceof String) || i + 1 >= args.length || !(args[i + 1] instanceof Number))
			{
				return null;
			}
			final String key = (String) args[i];
			final int count = ((Number) args[i + 1]).intValue();
			i += 2;
			if (count < 0 || i + count > args.length)
			{
				return null;
			}
			final Object[] segArgs = new Object[count];
			System.arraycopy(args, i, segArgs, 0, count);
			i += count;
			note.add(key, segArgs);
		}
		return note.isEmpty() ? null : note;
	}

	/** The note in some language, or null when a key is unknown to {@code pWords}. */
	public static String render(final Words pWords, final Note pNote)
	{
		final StringBuilder out = new StringBuilder();
		for (final Seg seg : pNote.segs)
		{
			final Object[] args = new Object[seg.args.length];
			for (int i = 0; i < args.length; i++)
			{
				final Object arg = seg.args[i];
				String text = String.valueOf(arg);
				if (arg instanceof String && text.startsWith(NoteTexts.FRAG))
				{
					final String fragment = pWords.get(NoteTexts.P + text.substring(NoteTexts.FRAG.length()));
					if (fragment != null)
					{
						text = fragment;
					}
				}
				args[i] = text; // always text: a number is never grouped by the locale
			}
			final String piece = pWords.get(NoteTexts.P + seg.key, args);
			if (piece == null)
			{
				return null;
			}
			if (piece.isEmpty())
			{
				continue;
			}
			if (out.length() > 0 && NoteTexts.CLOSING.indexOf(piece.charAt(0)) < 0)
			{
				out.append(' ');
			}
			out.append(piece);
		}
		return out.toString();
	}

	private static final String CLOSING = ",;.)";

	/**
	 * The words of a stored text that holds a note, in some language: the note rendered, then the free text that followed it (the part of the stored English
	 * after the note's own English). Null when the text carries no note of ours, or a key is unknown.
	 */
	public static String display(final EngineMessageCodec.Decoded pDecoded, final Words pWords)
	{
		final Note note = NoteTexts.noteOf(pDecoded);
		if (note == null)
		{
			return null;
		}
		final String rendered = NoteTexts.render(pWords, note);
		if (rendered == null)
		{
			return null;
		}
		final String english = note.english();
		final String raw = pDecoded.raw;
		final String tail = raw.startsWith(english) ? raw.substring(english.length()).trim() : "";
		return tail.isEmpty() ? rendered : rendered.isEmpty() ? tail : rendered + " " + tail;
	}

	/**
	 * The stored text {@code pStored} with the segment {@code pKey} added at the end of its note (the free text that followed the note stays after it), or null when
	 * the text carries no note of ours (the caller then appends plain English). The same segment is not added twice in a row.
	 */
	public static String withSegment(final String pStored, final String pKey, final int pMax)
	{
		final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(pStored);
		final Note note = NoteTexts.noteOf(decoded);
		if (note == null)
		{
			return null;
		}
		final String english = note.english();
		final String tail = decoded.raw.startsWith(english) ? decoded.raw.substring(english.length()).trim() : "";
		if (!note.segs.isEmpty() && note.segs.get(note.segs.size() - 1).key.equals(pKey))
		{
			return pStored;
		}
		return note.add(pKey).stored(tail, pMax);
	}
}
