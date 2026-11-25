package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot 2, cascade (2026-09-23, `docs/MATCHING.md` &sect;12.4/&sect;22): decides the DESIRED {@code alignForeignKeys} value
 * of every entry a merge policy's own {@code relatedTables[]} already carries - for every related table that is a
 * PROMOTED CHILD entity of the same project (a table holding a foreign key to the entity being merged -
 * {@code master.MasterModel.Result#childTablePaths}) the value is {@link #WANTED}, left EXACTLY as stored for
 * everything else (a table belonging to another model/project, or one this generator never promoted).
 * <p>
 * <b>Lot MT (2026-10-04): {@link #WANTED} is now {@code NONE}, no longer {@code AUTOMATIC}.</b> Measured live on
 * 6.3.0-rc2 (project Vol1mF, 576,692 persons, 889,165 addresses): with a related table in {@code AUTOMATIC}, an
 * automatic merge at volume fails inside the add-on with {@code LxiDuplicateKeyException: Duplicate key ...
 * /root/CascadingRecords} and the whole run is rolled back (product defect, reported); the same run with {@code NONE}
 * completes (180,746 groups merged, no false merge). The Factory does not need the add-on's cascade: at the next
 * master load {@code MasterLoader} re-points every child row to the golden itself (counter {@code referencesToGolden},
 * 637,795 addresses re-pointed on that project). <b>To go back to the add-on's cascade once the defect is fixed,
 * change {@link #WANTED} to {@link #AUTOMATIC}</b> - nothing else: the comparator, the rewrite builder and the
 * configurator all read this one constant, and a re-configure then rewrites every stored child entry accordingly.
 * <p>
 * <b>This class never adds or removes a {@code relatedTables[]} entry.</b> {@code generate_merge_policy} is
 * the only tool that ever adds one (every inverse-FK table, {@code MANUAL} by default), and no
 * {@code patch_matching_config} op targets the list at all (`docs/MATCHING.md` &sect;12.4/&sect;22, measured against the
 * schema's own {@code patchOps} catalogue) - so the add-on, not this generator, owns membership of the list.
 * {@link #align} only ever rewrites the {@code alignForeignKeys} enum of an entry ALREADY present, matched by
 * {@code path} - never {@code name} (two related tables could in principle share a display name; {@code path}
 * is the add-on's own unique key for the entry, same convention {@code MatchingPolicyComparator}'s own
 * path-keyed indexing already uses for {@code fields[]}/{@code survivors[]}).
 * <p>
 * Pure Jackson, no EBX class, no MCP call - same convention as every other class in this package
 * ({@code MatchingConfigRewriteBuilder}, {@code MatchingPolicyComparator}).
 */
public final class RelatedTableAlignment
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** {@code matching_config_schema}'s own {@code alignForeignKeys} enum is {@code MANUAL|AUTOMATIC|NONE}; the add-on re-points a merged record's children to the golden itself. Not wanted today (lot MT, see the class Javadoc), kept as the value to go back to. */
	public static final String AUTOMATIC = "AUTOMATIC";
	/** The ONE value this class ever writes on a promoted child's entry: {@code NONE} - the Factory re-points the children at the master load (lot MT, see the class Javadoc). */
	public static final String WANTED = "NONE";

	private RelatedTableAlignment()
	{
	}

	/**
	 * @param pStoredRelatedTables the merge policy's own {@code relatedTables[]}, exactly as a live
	 *            {@code get_matching_config} (or a just-completed {@code generate_merge_policy}) answer carries
	 *            it - {@code null}/not-an-array reads as empty, never throws.
	 * @param pChildTablePaths every promoted CHILD entity's own table path (e.g. {@code /root/Contact}) of the
	 *            entity this merge policy belongs to ({@code MasterModel.Result#childTablePaths}). {@code null}
	 *            is treated as the empty set.
	 * @return a NEW array, same length and same entry order as {@code pStoredRelatedTables}: every entry whose
	 *         {@code path} is one of {@code pChildTablePaths} has its {@code alignForeignKeys} set to
	 *         {@link #WANTED}; every other entry is copied VERBATIM - {@code name}/{@code path}/{@code
	 *         alignForeignKeys} all untouched, whatever the stored enum happens to be ({@code MANUAL},
	 *         {@code AUTOMATIC} or {@code NONE}).
	 */
	public static ArrayNode align(final JsonNode pStoredRelatedTables, final Set<String> pChildTablePaths)
	{
		final ArrayNode out = RelatedTableAlignment.MAPPER.createArrayNode();
		if (pStoredRelatedTables == null || !pStoredRelatedTables.isArray())
		{
			return out;
		}
		final Set<String> children = pChildTablePaths == null ? new LinkedHashSet<String>() : pChildTablePaths;
		for (final JsonNode entry : pStoredRelatedTables)
		{
			final ObjectNode copy = entry.deepCopy();
			final String path = entry.path("path").asText(null);
			if (path != null && !path.trim().isEmpty() && children.contains(path))
			{
				copy.put("alignForeignKeys", RelatedTableAlignment.WANTED);
			}
			out.add(copy);
		}
		return out;
	}

	/**
	 * The {@code path} of every entry {@link #align} would actually FLIP to {@link #WANTED} - i.e. a
	 * promoted child entity's own entry that is not already {@link #WANTED} in {@code pStoredRelatedTables}
	 * ({@code MANUAL} and {@code AUTOMATIC} both flip).
	 * {@code ebx.MatchingConfigurator}'s own reporting hook (a step's recorded detail/message) and the signal
	 * for whether a follow-up {@code apply_matching_config} write is even needed at all - see
	 * {@link #changesAnything}.
	 */
	public static List<String> alignedPaths(final JsonNode pStoredRelatedTables, final Set<String> pChildTablePaths)
	{
		final List<String> out = new ArrayList<String>();
		if (pStoredRelatedTables == null || !pStoredRelatedTables.isArray())
		{
			return out;
		}
		final Set<String> children = pChildTablePaths == null ? new LinkedHashSet<String>() : pChildTablePaths;
		for (final JsonNode entry : pStoredRelatedTables)
		{
			final String path = entry.path("path").asText(null);
			if (path != null && !path.trim().isEmpty() && children.contains(path)
				&& !RelatedTableAlignment.WANTED.equals(entry.path("alignForeignKeys").asText(null)))
			{
				out.add(path);
			}
		}
		return out;
	}

	/**
	 * Lot MT fix (2026-10-04): the same selection as {@link #alignedPaths} but KEEPING the value found, so a configure can
	 * record what it read before writing and name the offending table in a failure message.
	 * <p>
	 * Why it exists: measured live on Vol100k, a REWRITE sends the merge policy with {@code relatedTables: []} (the stored
	 * policy had no entry at that moment) and the add-on re-adds every inverse-foreign-key table as {@code MANUAL} on its own;
	 * only a read AFTER the write shows the entry that really stands. The configurator therefore reads the configuration back
	 * after the branch's writes, aligns what that read shows and verifies it again - never trusting the list it sent. An entry
	 * that is ABSENT from {@code pStoredRelatedTables} is not listed (nothing to align; {@link RelatedTableAlignment} never
	 * adds an entry, the add-on owns membership of the list).
	 *
	 * @return {@code path} to the {@code alignForeignKeys} value found ({@code "<missing>"} when the entry carries none),
	 *         in the stored order, for every promoted child entry whose value is not {@link #WANTED}.
	 */
	public static Map<String, String> mismatches(final JsonNode pStoredRelatedTables, final Set<String> pChildTablePaths)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pStoredRelatedTables == null || !pStoredRelatedTables.isArray())
		{
			return out;
		}
		final Set<String> children = pChildTablePaths == null ? new LinkedHashSet<String>() : pChildTablePaths;
		for (final JsonNode entry : pStoredRelatedTables)
		{
			final String path = entry.path("path").asText(null);
			if (path != null && !path.trim().isEmpty() && children.contains(path) && !RelatedTableAlignment.WANTED.equals(entry.path("alignForeignKeys").asText(null)))
			{
				out.put(path, entry.path("alignForeignKeys").asText("<missing>"));
			}
		}
		return out;
	}

	/** {@code true} when {@link #alignedPaths} is non-empty - whether {@link #align} would change anything at all against {@code pStoredRelatedTables} as it currently stands. */
	public static boolean changesAnything(final JsonNode pStoredRelatedTables, final Set<String> pChildTablePaths)
	{
		return !RelatedTableAlignment.alignedPaths(pStoredRelatedTables, pChildTablePaths).isEmpty();
	}
}
