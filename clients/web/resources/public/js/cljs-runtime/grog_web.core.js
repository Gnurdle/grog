goog.provide('grog_web.core');
/**
 * Compact one-line summary of an ECA `usage` content.
 */
grog_web.core.usage_text = (function grog_web$core$usage_text(c){
return [cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"sessionTokens","sessionTokens",1892985457).cljs$core$IFn$_invoke$arity$1(c))," tok",(cljs.core.truth_(new cljs.core.Keyword(null,"lastMessageCost","lastMessageCost",-98551130).cljs$core$IFn$_invoke$arity$1(c))?[" \u00B7 msg $",cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"lastMessageCost","lastMessageCost",-98551130).cljs$core$IFn$_invoke$arity$1(c))].join(''):null),(cljs.core.truth_(new cljs.core.Keyword(null,"sessionCost","sessionCost",-642164836).cljs$core$IFn$_invoke$arity$1(c))?[" \u00B7 $",cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"sessionCost","sessionCost",-642164836).cljs$core$IFn$_invoke$arity$1(c))].join(''):null)].join('');
});
/**
 * Map one raw ECA `content` to a transcript segment, or nil to skip it.
 * 
 *   ECA streams many content types in a turn: reasoning (`reasonText` wrapped in
 *   `reasonStarted`/`reasonFinished`), tool lifecycle (`toolCallPrepare` →
 *   `toolCallRunning` → `toolCalled`/`toolCallRun`), plus `progress`/`metadata`
 *   chatter. Only the ones with user-meaningful payload are painted; the rest are
 *   dropped so they can't pollute the transcript (or fragment the answer stream).
 */
grog_web.core.line_of = (function grog_web$core$line_of(content){
var map__20707 = content;
var map__20707__$1 = cljs.core.__destructure_map(map__20707);
var type = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20707__$1,new cljs.core.Keyword(null,"type","type",1174270348));
var text = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20707__$1,new cljs.core.Keyword(null,"text","text",-1790561697));
var name = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20707__$1,new cljs.core.Keyword(null,"name","name",1843675177));
var summary = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20707__$1,new cljs.core.Keyword(null,"summary","summary",380847952));
var G__20708 = type;
switch (G__20708) {
case "text":
return new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"kind","kind",-717265803),new cljs.core.Keyword(null,"answer","answer",-742633163),new cljs.core.Keyword(null,"text","text",-1790561697),cljs.core.str.cljs$core$IFn$_invoke$arity$1((function (){var or__5002__auto__ = text;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return "";
}
})())], null);

break;
case "thinking":
return new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"kind","kind",-717265803),new cljs.core.Keyword(null,"thinking","thinking",2063777387),new cljs.core.Keyword(null,"text","text",-1790561697),cljs.core.str.cljs$core$IFn$_invoke$arity$1((function (){var or__5002__auto__ = text;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return "";
}
})())], null);

break;
case "reasonText":
return new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"kind","kind",-717265803),new cljs.core.Keyword(null,"thinking","thinking",2063777387),new cljs.core.Keyword(null,"text","text",-1790561697),cljs.core.str.cljs$core$IFn$_invoke$arity$1((function (){var or__5002__auto__ = text;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return "";
}
})())], null);

break;
case "toolCalled":
return new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"kind","kind",-717265803),new cljs.core.Keyword(null,"tool","tool",-1298696470),new cljs.core.Keyword(null,"text","text",-1790561697),["\u2699 ",cljs.core.str.cljs$core$IFn$_invoke$arity$1((function (){var or__5002__auto__ = summary;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
var or__5002__auto____$1 = name;
if(cljs.core.truth_(or__5002__auto____$1)){
return or__5002__auto____$1;
} else {
return "tool";
}
}
})())].join('')], null);

break;
case "toolCallRun":
return new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"kind","kind",-717265803),new cljs.core.Keyword(null,"tool","tool",-1298696470),new cljs.core.Keyword(null,"text","text",-1790561697),["\u2699 ",cljs.core.str.cljs$core$IFn$_invoke$arity$1((function (){var or__5002__auto__ = summary;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
var or__5002__auto____$1 = name;
if(cljs.core.truth_(or__5002__auto____$1)){
return or__5002__auto____$1;
} else {
return "tool";
}
}
})())].join('')], null);

break;
case "usage":
return new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"kind","kind",-717265803),new cljs.core.Keyword(null,"usage","usage",-1583752910),new cljs.core.Keyword(null,"text","text",-1790561697),grog_web.core.usage_text(content)], null);

break;
case "reasonStarted":
case "reasonFinished":
case "progress":
case "metadata":
case "toolCallPrepare":
case "toolCallRunning":
return null;

break;
default:
console.log("[grog-web] unhandled content type:",cljs.core.pr_str.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([type], 0)));

return null;

}
});
grog_web.core.project_name = "grog";
/**
 * Render one option from a question, tolerating ECA sending strings or maps.
 */
grog_web.core.opt_label = (function grog_web$core$opt_label(o){
if(typeof o === 'string'){
return o;
} else {
if(cljs.core.map_QMARK_(o)){
return cljs.core.str.cljs$core$IFn$_invoke$arity$1((function (){var or__5002__auto__ = new cljs.core.Keyword(null,"label","label",1718410804).cljs$core$IFn$_invoke$arity$1(o);
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
var or__5002__auto____$1 = new cljs.core.Keyword(null,"value","value",305978217).cljs$core$IFn$_invoke$arity$1(o);
if(cljs.core.truth_(or__5002__auto____$1)){
return or__5002__auto____$1;
} else {
var or__5002__auto____$2 = new cljs.core.Keyword(null,"text","text",-1790561697).cljs$core$IFn$_invoke$arity$1(o);
if(cljs.core.truth_(or__5002__auto____$2)){
return or__5002__auto____$2;
} else {
return cljs.core.pr_str.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([o], 0));
}
}
}
})());
} else {
return cljs.core.str.cljs$core$IFn$_invoke$arity$1(o);

}
}
});
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"init","init",-1875481434),(function (_,___$1){
return cljs.core.PersistentHashMap.fromArrays([new cljs.core.Keyword(null,"q-input","q-input",-1764562816),new cljs.core.Keyword(null,"projects","projects",-364845983),new cljs.core.Keyword(null,"connected?","connected?",-1197551387),new cljs.core.Keyword(null,"font-size","font-size",-1847940346),new cljs.core.Keyword(null,"socket-path","socket-path",-1920106584),new cljs.core.Keyword(null,"model-query","model-query",-2037530197),new cljs.core.Keyword(null,"voice","voice",185716428),new cljs.core.Keyword(null,"model-source","model-source",-1983623726),new cljs.core.Keyword(null,"transcribing?","transcribing?",771140466),new cljs.core.Keyword(null,"recording?","recording?",-1477514924),new cljs.core.Keyword(null,"active","active",1895962068),new cljs.core.Keyword(null,"catalogue","catalogue",224534933),new cljs.core.Keyword(null,"opened?","opened?",1096959669),new cljs.core.Keyword(null,"dialog","dialog",1415150135),new cljs.core.Keyword(null,"order","order",-1254677256),new cljs.core.Keyword(null,"sessions","sessions",-699316392),new cljs.core.Keyword(null,"dialog-input","dialog-input",-1508376519),new cljs.core.Keyword(null,"input","input",556931961),new cljs.core.Keyword(null,"focused?","focused?",-1922723333)],["",cljs.core.PersistentVector.EMPTY,false,(16),"","",null,"eca",false,false,null,null,false,null,cljs.core.PersistentVector.EMPTY,cljs.core.PersistentArrayMap.EMPTY,"","",true]);
}));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"db","db",993250759),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return db;
})], 0));
grog_web.core.sess_of = (function grog_web$core$sess_of(db,id){
return cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(db,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),id], null));
});
/**
 * Append a transcript segment. `nil` seg = nothing to paint (skipped). Consecutive
 *   :answer/:thinking runs coalesce into one growing blob; consecutive identical
 *   :tool/:line segments (lifecycle duplicates) are deduped.
 */
grog_web.core.append_segment = (function grog_web$core$append_segment(db,sid,seg){
if((seg == null)){
return db;
} else {
return cljs.core.update_in.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid,new cljs.core.Keyword(null,"transcript","transcript",-2018154566)], null),(function (t){
var t__$1 = (function (){var or__5002__auto__ = t;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return cljs.core.PersistentVector.EMPTY;
}
})();
var last_seg = cljs.core.peek(t__$1);
if(cljs.core.truth_((function (){var and__5000__auto__ = last_seg;
if(cljs.core.truth_(and__5000__auto__)){
return ((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"kind","kind",-717265803).cljs$core$IFn$_invoke$arity$1(last_seg),new cljs.core.Keyword(null,"kind","kind",-717265803).cljs$core$IFn$_invoke$arity$1(seg))) && (cljs.core.contains_QMARK_(new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"thinking","thinking",2063777387),null,new cljs.core.Keyword(null,"answer","answer",-742633163),null], null), null),new cljs.core.Keyword(null,"kind","kind",-717265803).cljs$core$IFn$_invoke$arity$1(seg))));
} else {
return and__5000__auto__;
}
})())){
return cljs.core.conj.cljs$core$IFn$_invoke$arity$2(cljs.core.pop(t__$1),cljs.core.update.cljs$core$IFn$_invoke$arity$4(last_seg,new cljs.core.Keyword(null,"text","text",-1790561697),cljs.core.str,new cljs.core.Keyword(null,"text","text",-1790561697).cljs$core$IFn$_invoke$arity$1(seg)));
} else {
if(cljs.core.truth_((function (){var and__5000__auto__ = last_seg;
if(cljs.core.truth_(and__5000__auto__)){
return ((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"text","text",-1790561697).cljs$core$IFn$_invoke$arity$1(last_seg),new cljs.core.Keyword(null,"text","text",-1790561697).cljs$core$IFn$_invoke$arity$1(seg))) && (cljs.core.contains_QMARK_(new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"tool","tool",-1298696470),null,new cljs.core.Keyword(null,"line","line",212345235),null], null), null),new cljs.core.Keyword(null,"kind","kind",-717265803).cljs$core$IFn$_invoke$arity$1(seg))));
} else {
return and__5000__auto__;
}
})())){
return t__$1;
} else {
return cljs.core.conj.cljs$core$IFn$_invoke$arity$2(t__$1,seg);

}
}
}));
}
});
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"open-ok","open-ok",1132719375),(function (db,p__20709){
var vec__20710 = p__20709;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20710,(0),null);
var snap = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20710,(1),null);
var id = new cljs.core.Keyword(null,"id","id",-1388402092).cljs$core$IFn$_invoke$arity$1(snap);
if(cljs.core.contains_QMARK_(new cljs.core.Keyword(null,"sessions","sessions",-699316392).cljs$core$IFn$_invoke$arity$1(db),id)){
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$variadic(db,new cljs.core.Keyword(null,"active","active",1895962068),id,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"opened?","opened?",1096959669),true], 0));
} else {
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$variadic(cljs.core.update.cljs$core$IFn$_invoke$arity$4(cljs.core.assoc_in(db,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),id], null),new cljs.core.PersistentArrayMap(null, 7, [new cljs.core.Keyword(null,"id","id",-1388402092),id,new cljs.core.Keyword(null,"project","project",1124394579),new cljs.core.Keyword(null,"project","project",1124394579).cljs$core$IFn$_invoke$arity$1(snap),new cljs.core.Keyword(null,"model","model",331153215),new cljs.core.Keyword(null,"model","model",331153215).cljs$core$IFn$_invoke$arity$1(snap),new cljs.core.Keyword(null,"status","status",-1997798413),(function (){var or__5002__auto__ = new cljs.core.Keyword(null,"status","status",-1997798413).cljs$core$IFn$_invoke$arity$1(snap);
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return "idle";
}
})(),new cljs.core.Keyword(null,"trust","trust",-650115463),cljs.core.boolean$(new cljs.core.Keyword(null,"trust","trust",-650115463).cljs$core$IFn$_invoke$arity$1(snap)),new cljs.core.Keyword(null,"running?","running?",-257884763),cljs.core.boolean$(new cljs.core.Keyword(null,"running?","running?",-257884763).cljs$core$IFn$_invoke$arity$1(snap)),new cljs.core.Keyword(null,"transcript","transcript",-2018154566),(cljs.core.truth_(new cljs.core.Keyword(null,"banner","banner",177448281).cljs$core$IFn$_invoke$arity$1(snap))?new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"kind","kind",-717265803),new cljs.core.Keyword(null,"snark","snark",1260821788),new cljs.core.Keyword(null,"text","text",-1790561697),cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"banner","banner",177448281).cljs$core$IFn$_invoke$arity$1(snap))], null)], null):cljs.core.PersistentVector.EMPTY)], null)),new cljs.core.Keyword(null,"order","order",-1254677256),cljs.core.fnil.cljs$core$IFn$_invoke$arity$2(cljs.core.conj,cljs.core.PersistentVector.EMPTY),id),new cljs.core.Keyword(null,"active","active",1895962068),id,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"opened?","opened?",1096959669),true], 0));
}
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"select-tab","select-tab",-1719420452),(function (db,p__20713){
var vec__20714 = p__20713;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20714,(0),null);
var id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20714,(1),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"active","active",1895962068),id);
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"focus","focus",234677911),(function (db,p__20717){
var vec__20718 = p__20717;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20718,(0),null);
var f = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20718,(1),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"focused?","focused?",-1922723333),f);
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"input","input",556931961),(function (db,p__20721){
var vec__20722 = p__20721;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20722,(0),null);
var s = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20722,(1),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"input","input",556931961),s);
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"q-input","q-input",-1764562816),(function (db,p__20725){
var vec__20726 = p__20725;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20726,(0),null);
var s = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20726,(1),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"q-input","q-input",-1764562816),s);
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"connected","connected",-169833045),(function (db,p__20729){
var vec__20730 = p__20729;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20730,(0),null);
var on_QMARK_ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20730,(1),null);
var path = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20730,(2),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$variadic(db,new cljs.core.Keyword(null,"connected?","connected?",-1197551387),on_QMARK_,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"socket-path","socket-path",-1920106584),(function (){var or__5002__auto__ = path;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return new cljs.core.Keyword(null,"socket-path","socket-path",-1920106584).cljs$core$IFn$_invoke$arity$1(db);
}
})()], 0));
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"retry-status","retry-status",-546801812),(function (db,p__20733){
var vec__20734 = p__20733;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20734,(0),null);
var r = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20734,(1),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"retry","retry",-614012896),r);
}));
if((typeof grog_web !== 'undefined') && (typeof grog_web.core !== 'undefined') && (typeof grog_web.core.voice_timer !== 'undefined')){
} else {
grog_web.core.voice_timer = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(null);
}
grog_web.core.focus_composer_BANG_ = (function grog_web$core$focus_composer_BANG_(){
return setTimeout((function (){
var temp__5823__auto__ = document.getElementById("grog-composer");
if(cljs.core.truth_(temp__5823__auto__)){
var el = temp__5823__auto__;
return el.focus();
} else {
return null;
}
}),(0));
});
grog_web.core.clear_voice_timer_BANG_ = (function grog_web$core$clear_voice_timer_BANG_(){
var temp__5823__auto___20993 = cljs.core.deref(grog_web.core.voice_timer);
if(cljs.core.truth_(temp__5823__auto___20993)){
var t_20994 = temp__5823__auto___20993;
clearTimeout(t_20994);
} else {
}

return cljs.core.reset_BANG_(grog_web.core.voice_timer,null);
});
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"voice-status","voice-status",699058004),(function (db,p__20737){
var vec__20738 = p__20737;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20738,(0),null);
var s = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20738,(1),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"voice","voice",185716428),s);
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"recording","recording",322996097),(function (db,p__20741){
var vec__20742 = p__20741;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20742,(0),null);
var on_QMARK_ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20742,(1),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"recording?","recording?",-1477514924),cljs.core.boolean$(on_QMARK_));
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"transcribing","transcribing",-892403696),(function (db,p__20745){
var vec__20746 = p__20745;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20746,(0),null);
var on_QMARK_ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20746,(1),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"transcribing?","transcribing?",771140466),cljs.core.boolean$(on_QMARK_));
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"insert-transcript","insert-transcript",-2128265462),(function (db,p__20749){
var vec__20750 = p__20749;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20750,(0),null);
var text = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20750,(1),null);
var cur = cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"input","input",556931961).cljs$core$IFn$_invoke$arity$1(db));
var sep = ((((cljs.core.seq(cur)) && ((!(clojure.string.ends_with_QMARK_(cur," "))))))?" ":"");
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"input","input",556931961),[cur,sep,cljs.core.str.cljs$core$IFn$_invoke$arity$1(text)].join(''));
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"voice-auto-stop","voice-auto-stop",-2001323719),(function (p__20753,_){
var map__20754 = p__20753;
var map__20754__$1 = cljs.core.__destructure_map(map__20754);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20754__$1,new cljs.core.Keyword(null,"db","db",993250759));
if(cljs.core.truth_(new cljs.core.Keyword(null,"recording?","recording?",-1477514924).cljs$core$IFn$_invoke$arity$1(db))){
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"toggle-voice","toggle-voice",1481320800)], null)], null);
} else {
return cljs.core.PersistentArrayMap.EMPTY;
}
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"toggle-voice","toggle-voice",1481320800),(function (p__20755,_){
var map__20756 = p__20755;
var map__20756__$1 = cljs.core.__destructure_map(map__20756);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20756__$1,new cljs.core.Keyword(null,"db","db",993250759));
var st = new cljs.core.Keyword(null,"voice","voice",185716428).cljs$core$IFn$_invoke$arity$1(db);
var enabled_QMARK_ = (function (){var and__5000__auto__ = st;
if(cljs.core.truth_(and__5000__auto__)){
return new cljs.core.Keyword(null,"enabled","enabled",1195909756).cljs$core$IFn$_invoke$arity$1(st);
} else {
return and__5000__auto__;
}
})();
if(cljs.core.truth_(new cljs.core.Keyword(null,"transcribing?","transcribing?",771140466).cljs$core$IFn$_invoke$arity$1(db))){
return cljs.core.PersistentArrayMap.EMPTY;
} else {
if(cljs.core.truth_(new cljs.core.Keyword(null,"recording?","recording?",-1477514924).cljs$core$IFn$_invoke$arity$1(db))){
grog_web.core.clear_voice_timer_BANG_();

re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"recording","recording",322996097),false], null));

