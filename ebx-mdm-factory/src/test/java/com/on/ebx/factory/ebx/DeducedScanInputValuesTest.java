package com.on.ebx.factory.ebx;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.deducedscan.OperationCatalog;

/** JUnit-free: run the main, a failure throws. The input values of a deduced scan are held back before the sample call (audit F-17); no MCP call here. */
public final class DeducedScanInputValuesTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final DeducedScanOperations.Described described = new DeducedScanOperations.Described();
		DeducedScanOperations.parseInto(new ObjectMapper().readTree("{\"operations\":["
			+ "{\"name\":\"list\",\"readOnly\":true,\"requiredInputs\":[],\"optionalInputs\":[{\"name\":\"limit\",\"type\":\"integer\"},{\"name\":\"after\"}]},"
			+ "{\"name\":\"plain\",\"readOnly\":true,\"requiredInputs\":[\"di\"],\"optionalInputs\":[]}]}"), described);
		final OperationCatalog.OperationInfo list = OperationCatalog.findByName(described.operations, "list");
		DeducedScanInputValuesTest.check("an input declared with a type is read with it", "integer".equals(list.inputTypes.get("limit")) && list.inputTypes.get("after") == null);
		DeducedScanInputValuesTest.check("input names still read as before", list.optionalInputNames.contains("limit") && list.optionalInputNames.contains("after"));
		DeducedScanInputValuesTest.check("string arrays (the live shape) declare no type", OperationCatalog.findByName(described.operations, "plain").inputTypes.isEmpty());

		final Map<String, String> bad = new LinkedHashMap<String, String>();
		bad.put("limit", "http://169.254.169.254/");
		DeducedScanInputValuesTest.check("a typed value is refused before the call", OperationCatalog.valueViolation(list, new OperationCatalog.ListChoice("list", bad)) != null);

		// the last look at the call itself, with no operation at hand
		final Map<String, String> good = new LinkedHashMap<String, String>();
		good.put("after", "0");
		DeducedScanInputValuesTest.check("a plain value passes the call-site check", DeducedScanSample.valueViolation(new OperationCatalog.ListChoice("list", good)) == null);
		final Map<String, String> host = new LinkedHashMap<String, String>();
		host.put("after", "internal.corp.example:8080/admin");
		DeducedScanInputValuesTest.check("a host in a plain input is refused at the call site", DeducedScanSample.valueViolation(new OperationCatalog.ListChoice("list", host)) != null);
		// sample() with a refused value answers an error and never touches its client (null here: any use would throw a NullPointerException)
		final DeducedScanSample.Result result = DeducedScanSample.sample(null, "conn", new OperationCatalog.ListChoice("list", host));
		DeducedScanInputValuesTest.check("sample() reports the refusal and makes no call: " + result.error, result.error != null && result.error.startsWith("Not called:") && result.rows.isEmpty());
		System.out.println("DeducedScanInputValuesTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
