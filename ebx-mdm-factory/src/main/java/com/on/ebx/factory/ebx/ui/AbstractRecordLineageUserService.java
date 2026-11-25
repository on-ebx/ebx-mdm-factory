package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.Assets;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.ebx.ProjectMasterEntities;
import com.on.ebx.factory.ebx.ProposalReviews;
import com.on.ebx.factory.ebx.RecordLineageReader;
import com.on.ebx.factory.lineage.RecordLineagePage;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.staging.StagingModel;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.UIButtonSpecSubmit;
import com.orchestranetworks.ui.selection.EntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceDisplayConfigurator;
import com.orchestranetworks.userservice.UserServiceEvent;
import com.orchestranetworks.userservice.UserServiceEventContext;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;
import com.orchestranetworks.userservice.UserServiceProcessEventOutcomeContext;
import com.orchestranetworks.userservice.UserServiceRequest;
import com.orchestranetworks.userservice.UserServiceSetupDisplayContext;
import com.orchestranetworks.userservice.UserServiceValidateContext;

/**
 * Everything the "Record lineage" screen does once it has a project/entity/key to look up: read through
 * {@link RecordLineageReader#read} and render through {@link RecordLineagePage}, plus the posted-back form
 * (project/entity/key selectors, project change, a fresh lookup) - shared, byte-for-byte, by the two ways
 * this module offers the screen:
 * <ul>
 * <li>{@link RecordLineageUserService} ({@code OnTableView} on {@code Project}) - the original lot 4
 * screen: deep link, toolbar action on a {@code Project} record, or a perspective entry with nothing
 * selected. See its own Javadoc.</li>
 * <li>{@link RecordLineageOnRecordUserService} ({@code OnRecord}, module-level - coordinator review,
 * 2026-09-22) - a record-level action on a MASTER record itself (a golden record in
 * {@code Companies_Master}/{@code Master}/{@code /root/Company}, say): project/entity/key are all resolved
 * from the record being viewed, so the first render already shows the lineage, but the SAME project/entity/
 * key form still lets a steward look up a different record without leaving the screen - see its own
 * Javadoc, and {@code RecordLineageOnRecordPermissionRule}'s for how the action is offered only on a
 * project's own promoted master tables.</li>
 * </ul>
 * <p>
 * Extracted (rather than one service delegating to the other, or the record-level one reaching into the
 * table-view one's private methods) because the two differ ONLY in how {@code S} resolves an initial
 * project/entity/key ({@link UserService#setupObjectContext}, left abstract) - {@code setupDisplay},
 * {@code decide}, {@code lookup} and every rendering method below are identical for both, generic purely
 * over the entity-selection type this class is never told to inspect.
 * <p>
 * <b>Never a second path to business data</b>: {@link #lookup} calls {@code RecordLineageReader.read} - in
 * process, with the CALLER's own {@link Session}, never an HTTP loopback to the module's own REST route -
 * and {@link #write} renders exactly what it returns; the permission redaction {@code RecordLineageReader}/
 * {@code RecordLineage.assemble} already enforce stays the one and only gate (see their own Javadoc,
 * `docs/RECORD-LINEAGE.md` "Authorisation").
 * <p>
 * <b>Errors are always plain words, never a stack trace</b>: {@link RecordLineageReader#read} already
 * throws {@link OperationException} subtypes carrying a human-readable message; {@link #lookup} surfaces
 * that message as the page notice and nothing else. Any other {@link RuntimeException} (a genuinely
 * unexpected failure) degrades the same way, with a generic message.
 */
abstract class AbstractRecordLineageUserService<S extends EntitySelection> implements UserService<S>
{
	Adaptation factory;
	Locale locale = Locale.ENGLISH;

	String projectCode;
	String entityName;
	String keyText = "";
	/** F2-112: how many contributors other than the golden itself precede the page shown ({@code NEXT_CONTRIBUTORS} moves it by one page). */
	int contributorOffset;

	String notice;
	String noticeKind = "ok";

