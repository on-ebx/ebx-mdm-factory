package com.on.ebx.factory.transfer;

import java.util.Map;

/**
 * The pure decisions behind the landing -&gt; staging transfer's incremental mode (PLAN.md, "Workflow de
 * transfert" / the product owner's 2026-09-23 brief, "the landing -&gt; staging transfer INCREMENTAL"): which landing
 * rows a run of scope {@code new} even reads ({@link #landingPredicate}), and whether one already-read row's
 * transformed content actually needs to be (re)written to its staging row ({@link #shouldWrite}). No EBX
 * class here - {@code ebx.StagingLoader} is the only caller that reads the live schema/data and hands the
 * plain values this class needs; unit-tested without a running instance, same convention as every other
 * pure class in this package ({@code FieldAvailabilityRule}, {@code NullFillRule}, {@code BatchRetry}).
 * <p>
 * <b>The design, in one paragraph</b>: {@code landing.LandingFlow}'s {@code _status} rule went from {@code
 * IF_EMPTY} to {@code ALWAYS} (a version received - reloaded from the source - always goes back to {@code
 * NEW}, whatever a previous transfer already did with the row); {@code _status} therefore now means
 * "version received, not yet transferred", and {@code STAGED}/{@code REJECTED} is the outcome of the LAST
 * transfer of that version, not a permanent mark. Scope {@code new} (the default everywhere except the
 * wizard, see {@code ebx.ui.ProjectWizardUserService#loadStagingData}) reads only the landing rows still at
 * that "received, not yet transferred" state; scope {@code all} is the historical, full-table behaviour
 * (every landing row, whatever its {@code _status}), still needed after a mapping/cleaning-step/derived-field
 * change, when every already-{@code STAGED} row must be re-transformed even though its own content did not
 * change. Within scope {@code new}, a landing row can still be READ (its {@code _status} says so) yet turn
 * out to carry EXACTLY the same source content as what its staging row was last built from (a source that
 * resends unchanged rows on every extract, common with a full-table SQL feed) - {@link #shouldWrite} is the
 * second, finer decision that skips the actual staging write for those, while the row is still marked {@code
 * STAGED} by the caller (a transfer of that version did happen, it just produced no change).
 */
public final class IncrementalTransfer
{
	/** The two recognised {@code scope} values - {@link #isAllScope} recognises {@link #SCOPE_ALL} case-insensitively; everything else, including {@link #SCOPE_NEW} itself, reads as "not all". */
	public static final String SCOPE_NEW = "new";
	public static final String SCOPE_ALL = "all";

	/**
	 * Every landing row still at "version received, not yet transferred": {@code _status='NEW'}, or - a
	 * defensive fallback, never actually expected once every landing row has gone through the {@code ALWAYS}
	 * rule at least once - a null/empty {@code _status} (a row landed before this change, or the technical
	 * field left unset for any other reason). {@code osd:is-null}, not {@code ./_status=''}: EBX stores an
	 * empty string as null (CLAUDE.md sec 9, "An XPath predicate `./field=''` never matches a record whose
	 * field is empty"), so only the null check actually reaches a blank status. Verified live, 2026-09-23,
	 * against {@code Companies_Landing/ERP//root/SUPPLIER} via the {@code data-access} MCP server's {@code
	 * get_xpath_syntax}/{@code count_records_by_xpath}: the predicate executes with no error (0 matches on
	 * that table today - every row there is already {@code STAGED} from before this change).
	 */
	public static final String NEW_SCOPE_PREDICATE = "./_status='NEW' or osd:is-null(./_status)";

	private IncrementalTransfer()
	{
	}

	/**
	 * Parses a caller-supplied {@code scope} string (a REST query parameter, a workflow bean property, ...)
	 * into the boolean {@code ebx.StagingLoader#load} actually switches on. {@code "all"} (any case, leading/
	 * trailing whitespace tolerated - the same tolerance {@code FactoryResource#runMatching} already gives its
	 * own {@code scope} parameter) is the only value that means "every landing row, regardless of {@code
	 * _status}"; {@code null}, blank, {@code "new"} itself, and anything unrecognised (a typo) all default to
	 * {@code false} (scope {@code new}) - never silently promoting an unrecognised value to the heavier,
	 * full-table pass.
	 */
	public static boolean isAllScope(final String pScope)
	{
		return IncrementalTransfer.SCOPE_ALL.equalsIgnoreCase(pScope == null ? "" : pScope.trim());
	}

	/**
	 * The landing read predicate for one transfer run. {@code null} means "no filter" - {@code
	 * AdaptationTable#createRequestResult(null)}, scope {@code all}'s own historical behaviour, every landing
	 * row read every run.
	 */
	public static String landingPredicate(final boolean pAllRows)
	{
		return pAllRows ? null : IncrementalTransfer.NEW_SCOPE_PREDICATE;
	}

