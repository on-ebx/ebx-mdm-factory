package com.on.ebx.factory.capability;

/**
 * fix/measure-observability (2026-09-21): the pure "does this validation-report item count as an occurrence
 * of the constraint" decisions {@code ebx.CapabilityApplier.measureEffect} makes on every item it walks - and
 * the small "keep sampling"/truncation rules behind its {@code unmatchedSample} diagnostic - pulled out so
 * they are unit-tested directly, the same {@code capability.*} (pure) / {@code ebx.*} (EBX-bound) split every
 * other class in this package already follows. No EBX class anywhere in this file; {@code
 * ebx.CapabilityApplier} is the only caller.
 * <p>
 * Built after a real apply/migration measured {@code recordsChecked: 55, recordsReported: 0} against a live
 * instance whose ground truth was ONE offending record, with no way to see why - {@link #onExpectedPath} and
 * {@link #messageMatches} are exactly the two gates a real item had to pass (and evidently did not) to be
 * counted; a diagnostic sample of the items that FAILED one of these gates ({@code
 * ebx.CapabilityApplier.UnmatchedSample}) is what makes the next such gap observable instead of guessed at.
 */
public final class MeasurementMatch
{
	private MeasurementMatch()
	{
	}

	/**
	 * The FIRST gate, before any message comparison: the item's own severity label equals the constraint's
	 * own expected severity label ({@code Severity.toParsableString()} on both sides, compared as plain
	 * strings - {@code ebx.CapabilityApplier} computes both once, outside the per-item loop), AND the item's
	 * own subject path is the SAME path as the constraint's own {@code expectedPath}, once both are put
	 * through {@link #normalizePath}. The severity comparison keeps the original discipline of this package -
	 * {@code pItemSeverityLabel}/{@code pExpectedSeverityLabel} are never null in practice ({@code
	 * ebx.CapabilityApplier} always has a real severity label by construction), and a caller passing null for
	 * either gets a {@code NullPointerException} from {@code String.equals}, not a graceful false. The path
	 * comparison does not carry that assumption - see {@link #normalizePath} - and never throws: a null
	 * {@code pSubjectPath} or {@code pExpectedPath} is simply not a match.
	 * <p>
	 * Measured 2026-09-21 against a real {@code recordsChecked: 55, recordsReported: 0} run: EBX formats a
	 * RECORD subject's path ({@code ValidationReportItemSubjectForAdaptation.getPathInAdaptation().format()})
	 * as {@code /iban}, not {@code ./iban} - while {@code ebx.CapabilityApplier.measureEffect} built {@code
	 * expectedPath} as {@code Path.parse("./" + attribute).format()} = {@code ./iban}. Strict {@code equals}
	 * on the two never matched, so the constraint's own warning was never attributed to the record it fired
	 * on. {@link #normalizePath} is the fix: it puts both sides into the same relative shape before comparing.
	 */
	public static boolean onExpectedPath(final String pItemSeverityLabel, final String pExpectedSeverityLabel, final String pSubjectPath,
		final String pExpectedPath)
	{
		if (pSubjectPath == null || pExpectedPath == null)
		{
			return false;
		}
		return pExpectedSeverityLabel.equals(pItemSeverityLabel)
			&& MeasurementMatch.normalizePath(pExpectedPath).equals(MeasurementMatch.normalizePath(pSubjectPath));
	}

	/**
	 * Puts a record-relative path into one canonical shape so {@link #onExpectedPath} can compare a path
	 * built by {@code ebx.CapabilityApplier} ({@code Path.parse("./" + attribute).format()}, e.g. {@code
	 * ./iban}) against a path EBX itself hands back on a validation-report item's subject ({@code
	 * ValidationReportItemSubjectForAdaptation.getPathInAdaptation().format()}, measured 2026-09-21 to be
	 * {@code /iban} for that same field - no leading {@code .}). A single leading {@code "./"} or {@code "/"}
	 * is stripped (a bare {@code "iban"}, which nothing in this codebase produces today but which {@code
	 * Path.parse} itself would normalize to {@code ./iban}, is left as-is and so already matches). Everything
	 * after that first segment is compared verbatim - {@code "/address/iban"} normalizes to {@code
	 * "address/iban"}, which is NOT {@code "iban"}, and an absolute table-qualified path such as {@code
	 * "/root/Supplier/iban"} normalizes to {@code "root/Supplier/iban"}, also not {@code "iban"}: this is
	 * exact-path equality after stripping one leading marker, never a suffix/contains match.
	 * <p>
	 * "The record itself" - {@code Path.SELF.format()} (measured 2026-09-21, via a throwaway {@code
	 * com.orchestranetworks.schema.Path} call against {@code ebx-lib.jar}, to be {@code "."}, not {@code ""})
	 * - and an empty or root-only path ({@code ""}, {@code "/"}) all normalize to the same empty string, so
	 * they compare equal to each other; none of them ever equals a normalized field path, since a field path
	 * always has at least one remaining character after the leading marker is stripped.
	 */
	private static String normalizePath(final String pPath)
	{
		if (".".equals(pPath) || "/".equals(pPath) || pPath.isEmpty())
		{
			return "";
		}
		if (pPath.startsWith("./"))
		{
			return pPath.substring(2);
		}
		if (pPath.startsWith("/"))
		{
			return pPath.substring(1);
		}
		return pPath;
	}

