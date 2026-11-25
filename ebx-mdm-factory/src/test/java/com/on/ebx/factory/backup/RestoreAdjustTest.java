package com.on.ebx.factory.backup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JUnit-free (F2-16, F2-03): a restored archive never starts a task, whatever the rows say. */
public final class RestoreAdjustTest
{
	public static void main(final String[] pArgs)
	{
		for (final String status : new String[] { "QUEUED", "RUNNING" })
		{
			final Map<String, Object> forged = new LinkedHashMap<String, Object>();
			forged.put("status", status);
			forged.put("kind", "PROJECT_DELETE");
			forged.put("requestedBy", "some-administrator");
			final List<String> notes = new ArrayList<String>();
			RestoreAdjust.apply("FactoryJob", forged, notes);
			RestoreAdjustTest.check("a forged " + status + " job becomes INTERRUPTED, never QUEUED", "INTERRUPTED".equals(forged.get("status")));
			RestoreAdjustTest.check("... and names nobody", forged.get("requestedBy") == null && notes.size() == 1);
		}
		final Map<String, Object> done = new LinkedHashMap<String, Object>();
		done.put("status", "DONE");
		done.put("requestedBy", "alice");
		RestoreAdjust.apply("FactoryJob", done, new ArrayList<String>());
		RestoreAdjustTest.check("a finished job keeps its history", "DONE".equals(done.get("status")) && "alice".equals(done.get("requestedBy")));
		System.out.println("RestoreAdjustTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
