package com.on.ebx.factory.ebx;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import com.on.ebx.factory.core.FactoryPaths;
import com.orchestranetworks.schema.Path;

/** JUnit-free: run the main, a failure throws. Who may write a job row, and that no field of the table escapes the creation lock. */
public final class FactoryJobAccessRuleTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		FactoryJobAccessRuleTest.check("an ordinary user may not write a job row", !FactoryJobAccessRule.writable(false, false));
		FactoryJobAccessRuleTest.check("an administrator may", FactoryJobAccessRule.writable(true, false));
		FactoryJobAccessRuleTest.check("a system session may (the purge)", FactoryJobAccessRule.writable(false, true));

		// every field of the table is in the creation lock: a field left out would let a row be created through it
		final Set<String> declared = new HashSet<String>();
		for (final Field field : FactoryPaths.FactoryJob.class.getDeclaredFields())
		{
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == Path.class)
			{
				declared.add(((Path) field.get(null)).format());
			}
		}
		final Set<String> locked = new HashSet<String>();
		for (final Path path : FactoryJobAccessRule.CREATE_LOCKED_FIELDS)
		{
			locked.add(path.format());
		}
		FactoryJobAccessRuleTest.check("the creation lock covers every FactoryJob field: " + declared + " vs " + locked, declared.equals(locked) && declared.size() == 14);
		FactoryJobAccessRuleTest.check("the absolute node path of a field", "/root/FactoryJob/requestedBy".equals(FactoryPaths.FACTORY_JOB.add(FactoryPaths.FactoryJob.REQUESTED_BY).format()));
		System.out.println("FactoryJobAccessRuleTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
