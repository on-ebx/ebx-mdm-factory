package com.on.ebx.factory.distribution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Volume-operations lot 8a (2026-09-28, task 3): a per-JVM cache of "does this project have at least one
 * ARMED REALTIME {@code DistributionTarget}" - the fact {@code ebx.DistributionTriggerBody}'s generated
 * trigger script re-derives with a live SQL query on EVERY create/modify/delete of a master row (task brief's
 * own evidence: 5 million queries during one load, even while every target of the project was PAUSED).
 * <p>
 * <b>What this class does NOT fix, and why - read before wiring this in anywhere new.</b> The trigger the task
 * brief targets is EBX SCRIPT, authored via {@code ebx-scripting}'s {@code update_script} and declared via the
 * {@code data-model} server's {@code set_script_trigger} ({@code ebx.DistributionTriggerInstaller}) - it runs
 * inside EBX's own script engine, which has no sanctioned way to call an arbitrary custom Java static method
 * (CLAUDE.md sec 2: a script's only capabilities are the fixed {@code core.*} built-ins plus other, ALSO
 * script-authored, libraries via {@code SCRIPT_LIBRARY_ACCESS} - never a Java class of this or any other
 * module). This cache therefore CANNOT be consulted from {@code DistributionTriggerBody}'s generated text as
 * literally asked; {@code DistributionTriggerBody}'s SQL is unchanged by this lot. Closing that gap for real
 * would need either (a) redeclaring the trigger as a plain {@code osd:trigger class="..."} Java trigger instead
 * of a script one (a data-model/schema-declaration change - this lot's own instructions say to stop and report
 * rather than make one), or (b) a denormalized "any target armed" flag on {@code Project} itself, cheap enough
 * for a script to read without a query (a schema (XSD) change to {@code MdmFactory.xsd}, owned by the sibling
 * lot on {@code feature/volume-loader-memory} per this lot's own file-ownership boundary - not attempted here).
 * Both are flagged for the coordinator; neither is implemented in this lot.
 * <p>
 * What this class IS for: any JAVA-side caller (today, none is hot enough to need it - {@link
 * com.on.ebx.factory.ebx.DistributionDispatcher}'s own drain pass already reads {@code DistributionTarget}
 * once per pass, not once per record) that would otherwise re-query {@code DistributionTarget} per project on
 * a path called often. {@link #get} answers a cached value (an explicit {@link #put}, invalidated by {@link
 * #invalidate} from every write path that changes a target's {@code state}/{@code mode} -
 * {@code DistributionConfigurator}, {@code DistributionPause}, {@code DistributionReader#setState}/{@code
 * #upsertTarget}, {@code DistributionRunner}'s own state writes) OR falls back to a bounded TTL ({@link
 * #TTL_MILLIS}, 30 s) so a missed invalidation is never wrong for more than that window. Pure, no EBX
 * dependency, {@code pNow} always passed in so it is testable without the wall clock.
 */
public final class ArmedTargetCache
{
	/** A missed/forgotten {@link #invalidate} call is wrong for at most this long. */
	public static final long TTL_MILLIS = 30_000L;

	private static final class Entry
	{
		final boolean armed;
		final long at;

		Entry(final boolean pArmed, final long pAt)
		{
			this.armed = pArmed;
			this.at = pAt;
		}
	}

	private static final Map<String, Entry> CACHE = new ConcurrentHashMap<String, Entry>();

	private ArmedTargetCache()
	{
	}

	/** The cached value for {@code pProjectId}, or {@code null} on a miss/expired entry (the caller must then compute it fresh and {@link #put} the result). */
	public static Boolean get(final String pProjectId, final long pNow)
	{
		if (pProjectId == null)
		{
			return null;
		}
		final Entry entry = ArmedTargetCache.CACHE.get(pProjectId);
		if (entry == null || pNow - entry.at >= ArmedTargetCache.TTL_MILLIS)
		{
			return null;
		}
		return Boolean.valueOf(entry.armed);
	}

	/** Caches {@code pArmed} for {@code pProjectId}, timestamped {@code pNow} - expires after {@link #TTL_MILLIS} even without an explicit {@link #invalidate}. */
	public static void put(final String pProjectId, final boolean pArmed, final long pNow)
	{
		if (pProjectId != null)
		{
			ArmedTargetCache.CACHE.put(pProjectId, new Entry(pArmed, pNow));
		}
	}

	/** Forgets any cached value for {@code pProjectId} - called from every write path that can change whether a REALTIME target of it is ARMED. A no-op when nothing was cached. */
	public static void invalidate(final String pProjectId)
	{
		if (pProjectId != null)
		{
			ArmedTargetCache.CACHE.remove(pProjectId);
		}
	}

	/** Test/diagnostic only: drops every cached entry. */
	public static void invalidateAll()
	{
		ArmedTargetCache.CACHE.clear();
	}
}
