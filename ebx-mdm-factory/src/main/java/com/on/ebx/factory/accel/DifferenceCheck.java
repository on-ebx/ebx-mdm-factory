package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.factory.accel.Difference.Nature;
import com.on.ebx.factory.accel.Difference.PlaceState;

/**
 * The Java check after every model pass (docs/ACCELERATORS.md section 16, "A Java check after every pass is not optional"; LOT-C1.md
 * section 4). Pure: a parsed {@link Difference} against the chosen packs, the blocks, the published libraries and the workflow types.
 * Rules: a nature outside the pass is dropped (never written, counted); {@code where} must resolve to a pack path, a block path or an
 * ADD of the same answer, else the line is kept "to place"; an unknown library function, block or workflow type is refused; an ADD of
 * a path the pack already has becomes a CHANGE; removing a pack's main table is refused.
 */
public final class DifferenceCheck
{
	/** What the check decided. */
	public enum Kind
	{
		OK, REFUSED, TO_PLACE, DROPPED
	}

	/** The answer for one line. {@link #difference()} is the line as it should be written (nature turned to CHANGE, placeState set). */
	public static final class Verdict
	{
		private final Kind kind;
		private final Difference difference;
		private final String reasonKey;
		private final String reason;
		private final String reasonArg;

		Verdict(final Kind pKind, final Difference pDifference, final String pReasonKey, final String pReason, final String pReasonArg)
		{
			this.kind = pKind;
			this.difference = pDifference;
			this.reasonKey = pReasonKey;
			this.reason = pReason;
			this.reasonArg = pReasonArg;
		}

		public Kind kind()
		{
			return this.kind;
		}

		public boolean ok()
		{
			return this.kind == Kind.OK;
		}

		public boolean refused()
		{
			return this.kind == Kind.REFUSED;
		}

		public boolean toPlace()
		{
			return this.kind == Kind.TO_PLACE;
		}

		public boolean dropped()
		{
			return this.kind == Kind.DROPPED;
		}

		/** The line to write when {@link #ok()} or {@link #toPlace()}; the line as parsed otherwise. */
		public Difference difference()
		{
			return this.difference;
		}

		/** Bundle key {@code accel.pass.refusal.*} of the reason, or null. */
		public String reasonKey()
		{
			return this.reasonKey;
		}

		/** The identifier the reason is about (a path, a function, a block key), the {0} of the bundle sentence; empty when none. */
		public String reasonArg()
		{
			return this.reasonArg == null ? "" : this.reasonArg;
		}

		/** English reason (what a re-ask tells the model), or null when ok. */
		public String reason()
		{
			return this.reason;
		}

		@Override
		public String toString()
		{
			return this.kind + (this.reason == null ? "" : " (" + this.reason + ")") + " " + this.difference;
		}
	}

	/** What a line is checked against: the chosen packs, the catalogue, and the differences standing from the previous passes. */
	public static final class CheckContext
	{
		private final PackCatalog catalogue;
		private final List<Pack> packs;
		private final ModelPaths model;
		private final Set<String> mainTables = new LinkedHashSet<String>();
		private final Set<String> packPaths = new LinkedHashSet<String>();
		private final Set<String> batchAdds = new LinkedHashSet<String>();

		/** @param pDecided the differences standing after the previous passes (not rejected); may be null. */
		public CheckContext(final PackCatalog pCatalogue, final List<Pack> pPacks, final List<Difference> pDecided)
		{
			this.catalogue = pCatalogue;
			this.packs = pPacks;
			this.model = ModelPaths.of(pCatalogue, pPacks, pDecided);
			for (final Pack p : pPacks)
			{
				this.mainTables.add(p.mainTable());
				this.packPaths.addAll(p.core().tablePaths());
				this.packPaths.addAll(p.core().fieldPaths());
			}
		}

		private CheckContext(final CheckContext pBase, final Set<String> pBatchAdds)
		{
			this.catalogue = pBase.catalogue;
			this.packs = pBase.packs;
			this.model = pBase.model;
			this.mainTables.addAll(pBase.mainTables);
			this.packPaths.addAll(pBase.packPaths);
			this.batchAdds.addAll(pBatchAdds);
		}

		public PackCatalog catalogue()
		{
			return this.catalogue;
		}

		public ModelPaths model()
		{
			return this.model;
		}

		boolean resolves(final String pPath)
		{
			return this.model.has(pPath) || this.packPaths.contains(pPath) || this.batchAdds.contains(pPath);
		}

		boolean existsInPack(final String pPath)
		{
			return this.packPaths.contains(pPath) || this.model.has(pPath);
		}

