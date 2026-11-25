package com.on.ebx.factory.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * "Cross-references of a golden record" (the product owner's request, 2026-09-23): a flat, one-row-per-{@code Xref}
 * projection of what {@link RecordLineage#assemble}/{@code ebx.RecordLineageReader#read} already computed -
 * every source that fed the record being looked at, the golden's own row (if any - an auto-created golden
 * has none) plus every record merged into it.
 * <p>
 * <b>A projection, never a second read.</b> The lineage screen and its REST route (
 * {@code GET /projects/{code}/lineage/{entity}}) already resolve, for a golden record, EVERY contributing
 * master record ({@code contributingKeys}: the golden plus every record whose {@code RecordMetadata.goldenId}
 * is that golden) and each one's own {@code Xref}-resolved chain ({@link RecordLineage.SourceChain} - masterKey,
 * golden flag, sourceSystem, sourceId, landing dataset/table/key, load id, received-at, staging table/key,
 * "open in EBX" links, the {@link RecordLineage.SourceChain#restricted} flag). This class reads exactly that
 * JSON's own {@code sources} array (never {@code suspectSources} - a pending suspect is not yet a confirmed
 * contributor, so it is not a cross-reference at all, the same distinction {@code RecordLineage}'s own
 * Javadoc already draws for the "champ par champ" field table) and reshapes it into the flat row shape a
 * cross-references table/API needs, deliberately duplicating NOTHING {@code ebx.RecordLineageReader} already
 * decided: permission redaction, the golden/anchor determination, the {@code Xref} resolution itself - all of
 * it already happened before this class ever sees the JSON. This is why both the "Cross-references" panel on
 * the Record lineage screen ({@code RecordLineagePage#renderCrossReferencesPanel}) and the dedicated REST
 * route ({@code rest.FactoryResource#recordCrossReferences}, {@code GET /projects/{code}/xrefs/{entity}}) call
 * {@link #project(JsonNode)} on the exact same {@code RecordLineageReader.read(...)} answer the {@code
 * /lineage} route itself returns - same permission behaviour, same 404/400 answers for an unknown key, by
 * construction rather than by two implementations happening to agree.
 * <p>
 * <b>Pure, like {@link RecordLineage}</b>: no EBX class anywhere in this file, Jackson only - built and
 * unit-tested (see {@code CrossReferencesTest}) without an EBX runtime.
 * <p>
 * <b>Redaction is inherited, not re-decided here.</b> A restricted source already carries nothing but {@code
 * masterKey}/{@code golden}/{@code sourceSystem}/{@code restricted:true} in the JSON this class reads ({@code
 * RecordLineage#assemble}'s own all-or-nothing rule) - {@link #projectSource} simply stops reading once it
 * sees {@code restricted:true}, the same "never even look for a value that should not be there" discipline
 * the rest of this module follows, rather than trusting the source JSON to already be clean and reading
 * every field regardless.
 * <p>
 * <b>{@code goldenKey} here is the LIVE, per-record merge answer already in the JSON
 * ({@code merge.goldenKey}, resolved through {@code RecordMetadata.goldenId} at render time) - never the
 * factory's own {@code Xref.goldenKey} column.</b> {@code docs/MASTER-ZONE.md} &sect;3.4 explains why that
 * column is deliberately left uncached/unfilled by this lot: the current golden of a source record can
 * change on every later merge/unmerge, and {@code RecordMetadata.goldenId} already answers it correctly at
 * read time without a second copy to keep in sync. This class does not read or write {@code Xref} at all -
 * it only re-shapes what {@code ebx.RecordLineageReader} already resolved.
 */
public final class CrossReferences
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private CrossReferences()
	{
	}

	/**
	 * Projects a {@code RecordLineage#assemble}/{@code RecordLineageReader.read} answer into the
	 * cross-references shape:
	 *
	 * <pre>
	 * {
	 *   "project": "Companies", "entity": "Company", "masterTable": "/root/Company", "masterKey": "12",
	 *   "present": true, "state": "GOLDEN", "goldenKey": "12",
	 *   "xrefs": [
	 *     { "masterKey": "12", "golden": true, "sourceSystem": "ERP", "sourceId": "SUP-00231",
	 *       "stagingTable": "Company", "stagingKey": "ERP|SUP-00231", "landingDataset": "ERP",
	 *       "landingTable": "SUPPLIER", "landingKey": "SUP-00231", "loadId": "L20260920-131535-1",
	 *       "receivedAt": "2026-09-20T13:15:35Z", "restricted": false,
	 *       "landingUrl": "...", "stagingUrl": "..." },
	 *     { "masterKey": "7", "golden": false, "sourceSystem": "CRM", "restricted": true }
	 *   ]
	 * }
	 * </pre>
	 *
	 * {@code present}/{@code state}/{@code goldenKey} mirror {@code merge.present}/{@code merge.state}/
	 * {@code merge.goldenKey} of the source JSON - null/absent {@code state}/{@code goldenKey} whenever
	 * {@code present} is false (see {@link RecordLineage.MergeInfo}'s own Javadoc for the four reasons that
	 * can be: matching never ran, the add-on is unavailable, this record has no metadata yet, or the add-on's
	 * own metadataset could not be read). {@code xrefs} is one entry per {@code sources[]} entry of the
	 * source JSON, in the SAME order - never sorted, filtered or de-duplicated here, since {@code
	 * ebx.RecordLineageReader} already bounds and orders that list.
	 * <p>
	 * {@code landingUrl}/{@code stagingUrl} are carried through in addition to the minimal field set a caller
	 * needs to identify a row ({@code masterKey}/{@code golden}/{@code sourceSystem}/{@code sourceId}/{@code
	 * stagingTable}/{@code stagingKey}/{@code landingDataset}/{@code landingTable}/{@code landingKey}/{@code
	 * loadId}/{@code receivedAt}/{@code restricted}) - the Record lineage screen's own "Cross-references"
	 * panel needs them for its "open in EBX" links, and this class is the ONE place both the screen and the
	 * REST route read a cross-reference row from, so leaving them out here would force the screen to read
	 * {@code sources[]} a second time, duplicating this exact projection. Absent (never emitted) whenever the
	 * reader could not build one (see {@code ebx.RecordLineageReader}'s own Javadoc on {@code openUrl}) or the
	 * row is restricted.
	 */
	public static ObjectNode project(final JsonNode pLineage)
	{
		final ObjectNode out = CrossReferences.MAPPER.createObjectNode();
		out.put("project", CrossReferences.textOrNull(pLineage, "project"));
		out.put("entity", CrossReferences.textOrNull(pLineage, "entity"));
		out.put("masterTable", CrossReferences.textOrNull(pLineage, "masterTable"));
		out.put("masterKey", CrossReferences.textOrNull(pLineage, "masterKey"));

		final JsonNode merge = pLineage.path("merge");
		final boolean present = merge.path("present").asBoolean(false);
		out.put("present", present);
		out.put("state", present ? CrossReferences.textOrNull(merge, "state") : null);
		out.put("goldenKey", present ? CrossReferences.textOrNull(merge, "goldenKey") : null);

		final ArrayNode xrefs = out.putArray("xrefs");
		for (final JsonNode source : pLineage.path("sources"))
		{
			xrefs.add(CrossReferences.projectSource(source));
		}
		return out;
	}

	/**
	 * One {@code sources[]} entry, reshaped flat - see {@link #project}'s own Javadoc for the exact fields.
	 * Stops reading as soon as {@code restricted} is seen (matching {@code RecordLineage#buildSourceNode}'s
	 * own early return): a restricted entry in the source JSON never carries anything beyond {@code
	 * masterKey}/{@code golden}/{@code sourceSystem} to begin with, so there is nothing else to project.
	 */
	private static ObjectNode projectSource(final JsonNode pSource)
	{
		final ObjectNode node = CrossReferences.MAPPER.createObjectNode();
		node.put("masterKey", CrossReferences.textOrNull(pSource, "masterKey"));
		node.put("golden", pSource.path("golden").asBoolean(false));
		node.put("sourceSystem", CrossReferences.textOrNull(pSource, "sourceSystem"));
		final boolean restricted = pSource.path("restricted").asBoolean(false);
		node.put("restricted", restricted);
		if (restricted)
		{
			return node;
		}
		node.put("sourceId", CrossReferences.textOrNull(pSource, "sourceId"));
		final JsonNode staging = pSource.path("staging");
		node.put("stagingTable", CrossReferences.textOrNull(staging, "table"));
		node.put("stagingKey", CrossReferences.textOrNull(staging, "key"));
		final JsonNode landing = pSource.path("landing");
		node.put("landingDataset", CrossReferences.textOrNull(landing, "dataset"));
		node.put("landingTable", CrossReferences.textOrNull(landing, "table"));
		node.put("landingKey", CrossReferences.textOrNull(landing, "key"));
		node.put("loadId", CrossReferences.textOrNull(landing, "loadId"));
		node.put("receivedAt", CrossReferences.textOrNull(landing, "receivedAt"));
		final String landingUrl = CrossReferences.textOrNull(landing, "url");
		if (landingUrl != null)
		{
			node.put("landingUrl", landingUrl);
		}
		final String stagingUrl = CrossReferences.textOrNull(staging, "url");
		if (stagingUrl != null)
		{
			node.put("stagingUrl", stagingUrl);
		}
		return node;
	}

	/** {@code pNode.path(pField).asText()} when the field is present and non-null, {@code null} otherwise - same idiom as {@code RecordLineagePage#textOrNull}. */
	private static String textOrNull(final JsonNode pNode, final String pField)
	{
		return pNode.hasNonNull(pField) ? pNode.path(pField).asText() : null;
	}
}
