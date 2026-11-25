package com.on.ebx.factory.ebx;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Locale;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;

/**
 * The two rules every credential the factory sends obeys (security hardening, 2026-09-24):
 * <ol>
 * <li><b>Fallback to the agent account.</b> A call normally runs as its caller (ebx-connectors' in-JVM bridge, a
 * session token for EBX's own servers). When that is impossible - no session, the bridge not registered, the token
 * not mintable after an upgrade - the stored agent account, likely more privileged than the caller, would take over.
 * Setting {@code agentFallback}: {@code FORBIDDEN} fails the call instead, {@code ALLOWED} lets it through, {@code AUTO}
 * (default, and an unset field) is ALLOWED on a DEVELOPMENT instance and FORBIDDEN on a TARGET one. A fallback that is
 * let through is written to the EBX kernel log, and the connection test shows the line's mode as {@code agent}.</li>
 * <li><b>Where a credential may go.</b> A session token or the agent password is sent only over HTTPS, or over HTTP to an
 * address of THIS machine (loopback or one of its own interfaces - such traffic never leaves the host). An
 * {@code http://} internal address naming another machine is refused before anything is sent.</li>
 * </ol>
 */
public final class CredentialPolicy
{
	public static final String ALLOWED = "ALLOWED";
	public static final String FORBIDDEN = "FORBIDDEN";
	public static final String AUTO = "AUTO";

	private CredentialPolicy()
	{
	}

	/** Pure: the {@code agentFallback} setting and the instance role decide. */
	static boolean fallbackAllowed(final String pSetting, final String pRole)
	{
		final String setting = pSetting == null ? AUTO : pSetting.trim().toUpperCase(Locale.ROOT);
		if (FORBIDDEN.equals(setting))
		{
			return false;
		}
		if (ALLOWED.equals(setting))
		{
			return true;
		}
		return !"TARGET".equalsIgnoreCase(pRole == null ? "" : pRole.trim());
	}

	public static boolean fallbackAllowed(final Adaptation pFactory)
	{
		final Adaptation settings = pFactory == null ? null
			: pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		return settings == null || CredentialPolicy.fallbackAllowed(settings.getString(FactoryPaths.Settings.AGENT_FALLBACK),
			settings.getString(FactoryPaths.Settings.ROLE));
	}

	/**
	 * Called at the one point where a call would switch to the agent account: throws when the policy forbids it,
	 * otherwise logs it (kernel log, WARN) and returns.
	 */
	public static void checkFallback(final Adaptation pFactory, final String pTarget, final String pReason) throws OperationException
	{
		if (!CredentialPolicy.fallbackAllowed(pFactory))
		{
			throw EngineRefusal.coded("conflict.agentFallbackForbidden", "The call to " + pTarget + " cannot run as its caller (" + pReason
				+ ") and falling back to the agent account is forbidden by the factory settings (agentFallback).", pTarget);
		}
		try
		{
			LoggingCategory.getKernel().warn("[mdm-factory] " + pTarget + " called with the AGENT ACCOUNT, not as the caller: " + pReason);
		}
		catch (final RuntimeException ex)
		{
			// logging must never break a call
		}
	}

	/** JVM property: extra hosts (comma-separated) that count as "this EBX node" - a cluster peer or an internal name. */
	public static final String INTERNAL_HOSTS_PROPERTY = "ebx.factory.internalHosts";

	/**
	 * Throws unless {@code pBaseUrl} is THIS EBX node (security review, 2026-09-24): an address of this machine, or a
	 * host listed in {@link #INTERNAL_HOSTS_PROPERTY}, and then only over HTTPS or to this machine. Used for every
	 * credential of EBX itself (a session token, the agent account): a free-form HTTPS URL in the settings would
	 * otherwise send an administrator's token to whoever typed it.
	 */
	public static void requireOwnNode(final String pBaseUrl) throws OperationException
	{
		if (!CredentialPolicy.isOwnNode(pBaseUrl, System.getProperty(CredentialPolicy.INTERNAL_HOSTS_PROPERTY)))
		{
			throw EngineRefusal.coded("conflict.credentialsNotSent", "Refusing to send EBX credentials to " + pBaseUrl
				+ ": only to this node (clear the internal EBX address to use the local Tomcat connector), or to a host listed in -D"
				+ CredentialPolicy.INTERNAL_HOSTS_PROPERTY + " over HTTPS.", pBaseUrl);
		}
	}

