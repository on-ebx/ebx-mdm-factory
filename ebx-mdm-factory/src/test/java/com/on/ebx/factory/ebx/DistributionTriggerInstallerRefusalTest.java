package com.on.ebx.factory.ebx;

/** JUnit-free: run the main, a failure throws. Which update_script answers trigger the validate-only IDE route (lot S3). */
public final class DistributionTriggerInstallerRefusalTest
{
	public static void main(final String[] pArgs)
	{
		check("code in the json", DistributionTriggerInstaller.isInvalidDataModel("{\"success\":false,\"code\":\"INVALID_DATA_MODEL\",\"message\":\"Revision history ... could not be loaded\"}", null));
		check("code in the text", DistributionTriggerInstaller.isInvalidDataModel(null, "INVALID_DATA_MODEL: Revision history for the data-model script could not be loaded"));
		check("a plain compile error does not trigger it", !DistributionTriggerInstaller.isInvalidDataModel("{\"success\":true,\"data\":{\"validation\":{\"isSuccess\":false}}}", "x"));
		check("nothing", !DistributionTriggerInstaller.isInvalidDataModel(null, null));
		// lot F3: the idempotent "reinstall when the body differs" comparison
		final String generated = com.on.ebx.factory.distribution.DistributionTriggerBody.body(com.on.ebx.factory.distribution.DistributionTriggerBody.AFTER_MODIFY, "42", "/root/Company",
			"Company", "Companies_Master", java.util.Arrays.asList("id"));
		check("the generated body equals itself", DistributionTriggerInstaller.sameSource(generated, generated));
		check("a final newline added by the server is ignored", DistributionTriggerInstaller.sameSource(generated + "\n", generated));
		check("CRLF line endings are ignored", DistributionTriggerInstaller.sameSource(generated.replace("\n", "\r\n"), generated));
		check("a body from the pre-F3 generator (retire block) differs and is rewritten",
			!DistributionTriggerInstaller.sameSource(generated.replace("\tconst changeValue := 'MODIFY';\n\tconst survivorValue := '';\n",
				"\tconst oldLinkedState := '' | _ebx.previousRecord.Linked_Field_State;\n\tconst newLinkedState := '' | _ebx.record.Linked_Field_State;\n\tref changeValue: string := 'MODIFY';\n"), generated));
		check("the template of a freshly declared trigger differs", !DistributionTriggerInstaller.sameSource("// Trigger script\nexport procedure onAfterModify()\nbegin\nend\n", generated));
		check("an unreadable stored source is never 'the same'", !DistributionTriggerInstaller.sameSource(null, generated));
		System.out.println("DistributionTriggerInstallerRefusalTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
