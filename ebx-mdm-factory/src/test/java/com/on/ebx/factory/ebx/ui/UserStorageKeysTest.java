package com.on.ebx.factory.ebx.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import com.on.ebx.factory.wizard.WizardPage;

/**
 * JUnit-free: run the main, a failure throws. Wave 13 (security): browser storage of the Factory screens is keyed by the EBX user. The behaviour of the key
 * library itself is tested in {@code tools/userstore_test.js} (JavaScriptCore); this class pins the Java side: the login reaches every script that touches
 * storage and the container attributes, and no script still talks to {@code sessionStorage}/{@code localStorage} directly.
 */
public final class UserStorageKeysTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		UserStorageKeysTest.loginIsWrittenAsAnEscapedAttribute();
		UserStorageKeysTest.everyOriginScriptOpensTheStoreOfTheLogin();
		UserStorageKeysTest.aHostileLoginStaysInsideItsJsLiteral();
		UserStorageKeysTest.facetedScriptsKeepTheirLayoutPerUser();
		UserStorageKeysTest.explorerAndWizardRootsCarryTheLogin();
		UserStorageKeysTest.jobNoticeScriptBringsTheLibrary();
		UserStorageKeysTest.noSourceTouchesTheBrowserStorageDirectly();
		UserStorageKeysTest.dumpOriginScriptsForTheBrowserTest();
		System.out.println("UserStorageKeysTest OK");
	}

	private static void loginIsWrittenAsAnEscapedAttribute()
	{
		UserStorageKeysTest.check("attribute with the login", " data-user=\"alice\"".equals(FactoryHtml.userAttr("alice")));
		UserStorageKeysTest.check("the login is escaped", " data-user=\"a&quot;&gt;b\"".equals(FactoryHtml.userAttr("a\">b")));
		UserStorageKeysTest.check("no login, no attribute", FactoryHtml.userAttr("").isEmpty() && FactoryHtml.userAttr(null).isEmpty());
		UserStorageKeysTest.check("no session, no login", FactoryHtml.userId(null).isEmpty());
	}

	private static void everyOriginScriptOpensTheStoreOfTheLogin()
	{
		final String[] scripts = {
			FactoryHtml.originCaptureScript("alice", ".x"),
			FactoryHtml.originCaptureScript("alice", ".x", java.util.Arrays.asList("/u1")),
			FactoryHtml.originCaptureDelegatedScript("alice", ".root", ".x"),
			FactoryHtml.wizardOriginKeepScript("alice", "P1"),
			FactoryHtml.originReturnScript("alice"),
			FactoryHtml.originReturnScript("alice", "/ebx-ui/ui/advanced/data/x") };
		for (final String script : scripts)
		{
			UserStorageKeysTest.check("opens the store of the login first", script.contains("window.mdmfStore.forUser('alice').session"));
			UserStorageKeysTest.check("carries the library (idempotent)", script.startsWith("(function () {") && script.contains("window.mdmfStore = {") && !script.contains("/*"));
			UserStorageKeysTest.check("never touches the storage areas itself", !script.contains("sessionStorage.") && !script.contains("localStorage."));
		}
		UserStorageKeysTest.check("keys are names, the library adds the namespace and the login", "origin".equals(FactoryHtml.ORIGIN_KEY) && "wizardOrigin".equals(FactoryHtml.WIZARD_ORIGIN_KEY));
		UserStorageKeysTest.check("the token handed from the page we leave is read and deleted at once (take)",
			FactoryHtml.originCaptureScript("alice", ".x", java.util.Arrays.asList("/u1")).contains("ST.take(key)"));
	}

	private static void aHostileLoginStaysInsideItsJsLiteral()
	{
		final String script = FactoryHtml.originReturnScript("o'brien </script>");
		UserStorageKeysTest.check("quote escaped", script.contains("forUser('o\\'brien"));
		UserStorageKeysTest.check("no raw script close tag after the library", script.indexOf("</script>") < 0);
	}

	private static void facetedScriptsKeepTheirLayoutPerUser()
	{
		final FacetedPage page = new FacetedPage(Locale.ENGLISH);
		final String[] scripts = { page.script(".mdmc-explorer", "mdmxSearch", "mdmxSort", "source", "zone"), page.serverScript(".mdmc-explorer", "mdmxSearch", "mdmxSort", "failed") };
		for (final String script : scripts)
		{
			UserStorageKeysTest.check("no direct localStorage", !script.contains("localStorage.") && !script.contains("sessionStorage."));
			UserStorageKeysTest.check("the store comes from the root's data-user", script.contains("window.mdmfStore.forUser(root.dataset.user||'').local"));
			UserStorageKeysTest.check("rail and more-filters preferences go through it", script.contains("ST.set('rail'") && script.contains("ST.get('rail')")
				&& script.contains("ST.set('moreOpen'") && script.contains("ST.get('moreOpen')"));
		}
	}

	private static void explorerAndWizardRootsCarryTheLogin()
	{
		final ProjectExplorerPage.Model explorer = new ProjectExplorerPage.Model();
		explorer.locale = Locale.ENGLISH;
		explorer.user = "alice";
		UserStorageKeysTest.check("explorer root has data-user", ProjectExplorerPage.render(explorer).contains("<div class=\"mdmf mdmc-explorer mdmx\" data-user=\"alice\""));
		explorer.user = "";
		UserStorageKeysTest.check("explorer without a login has none", !ProjectExplorerPage.render(explorer).contains("data-user="));
		final WizardPage.Model wizard = new WizardPage.Model();
		wizard.user = "a<b";
		final String html = WizardPage.render(wizard);
		UserStorageKeysTest.check("wizard root has data-mdmf-user, escaped", html.contains("class=\"mdmf mdmf-wizard\" data-mdmf-user=\"a&lt;b\""));
	}

	private static void jobNoticeScriptBringsTheLibrary()
	{
		final String script = JobNotice.script(Locale.ENGLISH);
		UserStorageKeysTest.check("library before the notice", script.indexOf("window.mdmfStore = {") >= 0 && script.indexOf("window.mdmfStore = {") < script.indexOf("window.mdmfJobNotice = {"));
		UserStorageKeysTest.check("the notice has no direct storage access", !FactoryHtml.resourceText("jobnotice.js").contains("localStorage."));
	}

	/** The only file allowed to name the storage areas is userstore.js; the others (Java strings, wizard.js, jobnotice.js) go through window.mdmfStore. */
	private static void noSourceTouchesTheBrowserStorageDirectly() throws Exception
	{
		final String sourceDir = System.getProperty("mdmf.sourceDir");
		if (sourceDir == null || sourceDir.isEmpty())
		{
			return;
		}
		final Path main = Paths.get(sourceDir).resolve("..").normalize();
		int scanned = 0;
		try (java.util.stream.Stream<Path> files = Files.walk(main))
		{
			for (final Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".js"))::iterator)
			{
				if (file.getFileName().toString().equals("userstore.js"))
				{
					continue;
				}
				final String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
				scanned++;
				for (final String call : new String[] { "sessionStorage.getItem", "sessionStorage.setItem", "sessionStorage.removeItem", "localStorage.getItem", "localStorage.setItem",
					"localStorage.removeItem", "indexedDB.open", "document.cookie=" })
				{
					UserStorageKeysTest.check(file.getFileName() + " calls " + call + " directly", !text.contains(call));
				}
			}
		}
		UserStorageKeysTest.check("sources were found", scanned > 100);
	}

	/** The origin scripts of two logins, for tools/userstore_test.js (run in JavaScriptCore against a stand-in window: one login never meets the other's token). */
	private static void dumpOriginScriptsForTheBrowserTest() throws Exception
	{
		final String dir = System.getProperty("mdmf.jsDump");
		if (dir == null || dir.isEmpty())
		{
			return;
		}
		Files.createDirectories(Paths.get(dir));
		for (final String user : new String[] { "alice", "bob" })
		{
			Files.write(Paths.get(dir, "origin-capture-" + user + ".js"), FactoryHtml.originCaptureScript(user, ".mdmc-btn").getBytes(java.nio.charset.StandardCharsets.UTF_8));
			Files.write(Paths.get(dir, "origin-keep-" + user + ".js"), FactoryHtml.wizardOriginKeepScript(user, "P1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
			// staging load rejects (2026-10-17): the transfer-reject screen's own "back" link follows the same token
			Files.write(Paths.get(dir, "origin-back-" + user + ".js"), FactoryHtml.originBackLinkScript(user, "#mdmfRjBack").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new IllegalStateException("FAILED: " + pWhat);
		}
	}
}
