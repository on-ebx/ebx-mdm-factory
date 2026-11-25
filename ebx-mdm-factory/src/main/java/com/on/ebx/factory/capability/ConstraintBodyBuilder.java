package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pure logic of the "apply an accepted SCRIPT_CONSTRAINT" lot: parses {@code CapabilitySuggestion.binding}
 * into typed entries, decides node-vs-table scope (CLAUDE.md &sect;5's own rule -
 * {@code setDependencyToLocalNode()} is honest only when the binding names exactly one attribute), derives a
 * deterministic, stable script name and idePath, builds the deterministic EBX Script body for a
 * boolean-returning function, builds the deterministic plain-language explanation for that body, and
 * structurally checks a language-model-submitted body for anything else. No EBX class anywhere in this file
 * (the same convention {@code capability.CapabilityLlmPass} follows) - {@code ebx.CapabilityApplier} is the
 * only caller, and it alone touches MCP/EBX. See {@code docs/CAPABILITY-APPLY.md}.
 */
public final class ConstraintBodyBuilder
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ConstraintBodyBuilder()
	{
	}

	// --------------------------------------------------------------------------------------------------- binding

	/** One resolved entry of {@code binding} - see {@code capability.CapabilityLlmPass}'s own three shapes: a real attribute, a documented literal, or a declaration that a person must still provide it. */
	public static final class BindingEntry
	{
		public enum Kind
		{
			ATTRIBUTE, LITERAL, TO_BE_PROVIDED
		}

		public final String parameter;
		public final Kind kind;
		/** The attribute name, the literal value, or (TO_BE_PROVIDED) the reason - never null. */
		public final String value;

		public BindingEntry(final String pParameter, final Kind pKind, final String pValue)
		{
			this.parameter = pParameter;
			this.kind = pKind;
			this.value = pValue == null ? "" : pValue;
		}
	}

	/**
	 * {@code {"a":"attrName","b":{"literal":"x"},"c":{"toBeProvided":"why"}}} -&gt; one {@link BindingEntry}
	 * per key, in encounter order. An unparsable/blank binding, or a value shaped like neither a plain string
	 * nor a recognised single-key object, is simply skipped (never throws) - a caller decides what to do with
	 * a parameter that ends up missing from the returned map (the same "arity" guard {@code
	 * CapabilityLlmPass} already ran at suggestion time, re-checked here defensively rather than trusted
	 * blindly, since the row could in principle have been written by an earlier schema revision).
	 */
	public static Map<String, BindingEntry> parseBinding(final String pBindingJson)
	{
		final Map<String, BindingEntry> out = new LinkedHashMap<String, BindingEntry>();
		if (pBindingJson == null || pBindingJson.trim().isEmpty())
		{
			return out;
		}
		final JsonNode root;
		try
		{
			root = ConstraintBodyBuilder.MAPPER.readTree(pBindingJson);
		}
		catch (final Exception ex)
		{
			return out;
		}
		if (root == null || !root.isObject())
		{
			return out;
		}
		final java.util.Iterator<String> fieldNames = root.fieldNames();
		while (fieldNames.hasNext())
		{
			final String parameter = fieldNames.next();
			final JsonNode entry = root.path(parameter);
			if (entry.isTextual())
			{
				out.put(parameter, new BindingEntry(parameter, BindingEntry.Kind.ATTRIBUTE, entry.asText()));
			}
			else if (entry.isObject() && entry.path("literal").isTextual())
			{
				out.put(parameter, new BindingEntry(parameter, BindingEntry.Kind.LITERAL, entry.path("literal").asText()));
			}
			else if (entry.isObject() && entry.path("toBeProvided").isTextual())
			{
				out.put(parameter, new BindingEntry(parameter, BindingEntry.Kind.TO_BE_PROVIDED, entry.path("toBeProvided").asText()));
			}
			// anything else: skipped, see class Javadoc
		}
		return out;
	}

	/** Every attribute this binding references (ATTRIBUTE-kind entries only), distinct, in first-encountered order - never LITERAL/TO_BE_PROVIDED values, which name no real attribute. */
	public static List<String> attributeBoundNames(final Map<String, BindingEntry> pBinding)
	{
		final List<String> out = new ArrayList<String>();
		final Set<String> seen = new LinkedHashSet<String>();
		for (final BindingEntry entry : pBinding.values())
		{
			if (entry.kind == BindingEntry.Kind.ATTRIBUTE && seen.add(entry.value))
			{
				out.add(entry.value);
			}
		}
		return out;
	}

	/** The parameter name(s), if any, whose binding is still {@code TO_BE_PROVIDED} - applying while any of these remain is refused (the task's own rule). */
	public static List<String> toBeProvidedParameters(final Map<String, BindingEntry> pBinding)
	{
		final List<String> out = new ArrayList<String>();
		for (final BindingEntry entry : pBinding.values())
		{
			if (entry.kind == BindingEntry.Kind.TO_BE_PROVIDED)
			{
				out.add(entry.parameter);
			}
		}
		return out;
	}

	// ----------------------------------------------------------------------------------------------------- scope

	public enum Scope
	{
		NODE, TABLE
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21): a coordinator design rule proved WRONG on the real
	 * instance - "several bound attributes ⇒ TABLE constraint with checkRecord" reads as "runs on every
	 * record", but the instance's own {@code get_syntax_guide(constraintOnTable)} states verbatim: a table
	 * constraint's reported message is attached to the TABLE's validation report only when raised from
	 * {@code checkTable}; {@code checkRecord} only fires when ONE record is validated (a form) - so a TABLE
	 * constraint with all its logic in {@code checkRecord} and an empty {@code checkTable} (exactly what
	 * this class used to generate) validates NOTHING on a whole-dataset pass. The real symptom: 1 invalid
	 * IBAN out of 47 staging rows, visible only when the product owner opened THAT record, and
	 * {@code recordsReported: 0} from the factory's own measurement. The right tool, proven live (declared,
	 * compiled, published, then deleted on a scratch model): a NODE constraint on the anchor field whose
	 * {@code setup()} declares an EXPLICIT dependency on every OTHER bound field too, via
	 * {@code core.validation.addDependencyToModify(path)} (absolute path, {@code /root/<Table>/<field>} -
	 * API help, verbatim: "declare that the validation result of the current constraint depends on
	 * modifications of the field specified by its path ... must be called from the procedure setup").
	 * <p>
	 * A {@code SCRIPT_CONSTRAINT} is now ALWAYS a NODE constraint, on its ANCHOR attribute - the
	 * suggestion's own top-level {@code attribute} when set, else the first attribute-bound parameter
	 * ({@code pAnchorAttribute} below - the caller passes the suggestion's own field, in EITHER role). One
	 * distinct bound attribute (the anchor alone, or the anchor duplicated as the only binding entry) still
	 * means {@code setup()} = {@code v.setDependencyToLocalNode();} (proven, unchanged - see
	 * {@link #buildSetupBlock}); several means one {@code addDependencyToModify} call per DISTINCT bound
	 * attribute, anchor included, and no {@code setDependencyToLocalNode()} call at all.
	 * <p>
	 * {@link Scope#TABLE} itself is NOT removed from this enum - {@code ebx.CapabilityApplier.remove}/
	 * {@code measure} still read a PRE-EXISTING row's own stored {@code constraintScope} to correctly
	 * remove/measure a constraint that is STILL live as a table constraint on the real instance until it is
	 * migrated (see {@code ebx.CapabilityApplier}'s own migration logic) - only THIS method (what a NEW or
	 * RE-authored apply produces) never returns it any more.
	 */
	public static final class ScopeDecision
	{
		public final Scope scope;
		/** Non-null only for {@link Scope#NODE}: the one field the constraint is declared on. */
		public final String attribute;
		/** Every distinct attribute the binding itself references (may be empty even for a NODE decision reached through {@code pAnchorAttribute} alone). */
		public final List<String> boundAttributes;

		/** Public (coordinator review, apply lot 2026-09-21): {@code ebx.CapabilityApplier.measure} rebuilds a {@code ScopeDecision} directly from a stored row's own {@code constraintScope}/{@code appliedElementPath}, without re-running {@link #chooseScope}. */
		public ScopeDecision(final Scope pScope, final String pAttribute, final List<String> pBoundAttributes)
		{
			this.scope = pScope;
			this.attribute = pAttribute;
			this.boundAttributes = pBoundAttributes;
		}
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21): ALWAYS {@link Scope#NODE} now - see this class's own
	 * Javadoc above for why the earlier "several bound attributes ⇒ TABLE" rule was wrong. The anchor is
	 * {@code pAnchorAttribute} (the suggestion's own top-level {@code attribute}) when given, otherwise the
	 * FIRST attribute-bound parameter (in the binding's own encounter order) - a suggestion with no
	 * attribute at all and no attribute-bound parameter either has no possible anchor, which the CALLER
	 * (({@code ebx.CapabilityApplier.prepare}) refuses outright before ever reaching here, the same
	 * precondition style already used for every other apply-time refusal; this method itself stays total
	 * (returns a {@code ScopeDecision} with a null {@code attribute} rather than throwing) so it remains
	 * trivially testable at every input shape.
	 */
	public static ScopeDecision chooseScope(final Map<String, BindingEntry> pBinding, final String pAnchorAttribute)
	{
		final List<String> bound = ConstraintBodyBuilder.attributeBoundNames(pBinding);
		final String anchor = pAnchorAttribute != null && !pAnchorAttribute.trim().isEmpty() ? pAnchorAttribute.trim() : bound.isEmpty() ? null : bound.get(0);
		return new ScopeDecision(Scope.NODE, anchor, bound);
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21): the anchor attribute PLUS every distinct bound attribute,
	 * anchor included, in first-encountered order (anchor first) - exactly the set {@link #buildSetupBlock}
	 * turns into dependency declarations. A NODE {@link ScopeDecision} with no anchor at all (the
	 * pathological case {@link #chooseScope}'s own Javadoc describes) returns just the bound attributes.
	 */
	public static List<String> dependencyAttributes(final ScopeDecision pScope)
	{
		final List<String> out = new ArrayList<String>();
		final Set<String> seen = new LinkedHashSet<String>();
		if (pScope.attribute != null && !pScope.attribute.trim().isEmpty() && seen.add(pScope.attribute))
		{
			out.add(pScope.attribute);
		}
		for (final String attribute : pScope.boundAttributes)
		{
			if (seen.add(attribute))
			{
				out.add(attribute);
			}
		}
		return out;
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21): the deterministic {@code setup()} procedure, shared by
	 * BOTH authoring paths - the deterministic TEMPLATE body ({@link #buildTemplateBody}) embeds it directly;
	 * the LLM path has {@code ebx.CapabilityApplier}/{@code capability.ConstraintBodySubmission} OVERWRITE
	 * whatever {@code setup()} the model wrote with this SAME text before ever compiling the submission (see
	 * {@link #replaceSetupBlock}) - "the factory writes {@code setup()} itself in both paths" (the
	 * coordinator's own instruction; the choice and its justification are documented on
	 * {@code capability.ConstraintBodySubmission} itself). {@link #dependencyAttributes} at size &le; 1 (the
	 * anchor alone, or duplicated as the only binding entry) ⇒ {@code v.setDependencyToLocalNode();} - proven,
	 * unchanged; otherwise one {@code v.addDependencyToModify('/root/<Table>/<attribute>');} per distinct
	 * dependency attribute, anchor included, absolute paths (API help, verbatim: "Paths are ABSOLUTE").
	 * <p>
	 * <b>fix/setup-alias (2026-09-21)</b>: {@code pValidationAlias} is the alias to call
	 * {@code setDependencyToLocalNode()}/{@code addDependencyToModify(...)} THROUGH - never hardcoded {@code
	 * "v"} any more. The deterministic TEMPLATE path ({@link #buildTemplateBody}) always passes the literal
	 * {@code "v"} (it emits {@code uses core.validation as v;} itself, unconditionally - unchanged by this
	 * fix); the LLM path ({@code capability.ConstraintBodySubmission#submit}) resolves the real alias from the
	 * MODEL's own submitted source first ({@link #resolveValidationAlias}) and passes THAT - a real captured
	 * body had no {@code core.validation} import line at all (its own {@code setup()} was empty, so it never
	 * needed one), and overwriting {@code setup()} with a hardcoded {@code v.} call against a source that
	 * never declares {@code uses core.validation as v;} would fail to compile on a line the model never wrote.
	 */
	public static String buildSetupBlock(final ScopeDecision pScope, final String pEntityName, final String pValidationAlias)
	{
		final List<String> dependencies = ConstraintBodyBuilder.dependencyAttributes(pScope);
		final StringBuilder out = new StringBuilder();
		out.append("export procedure setup()\n");
		out.append("begin\n");
		if (dependencies.size() <= 1)
		{
			out.append("  ").append(pValidationAlias).append(".setDependencyToLocalNode();\n");
		}
		else
		{
			for (final String attribute : dependencies)
			{
				out.append("  ").append(pValidationAlias).append(".addDependencyToModify('/root/").append(pEntityName).append('/').append(attribute)
					.append("');\n");
			}
		}
		out.append("end\n");
		return out.toString();
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21): replaces the FIRST {@code export procedure setup() ...
	 * end} block of {@code pSource} with {@code pNewSetupBlock} verbatim, wherever it sits in the source (not
	 * assumed to be last - {@link #checkSubmittedSource} only checks PRESENCE, never order). A small
	 * begin/end depth counter, not a real parser: starts at the first whole-word {@code begin} after
	 * {@code export procedure setup(}, and returns the text up to and including the MATCHING whole-word
	 * {@code end} (nested {@code begin}/{@code end} pairs inside setup - unlikely, but possible if a model
	 * writes an inner block - are tracked, never mistaken for the outer one). Returns {@code pSource}
	 * UNCHANGED (never throws) if {@code setup(} is missing or its block is malformed - {@link
	 * #checkSubmittedSource} already guarantees the entry point is present before this ever runs, so this is
	 * a defensive no-op path, not the expected one.
	 */
	public static String replaceSetupBlock(final String pSource, final String pNewSetupBlock)
	{
		final int setupStart = pSource.indexOf("export procedure setup(");
		if (setupStart < 0)
		{
			return pSource;
		}
		final int beginIndex = pSource.indexOf("begin", setupStart);
		if (beginIndex < 0)
		{
			return pSource;
		}
		int depth = 0;
		int i = beginIndex;
		int endOfBlock = -1;
		final int n = pSource.length();
		while (i < n)
		{
			if (ConstraintBodyBuilder.isWholeWordAt(pSource, i, "begin"))
			{
				depth++;
				i += 5;
				continue;
			}
			if (ConstraintBodyBuilder.isWholeWordAt(pSource, i, "end"))
			{
				depth--;
				i += 3;
				if (depth == 0)
				{
					endOfBlock = i;
					break;
				}
				continue;
			}
			i++;
		}
		if (endOfBlock < 0)
		{
			return pSource;
		}
		return pSource.substring(0, setupStart) + pNewSetupBlock.trim() + pSource.substring(endOfBlock);
	}

	private static boolean isWholeWordAt(final String pText, final int pStart, final String pWord)
	{
		if (!pText.startsWith(pWord, pStart))
		{
			return false;
		}
		final boolean beforeOk = pStart == 0 || !Character.isLetterOrDigit(pText.charAt(pStart - 1));
		final int after = pStart + pWord.length();
		final boolean afterOk = after >= pText.length() || !Character.isLetterOrDigit(pText.charAt(after));
		return beforeOk && afterOk;
	}

	// --------------------------------------------------------------------------------------------------- naming

	private static final Pattern NOT_IDENTIFIER_CHAR = Pattern.compile("[^A-Za-z0-9_]");
	/** Deterministic naming and idePaths must fit inside {@code CapabilitySuggestion.scriptName}'s own {@code maxLength=64} facet - see {@code tools/build_xsd.py}. */
	public static final int SCRIPT_NAME_MAX_LENGTH = 64;

	/**
	 * A deterministic, stable, {@code [A-Za-z][A-Za-z0-9_]*} name, &le; {@link #SCRIPT_NAME_MAX_LENGTH}
	 * characters: {@code <Entity>_<Function>} for a TABLE constraint, {@code <Entity>_<Function>_<Attribute>}
	 * for a NODE one - the same {entity, function, scope, attribute} tuple always yields the same name, so a
	 * re-apply (or a re-suggestion of the identical target, accepted again after an earlier reject) replaces
	 * the same declared constraint in place rather than accumulating dead ones (CLAUDE.md &sect;5, fact 1:
	 * "Same name = replaced in place").
	 */
	public static String deriveScriptName(final String pEntityName, final String pFunctionName, final ScopeDecision pScope)
	{
		final StringBuilder out = new StringBuilder();
		out.append(ConstraintBodyBuilder.sanitizeIdentifierPart(pEntityName));
		out.append('_').append(ConstraintBodyBuilder.sanitizeIdentifierPart(pFunctionName));
		if (pScope.scope == Scope.NODE)
		{
			out.append('_').append(ConstraintBodyBuilder.sanitizeIdentifierPart(pScope.attribute));
		}
		String name = out.toString();
		if (name.length() > ConstraintBodyBuilder.SCRIPT_NAME_MAX_LENGTH)
		{
			name = name.substring(0, ConstraintBodyBuilder.SCRIPT_NAME_MAX_LENGTH);
		}
		// a truncation could in principle leave a trailing underscore or, pathologically, an empty/digit-first
		// string if every source token were itself somehow non-alphabetic - defensively guarded even though
		// entity/function names are already [A-Za-z][A-Za-z0-9_]* by the model's own naming rules
		while (name.endsWith("_"))
		{
			name = name.substring(0, name.length() - 1);
		}
		if (name.isEmpty() || !Character.isLetter(name.charAt(0)))
		{
			name = "C" + name;
		}
		return name;
	}

	private static String sanitizeIdentifierPart(final String pValue)
	{
		final String cleaned = ConstraintBodyBuilder.NOT_IDENTIFIER_CHAR.matcher(pValue == null ? "" : pValue).replaceAll("");
		return cleaned.isEmpty() ? "X" : cleaned;
	}

	/** {@code /root/<Entity>} (TABLE) or {@code /root/<Entity>/<Attribute>} (NODE) - the element {@code set_script_constraint}/{@code unset_element_property} act on. */
	public static String elementPath(final String pEntityName, final ScopeDecision pScope)
	{
		return pScope.scope == Scope.NODE ? "/root/" + pEntityName + "/" + pScope.attribute : "/root/" + pEntityName;
	}

	/**
	 * {@code data-model/<Model>/root/<Entity>/<Attribute>/@nodeConstraint/<Name>} or {@code
	 * data-model/<Model>/root/<Entity>/@constraint/<Name>} - the deducible idePath on the {@code scripting}
	 * MCP server (CLAUDE.md &sect;5, fact 2).
	 */
	public static String scriptIdePath(final String pModelName, final String pEntityName, final ScopeDecision pScope, final String pScriptName)
	{
		return pScope.scope == Scope.NODE
			? "data-model/" + pModelName + "/root/" + pEntityName + "/" + pScope.attribute + "/@nodeConstraint/" + pScriptName
			: "data-model/" + pModelName + "/root/" + pEntityName + "/@constraint/" + pScriptName;
	}

	// -------------------------------------------------------------------------------------------- deterministic body

	/** One parameter of the target function, name and declared EBX Script type (possibly null/unknown) - the same shape {@code capability.ScriptInventory.Parameter} already carries. */
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
	 * The deterministic template body for a {@code boolean}-returning function: a null guard on every bound
	 * attribute the caller says is not mandatory, then {@code if not <alias>.<function>(<args>) then
	 * msg.add(Warning|Error)(...)}, {@code setup()} built by {@link #buildSetupBlock} (fix/node-scope-
	 * multi-attribute, 2026-09-21 - {@code setDependencyToLocalNode()} for one dependency attribute, one
	 * {@code addDependencyToModify} call per attribute otherwise - see this class's own Javadoc for why a
	 * TABLE constraint with {@code checkRecord} is never generated any more: proven wrong on the real
	 * instance, {@code checkRecord} only fires when a single record is validated, never on a whole-dataset
	 * pass). {@code pScope} must be {@link Scope#NODE} - a {@link Scope#TABLE} decision can no longer reach
	 * this method through the normal apply path ({@link #chooseScope} never returns one); it throws
	 * defensively if one slips through anyway, the same discipline already used for an unsettled binding.
	 * <p>
	 * A parameter whose binding is {@link BindingEntry.Kind#TO_BE_PROVIDED} must never reach this method -
	 * the caller refuses apply before calling it (see {@code ebx.CapabilityApplier}); this method throws
	 * defensively if one slips through anyway, rather than silently emitting a broken script.
	 * <p>
	 * <b>apply-ux fix (2026-09-21):</b> the reported message is no longer computed here - "the meaning must
	 * come from the LLM, not from Java" (a real deployed run's own message, {@code "'phone' failed
	 * phone.isValid."}, was developer jargon a business user should never see). {@code pMessage} is the FINAL
	 * plain-language text to report (the suggestion's own stored {@code userMessage}, or {@link
	 * #buildFallbackMessage} for a row suggested before that field existed) - this method only escapes it
	 * ({@link #escapeEbxString}) into the literal argument of {@code msg.addWarning}/{@code addError}.
	 *
	 * @param pAttributeMandatory {@code StagingAttribute.mandatory} per accepted attribute name; an absent
	 *        entry is treated as "not known to be mandatory" (guarded), the safe default.
	 * @param pMessage the plain-language sentence to report - never null/blank (the caller's responsibility).
	 */
	public static String buildTemplateBody(final String pEntityName, final ScopeDecision pScope, final String pImportName, final String pAlias,
		final String pFunctionName, final List<Param> pParameters, final Map<String, BindingEntry> pBinding, final Map<String, Boolean> pAttributeMandatory,
		final String pSeverity, final String pMessage)
	{
		if (pScope.scope != Scope.NODE)
		{
			throw new IllegalStateException("buildTemplateBody was called with a TABLE ScopeDecision - chooseScope never produces one any more (fix/node-scope-"
				+ "multi-attribute): this indicates a caller reconstructed a ScopeDecision by hand instead of going through chooseScope");
		}
		// audit F2-01: names that reach the generated source come from a row a steward can write - checked as names before they are concatenated
		ConstraintBodyBuilder.requireTemplateNames(pEntityName, pImportName, pAlias, pFunctionName, pScope.attribute);
		final List<String> args = new ArrayList<String>();
		final List<String> guardAttributes = new ArrayList<String>();
		final Set<String> guardSeen = new LinkedHashSet<String>();
		for (final Param param : pParameters)
		{
			final BindingEntry entry = pBinding.get(param.name);
			if (entry == null || entry.kind == BindingEntry.Kind.TO_BE_PROVIDED)
			{
				throw new IllegalStateException("parameter '" + param.name + "' has no settled binding: cannot build a template body for it");
			}
			if (entry.kind == BindingEntry.Kind.ATTRIBUTE)
			{
				ConstraintBodyBuilder.requireAttributeName(entry.value);
				args.add("record." + entry.value);
				if (!Boolean.TRUE.equals(pAttributeMandatory.get(entry.value)) && guardSeen.add(entry.value))
				{
					guardAttributes.add(entry.value);
				}
			}
			else
			{
				args.add(ConstraintBodyBuilder.renderLiteral(param.type, entry.value));
			}
		}

		final boolean error = "ERROR".equalsIgnoreCase(pSeverity);
		if (pMessage == null || pMessage.trim().isEmpty())
		{
			throw new IllegalStateException("no message to report: the caller must supply the stored userMessage or a fallback (ConstraintBodyBuilder.buildFallbackMessage)");
		}
		final String message = pMessage.trim();
		final String call = pAlias + "." + pFunctionName + "(" + ConstraintBodyBuilder.join(args, ", ") + ")";

		final StringBuilder out = new StringBuilder();
		out.append("uses core.message as msg;\n");
		out.append("uses core.validation as v;\n");
		out.append("import ").append(pImportName).append(" as ").append(pAlias).append(";\n\n");
		out.append("export procedure check(record : .").append(pEntityName).append(")\n");
		out.append("begin\n");
		for (final String attribute : guardAttributes)
		{
			out.append("  if isNull(record.").append(attribute).append(") then return;\n");
		}
		out.append("  if not ").append(call).append(" then\n");
		out.append("  begin\n");
		out.append("    msg.").append(error ? "addError" : "addWarning").append("('").append(ConstraintBodyBuilder.escapeEbxString(message)).append("');\n");
		out.append("  end\n");
		out.append("end\n\n");
		// fix/setup-alias (2026-09-21): the template body above ALWAYS emits "uses core.validation as v;"
		// itself, unconditionally - literal "v" is correct here, never resolveValidationAlias (that method
		// exists only for the LLM path, whose own submitted source may not declare the alias at all).
		out.append(ConstraintBodyBuilder.buildSetupBlock(pScope, pEntityName, "v"));
		// audit F2-01: the template body is read as tokens too, like the language model's, before anything compiles or publishes it
		final String violation = ScriptGuard.firstTemplateViolation(out.toString(), pImportName, pFunctionName, "check", "setup");
		if (violation != null)
		{
			throw new IllegalArgumentException("the generated constraint body is refused: " + violation);
		}
		return out.toString();
	}

	/** Audit F2-01: refuses (IllegalArgumentException) a name that would not stay a name once written into the generated source. */
	static void requireTemplateNames(final String pEntityName, final String pImportName, final String pAlias, final String pFunctionName, final String pAnchorAttribute)
	{
		final String importViolation = ScriptGuard.importNameViolation(pImportName);
		if (importViolation != null)
		{
			throw new IllegalArgumentException(importViolation);
		}
		ConstraintBodyBuilder.requireAttributeName(pEntityName);
		ConstraintBodyBuilder.requireAttributeName(pAlias);
		ConstraintBodyBuilder.requireAttributeName(pFunctionName);
		if (pAnchorAttribute != null)
		{
			ConstraintBodyBuilder.requireAttributeName(pAnchorAttribute);
		}
	}

	/** Audit F2-01: refuses (IllegalArgumentException) an attribute (or entity, alias, function) name that is not a plain identifier. */
	public static void requireAttributeName(final String pName)
	{
		final String violation = ScriptGuard.attributeNameViolation(pName);
		if (violation != null)
		{
			throw new IllegalArgumentException(violation);
		}
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21), item 4: the LEGACY fallback for a {@code
	 * CapabilitySuggestion} row that has no stored {@code userMessage} (suggested before that field
	 * existed) - now used ONLY by the deterministic TEMPLATE path (the LLM path has the body-writing model
	 * write its own business-readable message instead, see {@code capability.ConstraintBodySubmission}'s
	 * own Javadoc - it has the function's documentation in front of it and can actually read it). A real
	 * live run showed the EARLIER version of this fallback (attribute label + the function's own doc
	 * SENTENCE appended) is itself unusable to a business reviewer: for a function whose doc reads
	 * "Validates an IBAN and compares its country with the one of the postal address. Never raises: inspect
	 * 'valid' first, then 'status'.", echoing that sentence verbatim is exactly the kind of developer/API
	 * text this whole fallback exists to avoid. Simplified to {@code "<Attribute label>: value not
	 * accepted."} and NOTHING else - {@code pSubjectLabel} is {@code StagingAttribute.label} of the
	 * constraint's own (always NODE, fix/node-scope-multi-attribute) anchor attribute, or {@code
	 * pAttributeName} humanised ({@link #humanize}) when there is no stored label.
	 */
	public static String buildFallbackMessage(final String pSubjectLabel, final String pAttributeName)
	{
		final String subject = pSubjectLabel != null && !pSubjectLabel.trim().isEmpty() ? pSubjectLabel.trim() : ConstraintBodyBuilder.humanize(pAttributeName);
		return subject + ": value not accepted.";
	}

	/** {@code postalCode}/{@code legal_form} -&gt; {@code "Postal code"}/{@code "Legal form"} - splits camelCase and underscores into words, lower-cases, capitalises the first letter only. Never used alone as a fallback message (see {@link #buildFallbackMessage}'s own Javadoc) - only ever combined with the function's own doc sentence, or with a fixed "not accepted" when there is none. */
	static String humanize(final String pIdentifier)
	{
		if (pIdentifier == null || pIdentifier.trim().isEmpty())
		{
			return "This field";
		}
		final String spaced = pIdentifier.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').trim().toLowerCase(java.util.Locale.ROOT);
		return spaced.isEmpty() ? "This field" : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
	}

	/** A literal argument as an EBX Script expression: a bare number/boolean when the parameter's declared type says so and the value parses as one, a backslash-escaped string literal otherwise (the safe default). */
	static String renderLiteral(final String pParamType, final String pValue)
	{
		if ("decimal".equals(pParamType))
		{
			try
			{
				Double.parseDouble(pValue.trim());
				return pValue.trim();
			}
			catch (final NumberFormatException ex)
			{
				// not actually a number: fall through to a quoted string literal
			}
		}
		if ("boolean".equals(pParamType) && ("true".equalsIgnoreCase(pValue.trim()) || "false".equalsIgnoreCase(pValue.trim())))
		{
			return pValue.trim().toLowerCase(java.util.Locale.ROOT);
		}
		return "'" + ConstraintBodyBuilder.escapeEbxString(pValue) + "'";
	}

	/** Backslash-style escaping (CLAUDE.md &sect;2: {@code 'it\'s'}, {@code '\\'} for a backslash) - never the doubled-quote convention, which is wrong for a string literal in code. Public (apply-ux fix, 2026-09-21): {@code ebx.CapabilityApplier} reuses this exact escaping to show the LLM body-authoring prompt the message already quoted as a literal. */
	public static String escapeEbxString(final String pValue)
	{
		if (pValue == null)
		{
			return "";
		}
		return pValue.replace("\\", "\\\\").replace("'", "\\'");
	}

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

	// --------------------------------------------------------------------------------------------- explanation

	/** At most this many characters of a function's own documentation are echoed into the deterministic explanation - a reviewer-facing sentence, not the whole doc block. */
	private static final int EXPLANATION_DOC_MAX_LENGTH = 220;

	/**
	 * {@code "<Warns when|Blocks the record when> <importName>.<functionName>(<attributes>) answers false.
	 * <first sentence(s) of the function's own doc>"} - built without any LLM call, for a template body.
	 */
	public static String buildTemplateExplanation(final String pImportName, final String pFunctionName, final ScopeDecision pScope, final String pSeverity,
		final String pFunctionDocumentation)
	{
		final String verb = "ERROR".equalsIgnoreCase(pSeverity) ? "Blocks the record when" : "Warns when";
		final List<String> attributes = pScope.scope == Scope.NODE ? java.util.Collections.singletonList(pScope.attribute) : pScope.boundAttributes;
		final String argsText = attributes.isEmpty() ? "its configured argument(s)" : ConstraintBodyBuilder.join(attributes, ", ");
		final StringBuilder out = new StringBuilder();
		out.append(verb).append(' ').append(pImportName).append('.').append(pFunctionName).append('(').append(argsText).append(") answers false.");
		final String doc = ConstraintBodyBuilder.firstSentences(pFunctionDocumentation);
		if (doc != null && !doc.isEmpty())
		{
			out.append(' ').append(doc);
		}
		return out.toString();
	}

	/** Up to the first two sentences of {@code pText} (split on {@code ". "}), bounded to {@link #EXPLANATION_DOC_MAX_LENGTH} - null/blank in, null out. */
	static String firstSentences(final String pText)
	{
		if (pText == null || pText.trim().isEmpty())
		{
			return null;
		}
		final String trimmed = pText.trim();
		final String[] sentences = trimmed.split("(?<=[.!?])\\s+");
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < sentences.length && i < 2; i++)
		{
			if (out.length() > 0)
			{
				out.append(' ');
			}
			out.append(sentences[i]);
			if (out.length() >= ConstraintBodyBuilder.EXPLANATION_DOC_MAX_LENGTH)
			{
				break;
			}
		}
		String result = out.toString();
		if (result.length() > ConstraintBodyBuilder.EXPLANATION_DOC_MAX_LENGTH)
		{
			result = result.substring(0, ConstraintBodyBuilder.EXPLANATION_DOC_MAX_LENGTH) + "...";
		}
		return result;
	}

	// ------------------------------------------------------------------------------- language-model source checks

	/**
	 * fix/review-assets (2026-09-21), IBAN-body normalisation: a real captured failure - suggestion 6,
	 * {@code Fasttrack.lib.iban.validate} - had the model's own {@code submit_constraint_body} argument
	 * arrive as ONE physical line, with every intended line break sent as the TWO characters backslash+n
	 * (a double-JSON-escaped payload) instead of a real line break. Every line-based check in {@link
	 * #checkSubmittedSource} (the import line, the {@code uses} lines, the entry-point lines) then found
	 * nothing, even though {@code import Fasttrack.lib.iban as iban;} was right there in the text - the
	 * model could not understand the resulting refusal (on its third try it duplicated the import) and
	 * gave up in prose. {@link #checkSubmittedSource} now normalises the submitted text FIRST, so both the
	 * checks AND the source finally sent to {@code update_script} see real line breaks.
	 * <p>
	 * Detected narrowly: the WHOLE source has NO real line break at all ({@code \n}/{@code \r} as actual
	 * characters) - a source that already has real breaks is never touched, even if it also happens to
	 * contain a legitimate literal backslash-n somewhere. A no-op (returns {@code pSource} unchanged,
	 * same reference) when the gate does not match or there is no backslash in the text at all - safe to
	 * call unconditionally.
	 * <p>
	 * <b>{@code pProtectStringLiterals}</b>: when true (EBX Script source, {@link #checkSubmittedSource}'s
	 * own caller), an escape sequence written INSIDE a single-quoted string literal is copied verbatim,
	 * NEVER unescaped - CLAUDE.md &sect;2's own EBX Script string-escaping rule: {@code 'it\'s'},
	 * {@code '\\'} (one literal backslash), {@code '\t'}/{@code '\n'}/{@code '\r'} are all legal escapes
	 * there and must survive exactly as written (a message literal with an intentional embedded newline,
	 * or a value that legitimately contains a backslash-n two-character substring rather than an actual
	 * newline, must never be rewritten). When false (plain-language prose, {@code submit_explanation}'s
	 * own use), there is no string-literal syntax to protect - an apostrophe in "the record's value" must
	 * never be mistaken for opening a code string literal, so it is never treated as one.
	 */
	static String normalizeEscapedLineBreaks(final String pSource, final boolean pProtectStringLiterals)
	{
		if (pSource == null || pSource.indexOf('\n') >= 0 || pSource.indexOf('\r') >= 0 || pSource.indexOf('\\') < 0)
		{
			return pSource;
		}
		final int n = pSource.length();
		final StringBuilder out = new StringBuilder(n);
		boolean inString = false;
		int i = 0;
		while (i < n)
		{
			final char c = pSource.charAt(i);
			if (pProtectStringLiterals && c == '\'')
			{
				// a bare quote is always a genuine delimiter here: every escaped quote (\') inside a literal is
				// consumed atomically by the branch below, so the loop never sees one mid-escape
				inString = !inString;
				out.append(c);
				i++;
				continue;
			}
			if (c == '\\' && i + 1 < n)
			{
				final char next = pSource.charAt(i + 1);
				if (inString)
				{
					// an escape INSIDE a string literal - copy both characters verbatim, never unescape it
					out.append(c).append(next);
					i += 2;
					continue;
				}
				if (next == 'n')
				{
					out.append('\n');
					i += 2;
					continue;
				}
				if (next == 't')
				{
					out.append('\t');
					i += 2;
					continue;
				}
				if (next == 'r')
				{
					out.append('\r');
					i += 2;
					continue;
				}
				if (next == '"')
				{
					out.append('"');
					i += 2;
					continue;
				}
				// an escape this method does not recognise, outside a string literal: copy verbatim, never guess
				out.append(c);
				i++;
				continue;
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}

	private static final Pattern USES_LINE = Pattern.compile("^\\s*uses\\s+([\\w.]+)\\s+as\\s+(\\w+)\\s*;", Pattern.MULTILINE);
	private static final Pattern IMPORT_LINE = Pattern.compile("^\\s*import\\s+([\\w.]+)\\s+as\\s+(\\w+)\\s*;", Pattern.MULTILINE);

	/**
	 * Everything the task brief asks to be checked before a language-model-authored body is ever sent to
	 * {@code update_script}: imports exactly {@code pImportName} (and no other library), actually calls
	 * {@code pFunctionName} through that import's own alias, declares no {@code uses} outside the four
	 * allow-listed modules, defines every entry point the declared {@code pScope} requires, and touches
	 * neither the database nor an outbound call ({@code core.data}, {@code core.http}, any {@code db.xxx(}
	 * call - CLAUDE.md &sect;2: EBX Script has no try/catch, so a script able to reach the network or another
	 * table is exactly the capability this constraint must never smuggle in unreviewed). Audit F-08: all of this
	 * is read by {@link ScriptGuard} as tokens, with comments and string literals removed.
	 * <p>
	 * {@code pSource} is normalised ({@link #normalizeEscapedLineBreaks}) BEFORE any check runs - the caller
	 * ({@code ConstraintBodySubmission#submit}) uses the SAME normalised text as what it stores and later
	 * sends to {@code update_script}, so the checks and the actually-published source never disagree about
	 * what the text looks like.
	 *
	 * @return null when every check passes; otherwise the FIRST violation found, worded for the model to act
	 *         on directly (fed back into the next attempt) - naming what was looked for, what the source
	 *         actually has (line count, first 120 characters), and - when the source is still a single
	 *         physical line - the double-JSON-escape hint (see {@link #normalizeEscapedLineBreaks}'s own
	 *         Javadoc for the real captured case this responds to).
	 */
	public static String checkSubmittedSource(final String pSource, final String pImportName, final String pFunctionName, final ScopeDecision pScope)
	{
		return ConstraintBodyBuilder.checkSubmittedSource(pSource, pImportName, pFunctionName, pScope, false);
	}

	/**
	 * @param pAlreadyNormalized true when {@code pSource} was already normalised by the caller (
	 *        {@code ConstraintBodySubmission#submit}, which must check the SAME text it is about to store) -
	 *        false runs {@link #normalizeEscapedLineBreaks} itself first, for a direct caller (a test, or
	 *        anything checking a source it did not itself normalise).
	 */
	static String checkSubmittedSource(final String pRawSource, final String pImportName, final String pFunctionName, final ScopeDecision pScope,
		final boolean pAlreadyNormalized)
	{
		final String pSource = pAlreadyNormalized ? pRawSource : ConstraintBodyBuilder.normalizeEscapedLineBreaks(pRawSource, true);
		if (pSource == null || pSource.trim().isEmpty())
		{
			return "the source is empty.";
		}
		// audit F-08: read as tokens (comments and string literals removed), not as lines - see ScriptGuard
		final String violation = ScriptGuard.firstViolation(pSource, pImportName, pFunctionName, pScope.scope == Scope.NODE);
		if (violation != null)
		{
			return ConstraintBodyBuilder.describeViolation(violation, pSource);
		}
		return null;
	}

	/**
	 * fix/review-assets (2026-09-21): every structural refusal quotes what was looked for ({@code pWhat}) AND
	 * names what the source actually has - its real line count and its first 120 characters - so the model
	 * (or a developer reading the step detail) can SEE why the check failed instead of guessing. The real
	 * captured failure this responds to: "no import line was found" when {@code import Fasttrack.lib.iban as
	 * iban;} was visibly right there in the submitted text, on its ONE physical line - a refusal that named
	 * only the rule, never what the checker actually saw, gave the model nothing to act on (it duplicated the
	 * import on its next attempt instead of fixing the real problem). A source that is STILL a single
	 * physical line after {@link #normalizeEscapedLineBreaks} already ran gets the explicit double-JSON-escape
	 * hint - {@link #normalizeEscapedLineBreaks} already fixes the common case (a whole source with no real
	 * line break at all) upstream, so reaching this point still single-line means either that method's own
	 * gate did not match (a mixed case) or the source is genuinely, deliberately one line.
	 */
	private static String describeViolation(final String pWhat, final String pSource)
	{
		final int lineCount = pSource.split("\n", -1).length;
		final String preview = pSource.length() > 120 ? pSource.substring(0, 120) + "..." : pSource;
		final StringBuilder out = new StringBuilder(pWhat);
		out.append(" The source has ").append(lineCount).append(" line(s); the first 120 characters are: \"").append(preview).append('"');
		if (lineCount <= 1)
		{
			out.append(" - if your line breaks were sent as the two characters backslash-n, send real line breaks instead.");
		}
		else
		{
			out.append('.');
		}
		return out.toString();
	}

	// ------------------------------------------------------------------------------------------- validation alias

	/**
	 * fix/setup-alias (2026-09-21): a real captured body compiled clean with headers {@code uses core.message
	 * as msg;}, {@code uses core.json as json;}, {@code import Fasttrack.lib.iban as iban;} - NO {@code
	 * core.validation} line at all, because its own {@code setup()} was empty and it never needed one. The
	 * previous round's overwrite hardcoded {@code v.setDependencyToLocalNode()}/{@code v.addDependencyToModify
	 * (...)} into every {@code setup()} it wrote, which would fail to compile against exactly this source, on
	 * a line the model never wrote and could not act on. {@link #resolveValidationAlias} decides, from the
	 * MODEL's own submitted source alone (deterministic, no LLM call, unit-tested), what alias {@link
	 * #buildSetupBlock} must call through, and returns the source text {@link #replaceSetupBlock} must
	 * actually operate on - unchanged when the source already declares a usable alias, with one line inserted
	 * or replaced otherwise. Never duplicates a {@code core.validation} line.
	 */
	public static final class ValidationAliasResolution
	{
		/** The alias {@link #buildSetupBlock} must call {@code setDependencyToLocalNode()}/{@code addDependencyToModify(...)} through - never null/blank. */
		public final String alias;
		/** {@code pSource} verbatim when it already declared a usable {@code uses core.validation as <alias>;} line; otherwise {@code pSource} with exactly one line inserted (no prior {@code core.validation} line) or replaced (a prior BARE {@code uses core.validation;}, no alias) - never a second, duplicate {@code core.validation} line. */
		public final String source;

		ValidationAliasResolution(final String pAlias, final String pSource)
		{
			this.alias = pAlias;
			this.source = pSource;
		}
	}

	/** {@code uses core.validation as <alias>;} (the alias is optional here on purpose - see {@link #resolveValidationAlias}'s own three-way branch) - never anchored to a full line with {@code $} (mirrors {@link #IMPORT_LINE}/{@link #USES_LINE}'s own style: matches up to the semicolon only, so replacing/inserting around it never has to guess how much trailing whitespace or line terminator to keep). */
	private static final Pattern USES_CORE_VALIDATION_LINE = Pattern.compile("^\\s*uses\\s+core\\.validation(?:\\s+as\\s+(\\w+))?\\s*;", Pattern.MULTILINE);

	/**
	 * Three cases, exactly as the coordinator's own instruction states them:
	 * <ol>
	 * <li>{@code uses core.validation as <alias>;} already present - use THAT alias, {@code pSource}
	 * unchanged.</li>
	 * <li>A BARE {@code uses core.validation;} (no {@code as <alias>}) is present - REPLACE that one line in
	 * place with an aliased version (never insert a second line alongside it - that would be a duplicate
	 * {@code core.validation} declaration).</li>
	 * <li>No {@code core.validation} line at all - INSERT {@code uses core.validation as <alias>;} right after
	 * the last existing {@code uses ...;} line, before any {@code import} (or, with no {@code uses} line
	 * either, right before the first {@code import}; or, with neither, prepended - defensive, {@link
	 * #checkSubmittedSource} already requires an import line before this ever runs in practice).</li>
	 * </ol>
	 * The alias picked for cases 2-3 is the first of {@code v}, {@code validation}, {@code validation2},
	 * {@code validation3}, ... not already used as another {@code uses}/{@code import} alias in {@code
	 * pSource} ({@link #usedAliases}) - so a model that happens to have aliased something else {@code v}
	 * (an import, or a different {@code uses} unit) never collides with the alias this method picks.
	 */
	public static ValidationAliasResolution resolveValidationAlias(final String pSource)
	{
		final Matcher existing = ConstraintBodyBuilder.USES_CORE_VALIDATION_LINE.matcher(pSource);
		if (existing.find())
		{
			final String existingAlias = existing.group(1);
			if (existingAlias != null && !existingAlias.isEmpty())
			{
				return new ValidationAliasResolution(existingAlias, pSource);
			}
			// a bare "uses core.validation;" with no alias: replace THIS line in place, never add a second one
			final String alias = ConstraintBodyBuilder.pickUnusedAlias(pSource);
			final String replaced = pSource.substring(0, existing.start()) + "uses core.validation as " + alias + ";" + pSource.substring(existing.end());
			return new ValidationAliasResolution(alias, replaced);
		}
		final String alias = ConstraintBodyBuilder.pickUnusedAlias(pSource);
		return new ValidationAliasResolution(alias, ConstraintBodyBuilder.insertUsesCoreValidationLine(pSource, alias));
	}

	/** Every alias already spoken for in {@code pSource}, by an {@code import ... as <alias>;} OR a {@code uses ... as <alias>;} line (the exact same two patterns {@link #checkSubmittedSource} itself reads) - the collision set {@link #pickUnusedAlias} must avoid. */
	private static Set<String> usedAliases(final String pSource)
	{
		final Set<String> out = new LinkedHashSet<String>();
		final Matcher importMatcher = ConstraintBodyBuilder.IMPORT_LINE.matcher(pSource);
		while (importMatcher.find())
		{
			out.add(importMatcher.group(2));
		}
		final Matcher usesMatcher = ConstraintBodyBuilder.USES_LINE.matcher(pSource);
		while (usesMatcher.find())
		{
			out.add(usesMatcher.group(2));
		}
		return out;
	}

	/** {@code v}, else {@code validation}, else {@code validation2}/{@code validation3}/... - the first not already in {@link #usedAliases}. */
	private static String pickUnusedAlias(final String pSource)
	{
		final Set<String> used = ConstraintBodyBuilder.usedAliases(pSource);
		if (!used.contains("v"))
		{
			return "v";
		}
		if (!used.contains("validation"))
		{
			return "validation";
		}
		int suffix = 2;
		while (used.contains("validation" + suffix))
		{
			suffix++;
		}
		return "validation" + suffix;
	}

	/**
	 * Inserts {@code uses core.validation as <alias>;} as its OWN new line, right after the last existing
	 * {@code uses ...;} line (before this point, {@link #resolveValidationAlias} has already ruled out any
	 * existing {@code core.validation} line of either shape) - falls back to right before the first {@code
	 * import} line when there is no {@code uses} line at all, and to prepending when there is neither
	 * (defensive only: {@link #checkSubmittedSource} already requires an import line before this ever runs).
	 */
	private static String insertUsesCoreValidationLine(final String pSource, final String pAlias)
	{
		final String newLine = "uses core.validation as " + pAlias + ";\n";
		int lastUsesEnd = -1;
		final Matcher usesMatcher = ConstraintBodyBuilder.USES_LINE.matcher(pSource);
		while (usesMatcher.find())
		{
			lastUsesEnd = usesMatcher.end();
		}
		if (lastUsesEnd >= 0)
		{
			final int lineEnd = pSource.indexOf('\n', lastUsesEnd);
			return lineEnd < 0 ? pSource + "\n" + newLine : pSource.substring(0, lineEnd + 1) + newLine + pSource.substring(lineEnd + 1);
		}
		final Matcher importMatcher = ConstraintBodyBuilder.IMPORT_LINE.matcher(pSource);
		if (importMatcher.find())
		{
			return pSource.substring(0, importMatcher.start()) + newLine + pSource.substring(importMatcher.start());
		}
		return newLine + pSource;
	}

	// -------------------------------------------------------------------------------------- message attribution

	/**
	 * What a message-attribution match may rely on, extracted from a language-model-authored body (coordinator
	 * review, apply lot 2026-09-21: "Path + severity alone will count any other warning on the same field").
	 * {@link #text} is unescaped (EBX Script's own backslash-style, mirrored by {@link #unescapeEbxString}) -
	 * comparable directly against {@code UserMessage.formatMessage(Locale)}'s own plain-text output.
	 */
	public static final class MessageAttribution
	{
		/** The literal text (or its leading portion, when {@link #exact} is false). */
		public final String text;
		/** True when the whole {@code addWarning}/{@code addError} argument was this one literal (an exact match is possible); false when it is only the leading segment of a concatenation (a {@code startsWith} match is the most that can be claimed). */
		public final boolean exact;

		MessageAttribution(final String pText, final boolean pExact)
		{
			this.text = pText;
			this.exact = pExact;
		}
	}

	private static final Pattern ADD_MESSAGE_CALL = Pattern.compile("msg\\s*\\.\\s*(addWarning|addError)\\s*\\(");

	/**
	 * Finds the FIRST {@code msg.addWarning(...)}/{@code msg.addError(...)} call matching {@code pSeverity}
	 * ({@code ERROR} looks for {@code addError}, everything else for {@code addWarning} - the deterministic
	 * template's own convention) whose argument starts with a single-quoted string literal, and returns that
	 * literal's text. Returns null when no such call/leading-literal exists (a variable, a function call, or
	 * no call of the right kind at all) - the caller then falls back to path/severity-only attribution and
	 * notes it as approximate, never guesses. A deliberately simple scan (matches how {@link
	 * #checkSubmittedSource} itself reads source), not a real EBX Script parser: a literal built entirely from
	 * nested function calls with no leading quote is correctly reported as nothing-extractable, and a
	 * concatenation ({@code 'prefix' | expr}) is correctly reported as a PREFIX only ({@link
	 * MessageAttribution#exact} false) - the caller must use {@code startsWith}, never {@code equals}, for that
	 * case.
	 */
	public static MessageAttribution extractReportedMessage(final String pSource, final String pSeverity)
	{
		if (pSource == null || pSource.isEmpty())
		{
			return null;
		}
		final String wanted = "ERROR".equalsIgnoreCase(pSeverity) ? "addError" : "addWarning";
		final Matcher matcher = ConstraintBodyBuilder.ADD_MESSAGE_CALL.matcher(pSource);
		while (matcher.find())
		{
			if (!wanted.equals(matcher.group(1)))
			{
				continue;
			}
			int i = matcher.end();
			while (i < pSource.length() && Character.isWhitespace(pSource.charAt(i)))
			{
				i++;
			}
			if (i >= pSource.length() || pSource.charAt(i) != '\'')
			{
				continue; // this call's argument is not a literal (a variable, a function call...): nothing extractable from it
			}
			i++; // past the opening quote
			// collect the RAW (still-escaped) text, only tracking backslashes so an escaped quote (\') never looks
			// like the terminator - unescaping happens once, at the end, via unescapeEbxString (a single pass:
			// doing it here too, char by char, would double-unescape \t/\n/\r into a literal "t"/"n"/"r")
			final StringBuilder raw = new StringBuilder();
			boolean closed = false;
			while (i < pSource.length())
			{
				final char c = pSource.charAt(i);
				if (c == '\\' && i + 1 < pSource.length())
				{
					raw.append(c).append(pSource.charAt(i + 1));
					i += 2;
					continue;
				}
				if (c == '\'')
				{
					i++;
					closed = true;
					break;
				}
				raw.append(c);
				i++;
			}
			if (!closed)
			{
				continue; // an unterminated literal (malformed source): nothing to extract safely
			}
			while (i < pSource.length() && Character.isWhitespace(pSource.charAt(i)))
			{
				i++;
			}
			final boolean exact = i < pSource.length() && pSource.charAt(i) == ')';
			return new MessageAttribution(ConstraintBodyBuilder.unescapeEbxString(raw.toString()), exact);
		}
		return null;
	}

	/** The inverse of {@link #escapeEbxString} - a best-effort reversal (used only to compare against a rendered {@code UserMessage}, never re-emitted as code), so an escape sequence this class itself never emits (e.g. {@code \t}/{@code \n}/{@code \r}, which CLAUDE.md's own EBX Script notes say are legal) still degrades sensibly rather than throwing. */
	static String unescapeEbxString(final String pRaw)
	{
		final StringBuilder out = new StringBuilder(pRaw.length());
		for (int i = 0; i < pRaw.length(); i++)
		{
			final char c = pRaw.charAt(i);
			if (c == '\\' && i + 1 < pRaw.length())
			{
				final char next = pRaw.charAt(i + 1);
				switch (next)
				{
					case 't':
						out.append('\t');
						break;
					case 'n':
						out.append('\n');
						break;
					case 'r':
						out.append('\r');
						break;
					default:
						out.append(next);
						break;
				}
				i++;
			}
			else
			{
				out.append(c);
			}
		}
		return out.toString();
	}
}
