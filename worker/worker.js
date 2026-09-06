/**
 * Boss bug sink. The phone POSTs diagnostics here; Claude reads them back.
 *
 * Deliberately tiny: a write token the app carries, a separate read token that
 * never leaves this machine, a hard size cap, and a 30-day TTL so nothing
 * accumulates. Worst case if the app's token is pulled out of the APK is junk
 * rows, which expire.
 */
const MAX = 60_000;
const clean = (s, n) => (s || "").replace(/[^\w.-]/g, "").slice(0, n);

export default {
  async fetch(req, env) {
    const url = new URL(req.url);

    if (url.pathname === "/r" && req.method === "POST") {
      if (req.headers.get("x-boss-key") !== env.WRITE_KEY) {
        return new Response("nope", { status: 401 });
      }
      const body = await req.text();
      if (!body || body.length > MAX) return new Response("bad size", { status: 413 });
      const dev = clean(url.searchParams.get("d"), 16) || "unknown";
      const kind = clean(url.searchParams.get("k"), 16) || "log";
      // 13-digit millis sort lexicographically, so list() comes back in order.
      await env.BUGS.put(`r:${Date.now()}:${dev}:${kind}`, body, {
        expirationTtl: 60 * 60 * 24 * 30,
      });
      return new Response(null, { status: 204 });
    }

    if (url.pathname === "/r" && req.method === "GET") {
      if (url.searchParams.get("t") !== env.READ_KEY) {
        return new Response("nope", { status: 401 });
      }
      const n = Math.min(Number(url.searchParams.get("n") || 15), 50);
      const list = await env.BUGS.list({ prefix: "r:", limit: 1000 });
      const keys = list.keys.map((k) => k.name).sort().reverse().slice(0, n);
      const out = [];
      for (const k of keys) out.push({ key: k, body: await env.BUGS.get(k) });
      return Response.json(out);
    }

    return new Response("boss bug sink", { status: 200 });
  },
};