re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"transcribing","transcribing",-892403696),true], null));

grog_web.voice.stop_BANG_().then((function (res){
return window.grogAPI.voiceTranscribe(new cljs.core.Keyword(null,"wav","wav",270623362).cljs$core$IFn$_invoke$arity$1(res)).then((function (r){
re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"transcribing","transcribing",-892403696),false], null));

var txt = cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"text","text",-1790561697).cljs$core$IFn$_invoke$arity$1(cljs.core.js__GT_clj.cljs$core$IFn$_invoke$arity$variadic(r,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"keywordize-keys","keywordize-keys",1310784252),true], 0))));
if(cljs.core.seq(clojure.string.trim(txt))){
re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"insert-transcript","insert-transcript",-2128265462),txt], null));

grog_web.core.focus_composer_BANG_();

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,"[grog] voice: inserted transcript"], null));
} else {
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,"[grog] voice: no transcript"], null));
}
})).catch((function (e){
re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"transcribing","transcribing",-892403696),false], null));

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,["[grog] voice failed: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e.message)].join('')], null));
}));
})).catch((function (e){
re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"transcribing","transcribing",-892403696),false], null));

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,["[grog] voice capture failed: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e.message)].join('')], null));
}));

return cljs.core.PersistentArrayMap.EMPTY;
} else {
if(cljs.core.not(enabled_QMARK_)){
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,"[grog] voice off \u2014 set GROG_VOICE_COMMAND"], null)], null);
} else {
if((!(grog_web.voice.supported_QMARK_()))){
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,"[grog] microphone unavailable in this window"], null)], null);
} else {
grog_web.voice.start_BANG_().then((function (___$1){
re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"recording","recording",322996097),true], null));

re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,"[grog] recording\u2026 click the mic again to stop"], null));

grog_web.core.clear_voice_timer_BANG_();

return cljs.core.reset_BANG_(grog_web.core.voice_timer,setTimeout((function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"voice-auto-stop","voice-auto-stop",-2001323719)], null));
}),((1000) * (function (){var or__5002__auto__ = new cljs.core.Keyword(null,"maxSeconds","maxSeconds",618838596).cljs$core$IFn$_invoke$arity$1(st);
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return (60);
}
})())));
})).catch((function (e){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,["[grog] mic error: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e.message)].join('')], null));
}));

return cljs.core.PersistentArrayMap.EMPTY;

}
}
}
}
}));
grog_web.core.min_font = (11);
grog_web.core.max_font = (30);
grog_web.core.default_font = (16);
grog_web.core.clamp_font = (function grog_web$core$clamp_font(n){
var x__5090__auto__ = (function (){var x__5087__auto__ = (n | (0));
var y__5088__auto__ = grog_web.core.min_font;
return ((x__5087__auto__ > y__5088__auto__) ? x__5087__auto__ : y__5088__auto__);
})();
var y__5091__auto__ = grog_web.core.max_font;
return ((x__5090__auto__ < y__5091__auto__) ? x__5090__auto__ : y__5091__auto__);
});
grog_web.core.apply_font_BANG_ = (function grog_web$core$apply_font_BANG_(n){
try{(document.documentElement.style.fontSize = [cljs.core.str.cljs$core$IFn$_invoke$arity$1(n),"px"].join(''));
}catch (e20757){var __20995 = e20757;
}
try{return window.localStorage.setItem("grog-web.font-size",cljs.core.str.cljs$core$IFn$_invoke$arity$1(n));
}catch (e20758){var _ = e20758;
return null;
}});
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"set-font-size","set-font-size",-804794238),(function (p__20759,p__20760){
var map__20761 = p__20759;
var map__20761__$1 = cljs.core.__destructure_map(map__20761);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20761__$1,new cljs.core.Keyword(null,"db","db",993250759));
var vec__20762 = p__20760;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20762,(0),null);
var n = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20762,(1),null);
var n__$1 = grog_web.core.clamp_font(n);
grog_web.core.apply_font_BANG_(n__$1);

return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"db","db",993250759),cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"font-size","font-size",-1847940346),n__$1)], null);
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"bump-font","bump-font",-1491756021),(function (p__20765,p__20766){
var map__20767 = p__20765;
var map__20767__$1 = cljs.core.__destructure_map(map__20767);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20767__$1,new cljs.core.Keyword(null,"db","db",993250759));
var vec__20768 = p__20766;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20768,(0),null);
var d = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20768,(1),null);
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"set-font-size","set-font-size",-804794238),((function (){var or__5002__auto__ = new cljs.core.Keyword(null,"font-size","font-size",-1847940346).cljs$core$IFn$_invoke$arity$1(db);
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return grog_web.core.default_font;
}
})() + d)], null)], null);
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"dialog-open","dialog-open",2100079327),(function (p__20771,p__20772){
var map__20773 = p__20771;
var map__20773__$1 = cljs.core.__destructure_map(map__20773);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20773__$1,new cljs.core.Keyword(null,"db","db",993250759));
var vec__20774 = p__20772;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20774,(0),null);
var kind = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20774,(1),null);
var initial = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20774,(2),null);
var G__20777 = new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"db","db",993250759),cljs.core.assoc.cljs$core$IFn$_invoke$arity$variadic(db,new cljs.core.Keyword(null,"dialog","dialog",1415150135),kind,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"dialog-input","dialog-input",-1508376519),(function (){var or__5002__auto__ = initial;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return "";
}
})()], 0))], null);
var G__20777__$1 = ((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(kind,new cljs.core.Keyword(null,"projects","projects",-364845983)))?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__20777,new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"fetch-projects","fetch-projects",1303051401)], null)):G__20777);
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(kind,new cljs.core.Keyword(null,"settings","settings",1556144875))){
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__20777__$1,new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"models-fetch","models-fetch",-358237643),null], null));
} else {
return G__20777__$1;
}
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"projects-loaded","projects-loaded",455611062),(function (db,p__20778){
var vec__20779 = p__20778;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20779,(0),null);
var xs = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20779,(1),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"projects","projects",-364845983),cljs.core.vec(xs));
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"fetch-projects","fetch-projects",1303051401),(function (_,___$1){
window.grogAPI.call("projects",cljs.core.clj__GT_js(cljs.core.PersistentArrayMap.EMPTY)).then((function (xs){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"projects-loaded","projects-loaded",455611062),cljs.core.js__GT_clj.cljs$core$IFn$_invoke$arity$variadic(xs,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"keywordize-keys","keywordize-keys",1310784252),true], 0))], null));
})).catch((function (e){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,["[grog] projects failed: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e.message)].join('')], null));
}));

return cljs.core.PersistentArrayMap.EMPTY;
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"dialog-input","dialog-input",-1508376519),(function (db,p__20782){
var vec__20783 = p__20782;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20783,(0),null);
var s = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20783,(1),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"dialog-input","dialog-input",-1508376519),s);
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"dialog-close","dialog-close",180756817),(function (db,_){
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"dialog","dialog",1415150135),null);
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"models-source","models-source",-2075783156),(function (db,p__20786){
var vec__20787 = p__20786;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20787,(0),null);
var source = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20787,(1),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$variadic(db,new cljs.core.Keyword(null,"model-source","model-source",-1983623726),source,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"model-query","model-query",-2037530197),""], 0));
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"model-query","model-query",-2037530197),(function (db,p__20790){
var vec__20791 = p__20790;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20791,(0),null);
var q = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20791,(1),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"model-query","model-query",-2037530197),q);
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"catalogue","catalogue",224534933),(function (db,p__20794){
var vec__20795 = p__20794;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20795,(0),null);
var cat = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20795,(1),null);
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"catalogue","catalogue",224534933),cljs.core.js__GT_clj.cljs$core$IFn$_invoke$arity$variadic(cat,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"keywordize-keys","keywordize-keys",1310784252),true], 0)));
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"models-source-loaded","models-source-loaded",353761990),(function (db,p__20798){
var vec__20799 = p__20798;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20799,(0),null);
var map__20802 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20799,(1),null);
var map__20802__$1 = cljs.core.__destructure_map(map__20802);
var source = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20802__$1,new cljs.core.Keyword(null,"source","source",-433931539));
var models = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20802__$1,new cljs.core.Keyword(null,"models","models",-1985455662));
return cljs.core.assoc_in(db,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"catalogue","catalogue",224534933),cljs.core.keyword.cljs$core$IFn$_invoke$arity$1(source)], null),cljs.core.vec(models));
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$3(new cljs.core.Keyword(null,"models-fetch","models-fetch",-358237643),"Ask for `source`'s models (default: whichever source is being browsed).\n  `force` re-fetches a source we already have cached.",(function (p__20803,p__20804){
var map__20805 = p__20803;
var map__20805__$1 = cljs.core.__destructure_map(map__20805);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20805__$1,new cljs.core.Keyword(null,"db","db",993250759));
var vec__20806 = p__20804;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20806,(0),null);
var force = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20806,(1),null);
window.grogAPI.call("models",cljs.core.clj__GT_js((function (){var G__20809 = new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"source","source",-433931539),(function (){var or__5002__auto__ = new cljs.core.Keyword(null,"model-source","model-source",-1983623726).cljs$core$IFn$_invoke$arity$1(db);
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return "eca";
}
})()], null);
if(cljs.core.truth_(force)){
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__20809,new cljs.core.Keyword(null,"force","force",781957286),true);
} else {
return G__20809;
}
})())).then((function (cat){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"catalogue","catalogue",224534933),cat], null));
})).catch((function (e){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,["[grog] models failed: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e.message)].join('')], null));
}));

return cljs.core.PersistentArrayMap.EMPTY;
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"status-line","status-line",-1007802282),(function (db,p__20810){
var vec__20811 = p__20810;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20811,(0),null);
var sid = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20811,(1),null);
var text = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20811,(2),null);
var sid__$1 = (function (){var or__5002__auto__ = sid;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return new cljs.core.Keyword(null,"active","active",1895962068).cljs$core$IFn$_invoke$arity$1(db);
}
})();
var G__20814 = db;
if(cljs.core.truth_(sid__$1)){
return cljs.core.update_in.cljs$core$IFn$_invoke$arity$4(G__20814,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid__$1,new cljs.core.Keyword(null,"transcript","transcript",-2018154566)], null),cljs.core.fnil.cljs$core$IFn$_invoke$arity$2(cljs.core.conj,cljs.core.PersistentVector.EMPTY),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"kind","kind",-717265803),new cljs.core.Keyword(null,"line","line",212345235),new cljs.core.Keyword(null,"text","text",-1790561697),cljs.core.str.cljs$core$IFn$_invoke$arity$1(text)], null));
} else {
return G__20814;
}
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"ev","ev",-406827324),(function (db,p__20815){
var vec__20816 = p__20815;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20816,(0),null);
var wire = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20816,(1),null);
var sid = new cljs.core.Keyword(null,"sessionId","sessionId",1640410629).cljs$core$IFn$_invoke$arity$1(wire);
var ev = (function (){var G__20819 = wire;
if(typeof new cljs.core.Keyword(null,"type","type",1174270348).cljs$core$IFn$_invoke$arity$1(wire) === 'string'){
return cljs.core.update.cljs$core$IFn$_invoke$arity$3(G__20819,new cljs.core.Keyword(null,"type","type",1174270348),cljs.core.keyword);
} else {
return G__20819;
}
})();
var kind = new cljs.core.Keyword(null,"type","type",1174270348).cljs$core$IFn$_invoke$arity$1(ev);
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"content","content",15833224),kind)){
var c = new cljs.core.Keyword(null,"content","content",15833224).cljs$core$IFn$_invoke$arity$1(ev);
var db2 = grog_web.core.append_segment(db,sid,grog_web.core.line_of(c));
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2("usage",new cljs.core.Keyword(null,"type","type",1174270348).cljs$core$IFn$_invoke$arity$1(c))){
return cljs.core.assoc_in(db2,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid,new cljs.core.Keyword(null,"usage","usage",-1583752910)], null),c);
} else {
return db2;
}
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"user","user",1532431356),kind)){
return grog_web.core.append_segment(db,sid,new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"kind","kind",-717265803),new cljs.core.Keyword(null,"user","user",1532431356),new cljs.core.Keyword(null,"text","text",-1790561697),cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"text","text",-1790561697).cljs$core$IFn$_invoke$arity$1(ev))], null));
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"line","line",212345235),kind)){
return grog_web.core.append_segment(db,sid,new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"kind","kind",-717265803),new cljs.core.Keyword(null,"line","line",212345235),new cljs.core.Keyword(null,"text","text",-1790561697),cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"text","text",-1790561697).cljs$core$IFn$_invoke$arity$1(ev))], null));
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"status","status",-1997798413),kind)){
return cljs.core.assoc_in(db,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid,new cljs.core.Keyword(null,"status","status",-1997798413)], null),cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"value","value",305978217).cljs$core$IFn$_invoke$arity$1(ev)));
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"trust","trust",-650115463),kind)){
return cljs.core.assoc_in(db,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid,new cljs.core.Keyword(null,"trust","trust",-650115463)], null),cljs.core.boolean$(new cljs.core.Keyword(null,"value","value",305978217).cljs$core$IFn$_invoke$arity$1(ev)));
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"model","model",331153215),kind)){
return cljs.core.assoc_in(db,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid,new cljs.core.Keyword(null,"model","model",331153215)], null),new cljs.core.Keyword(null,"value","value",305978217).cljs$core$IFn$_invoke$arity$1(ev));
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"clear","clear",1877104959),kind)){
return cljs.core.assoc_in(db,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid,new cljs.core.Keyword(null,"transcript","transcript",-2018154566)], null),cljs.core.PersistentVector.EMPTY);
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"approval","approval",396657342),kind)){
return cljs.core.assoc_in(db,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid,new cljs.core.Keyword(null,"pending-approval","pending-approval",707430381)], null),cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(ev,new cljs.core.Keyword(null,"answer","answer",-742633163)));
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"question","question",-1411720117),kind)){
return cljs.core.update_in.cljs$core$IFn$_invoke$arity$4(cljs.core.assoc_in(db,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid,new cljs.core.Keyword(null,"pending-question","pending-question",393981712)], null),cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(ev,new cljs.core.Keyword(null,"answer","answer",-742633163))),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid,new cljs.core.Keyword(null,"transcript","transcript",-2018154566)], null),cljs.core.fnil.cljs$core$IFn$_invoke$arity$2(cljs.core.conj,cljs.core.PersistentVector.EMPTY),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"kind","kind",-717265803),new cljs.core.Keyword(null,"line","line",212345235),new cljs.core.Keyword(null,"text","text",-1790561697),"[LLM question pending]"], null));
} else {
return db;

}
}
}
}
}
}
}
}
}
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"running","running",1554969103),(function (db,p__20820){
var vec__20821 = p__20820;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20821,(0),null);
var sid = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20821,(1),null);
var on_QMARK_ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20821,(2),null);
return cljs.core.assoc_in(db,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid,new cljs.core.Keyword(null,"running?","running?",-257884763)], null),cljs.core.boolean$(on_QMARK_));
}));
re_frame.core.reg_event_db.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"jump","jump",-971319427),(function (db,p__20824){
var vec__20825 = p__20824;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20825,(0),null);
var n = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20825,(1),null);
var temp__5821__auto__ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(new cljs.core.Keyword(null,"order","order",-1254677256).cljs$core$IFn$_invoke$arity$1(db),(n - (1)),null);
if(cljs.core.truth_(temp__5821__auto__)){
var id = temp__5821__auto__;
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"active","active",1895962068),id);
} else {
return db;
}
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"cycle","cycle",710365284),(function (p__20828,p__20829){
var map__20830 = p__20828;
var map__20830__$1 = cljs.core.__destructure_map(map__20830);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20830__$1,new cljs.core.Keyword(null,"db","db",993250759));
var vec__20831 = p__20829;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20831,(0),null);
var dir = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20831,(1),null);
var order = new cljs.core.Keyword(null,"order","order",-1254677256).cljs$core$IFn$_invoke$arity$1(db);
var n = cljs.core.count(order);
var active = new cljs.core.Keyword(null,"active","active",1895962068).cljs$core$IFn$_invoke$arity$1(db);
if((n > (1))){
var i = cljs.core.to_array((function (){var or__5002__auto__ = order;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return cljs.core.PersistentVector.EMPTY;
}
})()).indexOf(active);
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"db","db",993250759),cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"active","active",1895962068),cljs.core.nth.cljs$core$IFn$_invoke$arity$2(order,cljs.core.mod((i + dir),n)))], null);
} else {
return cljs.core.PersistentArrayMap.EMPTY;
}
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"close-active","close-active",-1194859253),(function (p__20834,p__20835){
var map__20836 = p__20834;
var map__20836__$1 = cljs.core.__destructure_map(map__20836);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20836__$1,new cljs.core.Keyword(null,"db","db",993250759));
var vec__20837 = p__20835;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20837,(0),null);
var id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20837,(1),null);
var id__$1 = (function (){var or__5002__auto__ = id;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return new cljs.core.Keyword(null,"active","active",1895962068).cljs$core$IFn$_invoke$arity$1(db);
}
})();
var order = new cljs.core.Keyword(null,"order","order",-1254677256).cljs$core$IFn$_invoke$arity$1(db);
if((cljs.core.count(order) > (1))){
window.grogAPI.call("close",cljs.core.clj__GT_js(new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"id","id",-1388402092),id__$1], null))).catch((function (___$1){
return null;
}));

