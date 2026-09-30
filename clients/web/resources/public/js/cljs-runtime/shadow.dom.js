goog.provide('shadow.dom');
shadow.dom.transition_supported_QMARK_ = true;

/**
 * @interface
 */
shadow.dom.IElement = function(){};

var shadow$dom$IElement$_to_dom$dyn_32461 = (function (this$){
var x__5350__auto__ = (((this$ == null))?null:this$);
var m__5351__auto__ = (shadow.dom._to_dom[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$1(this$) : m__5351__auto__.call(null, this$));
} else {
var m__5349__auto__ = (shadow.dom._to_dom["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$1(this$) : m__5349__auto__.call(null, this$));
} else {
throw cljs.core.missing_protocol("IElement.-to-dom",this$);
}
}
});
shadow.dom._to_dom = (function shadow$dom$_to_dom(this$){
if((((!((this$ == null)))) && ((!((this$.shadow$dom$IElement$_to_dom$arity$1 == null)))))){
return this$.shadow$dom$IElement$_to_dom$arity$1(this$);
} else {
return shadow$dom$IElement$_to_dom$dyn_32461(this$);
}
});


/**
 * @interface
 */
shadow.dom.SVGElement = function(){};

var shadow$dom$SVGElement$_to_svg$dyn_32518 = (function (this$){
var x__5350__auto__ = (((this$ == null))?null:this$);
var m__5351__auto__ = (shadow.dom._to_svg[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$1(this$) : m__5351__auto__.call(null, this$));
} else {
var m__5349__auto__ = (shadow.dom._to_svg["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$1(this$) : m__5349__auto__.call(null, this$));
} else {
throw cljs.core.missing_protocol("SVGElement.-to-svg",this$);
}
}
});
shadow.dom._to_svg = (function shadow$dom$_to_svg(this$){
if((((!((this$ == null)))) && ((!((this$.shadow$dom$SVGElement$_to_svg$arity$1 == null)))))){
return this$.shadow$dom$SVGElement$_to_svg$arity$1(this$);
} else {
return shadow$dom$SVGElement$_to_svg$dyn_32518(this$);
}
});

shadow.dom.lazy_native_coll_seq = (function shadow$dom$lazy_native_coll_seq(coll,idx){
if((idx < coll.length)){
return (new cljs.core.LazySeq(null,(function (){
return cljs.core.cons((coll[idx]),(function (){var G__30482 = coll;
var G__30483 = (idx + (1));
return (shadow.dom.lazy_native_coll_seq.cljs$core$IFn$_invoke$arity$2 ? shadow.dom.lazy_native_coll_seq.cljs$core$IFn$_invoke$arity$2(G__30482,G__30483) : shadow.dom.lazy_native_coll_seq.call(null, G__30482,G__30483));
})());
}),null,null));
} else {
return null;
}
});

/**
* @constructor
 * @implements {cljs.core.IIndexed}
 * @implements {cljs.core.ICounted}
 * @implements {cljs.core.ISeqable}
 * @implements {cljs.core.IDeref}
 * @implements {shadow.dom.IElement}
*/
shadow.dom.NativeColl = (function (coll){
this.coll = coll;
this.cljs$lang$protocol_mask$partition0$ = 8421394;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(shadow.dom.NativeColl.prototype.cljs$core$IDeref$_deref$arity$1 = (function (this$){
var self__ = this;
var this$__$1 = this;
return self__.coll;
}));

(shadow.dom.NativeColl.prototype.cljs$core$IIndexed$_nth$arity$2 = (function (this$,n){
var self__ = this;
var this$__$1 = this;
return (self__.coll[n]);
}));

(shadow.dom.NativeColl.prototype.cljs$core$IIndexed$_nth$arity$3 = (function (this$,n,not_found){
var self__ = this;
var this$__$1 = this;
var or__5002__auto__ = (self__.coll[n]);
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return not_found;
}
}));

(shadow.dom.NativeColl.prototype.cljs$core$ICounted$_count$arity$1 = (function (this$){
var self__ = this;
var this$__$1 = this;
return self__.coll.length;
}));

(shadow.dom.NativeColl.prototype.cljs$core$ISeqable$_seq$arity$1 = (function (this$){
var self__ = this;
var this$__$1 = this;
return shadow.dom.lazy_native_coll_seq(self__.coll,(0));
}));

(shadow.dom.NativeColl.prototype.shadow$dom$IElement$ = cljs.core.PROTOCOL_SENTINEL);

(shadow.dom.NativeColl.prototype.shadow$dom$IElement$_to_dom$arity$1 = (function (this$){
var self__ = this;
var this$__$1 = this;
return self__.coll;
}));

(shadow.dom.NativeColl.getBasis = (function (){
return new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"coll","coll",-1006698606,null)], null);
}));

(shadow.dom.NativeColl.cljs$lang$type = true);

(shadow.dom.NativeColl.cljs$lang$ctorStr = "shadow.dom/NativeColl");

(shadow.dom.NativeColl.cljs$lang$ctorPrWriter = (function (this__5287__auto__,writer__5288__auto__,opt__5289__auto__){
return cljs.core._write(writer__5288__auto__,"shadow.dom/NativeColl");
}));

/**
 * Positional factory function for shadow.dom/NativeColl.
 */
shadow.dom.__GT_NativeColl = (function shadow$dom$__GT_NativeColl(coll){
return (new shadow.dom.NativeColl(coll));
});

