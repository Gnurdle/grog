goog.provide('grog_web.md');
/**
 * Drop the MIME-style <text/markdown>…</text/markdown> regions (and the legacy
 *   <text-markdown> forms) so the content parses as plain Markdown.
 */
grog_web.md.strip_wrappers = (function grog_web$md$strip_wrappers(s){
return clojure.string.replace((function (){var or__5002__auto__ = s;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return "";
}
})(),/<\/?text[-\/]markdown\/?>/is,"");
});
grog_web.md.alnum_QMARK_ = (function grog_web$md$alnum_QMARK_(c){
return cljs.core.boolean$((function (){var and__5000__auto__ = c;
if(cljs.core.truth_(and__5000__auto__)){
return cljs.core.re_matches(/[A-Za-z0-9]/,cljs.core.str.cljs$core$IFn$_invoke$arity$1(c));
} else {
return and__5000__auto__;
}
})());
});
/**
 * Build a [:code …] from the raw span body: newlines become spaces, and one
 *   leading+trailing space is trimmed when both are present (CommonMark rule).
 */
grog_web.md.code_span = (function grog_web$md$code_span(t){
var t__$1 = clojure.string.replace(t,"\n"," ");
var t__$2 = (((((((t__$1).length) >= (2))) && (((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(" ",cljs.core.first(t__$1))) && (cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(" ",cljs.core.last(t__$1)))))))?cljs.core.subs.cljs$core$IFn$_invoke$arity$3(t__$1,(1),(((t__$1).length) - (1))):t__$1);
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"code","code",1586293142),t__$2], null);
});
/**
 * If `s` at index `i` starts an inline construct, return [node next-index];
 *   else nil. Node is hiccup or a raw string (for escapes).
 */
