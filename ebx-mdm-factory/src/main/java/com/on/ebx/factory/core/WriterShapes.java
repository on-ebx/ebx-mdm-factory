package com.on.ebx.factory.core;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import com.on.ebx.factory.core.WriterGuard.Expectation;
import com.on.ebx.factory.core.WriterGuard.Kind;
import com.on.ebx.factory.core.WriterGuard.Shape;
import com.on.ebx.factory.core.WriterGuard.Verdict;
import com.on.ebx.factory.core.WriterGuard.Writer;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.LoggingCategory;

/**
 * The EBX half of {@link WriterGuard} (audit F-06): reads the schema of the dataset a writer is about to touch and answers, before
 * anything is written, whether every path the writer uses exists with the kind it expects. {@link #refusal} is the one call a writer
 * makes: {@code null} = go ahead, a text = refuse with it.
 * <p>
 * The versions are read once (the ON EBX build from the manifest of {@code ebx-lib.jar}, the add-ons jar by name and size, the module's own
 * build) and logged once at the first guarded write, with the state of every writer's switch - so an administrator reading
 * {@code kernel.log} after an upgrade sees which build the shapes were last matched against, and each refusal repeats it. A guard that
 * itself fails (an unexpected schema API answer) never blocks a writer: it logs once and lets the writer proceed as before.
 */
public final class WriterShapes
{
	private static final AtomicBoolean LOGGED_VERSIONS = new AtomicBoolean(false);
	private static final Set<String> LOGGED_REFUSALS = ConcurrentHashMap.newKeySet();
	private static volatile String versions;

	private WriterShapes()
	{
	}

	/**
	 * @return null when {@code pWriter} may write; otherwise the text of the refusal (the writer is switched off, or its shape check found a
	 *         difference)
	 * @param pSchemaRoot the schema root of the dataset the writer writes into
	 * @param pExpected every absolute path the writer touches, with its kind
	 */
	public static String refusal(final Writer pWriter, final Locale pLocale, final SchemaNode pSchemaRoot, final List<Expectation> pExpected)
	{
		WriterShapes.logVersionsOnce();
		if (!WriterGuard.enabled(pWriter))
		{
			WriterShapes.logOnce(pWriter, "off", WriterGuard.disabledMessage(pWriter, Locale.ENGLISH));
			return WriterGuard.disabledMessage(pWriter, pLocale);
		}
		if (!WriterGuard.shapeCheckOn())
		{
			return null;
		}
		final Verdict verdict;
		try
		{
			verdict = WriterGuard.check(pWriter, WriterShapes.shapeOf(pSchemaRoot), pExpected);
		}
		catch (final RuntimeException ex)
		{
			WriterShapes.logOnce(pWriter, "guard-failed", "the shape check of writer " + pWriter.id() + " could not run (" + ex + "): the writer goes ahead as before");
			return null;
		}
		if (verdict.ok())
		{
			return null;
		}
		WriterShapes.logOnce(pWriter, "mismatch", verdict.message(Locale.ENGLISH, WriterShapes.versions()));
		return verdict.message(pLocale, WriterShapes.versions());
	}

	/** The schema as a {@link Shape}: the {@link Kind} of the node at an absolute path. Package-visible for the test. */
	static Shape shapeOf(final SchemaNode pRoot)
	{
		return new Shape()
		{
			@Override
			public Kind kindOf(final String pAbsolutePath)
			{
				return WriterShapes.kindOf(pRoot.getNode(Path.parse(pAbsolutePath)));
			}
		};
	}

	/** The kind of one schema node; null for no node. */
	static Kind kindOf(final SchemaNode pNode)
	{
		if (pNode == null)
		{
			return null;
		}
		if (pNode.isTableNode())
		{
			return Kind.TABLE;
		}
		final int max = pNode.getMaxOccurs();
		if (max > 1 || max < 0)
		{
			return Kind.LIST;
		}
		if (pNode.getFacetOnTableReference() != null)
		{
			return Kind.FOREIGN_KEY;
		}
		if (pNode.isComplex())
		{
			return Kind.GROUP;
		}
		return WriterShapes.kindOfClass(pNode.getOccurrencesJavaClass());
	}

	/** The kind of a terminal value of Java class {@code pClass}. */
	static Kind kindOfClass(final Class<?> pClass)
	{
		if (String.class.equals(pClass))
		{
			return Kind.STRING;
		}
		if (Integer.class.equals(pClass) || Short.class.equals(pClass) || Long.class.equals(pClass) || Byte.class.equals(pClass) || BigInteger.class.equals(pClass))
		{
			return Kind.INT;
		}
		if (BigDecimal.class.equals(pClass))
		{
			return Kind.DECIMAL;
		}
		if (Boolean.class.equals(pClass))
		{
			return Kind.BOOLEAN;
		}
		if (Locale.class.equals(pClass))
		{
			return Kind.LOCALE;
		}
		return Kind.OTHER;
	}

	// ------------------------------------------------------------------------------------------------------ versions

