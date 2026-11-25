package com.on.ebx.factory.staging;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.on.ebx.factory.quality.Detectors;
import com.on.ebx.factory.transfer.TransformationCatalogue;

/**
 * Staging normalisation lot D (2026-10-17): runs a mapping's transform chain on the values of its landing column and
 * says whether the result will be accepted by the typed staging attribute - BEFORE the load finds out, a row at a time
 * (measured live, project TiersB2CRetestLanding, 2026-10-17: {@code isodate()} on a text date {@code 10/06/1963}
 * left it as it was, every row of the loyalty source was refused at the load, and the link that did it sat at 48 %
 * outside the points the matrix flagged). Pure: the chain itself is run by an {@link Evaluator} the caller supplies
 * (in production {@code Transformations.apply} of the Connectors mapping engine - the very code the loader runs, see
 * {@code ebx.NormalisationSuggester}), so this class compiles and is tested on its own.
 * <p>
 * <b>What is checked</b>
 * <ul>
 * <li>For a typed target ({@code DATE}, {@code TIMESTAMP}, {@code DECIMAL}, {@code INTEGER}, {@code BOOLEAN}): each
 * non-empty input must come out of the chain as the xs lexical form EBX's {@code setValueFromXsString} accepts
 * ({@link #isValidFor}); a value that comes out empty, throws, or comes out in another form counts as failed.</li>
 * <li>For a phone-like column ({@link NormalisationProfile#isPhoneLike}), whatever the target: a value that started with
 * {@code +} must still start with {@code +} (a {@code digits()} or a strip of {@code +} turns {@code +33 6 46 86 05 23}
 * into {@code 33646860523}, a number nobody can tell from a national one).</li>
 * </ul>
 * <b>What it does about a failure</b>: it tries the pipelines the PROFILE suggests ({@link NormalisationProfiler#dateCandidates}
 * for a date, number-format variants for a decimal, a {@code map} of the vocabulary found for a boolean, the pipeline
 * without its {@code digits} step for a phone), and replaces the chain only by a candidate that converts <b>every</b>
 * non-empty input ({@link #FIXED}); a candidate resting on a day/month order no value proves is returned as {@link
 * #AMBIGUOUS} and never applied; when nothing converts every value the result is {@link #FAILING} and the caller
 * flags the link for a person. The measured success rate of the chain as it was ({@link Result#ratePercent}) is what
 * the caller records as the link's confidence.
 * <p>
 * Weighted by row count: the inputs are the distinct values of the column with their rows ({@code
 * Detectors.ValueCount}), the sample the proposal already reads.
 */
public final class TransformVerifier
{
	/** The chain converts every non-empty input. */
	public static final String OK = "OK";
	/** The chain failed and a profile-derived replacement converts every non-empty input. */
	public static final String FIXED = "FIXED";
	/** The chain fails on some inputs and no replacement converts them all. */
	public static final String FAILING = "FAILING";
	/** A replacement converts every input but rests on an unprovable day/month order: not applied, the link is flagged. */
	public static final String AMBIGUOUS = "AMBIGUOUS";
	/** The chain cannot be run here (it reads another column or a live lookup): nothing is claimed. */
	public static final String UNVERIFIABLE = "UNVERIFIABLE";
	/** Nothing to check (a text target on a column that is not phone-like, or no input). */
	public static final String NOT_CHECKED = "NOT_CHECKED";

