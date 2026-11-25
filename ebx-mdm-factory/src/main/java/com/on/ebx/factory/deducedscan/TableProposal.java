package com.on.ebx.factory.deducedscan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deduced-scan lot (2026-09-24): the "Name and key" stage's proposal for ONE table - pure value object plus
 * validation against the columns {@link ColumnMeasurer} actually measured (contract sec "Why": "the model
 * proposes table name(s), key column(s), confidence, one-sentence rationale"). Domain knowledge (the name,
 * which columns form the key) is entirely the language model's; this class only checks the answer is
 * internally consistent with what Java measured - never re-derives or second-guesses the choice itself
 * (product rule, contract: "no test on a column/table NAME").
 */
public final class TableProposal
{
	/** Same identifier shape the rest of this module already uses for a table/column name ({@code StagingLlmPass.NAME_PATTERN}), reused here rather than declaring a slightly different one. */
	public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
	public static final int RATIONALE_MAX_LENGTH = 2000;

	public final String tableName;
	public final List<String> keyColumns;
	public final int confidence;
	public final String rationale;

	public TableProposal(final String pTableName, final List<String> pKeyColumns, final int pConfidence, final String pRationale)
	{
		this.tableName = pTableName == null ? "" : pTableName.trim();
		this.keyColumns = pKeyColumns == null ? new ArrayList<String>() : pKeyColumns;
		this.confidence = pConfidence;
		this.rationale = pRationale == null ? "" : pRationale;
	}

	/** 0-100, clamped - never lets an out-of-range value the model answered propagate further (same clamping discipline {@code StagingLlmPass} already applies to its own confidences). */
	public static int clampConfidence(final int pValue)
	{
		return Math.max(0, Math.min(100, pValue));
	}

	/**
	 * Null when {@code pProposal} is usable as-is; otherwise the reason it is not, in plain English (surfaced
	 * to the model as a tool-call refusal it can retry, and to a person only if every retry still fails).
	 * Checks made: a table name that matches {@link #NAME_PATTERN}; at least one key column; every key column
	 * name actually among {@code pMeasuredColumnNames} (the model cannot invent a column that was not
	 * measured); a non-blank rationale within {@link #RATIONALE_MAX_LENGTH}.
	 */
	public static String firstViolation(final TableProposal pProposal, final List<String> pMeasuredColumnNames)
	{
		if (pProposal == null || pProposal.tableName.isEmpty())
		{
			return "No table name proposed.";
		}
		if (!TableProposal.NAME_PATTERN.matcher(pProposal.tableName).matches())
		{
			return "'" + pProposal.tableName + "' is not a valid table name (letters/digits/underscore, starting with a letter).";
		}
		if (pProposal.keyColumns.isEmpty())
		{
			return "No key column proposed for '" + pProposal.tableName + "'.";
		}
		for (final String keyColumn : pProposal.keyColumns)
		{
			if (keyColumn == null || !pMeasuredColumnNames.contains(keyColumn))
			{
				return "'" + keyColumn + "' is not one of the measured columns of '" + pProposal.tableName + "'.";
			}
		}
		if (pProposal.rationale.trim().isEmpty())
		{
			return "No rationale given for '" + pProposal.tableName + "'.";
		}
		if (pProposal.rationale.length() > TableProposal.RATIONALE_MAX_LENGTH)
		{
			return "Rationale for '" + pProposal.tableName + "' is longer than " + TableProposal.RATIONALE_MAX_LENGTH + " characters.";
		}
		return null;
	}
}
