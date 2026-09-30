goog.provide('grog_web.voice');
if((typeof grog_web !== 'undefined') && (typeof grog_web.voice !== 'undefined') && (typeof grog_web.voice.cap !== 'undefined')){
} else {
grog_web.voice.cap = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(null);
}
grog_web.voice.target_rate = (16000);
/**
 * True when the runtime exposes getUserMedia (an Electron/Chromium renderer
 *   normally does; a plain sandboxed page may not).
 */
grog_web.voice.supported_QMARK_ = (function grog_web$voice$supported_QMARK_(){
return cljs.core.boolean$((function (){var and__5000__auto__ = (typeof navigator !== 'undefined');
if(and__5000__auto__){
return navigator.mediaDevices.getUserMedia;
} else {
return and__5000__auto__;
}
})());
});
/**
 * Clamp Float32 samples in [-1,1] to signed 16-bit.
 */
grog_web.voice.f32__GT_i16 = (function grog_web$voice$f32__GT_i16(f32){
var n = f32.length;
var out = (new Int16Array(n));
var n__5593__auto___20358 = n;
var i_20360 = (0);
while(true){
if((i_20360 < n__5593__auto___20358)){
var s_20362 = Math.max(-1.0,Math.min(1.0,(f32[i_20360])));
(out[i_20360] = Math.round((s_20362 * 32767.0)));

var G__20367 = (i_20360 + (1));
i_20360 = G__20367;
continue;
} else {
}
break;
}

return out;
});
/**
 * Linear resample mono Float32 from `src` Hz to `dst` Hz (identity if equal).
 */
grog_web.voice.resample = (function grog_web$voice$resample(f32,src,dst){
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(src,dst)){
return f32;
} else {
var ratio = (src / dst);
var n = Math.max((1),Math.floor((f32.length / ratio)));
var out = (new Float32Array(n));
var last_i = (f32.length - (1));
var n__5593__auto___20372 = n;
var i_20373 = (0);
while(true){
if((i_20373 < n__5593__auto___20372)){
var pos_20374 = (i_20373 * ratio);
var i0_20375 = Math.floor(pos_20374);
var i1_20376 = Math.min(Math.floor((pos_20374 + (1))),last_i);
var frac_20377 = (pos_20374 - i0_20375);
(out[i_20373] = (((f32[i0_20375]) * (1.0 - frac_20377)) + ((f32[i1_20376]) * frac_20377)));

var G__20382 = (i_20373 + (1));
i_20373 = G__20382;
continue;
} else {
}
break;
}

return out;
}
});
/**
 * Concatenate a seq of Float32Arrays into one.
 */
grog_web.voice.concat_f32 = (function grog_web$voice$concat_f32(chunks){
var total = cljs.core.reduce.cljs$core$IFn$_invoke$arity$3((function (a,c){
return (a + c.length);
}),(0),chunks);
var out = (new Float32Array(total));
var i_20388 = (0);
var cs_20389 = chunks;
while(true){
if(cljs.core.seq(cs_20389)){
var c_20390 = cljs.core.first(cs_20389);
out.set(c_20390,i_20388);

var G__20393 = (i_20388 + c_20390.length);
var G__20394 = cljs.core.rest(cs_20389);
i_20388 = G__20393;
cs_20389 = G__20394;
continue;
} else {
}
break;
}

return out;
});
/**
 * Write ASCII bytes of `s` into DataView `dv` at `off`.
 */
grog_web.voice.put_str_BANG_ = (function grog_web$voice$put_str_BANG_(dv,off,s){
var n__5593__auto__ = ((s).length);
var i = (0);
while(true){
if((i < n__5593__auto__)){
dv.setUint8((off + i),s.charCodeAt(i));

var G__20396 = (i + (1));
i = G__20396;
continue;
} else {
return null;
}
break;
}
});
/**
 * 16-bit PCM mono WAV of Float32 `f32` at `rate`. Returns a Uint8Array.
 */
grog_web.voice.encode_wav = (function grog_web$voice$encode_wav(f32,rate){
var samples = grog_web.voice.f32__GT_i16(f32);
var n = samples.length;
var buf = (new ArrayBuffer(((44) + (n * (2)))));
var dv = (new DataView(buf));
grog_web.voice.put_str_BANG_(dv,(0),"RIFF");

dv.setUint32((4),((36) + (n * (2))),true);

grog_web.voice.put_str_BANG_(dv,(8),"WAVE");

grog_web.voice.put_str_BANG_(dv,(12),"fmt ");

dv.setUint32((16),(16),true);

dv.setUint16((20),(1),true);

dv.setUint16((22),(1),true);

dv.setUint32((24),rate,true);

dv.setUint32((28),(rate * (2)),true);

dv.setUint16((32),(2),true);

dv.setUint16((34),(16),true);

grog_web.voice.put_str_BANG_(dv,(36),"data");

dv.setUint32((40),(n * (2)),true);

var n__5593__auto___20405 = n;
var i_20406 = (0);
while(true){
if((i_20406 < n__5593__auto___20405)){
dv.setInt16(((44) + (i_20406 * (2))),(samples[i_20406]),true);

var G__20409 = (i_20406 + (1));
i_20406 = G__20409;
continue;
} else {
}
break;
}

return (new Uint8Array(buf));
});
/**
 * Open the mic and begin buffering audio. Returns a Promise<handle>. Throws /
 *   rejects if the mic is unavailable or permission is denied.
 */