	/** What this guard read of the product at start, for the logs and for every refusal. Computed once. */
	public static String versions()
	{
		String value = WriterShapes.versions;
		if (value == null)
		{
			value = WriterShapes.readVersions();
			WriterShapes.versions = value;
		}
		return value;
	}

	/**
	 * The running ON EBX version as the product's own jar states it ({@code 6.3.0 (build 1234)}), or {@code null} when it cannot be read.
	 * Same reading as {@link #versions()}; the About screen shows it on its own line.
	 */
	public static String productVersion()
	{
		try
		{
			final String implementation = Repository.class.getPackage() == null ? null : Repository.class.getPackage().getImplementationVersion();
			if (implementation == null)
			{
				return null;
			}
			final String build = WriterShapes.manifestAttribute(Repository.class, "product-build-number");
			return build == null ? implementation : implementation + " (build " + build + ")";
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static String readVersions()
	{
		final StringBuilder out = new StringBuilder("ON EBX ");
		final String product = WriterShapes.productVersion();
		out.append(product == null ? ModuleVersion.UNKNOWN : product);
		out.append("; add-ons ").append(WriterShapes.addonsJar()).append("; module ").append(ModuleVersion.version());
		return out.toString();
	}

	/** The add-ons jar by file name, size and date - the jar carries no version of its own. */
	private static String addonsJar()
	{
		try
		{
			final Class<?> marker = Class.forName("com.orchestranetworks.addon.mame.common.util.metadata.MetadataHelper", false, WriterShapes.class.getClassLoader());
			final File file = WriterShapes.fileOf(marker);
			if (file == null)
			{
				return ModuleVersion.UNKNOWN;
			}
			final String version = WriterShapes.manifestAttribute(marker, "Implementation-Version");
			return file.getName() + (version == null ? "" : " " + version) + " (" + file.length() + " bytes, "
				+ new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date(file.lastModified())) + ")";
		}
		catch (final ClassNotFoundException ex)
		{
			return "not installed";
		}
		catch (final LinkageError ex)
		{
			return ModuleVersion.UNKNOWN;
		}
		catch (final RuntimeException ex)
		{
			return ModuleVersion.UNKNOWN;
		}
	}

	private static File fileOf(final Class<?> pClass)
	{
		try
		{
			final CodeSource source = pClass.getProtectionDomain().getCodeSource();
			return source == null || source.getLocation() == null ? null : new File(source.getLocation().toURI());
		}
		catch (final URISyntaxException ex)
		{
			return null;
		}
		catch (final SecurityException ex)
		{
			return null;
		}
	}

	private static String manifestAttribute(final Class<?> pClass, final String pName)
	{
		final File file = WriterShapes.fileOf(pClass);
		if (file == null || !file.isFile())
		{
			return null;
		}
		try (JarFile jar = new JarFile(file))
		{
			final Manifest manifest = jar.getManifest();
			return manifest == null ? null : manifest.getMainAttributes().getValue(new Attributes.Name(pName));
		}
		catch (final java.io.IOException ex)
		{
			return null;
		}
	}

	// --------------------------------------------------------------------------------------------------------- logs

	private static void logVersionsOnce()
	{
		if (!WriterShapes.LOGGED_VERSIONS.compareAndSet(false, true))
		{
			return;
		}
		final StringBuilder switches = new StringBuilder();
		for (final Writer writer : Writer.values())
		{
			switches.append(switches.length() == 0 ? "" : ", ").append(writer.id()).append('=').append(WriterGuard.enabled(writer) ? "on" : "OFF");
		}
		WriterShapes.log(false, "[mdm-factory] writers into technical datasets are guarded (audit F-06); read at start: " + WriterShapes.versions() + "; switches: "
			+ switches + "; shape check " + (WriterGuard.shapeCheckOn() ? "on" : "OFF (-D" + WriterGuard.GLOBAL_PROPERTY + "=false)"));
	}

	/** One WARN per writer and reason for the life of the JVM: a refusal repeated at every call must not flood the log. */
	private static void logOnce(final Writer pWriter, final String pReason, final String pText)
	{
		if (WriterShapes.LOGGED_REFUSALS.add(pWriter.id() + "|" + pReason))
		{
			WriterShapes.log(true, "[mdm-factory] " + pText);
		}
	}

	private static void log(final boolean pWarn, final String pText)
	{
		try
		{
			if (pWarn)
			{
				LoggingCategory.getKernel().warn(pText);
			}
			else
			{
				LoggingCategory.getKernel().info(pText);
			}
		}
		catch (final RuntimeException ex)
		{
			// no kernel logger (a test): the text is not lost to the caller, which also gets it in the refusal
		}
		catch (final LinkageError ex)
		{
			// same
		}
	}

	/** For the test: forget what was logged. */
	static void resetLogsForTest()
	{
		WriterShapes.LOGGED_VERSIONS.set(false);
		WriterShapes.LOGGED_REFUSALS.clear();
	}
}