		CheckContext withBatchAdds(final Set<String> pAdds)
		{
			return new CheckContext(this, pAdds);
		}
	}

	/** What {@link #checkAll} found in one answer. */
	public static final class Batch
	{
		public final List<Verdict> kept = new ArrayList<Verdict>();
		public final List<Verdict> refused = new ArrayList<Verdict>();
		public int dropped;

		/** The refusal reasons, one line each, for the re-ask. */
		public String refusalText()
		{
			final StringBuilder sb = new StringBuilder();
			for (final Verdict v : this.refused)
			{
				sb.append("- ").append(v.difference().what()).append(": ").append(v.reason()).append('\n');
			}
			return sb.toString();
		}
	}

	private DifferenceCheck()
	{
	}

	/** The natures a pass accepts from the model. */
	public static Set<Nature> naturesOf(final int pPass)
	{
		final Set<Nature> s = new LinkedHashSet<Nature>();
		switch (pPass)
		{
		case 1:
			s.add(Nature.ADD);
			s.add(Nature.CHANGE);
			s.add(Nature.REMOVE);
			s.add(Nature.USE_BLOCK);
			break;
		case 2:
			s.add(Nature.USE_LIBRARY);
			s.add(Nature.ADD);
			break;
		case 3:
			s.add(Nature.ADD);
			s.add(Nature.CHANGE);
			break;
		case 4:
			s.add(Nature.USE_WORKFLOW);
			s.add(Nature.ADD);
			break;
		default:
			break;
		}
		return s;
	}

	/** Checks a whole answer: the ADDs of the answer are known to every line of it. */
	public static Batch checkAll(final int pPass, final List<Difference> pAnswer, final CheckContext pContext)
	{
		final Set<String> adds = new LinkedHashSet<String>();
		if (pPass == 1)
		{
			for (final Difference d : pAnswer)
			{
				if (d.nature() == Nature.ADD && ModelPaths.PATH.matcher(d.where()).matches())
				{
					adds.add(d.where());
				}
			}
		}
		final CheckContext context = pContext.withBatchAdds(adds);
		final Batch batch = new Batch();
		for (final Difference d : pAnswer)
		{
			final Verdict v = DifferenceCheck.check(pPass, d, context);
			if (v.dropped())
			{
				batch.dropped++;
			}
			else if (v.refused())
			{
				batch.refused.add(v);
			}
			else
			{
				batch.kept.add(v);
			}
		}
		return batch;
	}

	public static Verdict check(final int pPass, final Difference pDifference, final CheckContext pContext)
	{
		final Nature nature = pDifference.nature();
		if (nature == null || !DifferenceCheck.naturesOf(pPass).contains(nature))
		{
			return new Verdict(Kind.DROPPED, pDifference, "accel.pass.refusal.outOfPass", "nature " + nature + " does not belong to pass " + pPass, String.valueOf(nature));
		}
		switch (nature)
		{
		case USE_LIBRARY:
			if (!pContext.catalogue().libraries().knows(pDifference.libraryFunction()))
			{
				return DifferenceCheck.refuse(pDifference, "accel.pass.refusal.unknownLibrary", "'" + pDifference.libraryFunction()
					+ "' is not in the catalogue of published libraries: write the fully qualified function exactly as listed (for example Fasttrack.lib.phone.isValid)",
					pDifference.libraryFunction());
			}
			return DifferenceCheck.place(pPass, pDifference, pContext, false);
		case USE_BLOCK:
			if (pContext.catalogue().block(pDifference.blockKey()) == null)
			{
				return DifferenceCheck.refuse(pDifference, "accel.pass.refusal.unknownBlock", "block '" + pDifference.blockKey() + "' is not in the catalogue of blocks", pDifference.blockKey());
			}
			return DifferenceCheck.place(pPass, pDifference, pContext, false);
		case USE_WORKFLOW:
			if (pContext.catalogue().workflowTypes().type(pDifference.workflowType()) == null)
			{
				return DifferenceCheck.refuse(pDifference, "accel.pass.refusal.unknownWorkflow", "workflow type '" + pDifference.workflowType() + "' is not in the catalogue of workflow types", pDifference.workflowType());
			}
			return DifferenceCheck.place(pPass, pDifference, pContext, false);
		case REMOVE:
			if (pContext.mainTables.contains(pDifference.where()))
			{
				return DifferenceCheck.refuse(pDifference, "accel.pass.refusal.removeMain", "'" + pDifference.where() + "' is the main table of its pack and cannot be removed", pDifference.where());
			}
			return DifferenceCheck.place(pPass, pDifference, pContext, false);
		case ADD:
			if (pPass == 1)
			{
				if (pContext.existsInPack(pDifference.where()))
				{
					return DifferenceCheck.place(pPass, pDifference.withNature(Nature.CHANGE), pContext, false);
				}
				return DifferenceCheck.place(pPass, pDifference, pContext, true);
			}
			if (pPass == 4)
			{
				// a role or a perspective section: no path to resolve
				return new Verdict(Kind.OK, pDifference.withPlaceState(PlaceState.PLACED), null, null, null);
			}
			return DifferenceCheck.place(pPass, pDifference, pContext, false);
		default:
			return DifferenceCheck.place(pPass, pDifference, pContext, false);
		}
	}