shadow.dom.native_coll = (function shadow$dom$native_coll(coll){
return (new shadow.dom.NativeColl(coll));
});
shadow.dom.dom_node = (function shadow$dom$dom_node(el){
if((el == null)){
return null;
} else {
if((((!((el == null))))?((((false) || ((cljs.core.PROTOCOL_SENTINEL === el.shadow$dom$IElement$))))?true:false):false)){
return el.shadow$dom$IElement$_to_dom$arity$1(null, );
} else {
if(typeof el === 'string'){
return document.createTextNode(el);
} else {
if(typeof el === 'number'){
return document.createTextNode(cljs.core.str.cljs$core$IFn$_invoke$arity$1(el));
} else {
return el;

}
}
}
}
});
shadow.dom.query_one = (function shadow$dom$query_one(var_args){
var G__30521 = arguments.length;
switch (G__30521) {
case 1:
return shadow.dom.query_one.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.query_one.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(shadow.dom.query_one.cljs$core$IFn$_invoke$arity$1 = (function (sel){
return document.querySelector(sel);
}));

(shadow.dom.query_one.cljs$core$IFn$_invoke$arity$2 = (function (sel,root){
return shadow.dom.dom_node(root).querySelector(sel);
}));

(shadow.dom.query_one.cljs$lang$maxFixedArity = 2);

shadow.dom.query = (function shadow$dom$query(var_args){
var G__30537 = arguments.length;
switch (G__30537) {
case 1:
return shadow.dom.query.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.query.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(shadow.dom.query.cljs$core$IFn$_invoke$arity$1 = (function (sel){
return (new shadow.dom.NativeColl(document.querySelectorAll(sel)));
}));

(shadow.dom.query.cljs$core$IFn$_invoke$arity$2 = (function (sel,root){
return (new shadow.dom.NativeColl(shadow.dom.dom_node(root).querySelectorAll(sel)));
}));

(shadow.dom.query.cljs$lang$maxFixedArity = 2);

shadow.dom.by_id = (function shadow$dom$by_id(var_args){
var G__30558 = arguments.length;
switch (G__30558) {
case 2:
return shadow.dom.by_id.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 1:
return shadow.dom.by_id.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(shadow.dom.by_id.cljs$core$IFn$_invoke$arity$2 = (function (id,el){
return shadow.dom.dom_node(el).getElementById(id);
}));

(shadow.dom.by_id.cljs$core$IFn$_invoke$arity$1 = (function (id){
return document.getElementById(id);
}));

(shadow.dom.by_id.cljs$lang$maxFixedArity = 2);

shadow.dom.build = shadow.dom.dom_node;
shadow.dom.ev_stop = (function shadow$dom$ev_stop(var_args){
var G__30585 = arguments.length;
switch (G__30585) {
case 1:
return shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 4:
return shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$1 = (function (e){
if(cljs.core.truth_(e.stopPropagation)){
e.stopPropagation();

e.preventDefault();
} else {
(e.cancelBubble = true);

(e.returnValue = false);
}

return e;
}));

(shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$2 = (function (e,el){
shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$1(e);

return el;
}));

(shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$4 = (function (e,el,scope,owner){
shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$1(e);

return el;
}));

(shadow.dom.ev_stop.cljs$lang$maxFixedArity = 4);

/**
 * check wether a parent node (or the document) contains the child
 */
shadow.dom.contains_QMARK_ = (function shadow$dom$contains_QMARK_(var_args){
var G__30632 = arguments.length;
switch (G__30632) {
case 1:
return shadow.dom.contains_QMARK_.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.contains_QMARK_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(shadow.dom.contains_QMARK_.cljs$core$IFn$_invoke$arity$1 = (function (el){
return goog.dom.contains(document,shadow.dom.dom_node(el));
}));

(shadow.dom.contains_QMARK_.cljs$core$IFn$_invoke$arity$2 = (function (parent,el){
return goog.dom.contains(shadow.dom.dom_node(parent),shadow.dom.dom_node(el));
}));

(shadow.dom.contains_QMARK_.cljs$lang$maxFixedArity = 2);

shadow.dom.add_class = (function shadow$dom$add_class(el,cls){
return goog.dom.classlist.add(shadow.dom.dom_node(el),cls);
});
shadow.dom.remove_class = (function shadow$dom$remove_class(el,cls){
return goog.dom.classlist.remove(shadow.dom.dom_node(el),cls);
});
shadow.dom.toggle_class = (function shadow$dom$toggle_class(var_args){
var G__30648 = arguments.length;
switch (G__30648) {
case 2:
return shadow.dom.toggle_class.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return shadow.dom.toggle_class.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(shadow.dom.toggle_class.cljs$core$IFn$_invoke$arity$2 = (function (el,cls){
return goog.dom.classlist.toggle(shadow.dom.dom_node(el),cls);
}));

(shadow.dom.toggle_class.cljs$core$IFn$_invoke$arity$3 = (function (el,cls,v){
if(cljs.core.truth_(v)){
return shadow.dom.add_class(el,cls);
} else {
return shadow.dom.remove_class(el,cls);
}
}));

(shadow.dom.toggle_class.cljs$lang$maxFixedArity = 3);

shadow.dom.dom_listen = (cljs.core.truth_((function (){var or__5002__auto__ = (!((typeof document !== 'undefined')));
if(or__5002__auto__){
return or__5002__auto__;
} else {
return document.addEventListener;
}
})())?(function shadow$dom$dom_listen_good(el,ev,handler){
return el.addEventListener(ev,handler,false);
}):(function shadow$dom$dom_listen_ie(el,ev,handler){
try{return el.attachEvent(["on",cljs.core.str.cljs$core$IFn$_invoke$arity$1(ev)].join(''),(function (e){
return (handler.cljs$core$IFn$_invoke$arity$2 ? handler.cljs$core$IFn$_invoke$arity$2(e,el) : handler.call(null, e,el));
}));
}catch (e30684){if((e30684 instanceof Object)){
var e = e30684;
return console.log("didnt support attachEvent",el,e);
} else {
throw e30684;

}
}}));
shadow.dom.dom_listen_remove = (cljs.core.truth_((function (){var or__5002__auto__ = (!((typeof document !== 'undefined')));
if(or__5002__auto__){
return or__5002__auto__;
} else {
return document.removeEventListener;
}
})())?(function shadow$dom$dom_listen_remove_good(el,ev,handler){
return el.removeEventListener(ev,handler,false);
}):(function shadow$dom$dom_listen_remove_ie(el,ev,handler){
return el.detachEvent(["on",cljs.core.str.cljs$core$IFn$_invoke$arity$1(ev)].join(''),handler);
}));
shadow.dom.on_query = (function shadow$dom$on_query(root_el,ev,selector,handler){
var seq__30744 = cljs.core.seq(shadow.dom.query.cljs$core$IFn$_invoke$arity$2(selector,root_el));
var chunk__30745 = null;
var count__30746 = (0);
var i__30747 = (0);
while(true){
if((i__30747 < count__30746)){
var el = chunk__30745.cljs$core$IIndexed$_nth$arity$2(null, i__30747);
var handler_32581__$1 = ((function (seq__30744,chunk__30745,count__30746,i__30747,el){
return (function (e){
return (handler.cljs$core$IFn$_invoke$arity$2 ? handler.cljs$core$IFn$_invoke$arity$2(e,el) : handler.call(null, e,el));
});})(seq__30744,chunk__30745,count__30746,i__30747,el))
;
shadow.dom.dom_listen(el,cljs.core.name(ev),handler_32581__$1);


var G__32584 = seq__30744;
var G__32585 = chunk__30745;
var G__32586 = count__30746;
var G__32587 = (i__30747 + (1));
seq__30744 = G__32584;
chunk__30745 = G__32585;
count__30746 = G__32586;
i__30747 = G__32587;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__30744);
if(temp__5823__auto__){
var seq__30744__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__30744__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__30744__$1);
var G__32588 = cljs.core.chunk_rest(seq__30744__$1);
var G__32589 = c__5525__auto__;
var G__32590 = cljs.core.count(c__5525__auto__);
var G__32591 = (0);
seq__30744 = G__32588;
chunk__30745 = G__32589;
count__30746 = G__32590;
i__30747 = G__32591;
continue;
} else {
var el = cljs.core.first(seq__30744__$1);
var handler_32596__$1 = ((function (seq__30744,chunk__30745,count__30746,i__30747,el,seq__30744__$1,temp__5823__auto__){
return (function (e){
return (handler.cljs$core$IFn$_invoke$arity$2 ? handler.cljs$core$IFn$_invoke$arity$2(e,el) : handler.call(null, e,el));
});})(seq__30744,chunk__30745,count__30746,i__30747,el,seq__30744__$1,temp__5823__auto__))
;
shadow.dom.dom_listen(el,cljs.core.name(ev),handler_32596__$1);


var G__32600 = cljs.core.next(seq__30744__$1);
var G__32601 = null;
var G__32602 = (0);
var G__32603 = (0);
seq__30744 = G__32600;
chunk__30745 = G__32601;
count__30746 = G__32602;
i__30747 = G__32603;
continue;
}
} else {
return null;
}
}
break;
}
});
shadow.dom.on = (function shadow$dom$on(var_args){
var G__30802 = arguments.length;
switch (G__30802) {
case 3:
return shadow.dom.on.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
case 4:
return shadow.dom.on.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(shadow.dom.on.cljs$core$IFn$_invoke$arity$3 = (function (el,ev,handler){
return shadow.dom.on.cljs$core$IFn$_invoke$arity$4(el,ev,handler,false);
}));

(shadow.dom.on.cljs$core$IFn$_invoke$arity$4 = (function (el,ev,handler,capture){
if(cljs.core.vector_QMARK_(ev)){
return shadow.dom.on_query(el,cljs.core.first(ev),cljs.core.second(ev),handler);
} else {
var handler__$1 = (function (e){
return (handler.cljs$core$IFn$_invoke$arity$2 ? handler.cljs$core$IFn$_invoke$arity$2(e,el) : handler.call(null, e,el));
});
return shadow.dom.dom_listen(shadow.dom.dom_node(el),cljs.core.name(ev),handler__$1);
}
}));

(shadow.dom.on.cljs$lang$maxFixedArity = 4);

shadow.dom.remove_event_handler = (function shadow$dom$remove_event_handler(el,ev,handler){
return shadow.dom.dom_listen_remove(shadow.dom.dom_node(el),cljs.core.name(ev),handler);
});
shadow.dom.add_event_listeners = (function shadow$dom$add_event_listeners(el,events){
var seq__30818 = cljs.core.seq(events);
var chunk__30819 = null;
var count__30820 = (0);
var i__30821 = (0);
while(true){
if((i__30821 < count__30820)){
var vec__30831 = chunk__30819.cljs$core$IIndexed$_nth$arity$2(null, i__30821);
var k = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__30831,(0),null);
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__30831,(1),null);
shadow.dom.on.cljs$core$IFn$_invoke$arity$3(el,k,v);


var G__32610 = seq__30818;
var G__32611 = chunk__30819;
var G__32612 = count__30820;
var G__32613 = (i__30821 + (1));
seq__30818 = G__32610;
chunk__30819 = G__32611;
count__30820 = G__32612;
i__30821 = G__32613;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__30818);
if(temp__5823__auto__){
var seq__30818__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__30818__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__30818__$1);
var G__32615 = cljs.core.chunk_rest(seq__30818__$1);
var G__32616 = c__5525__auto__;
var G__32617 = cljs.core.count(c__5525__auto__);
var G__32618 = (0);
seq__30818 = G__32615;
chunk__30819 = G__32616;
count__30820 = G__32617;
i__30821 = G__32618;
continue;
} else {
var vec__30836 = cljs.core.first(seq__30818__$1);
var k = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__30836,(0),null);
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__30836,(1),null);
shadow.dom.on.cljs$core$IFn$_invoke$arity$3(el,k,v);


var G__32619 = cljs.core.next(seq__30818__$1);
var G__32621 = null;
var G__32622 = (0);
var G__32623 = (0);
seq__30818 = G__32619;
chunk__30819 = G__32621;
count__30820 = G__32622;
i__30821 = G__32623;
continue;
}
} else {
return null;
}
}
break;
}
});
shadow.dom.set_style = (function shadow$dom$set_style(el,styles){
var dom = shadow.dom.dom_node(el);
var seq__30853 = cljs.core.seq(styles);
var chunk__30854 = null;
var count__30855 = (0);
var i__30856 = (0);
while(true){
if((i__30856 < count__30855)){
var vec__30905 = chunk__30854.cljs$core$IIndexed$_nth$arity$2(null, i__30856);
var k = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__30905,(0),null);
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__30905,(1),null);
goog.style.setStyle(dom,cljs.core.name(k),(((v == null))?"":v));


var G__32626 = seq__30853;
var G__32627 = chunk__30854;
var G__32628 = count__30855;
var G__32629 = (i__30856 + (1));
seq__30853 = G__32626;
chunk__30854 = G__32627;
count__30855 = G__32628;
i__30856 = G__32629;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__30853);
if(temp__5823__auto__){
var seq__30853__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__30853__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__30853__$1);
var G__32631 = cljs.core.chunk_rest(seq__30853__$1);
var G__32632 = c__5525__auto__;
var G__32633 = cljs.core.count(c__5525__auto__);
var G__32634 = (0);
seq__30853 = G__32631;
chunk__30854 = G__32632;
count__30855 = G__32633;
i__30856 = G__32634;
continue;
} else {
var vec__30920 = cljs.core.first(seq__30853__$1);
var k = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__30920,(0),null);
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__30920,(1),null);
goog.style.setStyle(dom,cljs.core.name(k),(((v == null))?"":v));


var G__32636 = cljs.core.next(seq__30853__$1);
var G__32637 = null;
var G__32638 = (0);
var G__32639 = (0);
seq__30853 = G__32636;
chunk__30854 = G__32637;
count__30855 = G__32638;
i__30856 = G__32639;
continue;
}
} else {
return null;
}
}
break;
}
});
shadow.dom.set_attr_STAR_ = (function shadow$dom$set_attr_STAR_(el,key,value){
var G__30942_32640 = key;
var G__30942_32641__$1 = (((G__30942_32640 instanceof cljs.core.Keyword))?G__30942_32640.fqn:null);
switch (G__30942_32641__$1) {
case "id":
(el.id = cljs.core.str.cljs$core$IFn$_invoke$arity$1(value));

break;
case "class":
(el.className = cljs.core.str.cljs$core$IFn$_invoke$arity$1(value));

break;
case "for":
(el.htmlFor = value);

break;
case "cellpadding":
el.setAttribute("cellPadding",value);

break;
case "cellspacing":
el.setAttribute("cellSpacing",value);

break;
case "colspan":
el.setAttribute("colSpan",value);

break;
case "frameborder":
el.setAttribute("frameBorder",value);

break;
case "height":
el.setAttribute("height",value);

break;
case "maxlength":
el.setAttribute("maxLength",value);

break;
case "role":
el.setAttribute("role",value);

break;
case "rowspan":
el.setAttribute("rowSpan",value);

break;
case "type":
el.setAttribute("type",value);

break;
case "usemap":
el.setAttribute("useMap",value);

break;
case "valign":
el.setAttribute("vAlign",value);

break;
case "width":
el.setAttribute("width",value);

break;
case "on":
shadow.dom.add_event_listeners(el,value);

break;
case "style":
if((value == null)){
} else {
if(typeof value === 'string'){
el.setAttribute("style",value);
} else {
if(cljs.core.map_QMARK_(value)){
shadow.dom.set_style(el,value);
} else {
goog.style.setStyle(el,value);

}
}
}

break;
default:
var ks_32645 = cljs.core.name(key);
if(cljs.core.truth_((function (){var or__5002__auto__ = goog.string.startsWith(ks_32645,"data-");
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return goog.string.startsWith(ks_32645,"aria-");
}
})())){
el.setAttribute(ks_32645,value);
} else {
(el[ks_32645] = value);
}

}

return el;
});
shadow.dom.set_attrs = (function shadow$dom$set_attrs(el,attrs){
return cljs.core.reduce_kv((function (el__$1,key,value){
shadow.dom.set_attr_STAR_(el__$1,key,value);

return el__$1;
}),shadow.dom.dom_node(el),attrs);
});
shadow.dom.set_attr = (function shadow$dom$set_attr(el,key,value){
return shadow.dom.set_attr_STAR_(shadow.dom.dom_node(el),key,value);
});
shadow.dom.has_class_QMARK_ = (function shadow$dom$has_class_QMARK_(el,cls){
return goog.dom.classlist.contains(shadow.dom.dom_node(el),cls);
});
shadow.dom.merge_class_string = (function shadow$dom$merge_class_string(current,extra_class){
if(cljs.core.seq(current)){
return [cljs.core.str.cljs$core$IFn$_invoke$arity$1(current)," ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(extra_class)].join('');
} else {
return extra_class;
}
});
shadow.dom.parse_tag = (function shadow$dom$parse_tag(spec){
var spec__$1 = cljs.core.name(spec);
var fdot = spec__$1.indexOf(".");
var fhash = spec__$1.indexOf("#");
if(((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2((-1),fdot)) && (cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2((-1),fhash)))){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [spec__$1,null,null], null);
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2((-1),fhash)){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [spec__$1.substring((0),fdot),null,clojure.string.replace(spec__$1.substring((fdot + (1))),/\./," ")], null);
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2((-1),fdot)){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [spec__$1.substring((0),fhash),spec__$1.substring((fhash + (1))),null], null);
} else {
if((fhash > fdot)){
throw ["cant have id after class?",spec__$1].join('');
} else {
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [spec__$1.substring((0),fhash),spec__$1.substring((fhash + (1)),fdot),clojure.string.replace(spec__$1.substring((fdot + (1))),/\./," ")], null);

}
}
}
}
});
shadow.dom.create_dom_node = (function shadow$dom$create_dom_node(tag_def,p__30991){
var map__30992 = p__30991;
var map__30992__$1 = cljs.core.__destructure_map(map__30992);
var props = map__30992__$1;
var class$ = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__30992__$1,new cljs.core.Keyword(null,"class","class",-2030961996));
var tag_props = ({});
var vec__30998 = shadow.dom.parse_tag(tag_def);
var tag_name = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__30998,(0),null);
var tag_id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__30998,(1),null);
var tag_classes = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__30998,(2),null);
if(cljs.core.truth_(tag_id)){
(tag_props["id"] = tag_id);
} else {
}

if(cljs.core.truth_(tag_classes)){
(tag_props["class"] = shadow.dom.merge_class_string(class$,tag_classes));
} else {
}

var G__31008 = goog.dom.createDom(tag_name,tag_props);
shadow.dom.set_attrs(G__31008,cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(props,new cljs.core.Keyword(null,"class","class",-2030961996)));

