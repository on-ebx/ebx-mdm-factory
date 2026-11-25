package com.on.ebx.factory.stagingdesk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.staging.AttentionRules;
import com.on.ebx.factory.staging.ModelAnswers;

/**
 * Validation desk lot L3 (docs/STAGING-DESK.md): the "points to check" callouts of the centre of a business object - for each attribute whose every source writes the SAME
 * constant ({@link AttentionRules#SAME_CONSTANT}, measured by Java) one yellow callout: what is measured (source, constant, the rationale sentence of the mapping), then the
 * decision. The correction itself is never Java's: it is the language model's alternative mapping ({@code AttributeMapping.alternativeTo}, written by {@code
 * ebx.AnomalyLlmSuggester}); the buttons only post the matrix' own decisions - {@code ACCEPT_ALTERNATIVE} ("Use RESIDENTIAL for SAP"), {@code ACCEPT_MAPPING} ("Keep MAILING") -
 * and {@link #ASK} (runs the anomaly pass for this object as a background job).
 * <p>
 * {@link #collect} is pure (a matrix model in, plain rows out) and tested; {@link #render} is plain HTML string building like the rest of the desk.
 */
public final class DeskPointsHtml
{
	/** The desk-only action that asks the language model for a correction of this object's flagged constants (a background job). */
	public static final String ASK = "ASK_CORRECTION";

	private DeskPointsHtml()
	{
	}

	/** The notice after "Ask for a correction" was queued. */
	public static String askedNotice(final Locale pLocale, final String pObjectName)
	{
		return DeskMessages.get(pLocale, "desk.l3.asked", pObjectName);
	}

	/** The notice when the correction could not be asked. */
	public static String askRefusedNotice(final Locale pLocale, final String pReason)
	{
		return DeskMessages.get(pLocale, "desk.l3.askRefused", pReason);
	}

	/** One source of a flagged attribute: the mapping that writes the constant and, when the model proposed one, its alternative. */
	public static final class Source
	{
		public final String sourceName;
		public final String feedId;
		public final String mappingId;
		public final String value;
		/** The rationale sentence of the mapping, "" when it has none. */
		public final String rationale;
		/** The Feed designer of the feed's root, "" when none can be found. */
		public final String designerUrl;
		/** The model's alternative: its mapping id ("" = none yet), the constant it writes and why. */
		public final String altMappingId;
		public final String altValue;
		public final String altRationale;
		/** Lot L3b: the model was asked about this source and kept its constant (it proposed nothing); its note, "" when none. */
		public final boolean modelKept;
		public final String modelNote;
		/** Lot L3b: the model's recorded answer for this mapping (kept or proposed), null when it was never submitted. */
		final ModelAnswers.Answer answer;

		Source(final String pSourceName, final String pFeedId, final String pMappingId, final String pValue, final String pRationale, final String pDesignerUrl,
			final String pAltMappingId, final String pAltValue, final String pAltRationale)
		{
			this(pSourceName, pFeedId, pMappingId, pValue, pRationale, pDesignerUrl, pAltMappingId, pAltValue, pAltRationale, null);
		}

		Source(final String pSourceName, final String pFeedId, final String pMappingId, final String pValue, final String pRationale, final String pDesignerUrl,
			final String pAltMappingId, final String pAltValue, final String pAltRationale, final ModelAnswers.Answer pAnswer)
		{
			this.answer = pAnswer;
			this.modelKept = pAnswer != null && pAnswer.kept && (pAltMappingId == null || pAltMappingId.isEmpty());
			this.modelNote = pAnswer == null ? "" : pAnswer.note;
			this.sourceName = pSourceName;
			this.feedId = pFeedId;
			this.mappingId = pMappingId;
			this.value = pValue;
			this.rationale = pRationale == null ? "" : pRationale;
			this.designerUrl = pDesignerUrl == null ? "" : pDesignerUrl;
			this.altMappingId = pAltMappingId == null ? "" : pAltMappingId;
			this.altValue = pAltValue == null ? "" : pAltValue;
			this.altRationale = pAltRationale == null ? "" : pAltRationale;
		}

		public boolean hasAlternative()
		{
			return !this.altMappingId.isEmpty();
		}
	}

	/** One flagged attribute and the sources still to decide. */
	public static final class Point
	{
		public final String attributeId;
		public final String attributeName;
		public final String value;
		public final List<Source> sources = new ArrayList<Source>();

