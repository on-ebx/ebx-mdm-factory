package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * JUnit-free: run the main, a failure throws. Production review of 2026-11-12 (F3-01, F3-11, F3-12, F3-105, F3-113): a structural check of the wiring the offline
 * suite cannot run (it needs an EBX repository) - the model operations an application sends are never read from the stored row, the apply and decide entry points
 * start with the switch, the planner marks like v1.3.0 and records nothing when drift is closed.
 */
public final class SourceDriftsSourceTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java")).resolve("com/on/ebx/factory");
		final String drifts = SourceDriftsSourceTest.read(root.resolve("ebx/SourceDrifts.java"));
		final String planner = SourceDriftsSourceTest.read(root.resolve("ebx/LandingPlanner.java"));
		final String resource = SourceDriftsSourceTest.read(root.resolve("rest/FactoryResource.java"));

		// F3-01: nothing the application executes comes from the stored row
		SourceDriftsSourceTest.check("the stored operations text is written for display and never read back",
			drifts.contains("FactoryPaths.SourceDrift.OPERATIONS") && drifts.indexOf("FactoryPaths.SourceDrift.OPERATIONS") == drifts.lastIndexOf("FactoryPaths.SourceDrift.OPERATIONS"));
		SourceDriftsSourceTest.check("the only write of that field is the display one", drifts.contains("value.setValue(SchemaDrift.operations(tableName, change).toString(), FactoryPaths.SourceDrift.OPERATIONS)"));
		SourceDriftsSourceTest.check("patchAndPublish takes operations computed by the caller, not a text", drifts.contains("final String pModel, final ArrayNode pOperations")
			&& !drifts.contains("readTree("));
		SourceDriftsSourceTest.check("apply recomputes through SchemaDrift.revalidate and sends SchemaDrift.operations of the recomputed change",
			drifts.contains("SchemaDrift.revalidate(") && drifts.contains("SchemaDrift.operations(landingTable.getString(FactoryPaths.LandingTable.TABLE_NAME), change)"));
		SourceDriftsSourceTest.check("the landing column, the retype and the removal use the recomputed change, not the row",
			drifts.contains("addColumn(pSession, pFactory, landingTable, change)") && drifts.contains("retypeColumn(pSession, pFactory, tableId, change)")
				&& drifts.contains("removeColumn(pSession, pFactory, tableId, change.sourceColumn)") && !drifts.contains("getString(FactoryPaths.SourceDrift.NEW_TYPE)")
				&& !drifts.contains("getString(FactoryPaths.SourceDrift.NEW_DATA_TYPE)"));

		// F3-105: the switch guards decide and apply, and the planner
		SourceDriftsSourceTest.check("decide starts with the switch", SourceDriftsSourceTest.startsWithGuard(drifts, "public static ObjectNode decide("));
		SourceDriftsSourceTest.check("apply starts with the switch", SourceDriftsSourceTest.startsWithGuard(drifts, "public static ObjectNode apply("));
		SourceDriftsSourceTest.check("the route gate refuses a closed function", SourceDriftsSourceTest.startsWithGuard(drifts, "public static String projectOrRefuse("));
		final int guard = planner.indexOf("if (!FeatureSwitches.sourceDrift())");
		SourceDriftsSourceTest.check("the planner asks the switch before diffing or recording", guard > 0 && guard < planner.indexOf("SourceDrifts.record(") && guard < planner.indexOf("SchemaDrift.diff("));
		final String closedBranch = planner.substring(guard, planner.indexOf("final List<SchemaDrift.Change> changes", guard));
		SourceDriftsSourceTest.check("closed, the planner keeps the v1.3.0 mark: DRIFTED with SchemaDrift.closedMessage, from GENERATED or LOADED only",
			closedBranch.contains("SchemaDrift.closedMessage(") && closedBranch.contains("\"DRIFTED\"") && closedBranch.contains("!\"GENERATED\".equals(status) && !\"LOADED\".equals(status)"));
		SourceDriftsSourceTest.check("closed, the planner writes no SourceDrift row", !closedBranch.contains("SourceDrifts.record(") && !closedBranch.contains("SOURCE_DRIFT"));
		SourceDriftsSourceTest.check("a closed instance does not touch a DRIFTED table (no settle code left)", !drifts.contains("settleClosed") && !drifts.contains("statusWhenClosed"));

		// F3-11 / F3-12: the steward decides with a read check on the project; no project = refused
		final String decide = resource.substring(resource.indexOf("public Response decideDrift("), resource.indexOf("public Response applyDrift("));
		SourceDriftsSourceTest.check("decide checks the project for reading (VIEW)", decide.contains("RecordGate.onRecord(FactoryRight.VIEW,") && !decide.contains("this.refused(\"POST /drift/{id}/decide\", "));
		SourceDriftsSourceTest.check("decide refuses a drift without project", decide.contains("SourceDrifts.projectOrRefuse("));
		final String apply = resource.substring(resource.indexOf("public Response applyDrift("), resource.indexOf("/** Lot C1: cancels a QUEUED job"));
		SourceDriftsSourceTest.check("apply keeps the write check on the project and refuses a drift without project",
			apply.contains("SourceDrifts.projectOrRefuse(") && apply.contains("this.refused(\"POST /drift/{id}/apply\", driftProject)"));
		SourceDriftsSourceTest.check("the table is settled with all privileges after a steward's decision",
			drifts.substring(drifts.indexOf("private static void settleTable(")).contains("pContext.setAllPrivileges(true)"));

		// F3-113: a FAILED change is a known signature
		SourceDriftsSourceTest.check("a FAILED change is not proposed a second time", drifts.contains("./status='REJECTED' or ./status='FAILED')\"))"));

		System.out.println("SourceDriftsSourceTest OK");
	}

	/** True when the method whose signature starts with {@code pSignature} calls {@code requireSourceDrift} before anything else of substance. */
	private static boolean startsWithGuard(final String pSource, final String pSignature)
	{
		final int at = pSource.indexOf(pSignature);
		if (at < 0)
		{
			return false;
		}
		final int body = pSource.indexOf('{', pSource.indexOf(')', at));
		final String head = pSource.substring(body + 1, Math.min(pSource.length(), body + 200)).trim();
		return head.startsWith("FeatureSwitches.requireSourceDrift();");
	}

	private static String read(final Path pPath) throws IOException
	{
		return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
