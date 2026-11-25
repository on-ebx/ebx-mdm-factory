package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One preloaded table of a pack: its CSV rows and the CSV-header to field-name map of {@code pack.json}. */
public final class ReferenceTable
{
	private final String path;
	private final String key;
	private final Map<String, String> columns;
	private final List<String> header;
	private final List<Map<String, String>> rows;

	ReferenceTable(final String pPath, final String pKey, final Map<String, String> pColumns, final String pCsv, final String pWhere)
	{
		this.path = pPath;
		this.key = pKey;
		this.columns = Collections.unmodifiableMap(new LinkedHashMap<String, String>(pColumns));
		final List<List<String>> table = ReferenceTable.parseCsv(pCsv, pWhere);
		if (table.isEmpty())
		{
			throw new IllegalStateException("Reference data file " + pWhere + " is empty");
		}
		this.header = Collections.unmodifiableList(table.get(0));
		final List<Map<String, String>> out = new ArrayList<Map<String, String>>();
		for (int i = 1; i < table.size(); i++)
		{
			final List<String> cells = table.get(i);
			if (cells.size() != this.header.size())
			{
				throw new IllegalStateException("Reference data file " + pWhere + ": row " + (i + 1) + " has " + cells.size() + " cells, the header has "
						+ this.header.size());
			}
			final Map<String, String> row = new LinkedHashMap<String, String>();
			for (int c = 0; c < cells.size(); c++)
			{
				row.put(this.header.get(c), cells.get(c));
			}
			out.add(Collections.unmodifiableMap(row));
		}
		this.rows = Collections.unmodifiableList(out);
	}

	/** Table path in the model, e.g. {@code /root/ReferenceData/Country}. */
	public String path()
	{
		return this.path;
	}

	/** Name of the primary-key FIELD (after the column map). */
	public String key()
	{
		return this.key;
	}

	/** CSV header to field name; empty when the CSV headers are the field names. */
	public Map<String, String> columns()
	{
		return this.columns;
	}

	public List<String> header()
	{
		return this.header;
	}

	/** Raw rows, keyed by CSV header. */
	public List<Map<String, String>> rows()
	{
		return this.rows;
	}

	/** The field names the loader writes: mapped columns only when a map is declared, else every header. */
	public List<String> fieldNames()
	{
		final List<String> out = new ArrayList<String>();
		for (final String h : this.header)
		{
			if (this.columns.isEmpty())
			{
				out.add(h);
			}
			else if (this.columns.containsKey(h))
			{
				out.add(this.columns.get(h));
			}
		}
		return out;
	}

	/** Rows keyed by FIELD name (column map applied, unmapped columns dropped when a map is declared). */
	public List<Map<String, String>> records()
	{
		final List<Map<String, String>> out = new ArrayList<Map<String, String>>();
		for (final Map<String, String> raw : this.rows)
		{
			final Map<String, String> rec = new LinkedHashMap<String, String>();
			for (final Map.Entry<String, String> e : raw.entrySet())
			{
				if (this.columns.isEmpty())
				{
					rec.put(e.getKey(), e.getValue());
				}
				else if (this.columns.containsKey(e.getKey()))
				{
					rec.put(this.columns.get(e.getKey()), e.getValue());
				}
			}
			out.add(rec);
		}
		return out;
	}

	/** RFC 4180 subset: comma separator, double-quote quoting with "" escape, quoted line breaks; blank lines skipped. */
	static List<List<String>> parseCsv(final String pText, final String pWhere)
	{
		final List<List<String>> out = new ArrayList<List<String>>();
		List<String> row = new ArrayList<String>();
		final StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		boolean cellStarted = false;
		final int n = pText.length();
		for (int i = 0; i < n; i++)
		{
			final char c = pText.charAt(i);
			if (quoted)
			{
				if (c == '"')
				{
					if (i + 1 < n && pText.charAt(i + 1) == '"')
					{
						cell.append('"');
						i++;
					}
					else
					{
						quoted = false;
					}
				}
				else
				{
					cell.append(c);
				}
			}
			else if (c == '"' && cell.length() == 0)
			{
				quoted = true;
				cellStarted = true;
			}
			else if (c == ',')
			{
				row.add(cell.toString());
				cell.setLength(0);
				cellStarted = true;
			}
			else if (c == '\n' || c == '\r')
			{
				if (c == '\r' && i + 1 < n && pText.charAt(i + 1) == '\n')
				{
					i++;
				}
				if (cellStarted || cell.length() > 0 || !row.isEmpty())
				{
					row.add(cell.toString());
					out.add(row);
				}
				row = new ArrayList<String>();
				cell.setLength(0);
				cellStarted = false;
			}
			else
			{
				cell.append(c);
			}
		}
		if (quoted)
		{
			throw new IllegalStateException("Reference data file " + pWhere + ": unterminated quoted cell");
		}
		if (cellStarted || cell.length() > 0 || !row.isEmpty())
		{
			row.add(cell.toString());
			out.add(row);
		}
		return out;
	}
}
