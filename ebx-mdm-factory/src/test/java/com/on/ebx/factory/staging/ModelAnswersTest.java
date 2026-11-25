package com.on.ebx.factory.staging;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** JUnit-free: run the main, a failure throws. Desk lot L3b - what the model answered, per mapping, kept on the job's params. */
public final class ModelAnswersTest
{
	public static void main(final String[] pArgs)
	{
		ModelAnswersTest.theDemoBalaCase();
		ModelAnswersTest.theRecordSurvivesTheParamsRoundTrip();
		ModelAnswersTest.askingStopsOnlyWhenEveryFlaggedMappingWasSubmitted();
		ModelAnswersTest.theParamsColumnIsNeverOverrun();
		ModelAnswersTest.garbageIsNoAnswer();
		System.out.println("ModelAnswersTest OK");
	}

	private static void theDemoBalaCase()
	{
		// SAP (5779) got an alternative, Salesforce (5780) was judged right
		final List<ModelAnswers.Answer> answers = ModelAnswers.answers(Arrays.asList("5779", "5780"), Arrays.asList("5779"), "  MAILING is\n right for Salesforce ");
		check("two answers", answers.size() == 2);
		check("SAP: proposed, no note", !answers.get(0).kept && answers.get(0).note.isEmpty());
		check("Salesforce: kept, note compacted", answers.get(1).kept && "MAILING is right for Salesforce".equals(answers.get(1).note));
		check("no note given: none stored", ModelAnswers.answers(Arrays.asList("1"), Collections.<String>emptyList(), null).get(0).note.isEmpty());
	}

	private static void theRecordSurvivesTheParamsRoundTrip()
	{
		final String start = "{\"projectCode\":\"DemoBala\",\"entityId\":\"439\",\"locale\":\"en\"}";
		final String end = ModelAnswers.withAnswers(start, ModelAnswers.answers(Arrays.asList("5779", "5780"), Arrays.asList("5779"), "keep it"));
		check("the start parameters are kept", end.contains("\"entityId\":\"439\"") && end.contains("\"projectCode\":\"DemoBala\""));
		final Map<String, ModelAnswers.Answer> read = ModelAnswers.parse(end);
		check("both read back", read.size() == 2 && !read.get("5779").kept && read.get("5780").kept && "keep it".equals(read.get("5780").note));
		check("params without answers: nothing", ModelAnswers.parse(start).isEmpty() && ModelAnswers.parse(null).isEmpty() && ModelAnswers.parse("").isEmpty());
	}

	private static void askingStopsOnlyWhenEveryFlaggedMappingWasSubmitted()
	{
		final Map<String, ModelAnswers.Answer> read = ModelAnswers.parse(ModelAnswers.withAnswers("{}", ModelAnswers.answers(Arrays.asList("5779", "5780"), Arrays.asList("5779"), null)));
		check("both flagged mappings were submitted: no ask", !ModelAnswers.needsAsk(Arrays.asList("5780"), read) && !ModelAnswers.needsAsk(Arrays.asList("5779", "5780"), read));
		check("a mapping never submitted still needs asking", ModelAnswers.needsAsk(Arrays.asList("5780", "9999"), read));
		check("a mapping changed since (a new id) needs asking", ModelAnswers.needsAsk(Arrays.asList("6001"), read));
		check("never asked: ask", ModelAnswers.needsAsk(Arrays.asList("5780"), Collections.<String, ModelAnswers.Answer>emptyMap()) && ModelAnswers.needsAsk(Arrays.asList("5780"), null));
		check("nothing flagged: nothing to ask", !ModelAnswers.needsAsk(Collections.<String>emptyList(), read));
	}

	private static void theParamsColumnIsNeverOverrun()
	{
		final StringBuilder note = new StringBuilder();
		for (int i = 0; i < 400; i++)
		{
			note.append("word ");
		}
		final java.util.List<String> ids = new java.util.ArrayList<String>();
		for (int i = 0; i < 40; i++)
		{
			ids.add("m" + i);
		}
		final List<ModelAnswers.Answer> many = ModelAnswers.answers(ids, Collections.<String>emptyList(), note.toString());
		check("a note is cut", many.get(0).note.length() <= ModelAnswers.NOTE_MAX);
		final String text = ModelAnswers.withAnswers("{\"entityId\":\"1\"}", many);
		check("fits the 4000-character column: " + text.length(), text.length() <= ModelAnswers.PARAMS_MAX);
		check("notes dropped before ids", ModelAnswers.parse(text).size() == 40);
		final java.util.List<String> huge = new java.util.ArrayList<String>();
		for (int i = 0; i < 2000; i++)
		{
			huge.add("mapping-" + i);
		}
		final String cut = ModelAnswers.withAnswers("{\"entityId\":\"1\"}", ModelAnswers.answers(huge, Collections.<String>emptyList(), null));
		check("too many to store: the start parameters only, never an overrun", cut.length() <= ModelAnswers.PARAMS_MAX && ModelAnswers.parse(cut).isEmpty());
	}

	private static void garbageIsNoAnswer()
	{
		check("not JSON", ModelAnswers.parse("not json").isEmpty());
		check("answers of the wrong shape", ModelAnswers.parse("{\"answers\":[{\"x\":1},{\"m\":\"\"}]}").isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
