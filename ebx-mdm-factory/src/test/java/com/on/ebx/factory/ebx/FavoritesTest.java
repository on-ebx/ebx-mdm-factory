package com.on.ebx.factory.ebx;

/**
 * Guards what the favorite star trusts from the client: the kind, the key, the per-user limit and the JSON it
 * answers. Ported from ebx-connectors' {@code FavoritesTest} of the same name (2026-09-23) - the checks are
 * identical, only {@link Favorites#PROJECT} replaces that module's {@code CONNECTOR} kind.
 */
public final class FavoritesTest
{
	private FavoritesTest()
	{
	}

	public static void main(final String[] pArgs)
	{
		check(Favorites.validType(Favorites.PROJECT), "PROJECT is the one kind of favorite");
		check(!Favorites.validType("CONNECTOR") && !Favorites.validType(null) && !Favorites.validType(""), "an unknown kind must be refused");
		check(Favorites.validKey("Companies") && !Favorites.validKey(" ") && !Favorites.validKey(null), "a blank key must be refused");
		final StringBuilder longKey = new StringBuilder();
		for (int i = 0; i <= Favorites.MAX_KEY_LENGTH; i++) longKey.append('k');
		check(!Favorites.validKey(longKey.toString()), "a key longer than the field must be refused");
		check(Favorites.withinLimit(Favorites.MAX_PER_USER - 1) && !Favorites.withinLimit(Favorites.MAX_PER_USER), "the per-user limit is not enforced");
		check("./userLogin='o''brien' and ./targetType='PROJECT' and ./targetKey='a''b'".equals(Favorites.predicate("o'brien", Favorites.PROJECT, "a'b")),
			"quotes of a login or a key must be doubled in the predicate");
		check("./userLogin='admin'".equals(Favorites.predicate("admin", null, null)), "a predicate on the login alone reads every kind");
		check("{\"favorite\":true}".equals(Favorites.json(new Favorites.Outcome(true, null), null)), "a successful toggle answers its new state only");
		check("{\"favorite\":false,\"error\":\"say \\\"no\\\"\\u000a\"}".equals(Favorites.json(new Favorites.Outcome(false, Favorites.ERR_FAILED), "say \"no\"\n")),
			"the error text must be escaped for JSON");
		System.out.println("PASS: favorites refuse unknown kinds, blank or oversized keys and a full list, and answer safe JSON");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
