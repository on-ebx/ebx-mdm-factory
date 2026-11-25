package com.on.ebx.factory.rest;

import java.lang.reflect.Method;

import com.onwbp.org.apache.cxf.jaxrs.model.URITemplate;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * JUnit-free: the automatic-intake routes really route. The {@code @Path} of each method is read by reflection and matched with CXF's own {@code URITemplate}
 * (shaded in ebx-lib.jar as {@code com.onwbp.org.apache.cxf.jaxrs.model.URITemplate} - the class the EBX REST toolkit builds on; the toolkit itself was not started
 * here): the {@code :complete} suffix after a template variable matches, takes the application name without the suffix, and collides with no other intake route.
 */
public final class IntakeRoutesTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final String complete = IntakeRoutesTest.pathOf("intakeBatchComplete", String.class, String.class);
		MultivaluedMap<String, String> values = new MultivaluedHashMap<String, String>();
		check("batch complete matches", new URITemplate(complete).match("/projects/Personnes/intake/Salesforce:complete", values));
		check("code and source are captured without the suffix", "Personnes".equals(values.getFirst("code")) && "Salesforce".equals(values.getFirst("source")));
		values = new MultivaluedHashMap<String, String>();
		check("an underscore/digit name routes", new URITemplate(complete).match("/projects/P1/intake/SAP_ECC2:complete", values) && "SAP_ECC2".equals(values.getFirst("source")));
		final String read = IntakeRoutesTest.pathOf("intakeReadNow", String.class, String.class);
		values = new MultivaluedHashMap<String, String>();
		check("read now matches", new URITemplate(read).match("/projects/Personnes/intake/Salesforce:read", values) && "Salesforce".equals(values.getFirst("source")) && "Personnes".equals(values.getFirst("code")));
		check("read and complete never take each other's paths", !new URITemplate(read).match("/projects/P/intake/CRM:complete", new MultivaluedHashMap<String, String>())
			&& !new URITemplate(complete).match("/projects/P/intake/CRM:read", new MultivaluedHashMap<String, String>()));
		for (final String other : new String[] { "settings", "on", "off", "pause", "resume", "process", "Salesforce" })
		{
			check("the read-now route does not take /intake/" + other, !new URITemplate(read).match("/projects/P/intake/" + other, new MultivaluedHashMap<String, String>()));
		}
		for (final String other : new String[] { "settings", "on", "off", "pause", "resume", "process", "Salesforce" })
		{
			check("the batch-complete route does not take /intake/" + other, !new URITemplate(complete).match("/projects/P/intake/" + other, new MultivaluedHashMap<String, String>()));
		}
		for (final String method : new String[] { "intakeSettings", "intakeOn", "intakePause", "intakeResume", "intakeOff", "intakeProcess" })
		{
			final String path = IntakeRoutesTest.pathOf(method, method.equals("intakeSettings") ? new Class<?>[] { String.class, String.class } : new Class<?>[] { String.class });
			check(method + " does not take a :read path", !new URITemplate(IntakeRoutesTest.pathOf(method, method.equals("intakeSettings") ? new Class<?>[] { String.class, String.class } : new Class<?>[] { String.class }))
				.match("/projects/P/intake/CRM:read", new MultivaluedHashMap<String, String>()));
			check(method + " does not take a :complete path", !new URITemplate(path).match("/projects/P/intake/CRM:complete", new MultivaluedHashMap<String, String>()));
			check(method + " matches its own path", new URITemplate(path).match(path.replace("{code}", "P"), new MultivaluedHashMap<String, String>()));
		}
		check("the status route", new URITemplate(IntakeRoutesTest.pathOf("intakeStatus", String.class)).match("/projects/P/intake", new MultivaluedHashMap<String, String>()));
		// the path the status tells an application to call is the route that was tested above
		final String advertised = com.on.ebx.factory.ebx.Intake.batchCompletePath("Personnes", "Salesforce");
		check("advertised path: " + advertised, advertised.equals("/ebx-mdm-factory/rest/v1/projects/Personnes/intake/Salesforce:complete"));
		check("and it ends with a path the route matches", new URITemplate(complete).match(advertised.substring("/ebx-mdm-factory/rest/v1".length()), new MultivaluedHashMap<String, String>()));
		System.out.println("IntakeRoutesTest OK");
	}

	private static String pathOf(final String pMethod, final Class<?>... pTypes) throws Exception
	{
		final Method method = FactoryResource.class.getMethod(pMethod, pTypes);
		return method.getAnnotation(jakarta.ws.rs.Path.class).value();
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
