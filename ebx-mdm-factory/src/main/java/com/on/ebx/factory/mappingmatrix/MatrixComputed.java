package com.on.ebx.factory.mappingmatrix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.capability.ConstraintBodyBuilder;
import com.on.ebx.factory.capability.ConstraintBodyBuilder.BindingEntry;
import com.on.ebx.factory.capability.FunctionDoc;
import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.review.ReviewLine;

/**
 * Everything the Mapping matrix says about a COMPUTED field ({@code StagingAttribute.derivation = SCRIPT}, fed at
 * load by a published library function) - fix/matrix-computed-field, docs/MAPPING-MATRIX.md sec 14. Pure, no EBX
 * type, unit-tested ({@code MatrixComputedTest}). Every text it builds comes from stored data (the suggestion's
 * function, binding and rationale), the library's own doc comment ({@link FunctionDoc}) or a measurement of the
 * staging table ({@link MatrixModel.Measurement}); nothing here knows what a phone number is, and nothing is shown
 * that was not read.
 * <p>
 * Three consumers in {@link MatrixHtml}: the merged cell's second line ({@link #cellLine2}), the "Script &middot;
 * function" chip of the Rules and checks column ({@link #chipLabel}) and the inspector, filled client-side from the
 * JSON {@link #cellJson} appends to the cell's ordinary {@code data-cell} JSON (the chip carries the same JSON, so
 * selecting either opens the same inspector sections, in this order: what is computed, when it is empty, measured,
 * why it was proposed, decision).
 */
final class MatrixComputed
{
	private MatrixComputed()
	{
	}

	/** One argument of the call, resolved from the suggestion's {@code binding}: its parameter name and what feeds it. */
	static final class Arg
	{
		final String parameter;
		final BindingEntry.Kind kind;
		/** The field name, the constant, or the reason (never null). */
		final String value;

		Arg(final String pParameter, final BindingEntry.Kind pKind, final String pValue)
		{
			this.parameter = pParameter;
			this.kind = pKind;
			this.value = pValue;
		}
	}

	/** The call's arguments in binding order; empty (never null) when there is no computation or the binding is unreadable. */
	static List<Arg> args(final MatrixModel.Computation pComputation)
	{
		final List<Arg> out = new ArrayList<Arg>();
		if (pComputation == null)
		{
			return out;
		}
		for (final Map.Entry<String, BindingEntry> entry : ConstraintBodyBuilder.parseBinding(pComputation.binding).entrySet())
		{
			out.add(new Arg(entry.getKey(), entry.getValue().kind, entry.getValue().value));
		}
		return out;
	}