var new_order = cljs.core.vec(cljs.core.remove.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentHashSet.createAsIfByAssoc([id__$1]),order));
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"db","db",993250759),cljs.core.update.cljs$core$IFn$_invoke$arity$4(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"order","order",-1254677256),new_order),new cljs.core.Keyword(null,"active","active",1895962068),((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(id__$1,new cljs.core.Keyword(null,"active","active",1895962068).cljs$core$IFn$_invoke$arity$1(db)))?cljs.core.first(new_order):new cljs.core.Keyword(null,"active","active",1895962068).cljs$core$IFn$_invoke$arity$1(db))),new cljs.core.Keyword(null,"sessions","sessions",-699316392),cljs.core.dissoc,id__$1)], null);
} else {
re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),id__$1,"[grog] can't close the last tab"], null));

return cljs.core.PersistentArrayMap.EMPTY;
}
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"key-escape","key-escape",-196986402),(function (p__20840,_){
var map__20841 = p__20840;
var map__20841__$1 = cljs.core.__destructure_map(map__20841);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20841__$1,new cljs.core.Keyword(null,"db","db",993250759));
var sid = new cljs.core.Keyword(null,"active","active",1895962068).cljs$core$IFn$_invoke$arity$1(db);
var sess = grog_web.core.sess_of(db,sid);
if(cljs.core.truth_(new cljs.core.Keyword(null,"dialog","dialog",1415150135).cljs$core$IFn$_invoke$arity$1(db))){
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"db","db",993250759),cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"dialog","dialog",1415150135),null)], null);
} else {
if(cljs.core.truth_((function (){var and__5000__auto__ = new cljs.core.Keyword(null,"focused?","focused?",-1922723333).cljs$core$IFn$_invoke$arity$1(db);
if(cljs.core.truth_(and__5000__auto__)){
return new cljs.core.Keyword(null,"pending-approval","pending-approval",707430381).cljs$core$IFn$_invoke$arity$1(sess);
} else {
return and__5000__auto__;
}
})())){
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"answer-approval","answer-approval",-1317205169),sid,new cljs.core.Keyword(null,"id","id",-1388402092).cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"pending-approval","pending-approval",707430381).cljs$core$IFn$_invoke$arity$1(sess)),new cljs.core.Keyword(null,"reject","reject",1415953113)], null)], null);
} else {
if(cljs.core.truth_((function (){var and__5000__auto__ = new cljs.core.Keyword(null,"focused?","focused?",-1922723333).cljs$core$IFn$_invoke$arity$1(db);
if(cljs.core.truth_(and__5000__auto__)){
return new cljs.core.Keyword(null,"pending-question","pending-question",393981712).cljs$core$IFn$_invoke$arity$1(sess);
} else {
return and__5000__auto__;
}
})())){
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"answer-question","answer-question",437271305),sid,new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"cancelled","cancelled",488726224),true,new cljs.core.Keyword(null,"answer","answer",-742633163),null], null)], null)], null);
} else {
return cljs.core.PersistentArrayMap.EMPTY;

}
}
}
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"notify","notify",-1256867814),(function (_,p__20842){
var vec__20843 = p__20842;
var ___$1 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20843,(0),null);
var msg = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20843,(1),null);
var G__20846 = new cljs.core.Keyword(null,"method","method",55703592).cljs$core$IFn$_invoke$arity$1(msg);
switch (G__20846) {
case "event":
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"ev","ev",-406827324),new cljs.core.Keyword(null,"params","params",710516235).cljs$core$IFn$_invoke$arity$1(msg)], null)], null);

break;
case "question":
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"ev","ev",-406827324),cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(new cljs.core.Keyword(null,"params","params",710516235).cljs$core$IFn$_invoke$arity$1(msg),new cljs.core.Keyword(null,"type","type",1174270348),"question")], null)], null);

break;
case "server-status":
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"dispatch-n","dispatch-n",-504469236),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"connected","connected",-169833045),new cljs.core.Keyword(null,"connected","connected",-169833045).cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"params","params",710516235).cljs$core$IFn$_invoke$arity$1(msg)),new cljs.core.Keyword(null,"path","path",-188191168).cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"params","params",710516235).cljs$core$IFn$_invoke$arity$1(msg))], null),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"retry-status","retry-status",-546801812),cljs.core.select_keys(new cljs.core.Keyword(null,"params","params",710516235).cljs$core$IFn$_invoke$arity$1(msg),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"attempts","attempts",1024246729),new cljs.core.Keyword(null,"nextRetryAt","nextRetryAt",262686636),new cljs.core.Keyword(null,"retrying","retrying",34468113)], null))], null),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"retry-open","retry-open",-669833220)], null)], null)], null);

break;
case "server-down":
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,new cljs.core.Keyword(null,"text","text",-1790561697).cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"params","params",710516235).cljs$core$IFn$_invoke$arity$1(msg))], null)], null);

break;
case "models":
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"models-source-loaded","models-source-loaded",353761990),new cljs.core.Keyword(null,"params","params",710516235).cljs$core$IFn$_invoke$arity$1(msg)], null)], null);

break;
default:
return null;

}
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"open","open",-1763596448),(function (_,p__20847){
var vec__20848 = p__20847;
var ___$1 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20848,(0),null);
var project = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20848,(1),null);
window.grogAPI.call("open",cljs.core.clj__GT_js(new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"project","project",1124394579),project], null))).then((function (snap){
var snap__$1 = cljs.core.js__GT_clj.cljs$core$IFn$_invoke$arity$variadic(snap,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"keywordize-keys","keywordize-keys",1310784252),true], 0));
re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"open-ok","open-ok",1132719375),snap__$1], null));

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"connect","connect",1232828233),new cljs.core.Keyword(null,"id","id",-1388402092).cljs$core$IFn$_invoke$arity$1(snap__$1)], null));
})).catch((function (e){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,["[grog] open failed: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e.message)].join('')], null));
}));

return cljs.core.PersistentArrayMap.EMPTY;
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"open-new","open-new",1883565920),(function (p__20851,_){
var map__20852 = p__20851;
var map__20852__$1 = cljs.core.__destructure_map(map__20852);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20852__$1,new cljs.core.Keyword(null,"db","db",993250759));
var nm = clojure.string.trim(cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"dialog-input","dialog-input",-1508376519).cljs$core$IFn$_invoke$arity$1(db)));
if(cljs.core.seq(nm)){
window.grogAPI.call("create-project",cljs.core.clj__GT_js(new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"name","name",1843675177),nm], null))).then((function (___$1){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"open","open",-1763596448),nm], null));
})).catch((function (___$1){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"open","open",-1763596448),nm], null));
}));

return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"db","db",993250759),cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"dialog","dialog",1415150135),null)], null);
} else {
return cljs.core.PersistentArrayMap.EMPTY;
}
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"pick-project","pick-project",1625273181),(function (_,p__20853){
var vec__20854 = p__20853;
var ___$1 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20854,(0),null);
var nm = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20854,(1),null);
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"dispatch-n","dispatch-n",-504469236),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"dialog-close","dialog-close",180756817)], null),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"open","open",-1763596448),nm], null)], null)], null);
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"connect","connect",1232828233),(function (_,p__20857){
var vec__20858 = p__20857;
var ___$1 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20858,(0),null);
var id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20858,(1),null);
window.grogAPI.call("connect",cljs.core.clj__GT_js(new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"id","id",-1388402092),id], null))).catch((function (e){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),id,["[grog] connect failed: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e.message)].join('')], null));
}));

return cljs.core.PersistentArrayMap.EMPTY;
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"send","send",-652151114),(function (p__20861,_){
var map__20862 = p__20861;
var map__20862__$1 = cljs.core.__destructure_map(map__20862);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20862__$1,new cljs.core.Keyword(null,"db","db",993250759));
var id = new cljs.core.Keyword(null,"active","active",1895962068).cljs$core$IFn$_invoke$arity$1(db);
var txt = new cljs.core.Keyword(null,"input","input",556931961).cljs$core$IFn$_invoke$arity$1(db);
if(cljs.core.truth_((function (){var and__5000__auto__ = id;
if(cljs.core.truth_(and__5000__auto__)){
return cljs.core.seq(clojure.string.trim(txt));
} else {
return and__5000__auto__;
}
})())){
re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"running","running",1554969103),id,true], null));

window.grogAPI.call("prompt",cljs.core.clj__GT_js(new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"id","id",-1388402092),id,new cljs.core.Keyword(null,"text","text",-1790561697),txt], null))).catch((function (e){
re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"running","running",1554969103),id,false], null));

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),id,["[grog] prompt failed: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e.message)].join('')], null));
}));

return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"db","db",993250759),cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"input","input",556931961),"")], null);
} else {
return cljs.core.PersistentArrayMap.EMPTY;
}
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"stop","stop",-2140911342),(function (p__20863,_){
var map__20864 = p__20863;
var map__20864__$1 = cljs.core.__destructure_map(map__20864);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20864__$1,new cljs.core.Keyword(null,"db","db",993250759));
var temp__5823__auto___20997 = new cljs.core.Keyword(null,"active","active",1895962068).cljs$core$IFn$_invoke$arity$1(db);
if(cljs.core.truth_(temp__5823__auto___20997)){
var id_20998 = temp__5823__auto___20997;
window.grogAPI.call("stop",cljs.core.clj__GT_js(new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"id","id",-1388402092),id_20998], null))).catch((function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),id_20998,"[grog] stop failed"], null));
}));

re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"running","running",1554969103),id_20998,false], null));
} else {
}

return cljs.core.PersistentArrayMap.EMPTY;
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"toggle-trust","toggle-trust",-1518698863),(function (p__20865,p__20866){
var map__20867 = p__20865;
var map__20867__$1 = cljs.core.__destructure_map(map__20867);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20867__$1,new cljs.core.Keyword(null,"db","db",993250759));
var vec__20868 = p__20866;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20868,(0),null);
var id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20868,(1),null);
var on = cljs.core.not(cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(db,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),id,new cljs.core.Keyword(null,"trust","trust",-650115463)], null)));
window.grogAPI.call("set-trust",cljs.core.clj__GT_js(new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"id","id",-1388402092),id,new cljs.core.Keyword(null,"on","on",173873944),on], null))).catch((function (e){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),id,["[grog] set-trust failed: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e.message)].join('')], null));
}));

return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"db","db",993250759),cljs.core.assoc_in(db,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),id,new cljs.core.Keyword(null,"trust","trust",-650115463)], null),on)], null);
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"set-model","set-model",-2046273150),(function (p__20871,p__20872){
var map__20873 = p__20871;
var map__20873__$1 = cljs.core.__destructure_map(map__20873);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20873__$1,new cljs.core.Keyword(null,"db","db",993250759));
var vec__20874 = p__20872;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20874,(0),null);
var id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20874,(1),null);
var model = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20874,(2),null);
var source = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20874,(3),null);
var m = clojure.string.trim(cljs.core.str.cljs$core$IFn$_invoke$arity$1(model));
if(cljs.core.seq(m)){
window.grogAPI.call("set-model",cljs.core.clj__GT_js((function (){var G__20877 = new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"id","id",-1388402092),id,new cljs.core.Keyword(null,"model","model",331153215),m], null);
if(cljs.core.truth_(source)){
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__20877,new cljs.core.Keyword(null,"source","source",-433931539),cljs.core.str.cljs$core$IFn$_invoke$arity$1(source));
} else {
return G__20877;
}
})())).catch((function (e){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),id,["[grog] set-model failed: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e.message)].join('')], null));
}));

return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"db","db",993250759),cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(db,new cljs.core.Keyword(null,"dialog","dialog",1415150135),null)], null);
} else {
return cljs.core.PersistentArrayMap.EMPTY;
}
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"answer-approval","answer-approval",-1317205169),(function (p__20878,p__20879){
var map__20880 = p__20878;
var map__20880__$1 = cljs.core.__destructure_map(map__20880);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20880__$1,new cljs.core.Keyword(null,"db","db",993250759));
var vec__20881 = p__20879;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20881,(0),null);
var sid = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20881,(1),null);
var approval_id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20881,(2),null);
var decision = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20881,(3),null);
window.grogAPI.call("answer",cljs.core.clj__GT_js(new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"id","id",-1388402092),sid,new cljs.core.Keyword(null,"approval-id","approval-id",2130331864),approval_id,new cljs.core.Keyword(null,"decision","decision",820953053),cljs.core.name(decision)], null))).catch((function (e){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),sid,["[grog] answer failed: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e.message)].join('')], null));
}));

return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"db","db",993250759),cljs.core.update_in.cljs$core$IFn$_invoke$arity$4(db,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid], null),cljs.core.dissoc,new cljs.core.Keyword(null,"pending-approval","pending-approval",707430381))], null);
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"answer-question","answer-question",437271305),(function (p__20884,p__20885){
var map__20886 = p__20884;
var map__20886__$1 = cljs.core.__destructure_map(map__20886);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20886__$1,new cljs.core.Keyword(null,"db","db",993250759));
var vec__20887 = p__20885;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20887,(0),null);
var sid = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20887,(1),null);
var result = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20887,(2),null);
var qid = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(db,new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid,new cljs.core.Keyword(null,"pending-question","pending-question",393981712),new cljs.core.Keyword(null,"questionId","questionId",335136346)], null));
window.grogAPI.call("answer-question",cljs.core.clj__GT_js(new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"question-id","question-id",529146980),qid,new cljs.core.Keyword(null,"result","result",1415092211),result], null))).catch((function (e){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),sid,["[grog] answer-question failed: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e.message)].join('')], null));
}));

