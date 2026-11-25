package com.on.ebx.factory.rejects;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.on.ebx.factory.wizard.EngineMessageCodec;
import com.on.ebx.factory.wizard.WizardMessages;

/**
 * Staging load and rejects lot (2026-10-17): ONE reason a row was set aside, as a CODE plus its arguments instead of a sentence. It is stored the way every other engine text of
 * the module is ({@link EngineMessageCodec}): the English sentence followed by one marker, {@code "... [mdmf:rejects.reason.convert.date|s:birthDate|s:date_naissance|s:10%2F06%2F1963]"},
 * so REST, MCP, logs and the CSV read plain English (the marker is stripped) while the review screen words it in the viewer's language. A text with no marker (older rows) is
 * shown as it is.
 * <p>
 * <b>Arguments.</b> A <i>fielded</i> reason (a value of one staging field that is wrong: {@code convert.*}, {@code tooLong}, {@code mandatory}, {@code unknownParent},
 * {@code quality}) always starts with {@code staging field, source column} (the column may be empty for a computed field); the rest depends on the code. The bundle patterns of a
 * fielded reason receive {@code {0}} = a phrase naming the field and its column, {@code {1}} = the field, {@code {2}} = a phrase naming the column, {@code {3}...} = the remaining
 * arguments. A {@code parent} reason carries the parent's entity, then the parent's own code and arguments (so the child says why its parent was rejected). The raw text EBX gave
 * ({@link #detail}) is NOT in the marker: it goes to the reject's {@code detail} field, folded on the screen.
 * <p>
 * Message keys: {@code rejects.reason.<code>} (the problem, one sentence), {@code rejects.group.<code>} (the same without the row's own value, for the grouped summary) and
 * {@code rejects.fix.<code>} (what to do, optional). Pure, EBX-free.
 */
public final class RejectReason
{
	/** Prefix of every reason key of the bundle. */
	public static final String P = "rejects.reason.";
	static final String GROUP_P = "rejects.group.";
	static final String FIX_P = "rejects.fix.";

	public static final String CONVERT_DATE = "convert.date";
	public static final String CONVERT_DATETIME = "convert.dateTime";
	public static final String CONVERT_INTEGER = "convert.integer";
	public static final String CONVERT_DECIMAL = "convert.decimal";
	public static final String CONVERT_BOOLEAN = "convert.boolean";
	public static final String CONVERT_VALUE = "convert.value";
	public static final String TOO_LONG = "tooLong";
	public static final String MANDATORY = "mandatory";
	public static final String UNKNOWN_PARENT = "unknownParent";
	public static final String QUALITY = "quality";
	public static final String PARENT = "parent";
	public static final String EBX_REFUSED = "ebx.refused";
	public static final String COMPUTE = "compute";
	public static final String READ = "read";
	public static final String NO_KEY = "noKey";
	public static final String SOURCE_ID_EMPTY = "sourceIdEmpty";
	// EBX refusal classes lot H4 (2026-10-17): the other refusals of a save, found by their EBX message (RejectClassifier), and the master stage's own checks
	public static final String EBX_ENUMERATION = "ebx.enumeration";
	public static final String EBX_FOREIGN_KEY = "ebx.foreignKey";
	public static final String EBX_PATTERN = "ebx.pattern";
	public static final String EBX_RANGE = "ebx.range";
	public static final String EBX_TOTAL_DIGITS = "ebx.totalDigits";
	public static final String EBX_FRACTION_DIGITS = "ebx.fractionDigits";
	public static final String EBX_MIN_LENGTH = "ebx.minLength";
	public static final String EBX_LENGTH = "ebx.length";
	public static final String EBX_DUPLICATE_KEY = "ebx.duplicateKey";
	public static final String EBX_TRIGGER = "ebx.trigger";
	public static final String CONSTRAINT = "constraint";
	public static final String UNKNOWN_MASTER_PARENT = "unknownMasterParent";

