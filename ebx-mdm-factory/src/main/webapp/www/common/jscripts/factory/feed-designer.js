/*
 * Feed designer for the MDM Factory (docs/FEED-DESIGNER.md).
 *
 * FORK of ebx-connectors' mapping-designer.js (com.on.ebx.connect.ebx.ui.MappingDesignerUserService),
 * as it stood 2026-09-23, adapted for a landing-table -> staging-entity feed tree instead of an
 * EBX <-> external-service mapping. Every id/class is prefixed "mdfd" (the original used "ftmd") so
 * the two designers never collide if both modules are ever loaded on the same page.
 *
 * KEPT from the original: the two trees with type badges and search, the SVG link curves, a
 * transformation node per rule on the canvas, drag-from-source-to-target to create a rule, "+
 * constant", the inspector (pipeline steps, editable, with the transformation catalogue's hints),
 * the table view, zoom, fullscreen, hide-unmapped, the dirty marker.
 *
 * REMOVED (a feed has none of these - docs/TARGET-MAPPING.md sec 2/3.3): the INBOUND/OUTBOUND
 * direction switch, the response-root field, the "service call" section (a feed's source is always
 * a landing table row, never a live external call), the outbound response section, and Connectors'
 * own sub-object "children" mapping creation (a feed tree's parent/child structure is fixed by
 * TransferFeed rows, never created ad hoc from this page).
 *
 * ADDED: a feed selector bar (the entity's whole feed tree - root feed first, child feeds indented)
 * that switches the model IN PLACE, one rule array per feed; for a child feed, a small header
 * showing its parentLinkField and an editable sourceIdPipeline; the child's own link attribute
 * (the target field whose referenceEntity is the parent) rendered on the right, greyed out and not
 * droppable ("set by the engine"); a read-only mode (no drag, no inspector edits) for a feed that is
 * REJECTED, or when the whole page is read-only (window.mdfdModel.readOnly).
 *
 * Expects a global `mdfdModel` (written by com.on.ebx.factory.ebx.ui.FeedDesignerUserService) and
 * the hidden inputs mdfdRulesInput / mdfdSourceIdPipelinesInput that travel with the form - see
 * that class' own Javadoc for the exact JSON shape each one carries on submit.
 *
 * ES5 only (no arrow functions, no let/const, no window.scrollY - CLAUDE.md sec 9/15): this page
 * runs inside a UserServicePane, whose scrolling container is an EBX div, never the window itself.
 *
 * Bug fix, 2026-09-24 (project Personnes, entity Personne): FeedDesignerUserService used to send only
 * ACCEPTED/APPLIED target attributes, so an entity whose attributes were all still PROPOSED showed an
 * empty Target pane and every rule's link fell back to anchor()'s pane-corner point (no port found for
 * its target). The server now sends every attribute but REJECTED/SUPERSEDED (PROPOSED included, tagged
 * with a small chip here - see renderTree's `proposed` flag) and this file no longer trusts a rule's
 * source/target to exist at all: fieldExists/isUnresolved gate both the canvas link drawing (drawAll)
 * and the table view (renderTable) so a rule naming a field truly not in the current list (e.g. one
 * rejected after the rule was drawn) is shown as an explicit "unresolved" row instead of a dangling
 * curve - see docs/FEED-DESIGNER.md.
 *
 * Staging-proposal "designer" lot (docs/STAGING-PROPOSAL.md, docs/FEED-DESIGNER.md): a target field row
 * now carries small rule/check badges (`x.field.guards`, from `ebx.GuardReader`) and, clicked, opens a
 * FIELD inspector (name/type/status/source links on the left, "Rules and checks on <field>" on the right)
 * instead of jumping straight to the rule inspector - see `selectField`/`renderFieldInspector`. A guard's
 * accept/reject/severity, and a suggested-fix link's apply/keep, are IMMEDIATE decisions posted through
 * their own hidden submit (`postDecision`, `#mdfdDecideSubmit`) - never folded into the Save/dirty cycle;
 * posting is refused client-side (and again server-side) while `dirty` is true, so an unsaved link edit
 * is never silently lost. A flagged rule (`r.attention`, `staging.AttentionRules`, computed over this
 * feed's own mappings) draws its link red-dashed with a "!" node; a suggested-fix mapping
 * (`AttributeMapping.alternativeTo`, `current.suggestions`) draws as its own green-dashed "+" link,
 * selectable independently of the ordinary rule links. A segmented Matrix/Designer control in the toolbar
 * switches to the Mapping matrix (`current.matrixUrl`, `core.MappingMatrixLink`) for this feed's entity.
 */
