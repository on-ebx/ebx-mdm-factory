package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.orchestranetworks.service.Profile;

/**
 * Audit F2-08 (lot H). JUnit-free: a failure throws. The directory listing goes through a class the public Javadoc does not list
 * ({@code ProfileListContextBridge}); when it is absent the dropdown must fall back to "Everyone" and the default role names, never to an empty list.
 */
public final class FactoryRoleEnumerationFallbackTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		FactoryRoleEnumerationFallbackTest.absentBridgeClass_throwsSoTheCallerFallsBack();
		FactoryRoleEnumerationFallbackTest.absentContextClass_throws();
		FactoryRoleEnumerationFallbackTest.emptyListing_offersEveryoneAndTheDefaultRoles();
		FactoryRoleEnumerationFallbackTest.listedRoles_alwaysGainEveryone_andStaySorted();
		System.out.println("FactoryRoleEnumerationFallbackTest OK");
	}

	private static void absentBridgeClass_throwsSoTheCallerFallsBack() throws Exception
	{
		boolean thrown = false;
		try
		{
			FactoryRoleEnumeration.listDirectoryProfiles(null, "com.orchestranetworks.service.directory.NoSuchBridge", FactoryRoleEnumeration.CONTEXT_CLASS);
		}
		catch (final ClassNotFoundException ex)
		{
			thrown = true;
		}
		FactoryRoleEnumerationFallbackTest.check("a missing bridge class is reported, not swallowed inside the listing", thrown);
	}

	private static void absentContextClass_throws() throws Exception
	{
		boolean thrown = false;
		try
		{
			FactoryRoleEnumeration.listDirectoryProfiles(null, FactoryRoleEnumeration.BRIDGE_CLASS, "com.orchestranetworks.service.directory.NoSuchContext");
		}
		catch (final Exception ex)
		{
			thrown = true; // the bridge itself may be absent from the test classpath too: either way the listing refuses
		}
		FactoryRoleEnumerationFallbackTest.check("a missing context class is reported", thrown);
	}

	private static void emptyListing_offersEveryoneAndTheDefaultRoles()
	{
		final List<String> values = FactoryRoleEnumeration.offered(Collections.<Profile> emptyList());
		FactoryRoleEnumerationFallbackTest.check("Everyone offered", values.contains(Profile.EVERYONE.format()));
		FactoryRoleEnumerationFallbackTest.check("steward role offered", values.contains(Profile.forSpecificRole(FactoryAccess.stewardRole()).format()));
		FactoryRoleEnumerationFallbackTest.check("never empty", values.size() >= 3);
	}

	private static void listedRoles_alwaysGainEveryone_andStaySorted()
	{
		final List<Profile> listed = Arrays.asList(Profile.forSpecificRole("Zeta"), Profile.forSpecificRole("Alpha"));
		final List<String> values = FactoryRoleEnumeration.offered(listed);
		FactoryRoleEnumerationFallbackTest.check("three values", values.size() == 3);
		FactoryRoleEnumerationFallbackTest.check("Everyone added", values.contains(Profile.EVERYONE.format()));
		FactoryRoleEnumerationFallbackTest.check("sorted", values.get(0).compareToIgnoreCase(values.get(1)) <= 0 && values.get(1).compareToIgnoreCase(values.get(2)) <= 0);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
