package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * JUnit-free: run the main, a failure throws. Orphan-connections lot (2026-10-17): a structural check on the source of {@code OrphanConnectionCleanup} (it needs a
 * live EBX repository to run) - the guarantees the lot promises: no second deletion implementation (it calls {@code ConnectionCleanup}'s two deletions and
 * writes nothing itself), only sample-file connections are read, the confirm guard runs BEFORE the first deletion, the live references are read by the deletion
 * too, and every attempt is logged. The source folder comes from {@code -Dmdmf.sourceDir} ({@code tools/test_local.sh} sets it).
 */
public final class OrphanConnectionCleanupSourceTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		final String root = System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java");
		final String src = new String(Files.readAllBytes(Paths.get(root, "com/on/ebx/factory/ebx/OrphanConnectionCleanup.java")), StandardCharsets.UTF_8);
		check("reuses ConnectionCleanup's catalogue source deletion", src.contains("ConnectionCleanup.deleteCatalogSource("));
		check("reuses ConnectionCleanup's connection deletion", src.contains("ConnectionCleanup.deleteConnection("));
		check("reuses ConnectionCleanup's gathering", src.contains("ConnectionCleanup.readConnection(") && src.contains("ConnectionCleanup.readCatalog(")
			&& src.contains("ConnectionCleanup.sourceIdsNaming("));
		check("writes nothing itself", !src.contains("doDelete") && !src.contains("ProgrammaticService") && !src.contains("doModifyContent") && !src.contains("VolumeBatchOps"));
		check("lists sample-file connections only", src.contains("\"./type=\" + CatalogReader.quote(OrphanConnections.SAMPLE_FILE)"));
		final int list = src.indexOf("public static ObjectNode list(");
		final int delete = src.indexOf("public static DeleteResult delete(");
		check("both entry points exist", list > 0 && delete > list);
		final String del = src.substring(delete, src.indexOf("private static String[] deleteOne("));
		check("the references are re-read by the deletion", del.contains("OrphanConnectionCleanup.referenced("));
		check("a referenced connection is kept", del.contains("used.contains(id)"));
		check("a non sample-file connection is kept", del.contains("OrphanConnections.SAMPLE_FILE.equals(type)"));
		final int confirm = del.indexOf("OrphanConnections.confirmRefusal(");
		final int first = del.indexOf("OrphanConnectionCleanup.deleteOne(");
		check("the confirm guard runs before the first deletion", confirm > 0 && first > confirm);
		check("the refusal returns before deleting", del.indexOf("return new DeleteResult(refusal, null);") > confirm && del.indexOf("return new DeleteResult(refusal, null);") < first);
		check("every attempt is logged to kernel.log", src.contains("LoggingCategory.getKernel().info(\"[mdm-factory] orphan connection cleanup by "));
		System.out.println("OrphanConnectionCleanupSourceTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