return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"db","db",993250759),cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(cljs.core.update_in.cljs$core$IFn$_invoke$arity$4(db,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid], null),cljs.core.dissoc,new cljs.core.Keyword(null,"pending-question","pending-question",393981712)),new cljs.core.Keyword(null,"q-input","q-input",-1764562816),"")], null);
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"write-clipboard","write-clipboard",742834211),(function (p__20890,p__20891){
var map__20892 = p__20890;
var map__20892__$1 = cljs.core.__destructure_map(map__20892);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20892__$1,new cljs.core.Keyword(null,"db","db",993250759));
var vec__20893 = p__20891;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20893,(0),null);
var text = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20893,(1),null);
var ok_msg = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20893,(2),null);
var cb_20999 = navigator.clipboard;
if(cljs.core.truth_((function (){var and__5000__auto__ = cb_20999;
if(cljs.core.truth_(and__5000__auto__)){
return cb_20999.writeText;
} else {
return and__5000__auto__;
}
})())){
cb_20999.writeText(text).then((function (___$1){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,ok_msg], null));
})).catch((function (___$1){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,"[grog] clipboard write failed"], null));
}));
} else {
re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,"[grog] clipboard unavailable"], null));
}

return cljs.core.PersistentArrayMap.EMPTY;
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"copy-transcript","copy-transcript",-2113119949),(function (p__20896,_){
var map__20897 = p__20896;
var map__20897__$1 = cljs.core.__destructure_map(map__20897);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20897__$1,new cljs.core.Keyword(null,"db","db",993250759));
var sid = new cljs.core.Keyword(null,"active","active",1895962068).cljs$core$IFn$_invoke$arity$1(db);
var segs = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(db,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),sid,new cljs.core.Keyword(null,"transcript","transcript",-2018154566)], null));
var txt = clojure.string.join.cljs$core$IFn$_invoke$arity$2("\n\n",cljs.core.map.cljs$core$IFn$_invoke$arity$2((function (s){
return [cljs.core.name(new cljs.core.Keyword(null,"kind","kind",-717265803).cljs$core$IFn$_invoke$arity$1(s)),": ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"text","text",-1790561697).cljs$core$IFn$_invoke$arity$1(s))].join('');
}),segs));
if(cljs.core.seq(clojure.string.trim(txt))){
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"write-clipboard","write-clipboard",742834211),txt,"[grog] transcript copied to clipboard"], null)], null);
} else {
return cljs.core.PersistentArrayMap.EMPTY;
}
}));
re_frame.core.reg_event_fx.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"retry-open","retry-open",-669833220),(function (p__20898,p__20899){
var map__20900 = p__20898;
var map__20900__$1 = cljs.core.__destructure_map(map__20900);
var db = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20900__$1,new cljs.core.Keyword(null,"db","db",993250759));
var vec__20901 = p__20899;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20901,(0),null);
var project = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20901,(1),null);
if(cljs.core.truth_(new cljs.core.Keyword(null,"opened?","opened?",1096959669).cljs$core$IFn$_invoke$arity$1(db))){
} else {
re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"open","open",-1763596448),(function (){var or__5002__auto__ = project;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return grog_web.core.project_name;
}
})()], null));
}

return cljs.core.PersistentArrayMap.EMPTY;
}));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"order","order",-1254677256),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"order","order",-1254677256).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"active","active",1895962068),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"active","active",1895962068).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"focused","focused",1851572115),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"focused?","focused?",-1922723333).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"input","input",556931961),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"input","input",556931961).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"q-input","q-input",-1764562816),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"q-input","q-input",-1764562816).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"connected?","connected?",-1197551387),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"connected?","connected?",-1197551387).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"voice","voice",185716428),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"voice","voice",185716428).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"recording?","recording?",-1477514924),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"recording?","recording?",-1477514924).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"transcribing?","transcribing?",771140466),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"transcribing?","transcribing?",771140466).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"font-size","font-size",-1847940346),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
var or__5002__auto__ = new cljs.core.Keyword(null,"font-size","font-size",-1847940346).cljs$core$IFn$_invoke$arity$1(db);
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return (16);
}
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"socket-path","socket-path",-1920106584),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"socket-path","socket-path",-1920106584).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"retry","retry",-614012896),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"retry","retry",-614012896).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"sessions","sessions",-699316392),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"sessions","sessions",-699316392).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"sess","sess",198610142),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,p__20904){
var vec__20905 = p__20904;
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20905,(0),null);
var id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20905,(1),null);
return cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(db,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392),id], null));
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"ui-dialog","ui-dialog",1514352730),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"dialog","dialog",1415150135).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"dialog-input","dialog-input",-1508376519),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"dialog-input","dialog-input",-1508376519).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"projects","projects",-364845983),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"projects","projects",-364845983).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"catalogue","catalogue",224534933),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"catalogue","catalogue",224534933).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"model-source","model-source",-1983623726),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"model-source","model-source",-1983623726).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"model-query","model-query",-2037530197),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return new cljs.core.Keyword(null,"model-query","model-query",-2037530197).cljs$core$IFn$_invoke$arity$1(db);
})], 0));
re_frame.core.reg_sub.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"pending-questions","pending-questions",-869412638),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (db,_){
return cljs.core.set(cljs.core.keep.cljs$core$IFn$_invoke$arity$2((function (p__20908){
var vec__20909 = p__20908;
var id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20909,(0),null);
var s = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20909,(1),null);
if(cljs.core.truth_(new cljs.core.Keyword(null,"pending-question","pending-question",393981712).cljs$core$IFn$_invoke$arity$1(s))){
return id;
} else {
return null;
}
}),new cljs.core.Keyword(null,"sessions","sessions",-699316392).cljs$core$IFn$_invoke$arity$1(db)));
})], 0));
grog_web.core.role_label = (function grog_web$core$role_label(kind){
var G__20912 = kind;
var G__20912__$1 = (((G__20912 instanceof cljs.core.Keyword))?G__20912.fqn:null);
switch (G__20912__$1) {
case "answer":
return "answer";

break;
case "thinking":
return "think";

break;
case "tool":
return "tool";

break;
case "user":
return "user";

break;
case "usage":
return "usage";

break;
default:
return "\u00B7";

}
});
grog_web.core.segment_view = (function grog_web$core$segment_view(p__20913){
var map__20914 = p__20913;
var map__20914__$1 = cljs.core.__destructure_map(map__20914);
var kind = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20914__$1,new cljs.core.Keyword(null,"kind","kind",-717265803));
var text = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20914__$1,new cljs.core.Keyword(null,"text","text",-1790561697));
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"snark","snark",1260821788),kind)){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-center seg-snark italic text-sm py-1"], null),text], null);
} else {
if(cljs.core.contains_QMARK_(new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"thinking","thinking",2063777387),null,new cljs.core.Keyword(null,"answer","answer",-742633163),null], null), null),kind)){
return new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex gap-2 text-sm"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),["flex-none pt-0.5 text-[0.7rem] ",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"thinking","thinking",2063777387),kind))?"role-think":"role-answer")].join('')], null),grog_web.core.role_label(kind)], null),cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),["flex-1 min-w-0 md ",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"thinking","thinking",2063777387),kind))?"seg-think":"seg-answer")].join('')], null)], null),grog_web.md.__GT_hiccup(text))], null);
} else {
return new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),["text-sm ",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"user","user",1532431356),kind))?"text-right":"")].join('')], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),[cljs.core.str.cljs$core$IFn$_invoke$arity$1((function (){var G__20916 = kind;
var G__20916__$1 = (((G__20916 instanceof cljs.core.Keyword))?G__20916.fqn:null);
switch (G__20916__$1) {
case "tool":
return "role-tool";

break;
case "user":
return "role-user";

break;
default:
return "role-snark";

}
})())," text-[0.7rem] mr-2 align-top"].join('')], null),grog_web.core.role_label(kind)], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),(function (){var G__20917 = kind;
var G__20917__$1 = (((G__20917 instanceof cljs.core.Keyword))?G__20917.fqn:null);
switch (G__20917__$1) {
case "user":
return "seg-user";

break;
case "tool":
return "seg-tool";

break;
default:
return "seg-answer";

}
})(),new cljs.core.Keyword(null,"style","style",-496642736),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"white-space","white-space",-707351930),"pre-wrap"], null)], null),text], null)], null);

}
}
});
if((typeof grog_web !== 'undefined') && (typeof grog_web.core !== 'undefined') && (typeof grog_web.core._BANG_scroll_el !== 'undefined')){
} else {
grog_web.core._BANG_scroll_el = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(null);
}
grog_web.core.scroll_end_BANG_ = (function grog_web$core$scroll_end_BANG_(){
var temp__5823__auto__ = cljs.core.deref(grog_web.core._BANG_scroll_el);
if(cljs.core.truth_(temp__5823__auto__)){
var n = temp__5823__auto__;
return (n.scrollTop = n.scrollHeight);
} else {
return null;
}
});
/**
 * True once a real turn has happened (the splash logo retires then, like
 *   client 1's :splash?).
 */
grog_web.core.has_messages_QMARK_ = (function grog_web$core$has_messages_QMARK_(segs){
return cljs.core.boolean$(cljs.core.some((function (p1__20918_SHARP_){
return cljs.core.contains_QMARK_(new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword(null,"tool","tool",-1298696470),null,new cljs.core.Keyword(null,"thinking","thinking",2063777387),null,new cljs.core.Keyword(null,"answer","answer",-742633163),null,new cljs.core.Keyword(null,"user","user",1532431356),null], null), null),new cljs.core.Keyword(null,"kind","kind",-717265803).cljs$core$IFn$_invoke$arity$1(p1__20918_SHARP_));
}),segs));
});
if((typeof grog_web !== 'undefined') && (typeof grog_web.core !== 'undefined') && (typeof grog_web.core._BANG_matrix !== 'undefined')){
} else {
grog_web.core._BANG_matrix = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"raf","raf",-1295410152),null,new cljs.core.Keyword(null,"resize","resize",297367086),null], null));
}
if((typeof grog_web !== 'undefined') && (typeof grog_web.core !== 'undefined') && (typeof grog_web.core._BANG_splash_canvas !== 'undefined')){
} else {
grog_web.core._BANG_splash_canvas = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(null);
}
grog_web.core.matrix_frame_ms = (55);
grog_web.core.matrix_glyphs = ["ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789","\u0410\u0411\u0412\u0413\u0414\u0415\u0416\u0417\u0418\u041A\u041B\u041C\u041D\u041E\u041F\u0420\u0421\u0422\u0423\u0424\u0425\u0426\u0427\u0428\u0429\u042D\u042E\u042F","\u0394\u0398\u039B\u039E\u03A0\u03A3\u03A6\u03A8\u03A9\u03B1\u03B2\u03B3\u03B4\u03B5\u03B6\u03B7\u03B8\u03BB\u03BC\u03C0\u03C3\u03C6\u03C7\u03C8\u03C9","\u2500\u2502\u250C\u2510\u2514\u2518\u251C\u2524\u252C\u2534\u253C\u2554\u2557\u255A\u255D\u2560\u2563\u2566\u2569\u256C\u2591\u2592\u2593\u2588\u25C7\u25CB\u25CF\u25A1\u25A0\u25B3\u25B2"].join('');
grog_web.core.matrix_font = "16px 'Fira Code','Noto Sans Mono','DejaVu Sans Mono',monospace";
grog_web.core.stop_matrix_BANG_ = (function grog_web$core$stop_matrix_BANG_(){
var map__20919 = cljs.core.deref(grog_web.core._BANG_matrix);
var map__20919__$1 = cljs.core.__destructure_map(map__20919);
var raf = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20919__$1,new cljs.core.Keyword(null,"raf","raf",-1295410152));
var resize = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20919__$1,new cljs.core.Keyword(null,"resize","resize",297367086));
if(cljs.core.truth_(raf)){
cancelAnimationFrame(raf);
} else {
}

if(cljs.core.truth_(resize)){
window.removeEventListener("resize",resize);
} else {
}

return cljs.core.reset_BANG_(grog_web.core._BANG_matrix,new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"raf","raf",-1295410152),null,new cljs.core.Keyword(null,"resize","resize",297367086),null], null));
});
grog_web.core.start_matrix_BANG_ = (function grog_web$core$start_matrix_BANG_(canvas){
grog_web.core.stop_matrix_BANG_();

var ctx = canvas.getContext("2d");
var chars = grog_web.core.matrix_glyphs;
var nchars = ((chars).length);
var font = (16);
var st = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword(null,"cols","cols",-1914801295),(1),new cljs.core.Keyword(null,"drops","drops",-1558072608),[],new cljs.core.Keyword(null,"w","w",354169001),(0),new cljs.core.Keyword(null,"h","h",1109658740),(0)], null));
var _BANG_last = cljs.core.atom.cljs$core$IFn$_invoke$arity$1((0));
var fit = (function grog_web$core$start_matrix_BANG__$_fit(){
var w = canvas.clientWidth;
var h = canvas.clientHeight;
var cols = ((function (){var x__5087__auto__ = (1);
var y__5088__auto__ = Math.floor((w / font));
return ((x__5087__auto__ > y__5088__auto__) ? x__5087__auto__ : y__5088__auto__);
})() | (0));
(canvas.width = w);

(canvas.height = h);

return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$variadic(st,cljs.core.assoc,new cljs.core.Keyword(null,"cols","cols",-1914801295),cols,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"w","w",354169001),w,new cljs.core.Keyword(null,"h","h",1109658740),h,new cljs.core.Keyword(null,"drops","drops",-1558072608),cljs.core.into_array.cljs$core$IFn$_invoke$arity$1(cljs.core.repeat.cljs$core$IFn$_invoke$arity$2(cols,(1)))], 0));
});
var draw = (function grog_web$core$start_matrix_BANG__$_draw(now){
if(((now - (function (){var or__5002__auto__ = cljs.core.deref(_BANG_last);
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return (0);
}
})()) >= grog_web.core.matrix_frame_ms)){
cljs.core.reset_BANG_(_BANG_last,now);

var map__20921_21003 = cljs.core.deref(st);
var map__20921_21004__$1 = cljs.core.__destructure_map(map__20921_21003);
var cols_21005 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20921_21004__$1,new cljs.core.Keyword(null,"cols","cols",-1914801295));
var drops_21006 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20921_21004__$1,new cljs.core.Keyword(null,"drops","drops",-1558072608));
var w_21007 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20921_21004__$1,new cljs.core.Keyword(null,"w","w",354169001));
var h_21008 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20921_21004__$1,new cljs.core.Keyword(null,"h","h",1109658740));
(ctx.fillStyle = "rgba(13,14,17,0.10)");

ctx.fillRect((0),(0),w_21007,h_21008);

(ctx.fillStyle = "#2ec7cd");

(ctx.font = grog_web.core.matrix_font);

var n__5593__auto___21009 = cols_21005;
var i_21010 = (0);
while(true){
if((i_21010 < n__5593__auto___21009)){
var i_21011__$1 = (i_21010 | (0));
var ch_21012 = chars.charAt(Math.floor((Math.random() * nchars)));
var y_21013 = ((drops_21006[i_21011__$1]) * font);
ctx.fillText(ch_21012,(i_21011__$1 * font),y_21013);

if((((y_21013 > h_21008)) && ((Math.random() > 0.975)))){
(drops_21006[i_21011__$1] = (0));
} else {
}

(drops_21006[i_21011__$1] = ((drops_21006[i_21011__$1]) + (1)));

var G__21014 = (i_21010 + (1));
i_21010 = G__21014;
continue;
} else {
}
break;
}
} else {
}

return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(grog_web.core._BANG_matrix,cljs.core.assoc,new cljs.core.Keyword(null,"raf","raf",-1295410152),requestAnimationFrame(grog_web$core$start_matrix_BANG__$_draw));
});
fit();

var on_resize_21015 = (function (_){
return fit();
});
window.addEventListener("resize",on_resize_21015);

cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(grog_web.core._BANG_matrix,cljs.core.assoc,new cljs.core.Keyword(null,"resize","resize",297367086),on_resize_21015);

