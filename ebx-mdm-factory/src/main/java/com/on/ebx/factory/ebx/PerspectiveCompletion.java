package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure decision behind {@link PerspectiveInstaller}'s completion pass: given what a perspective
 * currently holds and what the packaged definition says it should hold, which sections and items are
 * missing and must be added — no EBX type anywhere, so {@code PerspectiveCompletionTest} exercises it
 * directly.
 * <p>
 * <b>Why matching is by (section label, item target) and not a stored key.</b> A {@code menuItem}
 * row's only identity is EBX's own {@code id}, a PK auto-assigned globally across every perspective on the
 * instance (confirmed live by ebx-connectors' installer and ebx-perspective-admin's {@code PerspectiveService}
 * — every field it knows how to write is enumerated there: type, parent, order, hasTopSeparator, icon,
 * action.serviceKey, action.serviceParameters, label.localizedDocumentations; nothing free-text a caller
 * could stash an external key in). {@code ebx-manager}'s schema is Java-built, not XSD, so there is no schema
 * file to add such a field to either, even for this module's own perspective. The packaged JSON's own item
 * order (array position) is not stable across edits (inserting an item in the middle of the JSON shifts
 * every later one), so it cannot serve as an index-based key. What two installs of the {@code same} logical
 * item DO agree on, short of a real key, is: which section it lives under (its own EN label, since sections
 * are never renamed by this module across versions) and where it points — its {@code serviceKey}, or, for
 * a plain table link, its xpath (see {@link Item#target}). An item's own label is NOT part of the match: a
 * label is prose (translated, restyled, occasionally hand-edited once live) and two installs pointing at the
 * exact same place are the same menu entry whatever they are currently called — matching on label as well
 * would treat a harmless relabel as "missing" and re-add a duplicate under the old name (found live
 * 2026-09-21: a completion run crashed before writing anything, so this was never actually exercised
 * against a real perspective, but a dry run practice is to log every intended addition before writing, see
 * {@code PerspectiveInstaller#complete} — exactly so a wrong match like this shows up in the log instead of
 * as a silent duplicate in the menu).
 * <p>
 * <b>Known limitation, accepted rather than hidden</b>: if a person repoints a packaged item at a different
 * table or service entirely (not just a relabel), a later completion pass no longer recognizes it as the
 * same item (it matches by target) and will add the packaged one back pointing at the original place — a
 * duplicate, not data loss (the repointed item is never touched, per the "never touching an item that
 * exists" rule), but a duplicate nonetheless.
 */
final class PerspectiveCompletion
{
	private PerspectiveCompletion()
	{
	}

	/** One leaf ("action") item, identified for matching by where it points ({@link #target}), never by its label. */
	static final class Item<P>
	{
		final String labelEn;
		/** {@code serviceKey} for a generic action, or the bare xpath for a table-shorthand one (see the class Javadoc: normalized, dataspace/dataset are not part of the key). */
		final String target;
		/** The caller's own representation of the item's full content (icon, params, ...), typed rather than {@code Object} so a caller cannot accidentally read the wrong side's payload as the wrong type. */
		final P payload;

		Item(final String pLabelEn, final String pTarget, final P pPayload)
		{
			this.labelEn = pLabelEn;
			this.target = pTarget;
			this.payload = pPayload;
		}

		/** Matches on {@link #target} alone: two items pointing at the same place are the same menu entry, whatever either is currently labelled. */
		boolean samePlaceAs(final Item<?> pOther)
		{
			return this.target != null && this.target.equals(pOther.target);
		}
	}

	/** One top-level ("section") node and its direct children. */
	static final class Section<P>
	{
		final String labelEn;
		final List<Item<P>> items;
		/** The caller's own representation of the section's full content (icon, hasTopSeparator, ...) — read only when the section turns out to be entirely missing and must be created from it. */
		final P payload;

		Section(final String pLabelEn, final List<Item<P>> pItems, final P pPayload)
		{
			this.labelEn = pLabelEn;
			this.items = pItems;
			this.payload = pPayload;
		}
	}

	/**
	 * What to add for one packaged section: either the whole section (it does not exist yet) or a tail of
	 * missing items under an existing one. {@link #existingSectionId} and {@link #packagedSection} are two
	 * distinct, separately-typed fields on purpose — the live incident this class was rewritten to prevent
	 * ({@code ClassCastException: ObjectNode cannot be cast to String}) was exactly a single {@code payload}
	 * field doing double duty as both "the packaged content to create from" and "the existing row's id",
	 * with nothing at compile time to catch reading the wrong one.
	 */
	static final class Addition<P>
	{
		/** The packaged section: always what {@link #missingItems} (or, for a new section, every one of its items) are created from. */
		final Section<P> packagedSection;
		/** True: create {@link #packagedSection} in full, as a new top-level node. False: append {@link #missingItems} to the existing section identified by {@link #existingSectionId}. */
		final boolean newSection;
		/** The EXISTING section's own row id (the {@code menuItem} table's PK, as a string): non-null exactly when {@link #newSection} is false, so its children can be created under the real parent. */
		final String existingSectionId;
		final List<Item<P>> missingItems;

		Addition(final Section<P> pPackagedSection, final boolean pNewSection, final String pExistingSectionId, final List<Item<P>> pMissingItems)
		{
			this.packagedSection = pPackagedSection;
			this.newSection = pNewSection;
			this.existingSectionId = pExistingSectionId;
			this.missingItems = pMissingItems;
		}
	}

	/**
	 * @return one {@link Addition} per packaged section that has anything missing, in the packaged JSON's own
	 * order; a section already fully present (every one of its items matched) contributes nothing. Never
	 * reorders, renames or removes anything from {@code pExisting} — it is read, never written back here.
	 * {@code pExisting}'s payload type is always {@code String} (the row id: see {@link Addition#existingSectionId}),
	 * independent of {@code P}, the packaged side's own payload type — the two never need to be the same type,
	 * and {@code decide} never has to convert between them.
	 */
	static <P> List<Addition<P>> decide(final List<Section<String>> pExisting, final List<Section<P>> pPackaged)
	{
		final List<Addition<P>> additions = new ArrayList<Addition<P>>();
		for (final Section<P> packagedSection : pPackaged)
		{
			final Section<String> existingSection = PerspectiveCompletion.findSection(pExisting, packagedSection.labelEn);
			if (existingSection == null)
			{
				additions.add(new Addition<P>(packagedSection, true, null, new ArrayList<Item<P>>(packagedSection.items)));
				continue;
			}
			final List<Item<P>> missing = new ArrayList<Item<P>>();
			for (final Item<P> packagedItem : packagedSection.items)
			{
				if (PerspectiveCompletion.findItem(existingSection.items, packagedItem) == null)
				{
					missing.add(packagedItem);
				}
			}
			if (!missing.isEmpty())
			{
				additions.add(new Addition<P>(packagedSection, false, existingSection.payload, missing));
			}
		}
		return additions;
	}

	private static Section<String> findSection(final List<Section<String>> pSections, final String pLabelEn)
	{
		for (final Section<String> section : pSections)
		{
			if (section.labelEn != null && section.labelEn.equals(pLabelEn))
			{
				return section;
			}
		}
		return null;
	}

	private static Item<String> findItem(final List<Item<String>> pItems, final Item<?> pWanted)
	{
		for (final Item<String> item : pItems)
		{
			if (item.samePlaceAs(pWanted))
			{
				return item;
			}
		}
		return null;
	}
}
