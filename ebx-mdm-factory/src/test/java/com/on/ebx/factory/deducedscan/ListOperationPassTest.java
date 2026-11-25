package com.on.ebx.factory.deducedscan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;

/** JUnit-free: run the main, a failure throws. Exercises {@link ListOperationPass.Session}'s tool handler directly - no real LLM call. */
public final class ListOperationPassTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final ObjectMapper mapper = new ObjectMapper();
		final List<OperationCatalog.OperationInfo> readOnly = new ArrayList<OperationCatalog.OperationInfo>();
		readOnly.add(new OperationCatalog.OperationInfo("list_contacts", true, Arrays.asList("after")));
		readOnly.add(new OperationCatalog.OperationInfo("list_companies", true, java.util.Collections.<String>emptyList()));

		final ListOperationPass.Session session = new ListOperationPass.Session(readOnly);

		final JsonNode goodArgs = mapper.readTree("{\"operation\":\"list_contacts\",\"inputs\":{\"after\":\"0\"}}");
		final LocalTool.Result goodResult = session.chooseListOperation(goodArgs);
		ListOperationPassTest.check("valid choice is accepted", !goodResult.isError);
		ListOperationPassTest.check("valid choice recorded", session.chosen().size() == 1);

		final JsonNode secondArgs = mapper.readTree("{\"operation\":\"list_companies\",\"inputs\":{}}");
		session.chooseListOperation(secondArgs);
		ListOperationPassTest.check("a second distinct operation is also recorded", session.chosen().size() == 2);

		final JsonNode duplicateArgs = mapper.readTree("{\"operation\":\"list_contacts\",\"inputs\":{}}");
		final LocalTool.Result duplicateResult = session.chooseListOperation(duplicateArgs);
		ListOperationPassTest.check("a duplicate choice is not an error but is not re-added either", !duplicateResult.isError);
		ListOperationPassTest.check("duplicate choice not double-counted", session.chosen().size() == 2);

		final JsonNode writeArgs = mapper.readTree("{\"operation\":\"upsert_contact\",\"inputs\":{}}");
		final LocalTool.Result writeResult = session.chooseListOperation(writeArgs);
		ListOperationPassTest.check("a non-read-only operation is refused", writeResult.isError);
		ListOperationPassTest.check("a refused choice is not recorded", session.chosen().size() == 2);

		// a fresh session with no calls at all - the "lookup only" outcome
		final ListOperationPass.Session emptySession = new ListOperationPass.Session(readOnly);
		ListOperationPassTest.check("no calls means no choice", emptySession.chosen().isEmpty());

		System.out.println("ListOperationPassTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
