package com.on.ebx.factory.accel;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free: run the main, a failure throws. */
public final class PackCatalogTest
{
	public static void main(final String[] pArgs)
	{
		final PackCatalog catalog = PackCatalog.load();
		PackCatalogTest.packsAndBlocksLoad(catalog);
		PackCatalogTest.everyOpNameIsKnown(catalog);
		PackCatalogTest.corePathsAreUnique(catalog);
		PackCatalogTest.coreFieldsLiveUnderACreatedTable(catalog);
		PackCatalogTest.foreignKeysUseTheDocumentedArgumentName(catalog);
		PackCatalogTest.suggestFindsCompanyForSupplier(catalog);
		PackCatalogTest.referenceDataParsesWithItsColumnMap(catalog);
		PackCatalogTest.scriptBodiesHaveNoUnresolvedPlaceholder(catalog);
		PackCatalogTest.librariesAndWorkflowTypes(catalog);
		PackCatalogTest.promptTextsAreOneLinePerEntryAndNotJson(catalog);
		PackCatalogTest.indexNamesEveryFolderOnDisk();
		PackCatalogTest.malformedCsvFailsWithItsName();
		System.out.println("PackCatalogTest OK");
	}

	static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static void packsAndBlocksLoad(final PackCatalog c)
	{
		check("two packs", c.packs().size() == 2);
		check("packs sorted by key", "company".equals(c.packs().get(0).key()) && "reference-data".equals(c.packs().get(1).key()));
		check("company present", c.pack("company") != null && "/root/Company".equals(c.pack("company").mainTable()));
		check("unknown pack is null", c.pack("nope") == null && c.block("nope") == null);
		check("two blocks", c.blocks().size() == 2 && c.block("audit") != null && c.block("contacts") != null);
		check("company requires reference-data", c.pack("company").requires().packs().contains("reference-data"));
		check("company layout read", c.pack("company").layouts().containsKey("/root/Company"));
		check("company workflows and matching read", c.pack("company").workflows() != null && c.pack("company").matching() != null);
		check("guidance read", c.pack("company").guidance().length() > 200);
		check("labels localized", "Audit".equals(c.block("audit").label(Locale.ENGLISH)) && !c.pack("company").label(Locale.FRENCH).isEmpty());
		check("audit has 4 fields and 2 trigger scripts", c.block("audit").fields().size() == 4 && c.block("audit").scripts().size() == 2);
		check("contacts constraint anchored on a field", "phone".equals(c.block("contacts").script("PhoneCheck").field()));
		check("company places 6 blocks", c.pack("company").core().blockPlacements().size() == 6);
		check("every placement's table is created by the pack", c.pack("company").core().tablePaths()
				.containsAll(java.util.Arrays.asList("/root/Company", "/root/Contact")));
	}

	private static void everyOpNameIsKnown(final PackCatalog c)
	{
		for (final Pack p : c.packs())
		{
			for (final ObjectNode op : p.core().ops())
			{
				check(p.key() + ": unknown op " + op.path("op").asText(), PackOps.KNOWN_OPS.contains(op.path("op").asText()));
			}
		}
	}

	private static void corePathsAreUnique(final PackCatalog c)
	{
		for (final Pack p : c.packs())
		{
			final Set<String> seen = new HashSet<String>();
			for (final String path : p.core().fieldPaths())
			{
				check(p.key() + ": duplicate field path " + path, seen.add(path));
			}
			final Set<String> tables = new HashSet<String>();
			for (final String path : p.core().tablePaths())
			{
				check(p.key() + ": duplicate table path " + path, tables.add(path));
			}
		}
	}

	private static void coreFieldsLiveUnderACreatedTable(final PackCatalog c)
	{
		for (final Pack p : c.packs())
		{
			final List<String> tables = p.core().tablePaths();
			for (final String field : p.core().fieldPaths())
			{
				boolean under = false;
				for (final String t : tables)
				{
					under |= field.startsWith(t + "/");
				}
				check(p.key() + ": field " + field + " is not under a created table", under);
			}
		}
	}

	private static void foreignKeysUseTheDocumentedArgumentName(final PackCatalog c)
	{
		// StagingModelDiff.addForeignKeyOp, the only Java caller of apply_data_model_patch that writes it: field_path + target_table_path
		for (final Pack p : c.packs())
		{
			for (final ObjectNode op : p.core().ops())
			{
				if ("add_foreign_key".equals(op.path("op").asText()))
				{
					check(p.key() + ": add_foreign_key needs target_table_path", op.path("arguments").has("target_table_path")
							&& op.path("arguments").has("field_path") && !op.path("arguments").has("target_table"));
				}
			}
		}
	}

	private static void suggestFindsCompanyForSupplier(final PackCatalog c)
	{
		final List<Pack> hits = c.suggest("supplier");
		check("supplier gives company", !hits.isEmpty() && "company".equals(hits.get(0).key()));
		check("french need gives company", !c.suggest("je cherche un referentiel fournisseur").isEmpty());
		check("empty need gives nothing", c.suggest("  ").isEmpty() && c.suggest(null).isEmpty());
		check("unrelated need gives nothing", c.suggest("zzzzqqqq").isEmpty());
	}

