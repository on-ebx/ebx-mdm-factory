package com.on.ebx.factory.capability;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** JUnit-free: run the main, a failure throws. */
public final class DerivationBodyBuilderTest
{
	public static void main(final String[] pArgs)
	{
		DerivationBodyBuilderTest.dataTypeForReturnTypeMapsEverySupportedKeyword();
		DerivationBodyBuilderTest.dataTypeForReturnTypeUnsupportedIsNull();
		DerivationBodyBuilderTest.returnTypeForDataTypeIsTheInverseMapping();
		DerivationBodyBuilderTest.returnTypeForDataTypeBlankOrUnknownIsString();
		DerivationBodyBuilderTest.scriptTypeOrNullIsStrict();
		DerivationBodyBuilderTest.namesAndPaths();
		DerivationBodyBuilderTest.createTriggerBodyForThePhoneExample();
		DerivationBodyBuilderTest.modifyTriggerBodyForThePhoneExample();
		DerivationBodyBuilderTest.hostileImportNameIsRefused();
		DerivationBodyBuilderTest.hostileBindingIsRefused();
		DerivationBodyBuilderTest.twoAttributeBinding();
		DerivationBodyBuilderTest.mandatoryAttributeHasNoGuard();
		DerivationBodyBuilderTest.allMandatoryMeansNoIf();
		DerivationBodyBuilderTest.nonStringTypesGetTheirOwnHelpers();
		DerivationBodyBuilderTest.literalArgumentIsEscaped();
		DerivationBodyBuilderTest.refusals();
		DerivationBodyBuilderTest.buildExplanationNamesSourceAndTarget();
		DerivationBodyBuilderTest.buildExplanationFallsBackWhenLabelsBlank();
		DerivationBodyBuilderTest.storedBodiesRoundTripThroughTheSeparator();
		DerivationBodyBuilderTest.theEarlierStoredFormIsStillRead();
		DerivationBodyBuilderTest.unreliableStoredBodiesAreNotGuessed();
		System.out.println("DerivationBodyBuilderTest: all green");
	}

	private static void dataTypeForReturnTypeMapsEverySupportedKeyword()
	{
		DerivationBodyBuilderTest.check("string", "STRING".equals(DerivationBodyBuilder.dataTypeForReturnType("string")));
		DerivationBodyBuilderTest.check("int", "INTEGER".equals(DerivationBodyBuilder.dataTypeForReturnType("int")));
		DerivationBodyBuilderTest.check("decimal", "DECIMAL".equals(DerivationBodyBuilder.dataTypeForReturnType("decimal")));
		DerivationBodyBuilderTest.check("boolean", "BOOLEAN".equals(DerivationBodyBuilder.dataTypeForReturnType("boolean")));
		DerivationBodyBuilderTest.check("date", "DATE".equals(DerivationBodyBuilder.dataTypeForReturnType("date")));
		DerivationBodyBuilderTest.check("datetime", "TIMESTAMP".equals(DerivationBodyBuilder.dataTypeForReturnType("datetime")));
		DerivationBodyBuilderTest.check("case-insensitive, trimmed", "STRING".equals(DerivationBodyBuilder.dataTypeForReturnType("  String  ")));
	}

	private static void dataTypeForReturnTypeUnsupportedIsNull()
	{
		DerivationBodyBuilderTest.check("json_object unsupported", DerivationBodyBuilder.dataTypeForReturnType("json_object") == null);
		DerivationBodyBuilderTest.check("list<string> unsupported", DerivationBodyBuilder.dataTypeForReturnType("list<string>") == null);
		DerivationBodyBuilderTest.check("blank unsupported", DerivationBodyBuilder.dataTypeForReturnType("") == null);
		DerivationBodyBuilderTest.check("null unsupported", DerivationBodyBuilder.dataTypeForReturnType(null) == null);
	}

