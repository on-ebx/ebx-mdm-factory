package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.project.StepResetPlan;

/** JUnit-free: the pure bits of DomainGenerator (step vocabulary, permissions read-back search). No EBX. */
public final class DomainGeneratorTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		check("ten steps in the order of docs/LOT-C1.md section 7", DomainGenerator.STEPS.toString().equals("[ACCEL_DATASPACE, ACCEL_MODEL, ACCEL_REFERENCE_ROWS, ACCEL_SCRIPTS, ACCEL_FORMS, "
				+ "ACCEL_ROLES, ACCEL_WORKFLOW, ACCEL_MATCHING, ACCEL_PERMISSIONS, ACCEL_SUMMARY]"));
		check("the reset plan knows the same steps", StepResetPlan.domainStepNames().containsAll(DomainGenerator.STEPS) && StepResetPlan.domainStepNames().contains(DomainGenerator.STEP));
		final JsonNode permissions = new ObjectMapper().readTree("{\"success\":true,\"data\":{\"profiles\":[{\"profile\":\"RUser\",\"valueAccessDefault\":\"W\"},"
				+ "{\"profile\":\"RSteward\",\"valueAccessDefault\":\"H\",\"tableDefaultActions\":{}},{\"profile\":\"Beveryone\",\"valueAccessDefault\":\"R\"}]}}");
		check("read-back finds W", "W".equals(DomainGenerator.valueAccessOf(permissions, "RUser")));
		check("read-back finds the hidden-default trap", "H".equals(DomainGenerator.valueAccessOf(permissions, "RSteward")));
		check("read-back finds R", "R".equals(DomainGenerator.valueAccessOf(permissions, "Beveryone")));
		check("absent profile is null", DomainGenerator.valueAccessOf(permissions, "RNobody") == null && DomainGenerator.valueAccessOf(null, "x") == null);
		check("accel paths come from FactoryPaths", com.on.ebx.factory.core.FactoryPaths.DOMAIN_OBJECT.format().equals("/root/DomainObject") && com.on.ebx.factory.core.FactoryPaths.Project.DOMAIN_MODEL.format().equals("./domainModel"));
		System.out.println("DomainGeneratorTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
