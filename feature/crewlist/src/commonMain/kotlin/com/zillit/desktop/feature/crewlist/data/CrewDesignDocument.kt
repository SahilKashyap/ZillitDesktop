package com.zillit.desktop.feature.crewlist.data

import com.zillit.desktop.feature.crewlist.domain.HeaderArranger
import com.zillit.desktop.feature.crewlist.domain.HeaderLayout
import com.zillit.desktop.feature.crewlist.domain.HeaderSection
import com.zillit.desktop.feature.crewlist.domain.SectionOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The Design canvas's document: the backend's stacked HTML with the web's
 * in-document arranger layered on (`CREWLIST_DRAG_LAYER` + `injectDragLayer`
 * in `CrewListCustom.jsx`, ZL-19725), so the three header sections are grabbed
 * by their grips, paired or stacked, and nudged — in the backend's own render.
 *
 * One change from the web: the page talks back through the embedded browser's
 * `window.cefQuery` rather than `window.parent.postMessage` — there is no
 * parent frame here. It still posts to a parent when one exists, so the same
 * document works in a browser.
 */
internal object CrewDesignDocument {

    /**
     * [html] with the arranger injected, seeded with the current rows and
     * nudges; the table lines hidden and the logo resized client-side so both
     * show at once, exactly as the PDF will print them.
     */
    fun compose(html: String, layout: HeaderLayout, hideInternalLines: Boolean): String {
        val seeds = buildString {
            append("<script>window.__CL_LAYOUT__=")
            append(orderRows(layout.order))
            append(";window.__CL_OFFSETS__=")
            append(offsetsJson(layout))
            append(";</script>")
        }
        val lines = if (hideInternalLines) HIDE_LINES_STYLE else ""
        val logo = "<style>.doc .masthead .logo img,.doc .logoblock img{width:${layout.logoSize}px !important;" +
            "height:auto !important;max-width:none !important}</style>"
        val layer = seeds + DRAG_LAYER + lines + logo
        val bodyEnd = html.indexOf("</body>")
        return if (bodyEnd >= 0) html.substring(0, bodyEnd) + layer + html.substring(bodyEnd) else html + layer
    }

    /** Every row as an array, as the layer's seed expects (`[["logo","company"],["title"]]`). */
    private fun orderRows(order: List<List<HeaderSection>>): String = buildJsonArray {
        order.forEach { row -> add(buildJsonArray { row.forEach { add(JsonPrimitive(it.wire)) } }) }
    }.toString()

    private fun offsetsJson(layout: HeaderLayout): String = buildJsonObject {
        HeaderSection.entries.forEach { section ->
            val offset = layout.offsetOf(section)
            putJsonObject(section.wire) {
                put("x", offset.x)
                put("y", offset.y)
            }
        }
    }.toString()

    private const val HIDE_LINES_STYLE =
        "<style>.doc .titleblock,.doc .masthead,.doc .masthead *,.doc .logoblock,.doc .companyblock," +
            ".doc .companyblock *,.doc table.crew td,.doc table.crew th{border-color:transparent !important}</style>"

