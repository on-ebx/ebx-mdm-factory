package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.capability.ConstraintBodyBuilder.BindingEntry;

/**
 * Pure builder for the two EBX-Script table triggers that keep a {@code SCRIPT_DERIVATION} attribute up to date - the sibling of
 * {@link ConstraintBodyBuilder}, considerably simpler: a derivation body is ALWAYS one call to the target library function, no
 * message, no severity - see {@code quality.ScriptDerivation}'s own Javadoc for the design rationale and {@code
 * docs/SCRIPT-DERIVATION.md} sec 10 for the measured facts. No EBX class anywhere in this file, unit-tested without a running
 * instance, the same convention {@link ConstraintBodyBuilder} itself follows.
 * <p>
 * <b>Lot T1 (2026-10-04): the derived attribute is an ORDINARY STORED field, fed by a {@code beforeCreate} and a {@code
 * beforeModify} table trigger</b> - no longer a computed function field. Measured live on 6.3.0-rc2: an INDEXED function field cannot
 * import a script library ({@code SCRIPT_LIBRARY_ACCESS} not available), and matching needs the field indexed; a trigger script type
 * has {@code SCRIPT_LIBRARY_ACCESS}. The create trigger always computes; the modify trigger recomputes only when it must (a source
 * attribute changed, someone wrote the derived field, or the new derived value is empty), so a write to another field costs nothing.
 * <p>
 * <b>Null guard (2026-10-12): the derived value is null only when EVERY guarded input is null</b> ({@code isNull(a) and isNull(b)}, not
 * {@code or}), and no guard at all when one input is mandatory. Measured live on project Vol100kN: {@code Fasttrack.lib.names.sortedKeyOf(firstName, lastName)} documents "either field may be null"
 * and answers a key from one name alone, but the {@code or} guard gave NO key to every row with an empty last name (8 % of one source, about
 * 1,900 missed duplicate pairs). An input that is a {@code LITERAL} is never a guarded input. The modify body's recompute condition
 * ({@code differs_...(old, new)} on each source, on the derived attribute, or {@code isNull(derived)}) is independent of the guard and unchanged.
 * <p>
 * <b>Binding parsing/rendering is reused directly from {@link ConstraintBodyBuilder}</b> ({@link
 * ConstraintBodyBuilder#parseBinding}, {@link ConstraintBodyBuilder.BindingEntry}, the package-private {@code
 * renderLiteral}) - the JSON shape {@code CapabilitySuggestion.binding} carries is IDENTICAL for both natures ("which accepted
 * attribute(s)/literal feed which function parameter"), so this class never re-derives it.
 * <p>
 * <b>Where the values are read</b> (measured): in {@code onBeforeCreate} the record being created is {@code _ebx.record} (writable); in
 * {@code onBeforeModify} {@code _ebx.record} holds the OLD values (read only) and {@code _ebx.nextRecord} the NEW ones (writable).
 * <p>
 * <b>Types are not free text</b>: a derivation's function must return one of the six scalar types a {@code
 * StagingAttribute.dataType} can represent ({@link #dataTypeForReturnType}/{@link #returnTypeForDataType} are the two directions of the
 * SAME mapping {@code staging.StagingModel#ebxType} already applies when building an ordinary field, kept here as its own small pair
 * rather than reused across packages - {@code staging.StagingModel.ebxType} is {@code private}, and the mapping is a few lines either way).
 * A function returning anything else (a {@code json_object}, a collection...) has no attribute shape to become - callers must refuse the
 * suggestion before ever reaching this class, never guess a fallback type. The modify trigger compares old and new values with one typed
 * helper per script type actually needed ({@link #scriptTypeOrNull}).
 */
public final class DerivationBodyBuilder
{
	private DerivationBodyBuilder()
	{
	}

	// ------------------------------------------------------------------------------------------------ return type

