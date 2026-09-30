goog.provide('re_frame.fx');
re_frame.fx.kind = new cljs.core.Keyword(null,"fx","fx",-1237829572);
if(cljs.core.truth_((re_frame.registrar.kinds.cljs$core$IFn$_invoke$arity$1 ? re_frame.registrar.kinds.cljs$core$IFn$_invoke$arity$1(re_frame.fx.kind) : re_frame.registrar.kinds.call(null, re_frame.fx.kind)))){
} else {
throw (new Error("Assert failed: (re-frame.registrar/kinds kind)"));
}
re_frame.fx.reg_fx = (function re_frame$fx$reg_fx(id,handler){
return re_frame.registrar.register_handler(re_frame.fx.kind,id,handler);
});
/**
 * An interceptor whose `:after` actions the contents of `:effects`. As a result,
 *   this interceptor is Domino 3.
 * 
 *   This interceptor is silently added (by reg-event-db etc) to the front of
 *   interceptor chains for all events.
 * 
 *   For each key in `:effects` (a map), it calls the registered `effects handler`
 *   (see `reg-fx` for registration of effect handlers).
 * 
 *   So, if `:effects` was:
 *    {:dispatch  [:hello 42]
 *     :db        {...}
 *     :undo      "set flag"}
 * 
 *   it will call the registered effect handlers for each of the map's keys:
 *   `:dispatch`, `:undo` and `:db`. When calling each handler, provides the map
 *   value for that key - so in the example above the effect handler for :dispatch
 *   will be given one arg `[:hello 42]`.
 * 
 *   You cannot rely on the ordering in which effects are executed, other than that
 *   `:db` is guaranteed to be executed first.
 */
re_frame.fx.do_fx = re_frame.interceptor.__GT_interceptor.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"id","id",-1388402092),new cljs.core.Keyword(null,"do-fx","do-fx",1194163050),new cljs.core.Keyword(null,"after","after",594996914),(function re_frame$fx$do_fx_after(context){
if(re_frame.trace.is_trace_enabled_QMARK_()){
var _STAR_current_trace_STAR__orig_val__39518 = re_frame.trace._STAR_current_trace_STAR_;
var _STAR_current_trace_STAR__temp_val__39519 = re_frame.trace.start_trace(new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"op-type","op-type",-1636141668),new cljs.core.Keyword("event","do-fx","event/do-fx",1357330452)], null));
(re_frame.trace._STAR_current_trace_STAR_ = _STAR_current_trace_STAR__temp_val__39519);

try{try{var effects = new cljs.core.Keyword(null,"effects","effects",-282369292).cljs$core$IFn$_invoke$arity$1(context);
var effects_without_db = cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(effects,new cljs.core.Keyword(null,"db","db",993250759));
var temp__5823__auto___39682 = new cljs.core.Keyword(null,"db","db",993250759).cljs$core$IFn$_invoke$arity$1(effects);
if(cljs.core.truth_(temp__5823__auto___39682)){
var new_db_39683 = temp__5823__auto___39682;
var fexpr__39524_39684 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,new cljs.core.Keyword(null,"db","db",993250759),false);
(fexpr__39524_39684.cljs$core$IFn$_invoke$arity$1 ? fexpr__39524_39684.cljs$core$IFn$_invoke$arity$1(new_db_39683) : fexpr__39524_39684.call(null, new_db_39683));
} else {
}

var seq__39526 = cljs.core.seq(effects_without_db);
var chunk__39527 = null;
var count__39528 = (0);
var i__39529 = (0);
while(true){
if((i__39529 < count__39528)){
var vec__39549 = chunk__39527.cljs$core$IIndexed$_nth$arity$2(null, i__39529);
var effect_key = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__39549,(0),null);
var effect_value = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__39549,(1),null);
var temp__5821__auto___39685 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,effect_key,false);
if(cljs.core.truth_(temp__5821__auto___39685)){
var effect_fn_39686 = temp__5821__auto___39685;
(effect_fn_39686.cljs$core$IFn$_invoke$arity$1 ? effect_fn_39686.cljs$core$IFn$_invoke$arity$1(effect_value) : effect_fn_39686.call(null, effect_value));
} else {
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: no handler registered for effect:",effect_key,". Ignoring.",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"event","event",301435442),effect_key))?["You may be trying to return a coeffect map from an event-fx handler. ","See https://day8.github.io/re-frame/use-cofx-as-fx/"].join(''):null)], 0));
}


