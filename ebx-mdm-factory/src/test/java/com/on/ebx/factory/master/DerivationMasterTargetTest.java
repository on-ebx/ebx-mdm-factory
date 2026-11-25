package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * JUnit-free: run the main, a failure throws. Lot T2 (2026-10-04): when a script derivation applied on staging also gets its two triggers on the master
 * table. Domain-neutral fixture (an entity {@code Thing}, a source {@code raw}, a derived {@code rawKey}).
 */
public final class DerivationMasterTargetTest
{
	public static void main(final String[] pArgs)
	{
		DerivationMasterTargetTest.eligibleWhenTheMasterTableCarriesEveryAttributeUnderItsOwnName();
		DerivationMasterTargetTest.namesAreTheStagingOnesAndTheModelIsTheMasterModel();
		DerivationMasterTargetTest.noMasterModelOrNoTableIsNormalAndSilent();
		DerivationMasterTargetTest.anAttributeNotOnTheMasterTableIsALimitationWorthTelling();
		DerivationMasterTargetTest.aRenamedOrReservedAttributeIsNotEligible();
		DerivationMasterTargetTest.changedParametersWaitForTheNextApply();
		System.out.println("DerivationMasterTargetTest OK");
	}

	private static Set<String> fields(final String... pNames)
	{
		return new LinkedHashSet<String>(Arrays.asList(pNames));
	}

	private static void eligibleWhenTheMasterTableCarriesEveryAttributeUnderItsOwnName()
	{
		final DerivationMasterTarget.Decision d = DerivationMasterTarget.decide("Proj", "Thing", "rawKey", Arrays.asList("raw", "other"), true,
			DerivationMasterTargetTest.fields("id", "sourceSystem", "sourceId", "raw", "other", "rawKey", "_loadId"), false);
		DerivationMasterTargetTest.check("eligible: " + d.reason, d.eligible() && d.kind == DerivationMasterTarget.Kind.ELIGIBLE && d.reason.isEmpty() && !d.noteworthy());
	}

	private static void namesAreTheStagingOnesAndTheModelIsTheMasterModel()
	{
		final DerivationMasterTarget.Decision d = DerivationMasterTarget.decide("Proj", "Thing", "rawKey", Collections.singletonList("raw"), true, DerivationMasterTargetTest.fields("raw", "rawKey"),
			false);
		DerivationMasterTargetTest.check("model " + d.modelName, "Proj_Master".equals(d.modelName));
		DerivationMasterTargetTest.check("table " + d.tablePath, "/root/Thing".equals(d.tablePath));
		DerivationMasterTargetTest.check("the same trigger names as staging", "DeriveRawKeyOnCreate".equals(d.createName) && "DeriveRawKeyOnModify".equals(d.modifyName));
	}

	private static void noMasterModelOrNoTableIsNormalAndSilent()
	{
		final DerivationMasterTarget.Decision noModel = DerivationMasterTarget.decide("Proj", "Thing", "rawKey", Collections.singletonList("raw"), false, null, false);
		DerivationMasterTargetTest.check("no model", noModel.kind == DerivationMasterTarget.Kind.NO_MASTER_MODEL && !noModel.eligible() && !noModel.noteworthy() && noModel.reason.contains("Proj_Master"));
		final DerivationMasterTarget.Decision noTable = DerivationMasterTarget.decide("Proj", "Thing", "rawKey", Collections.singletonList("raw"), true, null, false);
		DerivationMasterTargetTest.check("no table (a member entity, or accepted after the last generation)", noTable.kind == DerivationMasterTarget.Kind.NO_MASTER_TABLE && !noTable.noteworthy()
			&& noTable.reason.contains("/root/Thing"));
	}

	private static void anAttributeNotOnTheMasterTableIsALimitationWorthTelling()
	{
		final DerivationMasterTarget.Decision derivedMissing = DerivationMasterTarget.decide("Proj", "Thing", "rawKey", Collections.singletonList("raw"), true,
			DerivationMasterTargetTest.fields("raw"), false);
		DerivationMasterTargetTest.check("the derived attribute is missing: " + derivedMissing.reason, derivedMissing.kind == DerivationMasterTarget.Kind.ATTRIBUTE_NOT_ON_MASTER
			&& derivedMissing.noteworthy() && derivedMissing.reason.contains("'rawKey'"));
		final DerivationMasterTarget.Decision boundMissing = DerivationMasterTarget.decide("Proj", "Thing", "rawKey", Arrays.asList("raw", "other"), true,
			DerivationMasterTargetTest.fields("raw", "rawKey"), false);
		DerivationMasterTargetTest.check("a bound attribute is missing: " + boundMissing.reason, boundMissing.noteworthy() && boundMissing.reason.contains("'other'") && !boundMissing.eligible());
		final DerivationMasterTarget.Decision exactCase = DerivationMasterTarget.decide("Proj", "Thing", "rawKey", Collections.singletonList("raw"), true,
			DerivationMasterTargetTest.fields("RAW", "rawKey"), false);
		DerivationMasterTargetTest.check("the match is on the exact name", !exactCase.eligible());
	}

	private static void aRenamedOrReservedAttributeIsNotEligible()
	{
		// MasterFieldNameRule: a business attribute named like one of the table's own fields is promoted as <name>_src, so the trigger could not read it by its name
		final DerivationMasterTarget.Decision renamedSource = DerivationMasterTarget.decide("Proj", "Thing", "rawKey", Collections.singletonList("Id"), true,
			DerivationMasterTargetTest.fields("id", "Id_src", "rawKey"), false);
		DerivationMasterTargetTest.check("a source that becomes Id_src: " + renamedSource.reason, renamedSource.kind == DerivationMasterTarget.Kind.ATTRIBUTE_NOT_ON_MASTER
			&& renamedSource.reason.contains("'Id'"));
		final DerivationMasterTarget.Decision lowerCaseId = DerivationMasterTarget.decide("Proj", "Thing", "rawKey", Collections.singletonList("id"), true,
			DerivationMasterTargetTest.fields("id", "id_src", "rawKey"), false);
		DerivationMasterTargetTest.check("the master's own id field is never mistaken for a promoted attribute", !lowerCaseId.eligible());
		final DerivationMasterTarget.Decision renamedDerived = DerivationMasterTarget.decide("Proj", "Thing", "sourceId", Collections.singletonList("raw"), true,
			DerivationMasterTargetTest.fields("raw", "sourceId"), false);
		DerivationMasterTargetTest.check("a derived attribute with a reserved name", !renamedDerived.eligible());
	}

	private static void changedParametersWaitForTheNextApply()
	{
		final DerivationMasterTarget.Decision d = DerivationMasterTarget.decide("Proj", "Thing", "rawKey", Collections.singletonList("raw"), true, DerivationMasterTargetTest.fields("raw", "rawKey"),
			true);
		DerivationMasterTargetTest.check("skipped with the reason: " + d.reason, d.kind == DerivationMasterTarget.Kind.PARAMETERS_CHANGED && !d.eligible() && d.reason.contains("apply it again"));
		final DerivationMasterTarget.Decision noModelFirst = DerivationMasterTarget.decide("Proj", "Thing", "rawKey", Collections.singletonList("raw"), false, null, true);
		DerivationMasterTargetTest.check("the missing master is reported before the parameters", noModelFirst.kind == DerivationMasterTarget.Kind.NO_MASTER_MODEL);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
