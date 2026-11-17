// Who is calling, for rate limits. The server sits behind Railway's proxy and, for soqzilla.com, Cloudflare
// in front of that. The right-most X-Forwarded-For entry is the address that connected to Railway. If that
// is a Cloudflare address, the real caller is in CF-Connecting-IP; from anywhere else that header is ignored,
// so nobody can pick their own rate-limit bucket by going to the Railway address directly.
import net from "node:net";

// Cloudflare's published ranges (cloudflare.com/ips), refreshed from the live list at startup
let ranges = [
  "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18", "108.162.192.0/18",
  "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
  "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
  "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32",
];
let list = build(ranges);

function build(cidrs) {
  const bl = new net.BlockList();
  for (const c of cidrs) {
    const [addr, bits] = c.trim().split("/");
    if (!addr || !bits) continue;
    try { bl.addSubnet(addr, Number(bits), addr.includes(":") ? "ipv6" : "ipv4"); } catch {}
  }
  return bl;
}

export async function refreshCloudflareRanges() {
  try {
    const get = (u) => fetch(u, { signal: AbortSignal.timeout(10_000) }).then((r) => (r.ok ? r.text() : ""));
    const [v4, v6] = await Promise.all([get("https://www.cloudflare.com/ips-v4"), get("https://www.cloudflare.com/ips-v6")]);
    const fresh = (v4 + "\n" + v6).split(/\s+/).filter((s) => /^[0-9a-f.:]+\/\d+$/i.test(s));
    if (fresh.length >= 10) { ranges = fresh; list = build(ranges); }
  } catch {}
  return ranges.length;
}

const strip = (ip) => (ip ?? "").trim().replace(/^::ffff:/, "");

export function isCloudflare(ip) {
  const a = strip(ip);
  const type = net.isIP(a);
  if (!type) return false;
  return list.check(a, type === 6 ? "ipv6" : "ipv4");
}

export function clientIp(req) {
  const peer = strip((req.headers["x-forwarded-for"] ?? req.socket.remoteAddress ?? "").split(",").pop());
  const cf = strip(req.headers["cf-connecting-ip"]);
  if (cf && net.isIP(cf) && isCloudflare(peer)) return cf;
  return peer;
}
