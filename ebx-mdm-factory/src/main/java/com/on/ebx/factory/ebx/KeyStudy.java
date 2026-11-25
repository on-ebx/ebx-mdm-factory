package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.on.ebx.agent.McpException;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.KeyEvidence;
import com.on.ebx.factory.landing.KeyLlmPass;
import com.on.ebx.factory.landing.KeyStudyReport;
import com.on.ebx.factory.landing.SourceKeyRule;
import com.on.ebx.factory.landing.TextLimits;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;

/**
 * The landing key study (2026-09-23, product owner on project Personnes: "we should get some help from the
 * LLM to identify the key, but also be able to display a sample of data to help me make a choice"). One
 * button of the wizard's step 3 per table, three things in one round trip:
 * <ol>
 * <li>a live sample of the source object ({@link LandingPreview}, {@link #SAMPLE_MAX} rows - larger than the
 * 5-row preview since uniqueness is what is being judged, still never a bulk read),</li>
 * <li>{@link KeyEvidence} on it - per column, empty/distinct counts and the first values - and {@link
 * SourceKeyRule} profiled by those counts, which is what the plan would have proposed had the catalog
 * profiled the table,</li>
 * <li>for an administrator with a language-model connector configured, {@link KeyLlmPass} through {@link
 * AgentRuntime}: the model reads the names, the evidence and the sample rows themselves and calls {@code
 * propose_key} once. Its proposal wins over the rule's when it made one.</li>
 * </ol>
 * Nothing is written by {@link #study}: the report is shown, and the person applies the proposal ({@link
 * #apply}) or picks the columns by hand exactly as before. Same rule as every proposal of the factory - a
 * human decision is never overridden by a pass.
 */
public final class KeyStudy
{
	/** Rows read for the study. */
	public static final int SAMPLE_MAX = 25;
	/** {@code AgentRun.phase} of the pass. */
	public static final String PHASE = "KEY_STUDY";

	private KeyStudy()
	{
	}

	public static KeyStudyReport study(final Session pSession, final Adaptation pFactory, final String pTableId) throws OperationException
	{
		return KeyStudy.study(pSession, pFactory, pTableId, true);
	}

	/**
	 * Sources+landing redesign, lot LANDING (2026-09-24): the same study, with the language-model pass
	 * additionally gated by {@code pAllowLlm} - the wizard's "use the language model" checkbox of the
	 * one-click landing run ({@code ebx.LandingRun}), default on, still administrators-only and still needing
	 * a connector configured underneath (unticking it forces the naming rule alone, whatever the session's
	 * role). The panel-driven "Study the key" button keeps calling the 3-argument overload above, unaffected.
	 * Delegates to the 5-argument overload below with the role read off {@code pSession} itself.
	 */
	public static KeyStudyReport study(final Session pSession, final Adaptation pFactory, final String pTableId, final boolean pAllowLlm) throws OperationException
	{
		// audit F-02: the language model belongs to the DESIGN right (was: administrators)
		return KeyStudy.study(pSession, pFactory, pTableId, pAllowLlm, FactoryAccess.hasInPerson(pSession, com.on.ebx.factory.core.FactoryRight.DESIGN));
	}

