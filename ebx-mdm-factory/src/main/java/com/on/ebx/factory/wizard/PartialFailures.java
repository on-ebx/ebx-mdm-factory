package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.landing.LandingRunProgress;

/**
 * UX lot D (2026-10-14): partial failures as a real outcome. A job that did some of its work and failed on the rest ends {@link #STATUS}
 * ({@code FactoryJob.status}), with the items that failed in {@code FactoryJob.detail} as JSON - until this lot the stored status was DONE and the only
 * trace was the English sentence of the job, parsed by the screens (measured 2026-10-13 on UxCrmSf2: the Cockpit, the jobs route and the next steps all
 * saw a success). The intake chain has had its own status for the same idea since the automatic-intake lot ({@code DONE_WITH_REJECTS}).
 * <p>
 * This class is the one place that knows the shape of that detail and how the screens word it: the items of a landing run (the tables with a failed
 * cell of the progress grid, with the reason of the cell) and of a matching configuration or run (the objects refused or failed, "Entity: reason"
 * entries), what is STILL missing now (an item may have been fixed by another route since), the sentences ("2 of 4 tables were not loaded: ...", "The
 * staging will be built without ..."). No EBX class.
 */
public final class PartialFailures
{
	/** The stored status of a job that did some of its work and failed on the rest (the XSD's {@code FactoryJob/status} value). */
	public static final String STATUS = "DONE_WITH_FAILURES";

	/** The groups of kinds that "a later run of the same kind" is judged in: the landing run and its resume count as one. */
	public static final String LANDING = "LANDING";
	public static final String CONFIGURE = "CONFIGURE";
	public static final String RUN = "RUN";

	/** The job kinds of each group ({@code LandingRun.STEP}, {@code LandingRun.RESUME_STEP}, {@code MatchingConfigureRun.STEP}, {@code MatchingRunRun.STEP}). */
	public static final String[] LANDING_KINDS = { "LANDING_RUN", "LANDING_RUN_RESUME" };
	public static final String CONFIGURE_KIND = "MATCHING_CONFIGURE";
	public static final String RUN_KIND = "MATCHING_RUN";

	/** The size of {@code FactoryJob.detail} in the XSD; the JSON written never exceeds it. */
	static final int DETAIL_MAX = 4000;
	/** A reason longer than this is cut in the stored detail (the full text is in the job's message and the grid). */
	static final int REASON_MAX = 200;
	/** The warning sentence names this many items, then "and N more". */
	static final int NAMES_MAX = 4;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private PartialFailures()
	{
	}

	/** One item that failed: a landing table, a matching object. {@code id} is what the screen matches on (the landing table id, the entity name). */
	public static final class Item
	{
		public final String id;
		public final String label;
		public final String reason;

		public Item(final String pId, final String pLabel, final String pReason)
		{
			this.id = pId == null ? "" : pId;
			this.label = pLabel == null || pLabel.isEmpty() ? this.id : pLabel;
			this.reason = pReason == null ? "" : pReason;
		}
	}

	/** What a finished job says failed: how many items the run had in all (0 = unknown) and the ones that failed. */
	public static final class Failures
	{
		public final int total;
		public final List<Item> items;

		public Failures(final int pTotal, final List<Item> pItems)
		{
			this.total = Math.max(0, pTotal);
			this.items = pItems == null ? new ArrayList<Item>() : pItems;
		}
	}

	/** The latest job of a group of kinds, as the screens need it: how it ended and what failed. Built by the reader of {@code FactoryJob}. */
	public static final class Last
	{
		public final String group;
		public final String status;
		public final Failures failures;

		public Last(final String pGroup, final String pStatus, final Failures pFailures)
		{
			this.group = pGroup;
			this.status = pStatus == null ? "" : pStatus;
			this.failures = pFailures == null ? new Failures(0, null) : pFailures;
		}

		/** Whether this job ended with failures (the only status that carries items). */
		public boolean partial()
		{
			return PartialFailures.STATUS.equals(this.status);
		}
	}

