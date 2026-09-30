goog.provide('reagent.debug');
reagent.debug.has_console = (typeof console !== 'undefined');
reagent.debug.tracking = false;
if((typeof reagent !== 'undefined') && (typeof reagent.debug !== 'undefined') && (typeof reagent.debug.warnings !== 'undefined')){
} else {
reagent.debug.warnings = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(null);
}
if((typeof reagent !== 'undefined') && (typeof reagent.debug !== 'undefined') && (typeof reagent.debug.track_console !== 'undefined')){
} else {
reagent.debug.track_console = (function (){var o = ({});
(o.warn = (function() { 
var G__33591__delegate = function (args){
return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$variadic(reagent.debug.warnings,cljs.core.update_in,new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"warn","warn",-436710552)], null),cljs.core.conj,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.str,args)], 0));
};
var G__33591 = function (var_args){
var args = null;
if (arguments.length > 0) {
var G__33592__i = 0, G__33592__a = new Array(arguments.length -  0);
while (G__33592__i < G__33592__a.length) {G__33592__a[G__33592__i] = arguments[G__33592__i + 0]; ++G__33592__i;}
  args = new cljs.core.IndexedSeq(G__33592__a,0,null);
} 
return G__33591__delegate.call(this,args);};
G__33591.cljs$lang$maxFixedArity = 0;
G__33591.cljs$lang$applyTo = (function (arglist__33593){
var args = cljs.core.seq(arglist__33593);
return G__33591__delegate(args);
});
G__33591.cljs$core$IFn$_invoke$arity$variadic = G__33591__delegate;
return G__33591;
})()
);

(o.error = (function() { 
var G__33594__delegate = function (args){
return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$variadic(reagent.debug.warnings,cljs.core.update_in,new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"error","error",-978969032)], null),cljs.core.conj,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.str,args)], 0));
};
var G__33594 = function (var_args){
var args = null;
if (arguments.length > 0) {
var G__33595__i = 0, G__33595__a = new Array(arguments.length -  0);
while (G__33595__i < G__33595__a.length) {G__33595__a[G__33595__i] = arguments[G__33595__i + 0]; ++G__33595__i;}
  args = new cljs.core.IndexedSeq(G__33595__a,0,null);
} 
return G__33594__delegate.call(this,args);};
G__33594.cljs$lang$maxFixedArity = 0;
G__33594.cljs$lang$applyTo = (function (arglist__33596){
var args = cljs.core.seq(arglist__33596);
return G__33594__delegate(args);
});
G__33594.cljs$core$IFn$_invoke$arity$variadic = G__33594__delegate;
return G__33594;
})()
);

return o;
})();
}
reagent.debug.track_warnings = (function reagent$debug$track_warnings(f){
(reagent.debug.tracking = true);

cljs.core.reset_BANG_(reagent.debug.warnings,null);

(f.cljs$core$IFn$_invoke$arity$0 ? f.cljs$core$IFn$_invoke$arity$0() : f.call(null, ));

var warns = cljs.core.deref(reagent.debug.warnings);
cljs.core.reset_BANG_(reagent.debug.warnings,null);

(reagent.debug.tracking = false);

return warns;
});

//# sourceMappingURL=reagent.debug.js.map
