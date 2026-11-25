package com.on.ebx.factory.ebx;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.accel.PackCatalog;

/** JUnit-free: the pure half of LayoutOps - the row fields and the ${publication} substitution. */
public final class LayoutOpsTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final JsonNode packLayout = PackCatalog.load().pack("company").layouts().get("/root/Company");
		LayoutOpsTest.check("pack layout carries the placeholder", packLayout.toString().contains("${publication}"));
		final JsonNode rendered = LayoutOps.render(packLayout, "Fournisseurs_Domain");
		LayoutOpsTest.check("model substituted", "urn:ebx:publication:Fournisseurs_Domain".equals(rendered.path("model").asText()));
		LayoutOpsTest.check("no placeholder left", !rendered.toString().contains("${publication}"));
		LayoutOpsTest.check("the source is not modified", packLayout.toString().contains("${publication}"));
		final Map<String, Object> row = LayoutOps.rowFields(packLayout, "/root/Company", "Fournisseurs_Domain", 3);
		LayoutOpsTest.check("row fields", "urn:ebx:publication:Fournisseurs_Domain".equals(row.get("model")) && "/root/Company".equals(row.get("table")) && Boolean.TRUE.equals(row.get("active"))
				&& Integer.valueOf(3).equals(row.get("version")) && "Company (pack)".equals(row.get("label")));
		LayoutOpsTest.check("row field names are the table's", row.keySet().toString().equals("[model, table, active, layout, version, label]"));
		final JsonNode parsed = new ObjectMapper().readTree((String) row.get("layout"));
		LayoutOpsTest.check("layout text is the rendered JSON", "urn:ebx:publication:Fournisseurs_Domain".equals(parsed.path("model").asText()) && "/root/Company".equals(parsed.path("table").asText()));
		LayoutOpsTest.check("versions", LayoutOps.nextVersion(null) == 1 && LayoutOps.nextVersion(Integer.valueOf(4)) == 5);
		LayoutOpsTest.check("urn", "urn:ebx:publication:X".equals(LayoutOps.publicationUrn("X")));
		System.out.println("LayoutOpsTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
