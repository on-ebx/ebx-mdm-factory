package com.on.ebx.factory.review;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.ebx.ProposalReviews;
import com.on.ebx.factory.mappingmatrix.MatrixHtml;
import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.quality.CodeMap;

/**
 * JUnit-free: run the main, a failure throws. Staging last points, item 3 (2026-10-17): a code table with an observed code that has rows and no target code is not
 * accepted - live, M. and Mme "(to choose)", "140 rows of 140 have no target code yet", and Accept was offered (matrix, bulk accept, Proposal review). The gate is one
 * rule ({@link ReviewRules#canAcceptMappingStep}) reached through {@code ProposalReviews#decide}, the one door of every decision; the card disables Accept and says what to
 * fill; Edit and Reject stay.
 */
public final class CodeTableAcceptGateTest
{
	private static final String TABLE_INCOMPLETE = "{\"codes\":{\"M.\":\"\",\"Mme\":\"\"},\"targets\":[\"MR\",\"MRS\"],\"counts\":{\"M.\":77,\"Mme\":63}}";
	private static final String TABLE_PARTIAL = "{\"codes\":{\"M.\":\"MR\",\"Mme\":\"\"},\"targets\":[\"MR\",\"MRS\"],\"counts\":{\"M.\":77,\"Mme\":63}}";
	private static final String TABLE_COMPLETE = "{\"codes\":{\"M.\":\"MR\",\"Mme\":\"MRS\"},\"targets\":[\"MR\",\"MRS\"],\"counts\":{\"M.\":77,\"Mme\":63}}";
	private static final String TABLE_EMPTY_CODE_NO_ROWS = "{\"codes\":{\"M.\":\"MR\",\"Mlle\":\"\"},\"targets\":[\"MR\"],\"counts\":{\"M.\":77,\"Mlle\":0}}";
	private static final String TABLE_NO_COUNTS = "{\"codes\":{\"M.\":\"MR\",\"Mme\":\"\"},\"targets\":[\"MR\"]}";
	private static final String TABLE_CONFLICT = "{\"codes\":{\"M.\":\"MR\",\"m\":\"MRS\"},\"targets\":[\"MR\",\"MRS\"],\"counts\":{\"M.\":5,\"m\":5}}";

	public static void main(final String[] pArgs) throws IOException
	{
		theRule();
		theCodesOfTheTable();
		theReasonIsWordedInBothLanguages();
		theMatrixCardDisablesAcceptAndSaysWhatToFill();
		everyDecisionGoesThroughTheOneDoor();
		System.out.println("CodeTableAcceptGateTest OK");
	}

	private static void theRule()
	{
		final ReviewRules.Decision incomplete = ReviewRules.canAcceptMappingStep("TRANSCODE_REFERENCE", TABLE_INCOMPLETE);
		check("no target at all: refused", !incomplete.allowed && "review.refuse.codeTable".equals(incomplete.reasonKey));
		check("the English reason names the codes: " + incomplete.reason, "Choose a target code for M., Mme, then accept.".equals(incomplete.reason));
		check("one code without a target is enough", !ReviewRules.canAcceptMappingStep("TRANSCODE_REFERENCE", TABLE_PARTIAL).allowed
			&& ReviewRules.canAcceptMappingStep("TRANSCODE_REFERENCE", TABLE_PARTIAL).reason.contains("Mme") && !ReviewRules.canAcceptMappingStep("TRANSCODE_REFERENCE", TABLE_PARTIAL).reason.contains("M."));
		check("a complete table accepts as before", ReviewRules.canAcceptMappingStep("TRANSCODE_REFERENCE", TABLE_COMPLETE).allowed);
		check("a code with no row does not block", ReviewRules.canAcceptMappingStep("TRANSCODE_REFERENCE", TABLE_EMPTY_CODE_NO_ROWS).allowed);
		check("a table that records no counts is taken to have rows", !ReviewRules.canAcceptMappingStep("TRANSCODE_REFERENCE", TABLE_NO_COUNTS).allowed);
		check("two spellings going to different targets resolve nothing: refused", !ReviewRules.canAcceptMappingStep("TRANSCODE_REFERENCE", TABLE_CONFLICT).allowed);
		check("a reference-naming step is not a table", ReviewRules.canAcceptMappingStep("TRANSCODE_REFERENCE", "{\"reference\":\"TITLE\"}").allowed
			&& ReviewRules.canAcceptMappingStep("TRANSCODE_REFERENCE", null).allowed);
		check("another kind of step is never asked", ReviewRules.canAcceptMappingStep("TRIM", TABLE_INCOMPLETE).allowed && ReviewRules.canAcceptMappingStep(null, null).allowed);
		final StringBuilder many = new StringBuilder("{\"codes\":{");
		for (int i = 0; i < 9; i++)
		{
			many.append(i > 0 ? "," : "").append("\"c").append(i).append("\":\"\"");
		}
		many.append("},\"targets\":[\"A\"]}");
		check("a long list of codes is cut and counted: " + ReviewRules.canAcceptMappingStep("TRANSCODE_REFERENCE", many.toString()).reason,
			ReviewRules.canAcceptMappingStep("TRANSCODE_REFERENCE", many.toString()).reason.equals("Choose a target code for c0, c1, c2, c3, c4, c5 (+3), then accept."));
	}

