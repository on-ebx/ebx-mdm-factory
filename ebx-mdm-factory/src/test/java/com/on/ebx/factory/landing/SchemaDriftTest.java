package com.on.ebx.factory.landing;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link SchemaDrift} (source drift lot, 2026-10-30). */
public final class SchemaDriftTest
{
	public static void main(final String[] pArgs)
	{
		SchemaDriftTest.checkNoChange();
		SchemaDriftTest.checkAddedRemovedRetyped();
		SchemaDriftTest.checkRetypeRules();
		SchemaDriftTest.checkAddedFieldNames();
		SchemaDriftTest.checkImpactsAndOperations();
		SchemaDriftTest.checkRevalidationDesignatesOnly();
		SchemaDriftTest.checkStaleDriftIsRefused();
		SchemaDriftTest.checkModelAlreadyPatched();
		SchemaDriftTest.checkClosedMessage();
		System.out.println("SchemaDriftTest OK");
	}

	private static List<SchemaDrift.Known> known()
	{
		return Arrays.asList(new SchemaDrift.Known("ID", "ID", "NUMBER(10)", true), new SchemaDrift.Known("NAME", "NAME", "VARCHAR(80)", false),
			new SchemaDrift.Known("FAX", "FAX", "VARCHAR(20)", false));
	}

	private static void checkNoChange()
	{
		final List<SchemaDrift.Change> changes = SchemaDrift.diff(SchemaDriftTest.known(), Arrays.asList(new SchemaDrift.Scanned("ID", "number(10)"),
			new SchemaDrift.Scanned("NAME", " VARCHAR(80) "), new SchemaDrift.Scanned("FAX", "VARCHAR(20)")));
		SchemaDriftTest.check("same schema, case and blanks aside: no change", changes.isEmpty());
	}

	private static void checkAddedRemovedRetyped()
	{
		final List<SchemaDrift.Change> changes = SchemaDrift.diff(SchemaDriftTest.known(), Arrays.asList(new SchemaDrift.Scanned("ID", "VARCHAR(36)"),
			new SchemaDrift.Scanned("NAME", "VARCHAR(80)"), new SchemaDrift.Scanned("EMAIL", "VARCHAR(120)")));
		SchemaDriftTest.check("three changes", changes.size() == 3);
		final SchemaDrift.Change retyped = changes.get(0);
		SchemaDriftTest.check("ID retyped", SchemaDrift.RETYPED.equals(retyped.kind) && "ID".equals(retyped.sourceColumn) && "NUMBER(10)".equals(retyped.oldType)
			&& "VARCHAR(36)".equals(retyped.newType) && retyped.sourceKey);
		final SchemaDrift.Change removed = changes.get(1);
		SchemaDriftTest.check("FAX removed", SchemaDrift.REMOVED.equals(removed.kind) && "FAX".equals(removed.fieldName) && removed.newType == null);
		final SchemaDrift.Change added = changes.get(2);
		SchemaDriftTest.check("EMAIL added", SchemaDrift.ADDED.equals(added.kind) && "EMAIL".equals(added.fieldName) && "VARCHAR(120)".equals(added.newType));
		SchemaDriftTest.check("signatures differ", !retyped.signature().equals(removed.signature()));
		final String summary = SchemaDrift.summary(changes);
		SchemaDriftTest.check("summary names all three", summary.contains("[EMAIL]") && summary.contains("[FAX]") && summary.contains("ID (NUMBER(10) -> VARCHAR(36))"));
	}

	private static void checkRetypeRules()
	{
		final List<SchemaDrift.Change> noType = SchemaDrift.diff(Collections.singletonList(new SchemaDrift.Known("A", "A", null, false)),
			Collections.singletonList(new SchemaDrift.Scanned("A", "INTEGER")));
		SchemaDriftTest.check("no recorded type: never retyped", noType.isEmpty());
		final List<SchemaDrift.Change> noScanType = SchemaDrift.diff(Collections.singletonList(new SchemaDrift.Known("A", "A", "INTEGER", false)),
			Collections.singletonList(new SchemaDrift.Scanned("A", "")));
		SchemaDriftTest.check("no scanned type: never retyped", noScanType.isEmpty());
	}

