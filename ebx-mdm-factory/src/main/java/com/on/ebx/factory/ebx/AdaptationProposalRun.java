package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.accel.AdaptationContext;
import com.on.ebx.factory.accel.AdaptationLlmPass;
import com.on.ebx.factory.accel.DetailShape;
import com.on.ebx.factory.accel.Difference;
import com.on.ebx.factory.accel.DifferenceCheck;
import com.on.ebx.factory.accel.Pack;
import com.on.ebx.factory.accel.PackCatalog;
import com.on.ebx.factory.accel.PackChoice;
import com.on.ebx.factory.accel.ProposalProgress;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Lot C1-C (docs/LOT-C1.md section 4): the adapted proposal of the Centralisation style. {@link #queue} starts, as a {@link FactoryJobQueue}
 * job (kind {@link #STEP}, resource DESIGN), the passes 1 to 4 (or one of them) of {@code docs/ACCELERATORS.md} 6b, one after the other,
 * each through {@link AgentRuntime#ask} (JSON answer, 120 s) with the pure {@link AdaptationLlmPass} prompts and the pure
 * {@link DifferenceCheck}: an answer that cannot be read, or whose lines the check refuses, is asked again ONCE with the reasons (the
 * {@code askOneField} pattern of {@code CapabilityApplier}); a cut-off answer counts as no answer. What is kept is written as
 * {@code Difference} rows {@code origin = LLM, status = PROPOSED} ({@link DifferenceWrites}), plus one KEEP row per pack table the pass left
 * alone. One parent {@code PipelineStep} ({@link #STEP}, scope = the pass or {@code ALL}) holds a {@link ProposalProgress} JSON the wizard polls.
 * <p>
 * <b>Gating.</b> A pass is not started while the previous pass still has PROPOSED rows, unless the run was asked for {@link #ALL} (then the
 * lines of the previous pass, decided or not, are the model the next one reads; rejected ones never are). {@link #queue} says so at once;
 * the handler checks again when the job really starts.
 * <p>
 * <b>Data policy</b> ({@code docs/LLM-DATA-POLICY.md}): what leaves is the pack material of the module, the user's own specification text
 * (fenced as data) and the names and types of the decided model. No table value is ever read here, so the value-sharing level does not apply.
 * <p>
 * {@link #suggestPacks} is pass 0 (the pack choice), synchronous, falling back to {@link PackCatalog#suggest} when no model can be asked.
 */
public final class AdaptationProposalRun
{
	/** Job kind and {@code PipelineStep.step}. */
	public static final String STEP = "ACCEL_PROPOSAL";
	/** The {@code pass} argument of {@link #queue} for passes 1 to 4 in turn. */
	public static final int ALL = -1;

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int TIMEOUT_MS = 120000;
	private static volatile PackCatalog catalogue;

	private AdaptationProposalRun()
	{
	}

	static PackCatalog catalogue()
	{
		PackCatalog c = AdaptationProposalRun.catalogue;
		if (c == null)
		{
			c = PackCatalog.load();
			AdaptationProposalRun.catalogue = c;
		}
		return c;
	}

	// ------------------------------------------------------------------------------------------------ entry points

	/**
	 * Queues the run of pass {@code pPass} (1 to 4) or of all four ({@link #ALL}). Never starts what cannot work, and says why with a bundle key:
	 * {@code started=false} with {@code reasonKey} = {@code accel.pass.noModel} (no language model reachable), {@code accel.pass.noPack} (no pack chosen
	 * for the project) or {@code accel.pass.skip.waiting} ({@code blocked=true}: the previous pass still has PROPOSED lines, {@code reasonArg} = its number).
	 * Otherwise {@code started=true, jobId, merged, position} like the other runs.
	 */
	public static ObjectNode queue(final Session pSession, final Adaptation pFactory, final String pProjectId, final int pPass) throws OperationException
	{
		return AdaptationProposalRun.queue(pSession, pFactory, pProjectId, pPass, false);
	}

	/**
	 * The same with {@code pAndAfter}: pass {@code pPass} and the ones after it, in one job and without waiting for a decision between them ("run all" from a
	 * pass; the passes before it are left alone). Ignored for {@link #ALL} and for pass 1 (that is a full run).
	 */
	public static ObjectNode queue(final Session pSession, final Adaptation pFactory, final String pProjectId, final int pPass, final boolean pAndAfter) throws OperationException
	{
		if (pPass != AdaptationProposalRun.ALL && (pPass < 1 || pPass > 4))
		{
			throw new IllegalArgumentException("pass " + pPass + ": 1 to 4, or AdaptationProposalRun.ALL");
		}
		AdaptationProposalRun.requireProject(pFactory, pProjectId);
		final ObjectNode out = AdaptationProposalRun.MAPPER.createObjectNode();
		if (!AgentRuntime.canAsk(pSession))
		{
			return AdaptationProposalRun.notStarted(out, "accel.pass.noModel", "", false);
		}
		final Chosen chosen = AdaptationProposalRun.readChosen(pFactory, pProjectId);
		if (chosen.packs.isEmpty() && !chosen.blank)
		{
			return AdaptationProposalRun.notStarted(out, "accel.pass.noPack", "", false);
		}
		if (chosen.packs.isEmpty() && chosen.specification.trim().isEmpty())
		{
			// a blank start has nothing but the need: without it the model has nothing to propose from
			return AdaptationProposalRun.notStarted(out, "accel.pass.noNeed", "", false);
		}
		if (pPass > 1 && !pAndAfter && DifferenceWrites.countProposed(pFactory, pProjectId, pPass - 1) > 0)
		{
			return AdaptationProposalRun.notStarted(out, "accel.pass.skip.waiting", String.valueOf(pPass - 1), true);
		}
		final ObjectNode params = AdaptationProposalRun.MAPPER.createObjectNode();
		params.put("pass", pPass);
		params.put("andAfter", pAndAfter && pPass > 1);
		params.put("locale", AdaptationProposalRun.locale(pSession).toLanguageTag());
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, pProjectId, AdaptationProposalRun.STEP, params);
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	private static ObjectNode notStarted(final ObjectNode pOut, final String pKey, final String pArg, final boolean pBlocked)
	{
		pOut.put("started", false);
		pOut.put("blocked", pBlocked);
		pOut.put("reasonKey", pKey);
		pOut.put("reasonArg", pArg);
		return pOut;
	}

	/**
	 * Pass 0: suggests packs and options for a need. It WRITES NOTHING: the wizard shows the cards ticked and the person's "Continue" saves the
	 * {@code ProjectPack} rows ({@code AccelWizard.choosePacks}, status CHOSEN). With a model: {@code via = "llm"}, asked once more with the reason when
	 * the answer holds no pack of the catalogue. Without one, or when the model gives nothing usable: the keyword suggestion {@link PackCatalog#suggest},
	 * {@code via = "keywords"}. The packs another pack requires are added. Answers {@code via, chosenBy (LLM|USER), packs[], options[], why}.
	 */
	public static ObjectNode suggestPacks(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pNeed) throws OperationException
	{
		AdaptationProposalRun.requireProject(pFactory, pProjectId);
		final PackCatalog cat = AdaptationProposalRun.catalogue();
		final String need = pNeed == null ? "" : pNeed.trim();
		PackChoice choice = null;
		String via = "keywords";
		String lastWhy = "no answer";
		if (!need.isEmpty() && AgentRuntime.canAsk(pSession))
		{
			final AdaptationContext context = new AdaptationContext(cat, new ArrayList<Pack>(), null, need, null);
			final String system = AdaptationLlmPass.systemPrompt(0);
			final String prompt = AdaptationLlmPass.userPrompt(0, context);
			String task = prompt;
			for (int attempt = 1; attempt <= 2 && choice == null; attempt++)
			{
				final AgentRuntime.AskOutcome answer =
					AgentRuntime.ask(pSession, pFactory, pProjectId, "ACCEL_PACKS", "Choose the accelerator packs for a need", system, task, 1000, true, AdaptationProposalRun.TIMEOUT_MS);
				final PackChoice parsed = answer.truncated ? null : AdaptationLlmPass.parseChoice(answer.text);
				final PackChoice known = parsed == null ? null : parsed.restrictedTo(cat);
				if (known != null && !known.packs().isEmpty())
				{
					choice = known;
					via = "llm";
				}
				else
				{
					lastWhy = answer.truncated ? "the answer was cut off" : parsed == null ? "the answer was not a JSON object with a \"packs\" array"
						: "none of the pack keys is in the catalogue";
					task = prompt + "\nYour previous answer was refused: " + lastWhy + ". Answer again with the JSON object only.\n";
				}
			}
		}
		if (choice == null && !need.isEmpty())
		{
			final List<String> keys = new ArrayList<String>();
			for (final Pack p : cat.suggest(need))
			{
				if (keys.size() < 2)
				{
					keys.add(p.key());
				}
			}
			choice = new PackChoice(keys, new ArrayList<String>(), "");
		}
		final List<String> packs = AdaptationProposalRun.withRequired(cat, choice == null ? new ArrayList<String>() : choice.packs());
		final ObjectNode out = AdaptationProposalRun.MAPPER.createObjectNode();
		out.put("via", via);
		final ArrayNode packsOut = out.putArray("packs");
		for (final String key : packs)
		{
			packsOut.add(key);
		}
		final ArrayNode optionsOut = out.putArray("options");
		if (choice != null)
		{
			for (final String option : choice.options())
			{
				optionsOut.add(option);
			}
		}
		out.put("why", choice == null ? "" : choice.why());
		out.put("chosenBy", "llm".equals(via) ? "LLM" : "USER");
		return out;
	}

	/** The packs, then the packs they require (recursively), each once. */
	private static List<String> withRequired(final PackCatalog pCat, final List<String> pPacks)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final String key : pPacks)
		{
			AdaptationProposalRun.addWithRequired(pCat, key, out);
		}
		return new ArrayList<String>(out);
	}

	private static void addWithRequired(final PackCatalog pCat, final String pKey, final Set<String> pOut)
	{
		final Pack pack = pCat.pack(pKey);
		if (pack == null || pOut.contains(pKey))
		{
			return;
		}
		for (final String need : pack.requires().packs())
		{
			AdaptationProposalRun.addWithRequired(pCat, need, pOut);
		}
		pOut.add(pKey);
	}

	// ------------------------------------------------------------------------------------------------ the job

	/** The job's work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final int pass = pParams.path("pass").asInt(AdaptationProposalRun.ALL);
			final boolean andAfter = pParams.path("andAfter").asBoolean(false);
			final String tag = pParams.path("locale").asText("");
			final Locale locale = tag.isEmpty() ? Locale.ENGLISH : Locale.forLanguageTag(tag);
			final String stepId = PipelineSteps.begin(pJobSession, pFactory, pProjectId, AdaptationProposalRun.STEP, pass == AdaptationProposalRun.ALL ? "ALL" : andAfter ? pass + "+" : String.valueOf(pass));
			FactoryJobs.attach(pHandle, stepId);
			final ProposalProgress initial = ProposalProgress.newRun();
			PipelineSteps.updateDetail(pJobSession, pFactory, stepId, initial.toJson());
			return AdaptationProposalRun.runInBackground(pJobSession, pFactory, pProjectId, pass, andAfter, stepId, pHandle, locale);
		}
	};

	private static String runInBackground(final Session pSession, final Adaptation pFactory, final String pProjectId, final int pPass, final boolean pAndAfter,
		final String pStepId, final FactoryJobs.Handle pHandle, final Locale pLocale)
	{
		final ProposalProgress progress = ProposalProgress.newRun();
		try
		{
			for (int pass = 1; pass <= 4; pass++)
			{
				final String key = ProposalProgress.keyOf(pass);
				if (pPass != AdaptationProposalRun.ALL && (pAndAfter ? pass < pPass : pPass != pass))
				{
					progress.skip(key, WizardMessages.get(pLocale, "accel.pass.skip.notAsked"));
				}
				else if (pHandle.cancelled())
				{
					progress.skip(key, WizardMessages.get(pLocale, "accel.pass.skip.stopped", String.valueOf(pHandle.stopReason())));
				}
				else if (pPass != AdaptationProposalRun.ALL && !pAndAfter && pass > 1 && DifferenceWrites.countProposed(pFactory, pProjectId, pass - 1) > 0)
				{
					progress.skip(key, WizardMessages.get(pLocale, "accel.pass.skip.waiting", String.valueOf(pass - 1)));
				}
				else
				{
					AdaptationProposalRun.runStage(pSession, pFactory, pProjectId, pStepId, pHandle, progress, pass, pLocale);
				}
				PipelineSteps.updateDetail(pSession, pFactory, pStepId, progress.toJson());
			}
			if (pHandle.cancelled())
			{
				final String message = WizardMessages.get(pLocale, "accel.pass.skip.stopped", String.valueOf(pHandle.stopReason()));
				PipelineSteps.endStopped(pSession, pFactory, pStepId, message, progress.toJson());
				return message;
			}
			final String message = WizardMessages.get(pLocale, "accel.pass.finished");
			PipelineSteps.end(pSession, pFactory, pStepId, true, message, progress.toJson());
			return message;
		}
		catch (final Exception ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] adapted proposal failed for project " + pProjectId + ": " + ex, ex);
			PipelineSteps.end(pSession, pFactory, pStepId, false, WizardMessages.get(pLocale, "accel.pass.failed", TextLimits.truncate(ErrorText.of(ex), 300)),
				progress.toJson());
			throw new IllegalStateException("Adapted proposal failed: " + ex.getMessage(), ex);
		}
	}

	/** One pass: marks it RUNNING, runs, marks DONE (an answer was obtained) or FAILED (none, or an error), persists each time. */
	private static void runStage(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pStepId, final FactoryJobs.Handle pHandle,
		final ProposalProgress pProgress, final int pPass, final Locale pLocale)
	{
		final String key = ProposalProgress.keyOf(pPass);
		pProgress.start(key, System.currentTimeMillis());
		PipelineSteps.updateDetail(pSession, pFactory, pStepId, pProgress.toJson());
		try
		{
			final ProposalProgress.Counts counts = AdaptationProposalRun.runPass(pSession, pFactory, pProjectId, pPass);
			final String summary = counts.noAnswer ? WizardMessages.get(pLocale, "accel.pass.noAnswer") : WizardMessages.get(pLocale, "accel.pass.summary",
				String.valueOf(counts.written()), String.valueOf(counts.toPlace), String.valueOf(counts.keepRows), String.valueOf(counts.refused), String.valueOf(counts.dropped));
			pProgress.finish(key, !counts.noAnswer, summary, counts, System.currentTimeMillis());
		}
		catch (final Exception ex) // one pass's own failure must not stop the later passes of an ALL run
		{
			LoggingCategory.getKernel().warn("[mdm-factory] adapted proposal pass " + pPass + " failed for project " + pProjectId + ": " + ex);
			pProgress.finish(key, false, TextLimits.truncate(ErrorText.of(ex), 500), null, System.currentTimeMillis());
		}
		PipelineSteps.updateDetail(pSession, pFactory, pStepId, pProgress.toJson());
	}

	/** What {@link #evaluate} found in one answer. */
	private static final class Evaluated
	{
		AdaptationLlmPass.Parsed parsed;
		DifferenceCheck.Batch batch;
		boolean truncated;
		boolean readable;
	}

	/** The work of one pass; throws when the model cannot be reached. */
	private static ProposalProgress.Counts runPass(final Session pSession, final Adaptation pFactory, final String pProjectId, final int pPass) throws OperationException
	{
		final PackCatalog cat = AdaptationProposalRun.catalogue();
		final Chosen chosen = AdaptationProposalRun.readChosen(pFactory, pProjectId);
		if (chosen.packs.isEmpty() && (!chosen.blank || chosen.specification.trim().isEmpty()))
		{
			throw new IllegalStateException(chosen.blank ? "The project started blank and has no need written: nothing to propose from." : "No pack is chosen for this project.");
		}
		final AdaptationContext context = new AdaptationContext(cat, chosen.packs, chosen.options, chosen.specification, DifferenceWrites.standingBefore(pFactory, pProjectId, pPass));
		final DifferenceCheck.CheckContext check = context.checkContext();
		final String system = AdaptationLlmPass.systemPrompt(pPass, chosen.packs.isEmpty());
		final String prompt = AdaptationLlmPass.userPrompt(pPass, context);
		final int maxTokens = pPass == 3 ? 6000 : 3000;
		final String phase = "ACCEL_PROPOSAL_P" + pPass;
		final String goal = "Adapted proposal, pass " + pPass;

		AgentRuntime.AskOutcome answer = AgentRuntime.ask(pSession, pFactory, pProjectId, phase, goal, system, prompt, maxTokens, true, AdaptationProposalRun.TIMEOUT_MS);
		Evaluated result = AdaptationProposalRun.evaluate(pPass, answer, context, check);
		if (!result.readable || !result.batch.refused.isEmpty())
		{
			final String again = !result.readable ? AdaptationLlmPass.reaskUnreadable(prompt, result.truncated)
				: AdaptationLlmPass.reaskPrompt(prompt, result.batch.refusalText());
			answer = AgentRuntime.ask(pSession, pFactory, pProjectId, phase, goal, system, again, maxTokens, true, AdaptationProposalRun.TIMEOUT_MS);
			final Evaluated second = AdaptationProposalRun.evaluate(pPass, answer, context, check);
			if (second.readable || !result.readable)
			{
				result = second;
			}
		}
		final ProposalProgress.Counts counts = new ProposalProgress.Counts();
		if (!result.readable)
		{
			counts.noAnswer = true;
			return counts; // nothing is cleared, nothing is written: an earlier proposal of this pass stays
		}
		final List<Difference> lines = new ArrayList<Difference>();
		counts.refused = result.batch.refused.size();
		counts.dropped = result.batch.dropped;
		counts.ignored = result.parsed.overCap + result.parsed.malformed;
		for (final DifferenceCheck.Verdict v : result.batch.kept)
		{
			if (v.difference().detailJson().length() > DifferenceWrites.TEXT_MAX)
			{
				counts.ignored++; // a layout longer than the column: asking for a shorter one is the person's re-run
				continue;
			}
			Difference kept = v.difference().withPackKey(v.difference().packKey().isEmpty() && !chosen.packs.isEmpty() ? chosen.packs.get(0).key() : v.difference().packKey());
			final DifferenceCheck.Existing existing = DifferenceCheck.looksLikeExisting(kept, cat);
			if (existing != null)
			{
				kept = kept.withDetail(DetailShape.withSimilar(kept.detail(), existing.toString(), ""));
			}
			lines.add(kept);
			if (v.toPlace())
			{
				counts.toPlace++;
			}
			else
			{
				counts.kept++;
			}
		}
		DifferenceWrites.clearProposed(pSession, pFactory, pProjectId, pPass);
		DifferenceWrites.write(pSession, pFactory, pProjectId, pPass, lines, "LLM", "PROPOSED", "Language model, adapted proposal pass " + pPass + ".");
		if (pPass == 1)
		{
			counts.keepRows = AdaptationProposalRun.writeKeepRows(pSession, pFactory, pProjectId, chosen.packs, lines);
		}
		return counts;
	}

	private static Evaluated evaluate(final int pPass, final AgentRuntime.AskOutcome pAnswer, final AdaptationContext pContext, final DifferenceCheck.CheckContext pCheck)
	{
		final Evaluated e = new Evaluated();
		e.truncated = pAnswer.truncated;
		if (pAnswer.truncated)
		{
			return e; // a cut JSON answer is never a usable one
		}
		e.parsed = AdaptationLlmPass.parseReport(pPass, pAnswer.text, pContext.model().tables().size());
		e.readable = e.parsed.readable;
		if (!e.readable)
		{
			return e;
		}
		List<Difference> items = e.parsed.items;
		if (pPass == 3)
		{
			final Set<String> withLayout = new LinkedHashSet<String>();
			for (final Pack p : pContext.packs())
			{
				withLayout.addAll(p.layouts().keySet());
			}
			final List<Difference> typed = new ArrayList<Difference>();
			for (final Difference d : items)
			{
				typed.add(d.withNature(withLayout.contains(d.where()) ? Difference.Nature.CHANGE : Difference.Nature.ADD));
			}
			items = typed;
		}
		e.batch = DifferenceCheck.checkAll(pPass, items, pCheck);
		return e;
	}

	/** One KEEP line per pack table no line of pass 1 touches (a REMOVE counts as touching). @return how many */
	private static int writeKeepRows(final Session pSession, final Adaptation pFactory, final String pProjectId, final List<Pack> pPacks, final List<Difference> pLines)
		throws OperationException
	{
		final List<Difference> keep = new ArrayList<Difference>();
		for (final Pack pack : pPacks)
		{
			for (final String table : pack.core().tablePaths())
			{
				if (!DifferenceWrites.touches(pLines, table))
				{
					keep.add(new Difference(1, Difference.Nature.KEEP, "Keep " + table.substring(table.lastIndexOf('/') + 1), table, Difference.PlaceState.PLACED, null, "",
						pack.key(), "", "", ""));
				}
			}
		}
		return DifferenceWrites.write(pSession, pFactory, pProjectId, 1, keep, "RULE", "ACCEPTED", "Pack table left as it is.");
	}

	// ------------------------------------------------------------------------------------------------ what the project chose

	/** The packs, options and specification of the project, read from its {@code ProjectPack} rows and its {@code Project} row (need, blank start). */
	private static final class Chosen
	{
		final List<Pack> packs = new ArrayList<Pack>();
		final List<String> options = new ArrayList<String>();
		String specification = "";
		/** "Skip the packs, start blank": no pack, the language model proposes the whole model from the need. */
		boolean blank;
	}

	private static Chosen readChosen(final Adaptation pFactory, final String pProjectId)
	{
		final PackCatalog cat = AdaptationProposalRun.catalogue();
		final Chosen chosen = new Chosen();
		final Map<String, Pack> byKey = new LinkedHashMap<String, Pack>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT_PACK).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Pack pack = cat.pack(row.getString(FactoryPaths.ProjectPack.PACK_KEY));
				if (pack == null)
				{
					continue;
				}
				byKey.put(pack.key(), pack);
				final List<?> options = row.getList(FactoryPaths.ProjectPack.OPTIONS);
				if (options != null)
				{
					for (final Object option : options)
					{
						if (option != null && !chosen.options.contains(option.toString()))
						{
							chosen.options.add(option.toString());
						}
					}
				}
				final String spec = row.getString(FactoryPaths.ProjectPack.SPECIFICATION);
				if (chosen.specification.isEmpty() && spec != null)
				{
					chosen.specification = spec;
				}
			}
		}
		finally
		{
			rows.close();
		}
		// the need on the project wins (it exists with or without a pack); the pack rows carry it for a project that predates the field
		final Adaptation project = AccelData.project(pFactory, pProjectId);
		final String onProject = AccelData.projectSpecification(project);
		if (!onProject.trim().isEmpty())
		{
			chosen.specification = onProject;
		}
		chosen.blank = AccelData.blankStart(project);
		// a required pack comes first: its tables are the ones the others point at
		for (final Pack p : cat.packs())
		{
			if (byKey.containsKey(p.key()))
			{
				chosen.packs.add(p);
			}
		}
		return chosen;
	}

	// ------------------------------------------------------------------------------------------------ status

	/** Current state of the latest run of the project: {@code exists}, {@code status}, {@code running}, {@code queued}, {@code jobId}, {@code message}, {@code detailJson}, {@code stages[]}. */
	public static ObjectNode status(final Adaptation pFactory, final String pProjectId)
	{
		final ObjectNode out = AdaptationProposalRun.MAPPER.createObjectNode();
		final Adaptation job = FactoryJobQueue.latest(pFactory, pProjectId, AdaptationProposalRun.STEP);
		final boolean queued = job != null && FactoryJobQueue.QUEUED.equals(job.getString(FactoryPaths.FactoryJob.STATUS));
		final Adaptation row = AdaptationProposalRun.latestRow(pFactory, pProjectId);
		if (row == null && !queued)
		{
			out.put("exists", false);
			return out;
		}
		final String status = queued ? FactoryJobQueue.QUEUED : row.getString(FactoryPaths.PipelineStep.STATUS);
		final boolean running = queued || "RUNNING".equals(status) && FactoryJobs.isLive(pProjectId, AdaptationProposalRun.STEP);
		out.put("exists", true);
		out.put("status", status);
		out.put("running", running);
		out.put("queued", queued);
		if (job != null)
		{
			out.put("jobId", job.getOccurrencePrimaryKey().format());
		}
		if (queued)
		{
			out.put("detailJson", "");
			return out;
		}
		final String message = row.getString(FactoryPaths.PipelineStep.MESSAGE);
		if (message != null)
		{
			out.put("message", message);
		}
		final String detail = row.getString(FactoryPaths.PipelineStep.DETAIL);
		out.put("detailJson", detail == null ? "" : detail);
		final ArrayNode stages = out.putArray("stages");
		for (final ProposalProgress.Stage stage : ProposalProgress.parse(detail).stages())
		{
			final ObjectNode n = stages.addObject();
			n.put("key", stage.key);
			n.put("pass", ProposalProgress.passOf(stage.key));
			n.put("state", stage.state);
			if (stage.summary != null)
			{
				n.put("summary", stage.summary);
			}
		}
		return out;
	}

	private static Adaptation latestRow(final Adaptation pFactory, final String pProjectId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(AdaptationProposalRun.STEP));
		try
		{
			Adaptation latest = null;
			Date latestStartedAt = null;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Date startedAt = (Date) row.get(FactoryPaths.PipelineStep.STARTED_AT);
				if (latest == null || startedAt != null && (latestStartedAt == null || startedAt.after(latestStartedAt)))
				{
					latest = row;
					latestStartedAt = startedAt;
				}
			}
			return latest;
		}
		finally
		{
			rows.close();
		}
	}

	private static void requireProject(final Adaptation pFactory, final String pProjectId) throws OperationException
	{
		if (pProjectId == null || pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId)) == null)
		{
			throw EngineRefusal.projectNotFound(pProjectId);
		}
	}

	private static Locale locale(final Session pSession)
	{
		return pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale();
	}
}
