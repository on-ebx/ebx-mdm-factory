package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Audit F-08: the structural guard of a constraint body the language model wrote, reading TOKENS instead of lines. The guard it replaces matched regular
 * expressions anchored at the start of a line, so a second {@code import} on the same line was not counted, {@code uses x;} without {@code as} skipped the
 * allow-list, a forbidden access was found only when written as one contiguous string, and a call (or an entry point) that only appeared in a comment or a
 * string literal satisfied the checks. Here comments and string literals are removed first (a string becomes one opaque token), then:
 * <ul>
 * <li>every {@code import} and {@code uses} statement, anywhere and several per line, is parsed ({@code import a.b as c;}, {@code uses a.b;}) - a statement
 * that cannot be parsed is refused, never skipped;</li>
 * <li>exactly one library is imported, the expected one; every {@code uses} names an allow-listed module;</li>
 * <li>outside those statements nothing refers to a {@code core} module that is not allow-listed, to {@code db}, or to an embedded SQL literal, however the
 * dots, spaces and comments are laid out;</li>
 * <li>the expected function is really called through the import's alias, and the entry points the scope needs are really declared.</li>
 * </ul>
 * Pure: no EBX class. What EBX Script itself accepts inside a dotted name (spaces, comments) is not established; reading such a name as one name can only make
 * this guard stricter than EBX, never looser. The messages of the guard it replaces are kept as they were.
 */
public final class ScriptGuard
{
	/** The {@code core} modules a constraint body may use - the same list {@code ConstraintBodyBuilder} has always applied. */
	/** F2-01: a dotted library name, as {@code import Fasttrack.lib.phone as phone;} writes it. */
	private static final java.util.regex.Pattern IMPORT_NAME = java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*$");
	/** F2-01: an attribute name as {@code record.<name>} writes it. */
	private static final java.util.regex.Pattern ATTRIBUTE_NAME = java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

	static final Set<String> ALLOWED_USES = new LinkedHashSet<String>(Arrays.asList("core.message", "core.validation", "core.json", "core.string"));

	/** The sentence of the original guard for a database or outbound access. */
	static final String DATABASE_MESSAGE = "the source touches the database or an outbound call (core.data / core.http / db.xxx(...)) - a constraint body must never do this.";

	private ScriptGuard()
	{
	}

	/** What the tokenizer sees. */
	enum Kind
	{
		/** An identifier or keyword. */
		WORD,
		/** A number, as one token (so that {@code 1.5} is never a dotted name). */
		NUMBER,
		/** A single-quoted string literal, content dropped. */
		STRING,
		/** One punctuation character. */
		SYMBOL,
		/** A double quote or a back quote (a delimiter EBX Script code does not need): {@link #text} is that one character. */
		QUOTE
	}

	static final class Token
	{
		final Kind kind;
		final String text;

		Token(final Kind pKind, final String pText)
		{
			this.kind = pKind;
			this.text = pText;
		}

		boolean is(final Kind pKind, final String pText)
		{
			return this.kind == pKind && this.text.equals(pText);
		}

		boolean word(final String pText)
		{
			return this.is(Kind.WORD, pText);
		}

		boolean symbol(final String pText)
		{
			return this.is(Kind.SYMBOL, pText);
		}
	}

