package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.matching.KeyFacts;

/**
 * Lot M10a (2026-10-18, {@code docs/MATCHING.md} &sect;64): a path to {@code SUSPECTED} or {@code MATCH} must be able to tell two records apart from two NAMESAKES - it must require
 * at least one exact condition that identifies a record.
 * <p>
 * Measured on the 7th live run of the B2C master scenario (plan 74, 35 leaves, recall 1.00): of the 581 pairs the plan found, 16 were two different people, chaining three clusters
 * of two people. All 15 suspect edges behind them went through ONE path - the same name key, similar names, and the same postal code - and nothing else: different e-mail, phone,
 * birth date and street. In the simulate-first variant of the same run (plan 75), 234 of 768 suspect edges - 274 of 839 pairs, precision 0.67 - went through "same first name,
 * similar names". The conditions of such a path (a name, a postal code) are shared by dozens of unrelated records; the plan's other paths already found every true pair, so the
 * path added false pairs only.
 * <p>
 * The rule, purely structural and measured (no field name is looked at): for every path from the root to a positive leaf, the nodes it passes through their {@code onTrue} edge
 * must include, in an {@code ALL_FIELDS} node, an exact condition ({@code exact}, or {@code exactFullText} at {@value #MIN_FULL_TEXT_SCORE} or more) on a field that
 * <ul>
 * <li>is a key of the entity's {@link KeyFacts} that is {@linkplain KeyFacts#predictive PREDICTIVE}: among the pairs of records that share it, at least {@link KeyFacts#MIN_PREDICTIVE_RATE} of the
 * comparable pairs share another selective key of another base ({@code emailKey}, a phone key: 100%; the address keys: 94.5%; the name key: 79-81% - namesakes - so it is not);
 * or</li>
 * <li>is not among the measured keys but shows, on its own profile, at most {@link #MAX_ROWS_PER_VALUE} rows per distinct value on average (a street: 1.6; a postal code: 53, the
 * first name: 19, the last name: 9.9 are groupings) - an attribute of the entity or of a related record ({@code via}); or</li>
 * <li>cannot be judged (no profile): the benefit of the doubt - the rule refuses what the data shows to be coarse, nothing else.</li>
 * </ul>
 * A condition counts only when a missing value does NOT satisfy it ({@code bothNull} and {@code oneNull} NOT_MATCH, the same reading as {@code CandidateFilterCheck}) and its
 * {@code minimumScore} is above 0.
 * The rule is inactive - the old behaviour - when the entity has no complete key facts or none of its keys is predictive (nothing could satisfy it), when the tree has more positive
 * paths than {@link CrossSystemPathRule#MAX_PATHS} (cannot be analysed), and for an {@code AVERAGE}/{@code AT_LEAST_N}/{@code PREDICATE} node, which never counts as identifying (a
 * score over names may be reached with the weight of any one of its fields). Recall: every path that carries a predictive key, or a precise related field, is untouched; on both
 * live plans the only paths it refuses are the ones that produced the false pairs, and none of the 565 true pairs of the scenario goes through one.
 * <p>
 * Pure: no EBX class.
 */
final class PathEvidenceRule
{
	/** An {@code exactFullText} condition counts as exact from this score: matched words over the larger word count, i.e. at least half of the words shared. */
	static final int MIN_FULL_TEXT_SCORE = 50;
	/** A field that has no measured key facts counts as precise when, among its filled rows, each distinct value is carried by at most this many rows on average. */
	static final double MAX_ROWS_PER_VALUE = 5d;
	/** How many offending paths a refusal lists before it says "and N more". */
	static final int MAX_LISTED_PATHS = 3;

	private PathEvidenceRule()
	{
	}

	/** What a condition of a path says about identity. */
	enum Verdict
	{
		/** Identifies a record: a predictive key, a precise field, or a field nothing is known about. */
		IDENTIFIES,
		/** Shared by many unrelated records. */
		COARSE,
		/** Not an exact condition (a similarity score, or an {@code exactFullText} below the minimum): carries no identity of its own. */
		NOT_EXACT
	}

