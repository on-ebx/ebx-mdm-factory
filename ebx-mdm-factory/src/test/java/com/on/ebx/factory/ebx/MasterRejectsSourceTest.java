package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * JUnit-free: run the main, a failure throws. EBX refusal classes lot H4 (2026-10-17): what needs a repository to run is pinned by reading the engine source - the MASTER
 * stage writes CODED reasons (the staging stage's {@code RejectReason}), with their category, signature and raw detail, classifies what EBX refuses with the same
 * {@code RejectClassifier}, cascades a rejected parent to its children, and reports why in the load result; the staging attempts leave the trigger / constraint header.
 */
public final class MasterRejectsSourceTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		final String root = System.getProperty("mdmf.sourceDir", "src/main/java") + "/com/on/ebx/factory/ebx/";
		final String master = new String(Files.readAllBytes(Paths.get(root + "MasterLoader.java")), StandardCharsets.UTF_8);
		final String staging = new String(Files.readAllBytes(Paths.get(root + "StagingLoader.java")), StandardCharsets.UTF_8);
		final String reader = new String(Files.readAllBytes(Paths.get(root + "RejectCauseReader.java")), StandardCharsets.UTF_8);

		MasterRejectsSourceTest.check("master rejects hold coded reasons", master.contains("final List<RejectReason> reasons;") && !master.contains("final List<String> reasons;"));
		MasterRejectsSourceTest.check("no more free-text master reasons",
			!master.contains("\"unknown parent \"") && !master.contains("\"EBX refused the save: \" + rejected.reason") && !master.contains("rule.reasonLabel + \": \" + firingMessage"));
		MasterRejectsSourceTest.check("what EBX refuses at master goes through the classifier, with the master fields (staging column)",
			master.contains("RejectClassifier.refusal(rejected.reason, pFieldInfos, rejected.item.fields)") && master.contains("RejectReason.stagingColumn(stagingName)"));
		MasterRejectsSourceTest.check("a parent not at master is told apart from a rejected parent",
			master.contains("pParentKeys.parentReason(field.masterField, stagingRefField, field.targetEntityName, rawRef)") && master.contains("this.rejectedParents.forChild("));
		MasterRejectsSourceTest.check("the rejects of a batch are remembered for the entities that follow", master.contains("pParentKeys.recordRejected(pEntityName, reject.stagingKey, reject.first())"));
		MasterRejectsSourceTest.check("a script rule is a named constraint of the quality category",
			master.contains("RejectReason.constraint(RejectCategory.QUALITY_RULE, rule.reasonLabel, firingMessage, \"\")"));
		MasterRejectsSourceTest.check("stored like staging's: reasons with markers, category, signature, detail",
			master.contains("RejectReasons.store(reject.reasons, TextLimits.MESSAGE_MAX_LENGTH)") && master.contains("FactoryPaths.TransferReject.CATEGORY")
				&& master.contains("FactoryPaths.TransferReject.REASON_CODE") && master.contains("FactoryPaths.TransferReject.DETAIL"));
		MasterRejectsSourceTest.check("the load result says why, per entity", master.contains("entityDetail.putObject(\"rejectedBy\")") && master.contains("rejectedByCategory"));
		MasterRejectsSourceTest.check("the attempts keep the trigger / constraint header of the exception",
			master.contains("RefusalHints.header(result.getException()) + MasterLoader.shortText(") && staging.contains("RefusalHints.header(result.getException()) + RejectText.cut("));
		MasterRejectsSourceTest.check("staging gives the classifier the length, the mandatory flag and the target entity",
			staging.contains("field.maxLength, field.mandatory, field.targetEntityName"));
		MasterRejectsSourceTest.check("the master causes are read like the staging ones", reader.contains("public static Causes master(") && reader.contains("\"MASTER\""));
		System.out.println("MasterRejectsSourceTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
