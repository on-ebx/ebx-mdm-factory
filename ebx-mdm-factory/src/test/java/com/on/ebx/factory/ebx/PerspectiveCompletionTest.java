package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

/** JUnit-free: run the main, a failure throws. */
public final class PerspectiveCompletionTest
{
	public static void main(final String[] pArgs)
	{
		PerspectiveCompletionTest.nothingMissingYieldsNoAdditions();
		PerspectiveCompletionTest.aWhollyMissingSectionIsAddedInFull();
		PerspectiveCompletionTest.oneMissingItemInAnExistingSectionIsAddedAlone();
		PerspectiveCompletionTest.existingItemsAndTheirOrderAreNeverReturnedAsAdditions();
		PerspectiveCompletionTest.differentTargetIsNotAMatchEvenWithTheSameLabel();
		PerspectiveCompletionTest.sameTargetIsAMatchEvenWithADifferentLabel();
		PerspectiveCompletionTest.additionsPreservePackagedOrder();
		PerspectiveCompletionTest.liveIncidentReproduction_existingSectionMissingOneItem_carriesTheRealExistingSectionId();

		System.out.println("PerspectiveCompletionTest OK");
	}

	private static void nothingMissingYieldsNoAdditions()
	{
		final List<PerspectiveCompletion.Section<String>> existing = new ArrayList<PerspectiveCompletion.Section<String>>();
		existing.add(PerspectiveCompletionTest.existingSection("s1", "Settings", PerspectiveCompletionTest.existingItem("Connection", "svc:FactoryConnection")));
		final List<PerspectiveCompletion.Section<String>> packaged = new ArrayList<PerspectiveCompletion.Section<String>>();
		packaged.add(PerspectiveCompletionTest.packagedSection("Settings", PerspectiveCompletionTest.packagedItem("Connection", "svc:FactoryConnection")));

		final List<PerspectiveCompletion.Addition<String>> additions = PerspectiveCompletion.decide(existing, packaged);
		PerspectiveCompletionTest.check("a perspective that already matches the package gets no additions", additions.isEmpty());
	}

	private static void aWhollyMissingSectionIsAddedInFull()
	{
		final List<PerspectiveCompletion.Section<String>> existing = new ArrayList<PerspectiveCompletion.Section<String>>();
		existing.add(PerspectiveCompletionTest.existingSection("s1", "Projects", PerspectiveCompletionTest.existingItem("Projects", "table:Project")));
		final List<PerspectiveCompletion.Section<String>> packaged = new ArrayList<PerspectiveCompletion.Section<String>>();
		packaged.add(PerspectiveCompletionTest.packagedSection("Projects", PerspectiveCompletionTest.packagedItem("Projects", "table:Project")));
		packaged.add(PerspectiveCompletionTest.packagedSection("Operations", PerspectiveCompletionTest.packagedItem("Pipeline steps", "table:PipelineStep"),
			PerspectiveCompletionTest.packagedItem("Agent runs", "table:AgentRun")));

		final List<PerspectiveCompletion.Addition<String>> additions = PerspectiveCompletion.decide(existing, packaged);
		PerspectiveCompletionTest.check("exactly one addition (the whole missing section)", additions.size() == 1);
		final PerspectiveCompletion.Addition<String> addition = additions.get(0);
		PerspectiveCompletionTest.check("it is a brand new section", addition.newSection);
		PerspectiveCompletionTest.check("a new section carries no existing section id: there is none", addition.existingSectionId == null);
		PerspectiveCompletionTest.check("Operations is the missing section", "Operations".equals(addition.packagedSection.labelEn));
		PerspectiveCompletionTest.check("every one of its packaged items is included", addition.missingItems.size() == 2);
	}

	private static void oneMissingItemInAnExistingSectionIsAddedAlone()
	{
		final List<PerspectiveCompletion.Section<String>> existing = new ArrayList<PerspectiveCompletion.Section<String>>();
		existing.add(PerspectiveCompletionTest.existingSection("s1", "Settings", PerspectiveCompletionTest.existingItem("Connection", "svc:FactoryConnection")));
		final List<PerspectiveCompletion.Section<String>> packaged = new ArrayList<PerspectiveCompletion.Section<String>>();
		// the coordinator's exact scenario: an item added to the packaged JSON after the perspective was first installed
		packaged.add(PerspectiveCompletionTest.packagedSection("Settings", PerspectiveCompletionTest.packagedItem("Connection", "svc:FactoryConnection"),
			PerspectiveCompletionTest.packagedItem("Settings", "table:FactorySettings")));

		final List<PerspectiveCompletion.Addition<String>> additions = PerspectiveCompletion.decide(existing, packaged);
		PerspectiveCompletionTest.check("one addition, for the existing section", additions.size() == 1);
		final PerspectiveCompletion.Addition<String> addition = additions.get(0);
		PerspectiveCompletionTest.check("it is NOT a new section: Settings already exists", !addition.newSection);
		PerspectiveCompletionTest.check("its existing section id is carried, typed, not read off the packaged side", "s1".equals(addition.existingSectionId));
		PerspectiveCompletionTest.check("only the missing item, not the one already there", addition.missingItems.size() == 1);
		PerspectiveCompletionTest.check("the missing item is Settings", "Settings".equals(addition.missingItems.get(0).labelEn));
	}

