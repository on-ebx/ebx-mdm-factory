package com.on.ebx.factory.stagingdesk;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.capability.ConstraintBodyBuilder;
import com.on.ebx.factory.capability.MissingInputs;
import com.on.ebx.factory.ebx.ui.FactoryHtml;
import com.on.ebx.factory.mappingmatrix.MatrixMessages;

/**
 * Validation desk lot L5 (docs/STAGING-DESK.md): the cards above the list of the "Cannot run as proposed" group - one per input that no field holds ("4 waiting for: the
 * complete address as one value"), with the names of the suggestions that wait, a button that asks the model for a computed field ("Propose a field for it", a background
 * job) and, once one was proposed, the field itself with Accept / Reject. Nothing is computed here: the cards come from {@link DeskInput#cards}, the field from the
 * rows the reader found. Called once from {@code DeskHtml#renderCannotRunCentre}; every button posts an action the desk already knows ({@code ACCEPT_ATTRIBUTE} /
 * {@code REJECT_ATTRIBUTE}, handled by the matrix handler) or the desk's own {@code PROPOSE_INPUT_FIELD}.
 */
final class DeskInputHtml
{
	private DeskInputHtml()
	{
	}

	/** Writes the cards for the waiting suggestions {@code pWaitingIds}; nothing when the desk has no input data or no suggestion waits for an input. */
	static void render(final StringBuilder pOut, final DeskInput pInput, final Set<String> pWaitingIds, final boolean pReadOnly, final Locale pLocale)
	{
		if (pInput == null)
		{
			return;
		}
		final List<DeskInput.Card> cards = pInput.cards(pWaitingIds);
		for (final DeskInput.Card card : cards)
		{
			DeskInputHtml.renderCard(pOut, pInput, card, pReadOnly, pLocale);
		}
	}

	private static void renderCard(final StringBuilder pOut, final DeskInput pInput, final DeskInput.Card pCard, final boolean pReadOnly, final Locale pLocale)
	{
		final String disabled = pReadOnly ? " disabled=\"disabled\"" : "";
		final int waiting = pCard.suggestions();
		pOut.append("<section class=\"mdskCard\" data-input=\"").append(DeskInputHtml.esc(pCard.key)).append("\"><span class=\"mdskEyebrow\">")
			.append(DeskInputHtml.esc(DeskMessages.get(pLocale, "desk.l5.eyebrow"))).append("</span><h2 style=\"margin:4px 0 6px;font-size:16px\">")
			.append(DeskInputHtml.esc(pCard.mixed() ? DeskMessages.plural(pLocale, waiting, "desk.l5.titleMixed.one", "desk.l5.titleMixed.other")
				: DeskMessages.plural(pLocale, waiting, "desk.l5.title.one", "desk.l5.title.other", pCard.sentence))).append("</h2>");
		if (pCard.mixed())
		{
			// the model's own words for each suggestion: the sentences differ, the card is the business object's
			pOut.append("<ul class=\"mdskList\">");
			for (final MissingInputs.Need need : pCard.needs)
			{
				pOut.append("<li><b>").append(DeskInputHtml.esc(need.label)).append("</b>").append(need.sentence.isEmpty() ? "" : " - " + DeskInputHtml.esc(need.sentence))
					.append("</li>");
			}
			pOut.append("</ul>");
		}
		else
		{
			pOut.append("<div class=\"mdskChips\">");
			for (final String label : pCard.labels())
			{
				pOut.append("<span class=\"mdskChip mut\">").append(DeskInputHtml.esc(label)).append("</span>");
			}
			pOut.append("</div>");
		}
		boolean proposed = false;
		for (final DeskInput.Field field : pCard.fields)
		{
			proposed = true;
			DeskInputHtml.renderField(pOut, field, pCard, disabled, pLocale);
		}
		if (!proposed)
		{
			pOut.append("<p class=\"mdskLead\">").append(DeskInputHtml.esc(DeskMessages.get(pLocale, "desk.l5.noField"))).append("</p><div style=\"margin-top:10px\">");
			if (pInput.jobRunning)
			{
				pOut.append("<span class=\"mdskChip acc\">").append(DeskInputHtml.esc(DeskMessages.get(pLocale, "desk.l5.running"))).append("</span>");
			}
			else
			{
				pOut.append("<button type=\"button\" class=\"mdskBtn mdskBtnPrimary mdskBtnSm\"").append(disabled).append(" onclick=\"mdskDo('").append(DeskInput.ACTION_PROPOSE)
					.append("','','',false)\">").append(DeskInputHtml.esc(DeskMessages.get(pLocale, "desk.l5.propose"))).append("</button> <span class=\"mdskDim mdskSmall\">")
					.append(DeskInputHtml.esc(DeskMessages.get(pLocale, "desk.l5.proposeHint"))).append("</span>");
			}
			pOut.append("</div>");
		}
		pOut.append("</section>");
	}

