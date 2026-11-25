package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot M3 (2026-10-17, {@code docs/MATCHING.md} &sect;56): what the DATA says about the exact keys of one entity, as plain counts - the measured facts the
 * language-model pass is given (and the plan stores) so that a plan can be judged on numbers, never on a key's name.
 * <p>
 * <b>Input</b>: one {@link Row} per record of the master table (its source system and the value of each candidate key, {@code null} when empty), and one
 * {@link KeySpec} per candidate key. Pure Java, no EBX class: {@code ebx.KeyFactsReader} reads the rows, {@code master.KeyFactsSelection} chooses the keys, this class
 * counts. Counting is linear in the number of rows (grouping by value), never a pairwise scan.
 * <p>
 * <b>What is counted</b> (all of it about records, never about a business meaning):
 * <ul>
 * <li>per key and per source system: how many records fill the key, how many carry a value that at least one OTHER record also carries ("shared");</li>
 * <li>per key: how many pairs of records share the exact value ({@link KeyStat#pairsSharing});</li>
 * <li>per ordered pair of keys (K, J) whose bases differ ({@link KeySpec#base}: a key derived from a field and that field are one base): among the pairs of
 * records that share the same K, how many ALSO share J ({@code agree}), how many have J filled on both sides with DIFFERENT values ({@code conflict}) and how
 * many have J missing on a side ({@code unknown}). {@link Agreement#rate()} = agree / (agree + conflict): "when two records carry the same K, how often do they
 * carry the same J". Zero conflicts over enough pairs is what justifies an exact-K-and-J path ending in a confirmed match;</li>
 * <li>the reach of the keys: for each source system, the pattern of filled keys (a bit mask per record), from which {@link #recordsReachedBy} computes, for ANY
 * subset of keys, how many records of each system carry at least one of them - a record that fills none of the keys a candidate filter is made of can never be
 * proposed as a duplicate.</li>
 * </ul>
 * A key holding SEVERAL tokens ({@link KeySpec#multiToken}, a derived child-keys attribute) is split on white space: two records share it when they share a
 * token; a pair sharing several tokens is then counted once per token ({@link KeyStat#approximate}).
 * <p>
 * Stored with the plan ({@link #toJson}/{@link #fromJson}) as {@code requirements.keyFacts}, so the plan card computes its findings from the stored plan alone.
 */
public final class KeyFacts
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** At least this share of the pairs sharing K must also share J (judged over the pairs where J is filled on both sides) for the pair (K, J) to be called safe. */
	public static final double MIN_AGREEMENT = 0.99;
	/** ... and no more than this many pairs may carry two different J values. */
	public static final int MAX_CONFLICTS = 0;
	/** ... over at least this many pairs where J is filled on both sides: fewer is an anecdote. */
	public static final int MIN_COMPARABLE_PAIRS = 10;
	/** A source system whose records fill none of the keys of a candidate filter for at least this share is "mostly unreachable" for that filter. */
	public static final double UNREACHABLE_SHARE = 0.25;
	/** ... judged only for a system with at least this many records. */
	public static final int MIN_SYSTEM_RECORDS = 20;
	/**
	 * Lot M10a (2026-10-18, {@code docs/MATCHING.md} &sect;64): a key is SELECTIVE when its records share, on average, at most this many pairs each ({@code pairsSharing / filled}).
	 * Above it the key groups records (a first name: 5,660 pairs for 534 filled records; a postal code) instead of identifying them. The name key of the B2C scenario (694 pairs
	 * for 534 records: true duplicates plus namesakes) passes; its first name does not. One constant for the Factory's fallback plan and for the path rule.
	 */
	public static final int MAX_PAIRS_PER_FILLED_RECORD = 2;
	/**
	 * Lot M10a: a key is PREDICTIVE when, among the pairs of records that carry the same value of it, at least this share carry the same value of another SELECTIVE key of another
	 * base (judged over the pairs where that key is filled on both sides, at least {@link #MIN_COMPARABLE_PAIRS} of them). Looser than {@link #MIN_AGREEMENT}: a SUSPECTED path only
	 * has to be worth a steward's time, where a MATCH has to be certain. The e-mail key and the phone key of the B2C scenario agree 100%; the address keys 94.5% (6 namesakes at one
	 * address); the name key 79-81% (129 namesake pairs of 694) - which is why it is not predictive.
	 */
	public static final double MIN_PREDICTIVE_RATE = 0.90;
	/** At most this many keys are measured (the bit mask and the pairwise table stay small). */
	public static final int MAX_KEYS = 8;
	/** Above this many records the facts are computed on a prefix: {@link #sampled()} is true and no rule is relaxed on them. */
	public static final int MAX_ROWS = 200000;

	/** One candidate key: its attribute name and the base it is derived from ({@code base}, equal to the name when it is not a derived key). */
	public static final class KeySpec
	{
		public final String name;
		public final String base;
		public final boolean multiToken;

		public KeySpec(final String pName, final String pBase, final boolean pMultiToken)
		{
			this.name = pName;
			this.base = pBase == null || pBase.isEmpty() ? pName : pBase;
			this.multiToken = pMultiToken;
		}
	}

	/** One record: its source system and the value of each key, in the order of the {@link KeySpec} list ({@code null} or empty = missing). */
	public static final class Row
	{
		public final String system;
		public final String[] values;

		public Row(final String pSystem, final String[] pValues)
		{
			this.system = pSystem == null ? "" : pSystem;
			this.values = pValues;
		}
	}

	/** What one key looks like on the data. */
	public static final class KeyStat
	{
		public final String name;
		public final String base;
		public final boolean multiToken;
		/** Records filling the key, per source system. */
		public final Map<String, Integer> filledBySystem;
		/** Records whose value (or one of whose tokens) is carried by at least one other record, per source system. */
		public final Map<String, Integer> sharedBySystem;
		/** Distinct values (tokens) among the filled records. */
		public final int distinct;
		/** Pairs of records sharing the exact value; a pair sharing several tokens counts once per token when {@link #approximate}. */
		public final long pairsSharing;
		public final boolean approximate;

		KeyStat(final String pName, final String pBase, final boolean pMultiToken, final Map<String, Integer> pFilled, final Map<String, Integer> pShared, final int pDistinct,
			final long pPairsSharing, final boolean pApproximate)
		{
			this.name = pName;
			this.base = pBase;
			this.multiToken = pMultiToken;
			this.filledBySystem = pFilled;
			this.sharedBySystem = pShared;
			this.distinct = pDistinct;
			this.pairsSharing = pPairsSharing;
			this.approximate = pApproximate;
		}

		public int filledTotal()
		{
			return KeyFacts.sum(this.filledBySystem);
		}
	}

	/** Among the pairs of records sharing {@link #key}, what the other key {@link #other} says. */
	public static final class Agreement
	{
		public final String key;
		public final String other;
		/** Pairs of records sharing the exact value of {@link #key}. */
		public final long pairs;
		/** ... that also share {@link #other}. */
		public final long agree;
		/** ... with {@link #other} filled on both sides and different. */
		public final long conflict;
		/** ... with {@link #other} missing on at least one side. */
		public final long unknown;

		Agreement(final String pKey, final String pOther, final long pPairs, final long pAgree, final long pConflict, final long pUnknown)
		{
			this.key = pKey;
			this.other = pOther;
			this.pairs = pPairs;
			this.agree = pAgree;
			this.conflict = pConflict;
			this.unknown = pUnknown;
		}

		/** Pairs where {@link #other} is filled on both sides. */
		public long comparable()
		{
			return this.agree + this.conflict;
		}

		/** agree / comparable, or {@code -1} when no pair is comparable. */
		public double rate()
		{
			final long comparable = this.comparable();
			return comparable == 0 ? -1d : (double) this.agree / (double) comparable;
		}

		/** Safe: enough comparable pairs, an agreement rate of at least {@link KeyFacts#MIN_AGREEMENT} and at most {@link KeyFacts#MAX_CONFLICTS} pairs in disagreement. */
		public boolean safe()
		{
			return this.comparable() >= KeyFacts.MIN_COMPARABLE_PAIRS && this.rate() >= KeyFacts.MIN_AGREEMENT && this.conflict <= KeyFacts.MAX_CONFLICTS;
		}
	}

	private final int rows;
	private final boolean sampled;
	private final Map<String, Integer> recordsBySystem;
	private final List<KeyStat> keys;
	private final List<Agreement> agreements;
	/** per system: bit mask of the filled keys (bit i = key i) to the number of records with that mask. */
	private final Map<String, Map<Integer, Integer>> masksBySystem;

	private KeyFacts(final int pRows, final boolean pSampled, final Map<String, Integer> pRecordsBySystem, final List<KeyStat> pKeys, final List<Agreement> pAgreements,
		final Map<String, Map<Integer, Integer>> pMasksBySystem)
	{
		this.rows = pRows;
		this.sampled = pSampled;
		this.recordsBySystem = pRecordsBySystem;
		this.keys = pKeys;
		this.agreements = pAgreements;
		this.masksBySystem = pMasksBySystem;
	}

	// ----------------------------------------------------------------------------------------------------------------------------------- computing

	/**
	 * Counts the facts of {@code pRows} for {@code pKeys} (at most {@link #MAX_KEYS}, the extra ones are ignored).
	 *
	 * @param pSampled true when {@code pRows} is only a prefix of the table (the facts are then lower bounds and must not relax any rule)
	 */
	public static KeyFacts compute(final List<Row> pRows, final List<KeySpec> pKeys, final boolean pSampled)
	{
		final List<KeySpec> specs = new ArrayList<KeySpec>(pKeys.size() > KeyFacts.MAX_KEYS ? pKeys.subList(0, KeyFacts.MAX_KEYS) : pKeys);
		final int n = pRows.size();
		final int k = specs.size();
		final Map<String, Integer> recordsBySystem = new TreeMap<String, Integer>();
		final Map<String, Map<Integer, Integer>> masks = new TreeMap<String, Map<Integer, Integer>>();
		// tokens[key][record] = the tokens of that record for that key (null = missing)
		final String[][][] tokens = new String[k][n][];
		for (int r = 0; r < n; r++)
		{
			final Row row = pRows.get(r);
			KeyFacts.increment(recordsBySystem, row.system);
			int mask = 0;
			for (int i = 0; i < k; i++)
			{
				final String value = i < row.values.length ? row.values[i] : null;
				final String[] t = KeyFacts.tokensOf(value, specs.get(i).multiToken);
				tokens[i][r] = t;
				if (t != null)
				{
					mask |= 1 << i;
				}
			}
			final Map<Integer, Integer> bySystem = masks.get(row.system);
			if (bySystem == null)
			{
				masks.put(row.system, new TreeMap<Integer, Integer>());
			}
			KeyFacts.incrementInt(masks.get(row.system), mask);
		}

		final List<KeyStat> stats = new ArrayList<KeyStat>();
		final List<Agreement> agreements = new ArrayList<Agreement>();
		for (int i = 0; i < k; i++)
		{
			final KeySpec spec = specs.get(i);
			final Map<String, List<Integer>> groups = new LinkedHashMap<String, List<Integer>>();
			final Map<String, Integer> filled = new TreeMap<String, Integer>();
			boolean approximate = false;
			for (int r = 0; r < n; r++)
			{
				final String[] t = tokens[i][r];
				if (t == null)
				{
					continue;
				}
				KeyFacts.increment(filled, pRows.get(r).system);
				if (t.length > 1)
				{
					approximate = true;
				}
				for (final String token : t)
				{
					List<Integer> members = groups.get(token);
					if (members == null)
					{
						members = new ArrayList<Integer>(2);
						groups.put(token, members);
					}
					members.add(Integer.valueOf(r));
				}
			}
			long pairs = 0;
			final Map<String, Integer> shared = new TreeMap<String, Integer>();
			final Set<Integer> sharedRecords = new HashSet<Integer>();
			for (final List<Integer> members : groups.values())
			{
				if (members.size() > 1)
				{
					pairs += KeyFacts.choose2(members.size());
					sharedRecords.addAll(members);
				}
			}
			for (final Integer r : sharedRecords)
			{
				KeyFacts.increment(shared, pRows.get(r.intValue()).system);
			}
			stats.add(new KeyStat(spec.name, spec.base, spec.multiToken, filled, shared, groups.size(), pairs, approximate));

			// agreement of this key with every other key of another base
			for (int j = 0; j < k; j++)
			{
				final KeySpec other = specs.get(j);
				if (j == i || other.multiToken || other.base.equals(spec.base))
				{
					continue;
				}
				long agreeTotal = 0;
				long comparableTotal = 0;
				long pairsTotal = 0;
				for (final List<Integer> members : groups.values())
				{
					if (members.size() < 2)
					{
						continue;
					}
					pairsTotal += KeyFacts.choose2(members.size());
					final Map<String, Integer> byOther = new HashMap<String, Integer>();
					int known = 0;
					for (final Integer r : members)
					{
						final String[] o = tokens[j][r.intValue()];
						if (o != null)
						{
							known++;
							final Integer c = byOther.get(o[0]);
							byOther.put(o[0], Integer.valueOf(c == null ? 1 : c.intValue() + 1));
						}
					}
					comparableTotal += KeyFacts.choose2(known);
					for (final Integer c : byOther.values())
					{
						agreeTotal += KeyFacts.choose2(c.intValue());
					}
				}
				if (pairsTotal > 0)
				{
					agreements.add(new Agreement(spec.name, other.name, pairsTotal, agreeTotal, comparableTotal - agreeTotal, pairsTotal - comparableTotal));
				}
			}
		}
		return new KeyFacts(n, pSampled, recordsBySystem, stats, agreements, masks);
	}

	// ----------------------------------------------------------------------------------------------------------------------------------- reading

	public int rows()
	{
		return this.rows;
	}

	/** True when the facts were computed on a prefix of the table: lower bounds, never used to relax a rule. */
	public boolean sampled()
	{
		return this.sampled;
	}

	public Map<String, Integer> recordsBySystem()
	{
		return Collections.unmodifiableMap(this.recordsBySystem);
	}

	public List<KeyStat> keys()
	{
		return Collections.unmodifiableList(this.keys);
	}

	public List<Agreement> agreements()
	{
		return Collections.unmodifiableList(this.agreements);
	}

	public KeyStat key(final String pName)
	{
		for (final KeyStat stat : this.keys)
		{
			if (stat.name.equals(pName))
			{
				return stat;
			}
		}
		return null;
	}

	/** The agreement of {@code pKey} with {@code pOther}, or null when the two share a base, one is unknown, or no two records share {@code pKey}. */
	public Agreement agreement(final String pKey, final String pOther)
	{
		for (final Agreement agreement : this.agreements)
		{
			if (agreement.key.equals(pKey) && agreement.other.equals(pOther))
			{
				return agreement;
			}
		}
		return null;
	}

	/** True when {@code pKey} and {@code pOther} are measured on complete data, independent, and {@link Agreement#safe()}. */
	public boolean safePair(final String pKey, final String pOther)
	{
		final Agreement agreement = this.agreement(pKey, pOther);
		return !this.sampled && agreement != null && agreement.safe();
	}

	/** True when {@code pKeyName} is measured and its records share at most {@link #MAX_PAIRS_PER_FILLED_RECORD} pairs each, on average (and at least one pair): see the constant. */
	public boolean selective(final String pKeyName)
	{
		final KeyStat stat = this.key(pKeyName);
		if (stat == null)
		{
			return false;
		}
		final int filled = stat.filledTotal();
		return stat.pairsSharing > 0 && filled > 0 && stat.pairsSharing <= (long) KeyFacts.MAX_PAIRS_PER_FILLED_RECORD * filled;
	}

	/**
	 * The agreement that makes {@code pKeyName} PREDICTIVE (the one with the most comparable pairs), or null: complete facts (never a sample), the key and the other key both
	 * {@linkplain #selective selective} - an agreement on a coarse key (the first name that is a component of the name key) proves nothing -, another base,
	 * at least {@link #MIN_COMPARABLE_PAIRS} comparable pairs and a rate of at least {@link #MIN_PREDICTIVE_RATE}.
	 */
	public Agreement predictiveAgreement(final String pKeyName)
	{
		if (this.sampled || !this.selective(pKeyName))
		{
			return null;
		}
		Agreement best = null;
		for (final Agreement agreement : this.agreements)
		{
			if (!agreement.key.equals(pKeyName) || agreement.comparable() < KeyFacts.MIN_COMPARABLE_PAIRS || agreement.rate() < KeyFacts.MIN_PREDICTIVE_RATE
				|| !this.selective(agreement.other))
			{
				continue;
			}
			if (best == null || agreement.comparable() > best.comparable())
			{
				best = agreement;
			}
		}
		return best;
	}

	/** True when {@link #predictiveAgreement} finds one: the data shows that two records sharing this key are, nine times out of ten or more, the same record on an independent key. */
	public boolean predictive(final String pKeyName)
	{
		return this.predictiveAgreement(pKeyName) != null;
	}

	/**
	 * The stricter reading the Factory's own fallback plan keeps: the key is {@linkplain #selective selective} and one of its agreements with another SELECTIVE key of another base is
	 * {@linkplain Agreement#safe SAFE} (99%, no conflict, ten comparable pairs) on complete facts. Implies {@link #predictive}. The first name that is a component of the name key made the name
	 * key "safe" (694 of 694 agree) before the partner had to be selective; a birth date shared by 105 pairs, 103 of them one person, is predictive but not safe.
	 */
	public boolean safelyPredictive(final String pKeyName)
	{
		if (this.sampled || !this.selective(pKeyName))
		{
			return false;
		}
		for (final Agreement agreement : this.agreements)
		{
			if (agreement.key.equals(pKeyName) && agreement.safe() && this.selective(agreement.other))
			{
				return true;
			}
		}
		return false;
	}

	/** The names of the predictive keys, in the order of the facts; empty when there are no complete facts or none is predictive. */
	public List<String> predictiveKeys()
	{
		final List<String> out = new ArrayList<String>();
		for (final KeyStat stat : this.keys)
		{
			if (this.predictive(stat.name))
			{
				out.add(stat.name);
			}
		}
		return out;
	}

	/**
	 * For each source system, how many records fill at least one of {@code pKeyNames}: {@code [records, reached]}. A key name unknown to these facts is ignored; when
	 * none is known the result is empty (nothing can be said).
	 */
	public Map<String, long[]> recordsReachedBy(final Iterable<String> pKeyNames)
	{
		int subset = 0;
		for (final String name : pKeyNames)
		{
			for (int i = 0; i < this.keys.size(); i++)
			{
				if (this.keys.get(i).name.equals(name))
				{
					subset |= 1 << i;
				}
			}
		}
		final Map<String, long[]> out = new LinkedHashMap<String, long[]>();
		if (subset == 0)
		{
			return out;
		}
		for (final Map.Entry<String, Map<Integer, Integer>> system : this.masksBySystem.entrySet())
		{
			long records = 0;
			long reached = 0;
			for (final Map.Entry<Integer, Integer> mask : system.getValue().entrySet())
			{
				records += mask.getValue().intValue();
				if ((mask.getKey().intValue() & subset) != 0)
				{
					reached += mask.getValue().intValue();
				}
			}
			out.put(system.getKey(), new long[] { records, reached });
		}
		return out;
	}

	/** Every key name of these facts. */
	public List<String> keyNames()
	{
		final List<String> out = new ArrayList<String>();
		for (final KeyStat stat : this.keys)
		{
			out.add(stat.name);
		}
		return out;
	}

	// ----------------------------------------------------------------------------------------------------------------------------------- JSON

	public ObjectNode toJson()
	{
		final ObjectNode out = KeyFacts.MAPPER.createObjectNode();
		out.put("rows", this.rows);
		out.put("sampled", this.sampled);
		final ObjectNode systems = out.putObject("recordsBySystem");
		for (final Map.Entry<String, Integer> e : this.recordsBySystem.entrySet())
		{
			systems.put(e.getKey(), e.getValue().intValue());
		}
		final ArrayNode keyArray = out.putArray("keys");
		for (final KeyStat stat : this.keys)
		{
			final ObjectNode node = keyArray.addObject();
			node.put("name", stat.name);
			node.put("base", stat.base);
			node.put("multiToken", stat.multiToken);
			KeyFacts.putCounts(node.putObject("filledBySystem"), stat.filledBySystem);
			KeyFacts.putCounts(node.putObject("sharedBySystem"), stat.sharedBySystem);
			node.put("distinct", stat.distinct);
			node.put("pairsSharing", stat.pairsSharing);
			node.put("approximate", stat.approximate);
		}
		final ArrayNode agreementArray = out.putArray("agreements");
		for (final Agreement agreement : this.agreements)
		{
			final ObjectNode node = agreementArray.addObject();
			node.put("key", agreement.key);
			node.put("other", agreement.other);
			node.put("pairs", agreement.pairs);
			node.put("agree", agreement.agree);
			node.put("conflict", agreement.conflict);
			node.put("unknown", agreement.unknown);
		}
		final ObjectNode masksNode = out.putObject("masks");
		for (final Map.Entry<String, Map<Integer, Integer>> system : this.masksBySystem.entrySet())
		{
			final ObjectNode node = masksNode.putObject(system.getKey());
			for (final Map.Entry<Integer, Integer> mask : system.getValue().entrySet())
			{
				node.put(String.valueOf(mask.getKey()), mask.getValue().intValue());
			}
		}
		return out;
	}

	/** The inverse of {@link #toJson}; null when {@code pNode} is missing or is not what {@link #toJson} writes. */
	public static KeyFacts fromJson(final JsonNode pNode)
	{
		if (pNode == null || !pNode.isObject() || !pNode.path("keys").isArray() || !pNode.path("masks").isObject())
		{
			return null;
		}
		final Map<String, Integer> records = new TreeMap<String, Integer>();
		final java.util.Iterator<Map.Entry<String, JsonNode>> systems = pNode.path("recordsBySystem").fields();
		while (systems.hasNext())
		{
			final Map.Entry<String, JsonNode> e = systems.next();
			records.put(e.getKey(), Integer.valueOf(e.getValue().asInt(0)));
		}
		final List<KeyStat> stats = new ArrayList<KeyStat>();
		for (final JsonNode node : pNode.path("keys"))
		{
			stats.add(new KeyStat(node.path("name").asText(""), node.path("base").asText(""), node.path("multiToken").asBoolean(false), KeyFacts.readCounts(node.path("filledBySystem")),
				KeyFacts.readCounts(node.path("sharedBySystem")), node.path("distinct").asInt(0), node.path("pairsSharing").asLong(0), node.path("approximate").asBoolean(false)));
		}
		final List<Agreement> agreements = new ArrayList<Agreement>();
		for (final JsonNode node : pNode.path("agreements"))
		{
			agreements.add(new Agreement(node.path("key").asText(""), node.path("other").asText(""), node.path("pairs").asLong(0), node.path("agree").asLong(0),
				node.path("conflict").asLong(0), node.path("unknown").asLong(0)));
		}
		final Map<String, Map<Integer, Integer>> masks = new TreeMap<String, Map<Integer, Integer>>();
		final java.util.Iterator<Map.Entry<String, JsonNode>> maskSystems = pNode.path("masks").fields();
		while (maskSystems.hasNext())
		{
			final Map.Entry<String, JsonNode> system = maskSystems.next();
			final Map<Integer, Integer> byMask = new TreeMap<Integer, Integer>();
			final java.util.Iterator<Map.Entry<String, JsonNode>> entries = system.getValue().fields();
			while (entries.hasNext())
			{
				final Map.Entry<String, JsonNode> entry = entries.next();
				try
				{
					byMask.put(Integer.valueOf(entry.getKey()), Integer.valueOf(entry.getValue().asInt(0)));
				}
				catch (final NumberFormatException ex)
				{
					return null;
				}
			}
			masks.put(system.getKey(), byMask);
		}
		return new KeyFacts(pNode.path("rows").asInt(0), pNode.path("sampled").asBoolean(false), records, stats, agreements, masks);
	}

	// ----------------------------------------------------------------------------------------------------------------------------------- prompt

	/**
	 * The "measured key facts" block of the prompt, English, one entity. Numbers only; never a value. {@code pRowCountNote} is appended to the header (for example
	 * the entity's row count).
	 */
	public String promptText(final String pEntityName)
	{
		final StringBuilder out = new StringBuilder();
		out.append("  Measured key facts (counted on ").append(this.rows).append(" record(s) of ").append(pEntityName)
			.append(this.sampled ? ", ONLY THE FIRST ones of a larger table - lower bounds, so none of the rules below that cite these numbers applies" : "")
			.append(", exact value comparison, empty values never agree):\n");
		out.append("    Records per source system: ").append(KeyFacts.joinCounts(this.recordsBySystem, null)).append(".\n");
		for (final KeyStat stat : this.keys)
		{
			out.append("    - ").append(stat.name).append(": filled ").append(KeyFacts.joinCounts(stat.filledBySystem, this.recordsBySystem)).append("; ")
				.append(stat.distinct).append(" distinct value(s); records whose value another record also carries ")
				.append(KeyFacts.joinCounts(stat.sharedBySystem, this.recordsBySystem)).append("; ").append(stat.pairsSharing).append(" pair(s) of records share it")
				.append(stat.approximate ? " (counted per shared token)" : "").append('\n');
		}
		boolean header = false;
		for (final Agreement agreement : this.agreements)
		{
			if (agreement.pairs == 0)
			{
				continue;
			}
			if (!header)
			{
				out.append("    Agreement between keys - among the pairs of records that carry the SAME value of the first key, what the second key says "
					+ "(agree = same value, differ = both filled and different, unknown = missing on a side; rate = agree / (agree + differ)):\n");
				header = true;
			}
			out.append("    - same ").append(agreement.key).append(" -> ").append(agreement.other).append(": ").append(agreement.pairs).append(" pair(s), agree ")
				.append(agreement.agree).append(", differ ").append(agreement.conflict).append(", unknown ").append(agreement.unknown);
			if (agreement.comparable() > 0)
			{
				out.append(", rate ").append(String.format(java.util.Locale.ROOT, "%.1f%%", Double.valueOf(agreement.rate() * 100d)));
			}
			if (!this.sampled && agreement.safe())
			{
				out.append(" [SAFE: at least ").append(String.format(java.util.Locale.ROOT, "%.0f%%", Double.valueOf(KeyFacts.MIN_AGREEMENT * 100d))).append(" over ")
					.append(KeyFacts.MIN_COMPARABLE_PAIRS).append("+ comparable pairs and no disagreement]");
			}
			out.append('\n');
		}
		final String gaps = this.gapsText();
		if (gaps != null)
		{
			out.append("    Gaps (records of a system that do not fill a key; a pair that includes such a record can only be found through the OTHER keys it fills, and the add-on's candidate search keeps a "
				+ "pair only when the keys it agrees on weigh enough - see HOW THE ADD-ON FINDS CANDIDATES): ").append(gaps).append(".\n");
		}
		final Map<String, long[]> all = this.recordsReachedBy(this.keyNames());
		if (!all.isEmpty())
		{
			out.append("    Records that fill NONE of the keys above, per source system: ");
			boolean first = true;
			for (final Map.Entry<String, long[]> e : all.entrySet())
			{
				out.append(first ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue()[0] - e.getValue()[1]).append(" of ").append(e.getValue()[0]);
				first = false;
			}
			out.append(". A candidate filter made of some of these keys can only reach the records that fill at least one of them: ask yourself, per source system, "
				+ "which keys it fills before choosing the filter.\n");
		}
		return out.toString();
	}

	/** {@code emailKey: STORES 103 of 234; mobileE164: ...} - for each key the systems whose records do not all fill it; {@code null} when every key is filled everywhere. */
	public String gapsText()
	{
		final StringBuilder out = new StringBuilder();
		for (final KeyStat stat : this.keys)
		{
			final StringBuilder systems = new StringBuilder();
			for (final Map.Entry<String, Integer> system : this.recordsBySystem.entrySet())
			{
				final Integer filled = stat.filledBySystem.get(system.getKey());
				final int missing = system.getValue().intValue() - (filled == null ? 0 : filled.intValue());
				if (missing > 0)
				{
					systems.append(systems.length() == 0 ? "" : ", ").append(system.getKey()).append(' ').append(missing).append(" of ").append(system.getValue());
				}
			}
			if (systems.length() > 0)
			{
				out.append(out.length() == 0 ? "" : "; ").append(stat.name).append(": ").append(systems);
			}
		}
		return out.length() == 0 ? null : out.toString();
	}

	/** {@code ECOM 160 (100%), STORES 131 (56%)} - how many records of each system fill the key; {@code null} for an unknown key. */
	public String fillText(final String pKeyName)
	{
		final KeyStat stat = this.key(pKeyName);
		return stat == null ? null : KeyFacts.joinCounts(stat.filledBySystem, this.recordsBySystem);
	}

	// ----------------------------------------------------------------------------------------------------------------------------------- helpers

	private static String[] tokensOf(final String pValue, final boolean pMultiToken)
	{
		if (pValue == null)
		{
			return null;
		}
		final String trimmed = pValue.trim();
		if (trimmed.isEmpty())
		{
			return null;
		}
		if (!pMultiToken)
		{
			return new String[] { trimmed };
		}
		final Set<String> distinct = new LinkedHashSet<String>();
		for (final String token : trimmed.split("\\s+"))
		{
			if (!token.isEmpty())
			{
				distinct.add(token);
			}
		}
		return distinct.isEmpty() ? null : distinct.toArray(new String[distinct.size()]);
	}

	static long choose2(final long pN)
	{
		return pN * (pN - 1) / 2;
	}

	private static void increment(final Map<String, Integer> pMap, final String pKey)
	{
		final Integer c = pMap.get(pKey);
		pMap.put(pKey, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
	}

	private static void incrementInt(final Map<Integer, Integer> pMap, final int pKey)
	{
		final Integer c = pMap.get(Integer.valueOf(pKey));
		pMap.put(Integer.valueOf(pKey), Integer.valueOf(c == null ? 1 : c.intValue() + 1));
	}

	private static int sum(final Map<String, Integer> pMap)
	{
		int total = 0;
		for (final Integer value : pMap.values())
		{
			total += value.intValue();
		}
		return total;
	}

	private static void putCounts(final ObjectNode pNode, final Map<String, Integer> pCounts)
	{
		for (final Map.Entry<String, Integer> e : pCounts.entrySet())
		{
			pNode.put(e.getKey(), e.getValue().intValue());
		}
	}

	private static Map<String, Integer> readCounts(final JsonNode pNode)
	{
		final Map<String, Integer> out = new TreeMap<String, Integer>();
		final java.util.Iterator<Map.Entry<String, JsonNode>> it = pNode.fields();
		while (it.hasNext())
		{
			final Map.Entry<String, JsonNode> e = it.next();
			out.put(e.getKey(), Integer.valueOf(e.getValue().asInt(0)));
		}
		return out;
	}

	/** {@code A 160 (100%), B 234 (56%)}: counts per system, with the share of the system's records when {@code pTotals} is given (a system absent from the counts reads 0). */
	static String joinCounts(final Map<String, Integer> pCounts, final Map<String, Integer> pTotals)
	{
		final Map<String, Integer> systems = pTotals != null ? pTotals : pCounts;
		final StringBuilder out = new StringBuilder();
		for (final String system : systems.keySet())
		{
			final Integer count = pCounts.get(system);
			final int value = count == null ? 0 : count.intValue();
			out.append(out.length() == 0 ? "" : ", ").append(system).append(' ').append(value);
			if (pTotals != null)
			{
				final Integer total = pTotals.get(system);
				if (total != null && total.intValue() > 0)
				{
					out.append(" (").append(Math.round(100d * value / total.intValue())).append("%)");
				}
			}
		}
		return out.length() == 0 ? "none" : out.toString();
	}
}
