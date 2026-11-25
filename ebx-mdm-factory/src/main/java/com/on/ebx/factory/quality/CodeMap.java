package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Codes transcoding lot H3 (2026-10-17, docs/CODES-TRANSCODING.md): the argument of a {@code TRANSCODE_REFERENCE}
 * {@code MappingStep} that carries its OWN code table instead of naming a configured {@code ReferenceBinding} - the
 * mapping "source code -&gt; target code" of ONE source column of an attribute whose sources write one short, repeated code
 * list differently (a loyalty level {@code Bronze}/{@code Argent}/{@code Or} against another system's {@code B}/{@code S}/{@code G}; a title
 * {@code M.}/{@code Mme} against {@code MR}/{@code MRS}). Pure: no EBX class.
 * <p>
 * <b>Shape</b> (the whole step argument, at most 2000 characters - {@code MappingStep.argument}):
 * <pre>{"codes":{"Bronze":"BRONZE","Argent":"SILVER"},"targets":["BRONZE","SILVER","GOLD"],"counts":{"Bronze":70,"Argent":70},"basedOn":"TIER"}</pre>
 * {@code codes} is the table (the key is the code AS OBSERVED in the landing column, the value its target code, empty = not
 * mapped yet); {@code targets} the canonical list every source of the attribute is mapped onto; {@code counts} the rows
 * each observed code had when the table was proposed (what the Mapping matrix shows next to it); {@code basedOn} the
 * configured reference ({@code ReferenceBinding.kind}) the targets were taken from, or absent. The key {@code reference}
 * of the reference-naming form is deliberately NOT reused: {@link CleaningSteps} tells the two forms apart by {@code codes}.
 * <p>
 * <b>Matching</b> is the lookup steps' own ({@link CleaningSteps#normalizeKey}: case, accents, punctuation and white space
 * ignored, so {@code "M."} and {@code "m"} are one key), except for a code that is nothing but punctuation ({@code "-"}),
 * which keeps its own characters as its key. Two observed codes with one key and two different targets are a
 * {@link #conflicts() conflict}: neither resolves. A value with no entry (or an empty target) is <b>unresolved</b>: the
 * lookup steps' rule, the value is never invented, and the verifier counts it as a failure.
 */
public final class CodeMap
{
	public static final String CODES = "codes";
	public static final String TARGETS = "targets";
	public static final String COUNTS = "counts";
	public static final String BASED_ON = "basedOn";
	/** {@code MappingStep.argument} is declared with this maximum length. */
	public static final int MAX_JSON_LENGTH = 2000;

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int CACHE_MAX = 256;
	private static final Map<String, CodeMap> CACHE = new LinkedHashMap<String, CodeMap>();

	/** Observed code -&gt; target code ("" when not mapped yet), in the order written. */
	public final Map<String, String> codes;
	/** The canonical list, in the order written. */
	public final List<String> targets;
	/** Rows per observed code at proposal time; may be empty. */
	public final Map<String, Long> counts;
	/** The {@code ReferenceBinding} kind the targets come from, or "". */
	public final String basedOn;
	private final Map<String, String> byKey = new HashMap<String, String>();
	private final Set<String> conflictKeys = new LinkedHashSet<String>();

	public CodeMap(final Map<String, String> pCodes, final List<String> pTargets, final Map<String, Long> pCounts, final String pBasedOn)
	{
		this.codes = Collections.unmodifiableMap(new LinkedHashMap<String, String>(pCodes == null ? new LinkedHashMap<String, String>() : pCodes));
		this.targets = Collections.unmodifiableList(new ArrayList<String>(pTargets == null ? new ArrayList<String>() : pTargets));
		this.counts = Collections.unmodifiableMap(new LinkedHashMap<String, Long>(pCounts == null ? new LinkedHashMap<String, Long>() : pCounts));
		this.basedOn = pBasedOn == null ? "" : pBasedOn;
		final Map<String, String> firstTarget = new HashMap<String, String>();
		for (final Map.Entry<String, String> entry : this.codes.entrySet())
		{
			final String target = entry.getValue() == null ? "" : entry.getValue().trim();
			if (target.isEmpty())
			{
				continue;
			}
			final String key = CodeMap.keyOf(entry.getKey());
			final String seen = firstTarget.get(key);
			if (seen == null)
			{
				firstTarget.put(key, target);
				this.byKey.put(key, target);
			}
			else if (!seen.equals(target))
			{
				this.conflictKeys.add(key);
			}
		}
		for (final String key : this.conflictKeys)
		{
			this.byKey.remove(key);
		}
	}

	/** The key two spellings of one code share: {@link CleaningSteps#normalizeKey}, or the trimmed lower-cased text when nothing but punctuation is left. */
	public static String keyOf(final String pCode)
	{
		final String key = CleaningSteps.normalizeKey(pCode);
		if (!key.isEmpty())
		{
			return key;
		}
		return "~" + (pCode == null ? "" : pCode.trim().toLowerCase(Locale.ROOT));
	}

	// ------------------------------------------------------------------------------------------------- reading

	/** The table of a step argument, or null when it is not one (a reference-naming argument, blank, malformed, no {@code codes} object). Cached: the loader asks once per row. */
	public static CodeMap parseCached(final String pJson)
	{
		if (pJson == null || pJson.indexOf('"' + CodeMap.CODES + '"') < 0)
		{
			return null;
		}
		synchronized (CodeMap.CACHE)
		{
			if (CodeMap.CACHE.containsKey(pJson))
			{
				return CodeMap.CACHE.get(pJson);
			}
		}
		final CodeMap parsed = CodeMap.parse(pJson);
		synchronized (CodeMap.CACHE)
		{
			if (CodeMap.CACHE.size() >= CodeMap.CACHE_MAX)
			{
				CodeMap.CACHE.clear();
			}
			CodeMap.CACHE.put(pJson, parsed);
		}
		return parsed;
	}

	/** Same, uncached. */
	public static CodeMap parse(final String pJson)
	{
		if (pJson == null || pJson.trim().isEmpty())
		{
			return null;
		}
		try
		{
			final JsonNode root = CodeMap.MAPPER.readTree(pJson);
			if (root == null || !root.isObject() || !root.path(CodeMap.CODES).isObject())
			{
				return null;
			}
			final Map<String, String> codes = new LinkedHashMap<String, String>();
			final Iterator<Map.Entry<String, JsonNode>> fields = root.path(CodeMap.CODES).fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				codes.put(field.getKey(), field.getValue().isNull() ? "" : field.getValue().asText(""));
			}
			final List<String> targets = new ArrayList<String>();
			if (root.path(CodeMap.TARGETS).isArray())
			{
				for (final JsonNode target : root.path(CodeMap.TARGETS))
				{
					if (target.isTextual() && !target.asText().trim().isEmpty())
					{
						targets.add(target.asText());
					}
				}
			}
			final Map<String, Long> counts = new LinkedHashMap<String, Long>();
			if (root.path(CodeMap.COUNTS).isObject())
			{
				final Iterator<Map.Entry<String, JsonNode>> countFields = root.path(CodeMap.COUNTS).fields();
				while (countFields.hasNext())
				{
					final Map.Entry<String, JsonNode> field = countFields.next();
					if (field.getValue().canConvertToLong())
					{
						counts.put(field.getKey(), Long.valueOf(field.getValue().asLong()));
					}
				}
			}
			return new CodeMap(codes, targets, counts, root.path(CodeMap.BASED_ON).asText(""));
		}
		catch (final java.io.IOException | RuntimeException ex)
		{
			return null;
		}
	}

	/** The step argument for this table. */
	public String toJson()
	{
		final ObjectNode root = CodeMap.MAPPER.createObjectNode();
		final ObjectNode codeNode = root.putObject(CodeMap.CODES);
		for (final Map.Entry<String, String> entry : this.codes.entrySet())
		{
			codeNode.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
		}
		final com.fasterxml.jackson.databind.node.ArrayNode targetNode = root.putArray(CodeMap.TARGETS);
		for (final String target : this.targets)
		{
			targetNode.add(target);
		}
		if (!this.counts.isEmpty())
		{
			final ObjectNode countNode = root.putObject(CodeMap.COUNTS);
			for (final Map.Entry<String, Long> entry : this.counts.entrySet())
			{
				countNode.put(entry.getKey(), entry.getValue().longValue());
			}
		}
		if (!this.basedOn.isEmpty())
		{
			root.put(CodeMap.BASED_ON, this.basedOn);
		}
		return root.toString();
	}

	// ---------------------------------------------------------------------------------------------- lookup

	/** The target of {@code pRaw}, or null when no entry (or an empty target, or a conflicting key) resolves it. */
	public String lookup(final String pRaw)
	{
		if (pRaw == null || pRaw.trim().isEmpty())
		{
			return null;
		}
		return this.byKey.get(CodeMap.keyOf(pRaw));
	}

	/** Keys that two observed codes share while pointing at different targets: nothing resolves them. */
	public Set<String> conflicts()
	{
		return Collections.unmodifiableSet(this.conflictKeys);
	}

	/** True when every observed code is the target it maps to (the table changes nothing). */
	public boolean isIdentity()
	{
		for (final Map.Entry<String, String> entry : this.codes.entrySet())
		{
			final String target = entry.getValue() == null ? "" : entry.getValue().trim();
			if (!target.isEmpty() && !target.equals(entry.getKey().trim()))
			{
				return false;
			}
		}
		return true;
	}

	/** A copy with {@code pEdited} (observed code -&gt; new target) written over the table; a code that is not in the table is ignored. */
	public CodeMap withTargets(final Map<String, String> pEdited)
	{
		final Map<String, String> next = new LinkedHashMap<String, String>(this.codes);
		final List<String> nextTargets = new ArrayList<String>(this.targets);
		for (final Map.Entry<String, String> entry : pEdited.entrySet())
		{
			if (!next.containsKey(entry.getKey()))
			{
				continue;
			}
			final String target = entry.getValue() == null ? "" : entry.getValue().trim();
			next.put(entry.getKey(), target);
			if (!target.isEmpty() && !nextTargets.contains(target))
			{
				nextTargets.add(target);
			}
		}
		return new CodeMap(next, nextTargets, this.counts, this.basedOn);
	}

	// -------------------------------------------------------------------------------------------- coverage

	/** What the table does to a set of values: rows resolved, rows left alone, rows rewritten. */
	public static final class Coverage
	{
		public long rows;
		public long resolved;
		public long unresolved;
		public long changed;
		public long distinct;
		public long unresolvedDistinct;
		/** Distinct values with no target, in the order met (up to a few). */
		public final List<String> unresolvedValues = new ArrayList<String>();

		/** Share of rows resolved, 0 to 100 (100 when there is no row). */
		public int percent()
		{
			return this.rows <= 0L ? 100 : (int) ((this.resolved * 100L) / this.rows);
		}

		/** True when no value is left without a target. */
		public boolean complete()
		{
			return this.unresolved == 0L;
		}
	}

	/** The table run over {@code pValues} (blank values are not counted: nothing reaches the step). */
	public Coverage cover(final List<Detectors.ValueCount> pValues)
	{
		final Coverage out = new Coverage();
		for (final Detectors.ValueCount value : pValues)
		{
			if (value.value == null || value.value.trim().isEmpty())
			{
				continue;
			}
			out.rows += value.count;
			out.distinct++;
			final String target = this.lookup(value.value);
			if (target == null)
			{
				out.unresolved += value.count;
				out.unresolvedDistinct++;
				if (out.unresolvedValues.size() < 8)
				{
					out.unresolvedValues.add(value.value.trim());
				}
				continue;
			}
			out.resolved += value.count;
			if (!target.equals(value.value.trim()))
			{
				out.changed += value.count;
			}
		}
		return out;
	}

	/**
	 * Staging last points, item 3 (2026-10-17): the observed codes that have rows and that no entry resolves - an empty target, or a spelling that collides with another going
	 * elsewhere - in the order of the table. A code whose recorded count is 0 has no row to lose; a code with no recorded count is taken to have some (the table does not say).
	 * A table with such a code must not be accepted: the load would leave those rows as they are, in a field whose other sources write the common list.
	 */
	public List<String> codesWithoutTarget()
	{
		final List<String> out = new ArrayList<String>();
		for (final String code : this.codes.keySet())
		{
			final Long count = this.counts.get(code);
			if (count != null && count.longValue() <= 0L)
			{
				continue;
			}
			if (this.lookup(code) == null)
			{
				out.add(code);
			}
		}
		return out;
	}

	/** The same figures from the rows recorded in the table itself ({@link #counts}) - what the Mapping matrix shows without reading the landing; a code with no recorded count counts for 0. */
	public Coverage coverageFromCounts()
	{
		final List<Detectors.ValueCount> values = new ArrayList<Detectors.ValueCount>();
		for (final String code : this.codes.keySet())
		{
			final Long count = this.counts.get(code);
			values.add(new Detectors.ValueCount(code, count == null ? 0L : count.longValue()));
		}
		return this.cover(values);
	}

	/** The values after the table: each resolved value replaced by its target (an unresolved one is kept as it is). */
	public List<Detectors.ValueCount> apply(final List<Detectors.ValueCount> pValues)
	{
		final List<Detectors.ValueCount> out = new ArrayList<Detectors.ValueCount>();
		for (final Detectors.ValueCount value : pValues)
		{
			final String target = this.lookup(value.value);
			out.add(target == null ? value : new Detectors.ValueCount(target, value.scopeValue, value.count));
		}
		return out;
	}
}
