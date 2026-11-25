package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Drops a merge policy's own {@code survivors[]} entries that name a field the MASTER table does not actually
 * carry (yet) - the gap found live 2026-09-23 13:30 (`docs/MATCHING.md` &sect;26, PipelineStep 159, table
 * {@code /root/Contact}, {@code dataModel urn:ebx:publication:Companies_Master}): {@code
 * ebx.MatchingConfigurator.configureOne}'s {@code Simulate} pass accepted {@code emailValid} as a staging
 * attribute (a {@code VALID_VALUE} derivation, {@code quality.ValidValueDerivation}) and ran {@code
 * staging/generate}/{@code staging/load}, but {@code master/generate} never ran afterwards - so the MASTER
 * table's own schema never gained the field. {@code MatchingPolicyTranslator.buildMergePolicy} has no way to
 * know this (it only ever reads {@code StagingAttribute} rows and the accepted plan's own {@code
 * requirements.survivorship.fields[]} - never the master table's actual schema), so it happily built a
 * survivor rule for {@code /emailValid} anyway; {@code generate_merge_policy} then refused the WHOLE call
 * outright: {@code {"code":"INVALID_PARAMS","message":"Survivor field '/emailValid' does not resolve to a
 * field of table /root/Contact",...}}.
 * <p>
 * This class is the shared fix, called by EVERY caller of {@code MatchingPolicyTranslator#buildMergePolicy}
 * ({@code ebx.MatchingConfigurator#configureOne}/{@code #applySurvivorship}, {@code
 * ebx.MatchingPreviewPolicy#ensureFresh}/{@code #ensureRewrite}) right after building the desired merge
 * policy and before it ever reaches a match-merge write call: any survivor whose field is not a node of the
 * master table's own record is dropped, with a human-readable note explaining why (never silently - a data
 * steward reading the resulting {@code PipelineStep}/{@code MatchPlan} detail should see exactly which field(s)
 * were skipped and what to do about it: run {@code master/generate} again).
 * <p>
 * <b>Deliberately kept pure</b> - a {@link Predicate} is the schema-lookup seam (CLAUDE.md &sect;9: {@code
 * Adaptation.get(path)}/a raw {@code SchemaNode} lookup throws on an unknown path, so the actual EBX call this
 * predicate wraps at the caller - {@code masterTable.getTableOccurrenceRootNode().getNode(Path.parse("./" +
 * name)) != null} - stays outside this class entirely). This class itself never touches an EBX class, so it is
 * unit-tested with a hand-built merge policy and a fake predicate, no {@code Adaptation}/{@code AdaptationTable}
 * needed - same discipline as every other pure class in this package.
 */
public final class MasterFieldFilter
{
	private MasterFieldFilter()
	{
	}

	/**
	 * Mutates {@code pMergePolicy}'s own {@code survivors} array in place (removing any entry that fails {@code
	 * pFieldExistsOnMaster}) and returns one human-readable note per field dropped - an empty list when nothing
	 * was dropped (including when {@code pMergePolicy} carries no {@code survivors} array at all).
	 * <p>
	 * A survivor entry's own {@code field} is read exactly as {@code MatchingPolicyTranslator#toFieldPath} wrote
	 * it (a leading slash, e.g. {@code /emailValid}) - the leading slash is stripped before the predicate is
	 * asked, since a master field name never carries one (CLAUDE.md &sect;9, {@code getNode(Path.parse("./" +
	 * name))}). An entry with a blank/missing {@code field} is left in place untouched (never dropped by this
	 * method - {@code MatchingPolicyTranslator.buildMergePolicy}'s own {@code assertMergePolicyWellFormed}
	 * self-check already refuses to build one, so this should never actually occur; this method is defensive
	 * only, the same "never crash on upstream input, never invent a fix for a defect that belongs elsewhere"
	 * discipline the rest of this package already applies).
	 *
	 * @param pTablePath the master table's own path (e.g. {@code /root/Contact}) - included in the note only,
	 *            never read or resolved by this class itself.
	 * @param pFieldExistsOnMaster {@code true} when the bare (slash-less) field name IS a node of the master
	 *            table's own record - the ONE seam this pure class does not implement itself (see class
	 *            Javadoc).
	 */
	public static List<String> dropSurvivorsNotOnMaster(final ObjectNode pMergePolicy, final String pTablePath, final Predicate<String> pFieldExistsOnMaster)
	{
		final List<String> notes = new ArrayList<String>();
		final JsonNode survivorsNode = pMergePolicy.path("survivors");
		if (!(survivorsNode instanceof ArrayNode) || survivorsNode.size() == 0)
		{
			return notes;
		}
		final ArrayNode survivors = (ArrayNode) survivorsNode;
		final List<JsonNode> kept = new ArrayList<JsonNode>();
		for (final JsonNode survivor : survivors)
		{
			final String fieldPath = survivor.path("field").asText("");
			final String fieldName = fieldPath.startsWith("/") ? fieldPath.substring(1) : fieldPath;
			if (fieldName.isEmpty() || pFieldExistsOnMaster.test(fieldName))
			{
				kept.add(survivor);
			}
			else
			{
				notes.add(MasterFieldFilter.droppedNote(fieldName, pTablePath));
			}
		}
		if (kept.size() != survivors.size())
		{
			survivors.removeAll();
			survivors.addAll(kept);
		}
		return notes;
	}

	/**
	 * A {@code *Valid} field ({@code quality.ValidValueDerivation}'s own naming convention, {@code
	 * "&lt;sourceName&gt;Valid"}) gets a slightly more specific note - the same "accepted but master/generate
	 * has not caught up yet" root cause either way, this is a wording-only distinction (task brief: "distinguish,
	 * if cheap"), never a different check.
	 */
	private static String droppedNote(final String pFieldName, final String pTablePath)
	{
		final String kind = pFieldName.endsWith("Valid") ? " (looks like a *Valid derived field)" : "";
		return pFieldName + kind + ": accepted in staging but not on the master table " + pTablePath
			+ " yet - run master/generate then configure again.";
	}
}
