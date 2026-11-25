package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot M16 (2026-10-18, {@code docs/MATCHING.md} &sect;68): the CANDIDATE COVERAGE of a plan, measured on the pivots of a simulation - for each exact key of the plan, how many records that share the
 * key's value with a pivot were NOT among the candidates the add-on evaluated for that pivot. It is a measurement handed to the language model (and shown on the plan card), never a correction: the
 * model rewrites the weights.
 * <p>
 * Pure (no EBX): {@code ebx.MatchPlanSimulationRunner} reads the records that share a key value with each pivot and the candidates the engine returned for it, and hands both here as plain ids.
 * A record counts once per key, however many pivots share it; a pivot that does not fill the key is not counted for it.
 */
public final class CandidateCoverage
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private CandidateCoverage()
	{
	}

	/** One pivot and one key it fills: the other records that carry the same value, with their source system. */
	public static final class Observation
	{
		public final String key;
		public final String pivotId;
		public final String pivotSystem;
		/** {id, sourceSystem} of every other record carrying the pivot's value of {@link #key}. */
		public final List<String[]> sharing;

		public Observation(final String pKey, final String pPivotId, final String pPivotSystem, final List<String[]> pSharing)
		{
			this.key = pKey;
			this.pivotId = pPivotId;
			this.pivotSystem = pPivotSystem;
			this.sharing = pSharing;
		}
	}

	/** The figures of one key. */
	public static final class KeyCoverage
	{
		public final String key;
		/** Pivots that fill the key and share its value with at least one other record. */
		public final int pivots;
		/** Records (distinct per pivot) that share the key's value with a pivot. */
		public final int sharing;
		/** Of those, the ones that were not among the candidates the engine evaluated for that pivot. */
		public final int missing;
		/** Source system -&gt; how many of the missing records belong to it. */
		public final Map<String, Integer> missingBySystem;
		/** Source system of the PIVOT -&gt; how many of the missing records were missed for a pivot of it. */
		public final Map<String, Integer> missingByPivotSystem;

		KeyCoverage(final String pKey, final int pPivots, final int pSharing, final int pMissing, final Map<String, Integer> pMissingBySystem, final Map<String, Integer> pMissingByPivotSystem)
		{
			this.key = pKey;
			this.pivots = pPivots;
			this.sharing = pSharing;
			this.missing = pMissing;
			this.missingBySystem = Collections.unmodifiableMap(pMissingBySystem);
			this.missingByPivotSystem = Collections.unmodifiableMap(pMissingByPivotSystem);
		}
	}

	/** The coverage of all the keys. */
	public static final class Result
	{
		public final int pivotCount;
		public final List<KeyCoverage> keys;

		Result(final int pPivotCount, final List<KeyCoverage> pKeys)
		{
			this.pivotCount = pPivotCount;
			this.keys = Collections.unmodifiableList(pKeys);
		}

		/** True when at least one key has a record that shares it with a pivot and was never a candidate. */
		public boolean hasGap()
		{
			for (final KeyCoverage key : this.keys)
			{
				if (key.missing > 0)
				{
					return true;
				}
			}
			return false;
		}

		public KeyCoverage key(final String pKey)
		{
			for (final KeyCoverage key : this.keys)
			{
				if (key.key.equals(pKey))
				{
					return key;
				}
			}
			return null;
		}

		/** The facts as the stored JSON: {@code {pivots, keys:[{key, pivots, sharing, missing, missingBySystem{}, missingByPivotSystem{}}]}}. */
		public ObjectNode toJson()
		{
			final ObjectNode out = MAPPER.createObjectNode();
			out.put("pivots", this.pivotCount);
			final ArrayNode array = out.putArray("keys");
			for (final KeyCoverage key : this.keys)
			{
				final ObjectNode entry = array.addObject();
				entry.put("key", key.key);
				entry.put("pivots", key.pivots);
				entry.put("sharing", key.sharing);
				entry.put("missing", key.missing);
				final ObjectNode bySystem = entry.putObject("missingBySystem");
				for (final Map.Entry<String, Integer> e : key.missingBySystem.entrySet())
				{
					bySystem.put(e.getKey(), e.getValue().intValue());
				}
				final ObjectNode byPivot = entry.putObject("missingByPivotSystem");
				for (final Map.Entry<String, Integer> e : key.missingByPivotSystem.entrySet())
				{
					byPivot.put(e.getKey(), e.getValue().intValue());
				}
			}
			return out;
		}

		/**
		 * The facts as numbered statements for the language model's next prompt and the plan card, English. {@code pCause} (may be null) is appended to every statement that reports a
		 * gap: why, from the weights ({@link com.on.ebx.factory.master.CandidateScoreShare}). A key with no gap gets one short statement so the model also reads what worked.
		 */
		public List<String> facts(final String pCause)
		{
			final List<String> out = new ArrayList<String>();
			for (final KeyCoverage key : this.keys)
			{
				if (key.sharing == 0)
				{
					continue;
				}
				final StringBuilder fact = new StringBuilder("simulation: ").append(this.pivotCount).append(" pivot(s); ");
				if (key.missing == 0)
				{
					fact.append("all ").append(key.sharing).append(" record(s) sharing ").append(key.key).append(" with a pivot were among the candidates the add-on evaluated.");
				}
				else
				{
					fact.append(key.missing).append(" of ").append(key.sharing).append(" record(s) sharing ").append(key.key).append(" with a pivot were never candidates");
					if (!key.missingBySystem.isEmpty())
					{
						fact.append(" (").append(CandidateCoverage.joinCounts(key.missingBySystem)).append(")");
					}
					if (!key.missingByPivotSystem.isEmpty())
					{
						fact.append(", for pivots of ").append(CandidateCoverage.joinSystems(key.missingByPivotSystem));
					}
					fact.append('.');
					if (pCause != null && !pCause.trim().isEmpty())
					{
						fact.append(" Cause: ").append(pCause.trim());
					}
				}
				out.add(fact.toString());
			}
			return out;
		}
	}

	/**
	 * @param pObservations one per (pivot, key it fills)
	 * @param pCandidatesByPivot pivot id -&gt; the ids of every record the engine returned as a candidate for it (before any deduplication of symmetric pairs)
	 * @param pPivotCount how many pivots the simulation drew
	 */
	public static Result measure(final List<Observation> pObservations, final Map<String, Set<String>> pCandidatesByPivot, final int pPivotCount)
	{
		final Map<String, int[]> perKey = new LinkedHashMap<String, int[]>(); // key -> {pivots, sharing, missing}
		final Map<String, Map<String, Integer>> missingBySystem = new LinkedHashMap<String, Map<String, Integer>>();
		final Map<String, Map<String, Integer>> missingByPivotSystem = new LinkedHashMap<String, Map<String, Integer>>();
		final Map<String, Set<String>> counted = new LinkedHashMap<String, Set<String>>();
		for (final Observation observation : pObservations)
		{
			if (observation.sharing.isEmpty())
			{
				continue;
			}
			int[] counters = perKey.get(observation.key);
			if (counters == null)
			{
				counters = new int[3];
				perKey.put(observation.key, counters);
				missingBySystem.put(observation.key, new LinkedHashMap<String, Integer>());
				missingByPivotSystem.put(observation.key, new LinkedHashMap<String, Integer>());
				counted.put(observation.key, new LinkedHashSet<String>());
			}
			counters[0]++;
			final Set<String> candidates = pCandidatesByPivot.containsKey(observation.pivotId) ? pCandidatesByPivot.get(observation.pivotId) : Collections.<String>emptySet();
			for (final String[] other : observation.sharing)
			{
				// one record counts once per key and pivot: the pair (pivot, record)
				if (!counted.get(observation.key).add(observation.pivotId + ">" + other[0]))
				{
					continue;
				}
				counters[1]++;
				if (!candidates.contains(other[0]))
				{
					counters[2]++;
					CandidateCoverage.increment(missingBySystem.get(observation.key), other[1] == null || other[1].isEmpty() ? "?" : other[1]);
					CandidateCoverage.increment(missingByPivotSystem.get(observation.key), observation.pivotSystem == null || observation.pivotSystem.isEmpty() ? "?" : observation.pivotSystem);
				}
			}
		}
		final List<KeyCoverage> keys = new ArrayList<KeyCoverage>();
		for (final Map.Entry<String, int[]> entry : perKey.entrySet())
		{
			keys.add(new KeyCoverage(entry.getKey(), entry.getValue()[0], entry.getValue()[1], entry.getValue()[2], missingBySystem.get(entry.getKey()), missingByPivotSystem.get(entry.getKey())));
		}
		return new Result(pPivotCount, keys);
	}

	/** The key of {@code MatchPlan.requirements} that carries the statements of the last simulation. */
	public static final String FACTS_KEY = "simulationFacts";

	/** {@code pRequirementsJson} with its {@value #FACTS_KEY} replaced by {@code pFacts} (removed when empty); {@code null} when the document cannot be read - the caller then stores nothing. */
	public static String withFacts(final String pRequirementsJson, final List<String> pFacts)
	{
		if (pRequirementsJson == null || pRequirementsJson.trim().isEmpty())
		{
			return null;
		}
		try
		{
			final com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(pRequirementsJson);
			if (!node.isObject())
			{
				return null;
			}
			final ObjectNode object = (ObjectNode) node;
			if (pFacts == null || pFacts.isEmpty())
			{
				object.remove(FACTS_KEY);
			}
			else
			{
				final ArrayNode array = object.putArray(FACTS_KEY);
				for (final String fact : pFacts)
				{
					array.add(fact);
				}
			}
			return object.toString();
		}
		catch (final java.io.IOException ex)
		{
			return null;
		}
	}

	/** The statements stored by {@link #withFacts}; empty when there are none or the document cannot be read. */
	public static List<String> factsOf(final String pRequirementsJson)
	{
		final List<String> out = new ArrayList<String>();
		if (pRequirementsJson == null || pRequirementsJson.trim().isEmpty())
		{
			return out;
		}
		try
		{
			for (final com.fasterxml.jackson.databind.JsonNode fact : MAPPER.readTree(pRequirementsJson).path(FACTS_KEY))
			{
				if (fact.isTextual() && !fact.asText().trim().isEmpty())
				{
					out.add(fact.asText().trim());
				}
			}
		}
		catch (final java.io.IOException ex)
		{
			// nothing stored that can be read
		}
		return out;
	}

	private static void increment(final Map<String, Integer> pMap, final String pKey)
	{
		final Integer count = pMap.get(pKey);
		pMap.put(pKey, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
	}

	private static String joinSystems(final Map<String, Integer> pCounts)
	{
		final StringBuilder out = new StringBuilder();
		for (final Map.Entry<String, Integer> entry : pCounts.entrySet())
		{
			out.append(out.length() == 0 ? "" : ", ").append(entry.getKey()).append(' ').append(entry.getValue());
		}
		return out.toString();
	}

	private static String joinCounts(final Map<String, Integer> pCounts)
	{
		final StringBuilder out = new StringBuilder();
		for (final Map.Entry<String, Integer> entry : pCounts.entrySet())
		{
			out.append(out.length() == 0 ? "" : ", ").append(entry.getValue()).append(" in ").append(entry.getKey());
		}
		return out.toString();
	}
}