	/**
	 * Tokens of an EBX Script source: {@code //} and {@code /* *}{@code /} comments are removed, a single-quoted string (backslash escapes, as CLAUDE.md
	 * section 2 documents) is one STRING token. Never throws; an unterminated comment or string ends at the end of the text.
	 */
	static List<Token> tokenize(final String pSource)
	{
		final List<Token> out = new ArrayList<Token>();
		final int n = pSource.length();
		int i = 0;
		while (i < n)
		{
			final char c = pSource.charAt(i);
			if (Character.isWhitespace(c))
			{
				i++;
			}
			else if (c == '/' && i + 1 < n && pSource.charAt(i + 1) == '/')
			{
				while (i < n && pSource.charAt(i) != '\n' && pSource.charAt(i) != '\r')
				{
					i++;
				}
			}
			else if (c == '/' && i + 1 < n && pSource.charAt(i + 1) == '*')
			{
				final int end = pSource.indexOf("*/", i + 2);
				i = end < 0 ? n : end + 2;
			}
			else if (c == '\'')
			{
				i++;
				while (i < n && pSource.charAt(i) != '\'')
				{
					i += pSource.charAt(i) == '\\' ? 2 : 1;
				}
				i++; // the closing quote
				out.add(new Token(Kind.STRING, "'"));
			}
			else if (c == '"' || c == '`')
			{
				out.add(new Token(Kind.QUOTE, String.valueOf(c)));
				i++;
			}
			else if (Character.isLetter(c) || c == '_')
			{
				final int start = i;
				while (i < n && (Character.isLetterOrDigit(pSource.charAt(i)) || pSource.charAt(i) == '_'))
				{
					i++;
				}
				out.add(new Token(Kind.WORD, pSource.substring(start, i)));
			}
			else if (Character.isDigit(c))
			{
				final int start = i;
				while (i < n && (Character.isLetterOrDigit(pSource.charAt(i)) || pSource.charAt(i) == '_' || pSource.charAt(i) == '.' && i + 1 < n
					&& Character.isDigit(pSource.charAt(i + 1))))
				{
					i++;
				}
				out.add(new Token(Kind.NUMBER, pSource.substring(start, i)));
			}
			else
			{
				out.add(new Token(Kind.SYMBOL, String.valueOf(c)));
				i++;
			}
		}
		return out;
	}

	/** One {@code import}/{@code uses} statement: its dotted name, its alias (null when none), and the range of tokens it covers. */
	static final class Statement
	{
		final String keyword;
		final String name;
		final String alias;
		final int first;
		final int last;

		Statement(final String pKeyword, final String pName, final String pAlias, final int pFirst, final int pLast)
		{
			this.keyword = pKeyword;
			this.name = pName;
			this.alias = pAlias;
			this.first = pFirst;
			this.last = pLast;
		}
	}

	/**
	 * Parses the statement that starts at token {@code pAt} ({@code import}/{@code uses}, dotted name, optional {@code as alias}, semicolon), or returns null
	 * when it is not of that form.
	 */
	static Statement parseStatement(final List<Token> pTokens, final int pAt)
	{
		final Token keyword = pTokens.get(pAt);
		int i = pAt + 1;
		final StringBuilder name = new StringBuilder();
		if (i >= pTokens.size() || pTokens.get(i).kind != Kind.WORD)
		{
			return null;
		}
		name.append(pTokens.get(i).text);
		i++;
		while (i + 1 < pTokens.size() && pTokens.get(i).symbol(".") && pTokens.get(i + 1).kind == Kind.WORD)
		{
			name.append('.').append(pTokens.get(i + 1).text);
			i += 2;
		}
		String alias = null;
		if (i + 1 < pTokens.size() && pTokens.get(i).word("as") && pTokens.get(i + 1).kind == Kind.WORD)
		{
			alias = pTokens.get(i + 1).text;
			i += 2;
		}
		if (i >= pTokens.size() || !pTokens.get(i).symbol(";"))
		{
			return null;
		}
		return new Statement(keyword.text, name.toString(), alias, pAt, i);
	}

	/**
	 * The first reason {@code pSource} may not be sent to {@code update_script}, or null. The sentence is the guard's own; the caller adds what the source looked like.
	 *
	 * @param pNodeScope true for a node constraint (entry points {@code check}, {@code setup}), false for a table constraint ({@code checkTable}, {@code checkRecord},
	 *            {@code setup})
	 */
	public static String firstViolation(final String pSource, final String pImportName, final String pFunctionName, final boolean pNodeScope)
	{
		final String violation = ScriptGuard.violationBeforeEntryPoints(pSource, pImportName, pFunctionName);
		if (violation != null)
		{
			return violation;
		}
		final List<Token> tokens = ScriptGuard.tokenize(pSource);
		final String[] entryPoints = pNodeScope ? new String[] { "check", "setup" } : new String[] { "checkTable", "checkRecord", "setup" };
		for (final String entryPoint : entryPoints)
		{
			if (!ScriptGuard.declares(tokens, entryPoint))
			{
				return pNodeScope ? "no line starts with 'export procedure " + entryPoint + "(': a node constraint must define it" + ("setup".equals(entryPoint) ? " too." : ".")
					: "no line starts with 'export procedure " + entryPoint + "(': a table constraint must define it" + ("checkTable".equals(entryPoint) ? ", even if empty."
						: "setup".equals(entryPoint) ? " too, even if empty." : ".");
			}
		}
		return null;
	}