	/** The group a job kind belongs to ({@link #LANDING}, {@link #CONFIGURE}, {@link #RUN}), null for a kind that never ends with failures. */
	public static String groupOf(final String pKind)
	{
		if (pKind == null)
		{
			return null;
		}
		if ("LANDING_RUN".equals(pKind) || "LANDING_RUN_RESUME".equals(pKind))
		{
			return PartialFailures.LANDING;
		}
		if (PartialFailures.CONFIGURE_KIND.equals(pKind))
		{
			return PartialFailures.CONFIGURE;
		}
		return PartialFailures.RUN_KIND.equals(pKind) ? PartialFailures.RUN : null;
	}

	// ---------------------------------------------------------------------------------------------------- what failed

	/**
	 * The landing tables with a failed cell in the progress grid of a run, in the grid's order, labelled {@code SOURCE.Table}, with the reason of the first
	 * failed cell (the one the grid shows). A table waiting for a key, skipped or still to do is not a failure here.
	 */
	public static Failures fromLanding(final LandingRunProgress pProgress)
	{
		final List<Item> items = new ArrayList<Item>();
		int total = 0;
		for (final LandingRunProgress.TableProgress table : pProgress.tables())
		{
			total++;
			for (final LandingRunProgress.Cell cell : table.cells())
			{
				if (LandingRunProgress.FAILED.equals(cell.state))
				{
					items.add(new Item(table.id, PartialFailures.tableLabel(table.sourceSystem, table.tableName), cell.summary));
					break;
				}
			}
		}
		return new Failures(total, items);
	}

	/**
	 * The sentence a landing job ends with when some tables failed ("Landing run finished with failures: 2 of 4 tables were not loaded (A, B)."): English, stored
	 * in the job's message (the screens word the same facts in the person's language from the detail).
	 */
	public static String landingMessage(final Failures pFailures)
	{
		return PartialFailures.landingText(pFailures).english;
	}

	/** Run panel i18n (2026-10-17): {@link #landingMessage} as a code plus its arguments (the stored form is the English followed by the marker). */
	public static com.on.ebx.factory.landing.KeyTexts.Text landingText(final Failures pFailures)
	{
		return com.on.ebx.factory.landing.RunTexts.partial(pFailures.items.size(), pFailures.total, PartialFailures.names(pFailures.items));
	}

	/** {@code SOURCE.Table}, or the table alone when the application is unknown. */
	static String tableLabel(final String pSourceSystem, final String pTableName)
	{
		final String source = pSourceSystem == null ? "" : pSourceSystem.trim();
		final String table = pTableName == null ? "" : pTableName.trim();
		return source.isEmpty() || "?".equals(source) ? table : source + "." + table;
	}

	/**
	 * The objects of a matching configuration or run that failed, from the entries the engine writes ("Organisation: MCP call failed ..." for a
	 * refusal, "Personne - the add-on ended in failure" for a failed run): the name is what precedes the first ": " or " - ", the rest is the reason.
	 * {@code pTotal} is how many objects the run handled (0 = unknown).
	 */
	public static Failures fromEntries(final List<String> pEntries, final int pTotal)
	{
		final List<Item> items = new ArrayList<Item>();
		final Set<String> seen = new LinkedHashSet<String>();
		if (pEntries != null)
		{
			for (final String entry : pEntries)
			{
				if (entry == null || entry.trim().isEmpty())
				{
					continue;
				}
				final String text = entry.trim();
				final int colon = text.indexOf(": ");
				final int dash = text.indexOf(" - ");
				int cut = colon < 0 ? dash : dash < 0 ? colon : Math.min(colon, dash);
				final String name = cut < 0 ? text : text.substring(0, cut).trim();
				final String reason = cut < 0 ? "" : text.substring(cut + (cut == dash ? 3 : 2)).trim();
				if (!name.isEmpty() && seen.add(name))
				{
					items.add(new Item(name, name, reason));
				}
			}
		}
		return new Failures(pTotal, items);
	}

	/**
	 * Whether a landing run is a partial failure rather than a total one: at least one table failed and at least one did not. A run where EVERY table
	 * failed fails the whole job (nothing was loaded, nothing to keep); one where none failed is a success.
	 */
	public static boolean isPartial(final Failures pFailures)
	{
		return pFailures.total > 0 && !pFailures.items.isEmpty() && pFailures.items.size() < pFailures.total;
	}

	/** Whether every item of the run failed (and the run had some). */
	public static boolean allFailed(final Failures pFailures)
	{
		return pFailures.total > 0 && pFailures.items.size() >= pFailures.total;
	}