	private static final Pattern XS_DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
	private static final Pattern XS_DATETIME = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2})(\\.\\d{1,9})?$");
	private static final Pattern XS_DECIMAL = Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)$");
	private static final Pattern XS_INTEGER = Pattern.compile("^[+-]?\\d{1,10}$");

	/** Pipeline step names that read another column, a live table or the clock: the chain cannot be judged on one column's values. */
	private static final String[] NOT_VERIFIABLE_STEPS = { "concat", "joinwidth", "join", "first", "count", "fk", "lookup", "golden", "mdmstate", "runid", "now", "hash", "constant" };

	private TransformVerifier()
	{
	}

	/** Runs one pipeline on one value. Throws {@link Unverifiable} when the chain cannot be run here at all, any other exception when the VALUE does not convert. */
	public interface Evaluator
	{
		String apply(String pPipeline, String pValue) throws Exception;
	}

	/** The chain cannot be run here (missing context): not a failure of the value. */
	public static final class Unverifiable extends Exception
	{
		private static final long serialVersionUID = 1L;

		public Unverifiable(final String pMessage)
		{
			super(pMessage);
		}
	}

	/** What {@link #verify} found. Never holds a value: counts, percentages, a pipeline text. */
	public static final class Result
	{
		public final String verdict;
		public final long checkedRows;
		public final long okRows;
		public final long failedRows;
		public final long emptiedRows;
		/** Rows that started with {@code +} and no longer do. */
		public final long lostPlusRows;
		/** Rows converted by the chain as it was, 0-100 ({@code 100} only when every row converts). */
		public final int ratePercent;
		/** {@link #FIXED}: the pipeline to use instead; {@link #AMBIGUOUS}: the candidate that is NOT applied; otherwise null. */
		public final String replacementPipeline;
		/** Rows the replacement converts, 0-100. */
		public final int replacementRatePercent;
		/** One English sentence saying what was found - counts and pipelines, no value. Empty for {@link #OK} / {@link #NOT_CHECKED}. */
		public final String reason;
		/** Staging last points (2026-10-17): the same sentence as a coded note (stored in English with its marker, worded in the viewer's language). Empty when {@link #reason} is. */
		public final NoteTexts.Note note;
		/** The inputs were the most frequent distinct values, not the whole column. */
		public final boolean sampled;

		Result(final String pVerdict, final long pChecked, final long pOk, final long pFailed, final long pEmptied, final long pLostPlus, final int pRate,
			final String pReplacement, final int pReplacementRate, final NoteTexts.Note pNote, final boolean pSampled)
		{
			this.verdict = pVerdict;
			this.checkedRows = pChecked;
			this.okRows = pOk;
			this.failedRows = pFailed;
			this.emptiedRows = pEmptied;
			this.lostPlusRows = pLostPlus;
			this.ratePercent = pRate;
			this.replacementPipeline = pReplacement;
			this.replacementRatePercent = pReplacementRate;
			this.note = pNote == null ? new NoteTexts.Note() : pNote;
			this.reason = this.note.english();
			this.sampled = pSampled;
		}

		/** True when the link must not be accepted in bulk: the chain fails and nothing fixed it. */
		public boolean needsAPerson()
		{
			return FAILING.equals(this.verdict) || AMBIGUOUS.equals(this.verdict);
		}
	}

	// --------------------------------------------------------------------------------------------------- lexical form

	/** Whether {@code pValue} is a form the typed staging attribute accepts at write time ({@code setValueFromXsString}). A {@code STRING} target accepts anything. */
	public static boolean isValidFor(final String pDataType, final String pValue)
	{
		final String type = pDataType == null ? "STRING" : pDataType.trim().toUpperCase(Locale.ROOT);
		if (pValue == null)
		{
			return false;
		}
		final String value = pValue.trim();
		if ("DATE".equals(type))
		{
			final java.util.regex.Matcher matcher = TransformVerifier.XS_DATE.matcher(value);
			return matcher.matches() && TransformVerifier.validDate(value);
		}
		if ("TIMESTAMP".equals(type))
		{
			final java.util.regex.Matcher matcher = TransformVerifier.XS_DATETIME.matcher(value);
			if (!matcher.matches())
			{
				return false;
			}
			try
			{
				LocalDateTime.parse(matcher.group(1) + "T" + matcher.group(2));
				return true;
			}
			catch (final DateTimeException ex)
			{
				return false;
			}
		}
		if ("DECIMAL".equals(type))
		{
			return TransformVerifier.XS_DECIMAL.matcher(value).matches();
		}
		if ("INTEGER".equals(type))
		{
			if (!TransformVerifier.XS_INTEGER.matcher(value).matches())
			{
				return false;
			}
			return new BigDecimal(value).abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0;
		}
		if ("BOOLEAN".equals(type))
		{
			return "true".equals(value) || "false".equals(value) || "1".equals(value) || "0".equals(value);
		}
		return true;
	}

	private static boolean validDate(final String pIsoDate)
	{
		try
		{
			LocalDate.parse(pIsoDate);
			return true;
		}
		catch (final DateTimeException ex)
		{
			return false;
		}
	}

	/** True for a target type whose lexical form is checked. */
	public static boolean isTyped(final String pDataType)
	{
		final String type = pDataType == null ? "" : pDataType.trim().toUpperCase(Locale.ROOT);
		return "DATE".equals(type) || "TIMESTAMP".equals(type) || "DECIMAL".equals(type) || "INTEGER".equals(type) || "BOOLEAN".equals(type);
	}

	/**
	 * How many of {@code pOutputs} (the already-computed outputs of a chain, empty ones skipped) are NOT a valid form of
	 * {@code pDataType} - the live check the Mapping matrix runs on the values it samples, without a profile. 0 for a text target.
	 */
	public static int invalidOutputs(final String pDataType, final List<String> pOutputs)
	{
		if (!TransformVerifier.isTyped(pDataType) || pOutputs == null)
		{
			return 0;
		}
		int invalid = 0;
		for (final String output : pOutputs)
		{
			if (output != null && !output.trim().isEmpty() && !TransformVerifier.isValidFor(pDataType, output))
			{
				invalid++;
			}
		}
		return invalid;
	}

	// ------------------------------------------------------------------------------------------------------- verify

	/**
	 * @param pDataType the staging attribute's data type ({@code STRING}...)
	 * @param pPipeline the chain as the loader will run it (a type-driven mapping already translated); blank = no transformation
	 * @param pInputs the distinct values of the column, AFTER the mapping's cleaning steps, with their rows
	 * @param pProfile the column's profile (candidates and the phone check are derived from it); may be null
	 * @param pSampled whether {@code pInputs} are the most frequent distinct values only
	 */
	public static Result verify(final String pDataType, final String pPipeline, final List<Detectors.ValueCount> pInputs, final NormalisationProfile pProfile,
		final Evaluator pEvaluator, final boolean pSampled)
	{
		return TransformVerifier.verify(pDataType, pPipeline, pInputs, pProfile, pEvaluator, pSampled, 0L);
	}

	/**
	 * Staging polish (2026-10-17): {@code pSampleRows} &gt; 0 when {@code pInputs} were read on the first N rows of a big table ({@code quality.ScanGuard}); the
	 * verdict's sentence then says "on a sample of N rows" instead of "most frequent values" ({@code pSampled} must be true).
	 */
	public static Result verify(final String pDataType, final String pPipeline, final List<Detectors.ValueCount> pInputs, final NormalisationProfile pProfile,
		final Evaluator pEvaluator, final boolean pSampled, final long pSampleRows)
	{
		final String pipeline = pPipeline == null ? "" : pPipeline.trim();
		final boolean typed = TransformVerifier.isTyped(pDataType);
		final boolean plusCheck = pProfile != null && pProfile.isPhoneLike() && pProfile.leadingPlusRows() > 0L;
		if (!typed && !plusCheck)
		{
			return TransformVerifier.plain(NOT_CHECKED, 0L, 0, pSampled);
		}
		if (TransformVerifier.hasNotVerifiableStep(pipeline))
		{
			return TransformVerifier.plain(UNVERIFIABLE, 0L, 0, pSampled);
		}
		final Tally own;
		try
		{
			own = TransformVerifier.run(pDataType, pipeline, pInputs, pEvaluator, plusCheck);
		}
		catch (final Unverifiable ex)
		{
			return TransformVerifier.plain(UNVERIFIABLE, 0L, 0, pSampled);
		}
		if (own.checked == 0L)
		{
			return TransformVerifier.plain(NOT_CHECKED, 0L, 0, pSampled);
		}
		final int rate = TransformVerifier.rate(own.ok, own.checked);
		if (own.ok == own.checked)
		{
			return new Result(OK, own.checked, own.ok, 0L, 0L, 0L, 100, null, 100, null, pSampled);
		}
		// the chain fails somewhere: try what the profile suggests
		String ambiguousPipeline = null;
		int ambiguousRate = 0;
		for (final Candidate candidate : TransformVerifier.candidates(pDataType, pipeline, pInputs, pProfile))
		{
			final Tally tally;
			try
			{
				tally = TransformVerifier.run(pDataType, candidate.pipeline, pInputs, pEvaluator, plusCheck);
			}
			catch (final Unverifiable ex)
			{
				continue;
			}
			if (tally.checked > 0L && tally.ok == tally.checked)
			{
				if (candidate.certain)
				{
					return new Result(FIXED, own.checked, own.ok, own.failed, own.emptied, own.lostPlus, rate, candidate.pipeline, 100,
						TransformVerifier.sentence(pDataType, pipeline, own, candidate.pipeline, true, pSampled, pSampleRows), pSampled);
				}
				if (ambiguousPipeline == null)
				{
					ambiguousPipeline = candidate.pipeline;
					ambiguousRate = 100;
				}
			}
		}
		if (ambiguousPipeline != null)
		{
			return new Result(AMBIGUOUS, own.checked, own.ok, own.failed, own.emptied, own.lostPlus, rate, ambiguousPipeline, ambiguousRate,
				TransformVerifier.sentence(pDataType, pipeline, own, ambiguousPipeline, false, pSampled, pSampleRows), pSampled);
		}
		return new Result(FAILING, own.checked, own.ok, own.failed, own.emptied, own.lostPlus, rate, null, 0,
			TransformVerifier.sentence(pDataType, pipeline, own, null, false, pSampled, pSampleRows), pSampled);
	}

	private static Result plain(final String pVerdict, final long pChecked, final int pRate, final boolean pSampled)
	{
		return new Result(pVerdict, pChecked, pChecked, 0L, 0L, 0L, pRate, null, 0, null, pSampled);
	}

	private static int rate(final long pOk, final long pChecked)
	{
		if (pChecked <= 0L)
		{
			return 0;
		}
		if (pOk >= pChecked)
		{
			return 100;
		}
		return (int) Math.min(99L, (100L * pOk) / pChecked);
	}

	/** Staging preflight lot H1 (2026-10-17): whether the chain reads another column, a live table or the clock - the very test {@link #verify} makes before it says {@link #UNVERIFIABLE}. */
	static boolean readsOutsideItsColumn(final String pPipeline)
	{
		return TransformVerifier.hasNotVerifiableStep(pPipeline == null ? "" : pPipeline.trim());
	}

	private static boolean hasNotVerifiableStep(final String pPipeline)
	{
		if (pPipeline.isEmpty())
		{
			return false;
		}
		for (final TransformationCatalogue.Step step : TransformationCatalogue.parsePipeline(pPipeline))
		{
			final String name = step.name == null ? "" : step.name.trim().toLowerCase(Locale.ROOT);
			for (final String blocked : TransformVerifier.NOT_VERIFIABLE_STEPS)
			{
				if (blocked.equals(name))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Weighted counts of one run. */
	private static final class Tally
	{
		long checked;
		long ok;
		long failed;
		long emptied;
		long lostPlus;
	}

	private static Tally run(final String pDataType, final String pPipeline, final List<Detectors.ValueCount> pInputs, final Evaluator pEvaluator,
		final boolean pPlusCheck) throws Unverifiable
	{
		final Tally tally = new Tally();
		if (pInputs == null)
		{
			return tally;
		}
		final boolean typed = TransformVerifier.isTyped(pDataType);
		for (final Detectors.ValueCount input : pInputs)
		{
			if (input.value == null || input.value.trim().isEmpty())
			{
				continue;
			}
			tally.checked += input.count;
			String output;
			try
			{
				output = pPipeline.isEmpty() ? input.value.trim() : pEvaluator.apply(pPipeline, input.value);
			}
			catch (final Unverifiable ex)
			{
				throw ex;
			}
			catch (final NullPointerException | IllegalStateException ex)
			{
				throw new Unverifiable("the chain needs a context this check does not have");
			}
			catch (final Exception ex)
			{
				tally.failed += input.count;
				continue;
			}
			if (output == null || output.trim().isEmpty())
			{
				tally.failed += input.count;
				tally.emptied += input.count;
				continue;
			}
			if (pPlusCheck && input.value.trim().startsWith("+") && !output.trim().startsWith("+"))
			{
				tally.failed += input.count;
				tally.lostPlus += input.count;
				continue;
			}
			if (typed && !TransformVerifier.isValidFor(pDataType, output))
			{
				tally.failed += input.count;
				continue;
			}
			tally.ok += input.count;
		}
		return tally;
	}

	// ----------------------------------------------------------------------------------------------------- candidates

	private static final class Candidate
	{
		final String pipeline;
		final boolean certain;

		Candidate(final String pPipeline, final boolean pCertain)
		{
			this.pipeline = pPipeline;
			this.certain = pCertain;
		}
	}

	private static List<Candidate> candidates(final String pDataType, final String pCurrentPipeline, final List<Detectors.ValueCount> pInputs,
		final NormalisationProfile pProfile)
	{
		final List<Candidate> out = new ArrayList<Candidate>();
		final String type = pDataType == null ? "STRING" : pDataType.trim().toUpperCase(Locale.ROOT);
		if (pProfile != null && ("DATE".equals(type) || "TIMESTAMP".equals(type)))
		{
			for (final NormalisationProfiler.DateCandidate date : NormalisationProfiler.dateCandidates(pProfile, "TIMESTAMP".equals(type)))
			{
				out.add(new Candidate(date.pipeline, date.certain));
			}
		}
		else if ("DECIMAL".equals(type) || "INTEGER".equals(type))
		{
			// each variant only where the profile SHOWS that thousand separator: removing a dot turns 13.5 into 135 and would still "convert" it
			out.add(new Candidate("trim|number", true));
			if (pProfile != null && pProfile.numberShapes.containsKey("THOUSAND_SPACE"))
			{
				out.add(new Candidate("trim|replace(' ','')|replace('\u00a0','')|replace('\u202f','')|number", true));
			}
			if (pProfile != null && pProfile.numberShapes.containsKey("THOUSAND_DOT_COMMA") && !pProfile.numberShapes.containsKey("DEC_DOT"))
			{
				out.add(new Candidate("trim|replace('.','')|replace(',','.')|number", true));
			}
			if (pProfile != null && pProfile.numberShapes.containsKey("THOUSAND_COMMA_DOT") && !pProfile.numberShapes.containsKey("DEC_COMMA"))
			{
				out.add(new Candidate("trim|replace(',','')|number", true));
			}
		}
		else if ("BOOLEAN".equals(type))
		{
			final String map = TransformVerifier.booleanMap(pInputs);
			if (map != null)
			{
				out.add(new Candidate("trim|map('" + map + "')", true));
			}
		}
		if (pProfile != null && pProfile.isPhoneLike() && pProfile.leadingPlusRows() > 0L)
		{
			final String withoutDigits = TransformVerifier.withoutStep(pCurrentPipeline, "digits");
			if (withoutDigits != null)
			{
				out.add(new Candidate(withoutDigits, true));
			}
		}
		return out;
	}

	/** {@code a=true;b=false;...} for every distinct input when ALL of them are boolean words made of safe characters; null otherwise (a word outside the vocabulary would silently become false). */
	static String booleanMap(final List<Detectors.ValueCount> pInputs)
	{
		if (pInputs == null)
		{
			return null;
		}
		final Map<String, Boolean> words = new TreeMap<String, Boolean>();
		for (final Detectors.ValueCount input : pInputs)
		{
			if (input.value == null || input.value.trim().isEmpty())
			{
				continue;
			}
			final String word = input.value.trim().toLowerCase(Locale.ROOT);
			final Boolean meaning = NormalisationProfiler.booleanWord(word);
			if (meaning == null || !word.matches("[\\p{L}\\p{N}_]+"))
			{
				return null;
			}
			words.put(word, meaning);
		}
		if (words.isEmpty())
		{
			return null;
		}
		final StringBuilder out = new StringBuilder();
		for (final Map.Entry<String, Boolean> entry : words.entrySet())
		{
			if (out.length() > 0)
			{
				out.append(';');
			}
			out.append(entry.getKey()).append('=').append(entry.getValue().booleanValue() ? "true" : "false");
		}
		return out.toString();
	}

	/** {@code pPipeline} without its steps named {@code pStepName}; {@code trim} when nothing is left; null when the pipeline does not hold that step. */
	static String withoutStep(final String pPipeline, final String pStepName)
	{
		if (pPipeline == null || pPipeline.trim().isEmpty())
		{
			return null;
		}
		final List<TransformationCatalogue.Step> kept = new ArrayList<TransformationCatalogue.Step>();
		boolean removed = false;
		for (final TransformationCatalogue.Step step : TransformationCatalogue.parsePipeline(pPipeline))
		{
			if (step.name != null && step.name.trim().equalsIgnoreCase(pStepName))
			{
				removed = true;
			}
			else
			{
				kept.add(step);
			}
		}
		if (!removed)
		{
			return null;
		}
		return kept.isEmpty() ? "trim" : TransformationCatalogue.joinSteps(kept);
	}

	/**
	 * A STRIP_CHARS argument without the {@code +} sign when the column is phone-like and carries leading plus signs;
	 * null when the argument is fine (or the column is not such a column). Never makes an argument up: it only removes the
	 * plus.
	 */
	public static String repairStripChars(final String pArgument, final NormalisationProfile pProfile)
	{
		if (pArgument == null || pArgument.indexOf('+') < 0 || pProfile == null || !pProfile.isPhoneLike() || pProfile.leadingPlusRows() == 0L)
		{
			return null;
		}
		return pArgument.replace("+", "");
	}

	// ------------------------------------------------------------------------------------------------------ wording

	private static NoteTexts.Note sentence(final String pDataType, final String pPipeline, final Tally pOwn, final String pReplacement, final boolean pCertain,
		final boolean pSampled, final long pSampleRows)
	{
		final String type = pDataType == null ? "STRING" : pDataType.trim().toUpperCase(Locale.ROOT);
		final NoteTexts.Note out = new NoteTexts.Note();
		final String chain = pPipeline.isEmpty() ? NoteTexts.frag(NoteTexts.F_CHAIN_RAW) : "'" + pPipeline + "'";
		if (pOwn.lostPlus > 0L && pOwn.lostPlus == pOwn.failed)
		{
			out.add(NoteTexts.V_DROPS_PLUS, chain, Long.valueOf(pOwn.lostPlus), Long.valueOf(pOwn.checked));
		}
		else
		{
			out.add(NoteTexts.V_GIVES, chain, NoteTexts.frag(TransformVerifier.typeFragment(type)), Long.valueOf(pOwn.ok), Long.valueOf(pOwn.checked));
			if (pOwn.emptied > 0L)
			{
				out.add(NoteTexts.V_EMPTIED, Long.valueOf(pOwn.emptied));
			}
			if (pOwn.lostPlus > 0L)
			{
				out.add(NoteTexts.V_LOST_PLUS, Long.valueOf(pOwn.lostPlus));
			}
		}
		if (pSampleRows > 0L)
		{
			out.add(NoteTexts.V_SAMPLE_ROWS, String.format(Locale.ROOT, "%,d", Long.valueOf(pSampleRows)));
		}
		else if (pSampled)
		{
			out.add(NoteTexts.V_SAMPLE_FREQ);
		}
		out.add(NoteTexts.DOT);
		if (pReplacement != null && pCertain)
		{
			out.add(NoteTexts.V_FIXED_BY, pReplacement);
		}
		else if (pReplacement != null)
		{
			out.add(NoteTexts.V_AMBIGUOUS, pReplacement);
		}
		else
		{
			out.add(NoteTexts.V_NO_PATTERN);
		}
		return out;
	}

	/** The fragment key ({@code f.type.<X>}) of the words of a target type. */
	private static String typeFragment(final String pType)
	{
		if ("DATE".equals(pType) || "TIMESTAMP".equals(pType) || "DECIMAL".equals(pType) || "INTEGER".equals(pType) || "BOOLEAN".equals(pType))
		{
			return NoteTexts.F_TYPE + pType;
		}
		return NoteTexts.F_TYPE + "OTHER";
	}
}