	private static void referenceDataParsesWithItsColumnMap(final PackCatalog c)
	{
		final Pack ref = c.pack("reference-data");
		check("nine reference tables", ref.referenceData().size() == 9);
		for (final ReferenceTable t : ref.referenceData().values())
		{
			check(t.path() + ": has rows", !t.rows().isEmpty());
			if (!t.columns().isEmpty())
			{
				for (final String header : t.columns().keySet())
				{
					check(t.path() + ": mapped column " + header + " absent from the CSV header " + t.header(), t.header().contains(header));
				}
			}
			check(t.path() + ": key " + t.key() + " is not a loaded field " + t.fieldNames(), t.fieldNames().contains(t.key()));
			final Set<String> keys = new HashSet<String>();
			for (final Map<String, String> rec : t.records())
			{
				final String k = rec.get(t.key());
				check(t.path() + ": empty key", k != null && !k.trim().isEmpty());
				check(t.path() + ": duplicate key " + k, keys.add(k));
			}
			// every loaded field exists in the pack's core for that table
			for (final String f : t.fieldNames())
			{
				check(t.path() + ": field " + f + " is not created by core.json", ref.core().fieldPaths().contains(t.path() + "/" + f));
			}
		}
		final ReferenceTable country = ref.referenceData().get("/root/ReferenceData/Country");
		check("country map", "alpha2".equals(country.key()) && country.records().get(0).containsKey("dialCode") && !country.records().get(0).containsKey("ITU"));
		check("legal forms are all there", ref.referenceData().get("/root/ReferenceData/LegalForm").rows().size() > 3000);
		// accents survive
		boolean accent = false;
		for (final Map<String, String> rec : country.records())
		{
			accent |= rec.get("nameFr").contains("é");
		}
		check("utf-8 read", accent);
	}

	private static void scriptBodiesHaveNoUnresolvedPlaceholder(final PackCatalog c)
	{
		for (final Block b : c.blocks())
		{
			for (final Block.Script s : b.scripts())
			{
				final String body = PackOps.scriptBody(b, s.name(), "Company", b.group());
				check(b.key() + "/" + s.name() + ": unresolved placeholder", !body.contains("${"));
				check(b.key() + "/" + s.name() + ": template had a placeholder", s.body().contains("${"));
			}
		}
		check("audit uses the chosen group", PackOps.scriptBody(c.block("audit"), "AuditOnCreate", "Company", "Trace").contains("_ebx.record.Trace.createdOn"));
		check("table name taken from a path", PackOps.scriptBody(c.block("contacts"), "PhoneCheck", "/root/Contact", null).contains("record : .Contact"));
		boolean refused = false;
		try
		{
			PackOps.scriptBody(c.block("audit"), "Nope", "Company", null);
		}
		catch (final IllegalArgumentException expected)
		{
			refused = true;
		}
		check("unknown script refused", refused);
	}

	private static void librariesAndWorkflowTypes(final PackCatalog c)
	{
		check("library known", c.libraries().knows("Fasttrack.lib.phone.isValid") && c.libraries().knows("Fasttrack.lib.phone.toE164(phone: string, country: string)"));
		check("library unknown", !c.libraries().knows("Fasttrack.lib.phone.nope") && !c.libraries().knows("date.isValid") && !c.libraries().knows(null));
		check("library lookup", c.libraries().library("Fasttrack.lib.validator.iban") != null && c.libraries().all().size() == 8);
		check("creationWithReview generated", c.workflowTypes().type("creationWithReview").generated());
		check("changeRequest not generated", !c.workflowTypes().type("changeRequest").generated());
		check("four types", c.workflowTypes().all().size() == 4 && c.workflowTypes().type("nope") == null);
		check("types have parameters", c.workflowTypes().type("creationWithReview").parameters().containsKey("reviewers"));
	}

	private static void promptTextsAreOneLinePerEntryAndNotJson(final PackCatalog c)
	{
		final String libs = c.libraries().promptText();
		check("one line per library", libs.split("\n").length == 8 && !libs.contains("{"));
		final String types = c.workflowTypes().promptText();
		check("one line per workflow type", types.split("\n").length == 4 && !types.contains("{") && types.contains("creationWithReview"));
		final String cat = c.promptText();
		check("catalogue lines", cat.split("\n").length == 4 && cat.contains("pack company") && cat.contains("block audit"));
	}

	/** The class path cannot be listed: index.json must name every folder shipped, or a new pack would be silently ignored. */
	private static void indexNamesEveryFolderOnDisk()
	{
		final String source = System.getProperty("mdmf.sourceDir");
		if (source == null)
		{
			return;
		}
		final File root = new File(source).getParentFile().getParentFile().getParentFile().getParentFile().getParentFile();
		final File accel = new File(root, "main/resources/com/on/ebx/factory/accel");
		if (!accel.isDirectory())
		{
			return;
		}
		final PackCatalog c = PackCatalog.load();
		final File[] packs = new File(accel, "packs").listFiles();
		for (final File f : packs)
		{
			if (f.isDirectory())
			{
				check("pack folder " + f.getName() + " is missing from index.json", c.pack(f.getName()) != null);
			}
		}
		for (final File f : new File(accel, "blocks").listFiles())
		{
			if (f.isDirectory())
			{
				check("block folder " + f.getName() + " is missing from index.json", c.block(f.getName()) != null);
			}
		}
		for (final File f : packs)
		{
			final File layouts = new File(f, "layouts");
			if (layouts.isDirectory())
			{
				check("layouts of " + f.getName() + " missing from index.json", c.pack(f.getName()).layouts().size() == layouts.listFiles().length);
			}
		}
	}

	private static void malformedCsvFailsWithItsName()
	{
		boolean failed = false;
		try
		{
			ReferenceTable.parseCsv("a,b\n\"x,y\n", "demo.csv");
		}
		catch (final IllegalStateException e)
		{
			failed = e.getMessage().contains("demo.csv");
		}
		check("unterminated quote refused with the file name", failed);
		final List<List<String>> ok = ReferenceTable.parseCsv("a,b\r\n\"x,\"\"y\"\"\",z\r\n\r\n", "demo.csv");
		check("quotes and CRLF", ok.size() == 2 && "x,\"y\"".equals(ok.get(1).get(0)) && "z".equals(ok.get(1).get(1)));
	}
}
