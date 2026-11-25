// Offline test of the code-table block of the Mapping matrix inspector (codes transcoding lot H3, 2026-10-17), run by tools/test_local.sh with macOS's JavaScriptCore:
//   osascript -l JavaScript tools/matrix_codes_test.js <folder of dumped scripts>      (matrix-en.js / matrix-fr.js, written by MatrixHtmlTest with -Dmdmf.jsDump)
// The functions of the block are cut out of the generated script and run against stand-ins for the DOM: what the table prints (every value escaped), what Accept /
// Reject post, and what Save posts (the typed targets, keyed by the observed code read from an attribute). A failure throws; success returns "matrix_codes_test OK".
ObjC.import("Foundation");

function run(argv) {
	function check(label, ok) { if (!ok) throw new Error("FAILED: " + label); }
	function read(name) { return $.NSString.stringWithContentsOfFileEncodingError(argv[0] + "/" + name, $.NSUTF8StringEncoding, null).js; }
	function line(src, startsWith) {
		var found = src.split("\n").filter(function (l) { return l.indexOf(startsWith) === 0; });
		if (found.length !== 1) throw new Error("FAILED: expected one line starting with " + startsWith + " in the matrix script, found " + found.length);
		return found[0];
	}
	["en", "fr"].forEach(function (lang) {
		var src = read("matrix-" + lang + ".js");
		var code = [line(src, "function e(s)"), line(src, "function toneOf(s)"), line(src, "function codeBlock(btn)"), line(src, "window.mdmmCodesDecide="), line(src, "window.mdmmCodesEdit="),
			line(src, "window.mdmmCodesSave=")].join("\n");
		var T = { codesTitle: "Code table", codesFrom: "Source code", codesTo: "Target code", codesRows: "Rows", codesEdit: "Edit", codesSave: "Save", codesCancel: "Cancel",
			codesTargets: "Common list: {0}", codesNoTarget: "(to choose)", codesIncomplete: "{0} of {1} rows have no target code yet.", accept: "Accept", reject: "Reject", confidence: "Confidence:" };
		var posted = [];
		var inspector = { html: "", insertAdjacentHTML: function (where, h) { this.html += h; } };
		var window = { mdmmSelectedKey: "cell|1", mdmmSelect: function () {} };
		var selected = { value: "" };
		var byId = function (id) { return id === "mdmmInspector" ? inspector : selected; };
		var submitAction = function (a, id, id2, comment) { posted.push([a, id, id2, comment]); };
		var hostile = "<img src=x onerror=alert(1)> \"q\" 'r'";
		var cell = { codeLists: [
			{ id: "s1", status: "PROPOSED", statusLabel: "To validate", open: true, confidence: 59, basedOn: "", total: 141, unresolved: 63, targets: ["MR", "MRS"],
				blocked: true, fill: "Choose a target code for Mme, then accept.",
				rows: [{ c: "M.", t: "MR", n: 77 }, { c: "Mme", t: "", n: 63 }, { c: hostile, t: "<b>MRS</b>", n: 1 }] },
			{ id: "s3", status: "PROPOSED", statusLabel: "To validate", open: true, confidence: 80, basedOn: "", total: 10, unresolved: 0, targets: ["MR"], blocked: false, fill: "",
				rows: [{ c: "M", t: "MR", n: 10 }] },
			{ id: "s2", status: "ACCEPTED", statusLabel: "Accepted", open: false, confidence: null, basedOn: "TITLE", total: 10, unresolved: 0, targets: ["MR"], rows: [{ c: "M", t: "MR", n: 10 }] }] };
		var btn = { getAttribute: function (n) { return n === "data-cell" ? JSON.stringify(cell) : null; } };
		(new Function("T", "window", "byId", "submitAction", code + "\ncodeBlock(arguments[4]);"))(T, window, byId, submitAction, btn);
		var html = inspector.html;
		check(lang + ": the table has its two columns and the rows", html.indexOf("<th>Source code</th><th>Target code</th><th>Rows</th>") > 0 && html.indexOf("<td>M.</td>") > 0
			&& html.indexOf("data-target=\"MR\"") > 0 && html.indexOf("(to choose)") > 0 && html.indexOf(">77</td>") > 0);
		check(lang + ": nothing raw from the data reaches the page", html.indexOf("<img") < 0 && html.indexOf("<b>MRS") < 0 && html.indexOf("&lt;img src=x onerror=alert(1)&gt;") > 0);
		check(lang + ": the incomplete table says how many rows lack a target", html.indexOf("63 of 141 rows have no target code yet.") > 0);
		check(lang + ": the open proposal has Accept, Edit and Reject, the decided one none", (html.match(/mdmmCodesDecide/g) || []).length === 4 && html.indexOf("mdmmCodesEdit") > 0
			&& (html.match(/mdmmCodeButtons/g) || []).length === 2);
		// staging last points (2026-10-17): a table with an observed code that has rows and no target cannot be accepted - Accept is disabled and the card says what to fill;
		// Edit and Reject stay; the complete open table accepts as before
		check(lang + ": Accept is disabled on the incomplete table only", (html.match(/disabled="disabled"/g) || []).length === 1 && html.indexOf('data-act="ACCEPT_CODES" data-step="s1" disabled="disabled"') > 0
			&& html.indexOf('data-act="ACCEPT_CODES" data-step="s3" onclick') > 0);
		check(lang + ": the card says what to fill, once", (html.match(/Choose a target code for Mme, then accept\./g) || []).length === 2 /* the sentence and the title of the button */
			&& html.indexOf('mdmmCodesFill">Choose a target code for Mme, then accept.</p>') > 0);
		check(lang + ": Edit and Reject stay on the incomplete table", html.indexOf('data-act="REJECT_CODES" data-step="s1"') > 0 && (html.match(/mdmmCodesEdit/g) || []).length === 2);
		check(lang + ": the reference of the second table is named", html.indexOf("Common list: MR") > 0 && html.indexOf("(TITLE)") > 0);
		var accept = { getAttribute: function (n) { return n === "data-act" ? "ACCEPT_CODES" : "s1"; } };
		(new Function("window", "byId", "submitAction", "T", code + "\nwindow.mdmmCodesDecide(arguments[4]);"))(window, byId, submitAction, T, accept);
		check(lang + ": Accept posts the step id and keeps the selection", posted.length === 1 && posted[0][0] === "ACCEPT_CODES" && posted[0][1] === "s1" && selected.value === "cell|1");
		// Save: two cells with a typed target, the code read from the attribute
		function cellOf(code, typed) { return { getAttribute: function (n) { return n === "data-code" ? code : null; }, querySelector: function () { return { value: typed }; } }; }
		var box = { querySelectorAll: function () { return [cellOf("M.", "MR"), cellOf("Mme", "MRS"), cellOf(hostile, "")]; } };
		var save = { closest: function () { return box; }, getAttribute: function () { return "s1"; } };
		(new Function("window", "byId", "submitAction", "T", code + "\nwindow.mdmmCodesSave(arguments[4]);"))(window, byId, submitAction, T, save);
		var sent = JSON.parse(posted[1][3]);
		check(lang + ": Save posts every typed target keyed by its observed code", posted[1][0] === "SAVE_CODES" && posted[1][1] === "s1" && sent["M."] === "MR" && sent["Mme"] === "MRS" && sent[hostile] === "");
	});
	return "matrix_codes_test OK";
}
