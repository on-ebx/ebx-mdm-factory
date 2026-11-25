package com.on.ebx.factory.ebx;

import java.text.MessageFormat;
import java.util.Locale;

import com.on.ebx.factory.ebx.ui.Messages;

/**
 * JUnit-free: run the main, a failure throws. X3-11 (1.4.2): an empty "Tool invocation failed" while creating the dataset of a zone is translated into a coded message that says what
 * happened and what to do (EN and FR), and the three generators route both the flagged result and the thrown MCP failure through it.
 */
public final class ZoneDatasetDenialTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		for (final String empty : new String[] { null, "", "   ", "tools/call: Tool invocation failed: ", "tools/call: Tool invocation failed:", "Tool invocation failed", "TOOLS/CALL : tool invocation failed :  " })
		{
			ZoneDatasetDenialTest.check("empty failure recognised: [" + empty + "]", ZoneDatasetDenial.emptyToolFailure(empty));
		}
		for (final String real : new String[] { "tools/call: Tool invocation failed: Data model not found: 'X'", "Access denied", "Tool invocation failed: boom", "timeout" })
		{
			ZoneDatasetDenialTest.check("a failure with a reason is NOT translated: [" + real + "]", !ZoneDatasetDenial.emptyToolFailure(real));
		}
		ZoneDatasetDenialTest.check("codes", "external.zoneDatasetDeniedRemoved".equals(ZoneDatasetDenial.codeFor(true, true)) && "external.zoneDatasetDeniedKept".equals(ZoneDatasetDenial.codeFor(true, false))
			&& "external.zoneDatasetDeniedExisting".equals(ZoneDatasetDenial.codeFor(false, false)));
		for (final String code : new String[] { "external.zoneDatasetDeniedRemoved", "external.zoneDatasetDeniedKept", "external.zoneDatasetDeniedExisting" })
		{
			final String key = "engine." + code;
			final String en = ZoneDatasetDenialTest.fill(Locale.ENGLISH, key);
			final String fr = ZoneDatasetDenialTest.fill(Locale.FRENCH, key);
			ZoneDatasetDenialTest.check(code + ": both languages exist and differ", !en.equals(key) && !fr.equals(key) && !en.equals(fr));
			for (final String text : new String[] { en, fr })
			{
				ZoneDatasetDenialTest.check(code + ": names the zone, the property and the code: " + text, text.contains("MDMF-ZONE-1") && text.contains("ZONE_X") && text.contains("-D" + FactoryAccess.PROPERTY_ZONE_PERMISSIONS));
			}
			// 1.4.3: the message is right in the three settings (parent by default, a named dataspace, none) and names the current one
			for (final String text : new String[] { en, fr, ZoneDatasetDenial.english(code, "ZONE_X", "locked", "PARENT=Reference") })
			{
				ZoneDatasetDenialTest.check(code + ": says the three cases (parent, named, none) and the current setting: " + text,
					text.contains("PARENT=Reference") && text.contains("=none") && text.contains("=<") && text.toLowerCase(Locale.ROOT).contains("parent"));
				ZoneDatasetDenialTest.check(code + ": no longer tells to set the property as the only way out", !text.contains("set -D" + FactoryAccess.PROPERTY_ZONE_PERMISSIONS + "=<a dataspace whose permissions suit"));
			}
			final String english = ZoneDatasetDenial.english(code, "ZONE_X", "locked", "PARENT=Reference");
			ZoneDatasetDenialTest.check(code + ": the engine's English sentence says what to do", english.contains("MDMF-ZONE-1") && english.contains("ZONE_X") && english.contains(FactoryAccess.PROPERTY_ZONE_PERMISSIONS));
		}
		ZoneDatasetDenialTest.check("the 'kept' message gives the reason the removal failed", ZoneDatasetDenialTest.fill(Locale.ENGLISH, "engine.external.zoneDatasetDeniedKept").contains("locked"));
		ZoneDatasetDenialTest.check("the 'removed' message says the empty zone is gone and the setting is for new zones", ZoneDatasetDenialTest.fill(Locale.ENGLISH, "engine.external.zoneDatasetDeniedRemoved").contains("has been removed")
			&& ZoneDatasetDenialTest.fill(Locale.ENGLISH, "engine.external.zoneDatasetDeniedRemoved").contains("NEW zones"));
		for (final String generator : new String[] { "LandingGenerator", "StagingGenerator", "MasterGenerator" })
		{
			final String source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("mdmf.sourceDir"), "com/on/ebx/factory/ebx/" + generator + ".java")),
				java.nio.charset.StandardCharsets.UTF_8);
			ZoneDatasetDenialTest.check(generator + ": flagged result and thrown MCP failure both go through ZoneDatasetDenial, and the zone created in the step is tracked",
				source.contains("ZoneDatasetDenial.emptyToolFailure(create.text)") && source.contains("ZoneDatasetDenial.emptyToolFailure(ex.getMessage())") && source.contains("zoneCreatedHere = true;")
					&& source.contains("ZoneDatasetDenial.translate("));
		}
		System.out.println("ZoneDatasetDenialTest OK");
	}

	private static String fill(final Locale pLocale, final String pKey)
	{
		final String pattern = Messages.get(com.on.ebx.factory.ebx.ui.AboutServiceDeclaration.class, pLocale, pKey);
		return MessageFormat.format(pattern, "ZONE_X", FactoryAccess.PROPERTY_ZONE_PERMISSIONS, "locked", "PARENT=Reference");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new IllegalStateException("FAILED: " + pWhat);
		}
	}
}
