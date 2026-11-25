/* Browser storage of the Factory screens is keyed by the EBX user (security lot, wave 13).
 *
 * sessionStorage belongs to the browser TAB and survives an EBX logout, localStorage to the whole browser: a key without the user hands one person's
 * values (a return address, a remembered task, a layout choice) to the next one who logs in on the same tab or browser. Every key written by a Factory
 * screen is therefore  mdmf:<user>:<name>  (the user URL-encoded, so ":" in a login cannot make two users share a prefix), and opening a store
 * (mdmfStore.forUser) deletes, in both areas, every key of the module's namespaces (mdmf*, mdmx* - this includes the keys of the versions
 * that did not know the user) that does not belong to that user.
 *
 * var area = window.mdmfStore.forUser(login).session;   // or .local
 *   area.get(name) / area.set(name, value) / area.remove(name)
 *   area.take(name)  - reads then deletes at once: for anything handed from one page to another
 * A store opened with an empty user is inert (reads null, writes nothing): there is no one to key by. Every access is wrapped in try/catch - private mode,
 * blocked storage and a full quota read as "nothing stored", never as an error.
 * The user comes from the server: the Java side writes the login in a data-user attribute of the screen's container (or passes it to the script it writes).
 */
(function () {
	"use strict";
	if (window.mdmfStore) return;

	var NS = "mdmf:";

	function prefix(user) { return NS + encodeURIComponent(String(user)) + ":"; }
	function key(user, name) { return prefix(user) + name; }
	/** A key written by the Factory screens, of any version (mdmf*: origin, scroll, notices; mdmx*: the explorers' layout). */
	function ours(k) { return k.indexOf("mdmf") === 0 || k.indexOf("mdmx") === 0; }

	function purge(area, user) {
		try {
			var own = prefix(user), doomed = [], i, k;
			for (i = 0; i < area.length; i++) {
				k = area.key(i);
				if (k && ours(k) && k.indexOf(own) !== 0) doomed.push(k);
			}
			doomed.forEach(function (d) { area.removeItem(d); });
			return doomed.length;
		} catch (ignored) { return 0; }
	}

	function inert() {
		return { get: function () { return null; }, set: function () { return false; }, remove: function () { return false; }, take: function () { return null; } };
	}

	function wrap(win, areaName, user) {
		function area() { return win[areaName]; }
		return {
			get: function (name) { try { return area().getItem(key(user, name)); } catch (ignored) { return null; } },
			set: function (name, value) { try { area().setItem(key(user, name), String(value)); return true; } catch (ignored) { return false; } },
			remove: function (name) { try { area().removeItem(key(user, name)); return true; } catch (ignored) { return false; } },
			take: function (name) {
				try {
					var k = key(user, name), v = area().getItem(k);
					area().removeItem(k);
					return v;
				} catch (ignored) { return null; }
			}
		};
	}

	/** The stores of one user in one window (default: this one); the keys of every other user are removed from both areas first. */
	function forUser(user, win) {
		var w = win || window;
		var u = user === undefined || user === null ? "" : String(user);
		if (!u) return { user: "", session: inert(), local: inert(), purged: 0 };
		var purged = 0;
		try { purged += purge(w.sessionStorage, u); } catch (ignored) { /* storage not reachable */ }
		try { purged += purge(w.localStorage, u); } catch (ignored2) { /* storage not reachable */ }
		return { user: u, session: wrap(w, "sessionStorage", u), local: wrap(w, "localStorage", u), purged: purged };
	}

	window.mdmfStore = { prefix: prefix, key: key, forUser: forUser };
})();