grog_web.md.inline_token = (function grog_web$md$inline_token(s,i){
var n = cljs.core.count(s);
var two = ((((i + (2)) <= n))?cljs.core.subs.cljs$core$IFn$_invoke$arity$3(s,i,(i + (2))):null);
var one = (((i < n))?cljs.core.subs.cljs$core$IFn$_invoke$arity$3(s,i,(i + (1))):null);
var space_QMARK_ = (function (k){
return (((k < n)) && (cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(" ",cljs.core.nth.cljs$core$IFn$_invoke$arity$2(s,k))));
});
if(((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(one,"\\")) && (((i + (1)) < n)))){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.core.subs.cljs$core$IFn$_invoke$arity$3(s,(i + (1)),(i + (2))),(i + (2))], null);
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(two,"**")){
var temp__5823__auto__ = clojure.string.index_of.cljs$core$IFn$_invoke$arity$3(s,"**",(i + (2)));
if(cljs.core.truth_(temp__5823__auto__)){
var j = temp__5823__auto__;
if((((j > (i + (2)))) && ((((!(space_QMARK_((i + (1)))))) && ((!(space_QMARK_((j - (1)))))))))){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"strong","strong",269529000)], null),(function (){var G__20176 = cljs.core.subs.cljs$core$IFn$_invoke$arity$3(s,(i + (2)),j);
return (grog_web.md.inline.cljs$core$IFn$_invoke$arity$1 ? grog_web.md.inline.cljs$core$IFn$_invoke$arity$1(G__20176) : grog_web.md.inline.call(null, G__20176));
})()),(j + (2))], null);
} else {
return null;
}
} else {
return null;
}
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(two,"__")){
var temp__5823__auto__ = clojure.string.index_of.cljs$core$IFn$_invoke$arity$3(s,"__",(i + (2)));
if(cljs.core.truth_(temp__5823__auto__)){
var j = temp__5823__auto__;
if((((j > (i + (2)))) && ((((!(space_QMARK_((i + (1)))))) && ((!(space_QMARK_((j - (1)))))))))){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"strong","strong",269529000)], null),(function (){var G__20190 = cljs.core.subs.cljs$core$IFn$_invoke$arity$3(s,(i + (2)),j);
return (grog_web.md.inline.cljs$core$IFn$_invoke$arity$1 ? grog_web.md.inline.cljs$core$IFn$_invoke$arity$1(G__20190) : grog_web.md.inline.call(null, G__20190));
})()),(j + (2))], null);
} else {
return null;
}
} else {
return null;
}
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(two,"~~")){
var temp__5823__auto__ = clojure.string.index_of.cljs$core$IFn$_invoke$arity$3(s,"~~",(i + (2)));
if(cljs.core.truth_(temp__5823__auto__)){
var j = temp__5823__auto__;
if((j > (i + (2)))){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"del","del",574975584)], null),(function (){var G__20202 = cljs.core.subs.cljs$core$IFn$_invoke$arity$3(s,(i + (2)),j);
return (grog_web.md.inline.cljs$core$IFn$_invoke$arity$1 ? grog_web.md.inline.cljs$core$IFn$_invoke$arity$1(G__20202) : grog_web.md.inline.call(null, G__20202));
})()),(j + (2))], null);
} else {
return null;
}
} else {
return null;
}
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(one,"`")){
var run = (function (){var k = i;
while(true){
if((((k < n)) && (cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2("`",cljs.core.nth.cljs$core$IFn$_invoke$arity$2(s,k))))){
var G__20662 = (k + (1));
k = G__20662;
continue;
} else {
return (k - i);
}
break;
}
})();
var tick = cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.str,cljs.core.repeat.cljs$core$IFn$_invoke$arity$2(run,"`"));
var temp__5823__auto__ = clojure.string.index_of.cljs$core$IFn$_invoke$arity$3(s,tick,(i + run));
if(cljs.core.truth_(temp__5823__auto__)){
var j = temp__5823__auto__;
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [grog_web.md.code_span(cljs.core.subs.cljs$core$IFn$_invoke$arity$3(s,(i + run),j)),(j + run)], null);
} else {
return null;
}
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(one,"*")){
var temp__5823__auto__ = clojure.string.index_of.cljs$core$IFn$_invoke$arity$3(s,"*",(i + (1)));
if(cljs.core.truth_(temp__5823__auto__)){
var j = temp__5823__auto__;
if((((j > (i + (1)))) && ((((!(space_QMARK_((i + (1)))))) && ((!(space_QMARK_((j - (1)))))))))){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"em","em",707813035)], null),(function (){var G__20272 = cljs.core.subs.cljs$core$IFn$_invoke$arity$3(s,(i + (1)),j);
return (grog_web.md.inline.cljs$core$IFn$_invoke$arity$1 ? grog_web.md.inline.cljs$core$IFn$_invoke$arity$1(G__20272) : grog_web.md.inline.call(null, G__20272));
})()),(j + (1))], null);
} else {
return null;
}
} else {
return null;
}
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(one,"_")){
if((((((i === (0))) || ((!(grog_web.md.alnum_QMARK_(cljs.core.nth.cljs$core$IFn$_invoke$arity$2(s,(i - (1))))))))) && ((!(space_QMARK_((i + (1)))))))){
var temp__5823__auto__ = clojure.string.index_of.cljs$core$IFn$_invoke$arity$3(s,"_",(i + (1)));
if(cljs.core.truth_(temp__5823__auto__)){
var j = temp__5823__auto__;
if((((j > (i + (1)))) && ((((!(space_QMARK_((j - (1)))))) && (((((j + (1)) >= n)) || ((!(grog_web.md.alnum_QMARK_(cljs.core.nth.cljs$core$IFn$_invoke$arity$2(s,(j + (1))))))))))))){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"em","em",707813035)], null),(function (){var G__20285 = cljs.core.subs.cljs$core$IFn$_invoke$arity$3(s,(i + (1)),j);
return (grog_web.md.inline.cljs$core$IFn$_invoke$arity$1 ? grog_web.md.inline.cljs$core$IFn$_invoke$arity$1(G__20285) : grog_web.md.inline.call(null, G__20285));
})()),(j + (1))], null);
} else {
return null;
}
} else {
return null;
}
} else {
return null;
}
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(one,"[")){
var temp__5823__auto__ = clojure.string.index_of.cljs$core$IFn$_invoke$arity$3(s,"](",(i + (1)));
if(cljs.core.truth_(temp__5823__auto__)){
var j = temp__5823__auto__;
var temp__5823__auto____$1 = clojure.string.index_of.cljs$core$IFn$_invoke$arity$3(s,")",(j + (2)));
if(cljs.core.truth_(temp__5823__auto____$1)){
var k = temp__5823__auto____$1;
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"a","a",-2123407586),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"href","href",-793805698),clojure.string.trim(cljs.core.subs.cljs$core$IFn$_invoke$arity$3(s,(j + (2)),k))], null)], null),(function (){var G__20293 = cljs.core.subs.cljs$core$IFn$_invoke$arity$3(s,(i + (1)),j);
return (grog_web.md.inline.cljs$core$IFn$_invoke$arity$1 ? grog_web.md.inline.cljs$core$IFn$_invoke$arity$1(G__20293) : grog_web.md.inline.call(null, G__20293));
})()),(k + (1))], null);
} else {
return null;
}
} else {
return null;
}
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
});
/**
 * Flatten a string of inline Markdown into a seq of hiccup nodes / strings.
 */
