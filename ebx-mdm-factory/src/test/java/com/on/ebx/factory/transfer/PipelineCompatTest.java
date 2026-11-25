package com.on.ebx.factory.transfer;

/**
 * Structural translation checks only (docs/TARGET-MAPPING.md sec 2.2) - no Connectors class on the
 * classpath here, so this never runs a produced pipeline through the real engine. That equivalence (the
 * design's own required check) is {@code TransferPipelineEquivalenceTest}, compiled against
 * {@code on-ebx-mapping.jar} once it exists - see this lot's own final report for whether that jar was
 * lot 0's or a temporary one. JUnit-free, like the tests of the sibling modules: run the main, a failure
 * throws.
 */
public final class PipelineCompatTest
{
	public static void main(final String[] pArgs)
	{
		PipelineCompatTest.noneIsEmptyPipeline();
		PipelineCompatTest.trimUpperLower();
		PipelineCompatTest.parseDateDateTarget();
		PipelineCompatTest.parseDateTimestampOrUnrecognisedTarget();
		PipelineCompatTest.parseDateMissingPatternThrows();
		PipelineCompatTest.parseDecimalDefaultSeparatorSkipsReplace();
		PipelineCompatTest.parseDecimalCustomSeparatorQuoted();
		PipelineCompatTest.parseBooleanIgnoresArgument();
		PipelineCompatTest.transcodeFlattensToMapArgument();
		PipelineCompatTest.transcodeMissingArgumentThrows();
		PipelineCompatTest.transcodeNotAnObjectThrows();
		PipelineCompatTest.constantWithValueQuoted();
		PipelineCompatTest.constantWithNullArgumentHasNoPipelineArgument();
		PipelineCompatTest.constantWithEmptyArgumentIsQuotedEmpty();
		PipelineCompatTest.blankTransformationReadsAsNone();
		PipelineCompatTest.unknownTransformationThrows();
		PipelineCompatTest.checkConstraintsMandatoryAndMaxLength();
		PipelineCompatTest.checkConstraintsNullVsEmptyStringQuirk();
		System.out.println("PipelineCompatTest OK");
	}

	private static void noneIsEmptyPipeline()
	{
		PipelineCompatTest.check("NONE -> empty", "".equals(PipelineCompat.translate("NONE", null, "STRING")));
		PipelineCompatTest.check("blank transformation defaults like NONE", "".equals(PipelineCompat.translate("", null, "STRING")));
	}

	private static void trimUpperLower()
	{
		PipelineCompatTest.check("TRIM", "trim".equals(PipelineCompat.translate("TRIM", null, "STRING")));
		PipelineCompatTest.check("UPPER", "trim|upper".equals(PipelineCompat.translate("UPPER", null, "STRING")));
		PipelineCompatTest.check("LOWER", "trim|lower".equals(PipelineCompat.translate("LOWER", null, "STRING")));
		// case-insensitive on the transformation NAME, exactly like the retired Transformations#apply
		PipelineCompatTest.check("lower-case transformation name still recognised", "trim|upper".equals(PipelineCompat.translate("upper", null, "STRING")));
	}

	private static void parseDateDateTarget()
	{
		final String pipeline = PipelineCompat.translate("PARSE_DATE", "yyyy-MM-dd", "DATE");
		PipelineCompatTest.check("DATE target uses the xs:date pattern: " + pipeline, "trim|date('yyyy-MM-dd',yyyy-MM-dd)".equals(pipeline));
	}

	private static void parseDateTimestampOrUnrecognisedTarget()
	{
		final String timestamp = PipelineCompat.translate("PARSE_DATE", "dd/MM/yyyy", "TIMESTAMP");
		PipelineCompatTest.check("TIMESTAMP target uses the xs:dateTime pattern: " + timestamp,
			"trim|date('dd/MM/yyyy',yyyy-MM-dd'T'HH:mm:ss)".equals(timestamp));
		// same "defensively falls to the wider shape" rule as the retired Transformations#parseDate
		final String unrecognised = PipelineCompat.translate("PARSE_DATE", "dd/MM/yyyy", null);
		PipelineCompatTest.check("an unrecognised/blank target also gets the wider (dateTime) pattern", timestamp.equals(unrecognised));
	}

	private static void parseDateMissingPatternThrows()
	{
		try
		{
			PipelineCompat.translate("PARSE_DATE", null, "DATE");
			throw new AssertionError("expected IllegalArgumentException for a missing date pattern");
		}
		catch (final IllegalArgumentException expected)
		{
			// intentional: see the class Javadoc's PARSE_DATE entry
		}
		try
		{
			PipelineCompat.translate("PARSE_DATE", "   ", "DATE");
			throw new AssertionError("expected IllegalArgumentException for a blank date pattern");
		}
		catch (final IllegalArgumentException expected)
		{
			// intentional
		}
	}

