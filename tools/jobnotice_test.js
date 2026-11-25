// Offline test of jobnotice.js (audit F-34, wave 10), run by tools/test_local.sh with macOS's JavaScriptCore:
//   osascript -l JavaScript tools/jobnotice_test.js <path to jobnotice.js> [<path to wizard.js> [<folder of dumped scripts>]]
// With the second path, wizard.js is also parsed (a syntax error there would break the whole wizard and no Java test sees it).
// A minimal DOM and a localStorage stand-in (keys enumerable, as the browser's, because userstore.js purges the other users' keys); a failure throws, success returns "jobnotice_test OK".
ObjC.import('Foundation');

function run(argv) {
	var path = argv[0];
	var src = $.NSString.stringWithContentsOfFileEncodingError(path, $.NSUTF8StringEncoding, null).js;
	// wave 13: the per-user storage library (userstore.js, next to jobnotice.js) is written before the notice on every page
	var storeSrc = $.NSString.stringWithContentsOfFileEncodingError(path.replace(/jobnotice[.]js$/, "userstore.js"), $.NSUTF8StringEncoding, null).js;

	function check(what, ok) { if (!ok) throw new Error("FAILED: " + what); }

	function El(tag) {
		this.tag = tag; this.children = []; this.attrs = {}; this.listeners = {}; this.className = ""; this.textContent = ""; this.id = ""; this.parentNode = null;
	}
	El.prototype.appendChild = function (c) { c.parentNode = this; this.children.push(c); return c; };
	El.prototype.removeChild = function (c) { var i = this.children.indexOf(c); if (i >= 0) this.children.splice(i, 1); c.parentNode = null; return c; };
	El.prototype.setAttribute = function (k, v) { this.attrs[k] = String(v); };
	El.prototype.getAttribute = function (k) { return k in this.attrs ? this.attrs[k] : null; };
	El.prototype.addEventListener = function (n, f) { this.listeners[n] = f; };
	El.prototype.scrollIntoView = function () { this.scrolled = true; };
	function find(el, id) {
		if (el.id === id) return el;
		for (var i = 0; i < el.children.length; i++) { var r = find(el.children[i], id); if (r) return r; }
		return null;
	}
	function all(el, out) { out.push(el); el.children.forEach(function (c) { all(c, out); }); return out; }

	function world(storageBroken) {
		var head = new El("head"), body = new El("body");
		var store = {};
		var other = {};
		var doc = {
			head: head, body: body,
			createElement: function (t) { return new El(t); },
			getElementById: function (id) { return find(head, id) || find(body, id); }
		};
		function area(m) {
			return { getItem: function (k) { return k in m ? m[k] : null; }, setItem: function (k, v) { m[k] = String(v); }, removeItem: function (k) { delete m[k]; },
				key: function (i) { return Object.keys(m)[i]; }, get length() { return Object.keys(m).length; } };
		}
		var win = {
			sessionStorage: area(other),
			localStorage: storageBroken ? { getItem: function () { throw new Error("blocked"); }, setItem: function () { throw new Error("blocked"); }, removeItem: function () { throw new Error("blocked"); },
				key: function () { throw new Error("blocked"); }, get length() { throw new Error("blocked"); } } : area(store),
			mdmfJobNoticeLabels: { done: "{0}: finished.", failed: "{0}: failed.", open: "Recent tasks", close: "Close", more: "{0} more ended.", kinds: { MATCHING_RUN: "Running the matching" } }
		};
		new Function("window", "document", "fetch", storeSrc)(win, doc, undefined);
		new Function("window", "document", "fetch", src)(win, doc, undefined);
		return { win: win, doc: doc, store: store };
	}
	var now = Date.now();
	function job(id, status, who, ago, extra) {
		var j = { jobId: String(id), kind: "MATCHING_RUN", status: status, requestedBy: who, endedAt: now - ago, message: "msg " + id };
		for (var k in (extra || {})) j[k] = extra[k];
		return j;
	}
	function texts(w) { return all(w.doc.body, []).filter(function (e) { return e.className === "mdmf-toast-title"; }).map(function (e) { return e.textContent; }); }

	// 1. only the person's own, finished (DONE/FAILED), recent tasks that were not announced yet
	var w = world(false);
	var data = { projectId: "P1", me: "alice", active: [], recent: [
		job(1, "DONE", "alice", 1000), job(2, "FAILED", "alice", 2000), job(3, "DONE", "bob", 1000), job(4, "CANCELLED", "alice", 1000),
		job(5, "DONE", "alice", 3 * 3600 * 1000), job(6, "INTERRUPTED", "alice", 1000) ] };
	var pending = w.win.mdmfJobNotice.pending(data, {}, now).map(function (j) { return j.jobId; });
	check("own DONE and FAILED, recent only, oldest first: " + pending, pending.join(",") === "2,1");
	check("nobody asking = nothing", w.win.mdmfJobNotice.pending({ recent: data.recent }, {}, now).length === 0);

	// 2. check() announces them once, in a role=status region, and remembers
	check("two announced", w.win.mdmfJobNotice.check(data, { kinds: { MATCHING_RUN: "Running the matching" } }) === 2);
	var region = w.doc.getElementById("mdmfJobToasts");
	check("a live region", region && region.getAttribute("role") === "status" && region.getAttribute("aria-live") === "polite");
	var titles = texts(w);
	check("titles: " + titles.join("|"), titles.length === 2 && titles[0] === "Running the matching: failed." && titles[1] === "Running the matching: finished.");
	var closers = all(w.doc.body, []).filter(function (e) { return e.className === "mdmf-toast-close"; });
	check("a close button with an accessible name", closers.length === 2 && closers[0].getAttribute("aria-label") === "Close");
	closers[0].listeners.click();
	check("closing removes only that toast", texts(w).length === 1);
	check("the second look announces nothing (remembered)", w.win.mdmfJobNotice.check(data, {}) === 0 && texts(w).length === 1);
	check("remembered per person, project and job, under the person's own key", Object.keys(w.store).join() === "mdmf:alice:jobNoticesShown"
		&& Object.keys(JSON.parse(w.store["mdmf:alice:jobNoticesShown"])).sort().join() === "alice:P1:1,alice:P1:2");
	// another person on the same browser has their own memory
	check("bob sees his own task even though alice's was shown", w.win.mdmfJobNotice.check({ projectId: "P1", me: "bob", recent: [job(1, "DONE", "bob", 10)] }, {}) === 1);
	// wave 13: and opening bob's store deleted what alice had remembered (a login on the same browser must not find the previous person's keys)
	check("alice's key is gone, bob's is there: " + Object.keys(w.store).join(), Object.keys(w.store).join() === "mdmf:bob:jobNoticesShown");
	check("bob's memory holds only his own task", Object.keys(JSON.parse(w.store["mdmf:bob:jobNoticesShown"])).join() === "bob:P1:1");
	// another project's job with the same id is another task
	check("same job id in another project is announced", w.win.mdmfJobNotice.check({ projectId: "P2", me: "alice", recent: [job(1, "DONE", "alice", 10)] }, {}) === 1);

	// 3. no link to "Recent tasks" when the screen has no such list, a button when it has one
	var w2 = world(false);
	var list = new El("details"); list.id = "mdmfRecentTasks"; w2.doc.body.appendChild(list);
	w2.win.mdmfJobNotice.check({ projectId: "P1", me: "alice", recent: [job(9, "DONE", "alice", 10)] }, {});
	var links = all(w2.doc.body, []).filter(function (e) { return e.className === "mdmf-toast-link"; });
	check("a Recent tasks link", links.length === 1 && links[0].textContent === "Recent tasks");
	links[0].listeners.click();
	check("the link opens the list", list.open === true);
	check("none without the list", all(w.doc.body, []).filter(function (e) { return e.className === "mdmf-toast-link"; }).length === 0);

	// 4. more than three: three toasts and one "N more"
	var w3 = world(false);
	var many = []; for (var i = 1; i <= 5; i++) many.push(job(i, "DONE", "alice", 1000 * (6 - i)));
	check("five announced", w3.win.mdmfJobNotice.check({ projectId: "P1", me: "alice", recent: many }, {}) === 5);
	var t3 = texts(w3);
	check("three and a summary: " + t3.join("|"), t3.length === 4 && t3[3] === "2 more ended.");

	// 5. a blocked or full localStorage never throws (the task may be announced again, which is all that is lost)
	// (an old version's key, written without the user, is removed the first time any person's notice runs)
	var wOld = world(false);
	wOld.store.mdmfJobNoticesShown = "{\"alice:P1:1\":1}";
	wOld.win.mdmfJobNotice.check({ projectId: "P1", me: "alice", recent: [job(1, "DONE", "alice", 10)] }, {});
	check("the legacy key is purged: " + Object.keys(wOld.store).join(), Object.keys(wOld.store).join() === "mdmf:alice:jobNoticesShown");
	var w4 = world(true);
	check("no storage, still announced", w4.win.mdmfJobNotice.check({ projectId: "P1", me: "alice", recent: [job(1, "DONE", "alice", 10)] }, {}) === 1);

	// 6. a failed task says so and shows its sentence, shortened
	var w5 = world(false);
	w5.win.mdmfJobNotice.check({ projectId: "P1", me: "alice", recent: [job(1, "FAILED", "alice", 10, { message: new Array(400).join("x") })] }, {});
	var text = all(w5.doc.body, []).filter(function (e) { return e.className === "mdmf-toast-text"; })[0];
	check("sentence cut to 160 characters", text && text.textContent.length === 160 && text.textContent.slice(-1) === "…");
	var toastEl = all(w5.doc.body, []).filter(function (e) { return e.className.indexOf("mdmf-toast ") === 0; })[0];
	check("failed toast class", toastEl.className.indexOf("mdmf-toast-failed") > 0);
	// 7. UX lot U3: a DONE task whose message starts with "Nothing to do" is announced amber, with its own title - never the green "finished"
	var w6 = world(false);
	w6.win.mdmfJobNoticeLabels.nothing = "{0}: nothing to do.";
	check("nothingToDo rule", w6.win.mdmfJobNotice.nothingToDo(job(1, "DONE", "alice", 10, { message: "Nothing to do: 0 table(s) to plan." }))
		&& !w6.win.mdmfJobNotice.nothingToDo(job(2, "DONE", "alice", 10, { message: "Done: 3 rows." })) && !w6.win.mdmfJobNotice.nothingToDo(job(3, "FAILED", "alice", 10, { message: "Nothing to do" })));
	// UX lot D: a task STORED with failures on some of its items is amber "finished with failures", never green - the status says it, the sentence is not read
	check("partial rule", w6.win.mdmfJobNotice.partial(job(4, "DONE_WITH_FAILURES", "alice", 10, { message: "Landing run finished with failures: 2 of 4 tables were not loaded (A, B)." }))
		&& w6.win.mdmfJobNotice.partial(job(5, "DONE_WITH_FAILURES", "alice", 10, { kind: "MATCHING_CONFIGURE", message: "1 entity configured, 2 refused, 0 not accepted." }))
		&& !w6.win.mdmfJobNotice.partial(job(6, "DONE", "alice", 10, { kind: "MATCHING_RUN", message: "1 entity run, 2 not applied, 0 refused." }))
		&& !w6.win.mdmfJobNotice.partial(job(7, "FAILED", "alice", 10, { message: "Landing run finished with failures on: A." }))
		&& !w6.win.mdmfJobNotice.partial(job(8, "DONE", "alice", 10, { message: "Landing run finished: 16 of 16 stages done." })));
	var w7 = world(false);
	check("a task with failures is announced once, amber", w7.win.mdmfJobNotice.check({ projectId: "P1", me: "alice", recent: [job(9, "DONE_WITH_FAILURES", "alice", 10, { message: "2 of 4 tables were not loaded." })] }, {}) === 1);
	var amberPartial = all(w7.doc.body, []).filter(function (e) { return e.className.indexOf("mdmf-toast ") === 0; })[0];
	check("partial class", amberPartial.className.indexOf("mdmf-toast-partial") > 0 && amberPartial.className.indexOf("mdmf-toast-done") < 0 && amberPartial.className.indexOf("mdmf-toast-failed") < 0);
	w6.win.mdmfJobNotice.check({ projectId: "P1", me: "alice", recent: [job(1, "DONE", "alice", 10, { message: "Nothing to do: 0 table(s) to plan." })] }, { kinds: { MATCHING_RUN: "Running the matching" } });
	check("amber title: " + texts(w6).join("|"), texts(w6).join("|") === "Running the matching: nothing to do.");
	var amber = all(w6.doc.body, []).filter(function (e) { return e.className.indexOf("mdmf-toast ") === 0; })[0];
	check("amber class", amber.className.indexOf("mdmf-toast-nothing") > 0 && amber.className.indexOf("mdmf-toast-done") < 0);
	if (argv[1]) {
		var wizard = $.NSString.stringWithContentsOfFileEncodingError(argv[1], $.NSUTF8StringEncoding, null).js;
		new Function(wizard); // parses, does not run
		check("wizard.js locks the buttons of every action", wizard.indexOf("lockButtons(LOCK_TIMEOUT_MS)") > 0 && wizard.indexOf("mdmfJobNotice.check(data") > 0);
	}
	// every script the Java side writes (ScreenScriptsWave10Test with -Dmdmf.jsDump): the wizard's and the review's, built from strings, must parse
	if (argv[2]) {
		var names = $.NSFileManager.defaultManager.contentsOfDirectoryAtPathError(argv[2], null).js;
		var parsed = 0;
		names.forEach(function (n) {
			var name = ObjC.unwrap(n);
			if (!/[.]js$/.test(name)) return;
			var text = $.NSString.stringWithContentsOfFileEncodingError(argv[2] + "/" + name, $.NSUTF8StringEncoding, null).js;
			try { new Function(text); } catch (e) { throw new Error("FAILED: " + name + " does not parse: " + e); }
			parsed++;
		});
		check("the dumped scripts were found", parsed >= 4);
	}
	return "jobnotice_test OK";
}
