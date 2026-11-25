package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.List;

/** JUnit-free: order and options of the removal of a generated domain. */
public final class DomainCleanupPlanTest
{
	public static void main(final String[] pArgs)
	{
		final List<DomainCleanupPlan.Row> rows = new ArrayList<DomainCleanupPlan.Row>();
		rows.add(new DomainCleanupPlan.Row("DATASPACE", "F_Domain", "F_Domain"));
		rows.add(new DomainCleanupPlan.Row("DATA_MODEL", "F_Domain", "F_Domain"));
		rows.add(new DomainCleanupPlan.Row("PUBLICATION", "F_Domain", "F_Domain"));
		rows.add(new DomainCleanupPlan.Row("DATASET", "F_Domain/Domain", "Domain"));
		rows.add(new DomainCleanupPlan.Row("SCRIPT", "data-model/F_Domain/root/Company/@trigger/AuditOnCreate", "/root/Company"));
		rows.add(new DomainCleanupPlan.Row("LAYOUT", "/root/Company", "/root/Company"));
		rows.add(new DomainCleanupPlan.Row("WORKFLOW_MODEL", "F_CompanyCreationRequest", "/root/Company"));
		rows.add(new DomainCleanupPlan.Row("WORKFLOW_PUBLICATION", "F_CompanyCreationRequest", "/root/Company"));
		rows.add(new DomainCleanupPlan.Row("MATCHING_POLICY", "/root/Company#Company_entry", "/root/Company"));
		final List<DomainCleanupPlan.Item> items = DomainCleanupPlan.build(rows);
		final StringBuilder order = new StringBuilder();
		for (final DomainCleanupPlan.Item i : items)
		{
			order.append(i.kind.name()).append(' ');
		}
		check("order " + order, "MATCHING_CONFIG WORKFLOW_PUBLICATION WORKFLOW_MODEL DOMAIN_DATASPACE FORM_LAYOUTS DATA_MODEL ".equals(order.toString()));
		check("matching addressed by table through the model", "/root/Company".equals(items.get(0).tablePath) && "F_Domain".equals(items.get(0).modelName));
		check("options", ProjectDeletionPlan.OPTION_MATCHING.equals(items.get(0).option) && ProjectDeletionPlan.OPTION_FLOWS.equals(items.get(1).option)
				&& ProjectDeletionPlan.OPTION_FLOWS.equals(items.get(2).option) && ProjectDeletionPlan.OPTION_DATASPACES.equals(items.get(3).option)
				&& ProjectDeletionPlan.OPTION_MODELS.equals(items.get(4).option) && ProjectDeletionPlan.OPTION_MODELS.equals(items.get(5).option));
		check("nothing to remove, nothing planned", DomainCleanupPlan.build(new ArrayList<DomainCleanupPlan.Row>()).isEmpty());
		check("the reset plan exposes the same items and the ten steps plus the run", StepResetPlan.domainItems(rows).size() == 6 && StepResetPlan.domainStepNames().size() == 11
				&& StepResetPlan.domainStepNames().get(0).equals("ACCEL_DATASPACE"));
		System.out.println("DomainCleanupPlanTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
