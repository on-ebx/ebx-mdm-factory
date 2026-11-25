package com.on.ebx.factory.rejects;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

/**
 * The identity of one {@code TransferReject} row as the review screen carries it in a checkbox: the four parts of its natural key
 * after the project (entity id, stage, source system, source id), each percent-encoded and joined with {@code |} - a source id may
 * hold any character, including the pipe. Pure.
 */
public final class RejectRef
{
	public final String entityId;
	public final String stage;
	public final String sourceSystem;
	public final String sourceId;

	public RejectRef(final String pEntityId, final String pStage, final String pSourceSystem, final String pSourceId)
	{
		this.entityId = pEntityId == null ? "" : pEntityId;
		this.stage = pStage == null ? "" : pStage;
		this.sourceSystem = pSourceSystem == null ? "" : pSourceSystem;
		this.sourceId = pSourceId == null ? "" : pSourceId;
	}

	public String encode()
	{
		return RejectRef.enc(this.entityId) + "|" + RejectRef.enc(this.stage) + "|" + RejectRef.enc(this.sourceSystem) + "|" + RejectRef.enc(this.sourceId);
	}

	/** The reference an {@link #encode}d text stands for; null when the text is not four parts. */
	public static RejectRef decode(final String pText)
	{
		if (pText == null)
		{
			return null;
		}
		final String[] parts = pText.split("\\|", -1);
		if (parts.length != 4)
		{
			return null;
		}
		return new RejectRef(RejectRef.dec(parts[0]), RejectRef.dec(parts[1]), RejectRef.dec(parts[2]), RejectRef.dec(parts[3]));
	}

	private static String enc(final String pValue)
	{
		try
		{
			return URLEncoder.encode(pValue, "UTF-8");
		}
		catch (final UnsupportedEncodingException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	private static String dec(final String pValue)
	{
		try
		{
			return URLDecoder.decode(pValue, "UTF-8");
		}
		catch (final UnsupportedEncodingException ex)
		{
			throw new IllegalStateException(ex);
		}
	}
}
