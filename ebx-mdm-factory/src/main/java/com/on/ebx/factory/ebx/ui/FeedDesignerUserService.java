package com.on.ebx.factory.ebx.ui;

import com.on.ebx.factory.ebx.UserView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.Assets;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.MappingMatrixLink;
import com.on.ebx.factory.designer.DesignerModel;
import com.on.ebx.factory.ebx.GuardReader;
import com.on.ebx.factory.ebx.GuardWrites;
import com.on.ebx.factory.ebx.LandingRowCounts;
import com.on.ebx.factory.ebx.ProposalReviews;
import com.on.ebx.factory.ebx.WizardData;
import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.staging.AttentionRules;
import com.on.ebx.factory.transfer.PipelineCompat;
import com.on.ebx.factory.transfer.TransformationCatalogue;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;
import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
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
 * Graphical feed designer (docs/FEED-DESIGNER.md): landing columns of a {@code TransferFeed}'s own
 * landing table on the left, the target {@code StagingEntity}'s attributes on the right, one link per
 * {@code AttributeMapping} rule - the FORK of ebx-connectors' mapping designer described in
 * {@code www/common/jscripts/factory/feed-designer.js}'s own header comment.
 * <p>
 * {@code OnTableView} on {@code Project} (see {@link FeedDesignerServiceDeclaration}), with no fixed
 * record selected: the feed to open travels as the {@code feed} launch parameter (a {@code
 * TransferFeed} row id, always a ROOT feed - {@link com.on.ebx.factory.core.FeedDesignerLink} is the
 * only place that builds this service's URL, and it only ever does so for a root feed), resolved ONCE
 * per page load exactly like {@link ProjectWizardUserService#urlProjectChecked} resolves its own
 * {@code project} parameter (CLAUDE.md &sect;9: {@code setupObjectContext} runs again on every
 * postback, where the query string may or may not still be present).
 * <p>
 * The page shows every feed of the tree rooted at that id (the root plus every descendant, walked
 * through {@code TransferFeed.parentFeed}) AND the trees of the other root feeds of the same business
 * object, one per application ({@link #pageFeeds}), one feed at a time, switched client-side by the page's
 * two-row selector (applications, then the feeds of the chosen one) - {@link #model} therefore serialises
 * every one of them in one JSON object, so switching feeds or applications needs no round trip.
 * <p>
 * <b>Save</b> ({@link #onSave}) diffs the posted rules of EVERY feed of the tree against the stored
 * {@code AttributeMapping} rows in one pass, keyed by TARGET ATTRIBUTE NAME - the same "one rule per
 * output field" identity the original Connectors designer already uses (dropping on an already-mapped
 * target re-points that same rule, {@code feed-designer.js#addRule}), so a posted rule never needs to
 * carry a stable server-assigned id to be matched back to its stored row. A rule whose target is the
 * feed's own link attribute (the child's reference to its parent - never a rule target, {@code
 * feed-designer.js}'s own {@code mdfdLocked} gate on the client) is silently dropped here too, in case
 * a hand-crafted request ever tried to smuggle one through.
 * <p>
 * <b>Staging-proposal "designer" lot (docs/STAGING-PROPOSAL.md, docs/FEED-DESIGNER.md):</b> the right
 * (target) tree now carries a rule/check badge per field ({@link #guardsByTarget}, {@code ebx.GuardReader}),
 * a "worth a second look" flag on a flagged rule ({@link #attentionByTarget}, {@code staging.AttentionRules}
 * - computed over THIS FEED's own mappings only, per the task brief), and a green-dashed "suggested fix"
 * link per {@code AttributeMapping.alternativeTo} row ({@link #suggestionsOf}). Clicking a target field now
 * opens a field inspector (name/type/status/source links on the left, "Rules and checks on &lt;field&gt;" on
 * the right - {@code feed-designer.js}); a guard's accept/reject/severity and a suggestion's apply/keep are
 * IMMEDIATE decisions, posted through their own hidden submit ({@link #onDecide}, {@link #DECIDE_SUBMIT_ID})
 * exactly like the Mapping matrix's own one-shot decisions (docs/MAPPING-MATRIX.md sec 4) - never folded
 * into the link-editing {@link #onSave}/dirty cycle. {@link #onDecide} refuses outright (never silently
 * drops the pending edits) when the page's own dirty flag travelled with the postback ({@link
 * #DIRTY_PARAM}) - see its own Javadoc.
 */
public final class FeedDesignerUserService implements UserService<TableViewEntitySelection>
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String RULES_PARAM = "mdfdRulesInput";
	private static final String PIPELINES_PARAM = "mdfdSourceIdPipelinesInput";
	private static final String DIRTY_PARAM = "mdfdDirtyInput";
	private static final String DECIDE_SUBMIT_ID = "mdfdDecideSubmit";
	private static final String DECIDE_NATURE_PARAM = "mdfdDecideNature";
	private static final String DECIDE_ID_PARAM = "mdfdDecideId";
	private static final String DECIDE_ACTION_PARAM = "mdfdDecideAction";
	private static final String DECIDE_SEVERITY_PARAM = "mdfdDecideSeverity";
	// "add a target field" lot (docs/FEED-DESIGNER.md): a THIRD hidden one-shot submit, same family as
	// DECIDE_SUBMIT_ID above (CLAUDE.md sec 9 "Posting a user service's own inputs") - never folded into
	// onSave/onDecide, so a stray postback of one never touches the other two's own params.
	private static final String ADD_FIELD_SUBMIT_ID = "mdfdAddFieldSubmit";
	private static final String ADD_FIELD_ENTITY_PARAM = "mdfdNewFieldEntity";
	private static final String ADD_FIELD_NAME_PARAM = "mdfdNewFieldName";
	private static final String ADD_FIELD_LABEL_PARAM = "mdfdNewFieldLabel";
	private static final String ADD_FIELD_TYPE_PARAM = "mdfdNewFieldType";
	private static final String ADD_FIELD_MANDATORY_PARAM = "mdfdNewFieldMandatory";
	/** {@code StagingAttribute.name}'s own XSD pattern (tools/build_xsd.py) - checked again here, never trusted from the client alone. */
	private static final Pattern FIELD_NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
	private static final int FIELD_NAME_MAX_LENGTH = 80;
	/** {@code StagingAttribute.dataType}'s own enum (tools/build_xsd.py's {@code DATA_TYPE}) - a hand-kept mirror, same posture as {@code TransformationCatalogue} (class Javadoc reference), since this class has no data-model MCP access to read the enum live. */
	private static final Set<String> FIELD_DATA_TYPES =
		new HashSet<String>(Arrays.asList("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP"));

	/** {@code designer.*} keys the page script reads through {@code M.labels} - see {@code feed-designer.js}'s own {@code t()} helper. */
	private static final String[] LABEL_KEYS = { "designer.noFields", "designer.mappedCount", "designer.linkAttribute", "designer.setByEngine",
		"designer.rule.constant", "designer.rule.transformation", "designer.inspector.empty", "designer.step.remove", "designer.step.add", "designer.value",
		"designer.pipeline.raw", "designer.pipeline", "designer.constantHint", "designer.rule.delete", "designer.rule.source", "designer.rule.target",
		"designer.noRules", "designer.child", "designer.childOf", "designer.bar.sources", "designer.bar.feeds", "designer.parentLinkField", "designer.sourceIdPipeline",
		"designer.sourceIdPipeline.placeholder", "designer.feedRejected", "designer.readOnly", "designer.addConstant", "designer.unsaved",
		"designer.hideUnmapped", "designer.rules", "designer.viewDiagram", "designer.viewTable", "designer.fullscreen", "designer.source", "designer.target",
		"designer.search", "designer.pickTarget", "designer.inspector", "designer.proposed", "designer.unresolvedWarning", "designer.unresolvedField",
		"designer.mode.matrix", "designer.mode.designer", "designer.field.inspector", "designer.field.newTag", "designer.field.name", "designer.field.type",
		"designer.field.status", "designer.field.sourceLinks", "designer.field.noSource", "designer.guard.rule", "designer.guard.check",
		"designer.guard.none", "designer.guard.whenFails", "designer.guard.flag", "designer.guard.stop", "designer.guard.notSet", "designer.guard.accept",
		"designer.guard.reject", "designer.guard.save", "designer.guard.severity.WARN", "designer.guard.severity.REJECT",
		"designer.guard.severity.MODEL_CONSTRAINT", "designer.guard.decided", "designer.guard.dirty", "designer.guard.readOnly", "designer.guard.unknown",
		"designer.attention.constant", "designer.attention.empty", "designer.attention.duplicate", "designer.attention.needsEye", "designer.suggestion.tag",
		"designer.suggestion.replaces", "designer.suggestion.apply", "designer.suggestion.keep", "designer.suggestion.rationale",
		// "add a target field" lot (docs/FEED-DESIGNER.md) - client-side (JS t()) labels only; the server-rendered
		// result/error messages ("designer.targetField.created"/"invalid", "designer.saveFailed", "designer.
		// unknownFeed") are read straight by Messages.get and never travel through M.labels, same as every other
		// this.fail()/this.message text this class already sets.
		"designer.addTargetField", "designer.targetField.name", "designer.targetField.labelField", "designer.targetField.type", "designer.targetField.mandatory",
		"designer.targetField.create", "designer.targetField.cancel", "designer.targetField.namePattern", "designer.targetField.duplicate",
		// audit F-45: the keyboard path (select a source, then a target, with Enter) and the names of the fields
		"designer.kbd.hint", "designer.kbd.sourceRow", "designer.kbd.targetRow", "designer.kbd.sourcePicked", "designer.kbd.ruleCreated", "designer.kbd.stepArgs" };

	Adaptation factory;
	Session session;
	Locale locale = Locale.ENGLISH;
	/** The root feed id, resolved once from the {@code feed} launch parameter - see the class Javadoc. */
	String feedId;
	boolean feedIdChecked;

	String message;
	boolean messageIsError;

	// ------------------------------------------------------------------ permissions

	/**
	 * Second permissions pass (2026-10-14): the project the root feed belongs to (feed - entity - project), each link read with the user's view; null
	 * when any of them is unknown or hidden from the user (answered alike). The {@code feed} launch parameter is the page's word: checked here.
	 */
	Adaptation projectOfFeed()
	{
		final UserView view = UserView.of(this.session);
		if (this.factory == null || this.feedId == null || this.feedId.isEmpty() || ProjectAccess.refusal(this.session, this.factory, this.locale) != null)
		{
			return null;
		}
		final Adaptation feed = view.lookup(this.factory.getTable(FactoryPaths.TRANSFER_FEED), this.feedId);
		final Adaptation entity = feed == null ? null : view.lookup(this.factory.getTable(FactoryPaths.STAGING_ENTITY), view.text(feed, FactoryPaths.TransferFeed.ENTITY));
		return entity == null ? null : ProjectAccess.openProject(this.session, this.factory, view.text(entity, FactoryPaths.StagingEntity.PROJECT));
	}

	/** Null when the user may change this feed tree (its project visible AND writable); otherwise the refusal to show. */
	String writeRefusal(final Session pSession)
	{
		final Adaptation project = this.projectOfFeed();
		if (project == null)
		{
			return Messages.get(FeedDesignerUserService.class, this.locale, "designer.unknownFeed");
		}
		if (!UserView.of(pSession).recordWritable(project))
		{
			return Messages.get(FeedDesignerUserService.class, this.locale, "screen.readOnly");
		}
		return null;
	}

	// ------------------------------------------------------------------ lifecycle

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		this.factory = pContext.getEntitySelection().getDataset();
		this.session = pContext.getSession();
		if (!this.feedIdChecked)
		{
			this.feedIdChecked = true;
			final String param = AbstractRecordLineageUserService.param(pContext, "feed");
			if (!param.isEmpty())
			{
				this.feedId = param;
			}
		}
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<TableViewEntitySelection> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		// second permissions pass (2026-10-14): dataset, service permission and the feed's project, with the user's session, before anything is read
		String closed = ProjectAccess.refusal(this.session, this.factory, this.locale);
		if (closed == null && !ProjectAccess.serviceOnTable(this.session, FeedDesignerServiceDeclaration.SERVICE_KEY, pContext.getEntitySelection().getTable()))
		{
			closed = Messages.get(FeedDesignerUserService.class, this.locale, "screen.service.refused");
		}
		if (closed == null && this.projectOfFeed() == null)
		{
			closed = Messages.get(FeedDesignerUserService.class, this.locale, "designer.unknownFeed");
		}
		if (closed != null)
		{
			final String message = closed;
			pConfigurator.setTitle(Messages.get(FeedDesignerUserService.class, this.locale, "designer.title", "?"));
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
		final UserView view = UserView.of(this.session);
		final Adaptation rootFeed = view.lookup(this.factory.getTable(FactoryPaths.TRANSFER_FEED), this.feedId);
		final String entityLabel = rootFeed == null ? "?" : UserView.scoped(view, () -> this.entityNameOf(view.text(rootFeed, FactoryPaths.TransferFeed.ENTITY)));
		pConfigurator.setTitle(Messages.get(FeedDesignerUserService.class, this.locale, "designer.title", entityLabel));
		// audit F-41: an unplanned failure of one of the three submits or of the rendering becomes a localized notice with a logged reference
		final java.util.function.Consumer<String> failureNotice = pNotice -> {
			this.message = pNotice;
			this.messageIsError = true;
		};
		final UIButtonSpecSubmit save = pConfigurator.newSubmitButton(Messages.get(FeedDesignerUserService.class, this.locale, "designer.save"), ScreenFailure.guard("feed designer", "save", this.locale, new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				if (!com.on.ebx.factory.ebx.FactoryAccess.has(pEventContext.getSession(), com.on.ebx.factory.core.FactoryRights.service(FeedDesignerServiceDeclaration.SERVICE_NAME)))
				{
					// audit F-02: the DESIGN right, re-checked server-side
					FeedDesignerUserService.this.message = Messages.get(FeedDesignerUserService.class, FeedDesignerUserService.this.locale, "review.notice.adminOnly");
					FeedDesignerUserService.this.messageIsError = true;
					return null;
				}
				// second permissions pass (2026-10-14): the feed's project must be visible AND writable for this user; the writes then run with the
				// user's own session (EBX checks each one)
				final String refusal = FeedDesignerUserService.this.writeRefusal(pEventContext.getSession());
				if (refusal != null)
				{
					FeedDesignerUserService.this.message = refusal;
					FeedDesignerUserService.this.messageIsError = true;
					return null;
				}
				FeedDesignerUserService.this.onSave(pEventContext);
				return null;
			}
		}, failureNotice));
		save.setId("mdfdSave");
		// staging-proposal "designer" lot: a SECOND hidden submit button for guard/suggestion decisions
		// (accept/reject/severity), posted independently of the link-editing Save above - CLAUDE.md sec 9
		// "Posting a user service's own inputs", same one-shot mechanism docs/MAPPING-MATRIX.md sec 4 already
		// uses (mdmmSubmit there). Hidden by feed-designer.css (never shown as a visible button); clicked only
		// by feed-designer.js itself, after filling the mdfdDecide* hidden fields written().
		final UIButtonSpecSubmit decide = pConfigurator.newSubmitButton(Messages.get(FeedDesignerUserService.class, this.locale, "designer.guard.save"),
			ScreenFailure.guard("feed designer", "decision", this.locale, new UserServiceEvent()
			{
				@Override
				public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
				{
					if (!com.on.ebx.factory.ebx.FactoryAccess.has(pEventContext.getSession(), com.on.ebx.factory.core.FactoryRights.service(FeedDesignerServiceDeclaration.SERVICE_NAME)))
				{
					// audit F-02: the DESIGN right, re-checked server-side
					FeedDesignerUserService.this.message = Messages.get(FeedDesignerUserService.class, FeedDesignerUserService.this.locale, "review.notice.adminOnly");
					FeedDesignerUserService.this.messageIsError = true;
					return null;
				}
				// second permissions pass (2026-10-14): the feed's project must be visible AND writable for this user; the writes then run with the
				// user's own session (EBX checks each one)
				final String refusal = FeedDesignerUserService.this.writeRefusal(pEventContext.getSession());
				if (refusal != null)
				{
					FeedDesignerUserService.this.message = refusal;
					FeedDesignerUserService.this.messageIsError = true;
					return null;
				}
				FeedDesignerUserService.this.onDecide(pEventContext);
					return null;
				}
			}, failureNotice));
		decide.setId(FeedDesignerUserService.DECIDE_SUBMIT_ID);
		// "add a target field" lot: a THIRD hidden submit, same one-shot posture as `decide` above - clicked
		// only by feed-designer.js's own submitAddField(), after filling the mdfdNewField* hidden fields.
		final UIButtonSpecSubmit addField = pConfigurator.newSubmitButton(Messages.get(FeedDesignerUserService.class, this.locale, "designer.targetField.save"),
			ScreenFailure.guard("feed designer", "new target field", this.locale, new UserServiceEvent()
			{
				@Override
				public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
				{
					// audit F-02: the third submit was the only one without a server-side check (the permission rule only hides the screen)
					if (!com.on.ebx.factory.ebx.FactoryAccess.has(pEventContext.getSession(), com.on.ebx.factory.core.FactoryRights.service(FeedDesignerServiceDeclaration.SERVICE_NAME)))
					{
						FeedDesignerUserService.this.message = Messages.get(FeedDesignerUserService.class, FeedDesignerUserService.this.locale, "review.notice.adminOnly");
						FeedDesignerUserService.this.messageIsError = true;
						return null;
					}
					// second permissions pass (2026-10-14): the feed's project must be visible AND writable for this user; the writes then run with the
					// user's own session (EBX checks each one)
					final String refusal = FeedDesignerUserService.this.writeRefusal(pEventContext.getSession());
					if (refusal != null)
					{
						FeedDesignerUserService.this.message = refusal;
						FeedDesignerUserService.this.messageIsError = true;
						return null;
					}
					FeedDesignerUserService.this.onAddTargetField(pEventContext);
					return null;
				}
			}, failureNotice));
		addField.setId(FeedDesignerUserService.ADD_FIELD_SUBMIT_ID);
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		close.setId("mdfdClose");
		pConfigurator.setLeftButtons(save, decide, addField);
		pConfigurator.setRightButtons(close);
		pConfigurator.setContent(ScreenFailure.guard("feed designer", this.locale, new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				FeedDesignerUserService.this.write(pWriter);
			}
		}));
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<TableViewEntitySelection> pContext,
		final UserServiceEventOutcome pEventOutcome)
	{
		return pEventOutcome;
	}

	@Override
	public void validate(final UserServiceValidateContext<TableViewEntitySelection> pContext)
	{
		// nothing: the page is plain HTML/JS, read back from the posted hidden fields on save
	}

	// ------------------------------------------------------------------ save

	/**
	 * Diffs every feed of the tree against its posted rules ({@link #RULES_PARAM}, {@code
	 * {feedId: [{source,target,transformation}, ...]}}) and {@link #PIPELINES_PARAM} ({@code
	 * {feedId: "pipeline text"}}), one {@link Procedure} per feed (so one feed's failure never rolls
	 * back another's) - see {@link #saveFeedRules} for the per-feed diff and {@link WizardData#setFeedSourceIdPipeline}
	 * for the {@code sourceIdPipeline} write (reused verbatim, same {@code PROPOSED}-or-{@code USER}
	 * guard as every other in-place edit this module offers).
	 */
	void onSave(final UserServiceEventContext pContext)
	{
		this.message = null;
		this.messageIsError = false;
		final List<Adaptation> tree = this.pageFeeds();
		if (tree.isEmpty())
		{
			this.fail(Messages.get(FeedDesignerUserService.class, this.locale, "designer.unknownFeed"));
			return;
		}
		final Map<String, List<PostedRule>> rulesByFeed = FeedDesignerUserService.parseRulesByFeed(pContext.getParameter(FeedDesignerUserService.RULES_PARAM));
		final Map<String, String> pipelinesByFeed = FeedDesignerUserService.parsePipelinesByFeed(pContext.getParameter(FeedDesignerUserService.PIPELINES_PARAM));
		int changed = 0;
		final StringBuilder errors = new StringBuilder();
		for (final Adaptation feedRow : tree)
		{
			final String feedRowId = feedRow.getOccurrencePrimaryKey().format();
			final List<PostedRule> posted = rulesByFeed.get(feedRowId);
			try
			{
				changed += this.saveFeedRules(feedRow, posted == null ? new ArrayList<PostedRule>() : posted);
			}
			catch (final RuntimeException ex)
			{
				// audit F2-108: the person's language when the engine refused with a code, never a raw "null" or a bare class name
				FeedDesignerUserService.appendSpaced(errors, EngineNotice.inline(ex, this.locale));
			}
			final String postedPipeline = pipelinesByFeed.get(feedRowId);
			if (postedPipeline != null)
			{
				final String stored = FeedDesignerUserService.textOf(feedRow, FactoryPaths.TransferFeed.SOURCE_ID_PIPELINE);
				if (!postedPipeline.trim().equals(stored.trim()))
				{
					try
					{
						WizardData.setFeedSourceIdPipeline(this.session, this.factory, feedRowId, postedPipeline);
						changed++;
					}
					catch (final OperationException ex)
					{
						FeedDesignerUserService.appendSpaced(errors, EngineNotice.inline(ex, this.locale));
					}
				}
			}
		}
		if (errors.length() > 0)
		{
			this.fail(Messages.get(FeedDesignerUserService.class, this.locale, "designer.saveFailed", errors.toString()));
			return;
		}
		this.message = Messages.get(FeedDesignerUserService.class, this.locale, "designer.saved", Integer.valueOf(changed));
	}

	/**
	 * One feed's diff: {@code postedByTarget} (the page's current rules, keyed by target attribute name -
	 * see the class Javadoc) against {@code storedByTarget} (this feed's own {@code AttributeMapping} rows,
	 * same key). A posted rule naming the feed's own link attribute (child only) is dropped without a
	 * write - defence in depth, the client already refuses to create one.
	 *
	 * @return how many rows were created, changed, rejected or deleted.
	 */
	private int saveFeedRules(final Adaptation pFeedRow, final List<PostedRule> pPosted)
	{
		final String feedId = pFeedRow.getOccurrencePrimaryKey().format();
		final String entityId = FeedDesignerUserService.textOf(pFeedRow, FactoryPaths.TransferFeed.ENTITY);
		final Map<String, Adaptation> attributesByName = this.attributesByName(entityId);
		final String linkAttributeName = this.linkAttributeName(pFeedRow, entityId);
		final Map<String, Adaptation> columnsByFieldName = this.columnsByFieldName(FeedDesignerUserService.textOf(pFeedRow, FactoryPaths.TransferFeed.LANDING_TABLE));
		final Map<String, Adaptation> storedByTarget = new LinkedHashMap<String, Adaptation>();
		for (final Adaptation row : this.mappingsOfFeed(feedId))
		{
			// staging-proposal "designer" lot: a suggested-fix row (AttributeMapping.alternativeTo set) is NOT
			// "the" rule of its target - the row it replaces already holds that slot. It is decided only through
			// onDecide (Apply the fix / Keep the current link), never through this diff - see suggestionsOf/
			// feedModel's own "suggestions" array. Excluding it here matters: two rows can legitimately share one
			// target (the accepted mapping and its still-PROPOSED alternative), and letting the LAST one seen win
			// in this map would silently clobber the accepted row's own diff entry.
			if (FeedDesignerUserService.hasAlternativeTo(row))
			{
				continue;
			}
			final String targetName = this.attributeNameOfId(entityId, FeedDesignerUserService.textOf(row, FactoryPaths.AttributeMapping.ATTRIBUTE));
			if (targetName != null)
			{
				storedByTarget.put(targetName, row);
			}
		}
		final Map<String, PostedRule> postedByTarget = new LinkedHashMap<String, PostedRule>();
		for (final PostedRule rule : pPosted)
		{
			if (rule.target == null || rule.target.isEmpty() || rule.target.equals(linkAttributeName))
			{
				continue;
			}
			postedByTarget.put(rule.target, rule);
		}
		final Adaptation factory = this.factory;
		final int[] changed = { 0 };
		final ProcedureResult result = ProgrammaticService.createForSession(this.session, factory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pProcedure) throws Exception
			{
				final AdaptationTable mappings = factory.getTable(FactoryPaths.ATTRIBUTE_MAPPING);
				for (final Map.Entry<String, PostedRule> entry : postedByTarget.entrySet())
				{
					final PostedRule posted = entry.getValue();
					final Adaptation targetAttribute = attributesByName.get(entry.getKey());
					if (targetAttribute == null)
					{
						continue; // an unknown/no-longer-accepted attribute - never crash the whole save on one bad row
					}
					final Adaptation column = posted.source.isEmpty() ? null : columnsByFieldName.get(posted.source);
					final String columnId;
					if (column != null)
					{
						columnId = column.getOccurrencePrimaryKey().format();
					}
					else if (!columnsByFieldName.isEmpty())
					{
						// a constant rule: AttributeMapping.landingColumn stays mandatory (docs/TARGET-MAPPING.md
						// sec 2.2), but a constant() pipeline step ignores its input entirely (README, "Mapping
						// engine jar" table) - which landing column it names has no effect on the value written,
						// so any column of this feed's own table satisfies the constraint. Documented as a known
						// limitation in docs/FEED-DESIGNER.md rather than left unexplained.
						columnId = columnsByFieldName.values().iterator().next().getOccurrencePrimaryKey().format();
					}
					else
					{
						continue; // no landing column exists at all on this table yet - cannot satisfy the FK, skip
					}
					final Adaptation stored = storedByTarget.get(entry.getKey());
					if (stored == null)
					{
						final ValueContextForUpdate values = pProcedure.getContextForNewOccurrence(mappings);
						values.setValue(targetAttribute.getOccurrencePrimaryKey().format(), FactoryPaths.AttributeMapping.ATTRIBUTE);
						values.setValue(columnId, FactoryPaths.AttributeMapping.LANDING_COLUMN);
						values.setValue(feedId, FactoryPaths.AttributeMapping.FEED);
						values.setValue(posted.transformation.isEmpty() ? null : posted.transformation, FactoryPaths.AttributeMapping.PIPELINE);
						values.setValue("USER", FactoryPaths.AttributeMapping.ORIGIN);
						values.setValue("ACCEPTED", FactoryPaths.AttributeMapping.STATUS);
						pProcedure.doCreateOccurrence(values, mappings);
						changed[0]++;
						continue;
					}
					final String storedStatus = FeedDesignerUserService.textOf(stored, FactoryPaths.AttributeMapping.STATUS);
					final String storedOrigin = FeedDesignerUserService.textOf(stored, FactoryPaths.AttributeMapping.ORIGIN);
					final String storedPipeline = FeedDesignerUserService.textOf(stored, FactoryPaths.AttributeMapping.PIPELINE);
					final String storedColumnId = FeedDesignerUserService.textOf(stored, FactoryPaths.AttributeMapping.LANDING_COLUMN);
					final boolean pipelineChanged = !storedPipeline.trim().equals(posted.transformation.trim());
					final boolean sourceChanged = !storedColumnId.equals(columnId);
					if (!pipelineChanged && !sourceChanged)
					{
						continue;
					}
					if (!"PROPOSED".equals(storedStatus) && !"USER".equals(storedOrigin))
					{
						continue; // editable-in-place gate (docs/TARGET-MAPPING.md sec 4.3) - same as WizardData.setMappingPipeline
					}
					final ValueContextForUpdate values = pProcedure.getContext(stored.getAdaptationName());
					if (pipelineChanged)
					{
						values.setValue(posted.transformation.isEmpty() ? null : posted.transformation, FactoryPaths.AttributeMapping.PIPELINE);
					}
					if (sourceChanged)
					{
						values.setValue(columnId, FactoryPaths.AttributeMapping.LANDING_COLUMN);
					}
					values.setValue("USER", FactoryPaths.AttributeMapping.ORIGIN);
					pProcedure.doModifyContent(stored, values);
					changed[0]++;
				}
				for (final Map.Entry<String, Adaptation> entry : storedByTarget.entrySet())
				{
					if (postedByTarget.containsKey(entry.getKey()))
					{
						continue;
					}
					final Adaptation row = entry.getValue();
					if ("USER".equals(FeedDesignerUserService.textOf(row, FactoryPaths.AttributeMapping.ORIGIN)))
					{
						pProcedure.doDelete(row.getAdaptationName(), false);
					}
					else
					{
						final ValueContextForUpdate values = pProcedure.getContext(row.getAdaptationName());
						values.setValue("REJECTED", FactoryPaths.AttributeMapping.STATUS);
						pProcedure.doModifyContent(row, values);
					}
					changed[0]++;
				}
			}
		});
		if (result.hasFailed())
		{
			throw new RuntimeException(result.getExceptionFullMessage(this.locale), result.getException());
		}
		return changed[0];
	}

	private void fail(final String pMessage)
	{
		this.message = pMessage;
		this.messageIsError = true;
	}

	// ------------------------------------------------------------------ guard / suggestion decisions

	/**
	 * The field inspector's own one-shot decisions (class Javadoc): accept/reject a guard ({@link
	 * ReviewLine#QUALITY}/{@link ReviewLine#SCRIPT_CONSTRAINT}/{@link ReviewLine#EXTERNAL_SERVICE}/{@link
	 * ReviewLine#SCRIPT_DERIVATION}) or a suggested-fix mapping ({@link ReviewLine#MAPPING} - accepting one
	 * with {@code alternativeTo} set cascades to reject the mapping it replaces, {@code
	 * ebx.ProposalReviews#decide}'s own extension, "lot run" per the task brief - relied on here, never
	 * re-implemented), or set a guard's severity/transfer policy ({@code ebx.GuardWrites#setSeverity}).
	 * <p>
	 * Never folded into {@link #onSave}: this button posts {@link #DECIDE_NATURE_PARAM}/{@link
	 * #DECIDE_ID_PARAM}/{@link #DECIDE_ACTION_PARAM}/{@link #DECIDE_SEVERITY_PARAM} instead of {@link
	 * #RULES_PARAM}/{@link #PIPELINES_PARAM} - both travel with every postback of this page's one form (the
	 * hidden inputs are always present), but only the fields the CLICKED submit button's own handler reads
	 * are acted on.
	 * <p>
	 * <b>Never loses a pending link edit silently</b> (task brief): {@code feed-designer.js} keeps the page's
	 * own dirty flag in {@link #DIRTY_PARAM} (synced alongside {@link #RULES_PARAM}/{@link #PIPELINES_PARAM}
	 * on every change) and refuses client-side to even submit a guard decision while dirty (with a message
	 * telling the reviewer to save first) - checked AGAIN here, fail-closed, in case a hand-crafted request
	 * bypassed that client-side guard: a dirty postback is refused outright, nothing decided, nothing
	 * overwritten. This is the one deliberate choice among the two the task brief allows ("warn and keep them
	 * ... or refuse the decision") - refusing was chosen because this class has no way to re-apply the
	 * poster's in-page edits onto the freshly re-read model {@link #write} builds after this postback: silently
	 * "keeping" them without persisting them would show them once more only to lose them on the NEXT postback,
	 * which is worse than an explicit refusal.
	 */
	void onDecide(final UserServiceEventContext pContext)
	{
		this.message = null;
		this.messageIsError = false;
		if ("1".equals(pContext.getParameter(FeedDesignerUserService.DIRTY_PARAM)))
		{
			this.fail(Messages.get(FeedDesignerUserService.class, this.locale, "designer.guard.dirty"));
			return;
		}
		final List<Adaptation> tree = this.pageFeeds();
		if (!this.canEdit(tree))
		{
			this.fail(Messages.get(FeedDesignerUserService.class, this.locale, "designer.guard.readOnly"));
			return;
		}
		final String nature = pContext.getParameter(FeedDesignerUserService.DECIDE_NATURE_PARAM);
		final String id = pContext.getParameter(FeedDesignerUserService.DECIDE_ID_PARAM);
		final String action = pContext.getParameter(FeedDesignerUserService.DECIDE_ACTION_PARAM);
		if (nature == null || nature.isEmpty() || id == null || id.isEmpty() || action == null || action.isEmpty())
		{
			return; // a stray submit with nothing filled in - nothing to do
		}
		if ("SET_SEVERITY".equals(action))
		{
			final String severity = pContext.getParameter(FeedDesignerUserService.DECIDE_SEVERITY_PARAM);
			try
			{
				GuardWrites.setSeverity(this.session, this.factory, nature, id, severity == null ? "" : severity);
				this.message = Messages.get(FeedDesignerUserService.class, this.locale, "designer.guard.decided");
			}
			catch (final OperationException ex)
			{
				this.fail(EngineNotice.inline(ex, this.locale));
			}
			return;
		}
		if ("ACCEPT".equals(action) || "REJECT".equals(action))
		{
			final ProposalReviews.DecisionResult result =
				ProposalReviews.decide(this.session, this.factory, nature, id, "ACCEPT".equals(action) ? ReviewLine.ACCEPTED : ReviewLine.REJECTED);
			if (!result.ok)
			{
				this.fail(result.reason);
				return;
			}
			this.message = Messages.get(FeedDesignerUserService.class, this.locale, "designer.guard.decided");
			return;
		}
		this.fail(Messages.get(FeedDesignerUserService.class, this.locale, "designer.guard.unknown"));
	}

	/**
	 * "Add a target field" lot (docs/FEED-DESIGNER.md): creates a NEW {@code StagingAttribute} on the
	 * current feed's entity, {@code origin = USER}, {@code status = ACCEPTED} - a person created it, so it
	 * needs no further review, same convention {@link #saveFeedRules} already uses for a hand-drawn rule.
	 * Posted through {@link #ADD_FIELD_SUBMIT_ID} (class Javadoc, "Posting a user service's own inputs"),
	 * never folded into {@link #onSave}/{@link #onDecide}.
	 * <p>
	 * Refused, in this order, exactly like {@link #onDecide}: (1) the page is dirty (an unsaved link edit
	 * would otherwise be silently discarded by the postback, same reasoning as {@link #onDecide}'s own
	 * Javadoc); (2) the session cannot write this feed's tree ({@link #canEdit}); (3) the name is empty,
	 * too long, or does not match {@link #FIELD_NAME_PATTERN} (the exact pattern {@code StagingAttribute.name}
	 * enforces - checked again here, the client's own check is a courtesy, never the authority); (4) the
	 * name collides, case-insensitively, with ANY existing attribute of the entity regardless of ITS OWN
	 * status (a name must be unique whether the row it collides with is accepted, proposed or even
	 * rejected - {@code StagingModel} has no per-status uniqueness rule of its own to lean on); (5) the
	 * posted data type is not one of {@link #FIELD_DATA_TYPES}.
	 * <p>
	 * Whether the new field reaches the GENERATED staging table right away depends entirely on {@code
	 * staging.StagingModel#build}/{@code staging.StagingModelDiff}, neither of which is touched by this
	 * class: a new {@code ACCEPTED} attribute of an already-{@code APPLIED} (generated) entity is picked up
	 * by the NEXT "Generate the staging" run as an ordinary ADDITIVE change (a new field, {@code
	 * StagingModelDiff#diffExistingTable}) and applied/republished automatically - see this method's own
	 * success message, which says so, and docs/FEED-DESIGNER.md for the file:line evidence.
	 */
	void onAddTargetField(final UserServiceEventContext pContext)
	{
		this.message = null;
		this.messageIsError = false;
		if ("1".equals(pContext.getParameter(FeedDesignerUserService.DIRTY_PARAM)))
		{
			this.fail(Messages.get(FeedDesignerUserService.class, this.locale, "designer.guard.dirty"));
			return;
		}
		final List<Adaptation> tree = this.pageFeeds();
		if (!this.canEdit(tree))
		{
			this.fail(Messages.get(FeedDesignerUserService.class, this.locale, "designer.guard.readOnly"));
			return;
		}
		final String entityId = pContext.getParameter(FeedDesignerUserService.ADD_FIELD_ENTITY_PARAM);
		final String name = FeedDesignerUserService.trim(pContext.getParameter(FeedDesignerUserService.ADD_FIELD_NAME_PARAM));
		final String label = FeedDesignerUserService.trim(pContext.getParameter(FeedDesignerUserService.ADD_FIELD_LABEL_PARAM));
		final String dataType = FeedDesignerUserService.trim(pContext.getParameter(FeedDesignerUserService.ADD_FIELD_TYPE_PARAM));
		final boolean mandatory = "1".equals(pContext.getParameter(FeedDesignerUserService.ADD_FIELD_MANDATORY_PARAM));
		if (entityId == null || entityId.isEmpty())
		{
			return; // a stray submit with nothing filled in - nothing to do, same posture as onDecide
		}
		if (!this.entityBelongsToTree(tree, entityId))
		{
			// defence in depth, same reasoning as saveFeedRules' own link-attribute drop: the client always posts
			// the current feed's own entityId, so this only ever fires on a hand-crafted request.
			this.fail(Messages.get(FeedDesignerUserService.class, this.locale, "designer.unknownFeed"));
			return;
		}
		if (!FeedDesignerUserService.isValidFieldName(name))
		{
			this.fail(Messages.get(FeedDesignerUserService.class, this.locale, "designer.targetField.namePattern", name));
			return;
		}
		if (this.attributeNamesOfEntity(entityId).contains(name.toLowerCase(Locale.ROOT)))
		{
			this.fail(Messages.get(FeedDesignerUserService.class, this.locale, "designer.targetField.duplicate", name));
			return;
		}
		final String finalDataType = FeedDesignerUserService.isKnownDataType(dataType) ? dataType : null;
		if (finalDataType == null)
		{
			this.fail(Messages.get(FeedDesignerUserService.class, this.locale, "designer.targetField.invalid"));
			return;
		}
		final Adaptation factory = this.factory;
		final ProcedureResult result = ProgrammaticService.createForSession(this.session, factory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pProcedure) throws Exception
			{
				final AdaptationTable attributes = factory.getTable(FactoryPaths.STAGING_ATTRIBUTE);
				final ValueContextForUpdate values = pProcedure.getContextForNewOccurrence(attributes);
				values.setValue(entityId, FactoryPaths.StagingAttribute.ENTITY);
				values.setValue(name, FactoryPaths.StagingAttribute.NAME);
				values.setValue(label.isEmpty() ? null : label, FactoryPaths.StagingAttribute.LABEL);
				values.setValue(finalDataType, FactoryPaths.StagingAttribute.DATA_TYPE);
				values.setValue(Boolean.valueOf(mandatory), FactoryPaths.StagingAttribute.MANDATORY);
				values.setValue("USER", FactoryPaths.StagingAttribute.ORIGIN);
				// a person created it: no further review needed, same convention as a hand-drawn rule (saveFeedRules)
				values.setValue("ACCEPTED", FactoryPaths.StagingAttribute.STATUS);
				pProcedure.doCreateOccurrence(values, attributes);
			}
		});
		if (result.hasFailed())
		{
			this.fail(Messages.get(FeedDesignerUserService.class, this.locale, "designer.saveFailed", result.getExceptionFullMessage(this.locale)));
			return;
		}
		// staging.StagingModel/StagingModelDiff pick this up as an ordinary ADDITIVE "new field" change on the next
		// "Generate the staging" run of an already-generated entity - see this method's own Javadoc.
		this.message = Messages.get(FeedDesignerUserService.class, this.locale, "designer.targetField.created", name);
	}

	private boolean entityBelongsToTree(final List<Adaptation> pTree, final String pEntityId)
	{
		for (final Adaptation feedRow : pTree)
		{
			if (pEntityId.equals(FeedDesignerUserService.textOf(feedRow, FactoryPaths.TransferFeed.ENTITY)))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Every {@code StagingAttribute} name of the entity, lower-cased, REGARDLESS OF STATUS - unlike {@link
	 * #attributesByName} (eligible-target statuses only), a new name must never collide with ANY existing
	 * row of the entity, accepted, proposed or even rejected: {@code StagingModel} has no per-status
	 * uniqueness rule to fall back on, so this errs on the side of refusing a collision the schema itself
	 * would not otherwise catch (there is no unique constraint on {@code StagingAttribute.name} - {@code id}
	 * is the table's own primary key).
	 */
	private Set<String> attributeNamesOfEntity(final String pEntityId)
	{
		final Set<String> out = new HashSet<String>();
		final RequestResult rows = UserView.select(this.factory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + FeedDesignerUserService.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(FeedDesignerUserService.textOf(row, FactoryPaths.StagingAttribute.NAME).toLowerCase(Locale.ROOT));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static String trim(final String pValue)
	{
		return pValue == null ? "" : pValue.trim();
	}

	// ------------------------------------------------------------------ reading the feed tree

	/**
	 * Every feed this page shows (fix/designer-all-sources, 2026-10-19): the tree of the requested root feed first,
	 * then the tree of every OTHER root feed writing the same business object (one per application), ordered by id.
	 * Before this, the page showed one application only and the Matrix's "Designer" switch always opened the
	 * entity's first one, so the other sources of the object could not be reached from the screen at all.
	 */
	private List<Adaptation> pageFeeds()
	{
		final Adaptation requested = FeedDesignerUserService.lookup(this.factory, FactoryPaths.TRANSFER_FEED, this.feedId);
		if (requested == null)
		{
			return new ArrayList<Adaptation>();
		}
		// audit F2-111: ONE read of the feeds of the business object per render (the requested root carries the entity), then the trees are built in memory;
		// the table used to be scanned whole once per node of the tree
		final Map<String, Adaptation> byId = new LinkedHashMap<String, Adaptation>();
		final Map<String, String> parentOf = new LinkedHashMap<String, String>();
		final RequestResult rows = UserView.select(this.factory.getTable(FactoryPaths.TRANSFER_FEED),
			"./entity=" + FeedDesignerUserService.quote(FeedDesignerUserService.textOf(requested, FactoryPaths.TransferFeed.ENTITY)));
		FeedDesignerUserService.feedTableReads++;
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String id = row.getOccurrencePrimaryKey().format();
				byId.put(id, row);
				parentOf.put(id, FeedDesignerUserService.textOf(row, FactoryPaths.TransferFeed.PARENT_FEED));
			}
		}
		finally
		{
			rows.close();
		}
		final String requestedId = requested.getOccurrencePrimaryKey().format();
		if (!byId.containsKey(requestedId))
		{
			byId.put(requestedId, requested);
			parentOf.put(requestedId, FeedDesignerUserService.textOf(requested, FactoryPaths.TransferFeed.PARENT_FEED));
		}
		final List<Adaptation> out = new ArrayList<Adaptation>();
		for (final String id : FeedDesignerUserService.pageOrder(requestedId, parentOf))
		{
			out.add(byId.get(id));
		}
		return out;
	}

	/** How many times the page has scanned {@code TransferFeed} (audit F2-111: once per render). Package-visible for the tests. */
	static int feedTableReads;

	/**
	 * Audit F2-111, pure: the ids of the page in order - the tree of the requested root first (the root, then its descendants depth first, siblings in
	 * the order the table gave them), then the tree of every OTHER root (a feed with no parent) ordered by numeric id. {@code pParentOf} maps every
	 * feed id of the business object to its parent's id ("" for a root), in table order. A cycle never loops: an id is listed once.
	 */
	static List<String> pageOrder(final String pRequestedId, final Map<String, String> pParentOf)
	{
		final List<String> out = new ArrayList<String>();
		if (!pParentOf.containsKey(pRequestedId))
		{
			return out;
		}
		final Map<String, List<String>> children = new LinkedHashMap<String, List<String>>();
		final List<String> otherRoots = new ArrayList<String>();
		for (final Map.Entry<String, String> entry : pParentOf.entrySet())
		{
			final String parent = entry.getValue() == null ? "" : entry.getValue();
			if (parent.isEmpty())
			{
				if (!pRequestedId.equals(entry.getKey()))
				{
					otherRoots.add(entry.getKey());
				}
				continue;
			}
			List<String> list = children.get(parent);
			if (list == null)
			{
				list = new ArrayList<String>();
				children.put(parent, list);
			}
			list.add(entry.getKey());
		}
		java.util.Collections.sort(otherRoots, new java.util.Comparator<String>()
		{
			@Override
			public int compare(final String pA, final String pB)
			{
				return Long.compare(FeedDesignerUserService.idOf(pA), FeedDesignerUserService.idOf(pB));
			}
		});
		final Set<String> seen = new HashSet<String>();
		FeedDesignerUserService.walk(pRequestedId, children, seen, out);
		for (final String root : otherRoots)
		{
			FeedDesignerUserService.walk(root, children, seen, out);
		}
		return out;
	}

	private static void walk(final String pId, final Map<String, List<String>> pChildren, final Set<String> pSeen, final List<String> pOut)
	{
		if (!pSeen.add(pId))
		{
			return;
		}
		pOut.add(pId);
		final List<String> kids = pChildren.get(pId);
		if (kids != null)
		{
			for (final String kid : kids)
			{
				FeedDesignerUserService.walk(kid, pChildren, pSeen, pOut);
			}
		}
	}

	private static long idOf(final String pId)
	{
		try
		{
			return Long.parseLong(pId);
		}
		catch (final NumberFormatException ex)
		{
			return Long.MAX_VALUE;
		}
	}

	private List<Adaptation> mappingsOfFeed(final String pFeedId)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final RequestResult rows = UserView.select(this.factory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), "./feed=" + FeedDesignerUserService.quote(pFeedId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Every attribute of the entity that is still a legitimate rule target - every status but {@code
	 * REJECTED} (and, for parity with {@link #isEligibleTargetStatus}, {@code SUPERSEDED}), {@code
	 * PROPOSED} included. Used both to resolve a posted rule's target on save ({@link #saveFeedRules})
	 * and to build the Target pane's own field list ({@link #feedModel}) - see {@link
	 * #isEligibleTargetStatus} for why {@code PROPOSED} must not be dropped here (bug fixed 2026-09-24,
	 * project Personnes: every attribute of an entity still {@code PROPOSED} left the whole Target pane
	 * empty and every rule's link dangling at the pane corner).
	 */
	private Map<String, Adaptation> attributesByName(final String pEntityId)
	{
		final Map<String, Adaptation> out = new LinkedHashMap<String, Adaptation>();
		final RequestResult rows = UserView.select(this.factory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + FeedDesignerUserService.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String status = FeedDesignerUserService.textOf(row, FactoryPaths.StagingAttribute.STATUS);
				if (FeedDesignerUserService.isEligibleTargetStatus(status))
				{
					out.put(FeedDesignerUserService.textOf(row, FactoryPaths.StagingAttribute.NAME), row);
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Whether a {@code StagingAttribute} in this status still belongs on the Target side of the designer:
	 * every status but {@code REJECTED} - nothing should ever be mapped onto an attribute a person has
	 * ruled out - and, for parity with the {@code SUPERSEDED} value other proposal-style tables of this
	 * module carry ({@code review.ReviewLine#SUPERSEDED}, {@code FactoryPaths.MatchPlan#STATUS_SUPERSEDED}),
	 * {@code SUPERSEDED} too, even though {@code StagingAttribute} never actually carries it today
	 * (tools/build_xsd.py's {@code proposal('attribute', ...)} call keeps the plain four-value {@code
	 * PROPOSED}/{@code ACCEPTED}/{@code REJECTED}/{@code APPLIED} enum - checking for it here costs
	 * nothing and keeps this method correct if that ever changes). In particular {@code PROPOSED} IS
	 * eligible: the designer is precisely where a person reviews a proposal before deciding it, so
	 * hiding a not-yet-decided target here defeats the whole point of the screen.
	 */
	/** Package-visible only for {@code FeedDesignerUserServiceTest} - this class has no other pure logic worth a JUnit-free unit test on its own (everything else needs a live {@code Adaptation}/{@code Session}). */
	static boolean isEligibleTargetStatus(final String pStatus)
	{
		return !"REJECTED".equals(pStatus) && !"SUPERSEDED".equals(pStatus);
	}

	/**
	 * "Add a target field" lot: whether {@code pName} could be a legal {@code StagingAttribute.name} - the
	 * exact same non-empty/length/pattern test the field's own XSD facet enforces (tools/build_xsd.py:
	 * {@code maxLen=80, pattern='[A-Za-z][A-Za-z0-9_]*'}), checked again here so a malformed name is refused
	 * with a clear message BEFORE the write, rather than left to a generic blocking-constraint failure at
	 * {@code doCreateOccurrence} time (CLAUDE.md sec 2: a blocking constraint failure is a bare, unhelpful
	 * {@code 500}-style message with no field-specific text). Package-visible only for {@link
	 * FeedDesignerUserServiceTest} - this class' one other pure decision, alongside {@link
	 * #isEligibleTargetStatus}.
	 */
	static boolean isValidFieldName(final String pName)
	{
		return pName != null && !pName.isEmpty() && pName.length() <= FeedDesignerUserService.FIELD_NAME_MAX_LENGTH
			&& FeedDesignerUserService.FIELD_NAME_PATTERN.matcher(pName).matches();
	}

	/** "Add a target field" lot: whether {@code pDataType} is one of {@code StagingAttribute.dataType}'s own enum values (tools/build_xsd.py's {@code DATA_TYPE}) - the client only ever offers these six through a &lt;select&gt;, so a mismatch here means a hand-crafted request. Package-visible only for {@link FeedDesignerUserServiceTest}. */
	static boolean isKnownDataType(final String pDataType)
	{
		return FeedDesignerUserService.FIELD_DATA_TYPES.contains(pDataType);
	}

	private String attributeNameOfId(final String pEntityId, final String pAttributeId)
	{
		final Adaptation row = FeedDesignerUserService.lookup(this.factory, FactoryPaths.STAGING_ATTRIBUTE, pAttributeId);
		return row == null ? null : FeedDesignerUserService.textOf(row, FactoryPaths.StagingAttribute.NAME);
	}

	/** Every {@code LandingColumn} of the table, ordered by {@code position}, keyed by {@code fieldName} (a rule's own {@code sourcePath}, docs/TARGET-MAPPING.md sec 3.1). */
	private Map<String, Adaptation> columnsByFieldName(final String pLandingTableId)
	{
		final Map<String, Adaptation> out = new LinkedHashMap<String, Adaptation>();
		for (final Adaptation row : this.columnsOf(pLandingTableId))
		{
			out.put(FeedDesignerUserService.textOf(row, FactoryPaths.LandingColumn.FIELD_NAME), row);
		}
		return out;
	}

	private List<Adaptation> columnsOf(final String pLandingTableId)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final RequestResult rows = UserView.select(this.factory.getTable(FactoryPaths.LANDING_COLUMN), "./landingTable=" + FeedDesignerUserService.quote(pLandingTableId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		final java.util.Comparator<Adaptation> byPosition = new java.util.Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pA, final Adaptation pB)
			{
				final Object a = UserView.get(pA, FactoryPaths.LandingColumn.POSITION);
				final Object b = UserView.get(pB, FactoryPaths.LandingColumn.POSITION);
				final int ai = a instanceof Number ? ((Number) a).intValue() : 0;
				final int bi = b instanceof Number ? ((Number) b).intValue() : 0;
				return ai - bi;
			}
		};
		java.util.Collections.sort(out, byPosition);
		return out;
	}

	/**
	 * The child entity's own attribute pointing back at {@code pFeedRow}'s parent entity ({@code
	 * StagingAttribute.referenceEntity} - see docs/TARGET-MAPPING.md sec 4.2's {@code propose_target_entity}:
	 * exactly one such attribute per child entity), or {@code null} for a root feed or when none is found
	 * (a malformed/pre-lot entity - never a crash, just no "locked" row). Unlike {@link #attributesByName},
	 * this scan never filters by {@code status} at all - it must find the link attribute even while it is
	 * still {@code PROPOSED} (it is created together with the rest of the entity, docs/TARGET-MAPPING.md
	 * sec 4.2's {@code propose_target_entity}), so there is nothing to align with {@link
	 * #isEligibleTargetStatus} here.
	 */
	private String linkAttributeName(final Adaptation pFeedRow, final String pEntityId)
	{
		final String parentFeedId = FeedDesignerUserService.textOf(pFeedRow, FactoryPaths.TransferFeed.PARENT_FEED);
		if (parentFeedId.isEmpty())
		{
			return null;
		}
		final Adaptation parentFeed = FeedDesignerUserService.lookup(this.factory, FactoryPaths.TRANSFER_FEED, parentFeedId);
		final String parentEntityId = parentFeed == null ? null : FeedDesignerUserService.textOf(parentFeed, FactoryPaths.TransferFeed.ENTITY);
		if (parentEntityId == null || parentEntityId.isEmpty())
		{
			return null;
		}
		final RequestResult rows = UserView.select(this.factory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + FeedDesignerUserService.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (parentEntityId.equals(FeedDesignerUserService.textOf(row, FactoryPaths.StagingAttribute.REFERENCE_ENTITY)))
				{
					return FeedDesignerUserService.textOf(row, FactoryPaths.StagingAttribute.NAME);
				}
			}
		}
		finally
		{
			rows.close();
		}
		return null;
	}

	private String entityNameOf(final String pEntityId)
	{
		final Adaptation row = FeedDesignerUserService.lookup(this.factory, FactoryPaths.STAGING_ENTITY, pEntityId);
		return row == null ? "?" : FeedDesignerUserService.textOf(row, FactoryPaths.StagingEntity.NAME);
	}

	// ------------------------------------------------------------------ display

	private void write(final UserServicePaneWriter pWriter)
	{
		final String stamp = Assets.version();
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/feed-designer.css")).add("?v=")
				.add(stamp).add("\"/>");
		// second permissions pass (2026-10-14): every row and field of the page through the user's view (a pure read: no write, no call)
		final com.fasterxml.jackson.databind.node.ObjectNode model = UserView.scoped(UserView.of(this.session), () -> this.model());
		// fix/wizard-live-wave1 (2026-09-24, live bug item 2): a visible way back to the wizard, top-left, same
		// reasoning and same class name as MatrixHtml's own "Back to the wizard" link (Close alone only works
		// when this screen was reached from the wizard in THIS SAME session - see FactoryHtml#originReturnScript).
		final String wizardUrl = model.path("wizardUrl").asText("");
		if (!wizardUrl.isEmpty())
		{
			pWriter.add("<div class=\"mdfdBackToWizard\"><a class=\"mdmf-matrix-designer-link\" target=\"_top\" href=\"")
				.add(FeedDesignerUserService.esc(wizardUrl)).add("\">&larr; ")
				.add(FeedDesignerUserService.esc(Messages.get(FeedDesignerUserService.class, this.locale, "mappingMatrix.backToWizard"))).add("</a></div>");
		}
		if (this.message != null)
		{
			pWriter.add("<div class=\"mdfdMsg").add(this.messageIsError ? " mdfdErr" : "").add("\">").add(FeedDesignerUserService.esc(this.message)).add("</div>");
		}
		pWriter.add("<div id=\"mdfd\"></div>");
		pWriter.add("<input type=\"hidden\" name=\"" + FeedDesignerUserService.RULES_PARAM + "\" id=\"mdfdRulesInput\" value=\"\"/>");
		pWriter.add("<input type=\"hidden\" name=\"" + FeedDesignerUserService.PIPELINES_PARAM + "\" id=\"mdfdSourceIdPipelinesInput\" value=\"\"/>");
		// staging-proposal "designer" lot: the dirty flag and the one-shot guard/suggestion decision fields -
		// see onDecide's own Javadoc and feed-designer.js's sync()/postDecision().
		pWriter.add("<input type=\"hidden\" name=\"" + FeedDesignerUserService.DIRTY_PARAM + "\" id=\"" + FeedDesignerUserService.DIRTY_PARAM + "\" value=\"\"/>");
		pWriter.add("<input type=\"hidden\" name=\"" + FeedDesignerUserService.DECIDE_NATURE_PARAM + "\" id=\"" + FeedDesignerUserService.DECIDE_NATURE_PARAM + "\" value=\"\"/>");
		pWriter.add("<input type=\"hidden\" name=\"" + FeedDesignerUserService.DECIDE_ID_PARAM + "\" id=\"" + FeedDesignerUserService.DECIDE_ID_PARAM + "\" value=\"\"/>");
		pWriter.add("<input type=\"hidden\" name=\"" + FeedDesignerUserService.DECIDE_ACTION_PARAM + "\" id=\"" + FeedDesignerUserService.DECIDE_ACTION_PARAM + "\" value=\"\"/>");
		pWriter.add("<input type=\"hidden\" name=\"" + FeedDesignerUserService.DECIDE_SEVERITY_PARAM + "\" id=\"" + FeedDesignerUserService.DECIDE_SEVERITY_PARAM + "\" value=\"\"/>");
		// "add a target field" lot - same hidden-field posture as the two blocks above, read by onAddTargetField.
		pWriter.add("<input type=\"hidden\" name=\"" + FeedDesignerUserService.ADD_FIELD_ENTITY_PARAM + "\" id=\"" + FeedDesignerUserService.ADD_FIELD_ENTITY_PARAM + "\" value=\"\"/>");
		pWriter.add("<input type=\"hidden\" name=\"" + FeedDesignerUserService.ADD_FIELD_NAME_PARAM + "\" id=\"" + FeedDesignerUserService.ADD_FIELD_NAME_PARAM + "\" value=\"\"/>");
		pWriter.add("<input type=\"hidden\" name=\"" + FeedDesignerUserService.ADD_FIELD_LABEL_PARAM + "\" id=\"" + FeedDesignerUserService.ADD_FIELD_LABEL_PARAM + "\" value=\"\"/>");
		pWriter.add("<input type=\"hidden\" name=\"" + FeedDesignerUserService.ADD_FIELD_TYPE_PARAM + "\" id=\"" + FeedDesignerUserService.ADD_FIELD_TYPE_PARAM + "\" value=\"\"/>");
		pWriter.add("<input type=\"hidden\" name=\"" + FeedDesignerUserService.ADD_FIELD_MANDATORY_PARAM + "\" id=\"" + FeedDesignerUserService.ADD_FIELD_MANDATORY_PARAM + "\" value=\"\"/>");
		// fix/wizard-live-wave1 (2026-09-24): reuses the SAME model computed above for the "Back to the wizard"
		// link, rather than calling this.model() (a fresh EBX read of the whole tree/guards) a second time.
		// security review 2026-09-24, finding I2: model.toString() is Jackson output, unescaped for a <script>
		// context - a </script> in an LLM rationale/source column label would otherwise break out of the
		// enclosing script element. FactoryHtml.jsonForScript is the same escaping RecordLineagePage#graphicScript
		// already used, now shared.
		// fix/matrix-perspective-loss (2026-09-27): the perspective rewrite first - EBX runs every addJS of the page
		// as one script, so a failure further down must never leave the Matrix switch outside the perspective
		// (MappingMatrixUserService#write, same fix).
		pWriter.addJS_cr(FactoryHtml.perspectiveLinkRewriteScript());
		pWriter.addJS_cr("var mdfdModel = " + FactoryHtml.jsonForScript(model.toString()) + ";");
		pWriter.addJS_cr(FactoryHtml.originReturnScript(FactoryHtml.userId(this.session)));
		// mode-switch (delta 5): the Matrix link is a full-page (target=_top) departure with no return token of
		// its own - same posture as MatrixHtml's own "Open this feed in the Designer" link, only the voluntary-
		// leave marking so EBX's leave-page guard never fires for it (docs/MAPPING-MATRIX.md, ebx.ui.
		// MappingMatrixUserService#write's own ".mdmf-matrix-designer-link" comment).
		pWriter.addJS_cr(FactoryHtml.voluntaryLeaveScript(".mdfd-designer-matrix-link"));
		// fix/wizard-live-wave1 (2026-09-24, live bug item 2): keeps every /ebx-ui/ link of this screen inside
		// whatever perspective it was opened from - the Matrix|Designer switch (built by feed-designer.js, well
		// after this page load) and the "Back to the wizard" link below - same click-time rewrite as
		// MappingMatrixUserService#write, see the script's own Javadoc for why a one-shot rewrite is not enough.
		pWriter.addJS_cr("(function(){ var s = document.createElement('script'); s.type = 'text/javascript'; s.src = '"
				+ pWriter.getURLForResource(ResourceType.JSCRIPT, "factory/feed-designer.js").replace("'", "%27") + "?v=" + stamp
				+ "'; (document.body || document.head).appendChild(s); })();");
	}

	/** Everything the page script needs, as one JSON object - see {@code feed-designer.js}'s own header comment for the shape. */
	private ObjectNode model()
	{
		final ObjectNode model = FeedDesignerUserService.MAPPER.createObjectNode();
		final List<Adaptation> tree = this.pageFeeds();
		model.put("activeFeedId", this.feedId == null ? "" : this.feedId);
		model.put("entityName", tree.isEmpty() ? "?" : this.entityNameOf(FeedDesignerUserService.textOf(tree.get(0), FactoryPaths.TransferFeed.ENTITY)));
		model.put("readOnly", !this.canEdit(tree));
		final ArrayNode labels = model.putArray("labels");
		for (final String key : FeedDesignerUserService.LABEL_KEYS)
		{
			final ObjectNode l = labels.addObject();
			l.put("key", key);
			l.put("text", Messages.get(FeedDesignerUserService.class, this.locale, key));
		}
		final boolean french = this.locale != null && "fr".equalsIgnoreCase(this.locale.getLanguage());
		final ArrayNode transformations = model.putArray("transformations");
		for (final TransformationCatalogue.Entry entry : TransformationCatalogue.entries())
		{
			final ObjectNode t = transformations.addObject();
			t.put("name", entry.name);
			t.put("syntax", entry.display());
			t.put("hint", french ? entry.hintFr : entry.hintEn);
		}
		// staging-proposal "designer" lot: guards and the Matrix mode-switch link are resolved ONCE for the
		// whole tree (every feed of one designer page shares the same project - class Javadoc), not once per
		// feed - GuardReader.read already reads every entity of the project in one call.
		final String projectCode = tree.isEmpty() ? null : this.projectCodeOfEntity(FeedDesignerUserService.textOf(tree.get(0), FactoryPaths.TransferFeed.ENTITY));
		final Map<String, List<Guard>> guardsByAttributeId = projectCode == null ? java.util.Collections.<String, List<Guard>> emptyMap()
			: GuardReader.read(this.factory, projectCode);
		final String dataspaceKey = this.factory.getHome().getKey().format();
		final String datasetName = this.factory.getAdaptationName().getStringName();
		// fix/wizard-live-wave1 (2026-09-24, live bug item 2): "Back to the wizard", step 4 (Staging) - read by
		// write() to render the top-left link, same project CODE (never an occurrence id) every matrixUrl below
		// already resolves.
		model.put("wizardUrl", projectCode == null ? ""
			: FactoryLinks.wizardUrl(dataspaceKey, datasetName, projectCode, com.on.ebx.factory.wizard.WizardRules.STEP_STAGING));
		final ArrayNode feeds = model.putArray("feeds");
		for (final Adaptation feedRow : tree)
		{
			feeds.add(this.feedModel(feedRow, guardsByAttributeId, dataspaceKey, datasetName, projectCode));
		}
		return model;
	}

	/**
	 * Mode-switch (task brief delta 5): the project CODE a {@code StagingEntity} belongs to, via {@code
	 * StagingEntity.project} - {@code Project.code} - the same value {@code core.MappingMatrixLink#url} and
	 * {@code ebx.GuardReader#read} both key by (never the EBX row id). {@code null} when either lookup fails
	 * (a dangling/malformed row - never a crash, just no guards/no Matrix link for that tree).
	 */
	private String projectCodeOfEntity(final String pEntityId)
	{
		final Adaptation entity = FeedDesignerUserService.lookup(this.factory, FactoryPaths.STAGING_ENTITY, pEntityId);
		if (entity == null)
		{
			return null;
		}
		final Adaptation project = FeedDesignerUserService.lookup(this.factory, FactoryPaths.PROJECT, FeedDesignerUserService.textOf(entity, FactoryPaths.StagingEntity.PROJECT));
		return project == null ? null : FeedDesignerUserService.textOf(project, FactoryPaths.Project.CODE);
	}

	private ObjectNode feedModel(final Adaptation pFeedRow, final Map<String, List<Guard>> pGuardsByAttributeId, final String pDataspaceKey,
		final String pDatasetName, final String pProjectCode)
	{
		final ObjectNode f = FeedDesignerUserService.MAPPER.createObjectNode();
		final String feedId = pFeedRow.getOccurrencePrimaryKey().format();
		final String entityId = FeedDesignerUserService.textOf(pFeedRow, FactoryPaths.TransferFeed.ENTITY);
		final String landingTableId = FeedDesignerUserService.textOf(pFeedRow, FactoryPaths.TransferFeed.LANDING_TABLE);
		final Adaptation landingTable = FeedDesignerUserService.lookup(this.factory, FactoryPaths.LANDING_TABLE, landingTableId);
		final Adaptation projectSource = landingTable == null ? null
				: FeedDesignerUserService.lookup(this.factory, FactoryPaths.PROJECT_SOURCE, FeedDesignerUserService.textOf(landingTable, FactoryPaths.LandingTable.PROJECT_SOURCE));
		final String parentFeedId = FeedDesignerUserService.textOf(pFeedRow, FactoryPaths.TransferFeed.PARENT_FEED);
		final Adaptation parentFeed = parentFeedId.isEmpty() ? null : FeedDesignerUserService.lookup(this.factory, FactoryPaths.TRANSFER_FEED, parentFeedId);
		final String status = FeedDesignerUserService.textOf(pFeedRow, FactoryPaths.TransferFeed.STATUS);

		f.put("id", feedId);
		// "add a target field" lot: the raw StagingEntity row id, never shown, only posted back by
		// submitAddField() (feed-designer.js) so onAddTargetField knows which entity to write to - entityName
		// below is a display label only, not something the server could resolve back to an id.
		f.put("entityId", entityId);
		f.put("entityName", this.entityNameOf(entityId));
		f.put("landingTableName", landingTable == null ? "?" : FeedDesignerUserService.textOf(landingTable, FactoryPaths.LandingTable.TABLE_NAME));
		f.put("sourceSystem", projectSource == null ? "?" : FeedDesignerUserService.textOf(projectSource, FactoryPaths.ProjectSource.SOURCE_SYSTEM));
		f.put("parentFeedId", parentFeedId);
		f.put("parentEntityName", parentFeed == null ? "" : this.entityNameOf(FeedDesignerUserService.textOf(parentFeed, FactoryPaths.TransferFeed.ENTITY)));
		f.put("parentLinkField", FeedDesignerUserService.textOf(pFeedRow, FactoryPaths.TransferFeed.PARENT_LINK_FIELD));
		f.put("variant", FeedDesignerUserService.textOf(pFeedRow, FactoryPaths.TransferFeed.VARIANT));
		f.put("sourceIdPipeline", FeedDesignerUserService.textOf(pFeedRow, FactoryPaths.TransferFeed.SOURCE_ID_PIPELINE));
		f.put("origin", FeedDesignerUserService.textOf(pFeedRow, FactoryPaths.TransferFeed.ORIGIN));
		f.put("status", status);
		f.put("editable", !"REJECTED".equals(status));
		// mode-switch (task brief delta 5): empty when the project code could not be resolved - feed-designer.js
		// then simply omits the Matrix link rather than rendering a broken one.
		f.put("matrixUrl", pProjectCode == null ? "" : MappingMatrixLink.url(pDataspaceKey, pDatasetName, pProjectCode, entityId));

		final String linkAttributeName = this.linkAttributeName(pFeedRow, entityId);

		final ArrayNode sourceFields = f.putArray("sourceFields");
		for (final Adaptation column : this.columnsOf(landingTableId))
		{
			final ObjectNode c = sourceFields.addObject();
			final String fieldName = FeedDesignerUserService.textOf(column, FactoryPaths.LandingColumn.FIELD_NAME);
			c.put("path", fieldName);
			final String sourceColumn = FeedDesignerUserService.textOf(column, FactoryPaths.LandingColumn.SOURCE_COLUMN);
			c.put("label", sourceColumn.isEmpty() ? fieldName : sourceColumn);
			c.put("type", FeedDesignerUserService.textOf(column, FactoryPaths.LandingColumn.INFERRED_TYPE));
			c.put("key", Boolean.TRUE.equals(UserView.get(column, FactoryPaths.LandingColumn.SOURCE_KEY)));
		}

		final ArrayNode targetFields = f.putArray("targetFields");
		final RequestResult attributeRows = UserView.select(this.factory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + FeedDesignerUserService.quote(entityId));
		try
		{
			for (Adaptation attribute = attributeRows.nextAdaptation(); attribute != null; attribute = attributeRows.nextAdaptation())
			{
				final String attributeStatus = FeedDesignerUserService.textOf(attribute, FactoryPaths.StagingAttribute.STATUS);
				if (!FeedDesignerUserService.isEligibleTargetStatus(attributeStatus))
				{
					continue; // REJECTED (or SUPERSEDED, never actually stored - see isEligibleTargetStatus): never a rule target
				}
				// PROPOSED included on purpose (isEligibleTargetStatus's own Javadoc) - a not-yet-generated field
				// still needs to be shown so a person can review the rule(s) already drawn onto it; the client
				// renders it with a small "proposed" chip (feed-designer.js#renderTree) rather than locking it.
				final ObjectNode a = targetFields.addObject();
				final String name = FeedDesignerUserService.textOf(attribute, FactoryPaths.StagingAttribute.NAME);
				a.put("path", name);
				final String label = FeedDesignerUserService.textOf(attribute, FactoryPaths.StagingAttribute.LABEL);
				a.put("label", label.isEmpty() ? name : label);
				a.put("type", FeedDesignerUserService.textOf(attribute, FactoryPaths.StagingAttribute.DATA_TYPE));
				a.put("mandatory", Boolean.TRUE.equals(UserView.get(attribute, FactoryPaths.StagingAttribute.MANDATORY)));
				a.put("reference", !FeedDesignerUserService.textOf(attribute, FactoryPaths.StagingAttribute.REFERENCE_ENTITY).isEmpty());
				a.put("technical", Boolean.TRUE.equals(UserView.get(attribute, FactoryPaths.StagingAttribute.PRIMARY_KEY)));
				a.put("linkAttribute", name.equals(linkAttributeName));
				a.put("status", attributeStatus);
				// staging-proposal "designer" lot: rule/check badges (delta 1) - entity-level guards (GuardReader.
				// ENTITY_LEVEL) are deliberately never attached to a field row; this designer has no entity-level
				// display, unlike the Mapping matrix's own column (docs/MAPPING-MATRIX.md).
				final ArrayNode guards = a.putArray("guards");
				final List<Guard> attributeGuards = pGuardsByAttributeId.get(attribute.getOccurrencePrimaryKey().format());
				if (attributeGuards != null)
				{
					for (final Guard guard : attributeGuards)
					{
						final ObjectNode g = guards.addObject();
						g.put("id", guard.id);
						g.put("nature", guard.nature);
						g.put("label", guard.label());
						g.put("status", guard.status);
						g.put("check", DesignerModel.isCheckNature(guard.nature));
						g.put("severity", guard.severity == null ? "" : guard.severity);
						g.put("transferPolicy", guard.transferPolicy == null ? "" : guard.transferPolicy);
						g.put("rationale", guard.rationale == null ? "" : guard.rationale);
						final ArrayNode severityOptions = g.putArray("severityOptions");
						for (final String option : DesignerModel.severityOptions(guard.nature))
						{
							severityOptions.add(option);
						}
					}
				}
			}
		}
		finally
		{
			attributeRows.close();
		}

		final Map<String, String> columnLabelById = new LinkedHashMap<String, String>();
		for (final Adaptation column : this.columnsOf(landingTableId))
		{
			columnLabelById.put(column.getOccurrencePrimaryKey().format(), FeedDesignerUserService.textOf(column, FactoryPaths.LandingColumn.FIELD_NAME));
		}
		// staging-proposal "designer" lot, delta 3: "worth a second look" flags, computed over THIS FEED's own
		// mappings only (task brief: "for the feed's mappings") - never the whole entity, unlike
		// staging.AttentionRules.DUPLICATE_FIELD's own entity-wide definition elsewhere. A cross-feed duplicate
		// (two DIFFERENT feeds each feeding the same attribute) is out of scope here on purpose.
		final Map<String, AttentionRules.Flag> attentionByTarget = this.attentionFlagsByTarget(pFeedRow, entityId, feedId);
		final ArrayNode rules = f.putArray("rules");
		final ArrayNode suggestions = f.putArray("suggestions");
		for (final Adaptation mapping : this.mappingsOfFeed(feedId))
		{
			final String targetName = this.attributeNameOfId(entityId, FeedDesignerUserService.textOf(mapping, FactoryPaths.AttributeMapping.ATTRIBUTE));
			if (targetName == null)
			{
				continue;
			}
			final String pipeline = this.pipelineOf(mapping, entityId, targetName);
			if (FeedDesignerUserService.hasAlternativeTo(mapping))
			{
				// delta 4: a suggested-fix row - drawn as its own green-dashed link, never merged into `rules`
				// (see saveFeedRules' own exclusion, same reason: two rows can legitimately target one attribute).
				final String replacesId = FeedDesignerUserService.textOf(mapping, FactoryPaths.AttributeMapping.ALTERNATIVE_TO);
				final Adaptation replaces = FeedDesignerUserService.lookup(this.factory, FactoryPaths.ATTRIBUTE_MAPPING, replacesId);
				final ObjectNode s = suggestions.addObject();
				s.put("id", mapping.getOccurrencePrimaryKey().format());
				s.put("source", columnLabelById.get(FeedDesignerUserService.textOf(mapping, FactoryPaths.AttributeMapping.LANDING_COLUMN)));
				s.put("target", targetName);
				s.put("transformation", pipeline);
				s.put("status", FeedDesignerUserService.textOf(mapping, FactoryPaths.AttributeMapping.STATUS));
				s.put("rationale", com.on.ebx.factory.wizard.RationaleText.display(FeedDesignerUserService.textOf(mapping, FactoryPaths.AttributeMapping.RATIONALE), this.locale));
				s.put("replacesId", replacesId);
				s.put("replacesSource", replaces == null ? "" : columnLabelById.get(FeedDesignerUserService.textOf(replaces, FactoryPaths.AttributeMapping.LANDING_COLUMN)));
				continue;
			}
			final ObjectNode r = rules.addObject();
			r.put("id", mapping.getOccurrencePrimaryKey().format());
			r.put("source", columnLabelById.get(FeedDesignerUserService.textOf(mapping, FactoryPaths.AttributeMapping.LANDING_COLUMN)));
			r.put("target", targetName);
			r.put("transformation", pipeline);
			final AttentionRules.Flag flag = attentionByTarget.get(targetName);
			if (flag != null)
			{
				r.put("attention", flag.kind);
				r.put("attentionFilledRows", flag.filledRows == null ? null : flag.filledRows.longValue());
				r.put("attentionTotalRows", flag.totalRows == null ? null : flag.totalRows.longValue());
			}
		}
		return f;
	}

	/** {@link FactoryPaths.AttributeMapping#PIPELINE}, or the legacy {@link FactoryPaths.AttributeMapping#TRANSFORMATION}/{@link FactoryPaths.AttributeMapping#ARGUMENT} translated - same fallback {@code feedModel}'s own rules loop already applied before this lot, factored out so {@link #feedModel}'s new suggestions branch can reuse it verbatim. */
	private String pipelineOf(final Adaptation pMapping, final String pEntityId, final String pTargetName)
	{
		final String pipeline = FeedDesignerUserService.textOf(pMapping, FactoryPaths.AttributeMapping.PIPELINE);
		if (!pipeline.isEmpty())
		{
			return pipeline;
		}
		final String transformation = FeedDesignerUserService.textOf(pMapping, FactoryPaths.AttributeMapping.TRANSFORMATION);
		if (transformation.isEmpty())
		{
			return "";
		}
		final String targetDataType = this.dataTypeOf(pEntityId, pTargetName);
		try
		{
			return PipelineCompat.translate(transformation, FeedDesignerUserService.textOf(pMapping, FactoryPaths.AttributeMapping.ARGUMENT), targetDataType);
		}
		catch (final IllegalArgumentException ex)
		{
			return ""; // an old row this translator cannot express (docs/TARGET-MAPPING.md sec 2.2's own
			// "throws at plan time" cases) - shown blank rather than crashing the whole designer
		}
	}

	/**
	 * Delta 3: builds {@code staging.AttentionRules.MappingInput} from this feed's own non-suggested {@code
	 * AttributeMapping} rows joined to their {@code LandingColumn} (fillRate/distinctCount) and this feed's
	 * {@code LandingTable} (row count), runs {@link AttentionRules#detect}, and keys the result by TARGET
	 * ATTRIBUTE NAME (same key every other per-rule map in this class uses). A column never profiled
	 * (null fillRate) never raises {@link AttentionRules#CONSTANT_COLUMN}/{@link AttentionRules#EMPTY_COLUMN}
	 * - {@code AttentionRules.MappingInput#filledRows()}'s own null-propagation (class Javadoc: "no flag when
	 * columns were never profiled"). When two rows of this feed would flag the same target (never expected in
	 * practice - one rule per target), the FIRST flag found wins.
	 */
	private Map<String, AttentionRules.Flag> attentionFlagsByTarget(final Adaptation pFeedRow, final String pEntityId, final String pFeedId)
	{
		final String landingTableId = FeedDesignerUserService.textOf(pFeedRow, FactoryPaths.TransferFeed.LANDING_TABLE);
		final Adaptation landingTable = FeedDesignerUserService.lookup(this.factory, FactoryPaths.LANDING_TABLE, landingTableId);
		// staging-proposal live fix: LandingRowCounts.rowCountOf, not the raw sourceRowCount field alone -
		// see its own Javadoc (sourceRowCount stays null for a sample-file source; lastLoadRows does not).
		final Long rowCount = LandingRowCounts.rowCountOf(landingTable);
		final List<AttentionRules.MappingInput> inputs = new ArrayList<AttentionRules.MappingInput>();
		final Map<String, String> targetNameByAttributeId = new LinkedHashMap<String, String>();
		for (final Adaptation mapping : this.mappingsOfFeed(pFeedId))
		{
			if (FeedDesignerUserService.hasAlternativeTo(mapping))
			{
				continue;
			}
			final String attributeId = FeedDesignerUserService.textOf(mapping, FactoryPaths.AttributeMapping.ATTRIBUTE);
			final String columnId = FeedDesignerUserService.textOf(mapping, FactoryPaths.AttributeMapping.LANDING_COLUMN);
			final Adaptation column = FeedDesignerUserService.lookup(this.factory, FactoryPaths.LANDING_COLUMN, columnId);
			inputs.add(new AttentionRules.MappingInput(attributeId, columnId, pEntityId, FeedDesignerUserService.textOf(mapping, FactoryPaths.AttributeMapping.STATUS),
				FeedDesignerUserService.doubleOf(column, FactoryPaths.LandingColumn.FILL_RATE), FeedDesignerUserService.longOf(column, FactoryPaths.LandingColumn.DISTINCT_COUNT),
				rowCount));
			final String targetName = this.attributeNameOfId(pEntityId, attributeId);
			if (targetName != null && !targetNameByAttributeId.containsKey(attributeId))
			{
				targetNameByAttributeId.put(attributeId, targetName);
			}
		}
		final Map<String, AttentionRules.Flag> out = new LinkedHashMap<String, AttentionRules.Flag>();
		for (final AttentionRules.Flag flag : AttentionRules.detect(inputs))
		{
			final String targetName = targetNameByAttributeId.get(flag.attributeId);
			if (targetName != null && !out.containsKey(targetName))
			{
				out.put(targetName, flag);
			}
		}
		return out;
	}

	private String dataTypeOf(final String pEntityId, final String pAttributeName)
	{
		final RequestResult rows = UserView.select(this.factory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + FeedDesignerUserService.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (pAttributeName.equals(FeedDesignerUserService.textOf(row, FactoryPaths.StagingAttribute.NAME)))
				{
					return FeedDesignerUserService.textOf(row, FactoryPaths.StagingAttribute.DATA_TYPE);
				}
			}
		}
		finally
		{
			rows.close();
		}
		return "STRING";
	}

	/**
	 * {@code SessionPermissions.getAdaptationAccessPermission(Adaptation).isReadWrite()} on the ROOT feed's
	 * own record - same signature and the same fail-closed posture as {@code RecordLineageReader}'s own
	 * {@code canReadWrite} (javap-verified against {@code ebx-lib.jar} there); checked once for the whole
	 * tree (every feed of one project shares the same table-level permission in practice).
	 */
	private boolean canEdit(final List<Adaptation> pTree)
	{
		if (pTree.isEmpty() || this.session == null)
		{
			return false;
		}
		try
		{
			final AccessPermission permission = this.session.getPermissions().getAdaptationAccessPermission(pTree.get(0));
			return permission != null && permission.isReadWrite();
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	// ------------------------------------------------------------------ posted JSON

	/** One posted rule of {@link #RULES_PARAM} - {@code id} travels for display only, the diff itself is keyed by {@code target} (class Javadoc). */
	private static final class PostedRule
	{
		final String source;
		final String target;
		final String transformation;

		PostedRule(final String pSource, final String pTarget, final String pTransformation)
		{
			this.source = pSource == null ? "" : pSource;
			this.target = pTarget == null ? "" : pTarget;
			this.transformation = pTransformation == null ? "" : pTransformation;
		}
	}

	private static Map<String, List<PostedRule>> parseRulesByFeed(final String pJson)
	{
		final Map<String, List<PostedRule>> out = new LinkedHashMap<String, List<PostedRule>>();
		if (pJson == null || pJson.trim().isEmpty())
		{
			return out;
		}
		try
		{
			final JsonNode node = FeedDesignerUserService.MAPPER.readTree(pJson);
			final Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> entry = fields.next();
				final List<PostedRule> rules = new ArrayList<PostedRule>();
				for (final JsonNode ruleNode : entry.getValue())
				{
					rules.add(new PostedRule(FeedDesignerUserService.text(ruleNode, "source"), FeedDesignerUserService.text(ruleNode, "target"),
						FeedDesignerUserService.text(ruleNode, "transformation")));
				}
				out.put(entry.getKey(), rules);
			}
		}
		catch (final Exception ex)
		{
			// malformed JSON - never a stack trace on the page; onSave then treats every feed as "no posted rules"
		}
		return out;
	}

	private static Map<String, String> parsePipelinesByFeed(final String pJson)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pJson == null || pJson.trim().isEmpty())
		{
			return out;
		}
		try
		{
			final JsonNode node = FeedDesignerUserService.MAPPER.readTree(pJson);
			final Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> entry = fields.next();
				out.put(entry.getKey(), entry.getValue().isTextual() ? entry.getValue().asText() : "");
			}
		}
		catch (final Exception ex)
		{
			// malformed JSON - never a stack trace on the page
		}
		return out;
	}

	private static String text(final JsonNode pNode, final String pKey)
	{
		final JsonNode value = pNode.get(pKey);
		return value == null || value.isNull() ? "" : value.asText();
	}

	// ------------------------------------------------------------------ small EBX helpers

	private static Adaptation lookup(final Adaptation pFactory, final com.orchestranetworks.schema.Path pTablePath, final String pId)
	{
		if (pFactory == null || pId == null || pId.isEmpty())
		{
			return null;
		}
		try
		{
			return UserView.byKey(pFactory.getTable(pTablePath), PrimaryKey.parseString(pId));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static String textOf(final Adaptation pRecord, final com.orchestranetworks.schema.Path pPath)
	{
		if (pRecord == null)
		{
			return "";
		}
		final Object value = UserView.get(pRecord, pPath);
		return value == null ? "" : String.valueOf(value);
	}

	/** Staging-proposal "designer" lot: whether this {@code AttributeMapping} is a suggested-fix row (see {@link #saveFeedRules}/{@link #suggestionsOf}). */
	private static boolean hasAlternativeTo(final Adaptation pMapping)
	{
		return !FeedDesignerUserService.textOf(pMapping, FactoryPaths.AttributeMapping.ALTERNATIVE_TO).isEmpty();
	}

	private static Double doubleOf(final Adaptation pRecord, final com.orchestranetworks.schema.Path pPath)
	{
		if (pRecord == null)
		{
			return null;
		}
		final Object value = pRecord.get(pPath);
		return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : null;
	}

	private static Long longOf(final Adaptation pRecord, final com.orchestranetworks.schema.Path pPath)
	{
		if (pRecord == null)
		{
			return null;
		}
		final Object value = pRecord.get(pPath);
		return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
	}

	/** {@code 'value'}, with an embedded quote doubled - same convention as {@code ebx.CatalogReader.quote}/{@code ebx.ui.FactoryHtml.quote}. */
	private static String quote(final String pValue)
	{
		return "'" + String.valueOf(pValue).replace("'", "''") + "'";
	}

	private static void appendSpaced(final StringBuilder pOut, final String pText)
	{
		if (pText == null || pText.isEmpty())
		{
			return;
		}
		if (pOut.length() > 0)
		{
			pOut.append(' ');
		}
		pOut.append(pText);
	}

	// security review 2026-09-24, finding I3: aligned on FactoryHtml.esc (escapes the single quote too, not
	// just & < > ").
	private static String esc(final String pText)
	{
		if (pText == null)
		{
			return "";
		}
		return pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}
}
