package com.on.ebx.factory.deducedscan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;

/** JUnit-free: run the main, a failure throws. The values the model chooses for a deduced scan are checked before the external system is called (audit F-17). */
public final class InputValueRulesTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		InputValueRulesTest.acceptedValues();
		InputValueRulesTest.refusedValues();
		InputValueRulesTest.numericInputs();
		InputValueRulesTest.urlInputs();
		InputValueRulesTest.catalogAndSession();
		System.out.println("InputValueRulesTest OK");
	}

	private static void acceptedValues()
	{
		for (final String value : new String[] { "0", "100", "*", "%", "all", "Acme", "2024-01-15", "2024-01-15T10:00:00Z", "A:B", "v1.2", "1.5", "NDC 12345-678", "a_b-c", "" })
		{
			InputValueRulesTest.check("'" + value + "' goes through as a plain input", InputValueRules.firstViolation("filter", value, null) == null);
		}
		InputValueRulesTest.check("a null value is not this rule's business", InputValueRules.firstViolation("filter", null, null) == null);
		InputValueRulesTest.check("exactly the maximum length is accepted", InputValueRules.firstViolation("q", InputValueRulesTest.repeat('a', InputValueRules.MAX_LENGTH), null) == null);
	}

	private static void refusedValues()
	{
		final String[] refused = { "http://169.254.169.254/latest/meta-data", "https://evil.example/x", "HTTP://EVIL.example", "//evil.example/x", "ftp://host/file", "file:///etc/passwd",
			"file:/etc/passwd", "javascript:alert(1)", "jdbc:h2:mem:x", "\\\\server\\share", "evil.example.com", "evil.example.com/path?x=1", "example.org:8080", "10.0.0.1", "10.0.0.1:8080/x",
			"127.0.0.1", "localhost", "localhost:8080/admin", "[::1]", "[fe80::1]:80" };
		for (final String value : refused)
		{
			InputValueRulesTest.check("'" + value + "' is refused for a plain input", InputValueRules.firstViolation("filter", value, null) != null);
		}
		InputValueRulesTest.check("a line break is refused", InputValueRules.firstViolation("filter", "a\nb", null) != null);
		InputValueRulesTest.check("a tab is refused", InputValueRules.firstViolation("filter", "a\tb", null) != null);
		InputValueRulesTest.check("a NUL is refused", InputValueRules.firstViolation("filter", "a\u0000b", null) != null);
		InputValueRulesTest.check("a value over the maximum is refused", InputValueRules.firstViolation("q", InputValueRulesTest.repeat('a', InputValueRules.MAX_LENGTH + 1), null) != null);
		InputValueRulesTest.check("the reason names the rule: " + InputValueRules.firstViolation("q", "http://x/y", null),
			InputValueRules.firstViolation("q", "http://x/y", null).contains("not a URL parameter"));
	}

	private static void numericInputs()
	{
		for (final String type : new String[] { "integer", "INT", "long", "number", "Decimal", "double" })
		{
			InputValueRulesTest.check(type + ": a number is accepted", InputValueRules.firstViolation("limit", "250", type) == null);
			InputValueRulesTest.check(type + ": a decimal is accepted", InputValueRules.firstViolation("limit", "-1.5", type) == null);
			InputValueRulesTest.check(type + ": text is refused", InputValueRules.firstViolation("limit", "all", type) != null);
			InputValueRulesTest.check(type + ": an expression is refused", InputValueRules.firstViolation("limit", "1 OR 1=1", type) != null);
			InputValueRulesTest.check(type + ": a URL is refused", InputValueRules.firstViolation("limit", "http://x", type) != null);
		}
		InputValueRulesTest.check("a type that is not numeric leaves text alone", InputValueRules.firstViolation("limit", "all", "string") == null);
		InputValueRulesTest.check("an absurdly long digit string is refused", InputValueRules.firstViolation("limit", InputValueRulesTest.repeat('9', 30), "integer") != null);
	}

	private static void urlInputs()
	{
		InputValueRulesTest.check("a URL parameter takes https", InputValueRules.firstViolation("callbackUrl", "https://api.example.com/v1/items?page=2", null) == null);
		InputValueRulesTest.check("a URL parameter takes http", InputValueRules.firstViolation("next_url", "http://api.example.com/items", null) == null);
		InputValueRulesTest.check("declared url is a URL parameter whatever its name", InputValueRules.firstViolation("target", "https://api.example.com/", "url") == null);
		InputValueRulesTest.check("a URL parameter refuses file:", InputValueRules.firstViolation("pageUrl", "file:///etc/passwd", null) != null);
		InputValueRulesTest.check("a URL parameter refuses a scheme-less host", InputValueRules.firstViolation("pageUrl", "api.example.com/x", null) != null);
		InputValueRulesTest.check("a URL parameter refuses user information", InputValueRules.firstViolation("pageUrl", "https://good.example.com@evil.example/", null) != null);
		InputValueRulesTest.check("a URL parameter refuses an empty host", InputValueRules.firstViolation("pageUrl", "https:///x", null) != null);
		InputValueRulesTest.check("a URL parameter still refuses control characters", InputValueRules.firstViolation("pageUrl", "https://a.example/\r\nHost: x", null) != null);
		InputValueRulesTest.check("a name that merely contains 'url' in the middle is not a URL parameter", InputValueRules.firstViolation("urlCount", "http://x", null) != null);
	}

	private static void catalogAndSession() throws Exception
	{
		final Map<String, String> types = new LinkedHashMap<String, String>();
		types.put("limit", "integer");
		final List<OperationCatalog.OperationInfo> readOnly = new ArrayList<OperationCatalog.OperationInfo>();
		readOnly.add(new OperationCatalog.OperationInfo("list_contacts", true, new ArrayList<String>(), Arrays.asList("limit", "after"), null, "READ", types));
		readOnly.add(new OperationCatalog.OperationInfo("deviceSearch", true, Arrays.asList("brand"), new ArrayList<String>(), null, "SEARCH"));

		final Map<String, String> good = new LinkedHashMap<String, String>();
		good.put("limit", "100");
		good.put("after", "0");
		InputValueRulesTest.check("good values pass the choice check", OperationCatalog.firstViolation(new OperationCatalog.ListChoice("list_contacts", good), readOnly) == null);
		final Map<String, String> words = new LinkedHashMap<String, String>();
		words.put("limit", "everything");
		final String typed = OperationCatalog.firstViolation(new OperationCatalog.ListChoice("list_contacts", words), readOnly);
		InputValueRulesTest.check("a declared number refuses a word, naming operation and input: " + typed, typed != null && typed.contains("list_contacts") && typed.contains("limit"));
		final Map<String, String> url = new LinkedHashMap<String, String>();
		url.put("after", "http://169.254.169.254/");
		InputValueRulesTest.check("a URL in a cursor is refused at the choice", OperationCatalog.firstViolation(new OperationCatalog.ListChoice("list_contacts", url), readOnly) != null);

		// the model's own tool answers with the reason, so it can answer again, and nothing is recorded
		final ListOperationPass.Session session = new ListOperationPass.Session(readOnly);
		final ObjectMapper mapper = new ObjectMapper();
		final LocalTool.Result refused = session.chooseListOperation(mapper.readTree("{\"operation\":\"list_contacts\",\"inputs\":{\"after\":\"https://evil.example/x\"}}"));
		InputValueRulesTest.check("the tool refuses the value", refused.isError);
		InputValueRulesTest.check("a refused choice is not recorded", session.chosen().isEmpty());
		InputValueRulesTest.check("a corrected choice is accepted", !session.chooseListOperation(mapper.readTree("{\"operation\":\"list_contacts\",\"inputs\":{\"after\":\"0\"}}")).isError
			&& session.chosen().size() == 1);

		// the callability filter: whatever reached it with a bad value is reported, never called
		final Map<String, String> sneaky = new LinkedHashMap<String, String>();
		sneaky.put("brand", "file:///etc/passwd");
		final OperationCatalog.CallabilityResult result = OperationCatalog.filterCallable(
			Arrays.asList(new OperationCatalog.ListChoice("deviceSearch", sneaky), new OperationCatalog.ListChoice("list_contacts", good)), readOnly);
		InputValueRulesTest.check("the bad one is not callable", result.callable.size() == 1 && "list_contacts".equals(result.callable.get(0).operation));
		InputValueRulesTest.check("and the step says why: " + result.notCallableReasons, result.notCallableReasons.size() == 1 && result.notCallableReasons.get(0).contains("deviceSearch"));
	}

	private static String repeat(final char pChar, final int pCount)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pCount; i++)
		{
			out.append(pChar);
		}
		return out.toString();
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
