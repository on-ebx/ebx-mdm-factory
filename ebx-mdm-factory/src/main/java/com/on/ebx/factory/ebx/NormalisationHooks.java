package com.on.ebx.factory.ebx;

import java.util.Locale;

import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;

/**
 * Staging normalisation lot D (2026-10-17): the three points of the one-click proposal run ({@link StagingProposalRun}) where
 * {@link NormalisationSuggester} runs, each one a single call that returns the sentence to append to its stage's summary:
 * <ol>
 * <li>{@link #afterRulePass}: right after the rule pass - the landing columns are profiled (BEFORE the language-model pass,
 * which then reads the same facts from {@code StagingLlmSuggester}), the deterministic steps are proposed on the rule-pass
 * mappings and every transform of theirs is verified. A project without a language model ends here, already normalised.</li>
 * <li>{@link #afterLlmPass}: right after the language-model structure pass - the same pass over the links the model wrote
 * (new mappings, unified sources: the common letter case across sources can only be decided now), and their transforms
 * go through the same verifier.</li>
 * <li>{@link #afterQualityPass}: after the quality language-model pass - its steps (a {@code STRIP_CHARS} that strips the
 * {@code +}) and its effect on the chain are verified too. {@link #endOfRun} drops the run's cache.</li>
 * </ol>
 * <b>Never fails the run</b>: whatever goes wrong is logged and written into the stage's summary ("Value checks did not
 * complete: ..."), never thrown - one pass's own bug must not take the rest of the proposal down with it.
 */
public final class NormalisationHooks
{
	private NormalisationHooks()
	{
	}

	public static String afterRulePass(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Locale pLocale)
	{
		return NormalisationHooks.run(pSession, pFactory, pProjectCode, pLocale, true);
	}

	public static String afterLlmPass(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Locale pLocale)
	{
		// codes transcoding lot H3 (2026-10-17): the code lists the sources write differently are put to the language model right after the structure pass, in the stage
		// that already runs only when a model is configured; the tables it proposes are verified by a second, cached run of the normalisation pass
		final NormalisationSuggester.Outcome[] outcome = new NormalisationSuggester.Outcome[1];
		final String normalised = NormalisationHooks.run(pSession, pFactory, pProjectCode, pLocale, false, outcome);
		return normalised + NormalisationHooks.codeLists(pSession, pFactory, pProjectCode, pLocale, outcome[0]);
	}

	public static String afterQualityPass(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Locale pLocale)
	{
		return NormalisationHooks.run(pSession, pFactory, pProjectCode, pLocale, false);
	}

	/** End of the proposal run: the landing columns read by the hooks are dropped from memory. */
	public static void endOfRun(final Adaptation pFactory, final String pProjectCode)
	{
		final String projectId = NormalisationHooks.projectId(pFactory, pProjectCode);
		if (projectId != null)
		{
			NormalisationSuggester.release(projectId);
		}
	}

	private static String run(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Locale pLocale, final boolean pFresh)
	{
		return NormalisationHooks.run(pSession, pFactory, pProjectCode, pLocale, pFresh, null);
	}

	private static String run(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Locale pLocale, final boolean pFresh,
		final NormalisationSuggester.Outcome[] pOutcomeOut)
	{
		try
		{
			final NormalisationSuggester.Outcome outcome = NormalisationSuggester.run(pSession, pFactory, pProjectCode, pFresh);
			if (pOutcomeOut != null)
			{
				pOutcomeOut[0] = outcome;
			}
			return " " + NormalisationHooks.sentence(outcome, pLocale);
		}
		catch (final Exception ex) // deliberately broad: see the class Javadoc
		{
			LoggingCategory.getKernel().error("[mdm-factory] normalisation pass failed for project " + pProjectCode + ": " + ex, ex);
			return " " + WizardMessages.get(pLocale, "wizard.step4.run.stage.normalise.failed", TextLimits.truncate(ErrorText.of(ex), 300));
		}
	}

	/**
	 * Codes transcoding lot H3: asks the language model about the code lists of {@code pOutcome} that do not line up, then runs the normalisation pass again (the
	 * columns are cached) so that the tables written are verified on the values and the links they leave incomplete are flagged. Returns the sentence to append to the
	 * stage's summary - empty when there was nothing to align. Never fails the run.
	 */
	private static String codeLists(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Locale pLocale, final NormalisationSuggester.Outcome pOutcome)
	{
		if (pOutcome == null || pOutcome.alignments.isEmpty())
		{
			return "";
		}
		try
		{
			final CodeTranscodingSuggester.Result result = CodeTranscodingSuggester.suggest(pSession, pFactory, pProjectCode, pOutcome.alignments, pLocale);
			if (result.isEmpty())
			{
				return "";
			}
			if (result.stepsWritten > 0)
			{
				NormalisationSuggester.run(pSession, pFactory, pProjectCode, false);
			}
			return " " + WizardMessages.get(pLocale, "wizard.step4.run.stage.codes.summary", WizardMessages.countWords(pLocale, result.stepsWritten, "wizard.unit.codeTable"),
				WizardMessages.countWords(pLocale, result.stepsIncomplete, "wizard.unit.codeTableIncomplete"));
		}
		catch (final Exception ex) // deliberately broad: see the class Javadoc
		{
			LoggingCategory.getKernel().error("[mdm-factory] code list pass failed for project " + pProjectCode + ": " + ex, ex);
			return " " + WizardMessages.get(pLocale, "wizard.step4.run.stage.codes.failed", TextLimits.truncate(ErrorText.of(ex), 300));
		}
	}

	private static String projectId(final Adaptation pFactory, final String pProjectCode)
	{
		try
		{
			final com.onwbp.adaptation.RequestResult rows = pFactory.getTable(com.on.ebx.factory.core.FactoryPaths.PROJECT)
				.createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
			try
			{
				final Adaptation project = rows.nextAdaptation();
				return project == null ? null : project.getOccurrencePrimaryKey().format();
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** The stage summary sentence for an outcome, in the viewer's language. */
	static String sentence(final NormalisationSuggester.Outcome pOutcome, final Locale pLocale)
	{
		if (pOutcome.isEmpty())
		{
			return WizardMessages.get(pLocale, "wizard.step4.run.stage.normalise.none");
		}
		return WizardMessages.get(pLocale, "wizard.step4.run.stage.normalise.summary",
			WizardMessages.countWords(pLocale, pOutcome.stepsCreated + pOutcome.stepsRefreshed + pOutcome.stepsRepaired, "wizard.unit.normStep"),
			WizardMessages.countWords(pLocale, pOutcome.pipelinesFixed, "wizard.unit.normFix"),
			WizardMessages.countWords(pLocale, pOutcome.linksFlagged, "wizard.unit.normFlag"));
	}
}
