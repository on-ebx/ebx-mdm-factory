package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class MameAddonDecisionTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Verbatim, captured live by the coordinator on Companies_Master, 2026-09-21 - docs/MATCHING.md sec 5.1. */
	private static final String REAL_UNDECLARED_RESPONSE = "{\"dataModelName\":\"Companies_Master\",\"operation\":\"list_addons\",\"found\":true,\"addons\":[],"
		+ "\"availableAddons\":[{\"label\":\"Digital Asset Manager\",\"name\":\"ebx-addon-dama\"},"
		+ "{\"label\":\"Match and Merge\",\"name\":\"ebx-addon-mame\"}],\"code\":null,\"message\":null,\"nextStep\":null}";

	public static void main(final String[] pArgs) throws Exception
	{
		MameAddonDecisionTest.realUndeclaredResponse_declaresEbxAddonMame();
		MameAddonDecisionTest.declaredAndActivated_alreadyDeclared_neverCallsAddAddon();
		MameAddonDecisionTest.declaredWithActivatedFalse_notAvailable();
		MameAddonDecisionTest.declaredWithNoActivatedField_treatedAsAlreadyDeclared();
		MameAddonDecisionTest.notInAvailableCatalogueAtAll_notAvailable();
		MameAddonDecisionTest.nullResponse_unreadable();
		MameAddonDecisionTest.foundFalse_unreadable();
		MameAddonDecisionTest.missingAddonsArray_unreadable();
		System.out.println("MameAddonDecisionTest OK");
	}

	private static void realUndeclaredResponse_declaresEbxAddonMame() throws Exception
	{
		final MameAddonDecision.Decision decision = MameAddonDecision.decide(MameAddonDecisionTest.parse(MameAddonDecisionTest.REAL_UNDECLARED_RESPONSE));
		MameAddonDecisionTest.check("action DECLARE", decision.action == MameAddonDecision.Action.DECLARE);
		MameAddonDecisionTest.check("name is ebx-addon-mame", "ebx-addon-mame".equals(decision.name));
		MameAddonDecisionTest.check("no message", decision.message == null);
	}

	private static void declaredAndActivated_alreadyDeclared_neverCallsAddAddon() throws Exception
	{
		final String json = "{\"found\":true,\"addons\":[{\"label\":\"Match and Merge\",\"name\":\"ebx-addon-mame\",\"version\":\"6.2.2\",\"activated\":true}],"
			+ "\"availableAddons\":[{\"label\":\"Match and Merge\",\"name\":\"ebx-addon-mame\"}]}";
		final MameAddonDecision.Decision decision = MameAddonDecision.decide(MameAddonDecisionTest.parse(json));
		MameAddonDecisionTest.check("action ALREADY_DECLARED", decision.action == MameAddonDecision.Action.ALREADY_DECLARED);
		MameAddonDecisionTest.check("no name (never call add_addon)", decision.name == null);
	}

	private static void declaredWithActivatedFalse_notAvailable() throws Exception
	{
		final String json = "{\"found\":true,\"addons\":[{\"label\":\"Match and Merge\",\"name\":\"ebx-addon-mame\",\"activated\":false}],\"availableAddons\":[]}";
		final MameAddonDecision.Decision decision = MameAddonDecision.decide(MameAddonDecisionTest.parse(json));
		MameAddonDecisionTest.check("action NOT_AVAILABLE", decision.action == MameAddonDecision.Action.NOT_AVAILABLE);
		MameAddonDecisionTest.check("message points at Administration > Add-ons", decision.message.contains("Administration > Add-ons"));
	}

	/** Declared but the entry carries no 'activated' field at all: read as unknown, not false - never refused on that basis alone. */
	private static void declaredWithNoActivatedField_treatedAsAlreadyDeclared() throws Exception
	{
		final String json = "{\"found\":true,\"addons\":[{\"label\":\"Match and Merge\",\"name\":\"ebx-addon-mame\"}],\"availableAddons\":[]}";
		final MameAddonDecision.Decision decision = MameAddonDecision.decide(MameAddonDecisionTest.parse(json));
		MameAddonDecisionTest.check("action ALREADY_DECLARED", decision.action == MameAddonDecision.Action.ALREADY_DECLARED);
	}

	private static void notInAvailableCatalogueAtAll_notAvailable() throws Exception
	{
		final String json = "{\"found\":true,\"addons\":[],\"availableAddons\":[{\"label\":\"Digital Asset Manager\",\"name\":\"ebx-addon-dama\"}]}";
		final MameAddonDecision.Decision decision = MameAddonDecision.decide(MameAddonDecisionTest.parse(json));
		MameAddonDecisionTest.check("action NOT_AVAILABLE", decision.action == MameAddonDecision.Action.NOT_AVAILABLE);
		MameAddonDecisionTest.check("message names the add-on", decision.message.contains("ebx-addon-mame"));
	}

	private static void nullResponse_unreadable()
	{
		final MameAddonDecision.Decision decision = MameAddonDecision.decide(null);
		MameAddonDecisionTest.check("action UNREADABLE", decision.action == MameAddonDecision.Action.UNREADABLE);
	}

	private static void foundFalse_unreadable() throws Exception
	{
		final MameAddonDecision.Decision decision = MameAddonDecision.decide(MameAddonDecisionTest.parse("{\"found\":false,\"code\":\"MODEL_NOT_FOUND\"}"));
		MameAddonDecisionTest.check("action UNREADABLE", decision.action == MameAddonDecision.Action.UNREADABLE);
	}

	private static void missingAddonsArray_unreadable() throws Exception
	{
		final MameAddonDecision.Decision decision = MameAddonDecision.decide(MameAddonDecisionTest.parse("{\"found\":true}"));
		MameAddonDecisionTest.check("action UNREADABLE", decision.action == MameAddonDecision.Action.UNREADABLE);
	}

	private static JsonNode parse(final String pJson) throws Exception
	{
		return MameAddonDecisionTest.MAPPER.readTree(pJson);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