	/**
	 * The {@code StagingAttribute.dataType} a function returning {@code pEbxReturnType} (e.g. {@code "string"},
	 * {@code "decimal"}, {@code "datetime"}) would produce, or {@code null} when the return type has no scalar
	 * attribute shape (a collection, {@code json_object}, {@code rest_response}, an unrecognised/model type...).
	 * Case-insensitive, tolerant of surrounding whitespace.
	 */
	public static String dataTypeForReturnType(final String pEbxReturnType)
	{
		final String type = pEbxReturnType == null ? "" : pEbxReturnType.trim().toLowerCase(java.util.Locale.ROOT);
		if ("string".equals(type))
		{
			return "STRING";
		}
		if ("int".equals(type))
		{
			return "INTEGER";
		}
		if ("decimal".equals(type))
		{
			return "DECIMAL";
		}
		if ("boolean".equals(type))
		{
			return "BOOLEAN";
		}
		if ("date".equals(type))
		{
			return "DATE";
		}
		if ("datetime".equals(type))
		{
			return "TIMESTAMP";
		}
		return null;
	}

	/** The inverse of {@link #dataTypeForReturnType} - the {@code export function getValue(): &lt;type&gt;} return-type keyword for a {@code StagingAttribute.dataType}; {@code "string"} for blank/unrecognised, same default {@code staging.StagingModel.ebxType} uses. */
	public static String returnTypeForDataType(final String pDataType)
	{
		final String type = pDataType == null ? "" : pDataType.trim().toUpperCase(java.util.Locale.ROOT);
		if ("INTEGER".equals(type))
		{
			return "int";
		}
		if ("DECIMAL".equals(type))
		{
			return "decimal";
		}
		if ("BOOLEAN".equals(type))
		{
			return "boolean";
		}
		if ("DATE".equals(type))
		{
			return "date";
		}
		if ("TIMESTAMP".equals(type))
		{
			return "datetime";
		}
		return "string";
	}

	// ------------------------------------------------------------------------------------------------------ paths

	/** {@code /root/<Entity>/<Attribute>} - the derived attribute's OWN element ({@code CapabilitySuggestion.attribute}); what {@code CapabilitySuggestion.appliedElementPath} records ("this attribute's derivation is applied") and what a function-field declaration of an older apply sat on. */
	public static String elementPath(final String pEntityName, final String pAttributeName)
	{
		return "/root/" + pEntityName + "/" + pAttributeName;
	}

	/** {@code /root/<Entity>} - the TABLE element the triggers are declared on ({@code set_script_trigger}'s {@code element_path}, scope {@code table}). */
	public static String tableElementPath(final String pEntityName)
	{
		return "/root/" + pEntityName;
	}

	/** The first letter of {@code pAttributeName} upper-cased, the rest untouched ({@code phoneE164} gives {@code PhoneE164}). */
	private static String capitalised(final String pAttributeName)
	{
		return pAttributeName.isEmpty() ? pAttributeName : Character.toUpperCase(pAttributeName.charAt(0)) + pAttributeName.substring(1);
	}

	/**
	 * The name of the create or modify trigger of a derived attribute: {@code Derive<Attribute>OnCreate} / {@code Derive<Attribute>OnModify}
	 * ({@code DerivePhoneE164OnCreate} for {@code phoneE164}). Deterministic and stable: re-declaring the same name REPLACES that trigger in
	 * place (measured), another name would add a second trigger - and a script cannot be renamed.
	 */
	public static String triggerName(final String pAttributeName, final boolean pOnCreate)
	{
		return "Derive" + DerivationBodyBuilder.capitalised(pAttributeName) + (pOnCreate ? "OnCreate" : "OnModify");
	}

	/**
	 * The {@code scripting} idePath of a table trigger body: {@code data-model/<Model>/root/<Entity>/@trigger/<TriggerName>} (measured live
	 * 2026-10-04 on Vol100k_Staging: {@code get_script} answers {@code scriptTypeId: triggerBeforeCreate} / {@code triggerBeforeModify} there).
	 */
	public static String triggerIdePath(final String pModelName, final String pEntityName, final String pTriggerName)
	{
		return "data-model/" + pModelName + "/root/" + pEntityName + "/@trigger/" + pTriggerName;
	}

