package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.core.FakeSchemaTest;
import com.on.ebx.factory.core.WriterGuard.Expectation;
import com.on.ebx.factory.core.WriterGuard.Kind;
import com.on.ebx.factory.core.WriterGuard.Writer;
import com.on.ebx.factory.core.WriterShapes;

/**
 * Audit F-06: what the three writers of this package (DmaToolbarWriter, PublicationOwnerRepair, PerspectiveInstaller) declare they touch
 * ({@code expectations()}) is kept in step with the {@code Path} constants of their sources, and each declaration refuses a schema that lacks any one
 * of its paths. A {@code Path} constant added to a writer without a line here, and without an expectation, fails this test: the shape check cannot fall behind the code.
 * JUnit-free: run the main (needs {@code -Dmdmf.sourceDir}).
 */
public final class WriterExpectationsTest
{
	private static final Pattern CONSTANT = Pattern.compile("private static final Path (\\w+) = Path\\.parse\\(\"([^\"]*)\"\\);");

	public static void main(final String[] pArgs) throws IOException
	{
		WriterExpectationsTest.dmaToolbars();
		WriterExpectationsTest.publicationOwner();
		WriterExpectationsTest.perspective();
		System.out.println("WriterExpectationsTest OK");
	}

	private static void dmaToolbars() throws IOException
	{
		final String t = "/root/toolbarNode";
		final Map<String, String> constants = new LinkedHashMap<String, String>();
		constants.put("TOOLBAR_NODE_TABLE", t);
		constants.put("ID", t + "/id");
		constants.put("PARENT", t + "/parent");
		constants.put("TYPE", t + "/type");
		constants.put("ORDERING", t + "/ordering");
		constants.put("TOOLBAR_NAME", t + "/toolbar/name");
		constants.put("TOOLBAR_DOCS", t + "/toolbar/documentation/localizedDocumentations");
		constants.put("TOOLBAR_LOCATION", t + "/toolbar/location");
		constants.put("AB_SERVICE_KEY", t + "/actionButton/service/serviceKey");
		constants.put("AB_TARGET", t + "/actionButton/service/target");
		constants.put("AB_MODAL_SIZE", t + "/actionButton/service/modalSize");
		constants.put("AB_RESIZABLE", t + "/actionButton/service/resizable");
		constants.put("AB_SERVICE_PARAMETERS", t + "/actionButton/service/serviceParameters");
		constants.put("AB_DOCS", t + "/actionButton/documentation/localizedDocumentations");
		constants.put("AB_LAYOUT", t + "/actionButton/display/layout");
		constants.put("AB_ICON_TYPE", t + "/actionButton/display/icon/type");
		constants.put("AB_ICON_REFERENCE", t + "/actionButton/display/icon/reference");
		constants.put("AB_HIGHLIGHTED", t + "/actionButton/display/highlightedButton");
		constants.put("SEP_WIDTH", t + "/separator/width");
		constants.put("MB_DOCS", t + "/menuButton/documentation/localizedDocumentations");
		constants.put("MB_LAYOUT", t + "/menuButton/display/layout");
		constants.put("MB_HIGHLIGHTED", t + "/menuButton/display/highlightedButton");
		constants.put("MB_ICON_TYPE", t + "/menuButton/display/icon/type");
		constants.put("MB_ICON_REFERENCE", t + "/menuButton/display/icon/reference");
		constants.put("MG_TYPE", t + "/menuGroup/type");
		constants.put("MG_BUILDER_KEY", t + "/menuGroup/menuBuilder/menuBuilderKey");
		constants.put("MG_EXCLUDED_SERVICE_GROUPS", t + "/menuGroup/menuBuilder/excludedServiceGroups");
		constants.put("MG_EXCLUDED_SERVICES", t + "/menuGroup/menuBuilder/excludedServices");
		constants.put("MG_FILTERING_POLICY", t + "/menuGroup/menuBuilder/filteringPolicy");
		// the sub-fields of a repeatable group, relative to the group itself (the expectations name them under each group)
		final Set<String> groupRelative = new HashSet<String>(java.util.Arrays.asList("DOC_LOCALE", "DOC_LABEL", "PARAM_NAME", "PARAM_VALUE"));
		WriterExpectationsTest.verify("DmaToolbarWriter", "ebx/DmaToolbarWriter.java", constants, groupRelative, DmaToolbarWriter.expectations(), Writer.DMA_TOOLBARS);
		final List<Expectation> expected = DmaToolbarWriter.expectations();
		WriterExpectationsTest.check("the locale of a localized label is a Locale (the incident the class Javadoc names)",
			WriterExpectationsTest.kindOf(expected, t + "/toolbar/documentation/localizedDocumentations/locale") == Kind.LOCALE);
		WriterExpectationsTest.check("ordering and width are integers, resizable a boolean",
			WriterExpectationsTest.kindOf(expected, t + "/ordering") == Kind.INT && WriterExpectationsTest.kindOf(expected, t + "/separator/width") == Kind.INT
				&& WriterExpectationsTest.kindOf(expected, t + "/actionButton/service/resizable") == Kind.BOOLEAN);
	}