	private static void existingItemsAndTheirOrderAreNeverReturnedAsAdditions()
	{
		final List<PerspectiveCompletion.Section<String>> existing = new ArrayList<PerspectiveCompletion.Section<String>>();
		// a person reordered/added their own third item under Settings: none of this must be reported as missing
		existing.add(PerspectiveCompletionTest.existingSection("s1", "Settings", PerspectiveCompletionTest.existingItem("Settings", "table:FactorySettings"),
			PerspectiveCompletionTest.existingItem("My own link", "table:SomethingElse"), PerspectiveCompletionTest.existingItem("Connection", "svc:FactoryConnection")));
		final List<PerspectiveCompletion.Section<String>> packaged = new ArrayList<PerspectiveCompletion.Section<String>>();
		packaged.add(PerspectiveCompletionTest.packagedSection("Settings", PerspectiveCompletionTest.packagedItem("Connection", "svc:FactoryConnection"),
			PerspectiveCompletionTest.packagedItem("Settings", "table:FactorySettings")));

		final List<PerspectiveCompletion.Addition<String>> additions = PerspectiveCompletion.decide(existing, packaged);
		PerspectiveCompletionTest.check("both packaged items are already present (in a different order, plus a custom one): nothing to add",
			additions.isEmpty());
	}

	/** Same label, different target: e.g. a person repointed "Connection" at their own service. Still not the same item. */
	private static void differentTargetIsNotAMatchEvenWithTheSameLabel()
	{
		final List<PerspectiveCompletion.Section<String>> existing = new ArrayList<PerspectiveCompletion.Section<String>>();
		existing.add(PerspectiveCompletionTest.existingSection("s1", "Settings", PerspectiveCompletionTest.existingItem("Connection", "svc:SomeoneElsesService")));
		final List<PerspectiveCompletion.Section<String>> packaged = new ArrayList<PerspectiveCompletion.Section<String>>();
		packaged.add(PerspectiveCompletionTest.packagedSection("Settings", PerspectiveCompletionTest.packagedItem("Connection", "svc:FactoryConnection")));

		final List<PerspectiveCompletion.Addition<String>> additions = PerspectiveCompletion.decide(existing, packaged);
		PerspectiveCompletionTest.check("a same-label, different-target item does not count as a match", additions.size() == 1);
		PerspectiveCompletionTest.check("the packaged Connection item is reported missing", "Connection".equals(additions.get(0).missingItems.get(0).labelEn));
	}

	/** The coordinator's fix (2026-09-21): a target match is "exists" even when the label differs - a relabelled
	 * item (translated, restyled, hand-edited once live) is still the same menu entry, not a duplicate to add. */
	private static void sameTargetIsAMatchEvenWithADifferentLabel()
	{
		final List<PerspectiveCompletion.Section<String>> existing = new ArrayList<PerspectiveCompletion.Section<String>>();
		existing.add(PerspectiveCompletionTest.existingSection("s1", "Settings",
			PerspectiveCompletionTest.existingItem("Connexion (renamed live)", "svc:FactoryConnection")));
		final List<PerspectiveCompletion.Section<String>> packaged = new ArrayList<PerspectiveCompletion.Section<String>>();
		packaged.add(PerspectiveCompletionTest.packagedSection("Settings", PerspectiveCompletionTest.packagedItem("Connection", "svc:FactoryConnection")));

		final List<PerspectiveCompletion.Addition<String>> additions = PerspectiveCompletion.decide(existing, packaged);
		PerspectiveCompletionTest.check("no addition: the renamed item still matches by target, so it is not added again as a duplicate", additions.isEmpty());
	}

	private static void additionsPreservePackagedOrder()
	{
		final List<PerspectiveCompletion.Section<String>> existing = new ArrayList<PerspectiveCompletion.Section<String>>();
		final List<PerspectiveCompletion.Section<String>> packaged = new ArrayList<PerspectiveCompletion.Section<String>>();
		packaged.add(PerspectiveCompletionTest.packagedSection("Alpha", PerspectiveCompletionTest.packagedItem("A", "t:A")));
		packaged.add(PerspectiveCompletionTest.packagedSection("Beta", PerspectiveCompletionTest.packagedItem("B", "t:B")));
		packaged.add(PerspectiveCompletionTest.packagedSection("Gamma", PerspectiveCompletionTest.packagedItem("C", "t:C")));

		final List<PerspectiveCompletion.Addition<String>> additions = PerspectiveCompletion.decide(existing, packaged);
		PerspectiveCompletionTest.check("three new sections, in the packaged JSON's own order", additions.size() == 3);
		PerspectiveCompletionTest.check("Alpha first", "Alpha".equals(additions.get(0).packagedSection.labelEn));
		PerspectiveCompletionTest.check("Beta second", "Beta".equals(additions.get(1).packagedSection.labelEn));
		PerspectiveCompletionTest.check("Gamma third", "Gamma".equals(additions.get(2).packagedSection.labelEn));
	}

