package com.on.ebx.factory.wizard;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sample-files lot (2026-09-23): pure rules for the wizard's "Sources from sample files" panel (step 2) -
 * turning a dropped file into a Connectors {@code sample-file} connection id, its dedicated host (so two
 * sample-file connections never collide on the same namespace, CLAUDE.md &sect;14), a proposed application
 * code, and the RFC 2397 data URL contract this lot shares with the parallel Connectors lot
 * ({@code feature/sample-file-name}): {@code data:<mime>;name=<encodeURIComponent(file name)>;base64,<payload>}
 * - the {@code ;name=} parameter carries the original file name through so the catalog asset (and the landing
 * table derived from it) is named after the file, not the generic connector id. No EBX/MCP import: unit-tested
 * in {@link SampleFileRulesTest}. The live side, {@code ebx.SampleSourceImporter}, is the only caller.
 * <p>
 * <b>{@link #RAW_BYTES_CAP} is deliberately never read from anywhere</b> - there is no reliable way to learn
 * Tomcat's own {@code maxPostSize} from inside EBX (measured on this instance's {@code server.xml}: the 8080
 * connector sets none, so the default 2 MB applies to the WHOLE form POST, sample data included). 1,400,000
 * raw bytes base64-inflates to about 1.87 MB of text, which leaves headroom for the rest of the wizard's own
 * hidden fields under that 2 MB ceiling. Raising the connector's {@code maxPostSize} in {@code server.xml}
 * lifts the practical limit to the connector's own 2 MB per file - this constant would then be
 * over-conservative, but never unsafe: it stays the same until someone deliberately raises it here too.
 */
public final class SampleFileRules
{
	/** Matches the EBX {@code Connector.id} field's own {@code maxLength} (Connectors.xsd, read-only reference, 2026-09-23). */
	public static final int CONNECTION_ID_MAX = 40;

	/**
	 * The cap applied BOTH ways by {@code ebx.SampleSourceImporter}: no single file's raw (pre-base64) size may
	 * exceed it, and the running total of raw bytes across every file of one submit may not exceed it either -
	 * see this class's own Javadoc for why the two checks share one number.
	 */
	public static final long RAW_BYTES_CAP = 1_400_000L;

	private static final Map<String, String> MIME_BY_EXTENSION;
	/** The distinct MIME values {@link #MIME_BY_EXTENSION} can produce - what a data URL's own mime must be one of to be accepted. */
	public static final Set<String> KNOWN_MIMES;

	static
	{
		final Map<String, String> byExtension = new HashMap<String, String>();
		byExtension.put("csv", "text/csv");
		byExtension.put("txt", "text/csv");
		byExtension.put("tsv", "text/tab-separated-values");
		byExtension.put("json", "application/json");
		byExtension.put("ndjson", "application/x-ndjson");
		byExtension.put("jsonl", "application/x-ndjson");
		MIME_BY_EXTENSION = Collections.unmodifiableMap(byExtension);
		KNOWN_MIMES = Collections.unmodifiableSet(new HashSet<String>(byExtension.values()));
	}

	/** {@code data:<mime>;name=<percent-encoded name>;base64,<payload>} - DOTALL so a payload with no embedded newlines still matches end to end; a base64 payload never legitimately contains {@code ;} or {@code ,} before its own start, so a non-greedy mime/name split is not needed. */
	private static final Pattern DATA_URL_PATTERN = Pattern.compile("^data:([^;,]+);name=([^;,]*);base64,(.*)$", Pattern.DOTALL);

	private static final String FALLBACK_PREFIX = "File";

	private SampleFileRules()
	{
	}

	// -------------------------------------------------------------------------------------------------------- mime

	/** {@code null} for a refused/unknown extension (no dot, or an extension not in the map). */
	public static String mimeFor(final String pFileName)
	{
		final String extension = SampleFileRules.extensionOf(pFileName);
		return extension == null ? null : SampleFileRules.MIME_BY_EXTENSION.get(extension);
	}

	private static String extensionOf(final String pFileName)
	{
		if (pFileName == null)
		{
			return null;
		}
		final int dot = pFileName.lastIndexOf('.');
		if (dot < 0 || dot + 1 >= pFileName.length())
		{
			return null;
		}
		return pFileName.substring(dot + 1).toLowerCase(Locale.ENGLISH);
	}

	/** {@code pFileName} with its last {@code .extension} stripped - the whole name when there is none. */
	public static String baseName(final String pFileName)
	{
		if (pFileName == null)
		{
			return "";
		}
		final int dot = pFileName.lastIndexOf('.');
		return dot <= 0 ? pFileName : pFileName.substring(0, dot);
	}

	// ------------------------------------------------------------------------------------------------ connection id

