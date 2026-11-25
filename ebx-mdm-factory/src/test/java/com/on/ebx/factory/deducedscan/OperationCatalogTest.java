package com.on.ebx.factory.deducedscan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JUnit-free: run the main, a failure throws. */
public final class OperationCatalogTest
{
	public static void main(final String[] pArgs)
	{
		final List<OperationCatalog.OperationInfo> operations = new ArrayList<OperationCatalog.OperationInfo>();
		operations.add(new OperationCatalog.OperationInfo("list_contacts", true, java.util.Arrays.asList("after")));
		operations.add(new OperationCatalog.OperationInfo("get_contact", true, java.util.Arrays.asList("id")));
		operations.add(new OperationCatalog.OperationInfo("upsert_contact", false, java.util.Arrays.asList("body")));
		operations.add(new OperationCatalog.OperationInfo("delete_contact", false, java.util.Collections.<String>emptyList()));

		final List<OperationCatalog.OperationInfo> readOnly = OperationCatalog.readOnlyOperations(operations);
		OperationCatalogTest.check("keeps only readOnly=true", readOnly.size() == 2);
		OperationCatalogTest.check("readOnly=false operations are dropped", !OperationCatalogTest.hasName(readOnly, "upsert_contact"));
		OperationCatalogTest.check("readOnly=false operations are dropped (2)", !OperationCatalogTest.hasName(readOnly, "delete_contact"));

		OperationCatalogTest.check("hasNoReadOnlyOperation is false when some exist", !OperationCatalog.hasNoReadOnlyOperation(readOnly));
		OperationCatalogTest.check("hasNoReadOnlyOperation is true on an empty list", OperationCatalog.hasNoReadOnlyOperation(new ArrayList<OperationCatalog.OperationInfo>()));
		OperationCatalogTest.check("hasNoReadOnlyOperation tolerates null", OperationCatalog.hasNoReadOnlyOperation(null));

		// null/missing/false readOnly all default to false - never trusted absent
		final List<OperationCatalog.OperationInfo> undeclared = new ArrayList<OperationCatalog.OperationInfo>();
		undeclared.add(new OperationCatalog.OperationInfo("list_things", false, java.util.Collections.<String>emptyList()));
		OperationCatalogTest.check("a missing readOnly flag is never trusted", OperationCatalog.readOnlyOperations(undeclared).isEmpty());

		// firstViolation: an operation the model names that is NOT in the read-only list is refused, even if
		// it exists among the connector's full (non-filtered) operation list - the enforcement point.
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("after", "0");
		final OperationCatalog.ListChoice writeChoice = new OperationCatalog.ListChoice("upsert_contact", inputs);
		OperationCatalogTest.check("a write operation is refused even if named by the model", OperationCatalog.firstViolation(writeChoice, readOnly) != null);

		final OperationCatalog.ListChoice goodChoice = new OperationCatalog.ListChoice("list_contacts", inputs);
		OperationCatalogTest.check("a genuine read-only listing operation is accepted", OperationCatalog.firstViolation(goodChoice, readOnly) == null);

		final Map<String, String> badInputs = new LinkedHashMap<String, String>();
		badInputs.put("madeUpInput", "x");
		final OperationCatalog.ListChoice badInputChoice = new OperationCatalog.ListChoice("list_contacts", badInputs);
		OperationCatalogTest.check("an undeclared input is refused", OperationCatalog.firstViolation(badInputChoice, readOnly) != null);

		OperationCatalogTest.check("no operation named is refused", OperationCatalog.firstViolation(new OperationCatalog.ListChoice("", null), readOnly) != null);
		OperationCatalogTest.check("a null choice is refused", OperationCatalog.firstViolation(null, readOnly) != null);

		OperationCatalogTest.findByName();
		OperationCatalogTest.missingRequiredInputs();
		OperationCatalogTest.filterCallable();

		System.out.println("OperationCatalogTest OK");
	}

	/** Bug fix 2026-09-26 (contract point 3): {@link OperationCatalog#findByName}. */
	private static void findByName()
	{
		final List<OperationCatalog.OperationInfo> operations = java.util.Arrays.asList(new OperationCatalog.OperationInfo("a", true, java.util.Collections.<String>emptyList()));
		OperationCatalogTest.check("found by name", OperationCatalog.findByName(operations, "a") != null);
		OperationCatalogTest.check("not found answers null", OperationCatalog.findByName(operations, "b") == null);
		OperationCatalogTest.check("null list answers null", OperationCatalog.findByName(null, "a") == null);
		OperationCatalogTest.check("null name answers null", OperationCatalog.findByName(operations, null) == null);
	}