	/**
	 * Reproduces the live incident (2026-09-21): {@code ClassCastException: ObjectNode cannot be cast to String}
	 * at the write site, because {@code addition.packagedSection.payload} (always the PACKAGED content) was read
	 * as if it were the EXISTING section's row id. An existing perspective that already has every packaged
	 * section, one of which (here: "Settings") is missing exactly one packaged item, must produce an addition
	 * whose {@code existingSectionId} is the real id PerspectiveInstaller.existingTree gave that section
	 * ("row-42" here, deliberately not equal to any id used elsewhere in this test file, so a test that
	 * accidentally read the wrong field would very likely fail rather than pass by coincidence), and whose
	 * {@code missingItems} carry the packaged item's own payload, untouched.
	 */
	private static void liveIncidentReproduction_existingSectionMissingOneItem_carriesTheRealExistingSectionId()
	{
		final List<PerspectiveCompletion.Section<String>> existing = new ArrayList<PerspectiveCompletion.Section<String>>();
		existing.add(PerspectiveCompletionTest.existingSection("row-1", "Projects", PerspectiveCompletionTest.existingItem("Projects", "table:Project"),
			PerspectiveCompletionTest.existingItem("Project wizard", "svc:ProjectWizard")));
		existing.add(PerspectiveCompletionTest.existingSection("row-42", "Settings", PerspectiveCompletionTest.existingItem("Connection", "svc:FactoryConnection")));

		final List<PerspectiveCompletion.Section<String>> packaged = new ArrayList<PerspectiveCompletion.Section<String>>();
		packaged.add(PerspectiveCompletionTest.packagedSection("Projects", PerspectiveCompletionTest.packagedItem("Projects", "table:Project"),
			PerspectiveCompletionTest.packagedItem("Project wizard", "svc:ProjectWizard")));
		packaged.add(PerspectiveCompletionTest.packagedSection("Settings", PerspectiveCompletionTest.packagedItem("Connection", "svc:FactoryConnection"),
			PerspectiveCompletionTest.packagedItem("Settings", "table:FactorySettings")));

		final List<PerspectiveCompletion.Addition<String>> additions = PerspectiveCompletion.decide(existing, packaged);
		PerspectiveCompletionTest.check("only Settings is missing something: Projects already has both its packaged items", additions.size() == 1);
		final PerspectiveCompletion.Addition<String> addition = additions.get(0);
		PerspectiveCompletionTest.check("not a new section", !addition.newSection);
		PerspectiveCompletionTest.check("the existing section id is exactly the one existingTree gave \"Settings\", not confused with any other section's",
			"row-42".equals(addition.existingSectionId));
		PerspectiveCompletionTest.check("exactly the one missing item", addition.missingItems.size() == 1);
		PerspectiveCompletionTest.check("it is the packaged \"Settings\" table link, its payload untouched",
			"Settings".equals(addition.missingItems.get(0).labelEn) && "packaged:Settings".equals(addition.missingItems.get(0).payload));
	}

	// ------------------------------------------------------------------ fixtures

	/** An existing top-level section: payload is the row id (a plain {@code String}), exactly what {@link PerspectiveCompletion#decide} expects on the existing side. */
	@SafeVarargs
	private static PerspectiveCompletion.Section<String> existingSection(final String pRowId, final String pLabelEn, final PerspectiveCompletion.Item<String>... pItems)
	{
		final List<PerspectiveCompletion.Item<String>> items = new ArrayList<PerspectiveCompletion.Item<String>>();
		for (final PerspectiveCompletion.Item<String> item : pItems)
		{
			items.add(item);
		}
		return new PerspectiveCompletion.Section<String>(pLabelEn, items, pRowId);
	}

	private static PerspectiveCompletion.Item<String> existingItem(final String pLabelEn, final String pTarget)
	{
		return new PerspectiveCompletion.Item<String>(pLabelEn, pTarget, null);
	}

	/** A packaged section: payload is a distinguishable marker string standing in for what would really be a {@code JsonNode} - verified to survive untouched into {@link PerspectiveCompletion.Addition#missingItems}. */
	@SafeVarargs
	private static PerspectiveCompletion.Section<String> packagedSection(final String pLabelEn, final PerspectiveCompletion.Item<String>... pItems)
	{
		final List<PerspectiveCompletion.Item<String>> items = new ArrayList<PerspectiveCompletion.Item<String>>();
		for (final PerspectiveCompletion.Item<String> item : pItems)
		{
			items.add(item);
		}
		return new PerspectiveCompletion.Section<String>(pLabelEn, items, "packaged:" + pLabelEn);
	}

	private static PerspectiveCompletion.Item<String> packagedItem(final String pLabelEn, final String pTarget)
	{
		return new PerspectiveCompletion.Item<String>(pLabelEn, pTarget, "packaged:" + pLabelEn);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
