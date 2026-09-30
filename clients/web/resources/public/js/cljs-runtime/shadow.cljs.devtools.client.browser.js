goog.provide('shadow.cljs.devtools.client.browser');
shadow.cljs.devtools.client.browser.devtools_msg = (function shadow$cljs$devtools$client$browser$devtools_msg(var_args){
var args__5732__auto__ = [];
var len__5726__auto___39802 = arguments.length;
var i__5727__auto___39803 = (0);
while(true){
if((i__5727__auto___39803 < len__5726__auto___39802)){
args__5732__auto__.push((arguments[i__5727__auto___39803]));

var G__39804 = (i__5727__auto___39803 + (1));
i__5727__auto___39803 = G__39804;
continue;
} else {
}
break;
}

var argseq__5733__auto__ = ((((1) < args__5732__auto__.length))?(new cljs.core.IndexedSeq(args__5732__auto__.slice((1)),(0),null)):null);
return shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic((arguments[(0)]),argseq__5733__auto__);
});

(shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic = (function (msg,args){
if(shadow.cljs.devtools.client.env.log){
if(cljs.core.seq(shadow.cljs.devtools.client.env.log_style)){
return console.log.apply(console,cljs.core.into_array.cljs$core$IFn$_invoke$arity$1(cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [["%cshadow-cljs: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(msg)].join(''),shadow.cljs.devtools.client.env.log_style], null),args)));
} else {
return console.log.apply(console,cljs.core.into_array.cljs$core$IFn$_invoke$arity$1(cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [["shadow-cljs: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(msg)].join('')], null),args)));
}
} else {
return null;
}
}));

(shadow.cljs.devtools.client.browser.devtools_msg.cljs$lang$maxFixedArity = (1));

/** @this {Function} */
(shadow.cljs.devtools.client.browser.devtools_msg.cljs$lang$applyTo = (function (seq39113){
var G__39114 = cljs.core.first(seq39113);
var seq39113__$1 = cljs.core.next(seq39113);
var self__5711__auto__ = this;
return self__5711__auto__.cljs$core$IFn$_invoke$arity$variadic(G__39114,seq39113__$1);
}));

shadow.cljs.devtools.client.browser.script_eval = (function shadow$cljs$devtools$client$browser$script_eval(code){
return goog.globalEval(code);
});
shadow.cljs.devtools.client.browser.do_js_load = (function shadow$cljs$devtools$client$browser$do_js_load(sources){
var seq__39129 = cljs.core.seq(sources);
var chunk__39130 = null;
var count__39131 = (0);
var i__39132 = (0);
while(true){
if((i__39132 < count__39131)){
var map__39152 = chunk__39130.cljs$core$IIndexed$_nth$arity$2(null, i__39132);
var map__39152__$1 = cljs.core.__destructure_map(map__39152);
var src = map__39152__$1;
var resource_id = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39152__$1,new cljs.core.Keyword(null,"resource-id","resource-id",-1308422582));
var output_name = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39152__$1,new cljs.core.Keyword(null,"output-name","output-name",-1769107767));
var resource_name = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39152__$1,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100));
var js = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39152__$1,new cljs.core.Keyword(null,"js","js",1768080579));
$CLJS.SHADOW_ENV.setLoaded(output_name);

shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("load JS",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([resource_name], 0));

shadow.cljs.devtools.client.env.before_load_src(src);

try{shadow.cljs.devtools.client.browser.script_eval([cljs.core.str.cljs$core$IFn$_invoke$arity$1(js),"\n//# sourceURL=",cljs.core.str.cljs$core$IFn$_invoke$arity$1($CLJS.SHADOW_ENV.scriptBase),cljs.core.str.cljs$core$IFn$_invoke$arity$1(output_name)].join(''));
}catch (e39158){var e_39805 = e39158;
if(shadow.cljs.devtools.client.env.log){
console.error(["Failed to load ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(resource_name)].join(''),e_39805);
} else {
}

throw (new Error(["Failed to load ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(resource_name),": ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e_39805.message)].join('')));
}

var G__39806 = seq__39129;
var G__39807 = chunk__39130;
var G__39808 = count__39131;
var G__39809 = (i__39132 + (1));
seq__39129 = G__39806;
chunk__39130 = G__39807;
count__39131 = G__39808;
i__39132 = G__39809;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__39129);
if(temp__5823__auto__){
var seq__39129__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__39129__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__39129__$1);
var G__39811 = cljs.core.chunk_rest(seq__39129__$1);
var G__39812 = c__5525__auto__;
var G__39813 = cljs.core.count(c__5525__auto__);
var G__39814 = (0);
seq__39129 = G__39811;
chunk__39130 = G__39812;
count__39131 = G__39813;
i__39132 = G__39814;
continue;
} else {
var map__39164 = cljs.core.first(seq__39129__$1);
var map__39164__$1 = cljs.core.__destructure_map(map__39164);
var src = map__39164__$1;
var resource_id = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39164__$1,new cljs.core.Keyword(null,"resource-id","resource-id",-1308422582));
var output_name = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39164__$1,new cljs.core.Keyword(null,"output-name","output-name",-1769107767));
var resource_name = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39164__$1,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100));
var js = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39164__$1,new cljs.core.Keyword(null,"js","js",1768080579));
$CLJS.SHADOW_ENV.setLoaded(output_name);

shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("load JS",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([resource_name], 0));

shadow.cljs.devtools.client.env.before_load_src(src);

try{shadow.cljs.devtools.client.browser.script_eval([cljs.core.str.cljs$core$IFn$_invoke$arity$1(js),"\n//# sourceURL=",cljs.core.str.cljs$core$IFn$_invoke$arity$1($CLJS.SHADOW_ENV.scriptBase),cljs.core.str.cljs$core$IFn$_invoke$arity$1(output_name)].join(''));
}catch (e39169){var e_39816 = e39169;
if(shadow.cljs.devtools.client.env.log){
console.error(["Failed to load ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(resource_name)].join(''),e_39816);
} else {
}

throw (new Error(["Failed to load ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(resource_name),": ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(e_39816.message)].join('')));
}

var G__39817 = cljs.core.next(seq__39129__$1);
var G__39818 = null;
var G__39819 = (0);
var G__39820 = (0);
seq__39129 = G__39817;
chunk__39130 = G__39818;
count__39131 = G__39819;
i__39132 = G__39820;
continue;
}
} else {
return null;
}
}
break;
}
});
shadow.cljs.devtools.client.browser.do_js_reload = (function shadow$cljs$devtools$client$browser$do_js_reload(msg,sources,complete_fn,failure_fn){
return shadow.cljs.devtools.client.env.do_js_reload.cljs$core$IFn$_invoke$arity$4(cljs.core.assoc.cljs$core$IFn$_invoke$arity$variadic(msg,new cljs.core.Keyword(null,"log-missing-fn","log-missing-fn",732676765),(function (fn_sym){
return null;
}),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"log-call-async","log-call-async",183826192),(function (fn_sym){
return shadow.cljs.devtools.client.browser.devtools_msg(["call async ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym)].join(''));
}),new cljs.core.Keyword(null,"log-call","log-call",412404391),(function (fn_sym){
return shadow.cljs.devtools.client.browser.devtools_msg(["call ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym)].join(''));
})], 0)),(function (){
return shadow.cljs.devtools.client.browser.do_js_load(sources);
}),complete_fn,failure_fn);
});
/**
 * when (require '["some-str" :as x]) is done at the REPL we need to manually call the shadow.js.require for it
 * since the file only adds the shadow$provide. only need to do this for shadow-js.
 */
shadow.cljs.devtools.client.browser.do_js_requires = (function shadow$cljs$devtools$client$browser$do_js_requires(js_requires){
var seq__39186 = cljs.core.seq(js_requires);
var chunk__39187 = null;
var count__39188 = (0);
var i__39189 = (0);
while(true){
if((i__39189 < count__39188)){
var js_ns = chunk__39187.cljs$core$IIndexed$_nth$arity$2(null, i__39189);
var require_str_39821 = ["var ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(js_ns)," = shadow.js.require(\"",cljs.core.str.cljs$core$IFn$_invoke$arity$1(js_ns),"\");"].join('');
shadow.cljs.devtools.client.browser.script_eval(require_str_39821);


var G__39822 = seq__39186;
var G__39823 = chunk__39187;
var G__39824 = count__39188;
var G__39825 = (i__39189 + (1));
seq__39186 = G__39822;
chunk__39187 = G__39823;
count__39188 = G__39824;
i__39189 = G__39825;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__39186);
if(temp__5823__auto__){
var seq__39186__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__39186__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__39186__$1);
var G__39826 = cljs.core.chunk_rest(seq__39186__$1);
var G__39827 = c__5525__auto__;
var G__39828 = cljs.core.count(c__5525__auto__);
var G__39829 = (0);
seq__39186 = G__39826;
chunk__39187 = G__39827;
count__39188 = G__39828;
i__39189 = G__39829;
continue;
} else {
var js_ns = cljs.core.first(seq__39186__$1);
var require_str_39830 = ["var ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(js_ns)," = shadow.js.require(\"",cljs.core.str.cljs$core$IFn$_invoke$arity$1(js_ns),"\");"].join('');
shadow.cljs.devtools.client.browser.script_eval(require_str_39830);


var G__39831 = cljs.core.next(seq__39186__$1);
var G__39832 = null;
var G__39833 = (0);
var G__39834 = (0);
seq__39186 = G__39831;
chunk__39187 = G__39832;
count__39188 = G__39833;
i__39189 = G__39834;
continue;
}
} else {
return null;
}
}
break;
}
});
shadow.cljs.devtools.client.browser.handle_build_complete = (function shadow$cljs$devtools$client$browser$handle_build_complete(runtime,p__39196){
var map__39197 = p__39196;
var map__39197__$1 = cljs.core.__destructure_map(map__39197);
var msg = map__39197__$1;
var info = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39197__$1,new cljs.core.Keyword(null,"info","info",-317069002));
var reload_info = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39197__$1,new cljs.core.Keyword(null,"reload-info","reload-info",1648088086));
var warnings = cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentVector.EMPTY,cljs.core.distinct.cljs$core$IFn$_invoke$arity$1((function (){var iter__5480__auto__ = (function shadow$cljs$devtools$client$browser$handle_build_complete_$_iter__39202(s__39203){
return (new cljs.core.LazySeq(null,(function (){
var s__39203__$1 = s__39203;
while(true){
var temp__5823__auto__ = cljs.core.seq(s__39203__$1);
if(temp__5823__auto__){
var xs__6383__auto__ = temp__5823__auto__;
var map__39229 = cljs.core.first(xs__6383__auto__);
var map__39229__$1 = cljs.core.__destructure_map(map__39229);
var src = map__39229__$1;
var resource_name = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39229__$1,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100));
var warnings = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39229__$1,new cljs.core.Keyword(null,"warnings","warnings",-735437651));
if(cljs.core.not(new cljs.core.Keyword(null,"from-jar","from-jar",1050932827).cljs$core$IFn$_invoke$arity$1(src))){
var iterys__5476__auto__ = ((function (s__39203__$1,map__39229,map__39229__$1,src,resource_name,warnings,xs__6383__auto__,temp__5823__auto__,map__39197,map__39197__$1,msg,info,reload_info){
return (function shadow$cljs$devtools$client$browser$handle_build_complete_$_iter__39202_$_iter__39204(s__39205){
return (new cljs.core.LazySeq(null,((function (s__39203__$1,map__39229,map__39229__$1,src,resource_name,warnings,xs__6383__auto__,temp__5823__auto__,map__39197,map__39197__$1,msg,info,reload_info){
return (function (){
var s__39205__$1 = s__39205;
while(true){
var temp__5823__auto____$1 = cljs.core.seq(s__39205__$1);
if(temp__5823__auto____$1){
var s__39205__$2 = temp__5823__auto____$1;
if(cljs.core.chunked_seq_QMARK_(s__39205__$2)){
var c__5478__auto__ = cljs.core.chunk_first(s__39205__$2);
var size__5479__auto__ = cljs.core.count(c__5478__auto__);
var b__39207 = cljs.core.chunk_buffer(size__5479__auto__);
if((function (){var i__39206 = (0);
while(true){
if((i__39206 < size__5479__auto__)){
var warning = cljs.core._nth(c__5478__auto__,i__39206);
cljs.core.chunk_append(b__39207,cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(warning,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100),resource_name));

var G__39836 = (i__39206 + (1));
i__39206 = G__39836;
continue;
} else {
return true;
}
break;
}
})()){
return cljs.core.chunk_cons(cljs.core.chunk(b__39207),shadow$cljs$devtools$client$browser$handle_build_complete_$_iter__39202_$_iter__39204(cljs.core.chunk_rest(s__39205__$2)));
} else {
return cljs.core.chunk_cons(cljs.core.chunk(b__39207),null);
}
} else {
var warning = cljs.core.first(s__39205__$2);
return cljs.core.cons(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(warning,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100),resource_name),shadow$cljs$devtools$client$browser$handle_build_complete_$_iter__39202_$_iter__39204(cljs.core.rest(s__39205__$2)));
}
} else {
return null;
}
break;
}
});})(s__39203__$1,map__39229,map__39229__$1,src,resource_name,warnings,xs__6383__auto__,temp__5823__auto__,map__39197,map__39197__$1,msg,info,reload_info))
,null,null));
});})(s__39203__$1,map__39229,map__39229__$1,src,resource_name,warnings,xs__6383__auto__,temp__5823__auto__,map__39197,map__39197__$1,msg,info,reload_info))
;
var fs__5477__auto__ = cljs.core.seq(iterys__5476__auto__(warnings));
if(fs__5477__auto__){
return cljs.core.concat.cljs$core$IFn$_invoke$arity$2(fs__5477__auto__,shadow$cljs$devtools$client$browser$handle_build_complete_$_iter__39202(cljs.core.rest(s__39203__$1)));
} else {
var G__39838 = cljs.core.rest(s__39203__$1);
s__39203__$1 = G__39838;
continue;
}
} else {
var G__39839 = cljs.core.rest(s__39203__$1);
s__39203__$1 = G__39839;
continue;
}
} else {
return null;
}
break;
}
}),null,null));
});
return iter__5480__auto__(new cljs.core.Keyword(null,"sources","sources",-321166424).cljs$core$IFn$_invoke$arity$1(info));
})()));
if(shadow.cljs.devtools.client.env.log){
var seq__39235_39840 = cljs.core.seq(warnings);
var chunk__39236_39841 = null;
var count__39237_39842 = (0);
var i__39238_39843 = (0);
while(true){
if((i__39238_39843 < count__39237_39842)){
var map__39247_39844 = chunk__39236_39841.cljs$core$IIndexed$_nth$arity$2(null, i__39238_39843);
var map__39247_39845__$1 = cljs.core.__destructure_map(map__39247_39844);
var w_39846 = map__39247_39845__$1;
var msg_39847__$1 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39247_39845__$1,new cljs.core.Keyword(null,"msg","msg",-1386103444));
var line_39848 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39247_39845__$1,new cljs.core.Keyword(null,"line","line",212345235));
var column_39849 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39247_39845__$1,new cljs.core.Keyword(null,"column","column",2078222095));
var resource_name_39850 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39247_39845__$1,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100));
console.warn(["BUILD-WARNING in ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(resource_name_39850)," at [",cljs.core.str.cljs$core$IFn$_invoke$arity$1(line_39848),":",cljs.core.str.cljs$core$IFn$_invoke$arity$1(column_39849),"]\n\t",cljs.core.str.cljs$core$IFn$_invoke$arity$1(msg_39847__$1)].join(''));


var G__39851 = seq__39235_39840;
var G__39852 = chunk__39236_39841;
var G__39853 = count__39237_39842;
var G__39854 = (i__39238_39843 + (1));
seq__39235_39840 = G__39851;
chunk__39236_39841 = G__39852;
count__39237_39842 = G__39853;
i__39238_39843 = G__39854;
continue;
} else {
var temp__5823__auto___39855 = cljs.core.seq(seq__39235_39840);
if(temp__5823__auto___39855){
var seq__39235_39856__$1 = temp__5823__auto___39855;
if(cljs.core.chunked_seq_QMARK_(seq__39235_39856__$1)){
var c__5525__auto___39857 = cljs.core.chunk_first(seq__39235_39856__$1);
var G__39858 = cljs.core.chunk_rest(seq__39235_39856__$1);
var G__39859 = c__5525__auto___39857;
var G__39860 = cljs.core.count(c__5525__auto___39857);
var G__39861 = (0);
seq__39235_39840 = G__39858;
chunk__39236_39841 = G__39859;
count__39237_39842 = G__39860;
i__39238_39843 = G__39861;
continue;
} else {
var map__39251_39862 = cljs.core.first(seq__39235_39856__$1);
var map__39251_39863__$1 = cljs.core.__destructure_map(map__39251_39862);
var w_39864 = map__39251_39863__$1;
var msg_39865__$1 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39251_39863__$1,new cljs.core.Keyword(null,"msg","msg",-1386103444));
var line_39866 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39251_39863__$1,new cljs.core.Keyword(null,"line","line",212345235));
var column_39867 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39251_39863__$1,new cljs.core.Keyword(null,"column","column",2078222095));
var resource_name_39868 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39251_39863__$1,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100));
console.warn(["BUILD-WARNING in ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(resource_name_39868)," at [",cljs.core.str.cljs$core$IFn$_invoke$arity$1(line_39866),":",cljs.core.str.cljs$core$IFn$_invoke$arity$1(column_39867),"]\n\t",cljs.core.str.cljs$core$IFn$_invoke$arity$1(msg_39865__$1)].join(''));


var G__39869 = cljs.core.next(seq__39235_39856__$1);
var G__39870 = null;
var G__39871 = (0);
var G__39872 = (0);
seq__39235_39840 = G__39869;
chunk__39236_39841 = G__39870;
count__39237_39842 = G__39871;
i__39238_39843 = G__39872;
continue;
}
} else {
}
}
break;
}
} else {
}

