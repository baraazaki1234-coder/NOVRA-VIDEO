(function () {
  "use strict";
  const capacitor = window.Capacitor;
  let plugin = capacitor?.Plugins?.NovraMedia || null;
  if (!plugin && typeof capacitor?.registerPlugin === "function") {
    try { plugin = capacitor.registerPlugin("NovraMedia"); } catch (error) {
      console.warn("NOVRA native plugin registration failed:", error);
    }
  }
  window.NovraMedia = plugin;
  window.__novraNativePluginReady = Promise.resolve(plugin);
})();
