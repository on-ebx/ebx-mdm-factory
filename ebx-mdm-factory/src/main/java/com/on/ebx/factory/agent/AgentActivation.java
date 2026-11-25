package com.on.ebx.factory.agent;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Production review of 2026-11-12 (F3-02): who an agent acts for is NEVER a value a person writes into the row. The route that switches an agent on
 * ({@code POST /agents/{code}/enable}) stores {@code <login>~<signature>} in {@code AgentDefinition.enabledBy}; the signature is an HMAC-SHA256 over the agent
 * code, the settings that decide WHAT the agent may do ({@link #configOf}) and the login, keyed with a SECRET drawn at random by the installer
 * ({@code FactorySettings.agentActivationKey}, hidden from every person, never logged, returned, backed up or delivered). The instance identifier is NOT a
 * key: it is copied into every project and shown in the cockpit. A login typed into the field, a row copied from another agent, and any later change of
 * the project, the kind, the autonomy, the tools or the instructions of an activated agent all fail {@link #verify}: the agent is then treated as not switched
 * on. No key (an installation that has not run yet) means nothing is signed and nothing verifies. Pure, no EBX dependency.
 */
public final class AgentActivation
{
	/** Separates the login from its signature; a login may contain it, the signature never does (the last one counts). */
	public static final String SEPARATOR = "~";
	/** Hex characters of the signature kept (128 bits). */
	static final int SIGNATURE_LENGTH = 32;
	/** The size of the {@code enabledBy} field. */
	public static final int FIELD_MAX = 120;

	private AgentActivation()
	{
	}

	/** Hex characters of a key as drawn by {@link #newKey} (32 bytes). */
	static final int KEY_HEX_LENGTH = 64;

	/** A new random key: 32 bytes from {@link SecureRandom}, in lowercase hexadecimal. */
	public static String newKey(final SecureRandom pRandom)
	{
		final byte[] raw = new byte[KEY_HEX_LENGTH / 2];
		pRandom.nextBytes(raw);
		final StringBuilder hex = new StringBuilder();
		for (final byte one : raw)
		{
			hex.append(String.format("%02x", Integer.valueOf(one & 0xff)));
		}
		return hex.toString();
	}

	/** The HMAC key bytes for a stored key, null when there is none or it is not a key drawn by {@link #newKey} (nothing can then be signed or verified). */
	public static byte[] keyOf(final String pStoredKey)
	{
		final String key = pStoredKey == null ? "" : pStoredKey.trim();
		return key.matches("[0-9a-f]{" + AgentActivation.KEY_HEX_LENGTH + "}") ? ("mdmf-agent-activation|" + key).getBytes(StandardCharsets.UTF_8) : null;
	}

	/**
	 * What an activation covers, as one text: every setting that decides what the agent may read, decide and write. Lengths prefix each part, so two different
	 * rows never give the same text.
	 */
	public static String configOf(final String pProject, final String pKind, final String pEntity, final String pZone, final String pAutonomy, final Integer pThreshold,
		final Integer pMaxItems, final String pOwnerProfile, final List<String> pAllowedTools, final String pInstructions)
	{
		final StringBuilder out = new StringBuilder();
		AgentActivation.part(out, pProject);
		AgentActivation.part(out, pKind);
		AgentActivation.part(out, pEntity);
		AgentActivation.part(out, pZone);
		AgentActivation.part(out, pAutonomy);
		AgentActivation.part(out, pThreshold == null ? null : pThreshold.toString());
		AgentActivation.part(out, pMaxItems == null ? null : pMaxItems.toString());
		AgentActivation.part(out, pOwnerProfile);
		AgentActivation.part(out, pAllowedTools == null ? null : String.valueOf(pAllowedTools.size()));
		if (pAllowedTools != null)
		{
			for (final String tool : pAllowedTools)
			{
				AgentActivation.part(out, tool);
			}
		}
		AgentActivation.part(out, pInstructions);
		return out.toString();
	}

	private static void part(final StringBuilder pOut, final String pValue)
	{
		final String value = pValue == null ? "" : pValue.trim();
		pOut.append(value.length()).append(':').append(value).append(';');
	}

	/** The value to store in {@code enabledBy} for {@code pLogin}, or null when nothing can be signed (no key, no login, a value that would not fit the field). */
	public static String sign(final byte[] pKey, final String pAgentCode, final String pConfig, final String pLogin)
	{
		final String login = pLogin == null ? "" : pLogin.trim();
		if (pKey == null || pAgentCode == null || login.isEmpty())
		{
			return null;
		}
		final String stored = login + AgentActivation.SEPARATOR + AgentActivation.mac(pKey, pAgentCode.trim(), pConfig, login);
		return stored.length() > AgentActivation.FIELD_MAX ? null : stored;
	}

	/** The login an activation value was signed for, null when the value is empty, unsigned, signed for another agent or other settings, or tampered with. */
	public static String verify(final byte[] pKey, final String pAgentCode, final String pConfig, final String pStored)
	{
		if (pKey == null || pAgentCode == null || pStored == null)
		{
			return null;
		}
		final String stored = pStored.trim();
		final int cut = stored.lastIndexOf(AgentActivation.SEPARATOR);
		if (cut <= 0 || cut != stored.length() - 1 - AgentActivation.SIGNATURE_LENGTH)
		{
			return null;
		}
		final String login = stored.substring(0, cut);
		if (!login.equals(login.trim()))
		{
			return null;
		}
		final String expected = AgentActivation.mac(pKey, pAgentCode.trim(), pConfig, login);
		final boolean same = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), stored.substring(cut + 1).getBytes(StandardCharsets.UTF_8));
		return same ? login : null;
	}

	private static String mac(final byte[] pKey, final String pAgentCode, final String pConfig, final String pLogin)
	{
		try
		{
			final Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(pKey, "HmacSHA256"));
			final byte[] raw = mac.doFinal(("agent=" + pAgentCode + "\u0001config=" + (pConfig == null ? "" : pConfig) + "\u0001login=" + pLogin).getBytes(StandardCharsets.UTF_8));
			final StringBuilder hex = new StringBuilder();
			for (final byte one : raw)
			{
				hex.append(String.format("%02x", Integer.valueOf(one & 0xff)));
			}
			return hex.substring(0, AgentActivation.SIGNATURE_LENGTH);
		}
		catch (final GeneralSecurityException ex)
		{
			throw new IllegalStateException("HmacSHA256 is part of every Java platform", ex);
		}
	}
}