	// -------------------------------------------------------------------------------------------- deterministic body

	/** One parameter of the target function - the same shape {@link ConstraintBodyBuilder.Param} already carries (kept as its own tiny class here so this file has no compile-time dependency on {@link ConstraintBodyBuilder} beyond the binding/escaping helpers it explicitly reuses). */
	public static final class Param
	{
		public final String name;
		public final String type;

		public Param(final String pName, final String pType)
		{
			this.name = pName;
			this.type = pType;
		}
	}

	/**
	 * One bound parameter list, resolved once for both bodies: the call's arguments for {@code pRecordVar} ({@code _ebx.record} or {@code
	 * _ebx.nextRecord}), and the DISTINCT attribute-bound sources in parameter order.
	 */
	private static final class Resolved
	{
		final List<String> sources = new ArrayList<String>();
		final List<String> guarded = new ArrayList<String>();
		/** One entry per parameter, in order: an attribute name (read from the record) or an already rendered literal. */
		final List<String> arguments = new ArrayList<String>();
		final List<Boolean> isAttribute = new ArrayList<Boolean>();

		String call(final String pAlias, final String pFunctionName, final String pRecordVar)
		{
			final List<String> args = new ArrayList<String>();
			for (int i = 0; i < this.arguments.size(); i++)
			{
				args.add(this.isAttribute.get(i).booleanValue() ? pRecordVar + "." + this.arguments.get(i) : this.arguments.get(i));
			}
			return pAlias + "." + pFunctionName + "(" + DerivationBodyBuilder.join(args, ", ") + ")";
		}
	}

	private static Resolved resolve(final List<Param> pParameters, final Map<String, BindingEntry> pBinding, final Map<String, Boolean> pAttributeMandatory)
	{
		final Resolved out = new Resolved();
		final Set<String> seen = new LinkedHashSet<String>();
		for (final Param param : pParameters)
		{
			final BindingEntry entry = pBinding.get(param.name);
			if (entry == null || entry.kind == BindingEntry.Kind.TO_BE_PROVIDED)
			{
				throw new IllegalStateException("parameter '" + param.name + "' has no settled binding: cannot build a derivation body for it");
			}
			if (entry.kind == BindingEntry.Kind.ATTRIBUTE)
			{
				ConstraintBodyBuilder.requireAttributeName(entry.value);
				out.arguments.add(entry.value);
				out.isAttribute.add(Boolean.TRUE);
				if (seen.add(entry.value))
				{
					out.sources.add(entry.value);
					// the same "guard the ones not already guaranteed non-null" reasoning ConstraintBodyBuilder#buildTemplateBody applies: an
					// absent/unknown mandatory flag reads as "guard it", the safe default
					if (!Boolean.TRUE.equals(pAttributeMandatory.get(entry.value)))
					{
						out.guarded.add(entry.value);
					}
				}
			}
			else
			{
				out.arguments.add(ConstraintBodyBuilder.renderLiteral(param.type, entry.value));
				out.isAttribute.add(Boolean.FALSE);
			}
		}
		// one attribute input guaranteed non-null means the inputs can never ALL be null: no guard at all, the function decides (2026-10-12)
		if (out.guarded.size() < out.sources.size())
		{
			out.guarded.clear();
		}
		return out;
	}

	/**
	 * The EBX-Script keyword for a {@code StagingAttribute.dataType} when it is one the staging model builds a field of, else {@code null}.
	 * Blank reads as {@code string} (what {@code staging.StagingModel.ebxType} builds for it); a value that is neither blank nor one of the
	 * six known kinds has no typed comparison helper and is REFUSED by the callers rather than guessed (lot T1).
	 */
	public static String scriptTypeOrNull(final String pDataType)
	{
		final String type = pDataType == null ? "" : pDataType.trim().toUpperCase(java.util.Locale.ROOT);
		if (type.isEmpty() || "STRING".equals(type))
		{
			return "string";
		}
		if ("INTEGER".equals(type) || "DECIMAL".equals(type) || "BOOLEAN".equals(type) || "DATE".equals(type) || "TIMESTAMP".equals(type))
		{
			return DerivationBodyBuilder.returnTypeForDataType(type);
		}
		return null;
	}