grog_web.md.inline = (function grog_web$md$inline(s){
var s__$1 = cljs.core.str.cljs$core$IFn$_invoke$arity$1(s);
var n = ((s__$1).length);
var i = (0);
var start = (0);
var out = cljs.core.PersistentVector.EMPTY;
while(true){
if((i >= n)){
if((start < n)){
return cljs.core.conj.cljs$core$IFn$_invoke$arity$2(out,cljs.core.subs.cljs$core$IFn$_invoke$arity$2(s__$1,start));
} else {
return out;
}
} else {
var temp__5821__auto__ = grog_web.md.inline_token(s__$1,i);
if(cljs.core.truth_(temp__5821__auto__)){
var vec__20323 = temp__5821__auto__;
var node = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20323,(0),null);
var ni = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20323,(1),null);
var out__$1 = (((start < i))?cljs.core.conj.cljs$core$IFn$_invoke$arity$2(out,cljs.core.subs.cljs$core$IFn$_invoke$arity$3(s__$1,start,i)):out);
var G__20663 = ni;
var G__20664 = ni;
var G__20665 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(out__$1,node);
i = G__20663;
start = G__20664;
out = G__20665;
continue;
} else {
var G__20666 = (i + (1));
var G__20667 = start;
var G__20668 = out;
i = G__20666;
start = G__20667;
out = G__20668;
continue;
}
}
break;
}
});
grog_web.md.fence_re = /^\s{0,3}(`{3,}|~{3,})\s*([^`]*)$/;
grog_web.md.heading_re = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
grog_web.md.hr_re = /^\s{0,3}(?:(?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,})$/;
grog_web.md.bullet_re = /^\s{0,3}[-*+]\s+(.*)$/;
grog_web.md.ol_re = /^\s{0,3}\d{1,9}[.)]\s+(.*)$/;
grog_web.md.quote_re = /^\s{0,3}>\s?(.*)$/;
grog_web.md.fence_open = (function grog_web$md$fence_open(line){
var temp__5823__auto__ = cljs.core.re_matches(grog_web.md.fence_re,line);
if(cljs.core.truth_(temp__5823__auto__)){
var m = temp__5823__auto__;
return new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"marker","marker",865118313),cljs.core.nth.cljs$core$IFn$_invoke$arity$2(m,(1)),new cljs.core.Keyword(null,"info","info",-317069002),clojure.string.trim(cljs.core.nth.cljs$core$IFn$_invoke$arity$2(m,(2)))], null);
} else {
return null;
}
});
grog_web.md.fence_close_re = (function grog_web$md$fence_close_re(p__20342){
var map__20344 = p__20342;
var map__20344__$1 = cljs.core.__destructure_map(map__20344);
var marker = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20344__$1,new cljs.core.Keyword(null,"marker","marker",865118313));
var c = cljs.core.subs.cljs$core$IFn$_invoke$arity$3(marker,(0),(1));
return cljs.core.re_pattern(["^\\s{0,3}",c,"{3,}\\s*$"].join(''));
});
grog_web.md.pipe_row_QMARK_ = (function grog_web$md$pipe_row_QMARK_(line){
return (((!(clojure.string.blank_QMARK_(line)))) && (clojure.string.includes_QMARK_(line,"|")));
});
grog_web.md.cell_text = (function grog_web$md$cell_text(s){
return clojure.string.replace(clojure.string.trim(s),"\\|","|");
});
/**
 * Split a table row into trimmed cells, dropping the outer pipes. Splits on
 *   unescaped `|`, but NOT on a pipe inside a `code span` (models routinely write
 *   `` `a|b` `` in a cell without escaping — GFM says you must, we're lenient);
 *   honours `\|` escapes. `line` may be nil (callers pass the *next* line, which
 *   doesn't exist on the last row).
 */