	/** The last successful lookup's answer; null until one succeeds - see the class Javadoc for why a failure never leaves a stale answer on screen either (cleared at the start of every {@code LOOKUP}). */
	ObjectNode lineage;

	/**
	 * "Decide on a suspect" (2026-09-22 lot): {@code "ACCEPT"}/{@code "REJECT"} awaiting a SECOND click to
	 * confirm (task brief: "each with a confirmation") - null whenever nothing is pending. Cleared on every
	 * {@code LOOKUP}/{@code SELECT_PROJECT}/{@code CHANGE_PROJECT}, and after a decision is actually applied
	 * or cancelled, so a stale pending confirmation from a DIFFERENT record/suspect can never survive a
	 * navigation - the same "never leave a stale answer on screen" discipline {@link #lineage} already
	 * follows for a failed lookup.
	 */
	String pendingSuspectAction;
	String pendingSuspectKey;
	/** Second permissions pass (2026-10-14): the session of the current request - every read of the page goes through its view. */
	Session session;

	/** The service is enabled for this user where it was opened (EBX's service permissions); each subclass knows its selection. */
	abstract boolean serviceEnabled(Session pSession);

	/** A pure read with the user's view ({@code UserView#scoped}). */
	<T> T asUser(final java.util.function.Supplier<T> pRead)
	{
		return com.on.ebx.factory.ebx.UserView.scoped(com.on.ebx.factory.ebx.UserView.of(this.session), pRead);
	}

	// ------------------------------------------------------------------------------------------------- display

