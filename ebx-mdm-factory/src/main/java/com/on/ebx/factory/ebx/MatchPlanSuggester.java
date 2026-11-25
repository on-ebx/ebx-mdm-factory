package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.matching.CandidateFilterCheck;
import com.on.ebx.factory.master.ChildEntityMatching;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.MatchPlanLlmPass;
import com.on.ebx.factory.master.MatchPlanRules;
import com.on.ebx.factory.master.StoredJsonFit;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Deterministic (no language model, no MCP call) first pass over a project's PROMOTED staging entities
 * (`docs/MATCHING.md` &sect;2, `PLAN.md` "Zone MDM" lot 3): proposes at most one {@code MatchPlan} per
 * promoted entity, from its own accepted attributes and the accepted {@code QualityRule} evidence already
 * gathered on them ({@link MatchPlanRules} does every actual decision, pure and unit-tested; this class only
 * reads the EBX rows and writes the proposals). Route {@code POST /projects/{code}/matching/suggest}.
 * <p>
 * <b>"Promoted"</b> is computed the same way {@code ebx.MasterGenerator} computes it -
 * {@code master.MasterModel.buildFromStaging(...).builtEntities} - never re-derived: an entity that is
 * itself a MEMBER of another accepted (canonical) entity ({@code Account}, a member of the canonical
 * {@code Company}, on this project) is never handed to {@link MatchPlanRules} in the first place. <b>A
 * "child" entity (an entity carrying an accepted attribute referencing another promoted entity - {@code
 * Contact} on this project) IS handed to {@link MatchPlanRules} and proposed exactly like any other promoted
 * entity - 2026-09-22, reversed (`docs/MATCHING.md`): a child is precisely the kind of data that needs
 * deduplication, and the previous "skip a child by default" rule risked leaving it out entirely.</b> {@link
 * MatchPlanRules} still needs to KNOW which entity is a child (for rationale wording and to offer the
 * reference attribute as a supporting clustering field, never a decision-making field - see that class' own
 * Javadoc), and <b>a reference it is fed must already be RESOLVED the same way a real foreign key would be</b>
 * - {@link #toRuleEntity} calls {@code masterModel.resolveReference(...)} on every accepted {@code
 * referenceEntity} before handing it to {@link MatchPlanRules.AttributeInfo} (coordinator review, 2026-09-21,
 * after a real run showed {@code Contact} wrongly NOT recognised as a child by the LLM pass' own copy of this
 * check - see {@code ebx.MatchPlanLlmSuggester}'s own Javadoc for the full story): {@code Contact.accountId}
 * references {@code Account}, itself a member of {@code Company} - MatchPlanRules must see {@code Company}
 * named as the child-of parent, never the raw, never-independently-promoted {@code Account}.
 * <p>
 * Like {@code MasterGenerator}/{@code MasterLoader}, this class runs {@link MemberEntitiesBackfill} first -
 * "promoted" depends on {@code StagingEntity.memberEntities} being current, the same reasoning both of those
 * classes' own Javadoc already gives.
 * <p>
 * <b>Never touches a decided {@code MatchPlan}.</b> A plan already {@code ACCEPTED}/{@code REJECTED}/
 * {@code APPLIED} for an entity is left exactly as it is - only a still-{@code PROPOSED} plan (or no plan at
 * all) is written/refreshed, so re-running this after {@code Company} gains new attributes (LEI, a
 * registration number - `docs/MASTER-ZONE.md` &sect;4.1's own open item) picks up the new evidence on an
 * undecided plan without ever silently rewriting something a human already reviewed - the same "propose
 * refreshes PROPOSED, never touches a decision" rule {@code StagingSuggester}/{@code QualitySuggester} apply
 * to their own tables.
 * <p>
 * No {@code PipelineStep} is recorded - not one of the model's declared step values, the same reasoning
 * {@code RelationChecker}/{@code StagingSuggester}/{@code QualitySuggester} already give for their own
 * proposal-only routes.
 * <p>
 * <b>2026-09-21, "Rapprochement": the same cross-system usability guard the language-model pass enforces
 * ({@code master.CrossSystemFieldGuard}) is wired here too</b> - {@link MatchPlanRules} proposes each
 * validated identifier as both an exact tier and a clustering field, so it must not propose what that guard
 * would itself refuse. Live measurement runs on the project's MASTER table (matching always runs on master,
 * never staging - same rule {@code MatchPlanLlmSuggester} already follows), opened the same tolerant way
 * {@code MatchPlanLlmSuggester#openMasterDataset} does: when the project has no master zone yet
 * (`Project.masterDataspace`/`masterDataset` blank, or the dataset cannot be found), both lookups stay null
 * and {@link MatchPlanRules#propose(List)}'s own original, unguarded behaviour applies unchanged - this
 * route has never required a master zone to exist, and must not start failing just because this guard was
 * added. Both live SQL statements are {@code ebx.CrossSystemMeasures#sharedCount}/{@code
 * #perSystemFilledCounts} - a single package-private helper shared with {@code MatchPlanLlmSuggester}
 * (coordinator review, 2026-09-21, second pass: "do not create a third private copy of the SQL"). An
 * earlier revision of this class kept its own duplicate of both methods, on the (then-correct) belief that
 * touching {@code MatchPlanLlmSuggester.java} was off-limits because of a concurrent, unrelated change on
 * that file - lifted the same day once that other branch merged; the duplicate is gone.
 */
public final class MatchPlanSuggester
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String CHECK_DIGIT = "CHECK_DIGIT";
	// requirements / evidence limits: master.StoredJsonFit - a proposal that does not fit is skipped and reported ("refused"), never cut

	private MatchPlanSuggester()
	{
	}

	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = MatchPlanSuggester.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		// delivery lot 1: a project delivered here by EBX Staging is designed elsewhere - no suggestion on a target
		ProjectOrigin.refuseIfDelivered(pFactory, project);
		final String projectId = project.getOccurrencePrimaryKey().format();

		final MemberEntitiesBackfill.Result backfill = MemberEntitiesBackfill.run(pSession, pFactory, projectId);

		final Map<String, String> entityIdByName = new HashMap<String, String>();
		final List<StagingModel.EntityInfo> stagingEntities = MatchPlanSuggester.readEntities(pFactory, projectId, entityIdByName);
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(stagingEntities);

		final List<MatchPlanRules.EntityInfo> ruleEntities = new ArrayList<MatchPlanRules.EntityInfo>();
		for (final String entityName : masterModel.builtEntities)
		{
			ruleEntities.add(MatchPlanSuggester.toRuleEntity(pFactory, entityIdByName.get(entityName), entityName, masterModel));
		}

		// CrossSystemFieldGuard (class Javadoc, `PLAN.md`/`docs/MATCHING.md` "Rapprochement", 2026-09-21) - wired
		// only when the project's master zone already exists; both lookups stay null otherwise (this route has
		// never required one, and must keep working exactly as before until it does).
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		final Adaptation masterDataset = MatchPlanSuggester.blank(masterDataspaceKey) || MatchPlanSuggester.blank(masterDatasetName) ? null
			: MatchPlanSuggester.openMasterDataset(pFactory.getHome().getRepository(), masterDataspaceKey, masterDatasetName);
		final MatchPlanLlmPass.CrossSystemUsageLookup sharedCountLookup = masterDataset == null ? null : new MatchPlanLlmPass.CrossSystemUsageLookup()
		{
			@Override
			public Integer sharedCount(final String pEntityName, final String pFieldName)
			{
				return CrossSystemMeasures.sharedCount(pSession, masterDataset, pEntityName, pFieldName);
			}
		};
		final MatchPlanLlmPass.PerSystemFillLookup perSystemFillLookup = masterDataset == null ? null : new MatchPlanLlmPass.PerSystemFillLookup()
		{
			@Override
			public Map<String, Long> filledCountBySystem(final String pEntityName, final String pFieldName)
			{
				return CrossSystemMeasures.perSystemFilledCounts(pSession, masterDataset, pEntityName, pFieldName);
			}
		};
		// 2026-09-22, "child entities matched too": the within-system mirror of the two lookups above - see
		// MatchPlanLlmPass.WithinSystemDuplicateLookup's own Javadoc. Same tolerant wiring: null whenever the
		// project has no master zone yet, exactly like the other two.
		final MatchPlanLlmPass.WithinSystemDuplicateLookup withinSystemDuplicateLookup = masterDataset == null ? null
			: new MatchPlanLlmPass.WithinSystemDuplicateLookup()
			{
				@Override
				public Integer duplicateCount(final String pEntityName, final String pFieldName)
				{
					return CrossSystemMeasures.withinSystemDuplicateCount(pSession, masterDataset, pEntityName, pFieldName);
				}
			};
		final List<MatchPlanRules.Proposal> proposals = MatchPlanRules.propose(ruleEntities, sharedCountLookup, perSystemFillLookup, withinSystemDuplicateLookup);

		final List<String> written = new ArrayList<String>();
		final List<String> refreshed = new ArrayList<String>();
		final List<String> alreadyDecided = new ArrayList<String>();
		final List<String> skipped = new ArrayList<String>();
		final List<String> refused = new ArrayList<String>();
		// lot M8a: one line per proposed entity - the candidate-filter verdict of the plan the rules produced, with the warning when there is one
		final List<String> candidateSearch = new ArrayList<String>();
		// 2026-09-22, "child entities matched too": informational only, never an exclusion list any more - a child
		// entity is written/refreshed exactly like any other entity above; this just tells the caller which ones
		// are children, and that their own reference attribute was offered as a supporting field (requirement 4,
		// `docs/MATCHING.md`).
		final List<String> childEntities = new ArrayList<String>();
		// lot M2 (2026-10-17, docs/MATCHING.md sect;56): a child entity follows its parent - no plan of its own is proposed, whatever the rules could say about it
		final Map<String, List<String>> parentsByChild = ChildEntityMatching.parentsByChild(masterModel);
		final List<String> followsParent = new ArrayList<String>();

		for (final MatchPlanRules.Proposal proposal : proposals)
		{
			final List<String> parents = parentsByChild.get(proposal.entityName);
			if (parents != null)
			{
				followsParent.add(ChildEntityMatching.followsNote(proposal.entityName, parents));
				continue;
			}
			final String entityId = entityIdByName.get(proposal.entityName);
			final Adaptation existing = MatchPlanSuggester.findMatchPlan(pFactory, entityId);
			if (!proposal.propose)
			{
				skipped.add(proposal.entityName + ": " + proposal.skipReason);
				continue;
			}
			if (existing != null && !"PROPOSED".equals(existing.getString(FactoryPaths.MatchPlan.STATUS)))
			{
				alreadyDecided.add(proposal.entityName + " (" + existing.getString(FactoryPaths.MatchPlan.STATUS) + ")");
				continue;
			}
			// lot M8a (2026-10-04, docs/MATCHING.md sect;51): the rule-based pass never invents keys - it only REPORTS whether what it produced gets candidate
			// filters from the add-on, in the response and (when it does not) in the plan's rationale, never refusing its own proposal
			final CandidateFilterCheck.Result filterResult = MatchPlanSuggester.candidateFilterCheck(proposal.requirementsJson);
			final String filterWarning = CandidateFilterCheck.warning(filterResult, CandidateFilterCheck.jvmOptionEnabled());
			candidateSearch.add(proposal.entityName + ": " + (filterResult.filtered() ? "FILTERED on " + filterResult.filtersPhrase() : filterResult.verdict)
				+ (filterWarning == null ? "" : " - " + filterWarning));
			// fix/match-plan-requirements-length (2026-10-12): JSON read back later is never stored cut - the proposal is refused, the row keeps its plan
			final String doesNotFit = StoredJsonFit.planRefusal(proposal.requirementsJson, proposal.evidenceJson);
			if (doesNotFit != null)
			{
				refused.add(proposal.entityName + ": " + doesNotFit);
				continue;
			}
			MatchPlanSuggester.writeMatchPlan(pSession, pFactory, entityId, existing, proposal, filterResult.notFiltered() ? filterWarning : null);
			(existing == null ? written : refreshed).add(proposal.entityName);
		}
		for (final MatchPlanRules.EntityInfo ruleEntity : ruleEntities)
		{
			for (final MatchPlanRules.AttributeInfo attribute : ruleEntity.attributes)
			{
				if (attribute.referenceEntity != null && !attribute.referenceEntity.equals(ruleEntity.name))
				{
					childEntities.add(ruleEntity.name + " (child of " + attribute.referenceEntity + "): parent reference offered as supporting field.");
					break;
				}
			}
		}

		final ObjectNode out = MatchPlanSuggester.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("memberEntitiesBackfilled", backfill.backfilled);
		out.put("promotedEntities", masterModel.builtEntities.size());
		MatchPlanSuggester.putStrings(out, "written", written);
		MatchPlanSuggester.putStrings(out, "refreshed", refreshed);
		MatchPlanSuggester.putStrings(out, "alreadyDecided", alreadyDecided);
		MatchPlanSuggester.putStrings(out, "skipped", skipped);
		MatchPlanSuggester.putStrings(out, "refused", refused);
		MatchPlanSuggester.putStrings(out, "childEntities", childEntities);
		MatchPlanSuggester.putStrings(out, "followsParent", followsParent);
		MatchPlanSuggester.putStrings(out, "candidateSearch", candidateSearch);
		return out;
	}

	// --------------------------------------------------------------------------------------------------------- write

	/** Lot M8a: the candidate-filter verdict of a stored plan's requirements JSON; never throws (unparsable = not applicable). */
	static CandidateFilterCheck.Result candidateFilterCheck(final String pRequirementsJson)
	{
		try
		{
			return CandidateFilterCheck.check(MatchPlanSuggester.MAPPER.readTree(pRequirementsJson));
		}
		catch (final Exception ex)
		{
			return CandidateFilterCheck.check(null);
		}
	}

	/** Lot M8a: the rationale as stored - the proposal's own, followed by the candidate-search warning when there is one. */
	static String rationaleWithWarning(final String pRationale, final String pWarning)
	{
		return pWarning == null || pWarning.trim().isEmpty() ? pRationale : pRationale + " " + pWarning;
	}

	private static void writeMatchPlan(final Session pSession, final Adaptation pFactory, final String pEntityId, final Adaptation pExisting,
		final MatchPlanRules.Proposal pProposal, final String pCandidateSearchWarning) throws OperationException
	{
		final AdaptationTable matchPlans = pFactory.getTable(FactoryPaths.MATCH_PLAN);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pExisting == null ? pContext.getContextForNewOccurrence(matchPlans) : pContext.getContext(pExisting.getAdaptationName());
				value.setValue(pEntityId, FactoryPaths.MatchPlan.ENTITY);
				value.setValue(pProposal.requirementsJson, FactoryPaths.MatchPlan.REQUIREMENTS);
				value.setValue("RULE", FactoryPaths.MatchPlan.ORIGIN);
				value.setValue(Integer.valueOf(pProposal.confidence), FactoryPaths.MatchPlan.CONFIDENCE);
				value.setValue(TextLimits.truncate(MatchPlanSuggester.rationaleWithWarning(pProposal.rationale, pCandidateSearchWarning), TextLimits.MESSAGE_MAX_LENGTH),
					FactoryPaths.MatchPlan.RATIONALE);
				// review-match-plan lot (2026-09-22): the structured sibling of the prose already folded into
				// rationale above - see master.MatchPlanEvidence's own Javadoc for the shape.
				value.setValue(pProposal.evidenceJson, FactoryPaths.MatchPlan.EVIDENCE);
				value.setValue("PROPOSED", FactoryPaths.MatchPlan.STATUS);
				if (pExisting == null)
				{
					value.setValue(Boolean.valueOf(MatchPlanSuggester.autoMergeForWrite(true, false)), FactoryPaths.MatchPlan.AUTO_MERGE);
					pContext.doCreateOccurrence(value, matchPlans);
				}
				else
				{
					// automerge-toggle lot (2026-09-23), task 2: same explicit preservation as
					// MatchPlanLlmSuggester#write - see that method's own comment and #autoMergeForWrite's Javadoc.
					value.setValue(Boolean.valueOf(MatchPlanSuggester.autoMergeForWrite(false, Boolean.TRUE.equals(pExisting.get(FactoryPaths.MatchPlan.AUTO_MERGE)))),
						FactoryPaths.MatchPlan.AUTO_MERGE);
					pContext.doModifyContent(pExisting, value);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveMatchPlan", "Could not write the matching plan of entity " + pEntityId + ": " + result.getExceptionFullMessage(java.util.Locale.ENGLISH), pEntityId);
		}
	}

	/**
	 * Automerge-toggle lot (2026-09-23), task 2: same pure rule and same reason as {@code
	 * ebx.MatchPlanLlmSuggester#autoMergeForWrite} - a brand-new row never inherits {@code true} (human
	 * decision only), an existing row keeps whatever a person set from the review screen's own toggle (task 1).
	 * Unit-tested without EBX ({@code MatchPlanSuggesterAutoMergeTest}), same discipline as {@link #blank}.
	 */
	static boolean autoMergeForWrite(final boolean pIsNewRow, final boolean pExistingAutoMerge)
	{
		return !pIsNewRow && pExistingAutoMerge;
	}

	// ---------------------------------------------------------------------------------------------------------- read

	private static MatchPlanRules.EntityInfo toRuleEntity(final Adaptation pFactory, final String pEntityId, final String pEntityName,
		final MasterModel.Result pMasterModel)
	{
		final List<MatchPlanRules.AttributeInfo> attributes = new ArrayList<MatchPlanRules.AttributeInfo>();
		// Matching-key contract (2026-09-22): a source attribute's own derived key (StagingAttribute.derivation
		// FOLD_KEY, derivedFrom pointing back at it) lets the deterministic route compare/cluster via the key too -
		// see master.MatchPlanRules' own Javadoc. Guarded per CLAUDE.md sec 9 - see readMatchKeyByName's own Javadoc.
		final Map<String, String> matchKeyByName = MatchPlanSuggester.readMatchKeyByName(pFactory, pEntityId);
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.PRIMARY_KEY)) || !TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.StagingAttribute.STATUS)))
				{
					continue;
				}
				final String attributeId = row.getOccurrencePrimaryKey().format();
				final String referenceEntityId = row.getString(FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
				final String rawReferenceEntityName = referenceEntityId == null ? null : MatchPlanSuggester.entityName(pFactory, referenceEntityId);
				// resolved through MasterModel.Result.resolveReference (coordinator review, 2026-09-21) - a reference to a MEMBER
				// entity (Contact.accountId -> Account, where Account is a member of the canonical Company) must name the
				// canonical entity here, not the raw, never-promoted member name, or MatchPlanRules.childOf's skip reason
				// names an entity that was never actually proposed for matching in the first place
				final String referenceEntityName = rawReferenceEntityName == null ? null : pMasterModel.resolveReference(rawReferenceEntityName);
				final String name = row.getString(FactoryPaths.StagingAttribute.NAME);
				attributes.add(new MatchPlanRules.AttributeInfo(name, row.getString(FactoryPaths.StagingAttribute.DATA_TYPE), referenceEntityName,
					MatchPlanSuggester.identifierKind(pFactory, attributeId), matchKeyByName.get(name)));
			}
		}
		finally
		{
			rows.close();
		}
		return new MatchPlanRules.EntityInfo(pEntityName, attributes);
	}

	/**
	 * Source attribute name -> the name of its own derived matching key ({@code StagingAttribute.derivation} =
	 * {@code FOLD_KEY}, {@code derivedFrom} pointing back at the source) - 2026-09-22 contract, implemented on the
	 * model side by a separate agent (`feature/match-key-attribute`). Guarded per CLAUDE.md sec 9:
	 * {@code Adaptation.get}/{@code getString} throws on a path the table does not define, so every read here goes
	 * through {@link #safeString} (this class' own guarded read, same pattern {@code ebx.CatalogReader}'s own
	 * private {@code text} helper uses), which answers null on a schema that does not carry these fields yet - this
	 * method then simply returns an empty map, treating every attribute as "not derived", so this class compiles
	 * and behaves correctly on a model built before the other branch merges.
	 * <p>
	 * <b>Coordinator review, 2026-09-22: only an ACCEPTED-or-APPLIED derived (key) row counts as an existing
	 * key.</b> The key attribute row is a staging proposal like any other, with its own {@code
	 * StagingAttribute.status} ({@link FactoryPaths.StagingAttribute#STATUS}, read through {@link
	 * TechnicalKeyRule#isAcceptedLike} - the same "ACCEPTED or APPLIED" test this class already applies to every
	 * ordinary attribute a few lines above, in {@code toRuleEntity}'s own loop). A still-{@code PROPOSED} key is
	 * not in the master table yet, and a {@code REJECTED} one must never be used - both are excluded here so an
	 * entity with only a {@code PROPOSED}/{@code REJECTED} key for a field behaves exactly as if that field had
	 * no key at all (the existing cross-system-usability refusal stands, unqualified).
	 */
	private static Map<String, String> readMatchKeyByName(final Adaptation pFactory, final String pEntityId)
	{
		final List<Adaptation> rows = new ArrayList<Adaptation>();
		final Map<String, String> nameById = new HashMap<String, String>();
		final RequestResult requestResult = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = requestResult.nextAdaptation(); row != null; row = requestResult.nextAdaptation())
			{
				rows.add(row);
				nameById.put(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.StagingAttribute.NAME));
			}
		}
		finally
		{
			requestResult.close();
		}
		final Map<String, String> out = new HashMap<String, String>();
		for (final Adaptation row : rows)
		{
			if (!"FOLD_KEY".equals(MatchPlanSuggester.safeString(row, "./derivation"))
				|| !TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.StagingAttribute.STATUS)))
			{
				continue;
			}
			final String derivedFromId = MatchPlanSuggester.safeString(row, "./derivedFrom");
			final String sourceName = derivedFromId == null ? null : nameById.get(derivedFromId);
			if (sourceName != null)
			{
				out.put(sourceName, row.getString(FactoryPaths.StagingAttribute.NAME));
			}
		}
		return out;
	}

	/** {@code Adaptation.get}/{@code getString} throws on a path the table does not define (CLAUDE.md sec 9) - guarded read for {@code derivedFrom}/{@code derivation}. */
	private static String safeString(final Adaptation pRow, final String pPath)
	{
		final Path path = Path.parse(pPath);
		return pRow.getSchemaNode().getNode(path) == null ? null : pRow.getString(path);
	}

	/** The {@code argument} (IBAN/LEI/SIREN/SIRET) of an ACCEPTED-or-APPLIED {@code QualityRule} of kind CHECK_DIGIT scoped to this attribute, or null. At most one is expected; the first found wins. */
	private static String identifierKind(final Adaptation pFactory, final String pAttributeId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.QUALITY_RULE).createRequestResult(
			"./attribute=" + CatalogReader.quote(pAttributeId) + " and ./kind=" + CatalogReader.quote(MatchPlanSuggester.CHECK_DIGIT));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.QualityRule.STATUS)))
				{
					return row.getString(FactoryPaths.QualityRule.ARGUMENT);
				}
			}
			return null;
		}
		finally
		{
			rows.close();
		}
	}

	private static String entityName(final Adaptation pFactory, final String pEntityId)
	{
		final Adaptation row = pFactory.getTable(FactoryPaths.STAGING_ENTITY).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pEntityId));
		return row == null ? null : row.getString(FactoryPaths.StagingEntity.NAME);
	}

	private static Adaptation findMatchPlan(final Adaptation pFactory, final String pEntityId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.MATCH_PLAN).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	/** Same shape {@code MasterGenerator.readEntities}/{@code MasterLoader.readEntities} already read - a separate copy per this codebase's own convention (each caller keeps its own private reader). */
	private static List<StagingModel.EntityInfo> readEntities(final Adaptation pFactory, final String pProjectId, final Map<String, String> pEntityIdByName)
	{
		final List<Adaptation> entityRowsList = new ArrayList<Adaptation>();
		final Map<String, String> entityIdToName = new HashMap<String, String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				entityRowsList.add(row);
				final String id = row.getOccurrencePrimaryKey().format();
				final String name = row.getString(FactoryPaths.StagingEntity.NAME);
				entityIdToName.put(id, name);
				pEntityIdByName.put(name, id);
			}
		}
		finally
		{
			entityRows.close();
		}

		final List<StagingModel.EntityInfo> out = new ArrayList<StagingModel.EntityInfo>();
		for (final Adaptation row : entityRowsList)
		{
			final String entityId = row.getOccurrencePrimaryKey().format();
			final List<String> memberNames = new ArrayList<String>();
			final List<?> rawMemberIds = row.getList(FactoryPaths.StagingEntity.MEMBER_ENTITIES);
			if (rawMemberIds != null)
			{
				for (final Object rawMemberId : rawMemberIds)
				{
					final String memberName = entityIdToName.get(String.valueOf(rawMemberId));
					if (memberName != null)
					{
						memberNames.add(memberName);
					}
				}
			}
			out.add(new StagingModel.EntityInfo(row.getString(FactoryPaths.StagingEntity.NAME), row.getString(FactoryPaths.StagingEntity.LABEL),
				row.getString(FactoryPaths.StagingEntity.STATUS), MatchPlanSuggester.readAttributes(pFactory, entityId, entityIdToName), memberNames));
		}
		return out;
	}

	private static List<StagingModel.AttributeInfo> readAttributes(final Adaptation pFactory, final String pEntityId, final Map<String, String> pEntityIdToName)
	{
		final List<StagingModel.AttributeInfo> out = new ArrayList<StagingModel.AttributeInfo>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object rawMaxLength = row.get(FactoryPaths.StagingAttribute.MAX_LENGTH);
				final Integer maxLength = rawMaxLength instanceof Number ? Integer.valueOf(((Number) rawMaxLength).intValue()) : null;
				final boolean mandatory = Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.MANDATORY));
				final boolean primaryKey = Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.PRIMARY_KEY));
				final String referenceEntityId = row.getString(FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
				final String referenceEntityName = referenceEntityId == null ? null : pEntityIdToName.get(referenceEntityId);
				out.add(new StagingModel.AttributeInfo(row.getString(FactoryPaths.StagingAttribute.NAME), row.getString(FactoryPaths.StagingAttribute.LABEL),
					row.getString(FactoryPaths.StagingAttribute.DATA_TYPE), maxLength, mandatory, primaryKey, referenceEntityName,
					row.getString(FactoryPaths.StagingAttribute.STATUS)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	private static void putStrings(final ObjectNode pOut, final String pKey, final List<String> pValues)
	{
		final ArrayNode array = pOut.putArray(pKey);
		for (final String value : pValues)
		{
			array.add(value);
		}
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	/** Same tolerant shape as {@code ebx.MatchPlanLlmSuggester#openMasterDataset} - a separate copy per this codebase's own convention (see class Javadoc): never throws, returns null when either the home or the dataset cannot be found. */
	private static Adaptation openMasterDataset(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName)
	{
		final AdaptationHome masterHome = pRepository.lookupHome(HomeKey.forBranchName(pMasterDataspaceKey));
		return masterHome == null ? null : masterHome.findAdaptationOrNull(AdaptationName.forName(pMasterDatasetName));
	}

}
