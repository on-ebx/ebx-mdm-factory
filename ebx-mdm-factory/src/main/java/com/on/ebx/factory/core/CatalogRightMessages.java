package com.on.ebx.factory.core;

import java.util.Locale;

/**
 * Audit F-02 (catalog reads): what a Factory user is told when the data catalog's own rights (module ebx-data-catalog,
 * {@code CatalogRights} / {@code CatalogTableRights}, read there, never imported) hide something from them or refuse one of
 * their writes. Pure (no EBX dependency), so the wording and the refusal detection are tested offline.
 * <p>
 * The catalog has four rights, each including the ones before it: {@link CatalogRight#CONSUME} (see the assets, columns,
 * sources, products), {@link CatalogRight#STEWARD} (curate them: the deduced-scan Confirm / Leave out), {@link CatalogRight#OWN}
 * (data products) and {@link CatalogRight#ADMINISTER} (sources, scans, renaming an asset, lineage ingestion). Without any
 * right the catalog's rows are hidden: a read under the user's session finds nothing, which the Factory never presents as
 * "nothing found" ({@link #hidden}).
 */
public final class CatalogRightMessages
{
	private CatalogRightMessages()
	{
	}

	/** The rights of the data catalog, mirrored from ebx-data-catalog's {@code CatalogRights.Right} (same names, same order). */
	public enum CatalogRight
	{
		CONSUME("CatalogConsumer"), STEWARD("CatalogSteward"), OWN("CatalogOwner"), ADMINISTER("CatalogAdmin");

		/** The directory role that holds this right without any row in the catalog's table "Access rights" (its default name). */
		public final String defaultRole;

		CatalogRight(final String pDefaultRole)
		{
			this.defaultRole = pDefaultRole;
		}
	}

	/** A write of the Factory into the catalog, and the catalog right it needs (decided by the catalog, listed in docs/OPERATIONS.md 4.1). */
	public enum CatalogWrite
	{
		/** Board 2e "Confirm" of a deduced table: {@code Asset.scanState/scanConfirmedBy/scanConfirmedAt}, steward fields. */
		CONFIRM_DEDUCED_TABLE("confirm this deduced table", CatalogRight.STEWARD),
		/** Board 2e "Leave it out": the same steward fields. */
		LEAVE_OUT_DEDUCED_TABLE("leave this deduced table out", CatalogRight.STEWARD),
		/** Board 2e rename: the asset and its columns move to a new key (create and delete on Asset and Column). */
		RENAME_DEDUCED_TABLE("rename this deduced table", CatalogRight.ADMINISTER),
		/** A catalog {@code Source} created for a connection (deduced scan from the wizard, guided onboarding). */
		REGISTER_SOURCE("register this connection as a catalog source", CatalogRight.ADMINISTER),
		/** Wizard step 7c / {@code POST .../distribution/product}: {@code DataProduct} and {@code OutputPort}. */
		PUBLISH_PRODUCT("create or update the data product", CatalogRight.OWN),
		/** {@code POST .../catalog/declare-lineage}: the catalog's OpenLineage route. */
		DECLARE_LINEAGE("declare the project's lineage", CatalogRight.ADMINISTER);

		public final String what;
		public final CatalogRight needs;

		CatalogWrite(final String pWhat, final CatalogRight pNeeds)
		{
			this.what = pWhat;
			this.needs = pNeeds;
		}
	}

	/** How to obtain a right, the same sentence the catalog writes in its own refusals. */
	static String howToObtain(final CatalogRight pRight)
	{
		return "An EBX administrator gives it to one of your roles in the table 'Access rights' of the data catalog (the role '" + pRight.defaultRole
			+ "' has it by default).";
	}

	/**
	 * A read under the user's session found nothing while the catalog does hold rows there: the catalog's rights hide them.
	 * {@code pWhat} names what is hidden ("the catalog assets of source CRM").
	 */
	public static String hidden(final String pWhat)
	{
		final String what = pWhat == null || pWhat.trim().isEmpty() ? "this part of the data catalog" : pWhat.trim();
		return "Your data catalog right does not let you see " + what + ": nothing of it is shown. Seeing the catalog requires the data catalog right "
			+ CatalogRight.CONSUME + ". " + CatalogRightMessages.howToObtain(CatalogRight.CONSUME);
	}

	/** The short form for a banner that covers a whole page (step 2 / step 3 of the wizard). */
	public static String hiddenOnPage()
	{
		return CatalogRightMessages.hidden("some data catalog content of this project (sources, tables, columns, deduced tables)");
	}

	/**
	 * The message for a write the catalog refused: a clear sentence naming the right when {@code pRawMessage} looks like a
	 * refusal of the catalog's rights or of EBX permissions, {@code null} otherwise (another failure: the caller keeps the
	 * raw message, never dresses it as a rights problem).
	 */
	public static String refused(final CatalogWrite pWrite, final String pRawMessage)
	{
		if (pWrite == null || !CatalogRightMessages.looksLikeRightsRefusal(pRawMessage))
		{
			return null;
		}
		return "The data catalog refused to " + pWrite.what + ": this requires the data catalog right " + pWrite.needs + ". "
			+ CatalogRightMessages.howToObtain(pWrite.needs) + " (Catalog answer: " + CatalogRightMessages.shorten(pRawMessage) + ")";
	}

	/** {@link #refused} when it applies, the raw message otherwise. */
	public static String refusedOrRaw(final CatalogWrite pWrite, final String pRawMessage)
	{
		final String refused = CatalogRightMessages.refused(pWrite, pRawMessage);
		return refused != null ? refused : pRawMessage;
	}

	/**
	 * Markers of a refusal by rights: the catalog's own read-only reasons and refusals ({@code CatalogTableRights.readOnlyReason},
	 * {@code CatalogRights.refusal}, the deletion refusal of its triggers), EBX's permission refusals in a procedure
	 * ("Forbidden to [UPDATE] the record", "no write permission on the following element", a hidden record), and an HTTP 403
	 * answer of a catalog route. Lower-cased comparison.
	 */
	static final String[] REFUSAL_MARKERS = { "data catalog:", "requires the right", "requires the data catalog right", "access denied", "forbidden",
		"no write permission", "permission denied", "not allowed", "read-only", "read only", "insufficient privilege", "http 403", "status 403", " 403 " };

	public static boolean looksLikeRightsRefusal(final String pRawMessage)
	{
		if (pRawMessage == null || pRawMessage.trim().isEmpty())
		{
			return false;
		}
		final String text = " " + pRawMessage.toLowerCase(Locale.ROOT) + " ";
		for (final String marker : CatalogRightMessages.REFUSAL_MARKERS)
		{
			if (text.contains(marker))
			{
				return true;
			}
		}
		return false;
	}

	static String shorten(final String pText)
	{
		final String text = pText == null ? "" : pText.replace('\n', ' ').replace('\r', ' ').trim();
		return text.length() <= 300 ? text : text.substring(0, 297) + "...";
	}
}
