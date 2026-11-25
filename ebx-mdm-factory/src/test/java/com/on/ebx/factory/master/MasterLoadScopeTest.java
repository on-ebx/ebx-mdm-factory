package com.on.ebx.factory.master;

/** JUnit-free: run the main, a failure throws. {@link MasterLoadScope} (lot M-INC, 2026-10-12): scope parsing, the decision table, the lookup-versus-scan threshold. */
public final class MasterLoadScopeTest
{
	public static void main(final String[] pArgs)
	{
		// ---- parsing: only "changed" asks for the journal
		check("changed", MasterLoadScope.CHANGED.equals(MasterLoadScope.parse("changed", null)));
		check("changed, any case, padded", MasterLoadScope.CHANGED.equals(MasterLoadScope.parse("  ChAnGeD ", "full")));
		check("full", MasterLoadScope.FULL.equals(MasterLoadScope.parse("full", "changed")));
		check("an explicit full beats a default of changed", MasterLoadScope.FULL.equals(MasterLoadScope.parse("full", "changed")));
		check("a typo is full, never the lighter read", MasterLoadScope.FULL.equals(MasterLoadScope.parse("chaneged", "changed")));
		check("blank takes the default: full without a property", MasterLoadScope.FULL.equals(MasterLoadScope.parse(null, null)));
		check("blank takes the default: changed", MasterLoadScope.CHANGED.equals(MasterLoadScope.parse("", " changed ")));
		check("blank with a nonsense default is full", MasterLoadScope.FULL.equals(MasterLoadScope.parse("  ", "all")));

		// ---- properties
		check("lookup max default", MasterLoadScope.parseLookupMax(null) == 20000L && MasterLoadScope.DEFAULT_LOOKUP_MAX == 20000L);
		check("lookup max a number", MasterLoadScope.parseLookupMax(" 50 ") == 50L);
		check("lookup max garbage is the default", MasterLoadScope.parseLookupMax("lots") == 20000L);
		check("lookup max zero is kept", MasterLoadScope.parseLookupMax("0") == 0L);
		check("journal on by default", MasterLoadScope.parseJournalEnabled(null) && MasterLoadScope.parseJournalEnabled("") && MasterLoadScope.parseJournalEnabled("true"));
		check("journal off only for an explicit false", !MasterLoadScope.parseJournalEnabled(" FALSE ") && MasterLoadScope.parseJournalEnabled("no"));
		check("property names", MasterLoadScope.LOOKUP_MAX_PROPERTY.equals("mdm.factory.masterLoad.journalLookupMax")
			&& MasterLoadScope.DEFAULT_SCOPE_PROPERTY.equals("mdm.factory.masterLoad.defaultScope")
			&& MasterLoadScope.JOURNAL_ENABLED_PROPERTY.equals("mdm.factory.masterLoad.journalEnabled"));

		// ---- the decision table: a good entity first, then one failure at a time
		check("a full request is full, whatever the facts", !MasterLoadScope.decide(MasterLoadScope.FULL, good()).changed);
		check("a full request gives no reason (nothing to explain)", MasterLoadScope.decide(MasterLoadScope.FULL, good()).reason.isEmpty());
		MasterLoadScope.Decision decision = MasterLoadScope.decide(MasterLoadScope.CHANGED, good());
		check("every condition met: changed", decision.changed && decision.reason.isEmpty() && MasterLoadScope.CHANGED.equals(decision.scope()));

		MasterLoadScope.Facts facts = good();
		facts.journalUsable = false;
		facts.journalUnusableReason = "the data-model server is not reachable";
		decision = MasterLoadScope.decide(MasterLoadScope.CHANGED, facts);
		check("journal not usable: full, reason says why", !decision.changed && decision.reason.contains("not usable") && decision.reason.contains("data-model server")
			&& MasterLoadScope.FULL.equals(decision.scope()));

		facts = good();
		facts.installedNow = true;
		decision = MasterLoadScope.decide(MasterLoadScope.CHANGED, facts);
		check("triggers installed by this very load: full (rows written before were not recorded)", !decision.changed && decision.reason.contains("installed by this load"));

		facts = good();
		facts.armed = false;
		decision = MasterLoadScope.decide(MasterLoadScope.CHANGED, facts);
		check("not armed (first load, or the last full one did not finish): full", !decision.changed && decision.reason.contains("no full load"));

		facts = good();
		facts.fingerprintMatches = false;
		decision = MasterLoadScope.decide(MasterLoadScope.CHANGED, facts);
		check("plan changed since the armed load: full", !decision.changed && decision.reason.contains("changed since the last full load"));

		facts = good();
		facts.masterEmpty = true;
		decision = MasterLoadScope.decide(MasterLoadScope.CHANGED, facts);
		check("empty master: full", !decision.changed && decision.reason.contains("master table is empty"));

		facts = good();
		facts.deferredReferences = true;
		decision = MasterLoadScope.decide(MasterLoadScope.CHANGED, facts);
		check("deferred foreign keys: full", !decision.changed && decision.reason.contains("deferred"));

		facts = good();
		facts.journalRows = MasterLoadScope.MAX_JOURNAL_ROWS;
		check("a journal at the bound is still read", MasterLoadScope.decide(MasterLoadScope.CHANGED, facts).changed);
		facts.journalRows = MasterLoadScope.MAX_JOURNAL_ROWS + 1L;
		decision = MasterLoadScope.decide(MasterLoadScope.CHANGED, facts);
		check("a journal above the bound: full", !decision.changed && decision.reason.contains("holds"));

		facts = good();
		facts.journalRows = 0L;
		check("an empty journal is still a changed load (it ends at once)", MasterLoadScope.decide(MasterLoadScope.CHANGED, facts).changed);

		// the first failing condition is the one reported
		facts = new MasterLoadScope.Facts();
		facts.journalUsable = true;
		facts.installedNow = true;
		facts.armed = false;
		facts.fingerprintMatches = false;
		check("first failing condition reported", MasterLoadScope.decide(MasterLoadScope.CHANGED, facts).reason.contains("installed by this load"));
		check("unusable without reason does not crash", MasterLoadScope.decide(MasterLoadScope.CHANGED, new MasterLoadScope.Facts()).reason.contains("unknown reason"));

		// ---- lookup versus scan
		check("full entity: scan all", MasterLoadScope.readMode(false, 5L, 20000L) == MasterLoadScope.ReadMode.SCAN_ALL);
		check("small journal: by key", MasterLoadScope.readMode(true, 1L, 20000L) == MasterLoadScope.ReadMode.KEYS);
		check("journal at the threshold: still by key", MasterLoadScope.readMode(true, 20000L, 20000L) == MasterLoadScope.ReadMode.KEYS);
		check("journal above the threshold: filtered scan", MasterLoadScope.readMode(true, 20001L, 20000L) == MasterLoadScope.ReadMode.FILTERED_SCAN);
		check("threshold zero: always scan", MasterLoadScope.readMode(true, 1L, 0L) == MasterLoadScope.ReadMode.FILTERED_SCAN);
		check("negative threshold: always scan", MasterLoadScope.readMode(true, 1L, -5L) == MasterLoadScope.ReadMode.FILTERED_SCAN);
		check("empty journal: by key (no row to read at all)", MasterLoadScope.readMode(true, 0L, 20000L) == MasterLoadScope.ReadMode.KEYS);
		System.out.println("MasterLoadScopeTest OK");
	}

	/** A journal that can stand for a full read: usable, not installed now, armed, same plan, master not empty, no deferred references, a handful of rows. */
	private static MasterLoadScope.Facts good()
	{
		final MasterLoadScope.Facts facts = new MasterLoadScope.Facts().usable();
		facts.installedNow = false;
		facts.armed = true;
		facts.fingerprintMatches = true;
		facts.masterEmpty = false;
		facts.deferredReferences = false;
		facts.journalRows = 3L;
		return facts;
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
