/** grog web client — Tailwind. Skin borrowed from cms-estimate
 *  (slate-950 shell, sky-500 accent); role colours come from grog.edn via CSS
 *  vars in input.css, NOT from this config (see web-client-plan.md §2). */
module.exports = {
  content: ["./resources/public/index.html", "./src/renderer/**/*.{cljs,cljc}"],
  theme: { extend: {} },
  plugins: [],
};