package com.on.ebx.factory.ebx;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;

/**
 * Where the factory reaches the HTTP end points of ITS OWN node (the MCP servers under {@code ebx-assistant}, the
 * {@code ebx-connectors} MCP end point, the catalog's OpenLineage route): always this JVM, never the public address
 * of the instance. Going through a load balancer or a TLS front end to talk to oneself costs a hop, may land on
 * another node, and fails on a certificate issued for the public name.
 * <p>
 * Resolution, first answer wins:
 * <ol>
 * <li>{@link Source#SETTING} - the {@code ebxBaseUrl} field of the factory settings, when filled. Kept for a
 * container this class cannot inspect, or a site that wants a specific internal address.</li>
 * <li>{@link Source#TOMCAT_CONNECTOR} - the HTTP connectors Tomcat registers as MBeans
 * ({@code *:type=Connector,*}): a plain HTTP connector first, an HTTPS one otherwise, never AJP; host = the
 * connector's bound address, or {@code 127.0.0.1} when it listens on every interface.</li>
 * <li>Otherwise an {@link OperationException} naming the setting to fill: no silent {@code localhost:8080}.</li>
 * </ol>
 * The detection is cached for the life of the class loader (a connector does not change without a restart).
 */
public final class LocalEndpoint
{
	/** How {@link Resolved#baseUrl} was obtained, shown on the connection screen and in the preflight. */
	public enum Source
	{
		SETTING, TOMCAT_CONNECTOR
	}

	public static final class Resolved
	{
		public final String baseUrl;
		public final Source source;
		/** One sentence for a person: which setting or which connector, and a warning when it is HTTPS. */
		public final String detail;

		Resolved(final String pBaseUrl, final Source pSource, final String pDetail)
		{
			this.baseUrl = pBaseUrl;
			this.source = pSource;
			this.detail = pDetail;
		}
	}

	/** One Tomcat connector, as read from its MBean. Package-private for the test. */
	static final class Connector
	{
		final String protocol;
		final String scheme;
		final boolean secure;
		final int port;
		final String address;

		Connector(final String pProtocol, final String pScheme, final boolean pSecure, final int pPort, final String pAddress)
		{
			this.protocol = pProtocol == null ? "" : pProtocol;
			this.scheme = pScheme == null ? "" : pScheme;
			this.secure = pSecure;
			this.port = pPort;
			this.address = pAddress;
		}

		boolean isAjp()
		{
			return this.protocol.toUpperCase(Locale.ROOT).contains("AJP");
		}

		boolean isHttps()
		{
			return this.secure || "https".equalsIgnoreCase(this.scheme);
		}
	}

	private static volatile Resolved detected;

	private LocalEndpoint()
	{
	}

	/** The base URL of this node, without a trailing slash. */
	public static Resolved resolve(final Adaptation pFactory) throws OperationException
	{
		final Adaptation settings = pFactory == null ? null
			: pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		return LocalEndpoint.resolve(settings == null ? null : settings.getString(FactoryPaths.Settings.EBX_BASE_URL));
	}

	static Resolved resolve(final String pSetting) throws OperationException
	{
		final String setting = LocalEndpoint.trimSlashes(pSetting);
		if (!setting.isEmpty())
		{
			return new Resolved(setting, Source.SETTING, "Factory setting 'EBX base URL'.");
		}
		Resolved found = LocalEndpoint.detected;
		if (found == null)
		{
			found = LocalEndpoint.choose(LocalEndpoint.readTomcatConnectors());
			LocalEndpoint.detected = found;
		}
		if (found == null)
		{
			throw EngineRefusal.coded("missing.baseUrl", "The factory cannot find the HTTP address of this EBX node: no Tomcat HTTP connector is visible over JMX. "
				+ "Fill 'EBX base URL' in the factory settings with an address of this node itself (for example http://127.0.0.1:8080), not the public address behind a load balancer.");
		}
		return found;
	}

	/**
	 * The connector the factory should call: plain HTTP before HTTPS, AJP never; among equals, the first one Tomcat
	 * listed. Null when there is none. Package-private and pure for the test.
	 */
	static Resolved choose(final List<Connector> pConnectors)
	{
		Connector http = null;
		Connector https = null;
		for (final Connector connector : pConnectors)
		{
			if (connector.isAjp() || connector.port <= 0)
			{
				continue;
			}
			if (connector.isHttps())
			{
				https = https == null ? connector : https;
			}
			else
			{
				http = http == null ? connector : http;
			}
		}
		final Connector chosen = http != null ? http : https;
		if (chosen == null)
		{
			return null;
		}
		final String scheme = chosen == http ? "http" : "https";
		final String baseUrl = scheme + "://" + LocalEndpoint.host(chosen.address) + ":" + chosen.port;
		final String detail = chosen == http ? "Tomcat HTTP connector on port " + chosen.port + "."
			: "Tomcat HTTPS connector on port " + chosen.port + " (no plain HTTP connector found): the JVM must trust its certificate for this host. "
				+ "An internal HTTP connector bound to 127.0.0.1 avoids that.";
		return new Resolved(baseUrl, Source.TOMCAT_CONNECTOR, detail);
	}

	/** {@code 127.0.0.1} for a wildcard or missing address, the address itself otherwise (bracketed when IPv6). */
	static String host(final String pAddress)
	{
		String address = pAddress == null ? "" : pAddress.trim();
		if (address.startsWith("/"))
		{
			address = address.substring(1); // InetAddress.toString() form: "/127.0.0.1"
		}
		if (address.isEmpty() || "0.0.0.0".equals(address) || "::".equals(address) || "0:0:0:0:0:0:0:0".equals(address))
		{
			return "127.0.0.1";
		}
		return address.indexOf(':') >= 0 && !address.startsWith("[") ? "[" + address + "]" : address;
	}

	static String trimSlashes(final String pUrl)
	{
		String url = pUrl == null ? "" : pUrl.trim();
		while (url.endsWith("/"))
		{
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	private static List<Connector> readTomcatConnectors()
	{
		final List<Connector> out = new ArrayList<Connector>();
		try
		{
			final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			final Set<ObjectName> names = server.queryNames(new ObjectName("*:type=Connector,*"), null);
			for (final ObjectName name : names)
			{
				final Object localPort = LocalEndpoint.attribute(server, name, "localPort");
				final Object port = localPort instanceof Number && ((Number) localPort).intValue() > 0 ? localPort : LocalEndpoint.attribute(server, name, "port");
				final Object address = LocalEndpoint.attribute(server, name, "address");
				out.add(new Connector(LocalEndpoint.string(LocalEndpoint.attribute(server, name, "protocol")),
					LocalEndpoint.string(LocalEndpoint.attribute(server, name, "scheme")),
					Boolean.TRUE.equals(LocalEndpoint.attribute(server, name, "secure")) || Boolean.TRUE.equals(LocalEndpoint.attribute(server, name, "SSLEnabled")),
					port instanceof Number ? ((Number) port).intValue() : -1, address == null ? name.getKeyProperty("address") : String.valueOf(address)));
			}
		}
		catch (final Exception ex)
		{
			// not Tomcat, or JMX not reachable: resolve() then asks for the setting
		}
		return out;
	}

	private static Object attribute(final MBeanServer pServer, final ObjectName pName, final String pAttribute)
	{
		try
		{
			return pServer.getAttribute(pName, pAttribute);
		}
		catch (final Exception ex)
		{
			return null;
		}
	}

	private static String string(final Object pValue)
	{
		return pValue == null ? null : String.valueOf(pValue);
	}
}