(function () {
	'use strict';
	var M = window.mdfdModel;
	var root = document.getElementById('mdfd');
	if (!M || !root) { return; }
	var L = {};
	for (var li = 0; li < M.labels.length; li++) { L[M.labels[li].key] = M.labels[li].text; }
	function t(key, a, b) { var s = L[key] || key; return s.replace('{0}', a === undefined ? '' : a).replace('{1}', b === undefined ? '' : b); }

	// ------------------------------------------------------------ state
	var feeds = M.feeds || [];
	var byId = {};
	for (var fi = 0; fi < feeds.length; fi++) { byId[feeds[fi].id] = feeds[fi]; }
	var currentIndex = 0;
	for (var fj = 0; fj < feeds.length; fj++) { if (feeds[fj].id === M.activeFeedId) { currentIndex = fj; break; } }
	var current = feeds.length ? feeds[currentIndex] : null;
	var rules = current ? current.rules : [];         // {id, source, target, transformation} - a reference into current.rules, mutated in place
	var selected = -1;                                 // index of the selected rule
	var selectedField = null;                           // target field path with the FIELD inspector open (designer lot)
	var selectedSuggestion = -1;                        // index of the selected suggested-fix link (current.suggestions)
	var focusField = null;                              // {side, path} highlighted field
	var hideUnmapped = false;
	var tableMode = false;
	var armedConstant = false;                          // "+ constant" pressed: next output click makes a rule
	var dirty = false;
	var trees = { L: null, R: null };
	var expanded = { L: {}, R: {} };
	var filters = { L: '', R: '' };
	// "add a target field" lot: StagingAttribute.dataType's own enum (tools/build_xsd.py's DATA_TYPE) - a
	// hand-kept mirror, same posture as the transformation catalogue (M.transformations, sent by the server);
	// the codes themselves are shown as-is (no i18n label), same convention as a field's raw `type`/`status`.
	var DATA_TYPES = ['STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP'];
	var NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_]*$/;

	// security review 2026-09-24, finding I3: aligned on ebx.ui.FactoryHtml#esc / mappingmatrix.MatrixHtml#esc
	// (escapes the single quote too, not just & < > ") - every call site here writes into innerHTML or a
	// double-quoted HTML attribute, never a single-quoted one, but the extra escape is cheap and keeps the
	// three esc() copies in this module identical rather than silently drifting.
	function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;'); }
	function el(id) { return document.getElementById(id); }
	function readOnly() { return !!M.readOnly || !current || current.editable === false; }
	function sync(markDirty) {
		if (markDirty) { dirty = true; }
		var rulesByFeed = {};
		var pipelinesByFeed = {};
		for (var i = 0; i < feeds.length; i++) { rulesByFeed[feeds[i].id] = feeds[i].rules; pipelinesByFeed[feeds[i].id] = feeds[i].sourceIdPipeline || ''; }
		el('mdfdRulesInput').value = JSON.stringify(rulesByFeed);
		el('mdfdSourceIdPipelinesInput').value = JSON.stringify(pipelinesByFeed);
		var dirtyInput = el('mdfdDirtyInput'); if (dirtyInput) { dirtyInput.value = dirty ? '1' : ''; }
		var d = el('mdfdDirty'); if (d) { d.className = 'mdfdDirty' + (dirty ? ' mdfdOn' : ''); }
	}

	// ------------------------------------------------------------ guard/suggestion decisions (designer lot)
	// One-shot posts through the hidden #mdfdDecideSubmit button - see FeedDesignerUserService#onDecide's own
	// Javadoc (CLAUDE.md sec 9 "Posting a user service's own inputs"). Never posted while `dirty`: the form
	// would still carry the pending mdfdRulesInput/mdfdSourceIdPipelinesInput edits, but onDecide's own
	// handler never calls onSave, so they would be silently discarded on the next render - refused instead,
	// client-side here and again server-side (mdfdDirtyInput).
	function postDecision(nature, id, action, severity) {
		if (dirty) { window.alert(t('designer.guard.dirty')); return; }
		if (readOnly()) { return; }
		el('mdfdDecideNature').value = nature;
		el('mdfdDecideId').value = id;
		el('mdfdDecideAction').value = action;
		el('mdfdDecideSeverity').value = severity === undefined ? '' : severity;
		var b = el('mdfdDecideSubmit');
		if (b) { b.click(); }
	}
	function fieldsOf(side) { return current ? (side === 'L' ? current.sourceFields : current.targetFields) : []; }
	function isTarget(side) { return side === 'R'; }

	// ------------------------------------------------------------ "add a target field" (docs/FEED-DESIGNER.md)
	// Same one-shot posting family as postDecision above (a THIRD hidden submit, #mdfdAddFieldSubmit, never
	// folded into Save/onDecide) - refused client-side while dirty for the exact same reason postDecision is:
	// FeedDesignerUserService#onAddTargetField re-reads the model fresh after this postback and has no way to
	// re-apply a poster's pending link edits onto it, so an unsaved edit must be saved first, not silently lost.
	function toggleAddFieldForm() {
		var f = el('mdfdAddFieldForm'); if (!f) { return; }
		if (f.style.display === 'none') { openAddFieldForm(); } else { closeAddFieldForm(); }
	}
	function openAddFieldForm() {
		var f = el('mdfdAddFieldForm'); if (!f) { return; }
		f.style.display = '';
		var err = el('mdfdAddFieldErr'); if (err) { err.textContent = ''; }
		var nameInput = el('mdfdAddFieldName'); if (nameInput) { nameInput.focus(); }
	}
	function closeAddFieldForm() {
		var f = el('mdfdAddFieldForm'); if (f) { f.style.display = 'none'; }
		var err = el('mdfdAddFieldErr'); if (err) { err.textContent = ''; }
	}
	// Client-side checks are a courtesy (immediate feedback) only - FeedDesignerUserService#onAddTargetField
	// re-checks the exact same three things (dirty, name pattern/length, case-insensitive uniqueness against
	// EVERY attribute of the entity regardless of ITS OWN status) and is the only one that is ever trusted.
	function submitAddField() {
		if (dirty) { window.alert(t('designer.guard.dirty')); return; }
		if (readOnly() || !current) { return; }
		var err = el('mdfdAddFieldErr');
		var name = (el('mdfdAddFieldName').value || '').replace(/^\s+|\s+$/g, '');
		if (!name || name.length > 80 || !NAME_PATTERN.test(name)) {
			err.textContent = t('designer.targetField.namePattern', name);
			return;
		}
		var fields = fieldsOf('R'); var lower = name.toLowerCase();
		for (var i = 0; i < fields.length; i++) { if (fields[i].path.toLowerCase() === lower) { err.textContent = t('designer.targetField.duplicate', name); return; } }
		el('mdfdNewFieldEntity').value = current.entityId;
		el('mdfdNewFieldName').value = name;
		el('mdfdNewFieldLabel').value = el('mdfdAddFieldLabel').value || '';
		el('mdfdNewFieldType').value = el('mdfdAddFieldType').value || 'STRING';
		el('mdfdNewFieldMandatory').value = el('mdfdAddFieldMandatory').checked ? '1' : '';
		var b = el('mdfdAddFieldSubmit'); if (b) { b.click(); }
	}

	// ------------------------------------------------------------ type badges
	function badge(type) {
		var s = String(type || '').toLowerCase();
		if (!s) { return ['?', '']; }
		if (s.indexOf('fk') === 0 || s === 'reference') { return ['FK', 'mdfdTFk']; }
		if (/int|decimal|number|double|float|long|numeric/.test(s)) { return ['NUM', 'mdfdTNum']; }
		if (/timestamp/.test(s)) { return ['DT', 'mdfdTDate']; }
		if (/date/.test(s)) { return ['DATE', 'mdfdTDate']; }
		if (/bool/.test(s)) { return ['BOOL', 'mdfdTBool']; }
		if (/string|text|char|varchar|null/.test(s)) { return ['STR', '']; }
		return [s.substring(0, 4).toUpperCase(), ''];
	}

	// ------------------------------------------------------------ trees
	// A tree node: {key, name, label, type, path (leaf), children[], field}. A feed's fields are flat
	// (a landing column, or a staging attribute) - no nested groups, unlike a document's fields in the
	// Connectors original - but the same tree shape is kept so the rest of the geometry/rendering code
	// (written for a general tree) needs no change.
	function buildTree(side) {
		var fields = fieldsOf(side);
		var rootNode = { key: '', children: [], byName: {} };
		for (var i = 0; i < fields.length; i++) {
			var f = fields[i];
			rootNode.children.push({ key: f.path, name: f.path, label: f.label || f.path, children: [], byName: {}, path: f.path, field: f, type: f.type });
		}
		return rootNode;
	}
	function mappedPaths(side) {
		var m = {}; var out = (side === 'R');
		for (var i = 0; i < rules.length; i++) { var p = out ? rules[i].target : rules[i].source; if (p) { m[p] = true; } }
		return m;
	}
	// A rule can point at a field the current feed no longer lists (the target attribute was rejected/
	// superseded after the rule was drawn, or a landing column was removed) - fieldsOf(side) is the FULL,
	// unfiltered list (search/hideUnmapped never remove a field from it, only from what renderTree shows),
	// so this is never confused with "temporarily filtered out". Robustness fix, docs/FEED-DESIGNER.md: such
	// a rule must never fall back to anchor()'s "folded" pane-corner point (that is the exact dangling-curve
	// bug a missing PROPOSED target used to cause) - it is rendered as an explicit "unresolved" rule instead
	// (table view only, see renderTable/drawAll).
	function fieldExists(side, path) {
		if (!path) { return true; } // empty source = a constant rule, always "resolved"
		var fields = fieldsOf(side);
		for (var i = 0; i < fields.length; i++) { if (fields[i].path === path) { return true; } }
		return false;
	}
	function isUnresolved(r) { return (r.source && !fieldExists('L', r.source)) || !fieldExists('R', r.target); }
	function unresolvedCount() { var n = 0; for (var i = 0; i < rules.length; i++) { if (isUnresolved(rules[i])) { n++; } } return n; }
	function renderTree(side) {
		var tree = trees[side] = trees[side] || buildTree(side);
		var container = el(side === 'L' ? 'mdfdTreeL' : 'mdfdTreeR');
		var filter = filters[side].toLowerCase(); var mapped = mappedPaths(side); var h = [];
		if (!tree.children.length) {
			container.innerHTML = '<div class="mdfdEmpty">' + esc(t('designer.noFields')) + '</div>';
			return;
		}
		var count = 0;
		function matches(n) { return !filter || (n.label + ' ' + n.name + ' ' + (n.path || '')).toLowerCase().indexOf(filter) >= 0; }
		for (var c = 0; c < tree.children.length; c++) {
			var x = tree.children[c];
			if (!matches(x)) { continue; }
			var isMapped = mapped[x.path];
			if (hideUnmapped && !isMapped) { continue; }
			count++;
			var linkAttr = !!(x.field && x.field.linkAttribute);
			var technical = !!(x.field && x.field.technical);
			// A PROPOSED target attribute (staging.StagingModel#build has not generated its field yet) is still
			// shown and still a normal drop target - the designer is precisely where a person reviews the rules
			// already drawn onto it before deciding it (docs/FEED-DESIGNER.md) - just tagged, never locked.
			var proposed = side === 'R' && !!(x.field && x.field.status === 'PROPOSED');
			// "shown but not droppable" (task brief) applies to a TARGET-side technical (primary-key) attribute
			// or the child's own link attribute - never to a SOURCE-side technical/key column, which remains a
			// perfectly normal drag source (a source's own key is often exactly what feeds a business key).
			var locked = side === 'R' && (technical || linkAttr);
			var b = badge(x.type);
			var cls = 'mdfdRow' + (isMapped ? ' mdfdMapped' : '') + (technical ? ' mdfdPk' : '') + (locked ? ' mdfdLocked' : '') + (proposed ? ' mdfdProposed' : '')
				+ (focusField && focusField.side === side && focusField.path === x.path ? ' mdfdSel' : '')
				+ (side === 'R' && selectedField === x.path ? ' mdfdSel' : '');
			var title = x.path + (x.field && x.field.sample ? ' = ' + x.field.sample : '') + (linkAttr ? ' (' + t('designer.linkAttribute') + ')' : '');
			// audit F-45: every row is reachable with Tab and acts on Enter/Space like a click (the keydown handler below) - a source row
			// then a target row creates a rule without dragging; the name says which side it is on and whether it is the picked source
			var picked = focusField && focusField.side === side && focusField.path === x.path;
			h.push('<div class="' + cls + '" data-side="' + side + '" data-key="' + esc(x.key) + '" data-path="' + esc(x.path) + '" title="' + esc(title) + '"'
				+ ' role="button" tabindex="0" aria-label="' + esc(t(side === 'L' ? 'designer.kbd.sourceRow' : 'designer.kbd.targetRow', x.label)) + '"'
				+ (side === 'L' ? ' aria-pressed="' + (picked ? 'true' : 'false') + '"' : '') + (locked ? ' aria-disabled="true"' : '') + '>');
			h.push('<span class="mdfdIndent" style="width:4px"></span>');
			h.push('<span class="mdfdType ' + b[1] + '">' + esc(b[0]) + '</span>');
			h.push('<span class="mdfdName">' + esc(x.label) + '</span>');
			if (x.field && x.field.mandatory) { h.push('<span class="mdfdReq">*</span>'); }
			if (proposed) { h.push('<span class="mdfdProposedTag">' + esc(t('designer.proposed')) + '</span>'); }
			// designer lot, delta 1: rule/check badges - a REJECTED/SUPERSEDED guard never reaches this array
			// (ebx.GuardReader skips it server-side), so no client-side status filter is needed here.
			if (side === 'R' && x.field && x.field.guards && x.field.guards.length) {
				for (var gi = 0; gi < x.field.guards.length; gi++) {
					var guard = x.field.guards[gi];
					h.push('<span class="mdfdGuardBadge ' + (guard.check ? 'mdfdGuardCheck' : 'mdfdGuardRule') + '" title="' + esc(guard.label) + '">'
						+ esc(guard.check ? t('designer.guard.check') : t('designer.guard.rule')) + '</span>');
				}
			}
			if (x.field && x.field.sample) { h.push('<span class="mdfdSampleV">' + esc(x.field.sample) + '</span>'); }
			if (!locked) { h.push('<span class="mdfdPort" data-port="' + side + '" data-path="' + esc(x.path) + '"></span>'); }
			else if (linkAttr) { h.push('<span class="mdfdEngineTag">' + esc(t('designer.setByEngine')) + '</span>'); }
			h.push('</div>');
		}
		container.innerHTML = h.join('');
		var cnt = el(side === 'L' ? 'mdfdCountL' : 'mdfdCountR');
		if (cnt) { var m = 0; for (var k in mapped) { if (mapped.hasOwnProperty(k)) { m++; } } cnt.textContent = t('designer.mappedCount', m) + ' / ' + count; }
	}

	// ------------------------------------------------------------ geometry (unchanged from the original: a
	// general "anchor of a leaf row on a side, clamped to the pane's own visible area" computation)
	function portOf(side, path) {
		var pane = el(side === 'L' ? 'mdfdTreeL' : 'mdfdTreeR');
		var ports = pane.querySelectorAll('.mdfdPort');
		for (var i = 0; i < ports.length; i++) { if (ports[i].getAttribute('data-path') === path) { return ports[i]; } }
		return null;
	}
	function anchor(side, path) {
		var work = el('mdfdWork').getBoundingClientRect();
		var pane = el(side === 'L' ? 'mdfdTreeL' : 'mdfdTreeR').getBoundingClientRect();
		var port = portOf(side, path);
		if (port) {
			var r = port.getBoundingClientRect();
			var y = r.top + r.height / 2 - work.top;
			var clamped = Math.max(pane.top - work.top + 2, Math.min(pane.bottom - work.top - 2, y));
			return { x: (side === 'L' ? r.right : r.left) - work.left, y: clamped, visible: y >= pane.top - work.top && y <= pane.bottom - work.top };
		}
		var edge = side === 'L' ? pane.right - 7 : pane.left + 7;
		return { x: edge - work.left, y: pane.top - work.top + 12, visible: false, folded: true };
	}
	function curve(x1, y1, x2, y2) { var mx = (x1 + x2) / 2; return 'M' + x1 + ',' + y1 + ' C' + mx + ',' + y1 + ' ' + mx + ',' + y2 + ' ' + x2 + ',' + y2; }

	// ------------------------------------------------------------ canvas: links and nodes
	function hasNode(r) { return !!(r.transformation && r.transformation.trim()) || !r.source; }
	function suggestionsOf() { return (current && current.suggestions) || []; }
	// a suggestion is only drawable when both ends still resolve in the current field lists (same guard as
	// isUnresolved for an ordinary rule).
	function suggestionResolved(s) { return fieldExists('L', s.source) && fieldExists('R', s.target); }
	// Bug fix, 2026-09-24 (docs/FEED-DESIGNER.md, live screenshot with 22 rules): the old pitch (NH=44 + GAP=6
	// = 50px) was almost double a tree row's own height (26px * scale), so past ~7 rules the forward-only
	// clamp ("if yy+NH>bottom-4 then yy=bottom-4-NH") pinned every remaining node to the SAME bottom-corner
	// spot - the "two overlapping Transformation boxes, ~15 links converging on one" in the report. Fixed by:
	// (1) a node's natural height now MATCHES a tree row's own height (never independent of `scale`), so a
	// simple 1:1 mapping of rules fits without any push at all; (2) the ideal position is the node's OWN
	// TARGET row (`b.y`), not the midpoint of source and target, so a node lines up with the field it feeds
	// and its output link is a straight horizontal line; (3) collisions are resolved by the classic two-pass
	// "minimum-gap" placement (forward pass pushes later nodes down, backward pass pulls earlier ones back up
	// no further than the gap allows) instead of a single forward pass with a hard bottom clamp - this keeps a
	// strictly increasing sequence of centers at least NH+GAP apart, so nodes are NEVER placed on top of each
	// other, whatever the count (they may run past the visible top/bottom instead, which a scroll can reach -
	// preferred over ever stacking).
	function drawAll() {
		var svg = el('mdfdSvg'); var canvas = el('mdfdCanvas'); var work = el('mdfdWork').getBoundingClientRect();
		var cRect = canvas.getBoundingClientRect(); var cx0 = cRect.left - work.left, cx1 = cRect.right - work.left;
		var paths = []; var nodes = [];
		var hot = {};
		if (focusField) { for (var i = 0; i < rules.length; i++) { if ((focusField.side === 'L' ? rules[i].source : rules[i].target) === focusField.path) { hot[i] = true; } } }
		var anyHot = focusField !== null || selected >= 0;
		var sugs = suggestionsOf();
		// a node's height tracks the tree row height (26px * scale, feed-designer.css's own --mdfd-scale) minus
		// a small margin, so a 1:1 set of rules lines up node-per-row with no push needed at all; GAP is the
		// small remainder, not an independent constant.
		var NH = Math.max(16, Math.round(26 * scale) - 4), GAP = 2;
		// designer lot: rule nodes and suggestion nodes share ONE vertical layout pass (a "box" per drawable
		// link, kind 'rule' or 'sug') so the two families never overlap each other on the canvas.
		var placed = [];
		for (var n = 0; n < rules.length; n++) {
			var r = rules[n]; if (!hasNode(r) || isUnresolved(r)) { continue; }
			var a = r.source ? anchor('L', r.source) : null; var b = anchor('R', r.target);
			// fix/designer-cosmetics (2026-09-24, live screenshot: filtering source on "NAME1" and target on
			// "Designer" drew NAME1's node linked to the unrelated "Designer test" row): a field the search
			// filter or "Hide unmapped fields" currently hides from its tree has no rendered port, so anchor()
			// falls back to its "folded" pane-top corner point - a rule/suggestion with either end folded is not
			// currently renderable at all and must be skipped here (no node), same as isUnresolved (field truly
			// absent) but a DIFFERENT condition: a folded field still exists and still counts in the rule/table
			// view and counts, it is just not drawn right now. Never confuse the two - fieldExists/isUnresolved
			// below is unchanged.
			if ((a && a.folded) || b.folded) { continue; }
			// both ends scrolled out of the visible pane area (but still rendered - a real port exists): draw no
			// node for it either (cleaner than a useless box at a clamped corner) - its link, if any, still falls
			// back to the plain a->b curve below (byRule stays unset for it), clamped exactly as any other
			// off-screen link already was before this fix.
			if (a ? (!a.visible && !b.visible) : !b.visible) { continue; }
			placed.push({ kind: 'rule', i: n, y: b.y }); // centred on the TARGET row, not the source/target midpoint
		}
		for (var sn = 0; sn < sugs.length; sn++) {
			if (!suggestionResolved(sugs[sn])) { continue; }
			var sa = anchor('L', sugs[sn].source), sb = anchor('R', sugs[sn].target);
			if (sa.folded || sb.folded) { continue; } // see the rule loop's own comment just above
			if (!sa.visible && !sb.visible) { continue; }
			placed.push({ kind: 'sug', i: sn, y: sb.y });
		}
		placed.sort(function (p, q) { return p.y - q.y; });
		// two-pass "minimum gap" collision resolution (see the function's own header comment): forward pushes a
		// later box down when it would start too close to the previous one; backward then pulls an earlier box
		// back up when the forward pass pushed a LATER one down more than necessary - the two together keep
		// every center at least NH+GAP apart without ever needing to clamp (and therefore stack) at a boundary.
		for (var p = 0; p < placed.length; p++) {
			placed[p].center = p === 0 ? placed[p].y : Math.max(placed[p].y, placed[p - 1].center + NH + GAP);
		}
		for (var p2 = placed.length - 2; p2 >= 0; p2--) {
			var maxCenter = placed[p2 + 1].center - (NH + GAP);
			if (placed[p2].center > maxCenter) { placed[p2].center = maxCenter; }
		}
		for (var p3 = 0; p3 < placed.length; p3++) { placed[p3].top = placed[p3].center - NH / 2; }
		var nodeW = Math.min(210, Math.max(150, (cx1 - cx0) - 40)); var nodeX = cx0 + ((cx1 - cx0) - nodeW) / 2;
		var byRule = {}; var bySug = {};
		for (var q = 0; q < placed.length; q++) {
			var box = placed[q];
			// designer lot: a compact, ONE-LINE node - gear/icon plus the pipeline text only, no separate
			// "Transformation"/"Constant" heading line (that stays in the title tooltip, already built below) -
			// this is what lets a node's height match a tree row's own height (see the function header comment).
			if (box.kind === 'rule') {
				var idx = box.i; var rr = rules[idx];
				var cls = 'mdfdNode' + (!rr.source ? ' mdfdConst' : '') + (rr.attention ? ' mdfdNodeBad' : '') + (idx === selected ? ' mdfdSel' : '') + (anyHot && !hot[idx] && idx !== selected ? ' mdfdOff' : '');
				var gearIcon = rr.attention ? '!' : '&#9881;';
				var lineText = rr.transformation && rr.transformation.trim() ? rr.transformation : t('designer.rule.constant');
				var titleText = (rr.source || '') + ' -> ' + rr.target + '\n' + rr.transformation + (rr.attention ? '\n' + attentionReason(rr) : '');
				nodes.push('<div class="' + cls + '" data-rule="' + idx + '" style="left:' + nodeX + 'px;top:' + box.top + 'px;width:' + nodeW + 'px;height:' + NH + 'px" title="' + esc(titleText) + '">'
					+ (rr.source ? '<span class="mdfdNodePort mdfdIn"></span>' : '') + '<span class="mdfdNodePort mdfdOut" data-nodeport="' + idx + '"></span>'
					+ '<span class="mdfdGear">' + gearIcon + '</span><span class="mdfdNodeLine">' + esc(lineText) + '</span></div>');
				box.cy = box.top + NH / 2; byRule[idx] = box;
			} else {
				var sidx = box.i; var ss = sugs[sidx];
				var cls3 = 'mdfdNode mdfdNodeSug' + (selectedSuggestion === sidx ? ' mdfdSel' : '');
				var lineText2 = ss.transformation && ss.transformation.trim() ? ss.transformation : (ss.source + ' -> ' + ss.target);
				nodes.push('<div class="' + cls3 + '" data-suggestion="' + sidx + '" style="left:' + nodeX + 'px;top:' + box.top + 'px;width:' + nodeW + 'px;height:' + NH + 'px" title="' + esc(t('designer.suggestion.tag') + ': ' + ss.source + ' -> ' + ss.target) + '">'
					+ '<span class="mdfdNodePort mdfdIn"></span><span class="mdfdNodePort mdfdOut"></span>'
					+ '<span class="mdfdGear">+</span><span class="mdfdNodeLine">' + esc(lineText2) + '</span></div>');
				box.cy = box.top + NH / 2; bySug[sidx] = box;
			}
		}
		for (var k = 0; k < rules.length; k++) {
			var ru = rules[k]; if (isUnresolved(ru)) { continue; } // never anchor()'s pane-corner fallback - see fieldExists/isUnresolved
			var pa = ru.source ? anchor('L', ru.source) : null; var pb = anchor('R', ru.target);
			// fix/designer-cosmetics: a rule whose source or target is currently filtered out of its tree (the
			// SAME "folded" condition the placement pass above skips a node for) gets no link drawn either - the
			// old code recomputed pa/pb here independently of `placed`/byRule and still drew a curve to the
			// folded pane-top point, which is the dangling-looking-like-another-row link from the live bug report.
			if ((pa && pa.folded) || pb.folded) { continue; }
			var cls2 = (k === selected ? ' mdfdSel' : '') + (hot[k] ? ' mdfdHot' : '') + (anyHot && !hot[k] && k !== selected ? ' mdfdOff' : '') + (ru.attention ? ' mdfdAttn' : '');
			var tip = '<title>' + esc((ru.source || t('designer.rule.constant')) + ' -> ' + ru.target + (ru.transformation ? '\n' + ru.transformation : '') + (ru.attention ? '\n' + attentionReason(ru) : '')) + '</title>';
			if (byRule[k]) {
				var node = byRule[k]; var nx0 = nodeX - 6, nx1 = nodeX + nodeW + 6;
				if (pa) { paths.push('<path data-rule="' + k + '" class="' + cls2 + '" d="' + curve(pa.x, pa.y, nx0, node.cy) + '">' + tip + '</path>'); }
				paths.push('<path data-rule="' + k + '" class="' + cls2 + '" d="' + curve(nx1, node.cy, pb.x, pb.y) + '">' + tip + '</path>');
			} else if (pa) {
				paths.push('<path data-rule="' + k + '" class="' + cls2 + '" d="' + curve(pa.x, pa.y, pb.x, pb.y) + '">' + tip + '</path>');
			}
		}
		// designer lot, delta 4: suggested-fix links - always resolved-to-node here (only pushed into `placed`
		// when suggestionResolved), green dashed via CSS's own .mdfdSug class.
		for (var sk = 0; sk < sugs.length; sk++) {
			if (!bySug[sk]) { continue; }
			var su = sugs[sk]; var node2 = bySug[sk];
			var pa2 = anchor('L', su.source), pb2 = anchor('R', su.target);
			var clsS = 'mdfdSug' + (selectedSuggestion === sk ? ' mdfdSel' : '');
			var nx0b = nodeX - 6, nx1b = nodeX + nodeW + 6;
			paths.push('<path data-suggestion="' + sk + '" class="' + clsS + '" d="' + curve(pa2.x, pa2.y, nx0b, node2.cy) + '"></path>');
			paths.push('<path data-suggestion="' + sk + '" class="' + clsS + '" d="' + curve(nx1b, node2.cy, pb2.x, pb2.y) + '"></path>');
		}
		svg.innerHTML = paths.join('');
		var layer = el('mdfdNodes'); layer.innerHTML = nodes.join('');
		var uCount = unresolvedCount();
		el('mdfdCanvasHint').style.display = (rules.length - uCount) > 0 || sugs.length > 0 ? 'none' : 'block';
		el('mdfdRuleCount').textContent = rules.length;
		var warn = el('mdfdUnresolvedWarn');
		if (warn) { warn.style.display = uCount ? 'inline' : 'none'; warn.textContent = t('designer.unresolvedWarning', uCount); }
		var aCount = 0; for (var ai = 0; ai < rules.length; ai++) { if (rules[ai].attention) { aCount++; } }
		var attnWarn = el('mdfdAttnWarn');
		if (attnWarn) { attnWarn.style.display = aCount ? 'inline' : 'none'; attnWarn.textContent = aCount ? ('⚠ ' + aCount + ' ' + t('designer.attention.needsEye')) : ''; }
		var ps = svg.getElementsByTagName('path');
		for (var y2 = 0; y2 < ps.length; y2++) {
			var sugAttr = ps[y2].getAttribute('data-suggestion');
			if (sugAttr !== null) { ps[y2].onclick = function () { selectSuggestion(parseInt(this.getAttribute('data-suggestion'), 10)); }; }
			else { ps[y2].onclick = function () { select(parseInt(this.getAttribute('data-rule'), 10)); }; }
		}
		var ns = layer.getElementsByClassName('mdfdNode');
		for (var y3 = 0; y3 < ns.length; y3++) {
			ns[y3].onclick = (function (nodeEl) {
				return function (e) {
					if (e.target.getAttribute('data-nodeport')) { return; }
					var sugId = nodeEl.getAttribute('data-suggestion');
					if (sugId !== null) { selectSuggestion(parseInt(sugId, 10)); return; }
					select(parseInt(nodeEl.getAttribute('data-rule'), 10));
				};
			})(ns[y3]);
		}
	}

	// ------------------------------------------------------------ selection + inspector
	function select(i) { selected = i; selectedField = null; selectedSuggestion = -1; focusField = null; armedConstant = false; var b = el('mdfdConstBtn'); if (b) { b.className = 'mdfdBtn'; } refreshAll(); }
	// designer lot: clicking a target FIELD row opens the field inspector (left: the field itself; right:
	// "Rules and checks on <field>") instead of the rule inspector - a rule/link is still reached by clicking
	// its own curve or transformation node (select(i) above, unchanged).
	function selectField(path) { selectedField = selectedField === path ? null : path; selected = -1; selectedSuggestion = -1; focusField = null; armedConstant = false; refreshAll(); }
	function selectSuggestion(i) { selectedSuggestion = i; selected = -1; selectedField = null; focusField = null; armedConstant = false; refreshAll(); }
	// port of the page's own pipeline splitter - see TransformationCatalogue#parsePipeline, which this
	// must keep reading the exact same way (the class Javadoc names this function as its reference).
	function stepsOf(pipeline) {
		var out = []; var s = pipeline || ''; var cur = '', depth = 0, quote = null;
		for (var i = 0; i < s.length; i++) {
			var c = s.charAt(i);
			if (quote) { cur += c; if (c === quote) { quote = null; } continue; }
			if (c === '\'' || c === '"') { quote = c; cur += c; continue; }
			if (c === '(') { depth++; } if (c === ')') { depth--; }
			if (c === '|' && depth === 0) { out.push(cur.trim()); cur = ''; continue; }
			cur += c;
		}
		if (cur.trim()) { out.push(cur.trim()); }
		var parsed = [];
		for (var j = 0; j < out.length; j++) { var m = /^([A-Za-z_][A-Za-z0-9_]*)\s*(?:\((.*)\))?\s*$/.exec(out[j]); parsed.push(m ? { name: m[1], args: m[2] === undefined ? null : m[2] } : { name: out[j], args: null }); }
		return parsed;
	}
	function joinSteps(steps) { var s = []; for (var i = 0; i < steps.length; i++) { s.push(steps[i].name + (steps[i].args === null ? '' : '(' + steps[i].args + ')')); } return s.join('|'); }
	function catalogueEntry(name) { for (var i = 0; i < M.transformations.length; i++) { if (M.transformations[i].name.toLowerCase() === name.toLowerCase()) { return M.transformations[i]; } } return null; }
	function hintFor(name) { var c = catalogueEntry(name); return c ? c.hint : name; }
	function syntaxFor(name) { var c = catalogueEntry(name); return c ? c.syntax : name; }
	// designer lot: which of the three inspectors is showing - a suggested-fix link, a target field
	// (guards/decisions), or an ordinary rule (the original, unchanged behaviour) - in that priority order
	// since at most one of selected/selectedField/selectedSuggestion is ever set at a time (select*/selectField
	// above all clear the other two).
	function renderInspector() {
		if (selectedSuggestion >= 0 && current && current.suggestions && selectedSuggestion < current.suggestions.length) { renderSuggestionInspector(); return; }
		if (selectedField) { renderFieldInspector(); return; }
		renderRuleInspector();
	}
	// designer lot, delta 3: a short, purely client-side reason string for a flagged rule - built from the
	// plain numbers the server sent (staging.AttentionRules, no vocabulary of its own by design), never a
	// column/attribute name. Empty for a rule with no attention flag.
	function attentionReason(r) {
		if (!r || !r.attention) { return ''; }
		if (r.attention === 'CONSTANT_COLUMN') { return t('designer.attention.constant', r.attentionFilledRows == null ? '?' : r.attentionFilledRows); }
		if (r.attention === 'EMPTY_COLUMN') { return t('designer.attention.empty'); }
		if (r.attention === 'DUPLICATE_FIELD') { return t('designer.attention.duplicate'); }
		return '';
	}
	function renderRuleInspector() {
		var body = el('mdfdInsBody'); var head = el('mdfdInsTitle');
		if (selected < 0 || selected >= rules.length) {
			head.textContent = t('designer.inspector'); body.innerHTML = '<div class="mdfdInsEmpty">' + esc(t('designer.inspector.empty')) + '</div>'; return;
		}
		var r = rules[selected]; head.textContent = t('designer.inspector') + ' #' + (selected + 1);
		var ro = readOnly();
		var steps = stepsOf(r.transformation); var h = [];
		if (r.attention) { h.push('<div class="mdfdAttnNote">&#9888; ' + esc(attentionReason(r)) + '</div>'); }
		h.push('<div class="mdfdFlow"><span class="mdfdChip mdfdSrc"><code>' + esc(r.source || '(' + t('designer.rule.constant') + ')') + '</code></span><span class="mdfdArrow">&#10140;</span>');
		for (var i = 0; i < steps.length; i++) {
			h.push('<span class="mdfdStep" title="' + esc(hintFor(steps[i].name)) + '"><b>' + esc(steps[i].name) + '</b>'
				+ (steps[i].args !== null ? '(<input type="text" data-step="' + i + '" value="' + esc(steps[i].args) + '" aria-label="' + esc(t('designer.kbd.stepArgs', steps[i].name)) + '"' + (ro ? ' disabled="disabled"' : '') + '/>)' : '')
				+ (ro ? '' : '<a data-del="' + i + '" role="button" tabindex="0" title="' + esc(t('designer.step.remove')) + '" aria-label="' + esc(t('designer.step.remove') + ' ' + steps[i].name) + '">&#10005;</a>') + '</span><span class="mdfdStepArrow">&#8250;</span>');
		}
		if (!ro) {
			var opts = '<option value="">' + esc(t('designer.step.add')) + '...</option>';
			for (var o = 0; o < M.transformations.length; o++) { opts += '<option value="' + esc(syntaxFor(M.transformations[o].name)) + '" title="' + esc(M.transformations[o].hint) + '">' + esc(syntaxFor(M.transformations[o].name)) + '</option>'; }
			h.push('<select class="mdfdAddStep" id="mdfdAddStep" aria-label="' + esc(t('designer.step.add')) + '">' + opts + '</select>');
		}
		h.push('<span class="mdfdArrow">&#10140;</span><span class="mdfdChip mdfdDst"><code>' + esc(r.target) + '</code></span></div>');
		h.push('<div class="mdfdGrid">');
		if (!r.source) { h.push('<label for="mdfdConstVal">' + esc(t('designer.value')) + '</label><input type="text" id="mdfdConstVal" value="' + esc(constantOf(r.transformation)) + '"' + (ro ? ' disabled="disabled"' : '') + '/>'); }
		h.push('<label for="mdfdRaw">' + esc(t('designer.pipeline.raw')) + '</label><input type="text" id="mdfdRaw" value="' + esc(r.transformation) + '" placeholder="trim|left(80)"' + (ro ? ' disabled="disabled"' : '') + '/>');
		h.push('</div><div class="mdfdHint">' + esc(r.source ? t('designer.pipeline') : t('designer.constantHint')) + '</div>');
		if (!ro) { h.push('<div class="mdfdInsFoot"><button type="button" class="mdfdBtn mdfdDanger" id="mdfdDelRule">' + esc(t('designer.rule.delete')) + '</button></div>'); }
		body.innerHTML = h.join('');
		if (ro) { return; }
		var inputs = body.querySelectorAll('input[data-step]');
		for (var s = 0; s < inputs.length; s++) {
			inputs[s].oninput = function () { var st = stepsOf(r.transformation); st[parseInt(this.getAttribute('data-step'), 10)].args = this.value; r.transformation = joinSteps(st); el('mdfdRaw').value = r.transformation; sync(true); drawAll(); renderTable(); };
		}
		var dels = body.querySelectorAll('a[data-del]'); // role="button": Enter/Space reach them through the root keydown handler (audit F-45)
		for (var d = 0; d < dels.length; d++) { dels[d].onclick = function () { var st = stepsOf(r.transformation); st.splice(parseInt(this.getAttribute('data-del'), 10), 1); r.transformation = joinSteps(st); sync(true); refreshAll(); }; }
		el('mdfdAddStep').onchange = function () { if (!this.value) { return; } r.transformation = r.transformation ? r.transformation + '|' + this.value : this.value; sync(true); refreshAll(); };
		el('mdfdRaw').oninput = function () { r.transformation = this.value; sync(true); drawAll(); renderTable(); };
		el('mdfdRaw').onchange = function () { refreshAll(); };
		var cv = el('mdfdConstVal'); if (cv) { cv.oninput = function () { r.transformation = withConstant(r.transformation, this.value); el('mdfdRaw').value = r.transformation; sync(true); drawAll(); renderTable(); }; }
		el('mdfdDelRule').onclick = function () { rules.splice(selected, 1); selected = -1; sync(true); invalidateTrees(); refreshAll(); };
	}
	function constantOf(pipeline) { var m = /constant\((.*?)\)/.exec(pipeline || ''); return m ? m[1] : ''; }
	function withConstant(pipeline, value) { var st = stepsOf(pipeline); var found = false; for (var i = 0; i < st.length; i++) { if (st[i].name === 'constant') { st[i].args = value; found = true; } } if (!found) { st.unshift({ name: 'constant', args: value }); } return joinSteps(st); }

	// ------------------------------------------------------------ field inspector (designer lot, delta 2)
	// Two columns: left = the target field itself (name/type/status/what feeds it on THIS source link, in
	// the module's own vocabulary - PLAN.md's "source link"/"field", never "TransferFeed"/"attribute" in
	// user-facing text); right = its rules and checks, each with an immediate accept/reject/severity decision
	// (postDecision - never part of the Save/dirty cycle).
	function targetFieldOf(path) { var fields = fieldsOf('R'); for (var i = 0; i < fields.length; i++) { if (fields[i].path === path) { return fields[i]; } } return null; }
	function rulesFeedingTarget(path) { var out = []; for (var i = 0; i < rules.length; i++) { if (rules[i].target === path) { out.push(rules[i]); } } return out; }
	// ebx.GuardWrites: QUALITY writes a validation SEVERITY (WARN/REJECT/MODEL_CONSTRAINT - a value already
	// meaningful on its own, kept as-is); SCRIPT_CONSTRAINT writes a TRANSFER POLICY, where WARN/REJECT mean
	// exactly "flag the record" / "stop the row before the master" (task brief's own wording) - a different
	// label set for the same two codes.
	function severityOptionLabel(nature, v) {
		if (v === '') { return t('designer.guard.notSet'); }
		if (nature === 'SCRIPT_CONSTRAINT') { return v === 'WARN' ? t('designer.guard.flag') : (v === 'REJECT' ? t('designer.guard.stop') : v); }
		return t('designer.guard.severity.' + v) || v;
	}
	function severityOptionHtml(guard) {
		if (!guard.severityOptions || !guard.severityOptions.length) { return ''; }
		var opts = [];
		for (var i = 0; i < guard.severityOptions.length; i++) {
			var v = guard.severityOptions[i];
			var label = severityOptionLabel(guard.nature, v);
			opts.push('<option value="' + esc(v) + '"' + (v === (guard.severity || '') ? ' selected="selected"' : '') + '>' + esc(label) + '</option>');
		}
		return '<select class="mdfdGuardSeverity" aria-label="' + esc(t('designer.guard.whenFails') + ' - ' + (guard.label || '')) + '" data-guard-nature="' + esc(guard.nature) + '" data-guard-id="' + esc(guard.id) + '">' + opts.join('') + '</select>';
	}
	function renderFieldInspector() {
		var body = el('mdfdInsBody'); var head = el('mdfdInsTitle');
		var field = targetFieldOf(selectedField);
		if (!field) { head.textContent = t('designer.inspector'); body.innerHTML = '<div class="mdfdInsEmpty">' + esc(t('designer.inspector.empty')) + '</div>'; return; }
		head.textContent = field.label || field.path;
		var ro = readOnly();
		var feeding = rulesFeedingTarget(field.path);
		var h = ['<div class="mdfdFieldInspector">'];
		h.push('<div class="mdfdFieldCol">');
		h.push('<div class="mdfdFieldRow"><label>' + esc(t('designer.field.name')) + '</label><span>' + esc(field.path) + '</span></div>');
		h.push('<div class="mdfdFieldRow"><label>' + esc(t('designer.field.type')) + '</label><span>' + esc(field.type || '') + '</span></div>');
		h.push('<div class="mdfdFieldRow"><label>' + esc(t('designer.field.status')) + '</label><span>' + esc(field.status || '') + '</span></div>');
		h.push('<div class="mdfdFieldRow mdfdFieldSources"><label>' + esc(t('designer.field.sourceLinks')) + '</label>');
		if (feeding.length) {
			for (var fi = 0; fi < feeding.length; fi++) {
				var fr = feeding[fi];
				h.push('<div class="mdfdFieldSource"><code>' + esc(fr.source || '(' + t('designer.rule.constant') + ')') + '</code>'
					+ (fr.transformation ? ' <span class="mdfdFieldSourcePipe">' + esc(fr.transformation) + '</span>' : '') + '</div>');
			}
		} else {
			h.push('<div class="mdfdInsEmpty">' + esc(t('designer.field.noSource')) + '</div>');
		}
		h.push('</div></div>');
		h.push('<div class="mdfdFieldCol mdfdFieldColGuards">');
		h.push('<div class="mdfdFieldGuardsHead"><span class="mdfdNewTag">' + esc(t('designer.field.newTag')) + '</span>' + esc(t('designer.field.inspector', field.label || field.path)) + '</div>');
		if (field.guards && field.guards.length) {
			for (var gi2 = 0; gi2 < field.guards.length; gi2++) {
				var guard = field.guards[gi2];
				h.push('<div class="mdfdGuardCard">');
				h.push('<div class="mdfdGuardCardHead"><span class="mdfdGuardBadge ' + (guard.check ? 'mdfdGuardCheck' : 'mdfdGuardRule') + '">'
					+ esc(guard.check ? t('designer.guard.check') : t('designer.guard.rule')) + '</span><b>' + esc(guard.label) + '</b><span class="mdfdGuardStatus">' + esc(guard.status) + '</span></div>');
				if (guard.rationale) { h.push('<div class="mdfdGuardRationale">' + esc(guard.rationale) + '</div>'); }
				if (guard.severityOptions && guard.severityOptions.length) {
					h.push('<div class="mdfdGuardRow"><label>' + esc(t('designer.guard.whenFails')) + '</label>' + (ro ? esc(guard.severity || t('designer.guard.notSet')) : severityOptionHtml(guard)) + '</div>');
				}
				if (!ro) {
					h.push('<div class="mdfdGuardActions"><button type="button" class="mdfdBtn" data-guard-accept="' + gi2 + '">' + esc(t('designer.guard.accept')) + '</button>'
						+ '<button type="button" class="mdfdBtn mdfdDanger" data-guard-reject="' + gi2 + '">' + esc(t('designer.guard.reject')) + '</button></div>');
				}
				h.push('</div>');
			}
		} else {
			h.push('<div class="mdfdInsEmpty">' + esc(t('designer.guard.none')) + '</div>');
		}
		h.push('</div></div>');
		body.innerHTML = h.join('');
		if (ro) { return; }
		var selects = body.querySelectorAll('.mdfdGuardSeverity');
		for (var s = 0; s < selects.length; s++) {
			selects[s].onchange = function () { postDecision(this.getAttribute('data-guard-nature'), this.getAttribute('data-guard-id'), 'SET_SEVERITY', this.value); };
		}
		var accepts = body.querySelectorAll('[data-guard-accept]');
		for (var a = 0; a < accepts.length; a++) {
			accepts[a].onclick = function () { var g = field.guards[parseInt(this.getAttribute('data-guard-accept'), 10)]; postDecision(g.nature, g.id, 'ACCEPT'); };
		}
		var rejects = body.querySelectorAll('[data-guard-reject]');
		for (var rj = 0; rj < rejects.length; rj++) {
			rejects[rj].onclick = function () { var g = field.guards[parseInt(this.getAttribute('data-guard-reject'), 10)]; postDecision(g.nature, g.id, 'REJECT'); };
		}
	}

	// ------------------------------------------------------------ suggestion inspector (designer lot, delta 4)
	function renderSuggestionInspector() {
		var body = el('mdfdInsBody'); var head = el('mdfdInsTitle');
		var s = current.suggestions[selectedSuggestion];
		head.textContent = (s.source || '') + ' → ' + s.target;
		var ro = readOnly();
		var h = [];
		if (s.replacesSource) { h.push('<div class="mdfdSuggestionReplaces">' + esc(t('designer.suggestion.replaces', s.replacesSource)) + '</div>'); }
		if (s.rationale) { h.push('<div class="mdfdGuardRationale"><label>' + esc(t('designer.suggestion.rationale')) + '</label> ' + esc(s.rationale) + '</div>'); }
		if (s.transformation) { h.push('<div class="mdfdFieldSourcePipe">' + esc(s.transformation) + '</div>'); }
		if (!ro) {
			h.push('<div class="mdfdGuardActions"><button type="button" class="mdfdBtn" id="mdfdSuggestionApply" style="border-color:var(--mdfd-ok);color:var(--mdfd-ok)">'
				+ esc(t('designer.suggestion.apply')) + '</button><button type="button" class="mdfdBtn" id="mdfdSuggestionKeep">' + esc(t('designer.suggestion.keep')) + '</button></div>');
		}
		body.innerHTML = h.join('');
		if (ro) { return; }
		el('mdfdSuggestionApply').onclick = function () { postDecision('MAPPING', s.id, 'ACCEPT'); };
		el('mdfdSuggestionKeep').onclick = function () { postDecision('MAPPING', s.id, 'REJECT'); };
	}

	// ------------------------------------------------------------ table view
	// A resolved cell renders as before; an unresolved one (fieldExists false - robustness fix, see
	// fieldExists/isUnresolved) gets the mdfdUnresolvedVal class and a tooltip instead of ever being drawn as
	// a dangling canvas link - the table view is where such a rule is still fully visible and selectable.
	function tableCell(path, unresolved, placeholder) {
		var text = esc(path || placeholder);
		return unresolved ? '<code class="mdfdUnresolvedVal" title="' + esc(t('designer.unresolvedField')) + '">' + text + '</code>' : '<code>' + text + '</code>';
	}
	function renderTable() {
		var tb = el('mdfdTable'); var h = ['<tr><th>#</th><th>' + esc(t('designer.rule.source')) + '</th><th>' + esc(t('designer.rule.transformation')) + '</th><th>' + esc(t('designer.rule.target')) + '</th></tr>'];
		for (var i = 0; i < rules.length; i++) {
			var r = rules[i];
			var srcUnresolved = !!r.source && !fieldExists('L', r.source);
			var tgtUnresolved = !fieldExists('R', r.target);
			var rowCls = (i === selected ? 'mdfdSel' : '') + ((srcUnresolved || tgtUnresolved) ? ' mdfdRowUnresolved' : '') + (r.attention ? ' mdfdRowAttn' : '');
			var attnMark = r.attention ? '<span class="mdfdAttnMark" title="' + esc(attentionReason(r)) + '">&#9888;</span> ' : '';
			h.push('<tr data-rule="' + i + '" class="' + rowCls + '"><td>' + (i + 1) + '</td><td>' + tableCell(r.source, srcUnresolved, '(' + t('designer.rule.constant') + ')')
				+ '</td><td><code>' + esc(r.transformation) + '</code></td><td>' + attnMark + tableCell(r.target, tgtUnresolved, '') + '</td></tr>');
		}
		if (!rules.length) { h.push('<tr><td colspan="4" class="mdfdInsEmpty">' + esc(t('designer.noRules')) + '</td></tr>'); }
		tb.innerHTML = h.join('');
		var trs = tb.getElementsByTagName('tr');
		for (var j = 1; j < trs.length; j++) { trs[j].onclick = function () { var v = this.getAttribute('data-rule'); if (v !== null) { select(parseInt(v, 10)); } }; }
	}

	// ------------------------------------------------------------ drag and drop
	var drag = null; // {fromSide:'L', path} or {node: ruleIndex}
	function tempPath(x1, y1, x2, y2) { var svg = el('mdfdSvg'); var old = el('mdfdTempPath'); if (old) { old.parentNode.removeChild(old); } var p = document.createElementNS('http://www.w3.org/2000/svg', 'path'); p.setAttribute('id', 'mdfdTempPath'); p.setAttribute('class', 'mdfdTemp'); p.setAttribute('d', curve(x1, y1, x2, y2)); svg.appendChild(p); }
	function endDrag() { var old = el('mdfdTempPath'); if (old) { old.parentNode.removeChild(old); } var rows = root.querySelectorAll('.mdfdDropOk'); for (var i = 0; i < rows.length; i++) { rows[i].className = rows[i].className.replace(' mdfdDropOk', ''); } drag = null; }
	function addRule(source, target, transformation) {
		for (var i = 0; i < rules.length; i++) { if (rules[i].target === target && source) { rules[i].source = source; selected = i; sync(true); invalidateTrees(); refreshAll(); return; } }
		rules.push({ id: 0, source: source || '', target: target, transformation: transformation || '' });
		selected = rules.length - 1; sync(true); invalidateTrees(); refreshAll();
	}
	function invalidateTrees() { trees.L = null; trees.R = null; }
	root.addEventListener('mousedown', function (e) {
		if (readOnly()) { return; }
		var port = e.target; if (!port.getAttribute) { return; }
		if (port.getAttribute('data-port') === 'L') { drag = { fromSide: 'L', path: port.getAttribute('data-path') }; e.preventDefault(); }
		else if (port.getAttribute('data-nodeport') !== null && port.getAttribute('data-nodeport') !== undefined && port.className.indexOf('mdfdOut') >= 0) { drag = { node: parseInt(port.getAttribute('data-nodeport'), 10) }; e.preventDefault(); }
	});
	document.addEventListener('mousemove', function (e) {
		if (!drag) { return; }
		var work = el('mdfdWork').getBoundingClientRect(); var from;
		if (drag.fromSide) { from = anchor('L', drag.path); } else { var nd = el('mdfdNodes').querySelector('.mdfdNode[data-rule="' + drag.node + '"]'); var nr = nd.getBoundingClientRect(); from = { x: nr.right - work.left, y: nr.top + nr.height / 2 - work.top }; }
		tempPath(from.x, from.y, e.clientX - work.left, e.clientY - work.top);
		var rows = root.querySelectorAll('.mdfdDropOk'); for (var i = 0; i < rows.length; i++) { rows[i].className = rows[i].className.replace(' mdfdDropOk', ''); }
		var row = rowAt(e.target, 'R'); if (row && row.className.indexOf('mdfdLocked') < 0) { row.className += ' mdfdDropOk'; }
	});
	document.addEventListener('mouseup', function (e) {
		if (!drag) { return; }
		var row = rowAt(e.target, 'R'); var d = drag; endDrag();
		if (!row || row.className.indexOf('mdfdLocked') >= 0) { return; }
		var target = row.getAttribute('data-path'); if (!target) { return; }
		if (d.fromSide) { addRule(d.path, target, ''); }
		else { rules[d.node].target = target; selected = d.node; sync(true); invalidateTrees(); refreshAll(); }
	});
	function rowAt(node, side) { var n = node; while (n && n !== root) { if (n.getAttribute && n.getAttribute('data-side') === side && n.className.indexOf('mdfdRow') >= 0) { return n; } n = n.parentNode; } return null; }

	// ------------------------------------------------------------ clicks in trees
	root.addEventListener('click', function (e) {
		var tg = e.target; if (!tg.getAttribute) { return; }
		if (tg.getAttribute('data-port')) { return; }
		var row = rowAt(tg, 'L') || rowAt(tg, 'R'); if (!row) { return; }
		var path = row.getAttribute('data-path'); var sd = row.getAttribute('data-side');
		if (!path) { return; }
		var isLocked = row.className.indexOf('mdfdLocked') >= 0;
		if (sd === 'R' && isLocked) { return; } // technical key or link attribute - never a rule target
		if (!readOnly() && sd === 'R' && armedConstant) { armedConstant = false; el('mdfdConstBtn').className = 'mdfdBtn'; addRule('', path, 'constant()'); return; }
		if (!readOnly() && sd === 'R' && focusField && focusField.side === 'L') { var from = focusField.path; addRule(from, path, ''); announce(t('designer.kbd.ruleCreated', from, path)); return; }
		// designer lot: a target field row now opens the FIELD inspector (guards, accept/reject/severity) -
		// even read-only (viewing is always allowed; postDecision refuses to post while read-only) - a rule's
		// own link/transformation node is still reached by clicking it on the canvas (select(i), unchanged).
		if (sd === 'R') { selectField(path); return; }
		if (readOnly()) { return; } // the L-side highlight below is a drag-drop aid only, not useful read-only
		focusField = focusField && focusField.side === sd && focusField.path === path ? null : { side: sd, path: path }; selected = -1; selectedField = null; selectedSuggestion = -1;
		announce(focusField ? t('designer.kbd.sourcePicked', path) : '');
		renderTree('L'); renderTree('R'); drawAll(); renderInspector(); renderTable();
	});

	// ------------------------------------------------------------ feed selector
	// fix/designer-all-sources (2026-10-19): the page carries every application feeding the business object
	// (FeedDesignerUserService#pageFeeds), so the selector has two rows - "Application": one pill per root feed
	// (source system + landing table), "Fills": the chosen application's root feed and its child feeds
	// ("Personne", "Adresse - MAILING"...). A child always reads the SAME landing table as its parent
	// (docs/TARGET-MAPPING.md sec 4.2), so its pill only needs its entity name and variant.
	function rootIndexOf(i) {
		var f = feeds[i]; var guard = 0;
		while (f && f.parentFeedId && byId[f.parentFeedId] && guard++ < 50) { f = byId[f.parentFeedId]; }
		for (var k = 0; k < feeds.length; k++) { if (feeds[k] === f) { return k; } }
		return i;
	}
	function sourceLabel(f) { return f.sourceSystem + ' ' + f.landingTableName; }
	function feedLabel(f) {
		if (!f.parentFeedId) { return f.entityName; }
		return f.entityName + ' - ' + (f.variant ? f.variant : t('designer.child'));
	}
	function feedPill(i, label, extraCls, active) {
		var f = feeds[i];
		var cls = 'mdfdFeedItem' + extraCls + (active ? ' mdfdOn' : '') + (f.status === 'REJECTED' ? ' mdfdFeedRejected' : '');
		return '<a class="' + cls + '" data-feed="' + i + '" role="button" tabindex="0"' + (active ? ' aria-current="true"' : '') + ' title="' + esc(f.status + ' - ' + f.origin) + '">' + esc(label) + '</a>';
	}
	function renderFeedBar() {
		var activeRoot = rootIndexOf(currentIndex);
		var h = ['<div class="mdfdFeedList">'];
		h.push('<div class="mdfdFeedRow"><span class="mdfdFeedRowLabel">' + esc(t('designer.bar.sources')) + '</span>');
		for (var i = 0; i < feeds.length; i++) {
			if (!feeds[i].parentFeedId) { h.push(feedPill(i, sourceLabel(feeds[i]), ' mdfdFeedSource', i === activeRoot)); }
		}
		h.push('</div>');
		h.push('<div class="mdfdFeedRow"><span class="mdfdFeedRowLabel">' + esc(t('designer.bar.feeds')) + '</span>');
		for (var j = 0; j < feeds.length; j++) {
			if (rootIndexOf(j) === activeRoot) { h.push(feedPill(j, feedLabel(feeds[j]), feeds[j].parentFeedId ? ' mdfdFeedChild' : '', j === currentIndex)); }
		}
		h.push('</div></div>');
		return h.join('');
	}
	// designer lot, delta 5: Matrix | Designer segmented control - Designer is always the active one on this
	// page (there is no client-side toggle: Matrix is a full-page departure to a different UserService, see
	// core.MappingMatrixLink/FeedDesignerUserService#feedModel's own "matrixUrl"). A plain-text (non-link)
	// "Designer" segment when matrixUrl is empty (project code could not be resolved - server-side note) so
	// the control never renders a dead link.
	// fix/designer-cosmetics (2026-09-24, live screenshot): the zoom control and the fullscreen button moved
	// here from the main toolbar (build()'s own .mdfdBar), which wrapped the two onto a lone second line at
	// the ~620px UserServicePane width this page normally runs at - this bar (Matrix|Designer) has plenty of
	// spare room. Ids/handlers are unchanged (build() still wires #mdfdZoomIn/#mdfdZoomOut/#mdfdFullBtn by id
	// after innerHTML, regardless of which bar the elements end up in); the elements are rendered even with no
	// current feed (an empty feed list), same posture as before this move, via the .mdfdSp spacer pushing them
	// to this bar's right end.
	function renderModeSwitch() {
		var h = ['<div class="mdfdBar mdfdModeBar">'];
		if (current) {
			h.push('<span class="mdfdSeg">');
			if (current.matrixUrl) { h.push('<a class="mdfd-designer-matrix-link" target="_top" href="' + esc(current.matrixUrl) + '">' + esc(t('designer.mode.matrix')) + '</a>'); }
			else { h.push('<span>' + esc(t('designer.mode.matrix')) + '</span>'); }
			h.push('<span class="mdfdOn">' + esc(t('designer.mode.designer')) + '</span></span>');
		}
		h.push('<span class="mdfdSp"></span>');
		h.push('<span class="mdfdZoom"><button type="button" class="mdfdBtn" id="mdfdZoomOut" title="zoom -">&#8722;</button><span id="mdfdZoomPct">100%</span><button type="button" class="mdfdBtn" id="mdfdZoomIn" title="zoom +">&#43;</button></span>');
		h.push('<button type="button" class="mdfdBtn" id="mdfdFullBtn" title="' + esc(t('designer.fullscreen')) + '">&#x26F6;</button>');
		h.push('</div>');
		return h.join('');
	}
	function renderFeedMeta() {
		if (!current) { return ''; }
		var ro = readOnly();
		var h = ['<div class="mdfdFeedMeta">'];
		if (current.parentFeedId) {
			h.push('<span class="mdfdFeedMetaLabel">' + esc(t('designer.childOf', current.parentEntityName)) + '</span>');
			h.push('<span class="mdfdFeedMetaLabel">' + esc(t('designer.parentLinkField')) + ' <code>' + esc(current.parentLinkField) + '</code></span>');
		}
		h.push('<label for="mdfdSourceIdPipeline">' + esc(t('designer.sourceIdPipeline')) + '</label>');
		h.push('<input type="text" id="mdfdSourceIdPipeline" value="' + esc(current.sourceIdPipeline || '') + '"' + (ro ? ' disabled="disabled"' : '') + ' placeholder="' + esc(t('designer.sourceIdPipeline.placeholder')) + '"/>');
		if (current.status === 'REJECTED') { h.push('<span class="mdfdFeedMetaWarn">' + esc(t('designer.feedRejected')) + '</span>'); }
		else if (M.readOnly) { h.push('<span class="mdfdFeedMetaWarn">' + esc(t('designer.readOnly')) + '</span>'); }
		h.push('</div>');
		return h.join('');
	}
	function switchFeed(i) {
		if (i < 0 || i >= feeds.length || i === currentIndex) { return; }
		currentIndex = i; current = feeds[currentIndex]; rules = current.rules;
		selected = -1; focusField = null; armedConstant = false; invalidateTrees();
		build();
	}

	// ------------------------------------------------------------ page
	function build() {
		var h = [];
		h.push(renderFeedBar());
		h.push(renderModeSwitch());
		h.push(renderFeedMeta());
		h.push('<div class="mdfdBar">');
		if (!readOnly()) { h.push('<button type="button" class="mdfdBtn" id="mdfdConstBtn">&#65291; ' + esc(t('designer.addConstant')) + '</button>'); }
		// "add a target field" lot (docs/FEED-DESIGNER.md): opens the small inline form below, next to
		// "+ Constant value" - hidden in read-only mode, same gate as the constant button.
		if (!readOnly()) { h.push('<button type="button" class="mdfdBtn" id="mdfdAddFieldBtn">&#65291; ' + esc(t('designer.addTargetField')) + '</button>'); }
		h.push('<span class="mdfdSp"></span>');
		h.push('<span class="mdfdDirty" id="mdfdDirty">&#9679; ' + esc(t('designer.unsaved')) + '</span>');
		h.push('<label class="mdfdToggle"><input type="checkbox" id="mdfdHide"/>' + esc(t('designer.hideUnmapped')) + '</label>');
		h.push('<span><span class="mdfdBadgeCount" id="mdfdRuleCount">0</span> ' + esc(t('designer.rules')) + '</span>');
		if (suggestionsOf().length) { h.push('<span class="mdfdBadgeCount mdfdBadgeSug">' + suggestionsOf().length + '</span>'); }
		h.push('<span class="mdfdWarn" id="mdfdUnresolvedWarn" style="display:none" title="' + esc(t('designer.unresolvedField')) + '"></span>');
		h.push('<span class="mdfdWarn" id="mdfdAttnWarn" style="display:none"></span>');
		h.push('<span class="mdfdSeg"><button type="button" id="mdfdViewD" class="mdfdOn">' + esc(t('designer.viewDiagram')) + '</button><button type="button" id="mdfdViewT">' + esc(t('designer.viewTable')) + '</button></span>');
		h.push('</div>');
		// "add a target field" lot: a small inline form, closed by default, toggled by mdfdAddFieldBtn above -
		// name/label/type/mandatory, "Create"/"Cancel" and an inline error line (client-side checks only; the
		// server re-checks everything again, see FeedDesignerUserService#onAddTargetField).
		if (!readOnly()) {
			h.push('<div class="mdfdAddFieldForm" id="mdfdAddFieldForm" style="display:none">');
			// fix/designer-cosmetics (2026-09-24, live screenshot): each label+control is wrapped in its own
			// non-splitting <span class="mdfdAddFieldPair"> - EBX's own stylesheet stretches a bare text input to
			// 100% of the flex row's remaining width, which used to push every following label/control onto a
			// wrapped line of its own ("Name [.........] Label" / "[.......] Type" / ...). The pair wrapper plus
			// explicit widths on the two text inputs and the select (feed-designer.css) keep each label glued to
			// its own control; Create/Cancel/the error line stay grouped in one trailing span.
			h.push('<span class="mdfdAddFieldPair"><label for="mdfdAddFieldName">' + esc(t('designer.targetField.name')) + '</label><input type="text" id="mdfdAddFieldName" maxlength="80"/></span>');
			h.push('<span class="mdfdAddFieldPair"><label for="mdfdAddFieldLabel">' + esc(t('designer.targetField.labelField')) + '</label><input type="text" id="mdfdAddFieldLabel" maxlength="120"/></span>');
			h.push('<span class="mdfdAddFieldPair"><label for="mdfdAddFieldType">' + esc(t('designer.targetField.type')) + '</label><select id="mdfdAddFieldType">');
			for (var dt = 0; dt < DATA_TYPES.length; dt++) { h.push('<option value="' + DATA_TYPES[dt] + '">' + DATA_TYPES[dt] + '</option>'); }
			h.push('</select></span>');
			h.push('<span class="mdfdAddFieldPair"><label class="mdfdToggle"><input type="checkbox" id="mdfdAddFieldMandatory"/>' + esc(t('designer.targetField.mandatory')) + '</label></span>');
			h.push('<span class="mdfdAddFieldActions">');
			h.push('<button type="button" class="mdfdBtn" id="mdfdAddFieldCreate">' + esc(t('designer.targetField.create')) + '</button>');
			h.push('<button type="button" class="mdfdBtn" id="mdfdAddFieldCancel">' + esc(t('designer.targetField.cancel')) + '</button>');
			h.push('<span class="mdfdAddFieldErr" id="mdfdAddFieldErr"></span>');
			h.push('</span>');
			h.push('</div>');
		}
		// audit F-45: how to create a rule without a mouse, and a polite live region that says what a key press did
		if (!readOnly()) { h.push('<div class="mdfdKbdHint" id="mdfdKbdHint">' + esc(t('designer.kbd.hint')) + '</div>'); }
		h.push('<div class="mdfdSrOnly" id="mdfdLive" aria-live="polite"></div>');
		h.push('<div class="mdfdWork" id="mdfdWork">');
		h.push(pane('L', t('designer.source'), current ? current.sourceSystem + ' ' + current.landingTableName : ''));
		h.push('<div class="mdfdCanvas" id="mdfdCanvas"><div class="mdfdCanvasHint" id="mdfdCanvasHint">' + esc(t('designer.inspector.empty')) + '</div></div>');
		h.push(pane('R', t('designer.target'), current ? current.entityName : M.entityName));
		h.push('<svg class="mdfdSvg" id="mdfdSvg"></svg><div class="mdfdNodesLayer" id="mdfdNodes"></div>');
		h.push('</div>');
		h.push('<table class="mdfdTable" id="mdfdTable"></table>');
		h.push('<div class="mdfdInspector"><div class="mdfdInsHead"><span id="mdfdInsTitle"></span><span class="mdfdSp"></span></div><div class="mdfdInsBody" id="mdfdInsBody"></div></div>');
		root.innerHTML = h.join('');
		var items = root.querySelectorAll('.mdfdFeedItem');
		for (var fi2 = 0; fi2 < items.length; fi2++) { items[fi2].onclick = function () { switchFeed(parseInt(this.getAttribute('data-feed'), 10)); }; }
		var sip = el('mdfdSourceIdPipeline'); if (sip) { sip.oninput = function () { current.sourceIdPipeline = this.value; sync(true); }; }
		var cb = el('mdfdConstBtn'); if (cb) { cb.onclick = function () { armedConstant = !armedConstant; this.className = 'mdfdBtn' + (armedConstant ? ' mdfdArmed' : ''); el('mdfdCanvasHint').style.display = armedConstant ? 'block' : (rules.length ? 'none' : 'block'); el('mdfdCanvasHint').textContent = armedConstant ? t('designer.pickTarget') : t('designer.inspector.empty'); }; }
		var afb = el('mdfdAddFieldBtn'); if (afb) { afb.onclick = function () { toggleAddFieldForm(); }; }
		var afc = el('mdfdAddFieldCreate'); if (afc) { afc.onclick = function () { submitAddField(); }; }
		var afx = el('mdfdAddFieldCancel'); if (afx) { afx.onclick = function () { closeAddFieldForm(); }; }
		el('mdfdHide').onchange = function () { hideUnmapped = this.checked; renderTree('L'); renderTree('R'); drawAll(); };
		el('mdfdZoomIn').onclick = function () { setScale(scale + 0.1); };
		el('mdfdZoomOut').onclick = function () { setScale(scale - 0.1); };
		el('mdfdFullBtn').onclick = function () { toggleFull(); };
		el('mdfdWork').addEventListener('wheel', function (e) { if (e.ctrlKey || e.metaKey) { e.preventDefault(); setScale(scale + (e.deltaY < 0 ? 0.1 : -0.1)); } }, { passive: false });
		el('mdfdViewD').onclick = function () { tableMode = false; viewMode(); };
		el('mdfdViewT').onclick = function () { tableMode = true; viewMode(); };
		el('mdfdSearchL').oninput = function () { filters.L = this.value; renderTree('L'); drawAll(); };
		el('mdfdSearchR').oninput = function () { filters.R = this.value; renderTree('R'); drawAll(); };
		el('mdfdTreeL').addEventListener('scroll', drawAll); el('mdfdTreeR').addEventListener('scroll', drawAll); window.addEventListener('resize', drawAll);
		if (window.ResizeObserver) { new ResizeObserver(function () { drawAll(); }).observe(el('mdfdWork')); }
		refreshAll();
	}
	function pane(side, title, name) {
		return '<div class="mdfdPane ' + (side === 'L' ? 'mdfdLeftPane' : 'mdfdRightPane') + '"><div class="mdfdPaneHead"><div class="mdfdPaneTitle">' + esc(title) + '<small title="' + esc(name) + '">' + esc(name) + '</small></div>'
			+ '<div class="mdfdSearch"><input type="text" id="mdfdSearch' + side + '" placeholder="' + esc(t('designer.search')) + '" aria-label="' + esc(t('designer.search') + ' - ' + title) + '"/><span aria-hidden="true">&#128269;</span></div>'
			+ '<div class="mdfdTreeTools"><span class="mdfdSp" style="flex:1"></span><span id="mdfdCount' + side + '"></span></div></div>'
			+ '<div class="mdfdTree" id="mdfdTree' + side + '"></div></div>';
	}
	var scale = 1;
	function setScale(v) { scale = Math.max(0.6, Math.min(1.8, Math.round(v * 10) / 10)); el('mdfdWork').style.setProperty('--mdfd-scale', scale); el('mdfdZoomPct').textContent = Math.round(scale * 100) + '%'; drawAll(); }
	function toggleFull() {
		var on = root.className.indexOf('mdfdFull') < 0;
		root.className = on ? 'mdfdFull' : '';
		el('mdfdFullBtn').className = 'mdfdBtn' + (on ? ' mdfdArmed' : '');
		drawAll();
	}
	document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && root.className.indexOf('mdfdFull') >= 0) { toggleFull(); } });
	// audit F-45: the designer with the keyboard - every element drawn with role="button" (tree rows, feed items, a step's remove cross)
	// acts on Enter or Space like a click, and keeps the focus where it was although the tree is redrawn; Escape drops a picked source.
	function announce(text) { var live = el('mdfdLive'); if (live) { live.textContent = text || ''; } }
	function refocus(side, path) {
		var rows = root.querySelectorAll('.mdfdRow[data-side="' + side + '"]');
		for (var i = 0; i < rows.length; i++) { if (rows[i].getAttribute('data-path') === path) { rows[i].focus(); return; } }
	}
	root.addEventListener('keydown', function (e) {
		var tg = e.target; if (!tg || !tg.getAttribute) { return; }
		if (e.key === 'Escape' && focusField && root.className.indexOf('mdfdFull') < 0) {
			var was = focusField; focusField = null; renderTree('L'); renderTree('R'); drawAll(); announce(''); refocus(was.side, was.path); return;
		}
		if ((e.key !== 'Enter' && e.key !== ' ') || tg.getAttribute('role') !== 'button' || tg.tagName === 'BUTTON') { return; }
		e.preventDefault();
		var side = tg.getAttribute('data-side'); var path = tg.getAttribute('data-path');
		tg.click();
		if (side && path) { refocus(side, path); }
	});
	function viewMode() { el('mdfdWork').className = 'mdfdWork' + (tableMode ? ' mdfdTableMode' : ''); el('mdfdTable').className = 'mdfdTable' + (tableMode ? ' mdfdOn' : ''); el('mdfdViewD').className = tableMode ? '' : 'mdfdOn'; el('mdfdViewT').className = tableMode ? 'mdfdOn' : ''; if (!tableMode) { drawAll(); } }
	function refreshAll() { renderTree('L'); renderTree('R'); renderTable(); renderInspector(); drawAll(); sync(false); }

	build();
})();
