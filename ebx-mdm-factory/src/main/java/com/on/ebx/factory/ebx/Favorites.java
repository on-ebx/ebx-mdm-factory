package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryPaths.Favorite;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Per-user favorites: a shortcut to a project, kept in the {@code Favorite} table. Ported line-for-line from
 * ebx-connectors' class of the same name (2026-09-23), at the product owner's request that the project explorer be
 * "consistent" with the connector explorer - only the one kind ({@link #PROJECT}, target table {@code
 * /root/Project}) and the paths ({@link FactoryPaths}) are the factory's own; every check, the JSON shape and
 * the dangling-favorite cleanup are unchanged. The rows are written here only (with all privileges, the table
 * being read-only for everybody - see {@link FavoriteAccessRule}), and every read is filtered on the login of
 * the session, so a user never sees somebody else's shortcuts. A favorite gives no access: the target is
 * always resolved by the caller with the caller's own rights.
 */
public final class Favorites
{
	public static final int MAX_PER_USER = 200;
	public static final String PROJECT = "PROJECT";
	public static final String ERR_LIMIT = "LIMIT";
	public static final String ERR_UNKNOWN_TARGET = "UNKNOWN_TARGET";
	public static final String ERR_BAD_REQUEST = "BAD_REQUEST";
	public static final String ERR_FAILED = "FAILED";
	static final int MAX_KEY_LENGTH = 255;

	private Favorites()
	{
	}

	/** The result of a toggle: the new state, or an error code (state unchanged). */
	public static final class Outcome
	{
		public final boolean favorite;
		public final String error;

		Outcome(final boolean pFavorite, final String pError)
		{
			this.favorite = pFavorite;
			this.error = pError;
		}
	}

	/**
	 * A toggle refused before reaching the table (2026-10-14, EBX permissions: a pin asked on a project the user's session does not return answers
	 * exactly as an unknown project, so the answer tells nothing about invisible records).
	 */
	public static Outcome refused(final String pError)
	{
		return new Outcome(false, pError);
	}

	public static boolean validType(final String pType)
	{
		return PROJECT.equals(pType);
	}

	static Path tableOf(final String pType)
	{
		return FactoryPaths.PROJECT;
	}

	/** The EBX login of the session, or null (no user: nothing is ever read or written). */
	public static String login(final Session pSession)
	{
		if (pSession == null || pSession.getUserReference() == null) return null;
		final String id = pSession.getUserReference().getUserId();
		return id == null || id.trim().isEmpty() ? null : id;
	}

	public static boolean withinLimit(final int pCurrentCount)
	{
		return pCurrentCount < MAX_PER_USER;
	}

	static boolean validKey(final String pKey)
	{
		return pKey != null && !pKey.trim().isEmpty() && pKey.length() <= MAX_KEY_LENGTH;
	}

	static String quote(final String pValue)
	{
		return "'" + pValue.replace("'", "''") + "'";
	}

	static String predicate(final String pLogin, final String pType, final String pKey)
	{
		final StringBuilder sb = new StringBuilder("./userLogin=").append(quote(pLogin));
		if (pType != null) sb.append(" and ./targetType=").append(quote(pType));
		if (pKey != null) sb.append(" and ./targetKey=").append(quote(pKey));
		return sb.toString();
	}

