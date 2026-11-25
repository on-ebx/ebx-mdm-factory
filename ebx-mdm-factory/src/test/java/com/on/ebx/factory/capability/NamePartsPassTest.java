package com.on.ebx.factory.capability;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.agent.LocalTool;
import com.on.ebx.factory.core.PromptData;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Lot M10c (2026-10-18): the one closed question that supplies the first-name/last-name
 * pair to {@link NameKeyRule}. The attributes are called {@code a1}.. on purpose: nothing here reads a name, the answer can only be one of the listed attributes.
 */
public final class NamePartsPassTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		NamePartsPassTest.aValidPairIsRecorded();
		NamePartsPassTest.noPairIsAnAnswerToo();
		NamePartsPassTest.anAttributeOutsideTheCandidatesIsRefusedWithTheList();
		NamePartsPassTest.theSameAttributeTwiceIsRefused();
		NamePartsPassTest.aTruePairWithoutBothNamesIsRefused();
		NamePartsPassTest.aMissingFlagIsRefused();
		NamePartsPassTest.theSameRefusalThreeTimesEndsTheRun();
		NamePartsPassTest.aSecondCallAfterTheAnswerChangesNothing();
		NamePartsPassTest.theToolIsOneCallWithTheCandidatesAsItsVocabulary();
		NamePartsPassTest.thePromptsAreDataFencedAndNameNoDomain();
		System.out.println("NamePartsPassTest OK");
	}

	private static List<CapabilityLlmPass.AttributeInfo> candidates()
	{
		return Arrays.asList(new CapabilityLlmPass.AttributeInfo("a1", "STRING", 80), new CapabilityLlmPass.AttributeInfo("a2", "STRING", 80),
			new CapabilityLlmPass.AttributeInfo("a3", "STRING", 255));
	}

	private static LocalTool.Result call(final NamePartsPass.Session pSession, final String pJson) throws IOException
	{
		return pSession.declare(NamePartsPass.parse(pJson));
	}

	private static void aValidPairIsRecorded() throws IOException
	{
		final NamePartsPass.Session session = new NamePartsPass.Session(NamePartsPassTest.candidates());
		final LocalTool.Result r = NamePartsPassTest.call(session, "{\"hasNamePair\":true,\"firstName\":\"a1\",\"lastName\":\"a2\",\"reason\":\"two short title-case words\"}");
		NamePartsPassTest.check("accepted: " + r.text, !r.isError);
		NamePartsPassTest.check("answered", session.answered());
		NamePartsPassTest.check("the pair", session.parts() != null && "a1".equals(session.parts().firstName) && "a2".equals(session.parts().lastName));
		NamePartsPassTest.check("the reason kept", "two short title-case words".equals(session.reason()));
	}

	private static void noPairIsAnAnswerToo() throws IOException
	{
		final NamePartsPass.Session session = new NamePartsPass.Session(NamePartsPassTest.candidates());
		final LocalTool.Result r = NamePartsPassTest.call(session, "{\"hasNamePair\":false,\"reason\":\"one full-name attribute\"}");
		NamePartsPassTest.check("accepted: " + r.text, !r.isError);
		NamePartsPassTest.check("answered, no pair", session.answered() && session.parts() == null);
	}

	private static void anAttributeOutsideTheCandidatesIsRefusedWithTheList() throws IOException
	{
		final NamePartsPass.Session session = new NamePartsPass.Session(NamePartsPassTest.candidates());
		final LocalTool.Result r = NamePartsPassTest.call(session, "{\"hasNamePair\":true,\"firstName\":\"zz\",\"lastName\":\"a2\"}");
		NamePartsPassTest.check("refused and the candidates named: " + r.text, r.isError && r.text.contains("a1, a2, a3"));
		NamePartsPassTest.check("nothing recorded", !session.answered() && session.parts() == null);
		final LocalTool.Result last = NamePartsPassTest.call(session, "{\"hasNamePair\":true,\"firstName\":\"a1\",\"lastName\":\"zz\"}");
		NamePartsPassTest.check("the last name is checked too: " + last.text, last.isError && !session.answered());
		final LocalTool.Result fixed = NamePartsPassTest.call(session, "{\"hasNamePair\":true,\"firstName\":\"a3\",\"lastName\":\"a1\"}");
		NamePartsPassTest.check("a corrected call is accepted: " + fixed.text, !fixed.isError && session.parts() != null && "a3".equals(session.parts().firstName));
	}

	private static void theSameAttributeTwiceIsRefused() throws IOException
	{
		final NamePartsPass.Session session = new NamePartsPass.Session(NamePartsPassTest.candidates());
		final LocalTool.Result r = NamePartsPassTest.call(session, "{\"hasNamePair\":true,\"firstName\":\"a1\",\"lastName\":\"a1\"}");
		NamePartsPassTest.check("refused: " + r.text, r.isError && r.text.contains("two different attributes") && !session.answered());
	}

	private static void aTruePairWithoutBothNamesIsRefused() throws IOException
	{
		final NamePartsPass.Session session = new NamePartsPass.Session(NamePartsPassTest.candidates());
		final LocalTool.Result r = NamePartsPassTest.call(session, "{\"hasNamePair\":true,\"firstName\":\"a1\"}");
		NamePartsPassTest.check("refused: " + r.text, r.isError && r.text.contains("both") && !session.answered());
		final LocalTool.Result blank = NamePartsPassTest.call(session, "{\"hasNamePair\":true,\"firstName\":\"a1\",\"lastName\":\"  \"}");
		NamePartsPassTest.check("a blank is missing too: " + blank.text, blank.isError && !session.answered());
	}

	private static void aMissingFlagIsRefused() throws IOException
	{
		final NamePartsPass.Session session = new NamePartsPass.Session(NamePartsPassTest.candidates());
		final LocalTool.Result r = NamePartsPassTest.call(session, "{\"firstName\":\"a1\",\"lastName\":\"a2\"}");
		NamePartsPassTest.check("refused: " + r.text, r.isError && !session.answered());
		final LocalTool.Result text = NamePartsPassTest.call(session, "{\"hasNamePair\":\"yes\",\"firstName\":\"a1\",\"lastName\":\"a2\"}");
		NamePartsPassTest.check("a text is not a boolean: " + text.text, text.isError && !session.answered());
	}

	private static void theSameRefusalThreeTimesEndsTheRun() throws IOException
	{
		final NamePartsPass.Session session = new NamePartsPass.Session(NamePartsPassTest.candidates());
		final String bad = "{\"hasNamePair\":true,\"firstName\":\"zz\",\"lastName\":\"a2\"}";
		NamePartsPassTest.call(session, bad);
		NamePartsPassTest.call(session, bad);
		final LocalTool.Result third = NamePartsPassTest.call(session, bad);
		NamePartsPassTest.check("the third ends it: " + third.text, third.isError && third.text.contains("3 times") && session.stopped());
		final LocalTool.Result after = NamePartsPassTest.call(session, "{\"hasNamePair\":true,\"firstName\":\"a1\",\"lastName\":\"a2\"}");
		NamePartsPassTest.check("nothing is recorded once stopped: " + after.text, after.isError && !session.answered() && session.parts() == null);
		NamePartsPassTest.check("the last refusal is kept for the outcome", !session.lastRefusal().isEmpty());
	}

	private static void aSecondCallAfterTheAnswerChangesNothing() throws IOException
	{
		final NamePartsPass.Session session = new NamePartsPass.Session(NamePartsPassTest.candidates());
		NamePartsPassTest.call(session, "{\"hasNamePair\":true,\"firstName\":\"a1\",\"lastName\":\"a2\"}");
		final LocalTool.Result again = NamePartsPassTest.call(session, "{\"hasNamePair\":true,\"firstName\":\"a3\",\"lastName\":\"a1\"}");
		NamePartsPassTest.check("told it is recorded: " + again.text, !again.isError && again.text.contains("Already recorded"));
		NamePartsPassTest.check("the first answer stands", "a1".equals(session.parts().firstName) && "a2".equals(session.parts().lastName));
	}

	private static void theToolIsOneCallWithTheCandidatesAsItsVocabulary() throws IOException
	{
		final NamePartsPass.Session session = new NamePartsPass.Session(NamePartsPassTest.candidates());
		NamePartsPassTest.check("a tool group", session.toolGroup() != null);
		NamePartsPassTest.check("candidates exposed", session.candidateNames().equals(new java.util.LinkedHashSet<String>(Arrays.asList("a1", "a2", "a3"))));
	}

	private static void thePromptsAreDataFencedAndNameNoDomain()
	{
		final CapabilityLlmPass.EntityInfo entity = new CapabilityLlmPass.EntityInfo("Thing", "A thing", true, NamePartsPassTest.candidates(), null);
		final Map<String, String> labels = new LinkedHashMap<String, String>();
		labels.put("a1", "Given label");
		labels.put("a2", "a2");
		final List<CapabilityLlmPass.AttributeInfo> candidates = Arrays.asList(
			new CapabilityLlmPass.AttributeInfo("a1", "STRING", 80, Long.valueOf(500), Double.valueOf(99.5), Long.valueOf(120),
				Arrays.asList(new CapabilityLlmPass.ShapeCount("Aaaa", 400), new CapabilityLlmPass.ShapeCount("AAAA", 90)), null, null),
			new CapabilityLlmPass.AttributeInfo("a2", "STRING", 80));
		final String user = NamePartsPass.userPrompt(entity, candidates, labels);
		NamePartsPassTest.check("fenced as data", PromptData.isFenced(user) && PromptData.onlyInsideFences(user, "a1"));
		NamePartsPassTest.check("the label shown, the redundant one not repeated", user.contains("(label: Given label)") && !user.contains("(label: a2)"));
		NamePartsPassTest.check("the shapes shown, never a value", user.contains("Aaaa (400)") && user.contains("fill rate 99.5%"));
		NamePartsPassTest.check("the tool named", user.contains(NamePartsPass.TOOL));
		final String system = NamePartsPass.systemPrompt();
		NamePartsPassTest.check("the data notice", system.endsWith(PromptData.SYSTEM_NOTICE));
		NamePartsPassTest.check("a wrong pair is worse than none", system.contains("hasNamePair=false"));
		final String ownText = system.substring(0, system.length() - PromptData.SYSTEM_NOTICE.length());
		for (final String domainWord : new String[] { "prenom", "Vorname", "customer", "email", "phone" })
		{
			NamePartsPassTest.check("no domain word '" + domainWord + "' in the prompt's own text", !ownText.contains(domainWord));
		}
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
