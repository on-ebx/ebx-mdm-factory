package com.on.ebx.factory.matching;

/** JUnit-free: run the main, a failure throws. Matching-live lot (2026-09-27). */
public final class MatchingActivityTest
{
	public static void main(final String[] pArgs)
	{
		MatchingActivityTest.statusesMapToThePanelWords();
		MatchingActivityTest.jsonRoundTrip();
		MatchingActivityTest.garbageIsNoAction();
		System.out.println("MatchingActivityTest OK");
	}

	private static void statusesMapToThePanelWords()
	{
		MatchingActivityTest.check("DONE", MatchingActivity.DONE.equals(MatchingActivity.statusOf("DONE")));
		MatchingActivityTest.check("FAILED", MatchingActivity.FAILED.equals(MatchingActivity.statusOf("FAILED")));
		MatchingActivityTest.check("a server restart reads as failed", MatchingActivity.FAILED.equals(MatchingActivity.statusOf("INTERRUPTED")));
		MatchingActivityTest.check("a stop reads as stopped", MatchingActivity.STOPPED.equals(MatchingActivity.statusOf("STOPPED")));
		MatchingActivityTest.check("RUNNING and unknown read as running",
			MatchingActivity.RUNNING.equals(MatchingActivity.statusOf("RUNNING")) && MatchingActivity.RUNNING.equals(MatchingActivity.statusOf(null)));
	}

	private static void jsonRoundTrip()
	{
		final MatchingActivity activity = new MatchingActivity();
		activity.kind = MatchingActivity.RUN;
		activity.status = MatchingActivity.RUNNING;
		activity.startedAt = 1000L;
		final MatchingActivity.Entity personne = new MatchingActivity.Entity();
		personne.name = "Personne";
		personne.status = MatchingActivity.DONE;
		personne.startedAt = 1100L;
		personne.endedAt = 3100L;
		personne.message = "Matching run on Personne finished (DONE); Xref.goldenKey refreshed (3 set, 0 cleared, 177 unchanged).";
		activity.entities.add(personne);
		final MatchingActivity.Entity adresse = new MatchingActivity.Entity();
		adresse.name = "Adresse";
		adresse.startedAt = 3200L;
		activity.entities.add(adresse);
		// lot M6 (2026-10-18): the configure's coded notes travel with the activity
		activity.notes.add("The main table was validated alone. [mdmf:wizard.step6.configure.note.validateBusinessObjectFallback|s:%2Froot%2FClient|s:%2Froot%2FClient|s:f]");
		MatchingActivityTest.check("live while running", activity.isLive());
		final MatchingActivity back = MatchingActivity.parse(activity.toJson().toString());
		MatchingActivityTest.check("the notes come back as stored", back.notes.size() == 1 && back.notes.get(0).equals(activity.notes.get(0)));
		MatchingActivityTest.check("an activity without notes parses to none", MatchingActivity.parse("{\"kind\":\"CONFIGURE\",\"status\":\"DONE\"}").notes.isEmpty());
		MatchingActivityTest.check("kind and status", MatchingActivity.RUN.equals(back.kind) && MatchingActivity.RUNNING.equals(back.status));
		MatchingActivityTest.check("two entities in order", back.entities.size() == 2 && "Personne".equals(back.entities.get(0).name));
		MatchingActivityTest.check("entity fields", back.entity("Personne").endedAt == 3100L && back.entity("Personne").message.contains("goldenKey refreshed"));
		MatchingActivityTest.check("running entity", MatchingActivity.RUNNING.equals(back.entity("Adresse").status));
		back.status = MatchingActivity.DONE;
		MatchingActivityTest.check("not live once done", !back.isLive());
	}

	private static void garbageIsNoAction()
	{
		MatchingActivityTest.check("null", MatchingActivity.NONE.equals(MatchingActivity.parse(null).status));
		MatchingActivityTest.check("garbage", MatchingActivity.NONE.equals(MatchingActivity.parse("{oops").status));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
