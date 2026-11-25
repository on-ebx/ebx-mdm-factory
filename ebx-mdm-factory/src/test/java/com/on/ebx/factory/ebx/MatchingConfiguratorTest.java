package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JUnit-free: run the main, a failure throws. Covers the pure pieces of the 2026-09-22 validate-response-
 * shape fix that need no EBX class: {@link MatchingConfigurator#describeValidationMessages} (the message
 * array a validate refusal now quotes instead of the raw JSON) and the idempotence resume check ({@link
 * MatchingConfigurator#priorAttemptCompletedGenerationAndLink}), built entirely from this class' OWN
 * previously-recorded {@link MatchingConfigurator#toolDetail} shape - never a guess at the live server's own
 * found-config JSON (see {@link MatchingConfigurator}'s own class Javadoc, "Idempotence corollary"). Same
 * package as {@link MatchingConfigurator} on purpose, to reach these package-private statics directly - the
 * same convention {@code PipelineStepsTest} already follows for {@code PipelineSteps.scopeEquals}.
 * <p>
 * Also covers {@link MatchingConfigurator#decideConfigureAction} (compare-then-rewrite lot, 2026-09-22 23:50,
 * `docs/MATCHING.md` &sect;19): the pure rule choosing {@code GENERATE_FRESH}/{@code RESUME_SAME}/{@code
 * REWRITE}/{@code REFUSE} from {@code found}/{@code previewOnly}/{@code ownsExistingConfig}/{@code
 * storedMatchesDesired}. The structural comparison itself ({@code master.MatchingPolicyComparator}) and the
 * {@code apply_matching_config} payload builder ({@code master.MatchingConfigRewriteBuilder}) are tested in
 * their own package, {@code com.on.ebx.factory.master} - pure classes, no reason to duplicate their coverage
 * here.
 */
public final class MatchingConfiguratorTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchingConfiguratorTest.noMessages_saysSoAndKeepsRawAnswer();
		MatchingConfiguratorTest.oneMessage_codeAndHintSurfaced();
		MatchingConfiguratorTest.severalMessages_joinedNotTruncatedToOne();
		MatchingConfiguratorTest.priorAttempt_allThreeToolsSucceeded_resumes();
		MatchingConfiguratorTest.priorAttempt_patchFailed_doesNotResume();
		MatchingConfiguratorTest.priorAttempt_onlyFirstToolEverRan_doesNotResume();
		MatchingConfiguratorTest.priorAttempt_nullOrUnparseableDetail_doesNotResume();
		MatchingConfiguratorTest.anyPriorAttempt_refusedAfterEarlierSuccess_owned();
		MatchingConfiguratorTest.anyPriorAttempt_onlyRefusals_notOwned();
		MatchingConfiguratorTest.anyPriorAttempt_emptyHistory_notOwned();
		MatchingConfiguratorTest.ownsByDataModel_ownPublication_owned();
		MatchingConfiguratorTest.ownsByDataModel_otherProjectsPublication_notOwned();
		MatchingConfiguratorTest.ownsByDataModel_nullEitherSide_notOwned();
		MatchingConfiguratorTest.determineOwnership_noSignal_none();
		MatchingConfiguratorTest.determineOwnership_doneStepAlone_doneStep();
		MatchingConfiguratorTest.determineOwnership_priorAttemptAlone_priorAttempt();
		MatchingConfiguratorTest.determineOwnership_ownPublicationAlone_ownPublication();
		MatchingConfiguratorTest.determineOwnership_priorityOrder_doneStepWinsOverOthers();
		MatchingConfiguratorTest.decideConfigureAction_notFound_generatesFresh();
		MatchingConfiguratorTest.decideConfigureAction_previewOnly_generatesFresh_evenIfOwned();
		MatchingConfiguratorTest.decideConfigureAction_foundNotOwned_refuses();
		MatchingConfiguratorTest.decideConfigureAction_foundOwnedSame_resumes();
		MatchingConfiguratorTest.decideConfigureAction_foundOwnedDifferent_rewrites();
		MatchingConfiguratorTest.missingBusinessObjectRefusal_isCodedAndNamesTheRemedy();
		MatchingConfiguratorTest.relationChildTablePaths_mapsEachPromotedChildEntityToItsTable();
		MatchingConfiguratorTest.alignmentSkippedDetail_saysBusinessObject();
		MatchingConfiguratorTest.buildPendingSuspectNote_zeroOrNegative_null();
		MatchingConfiguratorTest.buildPendingSuspectNote_positive_wordsItDomainNeutral();
		MatchingConfiguratorTest.parentEntitiesByChild_childMapsToItsPromotedParent();
		MatchingConfiguratorTest.parentEntitiesByChild_selfReferenceExcluded();
		MatchingConfiguratorTest.parentEntitiesByChild_targetNotBuilt_excluded();
		MatchingConfiguratorTest.parentEntitiesByChild_duplicateEdgesToSameParent_listedOnce();
		MatchingConfiguratorTest.pendingSuspectNoteOnFailure_namesTheExceptionClass();
		System.out.println("MatchingConfiguratorTest OK");
	}

	// -------------------------------------------------------------------------- pendingSuspectNoteOnFailure (R11)

	/** Lot R11, 2026-09-27: the best-effort fallback note {@link #runInternal} records when {@code parentPendingSuspectNote} throws - never null, names the exception class. */
	private static void pendingSuspectNoteOnFailure_namesTheExceptionClass()
	{
		final String note = MatchingConfigurator.pendingSuspectNoteOnFailure(ClassCastException.class);
		MatchingConfiguratorTest.check("never null", note != null);
		MatchingConfiguratorTest.check("names the exception class", note.contains("ClassCastException"));
		MatchingConfiguratorTest.check("says pairs could not be counted", note.contains("could not be counted"));
	}

	// ----------------------------------------------------------------------------- native relation fields (lot W13)

	/**
	 * A plan that compares child fields on a table with no business object: the refusal is coded ({@code missing.businessObject}, a bundle sentence in both
	 * languages), names the table, the fields and the remedy (generate the master again) - never a silent fallback to the older placeholder route.
	 */
	private static void missingBusinessObjectRefusal_isCodedAndNamesTheRemedy()
	{
		final java.util.Set<String> fields = new java.util.LinkedHashSet<String>(Arrays.asList("/root/Adresse/city", "/root/Adresse/postalCode"));
		final EngineRefusal refusal = MatchingConfigurator.missingBusinessObjectRefusal("Personne", "/root/Personne", "Vol100k_Master", fields);
		MatchingConfiguratorTest.check("coded as engine.missing.businessObject: " + refusal.code(), "engine.missing.businessObject".equals(refusal.code()));
		MatchingConfiguratorTest.check("names the table and the fields", refusal.getMessage().contains("/root/Personne") && refusal.getMessage().contains("/root/Adresse/city"));
		MatchingConfiguratorTest.check("says what to do", refusal.getMessage().contains("Generate the master model again"));
		MatchingConfiguratorTest.check("English sentence from the bundle", refusal.sentence(java.util.Locale.ENGLISH).contains("declares no business object") && refusal.sentence(java.util.Locale.ENGLISH).contains("Personne"));
		MatchingConfiguratorTest.check("French sentence from the bundle", refusal.sentence(java.util.Locale.FRENCH).contains("aucun objet m"));
	}

	/** The child entities that point at the parent, each mapped to its master table (the input of MatchingPolicyTranslator.translate); a self-reference is no child. */
	private static void relationChildTablePaths_mapsEachPromotedChildEntityToItsTable()
	{
		final com.on.ebx.factory.master.MasterModel.Result model = com.on.ebx.factory.master.MasterModel.build(Arrays.asList(
			new com.on.ebx.factory.master.MasterModel.EntityInfo("Personne", "Personne", "ACCEPTED", Arrays.asList(
				new com.on.ebx.factory.master.MasterModel.AttributeInfo("sourceSystem", "s", "STRING", Integer.valueOf(40), true, null, "ACCEPTED"),
				new com.on.ebx.factory.master.MasterModel.AttributeInfo("sourceId", "s", "STRING", Integer.valueOf(255), true, null, "ACCEPTED"),
				new com.on.ebx.factory.master.MasterModel.AttributeInfo("parentId", "p", "STRING", Integer.valueOf(40), false, "Personne", "ACCEPTED")),
				java.util.Collections.<String>emptyList()),
			new com.on.ebx.factory.master.MasterModel.EntityInfo("Adresse", "Adresse", "ACCEPTED", Arrays.asList(
				new com.on.ebx.factory.master.MasterModel.AttributeInfo("sourceSystem", "s", "STRING", Integer.valueOf(40), true, null, "ACCEPTED"),
				new com.on.ebx.factory.master.MasterModel.AttributeInfo("sourceId", "s", "STRING", Integer.valueOf(255), true, null, "ACCEPTED"),
				new com.on.ebx.factory.master.MasterModel.AttributeInfo("personneId", "p", "STRING", Integer.valueOf(40), false, "Personne", "ACCEPTED")),
				java.util.Collections.<String>emptyList())));
		final Map<String, String> children = MatchingConfigurator.relationChildTablePaths(model, "Personne");
		MatchingConfiguratorTest.check("Adresse -> /root/Adresse, Personne (self-reference) is no child: " + children, children.size() == 1 && "/root/Adresse".equals(children.get("Adresse")));
		MatchingConfiguratorTest.check("a child has no children of its own", MatchingConfigurator.relationChildTablePaths(model, "Adresse").isEmpty());
	}

	/** The step detail of a business-object table says the alignment was skipped and why: {@code alignRelatedTables: {skipped: "business object"}}. */
	private static void alignmentSkippedDetail_saysBusinessObject()
	{
		final JsonNode detail = MatchingConfigurator.alignmentSkippedDetail();
		MatchingConfiguratorTest.check("skipped: business object: " + detail, "business object".equals(detail.path("skipped").asText()));
		MatchingConfiguratorTest.check("the decision that drives it: a business-object table is never aligned nor applied",
			!com.on.ebx.factory.master.BusinessObjectPlan.alignsRelatedTables(true) && !com.on.ebx.factory.master.BusinessObjectPlan.mayUseApplyMatchingConfig(true));
	}

	private static JsonNode node(final String pJson) throws Exception
	{
		return MatchingConfiguratorTest.MAPPER.readTree(pJson);
	}

	// -------------------------------------------------------------------------------- buildPendingSuspectNote (R6)

	private static void buildPendingSuspectNote_zeroOrNegative_null()
	{
		MatchingConfiguratorTest.check("zero pending: no note", MatchingConfigurator.buildPendingSuspectNote("Personne", "Adresse", 0) == null);
		MatchingConfiguratorTest.check("negative pending (defensive): no note", MatchingConfigurator.buildPendingSuspectNote("Personne", "Adresse", -1) == null);
	}

	private static void buildPendingSuspectNote_positive_wordsItDomainNeutral()
	{
		final String note = MatchingConfigurator.buildPendingSuspectNote("Personne", "Adresse", 3);
		MatchingConfiguratorTest.check("mentions the count", note.contains("3"));
		MatchingConfiguratorTest.check("mentions the parent entity name", note.contains("Personne"));
		MatchingConfiguratorTest.check("mentions the child entity name", note.contains("Adresse"));
		MatchingConfiguratorTest.check("says pairs await a decision", note.contains("await a decision"));
		MatchingConfiguratorTest.check("tells the reader to re-run matching on the child", note.contains("run matching on Adresse again"));
	}

	// -------------------------------------------------------------------------------- parentEntitiesByChild (R6)

	private static void parentEntitiesByChild_childMapsToItsPromotedParent()
	{
		final Map<String, List<String>> byChild = MatchingConfigurator.parentEntitiesByChild(Arrays.asList("Personne", "Adresse"),
			MatchingConfiguratorTest.edges(new String[] { "Adresse", "Personne" }));
		MatchingConfiguratorTest.check("Adresse maps to Personne", byChild.get("Adresse") != null && byChild.get("Adresse").contains("Personne"));
		MatchingConfiguratorTest.check("Personne has no parent entry", byChild.get("Personne") == null);
	}

	private static void parentEntitiesByChild_selfReferenceExcluded()
	{
		final Map<String, List<String>> byChild = MatchingConfigurator.parentEntitiesByChild(Arrays.asList("Account"),
			MatchingConfiguratorTest.edges(new String[] { "Account", "Account" }));
		MatchingConfiguratorTest.check("a self-referencing FK carries no parent-first ordering constraint", byChild.isEmpty());
	}

	private static void parentEntitiesByChild_targetNotBuilt_excluded()
	{
		final Map<String, List<String>> byChild = MatchingConfigurator.parentEntitiesByChild(Arrays.asList("Adresse"),
			MatchingConfiguratorTest.edges(new String[] { "Adresse", "Personne" }));
		MatchingConfiguratorTest.check("a target that is not itself a built/promoted entity is excluded", byChild.isEmpty());
	}

	private static void parentEntitiesByChild_duplicateEdgesToSameParent_listedOnce()
	{
		final Map<String, List<String>> byChild = MatchingConfigurator.parentEntitiesByChild(Arrays.asList("Personne", "Adresse"),
			MatchingConfiguratorTest.edges(new String[] { "Adresse", "Personne" }, new String[] { "Adresse", "Personne" }));
		MatchingConfiguratorTest.check("the same parent named twice is only listed once", byChild.get("Adresse").size() == 1);
	}

	/** {@code Arrays.asList(String[]...)} would flatten a single {@code String[]} into its own varargs array (a
	 * classic overload-resolution trap) - this helper builds the {@code List<String[]>} {@code parentEntitiesByChild}
	 * actually expects, unambiguously. */
	private static List<String[]> edges(final String[]... pEdges)
	{
		return Arrays.asList(pEdges);
	}

	// -------------------------------------------------------------------------------------------- decideConfigureAction

	/** compare-then-rewrite lot, 2026-09-22 23:50 (docs/MATCHING.md sec 19): the pure decision rule behind {@link MatchingConfigurator#configureOne}'s branching. */
	private static void decideConfigureAction_notFound_generatesFresh()
	{
		MatchingConfiguratorTest.check("nothing found at all: generate fresh, regardless of ownership/same",
			MatchingConfigurator.decideConfigureAction(false, false, false, false) == MatchingConfigurator.ConfigureDecision.GENERATE_FRESH);
		MatchingConfiguratorTest.check("nothing found, even if somehow owned: still generate fresh",
			MatchingConfigurator.decideConfigureAction(false, false, true, true) == MatchingConfigurator.ConfigureDecision.GENERATE_FRESH);
	}

	private static void decideConfigureAction_previewOnly_generatesFresh_evenIfOwned()
	{
		MatchingConfiguratorTest.check("found but preview-only: generate fresh (never a stranger's, never worth comparing)",
			MatchingConfigurator.decideConfigureAction(true, true, true, false) == MatchingConfigurator.ConfigureDecision.GENERATE_FRESH);
	}

	private static void decideConfigureAction_foundNotOwned_refuses()
	{
		MatchingConfiguratorTest.check("found, real, never owned by this project: refuse - the guard stays",
			MatchingConfigurator.decideConfigureAction(true, false, false, false) == MatchingConfigurator.ConfigureDecision.REFUSE);
	}

	private static void decideConfigureAction_foundOwnedSame_resumes()
	{
		MatchingConfiguratorTest.check("found, owned, matches the accepted plan: resume (validate/activate only)",
			MatchingConfigurator.decideConfigureAction(true, false, true, true) == MatchingConfigurator.ConfigureDecision.RESUME_SAME);
	}

	private static void decideConfigureAction_foundOwnedDifferent_rewrites()
	{
		MatchingConfiguratorTest.check("found, owned, differs from the accepted plan: rewrite in place",
			MatchingConfigurator.decideConfigureAction(true, false, true, false) == MatchingConfigurator.ConfigureDecision.REWRITE);
	}

	// -------------------------------------------------------------------------------------- describeValidationMessages

	/** The exact captured validate answer, 2026-09-22 08:58 (`docs/MATCHING.md`): {@code valid:true}, an empty {@code messages} array - never reached by a refusal in real life, but the "no messages" branch must not crash on it. */
	private static void noMessages_saysSoAndKeepsRawAnswer() throws Exception
	{
		final JsonNode answer = MatchingConfiguratorTest.parse("{\"success\":true,\"data\":{\"messages\":[],\"table\":\"/root/Company\",\"valid\":true}}");
		final String described = MatchingConfigurator.describeValidationMessages(answer);
		MatchingConfiguratorTest.check("no messages reported, and the raw answer is still shown", described.contains("no messages reported"));
	}

	private static void oneMessage_codeAndHintSurfaced() throws Exception
	{
		final JsonNode answer = MatchingConfiguratorTest.parse("{\"success\":true,\"data\":{\"valid\":false,\"messages\":["
			+ "{\"code\":\"FIELD_NOT_CLUSTERABLE\",\"path\":\"/vatNo\",\"severity\":\"ERROR\",\"message\":\"vatNo cannot be pre-clustered\","
			+ "\"hint\":\"drop it from clusteringFields\"}]}}");
		final String described = MatchingConfigurator.describeValidationMessages(answer);
		MatchingConfiguratorTest.check("code present", described.contains("FIELD_NOT_CLUSTERABLE"));
		MatchingConfiguratorTest.check("path present", described.contains("/vatNo"));
		MatchingConfiguratorTest.check("severity present", described.contains("ERROR"));
		MatchingConfiguratorTest.check("message present", described.contains("vatNo cannot be pre-clustered"));
		MatchingConfiguratorTest.check("hint present", described.contains("drop it from clusteringFields"));
	}

	private static void severalMessages_joinedNotTruncatedToOne() throws Exception
	{
		final JsonNode answer = MatchingConfiguratorTest.parse("{\"success\":true,\"data\":{\"valid\":false,\"messages\":["
			+ "{\"code\":\"FIRST_CODE\",\"severity\":\"ERROR\",\"message\":\"first problem\"},"
			+ "{\"code\":\"SECOND_CODE\",\"severity\":\"WARNING\",\"message\":\"second problem\"}]}}");
		final String described = MatchingConfigurator.describeValidationMessages(answer);
		MatchingConfiguratorTest.check("first message present", described.contains("FIRST_CODE") && described.contains("first problem"));
		MatchingConfiguratorTest.check("second message present too", described.contains("SECOND_CODE") && described.contains("second problem"));
	}

	// ---------------------------------------------------------------------------- priorAttemptCompletedGenerationAndLink

	/** Exactly {@link MatchingConfigurator#toolDetail}'s own shape - built by this class, never guessed. */
	private static String detailWith(final boolean pGenerateMatching, final boolean pGenerateMerge, final boolean pLink)
	{
		return "{\"generateMatchingPolicy\":{\"error\":false,\"result\":{\"success\":" + pGenerateMatching + "}},"
			+ "\"generateMergePolicy\":{\"error\":false,\"result\":{\"success\":" + pGenerateMerge + "}},"
			+ "\"linkMergePolicy\":{\"error\":false,\"result\":{\"success\":" + pLink + "}}}";
	}

	/** The exact live scenario, 2026-09-22 08:58: generate+generate+patch all succeeded, only validate failed later - a retry must resume, never re-generate. */
	private static void priorAttempt_allThreeToolsSucceeded_resumes()
	{
		MatchingConfiguratorTest.check("all three succeeded: resumes",
			MatchingConfigurator.priorAttemptCompletedGenerationAndLink(MatchingConfiguratorTest.detailWith(true, true, true)));
	}

	private static void priorAttempt_patchFailed_doesNotResume()
	{
		MatchingConfiguratorTest.check("patch_matching_config itself failed: never resumes",
			!MatchingConfigurator.priorAttemptCompletedGenerationAndLink(MatchingConfiguratorTest.detailWith(true, true, false)));
	}

	private static void priorAttempt_onlyFirstToolEverRan_doesNotResume()
	{
		MatchingConfiguratorTest.check("the attempt failed before generate_merge_policy/patch_matching_config ever ran: never resumes",
			!MatchingConfigurator.priorAttemptCompletedGenerationAndLink("{\"generateMatchingPolicy\":{\"error\":false,\"result\":{\"success\":true}}}"));
	}

	private static void priorAttempt_nullOrUnparseableDetail_doesNotResume()
	{
		MatchingConfiguratorTest.check("the step never ran at all: never resumes", !MatchingConfigurator.priorAttemptCompletedGenerationAndLink(null));
		MatchingConfiguratorTest.check("an unparseable detail never resumes, and never throws",
			!MatchingConfigurator.priorAttemptCompletedGenerationAndLink("not json at all {{{"));
	}

	// -------------------------------------------------------------------------------------------- lot R8 (2026-09-27)

	/** The live PersVol7/Personne shape (docs/MATCHING.md sec 39): an EARLIER attempt genuinely completed generate+generate+link, a LATER retry recorded nothing but its own refusal (no tool keys at all) - a refusal never itself satisfies {@link MatchingConfigurator#priorAttemptCompletedGenerationAndLink}, but scanning EVERY past attempt still finds the earlier success. */
	private static void anyPriorAttempt_refusedAfterEarlierSuccess_owned()
	{
		final List<String> history = Arrays.asList(MatchingConfiguratorTest.detailWith(true, true, true), "{}");
		MatchingConfiguratorTest.check("an earlier successful attempt anywhere in the history is enough, even after a later refusal-only retry",
			MatchingConfigurator.anyPriorAttemptCompletedGenerationAndLink(history));
	}

	private static void anyPriorAttempt_onlyRefusals_notOwned()
	{
		final List<String> history = Arrays.asList("{}", "{}");
		MatchingConfiguratorTest.check("nothing but refusal-only attempts: never owned by this signal",
			!MatchingConfigurator.anyPriorAttemptCompletedGenerationAndLink(history));
	}

	private static void anyPriorAttempt_emptyHistory_notOwned()
	{
		MatchingConfiguratorTest.check("the step never ran at all for this project/entity: never owned by this signal",
			!MatchingConfigurator.anyPriorAttemptCompletedGenerationAndLink(java.util.Collections.<String>emptyList()));
	}

	// -------------------------------------------------------------------------------------------------- ownsByDataModel

	private static void ownsByDataModel_ownPublication_owned()
	{
		MatchingConfiguratorTest.check("the config was found scoped to this project's own master publication: owned",
			MatchingConfigurator.ownsByDataModel("urn:ebx:publication:PersVol7_Master", "urn:ebx:publication:PersVol7_Master"));
	}

	private static void ownsByDataModel_otherProjectsPublication_notOwned()
	{
		MatchingConfiguratorTest.check("the config was found scoped to a DIFFERENT project's own publication: never owned by this signal",
			!MatchingConfigurator.ownsByDataModel("urn:ebx:publication:OtherProject_Master", "urn:ebx:publication:PersVol7_Master"));
	}

	private static void ownsByDataModel_nullEitherSide_notOwned()
	{
		MatchingConfiguratorTest.check("no dataModel was ever actually sent: never owned by this signal", !MatchingConfigurator.ownsByDataModel(null, "urn:ebx:publication:PersVol7_Master"));
		MatchingConfiguratorTest.check("no own publication known: never owned by this signal", !MatchingConfigurator.ownsByDataModel("urn:ebx:publication:PersVol7_Master", null));
	}

	// ------------------------------------------------------------------------------------------------ determineOwnership

	private static void determineOwnership_noSignal_none()
	{
		MatchingConfiguratorTest.check("none of the three signals holds: NONE",
			MatchingConfigurator.determineOwnership(false, false, false) == MatchingConfigurator.OwnershipRule.NONE);
	}

	private static void determineOwnership_doneStepAlone_doneStep()
	{
		MatchingConfiguratorTest.check("a genuinely DONE step alone: DONE_STEP",
			MatchingConfigurator.determineOwnership(true, false, false) == MatchingConfigurator.OwnershipRule.DONE_STEP);
	}

	private static void determineOwnership_priorAttemptAlone_priorAttempt()
	{
		MatchingConfiguratorTest.check("an earlier completed generate+generate+link alone: PRIOR_ATTEMPT",
			MatchingConfigurator.determineOwnership(false, true, false) == MatchingConfigurator.OwnershipRule.PRIOR_ATTEMPT);
	}

	private static void determineOwnership_ownPublicationAlone_ownPublication()
	{
		MatchingConfiguratorTest.check("only the own-publication signal (the PersVol7/Personne deadlock case): OWN_PUBLICATION",
			MatchingConfigurator.determineOwnership(false, false, true) == MatchingConfigurator.OwnershipRule.OWN_PUBLICATION);
	}

	private static void determineOwnership_priorityOrder_doneStepWinsOverOthers()
	{
		MatchingConfiguratorTest.check("when every signal holds at once, DONE_STEP is reported first",
			MatchingConfigurator.determineOwnership(true, true, true) == MatchingConfigurator.OwnershipRule.DONE_STEP);
		MatchingConfiguratorTest.check("PRIOR_ATTEMPT is reported over OWN_PUBLICATION when both hold but not DONE_STEP",
			MatchingConfigurator.determineOwnership(false, true, true) == MatchingConfigurator.OwnershipRule.PRIOR_ATTEMPT);
	}

	private static JsonNode parse(final String pJson) throws Exception
	{
		return MatchingConfiguratorTest.MAPPER.readTree(pJson);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