grog_web.md.split_cells = (function grog_web$md$split_cells(line){
var l = clojure.string.trim(cljs.core.str.cljs$core$IFn$_invoke$arity$1(line));
var l__$1 = ((clojure.string.starts_with_QMARK_(l,"|"))?cljs.core.subs.cljs$core$IFn$_invoke$arity$2(l,(1)):l);
var l__$2 = ((clojure.string.ends_with_QMARK_(l__$1,"|"))?cljs.core.subs.cljs$core$IFn$_invoke$arity$3(l__$1,(0),(((l__$1).length) - (1))):l__$1);
var n = ((l__$2).length);
var i = (0);
var start = (0);
var in_code_QMARK_ = false;
var out = cljs.core.PersistentVector.EMPTY;
while(true){
if((i >= n)){
return cljs.core.conj.cljs$core$IFn$_invoke$arity$2(out,grog_web.md.cell_text(cljs.core.subs.cljs$core$IFn$_invoke$arity$2(l__$2,start)));
} else {
var c = cljs.core.nth.cljs$core$IFn$_invoke$arity$2(l__$2,i);
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(c,"`")){
var G__20669 = (i + (1));
var G__20670 = start;
var G__20671 = cljs.core.not(in_code_QMARK_);
var G__20672 = out;
i = G__20669;
start = G__20670;
in_code_QMARK_ = G__20671;
out = G__20672;
continue;
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(c,"\\")){
var G__20673 = (i + (2));
var G__20674 = start;
var G__20675 = in_code_QMARK_;
var G__20676 = out;
i = G__20673;
start = G__20674;
in_code_QMARK_ = G__20675;
out = G__20676;
continue;
} else {
if(((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(c,"|")) && (cljs.core.not(in_code_QMARK_)))){
var G__20677 = (i + (1));
var G__20678 = (i + (1));
var G__20679 = in_code_QMARK_;
var G__20680 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(out,grog_web.md.cell_text(cljs.core.subs.cljs$core$IFn$_invoke$arity$3(l__$2,start,i)));
i = G__20677;
start = G__20678;
in_code_QMARK_ = G__20679;
out = G__20680;
continue;
} else {
var G__20681 = (i + (1));
var G__20682 = start;
var G__20683 = in_code_QMARK_;
var G__20684 = out;
i = G__20681;
start = G__20682;
in_code_QMARK_ = G__20683;
out = G__20684;
continue;

}
}
}
}
break;
}
});
grog_web.md.delimiter_row_QMARK_ = (function grog_web$md$delimiter_row_QMARK_(line){
var cells = grog_web.md.split_cells(line);
return ((cljs.core.seq(cells)) && (cljs.core.every_QMARK_((function (c){
return cljs.core.boolean$(cljs.core.re_matches(/:?-{1,}:?/,clojure.string.replace(c," ","")));
}),cells)));
});
grog_web.md.align_of = (function grog_web$md$align_of(c){
var c__$1 = clojure.string.replace(c," ","");
if(((clojure.string.starts_with_QMARK_(c__$1,":")) && (clojure.string.ends_with_QMARK_(c__$1,":")))){
return new cljs.core.Keyword(null,"center","center",-748944368);
} else {
if(clojure.string.starts_with_QMARK_(c__$1,":")){
return new cljs.core.Keyword(null,"left","left",-399115937);
} else {
if(clojure.string.ends_with_QMARK_(c__$1,":")){
return new cljs.core.Keyword(null,"right","right",-452581833);
} else {
return null;

}
}
}
});
/**
 * Split `ls` into (take-while pred ls) and the remainder.
 */
