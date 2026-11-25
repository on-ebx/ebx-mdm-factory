package com.on.ebx.factory.ebx.ui;

import com.on.ebx.factory.ebx.EngineRefusal;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.factory.core.Assets;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.ebx.AgentRuntime;
import com.on.ebx.factory.ebx.McpServers;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.security.PasswordTypeEncryption;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;
import com.orchestranetworks.ui.UIButtonSpecSubmit;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceDisplayConfigurator;
import com.orchestranetworks.userservice.UserServiceEvent;
import com.orchestranetworks.userservice.UserServiceEventContext;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;
import com.orchestranetworks.userservice.UserServiceProcessEventOutcomeContext;
import com.orchestranetworks.userservice.UserServiceSetupDisplayContext;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;
import com.orchestranetworks.userservice.UserServiceValidateContext;

/**
 * The "Connexion" screen (mockup screen 1): the factory's one agent account (base URL, user, password) and
 * the language model connector, entered once, plus a "Tester la connexion" button that saves the form and
 * runs {@link McpServers#test}. The password is never redisplayed — only whether one is stored — and is
 * written with EBX's own reversible encryption ({@link PasswordTypeEncryption}), the same call already used
 * to write {@code GovernanceSettings.linkSecret} in ebx-data-catalog's installer
 * ({@code CatalogInstaller.java}, lot 17): {@code new PasswordTypeEncryption().encrypt(raw)} into the
 * {@code ValueContextForUpdate}, read back with the static {@code PasswordTypeEncryption.decrypt} that
 * {@link McpServers} already uses. Registered on the {@code FactorySettings} table view; restricted to
 * administrators in {@link FactoryConnectionServiceDeclaration}, and re-checked here since the button also
 * writes the account credentials.
 * <p>
 * Like {@code ProductWizardUserService}, the instance lives only for the page's request/response: what was
 * typed and what came back from the test are kept on the instance from the event to the next rendering.
 */
public final class FactoryConnectionUserService implements UserService<TableViewEntitySelection>
{
	static final String SUBMIT_ID = "mdmfTest";

	Adaptation dataset;
	Locale locale = Locale.ENGLISH;

	String ebxBaseUrl = "";
	String ebxNamespace = "";
	String agentUser = "";
	boolean passwordSet;
	String llmConnector = "";