	/** Bug fix 2026-09-26 (contract point 3): {@link OperationCatalog#missingRequiredInputs}/{@link OperationCatalog#notCallableReason} - the guard that stopped OpenFDA's udi/deviceSearch/drug (each a single-record lookup with a required input the model never filled) from being sampled as if they were listings. */
	private static void missingRequiredInputs()
	{
		final OperationCatalog.OperationInfo deviceSearch = new OperationCatalog.OperationInfo("deviceSearch", true, java.util.Arrays.asList("brand"),
			java.util.Collections.<String>emptyList(), "GUDID device by brand name", "SEARCH");

		final Map<String, String> noInputs = new LinkedHashMap<String, String>();
		final OperationCatalog.ListChoice choiceWithNoValue = new OperationCatalog.ListChoice("deviceSearch", noInputs);
		final List<String> missing = OperationCatalog.missingRequiredInputs(deviceSearch, choiceWithNoValue);
		OperationCatalogTest.check("brand reported missing when not filled at all", missing.size() == 1 && "brand".equals(missing.get(0)));

		final Map<String, String> blankValue = new LinkedHashMap<String, String>();
		blankValue.put("brand", "   ");
		OperationCatalogTest.check("a blank (whitespace-only) value is treated the same as missing",
			OperationCatalog.missingRequiredInputs(deviceSearch, new OperationCatalog.ListChoice("deviceSearch", blankValue)).size() == 1);

		final Map<String, String> realValue = new LinkedHashMap<String, String>();
		realValue.put("brand", "Acme");
		OperationCatalogTest.check("a real value satisfies the required input",
			OperationCatalog.missingRequiredInputs(deviceSearch, new OperationCatalog.ListChoice("deviceSearch", realValue)).isEmpty());

		OperationCatalogTest.check("null operation reports nothing missing (defence in depth only)", OperationCatalog.missingRequiredInputs(null, choiceWithNoValue).isEmpty());
		OperationCatalogTest.check("null choice reports nothing missing", OperationCatalog.missingRequiredInputs(deviceSearch, null).isEmpty());

		final String reason = OperationCatalog.notCallableReason(deviceSearch, missing);
		OperationCatalogTest.check("reason names the operation", reason != null && reason.contains("deviceSearch"));
		OperationCatalogTest.check("reason names the missing input", reason.contains("'brand'"));
		OperationCatalogTest.check("reason calls it a search, not a listing", reason.contains("search"));
		OperationCatalogTest.check("no reason when nothing is missing", OperationCatalog.notCallableReason(deviceSearch, java.util.Collections.<String>emptyList()) == null);
	}

	/** Bug fix 2026-09-26 (contract point 3): {@link OperationCatalog#filterCallable} - the partition the job actually acts on. */
	private static void filterCallable()
	{
		final OperationCatalog.OperationInfo udi = new OperationCatalog.OperationInfo("udi", true, java.util.Arrays.asList("di"), java.util.Collections.<String>emptyList(),
			"GUDID device record by DI", "READ");
		final OperationCatalog.OperationInfo listContacts = new OperationCatalog.OperationInfo("listContacts", true, java.util.Collections.<String>emptyList(),
			java.util.Arrays.asList("after"), "List contacts", "SEARCH");
		final List<OperationCatalog.OperationInfo> operations = java.util.Arrays.asList(udi, listContacts);

		final Map<String, String> empty = new LinkedHashMap<String, String>();
		final List<OperationCatalog.ListChoice> chosen = java.util.Arrays.asList(new OperationCatalog.ListChoice("udi", empty),
			new OperationCatalog.ListChoice("listContacts", empty));
		final OperationCatalog.CallabilityResult result = OperationCatalog.filterCallable(chosen, operations);
		OperationCatalogTest.check("only listContacts is callable", result.callable.size() == 1 && "listContacts".equals(result.callable.get(0).operation));
		OperationCatalogTest.check("one reason reported for udi", result.notCallableReasons.size() == 1 && result.notCallableReasons.get(0).contains("udi"));

		// the enrichment/lookup-only outcome: every choice is a lookup missing its required input
		final List<OperationCatalog.ListChoice> onlyLookups = java.util.Collections.singletonList(new OperationCatalog.ListChoice("udi", empty));
		final OperationCatalog.CallabilityResult noneCallable = OperationCatalog.filterCallable(onlyLookups, operations);
		OperationCatalogTest.check("nothing callable when every choice is a bare lookup", noneCallable.callable.isEmpty());
		OperationCatalogTest.check("still one reason reported", noneCallable.notCallableReasons.size() == 1);

		OperationCatalogTest.check("null chosen list answers empty result, not an error", OperationCatalog.filterCallable(null, operations).callable.isEmpty());

		final List<OperationCatalog.ListChoice> unknownOperation = java.util.Collections.singletonList(new OperationCatalog.ListChoice("madeUp", empty));
		final OperationCatalog.CallabilityResult unknownResult = OperationCatalog.filterCallable(unknownOperation, operations);
		OperationCatalogTest.check("an operation not in the list is reported not callable, not a crash", unknownResult.callable.isEmpty() && unknownResult.notCallableReasons.size() == 1);
	}

	private static boolean hasName(final List<OperationCatalog.OperationInfo> pOperations, final String pName)
	{
		for (final OperationCatalog.OperationInfo operation : pOperations)
		{
			if (operation.name.equals(pName))
			{
				return true;
			}
		}
		return false;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