grog_web.voice.start_BANG_ = (function grog_web$voice$start_BANG_(){
if((!(grog_web.voice.supported_QMARK_()))){
return Promise.reject((new Error("microphone API unavailable")));
} else {
return navigator.mediaDevices.getUserMedia(cljs.core.clj__GT_js(new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"audio","audio",1819127321),new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword(null,"channelCount","channelCount",989969849),(1),new cljs.core.Keyword(null,"echoCancellation","echoCancellation",-1009754626),true,new cljs.core.Keyword(null,"noiseSuppression","noiseSuppression",1470931726),true,new cljs.core.Keyword(null,"autoGainControl","autoGainControl",703085489),true], null)], null))).then((function (stream){
var ctx = (new AudioContext(cljs.core.clj__GT_js(new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"sampleRate","sampleRate",-541273751),grog_web.voice.target_rate], null))));
var src = ctx.createMediaStreamSource(stream);
var proc = ctx.createScriptProcessor((4096),(1),(1));
var mute = ctx.createGain();
var chunks = (new Array());
(mute.gain.value = 0.0);

(proc.onaudioprocess = (function (e){
var ch = e.inputBuffer.getChannelData((0));
return chunks.push((new Float32Array(ch)));
}));

src.connect(proc);

proc.connect(mute);

mute.connect(ctx.destination);

cljs.core.reset_BANG_(grog_web.voice.cap,new cljs.core.PersistentArrayMap(null, 7, [new cljs.core.Keyword(null,"stream","stream",1534941648),stream,new cljs.core.Keyword(null,"ctx","ctx",-493610118),ctx,new cljs.core.Keyword(null,"src","src",-1651076051),src,new cljs.core.Keyword(null,"proc","proc",2011328965),proc,new cljs.core.Keyword(null,"mute","mute",1151223646),mute,new cljs.core.Keyword(null,"chunks","chunks",83720431),chunks,new cljs.core.Keyword(null,"rate","rate",-1428659698),ctx.sampleRate], null));

return null;
}));
}
});
grog_web.voice.release_BANG_ = (function grog_web$voice$release_BANG_(p__20192){
var map__20211 = p__20192;
var map__20211__$1 = cljs.core.__destructure_map(map__20211);
var _h = map__20211__$1;
var stream = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20211__$1,new cljs.core.Keyword(null,"stream","stream",1534941648));
var ctx = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20211__$1,new cljs.core.Keyword(null,"ctx","ctx",-493610118));
var src = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20211__$1,new cljs.core.Keyword(null,"src","src",-1651076051));
var proc = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20211__$1,new cljs.core.Keyword(null,"proc","proc",2011328965));
var mute = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20211__$1,new cljs.core.Keyword(null,"mute","mute",1151223646));
if(cljs.core.truth_(proc)){
(proc.onaudioprocess = null);
} else {
}

var seq__20238_20410 = cljs.core.seq(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [src,proc,mute], null));
var chunk__20239_20411 = null;
var count__20240_20412 = (0);
var i__20241_20413 = (0);
while(true){
if((i__20241_20413 < count__20240_20412)){
var node_20417 = chunk__20239_20411.cljs$core$IIndexed$_nth$arity$2(null, i__20241_20413);
try{node_20417.disconnect();
}catch (e20288){var __20420 = e20288;
}

var G__20421 = seq__20238_20410;
var G__20422 = chunk__20239_20411;
var G__20423 = count__20240_20412;
var G__20424 = (i__20241_20413 + (1));
seq__20238_20410 = G__20421;
chunk__20239_20411 = G__20422;
count__20240_20412 = G__20423;
i__20241_20413 = G__20424;
continue;
} else {
var temp__5823__auto___20426 = cljs.core.seq(seq__20238_20410);
if(temp__5823__auto___20426){
var seq__20238_20427__$1 = temp__5823__auto___20426;
if(cljs.core.chunked_seq_QMARK_(seq__20238_20427__$1)){
var c__5525__auto___20429 = cljs.core.chunk_first(seq__20238_20427__$1);
var G__20431 = cljs.core.chunk_rest(seq__20238_20427__$1);
var G__20432 = c__5525__auto___20429;
var G__20433 = cljs.core.count(c__5525__auto___20429);
var G__20434 = (0);
seq__20238_20410 = G__20431;
chunk__20239_20411 = G__20432;
count__20240_20412 = G__20433;
i__20241_20413 = G__20434;
continue;
} else {
var node_20435 = cljs.core.first(seq__20238_20427__$1);
try{node_20435.disconnect();
}catch (e20290){var __20436 = e20290;
}

var G__20438 = cljs.core.next(seq__20238_20427__$1);
var G__20439 = null;
var G__20440 = (0);
var G__20441 = (0);
seq__20238_20410 = G__20438;
chunk__20239_20411 = G__20439;
count__20240_20412 = G__20440;
i__20241_20413 = G__20441;
continue;
}
} else {
}
}
break;
}

