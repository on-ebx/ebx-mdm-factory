package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JUnit-free: run the main, a failure throws. F2-08/F2-09 (audit 2026-10-23): {@code com.orchestranetworks.security.JsonWebTokenHandler} is an internal EBX API; the one
 * accepted use is {@link SessionToken} ({@code docs/OPERATIONS.md}). Any other import of it - {@code AuthProbe} had its own - fails here. The source folder comes from
 * {@code -Dmdmf.sourceDir}.
 */
public final class JsonWebTokenHandlerConfinementTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"));
		final List<Path> files;
		try (Stream<Path> walk = Files.walk(root))
		{
			files = walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
		}
		final List<String> importers = new ArrayList<String>();
		for (final Path file : files)
		{
			for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8))
			{
				if (line.trim().startsWith("import ") && line.contains("security.JsonWebToken"))
				{
					importers.add(file.getFileName().toString());
					break;
				}
			}
		}
		if (importers.size() != 1 || !"SessionToken.java".equals(importers.get(0)))
		{
			throw new AssertionError("only SessionToken may import JsonWebTokenHandler/JsonWebToken, found: " + importers);
		}
		check("tokenOf strips the cookie name", "abc".equals(SessionToken.tokenOf("EBXUID=abc")));
		check("tokenOf of nothing is null", SessionToken.tokenOf(null) == null && SessionToken.tokenOf("EBXUID=") == null);
		System.out.println("JsonWebTokenHandlerConfinementTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
