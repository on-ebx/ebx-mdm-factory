package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.LoadPauseRule;

/**
 * JUnit-free: run the main, a failure throws. The in-memory half of the ledger: a load is "live" from {@code open} to {@code close}, so after a restart (an empty
 * registry) every row's owner is gone. The rows themselves need an EBX repository and are not exercised here.
 */
public final class LoadPauseLedgerTest
{
	public static void main(final String[] pArgs)
	{
		final LoadPauseLedger.Owner first = LoadPauseLedger.open("MASTER_LOAD", "1");
		final LoadPauseLedger.Owner second = LoadPauseLedger.open("STAGING_LOAD", "1");
		LoadPauseLedgerTest.check("tokens are distinct", !first.token.equals(second.token));
		LoadPauseLedgerTest.check("an open load is live", LoadPauseLedger.isLive(first.token) && LoadPauseLedger.isLive(second.token));
		LoadPauseLedgerTest.check("a live load is not an orphan", !LoadPauseRule.isOrphan(first.token, LoadPauseLedger.liveTokens()));
		first.close();
		LoadPauseLedgerTest.check("a closed load is gone", !LoadPauseLedger.isLive(first.token));
		LoadPauseLedgerTest.check("its rows are orphans", LoadPauseRule.isOrphan(first.token, LoadPauseLedger.liveTokens()));
		LoadPauseLedgerTest.check("the other one is untouched", LoadPauseLedger.isLive(second.token));
		first.close();
		second.close();
		LoadPauseLedgerTest.check("closing twice is harmless, nothing live", !LoadPauseLedger.isLive(second.token) && LoadPauseLedger.liveTokens().isEmpty());
		LoadPauseLedgerTest.check("a token nobody holds", !LoadPauseLedger.isLive(null) && !LoadPauseLedger.isLive("never-opened"));
		System.out.println("LoadPauseLedgerTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
