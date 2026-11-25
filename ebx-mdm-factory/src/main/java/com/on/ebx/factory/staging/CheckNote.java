package com.on.ebx.factory.staging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Staging normalisation lot D (2026-10-17): the "Value check" note the normalisation lot appends to an {@code
 * AttributeMapping.rationale} when its value check fixed or flagged the link - kept at the END of the rationale behind a
 * fixed delimiter so a re-run replaces it instead of piling a second one on, and carrying the confidence the link had
 * before the check lowered it ({@code [was 85]}), so a link that verifies clean on a later run gets its confidence back.
 * Pure: no EBX class.
 */
public final class CheckNote
{
	/** The delimiter that starts the note; the text before it is the proposer's own rationale, untouched. */
	public static final String DELIMITER = " || Value check";
	private static final Pattern WAS = Pattern.compile("^ \\|\\| Value check \\[was (\\d{1,3})\\]");
	private static final Pattern WAS_TAIL = Pattern.compile("^\\s*\\[was \\d{1,3}\\]");

	private CheckNote()
	{
	}

	/** The rationale without any note. */
	public static String base(final String pRationale)
	{
		if (pRationale == null)
		{
			return "";
		}
		final int index = pRationale.indexOf(CheckNote.DELIMITER);
		return index < 0 ? pRationale : pRationale.substring(0, index);
	}

	/** True when the rationale carries a note. */
	public static boolean has(final String pRationale)
	{
		return pRationale != null && pRationale.contains(CheckNote.DELIMITER);
	}

	/** The confidence the link had before the check lowered it, or null when the note does not say (or there is no note). */
	public static Integer originalConfidence(final String pRationale)
	{
		if (pRationale == null)
		{
			return null;
		}
		final int index = pRationale.indexOf(CheckNote.DELIMITER);
		if (index < 0)
		{
			return null;
		}
		final Matcher matcher = CheckNote.WAS.matcher(pRationale.substring(index));
		return matcher.find() ? Integer.valueOf(Integer.parseInt(matcher.group(1))) : null;
	}

	/**
	 * {@code pRationale}'s base followed by the note, within {@code pMaxLength} (the note is cut, never the proposer's rationale).
	 *
	 * @param pWasConfidence the confidence to restore if the link later verifies clean; null when the check did not touch it
	 */
	public static String with(final String pRationale, final String pNote, final Integer pWasConfidence, final int pMaxLength)
	{
		final String base = CheckNote.base(pRationale);
		final StringBuilder note = new StringBuilder(CheckNote.DELIMITER);
		if (pWasConfidence != null)
		{
			note.append(" [was ").append(pWasConfidence.intValue()).append(']');
		}
		note.append(": ").append(pNote == null ? "" : pNote);
		final int room = pMaxLength - base.length();
		if (room <= CheckNote.DELIMITER.length() + 12)
		{
			return base.length() > pMaxLength ? base.substring(0, pMaxLength) : base;
		}
		final String text = note.length() > room ? note.substring(0, room - 3) + "..." : note.toString();
		return base + text;
	}

	/**
	 * Staging last points, item 1 (2026-10-17): the same with a CODED note ({@link NoteTexts.Note}) - the stored text is the English note followed by its marker, so a
	 * screen words it in the viewer's language. The marker is never cut; when the room is too small for it the plain English is written, as {@link #with(String, String,
	 * Integer, int)} does.
	 */
	public static String with(final String pRationale, final NoteTexts.Note pNote, final Integer pWasConfidence, final int pMaxLength)
	{
		final String base = CheckNote.base(pRationale);
		final StringBuilder head = new StringBuilder(CheckNote.DELIMITER);
		if (pWasConfidence != null)
		{
			head.append(" [was ").append(pWasConfidence.intValue()).append(']');
		}
		head.append(": ");
		final int room = pMaxLength - base.length();
		if (room <= CheckNote.DELIMITER.length() + 12)
		{
			return base.length() > pMaxLength ? base.substring(0, pMaxLength) : base;
		}
		return base + pNote.storedWith(head.toString(), "", room);
	}

	/** The text after the delimiter and its {@code [was N]}, without the colon: the note as stored (English and marker), or empty when the rationale carries no note. */
	public static String noteOf(final String pRationale)
	{
		if (pRationale == null)
		{
			return "";
		}
		final int index = pRationale.indexOf(CheckNote.DELIMITER);
		if (index < 0)
		{
			return "";
		}
		String tail = pRationale.substring(index + CheckNote.DELIMITER.length());
		final Matcher was = CheckNote.WAS_TAIL.matcher(tail);
		if (was.find())
		{
			tail = tail.substring(was.end());
		}
		tail = tail.trim();
		if (tail.startsWith(":"))
		{
			tail = tail.substring(1).trim();
		}
		return tail;
	}
}
