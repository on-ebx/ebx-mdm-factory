package com.on.ebx.factory.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Builds the URL of the "Designer" link the wizard's step 4 (a root {@code TransferFeed}'s own card,
 * {@code wizard.WizardPage#renderFeed}) and the Proposal review screen (a {@code FEED} line,
 * {@code review.ReviewPage}) both offer, opening {@code ebx.ui.FeedDesignerUserService} on that feed's tree.
 * <p>
 * Pure, {@code core} (not {@code ebx.ui}, where the sibling {@code FactoryLinks} lives) on purpose: both
 * {@code WizardPage} and {@code ReviewPage} are themselves pure - no EBX type, unit-tested without a live
 * repository - and {@code ebx.ui.FactoryLinks} is package-private, unreachable from either. Same URL shape
 * and the same reason as {@code FactoryLinks#wizardUrl}: {@code FeedDesignerServiceDeclaration} is declared
 * {@code OnTableView} on {@code /root/Project} (no fixed record to select, exactly like {@code
 * ProjectWizardServiceDeclaration}), so the feed id travels as {@code serviceParams} - the base64 of a small
 * JSON object - never a plain query parameter (ebx-ui silently drops one on this route family, CLAUDE.md
 * &sect;9's own "the ebx-ui routes drop plain URL parameters" note).
 */
public final class FeedDesignerLink
{
	private static final String TABLE_URI = "/ebx-ui/ui/advanced/data/";

	private FeedDesignerLink()
	{
	}

	/** Opens the feed designer ({@code OnTableView}) pre-loaded on the given feed - {@code FeedDesignerUserService.setupObjectContext} resolves the {@code feed} launch parameter the same way {@code ProjectWizardUserService} resolves its own {@code project} one. */
	public static String url(final String pDataspaceKey, final String pDatasetName, final String pFeedId)
	{
		return FeedDesignerLink.TABLE_URI + FeedDesignerLink.queryValue(pDataspaceKey) + "/" + FeedDesignerLink.queryValue(pDatasetName) + "/root/Project"
			+ "?service=FeedDesigner&serviceMode=TABLE_VIEW&serviceParams=" + FeedDesignerLink.queryValue(FeedDesignerLink.serviceParams(pFeedId));
	}

	/** {@code {"feed":"<id>"}}, base64-encoded (UTF-8) - same shape and encoding as {@code FactoryLinks#serviceParams}. */
	private static String serviceParams(final String pFeedId)
	{
		final String json = "{" + FeedDesignerLink.jsonString("feed") + ":" + FeedDesignerLink.jsonString(pFeedId) + "}";
		return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	private static String jsonString(final String pValue)
	{
		return "\"" + (pValue == null ? "" : pValue).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

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
