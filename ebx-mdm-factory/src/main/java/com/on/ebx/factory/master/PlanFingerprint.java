package com.on.ebx.factory.master;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Lot M-INC (2026-10-12, docs/VOLUME.md "Lot M-INC"): a hash of everything that decides what a master load writes for a staging row that did NOT change - the fields
 * of the entity's plan (staging field, master field, foreign key, target, deferred), the master fields that were writable (a field of the plan not yet on the published
 * master table is skipped), and the REJECT/WARN script rules. The journal only knows which STAGING rows changed; when this fingerprint differs from the one stored when the
 * entity was last armed, the unchanged rows would now produce a different master row (or a reject), so the entity is loaded in full. Pure, SHA-256 over length-prefixed
 * parts (the same part list always gives the same text, two different lists never do).
 */
public final class PlanFingerprint
{
	private PlanFingerprint()
	{
	}

	/** Lower-case hex SHA-256 of {@code pParts}, each part prefixed by its length; a null part counts as the empty string. */
	public static String of(final List<String> pParts)
	{
		final MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (final NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException("SHA-256 is part of every Java platform", ex);
		}
		for (final String part : pParts)
		{
			final byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
			digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
			digest.update(bytes);
			digest.update((byte) ';');
		}
		final StringBuilder sb = new StringBuilder();
		for (final byte b : digest.digest())
		{
			sb.append(String.format("%02x", Integer.valueOf(b & 0xff)));
		}
		return sb.toString();
	}
}