if(cljs.core.truth_(ctx)){
try{ctx.close();
}catch (e20291){var __20443 = e20291;
}} else {
}

if(cljs.core.truth_(stream)){
var seq__20295 = cljs.core.seq(stream.getTracks());
var chunk__20296 = null;
var count__20297 = (0);
var i__20298 = (0);
while(true){
if((i__20298 < count__20297)){
var t = chunk__20296.cljs$core$IIndexed$_nth$arity$2(null, i__20298);
try{t.stop();
}catch (e20313){var __20448 = e20313;
}

var G__20452 = seq__20295;
var G__20453 = chunk__20296;
var G__20454 = count__20297;
var G__20455 = (i__20298 + (1));
seq__20295 = G__20452;
chunk__20296 = G__20453;
count__20297 = G__20454;
i__20298 = G__20455;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__20295);
if(temp__5823__auto__){
var seq__20295__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__20295__$1)){
var c__5525__auto__ = cljs.core.chunk_first(seq__20295__$1);
var G__20461 = cljs.core.chunk_rest(seq__20295__$1);
var G__20462 = c__5525__auto__;
var G__20463 = cljs.core.count(c__5525__auto__);
var G__20464 = (0);
seq__20295 = G__20461;
chunk__20296 = G__20462;
count__20297 = G__20463;
i__20298 = G__20464;
continue;
} else {
var t = cljs.core.first(seq__20295__$1);
try{t.stop();
}catch (e20315){var __20465 = e20315;
}

var G__20466 = cljs.core.next(seq__20295__$1);
var G__20467 = null;
var G__20468 = (0);
var G__20469 = (0);
seq__20295 = G__20466;
chunk__20296 = G__20467;
count__20297 = G__20468;
i__20298 = G__20469;
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
/**
 * Stop capturing, release the mic, and return a Promise resolving to
 *   `{:wav <Uint8Array> :seconds <number>}` (empty wav when nothing was captured).
 */
grog_web.voice.stop_BANG_ = (function grog_web$voice$stop_BANG_(){
var map__20324 = cljs.core.deref(grog_web.voice.cap);
var map__20324__$1 = cljs.core.__destructure_map(map__20324);
var h = map__20324__$1;
var rate = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20324__$1,new cljs.core.Keyword(null,"rate","rate",-1428659698));
var chunks = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20324__$1,new cljs.core.Keyword(null,"chunks","chunks",83720431));
cljs.core.reset_BANG_(grog_web.voice.cap,null);

if(cljs.core.truth_(h)){
grog_web.voice.release_BANG_(h);
} else {
}

var raw = grog_web.voice.concat_f32((function (){var or__5002__auto__ = (function (){var G__20326 = chunks;
if((G__20326 == null)){
return null;
} else {
return cljs.core.vec(G__20326);
}
})();
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return cljs.core.PersistentVector.EMPTY;
}
})());
var res = grog_web.voice.resample(raw,(function (){var or__5002__auto__ = rate;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return grog_web.voice.target_rate;
}
})(),grog_web.voice.target_rate);
var wav = grog_web.voice.encode_wav(res,grog_web.voice.target_rate);
return Promise.resolve(new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"wav","wav",270623362),wav,new cljs.core.Keyword(null,"seconds","seconds",-445266194),(res.length / grog_web.voice.target_rate)], null));
});
/**
 * Abort capture without producing a WAV.
 */
grog_web.voice.cancel_BANG_ = (function grog_web$voice$cancel_BANG_(){
var temp__5823__auto__ = cljs.core.deref(grog_web.voice.cap);
if(cljs.core.truth_(temp__5823__auto__)){
var h = temp__5823__auto__;
cljs.core.reset_BANG_(grog_web.voice.cap,null);

return grog_web.voice.release_BANG_(h);
} else {
return null;
}
});
/**
 * True while a capture is live.
 */
grog_web.voice.recording_QMARK_ = (function grog_web$voice$recording_QMARK_(){
return (!((cljs.core.deref(grog_web.voice.cap) == null)));
});

//# sourceMappingURL=grog_web.voice.js.map