	private static void theCodesOfTheTable()
	{
		final CodeMap map = CodeMap.parse(TABLE_PARTIAL);
		check("codesWithoutTarget", map.codesWithoutTarget().equals(Arrays.asList("Mme")));
		check("none when complete", CodeMap.parse(TABLE_COMPLETE).codesWithoutTarget().isEmpty());
		final Map<String, String> edits = new LinkedHashMap<String, String>();
		edits.put("Mme", "MRS");
		check("filling the target releases it (the Edit of the card)", map.withTargets(edits).codesWithoutTarget().isEmpty());
		edits.put("Mme", "");
		check("emptying a target blocks it again", !CodeMap.parse(TABLE_COMPLETE).withTargets(edits).codesWithoutTarget().isEmpty());
	}

	private static void theReasonIsWordedInBothLanguages()
	{
		final ReviewRules.Decision refused = ReviewRules.canAcceptMappingStep("TRANSCODE_REFERENCE", TABLE_INCOMPLETE);
		final ProposalReviews.DecisionResult result = new ProposalReviews.DecisionResult();
		result.ok = false;
		result.reason = refused.reason;
		result.reasonKey = refused.reasonKey;
		result.reasonArgs = refused.reasonArgs;
		check("English", "Choose a target code for M., Mme, then accept.".equals(result.reasonIn(Locale.ENGLISH)));
		check("French", "Choisissez un code cible pour M., Mme, puis acceptez.".equals(result.reasonIn(Locale.FRENCH)));
		check("no locale reads English", "Choose a target code for M., Mme, then accept.".equals(result.reasonIn(null)));
		final ProposalReviews.DecisionResult plain = new ProposalReviews.DecisionResult();
		plain.reason = "Already applied: cannot be changed any more.";
		check("a refusal with no code is shown as it is", plain.reason.equals(plain.reasonIn(Locale.FRENCH)));
	}

