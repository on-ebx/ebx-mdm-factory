package com.on.ebx.factory.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The natural keys of the data catalog's {@code Asset} and {@code Column} tables (audit D-07, decided 2026-10-06): the
 * auto-increment {@code /id} is gone, an asset is keyed {@code assetKey = source:qualifiedName} and a column
 * {@code columnKey = assetKey.name}. A port of the catalog's own {@code com.on.ebx.catalog.core.CatalogKeys} (module
 * ebx-data-catalog): the factory reads and writes the catalog as DATA and never loads a class of that module, so the
 * two formulas are copied here verbatim and pinned by {@code CatalogKeysTest} on the values the catalog's class gives.
 * Names are kept exactly as spelled (no trimming, no case change, no escaping); the source id is trimmed. A
 * single-field string key is stored raw: a foreign key to an asset or a column holds this string itself, and a lookup
 * goes through {@code AdaptationTable.computePrimaryKey(new Object[] { key })}, never {@code PrimaryKey.parseString}
 * (which reads {@code |} and {@code \} as escapes) and never a string built by hand.
 * <p>
 * Pure: no EBX type.
 */
public final class CatalogKeys
{
	/** {@code maxLength} of {@code Asset.assetKey} in the catalog's model. */
	public static final int ASSET_KEY_MAX = 560;
	/** {@code maxLength} of {@code Column.columnKey} in the catalog's model. */
	public static final int COLUMN_KEY_MAX = 820;

	private CatalogKeys()
	{
	}

	/** {@code source:qualifiedName}; null when either part is missing. The source id is trimmed, the name is not. */
	public static String assetKey(final String pSource, final String pQualifiedName)
	{
		if (CatalogKeys.blank(pSource) || pQualifiedName == null || pQualifiedName.isEmpty())
		{
			return null;
		}
		return pSource.trim() + ":" + pQualifiedName;
	}

	/** {@code assetKey.name}; null when either part is missing. */
	public static String columnKey(final String pAssetKey, final String pName)
	{
		if (CatalogKeys.blank(pAssetKey) || pName == null || pName.isEmpty())
		{
			return null;
		}
		return pAssetKey + "." + pName;
	}

	/**
	 * What may be looked up as an asset key: not blank, at most {@link #ASSET_KEY_MAX} characters, no control character,
	 * and holding the {@code :} every {@link #assetKey} has - an integer id stored before audit D-07 (EBX converts the old
	 * {@code xs:int} {@code LandingTable.catalogAsset} to its digits, {@code "51"}) is not one, and is re-resolved from the
	 * source and the table name instead.
	 */
	public static boolean plausibleAssetKey(final String pKey)
	{
		if (CatalogKeys.blank(pKey) || pKey.length() > CatalogKeys.ASSET_KEY_MAX || pKey.indexOf(':') <= 0)
		{
			return false;
		}
		for (int i = 0; i < pKey.length(); i++)
		{
			if (Character.isISOControl(pKey.charAt(i)))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * The keys to try, in order, to find the asset a landing table was planned from: the stored {@code catalogAsset} when
	 * it is a plausible key, then the key the source and the source object give ({@link #assetKey}) when different. Empty
	 * when neither exists. The caller looks each one up and, when none answers, searches the source's assets by
	 * qualified name (a deduced asset renamed before D-07, or a connector that keys its assets its own way).
	 */
	public static List<String> lookupOrder(final String pStored, final String pSource, final String pSourceObject)
	{
		final List<String> out = new ArrayList<String>();
		if (CatalogKeys.plausibleAssetKey(pStored))
		{
			out.add(pStored);
		}
		final String computed = CatalogKeys.assetKey(pSource, pSourceObject);
		if (computed != null && !out.contains(computed))
		{
			out.add(computed);
		}
		return out;
	}

	/**
	 * A token made only of {@code [A-Za-z0-9_]}, unique per key, for an HTML id built from a key (a key holds quotes,
	 * spaces, dots, colons, slashes...). The key itself still travels in an escaped attribute VALUE, which round-trips.
	 */
	public static String domToken(final String pKey)
	{
		return "k" + CatalogKeys.sha256(pKey == null ? "" : pKey).substring(0, 20);
	}

	static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	static String sha256(final String pValue)
	{
		try
		{
			final byte[] hash = MessageDigest.getInstance("SHA-256").digest(pValue.getBytes(StandardCharsets.UTF_8));
			final StringBuilder sb = new StringBuilder(hash.length * 2);
			for (final byte b : hash)
			{
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		}
		catch (final NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException(ex);
		}
	}
}
