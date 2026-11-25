package com.on.ebx.factory.staging;

/** JUnit-free: run the main, a failure throws. */
public final class CheckNoteTest
{
	public static void main(final String[] pArgs)
	{
		final String proposer = "Type-driven mapping for DATE.";
		final String flagged = CheckNote.with(proposer, "'isodate()' gives a valid date for 0 of 140 rows.", Integer.valueOf(85), 2000);
		check("the proposer's rationale stays first", flagged.startsWith(proposer + CheckNote.DELIMITER));
		check("the note says the confidence it replaced", Integer.valueOf(85).equals(CheckNote.originalConfidence(flagged)));
		check("has() sees it", CheckNote.has(flagged) && !CheckNote.has(proposer));
		check("base() gives the proposer's text back", proposer.equals(CheckNote.base(flagged)));
		final String again = CheckNote.with(flagged, "another measure", Integer.valueOf(85), 2000);
		check("a second note replaces the first, it does not pile on", again.indexOf(CheckNote.DELIMITER) == again.lastIndexOf(CheckNote.DELIMITER) && !again.contains("0 of 140"));
		check("no 'was' when the check did not touch the confidence", CheckNote.originalConfidence(CheckNote.with(proposer, "fixed", null, 2000)) == null);
		final StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 300; i++)
		{
			longText.append("word ");
		}
		final String cut = CheckNote.with(proposer, longText.toString(), null, 200);
		check("never longer than the field", cut.length() <= 200 && cut.startsWith(proposer));
		final String tinyRoom = CheckNote.with(longText.toString(), "note", null, 100);
		check("the proposer's text is cut before a note is written over it", tinyRoom.length() <= 100 && !CheckNote.has(tinyRoom));
		check("null rationale", CheckNote.with(null, "x", null, 2000).startsWith(CheckNote.DELIMITER) && "".equals(CheckNote.base(null)));
		System.out.println("CheckNoteTest OK");
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("CheckNoteTest: " + pLabel);
		}
	}
}
