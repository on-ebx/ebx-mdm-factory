package com.on.ebx.factory.mappingmatrix;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.staging.CheckNote;
import com.on.ebx.factory.staging.ProposalChecks;
import com.on.ebx.factory.wizard.RationaleText;

/**
 * Lot G2 (2026-10-17, docs/UX-STAGING-TEST-FIXES-G2.md): the REASON a proposal was held back, in the words the proposer wrote it, for the cards of the
 * Mapping matrix. Measured on the live test (project TiersB2CRetestFinal): a field capped at 20 % by lot A's {@code staging.ProposalChecks} read only
 * "confidence 20 %, under the 60 % bar" on its card, while its own rationale started with the real reason ("To check: ... this field is a duplicate on
 * Customer (suggestion: reject it)"). Two kinds of note, both read from stored text, neither invented here:
 * <ul>
 * <li>the <b>leading check sentence</b> of a {@code StagingAttribute.rationale} ({@code ProposalChecks} writes it first, in the language of the run:
 * recognised from the bundle's own sentences of both languages, never from a name);</li>
 * <li>the <b>"Value check" note</b> that lot D/E ({@code staging.CheckNote}) appends to a mapping's rationale.</li>
 * </ul>
 * Pure: no EBX type.
 */
final class MatrixNotes
{
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+\\}");
	private static final Pattern WAS = Pattern.compile("^\\s*\\[was \\d{1,3}\\]");
	private static final String[] CHECK_KEYS = { ProposalChecks.KEY_DUPLICATES_RELATED, ProposalChecks.KEY_NO_SOURCE };
	private static final Locale[] LOCALES = { Locale.ENGLISH, Locale.FRENCH };

	private MatrixNotes()
	{
	}

	/** A note to show: the sentence, and whether it proposes to reject (the card then makes Reject the primary action; the person still decides). */
	static final class Note
	{
		/** The sentence as stored, trimmed. */
		final String sentence;
		final boolean proposesReject;
		/** What follows the sentence in the rationale (the proposer's own text), trimmed; empty when there is nothing. */
		final String rest;

		Note(final String pSentence, final boolean pProposesReject, final String pRest)
		{
			this.sentence = pSentence;
			this.proposesReject = pProposesReject;
			this.rest = pRest == null ? "" : pRest;
		}
	}

	/**
	 * The check sentence a staging proposal check wrote at the START of an attribute rationale, or null. Recognised against the bundle sentences
	 * ({@code stagingProposal.check.duplicatesRelated} / {@code .noSource}) in English and French, apostrophes tolerated either way; the sentences
	 * of both findings propose rejecting the field.
	 */
	static Note leadingCheck(final String pRationale)
	{
		if (pRationale == null || pRationale.trim().isEmpty())
		{
			return null;
		}
		final String text = pRationale.trim();
		final String comparable = MatrixNotes.plainApostrophes(text);
		for (final String key : MatrixNotes.CHECK_KEYS)
		{
			for (final Locale locale : MatrixNotes.LOCALES)
			{
				final String pattern = MatrixMessages.get(locale, key);
				if (pattern == null || pattern.equals(key))
				{
					continue;
				}
				final Matcher matcher = MatrixNotes.regexOf(pattern).matcher(comparable);
				if (matcher.lookingAt())
				{
					return new Note(text.substring(0, matcher.end()).trim(), true, text.substring(matcher.end()).trim());
				}
			}
		}
		return null;
	}

	/**
	 * The "Value check" note lots D/E append to a mapping rationale ({@link CheckNote}), without its {@code [was N]} marker, or null. It never
	 * proposes a rejection: a failing link is flagged and left for the person, a fixed one is only informative.
	 */
	static Note valueCheck(final String pRationale)
	{
		return MatrixNotes.valueCheck(pRationale, Locale.ENGLISH);
	}

	/**
	 * Staging last points (2026-10-17): the same, with the note worded in {@code pLocale} (the passes store it coded: English plus a marker, see {@link
	 * com.on.ebx.factory.staging.NoteTexts}); the proposer's own text that precedes it comes back as {@link Note#rest} without any marker.
	 */
	static Note valueCheck(final String pRationale, final Locale pLocale)
	{
		if (pRationale == null || !CheckNote.has(pRationale))
		{
			return null;
		}
		final String tail = RationaleText.valueCheck(pRationale, pLocale);
		return tail.isEmpty() ? null : new Note(tail, false, RationaleText.one(CheckNote.base(pRationale), pLocale).trim());
	}

	/** The note an item of the "left to decide" list shows prominently: the leading check of a field, the field's check or the value check of a link. Null when there is none. */
	static Note of(final MatrixModel.Entity pEntity, final MatrixCompute.StillItem pItem)
	{
		if (pEntity == null || pItem == null)
		{
			return null;
		}
		if (ReviewLine.ATTRIBUTE.equals(pItem.nature))
		{
			for (final MatrixModel.Attr attr : pEntity.attrs)
			{
				if (attr.id.equals(pItem.attributeId))
				{
					return MatrixNotes.leadingCheck(attr.rationale);
				}
			}
			return null;
		}
		return null;
	}

	/** A field link's own value-check note, found by the mapping row id the item decides. */
	static Note valueCheckOfLink(final MatrixModel.Entity pEntity, final MatrixCompute.StillItem pItem)
	{
		return MatrixNotes.valueCheckOfLink(pEntity, pItem, Locale.ENGLISH);
	}

	/** {@link #valueCheckOfLink(MatrixModel.Entity, MatrixCompute.StillItem)} with the note worded in {@code pLocale}. */
	static Note valueCheckOfLink(final MatrixModel.Entity pEntity, final MatrixCompute.StillItem pItem, final Locale pLocale)
	{
		if (pEntity == null || pItem == null || !ReviewLine.MAPPING.equals(pItem.nature) || pItem.id.isEmpty())
		{
			return null;
		}
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			for (final MatrixModel.Cell cell : feed.cellsByAttributeId.values())
			{
				if (pItem.id.equals(cell.mappingId))
				{
					return MatrixNotes.valueCheck(cell.rationale, pLocale);
				}
			}
		}
		return null;
	}

	private static String plainApostrophes(final String pText)
	{
		return pText.replace('’', '\'').replace('‘', '\'');
	}

	/** The pattern of a bundle sentence as a regular expression: its literal parts quoted, each {@code {n}} a lazy wildcard. */
	private static Pattern regexOf(final String pBundlePattern)
	{
		final String literal = MatrixNotes.plainApostrophes(pBundlePattern.replace("''", "'"));
		final Matcher matcher = MatrixNotes.PLACEHOLDER.matcher(literal);
		final StringBuilder regex = new StringBuilder();
		int last = 0;
		while (matcher.find())
		{
			regex.append(Pattern.quote(literal.substring(last, matcher.start()))).append(".+?");
			last = matcher.end();
		}
		regex.append(Pattern.quote(literal.substring(last)));
		return Pattern.compile(regex.toString(), Pattern.DOTALL);
	}
}