return draw(performance.now());
});
grog_web.core.splash = (function grog_web$core$splash(segs){
return reagent.core.create_class.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"component-did-mount","component-did-mount",-1126910518),(function (_){
var temp__5823__auto__ = cljs.core.deref(grog_web.core._BANG_splash_canvas);
if(cljs.core.truth_(temp__5823__auto__)){
var c = temp__5823__auto__;
return grog_web.core.start_matrix_BANG_(c);
} else {
return null;
}
}),new cljs.core.Keyword(null,"component-will-unmount","component-will-unmount",-2058314698),(function (_){
return grog_web.core.stop_matrix_BANG_();
}),new cljs.core.Keyword(null,"reagent-render","reagent-render",-985383853),(function (segs__$1){
var notes = cljs.core.filter.cljs$core$IFn$_invoke$arity$2((function (p1__20922_SHARP_){
return cljs.core.contains_QMARK_(new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"line","line",212345235),null,new cljs.core.Keyword(null,"snark","snark",1260821788),null], null), null),new cljs.core.Keyword(null,"kind","kind",-717265803).cljs$core$IFn$_invoke$arity$1(p1__20922_SHARP_));
}),segs__$1);
return new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"absolute inset-0 flex flex-col items-center justify-center gap-3 overflow-hidden p-6"], null),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"canvas","canvas",-1798817489),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"ref","ref",1289896967),(function (el){
return cljs.core.reset_BANG_(grog_web.core._BANG_splash_canvas,el);
}),new cljs.core.Keyword(null,"class","class",-2030961996),"absolute inset-0 w-full h-full"], null)], null),new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"relative z-10 flex flex-col items-center gap-3 w-full max-h-full min-h-0"], null),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"img","img",1442687358),new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword(null,"src","src",-1651076051),"logo.jpg",new cljs.core.Keyword(null,"alt","alt",-3214426),"grog",new cljs.core.Keyword(null,"class","class",-2030961996),"w-auto max-w-[70vw] min-h-0 shrink object-contain rounded-xl shadow-2xl opacity-50",new cljs.core.Keyword(null,"style","style",-496642736),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"max-height","max-height",-612563804),"50vh"], null)], null)], null),((cljs.core.seq(notes))?new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex flex-col items-center gap-1 max-w-3xl max-h-[22vh] overflow-y-auto min-h-0"], null),(function (){var iter__5480__auto__ = (function grog_web$core$splash_$_iter__20923(s__20924){
return (new cljs.core.LazySeq(null,(function (){
var s__20924__$1 = s__20924;
while(true){
var temp__5823__auto__ = cljs.core.seq(s__20924__$1);
if(temp__5823__auto__){
var s__20924__$2 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(s__20924__$2)){
var c__5478__auto__ = cljs.core.chunk_first(s__20924__$2);
var size__5479__auto__ = cljs.core.count(c__5478__auto__);
var b__20926 = cljs.core.chunk_buffer(size__5479__auto__);
if((function (){var i__20925 = (0);
while(true){
if((i__20925 < size__5479__auto__)){
var vec__20927 = cljs.core._nth(c__5478__auto__,i__20925);
var i = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20927,(0),null);
var s = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20927,(1),null);
cljs.core.chunk_append(b__20926,cljs.core.with_meta(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-center text-sm italic seg-snark"], null),new cljs.core.Keyword(null,"text","text",-1790561697).cljs$core$IFn$_invoke$arity$1(s)], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),i], null)));

var G__21016 = (i__20925 + (1));
i__20925 = G__21016;
continue;
} else {
return true;
}
break;
}
})()){
return cljs.core.chunk_cons(cljs.core.chunk(b__20926),grog_web$core$splash_$_iter__20923(cljs.core.chunk_rest(s__20924__$2)));
} else {
return cljs.core.chunk_cons(cljs.core.chunk(b__20926),null);
}
} else {
var vec__20930 = cljs.core.first(s__20924__$2);
var i = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20930,(0),null);
var s = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20930,(1),null);
return cljs.core.cons(cljs.core.with_meta(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-center text-sm italic seg-snark"], null),new cljs.core.Keyword(null,"text","text",-1790561697).cljs$core$IFn$_invoke$arity$1(s)], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),i], null)),grog_web$core$splash_$_iter__20923(cljs.core.rest(s__20924__$2)));
}
} else {
return null;
}
break;
}
}),null,null));
});
return iter__5480__auto__(cljs.core.map_indexed.cljs$core$IFn$_invoke$arity$2(cljs.core.vector,notes));
})()], null):null)], null)], null);
})], null));
});
grog_web.core.transcript = (function grog_web$core$transcript(sid){
var s = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sess","sess",198610142),sid], null)));
var segs = new cljs.core.Keyword(null,"transcript","transcript",-2018154566).cljs$core$IFn$_invoke$arity$1(s);
reagent.core.after_render(grog_web.core.scroll_end_BANG_);

return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"ref","ref",1289896967),(function (el){
return cljs.core.reset_BANG_(grog_web.core._BANG_scroll_el,el);
}),new cljs.core.Keyword(null,"class","class",-2030961996),"relative flex-1 min-h-0 overflow-y-auto px-6 py-4 space-y-3"], null),((grog_web.core.has_messages_QMARK_(segs))?(function (){var iter__5480__auto__ = (function grog_web$core$transcript_$_iter__20933(s__20934){
return (new cljs.core.LazySeq(null,(function (){
var s__20934__$1 = s__20934;
while(true){
var temp__5823__auto__ = cljs.core.seq(s__20934__$1);
if(temp__5823__auto__){
var s__20934__$2 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(s__20934__$2)){
var c__5478__auto__ = cljs.core.chunk_first(s__20934__$2);
var size__5479__auto__ = cljs.core.count(c__5478__auto__);
var b__20936 = cljs.core.chunk_buffer(size__5479__auto__);
if((function (){var i__20935 = (0);
while(true){
if((i__20935 < size__5479__auto__)){
var vec__20937 = cljs.core._nth(c__5478__auto__,i__20935);
var i = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20937,(0),null);
var seg = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20937,(1),null);
cljs.core.chunk_append(b__20936,cljs.core.with_meta(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.segment_view,seg], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),i], null)));

var G__21017 = (i__20935 + (1));
i__20935 = G__21017;
continue;
} else {
return true;
}
break;
}
})()){
return cljs.core.chunk_cons(cljs.core.chunk(b__20936),grog_web$core$transcript_$_iter__20933(cljs.core.chunk_rest(s__20934__$2)));
} else {
return cljs.core.chunk_cons(cljs.core.chunk(b__20936),null);
}
} else {
var vec__20940 = cljs.core.first(s__20934__$2);
var i = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20940,(0),null);
var seg = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20940,(1),null);
return cljs.core.cons(cljs.core.with_meta(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.segment_view,seg], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),i], null)),grog_web$core$transcript_$_iter__20933(cljs.core.rest(s__20934__$2)));
}
} else {
return null;
}
break;
}
}),null,null));
});
return iter__5480__auto__(cljs.core.map_indexed.cljs$core$IFn$_invoke$arity$2(cljs.core.vector,segs));
})():new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.splash,segs], null))], null);
});
grog_web.core.tab_strip = (function grog_web$core$tab_strip(){
var order = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"order","order",-1254677256)], null)));
var active = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"active","active",1895962068)], null)));
var pending = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"pending-questions","pending-questions",-869412638)], null)));
var sessions = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392)], null)));
return new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex items-center gap-1 px-2 pt-2 bg-slate-900/70 border-b border-slate-700"], null),(function (){var iter__5480__auto__ = (function grog_web$core$tab_strip_$_iter__20943(s__20944){
return (new cljs.core.LazySeq(null,(function (){
var s__20944__$1 = s__20944;
while(true){
var temp__5823__auto__ = cljs.core.seq(s__20944__$1);
if(temp__5823__auto__){
var s__20944__$2 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(s__20944__$2)){
var c__5478__auto__ = cljs.core.chunk_first(s__20944__$2);
var size__5479__auto__ = cljs.core.count(c__5478__auto__);
var b__20946 = cljs.core.chunk_buffer(size__5479__auto__);
if((function (){var i__20945 = (0);
while(true){
if((i__20945 < size__5479__auto__)){
var id = cljs.core._nth(c__5478__auto__,i__20945);
cljs.core.chunk_append(b__20946,(function (){var pend_QMARK_ = cljs.core.contains_QMARK_(pending,id);
var running_QMARK_ = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(sessions,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [id,new cljs.core.Keyword(null,"running?","running?",-257884763)], null));
return cljs.core.with_meta(new cljs.core.PersistentVector(null, 6, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"on-click","on-click",1632826543),((function (i__20945,pend_QMARK_,running_QMARK_,id,c__5478__auto__,size__5479__auto__,b__20946,s__20944__$2,temp__5823__auto__,order,active,pending,sessions){
return (function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"select-tab","select-tab",-1719420452),id], null));
});})(i__20945,pend_QMARK_,running_QMARK_,id,c__5478__auto__,size__5479__auto__,b__20946,s__20944__$2,temp__5823__auto__,order,active,pending,sessions))
,new cljs.core.Keyword(null,"title","title",636505583),((pend_QMARK_)?"Question pending \u2014 dialog appears when this tab is selected and the window is focused":null),new cljs.core.Keyword(null,"class","class",-2030961996),["group flex items-center gap-2 px-3 py-2 rounded-t-md text-sm cursor-pointer ",((pend_QMARK_)?"bg-amber-500/10 border border-amber-500/40 border-b-0 text-amber-200":((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(id,active))?"bg-sky-500/15 border border-sky-500/40 border-b-0 text-sky-200":"text-slate-400 hover:bg-slate-800"))].join('')], null),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),["w-2 h-2 rounded-full ",((pend_QMARK_)?"bg-amber-400 animate-pulse":(cljs.core.truth_(running_QMARK_)?"bg-sky-400 animate-pulse":"bg-emerald-400"
))].join('')], null)], null),cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(sessions,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [id,new cljs.core.Keyword(null,"project","project",1124394579)], null)),((pend_QMARK_)?new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-1 rounded bg-amber-500/20 text-[0.64rem] text-amber-300"], null),"?"], null):null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"ml-1 opacity-0 group-hover:opacity-100 text-slate-500 hover:text-rose-300",new cljs.core.Keyword(null,"on-click","on-click",1632826543),((function (i__20945,pend_QMARK_,running_QMARK_,id,c__5478__auto__,size__5479__auto__,b__20946,s__20944__$2,temp__5823__auto__,order,active,pending,sessions){
return (function (e){
e.stopPropagation();

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"close-active","close-active",-1194859253),id], null));
});})(i__20945,pend_QMARK_,running_QMARK_,id,c__5478__auto__,size__5479__auto__,b__20946,s__20944__$2,temp__5823__auto__,order,active,pending,sessions))
], null),"\u2715"], null)], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),id], null));
})());

