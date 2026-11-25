package com.on.ebx.factory.ebx;

/**
 * JUnit-free: run the main, a failure throws. {@link CredentialPolicy} (security hardening, 2026-09-24): the agent
 * fallback follows the setting then the instance role; credentials go over HTTPS, or over HTTP to this machine only.
 */
public final class CredentialPolicyTest
{
	public static void main(final String[] pArgs)
	{
		CredentialPolicyTest.check("AUTO on a development instance: allowed", CredentialPolicy.fallbackAllowed("AUTO", "DEVELOPMENT"));
		CredentialPolicyTest.check("AUTO on a target: forbidden", !CredentialPolicy.fallbackAllowed("AUTO", "TARGET"));
		CredentialPolicyTest.check("unset setting and role (older row): allowed", CredentialPolicy.fallbackAllowed(null, null));
		CredentialPolicyTest.check("unset setting on a target: forbidden", !CredentialPolicy.fallbackAllowed(null, "TARGET"));
		CredentialPolicyTest.check("FORBIDDEN wins on development", !CredentialPolicy.fallbackAllowed("FORBIDDEN", "DEVELOPMENT"));
		CredentialPolicyTest.check("ALLOWED wins on a target", CredentialPolicy.fallbackAllowed("allowed", "TARGET"));

		CredentialPolicyTest.check("loopback over HTTP", CredentialPolicy.isSafeDestination("http://127.0.0.1:8080"));
		CredentialPolicyTest.check("localhost over HTTP", CredentialPolicy.isSafeDestination("http://localhost:8080"));
		CredentialPolicyTest.check("IPv6 loopback over HTTP", CredentialPolicy.isSafeDestination("http://[::1]:8080"));
		CredentialPolicyTest.check("any host over HTTPS", CredentialPolicy.isSafeDestination("https://mdm.example.com"));
		CredentialPolicyTest.check("another machine over HTTP is refused", !CredentialPolicy.isSafeDestination("http://192.0.2.10:8080"));
		CredentialPolicyTest.check("an unresolvable host over HTTP is refused", !CredentialPolicy.isSafeDestination("http://no-such-host.invalid:8080"));
		CredentialPolicyTest.check("garbage is refused", !CredentialPolicy.isSafeDestination("not a url"));
		// security review (2026-09-24): EBX credentials only to this node or an allow-listed HTTPS host
		CredentialPolicyTest.check("own node over HTTP", CredentialPolicy.isOwnNode("http://127.0.0.1:8080", null));
		CredentialPolicyTest.check("a foreign HTTPS host is refused", !CredentialPolicy.isOwnNode("https://attacker.example", null));
		CredentialPolicyTest.check("an allow-listed HTTPS host is accepted", CredentialPolicy.isOwnNode("https://ebx-node2.corp", "ebx-node1.corp, ebx-node2.corp"));
		CredentialPolicyTest.check("an allow-listed host over HTTP is refused", !CredentialPolicy.isOwnNode("http://ebx-node2.corp", "ebx-node2.corp"));
		// an internal language model over plain HTTP only when its host is listed
		CredentialPolicyTest.check("a listed internal model host", CredentialPolicy.isTrustedLlmHost("http://llm.corp.local:11434/v1/chat", "gpu1.corp.local, llm.corp.local"));
		CredentialPolicyTest.check("an unlisted host", !CredentialPolicy.isTrustedLlmHost("http://other.corp.local:11434", "llm.corp.local"));
		CredentialPolicyTest.check("no list", !CredentialPolicy.isTrustedLlmHost("http://llm.corp.local", null));
		// audit decision 3 (2026-10-06): the optional allow-list of language model hosts
		final String api = "https://api.openai.com/v1/chat/completions";
		CredentialPolicyTest.check("no list: nothing is restricted", CredentialPolicy.isAllowedLlmHost(api, null) && CredentialPolicy.isAllowedLlmHost(api, "  "));
		CredentialPolicyTest.check("a listed host", CredentialPolicy.isAllowedLlmHost(api, "api.openai.com"));
		CredentialPolicyTest.check("case does not matter", CredentialPolicy.isAllowedLlmHost(api, "API.OpenAI.com"));
		CredentialPolicyTest.check("one of several, spaces around", CredentialPolicy.isAllowedLlmHost(api, "api.anthropic.com , api.openai.com"));
		CredentialPolicyTest.check("any other host is refused", !CredentialPolicy.isAllowedLlmHost("https://evil.example/v1", "api.openai.com"));
		CredentialPolicyTest.check("a sub-domain is not the host", !CredentialPolicy.isAllowedLlmHost("https://x.api.openai.com", "api.openai.com")
			&& !CredentialPolicy.isAllowedLlmHost("https://openai.com", "api.openai.com"));
		CredentialPolicyTest.check("a host entry allows any port", CredentialPolicy.isAllowedLlmHost("http://llm.corp.local:11434/v1", "llm.corp.local"));
		CredentialPolicyTest.check("host:port allows that port", CredentialPolicy.isAllowedLlmHost("http://llm.corp.local:11434/v1", "llm.corp.local:11434"));
		CredentialPolicyTest.check("host:port refuses another port", !CredentialPolicy.isAllowedLlmHost("http://llm.corp.local:8080/v1", "llm.corp.local:11434"));
		CredentialPolicyTest.check("the default https port is 443", CredentialPolicy.isAllowedLlmHost(api, "api.openai.com:443") && !CredentialPolicy.isAllowedLlmHost(api, "api.openai.com:8443"));
		CredentialPolicyTest.check("the default http port is 80", CredentialPolicy.isAllowedLlmHost("http://llm.corp.local/v1", "llm.corp.local:80"));
		CredentialPolicyTest.check("an IPv6 literal with a port", CredentialPolicy.isAllowedLlmHost("http://[::1]:11434/v1", "[::1]:11434"));
		CredentialPolicyTest.check("a bare IPv6 literal, any port", CredentialPolicy.isAllowedLlmHost("http://[::1]:11434/v1", "::1"));
		CredentialPolicyTest.check("a bad port entry matches nothing", !CredentialPolicy.isAllowedLlmHost(api, "api.openai.com:abc"));
		CredentialPolicyTest.check("empty entries are skipped", CredentialPolicy.isAllowedLlmHost(api, ",, api.openai.com,"));
		CredentialPolicyTest.check("an unparsable URL is refused when a list is set", !CredentialPolicy.isAllowedLlmHost("not a url", "api.openai.com"));
		System.setProperty(CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY, "api.anthropic.com");
		try
		{
			CredentialPolicy.requireLlmDestination(api);
			CredentialPolicyTest.check("a host outside the list must be refused", false);
		}
		catch (final com.orchestranetworks.service.OperationException ex)
		{
			CredentialPolicyTest.check("the refusal names the host and the property", ex.getMessage().contains("api.openai.com") && ex.getMessage().contains(CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY));
		}
		try
		{
			CredentialPolicy.requireLlmDestination("https://api.anthropic.com/v1/messages");
		}
		catch (final com.orchestranetworks.service.OperationException ex)
		{
			CredentialPolicyTest.check("a listed HTTPS host passes: " + ex.getMessage(), false);
		}
		finally
		{
			System.clearProperty(CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY);
		}
		try
		{
			CredentialPolicy.requireLlmDestination(api);
		}
		catch (final com.orchestranetworks.service.OperationException ex)
		{
			CredentialPolicyTest.check("with no property the behaviour is unchanged: " + ex.getMessage(), false);
		}
		System.out.println("CredentialPolicyTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