		Point(final String pAttributeId, final String pAttributeName, final String pValue)
		{
			this.attributeId = pAttributeId;
			this.attributeName = pAttributeName;
			this.value = pValue;
		}

		/**
		 * True when at least one source has no alternative AND was never submitted to the model (or was changed since): the callout then offers to ask. A source the model was asked
		 * about and kept needs nothing more ({@link ModelAnswers#needsAsk}).
		 */
		public boolean needsAsk()
		{
			final List<String> open = new ArrayList<String>();
			for (final Source source : this.sources)
			{
				if (!source.hasAlternative())
				{
					open.add(source.mappingId);
				}
			}
			final java.util.Map<String, ModelAnswers.Answer> answered = new java.util.HashMap<String, ModelAnswers.Answer>();
			for (final Source source : this.sources)
			{
				if (source.answer != null)
				{
					answered.put(source.mappingId, source.answer);
				}
			}
			return ModelAnswers.needsAsk(open, answered);
		}
	}

	/**
	 * The callouts of one business object: its attributes whose cells carry {@link AttentionRules#SAME_CONSTANT} and are still {@code PROPOSED} (a decided link no longer
	 * waits). Pure.
	 */
	public static List<Point> collect(final MatrixModel.Project pProject, final MatrixModel.Entity pEntity)
	{
		return DeskPointsHtml.collect(pProject, pEntity, null);
	}

