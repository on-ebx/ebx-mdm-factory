package com.on.ebx.factory.staging;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Lot M5 (2026-10-04). */
public final class ScriptDerivationGateTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		ScriptDerivationGateTest.appliedMeansAcceptedWithTheModelAndTheElementSet();
		ScriptDerivationGateTest.onlyTheUnappliedEntriesAreKept();
		ScriptDerivationGateTest.theLoadNoteSaysLeftEmptyAndHowToApply();
		ScriptDerivationGateTest.policyFieldNamesReadClusteringAndEveryTreeNode();
		ScriptDerivationGateTest.planWarningsOnlyForComparedUnappliedAttributesOfTheEntity();
		ScriptDerivationGateTest.reapplyIsAMarkOnAnAppliedEntryAndNeverChangesApplied();
		ScriptDerivationGateTest.theLoadAppliesOnlyWhatIsAcceptedAndNotApplied();
		ScriptDerivationGateTest.aDerivationWaitingForAParameterIsNotTriedAndSaysWhatToEnter();
		System.out.println("ScriptDerivationGateTest OK");
	}

	private static void appliedMeansAcceptedWithTheModelAndTheElementSet()
	{
		ScriptDerivationGateTest.check("applied", ScriptDerivationGate.isApplied("APPLIED", "Vol1m_Staging", "/root/Personne/phoneE164"));
		ScriptDerivationGateTest.check("accepted with both set counts", ScriptDerivationGate.isApplied("ACCEPTED", "Vol1m_Staging", "/root/Personne/phoneE164"));
		ScriptDerivationGateTest.check("the Vol1m case: ACCEPTED, appliedModel empty", !ScriptDerivationGate.isApplied("ACCEPTED", null, null));
		ScriptDerivationGateTest.check("blank model", !ScriptDerivationGate.isApplied("APPLIED", " ", "/root/Personne/phoneE164"));
		ScriptDerivationGateTest.check("blank element", !ScriptDerivationGate.isApplied("APPLIED", "Vol1m_Staging", ""));
		ScriptDerivationGateTest.check("proposed or rejected never applied", !ScriptDerivationGate.isApplied("PROPOSED", "m", "/p") && !ScriptDerivationGate.isApplied("REJECTED", "m", "/p"));
		ScriptDerivationGateTest.check("the kind", ScriptDerivationGate.isScript("SCRIPT") && !ScriptDerivationGate.isScript("FOLD_KEY") && !ScriptDerivationGate.isScript(null));
	}

	private static List<ScriptDerivationGate.Entry> entries()
	{
		return Arrays.asList(new ScriptDerivationGate.Entry("Personne", "phoneE164", "phone", "107", false),
			new ScriptDerivationGate.Entry("Personne", "emailNorm", "email", "108", true), new ScriptDerivationGate.Entry("Adresse", "zipNorm", "zip", null, false));
	}

	private static void onlyTheUnappliedEntriesAreKept()
	{
		ScriptDerivationGateTest.check("two unapplied", ScriptDerivationGate.notApplied(ScriptDerivationGateTest.entries()).size() == 2);
		ScriptDerivationGateTest.check("null reads as empty", ScriptDerivationGate.notApplied(null).isEmpty());
		ScriptDerivationGateTest.check("names of one entity", ScriptDerivationGate.notAppliedNames(ScriptDerivationGateTest.entries(), "Personne").toString().equals("[phoneE164]"));
	}

	private static void theLoadNoteSaysLeftEmptyAndHowToApply()
	{
		final String note = ScriptDerivationGate.loadNote(ScriptDerivationGateTest.entries().get(0));
		ScriptDerivationGateTest.check("note: " + note, note.startsWith("phoneE164 is declared as a script derivation that is not applied: left empty")
			&& note.contains("Proposal review") && note.contains("capabilities/107/apply"));
		ScriptDerivationGateTest.check("no suggestion at all still gives a usable note", ScriptDerivationGate.loadNote(ScriptDerivationGateTest.entries().get(2)).contains("{id}"));
		ScriptDerivationGateTest.check("open point names the entity, the source and the capability",
			ScriptDerivationGate.openPointText(ScriptDerivationGateTest.entries().get(0)).equals(
				"Personne.phoneE164 (computed from phone) is a script derivation that is not applied: it stays empty until it is applied (capability 107)"));
	}

	private static JsonNode policy() throws Exception
	{
		return new ObjectMapper().readTree("{\"fields\":[{\"field\":\"/lastName\"},{\"field\":\"/phoneE164\"}],"
			+ "\"decisionTree\":{\"fields\":[{\"field\":\"/lastName\"}],\"trueNode\":{\"fields\":[{\"field\":\"/emailNorm\"}]},\"falseNode\":{\"fields\":[{\"field\":\"/zipNorm\"}]}}}");
	}

	private static void policyFieldNamesReadClusteringAndEveryTreeNode() throws Exception
	{
		ScriptDerivationGateTest.check("clustering and tree fields, bare, once each", ScriptDerivationGate.policyFieldNames(ScriptDerivationGateTest.policy()).toString()
			.equals("[lastName, phoneE164, emailNorm, zipNorm]"));
		ScriptDerivationGateTest.check("a missing policy is empty", ScriptDerivationGate.policyFieldNames(null).isEmpty());
	}

	private static void planWarningsOnlyForComparedUnappliedAttributesOfTheEntity() throws Exception
	{
		final List<String> warnings = ScriptDerivationGate.planWarnings(ScriptDerivationGateTest.entries(), "Personne", ScriptDerivationGate.policyFieldNames(ScriptDerivationGateTest.policy()));
		ScriptDerivationGateTest.check("one warning: " + warnings, warnings.size() == 1 && warnings.get(0).startsWith("Personne.phoneE164 is compared by the matching plan")
			&& warnings.get(0).contains("capability 107"));
		ScriptDerivationGateTest.check("an applied one (emailNorm) is not warned about", !warnings.toString().contains("emailNorm"));
		ScriptDerivationGateTest.check("another entity's unapplied attribute is not warned about here", !warnings.toString().contains("zipNorm"));
		ScriptDerivationGateTest.check("a plan comparing none of them has no warning", ScriptDerivationGate.planWarnings(ScriptDerivationGateTest.entries(), "Personne",
			Arrays.asList("lastName")).isEmpty());
		ScriptDerivationGateTest.check("null fields read as no warning", ScriptDerivationGate.planWarnings(ScriptDerivationGateTest.entries(), "Personne", null).isEmpty());
	}

	/** Lot P1 (2026-10-04): an applied derivation whose parameters changed since is still APPLIED (the loader leaves it to the triggers), but flagged for a new apply. */
	private static void reapplyIsAMarkOnAnAppliedEntryAndNeverChangesApplied() throws Exception
	{
		final ScriptDerivationGate.Entry stale = new ScriptDerivationGate.Entry("Personne", "emailNorm", "email", "108", true, true);
		final ScriptDerivationGate.Entry notApplied = new ScriptDerivationGate.Entry("Personne", "phoneE164", "phone", "107", false, true);
		ScriptDerivationGateTest.check("an applied entry carries the mark and stays applied", stale.reapplyNeeded && stale.applied);
		ScriptDerivationGateTest.check("an unapplied entry never carries it", !notApplied.reapplyNeeded);
		ScriptDerivationGateTest.check("the five-argument constructor reads as no re-apply", !new ScriptDerivationGate.Entry("P", "a", null, "1", true).reapplyNeeded);
		final List<ScriptDerivationGate.Entry> all = Arrays.asList(stale, notApplied);
		ScriptDerivationGateTest.check("the loader's list of unapplied entries is untouched by the mark", ScriptDerivationGate.notApplied(all).size() == 1
			&& ScriptDerivationGate.notApplied(all).get(0).attributeName.equals("phoneE164"));
		ScriptDerivationGateTest.check("only the marked entry is listed for a new apply", ScriptDerivationGate.reapplyNeeded(all).size() == 1
			&& ScriptDerivationGate.reapplyNeeded(all).get(0).attributeName.equals("emailNorm") && ScriptDerivationGate.reapplyNeeded(null).isEmpty());
		ScriptDerivationGateTest.check("its sentence: " + ScriptDerivationGate.reapplyText(stale), ScriptDerivationGate.reapplyText(stale).equals(
			"Personne.emailNorm (computed from email) is a script derivation whose parameters changed since it was applied: its stored values are stale until it is applied again (capability 108)"));
		final List<String> warnings = ScriptDerivationGate.planWarnings(all, "Personne", ScriptDerivationGate.policyFieldNames(ScriptDerivationGateTest.policy()));
		ScriptDerivationGateTest.check("a compared stale derivation is warned about, with its capability: " + warnings, warnings.size() == 2
			&& warnings.toString().contains("Personne.emailNorm is compared by the matching plan but its parameters changed") && warnings.toString().contains("capability 108"));
	}

	/** Fix/staging-proposal-quality (2026-10-17, item 1): accepted but never applied (capabilities 201/202 of TiersB2CRetestLanding) is applied by the load itself. */
	private static void theLoadAppliesOnlyWhatIsAcceptedAndNotApplied()
	{
		final List<ScriptDerivationGate.Entry> entries = Arrays.asList(new ScriptDerivationGate.Entry("Customer", "phoneE164", "mobilePhone", "201", false),
			new ScriptDerivationGate.Entry("Customer", "personNameKey", "lastName", "202", false), new ScriptDerivationGate.Entry("Customer", "emailNorm", "email", "203", true),
			new ScriptDerivationGate.Entry("Customer", "zipNorm", "zip", "204", false), new ScriptDerivationGate.Entry("Customer", "orphan", "zip", null, false));
		final List<String> ids = ScriptDerivationGate.idsToApply(entries, Arrays.asList("201", "202", "203"));
		ScriptDerivationGateTest.check("the two accepted unapplied ones, in order: " + ids, ids.equals(Arrays.asList("201", "202")));
		ScriptDerivationGateTest.check("an applied one is left alone", !ids.contains("203"));
		ScriptDerivationGateTest.check("a not accepted one (204) and one without suggestion are not applied", !ids.contains("204") && ids.size() == 2);
		ScriptDerivationGateTest.check("nothing accepted, nothing applied", ScriptDerivationGate.idsToApply(entries, java.util.Collections.<String>emptyList()).isEmpty()
			&& ScriptDerivationGate.idsToApply(entries, null).isEmpty() && ScriptDerivationGate.idsToApply(null, Arrays.asList("201")).isEmpty());
		ScriptDerivationGateTest.check("a suggestion naming two attributes is applied once", ScriptDerivationGate.idsToApply(Arrays.asList(
			new ScriptDerivationGate.Entry("A", "x", null, "9", false), new ScriptDerivationGate.Entry("A", "y", null, "9", false)), Arrays.asList("9")).size() == 1);
	}

	/** Lot G1 (2026-10-17, item 3): capability 226 of TiersB2CRetestFinal - accepted, defaultCountry to be provided. */
	private static void aDerivationWaitingForAParameterIsNotTriedAndSaysWhatToEnter()
	{
		final List<ScriptDerivationGate.Entry> entries = Arrays.asList(
			new ScriptDerivationGate.Entry("Client", "mobileE164", "mobile", "226", false, false, Arrays.asList("defaultCountry"), null),
			new ScriptDerivationGate.Entry("Client", "nameKey", "lastName", "227", false, false, Arrays.<String> asList(), null),
			new ScriptDerivationGate.Entry("Client", "emailKey", "email", "228", true, false, Arrays.asList("ignoredOnceApplied"), new java.util.Date(5L)));
		ScriptDerivationGateTest.check("waits", entries.get(0).waitsForParameter() && !entries.get(1).waitsForParameter());
		ScriptDerivationGateTest.check("an applied entry waits for nothing and keeps its apply time", !entries.get(2).waitsForParameter() && entries.get(2).waitingParameters.isEmpty()
			&& entries.get(2).appliedAt != null);
		ScriptDerivationGateTest.check("an unapplied entry has no apply time", entries.get(0).appliedAt == null);
		ScriptDerivationGateTest.check("only the waiting one is listed", ScriptDerivationGate.waitingForParameter(entries).size() == 1
			&& "226".equals(ScriptDerivationGate.waitingForParameter(entries).get(0).suggestionId));
		ScriptDerivationGateTest.check("null reads as empty", ScriptDerivationGate.waitingForParameter(null).isEmpty());
		// the load must not try to apply it (the apply refuses a binding with a toBeProvided entry): the other accepted one is still tried
		final List<String> ids = ScriptDerivationGate.idsToApply(entries, Arrays.asList("226", "227", "228"));
		ScriptDerivationGateTest.check(ids.toString(), ids.toString().equals("[227]"));
		final String note = ScriptDerivationGate.loadNote(entries.get(0));
		ScriptDerivationGateTest.check(note, note.startsWith("mobileE164 is a script derivation that waits for the parameter(s) [defaultCountry]") && note.contains("step 4")
			&& note.contains("Build and load"));
		ScriptDerivationGateTest.check("the ordinary note is unchanged", ScriptDerivationGate.loadNote(entries.get(1)).contains("declared as a script derivation that is not applied"));
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