var G__21018 = (i__20945 + (1));
i__20945 = G__21018;
continue;
} else {
return true;
}
break;
}
})()){
return cljs.core.chunk_cons(cljs.core.chunk(b__20946),grog_web$core$tab_strip_$_iter__20943(cljs.core.chunk_rest(s__20944__$2)));
} else {
return cljs.core.chunk_cons(cljs.core.chunk(b__20946),null);
}
} else {
var id = cljs.core.first(s__20944__$2);
return cljs.core.cons((function (){var pend_QMARK_ = cljs.core.contains_QMARK_(pending,id);
var running_QMARK_ = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(sessions,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [id,new cljs.core.Keyword(null,"running?","running?",-257884763)], null));
return cljs.core.with_meta(new cljs.core.PersistentVector(null, 6, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"on-click","on-click",1632826543),((function (pend_QMARK_,running_QMARK_,id,s__20944__$2,temp__5823__auto__,order,active,pending,sessions){
return (function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"select-tab","select-tab",-1719420452),id], null));
});})(pend_QMARK_,running_QMARK_,id,s__20944__$2,temp__5823__auto__,order,active,pending,sessions))
,new cljs.core.Keyword(null,"title","title",636505583),((pend_QMARK_)?"Question pending \u2014 dialog appears when this tab is selected and the window is focused":null),new cljs.core.Keyword(null,"class","class",-2030961996),["group flex items-center gap-2 px-3 py-2 rounded-t-md text-sm cursor-pointer ",((pend_QMARK_)?"bg-amber-500/10 border border-amber-500/40 border-b-0 text-amber-200":((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(id,active))?"bg-sky-500/15 border border-sky-500/40 border-b-0 text-sky-200":"text-slate-400 hover:bg-slate-800"))].join('')], null),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),["w-2 h-2 rounded-full ",((pend_QMARK_)?"bg-amber-400 animate-pulse":(cljs.core.truth_(running_QMARK_)?"bg-sky-400 animate-pulse":"bg-emerald-400"
))].join('')], null)], null),cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(sessions,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [id,new cljs.core.Keyword(null,"project","project",1124394579)], null)),((pend_QMARK_)?new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-1 rounded bg-amber-500/20 text-[0.64rem] text-amber-300"], null),"?"], null):null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"ml-1 opacity-0 group-hover:opacity-100 text-slate-500 hover:text-rose-300",new cljs.core.Keyword(null,"on-click","on-click",1632826543),((function (pend_QMARK_,running_QMARK_,id,s__20944__$2,temp__5823__auto__,order,active,pending,sessions){
return (function (e){
e.stopPropagation();

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"close-active","close-active",-1194859253),id], null));
});})(pend_QMARK_,running_QMARK_,id,s__20944__$2,temp__5823__auto__,order,active,pending,sessions))
], null),"\u2715"], null)], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),id], null));
})(),grog_web$core$tab_strip_$_iter__20943(cljs.core.rest(s__20944__$2)));
}
} else {
return null;
}
break;
}
}),null,null));
});
return iter__5480__auto__(order);
})(),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"ml-auto flex items-center gap-1 text-xs pr-1"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"title","title",636505583),"New tab (Ctrl+T)",new cljs.core.Keyword(null,"class","class",-2030961996),"px-2 py-1 rounded text-slate-400 hover:bg-slate-800",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"dialog-open","dialog-open",2100079327),new cljs.core.Keyword(null,"projects","projects",-364845983),""], null));
})], null),"+"], null)], null)], null);
});
grog_web.core.toolbar = (function grog_web$core$toolbar(){
var active = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"active","active",1895962068)], null)));
var sess = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392)], null))),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [active], null));
var running_QMARK_ = new cljs.core.Keyword(null,"running?","running?",-257884763).cljs$core$IFn$_invoke$arity$1(sess);
var trust_QMARK_ = new cljs.core.Keyword(null,"trust","trust",-650115463).cljs$core$IFn$_invoke$arity$1(sess);
var st = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"voice","voice",185716428)], null)));
var recording_QMARK_ = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"recording?","recording?",-1477514924)], null)));
var transcribing_QMARK_ = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"transcribing?","transcribing?",771140466)], null)));
var voice_QMARK_ = (function (){var and__5000__auto__ = st;
if(cljs.core.truth_(and__5000__auto__)){
return new cljs.core.Keyword(null,"enabled","enabled",1195909756).cljs$core$IFn$_invoke$arity$1(st);
} else {
return and__5000__auto__;
}
})();
return new cljs.core.PersistentVector(null, 8, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex items-center gap-1 text-xs px-3 pt-2"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"disabled","disabled",-1529784218),cljs.core.not(active),new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1.5 rounded-md bg-sky-500 text-slate-950 font-medium disabled:opacity-40",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"send","send",-652151114)], null));
})], null),"Send"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"disabled","disabled",-1529784218),cljs.core.not(running_QMARK_),new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 disabled:opacity-40",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"stop","stop",-2140911342)], null));
})], null),"Stop"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword(null,"disabled","disabled",-1529784218),cljs.core.not(active),new cljs.core.Keyword(null,"title","title",636505583),(cljs.core.truth_(transcribing_QMARK_)?"Transcribing\u2026":(cljs.core.truth_(recording_QMARK_)?"Recording \u2014 click to stop":(cljs.core.truth_(voice_QMARK_)?["Voice input \u2014 click to record, click again to stop \u00B7 ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"engine","engine",1459054265).cljs$core$IFn$_invoke$arity$1(st))].join(''):"Voice off \u2014 set GROG_VOICE_COMMAND"
))),new cljs.core.Keyword(null,"class","class",-2030961996),["px-3 py-1.5 rounded-md border ",(cljs.core.truth_(recording_QMARK_)?"bg-rose-500/20 border-rose-500/50 text-rose-200 animate-pulse":((cljs.core.not(voice_QMARK_))?"bg-slate-800 border-slate-700 text-slate-500 opacity-60":"bg-slate-800 border-slate-700 text-slate-300"
))," disabled:opacity-40"].join(''),new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"toggle-voice","toggle-voice",1481320800)], null));
})], null),(cljs.core.truth_(transcribing_QMARK_)?"\u2026":(cljs.core.truth_(recording_QMARK_)?"\u23F9":"\uD83C\uDFA4"
))], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword(null,"disabled","disabled",-1529784218),cljs.core.not(active),new cljs.core.Keyword(null,"title","title",636505583),"Trust / YOLO \u2014 auto-approve tool calls",new cljs.core.Keyword(null,"class","class",-2030961996),["px-3 py-1.5 rounded-md border ",(cljs.core.truth_(trust_QMARK_)?"bg-amber-500/20 border-amber-500/50 text-amber-200":"bg-slate-800 border-slate-700 text-slate-300 disabled:opacity-40")].join(''),new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"toggle-trust","toggle-trust",-1518698863),active], null));
})], null),(cljs.core.truth_(trust_QMARK_)?"YOLO ON":"YOLO")], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword(null,"disabled","disabled",-1529784218),cljs.core.not(active),new cljs.core.Keyword(null,"title","title",636505583),"Settings",new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 disabled:opacity-40",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"dialog-open","dialog-open",2100079327),new cljs.core.Keyword(null,"settings","settings",1556144875),cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392)], null))),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [active,new cljs.core.Keyword(null,"model","model",331153215)], null))], null));
})], null),"\u2699"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword(null,"disabled","disabled",-1529784218),cljs.core.not(active),new cljs.core.Keyword(null,"title","title",636505583),"Copy transcript (Ctrl+E)",new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 disabled:opacity-40",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"copy-transcript","copy-transcript",-2113119949)], null));
})], null),"\u29C9"], null)], null);
});
grog_web.core.composer = (function grog_web$core$composer(){
var txt = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"input","input",556931961)], null)));
var active = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"active","active",1895962068)], null)));
return new cljs.core.PersistentVector(null, 5, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"border-t border-slate-700 bg-slate-900/50 p-3 space-y-2"], null),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.toolbar], null),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"textarea","textarea",-650375824),new cljs.core.PersistentArrayMap(null, 8, [new cljs.core.Keyword(null,"id","id",-1388402092),"grog-composer",new cljs.core.Keyword(null,"rows","rows",850049680),(3),new cljs.core.Keyword(null,"value","value",305978217),txt,new cljs.core.Keyword(null,"placeholder","placeholder",-104873083),"Ctrl+Enter sends \u00B7 Enter newline",new cljs.core.Keyword(null,"disabled","disabled",-1529784218),cljs.core.not(active),new cljs.core.Keyword(null,"on-change","on-change",-732046149),(function (p1__20947_SHARP_){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"input","input",556931961),p1__20947_SHARP_.target.value], null));
}),new cljs.core.Keyword(null,"on-key-down","on-key-down",-1374733765),(function (p1__20948_SHARP_){
if(cljs.core.truth_((function (){var and__5000__auto__ = cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2("Enter",p1__20948_SHARP_.key);
if(and__5000__auto__){
return p1__20948_SHARP_.ctrlKey;
} else {
return and__5000__auto__;
}
})())){
p1__20948_SHARP_.preventDefault();

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"send","send",-652151114)], null));
} else {
return null;
}
}),new cljs.core.Keyword(null,"class","class",-2030961996),"w-full rounded-md bg-[#121212] border border-slate-700 px-3 py-2 text-sm text-slate-200 outline-none focus:border-sky-500"], null)], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-[0.7rem] text-slate-500"], null),"Ctrl+Enter send \u00B7 Ctrl+Shift+Space voice \u00B7 /clear \u00B7 /yolo"], null)], null);
});
grog_web.core.status_bar = (function grog_web$core$status_bar(){
var pending = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"pending-questions","pending-questions",-869412638)], null)));
var connected_QMARK_ = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"connected?","connected?",-1197551387)], null)));
var active = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"active","active",1895962068)], null)));
var sess = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392)], null))),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [active], null));
var name_of = (function (id){
return cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392)], null))),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [id,new cljs.core.Keyword(null,"project","project",1124394579)], null));
});
return new cljs.core.PersistentVector(null, 8, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex items-center gap-4 px-4 py-1.5 bg-slate-900 border-t border-slate-700 text-[0.7rem] text-slate-400"], null),new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex items-center gap-1.5"], null),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),["w-2 h-2 rounded-full ",(cljs.core.truth_(connected_QMARK_)?"bg-emerald-400":"bg-rose-500")].join('')], null)], null),(cljs.core.truth_(connected_QMARK_)?"server attached":"server unreachable")], null),(cljs.core.truth_(sess)?new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),["model: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1((function (){var or__5002__auto__ = new cljs.core.Keyword(null,"model","model",331153215).cljs$core$IFn$_invoke$arity$1(sess);
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return "?";
}
})())].join('')], null):null),(function (){var temp__5823__auto__ = new cljs.core.Keyword(null,"usage","usage",-1583752910).cljs$core$IFn$_invoke$arity$1(sess);
if(cljs.core.truth_(temp__5823__auto__)){
var u = temp__5823__auto__;
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),[cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"sessionTokens","sessionTokens",1892985457).cljs$core$IFn$_invoke$arity$1(u))," tok \u00B7 $",cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"sessionCost","sessionCost",-642164836).cljs$core$IFn$_invoke$arity$1(u))].join('')], null);
} else {
return null;
}
})(),(cljs.core.truth_(sess)?new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),(cljs.core.truth_(new cljs.core.Keyword(null,"running?","running?",-257884763).cljs$core$IFn$_invoke$arity$1(sess))?"\u25CF streaming":"idle")], null):null),(cljs.core.truth_(sess)?new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),(cljs.core.truth_(new cljs.core.Keyword(null,"trust","trust",-650115463).cljs$core$IFn$_invoke$arity$1(sess))?"YOLO ON":"trust off")], null):null),((cljs.core.seq(pending))?new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-300 border border-amber-500/40"], null),["\u26A0 question pending \u00B7 ",clojure.string.join.cljs$core$IFn$_invoke$arity$2(", ",cljs.core.map.cljs$core$IFn$_invoke$arity$2(name_of,pending))].join('')], null):null)], null);
});
grog_web.core.banner = (function grog_web$core$banner(){
if(cljs.core.truth_(cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"connected?","connected?",-1197551387)], null))))){
return null;
} else {
var r = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"retry","retry",-614012896)], null)));
var att = new cljs.core.Keyword(null,"attempts","attempts",1024246729).cljs$core$IFn$_invoke$arity$1(r);
var nra = new cljs.core.Keyword(null,"nextRetryAt","nextRetryAt",262686636).cljs$core$IFn$_invoke$arity$1(r);
var secs = (cljs.core.truth_((function (){var and__5000__auto__ = nra;
if(cljs.core.truth_(and__5000__auto__)){
return (nra > (0));
} else {
return and__5000__auto__;
}
})())?(function (){var x__5087__auto__ = (1);
var y__5088__auto__ = (((nra - Date.now()) / (1000)) | (0));
return ((x__5087__auto__ > y__5088__auto__) ? x__5087__auto__ : y__5088__auto__);
})():null);
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"mx-4 mt-3 px-3 py-2 rounded-md bg-rose-500/15 border border-rose-500/40 text-rose-200 text-xs"], null),["\u26A0 grog-server unreachable at ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"socket-path","socket-path",-1920106584)], null)))),(cljs.core.truth_((function (){var and__5000__auto__ = att;
if(cljs.core.truth_(and__5000__auto__)){
return (att > (0));
} else {
return and__5000__auto__;
}
})())?[" \u2014 reconnecting (attempt ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(att),(cljs.core.truth_(secs)?[", next in ~",cljs.core.str.cljs$core$IFn$_invoke$arity$1(secs),"s"].join(''):null),")\u2026"].join(''):null)," Start the daemon and it attaches automatically (retries back off to 30s)."].join('')], null);
}
});
grog_web.core.overlay = (function grog_web$core$overlay(var_args){
var args__5732__auto__ = [];
var len__5726__auto___21019 = arguments.length;
var i__5727__auto___21020 = (0);
while(true){
if((i__5727__auto___21020 < len__5726__auto___21019)){
args__5732__auto__.push((arguments[i__5727__auto___21020]));

var G__21021 = (i__5727__auto___21020 + (1));
i__5727__auto___21020 = G__21021;
continue;
} else {
}
break;
}

var argseq__5733__auto__ = ((((0) < args__5732__auto__.length))?(new cljs.core.IndexedSeq(args__5732__auto__.slice((0)),(0),null)):null);
return grog_web.core.overlay.cljs$core$IFn$_invoke$arity$variadic(argseq__5733__auto__);
});

(grog_web.core.overlay.cljs$core$IFn$_invoke$arity$variadic = (function (body){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"fixed inset-0 z-50 bg-black/60 flex items-center justify-center"], null),body], null);
}));

(grog_web.core.overlay.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(grog_web.core.overlay.cljs$lang$applyTo = (function (seq20949){
var self__5712__auto__ = this;
return self__5712__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq20949));
}));

