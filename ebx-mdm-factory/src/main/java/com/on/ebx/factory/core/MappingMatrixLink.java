package com.on.ebx.factory.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Builds the URL of the "Mapping matrix" screen (docs/MAPPING-MATRIX.md), opened from the wizard's step 4
 * (next to an entity's feeds) and, once acted on, could be opened from a FEED/MAPPING review line the same
 * way {@link FeedDesignerLink} is. Same shape and the same reason as {@link FeedDesignerLink}:
 * {@code ebx.ui.MappingMatrixServiceDeclaration} is declared {@code OnTableView} on {@code /root/Project}
 * (no fixed record to select), so the project code and the optional entity to open travel as {@code
 * serviceParams} - the base64 of a small JSON object - never a plain query parameter (CLAUDE.md &sect;9,
 * "the ebx-ui routes drop plain URL parameters").
 */
public final class MappingMatrixLink
{
	private static final String TABLE_URI = "/ebx-ui/ui/advanced/data/";

	private MappingMatrixLink()
	{
	}

	/** Opens the Mapping matrix on the given project, on its first entity. */
	public static String url(final String pDataspaceKey, final String pDatasetName, final String pProjectCode)
	{
		return MappingMatrixLink.url(pDataspaceKey, pDatasetName, pProjectCode, null);
	}

	/** Opens the Mapping matrix on the given project, pre-selecting {@code pEntityId} (a {@code StagingEntity} row id) when given. */
	public static String url(final String pDataspaceKey, final String pDatasetName, final String pProjectCode, final String pEntityId)
	{
		return MappingMatrixLink.TABLE_URI + MappingMatrixLink.queryValue(pDataspaceKey) + "/" + MappingMatrixLink.queryValue(pDatasetName) + "/root/Project"
			+ "?service=MappingMatrix&serviceMode=TABLE_VIEW&serviceParams=" + MappingMatrixLink.queryValue(MappingMatrixLink.serviceParams(pProjectCode, pEntityId));
	}

	/** {@code {"project":"<code>","entity":"<id>"}} (the second key omitted when {@code pEntityId} is null), base64-encoded (UTF-8) - same shape and encoding as {@code FeedDesignerLink#serviceParams}. */
	private static String serviceParams(final String pProjectCode, final String pEntityId)
	{
		final StringBuilder json = new StringBuilder("{").append(MappingMatrixLink.jsonString("project")).append(":")
			.append(MappingMatrixLink.jsonString(pProjectCode));
		if (pEntityId != null && !pEntityId.isEmpty())
		{
			json.append(",").append(MappingMatrixLink.jsonString("entity")).append(":").append(MappingMatrixLink.jsonString(pEntityId));
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