	private static void renderField(final StringBuilder pOut, final DeskInput.Field pField, final DeskInput.Card pCard, final String pDisabled, final Locale pLocale)
	{
		pOut.append("<div style=\"margin-top:12px;border:1px solid #e4e7ec;border-radius:10px;padding:12px 14px;background:#fafbfc\"><div><b><code>")
			.append(DeskInputHtml.esc(pField.entityName.isEmpty() ? pField.name : pField.entityName + "." + pField.name)).append("</code></b> <span class=\"mdskChip acc\">")
			.append(DeskInputHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.kind.COMPUTED"))).append("</span> <span class=\"mdskDim\"><code>")
			.append(DeskInputHtml.esc(pField.dataType)).append("</code></span>");
		if (pField.confidence != null)
		{
			pOut.append(" <span class=\"mdskDim mdskSmall\">").append(pField.confidence.intValue()).append(" %</span>");
		}
		pOut.append("</div>");
		// what it computes: the call, then the arguments, in the matrix' own words
		final String call = DeskInputHtml.callText(pField);
		if (!call.isEmpty())
		{
			pOut.append("<div class=\"mdskSmall\" style=\"margin-top:6px\"><code>").append(DeskInputHtml.esc(call)).append("</code></div>");
		}
		final String description = DeskInputHtml.functionDescription(pField, pLocale);
		if (!description.isEmpty())
		{
			pOut.append("<div class=\"mdskDim mdskSmall\" style=\"margin-top:4px\">").append(DeskInputHtml.esc(description)).append("</div>");
		}
		pOut.append("<ul class=\"mdskList\">");
		for (final Map.Entry<String, ConstraintBodyBuilder.BindingEntry> entry : ConstraintBodyBuilder.parseBinding(pField.binding).entrySet())
		{
			pOut.append("<li>").append(DeskInputHtml.esc(DeskInputHtml.argLine(entry.getValue(), pLocale))).append("</li>");
		}
		pOut.append("</ul>");
		if (!pField.rationale.isEmpty())
		{
			pOut.append("<div class=\"mdskDim mdskSmall\">").append(DeskInputHtml.esc(pField.rationale)).append("</div>");
		}
		if (pField.decidable())
		{
			final int binds = pField.suppliedAmong(pCard.needs);
			pOut.append("<div style=\"margin-top:10px;display:flex;gap:8px;align-items:center;flex-wrap:wrap\"><button type=\"button\" class=\"mdskBtn mdskBtnPrimary mdskBtnSm\"")
				.append(pDisabled).append(" onclick=\"mdskDo('ACCEPT_ATTRIBUTE',").append(DeskInputHtml.jsAttr(pField.attributeId)).append(",'',false)\">")
				.append(DeskInputHtml.esc(DeskMessages.get(pLocale, "desk.l5.accept"))).append("</button><button type=\"button\" class=\"mdskBtn mdskBtnSm\"").append(pDisabled)
				.append(" onclick=\"mdskDo('REJECT_ATTRIBUTE',").append(DeskInputHtml.jsAttr(pField.attributeId)).append(",'',false)\">")
				.append(DeskInputHtml.esc(DeskMessages.get(pLocale, "desk.btn.reject"))).append("</button><span class=\"mdskDim mdskSmall\">")
				.append(DeskInputHtml.esc(DeskMessages.plural(pLocale, binds, "desk.l5.binds.one", "desk.l5.binds.other"))).append("</span></div>");
		}
		else
		{
			pOut.append("<div class=\"mdskDim mdskSmall\" style=\"margin-top:8px\">").append(DeskInputHtml.esc(DeskMessages.get(pLocale, "desk.l5.decided"))).append("</div>");
		}
		pOut.append("</div>");
	}

	/** {@code buildFullAddress(number, street, zipcode, city)}: the function, its arguments by the field that feeds them or {@code parameter = "constant"} / {@code parameter = ?}. */
	static String callText(final DeskInput.Field pField)
	{
		if (pField.function.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder(pField.function).append('(');
		boolean first = true;
		for (final ConstraintBodyBuilder.BindingEntry entry : ConstraintBodyBuilder.parseBinding(pField.binding).values())
		{
			out.append(first ? "" : ", ");
			first = false;
			out.append(entry.kind == ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE ? entry.value
				: entry.kind == ConstraintBodyBuilder.BindingEntry.Kind.LITERAL ? entry.parameter + " = \"" + entry.value + "\"" : entry.parameter + " = ?");
		}
		return out.append(')').toString();
	}

	/** One argument in the matrix' words ({@code mappingMatrix.computed.arg.*}). */
	static String argLine(final ConstraintBodyBuilder.BindingEntry pEntry, final Locale pLocale)
	{
		if (pEntry.kind == ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE)
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.computed.arg.field", pEntry.parameter, pEntry.value);
		}
		if (pEntry.kind == ConstraintBodyBuilder.BindingEntry.Kind.LITERAL)
		{
			return pEntry.value.isEmpty() ? MatrixMessages.get(pLocale, "mappingMatrix.computed.arg.blank", pEntry.parameter)
				: MatrixMessages.get(pLocale, "mappingMatrix.computed.arg.literal", pEntry.parameter, "\"" + pEntry.value + "\"");
		}
		return MatrixMessages.get(pLocale, "mappingMatrix.computed.arg.toBeProvided", pEntry.parameter);
	}

	/** The catalogue's text of the function in the viewer's language ({@code capability.fn.<library>.<function>.description}), "" when the bundle does not know it. */
	static String functionDescription(final DeskInput.Field pField, final Locale pLocale)
	{
		String library = pField.library.trim();
		if (library.startsWith("library/"))
		{
			library = library.substring("library/".length());
		}
		library = library.replace('/', '.');
		final String text = MatrixMessages.find(pLocale, "capability.fn." + library + "." + pField.function + ".description");
		return text == null ? "" : text.trim();
	}

	/** Same escaping as {@code DeskHtml#esc} (private there, and the shared file is kept untouched). */
	private static String esc(final String pText)
	{
		if (pText == null)
		{
			return "";
		}
		return pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}

	/** Same as {@code DeskHtml#jsAttr}. */
	private static String jsAttr(final String pValue)
	{
		return ("'" + FactoryHtml.jsStringBody(pValue) + "'").replace("&", "&amp;").replace("\"", "&quot;");
	}
}