grog_web.core.approval_dialog = (function grog_web$core$approval_dialog(){
var focused = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"focused","focused",1851572115)], null)));
var active = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"active","active",1895962068)], null)));
var sess = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392)], null))),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [active], null));
if(cljs.core.truth_((function (){var and__5000__auto__ = focused;
if(cljs.core.truth_(and__5000__auto__)){
var and__5000__auto____$1 = active;
if(cljs.core.truth_(and__5000__auto____$1)){
return new cljs.core.Keyword(null,"pending-approval","pending-approval",707430381).cljs$core$IFn$_invoke$arity$1(sess);
} else {
return and__5000__auto____$1;
}
} else {
return and__5000__auto__;
}
})())){
var a = new cljs.core.Keyword(null,"pending-approval","pending-approval",707430381).cljs$core$IFn$_invoke$arity$1(sess);
var sid = active;
var ans = (function (d){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"answer-approval","answer-approval",-1317205169),sid,new cljs.core.Keyword(null,"id","id",-1388402092).cljs$core$IFn$_invoke$arity$1(a),d], null));
});
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.overlay,new cljs.core.PersistentVector(null, 7, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"card rounded-xl shadow-2xl p-4 space-y-3 w-[620px]"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-slate-200 text-sm font-medium"], null),"\uD83D\uDD27 Tool approval requested"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-slate-300 text-sm"], null),cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"name","name",1843675177).cljs$core$IFn$_invoke$arity$1(a))], null),(cljs.core.truth_(new cljs.core.Keyword(null,"summary","summary",380847952).cljs$core$IFn$_invoke$arity$1(a))?new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-xs text-slate-400"], null),new cljs.core.Keyword(null,"summary","summary",380847952).cljs$core$IFn$_invoke$arity$1(a)], null):null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"pre","pre",2118456869),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-xs text-slate-400 bg-black/30 rounded p-2 overflow-auto max-h-40"], null),cljs.core.pr_str.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"args","args",1315556576).cljs$core$IFn$_invoke$arity$1(a)], 0))], null),new cljs.core.PersistentVector(null, 6, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex gap-2 justify-end text-sm"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return ans(new cljs.core.Keyword(null,"reject","reject",1415953113));
})], null),"Reject"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return ans(new cljs.core.Keyword(null,"yolo","yolo",697110299));
})], null),"YOLO"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return ans(new cljs.core.Keyword(null,"approve-tool","approve-tool",1600088560));
})], null),"Approve tool"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"autoFocus","autoFocus",-552622425),true,new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1.5 rounded-md bg-sky-500 text-slate-950 font-medium",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return ans(new cljs.core.Keyword(null,"approve","approve",784812935));
})], null),"Approve"], null)], null)], null)], null);
} else {
return null;
}
});
grog_web.core.question_dialog = (function grog_web$core$question_dialog(){
var focused = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"focused","focused",1851572115)], null)));
var active = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"active","active",1895962068)], null)));
var sess = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392)], null))),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [active], null));
var qin = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"q-input","q-input",-1764562816)], null)));
if(cljs.core.truth_((function (){var and__5000__auto__ = focused;
if(cljs.core.truth_(and__5000__auto__)){
var and__5000__auto____$1 = active;
if(cljs.core.truth_(and__5000__auto____$1)){
return new cljs.core.Keyword(null,"pending-question","pending-question",393981712).cljs$core$IFn$_invoke$arity$1(sess);
} else {
return and__5000__auto____$1;
}
} else {
return and__5000__auto__;
}
})())){
var q = new cljs.core.Keyword(null,"pending-question","pending-question",393981712).cljs$core$IFn$_invoke$arity$1(sess);
var sid = active;
var ans = (function (res){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"answer-question","answer-question",437271305),sid,res], null));
});
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.overlay,new cljs.core.PersistentVector(null, 7, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"card rounded-xl shadow-2xl p-4 space-y-3 w-[620px]"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-slate-200 text-sm font-medium"], null),"\u2753 The model is asking"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-slate-300 text-sm"], null),new cljs.core.Keyword(null,"prompt","prompt",-78109487).cljs$core$IFn$_invoke$arity$1(q)], null),((cljs.core.seq(new cljs.core.Keyword(null,"options","options",99638489).cljs$core$IFn$_invoke$arity$1(q)))?new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"space-y-1"], null),(function (){var iter__5480__auto__ = (function grog_web$core$question_dialog_$_iter__20952(s__20953){
return (new cljs.core.LazySeq(null,(function (){
var s__20953__$1 = s__20953;
while(true){
var temp__5823__auto__ = cljs.core.seq(s__20953__$1);
if(temp__5823__auto__){
var s__20953__$2 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(s__20953__$2)){
var c__5478__auto__ = cljs.core.chunk_first(s__20953__$2);
var size__5479__auto__ = cljs.core.count(c__5478__auto__);
var b__20955 = cljs.core.chunk_buffer(size__5479__auto__);
if((function (){var i__20954 = (0);
while(true){
if((i__20954 < size__5479__auto__)){
var vec__20956 = cljs.core._nth(c__5478__auto__,i__20954);
var i = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20956,(0),null);
var o = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20956,(1),null);
cljs.core.chunk_append(b__20955,cljs.core.with_meta(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"w-full text-left px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-200 hover:border-sky-500",new cljs.core.Keyword(null,"on-click","on-click",1632826543),((function (i__20954,vec__20956,i,o,c__5478__auto__,size__5479__auto__,b__20955,s__20953__$2,temp__5823__auto__,q,sid,ans,focused,active,sess,qin){
return (function (){
return ans(new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"cancelled","cancelled",488726224),false,new cljs.core.Keyword(null,"answer","answer",-742633163),grog_web.core.opt_label(o)], null));
});})(i__20954,vec__20956,i,o,c__5478__auto__,size__5479__auto__,b__20955,s__20953__$2,temp__5823__auto__,q,sid,ans,focused,active,sess,qin))
], null),grog_web.core.opt_label(o)], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),i], null)));

var G__21022 = (i__20954 + (1));
i__20954 = G__21022;
continue;
} else {
return true;
}
break;
}
})()){
return cljs.core.chunk_cons(cljs.core.chunk(b__20955),grog_web$core$question_dialog_$_iter__20952(cljs.core.chunk_rest(s__20953__$2)));
} else {
return cljs.core.chunk_cons(cljs.core.chunk(b__20955),null);
}
} else {
var vec__20959 = cljs.core.first(s__20953__$2);
var i = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20959,(0),null);
var o = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20959,(1),null);
return cljs.core.cons(cljs.core.with_meta(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"w-full text-left px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-200 hover:border-sky-500",new cljs.core.Keyword(null,"on-click","on-click",1632826543),((function (vec__20959,i,o,s__20953__$2,temp__5823__auto__,q,sid,ans,focused,active,sess,qin){
return (function (){
return ans(new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"cancelled","cancelled",488726224),false,new cljs.core.Keyword(null,"answer","answer",-742633163),grog_web.core.opt_label(o)], null));
});})(vec__20959,i,o,s__20953__$2,temp__5823__auto__,q,sid,ans,focused,active,sess,qin))
], null),grog_web.core.opt_label(o)], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),i], null)),grog_web$core$question_dialog_$_iter__20952(cljs.core.rest(s__20953__$2)));
}
} else {
return null;
}
break;
}
}),null,null));
});
return iter__5480__auto__(cljs.core.map_indexed.cljs$core$IFn$_invoke$arity$2(cljs.core.vector,new cljs.core.Keyword(null,"options","options",99638489).cljs$core$IFn$_invoke$arity$1(q)));
})()], null):null),(cljs.core.truth_(new cljs.core.Keyword(null,"allowFreeform?","allowFreeform?",2094476983).cljs$core$IFn$_invoke$arity$1(q))?new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"input","input",556931961),new cljs.core.PersistentArrayMap(null, 6, [new cljs.core.Keyword(null,"value","value",305978217),qin,new cljs.core.Keyword(null,"placeholder","placeholder",-104873083),"or type an answer\u2026",new cljs.core.Keyword(null,"autoFocus","autoFocus",-552622425),true,new cljs.core.Keyword(null,"on-change","on-change",-732046149),(function (p1__20950_SHARP_){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"q-input","q-input",-1764562816),p1__20950_SHARP_.target.value], null));
}),new cljs.core.Keyword(null,"on-key-down","on-key-down",-1374733765),(function (p1__20951_SHARP_){
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2("Enter",p1__20951_SHARP_.key)){
return ans(new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"cancelled","cancelled",488726224),false,new cljs.core.Keyword(null,"answer","answer",-742633163),qin], null));
} else {
return null;
}
}),new cljs.core.Keyword(null,"class","class",-2030961996),"w-full rounded-md bg-[#121212] border border-slate-700 px-3 py-2 text-sm text-slate-200 outline-none focus:border-sky-500"], null)], null):null),new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex gap-2 justify-end text-sm"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return ans(new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"cancelled","cancelled",488726224),true,new cljs.core.Keyword(null,"answer","answer",-742633163),null], null));
})], null),"Cancel"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1.5 rounded-md bg-sky-500 text-slate-950 font-medium",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return ans(new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"cancelled","cancelled",488726224),false,new cljs.core.Keyword(null,"answer","answer",-742633163),qin], null));
})], null),"Answer"], null)], null)], null)], null);
} else {
return null;
}
});
grog_web.core.project_manager = (function grog_web$core$project_manager(){
var in$ = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"dialog-input","dialog-input",-1508376519)], null)));
var projects = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"projects","projects",-364845983)], null)));
var order = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"order","order",-1254677256)], null)));
var sessions = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392)], null)));
var q = clojure.string.lower_case(clojure.string.trim(cljs.core.str.cljs$core$IFn$_invoke$arity$1(in$)));
var names = cljs.core.map.cljs$core$IFn$_invoke$arity$2((function (p1__20962_SHARP_){
if(cljs.core.map_QMARK_(p1__20962_SHARP_)){
return new cljs.core.Keyword(null,"name","name",1843675177).cljs$core$IFn$_invoke$arity$1(p1__20962_SHARP_);
} else {
return cljs.core.str.cljs$core$IFn$_invoke$arity$1(p1__20962_SHARP_);
}
}),projects);
var shown = ((cljs.core.seq(q))?cljs.core.filter.cljs$core$IFn$_invoke$arity$2((function (p1__20963_SHARP_){
return clojure.string.includes_QMARK_(clojure.string.lower_case(p1__20963_SHARP_),q);
}),names):names);
var exact_QMARK_ = cljs.core.contains_QMARK_(cljs.core.set(names),clojure.string.trim(cljs.core.str.cljs$core$IFn$_invoke$arity$1(in$)));
var open_names = cljs.core.set(cljs.core.map.cljs$core$IFn$_invoke$arity$2((function (p1__20964_SHARP_){
return cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(sessions,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [p1__20964_SHARP_,new cljs.core.Keyword(null,"project","project",1124394579)], null));
}),order));
var desc_of = (function (nm){
return cljs.core.some((function (p){
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(nm,((cljs.core.map_QMARK_(p))?new cljs.core.Keyword(null,"name","name",1843675177).cljs$core$IFn$_invoke$arity$1(p):cljs.core.str.cljs$core$IFn$_invoke$arity$1(p)))){
return new cljs.core.Keyword(null,"description","description",-1428560544).cljs$core$IFn$_invoke$arity$1(p);
} else {
return null;
}
}),projects);
});
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.overlay,new cljs.core.PersistentVector(null, 7, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"card rounded-xl shadow-2xl p-4 space-y-3 w-[560px] max-h-[80vh] flex flex-col"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-slate-200 text-sm font-medium"], null),"Open project"], null),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"input","input",556931961),new cljs.core.PersistentArrayMap(null, 6, [new cljs.core.Keyword(null,"value","value",305978217),in$,new cljs.core.Keyword(null,"autoFocus","autoFocus",-552622425),true,new cljs.core.Keyword(null,"placeholder","placeholder",-104873083),"filter or type a new project name\u2026",new cljs.core.Keyword(null,"on-change","on-change",-732046149),(function (p1__20965_SHARP_){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"dialog-input","dialog-input",-1508376519),p1__20965_SHARP_.target.value], null));
}),new cljs.core.Keyword(null,"on-key-down","on-key-down",-1374733765),(function (p1__20966_SHARP_){
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2("Enter",p1__20966_SHARP_.key)){
var v = clojure.string.trim(cljs.core.str.cljs$core$IFn$_invoke$arity$1(in$));
if(cljs.core.seq(v)){
if(cljs.core.contains_QMARK_(cljs.core.set(names),v)){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"pick-project","pick-project",1625273181),v], null));
} else {
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"open-new","open-new",1883565920)], null));
}
} else {
return null;
}
} else {
return null;
}
}),new cljs.core.Keyword(null,"class","class",-2030961996),"w-full rounded-md bg-[#121212] border border-slate-700 px-3 py-2 text-sm text-slate-200 outline-none focus:border-sky-500"], null)], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex-1 min-h-0 overflow-y-auto border border-slate-700 rounded-md divide-y divide-slate-800"], null),((cljs.core.seq(shown))?(function (){var iter__5480__auto__ = (function grog_web$core$project_manager_$_iter__20967(s__20968){
return (new cljs.core.LazySeq(null,(function (){
var s__20968__$1 = s__20968;
while(true){
var temp__5823__auto__ = cljs.core.seq(s__20968__$1);
if(temp__5823__auto__){
var s__20968__$2 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(s__20968__$2)){
var c__5478__auto__ = cljs.core.chunk_first(s__20968__$2);
var size__5479__auto__ = cljs.core.count(c__5478__auto__);
var b__20970 = cljs.core.chunk_buffer(size__5479__auto__);
if((function (){var i__20969 = (0);
while(true){
if((i__20969 < size__5479__auto__)){
var nm = cljs.core._nth(c__5478__auto__,i__20969);
cljs.core.chunk_append(b__20970,(function (){var desc = desc_of(nm);
var open_QMARK_ = cljs.core.contains_QMARK_(open_names,nm);
return cljs.core.with_meta(new cljs.core.PersistentVector(null, 5, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),["w-full text-left px-3 py-2 flex items-center gap-2 ",((open_QMARK_)?"bg-sky-500/15 text-sky-200 hover:bg-sky-500/25":"text-slate-200 hover:bg-slate-700/50")].join(''),new cljs.core.Keyword(null,"on-click","on-click",1632826543),((function (i__20969,desc,open_QMARK_,nm,c__5478__auto__,size__5479__auto__,b__20970,s__20968__$2,temp__5823__auto__,in$,projects,order,sessions,q,names,shown,exact_QMARK_,open_names,desc_of){
return (function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"pick-project","pick-project",1625273181),nm], null));
});})(i__20969,desc,open_QMARK_,nm,c__5478__auto__,size__5479__auto__,b__20970,s__20968__$2,temp__5823__auto__,in$,projects,order,sessions,q,names,shown,exact_QMARK_,open_names,desc_of))
], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex-1 truncate"], null),nm], null),(cljs.core.truth_(desc)?new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-[0.64rem] seg-snark truncate max-w-[45%]"], null),desc], null):null),((open_QMARK_)?new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-[0.64rem] text-sky-300 shrink-0"], null),"open"], null):null)], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),nm], null));
})());

var G__21023 = (i__20969 + (1));
i__20969 = G__21023;
continue;
} else {
return true;
}
break;
}
})()){
return cljs.core.chunk_cons(cljs.core.chunk(b__20970),grog_web$core$project_manager_$_iter__20967(cljs.core.chunk_rest(s__20968__$2)));
} else {
return cljs.core.chunk_cons(cljs.core.chunk(b__20970),null);
}
} else {
var nm = cljs.core.first(s__20968__$2);
return cljs.core.cons((function (){var desc = desc_of(nm);
var open_QMARK_ = cljs.core.contains_QMARK_(open_names,nm);
return cljs.core.with_meta(new cljs.core.PersistentVector(null, 5, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),["w-full text-left px-3 py-2 flex items-center gap-2 ",((open_QMARK_)?"bg-sky-500/15 text-sky-200 hover:bg-sky-500/25":"text-slate-200 hover:bg-slate-700/50")].join(''),new cljs.core.Keyword(null,"on-click","on-click",1632826543),((function (desc,open_QMARK_,nm,s__20968__$2,temp__5823__auto__,in$,projects,order,sessions,q,names,shown,exact_QMARK_,open_names,desc_of){
return (function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"pick-project","pick-project",1625273181),nm], null));
});})(desc,open_QMARK_,nm,s__20968__$2,temp__5823__auto__,in$,projects,order,sessions,q,names,shown,exact_QMARK_,open_names,desc_of))
], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex-1 truncate"], null),nm], null),(cljs.core.truth_(desc)?new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-[0.64rem] seg-snark truncate max-w-[45%]"], null),desc], null):null),((open_QMARK_)?new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-[0.64rem] text-sky-300 shrink-0"], null),"open"], null):null)], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),nm], null));
})(),grog_web$core$project_manager_$_iter__20967(cljs.core.rest(s__20968__$2)));
}
} else {
return null;
}
break;
}
}),null,null));
});
return iter__5480__auto__(shown);
})():new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-xs text-slate-500 p-3"], null),((cljs.core.seq(q))?"no matching project":"no projects found")], null))], null),((((cljs.core.seq(q)) && ((!(exact_QMARK_)))))?new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-2 rounded-md bg-emerald-600/20 border border-emerald-600/50 text-emerald-200 text-sm text-left hover:bg-emerald-600/30",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"open-new","open-new",1883565920)], null));
})], null),["+ create project \u201C",clojure.string.trim(cljs.core.str.cljs$core$IFn$_invoke$arity$1(in$)),"\u201D"].join('')], null):null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex gap-2 justify-end text-sm"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"dialog-close","dialog-close",180756817)], null));
})], null),"Cancel"], null)], null)], null)], null);
});
/**
 * Model sources the picker offers, in order. `eca` is ECA's own catalogue
 *   (offline, arrives on connect); the other two are fetched.
 */
grog_web.core.picker_sources = new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, ["eca","ECA"], null),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, ["openrouter","OpenRouter"], null),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, ["ollama","Ollama"], null)], null);
grog_web.core.settings_dialog = (function grog_web$core$settings_dialog(){
var in$ = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"dialog-input","dialog-input",-1508376519)], null)));
var active = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"active","active",1895962068)], null)));
var sess = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"sessions","sessions",-699316392)], null))),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [active], null));
var src = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"model-source","model-source",-1983623726)], null)));
var q = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"model-query","model-query",-2037530197)], null)));
var cat = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"catalogue","catalogue",224534933)], null)));
var all = cljs.core.get.cljs$core$IFn$_invoke$arity$2(cat,cljs.core.keyword.cljs$core$IFn$_invoke$arity$1(src));
var all__$1 = (function (){var or__5002__auto__ = all;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return cljs.core.PersistentVector.EMPTY;
}
})();
var shown = ((clojure.string.blank_QMARK_(q))?all__$1:cljs.core.filterv((function (p1__20971_SHARP_){
return clojure.string.includes_QMARK_(clojure.string.lower_case(p1__20971_SHARP_),clojure.string.lower_case(q));
}),all__$1));
var loading_src_QMARK_ = cljs.core.some((function (p1__20972_SHARP_){
return cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(src,p1__20972_SHARP_);
}),new cljs.core.Keyword(null,"loading","loading",-737050189).cljs$core$IFn$_invoke$arity$1(cat));
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.overlay,new cljs.core.PersistentVector(null, 11, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"card rounded-xl shadow-2xl p-4 space-y-3 w-[520px]"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-slate-200 text-sm font-medium"], null),"Settings"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-xs text-slate-400"], null),["project: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"project","project",1124394579).cljs$core$IFn$_invoke$arity$1(sess))].join('')], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"label","label",1718410804),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-xs text-slate-400"], null),"model"], null),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"input","input",556931961),new cljs.core.PersistentArrayMap(null, 6, [new cljs.core.Keyword(null,"value","value",305978217),in$,new cljs.core.Keyword(null,"autoFocus","autoFocus",-552622425),true,new cljs.core.Keyword(null,"placeholder","placeholder",-104873083),(function (){var or__5002__auto__ = new cljs.core.Keyword(null,"model","model",331153215).cljs$core$IFn$_invoke$arity$1(sess);
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return "provider/model";
}
})(),new cljs.core.Keyword(null,"on-change","on-change",-732046149),(function (p1__20973_SHARP_){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"dialog-input","dialog-input",-1508376519),p1__20973_SHARP_.target.value], null));
}),new cljs.core.Keyword(null,"on-key-down","on-key-down",-1374733765),(function (p1__20974_SHARP_){
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2("Enter",p1__20974_SHARP_.key)){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"set-model","set-model",-2046273150),active,in$], null));
} else {
return null;
}
}),new cljs.core.Keyword(null,"class","class",-2030961996),"w-full rounded-md bg-[#121212] border border-slate-700 px-3 py-2 text-sm text-slate-200 outline-none focus:border-sky-500"], null)], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-[0.7rem] text-slate-500"], null),"persists via /eca-model on the server"], null),new cljs.core.PersistentVector(null, 6, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"border-t border-slate-700 pt-3 space-y-2"], null),new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex items-center gap-1"], null),cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex items-center gap-1"], null)], null),(function (){var iter__5480__auto__ = (function grog_web$core$settings_dialog_$_iter__20977(s__20978){
return (new cljs.core.LazySeq(null,(function (){
var s__20978__$1 = s__20978;
while(true){
var temp__5823__auto__ = cljs.core.seq(s__20978__$1);
if(temp__5823__auto__){
var s__20978__$2 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(s__20978__$2)){
var c__5478__auto__ = cljs.core.chunk_first(s__20978__$2);
var size__5479__auto__ = cljs.core.count(c__5478__auto__);
var b__20980 = cljs.core.chunk_buffer(size__5479__auto__);
if((function (){var i__20979 = (0);
while(true){
if((i__20979 < size__5479__auto__)){
var vec__20981 = cljs.core._nth(c__5478__auto__,i__20979);
var id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20981,(0),null);
var label = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20981,(1),null);
cljs.core.chunk_append(b__20980,cljs.core.with_meta(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),["px-2 py-1 rounded text-[0.7rem] border ",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(id,src))?"bg-sky-600 border-sky-500 text-slate-950 font-medium":"bg-slate-800 border-slate-700 text-slate-300")].join(''),new cljs.core.Keyword(null,"on-click","on-click",1632826543),((function (i__20979,vec__20981,id,label,c__5478__auto__,size__5479__auto__,b__20980,s__20978__$2,temp__5823__auto__,in$,active,sess,src,q,cat,all,all__$1,shown,loading_src_QMARK_){
return (function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"models-source","models-source",-2075783156),id], null));
});})(i__20979,vec__20981,id,label,c__5478__auto__,size__5479__auto__,b__20980,s__20978__$2,temp__5823__auto__,in$,active,sess,src,q,cat,all,all__$1,shown,loading_src_QMARK_))
], null),label], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),id], null)));