return G__31008;
});
shadow.dom.append = (function shadow$dom$append(var_args){
var G__31018 = arguments.length;
switch (G__31018) {
case 1:
return shadow.dom.append.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.append.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(shadow.dom.append.cljs$core$IFn$_invoke$arity$1 = (function (node){
if(cljs.core.truth_(node)){
var temp__5823__auto__ = shadow.dom.dom_node(node);
if(cljs.core.truth_(temp__5823__auto__)){
var n = temp__5823__auto__;
document.body.appendChild(n);

return n;
} else {
return null;
}
} else {
return null;
}
}));

(shadow.dom.append.cljs$core$IFn$_invoke$arity$2 = (function (el,node){
if(cljs.core.truth_(node)){
var temp__5823__auto__ = shadow.dom.dom_node(node);
if(cljs.core.truth_(temp__5823__auto__)){
var n = temp__5823__auto__;
shadow.dom.dom_node(el).appendChild(n);

return n;
} else {
return null;
}
} else {
return null;
}
}));

(shadow.dom.append.cljs$lang$maxFixedArity = 2);

shadow.dom.destructure_node = (function shadow$dom$destructure_node(create_fn,p__31058){
var vec__31059 = p__31058;
var seq__31060 = cljs.core.seq(vec__31059);
var first__31061 = cljs.core.first(seq__31060);
var seq__31060__$1 = cljs.core.next(seq__31060);
var nn = first__31061;
var first__31061__$1 = cljs.core.first(seq__31060__$1);
var seq__31060__$2 = cljs.core.next(seq__31060__$1);
var np = first__31061__$1;
var nc = seq__31060__$2;
var node = vec__31059;
if((nn instanceof cljs.core.Keyword)){
} else {
throw cljs.core.ex_info.cljs$core$IFn$_invoke$arity$2("invalid dom node",new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"node","node",581201198),node], null));
}

if((((np == null)) && ((nc == null)))){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(function (){var G__31067 = nn;
var G__31068 = cljs.core.PersistentArrayMap.EMPTY;
return (create_fn.cljs$core$IFn$_invoke$arity$2 ? create_fn.cljs$core$IFn$_invoke$arity$2(G__31067,G__31068) : create_fn.call(null, G__31067,G__31068));
})(),cljs.core.List.EMPTY], null);
} else {
if(cljs.core.map_QMARK_(np)){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(create_fn.cljs$core$IFn$_invoke$arity$2 ? create_fn.cljs$core$IFn$_invoke$arity$2(nn,np) : create_fn.call(null, nn,np)),nc], null);
} else {
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(function (){var G__31120 = nn;
var G__31121 = cljs.core.PersistentArrayMap.EMPTY;
return (create_fn.cljs$core$IFn$_invoke$arity$2 ? create_fn.cljs$core$IFn$_invoke$arity$2(G__31120,G__31121) : create_fn.call(null, G__31120,G__31121));
})(),cljs.core.conj.cljs$core$IFn$_invoke$arity$2(nc,np)], null);

}
}
});
shadow.dom.make_dom_node = (function shadow$dom$make_dom_node(structure){
var vec__31124 = shadow.dom.destructure_node(shadow.dom.create_dom_node,structure);
var node = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31124,(0),null);
var node_children = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31124,(1),null);
var seq__31127_32657 = cljs.core.seq(node_children);
var chunk__31128_32658 = null;
var count__31129_32659 = (0);
var i__31130_32660 = (0);
while(true){
if((i__31130_32660 < count__31129_32659)){
var child_struct_32661 = chunk__31128_32658.cljs$core$IIndexed$_nth$arity$2(null, i__31130_32660);
var children_32663 = shadow.dom.dom_node(child_struct_32661);
if(cljs.core.seq_QMARK_(children_32663)){
var seq__31198_32664 = cljs.core.seq(cljs.core.map.cljs$core$IFn$_invoke$arity$2(shadow.dom.dom_node,children_32663));
var chunk__31200_32665 = null;
var count__31201_32666 = (0);
var i__31202_32667 = (0);
while(true){
if((i__31202_32667 < count__31201_32666)){
var child_32668 = chunk__31200_32665.cljs$core$IIndexed$_nth$arity$2(null, i__31202_32667);
if(cljs.core.truth_(child_32668)){
shadow.dom.append.cljs$core$IFn$_invoke$arity$2(node,child_32668);


var G__32669 = seq__31198_32664;
var G__32670 = chunk__31200_32665;
var G__32671 = count__31201_32666;
var G__32672 = (i__31202_32667 + (1));
seq__31198_32664 = G__32669;
chunk__31200_32665 = G__32670;
count__31201_32666 = G__32671;
i__31202_32667 = G__32672;
continue;
} else {
var G__32673 = seq__31198_32664;
var G__32674 = chunk__31200_32665;
var G__32675 = count__31201_32666;
var G__32676 = (i__31202_32667 + (1));
seq__31198_32664 = G__32673;
chunk__31200_32665 = G__32674;
count__31201_32666 = G__32675;
i__31202_32667 = G__32676;
continue;
}
} else {
var temp__5823__auto___32678 = cljs.core.seq(seq__31198_32664);
if(temp__5823__auto___32678){
var seq__31198_32680__$1 = temp__5823__auto___32678;
if(cljs.core.chunked_seq_QMARK_(seq__31198_32680__$1)){
var c__5525__auto___32683 = cljs.core.chunk_first(seq__31198_32680__$1);
var G__32684 = cljs.core.chunk_rest(seq__31198_32680__$1);
var G__32685 = c__5525__auto___32683;
var G__32686 = cljs.core.count(c__5525__auto___32683);
var G__32687 = (0);
seq__31198_32664 = G__32684;
chunk__31200_32665 = G__32685;
count__31201_32666 = G__32686;
i__31202_32667 = G__32687;
continue;
} else {
var child_32690 = cljs.core.first(seq__31198_32680__$1);
if(cljs.core.truth_(child_32690)){
shadow.dom.append.cljs$core$IFn$_invoke$arity$2(node,child_32690);


var G__32691 = cljs.core.next(seq__31198_32680__$1);
var G__32692 = null;
var G__32693 = (0);
var G__32694 = (0);
seq__31198_32664 = G__32691;
chunk__31200_32665 = G__32692;
count__31201_32666 = G__32693;
i__31202_32667 = G__32694;
continue;
} else {
var G__32695 = cljs.core.next(seq__31198_32680__$1);
var G__32696 = null;
var G__32697 = (0);
var G__32698 = (0);
seq__31198_32664 = G__32695;
chunk__31200_32665 = G__32696;
count__31201_32666 = G__32697;
i__31202_32667 = G__32698;
continue;
}
}
} else {
}
}
break;
}
} else {
shadow.dom.append.cljs$core$IFn$_invoke$arity$2(node,children_32663);
}


var G__32701 = seq__31127_32657;
var G__32702 = chunk__31128_32658;
var G__32703 = count__31129_32659;
var G__32704 = (i__31130_32660 + (1));
seq__31127_32657 = G__32701;
chunk__31128_32658 = G__32702;
count__31129_32659 = G__32703;
i__31130_32660 = G__32704;
continue;
} else {
var temp__5823__auto___32706 = cljs.core.seq(seq__31127_32657);
if(temp__5823__auto___32706){
var seq__31127_32707__$1 = temp__5823__auto___32706;
if(cljs.core.chunked_seq_QMARK_(seq__31127_32707__$1)){
var c__5525__auto___32708 = cljs.core.chunk_first(seq__31127_32707__$1);
var G__32709 = cljs.core.chunk_rest(seq__31127_32707__$1);
var G__32710 = c__5525__auto___32708;
var G__32711 = cljs.core.count(c__5525__auto___32708);
var G__32712 = (0);
seq__31127_32657 = G__32709;
chunk__31128_32658 = G__32710;
count__31129_32659 = G__32711;
i__31130_32660 = G__32712;
continue;
} else {
var child_struct_32713 = cljs.core.first(seq__31127_32707__$1);
var children_32714 = shadow.dom.dom_node(child_struct_32713);
if(cljs.core.seq_QMARK_(children_32714)){
var seq__31248_32715 = cljs.core.seq(cljs.core.map.cljs$core$IFn$_invoke$arity$2(shadow.dom.dom_node,children_32714));
var chunk__31250_32716 = null;
var count__31251_32717 = (0);
var i__31252_32718 = (0);
while(true){
if((i__31252_32718 < count__31251_32717)){
var child_32719 = chunk__31250_32716.cljs$core$IIndexed$_nth$arity$2(null, i__31252_32718);
if(cljs.core.truth_(child_32719)){
shadow.dom.append.cljs$core$IFn$_invoke$arity$2(node,child_32719);


var G__32720 = seq__31248_32715;
var G__32721 = chunk__31250_32716;
var G__32722 = count__31251_32717;
var G__32723 = (i__31252_32718 + (1));
seq__31248_32715 = G__32720;
chunk__31250_32716 = G__32721;
count__31251_32717 = G__32722;
i__31252_32718 = G__32723;
continue;
} else {
var G__32724 = seq__31248_32715;
var G__32725 = chunk__31250_32716;
var G__32726 = count__31251_32717;
var G__32727 = (i__31252_32718 + (1));
seq__31248_32715 = G__32724;
chunk__31250_32716 = G__32725;
count__31251_32717 = G__32726;
i__31252_32718 = G__32727;
continue;
}
} else {
var temp__5823__auto___32728__$1 = cljs.core.seq(seq__31248_32715);
if(temp__5823__auto___32728__$1){
var seq__31248_32730__$1 = temp__5823__auto___32728__$1;
if(cljs.core.chunked_seq_QMARK_(seq__31248_32730__$1)){
var c__5525__auto___32731 = cljs.core.chunk_first(seq__31248_32730__$1);
var G__32733 = cljs.core.chunk_rest(seq__31248_32730__$1);
var G__32734 = c__5525__auto___32731;
var G__32735 = cljs.core.count(c__5525__auto___32731);
var G__32736 = (0);
seq__31248_32715 = G__32733;
chunk__31250_32716 = G__32734;
count__31251_32717 = G__32735;
i__31252_32718 = G__32736;
continue;
} else {
var child_32737 = cljs.core.first(seq__31248_32730__$1);
if(cljs.core.truth_(child_32737)){
shadow.dom.append.cljs$core$IFn$_invoke$arity$2(node,child_32737);


var G__32745 = cljs.core.next(seq__31248_32730__$1);
var G__32746 = null;
var G__32747 = (0);
var G__32748 = (0);
seq__31248_32715 = G__32745;
chunk__31250_32716 = G__32746;
count__31251_32717 = G__32747;
i__31252_32718 = G__32748;
continue;
} else {
var G__32749 = cljs.core.next(seq__31248_32730__$1);
var G__32750 = null;
var G__32751 = (0);
var G__32752 = (0);
seq__31248_32715 = G__32749;
chunk__31250_32716 = G__32750;
count__31251_32717 = G__32751;
i__31252_32718 = G__32752;
continue;
}
}
} else {
}
}
break;
}
} else {
shadow.dom.append.cljs$core$IFn$_invoke$arity$2(node,children_32714);
}


var G__32753 = cljs.core.next(seq__31127_32707__$1);
var G__32754 = null;
var G__32755 = (0);
var G__32756 = (0);
seq__31127_32657 = G__32753;
chunk__31128_32658 = G__32754;
count__31129_32659 = G__32755;
i__31130_32660 = G__32756;
continue;
}
} else {
}
}
break;
}

return node;
});
(cljs.core.Keyword.prototype.shadow$dom$IElement$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.Keyword.prototype.shadow$dom$IElement$_to_dom$arity$1 = (function (this$){
var this$__$1 = this;
return shadow.dom.make_dom_node(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [this$__$1], null));
}));

(cljs.core.PersistentVector.prototype.shadow$dom$IElement$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.PersistentVector.prototype.shadow$dom$IElement$_to_dom$arity$1 = (function (this$){
var this$__$1 = this;
return shadow.dom.make_dom_node(this$__$1);
}));

(cljs.core.LazySeq.prototype.shadow$dom$IElement$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.LazySeq.prototype.shadow$dom$IElement$_to_dom$arity$1 = (function (this$){
var this$__$1 = this;
return cljs.core.map.cljs$core$IFn$_invoke$arity$2(shadow.dom._to_dom,this$__$1);
}));
if(cljs.core.truth_(((typeof HTMLElement) != 'undefined'))){
(HTMLElement.prototype.shadow$dom$IElement$ = cljs.core.PROTOCOL_SENTINEL);

(HTMLElement.prototype.shadow$dom$IElement$_to_dom$arity$1 = (function (this$){
var this$__$1 = this;
return this$__$1;
}));
} else {
}
if(cljs.core.truth_(((typeof DocumentFragment) != 'undefined'))){
(DocumentFragment.prototype.shadow$dom$IElement$ = cljs.core.PROTOCOL_SENTINEL);

(DocumentFragment.prototype.shadow$dom$IElement$_to_dom$arity$1 = (function (this$){
var this$__$1 = this;
return this$__$1;
}));
} else {
}
/**
 * clear node children
 */
