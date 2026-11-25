package com.on.ebx.factory.accel;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One line of the adapted proposal (lot C1-C): what the language model suggests to change from the pack, in one of the natures of
 * {@code docs/ACCELERATORS.md} section 4. Pure and immutable; the EBX row is {@code Difference} (sub-lot B), written by
 * {@code ebx.DifferenceWrites}.
 * <p>
 * <b>Shape of {@link #detail()} per nature</b> (ONE shape: the prompts of {@link AdaptationLlmPass} ask for it, {@link DetailShape#normalise} repairs
 * what the model wrote, {@link DomainPlan} reads it; {@code similarTo}/{@code similarNote} may be added to any ADD by the check, {@code draft:true} marks a rule):
 * <ul>
 * <li>{@code ADD} pass 1: {@code {"kind":"table|group|field","fieldType","maxLength","mandatory","target":"/root/T" (foreign key),"fields":[{"name","type","maxLength","mandatory"}] (table)}};
 * or {@code {"ops":[patch operations]}} (what a person types when editing); {@code where} must be an absolute path, else the line stays TO_PLACE and the generator skips it.</li>
 * <li>{@code ADD} pass 2: {@code {"kind":"rule","rule":"one sentence","draft":true,"script":optional EBX Script}}; authored as a draft, never published without a person.</li>
 * <li>{@code ADD} pass 4: {@code {"kind":"role","role":"Name","does":"phrase","create","overwrite","delete"}}.</li>
 * <li>{@code ADD}/{@code CHANGE} pass 3: {@code {"layout":{Layout Designer JSON of the table in "where"}}}.</li>
 * <li>{@code CHANGE} pass 1: {@code {"property":"mandatory|maxLength","value":"..."}} or {@code {"properties":{property:value}}}.</li>
 * <li>{@code REMOVE}: no detail; {@code USE_BLOCK}: {@code {"group":"name"}} (optional); {@code KEEP}: none.</li>
 * <li>{@code USE_LIBRARY}: {@code {"exactLength":n,"message":"...","lengthMessage":"..."}} (all optional; a legacy {@code wrap} note is kept and ignored).</li>
 * <li>{@code USE_WORKFLOW}: the parameters of the type, flat: {@code {"requester","reviewers":["Role"],"mode","acceptLabel","rejectLabel"}} ({@code {"parameters":{...}}} is read too).</li>
 * </ul>
 */
public final class Difference
{
	/** The natures of a difference. */
	public enum Nature
	{
		ADD, CHANGE, REMOVE, USE_BLOCK, USE_LIBRARY, USE_WORKFLOW, KEEP;

		/** Case, dashes and spaces tolerated ("use-block", "Use block"); null when unknown. */
		public static Nature parse(final String pText)
		{
			if (pText == null)
			{
				return null;
			}
			final String n = pText.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
			for (final Nature nature : Nature.values())
			{
				if (nature.name().equals(n))
				{
					return nature;
				}
			}
			return null;
		}
	}

	/** Whether {@code where} resolves to a path the generator can address. */
	public enum PlaceState
	{
		PLACED, TO_PLACE
	}

	private final int pass;
	private final Nature nature;
	private final String what;
	private final String where;
	private final PlaceState placeState;
	private final JsonNode detail;
	private final String why;
	private final String packKey;
	private final String blockKey;
	private final String libraryFunction;
	private final String workflowType;

	public Difference(final int pPass, final Nature pNature, final String pWhat, final String pWhere, final PlaceState pPlaceState, final JsonNode pDetail,
		final String pWhy, final String pPackKey, final String pBlockKey, final String pLibraryFunction, final String pWorkflowType)
	{
		this.pass = pPass;
		this.nature = pNature;
		this.what = blank(pWhat);
		this.where = blank(pWhere);
		this.placeState = pPlaceState == null ? PlaceState.PLACED : pPlaceState;
		this.detail = pDetail == null || pDetail.isNull() || pDetail.isMissingNode() ? null : pDetail;
		this.why = blank(pWhy);
		this.packKey = blank(pPackKey);
		this.blockKey = blank(pBlockKey);
		this.libraryFunction = blank(pLibraryFunction);
		this.workflowType = blank(pWorkflowType);
	}

	private static String blank(final String pText)
	{
		return pText == null ? "" : pText.trim();
	}

	public int pass()
	{
		return this.pass;
	}

	public Nature nature()
	{
		return this.nature;
	}

	public String what()
	{
		return this.what;
	}

	/** A path ({@code /root/Company/Purchasing/paymentTerms}) when it resolves, whatever the model wrote otherwise. Never null. */
	public String where()
	{
		return this.where;
	}

	public PlaceState placeState()
	{
		return this.placeState;
	}

	/** Null when none. */
	public JsonNode detail()
	{
		return this.detail;
	}

	/** One line of JSON, or the empty string. */
	public String detailJson()
	{
		return this.detail == null ? "" : this.detail.toString();
	}

	public String why()
	{
		return this.why;
	}

	public String packKey()
	{
		return this.packKey;
	}

	public String blockKey()
	{
		return this.blockKey;
	}

	public String libraryFunction()
	{
		return this.libraryFunction;
	}

	public String workflowType()
	{
		return this.workflowType;
	}

	public Difference withNature(final Nature pNature)
	{
		return new Difference(this.pass, pNature, this.what, this.where, this.placeState, this.detail, this.why, this.packKey, this.blockKey, this.libraryFunction,
			this.workflowType);
	}

	public Difference withPlaceState(final PlaceState pState)
	{
		return new Difference(this.pass, this.nature, this.what, this.where, pState, this.detail, this.why, this.packKey, this.blockKey, this.libraryFunction,
			this.workflowType);
	}

	public Difference withDetail(final JsonNode pDetail)
	{
		return new Difference(this.pass, this.nature, this.what, this.where, this.placeState, pDetail, this.why, this.packKey, this.blockKey, this.libraryFunction,
			this.workflowType);
	}

	public Difference withPackKey(final String pPackKey)
	{
		return new Difference(this.pass, this.nature, this.what, this.where, this.placeState, this.detail, this.why, pPackKey, this.blockKey, this.libraryFunction,
			this.workflowType);
	}

	/** One line for logs and tests. */
	@Override
	public String toString()
	{
		return "pass " + this.pass + " " + this.nature + " " + this.what + " @ " + this.where + (this.placeState == PlaceState.TO_PLACE ? " [to place]" : "");
	}
}