	/** Same, with what the model answered the last time it was asked about this object (by mapping id; null = never asked, see {@link ModelAnswers}). */
	public static List<Point> collect(final MatrixModel.Project pProject, final MatrixModel.Entity pEntity, final java.util.Map<String, ModelAnswers.Answer> pAnswers)
	{
		final List<Point> out = new ArrayList<Point>();
		if (pProject == null || pEntity == null)
		{
			return out;
		}
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			Point point = null;
			for (final MatrixModel.Feed feed : pEntity.feeds)
			{
				final MatrixModel.Cell cell = feed.cellsByAttributeId.get(attr.id);
				if (cell == null || cell.mappingId == null || !AttentionRules.SAME_CONSTANT.equals(cell.attentionFlag) || !MatrixModel.PROPOSED.equals(cell.status))
				{
					continue;
				}
				final String value = AttentionRules.constantValueOf(cell.pipeline);
				if (value == null)
				{
					continue;
				}
				if (point == null)
				{
					point = new Point(attr.id, attr.name, value);
					out.add(point);
				}
				String altId = "";
				String altValue = "";
				String altRationale = "";
				if (cell.alternative != null)
				{
					final String altConstant = AttentionRules.constantValueOf(String.join("|", cell.alternative.pipelineSteps));
					altId = cell.alternative.mappingId;
					altValue = altConstant == null ? "" : altConstant;
					altRationale = cell.alternative.rationale;
				}
				point.sources.add(new Source(DeskPointsHtml.sourceName(pProject, feed), feed.id, cell.mappingId, value, cell.rationale, DeskPointsHtml.designerUrl(pProject, feed), altId, altValue,
					altRationale, pAnswers == null ? null : pAnswers.get(cell.mappingId)));
			}
		}
		return out;
	}

	private static String sourceName(final MatrixModel.Project pProject, final MatrixModel.Feed pFeed)
	{
		return DeskSourceNames.name(pProject, pFeed);
	}

	/** The Feed designer opens from a ROOT feed: a child feed climbs to its parent until one carries a link. */
	static String designerUrl(final MatrixModel.Project pProject, final MatrixModel.Feed pFeed)
	{
		MatrixModel.Feed feed = pFeed;
		for (int guard = 0; feed != null && guard < 10; guard++)
		{
			if (!feed.designerUrl.isEmpty())
			{
				return feed.designerUrl;
			}
			if (feed.parentFeedId == null)
			{
				return "";
			}
			MatrixModel.Feed parent = null;
			for (final MatrixModel.Entity entity : pProject.entities)
			{
				for (final MatrixModel.Feed candidate : entity.feeds)
				{
					if (candidate.id.equals(feed.parentFeedId))
					{
						parent = candidate;
					}
				}
			}
			feed = parent;
		}
		return "";
	}

	/** The callouts, above the preview. Nothing is written when there is no point. */
	public static void render(final StringBuilder pOut, final List<Point> pPoints, final String pEntityId, final DeskHtml.State pState, final Locale pLocale)
	{
		if (pPoints == null || pPoints.isEmpty())
		{
			return;
		}
		final String disabled = pState.readOnly ? " disabled=\"disabled\"" : "";
		pOut.append("<style>#mdsk .mdskPoint{background:#fffaeb;border:1px solid #fedf89;border-radius:14px;padding:14px 18px;margin-bottom:14px}")
			.append("#mdsk .mdskPoint h2{margin:4px 0 2px;font-size:15px}#mdsk .mdskPointSrc{padding:10px 0;border-top:1px solid #fedf89}#mdsk .mdskPointSrc:first-of-type{border-top:0}")
			.append("#mdsk .mdskPointQuote{margin:4px 0 8px;color:#475467;font-size:12.5px;font-style:italic}#mdsk .mdskPointBtns{display:flex;gap:8px;flex-wrap:wrap;align-items:center}</style>");
		for (final Point point : pPoints)
		{
			pOut.append("<section class=\"mdskPoint\" data-attr=\"").append(DeskHtml.esc(point.attributeId)).append("\"><span class=\"mdskChip warn\">")
				.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.l3.chip"))).append("</span><h2>")
				.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.l3.title", point.attributeName, point.value))).append("</h2><p class=\"mdskLead\">")
				.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.l3.lead", point.attributeName, point.value))).append("</p>");
			for (final Source source : point.sources)
			{
				pOut.append("<div class=\"mdskPointSrc\"><strong>").append(DeskHtml.esc(source.sourceName)).append("</strong> <span class=\"mdskDim\">")
					.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.l3.writes", point.attributeName, source.value))).append("</span>");
				if (!source.rationale.isEmpty())
				{
					pOut.append("<p class=\"mdskPointQuote\">“").append(DeskHtml.esc(source.rationale)).append("”</p>");
				}
				if (source.hasAlternative())
				{
					pOut.append("<p class=\"mdskPointQuote\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.l3.proposed", source.altValue.isEmpty() ? "?" : source.altValue)));
					if (!source.altRationale.isEmpty())
					{
						pOut.append(" “").append(DeskHtml.esc(source.altRationale)).append("”");
					}
					pOut.append("</p>");
				}
				if (source.modelKept)
				{
					// lot L3b: the model was asked and judged this constant right - said, so nobody asks again for nothing
					pOut.append("<p class=\"mdskPointQuote\"><strong>").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.l3b.keeps", source.value, source.sourceName)))
						.append("</strong>");
					if (!source.modelNote.isEmpty())
					{
						pOut.append(" “").append(DeskHtml.esc(source.modelNote)).append("”");
					}
					pOut.append("</p>");
				}
				pOut.append("<div class=\"mdskPointBtns\">");
				if (source.hasAlternative())
				{
					pOut.append("<button type=\"button\" class=\"mdskBtn mdskBtnPrimary mdskBtnSm\"").append(disabled).append(" onclick=\"mdskDo('ACCEPT_ALTERNATIVE',")
						.append(DeskHtml.jsAttr(source.altMappingId)).append(",'',false)\">")
						.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.l3.use", source.altValue.isEmpty() ? "?" : source.altValue, source.sourceName))).append("</button>");
				}
				pOut.append("<button type=\"button\" class=\"mdskBtn mdskBtnSm\"").append(disabled).append(" onclick=\"mdskDo('ACCEPT_MAPPING',").append(DeskHtml.jsAttr(source.mappingId))
					.append(",'',false)\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.l3.keep", source.value, source.sourceName))).append("</button>");
				if (!source.designerUrl.isEmpty())
				{
					pOut.append("<a class=\"mdskBtn mdskBtnQuiet mdskBtnSm mdmf-matrix-designer-link\" target=\"_top\" href=\"").append(DeskHtml.esc(source.designerUrl)).append("\">")
						.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.l3.other"))).append("</a>");
				}
				pOut.append("</div></div>");
			}
			if (point.needsAsk())
			{
				pOut.append("<div class=\"mdskPointBtns\"><button type=\"button\" class=\"mdskBtn mdskBtnPrimary mdskBtnSm\"").append(disabled).append(" onclick=\"mdskDo('")
					.append(DeskPointsHtml.ASK).append("',").append(DeskHtml.jsAttr(pEntityId)).append(",'',false)\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.l3.ask")))
					.append("</button><span class=\"mdskDim mdskSmall\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.l3.askHint"))).append("</span></div>");
			}
			pOut.append("</section>");
		}
	}
}