	/** One condition of a path, as a person reads it. */
	static final class Condition
	{
		final String node;
		final String text;
		final Verdict verdict;
		/** Why this condition does not identify (empty when it does). */
		final String why;
		/** Lot M14: an exact condition on EVIDENCE ABOUT THE CHILDREN of the record - a derived child-keys attribute or a field read through a related record ({@code via}). */
		boolean child;
		/** Lot M14: an exact condition on an own-identity key of the record itself: a plain attribute (not a child-keys attribute, not a related record's field, not a parent reference) that is a measured key or identifies by its profile. */
		boolean own;

		Condition(final String pNode, final String pText, final Verdict pVerdict, final String pWhy)
		{
			this.node = pNode;
			this.text = pText;
			this.verdict = pVerdict;
			this.why = pWhy == null ? "" : pWhy;
		}
	}

	/** A positive path that no condition of identifies. */
	static final class Offender
	{
		final CrossSystemPathRule.PositivePath path;
		final List<Condition> conditions;

		Offender(final CrossSystemPathRule.PositivePath pPath, final List<Condition> pConditions)
		{
			this.path = pPath;
			this.conditions = pConditions;
		}

		/** The node whose {@code onTrue} edge ends the path in the positive leaf: the one to re-point. */
		String lastNode()
		{
			return this.path.nodeIds.isEmpty() ? "" : this.path.nodeIds.get(this.path.nodeIds.size() - 1);
		}
	}

	/** The verdict on a whole tree. */
	static final class Result
	{
		/** False when the rule did not apply (no complete facts, no predictive key, too many paths): {@link #offenders} is then empty. */
		final boolean active;
		final List<Offender> offenders;
		final List<String> predictiveKeys;

		Result(final boolean pActive, final List<Offender> pOffenders, final List<String> pPredictiveKeys)
		{
			this.active = pActive;
			this.offenders = pOffenders;
			this.predictiveKeys = pPredictiveKeys;
		}

		boolean ok()
		{
			return this.offenders.isEmpty();
		}
	}

	/** True when the rule can judge a tree of this entity at all: complete key facts with at least one predictive key. */
	static boolean applies(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		return pEntity != null && pEntity.keyFacts != null && !pEntity.keyFacts.sampled() && !pEntity.keyFacts.predictiveKeys().isEmpty();
	}

	/** Judges every positive path of {@code pPlan} (a validated tree) for {@code pEntity}. */
	static Result check(final MatchPlanLlmPass.EntityInfo pEntity, final DecisionTreePlan.Plan pPlan)
	{
		final List<Offender> offenders = new ArrayList<Offender>();
		if (!PathEvidenceRule.applies(pEntity) || pPlan == null)
		{
			return new Result(false, offenders, new ArrayList<String>());
		}
		final List<String> predictive = pEntity.keyFacts.predictiveKeys();
		final CrossSystemPathRule.Paths paths = CrossSystemPathRule.positivePaths(pPlan);
		if (paths.truncated)
		{
			return new Result(false, offenders, predictive);
		}
		for (final CrossSystemPathRule.PositivePath path : paths.paths)
		{
			final List<Condition> conditions = PathEvidenceRule.conditionsOf(pEntity, path);
			boolean identifies = false;
			for (final Condition condition : conditions)
			{
				if (condition.verdict == Verdict.IDENTIFIES)
				{
					identifies = true;
					break;
				}
			}
			if (!identifies)
			{
				offenders.add(new Offender(path, conditions));
			}
		}
		return new Result(true, offenders, predictive);
	}

