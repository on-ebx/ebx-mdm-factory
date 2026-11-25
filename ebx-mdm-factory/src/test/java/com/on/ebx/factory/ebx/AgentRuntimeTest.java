package com.on.ebx.factory.ebx;

import com.on.ebx.agent.AgentLoop;
import com.on.ebx.agent.LlmClient;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Covers the pure parts
 * of {@link AgentRuntime} only - {@link AgentRuntime#settingsFor} (connector properties -&gt; {@link
 * LlmClient} settings: format, URL, header names, model, the OpenAI-shaped token-limit field) and {@link
 * AgentRuntime#statusOf} (status mapping) - never the EBX-reading or EBX-writing parts, which need a live
 * repository and are exercised only by hand against a real instance (see the module's README).
 */
public final class AgentRuntimeTest
{
	public static void main(final String[] pArgs)
	{
		AgentRuntimeTest.anthropic();
		AgentRuntimeTest.anthropicCustomHeaderAndVersion();
		AgentRuntimeTest.openAiDefault();
		AgentRuntimeTest.azureDeploymentPath();
		AgentRuntimeTest.openAiReasoningEffort();
		AgentRuntimeTest.mistralAndOllamaShareTheOpenAiPath();
		AgentRuntimeTest.geminiIsRefused();
		AgentRuntimeTest.blankBaseUrlIsRefused();
		AgentRuntimeTest.noAuthMeansNoHeader();
		AgentRuntimeTest.statusMapping();
		System.out.println("AgentRuntimeTest OK");
	}

	private static void anthropic()
	{
		final AgentRuntime.ConnectorInfo info = new AgentRuntime.ConnectorInfo("https://api.anthropic.com/", null, null, "BEARER", null, "sk-ant-secret", null,
			null, null, null, 5000, 30000);
		final AgentRuntime.ProviderSettings settings = AgentRuntime.settingsFor(info);
		AgentRuntimeTest.check("provider detected from the base URL", "anthropic".equals(settings.provider));
		AgentRuntimeTest.check("Anthropic format", settings.format == LlmClient.Format.ANTHROPIC);
		AgentRuntimeTest.check("trailing slash stripped, /v1/messages appended", "https://api.anthropic.com/v1/messages".equals(settings.url));
		AgentRuntimeTest.check("default model", "claude-sonnet-5".equals(settings.model));
		AgentRuntimeTest.check("default key header x-api-key (BEARER authType still uses x-api-key for Anthropic, not Authorization)",
			"sk-ant-secret".equals(settings.headers.get("x-api-key")));
		AgentRuntimeTest.check("default anthropic-version header", "2023-06-01".equals(settings.headers.get("anthropic-version")));
		AgentRuntimeTest.check("exactly the two expected headers, nothing else", settings.headers.size() == 2);
		AgentRuntimeTest.check("default max tokens", settings.maxTokens == 4096);
	}

	private static void anthropicCustomHeaderAndVersion()
	{
		final AgentRuntime.ConnectorInfo info = new AgentRuntime.ConnectorInfo("https://api.anthropic.com", "anthropic", "claude-opus-4", "API_KEY_HEADER",
			"x-custom-key", "secret", null, null, "2024-01-01", Integer.valueOf(8000), 5000, 30000);
		final AgentRuntime.ProviderSettings settings = AgentRuntime.settingsFor(info);
		AgentRuntimeTest.check("explicit model kept", "claude-opus-4".equals(settings.model));
		AgentRuntimeTest.check("API_KEY_HEADER honoured for Anthropic too", "secret".equals(settings.headers.get("x-custom-key")));
		AgentRuntimeTest.check("custom anthropic-version honoured", "2024-01-01".equals(settings.headers.get("anthropic-version")));
		AgentRuntimeTest.check("explicit maxTokens honoured", settings.maxTokens == 8000);
	}

	private static void openAiDefault()
	{
		final AgentRuntime.ConnectorInfo info = new AgentRuntime.ConnectorInfo("https://api.openai.com/v1", null, null, "BEARER", null, "sk-openai", null, null,
			null, null, 5000, 30000);
		final AgentRuntime.ProviderSettings settings = AgentRuntime.settingsFor(info);
		AgentRuntimeTest.check("falls back to openai when nothing else matches", "openai".equals(settings.provider));
		AgentRuntimeTest.check("OPENAI_CHAT format", settings.format == LlmClient.Format.OPENAI_CHAT);
		AgentRuntimeTest.check("chat completions path", "https://api.openai.com/v1/chat/completions".equals(settings.url));
		AgentRuntimeTest.check("Bearer header by default", "Bearer sk-openai".equals(settings.headers.get("Authorization")));
		AgentRuntimeTest.check("default openai model", "gpt-4o-mini".equals(settings.model));
		AgentRuntimeTest.check("default token-limit field name", LlmClient.DEFAULT_OPENAI_MAX_TOKENS_PARAM.equals(settings.openAiMaxTokensParam));
	}

	private static void azureDeploymentPath()
	{
		final AgentRuntime.ConnectorInfo info = new AgentRuntime.ConnectorInfo("https://my-resource.openai.azure.com", null, "gpt-4o", "API_KEY_HEADER", null,
			"az-key", "max_completion_tokens", "2024-06-01", null, null, 5000, 30000);
		final AgentRuntime.ProviderSettings settings = AgentRuntime.settingsFor(info);
		AgentRuntimeTest.check("azure detected from the base URL", "azure".equals(settings.provider));
		AgentRuntimeTest.check("deployment path with the model as the deployment name and the api-version query string",
			"https://my-resource.openai.azure.com/openai/deployments/gpt-4o/chat/completions?api-version=2024-06-01".equals(settings.url));
		AgentRuntimeTest.check("default api-key header when authType is API_KEY_HEADER with no override", "az-key".equals(settings.headers.get("api-key")));
		AgentRuntimeTest.check("openai.maxTokensParam property passed through to the client", "max_completion_tokens".equals(settings.openAiMaxTokensParam));
	}

	/**
	 * The {@code openai.reasoningEffort} connector property (2026-09-26, gpt-6-luna refusing function tools
	 * unless it is {@code none}): passed through trimmed for an OpenAI-shaped provider, null when absent or
	 * blank (the field is then not sent at all), and never for Anthropic.
	 */
	private static void openAiReasoningEffort()
	{
		AgentRuntimeTest.check("no reasoning effort by default", AgentRuntime.settingsFor(new AgentRuntime.ConnectorInfo("https://api.openai.com", null, "gpt-4o",
			"BEARER", null, "sk-openai", null, null, null, null, 5000, 30000)).openAiReasoningEffort == null);
		AgentRuntimeTest.check("blank reasoning effort = not sent", AgentRuntime.settingsFor(new AgentRuntime.ConnectorInfo("https://api.openai.com", null,
			"gpt-4o", "BEARER", null, "sk-openai", null, null, null, null, 5000, 30000, "  ")).openAiReasoningEffort == null);
		AgentRuntimeTest.check("openai.reasoningEffort passed through, trimmed", "none".equals(AgentRuntime.settingsFor(new AgentRuntime.ConnectorInfo(
			"https://api.openai.com", null, "gpt-6-luna", "BEARER", null, "sk-openai", "max_completion_tokens", null, null, null, 5000, 30000, " none "))
			.openAiReasoningEffort));
		AgentRuntimeTest.check("never for Anthropic", AgentRuntime.settingsFor(new AgentRuntime.ConnectorInfo("https://api.anthropic.com", null, null, "BEARER",
			null, "sk-ant", null, null, null, null, 5000, 30000, "none")).openAiReasoningEffort == null);
		final LlmClient client = AgentRuntime.settingsFor(new AgentRuntime.ConnectorInfo("https://api.openai.com", null, "gpt-6-luna", "BEARER", null,
			"sk-openai", null, null, null, null, 5000, 30000, "low")).buildClient();
		AgentRuntimeTest.check("the built client carries it", "low".equals(client.getOpenAiReasoningEffort()));
	}

	private static void mistralAndOllamaShareTheOpenAiPath()
	{
		final AgentRuntime.ConnectorInfo mistral = new AgentRuntime.ConnectorInfo("https://api.mistral.ai", null, null, "BEARER", null, "m-key", null, null,
			null, null, 5000, 30000);
		final AgentRuntime.ProviderSettings mistralSettings = AgentRuntime.settingsFor(mistral);
		AgentRuntimeTest.check("mistral detected", "mistral".equals(mistralSettings.provider));
		AgentRuntimeTest.check("mistral default model", "mistral-small-latest".equals(mistralSettings.model));
		AgentRuntimeTest.check("mistral uses the plain OpenAI-shaped chat completions path", "https://api.mistral.ai/v1/chat/completions".equals(mistralSettings.url));

		// no key at all (a local Ollama with authType NONE): apiKey null in ConnectorInfo, no Authorization header sent
		final AgentRuntime.ConnectorInfo ollama = new AgentRuntime.ConnectorInfo("http://localhost:11434", null, null, "NONE", null, null, null, null, null,
			null, 5000, 30000);
		final AgentRuntime.ProviderSettings ollamaSettings = AgentRuntime.settingsFor(ollama);
		AgentRuntimeTest.check("ollama detected from the port", "ollama".equals(ollamaSettings.provider));
		AgentRuntimeTest.check("ollama default model", "llama3.2".equals(ollamaSettings.model));
		AgentRuntimeTest.check("ollama uses its OpenAI-compatible endpoint (/v1/chat/completions), not its native /api/chat",
			"http://localhost:11434/v1/chat/completions".equals(ollamaSettings.url));
		AgentRuntimeTest.check("no Authorization header when the connector has no key", !ollamaSettings.headers.containsKey("Authorization"));
	}

	private static void geminiIsRefused()
	{
		final AgentRuntime.ConnectorInfo info = new AgentRuntime.ConnectorInfo("https://generativelanguage.googleapis.com", null, null, "API_KEY_HEADER", null,
			"g-key", null, null, null, null, 5000, 30000);
		try
		{
			AgentRuntime.settingsFor(info);
			throw new AssertionError("Gemini should have been refused");
		}
		catch (final IllegalStateException ex)
		{
			AgentRuntimeTest.check("clear refusal message naming Gemini", ex.getMessage().contains("Gemini"));
		}

		final AgentRuntime.ConnectorInfo declaredGoogle = new AgentRuntime.ConnectorInfo("https://my-endpoint.example.com", "google", null, "NONE", null, null,
			null, null, null, null, 5000, 30000);
		try
		{
			AgentRuntime.settingsFor(declaredGoogle);
			throw new AssertionError("an explicit provider=google override should also be refused");
		}
		catch (final IllegalStateException ex)
		{
			AgentRuntimeTest.check("refused even when declared explicitly, not only when detected from the URL", ex.getMessage().contains("not supported"));
		}
	}

	private static void blankBaseUrlIsRefused()
	{
		final AgentRuntime.ConnectorInfo info = new AgentRuntime.ConnectorInfo("  ", null, null, "NONE", null, null, null, null, null, null, 5000, 30000);
		try
		{
			AgentRuntime.settingsFor(info);
			throw new AssertionError("a blank base URL should be refused");
		}
		catch (final IllegalStateException ex)
		{
			AgentRuntimeTest.check("message names the base URL", ex.getMessage().contains("base URL"));
		}
	}

	private static void noAuthMeansNoHeader()
	{
		final AgentRuntime.ConnectorInfo info = new AgentRuntime.ConnectorInfo("https://api.anthropic.com", null, null, "NONE", null, null, null, null, null,
			null, 5000, 30000);
		final AgentRuntime.ProviderSettings settings = AgentRuntime.settingsFor(info);
		AgentRuntimeTest.check("no x-api-key header when the connector carries no key", !settings.headers.containsKey("x-api-key"));
		AgentRuntimeTest.check("anthropic-version is still set regardless of auth", settings.headers.containsKey("anthropic-version"));
	}

	private static void statusMapping()
	{
		AgentRuntimeTest.check("DONE", "DONE".equals(AgentRuntime.statusOf(AgentLoop.Status.DONE)));
		AgentRuntimeTest.check("FAILED", "FAILED".equals(AgentRuntime.statusOf(AgentLoop.Status.FAILED)));
		AgentRuntimeTest.check("BUDGET", "BUDGET".equals(AgentRuntime.statusOf(AgentLoop.Status.BUDGET)));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
