package com.on.ebx.factory.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Builds the URL of the "Validation desk" screen (docs/STAGING-DESK.md), the fourth view of step 4 of the wizard next to the wizard card, the Mapping matrix
 * and the Feed designer. Same shape and the same reason as {@link MappingMatrixLink}: {@code ebx.ui.StagingDeskServiceDeclaration} is {@code OnTableView} on
 * {@code /root/Project}, so the project code and the optional business object to open on travel as {@code serviceParams} (the base64 of a small JSON object),
 * never as plain query parameters (CLAUDE.md &sect;9, "the ebx-ui routes drop plain URL parameters").
 */
public final class StagingDeskLink
{
	private static final String TABLE_URI = "/ebx-ui/ui/advanced/data/";

	private StagingDeskLink()
	{
	}

	/** Opens the Validation desk on the given project, on its first group. */
	public static String url(final String pDataspaceKey, final String pDatasetName, final String pProjectCode)
	{
		return StagingDeskLink.url(pDataspaceKey, pDatasetName, pProjectCode, null);
	}

	/** Opens the Validation desk on the given project, on the group of {@code pEntityId} (a {@code StagingEntity} row id) when given. */
	public static String url(final String pDataspaceKey, final String pDatasetName, final String pProjectCode, final String pEntityId)
	{
		return StagingDeskLink.TABLE_URI + StagingDeskLink.queryValue(pDataspaceKey) + "/" + StagingDeskLink.queryValue(pDatasetName) + "/root/Project"
			+ "?service=StagingDesk&serviceMode=TABLE_VIEW&serviceParams=" + StagingDeskLink.queryValue(StagingDeskLink.serviceParams(pProjectCode, pEntityId));
	}

	/** {@code {"project":"<code>","entity":"<id>"}} (the second key omitted when {@code pEntityId} is null), base64-encoded (UTF-8) - same shape and encoding as {@code FeedDesignerLink#serviceParams}. */
	private static String serviceParams(final String pProjectCode, final String pEntityId)
	{
		final StringBuilder json = new StringBuilder("{").append(StagingDeskLink.jsonString("project")).append(":")
			.append(StagingDeskLink.jsonString(pProjectCode));
		if (pEntityId != null && !pEntityId.isEmpty())
		{
			json.append(",").append(StagingDeskLink.jsonString("entity")).append(":").append(StagingDeskLink.jsonString(pEntityId));
		}
		json.append("}");
		return Base64.getEncoder().encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
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
			return pValue == null ? "" : pValue;
		}
	}
}