	/** {@code toE164(phone, country = "")}: a field-fed argument by its field name, a constant as {@code name = "value"}, a still-to-provide one as {@code name = ?}. Empty when there is no function. */
	static String callText(final MatrixModel.Computation pComputation)
	{
		if (pComputation == null || pComputation.function.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder(pComputation.function).append('(');
		boolean first = true;
		for (final Arg arg : MatrixComputed.args(pComputation))
		{
			out.append(first ? "" : ", ");
			first = false;
			if (arg.kind == BindingEntry.Kind.ATTRIBUTE)
			{
				out.append(arg.value);
			}
			else if (arg.kind == BindingEntry.Kind.LITERAL)
			{
				out.append(arg.parameter).append(" = \"").append(arg.value).append('"');
			}
			else
			{
				out.append(arg.parameter).append(" = ?");
			}
		}
		return out.append(')').toString();
	}

	private static final int RETURNS_SHORT_MAX = 60;

	/**
	 * The short "returns" text of the cell: the function's own {@code @return} text up to its first comma or
	 * semicolon (the rest is the "when it is empty" detail of the inspector), without its final dot, bounded. Empty
	 * when the library documents no {@code @return}.
	 */
	static String returnsShort(final MatrixModel.Computation pComputation)
	{
		return MatrixComputed.returnsShort(pComputation, Locale.ENGLISH);
	}

	/** {@link #returnsShort(MatrixModel.Computation)} in the viewer's language (lot G2: {@link MatrixFunctionTexts}). */
	static String returnsShort(final MatrixModel.Computation pComputation, final Locale pLocale)
	{
		String text = MatrixFunctionTexts.returns(pComputation, pLocale);
		if (text.isEmpty())
		{
			return "";
		}
		for (int i = 0; i < text.length(); i++)
		{
			final char c = text.charAt(i);
			if (c == ',' || c == ';')
			{
				text = text.substring(0, i);
				break;
			}
		}
		text = text.trim();
		if (text.endsWith("."))
		{
			text = text.substring(0, text.length() - 1);
		}
		return text.length() <= MatrixComputed.RETURNS_SHORT_MAX ? text : text.substring(0, MatrixComputed.RETURNS_SHORT_MAX) + "...";
	}

	/** True for the attributes this class describes: a {@code SCRIPT} derivation whose suggestion and function are known. */
	static boolean hasComputation(final MatrixModel.Attr pAttr)
	{
		return pAttr.computation != null && "SCRIPT".equals(pAttr.derivation) && !pAttr.computation.function.isEmpty();
	}

	/** The second line of the merged cell: {@code toE164(phone, country = "") -> the E.164 string}; without a known return text, the call alone. */
	static String cellLine2(final MatrixModel.Attr pAttr, final Locale pLocale)
	{
		final String call = MatrixComputed.callText(pAttr.computation);
		final String returns = MatrixComputed.returnsShort(pAttr.computation, pLocale);
		return returns.isEmpty() ? MatrixMessages.get(pLocale, "mappingMatrix.computed.cell.line2.noReturn", call)
			: MatrixMessages.get(pLocale, "mappingMatrix.computed.cell.line2", call, returns);
	}

	/** The chip of the Rules and checks column: {@code Script · toE164}. */
	static String chipLabel(final MatrixModel.Attr pAttr, final Guard pGuard, final Locale pLocale)
	{
		final String function = pAttr.computation != null && !pAttr.computation.function.isEmpty() ? pAttr.computation.function : pGuard.label();
		return MatrixMessages.get(pLocale, "mappingMatrix.computed.chip", function);
	}

	/** The SCRIPT_DERIVATION guard of this attribute, or null. */
	static Guard derivationGuard(final MatrixModel.Attr pAttr)
	{
		for (final Guard guard : pAttr.guards)
		{
			if (ReviewLine.SCRIPT_DERIVATION.equals(guard.nature))
			{
				return guard;
			}
		}
		return null;
	}

	// -------------------------------------------------------------------------------------------------------- lines

	/** {@code value -> phone}, {@code country -> blank (constant)}, {@code x -> constant "y"}. */
	static String argLine(final Arg pArg, final Locale pLocale)
	{
		if (pArg.kind == BindingEntry.Kind.ATTRIBUTE)
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.computed.arg.field", pArg.parameter, pArg.value);
		}
		if (pArg.kind == BindingEntry.Kind.LITERAL)
		{
			return pArg.value.isEmpty() ? MatrixMessages.get(pLocale, "mappingMatrix.computed.arg.blank", pArg.parameter)
				: MatrixMessages.get(pLocale, "mappingMatrix.computed.arg.literal", pArg.parameter, "\"" + pArg.value + "\"");
		}
		return MatrixMessages.get(pLocale, "mappingMatrix.computed.arg.toBeProvided", pArg.parameter);
	}

