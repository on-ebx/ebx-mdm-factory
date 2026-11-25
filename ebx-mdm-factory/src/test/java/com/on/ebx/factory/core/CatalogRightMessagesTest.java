package com.on.ebx.factory.core;

/**
 * JUnit-free: run the main, a failure throws. Audit F-02 (catalog reads): the message a Factory user gets when the data
 * catalog's rights hide something or refuse a write - the right is named, the way to obtain it is said, and a failure that
 * is not a rights refusal is never dressed as one.
 */
public final class CatalogRightMessagesTest
{
	public static void main(final String[] pArgs)
	{
		CatalogRightMessagesTest.rightsMirrorTheCatalog();
		CatalogRightMessagesTest.everyWriteNeedsTheCatalogsRight();
		CatalogRightMessagesTest.hiddenNamesConsumeAndHowToObtainIt();
		CatalogRightMessagesTest.refusalsOfTheCatalogAreRecognised();
		CatalogRightMessagesTest.otherFailuresKeepTheirOwnMessage();
		System.out.println("CatalogRightMessagesTest OK");
	}

	/** Same names, same order, same default roles as ebx-data-catalog's CatalogRights (read there, not imported). */
	private static void rightsMirrorTheCatalog()
	{
		final CatalogRightMessages.CatalogRight[] rights = CatalogRightMessages.CatalogRight.values();
		CatalogRightMessagesTest.check("four rights", rights.length == 4);
		CatalogRightMessagesTest.check("order CONSUME < STEWARD < OWN < ADMINISTER", rights[0] == CatalogRightMessages.CatalogRight.CONSUME
			&& rights[1] == CatalogRightMessages.CatalogRight.STEWARD && rights[2] == CatalogRightMessages.CatalogRight.OWN && rights[3] == CatalogRightMessages.CatalogRight.ADMINISTER);
		CatalogRightMessagesTest.check("default roles", "CatalogConsumer".equals(CatalogRightMessages.CatalogRight.CONSUME.defaultRole)
			&& "CatalogSteward".equals(CatalogRightMessages.CatalogRight.STEWARD.defaultRole) && "CatalogOwner".equals(CatalogRightMessages.CatalogRight.OWN.defaultRole)
			&& "CatalogAdmin".equals(CatalogRightMessages.CatalogRight.ADMINISTER.defaultRole));
	}

	/** The decisions of the catalog (its docs/OPERATIONS.md 4.1, "Who writes the tables"). */
	private static void everyWriteNeedsTheCatalogsRight()
	{
		CatalogRightMessagesTest.check("Confirm = STEWARD", CatalogRightMessages.CatalogWrite.CONFIRM_DEDUCED_TABLE.needs == CatalogRightMessages.CatalogRight.STEWARD);
		CatalogRightMessagesTest.check("Leave out = STEWARD", CatalogRightMessages.CatalogWrite.LEAVE_OUT_DEDUCED_TABLE.needs == CatalogRightMessages.CatalogRight.STEWARD);
		CatalogRightMessagesTest.check("Rename = ADMINISTER", CatalogRightMessages.CatalogWrite.RENAME_DEDUCED_TABLE.needs == CatalogRightMessages.CatalogRight.ADMINISTER);
		CatalogRightMessagesTest.check("register a source = ADMINISTER", CatalogRightMessages.CatalogWrite.REGISTER_SOURCE.needs == CatalogRightMessages.CatalogRight.ADMINISTER);
		CatalogRightMessagesTest.check("data product = OWN", CatalogRightMessages.CatalogWrite.PUBLISH_PRODUCT.needs == CatalogRightMessages.CatalogRight.OWN);
		CatalogRightMessagesTest.check("lineage declaration = ADMINISTER", CatalogRightMessages.CatalogWrite.DECLARE_LINEAGE.needs == CatalogRightMessages.CatalogRight.ADMINISTER);
		for (final CatalogRightMessages.CatalogWrite write : CatalogRightMessages.CatalogWrite.values())
		{
			CatalogRightMessagesTest.check(write + " says what it does", write.what != null && !write.what.isEmpty() && write.needs != null);
		}
	}

