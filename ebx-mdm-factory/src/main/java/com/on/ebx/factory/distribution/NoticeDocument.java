package com.on.ebx.factory.distribution;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lot S2 (2026-10-18): the ONE convention of the messages a distribution target receives, so a consumer can tell what a message means from its
 * payload alone (the messages carry no Kafka header: a header needs a Connectors lot that has not shipped).
 * <ul>
 * <li>Every message has a {@code _change} member. A record the mapping sends ({@code CREATE} and {@code MODIFY} alike) is the record's FULL current
 * state, built by Connectors at send time, with {@code _change = "UPSERT"} (a mapping rule, {@link DistributionMappingRules#CHANGE_FIELD}): Connectors
 * reads the record when it sends, so the Factory cannot tell the consumer whether it was a creation or a change, and the consumer does not need to
 * (the message replaces what it holds for {@code id}).</li>
 * <li>A message the Factory builds itself carries {@code id} (the record's formatted key, also the Kafka message key), {@code _change} and {@code at}
 * (the instant it was sent): {@code RETIRE} adds {@code retired: true}, {@code retiredTo} (the golden the record was merged into; absent when none),
 * {@code reason} ({@code MERGE} or {@code UNMERGE}) and {@code mergeId}; {@code DELETE} adds {@code deleted: true}.</li>
 * </ul>
 * Before this lot a {@code DELETE} was never delivered (it was handed to {@code connectors_sync}, which answers "No record for key" for a record that is
 * gone) and none of the messages said what they meant. Pure, unit-tested.
 */
public final class NoticeDocument
{
	public static final String CHANGE_MEMBER = "_change";
	public static final String UPSERT = "UPSERT";

	private NoticeDocument()
	{
	}

	/** Member order is the order of the JSON text. */
	public static Map<String, Object> retire(final String pRecordKey, final String pSurvivorKey, final String pReason, final String pMergeId, final String pAt)
	{
		final Map<String, Object> out = new LinkedHashMap<String, Object>();
		out.put(NoticeDocument.CHANGE_MEMBER, EventCoalescer.RETIRE);
		out.put("id", pRecordKey);
		out.put("retired", Boolean.TRUE);
		if (pSurvivorKey != null && !pSurvivorKey.trim().isEmpty())
		{
			out.put("retiredTo", pSurvivorKey);
		}
		if (pReason != null && !pReason.trim().isEmpty())
		{
			out.put("reason", pReason);
		}
		if (pMergeId != null && !pMergeId.trim().isEmpty())
		{
			out.put("mergeId", pMergeId);
		}
		out.put("at", pAt);
		return out;
	}

	public static Map<String, Object> delete(final String pRecordKey, final String pAt)
	{
		final Map<String, Object> out = new LinkedHashMap<String, Object>();
		out.put(NoticeDocument.CHANGE_MEMBER, EventCoalescer.DELETE);
		out.put("id", pRecordKey);
		out.put("deleted", Boolean.TRUE);
		out.put("at", pAt);
		return out;
	}
}
