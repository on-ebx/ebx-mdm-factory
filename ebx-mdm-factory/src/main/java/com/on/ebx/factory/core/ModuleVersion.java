package com.on.ebx.factory.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * What this build of the module is (audit F-03). {@code build.sh} writes
 * {@code com/on/ebx/factory/version.properties} into the classes: {@code version}, {@code commit}, {@code buildTime}
 * (UTC) and the SHA-256 of the two embedded jars. This class only reads it, once, and answers {@code "unknown"} for
 * anything absent: an Eclipse "Clean" wipes the output folder and nothing regenerates the file until the next
 * {@code build.sh} (same caveat as {@link Assets}), and a missing version must never fail class loading or a request.
 * Deliberately no EBX type here, so the plain {@code main()} test runs without the server libraries.
 */
public final class ModuleVersion
{
	public static final String UNKNOWN = "unknown";

	/** Prefix of the version.properties keys that build.sh writes for the Jackson jars of WEB-INF/lib (X3-08: in a package and in a tools/install.sh installation alike). */
	public static final String PACKAGED_JAR_PREFIX = "embeddedJar.";

	private static final String RESOURCE = "/com/on/ebx/factory/version.properties";

	private static final Properties PROPERTIES = ModuleVersion.parse(ModuleVersion.class.getResourceAsStream(ModuleVersion.RESOURCE));

	private ModuleVersion()
	{
	}

	public static String version()
	{
		return ModuleVersion.value(ModuleVersion.PROPERTIES, "version");
	}

	public static String commit()
	{
		return ModuleVersion.value(ModuleVersion.PROPERTIES, "commit");
	}

	public static String buildTime()
	{
		return ModuleVersion.value(ModuleVersion.PROPERTIES, "buildTime");
	}

	/** SHA-256 of {@code WEB-INF/lib/on-ebx-agent.jar} at build time, or {@code "unknown"} (jar absent from that build). */
	public static String agentJarSha256()
	{
		return ModuleVersion.value(ModuleVersion.PROPERTIES, "agentJarSha256");
	}

	/** SHA-256 of {@code WEB-INF/lib/on-ebx-mapping.jar} at build time, or {@code "unknown"}. */
	public static String mappingJarSha256()
	{
		return ModuleVersion.value(ModuleVersion.PROPERTIES, "mappingJarSha256");
	}

	/**
	 * The Jackson jars the module carries in {@code WEB-INF/lib} (X3-07; X3-08: since 1.4.1 {@code tools/install.sh} installs them too, so the module runs on its own
	 * Jackson 2.18.12 everywhere), by their file name, with their SHA-256. Empty only for a build made before 1.4.1 or by a tool that did not list them.
	 */
	public static Map<String, String> packagedJars()
	{
		return ModuleVersion.packagedJars(ModuleVersion.PROPERTIES);
	}

	/** Pure: the {@code embeddedJar.<file name>=<sha256>} entries, in file name order; a blank hash is left out. */
	static Map<String, String> packagedJars(final Properties pProps)
	{
		final Map<String, String> out = new TreeMap<String, String>();
		for (final String key : pProps.stringPropertyNames())
		{
			if (key.startsWith(ModuleVersion.PACKAGED_JAR_PREFIX) && key.length() > ModuleVersion.PACKAGED_JAR_PREFIX.length())
			{
				final String hash = pProps.getProperty(key);
				if (hash != null && !hash.trim().isEmpty())
				{
					out.put(key.substring(ModuleVersion.PACKAGED_JAR_PREFIX.length()), hash.trim());
				}
			}
		}
		return out;
	}

	/**
	 * Pure: reads and closes the stream; a {@code null} stream (resource absent) or an unreadable one gives an empty
	 * set, so every getter answers {@code "unknown"}.
	 */
	static Properties parse(final InputStream pStream)
	{
		final Properties props = new Properties();
		if (pStream == null)
		{
			return props;
		}
		try
		{
			props.load(pStream);
		}
		catch (final IOException ex)
		{
			return new Properties();
		}
		finally
		{
			try
			{
				pStream.close();
			}
			catch (final IOException ex)
			{
				// nothing to do: the stream was consumed
			}
		}
		return props;
	}

	/** A blank value counts as absent: the build writes an empty hash when the jar was not there. */
	static String value(final Properties pProperties, final String pKey)
	{
		final String value = pProperties.getProperty(pKey);
		return value == null || value.trim().isEmpty() ? ModuleVersion.UNKNOWN : value.trim();
	}
}
