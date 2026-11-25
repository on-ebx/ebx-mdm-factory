package com.on.ebx.factory.rest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.core.FactoryRight;
import com.on.ebx.factory.core.FactoryRights;

/**
 * JUnit-free: run the main, a failure throws. Audit F-02 (replaces RestGetsAdminTest of F-25): a structural check on the
 * source of {@link FactoryResource} (the JAX-RS class cannot run without an EBX session) - every route, whatever its
 * method, starts with {@code this.refused("<METHOD> <path>")} naming EXACTLY its own annotations, and that key is decided in
 * {@code core.FactoryRights}; the table names no route that does not exist. {@code GET /status} is the one exception: it
 * answers liveness to anybody and its details to an administrator only. The source folder comes from
 * {@code -Dmdmf.sourceDir} ({@code tools/test_local.sh} sets it).
 */
public final class RestRouteRightsTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"));
		final String resource = new String(Files.readAllBytes(root.resolve("com/on/ebx/factory/rest/FactoryResource.java")), StandardCharsets.UTF_8);
		final Matcher route = Pattern.compile("@(GET|POST|PUT|DELETE)\\s+@Path\\(\"([^\"]+)\"\\)").matcher(resource);
		final Pattern next = Pattern.compile("\\n\\t@(GET|POST|PUT|DELETE)\\b");
		final Set<String> seen = new LinkedHashSet<String>();
		while (route.find())
		{
			final String key = route.group(1) + " " + route.group(2);
			RestRouteRightsTest.check("route declared once: " + key, seen.add(key));
			RestRouteRightsTest.check("route " + key + " has a decided right", FactoryRights.routes().containsKey(key));
			final Matcher end = next.matcher(resource);
			final String body = resource.substring(route.end(), end.find(route.end()) ? end.start() : resource.length());
			final int open = body.indexOf("\n\t{\n");
			RestRouteRightsTest.check("route " + key + " has a body", open >= 0);
			final String head = body.substring(open, Math.min(body.length(), open + 900));
			if ("GET /status".equals(key))
			{
				RestRouteRightsTest.check("/status answers its details to an administrator only", head.contains("Profile.ADMINISTRATOR"));
				continue;
			}
			// second permissions pass (2026-10-14): a route that names a project goes through the project-aware gate (the Factory right, then EBX's
			// own permissions on the project record: refused(key, code), refusedSource(key, id), refusedCreateOrWrite(key, code)); the others keep
			// refused(key). The application's own batch-complete call is the one project route left on the plain gate (its landing write decides).
			final String quotedKey = "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
			final boolean projectRoute = key.contains("/projects/{code}") && !key.endsWith(":complete");
			final boolean sourceRoute = key.startsWith("POST /sources/{id}");
			final String expected = projectRoute ? "this.refused" : sourceRoute ? "this.refusedSource(" + quotedKey + ", " : "this.refused(" + quotedKey + ")";
			int at = head.indexOf(expected);
			if (projectRoute)
			{
				final int plain = head.indexOf("this.refused(" + quotedKey + ", ");
				final int create = head.indexOf("this.refusedCreateOrWrite(" + quotedKey + ", ");
				at = plain >= 0 ? plain : create;
			}
			RestRouteRightsTest.check("route " + key + " starts with its " + (projectRoute ? "project" : sourceRoute ? "project-source" : "route") + " gate", at >= 0);
			// nothing but comments and the authProbe property switch before the check
			final String before = head.substring(0, at);
			RestRouteRightsTest.check("route " + key + " checks before it reads anything",
				!before.contains("FactoryInstaller.") && !before.contains("this.sessionContext.getSession()."));
		}
		RestRouteRightsTest.check("every decided route exists", seen.containsAll(FactoryRights.routes().keySet()) && FactoryRights.routes().keySet().containsAll(seen));
		RestRouteRightsTest.check("seventy routes or more were examined (" + seen.size() + ")", seen.size() >= 70);
		RestRouteRightsTest.check("no administrator check is left behind but /status's",
			resource.split("isUserInRole\\(com\\.orchestranetworks\\.service\\.Profile\\.ADMINISTRATOR\\)", -1).length - 1 == 1);
		// audit F-25 kept: the previously open admin GETs never fall below DESIGN
		for (final String key : new String[] { "GET /projects/{code}/delivery/domain", "GET /sources/onboarding/connections", "GET /sources/onboarding/{connector}" })
		{
			RestRouteRightsTest.check(key + " is DESIGN or more", FactoryRights.route(key).covers(FactoryRight.DESIGN));
		}
		// the lineage projections stay readable to a viewer (filtered by the caller's own EBX permissions, RecordLineageReader)
		RestRouteRightsTest.check("lineage and xrefs ask VIEW", FactoryRights.route("GET /projects/{code}/lineage/{entity}") == FactoryRight.VIEW
			&& FactoryRights.route("GET /projects/{code}/xrefs/{entity}") == FactoryRight.VIEW);
		RestRouteRightsTest.check("the application's batch-complete call needs no Factory right (its landing write access decides)",
			FactoryRights.route("POST /projects/{code}/intake/{source: [^/:]+}:complete") == FactoryRight.AUTHENTICATED);
		RestRouteRightsTest.check("the helper refuses with 403", resource.contains("return FactoryResource.problem(Response.Status.FORBIDDEN, FactoryAccess.refusal(pRouteKey, needed));"));
		System.out.println("RestRouteRightsTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