	private static void returnTypeForDataTypeIsTheInverseMapping()
	{
		DerivationBodyBuilderTest.check("STRING", "string".equals(DerivationBodyBuilder.returnTypeForDataType("STRING")));
		DerivationBodyBuilderTest.check("INTEGER", "int".equals(DerivationBodyBuilder.returnTypeForDataType("INTEGER")));
		DerivationBodyBuilderTest.check("DECIMAL", "decimal".equals(DerivationBodyBuilder.returnTypeForDataType("DECIMAL")));
		DerivationBodyBuilderTest.check("BOOLEAN", "boolean".equals(DerivationBodyBuilder.returnTypeForDataType("BOOLEAN")));
		DerivationBodyBuilderTest.check("DATE", "date".equals(DerivationBodyBuilder.returnTypeForDataType("DATE")));
		DerivationBodyBuilderTest.check("TIMESTAMP", "datetime".equals(DerivationBodyBuilder.returnTypeForDataType("TIMESTAMP")));
	}

	private static void returnTypeForDataTypeBlankOrUnknownIsString()
	{
		DerivationBodyBuilderTest.check("blank", "string".equals(DerivationBodyBuilder.returnTypeForDataType("")));
		DerivationBodyBuilderTest.check("null", "string".equals(DerivationBodyBuilder.returnTypeForDataType(null)));
		DerivationBodyBuilderTest.check("unrecognised", "string".equals(DerivationBodyBuilder.returnTypeForDataType("SOMETHING_ELSE")));
	}