var G__39687 = seq__39526;
var G__39688 = chunk__39527;
var G__39689 = count__39528;
var G__39690 = (i__39529 + (1));
seq__39526 = G__39687;
chunk__39527 = G__39688;
count__39528 = G__39689;
i__39529 = G__39690;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__39526);
if(temp__5823__auto__){
var seq__39526__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__39526__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__39526__$1);
var G__39691 = cljs.core.chunk_rest(seq__39526__$1);
var G__39692 = c__5525__auto__;
var G__39693 = cljs.core.count(c__5525__auto__);
var G__39694 = (0);
seq__39526 = G__39691;
chunk__39527 = G__39692;
count__39528 = G__39693;
i__39529 = G__39694;
continue;
} else {
var vec__39555 = cljs.core.first(seq__39526__$1);
var effect_key = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__39555,(0),null);
var effect_value = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__39555,(1),null);
var temp__5821__auto___39695 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,effect_key,false);
if(cljs.core.truth_(temp__5821__auto___39695)){
var effect_fn_39696 = temp__5821__auto___39695;
(effect_fn_39696.cljs$core$IFn$_invoke$arity$1 ? effect_fn_39696.cljs$core$IFn$_invoke$arity$1(effect_value) : effect_fn_39696.call(null, effect_value));
} else {
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: no handler registered for effect:",effect_key,". Ignoring.",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"event","event",301435442),effect_key))?["You may be trying to return a coeffect map from an event-fx handler. ","See https://day8.github.io/re-frame/use-cofx-as-fx/"].join(''):null)], 0));
}


var G__39697 = cljs.core.next(seq__39526__$1);
var G__39698 = null;
var G__39699 = (0);
var G__39700 = (0);
seq__39526 = G__39697;
chunk__39527 = G__39698;
count__39528 = G__39699;
i__39529 = G__39700;
continue;
}
} else {
return null;
}
}
break;
}
}finally {if(re_frame.trace.is_trace_enabled_QMARK_()){
var end__38903__auto___39701 = re_frame.interop.now();
var duration__38904__auto___39702 = (end__38903__auto___39701 - new cljs.core.Keyword(null,"start","start",-355208981).cljs$core$IFn$_invoke$arity$1(re_frame.trace._STAR_current_trace_STAR_));
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(re_frame.trace.traces,cljs.core.conj,cljs.core.assoc.cljs$core$IFn$_invoke$arity$variadic(re_frame.trace._STAR_current_trace_STAR_,new cljs.core.Keyword(null,"duration","duration",1444101068),duration__38904__auto___39702,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"end","end",-268185958),re_frame.interop.now()], 0)));

re_frame.trace.run_tracing_callbacks_BANG_(end__38903__auto___39701);
} else {
}
}}finally {(re_frame.trace._STAR_current_trace_STAR_ = _STAR_current_trace_STAR__orig_val__39518);
}} else {
var effects = new cljs.core.Keyword(null,"effects","effects",-282369292).cljs$core$IFn$_invoke$arity$1(context);
var effects_without_db = cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(effects,new cljs.core.Keyword(null,"db","db",993250759));
var temp__5823__auto___39703 = new cljs.core.Keyword(null,"db","db",993250759).cljs$core$IFn$_invoke$arity$1(effects);
if(cljs.core.truth_(temp__5823__auto___39703)){
var new_db_39704 = temp__5823__auto___39703;
var fexpr__39559_39705 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,new cljs.core.Keyword(null,"db","db",993250759),false);
(fexpr__39559_39705.cljs$core$IFn$_invoke$arity$1 ? fexpr__39559_39705.cljs$core$IFn$_invoke$arity$1(new_db_39704) : fexpr__39559_39705.call(null, new_db_39704));
} else {
}

var seq__39560 = cljs.core.seq(effects_without_db);
var chunk__39561 = null;
var count__39562 = (0);
var i__39563 = (0);
while(true){
if((i__39563 < count__39562)){
var vec__39588 = chunk__39561.cljs$core$IIndexed$_nth$arity$2(null, i__39563);
var effect_key = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__39588,(0),null);
var effect_value = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__39588,(1),null);
var temp__5821__auto___39706 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,effect_key,false);
if(cljs.core.truth_(temp__5821__auto___39706)){
var effect_fn_39707 = temp__5821__auto___39706;
(effect_fn_39707.cljs$core$IFn$_invoke$arity$1 ? effect_fn_39707.cljs$core$IFn$_invoke$arity$1(effect_value) : effect_fn_39707.call(null, effect_value));
} else {
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: no handler registered for effect:",effect_key,". Ignoring.",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"event","event",301435442),effect_key))?["You may be trying to return a coeffect map from an event-fx handler. ","See https://day8.github.io/re-frame/use-cofx-as-fx/"].join(''):null)], 0));
}


