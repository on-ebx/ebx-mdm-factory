package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JUnit-free: run the main, a failure throws. {@link JournalConsumption} (lot M-INC, 2026-10-12): what a changed load reads and which journal rows it may delete. */
public final class JournalConsumptionTest
{
	public static void main(final String[] pArgs)
	{
		final Map<String, Integer> journal = new LinkedHashMap<String, Integer>();
		journal.put("SAP|3", Integer.valueOf(1));
		journal.put("SAP|1", Integer.valueOf(2));
		journal.put("CRM|9", Integer.valueOf(1));

		// ---- what is read: the journal first (its order), then the open rejects it does not hold
		Set<String> keys = JournalConsumption.keysToRead(journal, Arrays.asList("SAP|1", "SAP|7", "SAP|7", "", null));
		check("journal keys first, in order, then new reject keys, no duplicate, blanks dropped", new ArrayList<String>(keys).equals(Arrays.asList("SAP|3", "SAP|1", "CRM|9", "SAP|7")));
		check("no journal, no rejects: nothing to read", JournalConsumption.keysToRead(null, null).isEmpty());
		check("rejects alone are read", new ArrayList<String>(JournalConsumption.keysToRead(new LinkedHashMap<String, Integer>(), Arrays.asList("A|1"))).equals(Arrays.asList("A|1")));

		// ---- what is consumed: the journal's own keys, only after a normal end
		check("a normal end consumes the journal's keys, not the rejects'", JournalConsumption.consumable(journal, true).equals(Arrays.asList("SAP|3", "SAP|1", "CRM|9")));
		check("a failed or stopped entity consumes nothing", JournalConsumption.consumable(journal, false).isEmpty());
		check("an empty journal consumes nothing", JournalConsumption.consumable(new LinkedHashMap<String, Integer>(), true).isEmpty());
		check("a null journal consumes nothing", JournalConsumption.consumable(null, true).isEmpty());

		// ---- how a row is deleted: only if its counter is the one read (a change made during the load stays)
		check("same counter: delete", JournalConsumption.shouldDelete(Integer.valueOf(1), Integer.valueOf(1)));
		check("the record was written again during the load: keep", !JournalConsumption.shouldDelete(Integer.valueOf(1), Integer.valueOf(2)));
		check("the row is gone: nothing to delete", !JournalConsumption.shouldDelete(Integer.valueOf(1), null));
		check("no counter read: never delete", !JournalConsumption.shouldDelete(null, Integer.valueOf(1)) && !JournalConsumption.shouldDelete(null, null));
		check("a counter that went back to 0 is not the one read", !JournalConsumption.shouldDelete(Integer.valueOf(3), Integer.valueOf(0)));

		// ---- batches
		final List<Integer> items = Arrays.asList(1, 2, 3, 4, 5);
		final List<List<Integer>> batches = JournalConsumption.batches(items, 2);
		check("batches of 2", batches.size() == 3 && batches.get(0).equals(Arrays.asList(1, 2)) && batches.get(1).equals(Arrays.asList(3, 4)) && batches.get(2).equals(Arrays.asList(5)));
		check("one batch when it fits", JournalConsumption.batches(items, 10).size() == 1);
		check("a size of 0 still makes progress (batches of 1)", JournalConsumption.batches(items, 0).size() == 5);
		check("no item, no batch", JournalConsumption.batches(new ArrayList<Integer>(), 3).isEmpty());
		System.out.println("JournalConsumptionTest OK");
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
