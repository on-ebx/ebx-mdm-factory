package com.on.ebx.factory.ebx;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import com.on.ebx.factory.matching.AddonLogWindow;

/**
 * Lot MT (2026-10-04): reads the tail of {@code ebx-addon-mame.log} to give a failed matching process its reason - the pure selection is
 * {@link AddonLogWindow}, this class only finds and reads the file. Best-effort by design: it never throws, and answers {@code null}
 * (the caller then writes "see ebx-addon-mame.log") when the directory cannot be resolved or the file cannot be read.
 * <p>
 * The logs directory is resolved the way EBX itself does: the JVM option {@code ebx.logs.directory} if set, else the same property read
 * from the file named by the JVM option {@code -Debx.properties} (the launcher sets it, {@code ebx.sh}), with {@code ${ebx.home}} replaced
 * by the JVM option {@code -Debx.home}, else {@code <ebx.home>/ebxLog}. No EBX API gives it (checked by {@code javap} on {@code ebx-lib.jar}
 * 6.3.0-rc2: nothing on {@code Repository}), and nothing in this code base read it before this lot.
 */
final class AddonLog
{
	/** Only the end of the file is read: a run's ERROR lines are at the end, and the log of a busy day is large. */
	private static final long TAIL_BYTES = 4L * 1024L * 1024L;

	private AddonLog()
	{
	}

	/** The directory EBX writes its logs to, or {@code null} when it cannot be determined. */
	static File logDirectory()
	{
		final String home = System.getProperty("ebx.home");
		String logsDirectory = System.getProperty("ebx.logs.directory");
		if (logsDirectory == null || logsDirectory.trim().isEmpty())
		{
			final String propertiesPath = System.getProperty("ebx.properties");
			if (propertiesPath != null && !propertiesPath.trim().isEmpty())
			{
				final Properties properties = new Properties();
				try (InputStream in = new FileInputStream(propertiesPath.trim()))
				{
					properties.load(in);
					logsDirectory = properties.getProperty("ebx.logs.directory");
				}
				catch (final IOException | RuntimeException ex)
				{
					logsDirectory = null;
				}
			}
		}
		return AddonLogWindow.resolveLogDirectory(logsDirectory, home);
	}

	/** The add-on's log file, or {@code null} when the directory is unknown. */
	static File logFile()
	{
		final File directory = AddonLog.logDirectory();
		return directory == null ? null : new File(directory, AddonLogWindow.LOG_FILE_NAME);
	}

	/**
	 * The reason of a failed process started at {@code pStartEpochMs}: the ERROR lines written since, capped. {@code null} when the log
	 * cannot be found or read, or holds no ERROR line for the window.
	 */
	static String reasonSince(final long pStartEpochMs)
	{
		final File file = AddonLog.logFile();
		if (file == null || !file.isFile())
		{
			return null;
		}
		final List<String> lines = AddonLog.tail(file);
		if (lines == null)
		{
			return null;
		}
		return AddonLogWindow.reasonText(AddonLogWindow.errorMessages(lines, pStartEpochMs, ZoneId.systemDefault()), AddonLogWindow.REASON_MAX_CHARS);
	}

	/** What the step message says when no reason could be quoted: the log's full path when known, else its name. */
	static String logHint()
	{
		final File file = AddonLog.logFile();
		return file == null ? AddonLogWindow.LOG_FILE_NAME : file.getPath();
	}

	private static List<String> tail(final File pFile)
	{
		try (RandomAccessFile in = new RandomAccessFile(pFile, "r"))
		{
			final long length = in.length();
			final long start = Math.max(0L, length - AddonLog.TAIL_BYTES);
			in.seek(start);
			final byte[] bytes = new byte[(int) (length - start)];
			in.readFully(bytes);
			final String text = new String(bytes, StandardCharsets.UTF_8);
			final List<String> lines = new ArrayList<String>(Arrays.asList(text.split("\\r?\\n")));
			if (start > 0 && !lines.isEmpty())
			{
				lines.remove(0); // a cut line
			}
			return lines;
		}
		catch (final IOException | RuntimeException ex)
		{
			return null;
		}
	}
}