	private static void parseDecimalDefaultSeparatorSkipsReplace()
	{
		PipelineCompatTest.check("default separator '.': no replace step", "trim|number".equals(PipelineCompat.translate("PARSE_DECIMAL", ".", "DECIMAL")));
		PipelineCompatTest.check("null argument defaults to '.' too, like the retired class",
			"trim|number".equals(PipelineCompat.translate("PARSE_DECIMAL", null, "DECIMAL")));
		PipelineCompatTest.check("blank argument defaults to '.' too", "trim|number".equals(PipelineCompat.translate("PARSE_DECIMAL", "  ", "DECIMAL")));
	}

	private static void parseDecimalCustomSeparatorQuoted()
	{
		final String pipeline = PipelineCompat.translate("PARSE_DECIMAL", ",", "DECIMAL");
		PipelineCompatTest.check("comma separator is quoted (it is the pipeline's own argument separator): " + pipeline,
			"trim|replace(',',.)|number".equals(pipeline));
	}

	private static void parseBooleanIgnoresArgument()
	{
		// map, not boolean - see the class Javadoc's PARSE_BOOLEAN entry (boolean silently coerces an
		// unrecognised word to false; map answers null, a reject only when the attribute is mandatory)
		final String expected = "trim|map(true=true;1=true;y=true;o=true;yes=true;oui=true;false=false;0=false;n=false;no=false;non=false)";
		PipelineCompatTest.check("PARSE_BOOLEAN never reads its argument, like the retired class",
			expected.equals(PipelineCompat.translate("PARSE_BOOLEAN", "whatever", "BOOLEAN")));
	}

	private static void transcodeFlattensToMapArgument()
	{
		final String pipeline = PipelineCompat.translate("TRANSCODE", "{\"M\":\"MALE\",\"F\":\"FEMALE\",\"*\":\"UNKNOWN\"}", "STRING");
		PipelineCompatTest.check("flattened to map's key=value;key=value syntax, one quoted argument: " + pipeline,
			"trim|map('M=MALE;F=FEMALE;*=UNKNOWN')".equals(pipeline));
	}

	private static void transcodeMissingArgumentThrows()
	{
		try
		{
			PipelineCompat.translate("TRANSCODE", null, "STRING");
			throw new AssertionError("expected IllegalArgumentException for a missing transcode table");
		}
		catch (final IllegalArgumentException expected)
		{
			// intentional: see the class Javadoc's TRANSCODE entry
		}
	}

	private static void transcodeNotAnObjectThrows()
	{
		try
		{
			PipelineCompat.translate("TRANSCODE", "[1,2,3]", "STRING");
			throw new AssertionError("expected IllegalArgumentException for a non-object transcode argument");
		}
		catch (final IllegalArgumentException expected)
		{
			// intentional
		}
	}

	private static void constantWithValueQuoted()
	{
		PipelineCompatTest.check("CONSTANT quotes its value", "constant('FR')".equals(PipelineCompat.translate("CONSTANT", "FR", "STRING")));
	}

	private static void constantWithNullArgumentHasNoPipelineArgument()
	{
		// a null OLD argument must round-trip to a null new value too (Transformations.arg(a,0,null) with an
		// empty argument list returns its own null default) - see the class Javadoc's CONSTANT entry
		PipelineCompatTest.check("constant() with no argument at all", "constant()".equals(PipelineCompat.translate("CONSTANT", null, "STRING")));
	}

	private static void constantWithEmptyArgumentIsQuotedEmpty()
	{
		PipelineCompatTest.check("constant('') for a present-but-empty old argument", "constant('')".equals(PipelineCompat.translate("CONSTANT", "", "STRING")));
	}

	private static void blankTransformationReadsAsNone()
	{
		PipelineCompatTest.check("null transformation reads as NONE", "".equals(PipelineCompat.translate(null, null, "STRING")));
	}

	private static void unknownTransformationThrows()
	{
		try
		{
			PipelineCompat.translate("NOT_A_REAL_TRANSFORMATION", null, "STRING");
			throw new AssertionError("expected IllegalArgumentException for an unknown transformation name");
		}
		catch (final IllegalArgumentException expected)
		{
			// intentional
		}
	}

	private static void checkConstraintsMandatoryAndMaxLength()
	{
		PipelineCompatTest.check("mandatory and null rejects", PipelineCompat.checkConstraints("email", null, null, true) != null);
		PipelineCompatTest.check("not mandatory and null is fine", PipelineCompat.checkConstraints("email", null, null, false) == null);
		final String tooLong = PipelineCompat.checkConstraints("email", "a-very-long-value", Integer.valueOf(5), false);
		PipelineCompatTest.check("too long names the attribute, the length and the maximum: " + tooLong,
			tooLong != null && tooLong.contains("email") && tooLong.contains("17") && tooLong.contains("5"));
		PipelineCompatTest.check("within the limit is fine", PipelineCompat.checkConstraints("email", "short", Integer.valueOf(5), false) == null);
	}

	/** The retired class's own quirk, kept on purpose - see the class Javadoc's {@code checkConstraints} entry. */
	private static void checkConstraintsNullVsEmptyStringQuirk()
	{
		PipelineCompatTest.check("an empty STRING (not null) is not 'empty' for the mandatory check",
			PipelineCompat.checkConstraints("code", "", null, true) == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