	private static Verdict refuse(final Difference pDifference, final String pKey, final String pReason, final String pArg)
	{
		return new Verdict(Kind.REFUSED, pDifference, pKey, pReason, pArg);
	}

	/** OK when {@code where} resolves (or, for a new path, its parent does), TO_PLACE otherwise. */
	private static Verdict place(final int pPass, final Difference pDifference, final CheckContext pContext, final boolean pNewPathAllowed)
	{
		final String where = pDifference.where();
		final boolean path = ModelPaths.PATH.matcher(where).matches();
		boolean resolved = false;
		if (path && !pNewPathAllowed)
		{
			resolved = pContext.resolves(where);
		}
		else if (path)
		{
			// a new table, or a new field/group whose parent (or, for a new group, whose grandparent table) is known; the line never vouches for itself
			final String parent = where.substring(0, where.lastIndexOf('/'));
			final int cut = parent.lastIndexOf('/');
			resolved = "/root".equals(parent) || pContext.resolves(parent) || cut > 0 && pContext.resolves(parent.substring(0, cut));
		}
		if (resolved)
		{
			return new Verdict(Kind.OK, pDifference.withPlaceState(PlaceState.PLACED), null, null, null);
		}
		return new Verdict(Kind.TO_PLACE, pDifference.withPlaceState(PlaceState.TO_PLACE), "accel.pass.refusal.toPlace",
			"'" + where + "' is not a path of the model: write an absolute path such as /root/Table/field", where);
	}

	// ------------------------------------------------------------------------------------------------ "looks like something that exists"

	/** The block or library an ADD resembles. */
	public static final class Existing
	{
		public final String kind;
		public final String key;

		Existing(final String pKind, final String pKey)
		{
			this.kind = pKind;
			this.key = pKey;
		}

		@Override
		public String toString()
		{
			return this.kind + " " + this.key;
		}
	}

	/**
	 * For an ADD that resembles a block (its last path segment is one of a block's field names, or its text names a block) or a library
	 * (its text names what a library covers): what it resembles. Null otherwise. The check points it out, it never refuses (section 5).
	 */
	public static Existing looksLikeExisting(final Difference pDifference, final PackCatalog pCatalogue)
	{
		if (pDifference.nature() != Nature.ADD)
		{
			return null;
		}
		final String where = pDifference.where();
		final String last = where.substring(where.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
		final String text = (pDifference.what() + " " + pDifference.why() + " " + (pDifference.detail() == null ? "" : pDifference.detail().toString()) + " "
			+ last).toLowerCase(Locale.ROOT);
		for (final Block b : pCatalogue.blocks())
		{
			for (final Block.Field f : b.fields())
			{
				if (!last.isEmpty() && f.name().equalsIgnoreCase(last))
				{
					return new Existing("block", b.key());
				}
			}
			if (containsWord(text, b.key()))
			{
				return new Existing("block", b.key());
			}
		}
		for (final LibraryCatalog.Library lib : pCatalogue.libraries().all())
		{
			final String name = lib.name().substring(lib.name().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
			if (name.length() >= 3 && containsWord(text, name) && !"date".equals(name))
			{
				return new Existing("library", lib.name());
			}
		}
		return null;
	}

	private static boolean containsWord(final String pText, final String pWord)
	{
		final String w = pWord.toLowerCase(Locale.ROOT);
		int from = 0;
		while (true)
		{
			final int at = pText.indexOf(w, from);
			if (at < 0)
			{
				return false;
			}
			final boolean before = at == 0 || !Character.isLetterOrDigit(pText.charAt(at - 1));
			final int end = at + w.length();
			final boolean after = end >= pText.length() || !Character.isLetterOrDigit(pText.charAt(end));
			if (before && after)
			{
				return true;
			}
			from = at + 1;
		}
	}

	/** Detail helper for callers: the JSON text of a detail, or empty. */
	static String text(final JsonNode pNode)
	{
		return pNode == null ? "" : pNode.toString();
	}
}