	private static void checkAddedFieldNames()
	{
		final List<SchemaDrift.Change> changes = SchemaDrift.diff(Collections.singletonList(new SchemaDrift.Known("Name", "Name", "STRING", false)),
			Arrays.asList(new SchemaDrift.Scanned("Name", "STRING"), new SchemaDrift.Scanned("NAME", "STRING"), new SchemaDrift.Scanned("2nd line", "STRING")));
		SchemaDriftTest.check("two added", changes.size() == 2);
		SchemaDriftTest.check("case-insensitive clash renamed", "NAME_2".equals(changes.get(0).fieldName));
		SchemaDriftTest.check("field name is an XML name", changes.get(1).fieldName.matches("[A-Za-z_][A-Za-z0-9_]*"));
	}

	private static void checkImpactsAndOperations()
	{
		final SchemaDrift.Change added = new SchemaDrift.Change(SchemaDrift.ADDED, "EMAIL", "EMAIL", null, "VARCHAR", false);
		final ArrayNode ops = SchemaDrift.operations("CRM_Customer", added);
		SchemaDriftTest.check("one create_field", ops.size() == 1 && "create_field".equals(ops.get(0).path("op").asText()));
		SchemaDriftTest.check("field path", "/root/CRM_Customer/EMAIL".equals(ops.get(0).path("arguments").path("field_path").asText()));
		SchemaDriftTest.check("text field", "string".equals(ops.get(0).path("arguments").path("field_type").asText()));
		final SchemaDrift.Change removed = new SchemaDrift.Change(SchemaDrift.REMOVED, "ID", "ID", "INT", null, true);
		final SchemaDrift.Change retyped = new SchemaDrift.Change(SchemaDrift.RETYPED, "AGE", "AGE", "INT", "VARCHAR", false);
		SchemaDriftTest.check("removed changes no model", SchemaDrift.operations("T", removed).size() == 0);
		SchemaDriftTest.check("retyped changes no model", SchemaDrift.operations("T", retyped).size() == 0);
		SchemaDriftTest.check("removed key said", SchemaDrift.mappingImpact(removed).contains("source key"));
		SchemaDriftTest.check("staging lists the attributes", SchemaDrift.stagingImpact(retyped, Arrays.asList("Customer.age")).contains("Customer.age"));
		SchemaDriftTest.check("staging without attributes", SchemaDrift.stagingImpact(removed, null).startsWith("No staging attribute"));
		SchemaDriftTest.check("landing impact of added", SchemaDrift.landingImpact("T", added).contains("republish"));
	}

	// ---------------------------------------------------------------------------------------------- F3-01: revalidation

	private static List<SchemaDrift.Scanned> scannedWithEmail()
	{
		return Arrays.asList(new SchemaDrift.Scanned("ID", "NUMBER(10)"), new SchemaDrift.Scanned("NAME", "VARCHAR(80)"), new SchemaDrift.Scanned("FAX", "VARCHAR(20)"),
			new SchemaDrift.Scanned("EMAIL", "VARCHAR(120)", "STRING"));
	}

