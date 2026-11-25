package com.on.ebx.factory.ebx;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.orchestranetworks.interactions.SessionInteraction;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.SessionPermissions;
import com.orchestranetworks.service.UserReference;
import com.orchestranetworks.service.directory.DirectoryHandler;

/**
 * JUnit-free, like the rest of this package's tests. Exercises the job-engine lot C1 (2026-09-24) additions of
 * {@link SourcesRun} that do not need a live repository: {@link SourcesRun#entryState} (the {@code FactoryJob}
 * status -&gt; wizard-vocabulary mapping {@link #status} builds each card from) and {@link SourcesRun#locale}
 * (the job's captured {@code "locale"} param -&gt; a plain {@link Locale}, falling back to the job session's own
 * locale). Both are package-visible for exactly this reason, same convention as {@link SourcesRunUniqueCodeTest}
 * for {@link SourcesRun#uniqueCode}.
 * <p>
 * job-session-unwrapped fix (2026-09-25): {@link SourcesRun#locale} replaces the earlier {@code localize}, which
 * built a whole hand-made {@code implements Session} wrapper ({@code LocaleOverrideSession}) just to fake {@link
 * Session#getLocale()} - that wrapper broke live the first time it reached EBX/Connectors code that casts a
 * {@code Session} to the kernel's own concrete implementation (a {@code ClassCastException}, project
 * DeducedTest, {@code DEDUCED_SCAN} job). The job's own EBX/MCP calls now always use the real job session; only
 * the locale for a user-facing message travels separately, as the plain value this test exercises.
 */
public final class SourcesRunJobMappingTest
{
	public static void main(final String[] pArgs)
	{
		SourcesRunJobMappingTest.everyJobStatusMapsToAWizardState();
		SourcesRunJobMappingTest.blankLanguageTagFallsBackToTheSessionLocale();
		SourcesRunJobMappingTest.unparsableLanguageTagFallsBackToTheSessionLocale();
		SourcesRunJobMappingTest.languageTagOverridesTheSessionLocale();
		SourcesRunJobMappingTest.noSessionAndNoTagFallsBackToEnglish();

		System.out.println("SourcesRunJobMappingTest OK");
	}

	// ---------------------------------------------------------------------------------------------- entryState

	private static void everyJobStatusMapsToAWizardState()
	{
		SourcesRunJobMappingTest.check("QUEUED -> WAITING", "WAITING".equals(SourcesRun.entryState(FactoryJobQueue.QUEUED)));
		SourcesRunJobMappingTest.check("RUNNING -> RUNNING", "RUNNING".equals(SourcesRun.entryState(FactoryJobQueue.RUNNING)));
		SourcesRunJobMappingTest.check("DONE -> DONE", "DONE".equals(SourcesRun.entryState(FactoryJobQueue.DONE)));
		SourcesRunJobMappingTest.check("FAILED -> FAILED", "FAILED".equals(SourcesRun.entryState(FactoryJobQueue.FAILED)));
		// defensive: an unrecognised status (should never happen - batchJobs only selects these four) still
		// answers a value the wizard's message bundle actually has a key for, never null/blank.
		SourcesRunJobMappingTest.check("unknown status falls back to FAILED, never null", "FAILED".equals(SourcesRun.entryState("SOMETHING_ELSE")));
	}

	// ---------------------------------------------------------------------------------------------------- locale

	private static void blankLanguageTagFallsBackToTheSessionLocale()
	{
		final Session base = new FakeSession(Locale.GERMANY);
		SourcesRunJobMappingTest.check("null tag falls back to the session's locale", Locale.GERMANY.equals(SourcesRun.locale(base, null)));
		SourcesRunJobMappingTest.check("empty tag falls back to the session's locale", Locale.GERMANY.equals(SourcesRun.locale(base, "")));
		SourcesRunJobMappingTest.check("blank tag falls back to the session's locale", Locale.GERMANY.equals(SourcesRun.locale(base, "   ")));
	}

	private static void unparsableLanguageTagFallsBackToTheSessionLocale()
	{
		// Locale.forLanguageTag never throws (it returns Locale.ROOT / a best-effort locale for garbage input),
		// so a truly unparsable tag never happens in practice - this only documents that locale() never blows
		// up on one either, same posture the old localize() test had for a corrupted/pre-lot-C1 job resumed
		// after a restart.
		final Session base = new FakeSession(Locale.GERMANY);
		final Locale resolved = SourcesRun.locale(base, "not a real tag !!");
		SourcesRunJobMappingTest.check("garbage tag still produces a usable locale", resolved != null);
	}

	private static void languageTagOverridesTheSessionLocale()
	{
		final Session base = new FakeSession(Locale.GERMANY);
		final Locale resolved = SourcesRun.locale(base, "fr-FR");
		SourcesRunJobMappingTest.check("locale is overridden", Locale.forLanguageTag("fr-FR").equals(resolved));
		SourcesRunJobMappingTest.check("locale is NOT the session's own", !base.getLocale().equals(resolved));
	}

	private static void noSessionAndNoTagFallsBackToEnglish()
	{
		SourcesRunJobMappingTest.check("no session, no tag -> English", Locale.ENGLISH.equals(SourcesRun.locale(null, "")));
		SourcesRunJobMappingTest.check("no session, blank tag -> English", Locale.ENGLISH.equals(SourcesRun.locale(null, "   ")));
	}

	// ---------------------------------------------------------------------------------------------------- fixtures

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	/** The minimum {@link Session} fake needed to exercise {@link SourcesRun#locale} without a live repository. */
	private static final class FakeSession implements Session
	{
		private final Locale locale;

		FakeSession(final Locale pLocale)
		{
			this.locale = pLocale;
		}

		@Override
		public Locale getLocale()
		{
			return this.locale;
		}

		@Override
		public String getIpAddress()
		{
			return "127.0.0.1";
		}

		@Override
		public String getHttpSessionId()
		{
			return "fake";
		}

		@Override
		public String getTrackingInfo()
		{
			return "fake";
		}

		@Override
		public UserReference getUserReference()
		{
			return null;
		}

		@Override
		public String getUserEmail()
		{
			return "system@example.test";
		}

		@Override
		public DirectoryHandler getDirectory()
		{
			return null;
		}

		@Override
		public SessionInteraction getInteraction(final boolean pCreateIfNull)
		{
			return null;
		}

		@Override
		public boolean isInWorkflowInteraction(final boolean pCreateIfNull)
		{
			return false;
		}

		@Override
		public String getInputParameterValue(final boolean pCreateIfNull, final String pName)
		{
			return null;
		}

		@Override
		public SessionPermissions getPermissions()
		{
			return null;
		}

		@Override
		public boolean isUserInRole(final Profile pProfile)
		{
			return true;
		}

		@Override
		public boolean isUserInRoles(final Iterable<Profile> pProfiles)
		{
			return true;
		}

		@Override
		public void clearCache()
		{
			// no-op
		}

		@Override
		public boolean isSystemUser()
		{
			return true;
		}

		@Override
		public boolean isUserOwner(final Adaptation pAdaptation)
		{
			return false;
		}

		@Override
		public boolean isUserOwner(final AdaptationHome pHome)
		{
			return false;
		}

		@Override
		public Object getAttribute(final String pName)
		{
			return null;
		}

		@Override
		public Set<String> getAttributeNames()
		{
			return java.util.Collections.emptySet();
		}

		@Override
		public void setAttribute(final String pName, final Object pValue)
		{
			// no-op
		}

		@Override
		public Session clone(final Map<String, String> pParameters)
		{
			return this;
		}
	}
}