	// ---------------------------------------------------------------------------------------------------- the stored detail

	/** The JSON stored in {@code FactoryJob.detail}: {@code {"total":4,"items":[{"id","label","reason"}]}}, never longer than {@link #DETAIL_MAX} characters. */
	public static String toJson(final Failures pFailures)
	{
		int reasonMax = PartialFailures.REASON_MAX;
		while (true)
		{
			final String json = PartialFailures.json(pFailures, reasonMax, pFailures.items.size());
			if (json.length() <= PartialFailures.DETAIL_MAX)
			{
				return json;
			}
			if (reasonMax > 20)
			{
				reasonMax = reasonMax / 2;
				continue;
			}
			// the reasons are as short as they go: drop the last items (the count shown still comes from "total" and the job's message)
			int keep = pFailures.items.size();
			while (keep > 0 && PartialFailures.json(pFailures, reasonMax, keep).length() > PartialFailures.DETAIL_MAX)
			{
				keep--;
			}
			return PartialFailures.json(pFailures, reasonMax, keep);
		}
	}

	private static String json(final Failures pFailures, final int pReasonMax, final int pKeep)
	{
		final ObjectNode root = PartialFailures.MAPPER.createObjectNode();
		root.put("total", pFailures.total);
		final ArrayNode items = root.putArray("items");
		for (int i = 0; i < pKeep && i < pFailures.items.size(); i++)
		{
			final Item item = pFailures.items.get(i);
			final ObjectNode node = items.addObject();
			node.put("id", item.id);
			node.put("label", item.label);
			node.put("reason", PartialFailures.cut(item.reason, pReasonMax));
		}
		return root.toString();
	}

	/** A reason cut to {@code pMax} characters with a closing ellipsis; run panel i18n (2026-10-17): the code marker a reason may end with is never cut. */
	private static String cut(final String pReason, final int pMax)
	{
		final String raw = EngineMessageCodec.decode(pReason).raw;
		final String marker = pReason.substring(raw.length());
		return (raw.length() > pMax ? raw.substring(0, Math.max(0, pMax - 1)) + "\u2026" : raw) + marker;
	}

	/** Reads {@link #toJson}'s output; also a bare array of items. Never throws: an unreadable or empty detail is "no item". */
	public static Failures parse(final String pJson)
	{
		if (pJson == null || pJson.trim().isEmpty())
		{
			return new Failures(0, null);
		}
		try
		{
			final JsonNode root = PartialFailures.MAPPER.readTree(pJson);
			final JsonNode array = root.isArray() ? root : root.path("items");
			final List<Item> items = new ArrayList<Item>();
			for (final JsonNode node : array)
			{
				items.add(new Item(node.path("id").asText(""), node.path("label").asText(""), node.path("reason").asText("")));
			}
			return new Failures(root.isArray() ? 0 : root.path("total").asInt(0), items);
		}
		catch (final Exception ex)
		{
			return new Failures(0, null);
		}
	}

	// ---------------------------------------------------------------------------------------------------- what is still missing

	/**
	 * The items of the latest job of a group that are still unresolved now: none unless the job ended with failures (a later run that succeeded replaced it
	 * as the latest, and one that failed entirely carries no items), and, when {@code pUnresolvedIds} is not null, only those whose id is in it - a landing
	 * table loaded since by another route, an object configured since, no longer counts.
	 */
	public static List<Item> stillMissing(final Last pLast, final Set<String> pUnresolvedIds)
	{
		final List<Item> out = new ArrayList<Item>();
		if (pLast == null || !pLast.partial())
		{
			return out;
		}
		for (final Item item : pLast.failures.items)
		{
			if (pUnresolvedIds == null || pUnresolvedIds.contains(item.id))
			{
				out.add(item);
			}
		}
		return out;
	}

