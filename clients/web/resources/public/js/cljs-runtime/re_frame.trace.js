goog.provide('re_frame.trace');
re_frame.trace.id = cljs.core.atom.cljs$core$IFn$_invoke$arity$1((0));
re_frame.trace._STAR_current_trace_STAR_ = null;
re_frame.trace.reset_tracing_BANG_ = (function re_frame$trace$reset_tracing_BANG_(){
return cljs.core.reset_BANG_(re_frame.trace.id,(0));
});
/**
 * @define {boolean}
 */
re_frame.trace.trace_enabled_QMARK_ = goog.define("re_frame.trace.trace_enabled_QMARK_",false);
/**
 * See https://groups.google.com/d/msg/clojurescript/jk43kmYiMhA/IHglVr_TPdgJ for more details
 */
re_frame.trace.is_trace_enabled_QMARK_ = (function re_frame$trace$is_trace_enabled_QMARK_(){
return re_frame.trace.trace_enabled_QMARK_;
});
re_frame.trace.trace_cbs = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentArrayMap.EMPTY);
if((typeof re_frame !== 'undefined') && (typeof re_frame.trace !== 'undefined') && (typeof re_frame.trace.traces !== 'undefined')){
} else {
re_frame.trace.traces = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentVector.EMPTY);
}
if((typeof re_frame !== 'undefined') && (typeof re_frame.trace !== 'undefined') && (typeof re_frame.trace.next_delivery !== 'undefined')){
} else {
re_frame.trace.next_delivery = cljs.core.atom.cljs$core$IFn$_invoke$arity$1((0));
}
/**
 * Registers a tracing callback function which will receive a collection of one or more traces.
 *   Will replace an existing callback function if it shares the same key.
 */
re_frame.trace.register_trace_cb = (function re_frame$trace$register_trace_cb(key,f){
if(re_frame.trace.trace_enabled_QMARK_){
return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(re_frame.trace.trace_cbs,cljs.core.assoc,key,f);
} else {
return re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["Tracing is not enabled. Please set {\"re_frame.trace.trace_enabled_QMARK_\" true} in :closure-defines. See: https://github.com/day8/re-frame-10x#installation."], 0));
}
});
re_frame.trace.remove_trace_cb = (function re_frame$trace$remove_trace_cb(key){
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(re_frame.trace.trace_cbs,cljs.core.dissoc,key);

return null;
});
re_frame.trace.next_id = (function re_frame$trace$next_id(){
return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$2(re_frame.trace.id,cljs.core.inc);
});
re_frame.trace.start_trace = (function re_frame$trace$start_trace(p__38930){
var map__38931 = p__38930;
var map__38931__$1 = cljs.core.__destructure_map(map__38931);
var operation = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__38931__$1,new cljs.core.Keyword(null,"operation","operation",-1267664310));
var op_type = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__38931__$1,new cljs.core.Keyword(null,"op-type","op-type",-1636141668));
var tags = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__38931__$1,new cljs.core.Keyword(null,"tags","tags",1771418977));
var child_of = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__38931__$1,new cljs.core.Keyword(null,"child-of","child-of",-903376662));
return new cljs.core.PersistentArrayMap(null, 6, [new cljs.core.Keyword(null,"id","id",-1388402092),re_frame.trace.next_id(),new cljs.core.Keyword(null,"operation","operation",-1267664310),operation,new cljs.core.Keyword(null,"op-type","op-type",-1636141668),op_type,new cljs.core.Keyword(null,"tags","tags",1771418977),tags,new cljs.core.Keyword(null,"child-of","child-of",-903376662),(function (){var or__5002__auto__ = child_of;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return new cljs.core.Keyword(null,"id","id",-1388402092).cljs$core$IFn$_invoke$arity$1(re_frame.trace._STAR_current_trace_STAR_);
}
})(),new cljs.core.Keyword(null,"start","start",-355208981),re_frame.interop.now()], null);
});
re_frame.trace.debounce_time = (50);
re_frame.trace.debounce = (function re_frame$trace$debounce(f,interval){
return goog.functions.debounce(f,interval);
});
re_frame.trace.schedule_debounce = re_frame.trace.debounce((function re_frame$trace$tracing_cb_debounced(){
var seq__38934_38985 = cljs.core.seq(cljs.core.deref(re_frame.trace.trace_cbs));
var chunk__38935_38986 = null;
var count__38936_38987 = (0);
var i__38937_38988 = (0);
while(true){
if((i__38937_38988 < count__38936_38987)){
var vec__38951_38989 = chunk__38935_38986.cljs$core$IIndexed$_nth$arity$2(null, i__38937_38988);
var k_38990 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__38951_38989,(0),null);
var cb_38991 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__38951_38989,(1),null);
try{var G__38955_38992 = cljs.core.deref(re_frame.trace.traces);
(cb_38991.cljs$core$IFn$_invoke$arity$1 ? cb_38991.cljs$core$IFn$_invoke$arity$1(G__38955_38992) : cb_38991.call(null, G__38955_38992));
}catch (e38954){var e_38993 = e38954;
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"error","error",-978969032),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["Error thrown from trace cb",k_38990,"while storing",cljs.core.deref(re_frame.trace.traces),e_38993], 0));
}