shadow.dom.reset = (function shadow$dom$reset(node){
return goog.dom.removeChildren(shadow.dom.dom_node(node));
});
shadow.dom.remove = (function shadow$dom$remove(node){
if((((!((node == null))))?(((((node.cljs$lang$protocol_mask$partition0$ & (8388608))) || ((cljs.core.PROTOCOL_SENTINEL === node.cljs$core$ISeqable$))))?true:false):false)){
var seq__31347 = cljs.core.seq(node);
var chunk__31348 = null;
var count__31349 = (0);
var i__31350 = (0);
while(true){
if((i__31350 < count__31349)){
var n = chunk__31348.cljs$core$IIndexed$_nth$arity$2(null, i__31350);
(shadow.dom.remove.cljs$core$IFn$_invoke$arity$1 ? shadow.dom.remove.cljs$core$IFn$_invoke$arity$1(n) : shadow.dom.remove.call(null, n));


var G__32757 = seq__31347;
var G__32758 = chunk__31348;
var G__32759 = count__31349;
var G__32760 = (i__31350 + (1));
seq__31347 = G__32757;
chunk__31348 = G__32758;
count__31349 = G__32759;
i__31350 = G__32760;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__31347);
if(temp__5823__auto__){
var seq__31347__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__31347__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__31347__$1);
var G__32761 = cljs.core.chunk_rest(seq__31347__$1);
var G__32762 = c__5525__auto__;
var G__32763 = cljs.core.count(c__5525__auto__);
var G__32764 = (0);
seq__31347 = G__32761;
chunk__31348 = G__32762;
count__31349 = G__32763;
i__31350 = G__32764;
continue;
} else {
var n = cljs.core.first(seq__31347__$1);
(shadow.dom.remove.cljs$core$IFn$_invoke$arity$1 ? shadow.dom.remove.cljs$core$IFn$_invoke$arity$1(n) : shadow.dom.remove.call(null, n));


var G__32766 = cljs.core.next(seq__31347__$1);
var G__32767 = null;
var G__32768 = (0);
var G__32769 = (0);
seq__31347 = G__32766;
chunk__31348 = G__32767;
count__31349 = G__32768;
i__31350 = G__32769;
continue;
}
} else {
return null;
}
}
break;
}
} else {
return goog.dom.removeNode(node);
}
});
shadow.dom.replace_node = (function shadow$dom$replace_node(old,new$){
return goog.dom.replaceNode(shadow.dom.dom_node(new$),shadow.dom.dom_node(old));
});
shadow.dom.text = (function shadow$dom$text(var_args){
var G__31410 = arguments.length;
switch (G__31410) {
case 2:
return shadow.dom.text.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 1:
return shadow.dom.text.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(shadow.dom.text.cljs$core$IFn$_invoke$arity$2 = (function (el,new_text){
return (shadow.dom.dom_node(el).innerText = new_text);
}));

(shadow.dom.text.cljs$core$IFn$_invoke$arity$1 = (function (el){
return shadow.dom.dom_node(el).innerText;
}));

(shadow.dom.text.cljs$lang$maxFixedArity = 2);

shadow.dom.check = (function shadow$dom$check(var_args){
var G__31426 = arguments.length;
switch (G__31426) {
case 1:
return shadow.dom.check.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.check.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(shadow.dom.check.cljs$core$IFn$_invoke$arity$1 = (function (el){
return shadow.dom.check.cljs$core$IFn$_invoke$arity$2(el,true);
}));

(shadow.dom.check.cljs$core$IFn$_invoke$arity$2 = (function (el,checked){
return (shadow.dom.dom_node(el).checked = checked);
}));

(shadow.dom.check.cljs$lang$maxFixedArity = 2);

shadow.dom.checked_QMARK_ = (function shadow$dom$checked_QMARK_(el){
return shadow.dom.dom_node(el).checked;
});
shadow.dom.form_elements = (function shadow$dom$form_elements(el){
return (new shadow.dom.NativeColl(shadow.dom.dom_node(el).elements));
});
shadow.dom.children = (function shadow$dom$children(el){
return (new shadow.dom.NativeColl(shadow.dom.dom_node(el).children));
});
shadow.dom.child_nodes = (function shadow$dom$child_nodes(el){
return (new shadow.dom.NativeColl(shadow.dom.dom_node(el).childNodes));
});
shadow.dom.attr = (function shadow$dom$attr(var_args){
var G__31528 = arguments.length;
switch (G__31528) {
case 2:
return shadow.dom.attr.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return shadow.dom.attr.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(shadow.dom.attr.cljs$core$IFn$_invoke$arity$2 = (function (el,key){
return shadow.dom.dom_node(el).getAttribute(cljs.core.name(key));
}));

(shadow.dom.attr.cljs$core$IFn$_invoke$arity$3 = (function (el,key,default$){
var or__5002__auto__ = shadow.dom.dom_node(el).getAttribute(cljs.core.name(key));
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return default$;
}
}));

(shadow.dom.attr.cljs$lang$maxFixedArity = 3);

shadow.dom.del_attr = (function shadow$dom$del_attr(el,key){
return shadow.dom.dom_node(el).removeAttribute(cljs.core.name(key));
});
shadow.dom.data = (function shadow$dom$data(el,key){
return shadow.dom.dom_node(el).getAttribute(["data-",cljs.core.name(key)].join(''));
});
shadow.dom.set_data = (function shadow$dom$set_data(el,key,value){
return shadow.dom.dom_node(el).setAttribute(["data-",cljs.core.name(key)].join(''),cljs.core.str.cljs$core$IFn$_invoke$arity$1(value));
});
shadow.dom.set_html = (function shadow$dom$set_html(node,text){
return (shadow.dom.dom_node(node).innerHTML = text);
});
shadow.dom.get_html = (function shadow$dom$get_html(node){
return shadow.dom.dom_node(node).innerHTML;
});
shadow.dom.fragment = (function shadow$dom$fragment(var_args){
var args__5732__auto__ = [];
var len__5726__auto___32800 = arguments.length;
var i__5727__auto___32801 = (0);
while(true){
if((i__5727__auto___32801 < len__5726__auto___32800)){
args__5732__auto__.push((arguments[i__5727__auto___32801]));

var G__32802 = (i__5727__auto___32801 + (1));
i__5727__auto___32801 = G__32802;
continue;
} else {
}
break;
}

var argseq__5733__auto__ = ((((0) < args__5732__auto__.length))?(new cljs.core.IndexedSeq(args__5732__auto__.slice((0)),(0),null)):null);
return shadow.dom.fragment.cljs$core$IFn$_invoke$arity$variadic(argseq__5733__auto__);
});

(shadow.dom.fragment.cljs$core$IFn$_invoke$arity$variadic = (function (nodes){
var fragment = document.createDocumentFragment();
var seq__31581_32803 = cljs.core.seq(nodes);
var chunk__31582_32804 = null;
var count__31583_32805 = (0);
var i__31584_32806 = (0);
while(true){
if((i__31584_32806 < count__31583_32805)){
var node_32810 = chunk__31582_32804.cljs$core$IIndexed$_nth$arity$2(null, i__31584_32806);
fragment.appendChild(shadow.dom._to_dom(node_32810));


var G__32812 = seq__31581_32803;
var G__32813 = chunk__31582_32804;
var G__32814 = count__31583_32805;
var G__32815 = (i__31584_32806 + (1));
seq__31581_32803 = G__32812;
chunk__31582_32804 = G__32813;
count__31583_32805 = G__32814;
i__31584_32806 = G__32815;
continue;
} else {
var temp__5823__auto___32816 = cljs.core.seq(seq__31581_32803);
if(temp__5823__auto___32816){
var seq__31581_32817__$1 = temp__5823__auto___32816;
if(cljs.core.chunked_seq_QMARK_(seq__31581_32817__$1)){
var c__5525__auto___32821 = cljs.core.chunk_first(seq__31581_32817__$1);
var G__32822 = cljs.core.chunk_rest(seq__31581_32817__$1);
var G__32823 = c__5525__auto___32821;
var G__32824 = cljs.core.count(c__5525__auto___32821);
var G__32825 = (0);
seq__31581_32803 = G__32822;
chunk__31582_32804 = G__32823;
count__31583_32805 = G__32824;
i__31584_32806 = G__32825;
continue;
} else {
var node_32827 = cljs.core.first(seq__31581_32817__$1);
fragment.appendChild(shadow.dom._to_dom(node_32827));


var G__32829 = cljs.core.next(seq__31581_32817__$1);
var G__32830 = null;
var G__32831 = (0);
var G__32832 = (0);
seq__31581_32803 = G__32829;
chunk__31582_32804 = G__32830;
count__31583_32805 = G__32831;
i__31584_32806 = G__32832;
continue;
}
} else {
}
}
break;
}

return (new shadow.dom.NativeColl(fragment));
}));

(shadow.dom.fragment.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(shadow.dom.fragment.cljs$lang$applyTo = (function (seq31575){
var self__5712__auto__ = this;
return self__5712__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq31575));
}));

/**
 * given a html string, eval all <script> tags and return the html without the scripts
 * don't do this for everything, only content you trust.
 */
shadow.dom.eval_scripts = (function shadow$dom$eval_scripts(s){
var scripts = cljs.core.re_seq(/<script[^>]*?>(.+?)<\/script>/,s);
var seq__31634_32833 = cljs.core.seq(scripts);
var chunk__31635_32834 = null;
var count__31636_32835 = (0);
var i__31637_32836 = (0);
while(true){
if((i__31637_32836 < count__31636_32835)){
var vec__31660_32837 = chunk__31635_32834.cljs$core$IIndexed$_nth$arity$2(null, i__31637_32836);
var script_tag_32838 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31660_32837,(0),null);
var script_body_32839 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31660_32837,(1),null);
eval(script_body_32839);


var G__32841 = seq__31634_32833;
var G__32842 = chunk__31635_32834;
var G__32843 = count__31636_32835;
var G__32844 = (i__31637_32836 + (1));
seq__31634_32833 = G__32841;
chunk__31635_32834 = G__32842;
count__31636_32835 = G__32843;
i__31637_32836 = G__32844;
continue;
} else {
var temp__5823__auto___32845 = cljs.core.seq(seq__31634_32833);
if(temp__5823__auto___32845){
var seq__31634_32846__$1 = temp__5823__auto___32845;
if(cljs.core.chunked_seq_QMARK_(seq__31634_32846__$1)){
var c__5525__auto___32847 = cljs.core.chunk_first(seq__31634_32846__$1);
var G__32848 = cljs.core.chunk_rest(seq__31634_32846__$1);
var G__32849 = c__5525__auto___32847;
var G__32850 = cljs.core.count(c__5525__auto___32847);
var G__32851 = (0);
seq__31634_32833 = G__32848;
chunk__31635_32834 = G__32849;
count__31636_32835 = G__32850;
i__31637_32836 = G__32851;
continue;
} else {
var vec__31671_32852 = cljs.core.first(seq__31634_32846__$1);
var script_tag_32853 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31671_32852,(0),null);
var script_body_32854 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31671_32852,(1),null);
eval(script_body_32854);


var G__32857 = cljs.core.next(seq__31634_32846__$1);
var G__32858 = null;
var G__32859 = (0);
var G__32860 = (0);
seq__31634_32833 = G__32857;
chunk__31635_32834 = G__32858;
count__31636_32835 = G__32859;
i__31637_32836 = G__32860;
continue;
}
} else {
}
}
break;
}

return cljs.core.reduce.cljs$core$IFn$_invoke$arity$3((function (s__$1,p__31678){
var vec__31679 = p__31678;
var script_tag = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31679,(0),null);
var script_body = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31679,(1),null);
return clojure.string.replace(s__$1,script_tag,"");
}),s,scripts);
});
shadow.dom.str__GT_fragment = (function shadow$dom$str__GT_fragment(s){
var el = document.createElement("div");
(el.innerHTML = s);

return (new shadow.dom.NativeColl(goog.dom.childrenToNode_(document,el)));
});
shadow.dom.node_name = (function shadow$dom$node_name(el){
return shadow.dom.dom_node(el).nodeName;
});
shadow.dom.ancestor_by_class = (function shadow$dom$ancestor_by_class(el,cls){
return goog.dom.getAncestorByClass(shadow.dom.dom_node(el),cls);
});
shadow.dom.ancestor_by_tag = (function shadow$dom$ancestor_by_tag(var_args){
var G__31694 = arguments.length;
switch (G__31694) {
case 2:
return shadow.dom.ancestor_by_tag.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return shadow.dom.ancestor_by_tag.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(shadow.dom.ancestor_by_tag.cljs$core$IFn$_invoke$arity$2 = (function (el,tag){
return goog.dom.getAncestorByTagNameAndClass(shadow.dom.dom_node(el),cljs.core.name(tag));
}));

(shadow.dom.ancestor_by_tag.cljs$core$IFn$_invoke$arity$3 = (function (el,tag,cls){
return goog.dom.getAncestorByTagNameAndClass(shadow.dom.dom_node(el),cljs.core.name(tag),cljs.core.name(cls));
}));

(shadow.dom.ancestor_by_tag.cljs$lang$maxFixedArity = 3);

shadow.dom.get_value = (function shadow$dom$get_value(dom){
return goog.dom.forms.getValue(shadow.dom.dom_node(dom));
});
shadow.dom.set_value = (function shadow$dom$set_value(dom,value){
return goog.dom.forms.setValue(shadow.dom.dom_node(dom),value);
});
shadow.dom.px = (function shadow$dom$px(value){
return [cljs.core.str.cljs$core$IFn$_invoke$arity$1((value | (0))),"px"].join('');
});
shadow.dom.pct = (function shadow$dom$pct(value){
return [cljs.core.str.cljs$core$IFn$_invoke$arity$1(value),"%"].join('');
});
shadow.dom.remove_style_STAR_ = (function shadow$dom$remove_style_STAR_(el,style){
return el.style.removeProperty(cljs.core.name(style));
});
shadow.dom.remove_style = (function shadow$dom$remove_style(el,style){
var el__$1 = shadow.dom.dom_node(el);
return shadow.dom.remove_style_STAR_(el__$1,style);
});
shadow.dom.remove_styles = (function shadow$dom$remove_styles(el,style_keys){
var el__$1 = shadow.dom.dom_node(el);
var seq__31703 = cljs.core.seq(style_keys);
var chunk__31704 = null;
var count__31705 = (0);
var i__31706 = (0);
while(true){
if((i__31706 < count__31705)){
var it = chunk__31704.cljs$core$IIndexed$_nth$arity$2(null, i__31706);
shadow.dom.remove_style_STAR_(el__$1,it);


var G__32876 = seq__31703;
var G__32877 = chunk__31704;
var G__32878 = count__31705;
var G__32879 = (i__31706 + (1));
seq__31703 = G__32876;
chunk__31704 = G__32877;
count__31705 = G__32878;
i__31706 = G__32879;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__31703);
if(temp__5823__auto__){
var seq__31703__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__31703__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__31703__$1);
var G__32880 = cljs.core.chunk_rest(seq__31703__$1);
var G__32881 = c__5525__auto__;
var G__32882 = cljs.core.count(c__5525__auto__);
var G__32883 = (0);
seq__31703 = G__32880;
chunk__31704 = G__32881;
count__31705 = G__32882;
i__31706 = G__32883;
continue;
} else {
var it = cljs.core.first(seq__31703__$1);
shadow.dom.remove_style_STAR_(el__$1,it);


var G__32885 = cljs.core.next(seq__31703__$1);
var G__32886 = null;
var G__32887 = (0);
var G__32888 = (0);
seq__31703 = G__32885;
chunk__31704 = G__32886;
count__31705 = G__32887;
i__31706 = G__32888;
continue;
}
} else {
return null;
}
}
break;
}
});

/**
* @constructor
 * @implements {cljs.core.IRecord}
 * @implements {cljs.core.IKVReduce}
 * @implements {cljs.core.IEquiv}
 * @implements {cljs.core.IHash}
 * @implements {cljs.core.ICollection}
 * @implements {cljs.core.ICounted}
 * @implements {cljs.core.ISeqable}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.ICloneable}
 * @implements {cljs.core.IPrintWithWriter}
 * @implements {cljs.core.IIterable}
 * @implements {cljs.core.IWithMeta}
 * @implements {cljs.core.IAssociative}
 * @implements {cljs.core.IMap}
 * @implements {cljs.core.ILookup}
*/
shadow.dom.Coordinate = (function (x,y,__meta,__extmap,__hash){
this.x = x;
this.y = y;
this.__meta = __meta;
this.__extmap = __extmap;
this.__hash = __hash;
this.cljs$lang$protocol_mask$partition0$ = 2230716170;
this.cljs$lang$protocol_mask$partition1$ = 139264;
});
(shadow.dom.Coordinate.prototype.cljs$core$ILookup$_lookup$arity$2 = (function (this__5300__auto__,k__5301__auto__){
var self__ = this;
var this__5300__auto____$1 = this;
return this__5300__auto____$1.cljs$core$ILookup$_lookup$arity$3(null, k__5301__auto__,null);
}));

(shadow.dom.Coordinate.prototype.cljs$core$ILookup$_lookup$arity$3 = (function (this__5302__auto__,k31720,else__5303__auto__){
var self__ = this;
var this__5302__auto____$1 = this;
var G__31735 = k31720;
var G__31735__$1 = (((G__31735 instanceof cljs.core.Keyword))?G__31735.fqn:null);
switch (G__31735__$1) {
case "x":
return self__.x;

break;
case "y":
return self__.y;

break;
default:
return cljs.core.get.cljs$core$IFn$_invoke$arity$3(self__.__extmap,k31720,else__5303__auto__);

}
}));

(shadow.dom.Coordinate.prototype.cljs$core$IKVReduce$_kv_reduce$arity$3 = (function (this__5320__auto__,f__5321__auto__,init__5322__auto__){
var self__ = this;
var this__5320__auto____$1 = this;
return cljs.core.reduce.cljs$core$IFn$_invoke$arity$3((function (ret__5323__auto__,p__31742){
var vec__31743 = p__31742;
var k__5324__auto__ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31743,(0),null);
var v__5325__auto__ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31743,(1),null);
return (f__5321__auto__.cljs$core$IFn$_invoke$arity$3 ? f__5321__auto__.cljs$core$IFn$_invoke$arity$3(ret__5323__auto__,k__5324__auto__,v__5325__auto__) : f__5321__auto__.call(null, ret__5323__auto__,k__5324__auto__,v__5325__auto__));
}),init__5322__auto__,this__5320__auto____$1);
}));

