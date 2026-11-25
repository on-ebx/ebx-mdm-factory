package com.on.ebx.factory.master;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Reproduces the two real
 * matching plans `PLAN.md`'s "Rapprochement" entry (2026-09-21) recorded as accepted despite never being able
 * to bridge one ERP record and one CRM record: Plan A (`legalForm`, entirely null for CRM after a reload) and
 * Plan B (`country`, disjoint spellings between ERP and CRM, zero shared values).
 */
public final class CrossSystemFieldGuardTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		CrossSystemFieldGuardTest.planA_entirelyEmptyForOneSystem_refused();
		CrossSystemFieldGuardTest.planB_zeroSharedAcrossFilledSystems_refused();
		CrossSystemFieldGuardTest.lowShareNotesButDoesNotRefuse();
		CrossSystemFieldGuardTest.highShareNeverNotes();
		CrossSystemFieldGuardTest.singleSourceSystemTable_notApplicable();
		CrossSystemFieldGuardTest.bothMeasurementsMissing_notVerifiedNeverRefused();
		CrossSystemFieldGuardTest.zeroSharedWithoutConfirmedSystemCount_notVerifiedNeverRefused();
		CrossSystemFieldGuardTest.onlySharedCountMissing_notVerified();
		CrossSystemFieldGuardTest.evaluateHasNoValueCarryingParameter();
		CrossSystemFieldGuardTest.refusalNamesTheMatchKeyWhenOneIsGiven_entirelyEmptyCase();
		CrossSystemFieldGuardTest.refusalNamesTheMatchKeyWhenOneIsGiven_zeroSharedCase();
		CrossSystemFieldGuardTest.noKeyHintWhenMatchKeyNameIsNull();
		CrossSystemFieldGuardTest.evaluateWithMatchKeyOverloadHasExactlyTwoFieldNameParameters();
		CrossSystemFieldGuardTest.singleSourceSystem_withinSystemDuplicateCountGiven_evidenceShowsTheRealFinding();
		CrossSystemFieldGuardTest.singleSourceSystem_noWithinSystemDuplicateCount_fallsBackToNoEvidence();
		CrossSystemFieldGuardTest.contactShapedFixture_89Rows_zeroRepeatedEmails_threeRepeatedNamePairs();
		CrossSystemFieldGuardTest.emptySystemRefusal_namesTheEmptySystems_forThePerPathRule();
		CrossSystemFieldGuardTest.filledSystemsOnly_evaluatesTheSharedValueHalf();
		System.out.println("CrossSystemFieldGuardTest OK");
	}

	/** 2026-10-12: the "entirely empty" refusal says WHICH systems, so a caller that sees the whole tree can judge it per path; the zero-shared refusal does not. */
	private static void emptySystemRefusal_namesTheEmptySystems_forThePerPathRule()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("CRM", 85L, "ERP", 90L);
		filled.put("LOYALTY", Long.valueOf(95));
		filled.put("ECOM", Long.valueOf(0));
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("birthDate", Integer.valueOf(12), filled, Integer.valueOf(60));
		CrossSystemFieldGuardTest.check("refused", check.refuse && check.refusedOnEmptySystem());
		CrossSystemFieldGuardTest.check("names ECOM only", check.emptySystems.size() == 1 && "ECOM".equals(check.emptySystems.get(0)));
		final CrossSystemFieldGuard.Check zeroShared = CrossSystemFieldGuard.evaluate("country", Integer.valueOf(0), CrossSystemFieldGuardTest.map("ERP", 55L, "CRM", 52L),
			Integer.valueOf(28));
		CrossSystemFieldGuardTest.check("zero-shared refusal carries no empty system", zeroShared.refuse && !zeroShared.refusedOnEmptySystem() && zeroShared.emptySystems.isEmpty());
	}

	/** The shared-value half still applies to the systems that fill the field; fewer than two of them leaves nothing to judge (null). */
	private static void filledSystemsOnly_evaluatesTheSharedValueHalf()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("CRM", 85L, "ERP", 90L);
		filled.put("ECOM", Long.valueOf(0));
		final CrossSystemFieldGuard.Check zero = CrossSystemFieldGuard.evaluateFilledSystems("birthDate", Integer.valueOf(0), filled, Integer.valueOf(60), null);
		CrossSystemFieldGuardTest.check("no value shared between the two filled systems: still refused", zero != null && zero.refuse && !zero.refusedOnEmptySystem()
			&& zero.refusalDetail.toLowerCase().contains("no value is shared") && !zero.refusalDetail.contains("ECOM"));
		final CrossSystemFieldGuard.Check ok = CrossSystemFieldGuard.evaluateFilledSystems("birthDate", Integer.valueOf(12), filled, Integer.valueOf(60), null);
		CrossSystemFieldGuardTest.check("shared: usable, evidence without the empty system", ok != null && !ok.refuse && ok.evidence.contains("CRM 85 filled") && !ok.evidence.contains("ECOM"));
		final Map<String, Long> one = CrossSystemFieldGuardTest.map("CRM", 85L);
		one.put("ECOM", Long.valueOf(0));
		CrossSystemFieldGuardTest.check("a single filled system: nothing to judge", CrossSystemFieldGuard.evaluateFilledSystems("birthDate", Integer.valueOf(0), one, null, null) == null);
	}

	/**
	 * Plan A, as it stood after the reload the coordinator observed live: CRM's own {@code legalForm} became
	 * entirely null (0 of 52 filled) while ERP's stayed fully filled - refused, naming the empty system, with
	 * the measured fill counts, in one or two sentences telling a small model what to do next.
	 */
	private static void planA_entirelyEmptyForOneSystem_refused()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("ERP", 55L, "CRM", 0L);
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("legalForm", Integer.valueOf(0), filled, Integer.valueOf(3));
		CrossSystemFieldGuardTest.check("guard applies (>= 2 systems present)", check.guardApplicable);
		CrossSystemFieldGuardTest.check("refused", check.refuse);
		CrossSystemFieldGuardTest.check("names the field", check.refusalDetail.contains("legalForm"));
		CrossSystemFieldGuardTest.check("names the empty system", check.refusalDetail.contains("CRM"));
		CrossSystemFieldGuardTest.check("carries the measured fill counts", check.refusalDetail.contains("ERP 55 filled") && check.refusalDetail.contains("CRM 0 filled"));
		CrossSystemFieldGuardTest.check("tells the model what to do next", check.refusalDetail.toLowerCase().contains("remove it")
			|| check.refusalDetail.toLowerCase().contains("choose a field"));
		CrossSystemFieldGuardTest.check("never a refusal because of the empty-for-a-system message alone leaks a real field VALUE (only counts/names)",
			!check.refusalDetail.matches(".*\\b(SA|AG|PLC|Customer|Prospect|Partner)\\b.*"));
		// coordinator review, item 4: sharedCount is ALSO 0 here (entirely consistent - an empty system can never
		// share a value) - the MORE ACTIONABLE "entirely empty for source system" wording must win over the
		// generic "no value is shared" one when both conditions hold at once
		CrossSystemFieldGuardTest.check("the more actionable 'entirely empty' wording is chosen, not the generic 'no value is shared' one",
			check.refusalDetail.contains("entirely empty") && !check.refusalDetail.toLowerCase().contains("no value is shared"));
	}

	/**
	 * Plan B: {@code country} filled in both systems but written differently (Fr/France/Suisse vs. FR/CH) - no
	 * value shared at all. Refused even though every system is filled.
	 */
	private static void planB_zeroSharedAcrossFilledSystems_refused()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("ERP", 55L, "CRM", 52L);
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("country", Integer.valueOf(0), filled, Integer.valueOf(28));
		CrossSystemFieldGuardTest.check("refused", check.refuse);
		CrossSystemFieldGuardTest.check("names the field", check.refusalDetail.contains("country"));
		CrossSystemFieldGuardTest.check("says no value is shared", check.refusalDetail.toLowerCase().contains("no value is shared"));
		CrossSystemFieldGuardTest.check("carries the measured fill counts", check.refusalDetail.contains("ERP 55 filled") && check.refusalDetail.contains("CRM 52 filled"));
		CrossSystemFieldGuardTest.check("tells the model what to do next (normalise or drop it)", check.refusalDetail.toLowerCase().contains("normalising"));
	}

	/**
	 * {@code city}, the brief's own example: 3 values shared across systems out of 47 distinct - passes
	 * (never a threshold-based refusal here), and its evidence (coordinator review, item 5: "a measurement
	 * made for the guard must reach the plan's rationale when accepted") carries BOTH the shared/distinct
	 * counts AND the per-system fill breakdown, plus a "low share" emphasis since 3/47 is well under the
	 * {@link CrossSystemFieldGuard#LOW_SHARE_RATIO_FOR_NOTE} threshold.
	 */
	private static void lowShareNotesButDoesNotRefuse()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("ERP", 55L, "CRM", 52L);
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("city", Integer.valueOf(3), filled, Integer.valueOf(47));
		CrossSystemFieldGuardTest.check("not refused", !check.refuse);
		CrossSystemFieldGuardTest.check("no unverified detail", check.unverifiedDetail == null);
		CrossSystemFieldGuardTest.check("evidence present", check.evidence != null);
		CrossSystemFieldGuardTest.check("evidence names the field", check.evidence.contains("city"));
		CrossSystemFieldGuardTest.check("evidence carries the exact measured share/distinct numbers", check.evidence.contains("3 value(s) shared across systems out of 47 distinct"));
		CrossSystemFieldGuardTest.check("evidence ALSO carries the per-system fill breakdown (item 5)",
			check.evidence.contains("ERP 55 filled") && check.evidence.contains("CRM 52 filled"));
		CrossSystemFieldGuardTest.check("a low share is flagged as an emphasis, never a refusal reason", check.evidence.contains("Low share"));
	}

	/** A high cross-system share (well above the low-share threshold) still produces evidence (item 5: always, when accepted) - just without the "low share" emphasis. */
	private static void highShareNeverNotes()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("ERP", 55L, "CRM", 52L);
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("vatNo", Integer.valueOf(20), filled, Integer.valueOf(47));
		CrossSystemFieldGuardTest.check("not refused", !check.refuse);
		CrossSystemFieldGuardTest.check("evidence still present (always recorded when accepted, not only when low)", check.evidence != null);
		CrossSystemFieldGuardTest.check("evidence carries the numbers", check.evidence.contains("20 value(s) shared across systems out of 47 distinct"));
		CrossSystemFieldGuardTest.check("no low-share emphasis when the share is high", !check.evidence.contains("Low share"));
	}

	/** A single source system present in the table makes the whole cross-system question moot: never refuses, never any evidence at all. */
	private static void singleSourceSystemTable_notApplicable()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("ERP", 100L);
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("anyField", Integer.valueOf(0), filled, Integer.valueOf(10));
		CrossSystemFieldGuardTest.check("guard does not apply", !check.guardApplicable);
		CrossSystemFieldGuardTest.check("never refused", !check.refuse);
		CrossSystemFieldGuardTest.check("no unverified detail", check.unverifiedDetail == null);
		CrossSystemFieldGuardTest.check("no evidence at all", check.evidence == null);
	}

	/** Neither live measurement succeeded: never refused, "not verified" named. */
	private static void bothMeasurementsMissing_notVerifiedNeverRefused()
	{
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("vatNo", null, null, Integer.valueOf(46));
		CrossSystemFieldGuardTest.check("guard applicable (we simply don't know the system count)", check.guardApplicable);
		CrossSystemFieldGuardTest.check("never refused", !check.refuse);
		CrossSystemFieldGuardTest.check("unverified detail present", check.unverifiedDetail != null);
		CrossSystemFieldGuardTest.check("names the field", check.unverifiedDetail.contains("vatNo"));
	}

	/**
	 * A shared-value count of exactly zero, without the per-system fill measurement having confirmed there
	 * are even two systems in the table at all, is ambiguous (on a genuinely single-system table "shared by
	 * at least two systems" is trivially zero and means nothing) - treated as "not verified", never refused.
	 */
	private static void zeroSharedWithoutConfirmedSystemCount_notVerifiedNeverRefused()
	{
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("country", Integer.valueOf(0), null, Integer.valueOf(28));
		CrossSystemFieldGuardTest.check("never refused - ambiguous without a confirmed system count", !check.refuse);
		CrossSystemFieldGuardTest.check("unverified detail present", check.unverifiedDetail != null);
	}

	/** The per-system fill measurement succeeded and shows no problem, but the shared-value count itself failed: still "not verified", never refused. */
	private static void onlySharedCountMissing_notVerified()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("ERP", 55L, "CRM", 52L);
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("vatNo", null, filled, Integer.valueOf(46));
		CrossSystemFieldGuardTest.check("never refused", !check.refuse);
		CrossSystemFieldGuardTest.check("unverified detail present", check.unverifiedDetail != null);
		CrossSystemFieldGuardTest.check("names what could not be measured", check.unverifiedDetail.toLowerCase().contains("shared-value count"));
	}

	/**
	 * Structural proof, same convention as {@code MatchPlanLlmPassTest.noUngatedValueChannelExists}: {@link
	 * CrossSystemFieldGuard#evaluate} takes no {@code String}-typed parameter beyond the field's own name - no
	 * channel exists for a caller to pass an actual record VALUE into this class at all, only counts and a
	 * field name.
	 */
	private static void evaluateHasNoValueCarryingParameter() throws Exception
	{
		final Method evaluate = CrossSystemFieldGuard.class.getMethod("evaluate", String.class, Integer.class, Map.class, Integer.class);
		int stringParams = 0;
		for (final Class<?> type : evaluate.getParameterTypes())
		{
			if (type == String.class)
			{
				stringParams++;
			}
		}
		CrossSystemFieldGuardTest.check("exactly one String parameter (the field name) - no channel for an actual record value", stringParams == 1);
	}

	// ---------------------------------------------------------------------------------------------------- matching key

	/** {@link CrossSystemFieldGuard#evaluate}'s 2026-09-22 {@code pMatchKeyName} parameter: the "entirely empty for a source system" refusal names the key when one is given. */
	private static void refusalNamesTheMatchKeyWhenOneIsGiven_entirelyEmptyCase()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("ERP", 55L, "CRM", 0L);
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("legalForm", Integer.valueOf(0), filled, Integer.valueOf(3), "legalFormKey");
		CrossSystemFieldGuardTest.check("still refused", check.refuse);
		CrossSystemFieldGuardTest.check("names the key", check.refusalDetail.contains("legalFormKey"));
		CrossSystemFieldGuardTest.check("tells the model to use it instead", check.refusalDetail.toLowerCase().contains("use that instead"));
	}

	/** Same, for the "zero values shared across systems" refusal (Plan B's own shape). */
	private static void refusalNamesTheMatchKeyWhenOneIsGiven_zeroSharedCase()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("ERP", 55L, "CRM", 52L);
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("city", Integer.valueOf(0), filled, Integer.valueOf(47), "cityKey");
		CrossSystemFieldGuardTest.check("still refused", check.refuse);
		CrossSystemFieldGuardTest.check("names the key", check.refusalDetail.contains("cityKey"));
		CrossSystemFieldGuardTest.check("says no value is shared, same as without a key", check.refusalDetail.toLowerCase().contains("no value is shared"));
		CrossSystemFieldGuardTest.check("tells the model to use the key instead", check.refusalDetail.toLowerCase().contains("use that instead"));
	}

	/**
	 * The ordinary case: {@code master.MatchPlanLlmPass.Session} substitutes a source's key BEFORE ever calling
	 * this method whenever the key is resolvable, so by the time the guard runs it is usually already evaluating
	 * the KEY itself - {@code pMatchKeyName} is then null (a key has no further key of its own), and the refusal
	 * carries no "use ... instead" hint, exactly like the pre-2026-09-22 4-arg overload.
	 */
	private static void noKeyHintWhenMatchKeyNameIsNull()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("ERP", 55L, "CRM", 52L);
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("cityKey", Integer.valueOf(0), filled, Integer.valueOf(40), null);
		CrossSystemFieldGuardTest.check("refused", check.refuse);
		CrossSystemFieldGuardTest.check("no 'use ... instead' hint when no key name is given", !check.refusalDetail.toLowerCase().contains("use that instead"));
	}

	/**
	 * Structural proof for the 2026-09-22 {@code pMatchKeyName} overload: exactly two {@code String}-typed
	 * parameters (the field's own name, and its optional matching key's own name) - both purely structural field
	 * NAMES, never a channel for an actual record value (same convention {@link #evaluateHasNoValueCarryingParameter}
	 * already established for the 4-arg overload).
	 */
	private static void evaluateWithMatchKeyOverloadHasExactlyTwoFieldNameParameters() throws Exception
	{
		final Method evaluate = CrossSystemFieldGuard.class.getMethod("evaluate", String.class, Integer.class, Map.class, Integer.class, String.class);
		int stringParams = 0;
		for (final Class<?> type : evaluate.getParameterTypes())
		{
			if (type == String.class)
			{
				stringParams++;
			}
		}
		CrossSystemFieldGuardTest.check("exactly two String parameters (the field's own name and its optional matching key's own name) - still no value channel",
			stringParams == 2);
	}

	// ------------------------------------------------------------------- 2026-09-22, "child entities matched too"

	/**
	 * On a single-source-system table, {@link CrossSystemFieldGuard#evaluate(String, Integer, Map, Integer,
	 * String, Integer)}'s new {@code pWithinSystemDuplicateCount} parameter turns the old bare "does not apply"
	 * sentence into an actual finding - single-source matching is not a lesser case (CLAUDE.md &sect;8), so its
	 * own evidence should show what WAS measured.
	 */
	private static void singleSourceSystem_withinSystemDuplicateCountGiven_evidenceShowsTheRealFinding()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("CRM", 89L);
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("email", null, filled, Integer.valueOf(89), null, Integer.valueOf(0));
		CrossSystemFieldGuardTest.check("guard does not apply (single system)", !check.guardApplicable);
		CrossSystemFieldGuardTest.check("never refused", !check.refuse);
		CrossSystemFieldGuardTest.check("evidence is now populated, not null", check.evidence != null);
		CrossSystemFieldGuardTest.check("names the field", check.evidence.contains("email"));
		CrossSystemFieldGuardTest.check("names the single system", check.evidence.contains("CRM"));
		CrossSystemFieldGuardTest.check("says single source system", check.evidence.contains("single source system"));
		CrossSystemFieldGuardTest.check("carries the real within-system-duplicate count (0)", check.evidence.contains("0 value(s) repeated within this source"));
		CrossSystemFieldGuardTest.check("carries the distinct count (89) when known", check.evidence.contains("out of 89 distinct"));
	}

	/**
	 * The 4-/5-arg overloads (every caller before this lot) still answer {@code evidence == null} on a
	 * single-source table, byte-for-byte the original behaviour - the new evidence text is ADDITIVE, never a
	 * behaviour change for a caller that never wires the new lookup (see {@link #singleSourceSystemTable_notApplicable}
	 * above for the same proof on the 4-arg overload).
	 */
	private static void singleSourceSystem_noWithinSystemDuplicateCount_fallsBackToNoEvidence()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("CRM", 89L);
		final CrossSystemFieldGuard.Check check = CrossSystemFieldGuard.evaluate("email", null, filled, Integer.valueOf(89), null, null);
		CrossSystemFieldGuardTest.check("guard does not apply", !check.guardApplicable);
		CrossSystemFieldGuardTest.check("never refused", !check.refuse);
		CrossSystemFieldGuardTest.check("no evidence when the within-system count was never measured/wired", check.evidence == null);
	}

	/**
	 * The exact demo fixture the task brief names: the real master zone's {@code Contact} table, 89 rows, single
	 * source system CRM - {@code email} has 89 distinct values among 89 filled (0 repeated within this system,
	 * i.e. no duplicate at all), while a name field has 3 repeated pairs among its distinct values (real
	 * homonyms - Emma Garnier/Jan Huber/Lucas Brown, each pair with different emails and different companies,
	 * per the task brief - exactly the ambiguous case a human steward must decide, never silently excluded).
	 * Never run live (no EBX access this session) - a hand-built fixture over the pure guard logic, the same
	 * discipline {@code MatchPlanProfileReaderTest}/{@code MatchPlanLlmPassTest} already use for their own
	 * MCP-response fixtures.
	 */
	private static void contactShapedFixture_89Rows_zeroRepeatedEmails_threeRepeatedNamePairs()
	{
		final Map<String, Long> filled = CrossSystemFieldGuardTest.map("CRM", 89L);
		final CrossSystemFieldGuard.Check emailCheck = CrossSystemFieldGuard.evaluate("email", null, filled, Integer.valueOf(89), null, Integer.valueOf(0));
		CrossSystemFieldGuardTest.check("email: never refused (single-source matching is not a lesser case)", !emailCheck.refuse);
		CrossSystemFieldGuardTest.check("email: 0 repeated within the single source system", emailCheck.evidence.contains("0 value(s) repeated within this source"));

		final CrossSystemFieldGuard.Check nameCheck = CrossSystemFieldGuard.evaluate("lastName", null, filled, Integer.valueOf(86), null, Integer.valueOf(3));
		CrossSystemFieldGuardTest.check("lastName: never refused either", !nameCheck.refuse);
		CrossSystemFieldGuardTest.check("lastName: 3 repeated pairs within the single source system - the real homonym signal",
			nameCheck.evidence.contains("3 value(s) repeated within this source"));
		CrossSystemFieldGuardTest.check("lastName: out of 86 distinct (89 rows, 3 collisions -> 86 distinct values)", nameCheck.evidence.contains("out of 86 distinct"));
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	private static Map<String, Long> map(final String pSystem1, final long pFilled1)
	{
		final Map<String, Long> out = new LinkedHashMap<String, Long>();
		out.put(pSystem1, Long.valueOf(pFilled1));
		return out;
	}

	private static Map<String, Long> map(final String pSystem1, final long pFilled1, final String pSystem2, final long pFilled2)
	{
		final Map<String, Long> out = new LinkedHashMap<String, Long>();
		out.put(pSystem1, Long.valueOf(pFilled1));
		out.put(pSystem2, Long.valueOf(pFilled2));
		return out;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