	/**
	 * Volume lot 3 (2026-09-28, docs/VOLUME.md task 4): the coarse, cheap-to-build half of {@link
	 * #onExpectedPath} alone (severity + normalized path, no message) - a stable key two callers can compare
	 * with a plain {@code Set.contains} instead of walking every rule for every validation-report item. Built
	 * once from a constraint's own {@code expectedSeverityLabel}/{@code expectedPath} (a small, model-scale set
	 * of rules), then re-derived per report ITEM (severity label + subject path) to test "could this item match
	 * ANY rule at all" before paying for anything the item's own {@code getMessage().formatMessage(...)} costs -
	 * see {@code quality.ScriptConstraintCheck#expectedKeys}/{@code ebx.MasterLoader#readReportItems} for the
	 * caller. Uses the exact same {@link #normalizePath} this class' own {@link #onExpectedPath} already uses,
	 * so a key built from a rule and a key built from an item are comparable if and only if {@link
	 * #onExpectedPath} would itself call them a match on path+severity (the message gate, {@link
	 * #messageMatches}, is unaffected - it only ever runs AFTER this coarse filter already said yes).
	 */
	public static String key(final String pSeverityLabel, final String pPath)
	{
		return pSeverityLabel + "|" + MeasurementMatch.normalizePath(pPath);
	}

	/**
	 * The SECOND gate, only reached once {@link #onExpectedPath} is true: {@code pRenderedMessage} matches
	 * {@code pAttribution} - exact equality when {@link ConstraintBodyBuilder.MessageAttribution#exact}, a
	 * {@code startsWith} match otherwise (a concatenation, e.g. {@code message | ' (' | status | ')'}, whose
	 * stored literal is only the leading segment - see {@code ConstraintBodyBuilder.extractReportedMessage}'s
	 * own Javadoc). {@code pAttribution == null} means the caller has no literal to match at all (the
	 * "approximate" case, {@code MeasuredEffect#approximate}) - {@code ebx.CapabilityApplier} never calls this
	 * method in that case, treating {@link #onExpectedPath} alone as a match instead; called here anyway (a
	 * null {@code pAttribution}, or a null {@code pRenderedMessage} - the item's own message failed to
	 * render), it returns false rather than throwing, since "nothing to compare" is never a match.
	 */
	public static boolean messageMatches(final ConstraintBodyBuilder.MessageAttribution pAttribution, final String pRenderedMessage)
	{
		if (pAttribution == null || pRenderedMessage == null)
		{
			return false;
		}
		return pAttribution.exact ? pRenderedMessage.equals(pAttribution.text) : pRenderedMessage.startsWith(pAttribution.text);
	}

	/** The diagnostic sample's own cap check - {@code pCurrentSampleSize < pCap} - a caller adds one more unmatched item only while this answers true, so the sample never grows past {@code pCap} regardless of how many unmatched items the measurement actually walks. */
	public static boolean shouldSample(final int pCurrentSampleSize, final int pCap)
	{
		return pCurrentSampleSize < pCap;
	}

	/** {@code pText}'s first {@code pMaxLength} characters, with {@code "..."} appended when it was longer - null in, null out; {@code pText} at or under {@code pMaxLength} is returned unchanged (never re-wrapped into a new String needlessly). The diagnostic sample's own {@code messageStart} truncation - a blanket precaution even though this codebase's own constraint messages never carry a real field value. */
	public static String truncate(final String pText, final int pMaxLength)
	{
		if (pText == null)
		{
			return null;
		}
		return pText.length() > pMaxLength ? pText.substring(0, pMaxLength) + "..." : pText;
	}
}
