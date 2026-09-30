goog.provide('shadow.remote.runtime.tap_support');
shadow.remote.runtime.tap_support.tap_subscribe = (function shadow$remote$runtime$tap_support$tap_subscribe(p__37777,p__37778){
var map__37780 = p__37777;
var map__37780__$1 = cljs.core.__destructure_map(map__37780);
var svc = map__37780__$1;
var subs_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__37780__$1,new cljs.core.Keyword(null,"subs-ref","subs-ref",-1355989911));
var obj_support = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__37780__$1,new cljs.core.Keyword(null,"obj-support","obj-support",1522559229));
var runtime = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__37780__$1,new cljs.core.Keyword(null,"runtime","runtime",-1331573996));
var map__37781 = p__37778;
var map__37781__$1 = cljs.core.__destructure_map(map__37781);
var msg = map__37781__$1;
var from = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__37781__$1,new cljs.core.Keyword(null,"from","from",1815293044));
var summary = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__37781__$1,new cljs.core.Keyword(null,"summary","summary",380847952));
var history__$1 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__37781__$1,new cljs.core.Keyword(null,"history","history",-247395220));
var num = cljs.core.get.cljs$core$IFn$_invoke$arity$3(map__37781__$1,new cljs.core.Keyword(null,"num","num",1985240673),(10));
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(subs_ref,cljs.core.assoc,from,msg);

if(cljs.core.truth_(history__$1)){
return shadow.remote.runtime.shared.reply(runtime,msg,new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"tap-subscribed","tap-subscribed",-1882247432),new cljs.core.Keyword(null,"history","history",-247395220),cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentVector.EMPTY,cljs.core.map.cljs$core$IFn$_invoke$arity$2((function (oid){
return new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"oid","oid",-768692334),oid,new cljs.core.Keyword(null,"summary","summary",380847952),shadow.remote.runtime.obj_support.obj_describe_STAR_(obj_support,oid)], null);
}),shadow.remote.runtime.obj_support.get_tap_history(obj_support,num)))], null));
} else {
return null;
}
});
shadow.remote.runtime.tap_support.tap_unsubscribe = (function shadow$remote$runtime$tap_support$tap_unsubscribe(p__37794,p__37795){
var map__37796 = p__37794;
var map__37796__$1 = cljs.core.__destructure_map(map__37796);
var subs_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__37796__$1,new cljs.core.Keyword(null,"subs-ref","subs-ref",-1355989911));
var map__37797 = p__37795;
var map__37797__$1 = cljs.core.__destructure_map(map__37797);
var from = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__37797__$1,new cljs.core.Keyword(null,"from","from",1815293044));
return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(subs_ref,cljs.core.dissoc,from);
});
shadow.remote.runtime.tap_support.request_tap_history = (function shadow$remote$runtime$tap_support$request_tap_history(p__37801,p__37802){
var map__37803 = p__37801;
var map__37803__$1 = cljs.core.__destructure_map(map__37803);
var obj_support = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__37803__$1,new cljs.core.Keyword(null,"obj-support","obj-support",1522559229));
var runtime = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__37803__$1,new cljs.core.Keyword(null,"runtime","runtime",-1331573996));
var map__37804 = p__37802;
var map__37804__$1 = cljs.core.__destructure_map(map__37804);
var msg = map__37804__$1;
var num = cljs.core.get.cljs$core$IFn$_invoke$arity$3(map__37804__$1,new cljs.core.Keyword(null,"num","num",1985240673),(10));
var tap_ids = shadow.remote.runtime.obj_support.get_tap_history(obj_support,num);
return shadow.remote.runtime.shared.reply(runtime,msg,new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"tap-history","tap-history",-282803347),new cljs.core.Keyword(null,"oids","oids",-1580877688),tap_ids], null));
});
shadow.remote.runtime.tap_support.tool_disconnect = (function shadow$remote$runtime$tap_support$tool_disconnect(p__37813,tid){
var map__37814 = p__37813;
var map__37814__$1 = cljs.core.__destructure_map(map__37814);
var svc = map__37814__$1;
var subs_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__37814__$1,new cljs.core.Keyword(null,"subs-ref","subs-ref",-1355989911));
return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(subs_ref,cljs.core.dissoc,tid);
});
shadow.remote.runtime.tap_support.start = (function shadow$remote$runtime$tap_support$start(runtime,obj_support){
var subs_ref = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentArrayMap.EMPTY);
var tap_fn = (function shadow$remote$runtime$tap_support$start_$_runtime_tap(obj){
if((!((obj == null)))){
var oid = shadow.remote.runtime.obj_support.register(obj_support,obj,new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"from","from",1815293044),new cljs.core.Keyword(null,"tap","tap",-1086702463)], null));
var seq__37824 = cljs.core.seq(cljs.core.deref(subs_ref));
var chunk__37825 = null;
var count__37826 = (0);
var i__37827 = (0);
while(true){
if((i__37827 < count__37826)){
var vec__37910 = chunk__37825.cljs$core$IIndexed$_nth$arity$2(null, i__37827);
var tid = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__37910,(0),null);
var tap_config = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__37910,(1),null);
shadow.remote.runtime.api.relay_msg(runtime,new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"tap","tap",-1086702463),new cljs.core.Keyword(null,"to","to",192099007),tid,new cljs.core.Keyword(null,"oid","oid",-768692334),oid], null));


