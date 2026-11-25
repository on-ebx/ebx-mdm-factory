package com.on.ebx.factory.landing;

/**
 * Distribution round 4 fix (2026-09-26, task brief item 2): "every selected ('ticked') table of every
 * application is LOADED" - the exact rule {@code wizard.WizardRules#allTickedTablesLoaded} already uses to
 * gate the Staging step's reachability, factored out into this pure, dependency-free class so
 * {@code ebx.DistributionRunner}'s arming checklist ("landing loaded from every source") can share it
 * instead of re-deriving a second, looser notion from {@code ProjectSource.status}.
 * <p>
 * Live bug fixed (DistribTest, 2026-09-26): {@code DistributionRunner#everySourceLoaded} used to require
 * {@code ProjectSource.status == "LOADED"}, a status the wizard's own landing flow never actually sets to
 * that exact value once the tables themselves are loaded (DistribTest's source stayed {@code GENERATED}
 * although its one landing table was genuinely loaded - {@code PipelineStep LANDING_LOAD DONE "Loaded 1
 * landing table(s)"}, 180 rows) - the checklist item stayed unticked forever. {@code WizardRules} reads the
 * real per-TABLE {@code LandingTable.status} instead, never the source's own rolled-up status; this class
 * makes that the one shared rule both {@code WizardRules} and {@code DistributionRunner} apply.
 */
public final class LandingReadiness
{
	private LandingReadiness()
	{
	}

	/** One landing table's readiness facts: whether it is selected ("ticked") and whether it is loaded. */
	public static final class Table
	{
		public final boolean selected;
		public final boolean loaded;

		public Table(final boolean pSelected, final boolean pLoaded)
		{
			this.selected = pSelected;
			this.loaded = pLoaded;
		}
	}

	/**
	 * True only when there is at least one selected table AND every selected table is loaded - false when
	 * there is no selected table at all (nothing to be "all loaded" about yet).
	 */
	public static boolean allTickedTablesLoaded(final Iterable<Table> pTables)
	{
		boolean any = false;
		for (final Table table : pTables)
		{
			if (!table.selected)
			{
				continue;
			}
			any = true;
			if (!table.loaded)
			{
				return false;
			}
		}
		return any;
	}
}
