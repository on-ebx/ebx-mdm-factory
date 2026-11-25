package com.on.ebx.factory.capability;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JUnit-free: run the main, a failure throws. */
public final class ConstraintBodyBuilderTest
{
	public static void main(final String[] pArgs)
	{
		ConstraintBodyBuilderTest.parseBindingReadsAllThreeShapes();
		ConstraintBodyBuilderTest.parseBindingSkipsMalformedEntries();
		ConstraintBodyBuilderTest.attributeBoundNamesIsDistinctAndOrdered();
		ConstraintBodyBuilderTest.chooseScopeSingleAttributeIsNode();
		ConstraintBodyBuilderTest.chooseScopeMultipleAttributesIsStillNodeOnTheGivenAnchor();
		ConstraintBodyBuilderTest.chooseScopeNoAttributeFallsBackToSuggestionAttribute();
		ConstraintBodyBuilderTest.chooseScopeNoAttributeNoFallbackHasNullAttribute();
		ConstraintBodyBuilderTest.dependencyAttributesSingleIsAnchorOnly();
		ConstraintBodyBuilderTest.dependencyAttributesAnchorFirstThenOtherBoundDistinct();
		ConstraintBodyBuilderTest.buildSetupBlockSingleDependencyUsesLocalNode();
		ConstraintBodyBuilderTest.buildSetupBlockUsesTheGivenAliasNotHardcodedV();
		ConstraintBodyBuilderTest.buildSetupBlockMultipleDependenciesUsesAddDependencyToModifyForEach();
		ConstraintBodyBuilderTest.resolveValidationAliasReusesAnExistingAliasedLine();
		ConstraintBodyBuilderTest.resolveValidationAliasInsertsALineForTheRealCapturedHeaderSetWithNoValidationImport();
		ConstraintBodyBuilderTest.resolveValidationAliasReplacesABareUsesLineWithNoAliasInPlaceNeverDuplicating();
		ConstraintBodyBuilderTest.resolveValidationAliasPicksAnotherAliasWhenVIsAlreadyAnImportAlias();
		ConstraintBodyBuilderTest.resolveValidationAliasPicksValidation2WhenBothVAndValidationAreTaken();
		ConstraintBodyBuilderTest.replaceSetupBlockReplacesTheModelsOwnSetup();
		ConstraintBodyBuilderTest.replaceSetupBlockHandlesNestedBeginEndInsideSetup();
		ConstraintBodyBuilderTest.replaceSetupBlockIsANoOpWhenSetupIsMissing();
		ConstraintBodyBuilderTest.deriveScriptNameIsDeterministicAndStable();
		ConstraintBodyBuilderTest.deriveScriptNameIsTruncatedAndValid();
		ConstraintBodyBuilderTest.elementPathAndIdePathNode();
		ConstraintBodyBuilderTest.elementPathAndIdePathTable();
		ConstraintBodyBuilderTest.templateBodyNodeSingleAttribute();
		ConstraintBodyBuilderTest.templateBodyHealthyCaseIsExactlyTheBodyItAlwaysWas();
		ConstraintBodyBuilderTest.templateBodyRefusesAHostileImportName();
		ConstraintBodyBuilderTest.templateBodyRefusesAHostileBinding();
		ConstraintBodyBuilderTest.templateBodyGuardsOptionalAttributeNotMandatory();
		ConstraintBodyBuilderTest.templateBodyRefusesTableScope();
		ConstraintBodyBuilderTest.templateBodyNodeMultipleAttributesUsesAddDependencyToModify();
		ConstraintBodyBuilderTest.templateBodyErrorSeverityUsesAddError();
		ConstraintBodyBuilderTest.templateBodyLiteralArgumentIsEscaped();
		ConstraintBodyBuilderTest.templateBodyRefusesToBeProvidedParameter();
		ConstraintBodyBuilderTest.templateExplanationNodeWarn();
		ConstraintBodyBuilderTest.templateExplanationErrorSeverity();
		ConstraintBodyBuilderTest.checkSubmittedSourceAcceptsGoodNodeBody();
		ConstraintBodyBuilderTest.checkSubmittedSourceRefusesExtraImport();
		ConstraintBodyBuilderTest.checkSubmittedSourceRefusesMissingCall();
		ConstraintBodyBuilderTest.checkSubmittedSourceRefusesDisallowedUses();
		ConstraintBodyBuilderTest.checkSubmittedSourceRefusesDatabaseAccess();
		ConstraintBodyBuilderTest.checkSubmittedSourceRefusesMissingEntryPointTable();
		ConstraintBodyBuilderTest.escapeEbxStringHandlesQuoteAndBackslash();
		ConstraintBodyBuilderTest.templateBodyEmitsExactlyTheCallerSuppliedMessageEscaped();
		ConstraintBodyBuilderTest.buildTemplateBodyRefusesEmptyMessage();
		ConstraintBodyBuilderTest.buildFallbackMessageUsesLabel();
		ConstraintBodyBuilderTest.buildFallbackMessageHumanisesTheAttributeNameWhenNoLabel();
		ConstraintBodyBuilderTest.buildFallbackMessageBlankLabelAndNoAttributeFallsBackToGenericSubject();
		ConstraintBodyBuilderTest.humanizeSplitsCamelCaseAndUnderscores();
		ConstraintBodyBuilderTest.extractReportedMessageExactLiteral();
		ConstraintBodyBuilderTest.extractReportedMessageConcatenationIsPrefixOnly();
		ConstraintBodyBuilderTest.extractReportedMessageConcatenationWithStatusSuffixIsPrefixMatchOnly();
		ConstraintBodyBuilderTest.extractReportedMessageNothingExtractableWhenNotALiteral();
		ConstraintBodyBuilderTest.extractReportedMessagePicksTheRightSeverityCall();
		ConstraintBodyBuilderTest.extractReportedMessageUnescapesQuoteAndBackslash();
		ConstraintBodyBuilderTest.extractReportedMessageNullOrEmptySourceIsNull();
		ConstraintBodyBuilderTest.normalizeAcceptsTheRealCapturedDoubleEscapedIbanPayload();
		ConstraintBodyBuilderTest.normalizeProtectsEscapesInsideStringLiterals();
		ConstraintBodyBuilderTest.normalizeIsANoOpWhenARealLineBreakAlreadyExists();
		ConstraintBodyBuilderTest.normalizeIsANoOpWithNoBackslashAtAll();
		ConstraintBodyBuilderTest.normalizeInProseModeNeverTreatsAnApostropheAsAStringDelimiter();
		ConstraintBodyBuilderTest.checkSubmittedSourceRefusalNamesWhatWasLookedForAndWhatWasSeen();
		ConstraintBodyBuilderTest.checkSubmittedSourceRefusalHintsAtDoubleEscapingWhenStillOneLine();

		System.out.println("ConstraintBodyBuilderTest OK");
	}

