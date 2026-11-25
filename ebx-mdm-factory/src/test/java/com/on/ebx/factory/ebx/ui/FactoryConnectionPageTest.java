package com.on.ebx.factory.ebx.ui;

import java.util.Locale;

/** JUnit-free: run the main, a failure throws. Exercises {@link FactoryConnectionPage} without any EBX type. */
public final class FactoryConnectionPageTest
{
	public static void main(final String[] pArgs)
	{
		FactoryConnectionPageTest.passwordValueNeverAppearsOnTheRenderedPage();
		FactoryConnectionPageTest.everyFieldValueIsEscaped();
		FactoryConnectionPageTest.neverTestedShowsNoResults();
		FactoryConnectionPageTest.readyAndNotReadyRenderDifferentNotices();
		FactoryConnectionPageTest.probeIsShownOnAnOkLineAndItsFailureOnABadOne();
		FactoryConnectionPageTest.llmLineIsMarkedPendingNeverFakedAsOk();
		FactoryConnectionPageTest.llmLineReportsTheRealCall();
		FactoryConnectionPageTest.connectorsLineSaysInJvm();
		FactoryConnectionPageTest.ebxServerLineSaysSession();
		FactoryConnectionPageTest.saveFailureShowsTheErrorAndNoResults();
		FactoryConnectionPageTest.frenchRendersAccentedLabels();
		FactoryConnectionPageTest.llmPickerListsConnectorsWithANoneOption();
		FactoryConnectionPageTest.llmPickerKeepsASavedIdThatNoLongerExists();
		FactoryConnectionPageTest.scriptCarriesTheSharedComboAndItsInit();

		System.out.println("FactoryConnectionPageTest OK");
	}

	private static void passwordValueNeverAppearsOnTheRenderedPage()
	{
		final FactoryConnectionPage.Model model = FactoryConnectionPageTest.model();
		model.passwordSet = true;
		// FactoryConnectionPage.Model has no field at all to carry a clear-text password: this test guards the
		// contract (whoever extends the model later must not add one and render it), not a specific bug.
		final String html = FactoryConnectionPage.render(model);
		FactoryConnectionPageTest.check("the password field is never pre-filled with a value", html.indexOf("id=\"mdmfPassword\" name=\"mdmfPassword\" value=\"\"") >= 0);
		FactoryConnectionPageTest.check("a stored password is reported as present in the hint, not shown",
			html.indexOf(Messages.get(FactoryConnectionPage.class, Locale.ENGLISH, "connection.field.password.set")) >= 0);
	}

	private static void everyFieldValueIsEscaped()
	{
		final FactoryConnectionPage.Model model = FactoryConnectionPageTest.model();
		model.ebxBaseUrl = "http://x\"><script>alert(1)</script>";
		model.agentUser = "a&b";
		model.llmConnector = "<llm>";
		final String html = FactoryConnectionPage.render(model);
		FactoryConnectionPageTest.check("no raw <script> tag leaked from the URL field", html.indexOf("<script>alert(1)</script>") < 0);
		FactoryConnectionPageTest.check("the URL is present, escaped", html.indexOf("&lt;script&gt;alert(1)&lt;/script&gt;") >= 0);
		FactoryConnectionPageTest.check("the ampersand in the user is escaped", html.indexOf("a&amp;b") >= 0);
		FactoryConnectionPageTest.check("the llm connector id is escaped", html.indexOf("&lt;llm&gt;") >= 0);
	}

	private static void neverTestedShowsNoResults()
	{
		final FactoryConnectionPage.Model model = FactoryConnectionPageTest.model();
		model.tested = false;
		final String html = FactoryConnectionPage.render(model);
		FactoryConnectionPageTest.check("the 'never tested' hint is shown", html.indexOf(Messages.get(FactoryConnectionPage.class, Locale.ENGLISH, "connection.test.never")) >= 0);
		FactoryConnectionPageTest.check("no server result list before a test ran", html.indexOf("mdmf-mcp-list") < 0);
	}

