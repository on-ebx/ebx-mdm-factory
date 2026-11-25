package com.on.ebx.factory.ebx;

import java.util.Locale;

import com.on.ebx.factory.ebx.ui.Messages;

/**
 * JUnit-free: run the main, a failure throws. F3-13 (1.4.2): the style of a project is closed at creation for every permission-checked path (Data Services, the table's
 * form), open to the wizard for an open style only, and the refusals are coded and bilingual.
 */
public final class ProjectStyleAccessRuleTest
{
	public static void main(final String[] pArgs)
	{
		// no ticket: nobody creates a project with a style through a permission-checked path - a person (not platform, not system) is refused whatever the style
		ProjectStyleAccessRuleTest.check("a person without a ticket cannot set the style at creation", !ProjectStyleAccessRule.writableOnCreate(false, false, null));
		ProjectStyleAccessRuleTest.check("a ticket for a style that is not open does not exist (CENTRALISATION)", ProjectStyleAccessRuleTest.opens("CENTRALISATION") == false);
		ProjectStyleAccessRuleTest.check("... nor for an unknown or empty style", ProjectStyleAccessRuleTest.opens("MADEUP") == false && ProjectStyleAccessRuleTest.opens(null) == false);
		ProjectStyleAccessRuleTest.check("a ticket for CONSOLIDATION is granted", ProjectStyleAccessRuleTest.opens("CONSOLIDATION"));
		ProjectStyleAccessRuleTest.check("the wizard holding a CONSOLIDATION ticket writes", ProjectStyleAccessRule.writableOnCreate(false, false, "CONSOLIDATION"));
		ProjectStyleAccessRuleTest.check("a forged ticket value for a closed style does not write", !ProjectStyleAccessRule.writableOnCreate(false, false, "CENTRALISATION"));
		ProjectStyleAccessRuleTest.check("the platform and the system user write", ProjectStyleAccessRule.writableOnCreate(true, false, null) && ProjectStyleAccessRule.writableOnCreate(false, true, null));
		ProjectStyleAccessRuleTest.check("modification: only the platform or the system user", !ProjectStyleAccessRule.writableOnModify(false, false)
			&& ProjectStyleAccessRule.writableOnModify(true, false) && ProjectStyleAccessRule.writableOnModify(false, true));
		// the ticket ends with its try block and is per thread
		try (ProjectStyleAccessRule.Ticket ticket = ProjectStyleAccessRule.open("CONSOLIDATION"))
		{
			ProjectStyleAccessRuleTest.check("a ticket object exists inside the block", ticket != null);
		}
		for (final String key : new String[] { "access.style.closed", "access.style.fixed" })
		{
			final String en = Messages.get(com.on.ebx.factory.ebx.ui.AboutServiceDeclaration.class, Locale.ENGLISH, key);
			final String fr = Messages.get(com.on.ebx.factory.ebx.ui.AboutServiceDeclaration.class, Locale.FRENCH, key);
			ProjectStyleAccessRuleTest.check(key + " exists in English and French, coded", !key.equals(en) && !key.equals(fr) && !en.equals(fr) && en.startsWith("MDMF-STYLE-") && fr.startsWith("MDMF-STYLE-"));
		}
		ProjectStyleAccessRuleTest.check("a restore refuses an archive of a Centralisation project, coded", ProjectRestore.styleRefusal("CENTRALISATION") != null
			&& ProjectRestore.styleRefusal("CENTRALISATION").startsWith(ProjectRestore.STYLE_CODE));
		ProjectStyleAccessRuleTest.check("a restore accepts Consolidation or an archive without a style", ProjectRestore.styleRefusal("CONSOLIDATION") == null && ProjectRestore.styleRefusal("") == null);
		// the wiring, read from the source (an access rule cannot be exercised without a repository): both the modification and the creation rule name the style node,
		// and the wizard is the one writer
		final String extensions = ProjectStyleAccessRuleTest.source("com/on/ebx/factory/ebx/FactorySchemaExtensions.java");
		ProjectStyleAccessRuleTest.check("FactorySchemaExtensions closes Project/style at creation", extensions.contains("setAccessRuleForCreateOnNode(com.on.ebx.factory.core.FactoryPaths.PROJECT.add(com.on.ebx.factory.core.FactoryPaths.Project.STYLE), projectStyle)"));
		ProjectStyleAccessRuleTest.check("... and at modification", extensions.contains("setAccessRuleOnNode(com.on.ebx.factory.core.FactoryPaths.PROJECT.add(com.on.ebx.factory.core.FactoryPaths.Project.STYLE), projectStyle)"));
		ProjectStyleAccessRuleTest.check("the wizard's save opens the ticket", ProjectStyleAccessRuleTest.source("com/on/ebx/factory/ebx/WizardData.java").contains("ProjectStyleAccessRule.open("));
		System.out.println("ProjectStyleAccessRuleTest OK");
	}

	private static String source(final String pRelative)
	{
		try
		{
			return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("mdmf.sourceDir"), pRelative)), java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (final java.io.IOException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	private static boolean opens(final String pStyle)
	{
		try (ProjectStyleAccessRule.Ticket ticket = ProjectStyleAccessRule.open(pStyle))
		{
			return ticket != null;
		}
		catch (final IllegalArgumentException ex)
		{
			return false;
		}
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new IllegalStateException("FAILED: " + pWhat);
		}
	}
}
