package com.on.ebx.factory.ebx;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.core.WriterGuard;
import com.on.ebx.factory.core.WriterGuard.Expectation;
import com.on.ebx.factory.core.WriterGuard.Kind;
import com.on.ebx.factory.core.WriterGuard.Writer;
import com.on.ebx.factory.core.WriterShapes;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationReference;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Installs the packaged "MDM Factory" advanced perspective ({@code MdmFactory.perspective.json}) at
 * repository startup, when no perspective with that key exists. Same approach and the same reason as
 * ebx-connectors' own {@code PerspectiveInstaller} (copied, not reinvented, per the module's own note that
 * this exact code is the reference for the technique): a perspective is a child dataset of the
 * {@code ebx-manager} root dataset, with no REST or EBX Script API to create it — only
 * {@link ProcedureContext#doCreateChild}. Never modifies a perspective that already exists, so the menu can
 * be edited by hand in Administration without being overwritten at the next restart.
 * <p>
 * Differences from the Connectors original, beyond the resource name and log prefix: this reads the JSON
 * with the Jackson {@link ObjectMapper} already used throughout this module (McpServers, LandingPlanner,
 * StagingSuggester) instead of Connectors' own small {@code Json} helper — same {@link JsonNode} shape,
 * no behavioural difference. {@code -Debx.factory.perspective=false} disables the installation.
 */
final class PerspectiveInstaller
{
	static final String RESOURCE = "MdmFactory.perspective.json";

	private static final HomeKey EBX_MANAGER_HOME = HomeKey.forBranchName("ebx-manager");
	private static final AdaptationName EBX_MANAGER_ROOT = AdaptationName.forName("ebx-manager");
	private static final Path MENU_ITEM_TABLE = Path.parse("/domain/menuItem");
	private static final Path PROPERTIES_ACTIVATED = Path.parse("/domain/properties/activated");
	private static final Path PROPERTIES_DEFAULT_SELECTION = Path.parse("/domain/properties/defaultSelection");
	private static final Path MI_TYPE = Path.parse("type");
	private static final Path MI_PARENT = Path.parse("parent");
	private static final Path MI_ORDER = Path.parse("order");
	private static final Path MI_HAS_TOP_SEPARATOR = Path.parse("hasTopSeparator");
	private static final Path MI_ICON_TYPE = Path.parse("icon/type");
	private static final Path MI_ICON_REFERENCE = Path.parse("icon/reference");
	private static final Path MI_ACTION_TYPE = Path.parse("action/type");
	private static final Path MI_ACTION_SERVICE_KEY = Path.parse("action/serviceKey");
	private static final Path MI_ACTION_SERVICE_PARAMETERS = Path.parse("action/serviceParameters");
	private static final Path MI_LABEL_DOCS = Path.parse("label/localizedDocumentations");

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int ATTEMPTS = 10;
	private static final long RETRY_SECONDS = 30L;

	private PerspectiveInstaller()
	{
	}

	/**
	 * Audit F-06: what the installer reads and writes in the {@code ebx-manager} model (a perspective is a child dataset of its root). Not measured
	 * on a live instance (the dataset is technical and not offered by the read-only tools): the paths and the {@code Locale} / {@code Boolean}
	 * values are the ones this class has written at every start since 2026-09-11, so only existence and the coarse kind (list, group, locale, boolean)
	 * are asserted. {@code PerspectiveInstallerGuardTest} keeps it in step with the {@code Path} constants above.
	 */
	static List<Expectation> expectations()
	{
		final String t = "/domain/menuItem";
		final List<Expectation> out = new ArrayList<Expectation>();
		out.add(Expectation.of(t, Kind.TABLE));
		out.add(Expectation.of(t + "/id", Kind.OTHER));
		out.add(Expectation.of(t + "/type", Kind.OTHER));
		out.add(Expectation.of(t + "/parent", Kind.OTHER));
		out.add(Expectation.of(t + "/order", Kind.OTHER));
		out.add(Expectation.of(t + "/hasTopSeparator", Kind.OTHER));
		out.add(Expectation.of(t + "/icon/type", Kind.OTHER));
		out.add(Expectation.of(t + "/icon/reference", Kind.OTHER));
		out.add(Expectation.of(t + "/action/type", Kind.OTHER));
		out.add(Expectation.of(t + "/action/serviceKey", Kind.OTHER));
		out.add(Expectation.of(t + "/action/serviceParameters", Kind.LIST));
		out.add(Expectation.of(t + "/action/serviceParameters/name", Kind.OTHER));
		out.add(Expectation.of(t + "/action/serviceParameters/value", Kind.OTHER));
		out.add(Expectation.of(t + "/label/localizedDocumentations", Kind.LIST));
		out.add(Expectation.of(t + "/label/localizedDocumentations/locale", Kind.LOCALE));
		out.add(Expectation.of(t + "/label/localizedDocumentations/label", Kind.OTHER));
		out.add(Expectation.of("/domain/properties/activated", Kind.BOOLEAN));
		out.add(Expectation.of("/domain/properties/defaultSelection", Kind.OTHER));
		return out;
	}

	static void install(final Repository pRepository, final Session pSession, final LoggingCategory pLog)
	{
		if (!WriterGuard.enabled(Writer.PERSPECTIVE))
		{
			pLog.info("[mdm-factory] perspective installation disabled (-D" + Writer.PERSPECTIVE.property() + "=false or ebx.factory.perspective=false)");
			return;
		}
		final JsonNode definition;
		try
		{
			definition = PerspectiveInstaller.definition();
		}
		catch (final Exception ex)
		{
			pLog.error("[mdm-factory] packaged perspective unreadable: " + ex);
			return;
		}
		if (PerspectiveInstaller.attempt(pRepository, pSession, pLog, definition, false))
		{
			return;
		}
		final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
		{
			@Override
			public Thread newThread(final Runnable pRunnable)
			{
				final Thread thread = new Thread(pRunnable, "ebx-mdm-factory-perspective");
				thread.setDaemon(true);
				return thread;
			}
		});
		executor.schedule(new Runnable()
		{
			private int attempt = 1;

			@Override
			public void run()
			{
				this.attempt++;
				final boolean last = this.attempt >= PerspectiveInstaller.ATTEMPTS;
				try
				{
					if (PerspectiveInstaller.attempt(pRepository, pSession, pLog, definition, last) || last)
					{
						executor.shutdown();
						return;
					}
				}
				catch (final Throwable ex) // an Error must neither end the retry silently nor leave the thread behind
				{
					pLog.warn("[mdm-factory] perspective install attempt " + this.attempt + " failed: " + ex);
					if (last)
					{
						executor.shutdown();
						return;
					}
				}
				executor.schedule(this, PerspectiveInstaller.RETRY_SECONDS, TimeUnit.SECONDS);
			}
		}, PerspectiveInstaller.RETRY_SECONDS, TimeUnit.SECONDS);
	}

	static JsonNode definition() throws java.io.IOException
	{
		final InputStream input = PerspectiveInstaller.class.getResourceAsStream(PerspectiveInstaller.RESOURCE);
		if (input == null)
		{
			throw new java.io.FileNotFoundException(PerspectiveInstaller.RESOURCE);
		}
		try
		{
			return PerspectiveInstaller.MAPPER.readTree(input);
		}
		finally
		{
			input.close();
		}
	}

	/** @return true when nothing is left to do (installed, already present, or failed for good). */
	private static boolean attempt(final Repository pRepository, final Session pSession, final LoggingCategory pLog, final JsonNode pDefinition,
		final boolean pLastAttempt)
	{
		final String key = pDefinition.path("key").asText();
		try
		{
			final AdaptationHome home = pRepository.lookupHome(PerspectiveInstaller.EBX_MANAGER_HOME);
			final Adaptation parent = home == null ? null : home.findAdaptationOrNull(PerspectiveInstaller.EBX_MANAGER_ROOT);
			if (parent == null)
			{
				if (pLastAttempt)
				{
					pLog.warn("[mdm-factory] perspective " + key + " not installed: the ebx-manager dataset is unavailable");
				}
				return false;
			}
			// audit F-06: this writes into EBX's own ebx-manager dataset at every start - refuse, once, when this build does not have the fields below
			final String refusal = WriterShapes.refusal(Writer.PERSPECTIVE, Locale.ENGLISH, parent.getSchemaNode(), PerspectiveInstaller.expectations());
			if (refusal != null)
			{
				pLog.warn("[mdm-factory] perspective " + key + " not installed or completed: " + refusal);
				return true; // a refusal is not "ebx-manager not ready": retrying every 30 s would only repeat it
			}
			final Adaptation existing = home.findAdaptationOrNull(AdaptationName.forName(key));
			if (existing != null)
			{
				// existing sections/items are never moved, renamed or deleted - only what the package has and
				// the running perspective doesn't is added, at the end of the right section (see PerspectiveCompletion).
				// A failure here is never "ebx-manager not ready" (that case returned above, before this point was
				// even reached) - it is a bug or a refused write, and retrying the exact same write ten times every
				// 30s would only repeat it ten times: caught on its own, logged ONCE as a warning (no stack trace;
				// EBX's own kernel log already records the failed procedure's stack trace on its own, once, when it
				// happens - this is not a second copy of that), and treated as done so the schedule below never runs.
				try
				{
					final int added = PerspectiveInstaller.complete(pSession, home, existing, pDefinition, pLog);
					pLog.info(
						"[mdm-factory] perspective " + key + " already present" + (added == 0 ? ", nothing to add" : ", added " + added + " missing menu item(s)"));
				}
				catch (final RuntimeException ex)
				{
					pLog.warn("[mdm-factory] perspective " + key + " completion failed, not retried: " + ex);
				}
				return true;
			}
			final int[] count = { 0 };
			final String[] defaultId = { null };
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, home).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					// parent first, then the new child (the reverse order reports the child as a missing parent)
					final Adaptation child = pContext.doCreateChild(parent.getAdaptationName(), AdaptationReference.forPersistentName(key), Profile.ADMINISTRATOR);
					pContext.setInstanceLabel(child, UserMessage.createInfo(pDefinition.path("labelEn").asText(key)));
					final AdaptationTable menuItems = child.getTable(PerspectiveInstaller.MENU_ITEM_TABLE);
					count[0] = PerspectiveInstaller.createNodes(pContext, menuItems, pDefinition.path("items"), null, 1000, defaultId);
					final ValueContextForUpdate properties = pContext.getContext(child.getAdaptationName());
					properties.setValue(Boolean.valueOf(pDefinition.path("activated").asBoolean(true)), PerspectiveInstaller.PROPERTIES_ACTIVATED);
					if (defaultId[0] != null)
					{
						// foreign key to domain/menuItem: the item opened when the perspective is selected
						properties.setValue(defaultId[0], PerspectiveInstaller.PROPERTIES_DEFAULT_SELECTION);
					}
					pContext.doModifyContent(child, properties);
				}
			});
			if (result.hasFailed())
			{
				pLog.error("[mdm-factory] perspective " + key + " not installed: " + result.getExceptionFullMessage(Locale.ENGLISH));
				return true;
			}
			pLog.info("[mdm-factory] perspective " + key + " installed (" + count[0] + " menu items)");
			return true;
		}
		catch (final RuntimeException ex)
		{
			if (pLastAttempt)
			{
				pLog.error("[mdm-factory] perspective " + key + " not installed: " + ex);
			}
			return false;
		}
	}

	// ------------------------------------------------------------------ completion of an already-installed perspective

	/**
	 * Reads what {@code pExisting} currently holds and what {@code pDefinition} packages, asks the pure
	 * {@link PerspectiveCompletion#decide} what is missing, and writes only that — new sections in full,
	 * new items appended at the end of an existing section's current children. One transaction; a decision of
	 * "nothing missing" never opens one. Every intended addition is logged at INFO BEFORE the write, not only
	 * after: a wrong match (an item the matching rule thinks is missing but is actually already there, just
	 * under a different label than expected — the "known limitation" the coordinator asked to be told about,
	 * not just left to be found as a duplicate in the menu) is visible in the log even if the write itself
	 * then fails or is never looked at again. @return how many menu item rows were created.
	 */
	private static int complete(final Session pSession, final AdaptationHome pHome, final Adaptation pExisting, final JsonNode pDefinition,
		final LoggingCategory pLog)
	{
		final AdaptationTable menuItems = pExisting.getTable(PerspectiveInstaller.MENU_ITEM_TABLE);
		final List<Adaptation> allRows = PerspectiveInstaller.allRows(menuItems);
		final List<PerspectiveCompletion.Section<String>> existingSections = PerspectiveInstaller.existingTree(allRows);
		final List<PerspectiveCompletion.Section<JsonNode>> packagedSections = PerspectiveInstaller.packagedTree(pDefinition);
		final List<PerspectiveCompletion.Addition<JsonNode>> additions = PerspectiveCompletion.decide(existingSections, packagedSections);
		if (additions.isEmpty())
		{
			return 0;
		}
		for (final PerspectiveCompletion.Addition<JsonNode> addition : additions)
		{
			if (addition.newSection)
			{
				pLog.info("[mdm-factory] perspective completion intends to add section \"" + addition.packagedSection.labelEn + "\" (" + addition.missingItems.size()
					+ " item(s)) - it matched no existing top-level section by this label");
			}
			else
			{
				for (final PerspectiveCompletion.Item<JsonNode> item : addition.missingItems)
				{
					pLog.info("[mdm-factory] perspective completion intends to add \"" + item.labelEn + "\" (target " + item.target + ") under \""
						+ addition.packagedSection.labelEn + "\" (existing section id " + addition.existingSectionId
						+ ") - no existing item under that section pointed at this target");
				}
			}
		}
		final int[] count = { 0 };
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pHome).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				for (final PerspectiveCompletion.Addition<JsonNode> addition : additions)
				{
					if (addition.newSection)
					{
						final int order = PerspectiveInstaller.maxOrder(allRows, null) + 1000;
						final JsonNode sectionNode = addition.packagedSection.payload;
						final int sectionId = PerspectiveInstaller.createNode(pContext, menuItems, sectionNode, null, order);
						final String[] ignoredDefault = { null };
						count[0] += 1 + PerspectiveInstaller.createNodes(pContext, menuItems, sectionNode.path("children"), String.valueOf(sectionId), 1000,
							ignoredDefault);
					}
					else
					{
						int order = PerspectiveInstaller.maxOrder(allRows, addition.existingSectionId) + 1000;
						for (final PerspectiveCompletion.Item<JsonNode> item : addition.missingItems)
						{
							PerspectiveInstaller.createNode(pContext, menuItems, item.payload, addition.existingSectionId, order);
							order += 1000;
							count[0]++;
						}
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw new IllegalStateException(result.getExceptionFullMessage(Locale.ENGLISH));
		}
		return count[0];
	}

	private static List<Adaptation> allRows(final AdaptationTable pMenuItems)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final com.onwbp.adaptation.RequestResult rows = pMenuItems.createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * The installed perspective as plain sections/items: a top-level row (no {@code parent}) is a section, its
	 * label is read from {@code label/localizedDocumentations} (en-US), and {@link PerspectiveCompletion.Section#payload}
	 * is the section row's own id ({@code String}, the same id format {@code parent} stores its children under) —
	 * the existing side's payload type is always {@code String} (see {@link PerspectiveCompletion#decide}), never
	 * a {@link JsonNode}: {@link #complete} only ever writes an existing section's payload back as a parent id,
	 * never as node content, so there is nothing to confuse it with the packaged side's own payload type.
	 */
	private static List<PerspectiveCompletion.Section<String>> existingTree(final List<Adaptation> pAllRows)
	{
		final List<PerspectiveCompletion.Section<String>> out = new ArrayList<PerspectiveCompletion.Section<String>>();
		for (final Adaptation row : pAllRows)
		{
			if (row.get(PerspectiveInstaller.MI_PARENT) != null)
			{
				continue;
			}
			final String sectionId = String.valueOf(row.get(Path.parse("id")));
			final List<PerspectiveCompletion.Item<String>> items = new ArrayList<PerspectiveCompletion.Item<String>>();
			for (final Adaptation child : pAllRows)
			{
				final Object parent = child.get(PerspectiveInstaller.MI_PARENT);
				if (parent != null && sectionId.equals(String.valueOf(parent)))
				{
					items.add(new PerspectiveCompletion.Item<String>(PerspectiveInstaller.labelEn(child), PerspectiveInstaller.existingTarget(child), null));
				}
			}
			out.add(new PerspectiveCompletion.Section<String>(PerspectiveInstaller.labelEn(row), items, sectionId));
		}
		return out;
	}

	/** The packaged JSON as plain sections/items; each {@link PerspectiveCompletion.Item}/{@link PerspectiveCompletion.Section}'s payload is its own {@link JsonNode}, ready for {@link #createNode}/{@link #createNodes}. */
	private static List<PerspectiveCompletion.Section<JsonNode>> packagedTree(final JsonNode pDefinition)
	{
		final List<PerspectiveCompletion.Section<JsonNode>> out = new ArrayList<PerspectiveCompletion.Section<JsonNode>>();
		for (final JsonNode sectionNode : pDefinition.path("items"))
		{
			final List<PerspectiveCompletion.Item<JsonNode>> items = new ArrayList<PerspectiveCompletion.Item<JsonNode>>();
			for (final JsonNode itemNode : sectionNode.path("children"))
			{
				items.add(new PerspectiveCompletion.Item<JsonNode>(PerspectiveInstaller.text(itemNode, "labelEn"), PerspectiveInstaller.packagedTarget(itemNode), itemNode));
			}
			out.add(new PerspectiveCompletion.Section<JsonNode>(PerspectiveInstaller.text(sectionNode, "labelEn"), items, sectionNode));
		}
		return out;
	}

	/**
	 * {@code serviceKey} for a generic action, or the bare {@code xpath} for the table shorthand — normalized
	 * (dataspace/dataset are deliberately left out): every table-shorthand item in this perspective points at
	 * the same {@code MdmFactory} dataspace/dataset, so the xpath alone already uniquely identifies the target,
	 * and comparing the fuller triple risked a false "missing" on a harmless formatting difference between how
	 * the packaged JSON and an already-installed row happen to spell the same dataspace/dataset (e.g. an item
	 * added to the live perspective by a different tool than this installer).
	 */
	private static String packagedTarget(final JsonNode pNode)
	{
		final String serviceKey = PerspectiveInstaller.text(pNode, "serviceKey");
		if (serviceKey != null)
		{
			return serviceKey;
		}
		return PerspectiveInstaller.text(pNode, "xpath");
	}

	/** Same target shape as {@link #packagedTarget}, read off an installed row instead of a {@link JsonNode}: the table shorthand's own xpath is only in {@code action/serviceParameters}, not in {@code action/serviceKey} itself. */
	private static String existingTarget(final Adaptation pRow)
	{
		final Object serviceKey = pRow.get(PerspectiveInstaller.MI_ACTION_SERVICE_KEY);
		if (!"ebx-root-1.0@default".equals(serviceKey))
		{
			return serviceKey == null ? "" : String.valueOf(serviceKey);
		}
		final Object rawParams = pRow.get(PerspectiveInstaller.MI_ACTION_SERVICE_PARAMETERS);
		if (rawParams instanceof List)
		{
			final SchemaNode paramsNode = pRow.getSchemaNode().getNode(PerspectiveInstaller.MI_ACTION_SERVICE_PARAMETERS);
			final SchemaNode nameNode = paramsNode.getNode(Path.parse("name"));
			final SchemaNode valueNode = paramsNode.getNode(Path.parse("value"));
			for (final Object occurrence : (List<?>) rawParams)
			{
				final Object name = nameNode.executeRead(occurrence);
				if ("xpath".equals(name))
				{
					final Object value = valueNode.executeRead(occurrence);
					return value == null ? null : String.valueOf(value);
				}
			}
		}
		return null;
	}

	private static String labelEn(final Adaptation pRow)
	{
		final Object raw = pRow.get(PerspectiveInstaller.MI_LABEL_DOCS);
		if (!(raw instanceof List))
		{
			return null;
		}
		final SchemaNode docsNode = pRow.getSchemaNode().getNode(PerspectiveInstaller.MI_LABEL_DOCS);
		final SchemaNode localeNode = docsNode.getNode(Path.parse("locale"));
		final SchemaNode labelNode = docsNode.getNode(Path.parse("label"));
		for (final Object occurrence : (List<?>) raw)
		{
			final Object locale = localeNode.executeRead(occurrence);
			final String tag = locale instanceof Locale ? ((Locale) locale).toLanguageTag() : String.valueOf(locale);
			if ("en-US".equals(tag))
			{
				return (String) labelNode.executeRead(occurrence);
			}
		}
		return null;
	}

	/** Highest {@code order} among the direct children of {@code pParentId} (null = top level); 0 when there are none, so the first addition still lands on 1000. */
	private static int maxOrder(final List<Adaptation> pAllRows, final String pParentId)
	{
		int max = 0;
		for (final Adaptation row : pAllRows)
		{
			final Object parent = row.get(PerspectiveInstaller.MI_PARENT);
			final boolean sameParent = pParentId == null ? parent == null : pParentId.equals(String.valueOf(parent));
			if (!sameParent)
			{
				continue;
			}
			final Object order = row.get(PerspectiveInstaller.MI_ORDER);
			if (order instanceof Number && ((Number) order).intValue() > max)
			{
				max = ((Number) order).intValue();
			}
		}
		return max;
	}

	private static int createNodes(final ProcedureContext pContext, final AdaptationTable pMenuItems, final JsonNode pNodes, final String pParentId,
		final int pStartOrder, final String[] pDefaultId) throws Exception
	{
		int count = 0;
		int order = pStartOrder;
		for (final JsonNode node : pNodes)
		{
			final int id = PerspectiveInstaller.createNode(pContext, pMenuItems, node, pParentId, order);
			if (node.path("default").asBoolean(false))
			{
				pDefaultId[0] = String.valueOf(id);
			}
			count += 1 + PerspectiveInstaller.createNodes(pContext, pMenuItems, node.path("children"), String.valueOf(id), 1000, pDefaultId);
			order += 1000;
		}
		return count;
	}

	private static int createNode(final ProcedureContext pContext, final AdaptationTable pMenuItems, final JsonNode pNode, final String pParentId, final int pOrder)
		throws Exception
	{
		final String type = pNode.path("type").asText();
		final ValueContextForUpdate row = pContext.getContextForNewOccurrence(pMenuItems);
		row.setValue(type, PerspectiveInstaller.MI_TYPE);
		if (pParentId != null)
		{
			row.setValue(pParentId, PerspectiveInstaller.MI_PARENT);
		}
		row.setValue(Integer.valueOf(pOrder), PerspectiveInstaller.MI_ORDER);
		row.setValue(Boolean.valueOf(pNode.path("hasTopSeparator").asBoolean(false)), PerspectiveInstaller.MI_HAS_TOP_SEPARATOR);

		final String iconCss = PerspectiveInstaller.text(pNode, "iconCss");
		final String iconUrl = PerspectiveInstaller.text(pNode, "iconUrl");
		if (iconCss != null || iconUrl != null)
		{
			row.setValue(iconCss != null ? "css" : "url", PerspectiveInstaller.MI_ICON_TYPE);
			row.setValue(iconCss != null ? iconCss : iconUrl, PerspectiveInstaller.MI_ICON_REFERENCE);
		}
		final String labelEn = PerspectiveInstaller.text(pNode, "labelEn");
		final String labelFr = PerspectiveInstaller.text(pNode, "labelFr");
		if (labelEn != null || labelFr != null)
		{
			final SchemaNode docsNode = row.getNode(PerspectiveInstaller.MI_LABEL_DOCS);
			final SchemaNode localeNode = docsNode.getNode(Path.parse("locale"));
			final SchemaNode labelNode = docsNode.getNode(Path.parse("label"));
			final List<Object> docs = new ArrayList<Object>();
			// the locale field maps to java.util.Locale, not String
			docs.add(PerspectiveInstaller.occurrence(docsNode, localeNode, Locale.forLanguageTag("en-US"), labelNode, labelEn));
			docs.add(PerspectiveInstaller.occurrence(docsNode, localeNode, Locale.forLanguageTag("fr-FR"), labelNode, labelFr != null ? labelFr : labelEn));
			row.setValue(docs, PerspectiveInstaller.MI_LABEL_DOCS);
		}
		if ("action".equals(type))
		{
			row.setValue("S", PerspectiveInstaller.MI_ACTION_TYPE);
			final SchemaNode paramsNode = row.getNode(PerspectiveInstaller.MI_ACTION_SERVICE_PARAMETERS);
			final SchemaNode nameNode = paramsNode.getNode(Path.parse("name"));
			final SchemaNode valueNode = paramsNode.getNode(Path.parse("value"));
			final List<Object> params = new ArrayList<Object>();
			final String serviceKey = PerspectiveInstaller.text(pNode, "serviceKey");
			if (serviceKey != null)
			{
				row.setValue(serviceKey, PerspectiveInstaller.MI_ACTION_SERVICE_KEY);
				for (final JsonNode param : pNode.path("parameters"))
				{
					params.add(PerspectiveInstaller.occurrence(paramsNode, nameNode, param.path("name").asText(), valueNode, PerspectiveInstaller.text(param, "value")));
				}
			}
			else
			{
				// table shorthand: "Access data" on dataspace / dataset / xpath, labelled by the table itself
				row.setValue("ebx-root-1.0@default", PerspectiveInstaller.MI_ACTION_SERVICE_KEY);
				params.add(PerspectiveInstaller.occurrence(paramsNode, nameNode, "service", valueNode, "@default"));
				params.add(PerspectiveInstaller.occurrence(paramsNode, nameNode, "branch", valueNode, PerspectiveInstaller.text(pNode, "dataspace")));
				params.add(PerspectiveInstaller.occurrence(paramsNode, nameNode, "instance", valueNode, PerspectiveInstaller.text(pNode, "dataset")));
				params.add(PerspectiveInstaller.occurrence(paramsNode, nameNode, "xpath", valueNode, PerspectiveInstaller.text(pNode, "xpath")));
			}
			row.setValue(params, PerspectiveInstaller.MI_ACTION_SERVICE_PARAMETERS);
		}
		final Adaptation created = pContext.doCreateOccurrence(row, pMenuItems);
		return ((Number) created.get(Path.parse("id"))).intValue();
	}

	private static Object occurrence(final SchemaNode pGroupNode, final SchemaNode pFirstNode, final Object pFirstValue, final SchemaNode pSecondNode,
		final Object pSecondValue)
	{
		final Object occurrence = pGroupNode.createNewOccurrence();
		pFirstNode.executeWrite(pFirstValue, occurrence);
		if (pSecondValue != null)
		{
			pSecondNode.executeWrite(pSecondValue, occurrence);
		}
		return occurrence;
	}

	private static String text(final JsonNode pNode, final String pField)
	{
		final JsonNode value = pNode.get(pField);
		return value == null || value.isNull() || value.asText().trim().isEmpty() ? null : value.asText();
	}
}
