package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.matching.KeyFacts;

/**
 * Lot M17 (2026-10-18, {@code docs/MATCHING.md} &sect;69): how many source systems feed an entity and, when ONE source system only feeds it, whether any two rows of that source share
 * an exact key - measured facts, told to the language model before its first proposal. Pure (no EBX class).
 * <p>
 * Seen live (12th end-to-end run, 2026-10-18, project {@code E2EMASTER20261018115006}): after the person plan and the ticket plan, the model spent the rest of its token budget proposing
 * one-node plans with no clustering field for three reference tables fed by a single source ({@code VisiteMagasin}, {@code PaysClient}, {@code SuiviClient}), until the run ended on its
 * budget. A table fed by ONE source system whose rows carry that source's own key cannot hold cross-system duplicates, and a duplicate inside the source is a fact to measure.
 * <p>
 * What is measured, and where it comes from (nothing new is read: the numbers are the ones the pass already has):
 * <ul>
 * <li>the source systems and their row counts: the key facts (a count of the master table by {@code sourceSystem}), else the per-system row counts of the entity; a sample of a larger
 * table gives lower bounds and states nothing;</li>
 * <li>for one source system, every pair of rows is a pair of rows of that source, so the number of pairs of rows sharing an exact key ({@link KeyFacts.KeyStat#pairsSharing}) IS the
 * intra-source sharing; the keys the key facts leave out because all their filled values are unique are listed too ({@link KeyFactsSelection#uniqueKeys}): they share nothing.</li>
 * </ul>
 * {@link #noPlanNeeded()} is true when one source feeds the entity, at least one exact key was measured, and none is shared by two rows. It is a FACT told to the model with the
 * consequence written out; the model still decides ({@code skip_entity}, or a plan - in which case the stored rationale says that the facts showed one source and no shared key).
 */
public final class SingleSourceFacts
{
	/** Source system names, in the order of the facts; empty when not known. */
	public final List<String> systems;
	/** Rows of the entity (the sum over the systems when the facts give them, else the entity's own count). */
	public final long rows;
	/** The only source system and its row count when exactly one feeds the entity, else null. */
	public final String singleSystem;
	public final long singleSystemRows;
	/** Keys shared by two or more rows of the single source: {@code name -> pairs}. Empty for several sources or when nothing is shared. */
	public final Map<String, Long> sharedKeys;
	/** Keys measured with no shared value (key facts and unique keys). */
	public final List<String> unsharedKeys;
	/** True when the intra-source sharing is measured on the complete table (not a sample) and at least one key was measured. */
	public final boolean sharingMeasured;

	private SingleSourceFacts(final List<String> pSystems, final long pRows, final String pSingleSystem, final long pSingleRows, final Map<String, Long> pShared,
		final List<String> pUnshared, final boolean pMeasured)
	{
		this.systems = pSystems;
		this.rows = pRows;
		this.singleSystem = pSingleSystem;
		this.singleSystemRows = pSingleRows;
		this.sharedKeys = pShared;
		this.unsharedKeys = pUnshared;
		this.sharingMeasured = pMeasured;
	}

	/** The facts of {@code pEntity}, or null when the source systems that feed it are not known. */
	public static SingleSourceFacts of(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		final Map<String, Long> bySystem = new LinkedHashMap<String, Long>();
		final KeyFacts facts = pEntity.keyFacts;
		final boolean completeFacts = facts != null && !facts.sampled();
		if (completeFacts)
		{
			for (final Map.Entry<String, Integer> system : facts.recordsBySystem().entrySet())
			{
				if (system.getValue().intValue() > 0)
				{
					bySystem.put(system.getKey(), Long.valueOf(system.getValue().longValue()));
				}
			}
		}
		else if (facts == null && pEntity.rowCountBySystem != null)
		{
			for (final Map.Entry<String, Long> system : pEntity.rowCountBySystem.entrySet())
			{
				if (system.getValue() != null && system.getValue().longValue() > 0)
				{
					bySystem.put(system.getKey(), system.getValue());
				}
			}
		}
		if (bySystem.isEmpty())
		{
			return null;
		}
		// a record whose source system is empty belongs to a source that has no name: the count of sources is then unknown
		if (bySystem.containsKey(""))
		{
			return null;
		}
		long rows = 0;
		for (final Long count : bySystem.values())
		{
			rows += count.longValue();
		}
		final List<String> systems = new ArrayList<String>(bySystem.keySet());
		if (systems.size() != 1)
		{
			return new SingleSourceFacts(Collections.unmodifiableList(systems), rows, null, 0, Collections.<String, Long>emptyMap(), Collections.<String>emptyList(), false);
		}
		final Map<String, Long> shared = new LinkedHashMap<String, Long>();
		final List<String> unshared = new ArrayList<String>();
		if (completeFacts)
		{
			for (final KeyFacts.KeyStat stat : facts.keys())
			{
				if (stat.pairsSharing > 0)
				{
					shared.put(stat.name, Long.valueOf(stat.pairsSharing));
				}
				else
				{
					unshared.add(stat.name);
				}
			}
		}
		for (final String unique : KeyFactsSelection.uniqueKeys(pEntity))
		{
			if (!unshared.contains(unique) && !shared.containsKey(unique))
			{
				unshared.add(unique);
			}
		}
		// key facts of a larger table (a sample) are lower bounds: they state that a key IS shared, never that none is
		final boolean sampleOnly = facts != null && facts.sampled();
		final boolean measured = !sampleOnly && (!shared.isEmpty() || !unshared.isEmpty());
		return new SingleSourceFacts(Collections.unmodifiableList(systems), rows, systems.get(0), bySystem.get(systems.get(0)).longValue(), Collections.unmodifiableMap(shared),
			Collections.unmodifiableList(unshared), measured);
	}

