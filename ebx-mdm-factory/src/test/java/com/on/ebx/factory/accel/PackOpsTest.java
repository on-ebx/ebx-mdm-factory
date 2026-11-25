package com.on.ebx.factory.accel;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free: run the main, a failure throws. */
public final class PackOpsTest
{
	public static void main(final String[] pArgs)
	{
		final PackCatalog catalog = PackCatalog.load();
		PackOpsTest.opsForBlockShape(catalog);
		PackOpsTest.opsForBlockDefaultGroup(catalog);
		PackOpsTest.coreOpsUnchangedWithoutDifferences(catalog);
		PackOpsTest.coreOpsWithRemove(catalog);
		PackOpsTest.coreOpsWithChange(catalog);
		PackOpsTest.coreOpsDoesNotMutateTheCore(catalog);
		System.out.println("PackOpsTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		PackCatalogTest.check(pWhat, pCondition);
	}

	private static void opsForBlockShape(final PackCatalog c)
	{
		final List<ObjectNode> ops = PackOps.opsForBlock(c.block("contacts"), "/root/Company", "Contact");
		// create_group, then phone/email/website each create_field + maxLength
		check("7 ops", ops.size() == 7);
		check("group first", "create_group".equals(ops.get(0).path("op").asText()) && "/root/Company".equals(ops.get(0).path("arguments").path("table_path").asText())
				&& "Contact".equals(ops.get(0).path("arguments").path("group_name").asText()));
		check("field under the group", "/root/Company/Contact/phone".equals(ops.get(1).path("arguments").path("field_path").asText())
				&& "string".equals(ops.get(1).path("arguments").path("field_type").asText()));
		check("max length as a string value", "maxLength".equals(ops.get(2).path("arguments").path("property").asText())
				&& "40".equals(ops.get(2).path("arguments").path("value").asText())
				&& "/root/Company/Contact/phone".equals(ops.get(2).path("arguments").path("element_path").asText()));
		check("email type kept", "email".equals(ops.get(3).path("arguments").path("field_type").asText()));
		for (final ObjectNode op : ops)
		{
			check("known op", PackOps.KNOWN_OPS.contains(op.path("op").asText()));
		}
		final List<ObjectNode> audit = PackOps.opsForBlock(c.block("audit"), "/root/Contact", "Trace");
		check("audit: group + 4 fields + 2 max lengths", audit.size() == 1 + 4 + 2 && "Trace".equals(audit.get(0).path("arguments").path("group_name").asText()));
	}

	private static void opsForBlockDefaultGroup(final PackCatalog c)
	{
		check("null group means the block's own", "Audit".equals(PackOps.opsForBlock(c.block("audit"), "/root/T", null).get(0).path("arguments").path("group_name").asText()));
		check("blank group means the block's own", "Audit".equals(PackOps.opsForBlock(c.block("audit"), "/root/T", "  ").get(0).path("arguments").path("group_name").asText()));
	}

	private static void coreOpsUnchangedWithoutDifferences(final PackCatalog c)
	{
		final PackCore core = c.pack("company").core();
		final List<ObjectNode> ops = PackOps.coreOps(core, Collections.<String> emptySet(), Collections.<String, ObjectNode> emptyMap());
		check("same count", ops.size() == core.ops().size());
		check("same content", ops.get(5).equals(core.ops().get(5)));
		check("null arguments tolerated", PackOps.coreOps(core, null, null).size() == core.ops().size());
	}

	private static void coreOpsWithRemove(final PackCatalog c)
	{
		final PackCore core = c.pack("company").core();
		final Set<String> removed = new HashSet<String>();
		removed.add("/root/Contact");
		removed.add("/root/Company/Lifecycle");
		final List<ObjectNode> ops = PackOps.coreOps(core, removed, Collections.<String, ObjectNode> emptyMap());
		check("fewer ops", ops.size() < core.ops().size());
		for (final ObjectNode op : ops)
		{
			final String subject = PackOps.subject(op);
			check("nothing left on /root/Contact: " + op, subject == null || !(subject.equals("/root/Contact") || subject.startsWith("/root/Contact/")));
			check("nothing left on Lifecycle: " + op, subject == null || !subject.startsWith("/root/Company/Lifecycle"));
			check("no association to the removed table: " + op, !"/root/Contact".equals(op.path("arguments").path("target_table").asText()));
		}
		boolean companyKept = false;
		boolean addressKept = false;
		for (final ObjectNode op : ops)
		{
			companyKept |= "create_table".equals(op.path("op").asText()) && "/root/Company".equals(op.path("arguments").path("table_path").asText());
			addressKept |= "create_table".equals(op.path("op").asText()) && "/root/CompanyAddress".equals(op.path("arguments").path("table_path").asText());
		}
		check("other tables kept", companyKept && addressKept);
		// a prefix is not a path: removing /root/Company must keep /root/CompanyAddress
		final List<ObjectNode> noCompany = PackOps.coreOps(core, Collections.singleton("/root/Company"), null);
		boolean addressStill = false;
		for (final ObjectNode op : noCompany)
		{
			addressStill |= "create_table".equals(op.path("op").asText()) && "/root/CompanyAddress".equals(op.path("arguments").path("table_path").asText());
		}
		check("sibling with the same prefix kept", addressStill);
	}

	private static void coreOpsWithChange(final PackCatalog c)
	{
		final PackCore core = c.pack("company").core();
		final Map<String, ObjectNode> changed = new LinkedHashMap<String, ObjectNode>();
		final ObjectNode props = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		props.put("maxLength", 200);
		props.put("mandatory", "true");
		changed.put("/root/Company/Identification/legalName", props);
		final ObjectNode other = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		other.put("maxLength", "10");
		changed.put("/root/Company/Identification/name", other);
		final List<ObjectNode> ops = PackOps.coreOps(core, Collections.<String> emptySet(), changed);
		int lengthOps = 0;
		int mandatoryOps = 0;
		String lengthValue = null;
		for (final ObjectNode op : ops)
		{
			final com.fasterxml.jackson.databind.JsonNode a = op.path("arguments");
			if ("set_element_property".equals(op.path("op").asText()) && "/root/Company/Identification/legalName".equals(a.path("element_path").asText()))
			{
				if ("maxLength".equals(a.path("property").asText()))
				{
					lengthOps++;
					lengthValue = a.path("value").asText();
				}
				if ("mandatory".equals(a.path("property").asText()))
				{
					mandatoryOps++;
				}
			}
			if ("/root/Company/Identification/name".equals(a.path("element_path").asText()) && "maxLength".equals(a.path("property").asText()))
			{
				check("existing maxLength rewritten in place", "10".equals(a.path("value").asText()));
			}
		}
		check("maxLength rewritten, not duplicated", lengthOps == 1 && "200".equals(lengthValue));
		check("new property appended", mandatoryOps == 1);
		check("append goes last", "mandatory".equals(ops.get(ops.size() - 1).path("arguments").path("property").asText())
				|| "maxLength".equals(ops.get(ops.size() - 1).path("arguments").path("property").asText()));
		// a change on a removed path is dropped
		final List<ObjectNode> dropped = PackOps.coreOps(core, Collections.singleton("/root/Company/Identification"), changed);
		for (final ObjectNode op : dropped)
		{
			check("no change survives on a removed path", !op.path("arguments").path("element_path").asText().startsWith("/root/Company/Identification"));
		}
	}

	private static void coreOpsDoesNotMutateTheCore(final PackCatalog c)
	{
		final PackCore core = c.pack("company").core();
		final String before = core.ops().toString();
		final Map<String, ObjectNode> changed = new LinkedHashMap<String, ObjectNode>();
		final ObjectNode props = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		props.put("maxLength", "1");
		changed.put("/root/Company/Identification/name", props);
		PackOps.coreOps(core, Collections.singleton("/root/Contact"), changed);
		check("core untouched", before.equals(core.ops().toString()));
	}
}