(shadow.dom.Coordinate.prototype.cljs$core$IPrintWithWriter$_pr_writer$arity$3 = (function (this__5315__auto__,writer__5316__auto__,opts__5317__auto__){
var self__ = this;
var this__5315__auto____$1 = this;
var pr_pair__5318__auto__ = (function (keyval__5319__auto__){
return cljs.core.pr_sequential_writer(writer__5316__auto__,cljs.core.pr_writer,""," ","",opts__5317__auto__,keyval__5319__auto__);
});
return cljs.core.pr_sequential_writer(writer__5316__auto__,pr_pair__5318__auto__,"#shadow.dom.Coordinate{",", ","}",opts__5317__auto__,cljs.core.concat.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(new cljs.core.PersistentVector(null,2,(5),cljs.core.PersistentVector.EMPTY_NODE,[new cljs.core.Keyword(null,"x","x",2099068185),self__.x],null)),(new cljs.core.PersistentVector(null,2,(5),cljs.core.PersistentVector.EMPTY_NODE,[new cljs.core.Keyword(null,"y","y",-1757859776),self__.y],null))], null),self__.__extmap));
}));

(shadow.dom.Coordinate.prototype.cljs$core$IIterable$_iterator$arity$1 = (function (G__31719){
var self__ = this;
var G__31719__$1 = this;
return (new cljs.core.RecordIter((0),G__31719__$1,2,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"x","x",2099068185),new cljs.core.Keyword(null,"y","y",-1757859776)], null),(cljs.core.truth_(self__.__extmap)?cljs.core._iterator(self__.__extmap):cljs.core.nil_iter())));
}));

(shadow.dom.Coordinate.prototype.cljs$core$IMeta$_meta$arity$1 = (function (this__5298__auto__){
var self__ = this;
var this__5298__auto____$1 = this;
return self__.__meta;
}));

(shadow.dom.Coordinate.prototype.cljs$core$ICloneable$_clone$arity$1 = (function (this__5295__auto__){
var self__ = this;
var this__5295__auto____$1 = this;
return (new shadow.dom.Coordinate(self__.x,self__.y,self__.__meta,self__.__extmap,self__.__hash));
}));

(shadow.dom.Coordinate.prototype.cljs$core$ICounted$_count$arity$1 = (function (this__5304__auto__){
var self__ = this;
var this__5304__auto____$1 = this;
return (2 + cljs.core.count(self__.__extmap));
}));

(shadow.dom.Coordinate.prototype.cljs$core$IHash$_hash$arity$1 = (function (this__5296__auto__){
var self__ = this;
var this__5296__auto____$1 = this;
var h__5111__auto__ = self__.__hash;
if((!((h__5111__auto__ == null)))){
return h__5111__auto__;
} else {
var h__5111__auto____$1 = (function (coll__5297__auto__){
return (145542109 ^ cljs.core.hash_unordered_coll(coll__5297__auto__));
})(this__5296__auto____$1);
(self__.__hash = h__5111__auto____$1);

return h__5111__auto____$1;
}
}));

(shadow.dom.Coordinate.prototype.cljs$core$IEquiv$_equiv$arity$2 = (function (this31721,other31722){
var self__ = this;
var this31721__$1 = this;
return (((!((other31722 == null)))) && ((((this31721__$1.constructor === other31722.constructor)) && (((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(this31721__$1.x,other31722.x)) && (((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(this31721__$1.y,other31722.y)) && (cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(this31721__$1.__extmap,other31722.__extmap)))))))));
}));

(shadow.dom.Coordinate.prototype.cljs$core$IMap$_dissoc$arity$2 = (function (this__5310__auto__,k__5311__auto__){
var self__ = this;
var this__5310__auto____$1 = this;
if(cljs.core.contains_QMARK_(new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"y","y",-1757859776),null,new cljs.core.Keyword(null,"x","x",2099068185),null], null), null),k__5311__auto__)){
return cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(cljs.core._with_meta(cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentArrayMap.EMPTY,this__5310__auto____$1),self__.__meta),k__5311__auto__);
} else {
return (new shadow.dom.Coordinate(self__.x,self__.y,self__.__meta,cljs.core.not_empty(cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(self__.__extmap,k__5311__auto__)),null));
}
}));

(shadow.dom.Coordinate.prototype.cljs$core$IAssociative$_contains_key_QMARK_$arity$2 = (function (this__5307__auto__,k31720){
var self__ = this;
var this__5307__auto____$1 = this;
var G__31768 = k31720;
var G__31768__$1 = (((G__31768 instanceof cljs.core.Keyword))?G__31768.fqn:null);
switch (G__31768__$1) {
case "x":
case "y":
return true;

break;
default:
return cljs.core.contains_QMARK_(self__.__extmap,k31720);

}
}));

(shadow.dom.Coordinate.prototype.cljs$core$IAssociative$_assoc$arity$3 = (function (this__5308__auto__,k__5309__auto__,G__31719){
var self__ = this;
var this__5308__auto____$1 = this;
var pred__31775 = cljs.core.keyword_identical_QMARK_;
var expr__31776 = k__5309__auto__;
if(cljs.core.truth_((pred__31775.cljs$core$IFn$_invoke$arity$2 ? pred__31775.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"x","x",2099068185),expr__31776) : pred__31775.call(null, new cljs.core.Keyword(null,"x","x",2099068185),expr__31776)))){
return (new shadow.dom.Coordinate(G__31719,self__.y,self__.__meta,self__.__extmap,null));
} else {
if(cljs.core.truth_((pred__31775.cljs$core$IFn$_invoke$arity$2 ? pred__31775.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"y","y",-1757859776),expr__31776) : pred__31775.call(null, new cljs.core.Keyword(null,"y","y",-1757859776),expr__31776)))){
return (new shadow.dom.Coordinate(self__.x,G__31719,self__.__meta,self__.__extmap,null));
} else {
return (new shadow.dom.Coordinate(self__.x,self__.y,self__.__meta,cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(self__.__extmap,k__5309__auto__,G__31719),null));
}
}
}));