	/** The favorites of a login, newest first (all kinds when {@code pType} is null). */
	public static List<Adaptation> rows(final Adaptation pDataset, final String pLogin, final String pType)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		if (pDataset == null || pLogin == null) return out;
		final AdaptationTable table;
		try
		{
			// getTable throws on a model loaded before the table existed: the explorer must still open, without stars pinned
			table = pDataset.getTable(FactoryPaths.FAVORITE);
		}
		catch (final RuntimeException notInThisModel)
		{
			return out;
		}
		if (table == null) return out;
		final RequestResult result = table.createRequestResult(predicate(pLogin, pType, null));
		try
		{
			for (Adaptation row = result.nextAdaptation(); row != null; row = result.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			result.close();
		}
		Collections.sort(out, new Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation a, final Adaptation b)
			{
				final Object x = a.get(Favorite.CREATED_AT), y = b.get(Favorite.CREATED_AT);
				if (x instanceof Date && y instanceof Date) return ((Date) y).compareTo((Date) x);
				return x instanceof Date ? -1 : y instanceof Date ? 1 : 0;
			}
		});
		return out;
	}

	/** The target keys a login pinned, for one kind. */
	public static Set<String> keys(final Adaptation pDataset, final String pLogin, final String pType)
	{
		final Set<String> keys = new HashSet<String>();
		for (final Adaptation row : rows(pDataset, pLogin, pType)) keys.add(row.getString(Favorite.TARGET_KEY));
		return keys;
	}

	private static Adaptation find(final Adaptation pDataset, final String pLogin, final String pType, final String pKey)
	{
		final RequestResult result = pDataset.getTable(FactoryPaths.FAVORITE).createRequestResult(predicate(pLogin, pType, pKey));
		try
		{
			return result.nextAdaptation();
		}
		finally
		{
			result.close();
		}
	}

	private static boolean targetExists(final Adaptation pDataset, final String pType, final String pKey)
	{
		try
		{
			return pDataset.getTable(tableOf(pType)).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pKey)) != null;
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	/** Adds the favorite if absent, removes it if present. Never trusts the client: type, key and count are checked here. */
	public static Outcome toggle(final Adaptation pDataset, final Session pSession, final String pType, final String pKey)
	{
		final String login = login(pSession);
		if (login == null || !validType(pType) || !validKey(pKey)) return new Outcome(false, ERR_BAD_REQUEST);
		try
		{
			final Adaptation existing = find(pDataset, login, pType, pKey);
			if (existing != null)
			{
				return run(pDataset, pSession, new Procedure()
				{
					@Override
					public void execute(final ProcedureContext pContext) throws Exception
					{
						pContext.setAllPrivileges(true);
						pContext.doDelete(existing.getAdaptationName(), false);
					}
				}, false);
			}
			if (!targetExists(pDataset, pType, pKey)) return new Outcome(false, ERR_UNKNOWN_TARGET);
			if (!withinLimit(rows(pDataset, login, null).size())) return new Outcome(false, ERR_LIMIT);
			return run(pDataset, pSession, new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					final AdaptationTable table = pDataset.getTable(FactoryPaths.FAVORITE);
					final ValueContextForUpdate row = pContext.getContextForNewOccurrence(table);
					row.setValue(login, Favorite.USER_LOGIN);
					row.setValue(pType, Favorite.TARGET_TYPE);
					row.setValue(pKey, Favorite.TARGET_KEY);
					row.setValue(new Date(), Favorite.CREATED_AT);
					pContext.doCreateOccurrence(row, table);
				}
			}, true);
		}
		catch (final RuntimeException ex)
		{
			return new Outcome(false, ERR_FAILED);
		}
	}

	/** Deletes the favorites of this login whose target no longer exists; returns how many were removed. */
	public static int pruneDangling(final Adaptation pDataset, final Session pSession)
	{
		final String login = login(pSession);
		final List<Adaptation> dangling = new ArrayList<Adaptation>();
		for (final Adaptation row : rows(pDataset, login, null))
		{
			if (!targetExists(pDataset, row.getString(Favorite.TARGET_TYPE), row.getString(Favorite.TARGET_KEY))) dangling.add(row);
		}
		if (dangling.isEmpty()) return 0;
		final Outcome outcome = run(pDataset, pSession, new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				for (final Adaptation row : dangling) pContext.doDelete(row.getAdaptationName(), false);
			}
		}, false);
		return outcome.error == null ? dangling.size() : 0;
	}

	private static Outcome run(final Adaptation pDataset, final Session pSession, final Procedure pProcedure, final boolean pResult)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(pProcedure);
		return result.hasFailed() ? new Outcome(!pResult, ERR_FAILED) : new Outcome(pResult, null);
	}

	/** The answer of the star's AJAX call. {@code pErrorText} is the already localized message of an error. */
	public static String json(final Outcome pOutcome, final String pErrorText)
	{
		if (pOutcome.error == null) return "{\"favorite\":" + pOutcome.favorite + "}";
		return "{\"favorite\":" + pOutcome.favorite + ",\"error\":\"" + escapeJson(pErrorText == null ? pOutcome.error : pErrorText) + "\"}";
	}

	static String escapeJson(final String pText)
	{
		final StringBuilder sb = new StringBuilder();
		for (final char c : pText.toCharArray())
		{
			if (c == '"' || c == '\\') sb.append('\\').append(c);
			else if (c < 0x20) sb.append(String.format("\\u%04x", Integer.valueOf(c)));
			else sb.append(c);
		}
		return sb.toString();
	}
}