	private static void readyAndNotReadyRenderDifferentNotices()
	{
		final FactoryConnectionPage.Model ready = FactoryConnectionPageTest.model();
		ready.tested = true;
		ready.ready = true;
		ready.results.add(FactoryConnectionPageTest.line("data-model", true, 29, null));
		final String readyHtml = FactoryConnectionPage.render(ready);
		FactoryConnectionPageTest.check("ready shows the ready notice", readyHtml.indexOf(Messages.get(FactoryConnectionPage.class, Locale.ENGLISH, "connection.ready.yes")) >= 0);
		FactoryConnectionPageTest.check("the tool count is shown", readyHtml.indexOf("29") >= 0);
		FactoryConnectionPageTest.check("a reachable server gets the good dot", readyHtml.indexOf("mdmf-dot-good") >= 0);

		final FactoryConnectionPage.Model notReady = FactoryConnectionPageTest.model();
		notReady.tested = true;
		notReady.ready = false;
		notReady.results.add(FactoryConnectionPageTest.line("match-merge", false, 0, "The server answers but offers no tool"));
		final String notReadyHtml = FactoryConnectionPage.render(notReady);
		FactoryConnectionPageTest.check("not ready shows the not-ready notice",
			notReadyHtml.indexOf(Messages.get(FactoryConnectionPage.class, Locale.ENGLISH, "connection.ready.no")) >= 0);
		FactoryConnectionPageTest.check("the real problem text from McpServers.test is shown as-is, not translated or replaced",
			notReadyHtml.indexOf("The server answers but offers no tool") >= 0);
		FactoryConnectionPageTest.check("a failing server gets the bad dot", notReadyHtml.indexOf("mdmf-dot-bad") >= 0);
	}

	/** mcp-preflight-probe lot (2026-09-23): an ok line names the probe tool that answered; a failed probe is a bad line whose problem names the tool. */
	private static void probeIsShownOnAnOkLineAndItsFailureOnABadOne()
	{
		final FactoryConnectionPage.Model model = FactoryConnectionPageTest.model();
		model.tested = true;
		final FactoryConnectionPage.ServerLine good = FactoryConnectionPageTest.line("data-model", true, 29, null);
		good.probeTool = "list_data_models";
		good.probeOk = true;
		model.results.add(good);
		final FactoryConnectionPage.ServerLine bad = FactoryConnectionPageTest.line("match-merge", false, 17,
			"The account may list the tools but the call to 'matching_config_schema' was refused: MISSING_PERMISSION");
		bad.probeTool = "matching_config_schema";
		bad.probeOk = false;
		model.results.add(bad);
		final String html = FactoryConnectionPage.render(model);
		FactoryConnectionPageTest.check("the ok line names the probe tool that answered",
			html.indexOf(Messages.get(FactoryConnectionPage.class, Locale.ENGLISH, "connection.results.probe.ok", "list_data_models")) >= 0);
		// FactoryHtml.esc turns the apostrophes of McpServers' own sentence into &#39; - the sentence is shown, escaped, as-is
		FactoryConnectionPageTest.check("a failed probe renders as a bad line carrying its own sentence",
			html.indexOf("mdmf-dot-bad") >= 0 && html.indexOf("call to &#39;matching_config_schema&#39; was refused") >= 0);
		FactoryConnectionPageTest.check("a failed probe never shows the 'answered' wording for its tool",
			html.indexOf(Messages.get(FactoryConnectionPage.class, Locale.ENGLISH, "connection.results.probe.ok", "matching_config_schema")) < 0);
		final FactoryConnectionPage.ServerLine noProbe = FactoryConnectionPageTest.line("data-access", true, 20, null);
		final FactoryConnectionPage.Model older = FactoryConnectionPageTest.model();
		older.tested = true;
		older.results.add(noProbe);
		FactoryConnectionPageTest.check("a line without probe information (older answer shape) still renders as ok, with no probe wording",
			FactoryConnectionPage.render(older).indexOf("answered") < 0);
	}

	private static void llmLineIsMarkedPendingNeverFakedAsOk()
	{
		final FactoryConnectionPage.Model model = FactoryConnectionPageTest.model();
		model.tested = true;
		model.ready = true;
		final FactoryConnectionPage.ServerLine llm = new FactoryConnectionPage.ServerLine("llm");
		llm.pending = true;
		llm.ok = true; // even if a caller mistakenly sets this, "pending" must win: no dot, no ok/bad colour
		model.results.add(llm);
		final String html = FactoryConnectionPage.render(model);
		FactoryConnectionPageTest.check("no connector: the line says the factory runs on its rules",
			html.indexOf(Messages.get(FactoryConnectionPage.class, Locale.ENGLISH, "connection.results.llm.none")) >= 0);
		FactoryConnectionPageTest.check("a pending line carries neither the good nor the bad dot", html.indexOf("mdmf-dot") < 0);
	}

