package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the wizard's key-study panel shows for one landing table (landing key-study lot, 2026-09-23):
 * the live sample, per-column {@link KeyEvidence}, the proposal (the language model's when it ran, otherwise
 * {@link SourceKeyRule} profiled by the sample) and how the language-model pass went. Built by {@code
 * ebx.KeyStudy}, rendered by {@code wizard.WizardPage}, never persisted: the proposal reaches the {@code
 * LandingColumn} rows only when the person clicks "Apply this key". Pure: no EBX class.
 */
public final class KeyStudyReport
{
	public static final String ORIGIN_LLM = "LLM";
	public static final String ORIGIN_RULE = "RULE";

	public String tableId = "";
	public String tableName = "";
	public String sourceObject = "";
	/** Whether the table is still {@code PROPOSED} (its key may still change). */
	public boolean editable;

	public final List<String> sampleColumns = new ArrayList<String>();
	public final List<List<String>> sampleRows = new ArrayList<List<String>>();
	/** Null when the sample was read; the connector's own message otherwise (the study still shows the names). */
	public String sampleError;

	/** One row per column of the landing table, in position order. */
	public final List<ColumnRow> columns = new ArrayList<ColumnRow>();

	/** Null when neither the model nor the rule could propose anything. */
	public Proposal proposal;
	/** {@link KeyEvidence#uniqueTogether} of {@link #proposal} on the sample; null when there is no proposal or it could not be checked. */
	public Boolean proposalUniqueInSample;

	/** The gate message key when the language model was not asked ({@code wizard.gate.adminOnly}, {@code wizard.gate.noLlm}); null when it ran or failed. */
	public String llmSkippedReasonKey;
	/** The pass's own failure text; null otherwise. */
	public String llmError;
	public boolean llmRan;
	/** The run ended without ever calling its tool, even after the one follow-up ({@code ProseAnswerRecovery}); {@link #llmFinalText} holds what it said instead. */
	public boolean llmNoOutput;
	public String llmFinalText;
	public int llmSteps;
	public int llmInputTokens;
	public int llmOutputTokens;

	public static final class ColumnRow
	{
		public final String name;
		public final String dataType;
		/** Whether {@code LandingColumn.sourceKey} is currently set on it. */
		public final boolean inCurrentKey;
		/** Null when the sample has no column of this name. */
		public final KeyEvidence.Column evidence;

		public ColumnRow(final String pName, final String pDataType, final boolean pInCurrentKey, final KeyEvidence.Column pEvidence)
		{
			this.name = pName;
			this.dataType = pDataType == null ? "" : pDataType;
			this.inCurrentKey = pInCurrentKey;
			this.evidence = pEvidence;
		}
	}

	public static final class Proposal
	{
		public final List<String> columns;
		/** 0-100. */
		public final int confidence;
		public final String rationale;
		/** {@link #ORIGIN_LLM} or {@link #ORIGIN_RULE}. */
		public final String origin;

		public Proposal(final List<String> pColumns, final int pConfidence, final String pRationale, final String pOrigin)
		{
			this.columns = new ArrayList<String>(pColumns);
			this.confidence = pConfidence;
			this.rationale = pRationale == null ? "" : pRationale;
			this.origin = pOrigin;
		}

		public boolean contains(final String pColumn)
		{
			for (final String column : this.columns)
			{
				if (column.equalsIgnoreCase(pColumn))
				{
					return true;
				}
			}
			return false;
		}

		public String columnsCsv()
		{
			final StringBuilder out = new StringBuilder();
			for (final String column : this.columns)
			{
				if (out.length() > 0)
				{
					out.append(",");
				}
				out.append(column);
			}
			return out.toString();
		}
	}
}