	/** What a stored row says is only a designation: a forged kind, field, table or column is refused, and the operations come from the server, not from the row. */
	private static void checkRevalidationDesignatesOnly()
	{
		final List<SchemaDrift.Known> known = SchemaDriftTest.known();
		final List<SchemaDrift.Scanned> scanned = SchemaDriftTest.scannedWithEmail();
		final SchemaDrift.Verdict good = SchemaDrift.revalidate("CRM_Customer", SchemaDrift.ADDED, "EMAIL", "EMAIL", known, scanned);
		SchemaDriftTest.check("a genuine added column is accepted", good.ok() && good.refusal == null && "EMAIL".equals(good.change.fieldName) && "STRING".equals(good.change.newDataType));
		final ArrayNode ops = SchemaDrift.operations("CRM_Customer", good.change);
		SchemaDriftTest.check("the operation is the server's own: one create_field on the real table and field", ops.size() == 1
			&& "create_field".equals(ops.get(0).path("op").asText()) && "/root/CRM_Customer/EMAIL".equals(ops.get(0).path("arguments").path("field_path").asText()));
		for (final String kind : new String[] { "DELETE_ELEMENT", "added", "", null, "ADDED; drop", "SET_ACCESS_RIGHTS" })
		{
			final SchemaDrift.Verdict verdict = SchemaDrift.revalidate("CRM_Customer", kind, "EMAIL", "EMAIL", known, scanned);
			SchemaDriftTest.check("kind '" + kind + "' is refused", !verdict.ok() && SchemaDrift.REFUSAL_KIND.equals(verdict.refusal));
		}
		for (final String field : new String[] { "../Other/EMAIL", "EMAIL/../x", "a b", "EMAIL\"", "", null, "_EMAIL", "1EMAIL", "E;MAIL",
			"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" })
		{
			final SchemaDrift.Verdict verdict = SchemaDrift.revalidate("CRM_Customer", SchemaDrift.ADDED, "EMAIL", field, known, scanned);
			SchemaDriftTest.check("field name '" + field + "' is refused", !verdict.ok() && SchemaDrift.REFUSAL_FIELD.equals(verdict.refusal));
		}
		for (final String table : new String[] { "CRM/../Customer", "T T", "", null, "T'" })
		{
			final SchemaDrift.Verdict verdict = SchemaDrift.revalidate(table, SchemaDrift.ADDED, "EMAIL", "EMAIL", known, scanned);
			SchemaDriftTest.check("table name '" + table + "' is refused", !verdict.ok() && SchemaDrift.REFUSAL_FIELD.equals(verdict.refusal));
		}
		SchemaDriftTest.check("a blank source column is refused", SchemaDrift.REFUSAL_FIELD.equals(SchemaDrift.revalidate("T", SchemaDrift.ADDED, " ", "EMAIL", known, scanned).refusal));
		SchemaDriftTest.check("an added column that is not in the scanned source is refused", SchemaDrift.REFUSAL_NOT_IN_SOURCE
			.equals(SchemaDrift.revalidate("T", SchemaDrift.ADDED, "SSN", "SSN", known, scanned).refusal));
		final SchemaDrift.Verdict removed = SchemaDrift.revalidate("T", SchemaDrift.REMOVED, "FAX", "FAX", known,
			Arrays.asList(new SchemaDrift.Scanned("ID", "NUMBER(10)"), new SchemaDrift.Scanned("NAME", "VARCHAR(80)")));
		SchemaDriftTest.check("a genuine removed column is accepted, and changes no model", removed.ok() && SchemaDrift.operations("T", removed.change).size() == 0);
		final SchemaDrift.Verdict retyped = SchemaDrift.revalidate("T", SchemaDrift.RETYPED, "ID", "ID", known,
			Arrays.asList(new SchemaDrift.Scanned("ID", "VARCHAR(36)", "STRING"), new SchemaDrift.Scanned("NAME", "VARCHAR(80)"), new SchemaDrift.Scanned("FAX", "VARCHAR(20)")));
		SchemaDriftTest.check("a genuine retyped column is accepted with the CURRENT type", retyped.ok() && "VARCHAR(36)".equals(retyped.change.newType)
			&& "STRING".equals(retyped.change.newDataType));
	}

