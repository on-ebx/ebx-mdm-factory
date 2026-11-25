package com.on.ebx.factory.transfer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Connectors rule-pipeline transformations a Factory feed's {@code AttributeMapping.pipeline}/
 * {@code TransferFeed.sourceIdPipeline} may use, for the feed designer (docs/FEED-DESIGNER.md) and for the
 * language-model pass ({@code staging/StagingLlmPass}, docs/TARGET-MAPPING.md sec 4.2). Pure: no EBX type,
 * no {@code on-ebx-mapping.jar} class either - a plain, hand-kept mirror of {@code
 * com.on.ebx.connect.core.Transformations}'s own registered names (README of
 * {@code ~/Dev/wt/ebx-connectors-mapping-jar}, section "Mapping engine jar", read 2026-09-23), so this class
 * compiles and is unit-tested even before {@code tools/mapping_jar.sh} has put the jar in place.
 * <p>
 * <b>Excluded on purpose (7 of the 35 registered names, not 6 - docs/TARGET-MAPPING.md sec 4.2's own
 * correction of an earlier draft)</b>: {@code fk}, {@code lookup}, {@code golden}, {@code mdmState},
 * {@code runId}, {@code now}, {@code hash}. The first four resolve against a live EBX table (a reference
 * table, Match and Merge state) at RUN time - a person or a language model designing a feed never has that
 * table open in front of them, and a step whose behaviour depends on data nobody here can see is not a
 * transformation to author blind. {@code runId}/{@code now} depend on the moment the pipeline happens to
 * run, not on the row at hand - not a mapping decision either. {@code hash} produces an opaque digest with
 * no legitimate use in a target-mapping rule (it exists for {@code Transformations} itself to compute an
 * incremental-load fingerprint, not for a designer to expose). Every one of the remaining names (28, then 30 since 2026-10-23: {@code epochdate} and
 * {@code joinwidth}, Connectors {@code feat/odata-date-concat}) is
 * offered here, in the exact order and with the exact argument syntax the README table gives, so a step
 * built through the designer's inspector round-trips through {@code MappingEngine.compute} unchanged.
 * <p>
 * <b>{@link #parsePipeline}</b> is a byte-for-byte Java port of the page script's own {@code stepsOf}
 * function ({@code www/common/jscripts/factory/feed-designer.js}, forked 2026-09-23 from ebx-connectors'
 * {@code mapping-designer.js}): same quote tracking (a step name/argument list may itself embed a quoted
 * {@code |} or {@code ,}, e.g. {@code join('; ')}, {@code regex('\d{2,4}')}), same paren-depth tracking so a
 * {@code |} inside a step's own argument list never splits the pipeline, same trailing-empty-step handling
 * (an empty segment BETWEEN two pipes is kept as a step with an empty name; a trailing empty segment - the
 * pipeline ends in {@code |} - is dropped). Java and JavaScript must never read the same pipeline text
 * differently: the two are compared step by step in this class's own test and would need to be updated
 * together if either ever changes.
 */
public final class TransformationCatalogue
{
	private TransformationCatalogue()
	{
	}

	/** One allowed transformation: its name, its {@code (arg1,arg2,...)} syntax (empty when it takes none), and a one-line hint in each language. */
	public static final class Entry
	{
		public final String name;
		public final String argumentSyntax;
		public final String hintEn;
		public final String hintFr;

		Entry(final String pName, final String pArgumentSyntax, final String pHintEn, final String pHintFr)
		{
			this.name = pName;
			this.argumentSyntax = pArgumentSyntax;
			this.hintEn = pHintEn;
			this.hintFr = pHintFr;
		}

		/** {@code name} alone when it takes no argument, else {@code name(argumentSyntax)} - what the inspector's "add a step" list shows and what a raw pipeline segment looks like. */
		public String display()
		{
			return this.argumentSyntax.isEmpty() ? this.name : this.name + "(" + this.argumentSyntax + ")";
		}
	}

	// Order and argument syntax exactly as the README's own table (~/Dev/wt/ebx-connectors-mapping-jar,
	// "Mapping engine jar" section) lists them, minus the seven excluded names - see the class Javadoc.
	private static final Entry[] ENTRIES = {
		new Entry("trim", "", "Removes leading/trailing whitespace.", "Supprime les espaces en debut et fin de chaine."),
		new Entry("upper", "", "Converts to upper case.", "Met en majuscules."),
		new Entry("lower", "", "Converts to lower case.", "Met en minuscules."),
		new Entry("capitalize", "", "Capitalizes the first letter of each word.",
			"Met une majuscule au debut de chaque mot."),
		new Entry("left", "n", "Keeps the first n characters.", "Garde les n premiers caracteres."),
		new Entry("right", "n", "Keeps the last n characters.", "Garde les n derniers caracteres."),
		new Entry("substring", "start[,end]", "Extracts characters from start to end (0-based, end exclusive).",
			"Extrait les caracteres de start a end (base 0, end exclu)."),
		new Entry("replace", "old,new", "Replaces every literal occurrence of old with new.",
			"Remplace chaque occurrence litterale de old par new."),
		new Entry("regex", "pattern[,group]", "First regex match, or a capture group of it.",
			"Premiere correspondance de l'expression reguliere, ou un de ses groupes captures."),
		new Entry("vatfr", "", "Builds a French VAT number from a 9-digit SIREN or a 14-digit SIRET.",
			"Construit un numero de TVA francais a partir d'un SIREN (9 chiffres) ou d'un SIRET (14 chiffres)."),
		new Entry("digits", "", "Keeps only the digit characters.", "Ne garde que les chiffres."),
		new Entry("prefix", "text", "Prepends text.", "Ajoute text au debut de la valeur."),
		new Entry("suffix", "text", "Appends text.", "Ajoute text a la fin de la valeur."),
		new Entry("default", "value", "Uses value when the input is empty.", "Utilise value si l'entree est vide."),
		new Entry("constant", "value", "Always outputs value, ignoring the input.",
			"Renvoie toujours value, quelle que soit l'entree."),
		new Entry("nullifempty", "", "Turns a blank value into null.", "Transforme une valeur vide en null."),
		new Entry("nullif", "value", "Turns the input into null when it equals value.",
			"Transforme l'entree en null si elle est egale a value."),
		new Entry("isodate", "", "Keeps the first 10 characters (the date part) of an ISO timestamp.",
			"Garde les 10 premiers caracteres (la partie date) d'un horodatage ISO."),
		new Entry("isodatetime", "", "Keeps the first 19 characters of an ISO timestamp, normalising the separator to T.",
			"Garde les 19 premiers caracteres d'un horodatage ISO, en normalisant le separateur en T."),
		new Entry("date", "inPattern[,outPattern]", "Parses a date with inPattern and reformats it with outPattern.",
			"Analyse une date avec inPattern et la reformate avec outPattern."),
		new Entry("epochdate", "[outPattern]", "Reads an OData /Date(ms)/ value or epoch milliseconds as a UTC date, written yyyy-MM-dd or outPattern.",
			"Lit une valeur OData /Date(ms)/ ou des millisecondes epoch comme une date UTC, ecrite yyyy-MM-dd ou outPattern."),
		new Entry("map", "K1=V1;K2=V2;...[;*=fallback]", "Looks the input up in a fixed key=value table (case-insensitive).",
			"Cherche l'entree dans une table fixe cle=valeur (insensible a la casse)."),
		new Entry("join", "[separator]", "Joins a list of values with separator.", "Assemble une liste de valeurs avec separator."),
		new Entry("first", "", "First element of a list.", "Premier element d'une liste."),
		new Entry("count", "", "Number of elements of a list.", "Nombre d'elements d'une liste."),
		new Entry("boolean", "", "Parses common true/false words, oui/non included.",
			"Analyse les mots courants vrai/faux, oui/non compris."),
		new Entry("number", "", "Parses a decimal number, tolerant of thousand separators and a comma decimal point.",
			"Analyse un nombre decimal, tolerant aux separateurs de milliers et a la virgule decimale."),
		new Entry("round", "[scale]", "Rounds a decimal number to scale digits.", "Arrondit un nombre decimal a scale chiffres."),
		new Entry("concat", "path1,path2,...", "Joins the values at the given document paths with a bar separator.",
			"Assemble les valeurs des chemins donnes avec un separateur barre (|)."),
		new Entry("joinwidth", "width,path1,path2,...",
			"Rebuilds one value cut into fixed-width pieces: a full-width piece is followed directly by the next, a shorter one by a space.",
			"Reconstitue une valeur coupee en morceaux de largeur fixe : un morceau plein est suivi directement du suivant, un plus court d'une espace."), };

	/** Every allowed transformation, in the order the inspector should list them. */
	public static List<Entry> entries()
	{
		return Collections.unmodifiableList(Arrays.asList(TransformationCatalogue.ENTRIES));
	}

	/** True when {@code pName} (case-insensitive) is one of the {@link #entries()} - what a pipeline step's name must satisfy to be accepted. */
	public static boolean isKnown(final String pName)
	{
		if (pName == null)
		{
			return false;
		}
		for (final Entry entry : TransformationCatalogue.ENTRIES)
		{
			if (entry.name.equalsIgnoreCase(pName.trim()))
			{
				return true;
			}
		}
		return false;
	}

	// --------------------------------------------------------------------------------------------------- parsing

	/** One step of a parsed pipeline: {@code args} is {@code null} when the step carries no parentheses at all (not even empty ones - {@code constant()} has {@code args = ""}, {@code trim} has {@code args = null}). */
	public static final class Step
	{
		public final String name;
		public final String args;

		Step(final String pName, final String pArgs)
		{
			this.name = pName;
			this.args = pArgs;
		}
	}

	private static final Pattern STEP_PATTERN = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\((.*)\\))?\\s*$");

	/**
	 * Splits {@code a|b(x,y)|c('with, a comma')} into its steps, respecting single and double quotes and
	 * parenthesis depth - the exact algorithm of the page script's {@code stepsOf} (see the class Javadoc).
	 * A step whose text does not match {@code name} or {@code name(args)} is returned with its raw text as
	 * the name and a null {@code args} (same fallback the JS makes).
	 */
	public static List<Step> parsePipeline(final String pPipeline)
	{
		final String source = pPipeline == null ? "" : pPipeline;
		final List<String> rawSteps = new ArrayList<String>();
		final StringBuilder current = new StringBuilder();
		int depth = 0;
		char quote = 0;
		for (int i = 0; i < source.length(); i++)
		{
			final char c = source.charAt(i);
			if (quote != 0)
			{
				current.append(c);
				if (c == quote)
				{
					quote = 0;
				}
				continue;
			}
			if (c == '\'' || c == '"')
			{
				quote = c;
				current.append(c);
				continue;
			}
			if (c == '(')
			{
				depth++;
			}
			if (c == ')')
			{
				depth--;
			}
			if (c == '|' && depth == 0)
			{
				rawSteps.add(current.toString().trim());
				current.setLength(0);
				continue;
			}
			current.append(c);
		}
		if (current.toString().trim().length() > 0)
		{
			rawSteps.add(current.toString().trim());
		}
		final List<Step> steps = new ArrayList<Step>();
		for (final String raw : rawSteps)
		{
			final Matcher matcher = TransformationCatalogue.STEP_PATTERN.matcher(raw);
			if (matcher.matches())
			{
				steps.add(new Step(matcher.group(1), matcher.group(2)));
			}
			else
			{
				steps.add(new Step(raw, null));
			}
		}
		return steps;
	}

	/** Re-joins parsed steps back into a pipeline string - the inverse of {@link #parsePipeline}, same convention as the page script's {@code joinSteps}. */
	public static String joinSteps(final List<Step> pSteps)
	{
		final StringBuilder out = new StringBuilder();
		for (final Step step : pSteps)
		{
			if (out.length() > 0)
			{
				out.append('|');
			}
			out.append(step.name);
			if (step.args != null)
			{
				out.append('(').append(step.args).append(')');
			}
		}
		return out.toString();
	}
}
