package com.on.ebx.factory.rejects;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Staging load and rejects lot (2026-10-17): turns what EBX said when it refused to save a staging row into a {@link RejectReason} that names the field, the source column and
 * the value. EBX's own message for a value that does not convert ("Unexpected error... Caused by: ODateFormat[objectConverters=null, stringConverters=null]conversion error while
 * handling input string '10/06/1963'") names neither: the field is found by looking, in the row that was being written, for the field whose value IS that input string (and
 * whose type agrees with the converter that failed). A message that matches nothing known stays a generic {@link RejectReason#ebxRefused} whose raw text is kept as detail.
 * <p>
 * <b>EBX refusal classes lot H4 (2026-10-17): the other refusals.</b> The same method now recognises, by the words of EBX's own messages, the refusals a save can meet besides a
 * conversion: a text over the maximum length, a value outside a list of values, a foreign key to a record that does not exist, a mandatory field left empty, a duplicate primary key,
 * a pattern or a range / digit facet violated, a blocking constraint, and a table trigger that failed. Where the message does not name the field, it is found the way the
 * conversion's is - by the row's values (a text longer than the limit, a value equal to the one quoted, a value that does not match the pattern) - and left out when two fields
 * qualify: a reason never guesses. Each pattern below was built from a REAL text, named in its Javadoc (EBX's own message bundles in {@code ebx-lib.jar}, the live
 * {@code validation.log} and {@code kernel.log}); see also {@code docs/UX-STAGING-TEST-FIXES-B.md} (section H4).
 * <p>
 * Only a failure of EBX is classified here; the loader's own checks (mandatory, length, parent, quality rule) already build their reasons themselves. A header written by
 * {@link RefusalHints} (a trigger, a blocking constraint) is read and removed. Pure, EBX-free.
 */
public final class RejectClassifier
{
	/** The prefix the loader puts before EBX's message in the stored English text of an older reject. */
	static final String LEGACY_PREFIX = "EBX refused the save:";

	/** {@code ODateFormat[objectConverters=null, ...]conversion error while handling input string '10/06/1963'}; the converter name is optional. */
	private static final Pattern CONVERSION = Pattern
		.compile("(?:(O[A-Za-z]*Format)\\[[^\\]]*\\]\\s*)?conversion error while handling input string '([^']*)'", Pattern.DOTALL);

	// The patterns below read the text with every run of blanks collapsed to one space (EBX's bundles break their sentences over several lines).

	/** {@code userMessages.mxml facet.facetMaxLength}; live in {@code validation.log}: "Value must not exceed 20 character(s) long." */
	private static final Pattern MAX_LENGTH = Pattern.compile("Value must not exceed (\\d+) character\\(s\\) long\\.");
	/** {@code facet.facetMinLength}: "Value must be at least {2} character(s) long." */
	private static final Pattern MIN_LENGTH = Pattern.compile("Value must be at least (\\d+) character\\(s\\) long\\.");
	/** {@code facet.facetLength}: "Value must be {2} character(s) long." */
	private static final Pattern EXACT_LENGTH = Pattern.compile("Value must be (\\d+) character\\(s\\) long\\.");
	/** {@code compilationMessages.mxml facetEnum.*}; live in {@code validation.log}: "The value 'Mrs' is not valid (see enumeration facet)." */
	private static final Pattern ENUMERATION = Pattern.compile("The value '(.*)' is not valid \\(see enumeration facet\\)\\.");
	/** {@code facetTableRef.occurrenceNotDefined}; live in {@code validation.log}: "The value 'ACC-BAG' is not valid (record does not exist)." */
	private static final Pattern FOREIGN_KEY = Pattern.compile("The value '(.*)' is not valid \\(record does not exist\\)\\.");
	/** {@code manager/validationMessages.mxml validation.mandatory}: "Field is mandatory."; live in {@code validation.log}: "Field 'Revision identifier' is mandatory." */
	private static final Pattern MANDATORY = Pattern.compile("Field (?:'([^']*)' )?is mandatory\\.");
	/** {@code facet.facetPattern} / {@code facetPattern.withPattern}; live in {@code validation.log}: "The following pattern must be respected: [A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?" */
	private static final Pattern PATTERN = Pattern.compile("The following pattern must be respected: (.+)");
	/** {@code facet.facetTotalDigits}: "The number must have at most {2} digits." */
	private static final Pattern TOTAL_DIGITS = Pattern.compile("The number must have at most (\\d+) digits\\.");
	/** {@code facet.facetFractionDigits}: "Maximum number of decimal places is {2}." */
	private static final Pattern FRACTION_DIGITS = Pattern.compile("Maximum number of decimal places is (\\d+)\\.");
	/** {@code facet.facetMin/MaxInclusive/Exclusive}: "Value must be greater than or equal to {2}." (also "less than or equal to", "greater than", "less than"). */
	private static final Pattern RANGE = Pattern.compile("Value must be (greater than or equal to|less than or equal to|greater than|less than) (.+?)\\.(?= |$)");
	/**
	 * The long form EBX logs for a duplicate primary key (live in {@code kernel.log}, {@code UowErrorCannotCreateSincePrimaryKeyUsed}): "Cannot create record with primary key
	 * './dataSet='Accounts|FastTrack|Accounts' and ./dataSpaceImportSpec='...'' on /root/import/DataSetImportSpec of content type=VALUES in dataset MIMA in dataspace MIMA since a
	 * record with this key already exists, last update by tx: 1833..., current procedure tx: 1833...".
	 */
	private static final Pattern DUPLICATE_LONG = Pattern.compile("Cannot create record with primary key '(.*?)' on (/\\S+) of content type=\\S+ .*? since a record with this key already exists");
	/** The short form of the same refusal, as the user printer words it ({@code uowPrinter.mxml addErrorCannotCreateSincePrimaryKeyUsed}). */
	private static final Pattern DUPLICATE_SHORT = Pattern.compile("Cannot create the new record since its primary key is already used\\.");
	/** The subject EBX puts before the message of an error with a user message: "[/root/Customer[./id=1]/name] : message" or "In table '/root/Customer' - Selection xpath ./id=1 : message". */
	private static final Pattern SUBJECT = Pattern.compile("^(?:\\[.*?\\]|In table '[^']*' - Selection xpath .*?) : (.+)$");

	private RejectClassifier()
	{
	}

	/**
	 * One field of the row that was being written: its staging name, the landing column it reads, its data type ({@code STRING}, {@code INTEGER}, {@code DECIMAL}, {@code BOOLEAN},
	 * {@code DATE}, {@code TIMESTAMP}) and, when the plan knows them, its maximum length, whether it is mandatory and the entity a foreign key points to.
	 */
	public static final class FieldInfo
	{
		public final String stagingField;
		public final String sourceColumn;
		public final String dataType;
		public final Integer maxLength;
		public final boolean mandatory;
		/** The entity this field refers to when it is a foreign key, "" otherwise. */
		public final String target;

		public FieldInfo(final String pStagingField, final String pSourceColumn, final String pDataType)
		{
			this(pStagingField, pSourceColumn, pDataType, null, false, null);
		}

		public FieldInfo(final String pStagingField, final String pSourceColumn, final String pDataType, final Integer pMaxLength, final boolean pMandatory, final String pTarget)
		{
			this.stagingField = pStagingField == null ? "" : pStagingField;
			this.sourceColumn = pSourceColumn == null ? "" : pSourceColumn;
			this.dataType = pDataType == null ? "" : pDataType;
			this.maxLength = pMaxLength;
			this.mandatory = pMandatory;
			this.target = pTarget == null ? "" : pTarget;
		}
	}

	/** A test of one field of the row against what EBX's message said. */
	private interface FieldTest
	{
		boolean holds(FieldInfo pInfo, String pValue);
	}

	/**
	 * The reason a failed save stands for. {@code pRaw}: EBX's message (HTML-escaped or not, with or without the loader's "EBX refused the save:" prefix, with or without a
	 * {@link RefusalHints} header); {@code pFields}: the fields of the feed; {@code pValues}: the row being written (field to xs-string value).
	 */
	public static RejectReason refusal(final String pRaw, final List<FieldInfo> pFields, final Map<String, Object> pValues)
	{
		final RefusalHints hints = RefusalHints.parse(RejectText.detail(pRaw));
		String text = hints.text;
		if (text.startsWith(RejectClassifier.LEGACY_PREFIX))
		{
			text = text.substring(RejectClassifier.LEGACY_PREFIX.length()).trim();
		}
		final String flat = text.replaceAll("\\s+", " ");
		if (hints.trigger)
		{
			return RejectReason.trigger(hints.name, RejectClassifier.innermost(flat), text);
		}
		final Matcher conversion = RejectClassifier.CONVERSION.matcher(text);
		if (conversion.find())
		{
			return RejectClassifier.conversion(text, conversion, pFields, pValues);
		}
		final RejectReason known = RejectClassifier.known(text, flat, pFields, pValues);
		if (known != null)
		{
			return known;
		}
		if (hints.constraint)
		{
			final String message = RejectClassifier.innermost(flat);
			final Matcher subject = RejectClassifier.SUBJECT.matcher(message);
			return RejectReason.constraint(RejectCategory.EBX_REFUSAL, "", subject.matches() ? subject.group(1) : message, text);
		}
		return RejectReason.ebxRefused(text);
	}

	/**
	 * A stored text with no marker (a reject written before this lot) read as a reason, or null when it is not a conversion failure of EBX. The field and the column are
	 * unknown (the row is long gone), so the sentence names the value only.
	 */
	public static RejectReason legacy(final String pStored)
	{
		final String text = RejectText.detail(pStored);
		if (!text.startsWith(RejectClassifier.LEGACY_PREFIX) || !RejectClassifier.CONVERSION.matcher(text).find())
		{
			return null;
		}
		return RejectClassifier.refusal(text, null, null);
	}

	/** The last "Caused by" of a one-line EBX text, or the text itself: what the failing code actually said. */
	static String innermost(final String pFlat)
	{
		String message = pFlat;
		final int caused = message.lastIndexOf("Caused by:");
		if (caused >= 0)
		{
			message = message.substring(caused + "Caused by:".length());
		}
		else if (message.startsWith("Unexpected error...") && message.length() > "Unexpected error...".length())
		{
			message = message.substring("Unexpected error...".length());
			message = message.replaceFirst("^ ?> ?", "");
		}
		return message.trim();
	}

	// -------------------------------------------------------------------------------------------- conversion

	private static RejectReason conversion(final String pText, final Matcher pMatcher, final List<FieldInfo> pFields, final Map<String, Object> pValues)
	{
		final String converter = pMatcher.group(1);
		final String input = pMatcher.group(2);
		String kind = RejectClassifier.kindOfConverter(converter);
		FieldInfo found = null;
		if (pFields != null && pValues != null)
		{
			FieldInfo equalOnly = null;
			for (final FieldInfo info : pFields)
			{
				final Object value = pValues.get(info.stagingField);
				if (value == null || !input.equals(String.valueOf(value)))
				{
					continue;
				}
				if (RejectClassifier.agrees(kind, info.dataType))
				{
					found = info;
					break;
				}
				if (equalOnly == null)
				{
					equalOnly = info;
				}
			}
			if (found == null)
			{
				found = equalOnly;
			}
		}
		if (found != null && RejectReason.CONVERT_VALUE.equals(kind))
		{
			kind = RejectClassifier.kindOfDataType(found.dataType);
		}
		return RejectReason.convert(kind, found == null ? "" : found.stagingField, found == null ? "" : found.sourceColumn, input, pText);
	}

	private static String kindOfConverter(final String pConverter)
	{
		if (pConverter == null)
		{
			return RejectReason.CONVERT_VALUE;
		}
		final String name = pConverter.toLowerCase(java.util.Locale.ROOT);
		if (name.contains("datetime") || name.contains("timestamp"))
		{
			return RejectReason.CONVERT_DATETIME;
		}
		if (name.contains("date"))
		{
			return RejectReason.CONVERT_DATE;
		}
		if (name.contains("boolean"))
		{
			return RejectReason.CONVERT_BOOLEAN;
		}
		if (name.contains("integer") || name.contains("long") || name.contains("short"))
		{
			return RejectReason.CONVERT_INTEGER;
		}
		if (name.contains("decimal") || name.contains("double") || name.contains("float") || name.contains("number"))
		{
			return RejectReason.CONVERT_DECIMAL;
		}
		return RejectReason.CONVERT_VALUE;
	}

	private static String kindOfDataType(final String pDataType)
	{
		if ("DATE".equals(pDataType))
		{
			return RejectReason.CONVERT_DATE;
		}
		if ("TIMESTAMP".equals(pDataType))
		{
			return RejectReason.CONVERT_DATETIME;
		}
		if ("INTEGER".equals(pDataType))
		{
			return RejectReason.CONVERT_INTEGER;
		}
		if ("DECIMAL".equals(pDataType))
		{
			return RejectReason.CONVERT_DECIMAL;
		}
		if ("BOOLEAN".equals(pDataType))
		{
			return RejectReason.CONVERT_BOOLEAN;
		}
		return RejectReason.CONVERT_VALUE;
	}

	/** Whether a field of {@code pDataType} is what a converter of {@code pKind} works on ({@code convert.value}, an unknown converter, agrees with any non-text field). */
	private static boolean agrees(final String pKind, final String pDataType)
	{
		if (RejectReason.CONVERT_VALUE.equals(pKind))
		{
			return !"STRING".equals(pDataType) && !pDataType.isEmpty();
		}
		return pKind.equals(RejectClassifier.kindOfDataType(pDataType));
	}

	// ------------------------------------------------------------------------------------- the other classes

	private static String fieldName(final FieldInfo pField)
	{
		return pField == null ? "" : pField.stagingField;
	}

	private static String columnName(final FieldInfo pField)
	{
		return pField == null ? "" : pField.sourceColumn;
	}

	private static RejectReason known(final String pText, final String pFlat, final List<FieldInfo> pFields, final Map<String, Object> pValues)
	{
		// the subject EBX prints before a message ("[/root/Customer[./id=1]/bic] : ...") names the field when there is one: it settles what the row's values leave open
		final FieldInfo subject = RejectClassifier.subjectField(pFlat, pFields);
		Matcher m = RejectClassifier.DUPLICATE_LONG.matcher(pFlat);
		if (m.find())
		{
			return RejectReason.duplicateKey(m.group(1), m.group(2), pText);
		}
		if (RejectClassifier.DUPLICATE_SHORT.matcher(pFlat).find())
		{
			return RejectReason.duplicateKey("", "", pText);
		}
		m = RejectClassifier.MAX_LENGTH.matcher(pFlat);
		if (m.find())
		{
			final int max = Integer.parseInt(m.group(1));
			final FieldInfo field = RejectClassifier.locate(subject, pFields, pValues, new FieldTest()
			{
				@Override
				public boolean holds(final FieldInfo pInfo, final String pValue)
				{
					return pValue.length() > max && (pInfo.maxLength == null || pInfo.maxLength.intValue() == max);
				}
			});
			return RejectReason.tooLongRefused(RejectClassifier.fieldName(field), RejectClassifier.columnName(field), RejectClassifier.lengthOver(pValues, field, max), max, pText);
		}
		m = RejectClassifier.MIN_LENGTH.matcher(pFlat);
		if (m.find())
		{
			final int min = Integer.parseInt(m.group(1));
			final FieldInfo field = RejectClassifier.locate(subject, pFields, pValues, new FieldTest()
			{
				@Override
				public boolean holds(final FieldInfo pInfo, final String pValue)
				{
					return pValue.length() < min;
				}
			});
			return RejectReason.minLength(RejectClassifier.fieldName(field), RejectClassifier.columnName(field), RejectClassifier.lengthNot(pValues, field, min, true), min, pText);
		}
		m = RejectClassifier.EXACT_LENGTH.matcher(pFlat);
		if (m.find())
		{
			final int required = Integer.parseInt(m.group(1));
			final FieldInfo field = RejectClassifier.locate(subject, pFields, pValues, new FieldTest()
			{
				@Override
				public boolean holds(final FieldInfo pInfo, final String pValue)
				{
					return pValue.length() != required;
				}
			});
			return RejectReason.exactLength(RejectClassifier.fieldName(field), RejectClassifier.columnName(field), RejectClassifier.lengthNot(pValues, field, required, false), required,
				pText);
		}
		m = RejectClassifier.ENUMERATION.matcher(pFlat);
		if (m.find())
		{
			final String value = m.group(1);
			final FieldInfo field = RejectClassifier.locate(subject, pFields, pValues, RejectClassifier.equalTo(value));
			return RejectReason.enumeration(RejectClassifier.fieldName(field), RejectClassifier.columnName(field), value, pText);
		}
		m = RejectClassifier.FOREIGN_KEY.matcher(pFlat);
		if (m.find())
		{
			final String value = m.group(1);
			FieldInfo field = RejectClassifier.locate(subject, pFields, pValues, new FieldTest()
			{
				@Override
				public boolean holds(final FieldInfo pInfo, final String pValue)
				{
					return !pInfo.target.isEmpty() && pValue.equals(value);
				}
			});
			if (field == null)
			{
				field = RejectClassifier.locate(subject, pFields, pValues, RejectClassifier.equalTo(value));
			}
			return RejectReason.foreignKey(RejectClassifier.fieldName(field), RejectClassifier.columnName(field), value, field == null ? "" : field.target, pText);
		}
		m = RejectClassifier.MANDATORY.matcher(pFlat);
		if (m.find())
		{
			final FieldInfo field = subject != null ? subject : RejectClassifier.emptyMandatory(pFields, pValues, m.group(1));
			return RejectReason.mandatoryRefused(RejectClassifier.fieldName(field), RejectClassifier.columnName(field), pText);
		}
		m = RejectClassifier.PATTERN.matcher(pFlat);
		if (m.find())
		{
			return RejectClassifier.patternReason(m.group(1).trim(), pText, subject, pFields, pValues);
		}
		m = RejectClassifier.TOTAL_DIGITS.matcher(pFlat);
		if (m.find())
		{
			final int max = Integer.parseInt(m.group(1));
			final FieldInfo field = RejectClassifier.locate(subject, pFields, pValues, new FieldTest()
			{
				@Override
				public boolean holds(final FieldInfo pInfo, final String pValue)
				{
					final BigDecimal number = RejectClassifier.number(pValue);
					return number != null && number.precision() > max;
				}
			});
			return RejectReason.totalDigits(RejectClassifier.fieldName(field), RejectClassifier.columnName(field), field == null ? "" : RejectClassifier.valueOf(pValues, field),
				max, pText);
		}
		m = RejectClassifier.FRACTION_DIGITS.matcher(pFlat);
		if (m.find())
		{
			final int max = Integer.parseInt(m.group(1));
			final FieldInfo field = RejectClassifier.locate(subject, pFields, pValues, new FieldTest()
			{
				@Override
				public boolean holds(final FieldInfo pInfo, final String pValue)
				{
					final BigDecimal number = RejectClassifier.number(pValue);
					return number != null && Math.max(0, number.stripTrailingZeros().scale()) > max;
				}
			});
			return RejectReason.fractionDigits(RejectClassifier.fieldName(field), RejectClassifier.columnName(field),
				field == null ? "" : RejectClassifier.valueOf(pValues, field), max, pText);
		}
		m = RejectClassifier.RANGE.matcher(pFlat);
		if (m.find())
		{
			return RejectClassifier.rangeReason(m.group(1), m.group(2).trim(), pText, subject, pFields, pValues);
		}
		return null;
	}

	private static RejectReason patternReason(final String pCaptured, final String pText, final FieldInfo pSubject, final List<FieldInfo> pFields,
		final Map<String, Object> pValues)
	{
		// the bundle's "{0}." form puts its own full stop after the pattern; the "withPattern" form does not (live: validation.log): try the pattern without a last dot first
		final List<String> candidates = new ArrayList<String>(2);
		if (pCaptured.endsWith(".") && pCaptured.length() > 1)
		{
			candidates.add(pCaptured.substring(0, pCaptured.length() - 1));
		}
		candidates.add(pCaptured);
		Pattern compiled = null;
		String shown = pCaptured;
		for (final String candidate : candidates)
		{
			try
			{
				compiled = Pattern.compile(candidate);
				shown = candidate;
				break;
			}
			catch (final PatternSyntaxException ex)
			{
				compiled = null;
			}
		}
		final Pattern pattern = compiled;
		FieldInfo field = null;
		if (pattern != null)
		{
			field = RejectClassifier.locate(pSubject, pFields, pValues, new FieldTest()
			{
				@Override
				public boolean holds(final FieldInfo pInfo, final String pValue)
				{
					return !pValue.isEmpty() && !pattern.matcher(pValue).matches();
				}
			});
		}
		return RejectReason.pattern(RejectClassifier.fieldName(field), RejectClassifier.columnName(field), field == null ? "" : RejectClassifier.valueOf(pValues, field), shown, pText);
	}

	private static RejectReason rangeReason(final String pComparison, final String pBound, final String pText, final FieldInfo pSubject, final List<FieldInfo> pFields,
		final Map<String, Object> pValues)
	{
		final String op = "greater than or equal to".equals(pComparison) ? RejectReason.OP_GE
			: "less than or equal to".equals(pComparison) ? RejectReason.OP_LE : "greater than".equals(pComparison) ? RejectReason.OP_GT : RejectReason.OP_LT;
		final BigDecimal bound = RejectClassifier.number(pBound);
		FieldInfo field = null;
		if (bound != null)
		{
			field = RejectClassifier.locate(pSubject, pFields, pValues, new FieldTest()
			{
				@Override
				public boolean holds(final FieldInfo pInfo, final String pValue)
				{
					final BigDecimal number = RejectClassifier.number(pValue);
					if (number == null)
					{
						return false;
					}
					final int order = number.compareTo(bound);
					// the message states what the value MUST be: the field violates it when it is not
					return RejectReason.OP_GE.equals(op) ? order < 0 : RejectReason.OP_LE.equals(op) ? order > 0 : RejectReason.OP_GT.equals(op) ? order <= 0 : order >= 0;
				}
			});
		}
		return RejectReason.range(RejectClassifier.fieldName(field), RejectClassifier.columnName(field), field == null ? "" : RejectClassifier.valueOf(pValues, field), op, pBound,
			pText);
	}

	// ------------------------------------------------------------------------------------------ the row

	private static FieldTest equalTo(final String pExpected)
	{
		return new FieldTest()
		{
			@Override
			public boolean holds(final FieldInfo pInfo, final String pValue)
			{
				return pValue.equals(pExpected);
			}
		};
	}

	private static String valueOf(final Map<String, Object> pValues, final FieldInfo pField)
	{
		final Object value = pValues == null ? null : pValues.get(pField.stagingField);
		return value == null ? "" : String.valueOf(value);
	}

	/** The length of the field's value when it is over {@code pMax}, -1 (not known) when the row does not hold a value that long. */
	private static int lengthOver(final Map<String, Object> pValues, final FieldInfo pField, final int pMax)
	{
		final int length = pField == null ? -1 : RejectClassifier.valueOf(pValues, pField).length();
		return length > pMax ? length : -1;
	}

	/** The length of the field's value when it breaks {@code pLimit} (under it when {@code pMinimum}, different from it otherwise), -1 when the row does not show that. */
	private static int lengthNot(final Map<String, Object> pValues, final FieldInfo pField, final int pLimit, final boolean pMinimum)
	{
		final int length = pField == null ? -1 : RejectClassifier.valueOf(pValues, pField).length();
		if (length < 0)
		{
			return -1;
		}
		return (pMinimum ? length < pLimit : length != pLimit) ? length : -1;
	}

	/** The field the subject of the message names, else the one field of the row {@code pTest} picks. */
	private static FieldInfo locate(final FieldInfo pSubject, final List<FieldInfo> pFields, final Map<String, Object> pValues, final FieldTest pTest)
	{
		return pSubject != null ? pSubject : RejectClassifier.only(pFields, pValues, pTest);
	}

	/**
	 * The field named by the subject EBX puts in front of a message ("[/root/Customer[./id=1]/bic] : The following pattern..."): the last step of the path, predicates left out,
	 * when a field of the feed has that name. Null when there is no subject, it is a table's ("In table ..."), or no field is called that.
	 */
	static FieldInfo subjectField(final String pFlat, final List<FieldInfo> pFields)
	{
		if (pFields == null)
		{
			return null;
		}
		String text = pFlat;
		final int caused = text.lastIndexOf("Caused by:");
		if (caused >= 0)
		{
			text = text.substring(caused + "Caused by:".length()).trim();
		}
		if (!text.startsWith("[/"))
		{
			return null;
		}
		int depth = 0;
		int close = -1;
		for (int i = 0; i < text.length(); i++)
		{
			final char c = text.charAt(i);
			if (c == '[')
			{
				depth++;
			}
			else if (c == ']')
			{
				depth--;
				if (depth == 0)
				{
					close = i;
					break;
				}
			}
		}
		if (close < 0 || !text.substring(close + 1).startsWith(" : "))
		{
			return null;
		}
		final String path = text.substring(1, close);
		final StringBuilder steps = new StringBuilder();
		int open = 0;
		for (int i = 0; i < path.length(); i++)
		{
			final char c = path.charAt(i);
			if (c == '[')
			{
				open++;
			}
			else if (c == ']')
			{
				open--;
			}
			else if (open == 0)
			{
				steps.append(c);
			}
		}
		final String last = steps.substring(steps.lastIndexOf("/") + 1);
		for (final FieldInfo info : pFields)
		{
			if (info.stagingField.equals(last))
			{
				return info;
			}
		}
		return null;
	}

	/** The one field of the row for which {@code pTest} holds, null when none does or when two do (a reason never guesses which). */
	private static FieldInfo only(final List<FieldInfo> pFields, final Map<String, Object> pValues, final FieldTest pTest)
	{
		if (pFields == null || pValues == null)
		{
			return null;
		}
		FieldInfo found = null;
		for (final FieldInfo info : pFields)
		{
			final Object value = pValues.get(info.stagingField);
			if (value == null || !pTest.holds(info, String.valueOf(value)))
			{
				continue;
			}
			if (found != null)
			{
				return null;
			}
			found = info;
		}
		return found;
	}

	/** The empty mandatory field: the one whose name is the label EBX gave, else the only empty field the plan says is mandatory. */
	private static FieldInfo emptyMandatory(final List<FieldInfo> pFields, final Map<String, Object> pValues, final String pLabel)
	{
		if (pFields == null || pValues == null)
		{
			return null;
		}
		final List<FieldInfo> empty = new ArrayList<FieldInfo>();
		for (final FieldInfo info : pFields)
		{
			final Object value = pValues.get(info.stagingField);
			if (value == null || String.valueOf(value).isEmpty())
			{
				empty.add(info);
			}
		}
		if (pLabel != null && !pLabel.isEmpty())
		{
			final String wanted = RejectClassifier.squash(pLabel);
			for (final FieldInfo info : empty)
			{
				if (RejectClassifier.squash(info.stagingField).equals(wanted))
				{
					return info;
				}
			}
		}
		FieldInfo found = null;
		for (final FieldInfo info : empty)
		{
			if (!info.mandatory)
			{
				continue;
			}
			if (found != null)
			{
				return null;
			}
			found = info;
		}
		return found;
	}

	private static String squash(final String pText)
	{
		return pText.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	private static BigDecimal number(final String pValue)
	{
		try
		{
			return new BigDecimal(pValue.trim());
		}
		catch (final NumberFormatException ex)
		{
			return null;
		}
	}
}