var G__39708 = seq__39560;
var G__39709 = chunk__39561;
var G__39710 = count__39562;
var G__39711 = (i__39563 + (1));
seq__39560 = G__39708;
chunk__39561 = G__39709;
count__39562 = G__39710;
i__39563 = G__39711;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__39560);
if(temp__5823__auto__){
var seq__39560__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__39560__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__39560__$1);
var G__39712 = cljs.core.chunk_rest(seq__39560__$1);
var G__39713 = c__5525__auto__;
var G__39714 = cljs.core.count(c__5525__auto__);
var G__39715 = (0);
seq__39560 = G__39712;
chunk__39561 = G__39713;
count__39562 = G__39714;
i__39563 = G__39715;
continue;
} else {
var vec__39594 = cljs.core.first(seq__39560__$1);
var effect_key = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__39594,(0),null);
var effect_value = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__39594,(1),null);
var temp__5821__auto___39716 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,effect_key,false);
if(cljs.core.truth_(temp__5821__auto___39716)){
var effect_fn_39717 = temp__5821__auto___39716;
(effect_fn_39717.cljs$core$IFn$_invoke$arity$1 ? effect_fn_39717.cljs$core$IFn$_invoke$arity$1(effect_value) : effect_fn_39717.call(null, effect_value));
} else {
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: no handler registered for effect:",effect_key,". Ignoring.",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"event","event",301435442),effect_key))?["You may be trying to return a coeffect map from an event-fx handler. ","See https://day8.github.io/re-frame/use-cofx-as-fx/"].join(''):null)], 0));
}


var G__39718 = cljs.core.next(seq__39560__$1);
var G__39719 = null;
var G__39720 = (0);
var G__39721 = (0);
seq__39560 = G__39718;
chunk__39561 = G__39719;
count__39562 = G__39720;
i__39563 = G__39721;
continue;
}
} else {
return null;
}
}
break;
}
}
})], 0));
re_frame.fx.dispatch_later = (function re_frame$fx$dispatch_later(p__39600){
var map__39601 = p__39600;
var map__39601__$1 = cljs.core.__destructure_map(map__39601);
var effect = map__39601__$1;
var ms = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39601__$1,new cljs.core.Keyword(null,"ms","ms",-1152709733));
var dispatch = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39601__$1,new cljs.core.Keyword(null,"dispatch","dispatch",1319337009));
if(((cljs.core.empty_QMARK_(dispatch)) || ((!(typeof ms === 'number'))))){
return re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"error","error",-978969032),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: ignoring bad :dispatch-later value:",effect], 0));
} else {
return re_frame.interop.set_timeout_BANG_((function (){
return re_frame.router.dispatch(dispatch);
}),ms);
}
});
re_frame.fx.reg_fx(new cljs.core.Keyword(null,"dispatch-later","dispatch-later",291951390),(function (value){
if(cljs.core.map_QMARK_(value)){
return re_frame.fx.dispatch_later(value);
} else {
var seq__39603 = cljs.core.seq(cljs.core.remove.cljs$core$IFn$_invoke$arity$2(cljs.core.nil_QMARK_,value));
var chunk__39604 = null;
var count__39605 = (0);
var i__39606 = (0);
while(true){
if((i__39606 < count__39605)){
var effect = chunk__39604.cljs$core$IIndexed$_nth$arity$2(null, i__39606);
re_frame.fx.dispatch_later(effect);


var G__39722 = seq__39603;
var G__39723 = chunk__39604;
var G__39724 = count__39605;
var G__39725 = (i__39606 + (1));
seq__39603 = G__39722;
chunk__39604 = G__39723;
count__39605 = G__39724;
i__39606 = G__39725;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__39603);
if(temp__5823__auto__){
var seq__39603__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__39603__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__39603__$1);
var G__39726 = cljs.core.chunk_rest(seq__39603__$1);
var G__39727 = c__5525__auto__;
var G__39728 = cljs.core.count(c__5525__auto__);
var G__39729 = (0);
seq__39603 = G__39726;
chunk__39604 = G__39727;
count__39605 = G__39728;
i__39606 = G__39729;
continue;
} else {
var effect = cljs.core.first(seq__39603__$1);
re_frame.fx.dispatch_later(effect);


var G__39730 = cljs.core.next(seq__39603__$1);
var G__39731 = null;
var G__39732 = (0);
var G__39733 = (0);
seq__39603 = G__39730;
chunk__39604 = G__39731;
count__39605 = G__39732;
i__39606 = G__39733;
continue;
}
} else {
return null;
}
}
break;
}
}
}));
re_frame.fx.reg_fx(new cljs.core.Keyword(null,"fx","fx",-1237829572),(function (seq_of_effects){
if((!(cljs.core.sequential_QMARK_(seq_of_effects)))){
return re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: \":fx\" effect expects a seq, but was given ",cljs.core.type(seq_of_effects)], 0));
} else {
var seq__39631 = cljs.core.seq(cljs.core.remove.cljs$core$IFn$_invoke$arity$2(cljs.core.nil_QMARK_,seq_of_effects));
var chunk__39632 = null;
var count__39633 = (0);
var i__39634 = (0);
while(true){
if((i__39634 < count__39633)){
var vec__39644 = chunk__39632.cljs$core$IIndexed$_nth$arity$2(null, i__39634);
var effect_key = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__39644,(0),null);
var effect_value = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__39644,(1),null);
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"db","db",993250759),effect_key)){
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: \":fx\" effect should not contain a :db effect"], 0));
} else {
}