	/** The comparison of a range refusal: {@code ge} (at least), {@code le} (at most), {@code gt} (more than), {@code lt} (less than). */
	public static final String OP_GE = "ge";
	public static final String OP_LE = "le";
	public static final String OP_GT = "gt";
	public static final String OP_LT = "lt";

	/** In the master stage a reason names the STAGING field a value comes from where the staging stage names the source column: that column is written with this prefix. */
	public static final String STAGING_COLUMN = "staging:";

	private static final Set<String> FIELDED = new HashSet<String>(Arrays.asList(RejectReason.CONVERT_DATE, RejectReason.CONVERT_DATETIME, RejectReason.CONVERT_INTEGER,
		RejectReason.CONVERT_DECIMAL, RejectReason.CONVERT_BOOLEAN, RejectReason.CONVERT_VALUE, RejectReason.TOO_LONG, RejectReason.MANDATORY, RejectReason.UNKNOWN_PARENT,
		RejectReason.QUALITY, RejectReason.EBX_ENUMERATION, RejectReason.EBX_FOREIGN_KEY, RejectReason.EBX_PATTERN, RejectReason.EBX_RANGE, RejectReason.EBX_TOTAL_DIGITS,
		RejectReason.EBX_FRACTION_DIGITS, RejectReason.EBX_MIN_LENGTH, RejectReason.EBX_LENGTH, RejectReason.UNKNOWN_MASTER_PARENT));

	/** The fielded codes whose third argument is a value of the row, worded as a phrase ("'x'", or "its value" when the row's value is not known). */
	private static final Set<String> VALUE_PHRASED = new HashSet<String>(Arrays.asList(RejectReason.EBX_ENUMERATION, RejectReason.EBX_FOREIGN_KEY, RejectReason.EBX_PATTERN,
		RejectReason.EBX_RANGE, RejectReason.EBX_TOTAL_DIGITS, RejectReason.EBX_FRACTION_DIGITS));

	/** The longest value of a source row quoted in a reason. */
	static final int VALUE_MAX = 60;

	public final String category;
	public final String code;
	public final String[] args;
	/** The raw technical text (EBX's message, an exception's); empty when the sentence says everything. */
	public final String detail;

	public RejectReason(final String pCategory, final String pCode, final String[] pArgs, final String pDetail)
	{
		this.category = RejectCategory.of(pCategory);
		this.code = pCode == null ? "" : pCode;
		this.args = pArgs == null ? new String[0] : pArgs;
		this.detail = pDetail == null ? "" : pDetail;
	}

	// ----------------------------------------------------------------------------------------------- factories

	private static String value(final String pValue)
	{
		return RejectText.cut(RejectText.oneLine(pValue), RejectReason.VALUE_MAX);
	}

	private static String nz(final String pValue)
	{
		return pValue == null ? "" : pValue;
	}

