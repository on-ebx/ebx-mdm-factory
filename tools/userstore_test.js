// Offline test of userstore.js (security lot, wave 13), run by tools/test_local.sh with macOS's JavaScriptCore:
//   osascript -l JavaScript tools/userstore_test.js <path to userstore.js>
// Browser storage of a Factory screen is keyed by the EBX user: key composition, the purge of every other user's keys (and of the legacy, user-less keys of the
// earlier versions) in both areas, the hand-over read-and-delete, an inert store without a user, and storage that throws. A failure throws, success returns "userstore_test OK".
ObjC.import('Foundation');

function run(argv) {
	var src = $.NSString.stringWithContentsOfFileEncodingError(argv[0], $.NSUTF8StringEncoding, null).js;
	function check(what, ok) { if (!ok) throw new Error("FAILED: " + what); }

	function area(m, broken) {
		function guard() { if (broken) throw new Error("blocked"); }
		return { getItem: function (k) { guard(); return k in m ? m[k] : null; }, setItem: function (k, v) { guard(); m[k] = String(v); }, removeItem: function (k) { guard(); delete m[k]; },
			key: function (i) { guard(); return Object.keys(m)[i]; }, get length() { guard(); return Object.keys(m).length; } };
	}
	function world(broken) {
		var ss = {}, ls = {};
		var win = { sessionStorage: area(ss, broken), localStorage: area(ls, broken) };
		new Function("window", src)(win);
		return { win: win, ss: ss, ls: ls };
	}
	function names(m) { return Object.keys(m).sort().join(","); }

	// 1. keys are mdmf:<user>:<name>, the user URL-encoded
	var w = world(false);
	check("prefix", w.win.mdmfStore.prefix("alice") === "mdmf:alice:");
	check("key", w.win.mdmfStore.key("alice", "origin") === "mdmf:alice:origin");
	check("a colon in a login cannot make two users share a prefix", w.win.mdmfStore.prefix("a:b") === "mdmf:a%3Ab:" && w.win.mdmfStore.prefix("a:b").indexOf(w.win.mdmfStore.prefix("a")) !== 0);

	// 2. a store reads and writes under the user's own key, in the right area
	var alice = w.win.mdmfStore.forUser("alice");
	alice.session.set("origin", "{\"url\":\"u\"}");
	alice.local.set("rail", "0");
	check("session key", names(w.ss) === "mdmf:alice:origin" && w.ss["mdmf:alice:origin"] === "{\"url\":\"u\"}");
	check("local key", names(w.ls) === "mdmf:alice:rail");
	check("get", alice.session.get("origin") === "{\"url\":\"u\"}" && alice.session.get("missing") === null);
	alice.session.remove("origin");
	check("remove", names(w.ss) === "");

	// 3. take = read then delete at once (a value handed from one page to the next)
	alice.session.set("scrollTop", "120");
	check("take reads", alice.session.take("scrollTop") === "120");
	check("take deletes", alice.session.get("scrollTop") === null && names(w.ss) === "");
	check("take of nothing is null", alice.session.take("scrollTop") === null);

	// 4. the next user in the same tab/browser never reads the previous user's values, and the previous user's keys are deleted when the store opens
	alice.session.set("origin", "alice-origin");
	alice.session.set("wizardOrigin", "alice-wizard");
	alice.local.set("moreOpen", "1");
	alice.local.set("jobNoticesShown", "{\"alice:P1:1\":1}");
	var bob = w.win.mdmfStore.forUser("bob");
	check("bob reads none of alice's values", bob.session.get("origin") === null && bob.local.get("moreOpen") === null && bob.session.take("origin") === null);
	check("alice's keys are gone from both areas: " + names(w.ss) + " / " + names(w.ls), names(w.ss) === "" && names(w.ls) === "");
	check("the purge counted them", bob.purged === 5);
	bob.session.set("origin", "bob-origin");
	check("alice, back, finds nothing of bob and removes his keys", w.win.mdmfStore.forUser("alice").session.get("origin") === null && names(w.ss) === "");

	// 5. a login that is a prefix of another is not confused with it
	var w2 = world(false);
	w2.win.mdmfStore.forUser("ann").session.set("x", "1");
	var anna = w2.win.mdmfStore.forUser("anna");
	check("ann's key is not anna's", anna.session.get("x") === null && names(w2.ss) === "");
	anna.session.set("x", "2");
	check("anna's own key stays when she reopens", w2.win.mdmfStore.forUser("anna").session.get("x") === "2");
	w2.win.mdmfStore.forUser("a:b").session.set("y", "1");
	check("a login with a colon does not keep (or read) the one it starts with", w2.win.mdmfStore.forUser("a").session.get("y") === null && names(w2.ss) === "");

	// 6. the keys of the earlier versions (no user) are purged at the first opening; keys that are not ours are left alone
	var w3 = world(false);
	["mdmfOrigin", "mdmfWizardOrigin", "mdmfScrollAnchor", "mdmfScrollTop", "mdmfScrollBlock", "mdmfChecking"].forEach(function (k) { w3.ss[k] = "old"; });
	["mdmxRail", "mdmxMoreOpen", "mdmfJobNoticesShown"].forEach(function (k) { w3.ls[k] = "old"; });
	w3.ss.ftccRecordOrigin = "other module"; w3.ls.somethingElse = "x";
	w3.win.mdmfStore.forUser("alice");
	check("legacy session keys purged, foreign ones kept: " + names(w3.ss), names(w3.ss) === "ftccRecordOrigin");
	check("legacy local keys purged, foreign ones kept: " + names(w3.ls), names(w3.ls) === "somethingElse");

	// 7. without a user the store is inert and does not purge: nothing is stored, nothing is read
	var w4 = world(false);
	w4.win.mdmfStore.forUser("alice").session.set("origin", "v");
	["", null, undefined].forEach(function (nobody) {
		var s = w4.win.mdmfStore.forUser(nobody);
		check("inert: set refused", s.session.set("origin", "x") === false && s.local.set("rail", "1") === false);
		check("inert: reads null", s.session.get("origin") === null && s.local.take("rail") === null);
	});
	check("inert stores wrote nothing and removed nothing: " + names(w4.ss), names(w4.ss) === "mdmf:alice:origin" && names(w4.ls) === "");

	// 8. blocked storage never throws
	var w5 = world(true);
	var blocked = w5.win.mdmfStore.forUser("alice");
	check("blocked: set false, get null, take null", blocked.session.set("a", "1") === false && blocked.session.get("a") === null && blocked.local.take("a") === null && blocked.session.remove("a") === false);

	// 9. the library defines itself once
	var first = w.win.mdmfStore;
	new Function("window", src)(w.win);
	check("idempotent", w.win.mdmfStore === first);

	// 10. the scripts the Java side writes (FactoryHtml, dumped by UserStorageKeysTest into argv[1]): one login never meets the other's return address
	if (argv[1]) {
		function dumped(name) { return $.NSString.stringWithContentsOfFileEncodingError(argv[1] + "/" + name, $.NSUTF8StringEncoding, null).js; }
		function page(win, name) {
			var anchors = [];
			var doc = { querySelectorAll: function () { return anchors; }, getElementById: function () { return null; }, documentElement: { dataset: {} } };
			var a = { addEventListener: function (n, f) { a.click = f; } };
			anchors.push(a);
			win.top = { location: { href: "https://h/ebx-ui/ui/custom/p/data/BX/D/root/Project?of=" + name } };
			new Function("window", "document", dumped(name))(win, doc);
			return a;
		}
		var w6 = world(false);
		var aliceLink = page(w6.win, "origin-capture-alice.js");
		aliceLink.click();
		check("alice's click records her address under her key: " + names(w6.ss), names(w6.ss) === "mdmf:alice:origin" && /of=origin-capture-alice/.test(w6.ss["mdmf:alice:origin"]));
		// the next login in the same tab opens an explorer: alice's token is deleted, never meets bob
		page(w6.win, "origin-capture-bob.js");
		check("bob's explorer purged alice's token: " + names(w6.ss), names(w6.ss) === "");
		// the wizard keeps the token across its own postbacks, per user
		var w7 = world(false);
		page(w7.win, "origin-capture-alice.js").click();
		new Function("window", "document", dumped("origin-keep-alice.js"))(w7.win, {});
		check("alice's wizard keeps the origin under her key: " + names(w7.ss), names(w7.ss) === "mdmf:alice:origin,mdmf:alice:wizardOrigin");
		new Function("window", "document", dumped("origin-keep-bob.js"))(w7.win, {});
		check("bob's wizard finds and keeps nothing of alice's: " + names(w7.ss), names(w7.ss) === "");
	}
	// 11. the transfer-reject screen's "back" link (staging load rejects, 2026-10-17): points where the screen was opened from, like its Close button, and gives the token back
	if (argv[1]) {
		function dumpedBack(name) { return $.NSString.stringWithContentsOfFileEncodingError(argv[1] + "/" + name, $.NSUTF8StringEncoding, null).js; }
		function backLink(win, user, hasLink) {
			var attrs = { href: "/cockpit", "data-label-wizard": "Back to the wizard", "data-label-back": "Back" };
			var link = { text: "Back to the Cockpit", click: null, getAttribute: function (n) { return n in attrs ? attrs[n] : null; }, setAttribute: function (n, v) { attrs[n] = v; },
				addEventListener: function (n, f) { link.click = f; } };
			Object.defineProperty(link, "textContent", { get: function () { return link.text; }, set: function (v) { link.text = v; } });
			var doc = { querySelector: function (sel) { return hasLink === false ? null : link; } };
			// JavaScriptCore (osascript) has no URL class: a stand-in that resolves a relative address against the base and reads origin, pathname and href
			function URLStub(u, base) {
				var abs = /^https?:\/\//.test(u) ? u : base + u;
				var m = /^(https?:\/\/[^\/?#]+)([^?#]*)(.*)$/.exec(abs);
				if (!m) throw new Error("bad URL");
				this.origin = m[1]; this.pathname = m[2] || "/"; this.href = abs;
			}
			new Function("window", "document", "URL", dumpedBack("origin-back-" + user + ".js"))(win, doc, URLStub);
			link.attrs = attrs;
			return link;
		}
		var host = "https://h";
		var wizardUrl = host + "/ebx-ui/ui/custom/p/data/BX/D/root/Project?service=ProjectWizard&serviceMode=TABLE_VIEW&serviceParams=abc";
		function backWorld(token, topHref) {
			var bw = world(false);
			bw.win.top = { location: { href: host + "/ebx-ui/ui/custom/p/data/BX/D/root/Project", origin: host } };
			if (token) bw.win.mdmfStore.forUser("alice").session.set("origin", JSON.stringify(token));
			return bw;
		}
		// opened from the wizard: the link goes back there, worded for the wizard, and a click clears the token
		var b1 = backWorld({ url: wizardUrl });
		var l1 = backLink(b1.win, "alice");
		check("from the wizard: the link points at the wizard step: " + l1.attrs.href, l1.attrs.href === wizardUrl);
		check("from the wizard: the link says so: " + l1.text, l1.text === "Back to the wizard");
		l1.click();
		check("a click without a parent clears the token: " + names(b1.ss), names(b1.ss) === "");
		// opened from the wizard that was itself opened from the explorer: the click gives the explorer's token back
		var explorer = host + "/ebx-ui/ui/custom/p/data/BX/D/root/Project?explorer=1";
		var b2 = backWorld({ url: wizardUrl, parent: { url: explorer } });
		var l2 = backLink(b2.win, "alice");
		l2.click();
		check("a click with a parent hands the parent's token back: " + b2.ss["mdmf:alice:origin"], JSON.parse(b2.ss["mdmf:alice:origin"]).url === explorer);
		// opened from the Cockpit: the link goes there and keeps its own wording
		var b3 = backWorld({ url: host + "/ebx-ui/ui/custom/p/data/BX/D/root/Project?service=FactoryCockpit&serviceMode=RECORD" });
		var l3 = backLink(b3.win, "alice");
		check("from the cockpit: pointed there, wording kept: " + l3.text, /FactoryCockpit/.test(l3.attrs.href) && l3.text === "Back to the Cockpit");
		// any other origin of this EBX: a plain "Back"
		var b4 = backWorld({ url: explorer });
		check("from another screen: Back: ", backLink(b4.win, "alice").text === "Back");
		// no token, a token of another origin, a token that is not an ebx-ui address, a broken token: the link is left as rendered
		[null, { url: "https://evil.example/ebx-ui/x" }, { url: host + "/somewhere/else" }, { url: "" }].forEach(function (token, i) {
			var b = backWorld(token);
			var l = backLink(b.win, "alice");
			check("untouched #" + i + ": " + l.attrs.href + " / " + l.text, l.attrs.href === "/cockpit" && l.text === "Back to the Cockpit" && l.click === null);
		});
		var b5 = backWorld(null);
		b5.ss["mdmf:alice:origin"] = "{not json";
		check("a broken token leaves the link alone", backLink(b5.win, "alice").attrs.href === "/cockpit");
		// no link on the page: nothing happens
		check("no link, no error", backLink(backWorld({ url: wizardUrl }).win, "alice", false).click === null);
		// bob never follows alice's token
		var b6 = backWorld({ url: wizardUrl });
		var lb = backLink(b6.win, "bob");
		check("another login does not follow alice's token", lb.attrs.href === "/cockpit" && lb.click === null);
	}
	return "userstore_test OK";
}
