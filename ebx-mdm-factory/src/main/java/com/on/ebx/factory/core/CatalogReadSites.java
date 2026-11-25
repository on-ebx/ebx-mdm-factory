package com.on.ebx.factory.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit F-02 (catalog reads), the decision table: every place the Factory reads the data catalog's dataset (module
 * ebx-data-catalog) and for whom. Since wave 7 of the catalog, every table of its dataset hides its rows from a session
 * without a catalog right and EBX applies those rules only to a request that carries a session; a request without a session
 * sees everything. Each read therefore goes through {@code ebx.CatalogReader.Reader}, created at one of the sites below:
 * <ul>
 * <li>{@link Mode#PERSON} ({@code Reader.of(session)}) - the result is shown to a person or answered by a REST route on
 * their behalf: the session passed is theirs, or a job's system session when the same code runs in a job (then the job was
 * queued only for a person who can see the catalog, {@code CatalogReader.requireVisible});</li>
 * <li>{@link Mode#SYSTEM} ({@code Reader.system(REASON)}) - a read for the project whose result is never listed to anybody;
 * the reason is a named constant at the site.</li>
 * </ul>
 * Plus {@link #WRITE_SIDE}: the few raw lookups left inside the person's own write procedures, where EBX checks the write.
 * Pure: {@code CatalogReadSitesTest} counts the sites in the sources and fails the build when one is not decided here.
 */
public final class CatalogReadSites
{
	private CatalogReadSites()
	{
	}

	/** Who a site reads for. */
	public enum Mode
	{
		PERSON, SYSTEM
	}

	/** One decided site. */
	public static final class Site
	{
		/** Simple name of the class that creates the reader. */
		public final String className;
		public final Mode mode;
		/** What is read, and where it goes. */
		public final String what;
		/** Why this session (or none). */
		public final String reason;

		Site(final String pClassName, final Mode pMode, final String pWhat, final String pReason)
		{
			this.className = pClassName;
			this.mode = pMode;
			this.what = pWhat;
			this.reason = pReason;
		}
	}

	/** Raw catalog lookups left in one class, inside the person's write procedures only. */
	public static final class WriteSide
	{
		/** How many raw lookup statements the class holds (outside {@code CatalogReader.visible}). */
		public final int lookups;
		public final String reason;

		WriteSide(final int pLookups, final String pReason)
		{
			this.lookups = pLookups;
			this.reason = pReason;
		}
	}

	private static final List<Site> SITES = new ArrayList<Site>();
	/** Raw catalog lookups allowed outside {@code CatalogReader}, per class simple name. */
	public static final Map<String, WriteSide> WRITE_SIDE;

	private static void person(final String pClass, final String pWhat, final String pReason)
	{
		CatalogReadSites.SITES.add(new Site(pClass, Mode.PERSON, pWhat, pReason));
	}

	private static void system(final String pClass, final String pWhat, final String pReason)
	{
		CatalogReadSites.SITES.add(new Site(pClass, Mode.SYSTEM, pWhat, pReason));
	}

	static
	{
		// ---------------------------------------------------------------- shown to a person (or answered on their behalf)
		CatalogReadSites.person("WizardData", "wizard snapshot: catalog sources of step 2's picker, the label of each project source, and for each landing"
			+ " table its declared key columns, scan state, read recipe and live row count",
			"shown in steps 2 and 3; a hidden part turns WizardSnapshot.catalogRestricted on (banner). Jobs that read a snapshot pass their system session");
		CatalogReadSites.person("WizardData", "step 2: for an application with no planned table, the catalog objects of its source and whether each holds a column (why there is no table)",
			"shown on the application's card (UX lot U2); hidden objects are not listed, and the banner of a restricted catalog says so");
		CatalogReadSites.person("ProjectWizardUserService", "schemas of the picked catalog source (posted pick)", "the step 2 schema checklist shows them");
		CatalogReadSites.person("ProjectWizardUserService", "schemas of the source picked from the onboarding panel", "the same step 2 schema checklist");
		CatalogReadSites.person("ProjectWizardUserService", "after a Confirm / Leave out: the decided asset's source and its still-pending tables",
			"decides whether to plan the landing, as the person who decided (the plan reads as them too)");
		CatalogReadSites.person("ProjectWizardUserService", "boards 2d / 2e: decision counts and pending deduced tables (with their columns)",
			"shown; a hidden part turns the step 2 banner on");
		CatalogReadSites.person("SourceOnboarder", "onboarding combobox: which connection already is a catalog source", "shown (wizard and GET /sources/onboarding/connections)");
		CatalogReadSites.person("SourceOnboarder", "onboarding checklist: the Source row (active, last scan status) and whether it has assets",
			"shown (wizard and GET /sources/onboarding/{connector}); hidden: the scan step says so (SourceOnboarding.Facts.catalogRestricted)");
		CatalogReadSites.person("StagingLlmSuggester", "staging proposal: the tags and tag suggestions of the catalogue columns behind the landing columns (lot S10b)",
			"given to the model as measured facts; a hidden part leaves the tags the plan copied and no suggestion");
		CatalogReadSites.person("FactoryResource", "GET /sources/onboarding/connections: catalogSourceId of each connection",
			"answered to the caller, with catalogRestricted and a message when hidden");
		CatalogReadSites.person("LandingPlanner", "plan of a landing: the assets of the catalog source with their columns and profiles, and its scanned schemas",
			"copies the assets' structure into the project: the person must see them (wizard, REST); the PLAN and onboarding jobs pass their system"
				+ " session and are queued only for a person who can see the catalog. Hidden assets refuse the plan with a message");
		CatalogReadSites.person("SourceDrifts", "applying a source drift (F3-01): the current scan of the table's asset, to recompute the change instead of trusting the stored row",
			"read as the designer who applies: a catalog they cannot see refuses the application with a message, nothing is changed");
		CatalogReadSites.person("SourcesRun", "onboarding job: schemas of the catalog source of a new application",
			"the job's system session; SourcesRun.startForProjectId queues the job only for a person who can see the catalog");
		CatalogReadSites.person("DeducedScanRun", "end of a deduced scan (UX lot C4): the decision counts of the application's tables, to plan its landing at once when all are decided",
			"the job's system session; DeducedScanRun.start queues the job only for a person who can see the catalog (requireVisible); nothing is listed, the plan itself reads as the job");
		CatalogReadSites.person("DeducedScanConfirm", "the asset a Confirm / Leave out / Rename acts on",
			"the person acts on a table they see; a hidden one is refused as such, never as 'no longer in the catalog'");
		CatalogReadSites.person("DataProductPublication", "step 7c / GET and POST .../distribution/product: the catalog assets (with their sources and"
			+ " columns) offered under 'Add assets from the catalogue'", "shown; hidden: a note and catalogRestricted in the answer, a banner in the panel");
		CatalogReadSites.person("DataProductPublication", "create: the catalog asset of each planned EBX table",
			"the product names assets the person sees (OWN to write); hidden: the write is refused with a message naming the right");
		CatalogReadSites.person("DataProductPublication", "Summary card: the data product already created for the project",
			"shown on step 8's Summary card (exists, name, refresh mode); a hidden product reads as catalogRestricted, not as created");
		CatalogReadSites.person("CatalogReader", "gate of the jobs that read the catalog on the system session (requireVisible): the sources, and the"
			+ " assets of one source", "the person who queues the job must see the catalog: LandingSourceRun PLAN, SourcesRun (onboard all, sample files),"
				+ " OnboardStepRun (register / scan a source from the panel), DeducedScanRun");

		// ------------------------------------------------------------------------------- for the project, never listed
		CatalogReadSites.system("DeducedScanCatalogWriter", "deduced-scan writer: the existing asset by key or by recipe, its columns",
			"run by the job queue on the system session; reconciles with every asset (hidden ones included) so a rescan never duplicates one");
		CatalogReadSites.system("DeducedScanCatalog", "ensureSource: the Source of a connection, every Source id",
			"decides find-or-create and an id unique among every source; nothing shown; the creation runs under the caller's session (ADMINISTER)");
		CatalogReadSites.system("DeducedScanCatalog", "rekey: whether the new key is taken, the columns to move",
			"inside a rename the caller's session performs: must see every key to refuse a collision and every column to move it");
		CatalogReadSites.system("LandingFlows", "read recipe of a landing table (resolveAssetKey, preview / key study / flows)",
			"decides how a table already planned in the project is read; what is shown comes from the source through Connectors");
		CatalogReadSites.system("LandingFlows", "read recipe and scan state of an asset key", "same decision");
		CatalogReadSites.system("LandingFlows", "asset key of each landing table of a flow generation", "same decision, in the flows / load jobs");
		CatalogReadSites.system("ConnectionCleanup", "project deletion (lot C): the catalogue source the factory created for the project, its assets, columns and what still uses them",
			"run by the deletion job on the system session (or an administrator's); nothing is listed to anybody, the rows are only counted and deleted, and EBX and the catalog's own"
				+ " delete rules check the deletion; a source or an asset something else uses is kept");
		CatalogReadSites.system("MatchingConfigurator", "labels of the catalog sources the project uses",
			"descriptions of the project's trusted sources in the matching configuration and the language-model prompt; never a listing");

		final Map<String, WriteSide> writeSide = new LinkedHashMap<String, WriteSide>();
		writeSide.put("DataProductPublication", new WriteSide(2, "byId (DataProduct by code inside writeCatalogue; also used for a Source, wrapped in CatalogReader.visible)"
			+ " and findPortByCode (OutputPort inside writeCatalogue / updatePortMappings): lookups inside procedures run with the person's session, where"
			+ " EBX and the catalog's rules check the write (OWN)"));
		WRITE_SIDE = Collections.unmodifiableMap(writeSide);
	}

	/** Every decided site, in declaration order. */
	public static List<Site> sites()
	{
		return Collections.unmodifiableList(CatalogReadSites.SITES);
	}

	/** How many sites of {@code pMode} a class has (what the test compares with the sources). */
	public static int count(final String pClassName, final Mode pMode)
	{
		int count = 0;
		for (final Site site : CatalogReadSites.SITES)
		{
			if (site.className.equals(pClassName) && site.mode == pMode)
			{
				count++;
			}
		}
		return count;
	}
}
