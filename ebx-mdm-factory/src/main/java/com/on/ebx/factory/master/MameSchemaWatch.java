package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.on.ebx.factory.core.ModuleMessages;

/**
 * What the module learned, at run time, about the Match and Merge add-on's metadata not having the shape {@link MameMetadataContract} expects
 * (audit F-27). A reader that finds a table or a field missing used to answer "empty" and go on, so an add-on upgrade that renamed one made the
 * lineage screen, the retire notices and the golden counts silently wrong. Now the reader reports here ({@link #report}): the first report of a
 * finding is the signal to log one WARN line, every report is kept so that a screen can say "merge / lineage information unavailable" instead of
 * showing nothing ({@link #summary}). A finding is per metadataset (one per master table) and per problem, so the log is not flooded.
 * <p>
 * Pure: no EBX type. The state is process-wide, in memory (a restart after an add-on upgrade starts clean and checks again).
 */
public final class MameSchemaWatch
{
	/** One thing found wrong: which metadataset, which table, what. */
	public static final class Finding
	{
		public final String metadataset;
		public final String table;
		public final String problem;

		Finding(final String pMetadataset, final String pTable, final String pProblem)
		{
			this.metadataset = pMetadataset;
			this.table = pTable;
			this.problem = pProblem;
		}

		@Override
		public String toString()
		{
			return this.metadataset + "/" + this.table + ": " + this.problem;
		}
	}

	private static final Map<String, Finding> FINDINGS = new ConcurrentHashMap<String, Finding>();

	private MameSchemaWatch()
	{
	}

	/**
	 * Records a finding.
	 *
	 * @return true the FIRST time this exact finding is recorded (the caller logs it then), false for a repeat
	 */
	public static boolean report(final String pMetadataset, final String pTable, final String pProblem)
	{
		final String key = pMetadataset + "|" + pTable + "|" + pProblem;
		return MameSchemaWatch.FINDINGS.putIfAbsent(key, new Finding(pMetadataset, pTable, pProblem)) == null;
	}

	/** Forgets the findings of one metadataset (it was checked again and is fine now: the add-on was repaired or the model republished). */
	public static void clear(final String pMetadataset)
	{
		final String prefix = pMetadataset + "|";
		for (final String key : new ArrayList<String>(MameSchemaWatch.FINDINGS.keySet()))
		{
			if (key.startsWith(prefix))
			{
				MameSchemaWatch.FINDINGS.remove(key);
			}
		}
	}

	public static boolean any()
	{
		return !MameSchemaWatch.FINDINGS.isEmpty();
	}

	/** Every finding, in no particular order. */
	public static List<Finding> findings()
	{
		return Collections.unmodifiableList(new ArrayList<Finding>(MameSchemaWatch.FINDINGS.values()));
	}

	/** The findings of one metadataset. */
	public static List<Finding> findingsOf(final String pMetadataset)
	{
		final List<Finding> out = new ArrayList<Finding>();
		for (final Finding finding : MameSchemaWatch.FINDINGS.values())
		{
			if (finding.metadataset.equals(pMetadataset))
			{
				out.add(finding);
			}
		}
		return out;
	}

	/**
	 * The visible state for a screen: one sentence in {@code pLocale} saying the merge / lineage information cannot be trusted, followed by what was
	 * found (at most three entries), or null when nothing was found. {@code pMetadataset} null = any metadataset.
	 */
	public static String summary(final Locale pLocale, final String pMetadataset)
	{
		final String details = MameSchemaWatch.details(pMetadataset);
		return details == null ? null : ModuleMessages.get(pLocale, "mame.unavailable.summary", details);
	}

	/** The findings of one metadataset (null = any) as one line, at most three entries, or null when there is none. */
	public static String details(final String pMetadataset)
	{
		final List<Finding> findings = pMetadataset == null ? MameSchemaWatch.findings() : MameSchemaWatch.findingsOf(pMetadataset);
		if (findings.isEmpty())
		{
			return null;
		}
		final StringBuilder list = new StringBuilder();
		int shown = 0;
		for (final Finding finding : findings)
		{
			if (shown == 3)
			{
				list.append("; ...");
				break;
			}
			list.append(shown == 0 ? "" : "; ").append(finding);
			shown++;
		}
		return list.toString();
	}

	/** For tests: forget everything. */
	public static void clearAll()
	{
		MameSchemaWatch.FINDINGS.clear();
	}
}