var G__38994 = seq__38934_38985;
var G__38995 = chunk__38935_38986;
var G__38996 = count__38936_38987;
var G__38997 = (i__38937_38988 + (1));
seq__38934_38985 = G__38994;
chunk__38935_38986 = G__38995;
count__38936_38987 = G__38996;
i__38937_38988 = G__38997;
continue;
} else {
var temp__5823__auto___38998 = cljs.core.seq(seq__38934_38985);
if(temp__5823__auto___38998){
var seq__38934_38999__$1 = temp__5823__auto___38998;
if(cljs.core.chunked_seq_QMARK_(seq__38934_38999__$1)){
var c__5525__auto___39000 = cljs.core.chunk_first(seq__38934_38999__$1);
var G__39001 = cljs.core.chunk_rest(seq__38934_38999__$1);
var G__39002 = c__5525__auto___39000;
var G__39003 = cljs.core.count(c__5525__auto___39000);
var G__39004 = (0);
seq__38934_38985 = G__39001;
chunk__38935_38986 = G__39002;
count__38936_38987 = G__39003;
i__38937_38988 = G__39004;
continue;
} else {
var vec__38956_39005 = cljs.core.first(seq__38934_38999__$1);
var k_39006 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__38956_39005,(0),null);
var cb_39007 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__38956_39005,(1),null);
try{var G__38963_39008 = cljs.core.deref(re_frame.trace.traces);
(cb_39007.cljs$core$IFn$_invoke$arity$1 ? cb_39007.cljs$core$IFn$_invoke$arity$1(G__38963_39008) : cb_39007.call(null, G__38963_39008));
}catch (e38962){var e_39009 = e38962;
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"error","error",-978969032),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["Error thrown from trace cb",k_39006,"while storing",cljs.core.deref(re_frame.trace.traces),e_39009], 0));
}

var G__39010 = cljs.core.next(seq__38934_38999__$1);
var G__39011 = null;
var G__39012 = (0);
var G__39013 = (0);
seq__38934_38985 = G__39010;
chunk__38935_38986 = G__39011;
count__38936_38987 = G__39012;
i__38937_38988 = G__39013;
continue;
}
} else {
}
}
break;
}

return cljs.core.reset_BANG_(re_frame.trace.traces,cljs.core.PersistentVector.EMPTY);
}),re_frame.trace.debounce_time);
re_frame.trace.run_tracing_callbacks_BANG_ = (function re_frame$trace$run_tracing_callbacks_BANG_(now){
if(((cljs.core.deref(re_frame.trace.next_delivery) - (25)) < now)){
(re_frame.trace.schedule_debounce.cljs$core$IFn$_invoke$arity$0 ? re_frame.trace.schedule_debounce.cljs$core$IFn$_invoke$arity$0() : re_frame.trace.schedule_debounce.call(null, ));

return cljs.core.reset_BANG_(re_frame.trace.next_delivery,(now + re_frame.trace.debounce_time));
} else {
return null;
}
});

//# sourceMappingURL=re_frame.trace.js.map