	/** A drift whose change no longer exists in the source or in the landing is refused with the "replan" code, never executed. */
	private static void checkStaleDriftIsRefused()
	{
		final List<SchemaDrift.Known> known = SchemaDriftTest.known();
		final List<SchemaDrift.Scanned> scanned = SchemaDriftTest.scannedWithEmail();
		final List<SchemaDrift.Known> withEmail = new java.util.ArrayList<SchemaDrift.Known>(known);
		withEmail.add(new SchemaDrift.Known("EMAIL", "EMAIL", "VARCHAR(120)", false));
		SchemaDriftTest.check("the column was meanwhile added to the landing: stale", SchemaDrift.REFUSAL_STALE
			.equals(SchemaDrift.revalidate("T", SchemaDrift.ADDED, "EMAIL", "EMAIL", withEmail, scanned).refusal));
		SchemaDriftTest.check("a different landing field than the server computes: stale", SchemaDrift.REFUSAL_STALE
			.equals(SchemaDrift.revalidate("T", SchemaDrift.ADDED, "EMAIL", "EMAIL_2", known, scanned).refusal));
		SchemaDriftTest.check("a removed column that is back in the source: stale", SchemaDrift.REFUSAL_STALE
			.equals(SchemaDrift.revalidate("T", SchemaDrift.REMOVED, "FAX", "FAX", known, scanned).refusal));
		SchemaDriftTest.check("a removed column the landing no longer holds: stale", SchemaDrift.REFUSAL_STALE
			.equals(SchemaDrift.revalidate("T", SchemaDrift.REMOVED, "GONE", "GONE", known, scanned).refusal));
		SchemaDriftTest.check("a retype that the source reverted: stale", SchemaDrift.REFUSAL_STALE
			.equals(SchemaDrift.revalidate("T", SchemaDrift.RETYPED, "ID", "ID", known, scanned).refusal));
		SchemaDriftTest.check("a retype of a column the landing does not hold: stale", SchemaDrift.REFUSAL_STALE
			.equals(SchemaDrift.revalidate("T", SchemaDrift.RETYPED, "NOPE", "NOPE", known, scanned).refusal));
	}

	/** F3-113: a FAILED ADDED row whose model was already patched is replayed without patching again. */
	private static void checkModelAlreadyPatched()
	{
		SchemaDriftTest.check("patched and republished, then failed", SchemaDrift.modelAlreadyPatched("Field EMAIL added to CrmLanding and republished. boom", "EMAIL", "CrmLanding"));
		SchemaDriftTest.check("failed before the patch", !SchemaDrift.modelAlreadyPatched("apply_data_model_patch could not change CrmLanding: x", "EMAIL", "CrmLanding"));
		SchemaDriftTest.check("another field or another model", !SchemaDrift.modelAlreadyPatched("Field EMAIL added to CrmLanding and republished. boom", "PHONE", "CrmLanding")
			&& !SchemaDrift.modelAlreadyPatched("Field EMAIL added to CrmLanding and republished. boom", "EMAIL", "Other") && !SchemaDrift.modelAlreadyPatched(null, "EMAIL", "CrmLanding"));
	}

	/** F3-105: closed, the v1.3.0 warning, word for word; a retype alone is no warning. */
	private static void checkClosedMessage()
	{
		final List<SchemaDrift.Change> changes = SchemaDrift.diff(SchemaDriftTest.known(), Arrays.asList(new SchemaDrift.Scanned("ID", "VARCHAR(36)"),
			new SchemaDrift.Scanned("NAME", "VARCHAR(80)"), new SchemaDrift.Scanned("EMAIL", "VARCHAR(120)")));
		SchemaDriftTest.check("added and gone columns, exact v1.3.0 words", "The source changed since the landing was generated. New columns: [EMAIL]; columns gone: [FAX]."
			.equals(SchemaDrift.closedMessage(changes)));
		SchemaDriftTest.check("a retype alone marks nothing", SchemaDrift.closedMessage(SchemaDrift.diff(SchemaDriftTest.known(), Arrays.asList(new SchemaDrift.Scanned("ID", "VARCHAR(36)"),
			new SchemaDrift.Scanned("NAME", "VARCHAR(80)"), new SchemaDrift.Scanned("FAX", "VARCHAR(20)")))) == null);
		SchemaDriftTest.check("no change, no message", SchemaDrift.closedMessage(Collections.<SchemaDrift.Change>emptyList()) == null);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