if((!(shadow.cljs.devtools.client.env.autoload))){
return shadow.cljs.devtools.client.hud.load_end_success();
} else {
if(((cljs.core.empty_QMARK_(warnings)) || (shadow.cljs.devtools.client.env.ignore_warnings))){
var sources_to_get = shadow.cljs.devtools.client.env.filter_reload_sources(info,reload_info);
if(cljs.core.not(cljs.core.seq(sources_to_get))){
return shadow.cljs.devtools.client.hud.load_end_success();
} else {
if(cljs.core.seq(cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(msg,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"reload-info","reload-info",1648088086),new cljs.core.Keyword(null,"after-load","after-load",-1278503285)], null)))){
} else {
shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("reloading code but no :after-load hooks are configured!",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["https://shadow-cljs.github.io/docs/UsersGuide.html#_lifecycle_hooks"], 0));
}

return shadow.cljs.devtools.client.shared.load_sources(runtime,sources_to_get,(function (p1__39195_SHARP_){
return shadow.cljs.devtools.client.browser.do_js_reload(msg,p1__39195_SHARP_,shadow.cljs.devtools.client.hud.load_end_success,shadow.cljs.devtools.client.hud.load_failure);
}));
}
} else {
return null;
}
}
});
shadow.cljs.devtools.client.browser.page_load_uri = (cljs.core.truth_(goog.global.document)?goog.Uri.parse(document.location.href):null);
shadow.cljs.devtools.client.browser.match_paths = (function shadow$cljs$devtools$client$browser$match_paths(old,new$){
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2("file",shadow.cljs.devtools.client.browser.page_load_uri.getScheme())){
var rel_new = cljs.core.subs.cljs$core$IFn$_invoke$arity$2(new$,(1));
if(((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(old,rel_new)) || (clojure.string.starts_with_QMARK_(old,[rel_new,"?"].join(''))))){
return rel_new;
} else {
return null;
}
} else {
var node_uri = goog.Uri.parse(old);
var node_uri_resolved = shadow.cljs.devtools.client.browser.page_load_uri.resolve(node_uri);
var node_abs = node_uri_resolved.getPath();
var and__5000__auto__ = ((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$1(shadow.cljs.devtools.client.browser.page_load_uri.hasSameDomainAs(node_uri))) || (cljs.core.not(node_uri.hasDomain())));
if(and__5000__auto__){
var and__5000__auto____$1 = cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(node_abs,new$);
if(and__5000__auto____$1){
return new$;
} else {
return and__5000__auto____$1;
}
} else {
return and__5000__auto__;
}
}
});
shadow.cljs.devtools.client.browser.handle_asset_update = (function shadow$cljs$devtools$client$browser$handle_asset_update(p__39268){
var map__39269 = p__39268;
var map__39269__$1 = cljs.core.__destructure_map(map__39269);
var msg = map__39269__$1;
var updates = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39269__$1,new cljs.core.Keyword(null,"updates","updates",2013983452));
var reload_info = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39269__$1,new cljs.core.Keyword(null,"reload-info","reload-info",1648088086));
var seq__39273 = cljs.core.seq(updates);
var chunk__39275 = null;
var count__39276 = (0);
var i__39277 = (0);
while(true){
if((i__39277 < count__39276)){
var path = chunk__39275.cljs$core$IIndexed$_nth$arity$2(null, i__39277);
if(clojure.string.ends_with_QMARK_(path,"css")){
var seq__39470_39875 = cljs.core.seq(cljs.core.array_seq.cljs$core$IFn$_invoke$arity$1(document.querySelectorAll("link[rel=\"stylesheet\"]")));
var chunk__39474_39876 = null;
var count__39475_39877 = (0);
var i__39476_39878 = (0);
while(true){
if((i__39476_39878 < count__39475_39877)){
var node_39879 = chunk__39474_39876.cljs$core$IIndexed$_nth$arity$2(null, i__39476_39878);
if(cljs.core.not(node_39879.shadow$old)){
var path_match_39880 = shadow.cljs.devtools.client.browser.match_paths(node_39879.getAttribute("href"),path);
if(cljs.core.truth_(path_match_39880)){
var new_link_39881 = (function (){var G__39508 = node_39879.cloneNode(true);
G__39508.setAttribute("href",[cljs.core.str.cljs$core$IFn$_invoke$arity$1(path_match_39880),"?r=",cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.rand.cljs$core$IFn$_invoke$arity$0())].join(''));

return G__39508;
})();
(node_39879.shadow$old = true);

(new_link_39881.onload = ((function (seq__39470_39875,chunk__39474_39876,count__39475_39877,i__39476_39878,seq__39273,chunk__39275,count__39276,i__39277,new_link_39881,path_match_39880,node_39879,path,map__39269,map__39269__$1,msg,updates,reload_info){
return (function (e){
var seq__39510_39882 = cljs.core.seq(cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(msg,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"reload-info","reload-info",1648088086),new cljs.core.Keyword(null,"asset-load","asset-load",-1925902322)], null)));
var chunk__39512_39883 = null;
var count__39513_39884 = (0);
var i__39514_39885 = (0);
while(true){
if((i__39514_39885 < count__39513_39884)){
var map__39523_39886 = chunk__39512_39883.cljs$core$IIndexed$_nth$arity$2(null, i__39514_39885);
var map__39523_39887__$1 = cljs.core.__destructure_map(map__39523_39886);
var task_39888 = map__39523_39887__$1;
var fn_str_39889 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39523_39887__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_39890 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39523_39887__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_39891 = goog.getObjectByName(fn_str_39889,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg(["call ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_39890)].join(''));

(fn_obj_39891.cljs$core$IFn$_invoke$arity$2 ? fn_obj_39891.cljs$core$IFn$_invoke$arity$2(path,new_link_39881) : fn_obj_39891.call(null, path,new_link_39881));


var G__39892 = seq__39510_39882;
var G__39893 = chunk__39512_39883;
var G__39894 = count__39513_39884;
var G__39895 = (i__39514_39885 + (1));
seq__39510_39882 = G__39892;
chunk__39512_39883 = G__39893;
count__39513_39884 = G__39894;
i__39514_39885 = G__39895;
continue;
} else {
var temp__5823__auto___39896 = cljs.core.seq(seq__39510_39882);
if(temp__5823__auto___39896){
var seq__39510_39897__$1 = temp__5823__auto___39896;
if(cljs.core.chunked_seq_QMARK_(seq__39510_39897__$1)){
var c__5525__auto___39898 = cljs.core.chunk_first(seq__39510_39897__$1);
var G__39899 = cljs.core.chunk_rest(seq__39510_39897__$1);
var G__39900 = c__5525__auto___39898;
var G__39901 = cljs.core.count(c__5525__auto___39898);
var G__39902 = (0);
seq__39510_39882 = G__39899;
chunk__39512_39883 = G__39900;
count__39513_39884 = G__39901;
i__39514_39885 = G__39902;
continue;
} else {
var map__39530_39903 = cljs.core.first(seq__39510_39897__$1);
var map__39530_39904__$1 = cljs.core.__destructure_map(map__39530_39903);
var task_39905 = map__39530_39904__$1;
var fn_str_39906 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39530_39904__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_39907 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39530_39904__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_39908 = goog.getObjectByName(fn_str_39906,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg(["call ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_39907)].join(''));

(fn_obj_39908.cljs$core$IFn$_invoke$arity$2 ? fn_obj_39908.cljs$core$IFn$_invoke$arity$2(path,new_link_39881) : fn_obj_39908.call(null, path,new_link_39881));


var G__39909 = cljs.core.next(seq__39510_39897__$1);
var G__39910 = null;
var G__39911 = (0);
var G__39912 = (0);
seq__39510_39882 = G__39909;
chunk__39512_39883 = G__39910;
count__39513_39884 = G__39911;
i__39514_39885 = G__39912;
continue;
}
} else {
}
}
break;
}

return goog.dom.removeNode(node_39879);
});})(seq__39470_39875,chunk__39474_39876,count__39475_39877,i__39476_39878,seq__39273,chunk__39275,count__39276,i__39277,new_link_39881,path_match_39880,node_39879,path,map__39269,map__39269__$1,msg,updates,reload_info))
);

shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("load CSS",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([path_match_39880], 0));

goog.dom.insertSiblingAfter(new_link_39881,node_39879);


var G__39913 = seq__39470_39875;
var G__39914 = chunk__39474_39876;
var G__39915 = count__39475_39877;
var G__39916 = (i__39476_39878 + (1));
seq__39470_39875 = G__39913;
chunk__39474_39876 = G__39914;
count__39475_39877 = G__39915;
i__39476_39878 = G__39916;
continue;
} else {
var G__39917 = seq__39470_39875;
var G__39918 = chunk__39474_39876;
var G__39919 = count__39475_39877;
var G__39920 = (i__39476_39878 + (1));
seq__39470_39875 = G__39917;
chunk__39474_39876 = G__39918;
count__39475_39877 = G__39919;
i__39476_39878 = G__39920;
continue;
}
} else {
var G__39921 = seq__39470_39875;
var G__39922 = chunk__39474_39876;
var G__39923 = count__39475_39877;
var G__39924 = (i__39476_39878 + (1));
seq__39470_39875 = G__39921;
chunk__39474_39876 = G__39922;
count__39475_39877 = G__39923;
i__39476_39878 = G__39924;
continue;
}
} else {
var temp__5823__auto___39925 = cljs.core.seq(seq__39470_39875);
if(temp__5823__auto___39925){
var seq__39470_39926__$1 = temp__5823__auto___39925;
if(cljs.core.chunked_seq_QMARK_(seq__39470_39926__$1)){
var c__5525__auto___39927 = cljs.core.chunk_first(seq__39470_39926__$1);
var G__39928 = cljs.core.chunk_rest(seq__39470_39926__$1);
var G__39929 = c__5525__auto___39927;
var G__39930 = cljs.core.count(c__5525__auto___39927);
var G__39931 = (0);
seq__39470_39875 = G__39928;
chunk__39474_39876 = G__39929;
count__39475_39877 = G__39930;
i__39476_39878 = G__39931;
continue;
} else {
var node_39932 = cljs.core.first(seq__39470_39926__$1);
if(cljs.core.not(node_39932.shadow$old)){
var path_match_39933 = shadow.cljs.devtools.client.browser.match_paths(node_39932.getAttribute("href"),path);
if(cljs.core.truth_(path_match_39933)){
var new_link_39934 = (function (){var G__39536 = node_39932.cloneNode(true);
G__39536.setAttribute("href",[cljs.core.str.cljs$core$IFn$_invoke$arity$1(path_match_39933),"?r=",cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.rand.cljs$core$IFn$_invoke$arity$0())].join(''));

return G__39536;
})();
(node_39932.shadow$old = true);

(new_link_39934.onload = ((function (seq__39470_39875,chunk__39474_39876,count__39475_39877,i__39476_39878,seq__39273,chunk__39275,count__39276,i__39277,new_link_39934,path_match_39933,node_39932,seq__39470_39926__$1,temp__5823__auto___39925,path,map__39269,map__39269__$1,msg,updates,reload_info){
return (function (e){
var seq__39541_39935 = cljs.core.seq(cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(msg,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"reload-info","reload-info",1648088086),new cljs.core.Keyword(null,"asset-load","asset-load",-1925902322)], null)));
var chunk__39543_39936 = null;
var count__39544_39937 = (0);
var i__39545_39938 = (0);
while(true){
if((i__39545_39938 < count__39544_39937)){
var map__39554_39940 = chunk__39543_39936.cljs$core$IIndexed$_nth$arity$2(null, i__39545_39938);
var map__39554_39941__$1 = cljs.core.__destructure_map(map__39554_39940);
var task_39942 = map__39554_39941__$1;
var fn_str_39943 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39554_39941__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_39944 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39554_39941__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_39945 = goog.getObjectByName(fn_str_39943,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg(["call ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_39944)].join(''));

(fn_obj_39945.cljs$core$IFn$_invoke$arity$2 ? fn_obj_39945.cljs$core$IFn$_invoke$arity$2(path,new_link_39934) : fn_obj_39945.call(null, path,new_link_39934));


var G__39947 = seq__39541_39935;
var G__39948 = chunk__39543_39936;
var G__39949 = count__39544_39937;
var G__39950 = (i__39545_39938 + (1));
seq__39541_39935 = G__39947;
chunk__39543_39936 = G__39948;
count__39544_39937 = G__39949;
i__39545_39938 = G__39950;
continue;
} else {
var temp__5823__auto___39951__$1 = cljs.core.seq(seq__39541_39935);
if(temp__5823__auto___39951__$1){
var seq__39541_39952__$1 = temp__5823__auto___39951__$1;
if(cljs.core.chunked_seq_QMARK_(seq__39541_39952__$1)){
var c__5525__auto___39953 = cljs.core.chunk_first(seq__39541_39952__$1);
var G__39954 = cljs.core.chunk_rest(seq__39541_39952__$1);
var G__39955 = c__5525__auto___39953;
var G__39956 = cljs.core.count(c__5525__auto___39953);
var G__39957 = (0);
seq__39541_39935 = G__39954;
chunk__39543_39936 = G__39955;
count__39544_39937 = G__39956;
i__39545_39938 = G__39957;
continue;
} else {
var map__39558_39958 = cljs.core.first(seq__39541_39952__$1);
var map__39558_39959__$1 = cljs.core.__destructure_map(map__39558_39958);
var task_39960 = map__39558_39959__$1;
var fn_str_39961 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39558_39959__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_39962 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39558_39959__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_39963 = goog.getObjectByName(fn_str_39961,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg(["call ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_39962)].join(''));

(fn_obj_39963.cljs$core$IFn$_invoke$arity$2 ? fn_obj_39963.cljs$core$IFn$_invoke$arity$2(path,new_link_39934) : fn_obj_39963.call(null, path,new_link_39934));


var G__39964 = cljs.core.next(seq__39541_39952__$1);
var G__39965 = null;
var G__39966 = (0);
var G__39967 = (0);
seq__39541_39935 = G__39964;
chunk__39543_39936 = G__39965;
count__39544_39937 = G__39966;
i__39545_39938 = G__39967;
continue;
}
} else {
}
}
break;
}

return goog.dom.removeNode(node_39932);
});})(seq__39470_39875,chunk__39474_39876,count__39475_39877,i__39476_39878,seq__39273,chunk__39275,count__39276,i__39277,new_link_39934,path_match_39933,node_39932,seq__39470_39926__$1,temp__5823__auto___39925,path,map__39269,map__39269__$1,msg,updates,reload_info))
);

shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("load CSS",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([path_match_39933], 0));

goog.dom.insertSiblingAfter(new_link_39934,node_39932);


var G__39968 = cljs.core.next(seq__39470_39926__$1);
var G__39969 = null;
var G__39970 = (0);
var G__39971 = (0);
seq__39470_39875 = G__39968;
chunk__39474_39876 = G__39969;
count__39475_39877 = G__39970;
i__39476_39878 = G__39971;
continue;
} else {
var G__39972 = cljs.core.next(seq__39470_39926__$1);
var G__39973 = null;
var G__39974 = (0);
var G__39975 = (0);
seq__39470_39875 = G__39972;
chunk__39474_39876 = G__39973;
count__39475_39877 = G__39974;
i__39476_39878 = G__39975;
continue;
}
} else {
var G__39976 = cljs.core.next(seq__39470_39926__$1);
var G__39977 = null;
var G__39978 = (0);
var G__39979 = (0);
seq__39470_39875 = G__39976;
chunk__39474_39876 = G__39977;
count__39475_39877 = G__39978;
i__39476_39878 = G__39979;
continue;
}
}
} else {
}
}
break;
}


var G__39980 = seq__39273;
var G__39981 = chunk__39275;
var G__39982 = count__39276;
var G__39983 = (i__39277 + (1));
seq__39273 = G__39980;
chunk__39275 = G__39981;
count__39276 = G__39982;
i__39277 = G__39983;
continue;
} else {
var G__39984 = seq__39273;
var G__39985 = chunk__39275;
var G__39986 = count__39276;
var G__39987 = (i__39277 + (1));
seq__39273 = G__39984;
chunk__39275 = G__39985;
count__39276 = G__39986;
i__39277 = G__39987;
continue;
}
} else {
var temp__5823__auto__ = cljs.core.seq(seq__39273);
if(temp__5823__auto__){
var seq__39273__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__39273__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__39273__$1);
var G__39988 = cljs.core.chunk_rest(seq__39273__$1);
var G__39989 = c__5525__auto__;
var G__39990 = cljs.core.count(c__5525__auto__);
var G__39991 = (0);
seq__39273 = G__39988;
chunk__39275 = G__39989;
count__39276 = G__39990;
i__39277 = G__39991;
continue;
} else {
var path = cljs.core.first(seq__39273__$1);
if(clojure.string.ends_with_QMARK_(path,"css")){
var seq__39567_39992 = cljs.core.seq(cljs.core.array_seq.cljs$core$IFn$_invoke$arity$1(document.querySelectorAll("link[rel=\"stylesheet\"]")));
var chunk__39571_39993 = null;
var count__39572_39994 = (0);
var i__39573_39995 = (0);
while(true){
if((i__39573_39995 < count__39572_39994)){
var node_39996 = chunk__39571_39993.cljs$core$IIndexed$_nth$arity$2(null, i__39573_39995);
if(cljs.core.not(node_39996.shadow$old)){
var path_match_39997 = shadow.cljs.devtools.client.browser.match_paths(node_39996.getAttribute("href"),path);
if(cljs.core.truth_(path_match_39997)){
var new_link_39998 = (function (){var G__39650 = node_39996.cloneNode(true);
G__39650.setAttribute("href",[cljs.core.str.cljs$core$IFn$_invoke$arity$1(path_match_39997),"?r=",cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.rand.cljs$core$IFn$_invoke$arity$0())].join(''));

return G__39650;
})();
(node_39996.shadow$old = true);

(new_link_39998.onload = ((function (seq__39567_39992,chunk__39571_39993,count__39572_39994,i__39573_39995,seq__39273,chunk__39275,count__39276,i__39277,new_link_39998,path_match_39997,node_39996,path,seq__39273__$1,temp__5823__auto__,map__39269,map__39269__$1,msg,updates,reload_info){
return (function (e){
var seq__39651_39999 = cljs.core.seq(cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(msg,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"reload-info","reload-info",1648088086),new cljs.core.Keyword(null,"asset-load","asset-load",-1925902322)], null)));
var chunk__39653_40000 = null;
var count__39654_40001 = (0);
var i__39655_40002 = (0);
while(true){
if((i__39655_40002 < count__39654_40001)){
var map__39663_40003 = chunk__39653_40000.cljs$core$IIndexed$_nth$arity$2(null, i__39655_40002);
var map__39663_40004__$1 = cljs.core.__destructure_map(map__39663_40003);
var task_40005 = map__39663_40004__$1;
var fn_str_40006 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39663_40004__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_40007 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39663_40004__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_40008 = goog.getObjectByName(fn_str_40006,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg(["call ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_40007)].join(''));

(fn_obj_40008.cljs$core$IFn$_invoke$arity$2 ? fn_obj_40008.cljs$core$IFn$_invoke$arity$2(path,new_link_39998) : fn_obj_40008.call(null, path,new_link_39998));


var G__40009 = seq__39651_39999;
var G__40010 = chunk__39653_40000;
var G__40011 = count__39654_40001;
var G__40012 = (i__39655_40002 + (1));
seq__39651_39999 = G__40009;
chunk__39653_40000 = G__40010;
count__39654_40001 = G__40011;
i__39655_40002 = G__40012;
continue;
} else {
var temp__5823__auto___40013__$1 = cljs.core.seq(seq__39651_39999);
if(temp__5823__auto___40013__$1){
var seq__39651_40014__$1 = temp__5823__auto___40013__$1;
if(cljs.core.chunked_seq_QMARK_(seq__39651_40014__$1)){
var c__5525__auto___40015 = cljs.core.chunk_first(seq__39651_40014__$1);
var G__40017 = cljs.core.chunk_rest(seq__39651_40014__$1);
var G__40018 = c__5525__auto___40015;
var G__40019 = cljs.core.count(c__5525__auto___40015);
var G__40020 = (0);
seq__39651_39999 = G__40017;
chunk__39653_40000 = G__40018;
count__39654_40001 = G__40019;
i__39655_40002 = G__40020;
continue;
} else {
var map__39664_40021 = cljs.core.first(seq__39651_40014__$1);
var map__39664_40022__$1 = cljs.core.__destructure_map(map__39664_40021);
var task_40023 = map__39664_40022__$1;
var fn_str_40024 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39664_40022__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_40025 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39664_40022__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_40026 = goog.getObjectByName(fn_str_40024,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg(["call ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_40025)].join(''));

(fn_obj_40026.cljs$core$IFn$_invoke$arity$2 ? fn_obj_40026.cljs$core$IFn$_invoke$arity$2(path,new_link_39998) : fn_obj_40026.call(null, path,new_link_39998));


var G__40028 = cljs.core.next(seq__39651_40014__$1);
var G__40029 = null;
var G__40030 = (0);
var G__40031 = (0);
seq__39651_39999 = G__40028;
chunk__39653_40000 = G__40029;
count__39654_40001 = G__40030;
i__39655_40002 = G__40031;
continue;
}
} else {
}
}
break;
}

return goog.dom.removeNode(node_39996);
});})(seq__39567_39992,chunk__39571_39993,count__39572_39994,i__39573_39995,seq__39273,chunk__39275,count__39276,i__39277,new_link_39998,path_match_39997,node_39996,path,seq__39273__$1,temp__5823__auto__,map__39269,map__39269__$1,msg,updates,reload_info))
);

shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("load CSS",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([path_match_39997], 0));

goog.dom.insertSiblingAfter(new_link_39998,node_39996);


var G__40032 = seq__39567_39992;
var G__40033 = chunk__39571_39993;
var G__40034 = count__39572_39994;
var G__40035 = (i__39573_39995 + (1));
seq__39567_39992 = G__40032;
chunk__39571_39993 = G__40033;
count__39572_39994 = G__40034;
i__39573_39995 = G__40035;
continue;
} else {
var G__40036 = seq__39567_39992;
var G__40037 = chunk__39571_39993;
var G__40038 = count__39572_39994;
var G__40039 = (i__39573_39995 + (1));
seq__39567_39992 = G__40036;
chunk__39571_39993 = G__40037;
count__39572_39994 = G__40038;
i__39573_39995 = G__40039;
continue;
}
} else {
var G__40040 = seq__39567_39992;
var G__40041 = chunk__39571_39993;
var G__40042 = count__39572_39994;
var G__40043 = (i__39573_39995 + (1));
seq__39567_39992 = G__40040;
chunk__39571_39993 = G__40041;
count__39572_39994 = G__40042;
i__39573_39995 = G__40043;
continue;
}
} else {
var temp__5823__auto___40044__$1 = cljs.core.seq(seq__39567_39992);
if(temp__5823__auto___40044__$1){
var seq__39567_40045__$1 = temp__5823__auto___40044__$1;
if(cljs.core.chunked_seq_QMARK_(seq__39567_40045__$1)){
var c__5525__auto___40046 = cljs.core.chunk_first(seq__39567_40045__$1);
var G__40047 = cljs.core.chunk_rest(seq__39567_40045__$1);
var G__40048 = c__5525__auto___40046;
var G__40049 = cljs.core.count(c__5525__auto___40046);
var G__40050 = (0);
seq__39567_39992 = G__40047;
chunk__39571_39993 = G__40048;
count__39572_39994 = G__40049;
i__39573_39995 = G__40050;
continue;
} else {
var node_40051 = cljs.core.first(seq__39567_40045__$1);
if(cljs.core.not(node_40051.shadow$old)){
var path_match_40052 = shadow.cljs.devtools.client.browser.match_paths(node_40051.getAttribute("href"),path);
if(cljs.core.truth_(path_match_40052)){
var new_link_40053 = (function (){var G__39665 = node_40051.cloneNode(true);
G__39665.setAttribute("href",[cljs.core.str.cljs$core$IFn$_invoke$arity$1(path_match_40052),"?r=",cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.rand.cljs$core$IFn$_invoke$arity$0())].join(''));

return G__39665;
})();
(node_40051.shadow$old = true);

(new_link_40053.onload = ((function (seq__39567_39992,chunk__39571_39993,count__39572_39994,i__39573_39995,seq__39273,chunk__39275,count__39276,i__39277,new_link_40053,path_match_40052,node_40051,seq__39567_40045__$1,temp__5823__auto___40044__$1,path,seq__39273__$1,temp__5823__auto__,map__39269,map__39269__$1,msg,updates,reload_info){
return (function (e){
var seq__39670_40054 = cljs.core.seq(cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(msg,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"reload-info","reload-info",1648088086),new cljs.core.Keyword(null,"asset-load","asset-load",-1925902322)], null)));
var chunk__39672_40055 = null;
var count__39673_40056 = (0);
var i__39674_40057 = (0);
while(true){
if((i__39674_40057 < count__39673_40056)){
var map__39678_40058 = chunk__39672_40055.cljs$core$IIndexed$_nth$arity$2(null, i__39674_40057);
var map__39678_40059__$1 = cljs.core.__destructure_map(map__39678_40058);
var task_40060 = map__39678_40059__$1;
var fn_str_40061 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39678_40059__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_40062 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39678_40059__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_40063 = goog.getObjectByName(fn_str_40061,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg(["call ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_40062)].join(''));

(fn_obj_40063.cljs$core$IFn$_invoke$arity$2 ? fn_obj_40063.cljs$core$IFn$_invoke$arity$2(path,new_link_40053) : fn_obj_40063.call(null, path,new_link_40053));


var G__40064 = seq__39670_40054;
var G__40065 = chunk__39672_40055;
var G__40066 = count__39673_40056;
var G__40067 = (i__39674_40057 + (1));
seq__39670_40054 = G__40064;
chunk__39672_40055 = G__40065;
count__39673_40056 = G__40066;
i__39674_40057 = G__40067;
continue;
} else {
var temp__5823__auto___40068__$2 = cljs.core.seq(seq__39670_40054);
if(temp__5823__auto___40068__$2){
var seq__39670_40069__$1 = temp__5823__auto___40068__$2;
if(cljs.core.chunked_seq_QMARK_(seq__39670_40069__$1)){
var c__5525__auto___40070 = cljs.core.chunk_first(seq__39670_40069__$1);
var G__40071 = cljs.core.chunk_rest(seq__39670_40069__$1);
var G__40072 = c__5525__auto___40070;
var G__40073 = cljs.core.count(c__5525__auto___40070);
var G__40074 = (0);
seq__39670_40054 = G__40071;
chunk__39672_40055 = G__40072;
count__39673_40056 = G__40073;
i__39674_40057 = G__40074;
continue;
} else {
var map__39679_40076 = cljs.core.first(seq__39670_40069__$1);
var map__39679_40077__$1 = cljs.core.__destructure_map(map__39679_40076);
var task_40078 = map__39679_40077__$1;
var fn_str_40079 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39679_40077__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_40080 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39679_40077__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_40081 = goog.getObjectByName(fn_str_40079,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg(["call ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_40080)].join(''));

(fn_obj_40081.cljs$core$IFn$_invoke$arity$2 ? fn_obj_40081.cljs$core$IFn$_invoke$arity$2(path,new_link_40053) : fn_obj_40081.call(null, path,new_link_40053));


var G__40083 = cljs.core.next(seq__39670_40069__$1);
var G__40084 = null;
var G__40085 = (0);
var G__40086 = (0);
seq__39670_40054 = G__40083;
chunk__39672_40055 = G__40084;
count__39673_40056 = G__40085;
i__39674_40057 = G__40086;
continue;
}
} else {
}
}
break;
}

return goog.dom.removeNode(node_40051);
});})(seq__39567_39992,chunk__39571_39993,count__39572_39994,i__39573_39995,seq__39273,chunk__39275,count__39276,i__39277,new_link_40053,path_match_40052,node_40051,seq__39567_40045__$1,temp__5823__auto___40044__$1,path,seq__39273__$1,temp__5823__auto__,map__39269,map__39269__$1,msg,updates,reload_info))
);

shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("load CSS",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([path_match_40052], 0));

goog.dom.insertSiblingAfter(new_link_40053,node_40051);


var G__40087 = cljs.core.next(seq__39567_40045__$1);
var G__40088 = null;
var G__40089 = (0);
var G__40090 = (0);
seq__39567_39992 = G__40087;
chunk__39571_39993 = G__40088;
count__39572_39994 = G__40089;
i__39573_39995 = G__40090;
continue;
} else {
var G__40091 = cljs.core.next(seq__39567_40045__$1);
var G__40092 = null;
var G__40093 = (0);
var G__40094 = (0);
seq__39567_39992 = G__40091;
chunk__39571_39993 = G__40092;
count__39572_39994 = G__40093;
i__39573_39995 = G__40094;
continue;
}
} else {
var G__40095 = cljs.core.next(seq__39567_40045__$1);
var G__40096 = null;
var G__40097 = (0);
var G__40098 = (0);
seq__39567_39992 = G__40095;
chunk__39571_39993 = G__40096;
count__39572_39994 = G__40097;
i__39573_39995 = G__40098;
continue;
}
}
} else {
}
}
break;
}


var G__40099 = cljs.core.next(seq__39273__$1);
var G__40100 = null;
var G__40101 = (0);
var G__40102 = (0);
seq__39273 = G__40099;
chunk__39275 = G__40100;
count__39276 = G__40101;
i__39277 = G__40102;
continue;
} else {
var G__40103 = cljs.core.next(seq__39273__$1);
var G__40104 = null;
var G__40105 = (0);
var G__40106 = (0);
seq__39273 = G__40103;
chunk__39275 = G__40104;
count__39276 = G__40105;
i__39277 = G__40106;
continue;
}
}
} else {
return null;
}
}
break;
}
});
shadow.cljs.devtools.client.browser.global_eval = (function shadow$cljs$devtools$client$browser$global_eval(js){
if(cljs.core.not_EQ_.cljs$core$IFn$_invoke$arity$2("undefined",typeof(module))){
return eval(js);
} else {
return (0,eval)(js);;
}
});
shadow.cljs.devtools.client.browser.runtime_info = (((typeof SHADOW_CONFIG !== 'undefined'))?shadow.json.to_clj.cljs$core$IFn$_invoke$arity$1(SHADOW_CONFIG):null);
shadow.cljs.devtools.client.browser.client_info = cljs.core.merge.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([shadow.cljs.devtools.client.browser.runtime_info,new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"host","host",-1558485167),(cljs.core.truth_(goog.global.document)?new cljs.core.Keyword(null,"browser","browser",828191719):new cljs.core.Keyword(null,"browser-worker","browser-worker",1638998282)),new cljs.core.Keyword(null,"user-agent","user-agent",1220426212),[(cljs.core.truth_(goog.userAgent.OPERA)?"Opera":(cljs.core.truth_(goog.userAgent.product.CHROME)?"Chrome":(cljs.core.truth_(goog.userAgent.IE)?"MSIE":(cljs.core.truth_(goog.userAgent.EDGE)?"Edge":(cljs.core.truth_(goog.userAgent.GECKO)?"Firefox":(cljs.core.truth_(goog.userAgent.SAFARI)?"Safari":(cljs.core.truth_(goog.userAgent.WEBKIT)?"Webkit":null)))))))," ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(goog.userAgent.VERSION)," [",cljs.core.str.cljs$core$IFn$_invoke$arity$1(goog.userAgent.PLATFORM),"]"].join(''),new cljs.core.Keyword(null,"dom","dom",-1236537922),(!((goog.global.document == null)))], null)], 0));
if((typeof shadow !== 'undefined') && (typeof shadow.cljs !== 'undefined') && (typeof shadow.cljs.devtools !== 'undefined') && (typeof shadow.cljs.devtools.client !== 'undefined') && (typeof shadow.cljs.devtools.client.browser !== 'undefined') && (typeof shadow.cljs.devtools.client.browser.ws_was_welcome_ref !== 'undefined')){
} else {
shadow.cljs.devtools.client.browser.ws_was_welcome_ref = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(false);
}
if(((shadow.cljs.devtools.client.env.enabled) && ((shadow.cljs.devtools.client.env.worker_client_id > (0))))){
(shadow.cljs.devtools.client.shared.Runtime.prototype.shadow$remote$runtime$api$IEvalJS$ = cljs.core.PROTOCOL_SENTINEL);

(shadow.cljs.devtools.client.shared.Runtime.prototype.shadow$remote$runtime$api$IEvalJS$_js_eval$arity$2 = (function (this$,code){
var this$__$1 = this;
return shadow.cljs.devtools.client.browser.global_eval(code);
}));

(shadow.cljs.devtools.client.shared.Runtime.prototype.shadow$cljs$devtools$client$shared$IHostSpecific$ = cljs.core.PROTOCOL_SENTINEL);

(shadow.cljs.devtools.client.shared.Runtime.prototype.shadow$cljs$devtools$client$shared$IHostSpecific$do_invoke$arity$3 = (function (this$,ns,p__39734){
var map__39735 = p__39734;
var map__39735__$1 = cljs.core.__destructure_map(map__39735);
var js = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39735__$1,new cljs.core.Keyword(null,"js","js",1768080579));
var this$__$1 = this;
return shadow.cljs.devtools.client.browser.global_eval(js);
}));

(shadow.cljs.devtools.client.shared.Runtime.prototype.shadow$cljs$devtools$client$shared$IHostSpecific$do_repl_init$arity$4 = (function (runtime,p__39746,done,error){
var map__39748 = p__39746;
var map__39748__$1 = cljs.core.__destructure_map(map__39748);
var repl_sources = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39748__$1,new cljs.core.Keyword(null,"repl-sources","repl-sources",723867535));
var runtime__$1 = this;
return shadow.cljs.devtools.client.shared.load_sources(runtime__$1,cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentVector.EMPTY,cljs.core.remove.cljs$core$IFn$_invoke$arity$2(shadow.cljs.devtools.client.env.src_is_loaded_QMARK_,repl_sources)),(function (sources){
shadow.cljs.devtools.client.browser.do_js_load(sources);

return (done.cljs$core$IFn$_invoke$arity$0 ? done.cljs$core$IFn$_invoke$arity$0() : done.call(null, ));
}));
}));

(shadow.cljs.devtools.client.shared.Runtime.prototype.shadow$cljs$devtools$client$shared$IHostSpecific$do_repl_require$arity$4 = (function (runtime,p__39762,done,error){
var map__39767 = p__39762;
var map__39767__$1 = cljs.core.__destructure_map(map__39767);
var msg = map__39767__$1;
var sources = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39767__$1,new cljs.core.Keyword(null,"sources","sources",-321166424));
var reload_namespaces = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39767__$1,new cljs.core.Keyword(null,"reload-namespaces","reload-namespaces",250210134));
var js_requires = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39767__$1,new cljs.core.Keyword(null,"js-requires","js-requires",-1311472051));
var runtime__$1 = this;
var sources_to_load = cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentVector.EMPTY,cljs.core.remove.cljs$core$IFn$_invoke$arity$2((function (p__39780){
var map__39781 = p__39780;
var map__39781__$1 = cljs.core.__destructure_map(map__39781);
var src = map__39781__$1;
var provides = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39781__$1,new cljs.core.Keyword(null,"provides","provides",-1634397992));
var and__5000__auto__ = shadow.cljs.devtools.client.env.src_is_loaded_QMARK_(src);
if(cljs.core.truth_(and__5000__auto__)){
return cljs.core.not(cljs.core.some(reload_namespaces,provides));
} else {
return and__5000__auto__;
}
}),sources));
if(cljs.core.not(cljs.core.seq(sources_to_load))){
var G__39784 = cljs.core.PersistentVector.EMPTY;
return (done.cljs$core$IFn$_invoke$arity$1 ? done.cljs$core$IFn$_invoke$arity$1(G__39784) : done.call(null, G__39784));
} else {
return shadow.remote.runtime.shared.call.cljs$core$IFn$_invoke$arity$3(runtime__$1,new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"cljs-load-sources","cljs-load-sources",-1458295962),new cljs.core.Keyword(null,"to","to",192099007),shadow.cljs.devtools.client.env.worker_client_id,new cljs.core.Keyword(null,"sources","sources",-321166424),cljs.core.into.cljs$core$IFn$_invoke$arity$3(cljs.core.PersistentVector.EMPTY,cljs.core.map.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"resource-id","resource-id",-1308422582)),sources_to_load)], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"cljs-sources","cljs-sources",31121610),(function (p__39786){
var map__39787 = p__39786;
var map__39787__$1 = cljs.core.__destructure_map(map__39787);
var msg__$1 = map__39787__$1;
var sources__$1 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39787__$1,new cljs.core.Keyword(null,"sources","sources",-321166424));
try{shadow.cljs.devtools.client.browser.do_js_load(sources__$1);

if(cljs.core.seq(js_requires)){
shadow.cljs.devtools.client.browser.do_js_requires(js_requires);
} else {
}

return (done.cljs$core$IFn$_invoke$arity$1 ? done.cljs$core$IFn$_invoke$arity$1(sources_to_load) : done.call(null, sources_to_load));
}catch (e39788){var ex = e39788;
return (error.cljs$core$IFn$_invoke$arity$1 ? error.cljs$core$IFn$_invoke$arity$1(ex) : error.call(null, ex));
}})], null));
}
}));

