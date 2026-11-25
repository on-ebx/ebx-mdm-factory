package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The answer of pass 0: the packs and options the model suggests for a need, and why. */
public final class PackChoice
{
	private final List<String> packs;
	private final List<String> options;
	private final String why;

	public PackChoice(final List<String> pPacks, final List<String> pOptions, final String pWhy)
	{
		this.packs = Collections.unmodifiableList(new ArrayList<String>(pPacks));
		this.options = Collections.unmodifiableList(new ArrayList<String>(pOptions));
		this.why = pWhy == null ? "" : pWhy;
	}

	public List<String> packs()
	{
		return this.packs;
	}

	public List<String> options()
	{
		return this.options;
	}

	public String why()
	{
		return this.why;
	}

	/** The same choice without the pack keys the catalogue does not know, and the options no kept pack declares. */
	public PackChoice restrictedTo(final PackCatalog pCatalogue)
	{
		final List<String> p = new ArrayList<String>();
		final List<String> o = new ArrayList<String>();
		for (final String key : this.packs)
		{
			if (pCatalogue.pack(key) != null && !p.contains(key))
			{
				p.add(key);
			}
		}
		for (final String option : this.options)
		{
			for (final String key : p)
			{
				if (pCatalogue.pack(key).options().contains(option) && !o.contains(option))
				{
					o.add(option);
				}
			}
		}
		return new PackChoice(p, o, this.why);
	}
}
