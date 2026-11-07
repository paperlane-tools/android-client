(function () {
  "use strict";

  if (window.__YZJ_ANDROID_SHIM__) return;
  window.__YZJ_ANDROID_SHIM__ = true;

  var pending = {};
  var seq = 0;

  function native() {
    return window.YzjNative || null;
  }

  function log(level, text) {
    try {
      var n = native();
      if (n && n.log) n.log(level, String(text));
    } catch (e) { /* ignore */ }
  }

  window.__yzjXhrDone = function (id, status, text, headers) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    try {
      if (p.onload) p.onload({
        status: Number(status),
        responseText: text || "",
        responseHeaders: headers || "",
        finalUrl: p.url
      });
    } catch (e) {
      log("error", "onload failed: " + e);
    }
  };

  window.__yzjXhrFail = function (id, message) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    var isTimeout = /timeout|timed out/i.test(String(message));
    try {
      if (isTimeout && p.ontimeout) p.ontimeout({ error: message });
      else if (p.onerror) p.onerror({ error: message });
    } catch (e) {
      log("error", "onerror failed: " + e);
    }
  };

  window.GM_getValue = function (k, dflt) {
    try {
      var n = native();
      var raw = n && n.getValue ? n.getValue(String(k)) : null;
      if (raw === null || raw === undefined) return dflt;
      try { return JSON.parse(raw); } catch (e) { return raw; }
    } catch (e) {
      return dflt;
    }
  };

  window.GM_setValue = function (k, v) {
    try {
      var n = native();
      if (n && n.setValue) n.setValue(String(k), JSON.stringify(v === undefined ? null : v));
    } catch (e) { /* ignore */ }
  };

  window.GM_setClipboard = function (text) {
    try {
      var n = native();
      if (n && n.setClipboard) n.setClipboard(String(text));
    } catch (e) { /* ignore */ }
  };

  window.GM_xmlhttpRequest = function (opts) {
    opts = opts || {};
    var id = "x" + (++seq) + "_" + Date.now();
    pending[id] = opts;
    var headers = opts.headers || {};
    try {
      var n = native();
      if (!n || !n.xhr) throw new Error("native bridge missing");
      n.xhr(id,
        String(opts.method || "GET"),
        String(opts.url || ""),
        JSON.stringify(headers),
        opts.data === undefined || opts.data === null ? null : String(opts.data),
        Number(opts.timeout || 30000));
    } catch (e) {
      setTimeout(function () { window.__yzjXhrFail(id, String(e)); }, 0);
    }
    return {
      abort: function () {
        try {
          var n2 = native();
          if (n2 && n2.abortXhr) n2.abortXhr(id);
        } catch (e) { /* ignore */ }
        var p = pending[id];
        if (p) {
          delete pending[id];
          try { if (p.onabort) p.onabort({}); } catch (e) { /* ignore */ }
        }
      }
    };
  };

  window.addEventListener("error", function (e) {
    log("page-error", (e && e.message ? e.message : "unknown")
      + " @ " + (e && e.filename ? e.filename : "") + ":" + (e && e.lineno ? e.lineno : "?"));
  });

  window.addEventListener("unhandledrejection", function (e) {
    log("page-rejection", String((e && e.reason) || "unknown"));
  });

  // WebView does not download blob:/data: URLs from <a download>.
  // The userscripts use that for log export and CSV export, so capture the
  // click and persist the text through the native bridge.
  document.addEventListener("click", function (ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest("a[download]") : null;
    if (!a) return;
    var href = a.href || "";
    if (href.indexOf("blob:") !== 0 && href.indexOf("data:") !== 0) return;
    ev.preventDefault();
    ev.stopPropagation();
    fetch(href).then(function (r) { return r.text(); }).then(function (text) {
      var n = native();
      if (n && n.saveText) n.saveText(a.download || "yzj-export.txt", text);
      else log("error", "native saveText missing");
    }).catch(function (e) {
      log("error", "export save failed: " + e);
    });
  }, true);

  log("info", "GM shim ready, slot=" + (window.__YZJ_SLOT === undefined ? "?" : window.__YZJ_SLOT));
})();
