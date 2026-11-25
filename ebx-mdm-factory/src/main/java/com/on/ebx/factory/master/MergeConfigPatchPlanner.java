package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Computes the MINIMAL, order-safe {@code patch_matching_config} operations list that turns an EXISTING
 * merge policy's chains/survivors into {@link MatchingPolicyTranslator#buildMergePolicy}'s desired shape -
 * the logic behind {@code POST /projects/{code}/matching/survivorship} (`docs/MATCHING.md`, 2026-09-22
 * "Survivorship and trusted sources"): re-applying trusted-source survivorship to an ALREADY-CONFIGURED
 * table without deleting and regenerating its whole matching configuration (which would also mean
 * re-running {@code generate_matching_policy} and losing any manual tuning of the decision tree).
 * <p>
 * <b>Corrected 2026-09-22 23:45 against the LIVE refusal of this class's first version</b> (`docs/MATCHING.md`
 * &sect;14, "measured live, 2026-09-22 23:40"): {@code POST /projects/Companies/matching/survivorship?entity=Company}
 * failed PipelineStep 107 with {@code patch_matching_config} answering {@code {"code":"INVALID_PARAMS",
 * "message":"Invalid operations payload."}} on an operations list that used 0-based chain positions and an
 * {@code add_survivor_field} value shaped {@code {mergeFunctionChain:[...]}} - both wrong per the tool's own
 * live description and {@code matching_config_schema.patchOps}, re-read the same night:
 * <ul>
 * <li><b>{@code position} is 1-based</b> ("position addresses one of its entries, 1-based"; "position 1 is
 * the first entry") - every position this class emits for an EXISTING entry is now {@code index + 1}.</li>
 * <li><b>{@code add_chain_entry} appends when {@code target.position} is omitted</b> - this class now omits
 * {@code position} entirely for an appended entry rather than sending a stale post-append index.</li>
 * <li><b>{@code add_survivor_field}'s {@code value} is a bare merge-FUNCTION-NAME STRING</b>, never an
 * envelope - it only ever CREATES the field with a single, parameter-less entry. A desired chain whose first
 * entry carries {@code constantValue}/{@code dateFieldPath} (the live case then: {@code /sourceSystem}'s
 * {@code constant("MDM")}, replaced by lot M1), or that has more than one entry, is completed right after with {@code
 * survivor_field} chain ops (a {@code set_chain_entry} at position 1 to attach the parameter(s), then
 * {@code add_chain_entry} for any further entries) - never by trying to smuggle the parameter into the
 * creation call itself.</li>
 * </ul>
 * This was caught only at the live call, not by {@code validate_matching_config} or any compile-time check -
 * same "no line/column, re-verify against the actual instance" discipline as CLAUDE.md &sect;13: the schema
 * was read correctly in spirit (see the superseded Javadoc this replaces) but the two structural details
 * above - 1-based indexing, string-only creation value - were only surfaced by the refusal itself.
 * <p>
 * <b>Corrected AGAIN 2026-09-23 01:10, against a SECOND live measurement of the 2026-09-22 23:45 fix above</b>
 * (`docs/MATCHING.md` &sect;14.6): the coordinator ran the survivorship route on the deployed 117e193, then
 * probed {@code patch_matching_config} directly three more times. Four findings:
 * <ol>
 * <li>The one remaining op from that run, {@code {"op":"set_chain_entry", ...,
 * "value":{"function":"...lastUpdate.label"}}} - a RAW JSON OBJECT {@code value}, exactly what the 23:45 fix
 * above still sent for a parameter-less entry - was refused, generic "Invalid operations payload".</li>
 * <li>The SAME op with {@code "value":"most trusted source"} (a plain STRING) was ACCEPTED.</li>
 * <li>{@code "value":"{\"function\":\"...lastUpdate.label\",\"dateFieldPath\":\"System date\"}"} - the entry
 * object JSON-ENCODED AS A STRING - was ACCEPTED.</li>
 * <li>A raw JSON object as {@code value} is what triggers the refusal, full stop - so <b>{@code value} is
 * ALWAYS a JSON STRING</b>: the bare function name when the entry has no {@code constantValue}/{@code
 * dateFieldPath}, or the entry object serialized to a JSON string when it does - never a JSON object, not
 * even for {@code set_chain_entry}/{@code add_chain_entry} (this class's 23:45 version got this wrong for
 * EVERY chain op, not just the ones this run happened to touch - {@link #entryValueString} is the fix, used
 * everywhere a chain entry becomes a {@code value}, {@code survivor_field} {@code set_chain_entry} for a
 * {@code constant} entry included).</li>
 * </ol>
 * A fifth, unrelated finding from the same round: the add-on itself STORES {@code dateFieldPath: "System
 * date"} on a date-aware entry ({@code lastUpdate}) it created without one - this class's own {@code
 * mostCompleted}/{@code mostTrustedSource}/{@code lastUpdate} entries never set {@code dateFieldPath} at all,
 * so a fresh {@code get_matching_config} read-back of an entry THIS class just wrote reads back with {@code
 * dateFieldPath: "System date"} where the desired shape has none - compared naively, that is a spurious
 * difference, and {@link #planChain} emitted an unnecessary {@code set_chain_entry} rewriting an
 * already-correct position for exactly this reason. {@link #normalizedDateFieldPath} now collapses {@code
 * "System date"} to the same "unset" value an absent/blank {@code dateFieldPath} already normalizes to,
 * rather than having this class ever try to emit {@code "System date"} itself. A SIXTH finding, read off the
 * live shape {@code docs/MATCHING.md} &sect;14.2 already recorded ({@code constantValue: null, dateFieldPath:
 * null} - EXPLICIT JSON {@code null}, not an absent field) but never actually handled until now: {@link
 * JsonNode#asText(String)}'s default-value fallback only fires when the underlying {@code asText()} call
 * itself returns {@code null}, and Jackson's {@code NullNode.asText()} returns the four-character STRING
 * {@code "null"}, never actual Java {@code null} - so {@code pNode.path("constantValue").asText("")} on an
 * EXPLICIT JSON null used to read back {@code "null"}, not {@code ""}, comparing unequal to a desired entry
 * that never set the field at all ({@link MissingNode#asText(String)} DOES fall through to the default). "The
 * planner compared {@code constantValue}/{@code dateFieldPath} null vs absent as different" - exactly this
 * bug. {@link #text} now treats an explicit JSON null the same as a missing field.
 * <p>
 * <b>Op vocabulary and target shape - the task brief's own contract, re-read live 2026-09-22 23:45 off the
 * tool's own description and {@code matching_config_schema.patchOps}, never independently re-verified THIS
 * session by an actual successful {@code patch_matching_config} call (no EBX access - same discipline as
 * every other write-tool shape documented by this file's sibling classes, {@code MatchingPolicyTranslator}/
 * {@code MameDataModelResolution}).</b> {@code target = {policyCode, chain, position, survivorField}}; ops
 * {@code add_chain_entry}/{@code set_chain_entry}/{@code remove_chain_entry} take a {@code chain} of
 * {@code "record_selection"}, {@code "default_field_merge"} or {@code "survivor_field"} (the last one ALSO
 * carries {@code survivorField}, the field path the chain belongs to); {@code add_survivor_field}/
 * {@code remove_survivor_field} are whole-field create/delete, {@code target = {policyCode,
 * survivorField}} only - {@code add_survivor_field} is refused outright if the field already has an
 * override, so an EXISTING survivor field is only ever changed through {@code survivor_field} chain ops,
 * never re-created (an existing survivor's own {@code set_merge_function} shortcut is deliberately never
 * used here either - it is rejected against a chain of 2+ entries, so the chain-ops route is the only one
 * that works uniformly regardless of how many entries the desired chain has).
 * <p>
 * <b>"Several chain ops in one call apply in array order on ONE running chain - a removal renumbers later
 * positions."</b> This planner never emits a {@code remove_chain_entry} at a position a LATER op in the
 * same output still needs to address by index: the shared prefix (positions present in both the current and
 * the desired chain, 1-based) is always overwritten IN PLACE with {@code set_chain_entry} - a value change is
 * never expressed as remove-then-add - new entries are only ever ADDED past the chain's current end (via a
 * position-less {@code add_chain_entry}, which appends), and excess entries are only ever REMOVED from the
 * current end BACKWARDS (highest 1-based position first, descending). Because every remaining,
 * not-yet-processed position is strictly lower than the one just removed, no subsequent {@code position} in
 * the same list is ever invalidated by an earlier removal's renumbering.
 * <p>
 * <b>"The last entry of a required chain cannot be removed."</b> {@code record_selection}/{@code
 * default_field_merge} (and this factory's own {@code survivor_field} chains, which {@link
 * MatchingPolicyTranslator#buildMergePolicy} never builds with fewer than one entry) never have their last
 * remaining entry targeted by {@code remove_chain_entry} here, because a same-position VALUE change always
 * goes through {@code set_chain_entry} instead (see above) - the chain is never observed empty by an
 * intermediate op in the sequence. A survivor field disappearing entirely (present in the current config,
 * absent from the desired one) is expressed as a single {@code remove_survivor_field} on the WHOLE field,
 * never by draining its chain to zero entries through {@code survivor_field} ops.
 * <p>
 * Pure: no EBX class, Jackson only - unit-tested against a hand-built fixture (a plain {@code longest}/
 * {@code mostCompleted} config with no trusted-source chains yet - the shape `docs/MASTER-ZONE.md` &sect;1.1
 * recorded live on the real, unmodified {@code Companies} table - turning into the desired trusted-source
 * shape) rather than against a real {@code get_matching_config} answer, which this session never captured
 * for a merge policy's exact {@code mergePolicies[]} JSON (see the module's own final report). The tool's own
 * ACCEPTANCE of each corrected op is still unmeasured (no EBX access this session either) - only the shape
 * of the live refusal (the operations PipelineStep 107 recorded) and the tool description's stated contract
 * were available to fix against.
 */
public final class MergeConfigPatchPlanner
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MergeConfigPatchPlanner()
	{
	}

	/**
	 * @param pPolicyCode the merge policy's own {@code code} (patch target {@code policyCode}).
	 * @param pCurrentMergePolicy the merge policy object already located by the caller in a fresh
	 *            {@code get_matching_config} answer (see {@link #findMergePolicy}) - {@code null} (or any
	 *            {@code MissingNode}/absent-field chain under it) reads every chain/survivor as EMPTY, so
	 *            every desired entry is emitted as an addition. This planner never itself decides whether
	 *            that is the right thing to do for a policy that does not exist yet - the caller ({@code
	 *            ebx.MatchingConfigurator}) only reaches it once it already knows the table has an existing
	 *            configuration to patch (a fresh table goes through {@code generate_merge_policy} instead).
	 * @param pDesiredMergePolicy the FULL desired shape, exactly {@link
	 *            MatchingPolicyTranslator#buildMergePolicy}'s own output (this planner reads {@code
	 *            recordSelectionFunctionChain}/{@code defaultFieldMergeFunctionChain}/{@code survivors}
	 *            and, since lot M1, {@code autoCreateNewGoldenSource} off
	 *            it, nothing else - {@code code}/{@code mode}/{@code useForManualMerge}/{@code
	 *            applyPermission} are never patched by this route, only survivorship).
	 * @return {@code patch_matching_config}'s own {@code operations} array, in the exact order it must be
	 *         sent (see class Javadoc) - empty when the current shape already matches the desired one
	 *         exactly, so a genuinely idempotent re-apply sends nothing.
	 */
	public static ArrayNode plan(final String pPolicyCode, final JsonNode pCurrentMergePolicy, final ObjectNode pDesiredMergePolicy)
	{
		final JsonNode current = pCurrentMergePolicy == null ? MissingNode.getInstance() : pCurrentMergePolicy;
		final ArrayNode ops = MergeConfigPatchPlanner.MAPPER.createArrayNode();
		MergeConfigPatchPlanner.planGoldenSource(ops, pPolicyCode, MergeConfigPatchPlanner.text(current, "autoCreateNewGoldenSource"),
			MergeConfigPatchPlanner.text(pDesiredMergePolicy, "autoCreateNewGoldenSource"));
		MergeConfigPatchPlanner.planChain(ops, pPolicyCode, "record_selection", null, MergeConfigPatchPlanner.asList(current.path("recordSelectionFunctionChain")),
			MergeConfigPatchPlanner.asList(pDesiredMergePolicy.path("recordSelectionFunctionChain")));
		MergeConfigPatchPlanner.planChain(ops, pPolicyCode, "default_field_merge", null,
			MergeConfigPatchPlanner.asList(current.path("defaultFieldMergeFunctionChain")),
			MergeConfigPatchPlanner.asList(pDesiredMergePolicy.path("defaultFieldMergeFunctionChain")));
		MergeConfigPatchPlanner.planSurvivors(ops, pPolicyCode, current.path("survivors"), pDesiredMergePolicy.path("survivors"));
		return ops;
	}

	/**
	 * Finds the {@code data.mergePolicies[]} entry whose {@code code} equals {@code pPolicyCode} (root
	 * fallback for the array itself, same discipline as {@link MameAnswer#field}) in a raw {@code
	 * get_matching_config} answer - a {@link MissingNode} (never {@code null}, never throws) if the answer
	 * carries no such policy, which {@link #plan} then reads as "nothing configured yet for this policy".
	 */
	public static JsonNode findMergePolicy(final JsonNode pGetMatchingConfigAnswer, final String pPolicyCode)
	{
		final JsonNode mergePolicies = MameAnswer.field(pGetMatchingConfigAnswer, "mergePolicies");
		for (final JsonNode policy : mergePolicies)
		{
			if (pPolicyCode.equals(policy.path("code").asText(null)))
			{
				return policy;
			}
		}
		return MissingNode.getInstance();
	}

	/**
	 * Lot M1 (2026-10-17, `docs/MATCHING.md` sect;56): the source a golden record the add-on CREATES is stamped with. One {@code set_merge_policy_param}
	 * ({@code paramName = autoCreateNewGoldenSource}, {@code value} = a REGISTERED source code - the tool's own schema) when the stored value differs from the
	 * desired one; nothing when the desired policy carries none (a blank value would CLEAR it, so it is never sent). It replaces the former {@code constant}
	 * survivor on {@code /sourceSystem}, which the add-on applied to singleton records in place.
	 */
	private static void planGoldenSource(final ArrayNode pOps, final String pPolicyCode, final String pCurrent, final String pDesired)
	{
		if (pDesired.isEmpty() || pDesired.equals(pCurrent))
		{
			return;
		}
		final ObjectNode op = pOps.addObject();
		op.put("op", "set_merge_policy_param");
		final ObjectNode target = op.putObject("target");
		target.put("policyCode", pPolicyCode);
		target.put("paramName", "autoCreateNewGoldenSource");
		op.put("value", pDesired);
	}

	// ------------------------------------------------------------------------------------------------------- chains

	private static List<JsonNode> asList(final JsonNode pArray)
	{
		final List<JsonNode> out = new ArrayList<JsonNode>();
		for (final JsonNode entry : pArray)
		{
			out.add(entry);
		}
		return out;
	}

	/**
	 * Emits the minimal, order-safe {@code set_chain_entry}/{@code add_chain_entry}/{@code remove_chain_entry}
	 * sequence turning {@code pCurrent} into {@code pDesired} - see class Javadoc for why the shared prefix is
	 * always a same-position {@code set_chain_entry} (1-based), extra desired entries are appended with a
	 * position-LESS {@code add_chain_entry}, and excess current entries are removed from the current end
	 * backwards (highest 1-based position first).
	 *
	 * @param pSurvivorField null for {@code record_selection}/{@code default_field_merge}; the field path for a {@code survivor_field} chain.
	 */
	private static void planChain(final ArrayNode pOps, final String pPolicyCode, final String pChain, final String pSurvivorField, final List<JsonNode> pCurrent,
		final List<JsonNode> pDesired)
	{
		final int overlap = Math.min(pCurrent.size(), pDesired.size());
		for (int i = 0; i < overlap; i++)
		{
			if (!MergeConfigPatchPlanner.sameEntry(pCurrent.get(i), pDesired.get(i)))
			{
				// 1-based: this entry already exists at (0-based) index i, i.e. position i+1
				pOps.add(MergeConfigPatchPlanner.chainOp("set_chain_entry", pPolicyCode, pChain, pSurvivorField, Integer.valueOf(i + 1), pDesired.get(i)));
			}
		}
		for (int i = pCurrent.size(); i < pDesired.size(); i++)
		{
			// appended past the current end - position OMITTED, per contract ("appends when target.position is omitted");
			// a stale post-append index would be wrong the moment more than one entry is appended in the same call
			pOps.add(MergeConfigPatchPlanner.chainOp("add_chain_entry", pPolicyCode, pChain, pSurvivorField, null, pDesired.get(i)));
		}
		// removed from the current END backwards (1-based, highest position first) - see class Javadoc for why this
		// never invalidates a position a later op in this same list still needs to address
		for (int i = pCurrent.size() - 1; i >= pDesired.size(); i--)
		{
			pOps.add(MergeConfigPatchPlanner.chainOp("remove_chain_entry", pPolicyCode, pChain, pSurvivorField, Integer.valueOf(i + 1), null));
		}
	}

	/**
	 * @param pEntry the chain entry this op carries as its {@code value} - {@code null} for a
	 *            {@code remove_chain_entry} (no value at all). Never a raw {@link JsonNode} object in the
	 *            emitted {@code value} field - see {@link #entryValueString} and the class Javadoc's
	 *            2026-09-23 01:10 measurements for why every {@code value} this method emits is a JSON
	 *            STRING, never a JSON object.
	 */
	private static ObjectNode chainOp(final String pOp, final String pPolicyCode, final String pChain, final String pSurvivorField, final Integer pPosition,
		final JsonNode pEntry)
	{
		final ObjectNode op = MergeConfigPatchPlanner.MAPPER.createObjectNode();
		op.put("op", pOp);
		final ObjectNode target = op.putObject("target");
		target.put("policyCode", pPolicyCode);
		target.put("chain", pChain);
		if (pSurvivorField != null)
		{
			target.put("survivorField", pSurvivorField);
		}
		if (pPosition != null)
		{
			target.put("position", pPosition.intValue());
		}
		if (pEntry != null)
		{
			op.put("value", MergeConfigPatchPlanner.entryValueString(pEntry));
		}
		return op;
	}

	/**
	 * The live contract's own "{@code value} is a JSON STRING" rule - measured 2026-09-23 01:10 (class
	 * Javadoc): a raw JSON object {@code value} is refused outright ("Invalid operations payload"), a bare
	 * function-name STRING is accepted for a parameter-less entry, and the entry object SERIALIZED TO A JSON
	 * STRING (not set as a nested object) is accepted for one that carries {@code constantValue}/{@code
	 * dateFieldPath}. This is the one, uniform rule for {@code set_chain_entry}/{@code add_chain_entry}'s
	 * {@code value} on every chain this class writes to ({@code record_selection}, {@code
	 * default_field_merge}, {@code survivor_field}) - there is no shortcut for a parameter-less entry other
	 * than sending its bare function name.
	 */
	private static String entryValueString(final JsonNode pEntry)
	{
		if (!MergeConfigPatchPlanner.hasChainEntryParams(pEntry))
		{
			return MergeConfigPatchPlanner.text(pEntry, "function");
		}
		try
		{
			return MergeConfigPatchPlanner.MAPPER.writeValueAsString(pEntry);
		}
		catch (final JsonProcessingException ex)
		{
			// pEntry is always a plain {function, constantValue?, dateFieldPath?} object this codebase itself
			// built (MatchingPolicyTranslator's own output, or a chain entry copied straight off it) - Jackson
			// cannot fail to serialize it; this exists only so the method signature stays checked-exception-free
			// for its callers, same discipline as the rest of this pure, non-EBX class.
			throw new IllegalStateException("Failed to serialize a merge-policy chain entry to a JSON string for patch_matching_config: " + pEntry, ex);
		}
	}

	/**
	 * Compares only {@code function}/{@code constantValue}/{@code dateFieldPath} - ignores any extra key a
	 * live answer might carry that this factory never writes (e.g. a technical id), so a harmless extra field
	 * never triggers a spurious {@code set_chain_entry}. {@code dateFieldPath} is compared through {@link
	 * #normalizedDateFieldPath} (the add-on's own {@code "System date"} default collapses to the same "unset"
	 * value an absent one already normalizes to - class Javadoc, 2026-09-23 01:10).
	 */
	private static boolean sameEntry(final JsonNode pCurrent, final JsonNode pDesired)
	{
		return MergeConfigPatchPlanner.text(pCurrent, "function").equals(MergeConfigPatchPlanner.text(pDesired, "function"))
			&& MergeConfigPatchPlanner.text(pCurrent, "constantValue").equals(MergeConfigPatchPlanner.text(pDesired, "constantValue"))
			&& MergeConfigPatchPlanner.normalizedDateFieldPath(pCurrent).equals(MergeConfigPatchPlanner.normalizedDateFieldPath(pDesired));
	}

	/** True when {@code pEntry} carries a {@code constantValue} or a (non-{@code "System date"}, see {@link #normalizedDateFieldPath}) {@code dateFieldPath} - the two params {@code add_survivor_field}'s bare-string {@code value} cannot express, so a first entry with either needs a follow-up {@code survivor_field} {@code set_chain_entry} (see class Javadoc, the live {@code /sourceSystem} {@code constant("MDM")} case). */
	private static boolean hasChainEntryParams(final JsonNode pEntry)
	{
		return !MergeConfigPatchPlanner.text(pEntry, "constantValue").isEmpty() || !MergeConfigPatchPlanner.normalizedDateFieldPath(pEntry).isEmpty();
	}

	/**
	 * {@code "System date"} is the add-on's OWN default, silently stamped onto a date-aware entry (e.g. {@code
	 * lastUpdate}) that was created with no {@code dateFieldPath} at all - measured live 2026-09-23 01:10
	 * (class Javadoc): a {@code get_matching_config} read-back of an entry this class itself wrote with none
	 * comes back carrying {@code "System date"}, which must compare EQUAL to "no {@code dateFieldPath}" or
	 * {@link #sameEntry} emits a spurious rewrite of an already-correct entry forever (every subsequent
	 * read-back keeps carrying the add-on's own default). This class never emits {@code "System date"} itself
	 * - it only ever needs to recognize it on the CURRENT side of a comparison.
	 */
	private static String normalizedDateFieldPath(final JsonNode pEntry)
	{
		final String raw = MergeConfigPatchPlanner.text(pEntry, "dateFieldPath");
		return "System date".equals(raw) ? "" : raw;
	}

	/**
	 * {@code pField}'s text value, normalizing BOTH an absent field and an EXPLICIT JSON {@code null} to
	 * {@code ""} - measured live 2026-09-23 01:10 (class Javadoc): a real {@code get_matching_config} answer
	 * stores {@code constantValue} and {@code dateFieldPath} as explicit JSON {@code null} on a plain entry
	 * (docs/MATCHING.md &sect;14.2's own recorded shape), and {@link com.fasterxml.jackson.databind.node.NullNode
	 * NullNode}{@code #asText()} returns the four-character STRING {@code "null"} - never actual Java {@code
	 * null} - so the one-argument {@code asText(String)} default-value fallback (which only fires when the
	 * zero-argument {@code asText()} itself returns {@code null}) never kicks in for an explicit JSON null,
	 * unlike for a {@link MissingNode}, whose {@code asText()} DOES return {@code null}. Without this,
	 * comparing a stored explicit {@code null} against a desired entry that never set the field at all (which
	 * reads as {@code ""}) sees {@code "null" != ""} and treats two functionally-identical entries as
	 * different.
	 */
	private static String text(final JsonNode pNode, final String pField)
	{
		final JsonNode value = pNode.path(pField);
		return value.isMissingNode() || value.isNull() ? "" : value.asText("");
	}

	// ---------------------------------------------------------------------------------------------------- survivors

	private static void planSurvivors(final ArrayNode pOps, final String pPolicyCode, final JsonNode pCurrentSurvivors, final JsonNode pDesiredSurvivors)
	{
		final Map<String, JsonNode> currentByField = new LinkedHashMap<String, JsonNode>();
		for (final JsonNode survivor : pCurrentSurvivors)
		{
			final String field = survivor.path("field").asText(null);
			if (field != null)
			{
				currentByField.put(field, survivor.path("mergeFunctionChain"));
			}
		}
		final Set<String> desiredFields = new LinkedHashSet<String>();
		for (final JsonNode survivor : pDesiredSurvivors)
		{
			final String field = survivor.path("field").asText(null);
			if (field == null)
			{
				continue;
			}
			desiredFields.add(field);
			final JsonNode desiredChain = survivor.path("mergeFunctionChain");
			if (!currentByField.containsKey(field))
			{
				MergeConfigPatchPlanner.planNewSurvivorField(pOps, pPolicyCode, field, MergeConfigPatchPlanner.asList(desiredChain));
			}
			else
			{
				MergeConfigPatchPlanner.planChain(pOps, pPolicyCode, "survivor_field", field, MergeConfigPatchPlanner.asList(currentByField.get(field)),
					MergeConfigPatchPlanner.asList(desiredChain));
			}
		}
		for (final Map.Entry<String, JsonNode> entry : currentByField.entrySet())
		{
			if (!desiredFields.contains(entry.getKey()))
			{
				final ObjectNode op = pOps.addObject();
				op.put("op", "remove_survivor_field");
				final ObjectNode target = op.putObject("target");
				target.put("policyCode", pPolicyCode);
				target.put("survivorField", entry.getKey());
			}
		}
	}

	/**
	 * A survivor field the current config has no override for yet: {@code add_survivor_field}'s {@code value}
	 * is a bare merge-FUNCTION-NAME STRING (never {@code {mergeFunctionChain:[...]}}, the shape the live tool
	 * refused - see class Javadoc), so it can only ever create a single, parameter-less entry. When the
	 * DESIRED chain needs more than that - its first entry carries {@code constantValue}/{@code
	 * dateFieldPath}, or it has further entries - this completes the chain right after with {@code
	 * survivor_field} chain ops: a {@code set_chain_entry} at position 1 to attach the first entry's own
	 * parameter(s) (never omitted just because {@code add_survivor_field} already "created" that slot - the
	 * created slot never carries the parameter), then a position-less {@code add_chain_entry} per remaining
	 * entry, in order.
	 */
	private static void planNewSurvivorField(final ArrayNode pOps, final String pPolicyCode, final String pField, final List<JsonNode> pDesiredChain)
	{
		if (pDesiredChain.isEmpty())
		{
			// MatchingPolicyTranslator#buildMergePolicy never builds an empty survivor chain - guarded defensively,
			// same discipline as this class' other reads of caller-supplied JSON - nothing to add for an empty one.
			return;
		}
		final JsonNode firstEntry = pDesiredChain.get(0);
		final ObjectNode op = pOps.addObject();
		op.put("op", "add_survivor_field");
		final ObjectNode target = op.putObject("target");
		target.put("policyCode", pPolicyCode);
		target.put("survivorField", pField);
		op.put("value", MergeConfigPatchPlanner.text(firstEntry, "function"));
		if (MergeConfigPatchPlanner.hasChainEntryParams(firstEntry))
		{
			pOps.add(MergeConfigPatchPlanner.chainOp("set_chain_entry", pPolicyCode, "survivor_field", pField, Integer.valueOf(1), firstEntry));
		}
		for (int i = 1; i < pDesiredChain.size(); i++)
		{
			pOps.add(MergeConfigPatchPlanner.chainOp("add_chain_entry", pPolicyCode, "survivor_field", pField, null, pDesiredChain.get(i)));
		}
	}
}