var temp__5821__auto___39736 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,effect_key,false);
if(cljs.core.truth_(temp__5821__auto___39736)){
var effect_fn_39737 = temp__5821__auto___39736;
(effect_fn_39737.cljs$core$IFn$_invoke$arity$1 ? effect_fn_39737.cljs$core$IFn$_invoke$arity$1(effect_value) : effect_fn_39737.call(null, effect_value));
} else {
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: in \":fx\" effect found ",effect_key," which has no associated handler. Ignoring."], 0));
}


var G__39738 = seq__39631;
var G__39739 = chunk__39632;
var G__39740 = count__39633;
var G__39741 = (i__39634 + (1));
seq__39631 = G__39738;
chunk__39632 = G__39739;
count__39633 = G__39740;
i__39634 = G__39741;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__39631);
if(temp__5823__auto__){
var seq__39631__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__39631__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__39631__$1);
var G__39742 = cljs.core.chunk_rest(seq__39631__$1);
var G__39743 = c__5525__auto__;
var G__39744 = cljs.core.count(c__5525__auto__);
var G__39745 = (0);
seq__39631 = G__39742;
chunk__39632 = G__39743;
count__39633 = G__39744;
i__39634 = G__39745;
continue;
} else {
var vec__39647 = cljs.core.first(seq__39631__$1);
var effect_key = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__39647,(0),null);
var effect_value = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__39647,(1),null);
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"db","db",993250759),effect_key)){
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: \":fx\" effect should not contain a :db effect"], 0));
} else {
}

var temp__5821__auto___39747 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,effect_key,false);
if(cljs.core.truth_(temp__5821__auto___39747)){
var effect_fn_39749 = temp__5821__auto___39747;
(effect_fn_39749.cljs$core$IFn$_invoke$arity$1 ? effect_fn_39749.cljs$core$IFn$_invoke$arity$1(effect_value) : effect_fn_39749.call(null, effect_value));
} else {
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: in \":fx\" effect found ",effect_key," which has no associated handler. Ignoring."], 0));
}