	/** True when the entity is fed by one source system, an exact key was measured, and no two rows of that source share any: the facts say no duplicate can exist. */
	public boolean noPlanNeeded()
	{
		return this.singleSystem != null && this.sharingMeasured && this.sharedKeys.isEmpty();
	}

	/** {@code STORES, 48 rows}: the single source and its row count; empty for several sources. */
	public String sourceText()
	{
		return this.singleSystem == null ? "" : this.singleSystem + ", " + this.singleSystemRows + (this.singleSystemRows == 1 ? " row" : " rows");
	}

	/** {@code 3 (ECOM 160, LOYALTY 140, STORES 234; 534 rows in all)}, or null for a single source. */
	private String severalText(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		final KeyFacts facts = pEntity.keyFacts;
		final StringBuilder out = new StringBuilder();
		out.append(this.systems.size()).append(" (");
		for (int i = 0; i < this.systems.size(); i++)
		{
			final String system = this.systems.get(i);
			long count = 0;
			if (facts != null && !facts.sampled() && facts.recordsBySystem().get(system) != null)
			{
				count = facts.recordsBySystem().get(system).longValue();
			}
			else if (pEntity.rowCountBySystem != null && pEntity.rowCountBySystem.get(system) != null)
			{
				count = pEntity.rowCountBySystem.get(system).longValue();
			}
			out.append(i == 0 ? "" : ", ").append(system).append(' ').append(count);
		}
		return out.append("; ").append(this.rows).append(" rows in all)").toString();
	}

	/**
	 * The line the checklist opens with, English: how many source systems feed the entity and the rows - and, for one source, the intra-source sharing of its exact keys with the
	 * consequence written out. Numbers only, never a value.
	 */
	public String checklistLine(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		if (this.singleSystem == null)
		{
			return "    * Source systems feeding " + pEntity.name + ": " + this.severalText(pEntity) + ".\n";
		}
		final StringBuilder out = new StringBuilder("    * " + pEntity.name + " is fed by one source system only (" + this.sourceText() + ")");
		if (!this.sharingMeasured)
		{
			return out.append("; no exact key of this entity could be measured, so nothing is concluded about duplicates inside that source.\n").toString();
		}
		if (this.sharedKeys.isEmpty())
		{
			out.append("; no key is shared by two rows of that source (").append(String.join(", ", this.unsharedKeys)).append(": 0 pair(s) of rows share it) -> no duplicate can exist: ")
				.append("call skip_entity with that reason instead of proposing a plan; propose a plan only if a key IS shared within the source (then say which).\n");
			return out.toString();
		}
		out.append("; keys shared by two or more rows of that source: ");
		boolean first = true;
		for (final Map.Entry<String, Long> shared : this.sharedKeys.entrySet())
		{
			out.append(first ? "" : ", ").append(shared.getKey()).append(" (").append(shared.getValue()).append(" pair(s) of rows)");
			first = false;
		}
		out.append(" -> duplicates inside that source exist on these keys; a plan has something to find there.\n");
		return out.toString();
	}

	/** The sentence added to the stored rationale of a plan the model proposed although the facts showed one source and no shared key. */
	public String rationaleSentence()
	{
		return "The facts showed one source and no shared key (" + this.sourceText() + ").";
	}
}
