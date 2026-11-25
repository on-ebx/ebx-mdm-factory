package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.factory.project.ProjectDeletionPlan.ConnectionFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.Item;
import com.on.ebx.factory.project.ProjectDeletionPlan.MappingRef;

/**
 * Orphan-connections lot (2026-10-17): the SELECTION rules for removing the sample-file connections (and their catalogue sources) that no project uses and
 * that carry no creation trace - the ones left by projects deleted before the {@code connections} option of the project deletion existed. Pure (no EBX
 * type), unit-tested ({@code OrphanConnectionsTest}); {@code ebx.OrphanConnectionCleanup} gathers the live evidence and executes.
 * <p>
 * There is NO second rule set: the decision about a connection and its catalogue source is {@link ProjectDeletionPlan}'s own
 * ({@code catalogSourceItem} / {@code connectionItem}), fed with a {@link ConnectionFacts} that treats the connection as the factory's own
 * ({@code connectionCreatedByFactory}, because nobody else is left to claim it). What this class adds is what only an orphan needs:
 * <ul>
 * <li>a connection is an orphan only when NOTHING of a live project references it: no {@code ProjectSource.connector}, no
 * {@code ProjectSource.catalogSource} whose catalogue source names it, no {@code SOURCE_CONNECTION} / {@code SOURCES_RUN} step of an existing project, no
 * distribution target - {@link #referencedIds};</li>
 * <li>only {@code sample-file} connections are ever candidates (the caller never offers another type), and the seed connection of ebx-connectors
 * ({@link #SEED_CONNECTION}) is never deleted;</li>
 * <li>a connection named by several catalogue sources is kept (which one is "its" source is not decidable);</li>
 * <li>the {@code confirm} guard of the deletion - {@link #confirmRefusal}.</li>
 * </ul>
 */
public final class OrphanConnections
{
	/** The connection ebx-connectors seeds itself (base URL {@code local://sample-file}): shared by everybody, never listed as removable. */
	public static final String SEED_CONNECTION = "SampleFile";
	/** The only connection type ever considered. */
	public static final String SAMPLE_FILE = "sample-file";
	/** Ids accepted by one deletion call. */
	public static final int MAX_IDS = 500;

	private OrphanConnections()
	{
	}

	// -------------------------------------------------------------------------------------------------- references

	/**
	 * Every connection id a live project (or a distribution target) still references.
	 *
	 * @param pConnectorRefs {@code ProjectSource.connector} of every project source, {@code DistributionTarget.connector}, anything that names a connection directly
	 * @param pCatalogSourceRefs {@code ProjectSource.catalogSource} of every project source
	 * @param pSourceConnector catalogue source id to the connection it names now
	 * @param pLiveSteps the {@code SOURCE_CONNECTION} / {@code SOURCES_RUN} steps of the projects that still exist (never those of a deleted one)
	 */
	public static Set<String> referencedIds(final Collection<String> pConnectorRefs, final Collection<String> pCatalogSourceRefs, final Map<String, String> pSourceConnector,
		final List<ConnectionProvenance.StepRow> pLiveSteps)
	{
		final Set<String> out = new LinkedHashSet<String>();
		OrphanConnections.addAll(out, pConnectorRefs);
		for (final String source : pCatalogSourceRefs)
		{
			final String connector = source == null ? null : pSourceConnector.get(source);
			OrphanConnections.add(out, connector);
		}
		for (final ConnectionProvenance.Evidence evidence : ConnectionProvenance.read(pLiveSteps).values())
		{
			OrphanConnections.add(out, evidence.connectionId);
			OrphanConnections.add(out, evidence.catalogSourceId.isEmpty() ? null : pSourceConnector.get(evidence.catalogSourceId));
		}
		// a step whose detail cannot be read proves nothing about creation, but it still names the connection in its scope: stay on the safe side
		for (final ConnectionProvenance.StepRow step : pLiveSteps)
		{
			if (ConnectionProvenance.STEP_CONNECTION.equals(step.step) || ConnectionProvenance.STEP_SOURCES_RUN.equals(step.step))
			{
				OrphanConnections.add(out, step.scope);
			}
		}
		return out;
	}

	private static void addAll(final Set<String> pOut, final Collection<String> pValues)
	{
		for (final String value : pValues)
		{
			OrphanConnections.add(pOut, value);
		}
	}

	private static void add(final Set<String> pOut, final String pValue)
	{
		if (pValue != null && !pValue.trim().isEmpty())
		{
			pOut.add(pValue.trim());
		}
	}

	// --------------------------------------------------------------------------------------------------- filter

	/**
	 * The optional {@code namePattern} of the listing: a plain substring (never a regex), case-insensitive, tested on every name the entry has (connection id,
	 * label, catalogue source ids). Blank or null matches everything.
	 */
	public static boolean nameMatches(final String pPattern, final String... pNames)
	{
		if (pPattern == null || pPattern.trim().isEmpty())
		{
			return true;
		}
		final String needle = pPattern.trim().toLowerCase(Locale.ROOT);
		for (final String name : pNames)
		{
			if (name != null && name.toLowerCase(Locale.ROOT).contains(needle))
			{
				return true;
			}
		}
		return false;
	}

	// -------------------------------------------------------------------------------------------------- decision

	/** What the plan's rules say about one orphan candidate. */
	public static final class Verdict
	{
		public final boolean eligible;
		/** Null when eligible. */
		public final String reason;
		/** Everything that still uses the connection or its source and is not removed with them, one sentence each (also given for an eligible one: empty). */
		public final List<String> blockers;