var G__39750 = cljs.core.next(seq__39631__$1);
var G__39751 = null;
var G__39752 = (0);
var G__39753 = (0);
seq__39631 = G__39750;
chunk__39632 = G__39751;
count__39633 = G__39752;
i__39634 = G__39753;
continue;
}
} else {
return null;
}
}
break;
}
}
}));
re_frame.fx.reg_fx(new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),(function (value){
if((!(cljs.core.vector_QMARK_(value)))){
return re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"error","error",-978969032),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: ignoring bad :dispatch value. Expected a vector, but got:",value], 0));
} else {
return re_frame.router.dispatch(value);
}
}));
re_frame.fx.reg_fx(new cljs.core.Keyword(null,"dispatch-n","dispatch-n",-504469236),(function (value){
if((!(cljs.core.sequential_QMARK_(value)))){
return re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"error","error",-978969032),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: ignoring bad :dispatch-n value. Expected a collection, but got:",value], 0));
} else {
var seq__39659 = cljs.core.seq(cljs.core.remove.cljs$core$IFn$_invoke$arity$2(cljs.core.nil_QMARK_,value));
var chunk__39660 = null;
var count__39661 = (0);
var i__39662 = (0);
while(true){
if((i__39662 < count__39661)){
var event = chunk__39660.cljs$core$IIndexed$_nth$arity$2(null, i__39662);
re_frame.router.dispatch(event);


var G__39754 = seq__39659;
var G__39755 = chunk__39660;
var G__39756 = count__39661;
var G__39757 = (i__39662 + (1));
seq__39659 = G__39754;
chunk__39660 = G__39755;
count__39661 = G__39756;
i__39662 = G__39757;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__39659);
if(temp__5823__auto__){
var seq__39659__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__39659__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__39659__$1);
var G__39758 = cljs.core.chunk_rest(seq__39659__$1);
var G__39759 = c__5525__auto__;
var G__39760 = cljs.core.count(c__5525__auto__);
var G__39761 = (0);
seq__39659 = G__39758;
chunk__39660 = G__39759;
count__39661 = G__39760;
i__39662 = G__39761;
continue;
} else {
var event = cljs.core.first(seq__39659__$1);
re_frame.router.dispatch(event);


var G__39763 = cljs.core.next(seq__39659__$1);
var G__39764 = null;
var G__39765 = (0);
var G__39766 = (0);
seq__39659 = G__39763;
chunk__39660 = G__39764;
count__39661 = G__39765;
i__39662 = G__39766;
continue;
}
} else {
return null;
}
}
break;
}
}
}));
re_frame.fx.reg_fx(new cljs.core.Keyword(null,"deregister-event-handler","deregister-event-handler",-1096518994),(function (value){
var clear_event = cljs.core.partial.cljs$core$IFn$_invoke$arity$2(re_frame.registrar.clear_handlers,re_frame.events.kind);
if(cljs.core.sequential_QMARK_(value)){
var seq__39666 = cljs.core.seq(value);
var chunk__39667 = null;
var count__39668 = (0);
var i__39669 = (0);
while(true){
if((i__39669 < count__39668)){
var event = chunk__39667.cljs$core$IIndexed$_nth$arity$2(null, i__39669);
clear_event(event);


var G__39768 = seq__39666;
var G__39769 = chunk__39667;
var G__39770 = count__39668;
var G__39771 = (i__39669 + (1));
seq__39666 = G__39768;
chunk__39667 = G__39769;
count__39668 = G__39770;
i__39669 = G__39771;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__39666);
if(temp__5823__auto__){
var seq__39666__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__39666__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__39666__$1);
var G__39772 = cljs.core.chunk_rest(seq__39666__$1);
var G__39773 = c__5525__auto__;
var G__39774 = cljs.core.count(c__5525__auto__);
var G__39775 = (0);
seq__39666 = G__39772;
chunk__39667 = G__39773;
count__39668 = G__39774;
i__39669 = G__39775;
continue;
} else {
var event = cljs.core.first(seq__39666__$1);
clear_event(event);


var G__39776 = cljs.core.next(seq__39666__$1);
var G__39777 = null;
var G__39778 = (0);
var G__39779 = (0);
seq__39666 = G__39776;
chunk__39667 = G__39777;
count__39668 = G__39778;
i__39669 = G__39779;
continue;
}
} else {
return null;
}
}
break;
}
} else {
return clear_event(value);
}
}));
re_frame.fx.reg_fx(new cljs.core.Keyword(null,"db","db",993250759),(function (value){
if((!((cljs.core.deref(re_frame.db.app_db) === value)))){
return cljs.core.reset_BANG_(re_frame.db.app_db,value);
} else {
if(re_frame.trace.is_trace_enabled_QMARK_()){
var _STAR_current_trace_STAR__orig_val__39680 = re_frame.trace._STAR_current_trace_STAR_;
var _STAR_current_trace_STAR__temp_val__39681 = re_frame.trace.start_trace(new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"op-type","op-type",-1636141668),new cljs.core.Keyword("reagent","quiescent","reagent/quiescent",-16138681)], null));
(re_frame.trace._STAR_current_trace_STAR_ = _STAR_current_trace_STAR__temp_val__39681);

try{try{return null;
}finally {if(re_frame.trace.is_trace_enabled_QMARK_()){
var end__38903__auto___39782 = re_frame.interop.now();
var duration__38904__auto___39783 = (end__38903__auto___39782 - new cljs.core.Keyword(null,"start","start",-355208981).cljs$core$IFn$_invoke$arity$1(re_frame.trace._STAR_current_trace_STAR_));
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(re_frame.trace.traces,cljs.core.conj,cljs.core.assoc.cljs$core$IFn$_invoke$arity$variadic(re_frame.trace._STAR_current_trace_STAR_,new cljs.core.Keyword(null,"duration","duration",1444101068),duration__38904__auto___39783,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"end","end",-268185958),re_frame.interop.now()], 0)));

re_frame.trace.run_tracing_callbacks_BANG_(end__38903__auto___39782);
} else {
}
}}finally {(re_frame.trace._STAR_current_trace_STAR_ = _STAR_current_trace_STAR__orig_val__39680);
}} else {
return null;
}
}
}));

//# sourceMappingURL=re_frame.fx.js.map