	/**
	 * Whether one already-read landing row's transformed content must actually be (re)written to its staging
	 * row.
	 *
	 * @param pExistingHash the CURRENT staging row's own {@code _sourceHash} - {@code null} for a row that
	 *            does not exist yet (a CREATE) or was never given one (a row staged before this lot, or by a
	 *            run where {@code pSourceHashFieldExists} was false)
	 * @param pIncomingHash this run's landing row's own {@code _rowHash} - the value that would be written to
	 *            {@code _sourceHash} were this row written
	 * @param pAllRows scope {@code all}: always write, whatever the stored hash says - its whole purpose is to
	 *            re-apply the mapping/cleaning/derivation pipeline even to a row whose SOURCE content is
	 *            unchanged (a mapping, a cleaning step or a derived-field rule may have changed instead). Since lot V3-S (2026-10-04)
	 *            {@code ebx.StagingLoader} no longer asks this question in scope {@code all}: it compares the planned values with the stored row
	 *            ({@link StagingRowDiff}) and writes only a row that differs - the hash decision here is scope {@code new}'s alone
	 * @param pSourceHashFieldExists false when the live staging table has not been regenerated since {@code
	 *            _sourceHash} was added (a project created before this lot, not yet re-run through {@code
	 *            staging/generate}): every row is treated as changed, the safe default - never silently skip a
	 *            write nobody can verify against a hash the table does not even carry
	 * @return false only when every one of the above says "skip": not scope {@code all}, the field exists, an
	 *         existing row was found, and its hash equals the incoming one
	 */
	public static boolean shouldWrite(final String pExistingHash, final String pIncomingHash, final boolean pAllRows, final boolean pSourceHashFieldExists)
	{
		if (pAllRows || !pSourceHashFieldExists || pExistingHash == null)
		{
			return true;
		}
		return !pExistingHash.equals(pIncomingHash);
	}

	/**
	 * Lot V3-S (2026-10-04): whether a landing row's {@code _status} must be written back. Scope {@code new} ({@code pSkipUpToDate} false) always writes: every row
	 * it reads is at {@code NEW}, so the answer would be yes anyway and the path is left exactly as it was. Scope {@code all} skips a row that already carries
	 * the status the run gives it - most of them, after a first load: a landing row still {@code NEW} (reloaded since), {@code null} or in the other status
	 * differs and is written, so the end state of every landing row is the same, minus a modification EBX had to index for nothing.
	 */
	public static boolean landingStatusNeedsWrite(final String pCurrent, final String pDecision, final boolean pSkipUpToDate)
	{
		return !pSkipUpToDate || pDecision == null || !pDecision.equals(pCurrent);
	}

	/**
	 * 2026-09-23 bug fix, found live (coordinator, workflow instance 564, project {@code Companies}): a
	 * landing table fed into MORE THAN ONE staging entity (e.g. {@code Companies}' ERP {@code SUPPLIER}
	 * table feeding both the {@code Supplier} entity AND, through a canonical/member relationship, {@code
	 * Company}) must not have its rows marked {@code STAGED} by the FIRST entity's feed that touches
	 * them - every LATER entity's feed reading the SAME landing table under scope {@code new} (predicate
	 * {@code ./_status='NEW'}) would then find nothing left to read. Measured live: {@code Supplier} read
	 * 55/unchanged 55, {@code Account} read 52/unchanged 52, {@code Contact} read 89/unchanged 89 - each
	 * correct - but {@code Company} (fed by the SAME {@code SUPPLIER}/{@code ACCOUNT} landing tables as
	 * {@code Supplier}/{@code Account}) read 0, where a scope-{@code all} run on the same data reads 107
	 * (55 + 52). {@code ebx.StagingLoader} no longer writes a landing row's status back per batch, per
	 * feed - every feed's decision for every row it touched is accumulated for the WHOLE run (across every
	 * entity) and written back only ONCE, at the very end, after every entity's every feed has had a
	 * chance to read every row at its {@code NEW} status. This method is the merge rule for two feeds'
	 * decisions about the SAME landing row within that one run: {@code REJECTED} wins over {@code STAGED}
	 * - a row any entity's feed rejected must be retried next time and stays visible as rejected in {@code
	 * TransferReject}, even when a different entity's feed accepted the very same row cleanly. {@code
	 * null} (a row not yet decided by an earlier feed this run) simply becomes {@code pIncoming}.
	 */
	public static String mergeLandingStatus(final String pExisting, final String pIncoming)
	{
		if (pExisting == null)
		{
			return pIncoming;
		}
		if ("REJECTED".equals(pExisting) || "REJECTED".equals(pIncoming))
		{
			return "REJECTED";
		}
		return pIncoming;
	}

	/**
	 * Merges every entry of {@code pFrom} into {@code pInto} ({@link #mergeLandingStatus} per key),
	 * mutating and returning {@code pInto} - the pure accumulation half of the same fix as {@link
	 * #mergeLandingStatus}. Plain string maps only, no EBX class: {@code ebx.StagingLoader}'s own
	 * {@code PendingLandingStatus} wraps this with the EBX bookkeeping (which landing dataset/table each
	 * key of the outer, per-table map belongs to) that this method itself never needs to know about.
	 */
	public static Map<String, String> mergeLandingStatusMaps(final Map<String, String> pInto, final Map<String, String> pFrom)
	{
		for (final Map.Entry<String, String> entry : pFrom.entrySet())
		{
			pInto.put(entry.getKey(), IncrementalTransfer.mergeLandingStatus(pInto.get(entry.getKey()), entry.getValue()));
		}
		return pInto;
	}
}