	private static void publicationOwner() throws IOException
	{
		final String t = "/dataModels/publications";
		final Map<String, String> constants = new LinkedHashMap<String, String>();
		constants.put("PUBLICATIONS", t);
		constants.put("NAME", t + "/name");
		constants.put("DATA_MODEL", t + "/dataModel");
		constants.put("ORIGINAL_DATA_MODEL", t + "/originalDataModel");
		// read on the table the foreign key points at, a table the guard cannot name without following the key
		constants.put("VERSION_MODEL_NAME", null);
		WriterExpectationsTest.verify("PublicationOwnerRepair", "ebx/PublicationOwnerRepair.java", constants, new HashSet<String>(), PublicationOwnerRepair.expectations(),
			Writer.PUBLICATION_OWNER);
		WriterExpectationsTest.check("dataModel must be a foreign key (the repair follows its table-reference facet)",
			WriterExpectationsTest.kindOf(PublicationOwnerRepair.expectations(), t + "/dataModel") == Kind.FOREIGN_KEY);
	}

	private static void perspective() throws IOException
	{
		final String m = "/domain/menuItem";
		final Map<String, String> constants = new LinkedHashMap<String, String>();
		constants.put("MENU_ITEM_TABLE", m);
		constants.put("PROPERTIES_ACTIVATED", "/domain/properties/activated");
		constants.put("PROPERTIES_DEFAULT_SELECTION", "/domain/properties/defaultSelection");
		constants.put("MI_TYPE", m + "/type");
		constants.put("MI_PARENT", m + "/parent");
		constants.put("MI_ORDER", m + "/order");
		constants.put("MI_HAS_TOP_SEPARATOR", m + "/hasTopSeparator");
		constants.put("MI_ICON_TYPE", m + "/icon/type");
		constants.put("MI_ICON_REFERENCE", m + "/icon/reference");
		constants.put("MI_ACTION_TYPE", m + "/action/type");
		constants.put("MI_ACTION_SERVICE_KEY", m + "/action/serviceKey");
		constants.put("MI_ACTION_SERVICE_PARAMETERS", m + "/action/serviceParameters");
		constants.put("MI_LABEL_DOCS", m + "/label/localizedDocumentations");
		WriterExpectationsTest.verify("PerspectiveInstaller", "ebx/PerspectiveInstaller.java", constants, new HashSet<String>(), PerspectiveInstaller.expectations(), Writer.PERSPECTIVE);
		WriterExpectationsTest.check("activated is a boolean", WriterExpectationsTest.kindOf(PerspectiveInstaller.expectations(), "/domain/properties/activated") == Kind.BOOLEAN);
	}

	/**
	 * Every Path constant of the source is classified here (so a new one fails the test until someone decides where it lives), every classified path is an
	 * expectation, and the expectations are met by a schema built from them and refused when any ONE path is taken away.
	 */
	private static void verify(final String pName, final String pRelativeSource, final Map<String, String> pConstants, final Set<String> pGroupRelative,
		final List<Expectation> pExpectations, final Writer pWriter) throws IOException
	{
		final String dir = System.getProperty("mdmf.sourceDir");
		WriterExpectationsTest.check("-Dmdmf.sourceDir is set", dir != null);
		final String source = new String(Files.readAllBytes(Paths.get(dir, "com/on/ebx/factory", pRelativeSource)), StandardCharsets.UTF_8);
		final Matcher matcher = WriterExpectationsTest.CONSTANT.matcher(source);
		final Set<String> found = new HashSet<String>();
		while (matcher.find())
		{
			found.add(matcher.group(1));
		}
		for (final String name : found)
		{
			WriterExpectationsTest.check(pName + ": the Path constant " + name + " is classified in this test (add it, and its expectation)",
				pConstants.containsKey(name) || pGroupRelative.contains(name));
		}
		for (final String name : pConstants.keySet())
		{
			WriterExpectationsTest.check(pName + ": the test names a constant " + name + " that the source no longer has", found.contains(name));
		}
		final Set<String> expectedPaths = new HashSet<String>();
		for (final Expectation expectation : pExpectations)
		{
			expectedPaths.add(expectation.path);
		}
		for (final Map.Entry<String, String> entry : pConstants.entrySet())
		{
			if (entry.getValue() != null)
			{
				WriterExpectationsTest.check(pName + ": " + entry.getKey() + " (" + entry.getValue() + ") is among the expectations", expectedPaths.contains(entry.getValue()));
			}
		}
		WriterExpectationsTest.check(pName + ": no path declared twice", expectedPaths.size() == pExpectations.size());
		WriterExpectationsTest.check(pName + ": a schema built from the expectations passes", WriterShapes.refusal(pWriter, Locale.ENGLISH, FakeSchemaTest.satisfying(pExpectations), pExpectations) == null);
		for (final Expectation expectation : pExpectations)
		{
			final String refusal = WriterShapes.refusal(pWriter, Locale.ENGLISH, FakeSchemaTest.satisfyingWithout(pExpectations, expectation.path), pExpectations);
			WriterExpectationsTest.check(pName + ": a schema without " + expectation.path + " is refused, naming it: " + refusal,
				refusal != null && refusal.contains(expectation.path) && refusal.contains("\"" + pWriter.id() + "\""));
		}
	}

	private static Kind kindOf(final List<Expectation> pExpectations, final String pPath)
	{
		for (final Expectation expectation : pExpectations)
		{
			if (expectation.path.equals(pPath))
			{
				return expectation.kind;
			}
		}
		return null;
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