var G__21024 = (i__20979 + (1));
i__20979 = G__21024;
continue;
} else {
return true;
}
break;
}
})()){
return cljs.core.chunk_cons(cljs.core.chunk(b__20980),grog_web$core$settings_dialog_$_iter__20977(cljs.core.chunk_rest(s__20978__$2)));
} else {
return cljs.core.chunk_cons(cljs.core.chunk(b__20980),null);
}
} else {
var vec__20984 = cljs.core.first(s__20978__$2);
var id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20984,(0),null);
var label = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20984,(1),null);
return cljs.core.cons(cljs.core.with_meta(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),["px-2 py-1 rounded text-[0.7rem] border ",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(id,src))?"bg-sky-600 border-sky-500 text-slate-950 font-medium":"bg-slate-800 border-slate-700 text-slate-300")].join(''),new cljs.core.Keyword(null,"on-click","on-click",1632826543),((function (vec__20984,id,label,s__20978__$2,temp__5823__auto__,in$,active,sess,src,q,cat,all,all__$1,shown,loading_src_QMARK_){
return (function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"models-source","models-source",-2075783156),id], null));
});})(vec__20984,id,label,s__20978__$2,temp__5823__auto__,in$,active,sess,src,q,cat,all,all__$1,shown,loading_src_QMARK_))
], null),label], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),id], null)),grog_web$core$settings_dialog_$_iter__20977(cljs.core.rest(s__20978__$2)));
}
} else {
return null;
}
break;
}
}),null,null));
});
return iter__5480__auto__(grog_web.core.picker_sources);
})()),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"class","class",-2030961996),"ml-auto px-2 py-1 rounded text-[0.7rem] bg-slate-800 border border-slate-700 text-slate-300",new cljs.core.Keyword(null,"title","title",636505583),"refresh this source",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"models-fetch","models-fetch",-358237643),new cljs.core.Keyword(null,"force","force",781957286)], null));
})], null),"\u21BB"], null)], null),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"input","input",556931961),new cljs.core.PersistentArrayMap(null, 5, [new cljs.core.Keyword(null,"value","value",305978217),q,new cljs.core.Keyword(null,"placeholder","placeholder",-104873083),"search models\u2026",new cljs.core.Keyword(null,"on-change","on-change",-732046149),(function (p1__20975_SHARP_){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"model-query","model-query",-2037530197),p1__20975_SHARP_.target.value], null));
}),new cljs.core.Keyword(null,"on-key-down","on-key-down",-1374733765),(function (p1__20976_SHARP_){
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2("Escape",p1__20976_SHARP_.key)){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"model-query","model-query",-2037530197),""], null));
} else {
return null;
}
}),new cljs.core.Keyword(null,"class","class",-2030961996),"w-full rounded-md bg-[#121212] border border-slate-700 px-3 py-1.5 text-xs text-slate-200 outline-none focus:border-sky-500"], null)], null),cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"max-h-48 overflow-y-auto rounded-md border border-slate-700"], null)], null),(cljs.core.truth_(loading_src_QMARK_)?new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"p-2 text-xs text-slate-500"], null),["loading ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(src),"\u2026"].join('')], null)], null):((cljs.core.empty_QMARK_(shown))?new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"p-2 text-xs text-slate-500"], null),["no models \u2014 ",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2("eca",src))?"connect a session, or pick another source":"press \u21BB to fetch")].join('')], null)], null):(function (){var iter__5480__auto__ = (function grog_web$core$settings_dialog_$_iter__20987(s__20988){
return (new cljs.core.LazySeq(null,(function (){
var s__20988__$1 = s__20988;
while(true){
var temp__5823__auto__ = cljs.core.seq(s__20988__$1);
if(temp__5823__auto__){
var s__20988__$2 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(s__20988__$2)){
var c__5478__auto__ = cljs.core.chunk_first(s__20988__$2);
var size__5479__auto__ = cljs.core.count(c__5478__auto__);
var b__20990 = cljs.core.chunk_buffer(size__5479__auto__);
if((function (){var i__20989 = (0);
while(true){
if((i__20989 < size__5479__auto__)){
var m = cljs.core._nth(c__5478__auto__,i__20989);
cljs.core.chunk_append(b__20990,cljs.core.with_meta(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-2 py-1 text-[0.7rem] font-mono text-slate-300 hover:bg-slate-800 cursor-pointer",new cljs.core.Keyword(null,"on-click","on-click",1632826543),((function (i__20989,m,c__5478__auto__,size__5479__auto__,b__20990,s__20988__$2,temp__5823__auto__,in$,active,sess,src,q,cat,all,all__$1,shown,loading_src_QMARK_){
return (function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"dialog-input","dialog-input",-1508376519),m], null));
});})(i__20989,m,c__5478__auto__,size__5479__auto__,b__20990,s__20988__$2,temp__5823__auto__,in$,active,sess,src,q,cat,all,all__$1,shown,loading_src_QMARK_))
,new cljs.core.Keyword(null,"on-double-click","on-double-click",1434856980),((function (i__20989,m,c__5478__auto__,size__5479__auto__,b__20990,s__20988__$2,temp__5823__auto__,in$,active,sess,src,q,cat,all,all__$1,shown,loading_src_QMARK_){
return (function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"set-model","set-model",-2046273150),active,m,src], null));
});})(i__20989,m,c__5478__auto__,size__5479__auto__,b__20990,s__20988__$2,temp__5823__auto__,in$,active,sess,src,q,cat,all,all__$1,shown,loading_src_QMARK_))
], null),m], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),m], null)));

var G__21025 = (i__20989 + (1));
i__20989 = G__21025;
continue;
} else {
return true;
}
break;
}
})()){
return cljs.core.chunk_cons(cljs.core.chunk(b__20990),grog_web$core$settings_dialog_$_iter__20987(cljs.core.chunk_rest(s__20988__$2)));
} else {
return cljs.core.chunk_cons(cljs.core.chunk(b__20990),null);
}
} else {
var m = cljs.core.first(s__20988__$2);
return cljs.core.cons(cljs.core.with_meta(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-2 py-1 text-[0.7rem] font-mono text-slate-300 hover:bg-slate-800 cursor-pointer",new cljs.core.Keyword(null,"on-click","on-click",1632826543),((function (m,s__20988__$2,temp__5823__auto__,in$,active,sess,src,q,cat,all,all__$1,shown,loading_src_QMARK_){
return (function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"dialog-input","dialog-input",-1508376519),m], null));
});})(m,s__20988__$2,temp__5823__auto__,in$,active,sess,src,q,cat,all,all__$1,shown,loading_src_QMARK_))
,new cljs.core.Keyword(null,"on-double-click","on-double-click",1434856980),((function (m,s__20988__$2,temp__5823__auto__,in$,active,sess,src,q,cat,all,all__$1,shown,loading_src_QMARK_){
return (function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"set-model","set-model",-2046273150),active,m,src], null));
});})(m,s__20988__$2,temp__5823__auto__,in$,active,sess,src,q,cat,all,all__$1,shown,loading_src_QMARK_))
], null),m], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"key","key",-1516042587),m], null)),grog_web$core$settings_dialog_$_iter__20987(cljs.core.rest(s__20988__$2)));
}
} else {
return null;
}
break;
}
}),null,null));
});
return iter__5480__auto__(shown);
})()
))),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-[0.7rem] text-slate-500"], null),[cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.count(shown))," model",((cljs.core.not_EQ_.cljs$core$IFn$_invoke$arity$2((1),cljs.core.count(shown)))?"s":null)," \u00B7 click fills the box, double-click applies"].join('')], null)], null),new cljs.core.PersistentVector(null, 5, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"border-t border-slate-700 pt-3 space-y-1"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"label","label",1718410804),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-xs text-slate-400"], null),"font size"], null),new cljs.core.PersistentVector(null, 6, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex items-center gap-2"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1 rounded bg-slate-800 border border-slate-700 text-slate-200",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"bump-font","bump-font",-1491756021),(-1)], null));
})], null),"A\u2212"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"span","span",1394872991),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"w-12 text-center text-slate-200 text-sm"], null),[cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"font-size","font-size",-1847940346)], null)))),"px"].join('')], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1 rounded bg-slate-800 border border-slate-700 text-slate-200",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"bump-font","bump-font",-1491756021),(1)], null));
})], null),"A+"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1 rounded bg-slate-800 border border-slate-700 text-slate-400",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"set-font-size","set-font-size",-804794238),(16)], null));
})], null),"reset"], null)], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-[0.7rem] text-slate-500"], null),"Ctrl+= / Ctrl+- / Ctrl+0 \u00B7 saved locally"], null)], null),new cljs.core.PersistentVector(null, 5, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"border-t border-slate-700 pt-3 space-y-1"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"label","label",1718410804),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-xs text-slate-400"], null),"voice input"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-xs text-slate-300"], null),(function (){var st = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"voice","voice",185716428)], null)));
if(cljs.core.truth_((function (){var and__5000__auto__ = st;
if(cljs.core.truth_(and__5000__auto__)){
return new cljs.core.Keyword(null,"enabled","enabled",1195909756).cljs$core$IFn$_invoke$arity$1(st);
} else {
return and__5000__auto__;
}
})())){
return ["on \u00B7 ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"engine","engine",1459054265).cljs$core$IFn$_invoke$arity$1(st))," \u00B7 max ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"maxSeconds","maxSeconds",618838596).cljs$core$IFn$_invoke$arity$1(st)),"s"].join('');
} else {
return "off \u2014 set GROG_VOICE_COMMAND";
}
})()], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"text-[0.7rem] text-slate-500"], null),"mic + transcription stay on this machine; nothing is sent to the server"], null)], null),new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex gap-2 justify-end text-sm"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"dialog-close","dialog-close",180756817)], null));
})], null),"Cancel"], null),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"button","button",1456579943),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"class","class",-2030961996),"px-3 py-1.5 rounded-md bg-sky-500 text-slate-950 font-medium",new cljs.core.Keyword(null,"on-click","on-click",1632826543),(function (){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"set-model","set-model",-2046273150),active,in$], null));
})], null),"Save"], null)], null)], null)], null);
});
grog_web.core.shell = (function grog_web$core$shell(){
var active = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"active","active",1895962068)], null)));
var dialog = cljs.core.deref(re_frame.core.subscribe.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"ui-dialog","ui-dialog",1514352730)], null)));
return new cljs.core.PersistentVector(null, 8, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"app h-screen w-screen flex flex-col overflow-hidden"], null),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.banner], null),new cljs.core.PersistentVector(null, 6, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex-1 min-h-0 flex flex-col overflow-hidden"], null),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.tab_strip], null),(cljs.core.truth_(active)?new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.transcript,active], null):new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"div","div",1057191632),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"class","class",-2030961996),"flex-1 grid place-items-center text-slate-500"], null),"connecting\u2026"], null)),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.composer], null),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.status_bar], null)], null),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.approval_dialog], null),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.question_dialog], null),((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"projects","projects",-364845983),dialog))?new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.project_manager], null):null),((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"settings","settings",1556144875),dialog))?new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.settings_dialog], null):null)], null);
});
grog_web.core.init = (function grog_web$core$init(){
re_frame.core.dispatch_sync(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"init","init",-1875481434)], null));

re_frame.core.dispatch_sync(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"set-font-size","set-font-size",-804794238),(function (){var s = (function (){try{return window.localStorage.getItem("grog-web.font-size");
}catch (e20991){var _ = e20991;
return null;
}})();
var n = parseInt((function (){var or__5002__auto__ = s;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return "16";
}
})());
if(cljs.core.truth_(isNaN(n))){
return (16);
} else {
return n;
}
})()], null));

window.grogAPI.onNotify((function (msg){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"notify","notify",-1256867814),cljs.core.js__GT_clj.cljs$core$IFn$_invoke$arity$variadic(msg,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"keywordize-keys","keywordize-keys",1310784252),true], 0))], null));
}));

window.grogAPI.onFocus((function (f){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"focus","focus",234677911),cljs.core.boolean$(f)], null));
}));

document.addEventListener("keydown",(function (e){
var ctrl = e.ctrlKey;
var shift = e.shiftKey;
var k = e.key;
if(cljs.core.truth_((function (){var and__5000__auto__ = ctrl;
if(cljs.core.truth_(and__5000__auto__)){
return cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(k,"t");
} else {
return and__5000__auto__;
}
})())){
e.preventDefault();

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"dialog-open","dialog-open",2100079327),new cljs.core.Keyword(null,"projects","projects",-364845983),""], null));
} else {
if(cljs.core.truth_((function (){var and__5000__auto__ = ctrl;
if(cljs.core.truth_(and__5000__auto__)){
return cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(k,"w");
} else {
return and__5000__auto__;
}
})())){
e.preventDefault();

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"close-active","close-active",-1194859253)], null));
} else {
if(cljs.core.truth_((function (){var and__5000__auto__ = ctrl;
if(cljs.core.truth_(and__5000__auto__)){
return cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(k,"Tab");
} else {
return and__5000__auto__;
}
})())){
e.preventDefault();

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"cycle","cycle",710365284),(cljs.core.truth_(shift)?(-1):(1))], null));
} else {
if(cljs.core.truth_((function (){var and__5000__auto__ = ctrl;
if(cljs.core.truth_(and__5000__auto__)){
return cljs.core.re_matches(/[1-9]/,k);
} else {
return and__5000__auto__;
}
})())){
e.preventDefault();

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"jump","jump",-971319427),parseInt(k)], null));
} else {
if(cljs.core.truth_((function (){var and__5000__auto__ = ctrl;
if(cljs.core.truth_(and__5000__auto__)){
return cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(k,"e");
} else {
return and__5000__auto__;
}
})())){
e.preventDefault();

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"copy-transcript","copy-transcript",-2113119949)], null));
} else {
if(cljs.core.truth_((function (){var and__5000__auto__ = ctrl;
if(cljs.core.truth_(and__5000__auto__)){
return ((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(k,"=")) || (cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(k,"+")));
} else {
return and__5000__auto__;
}
})())){
e.preventDefault();

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"bump-font","bump-font",-1491756021),(1)], null));
} else {
if(cljs.core.truth_((function (){var and__5000__auto__ = ctrl;
if(cljs.core.truth_(and__5000__auto__)){
return cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(k,"-");
} else {
return and__5000__auto__;
}
})())){
e.preventDefault();

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"bump-font","bump-font",-1491756021),(-1)], null));
} else {
if(cljs.core.truth_((function (){var and__5000__auto__ = ctrl;
if(cljs.core.truth_(and__5000__auto__)){
return cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(k,"0");
} else {
return and__5000__auto__;
}
})())){
e.preventDefault();

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"set-font-size","set-font-size",-804794238),(16)], null));
} else {
if(cljs.core.truth_((function (){var and__5000__auto__ = ctrl;
if(cljs.core.truth_(and__5000__auto__)){
var and__5000__auto____$1 = shift;
if(cljs.core.truth_(and__5000__auto____$1)){
return cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(k," ");
} else {
return and__5000__auto____$1;
}
} else {
return and__5000__auto__;
}
})())){
e.preventDefault();

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"toggle-voice","toggle-voice",1481320800)], null));
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(k,"Escape")){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"key-escape","key-escape",-196986402)], null));
} else {
return null;

}
}
}
}
}
}
}
}
}
}
}));

window.grogAPI.socketPath().then((function (p){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"connected","connected",-169833045),false,p], null));
}));

window.grogAPI.voiceStatus().then((function (s){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"voice-status","voice-status",699058004),cljs.core.js__GT_clj.cljs$core$IFn$_invoke$arity$variadic(s,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"keywordize-keys","keywordize-keys",1310784252),true], 0))], null));
})).catch((function (_){
return null;
}));

window.grogAPI.call("open",cljs.core.clj__GT_js(new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"project","project",1124394579),grog_web.core.project_name], null))).then((function (snap){
var snap__$1 = cljs.core.js__GT_clj.cljs$core$IFn$_invoke$arity$variadic(snap,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"keywordize-keys","keywordize-keys",1310784252),true], 0));
re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"open-ok","open-ok",1132719375),snap__$1], null));

return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"connect","connect",1232828233),new cljs.core.Keyword(null,"id","id",-1388402092).cljs$core$IFn$_invoke$arity$1(snap__$1)], null));
})).catch((function (e){
return re_frame.core.dispatch(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"status-line","status-line",-1007802282),null,["[grog] open failed: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e.message)].join('')], null));
}));

return reagent.dom.render.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.core.shell], null),document.getElementById("app"));
});
goog.exportSymbol('grog_web.core.init', grog_web.core.init);

//# sourceMappingURL=grog_web.core.js.map
