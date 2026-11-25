package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * UX lot U5 (2026-10-13, RAPPORT 2.12): the Distribution step listed 24 write-capable connections flat - ActiveMQ, the H2 of a demo, three test Kafkas,
 * the sources of the project itself - and nothing told the plausible targets (Salesforce, Snowflake, a production Kafka) from test sources. This class sorts
 * the candidate connections into the sections the page shows: LIKELY targets, SOURCES of this project (sendable, flagged), OTHER connections (test, demo, H2,
 * sample systems, folded) and the ones with NO write operation (a folded count). Pure: no EBX class, the page passes what it already holds.
 * <p>
 * A connection that already carries a target of this project is always LIKELY, whatever its name or type: somebody set it up on purpose and is most likely here
 * to check on it. The "test" judgement reads the connector's type (H2, sample file) and whole words of its label/id ("test", "demo", "sample", "H2"): a
 * heuristic, wrong in both directions at times, which is why the connections it moves stay one click away under "Other connections" instead of vanishing.
 */
public final class DistributionConnectionGroups
{
	public final List<WizardPage.DistributionConnectionRow> likely = new ArrayList<WizardPage.DistributionConnectionRow>();
	public final List<WizardPage.DistributionConnectionRow> sources = new ArrayList<WizardPage.DistributionConnectionRow>();
	public final List<WizardPage.DistributionConnectionRow> others = new ArrayList<WizardPage.DistributionConnectionRow>();
	public final List<WizardPage.DistributionConnectionRow> noWrite = new ArrayList<WizardPage.DistributionConnectionRow>();

	private DistributionConnectionGroups()
	{
	}

	/** How many connections can receive golden records (the three sections above the folded count). */
	public int writeCapableCount()
	{
		return this.likely.size() + this.sources.size() + this.others.size();
	}

	/**
	 * @param pConnections every candidate connection ({@code ebx.DistributionConnections}), in the order Connectors lists them
	 * @param pTargetConnectors the connector ids that already carry a target of this project
	 * @param pSourceConnectors the connector ids of the applications of this project (a source system)
	 */
	public static DistributionConnectionGroups of(final List<WizardPage.DistributionConnectionRow> pConnections, final Set<String> pTargetConnectors,
		final Set<String> pSourceConnectors)
	{
		return DistributionConnectionGroups.of(pConnections, pTargetConnectors, pSourceConnectors, "", Collections.<String> emptySet(), Collections.<String> emptySet());
	}

	/**
	 * Step 7 screen counts (2026-10-18): a connection made for ANOTHER Factory project is not a likely target of this one (the Kafka of the previous e2e project, "Kafka - e2e
	 * E2EMASTER20261018171023", was listed among the likely targets of E2EMASTER20261018181651). It goes under the folded "other connections". A connection is another
	 * project's when a distribution target of another project uses it, or when its label or id carries the code of another project, or the code of a scenario project
	 * ({@code E2E...<digits>}) that is not this one. A connection that already carries a target of THIS project stays likely whatever its name.
	 *
	 * @param pProjectCode the code of this project
	 * @param pOtherProjectCodes the codes of the other Factory projects
	 * @param pOtherProjectConnectors the connector ids the distribution targets of other projects use
	 */
	public static DistributionConnectionGroups of(final List<WizardPage.DistributionConnectionRow> pConnections, final Set<String> pTargetConnectors,
		final Set<String> pSourceConnectors, final String pProjectCode, final Set<String> pOtherProjectCodes, final Set<String> pOtherProjectConnectors)
	{
		final DistributionConnectionGroups groups = new DistributionConnectionGroups();
		final List<WizardPage.DistributionConnectionRow> withTarget = new ArrayList<WizardPage.DistributionConnectionRow>();
		final List<WizardPage.DistributionConnectionRow> likelyWithout = new ArrayList<WizardPage.DistributionConnectionRow>();
		for (final WizardPage.DistributionConnectionRow connection : pConnections)
		{
			if (!connection.hasWriteOperation)
			{
				groups.noWrite.add(connection);
			}
			else if (pTargetConnectors.contains(connection.connector))
			{
				withTarget.add(connection);
			}
			else if (pSourceConnectors.contains(connection.connector))
			{
				groups.sources.add(connection);
			}
			else if (DistributionConnectionGroups.looksLikeTest(connection)
				|| DistributionConnectionGroups.madeForAnotherProject(connection, pProjectCode, pOtherProjectCodes, pOtherProjectConnectors))
			{
				groups.others.add(connection);
			}
			else
			{
				likelyWithout.add(connection);
			}
		}
		DistributionConnectionGroups.sort(withTarget);
		DistributionConnectionGroups.sort(likelyWithout);
		groups.likely.addAll(withTarget);
		groups.likely.addAll(likelyWithout);
		DistributionConnectionGroups.sort(groups.sources);
		DistributionConnectionGroups.sort(groups.others);
		DistributionConnectionGroups.sort(groups.noWrite);
		return groups;
	}

	private static final java.util.regex.Pattern SCENARIO_CODE = java.util.regex.Pattern.compile("(?i)e2e[a-z_]*\\d{8,}");

	/** True when the connection was created for a project other than {@code pProjectCode}: another project's target uses it, or its label / id names another project (see {@link #of(List, Set, Set, String, Set, Set)}). */
	static boolean madeForAnotherProject(final WizardPage.DistributionConnectionRow pConnection, final String pProjectCode, final Set<String> pOtherProjectCodes,
		final Set<String> pOtherProjectConnectors)
	{
		if (pOtherProjectConnectors.contains(pConnection.connector))
		{
			return true;
		}
		final String text = (pConnection.label + " " + pConnection.connector).toLowerCase(Locale.ENGLISH);
		final String own = pProjectCode == null ? "" : pProjectCode.toLowerCase(Locale.ENGLISH);
		for (final String other : pOtherProjectCodes)
		{
			final String code = other.toLowerCase(Locale.ENGLISH);
			// a short code ("Companies") would match any word: only a code of 6 characters or more is read inside a longer text, and one that is not part of this project's own code
			if (code.length() >= 6 && !code.equals(own) && !own.contains(code) && text.contains(code))
			{
				return true;
			}
		}
		final java.util.regex.Matcher scenario = DistributionConnectionGroups.SCENARIO_CODE.matcher(text);
		while (scenario.find())
		{
			if (!own.isEmpty() && !own.equals(scenario.group()) && !own.contains(scenario.group()))
			{
				return true;
			}
		}
		return false;
	}

	/** True for an H2 / sample-file connector or a label or id that names itself a test, demo, sample or H2 system. */
	static boolean looksLikeTest(final WizardPage.DistributionConnectionRow pConnection)
	{
		final String type = pConnection.type.toLowerCase(Locale.ENGLISH);
		if (type.equals("h2") || type.contains("sample"))
		{
			return true;
		}
		final String text = (pConnection.label + " " + pConnection.connector).toLowerCase(Locale.ENGLISH);
		for (final String word : text.split("[^a-z0-9]+"))
		{
			if (word.equals("test") || word.equals("tests") || word.equals("demo") || word.equals("sample") || word.equals("h2"))
			{
				return true;
			}
		}
		return false;
	}

	private static void sort(final List<WizardPage.DistributionConnectionRow> pRows)
	{
		Collections.sort(pRows, new Comparator<WizardPage.DistributionConnectionRow>()
		{
			@Override
			public int compare(final WizardPage.DistributionConnectionRow pLeft, final WizardPage.DistributionConnectionRow pRight)
			{
				return pLeft.label.compareToIgnoreCase(pRight.label);
			}
		});
	}
}
