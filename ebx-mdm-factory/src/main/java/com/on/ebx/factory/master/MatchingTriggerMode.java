package com.on.ebx.factory.master;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot MT (2026-10-04): the per-project "matching trigger" ({@code Project.matchingTrigger}) and what it means for the add-on's
 * execution modes of a matching policy. Two modes, no third:
 * <ul>
 * <li>{@link #MANUAL} (default, also when the field is empty): the policy's {@code matchingOnCreation} and {@code matchingOnUpdate}
 * are {@value #MANUAL_VALUE} (the add-on's {@code MatchingPolicy$ExecutionMode.MANUAL}) - a master load never triggers a
 * record-by-record matching; matching runs when {@code MATCHING_RUN} is launched (after a load, or by hand).</li>
 * <li>{@link #REALTIME}: both are {@value #REALTIME_VALUE} ({@code AFTER_SUBMISSION}) - each record created or changed in the master
 * is matched at once.</li>
 * </ul>
 * WHY this exists: {@code generate_matching_policy} leaves the add-on's default execution modes on the policy (measured live,
 * 6.3.0-rc2, 2026-10-04, project Vol1mF: {@code matchingOnCreation: 1, matchingOnUpdate: 1}), so every project ran an inline
 * matching at each batch commit of a master load even though the Factory's intended default is "matching on demand" - and, on
 * that 1 M-row project, the inline matching left the table index behind during the mass load. The values 2
 * ({@code BEFORE_CREATION}) and 3 ({@code INLINE_MATCHING}) of the add-on are never written by the Factory.
 * <p>
 * The write itself is a {@code patch_matching_config} op {@code set_execution_mode} with {@code target.policyCode} and
 * {@code target.paramName} ({@code matchingOnCreation} or {@code matchingOnUpdate}) and the value as a JSON STRING ({@code "0"},
 * {@code "1"}) - read in {@code MamePatchApplier.resolveSetExecutionMode} (javap, 6.3.0-rc2): it refuses a call without
 * {@code policyCode} or with another {@code paramName}, and a {@code value} that is not 0, 1, 2 or 3. {@link #patchOps} is the pure
 * "what is left to write" computation: only the parameters whose stored value differs, so a second configure patches nothing.
 * <p>
 * Pure Jackson, no EBX class, no MCP call.
 */
public enum MatchingTriggerMode
{
	MANUAL(MatchingTriggerMode.MANUAL_VALUE),
	REALTIME(MatchingTriggerMode.REALTIME_VALUE);

	/** The add-on's {@code ExecutionMode.MANUAL}. */
	public static final int MANUAL_VALUE = 0;
	/** The add-on's {@code ExecutionMode.AFTER_SUBMISSION}. */
	public static final int REALTIME_VALUE = 1;

	/** The two policy parameters {@code set_execution_mode} can target. */
	public static final String ON_CREATION = "matchingOnCreation";
	public static final String ON_UPDATE = "matchingOnUpdate";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final int executionValue;

	MatchingTriggerMode(final int pExecutionValue)
	{
		this.executionValue = pExecutionValue;
	}

	/** The value written on both {@code matchingOnCreation} and {@code matchingOnUpdate}. */
	public int executionValue()
	{
		return this.executionValue;
	}

	/**
	 * @param pStored the stored {@code Project.matchingTrigger}; {@code null}, blank or an unknown text reads as {@link #MANUAL} (the safe
	 *            default: nothing runs inline) - never throws.
	 */
	public static MatchingTriggerMode parse(final String pStored)
	{
		if (pStored != null && MatchingTriggerMode.REALTIME.name().equals(pStored.trim().toUpperCase(Locale.ROOT)))
		{
			return MatchingTriggerMode.REALTIME;
		}
		return MatchingTriggerMode.MANUAL;
	}

	/** {@code true} when {@code pStored} is empty or one of the two values - what a wizard save accepts. */
	public static boolean isAccepted(final String pStored)
	{
		if (pStored == null || pStored.trim().isEmpty())
		{
			return true;
		}
		final String value = pStored.trim().toUpperCase(Locale.ROOT);
		return MatchingTriggerMode.MANUAL.name().equals(value) || MatchingTriggerMode.REALTIME.name().equals(value);
	}

	/**
	 * The {@code patch_matching_config} operations still needed on {@code pStoredPolicy} (one entry of {@code get_matching_config}'s
	 * {@code matchingPolicies[]}) to bring both execution modes to this mode: one {@code set_execution_mode} per parameter whose stored
	 * value differs ({@code null}/absent counts as different). Empty array when nothing differs - the idempotence the configurator relies
	 * on. {@code pPolicyCode} is the policy's {@code processCode}.
	 */
	public ArrayNode patchOps(final String pPolicyCode, final JsonNode pStoredPolicy)
	{
		final ArrayNode ops = MatchingTriggerMode.MAPPER.createArrayNode();
		this.addIfDifferent(ops, pPolicyCode, pStoredPolicy, MatchingTriggerMode.ON_CREATION);
		this.addIfDifferent(ops, pPolicyCode, pStoredPolicy, MatchingTriggerMode.ON_UPDATE);
		return ops;
	}

	/** The stored value of one execution parameter, {@code -1} when it is absent or not a number (read as "different"). */
	public static int storedValue(final JsonNode pStoredPolicy, final String pParamName)
	{
		if (pStoredPolicy == null)
		{
			return -1;
		}
		final JsonNode node = pStoredPolicy.path(pParamName);
		if (node.isNumber())
		{
			return node.asInt();
		}
		if (node.isTextual())
		{
			try
			{
				return Integer.parseInt(node.asText().trim());
			}
			catch (final NumberFormatException ex)
			{
				return -1;
			}
		}
		return -1;
	}

	private void addIfDifferent(final ArrayNode pOps, final String pPolicyCode, final JsonNode pStoredPolicy, final String pParamName)
	{
		if (MatchingTriggerMode.storedValue(pStoredPolicy, pParamName) == this.executionValue)
		{
			return;
		}
		final ObjectNode op = pOps.addObject();
		op.put("op", "set_execution_mode");
		final ObjectNode target = op.putObject("target");
		target.put("policyCode", pPolicyCode);
		target.put("paramName", pParamName);
		op.put("value", String.valueOf(this.executionValue));
	}
}