(shadow.dom.Coordinate.prototype.cljs$core$ISeqable$_seq$arity$1 = (function (this__5313__auto__){
var self__ = this;
var this__5313__auto____$1 = this;
return cljs.core.seq(cljs.core.concat.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(new cljs.core.MapEntry(new cljs.core.Keyword(null,"x","x",2099068185),self__.x,null)),(new cljs.core.MapEntry(new cljs.core.Keyword(null,"y","y",-1757859776),self__.y,null))], null),self__.__extmap));
}));

(shadow.dom.Coordinate.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (this__5299__auto__,G__31719){
var self__ = this;
var this__5299__auto____$1 = this;
return (new shadow.dom.Coordinate(self__.x,self__.y,G__31719,self__.__extmap,self__.__hash));
}));

(shadow.dom.Coordinate.prototype.cljs$core$ICollection$_conj$arity$2 = (function (this__5305__auto__,entry__5306__auto__){
var self__ = this;
var this__5305__auto____$1 = this;
if(cljs.core.vector_QMARK_(entry__5306__auto__)){
return this__5305__auto____$1.cljs$core$IAssociative$_assoc$arity$3(null, cljs.core._nth(entry__5306__auto__,(0)),cljs.core._nth(entry__5306__auto__,(1)));
} else {
return cljs.core.reduce.cljs$core$IFn$_invoke$arity$3(cljs.core._conj,this__5305__auto____$1,entry__5306__auto__);
}
}));

(shadow.dom.Coordinate.getBasis = (function (){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"x","x",-555367584,null),new cljs.core.Symbol(null,"y","y",-117328249,null)], null);
}));

(shadow.dom.Coordinate.cljs$lang$type = true);

(shadow.dom.Coordinate.cljs$lang$ctorPrSeq = (function (this__5346__auto__){
return (new cljs.core.List(null,"shadow.dom/Coordinate",null,(1),null));
}));

(shadow.dom.Coordinate.cljs$lang$ctorPrWriter = (function (this__5346__auto__,writer__5347__auto__){
return cljs.core._write(writer__5347__auto__,"shadow.dom/Coordinate");
}));

/**
 * Positional factory function for shadow.dom/Coordinate.
 */
shadow.dom.__GT_Coordinate = (function shadow$dom$__GT_Coordinate(x,y){
return (new shadow.dom.Coordinate(x,y,null,null,null));
});

/**
 * Factory function for shadow.dom/Coordinate, taking a map of keywords to field values.
 */
shadow.dom.map__GT_Coordinate = (function shadow$dom$map__GT_Coordinate(G__31723){
var extmap__5342__auto__ = (function (){var G__31792 = cljs.core.dissoc.cljs$core$IFn$_invoke$arity$variadic(G__31723,new cljs.core.Keyword(null,"x","x",2099068185),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"y","y",-1757859776)], 0));
if(cljs.core.record_QMARK_(G__31723)){
return cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentArrayMap.EMPTY,G__31792);
} else {
return G__31792;
}
})();
return (new shadow.dom.Coordinate(new cljs.core.Keyword(null,"x","x",2099068185).cljs$core$IFn$_invoke$arity$1(G__31723),new cljs.core.Keyword(null,"y","y",-1757859776).cljs$core$IFn$_invoke$arity$1(G__31723),null,cljs.core.not_empty(extmap__5342__auto__),null));
});

shadow.dom.get_position = (function shadow$dom$get_position(el){
var pos = goog.style.getPosition(shadow.dom.dom_node(el));
return shadow.dom.__GT_Coordinate(pos.x,pos.y);
});
shadow.dom.get_client_position = (function shadow$dom$get_client_position(el){
var pos = goog.style.getClientPosition(shadow.dom.dom_node(el));
return shadow.dom.__GT_Coordinate(pos.x,pos.y);
});
shadow.dom.get_page_offset = (function shadow$dom$get_page_offset(el){
var pos = goog.style.getPageOffset(shadow.dom.dom_node(el));
return shadow.dom.__GT_Coordinate(pos.x,pos.y);
});

/**
* @constructor
 * @implements {cljs.core.IRecord}
 * @implements {cljs.core.IKVReduce}
 * @implements {cljs.core.IEquiv}
 * @implements {cljs.core.IHash}
 * @implements {cljs.core.ICollection}
 * @implements {cljs.core.ICounted}
 * @implements {cljs.core.ISeqable}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.ICloneable}
 * @implements {cljs.core.IPrintWithWriter}
 * @implements {cljs.core.IIterable}
 * @implements {cljs.core.IWithMeta}
 * @implements {cljs.core.IAssociative}
 * @implements {cljs.core.IMap}
 * @implements {cljs.core.ILookup}
*/
shadow.dom.Size = (function (w,h,__meta,__extmap,__hash){
this.w = w;
this.h = h;
this.__meta = __meta;
this.__extmap = __extmap;
this.__hash = __hash;
this.cljs$lang$protocol_mask$partition0$ = 2230716170;
this.cljs$lang$protocol_mask$partition1$ = 139264;
});
(shadow.dom.Size.prototype.cljs$core$ILookup$_lookup$arity$2 = (function (this__5300__auto__,k__5301__auto__){
var self__ = this;
var this__5300__auto____$1 = this;
return this__5300__auto____$1.cljs$core$ILookup$_lookup$arity$3(null, k__5301__auto__,null);
}));

(shadow.dom.Size.prototype.cljs$core$ILookup$_lookup$arity$3 = (function (this__5302__auto__,k31805,else__5303__auto__){
var self__ = this;
var this__5302__auto____$1 = this;
var G__31821 = k31805;
var G__31821__$1 = (((G__31821 instanceof cljs.core.Keyword))?G__31821.fqn:null);
switch (G__31821__$1) {
case "w":
return self__.w;

break;
case "h":
return self__.h;

break;
default:
return cljs.core.get.cljs$core$IFn$_invoke$arity$3(self__.__extmap,k31805,else__5303__auto__);

}
}));

(shadow.dom.Size.prototype.cljs$core$IKVReduce$_kv_reduce$arity$3 = (function (this__5320__auto__,f__5321__auto__,init__5322__auto__){
var self__ = this;
var this__5320__auto____$1 = this;
return cljs.core.reduce.cljs$core$IFn$_invoke$arity$3((function (ret__5323__auto__,p__31826){
var vec__31827 = p__31826;
var k__5324__auto__ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31827,(0),null);
var v__5325__auto__ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31827,(1),null);
return (f__5321__auto__.cljs$core$IFn$_invoke$arity$3 ? f__5321__auto__.cljs$core$IFn$_invoke$arity$3(ret__5323__auto__,k__5324__auto__,v__5325__auto__) : f__5321__auto__.call(null, ret__5323__auto__,k__5324__auto__,v__5325__auto__));
}),init__5322__auto__,this__5320__auto____$1);
}));

(shadow.dom.Size.prototype.cljs$core$IPrintWithWriter$_pr_writer$arity$3 = (function (this__5315__auto__,writer__5316__auto__,opts__5317__auto__){
var self__ = this;
var this__5315__auto____$1 = this;
var pr_pair__5318__auto__ = (function (keyval__5319__auto__){
return cljs.core.pr_sequential_writer(writer__5316__auto__,cljs.core.pr_writer,""," ","",opts__5317__auto__,keyval__5319__auto__);
});
return cljs.core.pr_sequential_writer(writer__5316__auto__,pr_pair__5318__auto__,"#shadow.dom.Size{",", ","}",opts__5317__auto__,cljs.core.concat.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(new cljs.core.PersistentVector(null,2,(5),cljs.core.PersistentVector.EMPTY_NODE,[new cljs.core.Keyword(null,"w","w",354169001),self__.w],null)),(new cljs.core.PersistentVector(null,2,(5),cljs.core.PersistentVector.EMPTY_NODE,[new cljs.core.Keyword(null,"h","h",1109658740),self__.h],null))], null),self__.__extmap));
}));

(shadow.dom.Size.prototype.cljs$core$IIterable$_iterator$arity$1 = (function (G__31804){
var self__ = this;
var G__31804__$1 = this;
return (new cljs.core.RecordIter((0),G__31804__$1,2,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"w","w",354169001),new cljs.core.Keyword(null,"h","h",1109658740)], null),(cljs.core.truth_(self__.__extmap)?cljs.core._iterator(self__.__extmap):cljs.core.nil_iter())));
}));

(shadow.dom.Size.prototype.cljs$core$IMeta$_meta$arity$1 = (function (this__5298__auto__){
var self__ = this;
var this__5298__auto____$1 = this;
return self__.__meta;
}));

(shadow.dom.Size.prototype.cljs$core$ICloneable$_clone$arity$1 = (function (this__5295__auto__){
var self__ = this;
var this__5295__auto____$1 = this;
return (new shadow.dom.Size(self__.w,self__.h,self__.__meta,self__.__extmap,self__.__hash));
}));

(shadow.dom.Size.prototype.cljs$core$ICounted$_count$arity$1 = (function (this__5304__auto__){
var self__ = this;
var this__5304__auto____$1 = this;
return (2 + cljs.core.count(self__.__extmap));
}));

(shadow.dom.Size.prototype.cljs$core$IHash$_hash$arity$1 = (function (this__5296__auto__){
var self__ = this;
var this__5296__auto____$1 = this;
var h__5111__auto__ = self__.__hash;
if((!((h__5111__auto__ == null)))){
return h__5111__auto__;
} else {
var h__5111__auto____$1 = (function (coll__5297__auto__){
return (-1228019642 ^ cljs.core.hash_unordered_coll(coll__5297__auto__));
})(this__5296__auto____$1);
(self__.__hash = h__5111__auto____$1);

return h__5111__auto____$1;
}
}));

(shadow.dom.Size.prototype.cljs$core$IEquiv$_equiv$arity$2 = (function (this31806,other31807){
var self__ = this;
var this31806__$1 = this;
return (((!((other31807 == null)))) && ((((this31806__$1.constructor === other31807.constructor)) && (((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(this31806__$1.w,other31807.w)) && (((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(this31806__$1.h,other31807.h)) && (cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(this31806__$1.__extmap,other31807.__extmap)))))))));
}));

(shadow.dom.Size.prototype.cljs$core$IMap$_dissoc$arity$2 = (function (this__5310__auto__,k__5311__auto__){
var self__ = this;
var this__5310__auto____$1 = this;
if(cljs.core.contains_QMARK_(new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"w","w",354169001),null,new cljs.core.Keyword(null,"h","h",1109658740),null], null), null),k__5311__auto__)){
return cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(cljs.core._with_meta(cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentArrayMap.EMPTY,this__5310__auto____$1),self__.__meta),k__5311__auto__);
} else {
return (new shadow.dom.Size(self__.w,self__.h,self__.__meta,cljs.core.not_empty(cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(self__.__extmap,k__5311__auto__)),null));
}
}));

(shadow.dom.Size.prototype.cljs$core$IAssociative$_contains_key_QMARK_$arity$2 = (function (this__5307__auto__,k31805){
var self__ = this;
var this__5307__auto____$1 = this;
var G__31855 = k31805;
var G__31855__$1 = (((G__31855 instanceof cljs.core.Keyword))?G__31855.fqn:null);
switch (G__31855__$1) {
case "w":
case "h":
return true;

break;
default:
return cljs.core.contains_QMARK_(self__.__extmap,k31805);

}
}));

(shadow.dom.Size.prototype.cljs$core$IAssociative$_assoc$arity$3 = (function (this__5308__auto__,k__5309__auto__,G__31804){
var self__ = this;
var this__5308__auto____$1 = this;
var pred__31858 = cljs.core.keyword_identical_QMARK_;
var expr__31859 = k__5309__auto__;
if(cljs.core.truth_((pred__31858.cljs$core$IFn$_invoke$arity$2 ? pred__31858.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"w","w",354169001),expr__31859) : pred__31858.call(null, new cljs.core.Keyword(null,"w","w",354169001),expr__31859)))){
return (new shadow.dom.Size(G__31804,self__.h,self__.__meta,self__.__extmap,null));
} else {
if(cljs.core.truth_((pred__31858.cljs$core$IFn$_invoke$arity$2 ? pred__31858.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"h","h",1109658740),expr__31859) : pred__31858.call(null, new cljs.core.Keyword(null,"h","h",1109658740),expr__31859)))){
return (new shadow.dom.Size(self__.w,G__31804,self__.__meta,self__.__extmap,null));
} else {
return (new shadow.dom.Size(self__.w,self__.h,self__.__meta,cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(self__.__extmap,k__5309__auto__,G__31804),null));
}
}
}));