var G__37982 = seq__37824;
var G__37983 = chunk__37825;
var G__37984 = count__37826;
var G__37985 = (i__37827 + (1));
seq__37824 = G__37982;
chunk__37825 = G__37983;
count__37826 = G__37984;
i__37827 = G__37985;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__37824);
if(temp__5823__auto__){
var seq__37824__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__37824__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__37824__$1);
var G__37988 = cljs.core.chunk_rest(seq__37824__$1);
var G__37989 = c__5525__auto__;
var G__37990 = cljs.core.count(c__5525__auto__);
var G__37991 = (0);
seq__37824 = G__37988;
chunk__37825 = G__37989;
count__37826 = G__37990;
i__37827 = G__37991;
continue;
} else {
var vec__37916 = cljs.core.first(seq__37824__$1);
var tid = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__37916,(0),null);
var tap_config = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__37916,(1),null);
shadow.remote.runtime.api.relay_msg(runtime,new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"tap","tap",-1086702463),new cljs.core.Keyword(null,"to","to",192099007),tid,new cljs.core.Keyword(null,"oid","oid",-768692334),oid], null));


var G__37992 = cljs.core.next(seq__37824__$1);
var G__37993 = null;
var G__37994 = (0);
var G__37995 = (0);
seq__37824 = G__37992;
chunk__37825 = G__37993;
count__37826 = G__37994;
i__37827 = G__37995;
continue;
}
} else {
return null;
}
}
break;
}
} else {
return null;
}
});
var svc = new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword(null,"runtime","runtime",-1331573996),runtime,new cljs.core.Keyword(null,"obj-support","obj-support",1522559229),obj_support,new cljs.core.Keyword(null,"tap-fn","tap-fn",1573556461),tap_fn,new cljs.core.Keyword(null,"subs-ref","subs-ref",-1355989911),subs_ref], null);
shadow.remote.runtime.api.add_extension(runtime,new cljs.core.Keyword("shadow.remote.runtime.tap-support","ext","shadow.remote.runtime.tap-support/ext",1019069674),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"ops","ops",1237330063),new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"tap-subscribe","tap-subscribe",411179050),(function (p1__37816_SHARP_){
return shadow.remote.runtime.tap_support.tap_subscribe(svc,p1__37816_SHARP_);
}),new cljs.core.Keyword(null,"tap-unsubscribe","tap-unsubscribe",1183890755),(function (p1__37817_SHARP_){
return shadow.remote.runtime.tap_support.tap_unsubscribe(svc,p1__37817_SHARP_);
}),new cljs.core.Keyword(null,"request-tap-history","request-tap-history",-670837812),(function (p1__37818_SHARP_){
return shadow.remote.runtime.tap_support.request_tap_history(svc,p1__37818_SHARP_);
})], null),new cljs.core.Keyword(null,"on-tool-disconnect","on-tool-disconnect",693464366),(function (p1__37819_SHARP_){
return shadow.remote.runtime.tap_support.tool_disconnect(svc,p1__37819_SHARP_);
})], null));

cljs.core.add_tap(tap_fn);

return svc;
});
shadow.remote.runtime.tap_support.stop = (function shadow$remote$runtime$tap_support$stop(p__37931){
var map__37932 = p__37931;
var map__37932__$1 = cljs.core.__destructure_map(map__37932);
var svc = map__37932__$1;
var tap_fn = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__37932__$1,new cljs.core.Keyword(null,"tap-fn","tap-fn",1573556461));
var runtime = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__37932__$1,new cljs.core.Keyword(null,"runtime","runtime",-1331573996));
cljs.core.remove_tap(tap_fn);

return shadow.remote.runtime.api.del_extension(runtime,new cljs.core.Keyword("shadow.remote.runtime.tap-support","ext","shadow.remote.runtime.tap-support/ext",1019069674));
});

//# sourceMappingURL=shadow.remote.runtime.tap_support.js.map