	private static void hiddenNamesConsumeAndHowToObtainIt()
	{
		final String message = CatalogRightMessages.hidden("the catalog assets of source CRM");
		CatalogRightMessagesTest.check("says it is the catalog right: " + message, message.startsWith("Your data catalog right does not let you see the catalog assets of source CRM"));
		CatalogRightMessagesTest.check("never reads as nothing found", message.contains("nothing of it is shown") && !message.toLowerCase().contains("not found"));
		CatalogRightMessagesTest.check("names CONSUME and the table and the default role",
			message.contains("CONSUME") && message.contains("'Access rights'") && message.contains("'CatalogConsumer'"));
		CatalogRightMessagesTest.check("a blank subject still reads", CatalogRightMessages.hidden("  ").contains("this part of the data catalog"));
		CatalogRightMessagesTest.check("the page banner text", CatalogRightMessages.hiddenOnPage().contains("sources, tables, columns"));
	}

	private static void refusalsOfTheCatalogAreRecognised()
	{
		// the catalog's own read-only reason (CatalogTableRights.readOnlyReason), as EBX puts it in a failed procedure
		final String readOnly = "Data catalog: changing this requires the right STEWARD (role 'CatalogSteward' by default).";
		final String confirm = CatalogRightMessages.refused(CatalogRightMessages.CatalogWrite.CONFIRM_DEDUCED_TABLE, readOnly);
		CatalogRightMessagesTest.check("Confirm refused names STEWARD: " + confirm,
			confirm != null && confirm.startsWith("The data catalog refused to confirm this deduced table: this requires the data catalog right STEWARD.")
				&& confirm.contains("'CatalogSteward'") && confirm.contains("Catalog answer: " + readOnly));
		// EBX's own refusals inside a procedure, and a 403 of a catalog route
		final String[] raw = { "Forbidden to [UPDATE] the record", "There is no write permission on the following element: /root/Asset/assetKey",
			"Access denied: publishing a port requires the data catalog right OWN.", "HTTP 403 {\"message\":\"no\"}", "The record is read-only for you" };
		for (final String text : raw)
		{
			CatalogRightMessagesTest.check("recognised: " + text, CatalogRightMessages.looksLikeRightsRefusal(text));
		}
		final String rename = CatalogRightMessages.refused(CatalogRightMessages.CatalogWrite.RENAME_DEDUCED_TABLE, raw[1]);
		CatalogRightMessagesTest.check("Rename names ADMINISTER and CatalogAdmin", rename != null && rename.contains("right ADMINISTER") && rename.contains("'CatalogAdmin'"));
		final String product = CatalogRightMessages.refusedOrRaw(CatalogRightMessages.CatalogWrite.PUBLISH_PRODUCT, "Writing the data product/output port failed: " + raw[0]);
		CatalogRightMessagesTest.check("product names OWN", product.contains("create or update the data product") && product.contains("right OWN"));
		final StringBuilder longText = new StringBuilder("Forbidden ");
		for (int i = 0; i < 100; i++)
		{
			longText.append("0123456789");
		}
		CatalogRightMessagesTest.check("a long answer is shortened", CatalogRightMessages.refused(CatalogRightMessages.CatalogWrite.REGISTER_SOURCE, longText.toString()).length() < 700);
	}

	private static void otherFailuresKeepTheirOwnMessage()
	{
		final String[] other = { "The catalog already has a table 'Company' for this source: choose another name, or leave this one out.",
			"This table is no longer in the catalog (CRM:Company): reload the page.", "Mandatory field 'physicalName' is empty", "Could not reach the data catalog", null, "" };
		for (final String text : other)
		{
			CatalogRightMessagesTest.check("not a rights refusal: " + text, !CatalogRightMessages.looksLikeRightsRefusal(text)
				&& CatalogRightMessages.refused(CatalogRightMessages.CatalogWrite.CONFIRM_DEDUCED_TABLE, text) == null);
		}
		CatalogRightMessagesTest.check("refusedOrRaw keeps another failure", other[0].equals(CatalogRightMessages.refusedOrRaw(CatalogRightMessages.CatalogWrite.RENAME_DEDUCED_TABLE, other[0])));
		CatalogRightMessagesTest.check("no write, nothing to say", CatalogRightMessages.refused(null, "Forbidden") == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