(shadow.dom.Size.prototype.cljs$core$ISeqable$_seq$arity$1 = (function (this__5313__auto__){
var self__ = this;
var this__5313__auto____$1 = this;
return cljs.core.seq(cljs.core.concat.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(new cljs.core.MapEntry(new cljs.core.Keyword(null,"w","w",354169001),self__.w,null)),(new cljs.core.MapEntry(new cljs.core.Keyword(null,"h","h",1109658740),self__.h,null))], null),self__.__extmap));
}));

(shadow.dom.Size.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (this__5299__auto__,G__31804){
var self__ = this;
var this__5299__auto____$1 = this;
return (new shadow.dom.Size(self__.w,self__.h,G__31804,self__.__extmap,self__.__hash));
}));

(shadow.dom.Size.prototype.cljs$core$ICollection$_conj$arity$2 = (function (this__5305__auto__,entry__5306__auto__){
var self__ = this;
var this__5305__auto____$1 = this;
if(cljs.core.vector_QMARK_(entry__5306__auto__)){
return this__5305__auto____$1.cljs$core$IAssociative$_assoc$arity$3(null, cljs.core._nth(entry__5306__auto__,(0)),cljs.core._nth(entry__5306__auto__,(1)));
} else {
return cljs.core.reduce.cljs$core$IFn$_invoke$arity$3(cljs.core._conj,this__5305__auto____$1,entry__5306__auto__);
}
}));

(shadow.dom.Size.getBasis = (function (){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"w","w",1994700528,null),new cljs.core.Symbol(null,"h","h",-1544777029,null)], null);
}));

(shadow.dom.Size.cljs$lang$type = true);

(shadow.dom.Size.cljs$lang$ctorPrSeq = (function (this__5346__auto__){
return (new cljs.core.List(null,"shadow.dom/Size",null,(1),null));
}));

(shadow.dom.Size.cljs$lang$ctorPrWriter = (function (this__5346__auto__,writer__5347__auto__){
return cljs.core._write(writer__5347__auto__,"shadow.dom/Size");
}));

/**
 * Positional factory function for shadow.dom/Size.
 */
shadow.dom.__GT_Size = (function shadow$dom$__GT_Size(w,h){
return (new shadow.dom.Size(w,h,null,null,null));
});

/**
 * Factory function for shadow.dom/Size, taking a map of keywords to field values.
 */
shadow.dom.map__GT_Size = (function shadow$dom$map__GT_Size(G__31810){
var extmap__5342__auto__ = (function (){var G__31877 = cljs.core.dissoc.cljs$core$IFn$_invoke$arity$variadic(G__31810,new cljs.core.Keyword(null,"w","w",354169001),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"h","h",1109658740)], 0));
if(cljs.core.record_QMARK_(G__31810)){
return cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentArrayMap.EMPTY,G__31877);
} else {
return G__31877;
}
})();
return (new shadow.dom.Size(new cljs.core.Keyword(null,"w","w",354169001).cljs$core$IFn$_invoke$arity$1(G__31810),new cljs.core.Keyword(null,"h","h",1109658740).cljs$core$IFn$_invoke$arity$1(G__31810),null,cljs.core.not_empty(extmap__5342__auto__),null));
});

shadow.dom.size__GT_clj = (function shadow$dom$size__GT_clj(size){
return (new shadow.dom.Size(size.width,size.height,null,null,null));
});
shadow.dom.get_size = (function shadow$dom$get_size(el){
return shadow.dom.size__GT_clj(goog.style.getSize(shadow.dom.dom_node(el)));
});
shadow.dom.get_height = (function shadow$dom$get_height(el){
return shadow.dom.get_size(el).h;
});
shadow.dom.get_viewport_size = (function shadow$dom$get_viewport_size(){
return shadow.dom.size__GT_clj(goog.dom.getViewportSize());
});
shadow.dom.first_child = (function shadow$dom$first_child(el){
return (shadow.dom.dom_node(el).children[(0)]);
});
shadow.dom.select_option_values = (function shadow$dom$select_option_values(el){
var native$ = shadow.dom.dom_node(el);
var opts = (native$["options"]);
var a__5590__auto__ = opts;
var l__5591__auto__ = a__5590__auto__.length;
var i = (0);
var ret = cljs.core.PersistentVector.EMPTY;
while(true){
if((i < l__5591__auto__)){
var G__32952 = (i + (1));
var G__32953 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(ret,(opts[i]["value"]));
i = G__32952;
ret = G__32953;
continue;
} else {
return ret;
}
break;
}
});
shadow.dom.build_url = (function shadow$dom$build_url(path,query_params){
if(cljs.core.empty_QMARK_(query_params)){
return path;
} else {
return [cljs.core.str.cljs$core$IFn$_invoke$arity$1(path),"?",clojure.string.join.cljs$core$IFn$_invoke$arity$2("&",cljs.core.map.cljs$core$IFn$_invoke$arity$2((function (p__31923){
var vec__31925 = p__31923;
var k = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31925,(0),null);
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31925,(1),null);
return [cljs.core.name(k),"=",cljs.core.str.cljs$core$IFn$_invoke$arity$1(encodeURIComponent(cljs.core.str.cljs$core$IFn$_invoke$arity$1(v)))].join('');
}),query_params))].join('');
}
});
shadow.dom.redirect = (function shadow$dom$redirect(var_args){
var G__31931 = arguments.length;
switch (G__31931) {
case 1:
return shadow.dom.redirect.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.redirect.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(shadow.dom.redirect.cljs$core$IFn$_invoke$arity$1 = (function (path){
return shadow.dom.redirect.cljs$core$IFn$_invoke$arity$2(path,cljs.core.PersistentArrayMap.EMPTY);
}));

(shadow.dom.redirect.cljs$core$IFn$_invoke$arity$2 = (function (path,query_params){
return (document["location"]["href"] = shadow.dom.build_url(path,query_params));
}));

(shadow.dom.redirect.cljs$lang$maxFixedArity = 2);

shadow.dom.reload_BANG_ = (function shadow$dom$reload_BANG_(){
return (document.location.href = document.location.href);
});
shadow.dom.tag_name = (function shadow$dom$tag_name(el){
var dom = shadow.dom.dom_node(el);
return dom.tagName;
});
shadow.dom.insert_after = (function shadow$dom$insert_after(ref,new$){
var new_node = shadow.dom.dom_node(new$);
goog.dom.insertSiblingAfter(new_node,shadow.dom.dom_node(ref));

return new_node;
});
shadow.dom.insert_before = (function shadow$dom$insert_before(ref,new$){
var new_node = shadow.dom.dom_node(new$);
goog.dom.insertSiblingBefore(new_node,shadow.dom.dom_node(ref));

return new_node;
});
shadow.dom.insert_first = (function shadow$dom$insert_first(ref,new$){
var temp__5821__auto__ = shadow.dom.dom_node(ref).firstChild;
if(cljs.core.truth_(temp__5821__auto__)){
var child = temp__5821__auto__;
return shadow.dom.insert_before(child,new$);
} else {
return shadow.dom.append.cljs$core$IFn$_invoke$arity$2(ref,new$);
}
});
shadow.dom.index_of = (function shadow$dom$index_of(el){
var el__$1 = shadow.dom.dom_node(el);
var i = (0);
while(true){
var ps = el__$1.previousSibling;
if((ps == null)){
return i;
} else {
var G__32975 = ps;
var G__32976 = (i + (1));
el__$1 = G__32975;
i = G__32976;
continue;
}
break;
}
});
shadow.dom.get_parent = (function shadow$dom$get_parent(el){
return goog.dom.getParentElement(shadow.dom.dom_node(el));
});
shadow.dom.parents = (function shadow$dom$parents(el){
var parent = shadow.dom.get_parent(el);
if(cljs.core.truth_(parent)){
return cljs.core.cons(parent,(new cljs.core.LazySeq(null,(function (){
return (shadow.dom.parents.cljs$core$IFn$_invoke$arity$1 ? shadow.dom.parents.cljs$core$IFn$_invoke$arity$1(parent) : shadow.dom.parents.call(null, parent));
}),null,null)));
} else {
return null;
}
});
shadow.dom.matches = (function shadow$dom$matches(el,sel){
return shadow.dom.dom_node(el).matches(sel);
});
shadow.dom.get_next_sibling = (function shadow$dom$get_next_sibling(el){
return goog.dom.getNextElementSibling(shadow.dom.dom_node(el));
});
shadow.dom.get_previous_sibling = (function shadow$dom$get_previous_sibling(el){
return goog.dom.getPreviousElementSibling(shadow.dom.dom_node(el));
});
shadow.dom.xmlns = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentArrayMap(null, 2, ["svg","http://www.w3.org/2000/svg","xlink","http://www.w3.org/1999/xlink"], null));
shadow.dom.create_svg_node = (function shadow$dom$create_svg_node(tag_def,props){
var vec__31983 = shadow.dom.parse_tag(tag_def);
var tag_name = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31983,(0),null);
var tag_id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31983,(1),null);
var tag_classes = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__31983,(2),null);
var el = document.createElementNS("http://www.w3.org/2000/svg",tag_name);
if(cljs.core.truth_(tag_id)){
el.setAttribute("id",tag_id);
} else {
}

if(cljs.core.truth_(tag_classes)){
el.setAttribute("class",shadow.dom.merge_class_string(new cljs.core.Keyword(null,"class","class",-2030961996).cljs$core$IFn$_invoke$arity$1(props),tag_classes));
} else {
}

var seq__31992_32989 = cljs.core.seq(props);
var chunk__31993_32990 = null;
var count__31994_32991 = (0);
var i__31995_32992 = (0);
while(true){
if((i__31995_32992 < count__31994_32991)){
var vec__32022_32993 = chunk__31993_32990.cljs$core$IIndexed$_nth$arity$2(null, i__31995_32992);
var k_32994 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__32022_32993,(0),null);
var v_32995 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__32022_32993,(1),null);
el.setAttributeNS((function (){var temp__5823__auto__ = cljs.core.namespace(k_32994);
if(cljs.core.truth_(temp__5823__auto__)){
var ns = temp__5823__auto__;
return cljs.core.get.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(shadow.dom.xmlns),ns);
} else {
return null;
}
})(),cljs.core.name(k_32994),v_32995);


var G__32998 = seq__31992_32989;
var G__32999 = chunk__31993_32990;
var G__33000 = count__31994_32991;
var G__33001 = (i__31995_32992 + (1));
seq__31992_32989 = G__32998;
chunk__31993_32990 = G__32999;
count__31994_32991 = G__33000;
i__31995_32992 = G__33001;
continue;
} else {
var temp__5823__auto___33002 = cljs.core.seq(seq__31992_32989);
if(temp__5823__auto___33002){
var seq__31992_33003__$1 = temp__5823__auto___33002;
if(cljs.core.chunked_seq_QMARK_(seq__31992_33003__$1)){
var c__5525__auto___33004 = cljs.core.chunk_first(seq__31992_33003__$1);
var G__33005 = cljs.core.chunk_rest(seq__31992_33003__$1);
var G__33006 = c__5525__auto___33004;
var G__33007 = cljs.core.count(c__5525__auto___33004);
var G__33008 = (0);
seq__31992_32989 = G__33005;
chunk__31993_32990 = G__33006;
count__31994_32991 = G__33007;
i__31995_32992 = G__33008;
continue;
} else {
var vec__32026_33009 = cljs.core.first(seq__31992_33003__$1);
var k_33010 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__32026_33009,(0),null);
var v_33011 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__32026_33009,(1),null);
el.setAttributeNS((function (){var temp__5823__auto____$1 = cljs.core.namespace(k_33010);
if(cljs.core.truth_(temp__5823__auto____$1)){
var ns = temp__5823__auto____$1;
return cljs.core.get.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(shadow.dom.xmlns),ns);
} else {
return null;
}
})(),cljs.core.name(k_33010),v_33011);


var G__33012 = cljs.core.next(seq__31992_33003__$1);
var G__33013 = null;
var G__33014 = (0);
var G__33015 = (0);
seq__31992_32989 = G__33012;
chunk__31993_32990 = G__33013;
count__31994_32991 = G__33014;
i__31995_32992 = G__33015;
continue;
}
} else {
}
}
break;
}

