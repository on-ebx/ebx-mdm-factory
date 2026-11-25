package com.on.ebx.factory.distribution;

import java.util.Locale;

/**
 * Distribution round 6 (2026-09-26, task brief item C, Personnes live incident): a target used to send EVERY
 * master table to a connector's single, connector-level destination property - live on Personnes, both
 * {@code /root/Personne} (mapping 314) and {@code /root/Adresse} (mapping 316) landed on the SAME Kafka topic
 * {@code mdm.personnes.person}, records of two different tables mixed together with the same key field. Some
 * connectors (Kafka: {@code KafkaConnector#publish}, read-only reference) already accept a PER-CALL
 * destination as a mapping input ({@code topic}, else the connector's own {@code topic} property; {@code key},
 * else the connector's own {@code key.field} property) - no Connectors change needed, only the Factory has to
 * start passing a per-table one.
 * <p>
 * Pure - no EBX class. {@code ebx.DistributionConfigurator} writes {@code DistributionTarget.tableDestinations}
 * (one {@code "tablePath=destination"} pair per master table, comma-separated - same encoding discipline as
 * {@code connectorsMappingIds}, parsed/formatted with {@link TargetMappingList}, never split by hand) and reads
 * this class only to PROPOSE a default for a table that has none yet; a value already stored (typed by hand in
 * the wizard's 7b "Table -&gt; topic" row, or proposed by an earlier Configure) is never overwritten.
 */
public final class TableDestinationDefaults
{
	private TableDestinationDefaults()
	{
	}

	/**
	 * The default destination proposed for one table, when the target has none stored yet:
	 * {@code <connector's own topic property, if it has one, else the project code, lower-cased>.<table name,
	 * lower-cased>} - e.g. a Kafka connector whose own {@code topic} property already reads {@code
	 * mdm.personnes} proposes {@code mdm.personnes.personne} for {@code /root/Personne} and {@code
	 * mdm.personnes.adresse} for {@code /root/Adresse}; a connector with no {@code topic} property of its own
	 * falls back to the project code, e.g. {@code personnes.personne}. This is only ever a STARTING POINT - the
	 * wizard's 7b row is editable, and nothing here is re-applied once a value is stored (see class Javadoc).
	 *
	 * @param pConnectorTopicProperty the connector's own {@code topic} configuration property ({@code
	 * connectors_describe}'s {@code properties.topic}), or null/blank when it has none
	 * @param pProjectCode the project's own code, used as the fallback prefix when the connector has no
	 * {@code topic} property of its own
	 * @param pTablePath the master table's path, e.g. {@code /root/Personne} - only its last segment (the table
	 * name) is used
	 */
	public static String proposeDestination(final String pConnectorTopicProperty, final String pProjectCode, final String pTablePath)
	{
		final String prefix = pConnectorTopicProperty != null && !pConnectorTopicProperty.trim().isEmpty() ? pConnectorTopicProperty.trim()
			: pProjectCode == null ? "" : pProjectCode.trim().toLowerCase(Locale.ENGLISH);
		final String tableNameLower = TableDestinationDefaults.tableName(pTablePath).toLowerCase(Locale.ENGLISH);
		return prefix.isEmpty() ? tableNameLower : prefix + "." + tableNameLower;
	}

	/** The last path segment of a table path ({@code /root/Personne} -&gt; {@code Personne}); the path unchanged when it carries no {@code /}. */
	private static String tableName(final String pTablePath)
	{
		if (pTablePath == null)
		{
			return "";
		}
		final int slash = pTablePath.lastIndexOf('/');
		return slash < 0 ? pTablePath : pTablePath.substring(slash + 1);
	}
}
