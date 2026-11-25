package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.factory.matching.KeyFacts;

/**
 * JUnit-free: run the main, a failure throws. Lot M17 (2026-10-18, {@code docs/MATCHING.md} &sect;69): the 12th live run spent its token budget proposing one-node plans with no clustering
 * field for three reference tables fed by a single source. The model is now given the facts (how many source systems feed an entity, and for one source whether two of its rows share an
 * exact key), the entities the facts mark as needing no plan come last in one line, and a plan proposed anyway says in its rationale what the facts had shown. The Factory decides nothing.
 */
public final class ReferenceTablesNeedNoPlanTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		ReferenceTablesNeedNoPlanTest.oneSourceAndNoSharedKeyIsStatedWithTheConsequence();
		ReferenceTablesNeedNoPlanTest.aKeyAllOfWhoseValuesAreUniqueCountsAsNotShared();
		ReferenceTablesNeedNoPlanTest.aKeySharedWithinTheSourceIsStatedAndCallsForNoSkip();
		ReferenceTablesNeedNoPlanTest.severalSourcesAreCountedAndNothingIsConcluded();
		ReferenceTablesNeedNoPlanTest.aSampleOrUnknownSourcesStateNothing();
		ReferenceTablesNeedNoPlanTest.theEntitiesTheFactsMarkComeLastAndAreNamedInOneLine();
		ReferenceTablesNeedNoPlanTest.noStartingPlanIsOfferedAndSkipEntityIsAccepted();
		ReferenceTablesNeedNoPlanTest.aPlanProposedAnywayIsAcceptedAndSaysWhatTheFactsShowed();
		System.out.println("ReferenceTablesNeedNoPlanTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	private static MatchPlanLlmPass.AllowedVocabulary vocabulary()
	{
		return new MatchPlanLlmPass.AllowedVocabulary(
			Arrays.asList("ebx-addon-mame@exact", "ebx-addon-mame@exactFullText", "ebx-addon-mame@jarowinkler"), Arrays.asList("MATCH", "NOT_MATCH"),
			Arrays.asList("mostCompleted", "lastUpdate", "mostTrustedSource"));
	}

	private static MatchPlanLlmPass.AttributeInfo attribute(final String pName, final String pRole, final int pDistinct)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 100, null, Double.valueOf(1.0), "EXACT", Integer.valueOf(pDistinct), "EXACT", null, null, null, null, true, pRole,
			Collections.<String>emptyList(), Collections.<String>emptyList());
	}

	private static Map<String, Long> bySystem(final Object... pPairs)
	{
		final Map<String, Long> out = new LinkedHashMap<String, Long>();
		for (int i = 0; i < pPairs.length; i += 2)
		{
			out.put((String) pPairs[i], Long.valueOf(((Integer) pPairs[i + 1]).longValue()));
		}
		return out;
	}

	/**
	 * A table fed by STORES only. The profile (attribute) says the key repeats (a third of the rows are distinct) - what lets a proposal on it pass the pass's own fill and distinctness
	 * checks - while the key facts count {@code pRows} rows of which {@code pSharedPairs} pairs share the key: the model's call stays possible, the facts are what the checklist states.
	 */
	private static MatchPlanLlmPass.EntityInfo store(final String pName, final int pRows, final boolean pSampled, final int pSharedPairs)
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(ReferenceTablesNeedNoPlanTest.attribute("storeKey", MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS, pRows / 3));
		final MatchPlanLlmPass.EntityInfo entity = new MatchPlanLlmPass.EntityInfo(pName, pName, pRows, attributes, null, null, ReferenceTablesNeedNoPlanTest.bySystem("STORES", Integer.valueOf(pRows)));
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		for (int i = 0; i < pRows; i++)
		{
			// the first (pSharedPairs + 1) rows... one value shared by exactly two rows when pSharedPairs is 1: row 1 repeats row 0
			final int value = pSharedPairs > 0 && i == 1 ? 0 : i;
			rows.add(new KeyFacts.Row("STORES", new String[] { "v" + value }));
		}
		entity.keyFacts = KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("storeKey", "storeKey", false)), pSampled);
		return entity;
	}

	/** An entity with a unique business key only: nothing for the key facts to measure (every filled value is unique), source counts from the profile. */
	private static MatchPlanLlmPass.EntityInfo uniqueOnly(final String pName, final int pRows)
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(ReferenceTablesNeedNoPlanTest.attribute("visitCode", MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS, pRows));
		attributes.add(ReferenceTablesNeedNoPlanTest.attribute("sourceId", MatchPlanLlmPass.AttributeInfo.ROLE_SOURCE_KEY, pRows));
		return new MatchPlanLlmPass.EntityInfo(pName, pName, pRows, attributes, null, null, ReferenceTablesNeedNoPlanTest.bySystem("STORES", Integer.valueOf(pRows)));
	}

	private static MatchPlanLlmPass.EntityInfo severalSources()
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(ReferenceTablesNeedNoPlanTest.attribute("emailKey", MatchPlanLlmPass.AttributeInfo.ROLE_MATCH_KEY, 150));
		final MatchPlanLlmPass.EntityInfo entity = new MatchPlanLlmPass.EntityInfo("Client", "Client", 534, attributes, null, null,
			ReferenceTablesNeedNoPlanTest.bySystem("ECOM", Integer.valueOf(160), "LOYALTY", Integer.valueOf(140), "STORES", Integer.valueOf(234)));
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		for (int i = 0; i < 534; i++)
		{
			rows.add(new KeyFacts.Row(i < 160 ? "ECOM" : i < 300 ? "LOYALTY" : "STORES", new String[] { "e" + (i % 150) }));
		}
		entity.keyFacts = KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("emailKey", "emailKey", false)), false);
		return entity;
	}

	// ------------------------------------------------------------------------------------------------------ tests

	private static void oneSourceAndNoSharedKeyIsStatedWithTheConsequence()
	{
		final MatchPlanLlmPass.EntityInfo entity = ReferenceTablesNeedNoPlanTest.store("StoreRef", 48, false, 0);
		final String block = MatchPlanChecklist.entityBlock(entity);
		System.out.println("--- one source, no shared key ---\n" + block);
		check(block.contains("StoreRef is fed by one source system only (STORES, 48 rows); no key is shared by two rows of that source (storeKey: 0 pair(s) of rows share it) -> no duplicate can exist: "
			+ "call skip_entity with that reason instead of proposing a plan; propose a plan only if a key IS shared within the source (then say which)."), "the sentence: " + block);
		check(SingleSourceFacts.of(entity).noPlanNeeded(), "marked as needing no plan");
		check(SingleSourceFacts.of(entity).sourceText().equals("STORES, 48 rows"), "the source text");
	}

	private static void aKeyAllOfWhoseValuesAreUniqueCountsAsNotShared()
	{
		final MatchPlanLlmPass.EntityInfo entity = ReferenceTablesNeedNoPlanTest.uniqueOnly("VisiteMagasin", 48);
		check(entity.keyFacts == null, "fixture: nothing for the key facts to measure");
		check(KeyFactsSelection.uniqueKeys(entity).equals(Arrays.asList("visitCode")), "the unique key is found, the source key never is: " + KeyFactsSelection.uniqueKeys(entity));
		final String block = MatchPlanChecklist.entityBlock(entity);
		check(block.contains("VisiteMagasin is fed by one source system only (STORES, 48 rows); no key is shared by two rows of that source (visitCode: 0 pair(s) of rows share it) -> no duplicate can exist"),
			"a unique key is a measured absence of sharing: " + block);
		// an entity with no key at all: the source is stated, nothing is concluded
		final List<MatchPlanLlmPass.AttributeInfo> none = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		none.add(ReferenceTablesNeedNoPlanTest.attribute("sourceId", MatchPlanLlmPass.AttributeInfo.ROLE_SOURCE_KEY, 48));
		final MatchPlanLlmPass.EntityInfo bare = new MatchPlanLlmPass.EntityInfo("Bare", "Bare", 48, none, null, null, ReferenceTablesNeedNoPlanTest.bySystem("STORES", Integer.valueOf(48)));
		final String bareBlock = MatchPlanChecklist.entityBlock(bare);
		check(bareBlock.contains("Bare is fed by one source system only (STORES, 48 rows); no exact key of this entity could be measured, so nothing is concluded") && !bareBlock.contains("skip_entity"),
			"no key measured: no advice to skip: " + bareBlock);
		check(!SingleSourceFacts.of(bare).noPlanNeeded(), "not marked");
	}

	private static void aKeySharedWithinTheSourceIsStatedAndCallsForNoSkip()
	{
		final MatchPlanLlmPass.EntityInfo entity = ReferenceTablesNeedNoPlanTest.store("StoreRef", 48, false, 1);
		final String block = MatchPlanChecklist.entityBlock(entity);
		check(block.contains("StoreRef is fed by one source system only (STORES, 48 rows); keys shared by two or more rows of that source: storeKey (1 pair(s) of rows) -> duplicates inside that source exist on these keys"),
			"the shared key is named: " + block);
		check(!block.contains("call skip_entity"), "no advice to skip: " + block);
		check(!SingleSourceFacts.of(entity).noPlanNeeded(), "not marked");
	}

	private static void severalSourcesAreCountedAndNothingIsConcluded()
	{
		final MatchPlanLlmPass.EntityInfo entity = ReferenceTablesNeedNoPlanTest.severalSources();
		final String block = MatchPlanChecklist.entityBlock(entity);
		check(block.contains("Source systems feeding Client: 3 (ECOM 160, LOYALTY 140, STORES 234; 534 rows in all)."), "sources and rows: " + block);
		check(!block.contains("skip_entity") && !SingleSourceFacts.of(entity).noPlanNeeded(), "no advice for several sources: " + block);
	}

	private static void aSampleOrUnknownSourcesStateNothing()
	{
		final MatchPlanLlmPass.EntityInfo sampled = ReferenceTablesNeedNoPlanTest.store("Big", 48, true, 0);
		sampled.keyFacts = KeyFacts.compute(Arrays.asList(new KeyFacts.Row("STORES", new String[] { "a" }), new KeyFacts.Row("STORES", new String[] { "b" })),
			Arrays.asList(new KeyFacts.KeySpec("storeKey", "storeKey", false)), true);
		check(SingleSourceFacts.of(sampled) == null && MatchPlanChecklist.entityBlock(sampled).isEmpty(), "lower bounds claim nothing: " + MatchPlanChecklist.entityBlock(sampled));
		final MatchPlanLlmPass.EntityInfo unknown = new MatchPlanLlmPass.EntityInfo("Unknown", "Unknown", 10, new ArrayList<MatchPlanLlmPass.AttributeInfo>());
		check(SingleSourceFacts.of(unknown) == null && MatchPlanChecklist.entityBlock(unknown).isEmpty(), "no source counts: nothing said, the prompt is what it was");
	}

	private static MatchPlanLlmPass.ProjectInfo project(final MatchPlanLlmPass.EntityInfo... pEntities)
	{
		return new MatchPlanLlmPass.ProjectInfo("E2E", "e2e", new ArrayList<MatchPlanLlmPass.EntityInfo>(Arrays.asList(pEntities)), new ArrayList<String>(), null, false);
	}

	private static void theEntitiesTheFactsMarkComeLastAndAreNamedInOneLine()
	{
		// the order of the project: the references first, as the 12th run listed them; the real entity must come first in the prompt
		final String prompt = MatchPlanLlmPass.userPrompt(ReferenceTablesNeedNoPlanTest.project(ReferenceTablesNeedNoPlanTest.uniqueOnly("VisiteMagasin", 48),
			ReferenceTablesNeedNoPlanTest.store("PaysClient", 30, false, 0), ReferenceTablesNeedNoPlanTest.severalSources(), ReferenceTablesNeedNoPlanTest.store("SuiviClient", 20, false, 0)));
		final int client = prompt.indexOf("\n- Client (label");
		final int visite = prompt.indexOf("\n- VisiteMagasin (label");
		final int pays = prompt.indexOf("\n- PaysClient (label");
		final int suivi = prompt.indexOf("\n- SuiviClient (label");
		check(client > 0 && client < visite && visite < pays && pays < suivi, "the entity that needs a plan first, the others after it in their own order: " + client + " " + visite + " " + pays + " " + suivi);
		final String line = "Entities the facts mark as needing no plan: VisiteMagasin, PaysClient, SuiviClient (one source, no shared key) - skip them with skip_entity, after the entities above.";
		check(prompt.contains(line), "the one line: " + prompt.substring(prompt.indexOf("Entities the facts mark") < 0 ? 0 : prompt.indexOf("Entities the facts mark")));
		check(prompt.indexOf(line) > suivi && prompt.indexOf(line) < prompt.indexOf("Propose a matching plan per entity"), "after the list, before the closing instruction");
		// nothing marked: no line, the order is the project's
		final String plain = MatchPlanLlmPass.userPrompt(ReferenceTablesNeedNoPlanTest.project(ReferenceTablesNeedNoPlanTest.store("StoreRef", 48, false, 1), ReferenceTablesNeedNoPlanTest.severalSources()));
		check(!plain.contains("Entities the facts mark as needing no plan") && plain.indexOf("\n- StoreRef (label") < plain.indexOf("\n- Client (label"), "no line, same order when nothing is marked");
		check(MatchPlanLlmPass.systemPrompt().contains("fed by one source system only and no key is shared by two rows of that source"), "the system prompt says what skip_entity is for here");
	}

	private static MatchPlanLlmPass.Session session(final MatchPlanLlmPass.EntityInfo... pEntities)
	{
		final Map<String, MatchPlanLlmPass.EntityInfo> entities = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		for (final MatchPlanLlmPass.EntityInfo entity : pEntities)
		{
			entities.put(entity.name, entity);
		}
		return new MatchPlanLlmPass.Session(entities, new LinkedHashMap<String, String>(), new ArrayList<MatchPlanLlmPass.ExistingPlan>(), ReferenceTablesNeedNoPlanTest.vocabulary());
	}

	private static void noStartingPlanIsOfferedAndSkipEntityIsAccepted() throws Exception
	{
		final MatchPlanLlmPass.Session session = ReferenceTablesNeedNoPlanTest.session(ReferenceTablesNeedNoPlanTest.store("StoreRef", 48, false, 0), ReferenceTablesNeedNoPlanTest.severalSources());
		session.prepareStartingPlans();
		check(session.startingPlanReasons().get("StoreRef").startsWith("the facts mark it as needing no plan"), "no starting plan to resubmit for it: " + session.startingPlanReasons());
		final Map<String, String> marked = session.noPlanNeeded();
		check(marked.size() == 1 && "STORES, 48 rows".equals(marked.get("StoreRef")), "the pass names it for the result: " + marked);
		final LocalTool.Result skipped = session.skipEntity(ReferenceTablesNeedNoPlanTest.MAPPER.readTree("{\"entity\":\"StoreRef\",\"reason\":\"one source, no shared key\"}"));
		check(!skipped.isError && session.notes().contains("StoreRef skipped: one source, no shared key"), "skip_entity records it: " + skipped.text + " " + session.notes());
	}

	private static void aPlanProposedAnywayIsAcceptedAndSaysWhatTheFactsShowed() throws Exception
	{
		// the model's call: the key repeats in the profile (it believes) although the facts count no pair - the plan is accepted, the stored rationale carries the sentence
		final MatchPlanLlmPass.Session session = ReferenceTablesNeedNoPlanTest.session(ReferenceTablesNeedNoPlanTest.store("StoreRef", 48, false, 0));
		final String call = "{\"entity\":\"StoreRef\",\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"storeKey\",\"algorithm\":\"ebx-addon-mame@exact\","
			+ "\"minimumScore\":100,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],"
			+ "\"clusteringFields\":[\"storeKey\"],\"rationale\":\"the key may repeat.\",\"confidence\":60}";
		final LocalTool.Result result = session.proposeMatchPlan(ReferenceTablesNeedNoPlanTest.MAPPER.readTree(call));
		System.out.println("--- proposal on a no-plan entity ---\n" + result.text);
		check(!result.isError, "the model's call is accepted: " + result.text);
		final String rationale = session.createdPlans().get(0).rationale;
		check(rationale.contains("the key may repeat.") && rationale.contains("The facts showed one source and no shared key (STORES, 48 rows)."), "the sentence in the stored rationale: " + rationale);
		// an entity with sharing gets no such sentence
		final MatchPlanLlmPass.Session shared = ReferenceTablesNeedNoPlanTest.session(ReferenceTablesNeedNoPlanTest.store("StoreRef", 48, false, 1));
		final LocalTool.Result ok = shared.proposeMatchPlan(ReferenceTablesNeedNoPlanTest.MAPPER.readTree(call));
		check(!ok.isError && !shared.createdPlans().get(0).rationale.contains("The facts showed one source"), "a shared key: no sentence: " + ok.text);
	}
}