return el;
});
shadow.dom.svg_node = (function shadow$dom$svg_node(el){
if((el == null)){
return null;
} else {
if((((!((el == null))))?((((false) || ((cljs.core.PROTOCOL_SENTINEL === el.shadow$dom$SVGElement$))))?true:false):false)){
return el.shadow$dom$SVGElement$_to_svg$arity$1(null, );
} else {
return el;

}
}
});
shadow.dom.make_svg_node = (function shadow$dom$make_svg_node(structure){
var vec__32048 = shadow.dom.destructure_node(shadow.dom.create_svg_node,structure);
var node = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__32048,(0),null);
var node_children = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__32048,(1),null);
var seq__32051_33023 = cljs.core.seq(node_children);
var chunk__32053_33024 = null;
var count__32054_33025 = (0);
var i__32055_33026 = (0);
while(true){
if((i__32055_33026 < count__32054_33025)){
var child_struct_33027 = chunk__32053_33024.cljs$core$IIndexed$_nth$arity$2(null, i__32055_33026);
if((!((child_struct_33027 == null)))){
if(typeof child_struct_33027 === 'string'){
var text_33032 = (node["textContent"]);
(node["textContent"] = [cljs.core.str.cljs$core$IFn$_invoke$arity$1(text_33032),child_struct_33027].join(''));
} else {
var children_33033 = shadow.dom.svg_node(child_struct_33027);
if(cljs.core.seq_QMARK_(children_33033)){
var seq__32150_33034 = cljs.core.seq(children_33033);
var chunk__32152_33035 = null;
var count__32153_33036 = (0);
var i__32154_33037 = (0);
while(true){
if((i__32154_33037 < count__32153_33036)){
var child_33038 = chunk__32152_33035.cljs$core$IIndexed$_nth$arity$2(null, i__32154_33037);
if(cljs.core.truth_(child_33038)){
node.appendChild(child_33038);


var G__33042 = seq__32150_33034;
var G__33043 = chunk__32152_33035;
var G__33044 = count__32153_33036;
var G__33045 = (i__32154_33037 + (1));
seq__32150_33034 = G__33042;
chunk__32152_33035 = G__33043;
count__32153_33036 = G__33044;
i__32154_33037 = G__33045;
continue;
} else {
var G__33046 = seq__32150_33034;
var G__33047 = chunk__32152_33035;
var G__33048 = count__32153_33036;
var G__33049 = (i__32154_33037 + (1));
seq__32150_33034 = G__33046;
chunk__32152_33035 = G__33047;
count__32153_33036 = G__33048;
i__32154_33037 = G__33049;
continue;
}
} else {
var temp__5823__auto___33050 = cljs.core.seq(seq__32150_33034);
if(temp__5823__auto___33050){
var seq__32150_33051__$1 = temp__5823__auto___33050;
if(cljs.core.chunked_seq_QMARK_(seq__32150_33051__$1)){
var c__5525__auto___33053 = cljs.core.chunk_first(seq__32150_33051__$1);
var G__33054 = cljs.core.chunk_rest(seq__32150_33051__$1);
var G__33055 = c__5525__auto___33053;
var G__33056 = cljs.core.count(c__5525__auto___33053);
var G__33057 = (0);
seq__32150_33034 = G__33054;
chunk__32152_33035 = G__33055;
count__32153_33036 = G__33056;
i__32154_33037 = G__33057;
continue;
} else {
var child_33058 = cljs.core.first(seq__32150_33051__$1);
if(cljs.core.truth_(child_33058)){
node.appendChild(child_33058);


var G__33059 = cljs.core.next(seq__32150_33051__$1);
var G__33060 = null;
var G__33061 = (0);
var G__33062 = (0);
seq__32150_33034 = G__33059;
chunk__32152_33035 = G__33060;
count__32153_33036 = G__33061;
i__32154_33037 = G__33062;
continue;
} else {
var G__33063 = cljs.core.next(seq__32150_33051__$1);
var G__33064 = null;
var G__33065 = (0);
var G__33066 = (0);
seq__32150_33034 = G__33063;
chunk__32152_33035 = G__33064;
count__32153_33036 = G__33065;
i__32154_33037 = G__33066;
continue;
}
}
} else {
}
}
break;
}
} else {
node.appendChild(children_33033);
}
}


var G__33067 = seq__32051_33023;
var G__33068 = chunk__32053_33024;
var G__33069 = count__32054_33025;
var G__33070 = (i__32055_33026 + (1));
seq__32051_33023 = G__33067;
chunk__32053_33024 = G__33068;
count__32054_33025 = G__33069;
i__32055_33026 = G__33070;
continue;
} else {
var G__33071 = seq__32051_33023;
var G__33072 = chunk__32053_33024;
var G__33073 = count__32054_33025;
var G__33074 = (i__32055_33026 + (1));
seq__32051_33023 = G__33071;
chunk__32053_33024 = G__33072;
count__32054_33025 = G__33073;
i__32055_33026 = G__33074;
continue;
}
} else {
var temp__5823__auto___33075 = cljs.core.seq(seq__32051_33023);
if(temp__5823__auto___33075){
var seq__32051_33076__$1 = temp__5823__auto___33075;
if(cljs.core.chunked_seq_QMARK_(seq__32051_33076__$1)){
var c__5525__auto___33077 = cljs.core.chunk_first(seq__32051_33076__$1);
var G__33078 = cljs.core.chunk_rest(seq__32051_33076__$1);
var G__33079 = c__5525__auto___33077;
var G__33080 = cljs.core.count(c__5525__auto___33077);
var G__33081 = (0);
seq__32051_33023 = G__33078;
chunk__32053_33024 = G__33079;
count__32054_33025 = G__33080;
i__32055_33026 = G__33081;
continue;
} else {
var child_struct_33084 = cljs.core.first(seq__32051_33076__$1);
if((!((child_struct_33084 == null)))){
if(typeof child_struct_33084 === 'string'){
var text_33085 = (node["textContent"]);
(node["textContent"] = [cljs.core.str.cljs$core$IFn$_invoke$arity$1(text_33085),child_struct_33084].join(''));
} else {
var children_33086 = shadow.dom.svg_node(child_struct_33084);
if(cljs.core.seq_QMARK_(children_33086)){
var seq__32197_33088 = cljs.core.seq(children_33086);
var chunk__32199_33089 = null;
var count__32200_33090 = (0);
var i__32201_33091 = (0);
while(true){
if((i__32201_33091 < count__32200_33090)){
var child_33095 = chunk__32199_33089.cljs$core$IIndexed$_nth$arity$2(null, i__32201_33091);
if(cljs.core.truth_(child_33095)){
node.appendChild(child_33095);


var G__33096 = seq__32197_33088;
var G__33097 = chunk__32199_33089;
var G__33098 = count__32200_33090;
var G__33099 = (i__32201_33091 + (1));
seq__32197_33088 = G__33096;
chunk__32199_33089 = G__33097;
count__32200_33090 = G__33098;
i__32201_33091 = G__33099;
continue;
} else {
var G__33101 = seq__32197_33088;
var G__33102 = chunk__32199_33089;
var G__33103 = count__32200_33090;
var G__33104 = (i__32201_33091 + (1));
seq__32197_33088 = G__33101;
chunk__32199_33089 = G__33102;
count__32200_33090 = G__33103;
i__32201_33091 = G__33104;
continue;
}
} else {
var temp__5823__auto___33105__$1 = cljs.core.seq(seq__32197_33088);
if(temp__5823__auto___33105__$1){
var seq__32197_33106__$1 = temp__5823__auto___33105__$1;
if(cljs.core.chunked_seq_QMARK_(seq__32197_33106__$1)){
var c__5525__auto___33107 = cljs.core.chunk_first(seq__32197_33106__$1);
var G__33108 = cljs.core.chunk_rest(seq__32197_33106__$1);
var G__33109 = c__5525__auto___33107;
var G__33110 = cljs.core.count(c__5525__auto___33107);
var G__33111 = (0);
seq__32197_33088 = G__33108;
chunk__32199_33089 = G__33109;
count__32200_33090 = G__33110;
i__32201_33091 = G__33111;
continue;
} else {
var child_33113 = cljs.core.first(seq__32197_33106__$1);
if(cljs.core.truth_(child_33113)){
node.appendChild(child_33113);


var G__33115 = cljs.core.next(seq__32197_33106__$1);
var G__33116 = null;
var G__33117 = (0);
var G__33118 = (0);
seq__32197_33088 = G__33115;
chunk__32199_33089 = G__33116;
count__32200_33090 = G__33117;
i__32201_33091 = G__33118;
continue;
} else {
var G__33120 = cljs.core.next(seq__32197_33106__$1);
var G__33121 = null;
var G__33122 = (0);
var G__33123 = (0);
seq__32197_33088 = G__33120;
chunk__32199_33089 = G__33121;
count__32200_33090 = G__33122;
i__32201_33091 = G__33123;
continue;
}
}
} else {
}
}
break;
}
} else {
node.appendChild(children_33086);
}
}


var G__33126 = cljs.core.next(seq__32051_33076__$1);
var G__33127 = null;
var G__33128 = (0);
var G__33129 = (0);
seq__32051_33023 = G__33126;
chunk__32053_33024 = G__33127;
count__32054_33025 = G__33128;
i__32055_33026 = G__33129;
continue;
} else {
var G__33134 = cljs.core.next(seq__32051_33076__$1);
var G__33135 = null;
var G__33136 = (0);
var G__33137 = (0);
seq__32051_33023 = G__33134;
chunk__32053_33024 = G__33135;
count__32054_33025 = G__33136;
i__32055_33026 = G__33137;
continue;
}
}
} else {
}
}
break;
}

return node;
});
(shadow.dom.SVGElement["string"] = true);

(shadow.dom._to_svg["string"] = (function (this$){
if((this$ instanceof cljs.core.Keyword)){
return shadow.dom.make_svg_node(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [this$], null));
} else {
throw cljs.core.ex_info.cljs$core$IFn$_invoke$arity$2("strings cannot be in svgs",new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"this","this",-611633625),this$], null));
}
}));

(cljs.core.PersistentVector.prototype.shadow$dom$SVGElement$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.PersistentVector.prototype.shadow$dom$SVGElement$_to_svg$arity$1 = (function (this$){
var this$__$1 = this;
return shadow.dom.make_svg_node(this$__$1);
}));

(cljs.core.LazySeq.prototype.shadow$dom$SVGElement$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.LazySeq.prototype.shadow$dom$SVGElement$_to_svg$arity$1 = (function (this$){
var this$__$1 = this;
return cljs.core.map.cljs$core$IFn$_invoke$arity$2(shadow.dom._to_svg,this$__$1);
}));

(shadow.dom.SVGElement["null"] = true);

(shadow.dom._to_svg["null"] = (function (_){
return null;
}));
shadow.dom.svg = (function shadow$dom$svg(var_args){
var args__5732__auto__ = [];
var len__5726__auto___33163 = arguments.length;
var i__5727__auto___33168 = (0);
while(true){
if((i__5727__auto___33168 < len__5726__auto___33163)){
args__5732__auto__.push((arguments[i__5727__auto___33168]));

var G__33173 = (i__5727__auto___33168 + (1));
i__5727__auto___33168 = G__33173;
continue;
} else {
}
break;
}

var argseq__5733__auto__ = ((((1) < args__5732__auto__.length))?(new cljs.core.IndexedSeq(args__5732__auto__.slice((1)),(0),null)):null);
return shadow.dom.svg.cljs$core$IFn$_invoke$arity$variadic((arguments[(0)]),argseq__5733__auto__);
});

(shadow.dom.svg.cljs$core$IFn$_invoke$arity$variadic = (function (attrs,children){
return shadow.dom._to_svg(cljs.core.vec(cljs.core.concat.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"svg","svg",856789142),attrs], null),children)));
}));

(shadow.dom.svg.cljs$lang$maxFixedArity = (1));

/** @this {Function} */
(shadow.dom.svg.cljs$lang$applyTo = (function (seq32428){
var G__32429 = cljs.core.first(seq32428);
var seq32428__$1 = cljs.core.next(seq32428);
var self__5711__auto__ = this;
return self__5711__auto__.cljs$core$IFn$_invoke$arity$variadic(G__32429,seq32428__$1);
}));


//# sourceMappingURL=shadow.dom.js.map
