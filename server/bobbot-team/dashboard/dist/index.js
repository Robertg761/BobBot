(function () {
  "use strict";
  const sdk = window.__HERMES_PLUGIN_SDK__;
  if (!sdk || !window.__HERMES_PLUGINS__) return;
  const h = sdk.React.createElement;
  const { useState, useEffect } = sdk.hooks;
  const { Card, CardContent, Button } = sdk.components;
  const api = "/api/plugins/bobbot-team";
  /** The dashboard knows its own address; the phone only has to read it off the screen. */
  function PairCard() {
    const [pair, setPair] = useState(null);
    const [error, setError] = useState("");
    useEffect(function () {
      let active = true;
      sdk.fetchJSON(api + "/pair?origin=" + encodeURIComponent(window.location.origin))
        .then(function (result) { if (active) setPair(result); })
        .catch(function (e) { if (active) setError(e.message); });
      return function () { active = false; };
    }, []);
    if (error) {
      return h(Card, null, h(CardContent, { className: "space-y-2 pt-4" },
        h("h2", { className: "font-semibold" }, "Pair your phone"),
        h("p", { role: "alert" }, error)));
    }
    if (!pair) return null;
    // Four light modules of quiet zone on every side, or a camera will not see the code at all.
    const span = pair.modules.length + 8;
    const dark = [];
    pair.modules.forEach(function (row, y) {
      row.forEach(function (on, x) {
        if (on) dark.push(h("rect", { key: y + ":" + x, x: x + 4, y: y + 4, width: 1, height: 1, fill: "#000000" }));
      });
    });
    return h(Card, null, h(CardContent, { className: "space-y-3 pt-4" },
      h("h2", { className: "font-semibold" }, "Pair your phone"),
      h("svg", {
        width: 220, height: 220, viewBox: "0 0 " + span + " " + span,
        shapeRendering: "crispEdges", role: "img", "aria-label": "BobBot pairing QR code"
      }, h("rect", { x: 0, y: 0, width: span, height: span, fill: "#ffffff" }), dark),
      h("p", { className: "text-sm" }, "Scan with your phone's camera, or open this link on the phone."),
      h("code", { className: "break-all text-xs" }, pair.link)));
  }

  function TeamPage() {
    const [data, setData] = useState(null);
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);
    useEffect(function () {
      let active = true;
      let timer;
      async function refresh() {
        try {
          const result = await sdk.fetchJSON(api + "/team");
          if (active) { setData(result); setError(""); }
        } catch (e) { if (active) setError(e.message); }
        if (active) timer = setTimeout(refresh, 5000);
      }
      refresh();
      return function () { active = false; clearTimeout(timer); };
    }, []);
    async function decide(id, choice) {
      setBusy(true);
      try {
        await sdk.fetchJSON(api + "/requests/" + encodeURIComponent(id) + "/decide", {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ choice: choice, reason: "Reviewed by Robert in the Hermes dashboard" })
        });
        setData(await sdk.fetchJSON(api + "/team"));
        setError("");
      } catch (e) { setError(e.message); }
      finally { setBusy(false); }
    }
    return h("div", { className: "space-y-4 p-6" },
      h("h1", { className: "text-2xl font-semibold" }, "BobBot team"),
      h("p", null, "Authority: " + (data ? data.authority : "loading")),
      h("p", null, "Manage the team in BobBot → Network → Permissions. Review pending requests here or on your phone."),
      h(PairCard, null),
      error && h("p", { role: "alert" }, error),
      ...(data ? data.requests : []).map(function (r) {
        return h(Card, { key: r.id }, h(CardContent, { className: "space-y-3 pt-4" },
          h("h2", { className: "font-semibold" }, r.profile + " · " + r.tool + " · " + r.status),
          h("pre", { className: "whitespace-pre-wrap break-all text-sm" }, r.args),
          h("p", null, r.reason),
          ["pending", "needs_user"].includes(r.status) && h("div", { className: "flex gap-2" },
            h(Button, { disabled: busy, onClick: function () { decide(r.id, "approved"); } }, "Allow once"),
            h(Button, { disabled: busy, variant: "outline", onClick: function () { decide(r.id, "denied"); } }, "Deny"))));
      }));
  }
  window.__HERMES_PLUGINS__.register("bobbot-team", TeamPage);
})();
