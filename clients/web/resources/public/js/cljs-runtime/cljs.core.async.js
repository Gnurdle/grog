goog.provide('cljs.core.async');
goog.scope(function(){
  cljs.core.async.goog$module$goog$array = goog.module.get('goog.array');
});

/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Handler}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async31688 = (function (f,blockable,meta31689){
this.f = f;
this.blockable = blockable;
this.meta31689 = meta31689;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async31688.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_31690,meta31689__$1){
var self__ = this;
var _31690__$1 = this;
return (new cljs.core.async.t_cljs$core$async31688(self__.f,self__.blockable,meta31689__$1));
}));

(cljs.core.async.t_cljs$core$async31688.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_31690){
var self__ = this;
var _31690__$1 = this;
return self__.meta31689;
}));

(cljs.core.async.t_cljs$core$async31688.prototype.cljs$core$async$impl$protocols$Handler$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async31688.prototype.cljs$core$async$impl$protocols$Handler$active_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return true;
}));

(cljs.core.async.t_cljs$core$async31688.prototype.cljs$core$async$impl$protocols$Handler$blockable_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return self__.blockable;
}));

(cljs.core.async.t_cljs$core$async31688.prototype.cljs$core$async$impl$protocols$Handler$commit$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return self__.f;
}));

(cljs.core.async.t_cljs$core$async31688.getBasis = (function (){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"f","f",43394975,null),new cljs.core.Symbol(null,"blockable","blockable",-28395259,null),new cljs.core.Symbol(null,"meta31689","meta31689",-1224307442,null)], null);
}));

(cljs.core.async.t_cljs$core$async31688.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async31688.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async31688");

(cljs.core.async.t_cljs$core$async31688.cljs$lang$ctorPrWriter = (function (this__5287__auto__,writer__5288__auto__,opt__5289__auto__){
return cljs.core._write(writer__5288__auto__,"cljs.core.async/t_cljs$core$async31688");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async31688.
 */
cljs.core.async.__GT_t_cljs$core$async31688 = (function cljs$core$async$__GT_t_cljs$core$async31688(f,blockable,meta31689){
return (new cljs.core.async.t_cljs$core$async31688(f,blockable,meta31689));
});


cljs.core.async.fn_handler = (function cljs$core$async$fn_handler(var_args){
var G__31683 = arguments.length;
switch (G__31683) {
case 1:
return cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$1 = (function (f){
return cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$2(f,true);
}));

(cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$2 = (function (f,blockable){
return (new cljs.core.async.t_cljs$core$async31688(f,blockable,cljs.core.PersistentArrayMap.EMPTY));
}));

(cljs.core.async.fn_handler.cljs$lang$maxFixedArity = 2);

/**
 * Returns a fixed buffer of size n. When full, puts will block/park.
 */
cljs.core.async.buffer = (function cljs$core$async$buffer(n){
return cljs.core.async.impl.buffers.fixed_buffer(n);
});
/**
 * Returns a buffer of size n. When full, puts will complete but
 *   val will be dropped (no transfer).
 */
cljs.core.async.dropping_buffer = (function cljs$core$async$dropping_buffer(n){
return cljs.core.async.impl.buffers.dropping_buffer(n);
});
/**
 * Returns a buffer of size n. When full, puts will complete, and be
 *   buffered, but oldest elements in buffer will be dropped (not
 *   transferred).
 */
cljs.core.async.sliding_buffer = (function cljs$core$async$sliding_buffer(n){
return cljs.core.async.impl.buffers.sliding_buffer(n);
});
/**
 * Returns true if a channel created with buff will never block. That is to say,
 * puts into this buffer will never cause the buffer to be full. 
 */
cljs.core.async.unblocking_buffer_QMARK_ = (function cljs$core$async$unblocking_buffer_QMARK_(buff){
if((!((buff == null)))){
if(((false) || ((cljs.core.PROTOCOL_SENTINEL === buff.cljs$core$async$impl$protocols$UnblockingBuffer$)))){
return true;
} else {
if((!buff.cljs$lang$protocol_mask$partition$)){
return cljs.core.native_satisfies_QMARK_(cljs.core.async.impl.protocols.UnblockingBuffer,buff);
} else {
return false;
}
}
} else {
return cljs.core.native_satisfies_QMARK_(cljs.core.async.impl.protocols.UnblockingBuffer,buff);
}
});
/**
 * Creates a channel with an optional buffer, an optional transducer (like (map f),
 *   (filter p) etc or a composition thereof), and an optional exception handler.
 *   If buf-or-n is a number, will create and use a fixed buffer of that size. If a
 *   transducer is supplied a buffer must be specified. ex-handler must be a
 *   fn of one argument - if an exception occurs during transformation it will be called
 *   with the thrown value as an argument, and any non-nil return value will be placed
 *   in the channel.
 */
cljs.core.async.chan = (function cljs$core$async$chan(var_args){
var G__31728 = arguments.length;
switch (G__31728) {
case 0:
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$0();

break;
case 1:
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.chan.cljs$core$IFn$_invoke$arity$0 = (function (){
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(null);
}));

(cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1 = (function (buf_or_n){
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$3(buf_or_n,null,null);
}));

(cljs.core.async.chan.cljs$core$IFn$_invoke$arity$2 = (function (buf_or_n,xform){
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$3(buf_or_n,xform,null);
}));

(cljs.core.async.chan.cljs$core$IFn$_invoke$arity$3 = (function (buf_or_n,xform,ex_handler){
var buf_or_n__$1 = ((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(buf_or_n,(0)))?null:buf_or_n);
if(cljs.core.truth_(xform)){
if(cljs.core.truth_(buf_or_n__$1)){
} else {
throw (new Error(["Assert failed: ","buffer must be supplied when transducer is","\n","buf-or-n"].join('')));
}
} else {
}

return cljs.core.async.impl.channels.chan.cljs$core$IFn$_invoke$arity$3(((typeof buf_or_n__$1 === 'number')?cljs.core.async.buffer(buf_or_n__$1):buf_or_n__$1),xform,ex_handler);
}));

(cljs.core.async.chan.cljs$lang$maxFixedArity = 3);

/**
 * Creates a promise channel with an optional transducer, and an optional
 *   exception-handler. A promise channel can take exactly one value that consumers
 *   will receive. Once full, puts complete but val is dropped (no transfer).
 *   Consumers will block until either a value is placed in the channel or the
 *   channel is closed. See chan for the semantics of xform and ex-handler.
 */
cljs.core.async.promise_chan = (function cljs$core$async$promise_chan(var_args){
var G__31748 = arguments.length;
switch (G__31748) {
case 0:
return cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$0();

break;
case 1:
return cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$0 = (function (){
return cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$1(null);
}));

(cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$1 = (function (xform){
return cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$2(xform,null);
}));

(cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$2 = (function (xform,ex_handler){
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$3(cljs.core.async.impl.buffers.promise_buffer(),xform,ex_handler);
}));

(cljs.core.async.promise_chan.cljs$lang$maxFixedArity = 2);

/**
 * Returns a channel that will close after msecs
 */
cljs.core.async.timeout = (function cljs$core$async$timeout(msecs){
return cljs.core.async.impl.timers.timeout(msecs);
});
/**
 * takes a val from port. Must be called inside a (go ...) block. Will
 *   return nil if closed. Will park if nothing is available.
 *   Returns true unless port is already closed
 */
cljs.core.async._LT__BANG_ = (function cljs$core$async$_LT__BANG_(port){
throw (new Error("<! used not in (go ...) block"));
});
/**
 * Asynchronously takes a val from port, passing to fn1. Will pass nil
 * if closed. If on-caller? (default true) is true, and value is
 * immediately available, will call fn1 on calling thread.
 * Returns nil.
 */
cljs.core.async.take_BANG_ = (function cljs$core$async$take_BANG_(var_args){
var G__31760 = arguments.length;
switch (G__31760) {
case 2:
return cljs.core.async.take_BANG_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.take_BANG_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.take_BANG_.cljs$core$IFn$_invoke$arity$2 = (function (port,fn1){
return cljs.core.async.take_BANG_.cljs$core$IFn$_invoke$arity$3(port,fn1,true);
}));

(cljs.core.async.take_BANG_.cljs$core$IFn$_invoke$arity$3 = (function (port,fn1,on_caller_QMARK_){
var ret = cljs.core.async.impl.protocols.take_BANG_(port,cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$1(fn1));
if(cljs.core.truth_(ret)){
var val_35397 = cljs.core.deref(ret);
if(cljs.core.truth_(on_caller_QMARK_)){
(fn1.cljs$core$IFn$_invoke$arity$1 ? fn1.cljs$core$IFn$_invoke$arity$1(val_35397) : fn1.call(null, val_35397));
} else {
cljs.core.async.impl.dispatch.run((function (){
return (fn1.cljs$core$IFn$_invoke$arity$1 ? fn1.cljs$core$IFn$_invoke$arity$1(val_35397) : fn1.call(null, val_35397));
}));
}
} else {
}

return null;
}));

(cljs.core.async.take_BANG_.cljs$lang$maxFixedArity = 3);

cljs.core.async.nop = (function cljs$core$async$nop(_){
return null;
});
cljs.core.async.fhnop = cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$1(cljs.core.async.nop);
/**
 * puts a val into port. nil values are not allowed. Must be called
 *   inside a (go ...) block. Will park if no buffer space is available.
 *   Returns true unless port is already closed.
 */
cljs.core.async._GT__BANG_ = (function cljs$core$async$_GT__BANG_(port,val){
throw (new Error(">! used not in (go ...) block"));
});
/**
 * Asynchronously puts a val into port, calling fn1 (if supplied) when
 * complete. nil values are not allowed. Will throw if closed. If
 * on-caller? (default true) is true, and the put is immediately
 * accepted, will call fn1 on calling thread.  Returns nil.
 */
cljs.core.async.put_BANG_ = (function cljs$core$async$put_BANG_(var_args){
var G__31782 = arguments.length;
switch (G__31782) {
case 2:
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
case 4:
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2 = (function (port,val){
var temp__5821__auto__ = cljs.core.async.impl.protocols.put_BANG_(port,val,cljs.core.async.fhnop);
if(cljs.core.truth_(temp__5821__auto__)){
var ret = temp__5821__auto__;
return cljs.core.deref(ret);
} else {
return true;
}
}));

(cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$3 = (function (port,val,fn1){
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$4(port,val,fn1,true);
}));

(cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$4 = (function (port,val,fn1,on_caller_QMARK_){
var temp__5821__auto__ = cljs.core.async.impl.protocols.put_BANG_(port,val,cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$1(fn1));
if(cljs.core.truth_(temp__5821__auto__)){
var retb = temp__5821__auto__;
var ret = cljs.core.deref(retb);
if(cljs.core.truth_(on_caller_QMARK_)){
(fn1.cljs$core$IFn$_invoke$arity$1 ? fn1.cljs$core$IFn$_invoke$arity$1(ret) : fn1.call(null, ret));
} else {
cljs.core.async.impl.dispatch.run((function (){
return (fn1.cljs$core$IFn$_invoke$arity$1 ? fn1.cljs$core$IFn$_invoke$arity$1(ret) : fn1.call(null, ret));
}));
}

return ret;
} else {
return true;
}
}));

(cljs.core.async.put_BANG_.cljs$lang$maxFixedArity = 4);

cljs.core.async.close_BANG_ = (function cljs$core$async$close_BANG_(port){
return cljs.core.async.impl.protocols.close_BANG_(port);
});
cljs.core.async.random_array = (function cljs$core$async$random_array(n){
var a = (new Array(n));
var n__5593__auto___35404 = n;
var x_35405 = (0);
while(true){
if((x_35405 < n__5593__auto___35404)){
(a[x_35405] = x_35405);

var G__35409 = (x_35405 + (1));
x_35405 = G__35409;
continue;
} else {
}
break;
}

cljs.core.async.goog$module$goog$array.shuffle(a);

return a;
});

/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Handler}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async31793 = (function (flag,meta31794){
this.flag = flag;
this.meta31794 = meta31794;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async31793.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_31795,meta31794__$1){
var self__ = this;
var _31795__$1 = this;
return (new cljs.core.async.t_cljs$core$async31793(self__.flag,meta31794__$1));
}));

(cljs.core.async.t_cljs$core$async31793.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_31795){
var self__ = this;
var _31795__$1 = this;
return self__.meta31794;
}));

(cljs.core.async.t_cljs$core$async31793.prototype.cljs$core$async$impl$protocols$Handler$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async31793.prototype.cljs$core$async$impl$protocols$Handler$active_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.deref(self__.flag);
}));

(cljs.core.async.t_cljs$core$async31793.prototype.cljs$core$async$impl$protocols$Handler$blockable_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return true;
}));

(cljs.core.async.t_cljs$core$async31793.prototype.cljs$core$async$impl$protocols$Handler$commit$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
cljs.core.reset_BANG_(self__.flag,null);

return true;
}));

(cljs.core.async.t_cljs$core$async31793.getBasis = (function (){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"flag","flag",-1565787888,null),new cljs.core.Symbol(null,"meta31794","meta31794",-1867014731,null)], null);
}));

(cljs.core.async.t_cljs$core$async31793.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async31793.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async31793");

(cljs.core.async.t_cljs$core$async31793.cljs$lang$ctorPrWriter = (function (this__5287__auto__,writer__5288__auto__,opt__5289__auto__){
return cljs.core._write(writer__5288__auto__,"cljs.core.async/t_cljs$core$async31793");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async31793.
 */
cljs.core.async.__GT_t_cljs$core$async31793 = (function cljs$core$async$__GT_t_cljs$core$async31793(flag,meta31794){
return (new cljs.core.async.t_cljs$core$async31793(flag,meta31794));
});


cljs.core.async.alt_flag = (function cljs$core$async$alt_flag(){
var flag = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(true);
return (new cljs.core.async.t_cljs$core$async31793(flag,cljs.core.PersistentArrayMap.EMPTY));
});

/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Handler}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async31812 = (function (flag,cb,meta31813){
this.flag = flag;
this.cb = cb;
this.meta31813 = meta31813;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async31812.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_31814,meta31813__$1){
var self__ = this;
var _31814__$1 = this;
return (new cljs.core.async.t_cljs$core$async31812(self__.flag,self__.cb,meta31813__$1));
}));

(cljs.core.async.t_cljs$core$async31812.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_31814){
var self__ = this;
var _31814__$1 = this;
return self__.meta31813;
}));

(cljs.core.async.t_cljs$core$async31812.prototype.cljs$core$async$impl$protocols$Handler$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async31812.prototype.cljs$core$async$impl$protocols$Handler$active_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.active_QMARK_(self__.flag);
}));

(cljs.core.async.t_cljs$core$async31812.prototype.cljs$core$async$impl$protocols$Handler$blockable_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return true;
}));

(cljs.core.async.t_cljs$core$async31812.prototype.cljs$core$async$impl$protocols$Handler$commit$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
cljs.core.async.impl.protocols.commit(self__.flag);

return self__.cb;
}));

(cljs.core.async.t_cljs$core$async31812.getBasis = (function (){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"flag","flag",-1565787888,null),new cljs.core.Symbol(null,"cb","cb",-2064487928,null),new cljs.core.Symbol(null,"meta31813","meta31813",2139716427,null)], null);
}));

(cljs.core.async.t_cljs$core$async31812.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async31812.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async31812");

(cljs.core.async.t_cljs$core$async31812.cljs$lang$ctorPrWriter = (function (this__5287__auto__,writer__5288__auto__,opt__5289__auto__){
return cljs.core._write(writer__5288__auto__,"cljs.core.async/t_cljs$core$async31812");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async31812.
 */
cljs.core.async.__GT_t_cljs$core$async31812 = (function cljs$core$async$__GT_t_cljs$core$async31812(flag,cb,meta31813){
return (new cljs.core.async.t_cljs$core$async31812(flag,cb,meta31813));
});


cljs.core.async.alt_handler = (function cljs$core$async$alt_handler(flag,cb){
return (new cljs.core.async.t_cljs$core$async31812(flag,cb,cljs.core.PersistentArrayMap.EMPTY));
});
/**
 * returns derefable [val port] if immediate, nil if enqueued
 */
cljs.core.async.do_alts = (function cljs$core$async$do_alts(fret,ports,opts){
if((cljs.core.count(ports) > (0))){
} else {
throw (new Error(["Assert failed: ","alts must have at least one channel operation","\n","(pos? (count ports))"].join('')));
}

var flag = cljs.core.async.alt_flag();
var n = cljs.core.count(ports);
var idxs = cljs.core.async.random_array(n);
var priority = new cljs.core.Keyword(null,"priority","priority",1431093715).cljs$core$IFn$_invoke$arity$1(opts);
var ret = (function (){var i = (0);
while(true){
if((i < n)){
var idx = (cljs.core.truth_(priority)?i:(idxs[i]));
var port = cljs.core.nth.cljs$core$IFn$_invoke$arity$2(ports,idx);
var wport = ((cljs.core.vector_QMARK_(port))?(port.cljs$core$IFn$_invoke$arity$1 ? port.cljs$core$IFn$_invoke$arity$1((0)) : port.call(null, (0))):null);
var vbox = (cljs.core.truth_(wport)?(function (){var val = (port.cljs$core$IFn$_invoke$arity$1 ? port.cljs$core$IFn$_invoke$arity$1((1)) : port.call(null, (1)));
return cljs.core.async.impl.protocols.put_BANG_(wport,val,cljs.core.async.alt_handler(flag,((function (i,val,idx,port,wport,flag,n,idxs,priority){
return (function (p1__31832_SHARP_){
var G__31838 = new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [p1__31832_SHARP_,wport], null);
return (fret.cljs$core$IFn$_invoke$arity$1 ? fret.cljs$core$IFn$_invoke$arity$1(G__31838) : fret.call(null, G__31838));
});})(i,val,idx,port,wport,flag,n,idxs,priority))
));
})():cljs.core.async.impl.protocols.take_BANG_(port,cljs.core.async.alt_handler(flag,((function (i,idx,port,wport,flag,n,idxs,priority){
return (function (p1__31833_SHARP_){
var G__31839 = new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [p1__31833_SHARP_,port], null);
return (fret.cljs$core$IFn$_invoke$arity$1 ? fret.cljs$core$IFn$_invoke$arity$1(G__31839) : fret.call(null, G__31839));
});})(i,idx,port,wport,flag,n,idxs,priority))
)));
if(cljs.core.truth_(vbox)){
return cljs.core.async.impl.channels.box(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.core.deref(vbox),(function (){var or__5002__auto__ = wport;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return port;
}
})()], null));
} else {
var G__35463 = (i + (1));
i = G__35463;
continue;
}
} else {
return null;
}
break;
}
})();
var or__5002__auto__ = ret;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
if(cljs.core.contains_QMARK_(opts,new cljs.core.Keyword(null,"default","default",-1987822328))){
var temp__5823__auto__ = (function (){var and__5000__auto__ = flag.cljs$core$async$impl$protocols$Handler$active_QMARK_$arity$1(null, );
if(cljs.core.truth_(and__5000__auto__)){
return flag.cljs$core$async$impl$protocols$Handler$commit$arity$1(null, );
} else {
return and__5000__auto__;
}
})();
if(cljs.core.truth_(temp__5823__auto__)){
var got = temp__5823__auto__;
return cljs.core.async.impl.channels.box(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"default","default",-1987822328).cljs$core$IFn$_invoke$arity$1(opts),new cljs.core.Keyword(null,"default","default",-1987822328)], null));
} else {
return null;
}
} else {
return null;
}
}
});
/**
 * Completes at most one of several channel operations. Must be called
 * inside a (go ...) block. ports is a vector of channel endpoints,
 * which can be either a channel to take from or a vector of
 *   [channel-to-put-to val-to-put], in any combination. Takes will be
 *   made as if by <!, and puts will be made as if by >!. Unless
 *   the :priority option is true, if more than one port operation is
 *   ready a non-deterministic choice will be made. If no operation is
 *   ready and a :default value is supplied, [default-val :default] will
 *   be returned, otherwise alts! will park until the first operation to
 *   become ready completes. Returns [val port] of the completed
 *   operation, where val is the value taken for takes, and a
 *   boolean (true unless already closed, as per put!) for puts.
 * 
 *   opts are passed as :key val ... Supported options:
 * 
 *   :default val - the value to use if none of the operations are immediately ready
 *   :priority true - (default nil) when true, the operations will be tried in order.
 * 
 *   Note: there is no guarantee that the port exps or val exprs will be
 *   used, nor in what order should they be, so they should not be
 *   depended upon for side effects.
 */
cljs.core.async.alts_BANG_ = (function cljs$core$async$alts_BANG_(var_args){
var args__5732__auto__ = [];
var len__5726__auto___35470 = arguments.length;
var i__5727__auto___35471 = (0);
while(true){
if((i__5727__auto___35471 < len__5726__auto___35470)){
args__5732__auto__.push((arguments[i__5727__auto___35471]));

var G__35475 = (i__5727__auto___35471 + (1));
i__5727__auto___35471 = G__35475;
continue;
} else {
}
break;
}

var argseq__5733__auto__ = ((((1) < args__5732__auto__.length))?(new cljs.core.IndexedSeq(args__5732__auto__.slice((1)),(0),null)):null);
return cljs.core.async.alts_BANG_.cljs$core$IFn$_invoke$arity$variadic((arguments[(0)]),argseq__5733__auto__);
});

(cljs.core.async.alts_BANG_.cljs$core$IFn$_invoke$arity$variadic = (function (ports,p__31848){
var map__31849 = p__31848;
var map__31849__$1 = cljs.core.__destructure_map(map__31849);
var opts = map__31849__$1;
throw (new Error("alts! used not in (go ...) block"));
}));

(cljs.core.async.alts_BANG_.cljs$lang$maxFixedArity = (1));

/** @this {Function} */
(cljs.core.async.alts_BANG_.cljs$lang$applyTo = (function (seq31845){
var G__31846 = cljs.core.first(seq31845);
var seq31845__$1 = cljs.core.next(seq31845);
var self__5711__auto__ = this;
return self__5711__auto__.cljs$core$IFn$_invoke$arity$variadic(G__31846,seq31845__$1);
}));

/**
 * Puts a val into port if it's possible to do so immediately.
 *   nil values are not allowed. Never blocks. Returns true if offer succeeds.
 */
cljs.core.async.offer_BANG_ = (function cljs$core$async$offer_BANG_(port,val){
var ret = cljs.core.async.impl.protocols.put_BANG_(port,val,cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$2(cljs.core.async.nop,false));
if(cljs.core.truth_(ret)){
return cljs.core.deref(ret);
} else {
return null;
}
});
/**
 * Takes a val from port if it's possible to do so immediately.
 *   Never blocks. Returns value if successful, nil otherwise.
 */
cljs.core.async.poll_BANG_ = (function cljs$core$async$poll_BANG_(port){
var ret = cljs.core.async.impl.protocols.take_BANG_(port,cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$2(cljs.core.async.nop,false));
if(cljs.core.truth_(ret)){
return cljs.core.deref(ret);
} else {
return null;
}
});
/**
 * Takes elements from the from channel and supplies them to the to
 * channel. By default, the to channel will be closed when the from
 * channel closes, but can be determined by the close?  parameter. Will
 * stop consuming the from channel if the to channel closes
 */