	/**
	 * {@code Personnes_SAP_Sample.json} -&gt; {@code PersonnesSapSample}; {@code clients export (v2).csv} -&gt;
	 * {@code ClientsExportV2} (task brief's own two examples). Never blank, never longer than
	 * {@link #CONNECTION_ID_MAX}, always starts with a letter (an all-digit/symbol base name is prefixed with
	 * {@code File}) - safe as-is for {@code Connector.id}.
	 */
	public static String connectionId(final String pFileName)
	{
		final String ascii = SampleFileRules.stripAccents(SampleFileRules.baseName(pFileName));
		final String[] segments = ascii.split("[^A-Za-z0-9]+");
		final StringBuilder camel = new StringBuilder();
		for (final String segment : segments)
		{
			if (segment.isEmpty())
			{
				continue;
			}
			camel.append(Character.toUpperCase(segment.charAt(0)));
			if (segment.length() > 1)
			{
				camel.append(segment.substring(1).toLowerCase(Locale.ENGLISH));
			}
		}
		String result = camel.toString();
		if (result.isEmpty() || !Character.isLetter(result.charAt(0)))
		{
			result = SampleFileRules.FALLBACK_PREFIX + result;
		}
		return result.length() > SampleFileRules.CONNECTION_ID_MAX ? result.substring(0, SampleFileRules.CONNECTION_ID_MAX) : result;
	}

	private static String stripAccents(final String pValue)
	{
		final String decomposed = Normalizer.normalize(pValue == null ? "" : pValue, Normalizer.Form.NFD);
		return decomposed.replaceAll("\\p{M}", "");
	}

	// ------------------------------------------------------------------------------------------------------- host

	/**
	 * A dedicated host for {@code pConnectionId}, never {@code localhost}: CLAUDE.md &sect;14 - the catalog's
	 * namespace is derived from the connection's {@code baseUrl} HOST alone, so two sample-file connections on
	 * the same host collide. {@code PersonnesSapSample} -&gt; {@code https://personnes-sap-sample.sample-file.local/data}.
	 */
	public static String host(final String pConnectionId)
	{
		String slug = (pConnectionId == null ? "" : pConnectionId).replaceAll("(?<=[a-z0-9])(?=[A-Z])", "-").toLowerCase(Locale.ENGLISH);
		slug = slug.replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-").replaceAll("^-+|-+$", "");
		if (slug.isEmpty())
		{
			slug = "sample";
		}
		return "https://" + slug + ".sample-file.local/data";
	}

	// -------------------------------------------------------------------------------------------- source system code

	/** {@code WizardRules.suggestSourceSystemCode} applied to {@link #baseName(String)} - the person can still edit it before submitting (task brief). */
	public static String sourceSystemCode(final String pFileName)
	{
		return WizardRules.suggestSourceSystemCode(SampleFileRules.baseName(pFileName));
	}

	// ---------------------------------------------------------------------------------------------------- data URL

	public static String dataUrl(final String pMime, final String pFileName, final String pBase64Payload)
	{
		return "data:" + pMime + ";name=" + SampleFileRules.encode(pFileName) + ";base64," + (pBase64Payload == null ? "" : pBase64Payload);
	}

	/** One parsed {@code data:} URL - {@link #valid} false for anything that does not match the contract, never a thrown exception. */
	public static final class ParsedDataUrl
	{
		public final boolean valid;
		public final String mime;
		public final String fileName;
		public final String payload;

		private ParsedDataUrl(final boolean pValid, final String pMime, final String pFileName, final String pPayload)
		{
			this.valid = pValid;
			this.mime = pMime;
			this.fileName = pFileName;
			this.payload = pPayload;
		}

		private static ParsedDataUrl invalid()
		{
			return new ParsedDataUrl(false, null, null, null);
		}
	}

	public static ParsedDataUrl parseDataUrl(final String pDataUrl)
	{
		if (pDataUrl == null)
		{
			return ParsedDataUrl.invalid();
		}
		final Matcher matcher = SampleFileRules.DATA_URL_PATTERN.matcher(pDataUrl);
		if (!matcher.matches())
		{
			return ParsedDataUrl.invalid();
		}
		final String name;
		try
		{
			name = URLDecoder.decode(matcher.group(2), "UTF-8");
		}
		catch (final UnsupportedEncodingException ex)
		{
			// UTF-8 is always supported - unreachable in practice, same defence as WizardPage.connectorRecordUrl
			return ParsedDataUrl.invalid();
		}
		catch (final IllegalArgumentException ex)
		{
			// a malformed percent-escape in the name parameter: the URL itself is not trustworthy
			return ParsedDataUrl.invalid();
		}
		return new ParsedDataUrl(true, matcher.group(1), name, matcher.group(3));
	}

	private static String encode(final String pValue)
	{
		try
		{
			// URLEncoder is the x-www-form-urlencoded flavour (space -> '+'); the browser's encodeURIComponent
			// (space -> '%20') is what the client side of this contract actually uses, so '+' is rewritten to
			// keep both sides decoding to the identical original file name
			return URLEncoder.encode(pValue == null ? "" : pValue, "UTF-8").replace("+", "%20");
		}
		catch (final UnsupportedEncodingException ex)
		{
			return "";
		}
	}

	// ----------------------------------------------------------------------------------------------------- report

