package com.on.ebx.factory.backup;

/**
 * The archive of {@code GET /projects/{code}/backup} (audit F-30, wave 10): ONE JSON document, written and read as a stream, {@code format} and
 * {@code formatVersion} first so a reader can refuse an archive it does not understand before reading a row.
 * <pre>
 * { "format": "mdm-factory-project-backup", "formatVersion": 1, "project": "&lt;code&gt;", "createdAt": "...", "createdBy": "...", "module": {...},
 *   "tables": [ { "table": "Project", "path": "/root/Project", "rows": [ { "key": "&lt;formatted primary key&gt;", "values": { "field": value, ... } } ] }, ... ],
 *   "rowCounts": { "Project": 1, ... }, "references": [ { "kind": "...", "name": "...", "detail": "..." } ], "notIncluded": [ "..." ] }
 * </pre>
 * Version 1 is the first; a change that an older reader would misread raises the version, a new optional piece of information does not. Pure.
 */
public final class BackupFormat
{
	public static final String FORMAT = "mdm-factory-project-backup";
	public static final int VERSION = 1;

	private BackupFormat()
	{
	}

	/**
	 * The refusal for a header that this build cannot restore, null when it can. {@code pVersion} is null when the field is missing.
	 */
	public static String refuse(final String pFormat, final Integer pVersion)
	{
		if (!BackupFormat.FORMAT.equals(pFormat))
		{
			return "This is not a project backup of the MDM Factory (the format is '" + pFormat + "', expected '" + BackupFormat.FORMAT + "').";
		}
		if (pVersion == null)
		{
			return "The backup has no formatVersion.";
		}
		if (pVersion.intValue() > BackupFormat.VERSION)
		{
			return "The backup was written by a newer MDM Factory (format version " + pVersion + ", this build reads up to " + BackupFormat.VERSION + "): upgrade the module first.";
		}
		if (pVersion.intValue() < 1)
		{
			return "The backup's formatVersion " + pVersion + " is not valid.";
		}
		return null;
	}

	/** True for a project code that can name a project here (the restore refuses anything else rather than writing it into a key). */
	public static boolean validProjectCode(final String pCode)
	{
		return pCode != null && !pCode.isEmpty() && pCode.length() <= 40 && pCode.matches("[A-Za-z][A-Za-z0-9_]*");
	}

	/** The download name: {@code project-backup-<code>-<yyyyMMdd-HHmmss>.json}, the code reduced to letters, digits, dot, underscore and dash. */
	public static String fileName(final String pProjectCode, final java.util.Date pNow)
	{
		final String code = (pProjectCode == null ? "" : pProjectCode).replaceAll("[^A-Za-z0-9._-]", "_");
		return "project-backup-" + (code.isEmpty() ? "project" : code) + "-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(pNow) + ".json";
	}
}