cljs.core.async.pipe = (function cljs$core$async$pipe(var_args){
var G__31861 = arguments.length;
switch (G__31861) {
case 2:
return cljs.core.async.pipe.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.pipe.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.pipe.cljs$core$IFn$_invoke$arity$2 = (function (from,to){
return cljs.core.async.pipe.cljs$core$IFn$_invoke$arity$3(from,to,true);
}));

(cljs.core.async.pipe.cljs$core$IFn$_invoke$arity$3 = (function (from,to,close_QMARK_){
var c__31514__auto___35490 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_31930){
var state_val_31932 = (state_31930[(1)]);
if((state_val_31932 === (7))){
var inst_31900 = (state_31930[(2)]);
var state_31930__$1 = state_31930;
var statearr_31939_35491 = state_31930__$1;
(statearr_31939_35491[(2)] = inst_31900);

(statearr_31939_35491[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_31932 === (1))){
var state_31930__$1 = state_31930;
var statearr_31940_35492 = state_31930__$1;
(statearr_31940_35492[(2)] = null);

(statearr_31940_35492[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_31932 === (4))){
var inst_31879 = (state_31930[(7)]);
var inst_31879__$1 = (state_31930[(2)]);
var inst_31882 = (inst_31879__$1 == null);
var state_31930__$1 = (function (){var statearr_31946 = state_31930;
(statearr_31946[(7)] = inst_31879__$1);

return statearr_31946;
})();
if(cljs.core.truth_(inst_31882)){
var statearr_31951_35493 = state_31930__$1;
(statearr_31951_35493[(1)] = (5));

} else {
var statearr_31952_35494 = state_31930__$1;
(statearr_31952_35494[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_31932 === (13))){
var state_31930__$1 = state_31930;
var statearr_31954_35495 = state_31930__$1;
(statearr_31954_35495[(2)] = null);

(statearr_31954_35495[(1)] = (14));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_31932 === (6))){
var inst_31879 = (state_31930[(7)]);
var state_31930__$1 = state_31930;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_31930__$1,(11),to,inst_31879);
} else {
if((state_val_31932 === (3))){
var inst_31902 = (state_31930[(2)]);
var state_31930__$1 = state_31930;
return cljs.core.async.impl.ioc_helpers.return_chan(state_31930__$1,inst_31902);
} else {
if((state_val_31932 === (12))){
var state_31930__$1 = state_31930;
var statearr_31956_35496 = state_31930__$1;
(statearr_31956_35496[(2)] = null);

(statearr_31956_35496[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_31932 === (2))){
var state_31930__$1 = state_31930;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_31930__$1,(4),from);
} else {
if((state_val_31932 === (11))){
var inst_31891 = (state_31930[(2)]);
var state_31930__$1 = state_31930;
if(cljs.core.truth_(inst_31891)){
var statearr_31957_35497 = state_31930__$1;
(statearr_31957_35497[(1)] = (12));

} else {
var statearr_31959_35499 = state_31930__$1;
(statearr_31959_35499[(1)] = (13));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_31932 === (9))){
var state_31930__$1 = state_31930;
var statearr_31961_35502 = state_31930__$1;
(statearr_31961_35502[(2)] = null);

(statearr_31961_35502[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_31932 === (5))){
var state_31930__$1 = state_31930;
if(cljs.core.truth_(close_QMARK_)){
var statearr_31963_35506 = state_31930__$1;
(statearr_31963_35506[(1)] = (8));

} else {
var statearr_31964_35507 = state_31930__$1;
(statearr_31964_35507[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_31932 === (14))){
var inst_31898 = (state_31930[(2)]);
var state_31930__$1 = state_31930;
var statearr_31967_35513 = state_31930__$1;
(statearr_31967_35513[(2)] = inst_31898);

(statearr_31967_35513[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_31932 === (10))){
var inst_31888 = (state_31930[(2)]);
var state_31930__$1 = state_31930;
var statearr_31969_35521 = state_31930__$1;
(statearr_31969_35521[(2)] = inst_31888);

(statearr_31969_35521[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_31932 === (8))){
var inst_31885 = cljs.core.async.close_BANG_(to);
var state_31930__$1 = state_31930;
var statearr_31970_35527 = state_31930__$1;
(statearr_31970_35527[(2)] = inst_31885);

(statearr_31970_35527[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__30402__auto__ = null;
var cljs$core$async$state_machine__30402__auto____0 = (function (){
var statearr_31975 = [null,null,null,null,null,null,null,null];
(statearr_31975[(0)] = cljs$core$async$state_machine__30402__auto__);

(statearr_31975[(1)] = (1));

return statearr_31975;
});
var cljs$core$async$state_machine__30402__auto____1 = (function (state_31930){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_31930);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e31976){var ex__30405__auto__ = e31976;
var statearr_31978_35552 = state_31930;
(statearr_31978_35552[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_31930[(4)]))){
var statearr_31979_35554 = state_31930;
(statearr_31979_35554[(1)] = cljs.core.first((state_31930[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__35555 = state_31930;
state_31930 = G__35555;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$state_machine__30402__auto__ = function(state_31930){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__30402__auto____1.call(this,state_31930);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__30402__auto____0;
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__30402__auto____1;
return cljs$core$async$state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_31981 = f__31515__auto__();
(statearr_31981[(6)] = c__31514__auto___35490);

return statearr_31981;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));


return to;
}));

(cljs.core.async.pipe.cljs$lang$maxFixedArity = 3);

cljs.core.async.pipeline_STAR_ = (function cljs$core$async$pipeline_STAR_(n,to,xf,from,close_QMARK_,ex_handler,type){
if((n > (0))){
} else {
throw (new Error("Assert failed: (pos? n)"));
}

var jobs = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(n);
var results = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(n);
var process__$1 = (function (p__32005){
var vec__32007 = p__32005;
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__32007,(0),null);
var p = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__32007,(1),null);
var job = vec__32007;
if((job == null)){
cljs.core.async.close_BANG_(results);

return null;
} else {
var res = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$3((1),xf,ex_handler);
var c__31514__auto___35563 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_32020){
var state_val_32021 = (state_32020[(1)]);
if((state_val_32021 === (1))){
var state_32020__$1 = state_32020;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_32020__$1,(2),res,v);
} else {
if((state_val_32021 === (2))){
var inst_32017 = (state_32020[(2)]);
var inst_32018 = cljs.core.async.close_BANG_(res);
var state_32020__$1 = (function (){var statearr_32025 = state_32020;
(statearr_32025[(7)] = inst_32017);

return statearr_32025;
})();
return cljs.core.async.impl.ioc_helpers.return_chan(state_32020__$1,inst_32018);
} else {
return null;
}
}
});
return (function() {
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__ = null;
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0 = (function (){
var statearr_32031 = [null,null,null,null,null,null,null,null];
(statearr_32031[(0)] = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__);

(statearr_32031[(1)] = (1));

return statearr_32031;
});
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1 = (function (state_32020){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_32020);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e32033){var ex__30405__auto__ = e32033;
var statearr_32034_35575 = state_32020;
(statearr_32034_35575[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_32020[(4)]))){
var statearr_32035_35576 = state_32020;
(statearr_32035_35576[(1)] = cljs.core.first((state_32020[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__35580 = state_32020;
state_32020 = G__35580;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__ = function(state_32020){
switch(arguments.length){
case 0:
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1.call(this,state_32020);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0;
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1;
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_32039 = f__31515__auto__();
(statearr_32039[(6)] = c__31514__auto___35563);

return statearr_32039;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));


cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2(p,res);

return true;
}
});
var async = (function (p__32044){
var vec__32045 = p__32044;
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__32045,(0),null);
var p = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__32045,(1),null);
var job = vec__32045;
if((job == null)){
cljs.core.async.close_BANG_(results);

return null;
} else {
var res = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
(xf.cljs$core$IFn$_invoke$arity$2 ? xf.cljs$core$IFn$_invoke$arity$2(v,res) : xf.call(null, v,res));

cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2(p,res);

return true;
}
});
var n__5593__auto___35581 = n;
var __35582 = (0);
while(true){
if((__35582 < n__5593__auto___35581)){
var G__32061_35583 = type;
var G__32061_35584__$1 = (((G__32061_35583 instanceof cljs.core.Keyword))?G__32061_35583.fqn:null);
switch (G__32061_35584__$1) {
case "compute":
var c__31514__auto___35586 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run(((function (__35582,c__31514__auto___35586,G__32061_35583,G__32061_35584__$1,n__5593__auto___35581,jobs,results,process__$1,async){
return (function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = ((function (__35582,c__31514__auto___35586,G__32061_35583,G__32061_35584__$1,n__5593__auto___35581,jobs,results,process__$1,async){
return (function (state_32077){
var state_val_32078 = (state_32077[(1)]);
if((state_val_32078 === (1))){
var state_32077__$1 = state_32077;
var statearr_32087_35590 = state_32077__$1;
(statearr_32087_35590[(2)] = null);

(statearr_32087_35590[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32078 === (2))){
var state_32077__$1 = state_32077;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_32077__$1,(4),jobs);
} else {
if((state_val_32078 === (3))){
var inst_32073 = (state_32077[(2)]);
var state_32077__$1 = state_32077;
return cljs.core.async.impl.ioc_helpers.return_chan(state_32077__$1,inst_32073);
} else {
if((state_val_32078 === (4))){
var inst_32065 = (state_32077[(2)]);
var inst_32066 = process__$1(inst_32065);
var state_32077__$1 = state_32077;
if(cljs.core.truth_(inst_32066)){
var statearr_32093_35591 = state_32077__$1;
(statearr_32093_35591[(1)] = (5));

} else {
var statearr_32094_35593 = state_32077__$1;
(statearr_32094_35593[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32078 === (5))){
var state_32077__$1 = state_32077;
var statearr_32095_35594 = state_32077__$1;
(statearr_32095_35594[(2)] = null);

(statearr_32095_35594[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32078 === (6))){
var state_32077__$1 = state_32077;
var statearr_32099_35595 = state_32077__$1;
(statearr_32099_35595[(2)] = null);

(statearr_32099_35595[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32078 === (7))){
var inst_32071 = (state_32077[(2)]);
var state_32077__$1 = state_32077;
var statearr_32100_35597 = state_32077__$1;
(statearr_32100_35597[(2)] = inst_32071);

(statearr_32100_35597[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
});})(__35582,c__31514__auto___35586,G__32061_35583,G__32061_35584__$1,n__5593__auto___35581,jobs,results,process__$1,async))
;
return ((function (__35582,switch__30401__auto__,c__31514__auto___35586,G__32061_35583,G__32061_35584__$1,n__5593__auto___35581,jobs,results,process__$1,async){
return (function() {
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__ = null;
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0 = (function (){
var statearr_32112 = [null,null,null,null,null,null,null];
(statearr_32112[(0)] = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__);

(statearr_32112[(1)] = (1));

return statearr_32112;
});
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1 = (function (state_32077){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_32077);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e32115){var ex__30405__auto__ = e32115;
var statearr_32118_35598 = state_32077;
(statearr_32118_35598[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_32077[(4)]))){
var statearr_32119_35599 = state_32077;
(statearr_32119_35599[(1)] = cljs.core.first((state_32077[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__35600 = state_32077;
state_32077 = G__35600;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__ = function(state_32077){
switch(arguments.length){
case 0:
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1.call(this,state_32077);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0;
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1;
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__;
})()
;})(__35582,switch__30401__auto__,c__31514__auto___35586,G__32061_35583,G__32061_35584__$1,n__5593__auto___35581,jobs,results,process__$1,async))
})();
var state__31516__auto__ = (function (){var statearr_32122 = f__31515__auto__();
(statearr_32122[(6)] = c__31514__auto___35586);

return statearr_32122;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
});})(__35582,c__31514__auto___35586,G__32061_35583,G__32061_35584__$1,n__5593__auto___35581,jobs,results,process__$1,async))
);


break;
case "async":
var c__31514__auto___35604 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run(((function (__35582,c__31514__auto___35604,G__32061_35583,G__32061_35584__$1,n__5593__auto___35581,jobs,results,process__$1,async){
return (function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = ((function (__35582,c__31514__auto___35604,G__32061_35583,G__32061_35584__$1,n__5593__auto___35581,jobs,results,process__$1,async){
return (function (state_32142){
var state_val_32143 = (state_32142[(1)]);
if((state_val_32143 === (1))){
var state_32142__$1 = state_32142;
var statearr_32164_35608 = state_32142__$1;
(statearr_32164_35608[(2)] = null);

(statearr_32164_35608[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32143 === (2))){
var state_32142__$1 = state_32142;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_32142__$1,(4),jobs);
} else {
if((state_val_32143 === (3))){
var inst_32139 = (state_32142[(2)]);
var state_32142__$1 = state_32142;
return cljs.core.async.impl.ioc_helpers.return_chan(state_32142__$1,inst_32139);
} else {
if((state_val_32143 === (4))){
var inst_32131 = (state_32142[(2)]);
var inst_32132 = async(inst_32131);
var state_32142__$1 = state_32142;
if(cljs.core.truth_(inst_32132)){
var statearr_32170_35609 = state_32142__$1;
(statearr_32170_35609[(1)] = (5));

} else {
var statearr_32171_35611 = state_32142__$1;
(statearr_32171_35611[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32143 === (5))){
var state_32142__$1 = state_32142;
var statearr_32173_35612 = state_32142__$1;
(statearr_32173_35612[(2)] = null);

(statearr_32173_35612[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32143 === (6))){
var state_32142__$1 = state_32142;
var statearr_32179_35613 = state_32142__$1;
(statearr_32179_35613[(2)] = null);

(statearr_32179_35613[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32143 === (7))){
var inst_32137 = (state_32142[(2)]);
var state_32142__$1 = state_32142;
var statearr_32181_35614 = state_32142__$1;
(statearr_32181_35614[(2)] = inst_32137);

(statearr_32181_35614[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
});})(__35582,c__31514__auto___35604,G__32061_35583,G__32061_35584__$1,n__5593__auto___35581,jobs,results,process__$1,async))
;
return ((function (__35582,switch__30401__auto__,c__31514__auto___35604,G__32061_35583,G__32061_35584__$1,n__5593__auto___35581,jobs,results,process__$1,async){
return (function() {
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__ = null;
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0 = (function (){
var statearr_32183 = [null,null,null,null,null,null,null];
(statearr_32183[(0)] = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__);

(statearr_32183[(1)] = (1));

return statearr_32183;
});
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1 = (function (state_32142){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_32142);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e32186){var ex__30405__auto__ = e32186;
var statearr_32188_35617 = state_32142;
(statearr_32188_35617[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_32142[(4)]))){
var statearr_32189_35618 = state_32142;
(statearr_32189_35618[(1)] = cljs.core.first((state_32142[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__35619 = state_32142;
state_32142 = G__35619;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__ = function(state_32142){
switch(arguments.length){
case 0:
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1.call(this,state_32142);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0;
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1;
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__;
})()
;})(__35582,switch__30401__auto__,c__31514__auto___35604,G__32061_35583,G__32061_35584__$1,n__5593__auto___35581,jobs,results,process__$1,async))
})();
var state__31516__auto__ = (function (){var statearr_32193 = f__31515__auto__();
(statearr_32193[(6)] = c__31514__auto___35604);

return statearr_32193;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
});})(__35582,c__31514__auto___35604,G__32061_35583,G__32061_35584__$1,n__5593__auto___35581,jobs,results,process__$1,async))
);


break;
default:
throw (new Error(["No matching clause: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(G__32061_35584__$1)].join('')));

}

var G__35620 = (__35582 + (1));
__35582 = G__35620;
continue;
} else {
}
break;
}

var c__31514__auto___35621 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_32236){
var state_val_32237 = (state_32236[(1)]);
if((state_val_32237 === (7))){
var inst_32231 = (state_32236[(2)]);
var state_32236__$1 = state_32236;
var statearr_32247_35622 = state_32236__$1;
(statearr_32247_35622[(2)] = inst_32231);

(statearr_32247_35622[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32237 === (1))){
var state_32236__$1 = state_32236;
var statearr_32250_35624 = state_32236__$1;
(statearr_32250_35624[(2)] = null);

(statearr_32250_35624[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32237 === (4))){
var inst_32206 = (state_32236[(7)]);
var inst_32206__$1 = (state_32236[(2)]);
var inst_32207 = (inst_32206__$1 == null);
var state_32236__$1 = (function (){var statearr_32252 = state_32236;
(statearr_32252[(7)] = inst_32206__$1);

return statearr_32252;
})();
if(cljs.core.truth_(inst_32207)){
var statearr_32254_35625 = state_32236__$1;
(statearr_32254_35625[(1)] = (5));

} else {
var statearr_32255_35626 = state_32236__$1;
(statearr_32255_35626[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32237 === (6))){
var inst_32214 = (state_32236[(8)]);
var inst_32206 = (state_32236[(7)]);
var inst_32214__$1 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
var inst_32216 = cljs.core.PersistentVector.EMPTY_NODE;
var inst_32221 = [inst_32206,inst_32214__$1];
var inst_32222 = (new cljs.core.PersistentVector(null,2,(5),inst_32216,inst_32221,null));
var state_32236__$1 = (function (){var statearr_32258 = state_32236;
(statearr_32258[(8)] = inst_32214__$1);

return statearr_32258;
})();
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_32236__$1,(8),jobs,inst_32222);
} else {
if((state_val_32237 === (3))){
var inst_32233 = (state_32236[(2)]);
var state_32236__$1 = state_32236;
return cljs.core.async.impl.ioc_helpers.return_chan(state_32236__$1,inst_32233);
} else {
if((state_val_32237 === (2))){
var state_32236__$1 = state_32236;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_32236__$1,(4),from);
} else {
if((state_val_32237 === (9))){
var inst_32227 = (state_32236[(2)]);
var state_32236__$1 = (function (){var statearr_32264 = state_32236;
(statearr_32264[(9)] = inst_32227);

return statearr_32264;
})();
var statearr_32265_35628 = state_32236__$1;
(statearr_32265_35628[(2)] = null);

(statearr_32265_35628[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32237 === (5))){
var inst_32211 = cljs.core.async.close_BANG_(jobs);
var state_32236__$1 = state_32236;
var statearr_32267_35629 = state_32236__$1;
(statearr_32267_35629[(2)] = inst_32211);

(statearr_32267_35629[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32237 === (8))){
var inst_32214 = (state_32236[(8)]);
var inst_32224 = (state_32236[(2)]);
var state_32236__$1 = (function (){var statearr_32268 = state_32236;
(statearr_32268[(10)] = inst_32224);

return statearr_32268;
})();
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_32236__$1,(9),results,inst_32214);
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
});
return (function() {
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__ = null;
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0 = (function (){
var statearr_32272 = [null,null,null,null,null,null,null,null,null,null,null];
(statearr_32272[(0)] = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__);

(statearr_32272[(1)] = (1));

return statearr_32272;
});
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1 = (function (state_32236){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_32236);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e32276){var ex__30405__auto__ = e32276;
var statearr_32277_35630 = state_32236;
(statearr_32277_35630[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_32236[(4)]))){
var statearr_32283_35631 = state_32236;
(statearr_32283_35631[(1)] = cljs.core.first((state_32236[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__35632 = state_32236;
state_32236 = G__35632;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__ = function(state_32236){
switch(arguments.length){
case 0:
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1.call(this,state_32236);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0;
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1;
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_32285 = f__31515__auto__();
(statearr_32285[(6)] = c__31514__auto___35621);

return statearr_32285;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));


var c__31514__auto__ = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_32338){
var state_val_32339 = (state_32338[(1)]);
if((state_val_32339 === (7))){
var inst_32331 = (state_32338[(2)]);
var state_32338__$1 = state_32338;
var statearr_32345_35633 = state_32338__$1;
(statearr_32345_35633[(2)] = inst_32331);

(statearr_32345_35633[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (20))){
var state_32338__$1 = state_32338;
var statearr_32347_35634 = state_32338__$1;
(statearr_32347_35634[(2)] = null);

(statearr_32347_35634[(1)] = (21));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (1))){
var state_32338__$1 = state_32338;
var statearr_32348_35635 = state_32338__$1;
(statearr_32348_35635[(2)] = null);

(statearr_32348_35635[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (4))){
var inst_32291 = (state_32338[(7)]);
var inst_32291__$1 = (state_32338[(2)]);
var inst_32293 = (inst_32291__$1 == null);
var state_32338__$1 = (function (){var statearr_32352 = state_32338;
(statearr_32352[(7)] = inst_32291__$1);

return statearr_32352;
})();
if(cljs.core.truth_(inst_32293)){
var statearr_32353_35637 = state_32338__$1;
(statearr_32353_35637[(1)] = (5));

} else {
var statearr_32354_35638 = state_32338__$1;
(statearr_32354_35638[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (15))){
var inst_32311 = (state_32338[(8)]);
var state_32338__$1 = state_32338;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_32338__$1,(18),to,inst_32311);
} else {
if((state_val_32339 === (21))){
var inst_32326 = (state_32338[(2)]);
var state_32338__$1 = state_32338;
var statearr_32362_35639 = state_32338__$1;
(statearr_32362_35639[(2)] = inst_32326);

(statearr_32362_35639[(1)] = (13));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (13))){
var inst_32328 = (state_32338[(2)]);
var state_32338__$1 = (function (){var statearr_32364 = state_32338;
(statearr_32364[(9)] = inst_32328);

return statearr_32364;
})();
var statearr_32365_35641 = state_32338__$1;
(statearr_32365_35641[(2)] = null);

(statearr_32365_35641[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (6))){
var inst_32291 = (state_32338[(7)]);
var state_32338__$1 = state_32338;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_32338__$1,(11),inst_32291);
} else {
if((state_val_32339 === (17))){
var inst_32320 = (state_32338[(2)]);
var state_32338__$1 = state_32338;
if(cljs.core.truth_(inst_32320)){
var statearr_32370_35645 = state_32338__$1;
(statearr_32370_35645[(1)] = (19));

} else {
var statearr_32372_35646 = state_32338__$1;
(statearr_32372_35646[(1)] = (20));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (3))){
var inst_32333 = (state_32338[(2)]);
var state_32338__$1 = state_32338;
return cljs.core.async.impl.ioc_helpers.return_chan(state_32338__$1,inst_32333);
} else {
if((state_val_32339 === (12))){
var inst_32305 = (state_32338[(10)]);
var state_32338__$1 = state_32338;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_32338__$1,(14),inst_32305);
} else {
if((state_val_32339 === (2))){
var state_32338__$1 = state_32338;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_32338__$1,(4),results);
} else {
if((state_val_32339 === (19))){
var state_32338__$1 = state_32338;
var statearr_32377_35649 = state_32338__$1;
(statearr_32377_35649[(2)] = null);

(statearr_32377_35649[(1)] = (12));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (11))){
var inst_32305 = (state_32338[(2)]);
var state_32338__$1 = (function (){var statearr_32380 = state_32338;
(statearr_32380[(10)] = inst_32305);

return statearr_32380;
})();
var statearr_32383_35650 = state_32338__$1;
(statearr_32383_35650[(2)] = null);

(statearr_32383_35650[(1)] = (12));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (9))){
var state_32338__$1 = state_32338;
var statearr_32385_35651 = state_32338__$1;
(statearr_32385_35651[(2)] = null);

(statearr_32385_35651[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (5))){
var state_32338__$1 = state_32338;
if(cljs.core.truth_(close_QMARK_)){
var statearr_32390_35652 = state_32338__$1;
(statearr_32390_35652[(1)] = (8));

} else {
var statearr_32391_35653 = state_32338__$1;
(statearr_32391_35653[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (14))){
var inst_32311 = (state_32338[(8)]);
var inst_32313 = (state_32338[(11)]);
var inst_32311__$1 = (state_32338[(2)]);
var inst_32312 = (inst_32311__$1 == null);
var inst_32313__$1 = cljs.core.not(inst_32312);
var state_32338__$1 = (function (){var statearr_32393 = state_32338;
(statearr_32393[(8)] = inst_32311__$1);

(statearr_32393[(11)] = inst_32313__$1);

return statearr_32393;
})();
if(inst_32313__$1){
var statearr_32394_35654 = state_32338__$1;
(statearr_32394_35654[(1)] = (15));

} else {
var statearr_32395_35655 = state_32338__$1;
(statearr_32395_35655[(1)] = (16));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (16))){
var inst_32313 = (state_32338[(11)]);
var state_32338__$1 = state_32338;
var statearr_32399_35656 = state_32338__$1;
(statearr_32399_35656[(2)] = inst_32313);

(statearr_32399_35656[(1)] = (17));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (10))){
var inst_32301 = (state_32338[(2)]);
var state_32338__$1 = state_32338;
var statearr_32400_35658 = state_32338__$1;
(statearr_32400_35658[(2)] = inst_32301);

(statearr_32400_35658[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (18))){
var inst_32316 = (state_32338[(2)]);
var state_32338__$1 = state_32338;
var statearr_32402_35661 = state_32338__$1;
(statearr_32402_35661[(2)] = inst_32316);

(statearr_32402_35661[(1)] = (17));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32339 === (8))){
var inst_32298 = cljs.core.async.close_BANG_(to);
var state_32338__$1 = state_32338;
var statearr_32406_35662 = state_32338__$1;
(statearr_32406_35662[(2)] = inst_32298);

(statearr_32406_35662[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
}
});
return (function() {
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__ = null;
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0 = (function (){
var statearr_32408 = [null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_32408[(0)] = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__);

(statearr_32408[(1)] = (1));

return statearr_32408;
});
var cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1 = (function (state_32338){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_32338);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e32412){var ex__30405__auto__ = e32412;
var statearr_32413_35663 = state_32338;
(statearr_32413_35663[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_32338[(4)]))){
var statearr_32414_35664 = state_32338;
(statearr_32414_35664[(1)] = cljs.core.first((state_32338[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__35671 = state_32338;
state_32338 = G__35671;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__ = function(state_32338){
switch(arguments.length){
case 0:
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1.call(this,state_32338);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____0;
cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$pipeline_STAR__$_state_machine__30402__auto____1;
return cljs$core$async$pipeline_STAR__$_state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_32417 = f__31515__auto__();
(statearr_32417[(6)] = c__31514__auto__);

return statearr_32417;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));

return c__31514__auto__;
});
/**
 * Takes elements from the from channel and supplies them to the to
 *   channel, subject to the async function af, with parallelism n. af
 *   must be a function of two arguments, the first an input value and
 *   the second a channel on which to place the result(s). The
 *   presumption is that af will return immediately, having launched some
 *   asynchronous operation whose completion/callback will put results on
 *   the channel, then close! it. Outputs will be returned in order
 *   relative to the inputs. By default, the to channel will be closed
 *   when the from channel closes, but can be determined by the close?
 *   parameter. Will stop consuming the from channel if the to channel
 *   closes. See also pipeline, pipeline-blocking.
 */
cljs.core.async.pipeline_async = (function cljs$core$async$pipeline_async(var_args){
var G__32425 = arguments.length;
switch (G__32425) {
case 4:
return cljs.core.async.pipeline_async.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
case 5:
return cljs.core.async.pipeline_async.cljs$core$IFn$_invoke$arity$5((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]),(arguments[(4)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.pipeline_async.cljs$core$IFn$_invoke$arity$4 = (function (n,to,af,from){
return cljs.core.async.pipeline_async.cljs$core$IFn$_invoke$arity$5(n,to,af,from,true);
}));

(cljs.core.async.pipeline_async.cljs$core$IFn$_invoke$arity$5 = (function (n,to,af,from,close_QMARK_){
return cljs.core.async.pipeline_STAR_(n,to,af,from,close_QMARK_,null,new cljs.core.Keyword(null,"async","async",1050769601));
}));

(cljs.core.async.pipeline_async.cljs$lang$maxFixedArity = 5);

/**
 * Takes elements from the from channel and supplies them to the to
 *   channel, subject to the transducer xf, with parallelism n. Because
 *   it is parallel, the transducer will be applied independently to each
 *   element, not across elements, and may produce zero or more outputs
 *   per input.  Outputs will be returned in order relative to the
 *   inputs. By default, the to channel will be closed when the from
 *   channel closes, but can be determined by the close?  parameter. Will
 *   stop consuming the from channel if the to channel closes.
 * 
 *   Note this is supplied for API compatibility with the Clojure version.
 *   Values of N > 1 will not result in actual concurrency in a
 *   single-threaded runtime.
 */
cljs.core.async.pipeline = (function cljs$core$async$pipeline(var_args){
var G__32438 = arguments.length;
switch (G__32438) {
case 4:
return cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
case 5:
return cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$5((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]),(arguments[(4)]));

break;
case 6:
return cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$6((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]),(arguments[(4)]),(arguments[(5)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$4 = (function (n,to,xf,from){
return cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$5(n,to,xf,from,true);
}));

(cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$5 = (function (n,to,xf,from,close_QMARK_){
return cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$6(n,to,xf,from,close_QMARK_,null);
}));

(cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$6 = (function (n,to,xf,from,close_QMARK_,ex_handler){
return cljs.core.async.pipeline_STAR_(n,to,xf,from,close_QMARK_,ex_handler,new cljs.core.Keyword(null,"compute","compute",1555393130));
}));

(cljs.core.async.pipeline.cljs$lang$maxFixedArity = 6);

/**
 * Takes a predicate and a source channel and returns a vector of two
 *   channels, the first of which will contain the values for which the
 *   predicate returned true, the second those for which it returned
 *   false.
 * 
 *   The out channels will be unbuffered by default, or two buf-or-ns can
 *   be supplied. The channels will close after the source channel has
 *   closed.
 */
cljs.core.async.split = (function cljs$core$async$split(var_args){
var G__32454 = arguments.length;
switch (G__32454) {
case 2:
return cljs.core.async.split.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 4:
return cljs.core.async.split.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.split.cljs$core$IFn$_invoke$arity$2 = (function (p,ch){
return cljs.core.async.split.cljs$core$IFn$_invoke$arity$4(p,ch,null,null);
}));

(cljs.core.async.split.cljs$core$IFn$_invoke$arity$4 = (function (p,ch,t_buf_or_n,f_buf_or_n){
var tc = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(t_buf_or_n);
var fc = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(f_buf_or_n);
var c__31514__auto___35689 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_32513){
var state_val_32514 = (state_32513[(1)]);
if((state_val_32514 === (7))){
var inst_32509 = (state_32513[(2)]);
var state_32513__$1 = state_32513;
var statearr_32523_35693 = state_32513__$1;
(statearr_32523_35693[(2)] = inst_32509);

(statearr_32523_35693[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32514 === (1))){
var state_32513__$1 = state_32513;
var statearr_32524_35694 = state_32513__$1;
(statearr_32524_35694[(2)] = null);

(statearr_32524_35694[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32514 === (4))){
var inst_32486 = (state_32513[(7)]);
var inst_32486__$1 = (state_32513[(2)]);
var inst_32488 = (inst_32486__$1 == null);
var state_32513__$1 = (function (){var statearr_32525 = state_32513;
(statearr_32525[(7)] = inst_32486__$1);

return statearr_32525;
})();
if(cljs.core.truth_(inst_32488)){
var statearr_32526_35696 = state_32513__$1;
(statearr_32526_35696[(1)] = (5));

} else {
var statearr_32527_35697 = state_32513__$1;
(statearr_32527_35697[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32514 === (13))){
var state_32513__$1 = state_32513;
var statearr_32528_35698 = state_32513__$1;
(statearr_32528_35698[(2)] = null);

(statearr_32528_35698[(1)] = (14));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32514 === (6))){
var inst_32486 = (state_32513[(7)]);
var inst_32495 = (p.cljs$core$IFn$_invoke$arity$1 ? p.cljs$core$IFn$_invoke$arity$1(inst_32486) : p.call(null, inst_32486));
var state_32513__$1 = state_32513;
if(cljs.core.truth_(inst_32495)){
var statearr_32531_35699 = state_32513__$1;
(statearr_32531_35699[(1)] = (9));

} else {
var statearr_32532_35701 = state_32513__$1;
(statearr_32532_35701[(1)] = (10));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32514 === (3))){
var inst_32511 = (state_32513[(2)]);
var state_32513__$1 = state_32513;
return cljs.core.async.impl.ioc_helpers.return_chan(state_32513__$1,inst_32511);
} else {
if((state_val_32514 === (12))){
var state_32513__$1 = state_32513;
var statearr_32533_35702 = state_32513__$1;
(statearr_32533_35702[(2)] = null);

(statearr_32533_35702[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32514 === (2))){
var state_32513__$1 = state_32513;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_32513__$1,(4),ch);
} else {
if((state_val_32514 === (11))){
var inst_32486 = (state_32513[(7)]);
var inst_32500 = (state_32513[(2)]);
var state_32513__$1 = state_32513;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_32513__$1,(8),inst_32500,inst_32486);
} else {
if((state_val_32514 === (9))){
var state_32513__$1 = state_32513;
var statearr_32535_35708 = state_32513__$1;
(statearr_32535_35708[(2)] = tc);

(statearr_32535_35708[(1)] = (11));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32514 === (5))){
var inst_32491 = cljs.core.async.close_BANG_(tc);
var inst_32493 = cljs.core.async.close_BANG_(fc);
var state_32513__$1 = (function (){var statearr_32536 = state_32513;
(statearr_32536[(8)] = inst_32491);

return statearr_32536;
})();
var statearr_32537_35709 = state_32513__$1;
(statearr_32537_35709[(2)] = inst_32493);

(statearr_32537_35709[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32514 === (14))){
var inst_32507 = (state_32513[(2)]);
var state_32513__$1 = state_32513;
var statearr_32538_35710 = state_32513__$1;
(statearr_32538_35710[(2)] = inst_32507);

(statearr_32538_35710[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32514 === (10))){
var state_32513__$1 = state_32513;
var statearr_32539_35712 = state_32513__$1;
(statearr_32539_35712[(2)] = fc);

(statearr_32539_35712[(1)] = (11));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32514 === (8))){
var inst_32502 = (state_32513[(2)]);
var state_32513__$1 = state_32513;
if(cljs.core.truth_(inst_32502)){
var statearr_32541_35715 = state_32513__$1;
(statearr_32541_35715[(1)] = (12));

} else {
var statearr_32542_35716 = state_32513__$1;
(statearr_32542_35716[(1)] = (13));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__30402__auto__ = null;
var cljs$core$async$state_machine__30402__auto____0 = (function (){
var statearr_32544 = [null,null,null,null,null,null,null,null,null];
(statearr_32544[(0)] = cljs$core$async$state_machine__30402__auto__);

(statearr_32544[(1)] = (1));

return statearr_32544;
});
var cljs$core$async$state_machine__30402__auto____1 = (function (state_32513){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_32513);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e32545){var ex__30405__auto__ = e32545;
var statearr_32546_35718 = state_32513;
(statearr_32546_35718[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_32513[(4)]))){
var statearr_32547_35719 = state_32513;
(statearr_32547_35719[(1)] = cljs.core.first((state_32513[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__35720 = state_32513;
state_32513 = G__35720;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$state_machine__30402__auto__ = function(state_32513){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__30402__auto____1.call(this,state_32513);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__30402__auto____0;
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__30402__auto____1;
return cljs$core$async$state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_32548 = f__31515__auto__();
(statearr_32548[(6)] = c__31514__auto___35689);

return statearr_32548;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));


return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [tc,fc], null);
}));

(cljs.core.async.split.cljs$lang$maxFixedArity = 4);

/**
 * f should be a function of 2 arguments. Returns a channel containing
 *   the single result of applying f to init and the first item from the
 *   channel, then applying f to that result and the 2nd item, etc. If
 *   the channel closes without yielding items, returns init and f is not
 *   called. ch must close before reduce produces a result.
 */
cljs.core.async.reduce = (function cljs$core$async$reduce(f,init,ch){
var c__31514__auto__ = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_32571){
var state_val_32572 = (state_32571[(1)]);
if((state_val_32572 === (7))){
var inst_32567 = (state_32571[(2)]);
var state_32571__$1 = state_32571;
var statearr_32574_35725 = state_32571__$1;
(statearr_32574_35725[(2)] = inst_32567);

(statearr_32574_35725[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32572 === (1))){
var inst_32550 = init;
var inst_32551 = inst_32550;
var state_32571__$1 = (function (){var statearr_32576 = state_32571;
(statearr_32576[(7)] = inst_32551);

return statearr_32576;
})();
var statearr_32579_35733 = state_32571__$1;
(statearr_32579_35733[(2)] = null);

(statearr_32579_35733[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32572 === (4))){
var inst_32554 = (state_32571[(8)]);
var inst_32554__$1 = (state_32571[(2)]);
var inst_32555 = (inst_32554__$1 == null);
var state_32571__$1 = (function (){var statearr_32580 = state_32571;
(statearr_32580[(8)] = inst_32554__$1);

return statearr_32580;
})();
if(cljs.core.truth_(inst_32555)){
var statearr_32582_35737 = state_32571__$1;
(statearr_32582_35737[(1)] = (5));

} else {
var statearr_32583_35738 = state_32571__$1;
(statearr_32583_35738[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32572 === (6))){
var inst_32551 = (state_32571[(7)]);
var inst_32554 = (state_32571[(8)]);
var inst_32558 = (state_32571[(9)]);
var inst_32558__$1 = (f.cljs$core$IFn$_invoke$arity$2 ? f.cljs$core$IFn$_invoke$arity$2(inst_32551,inst_32554) : f.call(null, inst_32551,inst_32554));
var inst_32559 = cljs.core.reduced_QMARK_(inst_32558__$1);
var state_32571__$1 = (function (){var statearr_32592 = state_32571;
(statearr_32592[(9)] = inst_32558__$1);

return statearr_32592;
})();
if(inst_32559){
var statearr_32597_35739 = state_32571__$1;
(statearr_32597_35739[(1)] = (8));

} else {
var statearr_32598_35740 = state_32571__$1;
(statearr_32598_35740[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32572 === (3))){
var inst_32569 = (state_32571[(2)]);
var state_32571__$1 = state_32571;
return cljs.core.async.impl.ioc_helpers.return_chan(state_32571__$1,inst_32569);
} else {
if((state_val_32572 === (2))){
var state_32571__$1 = state_32571;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_32571__$1,(4),ch);
} else {
if((state_val_32572 === (9))){
var inst_32558 = (state_32571[(9)]);
var inst_32551 = inst_32558;
var state_32571__$1 = (function (){var statearr_32606 = state_32571;
(statearr_32606[(7)] = inst_32551);

return statearr_32606;
})();
var statearr_32607_35743 = state_32571__$1;
(statearr_32607_35743[(2)] = null);

(statearr_32607_35743[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32572 === (5))){
var inst_32551 = (state_32571[(7)]);
var state_32571__$1 = state_32571;
var statearr_32608_35744 = state_32571__$1;
(statearr_32608_35744[(2)] = inst_32551);

(statearr_32608_35744[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32572 === (10))){
var inst_32565 = (state_32571[(2)]);
var state_32571__$1 = state_32571;
var statearr_32609_35745 = state_32571__$1;
(statearr_32609_35745[(2)] = inst_32565);

(statearr_32609_35745[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32572 === (8))){
var inst_32558 = (state_32571[(9)]);
var inst_32561 = cljs.core.deref(inst_32558);
var state_32571__$1 = state_32571;
var statearr_32614_35746 = state_32571__$1;
(statearr_32614_35746[(2)] = inst_32561);

(statearr_32614_35746[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
});
return (function() {
var cljs$core$async$reduce_$_state_machine__30402__auto__ = null;
var cljs$core$async$reduce_$_state_machine__30402__auto____0 = (function (){
var statearr_32620 = [null,null,null,null,null,null,null,null,null,null];
(statearr_32620[(0)] = cljs$core$async$reduce_$_state_machine__30402__auto__);

(statearr_32620[(1)] = (1));

return statearr_32620;
});
var cljs$core$async$reduce_$_state_machine__30402__auto____1 = (function (state_32571){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_32571);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e32624){var ex__30405__auto__ = e32624;
var statearr_32625_35747 = state_32571;
(statearr_32625_35747[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_32571[(4)]))){
var statearr_32646_35748 = state_32571;
(statearr_32646_35748[(1)] = cljs.core.first((state_32571[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__35749 = state_32571;
state_32571 = G__35749;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$reduce_$_state_machine__30402__auto__ = function(state_32571){
switch(arguments.length){
case 0:
return cljs$core$async$reduce_$_state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$reduce_$_state_machine__30402__auto____1.call(this,state_32571);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$reduce_$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$reduce_$_state_machine__30402__auto____0;
cljs$core$async$reduce_$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$reduce_$_state_machine__30402__auto____1;
return cljs$core$async$reduce_$_state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_32649 = f__31515__auto__();
(statearr_32649[(6)] = c__31514__auto__);

return statearr_32649;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));

return c__31514__auto__;
});
/**
 * async/reduces a channel with a transformation (xform f).
 *   Returns a channel containing the result.  ch must close before
 *   transduce produces a result.
 */
cljs.core.async.transduce = (function cljs$core$async$transduce(xform,f,init,ch){
var f__$1 = (xform.cljs$core$IFn$_invoke$arity$1 ? xform.cljs$core$IFn$_invoke$arity$1(f) : xform.call(null, f));
var c__31514__auto__ = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_32655){
var state_val_32656 = (state_32655[(1)]);
if((state_val_32656 === (1))){
var inst_32650 = cljs.core.async.reduce(f__$1,init,ch);
var state_32655__$1 = state_32655;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_32655__$1,(2),inst_32650);
} else {
if((state_val_32656 === (2))){
var inst_32652 = (state_32655[(2)]);
var inst_32653 = (f__$1.cljs$core$IFn$_invoke$arity$1 ? f__$1.cljs$core$IFn$_invoke$arity$1(inst_32652) : f__$1.call(null, inst_32652));
var state_32655__$1 = state_32655;
return cljs.core.async.impl.ioc_helpers.return_chan(state_32655__$1,inst_32653);
} else {
return null;
}
}
});
return (function() {
var cljs$core$async$transduce_$_state_machine__30402__auto__ = null;
var cljs$core$async$transduce_$_state_machine__30402__auto____0 = (function (){
var statearr_32677 = [null,null,null,null,null,null,null];
(statearr_32677[(0)] = cljs$core$async$transduce_$_state_machine__30402__auto__);

(statearr_32677[(1)] = (1));

return statearr_32677;
});
var cljs$core$async$transduce_$_state_machine__30402__auto____1 = (function (state_32655){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_32655);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e32688){var ex__30405__auto__ = e32688;
var statearr_32689_35752 = state_32655;
(statearr_32689_35752[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_32655[(4)]))){
var statearr_32732_35753 = state_32655;
(statearr_32732_35753[(1)] = cljs.core.first((state_32655[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__35754 = state_32655;
state_32655 = G__35754;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$transduce_$_state_machine__30402__auto__ = function(state_32655){
switch(arguments.length){
case 0:
return cljs$core$async$transduce_$_state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$transduce_$_state_machine__30402__auto____1.call(this,state_32655);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$transduce_$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$transduce_$_state_machine__30402__auto____0;
cljs$core$async$transduce_$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$transduce_$_state_machine__30402__auto____1;
return cljs$core$async$transduce_$_state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_32741 = f__31515__auto__();
(statearr_32741[(6)] = c__31514__auto__);

return statearr_32741;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));

return c__31514__auto__;
});
/**
 * Puts the contents of coll into the supplied channel.
 * 
 *   By default the channel will be closed after the items are copied,
 *   but can be determined by the close? parameter.
 * 
 *   Returns a channel which will close after the items are copied.
 */
cljs.core.async.onto_chan_BANG_ = (function cljs$core$async$onto_chan_BANG_(var_args){
var G__32744 = arguments.length;
switch (G__32744) {
case 2:
return cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$2 = (function (ch,coll){
return cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$3(ch,coll,true);
}));

(cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$3 = (function (ch,coll,close_QMARK_){
var c__31514__auto__ = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_32798){
var state_val_32799 = (state_32798[(1)]);
if((state_val_32799 === (7))){
var inst_32780 = (state_32798[(2)]);
var state_32798__$1 = state_32798;
var statearr_32811_35756 = state_32798__$1;
(statearr_32811_35756[(2)] = inst_32780);

(statearr_32811_35756[(1)] = (6));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32799 === (1))){
var inst_32773 = cljs.core.seq(coll);
var inst_32775 = inst_32773;
var state_32798__$1 = (function (){var statearr_32826 = state_32798;
(statearr_32826[(7)] = inst_32775);

return statearr_32826;
})();
var statearr_32828_35757 = state_32798__$1;
(statearr_32828_35757[(2)] = null);

(statearr_32828_35757[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32799 === (4))){
var inst_32775 = (state_32798[(7)]);
var inst_32778 = cljs.core.first(inst_32775);
var state_32798__$1 = state_32798;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_32798__$1,(7),ch,inst_32778);
} else {
if((state_val_32799 === (13))){
var inst_32792 = (state_32798[(2)]);
var state_32798__$1 = state_32798;
var statearr_32840_35762 = state_32798__$1;
(statearr_32840_35762[(2)] = inst_32792);

(statearr_32840_35762[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32799 === (6))){
var inst_32783 = (state_32798[(2)]);
var state_32798__$1 = state_32798;
if(cljs.core.truth_(inst_32783)){
var statearr_32855_35763 = state_32798__$1;
(statearr_32855_35763[(1)] = (8));

} else {
var statearr_32856_35764 = state_32798__$1;
(statearr_32856_35764[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32799 === (3))){
var inst_32796 = (state_32798[(2)]);
var state_32798__$1 = state_32798;
return cljs.core.async.impl.ioc_helpers.return_chan(state_32798__$1,inst_32796);
} else {
if((state_val_32799 === (12))){
var state_32798__$1 = state_32798;
var statearr_32861_35765 = state_32798__$1;
(statearr_32861_35765[(2)] = null);

(statearr_32861_35765[(1)] = (13));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32799 === (2))){
var inst_32775 = (state_32798[(7)]);
var state_32798__$1 = state_32798;
if(cljs.core.truth_(inst_32775)){
var statearr_32863_35770 = state_32798__$1;
(statearr_32863_35770[(1)] = (4));

} else {
var statearr_32864_35771 = state_32798__$1;
(statearr_32864_35771[(1)] = (5));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32799 === (11))){
var inst_32789 = cljs.core.async.close_BANG_(ch);
var state_32798__$1 = state_32798;
var statearr_32866_35772 = state_32798__$1;
(statearr_32866_35772[(2)] = inst_32789);

(statearr_32866_35772[(1)] = (13));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32799 === (9))){
var state_32798__$1 = state_32798;
if(cljs.core.truth_(close_QMARK_)){
var statearr_32868_35773 = state_32798__$1;
(statearr_32868_35773[(1)] = (11));

} else {
var statearr_32869_35774 = state_32798__$1;
(statearr_32869_35774[(1)] = (12));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32799 === (5))){
var inst_32775 = (state_32798[(7)]);
var state_32798__$1 = state_32798;
var statearr_32871_35775 = state_32798__$1;
(statearr_32871_35775[(2)] = inst_32775);

(statearr_32871_35775[(1)] = (6));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32799 === (10))){
var inst_32794 = (state_32798[(2)]);
var state_32798__$1 = state_32798;
var statearr_32875_35776 = state_32798__$1;
(statearr_32875_35776[(2)] = inst_32794);

(statearr_32875_35776[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_32799 === (8))){
var inst_32775 = (state_32798[(7)]);
var inst_32785 = cljs.core.next(inst_32775);
var inst_32775__$1 = inst_32785;
var state_32798__$1 = (function (){var statearr_32884 = state_32798;
(statearr_32884[(7)] = inst_32775__$1);

return statearr_32884;
})();
var statearr_32889_35777 = state_32798__$1;
(statearr_32889_35777[(2)] = null);

(statearr_32889_35777[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
}
}
}
});
return (function() {
var cljs$core$async$state_machine__30402__auto__ = null;
var cljs$core$async$state_machine__30402__auto____0 = (function (){
var statearr_32892 = [null,null,null,null,null,null,null,null];
(statearr_32892[(0)] = cljs$core$async$state_machine__30402__auto__);

(statearr_32892[(1)] = (1));

return statearr_32892;
});
var cljs$core$async$state_machine__30402__auto____1 = (function (state_32798){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_32798);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e32899){var ex__30405__auto__ = e32899;
var statearr_32900_35786 = state_32798;
(statearr_32900_35786[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_32798[(4)]))){
var statearr_32901_35787 = state_32798;
(statearr_32901_35787[(1)] = cljs.core.first((state_32798[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__35788 = state_32798;
state_32798 = G__35788;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$state_machine__30402__auto__ = function(state_32798){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__30402__auto____1.call(this,state_32798);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__30402__auto____0;
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__30402__auto____1;
return cljs$core$async$state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_32909 = f__31515__auto__();
(statearr_32909[(6)] = c__31514__auto__);

return statearr_32909;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));

return c__31514__auto__;
}));

(cljs.core.async.onto_chan_BANG_.cljs$lang$maxFixedArity = 3);

/**
 * Creates and returns a channel which contains the contents of coll,
 *   closing when exhausted.
 */
cljs.core.async.to_chan_BANG_ = (function cljs$core$async$to_chan_BANG_(coll){
var ch = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(cljs.core.bounded_count((100),coll));
cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$2(ch,coll);

return ch;
});
/**
 * Deprecated - use onto-chan!
 */
cljs.core.async.onto_chan = (function cljs$core$async$onto_chan(var_args){
var G__32920 = arguments.length;
switch (G__32920) {
case 2:
return cljs.core.async.onto_chan.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.onto_chan.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.onto_chan.cljs$core$IFn$_invoke$arity$2 = (function (ch,coll){
return cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$3(ch,coll,true);
}));

(cljs.core.async.onto_chan.cljs$core$IFn$_invoke$arity$3 = (function (ch,coll,close_QMARK_){
return cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$3(ch,coll,close_QMARK_);
}));

(cljs.core.async.onto_chan.cljs$lang$maxFixedArity = 3);

/**
 * Deprecated - use to-chan!
 */
cljs.core.async.to_chan = (function cljs$core$async$to_chan(coll){
return cljs.core.async.to_chan_BANG_(coll);
});

/**
 * @interface
 */
cljs.core.async.Mux = function(){};

var cljs$core$async$Mux$muxch_STAR_$dyn_35790 = (function (_){
var x__5350__auto__ = (((_ == null))?null:_);
var m__5351__auto__ = (cljs.core.async.muxch_STAR_[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$1(_) : m__5351__auto__.call(null, _));
} else {
var m__5349__auto__ = (cljs.core.async.muxch_STAR_["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$1(_) : m__5349__auto__.call(null, _));
} else {
throw cljs.core.missing_protocol("Mux.muxch*",_);
}
}
});
cljs.core.async.muxch_STAR_ = (function cljs$core$async$muxch_STAR_(_){
if((((!((_ == null)))) && ((!((_.cljs$core$async$Mux$muxch_STAR_$arity$1 == null)))))){
return _.cljs$core$async$Mux$muxch_STAR_$arity$1(_);
} else {
return cljs$core$async$Mux$muxch_STAR_$dyn_35790(_);
}
});


/**
 * @interface
 */
cljs.core.async.Mult = function(){};

var cljs$core$async$Mult$tap_STAR_$dyn_35791 = (function (m,ch,close_QMARK_){
var x__5350__auto__ = (((m == null))?null:m);
var m__5351__auto__ = (cljs.core.async.tap_STAR_[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$3 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$3(m,ch,close_QMARK_) : m__5351__auto__.call(null, m,ch,close_QMARK_));
} else {
var m__5349__auto__ = (cljs.core.async.tap_STAR_["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$3 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$3(m,ch,close_QMARK_) : m__5349__auto__.call(null, m,ch,close_QMARK_));
} else {
throw cljs.core.missing_protocol("Mult.tap*",m);
}
}
});
cljs.core.async.tap_STAR_ = (function cljs$core$async$tap_STAR_(m,ch,close_QMARK_){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mult$tap_STAR_$arity$3 == null)))))){
return m.cljs$core$async$Mult$tap_STAR_$arity$3(m,ch,close_QMARK_);
} else {
return cljs$core$async$Mult$tap_STAR_$dyn_35791(m,ch,close_QMARK_);
}
});

var cljs$core$async$Mult$untap_STAR_$dyn_35795 = (function (m,ch){
var x__5350__auto__ = (((m == null))?null:m);
var m__5351__auto__ = (cljs.core.async.untap_STAR_[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$2(m,ch) : m__5351__auto__.call(null, m,ch));
} else {
var m__5349__auto__ = (cljs.core.async.untap_STAR_["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$2(m,ch) : m__5349__auto__.call(null, m,ch));
} else {
throw cljs.core.missing_protocol("Mult.untap*",m);
}
}
});
cljs.core.async.untap_STAR_ = (function cljs$core$async$untap_STAR_(m,ch){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mult$untap_STAR_$arity$2 == null)))))){
return m.cljs$core$async$Mult$untap_STAR_$arity$2(m,ch);
} else {
return cljs$core$async$Mult$untap_STAR_$dyn_35795(m,ch);
}
});

var cljs$core$async$Mult$untap_all_STAR_$dyn_35796 = (function (m){
var x__5350__auto__ = (((m == null))?null:m);
var m__5351__auto__ = (cljs.core.async.untap_all_STAR_[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$1(m) : m__5351__auto__.call(null, m));
} else {
var m__5349__auto__ = (cljs.core.async.untap_all_STAR_["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$1(m) : m__5349__auto__.call(null, m));
} else {
throw cljs.core.missing_protocol("Mult.untap-all*",m);
}
}
});
cljs.core.async.untap_all_STAR_ = (function cljs$core$async$untap_all_STAR_(m){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mult$untap_all_STAR_$arity$1 == null)))))){
return m.cljs$core$async$Mult$untap_all_STAR_$arity$1(m);
} else {
return cljs$core$async$Mult$untap_all_STAR_$dyn_35796(m);
}
});


/**
* @constructor
 * @implements {cljs.core.async.Mult}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.async.Mux}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async32980 = (function (ch,cs,meta32981){
this.ch = ch;
this.cs = cs;
this.meta32981 = meta32981;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async32980.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_32982,meta32981__$1){
var self__ = this;
var _32982__$1 = this;
return (new cljs.core.async.t_cljs$core$async32980(self__.ch,self__.cs,meta32981__$1));
}));

(cljs.core.async.t_cljs$core$async32980.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_32982){
var self__ = this;
var _32982__$1 = this;
return self__.meta32981;
}));

(cljs.core.async.t_cljs$core$async32980.prototype.cljs$core$async$Mux$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async32980.prototype.cljs$core$async$Mux$muxch_STAR_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return self__.ch;
}));

(cljs.core.async.t_cljs$core$async32980.prototype.cljs$core$async$Mult$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async32980.prototype.cljs$core$async$Mult$tap_STAR_$arity$3 = (function (_,ch__$1,close_QMARK_){
var self__ = this;
var ___$1 = this;
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(self__.cs,cljs.core.assoc,ch__$1,close_QMARK_);

return null;
}));

(cljs.core.async.t_cljs$core$async32980.prototype.cljs$core$async$Mult$untap_STAR_$arity$2 = (function (_,ch__$1){
var self__ = this;
var ___$1 = this;
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(self__.cs,cljs.core.dissoc,ch__$1);

return null;
}));

(cljs.core.async.t_cljs$core$async32980.prototype.cljs$core$async$Mult$untap_all_STAR_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
cljs.core.reset_BANG_(self__.cs,cljs.core.PersistentArrayMap.EMPTY);

return null;
}));

(cljs.core.async.t_cljs$core$async32980.getBasis = (function (){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"ch","ch",1085813622,null),new cljs.core.Symbol(null,"cs","cs",-117024463,null),new cljs.core.Symbol(null,"meta32981","meta32981",-369682797,null)], null);
}));

(cljs.core.async.t_cljs$core$async32980.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async32980.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async32980");

(cljs.core.async.t_cljs$core$async32980.cljs$lang$ctorPrWriter = (function (this__5287__auto__,writer__5288__auto__,opt__5289__auto__){
return cljs.core._write(writer__5288__auto__,"cljs.core.async/t_cljs$core$async32980");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async32980.
 */
cljs.core.async.__GT_t_cljs$core$async32980 = (function cljs$core$async$__GT_t_cljs$core$async32980(ch,cs,meta32981){
return (new cljs.core.async.t_cljs$core$async32980(ch,cs,meta32981));
});


/**
 * Creates and returns a mult(iple) of the supplied channel. Channels
 *   containing copies of the channel can be created with 'tap', and
 *   detached with 'untap'.
 * 
 *   Each item is distributed to all taps in parallel and synchronously,
 *   i.e. each tap must accept before the next item is distributed. Use
 *   buffering/windowing to prevent slow taps from holding up the mult.
 * 
 *   Items received when there are no taps get dropped.
 * 
 *   If a tap puts to a closed channel, it will be removed from the mult.
 */
cljs.core.async.mult = (function cljs$core$async$mult(ch){
var cs = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentArrayMap.EMPTY);
var m = (new cljs.core.async.t_cljs$core$async32980(ch,cs,cljs.core.PersistentArrayMap.EMPTY));
var dchan = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
var dctr = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(null);
var done = (function (_){
if((cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$2(dctr,cljs.core.dec) === (0))){
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2(dchan,true);
} else {
return null;
}
});
var c__31514__auto___35806 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_33309){
var state_val_33310 = (state_33309[(1)]);
if((state_val_33310 === (7))){
var inst_33297 = (state_33309[(2)]);
var state_33309__$1 = state_33309;
var statearr_33316_35807 = state_33309__$1;
(statearr_33316_35807[(2)] = inst_33297);

(statearr_33316_35807[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (20))){
var inst_33174 = (state_33309[(7)]);
var inst_33192 = cljs.core.first(inst_33174);
var inst_33193 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_33192,(0),null);
var inst_33194 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_33192,(1),null);
var state_33309__$1 = (function (){var statearr_33319 = state_33309;
(statearr_33319[(8)] = inst_33193);

return statearr_33319;
})();
if(cljs.core.truth_(inst_33194)){
var statearr_33320_35808 = state_33309__$1;
(statearr_33320_35808[(1)] = (22));

} else {
var statearr_33322_35809 = state_33309__$1;
(statearr_33322_35809[(1)] = (23));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (27))){
var inst_33119 = (state_33309[(9)]);
var inst_33231 = (state_33309[(10)]);
var inst_33233 = (state_33309[(11)]);
var inst_33240 = (state_33309[(12)]);
var inst_33240__$1 = cljs.core._nth(inst_33231,inst_33233);
var inst_33241 = cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$3(inst_33240__$1,inst_33119,done);
var state_33309__$1 = (function (){var statearr_33327 = state_33309;
(statearr_33327[(12)] = inst_33240__$1);

return statearr_33327;
})();
if(cljs.core.truth_(inst_33241)){
var statearr_33328_35810 = state_33309__$1;
(statearr_33328_35810[(1)] = (30));

} else {
var statearr_33329_35811 = state_33309__$1;
(statearr_33329_35811[(1)] = (31));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (1))){
var state_33309__$1 = state_33309;
var statearr_33333_35812 = state_33309__$1;
(statearr_33333_35812[(2)] = null);

(statearr_33333_35812[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (24))){
var inst_33174 = (state_33309[(7)]);
var inst_33205 = (state_33309[(2)]);
var inst_33207 = cljs.core.next(inst_33174);
var inst_33143 = inst_33207;
var inst_33144 = null;
var inst_33145 = (0);
var inst_33146 = (0);
var state_33309__$1 = (function (){var statearr_33340 = state_33309;
(statearr_33340[(13)] = inst_33144);

(statearr_33340[(14)] = inst_33146);

(statearr_33340[(15)] = inst_33205);

(statearr_33340[(16)] = inst_33145);

(statearr_33340[(17)] = inst_33143);

return statearr_33340;
})();
var statearr_33344_35816 = state_33309__$1;
(statearr_33344_35816[(2)] = null);

(statearr_33344_35816[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (39))){
var state_33309__$1 = state_33309;
var statearr_33351_35817 = state_33309__$1;
(statearr_33351_35817[(2)] = null);

(statearr_33351_35817[(1)] = (41));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (4))){
var inst_33119 = (state_33309[(9)]);
var inst_33119__$1 = (state_33309[(2)]);
var inst_33124 = (inst_33119__$1 == null);
var state_33309__$1 = (function (){var statearr_33353 = state_33309;
(statearr_33353[(9)] = inst_33119__$1);

return statearr_33353;
})();
if(cljs.core.truth_(inst_33124)){
var statearr_33355_35819 = state_33309__$1;
(statearr_33355_35819[(1)] = (5));

} else {
var statearr_33356_35820 = state_33309__$1;
(statearr_33356_35820[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (15))){
var inst_33144 = (state_33309[(13)]);
var inst_33146 = (state_33309[(14)]);
var inst_33145 = (state_33309[(16)]);
var inst_33143 = (state_33309[(17)]);
var inst_33167 = (state_33309[(2)]);
var inst_33169 = (inst_33146 + (1));
var tmp33345 = inst_33144;
var tmp33346 = inst_33145;
var tmp33347 = inst_33143;
var inst_33143__$1 = tmp33347;
var inst_33144__$1 = tmp33345;
var inst_33145__$1 = tmp33346;
var inst_33146__$1 = inst_33169;
var state_33309__$1 = (function (){var statearr_33361 = state_33309;
(statearr_33361[(13)] = inst_33144__$1);

(statearr_33361[(14)] = inst_33146__$1);

(statearr_33361[(16)] = inst_33145__$1);

(statearr_33361[(18)] = inst_33167);

(statearr_33361[(17)] = inst_33143__$1);

return statearr_33361;
})();
var statearr_33364_35821 = state_33309__$1;
(statearr_33364_35821[(2)] = null);

(statearr_33364_35821[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (21))){
var inst_33212 = (state_33309[(2)]);
var state_33309__$1 = state_33309;
var statearr_33372_35824 = state_33309__$1;
(statearr_33372_35824[(2)] = inst_33212);

(statearr_33372_35824[(1)] = (18));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (31))){
var inst_33240 = (state_33309[(12)]);
var inst_33244 = m.cljs$core$async$Mult$untap_STAR_$arity$2(null, inst_33240);
var state_33309__$1 = state_33309;
var statearr_33374_35825 = state_33309__$1;
(statearr_33374_35825[(2)] = inst_33244);

(statearr_33374_35825[(1)] = (32));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (32))){
var inst_33230 = (state_33309[(19)]);
var inst_33231 = (state_33309[(10)]);
var inst_33233 = (state_33309[(11)]);
var inst_33232 = (state_33309[(20)]);
var inst_33246 = (state_33309[(2)]);
var inst_33248 = (inst_33233 + (1));
var tmp33365 = inst_33230;
var tmp33366 = inst_33231;
var tmp33367 = inst_33232;
var inst_33230__$1 = tmp33365;
var inst_33231__$1 = tmp33366;
var inst_33232__$1 = tmp33367;
var inst_33233__$1 = inst_33248;
var state_33309__$1 = (function (){var statearr_33375 = state_33309;
(statearr_33375[(19)] = inst_33230__$1);

(statearr_33375[(10)] = inst_33231__$1);

(statearr_33375[(21)] = inst_33246);

(statearr_33375[(11)] = inst_33233__$1);

(statearr_33375[(20)] = inst_33232__$1);

return statearr_33375;
})();
var statearr_33376_35830 = state_33309__$1;
(statearr_33376_35830[(2)] = null);

(statearr_33376_35830[(1)] = (25));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (40))){
var inst_33266 = (state_33309[(22)]);
var inst_33274 = m.cljs$core$async$Mult$untap_STAR_$arity$2(null, inst_33266);
var state_33309__$1 = state_33309;
var statearr_33378_35834 = state_33309__$1;
(statearr_33378_35834[(2)] = inst_33274);

(statearr_33378_35834[(1)] = (41));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (33))){
var inst_33251 = (state_33309[(23)]);
var inst_33255 = cljs.core.chunked_seq_QMARK_(inst_33251);
var state_33309__$1 = state_33309;
if(inst_33255){
var statearr_33380_35835 = state_33309__$1;
(statearr_33380_35835[(1)] = (36));

} else {
var statearr_33381_35836 = state_33309__$1;
(statearr_33381_35836[(1)] = (37));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (13))){
var inst_33157 = (state_33309[(24)]);
var inst_33164 = cljs.core.async.close_BANG_(inst_33157);
var state_33309__$1 = state_33309;
var statearr_33385_35837 = state_33309__$1;
(statearr_33385_35837[(2)] = inst_33164);

(statearr_33385_35837[(1)] = (15));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (22))){
var inst_33193 = (state_33309[(8)]);
var inst_33202 = cljs.core.async.close_BANG_(inst_33193);
var state_33309__$1 = state_33309;
var statearr_33390_35838 = state_33309__$1;
(statearr_33390_35838[(2)] = inst_33202);

(statearr_33390_35838[(1)] = (24));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (36))){
var inst_33251 = (state_33309[(23)]);
var inst_33259 = cljs.core.chunk_first(inst_33251);
var inst_33261 = cljs.core.chunk_rest(inst_33251);
var inst_33262 = cljs.core.count(inst_33259);
var inst_33230 = inst_33261;
var inst_33231 = inst_33259;
var inst_33232 = inst_33262;
var inst_33233 = (0);
var state_33309__$1 = (function (){var statearr_33392 = state_33309;
(statearr_33392[(19)] = inst_33230);

(statearr_33392[(10)] = inst_33231);

(statearr_33392[(11)] = inst_33233);

(statearr_33392[(20)] = inst_33232);

return statearr_33392;
})();
var statearr_33393_35839 = state_33309__$1;
(statearr_33393_35839[(2)] = null);

(statearr_33393_35839[(1)] = (25));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (41))){
var inst_33251 = (state_33309[(23)]);
var inst_33276 = (state_33309[(2)]);
var inst_33277 = cljs.core.next(inst_33251);
var inst_33230 = inst_33277;
var inst_33231 = null;
var inst_33232 = (0);
var inst_33233 = (0);
var state_33309__$1 = (function (){var statearr_33398 = state_33309;
(statearr_33398[(25)] = inst_33276);

(statearr_33398[(19)] = inst_33230);

(statearr_33398[(10)] = inst_33231);

(statearr_33398[(11)] = inst_33233);

(statearr_33398[(20)] = inst_33232);

return statearr_33398;
})();
var statearr_33399_35840 = state_33309__$1;
(statearr_33399_35840[(2)] = null);

(statearr_33399_35840[(1)] = (25));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (43))){
var state_33309__$1 = state_33309;
var statearr_33400_35842 = state_33309__$1;
(statearr_33400_35842[(2)] = null);

(statearr_33400_35842[(1)] = (44));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (29))){
var inst_33285 = (state_33309[(2)]);
var state_33309__$1 = state_33309;
var statearr_33404_35845 = state_33309__$1;
(statearr_33404_35845[(2)] = inst_33285);

(statearr_33404_35845[(1)] = (26));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (44))){
var inst_33294 = (state_33309[(2)]);
var state_33309__$1 = (function (){var statearr_33409 = state_33309;
(statearr_33409[(26)] = inst_33294);

return statearr_33409;
})();
var statearr_33412_35846 = state_33309__$1;
(statearr_33412_35846[(2)] = null);

(statearr_33412_35846[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (6))){
var inst_33222 = (state_33309[(27)]);
var inst_33221 = cljs.core.deref(cs);
var inst_33222__$1 = cljs.core.keys(inst_33221);
var inst_33223 = cljs.core.count(inst_33222__$1);
var inst_33224 = cljs.core.reset_BANG_(dctr,inst_33223);
var inst_33229 = cljs.core.seq(inst_33222__$1);
var inst_33230 = inst_33229;
var inst_33231 = null;
var inst_33232 = (0);
var inst_33233 = (0);
var state_33309__$1 = (function (){var statearr_33415 = state_33309;
(statearr_33415[(28)] = inst_33224);

(statearr_33415[(19)] = inst_33230);

(statearr_33415[(10)] = inst_33231);

(statearr_33415[(27)] = inst_33222__$1);

(statearr_33415[(11)] = inst_33233);

(statearr_33415[(20)] = inst_33232);

return statearr_33415;
})();
var statearr_33416_35847 = state_33309__$1;
(statearr_33416_35847[(2)] = null);

(statearr_33416_35847[(1)] = (25));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (28))){
var inst_33251 = (state_33309[(23)]);
var inst_33230 = (state_33309[(19)]);
var inst_33251__$1 = cljs.core.seq(inst_33230);
var state_33309__$1 = (function (){var statearr_33422 = state_33309;
(statearr_33422[(23)] = inst_33251__$1);

return statearr_33422;
})();
if(inst_33251__$1){
var statearr_33425_35848 = state_33309__$1;
(statearr_33425_35848[(1)] = (33));

} else {
var statearr_33430_35849 = state_33309__$1;
(statearr_33430_35849[(1)] = (34));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (25))){
var inst_33233 = (state_33309[(11)]);
var inst_33232 = (state_33309[(20)]);
var inst_33235 = (inst_33233 < inst_33232);
var inst_33236 = inst_33235;
var state_33309__$1 = state_33309;
if(cljs.core.truth_(inst_33236)){
var statearr_33433_35850 = state_33309__$1;
(statearr_33433_35850[(1)] = (27));

} else {
var statearr_33434_35851 = state_33309__$1;
(statearr_33434_35851[(1)] = (28));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (34))){
var state_33309__$1 = state_33309;
var statearr_33436_35852 = state_33309__$1;
(statearr_33436_35852[(2)] = null);

(statearr_33436_35852[(1)] = (35));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (17))){
var state_33309__$1 = state_33309;
var statearr_33439_35853 = state_33309__$1;
(statearr_33439_35853[(2)] = null);

(statearr_33439_35853[(1)] = (18));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (3))){
var inst_33299 = (state_33309[(2)]);
var state_33309__$1 = state_33309;
return cljs.core.async.impl.ioc_helpers.return_chan(state_33309__$1,inst_33299);
} else {
if((state_val_33310 === (12))){
var inst_33217 = (state_33309[(2)]);
var state_33309__$1 = state_33309;
var statearr_33466_35854 = state_33309__$1;
(statearr_33466_35854[(2)] = inst_33217);

(statearr_33466_35854[(1)] = (9));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (2))){
var state_33309__$1 = state_33309;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_33309__$1,(4),ch);
} else {
if((state_val_33310 === (23))){
var state_33309__$1 = state_33309;
var statearr_33468_35856 = state_33309__$1;
(statearr_33468_35856[(2)] = null);

(statearr_33468_35856[(1)] = (24));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (35))){
var inst_33283 = (state_33309[(2)]);
var state_33309__$1 = state_33309;
var statearr_33471_35860 = state_33309__$1;
(statearr_33471_35860[(2)] = inst_33283);

(statearr_33471_35860[(1)] = (29));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (19))){
var inst_33174 = (state_33309[(7)]);
var inst_33184 = cljs.core.chunk_first(inst_33174);
var inst_33185 = cljs.core.chunk_rest(inst_33174);
var inst_33186 = cljs.core.count(inst_33184);
var inst_33143 = inst_33185;
var inst_33144 = inst_33184;
var inst_33145 = inst_33186;
var inst_33146 = (0);
var state_33309__$1 = (function (){var statearr_33481 = state_33309;
(statearr_33481[(13)] = inst_33144);

(statearr_33481[(14)] = inst_33146);

(statearr_33481[(16)] = inst_33145);

(statearr_33481[(17)] = inst_33143);

return statearr_33481;
})();
var statearr_33482_35861 = state_33309__$1;
(statearr_33482_35861[(2)] = null);

(statearr_33482_35861[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (11))){
var inst_33174 = (state_33309[(7)]);
var inst_33143 = (state_33309[(17)]);
var inst_33174__$1 = cljs.core.seq(inst_33143);
var state_33309__$1 = (function (){var statearr_33484 = state_33309;
(statearr_33484[(7)] = inst_33174__$1);

return statearr_33484;
})();
if(inst_33174__$1){
var statearr_33487_35862 = state_33309__$1;
(statearr_33487_35862[(1)] = (16));

} else {
var statearr_33488_35863 = state_33309__$1;
(statearr_33488_35863[(1)] = (17));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (9))){
var inst_33219 = (state_33309[(2)]);
var state_33309__$1 = state_33309;
var statearr_33493_35864 = state_33309__$1;
(statearr_33493_35864[(2)] = inst_33219);

(statearr_33493_35864[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (5))){
var inst_33139 = cljs.core.deref(cs);
var inst_33142 = cljs.core.seq(inst_33139);
var inst_33143 = inst_33142;
var inst_33144 = null;
var inst_33145 = (0);
var inst_33146 = (0);
var state_33309__$1 = (function (){var statearr_33496 = state_33309;
(statearr_33496[(13)] = inst_33144);

(statearr_33496[(14)] = inst_33146);

(statearr_33496[(16)] = inst_33145);

(statearr_33496[(17)] = inst_33143);

return statearr_33496;
})();
var statearr_33501_35865 = state_33309__$1;
(statearr_33501_35865[(2)] = null);

(statearr_33501_35865[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (14))){
var state_33309__$1 = state_33309;
var statearr_33504_35866 = state_33309__$1;
(statearr_33504_35866[(2)] = null);

(statearr_33504_35866[(1)] = (15));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (45))){
var inst_33291 = (state_33309[(2)]);
var state_33309__$1 = state_33309;
var statearr_33508_35867 = state_33309__$1;
(statearr_33508_35867[(2)] = inst_33291);

(statearr_33508_35867[(1)] = (44));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (26))){
var inst_33222 = (state_33309[(27)]);
var inst_33287 = (state_33309[(2)]);
var inst_33288 = cljs.core.seq(inst_33222);
var state_33309__$1 = (function (){var statearr_33512 = state_33309;
(statearr_33512[(29)] = inst_33287);

return statearr_33512;
})();
if(inst_33288){
var statearr_33515_35868 = state_33309__$1;
(statearr_33515_35868[(1)] = (42));

} else {
var statearr_33517_35869 = state_33309__$1;
(statearr_33517_35869[(1)] = (43));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (16))){
var inst_33174 = (state_33309[(7)]);
var inst_33182 = cljs.core.chunked_seq_QMARK_(inst_33174);
var state_33309__$1 = state_33309;
if(inst_33182){
var statearr_33519_35870 = state_33309__$1;
(statearr_33519_35870[(1)] = (19));

} else {
var statearr_33520_35871 = state_33309__$1;
(statearr_33520_35871[(1)] = (20));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (38))){
var inst_33280 = (state_33309[(2)]);
var state_33309__$1 = state_33309;
var statearr_33521_35872 = state_33309__$1;
(statearr_33521_35872[(2)] = inst_33280);

(statearr_33521_35872[(1)] = (35));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (30))){
var state_33309__$1 = state_33309;
var statearr_33526_35873 = state_33309__$1;
(statearr_33526_35873[(2)] = null);

(statearr_33526_35873[(1)] = (32));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (10))){
var inst_33144 = (state_33309[(13)]);
var inst_33146 = (state_33309[(14)]);
var inst_33156 = cljs.core._nth(inst_33144,inst_33146);
var inst_33157 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_33156,(0),null);
var inst_33161 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_33156,(1),null);
var state_33309__$1 = (function (){var statearr_33529 = state_33309;
(statearr_33529[(24)] = inst_33157);

return statearr_33529;
})();
if(cljs.core.truth_(inst_33161)){
var statearr_33532_35874 = state_33309__$1;
(statearr_33532_35874[(1)] = (13));

} else {
var statearr_33534_35875 = state_33309__$1;
(statearr_33534_35875[(1)] = (14));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (18))){
var inst_33215 = (state_33309[(2)]);
var state_33309__$1 = state_33309;
var statearr_33539_35876 = state_33309__$1;
(statearr_33539_35876[(2)] = inst_33215);

(statearr_33539_35876[(1)] = (12));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (42))){
var state_33309__$1 = state_33309;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_33309__$1,(45),dchan);
} else {
if((state_val_33310 === (37))){
var inst_33266 = (state_33309[(22)]);
var inst_33251 = (state_33309[(23)]);
var inst_33119 = (state_33309[(9)]);
var inst_33266__$1 = cljs.core.first(inst_33251);
var inst_33267 = cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$3(inst_33266__$1,inst_33119,done);
var state_33309__$1 = (function (){var statearr_33549 = state_33309;
(statearr_33549[(22)] = inst_33266__$1);

return statearr_33549;
})();
if(cljs.core.truth_(inst_33267)){
var statearr_33551_35881 = state_33309__$1;
(statearr_33551_35881[(1)] = (39));

} else {
var statearr_33552_35882 = state_33309__$1;
(statearr_33552_35882[(1)] = (40));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33310 === (8))){
var inst_33146 = (state_33309[(14)]);
var inst_33145 = (state_33309[(16)]);
var inst_33148 = (inst_33146 < inst_33145);
var inst_33149 = inst_33148;
var state_33309__$1 = state_33309;
if(cljs.core.truth_(inst_33149)){
var statearr_33554_35884 = state_33309__$1;
(statearr_33554_35884[(1)] = (10));

} else {
var statearr_33555_35888 = state_33309__$1;
(statearr_33555_35888[(1)] = (11));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
}
}
}
}
}
});
return (function() {
var cljs$core$async$mult_$_state_machine__30402__auto__ = null;
var cljs$core$async$mult_$_state_machine__30402__auto____0 = (function (){
var statearr_33560 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_33560[(0)] = cljs$core$async$mult_$_state_machine__30402__auto__);

(statearr_33560[(1)] = (1));

return statearr_33560;
});
var cljs$core$async$mult_$_state_machine__30402__auto____1 = (function (state_33309){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_33309);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e33563){var ex__30405__auto__ = e33563;
var statearr_33564_35891 = state_33309;
(statearr_33564_35891[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_33309[(4)]))){
var statearr_33565_35892 = state_33309;
(statearr_33565_35892[(1)] = cljs.core.first((state_33309[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__35893 = state_33309;
state_33309 = G__35893;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$mult_$_state_machine__30402__auto__ = function(state_33309){
switch(arguments.length){
case 0:
return cljs$core$async$mult_$_state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$mult_$_state_machine__30402__auto____1.call(this,state_33309);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$mult_$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$mult_$_state_machine__30402__auto____0;
cljs$core$async$mult_$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$mult_$_state_machine__30402__auto____1;
return cljs$core$async$mult_$_state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_33571 = f__31515__auto__();
(statearr_33571[(6)] = c__31514__auto___35806);

return statearr_33571;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));


return m;
});
/**
 * Copies the mult source onto the supplied channel.
 * 
 *   By default the channel will be closed when the source closes,
 *   but can be determined by the close? parameter.
 */
cljs.core.async.tap = (function cljs$core$async$tap(var_args){
var G__33578 = arguments.length;
switch (G__33578) {
case 2:
return cljs.core.async.tap.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.tap.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.tap.cljs$core$IFn$_invoke$arity$2 = (function (mult,ch){
return cljs.core.async.tap.cljs$core$IFn$_invoke$arity$3(mult,ch,true);
}));

(cljs.core.async.tap.cljs$core$IFn$_invoke$arity$3 = (function (mult,ch,close_QMARK_){
cljs.core.async.tap_STAR_(mult,ch,close_QMARK_);

return ch;
}));

(cljs.core.async.tap.cljs$lang$maxFixedArity = 3);

/**
 * Disconnects a target channel from a mult
 */
cljs.core.async.untap = (function cljs$core$async$untap(mult,ch){
return cljs.core.async.untap_STAR_(mult,ch);
});
/**
 * Disconnects all target channels from a mult
 */
cljs.core.async.untap_all = (function cljs$core$async$untap_all(mult){
return cljs.core.async.untap_all_STAR_(mult);
});

/**
 * @interface
 */
cljs.core.async.Mix = function(){};

var cljs$core$async$Mix$admix_STAR_$dyn_35900 = (function (m,ch){
var x__5350__auto__ = (((m == null))?null:m);
var m__5351__auto__ = (cljs.core.async.admix_STAR_[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$2(m,ch) : m__5351__auto__.call(null, m,ch));
} else {
var m__5349__auto__ = (cljs.core.async.admix_STAR_["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$2(m,ch) : m__5349__auto__.call(null, m,ch));
} else {
throw cljs.core.missing_protocol("Mix.admix*",m);
}
}
});
cljs.core.async.admix_STAR_ = (function cljs$core$async$admix_STAR_(m,ch){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mix$admix_STAR_$arity$2 == null)))))){
return m.cljs$core$async$Mix$admix_STAR_$arity$2(m,ch);
} else {
return cljs$core$async$Mix$admix_STAR_$dyn_35900(m,ch);
}
});

var cljs$core$async$Mix$unmix_STAR_$dyn_35903 = (function (m,ch){
var x__5350__auto__ = (((m == null))?null:m);
var m__5351__auto__ = (cljs.core.async.unmix_STAR_[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$2(m,ch) : m__5351__auto__.call(null, m,ch));
} else {
var m__5349__auto__ = (cljs.core.async.unmix_STAR_["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$2(m,ch) : m__5349__auto__.call(null, m,ch));
} else {
throw cljs.core.missing_protocol("Mix.unmix*",m);
}
}
});
cljs.core.async.unmix_STAR_ = (function cljs$core$async$unmix_STAR_(m,ch){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mix$unmix_STAR_$arity$2 == null)))))){
return m.cljs$core$async$Mix$unmix_STAR_$arity$2(m,ch);
} else {
return cljs$core$async$Mix$unmix_STAR_$dyn_35903(m,ch);
}
});

var cljs$core$async$Mix$unmix_all_STAR_$dyn_35906 = (function (m){
var x__5350__auto__ = (((m == null))?null:m);
var m__5351__auto__ = (cljs.core.async.unmix_all_STAR_[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$1(m) : m__5351__auto__.call(null, m));
} else {
var m__5349__auto__ = (cljs.core.async.unmix_all_STAR_["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$1(m) : m__5349__auto__.call(null, m));
} else {
throw cljs.core.missing_protocol("Mix.unmix-all*",m);
}
}
});
cljs.core.async.unmix_all_STAR_ = (function cljs$core$async$unmix_all_STAR_(m){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mix$unmix_all_STAR_$arity$1 == null)))))){
return m.cljs$core$async$Mix$unmix_all_STAR_$arity$1(m);
} else {
return cljs$core$async$Mix$unmix_all_STAR_$dyn_35906(m);
}
});

var cljs$core$async$Mix$toggle_STAR_$dyn_35914 = (function (m,state_map){
var x__5350__auto__ = (((m == null))?null:m);
var m__5351__auto__ = (cljs.core.async.toggle_STAR_[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$2(m,state_map) : m__5351__auto__.call(null, m,state_map));
} else {
var m__5349__auto__ = (cljs.core.async.toggle_STAR_["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$2(m,state_map) : m__5349__auto__.call(null, m,state_map));
} else {
throw cljs.core.missing_protocol("Mix.toggle*",m);
}
}
});
cljs.core.async.toggle_STAR_ = (function cljs$core$async$toggle_STAR_(m,state_map){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mix$toggle_STAR_$arity$2 == null)))))){
return m.cljs$core$async$Mix$toggle_STAR_$arity$2(m,state_map);
} else {
return cljs$core$async$Mix$toggle_STAR_$dyn_35914(m,state_map);
}
});

var cljs$core$async$Mix$solo_mode_STAR_$dyn_35915 = (function (m,mode){
var x__5350__auto__ = (((m == null))?null:m);
var m__5351__auto__ = (cljs.core.async.solo_mode_STAR_[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$2(m,mode) : m__5351__auto__.call(null, m,mode));
} else {
var m__5349__auto__ = (cljs.core.async.solo_mode_STAR_["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$2(m,mode) : m__5349__auto__.call(null, m,mode));
} else {
throw cljs.core.missing_protocol("Mix.solo-mode*",m);
}
}
});
cljs.core.async.solo_mode_STAR_ = (function cljs$core$async$solo_mode_STAR_(m,mode){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mix$solo_mode_STAR_$arity$2 == null)))))){
return m.cljs$core$async$Mix$solo_mode_STAR_$arity$2(m,mode);
} else {
return cljs$core$async$Mix$solo_mode_STAR_$dyn_35915(m,mode);
}
});

cljs.core.async.ioc_alts_BANG_ = (function cljs$core$async$ioc_alts_BANG_(var_args){
var args__5732__auto__ = [];
var len__5726__auto___35916 = arguments.length;
var i__5727__auto___35917 = (0);
while(true){
if((i__5727__auto___35917 < len__5726__auto___35916)){
args__5732__auto__.push((arguments[i__5727__auto___35917]));

var G__35918 = (i__5727__auto___35917 + (1));
i__5727__auto___35917 = G__35918;
continue;
} else {
}
break;
}

var argseq__5733__auto__ = ((((3) < args__5732__auto__.length))?(new cljs.core.IndexedSeq(args__5732__auto__.slice((3)),(0),null)):null);
return cljs.core.async.ioc_alts_BANG_.cljs$core$IFn$_invoke$arity$variadic((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),argseq__5733__auto__);
});

(cljs.core.async.ioc_alts_BANG_.cljs$core$IFn$_invoke$arity$variadic = (function (state,cont_block,ports,p__33658){
var map__33659 = p__33658;
var map__33659__$1 = cljs.core.__destructure_map(map__33659);
var opts = map__33659__$1;
var statearr_33664_35921 = state;
(statearr_33664_35921[(1)] = cont_block);


var temp__5823__auto__ = cljs.core.async.do_alts((function (val){
var statearr_33668_35922 = state;
(statearr_33668_35922[(2)] = val);


return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state);
}),ports,opts);
if(cljs.core.truth_(temp__5823__auto__)){
var cb = temp__5823__auto__;
var statearr_33672_35923 = state;
(statearr_33672_35923[(2)] = cljs.core.deref(cb));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}));

(cljs.core.async.ioc_alts_BANG_.cljs$lang$maxFixedArity = (3));

/** @this {Function} */
(cljs.core.async.ioc_alts_BANG_.cljs$lang$applyTo = (function (seq33644){
var G__33645 = cljs.core.first(seq33644);
var seq33644__$1 = cljs.core.next(seq33644);
var G__33646 = cljs.core.first(seq33644__$1);
var seq33644__$2 = cljs.core.next(seq33644__$1);
var G__33647 = cljs.core.first(seq33644__$2);
var seq33644__$3 = cljs.core.next(seq33644__$2);
var self__5711__auto__ = this;
return self__5711__auto__.cljs$core$IFn$_invoke$arity$variadic(G__33645,G__33646,G__33647,seq33644__$3);
}));


/**
* @constructor
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.async.Mix}
 * @implements {cljs.core.async.Mux}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async33681 = (function (change,solo_mode,pick,cs,calc_state,out,changed,solo_modes,attrs,meta33682){
this.change = change;
this.solo_mode = solo_mode;
this.pick = pick;
this.cs = cs;
this.calc_state = calc_state;
this.out = out;
this.changed = changed;
this.solo_modes = solo_modes;
this.attrs = attrs;
this.meta33682 = meta33682;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async33681.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_33683,meta33682__$1){
var self__ = this;
var _33683__$1 = this;
return (new cljs.core.async.t_cljs$core$async33681(self__.change,self__.solo_mode,self__.pick,self__.cs,self__.calc_state,self__.out,self__.changed,self__.solo_modes,self__.attrs,meta33682__$1));
}));

(cljs.core.async.t_cljs$core$async33681.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_33683){
var self__ = this;
var _33683__$1 = this;
return self__.meta33682;
}));

(cljs.core.async.t_cljs$core$async33681.prototype.cljs$core$async$Mux$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async33681.prototype.cljs$core$async$Mux$muxch_STAR_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return self__.out;
}));

(cljs.core.async.t_cljs$core$async33681.prototype.cljs$core$async$Mix$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async33681.prototype.cljs$core$async$Mix$admix_STAR_$arity$2 = (function (_,ch){
var self__ = this;
var ___$1 = this;
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(self__.cs,cljs.core.assoc,ch,cljs.core.PersistentArrayMap.EMPTY);

return (self__.changed.cljs$core$IFn$_invoke$arity$0 ? self__.changed.cljs$core$IFn$_invoke$arity$0() : self__.changed.call(null, ));
}));

(cljs.core.async.t_cljs$core$async33681.prototype.cljs$core$async$Mix$unmix_STAR_$arity$2 = (function (_,ch){
var self__ = this;
var ___$1 = this;
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(self__.cs,cljs.core.dissoc,ch);

return (self__.changed.cljs$core$IFn$_invoke$arity$0 ? self__.changed.cljs$core$IFn$_invoke$arity$0() : self__.changed.call(null, ));
}));

(cljs.core.async.t_cljs$core$async33681.prototype.cljs$core$async$Mix$unmix_all_STAR_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
cljs.core.reset_BANG_(self__.cs,cljs.core.PersistentArrayMap.EMPTY);

return (self__.changed.cljs$core$IFn$_invoke$arity$0 ? self__.changed.cljs$core$IFn$_invoke$arity$0() : self__.changed.call(null, ));
}));

(cljs.core.async.t_cljs$core$async33681.prototype.cljs$core$async$Mix$toggle_STAR_$arity$2 = (function (_,state_map){
var self__ = this;
var ___$1 = this;
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(self__.cs,cljs.core.partial.cljs$core$IFn$_invoke$arity$2(cljs.core.merge_with,cljs.core.merge),state_map);

return (self__.changed.cljs$core$IFn$_invoke$arity$0 ? self__.changed.cljs$core$IFn$_invoke$arity$0() : self__.changed.call(null, ));
}));

(cljs.core.async.t_cljs$core$async33681.prototype.cljs$core$async$Mix$solo_mode_STAR_$arity$2 = (function (_,mode){
var self__ = this;
var ___$1 = this;
if(cljs.core.truth_((self__.solo_modes.cljs$core$IFn$_invoke$arity$1 ? self__.solo_modes.cljs$core$IFn$_invoke$arity$1(mode) : self__.solo_modes.call(null, mode)))){
} else {
throw (new Error(["Assert failed: ",["mode must be one of: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(self__.solo_modes)].join(''),"\n","(solo-modes mode)"].join('')));
}

cljs.core.reset_BANG_(self__.solo_mode,mode);

return (self__.changed.cljs$core$IFn$_invoke$arity$0 ? self__.changed.cljs$core$IFn$_invoke$arity$0() : self__.changed.call(null, ));
}));

(cljs.core.async.t_cljs$core$async33681.getBasis = (function (){
return new cljs.core.PersistentVector(null, 10, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"change","change",477485025,null),new cljs.core.Symbol(null,"solo-mode","solo-mode",2031788074,null),new cljs.core.Symbol(null,"pick","pick",1300068175,null),new cljs.core.Symbol(null,"cs","cs",-117024463,null),new cljs.core.Symbol(null,"calc-state","calc-state",-349968968,null),new cljs.core.Symbol(null,"out","out",729986010,null),new cljs.core.Symbol(null,"changed","changed",-2083710852,null),new cljs.core.Symbol(null,"solo-modes","solo-modes",882180540,null),new cljs.core.Symbol(null,"attrs","attrs",-450137186,null),new cljs.core.Symbol(null,"meta33682","meta33682",-499138777,null)], null);
}));

(cljs.core.async.t_cljs$core$async33681.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async33681.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async33681");

(cljs.core.async.t_cljs$core$async33681.cljs$lang$ctorPrWriter = (function (this__5287__auto__,writer__5288__auto__,opt__5289__auto__){
return cljs.core._write(writer__5288__auto__,"cljs.core.async/t_cljs$core$async33681");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async33681.
 */
cljs.core.async.__GT_t_cljs$core$async33681 = (function cljs$core$async$__GT_t_cljs$core$async33681(change,solo_mode,pick,cs,calc_state,out,changed,solo_modes,attrs,meta33682){
return (new cljs.core.async.t_cljs$core$async33681(change,solo_mode,pick,cs,calc_state,out,changed,solo_modes,attrs,meta33682));
});


/**
 * Creates and returns a mix of one or more input channels which will
 *   be put on the supplied out channel. Input sources can be added to
 *   the mix with 'admix', and removed with 'unmix'. A mix supports
 *   soloing, muting and pausing multiple inputs atomically using
 *   'toggle', and can solo using either muting or pausing as determined
 *   by 'solo-mode'.
 * 
 *   Each channel can have zero or more boolean modes set via 'toggle':
 * 
 *   :solo - when true, only this (ond other soloed) channel(s) will appear
 *        in the mix output channel. :mute and :pause states of soloed
 *        channels are ignored. If solo-mode is :mute, non-soloed
 *        channels are muted, if :pause, non-soloed channels are
 *        paused.
 * 
 *   :mute - muted channels will have their contents consumed but not included in the mix
 *   :pause - paused channels will not have their contents consumed (and thus also not included in the mix)
 */
cljs.core.async.mix = (function cljs$core$async$mix(out){
var cs = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentArrayMap.EMPTY);
var solo_modes = new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"pause","pause",-2095325672),null,new cljs.core.Keyword(null,"mute","mute",1151223646),null], null), null);
var attrs = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(solo_modes,new cljs.core.Keyword(null,"solo","solo",-316350075));
var solo_mode = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"mute","mute",1151223646));
var change = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(cljs.core.async.sliding_buffer((1)));
var changed = (function (){
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2(change,true);
});
var pick = (function (attr,chs){
return cljs.core.reduce_kv((function (ret,c,v){
if(cljs.core.truth_((attr.cljs$core$IFn$_invoke$arity$1 ? attr.cljs$core$IFn$_invoke$arity$1(v) : attr.call(null, v)))){
return cljs.core.conj.cljs$core$IFn$_invoke$arity$2(ret,c);
} else {
return ret;
}
}),cljs.core.PersistentHashSet.EMPTY,chs);
});
var calc_state = (function (){
var chs = cljs.core.deref(cs);
var mode = cljs.core.deref(solo_mode);
var solos = pick(new cljs.core.Keyword(null,"solo","solo",-316350075),chs);
var pauses = pick(new cljs.core.Keyword(null,"pause","pause",-2095325672),chs);
return new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"solos","solos",1441458643),solos,new cljs.core.Keyword(null,"mutes","mutes",1068806309),pick(new cljs.core.Keyword(null,"mute","mute",1151223646),chs),new cljs.core.Keyword(null,"reads","reads",-1215067361),cljs.core.conj.cljs$core$IFn$_invoke$arity$2(((((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(mode,new cljs.core.Keyword(null,"pause","pause",-2095325672))) && ((!(cljs.core.empty_QMARK_(solos))))))?cljs.core.vec(solos):cljs.core.vec(cljs.core.remove.cljs$core$IFn$_invoke$arity$2(pauses,cljs.core.keys(chs)))),change)], null);
});
var m = (new cljs.core.async.t_cljs$core$async33681(change,solo_mode,pick,cs,calc_state,out,changed,solo_modes,attrs,cljs.core.PersistentArrayMap.EMPTY));
var c__31514__auto___35954 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_33793){
var state_val_33794 = (state_33793[(1)]);
if((state_val_33794 === (7))){
var inst_33740 = (state_33793[(2)]);
var state_33793__$1 = state_33793;
if(cljs.core.truth_(inst_33740)){
var statearr_33796_35957 = state_33793__$1;
(statearr_33796_35957[(1)] = (8));

} else {
var statearr_33797_35958 = state_33793__$1;
(statearr_33797_35958[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (20))){
var inst_33732 = (state_33793[(7)]);
var state_33793__$1 = state_33793;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_33793__$1,(23),out,inst_33732);
} else {
if((state_val_33794 === (1))){
var inst_33711 = calc_state();
var inst_33715 = cljs.core.__destructure_map(inst_33711);
var inst_33716 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_33715,new cljs.core.Keyword(null,"solos","solos",1441458643));
var inst_33717 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_33715,new cljs.core.Keyword(null,"mutes","mutes",1068806309));
var inst_33718 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_33715,new cljs.core.Keyword(null,"reads","reads",-1215067361));
var inst_33719 = inst_33711;
var state_33793__$1 = (function (){var statearr_33799 = state_33793;
(statearr_33799[(8)] = inst_33719);

(statearr_33799[(9)] = inst_33716);

(statearr_33799[(10)] = inst_33717);

(statearr_33799[(11)] = inst_33718);

return statearr_33799;
})();
var statearr_33800_35971 = state_33793__$1;
(statearr_33800_35971[(2)] = null);

(statearr_33800_35971[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (24))){
var inst_33723 = (state_33793[(12)]);
var inst_33719 = inst_33723;
var state_33793__$1 = (function (){var statearr_33801 = state_33793;
(statearr_33801[(8)] = inst_33719);

return statearr_33801;
})();
var statearr_33803_35972 = state_33793__$1;
(statearr_33803_35972[(2)] = null);

(statearr_33803_35972[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (4))){
var inst_33732 = (state_33793[(7)]);
var inst_33735 = (state_33793[(13)]);
var inst_33731 = (state_33793[(2)]);
var inst_33732__$1 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_33731,(0),null);
var inst_33734 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_33731,(1),null);
var inst_33735__$1 = (inst_33732__$1 == null);
var state_33793__$1 = (function (){var statearr_33810 = state_33793;
(statearr_33810[(14)] = inst_33734);

(statearr_33810[(7)] = inst_33732__$1);

(statearr_33810[(13)] = inst_33735__$1);

return statearr_33810;
})();
if(cljs.core.truth_(inst_33735__$1)){
var statearr_33812_35976 = state_33793__$1;
(statearr_33812_35976[(1)] = (5));

} else {
var statearr_33813_35977 = state_33793__$1;
(statearr_33813_35977[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (15))){
var inst_33760 = (state_33793[(15)]);
var inst_33724 = (state_33793[(16)]);
var inst_33760__$1 = cljs.core.empty_QMARK_(inst_33724);
var state_33793__$1 = (function (){var statearr_33815 = state_33793;
(statearr_33815[(15)] = inst_33760__$1);

return statearr_33815;
})();
if(inst_33760__$1){
var statearr_33817_35979 = state_33793__$1;
(statearr_33817_35979[(1)] = (17));

} else {
var statearr_33818_35980 = state_33793__$1;
(statearr_33818_35980[(1)] = (18));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (21))){
var inst_33723 = (state_33793[(12)]);
var inst_33719 = inst_33723;
var state_33793__$1 = (function (){var statearr_33819 = state_33793;
(statearr_33819[(8)] = inst_33719);

return statearr_33819;
})();
var statearr_33820_35981 = state_33793__$1;
(statearr_33820_35981[(2)] = null);

(statearr_33820_35981[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (13))){
var inst_33752 = (state_33793[(2)]);
var inst_33753 = calc_state();
var inst_33719 = inst_33753;
var state_33793__$1 = (function (){var statearr_33821 = state_33793;
(statearr_33821[(8)] = inst_33719);

(statearr_33821[(17)] = inst_33752);

return statearr_33821;
})();
var statearr_33822_35982 = state_33793__$1;
(statearr_33822_35982[(2)] = null);

(statearr_33822_35982[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (22))){
var inst_33782 = (state_33793[(2)]);
var state_33793__$1 = state_33793;
var statearr_33824_35983 = state_33793__$1;
(statearr_33824_35983[(2)] = inst_33782);

(statearr_33824_35983[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (6))){
var inst_33734 = (state_33793[(14)]);
var inst_33738 = cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(inst_33734,change);
var state_33793__$1 = state_33793;
var statearr_33825_35984 = state_33793__$1;
(statearr_33825_35984[(2)] = inst_33738);

(statearr_33825_35984[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (25))){
var state_33793__$1 = state_33793;
var statearr_33827_35985 = state_33793__$1;
(statearr_33827_35985[(2)] = null);

(statearr_33827_35985[(1)] = (26));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (17))){
var inst_33725 = (state_33793[(18)]);
var inst_33734 = (state_33793[(14)]);
var inst_33762 = (inst_33725.cljs$core$IFn$_invoke$arity$1 ? inst_33725.cljs$core$IFn$_invoke$arity$1(inst_33734) : inst_33725.call(null, inst_33734));
var inst_33763 = cljs.core.not(inst_33762);
var state_33793__$1 = state_33793;
var statearr_33828_35986 = state_33793__$1;
(statearr_33828_35986[(2)] = inst_33763);

(statearr_33828_35986[(1)] = (19));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (3))){
var inst_33786 = (state_33793[(2)]);
var state_33793__$1 = state_33793;
return cljs.core.async.impl.ioc_helpers.return_chan(state_33793__$1,inst_33786);
} else {
if((state_val_33794 === (12))){
var state_33793__$1 = state_33793;
var statearr_33832_35987 = state_33793__$1;
(statearr_33832_35987[(2)] = null);

(statearr_33832_35987[(1)] = (13));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (2))){
var inst_33719 = (state_33793[(8)]);
var inst_33723 = (state_33793[(12)]);
var inst_33723__$1 = cljs.core.__destructure_map(inst_33719);
var inst_33724 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_33723__$1,new cljs.core.Keyword(null,"solos","solos",1441458643));
var inst_33725 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_33723__$1,new cljs.core.Keyword(null,"mutes","mutes",1068806309));
var inst_33726 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_33723__$1,new cljs.core.Keyword(null,"reads","reads",-1215067361));
var state_33793__$1 = (function (){var statearr_33833 = state_33793;
(statearr_33833[(12)] = inst_33723__$1);

(statearr_33833[(18)] = inst_33725);

(statearr_33833[(16)] = inst_33724);

return statearr_33833;
})();
return cljs.core.async.ioc_alts_BANG_(state_33793__$1,(4),inst_33726);
} else {
if((state_val_33794 === (23))){
var inst_33772 = (state_33793[(2)]);
var state_33793__$1 = state_33793;
if(cljs.core.truth_(inst_33772)){
var statearr_33834_35992 = state_33793__$1;
(statearr_33834_35992[(1)] = (24));

} else {
var statearr_33835_35993 = state_33793__$1;
(statearr_33835_35993[(1)] = (25));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (19))){
var inst_33767 = (state_33793[(2)]);
var state_33793__$1 = state_33793;
var statearr_33836_35994 = state_33793__$1;
(statearr_33836_35994[(2)] = inst_33767);

(statearr_33836_35994[(1)] = (16));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (11))){
var inst_33734 = (state_33793[(14)]);
var inst_33749 = cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(cs,cljs.core.dissoc,inst_33734);
var state_33793__$1 = state_33793;
var statearr_33837_35995 = state_33793__$1;
(statearr_33837_35995[(2)] = inst_33749);

(statearr_33837_35995[(1)] = (13));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (9))){
var inst_33734 = (state_33793[(14)]);
var inst_33757 = (state_33793[(19)]);
var inst_33724 = (state_33793[(16)]);
var inst_33757__$1 = (inst_33724.cljs$core$IFn$_invoke$arity$1 ? inst_33724.cljs$core$IFn$_invoke$arity$1(inst_33734) : inst_33724.call(null, inst_33734));
var state_33793__$1 = (function (){var statearr_33839 = state_33793;
(statearr_33839[(19)] = inst_33757__$1);

return statearr_33839;
})();
if(cljs.core.truth_(inst_33757__$1)){
var statearr_33843_35996 = state_33793__$1;
(statearr_33843_35996[(1)] = (14));

} else {
var statearr_33844_35997 = state_33793__$1;
(statearr_33844_35997[(1)] = (15));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (5))){
var inst_33735 = (state_33793[(13)]);
var state_33793__$1 = state_33793;
var statearr_33845_35998 = state_33793__$1;
(statearr_33845_35998[(2)] = inst_33735);

(statearr_33845_35998[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (14))){
var inst_33757 = (state_33793[(19)]);
var state_33793__$1 = state_33793;
var statearr_33846_35999 = state_33793__$1;
(statearr_33846_35999[(2)] = inst_33757);

(statearr_33846_35999[(1)] = (16));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (26))){
var inst_33778 = (state_33793[(2)]);
var state_33793__$1 = state_33793;
var statearr_33847_36000 = state_33793__$1;
(statearr_33847_36000[(2)] = inst_33778);

(statearr_33847_36000[(1)] = (22));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (16))){
var inst_33769 = (state_33793[(2)]);
var state_33793__$1 = state_33793;
if(cljs.core.truth_(inst_33769)){
var statearr_33848_36001 = state_33793__$1;
(statearr_33848_36001[(1)] = (20));

} else {
var statearr_33849_36002 = state_33793__$1;
(statearr_33849_36002[(1)] = (21));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (10))){
var inst_33784 = (state_33793[(2)]);
var state_33793__$1 = state_33793;
var statearr_33850_36003 = state_33793__$1;
(statearr_33850_36003[(2)] = inst_33784);

(statearr_33850_36003[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (18))){
var inst_33760 = (state_33793[(15)]);
var state_33793__$1 = state_33793;
var statearr_33851_36004 = state_33793__$1;
(statearr_33851_36004[(2)] = inst_33760);

(statearr_33851_36004[(1)] = (19));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_33794 === (8))){
var inst_33732 = (state_33793[(7)]);
var inst_33743 = (inst_33732 == null);
var state_33793__$1 = state_33793;
if(cljs.core.truth_(inst_33743)){
var statearr_33852_36005 = state_33793__$1;
(statearr_33852_36005[(1)] = (11));

} else {
var statearr_33853_36006 = state_33793__$1;
(statearr_33853_36006[(1)] = (12));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$mix_$_state_machine__30402__auto__ = null;
var cljs$core$async$mix_$_state_machine__30402__auto____0 = (function (){
var statearr_33855 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_33855[(0)] = cljs$core$async$mix_$_state_machine__30402__auto__);

(statearr_33855[(1)] = (1));

return statearr_33855;
});
var cljs$core$async$mix_$_state_machine__30402__auto____1 = (function (state_33793){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_33793);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e33857){var ex__30405__auto__ = e33857;
var statearr_33858_36008 = state_33793;
(statearr_33858_36008[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_33793[(4)]))){
var statearr_33859_36009 = state_33793;
(statearr_33859_36009[(1)] = cljs.core.first((state_33793[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__36010 = state_33793;
state_33793 = G__36010;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$mix_$_state_machine__30402__auto__ = function(state_33793){
switch(arguments.length){
case 0:
return cljs$core$async$mix_$_state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$mix_$_state_machine__30402__auto____1.call(this,state_33793);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$mix_$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$mix_$_state_machine__30402__auto____0;
cljs$core$async$mix_$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$mix_$_state_machine__30402__auto____1;
return cljs$core$async$mix_$_state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_33861 = f__31515__auto__();
(statearr_33861[(6)] = c__31514__auto___35954);

return statearr_33861;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));


return m;
});
/**
 * Adds ch as an input to the mix
 */
cljs.core.async.admix = (function cljs$core$async$admix(mix,ch){
return cljs.core.async.admix_STAR_(mix,ch);
});
/**
 * Removes ch as an input to the mix
 */
cljs.core.async.unmix = (function cljs$core$async$unmix(mix,ch){
return cljs.core.async.unmix_STAR_(mix,ch);
});
/**
 * removes all inputs from the mix
 */
cljs.core.async.unmix_all = (function cljs$core$async$unmix_all(mix){
return cljs.core.async.unmix_all_STAR_(mix);
});
/**
 * Atomically sets the state(s) of one or more channels in a mix. The
 *   state map is a map of channels -> channel-state-map. A
 *   channel-state-map is a map of attrs -> boolean, where attr is one or
 *   more of :mute, :pause or :solo. Any states supplied are merged with
 *   the current state.
 * 
 *   Note that channels can be added to a mix via toggle, which can be
 *   used to add channels in a particular (e.g. paused) state.
 */
cljs.core.async.toggle = (function cljs$core$async$toggle(mix,state_map){
return cljs.core.async.toggle_STAR_(mix,state_map);
});
/**
 * Sets the solo mode of the mix. mode must be one of :mute or :pause
 */
cljs.core.async.solo_mode = (function cljs$core$async$solo_mode(mix,mode){
return cljs.core.async.solo_mode_STAR_(mix,mode);
});

/**
 * @interface
 */
cljs.core.async.Pub = function(){};

var cljs$core$async$Pub$sub_STAR_$dyn_36018 = (function (p,v,ch,close_QMARK_){
var x__5350__auto__ = (((p == null))?null:p);
var m__5351__auto__ = (cljs.core.async.sub_STAR_[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$4 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$4(p,v,ch,close_QMARK_) : m__5351__auto__.call(null, p,v,ch,close_QMARK_));
} else {
var m__5349__auto__ = (cljs.core.async.sub_STAR_["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$4 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$4(p,v,ch,close_QMARK_) : m__5349__auto__.call(null, p,v,ch,close_QMARK_));
} else {
throw cljs.core.missing_protocol("Pub.sub*",p);
}
}
});
cljs.core.async.sub_STAR_ = (function cljs$core$async$sub_STAR_(p,v,ch,close_QMARK_){
if((((!((p == null)))) && ((!((p.cljs$core$async$Pub$sub_STAR_$arity$4 == null)))))){
return p.cljs$core$async$Pub$sub_STAR_$arity$4(p,v,ch,close_QMARK_);
} else {
return cljs$core$async$Pub$sub_STAR_$dyn_36018(p,v,ch,close_QMARK_);
}
});

var cljs$core$async$Pub$unsub_STAR_$dyn_36025 = (function (p,v,ch){
var x__5350__auto__ = (((p == null))?null:p);
var m__5351__auto__ = (cljs.core.async.unsub_STAR_[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$3 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$3(p,v,ch) : m__5351__auto__.call(null, p,v,ch));
} else {
var m__5349__auto__ = (cljs.core.async.unsub_STAR_["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$3 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$3(p,v,ch) : m__5349__auto__.call(null, p,v,ch));
} else {
throw cljs.core.missing_protocol("Pub.unsub*",p);
}
}
});
cljs.core.async.unsub_STAR_ = (function cljs$core$async$unsub_STAR_(p,v,ch){
if((((!((p == null)))) && ((!((p.cljs$core$async$Pub$unsub_STAR_$arity$3 == null)))))){
return p.cljs$core$async$Pub$unsub_STAR_$arity$3(p,v,ch);
} else {
return cljs$core$async$Pub$unsub_STAR_$dyn_36025(p,v,ch);
}
});

var cljs$core$async$Pub$unsub_all_STAR_$dyn_36031 = (function() {
var G__36032 = null;
var G__36032__1 = (function (p){
var x__5350__auto__ = (((p == null))?null:p);
var m__5351__auto__ = (cljs.core.async.unsub_all_STAR_[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$1(p) : m__5351__auto__.call(null, p));
} else {
var m__5349__auto__ = (cljs.core.async.unsub_all_STAR_["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$1(p) : m__5349__auto__.call(null, p));
} else {
throw cljs.core.missing_protocol("Pub.unsub-all*",p);
}
}
});
var G__36032__2 = (function (p,v){
var x__5350__auto__ = (((p == null))?null:p);
var m__5351__auto__ = (cljs.core.async.unsub_all_STAR_[goog.typeOf(x__5350__auto__)]);
if((!((m__5351__auto__ == null)))){
return (m__5351__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5351__auto__.cljs$core$IFn$_invoke$arity$2(p,v) : m__5351__auto__.call(null, p,v));
} else {
var m__5349__auto__ = (cljs.core.async.unsub_all_STAR_["_"]);
if((!((m__5349__auto__ == null)))){
return (m__5349__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5349__auto__.cljs$core$IFn$_invoke$arity$2(p,v) : m__5349__auto__.call(null, p,v));
} else {
throw cljs.core.missing_protocol("Pub.unsub-all*",p);
}
}
});
G__36032 = function(p,v){
switch(arguments.length){
case 1:
return G__36032__1.call(this,p);
case 2:
return G__36032__2.call(this,p,v);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
G__36032.cljs$core$IFn$_invoke$arity$1 = G__36032__1;
G__36032.cljs$core$IFn$_invoke$arity$2 = G__36032__2;
return G__36032;
})()
;
cljs.core.async.unsub_all_STAR_ = (function cljs$core$async$unsub_all_STAR_(var_args){
var G__33893 = arguments.length;
switch (G__33893) {
case 1:
return cljs.core.async.unsub_all_STAR_.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.unsub_all_STAR_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.unsub_all_STAR_.cljs$core$IFn$_invoke$arity$1 = (function (p){
if((((!((p == null)))) && ((!((p.cljs$core$async$Pub$unsub_all_STAR_$arity$1 == null)))))){
return p.cljs$core$async$Pub$unsub_all_STAR_$arity$1(p);
} else {
return cljs$core$async$Pub$unsub_all_STAR_$dyn_36031(p);
}
}));

(cljs.core.async.unsub_all_STAR_.cljs$core$IFn$_invoke$arity$2 = (function (p,v){
if((((!((p == null)))) && ((!((p.cljs$core$async$Pub$unsub_all_STAR_$arity$2 == null)))))){
return p.cljs$core$async$Pub$unsub_all_STAR_$arity$2(p,v);
} else {
return cljs$core$async$Pub$unsub_all_STAR_$dyn_36031(p,v);
}
}));

(cljs.core.async.unsub_all_STAR_.cljs$lang$maxFixedArity = 2);



/**
* @constructor
 * @implements {cljs.core.async.Pub}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.async.Mux}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async33911 = (function (ch,topic_fn,buf_fn,mults,ensure_mult,meta33912){
this.ch = ch;
this.topic_fn = topic_fn;
this.buf_fn = buf_fn;
this.mults = mults;
this.ensure_mult = ensure_mult;
this.meta33912 = meta33912;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async33911.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_33913,meta33912__$1){
var self__ = this;
var _33913__$1 = this;
return (new cljs.core.async.t_cljs$core$async33911(self__.ch,self__.topic_fn,self__.buf_fn,self__.mults,self__.ensure_mult,meta33912__$1));
}));

(cljs.core.async.t_cljs$core$async33911.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_33913){
var self__ = this;
var _33913__$1 = this;
return self__.meta33912;
}));

(cljs.core.async.t_cljs$core$async33911.prototype.cljs$core$async$Mux$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async33911.prototype.cljs$core$async$Mux$muxch_STAR_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return self__.ch;
}));

(cljs.core.async.t_cljs$core$async33911.prototype.cljs$core$async$Pub$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async33911.prototype.cljs$core$async$Pub$sub_STAR_$arity$4 = (function (p,topic,ch__$1,close_QMARK_){
var self__ = this;
var p__$1 = this;
var m = (self__.ensure_mult.cljs$core$IFn$_invoke$arity$1 ? self__.ensure_mult.cljs$core$IFn$_invoke$arity$1(topic) : self__.ensure_mult.call(null, topic));
return cljs.core.async.tap.cljs$core$IFn$_invoke$arity$3(m,ch__$1,close_QMARK_);
}));

(cljs.core.async.t_cljs$core$async33911.prototype.cljs$core$async$Pub$unsub_STAR_$arity$3 = (function (p,topic,ch__$1){
var self__ = this;
var p__$1 = this;
var temp__5823__auto__ = cljs.core.get.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(self__.mults),topic);
if(cljs.core.truth_(temp__5823__auto__)){
var m = temp__5823__auto__;
return cljs.core.async.untap(m,ch__$1);
} else {
return null;
}
}));

(cljs.core.async.t_cljs$core$async33911.prototype.cljs$core$async$Pub$unsub_all_STAR_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.reset_BANG_(self__.mults,cljs.core.PersistentArrayMap.EMPTY);
}));

(cljs.core.async.t_cljs$core$async33911.prototype.cljs$core$async$Pub$unsub_all_STAR_$arity$2 = (function (_,topic){
var self__ = this;
var ___$1 = this;
return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(self__.mults,cljs.core.dissoc,topic);
}));

(cljs.core.async.t_cljs$core$async33911.getBasis = (function (){
return new cljs.core.PersistentVector(null, 6, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"ch","ch",1085813622,null),new cljs.core.Symbol(null,"topic-fn","topic-fn",-862449736,null),new cljs.core.Symbol(null,"buf-fn","buf-fn",-1200281591,null),new cljs.core.Symbol(null,"mults","mults",-461114485,null),new cljs.core.Symbol(null,"ensure-mult","ensure-mult",1796584816,null),new cljs.core.Symbol(null,"meta33912","meta33912",780797330,null)], null);
}));

(cljs.core.async.t_cljs$core$async33911.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async33911.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async33911");

(cljs.core.async.t_cljs$core$async33911.cljs$lang$ctorPrWriter = (function (this__5287__auto__,writer__5288__auto__,opt__5289__auto__){
return cljs.core._write(writer__5288__auto__,"cljs.core.async/t_cljs$core$async33911");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async33911.
 */
cljs.core.async.__GT_t_cljs$core$async33911 = (function cljs$core$async$__GT_t_cljs$core$async33911(ch,topic_fn,buf_fn,mults,ensure_mult,meta33912){
return (new cljs.core.async.t_cljs$core$async33911(ch,topic_fn,buf_fn,mults,ensure_mult,meta33912));
});


/**
 * Creates and returns a pub(lication) of the supplied channel,
 *   partitioned into topics by the topic-fn. topic-fn will be applied to
 *   each value on the channel and the result will determine the 'topic'
 *   on which that value will be put. Channels can be subscribed to
 *   receive copies of topics using 'sub', and unsubscribed using
 *   'unsub'. Each topic will be handled by an internal mult on a
 *   dedicated channel. By default these internal channels are
 *   unbuffered, but a buf-fn can be supplied which, given a topic,
 *   creates a buffer with desired properties.
 * 
 *   Each item is distributed to all subs in parallel and synchronously,
 *   i.e. each sub must accept before the next item is distributed. Use
 *   buffering/windowing to prevent slow subs from holding up the pub.
 * 
 *   Items received when there are no matching subs get dropped.
 * 
 *   Note that if buf-fns are used then each topic is handled
 *   asynchronously, i.e. if a channel is subscribed to more than one
 *   topic it should not expect them to be interleaved identically with
 *   the source.
 */
cljs.core.async.pub = (function cljs$core$async$pub(var_args){
var G__33903 = arguments.length;
switch (G__33903) {
case 2:
return cljs.core.async.pub.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.pub.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.pub.cljs$core$IFn$_invoke$arity$2 = (function (ch,topic_fn){
return cljs.core.async.pub.cljs$core$IFn$_invoke$arity$3(ch,topic_fn,cljs.core.constantly(null));
}));

(cljs.core.async.pub.cljs$core$IFn$_invoke$arity$3 = (function (ch,topic_fn,buf_fn){
var mults = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentArrayMap.EMPTY);
var ensure_mult = (function (topic){
var or__5002__auto__ = cljs.core.get.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(mults),topic);
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return cljs.core.get.cljs$core$IFn$_invoke$arity$2(cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$2(mults,(function (p1__33897_SHARP_){
if(cljs.core.truth_((p1__33897_SHARP_.cljs$core$IFn$_invoke$arity$1 ? p1__33897_SHARP_.cljs$core$IFn$_invoke$arity$1(topic) : p1__33897_SHARP_.call(null, topic)))){
return p1__33897_SHARP_;
} else {
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(p1__33897_SHARP_,topic,cljs.core.async.mult(cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((buf_fn.cljs$core$IFn$_invoke$arity$1 ? buf_fn.cljs$core$IFn$_invoke$arity$1(topic) : buf_fn.call(null, topic)))));
}
})),topic);
}
});
var p = (new cljs.core.async.t_cljs$core$async33911(ch,topic_fn,buf_fn,mults,ensure_mult,cljs.core.PersistentArrayMap.EMPTY));
var c__31514__auto___36068 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_34005){
var state_val_34006 = (state_34005[(1)]);
if((state_val_34006 === (7))){
var inst_33997 = (state_34005[(2)]);
var state_34005__$1 = state_34005;
var statearr_34018_36069 = state_34005__$1;
(statearr_34018_36069[(2)] = inst_33997);

(statearr_34018_36069[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (20))){
var state_34005__$1 = state_34005;
var statearr_34019_36070 = state_34005__$1;
(statearr_34019_36070[(2)] = null);

(statearr_34019_36070[(1)] = (21));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (1))){
var state_34005__$1 = state_34005;
var statearr_34022_36071 = state_34005__$1;
(statearr_34022_36071[(2)] = null);

(statearr_34022_36071[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (24))){
var inst_33980 = (state_34005[(7)]);
var inst_33989 = cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(mults,cljs.core.dissoc,inst_33980);
var state_34005__$1 = state_34005;
var statearr_34023_36074 = state_34005__$1;
(statearr_34023_36074[(2)] = inst_33989);

(statearr_34023_36074[(1)] = (25));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (4))){
var inst_33930 = (state_34005[(8)]);
var inst_33930__$1 = (state_34005[(2)]);
var inst_33931 = (inst_33930__$1 == null);
var state_34005__$1 = (function (){var statearr_34026 = state_34005;
(statearr_34026[(8)] = inst_33930__$1);

return statearr_34026;
})();
if(cljs.core.truth_(inst_33931)){
var statearr_34027_36075 = state_34005__$1;
(statearr_34027_36075[(1)] = (5));

} else {
var statearr_34028_36076 = state_34005__$1;
(statearr_34028_36076[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (15))){
var inst_33974 = (state_34005[(2)]);
var state_34005__$1 = state_34005;
var statearr_34029_36077 = state_34005__$1;
(statearr_34029_36077[(2)] = inst_33974);

(statearr_34029_36077[(1)] = (12));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (21))){
var inst_33994 = (state_34005[(2)]);
var state_34005__$1 = (function (){var statearr_34030 = state_34005;
(statearr_34030[(9)] = inst_33994);

return statearr_34030;
})();
var statearr_34031_36078 = state_34005__$1;
(statearr_34031_36078[(2)] = null);

(statearr_34031_36078[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (13))){
var inst_33954 = (state_34005[(10)]);
var inst_33957 = cljs.core.chunked_seq_QMARK_(inst_33954);
var state_34005__$1 = state_34005;
if(inst_33957){
var statearr_34032_36079 = state_34005__$1;
(statearr_34032_36079[(1)] = (16));

} else {
var statearr_34033_36081 = state_34005__$1;
(statearr_34033_36081[(1)] = (17));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (22))){
var inst_33986 = (state_34005[(2)]);
var state_34005__$1 = state_34005;
if(cljs.core.truth_(inst_33986)){
var statearr_34034_36084 = state_34005__$1;
(statearr_34034_36084[(1)] = (23));

} else {
var statearr_34035_36085 = state_34005__$1;
(statearr_34035_36085[(1)] = (24));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (6))){
var inst_33930 = (state_34005[(8)]);
var inst_33980 = (state_34005[(7)]);
var inst_33982 = (state_34005[(11)]);
var inst_33980__$1 = (topic_fn.cljs$core$IFn$_invoke$arity$1 ? topic_fn.cljs$core$IFn$_invoke$arity$1(inst_33930) : topic_fn.call(null, inst_33930));
var inst_33981 = cljs.core.deref(mults);
var inst_33982__$1 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_33981,inst_33980__$1);
var state_34005__$1 = (function (){var statearr_34036 = state_34005;
(statearr_34036[(7)] = inst_33980__$1);

(statearr_34036[(11)] = inst_33982__$1);

return statearr_34036;
})();
if(cljs.core.truth_(inst_33982__$1)){
var statearr_34037_36089 = state_34005__$1;
(statearr_34037_36089[(1)] = (19));

} else {
var statearr_34038_36090 = state_34005__$1;
(statearr_34038_36090[(1)] = (20));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (25))){
var inst_33991 = (state_34005[(2)]);
var state_34005__$1 = state_34005;
var statearr_34041_36091 = state_34005__$1;
(statearr_34041_36091[(2)] = inst_33991);

(statearr_34041_36091[(1)] = (21));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (17))){
var inst_33954 = (state_34005[(10)]);
var inst_33965 = cljs.core.first(inst_33954);
var inst_33966 = cljs.core.async.muxch_STAR_(inst_33965);
var inst_33967 = cljs.core.async.close_BANG_(inst_33966);
var inst_33968 = cljs.core.next(inst_33954);
var inst_33940 = inst_33968;
var inst_33941 = null;
var inst_33942 = (0);
var inst_33943 = (0);
var state_34005__$1 = (function (){var statearr_34043 = state_34005;
(statearr_34043[(12)] = inst_33941);

(statearr_34043[(13)] = inst_33942);

(statearr_34043[(14)] = inst_33943);

(statearr_34043[(15)] = inst_33967);

(statearr_34043[(16)] = inst_33940);

return statearr_34043;
})();
var statearr_34045_36096 = state_34005__$1;
(statearr_34045_36096[(2)] = null);

(statearr_34045_36096[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (3))){
var inst_33999 = (state_34005[(2)]);
var state_34005__$1 = state_34005;
return cljs.core.async.impl.ioc_helpers.return_chan(state_34005__$1,inst_33999);
} else {
if((state_val_34006 === (12))){
var inst_33976 = (state_34005[(2)]);
var state_34005__$1 = state_34005;
var statearr_34046_36099 = state_34005__$1;
(statearr_34046_36099[(2)] = inst_33976);

(statearr_34046_36099[(1)] = (9));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (2))){
var state_34005__$1 = state_34005;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_34005__$1,(4),ch);
} else {
if((state_val_34006 === (23))){
var state_34005__$1 = state_34005;
var statearr_34047_36101 = state_34005__$1;
(statearr_34047_36101[(2)] = null);

(statearr_34047_36101[(1)] = (25));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (19))){
var inst_33930 = (state_34005[(8)]);
var inst_33982 = (state_34005[(11)]);
var inst_33984 = cljs.core.async.muxch_STAR_(inst_33982);
var state_34005__$1 = state_34005;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_34005__$1,(22),inst_33984,inst_33930);
} else {
if((state_val_34006 === (11))){
var inst_33954 = (state_34005[(10)]);
var inst_33940 = (state_34005[(16)]);
var inst_33954__$1 = cljs.core.seq(inst_33940);
var state_34005__$1 = (function (){var statearr_34059 = state_34005;
(statearr_34059[(10)] = inst_33954__$1);

return statearr_34059;
})();
if(inst_33954__$1){
var statearr_34060_36106 = state_34005__$1;
(statearr_34060_36106[(1)] = (13));

} else {
var statearr_34061_36108 = state_34005__$1;
(statearr_34061_36108[(1)] = (14));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (9))){
var inst_33978 = (state_34005[(2)]);
var state_34005__$1 = state_34005;
var statearr_34062_36109 = state_34005__$1;
(statearr_34062_36109[(2)] = inst_33978);

(statearr_34062_36109[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (5))){
var inst_33937 = cljs.core.deref(mults);
var inst_33938 = cljs.core.vals(inst_33937);
var inst_33939 = cljs.core.seq(inst_33938);
var inst_33940 = inst_33939;
var inst_33941 = null;
var inst_33942 = (0);
var inst_33943 = (0);
var state_34005__$1 = (function (){var statearr_34064 = state_34005;
(statearr_34064[(12)] = inst_33941);

(statearr_34064[(13)] = inst_33942);

(statearr_34064[(14)] = inst_33943);

(statearr_34064[(16)] = inst_33940);

return statearr_34064;
})();
var statearr_34065_36110 = state_34005__$1;
(statearr_34065_36110[(2)] = null);

(statearr_34065_36110[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (14))){
var state_34005__$1 = state_34005;
var statearr_34069_36111 = state_34005__$1;
(statearr_34069_36111[(2)] = null);

(statearr_34069_36111[(1)] = (15));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (16))){
var inst_33954 = (state_34005[(10)]);
var inst_33959 = cljs.core.chunk_first(inst_33954);
var inst_33960 = cljs.core.chunk_rest(inst_33954);
var inst_33961 = cljs.core.count(inst_33959);
var inst_33940 = inst_33960;
var inst_33941 = inst_33959;
var inst_33942 = inst_33961;
var inst_33943 = (0);
var state_34005__$1 = (function (){var statearr_34074 = state_34005;
(statearr_34074[(12)] = inst_33941);

(statearr_34074[(13)] = inst_33942);

(statearr_34074[(14)] = inst_33943);

(statearr_34074[(16)] = inst_33940);

return statearr_34074;
})();
var statearr_34075_36113 = state_34005__$1;
(statearr_34075_36113[(2)] = null);

(statearr_34075_36113[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (10))){
var inst_33941 = (state_34005[(12)]);
var inst_33942 = (state_34005[(13)]);
var inst_33943 = (state_34005[(14)]);
var inst_33940 = (state_34005[(16)]);
var inst_33948 = cljs.core._nth(inst_33941,inst_33943);
var inst_33949 = cljs.core.async.muxch_STAR_(inst_33948);
var inst_33950 = cljs.core.async.close_BANG_(inst_33949);
var inst_33951 = (inst_33943 + (1));
var tmp34066 = inst_33941;
var tmp34067 = inst_33942;
var tmp34068 = inst_33940;
var inst_33940__$1 = tmp34068;
var inst_33941__$1 = tmp34066;
var inst_33942__$1 = tmp34067;
var inst_33943__$1 = inst_33951;
var state_34005__$1 = (function (){var statearr_34078 = state_34005;
(statearr_34078[(17)] = inst_33950);

(statearr_34078[(12)] = inst_33941__$1);

(statearr_34078[(13)] = inst_33942__$1);

(statearr_34078[(14)] = inst_33943__$1);

(statearr_34078[(16)] = inst_33940__$1);

return statearr_34078;
})();
var statearr_34079_36114 = state_34005__$1;
(statearr_34079_36114[(2)] = null);

(statearr_34079_36114[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (18))){
var inst_33971 = (state_34005[(2)]);
var state_34005__$1 = state_34005;
var statearr_34081_36115 = state_34005__$1;
(statearr_34081_36115[(2)] = inst_33971);

(statearr_34081_36115[(1)] = (15));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34006 === (8))){
var inst_33942 = (state_34005[(13)]);
var inst_33943 = (state_34005[(14)]);
var inst_33945 = (inst_33943 < inst_33942);
var inst_33946 = inst_33945;
var state_34005__$1 = state_34005;
if(cljs.core.truth_(inst_33946)){
var statearr_34084_36117 = state_34005__$1;
(statearr_34084_36117[(1)] = (10));

} else {
var statearr_34085_36118 = state_34005__$1;
(statearr_34085_36118[(1)] = (11));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__30402__auto__ = null;
var cljs$core$async$state_machine__30402__auto____0 = (function (){
var statearr_34087 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_34087[(0)] = cljs$core$async$state_machine__30402__auto__);

(statearr_34087[(1)] = (1));

return statearr_34087;
});
var cljs$core$async$state_machine__30402__auto____1 = (function (state_34005){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_34005);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e34088){var ex__30405__auto__ = e34088;
var statearr_34089_36124 = state_34005;
(statearr_34089_36124[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_34005[(4)]))){
var statearr_34092_36126 = state_34005;
(statearr_34092_36126[(1)] = cljs.core.first((state_34005[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__36127 = state_34005;
state_34005 = G__36127;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$state_machine__30402__auto__ = function(state_34005){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__30402__auto____1.call(this,state_34005);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__30402__auto____0;
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__30402__auto____1;
return cljs$core$async$state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_34094 = f__31515__auto__();
(statearr_34094[(6)] = c__31514__auto___36068);

return statearr_34094;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));


return p;
}));

(cljs.core.async.pub.cljs$lang$maxFixedArity = 3);

/**
 * Subscribes a channel to a topic of a pub.
 * 
 *   By default the channel will be closed when the source closes,
 *   but can be determined by the close? parameter.
 */
cljs.core.async.sub = (function cljs$core$async$sub(var_args){
var G__34101 = arguments.length;
switch (G__34101) {
case 3:
return cljs.core.async.sub.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
case 4:
return cljs.core.async.sub.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.sub.cljs$core$IFn$_invoke$arity$3 = (function (p,topic,ch){
return cljs.core.async.sub.cljs$core$IFn$_invoke$arity$4(p,topic,ch,true);
}));

(cljs.core.async.sub.cljs$core$IFn$_invoke$arity$4 = (function (p,topic,ch,close_QMARK_){
return cljs.core.async.sub_STAR_(p,topic,ch,close_QMARK_);
}));

(cljs.core.async.sub.cljs$lang$maxFixedArity = 4);

/**
 * Unsubscribes a channel from a topic of a pub
 */
cljs.core.async.unsub = (function cljs$core$async$unsub(p,topic,ch){
return cljs.core.async.unsub_STAR_(p,topic,ch);
});
/**
 * Unsubscribes all channels from a pub, or a topic of a pub
 */
cljs.core.async.unsub_all = (function cljs$core$async$unsub_all(var_args){
var G__34114 = arguments.length;
switch (G__34114) {
case 1:
return cljs.core.async.unsub_all.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.unsub_all.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.unsub_all.cljs$core$IFn$_invoke$arity$1 = (function (p){
return cljs.core.async.unsub_all_STAR_(p);
}));

(cljs.core.async.unsub_all.cljs$core$IFn$_invoke$arity$2 = (function (p,topic){
return cljs.core.async.unsub_all_STAR_(p,topic);
}));

(cljs.core.async.unsub_all.cljs$lang$maxFixedArity = 2);

/**
 * Takes a function and a collection of source channels, and returns a
 *   channel which contains the values produced by applying f to the set
 *   of first items taken from each source channel, followed by applying
 *   f to the set of second items from each channel, until any one of the
 *   channels is closed, at which point the output channel will be
 *   closed. The returned channel will be unbuffered by default, or a
 *   buf-or-n can be supplied
 */
cljs.core.async.map = (function cljs$core$async$map(var_args){
var G__34126 = arguments.length;
switch (G__34126) {
case 2:
return cljs.core.async.map.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.map.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.map.cljs$core$IFn$_invoke$arity$2 = (function (f,chs){
return cljs.core.async.map.cljs$core$IFn$_invoke$arity$3(f,chs,null);
}));

(cljs.core.async.map.cljs$core$IFn$_invoke$arity$3 = (function (f,chs,buf_or_n){
var chs__$1 = cljs.core.vec(chs);
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var cnt = cljs.core.count(chs__$1);
var rets = cljs.core.object_array.cljs$core$IFn$_invoke$arity$1(cnt);
var dchan = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
var dctr = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(null);
var done = cljs.core.mapv.cljs$core$IFn$_invoke$arity$2((function (i){
return (function (ret){
(rets[i] = ret);

if((cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$2(dctr,cljs.core.dec) === (0))){
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2(dchan,rets.slice((0)));
} else {
return null;
}
});
}),cljs.core.range.cljs$core$IFn$_invoke$arity$1(cnt));
if((cnt === (0))){
cljs.core.async.close_BANG_(out);
} else {
var c__31514__auto___36174 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_34183){
var state_val_34185 = (state_34183[(1)]);
if((state_val_34185 === (7))){
var state_34183__$1 = state_34183;
var statearr_34188_36175 = state_34183__$1;
(statearr_34188_36175[(2)] = null);

(statearr_34188_36175[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34185 === (1))){
var state_34183__$1 = state_34183;
var statearr_34189_36179 = state_34183__$1;
(statearr_34189_36179[(2)] = null);

(statearr_34189_36179[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34185 === (4))){
var inst_34135 = (state_34183[(7)]);
var inst_34131 = (state_34183[(8)]);
var inst_34137 = (inst_34135 < inst_34131);
var state_34183__$1 = state_34183;
if(cljs.core.truth_(inst_34137)){
var statearr_34190_36180 = state_34183__$1;
(statearr_34190_36180[(1)] = (6));

} else {
var statearr_34191_36181 = state_34183__$1;
(statearr_34191_36181[(1)] = (7));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34185 === (15))){
var inst_34169 = (state_34183[(9)]);
var inst_34174 = cljs.core.apply.cljs$core$IFn$_invoke$arity$2(f,inst_34169);
var state_34183__$1 = state_34183;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_34183__$1,(17),out,inst_34174);
} else {
if((state_val_34185 === (13))){
var inst_34169 = (state_34183[(9)]);
var inst_34169__$1 = (state_34183[(2)]);
var inst_34170 = cljs.core.some(cljs.core.nil_QMARK_,inst_34169__$1);
var state_34183__$1 = (function (){var statearr_34192 = state_34183;
(statearr_34192[(9)] = inst_34169__$1);

return statearr_34192;
})();
if(cljs.core.truth_(inst_34170)){
var statearr_34193_36187 = state_34183__$1;
(statearr_34193_36187[(1)] = (14));

} else {
var statearr_34194_36192 = state_34183__$1;
(statearr_34194_36192[(1)] = (15));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34185 === (6))){
var state_34183__$1 = state_34183;
var statearr_34195_36196 = state_34183__$1;
(statearr_34195_36196[(2)] = null);

(statearr_34195_36196[(1)] = (9));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34185 === (17))){
var inst_34176 = (state_34183[(2)]);
var state_34183__$1 = (function (){var statearr_34212 = state_34183;
(statearr_34212[(10)] = inst_34176);

return statearr_34212;
})();
var statearr_34213_36206 = state_34183__$1;
(statearr_34213_36206[(2)] = null);

(statearr_34213_36206[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34185 === (3))){
var inst_34181 = (state_34183[(2)]);
var state_34183__$1 = state_34183;
return cljs.core.async.impl.ioc_helpers.return_chan(state_34183__$1,inst_34181);
} else {
if((state_val_34185 === (12))){
var _ = (function (){var statearr_34223 = state_34183;
(statearr_34223[(4)] = cljs.core.rest((state_34183[(4)])));

return statearr_34223;
})();
var state_34183__$1 = state_34183;
var ex34207 = (state_34183__$1[(2)]);
var statearr_34224_36209 = state_34183__$1;
(statearr_34224_36209[(5)] = ex34207);


if((ex34207 instanceof Object)){
var statearr_34231_36210 = state_34183__$1;
(statearr_34231_36210[(1)] = (11));

(statearr_34231_36210[(5)] = null);

} else {
throw ex34207;

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34185 === (2))){
var inst_34129 = cljs.core.reset_BANG_(dctr,cnt);
var inst_34131 = cnt;
var inst_34135 = (0);
var state_34183__$1 = (function (){var statearr_34238 = state_34183;
(statearr_34238[(7)] = inst_34135);

(statearr_34238[(8)] = inst_34131);

(statearr_34238[(11)] = inst_34129);

return statearr_34238;
})();
var statearr_34239_36218 = state_34183__$1;
(statearr_34239_36218[(2)] = null);

(statearr_34239_36218[(1)] = (4));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34185 === (11))){
var inst_34148 = (state_34183[(2)]);
var inst_34149 = cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$2(dctr,cljs.core.dec);
var state_34183__$1 = (function (){var statearr_34240 = state_34183;
(statearr_34240[(12)] = inst_34148);

return statearr_34240;
})();
var statearr_34241_36220 = state_34183__$1;
(statearr_34241_36220[(2)] = inst_34149);

(statearr_34241_36220[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34185 === (9))){
var inst_34135 = (state_34183[(7)]);
var _ = (function (){var statearr_34243 = state_34183;
(statearr_34243[(4)] = cljs.core.cons((12),(state_34183[(4)])));

return statearr_34243;
})();
var inst_34155 = (chs__$1.cljs$core$IFn$_invoke$arity$1 ? chs__$1.cljs$core$IFn$_invoke$arity$1(inst_34135) : chs__$1.call(null, inst_34135));
var inst_34156 = (done.cljs$core$IFn$_invoke$arity$1 ? done.cljs$core$IFn$_invoke$arity$1(inst_34135) : done.call(null, inst_34135));
var inst_34157 = cljs.core.async.take_BANG_.cljs$core$IFn$_invoke$arity$2(inst_34155,inst_34156);
var ___$1 = (function (){var statearr_34244 = state_34183;
(statearr_34244[(4)] = cljs.core.rest((state_34183[(4)])));

return statearr_34244;
})();
var state_34183__$1 = state_34183;
var statearr_34245_36221 = state_34183__$1;
(statearr_34245_36221[(2)] = inst_34157);

(statearr_34245_36221[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34185 === (5))){
var inst_34167 = (state_34183[(2)]);
var state_34183__$1 = (function (){var statearr_34247 = state_34183;
(statearr_34247[(13)] = inst_34167);

return statearr_34247;
})();
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_34183__$1,(13),dchan);
} else {
if((state_val_34185 === (14))){
var inst_34172 = cljs.core.async.close_BANG_(out);
var state_34183__$1 = state_34183;
var statearr_34265_36224 = state_34183__$1;
(statearr_34265_36224[(2)] = inst_34172);

(statearr_34265_36224[(1)] = (16));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34185 === (16))){
var inst_34179 = (state_34183[(2)]);
var state_34183__$1 = state_34183;
var statearr_34266_36227 = state_34183__$1;
(statearr_34266_36227[(2)] = inst_34179);

(statearr_34266_36227[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34185 === (10))){
var inst_34135 = (state_34183[(7)]);
var inst_34160 = (state_34183[(2)]);
var inst_34161 = (inst_34135 + (1));
var inst_34135__$1 = inst_34161;
var state_34183__$1 = (function (){var statearr_34267 = state_34183;
(statearr_34267[(14)] = inst_34160);

(statearr_34267[(7)] = inst_34135__$1);

return statearr_34267;
})();
var statearr_34276_36230 = state_34183__$1;
(statearr_34276_36230[(2)] = null);

(statearr_34276_36230[(1)] = (4));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34185 === (8))){
var inst_34165 = (state_34183[(2)]);
var state_34183__$1 = state_34183;
var statearr_34279_36233 = state_34183__$1;
(statearr_34279_36233[(2)] = inst_34165);

(statearr_34279_36233[(1)] = (5));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__30402__auto__ = null;
var cljs$core$async$state_machine__30402__auto____0 = (function (){
var statearr_34283 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_34283[(0)] = cljs$core$async$state_machine__30402__auto__);

(statearr_34283[(1)] = (1));

return statearr_34283;
});
var cljs$core$async$state_machine__30402__auto____1 = (function (state_34183){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_34183);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e34287){var ex__30405__auto__ = e34287;
var statearr_34290_36237 = state_34183;
(statearr_34290_36237[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_34183[(4)]))){
var statearr_34292_36238 = state_34183;
(statearr_34292_36238[(1)] = cljs.core.first((state_34183[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__36239 = state_34183;
state_34183 = G__36239;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$state_machine__30402__auto__ = function(state_34183){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__30402__auto____1.call(this,state_34183);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__30402__auto____0;
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__30402__auto____1;
return cljs$core$async$state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_34296 = f__31515__auto__();
(statearr_34296[(6)] = c__31514__auto___36174);

return statearr_34296;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));

}

return out;
}));

(cljs.core.async.map.cljs$lang$maxFixedArity = 3);

/**
 * Takes a collection of source channels and returns a channel which
 *   contains all values taken from them. The returned channel will be
 *   unbuffered by default, or a buf-or-n can be supplied. The channel
 *   will close after all the source channels have closed.
 */
cljs.core.async.merge = (function cljs$core$async$merge(var_args){
var G__34301 = arguments.length;
switch (G__34301) {
case 1:
return cljs.core.async.merge.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.merge.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.merge.cljs$core$IFn$_invoke$arity$1 = (function (chs){
return cljs.core.async.merge.cljs$core$IFn$_invoke$arity$2(chs,null);
}));

(cljs.core.async.merge.cljs$core$IFn$_invoke$arity$2 = (function (chs,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var c__31514__auto___36249 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_34366){
var state_val_34367 = (state_34366[(1)]);
if((state_val_34367 === (7))){
var inst_34315 = (state_34366[(7)]);
var inst_34316 = (state_34366[(8)]);
var inst_34315__$1 = (state_34366[(2)]);
var inst_34316__$1 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_34315__$1,(0),null);
var inst_34317 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_34315__$1,(1),null);
var inst_34318 = (inst_34316__$1 == null);
var state_34366__$1 = (function (){var statearr_34370 = state_34366;
(statearr_34370[(7)] = inst_34315__$1);

(statearr_34370[(9)] = inst_34317);

(statearr_34370[(8)] = inst_34316__$1);

return statearr_34370;
})();
if(cljs.core.truth_(inst_34318)){
var statearr_34371_36250 = state_34366__$1;
(statearr_34371_36250[(1)] = (8));

} else {
var statearr_34375_36251 = state_34366__$1;
(statearr_34375_36251[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34367 === (1))){
var inst_34304 = cljs.core.vec(chs);
var inst_34305 = inst_34304;
var state_34366__$1 = (function (){var statearr_34376 = state_34366;
(statearr_34376[(10)] = inst_34305);

return statearr_34376;
})();
var statearr_34377_36253 = state_34366__$1;
(statearr_34377_36253[(2)] = null);

(statearr_34377_36253[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34367 === (4))){
var inst_34305 = (state_34366[(10)]);
var state_34366__$1 = state_34366;
return cljs.core.async.ioc_alts_BANG_(state_34366__$1,(7),inst_34305);
} else {
if((state_val_34367 === (6))){
var inst_34356 = (state_34366[(2)]);
var state_34366__$1 = state_34366;
var statearr_34378_36254 = state_34366__$1;
(statearr_34378_36254[(2)] = inst_34356);

(statearr_34378_36254[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34367 === (3))){
var inst_34360 = (state_34366[(2)]);
var state_34366__$1 = state_34366;
return cljs.core.async.impl.ioc_helpers.return_chan(state_34366__$1,inst_34360);
} else {
if((state_val_34367 === (2))){
var inst_34305 = (state_34366[(10)]);
var inst_34307 = cljs.core.count(inst_34305);
var inst_34308 = (inst_34307 > (0));
var state_34366__$1 = state_34366;
if(cljs.core.truth_(inst_34308)){
var statearr_34381_36255 = state_34366__$1;
(statearr_34381_36255[(1)] = (4));

} else {
var statearr_34382_36256 = state_34366__$1;
(statearr_34382_36256[(1)] = (5));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34367 === (11))){
var inst_34305 = (state_34366[(10)]);
var inst_34349 = (state_34366[(2)]);
var tmp34379 = inst_34305;
var inst_34305__$1 = tmp34379;
var state_34366__$1 = (function (){var statearr_34383 = state_34366;
(statearr_34383[(11)] = inst_34349);

(statearr_34383[(10)] = inst_34305__$1);

return statearr_34383;
})();
var statearr_34384_36261 = state_34366__$1;
(statearr_34384_36261[(2)] = null);

(statearr_34384_36261[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34367 === (9))){
var inst_34316 = (state_34366[(8)]);
var state_34366__$1 = state_34366;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_34366__$1,(11),out,inst_34316);
} else {
if((state_val_34367 === (5))){
var inst_34354 = cljs.core.async.close_BANG_(out);
var state_34366__$1 = state_34366;
var statearr_34400_36263 = state_34366__$1;
(statearr_34400_36263[(2)] = inst_34354);

(statearr_34400_36263[(1)] = (6));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34367 === (10))){
var inst_34352 = (state_34366[(2)]);
var state_34366__$1 = state_34366;
var statearr_34403_36265 = state_34366__$1;
(statearr_34403_36265[(2)] = inst_34352);

(statearr_34403_36265[(1)] = (6));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34367 === (8))){
var inst_34315 = (state_34366[(7)]);
var inst_34317 = (state_34366[(9)]);
var inst_34305 = (state_34366[(10)]);
var inst_34316 = (state_34366[(8)]);
var inst_34336 = (function (){var cs = inst_34305;
var vec__34311 = inst_34315;
var v = inst_34316;
var c = inst_34317;
return (function (p1__34299_SHARP_){
return cljs.core.not_EQ_.cljs$core$IFn$_invoke$arity$2(c,p1__34299_SHARP_);
});
})();
var inst_34341 = cljs.core.filterv(inst_34336,inst_34305);
var inst_34305__$1 = inst_34341;
var state_34366__$1 = (function (){var statearr_34404 = state_34366;
(statearr_34404[(10)] = inst_34305__$1);

return statearr_34404;
})();
var statearr_34407_36268 = state_34366__$1;
(statearr_34407_36268[(2)] = null);

(statearr_34407_36268[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
}
});
return (function() {
var cljs$core$async$state_machine__30402__auto__ = null;
var cljs$core$async$state_machine__30402__auto____0 = (function (){
var statearr_34409 = [null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_34409[(0)] = cljs$core$async$state_machine__30402__auto__);

(statearr_34409[(1)] = (1));

return statearr_34409;
});
var cljs$core$async$state_machine__30402__auto____1 = (function (state_34366){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_34366);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e34411){var ex__30405__auto__ = e34411;
var statearr_34412_36269 = state_34366;
(statearr_34412_36269[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_34366[(4)]))){
var statearr_34413_36271 = state_34366;
(statearr_34413_36271[(1)] = cljs.core.first((state_34366[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__36272 = state_34366;
state_34366 = G__36272;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$state_machine__30402__auto__ = function(state_34366){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__30402__auto____1.call(this,state_34366);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__30402__auto____0;
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__30402__auto____1;
return cljs$core$async$state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_34417 = f__31515__auto__();
(statearr_34417[(6)] = c__31514__auto___36249);

return statearr_34417;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));


return out;
}));

(cljs.core.async.merge.cljs$lang$maxFixedArity = 2);

/**
 * Returns a channel containing the single (collection) result of the
 *   items taken from the channel conjoined to the supplied
 *   collection. ch must close before into produces a result.
 */
cljs.core.async.into = (function cljs$core$async$into(coll,ch){
return cljs.core.async.reduce(cljs.core.conj,coll,ch);
});
/**
 * Returns a channel that will return, at most, n items from ch. After n items
 * have been returned, or ch has been closed, the return chanel will close.
 * 
 *   The output channel is unbuffered by default, unless buf-or-n is given.
 */
cljs.core.async.take = (function cljs$core$async$take(var_args){
var G__34425 = arguments.length;
switch (G__34425) {
case 2:
return cljs.core.async.take.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.take.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.take.cljs$core$IFn$_invoke$arity$2 = (function (n,ch){
return cljs.core.async.take.cljs$core$IFn$_invoke$arity$3(n,ch,null);
}));

(cljs.core.async.take.cljs$core$IFn$_invoke$arity$3 = (function (n,ch,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var c__31514__auto___36275 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_34471){
var state_val_34472 = (state_34471[(1)]);
if((state_val_34472 === (7))){
var inst_34450 = (state_34471[(7)]);
var inst_34450__$1 = (state_34471[(2)]);
var inst_34451 = (inst_34450__$1 == null);
var inst_34452 = cljs.core.not(inst_34451);
var state_34471__$1 = (function (){var statearr_34476 = state_34471;
(statearr_34476[(7)] = inst_34450__$1);

return statearr_34476;
})();
if(inst_34452){
var statearr_34477_36276 = state_34471__$1;
(statearr_34477_36276[(1)] = (8));

} else {
var statearr_34478_36277 = state_34471__$1;
(statearr_34478_36277[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34472 === (1))){
var inst_34441 = (0);
var state_34471__$1 = (function (){var statearr_34480 = state_34471;
(statearr_34480[(8)] = inst_34441);

return statearr_34480;
})();
var statearr_34481_36281 = state_34471__$1;
(statearr_34481_36281[(2)] = null);

(statearr_34481_36281[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34472 === (4))){
var state_34471__$1 = state_34471;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_34471__$1,(7),ch);
} else {
if((state_val_34472 === (6))){
var inst_34466 = (state_34471[(2)]);
var state_34471__$1 = state_34471;
var statearr_34483_36282 = state_34471__$1;
(statearr_34483_36282[(2)] = inst_34466);

(statearr_34483_36282[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34472 === (3))){
var inst_34468 = (state_34471[(2)]);
var inst_34469 = cljs.core.async.close_BANG_(out);
var state_34471__$1 = (function (){var statearr_34485 = state_34471;
(statearr_34485[(9)] = inst_34468);

return statearr_34485;
})();
return cljs.core.async.impl.ioc_helpers.return_chan(state_34471__$1,inst_34469);
} else {
if((state_val_34472 === (2))){
var inst_34441 = (state_34471[(8)]);
var inst_34446 = (inst_34441 < n);
var state_34471__$1 = state_34471;
if(cljs.core.truth_(inst_34446)){
var statearr_34486_36283 = state_34471__$1;
(statearr_34486_36283[(1)] = (4));

} else {
var statearr_34487_36284 = state_34471__$1;
(statearr_34487_36284[(1)] = (5));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34472 === (11))){
var inst_34441 = (state_34471[(8)]);
var inst_34456 = (state_34471[(2)]);
var inst_34458 = (inst_34441 + (1));
var inst_34441__$1 = inst_34458;
var state_34471__$1 = (function (){var statearr_34491 = state_34471;
(statearr_34491[(10)] = inst_34456);

(statearr_34491[(8)] = inst_34441__$1);

return statearr_34491;
})();
var statearr_34492_36286 = state_34471__$1;
(statearr_34492_36286[(2)] = null);

(statearr_34492_36286[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34472 === (9))){
var state_34471__$1 = state_34471;
var statearr_34493_36291 = state_34471__$1;
(statearr_34493_36291[(2)] = null);

(statearr_34493_36291[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34472 === (5))){
var state_34471__$1 = state_34471;
var statearr_34494_36292 = state_34471__$1;
(statearr_34494_36292[(2)] = null);

(statearr_34494_36292[(1)] = (6));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34472 === (10))){
var inst_34462 = (state_34471[(2)]);
var state_34471__$1 = state_34471;
var statearr_34496_36293 = state_34471__$1;
(statearr_34496_36293[(2)] = inst_34462);

(statearr_34496_36293[(1)] = (6));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34472 === (8))){
var inst_34450 = (state_34471[(7)]);
var state_34471__$1 = state_34471;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_34471__$1,(11),out,inst_34450);
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
}
});
return (function() {
var cljs$core$async$state_machine__30402__auto__ = null;
var cljs$core$async$state_machine__30402__auto____0 = (function (){
var statearr_34497 = [null,null,null,null,null,null,null,null,null,null,null];
(statearr_34497[(0)] = cljs$core$async$state_machine__30402__auto__);

(statearr_34497[(1)] = (1));

return statearr_34497;
});
var cljs$core$async$state_machine__30402__auto____1 = (function (state_34471){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_34471);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e34498){var ex__30405__auto__ = e34498;
var statearr_34500_36300 = state_34471;
(statearr_34500_36300[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_34471[(4)]))){
var statearr_34501_36303 = state_34471;
(statearr_34501_36303[(1)] = cljs.core.first((state_34471[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__36307 = state_34471;
state_34471 = G__36307;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$state_machine__30402__auto__ = function(state_34471){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__30402__auto____1.call(this,state_34471);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__30402__auto____0;
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__30402__auto____1;
return cljs$core$async$state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_34503 = f__31515__auto__();
(statearr_34503[(6)] = c__31514__auto___36275);

return statearr_34503;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));


return out;
}));

(cljs.core.async.take.cljs$lang$maxFixedArity = 3);


/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Handler}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async34517 = (function (f,ch,meta34507,_,fn1,meta34518){
this.f = f;
this.ch = ch;
this.meta34507 = meta34507;
this._ = _;
this.fn1 = fn1;
this.meta34518 = meta34518;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async34517.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_34519,meta34518__$1){
var self__ = this;
var _34519__$1 = this;
return (new cljs.core.async.t_cljs$core$async34517(self__.f,self__.ch,self__.meta34507,self__._,self__.fn1,meta34518__$1));
}));

(cljs.core.async.t_cljs$core$async34517.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_34519){
var self__ = this;
var _34519__$1 = this;
return self__.meta34518;
}));

(cljs.core.async.t_cljs$core$async34517.prototype.cljs$core$async$impl$protocols$Handler$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async34517.prototype.cljs$core$async$impl$protocols$Handler$active_QMARK_$arity$1 = (function (___$1){
var self__ = this;
var ___$2 = this;
return cljs.core.async.impl.protocols.active_QMARK_(self__.fn1);
}));

(cljs.core.async.t_cljs$core$async34517.prototype.cljs$core$async$impl$protocols$Handler$blockable_QMARK_$arity$1 = (function (___$1){
var self__ = this;
var ___$2 = this;
return true;
}));

(cljs.core.async.t_cljs$core$async34517.prototype.cljs$core$async$impl$protocols$Handler$commit$arity$1 = (function (___$1){
var self__ = this;
var ___$2 = this;
var f1 = cljs.core.async.impl.protocols.commit(self__.fn1);
return (function (p1__34504_SHARP_){
var G__34535 = (((p1__34504_SHARP_ == null))?null:(self__.f.cljs$core$IFn$_invoke$arity$1 ? self__.f.cljs$core$IFn$_invoke$arity$1(p1__34504_SHARP_) : self__.f.call(null, p1__34504_SHARP_)));
return (f1.cljs$core$IFn$_invoke$arity$1 ? f1.cljs$core$IFn$_invoke$arity$1(G__34535) : f1.call(null, G__34535));
});
}));

(cljs.core.async.t_cljs$core$async34517.getBasis = (function (){
return new cljs.core.PersistentVector(null, 6, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"f","f",43394975,null),new cljs.core.Symbol(null,"ch","ch",1085813622,null),new cljs.core.Symbol(null,"meta34507","meta34507",-97039610,null),cljs.core.with_meta(new cljs.core.Symbol(null,"_","_",-1201019570,null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"tag","tag",-1290361223),new cljs.core.Symbol("cljs.core.async","t_cljs$core$async34506","cljs.core.async/t_cljs$core$async34506",-383805577,null)], null)),new cljs.core.Symbol(null,"fn1","fn1",895834444,null),new cljs.core.Symbol(null,"meta34518","meta34518",1390969303,null)], null);
}));

(cljs.core.async.t_cljs$core$async34517.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async34517.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async34517");

(cljs.core.async.t_cljs$core$async34517.cljs$lang$ctorPrWriter = (function (this__5287__auto__,writer__5288__auto__,opt__5289__auto__){
return cljs.core._write(writer__5288__auto__,"cljs.core.async/t_cljs$core$async34517");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async34517.
 */
cljs.core.async.__GT_t_cljs$core$async34517 = (function cljs$core$async$__GT_t_cljs$core$async34517(f,ch,meta34507,_,fn1,meta34518){
return (new cljs.core.async.t_cljs$core$async34517(f,ch,meta34507,_,fn1,meta34518));
});



/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Channel}
 * @implements {cljs.core.async.impl.protocols.WritePort}
 * @implements {cljs.core.async.impl.protocols.ReadPort}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async34506 = (function (f,ch,meta34507){
this.f = f;
this.ch = ch;
this.meta34507 = meta34507;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async34506.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_34508,meta34507__$1){
var self__ = this;
var _34508__$1 = this;
return (new cljs.core.async.t_cljs$core$async34506(self__.f,self__.ch,meta34507__$1));
}));

(cljs.core.async.t_cljs$core$async34506.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_34508){
var self__ = this;
var _34508__$1 = this;
return self__.meta34507;
}));

(cljs.core.async.t_cljs$core$async34506.prototype.cljs$core$async$impl$protocols$Channel$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async34506.prototype.cljs$core$async$impl$protocols$Channel$close_BANG_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.close_BANG_(self__.ch);
}));

(cljs.core.async.t_cljs$core$async34506.prototype.cljs$core$async$impl$protocols$Channel$closed_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.closed_QMARK_(self__.ch);
}));

(cljs.core.async.t_cljs$core$async34506.prototype.cljs$core$async$impl$protocols$ReadPort$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async34506.prototype.cljs$core$async$impl$protocols$ReadPort$take_BANG_$arity$2 = (function (_,fn1){
var self__ = this;
var ___$1 = this;
var ret = cljs.core.async.impl.protocols.take_BANG_(self__.ch,(new cljs.core.async.t_cljs$core$async34517(self__.f,self__.ch,self__.meta34507,___$1,fn1,cljs.core.PersistentArrayMap.EMPTY)));
if(cljs.core.truth_((function (){var and__5000__auto__ = ret;
if(cljs.core.truth_(and__5000__auto__)){
return (!((cljs.core.deref(ret) == null)));
} else {
return and__5000__auto__;
}
})())){
return cljs.core.async.impl.channels.box((function (){var G__34538 = cljs.core.deref(ret);
return (self__.f.cljs$core$IFn$_invoke$arity$1 ? self__.f.cljs$core$IFn$_invoke$arity$1(G__34538) : self__.f.call(null, G__34538));
})());
} else {
return ret;
}
}));

(cljs.core.async.t_cljs$core$async34506.prototype.cljs$core$async$impl$protocols$WritePort$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async34506.prototype.cljs$core$async$impl$protocols$WritePort$put_BANG_$arity$3 = (function (_,val,fn1){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.put_BANG_(self__.ch,val,fn1);
}));

(cljs.core.async.t_cljs$core$async34506.getBasis = (function (){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"f","f",43394975,null),new cljs.core.Symbol(null,"ch","ch",1085813622,null),new cljs.core.Symbol(null,"meta34507","meta34507",-97039610,null)], null);
}));

(cljs.core.async.t_cljs$core$async34506.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async34506.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async34506");

(cljs.core.async.t_cljs$core$async34506.cljs$lang$ctorPrWriter = (function (this__5287__auto__,writer__5288__auto__,opt__5289__auto__){
return cljs.core._write(writer__5288__auto__,"cljs.core.async/t_cljs$core$async34506");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async34506.
 */
cljs.core.async.__GT_t_cljs$core$async34506 = (function cljs$core$async$__GT_t_cljs$core$async34506(f,ch,meta34507){
return (new cljs.core.async.t_cljs$core$async34506(f,ch,meta34507));
});


/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.map_LT_ = (function cljs$core$async$map_LT_(f,ch){
return (new cljs.core.async.t_cljs$core$async34506(f,ch,cljs.core.PersistentArrayMap.EMPTY));
});

/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Channel}
 * @implements {cljs.core.async.impl.protocols.WritePort}
 * @implements {cljs.core.async.impl.protocols.ReadPort}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async34541 = (function (f,ch,meta34542){
this.f = f;
this.ch = ch;
this.meta34542 = meta34542;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async34541.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_34543,meta34542__$1){
var self__ = this;
var _34543__$1 = this;
return (new cljs.core.async.t_cljs$core$async34541(self__.f,self__.ch,meta34542__$1));
}));

(cljs.core.async.t_cljs$core$async34541.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_34543){
var self__ = this;
var _34543__$1 = this;
return self__.meta34542;
}));

(cljs.core.async.t_cljs$core$async34541.prototype.cljs$core$async$impl$protocols$Channel$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async34541.prototype.cljs$core$async$impl$protocols$Channel$close_BANG_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.close_BANG_(self__.ch);
}));

(cljs.core.async.t_cljs$core$async34541.prototype.cljs$core$async$impl$protocols$ReadPort$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async34541.prototype.cljs$core$async$impl$protocols$ReadPort$take_BANG_$arity$2 = (function (_,fn1){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.take_BANG_(self__.ch,fn1);
}));

(cljs.core.async.t_cljs$core$async34541.prototype.cljs$core$async$impl$protocols$WritePort$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async34541.prototype.cljs$core$async$impl$protocols$WritePort$put_BANG_$arity$3 = (function (_,val,fn1){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.put_BANG_(self__.ch,(self__.f.cljs$core$IFn$_invoke$arity$1 ? self__.f.cljs$core$IFn$_invoke$arity$1(val) : self__.f.call(null, val)),fn1);
}));

(cljs.core.async.t_cljs$core$async34541.getBasis = (function (){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"f","f",43394975,null),new cljs.core.Symbol(null,"ch","ch",1085813622,null),new cljs.core.Symbol(null,"meta34542","meta34542",-1555924157,null)], null);
}));

(cljs.core.async.t_cljs$core$async34541.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async34541.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async34541");

(cljs.core.async.t_cljs$core$async34541.cljs$lang$ctorPrWriter = (function (this__5287__auto__,writer__5288__auto__,opt__5289__auto__){
return cljs.core._write(writer__5288__auto__,"cljs.core.async/t_cljs$core$async34541");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async34541.
 */
cljs.core.async.__GT_t_cljs$core$async34541 = (function cljs$core$async$__GT_t_cljs$core$async34541(f,ch,meta34542){
return (new cljs.core.async.t_cljs$core$async34541(f,ch,meta34542));
});


/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.map_GT_ = (function cljs$core$async$map_GT_(f,ch){
return (new cljs.core.async.t_cljs$core$async34541(f,ch,cljs.core.PersistentArrayMap.EMPTY));
});

/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Channel}
 * @implements {cljs.core.async.impl.protocols.WritePort}
 * @implements {cljs.core.async.impl.protocols.ReadPort}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async34565 = (function (p,ch,meta34566){
this.p = p;
this.ch = ch;
this.meta34566 = meta34566;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async34565.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_34567,meta34566__$1){
var self__ = this;
var _34567__$1 = this;
return (new cljs.core.async.t_cljs$core$async34565(self__.p,self__.ch,meta34566__$1));
}));

(cljs.core.async.t_cljs$core$async34565.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_34567){
var self__ = this;
var _34567__$1 = this;
return self__.meta34566;
}));

(cljs.core.async.t_cljs$core$async34565.prototype.cljs$core$async$impl$protocols$Channel$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async34565.prototype.cljs$core$async$impl$protocols$Channel$close_BANG_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.close_BANG_(self__.ch);
}));

(cljs.core.async.t_cljs$core$async34565.prototype.cljs$core$async$impl$protocols$Channel$closed_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.closed_QMARK_(self__.ch);
}));

(cljs.core.async.t_cljs$core$async34565.prototype.cljs$core$async$impl$protocols$ReadPort$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async34565.prototype.cljs$core$async$impl$protocols$ReadPort$take_BANG_$arity$2 = (function (_,fn1){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.take_BANG_(self__.ch,fn1);
}));

(cljs.core.async.t_cljs$core$async34565.prototype.cljs$core$async$impl$protocols$WritePort$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async34565.prototype.cljs$core$async$impl$protocols$WritePort$put_BANG_$arity$3 = (function (_,val,fn1){
var self__ = this;
var ___$1 = this;
if(cljs.core.truth_((self__.p.cljs$core$IFn$_invoke$arity$1 ? self__.p.cljs$core$IFn$_invoke$arity$1(val) : self__.p.call(null, val)))){
return cljs.core.async.impl.protocols.put_BANG_(self__.ch,val,fn1);
} else {
return cljs.core.async.impl.channels.box(cljs.core.not(cljs.core.async.impl.protocols.closed_QMARK_(self__.ch)));
}
}));

(cljs.core.async.t_cljs$core$async34565.getBasis = (function (){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"p","p",1791580836,null),new cljs.core.Symbol(null,"ch","ch",1085813622,null),new cljs.core.Symbol(null,"meta34566","meta34566",-1425621575,null)], null);
}));

(cljs.core.async.t_cljs$core$async34565.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async34565.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async34565");

(cljs.core.async.t_cljs$core$async34565.cljs$lang$ctorPrWriter = (function (this__5287__auto__,writer__5288__auto__,opt__5289__auto__){
return cljs.core._write(writer__5288__auto__,"cljs.core.async/t_cljs$core$async34565");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async34565.
 */
cljs.core.async.__GT_t_cljs$core$async34565 = (function cljs$core$async$__GT_t_cljs$core$async34565(p,ch,meta34566){
return (new cljs.core.async.t_cljs$core$async34565(p,ch,meta34566));
});


/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.filter_GT_ = (function cljs$core$async$filter_GT_(p,ch){
return (new cljs.core.async.t_cljs$core$async34565(p,ch,cljs.core.PersistentArrayMap.EMPTY));
});
/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.remove_GT_ = (function cljs$core$async$remove_GT_(p,ch){
return cljs.core.async.filter_GT_(cljs.core.complement(p),ch);
});
/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.filter_LT_ = (function cljs$core$async$filter_LT_(var_args){
var G__34610 = arguments.length;
switch (G__34610) {
case 2:
return cljs.core.async.filter_LT_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.filter_LT_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.filter_LT_.cljs$core$IFn$_invoke$arity$2 = (function (p,ch){
return cljs.core.async.filter_LT_.cljs$core$IFn$_invoke$arity$3(p,ch,null);
}));

(cljs.core.async.filter_LT_.cljs$core$IFn$_invoke$arity$3 = (function (p,ch,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var c__31514__auto___36331 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_34650){
var state_val_34652 = (state_34650[(1)]);
if((state_val_34652 === (7))){
var inst_34645 = (state_34650[(2)]);
var state_34650__$1 = state_34650;
var statearr_34661_36335 = state_34650__$1;
(statearr_34661_36335[(2)] = inst_34645);

(statearr_34661_36335[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34652 === (1))){
var state_34650__$1 = state_34650;
var statearr_34662_36337 = state_34650__$1;
(statearr_34662_36337[(2)] = null);

(statearr_34662_36337[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34652 === (4))){
var inst_34630 = (state_34650[(7)]);
var inst_34630__$1 = (state_34650[(2)]);
var inst_34632 = (inst_34630__$1 == null);
var state_34650__$1 = (function (){var statearr_34664 = state_34650;
(statearr_34664[(7)] = inst_34630__$1);

return statearr_34664;
})();
if(cljs.core.truth_(inst_34632)){
var statearr_34667_36339 = state_34650__$1;
(statearr_34667_36339[(1)] = (5));

} else {
var statearr_34668_36341 = state_34650__$1;
(statearr_34668_36341[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34652 === (6))){
var inst_34630 = (state_34650[(7)]);
var inst_34636 = (p.cljs$core$IFn$_invoke$arity$1 ? p.cljs$core$IFn$_invoke$arity$1(inst_34630) : p.call(null, inst_34630));
var state_34650__$1 = state_34650;
if(cljs.core.truth_(inst_34636)){
var statearr_34672_36345 = state_34650__$1;
(statearr_34672_36345[(1)] = (8));

} else {
var statearr_34673_36346 = state_34650__$1;
(statearr_34673_36346[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34652 === (3))){
var inst_34647 = (state_34650[(2)]);
var state_34650__$1 = state_34650;
return cljs.core.async.impl.ioc_helpers.return_chan(state_34650__$1,inst_34647);
} else {
if((state_val_34652 === (2))){
var state_34650__$1 = state_34650;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_34650__$1,(4),ch);
} else {
if((state_val_34652 === (11))){
var inst_34639 = (state_34650[(2)]);
var state_34650__$1 = state_34650;
var statearr_34674_36347 = state_34650__$1;
(statearr_34674_36347[(2)] = inst_34639);

(statearr_34674_36347[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34652 === (9))){
var state_34650__$1 = state_34650;
var statearr_34676_36351 = state_34650__$1;
(statearr_34676_36351[(2)] = null);

(statearr_34676_36351[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34652 === (5))){
var inst_34634 = cljs.core.async.close_BANG_(out);
var state_34650__$1 = state_34650;
var statearr_34677_36353 = state_34650__$1;
(statearr_34677_36353[(2)] = inst_34634);

(statearr_34677_36353[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34652 === (10))){
var inst_34642 = (state_34650[(2)]);
var state_34650__$1 = (function (){var statearr_34678 = state_34650;
(statearr_34678[(8)] = inst_34642);

return statearr_34678;
})();
var statearr_34679_36354 = state_34650__$1;
(statearr_34679_36354[(2)] = null);

(statearr_34679_36354[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34652 === (8))){
var inst_34630 = (state_34650[(7)]);
var state_34650__$1 = state_34650;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_34650__$1,(11),out,inst_34630);
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
}
});
return (function() {
var cljs$core$async$state_machine__30402__auto__ = null;
var cljs$core$async$state_machine__30402__auto____0 = (function (){
var statearr_34690 = [null,null,null,null,null,null,null,null,null];
(statearr_34690[(0)] = cljs$core$async$state_machine__30402__auto__);

(statearr_34690[(1)] = (1));

return statearr_34690;
});
var cljs$core$async$state_machine__30402__auto____1 = (function (state_34650){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_34650);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e34691){var ex__30405__auto__ = e34691;
var statearr_34693_36355 = state_34650;
(statearr_34693_36355[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_34650[(4)]))){
var statearr_34696_36356 = state_34650;
(statearr_34696_36356[(1)] = cljs.core.first((state_34650[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__36357 = state_34650;
state_34650 = G__36357;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$state_machine__30402__auto__ = function(state_34650){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__30402__auto____1.call(this,state_34650);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__30402__auto____0;
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__30402__auto____1;
return cljs$core$async$state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_34701 = f__31515__auto__();
(statearr_34701[(6)] = c__31514__auto___36331);

return statearr_34701;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));


return out;
}));

(cljs.core.async.filter_LT_.cljs$lang$maxFixedArity = 3);

/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.remove_LT_ = (function cljs$core$async$remove_LT_(var_args){
var G__34707 = arguments.length;
switch (G__34707) {
case 2:
return cljs.core.async.remove_LT_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.remove_LT_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.remove_LT_.cljs$core$IFn$_invoke$arity$2 = (function (p,ch){
return cljs.core.async.remove_LT_.cljs$core$IFn$_invoke$arity$3(p,ch,null);
}));

(cljs.core.async.remove_LT_.cljs$core$IFn$_invoke$arity$3 = (function (p,ch,buf_or_n){
return cljs.core.async.filter_LT_.cljs$core$IFn$_invoke$arity$3(cljs.core.complement(p),ch,buf_or_n);
}));

(cljs.core.async.remove_LT_.cljs$lang$maxFixedArity = 3);

cljs.core.async.mapcat_STAR_ = (function cljs$core$async$mapcat_STAR_(f,in$,out){
var c__31514__auto__ = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_34797){
var state_val_34798 = (state_34797[(1)]);
if((state_val_34798 === (7))){
var inst_34790 = (state_34797[(2)]);
var state_34797__$1 = state_34797;
var statearr_34808_36363 = state_34797__$1;
(statearr_34808_36363[(2)] = inst_34790);

(statearr_34808_36363[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (20))){
var inst_34753 = (state_34797[(7)]);
var inst_34768 = (state_34797[(2)]);
var inst_34770 = cljs.core.next(inst_34753);
var inst_34736 = inst_34770;
var inst_34737 = null;
var inst_34738 = (0);
var inst_34739 = (0);
var state_34797__$1 = (function (){var statearr_34811 = state_34797;
(statearr_34811[(8)] = inst_34768);

(statearr_34811[(9)] = inst_34737);

(statearr_34811[(10)] = inst_34739);

(statearr_34811[(11)] = inst_34738);

(statearr_34811[(12)] = inst_34736);

return statearr_34811;
})();
var statearr_34814_36368 = state_34797__$1;
(statearr_34814_36368[(2)] = null);

(statearr_34814_36368[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (1))){
var state_34797__$1 = state_34797;
var statearr_34817_36369 = state_34797__$1;
(statearr_34817_36369[(2)] = null);

(statearr_34817_36369[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (4))){
var inst_34723 = (state_34797[(13)]);
var inst_34723__$1 = (state_34797[(2)]);
var inst_34725 = (inst_34723__$1 == null);
var state_34797__$1 = (function (){var statearr_34819 = state_34797;
(statearr_34819[(13)] = inst_34723__$1);

return statearr_34819;
})();
if(cljs.core.truth_(inst_34725)){
var statearr_34820_36370 = state_34797__$1;
(statearr_34820_36370[(1)] = (5));

} else {
var statearr_34825_36371 = state_34797__$1;
(statearr_34825_36371[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (15))){
var state_34797__$1 = state_34797;
var statearr_34832_36372 = state_34797__$1;
(statearr_34832_36372[(2)] = null);

(statearr_34832_36372[(1)] = (16));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (21))){
var state_34797__$1 = state_34797;
var statearr_34834_36377 = state_34797__$1;
(statearr_34834_36377[(2)] = null);

(statearr_34834_36377[(1)] = (23));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (13))){
var inst_34737 = (state_34797[(9)]);
var inst_34739 = (state_34797[(10)]);
var inst_34738 = (state_34797[(11)]);
var inst_34736 = (state_34797[(12)]);
var inst_34748 = (state_34797[(2)]);
var inst_34750 = (inst_34739 + (1));
var tmp34828 = inst_34737;
var tmp34829 = inst_34738;
var tmp34830 = inst_34736;
var inst_34736__$1 = tmp34830;
var inst_34737__$1 = tmp34828;
var inst_34738__$1 = tmp34829;
var inst_34739__$1 = inst_34750;
var state_34797__$1 = (function (){var statearr_34838 = state_34797;
(statearr_34838[(9)] = inst_34737__$1);

(statearr_34838[(14)] = inst_34748);

(statearr_34838[(10)] = inst_34739__$1);

(statearr_34838[(11)] = inst_34738__$1);

(statearr_34838[(12)] = inst_34736__$1);

return statearr_34838;
})();
var statearr_34839_36391 = state_34797__$1;
(statearr_34839_36391[(2)] = null);

(statearr_34839_36391[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (22))){
var state_34797__$1 = state_34797;
var statearr_34842_36392 = state_34797__$1;
(statearr_34842_36392[(2)] = null);

(statearr_34842_36392[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (6))){
var inst_34723 = (state_34797[(13)]);
var inst_34734 = (f.cljs$core$IFn$_invoke$arity$1 ? f.cljs$core$IFn$_invoke$arity$1(inst_34723) : f.call(null, inst_34723));
var inst_34735 = cljs.core.seq(inst_34734);
var inst_34736 = inst_34735;
var inst_34737 = null;
var inst_34738 = (0);
var inst_34739 = (0);
var state_34797__$1 = (function (){var statearr_34851 = state_34797;
(statearr_34851[(9)] = inst_34737);

(statearr_34851[(10)] = inst_34739);

(statearr_34851[(11)] = inst_34738);

(statearr_34851[(12)] = inst_34736);

return statearr_34851;
})();
var statearr_34853_36398 = state_34797__$1;
(statearr_34853_36398[(2)] = null);

(statearr_34853_36398[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (17))){
var inst_34753 = (state_34797[(7)]);
var inst_34758 = cljs.core.chunk_first(inst_34753);
var inst_34759 = cljs.core.chunk_rest(inst_34753);
var inst_34761 = cljs.core.count(inst_34758);
var inst_34736 = inst_34759;
var inst_34737 = inst_34758;
var inst_34738 = inst_34761;
var inst_34739 = (0);
var state_34797__$1 = (function (){var statearr_34864 = state_34797;
(statearr_34864[(9)] = inst_34737);

(statearr_34864[(10)] = inst_34739);

(statearr_34864[(11)] = inst_34738);

(statearr_34864[(12)] = inst_34736);

return statearr_34864;
})();
var statearr_34865_36406 = state_34797__$1;
(statearr_34865_36406[(2)] = null);

(statearr_34865_36406[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (3))){
var inst_34794 = (state_34797[(2)]);
var state_34797__$1 = state_34797;
return cljs.core.async.impl.ioc_helpers.return_chan(state_34797__$1,inst_34794);
} else {
if((state_val_34798 === (12))){
var inst_34778 = (state_34797[(2)]);
var state_34797__$1 = state_34797;
var statearr_34886_36410 = state_34797__$1;
(statearr_34886_36410[(2)] = inst_34778);

(statearr_34886_36410[(1)] = (9));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (2))){
var state_34797__$1 = state_34797;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_34797__$1,(4),in$);
} else {
if((state_val_34798 === (23))){
var inst_34787 = (state_34797[(2)]);
var state_34797__$1 = state_34797;
var statearr_34891_36412 = state_34797__$1;
(statearr_34891_36412[(2)] = inst_34787);

(statearr_34891_36412[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (19))){
var inst_34773 = (state_34797[(2)]);
var state_34797__$1 = state_34797;
var statearr_34897_36417 = state_34797__$1;
(statearr_34897_36417[(2)] = inst_34773);

(statearr_34897_36417[(1)] = (16));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (11))){
var inst_34753 = (state_34797[(7)]);
var inst_34736 = (state_34797[(12)]);
var inst_34753__$1 = cljs.core.seq(inst_34736);
var state_34797__$1 = (function (){var statearr_34903 = state_34797;
(statearr_34903[(7)] = inst_34753__$1);

return statearr_34903;
})();
if(inst_34753__$1){
var statearr_34904_36425 = state_34797__$1;
(statearr_34904_36425[(1)] = (14));

} else {
var statearr_34905_36429 = state_34797__$1;
(statearr_34905_36429[(1)] = (15));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (9))){
var inst_34780 = (state_34797[(2)]);
var inst_34781 = cljs.core.async.impl.protocols.closed_QMARK_(out);
var state_34797__$1 = (function (){var statearr_34906 = state_34797;
(statearr_34906[(15)] = inst_34780);

return statearr_34906;
})();
if(cljs.core.truth_(inst_34781)){
var statearr_34907_36430 = state_34797__$1;
(statearr_34907_36430[(1)] = (21));

} else {
var statearr_34908_36431 = state_34797__$1;
(statearr_34908_36431[(1)] = (22));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (5))){
var inst_34727 = cljs.core.async.close_BANG_(out);
var state_34797__$1 = state_34797;
var statearr_34909_36432 = state_34797__$1;
(statearr_34909_36432[(2)] = inst_34727);

(statearr_34909_36432[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (14))){
var inst_34753 = (state_34797[(7)]);
var inst_34756 = cljs.core.chunked_seq_QMARK_(inst_34753);
var state_34797__$1 = state_34797;
if(inst_34756){
var statearr_34910_36441 = state_34797__$1;
(statearr_34910_36441[(1)] = (17));

} else {
var statearr_34911_36443 = state_34797__$1;
(statearr_34911_36443[(1)] = (18));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (16))){
var inst_34776 = (state_34797[(2)]);
var state_34797__$1 = state_34797;
var statearr_34915_36446 = state_34797__$1;
(statearr_34915_36446[(2)] = inst_34776);

(statearr_34915_36446[(1)] = (12));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_34798 === (10))){
var inst_34737 = (state_34797[(9)]);
var inst_34739 = (state_34797[(10)]);
var inst_34745 = cljs.core._nth(inst_34737,inst_34739);
var state_34797__$1 = state_34797;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_34797__$1,(13),out,inst_34745);
} else {
if((state_val_34798 === (18))){
var inst_34753 = (state_34797[(7)]);
var inst_34766 = cljs.core.first(inst_34753);
var state_34797__$1 = state_34797;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_34797__$1,(20),out,inst_34766);
} else {
if((state_val_34798 === (8))){
var inst_34739 = (state_34797[(10)]);
var inst_34738 = (state_34797[(11)]);
var inst_34742 = (inst_34739 < inst_34738);
var inst_34743 = inst_34742;
var state_34797__$1 = state_34797;
if(cljs.core.truth_(inst_34743)){
var statearr_34926_36460 = state_34797__$1;
(statearr_34926_36460[(1)] = (10));

} else {
var statearr_34927_36462 = state_34797__$1;
(statearr_34927_36462[(1)] = (11));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
}
}
}
});
return (function() {
var cljs$core$async$mapcat_STAR__$_state_machine__30402__auto__ = null;
var cljs$core$async$mapcat_STAR__$_state_machine__30402__auto____0 = (function (){
var statearr_34928 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_34928[(0)] = cljs$core$async$mapcat_STAR__$_state_machine__30402__auto__);

(statearr_34928[(1)] = (1));

return statearr_34928;
});
var cljs$core$async$mapcat_STAR__$_state_machine__30402__auto____1 = (function (state_34797){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_34797);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e34930){var ex__30405__auto__ = e34930;
var statearr_34931_36471 = state_34797;
(statearr_34931_36471[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_34797[(4)]))){
var statearr_34932_36472 = state_34797;
(statearr_34932_36472[(1)] = cljs.core.first((state_34797[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__36473 = state_34797;
state_34797 = G__36473;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$mapcat_STAR__$_state_machine__30402__auto__ = function(state_34797){
switch(arguments.length){
case 0:
return cljs$core$async$mapcat_STAR__$_state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$mapcat_STAR__$_state_machine__30402__auto____1.call(this,state_34797);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$mapcat_STAR__$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$mapcat_STAR__$_state_machine__30402__auto____0;
cljs$core$async$mapcat_STAR__$_state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$mapcat_STAR__$_state_machine__30402__auto____1;
return cljs$core$async$mapcat_STAR__$_state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_34933 = f__31515__auto__();
(statearr_34933[(6)] = c__31514__auto__);

return statearr_34933;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));

return c__31514__auto__;
});
/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.mapcat_LT_ = (function cljs$core$async$mapcat_LT_(var_args){
var G__34938 = arguments.length;
switch (G__34938) {
case 2:
return cljs.core.async.mapcat_LT_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.mapcat_LT_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.mapcat_LT_.cljs$core$IFn$_invoke$arity$2 = (function (f,in$){
return cljs.core.async.mapcat_LT_.cljs$core$IFn$_invoke$arity$3(f,in$,null);
}));

(cljs.core.async.mapcat_LT_.cljs$core$IFn$_invoke$arity$3 = (function (f,in$,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
cljs.core.async.mapcat_STAR_(f,in$,out);

return out;
}));

(cljs.core.async.mapcat_LT_.cljs$lang$maxFixedArity = 3);

/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.mapcat_GT_ = (function cljs$core$async$mapcat_GT_(var_args){
var G__34955 = arguments.length;
switch (G__34955) {
case 2:
return cljs.core.async.mapcat_GT_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.mapcat_GT_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.mapcat_GT_.cljs$core$IFn$_invoke$arity$2 = (function (f,out){
return cljs.core.async.mapcat_GT_.cljs$core$IFn$_invoke$arity$3(f,out,null);
}));

(cljs.core.async.mapcat_GT_.cljs$core$IFn$_invoke$arity$3 = (function (f,out,buf_or_n){
var in$ = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
cljs.core.async.mapcat_STAR_(f,in$,out);

return in$;
}));

(cljs.core.async.mapcat_GT_.cljs$lang$maxFixedArity = 3);

/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.unique = (function cljs$core$async$unique(var_args){
var G__34969 = arguments.length;
switch (G__34969) {
case 1:
return cljs.core.async.unique.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.unique.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.unique.cljs$core$IFn$_invoke$arity$1 = (function (ch){
return cljs.core.async.unique.cljs$core$IFn$_invoke$arity$2(ch,null);
}));

(cljs.core.async.unique.cljs$core$IFn$_invoke$arity$2 = (function (ch,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var c__31514__auto___36510 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_35020){
var state_val_35021 = (state_35020[(1)]);
if((state_val_35021 === (7))){
var inst_35015 = (state_35020[(2)]);
var state_35020__$1 = state_35020;
var statearr_35030_36512 = state_35020__$1;
(statearr_35030_36512[(2)] = inst_35015);

(statearr_35030_36512[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35021 === (1))){
var inst_34994 = null;
var state_35020__$1 = (function (){var statearr_35032 = state_35020;
(statearr_35032[(7)] = inst_34994);

return statearr_35032;
})();
var statearr_35034_36513 = state_35020__$1;
(statearr_35034_36513[(2)] = null);

(statearr_35034_36513[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35021 === (4))){
var inst_34997 = (state_35020[(8)]);
var inst_34997__$1 = (state_35020[(2)]);
var inst_35000 = (inst_34997__$1 == null);
var inst_35001 = cljs.core.not(inst_35000);
var state_35020__$1 = (function (){var statearr_35041 = state_35020;
(statearr_35041[(8)] = inst_34997__$1);

return statearr_35041;
})();
if(inst_35001){
var statearr_35042_36514 = state_35020__$1;
(statearr_35042_36514[(1)] = (5));

} else {
var statearr_35043_36515 = state_35020__$1;
(statearr_35043_36515[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35021 === (6))){
var state_35020__$1 = state_35020;
var statearr_35044_36516 = state_35020__$1;
(statearr_35044_36516[(2)] = null);

(statearr_35044_36516[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35021 === (3))){
var inst_35017 = (state_35020[(2)]);
var inst_35018 = cljs.core.async.close_BANG_(out);
var state_35020__$1 = (function (){var statearr_35045 = state_35020;
(statearr_35045[(9)] = inst_35017);

return statearr_35045;
})();
return cljs.core.async.impl.ioc_helpers.return_chan(state_35020__$1,inst_35018);
} else {
if((state_val_35021 === (2))){
var state_35020__$1 = state_35020;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_35020__$1,(4),ch);
} else {
if((state_val_35021 === (11))){
var inst_34997 = (state_35020[(8)]);
var inst_35009 = (state_35020[(2)]);
var inst_34994 = inst_34997;
var state_35020__$1 = (function (){var statearr_35052 = state_35020;
(statearr_35052[(10)] = inst_35009);

(statearr_35052[(7)] = inst_34994);

return statearr_35052;
})();
var statearr_35053_36518 = state_35020__$1;
(statearr_35053_36518[(2)] = null);

(statearr_35053_36518[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35021 === (9))){
var inst_34997 = (state_35020[(8)]);
var state_35020__$1 = state_35020;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_35020__$1,(11),out,inst_34997);
} else {
if((state_val_35021 === (5))){
var inst_34997 = (state_35020[(8)]);
var inst_34994 = (state_35020[(7)]);
var inst_35004 = cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(inst_34997,inst_34994);
var state_35020__$1 = state_35020;
if(inst_35004){
var statearr_35061_36519 = state_35020__$1;
(statearr_35061_36519[(1)] = (8));

} else {
var statearr_35063_36521 = state_35020__$1;
(statearr_35063_36521[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35021 === (10))){
var inst_35012 = (state_35020[(2)]);
var state_35020__$1 = state_35020;
var statearr_35064_36522 = state_35020__$1;
(statearr_35064_36522[(2)] = inst_35012);

(statearr_35064_36522[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35021 === (8))){
var inst_34994 = (state_35020[(7)]);
var tmp35057 = inst_34994;
var inst_34994__$1 = tmp35057;
var state_35020__$1 = (function (){var statearr_35067 = state_35020;
(statearr_35067[(7)] = inst_34994__$1);

return statearr_35067;
})();
var statearr_35068_36524 = state_35020__$1;
(statearr_35068_36524[(2)] = null);

(statearr_35068_36524[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
}
});
return (function() {
var cljs$core$async$state_machine__30402__auto__ = null;
var cljs$core$async$state_machine__30402__auto____0 = (function (){
var statearr_35069 = [null,null,null,null,null,null,null,null,null,null,null];
(statearr_35069[(0)] = cljs$core$async$state_machine__30402__auto__);

(statearr_35069[(1)] = (1));

return statearr_35069;
});
var cljs$core$async$state_machine__30402__auto____1 = (function (state_35020){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_35020);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e35070){var ex__30405__auto__ = e35070;
var statearr_35071_36527 = state_35020;
(statearr_35071_36527[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_35020[(4)]))){
var statearr_35073_36528 = state_35020;
(statearr_35073_36528[(1)] = cljs.core.first((state_35020[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__36529 = state_35020;
state_35020 = G__36529;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$state_machine__30402__auto__ = function(state_35020){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__30402__auto____1.call(this,state_35020);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__30402__auto____0;
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__30402__auto____1;
return cljs$core$async$state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_35077 = f__31515__auto__();
(statearr_35077[(6)] = c__31514__auto___36510);

return statearr_35077;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));


return out;
}));

(cljs.core.async.unique.cljs$lang$maxFixedArity = 2);

/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.partition = (function cljs$core$async$partition(var_args){
var G__35091 = arguments.length;
switch (G__35091) {
case 2:
return cljs.core.async.partition.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.partition.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.partition.cljs$core$IFn$_invoke$arity$2 = (function (n,ch){
return cljs.core.async.partition.cljs$core$IFn$_invoke$arity$3(n,ch,null);
}));

(cljs.core.async.partition.cljs$core$IFn$_invoke$arity$3 = (function (n,ch,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var c__31514__auto___36547 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_35137){
var state_val_35138 = (state_35137[(1)]);
if((state_val_35138 === (7))){
var inst_35132 = (state_35137[(2)]);
var state_35137__$1 = state_35137;
var statearr_35148_36550 = state_35137__$1;
(statearr_35148_36550[(2)] = inst_35132);

(statearr_35148_36550[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35138 === (1))){
var inst_35092 = (new Array(n));
var inst_35093 = inst_35092;
var inst_35094 = (0);
var state_35137__$1 = (function (){var statearr_35149 = state_35137;
(statearr_35149[(7)] = inst_35093);

(statearr_35149[(8)] = inst_35094);

return statearr_35149;
})();
var statearr_35150_36551 = state_35137__$1;
(statearr_35150_36551[(2)] = null);

(statearr_35150_36551[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35138 === (4))){
var inst_35104 = (state_35137[(9)]);
var inst_35104__$1 = (state_35137[(2)]);
var inst_35105 = (inst_35104__$1 == null);
var inst_35106 = cljs.core.not(inst_35105);
var state_35137__$1 = (function (){var statearr_35151 = state_35137;
(statearr_35151[(9)] = inst_35104__$1);

return statearr_35151;
})();
if(inst_35106){
var statearr_35152_36552 = state_35137__$1;
(statearr_35152_36552[(1)] = (5));

} else {
var statearr_35153_36554 = state_35137__$1;
(statearr_35153_36554[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35138 === (15))){
var inst_35126 = (state_35137[(2)]);
var state_35137__$1 = state_35137;
var statearr_35157_36555 = state_35137__$1;
(statearr_35157_36555[(2)] = inst_35126);

(statearr_35157_36555[(1)] = (14));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35138 === (13))){
var state_35137__$1 = state_35137;
var statearr_35159_36556 = state_35137__$1;
(statearr_35159_36556[(2)] = null);

(statearr_35159_36556[(1)] = (14));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35138 === (6))){
var inst_35094 = (state_35137[(8)]);
var inst_35122 = (inst_35094 > (0));
var state_35137__$1 = state_35137;
if(cljs.core.truth_(inst_35122)){
var statearr_35160_36558 = state_35137__$1;
(statearr_35160_36558[(1)] = (12));

} else {
var statearr_35161_36559 = state_35137__$1;
(statearr_35161_36559[(1)] = (13));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35138 === (3))){
var inst_35135 = (state_35137[(2)]);
var state_35137__$1 = state_35137;
return cljs.core.async.impl.ioc_helpers.return_chan(state_35137__$1,inst_35135);
} else {
if((state_val_35138 === (12))){
var inst_35093 = (state_35137[(7)]);
var inst_35124 = cljs.core.vec(inst_35093);
var state_35137__$1 = state_35137;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_35137__$1,(15),out,inst_35124);
} else {
if((state_val_35138 === (2))){
var state_35137__$1 = state_35137;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_35137__$1,(4),ch);
} else {
if((state_val_35138 === (11))){
var inst_35116 = (state_35137[(2)]);
var inst_35117 = (new Array(n));
var inst_35093 = inst_35117;
var inst_35094 = (0);
var state_35137__$1 = (function (){var statearr_35169 = state_35137;
(statearr_35169[(7)] = inst_35093);

(statearr_35169[(10)] = inst_35116);

(statearr_35169[(8)] = inst_35094);

return statearr_35169;
})();
var statearr_35170_36570 = state_35137__$1;
(statearr_35170_36570[(2)] = null);

(statearr_35170_36570[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35138 === (9))){
var inst_35093 = (state_35137[(7)]);
var inst_35114 = cljs.core.vec(inst_35093);
var state_35137__$1 = state_35137;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_35137__$1,(11),out,inst_35114);
} else {
if((state_val_35138 === (5))){
var inst_35109 = (state_35137[(11)]);
var inst_35104 = (state_35137[(9)]);
var inst_35093 = (state_35137[(7)]);
var inst_35094 = (state_35137[(8)]);
var inst_35108 = (inst_35093[inst_35094] = inst_35104);
var inst_35109__$1 = (inst_35094 + (1));
var inst_35110 = (inst_35109__$1 < n);
var state_35137__$1 = (function (){var statearr_35175 = state_35137;
(statearr_35175[(11)] = inst_35109__$1);

(statearr_35175[(12)] = inst_35108);

return statearr_35175;
})();
if(cljs.core.truth_(inst_35110)){
var statearr_35176_36573 = state_35137__$1;
(statearr_35176_36573[(1)] = (8));

} else {
var statearr_35177_36574 = state_35137__$1;
(statearr_35177_36574[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35138 === (14))){
var inst_35129 = (state_35137[(2)]);
var inst_35130 = cljs.core.async.close_BANG_(out);
var state_35137__$1 = (function (){var statearr_35179 = state_35137;
(statearr_35179[(13)] = inst_35129);

return statearr_35179;
})();
var statearr_35180_36575 = state_35137__$1;
(statearr_35180_36575[(2)] = inst_35130);

(statearr_35180_36575[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35138 === (10))){
var inst_35120 = (state_35137[(2)]);
var state_35137__$1 = state_35137;
var statearr_35181_36579 = state_35137__$1;
(statearr_35181_36579[(2)] = inst_35120);

(statearr_35181_36579[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35138 === (8))){
var inst_35109 = (state_35137[(11)]);
var inst_35093 = (state_35137[(7)]);
var tmp35178 = inst_35093;
var inst_35093__$1 = tmp35178;
var inst_35094 = inst_35109;
var state_35137__$1 = (function (){var statearr_35182 = state_35137;
(statearr_35182[(7)] = inst_35093__$1);

(statearr_35182[(8)] = inst_35094);

return statearr_35182;
})();
var statearr_35183_36581 = state_35137__$1;
(statearr_35183_36581[(2)] = null);

(statearr_35183_36581[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__30402__auto__ = null;
var cljs$core$async$state_machine__30402__auto____0 = (function (){
var statearr_35189 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_35189[(0)] = cljs$core$async$state_machine__30402__auto__);

(statearr_35189[(1)] = (1));

return statearr_35189;
});
var cljs$core$async$state_machine__30402__auto____1 = (function (state_35137){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_35137);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e35193){var ex__30405__auto__ = e35193;
var statearr_35194_36582 = state_35137;
(statearr_35194_36582[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_35137[(4)]))){
var statearr_35197_36583 = state_35137;
(statearr_35197_36583[(1)] = cljs.core.first((state_35137[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__36584 = state_35137;
state_35137 = G__36584;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$state_machine__30402__auto__ = function(state_35137){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__30402__auto____1.call(this,state_35137);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__30402__auto____0;
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__30402__auto____1;
return cljs$core$async$state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_35198 = f__31515__auto__();
(statearr_35198[(6)] = c__31514__auto___36547);

return statearr_35198;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));


return out;
}));

(cljs.core.async.partition.cljs$lang$maxFixedArity = 3);

/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.partition_by = (function cljs$core$async$partition_by(var_args){
var G__35207 = arguments.length;
switch (G__35207) {
case 2:
return cljs.core.async.partition_by.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.partition_by.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",cljs.core.str.cljs$core$IFn$_invoke$arity$1(arguments.length)].join('')));

}
});

(cljs.core.async.partition_by.cljs$core$IFn$_invoke$arity$2 = (function (f,ch){
return cljs.core.async.partition_by.cljs$core$IFn$_invoke$arity$3(f,ch,null);
}));

(cljs.core.async.partition_by.cljs$core$IFn$_invoke$arity$3 = (function (f,ch,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var c__31514__auto___36587 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__31515__auto__ = (function (){var switch__30401__auto__ = (function (state_35262){
var state_val_35263 = (state_35262[(1)]);
if((state_val_35263 === (7))){
var inst_35258 = (state_35262[(2)]);
var state_35262__$1 = state_35262;
var statearr_35265_36588 = state_35262__$1;
(statearr_35265_36588[(2)] = inst_35258);

(statearr_35265_36588[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35263 === (1))){
var inst_35216 = [];
var inst_35217 = inst_35216;
var inst_35218 = new cljs.core.Keyword("cljs.core.async","nothing","cljs.core.async/nothing",-69252123);
var state_35262__$1 = (function (){var statearr_35273 = state_35262;
(statearr_35273[(7)] = inst_35217);

(statearr_35273[(8)] = inst_35218);

return statearr_35273;
})();
var statearr_35277_36589 = state_35262__$1;
(statearr_35277_36589[(2)] = null);

(statearr_35277_36589[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35263 === (4))){
var inst_35221 = (state_35262[(9)]);
var inst_35221__$1 = (state_35262[(2)]);
var inst_35222 = (inst_35221__$1 == null);
var inst_35223 = cljs.core.not(inst_35222);
var state_35262__$1 = (function (){var statearr_35280 = state_35262;
(statearr_35280[(9)] = inst_35221__$1);

return statearr_35280;
})();
if(inst_35223){
var statearr_35281_36591 = state_35262__$1;
(statearr_35281_36591[(1)] = (5));

} else {
var statearr_35283_36592 = state_35262__$1;
(statearr_35283_36592[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35263 === (15))){
var inst_35217 = (state_35262[(7)]);
var inst_35250 = cljs.core.vec(inst_35217);
var state_35262__$1 = state_35262;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_35262__$1,(18),out,inst_35250);
} else {
if((state_val_35263 === (13))){
var inst_35245 = (state_35262[(2)]);
var state_35262__$1 = state_35262;
var statearr_35285_36594 = state_35262__$1;
(statearr_35285_36594[(2)] = inst_35245);

(statearr_35285_36594[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35263 === (6))){
var inst_35217 = (state_35262[(7)]);
var inst_35247 = inst_35217.length;
var inst_35248 = (inst_35247 > (0));
var state_35262__$1 = state_35262;
if(cljs.core.truth_(inst_35248)){
var statearr_35286_36595 = state_35262__$1;
(statearr_35286_36595[(1)] = (15));

} else {
var statearr_35287_36596 = state_35262__$1;
(statearr_35287_36596[(1)] = (16));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35263 === (17))){
var inst_35255 = (state_35262[(2)]);
var inst_35256 = cljs.core.async.close_BANG_(out);
var state_35262__$1 = (function (){var statearr_35288 = state_35262;
(statearr_35288[(10)] = inst_35255);

return statearr_35288;
})();
var statearr_35289_36599 = state_35262__$1;
(statearr_35289_36599[(2)] = inst_35256);

(statearr_35289_36599[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35263 === (3))){
var inst_35260 = (state_35262[(2)]);
var state_35262__$1 = state_35262;
return cljs.core.async.impl.ioc_helpers.return_chan(state_35262__$1,inst_35260);
} else {
if((state_val_35263 === (12))){
var inst_35217 = (state_35262[(7)]);
var inst_35238 = cljs.core.vec(inst_35217);
var state_35262__$1 = state_35262;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_35262__$1,(14),out,inst_35238);
} else {
if((state_val_35263 === (2))){
var state_35262__$1 = state_35262;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_35262__$1,(4),ch);
} else {
if((state_val_35263 === (11))){
var inst_35217 = (state_35262[(7)]);
var inst_35221 = (state_35262[(9)]);
var inst_35226 = (state_35262[(11)]);
var inst_35235 = inst_35217.push(inst_35221);
var tmp35294 = inst_35217;
var inst_35217__$1 = tmp35294;
var inst_35218 = inst_35226;
var state_35262__$1 = (function (){var statearr_35297 = state_35262;
(statearr_35297[(7)] = inst_35217__$1);

(statearr_35297[(12)] = inst_35235);

(statearr_35297[(8)] = inst_35218);

return statearr_35297;
})();
var statearr_35298_36604 = state_35262__$1;
(statearr_35298_36604[(2)] = null);

(statearr_35298_36604[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35263 === (9))){
var inst_35218 = (state_35262[(8)]);
var inst_35231 = cljs.core.keyword_identical_QMARK_(inst_35218,new cljs.core.Keyword("cljs.core.async","nothing","cljs.core.async/nothing",-69252123));
var state_35262__$1 = state_35262;
var statearr_35301_36607 = state_35262__$1;
(statearr_35301_36607[(2)] = inst_35231);

(statearr_35301_36607[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35263 === (5))){
var inst_35221 = (state_35262[(9)]);
var inst_35218 = (state_35262[(8)]);
var inst_35226 = (state_35262[(11)]);
var inst_35228 = (state_35262[(13)]);
var inst_35226__$1 = (f.cljs$core$IFn$_invoke$arity$1 ? f.cljs$core$IFn$_invoke$arity$1(inst_35221) : f.call(null, inst_35221));
var inst_35228__$1 = cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(inst_35226__$1,inst_35218);
var state_35262__$1 = (function (){var statearr_35302 = state_35262;
(statearr_35302[(11)] = inst_35226__$1);

(statearr_35302[(13)] = inst_35228__$1);

return statearr_35302;
})();
if(inst_35228__$1){
var statearr_35306_36619 = state_35262__$1;
(statearr_35306_36619[(1)] = (8));

} else {
var statearr_35307_36622 = state_35262__$1;
(statearr_35307_36622[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35263 === (14))){
var inst_35221 = (state_35262[(9)]);
var inst_35226 = (state_35262[(11)]);
var inst_35240 = (state_35262[(2)]);
var inst_35241 = [];
var inst_35242 = inst_35241.push(inst_35221);
var inst_35217 = inst_35241;
var inst_35218 = inst_35226;
var state_35262__$1 = (function (){var statearr_35308 = state_35262;
(statearr_35308[(7)] = inst_35217);

(statearr_35308[(14)] = inst_35242);

(statearr_35308[(8)] = inst_35218);

(statearr_35308[(15)] = inst_35240);

return statearr_35308;
})();
var statearr_35309_36624 = state_35262__$1;
(statearr_35309_36624[(2)] = null);

(statearr_35309_36624[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35263 === (16))){
var state_35262__$1 = state_35262;
var statearr_35311_36625 = state_35262__$1;
(statearr_35311_36625[(2)] = null);

(statearr_35311_36625[(1)] = (17));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35263 === (10))){
var inst_35233 = (state_35262[(2)]);
var state_35262__$1 = state_35262;
if(cljs.core.truth_(inst_35233)){
var statearr_35312_36634 = state_35262__$1;
(statearr_35312_36634[(1)] = (11));

} else {
var statearr_35313_36635 = state_35262__$1;
(statearr_35313_36635[(1)] = (12));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35263 === (18))){
var inst_35252 = (state_35262[(2)]);
var state_35262__$1 = state_35262;
var statearr_35315_36639 = state_35262__$1;
(statearr_35315_36639[(2)] = inst_35252);

(statearr_35315_36639[(1)] = (17));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_35263 === (8))){
var inst_35228 = (state_35262[(13)]);
var state_35262__$1 = state_35262;
var statearr_35317_36641 = state_35262__$1;
(statearr_35317_36641[(2)] = inst_35228);

(statearr_35317_36641[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
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
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__30402__auto__ = null;
var cljs$core$async$state_machine__30402__auto____0 = (function (){
var statearr_35319 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_35319[(0)] = cljs$core$async$state_machine__30402__auto__);

(statearr_35319[(1)] = (1));

return statearr_35319;
});
var cljs$core$async$state_machine__30402__auto____1 = (function (state_35262){
while(true){
var ret_value__30403__auto__ = (function (){try{while(true){
var result__30404__auto__ = switch__30401__auto__(state_35262);
if(cljs.core.keyword_identical_QMARK_(result__30404__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__30404__auto__;
}
break;
}
}catch (e35321){var ex__30405__auto__ = e35321;
var statearr_35322_36645 = state_35262;
(statearr_35322_36645[(2)] = ex__30405__auto__);


if(cljs.core.seq((state_35262[(4)]))){
var statearr_35323_36646 = state_35262;
(statearr_35323_36646[(1)] = cljs.core.first((state_35262[(4)])));

} else {
throw ex__30405__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__30403__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__36647 = state_35262;
state_35262 = G__36647;
continue;
} else {
return ret_value__30403__auto__;
}
break;
}
});
cljs$core$async$state_machine__30402__auto__ = function(state_35262){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__30402__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__30402__auto____1.call(this,state_35262);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__30402__auto____0;
cljs$core$async$state_machine__30402__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__30402__auto____1;
return cljs$core$async$state_machine__30402__auto__;
})()
})();
var state__31516__auto__ = (function (){var statearr_35324 = f__31515__auto__();
(statearr_35324[(6)] = c__31514__auto___36587);

return statearr_35324;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__31516__auto__);
}));


return out;
}));

(cljs.core.async.partition_by.cljs$lang$maxFixedArity = 3);


//# sourceMappingURL=cljs.core.async.js.map
