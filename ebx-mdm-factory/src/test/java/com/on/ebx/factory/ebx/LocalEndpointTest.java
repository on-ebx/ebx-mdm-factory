package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.Collections;

import com.orchestranetworks.service.OperationException;

/**
 * JUnit-free: run the main, a failure throws. The pure parts of {@link LocalEndpoint} (local-endpoint lot,
 * 2026-09-23): an explicit setting wins, the connector choice prefers plain HTTP to HTTPS and never takes AJP, a
 * wildcard address becomes the loopback address, an IPv6 address is bracketed.
 */
public final class LocalEndpointTest
{
	public static void main(final String[] pArgs) throws OperationException
	{
		LocalEndpointTest.settingWinsAndLosesItsTrailingSlash();
		LocalEndpointTest.plainHttpBeforeHttpsNeverAjp();
		LocalEndpointTest.httpsOnlyIsUsedWithAWarning();
		LocalEndpointTest.nothingUsableIsNull();
		LocalEndpointTest.hostOfAConnector();
		System.out.println("LocalEndpointTest OK");
	}

	private static void settingWinsAndLosesItsTrailingSlash() throws OperationException
	{
		final LocalEndpoint.Resolved resolved = LocalEndpoint.resolve(" http://10.0.0.5:8080/ ");
		LocalEndpointTest.check("the setting is used as is: " + resolved.baseUrl, "http://10.0.0.5:8080".equals(resolved.baseUrl));
		LocalEndpointTest.check("its source is the setting", resolved.source == LocalEndpoint.Source.SETTING);
	}

	/** The connectors of the FastTrack Tomcat (conf/server.xml, 2026-09-23): 8080 HTTP, 8443 HTTPS, 8009 AJP on ::1. */
	private static void plainHttpBeforeHttpsNeverAjp()
	{
		final LocalEndpoint.Resolved resolved = LocalEndpoint.choose(Arrays.asList(
			new LocalEndpoint.Connector("AJP/1.3", "http", false, 8009, "::1"),
			new LocalEndpoint.Connector("org.apache.coyote.http11.Http11NioProtocol", "https", true, 8443, null),
			new LocalEndpoint.Connector("HTTP/1.1", "http", false, 8080, null)));
		LocalEndpointTest.check("plain HTTP on the loopback address: " + resolved.baseUrl, "http://127.0.0.1:8080".equals(resolved.baseUrl));
		LocalEndpointTest.check("its source is the Tomcat connector", resolved.source == LocalEndpoint.Source.TOMCAT_CONNECTOR);
	}

	private static void httpsOnlyIsUsedWithAWarning()
	{
		final LocalEndpoint.Resolved resolved = LocalEndpoint.choose(Arrays.asList(
			new LocalEndpoint.Connector("AJP/1.3", "http", false, 8009, null),
			new LocalEndpoint.Connector("HTTP/1.1", "http", true, 8443, "0.0.0.0")));
		LocalEndpointTest.check("a secure connector is called in HTTPS: " + resolved.baseUrl, "https://127.0.0.1:8443".equals(resolved.baseUrl));
		LocalEndpointTest.check("the detail warns about the certificate", resolved.detail.indexOf("certificate") >= 0);
	}

	private static void nothingUsableIsNull()
	{
		LocalEndpointTest.check("no connector at all", LocalEndpoint.choose(Collections.<LocalEndpoint.Connector>emptyList()) == null);
		LocalEndpointTest.check("AJP only, or an unbound port", LocalEndpoint.choose(Arrays.asList(new LocalEndpoint.Connector("AJP/1.3", "http", false, 8009, null),
			new LocalEndpoint.Connector("HTTP/1.1", "http", false, -1, null))) == null);
	}

	private static void hostOfAConnector()
	{
		LocalEndpointTest.check("no address", "127.0.0.1".equals(LocalEndpoint.host(null)));
		LocalEndpointTest.check("IPv4 wildcard", "127.0.0.1".equals(LocalEndpoint.host("0.0.0.0")));
		LocalEndpointTest.check("IPv6 wildcard", "127.0.0.1".equals(LocalEndpoint.host("::")));
		LocalEndpointTest.check("InetAddress.toString form", "10.1.2.3".equals(LocalEndpoint.host("/10.1.2.3")));
		LocalEndpointTest.check("IPv6 is bracketed", "[::1]".equals(LocalEndpoint.host("::1")));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
