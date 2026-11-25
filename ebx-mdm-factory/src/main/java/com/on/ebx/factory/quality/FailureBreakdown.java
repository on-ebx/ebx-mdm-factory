package com.on.ebx.factory.quality;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Validation desk lot L4 (2026-10-22): WHERE a quality rule fails. A rule measured on the distinct landing values says "48 of 335 fail"; this class says "the 48 are all
 * values of rows whose countryCode is not FR", by grouping the failing values by ONE other attribute of the same entity, the one that best separates failing from passing
 * values. A fact the factory MEASURES and writes into the prompt and onto the rule ({@code QualityRule.failureBreakdown}); the language model decides whether a condition
 * ("only when countryCode is FR") or a wider pattern is the answer, never this class.
 * <p>
 * Pure: no EBX class. The caller supplies, per candidate grouping attribute, the joint distinct counts of (value of the rule's attribute, value of the grouping attribute) read
 * from the same landing rows ({@link Joint}), a {@link Judge} that says whether a value fails the rule, and a {@link Shower} that renders a failing value for storage (a real
 * value or a shape, according to the data-sharing level - the same rule as {@code QualityRule.examples}).
 */
public final class FailureBreakdown
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** A grouping attribute with more distinct values than this is not offered: the breakdown must stay readable and storable. */
	public static final int MAX_GROUPS = 30;
	/** Example failing values kept per group. */
	public static final int MAX_EXAMPLES_PER_GROUP = 5;
	/** The storage cap of {@code QualityRule.failureBreakdown}. */
	public static final int MAX_JSON_LENGTH = 4000;

	/** Stored when a rule was measured and nothing fails, or no other field separates the failures: "measured, no breakdown", distinct from an empty field ("never measured"). */
	public static final String NOT_FOUND_JSON = "{\"groups\":[]}";

	private FailureBreakdown()
	{
	}

	/** True when the stored text says the rule was measured (a breakdown, or the {@link #NOT_FOUND_JSON} marker). */
	public static boolean measured(final String pJson)
	{
		if (pJson == null || pJson.trim().isEmpty())
		{
			return false;
		}
		try
		{
			return FailureBreakdown.MAPPER.readTree(pJson).isObject();
		}
		catch (final IOException ex)
		{
			return false;
		}
	}

	/** Whether a value fails the rule. */
	public interface Judge
	{
		boolean fails(String pValue);
	}

	/** How a failing value is stored as an example: the value itself, a shape, or {@code null} for none. */
	public interface Shower
	{
		String show(String pValue);
	}

	/** The joint distinct counts of one candidate grouping attribute: each {@link Detectors.ValueCount} is (value of the rule's attribute, value of the grouping attribute, rows). */
	public static final class Joint
	{
		public final String groupAttribute;
		public final List<Detectors.ValueCount> pairs;

		public Joint(final String pGroupAttribute, final List<Detectors.ValueCount> pPairs)
		{
			this.groupAttribute = pGroupAttribute;
			this.pairs = pPairs == null ? new ArrayList<Detectors.ValueCount>() : pPairs;
		}

		/** The distinct values of the grouping attribute in these pairs (blank ones left out). */
		public int groupCount()
		{
			final java.util.Set<String> groups = new java.util.LinkedHashSet<String>();
			for (final Detectors.ValueCount pair : this.pairs)
			{
				if (pair.scopeValue != null && !pair.scopeValue.trim().isEmpty())
				{
					groups.add(pair.scopeValue);
				}
			}
			return groups.size();
		}
	}

	/** One value of the grouping attribute: rows seen, rows failing, and a few failing values. */
	public static final class Group
	{
		public final String value;
		public final long seen;
		public final long failing;
		public final List<String> examples;

		Group(final String pValue, final long pSeen, final long pFailing, final List<String> pExamples)
		{
			this.value = pValue;
			this.seen = pSeen;
			this.failing = pFailing;
			this.examples = pExamples;
		}
	}

	/** The breakdown by one grouping attribute. */
	public static final class Result
	{
		public final String attribute;
		public final List<Group> groups;

		Result(final String pAttribute, final List<Group> pGroups)
		{
			this.attribute = pAttribute;
			this.groups = pGroups;
		}

		/** Rows seen and failing within the groups whose value is one of {@code pValues} (trimmed, case-insensitive). */
		public long[] within(final List<String> pValues)
		{
			long seen = 0;
			long failing = 0;
			for (final Group group : this.groups)
			{
				if (FailureBreakdown.contains(pValues, group.value))
				{
					seen += group.seen;
					failing += group.failing;
				}
			}
			return new long[] { seen, failing };
		}

		/** The group values that really occur (what a proposed condition may name). */
		public List<String> values()
		{
			final List<String> out = new ArrayList<String>();
			for (final Group group : this.groups)
			{
				out.add(group.value);
			}
			return out;
		}

		/** The values of the groups where nothing fails, i.e. the natural condition under which the rule is valid. */
		public List<String> cleanValues()
		{
			final List<String> out = new ArrayList<String>();
			for (final Group group : this.groups)
			{
				if (group.failing == 0)
				{
					out.add(group.value);
				}
			}
			return out;
		}
	}

	static boolean contains(final List<String> pValues, final String pValue)
	{
		if (pValues == null || pValue == null)
		{
			return false;
		}
		for (final String candidate : pValues)
		{
			if (candidate != null && candidate.trim().equalsIgnoreCase(pValue.trim()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Groups the pairs of ONE grouping attribute. Blank values of either side are skipped (an empty value never fails a rule and a row without a group is outside every group).
	 * {@code null} when the attribute has more than {@link #MAX_GROUPS} groups or fewer than one.
	 */
	public static Result groupBy(final Joint pJoint, final Judge pJudge, final Shower pShower)
	{
		final Map<String, long[]> counts = new LinkedHashMap<String, long[]>();
		final Map<String, List<String>> examples = new LinkedHashMap<String, List<String>>();
		for (final Detectors.ValueCount pair : pJoint.pairs)
		{
			if (pair.value == null || pair.value.trim().isEmpty() || pair.scopeValue == null || pair.scopeValue.trim().isEmpty())
			{
				continue;
			}
			final String group = pair.scopeValue.trim();
			long[] c = counts.get(group);
			if (c == null)
			{
				c = new long[2];
				counts.put(group, c);
				examples.put(group, new ArrayList<String>());
			}
			c[0] += pair.count;
			if (pJudge.fails(pair.value))
			{
				c[1] += pair.count;
				final List<String> list = examples.get(group);
				final String shown = pShower == null ? pair.value : pShower.show(pair.value);
				if (shown != null && list.size() < FailureBreakdown.MAX_EXAMPLES_PER_GROUP && !list.contains(shown))
				{
					list.add(shown);
				}
			}
		}
		if (counts.isEmpty() || counts.size() > FailureBreakdown.MAX_GROUPS)
		{
			return null;
		}
		final List<Group> groups = new ArrayList<Group>();
		for (final Map.Entry<String, long[]> entry : counts.entrySet())
		{
			groups.add(new Group(entry.getKey(), entry.getValue()[0], entry.getValue()[1], examples.get(entry.getKey())));
		}
		Collections.sort(groups, new Comparator<Group>()
		{
			@Override
			public int compare(final Group pLeft, final Group pRight)
			{
				if (pLeft.failing != pRight.failing)
				{
					return pLeft.failing > pRight.failing ? -1 : 1;
				}
				if (pLeft.seen != pRight.seen)
				{
					return pLeft.seen > pRight.seen ? -1 : 1;
				}
				return pLeft.value.compareTo(pRight.value);
			}
		});
		return new Result(pJoint.groupAttribute, groups);
	}

	/** Rows that a grouping cannot tell apart from their group's majority: for each group, the smaller of failing and passing. The lower, the better the attribute separates failures. */
	static long impurity(final Result pResult)
	{
		long impurity = 0;
		for (final Group group : pResult.groups)
		{
			impurity += Math.min(group.failing, group.seen - group.failing);
		}
		return impurity;
	}

	/**
	 * The grouping attribute that best separates failing from passing values, or {@code null} when none helps (nothing fails, or no attribute separates better than treating the
	 * column as one group). The measure is {@link #impurity}; it must be strictly below the one-group baseline (the smaller of all failing and all passing), ties go to the
	 * attribute with fewer groups, then to the name.
	 */
	public static Result best(final List<Joint> pJoints, final Judge pJudge, final Shower pShower)
	{
		Result best = null;
		long bestImpurity = Long.MAX_VALUE;
		if (pJoints == null)
		{
			return null;
		}
		for (final Joint joint : pJoints)
		{
			final Result result = FailureBreakdown.groupBy(joint, pJudge, pShower);
			if (result == null || result.groups.size() < 2)
			{
				continue;
			}
			long seen = 0;
			long failing = 0;
			for (final Group group : result.groups)
			{
				seen += group.seen;
				failing += group.failing;
			}
			if (failing == 0)
			{
				return null; // nothing fails on these rows: no breakdown to show
			}
			final long baseline = Math.min(failing, seen - failing);
			final long impurity = FailureBreakdown.impurity(result);
			if (impurity >= baseline)
			{
				continue;
			}
			final boolean better = best == null || impurity < bestImpurity
				|| (impurity == bestImpurity && (result.groups.size() < best.groups.size()
					|| (result.groups.size() == best.groups.size() && result.attribute.compareTo(best.attribute) < 0)));
			if (better)
			{
				best = result;
				bestImpurity = impurity;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------------------------------------------- json

	/** {@code {"attribute":..,"groups":[{"value","seen","failing","examples":[..]}]}}, cut to {@link #MAX_JSON_LENGTH}: examples go first, then the groups that fail nothing, last of all. */
	public static String toJson(final Result pResult)
	{
		if (pResult == null)
		{
			return null;
		}
		int keepExamples = FailureBreakdown.MAX_EXAMPLES_PER_GROUP;
		int keepGroups = pResult.groups.size();
		String json = FailureBreakdown.render(pResult, keepExamples, keepGroups);
		while (json.length() > FailureBreakdown.MAX_JSON_LENGTH && keepExamples > 0)
		{
			keepExamples--;
			json = FailureBreakdown.render(pResult, keepExamples, keepGroups);
		}
		while (json.length() > FailureBreakdown.MAX_JSON_LENGTH && keepGroups > 2)
		{
			keepGroups--;
			json = FailureBreakdown.render(pResult, keepExamples, keepGroups);
		}
		return json;
	}

	private static String render(final Result pResult, final int pExamples, final int pGroups)
	{
		final ObjectNode root = FailureBreakdown.MAPPER.createObjectNode();
		root.put("attribute", pResult.attribute);
		final ArrayNode groups = root.putArray("groups");
		for (int i = 0; i < pResult.groups.size() && i < pGroups; i++)
		{
			final Group group = pResult.groups.get(i);
			final ObjectNode node = groups.addObject();
			node.put("value", group.value);
			node.put("seen", group.seen);
			node.put("failing", group.failing);
			final ArrayNode list = node.putArray("examples");
			for (int e = 0; e < group.examples.size() && e < pExamples; e++)
			{
				list.add(group.examples.get(e));
			}
		}
		return root.toString();
	}

	/** The stored JSON read back; {@code null} for a blank or malformed text, a missing attribute or no group. */
	public static Result parse(final String pJson)
	{
		if (pJson == null || pJson.trim().isEmpty())
		{
			return null;
		}
		try
		{
			final JsonNode root = FailureBreakdown.MAPPER.readTree(pJson);
			final String attribute = root.path("attribute").asText(null);
			final JsonNode groupsNode = root.path("groups");
			if (attribute == null || attribute.isEmpty() || !groupsNode.isArray() || groupsNode.size() == 0)
			{
				return null;
			}
			final List<Group> groups = new ArrayList<Group>();
			for (final JsonNode node : groupsNode)
			{
				final List<String> examples = new ArrayList<String>();
				for (final JsonNode example : node.path("examples"))
				{
					examples.add(example.asText());
				}
				groups.add(new Group(node.path("value").asText(""), node.path("seen").asLong(0), node.path("failing").asLong(0), examples));
			}
			return new Result(attribute, groups);
		}
		catch (final IOException ex)
		{
			return null;
		}
	}

	// ---------------------------------------------------------------------------------------------------- prompt

	/**
	 * The breakdown as the model reads it. With {@code pShowValues} (the project shares real values) every group is listed with its failing share, the groups where nothing fails
	 * summarised; otherwise only the concentration is stated, with no value of the grouping attribute.
	 */
	public static String promptText(final Result pResult, final boolean pShowValues)
	{
		long seen = 0;
		long failing = 0;
		int failingGroups = 0;
		for (final Group group : pResult.groups)
		{
			seen += group.seen;
			failing += group.failing;
			if (group.failing > 0)
			{
				failingGroups++;
			}
		}
		final StringBuilder out = new StringBuilder();
		out.append(" Where it fails, by the field '").append(pResult.attribute).append("' of the same rows: ").append(failing).append(" of ").append(seen)
			.append(" value(s) fail, in ").append(failingGroups).append(" of ").append(pResult.groups.size()).append(" group(s).");
		if (!pShowValues)
		{
			return out.append(" (The values of that field are not shown at this data-sharing level, so no condition can be named: propose a pattern covering the failing shapes, or leave the rule out.)")
				.toString();
		}
		out.append(' ');
		boolean first = true;
		final List<String> clean = new ArrayList<String>();
		for (final Group group : pResult.groups)
		{
			if (group.failing == 0)
			{
				clean.add(group.value + " (" + group.seen + ")");
				continue;
			}
			if (!first)
			{
				out.append("; ");
			}
			first = false;
			out.append(pResult.attribute).append('=').append(group.value).append(": ").append(group.failing).append('/').append(group.seen).append(" fail");
		}
		if (!clean.isEmpty())
		{
			out.append(first ? "" : "; ").append("no failure for ").append(pResult.attribute).append(" in ").append(String.join(", ", clean));
		}
		out.append('.');
		return out.toString();
	}

	/**
	 * Why {@code pValues} cannot be a condition on {@code pResult}'s grouping attribute: empty, or a value that was never measured in a group. {@code null} when they are all
	 * measured values.
	 */
	public static String conditionProblem(final Result pResult, final List<String> pValues)
	{
		if (pValues == null || pValues.isEmpty())
		{
			return "conditionValues must list at least one value of " + pResult.attribute;
		}
		final List<String> known = pResult.values();
		for (final String value : pValues)
		{
			if (value == null || value.trim().isEmpty())
			{
				return "conditionValues holds an empty value";
			}
			if (!FailureBreakdown.contains(known, value))
			{
				return "'" + value + "' is not a value of " + pResult.attribute + " measured on these rows (measured: " + known + ")";
			}
		}
		return null;
	}

	/** Lower-case a name for comparisons that ignore case. */
	static String key(final String pName)
	{
		return pName == null ? "" : pName.trim().toLowerCase(Locale.ROOT);
	}
}