	/** llm-connector-combo lot (2026-09-24): the language model line now reports a real call. */
	private static void llmLineReportsTheRealCall()
	{
		final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		final com.fasterxml.jackson.databind.node.ObjectNode ok = mapper.createObjectNode().put("configured", true).put("connector", "OpenAI").put("provider", "openai")
			.put("model", "gpt-5-mini").put("ok", true).put("elapsedMs", 812L).put("answer", "OK");
		final FactoryConnectionPage.ServerLine okLine = FactoryConnectionUserService.llmLine(ok, Locale.ENGLISH);
		FactoryConnectionPageTest.check("an answered call is ok", okLine.ok && !okLine.pending);
		FactoryConnectionPageTest.check("it names connector, provider, model, time and answer: " + okLine.okText,
			"connector OpenAI \u00B7 openai gpt-5-mini answered in 812 ms: \"OK\"".equals(okLine.okText));

		final com.fasterxml.jackson.databind.node.ObjectNode bad = mapper.createObjectNode().put("configured", true).put("connector", "OpenAI").put("ok", false)
			.put("problem", "HTTP 401 from https://api.openai.com/v1/chat/completions: invalid key");
		final FactoryConnectionPage.ServerLine badLine = FactoryConnectionUserService.llmLine(bad, Locale.ENGLISH);
		FactoryConnectionPageTest.check("a refused call is bad, with the provider's answer", !badLine.ok && badLine.problem.indexOf("HTTP 401") >= 0);

		final FactoryConnectionPage.ServerLine none = FactoryConnectionUserService.llmLine(mapper.createObjectNode().put("configured", false), Locale.ENGLISH);
		FactoryConnectionPageTest.check("no connector is neutral, not a failure", none.pending);

		final FactoryConnectionPage.Model model = FactoryConnectionPageTest.model();
		model.tested = true;
		model.results.add(okLine);
		model.results.add(badLine);
		final String html = FactoryConnectionPage.render(model);
		FactoryConnectionPageTest.check("the ok line renders its own sentence and a good dot", html.indexOf("gpt-5-mini answered in 812 ms") >= 0 && html.indexOf("mdmf-dot-good") >= 0);
		FactoryConnectionPageTest.check("the bad line renders the refusal and a bad dot", html.indexOf("HTTP 401 from") >= 0 && html.indexOf("mdmf-dot-bad") >= 0);
	}

	private static void saveFailureShowsTheErrorAndNoResults()
	{
		final FactoryConnectionPage.Model model = FactoryConnectionPageTest.model();
		model.saveError = "boom";
		final String html = FactoryConnectionPage.render(model);
		FactoryConnectionPageTest.check("the save error is shown", html.indexOf("boom") >= 0);
		FactoryConnectionPageTest.check("mdmf-notice-bad marks the save error", html.indexOf("mdmf-notice-bad") >= 0);
		FactoryConnectionPageTest.check("a save failure never shows stale server results", html.indexOf("mdmf-mcp-list") < 0);
	}

	private static void frenchRendersAccentedLabels()
	{
		final FactoryConnectionPage.Model model = FactoryConnectionPageTest.model();
		model.locale = Locale.FRENCH;
		final String html = FactoryConnectionPage.render(model);
		FactoryConnectionPageTest.check("the French section title is used, correctly accented", html.indexOf("Identités et droits") >= 0);
		FactoryConnectionPageTest.check("no stray backslash-u escape leaked into the rendered page", html.indexOf("\\u") < 0);
	}

	private static FactoryConnectionPage.Model model()
	{
		final FactoryConnectionPage.Model model = new FactoryConnectionPage.Model();
		model.locale = Locale.ENGLISH;
		return model;
	}

	private static FactoryConnectionPage.ServerLine line(final String pServer, final boolean pOk, final int pTools, final String pProblem)
	{
		final FactoryConnectionPage.ServerLine line = new FactoryConnectionPage.ServerLine(pServer);
		line.ok = pOk;
		line.tools = Integer.valueOf(pTools);
		line.problem = pProblem;
		return line;
	}

