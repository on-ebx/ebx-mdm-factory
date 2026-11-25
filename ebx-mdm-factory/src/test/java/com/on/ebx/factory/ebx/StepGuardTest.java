package com.on.ebx.factory.ebx;

import com.orchestranetworks.service.OperationException;

/** JUnit-free: run the main, a failure throws. A step whose body throws anything ends FAILED, once, with the reason; one already ended keeps its outcome. */
public final class StepGuardTest
{
	/** A fake step row: records what the guard did to it. */
	private static final class FakeStep implements StepGuard.Ender
	{
		boolean running;
		String endedMessage;
		String endedDetail;
		int ends;
		int logs;
		Throwable logged;
		boolean readFails;
		boolean endFails;

		FakeStep(final boolean pRunning)
		{
			this.running = pRunning;
		}

		@Override
		public boolean isRunning()
		{
			if (this.readFails)
			{
				throw new IllegalStateException("cannot read the step");
			}
			return this.running;
		}

		@Override
		public void endFailed(final String pMessage, final String pDetailJson)
		{
			if (this.endFails)
			{
				throw new IllegalStateException("cannot write the step");
			}
			this.ends++;
			this.running = false;
			this.endedMessage = pMessage;
			this.endedDetail = pDetailJson;
		}

		@Override
		public void log(final String pMessage, final Throwable pCause)
		{
			this.logs++;
			this.logged = pCause;
		}
	}

	public static void main(final String[] pArgs)
	{
		StepGuardTest.anErrorEndsTheStepAndKeepsTheStack();
		StepGuardTest.aRuntimeExceptionEndsTheStep();
		StepGuardTest.anExpectedRefusalEndsTheStepWithItsOwnMessageAndNoStackLog();
		StepGuardTest.aStepAlreadyEndedKeepsItsOutcome();
		StepGuardTest.aFailingBookkeepingNeverReplacesTheCallersException();
		StepGuardTest.theMessageNamesTheClassOfWhatBroke();
		StepGuardTest.theCatchPatternRethrowsWhatItCaught();
		System.out.println("StepGuardTest OK");
	}

	private static void anErrorEndsTheStepAndKeepsTheStack()
	{
		final FakeStep step = new FakeStep(true);
		final Error error = new VerifyError("Bad type on operand stack");
		final boolean ended = StepGuard.handle(step, error, "{\"tables\":[]}");
		StepGuardTest.check("ended by the guard", ended && step.ends == 1 && !step.running);
		StepGuardTest.check("FAILED message names the error: " + step.endedMessage, step.endedMessage.contains("java.lang.VerifyError") && step.endedMessage.contains("Bad type"));
		StepGuardTest.check("the detail goes along", "{\"tables\":[]}".equals(step.endedDetail));
		StepGuardTest.check("the stack is logged once, with the throwable itself", step.logs == 1 && step.logged == error);
	}

	private static void aRuntimeExceptionEndsTheStep()
	{
		final FakeStep step = new FakeStep(true);
		StepGuardTest.check("ended", StepGuard.handle(step, new IllegalStateException("boom"), null) && step.endedMessage.equals("Unexpected error: java.lang.IllegalStateException: boom"));
	}

	private static void anExpectedRefusalEndsTheStepWithItsOwnMessageAndNoStackLog()
	{
		final FakeStep step = new FakeStep(true);
		StepGuard.handle(step, OperationException.createError("Project X has no staging zone"), null);
		StepGuardTest.check("its own message: " + step.endedMessage, "Project X has no staging zone".equals(step.endedMessage));
		StepGuardTest.check("an expected refusal is not logged as a failure", step.logs == 0);
	}

	private static void aStepAlreadyEndedKeepsItsOutcome()
	{
		final FakeStep step = new FakeStep(false);
		StepGuardTest.check("not ended again", !StepGuard.handle(step, new IllegalStateException("after the normal end"), null) && step.ends == 0);
		StepGuardTest.check("still logged: the failure is real even though the step had its outcome", step.logs == 1);
	}

	private static void aFailingBookkeepingNeverReplacesTheCallersException()
	{
		final FakeStep unreadable = new FakeStep(true);
		unreadable.readFails = true;
		StepGuardTest.check("an unreadable step: no throw, not ended", !StepGuard.handle(unreadable, new Error("x"), null));
		final FakeStep unwritable = new FakeStep(true);
		unwritable.endFails = true;
		StepGuardTest.check("an unwritable step: no throw, not ended", !StepGuard.handle(unwritable, new Error("x"), null));
	}

	private static void theMessageNamesTheClassOfWhatBroke()
	{
		StepGuardTest.check("no message", "Unexpected error: java.lang.NullPointerException".equals(StepGuard.message(new NullPointerException())));
		StepGuardTest.check("a refusal without text", StepGuard.message(OperationException.createError("")).startsWith("Failed: "));
	}

	/** The shape every step body uses: the guard ends the step, the exception still reaches the caller untouched. */
	private static void theCatchPatternRethrowsWhatItCaught()
	{
		final FakeStep step = new FakeStep(true);
		final LinkageError original = new LinkageError("after an upgrade");
		Throwable received = null;
		try
		{
			StepGuardTest.body(step, original);
		}
		catch (final Throwable ex)
		{
			received = ex;
		}
		StepGuardTest.check("the very same Error is rethrown", received == original);
		StepGuardTest.check("and the step ended FAILED", step.ends == 1 && step.endedMessage.contains("LinkageError"));
	}

	private static void body(final FakeStep pStep, final Error pToThrow)
	{
		try
		{
			throw pToThrow;
		}
		catch (final RuntimeException | Error ex)
		{
			StepGuard.handle(pStep, ex, null);
			throw ex;
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
