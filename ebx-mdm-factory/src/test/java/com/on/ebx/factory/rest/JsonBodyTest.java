package com.on.ebx.factory.rest;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.on.ebx.factory.core.JsonLimits;
import com.on.ebx.factory.ebx.SampleSourceImporter;

/**
 * JUnit-free. X3-08 (1.4.1): the routes of {@code FactoryResource} that read a JSON body (distribution product, orphan connections, orphan matching configurations, derivation
 * parameters, intake settings, staging decisions, sample files) all read it through {@code readJsonBody}: a body nested beyond the bound answers a coded refusal ({@code
 * MDMF-JSON-1}) that the route returns as a 400 - no exception, no {@code StackOverflowError}, never an empty body. A malformed body answers {@code MDMF-JSON-2}; a blank one is the
 * route's default. The route methods themselves need an EBX session and cannot run here: the helper is what each one calls (read the six {@code readJsonBody} call sites).
 */
public final class JsonBodyTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final com.fasterxml.jackson.databind.JsonNode dflt = JsonNodeFactory.instance.objectNode();
		final FactoryResource.JsonBody blank = FactoryResource.readJsonBody("  ", dflt);
		check("blank body is the route's default", blank.error == null && blank.node == dflt);
		check("null body is the route's default (may be null)", FactoryResource.readJsonBody(null, null).error == null && FactoryResource.readJsonBody(null, null).node == null);
		final FactoryResource.JsonBody ok = FactoryResource.readJsonBody("{\"decisions\":[{\"id\":\"1\"}]}", dflt);
		check("a normal body is read", ok.error == null && ok.node.path("decisions").isArray());
		for (final int levels : new int[] { 1001, 5000, 200000 })
		{
			final StringBuilder deep = new StringBuilder();
			for (int i = 0; i < levels; i++)
			{
				deep.append("{\"a\":");
			}
			deep.append('1');
			for (int i = 0; i < levels; i++)
			{
				deep.append('}');
			}
			final FactoryResource.JsonBody refused = FactoryResource.readJsonBody(deep.toString(), dflt);
			check(levels + " levels: refused, not read", refused.node == null && refused.error != null);
			check(levels + " levels: coded as a bound refusal: " + refused.error, refused.error.startsWith(JsonLimits.BOUND_CODE + ": the request body goes beyond a JSON read bound"));
		}
		final FactoryResource.JsonBody malformed = FactoryResource.readJsonBody("{\"a\":", dflt);
		check("malformed: coded as such: " + malformed.error, malformed.error != null && malformed.error.startsWith(JsonLimits.MALFORMED_CODE + ": the request body is not valid JSON"));

		// the sample-files route: the strict parse throws a bound refusal (the route codes it), the tolerant one the wizard uses stays tolerant
		final String deepFiles = "[[[[" + "[".repeat(1100) + "]".repeat(1100) + "]]]]";
		try
		{
			SampleSourceImporter.parseFileInputsStrict(deepFiles);
			throw new AssertionError("the strict parse must throw on a too-deep body");
		}
		catch (final IOException ex)
		{
			check("strict: a bound refusal", JsonLimits.isBoundRefusal(ex));
		}
		check("tolerant: nothing, as before", SampleSourceImporter.parseFileInputs(deepFiles).isEmpty());
		final List<SampleSourceImporter.FileInput> files = SampleSourceImporter.parseFileInputsStrict("[{\"name\":\"a.csv\",\"dataUrl\":\"data:text/csv;name=a.csv;base64,YQ==\"}]");
		check("strict: a normal body still gives its file", files.size() == 1);
		System.out.println("JsonBodyTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