	boolean tested;
	String saveError;
	FactoryConnectionPage.Model lastResult;
	/** The caller of the current request (wave 11: the list of language model connections is read through the Connectors contract as this session); null in a test. */
	private Session session;
	/** The table the service was opened on: its service permission is read there (second permissions pass, 2026-10-14). */
	private com.onwbp.adaptation.AdaptationTable openedOn;

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		this.dataset = pContext.getEntitySelection().getDataset();
		this.openedOn = pContext.getEntitySelection().getTable();
		this.session = pContext.getSession();
		this.readCurrentSettings();
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<TableViewEntitySelection> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(Messages.get(FactoryConnectionUserService.class, this.locale, "connection.title"));
		// second permissions pass (2026-10-14): dataspace, dataset, the service permission on the table it was opened on
		String closed = ProjectAccess.refusal(this.session, this.dataset, this.locale);
		if (closed == null && !ProjectAccess.serviceOnTable(this.session, FactoryConnectionServiceDeclaration.SERVICE_KEY, this.openedOn))
		{
			closed = Messages.get(FactoryConnectionUserService.class, this.locale, "screen.service.refused");
		}
		if (closed != null)
		{
			final String message = closed;
			pConfigurator.setRightButtons(pConfigurator.newCloseButton());
			pConfigurator.setContent(new UserServicePane()
			{
				@Override
				public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
				{
					pWriter.add(ThemeTokens.styleTagFor(pPaneContext));
					FactoryHtml.writeClosed(pWriter, message);
				}
			});
			return;
		}
		final UIButtonSpecSubmit test = pConfigurator.newSubmitButton(Messages.get(FactoryConnectionUserService.class, this.locale, "connection.test.button"),
			new UserServiceEvent()
			{
				@Override
				public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
				{
					FactoryConnectionUserService.this.test(pEventContext);
					return null;
				}
			});
		test.setId(FactoryConnectionUserService.SUBMIT_ID);
		pConfigurator.setLeftButtons(test);
		pConfigurator.setRightButtons(pConfigurator.newCloseButton());
		// audit F-41: an unplanned failure of the rendering becomes a localized notice with a logged reference, never a blank pane
		pConfigurator.setContent(ScreenFailure.guard("connection settings", this.locale, new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				FactoryConnectionUserService.this.write(pWriter);
			}
		}));
	}

	// ------------------------------------------------------------------ reading and saving the settings

	private void readCurrentSettings()
	{
		// second permissions pass (2026-10-14): the settings row and its fields through the user's view - a hidden row or field shows empty
		final com.on.ebx.factory.ebx.UserView view = com.on.ebx.factory.ebx.UserView.of(this.session);
		final Adaptation row = this.dataset == null ? null : view.lookup(this.dataset.getTable(FactoryPaths.SETTINGS), FactoryPaths.DEFAULT_SETTINGS);
		if (row == null)
		{
			return;
		}
		this.ebxBaseUrl = view.text(row, FactoryPaths.Settings.EBX_BASE_URL);
		this.ebxNamespace = view.text(row, FactoryPaths.Settings.EBX_NAMESPACE);
		this.agentUser = view.text(row, FactoryPaths.Settings.AGENT_USER);
		this.llmConnector = view.text(row, FactoryPaths.Settings.LLM_CONNECTOR);
		final String password = view.text(row, FactoryPaths.Settings.AGENT_PASSWORD);
		this.passwordSet = password != null && !password.isEmpty();
	}

	static Adaptation settingsRow(final Adaptation pDataset)
	{
		if (pDataset == null)
		{
			return null;
		}
		return pDataset.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
	}

	/** Reads the posted form, saves it (the password only when a new one was typed), then runs the test. */
	void test(final UserServiceEventContext pContext)
	{
		this.tested = false;
		this.saveError = null;
		// the permission rule of FactoryConnectionServiceDeclaration only hides the toolbar/perspective action;
		// it does not stop a forged request from reaching this event handler directly, so the role is re-checked
		// here too, the same way ConnectorDiagnosticsUserService.isReadable re-checks its own permission rule
		if (!pContext.getSession().isUserInRole(com.orchestranetworks.service.Profile.ADMINISTRATOR))
		{
			this.saveError = Messages.get(FactoryConnectionUserService.class, this.locale, "connection.forbidden");
			return;
		}
		// second permissions pass (2026-10-14): the settings row must be writable for this user (the save runs with the user's own session, EBX
		// checks it again field by field); a hidden or read-only row is refused before anything is posted to it
		final com.on.ebx.factory.ebx.UserView view = com.on.ebx.factory.ebx.UserView.of(pContext.getSession());
		if (ProjectAccess.refusal(pContext.getSession(), this.dataset, this.locale) != null
			|| !ProjectAccess.serviceOnTable(pContext.getSession(), FactoryConnectionServiceDeclaration.SERVICE_KEY, this.openedOn)
			|| !view.recordWritable(view.lookup(this.dataset.getTable(FactoryPaths.SETTINGS), FactoryPaths.DEFAULT_SETTINGS)))
		{
			this.saveError = Messages.get(FactoryConnectionUserService.class, this.locale, "connection.forbidden");
			return;
		}
		final String url = FactoryConnectionUserService.param(pContext, "mdmfUrl");
		final String namespace = FactoryConnectionUserService.param(pContext, "mdmfNamespace");
		final String user = FactoryConnectionUserService.param(pContext, "mdmfUser");
		final String password = FactoryConnectionUserService.param(pContext, "mdmfPassword");
		final String llm = FactoryConnectionUserService.param(pContext, "mdmfLlm");
		this.ebxBaseUrl = url;
		this.ebxNamespace = namespace;
		this.agentUser = user;
		this.llmConnector = llm;
		try
		{
			FactoryConnectionUserService.save(pContext.getSession(), this.dataset, url, namespace, user, password.isEmpty() ? null : password, llm);
		}
		catch (final OperationException ex)
		{
			this.saveError = EngineNotice.inline(ex, this.locale);
			return;
		}
		this.readCurrentSettings();
		this.runTest(pContext.getSession());
	}

	private void runTest(final com.orchestranetworks.service.Session pSession)
	{
		final JsonNode answer = McpServers.test(pSession, this.dataset);
		this.lastResult = new FactoryConnectionPage.Model();
		for (final JsonNode line : answer.path("servers"))
		{
			final FactoryConnectionPage.ServerLine out = new FactoryConnectionPage.ServerLine(line.path("server").asText());
			out.name = line.path("name").asText("");
			out.ok = line.path("ok").asBoolean(false);
			out.tools = line.has("tools") ? Integer.valueOf(line.path("tools").asInt(0)) : null;
			out.elapsedMs = line.has("elapsedMs") ? Long.valueOf(line.path("elapsedMs").asLong(0)) : null;
			out.problem = line.has("problem") ? line.path("problem").asText() : null;
			final JsonNode probe = line.path("probe");
			out.probeTool = probe.hasNonNull("tool") ? probe.path("tool").asText() : null;
			out.probeOk = probe.path("ok").asBoolean(false);
			out.transport = line.has("transport") ? line.path("transport").asText() : null;
			this.lastResult.results.add(out);
		}
		this.lastResult.results.add(FactoryConnectionUserService.llmLine(answer.path("llm"), this.locale));
		this.lastResult.ready = answer.path("ready").asBoolean(false);
		final JsonNode endpoint = answer.path("endpoint");
		this.lastResult.endpointUrl = endpoint.path("baseUrl").asText(null);
		this.lastResult.endpointDetail = endpoint.path("detail").asText("");
		this.lastResult.endpointProblem = endpoint.has("problem") ? endpoint.path("problem").asText() : null;
		this.lastResult.lineageNamespace = answer.path("lineageNamespace").asText("");
		this.lastResult.agentFallback = answer.has("agentFallback") ? answer.path("agentFallback").asText() : null;
		this.tested = true;
	}

	/** The language model line from {@code McpServers.test}'s {@code llm} object ({@code AgentRuntime.testLlm}). Package-private for the test. */
	static FactoryConnectionPage.ServerLine llmLine(final JsonNode pLlm, final Locale pLocale)
	{
		final FactoryConnectionPage.ServerLine line = new FactoryConnectionPage.ServerLine("llm");
		if (!pLlm.path("configured").asBoolean(false))
		{
			line.pending = true;
			return line;
		}
		line.name = pLlm.path("connector").asText("");
		line.ok = pLlm.path("ok").asBoolean(false);
		line.elapsedMs = pLlm.has("elapsedMs") ? Long.valueOf(pLlm.path("elapsedMs").asLong(0)) : null;
		final String who = (pLlm.path("provider").asText("") + " " + pLlm.path("model").asText("")).trim();
		if (line.ok)
		{
			line.okText = Messages.get(FactoryConnectionUserService.class, pLocale, "connection.results.llm.ok", line.name, who.isEmpty() ? "?" : who,
				String.valueOf(line.elapsedMs == null ? 0L : line.elapsedMs.longValue()), pLlm.path("answer").asText(""));
		}
		else
		{
			line.problem = Messages.get(FactoryConnectionUserService.class, pLocale, "connection.results.llm.bad", line.name, pLlm.path("problem").asText(""));
		}
		return line;
	}

	private static void addLlmOptions(final FactoryConnectionPage.Model pModel, final Adaptation pDataset, final Session pSession)
	{
		pModel.llmOptions.clear();
		if (pDataset == null)
		{
			return;
		}
		for (final AgentRuntime.LlmConnectorOption option : AgentRuntime.llmConnectors(pDataset.getHome().getRepository(), pSession))
		{
			pModel.llmOptions.add(new FactoryConnectionPage.LlmOption(option.id, option.label, option.provider, option.model, option.active));
		}
	}

	private static String param(final UserServiceEventContext pContext, final String pName)
	{
		final String value = pContext.getParameter(pName);
		return value == null ? "" : value.trim();
	}

	private static void save(final Session pSession, final Adaptation pDataset, final String pUrl, final String pNamespace, final String pUser, final String pPassword, final String pLlm)
		throws OperationException
	{
		final AdaptationTable settings = pDataset.getTable(FactoryPaths.SETTINGS);
		final Adaptation existing = settings.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		if (existing == null)
		{
			throw EngineRefusal.coded("missing.settings", "MDM Factory settings: the DEFAULT row is missing (the module did not finish installing)");
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(existing.getAdaptationName());
				value.setValue(pUrl.isEmpty() ? null : pUrl, FactoryPaths.Settings.EBX_BASE_URL);
				value.setValue(pNamespace.isEmpty() ? null : pNamespace, FactoryPaths.Settings.EBX_NAMESPACE);
				value.setValue(pUser.isEmpty() ? null : pUser, FactoryPaths.Settings.AGENT_USER);
				if (pPassword != null)
				{
					// same call as CatalogInstaller's GovernanceSettings.linkSecret: EBX's own reversible
					// encryption, read back by PasswordTypeEncryption.decrypt (McpServers already does).
					value.setValue(new PasswordTypeEncryption().encrypt(pPassword), FactoryPaths.Settings.AGENT_PASSWORD);
				}
				value.setValue(pLlm.isEmpty() ? null : pLlm, FactoryPaths.Settings.LLM_CONNECTOR);
				pContext.doModifyContent(existing, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveFailed", result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	// ------------------------------------------------------------------ the page

	void write(final UserServicePaneWriter pWriter)
	{
		// fix/review-assets (2026-09-21): Assets.version() changes with every build.sh run - see its own Javadoc
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(com.orchestranetworks.ui.ResourceType.STYLESHEET, "factory/factory.css")).add("?v=")
			.add(Assets.version()).add("\"/>");
		pWriter.add(FactoryConnectionPage.render(this.model()));
		pWriter.addJS_cr(FactoryConnectionPage.script());
	}

	FactoryConnectionPage.Model model()
	{
		if (this.tested && this.lastResult != null)
		{
			this.lastResult.locale = this.locale;
			this.lastResult.ebxBaseUrl = this.ebxBaseUrl;
			this.lastResult.ebxNamespace = this.ebxNamespace;
			this.lastResult.agentUser = this.agentUser;
			this.lastResult.passwordSet = this.passwordSet;
			this.lastResult.llmConnector = this.llmConnector;
			FactoryConnectionUserService.addLlmOptions(this.lastResult, this.dataset, this.session);
			this.lastResult.tested = true;
			this.lastResult.saveError = this.saveError;
			return this.lastResult;
		}
		final FactoryConnectionPage.Model model = new FactoryConnectionPage.Model();
		model.locale = this.locale;
		model.ebxBaseUrl = this.ebxBaseUrl;
		model.ebxNamespace = this.ebxNamespace;
		model.agentUser = this.agentUser;
		model.passwordSet = this.passwordSet;
		model.llmConnector = this.llmConnector;
		FactoryConnectionUserService.addLlmOptions(model, this.dataset, this.session);
		model.tested = false;
		model.saveError = this.saveError;
		return model;
	}

	@Override
	public void validate(final UserServiceValidateContext<TableViewEntitySelection> pContext)
	{
		// FactoryConnectionUserService#save reports its own errors; nothing to check ahead of the event
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<TableViewEntitySelection> pContext, final UserServiceEventOutcome pOutcome)
	{
		return pOutcome;
	}
}