	/**
	 * One row of the "Sources from sample files" panel's report - {@code ebx.SampleSourceImporter}'s own output
	 * for one file, kept HERE (a pure class, not in that EBX-bound one) so {@link WizardPage} can render it
	 * without importing EBX - the same "pure DTO in the wizard package, filled by the ebx package" split
	 * {@link SourceOnboarding.ConnectionOption} already follows for the sibling onboarding panel.
	 */
	public static final class ReportRow
	{
		public final String name;
		public final String connectionId;
		/** The catalog source id ({@code SourceOnboarding.Diagnosis#pickSourceId}) - empty until the onboarding chain reaches {@code ready}. */
		public final String sourceId;
		/** Null only when a failure happened before any onboarding read was possible at all. */
		public final SourceOnboarding.Diagnosis diagnosis;
		public final boolean applicationAdded;
		/** The {@code sourceSystem} code of the application already using this catalog source - empty when {@link #applicationAdded} is true or the chain never reached {@code ready}. */
		public final String existingApplication;
		/** Null when the file went through cleanly (whether or not the application was actually added). */
		public final String error;
		/**
		 * Sources-landing lot SOURCES (2026-09-24): true when the file was written as a connection but the
		 * catalog+scan chain was only QUEUED into {@code ebx.SourcesRun}, never run synchronously - the wizard's
		 * new "applications in progress" cards (fed by {@code SourcesRun.status}) carry the real progress from
		 * here on; {@link #diagnosis} is null in this case (no diagnosis was read yet), same as a row that
		 * failed before any onboarding read - the two are told apart by {@link #error} being null here.
		 */
		public final boolean queued;

		public ReportRow(final String pName, final String pConnectionId, final String pSourceId, final SourceOnboarding.Diagnosis pDiagnosis, final boolean pApplicationAdded,
			final String pExistingApplication, final String pError)
		{
			this(pName, pConnectionId, pSourceId, pDiagnosis, pApplicationAdded, pExistingApplication, pError, false);
		}

		public ReportRow(final String pName, final String pConnectionId, final String pSourceId, final SourceOnboarding.Diagnosis pDiagnosis, final boolean pApplicationAdded,
			final String pExistingApplication, final String pError, final boolean pQueued)
		{
			this.name = pName;
			this.connectionId = pConnectionId;
			this.sourceId = pSourceId == null ? "" : pSourceId;
			this.diagnosis = pDiagnosis;
			this.applicationAdded = pApplicationAdded;
			this.existingApplication = pExistingApplication == null ? "" : pExistingApplication;
			this.error = pError;
			this.queued = pQueued;
		}

		public static ReportRow failed(final String pName, final String pConnectionId, final String pError)
		{
			return new ReportRow(pName, pConnectionId, "", null, false, "", pError);
		}

		/** sources-landing lot SOURCES (2026-09-24): the file was written, its onboarding chain queued into {@code ebx.SourcesRun} - see this class's own Javadoc. */
		public static ReportRow queued(final String pName, final String pConnectionId)
		{
			return new ReportRow(pName, pConnectionId, "", null, false, "", null, true);
		}
	}

	// --------------------------------------------------------------------------------------------------- raw size

	/**
	 * The decoded (pre-base64) byte count of {@code pBase64Payload}, without actually decoding it - assumes a
	 * well-formed base64 string (length a multiple of 4, {@code =} padding only at the end), which is exactly
	 * what {@link #parseDataUrl} hands this method.
	 */
	/**
	 * Landing test fixes (2026-10-17): the trust rank proposed for a dropped file - the smallest positive rank not taken by an application of the project nor by another row
	 * of the drop table (the table used to propose 1, 2, 3 whatever the project held: re-dropped files all got rank 1 although it was taken). {@code wizard.js} mirrors it.
	 */
	public static int nextFreeRank(final java.util.Collection<Integer> pTaken)
	{
		int rank = 1;
		while (pTaken != null && pTaken.contains(Integer.valueOf(rank)))
		{
			rank++;
		}
		return rank;
	}

	/** The ranks the project's applications already hold, ascending and comma-separated ({@code 1,2,3}), for the drop zone's {@code data-mdmf-taken-ranks}. */
	public static String takenRanksText(final java.util.Collection<Integer> pRanks)
	{
		final java.util.TreeSet<Integer> sorted = new java.util.TreeSet<Integer>();
		if (pRanks != null)
		{
			for (final Integer rank : pRanks)
			{
				if (rank != null && rank.intValue() > 0 && rank.intValue() < 100)
				{
					sorted.add(rank);
				}
			}
		}
		final StringBuilder text = new StringBuilder();
		for (final Integer rank : sorted)
		{
			text.append(text.length() == 0 ? "" : ",").append(rank);
		}
		return text.toString();
	}

	public static long rawBytes(final String pBase64Payload)
	{
		if (pBase64Payload == null || pBase64Payload.isEmpty())
		{
			return 0L;
		}
		final int length = pBase64Payload.length();
		int padding = 0;
		if (pBase64Payload.endsWith("=="))
		{
			padding = 2;
		}
		else if (pBase64Payload.endsWith("="))
		{
			padding = 1;
		}
		return (length / 4L) * 3L - padding;
	}
}
