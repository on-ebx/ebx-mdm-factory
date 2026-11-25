package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The accelerator library as shipped on the class path under {@code com/on/ebx/factory/accel/}. Immutable once loaded. */
public final class PackCatalog
{
	private final Map<String, Pack> packs;
	private final Map<String, Block> blocks;
	private final LibraryCatalog libraries;
	private final WorkflowTypes workflowTypes;

	private PackCatalog(final Map<String, Pack> pPacks, final Map<String, Block> pBlocks, final LibraryCatalog pLibraries, final WorkflowTypes pTypes)
	{
		this.packs = pPacks;
		this.blocks = pBlocks;
		this.libraries = pLibraries;
		this.workflowTypes = pTypes;
	}

	/**
	 * Reads every pack, block, library and workflow type named by {@code accel/index.json}.
	 *
	 * @throws IllegalStateException with the file name and the cause on any missing or malformed file or inconsistency
	 */
	public static PackCatalog load()
	{
		final JsonNode index = Res.json("index.json");
		final Map<String, Block> blocks = new TreeMap<String, Block>();
		for (final String key : Res.strings(index.get("blocks")))
		{
			blocks.put(key, new Block(key, Res.json("blocks/" + key + "/block.json")));
		}
		final Map<String, Pack> packs = new TreeMap<String, Pack>();
		for (final String key : Res.strings(index.get("packs")))
		{
			final List<String> layoutNames = Res.strings(index.path("layouts").get(key));
			packs.put(key, new Pack(key, Res.json("packs/" + key + "/pack.json"), layoutNames));
		}
		final LibraryCatalog libraries = new LibraryCatalog(Res.json("libraries.json"), "libraries.json");
		final WorkflowTypes types = new WorkflowTypes(Res.json("workflow-types.json"), "workflow-types.json");
		final PackCatalog catalog = new PackCatalog(Collections.unmodifiableMap(packs), Collections.unmodifiableMap(blocks), libraries, types);
		catalog.checkConsistency();
		return catalog;
	}

	private void checkConsistency()
	{
		for (final Pack p : this.packs.values())
		{
			for (final String need : p.requires().packs())
			{
				if (!this.packs.containsKey(need))
				{
					throw new IllegalStateException("Pack " + p.key() + " requires the unknown pack " + need);
				}
			}
			for (final String b : p.blocks())
			{
				if (!this.blocks.containsKey(b))
				{
					throw new IllegalStateException("Pack " + p.key() + " lists the unknown block " + b);
				}
			}
			for (final BlockPlacement bp : p.core().blockPlacements())
			{
				if (!this.blocks.containsKey(bp.block()))
				{
					throw new IllegalStateException("Pack " + p.key() + " places the unknown block " + bp.block());
				}
			}
			for (final ObjectNode op : p.core().ops())
			{
				if (!PackOps.KNOWN_OPS.contains(op.path("op").asText()))
				{
					throw new IllegalStateException("Pack " + p.key() + " core.json uses the unknown operation '" + op.path("op").asText() + "'");
				}
			}
		}
		for (final Block b : this.blocks.values())
		{
			for (final Block.Script s : b.scripts())
			{
				for (final String lib : s.uses())
				{
					if (this.libraries.library(lib) == null)
					{
						throw new IllegalStateException("Block " + b.key() + " script " + s.name() + " uses the unknown library " + lib);
					}
				}
			}
		}
	}

	/** Sorted by key. */
	public List<Pack> packs()
	{
		return Collections.unmodifiableList(new ArrayList<Pack>(this.packs.values()));
	}

	/** Null when absent. */
	public Pack pack(final String pKey)
	{
		return this.packs.get(pKey);
	}

	/** Sorted by key. */
	public List<Block> blocks()
	{
		return Collections.unmodifiableList(new ArrayList<Block>(this.blocks.values()));
	}

	/** Null when absent. */
	public Block block(final String pKey)
	{
		return this.blocks.get(pKey);
	}

	public LibraryCatalog libraries()
	{
		return this.libraries;
	}

	public WorkflowTypes workflowTypes()
	{
		return this.workflowTypes;
	}

	/**
	 * Packs matching a need by keywords alone (no model call): a pack keyword found in the need weighs 3, a word of the need
	 * (4 letters or more) found in the pack's labels, descriptions or guidance weighs 1. Best score first, then key.
	 */
	public List<Pack> suggest(final String pNeed)
	{
		final List<Pack> out = new ArrayList<Pack>();
		if (pNeed == null || pNeed.trim().isEmpty())
		{
			return out;
		}
		final String need = pNeed.toLowerCase(Locale.ROOT);
		final List<String> words = new ArrayList<String>();
		for (final String w : need.split("[^\\p{L}\\p{N}]+"))
		{
			if (w.length() >= 4)
			{
				words.add(w);
			}
		}
		final Map<Pack, Integer> scores = new LinkedHashMap<Pack, Integer>();
		for (final Pack p : this.packs.values())
		{
			int score = 0;
			for (final String k : p.keywords())
			{
				if (need.contains(k.toLowerCase(Locale.ROOT)))
				{
					score += 3;
				}
			}
			final StringBuilder text = new StringBuilder();
			for (final String t : p.allTexts())
			{
				text.append(t.toLowerCase(Locale.ROOT)).append(' ');
			}
			text.append(p.guidance().toLowerCase(Locale.ROOT));
			for (final String w : words)
			{
				if (text.indexOf(w) >= 0)
				{
					score++;
				}
			}
			if (score > 0)
			{
				scores.put(p, Integer.valueOf(score));
			}
		}
		out.addAll(scores.keySet());
		Collections.sort(out, new Comparator<Pack>()
		{
			@Override
			public int compare(final Pack a, final Pack b)
			{
				final int byScore = scores.get(b).compareTo(scores.get(a));
				return byScore != 0 ? byScore : a.key().compareTo(b.key());
			}
		});
		return out;
	}

	/** One line per pack, then one per block: what the pack-choice and model passes paste into their prompts. */
	public String promptText()
	{
		final StringBuilder sb = new StringBuilder();
		for (final Pack p : this.packs.values())
		{
			sb.append("pack ").append(p.promptLine()).append('\n');
		}
		for (final Block b : this.blocks.values())
		{
			sb.append("block ").append(b.promptLine()).append('\n');
		}
		return sb.toString();
	}
}