	/** A value that does not convert to the type of its field: {@code pKind} is one of the {@code convert.*} codes. */
	public static RejectReason convert(final String pKind, final String pField, final String pColumn, final String pValue, final String pDetail)
	{
		return new RejectReason(RejectCategory.EBX_REFUSAL, pKind, new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn), RejectReason.value(pValue) }, pDetail);
	}

	/** A value longer than the staging field allows (the loader's own check, before EBX is asked). */
	public static RejectReason tooLong(final String pField, final String pColumn, final int pLength, final int pMax)
	{
		return new RejectReason(RejectCategory.DATA_CHECK, RejectReason.TOO_LONG,
			new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn), String.valueOf(pLength), String.valueOf(pMax) }, "");
	}

	/** A mandatory field that came out empty. */
	public static RejectReason mandatory(final String pField, final String pColumn)
	{
		return new RejectReason(RejectCategory.DATA_CHECK, RejectReason.MANDATORY, new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn) }, "");
	}

	/** A reference to a parent row that does not exist ({@code pKey}: the parent's staging key). */
	public static RejectReason unknownParent(final String pField, final String pColumn, final String pEntity, final String pKey)
	{
		return new RejectReason(RejectCategory.DATA_CHECK, RejectReason.UNKNOWN_PARENT,
			new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn), RejectReason.nz(pEntity), RejectReason.value(pKey) }, "");
	}

	/** A quality rule of severity REJECT failed ({@code pKind}: the rule's kind, {@code pArgument}: its argument, may be empty). */
	public static RejectReason quality(final String pField, final String pColumn, final String pKind, final String pArgument)
	{
		final String argument = pArgument == null ? "" : pArgument.trim();
		return new RejectReason(RejectCategory.QUALITY_RULE, RejectReason.QUALITY,
			new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn), RejectReason.nz(pKind) + (argument.isEmpty() ? "" : " " + RejectReason.value(argument)) }, "");
	}

	/** Why a row belongs to a rejected parent: {@code pParentEntity} is the parent's entity, {@code pParent} its own (first) reason. */
	public static RejectReason parentRejected(final String pParentEntity, final RejectReason pParent)
	{
		final String[] args = new String[pParent.args.length + 2];
		args[0] = RejectReason.nz(pParentEntity);
		args[1] = pParent.code;
		System.arraycopy(pParent.args, 0, args, 2, pParent.args.length);
		return new RejectReason(RejectCategory.PARENT_REJECTED, RejectReason.PARENT, args, pParent.detail);
	}

	/** EBX refused to save a row for a reason this class does not recognise; {@code pRaw} is EBX's own message. */
	public static RejectReason ebxRefused(final String pRaw)
	{
		final String raw = RejectText.detail(pRaw);
		return new RejectReason(RejectCategory.EBX_REFUSAL, RejectReason.EBX_REFUSED, new String[] { RejectText.cut(RejectText.oneLine(pRaw), 200) }, raw);
	}

	/** The mapping could not compute the row: {@code pText} is what the engine said. */
	public static RejectReason compute(final String pText)
	{
		return new RejectReason(RejectCategory.COMPUTATION, RejectReason.COMPUTE, new String[] { RejectText.cut(RejectText.oneLine(pText), 300) }, "");
	}

	/** A source row that could not be read at all. */
	public static RejectReason read(final String pText)
	{
		return new RejectReason(RejectCategory.COMPUTATION, RejectReason.READ, new String[] { RejectText.cut(RejectText.oneLine(pText), 300) }, "");
	}

	/** A landing row with no source system or source id: no staging key can be built. */
	public static RejectReason noKey()
	{
		return new RejectReason(RejectCategory.DATA_CHECK, RejectReason.NO_KEY, new String[0], "");
	}

	/** A child feed whose source id came out empty. */
	public static RejectReason sourceIdEmpty(final String pEntity, final String pFeed)
	{
		return new RejectReason(RejectCategory.COMPUTATION, RejectReason.SOURCE_ID_EMPTY, new String[] { RejectReason.nz(pEntity), RejectReason.nz(pFeed) }, "");
	}

	// ------------------------------------------------------------------------- EBX refusal classes (lot H4, 2026-10-17)

	/** Staging field and source column of a MASTER reason: the master stage's "column" is the staging field the value is read from. */
	public static String stagingColumn(final String pStagingField)
	{
		return pStagingField == null || pStagingField.isEmpty() ? "" : RejectReason.STAGING_COLUMN + pStagingField;
	}

	/** A text longer than the field allows, refused by EBX itself ("Value must not exceed N character(s) long."): the same sentence as the loader's own check, the refusal's category. */
	public static RejectReason tooLongRefused(final String pField, final String pColumn, final int pLength, final int pMax, final String pDetail)
	{
		return new RejectReason(RejectCategory.EBX_REFUSAL, RejectReason.TOO_LONG,
			new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn), pLength < 0 ? "?" : String.valueOf(pLength), String.valueOf(pMax) }, pDetail);
	}

	/** A mandatory field that EBX found empty ("Field is mandatory."). */
	public static RejectReason mandatoryRefused(final String pField, final String pColumn, final String pDetail)
	{
		return new RejectReason(RejectCategory.EBX_REFUSAL, RejectReason.MANDATORY, new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn) }, pDetail);
	}

	/** A value outside the list of values of its field ("The value 'x' is not valid (see enumeration facet).") */
	public static RejectReason enumeration(final String pField, final String pColumn, final String pValue, final String pDetail)
	{
		return new RejectReason(RejectCategory.EBX_REFUSAL, RejectReason.EBX_ENUMERATION, new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn), RejectReason.value(pValue) },
			pDetail);
	}

	/** A foreign key to a record that does not exist ("The value 'x' is not valid (record does not exist)."); {@code pTarget}: the table it points to, "" when not known. */
	public static RejectReason foreignKey(final String pField, final String pColumn, final String pValue, final String pTarget, final String pDetail)
	{
		return new RejectReason(RejectCategory.EBX_REFUSAL, RejectReason.EBX_FOREIGN_KEY,
			new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn), RejectReason.value(pValue), RejectReason.nz(pTarget) }, pDetail);
	}

	/** A value that does not match the pattern of its field ("The following pattern must be respected: ..."). */
	public static RejectReason pattern(final String pField, final String pColumn, final String pValue, final String pPattern, final String pDetail)
	{
		return new RejectReason(RejectCategory.EBX_REFUSAL, RejectReason.EBX_PATTERN,
			new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn), RejectReason.value(pValue), RejectText.cut(RejectText.oneLine(pPattern), 120) }, pDetail);
	}

	/** A number outside the bounds of its field (min/max inclusive/exclusive): {@code pOp} is one of the {@code OP_*} constants, {@code pBound} the limit EBX named. */
	public static RejectReason range(final String pField, final String pColumn, final String pValue, final String pOp, final String pBound, final String pDetail)
	{
		return new RejectReason(RejectCategory.EBX_REFUSAL, RejectReason.EBX_RANGE,
			new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn), RejectReason.value(pValue), RejectReason.nz(pOp), RejectReason.nz(pBound) }, pDetail);
	}

	/** A number with more digits than its field allows (totalDigits). */
	public static RejectReason totalDigits(final String pField, final String pColumn, final String pValue, final int pMax, final String pDetail)
	{
		return new RejectReason(RejectCategory.EBX_REFUSAL, RejectReason.EBX_TOTAL_DIGITS,
			new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn), RejectReason.value(pValue), String.valueOf(pMax) }, pDetail);
	}

	/** A number with more decimals than its field allows (fractionDigits). */
	public static RejectReason fractionDigits(final String pField, final String pColumn, final String pValue, final int pMax, final String pDetail)
	{
		return new RejectReason(RejectCategory.EBX_REFUSAL, RejectReason.EBX_FRACTION_DIGITS,
			new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn), RejectReason.value(pValue), String.valueOf(pMax) }, pDetail);
	}

	/** A text shorter than the minimum length of its field; {@code pLength} is -1 when the row's value is not known. */
	public static RejectReason minLength(final String pField, final String pColumn, final int pLength, final int pMin, final String pDetail)
	{
		return new RejectReason(RejectCategory.EBX_REFUSAL, RejectReason.EBX_MIN_LENGTH,
			new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn), pLength < 0 ? "?" : String.valueOf(pLength), String.valueOf(pMin) }, pDetail);
	}

	/** A text whose length is not the one its field requires. */
	public static RejectReason exactLength(final String pField, final String pColumn, final int pLength, final int pRequired, final String pDetail)
	{
		return new RejectReason(RejectCategory.EBX_REFUSAL, RejectReason.EBX_LENGTH,
			new String[] { RejectReason.nz(pField), RejectReason.nz(pColumn), pLength < 0 ? "?" : String.valueOf(pLength), String.valueOf(pRequired) }, pDetail);
	}

	/** A record with this primary key already exists; {@code pKey} (may be empty) is the key EBX named, {@code pTable} the table path (may be empty). */
	public static RejectReason duplicateKey(final String pKey, final String pTable, final String pDetail)
	{
		return new RejectReason(RejectCategory.EBX_REFUSAL, RejectReason.EBX_DUPLICATE_KEY,
			new String[] { RejectText.cut(RejectText.oneLine(RejectReason.nz(pKey).replace("./", "")), 160), RejectReason.nz(pTable) }, pDetail);
	}

	/**
	 * A constraint refused the row: {@code pName} is the constraint's name when known ("" otherwise), {@code pMessage} its own user message. {@code pCategory} is
	 * {@link RejectCategory#EBX_REFUSAL} for a blocking constraint EBX refused the save on, {@link RejectCategory#QUALITY_RULE} for a script constraint a transfer rule turned into a reject.
	 */
	public static RejectReason constraint(final String pCategory, final String pName, final String pMessage, final String pDetail)
	{
		return new RejectReason(pCategory, RejectReason.CONSTRAINT,
			new String[] { RejectText.cut(RejectText.oneLine(RejectReason.nz(pName)), 120), RejectText.cut(RejectText.oneLine(RejectReason.nz(pMessage)), 300) }, pDetail);
	}

	/** A table trigger failed while the row was saved: {@code pName} the trigger (script name or class, may be empty), {@code pMessage} what the failure said. */
	public static RejectReason trigger(final String pName, final String pMessage, final String pDetail)
	{
		return new RejectReason(RejectCategory.EBX_REFUSAL, RejectReason.EBX_TRIGGER,
			new String[] { RejectText.cut(RejectText.oneLine(RejectReason.nz(pName)), 120), RejectText.cut(RejectText.oneLine(RejectReason.nz(pMessage)), 300) }, pDetail);
	}

	/** MASTER stage: a reference to a parent entity whose row has no master record yet ({@code pEntity}: the parent entity, {@code pKey} the parent's staging key). */
	public static RejectReason unknownMasterParent(final String pField, final String pStagingField, final String pEntity, final String pKey)
	{
		return new RejectReason(RejectCategory.DATA_CHECK, RejectReason.UNKNOWN_MASTER_PARENT,
			new String[] { RejectReason.nz(pField), RejectReason.stagingColumn(pStagingField), RejectReason.nz(pEntity), RejectReason.value(pKey) }, "");
	}

	// ---------------------------------------------------------------------------------------------- reading

	/** True when the arguments start with {@code staging field, source column}. */
	public static boolean fielded(final String pCode)
	{
		return RejectReason.FIELDED.contains(pCode);
	}

	public String key()
	{
		return RejectReason.P + this.code;
	}

	/** The staging field a fielded reason is about, "" otherwise. */
	public String field()
	{
		return RejectReason.fielded(this.code) && this.args.length > 0 ? this.args[0] : "";
	}

	/** The source column of a fielded reason, "" otherwise or when the field is computed. */
	public String column()
	{
		return RejectReason.fielded(this.code) && this.args.length > 1 ? this.args[1] : "";
	}

	/** The English sentence (what REST, MCP, logs and the CSV read). */
	public String english()
	{
		return RejectReason.render(Locale.ENGLISH, RejectReason.P, this.code, this.args);
	}

	/** The sentence in {@code pLocale}. */
	public String sentence(final Locale pLocale)
	{
		return RejectReason.render(pLocale, RejectReason.P, this.code, this.args);
	}

	/** The same problem without the row's own value, for a summary that groups many rows. */
	public String group(final Locale pLocale)
	{
		return RejectReason.render(pLocale, RejectReason.GROUP_P, this.code, this.args);
	}

	/** What to do about it, "" when the sentence is enough. */
	public String fix(final Locale pLocale)
	{
		return RejectReason.fixOf(pLocale, this.code, this.args);
	}

	/**
	 * What identical reasons have in common (the code, the field and the column - never the value): the text a screen groups rows by. A parent-rejected reason is
	 * "parent|" followed by its parent's own signature.
	 */
	public String signature()
	{
		return RejectReason.signatureOf(this.code, this.args);
	}

	/** The English sentence followed by the marker, never longer than {@code pMax} (the marker is never cut, the sentence gives way to it). */
	public String stored(final int pMax)
	{
		return EngineMessageCodec.encode(this.english(), this.key(), this.args, pMax);
	}

	// ------------------------------------------------------------------------------------------- rendering

	private static String signatureOf(final String pCode, final String[] pArgs)
	{
		if (RejectReason.PARENT.equals(pCode))
		{
			if (pArgs.length < 2)
			{
				return RejectReason.PARENT;
			}
			return RejectReason.PARENT + "|" + RejectReason.signatureOf(pArgs[1], Arrays.copyOfRange(pArgs, 2, pArgs.length));
		}
		final StringBuilder sb = new StringBuilder(pCode);
		if (RejectReason.fielded(pCode))
		{
			sb.append('|').append(pArgs.length > 0 ? pArgs[0] : "").append('|').append(pArgs.length > 1 ? pArgs[1] : "");
		}
		else if (RejectReason.SOURCE_ID_EMPTY.equals(pCode) || RejectReason.EBX_TRIGGER.equals(pCode))
		{
			sb.append('|').append(pArgs.length > 0 ? pArgs[0] : "");
		}
		else if (RejectReason.CONSTRAINT.equals(pCode))
		{
			// a constraint is told apart by its name when it has one, else by its message (two messages are two reasons)
			sb.append('|').append(pArgs.length > 0 && !pArgs[0].isEmpty() ? pArgs[0] : pArgs.length > 1 ? RejectText.cut(pArgs[1], 80) : "");
		}
		else if (RejectReason.EBX_REFUSED.equals(pCode) || RejectReason.COMPUTE.equals(pCode) || RejectReason.READ.equals(pCode))
		{
			// free text: two different messages are two different reasons
			sb.append('|').append(pArgs.length > 0 ? RejectText.cut(pArgs[0], 80) : "");
		}
		return sb.length() > 250 ? sb.substring(0, 250) : sb.toString();
	}

	private static String where(final Locale pLocale, final String pField, final String pColumn)
	{
		if (!pField.isEmpty())
		{
			if (pColumn.startsWith(RejectReason.STAGING_COLUMN))
			{
				return WizardMessages.get(pLocale, "rejects.where.both.staging", pField, pColumn.substring(RejectReason.STAGING_COLUMN.length()));
			}
			return pColumn.isEmpty() ? WizardMessages.get(pLocale, "rejects.where.field", pField) : WizardMessages.get(pLocale, "rejects.where.both", pField, pColumn);
		}
		return WizardMessages.get(pLocale, "rejects.where.none");
	}

	private static String columnPhrase(final Locale pLocale, final String pColumn)
	{
		if (pColumn.startsWith(RejectReason.STAGING_COLUMN))
		{
			return WizardMessages.get(pLocale, "rejects.where.column.staging", pColumn.substring(RejectReason.STAGING_COLUMN.length()));
		}
		return pColumn.isEmpty() ? WizardMessages.get(pLocale, "rejects.where.column.none") : WizardMessages.get(pLocale, "rejects.where.column", pColumn);
	}

	private static String arg(final String[] pArgs, final int pIndex)
	{
		return pArgs.length > pIndex && pArgs[pIndex] != null ? pArgs[pIndex] : "";
	}

	/** The message arguments of a code: a fielded reason gets its phrases first, a parent reason its parent's sentence. */
	private static Object[] messageArgs(final Locale pLocale, final String pPrefix, final String pCode, final String[] pArgs)
	{
		if (RejectReason.fielded(pCode))
		{
			final String field = pArgs.length > 0 ? pArgs[0] : "";
			final String column = pArgs.length > 1 ? pArgs[1] : "";
			final Object[] out = new Object[Math.max(3, pArgs.length + 1)];
			out[0] = RejectReason.where(pLocale, field, column);
			out[1] = field;
			out[2] = RejectReason.columnPhrase(pLocale, column);
			for (int i = 2; i < pArgs.length; i++)
			{
				out[i + 1] = pArgs[i];
			}
			if (RejectReason.VALUE_PHRASED.contains(pCode))
			{
				final String value = RejectReason.arg(pArgs, 2);
				out[3] = value.isEmpty() ? WizardMessages.get(pLocale, "rejects.where.value.none") : WizardMessages.get(pLocale, "rejects.where.value", value);
			}
			if (RejectReason.EBX_FOREIGN_KEY.equals(pCode))
			{
				final String target = RejectReason.arg(pArgs, 3);
				out[4] = target.isEmpty() ? WizardMessages.get(pLocale, "rejects.where.target.none") : WizardMessages.get(pLocale, "rejects.where.target", target);
			}
			if (RejectReason.EBX_RANGE.equals(pCode))
			{
				final String op = RejectReason.arg(pArgs, 3);
				out[4] = WizardMessages.get(pLocale, "rejects.op." + op);
				if (("rejects.op." + op).equals(out[4]))
				{
					out[4] = op;
				}
			}
			for (int i = 0; i < out.length; i++)
			{
				if (out[i] == null)
				{
					out[i] = "";
				}
			}
			return out;
		}
		if (RejectReason.EBX_DUPLICATE_KEY.equals(pCode))
		{
			final String key = RejectReason.arg(pArgs, 0);
			return new Object[] { key.isEmpty() ? "" : " (" + key + ")", RejectReason.arg(pArgs, 1) };
		}
		if (RejectReason.CONSTRAINT.equals(pCode) || RejectReason.EBX_TRIGGER.equals(pCode))
		{
			final String name = RejectReason.arg(pArgs, 0);
			final String base = "rejects.where." + (RejectReason.CONSTRAINT.equals(pCode) ? "constraint" : "trigger");
			return new Object[] { name.isEmpty() ? WizardMessages.get(pLocale, base + ".none") : WizardMessages.get(pLocale, base, name), RejectReason.arg(pArgs, 1) };
		}
		if (RejectReason.PARENT.equals(pCode))
		{
			final String parentCode = pArgs.length > 1 ? pArgs[1] : "";
			final String[] parentArgs = pArgs.length > 2 ? Arrays.copyOfRange(pArgs, 2, pArgs.length) : new String[0];
			return new Object[] { pArgs.length > 0 ? pArgs[0] : "", RejectReason.render(pLocale, pPrefix, parentCode, parentArgs) };
		}
		return Arrays.copyOf(pArgs, pArgs.length, Object[].class);
	}

	static String render(final Locale pLocale, final String pPrefix, final String pCode, final String[] pArgs)
	{
		final String key = pPrefix + pCode;
		final Object[] args = RejectReason.messageArgs(pLocale, pPrefix, pCode, pArgs);
		final String sentence = WizardMessages.get(pLocale, key, args);
		if (key.equals(sentence))
		{
			// a code with no group wording says what the reason says; a code this build does not know is shown as its raw key
			return RejectReason.GROUP_P.equals(pPrefix) ? RejectReason.render(pLocale, RejectReason.P, pCode, pArgs) : sentence;
		}
		return sentence;
	}

	private static String fixOf(final Locale pLocale, final String pCode, final String[] pArgs)
	{
		if (RejectReason.PARENT.equals(pCode))
		{
			return pArgs.length > 1 ? RejectReason.fixOf(pLocale, pArgs[1], Arrays.copyOfRange(pArgs, 2, pArgs.length)) : "";
		}
		final String key = RejectReason.FIX_P + pCode;
		final String text = WizardMessages.get(pLocale, key, RejectReason.messageArgs(pLocale, RejectReason.FIX_P, pCode, pArgs));
		return key.equals(text) ? "" : text;
	}
}