	private static Map<String, ConstraintBodyBuilder.BindingEntry> bindingOf(final String pParam, final String pAttribute)
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put(pParam, new ConstraintBodyBuilder.BindingEntry(pParam, ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, pAttribute));
		return binding;
	}

	private static Map<String, ConstraintBodyBuilder.BindingEntry> phoneBinding()
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = DerivationBodyBuilderTest.bindingOf("value", "phone");
		binding.put("defaultCountry", new ConstraintBodyBuilder.BindingEntry("defaultCountry", ConstraintBodyBuilder.BindingEntry.Kind.LITERAL, ""));
		return binding;
	}

	private static java.util.List<DerivationBodyBuilder.Param> phoneParams()
	{
		return Arrays.asList(new DerivationBodyBuilder.Param("value", "string"), new DerivationBodyBuilder.Param("defaultCountry", "string"));
	}

	private static Map<String, String> types(final String... pPairs)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (int i = 0; i + 1 < pPairs.length; i += 2)
		{
			out.put(pPairs[i], pPairs[i + 1]);
		}
		return out;
	}

	private static final String CREATE_PHONE = "import Fasttrack.lib.phone as lib;\n\n"
		+ "export procedure onBeforeCreate()\n"
		+ "begin\n"
		+ "  if isNull(_ebx.record.phone) then\n"
		+ "  begin\n"
		+ "    _ebx.record.phoneE164 := null;\n"
		+ "  end\n"
		+ "  else\n"
		+ "  begin\n"
		+ "    _ebx.record.phoneE164 := lib.toE164(_ebx.record.phone, '');\n"
		+ "  end\n"
		+ "end\n";

	private static final String MODIFY_PHONE = "import Fasttrack.lib.phone as lib;\n\n"
		+ "function differs_string(a: string, b: string): boolean\n"
		+ "begin\n"
		+ "  if isNull(a) then return isNotNull(b);\n"
		+ "  if isNull(b) then return true;\n"
		+ "  return a <> b;\n"
		+ "end\n\n"
		+ "export procedure onBeforeModify()\n"
		+ "begin\n"
		+ "  if differs_string(_ebx.record.phone, _ebx.nextRecord.phone)\n"
		+ "    or differs_string(_ebx.record.phoneE164, _ebx.nextRecord.phoneE164)\n"
		+ "    or isNull(_ebx.nextRecord.phoneE164) then\n"
		+ "  begin\n"
		+ "    if isNull(_ebx.nextRecord.phone) then\n"
		+ "    begin\n"
		+ "      _ebx.nextRecord.phoneE164 := null;\n"
		+ "    end\n"
		+ "    else\n"
		+ "    begin\n"
		+ "      _ebx.nextRecord.phoneE164 := lib.toE164(_ebx.nextRecord.phone, '');\n"
		+ "    end\n"
		+ "  end\n"
		+ "end\n";

	private static void scriptTypeOrNullIsStrict()
	{
		DerivationBodyBuilderTest.check("blank is string", "string".equals(DerivationBodyBuilder.scriptTypeOrNull("")) && "string".equals(DerivationBodyBuilder.scriptTypeOrNull(null)));
		DerivationBodyBuilderTest.check("STRING", "string".equals(DerivationBodyBuilder.scriptTypeOrNull("STRING")));
		DerivationBodyBuilderTest.check("INTEGER", "int".equals(DerivationBodyBuilder.scriptTypeOrNull("INTEGER")));
		DerivationBodyBuilderTest.check("DECIMAL", "decimal".equals(DerivationBodyBuilder.scriptTypeOrNull("decimal")));
		DerivationBodyBuilderTest.check("BOOLEAN", "boolean".equals(DerivationBodyBuilder.scriptTypeOrNull("BOOLEAN")));
		DerivationBodyBuilderTest.check("DATE", "date".equals(DerivationBodyBuilder.scriptTypeOrNull("DATE")));
		DerivationBodyBuilderTest.check("TIMESTAMP", "datetime".equals(DerivationBodyBuilder.scriptTypeOrNull("TIMESTAMP")));
		DerivationBodyBuilderTest.check("unknown is refused, not guessed", DerivationBodyBuilder.scriptTypeOrNull("GEOMETRY") == null);
	}

	private static void namesAndPaths()
	{
		DerivationBodyBuilderTest.check("field path", "/root/Contact/phoneE164".equals(DerivationBodyBuilder.elementPath("Contact", "phoneE164")));
		DerivationBodyBuilderTest.check("table path", "/root/Personne".equals(DerivationBodyBuilder.tableElementPath("Personne")));
		DerivationBodyBuilderTest.check("create name (the one already on Vol100k_Staging)", "DerivePhoneE164OnCreate".equals(DerivationBodyBuilder.triggerName("phoneE164", true)));
		DerivationBodyBuilderTest.check("modify name", "DerivePhoneE164OnModify".equals(DerivationBodyBuilder.triggerName("phoneE164", false)));
		DerivationBodyBuilderTest.check("an upper-case first letter is kept", "DeriveKeyOnCreate".equals(DerivationBodyBuilder.triggerName("Key", true)));
		DerivationBodyBuilderTest.check("idePath",
			"data-model/Vol100k_Staging/root/Personne/@trigger/DerivePhoneE164OnCreate".equals(DerivationBodyBuilder.triggerIdePath("Vol100k_Staging", "Personne", "DerivePhoneE164OnCreate")));
	}

	private static void createTriggerBodyForThePhoneExample()
	{
		final Map<String, Boolean> mandatory = new LinkedHashMap<String, Boolean>();
		mandatory.put("phone", Boolean.FALSE);
		final String body = DerivationBodyBuilder.buildCreateTriggerBody("Fasttrack.lib.phone", "lib", "toE164", DerivationBodyBuilderTest.phoneParams(),
			DerivationBodyBuilderTest.phoneBinding(), mandatory, "phoneE164");
		DerivationBodyBuilderTest.check("create body:\n" + body, DerivationBodyBuilderTest.CREATE_PHONE.equals(body));
	}

	private static void modifyTriggerBodyForThePhoneExample()
	{
		final Map<String, Boolean> mandatory = new LinkedHashMap<String, Boolean>();
		mandatory.put("phone", Boolean.FALSE);
		final String body = DerivationBodyBuilder.buildModifyTriggerBody("Fasttrack.lib.phone", "lib", "toE164", DerivationBodyBuilderTest.phoneParams(),
			DerivationBodyBuilderTest.phoneBinding(), mandatory, "phoneE164", DerivationBodyBuilderTest.types("phone", "string"), "string");
		DerivationBodyBuilderTest.check("modify body:\n" + body, DerivationBodyBuilderTest.MODIFY_PHONE.equals(body));
	}

	/** Audit F2-01 / F2-16: an import name that carries statements never reaches a trigger body (create and modify). */
	private static void hostileImportNameIsRefused()
	{
		final Map<String, Boolean> mandatory = new LinkedHashMap<String, Boolean>();
		mandatory.put("phone", Boolean.FALSE);
		for (final String hostile : new String[] { "a.b as x; uses core.http as h; //", "Fasttrack.lib.phone as p; uses core.json as j; export procedure x() //", "", "a b" })
		{
			boolean createRefused = false;
			try
			{
				DerivationBodyBuilder.buildCreateTriggerBody(hostile, "phone", "toE164", DerivationBodyBuilderTest.phoneParams(), DerivationBodyBuilderTest.phoneBinding(), mandatory, "phoneE164");
			}
			catch (final IllegalArgumentException ex)
			{
				createRefused = true;
			}
			boolean modifyRefused = false;
			try
			{
				DerivationBodyBuilder.buildModifyTriggerBody(hostile, "phone", "toE164", DerivationBodyBuilderTest.phoneParams(), DerivationBodyBuilderTest.phoneBinding(), mandatory, "phoneE164",
					DerivationBodyBuilderTest.types("phone", "string"), "string");
			}
			catch (final IllegalArgumentException ex)
			{
				modifyRefused = true;
			}
			DerivationBodyBuilderTest.check("hostile import name refused (create): " + hostile, createRefused);
			DerivationBodyBuilderTest.check("hostile import name refused (modify): " + hostile, modifyRefused);
		}
	}

	/** Audit F2-01 / F2-16: a binding value that is not an identifier is refused. */
	private static void hostileBindingIsRefused()
	{
		final Map<String, Boolean> mandatory = new LinkedHashMap<String, Boolean>();
		mandatory.put("phone", Boolean.FALSE);
		for (final String hostile : new String[] { "phone) or true //", "phone; x := 1", "a.b" })
		{
			final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
			binding.put("value", new ConstraintBodyBuilder.BindingEntry("value", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, hostile));
			boolean refused = false;
			try
			{
				DerivationBodyBuilder.buildCreateTriggerBody("Fasttrack.lib.phone", "phone", "toE164", DerivationBodyBuilderTest.phoneParams(), binding, mandatory, "phoneE164");
			}
			catch (final IllegalArgumentException ex)
			{
				refused = true;
			}
			DerivationBodyBuilderTest.check("hostile binding refused: " + hostile, refused);
		}
	}

	private static void twoAttributeBinding()
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("value", new ConstraintBodyBuilder.BindingEntry("value", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "phone"));
		binding.put("country", new ConstraintBodyBuilder.BindingEntry("country", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "country"));
		final java.util.List<DerivationBodyBuilder.Param> params = Arrays.asList(new DerivationBodyBuilder.Param("value", "string"),
			new DerivationBodyBuilder.Param("country", "string"));
		final Map<String, Boolean> mandatory = new LinkedHashMap<String, Boolean>();
		final String create = DerivationBodyBuilder.buildCreateTriggerBody("Fasttrack.lib.phone", "phone", "toE164", params, binding, mandatory, "phoneE164");
		DerivationBodyBuilderTest.check("one guard over both sources, null only when EVERY one is empty: " + create, create.contains("  if isNull(_ebx.record.phone) and isNull(_ebx.record.country) then\n"));
		DerivationBodyBuilderTest.check("no 'or' guard left: " + create, !create.contains("isNull(_ebx.record.phone) or") && !create.contains(") or isNull(_ebx.record.country)"));
		DerivationBodyBuilderTest.check("call with both", create.contains("_ebx.record.phoneE164 := phone.toE164(_ebx.record.phone, _ebx.record.country);"));
		final String modify = DerivationBodyBuilder.buildModifyTriggerBody("Fasttrack.lib.phone", "phone", "toE164", params, binding, mandatory, "phoneE164",
			DerivationBodyBuilderTest.types("phone", "string", "country", "string"), "string");
		DerivationBodyBuilderTest.check("every source compared: " + modify, modify.contains("  if differs_string(_ebx.record.phone, _ebx.nextRecord.phone)\n"
			+ "    or differs_string(_ebx.record.country, _ebx.nextRecord.country)\n"
			+ "    or differs_string(_ebx.record.phoneE164, _ebx.nextRecord.phoneE164)\n"
			+ "    or isNull(_ebx.nextRecord.phoneE164) then\n"));
		DerivationBodyBuilderTest.check("one helper for one type", modify.indexOf("function differs_string") == modify.lastIndexOf("function differs_string"));
		DerivationBodyBuilderTest.check("guard on the new values, same rule: " + modify, modify.contains("    if isNull(_ebx.nextRecord.phone) and isNull(_ebx.nextRecord.country) then\n"));
		DerivationBodyBuilderTest.check("recompute condition unchanged (still an or over differences)", modify.contains("    or differs_string(_ebx.record.phoneE164, _ebx.nextRecord.phoneE164)\n    or isNull(_ebx.nextRecord.phoneE164) then\n"));
	}

	private static void mandatoryAttributeHasNoGuard()
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("value", new ConstraintBodyBuilder.BindingEntry("value", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "phone"));
		binding.put("country", new ConstraintBodyBuilder.BindingEntry("country", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "country"));
		final java.util.List<DerivationBodyBuilder.Param> params = Arrays.asList(new DerivationBodyBuilder.Param("value", "string"),
			new DerivationBodyBuilder.Param("country", "string"));
		final Map<String, Boolean> mandatory = new LinkedHashMap<String, Boolean>();
		mandatory.put("phone", Boolean.TRUE);
		// mandatory + optional input: the inputs can never all be null, so no guard at all, in the create AND the modify body
		final String create = DerivationBodyBuilder.buildCreateTriggerBody("Fasttrack.lib.phone", "phone", "toE164", params, binding, mandatory, "phoneE164");
		DerivationBodyBuilderTest.check("mandatory + optional input, no guard in create: " + create, !create.contains("isNull(") && !create.contains("if ")
			&& create.contains("  _ebx.record.phoneE164 := phone.toE164(_ebx.record.phone, _ebx.record.country);\n"));
		final String modify = DerivationBodyBuilder.buildModifyTriggerBody("Fasttrack.lib.phone", "phone", "toE164", params, binding, mandatory, "phoneE164",
			DerivationBodyBuilderTest.types("phone", "string", "country", "string"), "string");
		DerivationBodyBuilderTest.check("no guard in modify either: " + modify, !modify.contains("isNull(_ebx.nextRecord.phone)") && !modify.contains("isNull(_ebx.nextRecord.country)")
			&& modify.contains("    _ebx.nextRecord.phoneE164 := phone.toE164(_ebx.nextRecord.phone, _ebx.nextRecord.country);\n"));
		DerivationBodyBuilderTest.check("recompute condition kept: " + modify, modify.contains("    or isNull(_ebx.nextRecord.phoneE164) then\n"));
		// all optional: still the "and" guard (twoAttributeBinding); one optional input alone: the single guard
		final Map<String, ConstraintBodyBuilder.BindingEntry> one = DerivationBodyBuilderTest.bindingOf("value", "phone");
		final String single = DerivationBodyBuilder.buildCreateTriggerBody("Fasttrack.lib.phone", "phone", "toE164",
			Arrays.asList(new DerivationBodyBuilder.Param("value", "string")), one, new LinkedHashMap<String, Boolean>(), "phoneE164");
		DerivationBodyBuilderTest.check("single optional input keeps isNull(a): " + single, single.contains("  if isNull(_ebx.record.phone) then\n"));
	}

	private static void allMandatoryMeansNoIf()
	{
		final Map<String, Boolean> mandatory = new LinkedHashMap<String, Boolean>();
		mandatory.put("phone", Boolean.TRUE);
		final String create = DerivationBodyBuilder.buildCreateTriggerBody("Fasttrack.lib.phone", "lib", "toE164", DerivationBodyBuilderTest.phoneParams(),
			DerivationBodyBuilderTest.phoneBinding(), mandatory, "phoneE164");
		DerivationBodyBuilderTest.check("bare call, no if: " + create, create.equals("import Fasttrack.lib.phone as lib;\n\n"
			+ "export procedure onBeforeCreate()\nbegin\n  _ebx.record.phoneE164 := lib.toE164(_ebx.record.phone, '');\nend\n"));
		final String modify = DerivationBodyBuilder.buildModifyTriggerBody("Fasttrack.lib.phone", "lib", "toE164", DerivationBodyBuilderTest.phoneParams(),
			DerivationBodyBuilderTest.phoneBinding(), mandatory, "phoneE164", DerivationBodyBuilderTest.types("phone", "string"), "string");
		DerivationBodyBuilderTest.check("bare call in the modify body too: " + modify,
			modify.contains("  begin\n    _ebx.nextRecord.phoneE164 := lib.toE164(_ebx.nextRecord.phone, '');\n  end\nend\n"));
	}

	private static void nonStringTypesGetTheirOwnHelpers()
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = DerivationBodyBuilderTest.bindingOf("value", "amount");
		final java.util.List<DerivationBodyBuilder.Param> params = Arrays.asList(new DerivationBodyBuilder.Param("value", "decimal"));
		final String modify = DerivationBodyBuilder.buildModifyTriggerBody("Acme.lib.money", "money", "round", params, binding, new LinkedHashMap<String, Boolean>(), "rounded",
			DerivationBodyBuilderTest.types("amount", "decimal"), "int");
		DerivationBodyBuilderTest.check("decimal helper: " + modify, modify.contains("function differs_decimal(a: decimal, b: decimal): boolean\n"));
		DerivationBodyBuilderTest.check("int helper for the derived attribute", modify.contains("function differs_int(a: int, b: int): boolean\n"));
		DerivationBodyBuilderTest.check("each used", modify.contains("differs_decimal(_ebx.record.amount, _ebx.nextRecord.amount)")
			&& modify.contains("differs_int(_ebx.record.rounded, _ebx.nextRecord.rounded)"));
		DerivationBodyBuilderTest.check("no string helper when no string is compared", !modify.contains("differs_string"));
	}

	private static void literalArgumentIsEscaped()
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = DerivationBodyBuilderTest.bindingOf("value", "phone");
		binding.put("defaultCountry", new ConstraintBodyBuilder.BindingEntry("defaultCountry", ConstraintBodyBuilder.BindingEntry.Kind.LITERAL, "it's FR"));
		final String create = DerivationBodyBuilder.buildCreateTriggerBody("Fasttrack.lib.phone", "phone", "toE164", DerivationBodyBuilderTest.phoneParams(), binding,
			new LinkedHashMap<String, Boolean>(), "phoneE164");
		DerivationBodyBuilderTest.check("literal escaped: " + create, create.contains("phone.toE164(_ebx.record.phone, 'it\\'s FR')"));
	}

	private static void refusals()
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> unsettled = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		unsettled.put("value", new ConstraintBodyBuilder.BindingEntry("value", ConstraintBodyBuilder.BindingEntry.Kind.TO_BE_PROVIDED, "no attribute fits"));
		boolean threw = false;
		try
		{
			DerivationBodyBuilder.buildCreateTriggerBody("Fasttrack.lib.phone", "phone", "toE164", Arrays.asList(new DerivationBodyBuilder.Param("value", "string")), unsettled,
				new LinkedHashMap<String, Boolean>(), "phoneE164");
		}
		catch (final IllegalStateException ex)
		{
			threw = true;
		}
		DerivationBodyBuilderTest.check("refuses a toBeProvided parameter", threw);
		threw = false;
		try
		{
			DerivationBodyBuilder.buildModifyTriggerBody("Fasttrack.lib.phone", "lib", "toE164", DerivationBodyBuilderTest.phoneParams(), DerivationBodyBuilderTest.phoneBinding(),
				new LinkedHashMap<String, Boolean>(), "phoneE164", new LinkedHashMap<String, String>(), "string");
		}
		catch (final IllegalStateException ex)
		{
			threw = true;
		}
		DerivationBodyBuilderTest.check("refuses a source attribute with no script type", threw);
	}

	private static void buildExplanationNamesSourceAndTarget()
	{
		final String explanation = DerivationBodyBuilder.buildExplanation("Fasttrack.lib.phone", "toE164", "Phone", "Phone (E.164)");
		DerivationBodyBuilderTest.check("names the target", explanation.contains("Phone (E.164)"));
		DerivationBodyBuilderTest.check("names the source", explanation.contains("computed from Phone"));
		DerivationBodyBuilderTest.check("names the function", explanation.contains("Fasttrack.lib.phone.toE164"));
		DerivationBodyBuilderTest.check("says it is stored and kept by triggers", explanation.contains("stored value") && explanation.contains("created") && explanation.contains("triggers"));
	}

	private static void buildExplanationFallsBackWhenLabelsBlank()
	{
		final String explanation = DerivationBodyBuilder.buildExplanation("Fasttrack.lib.phone", "toE164", null, "");
		DerivationBodyBuilderTest.check("falls back for the target", explanation.contains("this attribute"));
		DerivationBodyBuilderTest.check("falls back for the source", explanation.contains("its source attribute"));
	}

	private static String phoneBody(final boolean pCreate)
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = DerivationBodyBuilderTest.phoneBinding();
		final java.util.List<DerivationBodyBuilder.Param> params = Arrays.asList(new DerivationBodyBuilder.Param("value", "string"),
			new DerivationBodyBuilder.Param("defaultCountry", "string"));
		final Map<String, String> types = new LinkedHashMap<String, String>();
		types.put("phone", "string");
		return pCreate ? DerivationBodyBuilder.buildCreateTriggerBody("Fasttrack.lib.phone", "phone", "toE164", params, binding, DerivationBodyBuilder.noMandatoryFlags(), "phoneE164")
			: DerivationBodyBuilder.buildModifyTriggerBody("Fasttrack.lib.phone", "phone", "toE164", params, binding, DerivationBodyBuilder.noMandatoryFlags(), "phoneE164", types,
				"string");
	}

	private static void storedBodiesRoundTripThroughTheSeparator()
	{
		final String create = DerivationBodyBuilderTest.phoneBody(true);
		final String modify = DerivationBodyBuilderTest.phoneBody(false);
		final String stored = DerivationBodyBuilder.joinStoredBodies("DerivePhoneE164OnCreate", "data-model/M/root/T/@trigger/DerivePhoneE164OnCreate", create, "DerivePhoneE164OnModify",
			"data-model/M/root/T/@trigger/DerivePhoneE164OnModify", modify);
		DerivationBodyBuilderTest.check("the separator line is in the stored text", stored.contains("\n" + DerivationBodyBuilder.STORED_BODY_SEPARATOR + "\n"));
		final DerivationBodyBuilder.StoredBodies back = DerivationBodyBuilder.splitStoredBodies(stored, "DerivePhoneE164OnCreate", "DerivePhoneE164OnModify");
		DerivationBodyBuilderTest.check("both bodies come back exactly", back != null && create.equals(back.createSource) && modify.equals(back.modifySource));
		DerivationBodyBuilderTest.check("a generated body never holds the separator", !create.contains(DerivationBodyBuilder.STORED_BODY_SEPARATOR) && !modify.contains(DerivationBodyBuilder.STORED_BODY_SEPARATOR));
	}

	private static void theEarlierStoredFormIsStillRead()
	{
		final String create = DerivationBodyBuilderTest.phoneBody(true);
		final String modify = DerivationBodyBuilderTest.phoneBody(false);
		// what lot T1 wrote: two header comments, no separator line
		final String legacy = "// DerivePhoneE164OnCreate (data-model/M/root/T/@trigger/DerivePhoneE164OnCreate)\n" + create + "\n// DerivePhoneE164OnModify (data-model/M/root/T/@trigger/DerivePhoneE164OnModify)\n"
			+ modify;
		final DerivationBodyBuilder.StoredBodies back = DerivationBodyBuilder.splitStoredBodies(legacy, "DerivePhoneE164OnCreate", "DerivePhoneE164OnModify");
		DerivationBodyBuilderTest.check("the earlier form gives both bodies back exactly", back != null && create.equals(back.createSource) && modify.equals(back.modifySource));
	}

	private static void unreliableStoredBodiesAreNotGuessed()
	{
		DerivationBodyBuilderTest.check("nothing stored", DerivationBodyBuilder.splitStoredBodies(null, "A", "B") == null && DerivationBodyBuilder.splitStoredBodies("  ", "A", "B") == null);
		DerivationBodyBuilderTest.check("a single body", DerivationBodyBuilder.splitStoredBodies("// A (p)\nexport procedure x()\nbegin\nend\n", "A", "B") == null);
		DerivationBodyBuilderTest.check("a modify header of another name", DerivationBodyBuilder.splitStoredBodies("// A (p)\nbody\n// C (q)\nbody2", "A", "B") == null);
		DerivationBodyBuilderTest.check("an empty modify body", DerivationBodyBuilder.splitStoredBodies("// A (p)\nbody\n" + DerivationBodyBuilder.STORED_BODY_SEPARATOR + "\n// B (q)\n", "A", "B") == null);
		DerivationBodyBuilderTest.check("no create header", DerivationBodyBuilder.splitStoredBodies("body\n" + DerivationBodyBuilder.STORED_BODY_SEPARATOR + "\n// B (q)\nbody2", "A", "B") == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