grog_web.md.collect = (function grog_web$md$collect(pred,ls){
var taken = cljs.core.PersistentVector.EMPTY;
var ls__$1 = ls;
while(true){
if(cljs.core.truth_((function (){var and__5000__auto__ = cljs.core.seq(ls__$1);
if(and__5000__auto__){
var G__20414 = cljs.core.first(ls__$1);
return (pred.cljs$core$IFn$_invoke$arity$1 ? pred.cljs$core$IFn$_invoke$arity$1(G__20414) : pred.call(null, G__20414));
} else {
return and__5000__auto__;
}
})())){
var G__20685 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(taken,cljs.core.first(ls__$1));
var G__20686 = cljs.core.rest(ls__$1);
taken = G__20685;
ls__$1 = G__20686;
continue;
} else {
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [taken,ls__$1], null);
}
break;
}
});
grog_web.md.parse_blocks = (function grog_web$md$parse_blocks(text){
var lines = clojure.string.split.cljs$core$IFn$_invoke$arity$3(cljs.core.str.cljs$core$IFn$_invoke$arity$1(text),/\n/,(-1));
var ls = lines;
var out = cljs.core.PersistentVector.EMPTY;
while(true){
if(cljs.core.not(cljs.core.seq(ls))){
return out;
} else {
var line = cljs.core.first(ls);
var more = cljs.core.rest(ls);
var nxt = cljs.core.first(more);
if(clojure.string.blank_QMARK_(line)){
var G__20687 = more;
var G__20688 = out;
ls = G__20687;
out = G__20688;
continue;
} else {
if(cljs.core.truth_(grog_web.md.fence_open(line))){
var fo = grog_web.md.fence_open(line);
var close_QMARK_ = grog_web.md.fence_close_re(fo);
var vec__20531 = grog_web.md.collect(((function (ls,out,fo,close_QMARK_,line,more,nxt,lines){
return (function (p1__20421_SHARP_){
return cljs.core.not(cljs.core.re_matches(close_QMARK_,p1__20421_SHARP_));
});})(ls,out,fo,close_QMARK_,line,more,nxt,lines))
,more);
var body = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20531,(0),null);
var rest_ls = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20531,(1),null);
var rest_ls__$1 = ((cljs.core.seq(rest_ls))?cljs.core.rest(rest_ls):rest_ls);
var G__20689 = rest_ls__$1;
var G__20690 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(out,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"pre","pre",2118456869),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"code","code",1586293142),clojure.string.join.cljs$core$IFn$_invoke$arity$2("\n",body)], null)], null));
ls = G__20689;
out = G__20690;
continue;
} else {
if(cljs.core.truth_(cljs.core.re_matches(grog_web.md.heading_re,line))){
var m = cljs.core.re_matches(grog_web.md.heading_re,line);
var lvl = cljs.core.count(cljs.core.nth.cljs$core$IFn$_invoke$arity$2(m,(1)));
var txt = cljs.core.nth.cljs$core$IFn$_invoke$arity$2(m,(2));
var G__20691 = more;
var G__20692 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(out,cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.core.keyword.cljs$core$IFn$_invoke$arity$1(["h",cljs.core.str.cljs$core$IFn$_invoke$arity$1(lvl)].join(''))], null),grog_web.md.inline(txt)));
ls = G__20691;
out = G__20692;
continue;
} else {
if(((grog_web.md.pipe_row_QMARK_(line)) && (grog_web.md.delimiter_row_QMARK_(nxt)))){
var head = grog_web.md.split_cells(line);
var aligns = cljs.core.mapv.cljs$core$IFn$_invoke$arity$2(grog_web.md.align_of,grog_web.md.split_cells(nxt));
var vec__20540 = grog_web.md.collect(grog_web.md.pipe_row_QMARK_,more);
var rows = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20540,(0),null);
var rest_ls = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20540,(1),null);
var body = cljs.core.rest(rows);
var cell = ((function (ls,out,head,aligns,vec__20540,rows,rest_ls,body,line,more,nxt,lines){
return (function (tag,c,a){
if(cljs.core.truth_(a)){
return cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [tag,new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"style","style",-496642736),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"text-align","text-align",1786091845),cljs.core.name(a)], null)], null)], null),grog_web.md.inline(c));
} else {
return cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [tag], null),grog_web.md.inline(c));
}
});})(ls,out,head,aligns,vec__20540,rows,rest_ls,body,line,more,nxt,lines))
;
var G__20693 = rest_ls;
var G__20694 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(out,new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"table","table",-564943036),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"thead","thead",-291875296),cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"tr","tr",-1424774646)], null),cljs.core.mapv.cljs$core$IFn$_invoke$arity$3(((function (ls,out,head,aligns,vec__20540,rows,rest_ls,body,cell,line,more,nxt,lines){
return (function (i,c){
return cell(new cljs.core.Keyword(null,"th","th",-545608566),c,cljs.core.nth.cljs$core$IFn$_invoke$arity$3(aligns,i,null));
});})(ls,out,head,aligns,vec__20540,rows,rest_ls,body,cell,line,more,nxt,lines))
,cljs.core.range.cljs$core$IFn$_invoke$arity$0(),head))], null),cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"tbody","tbody",-80678300)], null),cljs.core.mapv.cljs$core$IFn$_invoke$arity$2(((function (ls,out,head,aligns,vec__20540,rows,rest_ls,body,cell,line,more,nxt,lines){
return (function (r){
return cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"tr","tr",-1424774646)], null),cljs.core.mapv.cljs$core$IFn$_invoke$arity$3(((function (ls,out,head,aligns,vec__20540,rows,rest_ls,body,cell,line,more,nxt,lines){
return (function (i,c){
return cell(new cljs.core.Keyword(null,"td","td",1479933353),c,cljs.core.nth.cljs$core$IFn$_invoke$arity$3(aligns,i,null));
});})(ls,out,head,aligns,vec__20540,rows,rest_ls,body,cell,line,more,nxt,lines))
,cljs.core.range.cljs$core$IFn$_invoke$arity$0(),grog_web.md.split_cells(r)));
});})(ls,out,head,aligns,vec__20540,rows,rest_ls,body,cell,line,more,nxt,lines))
,body))], null));
ls = G__20693;
out = G__20694;
continue;
} else {
if(cljs.core.truth_(cljs.core.re_matches(grog_web.md.hr_re,line))){
var G__20695 = more;
var G__20696 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(out,new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"hr","hr",1377740067)], null));
ls = G__20695;
out = G__20696;
continue;
} else {
if(cljs.core.truth_(cljs.core.re_matches(grog_web.md.bullet_re,line))){
var vec__20553 = grog_web.md.collect(((function (ls,out,line,more,nxt,lines){
return (function (p1__20427_SHARP_){
return cljs.core.re_matches(grog_web.md.bullet_re,p1__20427_SHARP_);
});})(ls,out,line,more,nxt,lines))
,cljs.core.cons(line,more));
var items = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20553,(0),null);
var rest_ls = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20553,(1),null);
var G__20697 = rest_ls;
var G__20698 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(out,cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"ul","ul",-1349521403)], null),cljs.core.mapv.cljs$core$IFn$_invoke$arity$2(((function (ls,out,vec__20553,items,rest_ls,line,more,nxt,lines){
return (function (l){
return cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"li","li",723558921)], null),grog_web.md.inline(cljs.core.nth.cljs$core$IFn$_invoke$arity$2(cljs.core.re_matches(grog_web.md.bullet_re,l),(1))));
});})(ls,out,vec__20553,items,rest_ls,line,more,nxt,lines))
,items)));
ls = G__20697;
out = G__20698;
continue;
} else {
if(cljs.core.truth_(cljs.core.re_matches(grog_web.md.ol_re,line))){
var vec__20563 = grog_web.md.collect(((function (ls,out,line,more,nxt,lines){
return (function (p1__20430_SHARP_){
return cljs.core.re_matches(grog_web.md.ol_re,p1__20430_SHARP_);
});})(ls,out,line,more,nxt,lines))
,cljs.core.cons(line,more));
var items = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20563,(0),null);
var rest_ls = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20563,(1),null);
var G__20699 = rest_ls;
var G__20700 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(out,cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"ol","ol",932524051)], null),cljs.core.mapv.cljs$core$IFn$_invoke$arity$2(((function (ls,out,vec__20563,items,rest_ls,line,more,nxt,lines){
return (function (l){
return cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"li","li",723558921)], null),grog_web.md.inline(cljs.core.nth.cljs$core$IFn$_invoke$arity$2(cljs.core.re_matches(grog_web.md.ol_re,l),(1))));
});})(ls,out,vec__20563,items,rest_ls,line,more,nxt,lines))
,items)));
ls = G__20699;
out = G__20700;
continue;
} else {
if(cljs.core.truth_(cljs.core.re_matches(grog_web.md.quote_re,line))){
var vec__20570 = grog_web.md.collect(((function (ls,out,line,more,nxt,lines){
return (function (p1__20433_SHARP_){
return cljs.core.re_matches(grog_web.md.quote_re,p1__20433_SHARP_);
});})(ls,out,line,more,nxt,lines))
,cljs.core.cons(line,more));
var items = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20570,(0),null);
var rest_ls = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20570,(1),null);
var inner = clojure.string.join.cljs$core$IFn$_invoke$arity$2("\n",cljs.core.map.cljs$core$IFn$_invoke$arity$2(((function (ls,out,vec__20570,items,rest_ls,line,more,nxt,lines){
return (function (p1__20434_SHARP_){
return cljs.core.nth.cljs$core$IFn$_invoke$arity$2(cljs.core.re_matches(grog_web.md.quote_re,p1__20434_SHARP_),(1));
});})(ls,out,vec__20570,items,rest_ls,line,more,nxt,lines))
,items));
var G__20701 = rest_ls;
var G__20702 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(out,cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"blockquote","blockquote",372264190)], null),(grog_web.md.parse_blocks.cljs$core$IFn$_invoke$arity$1 ? grog_web.md.parse_blocks.cljs$core$IFn$_invoke$arity$1(inner) : grog_web.md.parse_blocks.call(null, inner))));
ls = G__20701;
out = G__20702;
continue;
} else {
var starts_QMARK_ = ((function (ls,out,line,more,nxt,lines){
return (function (l,n){
var or__5002__auto__ = clojure.string.blank_QMARK_(l);
if(or__5002__auto__){
return or__5002__auto__;
} else {
var or__5002__auto____$1 = grog_web.md.fence_open(l);
if(cljs.core.truth_(or__5002__auto____$1)){
return or__5002__auto____$1;
} else {
var or__5002__auto____$2 = cljs.core.re_matches(grog_web.md.heading_re,l);
if(cljs.core.truth_(or__5002__auto____$2)){
return or__5002__auto____$2;
} else {
var or__5002__auto____$3 = cljs.core.re_matches(grog_web.md.hr_re,l);
if(cljs.core.truth_(or__5002__auto____$3)){
return or__5002__auto____$3;
} else {
var or__5002__auto____$4 = cljs.core.re_matches(grog_web.md.bullet_re,l);
if(cljs.core.truth_(or__5002__auto____$4)){
return or__5002__auto____$4;
} else {
var or__5002__auto____$5 = cljs.core.re_matches(grog_web.md.ol_re,l);
if(cljs.core.truth_(or__5002__auto____$5)){
return or__5002__auto____$5;
} else {
var or__5002__auto____$6 = cljs.core.re_matches(grog_web.md.quote_re,l);
if(cljs.core.truth_(or__5002__auto____$6)){
return or__5002__auto____$6;
} else {
return ((grog_web.md.pipe_row_QMARK_(l)) && (grog_web.md.delimiter_row_QMARK_(n)));
}
}
}
}
}
}
}
});})(ls,out,line,more,nxt,lines))
;
var vec__20588 = (function (){var cur = cljs.core.cons(line,more);
var acc = cljs.core.PersistentVector.EMPTY;
while(true){
if(cljs.core.not(cljs.core.seq(cur))){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [acc,cljs.core.PersistentVector.EMPTY], null);
} else {
var l = cljs.core.first(cur);
if(cljs.core.truth_(starts_QMARK_(l,cljs.core.second(cur)))){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [acc,cur], null);
} else {
var G__20703 = cljs.core.rest(cur);
var G__20704 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(acc,l);
cur = G__20703;
acc = G__20704;
continue;
}
}
break;
}
})();
var para = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20588,(0),null);
var rest_ls = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20588,(1),null);
var G__20705 = rest_ls;
var G__20706 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(out,cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"p","p",151049309)], null),grog_web.md.inline(clojure.string.join.cljs$core$IFn$_invoke$arity$2(" ",para))));
ls = G__20705;
out = G__20706;
continue;

}
}
}
}
}
}
}
}
}
break;
}
});
/**
 * Render `md` (a Markdown string) to a vector of hiccup blocks. `nil` is treated
 *   as empty (a transcript segment can carry no text at all).
 */
grog_web.md.__GT_hiccup = (function grog_web$md$__GT_hiccup(md){
return grog_web.md.parse_blocks(grog_web.md.strip_wrappers((function (){var or__5002__auto__ = md;
if(cljs.core.truth_(or__5002__auto__)){
return or__5002__auto__;
} else {
return "";
}
})()));
});

//# sourceMappingURL=grog_web.md.js.map