	/** The ids of the ticked landing tables that are not loaded (what {@link #stillMissing} intersects a landing job with). */
	public static Set<String> unloadedTickedTables(final WizardSnapshot pSnapshot)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				if (table.selected && !"LOADED".equals(table.status))
				{
					out.add(table.id);
				}
			}
		}
		return out;
	}

	/** The names of the objects whose matching plan is accepted and not applied yet (what a configuration job's items are intersected with: a plan rejected since is no longer waiting). */
	public static Set<String> unappliedPlans(final WizardSnapshot pSnapshot)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final WizardSnapshot.MatchPlanInfo plan : pSnapshot.matchPlans)
		{
			if ("ACCEPTED".equals(plan.status))
			{
				out.add(plan.entityName);
			}
		}
		return out;
	}

	/**
	 * The landing tables the gate of the staging step may let through: the latest landing job ended with failures, at least one ticked table is loaded and
	 * EVERY ticked table that is not loaded is one of that job's failures. Anything else (a table never run, still waiting for its key) keeps the gate
	 * closed as before.
	 */
	public static boolean landingGapIsKnown(final WizardSnapshot pSnapshot, final Last pLandingLast)
	{
		final Set<String> unloaded = PartialFailures.unloadedTickedTables(pSnapshot);
		if (unloaded.isEmpty() || pLandingLast == null || !pLandingLast.partial())
		{
			return false;
		}
		boolean anyLoaded = false;
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				anyLoaded |= table.selected && "LOADED".equals(table.status);
			}
		}
		if (!anyLoaded)
		{
			return false;
		}
		final Set<String> failed = new LinkedHashSet<String>();
		for (final Item item : pLandingLast.failures.items)
		{
			failed.add(item.id);
		}
		return failed.containsAll(unloaded);
	}

	// ---------------------------------------------------------------------------------------------------- the words

	/** {@code "A, B"} - the labels, comma separated (the block that lists what is missing). */
	public static String names(final List<Item> pItems)
	{
		final StringBuilder out = new StringBuilder();
		for (final Item item : pItems)
		{
			out.append(out.length() == 0 ? "" : ", ").append(item.label);
		}
		return out.toString();
	}

	/** {@code "A and B"}, {@code "A, B and C"}, {@code "A, B, C, D and 3 more"} - the labels as they read inside a sentence. */
	public static String namesInSentence(final List<Item> pItems, final Locale pLocale)
	{
		final List<String> labels = new ArrayList<String>();
		for (final Item item : pItems)
		{
			labels.add(item.label);
		}
		if (labels.isEmpty())
		{
			return "";
		}
		if (labels.size() == 1)
		{
			return labels.get(0);
		}
		final String and = WizardMessages.get(pLocale, "wizard.partial.and");
		final List<String> shown = labels.size() > PartialFailures.NAMES_MAX ? labels.subList(0, PartialFailures.NAMES_MAX) : labels;
		final StringBuilder out = new StringBuilder();
		if (labels.size() > PartialFailures.NAMES_MAX)
		{
			for (final String label : shown)
			{
				out.append(out.length() == 0 ? "" : ", ").append(label);
			}
			return out + " " + and + " " + WizardMessages.get(pLocale, "wizard.partial.more", Integer.valueOf(labels.size() - PartialFailures.NAMES_MAX));
		}
		for (int i = 0; i < shown.size() - 1; i++)
		{
			out.append(i == 0 ? "" : ", ").append(shown.get(i));
		}
		return out + " " + and + " " + shown.get(shown.size() - 1);
	}

	/** "2 of 4 tables were not loaded: SALESFORCE.Account, SALESFORCE.Contact" (the total is left out when the job did not record it). */
	public static String landingTitle(final List<Item> pItems, final int pTotal, final Locale pLocale)
	{
		final int count = pItems.size();
		if (pTotal >= count && pTotal > 0)
		{
			return WizardMessages.plural(pLocale, count, "wizard.partial.landing.title.one", "wizard.partial.landing.title.other", Integer.valueOf(pTotal), PartialFailures.names(pItems));
		}
		return WizardMessages.plural(pLocale, count, "wizard.partial.landing.titleNoTotal.one", "wizard.partial.landing.titleNoTotal.other", PartialFailures.names(pItems));
	}

	/** "The staging will be built without SALESFORCE.Account and SALESFORCE.Contact." */
	public static String stagingWarning(final List<Item> pItems, final Locale pLocale)
	{
		return WizardMessages.get(pLocale, "wizard.partial.landing.warning", PartialFailures.namesInSentence(pItems, pLocale));
	}

	/** "2 objects were refused when configuring: Organisation, OrganisationAddress" */
	public static String configureTitle(final List<Item> pItems, final Locale pLocale)
	{
		return WizardMessages.plural(pLocale, pItems.size(), "wizard.partial.configure.title.one", "wizard.partial.configure.title.other", PartialFailures.names(pItems));
	}

	/** "2 objects failed in the last run: Organisation, OrganisationAddress" */
	public static String runTitle(final List<Item> pItems, final Locale pLocale)
	{
		return WizardMessages.plural(pLocale, pItems.size(), "wizard.partial.run.title.one", "wizard.partial.run.title.other", PartialFailures.names(pItems));
	}

	/** "Run matching will leave out Organisation and OrganisationAddress: they were refused when configuring." */
	public static String runWarning(final List<Item> pConfigureItems, final Locale pLocale)
	{
		return WizardMessages.get(pLocale, "wizard.partial.run.warning", PartialFailures.namesInSentence(pConfigureItems, pLocale));
	}

	// ---------------------------------------------------------------------------------------------------- the Summary

	/**
	 * The open points of the Summary for the partial failures nobody resolved yet, severity ERROR, each linking to its step: the landing tables of the last
	 * landing run that are still not loaded, the objects the last configuration refused that are still not applied, the objects the last matching run
	 * failed. They go away by themselves when a later run of the same kind succeeds (it becomes the latest job) or when the items are fixed another way.
	 * {@code pAlreadyReported} lists the objects the Summary already flags as "the last matching run failed" ({@code WizardSummaryData#failedRuns}): they are
	 * not repeated.
	 */
	public static List<WizardSummaryData.OpenPointInput> openPoints(final java.util.Map<String, Last> pLast, final WizardSnapshot pSnapshot,
		final List<String> pAlreadyReported, final Locale pLocale)
	{
		final List<WizardSummaryData.OpenPointInput> out = new ArrayList<WizardSummaryData.OpenPointInput>();
		if (pLast == null)
		{
			return out;
		}
		final List<Item> landing = PartialFailures.stillMissing(pLast.get(PartialFailures.LANDING), PartialFailures.unloadedTickedTables(pSnapshot));
		if (!landing.isEmpty())
		{
			out.add(new WizardSummaryData.OpenPointInput("ERROR", WizardMessages.plural(pLocale, landing.size(), "wizard.partial.open.landing.title.one",
				"wizard.partial.open.landing.title.other", PartialFailures.names(landing)), WizardMessages.get(pLocale, "wizard.partial.open.landing.detail"),
				WizardMessages.get(pLocale, "wizard.partial.open.landing.action"), WizardRules.STEP_LANDING, ""));
		}
		final List<Item> configure = PartialFailures.stillMissing(pLast.get(PartialFailures.CONFIGURE), PartialFailures.unappliedPlans(pSnapshot));
		if (!configure.isEmpty())
		{
			out.add(new WizardSummaryData.OpenPointInput("ERROR", WizardMessages.plural(pLocale, configure.size(), "wizard.partial.open.configure.title.one",
				"wizard.partial.open.configure.title.other", PartialFailures.names(configure)), WizardMessages.get(pLocale, "wizard.partial.open.configure.detail"),
				WizardMessages.get(pLocale, "wizard.step7.open.matching"), WizardRules.STEP_MATCHING, ""));
		}
		final List<Item> run = new ArrayList<Item>();
		for (final Item item : PartialFailures.stillMissing(pLast.get(PartialFailures.RUN), null))
		{
			if (pAlreadyReported == null || !pAlreadyReported.contains(item.id))
			{
				run.add(item);
			}
		}
		if (!run.isEmpty())
		{
			out.add(new WizardSummaryData.OpenPointInput("ERROR", WizardMessages.plural(pLocale, run.size(), "wizard.partial.open.run.title.one",
				"wizard.partial.open.run.title.other", PartialFailures.names(run)), WizardMessages.get(pLocale, "wizard.partial.open.run.detail"),
				WizardMessages.get(pLocale, "wizard.step7.open.matching"), WizardRules.STEP_MATCHING, ""));
		}
		return out;
	}

	/** An empty, unmodifiable list (a caller with nothing to show). */
	public static List<Item> none()
	{
		return Collections.<Item>emptyList();
	}
}
