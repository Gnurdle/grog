
shadow.cljs.devtools.client.env.module_loaded('main');

try { grog_web.core.init(); } catch (e) { console.error("An error occurred when calling (grog-web.core/init)"); console.error(e); }