	/** The one sentence a source's numbers make, e.g. {@code ECOM: of 313 filled values of 313, 0 were converted and 313 came out empty.}; the sampled form says how many were evaluated. */
	static String measureLine(final MatrixModel.SourceCount pCount, final Locale pLocale)
	{
		if (pCount.total == 0)
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.computed.measure.noRows", pCount.sourceSystem);
		}
		if (pCount.filled == 0)
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.computed.measure.neverFilled", pCount.sourceSystem, String.valueOf(pCount.total));
		}
		if (pCount.sampled >= pCount.filled)
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.computed.measure.line", pCount.sourceSystem, String.valueOf(pCount.filled), String.valueOf(pCount.total),
				String.valueOf(pCount.converted), String.valueOf(pCount.empty));
		}
		return MatrixMessages.get(pLocale, "mappingMatrix.computed.measure.lineSampled", pCount.sourceSystem, String.valueOf(pCount.sampled),
			String.valueOf(pCount.filled), String.valueOf(pCount.total), String.valueOf(pCount.converted), String.valueOf(pCount.empty));
	}

	/** What the Measured section says when there is no count to show (before a load, function not applied, unreadable table). */
	static String measureStateText(final MatrixModel.Measurement pMeasurement, final Locale pLocale)
	{
		if (MatrixModel.Measurement.NOT_LOADED.equals(pMeasurement.state))
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.computed.measure.notLoaded");
		}
		if (MatrixModel.Measurement.NOT_APPLIED.equals(pMeasurement.state))
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.computed.measure.notApplied");
		}
		if (MatrixModel.Measurement.UNAVAILABLE.equals(pMeasurement.state))
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.computed.measure.unavailable", pMeasurement.problem);
		}
		return "";
	}

	// ------------------------------------------------------------------------------------------------------- JSON

	/**
	 * {@code pBaseJson} (the cell's ordinary {@code MatrixHtml#cellJson}) with the computed-field sections appended:
	 * {@code computed:true} and the localized, already-assembled texts the inspector prints - it never composes a
	 * sentence itself, it only escapes and lays out what it is given.
	 */
	static String cellJson(final String pBaseJson, final MatrixModel.Entity pEntity, final MatrixModel.Attr pAttr, final Locale pLocale)
	{
		final MatrixModel.Computation c = pAttr.computation;
		final StringBuilder j = new StringBuilder(pBaseJson.substring(0, pBaseJson.length() - 1));
		if (j.charAt(j.length() - 1) != ',' && j.charAt(j.length() - 1) != '{')
		{
			j.append(',');
		}
		MatrixHtml.jsonField(j, "computed", "true", false);
		MatrixHtml.jsonField(j, "cCall", MatrixComputed.callText(c), true);
		MatrixHtml.jsonField(j, "cWhatTitle", MatrixMessages.get(pLocale, "mappingMatrix.computed.what.title"), true);
		// lot G2 (2026-10-17): the function's texts in the viewer's language (MatrixFunctionTexts), the library's own when there are none
		final String what = MatrixFunctionTexts.description(c, pLocale);
		MatrixHtml.jsonField(j, "cWhat", !what.isEmpty() ? what : MatrixMessages.get(pLocale, "mappingMatrix.computed.what.unreadable", c.function), true);
		MatrixHtml.jsonField(j, "cWhatMuted", !what.isEmpty() ? "false" : "true", false);
		j.append("\"cArgs\":[");
		boolean first = true;
		for (final Arg arg : MatrixComputed.args(c))
		{
			j.append(first ? "" : ",").append('{');
			first = false;
			MatrixHtml.jsonField(j, "line", MatrixComputed.argLine(arg, pLocale), true);
			MatrixHtml.jsonField(j, "text", MatrixFunctionTexts.param(c, pLocale, arg.parameter), true);
			j.setLength(j.length() - 1);
			j.append('}');
		}
		j.append("],");
		MatrixHtml.jsonField(j, "cEmptyTitle", MatrixMessages.get(pLocale, "mappingMatrix.computed.empty.title"), true);
		final String empty = MatrixFunctionTexts.returns(c, pLocale);
		MatrixHtml.jsonField(j, "cEmpty", !empty.isEmpty() ? empty : MatrixMessages.get(pLocale, "mappingMatrix.computed.empty.unknown"), true);
		MatrixHtml.jsonField(j, "cEmptyMuted", !empty.isEmpty() ? "false" : "true", false);
		MatrixHtml.jsonField(j, "cMeasureTitle", MatrixMessages.get(pLocale, "mappingMatrix.computed.measure.title"), true);
		MatrixHtml.jsonField(j, "cMeasureState", MatrixComputed.measureStateText(c.measurement, pLocale), true);
		j.append("\"cMeasure\":[");
		first = true;
		for (final MatrixModel.SourceCount count : c.measurement.sources)
		{
			j.append(first ? "" : ",").append('{');
			first = false;
			MatrixHtml.jsonField(j, "line", MatrixComputed.measureLine(count, pLocale), true);
			MatrixHtml.jsonField(j, "conv", count.convertedBefore == null ? ""
				: MatrixMessages.get(pLocale, "mappingMatrix.computed.measure.exConverted", count.convertedBefore, count.convertedAfter == null ? "" : count.convertedAfter), true);
			MatrixHtml.jsonField(j, "empty", count.emptyBefore == null ? "" : MatrixMessages.get(pLocale, "mappingMatrix.computed.measure.exEmpty", count.emptyBefore), true);
			j.setLength(j.length() - 1);
			j.append('}');
		}
		j.append("],");
		MatrixHtml.jsonField(j, "cWhyTitle", MatrixMessages.get(pLocale, "mappingMatrix.computed.why.title"), true);
		MatrixHtml.jsonField(j, "cWhy", c.rationale, true);
		// the decision: the FIELD's own Accept/Reject - accepting it takes the PROPOSED suggestion along, rejecting it rejects it
		// (ebx.ProposalReviews). A field already decided but a suggestion still PROPOSED (an older row) keeps its own two buttons.
		final boolean fieldOpen = ReviewLine.PROPOSED.equals(pAttr.status);
		final boolean entityAccepted = "ACCEPTED".equals(pEntity.status) || "APPLIED".equals(pEntity.status);
		final boolean guardOpen = !fieldOpen && ReviewLine.PROPOSED.equals(c.suggestionStatus) && !c.suggestionId.isEmpty();
		MatrixHtml.jsonField(j, "cDecide", fieldOpen ? (entityAccepted ? "FIELD" : "ENTITY_FIRST") : guardOpen ? "GUARD" : "", true);
		MatrixHtml.jsonField(j, "cGuardId", c.suggestionId, true);
		MatrixHtml.jsonField(j, "cAcceptLabel", MatrixMessages.get(pLocale, fieldOpen ? "mappingMatrix.computed.acceptField" : "mappingMatrix.computed.acceptFunction"), true);
		MatrixHtml.jsonField(j, "cRejectLabel", MatrixMessages.get(pLocale, fieldOpen ? "mappingMatrix.computed.rejectField" : "mappingMatrix.computed.rejectFunction"), true);
		MatrixHtml.jsonField(j, "cEntityFirst", MatrixMessages.get(pLocale, "mappingMatrix.js.acceptEntityFirst"), true);
		j.setLength(j.length() - 1);
		j.append('}');
		return j.toString();
	}
}