shadow.cljs.devtools.client.shared.add_plugin_BANG_(new cljs.core.Keyword("shadow.cljs.devtools.client.browser","client","shadow.cljs.devtools.client.browser/client",-1461019282),cljs.core.PersistentHashSet.EMPTY,(function (p__39790){
var map__39791 = p__39790;
var map__39791__$1 = cljs.core.__destructure_map(map__39791);
var env = map__39791__$1;
var runtime = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39791__$1,new cljs.core.Keyword(null,"runtime","runtime",-1331573996));
var svc = new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"runtime","runtime",-1331573996),runtime], null);
shadow.remote.runtime.api.add_extension(runtime,new cljs.core.Keyword("shadow.cljs.devtools.client.browser","client","shadow.cljs.devtools.client.browser/client",-1461019282),new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword(null,"on-welcome","on-welcome",1895317125),(function (){
cljs.core.reset_BANG_(shadow.cljs.devtools.client.browser.ws_was_welcome_ref,true);

shadow.cljs.devtools.client.hud.connection_error_clear_BANG_();

shadow.cljs.devtools.client.env.patch_goog_BANG_();

return shadow.cljs.devtools.client.browser.devtools_msg(["#",cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"client-id","client-id",-464622140).cljs$core$IFn$_invoke$arity$1(cljs.core.deref(new cljs.core.Keyword(null,"state-ref","state-ref",2127874952).cljs$core$IFn$_invoke$arity$1(runtime))))," ready!"].join(''));
}),new cljs.core.Keyword(null,"on-disconnect","on-disconnect",-809021814),(function (e){
if(cljs.core.truth_(cljs.core.deref(shadow.cljs.devtools.client.browser.ws_was_welcome_ref))){
shadow.cljs.devtools.client.hud.connection_error("The Websocket connection was closed!");

return cljs.core.reset_BANG_(shadow.cljs.devtools.client.browser.ws_was_welcome_ref,false);
} else {
return null;
}
}),new cljs.core.Keyword(null,"on-reconnect","on-reconnect",1239988702),(function (e){
return shadow.cljs.devtools.client.hud.connection_error("Reconnecting ...");
}),new cljs.core.Keyword(null,"ops","ops",1237330063),new cljs.core.PersistentArrayMap(null, 7, [new cljs.core.Keyword(null,"access-denied","access-denied",959449406),(function (msg){
cljs.core.reset_BANG_(shadow.cljs.devtools.client.browser.ws_was_welcome_ref,false);

return shadow.cljs.devtools.client.hud.connection_error(["Stale Output! Your loaded JS was not produced by the running shadow-cljs instance."," Is the watch for this build running?"].join(''));
}),new cljs.core.Keyword(null,"cljs-asset-update","cljs-asset-update",1224093028),(function (msg){
return shadow.cljs.devtools.client.browser.handle_asset_update(msg);
}),new cljs.core.Keyword(null,"cljs-build-configure","cljs-build-configure",-2089891268),(function (msg){
return null;
}),new cljs.core.Keyword(null,"cljs-build-start","cljs-build-start",-725781241),(function (msg){
shadow.cljs.devtools.client.hud.hud_hide();

shadow.cljs.devtools.client.hud.load_start();

return shadow.cljs.devtools.client.env.run_custom_notify_BANG_(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(msg,new cljs.core.Keyword(null,"type","type",1174270348),new cljs.core.Keyword(null,"build-start","build-start",-959649480)));
}),new cljs.core.Keyword(null,"cljs-build-complete","cljs-build-complete",273626153),(function (msg){
var msg__$1 = shadow.cljs.devtools.client.env.add_warnings_to_info(msg);
shadow.cljs.devtools.client.hud.connection_error_clear_BANG_();

shadow.cljs.devtools.client.hud.hud_warnings(msg__$1);

shadow.cljs.devtools.client.browser.handle_build_complete(runtime,msg__$1);

return shadow.cljs.devtools.client.env.run_custom_notify_BANG_(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(msg__$1,new cljs.core.Keyword(null,"type","type",1174270348),new cljs.core.Keyword(null,"build-complete","build-complete",-501868472)));
}),new cljs.core.Keyword(null,"cljs-build-failure","cljs-build-failure",1718154990),(function (msg){
shadow.cljs.devtools.client.hud.load_end();

shadow.cljs.devtools.client.hud.hud_error(msg);

return shadow.cljs.devtools.client.env.run_custom_notify_BANG_(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(msg,new cljs.core.Keyword(null,"type","type",1174270348),new cljs.core.Keyword(null,"build-failure","build-failure",-2107487466)));
}),new cljs.core.Keyword("shadow.cljs.devtools.client.env","worker-notify","shadow.cljs.devtools.client.env/worker-notify",-1456820670),(function (p__39794){
var map__39795 = p__39794;
var map__39795__$1 = cljs.core.__destructure_map(map__39795);
var event_op = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39795__$1,new cljs.core.Keyword(null,"event-op","event-op",200358057));
var client_id = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39795__$1,new cljs.core.Keyword(null,"client-id","client-id",-464622140));
if(((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"client-disconnect","client-disconnect",640227957),event_op)) && (cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(client_id,shadow.cljs.devtools.client.env.worker_client_id)))){
shadow.cljs.devtools.client.hud.connection_error_clear_BANG_();

return shadow.cljs.devtools.client.hud.connection_error("The watch for this build was stopped!");
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"client-connect","client-connect",-1113973888),event_op)){
shadow.cljs.devtools.client.hud.connection_error_clear_BANG_();

return shadow.cljs.devtools.client.hud.connection_error("The watch for this build was restarted. Reload required!");
} else {
return null;
}
}
})], null)], null));

return svc;
}),(function (p__39797){
var map__39798 = p__39797;
var map__39798__$1 = cljs.core.__destructure_map(map__39798);
var svc = map__39798__$1;
var runtime = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__39798__$1,new cljs.core.Keyword(null,"runtime","runtime",-1331573996));
return shadow.remote.runtime.api.del_extension(runtime,new cljs.core.Keyword("shadow.cljs.devtools.client.browser","client","shadow.cljs.devtools.client.browser/client",-1461019282));
}));

shadow.cljs.devtools.client.shared.init_runtime_BANG_(shadow.cljs.devtools.client.browser.client_info,shadow.cljs.devtools.client.websocket.start,shadow.cljs.devtools.client.websocket.send,shadow.cljs.devtools.client.websocket.stop);
} else {
}

//# sourceMappingURL=shadow.cljs.devtools.client.browser.js.map
