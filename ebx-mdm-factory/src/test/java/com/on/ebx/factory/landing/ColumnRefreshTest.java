package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JUnit-free: run the main, a failure throws. Landing test fixes (2026-10-17, defect 3): the rescan of a table planned and not generated. */
public final class ColumnRefreshTest
{
	public static void main(final String[] pArgs)
	{
		// Fidelite_Membres as planned from the bad first scan: one merged column
		final List<ColumnRefresh.Existing> one = Arrays.asList(new ColumnRefresh.Existing("card_number,civilite,nom", "card_number_civilite_nom", false));
		final List<String> now = Arrays.asList("card_number", "civilite", "nom", "prenom", "email", "points", "ville", "pays", "tel", "date_adhesion", "statut", "canal", "optin");
		ColumnRefresh.Diff diff = ColumnRefresh.diff(one, now);
		ColumnRefreshTest.check("13 columns now, 1 before", diff.changed() && diff.columnsNow == 13 && diff.columnsBefore == 1 && diff.added.size() == 13 && diff.removed.size() == 1);
		ColumnRefreshTest.check("the summary says it", ColumnRefresh.summary("Fidelite_Membres", diff).startsWith("Fidelite_Membres: 13 columns now (was 1)"));
		ColumnRefreshTest.check("no key, none dropped", diff.keyBefore.isEmpty() && !diff.keyDropped);

		// same columns: nothing to do
		final List<ColumnRefresh.Existing> same = Arrays.asList(new ColumnRefresh.Existing("id", "id", true), new ColumnRefresh.Existing("name", "name", false));
		ColumnRefreshTest.check("unchanged", !ColumnRefresh.diff(same, Arrays.asList("name", "id")).changed());

		// a key whose columns remain is kept (a column added elsewhere)
		diff = ColumnRefresh.diff(same, Arrays.asList("id", "name", "city"));
		ColumnRefreshTest.check("key kept when its columns remain", diff.changed() && diff.added.equals(Arrays.asList("city")) && diff.removed.isEmpty() && !diff.keyDropped && diff.keyBefore.equals(Arrays.asList("id")));

		// a key whose column disappeared is dropped, and said
		diff = ColumnRefresh.diff(same, Arrays.asList("name", "email"));
		ColumnRefreshTest.check("key dropped when its column is gone", diff.keyDropped && diff.removed.equals(Arrays.asList("id")) && diff.added.equals(Arrays.asList("email")));
		ColumnRefreshTest.check("and the summary tells", ColumnRefresh.summary("T", diff).contains("the key id was dropped"));

		// a composite key loses a part: the whole key is dropped
		final List<ColumnRefresh.Existing> composite = Arrays.asList(new ColumnRefresh.Existing("a", "a", true), new ColumnRefresh.Existing("b", "b", true), new ColumnRefresh.Existing("c", "c", false));
		diff = ColumnRefresh.diff(composite, Arrays.asList("a", "c"));
		ColumnRefreshTest.check("a composite key minus a part is dropped whole", diff.keyDropped && diff.keyBefore.equals(Arrays.asList("a", "b")));

		// a renamed column is a removed and an added one
		diff = ColumnRefresh.diff(same, Arrays.asList("id", "full_name"));
		ColumnRefreshTest.check("renamed = removed + added", diff.removed.equals(Arrays.asList("name")) && diff.added.equals(Arrays.asList("full_name")) && !diff.keyDropped);

		// field names for added columns never collide with the ones kept, nor with each other
		final List<String> fields = ColumnRefresh.newFieldNames(Arrays.asList("id", "Name"), Arrays.asList("name", "e-mail", "E_MAIL", "3d"));
		ColumnRefreshTest.check("distinct field names " + fields, fields.equals(Arrays.asList("name_2", "e_mail", "E_MAIL_2", "c_3d")));
		final List<String> none = new ArrayList<String>();
		ColumnRefreshTest.check("nothing added, nothing named", ColumnRefresh.newFieldNames(none, none).isEmpty());
		System.out.println("ColumnRefreshTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
