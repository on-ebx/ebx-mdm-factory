package com.on.ebx.factory.ebx.ui;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The three navigation links the project explorer's cards offer, gathered in one small, pure class so
 * {@link ProjectExplorerPage} (which builds them per card) and any future screen needing the same links never
 * duplicate the URL shape. Pure by design (no EBX type anywhere): every method takes plain strings and returns
 * a plain string, so a fixture-driven test can check the exact encoding without a live session.
 * <p>
 * <b>2026-09-23 rewrite: the {@code /ebx/?...} web-component route is gone.</b> Measured live by the
 * coordinator: opened that way, {@code ProjectWizard} had no Close button and no way back to the explorer,
 * and the link dropped the user out of the {@code MdmFactory} perspective entirely (a web component opens a
 * minimal-scope session). Every link here now targets the {@code ebx-ui} data route instead - a form
 * rewritten by {@code ProjectExplorerUserService}'s own client-side rewrite of {@code ui/advanced/data/} to
 * {@code ui/custom/<perspectiveKey>/data/}, which keeps it inside whatever perspective the explorer itself
 * runs in (CLAUDE.md &sect;9's own "never open an /ebx-ui/... route inside the modal iframe" and "the ebx-ui
 * routes drop plain URL parameters" notes) - in the two forms measured live on sibling modules and recorded in
 * {@code docs/PROJECT-EXPLORER.md}:
 * <ul>
 * <li>{@code ProjectWizard} is {@code OnTableView} (no fixed record to select), so the launch parameter
 * travels as {@code serviceParams} - the base64 of a small JSON object - exactly like ebx-connectors'
 * {@code ConnectorForm#serviceParams}/{@code flowRunURI} and ebx-data-catalog's {@code SourcePanes#runURI}
 * already carry their own service's launch parameter through the same route: a plain {@code &project=...}
 * query parameter is silently dropped there (found live on those two modules, 2026-09-14/15).</li>
 * <li>{@code FactoryCockpit} is {@code OnRecord}, so the record is selected with a {@code predicate} query
 * parameter on the project's own primary key field, {@code ./code} - the same technique ebx-data-catalog's
 * {@code TagReviewUserService#uri} uses to open a record-scoped service from a table-level screen. That
 * class's own comment records the one encoding trap: EBX's {@code ebx-ui} route rejects the predicate's
 * slash once it is percent-encoded next to {@code service=...&serviceMode=RECORD} ({@code
 * "predicate=.%2Fid%3D1"} answers "an error prevented some elements from loading") and only accepts it
 * literal ({@code "./id%3D1"}) - so the whole predicate is run through {@link #queryValue} like any other
 * value, then its one {@code %2F} (from the predicate's own leading {@code ./}) is turned back into a literal
 * slash. An embedded quote in the project code is doubled first by {@link FactoryHtml#quote} (never a
 * string-built key - CLAUDE.md's own warning against that, moot here since {@code Project}'s key is the
 * single field {@code code}), so it survives encoding as two consecutive {@code %27}, not a broken
 * predicate.</li>
 * </ul>
 * Measured live by the coordinator on the real instance, 2026-09-23: both forms open the target service
 * inside the perspective, with its Close button and its menu. See {@code docs/PROJECT-EXPLORER.md} for
 * worked examples of what these produce for a code carrying a quote, a space and a bracket, and for the
 * contextual-return mechanism ({@code FactoryHtml#originCaptureScript}/{@code #originReturnScript}) that
 * sends the wizard/cockpit's Close button back to the explorer instead of the native table.
 */
final class FactoryLinks
{
	private static final String TABLE_URI = "/ebx-ui/ui/advanced/data/";

	private FactoryLinks()
	{
	}

	/**
	 * Opens the Cockpit ({@code OnRecord}) directly on the given project - the record is selected by a
	 * {@code predicate} query parameter on its own primary key, {@code ./code} (see this class's own Javadoc
	 * for why the predicate's slash is restored after encoding).
	 */
	static String cockpitUrl(final String pDataspaceKey, final String pDatasetName, final String pProjectCode)
	{
		final String predicate = "./code=" + FactoryHtml.quote(pProjectCode);
		return FactoryLinks.tableUri(pDataspaceKey, pDatasetName) + "?predicate=" + FactoryLinks.queryValue(predicate).replace("%2F", "/")
			+ "&service=FactoryCockpit&serviceMode=RECORD";
	}

	/** Opens the transfer-reject review ({@code OnRecord}, audit F-38) on the given project, selected the same way as {@link #cockpitUrl}. */
	static String rejectsUrl(final String pDataspaceKey, final String pDatasetName, final String pProjectCode)
	{
		final String predicate = "./code=" + FactoryHtml.quote(pProjectCode);
		return FactoryLinks.tableUri(pDataspaceKey, pDatasetName) + "?predicate=" + FactoryLinks.queryValue(predicate).replace("%2F", "/")
			+ "&service=TransferRejectReview&serviceMode=RECORD";
	}

	/**
	 * Opens the wizard ({@code OnTableView}) pre-loaded on the given project: the code travels as {@code
	 * serviceParams} (see this class's own Javadoc), never a plain query parameter, resolved by {@link
	 * ProjectWizardUserService#setupObjectContext} through {@code ProjectWizardServiceDeclaration}'s {@code
	 * project} input parameter.
	 */
	static String wizardUrl(final String pDataspaceKey, final String pDatasetName, final String pProjectCode)
	{
		return FactoryLinks.wizardUrl(pDataspaceKey, pDatasetName, pProjectCode, 0);
	}

	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug item 2): same launch route as {@link #wizardUrl(String,
	 * String, String)}, with an optional {@code pStep} (a {@code WizardRules.STEP_*} constant, 0 for "no
	 * preference") added to the same {@code serviceParams} JSON object - {@code
	 * ProjectWizardUserService#setupObjectContext} resolves it the same one-time way it already resolves
	 * {@code project}. Used by the Mapping matrix and the Feed designer screens' own "Back to the wizard"
	 * link, which always targets {@code WizardRules.STEP_STAGING} (step 4, where both screens are reached
	 * from) - this class stays EBX-free (no {@code WizardRules} import), so the caller passes the plain int.
	 */
	static String wizardUrl(final String pDataspaceKey, final String pDatasetName, final String pProjectCode, final int pStep)
	{
		return FactoryLinks.tableUri(pDataspaceKey, pDatasetName) + "?service=ProjectWizard&serviceMode=TABLE_VIEW&serviceParams="
			+ FactoryLinks.queryValue(FactoryLinks.serviceParams(pProjectCode, pStep));
	}

	/** Opens the wizard with nothing selected - the same target as the perspective's own "Project wizard" entry, i.e. a brand new project. */
	static String newProjectUrl(final String pDataspaceKey, final String pDatasetName)
	{
		return FactoryLinks.tableUri(pDataspaceKey, pDatasetName) + "?service=ProjectWizard&serviceMode=TABLE_VIEW";
	}

	/**
	 * Opens the "Delete project" screen ({@code OnTableView}, like the wizard) pre-loaded on the given project -
	 * the code travels as {@code serviceParams}, the exact same technique and shape as {@link #wizardUrl}
	 * ({@code ProjectDeleteServiceDeclaration}'s own {@code project} input parameter, resolved by
	 * {@code ProjectDeleteUserService#setupObjectContext} the same way {@code ProjectWizardUserService} already
	 * resolves its own). Project-deletion lot, 2026-09-23.
	 */
	static String deleteUrl(final String pDataspaceKey, final String pDatasetName, final String pProjectCode)
	{
		return FactoryLinks.tableUri(pDataspaceKey, pDatasetName) + "?service=ProjectDelete&serviceMode=TABLE_VIEW&serviceParams="
			+ FactoryLinks.queryValue(FactoryLinks.serviceParams(pProjectCode));
	}

	/** Opens the project explorer with nothing selected - the "back to the explorer" link on the delete screen's own report (project-deletion lot, 2026-09-23). */
	static String explorerUrl(final String pDataspaceKey, final String pDatasetName)
	{
		return FactoryLinks.tableUri(pDataspaceKey, pDatasetName) + "?service=ProjectExplorer&serviceMode=TABLE_VIEW";
	}

	/**
	 * The native table view of {@code Project}, built from the dataset's REAL home key and name (never a
	 * literal {@code "MdmFactory"} - CLAUDE.md's own warning: {@code AdaptationName.toString()} is not the
	 * dataset name, the caller must pass {@code getStringName()}), same technique as ebx-connectors'
	 * {@code ConnectorCatalogUserService.nativeView}. The caller rewrites {@code ui/advanced/data/} to
	 * {@code ui/custom/<perspectiveKey>/data/} client-side when the page is opened inside a perspective (same
	 * script as the connector explorer) - this method only ever returns the generic, perspective-agnostic form.
	 */
	static String advancedViewUrl(final String pDataspaceKey, final String pDatasetName)
	{
		return FactoryLinks.tableUri(pDataspaceKey, pDatasetName);
	}

	/**
	 * Wizard summary lot (2026-09-24): a plain advanced-view link to ANY table of ANY dataset - the landing/
	 * master zone cards of step 7 open a generated zone dataset ({@code project.landingDataspace}, or the
	 * per-entity master table under {@code project.masterDataspace}/{@code masterDataset}), never the
	 * factory's own {@code Project} table {@link #tableUri} is hardcoded to. {@code pTablePath} is the raw
	 * model path ({@code "/root/Company"}), forwarded through {@link #pathSegment} like every other path
	 * segment this class builds.
	 */
	static String genericTableUrl(final String pDataspaceKey, final String pDatasetName, final String pTablePath)
	{
		return FactoryLinks.TABLE_URI + FactoryLinks.pathSegment(pDataspaceKey) + "/" + FactoryLinks.pathSegment(pDatasetName) + FactoryLinks.tablePathSegment(pTablePath);
	}

	private static String tablePathSegment(final String pTablePath)
	{
		final String path = pTablePath == null || pTablePath.isEmpty() ? "/root" : pTablePath;
		final StringBuilder sb = new StringBuilder(path.length() + 8);
		for (final String part : path.split("/"))
		{
			if (part.isEmpty())
			{
				continue;
			}
			sb.append('/').append(FactoryLinks.pathSegment(part));
		}
		return sb.toString();
	}

	private static String tableUri(final String pDataspaceKey, final String pDatasetName)
	{
		return FactoryLinks.TABLE_URI + FactoryLinks.pathSegment(pDataspaceKey) + "/" + FactoryLinks.pathSegment(pDatasetName) + "/root/Project";
	}

	/**
	 * {@code {"project":"<code>"}}, base64-encoded (UTF-8) - the same shape and encoding ebx-connectors'
	 * {@code ConnectorForm#serviceParams} and ebx-data-catalog's {@code SourcePanes#runURI} already use to
	 * carry one launch parameter through an {@code ebx-ui} table-view service.
	 */
	private static String serviceParams(final String pProjectCode)
	{
		final String json = "{" + FactoryLinks.jsonString("project") + ":" + FactoryLinks.jsonString(pProjectCode) + "}";
		return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	/** Same as {@link #serviceParams(String)}, with an optional {@code "step"} number added (omitted when {@code pStep <= 0}) - see {@link #wizardUrl(String, String, String, int)}. */
	private static String serviceParams(final String pProjectCode, final int pStep)
	{
		final StringBuilder json = new StringBuilder("{").append(FactoryLinks.jsonString("project")).append(":").append(FactoryLinks.jsonString(pProjectCode));
		if (pStep > 0)
		{
			json.append(",").append(FactoryLinks.jsonString("step")).append(":").append(pStep);
		}
		json.append("}");
		return Base64.getEncoder().encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** Minimal JSON string escaping (quote and backslash) - the same escaping {@code ConnectorForm#jsonString} uses for the very same purpose. */
	private static String jsonString(final String pValue)
	{
		return "\"" + (pValue == null ? "" : pValue).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static String pathSegment(final String pValue)
	{
		return FactoryLinks.queryValue(pValue);
	}

	/**
	 * Plain {@link URLEncoder}, with its "a space becomes a plus" convention turned into {@code %20} - the
	 * same fix ebx-connectors' and ebx-data-catalog's own query-value helpers apply for a link into the
	 * {@code ebx-ui} data route, since that client does not unescape a query value with the
	 * {@code application/x-www-form-urlencoded} rule (a literal {@code +} would otherwise survive as a plus,
	 * never becoming a space).
	 */
	private static String queryValue(final String pValue)
	{
		try
		{
			return URLEncoder.encode(pValue == null ? "" : pValue, "UTF-8").replace("+", "%20");
		}
		catch (final UnsupportedEncodingException ex)
		{
			// UTF-8 is always supported - unreachable in practice, kept only so this stays a checked-exception-free call site
			return pValue == null ? "" : pValue;
		}
	}
}