	/** The conditions a path imposes: every field of every node it passes through {@code onTrue}, in order. */
	static List<Condition> conditionsOf(final MatchPlanLlmPass.EntityInfo pEntity, final CrossSystemPathRule.PositivePath pPath)
	{
		final List<Condition> out = new ArrayList<Condition>();
		for (final DecisionTreePlan.Node node : pPath.passedNodes)
		{
			final boolean allFields = DecisionTreePlan.FUNCTION_ALL_FIELDS.equals(node.function);
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				final String text = PathEvidenceRule.describe(field, node);
				if (!allFields)
				{
					out.add(new Condition(node.id, text, Verdict.NOT_EXACT,
						"a score over several fields (" + node.function + ") can be reached without this one"));
					continue;
				}
				final Condition condition = PathEvidenceRule.judge(pEntity, node.id, field, text);
				PathEvidenceRule.tag(condition, pEntity, field);
				out.add(condition);
			}
		}
		return out;
	}

	private static String describe(final DecisionTreePlan.FieldRef pField, final DecisionTreePlan.Node pNode)
	{
		final StringBuilder out = new StringBuilder(String.valueOf(pField.field));
		if (pField.isRelation())
		{
			out.append(" of ").append(pField.via);
		}
		final String algorithm = PathEvidenceRule.shortAlgorithm(pField.algorithm);
		if (DecisionTreePlan.FUNCTION_ALL_FIELDS.equals(pNode.function))
		{
			out.append(' ').append(algorithm);
		}
		else
		{
			out.append(' ').append(pNode.function.toLowerCase(Locale.ROOT).replace('_', ' ')).append(' ').append(algorithm);
		}
		return out.toString();
	}

	private static String shortAlgorithm(final String pAlgorithm)
	{
		final String trimmed = pAlgorithm == null ? "" : pAlgorithm.trim();
		final int at = trimmed.lastIndexOf('@');
		return at >= 0 ? trimmed.substring(at + 1) : trimmed;
	}

	/** The verdict on one field of an ALL_FIELDS node. */
	static Condition judge(final MatchPlanLlmPass.EntityInfo pEntity, final String pNodeId, final DecisionTreePlan.FieldRef pField, final String pText)
	{
		if (!DecisionTreePlan.isExactAlgorithm(pField.algorithm))
		{
			return new Condition(pNodeId, pText, Verdict.NOT_EXACT, "a similarity comparison is not an identity");
		}
		if (PathEvidenceRule.matchesOnMissing(pField.oneNull) || PathEvidenceRule.matchesOnMissing(pField.bothNull))
		{
			return new Condition(pNodeId, pText, Verdict.NOT_EXACT, "a missing value would satisfy it (null handling other than NOT_MATCH): no evidence from a record that lacks it");
		}
		if (pField.minimumScore != null && pField.minimumScore.intValue() <= 0)
		{
			return new Condition(pNodeId, pText, Verdict.NOT_EXACT, "minimumScore 0 is satisfied by anything");
		}
		final boolean fullText = pField.algorithm != null && pField.algorithm.toLowerCase(Locale.ROOT).endsWith("exactfulltext");
		if (fullText && (pField.minimumScore == null ? 100 : pField.minimumScore.intValue()) < PathEvidenceRule.MIN_FULL_TEXT_SCORE)
		{
			return new Condition(pNodeId, pText, Verdict.NOT_EXACT, "exactFullText below " + PathEvidenceRule.MIN_FULL_TEXT_SCORE + " shares less than half of the words");
		}
		final String name = pField.field == null ? "" : pField.field;
		if (pField.isRelation())
		{
			final MatchPlanLlmPass.RelationChildInfo child = PathEvidenceRule.child(pEntity, pField.via);
			final MatchPlanLlmPass.AttributeInfo attribute = pEntity.relationAttribute(pField.via, name);
			final Double rows = attribute == null || child == null ? null : attribute.rowsPerDistinctValue(PathEvidenceRule.childRows(pEntity, child));
			return PathEvidenceRule.byRowsPerValue(pNodeId, pText, rows, attribute, name + " of " + pField.via);
		}
		final KeyFacts facts = pEntity.keyFacts;
		final KeyFacts.KeyStat stat = facts.key(name);
		if (stat != null)
		{
			if (facts.predictive(name))
			{
				return new Condition(pNodeId, pText, Verdict.IDENTIFIES, "");
			}
			return new Condition(pNodeId, pText, Verdict.COARSE, PathEvidenceRule.whyNotPredictive(facts, stat));
		}
		final MatchPlanLlmPass.AttributeInfo attribute = pEntity.attribute(name);
		if (attribute != null && MatchPlanLlmPass.AttributeInfo.ROLE_REFERENCE.equals(attribute.role))
		{
			// the parent reference of a child entity: its own node shape is checked elsewhere; it neither identifies nor is coarse
			return new Condition(pNodeId, pText, Verdict.NOT_EXACT, "a parent reference");
		}
		final Double rows = attribute == null ? null : attribute.rowsPerDistinctValue(pEntity.rowCount);
		return PathEvidenceRule.byRowsPerValue(pNodeId, pText, rows, attribute, name);
	}

	/** Absent = NOT_MATCH (the value the translator writes, as {@code CandidateFilterCheck} reads it); anything else lets a record with a missing value pass the condition. */
	private static boolean matchesOnMissing(final String pHandling)
	{
		return pHandling != null && !pHandling.trim().isEmpty() && !pHandling.trim().toUpperCase(Locale.ROOT).contains("NOT_MATCH");
	}

	private static Condition byRowsPerValue(final String pNodeId, final String pText, final Double pRows, final MatchPlanLlmPass.AttributeInfo pAttribute, final String pLabel)
	{
		if (pRows == null)
		{
			// not measured: never read as coarse
			return new Condition(pNodeId, pText, Verdict.IDENTIFIES, "");
		}
		if (pRows.doubleValue() <= PathEvidenceRule.MAX_ROWS_PER_VALUE)
		{
			return new Condition(pNodeId, pText, Verdict.IDENTIFIES, "");
		}
		return new Condition(pNodeId, pText, Verdict.COARSE, pLabel + ": each value is shared by " + PathEvidenceRule.oneDecimal(pRows.doubleValue()) + " rows on average ("
			+ (pAttribute == null || pAttribute.distinctCount == null ? "?" : String.valueOf(pAttribute.distinctCount)) + " distinct values) - a grouping, not an identity");
	}

	/** Why a measured key is not predictive, from its own numbers. */
	private static String whyNotPredictive(final KeyFacts pFacts, final KeyFacts.KeyStat pStat)
	{
		final int filled = pStat.filledTotal();
		if (!pFacts.selective(pStat.name))
		{
			return pStat.name + ": " + pStat.pairsSharing + " pair(s) of records share it for " + filled + " filled record(s) (more than " + KeyFacts.MAX_PAIRS_PER_FILLED_RECORD
				+ " per record) - a grouping, not an identity";
		}
		final List<String> rates = new ArrayList<String>();
		for (final KeyFacts.Agreement agreement : pFacts.agreements())
		{
			if (agreement.key.equals(pStat.name) && agreement.comparable() >= KeyFacts.MIN_COMPARABLE_PAIRS && pFacts.selective(agreement.other))
			{
				rates.add(Math.round(agreement.rate() * 100d) + "% share " + agreement.other);
			}
		}
		return pStat.name + ": among the " + pStat.pairsSharing + " pair(s) of records that share it, "
			+ (rates.isEmpty() ? "none can be compared with another selective key" : String.join(", ", rates)) + " - below " + Math.round(KeyFacts.MIN_PREDICTIVE_RATE * 100d)
			+ "%: two records can share it and be two different people";
	}

	private static String oneDecimal(final double pValue)
	{
		return String.format(Locale.ROOT, "%.1f", Double.valueOf(pValue)).replace(".0", "");
	}

	private static MatchPlanLlmPass.RelationChildInfo child(final MatchPlanLlmPass.EntityInfo pEntity, final String pChildEntity)
	{
		for (final MatchPlanLlmPass.RelationChildInfo child : pEntity.relationChildren)
		{
			if (child.childEntity != null && child.childEntity.equals(pChildEntity))
			{
				return child;
			}
		}
		return null;
	}

	/**
	 * Rows of a child table, estimated: parents x average rows per parent (at least one per parent when the average is not measured - a lower bound, which can only make a coarse
	 * field look more precise than it is).
	 */
	private static long childRows(final MatchPlanLlmPass.EntityInfo pEntity, final MatchPlanLlmPass.RelationChildInfo pChild)
	{
		final double average = pChild.avgRowsPerParent == null || pChild.avgRowsPerParent.doubleValue() < 1d ? 1d : pChild.avgRowsPerParent.doubleValue();
		return Math.round(pEntity.rowCount * average);
	}


	// ------------------------------------------------------------------------------------------------------------------------------------- lot M14

	/**
	 * Lot M14 (2026-10-18, {@code docs/MATCHING.md} &sect;67): marks a condition as child evidence or as an own-identity key. Only an exact, missing-value-proof condition ever carries a role
	 * ({@link Verdict#NOT_EXACT} carries none).
	 */
	private static void tag(final Condition pCondition, final MatchPlanLlmPass.EntityInfo pEntity, final DecisionTreePlan.FieldRef pField)
	{
		if (pCondition.verdict == Verdict.NOT_EXACT)
		{
			return;
		}
		if (pField.isRelation())
		{
			pCondition.child = true;
			return;
		}
		final String name = pField.field == null ? "" : pField.field;
		final MatchPlanLlmPass.AttributeInfo attribute = pEntity.attribute(name);
		final KeyFacts facts = pEntity.keyFacts;
		final KeyFacts.KeyStat stat = facts == null ? null : facts.key(name);
		if (attribute != null && attribute.isChildKeys() || attribute == null && stat != null && stat.multiToken)
		{
			pCondition.child = true;
			return;
		}
		if (attribute != null && MatchPlanLlmPass.AttributeInfo.ROLE_REFERENCE.equals(attribute.role))
		{
			return;
		}
		pCondition.own = stat != null || pCondition.verdict == Verdict.IDENTIFIES;
	}

	/** The verdict of the two structural rules of lot M14 on a whole tree. */
	static final class StructureResult
	{
		/** False when the rules did not apply (no complete key facts, no key of the record itself to put on a path, too many paths). */
		final boolean active;
		/** Paths to a positive leaf whose exact conditions are child evidence only. */
		final List<Offender> childAlone;
		/** Paths whose positive leaf hangs on the {@code onFalse} edge of a similarity node while no own-identity key holds on the path. */
		final List<Offender> failedComparison;
		/** The own-identity keys of the entity (the Factory's eligible keys), for the fixes. */
		final List<String> ownKeys;

		StructureResult(final boolean pActive, final List<Offender> pChildAlone, final List<Offender> pFailedComparison, final List<String> pOwnKeys)
		{
			this.active = pActive;
			this.childAlone = pChildAlone;
			this.failedComparison = pFailedComparison;
			this.ownKeys = pOwnKeys;
		}

		boolean ok()
		{
			return this.childAlone.isEmpty() && this.failedComparison.isEmpty();
		}
	}

	/** The keys of the record itself a path may be opened with (the Factory's own definition of a key that may stand in a node, {@link MatchPlanFallback#eligibleKeys}); empty when the facts are missing or sampled. */
	static List<String> ownKeyNames(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		final List<String> out = new ArrayList<String>();
		if (pEntity == null || pEntity.keyFacts == null || pEntity.keyFacts.sampled())
		{
			return out;
		}
		for (final MatchPlanFallback.Key key : MatchPlanFallback.eligibleKeys(pEntity, pEntity.keyFacts, false))
		{
			out.add(key.name);
		}
		return out;
	}

	/** A node that decides by a score or by anything but exact conditions: its {@code false} side means "not similar enough", not "different". */
	private static boolean similarityNode(final DecisionTreePlan.Node pNode)
	{
		if (!DecisionTreePlan.FUNCTION_ALL_FIELDS.equals(pNode.function))
		{
			return true;
		}
		for (final DecisionTreePlan.FieldRef field : pNode.fields)
		{
			if (!DecisionTreePlan.isExactAlgorithm(field.algorithm))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Lot M14. Two structural rules on every path to SUSPECTED or MATCH, active under complete key facts when the entity has a key of its own to put on a path:
	 * <ol>
	 * <li><b>Child evidence never carries a path alone.</b> A derived child-keys attribute ({@code addressKeys}) or a field of a related record ({@code street of Adresse}) is evidence about the
	 * record's CHILDREN: whatever its measured rate it needs an exact own-identity key of the record on the same {@code onTrue} chain. Measured on the 10th live run: a path
	 * {@code addressKeys -> SUSPECTED} paired store tickets of one area (the address of a ticket is a bare postal code).</li>
	 * <li><b>A failed comparison is not evidence.</b> A positive leaf reached through the {@code onFalse} edge of a similarity node ({@code AVERAGE}, a fuzzy comparison: "names NOT similar -> suspect")
	 * is allowed only when the conjunction of the exact conditions already met along the path holds an own-identity key ("key equal but the secondary comparison differs" is a legitimate suspect).</li>
	 * </ol>
	 */
	static StructureResult checkStructure(final MatchPlanLlmPass.EntityInfo pEntity, final DecisionTreePlan.Plan pPlan)
	{
		final List<Offender> childAlone = new ArrayList<Offender>();
		final List<Offender> failed = new ArrayList<Offender>();
		final List<String> ownKeys = PathEvidenceRule.ownKeyNames(pEntity);
		if (pPlan == null || ownKeys.isEmpty())
		{
			return new StructureResult(false, childAlone, failed, ownKeys);
		}
		final CrossSystemPathRule.Paths paths = CrossSystemPathRule.positivePaths(pPlan);
		if (paths.truncated)
		{
			return new StructureResult(false, childAlone, failed, ownKeys);
		}
		for (final CrossSystemPathRule.PositivePath path : paths.paths)
		{
			final List<Condition> conditions = PathEvidenceRule.conditionsOf(pEntity, path);
			boolean hasOwn = false;
			boolean hasChild = false;
			for (final Condition condition : conditions)
			{
				hasOwn |= condition.own;
				hasChild |= condition.child;
			}
			if (hasChild && !hasOwn)
			{
				childAlone.add(new Offender(path, conditions));
			}
			if (path.leafViaOnFalse && !hasOwn)
			{
				final DecisionTreePlan.Node last = path.nodeIds.isEmpty() ? null : pPlan.byId(path.nodeIds.get(path.nodeIds.size() - 1));
				if (last != null && PathEvidenceRule.similarityNode(last))
				{
					failed.add(new Offender(path, conditions));
				}
			}
		}
		return new StructureResult(true, childAlone, failed, ownKeys);
	}

	/** The refusals of {@link #checkStructure}, one text per rule (child evidence first); empty when the tree passes or the rules are inactive. */
	static List<String> structureRefusals(final MatchPlanLlmPass.EntityInfo pEntity, final StructureResult pResult)
	{
		final List<String> out = new ArrayList<String>();
		if (!pResult.active || pResult.ok())
		{
			return out;
		}
		final String keys = String.join(", ", pResult.ownKeys);
		if (!pResult.childAlone.isEmpty())
		{
			final List<String> childNodes = new ArrayList<String>();
			final List<String> childFields = new ArrayList<String>();
			final StringBuilder text = new StringBuilder("the proposed tree has ").append(pResult.childAlone.size())
				.append(" path(s) to SUSPECTED or MATCH carried by child evidence alone: ");
			final int shown = Math.min(pResult.childAlone.size(), PathEvidenceRule.MAX_LISTED_PATHS);
			for (int i = 0; i < pResult.childAlone.size(); i++)
			{
				final Offender offender = pResult.childAlone.get(i);
				if (i < shown)
				{
					text.append(i == 0 ? "" : "; ").append(PathEvidenceRule.pathLabel(offender));
				}
				for (final Condition condition : offender.conditions)
				{
					if (condition.child)
					{
						if (!childNodes.contains(condition.node))
						{
							childNodes.add(condition.node);
						}
						final String field = condition.text.indexOf(' ') > 0 ? condition.text.substring(0, condition.text.indexOf(' ')) : condition.text;
						if (!childFields.contains(field))
						{
							childFields.add(field);
						}
					}
				}
			}
			if (pResult.childAlone.size() > shown)
			{
				text.append("; and ").append(pResult.childAlone.size() - shown).append(" more");
			}
			final List<String> quotedNodes = new ArrayList<String>();
			for (final String node : childNodes)
			{
				quotedNodes.add("node '" + node + "'");
			}
			text.append(". Child evidence - a derived child-keys attribute or a field read through a related record (").append(String.join(", ", childFields))
				.append(") - is evidence about the record's CHILDREN, not about the record: whatever its measured rate it must never carry a path alone, because two different records can share an address, "
					+ "a street or a postal code (a store ticket's address may be a bare postal code). Every such path needs an exact own-identity key of the record (")
				.append(keys).append(") in an ALL_FIELDS node on the SAME onTrue path (exact algorithm, bothNullValueHandling and oneNullValueHandling NOT_MATCH, minimumScore 100). Fix each path: "
					+ "add one of those keys to the node that compares the child evidence (")
				.append(String.join(", ", quotedNodes)).append("), or open the path with an ALL_FIELDS node on one of those keys whose onTrue leads to that node, or point the onTrue of ")
				.append(String.join(", ", quotedNodes)).append(" to NOT_MATCHED.");
			out.add(text.toString());
		}
		if (!pResult.failedComparison.isEmpty())
		{
			final List<String> edges = new ArrayList<String>();
			final StringBuilder text = new StringBuilder("the proposed tree reaches SUSPECTED or MATCH through the onFalse edge of a similarity node, i.e. a FAILED comparison used as evidence, and no own-identity key holds on the path: ");
			final int shown = Math.min(pResult.failedComparison.size(), PathEvidenceRule.MAX_LISTED_PATHS);
			for (int i = 0; i < pResult.failedComparison.size(); i++)
			{
				final Offender offender = pResult.failedComparison.get(i);
				final String node = offender.lastNode();
				if (!edges.contains(node))
				{
					edges.add(node);
				}
				if (i < shown)
				{
					text.append(i == 0 ? "" : "; ").append("node '").append(node).append("'.onFalse -> ").append(offender.path.outcome).append(" (").append(PathEvidenceRule.pathLabel(offender)).append(')');
				}
			}
			if (pResult.failedComparison.size() > shown)
			{
				text.append("; and ").append(pResult.failedComparison.size() - shown).append(" more");
			}
			text.append(". 'The names are NOT similar, so suspect the pair' says nothing in favour of the pair: a failed comparison is a reason to reject, never to suspect. "
				+ "A SUSPECTED or MATCH leaf may hang on the onFalse edge of an AVERAGE or fuzzy node only when the exact conditions already met along the path (onTrue of its ALL_FIELDS ancestors) hold "
				+ "an own-identity key of the record (").append(keys).append(") - 'the key is equal but the secondary comparison differs' is then a legitimate suspect. Fix: point ");
			for (int i = 0; i < edges.size(); i++)
			{
				text.append(i == 0 ? "" : ", ").append("node '").append(edges.get(i)).append("'.onFalse");
			}
			text.append(" to NOT_MATCHED, or open the path with an own-identity key (an ALL_FIELDS node on one of those keys whose onTrue leads to the path).");
			out.add(text.toString());
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------------------------------------------- wording

	/**
	 * The refusal the model reads: the offending paths with the reason each condition does not identify, the keys that do, and the three fixes. Null when the result is ok or inactive.
	 */
	static String refusal(final MatchPlanLlmPass.EntityInfo pEntity, final Result pResult)
	{
		if (!pResult.active || pResult.ok())
		{
			return null;
		}
		final StringBuilder out = new StringBuilder("the proposed tree has ").append(pResult.offenders.size()).append(" path(s) to SUSPECTED or MATCH that require no condition identifying a record: ");
		final int shown = Math.min(pResult.offenders.size(), PathEvidenceRule.MAX_LISTED_PATHS);
		final List<String> reasons = new ArrayList<String>();
		for (int i = 0; i < shown; i++)
		{
			final Offender offender = pResult.offenders.get(i);
			out.append(i == 0 ? "" : "; ").append(PathEvidenceRule.pathLabel(offender));
			for (final Condition condition : offender.conditions)
			{
				if (!condition.why.isEmpty() && !reasons.contains(condition.why) && condition.verdict == Verdict.COARSE)
				{
					reasons.add(condition.why);
				}
			}
		}
		if (pResult.offenders.size() > shown)
		{
			out.append("; and ").append(pResult.offenders.size() - shown).append(" more");
		}
		out.append(". Two different records (namesakes in one city, say) can satisfy every condition of such a path, and the plan's other paths already find the pairs that share an identifying key, so it would add false pairs for a steward to dismiss");
		if (!reasons.isEmpty())
		{
			out.append(". Measured: ").append(String.join("; ", reasons));
		}
		out.append(". The keys the facts show to identify a record (the pairs that share one also share an independent key, ").append(Math.round(KeyFacts.MIN_PREDICTIVE_RATE * 100d))
			.append("% or more of the time): ").append(String.join(", ", pResult.predictiveKeys))
			.append(PathEvidenceRule.childKeysNote(pEntity, pResult.predictiveKeys))
			.append(". Fix each such path in one of three ways: (1) require an exact condition on one of those keys in an ALL_FIELDS node on the path (exact algorithm, bothNullValueHandling and oneNullValueHandling NOT_MATCH, minimumScore 100 - or exactFullText at 50 or more for a derived key); (2) require an exact condition on a precise field of the record itself (a related record's field, a street or a full address, only ever accompanies an own-identity key on the path); or (3) end the path in NOT_MATCHED: point the onTrue of ")
			.append(PathEvidenceRule.lastNodes(pResult, shown)).append(" to NOT_MATCHED. A similarity node (AVERAGE, jarowinkler) or a coarse key may still refine a path that already requires one of the above.");
		return out.toString();
	}

	/** Lot M14: among the identifying keys, the ones that are child evidence count only beside a key of the record itself. Empty when there is none. */
	private static String childKeysNote(final MatchPlanLlmPass.EntityInfo pEntity, final List<String> pKeys)
	{
		final List<String> child = new ArrayList<String>();
		for (final String key : pKeys)
		{
			final MatchPlanLlmPass.AttributeInfo attribute = pEntity.attribute(key);
			final KeyFacts.KeyStat stat = pEntity.keyFacts == null ? null : pEntity.keyFacts.key(key);
			if (attribute != null && attribute.isChildKeys() || attribute == null && stat != null && stat.multiToken)
			{
				child.add(key);
			}
		}
		return child.isEmpty() ? "" : " (" + String.join(", ", child) + (child.size() > 1 ? " are" : " is") + " child evidence: it identifies a record only together with an exact key of the record itself on the same path, never alone)";
	}

	private static String pathLabel(final Offender pOffender)
	{
		final StringBuilder ids = new StringBuilder();
		for (final String id : pOffender.path.nodeIds)
		{
			ids.append(ids.length() == 0 ? "" : " > ").append(id);
		}
		final List<String> texts = new ArrayList<String>();
		for (final Condition condition : pOffender.conditions)
		{
			if (!texts.contains(condition.text))
			{
				texts.add(condition.text);
			}
		}
		return "path " + ids + " (requires " + String.join(", ", texts) + " -> " + pOffender.path.outcome + ")";
	}

	private static String lastNodes(final Result pResult, final int pShown)
	{
		final List<String> nodes = new ArrayList<String>();
		for (int i = 0; i < pResult.offenders.size(); i++)
		{
			final String last = pResult.offenders.get(i).lastNode();
			if (!last.isEmpty() && !nodes.contains(last))
			{
				nodes.add(last);
			}
		}
		final int shown = Math.min(nodes.size(), PathEvidenceRule.MAX_LISTED_PATHS + 2);
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < shown; i++)
		{
			out.append(i == 0 ? "" : ", ").append(nodes.get(i));
		}
		if (nodes.size() > shown)
		{
			out.append(", and ").append(nodes.size() - shown).append(" more node(s)");
		}
		return out.length() == 0 ? "the last node of the path" : out.toString();
	}

	/**
	 * The line of the prompt, per entity, that names the keys which identify a record, so the first proposal already ends no path on a name and a postal code; empty when the rule
	 * does not apply to the entity.
	 */
	static String promptLine(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		if (!PathEvidenceRule.applies(pEntity))
		{
			return "";
		}
		return "  Identifying keys on " + pEntity.name + ": " + String.join(", ", pEntity.keyFacts.predictiveKeys())
			+ PathEvidenceRule.childKeysNote(pEntity, pEntity.keyFacts.predictiveKeys())
			+ " (the pairs of records that share one also share an independent key " + Math.round(KeyFacts.MIN_PREDICTIVE_RATE * 100d)
			+ "% or more of the time). Every path to SUSPECTED or MATCH must require an exact condition on one of them (or on a precise field of the record); "
			+ "a similarity score, a name or a postal code alone only refines. Child evidence (derived child keys, a street or a full address of a related record) never carries a path alone: it follows "
			+ "an exact key of the record on the same path. A SUSPECTED or MATCH leaf is never the onFalse of an AVERAGE or fuzzy node unless an exact key of the record already holds on that path.\n";
	}
}