    /**
     * The web's layer, verbatim but for `emit`. Reorder by the grips (drop on a
     * section to pair or swap, in a gap to stack); nudge by dragging a section's
     * content, clamped inside its cell. Each change is reported as
     * `crewlist-layout` (rows, one-section rows as bare strings) or
     * `crewlist-offset` (one section's `{x, y}`).
     */
    private const val DRAG_LAYER = """
<style>
  .cl-header{display:flex;flex-direction:column}
  .cl-sec{position:relative}
  .cl-drag{cursor:grab}
  .cl-ho:hover{outline:2px dashed #f99300;outline-offset:0}
  .cl-drag.cl-dragging{opacity:.4}
  .cl-row{display:flex;align-items:stretch;gap:28px;width:100%}
  .cl-row > .cl-sec{flex:1 1 0;min-width:0}
  .cl-inner{display:inline-block;max-width:100%;vertical-align:top}
  .cl-inner,.cl-inner *{white-space:normal !important;overflow-wrap:anywhere}
  .cl-drop-target{outline:2px solid #f99300 !important;outline-offset:-2px;background:rgba(249,147,0,.10)}
  .cl-grip{position:absolute;bottom:6px;right:6px;display:inline-flex;align-items:center;justify-content:center;
    font:600 15px/1 Helvetica,Arial,sans-serif;color:#fff;background:#f99300;border-radius:5px;
    width:24px;height:22px;cursor:grab;z-index:9;user-select:none;box-shadow:0 1px 3px rgba(0,0,0,.25)}
  .cl-grip-tip{position:absolute;top:calc(100% + 7px);right:0;width:220px;
    font:600 11px/1.45 Helvetica,Arial,sans-serif;color:#fff;background:#1f2937;
    padding:7px 10px;border-radius:7px;opacity:0;visibility:hidden;pointer-events:none;
    transition:opacity .12s;z-index:30;box-shadow:0 4px 12px rgba(0,0,0,.3)}
  .cl-grip:hover .cl-grip-tip{opacity:1;visibility:visible}
  .cl-gap{height:8px;margin:3px 0;border-radius:6px;transition:height .1s,background .1s}
  .cl-gap.cl-gap-on{height:20px;background:rgba(249,147,0,.16);outline:2px dashed #f99300}
</style>
<script>
(function(){
  function emit(o){
    var s=JSON.stringify(o);
    if(window.cefQuery){ window.cefQuery({request:s,persistent:false,onSuccess:function(){},onFailure:function(){}}); }
    else if(window.parent&&window.parent!==window){ window.parent.postMessage(o,'*'); }
  }
  var LABEL={title:'Title',logo:'Logo',company:'Company details'};
  function grip(el,id){
    if(el.querySelector(':scope > .cl-grip')) return;
    var g=document.createElement('div'); g.className='cl-grip'; g.textContent='⠿';
    var tip=document.createElement('span'); tip.className='cl-grip-tip';
    tip.textContent='Drag to move the '+(LABEL[id]||id)+' section — drop onto another to pair side-by-side, or in a gap to stack';
    g.appendChild(tip); el.appendChild(g);
  }
  function init(){
    var els={
      title:document.querySelector('.titleblock'),
      logo:document.querySelector('.logoblock')||document.querySelector('.masthead .logo'),
      company:document.querySelector('.companyblock')||document.querySelector('.masthead .company')
    };
    if(!els.title||!els.logo||!els.company) return;
    var raw=(window.__CL_LAYOUT__&&window.__CL_LAYOUT__.length)?window.__CL_LAYOUT__:[['logo','company'],['title']];
    var layout=(function(){
      var seen={}, out=[];
      raw.forEach(function(row){
        var r=(Array.isArray(row)?row:[row]).filter(function(id){ if(els[id]&&!seen[id]){seen[id]=1;return true;} return false; });
        if(r.length) out.push(r);
      });
      ['title','logo','company'].forEach(function(id){ if(!seen[id]) out.push([id]); });
      return out;
    })();
    var root=document.createElement('div'); root.className='cl-header';
    els.title.parentNode.insertBefore(root, els.title);
    var dragId=null;
    function clearHi(){
      Array.prototype.forEach.call(document.querySelectorAll('.cl-drop-target,.cl-gap-on'),
        function(n){ n.classList.remove('cl-drop-target'); n.classList.remove('cl-gap-on'); });
    }
    function rowOf(id){ for(var i=0;i<layout.length;i++){ if(layout[i].indexOf(id)>-1) return i; } return -1; }
    function removeId(id){
      var ri=rowOf(id); if(ri<0) return;
      layout[ri]=layout[ri].filter(function(x){return x!==id;});
      if(!layout[ri].length) layout.splice(ri,1);
    }
    function commit(){
      emit({type:'crewlist-layout',order:layout.map(function(r){return r.length===1?r[0]:r.slice();})});
      requestAnimationFrame(render)
    }
    function dropOnBlock(d,target,side){
      if(d===target) return;
      var sr=rowOf(d);
      if(sr>-1 && sr===rowOf(target)){
        var prow=layout[sr], di=prow.indexOf(d), tiPair=prow.indexOf(target);
        prow.splice(di,1);
        prow.splice(prow.indexOf(target)+(di<tiPair?1:0),0,d);
        return commit();
      }
      removeId(d);
      var ri=rowOf(target);
      if(ri<0){ layout.push([d]); return commit(); }
      var row=layout[ri];
      if(row.length>=2){
        var ev=row.filter(function(x){return x!==target;})[0];
        layout[ri]=[target]; layout.splice(ri+1,0,[ev]); row=layout[ri];
      }
      var ti=row.indexOf(target);
      row.splice(side==='before'?ti:ti+1,0,d);
      commit();
    }
    function dropToGap(d,gi){
      var src=rowOf(d);
      var removedRow = src>-1 && layout[src].length===1;
      removeId(d);
      if(removedRow && src<gi) gi=gi-1;
      if(gi<0){gi=0;} if(gi>layout.length){gi=layout.length;}
      layout.splice(gi,0,[d]); commit();
    }
    function gap(i){
      var g=document.createElement('div'); g.className='cl-gap';
      g.addEventListener('dragover',function(e){ if(dragId){ e.preventDefault(); g.classList.add('cl-gap-on'); } });
      g.addEventListener('dragleave',function(){ g.classList.remove('cl-gap-on'); });
      g.addEventListener('drop',function(e){ var d=dragId||e.dataTransfer.getData('text/plain'); if(!d){return;} e.preventDefault(); g.classList.remove('cl-gap-on'); dragId=null; dropToGap(d,i); });
      return g;
    }
    function wireBlock(id){
      var el=els[id];
      el.classList.add('cl-sec','cl-ho');
      Array.prototype.forEach.call(el.querySelectorAll('img'),function(im){im.draggable=false;});
      grip(el,id);
      var g=el.querySelector(':scope > .cl-grip');
      if(g && !g.dataset.clG){
        g.dataset.clG='1'; g.classList.add('cl-drag'); g.setAttribute('draggable','true');
        g.addEventListener('dragstart',function(e){ e.stopPropagation(); clearHi(); dragId=id; e.dataTransfer.effectAllowed='move'; e.dataTransfer.setData('text/plain',id); el.classList.add('cl-dragging'); });
        g.addEventListener('dragend',function(){ dragId=null; el.classList.remove('cl-dragging'); clearHi(); });
      }
      if(el.dataset.clW){return;} el.dataset.clW='1';
      el.addEventListener('dragover',function(e){ if(dragId&&dragId!==id){ e.preventDefault(); e.dataTransfer.dropEffect='move'; el.classList.add('cl-drop-target'); } });
      el.addEventListener('dragleave',function(){ el.classList.remove('cl-drop-target'); });
      el.addEventListener('drop',function(e){
        var d=dragId||e.dataTransfer.getData('text/plain');
        if(!d||d===id){return;} e.preventDefault(); el.classList.remove('cl-drop-target');
        var r=el.getBoundingClientRect(); var side=(e.clientX < r.left+r.width/2)?'before':'after';
        dragId=null; dropOnBlock(d,id,side);
      });
    }
    var reclampers={};
    function wireOffset(id){
      if(reclampers[id]){return;}
      var cell=els[id]; if(!cell){return;}
      var g=cell.querySelector(':scope > .cl-grip');
      var inner=document.createElement('div'); inner.className='cl-inner';
      Array.prototype.slice.call(cell.childNodes).forEach(function(n){ if(n!==g){inner.appendChild(n);} });
      cell.appendChild(inner); if(g){cell.appendChild(g);}
      var seed=(window.__CL_OFFSETS__||{})[id]||{}; var off={x:seed.x||0,y:seed.y||0};
      function apply(){ inner.style.transform='translate('+off.x+'px,'+off.y+'px)'; }
      function settleZ(){ cell.style.zIndex=(off.x||off.y)?'5':''; }
      function clamp(dx,dy){
        var cr=cell.getBoundingClientRect(), ir=inner.getBoundingClientRect();
        var nL=ir.left-off.x, nR=ir.right-off.x, nT=ir.top-off.y, nB=ir.bottom-off.y;
        var ax=cr.left-nL, bx=cr.right-nR, ay=cr.top-nT, by=cr.bottom-nB;
        off.x = ax>bx ? 0 : Math.max(ax, Math.min(bx, dx));
        off.y = ay>by ? 0 : Math.max(ay, Math.min(by, dy));
      }
      inner.style.cursor='move'; inner.title='Drag to reposition this content within its cell (use the grip to move the whole section)'; apply(); settleZ();
      reclampers[id]=function(){ clamp(off.x,off.y); apply(); settleZ(); };
      Array.prototype.forEach.call(inner.querySelectorAll('img'),function(im){
        if(!im.complete){ im.addEventListener('load',function(){ if(reclampers[id]){reclampers[id]();} },{once:true}); }
      });
      var sx,sy,so,drag=false;
      cell.addEventListener('pointerdown',function(e){
        if(g && (e.target===g || g.contains(e.target))){return;}
        e.preventDefault(); e.stopPropagation();
        drag=true; sx=e.clientX; sy=e.clientY; so={x:off.x,y:off.y};
        cell.style.zIndex='50';
        try{cell.setPointerCapture(e.pointerId);}catch(_){}
      });
      cell.addEventListener('pointermove',function(e){ if(!drag){return;} clamp(so.x+(e.clientX-sx), so.y+(e.clientY-sy)); apply(); });
      function end(e){ if(!drag){return;} drag=false; try{cell.releasePointerCapture(e.pointerId);}catch(_){}
        settleZ();
        emit({type:'crewlist-offset',id:id,offset:{x:Math.round(off.x),y:Math.round(off.y)}}); }
      cell.addEventListener('pointerup',end); cell.addEventListener('pointercancel',end);
    }
    function render(){
      root.innerHTML='';
      root.appendChild(gap(0));
      layout.forEach(function(row,ri){
        var rowEl=document.createElement('div'); rowEl.className='cl-row';
        row.forEach(function(id){ rowEl.appendChild(els[id]); });
        root.appendChild(rowEl);
        root.appendChild(gap(ri+1));
      });
      ['title','logo','company'].forEach(function(id){ wireBlock(id); wireOffset(id); });
      ['title','logo','company'].forEach(function(id){ if(reclampers[id]){reclampers[id]();} });
      requestAnimationFrame(function(){ ['title','logo','company'].forEach(function(id){ if(reclampers[id]){reclampers[id]();} }); });
    }
    render();
  }
  if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',init);}else{init();}
})();
</script>"""
}

/** What the Design canvas reported: a new arrangement, or one section nudged. */
sealed interface CrewCanvasMessage {
    data class Layout(val order: List<List<HeaderSection>>) : CrewCanvasMessage
    data class Offset(val section: HeaderSection, val offset: SectionOffset) : CrewCanvasMessage

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Null for anything that is not one of the two messages — the page never gets to write elsewhere. */
        fun parse(text: String): CrewCanvasMessage? {
            val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            return when ((message["type"] as? JsonPrimitive)?.contentOrNull) {
                "crewlist-layout" -> message["order"]
                    ?.toOrder()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Layout(HeaderArranger.sanitise(it)) }
                "crewlist-offset" -> {
                    val section = HeaderSection.of((message["id"] as? JsonPrimitive)?.contentOrNull) ?: return null
                    val offset = message["offset"] as? JsonObject ?: return null
                    Offset(section, SectionOffset(offset.number("x"), offset.number("y")))
                }
                else -> null
            }
        }

        private fun JsonObject.number(key: String): Int {
            val value = this[key] as? JsonPrimitive ?: return 0
            return value.intOrNull ?: value.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
        }
    }
}
