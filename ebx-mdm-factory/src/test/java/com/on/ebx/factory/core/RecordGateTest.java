package com.on.ebx.factory.core;

import com.on.ebx.factory.core.RecordGate.Verdict;

/** JUnit-free: run the main, a failure throws. The decisions of {@link RecordGate} (second permissions pass, 2026-10-14). */
public final class RecordGateTest
{
	public static void main(final String[] pArgs)
	{
		// a hidden record is answered like an absent one, whatever the action
		for (final FactoryRight right : FactoryRight.values())
		{
			RecordGateTest.check("absent " + right, RecordGate.onRecord(right, false, false, false) == Verdict.UNKNOWN);
			RecordGateTest.check("hidden " + right, RecordGate.onRecord(right, true, false, false) == Verdict.UNKNOWN);
			RecordGateTest.check("hidden but 'writable' " + right, RecordGate.onRecord(right, true, false, true) == Verdict.UNKNOWN);
		}
		// looking needs visibility only
		RecordGateTest.check("view read-only", RecordGate.onRecord(FactoryRight.VIEW, true, true, false) == Verdict.ALLOW);
		RecordGateTest.check("authenticated read-only", RecordGate.onRecord(FactoryRight.AUTHENTICATED, true, true, false) == Verdict.ALLOW);
		// writing needs read-write on the record, even for the administrator's Factory right
		RecordGateTest.check("steward on read-only", RecordGate.onRecord(FactoryRight.STEWARD, true, true, false) == Verdict.READ_ONLY);
		RecordGateTest.check("design on read-only", RecordGate.onRecord(FactoryRight.DESIGN, true, true, false) == Verdict.READ_ONLY);
		RecordGateTest.check("admin on read-only", RecordGate.onRecord(FactoryRight.ADMINISTRATOR, true, true, false) == Verdict.READ_ONLY);
		RecordGateTest.check("design on writable", RecordGate.onRecord(FactoryRight.DESIGN, true, true, true) == Verdict.ALLOW);
		RecordGateTest.check("readsOnly", RecordGate.readsOnly(FactoryRight.VIEW) && RecordGate.readsOnly(FactoryRight.AUTHENTICATED)
			&& !RecordGate.readsOnly(FactoryRight.STEWARD) && !RecordGate.readsOnly(null));
		// the wizard before a project exists
		RecordGateTest.check("create without right", RecordGate.onWizardAction(FactoryRight.DESIGN, true, "", false, false, false, false) == Verdict.NO_CREATE);
		RecordGateTest.check("create with right", RecordGate.onWizardAction(FactoryRight.DESIGN, true, null, false, false, false, true) == Verdict.ALLOW);
		RecordGateTest.check("no project, other action", RecordGate.onWizardAction(FactoryRight.VIEW, false, " ", false, false, false, false) == Verdict.ALLOW);
		RecordGateTest.check("existing project saved on read-only", RecordGate.onWizardAction(FactoryRight.DESIGN, true, "P1", true, true, false, true) == Verdict.READ_ONLY);
		RecordGateTest.check("existing hidden project", RecordGate.onWizardAction(FactoryRight.VIEW, false, "P1", true, false, false, true) == Verdict.UNKNOWN);
		// statuses
		RecordGateTest.check("404 for unknown", RecordGate.httpStatus(Verdict.UNKNOWN) == 404);
		RecordGateTest.check("403 for read-only", RecordGate.httpStatus(Verdict.READ_ONLY) == 403 && RecordGate.httpStatus(Verdict.NO_CREATE) == 403);
		RecordGateTest.check("200 for allow", RecordGate.httpStatus(Verdict.ALLOW) == 200);
		System.out.println("RecordGateTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
