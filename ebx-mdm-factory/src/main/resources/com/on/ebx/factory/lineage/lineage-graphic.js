// Embedded by RecordLineagePage.graphicScript: `root` is #mdmfLineageGraphicRoot, `data` is the JSON
// RecordLineageGraphic.build produced for THIS record (already redaction-safe - see that class's own
// Javadoc). Approach copied from the catalog module's own Journey view (ebx-data-catalog's journey.js /
// LineageJourneyPane.java): SVG built by hand with a small `el()` helper (createElementNS + textContent/
// setAttribute, never innerHTML with data), a fixed colour per node, a `fixUrl` perspective rewrite for
// "open in EBX"/"open in catalog" links, and a shared `interactive()` a11y click handler. Unlike the
// catalog's view, this screen never fetches more data over REST: RecordLineageReader's own read is already
// bounded (one row per contributing source), so everything the diagram can ever show is already in `data`.
//
// Coordinator review, 2026-09-22 (comparing docs/mockups/record-lineage.png against a rendered preview):
// this file was substantially reworked to fix nine visual gaps - the mockup's own fixed 1200-wide
// coordinate system (via build_record_lineage.py) drives the "parcours" layout below, a purple ribbon now
// runs from the matching ring to the golden card, the group caption moved to the band header with the
// state words centred under the ring, golden-card text now wraps instead of overflowing, cleaning chips
// show short human words and are capped at two-plus-N, the "champ par champ" table gained a real
// golden<->retained connector and box/badge styling, and a "N more sources"-style reveal only appears when
// actually needed.
(function(){
var NS='http://www.w3.org/2000/svg';
var GOLD='#684bc0'; // theme-fixed (audit F-44): the golden legend colour (SourceColorPalette.GOLDEN) and its tints
var L=data.labels||{};

function el(tag,attrs,parent,text){
	var e=document.createElementNS(NS,tag);
	Object.keys(attrs||{}).forEach(function(k){e.setAttribute(k,attrs[k]);});
	if(text!==undefined&&text!==null)e.textContent=text;
	if(parent)parent.appendChild(e);
	return e;
}
function h(tag,attrs,parent,text){
	var e=document.createElement(tag);
	Object.keys(attrs||{}).forEach(function(k){if(k==='class')e.className=attrs[k];else e.setAttribute(k,attrs[k]);});
	if(text!==undefined&&text!==null)e.textContent=text;
	if(parent)parent.appendChild(e);
	return e;
}
function fmt(t,args){return String(t||'').replace(/\{(\d+)\}/g,function(_,i){var v=args[+i];return v===undefined||v===null?'':v;});}
/** A legend/header dot: solid fill for a "solid"-style source, HOLLOW (outlined, transparent centre) for a "dashed"-style one past the palette's own size (coordinator review, 2026-09-22: "an outlined (hollow) legend dot"), so two same-coloured sources are still told apart at a glance. */
function paintDot(dotEl,color,style){
	if(style==='dashed'){dotEl.style.background='transparent';dotEl.style.border='2px solid '+color;}
	else{dotEl.style.background=color;dotEl.style.border='none';}
}
function interactive(e,cb){
	e.setAttribute('tabindex','0');
	e.setAttribute('role','button');
	e.style.cursor='pointer';
	e.addEventListener('click',cb);
	e.addEventListener('keydown',function(k){if(k.key==='Enter'||k.key===' '){k.preventDefault();cb();}});
}
// Reapply perspective routing to every "open in EBX"/"open in catalog" link (CLAUDE.md sect9's own
// documented technique, reused verbatim from the catalog module's journey.js).
var perspectivePath=/\/ui\/custom\/([^/]+)\//.exec(window.top.location.pathname);
function fixUrl(u){return u&&perspectivePath?u.replace('/ui/advanced/data/','/ui/custom/'+perspectivePath[1]+'/data/'):u;}
function openLink(u){if(u)window.top.location.href=fixUrl(u);}

/** Greedy word-wrap by character count (a simple, good-enough approximation for the fixed-size SVG text this screen draws - no canvas measureText dependency). Never returns an empty array. */
function wrapText(text,maxChars){
	var words=String(text||'').split(/\s+/).filter(Boolean);
	if(!words.length)return [''];
	var lines=[],cur='';
	words.forEach(function(w){
		var t=cur?cur+' '+w:w;
		if(t.length>maxChars&&cur){lines.push(cur);cur=w;}else{cur=t;}
	});
	if(cur)lines.push(cur);
	return lines;
}

var detailEl=root.querySelector('[data-mdmf-detail]');
var legendEl=root.querySelector('[data-mdmf-legend]');
var moreEl=root.querySelector('[data-mdmf-more-sources]');
var parcoursHost=root.querySelector('[data-mdmf-parcours]');
var fieldsHost=root.querySelector('[data-mdmf-fields]');
var fieldsNoteEl=root.querySelector('[data-mdmf-fields-note]');
var friseHost=root.querySelector('[data-mdmf-frise]');

function detail(text){if(detailEl)detailEl.textContent=text;}

// "Compare with suspects" panel (task brief, 2026-09-22, "decide on a suspect" lot): a native <details>
// RecordLineagePage#renderComparisonPanel already renders (opens on a plain click on its own <summary>, no
// script required - see that method's own Javadoc). This is a NICE-TO-HAVE on top of that native behaviour,
// never a replacement for it: clicking the matching ring or a suspect lane ALSO opens it programmatically
// and, for a specific suspect lane, scrolls its own column into view. Never throws if the panel is absent
// (no pending suspect at all - RecordLineagePage renders nothing then).
function openCompare(suspectKey){
	var panel=document.getElementById('mdmfCompare');
	if(!panel)return;
	panel.open=true;
	if(suspectKey){
		var cols=panel.querySelectorAll('[data-mdmf-compare-col]');
		for(var i=0;i<cols.length;i++){
			if(cols[i].getAttribute('data-mdmf-compare-col')===suspectKey&&cols[i].scrollIntoView){
				cols[i].scrollIntoView({behavior:'smooth',inline:'center',block:'nearest'});
				break;
			}
		}
	}
	if(panel.scrollIntoView)panel.scrollIntoView({behavior:'smooth',block:'nearest'});
}

var visibleCount=Math.max(1,Math.min(data.sources.length,data.visibleSourceCount||data.sources.length));
function visibleSources(){return data.sources.slice(0,visibleCount);}

// ------------------------------------------------------------------------------------------------- legend + reveal

function drawLegend(){
	if(!legendEl)return;
	legendEl.textContent='';
	var g=h('span',{class:'mdmf-legend-item'},legendEl);
	h('i',{class:'mdmf-legend-dot'},g).style.background=GOLD;
	g.appendChild(document.createTextNode(' '+(L.goldenLabel||'Golden record')));
	data.sources.forEach(function(s){
		var it=h('span',{class:'mdmf-legend-item'},legendEl);
		paintDot(h('i',{class:'mdmf-legend-dot'},it),s.color,s.style);
		it.appendChild(document.createTextNode(' '+(s.system||'')+(s.restricted?' 🔒':'')));
	});
}

function updateMoreControl(){
	if(!moreEl)return;
	moreEl.textContent='';
	var remaining=data.sources.length-visibleCount;
	if(remaining<=0){moreEl.hidden=true;return;}
	moreEl.hidden=false;
	var btn=h('button',{type:'button',class:'mdmf-btn mdmf-btn-sm'},moreEl,fmt(L.moreSources,[remaining]));
	btn.addEventListener('click',function(){visibleCount=data.sources.length;redraw();});
}

// ------------------------------------------------------------------------------------------------------ 1. parcours

// Fixed design-unit coordinate system (coordinator review, 2026-09-22: "compute band x/width ... as the
// mockup does" - build_record_lineage.py's own band x/widths, with the LANDING->STAGING gap widened from
// its own 85 to 130 so two cleaning chips plus a "+N" chip never overflow onto the staging card).
var BANDS=[
	{key:'SRC',label:'SOURCE SYSTEMS',x:0,w:215},
	{key:'LAND',label:'LANDING · RAW ROW',x:235,w:215},
	{key:'STAGE',label:'STAGING · CLEANED ROW',x:580,w:200},
	{key:'MATCH',label:'MATCHING',x:800,w:210},
	{key:'MASTER',label:'MASTER ZONE',x:1025,w:220}
];
var DESIGN_W=BANDS[4].x+BANDS[4].w+20;
var LANE_PITCH=85,LANE_FIRST=85,CARD_H=60;

function sourceDetail(s){
	if(s.restricted){detail((s.system||'')+': '+(L.locked||'hidden for your profile'));return;}
	var bits=[s.system,(s.sourceId||''),(s.landingTable||'')+' / '+(s.stagingTable||'')];
	if(s.receivedAtDisplay)bits.push(s.receivedAtDisplay);
	detail(bits.filter(Boolean).join(' · '));
}

/**
 * At most as many chips as fit in the gap (MEASURED with `getComputedTextLength`, not estimated - a chip
 * is drawn, measured while already in the DOM, and removed again if it would cross into the staging card),
 * then a "+N" chip whose title lists the rest (coordinator review, 2026-09-22: "never let a chip cross a
 * card"). `gapEnd` is the last x coordinate a chip's own right edge may reach - callers pass the staging
 * card's own left edge minus 8px.
 */
function drawCleaningChips(svg,steps,x0,gapEnd,laneY){
	var cx=x0,shown=0;
	for(var i=0;i<steps.length;i++){
		var step=steps[i],text=step.shortWords||step.kind;
		var probe=el('text',{x:'0',y:'0','font-size':'9','font-weight':'700'},svg,text);
		var textW=0;
		try{textW=probe.getComputedTextLength();}catch(e){textW=text.length*5.8;}
		probe.remove();
		var wid=Math.max(30,textW+16);
		var remaining=steps.length-i;
		// reserve room for a following "+N" chip too, unless this is the LAST step (nothing left to fold into one)
		var reserve=remaining>1?Math.max(24,(1+String(remaining-1).length)*7+14)+4:0;
		if(cx+wid+reserve>gapEnd){
			var restCount=steps.length-shown;
			var pw=Math.max(24,(1+String(restCount).length)*7+14);
			var plusChip=el('g',{},svg);
			el('rect',{x:cx,y:laneY-27,width:pw,height:17,rx:8.5,fill:'#f2f4f7',stroke:'#d0d5dd'},plusChip);
			el('text',{x:cx+pw/2,y:laneY-15,'font-size':'9','font-weight':'700','text-anchor':'middle',fill:'#475467'},plusChip,'+'+restCount);
			el('title',{},plusChip,steps.slice(shown).map(function(s){return s.shortWords||s.kind;}).join(', '));
			return;
		}
		var chip=el('g',{},svg);
		el('rect',{x:cx,y:laneY-27,width:wid,height:17,rx:8.5,fill:'#fff',stroke:step._color||'#667085'},chip);
		el('text',{x:cx+wid/2,y:laneY-15,'font-size':'9','font-weight':'700','text-anchor':'middle',fill:step._color||'#475467'},chip,text);
		el('title',{},chip,step.longWords||text);
		cx+=wid+4;
		shown++;
	}
}

function drawParcours(){
	if(!parcoursHost)return;
	parcoursHost.textContent='';
	var sources=visibleSources();
	// suspect lanes (task brief, 2026-09-22: "the PARCOURS must draw a lane for each SUSPECT record too") -
	// drawn IN ADDITION to the visible contributing lanes, never counted against visibleSourceCount/the "N
	// more sources" control (that budget is for CONFIRMED sources only).
	var lanes=sources.concat(data.suspectLanes||[]);
	var n=Math.max(1,lanes.length);
	var lastLaneY=LANE_FIRST+(n-1)*LANE_PITCH;
	var ringX=BANDS[3].x+BANDS[3].w/2,ringY=(LANE_FIRST+lastLaneY)/2;
	var goldX=BANDS[4].x+8,goldW=BANDS[4].w-16;

	// golden-card content is measured BEFORE the SVG height is fixed, since it can need more room than the
	// lanes alone (coordinator review, 2026-09-22: "texts overflow the card" - wrap first, size the card to
	// what it actually holds, then grow the canvas to fit rather than clipping).
	var compositionLines;
	if(data.composition.known){
		compositionLines=null; // drawn as a bar, not wrapped text - see drawGoldenCard
	}else{
		compositionLines=wrapText(fmt(L.compositionUnknown,[data.composition.sourceCount,data.composition.concordantCount,data.composition.differentCount]),30);
	}
	var proximityLines=wrapText(L.proximityUnavailable,30);
	var cardContentLines=(data.composition.known?2:compositionLines.length)+proximityLines.length;
	var cardH=118+cardContentLines*13;
	var cardY=Math.max(10,ringY-cardH/2);

	var H=Math.max(lastLaneY+80,cardY+cardH+20,ringY+70);
	var svg=el('svg',{viewBox:'0 0 '+DESIGN_W+' '+H,width:'100%',height:String(H),preserveAspectRatio:'none',role:'img','aria-label':'Journey diagram'},parcoursHost);
	var defs=el('defs',{},svg);
	var gradId='mdmfGoldGrad';
	var grad=el('linearGradient',{id:gradId,gradientUnits:'userSpaceOnUse',x1:String(ringX),y1:String(ringY),x2:String(goldX),y2:String(ringY)},defs);
	el('stop',{offset:'0','stop-color':GOLD,'stop-opacity':'.35'},grad);
	el('stop',{offset:'1','stop-color':GOLD,'stop-opacity':'.85'},grad);

	BANDS.forEach(function(b,i){
		el('rect',{x:String(b.x),y:'0',width:String(b.w),height:String(H-10),rx:'12',fill:i===4?'#f4f1fc':'#f8fafc'},svg); // theme-fixed (audit F-44): the golden legend colour (SourceColorPalette.GOLDEN) and its tints
		el('text',{x:String(b.x+12),y:'18','font-size':'9','font-weight':'700','letter-spacing':'.08em',fill:i===4?GOLD:'#667085'},svg,b.label);
	});
	// group caption at the TOP of the MATCHING band, left aligned (coordinator review, 2026-09-22) - never
	// floating over the ribbons the way a caption placed near the ring used to.
	var groupCaption=data.merge.present?(fmt(L.group,[data.merge.groupId])+' · '+data.merge.recordCount+(data.merge.recordCount===1?' record':' records')):'Not matched';
	el('text',{x:String(BANDS[3].x+12),y:'46','font-size':'10','font-weight':'700',fill:'#475467'},svg,groupCaption);

	lanes.forEach(function(s,i){
		var y=LANE_FIRST+i*LANE_PITCH;
		var color=s.restricted?'#98a2b3':s.color;
		// past the palette's own 12 entries, a lane REUSES a colour (SourceColorPalette#isExtended) - the
		// ribbon and lane bar are drawn dashed/hollow instead of solid so two lanes never look identical
		// even then (coordinator review, 2026-09-22: "preview-9src.png: SYS9 and SYS1 are both the same
		// blue"). A SUSPECT lane (kind==='suspect', task brief 2026-09-22) is drawn dashed too, whatever its
		// own style - a pending relation, not yet a confirmed contributor.
		var dashed=s.style==='dashed'||s.kind==='suspect';
		var dash=dashed?{'stroke-dasharray':'10 6'}:{};
		var p1='M '+(BANDS[0].x+BANDS[0].w-4)+' '+y+' L '+(BANDS[1].x+4)+' '+y;
		var p2='M '+(BANDS[1].x+BANDS[1].w-4)+' '+y+' L '+(BANDS[2].x+4)+' '+y;
		var bendX=(BANDS[2].x+BANDS[2].w+ringX)/2;
		var p3='M '+(BANDS[2].x+BANDS[2].w-4)+' '+y+' C '+bendX+' '+y+' '+bendX+' '+ringY+' '+(ringX-40)+' '+ringY;
		[p1,p2,p3].forEach(function(d){
			var attrs={d:d,stroke:color,'stroke-width':'10',fill:'none','stroke-opacity':s.restricted?'.35':'.55'};
			Object.keys(dash).forEach(function(k){attrs[k]=dash[k];});
			el('path',attrs,svg);
		});

		var srcNode=el('g',{},svg);
		el('rect',{x:String(BANDS[0].x+8),y:String(y-CARD_H/2),width:String(BANDS[0].w-16),height:String(CARD_H),rx:'9',fill:'#fff',stroke:color,'stroke-width':'1.5'},srcNode);
		if(dashed){
			el('rect',{x:String(BANDS[0].x+8),y:String(y-CARD_H/2),width:'5',height:String(CARD_H),rx:'2',fill:'none',stroke:color,'stroke-width':'2','stroke-dasharray':'4 3'},srcNode);
		}else{
			el('rect',{x:String(BANDS[0].x+8),y:String(y-CARD_H/2),width:'5',height:String(CARD_H),rx:'2',fill:color},srcNode);
		}
		el('text',{x:String(BANDS[0].x+20),y:String(y-10),'font-size':'8.5','font-weight':'700',fill:color},srcNode,'SOURCE SYSTEM');
		el('text',{x:String(BANDS[0].x+20),y:String(y+8),'font-size':'13','font-weight':'700',fill:'#172033'},srcNode,s.system||'');
		if(s.restricted){
			el('text',{x:String(BANDS[0].x+20),y:String(y+23),'font-size':'9.5',fill:'#98a2b3'},srcNode,'🔒 '+(L.locked||''));
		}else if(s.kind==='suspect'&&s.masterKey){
			// the SUSPECT's own EBX id - a DIFFERENT master record than the golden's own (task brief,
			// 2026-09-22, "show the id on each source/suspect lane"; a plain contributing source has none to
			// show here, it is the SAME record the golden card already names) - small and muted, same
			// .mdmf-id convention as the HTML halves of this screen (lineage.css cannot reach into this
			// inline SVG, so the styling is repeated here as attributes rather than a CSS class).
			var srcIdEl=el('text',{x:String(BANDS[0].x+20),y:String(y+23),'font-size':'9.5','font-family':'ui-monospace,SFMono-Regular,Menlo,monospace',fill:'#667085'},srcNode,'· '+s.masterKey);
			el('title',{},srcIdEl,L.idTooltip||'EBX record id');
		}
		interactive(srcNode,function(){sourceDetail(s);if(s.kind==='suspect')openCompare(s.masterKey);});

		if(!s.restricted){
			var landNode=el('g',{},svg);
			el('rect',{x:String(BANDS[1].x+8),y:String(y-CARD_H/2),width:String(BANDS[1].w-16),height:String(CARD_H),rx:'9',fill:'#fff',stroke:'#d0d5dd'},landNode);
			el('text',{x:String(BANDS[1].x+20),y:String(y-10),'font-size':'8.5','font-weight':'700',fill:color},landNode,(s.landingTable||'').slice(0,22));
			el('text',{x:String(BANDS[1].x+20),y:String(y+8),'font-size':'12','font-weight':'700',fill:'#172033'},landNode,(s.sourceId||'').slice(0,20));
			// third line: the LANDING row's own EBX id (task brief, 2026-09-22: "for a source-zone node whose
			// key is a different table's key, show that table's key the same way" - s.sourceId above is the
			// SOURCE SYSTEM's own business identifier, not an EBX primary key at all) followed by "received
			// on ..." when known (coordinator review, 2026-09-22, the mockup's own "reçue le 21/09 · 21 h 14"
			// - already formatted server-side, RecordLineageGraphic#formatInstant, never a raw ISO string
			// here). Either half can be absent; nothing is drawn when both are.
			var landingIdText=s.landingKey?('· '+s.landingKey):'';
			var receivedText=s.receivedAtDisplay?fmt(L.receivedOn,[s.receivedAtDisplay]):'';
			var landingLine3=[landingIdText,receivedText].filter(Boolean).join('  ');
			if(landingLine3){
				var landAttrs={x:String(BANDS[1].x+20),y:String(y+23),'font-size':'9.5',fill:'#98a2b3'};
				if(landingIdText)landAttrs['font-family']='ui-monospace,SFMono-Regular,Menlo,monospace';
				var landIdEl=el('text',landAttrs,landNode,landingLine3);
				if(landingIdText)el('title',{},landIdEl,L.idTooltip||'EBX record id');
			}
			interactive(landNode,function(){sourceDetail(s);if(s.kind==='suspect'){openCompare(s.masterKey);}else if(s.landingUrl){openLink(s.landingUrl);}});

			var stageNode=el('g',{},svg);
			el('rect',{x:String(BANDS[2].x+8),y:String(y-CARD_H/2),width:String(BANDS[2].w-16),height:String(CARD_H),rx:'9',fill:'#fff',stroke:'#d0d5dd'},stageNode);
			el('text',{x:String(BANDS[2].x+20),y:String(y-10),'font-size':'8.5','font-weight':'700',fill:color},stageNode,(s.stagingTable||'').slice(0,22));
			// the staging row's own EBX id - MUTED and monospace (task brief, 2026-09-22: "never as the main
			// title"), not bold/dark the way a real record name would be: a staging row has no business name
			// of its own, so this is the only line the card has for it, but it must not carry the same visual
			// weight as, say, s.system on the source card, which WOULD be a real name.
			var stageIdEl=el('text',{x:String(BANDS[2].x+20),y:String(y+8),'font-size':'11','font-family':'ui-monospace,SFMono-Regular,Menlo,monospace',fill:'#667085'},stageNode,'· '+(s.stagingKey||'').slice(0,20));
			el('title',{},stageIdEl,L.idTooltip||'EBX record id');
			var stepCount=(s.cleaningSteps||[]).length;
			el('text',{x:String(BANDS[2].x+20),y:String(y+23),'font-size':'9.5',fill:'#98a2b3'},stageNode,stepCount+(stepCount===1?' step':' steps'));
			// "suspect" badge (task brief, 2026-09-22: "label 'suspect' on the staging card") - the one visual
			// marker (besides the dashed ribbon/lane bar above) that this lane is a PENDING relation, not a
			// confirmed contributing source.
			if(s.kind==='suspect'){
				el('text',{x:String(BANDS[2].x+BANDS[2].w-10),y:String(y-CARD_H/2+12),'font-size':'8','font-weight':'700','text-anchor':'end',fill:'#b54708'},stageNode,(L.suspect||'Suspect').toUpperCase());
			}
			interactive(stageNode,function(){sourceDetail(s);if(s.kind==='suspect'){openCompare(s.masterKey);}else if(s.stagingUrl){openLink(s.stagingUrl);}});

			// cleaning-step chips, IN THE GAP between landing and staging (coordinator review, 2026-09-22:
			// "chips sit on the staging card's top edge and overflow ... they belong in the gap" - MEASURED
			// against the gap, minus 8px, never estimated - see drawCleaningChips's own Javadoc).
			(s.cleaningSteps||[]).forEach(function(step){step._color=color;});
			drawCleaningChips(svg,s.cleaningSteps||[],BANDS[1].x+BANDS[1].w+6,BANDS[2].x-8,y);
		}
	});

	// ring - the group caption (id) already sits at the band's top; the ring itself shows the group's
	// RECORD COUNT (coordinator review, 2026-09-22: "put the group's record count inside it") since no match
	// SCORE is ever readable (task brief). ONE-LINE SWITCH for later: the day a real score becomes readable
	// (RecordLineage.MergeInfo would need a new field for it - none exists today), replace the big number
	// below with the score and move `data.merge.recordCount` up into the band caption instead - everything
	// else here (ring geometry, state lines underneath) stays as-is.
	var ring=el('g',{transform:'translate('+ringX+','+ringY+')'},svg);
	el('circle',{r:'40',fill:'#fff',stroke:data.merge.present?'#d9d2f2':'#e6eaf0','stroke-width':'8'},ring); // theme-fixed (audit F-44): the golden legend colour (SourceColorPalette.GOLDEN) and its tints
	if(data.merge.present){
		el('text',{y:'6','text-anchor':'middle','font-size':'22','font-weight':'700',fill:'#172033'},ring,String(data.merge.recordCount));
		el('text',{y:'20','text-anchor':'middle','font-size':'8','font-weight':'700','letter-spacing':'.06em',fill:'#98a2b3'},ring,L.records||'RECORDS');
		// pending suspects (task brief, 2026-09-22: the ring shows "1 + N suspects") - a third, smaller line
		// under the record count/label, only when there is at least one.
		if(data.merge.suspectCount>0){
			el('text',{y:'32','text-anchor':'middle','font-size':'8','font-weight':'700',fill:'#b54708'},ring,'+'+data.merge.suspectCount+' '+(L.suspect||'suspect').toLowerCase()+(data.merge.suspectCount>1?'s':''));
		}
	}
	interactive(ring,function(){detail(data.merge.present?(fmt(L.group,[data.merge.groupId])+' · '+data.merge.stateWords):(data.merge.reasonWords||''));if(data.merge.present&&data.merge.suspectCount>0)openCompare();});

	var stateLines=data.merge.present?[data.merge.stateWords].concat(data.merge.autoCreated?['auto-created, no source record of its own']:[]):[data.merge.reasonWords||''];
	stateLines=stateLines.slice(0,2);
	stateLines.forEach(function(line,i){
		el('text',{x:String(ringX),y:String(ringY+56+i*14),'text-anchor':'middle','font-size':i===0?'11':'9.5','font-weight':i===0?'700':'400',fill:i===0?'#475467':'#98a2b3'},svg,line);
	});

	drawGoldenCard(svg,goldX,goldW,cardY,cardH,ringX,ringY,gradId,compositionLines,proximityLines);
}

function drawGoldenCard(svg,goldX,goldW,cardY,cardH,ringX,ringY,gradId,compositionLines,proximityLines){
	// ribbon from the ring to the golden card (coordinator review, 2026-09-22: "no ribbon between the group
	// ring and the golden card" in the earlier version) - drawn BEFORE the card so the card's own fill
	// cleanly covers the ribbon's end.
	el('path',{d:'M '+(ringX+40)+' '+ringY+' L '+(goldX-4)+' '+ringY,stroke:'url(#'+gradId+')','stroke-width':'24',fill:'none'},svg);

	// the record's own NAME, not its bare key (coordinator review, 2026-09-22: "the golden card ... shows
	// the primary key where the mockup shows the record's NAME") - the key still appears, just moved into
	// the subtitle line under the name, alongside the entity and source count.
	var masterTitle=data.masterLabel||data.masterKey||'';
	var card=el('g',{},svg);
	el('rect',{x:String(goldX),y:String(cardY),width:String(goldW),height:String(cardH),rx:'12',fill:'#f0eefa',stroke:GOLD,'stroke-width':'2'},card); // theme-fixed (audit F-44): the golden legend colour (SourceColorPalette.GOLDEN) and its tints
	el('text',{x:String(goldX+16),y:String(cardY+22),'font-size':'9','font-weight':'700','letter-spacing':'.06em',fill:GOLD},card,'GOLDEN RECORD');
	el('text',{x:String(goldX+16),y:String(cardY+44),'font-size':'15','font-weight':'700',fill:'#172033'},card,String(masterTitle).slice(0,26));
	el('text',{x:String(goldX+16),y:String(cardY+60),'font-size':'10',fill:'#667085'},card,(data.entity||'')+' · '+String(data.masterKey||'')+' · '+String(data.sourceCount)+' source(s)');
	interactive(card,function(){detail(masterTitle+' · '+data.masterTable+' · '+data.masterKey);if(data.masterUrl)openLink(data.masterUrl);});

	var y=cardY+80;
	el('text',{x:String(goldX+16),y:String(y),'font-size':'8.5','font-weight':'700','letter-spacing':'.06em',fill:'#667085'},card,L.compositionTitle||'');
	y+=10;
	if(data.composition.known){
		var total=0;
		data.composition.bySource.forEach(function(row){total+=row.count;});
		var bx=goldX+16,barW=goldW-32;
		data.composition.bySource.forEach(function(row){
			var w=total>0?barW*row.count/total:0;
			el('rect',{x:String(bx),y:String(y),width:String(w),height:'9',fill:row.color},card);
			bx+=w;
		});
		y+=23;
		var summary=data.composition.bySource.map(function(row){return row.count+' '+row.system;}).join(' · ');
		wrapText(summary,30).forEach(function(line){el('text',{x:String(goldX+16),y:String(y),'font-size':'9.5',fill:'#475467'},card,line);y+=13;});
	}else{
		y+=10;
		compositionLines.forEach(function(line){el('text',{x:String(goldX+16),y:String(y),'font-size':'10',fill:'#475467'},card,line);y+=13;});
	}
	y+=10;
	el('text',{x:String(goldX+16),y:String(y),'font-size':'8.5','font-weight':'700','letter-spacing':'.06em',fill:'#667085'},card,L.proximityTitle||'');
	y+=16;
	proximityLines.forEach(function(line){el('text',{x:String(goldX+16),y:String(y),'font-size':'10','font-style':'italic',fill:'#98a2b3'},card,line);y+=13;});
}

// ------------------------------------------------------------------------------------------------- 2. champ par champ

/**
 * An HTML table, not SVG (deliberate adaptation from the mockup for the real screen): the mockup draws a
 * hand-placed SVG bus-line from the retained cell to the golden cell, which does not survive a horizontally
 * scrolling, sticky-first-column table well. Instead: the table is built as plain HTML, then a SEPARATE
 * `drawFieldConnectors` pass measures the actual rendered cell positions (`offsetLeft`/`offsetTop`, which
 * are unaffected by scroll position - only the visual scrollLeft/scrollTop change, not the offsets) and
 * draws one small SVG overlay INSIDE the same scrolling container, so it scrolls together with the table
 * and is naturally clipped underneath the opaque, higher-z-index sticky golden column (coordinator review,
 * 2026-09-22: "must survive horizontal scroll ... clip at the sticky edge rather than drawing over it").
 */
function buildFieldsTable(){
	if(!fieldsHost)return;
	// single-source note (coordinator review, 2026-09-22): RecordLineage/RecordLineageGraphic already decide
	// WHETHER to show it (never fabricated here) - this is a plain placeholder toggle, not a rule of its own.
	if(fieldsNoteEl){
		if(data.singleSourceNote){fieldsNoteEl.textContent=data.singleSourceNote;fieldsNoteEl.hidden=false;}
		else{fieldsNoteEl.textContent='';fieldsNoteEl.hidden=true;}
	}
	fieldsHost.textContent='';
	var sources=visibleSources();
	// with a single contributing source, the golden column's own left bar still takes that source's colour
	// even though no cell is ever badged/connected as "retained" for it any more (RecordLineage#assemble's own
	// suppression, see its comment) - otherwise the bar would silently fall back to the plain grey default.
	var singleSourceColor=data.singleSource&&sources.length===1?sources[0].color:null;
	var inner=h('div',{class:'mdmf-fields-inner'},fieldsHost);
	var table=h('table',{class:'mdmf-fields-table'},inner);
	var thead=h('thead',{},table),headRow=h('tr',{},thead);
	var goldTh=h('th',{class:'mdmf-fields-golden-col'},headRow);
	h('i',{class:'mdmf-legend-dot'},goldTh).style.background=GOLD;
	goldTh.appendChild(document.createTextNode(' '+(L.goldenLabel||'Golden record')));
	sources.forEach(function(s){
		var th=h('th',{},headRow);
		paintDot(h('i',{class:'mdmf-legend-dot'},th),s.color,s.style);
		th.appendChild(document.createTextNode(' '+(s.system||'')));
	});
	var tbody=h('tbody',{},table);
	var connectorPlan=[]; // {goldenTd, retainedTd, color} - resolved to pixel geometry after layout, both cells living in the field's own CONNECTOR STRIP row, never the value row itself (coordinator review, 2026-09-22 - see drawFieldConnectors' own Javadoc for why)
	data.fields.forEach(function(f){
		var row=h('tr',{class:'mdmf-field-row'},tbody);
		var goldTd=h('td',{class:'mdmf-fields-golden-col'},row);
		var retainedColor=null;
		sources.forEach(function(s){
			var c=(f.contributions||[]).filter(function(x){return x.masterKey===s.masterKey;})[0];
			if(c&&c.state==='RETAINED')retainedColor=c.color;
		});
		var laneColor=retainedColor||singleSourceColor||null;
		goldTd.style.borderLeft='4px solid '+(laneColor||'var(--line)');
		if(f.goldenRestricted){
			h('span',{class:'mdmf-chip mdmf-chip-target'},goldTd,'🔒');
		}else{
			// the human label when RecordLineageGraphic sent one, the bare technical name otherwise - EITHER
			// way the technical name stays reachable via title (coordinator review, 2026-09-22: "raw camelCase
			// small caps" field names on the golden column).
			h('div',{class:'mdmf-golden-field-name',title:f.field},goldTd,f.fieldLabel||f.field);
			h('div',{class:'mdmf-golden-field-value'},goldTd,f.goldenValue===undefined?'':String(f.goldenValue));
			if(f.survivorshipRule){
				h('div',{class:'mdmf-golden-field-rule'},goldTd,f.survivorshipRule);
			}
		}
		sources.forEach(function(s){
			var c=(f.contributions||[]).filter(function(x){return x.masterKey===s.masterKey;})[0];
			var td=h('td',{},row);
			td.style.position='relative';
			if(!c||c.state==='LOCK'){
				td.className='mdmf-cell-lock';
				td.title=(s.system||'')+': '+(L.locked||'');
				h('span',{},td,'🔒 ');
				h('span',{},td,L.locked||'');
				return;
			}
			// the cell's own title (hover tooltip, coordinator review, 2026-09-22) explains ITS state in plain
			// words - see cellTooltip's own Javadoc for why the same sentence also drives the badge's title and
			// the click "detail" panel below.
			td.title=cellTooltip(s,f,c);
			if(c.state==='EMPTY'){
				td.className='mdmf-cell-empty';
				td.textContent=L.empty||'empty';
				return;
			}
			var valueLine=h('div',{class:'mdmf-cell-value'},td,c.value===undefined?'':String(c.value));
			var badge=null;
			if(c.state==='RETAINED'){
				td.className='mdmf-cell-retained';
				td.style.background=hexSoft(s.color);
				td.style.borderColor=s.color;
				badge=h('span',{class:'mdmf-cell-badge mdmf-cell-badge-retained'},td,(c.retainedReason==='KNOWN'?(L.retainedKnown||'retained'):(L.retainedDeduced||'retained (deduced)')));
			}else if(c.state==='DIFF'){
				td.className='mdmf-cell-diff';
				valueLine.style.textDecoration='line-through';
				badge=h('span',{class:'mdmf-cell-badge mdmf-cell-badge-diff'},td,'≠');
			}else if(c.state==='EQ'){
				td.className='mdmf-cell-eq';
				badge=h('span',{class:'mdmf-cell-badge mdmf-cell-badge-eq'},td,'=');
			}else if(c.state==='EQUIVALENT'){
				// a case/accent/whitespace-only difference (coordinator review, 2026-09-22) - the "≈" badge, NO
				// strikethrough (unlike DIFF): this is not a genuine disagreement.
				td.className='mdmf-cell-equivalent';
				badge=h('span',{class:'mdmf-cell-badge mdmf-cell-badge-approx'},td,'≈');
			}else if(c.state==='TRANSFORMED'){
				// single-source rule (coordinator review, 2026-09-22, task brief "never strike through a single
				// source's value"): a small text badge, no strikethrough - with one source, the golden value was
				// necessarily built from this one, whatever the case/accent/spelling difference.
				td.className='mdmf-cell-transformed';
				badge=h('span',{class:'mdmf-cell-badge mdmf-cell-badge-transformed'},td,L.transformed||'transformed');
			}
			if(badge)badge.title=td.title;
			if(c.rawValue!==undefined){
				var raw=h('div',{class:'mdmf-cell-raw',title:rawTooltip(s,c)},td);
				var rawText=fmt(L.rawWas,[c.rawValue]);
				var splitAt=rawText.indexOf(': ');
				if(splitAt>=0){
					raw.appendChild(document.createTextNode(rawText.slice(0,splitAt+2)));
					h('span',{style:'text-decoration:line-through'},raw,rawText.slice(splitAt+2));
				}else{
					raw.textContent=rawText;
				}
			}
			interactive(td,function(){
				detail(cellTooltip(s,f,c));
				highlightField(f.field);
			});
		});
		// the connector STRIP row (coordinator review, 2026-09-22): dedicated room under the value row so the
		// connector never has to run through a cell's own text - ~12px, present for every field (so the row
		// rhythm stays regular), empty whenever nothing is retained. Its own golden-column cell keeps the same
		// sticky/left-bar treatment as the value row above it (same class, same colour), so the lane reads as
		// one continuous coloured band rather than two disconnected pieces.
		var stripRow=h('tr',{class:'mdmf-connector-row'},tbody);
		var goldStripTd=h('td',{class:'mdmf-fields-golden-col'},stripRow);
		goldStripTd.style.borderLeft='4px solid '+(laneColor||'var(--line)');
		var retainedStripTd=null;
		sources.forEach(function(s){
			var stripTd=h('td',{},stripRow);
			var c=(f.contributions||[]).filter(function(x){return x.masterKey===s.masterKey;})[0];
			if(c&&c.state==='RETAINED')retainedStripTd=stripTd;
		});
		if(retainedColor&&retainedStripTd){
			connectorPlan.push({goldenTd:goldStripTd,retainedTd:retainedStripTd,color:retainedColor});
		}
	});
	drawFieldConnectors(inner,connectorPlan);
}

/**
 * The plain-language explanation for a "champ par champ" cell's own state (coordinator review, 2026-09-22,
 * fixing a reported false disagreement - master record Companies_Master/Master//root/Company id 108,
 * "BANK OF CHINA"/"Bank of China": a letter-case-only difference showed a "differs" badge with the value
 * struck through, read as "why is this value rejected?"). Used for the cell's own `title` (hover tooltip),
 * the badge's own `title`, and the click "detail" panel text (`interactive` below) - all three always say
 * the SAME sentence, never three independently-worded explanations of the same fact. Templates come from
 * `L` already carrying their own `{0}`/`{1}`/... placeholders (`RecordLineageGraphic#buildLabels` fetches
 * them with ZERO Java-side arguments - see its own comment) - filled in here with this file's own `fmt()`,
 * never `java.text.MessageFormat`.
 */
function cellTooltip(s,f,c){
	var system=s.system||'',value=c.value===undefined?'':String(c.value),golden=f.goldenValue===undefined?'':String(f.goldenValue);
	if(c.state==='EMPTY')return fmt(L.tooltipEmpty,[system]);
	if(c.state==='RETAINED')return fmt(c.retainedReason==='KNOWN'?L.tooltipRetainedKnown:L.tooltipRetainedDeduced,[system]);
	if(c.state==='EQ')return fmt(L.tooltipEq,[system]);
	if(c.state==='EQUIVALENT')return fmt(L.tooltipEquivalent,[system,value,golden]);
	if(c.state==='TRANSFORMED')return fmt(L.tooltipTransformed,[golden,system,value]);
	if(c.state==='DIFF')return fmt(L.tooltipDiff,[system,value,golden]);
	return '';
}

/**
 * The "raw:" line's own tooltip (coordinator review, 2026-09-22: the line had no explanation anywhere of
 * what "raw" meant) - names the cleaning step(s) that turned the received value into the one actually shown,
 * or a shorter sentence when no step is known (a value can differ from its raw form without a tracked
 * cleaning step, e.g. a plain type coercion).
 */
function rawTooltip(s,c){
	var steps=(c.cleaningSteps||[]).map(function(st){return st.shortWords||st.kind;}).join(', ');
	return steps?fmt(L.tooltipRawWithSteps,[c.rawValue,s.system||'',steps,c.value]):fmt(L.tooltipRawNoSteps,[c.rawValue,s.system||'',c.value]);
}

function hexSoft(hex){
	var m=/^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex||'');
	if(!m)return '#f4f1fc'; // theme-fixed (audit F-44): the golden legend colour (SourceColorPalette.GOLDEN) and its tints
	var r=parseInt(m[1],16),g=parseInt(m[2],16),b=parseInt(m[3],16);
	return 'rgba('+r+','+g+','+b+',.14)';
}

/**
 * Every retained<->golden connector, drawn ENTIRELY INSIDE each field's own dedicated connector-strip row
 * (coordinator review, 2026-09-22, second pass: the first fix drew a straight line at the VALUE row's own
 * mid-height, which was unmistakable as an arrow but ran straight through the value text of any cell it
 * crossed - "not acceptable: a value must stay readable"). `plan`'s `goldenTd`/`retainedTd` are the STRIP
 * row's own cells (buildFieldsTable), never the value row's - dedicated room means the connector never has
 * to share pixels with a value/badge/raw-value line again.
 * <p>
 * <b>Two pieces, not one - third pass, same review session</b>: a first attempt ran ONE path all the way to
 * the golden cell's own horizontal CENTER, but the golden column is sticky with an opaque background and a
 * deliberately HIGHER z-index than this shared overlay (`lineage.css`'s own stacking comment - needed so a
 * connector segment scrolled UNDER the sticky column is correctly hidden by it) - which made the entire
 * "inside golden" portion of that path invisible too, arrowhead included, verified in a rendered screenshot.
 * The fix mirrors the constraint instead of fighting it: the SHARED, page-level overlay below only ever
 * draws the piece that is genuinely outside golden's own rectangle (the retained cell's stub + the
 * horizontal run, stopping exactly at golden's own left border); the short last stretch that must actually
 * enter golden - a small stub and the arrowhead - is a separate, tiny SVG appended as golden's STRIP cell's
 * OWN CHILD. Being a descendant, it paints above that cell's own background for free and moves/sticks with
 * it during scroll for free, with no extra code - it needs no offsetLeft of its own either, since it is
 * positioned relative to its own parent cell.
 * <p>
 * Positions are read via `offsetLeft`/`offsetTop`, relative to the nearest POSITIONED ancestor
 * (`.mdmf-fields-inner`, `position:relative` in CSS) - unaffected by the container's own scroll position, so
 * the shared overlay stays correctly aligned wherever the user has scrolled to; only strip space, never a
 * cell's own value/badge/raw-value content, may ever be crossed when the retained column is not adjacent to
 * golden - the whole point of the dedicated strip row.
 */
function drawFieldConnectors(inner,plan){
	var old=inner.querySelector('.mdmf-connector-overlay');
	if(old)old.remove();
	if(!plan.length)return;
	var table=inner.querySelector('table');
	var w=table.offsetWidth,hgt=table.offsetHeight;
	var svg=el('svg',{class:'mdmf-connector-overlay',width:String(w),height:String(hgt),viewBox:'0 0 '+w+' '+hgt});
	inner.insertBefore(svg,table.nextSibling);
	plan.forEach(function(p,i){
		var top=p.retainedTd.offsetTop;
		var mid=top+p.retainedTd.offsetHeight/2;
		var x1=p.retainedTd.offsetLeft+p.retainedTd.offsetWidth/2;
		var goldRight=p.goldenTd.offsetLeft+p.goldenTd.offsetWidth;
		// leaves the retained strip cell from the middle of its own top edge (the value row's own bottom
		// edge sits exactly there) with a short vertical stub down to the strip's own vertical middle, then
		// runs horizontally toward golden - stopping at golden's own border, never entering its rectangle
		// (see this function's own Javadoc for why: anything past that point would be invisible anyway).
		var d='M '+x1+' '+top+' L '+x1+' '+mid+' L '+goldRight+' '+mid;
		// a 1px white halo (coordinator review) so the 3px coloured line stays readable over any strip
		// background it crosses - drawn first, wider, with no marker; the coloured line on top carries on
		// into the golden cell's own small "entry" SVG below, same colour, same width, so the two pieces
		// read as one continuous connector despite living in different stacking contexts.
		el('path',{d:d,fill:'none',stroke:'#fff','stroke-width':'5'},svg);
		el('path',{d:d,fill:'none',stroke:p.color,'stroke-width':'3'},svg);

		// the piece that actually enters golden: a small SVG, sized to exactly cover the golden strip cell,
		// appended as ITS OWN CHILD - see this function's own Javadoc for why. Local coordinates only (its
		// own width/height), continuing from the shared line's own arrival point (local right edge, same mid
		// height) through the cell's own horizontal centre, then up to its own top edge (golden's bottom
		// edge) with the arrowhead - >=10px, filled, source colour, same forward-pointing (+x) triangle
		// convention as drawParcours' own markers, auto-rotated to point straight up (the path's own local
		// direction of travel here).
		var gw=p.goldenTd.offsetWidth,gh=p.goldenTd.offsetHeight;
		var entry=el('svg',{class:'mdmf-connector-entry',width:String(gw),height:String(gh),viewBox:'0 0 '+gw+' '+gh,
			style:'position:absolute;left:0;top:0;pointer-events:none'});
		var entryDefs=el('defs',{},entry);
		var markerId='mdmfFieldArrow'+i;
		var marker=el('marker',{id:markerId,viewBox:'0 0 12 12',refX:'11',refY:'6',markerWidth:'12',markerHeight:'12',orient:'auto'},entryDefs);
		el('path',{d:'M0,0 L12,6 L0,12 z',fill:p.color},marker);
		var localMid=gh/2,cx=gw/2;
		var entryD='M '+gw+' '+localMid+' L '+cx+' '+localMid+' L '+cx+' 0';
		el('path',{d:entryD,fill:'none',stroke:'#fff','stroke-width':'5'},entry);
		el('path',{d:entryD,fill:'none',stroke:p.color,'stroke-width':'3','marker-end':'url(#'+markerId+')'},entry);
		p.goldenTd.appendChild(entry);
	});
}

/** Click on a golden field: highlight that field's row (already visible - the table has only one row per
 * field) - kept as a named hook so a future enhancement (highlighting the matching ribbons in part 1 too)
 * has one place to extend, per the task brief's "click on a golden field -> highlight ... the ribbons of
 * the sources that carry it". */
function highlightField(fieldName){
	if(!fieldsHost)return;
	Array.prototype.forEach.call(fieldsHost.querySelectorAll('tr'),function(tr){tr.classList.remove('mdmf-row-highlight');});
	// only the VALUE rows (coordinator review, 2026-09-22: each field now also has its own connector-strip
	// row right after it) - querySelectorAll('tbody tr') would count both and misalign data.fields[i] with
	// the wrong row from the second field onward.
	Array.prototype.forEach.call(fieldsHost.querySelectorAll('tbody tr.mdmf-field-row'),function(tr,i){
		if(data.fields[i]&&data.fields[i].field===fieldName)tr.classList.add('mdmf-row-highlight');
	});
}

// ------------------------------------------------------------------------------------------------------- 3. frise

/**
 * A horizontally scrollable strip (coordinator review, 2026-09-22: "the timeline overflows its card ...
 * make it a horizontally scrollable strip", `.mdmf-frise-canvas{overflow-x:auto}` in `lineage.css`), point
 * spacing at least 170 design units, the baseline spanning EXACTLY first point to last point (never the
 * full canvas width, which used to leave a dangling stub past the last point). For a FEW points that
 * already fit the card, the drawing stretches to use the full width instead of sitting at its own narrow
 * natural size (measured against `friseHost.clientWidth` - the actual rendered width of the card at this
 * moment); for MANY points it keeps its natural (larger) pixel size and lets the card scroll, rather than
 * shrinking every point down to illegibility.
 */
function drawFrise(){
	if(!friseHost)return;
	friseHost.textContent='';
	var events=(data.timeline||[]).slice();
	var n=Math.max(1,events.length);
	var spacing=170;
	var lastX=80+(n-1)*spacing;
	var W=lastX+80,H=100;
	var containerWidth=friseHost.clientWidth||W;
	var fitsWithoutScroll=W<=containerWidth;
	var svgAttrs={viewBox:'0 0 '+W+' '+H,role:'img','aria-label':'Timeline'};
	if(fitsWithoutScroll){svgAttrs.width='100%';svgAttrs.height=String(H);svgAttrs.preserveAspectRatio='xMinYMid meet';}
	else{svgAttrs.width=String(W);svgAttrs.height=String(H);}
	var svg=el('svg',svgAttrs,friseHost);
	// the baseline spans exactly the first point to the last one - never the full canvas width (coordinator
	// review, 2026-09-22: "let the base line span exactly first point -> last point").
	el('line',{x1:'80',y1:'30',x2:String(lastX),y2:'30',stroke:'#d0d5dd','stroke-width':'3','stroke-linecap':'round'},svg);
	events.forEach(function(e,i){
		var x=80+i*spacing;
		var color=e.color||(e.kind==='PENDING'?'#98a2b3':e.kind==='MATCH'?GOLD:'#475467');
		var known=e.kind==='RECEIVED';
		el('circle',{cx:String(x),cy:'30',r:'9',fill:known?color:'#fff',stroke:color,'stroke-width':'3'},svg);
		var label,detailText;
		if(e.kind==='RECEIVED'){label=fmt(L.received,[e.system]);detailText=e.at||'';}
		else if(e.kind==='CLEANED'){label=L.cleaned||'';detailText='';}
		else if(e.kind==='MATCH'){label=fmt(L.matched,[e.stateWords]);detailText=fmt(L.group,[e.groupId]);}
		else{label=fmt(L.pending,[e.reasonWords]);detailText='';}
		var t=el('text',{x:String(x),y:'58','text-anchor':'middle','font-size':'10.5','font-weight':'700',fill:'#172033'},svg,String(label).slice(0,30));
		el('text',{x:String(x),y:'74','text-anchor':'middle','font-size':'10',fill:'#667085'},svg,String(detailText));
		var g=el('g',{},svg);
		g.appendChild(t);
		interactive(g,function(){detail(label+(detailText?' · '+detailText:''));});
	});
}

// ----------------------------------------------------------------------------------------------------------- go

function redraw(){drawLegend();updateMoreControl();drawParcours();buildFieldsTable();drawFrise();}
redraw();
})();