	/** llm-connector-combo lot (2026-09-24): a searchable picker instead of a free text id. */
	private static void llmPickerListsConnectorsWithANoneOption()
	{
		final FactoryConnectionPage.Model model = FactoryConnectionPageTest.model();
		model.llmConnector = "claude";
		model.llmOptions.add(new FactoryConnectionPage.LlmOption("claude", "Claude Sonnet", "anthropic", "claude-sonnet-5", true));
		model.llmOptions.add(new FactoryConnectionPage.LlmOption("ollama", "Local Ollama", "ollama", "llama3", false));
		final String html = FactoryConnectionPage.render(model);
		FactoryConnectionPageTest.check("the id travels in a hidden field named mdmfLlm", html.indexOf("type=\"hidden\" id=\"mdmfLlm\" name=\"mdmfLlm\" value=\"claude\"") >= 0);
		FactoryConnectionPageTest.check("no free text input named mdmfLlm is left", html.indexOf("type=\"text\" id=\"mdmfLlm\"") < 0);
		FactoryConnectionPageTest.check("the picked label is shown in the search input", html.indexOf("id=\"mdmfLlmSearch\"") >= 0 && html.indexOf("value=\"Claude Sonnet\" data-mdmf-picked-label=\"Claude Sonnet\"") >= 0);
		FactoryConnectionPageTest.check("a 'none' option comes first", html.indexOf("data-mdmf-id=\"\"") >= 0 && html.indexOf("data-mdmf-id=\"\"") < html.indexOf("data-mdmf-id=\"claude\""));
		FactoryConnectionPageTest.check("provider and model are searchable", html.indexOf("data-search=\"claude sonnet claude anthropic claude-sonnet-5\"") >= 0);
		FactoryConnectionPageTest.check("an inactive connector is flagged", html.indexOf(Messages.get(FactoryConnectionPage.class, Locale.ENGLISH, "connection.field.llm.inactive")) >= 0);
		FactoryConnectionPageTest.check("the saved one is marked picked", html.indexOf("mdmf-combo-option picked\" data-mdmf-id=\"claude\"") >= 0);
	}

	private static void llmPickerKeepsASavedIdThatNoLongerExists()
	{
		final FactoryConnectionPage.Model model = FactoryConnectionPageTest.model();
		model.llmConnector = "gone";
		final String html = FactoryConnectionPage.render(model);
		FactoryConnectionPageTest.check("the saved id stays an option, picked", html.indexOf("mdmf-combo-option picked\" data-mdmf-id=\"gone\"") >= 0);
		FactoryConnectionPageTest.check("and says it is missing", html.indexOf(Messages.get(FactoryConnectionPage.class, Locale.ENGLISH, "connection.field.llm.missing")) >= 0);
	}

	private static void scriptCarriesTheSharedComboAndItsInit()
	{
		final String script = FactoryConnectionPage.script();
		FactoryConnectionPageTest.check("combo.js is embedded", script.indexOf("window.mdmfInitCombo = function") >= 0);
		FactoryConnectionPageTest.check("and initialised on the picker", script.indexOf("window.mdmfInitCombo(\"mdmfLlmCombo\", \"mdmfLlmSearch\", \"mdmfLlmList\", \"mdmfLlm\", null)") >= 0);
	}

	/** connectors-bridge-design-time lot (2026-09-24): the ebx-connectors line says it runs through the bridge, as the person. */
	private static void connectorsLineSaysInJvm()
	{
		final FactoryConnectionPage.Model model = FactoryConnectionPageTest.model();
		model.tested = true;
		final FactoryConnectionPage.ServerLine line = new FactoryConnectionPage.ServerLine("ebx-connectors");
		line.ok = true;
		line.tools = Integer.valueOf(22);
		line.transport = "in-jvm";
		model.results.add(line);
		model.locale = Locale.FRENCH;
		final String html = FactoryConnectionPage.render(model);
		FactoryConnectionPageTest.check("the French sentence keeps its apostrophe and its count: " + html,
			html.indexOf("pont en JVM, s&#39;ex\u00E9cute sous votre identit\u00E9 (sans compte) \u00B7 22 outils") >= 0);
	}

	/** lot 3 (2026-09-24): an EBX server reached as the session's user says so, apostrophe intact. */
	private static void ebxServerLineSaysSession()
	{
		final FactoryConnectionPage.Model model = FactoryConnectionPageTest.model();
		model.tested = true;
		model.locale = Locale.FRENCH;
		final FactoryConnectionPage.ServerLine line = new FactoryConnectionPage.ServerLine("data-model");
		line.ok = true;
		line.tools = Integer.valueOf(29);
		line.transport = "session";
		model.results.add(line);
		final String html = FactoryConnectionPage.render(model);
		FactoryConnectionPageTest.check("session line in French: " + html,
			html.indexOf("s&#39;ex\u00E9cute sous votre identit\u00E9 (jeton de session, sans mot de passe) \u00B7 29 outils") >= 0);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