	private static String guardCondition(final List<String> pGuarded, final String pRecordVar)
	{
		final List<String> terms = new ArrayList<String>();
		for (final String attribute : pGuarded)
		{
			terms.add("isNull(" + pRecordVar + "." + attribute + ")");
		}
		// "and", not "or": the derived value is null only when EVERY guarded input is empty (2026-10-12, see the class-level note on the null guard)
		return DerivationBodyBuilder.join(terms, " and ");
	}

	/**
	 * The {@code onBeforeCreate} body: {@code import <lib> as <alias>;}, then {@code derived := null} when a guarded source is empty, else
	 * {@code derived := <alias>.<function>(<args>)} - arguments read from {@code _ebx.record}. The null guard ({@code isNull(a) and isNull(b)})
	 * covers every DISTINCT ATTRIBUTE-bound parameter and holds only when EVERY one of them is empty: with one empty input among several the
	 * function is still called, because the library decides what a partly filled input gives (its own documented null-safety). With a single
	 * input the guard is {@code isNull(a)}, as before. When at least one input is {@link Boolean#TRUE}-mandatory the inputs can never all be
	 * null and NO guard is generated (the call is made directly), in the create and the modify body alike. A {@code
	 * LITERAL} binding entry is rendered like {@link ConstraintBodyBuilder} renders it. Measured live on Vol100k_Staging with exactly this
	 * shape (phone to E.164): create, modify, a hand-written value overwritten, null and a refused number all behaved.
	 *
	 * @param pParameters the target function's parameters, in declaration order (from {@code capability.ScriptInventory.FunctionInfo})
	 * @param pBinding every parameter's binding, parsed by {@link ConstraintBodyBuilder#parseBinding} - a missing entry, or one still {@link
	 *            BindingEntry.Kind#TO_BE_PROVIDED}, throws {@link IllegalStateException} (the caller must refuse the apply before ever reaching
	 *            this method)
	 * @param pAttributeMandatory {@code StagingAttribute.mandatory} per accepted attribute name; an absent entry reads as "not known to be mandatory" (guarded)
	 * @param pDerivedAttribute the stored attribute the trigger writes
	 */
	public static String buildCreateTriggerBody(final String pImportName, final String pAlias, final String pFunctionName, final List<Param> pParameters,
		final Map<String, BindingEntry> pBinding, final Map<String, Boolean> pAttributeMandatory, final String pDerivedAttribute)
	{
		DerivationBodyBuilder.requireNames(pImportName, pAlias, pFunctionName, pDerivedAttribute);
		final Resolved resolved = DerivationBodyBuilder.resolve(pParameters, pBinding, pAttributeMandatory);
		final String target = "_ebx.record." + pDerivedAttribute;
		final StringBuilder out = new StringBuilder();
		out.append("import ").append(pImportName).append(" as ").append(pAlias).append(";\n\n");
		out.append("export procedure onBeforeCreate()\n");
		out.append("begin\n");
		DerivationBodyBuilder.appendAssignment(out, resolved, pAlias, pFunctionName, "_ebx.record", target);
		out.append("end\n");
		return DerivationBodyBuilder.guarded(out.toString(), pImportName, pFunctionName, "onBeforeCreate");
	}

	/** Audit F2-01: refuses (IllegalArgumentException) a name that would not stay a name once written into the generated source. */
	private static void requireNames(final String pImportName, final String pAlias, final String pFunctionName, final String pDerivedAttribute)
	{
		final String importViolation = ScriptGuard.importNameViolation(pImportName);
		if (importViolation != null)
		{
			throw new IllegalArgumentException(importViolation);
		}
		ConstraintBodyBuilder.requireAttributeName(pAlias);
		ConstraintBodyBuilder.requireAttributeName(pFunctionName);
		ConstraintBodyBuilder.requireAttributeName(pDerivedAttribute);
	}