	private static MatrixModel.Project project(final MatrixModel.CodeList... pLists)
	{
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "title", "STRING", false, false, false);
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("a1", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "civilite", null, Integer.valueOf(90), "LLM", null, null, 0, null, null, "", "", null, Arrays.asList(pLists)));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "sap", "t1", "LOYALTY", "ACCEPTED", null, "", false, null, cells);
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Person", Arrays.asList(attr), Arrays.asList(feed));
		return new MatrixModel.Project("P", java.util.Collections.<MatrixModel.Source>emptyList(), Arrays.asList(entity));
	}

	private static com.fasterxml.jackson.databind.JsonNode listsOf(final MatrixModel.Project pProject, final Locale pLocale) throws IOException
	{
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		state.locale = pLocale;
		final java.util.regex.Matcher m = java.util.regex.Pattern.compile("data-cell=\"([^\"]*)\"").matcher(MatrixHtml.render(pProject, state));
		check("a cell is rendered", m.find());
		final String json = m.group(1).replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&amp;", "&");
		return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("codeLists");
	}

	private static void theMatrixCardDisablesAcceptAndSaysWhatToFill() throws IOException
	{
		final List<MatrixModel.CodeRow> gaps = Arrays.asList(new MatrixModel.CodeRow("M.", "", 77L), new MatrixModel.CodeRow("Mme", "", 63L), new MatrixModel.CodeRow("Mlle", "", 0L));
		final List<MatrixModel.CodeRow> full = Arrays.asList(new MatrixModel.CodeRow("M.", "MR", 77L), new MatrixModel.CodeRow("Mme", "MRS", 63L));
		final MatrixModel.CodeList incomplete = new MatrixModel.CodeList("s1", MatrixModel.PROPOSED, Integer.valueOf(59), "", Arrays.asList("MR", "MRS"), gaps, 140L, 140L);
		final MatrixModel.CodeList complete = new MatrixModel.CodeList("s2", MatrixModel.PROPOSED, Integer.valueOf(85), "", Arrays.asList("MR", "MRS"), full, 140L, 0L);
		check("the model derives the codes to fill from its rows (a code with no row is not one)", incomplete.missing.equals(Arrays.asList("M.", "Mme")) && incomplete.acceptBlocked()
			&& complete.missing.isEmpty() && !complete.acceptBlocked());
		final com.fasterxml.jackson.databind.JsonNode fr = listsOf(project(incomplete, complete), Locale.FRENCH);
		check("blocked and the sentence, in French", fr.get(0).path("blocked").asBoolean() && "Choisissez un code cible pour M., Mme, puis acceptez.".equals(fr.get(0).path("fill").asText())
			&& !fr.get(1).path("blocked").asBoolean() && fr.get(1).path("fill").asText().isEmpty());
		check("the sentence in English", "Choose a target code for M., Mme, then accept.".equals(listsOf(project(incomplete), Locale.ENGLISH).get(0).path("fill").asText()));
		final String script = MatrixHtml.script(Locale.ENGLISH);
		check("the script disables Accept on a blocked table, keeps Edit and Reject, and prints the sentence", script.contains("(l.blocked?' disabled=\"disabled\"") && script.contains("mdmmCodesFill")
			&& script.contains("mdmmCodesEdit(this)") && script.contains("data-act=\"REJECT_CODES\""));
	}

	private static String read(final String pRelative) throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "src/main/java")).resolve("com/on/ebx/factory");
		return new String(Files.readAllBytes(root.resolve(pRelative)), StandardCharsets.UTF_8);
	}

	/** Server side: one door. The matrix's ACCEPT_CODES, its bulk sweeps, the wizard's bulk job, the Proposal review and the REST route all call ProposalReviews.decide / decideRef. */
	private static void everyDecisionGoesThroughTheOneDoor() throws IOException
	{
		final String reviews = read("ebx/ProposalReviews.java");
		check("the accept check has a branch for a mapping step", reviews.contains("ReviewLine.MAPPING_STEP.equals(pNature)") && reviews.contains("ReviewRules.canAcceptMappingStep(UserView.string(pRow, FactoryPaths.MappingStep.KIND)"));
		check("it runs for ACCEPTED only (a reject stays possible)", reviews.contains("if (ReviewLine.ACCEPTED.equals(pTargetStatus))\n\t\t{\n\t\t\tfinal ReviewRules.Decision consistency = ProposalReviews.checkAccept("));
		check("the refusal keeps its code", reviews.contains("pOut.reasonKey = consistency.reasonKey;"));
		final String matrix = read("ebx/ui/MappingMatrixUserService.java");
		check("the matrix's Accept of a code table is the same decision", matrix.contains("\"ACCEPT_CODES\".equals(action)") && matrix.contains("this.decideOne(ReviewLine.MAPPING_STEP, rowId, ReviewLine.ACCEPTED, null);")
			&& matrix.contains("this.fail(result.reasonIn(this.locale));"));
		check("its bulk sweep of mapping steps counts a refusal as skipped", matrix.contains("pOutCounts[result.ok ? 0 : 1]++;"));
		final String bulk = read("ebx/StagingBulkDecideRun.java");
		check("the wizard's bulk job decides through ProposalReviews.decide and says how many code tables it left", bulk.contains("ProposalReviews.decide(pJobSession, pFactory, pNature, pRowId, pTargetStatus)")
			&& bulk.contains("\"review.refuse.codeTable\".equals(decided.reasonKey)") && bulk.contains("outcome.codeTablesSkipped = codeTables[0];"));
		check("the Proposal review words the refusal and its bulk uses decideRef", read("ebx/ui/ProposalReviewUserService.java").contains("result.reasonIn(this.locale)")
			&& read("ebx/ui/ProposalReviewUserService.java").contains("ProposalReviews.decideRef("));
		check("decideRef is the same door", reviews.contains("return ProposalReviews.decide(pSession, pFactory, pRef.nature, pRef.rowId, pTargetStatus);"));
		check("the REST decisions route answers the refusal", read("rest/FactoryResource.java").contains("ProposalReviews.decide(this.sessionContext.getSession(), dataset, nature, id, status);")
			&& read("rest/FactoryResource.java").contains("result.put(\"reason\", decided.reason);"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("CodeTableAcceptGateTest: " + pWhat);
		}
	}
}