	/**
	 * Jobs-queue lot (2026-09-24): same as the 4-argument overload, but the administrator gate is an explicit
	 * {@code pAdministrator} fact rather than derived from {@code pSession}'s own role - {@code ebx.LandingRun}'s
	 * background job runs with the queue's SYSTEM session (never the launcher's), so the launcher's role is
	 * resolved once, at enqueue time, from the launcher's own session, and travels in the job's params; deriving
	 * it here from {@code pSession} would silently read the system session's role instead and could never pass.
	 */
	public static KeyStudyReport study(final Session pSession, final Adaptation pFactory, final String pTableId, final boolean pAllowLlm,
		final boolean pAdministrator) throws OperationException
	{
		final KeyStudyReport report = new KeyStudyReport();
		final Adaptation table = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTableId));
		if (table == null)
		{
			throw EngineRefusal.coded("missing.landingTable", "Landing table " + pTableId + " not found.", pTableId);
		}
		report.tableId = pTableId;
		report.tableName = KeyStudy.text(table, FactoryPaths.LandingTable.TABLE_NAME);
		report.sourceObject = KeyStudy.text(table, FactoryPaths.LandingTable.SOURCE_OBJECT);
		report.editable = "PROPOSED".equals(table.getString(FactoryPaths.LandingTable.STATUS));
		final Object rowCount = table.get(FactoryPaths.LandingTable.SOURCE_ROW_COUNT);
		final String projectSourceId = KeyStudy.text(table, FactoryPaths.LandingTable.PROJECT_SOURCE);
		final Adaptation projectSource = projectSourceId.isEmpty() ? null
			: pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectSourceId));
		final String sourceSystem = projectSource == null ? "" : KeyStudy.text(projectSource, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
		final String projectId = projectSource == null ? null : KeyStudy.text(projectSource, FactoryPaths.ProjectSource.PROJECT);

		// 1. the sample
		final LandingPreview.Result sample = LandingPreview.sample(pSession, pFactory, pTableId, KeyStudy.SAMPLE_MAX);
		report.sampleColumns.addAll(sample.columns);
		report.sampleRows.addAll(sample.rows);
		report.sampleError = sample.error;
		final List<KeyEvidence.Column> evidence = KeyEvidence.analyse(sample.columns, sample.rows);

		// 2. the columns, their evidence, the rule
		final List<Adaptation> columnRows = KeyStudy.columnRows(pFactory, pTableId);
		final List<String> columnNames = new ArrayList<String>();
		final List<KeyLlmPass.ColumnFacts> facts = new ArrayList<KeyLlmPass.ColumnFacts>();
		String currentOrigin = "";
		for (final Adaptation column : columnRows)
		{
			final String name = KeyStudy.text(column, FactoryPaths.LandingColumn.SOURCE_COLUMN);
			final String dataType = KeyStudy.text(column, FactoryPaths.LandingColumn.SOURCE_DATA_TYPE);
			final boolean inKey = Boolean.TRUE.equals(column.get(FactoryPaths.LandingColumn.SOURCE_KEY));
			final Object nullable = column.get(FactoryPaths.LandingColumn.SOURCE_NULLABLE);
			final KeyEvidence.Column columnEvidence = KeyEvidence.find(evidence, name);
			columnNames.add(name);
			report.columns.add(new KeyStudyReport.ColumnRow(name, dataType, inKey, columnEvidence));
			facts.add(new KeyLlmPass.ColumnFacts(name, dataType, nullable instanceof Boolean ? (Boolean) nullable : null, inKey, columnEvidence));
			if (inKey)
			{
				currentOrigin = WizardData.strongerOrigin(currentOrigin, KeyStudy.text(column, FactoryPaths.LandingColumn.SOURCE_KEY_ORIGIN));
			}
		}
		final String[] parts = report.sourceObject.split("[.]");
		final SourceKeyRule.Proposal rule = SourceKeyRule.propose(parts[parts.length - 1], KeyEvidence.candidates(columnNames, evidence), true);
		if (!rule.columns.isEmpty())
		{
			report.proposal = new KeyStudyReport.Proposal(rule.columns, rule.confidence, rule.stored(), KeyStudyReport.ORIGIN_RULE);
		}

		// 3. the language model
		if (!pAllowLlm)
		{
			report.llmSkippedReasonKey = "wizard.step3.landingRun.llmUnticked";
		}
		else if (!pAdministrator)
		{
			report.llmSkippedReasonKey = "wizard.gate.adminOnly";
		}
		else if (!KeyStudy.llmConfigured(pFactory))
		{
			report.llmSkippedReasonKey = "wizard.gate.noLlm";
		}
		else
		{
			final KeyLlmPass.TableFacts tableFacts = new KeyLlmPass.TableFacts(sourceSystem, report.sourceObject, report.tableName,
				rowCount instanceof Number ? ((Number) rowCount).longValue() : 0L, currentOrigin, facts, sample.columns, sample.rows);
			KeyStudy.runLlm(pSession, pFactory, projectId, tableFacts, report);
		}

		report.proposalUniqueInSample = report.proposal == null ? null : KeyEvidence.uniqueTogether(sample.columns, sample.rows, report.proposal.columns);
		return report;
	}

	/** Writes the report's proposal as the table's key ({@code sourceKeyOrigin} = the proposal's origin) - the "Apply this key" button. */
	public static void apply(final Session pSession, final Adaptation pFactory, final KeyStudyReport pReport) throws OperationException
	{
		if (pReport == null || pReport.proposal == null)
		{
			throw EngineRefusal.coded("missing.noKeyProposal", "There is no key proposal to apply: study the key first.");
		}
		WizardData.setTableKey(pSession, pFactory, pReport.tableId, pReport.proposal.columnsCsv(), pReport.proposal.origin, pReport.proposal.confidence,
			pReport.proposal.rationale);
	}

	private static void runLlm(final Session pSession, final Adaptation pFactory, final String pProjectId, final KeyLlmPass.TableFacts pFacts,
		final KeyStudyReport pReport)
	{
		final KeyLlmPass.Session llmSession = new KeyLlmPass.Session(pFacts);
		final ToolSet tools;
		try
		{
			tools = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), Collections.singletonMap("factory", llmSession.toolGroup()));
		}
		catch (final McpException ex)
		{
			// unreachable in practice: no MCP server is ever given to this ToolSet, only the local "factory" group
			pReport.llmError = "Could not build the agent's tool set: " + ex.getMessage();
			return;
		}
		final String goal = "Choose the source key of landing table " + pFacts.tableName + " (" + pFacts.sourceObject + ").";
		final String systemPrompt = KeyLlmPass.systemPrompt();
		// security review (2026-09-24): real values only when the project opted in (llmValueSharing = SAMPLE_VALUES)
		final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId));
		// lot B: the instance's LlmDataPolicy caps the project's choice; sensitive column names are withheld whatever the level
		final LlmDataPolicy policy = LlmDataPolicy.of(pFactory);
		final LlmDataPolicy.Level level = policy.levelFor(project);
		final KeyLlmPass.Sharing sharing = level == LlmDataPolicy.Level.SAMPLE_VALUES ? KeyLlmPass.Sharing.VALUES
			: level == LlmDataPolicy.Level.SHAPES_ONLY ? KeyLlmPass.Sharing.SHAPES : KeyLlmPass.Sharing.NONE;
		final java.util.Set<String> withheld = new java.util.HashSet<String>();
		for (final String column : pFacts.sampleColumns)
		{
			if (policy.isSensitive(column))
			{
				withheld.add(column);
			}
		}
		for (final KeyLlmPass.ColumnFacts column : pFacts.columns)
		{
			if (policy.isSensitive(column.name))
			{
				withheld.add(column.name);
			}
		}
		final String userPrompt = KeyLlmPass.userPrompt(pFacts, sharing, withheld);
		try
		{
			final AgentRuntime.Outcome first = AgentRuntime.run(pSession, pFactory, pProjectId, KeyStudy.PHASE, goal, systemPrompt, userPrompt, tools);
			final ProseAnswerRecovery.Result recovery = ProseAnswerRecovery.recover(first, new ProseAnswerRecovery.RecordedNothing()
			{
				@Override
				public boolean test()
				{
					return llmSession.recordedNothing();
				}
			}, new ProseAnswerRecovery.FollowUpRunner()
			{
				@Override
				public AgentRuntime.Outcome run(final String pFollowUpTask) throws OperationException
				{
					return AgentRuntime.run(pSession, pFactory, pProjectId, KeyStudy.PHASE, goal + " (follow-up: the previous answer was prose, not a tool call)",
						systemPrompt, pFollowUpTask, tools);
				}
			});
			pReport.llmRan = true;
			pReport.llmSteps = recovery.combinedSteps();
			pReport.llmInputTokens = recovery.combinedInputTokens();
			pReport.llmOutputTokens = recovery.combinedOutputTokens();
			final KeyLlmPass.Proposal proposal = llmSession.proposal();
			if (proposal != null)
			{
				pReport.proposal = new KeyStudyReport.Proposal(proposal.columns, proposal.confidence, proposal.rationale, KeyStudyReport.ORIGIN_LLM);
			}
			else
			{
				pReport.llmNoOutput = true;
				final String text = recovery.noOutput ? recovery.finalText : recovery.latest().text;
				pReport.llmFinalText = TextLimits.truncate(text == null ? "" : text.trim(), 600);
			}
		}
		catch (final OperationException ex)
		{
			pReport.llmError = ex.getMessage();
		}
		catch (final RuntimeException ex)
		{
			pReport.llmError = ErrorText.of(ex);
		}
	}

	/** Whether a language-model connector is configured in the settings (audit F-34: {@link KeyStudyRun} decides from it whether the study is queued). */
	static boolean llmConfigured(final Adaptation pFactory)
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		final String llm = settings == null ? null : settings.getString(FactoryPaths.Settings.LLM_CONNECTOR);
		return llm != null && !llm.trim().isEmpty();
	}

	private static List<Adaptation> columnRows(final Adaptation pFactory, final String pTableId)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(pTableId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		Collections.sort(out, new Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pLeft, final Adaptation pRight)
			{
				final Object left = pLeft.get(FactoryPaths.LandingColumn.POSITION);
				final Object right = pRight.get(FactoryPaths.LandingColumn.POSITION);
				final int l = left instanceof Number ? ((Number) left).intValue() : Integer.MAX_VALUE;
				final int r = right instanceof Number ? ((Number) right).intValue() : Integer.MAX_VALUE;
				return Integer.compare(l, r);
			}
		});
		return out;
	}

	private static String text(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		final String value = pRow.getString(pPath);
		return value == null ? "" : value;
	}
}