	/**
	 * F2-01: the same token reading for a body the Factory BUILT from a template (a constraint, or a derivation trigger) and is about to compile and publish: one import,
	 * the expected one; allow-listed {@code uses} only; no database or outbound access; the function really called through the alias; and the given entry points declared.
	 * A hostile import name or binding that the pattern checks missed cannot add a statement, a second library or a call to another module without being seen here.
	 *
	 * @param pEntryPoints the {@code export procedure} names the body must declare (e.g. {@code check}, {@code setup} or {@code onBeforeCreate})
	 */
	public static String firstTemplateViolation(final String pSource, final String pImportName, final String pFunctionName, final String... pEntryPoints)
	{
		final String violation = ScriptGuard.violationBeforeEntryPoints(pSource, pImportName, pFunctionName);
		if (violation != null)
		{
			return violation;
		}
		final List<Token> tokens = ScriptGuard.tokenize(pSource);
		for (final String entryPoint : pEntryPoints)
		{
			if (!ScriptGuard.declares(tokens, entryPoint))
			{
				return "no line starts with 'export procedure " + entryPoint + "(': the generated body must define it.";
			}
		}
		return null;
	}

	/** F2-01: the sentence refusing an import name that is not a dotted EBX Script library name, or null. */
	public static String importNameViolation(final String pImportName)
	{
		if (pImportName == null || !ScriptGuard.IMPORT_NAME.matcher(pImportName).matches())
		{
			return "the import name '" + ScriptGuard.shown(pImportName) + "' is not a library name (letters, digits and '_' in dotted segments, e.g. Fasttrack.lib.phone).";
		}
		return null;
	}

	/** F2-01: the sentence refusing an attribute name that is not a plain EBX Script identifier (it is written as {@code record.<name>}), or null. */
	public static String attributeNameViolation(final String pAttributeName)
	{
		if (pAttributeName == null || !ScriptGuard.ATTRIBUTE_NAME.matcher(pAttributeName).matches())
		{
			return "the attribute name '" + ScriptGuard.shown(pAttributeName) + "' is not an identifier (letters, digits and '_', starting with a letter or '_').";
		}
		return null;
	}

	private static String shown(final String pValue)
	{
		if (pValue == null)
		{
			return "null";
		}
		return pValue.length() > 60 ? pValue.substring(0, 60) + "..." : pValue;
	}

	private static String violationBeforeEntryPoints(final String pSource, final String pImportName, final String pFunctionName)
	{
		final List<Token> tokens = ScriptGuard.tokenize(pSource);
		final List<Statement> imports = new ArrayList<Statement>();
		final List<Statement> uses = new ArrayList<Statement>();
		final boolean[] insideStatement = new boolean[tokens.size()];
		for (int i = 0; i < tokens.size(); i++)
		{
			final Token token = tokens.get(i);
			if (token.kind != Kind.WORD || !(token.text.equals("import") || token.text.equals("uses")) || i > 0 && tokens.get(i - 1).symbol("."))
			{
				continue;
			}
			final Statement statement = ScriptGuard.parseStatement(tokens, i);
			if (statement == null)
			{
				return "the source has an '" + token.text + "' that is not of the form '" + token.text + " <name> as <alias>;' (a name, optionally 'as' and an alias, then ';').";
			}
			for (int k = statement.first; k <= statement.last; k++)
			{
				insideStatement[k] = true;
			}
			(token.text.equals("import") ? imports : uses).add(statement);
		}
		if (imports.isEmpty())
		{
			return "no line starts with 'import " + pImportName + " as'; the source must 'import " + pImportName + " as <alias>;' somewhere.";
		}
		String alias = null;
		for (final Statement entry : imports)
		{
			if (entry.name.equals(pImportName))
			{
				alias = entry.alias == null ? entry.name.substring(entry.name.lastIndexOf('.') + 1) : entry.alias;
			}
		}
		if (alias == null)
		{
			return "the source imports '" + imports.get(0).name + "', not '" + pImportName + "' - it must import exactly '" + pImportName + "'.";
		}
		if (imports.size() > 1)
		{
			return "the source imports more than one library (" + imports.size() + " import lines): only '" + pImportName + "' is allowed.";
		}
		if (!ScriptGuard.callsThrough(tokens, insideStatement, alias, pFunctionName))
		{
			return "the source never calls '" + alias + "." + pFunctionName + "(': the constraint would do nothing.";
		}
		for (final Statement entry : uses)
		{
			if (!ScriptGuard.ALLOWED_USES.contains(entry.name))
			{
				return "the source declares 'uses " + entry.name + (entry.alias == null ? "" : " as " + entry.alias) + "', which is not allowed - only "
					+ ScriptGuard.ALLOWED_USES + " may be used.";
			}
		}
		final String forbidden = ScriptGuard.forbiddenReference(tokens, insideStatement);
		if (forbidden != null)
		{
			return forbidden;
		}
		return null;
	}

