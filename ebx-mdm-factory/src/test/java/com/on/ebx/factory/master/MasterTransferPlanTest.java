package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** JUnit-free: run the main, a failure throws. */
public final class MasterTransferPlanTest
{
	public static void main(final String[] pArgs)
	{
		MasterTransferPlanTest.parentsLoadBeforeChildren();
		MasterTransferPlanTest.foreignKeyFieldPlanCarriesRedirectedTarget();
		MasterTransferPlanTest.unresolvableTargetIsNoted();
		MasterTransferPlanTest.selfReferenceAmongPromotedEntitiesIsDeferredWithNote();
		MasterTransferPlanTest.regularForeignKeyIsNeverDeferred();
		MasterTransferPlanTest.derivedFoldKeyFieldPlannedLikeAnyOtherField();
		MasterTransferPlanTest.renamedReservedAttributeReadFromStagingIdWrittenToMasterIdSrc();
		System.out.println("MasterTransferPlanTest OK");
	}

	/**
	 * The exact live case the coordinator's review flagged: a project WITHOUT a {@code Company} canonical
	 * promotes {@code Account} directly, and the real staging data already has {@code Account.parentAccountId}
	 * as a self reference (`PLAN.md`, lot 3). The self-referencing FK must be detected and deferred, never
	 * silently left as an ordinary (immediate) one.
	 */
	private static void selfReferenceAmongPromotedEntitiesIsDeferredWithNote()
	{
		final MasterModel.EntityInfo account = new MasterModel.EntityInfo("Account", "Account", "ACCEPTED", Arrays.asList(
			MasterTransferPlanTest.technical("sourceSystem"), MasterTransferPlanTest.technical("sourceId"),
			new MasterModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(255), false, null, "ACCEPTED"),
			new MasterModel.AttributeInfo("parentAccountId", "Parent account id", "STRING", Integer.valueOf(40), false, "Account", "ACCEPTED")),
			Collections.<String>emptyList());
		final MasterModel.Result masterModel = MasterModel.build(Collections.singletonList(account));
		MasterTransferPlanTest.check("Account promoted on its own (no canonical)", masterModel.builtEntities.equals(Collections.singletonList("Account")));
		MasterTransferPlanTest.check("the self-referencing FK is still created at the model level", masterModel.foreignKeys.size() == 1
			&& "Account".equals(masterModel.foreignKeys.get(0).entityName) && "Account".equals(masterModel.foreignKeys.get(0).targetEntityName));