	/** Audit F2-01: the built trigger body, read as tokens like a language model's body is, before anything compiles or publishes it. */
	private static String guarded(final String pBody, final String pImportName, final String pFunctionName, final String pEntryPoint)
	{
		final String violation = ScriptGuard.firstTemplateViolation(pBody, pImportName, pFunctionName, pEntryPoint);
		if (violation != null)
		{
			throw new IllegalArgumentException("the generated trigger body is refused: " + violation);
		}
		return pBody;
	}

	/** The guarded assignment shared by both bodies, indented two spaces: {@code if <guard> then ... null ... else ... call ...} or the bare call. */
	private static void appendAssignment(final StringBuilder pOut, final Resolved pResolved, final String pAlias, final String pFunctionName,
		final String pRecordVar, final String pTarget)
	{
		final String call = pResolved.call(pAlias, pFunctionName, pRecordVar);
		if (pResolved.guarded.isEmpty())
		{
			pOut.append("  ").append(pTarget).append(" := ").append(call).append(";\n");
			return;
		}
		pOut.append("  if ").append(DerivationBodyBuilder.guardCondition(pResolved.guarded, pRecordVar)).append(" then\n");
		pOut.append("  begin\n");
		pOut.append("    ").append(pTarget).append(" := null;\n");
		pOut.append("  end\n");
		pOut.append("  else\n");
		pOut.append("  begin\n");
		pOut.append("    ").append(pTarget).append(" := ").append(call).append(";\n");
		pOut.append("  end\n");
	}

	/**
	 * The {@code onBeforeModify} body: same computation as the create body on {@code _ebx.nextRecord}, but run only when it must - ANY bound
	 * source attribute differs between {@code _ebx.record} (old) and {@code _ebx.nextRecord} (new), OR the derived attribute differs (someone
	 * wrote it: the computed value is put back), OR the new derived value is empty (never computed yet). Old and new values are compared by one
	 * typed helper per distinct script type needed, {@code differs_<type>} (null-safe: both empty is "equal", one empty is "different").
	 *
	 * @param pAttributeScriptTypes the EBX-Script type ({@link #scriptTypeOrNull}) of every bound source attribute, by name; a missing entry throws {@link IllegalStateException}
	 * @param pDerivedScriptType the EBX-Script type of the derived attribute
	 */
	public static String buildModifyTriggerBody(final String pImportName, final String pAlias, final String pFunctionName, final List<Param> pParameters,
		final Map<String, BindingEntry> pBinding, final Map<String, Boolean> pAttributeMandatory, final String pDerivedAttribute,
		final Map<String, String> pAttributeScriptTypes, final String pDerivedScriptType)
	{
		DerivationBodyBuilder.requireNames(pImportName, pAlias, pFunctionName, pDerivedAttribute);
		final Resolved resolved = DerivationBodyBuilder.resolve(pParameters, pBinding, pAttributeMandatory);
		final Set<String> helperTypes = new LinkedHashSet<String>();
		final List<String> terms = new ArrayList<String>();
		for (final String source : resolved.sources)
		{
			final String type = pAttributeScriptTypes == null ? null : pAttributeScriptTypes.get(source);
			if (type == null || type.trim().isEmpty())
			{
				throw new IllegalStateException("attribute '" + source + "' has no script type: cannot compare its old and new values");
			}
			helperTypes.add(type);
			terms.add("differs_" + type + "(_ebx.record." + source + ", _ebx.nextRecord." + source + ")");
		}
		if (pDerivedScriptType == null || pDerivedScriptType.trim().isEmpty())
		{
			throw new IllegalStateException("derived attribute '" + pDerivedAttribute + "' has no script type: cannot compare its old and new values");
		}
		helperTypes.add(pDerivedScriptType);
		terms.add("differs_" + pDerivedScriptType + "(_ebx.record." + pDerivedAttribute + ", _ebx.nextRecord." + pDerivedAttribute + ")");
		terms.add("isNull(_ebx.nextRecord." + pDerivedAttribute + ")");

		final StringBuilder out = new StringBuilder();
		out.append("import ").append(pImportName).append(" as ").append(pAlias).append(";\n\n");
		for (final String type : helperTypes)
		{
			out.append("function differs_").append(type).append("(a: ").append(type).append(", b: ").append(type).append("): boolean\n");
			out.append("begin\n");
			out.append("  if isNull(a) then return isNotNull(b);\n");
			out.append("  if isNull(b) then return true;\n");
			out.append("  return a <> b;\n");
			out.append("end\n\n");
		}
		out.append("export procedure onBeforeModify()\n");
		out.append("begin\n");
		out.append("  if ").append(DerivationBodyBuilder.join(terms, "\n    or ")).append(" then\n");
		out.append("  begin\n");
		final StringBuilder inner = new StringBuilder();
		DerivationBodyBuilder.appendAssignment(inner, resolved, pAlias, pFunctionName, "_ebx.nextRecord", "_ebx.nextRecord." + pDerivedAttribute);
		for (final String line : inner.toString().split("\n"))
		{
			out.append("  ").append(line).append("\n");
		}
		out.append("  end\n");
		out.append("end\n");
		return DerivationBodyBuilder.guarded(out.toString(), pImportName, pFunctionName, "onBeforeModify");
	}

