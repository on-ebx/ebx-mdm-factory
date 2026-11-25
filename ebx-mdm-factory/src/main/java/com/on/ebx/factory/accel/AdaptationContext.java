package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a proposal pass is told about the project (lot C1-C): the chosen packs and options, the specification text, and the differences
 * standing after the previous passes. Pure value, built by {@code ebx.AdaptationProposalRun} from the {@code ProjectPack} and
 * {@code Difference} rows. The specification is the user's own text; no table value is ever part of it.
 */
public final class AdaptationContext
{
	private final PackCatalog catalogue;
	private final List<Pack> packs;
	private final List<String> options;
	private final String specification;
	private final List<Difference> decided;

	/**
	 * @param pDecided the differences standing after the previous passes (accepted, or still proposed when "run all" was asked), never the rejected ones; may be null
	 */
	public AdaptationContext(final PackCatalog pCatalogue, final List<Pack> pPacks, final List<String> pOptions, final String pSpecification,
		final List<Difference> pDecided)
	{
		this.catalogue = pCatalogue;
		this.packs = Collections.unmodifiableList(new ArrayList<Pack>(pPacks == null ? new ArrayList<Pack>() : pPacks));
		this.options = Collections.unmodifiableList(new ArrayList<String>(pOptions == null ? new ArrayList<String>() : pOptions));
		this.specification = pSpecification == null ? "" : pSpecification;
		this.decided = Collections.unmodifiableList(new ArrayList<Difference>(pDecided == null ? new ArrayList<Difference>() : pDecided));
	}

	public PackCatalog catalogue()
	{
		return this.catalogue;
	}

	public List<Pack> packs()
	{
		return this.packs;
	}

	public List<String> options()
	{
		return this.options;
	}

	public String specification()
	{
		return this.specification;
	}

	public List<Difference> decided()
	{
		return this.decided;
	}

	/** The model after the packs' cores and the decided differences. */
	public ModelPaths model()
	{
		return ModelPaths.of(this.catalogue, this.packs, this.decided);
	}

	public DifferenceCheck.CheckContext checkContext()
	{
		return new DifferenceCheck.CheckContext(this.catalogue, this.packs, this.decided);
	}
}
