package com.on.ebx.factory.ebx;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder;
import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;

/**
 * The OpenLineage namespace this EBX instance is known by in the data catalog ({@code ebx://host[:port]}). It is an
 * IDENTITY, not an address: it must stay the same whichever node or port the factory calls itself on
 * ({@link LocalEndpoint}), and it must match the namespace {@code ebx-connectors} declares for the same instance.
 * <p>
 * Resolution, first answer wins:
 * <ol>
 * <li>the {@code ebxNamespace} field of the factory settings;</li>
 * <li>the system property {@code ebx.factory.catalog.ebxNamespace} (kept from the first version);</li>
 * <li>for a site that had put its public address in {@code ebxBaseUrl} before the two notions were split: that
 * address's host and port, as before - only when it is not a loopback address, which never named the instance;</li>
 * <li>{@link CatalogLineageBuilder#DEFAULT_EBX_NAMESPACE}, the {@code ebx-connectors} default.</li>
 * </ol>
 */
public final class LineageNamespace
{
	public static final String SYSTEM_PROPERTY = "ebx.factory.catalog.ebxNamespace";

	private LineageNamespace()
	{
	}

	public static String resolve(final Adaptation pFactory)
	{
		final Adaptation settings = pFactory == null ? null
			: pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		return LineageNamespace.resolve(settings == null ? null : settings.getString(FactoryPaths.Settings.EBX_NAMESPACE), System.getProperty(LineageNamespace.SYSTEM_PROPERTY),
			settings == null ? null : settings.getString(FactoryPaths.Settings.EBX_BASE_URL));
	}

	/** Package-private and pure for the test. */
	static String resolve(final String pSetting, final String pSystemProperty, final String pBaseUrlSetting)
	{
		if (pSetting != null && !pSetting.trim().isEmpty())
		{
			return pSetting.trim();
		}
		if (pSystemProperty != null && !pSystemProperty.trim().isEmpty())
		{
			return pSystemProperty.trim();
		}
		final String legacy = LineageNamespace.fromPublicBaseUrl(pBaseUrlSetting);
		return legacy != null ? legacy : CatalogLineageBuilder.DEFAULT_EBX_NAMESPACE;
	}

	private static String fromPublicBaseUrl(final String pBaseUrl)
	{
		if (pBaseUrl == null || pBaseUrl.trim().isEmpty())
		{
			return null;
		}
		try
		{
			final URL url = new URL(pBaseUrl.trim());
			final String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
			if (host.isEmpty() || LineageNamespace.isLoopback(host))
			{
				return null;
			}
			return "ebx://" + host + (url.getPort() > 0 ? ":" + url.getPort() : "");
		}
		catch (final MalformedURLException ex)
		{
			return null;
		}
	}

	static boolean isLoopback(final String pHost)
	{
		return "localhost".equals(pHost) || pHost.startsWith("127.") || "[::1]".equals(pHost) || "::1".equals(pHost) || "0:0:0:0:0:0:0:1".equals(pHost)
			|| "[0:0:0:0:0:0:0:1]".equals(pHost);
	}
}
