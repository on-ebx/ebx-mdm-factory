package com.on.ebx.factory.landing;

/**
 * Fits free text into the fixed-length fields the factory writes into: {@code PipelineStep.detail} (what
 * an MCP exchange sent and got back, for a replay or a diagnosis) and the {@code message} fields of
 * {@code PipelineStep} and {@code ProjectSource}. EBX blocks a value longer than the field's declared
 * {@code maxLength} outright (see the knowledge base): without truncation, a step that ran a real MCP
 * exchange with a large answer could fail to even record that it ran. Pure: no EBX class.
 */
public final class TextLimits
{
	/** {@code PipelineStep.detail}. */
	public static final int DETAIL_MAX_LENGTH = 100000;
	/** {@code PipelineStep.message} and {@code ProjectSource.message}. */
	public static final int MESSAGE_MAX_LENGTH = 2000;

	private TextLimits()
	{
	}

	/** {@code pText} as is when it already fits; otherwise cut to {@code pMaxLength}, with a trailing marker when there is room for one. */
	public static String truncate(final String pText, final int pMaxLength)
	{
		if (pText == null || pText.length() <= pMaxLength)
		{
			return pText;
		}
		if (pMaxLength <= 0)
		{
			return "";
		}
		final String marker = "...(truncated)";
		if (pMaxLength <= marker.length())
		{
			return pText.substring(0, pMaxLength);
		}
		return pText.substring(0, pMaxLength - marker.length()) + marker;
	}
}