	/**
	 * Plain-language, deterministic description of what an applied derivation does - the same reviewer-facing role {@link
	 * ConstraintBodyBuilder#buildTemplateExplanation} fills for a constraint, simplified. The value is STORED in the staging record (an ordinary
	 * field), written by two table triggers: when the record is created and whenever its source changes. Never by the transfer engine itself.
	 */
	public static String buildExplanation(final String pImportName, final String pFunctionName, final String pSourceLabel, final String pNewAttributeLabel)
	{
		final String source = pSourceLabel == null || pSourceLabel.trim().isEmpty() ? "its source attribute" : pSourceLabel.trim();
		final String target = pNewAttributeLabel == null || pNewAttributeLabel.trim().isEmpty() ? "this attribute" : pNewAttributeLabel.trim();
		return target + " is a stored value, computed from " + source + " by " + pImportName + "." + pFunctionName + ": EBX writes it when the staging record is created "
			+ "and again whenever " + source + " changes (two table triggers of the staging model, and of the master model when its table carries the attribute) - never the transfer engine itself.";
	}

	// ------------------------------------------------------------------------------------------- stored bodies

	/**
	 * The line that separates the two bodies in {@code CapabilitySuggestion.scriptBody} (lot T2, 2026-10-04). A body is generated code (no comment
	 * line), so this line cannot occur inside one; the reader splits on it instead of guessing from the header comments the writer puts before each body.
	 */
	public static final String STORED_BODY_SEPARATOR = "//=== mdm-factory: modify trigger body follows ===";

	/** The two bodies a stored {@code scriptBody} holds, as {@link #splitStoredBodies} read them back. */
	public static final class StoredBodies
	{
		public final String createSource;
		public final String modifySource;

		StoredBodies(final String pCreateSource, final String pModifySource)
		{
			this.createSource = pCreateSource;
			this.modifySource = pModifySource;
		}
	}

	/**
	 * The text stored in {@code scriptBody} of an applied derivation: a header comment and the create body, {@link #STORED_BODY_SEPARATOR}, a header
	 * comment and the modify body. {@link #splitStoredBodies} reads it back (and still reads the earlier form, which had no separator line).
	 */
	public static String joinStoredBodies(final String pCreateName, final String pCreateIdePath, final String pCreateSource, final String pModifyName,
		final String pModifyIdePath, final String pModifySource)
	{
		return "// " + pCreateName + " (" + pCreateIdePath + ")\n" + pCreateSource + "\n" + DerivationBodyBuilder.STORED_BODY_SEPARATOR + "\n// " + pModifyName + " ("
			+ pModifyIdePath + ")\n" + pModifySource;
	}