	static boolean isOwnNode(final String pBaseUrl, final String pAllowList)
	{
		try
		{
			final URL url = new URL(pBaseUrl);
			final String host = url.getHost();
			if (CredentialPolicy.isThisMachine(host))
			{
				return "http".equalsIgnoreCase(url.getProtocol()) || "https".equalsIgnoreCase(url.getProtocol());
			}
			if (pAllowList != null && "https".equalsIgnoreCase(url.getProtocol()))
			{
				for (final String allowed : pAllowList.split(","))
				{
					if (!allowed.trim().isEmpty() && allowed.trim().equalsIgnoreCase(host))
					{
						return true;
					}
				}
			}
			return false;
		}
		catch (final Exception ex)
		{
			return false;
		}
	}

	/**
	 * JVM property: hosts (comma-separated) of an INTERNAL language model that may be reached over plain HTTP - a model
	 * hosted inside the company network (Ollama, vLLM...). Anywhere else the model is reached over HTTPS only.
	 */
	public static final String TRUSTED_LLM_HOSTS_PROPERTY = "ebx.factory.trustedLlmHosts";

	/**
	 * JVM property, OPTIONAL (audit decision 3, 2026-10-06): the only hosts a prompt may be sent to. Comma-separated entries, each a host
	 * ({@code api.openai.com}: any port) or {@code host:port} ({@code llm.corp.local:11434}: that port only; the port of a URL without one is
	 * 443 for https, 80 for http); case-insensitive; an IPv6 literal is written {@code [::1]:11434} or bare {@code ::1}. Unset or blank: no
	 * restriction (the behaviour before the property existed). Set: a prompt to any other host is refused before anything is sent.
	 */
	public static final String ALLOWED_LLM_HOSTS_PROPERTY = "ebx.factory.llm.allowedHosts";

	/** Pure: whether {@code pUrl} may receive a prompt under {@code pAllowList}. A blank list allows everything; an unparsable URL is refused when a list is set. */
	static boolean isAllowedLlmHost(final String pUrl, final String pAllowList)
	{
		if (pAllowList == null || pAllowList.trim().isEmpty())
		{
			return true;
		}
		final URL url;
		try
		{
			url = new URL(pUrl);
		}
		catch (final Exception ex)
		{
			return false;
		}
		String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
		if (host.startsWith("[") && host.endsWith("]"))
		{
			host = host.substring(1, host.length() - 1);
		}
		final int port = url.getPort() >= 0 ? url.getPort() : url.getDefaultPort();
		for (final String raw : pAllowList.split(","))
		{
			String entry = raw.trim().toLowerCase(Locale.ROOT);
			if (entry.isEmpty())
			{
				continue;
			}
			int entryPort = -1;
			if (entry.startsWith("["))
			{
				final int close = entry.indexOf(']');
				if (close < 0)
				{
					continue;
				}
				final String rest = entry.substring(close + 1);
				if (rest.startsWith(":"))
				{
					entryPort = CredentialPolicy.parsePort(rest.substring(1));
					if (entryPort < 0)
					{
						continue;
					}
				}
				entry = entry.substring(1, close);
			}
			else if (entry.indexOf(':') >= 0 && entry.indexOf(':') == entry.lastIndexOf(':'))
			{
				entryPort = CredentialPolicy.parsePort(entry.substring(entry.indexOf(':') + 1));
				if (entryPort < 0)
				{
					continue;
				}
				entry = entry.substring(0, entry.indexOf(':'));
			}
			if (entry.equals(host) && (entryPort < 0 || entryPort == port))
			{
				return true;
			}
		}
		return false;
	}