		Verdict(final boolean pEligible, final String pReason, final List<String> pBlockers)
		{
			this.eligible = pEligible;
			this.reason = pReason;
			this.blockers = pBlockers;
		}
	}

	/**
	 * Prepares the facts of a connection nothing references so that the project deletion's own rules judge it: the connection counts as created by the
	 * factory, the single catalogue source naming it (if any) too. With several catalogue sources none is "its" source: they stay in
	 * {@link ConnectionFacts#otherSourceIds}, which keeps the connection.
	 */
	public static void claim(final ConnectionFacts pFacts, final List<String> pSourceIdsNaming)
	{
		pFacts.connectionStepSeen = true;
		pFacts.connectionCreatedByFactory = true;
		if (pSourceIdsNaming.size() == 1)
		{
			pFacts.catalogSourceId = pSourceIdsNaming.get(0);
			pFacts.catalogSourceCreatedByFactory = true;
		}
	}

	/** The verdict on facts built by {@code claim} and filled by the gathering. The connection must exist and be a sample-file one (the caller has checked: a listing never offers another). */
	public static Verdict decide(final ConnectionFacts pFacts)
	{
		final List<String> blockers = OrphanConnections.blockersOf(pFacts);
		if (OrphanConnections.SEED_CONNECTION.equals(pFacts.connectionId))
		{
			return new Verdict(false, "this is the sample-file connection ebx-connectors seeds itself: shared, never deleted.", blockers);
		}
		if (!pFacts.connectionExists)
		{
			return new Verdict(false, "the connection no longer exists.", blockers);
		}
		if (!OrphanConnections.SAMPLE_FILE.equals(pFacts.connectionType))
		{
			return new Verdict(false, ProjectDeletionPlan.ConnectionReasons.connectionNotSampleFile(pFacts.connectionType), blockers);
		}
		final List<Item> items = new ArrayList<Item>();
		if (!pFacts.catalogSourceId.isEmpty())
		{
			final Item source = ProjectDeletionPlan.catalogSourceItem(pFacts, false);
			if (!source.eligible)
			{
				return new Verdict(false, source.reason, blockers);
			}
			items.add(source);
		}
		final Item connection = ProjectDeletionPlan.connectionItem(pFacts, false, items);
		return connection.eligible ? new Verdict(true, null, blockers) : new Verdict(false, connection.reason, blockers);
	}

	private static List<String> blockersOf(final ConnectionFacts pFacts)
	{
		final List<String> out = new ArrayList<String>();
		for (final MappingRef mapping : pFacts.otherMappings)
		{
			out.add("mapping " + mapping.id + (mapping.label.isEmpty() ? "" : " (" + mapping.label + ")"));
		}
		out.addAll(pFacts.connectionBlockers);
		out.addAll(pFacts.catalogBlockers);
		if (!pFacts.otherSourceIds.isEmpty())
		{
			out.add("catalogue source(s) " + String.join(", ", pFacts.otherSourceIds) + " name this connection");
		}
		return out;
	}

	// ----------------------------------------------------------------------------------------------- the request

	/** The ids of a deletion body {@code {"ids":[...],"confirm":"n"}}, deduplicated, in order; null with {@link #bodyProblem} saying why when the body is unusable. */
	public static List<String> idsOf(final JsonNode pBody)
	{
		if (OrphanConnections.bodyProblem(pBody) != null)
		{
			return null;
		}
		final Set<String> ids = new LinkedHashSet<String>();
		for (final JsonNode one : pBody.path("ids"))
		{
			ids.add(one.asText().trim());
		}
		return new ArrayList<String>(ids);
	}

	/** Why a deletion body is unusable, null when it is fine. The {@code confirm} value is not judged here: see {@link #confirmRefusal}. */
	public static String bodyProblem(final JsonNode pBody)
	{
		if (pBody == null || !pBody.isObject())
		{
			return "The body must be a JSON object {\"ids\":[...],\"confirm\":\"<count>\"}.";
		}
		final JsonNode ids = pBody.path("ids");
		if (!ids.isArray() || ids.size() == 0)
		{
			return "\"ids\" must be a non-empty array of connection ids.";
		}
		if (ids.size() > OrphanConnections.MAX_IDS)
		{
			return "At most " + OrphanConnections.MAX_IDS + " ids per call.";
		}
		for (final JsonNode one : ids)
		{
			if (!one.isTextual() || one.asText().trim().isEmpty() || one.asText().length() > 200)
			{
				return "Every id must be a non-blank string of at most 200 characters.";
			}
		}
		return null;
	}

	/**
	 * The confirm guard: {@code confirm} must be exactly the count of the ids that are, now, eligible - decimal digits, no sign, no spaces inside. Null when it
	 * matches; the refusal sentence otherwise (nothing is deleted then).
	 */
	public static String confirmRefusal(final String pConfirm, final int pEligibleCount)
	{
		final String confirm = pConfirm == null ? "" : pConfirm.trim();
		if (!confirm.matches("[0-9]{1,6}"))
		{
			return "confirm must be the number of connections to delete (" + pEligibleCount + " of the ids sent are eligible now); nothing was deleted.";
		}
		if (Integer.parseInt(confirm) != pEligibleCount)
		{
			return "confirm says " + confirm + " but " + pEligibleCount + " of the ids sent are eligible now; nothing was deleted.";
		}
		return null;
	}
}