		final MasterTransferPlan.Result plan = MasterTransferPlan.plan(masterModel);
		MasterTransferPlanTest.check("Account still loads (no crash, no missing entity)",
			plan.loadOrder.size() == 1 && "Account".equals(plan.loadOrder.get(0).name));
		MasterTransferPlan.FieldPlan fk = null;
		for (final MasterTransferPlan.FieldPlan field : plan.loadOrder.get(0).fields)
		{
			if (field.isForeignKey)
			{
				fk = field;
			}
		}
		MasterTransferPlanTest.check("FK field plan found", fk != null);
		MasterTransferPlanTest.check("marked deferred - never resolved in the entity's own first pass", fk.deferred);
		MasterTransferPlanTest.check("target is Account itself", "Account".equals(fk.targetEntityName));
		MasterTransferPlanTest.check("a note explains it will be resolved in a second pass",
			MasterTransferPlanTest.anyContains(plan.notes, "Account.parentAccountIdRef: a self reference")
				&& MasterTransferPlanTest.anyContains(plan.notes, "resolved in a second pass"));
	}

	/**
	 * 2026-09-23 (`docs/MASTER-ZONE.md` &sect;13): a business attribute named {@code Id} (the Salesforce record id)
	 * is promoted as the master field {@code Id_src}; the plan reads the STAGING field {@code Id} and writes the
	 * MASTER field {@code Id_src} - nothing in {@code MasterTransferPlan} needed to know, it follows
	 * {@code MasterModel.FieldInfo}'s two names.
	 */
	private static void renamedReservedAttributeReadFromStagingIdWrittenToMasterIdSrc()
	{
		final MasterModel.EntityInfo sample = new MasterModel.EntityInfo("SalesforceSample", "Salesforce sample", "ACCEPTED", Arrays.asList(
			MasterTransferPlanTest.technical("sourceSystem"), MasterTransferPlanTest.technical("sourceId"),
			new MasterModel.AttributeInfo("Id", "Id", "STRING", Integer.valueOf(18), false, null, "ACCEPTED"),
			new MasterModel.AttributeInfo("Name", "Name", "STRING", Integer.valueOf(255), false, null, "ACCEPTED")), Collections.<String>emptyList());
		final MasterTransferPlan.Result plan = MasterTransferPlan.plan(MasterModel.build(Collections.singletonList(sample)));
		MasterTransferPlan.FieldPlan renamed = null;
		for (final MasterTransferPlan.FieldPlan field : plan.loadOrder.get(0).fields)
		{
			MasterTransferPlanTest.check("no plan ever writes a master field named Id", !"Id".equals(field.masterField));
			if ("Id".equals(field.stagingField))
			{
				renamed = field;
			}
		}
		MasterTransferPlanTest.check("a field plan reads the staging field Id", renamed != null);
		MasterTransferPlanTest.check("... and writes the master field Id_src", "Id_src".equals(renamed.masterField));
		MasterTransferPlanTest.check("an ordinary plain field, not a foreign key, not deferred", !renamed.isForeignKey && !renamed.deferred);
	}

	/** The reference demo's own Contact -&gt; Company edge is a plain parent/child relationship: never deferred. */
	private static void regularForeignKeyIsNeverDeferred()
	{
		final MasterModel.Result masterModel = MasterTransferPlanTest.realDemoResult();
		final MasterTransferPlan.Result plan = MasterTransferPlan.plan(masterModel);
		for (final MasterTransferPlan.Entity entity : plan.loadOrder)
		{
			for (final MasterTransferPlan.FieldPlan field : entity.fields)
			{
				if (field.isForeignKey)
				{
					MasterTransferPlanTest.check(entity.name + "." + field.masterField + " is not deferred", !field.deferred);
				}
			}
		}
	}

	/** Company (no FK) must load before Contact (FK to Company) - the real demo shape. */
	private static void parentsLoadBeforeChildren()
	{
		final MasterModel.Result masterModel = MasterTransferPlanTest.realDemoResult();
		final MasterTransferPlan.Result plan = MasterTransferPlan.plan(masterModel);
		final List<String> order = new ArrayList<String>();
		for (final MasterTransferPlan.Entity entity : plan.loadOrder)
		{
			order.add(entity.name);
		}
		MasterTransferPlanTest.check("both entities present", order.containsAll(Arrays.asList("Company", "Contact")));
		MasterTransferPlanTest.check("Company loads before Contact", order.indexOf("Company") < order.indexOf("Contact"));
		MasterTransferPlanTest.check("no cycle", plan.cycleEntities.isEmpty());
	}

	private static void foreignKeyFieldPlanCarriesRedirectedTarget()
	{
		final MasterModel.Result masterModel = MasterTransferPlanTest.realDemoResult();
		final MasterTransferPlan.Result plan = MasterTransferPlan.plan(masterModel);
		MasterTransferPlan.Entity contact = null;
		for (final MasterTransferPlan.Entity entity : plan.loadOrder)
		{
			if ("Contact".equals(entity.name))
			{
				contact = entity;
			}
		}
		MasterTransferPlanTest.check("Contact found", contact != null);
		MasterTransferPlan.FieldPlan fk = null;
		for (final MasterTransferPlan.FieldPlan field : contact.fields)
		{
			if (field.isForeignKey)
			{
				fk = field;
			}
		}
		MasterTransferPlanTest.check("one FK field plan found", fk != null);
		MasterTransferPlanTest.check("targets Company (redirected), not Account", "Company".equals(fk.targetEntityName));
		MasterTransferPlanTest.check("master field is accountIdRef", "accountIdRef".equals(fk.masterField));
		MasterTransferPlanTest.check("staging field carried is the attribute name accountId (resolved to the real Ref field name by the loader)",
			"accountId".equals(fk.stagingField));
	}

	private static void unresolvableTargetIsNoted()
	{
		final MasterModel.EntityInfo widget = new MasterModel.EntityInfo("Widget", "Widget", "ACCEPTED",
			Arrays.asList(MasterTransferPlanTest.technical("sourceSystem"), MasterTransferPlanTest.technical("sourceId")), Collections.<String>emptyList());
		final MasterModel.Result masterModel = MasterModel.build(Collections.singletonList(widget));
		// a hand-built ForeignKeyInfo whose target was never actually promoted (defensive: MasterModel itself
		// never produces this, but MasterTransferPlan must not assume every ForeignKeyInfo it is handed is safe)
		final List<MasterModel.ForeignKeyInfo> foreignKeysWithDangling = new ArrayList<MasterModel.ForeignKeyInfo>(masterModel.foreignKeys);
		final MasterModel.Result withDanglingFk = new MasterModel.Result(masterModel.tree, masterModel.operations, masterModel.autoIncrementOps,
			masterModel.defaultLabelByTable, masterModel.skippedEntities, masterModel.fields,
			MasterTransferPlanTest.append(foreignKeysWithDangling, new MasterModel.ForeignKeyInfo("Widget", "gadgetId", "gadgetIdRef", "Gadget")),
			masterModel.notes, masterModel.builtEntities, masterModel.keptInStagingOnly, Collections.<String, String>emptyMap());
		final MasterTransferPlan.Result plan = MasterTransferPlan.plan(withDanglingFk);
		MasterTransferPlanTest.check("a note is added for the unresolvable target",
			MasterTransferPlanTest.anyContains(plan.notes, "Widget.gadgetId: target entity Gadget has no field mapping"));
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	private static MasterModel.Result realDemoResult()
	{
		// Supplier/Account each need at least one accepted BUSINESS attribute, or StagingModel.build itself
		// never stages them at all (see StagingModel's own Javadoc) - the member->canonical redirection map is
		// built only from entities that reached staging in the first place, exactly like the real demo data
		final MasterModel.EntityInfo supplier = new MasterModel.EntityInfo("Supplier", "Supplier", "ACCEPTED", Arrays.asList(
			MasterTransferPlanTest.technical("sourceSystem"), MasterTransferPlanTest.technical("sourceId"),
			new MasterModel.AttributeInfo("legalName", "Legal name", "STRING", Integer.valueOf(255), false, null, "ACCEPTED")), Collections.<String>emptyList());
		final MasterModel.EntityInfo account = new MasterModel.EntityInfo("Account", "Account", "ACCEPTED", Arrays.asList(
			MasterTransferPlanTest.technical("sourceSystem"), MasterTransferPlanTest.technical("sourceId"),
			new MasterModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(255), false, null, "ACCEPTED")), Collections.<String>emptyList());
		final MasterModel.EntityInfo company = new MasterModel.EntityInfo("Company", "Company", "ACCEPTED", Arrays.asList(
			MasterTransferPlanTest.technical("sourceSystem"), MasterTransferPlanTest.technical("sourceId"),
			new MasterModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(255), false, null, "ACCEPTED")), Arrays.asList("Supplier", "Account"));
		final MasterModel.EntityInfo contact = new MasterModel.EntityInfo("Contact", "Contact", "ACCEPTED", Arrays.asList(
			MasterTransferPlanTest.technical("sourceSystem"), MasterTransferPlanTest.technical("sourceId"),
			new MasterModel.AttributeInfo("email", "Email", "STRING", Integer.valueOf(255), false, null, "ACCEPTED"),
			new MasterModel.AttributeInfo("accountId", "Account id", "STRING", Integer.valueOf(40), false, "Account", "ACCEPTED")), Collections.<String>emptyList());
		return MasterModel.build(Arrays.asList(supplier, account, company, contact));
	}

	/**
	 * A {@code FOLD_KEY} derived attribute (2026-09-22) flows into {@code ebx.MasterLoader}'s own transfer
	 * plan exactly like any other business attribute - {@code MasterTransferPlan} reads {@code
	 * MasterModel.Result#fields} without any notion of "derived" (see {@code MasterModel}'s own Javadoc), so
	 * {@code ebx.MasterLoader} simply copies the staging value across, same as for any plain field. A second
	 * domain (a site name, never only company/city vocabulary).
	 */
	private static void derivedFoldKeyFieldPlannedLikeAnyOtherField()
	{
		final MasterModel.EntityInfo site = new MasterModel.EntityInfo("Site", "Site", "ACCEPTED",
			Arrays.asList(MasterTransferPlanTest.technical("sourceSystem"), MasterTransferPlanTest.technical("sourceId"),
				new MasterModel.AttributeInfo("siteName", "Site name", "STRING", Integer.valueOf(255), false, null, "ACCEPTED"),
				new MasterModel.AttributeInfo("siteNameKey", "Matching key of siteName", "STRING", Integer.valueOf(255), false, null, "ACCEPTED")),
			Collections.<String>emptyList());
		final MasterModel.Result masterModel = MasterModel.build(Collections.singletonList(site));
		final MasterTransferPlan.Result plan = MasterTransferPlan.plan(masterModel);
		MasterTransferPlanTest.check("Site loads", plan.loadOrder.size() == 1 && "Site".equals(plan.loadOrder.get(0).name));
		MasterTransferPlan.FieldPlan derived = null;
		for (final MasterTransferPlan.FieldPlan field : plan.loadOrder.get(0).fields)
		{
			if ("siteNameKey".equals(field.masterField))
			{
				derived = field;
			}
		}
		MasterTransferPlanTest.check("siteNameKey is planned", derived != null);
		MasterTransferPlanTest.check("not a foreign key", !derived.isForeignKey);
		MasterTransferPlanTest.check("reads from the staging field of the same name (never renamed)", "siteNameKey".equals(derived.stagingField));
	}

	private static MasterModel.AttributeInfo technical(final String pName)
	{
		return new MasterModel.AttributeInfo(pName, pName, "STRING", Integer.valueOf(40), true, null, "ACCEPTED");
	}

	private static List<MasterModel.ForeignKeyInfo> append(final List<MasterModel.ForeignKeyInfo> pList, final MasterModel.ForeignKeyInfo pExtra)
	{
		pList.add(pExtra);
		return pList;
	}

	private static boolean anyContains(final List<String> pStrings, final String pFragment)
	{
		for (final String string : pStrings)
		{
			if (string.contains(pFragment))
			{
				return true;
			}
		}
		return false;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