	private static int parsePort(final String pText)
	{
		try
		{
			final int port = Integer.parseInt(pText.trim());
			return port >= 0 && port <= 65535 ? port : -1;
		}
		catch (final NumberFormatException ex)
		{
			return -1;
		}
	}

	/** The language model endpoint: {@link #ALLOWED_LLM_HOSTS_PROPERTY} when set, then {@link #requireSafeDestination}, or plain HTTP to a host of {@link #TRUSTED_LLM_HOSTS_PROPERTY}. */
	public static void requireLlmDestination(final String pUrl) throws OperationException
	{
		final String allowList = System.getProperty(CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY);
		if (!CredentialPolicy.isAllowedLlmHost(pUrl, allowList))
		{
			String host;
			try
			{
				host = new URL(pUrl).getHost();
			}
			catch (final Exception ex)
			{
				host = String.valueOf(pUrl);
			}
			throw EngineRefusal.coded("conflict.llmHostNotAllowed", "Refusing to send a prompt to the language model host " + host + " (" + pUrl + "): it is not in the allow-list -D"
				+ CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY + " (" + allowList.trim() + "). Add the host there, or change the language model connector.", host);
		}
		if (CredentialPolicy.isSafeDestination(pUrl) || CredentialPolicy.isTrustedLlmHost(pUrl, System.getProperty(CredentialPolicy.TRUSTED_LLM_HOSTS_PROPERTY)))
		{
			return;
		}
		throw EngineRefusal.coded("conflict.llmClearText", "Refusing to send the language model API key and data in clear text to " + pUrl
			+ ": use HTTPS, a model on this machine, or list an internal model's host in -D" + CredentialPolicy.TRUSTED_LLM_HOSTS_PROPERTY + ".", pUrl);
	}

	static boolean isTrustedLlmHost(final String pUrl, final String pAllowList)
	{
		if (pAllowList == null || pAllowList.trim().isEmpty())
		{
			return false;
		}
		try
		{
			final String host = new URL(pUrl).getHost();
			for (final String allowed : pAllowList.split(","))
			{
				if (!allowed.trim().isEmpty() && allowed.trim().equalsIgnoreCase(host))
				{
					return true;
				}
			}
			return false;
		}
		catch (final Exception ex)
		{
			return false;
		}
	}

	/** Throws unless a credential may be sent to {@code pBaseUrl} (HTTPS, or HTTP to this machine). For an EXTERNAL service (the language model); EBX's own credentials use {@link #requireOwnNode}. */
	public static void requireSafeDestination(final String pBaseUrl) throws OperationException
	{
		if (!CredentialPolicy.isSafeDestination(pBaseUrl))
		{
			throw EngineRefusal.coded("conflict.credentialsNotSent", "Refusing to send credentials in clear text to " + pBaseUrl
				+ ": over HTTP only to an address of this machine (clear the internal EBX address to use the local Tomcat connector, or use HTTPS).", pBaseUrl);
		}
	}

	static boolean isSafeDestination(final String pBaseUrl)
	{
		try
		{
			final URL url = new URL(pBaseUrl);
			if ("https".equalsIgnoreCase(url.getProtocol()))
			{
				return true;
			}
			return "http".equalsIgnoreCase(url.getProtocol()) && CredentialPolicy.isThisMachine(url.getHost());
		}
		catch (final Exception ex)
		{
			return false;
		}
	}

	static boolean isThisMachine(final String pHost)
	{
		if (pHost == null || pHost.isEmpty())
		{
			return false;
		}
		final String host = pHost.startsWith("[") && pHost.endsWith("]") ? pHost.substring(1, pHost.length() - 1) : pHost;
		if ("localhost".equalsIgnoreCase(host))
		{
			return true;
		}
		try
		{
			final InetAddress address = InetAddress.getByName(host);
			return address.isLoopbackAddress() || NetworkInterface.getByInetAddress(address) != null;
		}
		catch (final Exception ex)
		{
			return false;
		}
	}
}