	/**
	 * {@code true} when project/entity/key are fixed by the record being viewed, so the lookup card should
	 * never be shown at all (coordinator review, 2026-09-23) - overridden by {@link RecordLineageOnRecordUserService},
	 * which resolves all three in {@code setupObjectContext} before the first render. Left {@code false} here
	 * (the default) for {@link RecordLineageUserService}, where a steward genuinely picks project/entity/key,
	 * so its own screen is byte-for-byte unchanged by this hook's addition. Read once per render, in {@link
	 * #write}, and fed straight into {@code RecordLineagePage.Input#recordBound} - see that field's own
	 * Javadoc for what it actually skips (and, just as importantly, what it does not: the notice/warning and
	 * the hidden {@code mdmfProjectCode}/{@code mdmfEntity}/{@code mdmfKey} inputs render exactly as before).
	 */
	boolean isRecordBound()
	{
		return false;
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<S> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		this.session = pContext.getSession();
		pConfigurator.setTitle(Messages.get(this.getClass(), this.locale, "lineage.label"));
		// second permissions pass (2026-10-14): the factory dataset (the project rows the lineage is read through) and the service permission, with
		// the user's session; the master, staging and landing records are then checked one by one by RecordLineageReader
		String closed = this.factory == null ? null : ProjectAccess.refusal(this.session, this.factory, this.locale);
		if (closed == null && !this.serviceEnabled(this.session))
		{
			closed = Messages.get(this.getClass(), this.locale, "screen.service.refused");
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
		final UIButtonSpecSubmit submit = pConfigurator.newSubmitButton(Messages.get(this.getClass(), this.locale, "lineage.lookup.button"), new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				AbstractRecordLineageUserService.this.decide(pEventContext);
				return null;
			}
		});
		submit.setId("mdmfSubmit");
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		pConfigurator.setLeftButtons(submit);
		pConfigurator.setRightButtons(close);
		// audit F-41: an unplanned failure of the rendering becomes a localized notice with a logged reference, never a blank pane
		pConfigurator.setContent(ScreenFailure.guard("record lineage", this.locale, new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				// second permissions pass (2026-10-14): the page's reads (projects, entities, labels) with the user's view - a pure read
				AbstractRecordLineageUserService.this.asUser(() -> {
					AbstractRecordLineageUserService.this.write(pWriter);
					return null;
				});
			}
		}));
	}

	// ------------------------------------------------------------------------------------------- the posted-back form

	void decide(final UserServiceEventContext pContext)
	{
		this.session = pContext.getSession();
		final String action = AbstractRecordLineageUserService.param(pContext, "mdmfAction");
		this.projectCode = AbstractRecordLineageUserService.blankToNull(AbstractRecordLineageUserService.param(pContext, "mdmfProjectCode"));
		this.entityName = AbstractRecordLineageUserService.blankToNull(AbstractRecordLineageUserService.param(pContext, "mdmfEntity"));
		this.keyText = AbstractRecordLineageUserService.param(pContext, "mdmfKey");

		if ("SELECT_PROJECT".equals(action))
		{
			this.selectProject(AbstractRecordLineageUserService.param(pContext, "mdmfProjectCode"));
			this.clearPendingSuspectDecision();
			return;
		}
		if ("CHANGE_PROJECT".equals(action))
		{
			this.projectCode = null;
			this.entityName = null;
			this.keyText = "";
			this.lineage = null;
			this.notice = null;
			this.clearPendingSuspectDecision();
			return;
		}
		if ("NEXT_CONTRIBUTORS".equals(action))
		{
			final int next = this.lineage == null ? -1 : this.lineage.path("contributors").path("nextOffset").asInt(-1);
			if (next >= 0 && this.projectCode != null && this.entityName != null)
			{
				this.contributorOffset = next;
				this.lookup(pContext.getSession());
			}
			return;
		}
		if ("LOOKUP".equals(action))
		{
			this.contributorOffset = 0;
			this.lineage = null;
			this.clearPendingSuspectDecision();
			if (this.projectCode == null)
			{
				this.warn(Messages.get(this.getClass(), this.locale, "lineage.notice.projectRequired"));
				return;
			}
			if (this.entityName == null)
			{
				this.warn(Messages.get(this.getClass(), this.locale, "lineage.notice.entityRequired"));
				return;
			}
			if (this.keyText.trim().isEmpty())
			{
				this.warn(Messages.get(this.getClass(), this.locale, "lineage.notice.keyRequired"));
				return;
			}
			this.lookup(pContext.getSession());
			return;
		}
		// "decide on a suspect" (2026-09-22 lot) - see decideSuspect's own Javadoc for the two-step
		// confirmation this dispatch implements: ACCEPT_SUSPECT/REJECT_SUSPECT only ever set the PENDING
		// state (never call the add-on); CONFIRM_SUSPECT_DECISION is the one branch that actually writes.
		final String suspectKey = AbstractRecordLineageUserService.blankToNull(AbstractRecordLineageUserService.param(pContext, "mdmfSuspectKey"));
		if ("ACCEPT_SUSPECT".equals(action) || "REJECT_SUSPECT".equals(action))
		{
			if (suspectKey == null || this.lineage == null)
			{
				return; // no suspect key posted, or the screen has nothing looked up any more - nothing to decide
			}
			this.pendingSuspectAction = "ACCEPT_SUSPECT".equals(action) ? "ACCEPT" : "REJECT";
			this.pendingSuspectKey = suspectKey;
			return;
		}
		if ("CANCEL_SUSPECT_DECISION".equals(action))
		{
			this.clearPendingSuspectDecision();
			return;
		}
		if ("CONFIRM_SUSPECT_DECISION".equals(action))
		{
			this.confirmSuspectDecision(pContext.getSession());
			return;
		}
	}

	void clearPendingSuspectDecision()
	{
		this.pendingSuspectAction = null;
		this.pendingSuspectKey = null;
	}

	/**
	 * The one branch that actually calls {@code RecordLineageReader.decideSuspect} - only reachable after a
	 * matching {@code ACCEPT_SUSPECT}/{@code REJECT_SUSPECT} click already set {@link #pendingSuspectAction}/
	 * {@link #pendingSuspectKey} (the confirmation step). The screen is re-looked-up FRESH afterward
	 * (success or failure - {@link #lookup}), so the new group/merge state (or the fact nothing changed)
	 * shows immediately, per the task brief ("the screen re-renders the lineage fresh after the action").
	 */
	void confirmSuspectDecision(final Session pSession)
	{
		final String action = this.pendingSuspectAction;
		final String suspectKey = this.pendingSuspectKey;
		this.clearPendingSuspectDecision();
		if (action == null || suspectKey == null || this.projectCode == null || this.entityName == null || this.keyText.trim().isEmpty())
		{
			return; // the confirmation itself carries no data to act on any more - a plain no-op, never a stack trace
		}
		try
		{
			final RecordLineageReader.DecisionResult result = RecordLineageReader.decideSuspect(pSession, this.factory, this.projectCode, this.entityName,
				this.keyText, suspectKey, "ACCEPT".equals(action));
			final String key = "lineage.suspect.decision." + result.outcome;
			String message = Messages.get(this.getClass(), this.locale, key);
			if (result.detail != null)
			{
				message = message + " " + Messages.get(this.getClass(), this.locale, "lineage.suspect.decision.detail", result.detail);
			}
			if (result.success)
			{
				this.notice = message;
				this.noticeKind = "ok";
			}
			else
			{
				this.warn(message);
			}
		}
		catch (final OperationException ex)
		{
			this.warn(Messages.get(this.getClass(), this.locale, "lineage.notice.lookupFailed", EngineNotice.inline(ex, this.locale)));
		}
		catch (final RuntimeException ex)
		{
			this.warn(Messages.get(this.getClass(), this.locale, "lineage.notice.unexpected"));
		}
		// re-render the lineage fresh either way (task brief) - a decision notice from the WRITE above is
		// deliberately overwritten only on a fresh failure of THIS read, never silently dropped otherwise:
		// lookup() sets its own notice/noticeKind on failure, which would hide the decision's own message -
		// preserved explicitly when the refresh itself succeeds.
		final String decisionNotice = this.notice;
		final String decisionNoticeKind = this.noticeKind;
		this.lookup(pSession);
		if (this.lineage != null)
		{
			this.notice = decisionNotice;
			this.noticeKind = decisionNoticeKind;
		}
	}

	/**
	 * SELECT_PROJECT posts back {@code mdmfProjectCode} freshly overwritten, right before the click, with the
	 * {@code <select>}'s own LIVE current value - the hidden field's own value attribute (as rendered) still
	 * mirrors whatever was selected before, so {@link #pageJs} must read the live control and write it into
	 * that same hidden field before the submit, never leave the stale mirror in place. Exactly the same
	 * "read the live control, overwrite the hidden mirror" rule {@code ProposalReviewUserService.pageJs}
	 * already follows for its own project dropdown (fix/lineage-load-button, 2026-09-22, ported here from
	 * {@code RecordLineageUserService} when it moved into this shared base: this method used to read a
	 * separate {@code mdmfProjectSelect} request parameter that no rendered form field ever carried - {@code
	 * RecordLineagePage#renderHiddenFields} has no such name, and the {@code <select id="mdmfProjectSelect">}
	 * itself has no {@code name} attribute so it is never posted - which silently discarded every project
	 * pick and made the Load button look dead).
	 */
	void selectProject(final String pCode)
	{
		// second permissions pass (2026-10-14): a project the user cannot see is answered like an unknown code
		if (!pCode.isEmpty() && this.asUser(() -> ProposalReviews.findProjectByCode(this.factory, pCode)) == null)
		{
			this.projectCode = null;
			this.entityName = null;
			this.keyText = "";
			this.lineage = null;
			this.warn(Messages.get(this.getClass(), this.locale, "screen.project.unknown"));
			return;
		}
		this.projectCode = pCode.isEmpty() ? null : pCode;
		this.entityName = null;
		this.keyText = "";
		this.lineage = null;
		this.notice = null;
	}

	void lookup(final Session pSession)
	{
		try
		{
			this.lineage = RecordLineageReader.read(pSession, this.factory, this.projectCode, this.entityName, this.keyText, this.contributorOffset,
				RecordLineageReader.maxContributors());
			this.notice = null;
		}
		catch (final OperationException ex)
		{
			this.lineage = null;
			this.warn(Messages.get(this.getClass(), this.locale, "lineage.notice.lookupFailed", EngineNotice.inline(ex, this.locale)));
		}
		catch (final RuntimeException ex)
		{
			// a genuinely unexpected failure - never a stack trace on the pane, and never past this method
			this.lineage = null;
			this.warn(Messages.get(this.getClass(), this.locale, "lineage.notice.unexpected"));
		}
	}

	void warn(final String pMessage)
	{
		this.noticeKind = "warn";
		this.notice = pMessage;
	}

	// --------------------------------------------------------------------------------------------------- the page

	void write(final UserServicePaneWriter pWriter)
	{
		// fix/review-assets (2026-09-21): Assets.version() changes with every build.sh run - see its own Javadoc
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/factory.css")).add("?v=").add(Assets.version())
			.add("\"/>");
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/review.css")).add("?v=").add(Assets.version())
			.add("\"/>");
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/lineage.css")).add("?v=")
			.add(Assets.version()).add("\"/>");

		final List<RecordLineagePage.ProjectOption> projectOptions = new ArrayList<RecordLineagePage.ProjectOption>();
		for (final Adaptation project : ProposalReviews.projects(this.factory))
		{
			projectOptions.add(new RecordLineagePage.ProjectOption(project.getOccurrencePrimaryKey().format(), AbstractRecordLineageUserService.labelOf(project)));
		}
		final List<RecordLineagePage.EntityOption> entityOptions = this.entityOptions();
		final RecordLineagePage.Input input = new RecordLineagePage.Input(this.locale, projectOptions, this.projectCode,
			this.projectCode == null ? null : this.selectedProjectLabel(), entityOptions, this.entityName, this.keyText, this.notice, this.noticeKind, this.lineage,
			this.pendingSuspectAction, this.pendingSuspectKey, this.isRecordBound());
		pWriter.add(RecordLineagePage.render(input));
		pWriter.addJS_cr(AbstractRecordLineageUserService.pageJs());
		// the graphic screen's own SVG/table drawing (RecordLineagePage.render only wrote the inert container
		// plus the <noscript> fallback) - CLAUDE.md's own "JavaScript goes through addJS" rule, never a
		// hand-written <script> tag; see RecordLineagePage.graphicScript's own Javadoc for the XSS-safety
		// argument. Null (no call at all) while nothing has been looked up yet - nothing to draw.
		final String graphicScript = RecordLineagePage.graphicScript(this.lineage, this.locale);
		if (graphicScript != null)
		{
			pWriter.addJS_cr(graphicScript);
		}
		// fix, 2026-09-22 (the product owner's report: he clicked a suspect lane, the "Compare with suspects" panel
		// opened, but he never saw the confirm prompt) - null on every render except the ONE right after an
		// ACCEPT_SUSPECT/REJECT_SUSPECT click (see RecordLineagePage#pendingSuspectScrollScript's own
		// Javadoc for why that alone makes this "run once, only when the panel was just opened").
		final String scrollScript = RecordLineagePage.pendingSuspectScrollScript(this.pendingSuspectAction, this.pendingSuspectKey);
		if (scrollScript != null)
		{
			pWriter.addJS_cr(scrollScript);
		}
	}

	private String selectedProjectLabel()
	{
		final Adaptation project = ProposalReviews.findProjectByCode(this.factory, this.projectCode);
		return project == null ? this.projectCode : AbstractRecordLineageUserService.labelOf(project);
	}

	static String labelOf(final Adaptation pProject)
	{
		// second permissions pass (2026-10-14): the label through the current read scope (hidden = the key instead)
		final String label = com.on.ebx.factory.ebx.UserView.string(pProject, FactoryPaths.Project.LABEL);
		return label == null || label.trim().isEmpty() ? pProject.getOccurrencePrimaryKey().format() : label;
	}

	// ------------------------------------------------------------------------------------- entities of the selected project

	/**
	 * Every PROMOTED master entity of {@link #projectCode} - the same computation {@code
	 * RecordLineageReader.read} itself runs (staging entities of the project, fed to {@code
	 * MasterModel.buildFromStaging}, kept if in {@code builtEntities}), read through the shared {@code
	 * ProjectMasterEntities.readEntities} (coordinator review, 2026-09-22, "Record lineage" record-level
	 * action lot - see that class's own Javadoc for why a third private copy would have been one too many).
	 */
	private List<RecordLineagePage.EntityOption> entityOptions()
	{
		final List<RecordLineagePage.EntityOption> out = new ArrayList<RecordLineagePage.EntityOption>();
		if (this.projectCode == null)
		{
			return out;
		}
		final Adaptation project = ProposalReviews.findProjectByCode(this.factory, this.projectCode);
		if (project == null)
		{
			return out;
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final List<StagingModel.EntityInfo> entities = ProjectMasterEntities.readEntities(this.factory, projectId).entities;
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(entities);
		for (final StagingModel.EntityInfo entity : entities)
		{
			if (masterModel.builtEntities.contains(entity.name))
			{
				out.add(new RecordLineagePage.EntityOption(entity.name, entity.label == null || entity.label.isEmpty() ? entity.name : entity.label));
			}
		}
		return out;
	}

	// --------------------------------------------------------------------------------------------------------- misc

	static String param(final UserServiceRequest pContext, final String pName)
	{
		final String value = pContext.getParameter(pName);
		return value == null ? "" : value.trim();
	}

	static String blankToNull(final String pValue)
	{
		return pValue == null || pValue.isEmpty() ? null : pValue;
	}

	/** {@code 'value'}, with an embedded quote doubled, for an EBX XPath predicate - same convention as {@code ebx.CatalogReader.quote}/{@code ebx.ui.FactoryHtml.quote}. */
	static String quote(final String pValue)
	{
		return "'" + String.valueOf(pValue).replace("'", "''") + "'";
	}

	/**
	 * Every interactive control on the page is {@code type="button"} with {@code data-mdmf-action}; this
	 * delegate reads it, fills the hidden fields {@link RecordLineagePage} rendered, and clicks the one real
	 * submit button - same technique as every other screen of this module ({@code
	 * ProposalReviewUserService.pageJs}, CLAUDE.md &sect;9).
	 */
	static String pageJs()
	{
		return "(function(){var root=document.querySelector('.mdmf-lineage');if(!root)return;"
			+ "function set(id,v){var e=document.getElementById(id);if(e)e.value=v;}"
			+ "root.querySelectorAll('[data-mdmf-action]').forEach(function(b){b.addEventListener('click',function(e){e.preventDefault();"
			+ "var action=b.getAttribute('data-mdmf-action');set('mdmfAction',action);"
			+ "if(action==='SELECT_PROJECT'){var sel=document.getElementById('mdmfProjectSelect');set('mdmfProjectCode',sel?sel.value:'');}"
			+ "if(action==='LOOKUP'){var ent=document.getElementById('mdmfEntitySelect');set('mdmfEntity',ent?ent.value:'');"
			+ "var key=document.getElementById('mdmfKeyInput');set('mdmfKey',key?key.value:'');}"
			// "decide on a suspect" lot (2026-09-22): ACCEPT_SUSPECT/REJECT_SUSPECT/CONFIRM_SUSPECT_DECISION/
			// CANCEL_SUSPECT_DECISION buttons all carry data-mdmf-suspect-key (RecordLineagePage#
			// renderSuspectDecision) - a generic rule (any button carrying the attribute writes it), not one
			// branch per action, so a future decision button never needs a matching change here.
			+ "if(b.hasAttribute('data-mdmf-suspect-key')){set('mdmfSuspectKey',b.getAttribute('data-mdmf-suspect-key'));}"
			+ "var s=document.getElementById('mdmfSubmit');if(s)s.click();});});"
			+ "var hidden=document.getElementById('mdmfSubmit');if(hidden)hidden.style.display='none';})();";
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<S> pContext, final UserServiceEventOutcome pOutcome)
	{
		return pOutcome;
	}

	@Override
	public void validate(final UserServiceValidateContext<S> pContext)
	{
		// nothing: the form is plain HTML read back from the request
	}
}
