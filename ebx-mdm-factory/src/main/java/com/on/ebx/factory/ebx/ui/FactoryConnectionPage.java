package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The HTML of the "Connexion" screen (mockup screen 1, {@code docs/mockups/mdm-factory.html}), from plain
 * values only (no EBX object), so it renders from fixtures in {@code FactoryConnectionPageTest}. Reproduces
 * the approved mockup's structure and wording: the identity panel (base URL, agent user, password presence
 * only, language model connector), one line per MCP server after a test, and the reminder that what the
 * agent account may do in EBX is what the factory may do.
 * <p>
 * Two deliberate departures from the static mockup, both explained in the module's final report rather than
 * hidden here: (1) the mockup's "Exemple d'échec" panel is pedagogical illustration for the mockup review
 * and is not reproduced with fabricated data on the live screen — a real failure shows through the same
 * result line, using {@link com.on.ebx.factory.ebx.McpServers#test}'s own message; (2) no "last tested at"
 * timestamp is persisted across page loads — the result list shown is always the one just computed by the
 * button click that produced this rendering, never a stale one implied to still be current.
 */
final class FactoryConnectionPage
{
	private FactoryConnectionPage()
	{
	}

	/** One line of {@code McpServers.test}'s answer, or the language model connector's own placeholder line. */
	static final class ServerLine
	{
		final String server;
		String name = "";
		boolean ok;
		Integer tools;
		Long elapsedMs;
		String problem;
		/** The probe call of {@code McpServers.test} (tool name, whether it answered): shown on an ok line so the reader sees the account may CALL, not only list. */
		String probeTool;
		boolean probeOk;
		/** True for the language model line when no connector is set (rules only): neutral, no dot, no "ok/bad" colour. */
		boolean pending;
		/** The language model line once tested: its own sentence on an ok line, instead of the MCP tool count. */
		String okText;
		/** How the server was reached ({@code McpServers.mode}): {@code in-jvm}, {@code session} or {@code agent}. */
		String transport;

		ServerLine(final String pServer)
		{
			this.server = pServer;
		}
	}

	static final class LlmOption
	{
		final String id;
		final String label;
		final String provider;
		final String model;
		final boolean active;

		LlmOption(final String pId, final String pLabel, final String pProvider, final String pModel, final boolean pActive)
		{
			this.id = pId;
			this.label = pLabel;
			this.provider = pProvider == null ? "" : pProvider;
			this.model = pModel == null ? "" : pModel;
			this.active = pActive;
		}
	}

	static final class Model
	{
		Locale locale = Locale.ENGLISH;
		String ebxBaseUrl = "";
		String ebxNamespace = "";
		String agentUser = "";
		boolean passwordSet;
		String llmConnector = "";
		/** Every connector of type llm in ebx-connectors ({@code AgentRuntime.llmConnectors}), for the picker. */
		final List<LlmOption> llmOptions = new ArrayList<LlmOption>();
		/** True once a test (which also saves the form) has produced {@link #results} for this rendering. */
		boolean tested;
		final List<ServerLine> results = new ArrayList<ServerLine>();
		boolean ready;
		/** The address the test called ({@code LocalEndpoint}), how it was found, or why none was. */
		String endpointUrl;
		String endpointDetail;
		String endpointProblem;
		/** The namespace the lineage declarations carry ({@code LineageNamespace}). */
		String lineageNamespace;
		/** {@code ALLOWED} or {@code FORBIDDEN}: may a call fall back to the agent account ({@code CredentialPolicy}). */
		String agentFallback;
		/** Set when the posted form could not be saved; the test itself did not run. */
		String saveError;
	}

	static String esc(final String pText)
	{
		return FactoryHtml.esc(pText);
	}

	static String m(final Model pModel, final String pKey, final Object... pArgs)
	{
		return Messages.get(FactoryConnectionPage.class, pModel.locale, pKey, pArgs);
	}

	static String e(final Model pModel, final String pKey, final Object... pArgs)
	{
		return FactoryConnectionPage.esc(FactoryConnectionPage.m(pModel, pKey, pArgs));
	}

	static String render(final Model pModel)
	{
		final StringBuilder sb = new StringBuilder(8192);
		sb.append("<div class=\"mdmf mdmf-connection\">");

		sb.append("<section class=\"mdmf-panel\"><h2>").append(FactoryConnectionPage.e(pModel, "connection.section.title")).append("</h2>");
		sb.append("<p class=\"mdmf-lead\">").append(FactoryConnectionPage.e(pModel, "connection.section.lead")).append("</p>");

		if (pModel.saveError != null)
		{
			sb.append("<div class=\"mdmf-notice mdmf-notice-bad\" role=\"alert\">").append(FactoryConnectionPage.e(pModel, "connection.save.failed", pModel.saveError))
				.append("</div>");
		}

		sb.append("<div class=\"mdmf-grid-2\">");
		FactoryConnectionPage.textField(sb, pModel, "mdmfUrl", "connection.field.url", "connection.field.url.hint", pModel.ebxBaseUrl, "text");
		FactoryConnectionPage.textField(sb, pModel, "mdmfNamespace", "connection.field.namespace", "connection.field.namespace.hint", pModel.ebxNamespace, "text");
		FactoryConnectionPage.textField(sb, pModel, "mdmfUser", "connection.field.user", "connection.field.user.hint", pModel.agentUser, "text");

		sb.append("<div class=\"mdmf-field\"><label for=\"mdmfPassword\">").append(FactoryConnectionPage.e(pModel, "connection.field.password")).append("</label>")
			.append("<input type=\"password\" id=\"mdmfPassword\" name=\"mdmfPassword\" value=\"\" autocomplete=\"off\" placeholder=\"")
			.append(pModel.passwordSet ? FactoryConnectionPage.e(pModel, "connection.field.password.placeholder.set") : FactoryConnectionPage.e(pModel, "connection.field.password.placeholder.unset"))
			.append("\"/><p class=\"mdmf-hint\">")
			.append(pModel.passwordSet ? FactoryConnectionPage.e(pModel, "connection.field.password.set") : FactoryConnectionPage.e(pModel, "connection.field.password.unset"))
			.append(" ").append(FactoryConnectionPage.e(pModel, "connection.field.password.hint.target")).append("</p></div>");

		FactoryConnectionPage.llmCombo(sb, pModel);
		sb.append("</div>");

		sb.append("<p class=\"mdmf-hint\">").append(pModel.tested ? FactoryConnectionPage.e(pModel, "connection.test.just") : FactoryConnectionPage.e(pModel, "connection.test.never"))
			.append("</p>");

		if (pModel.tested)
		{
			sb.append("<p class=\"mdmf-hint\">");
			if (pModel.endpointProblem != null)
			{
				sb.append(FactoryConnectionPage.e(pModel, "connection.endpoint.none", pModel.endpointProblem));
			}
			else
			{
				sb.append(FactoryConnectionPage.e(pModel, "connection.endpoint", pModel.endpointUrl, pModel.endpointDetail));
			}
			sb.append(" ").append(FactoryConnectionPage.e(pModel, "connection.namespace", pModel.lineageNamespace));
			if (pModel.agentFallback != null)
			{
				sb.append(" ").append(FactoryConnectionPage.e(pModel, "FORBIDDEN".equals(pModel.agentFallback) ? "connection.fallback.forbidden" : "connection.fallback.allowed"));
			}
			sb.append("</p>");
			sb.append("<ul class=\"mdmf-mcp-list\">");
			for (final ServerLine line : pModel.results)
			{
				FactoryConnectionPage.serverLine(sb, pModel, line);
			}
			sb.append("</ul>");
			sb.append("<div class=\"mdmf-notice ").append(pModel.ready ? "mdmf-notice-ok" : "mdmf-notice-warn").append("\">")
				.append(pModel.ready ? FactoryConnectionPage.e(pModel, "connection.ready.yes") : FactoryConnectionPage.e(pModel, "connection.ready.no")).append("</div>");
		}
		sb.append("</section>");

		sb.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(FactoryConnectionPage.e(pModel, "connection.warning")).append("</div>");
		sb.append("</div>");
		return sb.toString();
	}

	/**
	 * The language model connector picker: the factory's searchable combobox ({@code combo.js}, same markup as the
	 * wizard's catalog-source picker), one option per llm connector with its provider/model and an "inactive" chip,
	 * a first "none" option (rules only), and the saved id kept as an option even when it no longer exists, so
	 * opening the screen never silently changes the setting. The picked id travels in the hidden {@code mdmfLlm}
	 * field the save already reads; no round trip on pick.
	 */
	static void llmCombo(final StringBuilder pOut, final Model pModel)
	{
		final List<LlmOption> options = new ArrayList<LlmOption>();
		options.add(new LlmOption("", FactoryConnectionPage.m(pModel, "connection.field.llm.rulesOnly"), "", "", true));
		boolean savedListed = pModel.llmConnector.isEmpty();
		for (final LlmOption option : pModel.llmOptions)
		{
			options.add(option);
			savedListed |= option.id.equals(pModel.llmConnector);
		}
		if (!savedListed)
		{
			options.add(new LlmOption(pModel.llmConnector, pModel.llmConnector, null, FactoryConnectionPage.m(pModel, "connection.field.llm.missing"), false));
		}
		String pickedLabel = "";
		for (final LlmOption option : options)
		{
			if (option.id.equals(pModel.llmConnector))
			{
				pickedLabel = option.label;
			}
		}
		pOut.append("<div class=\"mdmf-field\"><label for=\"mdmfLlmSearch\">").append(FactoryConnectionPage.e(pModel, "connection.field.llm")).append("</label>");
		pOut.append("<input type=\"hidden\" id=\"mdmfLlm\" name=\"mdmfLlm\" value=\"").append(FactoryConnectionPage.esc(pModel.llmConnector)).append("\"/>");
		pOut.append("<div class=\"mdmf-combo\" id=\"mdmfLlmCombo\" data-mdmf-nomatch=\"").append(FactoryConnectionPage.e(pModel, "connection.field.llm.noMatch")).append("\">");
		pOut.append("<span class=\"mdmf-combo-icon\" aria-hidden=\"true\">&#128269;</span>");
		pOut.append("<input type=\"text\" id=\"mdmfLlmSearch\" role=\"combobox\" aria-autocomplete=\"list\" aria-expanded=\"false\" aria-controls=\"mdmfLlmList\"")
			.append(" autocomplete=\"off\" spellcheck=\"false\" placeholder=\"").append(FactoryConnectionPage.e(pModel, "connection.field.llm.search")).append("\" value=\"")
			.append(FactoryConnectionPage.esc(pickedLabel)).append("\" data-mdmf-picked-label=\"").append(FactoryConnectionPage.esc(pickedLabel)).append("\"/>");
		pOut.append("<ul class=\"mdmf-combo-list\" id=\"mdmfLlmList\" role=\"listbox\" hidden=\"hidden\">");
		for (final LlmOption option : options)
		{
			final boolean picked = option.id.equals(pModel.llmConnector);
			final String search = (option.label + " " + option.id + " " + option.provider + " " + option.model).toLowerCase(Locale.ROOT);
			pOut.append("<li role=\"option\" class=\"mdmf-combo-option").append(picked ? " picked" : "").append("\" data-mdmf-id=\"").append(FactoryConnectionPage.esc(option.id))
				.append("\" data-mdmf-label=\"").append(FactoryConnectionPage.esc(option.label)).append("\" data-search=\"").append(FactoryConnectionPage.esc(search))
				.append("\" aria-selected=\"").append(picked ? "true" : "false").append("\">");
			pOut.append("<span class=\"mdmf-combo-label\">").append(FactoryConnectionPage.esc(option.label)).append("</span><span class=\"mdmf-combo-meta\">");
			if (!option.provider.isEmpty())
			{
				pOut.append("<span class=\"mdmf-chip mdmf-chip-muted\">").append(FactoryConnectionPage.esc(option.provider)).append("</span>");
			}
			if (!option.model.isEmpty())
			{
				pOut.append("<span class=\"mdmf-combo-connector\">").append(FactoryConnectionPage.esc(option.model)).append("</span>");
			}
			if (!option.active)
			{
				pOut.append("<span class=\"mdmf-chip mdmf-chip-warn\">").append(FactoryConnectionPage.e(pModel, "connection.field.llm.inactive")).append("</span>");
			}
			pOut.append("</span></li>");
		}
		pOut.append("<li class=\"mdmf-combo-empty\" hidden=\"hidden\"></li></ul></div>");
		pOut.append("<p class=\"mdmf-hint\">").append(FactoryConnectionPage.e(pModel, "connection.field.llm.hint")).append("</p></div>");
	}

	/** Wires the picker above: {@code combo.js} then one {@code mdmfInitCombo} call, no pick button (nothing to post until Test). */
	static String script()
	{
		return FactoryHtml.resourceText("combo.js") + "\nwindow.mdmfInitCombo(\"mdmfLlmCombo\", \"mdmfLlmSearch\", \"mdmfLlmList\", \"mdmfLlm\", null);\n";
	}

	private static void textField(final StringBuilder pOut, final Model pModel, final String pId, final String pLabelKey, final String pHintKey, final String pValue,
		final String pType)
	{
		pOut.append("<div class=\"mdmf-field\"><label for=\"").append(pId).append("\">").append(FactoryConnectionPage.e(pModel, pLabelKey)).append("</label>")
			.append("<input type=\"").append(pType).append("\" id=\"").append(pId).append("\" name=\"").append(pId).append("\" value=\"")
			.append(FactoryConnectionPage.esc(pValue)).append("\" autocomplete=\"off\"/>")
			.append("<p class=\"mdmf-hint\">").append(FactoryConnectionPage.e(pModel, pHintKey)).append("</p></div>");
	}

	private static void serverLine(final StringBuilder pOut, final Model pModel, final ServerLine pLine)
	{
		final String cls = pLine.pending ? "" : pLine.ok ? " mdmf-mcp-ok" : " mdmf-mcp-bad";
		pOut.append("<li class=\"").append(cls.trim()).append("\"><b>").append(FactoryConnectionPage.esc(pLine.server)).append("</b><span>");
		if (pLine.pending)
		{
			pOut.append(FactoryConnectionPage.e(pModel, "connection.results.llm.none"));
		}
		else if (pLine.ok && pLine.okText != null)
		{
			pOut.append(FactoryConnectionPage.esc(pLine.okText));
		}
		else if (pLine.ok)
		{
			pOut.append(FactoryConnectionPage.e(pModel, "in-jvm".equals(pLine.transport) ? "connection.results.okInJvm"
				: "session".equals(pLine.transport) ? "connection.results.okSession" : "connection.results.ok",
				pLine.tools == null ? "0" : String.valueOf(pLine.tools)));
			if (pLine.probeTool != null && pLine.probeOk)
			{
				pOut.append(" ").append(FactoryConnectionPage.e(pModel, "connection.results.probe.ok", pLine.probeTool));
			}
		}
		else
		{
			pOut.append(FactoryConnectionPage.esc(pLine.problem == null ? FactoryConnectionPage.m(pModel, "connection.results.bad.unknown") : pLine.problem));
		}
		pOut.append("</span>");
		if (!pLine.pending)
		{
			pOut.append("<i class=\"mdmf-dot ").append(pLine.ok ? "mdmf-dot-good" : "mdmf-dot-bad").append("\" aria-hidden=\"true\"></i>");
		}
		pOut.append("</li>");
	}
}