	/** {@code alias.function(} outside the import/uses statements, as tokens (spaces and comments in between do not matter). */
	private static boolean callsThrough(final List<Token> pTokens, final boolean[] pInsideStatement, final String pAlias, final String pFunction)
	{
		for (int i = 0; i + 3 < pTokens.size(); i++)
		{
			if (!pInsideStatement[i] && pTokens.get(i).word(pAlias) && pTokens.get(i + 1).symbol(".") && pTokens.get(i + 2).word(pFunction) && pTokens.get(i + 3).symbol("(")
				&& (i == 0 || !pTokens.get(i - 1).symbol(".")))
			{
				return true;
			}
		}
		return false;
	}

	/** {@code export procedure <name> (} as tokens. */
	private static boolean declares(final List<Token> pTokens, final String pName)
	{
		for (int i = 0; i + 3 < pTokens.size(); i++)
		{
			if (pTokens.get(i).word("export") && pTokens.get(i + 1).word("procedure") && pTokens.get(i + 2).word(pName) && pTokens.get(i + 3).symbol("("))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * A reference outside the allow-list, as a sentence, or null: a {@code core} module that is not allowed (reading {@code core.data} / {@code core.http} with the
	 * original sentence), any use of {@code db}, an embedded SQL literal, a double-quoted or back-quoted literal. A word that follows a dot is a member of
	 * something else ({@code record.db}) and is not read as the module.
	 */
	private static String forbiddenReference(final List<Token> pTokens, final boolean[] pInsideStatement)
	{
		for (int i = 0; i < pTokens.size(); i++)
		{
			if (pInsideStatement[i])
			{
				continue;
			}
			final Token token = pTokens.get(i);
			if (token.kind == Kind.QUOTE)
			{
				return "`".equals(token.text) ? "the source has an embedded SQL literal (back quotes): a constraint body must never read the database."
					: "the source has a double-quoted literal: EBX Script strings are single-quoted, and nothing else is read here.";
			}
			final boolean member = i > 0 && pTokens.get(i - 1).symbol(".");
			if (token.kind != Kind.WORD || member)
			{
				continue;
			}
			if (token.text.equals("db") && i + 1 < pTokens.size() && pTokens.get(i + 1).symbol("."))
			{
				return ScriptGuard.DATABASE_MESSAGE;
			}
			if (token.text.equals("core") && i + 2 < pTokens.size() && pTokens.get(i + 1).symbol(".") && pTokens.get(i + 2).kind == Kind.WORD)
			{
				final String module = "core." + pTokens.get(i + 2).text;
				if (module.equals("core.data") || module.equals("core.http"))
				{
					return ScriptGuard.DATABASE_MESSAGE;
				}
				if (!ScriptGuard.ALLOWED_USES.contains(module))
				{
					return "the source refers to '" + module + "', which is not allowed - only " + ScriptGuard.ALLOWED_USES + " may be used.";
				}
			}
		}
		return null;
	}
}