	/**
	 * The two bodies of a stored {@code scriptBody}, or {@code null} when they cannot be told apart reliably (nothing stored, or neither the separator
	 * nor the older form's modify header found, or one body empty): a caller that wants to RESTORE them (a failed re-apply) must then say that it
	 * could not, never restore a guess. The older form ({@code // <create> (path)\n<create>\n// <modify> (path)\n<modify>}) is found by the modify
	 * trigger's own header line, whose name the caller knows.
	 */
	public static StoredBodies splitStoredBodies(final String pStored, final String pCreateName, final String pModifyName)
	{
		if (pStored == null || pStored.trim().isEmpty())
		{
			return null;
		}
		final String separator = "\n" + DerivationBodyBuilder.STORED_BODY_SEPARATOR + "\n";
		final int at = pStored.indexOf(separator);
		final String createPart;
		final String modifyPart;
		if (at >= 0)
		{
			createPart = pStored.substring(0, at);
			modifyPart = pStored.substring(at + separator.length());
		}
		else
		{
			final String legacy = "\n// " + pModifyName + " (";
			final int legacyAt = pStored.indexOf(legacy);
			if (legacyAt < 0)
			{
				return null;
			}
			createPart = pStored.substring(0, legacyAt);
			modifyPart = pStored.substring(legacyAt + 1);
		}
		final String create = DerivationBodyBuilder.afterHeader(createPart, pCreateName);
		final String modify = DerivationBodyBuilder.afterHeader(modifyPart, pModifyName);
		if (create == null || modify == null || create.trim().isEmpty() || modify.trim().isEmpty())
		{
			return null;
		}
		return new StoredBodies(create, modify);
	}

	/** {@code pPart} without its first line, which must be the header comment {@code // <name> (...)}; {@code null} when it is not. */
	private static String afterHeader(final String pPart, final String pName)
	{
		final String header = "// " + pName + " (";
		if (!pPart.startsWith(header))
		{
			return null;
		}
		final int lineEnd = pPart.indexOf('\n');
		return lineEnd < 0 ? null : pPart.substring(lineEnd + 1);
	}

	// ------------------------------------------------------------------------------------------------------- misc

	private static String join(final List<String> pValues, final String pSeparator)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pValues.size(); i++)
		{
			if (i > 0)
			{
				out.append(pSeparator);
			}
			out.append(pValues.get(i));
		}
		return out.toString();
	}

	/** Convenience re-export so a caller needs only ONE import for both parsing and building - see class Javadoc on why binding parsing itself is never duplicated. */
	public static Map<String, BindingEntry> parseBinding(final String pBindingJson)
	{
		return ConstraintBodyBuilder.parseBinding(pBindingJson);
	}

	/** Every distinct real attribute this binding references (never a literal/toBeProvided value) - a thin re-export of {@link ConstraintBodyBuilder#attributeBoundNames}, same reasoning as {@link #parseBinding}. */
	public static List<String> attributeBoundNames(final Map<String, BindingEntry> pBinding)
	{
		return ConstraintBodyBuilder.attributeBoundNames(pBinding);
	}

	/** Every {@link BindingEntry.Kind#TO_BE_PROVIDED} parameter name still outstanding - a thin re-export of {@link ConstraintBodyBuilder#toBeProvidedParameters}. */
	public static List<String> toBeProvidedParameters(final Map<String, BindingEntry> pBinding)
	{
		return ConstraintBodyBuilder.toBeProvidedParameters(pBinding);
	}

	/** The target function's parameters as {@link Param}s - a small convenience so a caller does not have to build this itself from a {@code capability.ScriptInventory.FunctionInfo}. */
	public static List<Param> paramsOf(final List<ScriptInventory.Parameter> pParameters)
	{
		final List<Param> out = new ArrayList<Param>();
		if (pParameters != null)
		{
			for (final ScriptInventory.Parameter p : pParameters)
			{
				out.add(new Param(p.name, p.type));
			}
		}
		return out;
	}

	/** Defensive default map ({@code Collections.emptyMap} substitute kept local to avoid one more import) used by callers that have no mandatory-flag data at hand. */
	public static Map<String, Boolean> noMandatoryFlags()
	{
		return new LinkedHashMap<String, Boolean>();
	}
}
