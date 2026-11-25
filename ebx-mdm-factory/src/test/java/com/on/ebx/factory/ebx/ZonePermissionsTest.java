package com.on.ebx.factory.ebx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * JUnit-free: run the main, a failure throws. X3-11 (1.4.3): where a NEW generated zone copies its permissions from. The decision is pure ({@link ZonePermissions#resolve});
 * HomeCreationSpec and Repository cannot be built offline, so the call sites are read as source.
 */
public final class ZonePermissionsTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		for (final String unset : new String[] { null, "", "   " })
		{
			final ZonePermissions.Decision d = ZonePermissions.resolve(unset, "Reference", false);
			ZonePermissionsTest.check("unset [" + unset + "] = the PARENT's permissions", d.mode == ZonePermissions.Mode.PARENT && "Reference".equals(d.from));
		}
		final ZonePermissions.Decision otherParent = ZonePermissions.resolve(null, "Sandbox", false);
		ZonePermissionsTest.check("the parent is the one given, not Reference", otherParent.mode == ZonePermissions.Mode.PARENT && "Sandbox".equals(otherParent.from));
		final ZonePermissions.Decision named = ZonePermissions.resolve(" Template ", "Reference", true);
		ZonePermissionsTest.check("a key naming a dataspace = that dataspace, over the default", named.mode == ZonePermissions.Mode.NAMED && "Template".equals(named.from));
		for (final String none : new String[] { "none", "NONE", " None " })
		{
			final ZonePermissions.Decision d = ZonePermissions.resolve(none, "Reference", true);
			ZonePermissionsTest.check("[" + none + "] = nothing copied", d.mode == ZonePermissions.Mode.NONE && d.from == null);
		}
		final ZonePermissions.Decision missing = ZonePermissions.resolve("Typo", "Reference", false);
		ZonePermissionsTest.check("a key naming no dataspace = nothing copied, not the parent's (the explicit setting is not replaced silently)",
			missing.mode == ZonePermissions.Mode.NAMED_MISSING && "Typo".equals(missing.from));
		ZonePermissionsTest.check("labels", "PARENT=Reference".equals(ZonePermissions.label(ZonePermissions.resolve(null, "Reference", false))) && "NONE".equals(ZonePermissions.label(ZonePermissions.resolve("none", "R", false)))
			&& "NAMED=Template".equals(ZonePermissions.label(named)) && "NAMED_MISSING=Typo".equals(ZonePermissions.label(missing)));
		ZonePermissionsTest.check("descriptions say where the permissions come from", ZonePermissions.describe(ZonePermissions.resolve(null, "Reference", false)).contains("parent dataspace (Reference)")
			&& ZonePermissions.describe(named).contains("Template") && ZonePermissions.describe(missing).contains("names no dataspace") && ZonePermissions.describe(ZonePermissions.resolve("none", "R", false)).contains("=none"));
		ZonePermissionsTest.check("the property name is unchanged", "ebx.factory.zonePermissionsFrom".equals(FactoryAccess.PROPERTY_ZONE_PERMISSIONS));

		// every site that creates a zone gives its parent to the decision, from the SAME object it gives to setParent
		for (final String generator : new String[] { "LandingGenerator", "StagingGenerator", "MasterGenerator", "DomainGenerator" })
		{
			final String source = ZonePermissionsTest.read(generator);
			ZonePermissionsTest.check(generator + ": the parent given to setParent is the one the permissions are copied from",
				source.contains("spec.setParent(parentHome);") && source.contains("FactoryAccess.copyZonePermissions(spec, repository, parentHome)") && source.contains("\"permissionsCopiedFrom\""));
		}
		final String installer = ZonePermissionsTest.read("FactoryInstaller");
		ZonePermissionsTest.check("FactoryInstaller (the module's own dataspace, not a generated zone) does not copy permissions", !installer.contains("copyZonePermissions"));
		final String startup = ZonePermissionsTest.read("FactoryModuleRegistration");
		ZonePermissionsTest.check("the log says once at start where new zones copy their permissions", startup.contains("ZonePermissions.describe("));
		final String status = new String(Files.readAllBytes(Paths.get(System.getProperty("mdmf.sourceDir"), "com/on/ebx/factory/rest/FactoryResource.java")), StandardCharsets.UTF_8);
		ZonePermissionsTest.check("/status carries zonePermissions", status.contains("\"zonePermissions\"") && status.contains("ZonePermissions.statusJson("));
		System.out.println("ZonePermissionsTest OK");
	}

	private static String read(final String pClass) throws Exception
	{
		return new String(Files.readAllBytes(Paths.get(System.getProperty("mdmf.sourceDir"), "com/on/ebx/factory/ebx/" + pClass + ".java")), StandardCharsets.UTF_8);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new IllegalStateException("FAILED: " + pWhat);
		}
	}
}
