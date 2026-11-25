package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.accel.Pack;
import com.on.ebx.factory.accel.ReferenceTable;
import com.on.ebx.factory.wizard.WizardSnapshot;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Lot C1 (docs/LOT-C1.md): the seams the CENTRALISATION wizard path calls, wired to the proposal passes ({@link AdaptationProposalRun}, jobs of kind
 * {@code ACCEL_PROPOSAL}), the generation ({@link DomainGenerator}, job kind {@code ACCEL_GENERATE_RUN}) and the pack catalogue
 * ({@code accel.PackCatalog}). Package-private: the screens reach it only through {@link AccelWizard}.
 * <p>
 * A run that cannot start (no model reachable, no pack chosen, the previous pass not decided yet) answers {@code started=false} with a bundle key
 * ({@code reasonKey}, {@code reasonArg}); here it becomes the coded refusal of that key, which the wizard shows as a notice.
 */
final class AccelHooks
{
	private AccelHooks()
	{
	}

	/**
	 * Pass 0 (pack choice). Answers the JSON object {@code {"packs": [keys], "options": ["pack:option", ...], "why": "..."}}; the model's own choice, or the keyword
	 * hit when no model is reachable. Writes nothing: the wizard saves the packs when the person continues.
	 */
	static String suggestPacks(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pNeed, final Locale pLocale) throws OperationException
	{
		final ObjectNode answer = AdaptationProposalRun.suggestPacks(pSession, pFactory, pProjectId, pNeed);
		final ObjectNode out = answer.objectNode();
		out.set("packs", answer.path("packs"));
		out.set("options", answer.path("options"));
		out.put("why", answer.path("why").asText(""));
		return out.toString();
	}

	/** Queues the whole proposal as one {@code ACCEL_PROPOSAL} job: the four passes in turn, each waiting for the previous one to be decided only when run alone. */
	static void runProposal(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode, final Locale pLocale) throws OperationException
	{
		AccelHooks.requireStarted(AdaptationProposalRun.queue(pSession, pFactory, pProjectId, AdaptationProposalRun.ALL));
	}

	/** Queues one pass ({@code 1..4}) alone, or, with {@code pRunAll}, that pass and the ones after it in one job, without waiting for a decision. */
	static void runPass(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode, final int pPass, final boolean pRunAll,
		final Locale pLocale) throws OperationException
	{
		AccelHooks.requireStarted(AdaptationProposalRun.queue(pSession, pFactory, pProjectId, pPass, pRunAll));
	}

	/** Queues the generation of the domain as one {@code ACCEL_GENERATE_RUN} job. */
	static void generate(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode, final Locale pLocale) throws OperationException
	{
		AccelHooks.requireStarted(DomainGenerator.queue(pSession, pFactory, pProjectId));
	}

	/** Turns a {@code started=false} answer of a queue method into the coded refusal of its {@code reasonKey}. */
	static void requireStarted(final ObjectNode pAnswer) throws OperationException
	{
		if (pAnswer.path("started").asBoolean(true))
		{
			return;
		}
		final String key = pAnswer.path("reasonKey").asText("wizard.accel.notAvailable");
		final String arg = pAnswer.path("reasonArg").asText("");
		throw EngineRefusal.of(key, "The run was not started (" + key + (arg.isEmpty() ? "" : ": " + arg) + ").", arg);
	}

	/** The packs of the catalogue with what each brings, labels in the language of {@code pLocale}. Never null. */
	static List<WizardSnapshot.PackInfo> packCatalogue(final Locale pLocale)
	{
		final List<WizardSnapshot.PackInfo> out = new ArrayList<WizardSnapshot.PackInfo>();
		final Locale locale = pLocale == null ? Locale.ENGLISH : pLocale;
		for (final Pack pack : AdaptationProposalRun.catalogue().packs())
		{
			final WizardSnapshot.PackInfo info = new WizardSnapshot.PackInfo(pack.key(), pack.version(), pack.label(locale), pack.description(locale));
			info.blocks.addAll(pack.blocks());
			info.options.addAll(pack.options());
			info.requires.addAll(pack.requires().packs());
			info.tables = pack.core().tablePaths().size();
			info.fields = pack.core().fieldPaths().size();
			for (final ReferenceTable table : pack.referenceData().values())
			{
				info.referenceRows += table.rows().size();
			}
			out.add(info);
		}
		return out;
	}
}