	private static void parseBindingReadsAllThreeShapes()
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = ConstraintBodyBuilder
			.parseBinding("{\"rawIban\":\"iban\",\"addressCountryCode\":{\"literal\":\"FR\"},\"threshold\":{\"toBeProvided\":\"no attribute fits\"}}");
		ConstraintBodyBuilderTest.check("3 entries", binding.size() == 3);
		ConstraintBodyBuilderTest.check("rawIban is attribute", binding.get("rawIban").kind == ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE);
		ConstraintBodyBuilderTest.check("rawIban value", "iban".equals(binding.get("rawIban").value));
		ConstraintBodyBuilderTest.check("addressCountryCode is literal", binding.get("addressCountryCode").kind == ConstraintBodyBuilder.BindingEntry.Kind.LITERAL);
		ConstraintBodyBuilderTest.check("addressCountryCode value", "FR".equals(binding.get("addressCountryCode").value));
		ConstraintBodyBuilderTest.check("threshold is toBeProvided", binding.get("threshold").kind == ConstraintBodyBuilder.BindingEntry.Kind.TO_BE_PROVIDED);
	}

	private static void parseBindingSkipsMalformedEntries()
	{
		ConstraintBodyBuilderTest.check("null binding is empty, not thrown", ConstraintBodyBuilder.parseBinding(null).isEmpty());
		ConstraintBodyBuilderTest.check("blank binding is empty", ConstraintBodyBuilder.parseBinding("   ").isEmpty());
		ConstraintBodyBuilderTest.check("not-an-object binding is empty", ConstraintBodyBuilder.parseBinding("[1,2,3]").isEmpty());
		ConstraintBodyBuilderTest.check("malformed JSON is empty, not thrown", ConstraintBodyBuilder.parseBinding("{not json").isEmpty());
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = ConstraintBodyBuilder.parseBinding("{\"a\":\"x\",\"b\":{\"unknownShape\":\"y\"},\"c\":123}");
		ConstraintBodyBuilderTest.check("only the recognised shape is kept", binding.size() == 1 && binding.containsKey("a"));
	}

	private static void attributeBoundNamesIsDistinctAndOrdered()
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("a", new ConstraintBodyBuilder.BindingEntry("a", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "phone"));
		binding.put("b", new ConstraintBodyBuilder.BindingEntry("b", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "country"));
		binding.put("c", new ConstraintBodyBuilder.BindingEntry("c", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "phone"));
		final List<String> names = ConstraintBodyBuilder.attributeBoundNames(binding);
		ConstraintBodyBuilderTest.check("distinct, first-encountered order", names.equals(Arrays.asList("phone", "country")));
	}

	private static void chooseScopeSingleAttributeIsNode()
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("value", new ConstraintBodyBuilder.BindingEntry("value", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "phone"));
		final ConstraintBodyBuilder.ScopeDecision decision = ConstraintBodyBuilder.chooseScope(binding, null);
		ConstraintBodyBuilderTest.check("NODE", decision.scope == ConstraintBodyBuilder.Scope.NODE);
		ConstraintBodyBuilderTest.check("attribute is phone", "phone".equals(decision.attribute));
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21), item 1: several bound attributes no longer produce a TABLE
	 * decision - {@code chooseScope} is ALWAYS NODE now, anchored on the given attribute, with every distinct
	 * bound attribute carried in {@code boundAttributes} (consumed by {@link
	 * ConstraintBodyBuilder#dependencyAttributes}/{@link ConstraintBodyBuilder#buildSetupBlock} to emit one
	 * {@code addDependencyToModify} call per attribute).
	 */
	private static void chooseScopeMultipleAttributesIsStillNodeOnTheGivenAnchor()
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("rawIban", new ConstraintBodyBuilder.BindingEntry("rawIban", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "phone"));
		binding.put("addressCountryCode", new ConstraintBodyBuilder.BindingEntry("addressCountryCode", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "country"));
		final ConstraintBodyBuilder.ScopeDecision decision = ConstraintBodyBuilder.chooseScope(binding, "phone");
		ConstraintBodyBuilderTest.check("NODE, even with more than one bound attribute (fix/node-scope-multi-attribute)",
			decision.scope == ConstraintBodyBuilder.Scope.NODE);
		ConstraintBodyBuilderTest.check("anchored on the given attribute", "phone".equals(decision.attribute));
		ConstraintBodyBuilderTest.check("every distinct bound attribute carried through", decision.boundAttributes.equals(Arrays.asList("phone", "country")));
	}

	private static void chooseScopeNoAttributeFallsBackToSuggestionAttribute()
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("threshold", new ConstraintBodyBuilder.BindingEntry("threshold", ConstraintBodyBuilder.BindingEntry.Kind.LITERAL, "10"));
		final ConstraintBodyBuilder.ScopeDecision decision = ConstraintBodyBuilder.chooseScope(binding, "amount");
		ConstraintBodyBuilderTest.check("falls back to NODE on the suggestion's own attribute", decision.scope == ConstraintBodyBuilder.Scope.NODE);
		ConstraintBodyBuilderTest.check("attribute is the fallback", "amount".equals(decision.attribute));
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21): still NODE even with nothing to anchor on - {@code
	 * chooseScope} stays total (never throws) with a null {@code attribute} in this pathological case; the
	 * CALLER ({@code ebx.CapabilityApplier.prepare}) is the one that refuses apply outright when there is no
	 * anchor at all, not this method (see {@code chooseScope}'s own Javadoc).
	 */
	private static void chooseScopeNoAttributeNoFallbackHasNullAttribute()
	{
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("threshold", new ConstraintBodyBuilder.BindingEntry("threshold", ConstraintBodyBuilder.BindingEntry.Kind.LITERAL, "10"));
		final ConstraintBodyBuilder.ScopeDecision decision = ConstraintBodyBuilder.chooseScope(binding, null);
		ConstraintBodyBuilderTest.check("still NODE, never TABLE any more", decision.scope == ConstraintBodyBuilder.Scope.NODE);
		ConstraintBodyBuilderTest.check("no attribute to anchor on", decision.attribute == null);
	}

	private static void dependencyAttributesSingleIsAnchorOnly()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final List<String> deps = ConstraintBodyBuilder.dependencyAttributes(node);
		ConstraintBodyBuilderTest.check("single dependency is the anchor alone", deps.equals(Arrays.asList("phone")));
	}

	/** Anchor first, then every OTHER distinct bound attribute (duplicates of the anchor collapse away). */
	private static void dependencyAttributesAnchorFirstThenOtherBoundDistinct()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban",
			Arrays.asList("iban", "country", "iban"));
		final List<String> deps = ConstraintBodyBuilder.dependencyAttributes(node);
		ConstraintBodyBuilderTest.check("anchor first, then distinct bound attributes: " + deps, deps.equals(Arrays.asList("iban", "country")));
	}

	private static void buildSetupBlockSingleDependencyUsesLocalNode()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final String setup = ConstraintBodyBuilder.buildSetupBlock(node, "Contact", "v");
		ConstraintBodyBuilderTest.check("uses setDependencyToLocalNode", setup.contains("v.setDependencyToLocalNode();"));
		ConstraintBodyBuilderTest.check("no addDependencyToModify with a single dependency", !setup.contains("addDependencyToModify"));
		ConstraintBodyBuilderTest.check("well-formed procedure", setup.startsWith("export procedure setup()\nbegin\n") && setup.trim().endsWith("end"));
	}

	/** fix/setup-alias (2026-09-21): the alias argument is used verbatim, whatever it is - never hardcoded "v" internally any more. */
	private static void buildSetupBlockUsesTheGivenAliasNotHardcodedV()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final String setup = ConstraintBodyBuilder.buildSetupBlock(node, "Contact", "validationContext");
		ConstraintBodyBuilderTest.check("calls through the given alias", setup.contains("validationContext.setDependencyToLocalNode();"));
		ConstraintBodyBuilderTest.check("never the literal v", !setup.contains(" v."));
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21), item 1's proven fix: several bound attributes ⇒ one
	 * {@code addDependencyToModify('/root/<Table>/<attribute>')} call per DISTINCT attribute, anchor
	 * included, absolute paths, anchor declared first - never {@code setDependencyToLocalNode()}.
	 */
	private static void buildSetupBlockMultipleDependenciesUsesAddDependencyToModifyForEach()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban",
			Arrays.asList("iban", "country"));
		final String setup = ConstraintBodyBuilder.buildSetupBlock(node, "Supplier", "v");
		ConstraintBodyBuilderTest.check("no setDependencyToLocalNode with two dependencies", !setup.contains("setDependencyToLocalNode"));
		ConstraintBodyBuilderTest.check("absolute path to the anchor", setup.contains("v.addDependencyToModify('/root/Supplier/iban');"));
		ConstraintBodyBuilderTest.check("absolute path to the other bound attribute", setup.contains("v.addDependencyToModify('/root/Supplier/country');"));
		ConstraintBodyBuilderTest.check("anchor declared before the other attribute", setup.indexOf("/iban');") < setup.indexOf("/country');"));
	}

	// ---------------------------------------------------------------------------------------- fix/setup-alias

	/** Case 1 (coordinator spec): an existing {@code uses core.validation as <alias>;} line is reused verbatim - never replaced, never a second one inserted. */
	private static void resolveValidationAliasReusesAnExistingAliasedLine()
	{
		final String source = "uses core.message as msg;\nuses core.validation as validationContext;\nimport Fasttrack.lib.iban as iban;\n\n"
			+ "export procedure check(record : .Supplier)\nbegin\nend\n\nexport procedure setup()\nbegin\nend\n";
		final ConstraintBodyBuilder.ValidationAliasResolution resolution = ConstraintBodyBuilder.resolveValidationAlias(source);
		ConstraintBodyBuilderTest.check("reuses the existing alias", "validationContext".equals(resolution.alias));
		ConstraintBodyBuilderTest.check("source left unchanged (same reference is fine, but at least equal content)", source.equals(resolution.source));
	}

	/**
	 * The real captured header set (coordinator's own example): {@code uses core.message as msg;}, {@code
	 * uses core.json as json;}, {@code import Fasttrack.lib.iban as iban;} - NO {@code core.validation} line
	 * at all, because the model's own (soon-discarded) {@code setup()} was empty. A line must be INSERTED
	 * right after the last existing {@code uses ...;} line, before the {@code import}, picking {@code v}
	 * (nothing else uses it here) - and the resulting source, with a multi-attribute setup block built through
	 * that same alias, must carry both {@code addDependencyToModify} calls through it, with the model's own
	 * body left untouched.
	 */
	private static void resolveValidationAliasInsertsALineForTheRealCapturedHeaderSetWithNoValidationImport()
	{
		final String source = "uses core.message as msg;\nuses core.json as json;\n\nimport Fasttrack.lib.iban as iban;\n\n"
			+ "export procedure check(record : .Person)\nbegin\n  if isNull(record.iban) then return;\n"
			+ "  const report := iban.validate(record.iban, record.country);\nend\n\n" + "export procedure setup()\nbegin\nend\n";
		final ConstraintBodyBuilder.ValidationAliasResolution resolution = ConstraintBodyBuilder.resolveValidationAlias(source);
		ConstraintBodyBuilderTest.check("picks v: nothing else uses it", "v".equals(resolution.alias));
		ConstraintBodyBuilderTest.check("inserted right after the last uses line, before the import",
			resolution.source.contains("uses core.json as json;\nuses core.validation as v;\n\nimport Fasttrack.lib.iban as iban;"));
		ConstraintBodyBuilderTest.check("never duplicated",
			resolution.source.indexOf("core.validation") == resolution.source.lastIndexOf("core.validation"));

		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban",
			Arrays.asList("iban", "country"));
		final String setupBlock = ConstraintBodyBuilder.buildSetupBlock(node, "Person", resolution.alias);
		final String withSetup = ConstraintBodyBuilder.replaceSetupBlock(resolution.source, setupBlock);
		ConstraintBodyBuilderTest.check("both dependency calls go through the inserted alias, compiles-shaped",
			withSetup.contains("v.addDependencyToModify('/root/Person/iban');") && withSetup.contains("v.addDependencyToModify('/root/Person/country');"));
		ConstraintBodyBuilderTest.check("the model's own body is untouched",
			withSetup.contains("const report := iban.validate(record.iban, record.country);"));
	}

	/** Case 2 (coordinator spec): a BARE {@code uses core.validation;} (no alias) is replaced IN PLACE with an aliased version - never left alongside a second, inserted line. */
	private static void resolveValidationAliasReplacesABareUsesLineWithNoAliasInPlaceNeverDuplicating()
	{
		final String source = "uses core.message as msg;\nuses core.validation;\nimport Fasttrack.lib.iban as iban;\n\n"
			+ "export procedure check(record : .Supplier)\nbegin\nend\n\nexport procedure setup()\nbegin\nend\n";
		final ConstraintBodyBuilder.ValidationAliasResolution resolution = ConstraintBodyBuilder.resolveValidationAlias(source);
		ConstraintBodyBuilderTest.check("picks v", "v".equals(resolution.alias));
		ConstraintBodyBuilderTest.check("the bare line becomes aliased, in place, same position",
			resolution.source.contains("uses core.message as msg;\nuses core.validation as v;\nimport Fasttrack.lib.iban as iban;"));
		ConstraintBodyBuilderTest.check("never duplicated",
			resolution.source.indexOf("core.validation") == resolution.source.lastIndexOf("core.validation"));
	}

	/** Case 3 (coordinator spec): alias {@code v} already taken - by an IMPORT this time - so {@code validation} is chosen instead. */
	private static void resolveValidationAliasPicksAnotherAliasWhenVIsAlreadyAnImportAlias()
	{
		final String source = "uses core.message as msg;\nimport Fasttrack.lib.iban as v;\n\n"
			+ "export procedure check(record : .Supplier)\nbegin\nend\n\nexport procedure setup()\nbegin\nend\n";
		final ConstraintBodyBuilder.ValidationAliasResolution resolution = ConstraintBodyBuilder.resolveValidationAlias(source);
		ConstraintBodyBuilderTest.check("v is taken by the import: falls back to validation", "validation".equals(resolution.alias));
		ConstraintBodyBuilderTest.check("inserted with the chosen alias", resolution.source.contains("uses core.validation as validation;"));
	}

	/** Both {@code v} and {@code validation} are already taken (by two different {@code uses} units) - falls through to {@code validation2}. */
	private static void resolveValidationAliasPicksValidation2WhenBothVAndValidationAreTaken()
	{
		final String source = "uses core.message as v;\nuses core.string as validation;\nimport Fasttrack.lib.iban as iban;\n\n"
			+ "export procedure check(record : .Supplier)\nbegin\nend\n\nexport procedure setup()\nbegin\nend\n";
		final ConstraintBodyBuilder.ValidationAliasResolution resolution = ConstraintBodyBuilder.resolveValidationAlias(source);
		ConstraintBodyBuilderTest.check("both v and validation taken: falls back to validation2", "validation2".equals(resolution.alias));
	}

	/** {@link ConstraintBodyBuilder#replaceSetupBlock} swaps out the model's own setup() body, wherever it sits, leaving everything else untouched. */
	private static void replaceSetupBlockReplacesTheModelsOwnSetup()
	{
		final String source = "uses core.message as msg;\nuses core.validation as v;\nimport Fasttrack.lib.iban as iban;\n\n"
			+ "export procedure check(record : .Supplier)\nbegin\n  if isNull(record.iban) then return;\nend\n\n"
			+ "export procedure setup()\nbegin\n  v.setDependencyToLocalNode();\nend\n";
		final String newSetup = "export procedure setup()\nbegin\n  v.addDependencyToModify('/root/Supplier/iban');\n"
			+ "  v.addDependencyToModify('/root/Supplier/country');\nend\n";
		final String replaced = ConstraintBodyBuilder.replaceSetupBlock(source, newSetup);
		ConstraintBodyBuilderTest.check("the model's own setup body is gone", !replaced.contains("setDependencyToLocalNode"));
		ConstraintBodyBuilderTest.check("the factory's own setup is there instead",
			replaced.contains("v.addDependencyToModify('/root/Supplier/iban');") && replaced.contains("v.addDependencyToModify('/root/Supplier/country');"));
		ConstraintBodyBuilderTest.check("everything before setup is untouched",
			replaced.startsWith("uses core.message as msg;\nuses core.validation as v;\nimport Fasttrack.lib.iban as iban;"));
		ConstraintBodyBuilderTest.check("check() body is untouched", replaced.contains("if isNull(record.iban) then return;"));
	}

	/** A nested begin/end pair inside the model's own setup() must not be mistaken for the block's own closing end. */
	private static void replaceSetupBlockHandlesNestedBeginEndInsideSetup()
	{
		final String source = "export procedure setup()\nbegin\n  if true then\n  begin\n    v.setDependencyToLocalNode();\n  end\nend\n\n"
			+ "export procedure check(record : .X)\nbegin\nend\n";
		final String newSetup = "export procedure setup()\nbegin\n  v.setDependencyToLocalNode();\nend\n";
		final String replaced = ConstraintBodyBuilder.replaceSetupBlock(source, newSetup);
		ConstraintBodyBuilderTest.check("nested begin/end inside setup did not confuse the matcher",
			replaced.contains("v.setDependencyToLocalNode();") && !replaced.contains("if true then"));
		ConstraintBodyBuilderTest.check("the trailing check() procedure survives", replaced.contains("export procedure check(record : .X)"));
	}

	/** A defensive no-op: a source with no {@code setup(} entry point at all is returned unchanged, never throws. */
	private static void replaceSetupBlockIsANoOpWhenSetupIsMissing()
	{
		final String source = "export procedure check(record : .X)\nbegin\nend\n";
		final String replaced = ConstraintBodyBuilder.replaceSetupBlock(source, "export procedure setup()\nbegin\n  v.setDependencyToLocalNode();\nend\n");
		ConstraintBodyBuilderTest.check("unchanged when there is no setup() to replace", source.equals(replaced));
	}

	private static void deriveScriptNameIsDeterministicAndStable()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final String first = ConstraintBodyBuilder.deriveScriptName("Contact", "isValid", node);
		final String second = ConstraintBodyBuilder.deriveScriptName("Contact", "isValid", node);
		ConstraintBodyBuilderTest.check("same inputs -> same name", first.equals(second));
		ConstraintBodyBuilderTest.check("name is Contact_isValid_phone", "Contact_isValid_phone".equals(first));
		ConstraintBodyBuilderTest.check("valid identifier pattern", first.matches("[A-Za-z][A-Za-z0-9_]*"));

		final ConstraintBodyBuilder.ScopeDecision table = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.TABLE, null,
			Arrays.asList("iban", "country"));
		ConstraintBodyBuilderTest.check("table scope name has no attribute suffix", "Contact_validate".equals(ConstraintBodyBuilder.deriveScriptName("Contact", "validate", table)));
	}

	private static void deriveScriptNameIsTruncatedAndValid()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE,
			"aVeryLongAttributeNameThatKeepsGoingAndGoingAndGoingForever", Arrays.asList("x"));
		final String name = ConstraintBodyBuilder.deriveScriptName("AVeryLongEntityNameThatAlsoKeepsGoingForever", "aVeryLongFunctionNameToo", node);
		ConstraintBodyBuilderTest.check("never exceeds the schema's maxLength", name.length() <= ConstraintBodyBuilder.SCRIPT_NAME_MAX_LENGTH);
		ConstraintBodyBuilderTest.check("still a valid identifier after truncation", name.matches("[A-Za-z][A-Za-z0-9_]*"));
	}

	private static void elementPathAndIdePathNode()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		ConstraintBodyBuilderTest.check("node element path", "/root/Contact/phone".equals(ConstraintBodyBuilder.elementPath("Contact", node)));
		ConstraintBodyBuilderTest.check("node idePath",
			"data-model/Companies_Staging/root/Contact/phone/@nodeConstraint/Contact_isValid_phone"
				.equals(ConstraintBodyBuilder.scriptIdePath("Companies_Staging", "Contact", node, "Contact_isValid_phone")));
	}

	private static void elementPathAndIdePathTable()
	{
		final ConstraintBodyBuilder.ScopeDecision table = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.TABLE, null,
			Arrays.asList("iban", "country"));
		ConstraintBodyBuilderTest.check("table element path", "/root/Contact".equals(ConstraintBodyBuilder.elementPath("Contact", table)));
		ConstraintBodyBuilderTest.check("table idePath",
			"data-model/Companies_Staging/root/Contact/@constraint/Contact_validate".equals(ConstraintBodyBuilder.scriptIdePath("Companies_Staging", "Contact", table, "Contact_validate")));
	}

	private static void templateBodyNodeSingleAttribute()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("value", new ConstraintBodyBuilder.BindingEntry("value", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "phone"));
		final Map<String, Boolean> mandatory = new LinkedHashMap<String, Boolean>();
		mandatory.put("phone", Boolean.FALSE);
		final String body = ConstraintBodyBuilder.buildTemplateBody("Contact", node, "Fasttrack.lib.phone", "phone", "isValid",
			Arrays.asList(new ConstraintBodyBuilder.Param("value", "string")), binding, mandatory, "WARN", "Phone number is not recognised.");
		ConstraintBodyBuilderTest.check("imports the library", body.contains("import Fasttrack.lib.phone as phone;"));
		ConstraintBodyBuilderTest.check("null guard on the optional attribute", body.contains("if isNull(record.phone) then return;"));
		ConstraintBodyBuilderTest.check("calls the function on record.phone", body.contains("if not phone.isValid(record.phone) then"));
		ConstraintBodyBuilderTest.check("warns, does not error", body.contains("msg.addWarning("));
		ConstraintBodyBuilderTest.check("node entry point", body.contains("export procedure check(record : .Contact)"));
		ConstraintBodyBuilderTest.check("setup depends on the local node", body.contains("v.setDependencyToLocalNode();"));
		ConstraintBodyBuilderTest.check("reports the CALLER's own message, not a Java-built one", body.contains("msg.addWarning('Phone number is not recognised.')"));
	}

	/** Audit F2-16: the healthy case yields the SAME text as before F2-01 (captured from main), so no existing script changes. */
	private static void templateBodyHealthyCaseIsExactlyTheBodyItAlwaysWas()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("value", new ConstraintBodyBuilder.BindingEntry("value", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "phone"));
		final Map<String, Boolean> mandatory = new LinkedHashMap<String, Boolean>();
		mandatory.put("phone", Boolean.FALSE);
		final String body = ConstraintBodyBuilder.buildTemplateBody("Contact", node, "Fasttrack.lib.phone", "phone", "isValid",
			Arrays.asList(new ConstraintBodyBuilder.Param("value", "string")), binding, mandatory, "WARN", "Phone number is not recognised.");
		final String expected = "uses core.message as msg;\nuses core.validation as v;\nimport Fasttrack.lib.phone as phone;\n\n"
			+ "export procedure check(record : .Contact)\nbegin\n  if isNull(record.phone) then return;\n  if not phone.isValid(record.phone) then\n  begin\n"
			+ "    msg.addWarning('Phone number is not recognised.');\n  end\nend\n\n" + ConstraintBodyBuilder.buildSetupBlock(node, "Contact", "v");
		ConstraintBodyBuilderTest.check("healthy body unchanged:\n" + body, expected.equals(body));
	}

	/** Audit F2-01: an import name that carries statements is refused, never written into the source. */
	private static void templateBodyRefusesAHostileImportName()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("value", new ConstraintBodyBuilder.BindingEntry("value", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "phone"));
		final Map<String, Boolean> mandatory = new LinkedHashMap<String, Boolean>();
		mandatory.put("phone", Boolean.FALSE);
		for (final String hostile : new String[] { "a.b as x; uses core.http as h; //", "Fasttrack.lib.phone as p; uses core.json as j; export procedure x() //", "", "a..b", "a b", "a.b;" })
		{
			boolean refused = false;
			try
			{
				ConstraintBodyBuilder.buildTemplateBody("Contact", node, hostile, "phone", "isValid", Arrays.asList(new ConstraintBodyBuilder.Param("value", "string")), binding, mandatory,
					"WARN", "Phone number is not recognised.");
			}
			catch (final IllegalArgumentException ex)
			{
				refused = true;
			}
			ConstraintBodyBuilderTest.check("hostile import name refused: " + hostile, refused);
		}
	}

	/** Audit F2-01: a binding value that is not an identifier is refused. */
	private static void templateBodyRefusesAHostileBinding()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final Map<String, Boolean> mandatory = new LinkedHashMap<String, Boolean>();
		mandatory.put("phone", Boolean.FALSE);
		for (final String hostile : new String[] { "phone) or true //", "phone; msg.addError('x')", "a.b", "" })
		{
			final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
			binding.put("value", new ConstraintBodyBuilder.BindingEntry("value", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, hostile));
			boolean refused = false;
			try
			{
				ConstraintBodyBuilder.buildTemplateBody("Contact", node, "Fasttrack.lib.phone", "phone", "isValid", Arrays.asList(new ConstraintBodyBuilder.Param("value", "string")), binding,
					mandatory, "WARN", "Phone number is not recognised.");
			}
			catch (final IllegalArgumentException ex)
			{
				refused = true;
			}
			ConstraintBodyBuilderTest.check("hostile binding refused: " + hostile, refused);
		}
	}

	private static void templateBodyGuardsOptionalAttributeNotMandatory()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("value", new ConstraintBodyBuilder.BindingEntry("value", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "phone"));
		final Map<String, Boolean> mandatory = new LinkedHashMap<String, Boolean>();
		mandatory.put("phone", Boolean.TRUE);
		final String body = ConstraintBodyBuilder.buildTemplateBody("Contact", node, "Fasttrack.lib.phone", "phone", "isValid",
			Arrays.asList(new ConstraintBodyBuilder.Param("value", "string")), binding, mandatory, "WARN", "Phone number is not recognised.");
		ConstraintBodyBuilderTest.check("no null guard on a mandatory attribute", !body.contains("isNull(record.phone)"));
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21), item 1: {@code buildTemplateBody} now refuses a TABLE
	 * {@link ConstraintBodyBuilder.ScopeDecision} outright - {@code chooseScope} never produces one any more,
	 * so reaching this method with one means a caller reconstructed a {@code ScopeDecision} by hand instead
	 * of going through {@code chooseScope}.
	 */
	private static void templateBodyRefusesTableScope()
	{
		final ConstraintBodyBuilder.ScopeDecision table = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.TABLE, null,
			Arrays.asList("iban", "country"));
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("rawIban", new ConstraintBodyBuilder.BindingEntry("rawIban", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "iban"));
		binding.put("addressCountryCode", new ConstraintBodyBuilder.BindingEntry("addressCountryCode", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "country"));
		boolean threw = false;
		try
		{
			ConstraintBodyBuilder.buildTemplateBody("Company", table, "Fasttrack.lib.iban", "iban", "validate",
				Arrays.asList(new ConstraintBodyBuilder.Param("rawIban", "string"), new ConstraintBodyBuilder.Param("addressCountryCode", "string")), binding,
				new LinkedHashMap<String, Boolean>(), "WARN", "This record: not accepted.");
		}
		catch (final IllegalStateException ex)
		{
			threw = true;
		}
		ConstraintBodyBuilderTest.check("refuses a TABLE ScopeDecision - chooseScope never produces one any more", threw);
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21), item 1's real proven shape: a NODE constraint with TWO
	 * bound attributes (the anchor plus one other) calls the function with both, and its own {@code setup()}
	 * declares an explicit {@code addDependencyToModify} for each - never {@code setDependencyToLocalNode()}
	 * (which would only track the anchor field, silently missing every record whose OTHER bound attribute
	 * changed - the real captured bug this whole fix responds to).
	 */
	private static void templateBodyNodeMultipleAttributesUsesAddDependencyToModify()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban",
			Arrays.asList("iban", "country"));
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("rawIban", new ConstraintBodyBuilder.BindingEntry("rawIban", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "iban"));
		binding.put("addressCountryCode", new ConstraintBodyBuilder.BindingEntry("addressCountryCode", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "country"));
		final String body = ConstraintBodyBuilder.buildTemplateBody("Company", node, "Fasttrack.lib.iban", "iban", "validate",
			Arrays.asList(new ConstraintBodyBuilder.Param("rawIban", "string"), new ConstraintBodyBuilder.Param("addressCountryCode", "string")), binding,
			new LinkedHashMap<String, Boolean>(), "WARN", "IBAN is not accepted.");
		ConstraintBodyBuilderTest.check("node entry point", body.contains("export procedure check(record : .Company)"));
		ConstraintBodyBuilderTest.check("calls with both attributes in order", body.contains("iban.validate(record.iban, record.country)"));
		ConstraintBodyBuilderTest.check("no setDependencyToLocalNode with two dependencies", !body.contains("setDependencyToLocalNode"));
		ConstraintBodyBuilderTest.check("depends on the anchor", body.contains("v.addDependencyToModify('/root/Company/iban');"));
		ConstraintBodyBuilderTest.check("depends on the other bound attribute too", body.contains("v.addDependencyToModify('/root/Company/country');"));
	}

	private static void templateBodyErrorSeverityUsesAddError()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("value", new ConstraintBodyBuilder.BindingEntry("value", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "phone"));
		final String body = ConstraintBodyBuilder.buildTemplateBody("Contact", node, "Fasttrack.lib.phone", "phone", "isValid",
			Arrays.asList(new ConstraintBodyBuilder.Param("value", "string")), binding, new LinkedHashMap<String, Boolean>(), "ERROR", "Phone number is not recognised.");
		ConstraintBodyBuilderTest.check("errors, does not warn", body.contains("msg.addError(") && !body.contains("msg.addWarning("));
	}

	private static void templateBodyLiteralArgumentIsEscaped()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban", Arrays.asList("iban"));
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("rawIban", new ConstraintBodyBuilder.BindingEntry("rawIban", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "iban"));
		binding.put("addressCountryCode", new ConstraintBodyBuilder.BindingEntry("addressCountryCode", ConstraintBodyBuilder.BindingEntry.Kind.LITERAL, "it's FR"));
		final String body = ConstraintBodyBuilder.buildTemplateBody("Contact", node, "Fasttrack.lib.iban", "iban", "validate",
			Arrays.asList(new ConstraintBodyBuilder.Param("rawIban", "string"), new ConstraintBodyBuilder.Param("addressCountryCode", "string")), binding,
			new LinkedHashMap<String, Boolean>(), "WARN", "IBAN is not recognised.");
		ConstraintBodyBuilderTest.check("literal string is quoted and escaped", body.contains("iban.validate(record.iban, 'it\\'s FR')"));
	}

	private static void templateBodyRefusesToBeProvidedParameter()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban", Arrays.asList("iban"));
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("rawIban", new ConstraintBodyBuilder.BindingEntry("rawIban", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "iban"));
		binding.put("addressCountryCode", new ConstraintBodyBuilder.BindingEntry("addressCountryCode", ConstraintBodyBuilder.BindingEntry.Kind.TO_BE_PROVIDED, "unclear"));
		boolean threw = false;
		try
		{
			ConstraintBodyBuilder.buildTemplateBody("Contact", node, "Fasttrack.lib.iban", "iban", "validate",
				Arrays.asList(new ConstraintBodyBuilder.Param("rawIban", "string"), new ConstraintBodyBuilder.Param("addressCountryCode", "string")), binding,
				new LinkedHashMap<String, Boolean>(), "WARN", "IBAN is not recognised.");
		}
		catch (final IllegalStateException ex)
		{
			threw = true;
		}
		ConstraintBodyBuilderTest.check("a toBeProvided parameter must never reach template building", threw);
	}

	private static void templateExplanationNodeWarn()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final String explanation = ConstraintBodyBuilder.buildTemplateExplanation("Fasttrack.lib.phone", "isValid", node, "WARN",
			"Checks E.164 syntax or a national numbering plan. Returns false when neither matches.");
		ConstraintBodyBuilderTest.check("names the import and function", explanation.startsWith("Warns when Fasttrack.lib.phone.isValid(phone) answers false."));
		ConstraintBodyBuilderTest.check("echoes the doc's first sentences", explanation.contains("Checks E.164 syntax"));
	}

	private static void templateExplanationErrorSeverity()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final String explanation = ConstraintBodyBuilder.buildTemplateExplanation("Fasttrack.lib.phone", "isValid", node, "ERROR", null);
		ConstraintBodyBuilderTest.check("blocks wording for ERROR", explanation.startsWith("Blocks the record when"));
		ConstraintBodyBuilderTest.check("no trailing doc text when none given", explanation.equals("Blocks the record when Fasttrack.lib.phone.isValid(phone) answers false."));
	}

	private static void checkSubmittedSourceAcceptsGoodNodeBody()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban", Arrays.asList("iban"));
		final String source = "uses core.message as msg;\nuses core.json as json;\nimport Fasttrack.lib.iban as iban;\n\n"
			+ "export procedure check(record : .Contact)\nbegin\n  if isNull(record.iban) then return;\n"
			+ "  const report := iban.validate(record.iban, 'FR');\n  if json.getString(report, 'status') <> 'OK' then\n  begin\n"
			+ "    msg.addWarning('IBAN failed');\n  end\nend\n\nexport procedure setup()\nbegin\nend\n";
		final String violation = ConstraintBodyBuilder.checkSubmittedSource(source, "Fasttrack.lib.iban", "validate", node);
		ConstraintBodyBuilderTest.check("no violation: " + violation, violation == null);
	}

	private static void checkSubmittedSourceRefusesExtraImport()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban", Arrays.asList("iban"));
		final String source = "import Fasttrack.lib.iban as iban;\nimport Fasttrack.lib.phone as phone;\n\n"
			+ "export procedure check(record : .Contact)\nbegin\n  const r := iban.validate(record.iban, 'FR');\nend\n\nexport procedure setup()\nbegin\nend\n";
		final String violation = ConstraintBodyBuilder.checkSubmittedSource(source, "Fasttrack.lib.iban", "validate", node);
		ConstraintBodyBuilderTest.check("refused: an extra import", violation != null && violation.contains("more than one library"));
	}

	private static void checkSubmittedSourceRefusesMissingCall()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban", Arrays.asList("iban"));
		final String source = "import Fasttrack.lib.iban as iban;\n\nexport procedure check(record : .Contact)\nbegin\nend\n\nexport procedure setup()\nbegin\nend\n";
		final String violation = ConstraintBodyBuilder.checkSubmittedSource(source, "Fasttrack.lib.iban", "validate", node);
		ConstraintBodyBuilderTest.check("refused: never calls the function", violation != null && violation.contains("never calls"));
	}

	private static void checkSubmittedSourceRefusesDisallowedUses()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban", Arrays.asList("iban"));
		final String source = "uses core.http as http;\nimport Fasttrack.lib.iban as iban;\n\n"
			+ "export procedure check(record : .Contact)\nbegin\n  const r := iban.validate(record.iban, 'FR');\nend\n\nexport procedure setup()\nbegin\nend\n";
		final String violation = ConstraintBodyBuilder.checkSubmittedSource(source, "Fasttrack.lib.iban", "validate", node);
		ConstraintBodyBuilderTest.check("refused: disallowed uses", violation != null && violation.contains("uses core.http"));
	}

	private static void checkSubmittedSourceRefusesDatabaseAccess()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban", Arrays.asList("iban"));
		final String source = "import Fasttrack.lib.iban as iban;\n\nexport procedure check(record : .Contact)\nbegin\n"
			+ "  const r := iban.validate(record.iban, 'FR');\n  const x := db.findTable('/root/Other');\nend\n\nexport procedure setup()\nbegin\nend\n";
		final String violation = ConstraintBodyBuilder.checkSubmittedSource(source, "Fasttrack.lib.iban", "validate", node);
		ConstraintBodyBuilderTest.check("refused: db access", violation != null && violation.contains("database"));
	}

	private static void checkSubmittedSourceRefusesMissingEntryPointTable()
	{
		final ConstraintBodyBuilder.ScopeDecision table = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.TABLE, null,
			Arrays.asList("iban", "country"));
		final String source = "import Fasttrack.lib.iban as iban;\n\nexport procedure checkRecord(record : .Contact)\nbegin\n"
			+ "  const r := iban.validate(record.iban, record.country);\nend\n\nexport procedure setup()\nbegin\nend\n";
		final String violation = ConstraintBodyBuilder.checkSubmittedSource(source, "Fasttrack.lib.iban", "validate", table);
		ConstraintBodyBuilderTest.check("refused: missing checkTable", violation != null && violation.contains("checkTable"));
	}

	/**
	 * fix/review-assets (2026-09-21), IBAN-body normalisation: the real captured failure - suggestion 6,
	 * {@code Fasttrack.lib.iban.validate}, TABLE scope, binding rawIban&larr;iban / addressCountryCode&larr;
	 * country - the model's {@code submit_constraint_body} argument arrived as ONE physical line, every
	 * intended line break sent as the two characters backslash+n (a double-JSON-escaped payload) instead of
	 * a real line break. {@code import Fasttrack.lib.iban as iban;} was genuinely present in the text, but
	 * every line-based check found nothing because there were no real lines at all. Built with "\\n" (Java
	 * source: a literal backslash then 'n', never the Java escape "\n") between every logical line, exactly
	 * reproducing the double-escape symptom - {@link ConstraintBodyBuilder#checkSubmittedSource} must
	 * normalise this BEFORE checking and accept it.
	 */
	private static void normalizeAcceptsTheRealCapturedDoubleEscapedIbanPayload()
	{
		final ConstraintBodyBuilder.ScopeDecision table = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.TABLE, null,
			Arrays.asList("iban", "country"));
		final String doubleEscaped = "uses core.message as msg;\\nuses core.validation as v;\\nuses core.json as json;\\n\\n"
			+ "import Fasttrack.lib.iban as iban;\\n\\n" + "export procedure checkTable()\\nbegin\\nend\\n\\n"
			+ "export procedure checkRecord(record : .Company)\\nbegin\\n" + "  if isNull(record.iban) then return;\\n"
			+ "  if isNull(record.country) then return;\\n" + "  const r := iban.validate(record.iban, record.country);\\n"
			+ "  if not r then begin msg.addWarning('IBAN is not recognised.'); end;\\n" + "end\\n\\n" + "export procedure setup()\\nbegin\\nend\\n";
		// sanity on the fixture itself: genuinely one physical line, genuinely contains the import
		ConstraintBodyBuilderTest.check("fixture has no real line break", doubleEscaped.indexOf('\n') < 0 && doubleEscaped.indexOf('\r') < 0);
		ConstraintBodyBuilderTest.check("fixture genuinely contains the import text", doubleEscaped.contains("import Fasttrack.lib.iban as iban;"));
		final String violation = ConstraintBodyBuilder.checkSubmittedSource(doubleEscaped, "Fasttrack.lib.iban", "validate", table);
		ConstraintBodyBuilderTest.check("no violation after normalisation: " + violation, violation == null);
	}

	/**
	 * fix/review-assets (2026-09-21): {@code 'it\'s'} (an escaped apostrophe) and {@code 'a\\nb'} (an
	 * escaped backslash followed by a bare 'n' - a literal backslash-n SUBSTRING, never a newline) both sit
	 * INSIDE single-quoted string literals and must survive character-for-character - only the backslash-n
	 * OUTSIDE any string literal (between the two statements) is converted to a real line break.
	 */
	private static void normalizeProtectsEscapesInsideStringLiterals()
	{
		final String source = "const a := 'it\\'s';\\nconst b := 'a\\\\nb';";
		final String normalized = ConstraintBodyBuilder.normalizeEscapedLineBreaks(source, true);
		ConstraintBodyBuilderTest.check("the escaped apostrophe survives untouched", normalized.contains("'it\\'s'"));
		ConstraintBodyBuilderTest.check("the escaped-backslash-then-bare-n survives untouched", normalized.contains("'a\\\\nb'"));
		ConstraintBodyBuilderTest.check("the backslash-n BETWEEN the two statements became a real line break", normalized.contains(";\nconst b"));
		ConstraintBodyBuilderTest.check("exactly one real line break was introduced (the one outside any string)", ConstraintBodyBuilderTest.count(normalized, '\n') == 1);
	}

	/** A source that already has a real line break anywhere is never touched, even if it also has a literal backslash-n elsewhere. */
	private static void normalizeIsANoOpWhenARealLineBreakAlreadyExists()
	{
		final String source = "line one\\nstill one physical fragment\nbut this source already has ONE real break above";
		final String normalized = ConstraintBodyBuilder.normalizeEscapedLineBreaks(source, true);
		ConstraintBodyBuilderTest.check("unchanged (same content)", source.equals(normalized));
	}

	/** A source with no backslash at all is returned as-is (nothing to normalise, and no work done). */
	private static void normalizeIsANoOpWithNoBackslashAtAll()
	{
		final String source = "a single line with nothing special about it";
		ConstraintBodyBuilderTest.check("unchanged", source.equals(ConstraintBodyBuilder.normalizeEscapedLineBreaks(source, true)));
	}

	/**
	 * {@code submit_explanation}'s own use ({@code pProtectStringLiterals=false}): an apostrophe in prose
	 * ("the record's value") must never be mistaken for opening a code string literal - unlike the EBX
	 * Script mode, every backslash-n outside NOTHING (there is no protected region at all in prose mode)
	 * becomes a real line break, apostrophes notwithstanding.
	 */
	private static void normalizeInProseModeNeverTreatsAnApostropheAsAStringDelimiter()
	{
		final String prose = "Warns when the record's iban does not look valid.\\nSee the field's own documentation for details.";
		final String normalized = ConstraintBodyBuilder.normalizeEscapedLineBreaks(prose, false);
		ConstraintBodyBuilderTest.check("both sentences present, joined by a real line break",
			normalized.equals("Warns when the record's iban does not look valid.\nSee the field's own documentation for details."));
	}

	/**
	 * fix/review-assets (2026-09-21): every refusal now quotes what was looked for AND what the source
	 * actually has (line count, first 120 characters) - the real captured defect was a refusal that named
	 * only the rule ("no import line was found") with nothing telling the model its own text, which visibly
	 * contained the import, was being read as something else entirely.
	 */
	private static void checkSubmittedSourceRefusalNamesWhatWasLookedForAndWhatWasSeen()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban", Arrays.asList("iban"));
		final String source = "uses core.http as http;\nimport Fasttrack.lib.iban as iban;\n\n"
			+ "export procedure check(record : .Contact)\nbegin\n  const r := iban.validate(record.iban, 'FR');\nend\n\nexport procedure setup()\nbegin\nend\n";
		final String violation = ConstraintBodyBuilder.checkSubmittedSource(source, "Fasttrack.lib.iban", "validate", node);
		ConstraintBodyBuilderTest.check("names the rule", violation != null && violation.contains("uses core.http"));
		ConstraintBodyBuilderTest.check("names the real line count", violation.contains("line(s)"));
		ConstraintBodyBuilderTest.check("quotes the first characters of the source", violation.contains("first 120 characters"));
	}

	/** A refusal on a source that is STILL a single physical line gets the explicit double-JSON-escape hint. */
	private static void checkSubmittedSourceRefusalHintsAtDoubleEscapingWhenStillOneLine()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban", Arrays.asList("iban"));
		// deliberately not a "\n" pattern at all here - genuinely, permanently one line (not a normalisable case)
		final String source = "export procedure check(record : .Contact) begin end export procedure setup() begin end";
		final String violation = ConstraintBodyBuilder.checkSubmittedSource(source, "Fasttrack.lib.iban", "validate", node);
		ConstraintBodyBuilderTest.check("refused", violation != null);
		ConstraintBodyBuilderTest.check("hints at the double-escape symptom", violation.contains("backslash-n"));
	}

	private static int count(final String pHaystack, final char pChar)
	{
		int n = 0;
		for (int i = 0; i < pHaystack.length(); i++)
		{
			if (pHaystack.charAt(i) == pChar)
			{
				n++;
			}
		}
		return n;
	}

	private static void escapeEbxStringHandlesQuoteAndBackslash()
	{
		ConstraintBodyBuilderTest.check("apostrophe", "it\\'s".equals(ConstraintBodyBuilder.escapeEbxString("it's")));
		ConstraintBodyBuilderTest.check("backslash", "a\\\\b".equals(ConstraintBodyBuilder.escapeEbxString("a\\b")));
	}

	// ------------------------------------------------------------------------------------ message attribution (coordinator review)

	/**
	 * apply-ux fix (2026-09-21): the message reported is now the CALLER's own text (the stored {@code
	 * userMessage}, or {@link ConstraintBodyBuilder#buildFallbackMessage} for a legacy row) - {@code
	 * buildTemplateBody} only escapes whatever it is given, it no longer computes wording itself.
	 */
	private static void templateBodyEmitsExactlyTheCallerSuppliedMessageEscaped()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("value", new ConstraintBodyBuilder.BindingEntry("value", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "phone"));
		final String message = "Phone number isn't recognised: check the format.";
		final String body = ConstraintBodyBuilder.buildTemplateBody("Contact", node, "Fasttrack.lib.phone", "phone", "isValid",
			Arrays.asList(new ConstraintBodyBuilder.Param("value", "string")), binding, new LinkedHashMap<String, Boolean>(), "WARN", message);
		ConstraintBodyBuilderTest.check("the template body emits exactly this message, escaped",
			body.contains("msg.addWarning('" + ConstraintBodyBuilder.escapeEbxString(message) + "')"));
		ConstraintBodyBuilderTest.check("apostrophe properly escaped in the emitted source", body.contains("isn\\'t recognised"));
	}

	private static void buildTemplateBodyRefusesEmptyMessage()
	{
		final ConstraintBodyBuilder.ScopeDecision node = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "phone", Arrays.asList("phone"));
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = new LinkedHashMap<String, ConstraintBodyBuilder.BindingEntry>();
		binding.put("value", new ConstraintBodyBuilder.BindingEntry("value", ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE, "phone"));
		boolean threw = false;
		try
		{
			ConstraintBodyBuilder.buildTemplateBody("Contact", node, "Fasttrack.lib.phone", "phone", "isValid",
				Arrays.asList(new ConstraintBodyBuilder.Param("value", "string")), binding, new LinkedHashMap<String, Boolean>(), "WARN", "   ");
		}
		catch (final IllegalStateException ex)
		{
			threw = true;
		}
		ConstraintBodyBuilderTest.check("never silently emits a script with no real message", threw);
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21), item 4: simplified to {@code "<label>: value not
	 * accepted."} - the EARLIER shape (label + the function's own doc SENTENCE appended) proved unusable to a
	 * business reviewer for a doc like "Never raises: inspect 'valid' first, then 'status'." ({@code
	 * buildFallbackMessage} no longer takes a {@code ScopeDecision} or a doc-sentence argument at all - see
	 * this class's own Javadoc).
	 */
	private static void buildFallbackMessageUsesLabel()
	{
		final String message = ConstraintBodyBuilder.buildFallbackMessage("Phone", "phone");
		ConstraintBodyBuilderTest.check("exact shape: " + message, "Phone: value not accepted.".equals(message));
	}

	private static void buildFallbackMessageHumanisesTheAttributeNameWhenNoLabel()
	{
		final String message = ConstraintBodyBuilder.buildFallbackMessage(null, "postalCode");
		ConstraintBodyBuilderTest.check("humanised, never the bare identifier alone: " + message, "Postal code: value not accepted.".equals(message));
	}

	/** A blank label AND no attribute name still yields a usable, never-blank subject ({@link ConstraintBodyBuilder#humanize}'s own generic fallback). */
	private static void buildFallbackMessageBlankLabelAndNoAttributeFallsBackToGenericSubject()
	{
		final String message = ConstraintBodyBuilder.buildFallbackMessage("   ", null);
		ConstraintBodyBuilderTest.check("generic subject, never blank: " + message, "This field: value not accepted.".equals(message));
	}

	private static void humanizeSplitsCamelCaseAndUnderscores()
	{
		ConstraintBodyBuilderTest.check("camelCase", "Postal code".equals(ConstraintBodyBuilder.humanize("postalCode")));
		ConstraintBodyBuilderTest.check("snake_case", "Legal form".equals(ConstraintBodyBuilder.humanize("legal_form")));
		ConstraintBodyBuilderTest.check("null -> generic, never blank/throw", "This field".equals(ConstraintBodyBuilder.humanize(null)));
		ConstraintBodyBuilderTest.check("empty -> generic", "This field".equals(ConstraintBodyBuilder.humanize("")));
	}

	private static void extractReportedMessageExactLiteral()
	{
		final String source = "import Fasttrack.lib.iban as iban;\n\nexport procedure check(record : .Contact)\nbegin\n"
			+ "  if not iban.validate(record.iban) then\n  begin\n    msg.addWarning('IBAN failed validation.');\n  end\nend\n\nexport procedure setup()\nbegin\nend\n";
		final ConstraintBodyBuilder.MessageAttribution attribution = ConstraintBodyBuilder.extractReportedMessage(source, "WARN");
		ConstraintBodyBuilderTest.check("found", attribution != null);
		ConstraintBodyBuilderTest.check("exact text", "IBAN failed validation.".equals(attribution.text));
		ConstraintBodyBuilderTest.check("marked exact (whole argument was the literal)", attribution.exact);
	}

	private static void extractReportedMessageConcatenationIsPrefixOnly()
	{
		final String source = "export procedure check(record : .Contact)\nbegin\n  msg.addWarning('IBAN failed: ' | record.iban);\nend\n";
		final ConstraintBodyBuilder.MessageAttribution attribution = ConstraintBodyBuilder.extractReportedMessage(source, "WARN");
		ConstraintBodyBuilderTest.check("found", attribution != null);
		ConstraintBodyBuilderTest.check("prefix text", "IBAN failed: ".equals(attribution.text));
		ConstraintBodyBuilderTest.check("marked NOT exact (a concatenation follows)", !attribution.exact);
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21), item 5: the real shape a NODE constraint with an appended
	 * status now produces - {@code msg.addWarning(message | ' (' | status | ')')} - a validation report item
	 * whose rendered text is {@code "The IBAN is not valid (CHECKSUM_FAILED)."} is attributed by a PREFIX
	 * match against the stored source's own literal ({@code "The IBAN is not valid ("}), never an exact
	 * match, since the status suffix is only known at validation time.
	 */
	private static void extractReportedMessageConcatenationWithStatusSuffixIsPrefixMatchOnly()
	{
		final String source = "uses core.message as msg;\nuses core.validation as v;\nuses core.json as json;\nimport Fasttrack.lib.iban as iban;\n\n"
			+ "export procedure check(record : .Supplier)\nbegin\n  if isNull(record.iban) then return;\n"
			+ "  const report := iban.validate(record.iban, record.country);\n  if not json.getBoolean(report, 'valid') then\n  begin\n"
			+ "    msg.addWarning('The IBAN is not valid (' | json.getString(report, 'status') | ').');\n  end\nend\n\n"
			+ "export procedure setup()\nbegin\n  v.addDependencyToModify('/root/Supplier/iban');\n  v.addDependencyToModify('/root/Supplier/country');\nend\n";
		final ConstraintBodyBuilder.MessageAttribution attribution = ConstraintBodyBuilder.extractReportedMessage(source, "WARN");
		ConstraintBodyBuilderTest.check("found", attribution != null);
		ConstraintBodyBuilderTest.check("prefix text: " + attribution.text, "The IBAN is not valid (".equals(attribution.text));
		ConstraintBodyBuilderTest.check("marked NOT exact (the status suffix follows)", !attribution.exact);
		final String rendered = "The IBAN is not valid (CHECKSUM_FAILED).";
		ConstraintBodyBuilderTest.check("the real reported text starts with the extracted prefix", rendered.startsWith(attribution.text));
	}

	private static void extractReportedMessageNothingExtractableWhenNotALiteral()
	{
		final String source = "export procedure check(record : .Contact)\nbegin\n  msg.addWarning(buildMessage(record));\nend\n";
		ConstraintBodyBuilderTest.check("nothing extractable from a function-call argument", ConstraintBodyBuilder.extractReportedMessage(source, "WARN") == null);
	}

	private static void extractReportedMessagePicksTheRightSeverityCall()
	{
		final String source = "export procedure checkRecord(record : .Contact)\nbegin\n  msg.addWarning('a warning');\n  msg.addError('a real error');\nend\n";
		final ConstraintBodyBuilder.MessageAttribution warn = ConstraintBodyBuilder.extractReportedMessage(source, "WARN");
		ConstraintBodyBuilderTest.check("WARN picks addWarning's own literal", warn != null && "a warning".equals(warn.text));
		final ConstraintBodyBuilder.MessageAttribution error = ConstraintBodyBuilder.extractReportedMessage(source, "ERROR");
		ConstraintBodyBuilderTest.check("ERROR picks addError's own literal", error != null && "a real error".equals(error.text));
	}

	private static void extractReportedMessageUnescapesQuoteAndBackslash()
	{
		final String source = "export procedure check(record : .Contact)\nbegin\n  msg.addWarning('it\\'s a backslash: \\\\ here.');\nend\n";
		final ConstraintBodyBuilder.MessageAttribution attribution = ConstraintBodyBuilder.extractReportedMessage(source, "WARN");
		ConstraintBodyBuilderTest.check("found", attribution != null);
		ConstraintBodyBuilderTest.check("unescaped: " + attribution.text, "it's a backslash: \\ here.".equals(attribution.text));
	}

	private static void extractReportedMessageNullOrEmptySourceIsNull()
	{
		ConstraintBodyBuilderTest.check("null source", ConstraintBodyBuilder.extractReportedMessage(null, "WARN") == null);
		ConstraintBodyBuilderTest.check("empty source", ConstraintBodyBuilder.extractReportedMessage("", "WARN") == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
