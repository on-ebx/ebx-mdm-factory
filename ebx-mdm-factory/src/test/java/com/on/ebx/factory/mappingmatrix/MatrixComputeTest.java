package com.on.ebx.factory.mappingmatrix;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.review.ReviewLine;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class MatrixComputeTest
{
	public static void main(final String[] pArgs)
	{
		MatrixComputeTest.effectiveStatusFallsBackToMissingOrNone();
		MatrixComputeTest.feedStatsCountsAndBulkCandidates();
		MatrixComputeTest.entityStatsSumsEveryFeed();
		MatrixComputeTest.rowPositionsGeneralizeToAnyCount();
		MatrixComputeTest.curvePathIsAHorizontalSCurve();
		MatrixComputeTest.buttonVisibilityFollowsStatus();
		MatrixComputeTest.cellAcceptActionNeverCascadesToTheEntitySilently();
		MatrixComputeTest.entityBulkCascadesInOrderAndSkipsTheTechnicalKey();
		MatrixComputeTest.entityBulkLeavesLowConfidenceRowsOut();
		MatrixComputeTest.feedBulkOnlyTouchesItsOwnFeedAndAttributes();
		MatrixComputeTest.countersIncludeUndecidedStructuralRows();
		MatrixComputeTest.chipPositionsDoNotCollideAcrossEntityCounts();
		MatrixComputeTest.chipPositionsSpreadAcrossSourcesOfTheSameEntity();
		MatrixComputeTest.attributeFlaggedWhenAnyCellIsFlagged();
		MatrixComputeTest.entityBulkSkipsAFlaggedFieldEntirelyAndFoldsInItsGuards();
		MatrixComputeTest.feedBulkAlsoSkipsFlaggedFields();
		MatrixComputeTest.guardStatsCountsByStatusAndNature();
		MatrixComputeTest.planProjectBulkConcatenatesEveryEntity();
		MatrixComputeTest.bulkNeverSweepsInAnOrderUnknownSplit();
		MatrixComputeTest.readyToLoadIsFalseUntilNothingIsProposedOrFlagged();
		MatrixComputeTest.duplicateAndEmptyEverywhereHelpers();
		MatrixComputeTest.aMatchingKeyGoesWithTheSweepButAComputedFieldDoesNot();
		MatrixComputeTest.aMatchingKeyFollowsItsSourceFieldInTheSweep();
		MatrixComputeTest.stillToDecideListIsExactlyTheCount();
		MatrixComputeTest.stillToDecideNamesWhyEachLeftoverWasNotTaken();
		MatrixComputeTest.aSetAsideGuardIsNotCountedAndAServiceIsNeverAccepted();
		MatrixComputeTest.nextItemAfterADecision();
		MatrixComputeTest.onlyWhatIsLeftFilterKeepsTheRightRows();
		MatrixComputeTest.perTabCountsAndKeyOwnership();
		MatrixComputeTest.acceptAsShownTakesWhatWasShownWhateverItsConfidenceAndLeavesTheFlagged();
		System.out.println("MatrixComputeTest OK");
	}

	private static Guard guard(final String pId, final String pNature, final String pAttributeId, final String pStatus, final Integer pConfidence)
	{
		return new Guard(pId, pNature, "e1", pAttributeId, "isValid", "", pNature.equals(ReviewLine.QUALITY) ? "WARN" : null, null, pStatus, "LLM", pConfidence,
			"because", null, null, null);
	}

	/** Delta 2: a field is "flagged" as soon as ANY of its cells (any source) carries an attention flag. */
	private static void attributeFlaggedWhenAnyCellIsFlagged()
	{
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("a1", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "COL", null, Integer.valueOf(90), "LLM", null, null, 0, null, null, "EMPTY_COLUMN",
			"empty in this source (0 / 165)", null));
		final MatrixModel.Feed flaggedFeed = new MatrixModel.Feed("f1", "s1", "t1", "Table", "ACCEPTED", null, "", false, null, cells);
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "mobilePhone", "STRING", false, false, false);
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Person", Arrays.asList(attr), Arrays.asList(flaggedFeed));
		MatrixComputeTest.check("a cell carrying an attentionFlag makes the whole field flagged", MatrixCompute.attributeFlagged(entity, "a1"));
		MatrixComputeTest.check("a field with no flagged cell at all is not flagged", !MatrixCompute.attributeFlagged(entity, "aOther"));
	}

	/**
	 * Delta 4: "the bulk sweeps must skip flagged fields and include the guards ... of non-flagged fields
	 * after their field". Two fields: one flagged (never touched, not even its own guard), one clean (its
	 * mapping AND its own guard both planned, right after the attribute).
	 */
	private static void entityBulkSkipsAFlaggedFieldEntirelyAndFoldsInItsGuards()
	{
		final MatrixModel.Attr flagged = new MatrixModel.Attr("aFlag", "mobilePhone", "STRING", false, false, false, "PROPOSED", Integer.valueOf(95));
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("aFlag", new MatrixModel.Cell(MatrixModel.PROPOSED, "mFlag", "COL1", null, Integer.valueOf(95), "LLM", null, null, 0, null, null,
			"EMPTY_COLUMN", "empty", null));
		cells.put("aClean", new MatrixModel.Cell(MatrixModel.PROPOSED, "mClean", "COL2", null, Integer.valueOf(95), "LLM", null, null, 0, null, null));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "s1", "t1", "Table", "ACCEPTED", null, "", false, null, cells, Integer.valueOf(90), "");
		final Guard cleanGuard = MatrixComputeTest.guard("g1", ReviewLine.QUALITY, "aClean", ReviewLine.PROPOSED, Integer.valueOf(90));
		final MatrixModel.Attr cleanWithGuard =
			new MatrixModel.Attr("aClean", "email", "STRING", false, false, false, "PROPOSED", Integer.valueOf(95), Arrays.asList(cleanGuard));
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Person", Arrays.asList(flagged, cleanWithGuard), Arrays.asList(feed), "ACCEPTED",
			Integer.valueOf(100));

		final MatrixCompute.BulkPlan plan = MatrixCompute.planEntityBulk(entity, 60);
		MatrixComputeTest.check("the flagged attribute is never planned", !MatrixComputeTest.containsId(plan, "aFlag"));
		MatrixComputeTest.check("its mapping is never planned either", !MatrixComputeTest.containsId(plan, "mFlag"));
		MatrixComputeTest.check("the clean attribute IS planned", MatrixComputeTest.containsId(plan, "aClean"));
		MatrixComputeTest.check("its own guard is planned too", MatrixComputeTest.containsId(plan, "g1"));
		MatrixComputeTest.check("its mapping is planned", MatrixComputeTest.containsId(plan, "mClean"));
		MatrixComputeTest.check("the guard is planned right after its field, before the feed/mapping rows",
			MatrixComputeTest.indexOfId(plan, "g1") < MatrixComputeTest.indexOfId(plan, "mClean"));
	}

	/**
	 * Vol100kN (2026-10-12): the mapping of a column whose order of parts cannot be inferred, through a splitting regex, went
	 * through "accept all". Flagged ORDER_UNKNOWN_SPLIT it is skipped by the bulk accept like any other flagged field, while its
	 * clean neighbour (a full-value attribute fed whole) is planned.
	 */
	private static void bulkNeverSweepsInAnOrderUnknownSplit()
	{
		final MatrixModel.Attr split = new MatrixModel.Attr("aLast", "lastName", "STRING", false, false, false, "PROPOSED", Integer.valueOf(95));
		final MatrixModel.Attr whole = new MatrixModel.Attr("aFull", "fullName", "STRING", false, false, false, "PROPOSED", Integer.valueOf(95));
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("aLast", new MatrixModel.Cell(MatrixModel.PROPOSED, "mLast", "FULL_NAME", null, Integer.valueOf(95), "LLM", null, null, 0, null, null,
			"ORDER_UNKNOWN_SPLIT", "this rule splits a column whose order of parts cannot be inferred (44 of 48 sampled values): keep the full value and compare it with an order-independent key", null));
		cells.put("aFull", new MatrixModel.Cell(MatrixModel.PROPOSED, "mFull", "FULL_NAME", null, Integer.valueOf(95), "LLM", null, null, 0, null, null));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "s1", "t1", "MEMBER", "ACCEPTED", null, "", false, null, cells, Integer.valueOf(90), "");
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Person", Arrays.asList(split, whole), Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100));
		MatrixComputeTest.check("the splitting mapping makes its field flagged", MatrixCompute.attributeFlagged(entity, "aLast"));
		final MatrixCompute.BulkPlan plan = MatrixCompute.planEntityBulk(entity, 60);
		MatrixComputeTest.check("the flagged split is not swept in, field or mapping", !MatrixComputeTest.containsId(plan, "aLast") && !MatrixComputeTest.containsId(plan, "mLast"));
		MatrixComputeTest.check("the full-value mapping is", MatrixComputeTest.containsId(plan, "mFull"));
	}

	private static void feedBulkAlsoSkipsFlaggedFields()
	{
		final MatrixModel.Attr flagged = new MatrixModel.Attr("aFlag", "mobilePhone", "STRING", false, false, false, "PROPOSED", Integer.valueOf(95));
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("aFlag", new MatrixModel.Cell(MatrixModel.PROPOSED, "mFlag", "COL1", null, Integer.valueOf(95), "LLM", null, null, 0, null, null, "DUPLICATE_FIELD",
			"duplicate field", null));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "s1", "t1", "Table", "PROPOSED", null, "", false, null, cells, Integer.valueOf(90), "");
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Person", Arrays.asList(flagged), Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100));

		final MatrixCompute.BulkPlan plan = MatrixCompute.planFeedBulk(entity, feed, 60);
		MatrixComputeTest.check("only the feed itself is planned - its one attribute/mapping are flagged", plan.toDecide.size() == 1);
		MatrixComputeTest.check("the feed decision itself", ReviewLine.FEED.equals(plan.toDecide.get(0).nature));
	}

	private static void guardStatsCountsByStatusAndNature()
	{
		final List<Guard> guards = Arrays.asList(MatrixComputeTest.guard("g1", ReviewLine.QUALITY, "a1", ReviewLine.ACCEPTED, Integer.valueOf(90)),
			MatrixComputeTest.guard("g2", ReviewLine.QUALITY, "a1", ReviewLine.PROPOSED, Integer.valueOf(80)),
			MatrixComputeTest.guard("g3", ReviewLine.SCRIPT_CONSTRAINT, "a2", ReviewLine.PROPOSED, Integer.valueOf(70)),
			MatrixComputeTest.guard("g4", ReviewLine.EXTERNAL_SERVICE, "a2", ReviewLine.REJECTED, Integer.valueOf(60)));
		final MatrixCompute.GuardStats stats = MatrixCompute.guardStats(guards);
		MatrixComputeTest.check("one accepted", stats.accepted == 1);
		MatrixComputeTest.check("two proposed", stats.proposed == 2);
		MatrixComputeTest.check("one rejected", stats.rejected == 1);
		MatrixComputeTest.check("two rules (QUALITY: g1, g2)", stats.rules == 2);
		MatrixComputeTest.check("two checks (everything else: g3, g4)", stats.checks == 2);
		MatrixComputeTest.check("QUALITY has a severity control", MatrixCompute.guardHasSeverityControl(guards.get(0)));
		MatrixComputeTest.check("EXTERNAL_SERVICE has none", !MatrixCompute.guardHasSeverityControl(guards.get(3)));
	}

	private static void planProjectBulkConcatenatesEveryEntity()
	{
		final MatrixModel.Attr a1 = new MatrixModel.Attr("a1", "nom", "STRING", true, false, false, "PROPOSED", Integer.valueOf(90));
		final MatrixModel.Attr a2 = new MatrixModel.Attr("a2", "prenom", "STRING", false, false, false, "PROPOSED", Integer.valueOf(90));
		final MatrixModel.Entity e1 = new MatrixModel.Entity("e1", "Person", Arrays.asList(a1), java.util.Collections.<MatrixModel.Feed>emptyList(), "ACCEPTED",
			Integer.valueOf(100));
		final MatrixModel.Entity e2 = new MatrixModel.Entity("e2", "Address", Arrays.asList(a2), java.util.Collections.<MatrixModel.Feed>emptyList(), "ACCEPTED",
			Integer.valueOf(100));
		final MatrixModel.Project project = new MatrixModel.Project("Personnes", java.util.Collections.<MatrixModel.Source>emptyList(), Arrays.asList(e1, e2));
		final MatrixCompute.BulkPlan plan = MatrixCompute.planProjectBulk(project, 60);
		MatrixComputeTest.check("both entities' own attributes are planned", MatrixComputeTest.containsId(plan, "a1") && MatrixComputeTest.containsId(plan, "a2"));
	}

	private static void readyToLoadIsFalseUntilNothingIsProposedOrFlagged()
	{
		final MatrixModel.Attr pending = new MatrixModel.Attr("a1", "nom", "STRING", true, false, false, "PROPOSED", Integer.valueOf(90));
		final MatrixModel.Entity pendingEntity =
			new MatrixModel.Entity("e1", "Person", Arrays.asList(pending), java.util.Collections.<MatrixModel.Feed>emptyList(), "ACCEPTED", Integer.valueOf(100));
		final MatrixModel.Project pendingProject =
			new MatrixModel.Project("Personnes", java.util.Collections.<MatrixModel.Source>emptyList(), Arrays.asList(pendingEntity));
		MatrixComputeTest.check("not ready while an attribute is still PROPOSED", !MatrixCompute.readyToLoad(pendingProject, 60));
		// fix/wizard-live-wave1 (2026-09-24, live bug: the footer's "Load the staging" stayed disabled with no
		// reason shown): itemsStillToDecide is the EXACT count readyToLoad now delegates to - must be > 0
		// whenever readyToLoad is false, and agree with it (0 <=> ready) in every case.
		MatrixComputeTest.check("itemsStillToDecide is positive while readyToLoad is false", MatrixCompute.itemsStillToDecide(pendingProject, 60) > 0);

		final MatrixModel.Attr done = new MatrixModel.Attr("a1", "nom", "STRING", true, false, false, "ACCEPTED", Integer.valueOf(90));
		final MatrixModel.Entity doneEntity =
			new MatrixModel.Entity("e1", "Person", Arrays.asList(done), java.util.Collections.<MatrixModel.Feed>emptyList(), "ACCEPTED", Integer.valueOf(100));
		final MatrixModel.Project doneProject = new MatrixModel.Project("Personnes", java.util.Collections.<MatrixModel.Source>emptyList(), Arrays.asList(doneEntity));
		MatrixComputeTest.check("ready once nothing is left PROPOSED and nothing is flagged", MatrixCompute.readyToLoad(doneProject, 60));
		MatrixComputeTest.check("itemsStillToDecide is exactly 0 once ready", MatrixCompute.itemsStillToDecide(doneProject, 60) == 0);
	}

	private static void duplicateAndEmptyEverywhereHelpers()
	{
		final MatrixModel.Attr empty = new MatrixModel.Attr("aEmpty", "mobilePhone", "STRING", false, false, false);
		final Map<String, MatrixModel.Cell> cellsSf = new LinkedHashMap<String, MatrixModel.Cell>();
		cellsSf.put("aEmpty", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "MobilePhone", null, Integer.valueOf(90), "LLM", null, null, 0, null, null,
			"EMPTY_COLUMN", "empty", null));
		final Map<String, MatrixModel.Cell> cellsSap = new LinkedHashMap<String, MatrixModel.Cell>();
		cellsSap.put("aEmpty", new MatrixModel.Cell(MatrixModel.PROPOSED, "m2", "MobilePhoneNumber", null, Integer.valueOf(90), "LLM", null, null, 0, null, null,
			"EMPTY_COLUMN", "empty", null));
		final MatrixModel.Feed feedSf = new MatrixModel.Feed("f1", "sf", "t1", "Table", "ACCEPTED", null, "", false, null, cellsSf);
		final MatrixModel.Feed feedSap = new MatrixModel.Feed("f2", "sap", "t2", "Table", "ACCEPTED", null, "", false, null, cellsSap);
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Person", Arrays.asList(empty), Arrays.asList(feedSf, feedSap));
		MatrixComputeTest.check("empty in every source that has a cell for it -> eligible for 'Remove the field'",
			MatrixCompute.attributeEmptyEverywhere(entity, empty));

		final MatrixModel.Attr dup = new MatrixModel.Attr("aDup", "streetName", "STRING", false, false, false);
		final Map<String, MatrixModel.Cell> cellsDup = new LinkedHashMap<String, MatrixModel.Cell>();
		cellsDup.put("aDup", new MatrixModel.Cell(MatrixModel.PROPOSED, "m3", "StreetName", null, Integer.valueOf(90), "LLM", null, null, 0, null, null,
			"DUPLICATE_FIELD", "duplicate field", null));
		final MatrixModel.Feed feedDup = new MatrixModel.Feed("f3", "sap", "t3", "Table", "ACCEPTED", null, "", false, null, cellsDup);
		final MatrixModel.Entity entityDup = new MatrixModel.Entity("e2", "Address", Arrays.asList(dup), Arrays.asList(feedDup));
		final List<String> dupIds = MatrixCompute.duplicateFieldAttributeIds(entityDup);
		MatrixComputeTest.check("the duplicate-flagged attribute is listed once", dupIds.size() == 1 && "aDup".equals(dupIds.get(0)));
	}

	private static int indexOfId(final MatrixCompute.BulkPlan pPlan, final String pId)
	{
		for (int i = 0; i < pPlan.toDecide.size(); i++)
		{
			if (pId.equals(pPlan.toDecide.get(i).id))
			{
				return i;
			}
		}
		return -1;
	}

	private static void effectiveStatusFallsBackToMissingOrNone()
	{
		final MatrixModel.Attr mandatory = new MatrixModel.Attr("a1", "codePostal", "STRING", true, false, false);
		final MatrixModel.Attr optional = new MatrixModel.Attr("a2", "fonction", "STRING", false, false, false);
		final MatrixModel.Feed emptyFeed = new MatrixModel.Feed("f1", "s1", "t1", "Table", "ACCEPTED", null, "", false, null, null);
		MatrixComputeTest.check("a mandatory attribute with no cell reads MISSING", MatrixModel.MISSING.equals(MatrixCompute.effectiveStatus(mandatory, emptyFeed)));
		MatrixComputeTest.check("an optional attribute with no cell reads NONE", MatrixModel.NONE.equals(MatrixCompute.effectiveStatus(optional, emptyFeed)));

		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("a1", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "Col", null, Integer.valueOf(80), "LLM", null, null, 0, null, null));
		final MatrixModel.Feed withCell = new MatrixModel.Feed("f1", "s1", "t1", "Table", "ACCEPTED", null, "", false, null, cells);
		MatrixComputeTest.check("a cell's own status wins over the mandatory fallback", MatrixModel.PROPOSED.equals(MatrixCompute.effectiveStatus(mandatory, withCell)));
	}

	private static void feedStatsCountsAndBulkCandidates()
	{
		final MatrixModel.Attr a1 = new MatrixModel.Attr("a1", "nom", "STRING", true, false, false);
		final MatrixModel.Attr a2 = new MatrixModel.Attr("a2", "prenom", "STRING", false, false, false);
		final MatrixModel.Attr a3 = new MatrixModel.Attr("a3", "email", "STRING", false, false, false);
		final List<MatrixModel.Attr> attrs = Arrays.asList(a1, a2, a3);

		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("a1", new MatrixModel.Cell(MatrixModel.ACCEPTED, "m1", "LASTNAME", null, Integer.valueOf(100), "RULE", null, null, 0, null, null));
		cells.put("a2", new MatrixModel.Cell(MatrixModel.PROPOSED, "m2", "FIRSTNAME", null, Integer.valueOf(75), "LLM", null, null, 0, null, null));
		// a3 has no cell at all and is optional -> NONE, never counted as missing or proposed
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "s1", "t1", "Table", "ACCEPTED", null, "", false, null, cells);

		final MatrixCompute.FeedStats stats = MatrixCompute.feedStats(attrs, feed, 60);
		MatrixComputeTest.check("one accepted", stats.accepted == 1);
		MatrixComputeTest.check("one proposed", stats.proposed == 1);
		MatrixComputeTest.check("nothing rejected", stats.rejected == 0);
		MatrixComputeTest.check("nothing missing (a3 is optional and NONE, not MISSING)", stats.missing == 0);
		MatrixComputeTest.check("the 75% proposal is a bulk-accept candidate at threshold 60",
			stats.toAcceptMappingIds.size() == 1 && "m2".equals(stats.toAcceptMappingIds.get(0)));

		final MatrixCompute.FeedStats strict = MatrixCompute.feedStats(attrs, feed, 80);
		MatrixComputeTest.check("the same 75% proposal is NOT a candidate at threshold 80", strict.toAcceptMappingIds.isEmpty());
	}

	private static void entityStatsSumsEveryFeed()
	{
		final MatrixModel.Attr mandatory = new MatrixModel.Attr("a1", "codePostal", "STRING", true, false, false);
		final List<MatrixModel.Attr> attrs = Arrays.asList(mandatory);

		final Map<String, MatrixModel.Cell> rejectedMandatory = new LinkedHashMap<String, MatrixModel.Cell>();
		rejectedMandatory.put("a1", new MatrixModel.Cell(MatrixModel.REJECTED, "m1", "ZIP", null, Integer.valueOf(50), "LLM", null, "bad format", 0, null, null));
		final MatrixModel.Feed feedA = new MatrixModel.Feed("f1", "s1", "t1", "TableA", "ACCEPTED", null, "", false, null, rejectedMandatory);
		final MatrixModel.Feed feedB = new MatrixModel.Feed("f2", "s2", "t2", "TableB", "ACCEPTED", null, "", false, null, null); // no cell at all -> MISSING too

		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Adresse", attrs, Arrays.asList(feedA, feedB));
		final MatrixCompute.FeedStats stats = MatrixCompute.entityStats(entity, 60);
		MatrixComputeTest.check("a REJECTED mandatory mapping counts as missing", stats.missing >= 1);
		MatrixComputeTest.check("an uncovered mandatory attribute on the other feed also counts as missing", stats.missing == 2);
		MatrixComputeTest.check("one rejected mapping counted", stats.rejected == 1);

		final Map<String, MatrixCompute.FeedStats> bySource = MatrixCompute.statsBySource(entity, 60);
		MatrixComputeTest.check("one entry per distinct source", bySource.size() == 2);
		MatrixComputeTest.check("source s1 aggregated its own feed", bySource.get("s1").missing == 1);
	}

	/** docs/MAPPING-MATRIX.md: the overview layout must work for 1 to ~8 sources and 1 to ~6 entities, never hardcoded for 5. */
	private static void rowPositionsGeneralizeToAnyCount()
	{
		for (final int count : new int[] { 1, 5, 8 })
		{
			final double[] positions = MatrixCompute.rowPositions(count, 20, 44);
			MatrixComputeTest.check("rowPositions(" + count + ") returns exactly " + count + " positions", positions.length == count);
			for (int i = 0; i < positions.length - 1; i++)
			{
				MatrixComputeTest.check("row " + i + " is strictly above row " + (i + 1) + " (n=" + count + ")", positions[i] < positions[i + 1]);
			}
		}
		MatrixComputeTest.check("rowPositions(0) returns an empty array, never a crash", MatrixCompute.rowPositions(0, 0, 10).length == 0);
	}

	private static void curvePathIsAHorizontalSCurve()
	{
		final String path = MatrixCompute.curvePath(230, 44, 900, 196);
		MatrixComputeTest.check("path starts with the source point", path.startsWith("M 230 44"));
		MatrixComputeTest.check("path ends at the entity point", path.endsWith("900 196"));
		MatrixComputeTest.check("path uses a cubic Bezier command", path.contains(" C "));
		final MatrixCompute.Point mid = MatrixCompute.curveChipPoint(230, 44, 900, 196, 0.5);
		MatrixComputeTest.check("the chip at t=0.5 lands strictly between the two x positions", mid.x > 230 && mid.x < 900);
	}


	private static void buttonVisibilityFollowsStatus()
	{
		MatrixComputeTest.check("toolbar shows entity accept/reject only when PROPOSED",
			MatrixCompute.canDecideEntity("PROPOSED") && !MatrixCompute.canDecideEntity("ACCEPTED") && !MatrixCompute.canDecideEntity("REJECTED")
				&& !MatrixCompute.canDecideEntity("APPLIED") && !MatrixCompute.canDecideEntity(""));

		final MatrixModel.Attr proposedAttr = new MatrixModel.Attr("a1", "nom", "STRING", true, false, false, "PROPOSED", Integer.valueOf(80));
		final MatrixModel.Attr acceptedAttr = new MatrixModel.Attr("a2", "email", "STRING", false, false, false, "ACCEPTED", Integer.valueOf(90));
		final MatrixModel.Attr sourceKeyAttr = new MatrixModel.Attr("a3", "sourceId", "TXT", false, false, true, "PROPOSED", null);
		MatrixComputeTest.check("row header shows accept/reject for a PROPOSED, non-key attribute", MatrixCompute.canDecideAttribute(proposedAttr));
		MatrixComputeTest.check("row header hides it once accepted", !MatrixCompute.canDecideAttribute(acceptedAttr));
		MatrixComputeTest.check("the technical source-key row never gets its own decision, even when PROPOSED", !MatrixCompute.canDecideAttribute(sourceKeyAttr));
		// demoBala, 2026-10-21: Adresse.personne, the structural link to the parent, follows its entity like the technical key
		final MatrixModel.Attr parentLinkAttr = new MatrixModel.Attr("a4", "personne", "STRING", false, true, false, "PROPOSED", Integer.valueOf(99));
		MatrixComputeTest.check("the parent-link row never gets its own decision, even when PROPOSED", !MatrixCompute.canDecideAttribute(parentLinkAttr));
		// and the queue never lists it, nor counts it as proposed: it would sit there with no button to clear it
		final MatrixModel.Entity adresse = new MatrixModel.Entity("e9", "Adresse", Arrays.asList(parentLinkAttr), new java.util.ArrayList<MatrixModel.Feed>(), "ACCEPTED",
			Integer.valueOf(99));
		final MatrixModel.Project parentLinkProject = new MatrixModel.Project("P", new java.util.ArrayList<MatrixModel.Source>(), Arrays.asList(adresse));
		MatrixComputeTest.check("a PROPOSED parent link is not left to decide", MatrixCompute.stillToDecide(parentLinkProject, 60).isEmpty());

		final MatrixModel.Feed proposedFeed = new MatrixModel.Feed("f1", "s1", "t1", "Table", "PROPOSED", null, "", false, null, null);
		final MatrixModel.Feed acceptedFeed = new MatrixModel.Feed("f2", "s1", "t1", "Table", "ACCEPTED", null, "", false, null, null);
		MatrixComputeTest.check("column header shows accept/reject for a PROPOSED feed", MatrixCompute.canDecideFeed(proposedFeed));
		MatrixComputeTest.check("column header hides it once accepted", !MatrixCompute.canDecideFeed(acceptedFeed));
	}

	/** Coordinator review, 2026-09-23: "if the ENTITY itself is not accepted, show the refusal clearly ... do not silently cascade to the entity". */
	private static void cellAcceptActionNeverCascadesToTheEntitySilently()
	{
		MatrixComputeTest.check("entity not accepted -> ENTITY_NOT_ACCEPTED, whatever the attribute's own status",
			MatrixCompute.cellAcceptAction("PROPOSED", "ACCEPTED") == MatrixCompute.CellAcceptAction.ENTITY_NOT_ACCEPTED);
		MatrixComputeTest.check("entity rejected also blocks the cell - only accepted-like clears it",
			MatrixCompute.cellAcceptAction("REJECTED", "ACCEPTED") == MatrixCompute.CellAcceptAction.ENTITY_NOT_ACCEPTED);
		MatrixComputeTest.check("entity accepted, attribute still proposed -> two-step accept",
			MatrixCompute.cellAcceptAction("ACCEPTED", "PROPOSED") == MatrixCompute.CellAcceptAction.ACCEPT_ATTRIBUTE_THEN_MAPPING);
		MatrixComputeTest.check("entity APPLIED counts as accepted-like too",
			MatrixCompute.cellAcceptAction("APPLIED", "PROPOSED") == MatrixCompute.CellAcceptAction.ACCEPT_ATTRIBUTE_THEN_MAPPING);
		MatrixComputeTest.check("both accepted-like -> ordinary mapping accept",
			MatrixCompute.cellAcceptAction("ACCEPTED", "ACCEPTED") == MatrixCompute.CellAcceptAction.ACCEPT_MAPPING_ONLY);
		MatrixComputeTest.check("both APPLIED -> still an ordinary mapping accept",
			MatrixCompute.cellAcceptAction("APPLIED", "APPLIED") == MatrixCompute.CellAcceptAction.ACCEPT_MAPPING_ONLY);
	}

	/** Task brief: "entity, then non-key attributes, then feeds, then mappings". */
	private static void entityBulkCascadesInOrderAndSkipsTheTechnicalKey()
	{
		final MatrixModel.Attr sourceKey = new MatrixModel.Attr("aKey", "sourceId", "TXT", false, false, true, "PROPOSED", Integer.valueOf(100));
		final MatrixModel.Attr nom = new MatrixModel.Attr("aNom", "nom", "STRING", true, false, false, "PROPOSED", Integer.valueOf(90));
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("aNom", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "NOM", null, Integer.valueOf(95), "RULE", null, null, 0, null, null));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "s1", "t1", "Table", "PROPOSED", null, "", false, null, cells, Integer.valueOf(90), "");
		final MatrixModel.Entity entity =
			new MatrixModel.Entity("e1", "Personne", Arrays.asList(sourceKey, nom), Arrays.asList(feed), "PROPOSED", Integer.valueOf(85));

		final MatrixCompute.BulkPlan plan = MatrixCompute.planEntityBulk(entity, 60);
		MatrixComputeTest.check("exactly 4 rows planned: entity, attribute, feed, mapping - the source key is skipped", plan.toDecide.size() == 4);
		MatrixComputeTest.check("entity decided first", ReviewLine.ENTITY.equals(plan.toDecide.get(0).nature) && "e1".equals(plan.toDecide.get(0).id));
		MatrixComputeTest.check("then the non-key attribute", ReviewLine.ATTRIBUTE.equals(plan.toDecide.get(1).nature) && "aNom".equals(plan.toDecide.get(1).id));
		MatrixComputeTest.check("then the feed", ReviewLine.FEED.equals(plan.toDecide.get(2).nature) && "f1".equals(plan.toDecide.get(2).id));
		MatrixComputeTest.check("then the mapping, last", ReviewLine.MAPPING.equals(plan.toDecide.get(3).nature) && "m1".equals(plan.toDecide.get(3).id));
	}

	/**
	 * fix/matrix-derived-key-rows (2026-09-27), then the matrix review queue lot (2026-09-30): a derived attribute
	 * reads DERIVED in every feed whatever its cells and counts as ONE item to decide while PROPOSED - so "Load the
	 * staging" waits for it exactly as the wizard's own gate does. A matching key (FOLD_KEY) now goes with the entity
	 * and feed sweeps under the usual threshold (accepting it only adds a technical column, forgetting it loses
	 * matches); a script-computed field (SCRIPT) and a valid-value field never do.
	 */
	private static void aMatchingKeyGoesWithTheSweepButAComputedFieldDoesNot()
	{
		final MatrixModel.Attr nom = new MatrixModel.Attr("aNom", "nom", "STRING", false, false, false, "ACCEPTED", Integer.valueOf(90));
		final MatrixModel.Attr key = new MatrixModel.Attr("aKey", "nomKey", "STRING", false, false, false, "PROPOSED", Integer.valueOf(100), null, "FOLD_KEY", "nom");
		final MatrixModel.Attr script = new MatrixModel.Attr("aE164", "nomE164", "STRING", false, false, false, "PROPOSED", Integer.valueOf(100), null, "SCRIPT", "nom");
		final MatrixModel.Attr valid = new MatrixModel.Attr("aValid", "nomValid", "STRING", false, false, false, "PROPOSED", Integer.valueOf(100), null, "VALID_VALUE", "nom");
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("aNom", new MatrixModel.Cell(MatrixModel.ACCEPTED, "m1", "NOM", null, Integer.valueOf(95), "RULE", null, null, 0, null, null));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "s1", "t1", "Table", "ACCEPTED", null, "", false, null, cells, Integer.valueOf(90), "");
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Personne", Arrays.asList(nom, key, script, valid), Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100));

		MatrixComputeTest.check("isDerived reads the derivation", key.isDerived() && !nom.isDerived());
		MatrixComputeTest.check("effective status of a derived attribute is DERIVED, not NONE", MatrixModel.DERIVED.equals(MatrixCompute.effectiveStatus(key, feed)));
		MatrixComputeTest.check("a derived attribute can still be decided while PROPOSED", MatrixCompute.canDecideAttribute(key));
		final MatrixCompute.FeedStats stats = MatrixCompute.entityStats(entity, 60);
		MatrixComputeTest.check("each counts as ONE item to decide (its own status), never as a missing cell", stats.proposed == 3 && stats.missing == 0);
		MatrixComputeTest.check("so the project is not ready to load yet", !MatrixCompute.readyToLoad(new MatrixModel.Project("P", null, Arrays.asList(entity)), 60));
		final MatrixCompute.BulkPlan entityPlan = MatrixCompute.planEntityBulk(entity, 60);
		MatrixComputeTest.check("the entity sweep takes the matching key at 100%", MatrixComputeTest.containsId(entityPlan, "aKey"));
		MatrixComputeTest.check("but never the script-computed field", !MatrixComputeTest.containsId(entityPlan, "aE164"));
		MatrixComputeTest.check("nor the valid-value field", !MatrixComputeTest.containsId(entityPlan, "aValid"));
		MatrixComputeTest.check("only the key is planned", entityPlan.toDecide.size() == 1 && entityPlan.leftOut == 0);
		final MatrixCompute.BulkPlan feedPlan = MatrixCompute.planFeedBulk(entity, feed, 60);
		MatrixComputeTest.check("the feed sweep takes the key of a field this feed feeds", MatrixComputeTest.containsId(feedPlan, "aKey"));
		MatrixComputeTest.check("and still not the computed fields", !MatrixComputeTest.containsId(feedPlan, "aE164") && !MatrixComputeTest.containsId(feedPlan, "aValid"));
		final MatrixModel.Attr lowKey = new MatrixModel.Attr("aKey", "nomKey", "STRING", false, false, false, "PROPOSED", Integer.valueOf(40), null, "FOLD_KEY", "nom");
		final MatrixModel.Entity lowEntity = new MatrixModel.Entity("e1", "Personne", Arrays.asList(nom, lowKey), Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100));
		final MatrixCompute.BulkPlan lowPlan = MatrixCompute.planEntityBulk(lowEntity, 60);
		MatrixComputeTest.check("a key below the bar is left out and counted, like any field", lowPlan.toDecide.isEmpty() && lowPlan.leftOut == 1);
		final MatrixModel.Attr acceptedKey = new MatrixModel.Attr("aKey", "nomKey", "STRING", false, false, false, "ACCEPTED", Integer.valueOf(100), null, "FOLD_KEY", "nom");
		final MatrixModel.Entity decided = new MatrixModel.Entity("e1", "Personne", Arrays.asList(nom, acceptedKey), Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100));
		MatrixComputeTest.check("once accepted, nothing is left to decide", MatrixCompute.readyToLoad(new MatrixModel.Project("P", null, Arrays.asList(decided)), 60));
	}

	/** A key only follows a source field a later Reject of the field could still reach: never when that field is flagged, rejected or itself left waiting. */
	private static void aMatchingKeyFollowsItsSourceFieldInTheSweep()
	{
		final MatrixModel.Attr key = new MatrixModel.Attr("aKey", "nomKey", "STRING", false, false, false, "PROPOSED", Integer.valueOf(100), null, "FOLD_KEY", "nom");
		final Map<String, MatrixModel.Cell> flaggedCells = new LinkedHashMap<String, MatrixModel.Cell>();
		flaggedCells.put("aNom", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "NOM", null, Integer.valueOf(95), "RULE", null, null, 0, null, null, "EMPTY_COLUMN", "empty", null));
		final MatrixModel.Feed flaggedFeed = new MatrixModel.Feed("f1", "s1", "t1", "Table", "ACCEPTED", null, "", false, null, flaggedCells, Integer.valueOf(90), "");
		final MatrixModel.Attr nomProposed = new MatrixModel.Attr("aNom", "nom", "STRING", false, false, false, "PROPOSED", Integer.valueOf(90));
		MatrixComputeTest.check("a flagged source field holds its key back",
			!MatrixComputeTest.containsId(MatrixCompute.planEntityBulk(
				new MatrixModel.Entity("e1", "P", Arrays.asList(nomProposed, key), Arrays.asList(flaggedFeed), "ACCEPTED", Integer.valueOf(100)), 60), "aKey"));
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("aNom", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "NOM", null, Integer.valueOf(95), "RULE", null, null, 0, null, null));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "s1", "t1", "Table", "ACCEPTED", null, "", false, null, cells, Integer.valueOf(90), "");
		final MatrixModel.Attr nomRejected = new MatrixModel.Attr("aNom", "nom", "STRING", false, false, false, "REJECTED", Integer.valueOf(90));
		MatrixComputeTest.check("a rejected source field holds its key back",
			!MatrixComputeTest.containsId(MatrixCompute.planEntityBulk(
				new MatrixModel.Entity("e1", "P", Arrays.asList(nomRejected, key), Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100)), 60), "aKey"));
		final MatrixModel.Attr nomLow = new MatrixModel.Attr("aNom", "nom", "STRING", false, false, false, "PROPOSED", Integer.valueOf(30));
		MatrixComputeTest.check("a source field left waiting below the bar holds its key back",
			!MatrixComputeTest.containsId(MatrixCompute.planEntityBulk(
				new MatrixModel.Entity("e1", "P", Arrays.asList(nomLow, key), Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100)), 60), "aKey"));
		MatrixComputeTest.check("a source field taken by the same sweep takes its key along",
			MatrixComputeTest.containsId(MatrixCompute.planEntityBulk(
				new MatrixModel.Entity("e1", "P", Arrays.asList(nomProposed, key), Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100)), 60), "aKey"));
		final MatrixModel.Attr orphan = new MatrixModel.Attr("aKey", "nomKey", "STRING", false, false, false, "PROPOSED", Integer.valueOf(100), null, "FOLD_KEY", "");
		MatrixComputeTest.check("a key whose source name could not be read is not held back",
			MatrixComputeTest.containsId(MatrixCompute.planEntityBulk(
				new MatrixModel.Entity("e1", "P", Arrays.asList(nomProposed, orphan), Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100)), 60), "aKey"));
	}

	/** The list and the count come from ONE function: equal on the PersVol9-like project, before and after the keys are decided, and once nothing is left. */
	private static void stillToDecideListIsExactlyTheCount()
	{
		final MatrixModel.Project leftovers = MatrixQueueFixtureTest.persVol9Like(true, false);
		MatrixComputeTest.check("5 leftovers: 3 matching keys, 1 computed field (its derivation goes with it), 1 check; the 2 look-up suggestions are optional, listed apart",
			MatrixCompute.stillToDecide(leftovers, 60).size() == 5 && MatrixCompute.itemsStillToDecide(leftovers, 60) == 5
				&& MatrixCompute.optionalSuggestionCount(MatrixCompute.optionalServices(leftovers)) == 2);
		final MatrixModel.Project keysDone = MatrixQueueFixtureTest.persVol9Like(true, true);
		MatrixComputeTest.check("2 once the keys are accepted", MatrixCompute.stillToDecide(keysDone, 60).size() == MatrixCompute.itemsStillToDecide(keysDone, 60)
			&& MatrixCompute.itemsStillToDecide(keysDone, 60) == 2);
		final MatrixModel.Project untouched = MatrixQueueFixtureTest.persVol9Like(false, false);
		MatrixComputeTest.check("equal on the untouched project too", MatrixCompute.stillToDecide(untouched, 60).size() == MatrixCompute.itemsStillToDecide(untouched, 60));
		MatrixComputeTest.check("and the count is the one readyToLoad gates on", !MatrixCompute.readyToLoad(untouched, 60));
		final MatrixModel.Attr done = new MatrixModel.Attr("a1", "nom", "STRING", true, false, false, "ACCEPTED", Integer.valueOf(90));
		final MatrixModel.Project empty = new MatrixModel.Project("P", null,
			Arrays.asList(new MatrixModel.Entity("e1", "P", Arrays.asList(done), java.util.Collections.<MatrixModel.Feed>emptyList(), "ACCEPTED", Integer.valueOf(100))));
		MatrixComputeTest.check("an empty list <=> ready", MatrixCompute.stillToDecide(empty, 60).isEmpty() && MatrixCompute.readyToLoad(empty, 60));
		// a flagged PROPOSED cell used to be counted twice (proposed + attention): the list holds it once
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("a1", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "COL", null, Integer.valueOf(90), "LLM", null, null, 0, null, null, "EMPTY_COLUMN", "empty in SAP (0 / 165)", null));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "s1", "t1", "T", "ACCEPTED", null, "", false, null, cells, Integer.valueOf(90), "");
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "mobilePhone", "STRING", false, false, false, "ACCEPTED", Integer.valueOf(90));
		final MatrixModel.Project flagged = new MatrixModel.Project("P", null,
			Arrays.asList(new MatrixModel.Entity("e1", "P", Arrays.asList(attr), Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100))));
		MatrixComputeTest.check("a flagged proposed link is one item", MatrixCompute.itemsStillToDecide(flagged, 60) == 1);
	}

	private static MatrixCompute.StillItem itemWithKey(final List<MatrixCompute.StillItem> pItems, final String pKey)
	{
		for (final MatrixCompute.StillItem item : pItems)
		{
			if (item.key.equals(pKey))
			{
				return item;
			}
		}
		return null;
	}

	/** Every item carries a kind chip and the plain reason the bulk button did not take it - the PersVol9 leftovers, one by one. */
	private static void stillToDecideNamesWhyEachLeftoverWasNotTaken()
	{
		final List<MatrixCompute.StillItem> items = MatrixCompute.stillToDecide(MatrixQueueFixtureTest.persVol9Like(true, false), 60);
		final List<String> order = new java.util.ArrayList<String>();
		for (final MatrixCompute.StillItem item : items)
		{
			order.add(item.key);
		}
		MatrixComputeTest.check("reading order: per business object, field rows in order, a field's guards right after it",
			order.equals(Arrays.asList("attr|a5", "attr|a6", "attr|a7", "attr|a8", "guard|g5")));
		MatrixComputeTest.check("the three matching keys are 'Matching key' items the button takes",
			MatrixCompute.KIND_MATCHING_KEY.equals(MatrixComputeTest.itemWithKey(items, "attr|a5").kind) && MatrixComputeTest.itemWithKey(items, "attr|a5").takenByTheButton()
				&& MatrixComputeTest.itemWithKey(items, "attr|a6").takenByTheButton() && MatrixComputeTest.itemWithKey(items, "attr|a7").takenByTheButton());
		final MatrixCompute.StillItem computed = MatrixComputeTest.itemWithKey(items, "attr|a8");
		MatrixComputeTest.check("the script-computed field is a 'Computed field' that needs a person",
			MatrixCompute.KIND_COMPUTED.equals(computed.kind) && MatrixCompute.REASON_COMPUTED.equals(computed.reason) && !computed.takenByTheButton());
		MatrixComputeTest.check("its derivation suggestion is decided with it, never a line of its own", MatrixComputeTest.itemWithKey(items, "guard|g3") == null);
		final MatrixCompute.StillItem check = MatrixComputeTest.itemWithKey(items, "guard|g5");
		MatrixComputeTest.check("a check under the bar says its confidence and is a 'Check'",
			MatrixCompute.REASON_BELOW_BAR.equals(check.reason) && check.confidence.intValue() == 15 && MatrixCompute.KIND_CHECK.equals(check.kind));
		MatrixComputeTest.check("a look-up suggestion is not an item at all", MatrixComputeTest.itemWithKey(items, "guard|g1") == null && MatrixComputeTest.itemWithKey(items, "guard|g4") == null);
		MatrixComputeTest.check("2 of the 5 need a person", MatrixCompute.needingAPerson(items) == 2);
		// a flagged field: its links say 'needs your eye' with the cell's own reason
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("a1", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "COL", null, Integer.valueOf(90), "LLM", null, null, 0, null, null, "EMPTY_COLUMN", "empty in SAP (0 / 165)", null));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "s1", "t1", "T", "ACCEPTED", null, "", false, null, cells, Integer.valueOf(90), "");
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "mobilePhone", "STRING", false, false, false, "PROPOSED", Integer.valueOf(90));
		final MatrixModel.Project flagged = new MatrixModel.Project("P", new java.util.ArrayList<MatrixModel.Source>(Arrays.asList(new MatrixModel.Source("s1", "SAP", "#000"))),
			Arrays.asList(new MatrixModel.Entity("e1", "P", Arrays.asList(attr), Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100))));
		final List<MatrixCompute.StillItem> flaggedItems = MatrixCompute.stillToDecide(flagged, 60);
		final MatrixCompute.StillItem link = MatrixComputeTest.itemWithKey(flaggedItems, "f1|a1");
		MatrixComputeTest.check("a flagged field and its link read 'needs your eye', quoting why",
			MatrixCompute.REASON_ATTENTION.equals(link.reason) && "empty in SAP (0 / 165)".equals(link.detail)
				&& MatrixCompute.REASON_ATTENTION.equals(MatrixComputeTest.itemWithKey(flaggedItems, "attr|a1").reason));
		MatrixComputeTest.check("a field link is a 'Field link' labelled from source to field", MatrixCompute.KIND_FIELD_LINK.equals(link.kind) && link.label.contains("COL") && link.label.contains("mobilePhone"));
		// a business object still PROPOSED and below the bar: its fields wait for it
		final MatrixModel.Attr nom = new MatrixModel.Attr("a1", "nom", "STRING", false, false, false, "PROPOSED", Integer.valueOf(90));
		final MatrixModel.Project pending = new MatrixModel.Project("P", null,
			Arrays.asList(new MatrixModel.Entity("e1", "Personne", Arrays.asList(nom), java.util.Collections.<MatrixModel.Feed>emptyList(), "PROPOSED", Integer.valueOf(40))));
		final List<MatrixCompute.StillItem> pendingItems = MatrixCompute.stillToDecide(pending, 60);
		MatrixComputeTest.check("the business object itself is under the bar", MatrixCompute.REASON_BELOW_BAR.equals(MatrixComputeTest.itemWithKey(pendingItems, "entity|e1").reason));
		final MatrixCompute.StillItem waiting = MatrixComputeTest.itemWithKey(pendingItems, "attr|a1");
		MatrixComputeTest.check("its field must wait for it, naming it", MatrixCompute.REASON_PARENT_FIRST.equals(waiting.reason) && "Personne".equals(waiting.detail));
	}

	/** The wizard's rule: a connector suggestion set aside is neither counted nor listed; a still-proposed service is never offered Accept (Set aside / Reject). */
	private static void aSetAsideGuardIsNotCountedAndAServiceIsNeverAccepted()
	{
		final Guard aside = new Guard("gA", ReviewLine.EXTERNAL_SERVICE, "e1", "a1", "op", "", null, null, ReviewLine.SET_ASIDE, "LLM", Integer.valueOf(40), "r", null, null, null);
		final Guard service = new Guard("gB", ReviewLine.EXTERNAL_SERVICE, "e1", "a1", "op", "", null, null, ReviewLine.PROPOSED, "LLM", Integer.valueOf(40), "r", null, null, null);
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "nom", "STRING", false, false, false, "ACCEPTED", Integer.valueOf(90), Arrays.asList(aside, service));
		final MatrixModel.Project project = new MatrixModel.Project("P", null,
			Arrays.asList(new MatrixModel.Entity("e1", "P", Arrays.asList(attr), java.util.Collections.<MatrixModel.Feed>emptyList(), "ACCEPTED", Integer.valueOf(100))));
		final List<MatrixCompute.StillItem> items = MatrixCompute.stillToDecide(project, 60);
		MatrixComputeTest.check("a service suggestion is never an item that needs you: the list is empty and the project can load", items.isEmpty()
			&& MatrixCompute.itemsStillToDecide(project, 60) == 0 && MatrixCompute.readyToLoad(project, 60));
		final List<MatrixCompute.ServiceGroup> groups = MatrixCompute.optionalServices(project);
		MatrixComputeTest.check("only the still-proposed service is an optional suggestion (one group, one candidate, never the set-aside one)",
			groups.size() == 1 && groups.get(0).items.size() == 1 && "guard|gB".equals(groups.get(0).items.get(0).key));
		MatrixComputeTest.check("a service is offered Set aside, never Accept", groups.get(0).items.get(0).setAside);
		final Guard check = new Guard("gC", ReviewLine.SCRIPT_CONSTRAINT, "e1", "a1", "isValid", "", "WARN", null, ReviewLine.PROPOSED, "LLM", Integer.valueOf(40), "r", null, null, null);
		final MatrixModel.Attr attr2 = new MatrixModel.Attr("a1", "nom", "STRING", false, false, false, "ACCEPTED", Integer.valueOf(90), Arrays.asList(check));
		final MatrixModel.Project project2 = new MatrixModel.Project("P", null,
			Arrays.asList(new MatrixModel.Entity("e1", "P", Arrays.asList(attr2), java.util.Collections.<MatrixModel.Feed>emptyList(), "ACCEPTED", Integer.valueOf(100))));
		MatrixComputeTest.check("a script check is accepted or rejected, never set aside", !MatrixCompute.stillToDecide(project2, 60).get(0).setAside);
	}

	/** After a decision: the item that followed the decided one, else the first; a decided item still listed is kept. */
	private static void nextItemAfterADecision()
	{
		final List<MatrixCompute.StillItem> items = MatrixCompute.stillToDecide(MatrixQueueFixtureTest.persVol9Like(true, false), 60);
		// a5 was decided: it is gone from the fresh list; the keys that followed it in the old list were a6, a7, ...
		final List<MatrixCompute.StillItem> fresh = new java.util.ArrayList<MatrixCompute.StillItem>(items);
		fresh.remove(MatrixComputeTest.itemWithKey(items, "attr|a5"));
		final List<String> following = Arrays.asList("attr|a6", "attr|a7", "attr|a8", "guard|g3", "guard|g5");
		MatrixComputeTest.check("the next one follows the decided one", "attr|a6".equals(MatrixCompute.nextAfterDecision(fresh, "attr|a5", following).key));
		// a6 and a7 decided as well (a cascade): the first following key still listed
		fresh.remove(MatrixComputeTest.itemWithKey(items, "attr|a6"));
		fresh.remove(MatrixComputeTest.itemWithKey(items, "attr|a7"));
		MatrixComputeTest.check("skipping what left the list too", "attr|a8".equals(MatrixCompute.nextAfterDecision(fresh, "attr|a5", following).key));
		// the decided one was the last of the list: back to the first
		final List<MatrixCompute.StillItem> endFresh = new java.util.ArrayList<MatrixCompute.StillItem>(items);
		endFresh.remove(MatrixComputeTest.itemWithKey(items, "guard|g5"));
		MatrixComputeTest.check("after the last, the first", "attr|a5".equals(MatrixCompute.nextAfterDecision(endFresh, "guard|g5", java.util.Collections.<String>emptyList()).key));
		MatrixComputeTest.check("a decision that did not take (item still listed) keeps the selection", "attr|a5".equals(MatrixCompute.nextAfterDecision(items, "attr|a5", following).key));
		final List<String> rendered = Arrays.asList("guard|g5", "attr|a5", "attr|a6");
		MatrixComputeTest.check("the keys after the selected one come from the last rendered list", Arrays.asList("attr|a6").equals(MatrixCompute.keysAfter(rendered, "attr|a5")));
		MatrixComputeTest.check("a selection not in that list gets the whole list", rendered.equals(MatrixCompute.keysAfter(rendered, "zz")) && rendered.equals(MatrixCompute.keysAfter(rendered, null)));
		MatrixComputeTest.check("nothing rendered yet, nothing after", MatrixCompute.keysAfter(null, "attr|a5").isEmpty());
		MatrixComputeTest.check("nothing left, nothing selected", MatrixCompute.nextAfterDecision(java.util.Collections.<MatrixCompute.StillItem>emptyList(), "attr|a5", following) == null);
	}

	/** "Only what's left to do": a row stays when the field, one of its links or one of its guards is still to decide; a mandatory field not covered by some source stays too. */
	private static void onlyWhatIsLeftFilterKeepsTheRightRows()
	{
		final MatrixModel.Project project = MatrixQueueFixtureTest.persVol9Like(true, false);
		final List<MatrixCompute.StillItem> items = MatrixCompute.stillToDecide(project, 60);
		final MatrixModel.Entity personne = project.entityById("e1");
		final Map<String, Boolean> kept = new LinkedHashMap<String, Boolean>();
		for (final MatrixModel.Attr attr : personne.attrs)
		{
			kept.put(attr.name, Boolean.valueOf(MatrixCompute.rowHasWork(personne, attr, items)));
		}
		MatrixComputeTest.check("firstName goes (its only leftover is an optional look-up), lastName/email/phone go", !kept.get("firstName").booleanValue() && !kept.get("lastName").booleanValue()
			&& !kept.get("email").booleanValue() && !kept.get("phone").booleanValue());
		MatrixComputeTest.check("the three keys and the computed field stay", kept.get("firstNameKey").booleanValue() && kept.get("lastNameKey").booleanValue()
			&& kept.get("emailKey").booleanValue() && kept.get("phoneE164").booleanValue());
		MatrixComputeTest.check("the technical source key row goes", !kept.get("sourceId").booleanValue());
		final MatrixModel.Entity adresse = project.entityById("e2");
		int keptAddress = 0;
		for (final MatrixModel.Attr attr : adresse.attrs)
		{
			if (MatrixCompute.rowHasWork(adresse, attr, items))
			{
				keptAddress++;
			}
		}
		MatrixComputeTest.check("Adresse keeps validFrom only (postalCode has an optional look-up only)", keptAddress == 1);
		// a mandatory field a feed does not cover at all
		final MatrixModel.Attr mandatory = new MatrixModel.Attr("aM", "iban", "STRING", true, false, false, "ACCEPTED", Integer.valueOf(90));
		final MatrixModel.Feed bare = new MatrixModel.Feed("fB", "s1", "t1", "T", "ACCEPTED", null, "", false, null, null, Integer.valueOf(90), "");
		final MatrixModel.Entity entity = new MatrixModel.Entity("e9", "X", Arrays.asList(mandatory), Arrays.asList(bare), "ACCEPTED", Integer.valueOf(100));
		MatrixComputeTest.check("an uncovered mandatory field stays in the filtered view", MatrixCompute.rowHasWork(entity, mandatory, java.util.Collections.<MatrixCompute.StillItem>emptyList()));
	}

	/** Per-tab counters come from the same list; a selection key is owned by the tab that holds it. */
	private static void perTabCountsAndKeyOwnership()
	{
		final MatrixModel.Project project = MatrixQueueFixtureTest.persVol9Like(true, false);
		final List<MatrixCompute.StillItem> items = MatrixCompute.stillToDecide(project, 60);
		MatrixComputeTest.check("Personne has 4, Adresse 1 - and they add up to the footer's count",
			MatrixCompute.countOfEntity(items, "e1") == 4 && MatrixCompute.countOfEntity(items, "e2") == 1 && MatrixCompute.countOfEntity(items, "e1") + MatrixCompute.countOfEntity(items, "e2") == items.size());
		MatrixComputeTest.check("the first item of Adresse is its validFrom check", "guard|g5".equals(MatrixCompute.firstOfEntity(items, "e2").key));
		MatrixComputeTest.check("an unknown object has none", MatrixCompute.countOfEntity(items, "zz") == 0 && MatrixCompute.firstOfEntity(items, "zz") == null);
		final MatrixModel.Entity personne = project.entityById("e1");
		MatrixComputeTest.check("a link cell, a guard, a field row, a feed column and the tab are owned by their object",
			MatrixCompute.ownsKey(personne, "f1|a1") && MatrixCompute.ownsKey(personne, "guard|g3") && MatrixCompute.ownsKey(personne, "attr|a8")
				&& MatrixCompute.ownsKey(personne, "feed|f2") && MatrixCompute.ownsKey(personne, "entity|e1") && MatrixCompute.ownsKey(personne, "derived|a8"));
		MatrixComputeTest.check("another object's keys and blanks are not", !MatrixCompute.ownsKey(personne, "guard|g4") && !MatrixCompute.ownsKey(personne, "f3|a10")
			&& !MatrixCompute.ownsKey(personne, "") && !MatrixCompute.ownsKey(personne, null));
	}

	/** Threshold-left-out lot's own rule (WizardRules#bulkEligibility), reused here: a PROPOSED row below the threshold is left out and counted, never silently dropped. */
	private static void entityBulkLeavesLowConfidenceRowsOut()
	{
		final MatrixModel.Attr nom = new MatrixModel.Attr("aNom", "nom", "STRING", true, false, false, "PROPOSED", Integer.valueOf(40));
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Personne", Arrays.asList(nom), java.util.Collections.<MatrixModel.Feed>emptyList(),
			"ACCEPTED", Integer.valueOf(100));
		final MatrixCompute.BulkPlan plan = MatrixCompute.planEntityBulk(entity, 60);
		MatrixComputeTest.check("nothing eligible: the entity is already decided, the only attribute is below the threshold", plan.toDecide.isEmpty());
		MatrixComputeTest.check("the low-confidence attribute is counted as left out, not silently skipped", plan.leftOut == 1);
		MatrixComputeTest.check("lowest left-out confidence tracked", plan.lowestLeftOutConfidence == 40);
	}

	/** Task brief: "Bulk per feed = the feed itself + the eligible attributes its mappings point to + its eligible mappings" - never a sibling feed's own attribute. */
	private static void feedBulkOnlyTouchesItsOwnFeedAndAttributes()
	{
		final MatrixModel.Attr nom = new MatrixModel.Attr("aNom", "nom", "STRING", true, false, false, "PROPOSED", Integer.valueOf(90));
		final MatrixModel.Attr email = new MatrixModel.Attr("aEmail", "email", "STRING", false, false, false, "PROPOSED", Integer.valueOf(90));

		final Map<String, MatrixModel.Cell> cellsOfFeed1 = new LinkedHashMap<String, MatrixModel.Cell>();
		cellsOfFeed1.put("aNom", new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "NOM", null, Integer.valueOf(95), "RULE", null, null, 0, null, null));
		final MatrixModel.Feed feed1 = new MatrixModel.Feed("f1", "s1", "t1", "Table1", "PROPOSED", null, "", false, null, cellsOfFeed1, Integer.valueOf(90), "");

		final Map<String, MatrixModel.Cell> cellsOfFeed2 = new LinkedHashMap<String, MatrixModel.Cell>();
		cellsOfFeed2.put("aEmail", new MatrixModel.Cell(MatrixModel.PROPOSED, "m2", "EMAIL", null, Integer.valueOf(95), "RULE", null, null, 0, null, null));
		final MatrixModel.Feed feed2 = new MatrixModel.Feed("f2", "s2", "t2", "Table2", "PROPOSED", null, "", false, null, cellsOfFeed2, Integer.valueOf(90), "");

		final MatrixModel.Entity entity =
			new MatrixModel.Entity("e1", "Personne", Arrays.asList(nom, email), Arrays.asList(feed1, feed2), "ACCEPTED", Integer.valueOf(100));

		final MatrixCompute.BulkPlan plan = MatrixCompute.planFeedBulk(entity, feed1, 60);
		MatrixComputeTest.check("exactly 3 rows: feed1 itself, its own attribute, its own mapping", plan.toDecide.size() == 3);
		MatrixComputeTest.check("never feed2's own attribute", !MatrixComputeTest.containsId(plan, "aEmail"));
		MatrixComputeTest.check("never feed2's own mapping", !MatrixComputeTest.containsId(plan, "m2"));
		MatrixComputeTest.check("never feed2 itself", !MatrixComputeTest.containsId(plan, "f2"));
	}

	private static boolean containsId(final MatrixCompute.BulkPlan pPlan, final String pId)
	{
		for (final MatrixCompute.PlannedDecision decision : pPlan.toDecide)
		{
			if (pId.equals(decision.id))
			{
				return true;
			}
		}
		return false;
	}

	/** Coordinator review, 2026-09-23: "a feed PROPOSED with all mappings accepted is not 'all validated'" - counters must reflect the entity's/attribute's/feed's OWN pending decision too, not only mapping cells. */
	private static void countersIncludeUndecidedStructuralRows()
	{
		final MatrixModel.Attr nom = new MatrixModel.Attr("aNom", "nom", "STRING", true, false, false, "ACCEPTED", Integer.valueOf(100));
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("aNom", new MatrixModel.Cell(MatrixModel.ACCEPTED, "m1", "NOM", null, Integer.valueOf(100), "RULE", null, null, 0, null, null));
		// every mapping is ACCEPTED, but the FEED itself is still PROPOSED - must not read "all validated"
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "s1", "t1", "Table", "PROPOSED", null, "", false, null, cells, Integer.valueOf(90), "");
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Personne", Arrays.asList(nom), Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100));

		final MatrixCompute.FeedStats feedLevel = MatrixCompute.feedStats(entity.attrs, feed, 60);
		MatrixComputeTest.check("the feed's own pending decision counts as one more 'to validate', even though its one mapping is accepted",
			feedLevel.proposed == 1);

		final MatrixCompute.FeedStats entityLevel = MatrixCompute.entityStats(entity, 60);
		MatrixComputeTest.check("propagates to the entity-wide counters", entityLevel.proposed == 1);

		final Map<String, MatrixCompute.FeedStats> bySource = MatrixCompute.statsBySource(entity, 60);
		MatrixComputeTest.check("and to the per-source overview chip", bySource.get("s1").proposed == 1);

		// now the entity itself and a non-key attribute are ALSO still pending
		final MatrixModel.Attr pendingAttr = new MatrixModel.Attr("aEmail", "email", "STRING", false, false, false, "PROPOSED", Integer.valueOf(80));
		final MatrixModel.Feed decidedFeed = new MatrixModel.Feed("f2", "s1", "t2", "Table2", "ACCEPTED", null, "", false, null, null);
		final MatrixModel.Entity pendingEntity =
			new MatrixModel.Entity("e2", "Adresse", Arrays.asList(pendingAttr), Arrays.asList(decidedFeed), "PROPOSED", Integer.valueOf(70));
		final MatrixCompute.FeedStats pendingStats = MatrixCompute.entityStats(pendingEntity, 60);
		MatrixComputeTest.check("the entity's own pending decision and its pending attribute both count, even with a fully-decided feed",
			pendingStats.proposed == 2);
	}


	/**
	 * fix/mapping-matrix-live (2026-09-24): {@code curveChipPoint} existed but was never called - no status
	 * chip was ever drawn. {@code chipT} is the missing "which t" - verified here for 2, 5 and 8 sources (the
	 * task's own "2 sources x 2 entities and 5 x 2 and 8 x 6" spread) that DIFFERENT entities never land their
	 * chip at the same depth along a shared-source curve, whatever the entity count.
	 */
	private static void chipPositionsDoNotCollideAcrossEntityCounts()
	{
		MatrixComputeTest.checkChipsSpreadOut(2);
		MatrixComputeTest.checkChipsSpreadOut(5);
		MatrixComputeTest.checkChipsSpreadOut(8);
	}

	private static void checkChipsSpreadOut(final int pEntityCount)
	{
		final double x1 = 210, x2 = 920, y1 = 44, y2 = 300;
		final java.util.Set<Long> seenX = new java.util.HashSet<Long>();
		for (int e = 0; e < pEntityCount; e++)
		{
			final double t = MatrixCompute.chipT(e, pEntityCount);
			MatrixComputeTest.check("chipT(" + e + "," + pEntityCount + ") stays within the curve (0..1)", t >= 0.0 && t <= 1.0);
			final MatrixCompute.Point p = MatrixCompute.curveChipPoint(x1, y1, x2, y2, t);
			final long roundedX = Math.round(p.x);
			MatrixComputeTest.check("entity " + e + " of " + pEntityCount + " gets an x-position no earlier entity already used on this same curve",
				seenX.add(Long.valueOf(roundedX)));
		}
	}

	/**
	 * Live fix (bug 7, docs/STAGING-PROPOSAL.md "live fixes"): {@code chipT(entity, entityCount)} alone gives
	 * every source feeding the same entity the identical {@code t} - measured live on project Personnes (2
	 * sources x 2 entities), two chips landed on the exact same x. The 4-arg overload additionally spreads
	 * over the source index; checked for 2 and 5 sources (the case actually seen live, and the mockup's own
	 * "5 sources" scale) x 2 and 6 entities that: (1) every (entity, source) pair gets its own x on a shared
	 * curve, and (2) the per-source spread never crosses into a neighbouring entity's own base position (the
	 * two-entity case is the tightest: only two bases 0.4 apart on the 0..1 curve).
	 */
	private static void chipPositionsSpreadAcrossSourcesOfTheSameEntity()
	{
		MatrixComputeTest.checkChipsSpreadOutBySource(2, 2);
		MatrixComputeTest.checkChipsSpreadOutBySource(2, 5);
		MatrixComputeTest.checkChipsSpreadOutBySource(6, 5);
	}

	private static void checkChipsSpreadOutBySource(final int pEntityCount, final int pSourceCount)
	{
		final double x1 = 210, x2 = 920, y1 = 44, y2 = 300;
		final double[] entityBaseT = new double[pEntityCount];
		for (int e = 0; e < pEntityCount; e++)
		{
			entityBaseT[e] = MatrixCompute.chipT(e, pEntityCount);
		}
		for (int e = 0; e < pEntityCount; e++)
		{
			final java.util.Set<Long> seenX = new java.util.HashSet<Long>();
			for (int s = 0; s < pSourceCount; s++)
			{
				final double t = MatrixCompute.chipT(e, pEntityCount, s, pSourceCount);
				MatrixComputeTest.check("chipT(" + e + "," + pEntityCount + "," + s + "," + pSourceCount + ") stays within the curve (0..1)",
					t >= 0.0 && t <= 1.0);
				final MatrixCompute.Point p = MatrixCompute.curveChipPoint(x1, y1, x2, y2, t);
				MatrixComputeTest.check("entity " + e + ", source " + s + " of " + pSourceCount + " gets an x no earlier source of the SAME entity used",
					seenX.add(Long.valueOf(Math.round(p.x))));
				// never bleeds into a neighbouring entity's own base t (would make a chip of entity e look
				// like it belongs to entity e-1/e+1's own band)
				for (int otherE = 0; otherE < pEntityCount; otherE++)
				{
					if (otherE == e)
					{
						continue;
					}
					final double distanceToOwnBase = Math.abs(t - entityBaseT[e]);
					final double distanceToOtherBase = Math.abs(t - entityBaseT[otherE]);
					MatrixComputeTest.check(
						"entity " + e + " source " + s + "'s t (" + t + ") stays closer to its own entity base (" + entityBaseT[e]
							+ ") than to entity " + otherE + "'s (" + entityBaseT[otherE] + ")",
						distanceToOwnBase < distanceToOtherBase);
				}
			}
		}
	}


	private static MatrixModel.Cell shownCell(final String pStatus, final String pMappingId, final int pConfidence)
	{
		return new MatrixModel.Cell(pStatus, pMappingId, "COL", null, Integer.valueOf(pConfidence), "LLM", null, null, 0, null, null);
	}

	private static boolean planned(final MatrixCompute.BulkPlan pPlan, final String pNature, final String pId)
	{
		for (final MatrixCompute.PlannedDecision decision : pPlan.toDecide)
		{
			if (decision.nature.equals(pNature) && decision.id.equals(pId))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Validation desk lot L2: "Accept as shown" - the confidence is not asked (20 % is taken), a flagged link waits (and the field whose every link is flagged with it), a REJECTED row
	 * is never planned, an ACCEPTED one is already done, a feed that was not shown is not touched, the technical key / parent link / matching key are never planned, and the order is
	 * the cascade's (entity, fields, feeds, links).
	 */
	private static void acceptAsShownTakesWhatWasShownWhateverItsConfidenceAndLeavesTheFlagged()
	{
		final MatrixModel.Attr key = new MatrixModel.Attr("k", "sourceId", "STRING", true, false, true);
		final MatrixModel.Attr parent = new MatrixModel.Attr("p", "personne", "STRING", true, true, false, ReviewLine.PROPOSED, Integer.valueOf(100));
		final MatrixModel.Attr low = new MatrixModel.Attr("a1", "street", "STRING", true, false, false, ReviewLine.PROPOSED, Integer.valueOf(20));
		final MatrixModel.Attr done = new MatrixModel.Attr("a2", "city", "STRING", false, false, false, ReviewLine.ACCEPTED, Integer.valueOf(95));
		final MatrixModel.Attr allFlagged = new MatrixModel.Attr("a3", "addressType", "STRING", false, false, false, ReviewLine.PROPOSED, Integer.valueOf(91));
		final MatrixModel.Attr oneFlagged = new MatrixModel.Attr("a4", "postalCode", "STRING", false, false, false, ReviewLine.PROPOSED, Integer.valueOf(90));
		final MatrixModel.Attr matchingKey = new MatrixModel.Attr("a5", "cityKey", "STRING", false, false, false, ReviewLine.PROPOSED, Integer.valueOf(99), null, "FOLD_KEY", "city");
		final MatrixModel.Attr notShown = new MatrixModel.Attr("a6", "country", "STRING", false, false, false, ReviewLine.PROPOSED, Integer.valueOf(99));
		final Map<String, MatrixModel.Cell> shown = new LinkedHashMap<String, MatrixModel.Cell>();
		shown.put("p", new MatrixModel.Cell(MatrixModel.ENGINE, null, null, null, null, null, null, null, 0, null, null));
		shown.put("a1", MatrixComputeTest.shownCell(MatrixModel.PROPOSED, "m1", 20));
		shown.put("a2", MatrixComputeTest.shownCell(MatrixModel.ACCEPTED, "m2", 95));
		shown.put("a3", MatrixComputeTest.shownCell(MatrixModel.PROPOSED, "m3", 91));
		shown.put("a4", MatrixComputeTest.shownCell(MatrixModel.PROPOSED, "m4", 90));
		shown.put("a5", MatrixComputeTest.shownCell(MatrixModel.REJECTED, "m5", 90));
		final Map<String, MatrixModel.Cell> second = new LinkedHashMap<String, MatrixModel.Cell>();
		second.put("a4", MatrixComputeTest.shownCell(MatrixModel.PROPOSED, "m6", 90));
		final Map<String, MatrixModel.Cell> hidden = new LinkedHashMap<String, MatrixModel.Cell>();
		hidden.put("a6", MatrixComputeTest.shownCell(MatrixModel.PROPOSED, "m7", 99));
		final MatrixModel.Feed feedShown = new MatrixModel.Feed("f1", "s1", "t1", "T1", ReviewLine.PROPOSED, null, "", false, null, shown, Integer.valueOf(30), "");
		final MatrixModel.Feed feedSecond = new MatrixModel.Feed("f2", "s2", "t2", "T2", ReviewLine.ACCEPTED, null, "", false, null, second, Integer.valueOf(90), "");
		final MatrixModel.Feed feedHidden = new MatrixModel.Feed("f3", "s3", "t3", "T3", ReviewLine.PROPOSED, null, "", false, null, hidden, Integer.valueOf(99), "");
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Adresse", Arrays.asList(key, parent, low, done, allFlagged, oneFlagged, matchingKey, notShown),
			Arrays.asList(feedShown, feedSecond, feedHidden), ReviewLine.PROPOSED, Integer.valueOf(40));

		final MatrixCompute.ShownKeys keys = new MatrixCompute.ShownKeys();
		keys.shownFeedIds.add("f1");
		keys.shownFeedIds.add("f2");
		keys.flaggedMappingIds.add("m3");
		keys.flaggedMappingIds.add("m4");
		final MatrixCompute.BulkPlan plan = MatrixCompute.planEntityAsShown(entity, keys);
		check("the object, at 40 %, is taken: the person saw the result", MatrixComputeTest.planned(plan, ReviewLine.ENTITY, "e1"));
		check("a 20 % field and its 20 % link are taken", MatrixComputeTest.planned(plan, ReviewLine.ATTRIBUTE, "a1") && MatrixComputeTest.planned(plan, ReviewLine.MAPPING, "m1")
			&& MatrixComputeTest.planned(plan, ReviewLine.FEED, "f1"));
		check("an ACCEPTED field, link and feed are not planned again", !MatrixComputeTest.planned(plan, ReviewLine.ATTRIBUTE, "a2") && !MatrixComputeTest.planned(plan, ReviewLine.MAPPING, "m2")
			&& !MatrixComputeTest.planned(plan, ReviewLine.FEED, "f2"));
		check("a flagged link waits, and the field whose every link is flagged waits with it", !MatrixComputeTest.planned(plan, ReviewLine.MAPPING, "m3") && !MatrixComputeTest.planned(plan, ReviewLine.ATTRIBUTE, "a3"));
		check("a field with another, unflagged link is taken, the flagged one waits", MatrixComputeTest.planned(plan, ReviewLine.ATTRIBUTE, "a4") && !MatrixComputeTest.planned(plan, ReviewLine.MAPPING, "m4"));
		check("a REJECTED link is never touched", !MatrixComputeTest.planned(plan, ReviewLine.MAPPING, "m5"));
		check("a feed that was not shown, and what it feeds, are not touched", !MatrixComputeTest.planned(plan, ReviewLine.FEED, "f3") && !MatrixComputeTest.planned(plan, ReviewLine.MAPPING, "m7")
			&& !MatrixComputeTest.planned(plan, ReviewLine.ATTRIBUTE, "a6"));
		check("the key, the parent link and a matching key are never planned", !MatrixComputeTest.planned(plan, ReviewLine.ATTRIBUTE, "k") && !MatrixComputeTest.planned(plan, ReviewLine.ATTRIBUTE, "p")
			&& !MatrixComputeTest.planned(plan, ReviewLine.ATTRIBUTE, "a5"));
		check("two links held (m3, m4)", plan.held == 2 && plan.leftOut == 0);
		// m6 sits in an ACCEPTED feed and is PROPOSED: it IS planned (the feed was shown, the link not flagged)
		check("an unflagged PROPOSED link of a shown, accepted feed is planned", MatrixComputeTest.planned(plan, ReviewLine.MAPPING, "m6"));
		// order: entity, fields, feeds, links - the same cascade as planEntityBulk
		final List<String> order = new java.util.ArrayList<String>();
		for (final MatrixCompute.PlannedDecision decision : plan.toDecide)
		{
			order.add(decision.nature);
		}
		check("the cascade order: " + order, Arrays.asList(ReviewLine.ENTITY, ReviewLine.ATTRIBUTE, ReviewLine.ATTRIBUTE, ReviewLine.FEED, ReviewLine.MAPPING, ReviewLine.MAPPING).equals(order));
		// nothing shown, nothing planned beyond the object
		final MatrixCompute.BulkPlan none = MatrixCompute.planEntityAsShown(entity, new MatrixCompute.ShownKeys());
		check("with no feed shown only the object is planned", none.toDecide.size() == 1 && MatrixComputeTest.planned(none, ReviewLine.ENTITY, "e1") && none.held == 0);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
